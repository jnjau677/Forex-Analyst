package com.devcode940.ForexAnalyst.data.provider.fcs

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
 * [MarketDataClient] backed by fcsapi.com v4.
 *
 * Unlike Alpha Vantage this feed covers all seven of the app's timeframes from a
 * single endpoint, publishes volume, and accepts the app's own symbol spelling —
 * which is why it wins the `AUTO` precedence when both keys are present.
 *
 * @property accessKey read per call, not captured at construction. WHY: see
 *   [com.devcode940.ForexAnalyst.data.provider.alphavantage.AlphaVantageClient] —
 *   a key replaced in Settings must take effect without a process restart.
 */
class FcsClient(
    private val api: FcsApi,
    private val accessKey: () -> String,
    private val knownSecretsForRedaction: () -> Collection<String> = { emptyList() }
) : MarketDataClient {

    override val provider: MarketDataProvider = MarketDataProvider.FCS_API

    override fun supports(pair: PairSymbol, timeframe: Timeframe): Boolean =
        // WHY route the capability check through InstrumentSplit even though the
        // provider takes the bare symbol: the split is what excludes indices and
        // energy, and those genuinely are not served by this feed. Checking only
        // "is the symbol in our catalog" would spend a credit to be told no.
        InstrumentSplit.of(pair) != null && Periods.intervalFor(timeframe) != null

    override suspend fun fetchCandles(
        pair: PairSymbol,
        timeframe: Timeframe,
        limit: Int,
        clock: Clock
    ): List<CandleStick> {
        if (InstrumentSplit.of(pair) == null) throw MarketDataException(AppError.Unsupported)
        val period = Periods.intervalFor(timeframe)
            ?: throw MarketDataException(AppError.Unsupported)

        val key = accessKey()
        if (key.isBlank()) {
            throw MarketDataException(AppError.ProviderRejected("no FCS API key configured"))
        }

        // WHY clamp to the free-plan ceiling: the docs state that a `length` above
        // the plan limit returns "an informative error", not a truncated series.
        // This app's own default request is 500 candles, so asking for it verbatim
        // on a free key would fail *every* load and silently fall back to the
        // simulator — the exact outcome the user cannot diagnose.
        val length = limit.coerceIn(1, FcsApi.FREE_PLAN_MAX_CANDLES)

        val response = try {
            api.history(
                symbol = pair.value,
                period = period,
                length = length,
                accessKey = key
            )
        } catch (e: IOException) {
            throw MarketDataException(AppError.Network, e)
        } catch (e: JsonSyntaxException) {
            throw MarketDataException(
                AppError.ProviderRejected("FCS API returned a body that is not JSON"),
                e
            )
        }

        val body = response.body()
        if (body == null) {
            val detail = runCatching { response.errorBody()?.string() }.getOrNull()
            throw MarketDataException(classifyStatus(response.code(), detail))
        }

        // The envelope carries the real outcome even on HTTP 200; see FcsParser.
        FcsParser.refusal(body, knownSecretsForRedaction())?.let { throw MarketDataException(it) }

        val candles = FcsParser.parseCandles(body, knownSecretsForRedaction())
        return if (candles.size > length) candles.takeLast(length) else candles
    }

    private fun classifyStatus(code: Int, errorBody: String?): AppError = when (code) {
        401, 403 -> AppError.Unauthorized
        429 -> AppError.RateLimited
        404 -> AppError.Unsupported
        else -> AppError.ProviderRejected(
            errorBody
                ?.takeIf { it.isNotBlank() }
                ?.let { ProviderMessageSanitizer.sanitize(it, knownSecretsForRedaction()) }
                ?: "FCS API returned HTTP $code"
        )
    }

    /**
     * Timeframe -> FCS `period` spelling.
     *
     * The provider's documented ladder is `1m, 5m, 15m, 30m, 1h, 2h, 4h, 5h, 1d,
     * 1w, 1M`, which happens to cover every timeframe this app offers — including
     * the H4 and W1 that Alpha Vantage cannot serve.
     */
    object Periods : TimeframeMapper {
        override fun intervalFor(timeframe: Timeframe): String? = when (timeframe.value) {
            "M1" -> "1m"
            "M5" -> "5m"
            "M15" -> "15m"
            "H1" -> "1h"
            "H4" -> "4h"
            "D1" -> "1d"
            "W1" -> "1w"
            // WHY an explicit else: Timeframe is a closed set today, but a new
            // member must produce "unsupported" rather than a request for a period
            // the provider has never heard of.
            else -> null
        }
    }
}
