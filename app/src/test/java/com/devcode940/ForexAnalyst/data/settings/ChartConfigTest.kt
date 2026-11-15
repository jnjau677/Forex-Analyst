package com.devcode940.ForexAnalyst.data.settings

import com.devcode940.ForexAnalyst.data.model.ChartConfig
import com.devcode940.ForexAnalyst.data.model.ChartOverlay
import com.devcode940.ForexAnalyst.data.model.ChartStyle
import com.devcode940.ForexAnalyst.data.model.ChartTimeZone
import com.devcode940.ForexAnalyst.data.model.MagnetMode
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the chart configuration: its toggles, and its round trip through the
 * persisted [AppSettings] shape.
 *
 * WHY the round trip is the important half: the toolbar writes a [ChartConfig] and the
 * settings file stores ten flat fields. A projection that loses one of them (a new
 * overlay added to the enum but not to the mapper) would reset that choice on every
 * restart — and it would look like a "settings do not stick" bug with no obvious cause.
 */
class ChartConfigTest {

    private val gson = Gson()

    @Test
    fun `toggles are additive and never mutate the original`() {
        val config = ChartConfig.default()
        assertEquals(ChartOverlay.DEFAULT_ENABLED, config.enabled)
        assertTrue(config.isOn(ChartOverlay.EMA))
        assertFalse(config.isOn(ChartOverlay.MACD))

        val withMacd = config.toggled(ChartOverlay.MACD)
        assertTrue(withMacd.isOn(ChartOverlay.MACD))
        // WHY: a toggle that mutated the original would let two charts disagree about
        // what is enabled while sharing one config instance.
        assertFalse(config.isOn(ChartOverlay.MACD))
        assertEquals(config, withMacd.toggled(ChartOverlay.MACD))
        assertEquals(config, config.with(ChartOverlay.EMA, on = true))
        assertFalse(config.with(ChartOverlay.EMA, on = false).isOn(ChartOverlay.EMA))
    }

    @Test
    fun `only enabled sub-panes are laid out, in a stable order`() {
        val config = ChartConfig(enabled = setOf(ChartOverlay.MACD, ChartOverlay.VOLUME, ChartOverlay.EMA))
        // WHY order matters: the layout stacks panes top to bottom in this order, so a
        // set's iteration order leaking into the layout would reorder the panes between
        // launches.
        assertEquals(listOf(ChartOverlay.VOLUME, ChartOverlay.MACD), config.panes)
        assertTrue(ChartConfig(enabled = ChartOverlay.PANES.toSet()).panes == ChartOverlay.PANES)
        assertTrue(ChartConfig(enabled = emptySet()).panes.isEmpty())
        assertTrue(ChartOverlay.OVERLAYS.none { it.isPane })
        assertTrue(ChartOverlay.PANES.all { it.isPane })
        assertEquals(ChartOverlay.entries.size, ChartOverlay.PANES.size + ChartOverlay.OVERLAYS.size)
    }

    @Test
    fun `every display choice survives the round trip through settings`() {
        val config = ChartConfig(
            style = ChartStyle.HEIKIN_ASHI,
            timeZone = ChartTimeZone.NAIROBI,
            magnet = MagnetMode.STRONG,
            enabled = setOf(ChartOverlay.SMA, ChartOverlay.BOLLINGER, ChartOverlay.VWAP, ChartOverlay.MACD)
        )
        val restored = AppSettings().withChartConfig(config).toChartConfig()
        assertEquals(config, restored)

        val all = ChartConfig(
            style = ChartStyle.AREA,
            timeZone = ChartTimeZone.TOKYO,
            magnet = MagnetMode.NONE,
            enabled = ChartOverlay.entries.toSet()
        )
        assertEquals(all, AppSettings().withChartConfig(all).toChartConfig())

        val none = ChartConfig(enabled = emptySet())
        assertEquals(none, AppSettings().withChartConfig(none).toChartConfig())
        assertTrue(AppSettings().withChartConfig(none).toChartConfig().panes.isEmpty())
    }

    @Test
    fun `chart choices persist as json and survive an older payload`() {
        val settings = AppSettings().withChartConfig(
            ChartConfig(style = ChartStyle.OHLC_BARS, timeZone = ChartTimeZone.LONDON, magnet = MagnetMode.WEAK)
        )
        val json = gson.toJson(settings)
        val restored = gson.fromJson(json, AppSettings::class.java)

        assertEquals(ChartStyle.OHLC_BARS, restored.chartStyle)
        assertEquals(ChartTimeZone.LONDON, restored.chartTimeZone)
        assertEquals(MagnetMode.WEAK, restored.chartMagnet)
        assertEquals(settings.toChartConfig(), restored.toChartConfig())

        // WHY: Gson constructs through the no-arg constructor and assigns only the keys
        // present, so a payload written before these fields existed must still yield
        // usable defaults rather than nulls in non-null Kotlin fields.
        val legacy = gson.fromJson("""{"rsiPeriod":21,"showEma":false}""", AppSettings::class.java)
        assertEquals(21, legacy.rsiPeriod)
        assertFalse(legacy.showEma)
        assertEquals(ChartStyle.CANDLES, legacy.chartStyle)
        assertEquals(ChartTimeZone.DEVICE, legacy.chartTimeZone)
        assertEquals(MagnetMode.WEAK, legacy.chartMagnet)
        assertEquals(20, legacy.bollingerPeriod)
        assertEquals(2.0, legacy.bollingerStdDev, 1e-9)
        assertEquals(14, legacy.stochasticKPeriod)
        assertEquals(3, legacy.stochasticDPeriod)
        assertFalse(legacy.toChartConfig().isOn(ChartOverlay.EMA))
    }

    @Test
    fun `the wire spelling of every chart enum is frozen`() {
        // WHY: Gson maps enums by name, and an unknown name deserializes to null — which
        // would put null into a non-null field and crash on the next launch after a
        // rename. Pinning the spelling makes a rename a deliberate, test-visible change.
        // WHY the quotes are stripped: `toJson` of an enum yields a JSON string
        // literal, i.e. `"bars"` including the quote characters.
        assertEquals("heikin-ashi", wire(ChartStyle.HEIKIN_ASHI))
        assertEquals("hollow", wire(ChartStyle.HOLLOW_CANDLES))
        assertEquals("bars", wire(ChartStyle.OHLC_BARS))
        assertEquals("new_york", wire(ChartTimeZone.NEW_YORK))
        assertEquals("strong", wire(MagnetMode.STRONG))
        assertEquals("stochastic", wire(ChartOverlay.STOCHASTIC))

        // Every value must survive a serialize/deserialize round trip, which is what
        // an upgrade from a version that knew fewer enum constants has to tolerate.
        ChartStyle.entries.forEach { assertEquals(it, gson.fromJson(wireQuoted(it), ChartStyle::class.java)) }
        ChartTimeZone.entries.forEach { assertEquals(it, gson.fromJson(wireQuoted(it), ChartTimeZone::class.java)) }
        MagnetMode.entries.forEach { assertEquals(it, gson.fromJson(wireQuoted(it), MagnetMode::class.java)) }
        ChartOverlay.entries.forEach { assertEquals(it, gson.fromJson(wireQuoted(it), ChartOverlay::class.java)) }
    }

    private fun wire(value: Any): String = wireQuoted(value).trim('"')

    private fun wireQuoted(value: Any): String = gson.toJson(value)
}
