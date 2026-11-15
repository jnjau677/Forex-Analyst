package com.devcode940.ForexAnalyst.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The application database.
 *
 * WHY `exportSchema = true`: it writes `app/schemas/.../N.json` into the repo, and
 * that snapshot is what `MigrationTestHelper` diffs against when a version is bumped —
 * the only reliable way to prove a migration does not destroy user data.
 *
 * WHY explicit migrations and never `fallbackToDestructiveMigration()`: the fallback
 * silently wipes the watchlist, saved signals, alerts and every hand-drawn chart
 * object on upgrade. Data loss that happens only for existing users, only after a
 * release, is the worst kind.
 */
@Database(
    entities = [
        WatchlistItemEntity::class,
        SavedSignalEntity::class,
        CustomAlertEntity::class,
        HistoricalCandleEntity::class,
        ChartDrawingEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class ForexDatabase : RoomDatabase() {

    abstract fun signalDao(): SignalDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun alertDao(): AlertDao
    abstract fun historicalCandleDao(): HistoricalCandleDao
    abstract fun chartDrawingDao(): ChartDrawingDao

    companion object {

        /**
         * v1 → v2: adds the `chart_drawings` table.
         *
         * WHY hand-written SQL that mirrors the entity exactly: Room validates the
         * resulting schema against its generated model when the database opens, and
         * any mismatch (a missing NOT NULL, a wrong affinity, a missing index) throws
         * at runtime rather than degrading quietly. The column list, order, types and
         * nullability below are therefore deliberately literal — this is a contract,
         * not a style choice.
         *
         * WHY `IF NOT EXISTS`: a migration can be re-run after a failed transaction on
         * some devices, and creating an existing table would abort the open.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chart_drawings` (" +
                        "`id` TEXT NOT NULL, " +
                        "`pairSymbol` TEXT NOT NULL, " +
                        "`timeframe` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`style` TEXT NOT NULL, " +
                        "`colorArgb` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`startTime` INTEGER, " +
                        "`startPrice` REAL, " +
                        "`endTime` INTEGER, " +
                        "`endPrice` REAL, " +
                        "`extendLeft` INTEGER NOT NULL, " +
                        "`extendRight` INTEGER NOT NULL, " +
                        "`levelsCsv` TEXT, " +
                        "`side` TEXT, " +
                        "`entryPrice` REAL, " +
                        "`stopPrice` REAL, " +
                        "`targetPrice` REAL, " +
                        "`riskPercent` REAL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chart_drawings_pairSymbol_timeframe_createdAt` " +
                        "ON `chart_drawings` (`pairSymbol`, `timeframe`, `createdAt`)"
                )
            }
        }

        /** Every migration, oldest first — pass this to `addMigrations` on each bump. */
        private val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)

        @Volatile
        private var INSTANCE: ForexDatabase? = null

        fun getInstance(context: Context): ForexDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ForexDatabase::class.java,
                    "forex_analyst.db"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
