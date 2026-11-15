package com.devcode940.ForexAnalyst.data.db

import androidx.room.TypeConverter
import com.devcode940.ForexAnalyst.data.model.*
import java.time.Instant

class Converters {
    @TypeConverter fun fromSignalType(v: SignalType): String = v.name
    @TypeConverter fun toSignalType(v: String): SignalType = SignalType.valueOf(v)
    @TypeConverter fun fromInstant(v: Instant): Long = v.toEpochMilli()
    @TypeConverter fun toInstant(v: Long): Instant = Instant.ofEpochMilli(v)
    @TypeConverter fun fromPairSymbol(v: PairSymbol): String = v.value
    @TypeConverter fun toPairSymbol(v: String): PairSymbol = PairSymbol(v)
    @TypeConverter fun fromSignalId(v: SignalId): String = v.value
    @TypeConverter fun toSignalId(v: String): SignalId = SignalId(v)
    @TypeConverter fun fromAlertId(v: AlertId): String = v.value
    @TypeConverter fun toAlertId(v: String): AlertId = AlertId(v)
    @TypeConverter fun fromTimeframe(v: Timeframe): String = v.value
    @TypeConverter fun toTimeframe(v: String): Timeframe = Timeframe(v)
    @TypeConverter fun fromAlertConditionType(v: AlertConditionType): String = v.name
    @TypeConverter fun toAlertConditionType(v: String): AlertConditionType = AlertConditionType.valueOf(v)
}