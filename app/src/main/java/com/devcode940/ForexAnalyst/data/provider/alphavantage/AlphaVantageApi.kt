package com.devcode940.ForexAnalyst.data.provider.alphavantage

import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Alpha Vantage's single `query` endpoint.
 *
 * WHY one method for both `FX_INTRADAY` and `FX_DAILY`: the two differ only in the
 * `function` value and whether `interval` is present. Retrofit drops null query
 * parameters, so one signature covers both without building URLs by hand.
 *
 * WHY the response is [JsonObject] and not an annotated DTO: the payload's two
 * important keys are *dynamic*. The series arrives under `"Time Series FX (5min)"`
 * — the interval is part of the key name — and each bar is keyed by its own
 * timestamp, so the shape is a map-of-maps whose keys change with the request. A
 * DTO would need a different class per interval, and `@SerializedName` cannot
 * express "whatever key starts with `Time Series`". Parsing the tree keeps one
 * code path for every interval this app supports.
 *
 * WHY `apikey` is a query parameter: that is the only form the provider accepts.
 * It is the reason `RedactedRequestLogInterceptor` exists.
 */
interface AlphaVantageApi {

    @GET("query")
    suspend fun query(
        @Query("function") function: String,
        @Query("from_symbol") fromSymbol: String,
        @Query("to_symbol") toSymbol: String,
        @Query("interval") interval: String? = null,
        @Query("outputsize") outputSize: String? = null,
        @Query("apikey") apiKey: String
    ): Response<JsonObject>

    companion object {
        /** Base URL; trailing slash is required by Retrofit's relative-path resolution. */
        const val BASE_URL = "https://www.alphavantage.co/"

        const val FUNCTION_FX_INTRADAY = "FX_INTRADAY"
        const val FUNCTION_FX_DAILY = "FX_DAILY"

        /** `compact` returns the latest 100 points; `full` returns ~30 days. */
        const val OUTPUT_SIZE_FULL = "full"
    }
}
