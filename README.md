# Forex-Analyst

**Android** trading terminal: candlestick charts with **71 instruments** across FX, metals, energy,
indices and crypto, an **11-tool drawing layer** (trend lines, Fibonacci, position planners,
measurements) with undo/redo, a **time-zone-aware axis**, technical indicators
(EMA / SMA / Bollinger / VWAP / RSI / MACD / Stochastic / ATR), rule-based trade signals,
**live market data** from FCS API and Alpha Vantage behind a provider seam with a labelled
simulated fallback, **evaluated price/RSI alerts** delivered as notifications, and a watchlist.

It is offline-first by construction, not by absence of networking: with no API key, or with the feed
set to *Offline*, every screen still works on a seeded simulator — and says so on the chart.

> **Stack (specified, not assumed):** Jetpack Compose UI · Kotlin 1.9.20 · Coroutines + Flow ·
> Room 2.6.1 over **SQLite** · Retrofit 2.9.0 + OkHttp 4.12.0 + Gson 2.10.1 for the market-data
> feeds · `security-crypto` 1.1.0 for on-device keys · Gradle Kotlin DSL (AGP 8.2.0) ·
> `compileSdk` 34 / `minSdk` 26.
> This repository contains **no** Spring Boot / Ktor / Micronaut module and no server database —
> it is a single-user client, so the production concerns are persistence, concurrency,
> dispatcher usage, on-device credential storage and crash-safety rather than HTTP status codes
> and connection pooling. `docs/testing/IntegrationTests.md` contains the Testcontainers-based
> equivalent for the server-side variant of the same repository contract.

---

## Architecture

```
ui/            Compose screens, navigation, chart canvas     (stateless, collects StateFlow)
  chart/       ChartUiState / ChartFrame (one snapshot per frame), ChartRenderer,
               PaneRenderer, DrawingRenderer, ChartTextPainter, ChartColors,
               ChartGestureMath (pixels -> data space), ChartEvent (every interaction)
  components/  CandlestickChartCanvas (Compose Canvas + gestures), ChartToolbar
  viewmodel/   ForexViewModel, SettingsViewModel             (constructor-injected, no Android deps in logic)
  components/  DataSourceBadge (provenance), AlertMonitorBanner (why nothing fired)
data/
  provider/    MarketDataClient (feed contract), LiveCandleSource (failover + quota),
               RequestBudget (free-tier windows), PrefsBudgetStore, ProviderMessageSanitizer,
               MarketDataProviderRegistry (composition), fcs/ + alphavantage/ (Retrofit API,
               parser, client), http/ (OkHttp factory + redacting log interceptor)
  alert/       AlertEvaluator (pure JVM), AlertNotificationSink -> AlertNotifier, AlertMonitor
  model/       Domain types: CandleStick, TradeSignal, CustomAlert, Instrument + InstrumentCatalog,
               ChartDrawing (sealed, 8 subtypes) + DrawingPalette, ChartConfig / ChartStyle /
               ChartTimeZone / ChartOverlay / MagnetMode / ChartToolMode, PriceFormatter,
               value-class ids (SignalId/AlertId/DrawingId = UUID, PairSymbol, Timeframe),
               Result<T> / AppError envelope
  chart/       Pure-JVM chart engine: ChartRect, ChartMath, ChartCoordinateSystem, ChartLayout,
               TimeAxis, PriceAxis, TimeFormatter, DrawingGeometry, DrawingStore, DrawingFactory
  settings/    AppSettings (immutable snapshot), ThemeMode, IndicatorFormValidator,
               SettingsRepository (EncryptedSharedPreferences), ChartSettingsWriter
  repository/  MarketDataRepository (interface) -> ForexRepository (Room impl),
               CandleSource seam -> SimulatedCandleSource / LiveCandleSource,
               TechnicalAnalysisEngine (pure math), PriceGenerator (seeded simulator)
  db/          Room entities, DAOs, Converters, ForexDatabase (v2 + migrations)
```

**Dependency rule:** `ui` → `data.repository` (interface) → `data.provider` / `data.db`. The UI never
touches Room types *or* provider types; the repository maps entities to domain models and classifies
every failure into an `AppError`, so no internal exception text — and no API key — can reach a
screen. Nothing under `data/` imports `ui.*`, which is what keeps the alert layer testable on a JVM.

**Why `data/chart` exists separately from `ui/chart`:** everything that decides *where a pixel goes*
— coordinate mapping, axis ticks, layout, drawing geometry, the undo store — is pure Kotlin with no
Compose or Android import, so it runs as a JVM unit test in milliseconds. `ui/chart` only paints the
result and converts touches. A renderer bug is visible; a geometry bug is not, and the geometry is
where the tests are.

