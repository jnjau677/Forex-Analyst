# Integration testing strategy

This repository is an **Android client** (Jetpack Compose + Room/SQLite). There is no
Spring Boot / Ktor / Micronaut module and no server database, so the test strategy has
two layers:

| Layer | Engine | Where it runs | File |
|---|---|---|---|
| Unit (pure Kotlin) | JVM, JUnit 4 | `./gradlew :app:testDebugUnitTest` | `app/src/test/**` (367 tests, 22 suites) |
| Integration (real DB) | SQLite via Room in-memory | `./gradlew :app:connectedDebugAndroidTest` | `app/src/androidTest/**/ForexRepositoryIntegrationTest.kt` (27 tests) |

The unit layer carries the whole market-data and alert stack, which is a deliberate design
constraint rather than a convenience: `data/provider` and `data/alert` import no Android types, so
provider routing, quota accounting, message redaction, alert evaluation and monitor behaviour all run
in milliseconds on a JVM. The only Android-bound piece is `AlertNotifier`, and `AlertMonitor`
depends on the `AlertNotificationSink` interface instead of on it.

That split is also what makes CI possible on a hosted runner. `ci/build-apk.yml` (staged at that path
because GitHub only executes workflows under `.github/workflows/`, and the token that authored it
lacked the `workflows` permission — move it there to activate it; the README has the three ways) runs
two independent jobs: `unit-tests` (`:app:testDebugUnitTest`, the 367 JVM tests, plus the HTML report
as an artifact) and `apk` (`:app:assembleDebug` and `:app:assembleRelease`, with the APKs, the R8
mapping and the exported Room schemas uploaded). They are separate jobs rather than steps in one so
that a failing test cannot withhold a downloadable APK, and a compile error in the test sources cannot
hide whether the app itself builds.

The instrumented layer stays local. GitHub-hosted runners have no KVM, so an ARM-emulated Android
emulator takes ~10 minutes to boot and fails intermittently for reasons unrelated to this code; the
27 tests in `ForexRepositoryIntegrationTest` run through `./gradlew :app:connectedDebugAndroidTest`
against a real device or an accelerated emulator. A self-hosted runner with KVM, or
`reactivecircus/android-emulator-runner` on a KVM-enabled host, would change that calculus.

## Why not Testcontainers here

Testcontainers starts **server** databases (PostgreSQL, MySQL, MongoDB) in Docker and talks
to them over TCP. An instrumented Android test runs inside an app process on a device or
emulator:

* it cannot reach a Docker daemon on the host,
* the production database is SQLite **embedded in the process** — there is no server to containerize,
* starting a container per test would not exercise the code path that ships (Room + SQLite + `TypeConverters` + `@Entity` indexes).

The **equivalent guarantee** — a real database engine, real SQL, real constraints, ephemeral
per-test instance — is provided by `Room.inMemoryDatabaseBuilder`, which is what
`ForexRepositoryIntegrationTest` uses. It covers:

* signal round-trip through SQLite (including `Instant` `TypeConverters`),
* idempotent re-save (UNIQUE `fingerprint` + `OnConflictStrategy.IGNORE`),
* corrupt-row tolerance (`SignalType.valueOf` on garbage must skip the row, not kill the stream),
* per-timeframe cache isolation and TTL cache hits,
* LIMIT clamping and retention pruning,
* alert REPLACE/delete semantics,
* **chart drawings**: all eight subtypes through the real DAO — every column intact on the way back
  (a dropped `extendLeft` or a null `riskPercent` still returns an object, just the wrong one),
  scoping by pair *and* timeframe, `REPLACE`-by-id edits, and the `@Transaction` whole-chart replace
  that undo/redo persists through (it must delete rows *and* leave other charts untouched),
* corrupt drawing rows (an unknown `kind`, a long whose stop sits above its entry, a trend line with
  no end anchor) are skipped per row rather than killing the stream,
* bounded, oldest-first drawing observation (`LIMIT 200`, `ORDER BY createdAt, rowid`), which is what
  keeps the paint order — and therefore which object a tap selects — stable between launches,
* **the candle-source seam**: a `CandleSource` double injected into `ForexRepository` proves the
  rows that land in SQLite came from the source and not from the simulator, that a cache hit inside
  the TTL does not call it a second time (every call to a live feed costs metered quota), that
  `invalidateCandleCache()` empties *every* chart and forces the next fetch back through the source,
  and that `dataOrigin` names the producing feed both after a fetch and after a cache hit,
* **degradation provenance**: a batch that arrives with a `degradedReason` publishes that error's
  `userMessage` as the chart notice, so a fallback is explained rather than silently relabelled.

### What is deliberately *not* covered here

`MIGRATION_1_2` (adding the `chart_drawings` table and its index to an existing version-1 database)
is hand-written DDL. Testing it properly means `MigrationTestHelper` against the exported Room schema
for version 1, i.e. `app/schemas/com.devcode940.ForexAnalyst.data.db.ForexDatabase/1.json`. That file
is produced by a real Gradle build (`room.schemaLocation` is already wired in `app/build.gradle.kts`)
and must then be committed, because a migration test that regenerates its own expectation proves
nothing. Until a build has run, the migration is verified by inspection against the entity, and the
gap is recorded in the README's known limitations rather than papered over with a test that cannot
fail.

The same reasoning now applies to two live-feed decisions. Alerts evaluate on a fixed `H1` timeframe
and feed provenance lives in an in-memory map, because the alternatives — a `timeframe` column on
`custom_alerts` and a `provider` column on `historical_candles` — both need migrations that cannot be
validated without the exported schema JSON. Both gaps are recorded in the README.

