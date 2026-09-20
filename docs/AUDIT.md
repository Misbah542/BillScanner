# Audit of the original BillScanner app

State audited: commit `57dae66` ("first"), the only commit on the repo.

**Headline: the app could not build.** `app/build.gradle.kts` was a zero-byte
file, so nothing the README claimed the project used was ever declared. Beyond
that, the app was three screens wired to an API endpoint that did not exist.

---

## 1. Fatal — the project did not compile

| # | What | Evidence |
|---|---|---|
| 1 | **`app/build.gradle.kts` was completely empty (0 bytes).** No `plugins`, no `android { namespace, compileSdk, minSdk }`, no `dependencies`. Gradle sync fails immediately. Every library the README advertised (Compose BOM 2024.02.00, Hilt 2.48, Retrofit 2.9.0, CameraX 1.3.1, Navigation 2.7.7) was undeclared, while the Kotlin sources imported all of them. | `find . -type f -empty` |
| 2 | **Hilt Gradle plugin never applied.** Its classpath was added in the root `buildscript {}`, but `com.google.dagger.hilt.android` was never applied to the app module, and KSP/kapt was absent. `@HiltAndroidApp`, `@HiltViewModel` and `@AndroidEntryPoint` therefore generate nothing. | root `build.gradle.kts`, empty app module |
| 3 | **kotlinx-serialization plugin declared `apply false` and never applied.** Models used `@Serializable` / `@SerialName`, which needs the compiler plugin. | root `build.gradle.kts` vs `data/model/*.kt` |
| 4 | **`AndroidManifest.xml` referenced resources that did not exist:** `@xml/data_extraction_rules`, `@xml/backup_rules` — there was no `res/xml/` directory at all. Resource linking fails. | `res/` contained only `values/` |
| 5 | **No launcher icon.** The manifest pointed at `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`; no `mipmap-*` directory existed. Resource linking fails. | same |
| 6 | No `gradle/libs.versions.toml`, so versions could only ever be hardcoded. | — |

## 2. Runtime bugs that survive fixing the build

| # | What | Why it breaks |
|---|---|---|
| 7 | **Gson vs. kotlinx-serialization mismatch.** `NetworkModule` installed `GsonConverterFactory`, but `BillTotals` used `@SerialName("item_total")` etc. Gson ignores `@SerialName`, so *every* field of the totals object (`item_total`, `service_charge`, `state_gst`, `central_gst`, `round_off`, `net_amount`) would deserialize to `0.0`. The bill summary would silently render all zeros. | `di/NetworkModule.kt` vs `data/model/BillTotals.kt` |
| 8 | **API base URL was the placeholder** `https://your-api-base-url.com/api/`. Every request fails. | `di/NetworkModule.kt` |
| 9 | **Status-bar appearance flag inverted.** `isAppearanceLightStatusBars = darkTheme` — it should be `!darkTheme`. Dark theme got dark icons on a dark bar. | `ui/theme/Theme.kt` |
| 10 | **`dynamicColor = true` by default** overrode the entire custom palette on Android 12+, i.e. on most devices the designed brand colours never appeared. | `ui/theme/Theme.kt` |
| 11 | **`WRITE_EXTERNAL_STORAGE` with no `maxSdkVersion`** — a no-op since API 29 and a Play Console warning. `READ_EXTERNAL_STORAGE` was also requested but never used. | `AndroidManifest.xml` |
| 12 | **HTTP body logging at `Level.BODY` unconditionally**, including in release builds — leaks request/response contents to logcat. | `di/NetworkModule.kt` |

## 3. Silently swallowed errors

| # | What |
|---|---|
| 13 | `startCamera` — `catch (exc: Exception) { // Handle error }`. Camera bind failure shows a frozen black preview with no message. |
| 14 | `ImageCapture.OnImageSavedCallback.onError` — empty body. A failed capture does nothing at all; the user taps the shutter and nothing happens, forever. |
| 15 | `HomeScreen` — `uiState.error?.let { LaunchedEffect(error) { // Show error snackbar or dialog } }`. The error was plumbed all the way into UI state and then dropped. There was no `SnackbarHost` anywhere in the app, so **no error was ever visible to the user**. |

## 4. Missing whole layers

| # | What |
|---|---|
| 16 | **No backend.** The one endpoint the app called (`POST scan-bill`) existed only as a JSON sample in the README. |
| 17 | **No persistence.** No Room, no DataStore, no cache. A scanned bill lived in a `MutableStateFlow` and was gone on process death. No history — you could scan a bill and never see it again. |
| 18 | **No tests.** No `src/test/`, no `src/androidTest/`, no test dependencies. |
| 19 | **No auth.** No users, no accounts, no sessions. |
| 20 | **No CI**, no lint baseline, no formatter config. |
| 21 | **No `LICENSE` file**, although the README said "see the LICENSE file". |

## 5. Repo hygiene

| # | What |
|---|---|
| 22 | **No `.gitignore` at all.** |
| 23 | **`.gradle/` build cache was committed** — 16 files including `.lock` files and binary caches (`fileHashes.bin`, `executionHistory.bin`). |
| 24 | **`local.properties` was committed**, containing `sdk.dir=/Users/misbah.haque/Library/Android/sdk` — leaks a username and breaks the build for every other machine. |
| 25 | `README.md` and `PROJECT_STRUCTURE.md` documented dependencies, a working build and a "complete" structure that did not exist. `PROJECT_STRUCTURE.md` was essentially a duplicate of the README. |

## 6. Code quality / API drift

| # | What |
|---|---|
| 26 | **`strings.xml` was fully populated (27 strings) and never used once.** Every screen hardcoded English literals. No localisation was possible. |
| 27 | `Divider` is deprecated in favour of `HorizontalDivider` (Material3 1.2+). Used twice. |
| 28 | `LocalLifecycleOwner` imported from `androidx.compose.ui.platform`; it moved to `androidx.lifecycle.compose`. |
| 29 | `window.statusBarColor` is deprecated as of API 35 and a no-op with edge-to-edge, which `MainActivity` enables via `enableEdgeToEdge()`. The two fight each other. |
| 30 | `₹` hardcoded in six places instead of `NumberFormat.getCurrencyInstance`. |
| 31 | `HomeScreen`'s `viewModel` parameter defaulted to `hiltViewModel()` *and* was passed one from the navigation graph — two sources of truth for the same screen, easy to regress into two separate ViewModel instances. |
| 32 | `proguard-rules.pro` kept Gson rules for a Gson dependency that shouldn't be there, and `-keep class com.billscanner.data.model.**` referenced a package that did not exist (the real one was `com.billscanner.app.data.model`), so the rule matched nothing. |

## 7. Features in the brief that had no code whatsoever

Groups · categories & auto-suggestion · SMS/bank-alert detection · notifications
when the app is closed · bill splitting (equal, unequal, percentage) · adding
people by email or phone · sharing a bill · settling up · authentication.

---

## What replaced it

See [`ARCHITECTURE.md`](./ARCHITECTURE.md). In short: the repo is now an npm-workspaces
monorepo — `apps/api` (Node/Express/Prisma/Postgres), `apps/android` (the rebuilt app,
renamed **SnapTab**), and `packages/shared` (the money, split and classification rules
both sides agree on). Every item above is addressed; the numbered items map to commits
on the `claude/youthful-cray-g69qm5` branch.
