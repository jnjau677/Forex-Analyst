package com.devcode940.ForexAnalyst.data.repository

import com.devcode940.ForexAnalyst.data.model.BollingerBands
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.IndicatorSettings
import com.devcode940.ForexAnalyst.data.model.MACDResult
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.SignalId
import com.devcode940.ForexAnalyst.data.model.SignalType
import com.devcode940.ForexAnalyst.data.model.StochasticResult
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSignal
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure, stateless technical-analysis math (RSI, EMA, MACD, ATR, signals).
 *
 * WHY: No Android dependencies and no mutable state — every function is a
 * pure function of its inputs, which makes it trivially unit-testable on
 * the JVM and safe to call from any dispatcher. (The old implementation
 * carried an unused synchronizedMap "cache" that was never read.)
 */
object TechnicalAnalysisEngine {

    // WHY: Named constants — these are strategy parameters that must be
    // tunable in one place, not magic numbers scattered through the code.
    private const val RSI_OVERSOLD = 30.0
    private const val RSI_OVERBOUGHT = 70.0
    private const val STOP_LOSS_ATR_MULTIPLIER = 1.5
    private const val TAKE_PROFIT_1_ATR_MULTIPLIER = 2.0
    private const val TAKE_PROFIT_2_ATR_MULTIPLIER = 3.0
    private const val SIGNAL_CONFIDENCE = 0.75
    private const val MIN_CANDLES_FOR_SIGNAL = 200

    /** Value reported by bounded oscillators when a window has no range. */
    private const val NEUTRAL_OSCILLATOR = 50.0

    /**
     * Wilder's RSI, index-aligned with [closes] (leading nulls until the
     * first computable value).
     */
    fun calculateRSI(closes: List<Double>, period: Int = 14): List<Double?> {
        require(period >= 2) { "RSI period must be >= 2 (got $period)" }
        if (closes.size < period + 1) return List(closes.size) { null }

        val rsiValues = ArrayList<Double?>(closes.size)
        repeat(period) { rsiValues.add(null) }

        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val change = closes[i] - closes[i - 1]
            if (change > 0) avgGain += change else avgLoss += abs(change)
        }
        avgGain /= period
        avgLoss /= period
        rsiValues.add(rsiFromAverages(avgGain, avgLoss))

