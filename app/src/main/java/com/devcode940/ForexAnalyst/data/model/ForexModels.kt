package com.devcode940.ForexAnalyst.data.model

import java.time.Instant

/**
 * One OHLCV candle. [time] is the candle open time.
 *
 * WHY: [Instant] (not a raw Long of epoch-millis) makes time arithmetic and
 * formatting type-safe — you cannot accidentally add a price to a timestamp.
 * [timeMillis] is the escape hatch for the chart canvas, which does pixel
 * math in epoch-millis.
 */
data class CandleStick(
    val time: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long = 0L
) {
    /** Epoch-millis view for chart coordinate math. */
    val timeMillis: Long get() = time.toEpochMilli()

    val isBullish: Boolean get() = close >= open

    init {
        // WHY: Fail fast on corrupt market data instead of drawing garbage
        // charts or feeding NaN into indicator math (one NaN poisons every
        // downstream EMA/RSI/MACD value).
        require(open.isFinite() && high.isFinite() && low.isFinite() && close.isFinite()) {
            "Candle prices must be finite (got O=$open H=$high L=$low C=$close)"
        }
        require(high >= low) { "Candle high ($high) must be >= low ($low)" }
        require(high >= maxOf(open, close)) { "Candle high ($high) below open/close" }
        require(low <= minOf(open, close)) { "Candle low ($low) above open/close" }
        require(volume >= 0L) { "Candle volume cannot be negative (got $volume)" }
    }
}

enum class SignalType { BUY, SELL }

/**
 * A generated trade signal.
 *
 * WHY: Validation in init makes invalid signals unrepresentable — a NaN
 * price or a confidence of 1.5 can never reach the database or the UI.
 */
data class TradeSignal(
    val id: SignalId,
    val pairSymbol: PairSymbol,
    val signalType: SignalType,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit1: Double,
    val takeProfit2: Double,
    val riskRewardRatio: Double,
    val confidenceScore: Double,
    val timeframe: Timeframe,
    val summaryRationale: String,
    val timestamp: Instant,
    val isBookmarked: Boolean = false
) {
    init {
        require(entryPrice.isFinite() && stopLoss.isFinite() && takeProfit1.isFinite() && takeProfit2.isFinite()) {
            "Signal prices must be finite"
        }
        require(confidenceScore in 0.0..1.0) { "confidenceScore must be 0..1 (got $confidenceScore)" }
        require(riskRewardRatio.isFinite() && riskRewardRatio >= 0.0) {
            "riskRewardRatio must be finite and >= 0 (got $riskRewardRatio)"
        }
    }
}

enum class AlertConditionType { PRICE_ABOVE, PRICE_BELOW, RSI_ABOVE, RSI_BELOW }

/**
 * A user-created price/indicator alert.
 *
 * WHY: targetValue must be finite — "NaN".toDoubleOrNull() succeeds, so
 * without this check a user could store a NaN alert that can never trigger.
 */
data class CustomAlert(
    val id: AlertId,
    val pairSymbol: PairSymbol,
    val conditionType: AlertConditionType,
    val targetValue: Double,
    val isTriggered: Boolean,
    val isActive: Boolean,
    val note: String,
    val createdAt: Instant
) {
    init {
        require(targetValue.isFinite()) { "Alert targetValue must be finite (got $targetValue)" }
        require(note.length <= MAX_NOTE_LENGTH) { "Alert note too long (max $MAX_NOTE_LENGTH chars)" }
    }

    companion object {
        const val MAX_NOTE_LENGTH = 500
    }
}

/**
 * Indicator output series. Every list is index-aligned with the candle list
 * it was computed from (leading nulls until the first computable value).
 *
 * WHY: Index alignment is the contract the chart canvas relies on — it
 * draws values[i] at candles[i]. Any producer that returns a shorter or
 * longer list silently shifts every overlay.
 */
data class IndicatorState(
    val ema20: List<Double?>? = null,
    val ema50: List<Double?>? = null,
    val ema200: List<Double?>? = null,
    val rsi: List<Double?>? = null,
    val macd: MACDResult? = null
)

data class MACDResult(val macdLine: List<Double>, val signalLine: List<Double>, val histogram: List<Double>)

/**
 * Indicator configuration.
 *
 * WHY: Periods are validated here (not just in the settings UI) so any
 * caller — ViewModel, tests, future code — gets the same guarantees.
 * Callers that build this from persisted settings should wrap construction
 * in runCatching and fall back to [default] (see ForexViewModel).
 */
data class IndicatorSettings(
    val emaPeriods: List<Int> = listOf(20, 50, 200),
    val rsiPeriod: Int = 14,
    val macdFastPeriod: Int = 12,
    val macdSlowPeriod: Int = 26,
    val macdSignalPeriod: Int = 9,
    val atrPeriod: Int = 14
) {
    init {
        require(rsiPeriod in 2..100) { "rsiPeriod must be 2..100 (got $rsiPeriod)" }
        require(macdFastPeriod in 2..100) { "macdFastPeriod must be 2..100 (got $macdFastPeriod)" }
        require(macdSlowPeriod in 3..200) { "macdSlowPeriod must be 3..200 (got $macdSlowPeriod)" }
        require(macdSignalPeriod in 2..100) { "macdSignalPeriod must be 2..100 (got $macdSignalPeriod)" }
        require(macdFastPeriod < macdSlowPeriod) { "MACD fast period must be < slow period" }
        require(atrPeriod in 2..100) { "atrPeriod must be 2..100 (got $atrPeriod)" }
        require(emaPeriods.all { it in 2..400 }) { "EMA periods must be 2..400 (got $emaPeriods)" }
        require(emaPeriods == emaPeriods.sorted()) { "EMA periods must be ascending (got $emaPeriods)" }
    }

    companion object {
        fun default() = IndicatorSettings()
    }
}
