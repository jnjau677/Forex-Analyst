package com.devcode940.ForexAnalyst.data.provider.alphavantage

import android.util.Log
import com.devcode940.ForexAnalyst.data.model.AppError
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.MarketDataException
import com.devcode940.ForexAnalyst.data.provider.ProviderMessageSanitizer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns an Alpha Vantage payload into [CandleStick]s, or into a classified
 * [AppError] when the provider refused.
 *
 * WHY this is a separate object from the Retrofit client: it is the only part of
 * the feed worth testing, and as a pure function over [JsonObject] it runs on the
 * JVM with no OkHttp, no MockWebServer and no network. The HTTP client around it
 * is then thin enough to trust by inspection.
 *
 * Three properties of this provider's responses shape the code:
 *
 * 1. **A refusal is a 200.** Rate limiting and invalid keys come back as HTTP 200
 *    with `{"Note": ...}`, `{"Information": ...}` or `{"Error Message": ...}` and
 *    no series. Treating "200 + no data" as an empty result would silently blank
 *    the chart; it has to become `AppError.RateLimited` so the user learns to wait
 *    rather than to reinstall.
 * 2. **The series key embeds the interval** (`"Time Series FX (5min)"`), so it is
 *    located by prefix, never by exact name.
 * 3. **Timestamps carry an explicit zone in `Meta Data`.** FX timestamps are not
 *    guaranteed to be UTC, and reading the declared zone is the only way an H1
 *    chart of a provider that means US/Eastern does not shift every bar by five
 *    hours — which would misalign session shading and every VWAP anchor.
 */
object AlphaVantageParser {

    private const val TAG = "AlphaVantageParser"

    private const val KEY_NOTE = "Note"
    private const val KEY_INFORMATION = "Information"
    private const val KEY_ERROR_MESSAGE = "Error Message"
    private const val SERIES_KEY_PREFIX = "Time Series"
    private const val META_KEY = "Meta Data"
    private const val TIME_ZONE_SUFFIX = "Time Zone"

    private val INTRADAY_TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** OHLC field names, in the provider's numbered spelling and in the plain one. */
    private val OPEN_KEYS = listOf("1. open", "open")
    private val HIGH_KEYS = listOf("2. high", "high")
    private val LOW_KEYS = listOf("3. low", "low")
    private val CLOSE_KEYS = listOf("4. close", "close")

    /**
     * Classifies a refusal payload, or returns null when the payload looks like data.
     *
     * @param knownSecrets API keys to scrub from the quoted provider message.
     */
    fun refusal(root: JsonObject?, knownSecrets: Collection<String> = emptyList()): AppError? {
        if (root == null) return AppError.Network
        // WHY data wins: some responses carry an informational string *alongside* a
        // valid series. Refusing a payload that contains bars would blank a working
        // chart to report a message the user cannot act on.
        if (seriesOf(root) != null) return null

        val note = root.string(KEY_NOTE)
        val information = root.string(KEY_INFORMATION)
        val errorMessage = root.string(KEY_ERROR_MESSAGE)
        val quoted = listOfNotNull(note, information, errorMessage).firstOrNull()

        if (quoted != null) {
            val lower = quoted.lowercase(Locale.US)
            return when {
                // WHY these first: a rate-limit message that also mentions the key
                // must not be reported as an auth failure, or the user replaces a
                // perfectly good key.
                lower.contains("per minute") || lower.contains("per day") ||
                    lower.contains("rate limit") || lower.contains("call frequency") ->
                    AppError.RateLimited

                lower.contains("invalid api key") ||
                    (lower.contains("apikey") && lower.contains("invalid")) ->
                    AppError.Unauthorized

                else -> AppError.ProviderRejected(ProviderMessageSanitizer.sanitize(quoted, knownSecrets))
            }
        }
        // A payload with neither a series nor a refusal is an unrecognised shape —
        // most often the provider changing its response, or a captive-portal HTML
        // page that happened to parse.
        return AppError.ProviderRejected(
            ProviderMessageSanitizer.sanitize(root.toString(), knownSecrets)
        )
    }