### Data flow

1. `ForexViewModel.loadCandles()` → `MarketDataRepository.fetchHistoricalCandles(pair, timeframe)`.
2. The repository checks the SQLite cache under a `Mutex` (one query serves both the data and the
   freshness check). On a miss it calls **one** `CandleSource` — `LiveCandleSource` when a feed is
   configured, `SimulatedCandleSource` otherwise — caches the rows, then prunes anything older than
   30 days. The source returns a `CandleBatch` carrying its own provider, so the repository never
   has to guess where a price came from.
3. The batch's provenance is published on `MarketDataRepository.dataOrigin` and rendered by
   `DataSourceBadge` under the chart: feed name, `Cached`, and any degradation notice.
4. Indicator math runs on `Dispatchers.Default` (CPU-bound), I/O stays on Room's own executor.
5. `TechnicalAnalysisEngine.generateSignals()` produces at most one signal per candle; identity is a
   SHA-256 content fingerprint, so re-scans are **idempotent** (`UNIQUE` index + `IGNORE`).
6. Room `Flow`s push bounded result sets (`LIMIT`) back to the UI — no polling, no unbounded lists.
7. Chart state is published as **one** `ChartUiState` snapshot (candles + indicators + config +
   viewport + crosshair + draft + drawings). Collecting four flows into the canvas instead would let
   a recomposition pair a new config with old candles — the "chart jumped when I toggled RSI" bug.

## Instruments

`InstrumentCatalog` is the single source of truth for what the app can chart: **71 instruments** —
7 majors, 20 crosses, 20 exotics, 4 metals, 3 energy, 7 indices and 10 crypto — each carrying its
own `quoteDigits`, derived `tickSize` / `pipSize`, trading sessions and a reference price that seeds
the simulator.

* `PairCatalog` and the watchlist picker are thin projections over it, so adding an instrument is a
  one-line change that shows up everywhere at once.
* `PriceFormatter` takes its digit count from the instrument, which is why USD/JPY reads `151.500`
  and gold reads `2350.00` instead of both being forced to five decimals.
* Sessions (`TradingSession.SYDNEY/TOKYO/LONDON/NEW_YORK`, in UTC) drive the session bands on the
  chart and the "London · open" line in the readout bar, including the Sydney wrap past midnight.

## Chart tools

The tool rail carries thirteen modes — crosshair, pan/zoom, and **eleven drawing tools** — with one
gesture model, and every object is persisted:

| Tool | Stored as | Notes |
|---|---|---|
| Horizontal / vertical line | `HorizontalLine`, `VerticalLine` | one anchor — a tap is enough |
| Trend line / ray / extended line | `TrendLine` + `extendLeft` / `extendRight` | flags, not three types: the maths stays identical |
| Fib retracement / extension | `FibRetracement`, `FibExtension` | levels `0…1` and `0…2.618`, direction-aware |
| Rectangle / measure | `Rectangle`, `Measurement` | measure reports price, %, pips, bars and duration |
| Long / short position | `Position` | entry, stop and mirrored 1:1 target; the drag sets the risk *distance*, not the direction |

* **Selection is per-object, not per-bounding-box.** `DrawingGeometry.hitTest` gives each type its own
  rule (distance-to-segment for lines, outline-only for rectangles, level-stack for Fibonacci), so two
  crossing trend lines can still be picked apart with a finger.
* **One event sink.** The canvas emits `ChartEvent`s into a single `onChartEvent`, and the ViewModel
  handles all nineteen cases in one exhaustive `when` — a new tool or toggle is a compile error until
  its handling exists, which ten optional lambdas would let be forgotten silently.
* **Magnets.** `MagnetMode.NONE/WEAK/STRONG` snaps an anchor to the nearest OHLC extreme of the bar
  under the finger, measured **in pixels** — a tolerance in price would be unusable on one instrument
  or the other. Under `STRONG`, a two-anchor drag also snaps to 45° increments (a phone has no Shift
  key).
* **Undo/redo** lives in `DrawingStore`, an in-memory write-through cache of immutable snapshots
  (bounded at 40 snapshots / 200 drawings). Undo is a cursor move, not a hand-written inverse
  operation, and it persists as a single transactional whole-chart replace — because restoring a
  snapshot usually *removes* rows, which no single-row write can express.
* Drawings are scoped by **pair and timeframe**: an H1 level never appears on the D1 chart.

