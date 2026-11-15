package com.devcode940.ForexAnalyst.data.provider.alphavantage

import com.devcode940.ForexAnalyst.data.model.AppError
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.MarketDataException
import com.devcode940.ForexAnalyst.data.model.MarketDataProvider
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.provider.InstrumentSplit
import com.devcode940.ForexAnalyst.data.provider.MarketDataClient
import com.devcode940.ForexAnalyst.data.provider.ProviderMessageSanitizer
import com.devcode940.ForexAnalyst.data.provider.TimeframeMapper
import com.google.gson.JsonSyntaxException
import java.io.IOException
import java.time.Clock

/**
 * [MarketDataClient] backed by alphavantage.co's FX endpoints.
 *
 * WHY two functions instead of one: `FX_INTRADAY` covers minute-to-hour bars and
 * `FX_DAILY` covers daily bars, and there is no weekly endpoint at all. Pretending
 * otherwise (requesting `interval=4h`, for instance) returns a 200 with an error
 * body rather than an obvious 400, so the capability table is explicit and
 * [supports] answers "no" before a request is spent.
 *
 * @property apiKey read per call, not captured at construction. WHY: the key lives
 *   in encrypted preferences and the user can replace it in Settings without
 *   restarting; a constructor-injected key would keep serving the old one until
 *   the process dies, and the failure mode is "I pasted the new key and nothing
 *   changed".
 * @property knownSecretsForRedaction the keys to scrub from any provider message
 *   quoted back to the user.
 */
class AlphaVantageClient(
    private val api: AlphaVantageApi,
    private val apiKey: () -> String,
    private val knownSecretsForRedaction: () -> Collection<String> = { emptyList() }
) : MarketDataClient {

    override val provider: MarketDataProvider = MarketDataProvider.ALPHA_VANTAGE

    override fun supports(pair: PairSymbol, timeframe: Timeframe): Boolean =
        InstrumentSplit.of(pair) != null && Intervals.functionFor(timeframe) != null

    override suspend fun fetchCandles(
        pair: PairSymbol,
        timeframe: Timeframe,
        limit: Int,
        clock: Clock
    ): List<CandleStick> {
        val split = InstrumentSplit.of(pair)
            ?: throw MarketDataException(AppError.Unsupported)
        val function = Intervals.functionFor(timeframe)
            ?: throw MarketDataException(AppError.Unsupported)

        val key = apiKey()
        if (key.isBlank()) {
            // WHY not Unauthorized: nothing was rejected yet, the app simply has no
            // credential. The gateway uses this distinction to decide between
            // "switch provider" and "check your key".
            throw MarketDataException(AppError.ProviderRejected("no Alpha Vantage API key configured"))
        }

        val safeLimit = limit.coerceIn(1, MAX_BARS_RETURNED)
        val response = try {
            api.query(
                function = function,
                fromSymbol = split.base,
                toSymbol = split.quote,
                interval = Intervals.intervalFor(timeframe),
                outputSize = Intervals.outputSizeFor(timeframe, safeLimit),
                apiKey = key
            )
        } catch (e: IOException) {
            // WHY rethrow as Network: Result.of would classify it the same way, but
            // the gateway has to distinguish "no connectivity" (retry soon) from
            // "quota gone" (retry tomorrow) before it decides to fall back.
            throw MarketDataException(AppError.Network, e)
        } catch (e: JsonSyntaxException) {
            throw MarketDataException(
                AppError.ProviderRejected("Alpha Vantage returned a body that is not JSON"),
                e
            )
        }

        val body = response.body()
        if (body == null) {
            throw MarketDataException(classifyStatus(response.code(), response.errorBody()?.let { runCatching { it.string() }.getOrNull() }))
        }

        // WHY the body is checked for refusals before the status: Alpha Vantage
        // answers 200 with `{"Information": "...25 requests per day..."}` once the
        // quota is gone, so a status-only check reports success for an empty chart.
        AlphaVantageParser.refusal(body, knownSecretsForRedaction())?.let {
            throw MarketDataException(it)
        }

        val candles = AlphaVantageParser.parseCandles(body, knownSecretsForRedaction())
        // The provider has no `limit` parameter; trim to the newest bars the caller
        // asked for so the chart, the indicators and the cache all see one size.
        return if (candles.size > safeLimit) candles.takeLast(safeLimit) else candles
    }

    private fun classifyStatus(code: Int, errorBody: String?): AppError = when (code) {
        401, 403 -> AppError.Unauthorized
        429 -> AppError.RateLimited
        in 500..599 -> AppError.ProviderRejected("Alpha Vantage returned HTTP $code")
        else -> AppError.ProviderRejected(
            errorBody
                ?.takeIf { it.isNotBlank() }
                ?.let { ProviderMessageSanitizer.sanitize(it, knownSecretsForRedaction()) }
                ?: "Alpha Vantage returned HTTP $code"
        )
    }

    /**
     * Timeframe -> (function, interval) for this feed.
     *
     * Declared as an object so `supports()` and `fetchCandles()` cannot disagree
     * about what is available — a disagreement there shows up as a chart that
     * claims to work and then always falls back.
     */
    object Intervals : TimeframeMapper {

        /**
         * WHY M1 asks for `compact`: `full` means "the trailing 30 days", which for
         * one-minute bars is roughly 20,000 rows and a multi-megabyte JSON body the
         * phone has to parse — to keep at most [MAX_BARS_RETURNED] of them. Every
         * other timeframe stays on `full` because 30 days of those bars is small.
         */
        private const val COMPACT = "compact"
        private const val FULL = AlphaVantageApi.OUTPUT_SIZE_FULL

        override fun intervalFor(timeframe: Timeframe): String? = when (timeframe.value) {
            "M1" -> "1min"
            "M5" -> "5min"
            "M15" -> "15min"
            "H1" -> "60min"
            // FX_DAILY takes no interval parameter.
            "D1" -> null
            else -> null
        }

        /** The `function` value for [timeframe], or null when unsupported. */
        fun functionFor(timeframe: Timeframe): String? = when (timeframe.value) {
            "M1", "M5", "M15", "H1" -> AlphaVantageApi.FUNCTION_FX_INTRADAY
            "D1" -> AlphaVantageApi.FUNCTION_FX_DAILY
            // H4 and W1 have no Alpha Vantage FX endpoint; asking for one wastes a
            // quota unit to receive an error body.
            else -> null
        }

        fun outputSizeFor(timeframe: Timeframe, limit: Int): String = when {
            timeframe.value == "M1" -> COMPACT
            limit <= COMPACT_BAR_COUNT -> COMPACT
            else -> FULL
        }

        /** Number of bars `outputsize=compact` returns. */
        private const val COMPACT_BAR_COUNT = 100
    }

    companion object {
        /**
         * Hard ceiling on bars handed back from one request.
         *
         * WHY a ceiling: `MarketDataRepository.MAX_CANDLE_LIMIT` is 5,000, but this
         * feed's `full` responses are bounded by time rather than count. Capping at
         * the same number keeps the two layers consistent and makes the trim in
         * [fetchCandles] predictable instead of dependent on which interval was
         * requested.
         */
        private const val MAX_BARS_RETURNED = 5_000
    }
}
