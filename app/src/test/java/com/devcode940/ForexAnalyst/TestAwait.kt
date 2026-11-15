package com.devcode940.ForexAnalyst

import org.junit.Assert.assertTrue

/**
 * Waits, in real time, for a condition the coroutine test scheduler cannot drive.
 *
 * WHY this exists: `viewModelScope` runs on `Dispatchers.Main`, which the tests replace
 * with an unconfined test dispatcher, so everything the ViewModel does *before* its first
 * suspension happens eagerly and synchronously. The candle load, however, hands its
 * indicator math to `Dispatchers.Default` — a real thread pool that `runTest` does not
 * own and does not wait for (the load is not a child of the test scope). Without a
 * barrier, every assertion after a load races a background thread: the suite would pass
 * on a fast machine and fail intermittently on CI, which is the worst kind of test.
 *
 * WHY polling rather than `runBlocking` or a latch inside the ViewModel: the production
 * dispatcher choice is correct (CPU-bound work off the main thread) and must not be
 * bent to suit a test. Polling a `StateFlow` from the test thread needs no hook in the
 * code under test.
 *
 * @param description what was being waited for, so a timeout names the stuck step.
 * @param timeoutMs upper bound; the default is far above the work involved (a few
 *   indicator passes over a few hundred candles) and far below a CI job's patience.
 */
internal fun awaitSettled(
    description: String,
    timeoutMs: Long = 5_000,
    condition: () -> Boolean
) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < deadline) {
        if (condition()) return
        Thread.sleep(POLL_INTERVAL_MS)
    }
    // WHY re-check at the end: the failure message must report the final state, and a
    // condition that became true during the last sleep must not fail the test.
    assertTrue("Timed out after ${timeoutMs}ms waiting for $description", condition())
}

private const val POLL_INTERVAL_MS = 5L
