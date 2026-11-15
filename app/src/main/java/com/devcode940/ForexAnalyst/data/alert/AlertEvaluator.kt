package com.devcode940.ForexAnalyst.data.alert

import com.devcode940.ForexAnalyst.data.model.AlertConditionType
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.PriceFormatter
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.repository.TechnicalAnalysisEngine
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One alert's verdict against one candle series.
 *
 * WHY the strings are built here rather than in the notifier: they depend on the
 * instrument's quote precision and on which condition type fired, i.e. on domain
 * facts. Building them in a pure JVM object makes them unit-testable, and keeps the
 * Android notification code down to "put these two strings in a builder".
 *
 * @property fired true when the condition is satisfied by the newest bar.
 * @property observedValue the value the condition was tested against — the last
 *   close for a price alert, the last RSI for an oscillator alert. Null when the
 *   series is too short to produce one, in which case [fired] is false.
 * @property observedAt timestamp of the bar that produced [observedValue].
 * @property headline notification title, e.g. `EURUSD is above 1.09000`.
 * @property detail notification body: the observed value, the bar time and the
 *   alert's own note if the user wrote one.
 */
data class AlertEvaluation(
    val alert: CustomAlert,
    val fired: Boolean,
    val observedValue: Double?,
    val observedAt: Instant?,
    val headline: String,
    val detail: String
)

/**
 * Decides whether saved alerts have been hit.
 *
 * WHY this class exists: alerts were persisted, listed, edited and deleted — and
 * never evaluated. Nothing ever fired. An alert feature that cannot fire is worse
 * than no alert feature, because the user has been told to trust it.
 *
 * WHY pure and stateless: every input is a parameter, so the whole truth table
 * (four condition types x above/below/missing-data) is covered by JVM tests with no
 * database, no Android and no clock. The notifier and the polling loop around it are
 * then thin enough to trust by inspection.
 */
object AlertEvaluator {

    /**
     * The timeframe every alert is evaluated on.
     *
     * WHY fixed rather than per-alert: `CustomAlert` has no timeframe column, and
     * adding one means a Room schema migration. This sandbox has no JDK, Gradle or
     * Android SDK, so a migration written here cannot be exercised by
     * `MigrationTestHelper` — and an untested migration is the single most likely way
     * to destroy an existing user's data on upgrade. Evaluating on H1 is a
     * conventional, documented choice that ships safely today; the migration is
     * spelled out in the README's known limitations.
     *
     * WHY H1 specifically: it is the app's default chart timeframe, it is coarse
     * enough that a free-tier feed's hourly cache still makes alerts timely, and it
     * is fine enough that a daily-bar alert would not fire hours late.
     */
    val EVALUATION_TIMEFRAME: Timeframe = Timeframe("H1")

    /**
     * Bars requested per evaluation.
     *
     * WHY 200: RSI-14 needs `period + 1` bars to produce a single value and ~50 more
     * to settle, and 200 keeps the payload small on a metered feed while leaving
     * headroom for a longer oscillator period configured in Settings (up to 100).
     */
    const val EVALUATION_CANDLE_COUNT = 200

    /** Maximum age of the newest bar for an evaluation to be allowed to fire. */
    private val MAX_BAR_AGE: Duration = Duration.ofHours(72)

    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM, HH:mm 'UTC'", Locale.US).withZone(ZoneOffset.UTC)

    /**
     * Evaluates every alert against one shared series.
     *
     * @param candles oldest-first; the newest bar decides.
     * @param rsiPeriod the user's configured RSI period.
     */
    fun evaluateAll(
        alerts: List<CustomAlert>,
        candles: List<CandleStick>,
        rsiPeriod: Int,
        now: Instant
    ): List<AlertEvaluation> {
        if (alerts.isEmpty()) return emptyList()
        // WHY compute RSI once for the whole list: an alert screen can hold several
        // RSI conditions for the same pair, and recomputing a 200-bar oscillator per
        // alert is pure waste on the polling path.
        val rsiSeries = if (alerts.any { it.conditionType.isRsi }) {
            TechnicalAnalysisEngine.calculateRSI(candles.map { it.close }, rsiPeriod)
        } else {
            emptyList()
        }
        return alerts.map { alert -> evaluate(alert, candles, now, rsiSeries) }
    }

