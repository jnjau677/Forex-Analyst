package com.devcode940.ForexAnalyst.data.provider.fcs

import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * fcsapi.com v4 — the forex history endpoint.
 *
 * Verified against the provider's own documentation
 * (`https://fcsapi.com/document/forex-api/#history`):
 *
 * ```
 * GET https://fcsapi.com/api-v4/forex/history
 *       ?symbol=EURUSD&period=1h&length=300&access_key=KEY
 * ```
 *
 * WHY one endpoint for FX, metals *and* crypto: the docs state "All symbols work
 * with History API but 1 symbol in single history API request" and "100 % symbol
 * support from symbol list", and the same API exposes a `type` filter whose valid
 * values include `crypto` and `commodity`. Routing crypto to a guessed second path
 * would produce a "symbol not found" refusal that looks exactly like a bad key.
 *
 * WHY the symbol is passed through in this app's own spelling (`EURUSD`, not
 * `EUR/USD`): the docs list `EURUSD` as the standard format and `EUR/USD` as an
 * accepted alternative. Using the native form means no query-string encoding
 * surprises — Retrofit percent-encodes `/` inside a `@Query` value by default.
 *
 * WHY the response is [JsonObject] rather than an annotated DTO: the documented
 * payload is a *map keyed by unix timestamp* (`{"1748815200": {"o":…,"t":…}}`),
 * so the key set is data, not schema. A DTO cannot express that, and `is_chart=1`
 * would change it into a positional array. `FcsParser` accepts the map, an array
 * and the `status/code/msg/response` envelope around either.
 */
interface FcsApi {

    @GET("api-v4/forex/history")
    suspend fun history(
        @Query("symbol") symbol: String,
        @Query("period") period: String,
        @Query("length") length: Int,
        @Query("access_key") accessKey: String
    ): Response<JsonObject>

    companion object {
        /** Base URL; the trailing slash is required by Retrofit's relative-path resolution. */
        const val BASE_URL = "https://fcsapi.com/"

        /**
         * Longest series a free key may request.
         *
         * WHY a clamp rather than asking for more: the docs say "Free: up to 300
         * candles" and "Requests that exceed your plan limits will return an
         * informative error". So `length=500` on a free key does not return 300
         * bars — it returns *nothing*. This app's own default request is 500, so
         * without the clamp every first chart load on a free key would fail and
         * silently fall back to simulated data.
         */
        const val FREE_PLAN_MAX_CANDLES = 300

        /** Hard ceiling the provider accepts on any plan (`length` is 1-10000). */
        const val ABSOLUTE_MAX_CANDLES = 10_000
    }
}
