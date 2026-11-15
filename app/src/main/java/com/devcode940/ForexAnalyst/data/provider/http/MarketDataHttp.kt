package com.devcode940.ForexAnalyst.data.provider.http

import android.util.Log
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds the HTTP stack the market-data feeds share.
 *
 * WHY one [OkHttpClient] for both feeds: each client owns a connection pool and a
 * dispatcher with its own thread pool. Two clients means two pools — more sockets
 * and more threads on a phone, for no benefit. Sharing one is OkHttp's documented
 * recommendation.
 *
 * WHY explicit timeouts: OkHttp's defaults are 10 s per stage, so a stalled DNS
 * lookup plus a stalled read can pin a coroutine for 30 s. A chart refresh that
 * takes half a minute looks like a hang, and the user force-closes an app that was
 * about to succeed. These values are short enough to feel responsive and long
 * enough for a cold TLS handshake on a slow mobile connection.
 */
object MarketDataHttp {

    private const val TAG = "MarketDataHttp"
    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 20L
    private const val WRITE_TIMEOUT_SECONDS = 10L

    /**
     * @param logRequests when true, one scrubbed line per request is written to
     *   logcat. WHY a parameter rather than `BuildConfig.DEBUG` read here: this
     *   class stays JVM-testable and the caller (which does know the build type)
     *   decides. The default is `false` so a forgotten argument fails safe —
     *   silent, not leaky.
     */
    fun okHttpClient(logRequests: Boolean = false): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (logRequests) {
            builder.addInterceptor(RedactedRequestLogInterceptor { line -> Log.d(TAG, line) })
        }
        return builder.build()
    }

    /**
     * Creates a Retrofit service for [baseUrl].
     *
     * WHY [GsonConverterFactory] and not Moshi/kotlinx.serialization: the app
     * already depends on Gson (Room converters and chart-config persistence use
     * it), so a second JSON library would only add APK size and a second set of
     * keep rules. The providers parse into `JsonObject` rather than annotated
     * DTOs anyway — see `AlphaVantageParser` for why.
     */
    fun <T> service(baseUrl: String, client: OkHttpClient, api: Class<T>): T =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(api)
}
