package com.devcode940.ForexAnalyst.data.model

import java.time.Instant

data class CandleStick(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long = 0L) {
    val isBullish: Boolean get() = close >= open
}
enum class SignalType { BUY, SELL }
data class TradeSignal(
    val id: SignalId, val pairSymbol: PairSymbol, val signalType: SignalType,
    val entryPrice: Double, val stopLoss: Double, val takeProfit1: Double, val takeProfit2: Double,
    val riskRewardRatio: Double, val confidenceScore: Double, val timeframe: Timeframe,
    val summaryRationale: String, val timestamp: Instant, val isBookmarked: Boolean = false
)
enum class AlertConditionType { PRICE_ABOVE, PRICE_BELOW, RSI_ABOVE, RSI_BELOW }
data class CustomAlert(
    val id: AlertId, val pairSymbol: PairSymbol, val conditionType: AlertConditionType,
    val targetValue: Double, val isTriggered: Boolean, val isActive: Boolean,
    val note: String, val createdAt: Instant
)
data class IndicatorState(
    val ema20: List<Double?>? = null, val ema50: List<Double?>? = null,
    val ema200: List<Double?>? = null, val rsi: List<Double?>? = null,
    val macd: MACDResult? = null
)
data class MACDResult(val macdLine: List<Double>, val signalLine: List<Double>, val histogram: List<Double>)
data class IndicatorSettings(
    val emaPeriods: List<Int> = listOf(20, 50, 200), val rsiPeriod: Int = 14,
    val macdFastPeriod: Int = 12, val macdSlowPeriod: Int = 26, val macdSignalPeriod: Int = 9
) { companion object { fun default() = IndicatorSettings() } }