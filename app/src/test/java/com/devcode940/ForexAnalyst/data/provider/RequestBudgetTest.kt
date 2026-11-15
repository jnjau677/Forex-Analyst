package com.devcode940.ForexAnalyst.data.provider

import com.devcode940.ForexAnalyst.MutableClock
import com.devcode940.ForexAnalyst.data.model.MarketDataProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/**
 * Covers [RequestBudget]'s sliding windows, refunds and persistence.
 *
 * WHY this suite matters more than its size suggests: the budget is the only thing
 * standing between a five-minute polling loop and a free-tier key that is exhausted for
 * the rest of the month. A bug here does not crash anything — it silently costs the user
 * their quota, and the symptom ("prices stopped updating") arrives days later.
 */
class RequestBudgetTest {

    private val clock = MutableClock()

    private val minuteWindow = RequestBudget.Window(
        span = Duration.ofMinutes(1),
        maxRequests = 3,
        displayName = "minute"
    )
    private val dayWindow = RequestBudget.Window(
        span = Duration.ofDays(1),
        maxRequests = 5,
        displayName = "day"
    )
    private val limits = mapOf(
        MarketDataProvider.FCS_API to listOf(minuteWindow, dayWindow)
    )

    private fun budget(store: RequestBudget.BudgetStore? = null) =
        RequestBudget(limits = limits, clock = clock, store = store)

    @Test
    fun `grants requests up to the window ceiling`() {
        val budget = budget()
        repeat(3) {
            assertSame(
                "request ${it + 1} of 3 should be granted",
                RequestBudget.Decision.Granted,
                budget.tryAcquire(MarketDataProvider.FCS_API)
            )
        }
    }

    @Test
    fun `denies the request after the narrowest window is full`() {
        val budget = budget()
        repeat(3) { budget.tryAcquire(MarketDataProvider.FCS_API) }

        val decision = budget.tryAcquire(MarketDataProvider.FCS_API)
        assertTrue("expected a denial, got $decision", decision is RequestBudget.Decision.Denied)
        decision as RequestBudget.Decision.Denied
        assertSame(minuteWindow, decision.window)
    }

    @Test
    fun `the narrow window opens again once its oldest charge ages out`() {
        val budget = budget()
        repeat(3) { budget.tryAcquire(MarketDataProvider.FCS_API) }
        assertTrue(budget.tryAcquire(MarketDataProvider.FCS_API) is RequestBudget.Decision.Denied)

        // One second short of a full minute is still inside the window.
        clock.advanceSeconds(59)
        assertTrue(budget.tryAcquire(MarketDataProvider.FCS_API) is RequestBudget.Decision.Denied)

        clock.advanceSeconds(2)
        assertSame(RequestBudget.Decision.Granted, budget.tryAcquire(MarketDataProvider.FCS_API))
    }

    @Test
    fun `the wider window still binds after the narrow one resets`() {
        val budget = budget()
        // Two bursts of three, each a minute apart: the minute window is satisfied at
        // the start of the second burst, but the daily ceiling of five is not.
        repeat(3) { budget.tryAcquire(MarketDataProvider.FCS_API) }
        clock.advanceMinutes(2)
        repeat(2) { budget.tryAcquire(MarketDataProvider.FCS_API) }

        val decision = budget.tryAcquire(MarketDataProvider.FCS_API)
        assertTrue("expected a denial, got $decision", decision is RequestBudget.Decision.Denied)
        decision as RequestBudget.Decision.Denied
        assertSame(dayWindow, decision.window)
    }

    @Test
    fun `denied requests are not charged`() {
        val budget = budget()
        repeat(3) { budget.tryAcquire(MarketDataProvider.FCS_API) }
        repeat(5) { budget.tryAcquire(MarketDataProvider.FCS_API) }

        assertEquals(
            "a denial must not consume quota",
            3,
            budget.usedIn(MarketDataProvider.FCS_API, minuteWindow)
        )
    }

    @Test
    fun `a refund gives the charge back so the next request is granted`() {
        val budget = budget()
        repeat(3) { budget.tryAcquire(MarketDataProvider.FCS_API) }
        assertTrue(budget.tryAcquire(MarketDataProvider.FCS_API) is RequestBudget.Decision.Denied)

        budget.refund(MarketDataProvider.FCS_API)

        assertSame(RequestBudget.Decision.Granted, budget.tryAcquire(MarketDataProvider.FCS_API))
    }

    @Test
    fun `refunding more than was spent cannot drive usage negative`() {
        val budget = budget()
        budget.tryAcquire(MarketDataProvider.FCS_API)
        repeat(5) { budget.refund(MarketDataProvider.FCS_API) }

        assertEquals(0, budget.usedIn(MarketDataProvider.FCS_API, minuteWindow))
        // A negative counter would grant an extra three requests, i.e. a real overage.
        repeat(3) {
            assertSame(RequestBudget.Decision.Granted, budget.tryAcquire(MarketDataProvider.FCS_API))
        }
        assertTrue(budget.tryAcquire(MarketDataProvider.FCS_API) is RequestBudget.Decision.Denied)
    }

