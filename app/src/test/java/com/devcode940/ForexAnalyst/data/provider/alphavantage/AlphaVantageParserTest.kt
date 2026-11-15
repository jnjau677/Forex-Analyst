package com.devcode940.ForexAnalyst.data.provider.alphavantage

import com.devcode940.ForexAnalyst.data.model.AppError
import com.devcode940.ForexAnalyst.data.model.MarketDataException
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.provider.InstrumentSplit
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/**
 * Covers [AlphaVantageParser] and the client's capability table.
 *
 * WHY the fixtures are hand-written JSON rather than recorded responses: the
 * interesting cases are the ones a recording would not contain — a 200 carrying a
 * rate-limit note, a bar whose high is below its low, a series whose key embeds a
 * different interval. Those are exactly the shapes that break an integration in
 * production, and they have to be spelled out deliberately.
 */
class AlphaVantageParserTest {

    private fun json(body: String) = JsonParser.parseString(body).asJsonObject

    // ---------- successful series ----------

    @Test
    fun `an intraday series is parsed into ascending candles`() {
        val root = json(
            """
            {
              "Meta Data": {
                "1. Information": "FX (5min) intraday time series",
                "2. From Symbol": "EUR",
                "3. To Symbol": "USD",
                "5. Interval": "5min",
                "7. Time Zone": "UTC"
              },
              "Time Series FX (5min)": {
                "2024-03-15 16:05:00": {"1. open":"1.08510000","2. high":"1.08530000","3. low":"1.08490000","4. close":"1.08520000"},
                "2024-03-15 16:00:00": {"1. open":"1.08500000","2. high":"1.08520000","3. low":"1.08480000","4. close":"1.08510000"}
              }
            }
            """.trimIndent()
        )

        val candles = AlphaVantageParser.parseCandles(root)

        assertEquals(2, candles.size)
        // WHY ordering is asserted: the provider returns newest-first, and every
        // consumer of this list (chart, indicators, alert evaluator) assumes the
        // opposite. A provider that keeps its order must not reverse our history.
        assertEquals(Instant.parse("2024-03-15T16:00:00Z"), candles.first().time)
        assertEquals(1.08500, candles.first().open, 1e-9)
        assertEquals(1.08520, candles.first().high, 1e-9)
        assertEquals(1.08480, candles.first().low, 1e-9)
        assertEquals(1.08510, candles.first().close, 1e-9)
        assertEquals(Instant.parse("2024-03-15T16:05:00Z"), candles.last().time)
    }

    @Test
    fun `the series key is found by prefix, whatever interval it names`() {
        listOf(
            "Time Series FX (1min)",
            "Time Series FX (15min)",
            "Time Series FX (60min)",
            "Time Series FX (Daily)"
        ).forEach { key ->
            val root = json(
                """
                {
                  "$key": {
                    "2024-03-15 16:00:00": {"1. open":"1.0","2. high":"1.2","3. low":"0.9","4. close":"1.1"}
                  }
                }
                """.trimIndent()
            )
            assertEquals("series key '$key' was not recognised", 1, AlphaVantageParser.parseCandles(root).size)
        }
    }

    @Test
    fun `a daily series keys its bars by date alone`() {
        val root = json(
            """
            {
              "Meta Data": {"7. Time Zone": "UTC"},
              "Time Series FX (Daily)": {
                "2024-03-15": {"1. open":"1.0850","2. high":"1.0880","3. low":"1.0820","4. close":"1.0870"},
                "2024-03-14": {"1. open":"1.0800","2. high":"1.0860","3. low":"1.0790","4. close":"1.0850"}
              }
            }
            """.trimIndent()
        )

        val candles = AlphaVantageParser.parseCandles(root)

        assertEquals(2, candles.size)
        assertEquals(Instant.parse("2024-03-14T00:00:00Z"), candles.first().time)
        assertEquals(Instant.parse("2024-03-15T00:00:00Z"), candles.last().time)
    }

