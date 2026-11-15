package com.devcode940.ForexAnalyst.data.settings

/**
 * The narrowest possible write access to [AppSettings].
 *
 * WHY this interface exists: the chart terminal must persist display choices (candle
 * style, time zone, overlays, magnet), but it has no business touching API keys,
 * notification flags or the theme. Injecting the whole [SettingsRepository] would give
 * the chart screen — and every test of it — the ability to rewrite credentials.
 *
 * WHY a `fun interface`: the production implementation is
 * [SettingsRepository.update], and a test can supply a one-line lambda that applies
 * the transform to a `MutableStateFlow`. Neither needs a class.
 *
 * WHY a transform and not a setter: a delta cannot clobber a concurrent write to a
 * different field, which a whole-object setter always can.
 */
fun interface ChartSettingsWriter {

    /** Applies [transform] to the current settings and persists the result. */
    fun update(transform: (AppSettings) -> AppSettings)
}
