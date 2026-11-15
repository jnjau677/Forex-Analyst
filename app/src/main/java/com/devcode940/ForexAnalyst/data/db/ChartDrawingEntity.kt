package com.devcode940.ForexAnalyst.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Persisted chart drawing (single-table mapping of the [com.devcode940.ForexAnalyst.data.model.ChartDrawing]
 * sealed hierarchy).
 *
 * WHY one table with nullable columns rather than one table per subtype: eight
 * subtypes would mean eight DAOs, eight mappers and a UNION query to list a chart's
 * objects in creation order. A `kind` discriminator plus per-tool columns keeps
 * "everything on this chart, oldest first" a single indexed query, which is what the
 * renderer actually needs every time the chart is opened.
 *
 * WHY the columns are tool-specific and not a JSON blob: a blob cannot be indexed,
 * cannot be queried ("delete every position on EURUSD H1"), and cannot be validated
 * by Room's schema check. It would also silently survive a field rename, storing
 * data no version of the app can read.
 *
 * WHY timestamps other than [createdAt] are `Long?` and not `Instant?`: the project's
 * [Converters] are deliberately non-null (every existing timestamp column is NOT
 * NULL), and adding a nullable converter would let a null timestamp slip into the
 * domain as a zero epoch instead of being rejected by the mapper.
 */
@Entity(
    tableName = "chart_drawings",
    indices = [Index(value = ["pairSymbol", "timeframe", "createdAt"])]
)
data class ChartDrawingEntity(
    @PrimaryKey val id: String,
    val pairSymbol: String,
    val timeframe: String,

    /** Discriminator: the simple name of the [com.devcode940.ForexAnalyst.data.model.ChartDrawing] subtype. */
    val kind: String,

    /** [com.devcode940.ForexAnalyst.data.model.DrawingStyle] name. */
    val style: String,

    /** ARGB colour, so the palette stays a plain integer in SQLite. */
    val colorArgb: Int,

    val createdAt: Instant,
    val label: String,

    // ---- Two-anchor geometry (trend, fib, rectangle, measurement) ----
    val startTime: Long?,
    val startPrice: Double?,
    val endTime: Long?,
    val endPrice: Double?,

    // ---- Trendline projection flags ----
    val extendLeft: Boolean,
    val extendRight: Boolean,

    /** Fibonacci levels as a comma-separated ascending list; null when not a fib. */
    val levelsCsv: String?,

    // ---- Position planner ----
    /** [com.devcode940.ForexAnalyst.data.model.TradeSide] name; null when not a position. */
    val side: String?,
    val entryPrice: Double?,
    val stopPrice: Double?,
    val targetPrice: Double?,
    val riskPercent: Double?
)

/**
 * Drawing persistence.
 *
 * WHY every read is a bounded [Flow]: an unbounded table would re-emit the whole
 * history on each insert, and the UI would diff hundreds of objects for a change to
 * one. The limit mirrors [com.devcode940.ForexAnalyst.data.chart.DrawingStore.MAX_DRAWINGS].
 */
@Dao
interface ChartDrawingDao {

    /**
     * All drawings for one chart, oldest first.
     *
     * WHY ascending: the renderer paints in list order, so the newest object is on
     * top — and the hit-tester picks the last match, which must be that same object.
     */
    @Query(
        "SELECT * FROM chart_drawings WHERE pairSymbol = :pairSymbol AND timeframe = :timeframe " +
            // WHY `rowid` as the tiebreak: drawings placed inside the same millisecond
            // share a createdAt, and SQLite does not guarantee a stable order for ties.
            // The renderer paints in list order (so the newest object is on top) and the
            // hit-tester picks the last match — an arbitrary tie order would make two
            // quickly placed objects swap z-order between app starts, so a tap that
            // selected the trend line yesterday selects the rectangle today.
            "ORDER BY createdAt ASC, rowid ASC LIMIT :limit"
    )
    fun observeDrawings(pairSymbol: String, timeframe: String, limit: Int = MAX_ROWS): Flow<List<ChartDrawingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDrawing(drawing: ChartDrawingEntity)

    /**
     * WHY batch insert: restoring an undo snapshot or importing a set of objects is
     * N rows; one transaction instead of N round-trips keeps the UI responsive.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDrawings(drawings: List<ChartDrawingEntity>)

    @Query("DELETE FROM chart_drawings WHERE id = :id")
    suspend fun deleteDrawing(id: String)

    @Query("DELETE FROM chart_drawings WHERE pairSymbol = :pairSymbol AND timeframe = :timeframe")
    suspend fun deleteDrawingsForChart(pairSymbol: String, timeframe: String)

    /**
     * Atomically replaces every drawing on one chart.
     *
     * WHY one transaction: undo and redo both have to *remove* objects, which a
     * REPLACE insert cannot express. Doing the delete and the insert as two separate
     * calls would leave a window where the chart has no objects at all — visible as a
     * flicker, and a real loss if the process died in between.
     */
    @Transaction
    suspend fun replaceDrawingsForChart(
        pairSymbol: String,
        timeframe: String,
        drawings: List<ChartDrawingEntity>
    ) {
        deleteDrawingsForChart(pairSymbol, timeframe)
        if (drawings.isNotEmpty()) insertDrawings(drawings)
    }

    /** Total rows; used by the retention check and by tests. */
    @Query("SELECT COUNT(*) FROM chart_drawings")
    suspend fun countDrawings(): Int

    companion object {
        /**
         * Row cap per chart.
         *
         * WHY 200: matches the in-memory [com.devcode940.ForexAnalyst.data.chart.DrawingStore]
         * limit, so the persisted set and the editable set never disagree — a drawing
         * that exists in the database but can never be loaded is indistinguishable
         * from data loss.
         */
        const val MAX_ROWS = 200
    }
}
