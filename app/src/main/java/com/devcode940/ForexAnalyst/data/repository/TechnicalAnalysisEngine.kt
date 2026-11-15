package com.devcode940.ForexAnalyst.data.repository

import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.IndicatorSettings
import com.devcode940.ForexAnalyst.data.model.MACDResult
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.SignalId
import com.devcode940.ForexAnalyst.data.model.SignalType
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSignal
import kotlin.math.abs

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
