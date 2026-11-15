package com.devcode940.ForexAnalyst.data.provider

import java.util.Locale

/**
 * Makes a provider's own error text safe to render and to log.
 *
 * WHY this is a separate, tested class: the honest way to explain a refusal is to
 * quote the provider ("FCS: invalid access_key"). That text arrives from the
 * network and both feeds echo the query string back in some error paths — which
 * contains the API key. An unsanitized `AppError.ProviderRejected(reason)` would
 * therefore put the user's key into a snackbar, into logcat, and into any bug
 * report the user attaches. This is the one place that assumption is enforced.
 *
 * WHY redaction rather than dropping the message entirely: a bare "provider
 * refused" is unactionable, and users then send screenshots of their own key
 * asking for help. Redaction keeps the diagnostic value and removes the secret.
 */
object ProviderMessageSanitizer {

    /** Longest reason kept; longer provider prose is noise in a snackbar. */
    const val MAX_REASON_LENGTH = 180

    /**
     * Query-string parameters that carry a credential on either feed.
     *
     * WHY matched case-insensitively and with both separators: `apikey`,
     * `access_key`, `api_key` and `token` all appear across the two providers'
     * docs and error bodies, and a provider that echoes `Access_Key=...` must not
     * slip through a case-sensitive filter.
     */
    private val SECRET_PARAMETERS = listOf("apikey", "api_key", "access_key", "token", "key")

    /**
     * Returns [raw] with credentials removed, whitespace collapsed and length
     * bounded. Never returns null and never returns a blank string for non-blank
     * input — callers render the result directly.
     *
     * @param knownSecrets extra strings to scrub verbatim, typically the API keys
     *   actually configured for this session. WHY this is a parameter and not a
     *   lookup: the sanitizer is pure and unit-testable, and it must not need a
     *   `Context` to work.
     */
    fun sanitize(raw: String?, knownSecrets: Collection<String> = emptyList()): String {
        if (raw.isNullOrBlank()) return "no details supplied"

        var text = raw

        // 1. Exact known secrets first: these are the ones that really matter and
        //    they may not sit next to a recognizable parameter name.
        knownSecrets
            .filter { it.isNotBlank() && it.length >= MIN_SECRET_LENGTH }
            .forEach { secret -> text = text.replace(secret, REDACTED, ignoreCase = false) }

        // 2. Parameter-shaped secrets, for keys the app did not configure itself
        //    (a proxy-issued token, a key embedded in an echoed URL). One pass
        //    over every `name=value` pair: WHY not one pass per parameter name —
        //    five sequential rewrites of the same string are five chances for an
        //    earlier replacement to change what a later pattern matches.
        text = PARAMETER_PATTERN.toRegex().replace(text) { match ->
            val name = match.groupValues[1]
            if (name.lowercase(Locale.US) in SECRET_PARAMETERS) "$name=$REDACTED" else match.value
        }

        // 3. Collapse whitespace and bound the length.
        text = text.replace(WHITESPACE.toRegex(), " ").trim()
        if (text.length > MAX_REASON_LENGTH) {
            text = text.take(MAX_REASON_LENGTH - ELLIPSIS.length).trimEnd() + ELLIPSIS
        }
        return text.ifBlank { "no details supplied" }
    }

    private const val REDACTED = "[redacted]"
    private const val ELLIPSIS = "..."
    private const val MIN_SECRET_LENGTH = 6
    private const val PARAMETER_PATTERN = "([A-Za-z_][A-Za-z0-9_]*)=([^&\\s]+)"
    private const val WHITESPACE = "\\s+"
}
