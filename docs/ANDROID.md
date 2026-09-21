# The Android app

Kotlin, Jetpack Compose, one module. `apps/android` is a Gradle build, not an npm
workspace, so it is opened and built on its own — the rest of the monorepo touches
it only through `npm run sync:assets`, which copies the shared rule files into its
assets.

- [Building it](#building-it)
- [Demo flavour](#demo-flavour)
- [Screens](#screens)
- [Layout](#layout)
- [Stack](#stack)
- [Money on the client](#money-on-the-client)
- [Reading bank alerts](#reading-bank-alerts)
- [The icon](#the-icon)
- [Tests](#tests)
- [Things worth knowing](#things-worth-knowing)

## Building it

Needs **JDK 17** and the Android SDK (compileSdk 35, minSdk 26). Android Studio
Ladybug or newer.

```bash
cp apps/android/local.properties.example apps/android/local.properties
# set sdk.dir; Android Studio writes it for you on first sync
cd apps/android
./gradlew installLiveDebug      # the real app, against your API
./gradlew installDemoDebug      # the fake one, no server needed
```

`snaptab.apiBaseUrl` in `local.properties` points the app at your API and defaults
to `http://10.0.2.2:4000/`, which is the host machine as seen from the emulator.
`localhost` inside the emulator is the emulator itself, which is the mistake
everybody makes once.

On a physical device on the same wi-fi, use your machine's LAN address
(`http://192.168.1.x:4000/`) and add it to the cleartext allowances in
`src/debug/res/xml/network_security_config.xml` — only loopback is permitted out of
the box. Or skip that and use `adb reverse tcp:4000 tcp:4000`, which makes
`127.0.0.1:4000` on the phone reach your machine and is already allowed.

Check the phone is visible with `adb devices` before installing. A device listed as
`unauthorized` is waiting for you to accept the USB-debugging prompt on its screen.

## Demo flavour

Two product flavours on the `backend` dimension:

| | `demo` | `live` |
| --- | --- | --- |
| API | `FakeSnapTabApi`, in memory | Retrofit against your server |
| Application id | `com.snaptab.app.demo` | `com.snaptab.app` |
| Label | SnapTab Demo | SnapTab |
| Icon | clay | teal |
| SMS permissions | removed from the manifest | requested when you enable the feature |

The demo exists so the UI could be built and reviewed before the backend was
deployed, and it is now the build to hand to someone who just wants to see the app.
`FakeSnapTabApi` is not a stub that returns fixtures — it keeps state, so the app
behaves:

- Creating, splitting and settling all mutate the seeded data and the screens update.
- Splits go through the same `Money.apportion` the server uses, so the arithmetic on
  screen is the real arithmetic.
- A scan steps `QUEUED → PROCESSING → SUCCEEDED` across successive polls, so the
  progress screen has something to show.
- Failures come back in the real `{ error: { code, message } }` envelope, so error
  handling is exercised rather than bypassed.
- Every call waits 280ms, because a UI that never sees a loading state is a UI whose
  loading states are broken.
- `/auth/google` returns 501, exactly as a server with no `GOOGLE_CLIENT_IDS` does.

The seeded data is in `data/remote/fake/DemoData.kt`. Names are invented and the
email addresses are all `@example.com`, a domain RFC 2606 reserves so it can never
belong to anyone.

**The demo has no SMS permissions at all.** `src/demo/AndroidManifest.xml` removes
`RECEIVE_SMS`, `READ_SMS` and the receiver with `tools:node="remove"`. Not a runtime
toggle — they are absent from the APK, so the OS would refuse them. The bank alerts
in its inbox are sample data, and the settings screen says so in place of the
toggles (`BuildFlags.smsReadingAvailable` is the single flag that decides).

See [CI.md](CI.md#getting-the-demo-apk) for downloading a demo APK that CI built.

## Screens

| Route | Screen | What it is for |
| --- | --- | --- |
| `sign_in` | `SignInScreen` | Email, phone or Google. No passwords. |
| `verify` | `VerifyScreen` | Six-digit code, with resend countdown. |
| `home` | `HomeScreen` | Balances, this month's spend, recent expenses. Lens switches All / Personal / Your share. |
| `groups` | `GroupsScreen` | Your groups and your net position in each. |
| `group/{id}` | `GroupDetailScreen` | Members, activity, per-person balances. |
| `inbox` | `InboxScreen` | Bank alerts waiting to be filed. |
| — | `LogAlertSheet` | "Where does ₹486 go?" — personal, or an existing group. |
| `settle` | `SettleScreen` | Who owes whom, and the fewest transfers that clear it. |
| `scan` | `ScanFlow` → Camera / Processing / Review | Capture, upload, wait, check the parse. |
| `add_expense` | `AddExpenseScreen` | A personal expense by hand, no group involved. |
| `monthly` | `MonthlyScreen` | The month: total, categories, personal vs share, what you fronted. |
| `expense/{id}` | `ExpenseDetailScreen` | Items, tax, shares, receipt, linked alert. |
| `split/{id}` | `SplitScreen` | Equal, unequal, percent, shares, or by item. |
| `profile` | `ProfileScreen` | Identity, SMS settings, preferences, sign out. |

Four bottom tabs — Home, Groups, Inbox, Settle — with the scan FAB in the centre.
Notifications deep-link straight to the alert or expense they are about.

## Layout

```
app/src/
├── main/java/com/snaptab/app/
│   ├── core/           Money, BuildFlags, ApiResult, StartupTask — no Android deps
│   ├── data/
│   │   ├── local/      Room entities and DAOs, DataStore token store
│   │   ├── remote/     Retrofit interface and DTOs
│   │   │   └── fake/   FakeSnapTabApi + DemoData (compiled into both flavours)
│   │   ├── repository/ One per resource; the only thing ViewModels talk to
│   │   └── sms/        BankAlertParser, driven by the shared rule JSON
│   ├── di/             Hilt modules
│   ├── navigation/     Routes, NavHost, deep links
│   ├── notifications/  Channel setup, the alert notification and its actions
│   ├── sms/            SmsReceiver
│   ├── ui/
│   │   ├── components/ Shared composables
│   │   ├── screen/     One package per screen, screen + ViewModel together
│   │   └── theme/      Colour, type, shape
│   └── work/           WorkManager workers: alert upload, scan polling
├── main/assets/shared/ Copied from packages/shared/data — do not edit here
├── demo/               FakeSnapTabApi binding, manifest removals, clay icon
├── live/               Retrofit binding, the real app label
├── debug/              The cleartext-to-loopback network config
└── test/               JVM unit tests
```

Screen and ViewModel live in the same package deliberately. They change together,
and a `ui/viewmodel/` folder holding thirteen unrelated ViewModels — which is what
this project had — makes you navigate two trees to follow one feature.

## Stack

From `gradle/libs.versions.toml`, which is the only place versions are written:

AGP 8.7.3 · Kotlin 2.0.21 · KSP 2.0.21-1.0.28 · Compose BOM 2024.12.01 ·
Hilt 2.53.1 · Navigation 2.8.5 · Room 2.6.1 · Retrofit 2.11.0 + OkHttp 4.12.0 ·
kotlinx-serialization 1.7.3 · CameraX 1.4.1 · DataStore 1.1.1 · WorkManager 2.10.0 ·
Coil 2.7.0 · Accompanist 0.36.0 · Firebase BOM 33.7.0 (optional)

**Retrofit uses kotlinx-serialization, not Gson.** The DTOs are annotated
`@SerialName`, which Gson ignores entirely — it matches on field name. That
mismatch is why the original app showed every total as zero: the JSON said
`net_amount`, the field was called `netAmount`, and Gson silently left it at its
default. A converter that respects the annotations makes the bug impossible.

Firebase Messaging is applied only if `app/google-services.json` exists:

```kotlin
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")
```

so a checkout with no Firebase project builds and runs, and notifications fall back
to the in-app inbox.

## Money on the client

`core/Money.kt` mirrors `packages/shared/src/money.ts`: `parseOrNull`, `toEditable`,
`format`, `formatCompact`, `formatSigned`, `formatBasisPoints`, and `apportion`.

`apportion` is the important one. It is the same largest-remainder algorithm the
server uses, so the per-head figure the split screen shows before you save is the
figure the server will store. Without that, the preview and the result disagree by a
paisa on any total that does not divide evenly, and the user is right to distrust
whichever one they noticed second.

Amounts are `Long` minor units throughout. No `Float`, no `Double`, no
`BigDecimal` — see [ARCHITECTURE.md](ARCHITECTURE.md#money).

## Reading bank alerts

`SmsReceiver` is manifest-registered with
`android:permission="android.permission.BROADCAST_SMS"`, so only the platform can
deliver to it and no other app can forge an alert into SnapTab. It joins multipart
messages, uses `goAsync()` for the database write, and refuses to do anything unless
the user has enabled the feature and is signed in.

`BankAlertParser` reads `assets/shared/sms-rules.json` — the same file the
TypeScript parser reads. Parsing happens here, on the device; what goes to the
server is the structured result, and the message body only if the user opted in.

Duplicates are dropped on a fingerprint: exact on the bank's reference number where
there is one, otherwise amount + account mask + normalised merchant + calendar day.
Room is keyed on it, and `enqueue` returning -1 means "already seen", in which case
nothing is said. A bank that sends the same alert twice notifies once.

When the app is closed the notification carries three actions: **Log it**, which
files a personal expense without opening the app; **Scan bill**, which opens the
camera with the alert attached so the receipt lands on the same expense; and
**Ignore**. `AlertActionReceiver` handles them.

`AlertSyncWorker` uploads the queue, so alerts read on the Underground arrive when
the phone next has signal.

## The icon

A receipt torn once on the diagonal, in `res/drawable/ic_launcher_foreground.xml`.
Pure vector, so there are no PNG density buckets to regenerate and nothing to
re-export when it changes.

Three layers: a flat teal background, the paper-coloured receipt, and a monochrome
copy for Android 13+ themed icons. The tear is genuine negative space — two
separate paths — rather than a gap painted in the background colour, because the
themed layer keeps only the alpha channel and a painted gap would be opaque there.

The artwork is 32×56 on the 108dp canvas, centred, which puts its furthest point
30.5 from the centre against the 33 the 66dp safe circle allows. Every launcher mask
leaves it whole. `minSdk 26` means adaptive icons are available on every supported
device, so there are no legacy raster fallbacks at all.

The demo flavour overrides only the background, to clay.

## Tests

```bash
cd apps/android
./gradlew testDemoDebugUnitTest testLiveDebugUnitTest
```

- `core/MoneyTest.kt` — the same vectors as the TypeScript suite, including Indian
  lakh grouping and the apportionment cases. The two implementations exist in order
  to agree; these tests are what hold them to it.
- `data/sms/BankAlertParserTest.kt` — real message shapes from several banks, the
  fingerprint cases, and the two that used to be wrong: a debit that prints the
  running balance afterwards, and a card narration where `*` separates the merchant
  from a descriptor.

## Things worth knowing

**`npm run sync:assets` after editing shared data.** `app/src/main/assets/shared/`
is a copy. Editing it there is editing the copy, and CI fails the build when the two
have drifted.

**Gradle needs 4GB.** `org.gradle.jvmargs=-Xmx4g` in `gradle.properties` — KSP plus
the Compose compiler on a module this size runs out at 2g.

**Configuration cache is on.** If you add a task that reads a system property or a
file at configuration time, the build will tell you about it rather than silently
going stale.

**The theme uses the SnapTab palette, not dynamic colour.** `dynamicColor` is off
on purpose: the whole point of a palette where teal means "owed to you" and clay
means "you owe" is lost when the launcher recolours it to the user's wallpaper.