### Persistence

One `chart_drawings` table with a `kind` discriminator rather than eight tables: "everything on this
chart, oldest first" stays a single indexed query, which is what the renderer needs every time the
chart opens. Columns are tool-specific (not a JSON blob) so they remain queryable and Room can
validate the schema. The discriminator strings are frozen constants — deriving them from
`::class.simpleName` would silently orphan every stored row the first time a class is renamed.
Reads are ordered `createdAt, rowid` so two objects placed inside the same millisecond keep a stable
paint order between launches.

## Styling time

Time is styled, not formatted:

* `TimeAxis.chooseStep` picks the tick interval from the **visible span** (not the timeframe), so
  500 bars read `14:35` on M1, `12 Mar` on D1 and `Mar 2024` on W1 — at every zoom level.
* Ticks are built from a `ZonedDateTime` and truncated/advanced per step, so a DST switch shifts the
  local hour without ever producing a `05:30` label. Calendar steps (`MONTH_1`) advance with
  `plusMonths`, not by adding 30 × 24 h.
* `ChartTimeZone` is an explicit, persisted choice — device, UTC, London, New York, Tokyo, Nairobi,
  Dubai, Singapore — shown as a badge on the toolbar (`Time nairobi`). It drives the axis, the
  crosshair tag, the readout bar and the **VWAP session anchor**, so the indicator resets at the day
  boundary the user can actually see.
* `TimeFormatter` supplies the human wording: `just now`, `5 min ago`, `in 5 min`, `1 h 25 min`,
  `3 bars`.
* A midnight tick is promoted from `00:00` to `16 Mar`, because an axis of six `00:00` labels says
  nothing.

## Market data: two live feeds behind one seam

`MarketDataRepository` is the seam the UI depends on, and `CandleSource` is the seam the *repository*
depends on. Adding a feed means writing one `MarketDataClient` and registering it — no screen,
ViewModel or DAO changes:

```
MarketDataClient          val provider; supports(pair, timeframe); suspend fetchCandles(...) -> List<CandleStick>
  FcsClient               fcsapi.com v4  GET /api-v4/forex/history  (all 7 timeframes, volume, app's own symbols)
  AlphaVantageClient      alphavantage.co  FX_INTRADAY / FX_DAILY    (M1/M5/M15/H1/D1 only; H4 and W1 unsupported)
LiveCandleSource          ordered candidates -> budget -> fetch -> classify; on total failure, the simulator
SimulatedCandleSource     seeded per (instrument, timeframe) from InstrumentCatalog
```

* **Precedence is explicit.** `MarketDataPreference` is `AUTO / FCS_API / ALPHA_VANTAGE / OFFLINE`.
  Under `AUTO` both keys are tried best-first and **FCS wins**, because Alpha Vantage's free tier is
  25 requests/day — one session of timeframe switching exhausts it — while FCS gives 500 credits per
  month and covers all seven timeframes from one endpoint. An *explicit* selection never silently falls through to the other feed: if you asked for
  Alpha Vantage and it fails, you are told, rather than shown FCS prices labelled as your choice.
* **A missing key degrades instead of failing.** The chart always renders something, and
  `DataOrigin` says what. A chart of simulated prices presented as real is how someone loses money
  on a demo, so provenance is not a debug detail — it is on the screen.
* **Quota is a first-class object.** `RequestBudget` models each provider's free-tier windows and
  refuses a request *before* it is sent, returning `AppError.RateLimited` with a retry-after. The
  enforced numbers sit **below** the published ceilings — Alpha Vantage 5/min and 20/day against a
  published 25/day; FCS 4/hour and 400/month against 500 credits/month — because the published number
  is where the provider starts refusing, and a client that sits exactly on it races its own retry
  logic into a 429. FCS has no per-day window: its quota is monthly, so a daily cap would be an
  invention, and its hourly cap matches both the repository cache TTL and FCS's own 60-minute
  response cache. Counters persist in
  `market_data_quota` preferences, which are excluded from backup — restoring a backup must not
  resurrect somebody else's spent quota. Settings → API keys shows the counters and offers
  **Reset counters**, because a local estimate of a server-side quota needs an exit when it drifts.
* **A rejected key suspends its feed for an hour** (`AUTH_FAILURE_SUSPENSION`) so a revoked key
  cannot burn the *other* feed's budget by failing every poll. Editing a key clears the suspension.
