package com.devcode940.ForexAnalyst.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Unit tests for domain-model invariants and the [Result] envelope.
 *
 * WHY: These invariants are the last line of defence before data reaches the
 * database or the chart. Each test pins one rule from the review checklist
 * (edge cases, negative values, NaN, unicode, long strings).
 */
class DomainModelValidationTest {

    private val time: Instant = Instant.parse("2024-05-01T08:30:00Z")

    // ------------------------------------------------------------ CandleStick

    @Test
    fun `candle exposes an epoch millis view of its instant`() {
        val candle = CandleStick(time, open = 1.1, high = 1.2, low = 1.0, close = 1.15, volume = 10)
        assertEquals(time, candle.time)
        assertEquals(time.toEpochMilli(), candle.timeMillis)
    }

    @Test
    fun `candle rejects nan prices`() {
        // WHY: a single NaN poisons every downstream EMA/RSI/MACD value.
        assertThrows(IllegalArgumentException::class.java) {
            CandleStick(time, open = Double.NaN, high = 1.2, low = 1.0, close = 1.15)
        }
    }

    @Test
    fun `candle rejects infinite prices`() {
        assertThrows(IllegalArgumentException::class.java) {
            CandleStick(time, open = 1.1, high = Double.POSITIVE_INFINITY, low = 1.0, close = 1.15)
        }
    }

    @Test
    fun `candle rejects high below low`() {
        assertThrows(IllegalArgumentException::class.java) {
            CandleStick(time, open = 1.1, high = 1.0, low = 1.2, close = 1.15)
        }
    }

    @Test
    fun `candle rejects high below close`() {
        assertThrows(IllegalArgumentException::class.java) {
            CandleStick(time, open = 1.1, high = 1.14, low = 1.0, close = 1.15)
        }
    }

    @Test
    fun `candle rejects low above open`() {
        assertThrows(IllegalArgumentException::class.java) {
            CandleStick(time, open = 1.1, high = 1.2, low = 1.12, close = 1.15)
        }
    }

    @Test
    fun `candle rejects negative volume`() {
        assertThrows(IllegalArgumentException::class.java) {
            CandleStick(time, open = 1.1, high = 1.2, low = 1.0, close = 1.15, volume = -1)
        }
    }

    @Test
    fun `candle is bullish when close is at or above open`() {
        assertTrue(CandleStick(time, 1.1, 1.2, 1.0, 1.15).isBullish)
        assertTrue(CandleStick(time, 1.1, 1.2, 1.0, 1.1).isBullish)
        assertTrue(!CandleStick(time, 1.1, 1.2, 1.0, 1.05).isBullish)
    }

