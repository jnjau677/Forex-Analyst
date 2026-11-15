package com.devcode940.ForexAnalyst.data.alert

import com.devcode940.ForexAnalyst.data.model.AlertConditionType
import com.devcode940.ForexAnalyst.data.model.AlertId
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Covers [AlertEvaluator]'s whole truth table.
 *
 * WHY this suite is exhaustive about boundaries: an alert is a promise that the app is
 * watching on the user's behalf. A comparison that is `>` where it should be `>=` means
 * an alert that never fires at exactly the level the user typed — and the user cannot
 * tell that apart from a broken app.
 */
class AlertEvaluatorTest {

    private val now: Instant = Instant.parse("2024-06-01T12:00:00Z")

    private fun alert(
        condition: AlertConditionType,
        target: Double,
        pair: String = "EURUSD",
        note: String = ""
    ) = CustomAlert(
        id = AlertId.generate(),
        pairSymbol = PairSymbol(pair),
        conditionType = condition,
        targetValue = target,
        isTriggered = false,
        isActive = true,
        note = note,
        createdAt = now.minus(Duration.ofDays(1))
    )

    /**
     * Builds [count] hourly bars ending at [end], each close [step] above the last.
     *
     * WHY built backwards from [end]: the evaluator reads the *newest* bar, so a fixture
     * that drifts away from `now` would silently turn every test into a staleness test.
     */
    private fun candles(
        count: Int,
        firstClose: Double,
        step: Double,
        end: Instant = now,
        intervalMillis: Long = Duration.ofHours(1).toMillis()
    ): List<CandleStick> {
        val out = ArrayList<CandleStick>(count)
        for (i in 0 until count) {
            val close = firstClose + step * i
            val open = if (i == 0) close else firstClose + step * (i - 1)
            out += CandleStick(
                time = end.minusMillis((count - 1 - i) * intervalMillis),
                open = open,
                high = maxOf(open, close) + 0.0001,
                low = minOf(open, close) - 0.0001,
                close = close
            )
        }
        return out
    }

    private fun flat(count: Int, price: Double, end: Instant = now) =
        candles(count = count, firstClose = price, step = 0.0, end = end)

    // ---------- price conditions ----------