    /**
     * Parses the OHLC series, oldest-first.
     *
     * @throws MarketDataException carrying a classified error when the payload is a
     *   refusal or contains no usable bar. WHY throw rather than return an empty
     *   list: an empty list is a legitimate answer ("this market has no bars"), and
     *   conflating it with failure would make the gateway fall back to the
     *   simulator on a working feed.
     */
    fun parseCandles(root: JsonObject, knownSecrets: Collection<String> = emptyList()): List<CandleStick> {
        refusal(root, knownSecrets)?.let { throw MarketDataException(it) }

        // refusal() above has already established that a series is present.
        val series = seriesOf(root)
            ?: throw MarketDataException(AppError.ProviderRejected("response contained no time series"))
        val zone = zoneOf(root)

        val candles = series.entrySet()
            .mapNotNull { (rawTimestamp, bar) ->
                val timestamp = parseTimestamp(rawTimestamp, zone) ?: run {
                    Log.w(TAG, "skipping bar with unparseable timestamp")
                    return@mapNotNull null
                }
                bar.asObjectOrNull()?.let { obj -> barAt(obj, timestamp) }
            }
            .distinctBy { it.time }
            // WHY sort rather than trust insertion order: the provider returns
            // newest-first today, and the chart, the indicators and the alert
            // evaluator all assume oldest-first. A provider that flips its order
            // must not be able to reverse this app's price history.
            .sortedBy { it.time }

        if (candles.isEmpty()) {
            throw MarketDataException(
                AppError.ProviderRejected("time series contained no usable bars")
            )
        }
        return candles
    }

    /**
     * The zone the provider says its timestamps are in.
     *
     * Falls back to UTC when the field is absent or unrecognised: UTC is what the
     * rest of this app assumes, and a wrong-but-consistent zone is far less
     * damaging than a crash.
     */
    fun zoneOf(root: JsonObject): ZoneId {
        val meta = root.getAsObject(META_KEY) ?: return ZoneOffset.UTC
        val declared = meta.entrySet()
            .firstOrNull { it.key.endsWith(TIME_ZONE_SUFFIX) }
            ?.value?.takeIf { it.isJsonPrimitive }?.asString
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ZoneOffset.UTC
        return runCatching { ZoneId.of(declared) }
            .getOrElse {
                Log.w(TAG, "unknown time zone '$declared'; assuming UTC")
                ZoneOffset.UTC
            }
    }

    /** The OHLC map, or null when the payload has no series member. */
    private fun seriesOf(root: JsonObject): JsonObject? =
        root.entrySet()
            .firstOrNull { it.key.startsWith(SERIES_KEY_PREFIX) && it.value.isJsonObject }
            ?.value
            ?.asJsonObject

    private fun barAt(bar: JsonObject, timestamp: Instant): CandleStick? {
        val open = bar.number(OPEN_KEYS) ?: return null
        val high = bar.number(HIGH_KEYS) ?: return null
        val low = bar.number(LOW_KEYS) ?: return null
        val close = bar.number(CLOSE_KEYS) ?: return null
        // WHY runCatching: CandleStick enforces high >= low and finite prices, and
        // a single corrupt bar from the provider must not discard the whole series.
        // WHY volume is 0: Alpha Vantage's FX endpoints publish no volume, and
        // inventing one would put a fabricated number into the volume pane and
        // into the VWAP weights.
        return runCatching {
            CandleStick(time = timestamp, open = open, high = high, low = low, close = close, volume = 0L)
        }.getOrElse {
            Log.w(TAG, "skipping invalid bar (high/low/order)")
            null
        }
    }

    private fun parseTimestamp(raw: String, zone: ZoneId): Instant? = runCatching {
        val trimmed = raw.trim()
        if (trimmed.length <= DATE_ONLY_MAX_LENGTH) {
            // FX_DAILY keys are bare dates; anchor them at the start of that day in
            // the declared zone so consecutive daily bars stay 24 h apart.
            LocalDate.parse(trimmed).atStartOfDay(zone).toInstant()
        } else {
            LocalDateTime.parse(trimmed, INTRADAY_TIMESTAMP).atZone(zone).toInstant()
        }
    }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.number(keys: List<String>): Double? {
        for (key in keys) {
            val element: JsonElement = get(key) ?: continue
            if (!element.isJsonPrimitive) continue
            val parsed = element.asString.trim().toDoubleOrNull()
            if (parsed != null && parsed.isFinite()) return parsed
        }
        return null
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? =
        if (isJsonObject) asJsonObject else null

    /** Longest timestamp that is a bare `yyyy-MM-dd` (10 characters). */
    private const val DATE_ONLY_MAX_LENGTH = 10
}
