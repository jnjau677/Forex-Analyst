package com.devcode940.ForexAnalyst.data.chart

import com.devcode940.ForexAnalyst.data.model.ChartDrawing
import com.devcode940.ForexAnalyst.data.model.DrawingId
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Timeframe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/** Undo/redo availability, so the toolbar can enable or disable its buttons. */
data class HistoryAvailability(val canUndo: Boolean, val canRedo: Boolean)

/**
 * In-memory store of chart drawings, with undo/redo and per-chart scoping.
 *
 * WHY snapshot history instead of an "inverse operation" stack: every mutation
 * (commit, delete, clear) would need a hand-written inverse, and the first missing
 * inverse is a silent data-loss bug. Storing immutable snapshots makes undo and redo
 * the *same* operation — moving a cursor — bounded by [MAX_HISTORY].
 *
 * WHY a lock rather than a coroutine scope: this is a plain class with no lifecycle.
 * Mutations arrive from the UI thread but nothing prevents a future caller from
 * invoking `undo()` off it, so the three fields that must stay consistent
 * (`history`, `cursor`, the published flows) are updated inside one critical section
 * instead of relying on `StateFlow.update` for a multi-field invariant.
 *
 * WHY an in-memory store in front of Room rather than reading the database directly:
 * a drawing is committed the instant the finger lifts, and the user expects to see it
 * in the same frame. Going through the database for the read path would show the
 * object only after a write round-trip, so a slow disk would make the chart feel like
 * it dropped the gesture. The store is the write-through cache: the ViewModel mutates
 * it (instant echo), persists the result, and reconciles only when the persisted set
 * genuinely differs — see [replaceAllFor].
 *
 * WHY undo/redo lives here and not in SQL: history is a session concept. Keeping 40
 * snapshots in a table would mean writing every intermediate state to disk, and the
 * user's undo stack would then survive a process restart in a way no editor does.
 */
class DrawingStore {

    private val lock = Any()

    /** Snapshot history; index [cursor] is the live state. */
    private var history: List<List<ChartDrawing>> = listOf(emptyList())
    private var cursor: Int = 0

    private val _drawings = MutableStateFlow<List<ChartDrawing>>(emptyList())

    /** Every drawing on every chart, newest last. */
    val drawings: StateFlow<List<ChartDrawing>> = _drawings.asStateFlow()

    private val _historyState = MutableStateFlow(HistoryAvailability(canUndo = false, canRedo = false))

    /** Undo/redo availability for the toolbar. */
    val historyState: StateFlow<HistoryAvailability> = _historyState.asStateFlow()

    /** Drawings belonging to one chart, as a hot stream. */
    fun drawingsFor(pairSymbol: PairSymbol, timeframe: Timeframe): Flow<List<ChartDrawing>> =
        drawings.map { list -> list.filter { it.pairSymbol == pairSymbol && it.timeframe == timeframe } }

    /** Drawings belonging to one chart, as a snapshot. */
    fun currentFor(pairSymbol: PairSymbol, timeframe: Timeframe): List<ChartDrawing> =
        drawings.value.filter { it.pairSymbol == pairSymbol && it.timeframe == timeframe }

    /**
     * Appends [drawing] as one undoable step and clears the redo branch.
     *
     * WHY clear redo: committing after an undo forks history; keeping the old redo
     * branch would let the user "redo" into a state that no longer contains the
     * drawing they just added.
     */
    fun commit(drawing: ChartDrawing) = commitAll(listOf(drawing))

    /** Commits several drawings as a single undoable step. */
    fun commitAll(drawingsToAdd: List<ChartDrawing>) {
        if (drawingsToAdd.isEmpty()) return
        mutate { current ->
            val next = current + drawingsToAdd
            // WHY: bound memory (and the future persisted row count) by dropping the
            // oldest drawings rather than rejecting the new one.
            if (next.size > MAX_DRAWINGS) next.takeLast(MAX_DRAWINGS) else next
        }
    }

    /**
     * Removes the drawing with [id]; a no-op (no undo step) when it does not exist.
     *
     * @return true when the live snapshot changed. WHY report it: callers persist the
     *   change, and an idempotent retry must not write to the database — nor consume an
     *   undo step, nor surface a storage error for an action that changed nothing.
     */
    fun delete(id: DrawingId): Boolean = mutate { current ->
        val next = current.filterNot { it.id == id }
        // WHY: only push a snapshot when something changed, so an idempotent
        // retry does not consume an undo step.
        if (next.size == current.size) current else next
    }

