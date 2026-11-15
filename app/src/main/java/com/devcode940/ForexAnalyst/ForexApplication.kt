package com.devcode940.ForexAnalyst

import android.app.Application
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
                val dao = database.watchlistDao()
                if (dao.getAllSymbols().isEmpty()) {
                    val now = Instant.now()
                    PairCatalog.getDefaultWatchlist().forEach { pair ->
                        dao.insertItem(WatchlistItemEntity(pair.value, now))
                    }
                }
            }.onFailure {
                Log.e("ForexApplication", "Watchlist seeding failed", it)
            }
        }
    }
}