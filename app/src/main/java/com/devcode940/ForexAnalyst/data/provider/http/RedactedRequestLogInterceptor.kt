package com.devcode940.ForexAnalyst.data.provider.http

import com.devcode940.ForexAnalyst.data.provider.ProviderMessageSanitizer
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Logs one line per market-data request, with the credential removed.
 *
 * WHY not OkHttp's `HttpLoggingInterceptor`: at BODY level it prints the full
 * request URL, and both feeds take the API key as a *query parameter*
 * (`apikey=`, `access_key=`). It has `redactHeader()` but no query redaction, so
 * the standard tool would write the user's key to logcat — where any app with
 * READ_LOGS, or a bug report the user attaches, can pick it up. This interceptor
 * is deliberately weaker (no bodies, no headers) and deliberately safer.
 *
 * WHY still worth logging at all: "prices stopped updating" is unreportable
 * without knowing which call returned which status. One scrubbed line per
 * request makes that answerable from a logcat capture.
 */
class RedactedRequestLogInterceptor(
    private val emit: (String) -> Unit
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedAt = System.nanoTime()
        val scrubbedUrl = ProviderMessageSanitizer.sanitize(request.url.toString())

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            emit("$scrubbedUrl -> failed (${e.javaClass.simpleName}) in ${elapsedMillis(startedAt)}ms")
            throw e
        }

        emit("$scrubbedUrl -> ${response.code} in ${elapsedMillis(startedAt)}ms")
        return response
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
}
