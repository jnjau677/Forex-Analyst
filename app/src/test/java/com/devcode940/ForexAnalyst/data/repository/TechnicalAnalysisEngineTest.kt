package com.devcode940.ForexAnalyst.data.repository

import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.IndicatorSettings
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.SignalType
import com.devcode940.ForexAnalyst.data.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * Unit tests for the pure technical-analysis math.
 *
 * WHY: Every bug found in review (MACD histogram misalignment, ATR off-by-one,
 * EMA length mismatch, RSI=100 on a dead market, hardcoded EURUSD) is pinned
 * by a test here so it cannot silently regress.
 */
class TechnicalAnalysisEngineTest {

    private val baseTime: Instant = Instant.parse("2024-01-01T00:00:00Z")

    // ---------------------------------------------------------------- EMA

    @Test
    fun `ema is index aligned with input and never longer`() {
        // WHY (regression): the old implementation returned period-1 nulls
        // PLUS the seed SMA even when data.size < period, producing a list
        // longer than the candle list and shifting every chart overlay.
        val short = TechnicalAnalysisEngine.calculateEMA(listOf(1.0, 2.0, 3.0), period = 20)
        assertEquals(3, short.size)
        assertTrue(short.all { it == null })

        val exact = TechnicalAnalysisEngine.calculateEMA((1..30).map { it.toDouble() }, period = 20)
        assertEquals(30, exact.size)
        assertTrue(exact.take(19).all { it == null })
        assertNotNull(exact[19])
    }

    @Test
    fun `ema computes hand verifiable values`() {
        // data = [1,2,3,4], period = 2 -> seed SMA(1,2)=1.5, multiplier=2/3
        // i=2: (3-1.5)*2/3+1.5 = 2.5 ; i=3: (4-2.5)*2/3+2.5 = 3.5
        val ema = TechnicalAnalysisEngine.calculateEMA(listOf(1.0, 2.0, 3.0, 4.0), period = 2)
        assertEquals(4, ema.size)
        assertNull(ema[0])
        assertEquals(1.5, ema[1] ?: Double.NaN, 1e-9)
        assertEquals(2.5, ema[2] ?: Double.NaN, 1e-9)
        assertEquals(3.5, ema[3] ?: Double.NaN, 1e-9)
    }