    @Test
    fun `candle equality is structural and ignores nothing`() {
        // WHY: data class equals/hashCode must include every field, otherwise
        // Compose skips recomposition when only one price changes.
        val a = CandleStick(time, 1.1, 1.2, 1.0, 1.15, 100)
        val b = CandleStick(time, 1.1, 1.2, 1.0, 1.15, 100)
        val c = CandleStick(time, 1.1, 1.2, 1.0, 1.15, 101)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    // -------------------------------------------------------------- TradeSignal

    @Test
    fun `signal rejects confidence outside zero to one`() {
        assertThrows(IllegalArgumentException::class.java) { validSignal().copy(confidenceScore = 1.5) }
        assertThrows(IllegalArgumentException::class.java) { validSignal().copy(confidenceScore = -0.1) }
    }

    @Test
    fun `signal rejects nan prices`() {
        assertThrows(IllegalArgumentException::class.java) { validSignal().copy(entryPrice = Double.NaN) }
    }

    @Test
    fun `signal rejects negative risk reward`() {
        assertThrows(IllegalArgumentException::class.java) { validSignal().copy(riskRewardRatio = -1.0) }
    }

    // ------------------------------------------------------------- CustomAlert

    @Test
    fun `alert rejects nan target`() {
        // WHY: "NaN".toDoubleOrNull() succeeds — without this check a user can
        // store an alert that can never trigger.
        assertThrows(IllegalArgumentException::class.java) { validAlert().copy(targetValue = Double.NaN) }
    }

    @Test
    fun `alert rejects an over long note`() {
        assertThrows(IllegalArgumentException::class.java) {
            validAlert().copy(note = "x".repeat(CustomAlert.MAX_NOTE_LENGTH + 1))
        }
    }

    @Test
    fun `alert accepts a unicode note at the length limit`() {
        // WHY: length is measured in UTF-16 chars, so multi-byte text must be
        // accepted right up to the documented limit. 3 chars x 200 = 600,
        // truncated to exactly 500 — proves the boundary is inclusive.
        val note = "é日€".repeat(200).take(CustomAlert.MAX_NOTE_LENGTH)
        assertEquals(CustomAlert.MAX_NOTE_LENGTH, note.length)
        assertEquals(note, validAlert().copy(note = note).note)
    }

    @Test
    fun `alert accepts emoji in notes`() {
        // WHY: UTF-16 surrogate pairs must survive the round trip untouched.
        val note = "Breakout 🚀 above 1.09 💹"
        assertEquals(note, validAlert().copy(note = note).note)
    }

    // ------------------------------------------------------- IndicatorSettings

    @Test
    fun `indicator settings reject macd fast period not below slow`() {
        assertThrows(IllegalArgumentException::class.java) {
            IndicatorSettings.default().copy(macdFastPeriod = 26, macdSlowPeriod = 12)
        }
    }

    @Test
    fun `indicator settings reject unsorted ema periods`() {
        assertThrows(IllegalArgumentException::class.java) {
            IndicatorSettings.default().copy(emaPeriods = listOf(200, 50, 20))
        }
    }

    @Test
    fun `indicator settings reject out of range periods`() {
        assertThrows(IllegalArgumentException::class.java) { IndicatorSettings.default().copy(rsiPeriod = 1) }
        assertThrows(IllegalArgumentException::class.java) { IndicatorSettings.default().copy(atrPeriod = 101) }
    }

    // --------------------------------------------------------------- Id types

    @Test
    fun `timeframe accepts every supported granularity and rejects others`() {
        Timeframe.VALID_TIMEFRAMES.forEach { Timeframe(it) }
        assertThrows(IllegalArgumentException::class.java) { Timeframe("1H") }
        assertThrows(IllegalArgumentException::class.java) { Timeframe("h1") }
        assertThrows(IllegalArgumentException::class.java) { Timeframe("") }
    }

    @Test
    fun `pair symbol rejects blank input`() {
        assertThrows(IllegalArgumentException::class.java) { PairSymbol("   ") }
    }

    // ---------------------------------------------------------- Result envelope

    @Test
    fun `result success carries data and failure carries a safe message`() {
        val success: Result<Int> = Result.Success(7)
        val failure: Result<Int> = Result.Failure(AppError.Network)

        assertEquals(7, success.getOrNull())
        assertNull(failure.getOrNull())
        assertEquals(7, success.getOrElse(0))
        assertEquals(0, failure.getOrElse(0))
        assertEquals(14, success.map { it * 2 }.getOrElse(0))
        assertEquals(0, failure.map { it * 2 }.getOrElse(0))
    }

    @Test
    fun `result of classifies domain validation failures`() {
        // WHY: require() failures are user/data problems, not internal faults —
        // they must be classified as Validation so the UI can be specific.
        val result = runBlockingResult { Result.of { CandleStick(time, 1.1, 1.0, 1.2, 1.15) } }
        val error = (result as Result.Failure).error
        assertTrue(error is AppError.Validation)
        assertEquals("VALIDATION_FAILED", error.code)
    }

    @Test
    fun `result of classifies io failures as network`() {
        val result = runBlockingResult {
            Result.of { throw java.io.IOException("socket closed") }
        }
        val error = (result as Result.Failure).error
        assertEquals(AppError.Network, error)
        assertEquals("NETWORK_UNAVAILABLE", error.code)
    }

    @Test
    fun `result of never exposes the internal exception message`() {
        // WHY (non-negotiable): the cause stays in the AppError.Unknown for
        // logging only; userMessage is generic and safe to render.
        val result = runBlockingResult {
            Result.of { throw IllegalStateException("SELECT * FROM secrets WHERE key = 'abc123'") }
        }
        val error = (result as Result.Failure).error
        assertTrue(error is AppError.Unknown)
        assertEquals("INTERNAL_ERROR", error.code)
        assertTrue(!error.userMessage.contains("secrets"))
        assertTrue(!error.userMessage.contains("abc123"))
        val cause = (error as AppError.Unknown).cause
        assertTrue(cause is IllegalStateException)
    }

    @Test
    fun `result of passes the success value through`() {
        val result = runBlockingResult { Result.of { "ok" } }
        assertEquals("ok", result.getOrNull())
    }

    @Test
    fun `fold forces both branches to be handled`() {
        val success = Result.Success(3).fold(onSuccess = { "value=$it" }, onFailure = { it.code })
        val failure = Result.Failure(AppError.Storage).fold(onSuccess = { "value=$it" }, onFailure = { it.code })
        assertEquals("value=3", success)
        assertEquals("STORAGE_FAILURE", failure)
    }

    /**
     * WHY: [Result.of] is a suspend factory (it must be able to wrap Room
     * calls), so tests drive it with a minimal blocking runner instead of
     * pulling in a coroutines-test dependency for one call.
     */
    private fun <T> runBlockingResult(block: suspend () -> Result<T>): Result<T> =
        kotlinx.coroutines.runBlocking { block() }

    private fun validSignal() = TradeSignal(
        id = SignalId.generate(),
        pairSymbol = PairSymbol("EURUSD"),
        signalType = SignalType.BUY,
        entryPrice = 1.0842,
        stopLoss = 1.0820,
        takeProfit1 = 1.0880,
        takeProfit2 = 1.0920,
        riskRewardRatio = 1.7,
        confidenceScore = 0.8,
        timeframe = Timeframe("H1"),
        summaryRationale = "test signal",
        timestamp = time
    )

    private fun validAlert() = CustomAlert(
        id = AlertId.generate(),
        pairSymbol = PairSymbol("EURUSD"),
        conditionType = AlertConditionType.PRICE_ABOVE,
        targetValue = 1.09,
        isTriggered = false,
        isActive = true,
        note = "note",
        createdAt = time
    )
}
