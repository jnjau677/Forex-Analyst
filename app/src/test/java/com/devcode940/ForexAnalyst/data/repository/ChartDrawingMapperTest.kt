package com.devcode940.ForexAnalyst.data.repository

import com.devcode940.ForexAnalyst.data.db.ChartDrawingEntity
import com.devcode940.ForexAnalyst.data.model.ChartDrawing
import com.devcode940.ForexAnalyst.data.model.ChartPoint
import com.devcode940.ForexAnalyst.data.model.DrawingId
import com.devcode940.ForexAnalyst.data.model.DrawingPalette
import com.devcode940.ForexAnalyst.data.model.DrawingStyle
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Round-trip tests for the drawing ↔ database-row mapping.
 *
 * WHY this is the highest-value persistence test in the project: a drawing is written
 * once and read back on every app start, forever. A swapped start/end column, a
 * renamed discriminator or a locale-dependent level string does not crash — it makes
 * the chart load with the user's objects missing, or with a fib fan at the wrong
 * prices, and there is no error anywhere to point at the cause. The instrumented Room
 * test proves the *table* works; this proves the *mapping* does.
 */
class ChartDrawingMapperTest {

    private val pair = PairSymbol("EURUSD")
    private val timeframe = Timeframe("H1")
    private val createdAt = Instant.parse("2024-06-01T12:00:00Z")
    private val start = ChartPoint(Instant.parse("2024-06-01T09:00:00Z"), 1.0800)
    private val end = ChartPoint(Instant.parse("2024-06-01T15:00:00Z"), 1.0950)

    private fun id(seed: String) = DrawingId(UUID.nameUUIDFromBytes(seed.toByteArray()))

    private fun entity(
        seed: String,
        kind: String,
        style: String = DrawingStyle.SOLID.name,
        startTime: Long? = null,
        startPrice: Double? = null,
        endTime: Long? = null,
        endPrice: Double? = null,
        levelsCsv: String? = null,
        side: String? = null,
        entryPrice: Double? = null,
        stopPrice: Double? = null,
        targetPrice: Double? = null,
        riskPercent: Double? = null,
        label: String = "",
        colorArgb: Int = 0xFF2962FF.toInt(),
        pairSymbol: String = pair.value,
        timeframeValue: String = timeframe.value
    ) = ChartDrawingEntity(
        id = id(seed).value.toString(),
        pairSymbol = pairSymbol,
        timeframe = timeframeValue,
        kind = kind,
        style = style,
        colorArgb = colorArgb,
        createdAt = createdAt,
        label = label,
        startTime = startTime,
        startPrice = startPrice,
        endTime = endTime,
        endPrice = endPrice,
        extendLeft = false,
        extendRight = false,
        levelsCsv = levelsCsv,
        side = side,
        entryPrice = entryPrice,
        stopPrice = stopPrice,
        targetPrice = targetPrice,
        riskPercent = riskPercent
    )

    private fun everySubtype(): List<ChartDrawing> = listOf(
        ChartDrawing.HorizontalLine(
            id = id("horizontal"), pairSymbol = pair, timeframe = timeframe,
            style = DrawingStyle.DASHED, colorArgb = 0xFF26A69A.toInt(),
            createdAt = createdAt, label = "Daily high", price = 1.0925
        ),
        ChartDrawing.VerticalLine(
            id = id("vertical"), pairSymbol = pair, timeframe = timeframe,
            style = DrawingStyle.DOTTED, colorArgb = 0xFFAB47BC.toInt(),
            createdAt = createdAt, label = "NFP", time = start.time
        ),
        ChartDrawing.TrendLine(
            id = id("trend"), pairSymbol = pair, timeframe = timeframe,
            style = DrawingStyle.SOLID, colorArgb = 0xFF2962FF.toInt(),
            createdAt = createdAt, label = "", start = start, end = end,
            extendLeft = true, extendRight = false
        ),
        ChartDrawing.FibRetracement(
            id = id("fib"), pairSymbol = pair, timeframe = timeframe,
            style = DrawingStyle.SOLID, colorArgb = 0xFFFFB74D.toInt(),
            createdAt = createdAt, label = "Swing", start = start, end = end,
            levels = listOf(0.0, 0.236, 0.382, 0.5, 0.618, 1.0)
        ),
        ChartDrawing.FibExtension(
            id = id("fibx"), pairSymbol = pair, timeframe = timeframe,
            style = DrawingStyle.SOLID, colorArgb = 0xFF66BB6A.toInt(),
            createdAt = createdAt, label = "", start = start, end = end,
            levels = listOf(0.0, 0.618, 1.0, 1.618)
        ),
        ChartDrawing.Rectangle(
            id = id("rect"), pairSymbol = pair, timeframe = timeframe,
            style = DrawingStyle.SOLID, colorArgb = 0xFFEF5350.toInt(),
            createdAt = createdAt, label = "Consolidation", start = start, end = end
        ),
        ChartDrawing.Measurement(
            id = id("measure"), pairSymbol = pair, timeframe = timeframe,
            style = DrawingStyle.DASHED, colorArgb = 0xFFECEFF1.toInt(),
            createdAt = createdAt, label = "", start = start, end = end
        ),
        ChartDrawing.Position(
            id = id("position"), pairSymbol = pair, timeframe = timeframe,
            style = DrawingStyle.SOLID, colorArgb = 0xFF26A69A.toInt(),
            createdAt = createdAt, label = "London break",
            side = TradeSide.LONG, entryPrice = 1.0850, stopPrice = 1.0810,
            targetPrice = 1.0930, riskPercent = 1.5
        )
    )

