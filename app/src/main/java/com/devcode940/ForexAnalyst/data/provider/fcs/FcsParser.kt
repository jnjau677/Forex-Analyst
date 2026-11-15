package com.devcode940.ForexAnalyst.data.provider.fcs

import android.util.Log
import com.devcode940.ForexAnalyst.data.model.AppError
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.MarketDataException
import com.devcode940.ForexAnalyst.data.provider.ProviderMessageSanitizer
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns an FCS API v4 payload into [CandleStick]s, or into a classified [AppError].
 *
 * Verified against `https://fcsapi.com/document/forex-api`:
 *
 * - every endpoint answers with one envelope: `status` (bool), `code` (200 on
 *   success), `msg`, `response` (the data) and `info` (server time, credits used);
 * - history returns bars as a **map keyed by unix timestamp**, each bar holding
 *   `o`, `h`, `l`, `c`, `v`, `vw`, `t` (unix time) and `tm` (human-readable time).
 *
 * WHY the parser still accepts an array, a bare map without the envelope, and the
 * `date`/`d`+`t` spellings from the older v3 API: the provider has changed its
 * response shape across versions, and the app has no way to detect that change
 * other than "the chart went blank". Tolerating the known variants costs a few
 * lines here and removes a whole class of remote-breakage.
 *
 * WHY this is separate from the Retrofit client: like `AlphaVantageParser`, it is
 * the only part worth testing, and as a pure function over [JsonObject] it runs on
 * the JVM with no network and no OkHttp.
 */
object FcsParser {

    private const val TAG = "FcsParser"

    private const val KEY_STATUS = "status"
    private const val KEY_CODE = "code"
    private const val KEY_MESSAGE = "msg"
    private const val KEY_RESPONSE = "response"

    /** Unix seconds are ten digits; milliseconds are thirteen. */
    private const val MILLIS_THRESHOLD = 100_000_000_000L

    private val HUMAN_TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Classifies a refusal envelope, or returns null when the payload holds data.
     *
     * WHY `status`/`code` are checked before anything else: the provider answers
     * HTTP 200 for an invalid key, an exhausted quota and an unknown symbol alike,
     * putting the real outcome in the body. A status-only HTTP check would report
     * success for every one of them and blank the chart.
     */
    fun refusal(root: JsonObject?, knownSecrets: Collection<String> = emptyList()): AppError? {
        if (root == null) return AppError.Network

        val status = root.get(KEY_STATUS)?.takeIf { it.isJsonPrimitive }
        val statusOk = when {
            status == null -> true
            status.asBooleanOrNull() != null -> status.asBoolean
            else -> status.asString.equals("true", ignoreCase = true)
        }
        val code = root.int(KEY_CODE)
        val message = root.string(KEY_MESSAGE)

        val refused = !statusOk || (code != null && code != FcsApi.BODY_CODE_SUCCESS)
        if (!refused) return null

        val text = message.orEmpty()
        val lower = text.lowercase(Locale.US)
        return when {
            code == 401 || code == 403 ||
                lower.contains("invalid access key") ||
                lower.contains("invalid api key") ||
                (lower.contains("access key") && lower.contains("invalid")) -> AppError.Unauthorized

            code == 429 ||
                lower.contains("rate limit") ||
                lower.contains("quota") ||
                lower.contains("credit") ||
                lower.contains("exceed") && lower.contains("limit") -> AppError.RateLimited

            lower.contains("not found") ||
                lower.contains("no data") ||
                lower.contains("invalid symbol") ||
                lower.contains("unsupported") -> AppError.Unsupported

            else -> AppError.ProviderRejected(
                ProviderMessageSanitizer.sanitize(
                    if (text.isBlank()) "FCS API returned code ${code ?: "unknown"}" else text,
                    knownSecrets
                )
            )
        }
    }

    /**
     * Parses the bars, oldest-first.
     *
     * @throws MarketDataException carrying a classified error when the payload is a
     *   refusal or holds no usable bar.
     */
    fun parseCandles(root: JsonObject, knownSecrets: Collection<String> = emptyList()): List<CandleStick> {
        refusal(root, knownSecrets)?.let { throw MarketDataException(it) }

        val payload = root.get(KEY_RESPONSE)?.takeIf { it.isJsonObject || it.isJsonArray } ?: root
        val bars: List<Pair<String?, JsonObject>> = when (payload) {
            is JsonObject -> payload.entrySet()
                .mapNotNull { (key, value) -> value.asObjectOrNull()?.let { key to it } }

            is JsonArray -> payload.mapNotNull { element -> element.asObjectOrNull()?.let { null to it } }
            else -> emptyList()
        }

        val candles = bars
            .mapNotNull { (key, bar) -> barAt(bar, key) }
            .distinctBy { it.time }
            // WHY sort rather than trust the payload: the chart, the indicators and
            // the alert evaluator all require oldest-first, and a JSON map's key
            // order is not a contract the provider owes us.
            .sortedBy { it.time }

        if (candles.isEmpty()) {
            throw MarketDataException(AppError.Unsupported)
        }
        return candles
    }

