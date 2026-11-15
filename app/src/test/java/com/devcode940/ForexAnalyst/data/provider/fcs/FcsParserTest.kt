package com.devcode940.ForexAnalyst.data.provider.fcs

import com.devcode940.ForexAnalyst.data.model.AppError
import com.devcode940.ForexAnalyst.data.model.MarketDataException
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/**
 * Covers [FcsParser] and the client's period table.
 *
 * The fixtures follow the shape documented at `fcsapi.com/document/forex-api`: one
 * envelope (`status`, `code`, `msg`, `response`, `info`) around a map of bars keyed by
 * unix timestamp. The tolerant variants (array body, v3 `d`+`t` spelling, millisecond
 * timestamps) are asserted too, because the provider has changed its response shape
 * between API versions and this app has no way to detect that other than "the chart went
 * blank".
 */
class FcsParserTest {

    private fun json(body: String) = JsonParser.parseString(body).asJsonObject

    // ---------- successful series ----------

    @Test
    fun `the documented v4 map shape is parsed into ascending candles`() {
        val root = json(
            """
            {
              "status": true,
              "code": 200,
              "msg": "Operation is successful",
              "response": {
                "1748818800": {"o":1.14363,"h":1.14510,"l":1.14300,"c":1.14480,"v":120400,"vw":1.1442,"t":1748818800,"tm":"2025-06-01 23:00:00"},
                "1748815200": {"o":1.13479,"h":1.14495,"l":1.13476,"c":1.14363,"v":434204,"vw":1.144,"t":1748815200,"tm":"2025-06-01 22:00:00"}
              },
              "info": {"credit_count": 1}
            }
            """.trimIndent()
        )

        val candles = FcsParser.parseCandles(root)

        assertEquals(2, candles.size)
        // WHY ordering is asserted: a JSON map's key order is not a contract, and every
        // consumer of this list assumes oldest-first.
        assertEquals(Instant.ofEpochSecond(1748815200), candles.first().time)
        assertEquals(1.13479, candles.first().open, 1e-9)
        assertEquals(1.14495, candles.first().high, 1e-9)
        assertEquals(1.13476, candles.first().low, 1e-9)
        assertEquals(1.14363, candles.first().close, 1e-9)
        assertEquals(Instant.ofEpochSecond(1748818800), candles.last().time)
    }

    @Test
    fun `volume is carried through because this feed publishes it`() {
        val root = json(
            """
            {"status":true,"code":200,"response":{"1748815200":{"o":1.0,"h":1.2,"l":0.9,"c":1.1,"v":434204,"t":1748815200}}}
            """.trimIndent()
        )
        assertEquals(434204L, FcsParser.parseCandles(root).single().volume)
    }

    @Test
    fun `a negative volume is clamped rather than rejected`() {
        val root = json(
            """
            {"status":true,"code":200,"response":{"1748815200":{"o":1.0,"h":1.2,"l":0.9,"c":1.1,"v":-5,"t":1748815200}}}
            """.trimIndent()
        )
        // WHY: CandleStick requires volume >= 0, so an unclamped negative would drop the
        // whole bar and lose a real price to a bad auxiliary field.
        assertEquals(0L, FcsParser.parseCandles(root).single().volume)
    }

    @Test
    fun `an array body is accepted as well as a map`() {
        val root = json(
            """
            {"status":true,"code":200,"response":[
              {"o":1.0,"h":1.2,"l":0.9,"c":1.1,"v":10,"t":1748815200},
              {"o":1.1,"h":1.3,"l":1.0,"c":1.2,"v":11,"t":1748818800}
            ]}
            """.trimIndent()
        )
        val candles = FcsParser.parseCandles(root)
        assertEquals(2, candles.size)
        assertEquals(Instant.ofEpochSecond(1748815200), candles.first().time)
    }

    @Test
    fun `a body without the envelope is still parsed`() {
        val root = json(
            """
            {"1748815200":{"o":1.0,"h":1.2,"l":0.9,"c":1.1,"t":1748815200}}
            """.trimIndent()
        )
        assertEquals(1, FcsParser.parseCandles(root).size)
    }