    @Test
    fun `the declared time zone shifts every timestamp`() {
        // WHY this matters: if the provider means US/Eastern and the parser assumes
        // UTC, every bar lands five hours late. Session shading and the VWAP daily
        // anchor would both be wrong, and the prices would still look plausible.
        val eastern = json(
            """
            {
              "Meta Data": {"7. Time Zone": "US/Eastern"},
              "Time Series FX (60min)": {
                "2024-03-15 12:00:00": {"1. open":"1.0","2. high":"1.2","3. low":"0.9","4. close":"1.1"}
              }
            }
            """.trimIndent()
        )

        assertEquals(
            Instant.parse("2024-03-15T16:00:00Z"),
            AlphaVantageParser.parseCandles(eastern).single().time
        )
    }

    @Test
    fun `an unknown or missing time zone falls back to UTC instead of crashing`() {
        val unknown = json(
            """
            {
              "Meta Data": {"7. Time Zone": "Mars/Olympus_Mons"},
              "Time Series FX (5min)": {
                "2024-03-15 16:00:00": {"1. open":"1.0","2. high":"1.2","3. low":"0.9","4. close":"1.1"}
              }
            }
            """.trimIndent()
        )
        // WHY the id rather than equality with ZoneId.of("UTC"): that call returns a
        // zone *region* named "UTC", which is not equal to ZoneOffset.UTC even though
        // both mean the same thing. Asserting the id keeps the test about behaviour.
        assertEquals("Z", AlphaVantageParser.zoneOf(unknown).id)
        assertEquals(
            Instant.parse("2024-03-15T16:00:00Z"),
            AlphaVantageParser.parseCandles(unknown).single().time
        )

        val absent = json(
            """
            {
              "Time Series FX (5min)": {
                "2024-03-15 16:00:00": {"1. open":"1.0","2. high":"1.2","3. low":"0.9","4. close":"1.1"}
              }
            }
            """.trimIndent()
        )
        assertEquals("Z", AlphaVantageParser.zoneOf(absent).id)
        assertEquals(
            Instant.parse("2024-03-15T16:00:00Z"),
            AlphaVantageParser.parseCandles(absent).single().time
        )
    }

    @Test
    fun `one corrupt bar does not discard the series`() {
        val root = json(
            """
            {
              "Time Series FX (5min)": {
                "2024-03-15 16:00:00": {"1. open":"1.0850","2. high":"1.0852","3. low":"1.0848","4. close":"1.0851"},
                "2024-03-15 16:05:00": {"1. open":"1.0851","2. high":"1.0840","3. low":"1.0860","4. close":"1.0852"},
                "2024-03-15 16:10:00": {"1. open":"not-a-number","2. high":"1.0870","3. low":"1.0850","4. close":"1.0860"}
              }
            }
            """.trimIndent()
        )

        val candles = AlphaVantageParser.parseCandles(root)

        // WHY exactly one survives: bar two has a high below its low, bar three has a
        // non-numeric open. Both violate CandleStick's invariants and must be dropped,
        // not repaired - a silently "fixed" bar invents a price.
        assertEquals(1, candles.size)
        assertEquals(Instant.parse("2024-03-15T16:00:00Z"), candles.single().time)
    }

    @Test
    fun `duplicate timestamps collapse to one bar`() {
        val root = json(
            """
            {
              "Time Series FX (5min)": {
                "2024-03-15 16:00:00": {"1. open":"1.0","2. high":"1.2","3. low":"0.9","4. close":"1.1"},
                "2024-03-15 16:00:00 ": {"1. open":"1.0","2. high":"1.2","3. low":"0.9","4. close":"1.1"}
              }
            }
            """.trimIndent()
        )
        assertEquals(1, AlphaVantageParser.parseCandles(root).size)
    }

