package com.devcode940.ForexAnalyst.data.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Locale-independent formatting for prices, pips, percentages and volumes.
 *
 * WHY a single formatter: `"%.5f".format(price)` was copy-pasted across four
 * screens with two problems —
 *  1. it hardcodes **five** decimals, which is wrong for JPY pairs (3), gold (2),
 *     indices (2) and crypto (2–5), so a USD/JPY price rendered as `151.50000`
 *     and a BTC price as `67500.00000`;
 *  2. `String.format(pattern, ...)` without a `Locale` uses the **device**
 *     locale, so the same price renders `1.08500` in Nairobi and `1,08500` in
 *     Berlin — which also breaks any string built from it (fingerprints, CSV
 *     exports, cache keys).
 *
 * Everything here pins [Locale.US] and takes the digit count from the
 * [Instrument], so one rule applies everywhere.
 */
object PriceFormatter {

    /**
     * Formats [price] with [digits] decimals using a dot separator.
     *
     * WHY `coerceIn(0, 8)`: the digit count reaches a `String.format` pattern;
     * a negative or absurd value would throw or produce a useless string, and a
     * formatter must never crash a chart frame.
     */
    fun format(price: Double, digits: Int): String {
        if (!price.isFinite()) return NOT_A_NUMBER
        val safeDigits = digits.coerceIn(0, 8)
        return String.format(Locale.US, "%.${safeDigits}f", price)
    }

    /** Formats [price] using the instrument's own precision. */
    fun format(price: Double, instrument: Instrument?): String =
        format(price, instrument?.quoteDigits ?: DEFAULT_DIGITS)

    /** Formats [price] for [symbol] using the catalog's precision. */
    fun format(price: Double, symbol: PairSymbol): String =
        format(price, InstrumentCatalog.quoteDigitsFor(symbol))

    /**
     * Signed price change, e.g. `+0.00420` / `-12.30`.
     *
     * WHY: a bare number loses the direction colour-coding depends on, and
     * `+0.00000` reads as "no change" rather than "flat".
     */
    fun formatSigned(value: Double, digits: Int): String {
        val text = format(value, digits)
        return if (value > 0.0) "+$text" else text
    }

    /** Price delta expressed in pips for [instrument], e.g. `+42.0 pips`. */
    fun formatPips(delta: Double, instrument: Instrument?): String {
        if (!delta.isFinite() || instrument == null) return NOT_A_NUMBER
        val pips = instrument.toPips(delta)
        return String.format(Locale.US, "%+.1f pips", pips)
    }

    /**
     * Percentage change, e.g. `+0.38 %`.
     *
     * WHY: percentage is the only comparable unit across instruments with
     * different quote scales — 100 points means nothing on EUR/USD and little on
     * BTC/USD.
     */
    fun formatPercent(from: Double, to: Double): String {
        if (!from.isFinite() || !to.isFinite() || from == 0.0) return NOT_A_NUMBER
        val percent = (to - from) / from * 100.0
        return String.format(Locale.US, "%+.2f %%", percent)
    }

    /** Compact volume/turnover, e.g. `12.4K`, `3.1M`. */
    fun formatCompact(value: Double): String {
        if (!value.isFinite()) return NOT_A_NUMBER
        val magnitude = abs(value)
        return when {
            magnitude >= 1_000_000_000 -> String.format(Locale.US, "%.2fB", value / 1_000_000_000)
            magnitude >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000)
            magnitude >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000)
            else -> String.format(Locale.US, "%.0f", value)
        }
    }

    /**
     * Rounds [value] to [digits] decimals.
     *
     * WHY: axis labels are generated from a "nice step" and then accumulated,
     * which reintroduces binary floating-point dust (`0.30000000000000004`).
     * Rounding before formatting keeps labels stable.
     */
    fun round(value: Double, digits: Int): Double {
        if (!value.isFinite()) return value
        val factor = Math.pow(10.0, digits.coerceIn(0, 8).toDouble())
        return (value * factor).roundToLong() / factor
    }

    /** Placeholder shown instead of a number that cannot be represented. */
    const val NOT_A_NUMBER = "—"

    /** Digits used when the instrument is unknown (FX convention). */
    const val DEFAULT_DIGITS = 5
}