* **Failures are classified, never leaked.** `ProviderMessageSanitizer` redacts `apikey`,
  `access_key`, `token`, `key` and any known secret from a provider's message before it can reach a
  notice or a log; OkHttp logging goes through `RedactedRequestLogInterceptor`, which prints a
  scrubbed URL, the status code and the duration — both feeds pass the key as a **query parameter**,
  so BODY-level logging would have written it to logcat in debug builds.
* **Only network failures refund a charge.** A provider refusal is *not* refunded: Alpha Vantage
  counts every call including throttled ones, and an optimistic refund is how a key gets suspended.

### Provider data-quality note

FCS documents its own data as "for informational and educational purposes only… not appropriate for
trading purposes", and prices "may differ from the actual market price". Free-plan responses are also
cached provider-side for 60 minutes, so polling faster than hourly spends a credit for an identical
payload — which is why the repository's own cache TTL (1 hour) and the alert poll interval are not
shorter than they are.

## Alerts: evaluation and notifications

Alerts were previously persisted and listed but never evaluated. `data/alert` closes that loop.

* **`AlertEvaluator` is pure JVM** — no Android import, so the whole decision table is unit-tested.
  It answers one question per alert: does the newest H1 bar satisfy the condition? All four
  conditions (`PRICE_ABOVE`, `PRICE_BELOW`, `RSI_ABOVE`, `RSI_BELOW`) are **inclusive**, and RSI is
  computed once for the whole alert list rather than once per alert.
* **It refuses to fire on stale or absent data.** Too few bars, or a newest bar older than 72 hours
  (a weekend, a suspended key, a provider cache), yields `fired = false` plus a detail that says why.
  Firing on Thursday's price and telling the user "EURUSD is above 1.09000" *now* is a false trading
  signal.
* **It never fires on simulated data.** When the resolved provider is `SIMULATED`, `AlertMonitor`
  skips evaluation entirely and publishes `waitingForLiveFeed`, which `AlertMonitorBanner` renders on
  the Alerts screen. A fabricated trading signal is the most harmful thing this app could produce.
* **`AlertMonitor` is an in-process, foreground-only loop** (`ForegroundTracker` gates each tick,
  five minutes apart, at most five distinct pairs per pass). This is deliberate and **not**
  WorkManager: a 15-minute periodic floor would burn a month of FCS credits in under two days. The
  trade — no alerts while the app is closed — is listed under Known limitations.
* **An alert is persisted as triggered *before* it is announced.** If the write failed after the
  notification, the next tick would fire the same alert again every five minutes; this ordering makes
  the worst case a missed notification instead of a spam loop. `getActiveAlerts()` filters
  `isTriggered = 0`, so the loop is idempotent by query, not by luck.
* **Notifications respect the existing settings.** `AlertNotifier.canNotify` requires
  `notificationsEnabled && alertNotifications` *and* the runtime `POST_NOTIFICATIONS` grant, which is
  read live rather than cached (so revoking it in system settings takes effect immediately). Channel
  importance is `HIGH` only when `soundEnabled` or `vibrationEnabled` is on — otherwise `DEFAULT` —
  so a user who switched off sound does not get a heads-up banner anyway. Tapping the notification
  opens the Alerts screen with that alert id.
* `AlertNotifier` implements the one-method-per-concern `AlertNotificationSink` interface, which is
  what `AlertMonitor` actually depends on; that indirection is the only reason the monitor's
  notification behaviour is testable without a `Context`.

## Correctness invariants (enforced in `init` blocks)

| Type | Invariant |
|---|---|
| `CandleStick` | prices finite, `high >= low`, `high >= max(open, close)`, `low <= min(open, close)`, `volume >= 0` |
| `TradeSignal` | prices finite, `confidenceScore ∈ [0, 1]`, `riskRewardRatio` finite and `>= 0` |
| `CustomAlert` | `targetValue` finite (rejects `NaN`), `note <= 500` chars |
| `IndicatorSettings` | periods in range, EMA periods ascending, `macdFast < macdSlow` |
| `Instrument` | non-blank display name, `quoteDigits ∈ 0..8`, base price finite and `> 0`, `volatility ∈ 0..0.5`, at least one trading session |
| `ChartPoint` | finite price |
| `ChartDrawing.*` | label `<= 80` chars; fib levels ascending, unique, `<= 12`, inside the tool's range; a long's stop below its entry and target above it (mirrored for shorts); `riskPercent ∈ 0.01..100` |
| `ChartCoordinateSystem` | finite price and time bounds, `scale > 0` |
| `ChartViewport` | finite `scale > 0`, finite `offsetX` |
| `CrosshairData` | finite price |
| `PairSymbol` | non-blank; `PairSymbol.of()` normalizes case/whitespace |
| `Timeframe` | one of `Timeframe.ORDERED` (M1, M5, M15, H1, H4, D1, W1) |