    @Test
    fun `volume is zero because the FX endpoint publishes none`() {
        val root = json(
            """
            {
              "Time Series FX (5min)": {
                "2024-03-15 16:00:00": {"1. open":"1.0","2. high":"1.2","3. low":"0.9","4. close":"1.1"}
              }
            }
            """.trimIndent()
        )
        // WHY asserted rather than assumed: inventing a volume would feed a fabricated
        // number into the volume pane and into the VWAP weights.
        assertEquals(0L, AlphaVantageParser.parseCandles(root).single().volume)
    }

    // ---------- refusals ----------

    @Test
    fun `a daily rate-limit Information payload becomes RateLimited, not a parse failure`() {
        val root = json(
            """
            {
              "Information": "Thank you for using Alpha Vantage! Our standard API rate limit is 25 requests per day. Please subscribe for a premium API key."
            }
            """.trimIndent()
        )

        assertEquals(AppError.RateLimited, AlphaVantageParser.refusal(root))
        assertThrowsMarketData(AppError.RateLimited) { AlphaVantageParser.parseCandles(root) }
    }

    @Test
    fun `a per-minute Note payload becomes RateLimited`() {
        val root = json(
            """
            {
              "Note": "Thank you for using Alpha Vantage! Our standard API call frequency is 5 calls per minute and 500 calls per day."
            }
            """.trimIndent()
        )
        assertEquals(AppError.RateLimited, AlphaVantageParser.refusal(root))
    }

    @Test
    fun `an invalid key becomes Unauthorized`() {
        val root = json("""{"Error Message": "Invalid API KEY. Please retry or visit the documentation."}""")

        assertEquals(AppError.Unauthorized, AlphaVantageParser.refusal(root))
    }

    @Test
    fun `a generic invalid call becomes ProviderRejected with the provider text`() {
        val root = json("""{"Error Message": "Invalid API call. Please retry or visit the documentation."}""")

        val error = AlphaVantageParser.refusal(root)
        assertNotNull(error)
        assertTrue("expected ProviderRejected, got $error", error is AppError.ProviderRejected)
        assertTrue((error as AppError.ProviderRejected).reason.contains("Invalid API call"))
    }

    @Test
    fun `a refusal message never carries a configured key`() {
        val root = json("""{"Error Message": "Rejected apikey=AV_KEY_1234567890 for this endpoint"}""")

        val error = AlphaVantageParser.refusal(root, listOf("AV_KEY_1234567890"))
        assertTrue(error is AppError.ProviderRejected)
        val reason = (error as AppError.ProviderRejected).reason
        assertTrue("the key leaked into the user-facing reason: $reason", !reason.contains("AV_KEY_1234567890"))
    }

    @Test
    fun `a payload with a series is never treated as a refusal`() {
        // WHY: some responses carry an informational string alongside valid data.
        // Refusing those would blank a working chart to report an unactionable message.
        val root = json(
            """
            {
              "Note": "Thank you for using Alpha Vantage!",
              "Time Series FX (5min)": {
                "2024-03-15 16:00:00": {"1. open":"1.0","2. high":"1.2","3. low":"0.9","4. close":"1.1"}
              }
            }
            """.trimIndent()
        )

        assertNull(AlphaVantageParser.refusal(root))
        assertEquals(1, AlphaVantageParser.parseCandles(root).size)
    }

    @Test
    fun `an unrecognised payload shape is a classified refusal, not an empty chart`() {
        val root = json("""{"unexpected": "shape"}""")

        val error = AlphaVantageParser.refusal(root)
        assertTrue("expected ProviderRejected, got $error", error is AppError.ProviderRejected)
    }

    @Test
    fun `a null body classifies as a network failure`() {
        assertEquals(AppError.Network, AlphaVantageParser.refusal(null))
    }