        for (i in period + 1 until closes.size) {
            val change = closes[i] - closes[i - 1]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) abs(change) else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
            rsiValues.add(rsiFromAverages(avgGain, avgLoss))
        }
        return rsiValues
    }

    /**
     * WHY: Flat prices (avgGain == avgLoss == 0) are neutral, not "100".
     * The old code returned RSI 100 for a dead market, which could fire a
     * false overbought signal.
     */
    private fun rsiFromAverages(avgGain: Double, avgLoss: Double): Double {
        if (avgGain == 0.0 && avgLoss == 0.0) return 50.0
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    fun calculateLatestRSI(closes: List<Double>, period: Int = 14): Double? =
        calculateRSI(closes, period).lastOrNull()

    /**
     * EMA, index-aligned with [data] (leading nulls until the seed SMA).
     *
     * WHY: The old code returned a list LONGER than [data] when
     * data.size < period (period-1 nulls + SMA), which misaligns every
     * chart overlay that indexes by candle position.
     */
    fun calculateEMA(data: List<Double>, period: Int): List<Double?> {
        require(period >= 2) { "EMA period must be >= 2 (got $period)" }
        if (data.isEmpty()) return emptyList()
        if (data.size < period) return List(data.size) { null }

        val ema = ArrayList<Double?>(data.size)
        repeat(period - 1) { ema.add(null) }
        ema.add(data.take(period).average())

        val multiplier = 2.0 / (period + 1)
        for (i in period until data.size) {
            val prevEMA = ema.last() ?: continue // WHY: unreachable after the seed, but never crash
            ema.add((data[i] - prevEMA) * multiplier + prevEMA)
        }
        return ema
    }

    /**
     * MACD with all three lines index-aligned to [closes].
     *
     * WHY (critical fix): The old code dropped leading nulls with
     * mapNotNull, then zipped macdLine with signalLine from index 0 — but
     * signalLine starts (signalPeriod-1) bars LATER, so the histogram was
     * shifted and every MACD crossover signal was computed on wrong bars.
     */
    fun calculateMACD(
        closes: List<Double>,
        fastPeriod: Int = 12,
        slowPeriod: Int = 26,
        signalPeriod: Int = 9
    ): MACDResult {
        if (closes.isEmpty()) return MACDResult(emptyList(), emptyList(), emptyList())

        val fastEMA = calculateEMA(closes, fastPeriod)
        val slowEMA = calculateEMA(closes, slowPeriod)
        // WHY: Preserve nulls so index i always means "bar i".
        val macdLine: List<Double?> = fastEMA.zip(slowEMA) { f, s ->
            if (f != null && s != null) f - s else null
        }
        val macdValues = macdLine.filterNotNull()
        val signalValues = calculateEMA(macdValues, signalPeriod).filterNotNull()
        // WHY: signalValues[0] corresponds to macdValues[signalPeriod-1] —
        // drop that many leading macd values before zipping so
        // histogram[i] aligns with the correct bar.
        val histogram = macdValues.drop(signalPeriod - 1).zip(signalValues) { m, s -> m - s }
        return MACDResult(macdValues, signalValues, histogram)
    }

    /**
     * ATR, index-aligned with [candles] (atr[i] ends at candle i).
     *
     * WHY: The old code returned a list one element SHORTER than [candles]
     * and shifted by one bar (period-1 leading nulls instead of period).
     */
    fun calculateATR(candles: List<CandleStick>, period: Int = 14): List<Double?> {
        require(period >= 2) { "ATR period must be >= 2 (got $period)" }
        if (candles.size <= period) return List(candles.size) { null }

        val trueRanges = ArrayList<Double>(candles.size - 1)
        for (i in 1 until candles.size) {
            val cur = candles[i]
            val prev = candles[i - 1]
            trueRanges.add(
                maxOf(
                    cur.high - cur.low,
                    abs(cur.high - prev.close),
                    abs(cur.low - prev.close)
                )
            )
        }

        // WHY: First computable ATR is at candle index `period` (it needs
        // `period` true ranges), so `period` leading nulls.
        val atr = ArrayList<Double?>(candles.size)
        repeat(period) { atr.add(null) }
        atr.add(trueRanges.take(period).average())
        for (i in period + 1 until candles.size) {
            val prevATR = atr.last() ?: continue
            atr.add((prevATR * (period - 1) + trueRanges[i - 1]) / period)
        }
        return atr
    }

    fun calculateLatestATR(candles: List<CandleStick>, period: Int = 14): Double? =
        calculateATR(candles, period).lastOrNull()

    /**
     * Simple moving average, index-aligned with [data].
     *
     * WHY kept alongside the EMA: Bollinger Bands are defined on an SMA, and mixing
     * an EMA mid-line into the band formula changes the width enough to invalidate
     * the conventional 2σ levels traders read.
     */
    fun calculateSMA(data: List<Double>, period: Int): List<Double?> {
        require(period >= 2) { "SMA period must be >= 2 (got $period)" }
        if (data.isEmpty()) return emptyList()
        if (data.size < period) return List(data.size) { null }

        val result = ArrayList<Double?>(data.size)
        repeat(period - 1) { result.add(null) }
        // WHY: a rolling sum updated in O(1) per bar instead of re-summing the
        // window; the naive version is O(n·period), which for 5 000 bars at
        // period 200 is a million additions per indicator per frame.
        var sum = data.take(period).sum()
        result.add(sum / period)
        for (index in period until data.size) {
            sum += data[index] - data[index - period]
            result.add(sum / period)
        }
        return result
    }

    /**
     * Bollinger Bands: SMA ± [stdDevMultiplier] population standard deviations.
     *
     * WHY population (n) rather than sample (n−1) deviation: the band definition
     * every platform ships uses the population form over the rolling window, so a
     * sample form would render visibly narrower bands than the user expects and
     * disagree with their other tools.
     */
    fun calculateBollingerBands(
        closes: List<Double>,
        period: Int = 20,
        stdDevMultiplier: Double = 2.0
    ): BollingerBands {
        require(period >= 2) { "Bollinger period must be >= 2 (got $period)" }
        require(stdDevMultiplier.isFinite() && stdDevMultiplier > 0.0) {
            "Bollinger multiplier must be finite and > 0 (got $stdDevMultiplier)"
        }
        if (closes.isEmpty()) {
            return BollingerBands(emptyList(), emptyList(), emptyList(), emptyList())
        }
        val middle = calculateSMA(closes, period)
        val upper = ArrayList<Double?>(closes.size)
        val lower = ArrayList<Double?>(closes.size)
        val bandwidth = ArrayList<Double?>(closes.size)

        for (index in closes.indices) {
            val mid = middle[index]
            if (mid == null || index < period - 1) {
                upper.add(null)
                lower.add(null)
                bandwidth.add(null)
                continue
            }
            var variance = 0.0
            for (offset in 0 until period) {
                val delta = closes[index - offset] - mid
                variance += delta * delta
            }
            val deviation = sqrt(variance / period)
            upper.add(mid + deviation * stdDevMultiplier)
            lower.add(mid - deviation * stdDevMultiplier)
            // WHY: guard the division — a flat window has mid != 0 in practice, but
            // a zero mid (a degenerate feed) would otherwise produce Infinity and
            // poison the squeeze readout.
            bandwidth.add(if (mid == 0.0) null else (deviation * 2 * stdDevMultiplier) / mid)
        }
        return BollingerBands(upper, middle, lower, bandwidth)
    }

    /**
     * Session-anchored VWAP.
     *
     * WHY anchored per UTC day: VWAP is only meaningful as an average *since the
     * session open*; a cumulative-from-the-first-bar VWAP on 500 hourly candles
     * flattens into a near-constant line and tells the trader nothing. Anchoring at
     * the day boundary makes it the intraday fair-value reference institutions use.
     *
     * WHY volume-weighted with a zero-volume fallback: simulated and thin feeds
     * produce bars with zero volume, where the classic formula divides by zero. Such
     * bars carry the previous VWAP forward instead of emitting NaN.
     */
    fun calculateVWAP(candles: List<CandleStick>, zone: ZoneId = ZoneOffset.UTC): List<Double?> {
        if (candles.isEmpty()) return emptyList()
        val result = ArrayList<Double?>(candles.size)
        var cumulativePriceVolume = 0.0
        var cumulativeVolume = 0.0
        var previousDay: LocalDate? = null
        var previousValue: Double? = null

        for (candle in candles) {
            val day = candle.time.atZone(zone).toLocalDate()
            if (previousDay != null && day != previousDay) {
                // WHY: reset on the session boundary, not on the first bar only.
                cumulativePriceVolume = 0.0
                cumulativeVolume = 0.0
            }
            previousDay = day

            val typicalPrice = (candle.high + candle.low + candle.close) / 3.0
            val volume = candle.volume.toDouble()
            cumulativePriceVolume += typicalPrice * volume
            cumulativeVolume += volume

            val value = when {
                cumulativeVolume > 0.0 -> cumulativePriceVolume / cumulativeVolume
                // WHY: no volume traded yet in this session — the typical price is
                // the best available estimate and keeps the line continuous.
                else -> previousValue ?: typicalPrice
            }
            previousValue = value
            result.add(value)
        }
        return result
    }

    /**
     * Stochastic oscillator: %K over [kPeriod], %D as its [dPeriod]-bar SMA.
     *
     * WHY 50 on a flat window: when the highest high equals the lowest low the
     * classic formula divides by zero. Reporting 50 (neutral) matches the RSI
     * behaviour already implemented above and avoids a spurious overbought reading
     * on an illiquid bar.
     */
    fun calculateStochastic(
        candles: List<CandleStick>,
        kPeriod: Int = 14,
        dPeriod: Int = 3
    ): StochasticResult {
        require(kPeriod >= 2) { "Stochastic %K period must be >= 2 (got $kPeriod)" }
        require(dPeriod >= 2) { "Stochastic %D period must be >= 2 (got $dPeriod)" }
        if (candles.isEmpty()) return StochasticResult(emptyList(), emptyList())
        if (candles.size < kPeriod) {
            return StochasticResult(List(candles.size) { null }, List(candles.size) { null })
        }

        val kValues = ArrayList<Double?>(candles.size)
        repeat(kPeriod - 1) { kValues.add(null) }
        for (index in (kPeriod - 1) until candles.size) {
            var highestHigh = Double.NEGATIVE_INFINITY
            var lowestLow = Double.POSITIVE_INFINITY
            for (offset in 0 until kPeriod) {
                val candle = candles[index - offset]
                if (candle.high > highestHigh) highestHigh = candle.high
                if (candle.low < lowestLow) lowestLow = candle.low
            }
            val range = highestHigh - lowestLow
            val close = candles[index].close
            kValues.add(
                if (range <= 0.0) NEUTRAL_OSCILLATOR
                else ((close - lowestLow) / range * 100.0).coerceIn(0.0, 100.0)
            )
        }

        // WHY: %D is an SMA of the *computed* %K values, aligned back onto the
        // candle index — dropping nulls and re-zipping is what misaligned MACD.
        val compact = kValues.filterNotNull()
        val smoothed = if (compact.size >= dPeriod) calculateSMA(compact, dPeriod) else List(compact.size) { null }
        val dValues = ArrayList<Double?>(candles.size)
        val leadingNulls = kValues.size - compact.size
        repeat(leadingNulls) { dValues.add(null) }
        dValues.addAll(smoothed)
        return StochasticResult(kValues, dValues)
    }

    /**
     * Heikin-Ashi smoothed candles.
     *
     * WHY a transform and not a separate series: Heikin-Ashi is derived from the
     * previous *smoothed* bar, so it must be computed recursively over the same
     * candle list. Producing `List<CandleStick>` means the existing renderer, the
     * price-axis bounds and the crosshair tooltip all work unchanged — the style
     * switch is a one-line substitution at the draw site.
     *
     * WHY volume and timestamps are preserved: the volume pane and the time axis
     * must keep working when the user switches chart style.
     */
    fun heikinAshi(candles: List<CandleStick>): List<CandleStick> {
        if (candles.isEmpty()) return emptyList()
        val result = ArrayList<CandleStick>(candles.size)
        var previousOpen = (candles[0].open + candles[0].close) / 2.0
        var previousClose = previousOpen

        for (candle in candles) {
            val close = (candle.open + candle.high + candle.low + candle.close) / 4.0
            val open = (previousOpen + previousClose) / 2.0
            result.add(
                CandleStick(
                    time = candle.time,
                    open = open,
                    // WHY: the smoothed high/low must still bound the smoothed
                    // open/close, or CandleStick's own invariant would reject the
                    // bar (and the price axis would clip it).
                    high = maxOf(candle.high, open, close),
                    low = minOf(candle.low, open, close),
                    close = close,
                    volume = candle.volume
                )
            )
            previousOpen = open
            previousClose = close
        }
        return result
    }

    /**
     * Generates at most one signal from the latest candle.
     *
     * WHY: [pairSymbol] is a required parameter — the old signature
     * hardcoded EURUSD, so signals for every other pair were saved under
     * the wrong symbol and polluted the EURUSD signal feed.
     */
    fun generateSignals(
        candles: List<CandleStick>,
        pairSymbol: PairSymbol,
        timeframe: Timeframe,
        settings: IndicatorSettings
    ): List<TradeSignal> {
        if (candles.size < MIN_CANDLES_FOR_SIGNAL) return emptyList()
        val closes = candles.map { it.close }
        val rsi = calculateLatestRSI(closes, settings.rsiPeriod) ?: return emptyList()
        val macd = calculateMACD(closes, settings.macdFastPeriod, settings.macdSlowPeriod, settings.macdSignalPeriod)
        val atr = calculateLatestATR(candles, settings.atrPeriod) ?: return emptyList()
        // WHY: ATR == 0 (dead market) would divide by zero below and produce
        // Infinity/NaN risk-reward — no signal is better than a corrupt one.
        if (atr <= 0.0 || !atr.isFinite()) return emptyList()

        val latestCandle = candles.last()
        val currentPrice = latestCandle.close
        val histogram = macd.histogram.lastOrNull() ?: return emptyList()

        val signal = when {
            rsi < RSI_OVERSOLD && histogram > 0 -> TradeSignal(
                id = SignalId.generate(),
                pairSymbol = pairSymbol,
                signalType = SignalType.BUY,
                entryPrice = currentPrice,
                stopLoss = currentPrice - atr * STOP_LOSS_ATR_MULTIPLIER,
                takeProfit1 = currentPrice + atr * TAKE_PROFIT_1_ATR_MULTIPLIER,
                takeProfit2 = currentPrice + atr * TAKE_PROFIT_2_ATR_MULTIPLIER,
                // WHY: (TP-entry)/(entry-SL) == TP_mult/SL_mult exactly —
                // same value, no division by a price that could be zero.
                riskRewardRatio = TAKE_PROFIT_1_ATR_MULTIPLIER / STOP_LOSS_ATR_MULTIPLIER,
                confidenceScore = SIGNAL_CONFIDENCE,
                timeframe = timeframe,
                summaryRationale = "RSI oversold ($rsi) with MACD bullish momentum",
                // WHY: Stamp the signal with the candle's time, not "now" —
                // this makes the dedup fingerprint stable across rescans.
                timestamp = latestCandle.time
            )
            rsi > RSI_OVERBOUGHT && histogram < 0 -> TradeSignal(
                id = SignalId.generate(),
                pairSymbol = pairSymbol,
                signalType = SignalType.SELL,
                entryPrice = currentPrice,
                stopLoss = currentPrice + atr * STOP_LOSS_ATR_MULTIPLIER,
                takeProfit1 = currentPrice - atr * TAKE_PROFIT_1_ATR_MULTIPLIER,
                takeProfit2 = currentPrice - atr * TAKE_PROFIT_2_ATR_MULTIPLIER,
                riskRewardRatio = TAKE_PROFIT_1_ATR_MULTIPLIER / STOP_LOSS_ATR_MULTIPLIER,
                confidenceScore = SIGNAL_CONFIDENCE,
                timeframe = timeframe,
                summaryRationale = "RSI overbought ($rsi) with MACD bearish momentum",
                timestamp = latestCandle.time
            )
            else -> null
        }
        return signal?.let { listOf(it) } ?: emptyList()
    }
}
