package com.devcode940.ForexAnalyst.data.repository

import com.devcode940.ForexAnalyst.data.model.CandleStick
import kotlin.random.Random

object PriceGenerator {
    fun generateCandles(count: Int, basePrice: Double = 1.1000, volatility: Double = 0.001, intervalMs: Long = 3_600_000L): List<CandleStick> {
        val now = System.currentTimeMillis(); val candles = mutableListOf<CandleStick>(); var price = basePrice
        for (i in 0 until count) {
            val open = price; val change = (Random.nextDouble() - 0.5) * 2 * volatility * price; val close = open + change
            val high = maxOf(open, close) + Random.nextDouble() * volatility * price; val low = minOf(open, close) - Random.nextDouble() * volatility * price
            candles.add(CandleStick(now - (count - i) * intervalMs, open, high, low, close, Random.nextLong(1000, 10000))); price = close
        }
        return candles
    }
}