    @Test
    fun `every subtype survives the round trip unchanged`() {
        val drawings = everySubtype()
        assertEquals(8, drawings.size)
        drawings.forEach { drawing ->
            val restored = drawing.toEntity().toDomainOrNull()
            // WHY full equality and not field-by-field: these are data classes, so
            // equality covers every column including the label and colour. A field list
            // written by hand would silently omit the next column someone adds.
            assertEquals(drawing, restored)
        }
    }

    @Test
    fun `a short position round trips too`() {
        val short = ChartDrawing.Position(
            id = id("short"), pairSymbol = pair, timeframe = timeframe,
            style = DrawingStyle.SOLID, colorArgb = 0xFFEF5350.toInt(),
            createdAt = createdAt, label = "",
            side = TradeSide.SHORT, entryPrice = 1.0850, stopPrice = 1.0890,
            targetPrice = 1.0770, riskPercent = 2.0
        )
        assertEquals(short, short.toEntity().toDomainOrNull())
        assertEquals("SHORT", short.toEntity().side)
    }

    @Test
    fun `the discriminator spellings are frozen`() {
        // WHY: the kind string is written to SQLite once and read forever. If it were
        // derived from `::class.simpleName`, renaming a Kotlin class would orphan every
        // stored row — with no compile error and no runtime error, just an empty chart.
        val kinds = everySubtype().map { it.toEntity().kind }
        assertEquals(
            listOf(
                "HORIZONTAL_LINE", "VERTICAL_LINE", "TREND_LINE", "FIB_RETRACEMENT",
                "FIB_EXTENSION", "RECTANGLE", "MEASUREMENT", "POSITION"
            ),
            kinds
        )
        assertEquals(kinds.size, kinds.distinct().size)
    }

    @Test
    fun `each tool writes only the columns it owns`() {
        val horizontal = everySubtype()[0].toEntity()
        assertEquals(1.0925, requireNotNull(horizontal.startPrice), 0.0)
        // WHY null matters as much as the value: a horizontal line has no time anchor,
        // and storing one would make a later "does this row have two anchors?" check
        // answer yes for a single-price object.
        assertNull(horizontal.startTime)
        assertNull(horizontal.endTime)
        assertNull(horizontal.endPrice)
        assertNull(horizontal.levelsCsv)
        assertNull(horizontal.side)
        assertFalse(horizontal.extendLeft)
        assertFalse(horizontal.extendRight)

        val vertical = everySubtype()[1].toEntity()
        assertEquals(start.time.toEpochMilli(), vertical.startTime)
        assertNull(vertical.startPrice)
        assertNull(vertical.endPrice)
        assertNull(vertical.endTime)

        val trend = everySubtype()[2].toEntity()
        assertTrue(trend.extendLeft)
        assertFalse(trend.extendRight)
        assertEquals(start.time.toEpochMilli(), trend.startTime)
        assertEquals(end.time.toEpochMilli(), trend.endTime)

        val position = everySubtype()[7].toEntity()
        // A position stores its three prices, not an anchor pair — dragging it moves the
        // whole plan, so the geometry is derived from entry/stop/target at draw time.
        assertNull(position.startTime)
        assertNull(position.startPrice)
        assertEquals(1.0850, requireNotNull(position.entryPrice), 0.0)
        assertEquals(1.0810, requireNotNull(position.stopPrice), 0.0)
        assertEquals(1.0930, requireNotNull(position.targetPrice), 0.0)
        assertEquals(1.5, requireNotNull(position.riskPercent), 0.0)
    }

