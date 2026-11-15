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
    exportSchema = false
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
                    // WHY: Dev-stage schema changes should not crash the app on upgrade.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}