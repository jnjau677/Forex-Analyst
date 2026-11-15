package com.devcode940.ForexAnalyst.ui.viewmodel

import com.devcode940.ForexAnalyst.awaitSettled
import com.devcode940.ForexAnalyst.data.chart.ChartViewport
import com.devcode940.ForexAnalyst.data.chart.CrosshairData
import com.devcode940.ForexAnalyst.data.model.AppError
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.ChartDrawing
import com.devcode940.ForexAnalyst.data.model.ChartOverlay
import com.devcode940.ForexAnalyst.data.model.ChartPoint
import com.devcode940.ForexAnalyst.data.model.ChartStyle
import com.devcode940.ForexAnalyst.data.model.ChartTimeZone
import com.devcode940.ForexAnalyst.data.model.ChartToolMode
import com.devcode940.ForexAnalyst.data.model.DrawingId
import com.devcode940.ForexAnalyst.data.model.DrawingPalette
import com.devcode940.ForexAnalyst.data.model.DrawingStyle
import com.devcode940.ForexAnalyst.data.model.MagnetMode
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Result
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.repository.FakeMarketDataRepository
import com.devcode940.ForexAnalyst.data.repository.PriceGenerator
import com.devcode940.ForexAnalyst.data.settings.AppSettings
import com.devcode940.ForexAnalyst.data.settings.ChartSettingsWriter
import com.devcode940.ForexAnalyst.ui.chart.ChartEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * Tests for the chart's interactive layer: toolbar events, the drawing lifecycle,
 * undo/redo persistence, and how display choices reach the rendered snapshot.
 *
 * WHY a separate class from [ForexViewModelTest]: that suite covers market data,
 * signals and alerts. Everything here exercises state that only exists because the
 * chart became an editor — a half-finished gesture, a selection, an undo stack — and
 * the failure modes are correspondingly different: not "wrong number" but "the object
 * went to the wrong chart" or "one tap deleted the user's work".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChartInteractionTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: FakeMarketDataRepository
    private lateinit var settings: MutableStateFlow<AppSettings>

    private val clock: Clock = Clock.fixed(Instant.parse("2024-06-01T12:00:00Z"), ZoneOffset.UTC)
    private val pair = PairSymbol("EURUSD")
    private val timeframe = Timeframe("H1")

    private val firstAnchor = ChartPoint(Instant.parse("2024-06-01T09:00:00Z"), 1.0800)
    private val secondAnchor = ChartPoint(Instant.parse("2024-06-01T11:00:00Z"), 1.0950)

    private lateinit var originalZone: java.util.TimeZone

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeMarketDataRepository()
        settings = MutableStateFlow(AppSettings())
        // WHY pin the JVM default zone: `ChartTimeZone.DEVICE` resolves through
        // `ZoneId.systemDefault()`, so without this the session-anchored indicator test
        // would compare against whichever zone the build machine happens to be in.
        originalZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        java.util.TimeZone.setDefault(originalZone)
        Dispatchers.resetMain()
    }

    private fun seededCandles(count: Int = 300) =
        PriceGenerator.generateCandles(count = count, random = Random(42), clock = clock)

    /**
     * Builds the ViewModel with [candles] already loaded.
     *
     * WHY the barrier: the load computes indicators on `Dispatchers.Default`, a real
     * thread the test scheduler does not wait for. Asserting straight after the
     * constructor would race it.
     */
    private fun viewModel(candles: List<CandleStick> = seededCandles()): ForexViewModel {
        repository.candleResult = Result.Success(candles)
        return ForexViewModel(
            repository = repository,
            settings = settings,
            settingsWriter = ChartSettingsWriter { transform -> settings.value = transform(settings.value) },
            clock = clock
        ).also { created -> awaitSettled("the initial candle load") { !created.isLoading.value } }
    }

    /** Three bars straddling midnight in Nairobi (UTC+3) but not in UTC. */
    private fun zoneProbeCandles() = listOf(
        candle("2024-03-15T20:00:00Z", open = 1.1000, high = 1.1010, low = 1.0990, close = 1.1005, volume = 100L),
        candle("2024-03-15T22:00:00Z", open = 1.1005, high = 1.1050, low = 1.1000, close = 1.1040, volume = 200L),
        candle("2024-03-16T00:00:00Z", open = 1.1040, high = 1.1060, low = 1.1030, close = 1.1055, volume = 150L)
    )

    private fun candle(
        time: String,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Long
    ) = CandleStick(
        time = Instant.parse(time),
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume
    )

    /** Arms [tool] and drags between the two anchors, returning the committed object. */
    private fun draw(
        viewModel: ForexViewModel,
        tool: ChartToolMode,
        start: ChartPoint = firstAnchor,
        end: ChartPoint = secondAnchor
    ): ChartDrawing? {
        viewModel.onChartEvent(ChartEvent.ToolSelected(tool))
        viewModel.onChartEvent(ChartEvent.DraftStarted(start))
        viewModel.onChartEvent(ChartEvent.DraftMoved(end))
        viewModel.onChartEvent(ChartEvent.DraftFinished(end))
        return viewModel.chartState.value.drawings.lastOrNull()
    }

    // ---------- Toolbar → settings ----------

    @Test
    fun `every toolbar choice is persisted, not just held in memory`() = runTest {
        val viewModel = viewModel()

        viewModel.onChartEvent(ChartEvent.OverlayToggled(ChartOverlay.MACD))
        viewModel.onChartEvent(ChartEvent.StyleSelected(ChartStyle.HEIKIN_ASHI))
        viewModel.onChartEvent(ChartEvent.TimeZoneSelected(ChartTimeZone.NAIROBI))
        viewModel.onChartEvent(ChartEvent.MagnetSelected(MagnetMode.STRONG))

        val persisted = settings.value
        assertTrue(persisted.showMacd)
        assertEquals(ChartStyle.HEIKIN_ASHI, persisted.chartStyle)
        assertEquals(ChartTimeZone.NAIROBI, persisted.chartTimeZone)
        assertEquals(MagnetMode.STRONG, persisted.chartMagnet)

        // WHY assert the snapshot too: the toolbar renders from `chartState.config`, so
        // a write that reached the file but not the state would look like a dead switch
        // until the next process start.
        val config = viewModel.chartState.value.config
        assertTrue(config.isOn(ChartOverlay.MACD))
        assertEquals(ChartStyle.HEIKIN_ASHI, config.style)
        assertEquals(ChartTimeZone.NAIROBI, config.timeZone)
        assertEquals(MagnetMode.STRONG, config.magnet)
    }

    @Test
    fun `toggling an overlay twice returns to the stored default`() = runTest {
        val viewModel = viewModel()
        val before = settings.value

        viewModel.onChartEvent(ChartEvent.OverlayToggled(ChartOverlay.RSI))
        assertFalse(settings.value.showRsi)
        viewModel.onChartEvent(ChartEvent.OverlayToggled(ChartOverlay.RSI))

        assertEquals(before.showRsi, settings.value.showRsi)
        assertTrue(viewModel.chartState.value.config.isOn(ChartOverlay.RSI))
    }

    @Test
    fun `heikin ashi restyles the drawn series while indicators keep using real prices`() = runTest {
        val raw = seededCandles(60)
        val viewModel = viewModel(raw)
        val indicatorsBefore = viewModel.indicators.value

        viewModel.onChartEvent(ChartEvent.StyleSelected(ChartStyle.HEIKIN_ASHI))

        val drawn = viewModel.chartState.value.candles
        assertEquals(raw.size, drawn.size)
        // WHY the raw list must stay untouched: signals, alerts and every indicator are
        // computed from real prices. A smoothed close would silently change an RSI and
        // therefore the trade signal — the chart style would become a data change.
        assertEquals(raw, viewModel.candles.value)
        assertNotEquals(raw, drawn)
        assertEquals((raw[0].open + raw[0].close) / 2.0, drawn[0].open, 1e-9)
        assertEquals((raw[0].open + raw[0].high + raw[0].low + raw[0].close) / 4.0, drawn[0].close, 1e-9)
        // Timestamps and volume drive the axis and the volume pane, so they survive.
        assertEquals(raw.map { it.time }, drawn.map { it.time })
        assertEquals(raw.map { it.volume }, drawn.map { it.volume })

        assertSame(indicatorsBefore, viewModel.chartState.value.indicators)
        assertEquals(indicatorsBefore, viewModel.indicators.value)

        viewModel.onChartEvent(ChartEvent.StyleSelected(ChartStyle.CANDLES))
        assertEquals(raw, viewModel.chartState.value.candles)
    }

    @Test
    fun `a time zone change re-anchors the session based indicator`() = runTest {
        val viewModel = viewModel(zoneProbeCandles())
        // The default zone is UTC (pinned in setUp), so this is the UTC-anchored series.
        val utcVwap = requireNotNull(viewModel.indicators.value.vwap)
        assertEquals(3, utcVwap.size)

        viewModel.onChartEvent(ChartEvent.TimeZoneSelected(ChartTimeZone.NAIROBI))
        // WHY this needs a real wait: the recompute runs on Dispatchers.Default, a
        // thread the test scheduler does not drive.
        awaitSettled("the Nairobi indicator pass") { viewModel.indicators.value.vwap != utcVwap }
        val nairobiVwap = requireNotNull(viewModel.indicators.value.vwap)

        // Bar 0 (20:00Z) anchors the session in both zones, so the two agree there.
        assertEquals(utcVwap[0], nairobiVwap[0])
        // Bar 1 is 22:00Z: still the same UTC day, so the UTC VWAP accumulates, while
        // Nairobi has already rolled past midnight and re-anchors on the bar itself.
        // WHY this is the assertion that matters: a VWAP that ignores the chart zone
        // resets at a moment the user cannot see on the axis, so the line disagrees with
        // the day boundary drawn underneath it.
        assertNotEquals(utcVwap[1], nairobiVwap[1])
        // The chart snapshot carries the same values the axis is labelled with.
        assertEquals(nairobiVwap, viewModel.chartState.value.indicators.vwap)
        assertEquals(ChartTimeZone.NAIROBI, viewModel.chartState.value.config.timeZone)
        assertEquals(ChartTimeZone.NAIROBI, settings.value.chartTimeZone)
    }

    @Test
    fun `a style change alone does not recompute indicators`() = runTest {
        val viewModel = viewModel()
        val before = viewModel.indicators.value

        viewModel.onChartEvent(ChartEvent.StyleSelected(ChartStyle.LINE))
        viewModel.onChartEvent(ChartEvent.OverlayToggled(ChartOverlay.BOLLINGER))

        // WHY: recomputing nine indicator passes on every toggle would stall the frame
        // the user tapped in. Toggles decide what is drawn, not what is calculated.
        assertSame(before, viewModel.indicators.value)
    }

    // ---------- Tool arming ----------

    @Test
    fun `arming a drawing tool drops the draft, the selection and the crosshair`() = runTest {
        val viewModel = viewModel()
        val placed = requireNotNull(draw(viewModel, ChartToolMode.TREND_LINE))

        viewModel.onChartEvent(
            ChartEvent.CrosshairChanged(CrosshairData(price = 1.0825, time = firstAnchor.time, candle = null))
        )
        viewModel.onChartEvent(ChartEvent.ToolSelected(ChartToolMode.TREND_LINE))
        viewModel.onChartEvent(ChartEvent.DraftStarted(firstAnchor))
        viewModel.onChartEvent(ChartEvent.DrawingSelected(placed.id))

        // WHY arming clears all three: a half-finished object from the previous gesture
        // would be committed by the next release, a selected object invites an edit the
        // user no longer means, and a drawing tool that also moves the crosshair gives
        // two answers to one gesture.
        viewModel.onChartEvent(ChartEvent.ToolSelected(ChartToolMode.FIBONACCI))
        val state = viewModel.chartState.value
        assertEquals(ChartToolMode.FIBONACCI, state.activeTool)
        assertNull(state.draft)
        assertNull(state.selectedDrawingId)
        assertNull(state.crosshair)
        assertEquals(listOf(placed), state.drawings)
    }

    @Test
    fun `navigation modes keep the crosshair`() = runTest {
        val viewModel = viewModel()
        val crosshair = CrosshairData(price = 1.0825, time = firstAnchor.time, candle = null)
        viewModel.onChartEvent(ChartEvent.CrosshairChanged(crosshair))

        viewModel.onChartEvent(ChartEvent.ToolSelected(ChartToolMode.PAN))
        assertEquals(crosshair, viewModel.chartState.value.crosshair)
        viewModel.onChartEvent(ChartEvent.ToolSelected(ChartToolMode.NONE))
        assertEquals(crosshair, viewModel.chartState.value.crosshair)
    }

    @Test
    fun `draft events are ignored while no tool is armed`() = runTest {
        val viewModel = viewModel()
        assertEquals(ChartToolMode.NONE, viewModel.chartState.value.activeTool)

        viewModel.onChartEvent(ChartEvent.DraftStarted(firstAnchor))
        viewModel.onChartEvent(ChartEvent.DraftMoved(secondAnchor))
        viewModel.onChartEvent(ChartEvent.DraftFinished(secondAnchor))

        // WHY: a chart in crosshair mode must not place objects. Without this guard a
        // stray drag on the price pane would leave a trend line the user never asked
        // for — and they would blame the app for "drawing on its own".
        assertNull(viewModel.chartState.value.draft)
        assertTrue(viewModel.chartState.value.drawings.isEmpty())
        assertTrue(repository.savedDrawings.isEmpty())
        assertNull(viewModel.errorMessage.value)

        viewModel.onChartEvent(ChartEvent.ToolSelected(ChartToolMode.PAN))
        viewModel.onChartEvent(ChartEvent.DraftStarted(firstAnchor))
        assertNull(viewModel.chartState.value.draft)
    }

    // ---------- Drawing lifecycle ----------

    @Test
    fun `a completed drag commits, selects, disarms and persists`() = runTest {
        val viewModel = viewModel()

        val placed = requireNotNull(draw(viewModel, ChartToolMode.TREND_LINE))

        val state = viewModel.chartState.value
        assertEquals(listOf(placed), state.drawings)
        assertNull(state.draft)
        // WHY the tool disarms: after placing an object the next tap is almost always
        // meant to read the crosshair, and a still-armed tool would silently drop
        // another object instead.
        assertEquals(ChartToolMode.NONE, state.activeTool)
        assertEquals(placed.id, state.selectedDrawingId)
        assertNull(viewModel.errorMessage.value)

        assertEquals(listOf(placed), repository.savedDrawings)
        assertEquals(pair, placed.pairSymbol)
        assertEquals(timeframe, placed.timeframe)
        assertEquals(clock.instant(), placed.createdAt)
        val trend = placed as ChartDrawing.TrendLine
        assertEquals(firstAnchor, trend.start)
        assertEquals(secondAnchor, trend.end)
        assertFalse(trend.extendLeft)
        // A trend line projects forward only; an extended line would project both ways.
        assertTrue(trend.extendRight)
        assertFalse(viewModel.drawingHistory.value.canRedo)
        assertTrue(viewModel.drawingHistory.value.canUndo)
    }

    @Test
    fun `one anchor tools are placed by a tap, two anchor tools are not`() = runTest {
        val viewModel = viewModel()

        // A horizontal line is fully described by one price, so a tap is enough.
        val tapped = requireNotNull(draw(viewModel, ChartToolMode.HORIZONTAL_LINE, firstAnchor, firstAnchor))
        assertTrue(tapped is ChartDrawing.HorizontalLine)
        assertEquals(firstAnchor.price, (tapped as ChartDrawing.HorizontalLine).price, 0.0)
        assertNull(viewModel.errorMessage.value)

        // A trend line needs two distinct points; a tap has no extent to measure.
        viewModel.onChartEvent(ChartEvent.ToolSelected(ChartToolMode.TREND_LINE))
        viewModel.onChartEvent(ChartEvent.DraftStarted(secondAnchor))
        viewModel.onChartEvent(ChartEvent.DraftFinished(secondAnchor))

        // WHY a message rather than silence: the gesture produced nothing, and a chart
        // that ignores a drag looks frozen.
        assertEquals(1, viewModel.chartState.value.drawings.size)
        assertNull(viewModel.chartState.value.draft)
        assertEquals(ChartToolMode.TREND_LINE, viewModel.chartState.value.activeTool)
        assertEquals("Drag between two points to place that object", viewModel.errorMessage.value)
        assertEquals(1, repository.savedDrawings.size)
    }

    @Test
    fun `the pending style and colour are applied to the next drawing`() = runTest {
        val viewModel = viewModel()
        viewModel.onChartEvent(ChartEvent.LineStyleSelected(DrawingStyle.DASHED))
        viewModel.onChartEvent(ChartEvent.ColorSelected(DrawingPalette.SHORT))
        assertEquals(DrawingStyle.DASHED, viewModel.chartState.value.pendingStyle)
        assertEquals(DrawingPalette.SHORT, viewModel.chartState.value.pendingColorArgb)

        val placed = requireNotNull(draw(viewModel, ChartToolMode.RECTANGLE))

        assertEquals(DrawingStyle.DASHED, placed.style)
        assertEquals(DrawingPalette.SHORT, placed.colorArgb)
        // WHY the choice is sticky: a user annotating a chart picks a colour once and
        // then draws six objects. Resetting to the default after each commit would make
        // the palette a per-object dialog.
        assertEquals(DrawingStyle.DASHED, viewModel.chartState.value.pendingStyle)
    }

    @Test
    fun `every toolbar tool produces the matching object`() = runTest {
        val viewModel = viewModel()
        val tools = listOf(
            ChartToolMode.HORIZONTAL_LINE to ChartDrawing.HorizontalLine::class,
            ChartToolMode.VERTICAL_LINE to ChartDrawing.VerticalLine::class,
            ChartToolMode.TREND_LINE to ChartDrawing.TrendLine::class,
            ChartToolMode.RAY to ChartDrawing.TrendLine::class,
            ChartToolMode.EXTENDED_LINE to ChartDrawing.TrendLine::class,
            ChartToolMode.FIBONACCI to ChartDrawing.FibRetracement::class,
            ChartToolMode.FIB_EXTENSION to ChartDrawing.FibExtension::class,
            ChartToolMode.RECTANGLE to ChartDrawing.Rectangle::class,
            ChartToolMode.MEASURE to ChartDrawing.Measurement::class,
            ChartToolMode.LONG_POSITION to ChartDrawing.Position::class,
            ChartToolMode.SHORT_POSITION to ChartDrawing.Position::class
        )

        tools.forEachIndexed { index, (tool, expected) ->
            val placed = draw(viewModel, tool)
            assertNotNull("$tool produced nothing", placed)
            assertEquals("$tool produced ${placed?.let { it::class.simpleName }}", expected, placed!!::class)
            // WHY distinct ids: two objects sharing an id would make selecting one
            // select both, and deleting one delete both.
            assertEquals(index + 1, viewModel.chartState.value.drawings.map { it.id }.distinct().size)
        }
        assertEquals(tools.size, repository.savedDrawings.size)
    }

    @Test
    fun `a ray projects forward and an extended line projects both ways`() = runTest {
        val viewModel = viewModel()
        val ray = requireNotNull(draw(viewModel, ChartToolMode.RAY)) as ChartDrawing.TrendLine
        assertFalse(ray.extendLeft)
        assertTrue(ray.extendRight)

        val extended = requireNotNull(draw(viewModel, ChartToolMode.EXTENDED_LINE)) as ChartDrawing.TrendLine
        assertTrue(extended.extendLeft)
        assertTrue(extended.extendRight)

        val plain = requireNotNull(draw(viewModel, ChartToolMode.TREND_LINE)) as ChartDrawing.TrendLine
        assertFalse(plain.extendLeft)
        assertFalse(plain.extendRight)
    }

    @Test
    fun `a position plan stores entry, stop and target on the correct sides`() = runTest {
        val viewModel = viewModel()

        // Dragging *down* from the entry still produces a long plan whose stop sits
        // below it: the drag defines the risk distance, not the direction.
        val long = requireNotNull(
            draw(viewModel, ChartToolMode.LONG_POSITION, firstAnchor, ChartPoint(secondAnchor.time, 1.0750))
        ) as ChartDrawing.Position
        assertEquals(firstAnchor.price, long.entryPrice, 0.0)
        assertTrue(long.stopPrice < long.entryPrice)
        assertTrue(long.targetPrice > long.entryPrice)
        assertEquals(long.entryPrice - long.stopPrice, long.targetPrice - long.entryPrice, 1e-9)

        val short = requireNotNull(
            draw(viewModel, ChartToolMode.SHORT_POSITION, firstAnchor, ChartPoint(secondAnchor.time, 1.0850))
        ) as ChartDrawing.Position
        assertTrue(short.stopPrice > short.entryPrice)
        assertTrue(short.targetPrice < short.entryPrice)
    }

    // ---------- Undo, redo, delete, clear ----------

    @Test
    fun `undo and redo replace the stored set instead of writing single rows`() = runTest {
        val viewModel = viewModel()
        val first = requireNotNull(draw(viewModel, ChartToolMode.TREND_LINE))
        val second = requireNotNull(draw(viewModel, ChartToolMode.HORIZONTAL_LINE))
        assertEquals(listOf(first, second), viewModel.chartState.value.drawings)
        assertTrue(repository.replacedDrawingCharts.isEmpty())

        viewModel.onChartEvent(ChartEvent.Undo)

        assertEquals(listOf(first), viewModel.chartState.value.drawings)
        // WHY a whole-set replace: undo restored a state by *removing* an object, which
        // no single-row insert or delete can express. Persisting the snapshot keeps the
        // database and the chart identical after a process restart.
        assertEquals(listOf(pair to timeframe), repository.replacedDrawingCharts)
        assertFalse(viewModel.drawingHistory.value.canUndo)
        assertTrue(viewModel.drawingHistory.value.canRedo)

        viewModel.onChartEvent(ChartEvent.Redo)

        assertEquals(listOf(first, second), viewModel.chartState.value.drawings)
        assertEquals(2, repository.replacedDrawingCharts.size)
        assertTrue(viewModel.drawingHistory.value.canUndo)
        assertFalse(viewModel.drawingHistory.value.canRedo)
    }

    @Test
    fun `undo and redo are no-ops at the ends of history`() = runTest {
        val viewModel = viewModel()
        assertFalse(viewModel.drawingHistory.value.canUndo)

        viewModel.onChartEvent(ChartEvent.Undo)
        viewModel.onChartEvent(ChartEvent.Redo)

        // WHY nothing is written: an undo that cannot step back must not persist an
        // empty snapshot over the user's stored drawings.
        assertTrue(repository.replacedDrawingCharts.isEmpty())
        assertTrue(viewModel.chartState.value.drawings.isEmpty())
    }

    @Test
    fun `deleting the selected drawing clears the selection and writes through`() = runTest {
        val viewModel = viewModel()
        val placed = requireNotNull(draw(viewModel, ChartToolMode.FIBONACCI))
        assertEquals(placed.id, viewModel.chartState.value.selectedDrawingId)

        viewModel.onChartEvent(ChartEvent.DrawingDeleted(placed.id))

        assertTrue(viewModel.chartState.value.drawings.isEmpty())
        assertNull(viewModel.chartState.value.selectedDrawingId)
        assertEquals(listOf(placed.id), repository.deletedDrawingIds)
        assertTrue(viewModel.drawingHistory.value.canUndo)

        // Deleting twice must not consume a second undo step or write twice.
        viewModel.onChartEvent(ChartEvent.DrawingDeleted(placed.id))
        assertEquals(listOf(placed.id), repository.deletedDrawingIds)
    }

    @Test
    fun `the toolbar delete button removes the selection`() = runTest {
        val viewModel = viewModel()
        val placed = requireNotNull(draw(viewModel, ChartToolMode.RECTANGLE))

        viewModel.deleteSelectedDrawing()

        assertTrue(viewModel.chartState.value.drawings.isEmpty())
        assertEquals(listOf(placed.id), repository.deletedDrawingIds)

        // With nothing selected the button is a no-op, not a crash.
        viewModel.deleteSelectedDrawing()
        assertEquals(1, repository.deletedDrawingIds.size)
    }

    @Test
    fun `a delete failure is reported rather than swallowed`() = runTest {
        val viewModel = viewModel()
        val placed = requireNotNull(draw(viewModel, ChartToolMode.MEASURE))
        repository.drawingResult = Result.Failure(AppError.Storage)

        viewModel.onChartEvent(ChartEvent.DrawingDeleted(placed.id))

        // WHY the object still leaves the screen: the store is the write-through cache,
        // so the edit is applied optimistically and the failure is surfaced as a banner.
        // Reverting silently would make the delete button look broken.
        assertTrue(viewModel.chartState.value.drawings.isEmpty())
        assertEquals(AppError.Storage.userMessage, viewModel.errorMessage.value)
    }

    @Test
    fun `clearing removes only the active chart`() = runTest {
        val viewModel = viewModel()
        val onH1 = requireNotNull(draw(viewModel, ChartToolMode.TREND_LINE))

        viewModel.selectTimeframe(Timeframe("H4"))
        awaitSettled("the H4 load") { !viewModel.isLoading.value }
        assertTrue(viewModel.chartState.value.drawings.isEmpty())
        val onH4 = requireNotNull(draw(viewModel, ChartToolMode.HORIZONTAL_LINE))

        viewModel.onChartEvent(ChartEvent.ClearDrawings)

        assertTrue(viewModel.chartState.value.drawings.isEmpty())
        assertEquals(listOf(pair to Timeframe("H4")), repository.clearedDrawingCharts)
        // WHY scoping is the whole point: drawings belong to a chart, and clearing the
        // H4 objects must not touch the analysis the user built on H1.
        viewModel.selectTimeframe(timeframe)
        awaitSettled("the H1 reload") { !viewModel.isLoading.value }
        assertEquals(listOf(onH1), viewModel.chartState.value.drawings)
        assertNotEquals(onH1.id, onH4.id)
    }

    @Test
    fun `a drawing committed while a chart is loading is filed under the selected chart`() = runTest {
        val viewModel = viewModel()

        // WHY: switching timeframe publishes the new identity immediately and then loads
        // the candles. A gesture that lands during the load must be stored against the
        // chart the user selected, not the one they left.
        viewModel.selectTimeframe(Timeframe("D1"))
        val placed = requireNotNull(draw(viewModel, ChartToolMode.VERTICAL_LINE))

        assertEquals(Timeframe("D1"), placed.timeframe)
        assertEquals(Timeframe("D1"), viewModel.chartState.value.timeframe)
        awaitSettled("the D1 load") { !viewModel.isLoading.value }
        assertEquals(listOf(placed), viewModel.chartState.value.drawings)
    }

    // ---------- Reconciliation with the database ----------

    @Test
    fun `drawings loaded from the database are adopted without enabling undo`() = runTest {
        val viewModel = viewModel()
        val stored = ChartDrawing.HorizontalLine(
            id = DrawingId.generate(),
            pairSymbol = pair,
            timeframe = timeframe,
            style = DrawingStyle.SOLID,
            colorArgb = DrawingPalette.DEFAULT,
            createdAt = Instant.parse("2024-05-30T08:00:00Z"),
            label = "Loaded from disk",
            price = 1.0700
        )

        repository.emitDrawings(pair, timeframe, listOf(stored))

        assertEquals(listOf(stored), viewModel.chartState.value.drawings)
        // WHY this assertion is the important one: adopting persisted state is not an
        // action the user took, so it must not become an undo step. If it did, Undo
        // would be enabled on a freshly opened chart and the first press would persist
        // the empty baseline — deleting every drawing the user ever saved.
        assertFalse(viewModel.drawingHistory.value.canUndo)
        assertFalse(viewModel.drawingHistory.value.canRedo)

        viewModel.onChartEvent(ChartEvent.Undo)

        assertEquals(listOf(stored), viewModel.chartState.value.drawings)
        assertTrue(repository.replacedDrawingCharts.isEmpty())
        assertTrue(repository.deletedDrawingIds.isEmpty())
    }

    @Test
    fun `our own write does not create a duplicate history entry`() = runTest {
        val viewModel = viewModel()
        val placed = requireNotNull(draw(viewModel, ChartToolMode.TREND_LINE))

        viewModel.onChartEvent(ChartEvent.Undo)
        viewModel.onChartEvent(ChartEvent.Redo)

        // WHY: undo/redo persist a whole-set replace, and the fake (like Room) echoes
        // that set back through `observeDrawings`. Re-adopting our own echo would push
        // another snapshot, so Undo would appear to do nothing — the user presses it
        // twice and the chart only moves once.
        assertEquals(listOf(placed), viewModel.chartState.value.drawings)
        assertTrue(viewModel.drawingHistory.value.canUndo)
        assertFalse(viewModel.drawingHistory.value.canRedo)
        assertEquals(2, repository.replacedDrawingCharts.size)
    }

    @Test
    fun `a drawing from another chart does not leak into this one`() = runTest {
        val viewModel = viewModel()
        val elsewhere = ChartDrawing.Measurement(
            id = DrawingId.generate(),
            pairSymbol = PairSymbol("USDJPY"),
            timeframe = Timeframe("M15"),
            style = DrawingStyle.DASHED,
            colorArgb = DrawingPalette.DEFAULT,
            createdAt = Instant.parse("2024-05-30T08:00:00Z"),
            label = "",
            start = firstAnchor,
            end = secondAnchor
        )

        repository.emitDrawings(PairSymbol("USDJPY"), Timeframe("M15"), listOf(elsewhere))

        assertTrue(viewModel.chartState.value.drawings.isEmpty())
        assertFalse(viewModel.drawingHistory.value.canUndo)
    }

    // ---------- Crosshair and viewport ----------

    @Test
    fun `crosshair and viewport events update the snapshot`() = runTest {
        val viewModel = viewModel()
        val crosshair = CrosshairData(price = 1.0825, time = firstAnchor.time, candle = null)
        viewModel.onChartEvent(ChartEvent.CrosshairChanged(crosshair))
        assertEquals(crosshair, viewModel.chartState.value.crosshair)

        viewModel.onChartEvent(ChartEvent.ViewportChanged(ChartViewport(scale = 2.5f, offsetX = -40f)))
        assertEquals(ChartViewport(scale = 2.5f, offsetX = -40f), viewModel.chartState.value.viewport)

        // WHY the reset also clears the crosshair: a double-tap means "start over", and
        // leaving the crosshair on the old bar makes the reset look half-applied.
        viewModel.onChartEvent(ChartEvent.ViewportReset)
        assertEquals(ChartViewport.DEFAULT, viewModel.chartState.value.viewport)
        assertNull(viewModel.chartState.value.crosshair)

        viewModel.onChartEvent(ChartEvent.CrosshairChanged(null))
        assertNull(viewModel.chartState.value.crosshair)
    }

    @Test
    fun `the snapshot always names the chart the user is looking at`() = runTest {
        val viewModel = viewModel()
        assertEquals(pair, viewModel.chartState.value.pair)
        assertEquals(timeframe, viewModel.chartState.value.timeframe)
        assertEquals(viewModel.candles.value.size, viewModel.chartState.value.candles.size)
        assertFalse(viewModel.chartState.value.isLoading)

        viewModel.selectPair(PairSymbol("XAUUSD"))
        // WHY both of these are asserted before the load finishes: the identity and the
        // loading flag are what the screen renders its header and empty state from, so
        // they must move the instant the user taps — not when the candles arrive.
        assertEquals(PairSymbol("XAUUSD"), viewModel.chartState.value.pair)
        assertTrue(viewModel.chartState.value.isLoading)

        awaitSettled("the XAUUSD load") { !viewModel.chartState.value.isLoading }
        assertEquals(listOf(pair, PairSymbol("XAUUSD")), repository.fetchedPairs)
        assertEquals(PairSymbol("XAUUSD"), viewModel.chartState.value.pair)
        assertFalse(viewModel.isLoading.value)
    }
}
