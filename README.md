# Forex-Analyst

Offline-first **Android** trading terminal: candlestick charts, technical indicators
(EMA / RSI / MACD / ATR), rule-based trade signals, price alerts and a watchlist.

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
  viewmodel/   ForexViewModel, SettingsViewModel             (constructor-injected, no Android deps in logic)
data/
  model/       Domain types: CandleStick, TradeSignal, CustomAlert,
               value-class ids (SignalId/AlertId = UUID, PairSymbol, Timeframe),
               Result<T> / AppError envelope
  settings/    AppSettings (immutable snapshot), ThemeMode, IndicatorFormValidator,
               SettingsRepository (EncryptedSharedPreferences)
  repository/  MarketDataRepository (interface) -> ForexRepository (Room impl),
               TechnicalAnalysisEngine (pure math), PriceGenerator (seeded simulator)
  db/          Room entities, DAOs, Converters, ForexDatabase
```

**Dependency rule:** `ui` → `data.repository` (interface) → `data.db`. The UI never touches Room
types; the repository maps entities to domain models and classifies every failure into an
`AppError`, so no internal exception text can reach a screen.

### Data flow

1. `ForexViewModel.loadCandles()` → `MarketDataRepository.fetchHistoricalCandles(pair, timeframe)`.
2. The repository checks the SQLite cache under a `Mutex` (one query serves both the data and the
   freshness check), generating and caching candles on a miss, then pruning rows older than 30 days.
3. Indicator math runs on `Dispatchers.Default` (CPU-bound), I/O stays on Room's own executor.
4. `TechnicalAnalysisEngine.generateSignals()` produces at most one signal per candle; identity is a
   SHA-256 content fingerprint, so re-scans are **idempotent** (`UNIQUE` index + `IGNORE`).
5. Room `Flow`s push bounded result sets (`LIMIT`) back to the UI — no polling, no unbounded lists.

## Correctness invariants (enforced in `init` blocks)

| Type | Invariant |
|---|---|
| `CandleStick` | prices finite, `high >= low`, `high >= max(open, close)`, `low <= min(open, close)`, `volume >= 0` |
| `TradeSignal` | prices finite, `confidenceScore ∈ [0, 1]`, `riskRewardRatio` finite and `>= 0` |
| `CustomAlert` | `targetValue` finite (rejects `NaN`), `note <= 500` chars |
| `IndicatorSettings` | periods in range, EMA periods ascending, `macdFast < macdSlow` |
| `PairSymbol` | non-blank; `PairSymbol.of()` normalizes case/whitespace |
| `Timeframe` | one of `Timeframe.ORDERED` (M1, M5, M15, H1, H4, D1, W1) |

Indicator series are **index-aligned** with the candle list they were computed from: `values[i]`
belongs to `candles[i]`, with leading `null`s until the first computable bar. The chart canvas
depends on this contract.

## Security

* API keys are stored in `EncryptedSharedPreferences` (Android Keystore, AES-256-GCM) with a
  one-time migration from the previous plaintext file, which is then cleared.
* `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` exclude both settings files
  from cloud backup and device-to-device transfer.
* OkHttp's logging interceptor is a **debug-only** dependency, so release builds cannot log
  credential-bearing headers.
* Errors shown to users come from `AppError.userMessage`; the underlying `Throwable` is logged only.
* Release builds run R8 with `app/proguard-rules.pro` (Gson reflection keep rules included — without
  them obfuscation silently resets settings on every launch).

## Build & test

```bash
./gradlew :app:assembleDebug                 # build
./gradlew :app:testDebugUnitTest             # JVM unit tests (engine, generator, models, validator, ViewModel)
./gradlew :app:connectedDebugAndroidTest     # Room integration test on a device/emulator
./gradlew :app:lintDebug                     # static analysis
```

| Suite | Covers |
|---|---|
| `TechnicalAnalysisEngineTest` | EMA/RSI/MACD/ATR alignment, hand-computed values, BUY/SELL/no-signal conditions |
| `PriceGeneratorTest` | seeded determinism, clock injection, timestamp spacing, input validation |
| `TradeSignalFingerprintTest` | SHA-256 identity, 1 000-bar collision check, UUID ids, symbol normalization |
| `DomainModelValidationTest` | every `init` invariant, `Result`/`AppError` classification, no message leaks |
| `IndicatorFormValidatorTest` | parse/coerce/cross-field rules and actionable messages |
| `ForexViewModelTest` | load success/failure, timeframe & pair switching, stream resubscription, error surfacing (uses `FakeMarketDataRepository`) |
| `ForexRepositoryIntegrationTest` | real SQLite: round-trips, idempotent saves, corrupt-row tolerance, cache TTL, per-timeframe isolation, pruning |

Every non-obvious change in the codebase carries an inline `// WHY:` comment explaining the
production risk it removes.

## Known limitations

* Market data is **simulated** (`PriceGenerator`). Retrofit/OkHttp are declared for the live
  FCS/Alpha Vantage integration, which is not implemented yet; the AI Analyst screen is a
  placeholder until an OpenRouter key is wired up.
* Alert *evaluation* (checking live prices against stored alerts and posting notifications) is not
  implemented — alerts are persisted and listed only.
* `MACDResult` is computed and cached per load but has no dedicated sub-panel yet (RSI does).