    @Test
    fun `millisecond timestamps are recognised and not read as the far future`() {
        val root = json(
            """
            {"status":true,"code":200,"response":{"1748815200000":{"o":1.0,"h":1.2,"l":0.9,"c":1.1,"t":1748815200000}}}
            """.trimIndent()
        )
        assertEquals(
            Instant.ofEpochSecond(1748815200),
            FcsParser.parseCandles(root).single().time
        )
    }

    @Test
    fun `the human readable time is used when no unix time is present`() {
        val root = json(
            """
            {"status":true,"code":200,"response":[
              {"o":1.0,"h":1.2,"l":0.9,"c":1.1,"tm":"2025-06-01 22:00:00"}
            ]}
            """.trimIndent()
        )
        assertEquals(
            Instant.parse("2025-06-01T22:00:00Z"),
            FcsParser.parseCandles(root).single().time
        )
    }

    @Test
    fun `the older v3 date plus time spelling is still understood`() {
        val root = json(
            """
            {"status":true,"code":200,"response":[
              {"s":"EUR/USD","d":"2024-03-15","t":"16:00:00","o":1.0,"h":1.2,"l":0.9,"c":1.1,"v":7}
            ]}
            """.trimIndent()
        )
        assertEquals(
            Instant.parse("2024-03-15T16:00:00Z"),
            FcsParser.parseCandles(root).single().time
        )
    }

    @Test
    fun `the map key dates a bar that carries no time field`() {
        val root = json(
            """
            {"status":true,"code":200,"response":{"1748815200":{"o":1.0,"h":1.2,"l":0.9,"c":1.1}}}
            """.trimIndent()
        )
        assertEquals(
            Instant.ofEpochSecond(1748815200),
            FcsParser.parseCandles(root).single().time
        )
    }

    @Test
    fun `one corrupt bar does not discard the series`() {
        val root = json(
            """
            {"status":true,"code":200,"response":[
              {"o":1.0,"h":1.2,"l":0.9,"c":1.1,"t":1748815200},
              {"o":1.0,"h":0.8,"l":0.9,"c":1.1,"t":1748818800},
              {"o":"junk","h":1.2,"l":0.9,"c":1.1,"t":1748822400}
            ]}
            """.trimIndent()
        )
        // Bar two has a high below its low; bar three has a non-numeric open.
        assertEquals(1, FcsParser.parseCandles(root).size)
    }

    @Test
    fun `duplicate timestamps collapse to one bar`() {
        val root = json(
            """
            {"status":true,"code":200,"response":[
              {"o":1.0,"h":1.2,"l":0.9,"c":1.1,"t":1748815200},
              {"o":1.0,"h":1.2,"l":0.9,"c":1.1,"t":1748815200}
            ]}
            """.trimIndent()
        )
        assertEquals(1, FcsParser.parseCandles(root).size)
    }

    // ---------- refusals ----------

    @Test
    fun `an invalid access key on HTTP 200 becomes Unauthorized`() {
        // WHY this case matters most: the provider answers 200 and puts the real outcome
        // in the body, so an HTTP-status-only check reports success and blanks the chart.
        val root = json("""{"status":false,"code":403,"msg":"Invalid access key","response":[]}""")

        assertEquals(AppError.Unauthorized, FcsParser.refusal(root))
        assertThrowsMarketData(AppError.Unauthorized) { FcsParser.parseCandles(root) }
    }

    @Test
    fun `a rate limit becomes RateLimited`() {
        val root = json("""{"status":false,"code":429,"msg":"Rate limit exceeded","response":[]}""")
        assertEquals(AppError.RateLimited, FcsParser.refusal(root))
    }

    @Test
    fun `an exhausted credit allowance becomes RateLimited`() {
        // The free plan is 500 credits a month; the provider words this as a credit
        // problem rather than a 429.
        val root = json("""{"status":false,"code":402,"msg":"Your monthly credit limit has been reached","response":[]}""")
        assertEquals(AppError.RateLimited, FcsParser.refusal(root))
    }

    @Test
    fun `an unknown symbol becomes Unsupported so the gateway can fall back`() {
        val root = json("""{"status":false,"code":404,"msg":"Symbol not found","response":[]}""")
        assertEquals(AppError.Unsupported, FcsParser.refusal(root))
    }