Indicator series are **index-aligned** with the candle list they were computed from: `values[i]`
belongs to `candles[i]`, with leading `null`s until the first computable bar. The chart canvas
depends on this contract, including `%D`, which is an SMA of the *computed* `%K` values re-aligned
onto the candle index.

Heikin-Ashi is a **display** transform: `chartState.candles` carries the smoothed bars while
`candles` and every indicator keep using real prices, so a chart style can never change a signal.

## Security

* API keys are stored in `EncryptedSharedPreferences` (Android Keystore, AES-256-GCM) with a
  one-time migration from the previous plaintext file, which is then cleared.
* **Keys are read per request, never captured at construction.** `MarketDataProviderRegistry`
  builds its clients with key *lambdas*, so a key pasted into Settings takes effect on the next
  fetch instead of the next process start — and no long-lived object holds a copy.
* **Shipping a key in the client is a known limitation, not a design.** Both feeds authenticate with
  a long-lived key passed as a query parameter, so anyone who unpacks the APK can read it. The honest
  shape for a distributed build is a short proxy that holds the key server-side and issues
  short-lived tokens; that needs a backend this repository does not have, so the key stays on-device
  in encrypted storage, is excluded from backup, and is redacted from every log and error path. Treat
  a debug build as *your own* key only.
* The chart ViewModel receives a `ChartSettingsWriter` — a one-method interface — instead of the
  whole `SettingsRepository`, so chart code (and its tests) cannot read the stored API keys.
* `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` exclude both settings files
  **and** `market_data_quota` from cloud backup and device-to-device transfer — restoring a backup
  must not resurrect somebody else's spent quota.
* Both feeds put the key in the **query string**, so `RedactedRequestLogInterceptor` scrubs known
  secret parameter names before any URL is logged; OkHttp's own BODY-level interceptor stays a
  debug-only dependency.
* OkHttp's logging interceptor is a **debug-only** dependency, so release builds cannot log
  credential-bearing headers.
* Errors shown to users come from `AppError.userMessage`; the underlying `Throwable` is logged only.
  Provider text that reaches a notice passes through `ProviderMessageSanitizer` first, which redacts
  `apikey` / `api_key` / `access_key` / `token` / `key` values and any known secret of six or more
  characters, caps the reason at 180 characters, and never returns a blank string.
* Release builds run R8 with `app/proguard-rules.pro` (Gson reflection keep rules included — without
  them obfuscation silently resets settings on every launch). Every persisted enum also pins its wire
  spelling with `@SerializedName`, and `ChartConfigTest` fails if one is renamed.
* **Signing credentials never live in the repository.** `app/build.gradle.kts` reads the keystore path
  and passwords from Gradle properties or environment variables; `*.jks`, `*.keystore`,
  `keystore.properties` and `signing.properties` are gitignored, and CI decodes the keystore from a
  secret into `$RUNNER_TEMP`, outside the checkout, so no uploaded artifact can contain it. A
  committed signing key cannot be rotated without breaking every existing install.
* The CI workflow runs with `permissions: contents: read` — it builds and uploads artifacts, and
  cannot write to the repository it ran on.

## Accessibility

* The canvas is opaque to TalkBack, so the chart carries a `semantics { contentDescription = … }`
  built from the same values the visible readout shows, plus an always-on `ChartReadoutBar` of real
  selectable text (the in-canvas tooltip only exists while a finger is down).
* Tool and palette labels are text, not icon-only (the module deliberately has no
  `material-icons-extended` dependency, so there is no icon to guess at), and every palette swatch
  carries its name on the `Swatch` itself so a reorder cannot mislabel the colours.

## Build & test

**Requires JDK 17.** `app/build.gradle.kts` pins `sourceCompatibility`, `targetCompatibility` and
`jvmTarget` to 17, and AGP 8.2.0 refuses to run on JDK 11 — so 17 is both the floor and the target.
There is no `jvmToolchain` block and no `org.gradle.java.home`, which means Gradle builds with
whatever JVM launched it: in Android Studio set *Gradle JDK* to the embedded JetBrains Runtime 17,
on the command line point `JAVA_HOME` at a Temurin 17. JDK 21 runs the build but changes nothing,
since the bytecode target is still 17.

