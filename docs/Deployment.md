# Deploying Forex-Analyst

Forex-Analyst is a **client-only Android app**: no server, no database service, no API of its own.
"Deploy" therefore means one of three targets, and they share the first four sections of this
document.

| Target | Artifact | Audience | Section |
|---|---|---|---|
| A — one device you control | `app-debug.apk` | you | [§4](#4-path-a--build-and-install-on-one-device) |
| B — a group of testers | release-signed `.apk` | friends, beta list | [§5](#5-path-b--signing-then-distributing-to-testers) |
| C — Google Play | `app-release.aab` | public | [§6](#6-path-c--google-play) |

---

## 1. Pre-flight blockers

Both of these were found by reading the repository rather than by building it, and both would have
cost a deployer an afternoon.

**1.1 `app/src/main/res/res_compose/` was not a valid resource directory — removed.**
Android resource directories must be named `<type>[-qualifier]` (`values`, `drawable`,
`mipmap-anydpi-v26`, …). `res_compose` is none of those, so aapt2 rejects it during resource
linking — and if a toolchain version instead *accepted* it, the outcome would have been worse: it
declared `<string name="app_name">appName</string>`, colliding with the real `app_name` in
`res/values/strings.xml` ("Forex Analyst") and putting the literal text **"appName"** under the
launcher icon. Its `AppTheme` style was dead code — the manifest uses `Theme.ForexAnalyst`, and
nothing anywhere referenced `AppTheme`. The directory is deleted; it had no unique content.

**1.2 There is still no adaptive launcher icon.**
`res/` contains only `drawable/ic_launcher.xml` (a 108dp `<vector>`) and `ic_launcher_round.xml`.
There is no `mipmap-anydpi-v26/ic_launcher.xml` with `<background>`/`<foreground>`, and no bitmap
mipmaps at all. On API 26+ launchers the vector is treated as a *legacy* icon and letterboxed inside
a generated shim, so it looks wrong next to every other app, and some launchers rasterise vector
launcher icons poorly. The existing vector is already drawn on the 108×108 adaptive canvas, so
splitting it into `ic_launcher_background.xml` + `ic_launcher_foreground.xml` (respecting the 72dp
safe zone) and adding the `mipmap-anydpi-v26` wrapper is a small, mechanical fix. **Not done yet.**

**Also worth knowing, not blocking:**

* **No notification silhouette.** `AlertNotifier` uses `R.drawable.ic_launcher` as the small icon,
  which renders as a white square in the status bar on most OEMs. A monochrome `ic_stat_alert` is a
  one-file fix that needs a design asset.
* **`MIGRATION_1_2` has never run against a real version-1 database.** It is hand-written DDL
  verified by inspection. This only matters for an **update** release over an existing install — a
  fresh install gets the table from the generated schema. The `ForexAnalyst-room-schemas` CI artifact
  is what you need to commit in order to switch `MigrationTestHelper` on; do that before shipping
  version 2 to anyone who has version 1.
* **Nothing in this repository has been compiled by the people who wrote it.** The environment it was
  authored in has no JDK, no Gradle and no Android SDK. Expect the first build to surface errors and
  budget a fix-up cycle rather than a same-day release.
* **Google Play's target-API requirement.** This app targets API 34. Play raises the required target
  level on a rolling annual schedule that moves faster than this document does. **Check the current
  deadline in Play Console before planning a Play release.** If API 35 is required, `compileSdk` and
  `targetSdk` must move to 35, which needs AGP 8.6+ and Gradle 8.7+ (this project is on AGP 8.2.0 /
  Gradle 8.13) — a real upgrade, not a one-line change.

---

## 2. Prerequisites

| | |
|---|---|
| JDK | **17** — `compileOptions` and `jvmTarget` are pinned to 17, and AGP 8.2.0 refuses JDK 11. There is no `jvmToolchain` block, so Gradle builds with whatever JVM launches it. |
| Android SDK | Platform **34** (`compileSdk`), build-tools 34.x, `minSdk` 26 |
| Gradle | Wrapper, **8.13** — `./gradlew`, committed at mode `100755` |
| Identity | `applicationId com.devcode940.ForexAnalyst`, `versionCode 1`, `versionName "1.0.0"` (`app/build.gradle.kts`) |
| Licence | Eclipse Public License 2.0 |

Android Studio: *Settings → Build Tools → Gradle → Gradle JDK* → **embedded JBR 17**.
Command line: `JAVA_HOME` → Temurin 17.

JDK 21 will run the build but changes nothing, since the bytecode target is still 17. `minSdk 26` and
`compileSdk 34` are Android API levels, not Java versions — and because `minSdk` is 26, the
`java.time` usage throughout the data layer needs no core-library desugaring.

---

## 3. Building on-device (AndroidIDE, Termux)

This project was first built in **AndroidIDE**, and on-device builds fail in ways a desktop never
does. If you are building on a phone, read this section before you read the error message.

### 3.1 Use the wrapper, not a bare `gradle`

The repository already ships a wrapper pinned to Gradle 8.13 (`gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.jar` and `gradle-wrapper.properties`). Running a `gradle` that
happens to be on `PATH` uses a **different Gradle version and possibly a different JVM** than the
project was written against — and the two are not interchangeable. `gradle wrapper` is only for
*creating* a wrapper in a project that lacks one; this project has one.

### 3.2 `/storage/...` cannot hold an executable wrapper

Shared storage on Android (`/storage/emulated/0`, `/storage/internal_new`, …) is FUSE-emulated and
typically **does not honour the executable bit**. A clone there gives `./gradlew` → *Permission
denied* even though the mode is `100755` in Git, and `chmod +x` silently fails to stick. Gradle's
daemon also relies on file locking, which behaves badly on that filesystem.

Work around it in this order of preference:

1. Put the project in **app-private storage** (`~/project/Forex-Analyst` in Termux/AndroidIDE), where
   the exec bit and file locks work normally.
2. Or invoke the wrapper through a shell, which needs no exec bit: `sh gradlew :app:assembleDebug`.
3. Or let AndroidIDE drive the build — it uses its own bundled Gradle and JDK and never touches
   `gradlew`.

### 3.3 `android.aapt2FromMavenOverride` belongs to your machine, not the repo

`gradle.properties` deliberately does **not** set it. It used to point at `/usr/bin/aapt2`, a path
that exists only inside AndroidIDE; because a *project-level* property is read by every machine that
opens the repository, Android Studio, the CLI and CI would all die during resource linking with
*"Cannot run program /usr/bin/aapt2 … No such file or directory"* before Gradle reached a line of
Kotlin. If you build with AndroidIDE, put the override in your **user-level**
`~/.gradle/gradle.properties`:

```properties
android.aapt2FromMavenOverride=/usr/bin/aapt2
```

### 3.4 `foojay-resolver-convention` cannot resolve / "Cannot choose between javadocElements and sourcesElements"

Symptom, verbatim from an on-device build:

```
Could not resolve all artifacts for configuration 'classpath'.
> Could not resolve org.gradle.toolchains:foojay-resolver:1.0.0.
  Required by:
      unspecified:unspecified:unspecified > org.gradle.toolchains.foojay-resolver-convention:…:1.0.0
   > Cannot choose between the following variants of org.gradle.toolchains:foojay-resolver:1.0.0:
       - javadocElements
       - sourcesElements
     …
       - Required org.gradle.jvm.version '8' but no value provided.
```

Three separate facts, each independently useful:

* **This project does not use that plugin.** `settings.gradle.kts` here has no `plugins {}` block at
  all, and the string `foojay` appears nowhere in the repository. So the `settings.gradle.kts` being
  evaluated is **not this project's** — the directory is an IDE-generated skeleton, or the sources
  were copied into a different project. `ls` the folder and `cat settings.gradle.kts` before
  debugging anything else.
* **`unspecified:unspecified:unspecified` means the failure is in the *settings* script's classpath**,
  resolved before any project exists. Only a settings plugin (`foojay-resolver-convention` is one)
  produces that. It is not a dependency of the app.
* **`Required org.gradle.jvm.version '8'` means Gradle is running on JDK 8.** Gradle derives that
  attribute from the JVM it is executing in. `foojay-resolver` publishes jar variants targeting a
  newer JVM, so those are discarded as incompatible and only the *documentation* variants (which
  carry no `jvm.version`) survive — two of them, equally, hence "cannot choose". The accompanying
  *"2 incompatible and 1 stopped Daemons could not be reused"* corroborates it: several different
  Gradle/JVM combinations have run in that environment.

Fixes, in order:

1. **Point Gradle at JDK 17** — in AndroidIDE: *Settings → Gradle → JDK*; in a shell:
   `export JAVA_HOME=<jdk-17>` then `gradle --version` and check the `JVM:` line reads 17.
2. **Delete the plugin.** It exists to auto-provision JDKs via the Foojay API. This project does not
   use Gradle toolchain auto-provisioning — there is no `jvmToolchain` block, and it compiles with
   whatever JVM runs Gradle. Removing
   `id("org.gradle.toolchains.foojay-resolver-convention") version "…"` from `settings.gradle.kts`
   removes the whole failure mode.
3. **Use the real project**, which has neither the plugin nor a reason to add it.

### 3.5 The wrapper's Gradle download aborts part-way (`SSLException: Software caused connection abort`)

Symptom, on the first build in a clean environment:

```
Downloading https://services.gradle.org/distributions/gradle-8.13-bin.zip
.............10%.............20%.........
Exception in thread "main" javax.net.ssl.SSLException: Connection has been shutdown:
  javax.net.ssl.SSLException: java.net.SocketException: Software caused connection abort (Read failed)
    at sun.security.ssl.Alerts.getSSLException(Alerts.java:208)
    at sun.security.ssl.SSLSocketImpl$AppInputStream.available(SSLSocketImpl.java:60)
    at sun.security.ssl.InputRecord.readV3Record(InputRecord.java:593)
    at org.gradle.wrapper.Download.downloadInternal(Download.java:84)
```

Read three things out of that stack, in this order:

**1. The JVM running the wrapper is JDK 8, and that is a separate, fatal problem.**
`sun.security.ssl.Alerts` (plural), `SSLSocketImpl$AppInputStream` and `InputRecord.readV3Record` are
JDK 8 internals — JDK 11 rewrote `SSLSocketImpl` on top of `SSLEngine` and has no `AppInputStream` at
all. So even if this download completed, **AGP 8.2.0 would refuse to run**: it requires JDK 17, which
is also what `compileOptions` and `jvmTarget` are pinned to (§2). This is the same JDK 8 that produced
`Required org.gradle.jvm.version '8'` in §3.4.

Check and fix before anything else:

```bash
java -version                      # expect 17.x, not 1.8.x
./gradlew --version                # the "JVM:" line is what Gradle will actually use
```

Then point Gradle at a JDK 17 by whichever route your environment allows — the IDE's *Gradle JDK*
setting, `export JAVA_HOME=<jdk-17>` before invoking the wrapper, or `org.gradle.java.home=<jdk-17>`
in your **user-level** `~/.gradle/gradle.properties`. Do not put that last one in the repository's
`gradle.properties`: it is a machine-specific path, for exactly the reason §3.3 exists.

An old JDK 8 TLS stack is also a plausible *cause* of the abort itself — a long-lived HTTPS download
negotiated with weak defaults gets reset by a modern CDN. Fixing the JVM may fix the network symptom
as a side effect, so do it first and re-test.

**2. The wrapper does not resume.** `org.gradle.wrapper.Download` streams into a temporary part file
and restarts from byte 0 on every attempt. A 130 MB distribution over mobile data that drops at 20%
will therefore loop forever, and each failed attempt can leave debris behind:

```bash
rm -rf ~/.gradle/wrapper/dists/gradle-8.13-bin
```

That removes the partial zip and the `.lck` file from `ExclusiveFileAccessManager` (visible in the
stack above). A stale lock from a killed download makes the next attempt hang or fail instantly rather
than retry — so clear it before retrying, and do not retry in a tight loop.

**3. Fetch the distribution out of band, with resume, then point the wrapper at the file.**

```bash
curl -L -C - --retry 5 --retry-delay 10   -o ~/gradle-8.13-bin.zip   https://services.gradle.org/distributions/gradle-8.13-bin.zip
```

`-C -` resumes where the last attempt stopped, which is the one thing the wrapper cannot do. Then edit
`gradle/wrapper/gradle-wrapper.properties` **locally, and do not commit the change** — a `file:` URL
is a machine-specific path, same rule as §3.3:

```properties
distributionUrl=file\:/absolute/path/to/gradle-8.13-bin.zip
```

(The colon must be escaped: `.properties` treats an unescaped `:` as a key/value separator.)

Verify the archive against the published SHA-256 at `https://gradle.org/release-checksums/` before
pointing Gradle at it. A truncated zip does not fail cleanly — it surfaces much later as a confusing
unzip or "Could not install Gradle distribution" error, with the real cause invisible.

**Or skip the wrapper download entirely:** AndroidIDE ships its own Gradle and JDK 17. If your IDE can
build with its bundled distribution instead of the one named in `gradle-wrapper.properties`, that is
the fastest path on a device — no 130 MB download, and no JDK 8.

**Environment, because this is a phone:** use Wi-Fi rather than mobile data; keep the screen on and the
device charging, since Doze kills idle sockets mid-download; and check space first with `df -h`. The
distribution is ~130 MB compressed and ~450 MB extracted, *before* the Android SDK, the dependency
cache and the build outputs — running out of space part-way can present as a connection abort rather
than as an I/O error.

### 3.6 `FAIL_ON_PROJECT_REPOS`

`settings.gradle.kts` sets `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, so any `repositories { … }`
block inside a module's `build.gradle.kts` **fails the build on purpose** — repositories are declared
once, centrally. If you merge IDE-template build scripts into this project, expect that error and
delete the module-level block rather than relaxing the mode.

---

## 4. Path A — build and install on one device

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the device and tap it (Settings → allow install from unknown sources). The debug
APK is signed with the throwaway debug keystore: fine for you, **not** for distribution — it expires,
differs per machine, and nobody else's build can update it.

No local toolchain? `ci/build-apk.yml` is staged in this repository and, once moved to
`.github/workflows/`, publishes `ForexAnalyst-apks.zip` as a downloadable artifact on every run — see
the README's *Getting an APK without an Android toolchain*.

---

## 5. Path B — signing, then distributing to testers

### 5.1 Create the key once, and back it up

```bash
keytool -genkeypair -v -keystore forex-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias forex
```

**This file is the app's identity.** Lose it and you can never publish an update; leak it and anyone
can publish one. Keep it in a password manager's file attachment or an offline backup, and never
commit it — `*.jks`, `*.keystore`, `keystore.properties` and `signing.properties` are gitignored.

### 5.2 Wire it into the build

`~/.gradle/gradle.properties` (user level, outside the repository):

```properties
forexStoreFile=/absolute/path/to/forex-release.jks
forexStorePassword=…
forexKeyAlias=forex
forexKeyPassword=…
```

`./gradlew :app:assembleRelease` then produces a signed
`app/build/outputs/apk/release/app-release.apk`. If any value is missing, the build logs a warning
naming it and emits `app-release-unsigned.apk` instead — which **no launcher will install**, so check
the filename before sending it to anyone.

### 5.3 CI signing

Add four repository secrets: `FOREX_KEYSTORE_BASE64` (`base64 -w0 forex-release.jks`),
`FOREX_STORE_PASSWORD`, `FOREX_KEY_ALIAS`, `FOREX_KEY_PASSWORD`. The workflow decodes the keystore
into `$RUNNER_TEMP`, outside the checkout, so no artifact glob can pick it up.

### 5.4 Distributing

* **GitHub Releases:** `gh release create v1.0.0 app-release.apk --title "Forex Analyst 1.0.0"`.
* **Firebase App Distribution** or any private link: upload the release-signed APK.
* **Retain `mapping.txt` for every release.** Release builds are minified, so a crash trace from the
  field is unreadable without the mapping from that exact build, and it cannot be regenerated
  afterwards. The CI workflow uploads it as `ForexAnalyst-release-mapping`. Never publish it.

---

## 6. Path C — Google Play

1. **Build an AAB, not an APK:** `./gradlew :app:bundleRelease` →
   `app/build/outputs/bundle/release/app-release.aab`. Play requires AAB for new apps. The same
   signing configuration in §5 applies.
2. **Enrol in Play App Signing.** You keep an *upload* key; Google holds the *signing* key. For a
   solo developer this is the right trade — a lost upload key is recoverable, a lost signing key is
   not.
3. **Create the app** in Play Console: name *Forex Analyst*, default language, free, category
   Finance.
4. **Store assets this repository does not have:** a **512×512 PNG** icon and a **1024×500** feature
   graphic (the vector launcher icon is not a substitute — Play needs bitmaps), plus at least two
   screenshots.
5. **Complete the required forms:** Data safety, Content rating (IARC), Privacy policy URL, Target
   audience, Ads declaration. For Data safety, accurately: the app collects no personal data and has
   no analytics or crash reporting; it stores user-supplied API keys in `EncryptedSharedPreferences`
   on-device; it sends market-data requests to `fcsapi.com` and `alphavantage.co` using the user's own
   key; both settings files and the quota counters are excluded from backup.
6. **Financial-content honesty.** The listing and the in-app About screen should carry a clear "not
   financial advice" disclaimer. The FCS feed this app uses documents its own data as *"for
   informational and educational purposes only… not appropriate for trading purposes"* and states that
   prices *"may differ from the actual market price"*. Quoting that verbatim is both accurate and
   protective.
7. **Release track:** Internal testing → Closed → Production, with staged rollout (10% → 50% → 100%)
   so a bad build reaches few people first.

---

## 7. Versioning discipline

* Bump `versionCode` (integer, must strictly increase) and `versionName` (human) in
  `app/build.gradle.kts` for **every** release. Play rejects a non-increasing `versionCode`, and
  sideloading silently refuses to install over a higher one.
* **A downgrade costs the user their data.** `versionCode` can only go up, and uninstall/reinstall
  wipes the Room database (watchlist, alerts, drawings, signals) and the encrypted settings — which
  are deliberately excluded from backup, so nothing restores them. There is no downgrade path; fix
  forward.
* Tag the release (`git tag v1.0.0`) and keep the matching `mapping.txt`.

---

## 8. Post-deploy smoke test (release build only)

Run these against the **release** APK, not debug. R8 changes behaviour, and the first item has a
silent, total-failure mode in this codebase.

1. **Settings and API keys survive a restart.** `SettingsRepository` serialises `AppSettings` with
   Gson, which resolves fields **reflectively by name**. If the `-keepclassmembers` rules in
   `app/proguard-rules.pro` ever regress, R8 renames the fields and *every release install silently
   loses its settings and API keys on first load* — no crash, just defaults. Paste a key, kill the
   process, reopen, confirm the key is still there.
2. **Chart style, theme and time zone round-trip.** Same Gson path, same failure mode.
3. **The provenance badge reads correctly.** With no key: *Simulated*, and the Alerts screen says
   monitoring is waiting for a live feed. With a key: the feed's name, and `Cached` on the second
   visit.
4. **Alerts fire on live data and never on simulated data**, and respect `notificationsEnabled` /
   `alertNotifications` / `soundEnabled` / `vibrationEnabled`. Revoking the notification permission in
   system settings must take effect without an app restart.
5. **Quota counters and "Reset counters"** in Settings → API keys.
6. **Drawings persist** across a process kill, and undo/redo still restores a whole chart.
7. **Cold start at `minSdk` 26** (Android 8.0) — the lowest supported level, and the one most likely
   to expose a `java.time` or API-level assumption.

---

## 9. Rollback

* **Play:** halt the staged rollout, or promote the previous release under a higher `versionCode`.
  Users already on the bad build keep it until you ship a fix — another reason to fix forward.
* **Sideloaded:** there is no rollback. Distributing an APK is not reversible; a user must uninstall
  (losing all local data) to go back.

---

## 10. Constraints a deployer should not be surprised by

* **Alerts only work while the app is in the foreground.** `AlertMonitor` is an in-process loop by
  design — WorkManager's 15-minute periodic floor would spend a month of FCS free credits in under two
  days. No alerts after the app is closed.
* **Alerts evaluate on H1 only**, so an M5 alert fires on the hourly bar.
* **API keys ship inside the client.** Anyone who unpacks the APK can read them. The honest shape for
  a public release is a short proxy that holds the key server-side and issues short-lived tokens; that
  backend does not exist here. If you distribute this app publicly, tell users to use a **free-tier
  key they do not mind exposing**, or build the proxy first.
* **Alpha Vantage cannot serve H4 or W1**; under `AUTO` the FCS feed covers those timeframes.
* **No crash reporting and no analytics**, so field failures are invisible unless a user reports them.
  The retained `mapping.txt` is what makes their stack trace readable.