    /**
     * Replaces every drawing belonging to one chart with [drawings], as a single
     * undoable step.
     *
     * WHY this exists: it is the reconciliation point between the persisted set and
     * the in-memory one. Loading a chart, or undoing back to a state the database
     * already holds, must not append duplicates — and it must not silently discard
     * objects the user placed in this session either. Replacing the whole scoped set
     * is the only operation that is correct in both directions.
     *
     * WHY it pushes a history step only when something changed: re-emitting the same
     * set (which Room does after our own insert) must not consume an undo step, or
     * Undo would appear to do nothing.
     *
     * @param recordHistory pass `false` when the replacement describes state the user
     *   did not create in this session — i.e. when adopting the persisted set at load
     *   time. WHY this parameter exists: adopting is otherwise indistinguishable from
     *   an edit, so opening a chart that already holds drawings would enable Undo, and
     *   the first press would replace the stored set with the empty baseline — one tap
     *   on a freshly opened chart deleting every drawing the user ever made. With
     *   `recordHistory = false` the live snapshot is rewritten in place, so the loaded
     *   objects become the baseline Undo cannot step behind.
     */
    fun replaceAllFor(
        pairSymbol: PairSymbol,
        timeframe: Timeframe,
        drawings: List<ChartDrawing>,
        recordHistory: Boolean = true
    ) {
        if (recordHistory) {
            mutate { current -> adopted(current, pairSymbol, timeframe, drawings) }
            return
        }
        synchronized(lock) {
            val next = adopted(history[cursor], pairSymbol, timeframe, drawings)
            if (next == history[cursor]) return
            val rewritten = history.toMutableList()
            rewritten[cursor] = next
            history = rewritten
            publish()
        }
    }

    /** The whole set with one chart's drawings replaced by [drawings]. */
    private fun adopted(
        current: List<ChartDrawing>,
        pairSymbol: PairSymbol,
        timeframe: Timeframe,
        drawings: List<ChartDrawing>
    ): List<ChartDrawing> {
        val others = current.filterNot { it.pairSymbol == pairSymbol && it.timeframe == timeframe }
        val scoped = drawings.filter { it.pairSymbol == pairSymbol && it.timeframe == timeframe }
        val next = others + scoped
        return if (next.size > MAX_DRAWINGS) next.takeLast(MAX_DRAWINGS) else next
    }

    /** Removes every drawing on one chart. */
    fun clearFor(pairSymbol: PairSymbol, timeframe: Timeframe) {
        mutate { current ->
            val next = current.filterNot { it.pairSymbol == pairSymbol && it.timeframe == timeframe }
            if (next.size == current.size) current else next
        }
    }

    /** Removes every drawing on every chart. */
    fun clearAll() {
        mutate { current -> if (current.isEmpty()) current else emptyList() }
    }

    /** Steps one snapshot back; false when already at the oldest state. */
    fun undo(): Boolean = synchronized(lock) {
        if (cursor <= 0) {
            false
        } else {
            cursor -= 1
            publish()
            true
        }
    }

    /** Steps one snapshot forward; false when already at the newest state. */
    fun redo(): Boolean = synchronized(lock) {
        if (cursor >= history.size - 1) {
            false
        } else {
            cursor += 1
            publish()
            true
        }
    }

    /** Number of drawings in the live state. */
    val size: Int get() = synchronized(lock) { history[cursor].size }

    /** Number of retained snapshots; exposed for tests. */
    val historyDepth: Int get() = synchronized(lock) { history.size }

    /** Applies [transform] as one undoable step; returns whether anything changed. */
    private fun mutate(transform: (List<ChartDrawing>) -> List<ChartDrawing>): Boolean =
        synchronized(lock) {
            val current = history[cursor]
            val next = transform(current)
            if (next == current) {
                false
            } else {
                val truncated = history.take(cursor + 1) + next
                history = if (truncated.size > MAX_HISTORY) truncated.takeLast(MAX_HISTORY) else truncated
                cursor = history.size - 1
                publish()
                true
            }
        }

    /** Publishes the live snapshot to both derived flows. Callers hold [lock]. */
    private fun publish() {
        _drawings.value = history[cursor]
        _historyState.value = HistoryAvailability(
            canUndo = cursor > 0,
            canRedo = cursor < history.size - 1
        )
    }

    companion object {
        /** Snapshots retained for undo/redo. */
        const val MAX_HISTORY = 40

        /** Drawings retained across all charts. */
        const val MAX_DRAWINGS = 200
    }
}