`minSdk 26` / `compileSdk 34` are Android API levels, not Java versions. Because `minSdk` is 26, the
`java.time` usage throughout the data layer needs no core-library desugaring.

```bash
./gradlew :app:assembleDebug                 # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease               # -> app-release.apk (signed) or app-release-unsigned.apk
./gradlew :app:testDebugUnitTest             # JVM unit tests (engine, chart maths, models, ViewModel)
./gradlew :app:connectedDebugAndroidTest     # Room integration test on a device/emulator
./gradlew :app:lintDebug                     # static analysis
```

### Getting an APK without an Android toolchain

`ci/build-apk.yml` builds on GitHub's runners — JDK 17 plus the preinstalled Android SDK — and
publishes the result as a downloadable artifact, so nobody needs a local toolchain to hold the app.

> **It is staged, not active.** GitHub only runs workflows under `.github/workflows/`, and the commit
> that added this one was made with a GitHub App token that lacks the `workflows` permission — GitHub
> rejected the push outright. The file is committed at `ci/build-apk.yml`, complete and reviewed, and
> goes live with any one of:
>
> ```bash
> git mv ci/build-apk.yml .github/workflows/build-apk.yml
> git commit -m "Enable the APK build workflow" && git push
> ```
>
> …or **Actions → New workflow → set up a workflow yourself**, paste the file's contents and commit
> (the web UI commits as you, so the App permission never applies), …or grant the app
> *Settings → GitHub Apps → Repository permissions → Workflows: Read and write*.

Once active, run it from **Actions → Build APK → Run workflow** (it also runs on every push to `main`
and every pull request), then download `ForexAnalyst-apks.zip` from the run page. The same run uploads:

| Artifact | Contents | Why |
|---|---|---|
| `ForexAnalyst-apks` | debug and release `.apk` | the installable app |
| `ForexAnalyst-release-mapping` | R8 `mapping.txt` | release builds are obfuscated, so a field crash trace is unreadable without the mapping from *that exact* build |
| `ForexAnalyst-room-schemas` | exported `app/schemas/*.json` | the input `MigrationTestHelper` needs — see Known limitations |
| `unit-test-report` | HTML test report | which assertion failed, without re-running anything |

The APK build and the unit tests are **separate jobs** on purpose: a failing test must not withhold a
downloadable APK, and a compile error in the test sources must not hide whether the app itself builds.
The 27 instrumented tests are *not* run in CI — GitHub-hosted runners have no hardware acceleration,
so a software emulator costs ten minutes to boot and fails for reasons unrelated to this code. They
stay a local `connectedDebugAndroidTest` run.

**Which APK installs:** the debug APK is signed with the throwaway debug keystore and installs on any
device with sideloading enabled. The release APK installs **only** if signing is configured; otherwise
AGP emits `app-release-unsigned.apk`, which the launcher refuses, and the workflow raises a visible
warning annotation rather than letting you discover that on your phone.

### Signing a release

Credentials are read from Gradle properties or environment variables — never from the repository,
because a committed keystore lets anyone who clones it publish an update under the same signature:

| Gradle property (`~/.gradle/gradle.properties`) | Environment variable (CI secret) |
|---|---|
| `forexStoreFile` | `FOREX_STORE_FILE` |
| `forexStorePassword` | `FOREX_STORE_PASSWORD` |
| `forexKeyAlias` | `FOREX_KEY_ALIAS` |
| `forexKeyPassword` | `FOREX_KEY_PASSWORD` |

In CI the keystore itself is stored as `FOREX_KEYSTORE_BASE64` and decoded to `$RUNNER_TEMP`, outside
the checkout, so no artifact glob can pick it up. With all four values present and the file existing,
the release build type is signed; with any of them missing, the build logs a warning naming the
missing property at *configuration* time and produces an unsigned APK — AGP's own failure for a bad
keystore path arrives late inside `packageRelease` and reads like a code error.
`*.jks`, `*.keystore`, `keystore.properties` and `signing.properties` are gitignored.

### A note for AndroidIDE users

`gradle.properties` deliberately does **not** set `android.aapt2FromMavenOverride`. It used to point at
`/usr/bin/aapt2`, a path that exists only inside AndroidIDE; because a project-level property is read by
every machine that opens the repository, Android Studio, the CLI and CI all failed during resource
linking with *"Cannot run program /usr/bin/aapt2 … No such file or directory"* before Gradle reached a
line of Kotlin. If you build on-device with AndroidIDE, put the override in your **user-level**
`~/.gradle/gradle.properties`, where it applies to your machine only.


