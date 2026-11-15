package com.devcode940.ForexAnalyst.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the single price-formatting rule the whole app now shares.
 *
 * WHY: `"%.5f".format(price)` was copy-pasted across four screens. It was
 * locale-dependent (a German device renders `1,08500`, breaking every price string in
 * the app) and it quoted every instrument to five decimals, so USDJPY read `151.50000`
 * and gold read `2350.00000`. Both failures are invisible on an emulator set to
 * en-US with EURUSD selected — which is exactly how they shipped.
 */
class PriceFormatterTest {

    @Test
    fun `format pins the decimal separator regardless of the default locale`() {
        val default = java.util.Locale.getDefault()
        try {
            // WHY this test changes the JVM default locale: the bug it guards against
            // only reproduces when the default locale uses a comma. Asserting with
            // en-US alone would pass on the broken code too.
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("1.08500", PriceFormatter.format(1.085, 5))
            assertEquals("151.500", PriceFormatter.format(151.5, 3))
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            assertEquals("2350.00", PriceFormatter.format(2350.0, 2))
        } finally {
            java.util.Locale.setDefault(default)
        }
    }

    @Test
    fun `format follows the instrument precision`() {
        assertEquals("1.08500", PriceFormatter.format(1.085, PairSymbol("EURUSD")))
        assertEquals("151.500", PriceFormatter.format(151.5, PairSymbol("USDJPY")))
        assertEquals("2350.00", PriceFormatter.format(2350.0, PairSymbol("XAUUSD")))
        assertEquals("67500.00", PriceFormatter.format(67500.0, PairSymbol("BTCUSD")))
        // An unknown symbol still formats, using the FX convention.
        assertEquals("1.23457", PriceFormatter.format(1.234567, PairSymbol("ZZZZZZ")))
        assertEquals("1.23457", PriceFormatter.format(1.234567, null as Instrument?))
    }

    @Test
    fun `an unusable number becomes a dash instead of NaN`() {
        // WHY: "NaN" rendered in an alert target and in the crosshair tooltip. A dash
        // says "no value" without looking like a crash.
        assertEquals(PriceFormatter.NOT_A_NUMBER, PriceFormatter.format(Double.NaN, 5))
        assertEquals(PriceFormatter.NOT_A_NUMBER, PriceFormatter.format(Double.POSITIVE_INFINITY, 5))
        assertEquals(PriceFormatter.NOT_A_NUMBER, PriceFormatter.formatPips(Double.NaN, InstrumentCatalog.specFor(PairSymbol("EURUSD"))))
        assertEquals(PriceFormatter.NOT_A_NUMBER, PriceFormatter.formatPips(0.001, null))
        assertEquals(PriceFormatter.NOT_A_NUMBER, PriceFormatter.formatCompact(Double.NaN))
        assertEquals(PriceFormatter.NOT_A_NUMBER, PriceFormatter.formatPercent(0.0, 1.0))
    }

    @Test
    fun `signed and pip formatting keep the direction visible`() {
        assertEquals("+0.00420", PriceFormatter.formatSigned(0.0042, 5))
        assertEquals("-0.00420", PriceFormatter.formatSigned(-0.0042, 5))
        // WHY zero is not "+0.00000": a plus on a flat bar reads as a move.
        assertEquals("0.00000", PriceFormatter.formatSigned(0.0, 5))

        val eurusd = requireNotNull(InstrumentCatalog.specFor(PairSymbol("EURUSD")))
        val usdjpy = requireNotNull(InstrumentCatalog.specFor(PairSymbol("USDJPY")))
        assertEquals("+42.0 pips", PriceFormatter.formatPips(0.0042, eurusd))
        assertEquals("-42.0 pips", PriceFormatter.formatPips(-0.0042, eurusd))
        // A pip on a JPY pair is 0.01, not 0.0001.
        assertEquals("+1.0 pips", PriceFormatter.formatPips(0.01, usdjpy))
    }

    @Test
    fun `percent change is comparable across instruments`() {
        assertEquals("+0.39 %", PriceFormatter.formatPercent(1.0800, 1.0842))
        assertEquals("-0.39 %", PriceFormatter.formatPercent(1.0842, 1.0800))
        // WHY percent and not points: 100 points means nothing on EURUSD and little on
        // BTCUSD, so the only cross-instrument comparable unit is percent.
        assertEquals("+0.15 %", PriceFormatter.formatPercent(67000.0, 67100.0))
    }

    @Test
    fun `compact formatting scales volume readouts`() {
        assertEquals("0", PriceFormatter.formatCompact(0.0))
        assertEquals("999", PriceFormatter.formatCompact(999.0))
        assertEquals("1.5K", PriceFormatter.formatCompact(1_500.0))
        assertEquals("2.35M", PriceFormatter.formatCompact(2_345_678.0))
        assertEquals("1.10B", PriceFormatter.formatCompact(1_100_000_000.0))
        assertEquals("-1.5K", PriceFormatter.formatCompact(-1_500.0))
    }

    @Test
    fun `round removes the dust an accumulated axis step leaves behind`() {
        // WHY: axis ticks are generated as `first + index * step`, which reintroduces
        // binary error; a label reading 1.0850000000000002 is a visible bug.
        assertEquals(1.085, PriceFormatter.round(1.0850000000000002, 5), 0.0)
        assertEquals(0.3, PriceFormatter.round(0.30000000000000004, 5), 0.0)
        assertEquals(2350.0, PriceFormatter.round(2350.004, 2), 0.0)
        // An absurd digit count is clamped rather than throwing inside a draw pass.
        assertEquals(1.085, PriceFormatter.round(1.085, 99), 1e-9)
        assertEquals(1.0, PriceFormatter.round(1.085, -3), 1e-9)
        assertEquals("1.08500000", PriceFormatter.format(1.085, 99))
        assertEquals("1", PriceFormatter.format(1.085, -3))
        assertTrue(PriceFormatter.round(Double.NaN, 5).isNaN())
        assertFalse(PriceFormatter.format(1.085, 5).contains(","))
    }
}
