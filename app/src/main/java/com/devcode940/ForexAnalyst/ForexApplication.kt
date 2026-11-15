package com.devcode940.ForexAnalyst

import android.app.Application
import android.content.Context
import android.util.Log
import com.devcode940.ForexAnalyst.data.alert.AlertMonitor
import com.devcode940.ForexAnalyst.data.alert.AlertNotifier
import com.devcode940.ForexAnalyst.data.db.ForexDatabase
import com.devcode940.ForexAnalyst.data.db.WatchlistItemEntity
import com.devcode940.ForexAnalyst.data.model.PairCatalog
import com.devcode940.ForexAnalyst.data.provider.MarketDataProviderRegistry
import com.devcode940.ForexAnalyst.data.repository.ForexRepository
import com.devcode940.ForexAnalyst.data.repository.SimulatedCandleSource
import com.devcode940.ForexAnalyst.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Application subclass and composition root.
 *
 * WHY the object graph is built here rather than in the ViewModel factory: the alert
 * monitor and the chart share one repository, one quota budget and one set of
 * credentials. A ViewModel-scoped graph would give each screen its own budget counter,
 * and two counters against one 500-credit monthly allowance is how a key gets
 * exhausted twice as fast as the user can see.
 *
 * Every collaborator is exposed as a `lateinit var` with a private setter so screens
 * and ViewModels can reach the *same* instances, and so nothing outside this class can
 * swap them mid-process.
 */
class ForexApplication : Application() {

    lateinit var database: ForexDatabase
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    /**
     * Live market-data stack: HTTP clients, quota budget and provider routing.
     *
     * Exposed so Settings can render remaining quota without knowing how it is
     * counted.
     */
    lateinit var marketData: MarketDataProviderRegistry
        private set

    /** The single repository instance for the whole process. */
    lateinit var repository: ForexRepository
        private set

    lateinit var alertNotifier: AlertNotifier
        private set

    /**
     * Polls saved alerts against live prices and posts notifications.
     *
     * Exposed so the Alerts screen can show its status (last run, whether it is waiting
     * for a live feed, whether notifications are blocked) — without which "my alert
     * never fired" is unanswerable.
     */
    lateinit var alertMonitor: AlertMonitor
        private set

    /** Foreground detector the alert monitor uses to avoid spending quota in a pocket. */
    val foregroundTracker = ForegroundTracker()

    // WHY: Application-scoped IO work must never block the main thread, and it must
    // outlive every screen - the alert monitor in particular runs for the whole
    // process lifetime.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        database = ForexDatabase.getInstance(this)
        settingsRepository = SettingsRepository.getInstance(this)

        marketData = MarketDataProviderRegistry(
            context = this,
            settings = settingsRepository.settings,
            // WHY the simulator is passed in explicitly: it is the fallback the live
            // source degrades to, and naming it here keeps that decision visible in the
            // composition root instead of hidden inside the provider package.
            fallback = SimulatedCandleSource(),
            logRequests = BuildConfig.DEBUG
        )

        repository = ForexRepository(
            database = database,
            candleSource = marketData.candleSource
        )

        alertNotifier = AlertNotifier(this)
        alertMonitor = AlertMonitor(
            repository = repository,
            settings = settingsRepository.settings,
            notifier = alertNotifier,
            scope = appScope,
            isForeground = { foregroundTracker.isForeground }
        )

        registerActivityLifecycleCallbacks(foregroundTracker)
        seedDefaultWatchlist()
        alertMonitor.start()
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

        /**
         * The process-wide graph, or null when the host application is not this class.
         *
         * WHY nullable rather than a cast: instrumented tests and previews can run with
         * a plain `Application`, and `(application as ForexApplication)` would crash
         * them with a ClassCastException that has nothing to do with what is being
         * tested. Callers fall back to constructing their own repository.
         */
        fun graphOf(context: Context): ForexApplication? =
            context.applicationContext as? ForexApplication
    }
}