**394 tests across 23 suites** (367 JVM unit tests in 22 suites, 27 instrumented in 1). The provider,
budget, sanitizer, alert-evaluation and alert-monitor suites run on the JVM with no Android
dependency: the parsers take `JsonObject` in and `List<CandleStick>` out, `AlertEvaluator` is a pure
function, and `AlertMonitor` depends on the `AlertNotificationSink` interface rather than
`NotificationManager`.

| Suite | Covers |
|---|---|
| `TechnicalAnalysisEngineTest` | EMA/RSI/MACD/ATR alignment, hand-computed values, BUY/SELL/no-signal conditions |
| `ExtendedIndicatorTest` | SMA, Bollinger (population σ, bandwidth), session-anchored VWAP across zones and zero-volume bars, Stochastic `%K`/`%D` alignment, Heikin-Ashi recursion |
| `PriceGeneratorTest` | seeded determinism, clock injection, timestamp spacing, input validation |
| `TradeSignalFingerprintTest` | SHA-256 identity, 1 000-bar collision check, UUID ids, symbol normalization |
| `DomainModelValidationTest` | every `init` invariant, `Result`/`AppError` classification, no message leaks |
| `InstrumentCatalogTest` | catalogue uniqueness, digit/tick/pip derivation per asset class, session overlaps and the Sydney midnight wrap |
| `PriceFormatterTest` | locale-pinned separators, per-instrument precision, pips/percent/compact forms, `NaN` → em dash |
| `ChartConfigTest` | overlay toggles, pane ordering, settings round trip, frozen enum wire spellings |
| `ChartEngineTest` | index maths, coordinate mapping and clamping, layout budget, both axes, DST-safe ticks, time wording |
| `DrawingTest` | geometry, per-type hit-testing, magnets, ray projection, store scoping/undo/redo/replace, gesture→object factory |
| `ChartDrawingMapperTest` | entity ↔ domain round trip for all 8 subtypes, frozen discriminators, column placement, corrupt-row tolerance |
| `IndicatorFormValidatorTest` | parse/coerce/cross-field rules and actionable messages |
| `ForexViewModelTest` | load success/failure, timeframe & pair switching, stream resubscription, error surfacing |
| `ChartInteractionTest` | toolbar write-through, Heikin-Ashi vs indicators, zone re-anchoring, tool arming, drawing lifecycle, undo/redo persistence, adoption without enabling undo |
| `RequestBudgetTest` | window accounting per provider, grant/deny decisions and retry-after, refunds, persistence through a `BudgetStore` double, `reset`, future-dated token rejection |
| `ProviderMessageSanitizerTest` | secret redaction by parameter name and by known value, one-pass regex, length cap, never blank |
| `MarketDataRoutingTest` | preference resolution and candidate ordering, frozen provider wire spellings, `InstrumentSplit` across the whole catalogue (including `DOGEUSD`), `LiveCandleSource` failover, auth suspension, quota denial, network refunds and simulator fallback |
| `AlphaVantageParserTest` | `FX_INTRADAY`/`FX_DAILY` payloads, `Note`/`Information` throttle detection (data wins over a refusal), zone metadata, dedupe + ordering, malformed rows |
| `FcsParserTest` | v4 envelope (`status`/`code`/`msg`), timestamp-keyed maps, positional `is_chart` arrays, all timestamp spellings, negative volume clamping, no secret leakage in a refusal |
| `SimulatedCandleSourceTest` | provenance label, bar spacing per timeframe, stable seed, per-instrument reference prices, all 71 catalogue instruments |
| `AlertEvaluatorTest` | all four conditions (inclusive edges), RSI series reuse, too-short and too-stale refusals, fired vs neutral headlines, per-instrument precision, note passthrough |
| `AlertMonitorTest` | never evaluates simulated prices, persists-before-notifies, idempotent `start()`, foreground gating, five-pair cap, fetch and write failures, blocked notifications |
| `ForexRepositoryIntegrationTest` | real SQLite: round-trips, idempotent saves, corrupt-row tolerance, cache TTL, per-timeframe isolation, pruning, all 8 drawing subtypes through the real DAO (scoping, transactional replace, bounded ordering), and the **candle-source seam** — injected source supplies the cached rows, a cache hit does not re-call it, invalidation empties every chart and forces a re-fetch, `dataOrigin` reports the feed and cache state, a degraded batch publishes a safe notice |

ViewModel tests share one barrier helper (`TestAwait.awaitSettled`): `viewModelScope` runs on the
test dispatcher, but the indicator math is handed to `Dispatchers.Default` — a real thread `runTest`
does not wait for. Without the barrier those assertions would race a background thread and fail
intermittently on CI only.

