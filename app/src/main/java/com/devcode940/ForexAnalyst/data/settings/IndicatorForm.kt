package com.devcode940.ForexAnalyst.data.settings

import com.devcode940.ForexAnalyst.data.model.IndicatorSettings

/**
 * Raw, unparsed indicator form input exactly as typed by the user.
 *
 * WHY: Strings (not Ints) — the old UI did `text.toIntOrNull() ?: current`,
 * which silently discarded a typo and saved the previous value, so the user
 * got "Saved" while their edit vanished. Validation must see the raw text to
 * explain what is wrong.
 */
data class IndicatorFormInput(
    val rsiPeriod: String,
    val emaFast: String,
    val emaMedium: String,
    val emaSlow: String,
    val macdFast: String,
    val macdSlow: String,
    val macdSignal: String,
    val atrPeriod: String,
    val showEma: Boolean,
    val showRsi: Boolean
)

/**
 * Parsed and validated indicator values.
 *
 * WHY: A separate type from [AppSettings] — this is the *form* projection,
 * and it can only exist after validation succeeded (parse, don't validate).
 */
data class IndicatorValues(
    val rsiPeriod: Int,
    val emaFast: Int,
    val emaMedium: Int,
    val emaSlow: Int,
    val macdFast: Int,
    val macdSlow: Int,
    val macdSignal: Int,
    val atrPeriod: Int,
    val showEma: Boolean,
    val showRsi: Boolean
) {
    /**
     * Builds the domain [IndicatorSettings].
     *
     * WHY: Safe to call without try/catch — [IndicatorFormValidator] already
     * enforced every invariant [IndicatorSettings] requires (ranges,
     * ascending EMA periods, MACD fast < slow).
     */
    fun toSettings(): IndicatorSettings = IndicatorSettings(
        emaPeriods = listOf(emaFast, emaMedium, emaSlow),
        rsiPeriod = rsiPeriod,
        macdFastPeriod = macdFast,
        macdSlowPeriod = macdSlow,
        macdSignalPeriod = macdSignal,
        atrPeriod = atrPeriod
    )
}

/**
 * Outcome of validating an [IndicatorFormInput].
 *
 * WHY: Sealed interface (not Boolean + out-params, not exceptions) — the
 * caller must handle both branches, and [Invalid] carries a message that is
 * safe and useful to show a human.
 */
sealed interface IndicatorFormResult {
    /** All fields parsed and satisfy every domain invariant. */
    data class Valid(val values: IndicatorValues) : IndicatorFormResult

    /** At least one field is wrong; [userMessage] says which and why. */
    data class Invalid(val field: String, val userMessage: String) : IndicatorFormResult
}

/**
 * Pure validator for the indicator settings form. No Android dependencies.
 *
 * WHY: Extracted from the ViewModel so the rules are unit-testable on the JVM
 * and reusable by any future surface (dialog, sync, import).
 */
object IndicatorFormValidator {

    private val RSI_RANGE: IntRange = 2..100
    private val EMA_RANGE: IntRange = 2..400
    private val MACD_FAST_RANGE: IntRange = 2..100
    private val MACD_SLOW_RANGE: IntRange = 3..200
    private val MACD_SIGNAL_RANGE: IntRange = 2..100
    private val ATR_RANGE: IntRange = 2..100

    /** Validates [input] and returns either parsed values or one actionable error. */
    fun validate(input: IndicatorFormInput): IndicatorFormResult {
        val rsi = parse(input.rsiPeriod, RSI_RANGE) ?: return invalid(input.rsiPeriod, "RSI period", RSI_RANGE)
        val fast = parse(input.emaFast, EMA_RANGE) ?: return invalid(input.emaFast, "EMA fast period", EMA_RANGE)
        val medium = parse(input.emaMedium, EMA_RANGE) ?: return invalid(input.emaMedium, "EMA medium period", EMA_RANGE)
        val slow = parse(input.emaSlow, EMA_RANGE) ?: return invalid(input.emaSlow, "EMA slow period", EMA_RANGE)
        val macdFast = parse(input.macdFast, MACD_FAST_RANGE) ?: return invalid(input.macdFast, "MACD fast period", MACD_FAST_RANGE)
        val macdSlow = parse(input.macdSlow, MACD_SLOW_RANGE) ?: return invalid(input.macdSlow, "MACD slow period", MACD_SLOW_RANGE)
        val macdSignal = parse(input.macdSignal, MACD_SIGNAL_RANGE) ?: return invalid(input.macdSignal, "MACD signal period", MACD_SIGNAL_RANGE)
        val atr = parse(input.atrPeriod, ATR_RANGE) ?: return invalid(input.atrPeriod, "ATR period", ATR_RANGE)

        // WHY: Cross-field invariants — a valid-looking set of numbers can
        // still be nonsense (fast EMA slower than the medium one inverts the
        // whole crossover strategy).
        if (fast >= medium || medium >= slow) {
            return IndicatorFormResult.Invalid(
                field = "EMA periods",
                userMessage = "EMA periods must increase: fast ($fast) < medium ($medium) < slow ($slow)."
            )
        }
        if (macdFast >= macdSlow) {
            return IndicatorFormResult.Invalid(
                field = "MACD periods",
                userMessage = "MACD fast period ($macdFast) must be smaller than the slow period ($macdSlow)."
            )
        }

        return IndicatorFormResult.Valid(
            IndicatorValues(
                rsiPeriod = rsi,
                emaFast = fast,
                emaMedium = medium,
                emaSlow = slow,
                macdFast = macdFast,
                macdSlow = macdSlow,
                macdSignal = macdSignal,
                atrPeriod = atr,
                showEma = input.showEma,
                showRsi = input.showRsi
            )
        )
    }

    private fun parse(raw: String, range: IntRange): Int? {
        // WHY: trim() first — a trailing space from a soft keyboard is not a
        // user error worth rejecting.
        val value = raw.trim().toIntOrNull() ?: return null
        return value.takeIf { it in range }
    }

    private fun invalid(raw: String, label: String, range: IntRange): IndicatorFormResult.Invalid {
        // WHY: Tell the user which field, what they typed, and the accepted
        // range — "Invalid input" alone is not actionable.
        val typed = raw.trim().ifBlank { "(empty)" }
        return IndicatorFormResult.Invalid(
            field = label,
            userMessage = "$label must be a whole number from ${range.first} to ${range.last}. You entered $typed."
        )
    }
}