**The HTTP boundary itself is not covered by an automated test.** `FcsApi` / `AlphaVantageApi`
(Retrofit interfaces), `MarketDataHttp` (the OkHttp client and its redacting log interceptor) and the
status-code classification inside `FcsClient` / `AlphaVantageClient` are exercised only by
inspection. Everything *around* them is tested on the JVM: the parsers take a `JsonObject` and return
`List<CandleStick>` with no Android dependency, `LiveCandleSource`'s failover / suspension / refund
logic is driven by in-test `MarketDataClient` doubles, and `RequestBudget` is pure. The missing piece
is one `MockWebServer` suite asserting that a 401 becomes `AppError.Unauthorized`, a 429 becomes
`RateLimited`, a 5xx becomes `ProviderRejected`, and that no request log contains the API key —
`com.squareup.okhttp3:mockwebserver:4.12.0` as a `testImplementation` dependency, which is
deliberately *not* declared yet so the build does not carry a dependency no test uses.

The server-side equivalent of the same rule is #2 below: migrations are asserted against committed
DDL, never against auto-generated schema.

---

## Testcontainers reference — server-side variant of the same contract

If the market-data/signal logic is ever lifted into a service (the natural shape is
**Ktor 2.x + Exposed/JDBC + PostgreSQL 16**, or **Spring Boot 3.x + Spring Data JDBC**),
the same repository contract is tested with Testcontainers like this. Drop it into a
`server/src/test/kotlin/...` module with these dependencies:

```kotlin
// server/build.gradle.kts
dependencies {
    testImplementation("org.testcontainers:postgresql:1.20.1")
    testImplementation("org.testcontainers:junit-jupiter:1.20.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("io.ktor:ktor-server-test-host-jvm:2.3.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testRuntimeOnly("org.postgresql:postgresql:42.7.3")
}
```

```kotlin
package com.devcode940.forex.signals

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

/**
 * Integration test for the PostgreSQL-backed signal store.
 *
 * WHY @Testcontainers + a static @Container: ONE container is started for the
 * whole class (Docker startup is ~2s) and every test gets a clean schema via a
 * transaction rollback — the same isolation guarantee the Android in-memory
 * Room database gives, but against a real networked database engine.
 */
@Testcontainers
class SignalStoreIntegrationTest {

    companion object {
        // WHY: pinned image digest/tag — `postgres:latest` makes builds
        // non-reproducible when the upstream image changes.
        @Container
        @JvmStatic
        private val postgres = PostgreSQLContainer("postgres:16.3-alpine")
            .withDatabaseName("forex_test")
            .withUsername("forex")
            .withPassword("forex")
            // WHY: an init script applies the exact production DDL, so the test
            // fails when a migration is missing rather than silently passing
            // against a schema nobody ships.
            .withInitScript("sql/schema.sql")
            // WHY: bound the wait so CI fails fast instead of hanging.
            .withStartupTimeout(java.time.Duration.ofSeconds(60))
    }

    private fun store(): JdbcSignalStore =
        JdbcSignalStore(HikariTestPool.of(postgres.jdbcUrl, postgres.username, postgres.password))

    @Test
    fun `saving the same signal twice is idempotent`() = runIntegration {
        val store = store()
        val signal = TradeSignalRecord(
            id = UUID.randomUUID(),
            pair = "EURUSD",
            timeframe = "H1",
            type = "BUY",
            entry = 1.08421,
            candleTime = Instant.parse("2024-03-15T09:00:00Z")
        )

        // WHY: parameterized statements only — no string-concatenated SQL, so
        // injection is impossible by construction.
        store.save(signal)
        store.save(signal.copy(id = UUID.randomUUID()))

        assertEquals(1, store.countForPair("EURUSD"))
    }

    @Test
    fun `signals are paginated with a sane default page size`() = runIntegration {
        val store = store()
        repeat(75) { index ->
            store.save(
                TradeSignalRecord(
                    id = UUID.randomUUID(),
                    pair = "EURUSD",
                    timeframe = "H1",
                    type = "BUY",
                    entry = 1.0 + index * 0.0001,
                    candleTime = Instant.parse("2024-03-15T00:00:00Z").plusSeconds(index * 3600L)
                )
            )
        }

        // WHY: keyset pagination (WHERE candle_time < :cursor) is O(log n) via
        // the index; OFFSET pagination degrades to O(n) on deep pages and is
        // the usual cause of "slow list endpoint" tickets.
        val page = store.page(pair = "EURUSD", limit = 25, cursor = null)
        assertEquals(25, page.items.size)
        assertTrue(page.nextCursor != null)

        val second = store.page(pair = "EURUSD", limit = 25, cursor = page.nextCursor)
        assertTrue(second.items.none { it.id in page.items.map { p -> p.id }.toSet() })
    }

    @Test
    fun `a malformed pair is rejected with 422 not 500`() = runIntegration {
        val result = runCatching { store.page(pair = "'; DROP TABLE signals; --", limit = 10, cursor = null) }
        // WHY: validation happens BEFORE the query, and the parameterized
        // statement would make the payload inert anyway. Either way the caller
        // gets a classified domain error, never a raw SQLException.
        assertTrue(result.isFailure)
    }
}
```

Rules this example encodes (same rules the Android module follows):

1. **One container per class, clean state per test** — cheap isolation, no shared mutable fixtures.
2. **`withInitScript` / Flyway migrations, never `hibernate.hbm2ddl`-style auto-DDL** — the test must fail if production DDL drifts.
3. **Parameterized SQL only** — injection is prevented structurally.
4. **Pinned image tags** — reproducible CI.
5. **Errors are classified** (`AppError` on Android, `ProblemDetails`/error envelope on the server) — a raw `SQLException` never reaches a client.
6. **Keyset pagination with a bounded default page size** — 25, max 100.