    @Test
    fun `a provider with no configured limit is unlimited`() {
        val budget = budget()
        // The simulator has no quota; a missing entry must not deny it.
        repeat(50) {
            assertSame(RequestBudget.Decision.Granted, budget.tryAcquire(MarketDataProvider.SIMULATED))
        }
    }

    @Test
    fun `usage survives a restart through the store`() {
        val store = RecordingBudgetStore()
        val first = budget(store)
        repeat(3) { first.tryAcquire(MarketDataProvider.FCS_API) }
        assertTrue(
            "the store must have been written",
            store.saved[MarketDataProvider.FCS_API]?.isNotEmpty() == true
        )

        // A second budget over the same store and clock is what a restarted process sees.
        val second = budget(store)
        val decision = second.tryAcquire(MarketDataProvider.FCS_API)
        assertTrue(
            "a restart must not reset a monthly quota, got $decision",
            decision is RequestBudget.Decision.Denied
        )
    }

    @Test
    fun `charges older than the widest window are dropped on load`() {
        val store = RecordingBudgetStore()
        store.saved[MarketDataProvider.FCS_API] = listOf(clock.instant().minus(Duration.ofDays(3)))

        val budget = budget(store)
        assertSame(RequestBudget.Decision.Granted, budget.tryAcquire(MarketDataProvider.FCS_API))
        assertEquals(
            "an aged-out charge must not still be counted",
            1,
            budget.usedIn(MarketDataProvider.FCS_API, dayWindow)
        )
    }

    @Test
    fun `reset clears every provider`() {
        val store = RecordingBudgetStore()
        val budget = budget(store)
        repeat(3) { budget.tryAcquire(MarketDataProvider.FCS_API) }

        budget.reset()

        assertEquals(0, budget.usedIn(MarketDataProvider.FCS_API, minuteWindow))
        assertTrue(store.saved[MarketDataProvider.FCS_API]?.isEmpty() == true)
        assertSame(RequestBudget.Decision.Granted, budget.tryAcquire(MarketDataProvider.FCS_API))
    }

    @Test
    fun `a denial reports how long to wait`() {
        val budget = budget()
        repeat(3) { budget.tryAcquire(MarketDataProvider.FCS_API) }
        clock.advanceSeconds(10)

        val decision = budget.tryAcquire(MarketDataProvider.FCS_API) as RequestBudget.Decision.Denied
        // The oldest charge was 10 seconds ago, so 50 seconds remain of its minute.
        assertEquals(50L, decision.retryAfter.seconds)
    }

    @Test
    fun `the free-tier table keeps both metered feeds below their published ceilings`() {
        val table = RequestBudget.FREE_TIER_LIMITS

        val alpha = table[MarketDataProvider.ALPHA_VANTAGE]
        assertNotNull("Alpha Vantage must have limits", alpha)
        assertTrue(
            "Alpha Vantage's published free ceiling is 25/day; the guard must sit below it",
            alpha!!.any { it.span == Duration.ofDays(1) && it.maxRequests < 25 }
        )
        assertTrue(
            "Alpha Vantage's published free ceiling is 5/minute",
            alpha.any { it.span == Duration.ofMinutes(1) && it.maxRequests <= 5 }
        )

        val fcs = table[MarketDataProvider.FCS_API]
        assertNotNull("FCS must have limits", fcs)
        assertTrue(
            "FCS's published free allowance is 500 credits per month",
            fcs!!.any { it.span == Duration.ofDays(30) && it.maxRequests < 500 }
        )
    }

    @Test
    fun `the simulator is not metered`() {
        assertTrue(
            "the simulator has no quota and must not appear in the limit table",
            MarketDataProvider.SIMULATED !in RequestBudget.FREE_TIER_LIMITS
        )
    }

    @Test
    fun `the summary names the feed and every window`() {
        val budget = budget()
        budget.tryAcquire(MarketDataProvider.FCS_API)

        val summary = budget.describe(MarketDataProvider.FCS_API)
        assertTrue("summary should name the feed: $summary", summary.contains("FCS API"))
        assertTrue("summary should name the minute window: $summary", summary.contains("per minute"))
        assertTrue("summary should name the day window: $summary", summary.contains("per day"))
        assertTrue("summary should report the charge: $summary", summary.contains("1 of 3"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a window cannot allow zero requests`() {
        RequestBudget.Window(Duration.ofMinutes(1), maxRequests = 0, displayName = "minute")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a window span must be positive`() {
        RequestBudget.Window(Duration.ZERO, maxRequests = 1, displayName = "minute")
    }

    /** A store that keeps everything in memory and records what was written. */
    private class RecordingBudgetStore : RequestBudget.BudgetStore {
        val saved = mutableMapOf<MarketDataProvider, List<java.time.Instant>>()

        override fun load(provider: MarketDataProvider): List<java.time.Instant> =
            saved[provider].orEmpty()

        override fun save(provider: MarketDataProvider, usage: List<java.time.Instant>) {
            saved[provider] = usage.toList()
        }
    }
}
