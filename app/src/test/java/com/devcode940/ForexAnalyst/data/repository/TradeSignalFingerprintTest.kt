package com.devcode940.ForexAnalyst.data.repository

import com.devcode940.ForexAnalyst.data.model.AlertConditionType
import com.devcode940.ForexAnalyst.data.model.AlertId
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.SignalId
import com.devcode940.ForexAnalyst.data.model.SignalType
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for the signal identity/dedup fingerprint.
 *
 * WHY: The fingerprint backs a UNIQUE index, so a weak hash silently DROPS
 * legitimate signals (OnConflictStrategy.IGNORE). The old implementation used
 * `String.hashCode()` — 32-bit, collision-prone, and locale-dependent because
 * it formatted prices with `"%.5f".format(...)` (comma in many locales).
 */
class TradeSignalFingerprintTest {

    private val candleTime: Instant = Instant.parse("2024-03-15T09:00:00Z")

    private fun signal(
        pair: PairSymbol = PairSymbol("EURUSD"),
        timeframe: Timeframe = Timeframe("H1"),
        type: SignalType = SignalType.BUY,
        time: Instant = candleTime,
        entry: Double = 1.08421
    ) = TradeSignal(
        id = SignalId.generate(),
        pairSymbol = pair,
        signalType = type,
        entryPrice = entry,
        stopLoss = entry - 0.002,
        takeProfit1 = entry + 0.003,
        takeProfit2 = entry + 0.005,
        riskRewardRatio = 1.5,
        confidenceScore = 0.75,
        timeframe = timeframe,
        summaryRationale = "test",
        timestamp = time
    )

    @Test
    fun `fingerprint is stable across repeated rescans of the same bar`() {
        // WHY: this is the idempotency guarantee — re-running signal
        // generation for the same candle must produce the same identity, so
        // the UNIQUE index turns a retry into a no-op instead of a duplicate.
        val first = signal().buildFingerprint()
        val second = signal().buildFingerprint()
        assertEquals(first, second)
    }

    @Test
    fun `fingerprint is a sha256 hex digest`() {
        val fingerprint = signal().buildFingerprint()
        assertEquals(64, fingerprint.length)
        assertTrue(fingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `fingerprint differs for every identity component`() {
        val base = signal().buildFingerprint()
        assertNotEquals(base, signal(pair = PairSymbol("GBPUSD")).buildFingerprint())
        assertNotEquals(base, signal(timeframe = Timeframe("H4")).buildFingerprint())
        assertNotEquals(base, signal(type = SignalType.SELL).buildFingerprint())
        assertNotEquals(base, signal(time = candleTime.plusSeconds(3600)).buildFingerprint())
    }

    @Test
    fun `fingerprint ignores the per scan random signal id`() {
        // WHY: the id is generated fresh on every scan; including it in the
        // identity would defeat deduplication entirely.
        val first = signal()
        val second = first.copy(id = SignalId.generate(), summaryRationale = "different wording")
        assertNotEquals(first.id, second.id)
        assertEquals(first.buildFingerprint(), second.buildFingerprint())
    }

    @Test
    fun `fingerprint does not collide across one thousand consecutive bars`() {
        // WHY: a 32-bit hashCode collides after ~77k random values, but far
        // sooner for structured input like sequential timestamps. SHA-256 does
        // not collide here — this pins the property the UNIQUE index relies on.
        val fingerprints = (0 until 1_000)
            .map { offset -> signal(time = candleTime.plusSeconds(offset * 3600L)).buildFingerprint() }
            .toSet()
        assertEquals(1_000, fingerprints.size)
    }

    @Test
    fun `ids are uuid backed and unique`() {
        // WHY (regression): SignalId/AlertId wrapped a raw String; they now
        // wrap java.util.UUID so a malformed id cannot be constructed and
        // equality is not affected by string formatting differences.
        val ids = List(1_000) { SignalId.generate().value }
        assertEquals(1_000, ids.toSet().size)
        assertEquals(ids.first(), UUID.fromString(ids.first().toString()))

        val alertId = AlertId.generate()
        assertEquals(alertId, AlertId(UUID.fromString(alertId.value.toString())))
    }

    @Test
    fun `pair symbol normalization makes casing and padding irrelevant`() {
        // WHY: nav args, search input and DB rows all reach PairSymbol; without
        // one normalization point, "eurusd" and "EURUSD" become two watchlist
        // rows and two cache keys.
        assertEquals(PairSymbol("EURUSD"), PairSymbol.of("  eurusd "))
        assertEquals(PairSymbol.of("eurusd").value, PairSymbol.of("EURUSD").value)
        assertEquals(
            signal(pair = PairSymbol.of("eurusd")).buildFingerprint(),
            signal(pair = PairSymbol.of("EURUSD")).buildFingerprint()
        )
    }

    @Test
    fun `alert identity round trips through its uuid`() {
        val alert = CustomAlert(
            id = AlertId.generate(),
            pairSymbol = PairSymbol("USDJPY"),
            conditionType = AlertConditionType.PRICE_ABOVE,
            targetValue = 151.25,
            isTriggered = false,
            isActive = true,
            note = "Breakout watch",
            createdAt = candleTime
        )
        val restored = AlertId(UUID.fromString(alert.id.value.toString()))
        assertEquals(alert.id, restored)
    }
}
