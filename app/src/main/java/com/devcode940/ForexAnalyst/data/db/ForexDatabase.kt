package com.devcode940.ForexAnalyst.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        WatchlistItemEntity::class,
        SavedSignalEntity::class,
        CustomAlertEntity::class,
        HistoricalCandleEntity::class
    ],
    version = 1,
    // WHY: exportSchema = true writes schemas/ForexDatabase/1.json into the
    // repo. That snapshot is what MigrationTestHelper diffs against when you
    // bump the version — the only reliable way to prove a migration does not
    // destroy user data.
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class ForexDatabase : RoomDatabase() {

    abstract fun signalDao(): SignalDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun alertDao(): AlertDao
    abstract fun historicalCandleDao(): HistoricalCandleDao

    companion object {
        @Volatile
        private var INSTANCE: ForexDatabase? = null

        fun getInstance(context: Context): ForexDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ForexDatabase::class.java,
                    "forex_analyst.db"
                )
                    // WHY: No fallbackToDestructiveMigration() in production —
                    // silently wiping the user's watchlist/signals/alerts on
                    // upgrade is data loss. Add explicit Migration objects
                    // before bumping [version] instead.
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