    /** Evaluates a single alert against an already-computed RSI series. */
    fun evaluate(
        alert: CustomAlert,
        candles: List<CandleStick>,
        now: Instant,
        rsiSeries: List<Double?> = emptyList()
    ): AlertEvaluation {
        val latest = candles.lastOrNull()
        val observed = observedValueFor(alert.conditionType, candles, rsiSeries)
        val pair = alert.pairSymbol

        if (latest == null || observed == null) {
            return AlertEvaluation(
                alert = alert,
                fired = false,
                observedValue = null,
                observedAt = latest?.time,
                headline = neutralHeadline(pair, alert.conditionType),
                detail = "Not enough history yet to evaluate this alert."
            )
        }

        // WHY a staleness ceiling: a free-tier feed can return a series whose newest
        // bar is days old (a weekend, a suspended key, a provider cache). Firing on
        // that would send "EURUSD is above 1.09000" for a price from Thursday, and
        // the user acts on it. Not firing, and saying why, is the honest answer.
        if (isTooOld(latest.time, now)) {
            return AlertEvaluation(
                alert = alert,
                fired = false,
                observedValue = observed,
                observedAt = latest.time,
                headline = neutralHeadline(pair, alert.conditionType),
                detail = "The newest ${EVALUATION_TIMEFRAME.value} bar is from " +
                    "${TIME_FORMAT.format(latest.time)}, which is too old to alert on."
            )
        }

        val fired = when (alert.conditionType) {
            AlertConditionType.PRICE_ABOVE -> observed >= alert.targetValue
            AlertConditionType.PRICE_BELOW -> observed <= alert.targetValue
            AlertConditionType.RSI_ABOVE -> observed >= alert.targetValue
            AlertConditionType.RSI_BELOW -> observed <= alert.targetValue
        }

        return AlertEvaluation(
            alert = alert,
            fired = fired,
            observedValue = observed,
            observedAt = latest.time,
            headline = if (fired) triggeredHeadline(alert, pair) else neutralHeadline(pair, alert.conditionType),
            detail = detailFor(alert, pair, observed, latest.time, fired)
        )
    }

    private fun observedValueFor(
        condition: AlertConditionType,
        candles: List<CandleStick>,
        rsiSeries: List<Double?>
    ): Double? = when {
        condition.isRsi -> rsiSeries.lastOrNull { it != null }
        else -> candles.lastOrNull()?.close?.takeIf { it.isFinite() }
    }

    private fun isTooOld(barTime: Instant, now: Instant): Boolean =
        Duration.between(barTime, now) > MAX_BAR_AGE

    /**
     * Title shown only when the condition is satisfied.
     *
     * WHY the fired/neutral split exists: one [AlertEvaluation] list is produced per
     * poll and most of it has not fired. Rendering the same sentence for both would
     * put "EURUSD is above 1.09000" in a debug surface for an alert that is still
     * waiting — and the day that string reaches a notification by accident, it is a
     * false trading signal.
     */
    private fun triggeredHeadline(alert: CustomAlert, pair: PairSymbol): String {
        val direction = when (alert.conditionType) {
            AlertConditionType.PRICE_ABOVE -> "is above"
            AlertConditionType.PRICE_BELOW -> "is below"
            AlertConditionType.RSI_ABOVE -> "RSI is above"
            AlertConditionType.RSI_BELOW -> "RSI is below"
        }
        val target = when {
            alert.conditionType.isRsi -> formatLevel(alert.targetValue)
            else -> PriceFormatter.format(alert.targetValue, pair)
        }
        // WHY include the pair in the title: alerts from several pairs arrive in the
        // same notification shade, and "Price alert" x3 is unreadable.
        return "$pair.value $direction $target"
    }

    private fun detailFor(
        alert: CustomAlert,
        pair: PairSymbol,
        observed: Double,
        at: Instant,
        fired: Boolean
    ): String {
        val observedText = when {
            alert.conditionType.isRsi -> "RSI ${formatLevel(observed)}"
            else -> PriceFormatter.format(observed, pair)
        }
        val state = if (fired) "Triggered" else "Not yet triggered"
        val note = alert.note.trim()
        val base = "$state at $observedText on ${TIME_FORMAT.format(at)}."
        return if (note.isEmpty()) base else "$base Note: $note"
    }

    /** Title used when the alert has not fired; never asserts a price level. */
    private fun neutralHeadline(pair: PairSymbol, condition: AlertConditionType): String =
        if (condition.isRsi) "$pair.value RSI alert" else "$pair.value price alert"

    private fun formatLevel(value: Double): String =
        // WHY one decimal and Locale.US: RSI is quoted to a tenth everywhere in this
        // app's UI, and a locale whose decimal separator is a comma would render
        // "63,4" inside an otherwise English sentence.
        if (value.isFinite()) String.format(Locale.US, RSI_FORMAT, value) else PriceFormatter.NOT_A_NUMBER

    private const val RSI_FORMAT = "%.1f"

    private val AlertConditionType.isRsi: Boolean
        get() = this == AlertConditionType.RSI_ABOVE || this == AlertConditionType.RSI_BELOW
}