Two suites deliberately avoid `runTest` where it would make an assertion pass for the wrong reason.
`MarketDataRoutingTest` uses plain `runBlocking` for the auth-suspension case, because `runTest`'s
virtual clock would skip the one-hour window and prove nothing; `AlertMonitorTest` uses `runTest`
only for the polling loop, where advancing virtual time by exact poll intervals is the point.
`MutableClock` (an injected `java.time.Clock`) is what makes staleness, TTL and quota-window tests
deterministic without `Thread.sleep`.

Every non-obvious change in the codebase carries an inline `// WHY:` comment explaining the
production risk it removes.

## Known limitations

* **No alerts while the app is closed.** `AlertMonitor` is an in-process, foreground-gated loop. The
  alternative — WorkManager — has a 15-minute periodic floor that would spend a month of FCS free
  credits in under two days, and a `FOREGROUND_SERVICE` for price polling is a worse trade than
  "open the app to be alerted". The Alerts screen says this out loud via `AlertMonitorBanner`.
* **Alerts evaluate on H1 only.** `AlertEvaluator.EVALUATION_TIMEFRAME` is fixed, because a
  per-alert timeframe column needs a Room migration, and no migration can be validated in this
  environment (`MigrationTestHelper` needs the exported schema JSON that only a real Gradle build
  produces). An M5 alert therefore fires on the hourly bar, not the five-minute one.
* **API keys live in the client.** See Security: the honest shape is a proxy issuing short-lived
  tokens, and this repository has no backend.
* **Feed provenance is in-memory only.** `dataOrigin` and the recorded provider per
  (pair, timeframe) live in a `ConcurrentHashMap`, so a process restart labels a still-valid cache
  hit `UNKNOWN` until the next fetch. Cached rows in SQLite carry no provider column — adding one is
  another migration this environment cannot test.
* **No notification silhouette icon.** The module has only `res/drawable/ic_launcher.xml`, so
  `AlertNotifier` uses it as the small icon; on some launchers that renders as a white square in the
  status bar. A monochrome `ic_stat_alert` is a one-file fix that needs a design asset.
* **Alpha Vantage cannot serve H4 or W1.** Its free FX endpoints are intraday (1/5/15/30/60 min) and
  daily only, so `TimeframeMapper.intervalFor` returns null and the client reports
  `AppError.Unsupported`; under `AUTO` the FCS feed covers those timeframes instead.
* The AI Analyst screen is a placeholder until an OpenRouter key is wired up.
* Without any key, market data is the seeded simulator (`PriceGenerator`), which is honest but not
  tradable — and FCS's own documentation says its prices "may differ from the actual market price"
  and are "not appropriate for trading purposes".
* Drawing objects can be created, selected, deleted, undone and cleared, but not **dragged** after
  placement; editing a level means deleting and redrawing it.
* `ChartGestureMath` — the thin Compose layer that turns a touch into a `ChartPoint` — is verified by
  inspection and through the pure layers it composes (`DrawingGeometry`, `ChartCoordinateSystem`),
  which are both unit-tested. It is not covered by a JVM test because that would put Compose on the
  unit-test classpath, and not by an instrumented test because the project has no UI-test harness.
* The chart's display choices live in the toolbar's **Display** panel, not in the Settings screen;
  the two surfaces would otherwise offer duplicate switches over the same preferences.
* `MIGRATION_1_2` (the `chart_drawings` table and its index) is hand-written DDL, verified by
  inspection against the entity. A `MigrationTestHelper` test needs the exported Room schema JSON for
  version 1 under `app/schemas/`, which only a real Gradle build produces (`room.schemaLocation` is
  already configured in `app/build.gradle.kts`); no build has been run in this environment, so the
  migration is not covered by an automated test yet. Fresh installs get the table from the generated
  schema and are unaffected. The `Build APK` workflow now uploads whatever `app/schemas/` a real build
  exports as the `ForexAnalyst-room-schemas` artifact — committing those JSON files is the last step
  needed to switch the migration test on.
* **Nothing in this repository has been compiled by the author of these notes.** The environment it was
  written in has no JDK, Gradle or Android SDK, so the 394 tests are unexecuted and every Kotlin file is
  unverified by `kotlinc`. The first CI run is the real review gate; the likeliest places to need a fix
  are the Compose call sites in `SettingsScreen` / `AlertsScreen` and the two `runTest` polling
  assertions in `AlertMonitorTest`.