    @Test
    fun `fib levels are stored as a locale independent string`() {
        val default = Locale.getDefault()
        try {
            // WHY: a comma decimal separator in the CSV would either throw on read or,
            // worse, split "0,236" into two bogus levels.
            Locale.setDefault(Locale.GERMANY)
            val csv = listOf(0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0).toLevelsCsv()
            assertEquals("0.0,0.236,0.382,0.5,0.618,0.786,1.0", csv)
            assertEquals(listOf(0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0), csv.toLevelsOrNull())

            val fib = everySubtype()[3]
            assertEquals(fib, fib.toEntity().toDomainOrNull())
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun `levels csv parsing tolerates gaps and rejects junk`() {
        assertNull(null.toLevelsOrNull())
        // WHY null and not an empty list: an empty level list is invalid, and the
        // caller falls back to the tool's default fan when the column is unusable.
        assertNull("".toLevelsOrNull())
        assertEquals(listOf(0.5), "0.5".toLevelsOrNull())
        assertEquals(listOf(0.0, 1.0), " 0.0 , 1.0 ".toLevelsOrNull())
        // WHY strict rather than lenient: the mapper wraps this in `runCatching`, so a
        // throw becomes "skip this row" — a fib fan with two invented levels would be
        // far worse than one missing object.
        assertThrows(NumberFormatException::class.java) { "0.5,,1.0".toLevelsOrNull() }
        assertThrows(NumberFormatException::class.java) { "a,b".toLevelsOrNull() }
    }

    @Test
    fun `a row that cannot be reconstructed is skipped, not thrown`() {
        // WHY: one corrupt row (an older build, a partial write, hand-edited storage)
        // must not take down the whole chart's object list. `mapNotNull` drops it and
        // the rest still render.
        assertNull(entity("x", kind = "HORIZONTAL_LINE").toDomainOrNull()) // no price
        assertNull(entity("x", kind = "VERTICAL_LINE").toDomainOrNull()) // no time
        assertNull(
            entity("x", kind = "TREND_LINE", startTime = 1L, startPrice = 1.0).toDomainOrNull()
        ) // missing end anchor
        assertNull(
            entity(
                "x", kind = "POSITION", side = "LONG", entryPrice = 1.0,
                stopPrice = 1.5, targetPrice = 0.5, riskPercent = 1.0
            ).toDomainOrNull()
        ) // a long whose stop sits above its entry
        assertNull(
            entity(
                "x", kind = "POSITION", side = "LONG", entryPrice = 1.0,
                stopPrice = 0.5, targetPrice = 1.5, riskPercent = 500.0
            ).toDomainOrNull()
        ) // risk outside 0.01..100
        assertNull(entity("x", kind = "NOT_A_TOOL", startPrice = 1.0).toDomainOrNull())
        assertNull(entity("x", kind = "HORIZONTAL_LINE", startPrice = Double.NaN).toDomainOrNull())
        assertNull(
            entity("x", kind = "HORIZONTAL_LINE", startPrice = 1.0, style = "WAVY").toDomainOrNull()
        ) // unknown style name
        assertNull(
            entity("x", kind = "FIB_RETRACEMENT", startTime = 1L, startPrice = 1.0,
                endTime = 2L, endPrice = 2.0, levelsCsv = "junk").toDomainOrNull()
        )
    }

    @Test
    fun `an invalid id or symbol is skipped`() {
        val broken = entity("x", kind = "HORIZONTAL_LINE", startPrice = 1.0)
        assertNull(broken.copy(id = "not-a-uuid").toDomainOrNull())
        // WHY the symbol is rejected rather than stored raw: PairSymbol validates its
        // shape, so a row with a blank symbol cannot enter the domain.
        assertNull(broken.copy(pairSymbol = "").toDomainOrNull())
        assertNull(broken.copy(timeframe = "H9").toDomainOrNull())
        assertNull(broken.copy(timeframe = "").toDomainOrNull())
    }

    @Test
    fun `labels and colours are carried through`() {
        val labelled = everySubtype()[0] as ChartDrawing.HorizontalLine
        val restored = labelled.toEntity().toDomainOrNull() as ChartDrawing.HorizontalLine
        assertEquals("Daily high", restored.label)
        assertEquals(labelled.colorArgb, restored.colorArgb)
        assertEquals(labelled.style, restored.style)
        assertEquals(labelled.id, restored.id)
        assertEquals(pair, restored.pairSymbol)
        assertEquals(timeframe, restored.timeframe)
        assertEquals(createdAt, restored.createdAt)

        val blank = ChartDrawing.Rectangle(
            id = id("blank"), pairSymbol = pair, timeframe = timeframe,
            style = DrawingStyle.SOLID, colorArgb = DrawingPalette.DEFAULT,
            createdAt = createdAt, label = "", start = start, end = end
        )
        assertEquals("", blank.toEntity().label)
        assertEquals(blank, blank.toEntity().toDomainOrNull())
    }

    @Test
    fun `the mapping is deterministic for identical drawings`() {
        val drawings = everySubtype()
        val once = drawings.map { it.toEntity() }
        val twice = drawings.map { it.toEntity() }
        // WHY: an entity is the primary-key carrier for a REPLACE insert, so two
        // mappings of the same object must produce the same row — a fresh id or
        // timestamp per call would duplicate every drawing on each save.
        assertEquals(once, twice)
        assertEquals(once.map { it.id }, twice.map { it.id })
    }
}
