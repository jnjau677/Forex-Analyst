package com.devcode940.ForexAnalyst.data.model

/**
 * Stable, machine-readable error taxonomy for every fallible operation.
 *
 * WHY: A sealed hierarchy (not exceptions, not error Strings) forces callers
 * to handle every case at compile time, and — critically — decouples the
 * message shown to a human from the exception that caused it. Internal
 * exception text (SQL, stack traces, file paths) must never reach the UI.
 */
sealed class AppError(
    /** Stable code for logs/telemetry — safe to expose. */
    val code: String,
    /** Actionable, human-readable message — safe to show in the UI. */
    val userMessage: String
) {
    /** WHY: No network / upstream market-data provider unreachable. */
    object Network : AppError(
        code = "NETWORK_UNAVAILABLE",
        userMessage = "Couldn't reach the market data service. Check your connection and try again."
    )

    /** WHY: Local persistence failed (disk full, corrupt DB, migration). */
    object Storage : AppError(
        code = "STORAGE_FAILURE",
        userMessage = "Couldn't save this locally. Free up some space and try again."
    )

    /** Caller supplied data that violates a domain invariant. */
    data class Validation(val field: String, val reason: String) : AppError(
        code = "VALIDATION_FAILED",
        userMessage = "Check $field: $reason"
    )

    /** Requested entity does not exist. */
    data class NotFound(val what: String) : AppError(
        code = "NOT_FOUND",
        userMessage = "$what was not found. It may have been deleted."
    )

    /** Operation conflicts with existing state (duplicate, race). */
    data class Conflict(val what: String) : AppError(
        code = "CONFLICT",
        userMessage = "$what already exists."
    )

    /**
     * The market-data provider rejected the credential (HTTP 401/403).
     *
     * WHY its own case: "check your connection" sends the user to the wrong screen. A
     * rejected key has exactly one fix, and it lives in Settings.
     */
    object Unauthorized : AppError(
        code = "PROVIDER_UNAUTHORIZED",
        userMessage = "The market data provider rejected your API key. Check it in Settings."
    )

    /**
     * The provider's request quota is exhausted (HTTP 429, or a 200 carrying its
     * rate-limit note — Alpha Vantage answers 200 with a `"Note"` body).
     */
    object RateLimited : AppError(
        code = "RATE_LIMITED",
        userMessage = "The market data provider's daily limit is used up. Try again later, or switch provider in Settings."
    )

    /**
     * The selected provider does not serve this instrument or timeframe.
     *
     * WHY an error and not a silent fallback: the alternative is a chart of invented
     * prices with nothing to explain them. The caller falls back *and* reports why.
     */
    object Unsupported : AppError(
        code = "PROVIDER_UNSUPPORTED",
        userMessage = "That market or timeframe is not covered by the selected data provider."
    )

    /**
     * The provider answered with a refusal this app does not classify further.
     *
     * WHY [reason] is safe to render: it is the provider's own short message, truncated
     * and passed through `redactSecrets` before it reaches here, so neither the API key
     * nor a full response body can leak into the UI or a bug report.
     */
    data class ProviderRejected(val reason: String) : AppError(
        code = "PROVIDER_REJECTED",
        userMessage = "The data provider refused the request: $reason"
    )

    /**
     * Catch-all. WHY: [cause] is @Transient-style internal-only: it is logged,
     * never rendered. The user sees a generic, actionable message.
     */
    data class Unknown internal constructor(val cause: Throwable?) : AppError(
        code = "INTERNAL_ERROR",
        userMessage = "Something went wrong. Please try again."
    ) {
        companion object {
            /** WHY: Named factory hides the constructor so callers cannot invent messages. */
            fun of(cause: Throwable?): Unknown = Unknown(cause)
        }
    }
}

/**
 * A market-data provider failure that has already been classified.
 *
 * WHY an exception carrying an [AppError] rather than a `Result` return type:
 * provider clients parse JSON, read HTTP statuses and hit the network, all of
 * which fail with exceptions anyway. Forcing every one of them to build a
 * `Result.Failure` at each `throw` site is the same boilerplate that lets one
 * site forget — and the one that forgets leaks a stack trace into the UI.
 * Instead clients throw this, and [Result.of] unwraps it, so classification
 * still happens in exactly one place.
 *
 * WHY it lives in `data.model` and not `data.provider`: [Result.of] has to
 * catch it, and the model layer must not depend on the network layer.
 */
class MarketDataException(
    val error: AppError,
    cause: Throwable? = null
) : Exception(error.code, cause) {
    init {
        // WHY: `AppError.Unknown` already carries its own cause. Nesting it here
        // too would produce a two-deep wrapper for one failure and make logcat
        // harder to read.
        require(error !is AppError.Unknown) {
            "Use AppError.Unknown.of(cause) via Result.of instead of MarketDataException"
        }
    }
}

/**
 * Explicit success/failure envelope for repository operations.
 *
 * WHY: Kotlin's built-in [kotlin.Result] cannot be used as a public API
 * return type on inline-class-restricted paths, carries no domain error, and
 * encourages `getOrThrow()` — which re-exposes internal exceptions. This
 * sealed interface makes failure a value with a *safe* message attached.
 */
sealed interface Result<out T> {

    /** Operation succeeded with [data]. */
    data class Success<out T>(val data: T) : Result<T>

    /** Operation failed with a classified [error]; [cause] is log-only. */
    data class Failure(val error: AppError) : Result<Nothing>

    companion object {
        /**
         * Runs [block], classifying any exception into an [AppError].
         *
         * WHY: One single place translates Throwable -> AppError, so no call
         * site can accidentally leak exception text to the UI. Declared
         * suspend (not inline) so the block may call Room/DAO suspend
         * functions directly.
         */
        suspend fun <T> of(block: suspend () -> T): Result<T> = try {
            Success(block())
        } catch (e: MarketDataException) {
            // WHY first in the ladder: this one is already classified by the
            // provider layer. Re-classifying it as Unknown would throw away
            // the distinction between "your key is wrong" and "we crashed".
            Failure(e.error)
        } catch (e: IllegalArgumentException) {
            // WHY: Domain invariant violated (require() in models) — this is
            // a validation problem, not an internal crash.
            Failure(AppError.Validation(field = "input", reason = e.message ?: "invalid value"))
        } catch (e: java.io.IOException) {
            Failure(AppError.Network)
        } catch (e: Exception) {
            // WHY: Exception, never Throwable — an OutOfMemoryError must
            // propagate, not be converted into a friendly message.
            Failure(AppError.Unknown.of(e))
        }
    }
}

/** Unwraps [Result.Success] or returns null. */
fun <T> Result<T>.getOrNull(): T? = when (this) {
    is Result.Success -> data
    is Result.Failure -> null
}

/** Returns [Result.Success] data or [default] on any failure. */
fun <T> Result<T>.getOrElse(default: T): T = when (this) {
    is Result.Success -> data
    is Result.Failure -> default
}

/** Applies [transform] to the success value, passing failures through. */
fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
    is Result.Success -> Result.Success(transform(data))
    is Result.Failure -> this
}

/**
 * Terminal fold: handles both cases so callers cannot forget the failure path.
 *
 * WHY: Exhaustive `when` on a sealed type is the compile-time guarantee that
 * every error is surfaced somewhere (log, snackbar, telemetry).
 */
inline fun <T, R> Result<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (AppError) -> R
): R = when (this) {
    is Result.Success -> onSuccess(data)
    is Result.Failure -> onFailure(error)
}
