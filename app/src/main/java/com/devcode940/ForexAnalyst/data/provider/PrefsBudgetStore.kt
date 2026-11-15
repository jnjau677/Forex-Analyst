package com.devcode940.ForexAnalyst.data.provider

import android.content.Context
import android.content.SharedPreferences
import com.devcode940.ForexAnalyst.data.model.MarketDataProvider
import java.time.Instant

/**
 * Remembers market-data quota usage across process restarts.
 *
 * WHY persisted: FCS's free allowance is 500 credits *per month*. A counter that
 * resets when the process dies is not a quota — and restarting is exactly what a
 * user does when prices stop updating, so an in-memory guard would be defeated by
 * the one behaviour it most needs to survive.
 *
 * WHY plain `SharedPreferences` and not the encrypted store: this holds request
 * timestamps, not credentials. Putting it in the encrypted file would mean a
 * MasterKey decryption on the hot path of every quota check, and would mix
 * non-secret bookkeeping in with the API keys it is meant to protect.
 *
 * Format: one string per provider, comma-separated epoch milliseconds.
 * WHY not JSON: a list of longs has no ambiguity to parse, no library to depend on,
 * and corrupts into "empty list" rather than into an exception.
 */
class PrefsBudgetStore(context: Context) : RequestBudget.BudgetStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(provider: MarketDataProvider): List<Instant> =
        prefs.getString(keyFor(provider), null)
            ?.split(SEPARATOR)
            ?.mapNotNull { token -> token.trim().toLongOrNull() }
            // WHY filter out the far future: a device whose clock was wrong (or was
            // rolled forward, then corrected) can leave timestamps ahead of now.
            // Those would never age out of any sliding window and would permanently
            // deny the feed.
            ?.filter { it <= System.currentTimeMillis() + MAX_CLOCK_SKEW_MILLIS }
            ?.map { Instant.ofEpochMilli(it) }
            .orEmpty()

    override fun save(provider: MarketDataProvider, usage: List<Instant>) {
        val encoded = usage.joinToString(SEPARATOR.toString()) { it.toEpochMilli().toString() }
        prefs.edit().putString(keyFor(provider), encoded).apply()
    }

    /** Drops all recorded usage. Used by the "reset data" action in Settings. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun keyFor(provider: MarketDataProvider): String = "$KEY_PREFIX${provider.name}"

    private companion object {
        const val PREFS_NAME = "market_data_quota"
        const val KEY_PREFIX = "usage_"
        const val SEPARATOR = ','

        /** One hour of tolerated clock skew before a timestamp is treated as bogus. */
        const val MAX_CLOCK_SKEW_MILLIS = 60L * 60L * 1000L
    }
}
