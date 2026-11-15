package com.devcode940.ForexAnalyst.data.repository

import com.devcode940.ForexAnalyst.data.model.*
import java.util.Collections
import java.util.LinkedHashMap

object TechnicalAnalysisEngine {
    private const val MAX_CACHE_SIZE = 1000
    private const val CACHE_EXPIRATION_MS = 5 * 60 * 1000L
    private val indicatorCache = Collections.synchronizedMap(object : LinkedHashMap<String, Any>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean = size > MAX_CACHE_SIZE
    })

    fun calculateRSI(closes: List<Double>, period: Int = 14): List<Double?> {
        if (closes.size < period + 1) return List(closes.size) { null }
        val rsiValues = mutableListOf<Double?>()
        rsiValues.addAll(List(period) { null })
        var avgGain = 0.0; var avgLoss = 0.0
        for (i in 1..period) { val change = closes[i] - closes[i - 1]; if (change > 0) avgGain += change else avgLoss += Math.abs(change) }
        avgGain /= period; avgLoss /= period
        val rs = if (avgLoss == 0.0) 100.0 else avgGain / avgLoss
        rsiValues.add(100.0 - (100.0 / (1.0 + rs)))
        for (i in (period + 1) until closes.size) {
            val change = closes[i] - closes[i - 1]
            val gain = if (change > 0) change else 0.0; val loss = if (change < 0) Math.abs(change) else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period; avgLoss = (avgLoss * (period - 1) + loss) / period
            val currentRs = if (avgLoss == 0.0) 100.0 else avgGain / avgLoss
            rsiValues.add(100.0 - (100.0 / (1.0 + currentRs)))
        }
        return rsiValues
    }

    fun calculateLatestRSI(closes: List<Double>, period: Int = 14): Double? = calculateRSI(closes, period).lastOrNull()

    fun calculateEMA(data: List<Double>, period: Int): List<Double?> {
        if (data.isEmpty() || period <= 0) return emptyList()
        val ema = mutableListOf<Double?>(); val multiplier = 2.0 / (period + 1)
        val initialSMA = data.take(period).average()
        ema.addAll(List(period - 1) { null }); ema.add(initialSMA)
        for (i in period until data.size) { val prevEMA = ema.last() ?: continue; ema.add((data[i] - prevEMA) * multiplier + prevEMA) }
        return ema
    }

    fun calculateMACD(closes: List<Double>, fastPeriod: Int = 12, slowPeriod: Int = 26, signalPeriod: Int = 9): MACDResult {
        if (closes.isEmpty()) return MACDResult(emptyList(), emptyList(), emptyList())
        val fastEMA = calculateEMA(closes, fastPeriod); val slowEMA = calculateEMA(closes, slowPeriod)
        val macdLine = fastEMA.zip(slowEMA) { f, s -> if (f != null && s != null) f - s else null }.mapNotNull { it }
        val signalLine = calculateEMA(macdLine, signalPeriod).mapNotNull { it }
        val histogram = macdLine.zip(signalLine) { m, s -> m - s }
        return MACDResult(macdLine, signalLine, histogram)
    }

    fun calculateATR(candles: List<CandleStick>, period: Int = 14): List<Double?> {
        if (candles.size < 2) return emptyList()
        val trueRanges = mutableListOf<Double>()
        for (i in 1 until candles.size) {
            val cur = candles[i]; val prev = candles[i - 1]
            trueRanges.add(maxOf(cur.high - cur.low, Math.abs(cur.high - prev.close), Math.abs(cur.low - prev.close)))
        }
        val atr = mutableListOf<Double?>(); atr.addAll(List(period - 1) { null })
        val initialATR = trueRanges.take(period).average(); atr.add(initialATR)
        for (i in period until trueRanges.size) { val prevATR = atr.last() ?: continue; atr.add((prevATR * (period - 1) + trueRanges[i]) / period) }
        return atr
    }

    fun calculateLatestATR(candles: List<CandleStick>, period: Int = 14): Double? = calculateATR(candles, period).lastOrNull()

    fun generateSignals(candles: List<CandleStick>, timeframe: Timeframe, settings: IndicatorSettings): List<TradeSignal> {
        if (candles.size < 200) return emptyList()
        val closes = candles.map { it.close }
        val rsi = calculateLatestRSI(closes, settings.rsiPeriod) ?: return emptyList()
        val macd = calculateMACD(closes, settings.macdFastPeriod, settings.macdSlowPeriod, settings.macdSignalPeriod)
        val atr = calculateLatestATR(candles) ?: return emptyList()
        val latestCandle = candles.last(); val currentPrice = latestCandle.close
        val signals = mutableListOf<TradeSignal>()
        if (rsi < 30 && macd.histogram.lastOrNull()?.let { it > 0 } == true) {
            val sl = currentPrice - (atr * 1.5); val tp1 = currentPrice + (atr * 2.0); val tp2 = currentPrice + (atr * 3.0)
            signals.add(TradeSignal(SignalId.generate(), PairSymbol("EURUSD"), SignalType.BUY, currentPrice, sl, tp1, tp2, (tp1 - currentPrice) / (currentPrice - sl), 0.75, timeframe, "RSI oversold with MACD bullish crossover", java.time.Instant.now()))
        } else if (rsi > 70 && macd.histogram.lastOrNull()?.let { it < 0 } == true) {
            val sl = currentPrice + (atr * 1.5); val tp1 = currentPrice - (atr * 2.0); val tp2 = currentPrice - (atr * 3.0)
            signals.add(TradeSignal(SignalId.generate(), PairSymbol("EURUSD"), SignalType.SELL, currentPrice, sl, tp1, tp2, (currentPrice - tp1) / (sl - currentPrice), 0.75, timeframe, "RSI overbought with MACD bearish crossover", java.time.Instant.now()))
        }
        return signals
    }
}