    @Test
    fun `a server error becomes ProviderRejected with the provider text`() {
        val root = json("""{"status":false,"code":500,"msg":"Internal upstream failure","response":[]}""")

        val error = FcsParser.refusal(root)
        assertTrue("expected ProviderRejected, got $error", error is AppError.ProviderRejected)
        assertTrue((error as AppError.ProviderRejected).reason.contains("Internal upstream failure"))
    }

    @Test
    fun `a refusal with no message still produces a classified error`() {
        val root = json("""{"status":false,"code":503}""")

        val error = FcsParser.refusal(root)
        assertNotNull(error)
        assertTrue(error is AppError.ProviderRejected)
        assertTrue((error as AppError.ProviderRejected).reason.contains("503"))
    }

    @Test
    fun `a refusal message never carries a configured key`() {
        // WHY code 400 rather than 403: a 403 is classified as Unauthorized before the
        // message is ever quoted, and this test is about what reaches the user's screen.
        val root = json("""{"status":false,"code":400,"msg":"Rejected access_key=FCS_KEY_ABC123 for this plan"}""")

        val error = FcsParser.refusal(root, listOf("FCS_KEY_ABC123"))
        assertTrue(error is AppError.ProviderRejected)
        val reason = (error as AppError.ProviderRejected).reason
        assertTrue("the key leaked into the user-facing reason: $reason", !reason.contains("FCS_KEY_ABC123"))
    }

    @Test
    fun `a successful envelope is not a refusal`() {
        val root = json(
            """
            {"status":true,"code":200,"msg":"Operation is successful","response":{"1748815200":{"o":1.0,"h":1.2,"l":0.9,"c":1.1,"t":1748815200}}}
            """.trimIndent()
        )
        assertNull(FcsParser.refusal(root))
    }

    @Test
    fun `a null body classifies as a network failure`() {
        assertEquals(AppError.Network, FcsParser.refusal(null))
    }

    @Test
    fun `an empty series on a successful envelope is Unsupported, not an empty chart`() {
        val root = json("""{"status":true,"code":200,"msg":"Operation is successful","response":{}}""")
        // WHY: "no bars for this symbol and period" is a coverage answer, and the gateway
        // falls back on it. Returning an empty list would be cached as a real result.
        assertThrowsMarketData(AppError.Unsupported) { FcsParser.parseCandles(root) }
    }

    // ---------- period table ----------

    @Test
    fun `every timeframe this app offers maps to a documented FCS period`() {
        // The provider's ladder is 1m, 5m, 15m, 30m, 1h, 2h, 4h, 5h, 1d, 1w, 1M, which
        // covers all seven of the app's timeframes - unlike Alpha Vantage's FX feed.
        val expected = mapOf(
            "M1" to "1m",
            "M5" to "5m",
            "M15" to "15m",
            "H1" to "1h",
            "H4" to "4h",
            "D1" to "1d",
            "W1" to "1w"
        )
        assertEquals(Timeframe.ORDERED, expected.keys.toList())
        Timeframe.ORDERED.forEach { value ->
            assertEquals(value, expected.getValue(value), FcsClient.Periods.intervalFor(Timeframe(value)))
        }
    }

    @Test
    fun `the free plan ceiling is below the app default request size`() {
        // WHY this is asserted: the docs say a `length` above the plan limit returns an
        // informative *error*, not a truncated series. The app's own default is 500, so
        // without the clamp every first load on a free key would fail.
        assertTrue(
            "the free ceiling must be below the repository default of 500",
            FcsApi.FREE_PLAN_MAX_CANDLES < 500
        )
        assertEquals(300, FcsApi.FREE_PLAN_MAX_CANDLES)
    }

    private fun assertThrowsMarketData(expected: AppError, block: () -> Unit) {
        try {
            block()
            fail("expected a MarketDataException carrying $expected")
        } catch (e: MarketDataException) {
            if (expected is AppError.ProviderRejected) {
                assertTrue("expected a ProviderRejected, got ${e.error}", e.error is AppError.ProviderRejected)
            } else {
                assertEquals(expected, e.error)
            }
        }
    }
}
