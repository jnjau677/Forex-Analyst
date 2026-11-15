# Forex-Analyst

Offline-first **Android** trading terminal: candlestick charts with **71 instruments** across FX,
metals, energy, indices and crypto, an **11-tool drawing layer** (trend lines, Fibonacci, position
planners, measurements) with undo/redo, a **time-zone-aware axis**, technical indicators
(EMA / SMA / Bollinger / VWAP / RSI / MACD / Stochastic / ATR), rule-based trade signals, price
alerts and a watchlist.

> **Stack (specified, not assumed):** Jetpack Compose UI · Kotlin 1.9.20 · Coroutines + Flow ·
> Room 2.6.1 over **SQLite** · Gradle Kotlin DSL (AGP 8.2.0) · `compileSdk` 34 / `minSdk` 26.
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
data/
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
               TechnicalAnalysisEngine (pure math), PriceGenerator (seeded simulator)
  db/          Room entities, DAOs, Converters, ForexDatabase (v2 + migrations)
```

**Dependency rule:** `ui` → `data.repository` (interface) → `data.db`. The UI never touches Room
types; the repository maps entities to domain models and classifies every failure into an
`AppError`, so no internal exception text can reach a screen.

**Why `data/chart` exists separately from `ui/chart`:** everything that decides *where a pixel goes*
— coordinate mapping, axis ticks, layout, drawing geometry, the undo store — is pure Kotlin with no
Compose or Android import, so it runs as a JVM unit test in milliseconds. `ui/chart` only paints the
result and converts touches. A renderer bug is visible; a geometry bug is not, and the geometry is
where the tests are.

### Data flow

1. `ForexViewModel.loadCandles()` → `MarketDataRepository.fetchHistoricalCandles(pair, timeframe)`.
2. The repository checks the SQLite cache under a `Mutex` (one query serves both the data and the
   freshness check), generating and caching candles on a miss, then pruning rows older than 30 days.
3. Indicator math runs on `Dispatchers.Default` (CPU-bound), I/O stays on Room's own executor.
4. `TechnicalAnalysisEngine.generateSignals()` produces at most one signal per candle; identity is a
   SHA-256 content fingerprint, so re-scans are **idempotent** (`UNIQUE` index + `IGNORE`).
5. Room `Flow`s push bounded result sets (`LIMIT`) back to the UI — no polling, no unbounded lists.
6. Chart state is published as **one** `ChartUiState` snapshot (candles + indicators + config +
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
* The chart ViewModel receives a `ChartSettingsWriter` — a one-method interface — instead of the
  whole `SettingsRepository`, so chart code (and its tests) cannot read the stored API keys.
* `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` exclude both settings files
  from cloud backup and device-to-device transfer.
* OkHttp's logging interceptor is a **debug-only** dependency, so release builds cannot log
  credential-bearing headers.
* Errors shown to users come from `AppError.userMessage`; the underlying `Throwable` is logged only.
* Release builds run R8 with `app/proguard-rules.pro` (Gson reflection keep rules included — without
  them obfuscation silently resets settings on every launch). Every persisted enum also pins its wire
  spelling with `@SerializedName`, and `ChartConfigTest` fails if one is renamed.

## Accessibility

* The canvas is opaque to TalkBack, so the chart carries a `semantics { contentDescription = … }`
  built from the same values the visible readout shows, plus an always-on `ChartReadoutBar` of real
  selectable text (the in-canvas tooltip only exists while a finger is down).
* Tool and palette labels are text, not icon-only (the module deliberately has no
  `material-icons-extended` dependency, so there is no icon to guess at), and every palette swatch
  carries its name on the `Swatch` itself so a reorder cannot mislabel the colours.

## Build & test

```bash
./gradlew :app:assembleDebug                 # build
./gradlew :app:testDebugUnitTest             # JVM unit tests (engine, chart maths, models, ViewModel)
./gradlew :app:connectedDebugAndroidTest     # Room integration test on a device/emulator
./gradlew :app:lintDebug                     # static analysis
```

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
| `ForexRepositoryIntegrationTest` | real SQLite: round-trips, idempotent saves, corrupt-row tolerance, cache TTL, per-timeframe isolation, pruning, and all 8 drawing subtypes through the real DAO (scoping, transactional replace, bounded ordering) |

ViewModel tests share one barrier helper (`TestAwait.awaitSettled`): `viewModelScope` runs on the
test dispatcher, but the indicator math is handed to `Dispatchers.Default` — a real thread `runTest`
does not wait for. Without the barrier those assertions would race a background thread and fail
intermittently on CI only.

Every non-obvious change in the codebase carries an inline `// WHY:` comment explaining the
production risk it removes.

## Known limitations

* Market data is **simulated** (`PriceGenerator`), seeded per instrument from `InstrumentCatalog` so
  each market moves at a plausible scale. Retrofit/OkHttp are declared for the live FCS/Alpha Vantage
  integration, which is not implemented yet; the AI Analyst screen is a placeholder until an
  OpenRouter key is wired up.
* Alert *evaluation* (checking live prices against stored alerts and posting notifications) is not
  implemented — alerts are persisted and listed only.
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
  schema and are unaffected.
