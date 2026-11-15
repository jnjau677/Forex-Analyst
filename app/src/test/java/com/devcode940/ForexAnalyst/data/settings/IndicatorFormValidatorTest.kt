package com.devcode940.ForexAnalyst.data.settings

import com.devcode940.ForexAnalyst.data.model.IndicatorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the indicator settings form validator.
 *
 * WHY: This is the logic the old SettingsViewModel had inline (and got wrong —
 * it coerced AFTER validating). Extracted into a pure object, it now runs on
 * the JVM in milliseconds with no Android runtime.
 */
class IndicatorFormValidatorTest {

    private fun input(
        rsi: String = "14",
        emaFast: String = "20",
        emaMedium: String = "50",
        emaSlow: String = "200",
        macdFast: String = "12",
        macdSlow: String = "26",
        macdSignal: String = "9",
        atr: String = "14",
        showEma: Boolean = true,
        showRsi: Boolean = true
    ) = IndicatorFormInput(
        rsiPeriod = rsi,
        emaFast = emaFast,
        emaMedium = emaMedium,
        emaSlow = emaSlow,
        macdFast = macdFast,
        macdSlow = macdSlow,
        macdSignal = macdSignal,
        atrPeriod = atr,
        showEma = showEma,
        showRsi = showRsi
    )

    @Test
    fun `accepts the default configuration`() {
        val result = IndicatorFormValidator.validate(input())
        assertTrue(result is IndicatorFormResult.Valid)
        val values = (result as IndicatorFormResult.Valid).values
        assertEquals(14, values.rsiPeriod)
        assertEquals(20, values.emaFast)
        assertEquals(50, values.emaMedium)
        assertEquals(200, values.emaSlow)
        assertTrue(values.showEma)
        assertTrue(values.showRsi)
    }

    @Test
    fun `valid values build a legal IndicatorSettings`() {
        // WHY: the validator's contract is that toSettings() can never throw.
        val values = (IndicatorFormValidator.validate(input()) as IndicatorFormResult.Valid).values
        val settings: IndicatorSettings = values.toSettings()
        assertEquals(listOf(20, 50, 200), settings.emaPeriods)
        assertEquals(14, settings.rsiPeriod)
        assertEquals(9, settings.macdSignalPeriod)
    }

    @Test
    fun `trims surrounding whitespace`() {
        val result = IndicatorFormValidator.validate(input(rsi = " 21 "))
        assertTrue(result is IndicatorFormResult.Valid)
        assertEquals(21, (result as IndicatorFormResult.Valid).values.rsiPeriod)
    }

    @Test
    fun `rejects non numeric input with an actionable message`() {
        val result = IndicatorFormValidator.validate(input(rsi = "abc"))
        assertTrue(result is IndicatorFormResult.Invalid)
        val invalid = result as IndicatorFormResult.Invalid
        assertEquals("RSI period", invalid.field)
        assertTrue(invalid.userMessage.contains("RSI period"))
        assertTrue(invalid.userMessage.contains("abc"))
        assertTrue(invalid.userMessage.contains("2"))
        assertTrue(invalid.userMessage.contains("100"))
    }

    @Test
    fun `rejects blank input and says so explicitly`() {
        val invalid = IndicatorFormValidator.validate(input(emaSlow = "  ")) as IndicatorFormResult.Invalid
        assertEquals("EMA slow period", invalid.field)
        assertTrue(invalid.userMessage.contains("(empty)"))
    }

    @Test
    fun `rejects out of range values at both boundaries`() {
        assertTrue(IndicatorFormValidator.validate(input(rsi = "1")) is IndicatorFormResult.Invalid)
        assertTrue(IndicatorFormValidator.validate(input(rsi = "101")) is IndicatorFormResult.Invalid)
        assertTrue(IndicatorFormValidator.validate(input(rsi = "2")) is IndicatorFormResult.Valid)
        assertTrue(IndicatorFormValidator.validate(input(rsi = "100")) is IndicatorFormResult.Valid)
        assertTrue(IndicatorFormValidator.validate(input(emaSlow = "401")) is IndicatorFormResult.Invalid)
        assertTrue(IndicatorFormValidator.validate(input(atr = "0")) is IndicatorFormResult.Invalid)
        assertTrue(IndicatorFormValidator.validate(input(macdSlow = "2")) is IndicatorFormResult.Invalid)
    }

    @Test
    fun `rejects negative values`() {
        val invalid = IndicatorFormValidator.validate(input(rsi = "-5")) as IndicatorFormResult.Invalid
        assertTrue(invalid.userMessage.contains("-5"))
    }

    @Test
    fun `rejects ema periods that are not ascending`() {
        // WHY (regression): the old code coerced first and validated second,
        // which could persist fast=200 / slow=20 and invert the whole strategy.
        val invalid = IndicatorFormValidator.validate(
            input(emaFast = "200", emaMedium = "50", emaSlow = "20")
        ) as IndicatorFormResult.Invalid
        assertEquals("EMA periods", invalid.field)
        assertTrue(invalid.userMessage.contains("must increase"))
    }

    @Test
    fun `rejects equal ema periods`() {
        assertTrue(
            IndicatorFormValidator.validate(input(emaFast = "50", emaMedium = "50")) is IndicatorFormResult.Invalid
        )
    }

    @Test
    fun `rejects macd fast period not below slow period`() {
        val invalid = IndicatorFormValidator.validate(
            input(macdFast = "26", macdSlow = "12")
        ) as IndicatorFormResult.Invalid
        assertEquals("MACD periods", invalid.field)
        assertTrue(invalid.userMessage.contains("smaller"))
    }

    @Test
    fun `reports only the first offending field`() {
        // WHY: one clear message per save beats a wall of errors; the user
        // fixes it and saves again.
        val invalid = IndicatorFormValidator.validate(
            input(rsi = "0", emaFast = "x", macdFast = "-1")
        ) as IndicatorFormResult.Invalid
        assertEquals("RSI period", invalid.field)
    }
}
