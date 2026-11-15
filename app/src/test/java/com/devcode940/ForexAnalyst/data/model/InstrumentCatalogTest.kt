package com.devcode940.ForexAnalyst.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Tests for the expanded instrument catalogue and the price formatting that depends
 * on it.
 *
 * WHY these are invariant tests rather than value snapshots: the catalogue is data,
 * and data grows. Asserting "71 instruments" would fail on the next addition for no
 * good reason, so the tests assert the properties every entry must hold (digits in
 * range, pip size consistent with digits, sessions non-empty, symbols unique) plus a
 * few concrete examples that pin the interesting cases (JPY, gold, Bitcoin).
 */
class InstrumentCatalogTest {

    @Test
    fun `every instrument has a unique symbol and a usable precision`() {
        val symbols = InstrumentCatalog.ALL.map { it.symbol }
        assertEquals("duplicate symbols in the catalogue", symbols.size, symbols.distinct().size)

        InstrumentCatalog.ALL.forEach { instrument ->
            assertTrue(
                "${instrument.symbol.value} quoteDigits out of range",
                instrument.quoteDigits in 0..8
            )
            assertTrue(
                "${instrument.symbol.value} basePrice must be positive",
                instrument.basePrice.isFinite() && instrument.basePrice > 0.0
            )
            assertTrue(
                "${instrument.symbol.value} volatility must be non-negative",
                instrument.volatility.isFinite() && instrument.volatility >= 0.0
            )
            assertTrue(
                "${instrument.symbol.value} must belong to at least one session",
                instrument.sessions.isNotEmpty()
            )
            assertTrue(
                "${instrument.symbol.value} display name must not be blank",
                instrument.displayName.isNotBlank()
            )
        }
    }

    @Test
    fun `tick and pip size follow the quote digits`() {
        InstrumentCatalog.ALL.forEach { instrument ->
            val expectedTick = Math.pow(10.0, -instrument.quoteDigits.toDouble())
            val expectedPip = Math.pow(10.0, -(instrument.quoteDigits - 1).toDouble())
            assertEquals(instrument.symbol.value, expectedTick, instrument.tickSize, 1e-12)
            assertEquals(instrument.symbol.value, expectedPip, instrument.pipSize, 1e-12)
        }
    }

    @Test
    fun `the catalogue spans every market class`() {
        // WHY: the point of the expansion was breadth — a catalogue that quietly lost
        // its crypto or metals section would still pass every per-instrument test.
        InstrumentCategory.entries.forEach { category ->
            assertTrue(
                "no instruments in ${category.displayName}",
                InstrumentCatalog.inCategory(category).isNotEmpty()
            )
        }
        assertEquals(
            InstrumentCatalog.ALL.size,
            InstrumentCatalog.CATEGORIES.sumOf { InstrumentCatalog.inCategory(it).size }
        )
        assertEquals(InstrumentCatalog.ALL.size, InstrumentCatalog.size)
    }

    @Test
    fun `jpy pairs quote three decimals and majors five`() {
        assertEquals(3, InstrumentCatalog.quoteDigitsFor(PairSymbol("USDJPY")))
        assertEquals(5, InstrumentCatalog.quoteDigitsFor(PairSymbol("EURUSD")))
        assertEquals(2, InstrumentCatalog.quoteDigitsFor(PairSymbol("XAUUSD")))
        assertEquals(2, InstrumentCatalog.quoteDigitsFor(PairSymbol("BTCUSD")))
    }

    @Test
    fun `reference prices come from the catalogue, not a hardcoded ladder`() {
        // WHY (regression): the repository used a `symbol.contains("JPY") -> 150.0`
        // ladder that knew six instruments. Everything else simulated as EURUSD.
        assertEquals(151.50, InstrumentCatalog.basePriceFor(PairSymbol("USDJPY")), 1e-9)
        assertEquals(2350.00, InstrumentCatalog.basePriceFor(PairSymbol("XAUUSD")), 1e-9)
        assertEquals(67500.00, InstrumentCatalog.basePriceFor(PairSymbol("BTCUSD")), 1e-9)
        // An unknown symbol still gets a usable default rather than throwing.
        assertEquals(1.1000, InstrumentCatalog.basePriceFor(PairSymbol("ZZZZZZ")), 1e-9)
        assertEquals(5, InstrumentCatalog.quoteDigitsFor(PairSymbol("ZZZZZZ")))
        assertNull(InstrumentCatalog.specFor(PairSymbol("ZZZZZZ")))
        assertNotNull(InstrumentCatalog.specFor(PairSymbol("EURUSD")))
    }

    @Test
    fun `symbol lookup normalizes casing and separators`() {
        assertNotNull(InstrumentCatalog.specFor("eurusd"))
        assertNotNull(InstrumentCatalog.specFor("EUR/USD"))
        assertNotNull(InstrumentCatalog.specFor("  xauusd "))
        assertNull(InstrumentCatalog.specFor("not-a-symbol"))
    }

    @Test
    fun `pip conversion uses the instrument pip size`() {
        val eurusd = requireNotNull(InstrumentCatalog.specFor(PairSymbol("EURUSD")))
        val usdjpy = requireNotNull(InstrumentCatalog.specFor(PairSymbol("USDJPY")))
        // WHY these numbers: a pip is 10 points on a 5-digit pair and 0.01 on a
        // 3-digit JPY pair — the two cases traders argue about most.
        assertEquals(10.0, eurusd.toPips(0.00100), 1e-9)
        assertEquals(1.0, usdjpy.toPips(0.01), 1e-9)
    }

    @Test
    fun `sydney session wraps past midnight utc`() {
        val sydney = TradingSession.SYDNEY
        assertTrue(sydney.isActiveAt(Instant.parse("2024-03-15T23:00:00Z")))
        assertTrue(sydney.isActiveAt(Instant.parse("2024-03-15T02:00:00Z")))
        assertFalse(sydney.isActiveAt(Instant.parse("2024-03-15T12:00:00Z")))
    }

    @Test
    fun `london and new york overlap in the afternoon utc window`() {
        // WHY this matters to the chart: the overlap is shaded as one run, so the
        // session set at 13:00 UTC must contain both.
        val overlap = TradingSession.activeAt(Instant.parse("2024-03-15T13:00:00Z"))
        assertTrue(TradingSession.LONDON in overlap)
        assertTrue(TradingSession.NEW_YORK in overlap)
        assertFalse(TradingSession.TOKYO in overlap)
    }

    @Test
    fun `crypto and metals are continuous, indices are not`() {
        val btc = requireNotNull(InstrumentCatalog.specFor(PairSymbol("BTCUSD")))
        val spx = requireNotNull(InstrumentCatalog.specFor(PairSymbol("SPX500")))
        assertTrue("crypto trades around the clock", btc.isContinuous)
        assertFalse("an index has a session", spx.isContinuous)
    }

    @Test
    fun `default watchlist mixes market classes and every entry exists`() {
        val watchlist = PairCatalog.getDefaultWatchlist()
        assertTrue(watchlist.size >= 3)
        watchlist.forEach { symbol ->
            assertNotNull("$symbol is not in the catalogue", InstrumentCatalog.specFor(symbol))
        }
        // WHY: the projection must agree with the catalogue it projects.
        assertEquals(InstrumentCatalog.ALL.size, PairCatalog.ALL_PAIRS.size)
        assertEquals(InstrumentCatalog.MAJORS.size, PairCatalog.MAJOR_PAIRS.size)
    }
}
