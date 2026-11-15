package com.devcode940.ForexAnalyst.data.model

import java.util.UUID

/**
 * Strongly-typed identity for a saved trade signal.
 *
 * WHY: A value class wrapping [UUID] (not String) makes it impossible to
 * accidentally pass an alert id where a signal id is expected, and gives
 * collision-free identifiers without any coordination.
 */
@JvmInline
value class SignalId(val value: UUID) {
    companion object {
        /** WHY: Random UUIDs need no server round-trip and never collide in practice. */
        fun generate(): SignalId = SignalId(UUID.randomUUID())
    }
}

/**
 * Strongly-typed identity for a user-created price alert.
 * See [SignalId] for why UUID beats String here.
 */
@JvmInline
value class AlertId(val value: UUID) {
    companion object {
        fun generate(): AlertId = AlertId(UUID.randomUUID())
    }
}

/**
 * A forex pair symbol such as "EURUSD".
 *
 * WHY: [of] normalizes casing/whitespace so "eurusd", " EURUSD " and
 * "EURUSD" are the same value — no duplicate watchlist rows or missed
 * cache hits from casing differences. Prefer [of] for any input that is
 * not already a canonical literal.
 */
@JvmInline
value class PairSymbol(val value: String) {
    init {
        require(value.isNotBlank()) { "PairSymbol cannot be blank" }
    }

    companion object {
        /** WHY: Single normalization point for all external input (nav args, search, DB). */
        fun of(raw: String): PairSymbol = PairSymbol(raw.trim().uppercase())
    }
}

/**
 * A candle timeframe such as "H1".
 *
 * WHY: Validating against a closed set at construction turns a typo
 * ("h1", "1H") into an immediate, debuggable failure instead of a silent
 * empty result set from the database.
 */
@JvmInline
value class Timeframe(val value: String) {
    init {
        require(value in VALID_TIMEFRAMES) { "Invalid timeframe: $value" }
    }

    companion object {
        /**
         * Every supported timeframe, finest first.
         *
         * WHY: one ordered source of truth for BOTH validation and the UI
         * chips — the old selector hardcoded "M5..D1", so M1/W1 existed in the
         * domain but were unreachable, and adding a timeframe meant editing
         * two files that could drift apart.
         */
        val ORDERED: List<String> = listOf("M1", "M5", "M15", "H1", "H4", "D1", "W1")

        /** Accepted values; derived from [ORDERED] so they cannot disagree. */
        val VALID_TIMEFRAMES: Set<String> = ORDERED.toSet()
    }
}
