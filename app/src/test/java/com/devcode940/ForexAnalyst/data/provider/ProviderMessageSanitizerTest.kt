package com.devcode940.ForexAnalyst.data.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [ProviderMessageSanitizer].
 *
 * WHY every case here is about a secret rather than about formatting: this function is
 * the last thing standing between a provider's response body and a snackbar, a logcat
 * line and a bug-report attachment. Both feeds take the API key as a *query parameter*
 * and both echo request details in some error paths, so a formatting bug is cosmetic
 * while a redaction bug leaks a credential.
 */
class ProviderMessageSanitizerTest {

    private val key = "AV_KEY_1234567890"

    @Test
    fun `a configured key is removed wherever it appears`() {
        val input = "Invalid call: your key $key is not recognised for this endpoint."
        val output = ProviderMessageSanitizer.sanitize(input, listOf(key))

        assertFalse("the key survived: $output", output.contains(key))
        assertTrue("the message should still be useful: $output", output.contains("not recognised"))
        assertTrue(output.contains("[redacted]"))
    }

    @Test
    fun `an apikey query parameter is redacted`() {
        val output = ProviderMessageSanitizer.sanitize("https://www.alphavantage.co/query?function=FX_INTRADAY&apikey=$key")

        assertFalse("the key survived: $output", output.contains(key))
        assertTrue("the parameter name should stay so the message is diagnosable: $output", output.contains("apikey=[redacted]"))
        assertTrue("non-secret parameters must survive: $output", output.contains("function=FX_INTRADAY"))
    }

    @Test
    fun `an access_key query parameter is redacted`() {
        val output = ProviderMessageSanitizer.sanitize("https://fcsapi.com/api-v4/forex/history?symbol=EURUSD&access_key=$key")

        assertFalse(output.contains(key))
        assertTrue(output.contains("access_key=[redacted]"))
        assertTrue(output.contains("symbol=EURUSD"))
    }

    @Test
    fun `parameter matching ignores case`() {
        val output = ProviderMessageSanitizer.sanitize("Access_Key=$key was rejected")

        assertFalse("the key survived: $output", output.contains(key))
        assertTrue(output.contains("Access_Key=[redacted]"))
    }

    @Test
    fun `every documented credential parameter name is covered`() {
        listOf("apikey", "api_key", "access_key", "token", "key").forEach { name ->
            val output = ProviderMessageSanitizer.sanitize("$name=SUPERSECRETVALUE")
            assertFalse("'$name' was not redacted: $output", output.contains("SUPERSECRETVALUE"))
        }
    }

    @Test
    fun `a key that is not configured is still caught by its parameter name`() {
        // WHY this case exists: a proxy-issued token, or a key typed into a different
        // field, is not in `knownSecrets` — the parameter-shaped rule is the backstop.
        val output = ProviderMessageSanitizer.sanitize("Rejected request apikey=NOT_MINE_999")

        assertFalse(output.contains("NOT_MINE_999"))
        assertTrue(output.contains("apikey=[redacted]"))
    }

    @Test
    fun `an over-long message is truncated with an ellipsis`() {
        val input = "x".repeat(1_000)
        val output = ProviderMessageSanitizer.sanitize(input)

        assertEquals(ProviderMessageSanitizer.MAX_REASON_LENGTH, output.length)
        assertTrue("truncation must be visible: $output", output.endsWith("..."))
    }

    @Test
    fun `whitespace is collapsed so a multi-line body renders on one line`() {
        val output = ProviderMessageSanitizer.sanitize("rate   limit\n\treached\r\nfor this key")

        assertEquals("rate limit reached for this key", output)
    }

    @Test
    fun `a null or blank message never renders as an empty string`() {
        assertEquals("no details supplied", ProviderMessageSanitizer.sanitize(null))
        assertEquals("no details supplied", ProviderMessageSanitizer.sanitize(""))
        assertEquals("no details supplied", ProviderMessageSanitizer.sanitize("   \n\t "))
    }

    @Test
    fun `a message that is nothing but a secret still yields usable text`() {
        val output = ProviderMessageSanitizer.sanitize(key, listOf(key))

        assertTrue(output.isNotBlank())
        assertFalse(output.contains(key))
    }

    @Test
    fun `short configured values are not treated as secrets`() {
        // WHY: scrubbing every occurrence of "USD" because a key field once held "USD"
        // would turn "EURUSD is not supported" into "EUR[redacted] is not supported".
        val output = ProviderMessageSanitizer.sanitize("EURUSD is not supported", listOf("USD"))

        assertEquals("EURUSD is not supported", output)
    }

    @Test
    fun `redaction is not defeated by the key appearing twice`() {
        val input = "key $key rejected; retry with $key"
        val output = ProviderMessageSanitizer.sanitize(input, listOf(key))

        assertFalse(output.contains(key))
        assertEquals(2, Regex("\\[redacted]").findAll(output).count())
    }
}
