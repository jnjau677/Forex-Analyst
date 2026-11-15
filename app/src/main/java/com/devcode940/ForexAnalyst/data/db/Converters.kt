package com.devcode940.ForexAnalyst.data.db

import androidx.room.TypeConverter
import java.time.Instant

/**
 * Room type converters for the persistence layer.
 *
 * WHY only [Instant]: every entity column is a SQLite-native type (String,
 * Long, Double, Boolean) except timestamps, which the domain models as
 * [Instant]. Value-class and enum conversion (PairSymbol, Timeframe,
 * SignalType, SignalId, AlertId) deliberately lives in the repository mappers
 * instead of here:
 *
 * 1. KAPT mangles the generated Java signature of a function that takes a
 *    `@JvmInline value class` (name suffix + erased parameter type), so Room
 *    cannot reliably discover such a converter — it either ignores it or, at
 *    worst, fails the kapt task with a confusing "no converter" error.
 * 2. Mappers can be defensive per row (skip + log a corrupt record), which a
 *    converter cannot: a converter has no context and must return something,
 *    so a fallback value silently rewrites reality (e.g. an unknown signal
 *    type becoming BUY).
 *
 * Both directions are non-null because every timestamp column is NOT NULL.
 */
class Converters {

    /** Stores an [Instant] as epoch milliseconds (INTEGER, UTC, no zone ambiguity). */
    @TypeConverter
    fun fromInstant(value: Instant): Long = value.toEpochMilli()

    /** Reads epoch milliseconds back into an [Instant]. */
    @TypeConverter
    fun toInstant(value: Long): Instant = Instant.ofEpochMilli(value)
}
