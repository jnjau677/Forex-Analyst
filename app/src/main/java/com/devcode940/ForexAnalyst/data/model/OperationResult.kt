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