    private fun barAt(bar: JsonObject, mapKey: String?): CandleStick? {
        val open = bar.number("o", "open") ?: return null
        val high = bar.number("h", "high") ?: return null
        val low = bar.number("l", "low") ?: return null
        val close = bar.number("c", "close") ?: return null
        val timestamp = timestampOf(bar, mapKey) ?: run {
            Log.w(TAG, "skipping bar with no usable timestamp")
            return null
        }
        val volume = bar.number("v", "volume")?.toLong()?.coerceAtLeast(0L) ?: 0L

        // WHY runCatching: CandleStick enforces high >= low and finite prices, and one
        // corrupt bar from the provider must not discard the whole series.
        return runCatching {
            CandleStick(
                time = timestamp,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume
            )
        }.getOrElse {
            Log.w(TAG, "skipping invalid bar at $timestamp")
            null
        }
    }

    /**
     * Resolves a bar's timestamp from whichever field carries one.
     *
     * Precedence: `t` (documented unix time) -> `timestamp`/`ts` -> `tm` (documented
     * human-readable) -> `date` -> `d` + `t` (v3 spelling) -> the map key.
     *
     * WHY the map key is a candidate at all: the documented response *is* keyed by
     * unix seconds, so a bar that omitted `t` is still perfectly datable.
     *
     * Human-readable values are interpreted as UTC. WHY that is safe rather than
     * hopeful: the provider publishes `t` alongside `tm` for every bar, so `tm` is
     * only reached when `t` is missing, and the whole app already assumes UTC —
     * disagreeing with itself would shift bars by hours.
     */
    private fun timestampOf(bar: JsonObject, mapKey: String?): Instant? {
        bar.long("t", "timestamp", "ts")?.let { return fromUnix(it) }
        bar.string("tm")?.let { parseHuman(it)?.let { instant -> return instant } }
        bar.string("date")?.let { parseHuman(it)?.let { instant -> return instant } }

        val datePart = bar.string("d")
        val timePart = bar.string("t")
        if (datePart != null) {
            val combined = if (timePart != null && ':' in timePart) "$datePart $timePart" else datePart
            parseHuman(combined)?.let { return it }
        }
        mapKey?.toLongOrNull()?.let { return fromUnix(it) }
        return null
    }

    private fun fromUnix(value: Long): Instant =
        if (value > MILLIS_THRESHOLD) Instant.ofEpochMilli(value) else Instant.ofEpochSecond(value)

    private fun parseHuman(raw: String): Instant? = runCatching {
        val trimmed = raw.trim()
        if (trimmed.length <= DATE_ONLY_MAX_LENGTH) {
            LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant()
        } else {
            LocalDateTime.parse(trimmed, HUMAN_TIMESTAMP).toInstant(ZoneOffset.UTC)
        }
    }.getOrNull()

    private fun JsonObject.string(vararg keys: String): String? {
        for (key in keys) {
            val element = get(key) ?: continue
            if (!element.isJsonPrimitive) continue
            val text = element.asString.trim()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    private fun JsonObject.int(key: String): Int? = long(key)?.toInt()

    private fun JsonObject.long(vararg keys: String): Long? {
        for (key in keys) {
            val element = get(key) ?: continue
            if (!element.isJsonPrimitive) continue
            element.asString.trim().toLongOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.number(vararg keys: String): Double? {
        for (key in keys) {
            val element = get(key) ?: continue
            if (!element.isJsonPrimitive) continue
            val parsed = element.asString.trim().toDoubleOrNull()
            if (parsed != null && parsed.isFinite()) return parsed
        }
        return null
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? =
        if (isJsonObject) asJsonObject else null

    private fun JsonElement.asBooleanOrNull(): Boolean? = runCatching { asBoolean }.getOrNull()

    /** Longest timestamp that is a bare `yyyy-MM-dd`. */
    private const val DATE_ONLY_MAX_LENGTH = 10
}