    @Test
    fun `price above fires when the last close exceeds the target`() {
        val evaluation = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.PRICE_ABOVE, 1.0800),
            candles = candles(count = 20, firstClose = 1.0700, step = 0.0020),
            now = now
        )
        // Last close = 1.0700 + 0.0020 * 19 = 1.1080.
        assertTrue(evaluation.fired)
        assertEquals(1.1080, evaluation.observedValue!!, 1e-9)
        assertEquals(now, evaluation.observedAt)
    }

    @Test
    fun `price above fires at exactly the target`() {
        // WHY >= and not >: the user typed a level, and a market that touches it has
        // reached it. Requiring a strict crossing makes the alert miss the print the
        // user was actually watching for.
        val evaluation = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.PRICE_ABOVE, 1.1000),
            candles = flat(count = 20, price = 1.1000),
            now = now
        )
        assertTrue(evaluation.fired)
    }

    @Test
    fun `price above does not fire just below the target`() {
        val evaluation = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.PRICE_ABOVE, 1.1000),
            candles = flat(count = 20, price = 1.09999),
            now = now
        )
        assertFalse(evaluation.fired)
    }

    @Test
    fun `price below fires when the last close is at or under the target`() {
        val atTarget = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.PRICE_BELOW, 1.1000),
            candles = flat(count = 20, price = 1.1000),
            now = now
        )
        val underTarget = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.PRICE_BELOW, 1.1000),
            candles = flat(count = 20, price = 1.0500),
            now = now
        )
        val aboveTarget = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.PRICE_BELOW, 1.1000),
            candles = flat(count = 20, price = 1.1500),
            now = now
        )

        assertTrue(atTarget.fired)
        assertTrue(underTarget.fired)
        assertFalse(aboveTarget.fired)
    }

    @Test
    fun `only the newest bar decides`() {
        // WHY: an earlier bar that breached the level is history. Firing on it would send
        // an alert about a price that is no longer current, which is worse than not
        // alerting at all.
        val rising = candles(count = 20, firstClose = 1.0000, step = 0.0100)
        val withAnOldSpike = rising.toMutableList().also {
            it[5] = it[5].let { bar ->
                CandleStick(bar.time, 5.0, 5.1, 4.9, 5.0)
            }
        }

        val evaluation = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.PRICE_ABOVE, 2.0000),
            candles = withAnOldSpike,
            now = now
        )
        assertFalse("an old bar must not fire a current alert", evaluation.fired)
    }

    // ---------- RSI conditions ----------

    @Test
    fun `rsi above fires on a strongly rising series`() {
        // A strictly rising series has no losses, so Wilder's RSI is exactly 100.
        val evaluation = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.RSI_ABOVE, 70.0),
            candles = candles(count = 40, firstClose = 1.0000, step = 0.0010),
            now = now,
            rsiSeries = rsiOf(candles(count = 40, firstClose = 1.0000, step = 0.0010))
        )
        assertTrue(evaluation.fired)
        assertEquals(100.0, evaluation.observedValue!!, 1e-9)
    }

    @Test
    fun `rsi below fires on a strongly falling series`() {
        val series = candles(count = 40, firstClose = 1.2000, step = -0.0010)
        val evaluation = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.RSI_BELOW, 30.0),
            candles = series,
            now = now,
            rsiSeries = rsiOf(series)
        )
        assertTrue(evaluation.fired)
        assertEquals(0.0, evaluation.observedValue!!, 1e-9)
    }

    @Test
    fun `a flat market reads as neutral RSI and fires neither oscillator alert`() {
        // WHY this is pinned: the engine deliberately returns 50 rather than 100 for a
        // dead market. An RSI-ABOVE-30 alert firing on a market that has not moved would
        // be a false signal generated by nothing at all.
        val series = flat(count = 40, price = 1.1000)
        val rsi = rsiOf(series)

        val above = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.RSI_ABOVE, 70.0),
            candles = series,
            now = now,
            rsiSeries = rsi
        )
        val below = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.RSI_BELOW, 30.0),
            candles = series,
            now = now,
            rsiSeries = rsi
        )

        assertEquals(50.0, above.observedValue!!, 1e-9)
        assertFalse(above.fired)
        assertFalse(below.fired)
    }

    @Test
    fun `evaluateAll computes the RSI series for the caller`() {
        // WHY this path is tested separately: evaluate() takes a precomputed series, and
        // evaluateAll() is what the monitor actually calls. A wiring mistake between them
        // would leave every RSI alert permanently un-evaluable.
        val rising = candles(count = 40, firstClose = 1.0000, step = 0.0010)
        val alerts = listOf(
            alert(AlertConditionType.RSI_ABOVE, 70.0),
            alert(AlertConditionType.RSI_BELOW, 30.0),
            alert(AlertConditionType.PRICE_ABOVE, 1.0100)
        )

        val evaluations = AlertEvaluator.evaluateAll(alerts, rising, rsiPeriod = 14, now = now)

        assertEquals(3, evaluations.size)
        assertTrue("RSI above 70 should fire on a series with no losses", evaluations[0].fired)
        assertFalse(evaluations[1].fired)
        assertTrue(evaluations[2].fired)
    }

    @Test
    fun `an RSI alert with too little history does not fire and says so`() {
        // RSI-14 needs period + 1 bars; ten bars produce no value at all.
        val short = candles(count = 10, firstClose = 1.0000, step = 0.0100)
        val evaluation = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.RSI_ABOVE, 10.0),
            candles = short,
            now = now,
            rsiSeries = rsiOf(short)
        )

        assertFalse(evaluation.fired)
        assertNull("no RSI value exists, so there is nothing to report", evaluation.observedValue)
        assertTrue(evaluation.detail.contains("Not enough history"))
    }

    // ---------- missing and stale data ----------

    @Test
    fun `an empty series does not fire and reports no observed value`() {
        val evaluation = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.PRICE_ABOVE, 0.0001),
            candles = emptyList(),
            now = now
        )

        assertFalse(evaluation.fired)
        assertNull(evaluation.observedValue)
        assertNull(evaluation.observedAt)
    }

    @Test
    fun `a series whose newest bar is stale does not fire`() {
        // WHY: a free-tier feed can return a series ending days ago (a weekend, a
        // suspended key, a provider cache). Firing on that sends "EURUSD is above
        // 1.09000" for Thursday's price, and the user acts on it.
        val stale = flat(count = 20, price = 1.2000, end = now.minus(Duration.ofDays(4)))

        val evaluation = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.PRICE_ABOVE, 1.1000),
            candles = stale,
            now = now
        )

        assertFalse(evaluation.fired)
        assertEquals(1.2000, evaluation.observedValue!!, 1e-9)
        assertTrue("the reason must be visible: ${evaluation.detail}", evaluation.detail.contains("too old"))
    }

    @Test
    fun `a fresh-enough series still fires at the staleness boundary`() {
        val justFresh = flat(count = 20, price = 1.2000, end = now.minus(Duration.ofHours(71)))

        assertTrue(
            AlertEvaluator.evaluate(
                alert = alert(AlertConditionType.PRICE_ABOVE, 1.1000),
                candles = justFresh,
                now = now
            ).fired
        )
    }

    // ---------- wording ----------

    @Test
    fun `the headline names the pair, the direction and the target`() {
        // WHY the pair is in the title: alerts from several pairs land in the same
        // notification shade, and three entries reading "Price alert" are unreadable.
        val evaluation = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.PRICE_ABOVE, 1.0900, pair = "EURUSD"),
            candles = flat(count = 20, price = 1.1000),
            now = now
        )

        assertEquals("EURUSD is above 1.09000", evaluation.headline)
    }

    @Test
    fun `the target is quoted with the instrument's own precision`() {
        // WHY: USDJPY quotes three decimals and XAUUSD two. Padding both to five, as the
        // app once did everywhere, reads as ignorance of the instrument.
        val jpy = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.PRICE_ABOVE, 151.5, pair = "USDJPY"),
            candles = flat(count = 20, price = 152.0, end = now),
            now = now
        )
        val gold = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.PRICE_ABOVE, 2350.0, pair = "XAUUSD"),
            candles = flat(count = 20, price = 2400.0, end = now),
            now = now
        )

        assertTrue("JPY headline should read 151.500: ${jpy.headline}", jpy.headline.contains("151.500"))
        assertTrue("gold headline should read 2350.00: ${gold.headline}", gold.headline.contains("2350.00"))
    }

    @Test
    fun `a note the user wrote is carried into the detail`() {
        val evaluation = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.PRICE_ABOVE, 1.0900, note = "Take profit here"),
            candles = flat(count = 20, price = 1.1000),
            now = now
        )

        assertTrue(evaluation.detail.contains("Take profit here"))
    }

    @Test
    fun `an alert that has not fired never claims a level in its headline`() {
        // WHY: one evaluation list is produced per poll and most of it has not fired. If
        // the same sentence described both, the day that string reached a notification
        // by accident it would be a false trading signal.
        val evaluation = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.PRICE_ABOVE, 1.5000),
            candles = flat(count = 20, price = 1.1000),
            now = now
        )

        assertFalse(evaluation.fired)
        assertEquals("EURUSD price alert", evaluation.headline)
        assertFalse(evaluation.headline.contains("1.50000"))
        assertTrue(evaluation.detail.contains("Not yet triggered"))
    }

    @Test
    fun `an RSI headline quotes one decimal`() {
        val series = candles(count = 40, firstClose = 1.0000, step = 0.0010)
        val evaluation = AlertEvaluator.evaluate(
            alert = alert(AlertConditionType.RSI_ABOVE, 70.0),
            candles = series,
            now = now,
            rsiSeries = rsiOf(series)
        )

        assertEquals("EURUSD RSI is above 70.0", evaluation.headline)
    }

    // ---------- monitor contract ----------

    @Test
    fun `the monitor evaluates on H1 and asks for enough bars to settle an RSI`() {
        assertEquals("H1", AlertEvaluator.EVALUATION_TIMEFRAME.value)
        // RSI-14 needs 15 bars to produce one value; the app allows periods up to 100,
        // so 200 bars leaves headroom without a large payload on a metered feed.
        assertTrue(AlertEvaluator.EVALUATION_CANDLE_COUNT >= 200)
    }

    private fun rsiOf(candles: List<CandleStick>): List<Double?> =
        com.devcode940.ForexAnalyst.data.repository.TechnicalAnalysisEngine.calculateRSI(
            closes = candles.map { it.close },
            period = 14
        )
}
