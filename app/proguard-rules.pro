# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# WHY: Release builds run R8 (isMinifyEnabled = true). Keep line numbers so
# crash reports stay readable, and hide the original source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WHY: Gson needs generic signatures and annotations at runtime.
-keepattributes Signature
-keepattributes *Annotation*

# ---------------------------------------------------------------------------
# Gson (settings persistence)
# ---------------------------------------------------------------------------
# WHY: SettingsRepository serializes AppSettings with Gson, which resolves
# fields REFLECTIVELY by name. Without these rules R8 renames/strips the
# fields and every release-build install silently loses its settings and API
# keys on the first load (Gson finds no matching field -> default value).
-keepclassmembers,allowobfuscation class com.devcode940.ForexAnalyst.data.settings.AppSettings {
    <fields>;
    <init>();
}

# WHY: @SerializedName fields must keep their serialized names.
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# WHY: Gson instantiates enums through values()/valueOf() reflectively
# (ThemeMode is persisted as "system"/"light"/"dark").
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ---------------------------------------------------------------------------
# Room
# ---------------------------------------------------------------------------
# WHY: Room's generated Impl classes are looked up by name at runtime.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# WHY: TypeConverters are invoked from generated code; keep the Instant
# converters reachable and unobfuscated for stack traces.
-keep class com.devcode940.ForexAnalyst.data.db.Converters { *; }

# ---------------------------------------------------------------------------
# Kotlin coroutines / value classes
# ---------------------------------------------------------------------------
# WHY: ServiceLoader-based coroutine exception handlers and debug probes.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# WHY: @JvmInline value classes are erased to their underlying type at runtime;
# keep the names for readable logs and reflection-free equality.
-keep class com.devcode940.ForexAnalyst.data.model.SignalId { *; }
-keep class com.devcode940.ForexAnalyst.data.model.AlertId { *; }
-keep class com.devcode940.ForexAnalyst.data.model.PairSymbol { *; }
-keep class com.devcode940.ForexAnalyst.data.model.Timeframe { *; }

# ---------------------------------------------------------------------------
# Networking (Retrofit/OkHttp are declared for the future live-data source)
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
