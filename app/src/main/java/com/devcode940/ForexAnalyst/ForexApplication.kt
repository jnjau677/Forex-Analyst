package com.devcode940.ForexAnalyst

import android.app.Application
import android.content.Context
import android.util.Log
import com.devcode940.ForexAnalyst.data.db.ForexDatabase
import com.devcode940.ForexAnalyst.data.db.WatchlistItemEntity
import com.devcode940.ForexAnalyst.data.model.PairCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

class ForexApplication : Application() {

    lateinit var database: ForexDatabase
        private set

    // WHY: Application-scoped IO work must never block the main thread.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        database = ForexDatabase.getInstance(this)
        seedDefaultWatchlist()
    }

    private fun seedDefaultWatchlist() {
        appScope.launch {
            runCatching {
                // WHY: A "seeded" flag (not isEmpty) — if the user deletes
                // every watchlist item, the old isEmpty check resurrected
                // the defaults on next launch.
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (!prefs.getBoolean(KEY_SEEDED, false)) {
                    val now = Instant.now()
                    // WHY: One batch insert instead of N single inserts.
                    database.watchlistDao().insertAll(
                        PairCatalog.getDefaultWatchlist().map { pair ->
                            WatchlistItemEntity(pair.value, now)
                        }
                    )
                    prefs.edit().putBoolean(KEY_SEEDED, true).apply()
                }
            }.onFailure {
                Log.e(TAG, "Watchlist seeding failed", it)
            }
        }
    }

    companion object {
        private const val TAG = "ForexApplication"
        private const val PREFS_NAME = "forex_app_prefs"
        private const val KEY_SEEDED = "watchlist_seeded"
    }
}