    @Test
    fun `ema of empty input is empty`() {
        assertTrue(TechnicalAnalysisEngine.calculateEMA(emptyList(), 20).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ema rejects period below two`() {
        // WHY: period 1 makes the multiplier 1.0 (EMA == price), which is a
        // caller bug, not a valid configuration.
        TechnicalAnalysisEngine.calculateEMA(listOf(1.0, 2.0), period = 1)
    }

    // ---------------------------------------------------------------- RSI

    @Test
    fun `rsi is index aligned with leading nulls`() {
        val closes = (1..40).map { it.toDouble() }
        val rsi = TechnicalAnalysisEngine.calculateRSI(closes, period = 14)
        assertEquals(closes.size, rsi.size)
        assertTrue(rsi.take(14).all { it == null })
        assertNotNull(rsi[14])
    }

    @Test
    fun `rsi of a flat market is neutral fifty not one hundred`() {
        // WHY (regression): the old code divided by avgLoss == 0 and reported
        // RSI 100 for a dead market, which could fire a false SELL signal.
        val rsi = TechnicalAnalysisEngine.calculateRSI(List(30) { 1.1 }, period = 14)
        rsi.filterNotNull().forEach { assertEquals(50.0, it, 1e-9) }
    }

    @Test
    fun `rsi is one hundred for a pure uptrend and zero for a pure downtrend`() {
        val up = TechnicalAnalysisEngine.calculateRSI((1..30).map { it.toDouble() }, 14)
        assertEquals(100.0, up.last() ?: Double.NaN, 1e-9)

        val down = TechnicalAnalysisEngine.calculateRSI((1..30).map { (31 - it).toDouble() }, 14)
        assertEquals(0.0, down.last() ?: Double.NaN, 1e-9)
    }

    @Test
    fun `rsi computes hand verifiable value`() {
        // closes=[10,11,10.5], period=2 -> avgGain=0.5, avgLoss=0.25,
        // rs=2 -> RSI = 100 - 100/3 = 66.666...
        val rsi = TechnicalAnalysisEngine.calculateRSI(listOf(10.0, 11.0, 10.5), period = 2)
        assertEquals(3, rsi.size)
        assertEquals(66.666666, rsi[2] ?: Double.NaN, 1e-5)
    }

    @Test
    fun `rsi returns all nulls when there is not enough data`() {
        val rsi = TechnicalAnalysisEngine.calculateRSI(listOf(1.0, 2.0, 3.0), period = 14)
        assertEquals(3, rsi.size)
        assertTrue(rsi.all { it == null })
        assertNull(TechnicalAnalysisEngine.calculateLatestRSI(listOf(1.0, 2.0), 14))
    }

    // ---------------------------------------------------------------- MACD

    @Test
    fun `macd lines and histogram are consistently sized and aligned`() {
        val closes = (1..120).map { 1.1 + it * 0.0005 }
        val result = TechnicalAnalysisEngine.calculateMACD(closes, 12, 26, 9)

        // WHY: macdLine starts once the slow EMA is seeded: n - (slow - 1).
        assertEquals(120 - 25, result.macdLine.size)
        // signalLine needs signalPeriod MACD values.
        assertEquals(result.macdLine.size - 8, result.signalLine.size)
        assertEquals(result.signalLine.size, result.histogram.size)

        // WHY (critical regression): the old code zipped macdLine from index 0
        // with signalLine, so the histogram was shifted by signalPeriod-1 bars
        // and every crossover signal was computed on the wrong candle.
        val expectedLast = result.macdLine.last() - result.signalLine.last()
        assertEquals(expectedLast, result.histogram.last(), 1e-12)
    }

    @Test
    fun `macd of empty input is empty`() {
        val result = TechnicalAnalysisEngine.calculateMACD(emptyList())
        assertTrue(result.macdLine.isEmpty())
        assertTrue(result.signalLine.isEmpty())
        assertTrue(result.histogram.isEmpty())
    }

    // ---------------------------------------------------------------- ATR

    @Test
    fun `atr is index aligned with the candle list`() {
        val candles = buildCandles((1..30).map { 1.1 + it * 0.001 })
        val atr = TechnicalAnalysisEngine.calculateATR(candles, period = 14)
        // WHY (regression): the old ATR list was one element shorter than the
        // candle list and shifted by one bar.
        assertEquals(candles.size, atr.size)
        assertTrue(atr.take(14).all { it == null })
        assertNotNull(atr[14])
    }

    @Test
    fun `atr computes hand verifiable value`() {
        // c0: o=10 h=11 l=9 c=10 ; c1: o=10 h=12 l=10 c=11 -> TR=2
        // c2: o=11 h=11.5 l=10 c=10.5 -> TR=max(1.5, 0.5, 1)=1.5
        // period=2 -> ATR[2] = (2 + 1.5)/2 = 1.75
        val candles = listOf(
            CandleStick(baseTime, open = 10.0, high = 11.0, low = 9.0, close = 10.0),
            CandleStick(baseTime.plusSeconds(3600), open = 10.0, high = 12.0, low = 10.0, close = 11.0),
            CandleStick(baseTime.plusSeconds(7200), open = 11.0, high = 11.5, low = 10.0, close = 10.5)
        )
        val atr = TechnicalAnalysisEngine.calculateATR(candles, period = 2)
        assertEquals(3, atr.size)
        assertEquals(1.75, atr[2] ?: Double.NaN, 1e-9)
    }

    @Test
    fun `atr is zero for a market with no movement`() {
        val flat = (1..30).map { baseTime.plusSeconds(it * 3600L) }.map { instant ->
            CandleStick(instant, open = 1.1, high = 1.1, low = 1.1, close = 1.1)
        }
        assertEquals(0.0, TechnicalAnalysisEngine.calculateLatestATR(flat, 14) ?: -1.0, 1e-12)
    }

    // ------------------------------------------------------------ Signals

    @Test
    fun `generates a BUY when rsi is oversold and macd momentum turns up`() {
        // WHY this shape: 200 steady down-bars push Wilder RSI to ~13, and the
        // final +0.02 reversal bar lifts the MACD line above its signal line,
        // so the histogram is positive — exactly the documented BUY condition.
        val candles = trendingCandles(start = 3.20, step = -0.01, bars = 200, finalStep = 0.02)
        val signals = TechnicalAnalysisEngine.generateSignals(
            candles = candles,
            pairSymbol = PairSymbol("GBPJPY"),
            timeframe = Timeframe("H4"),
            settings = IndicatorSettings.default()
        )

        assertEquals(1, signals.size)
        val signal = signals.first()
        assertEquals(SignalType.BUY, signal.signalType)
        // WHY (regression): the old engine hardcoded EURUSD, so signals for
        // every other pair were saved under the wrong symbol.
        assertEquals(PairSymbol("GBPJPY"), signal.pairSymbol)
        assertEquals(Timeframe("H4"), signal.timeframe)
        // WHY (regression): signals were stamped Instant.now(), which broke
        // dedup on rescan; they must carry the candle's own timestamp.
        assertEquals(candles.last().time, signal.timestamp)

        val atr = 0.014714285714285714
        val entry = candles.last().close
        assertEquals(entry, signal.entryPrice, 1e-9)
        assertEquals(entry - atr * 1.5, signal.stopLoss, 1e-6)
        assertEquals(entry + atr * 2.0, signal.takeProfit1, 1e-6)
        assertEquals(entry + atr * 3.0, signal.takeProfit2, 1e-6)
        assertEquals(2.0 / 1.5, signal.riskRewardRatio, 1e-9)
        assertTrue(signal.confidenceScore in 0.0..1.0)
    }

    @Test
    fun `generates a SELL when rsi is overbought and macd momentum turns down`() {
        val candles = trendingCandles(start = 1.20, step = 0.01, bars = 200, finalStep = -0.02)
        val signals = TechnicalAnalysisEngine.generateSignals(
            candles = candles,
            pairSymbol = PairSymbol("EURUSD"),
            timeframe = Timeframe("H1"),
            settings = IndicatorSettings.default()
        )

        assertEquals(1, signals.size)
        val signal = signals.first()
        assertEquals(SignalType.SELL, signal.signalType)
        val entry = candles.last().close
        val atr = 0.014714285714285714
        assertEquals(entry + atr * 1.5, signal.stopLoss, 1e-6)
        assertEquals(entry - atr * 2.0, signal.takeProfit1, 1e-6)
        assertTrue(signal.stopLoss > entry)
        assertTrue(signal.takeProfit1 < entry)
    }

    @Test
    fun `generates no signal below the minimum candle count`() {
        val candles = trendingCandles(start = 3.20, step = -0.01, bars = 100, finalStep = 0.02)
        assertTrue(
            TechnicalAnalysisEngine.generateSignals(
                candles, PairSymbol("EURUSD"), Timeframe("H1"), IndicatorSettings.default()
            ).isEmpty()
        )
    }

    @Test
    fun `generates no signal for an empty candle list`() {
        assertTrue(
            TechnicalAnalysisEngine.generateSignals(
                emptyList(), PairSymbol("EURUSD"), Timeframe("H1"), IndicatorSettings.default()
            ).isEmpty()
        )
    }

    @Test
    fun `generates no signal when the market is completely flat`() {
        // WHY: A dead market yields ATR == 0, which would divide into an
        // infinite/NaN risk-reward. No signal is better than a corrupt one.
        val flat = (1..250).map { index ->
            CandleStick(baseTime.plusSeconds(index * 3600L), 1.1, 1.1, 1.1, 1.1)
        }
        assertTrue(
            TechnicalAnalysisEngine.generateSignals(
                flat, PairSymbol("EURUSD"), Timeframe("H1"), IndicatorSettings.default()
            ).isEmpty()
        )
    }

    @Test
    fun `honours custom indicator settings`() {
        val candles = trendingCandles(start = 3.20, step = -0.01, bars = 200, finalStep = 0.02)
        val custom = IndicatorSettings(
            emaPeriods = listOf(10, 30, 100),
            rsiPeriod = 7,
            macdFastPeriod = 5,
            macdSlowPeriod = 10,
            macdSignalPeriod = 3,
            atrPeriod = 7
        )
        val signals = TechnicalAnalysisEngine.generateSignals(
            candles, PairSymbol("EURUSD"), Timeframe("M15"), custom
        )
        // WHY: RSI(7) = 25.0 and MACD(5,10,3) histogram > 0 on this series,
        // so the custom periods must still produce exactly one BUY.
        assertEquals(1, signals.size)
        assertEquals(Timeframe("M15"), signals.first().timeframe)
        assertEquals(SignalType.BUY, signals.first().signalType)
    }

    // ------------------------------------------------------------- helpers

    /** Candles whose close follows [closes], with a +/-0.002 wick envelope. */
    private fun buildCandles(closes: List<Double>): List<CandleStick> {
        return closes.mapIndexed { index, close ->
            val open = if (index == 0) close else closes[index - 1]
            CandleStick(
                time = baseTime.plusSeconds(index * 3600L),
                open = open,
                high = max(open, close) + 0.002,
                low = min(open, close) - 0.002,
                close = close,
                volume = 1_000L
            )
        }
    }

    /**
     * [bars] closes stepping by [step] from [start], then one final reversal
     * bar of exactly [finalStep] — 201 candles total for bars = 200.
     *
     * WHY: the last delta is measured from closes.last() (not from the loop's
     * trailing accumulator) so the reversal magnitude in the test is exactly
     * [finalStep], which is what determines the RSI/MACD expectations.
     */
    private fun trendingCandles(
        start: Double,
        step: Double,
        bars: Int,
        finalStep: Double
    ): List<CandleStick> {
        val closes = ArrayList<Double>(bars + 1)
        var price = start
        repeat(bars) {
            closes.add(price)
            price += step
        }
        closes.add(closes.last() + finalStep)
        return buildCandles(closes)
    }
}