    @Test
    fun `a series whose bars are all unusable is reported, not returned empty`() {
        val root = json(
            """
            {
              "Time Series FX (5min)": {
                "not-a-timestamp": {"1. open":"1.0","2. high":"1.2","3. low":"0.9","4. close":"1.1"}
              }
            }
            """.trimIndent()
        )
        // WHY: an empty list means "this market has no bars", which the gateway would
        // accept as a success and cache. A refusal has to be a refusal.
        assertThrowsMarketData(AppError.ProviderRejected("time series contained no usable bars")) {
            AlphaVantageParser.parseCandles(root)
        }
    }

    // ---------- capability table ----------

    @Test
    fun `every timeframe maps to a function or is explicitly unsupported`() {
        val expected = mapOf(
            "M1" to AlphaVantageApi.FUNCTION_FX_INTRADAY,
            "M5" to AlphaVantageApi.FUNCTION_FX_INTRADAY,
            "M15" to AlphaVantageApi.FUNCTION_FX_INTRADAY,
            "H1" to AlphaVantageApi.FUNCTION_FX_INTRADAY,
            "D1" to AlphaVantageApi.FUNCTION_FX_DAILY,
            "H4" to null,
            "W1" to null
        )
        Timeframe.ORDERED.forEach { value ->
            val timeframe = Timeframe(value)
            assertEquals(
                "$value mapped to the wrong function",
                expected.getValue(value),
                AlphaVantageClient.Intervals.functionFor(timeframe)
            )
        }
    }

    @Test
    fun `the daily function takes no interval and the intraday one always does`() {
        Timeframe.ORDERED.forEach { value ->
            val timeframe = Timeframe(value)
            val function = AlphaVantageClient.Intervals.functionFor(timeframe)
            val interval = AlphaVantageClient.Intervals.intervalFor(timeframe)
            when (function) {
                AlphaVantageApi.FUNCTION_FX_DAILY ->
                    assertNull("$value must not send an interval to FX_DAILY", interval)

                AlphaVantageApi.FUNCTION_FX_INTRADAY ->
                    assertNotNull("$value must send an interval to FX_INTRADAY", interval)

                else -> assertNull("$value is unsupported so it needs no interval", interval)
            }
        }
    }

    @Test
    fun `one-minute bars ask for the compact output size`() {
        // WHY: `full` means the trailing 30 days, which for one-minute bars is roughly
        // 20,000 rows and a multi-megabyte body parsed to keep at most a few hundred.
        assertEquals(
            "compact",
            AlphaVantageClient.Intervals.outputSizeFor(Timeframe("M1"), limit = 500)
        )
        assertEquals(
            AlphaVantageApi.OUTPUT_SIZE_FULL,
            AlphaVantageClient.Intervals.outputSizeFor(Timeframe("H1"), limit = 500)
        )
        assertEquals(
            "compact",
            AlphaVantageClient.Intervals.outputSizeFor(Timeframe("H1"), limit = 100)
        )
    }

    @Test
    fun `the client rejects indices and energy instruments before spending a request`() {
        // WHY: SPX500 and NATGAS are broker-invented tickers with no ISO code. Sending
        // one costs a quota unit to receive "unknown symbol".
        listOf("SPX500", "NAS100", "GER40", "US30", "NATGAS", "USOIL").forEach { symbol ->
            assertNull("$symbol should not be servable", InstrumentSplit.of(PairSymbol(symbol)))
        }
        listOf("EURUSD", "BTCUSD", "XAUUSD", "DOGEUSD", "MATICUSD").forEach { symbol ->
            assertNotNull("$symbol should be servable", InstrumentSplit.of(PairSymbol(symbol)))
        }
    }

    private fun assertThrowsMarketData(expected: AppError, block: () -> Unit) {
        try {
            block()
            fail("expected a MarketDataException carrying $expected")
        } catch (e: MarketDataException) {
            if (expected is AppError.ProviderRejected) {
                assertTrue(
                    "expected a ProviderRejected, got ${e.error}",
                    e.error is AppError.ProviderRejected
                )
            } else {
                assertEquals(expected, e.error)
            }
        }
    }
}
