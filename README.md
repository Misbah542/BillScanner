# SnapTab

[![CI](https://github.com/Misbah542/BillScanner/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Misbah542/BillScanner/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

**Point your camera at a bill, get the items back as a list, split it with whoever
was there.** SnapTab also reads your bank's debit and credit SMS, so the spending
that never comes with a receipt — a card tap, a UPI transfer — still lands in your
month.

This repository is a monorepo: the Node.js backend, the Android app, and the domain
rules they share all live here and move together.

---

## Contents

- [What it does](#what-it-does)
- [Try it in two minutes](#try-it-in-two-minutes)
- [Repository layout](#repository-layout)
- [Quick start](#quick-start)
- [The Android app](#the-android-app)
- [The API](#the-api)
- [The data model](#the-data-model)
- [Four conventions worth knowing](#four-conventions-worth-knowing)
- [Tests](#tests)
- [CI and releases](#ci-and-releases)
- [Secrets, and what is in this public repo](#secrets-and-what-is-in-this-public-repo)
- [Privacy](#privacy)
- [Scripts](#scripts)
- [Where things are](#where-things-are)
- [Documentation](#documentation)
- [License](#license)

---

## What it does

**Scan a bill.** Photograph a receipt; the server reads it and returns the line
items, quantities, rates, service charge and tax as structured data you can correct
before saving. OCR is server-side and asynchronous, so parsing can be improved for
everyone without shipping an app release.

**Split it, five ways.** Equal, unequal (exact amounts), percentage, shares
(weights), or item by item — "I only had the dosa". Shares always add up to the
exact total; odd paise go to whoever paid.

**Add people by email or phone.** Someone with no SnapTab account becomes a real
member with a real balance, and claims their history when they eventually sign in.
The friend who will pay you back on Tuesday and never install anything is the whole
point.

**Catch what has no receipt.** SnapTab reads bank debit and credit SMS on the
device, recognising the message formats of 17 banks and card issuers. When the app is
closed you get a notification with three actions: *Log it* (files it without opening
the app), *Scan bill* (opens the camera so the receipt lands on the same expense), or
*Ignore*. Open it in the app and it asks where the money goes — personal, or one of
the groups you are already in.

**Personal expenses, not just shared ones.** A lone card debit is a first-class
expense with no group, no split and no bill. `Home` has a Personal lens; there is an
add-by-hand screen that never mentions groups.

**Know what you actually spent.** Monthly spend is your personal expenses in full
plus *your share* of the shared ones. Money you fronted for other people is reported
separately as a loan, because the person who always pays should not look like the
person who always overspends.

**Auto-suggested categories.** 17 categories, suggested from what you filed this
merchant under last time, then a shared merchant taxonomy, then words on the receipt
— always a suggestion with a confidence, never a silent assignment.

**Settle up in the fewest payments.** Four people who each owe two others settle in
two transfers, not six.

**Share a tab with anyone.** An unguessable link renders one expense read-only,
outside auth, for pasting into a group chat. It exposes that expense and nothing
else — not the group, not other expenses, not anyone's balance.

**Sign in without a password.** Email OTP, phone OTP, or Google.

## Try it in two minutes

The `demo` build is a complete, navigable app with **no backend at all** — every
screen is served by an in-memory fake API with seeded data.

**Download one CI already built:** [Actions → CI](https://github.com/Misbah542/BillScanner/actions/workflows/ci.yml)
→ newest run on `main` → **Artifacts** → `snaptab-demo-debug` → unzip →

```bash
adb devices                       # confirm the phone is listed
adb install -r app-demo-debug.apk
```

**Or build it yourself** (needs JDK 17 and the Android SDK):

```bash
cd apps/android && ./gradlew installDemoDebug
```

It installs alongside the real app as **SnapTab Demo**, with a clay icon instead of
teal. It has no SMS permissions in its manifest at all, reaches no network, and its
sample data is invented. See [docs/CI.md](docs/CI.md#what-is-in-the-demo-apk-and-what-is-not).

## Repository layout

```
snaptab/
├── apps/
│   ├── api/                Node.js + Express + Prisma backend  (@snaptab/api)
│   │   ├── prisma/         schema.prisma — 21 models, 23 enums — and migrations
│   │   ├── src/modules/    one router per resource
│   │   ├── src/services/   the logic the routers call
│   │   ├── src/ocr/        OcrProvider: stub | google-vision
│   │   ├── src/workers/    drains the outbox and the OCR queue
│   │   └── test/           131 tests, over HTTP, against a real Postgres
│   └── android/            Kotlin + Jetpack Compose app (Gradle, not an npm workspace)
│       └── app/src/
│           ├── main/       78 Kotlin files: 13 screens, repositories, SMS parser
│           ├── demo/       the in-memory fake API, and the manifest removals
│           ├── live/       the Retrofit binding
│           └── debug/      the cleartext-to-loopback network config
├── packages/
│   └── shared/             domain rules used by both sides  (@snaptab/shared)
│       ├── src/            money, split, categories, sms, receipt, types
│       └── data/           sms-rules.json, categories.json — read by TS *and* Kotlin
├── docs/
│   ├── ARCHITECTURE.md     how it fits together, and why the awkward parts are awkward
│   ├── API.md              every endpoint, with real request and response shapes
│   ├── ANDROID.md          app layout, flavours, release builds, R8
│   ├── CI.md               what CI runs, where secrets go, how to cut a release
│   └── AUDIT.md            the 32 findings in the original app, before the rebuild
├── scripts/
│   ├── setup.mjs           one-command first run
│   └── sync-shared-assets.mjs   copies shared rule data into the app's assets
├── .github/workflows/
│   ├── ci.yml              hygiene → shared + API tests → Android tests, lint, APKs
│   └── release.yml         a signed release, on demand or from a v* tag
├── docker-compose.yml      Postgres + the API
└── package.json            npm workspaces root
```

**Why a monorepo.** The split arithmetic, the money parser and the bank-SMS rule
table are needed on both sides. In two repositories they drift, and the first symptom
is a split preview on the phone that disagrees with the server by a paisa — or worse,
the same bank SMS fingerprinted two different ways, so the user sees one transaction
twice. Here `packages/shared` is the single definition, the API imports it directly,
and `npm run sync:assets` copies its JSON rule files into the app's assets. CI fails
the build if those copies have drifted.

## Quick start

Needs **Node 20.11+** (`.nvmrc` pins 22) and Docker for Postgres. The Android app
also needs **JDK 17** and the Android SDK — see [docs/ANDROID.md](docs/ANDROID.md).

```bash
git clone https://github.com/Misbah542/BillScanner.git snaptab
cd snaptab
npm run setup      # install, start Postgres, write .env, migrate, seed, build
npm run dev        # API on http://localhost:4000
```

`npm run setup` is idempotent — run it again after pulling.

Check it came up:

```bash
curl -s localhost:4000/healthz          # liveness; does not touch the database
curl -s localhost:4000/readyz           # readiness; 503 if Postgres is unreachable
curl -s localhost:4000/v1/config | jq   # sign-in methods available, rule versions
```

<details>
<summary>Doing it by hand instead</summary>

```bash
npm install
cp apps/api/.env.example apps/api/.env      # then set a real JWT_SECRET
docker compose up -d postgres
npm run db:generate                          # writes the Prisma client's types
npm run db:migrate
SEED_DEMO=1 npm run db:seed                  # sample account, or omit for categories only
npm run build
npm run dev
npm run dev:worker                           # second terminal: OCR + outbox worker
```

`npm run db:generate` before anything that typechecks: `@prisma/client` ships as a
stub whose types are written from `schema.prisma`, and without it `tsc` reports
dozens of errors that have nothing to do with the code.

</details>

### Pointing the app at your API

```bash
cp apps/android/local.properties.example apps/android/local.properties
# set sdk.dir — Android Studio writes it for you on first sync
cd apps/android && ./gradlew installLiveDebug
```

`snaptab.apiBaseUrl` defaults to `http://10.0.2.2:4000/`, which is the host machine
as seen from the Android emulator. `localhost` inside the emulator is the emulator,
which is the mistake everybody makes once.

On a physical device, either use your machine's LAN address and add it to the
cleartext allowances in `src/debug/res/xml/network_security_config.xml`, or run
`adb reverse tcp:4000 tcp:4000` and keep using `127.0.0.1:4000`, which is already
allowed.

## The Android app

Kotlin 2.0.21, Compose BOM 2024.12.01, Hilt, Room, Retrofit with
**kotlinx-serialization** (not Gson — the DTOs are annotated `@SerialName`, which
Gson ignores, and that mismatch is why the original app displayed every total as
zero). AGP 8.7.3, compileSdk 35, minSdk 26.

**13 screens** across four bottom tabs — Home, Groups, Inbox, Settle — with the scan
FAB in the centre: sign-in, verify, home, groups, group detail, inbox, settle, scan
(camera → processing → review), add expense, monthly, expense detail, split, profile.

**Four variants**, two flavours × two build types:

| | `demo` | `live` |
| --- | --- | --- |
| API | `FakeSnapTabApi`, in memory | Retrofit against your server |
| Application id | `com.snaptab.app.demo` | `com.snaptab.app` |
| Label / icon | SnapTab Demo, clay | SnapTab, teal |
| SMS permissions | **removed from the manifest** | requested when you enable the feature |
| Release needs | nothing configured | an API address and a signing key |

```bash
./gradlew installDemoDebug      # no server needed
./gradlew installLiveDebug      # against your API
./gradlew assembleDemoRelease   # minified, unsigned, needs nothing
./gradlew assembleLiveRelease   # minified, signed — see docs/CI.md
```

`release` is not `debug` with a different name: it runs R8, and R8 is where a missing
keep rule becomes an app that installs and then fails on its first API call. CI
builds the minified variant on every push for exactly that reason. Details, and what
`proguard-rules.pro` keeps and why, are in [docs/ANDROID.md](docs/ANDROID.md#release-builds).

## The API

Express 4 on Node, Prisma 5 on PostgreSQL 16. **56 endpoints under `/v1`**, two
public share routes under `/t`, and two health probes. Full reference with request
and response shapes: [docs/API.md](docs/API.md).

| Group | What it covers |
| --- | --- |
| `/v1/auth` | email/phone OTP, Google, rotating refresh tokens, logout everywhere |
| `/v1/users` | profile, contact lookup, notifications, device registration |
| `/v1/groups` | groups, members by email or phone, balances, activity |
| `/v1/expenses` | the central resource — list, create, patch, split, re-split, category suggestions |
| `/v1/scans` | upload a receipt, poll its OCR, fetch the image, retry |
| `/v1/alerts` | ingest parsed bank SMS, turn one into a personal or group expense, link, ignore |
| `/v1/settlements` | balances, record a payment, minimal transfers, remind |
| `/v1/categories` | the taxonomy plus your own |
| `/v1/insights` | monthly summary, trend, top merchants |
| `/v1/share-links` | mint and revoke a public read-only link |
| `/t/:token` | that link, unauthenticated |

Every error is the same shape, with a message written to be shown to a person:

```json
{ "error": { "code": "SPLIT_PERCENT_SUM",
             "message": "The percentages add up to 90%, not 100%.",
             "details": { "totalBp": 9000 } } }
```

**The scan pipeline is asynchronous.** `POST /v1/scans` returns `202` with a scan id;
a worker runs the OCR; the client polls or takes a push. A request that holds a
connection open for the length of an OCR call is a request that times out on a train.
The provider is an interface — `stub` needs no credentials and is what tests and a
fresh checkout use; `google-vision` is the real one.

**Notifications go through a transactional outbox.** The transaction that writes an
expense also writes the event, and commits both or neither. A push provider having a
bad afternoon delays notifications and cannot fail the request that created the
expense.

## The data model

21 models and 23 enums in [`apps/api/prisma/schema.prisma`](apps/api/prisma/schema.prisma).
The spine is `Expense`, not `Bill`:

```
Expense.kind    PERSONAL | SHARED
Expense.source  SCAN | ALERT | MANUAL | IMPORT
```

A card debit for a chai is a `PERSONAL` expense, `source = ALERT`, with no items and
no shares. A restaurant bill split five ways is the same table with `ExpenseItem`s,
`ExpenseTaxLine`s, `ExpenseShare`s and `ExpenseItemShare`s hanging off it. Separate
`Bill` and `Transaction` tables were the alternative, and every query that matters
crosses them: "what did I spend in September" becomes a union, and "attach this
receipt to that debit" becomes a migration between tables rather than an update.

Around it: `User`/`Identity`/`Session`/`OtpChallenge`/`Device`,
`Group`/`GroupMember`/`GroupInvite`, `Category`/`MerchantCategoryMemory`, `Scan`,
`BankAlert`, `Settlement`, `ShareLink`, `Notification`, `OutboxEvent`.

**`ExpenseShare` is the only source of truth for who owes what.** Each share carries
what this person owes and what they have put in; a settlement moves the second. This
is worth stating because getting it wrong is subtle: an earlier version subtracted
settlements when computing balances *and* wrote them into the share, so paying a debt
off in full left you owed the same amount again. A test caught it.

## Four conventions worth knowing

**1. Money is an integer count of minor units.** Paise, cents — in a field whose name
ends `Minor`. `totalMinor: 257500` is ₹2,575.00. `BigInt` on the wire and in
Postgres, `Long` in Kotlin. There is no `Float` or `Double` anywhere in the money
path and there must not be: `0.1 + 0.2 !== 0.3` is not a curiosity here, it is item
amounts that visibly fail to add up to the total.

**2. Split shares always sum to the total, exactly.** ₹100 three ways is
33.34 / 33.33 / 33.33, never 33.33 × 3 with a paisa evaporating into a permanently
unsettled bill. That is largest-remainder apportionment, implemented once in
`apportion()` and mirrored in Kotlin so a preview on the phone matches the server to
the paisa. Percentages are **basis points** for the same reason — 33.33% is not
representable and three of them do not make 100%, but 3333 + 3333 + 3334 do.

**3. An expense is the spine, not a bill.** See [the data model](#the-data-model).

**4. Monthly spend counts your share, not your outlay.** Personal expenses in full,
plus your share of shared ones. What you fronted for other people is `paidOutMinor`
— a loan you are owed, not something you spent.

The reasoning behind each, and every other decision that looks odd, is in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Tests

```bash
npm test                                  # 95 shared + 131 API
npm test --workspace @snaptab/shared      # money, splits, categories, SMS, receipts
npm test --workspace @snaptab/api         # every endpoint over HTTP
cd apps/android && ./gradlew testDemoDebugUnitTest testLiveDebugUnitTest
```

| Suite | Count | Covers |
| --- | --- | --- |
| `packages/shared` | 95 | money parsing and formatting, apportionment, all five split methods, itemised splits, minimal transfers, category classification, SMS rules, receipt parsing |
| `apps/api` | 131 | every endpoint over HTTP with Supertest, against a real Postgres |
| `apps/android` | JVM | `Money` against the same vectors as the TS suite; `BankAlertParser` against real bank message shapes |

The API suite uses a real database rather than a mock, because the failures worth
catching are transactional: a settlement applied to shares, an outbox row committed
with the expense that produced it, a refresh-token family revoked on reuse. A mocked
Prisma client asserts that the code called the functions it was written to call, which
is not the same as asserting the data is right — the double-counting balance bug above
would have passed a mocked suite, and did fail the real one.

Copy `apps/api/.env.test.example` to `apps/api/.env.test` first, and point it at a
scratch database: the suite truncates every table in it. Because it truncates between
files, `apps/api/vitest.config.ts` sets `fileParallelism: false`.

`Money` is tested against the same vectors in two languages on purpose. The two
implementations exist in order to agree, and the tests are what hold them to it.

## CI and releases

**[`ci.yml`](.github/workflows/ci.yml)** runs on every push and pull request, in
three jobs:

- **`hygiene`** — seconds, and first, so a leak fails the run before anything
  expensive starts. Fails the build if a `.env`, `local.properties`, `.pem`/`.p12`/
  `.jks`/`.keystore`, `google-services.json` or `key.properties` has been committed,
  or if one of the templates has picked up something that looks like a real value.
- **`js`** — builds the shared package, generates the Prisma client, checks the app's
  bundled rule files are in sync with the originals, typechecks, migrates a throwaway
  Postgres, runs both suites.
- **`android`** — unit tests for both flavours, lint, the debug APK, and the
  **minified release** APK. Both are uploaded as artifacts.

**[`release.yml`](.github/workflows/release.yml)** builds a signed release on demand
or from a `v*` tag — flavour `demo` or `live`, artifact APK or AAB. It checks the
secrets are present before spending ten minutes building something it cannot sign,
decodes the keystore into the runner's temp directory rather than the workspace, and
verifies with `apksigner` that what came out is actually signed. It is the only
workflow that touches a secret.

To cut a release you need a keystore and an API address, neither of which lives here:
[docs/CI.md](docs/CI.md#building-a-release) has the walkthrough. Generate the key
yourself and **back it up somewhere that is not just your laptop** — it is the one
secret that cannot be rotated. Lose it and you can never publish an update to this
app again.

## Secrets, and what is in this public repo

**Nothing secret is committed, and `ci.yml` needs no secrets to run.** This
repository is public, so here is the complete list of committed files that hold any
configuration and exactly what is in each:

| File | Contents |
| --- | --- |
| `apps/api/.env.example` | Variable names with placeholders — `JWT_SECRET=dev-only-change-me-…`, every key blank. Copied to `.env`, which is git-ignored. |
| `apps/api/.env.test.example` | The same for the test suite. Copied to `.env.test`, git-ignored. |
| `apps/android/local.properties.example` | `sdk.dir`, and commented-out keys for release builds. Copied to `local.properties`, git-ignored. |
| `.github/workflows/ci.yml` | A `JWT_SECRET` for a database that exists for 30 seconds inside a container that is then destroyed. Deliberately in the clear — hiding it would only stop people reading the workflow. |
| `docker-compose.yml` | `snaptab`/`snaptab` for a local Postgres on `localhost`, and `JWT_SECRET` as a *required* variable with no default, so `docker compose up` fails rather than starting with a guessable one. |

No `.env`, no `local.properties`, no keystore and no `google-services.json` — not in
the tree, and not in history. Anyone cloning this gets templates and supplies their
own values, which is the point. The `hygiene` job fails the build if that ever
regresses.

Real values belong to the environment that runs the code, and for CI to
**Settings → Secrets and variables → Actions**. [docs/CI.md](docs/CI.md#secrets) has
the table of which secrets exist and what each is for.

**One caveat if you distribute an APK:** an APK is trivially decompiled, so anything
the build put into `BuildConfig` is readable in seconds. That is why the base URL and
Google client id come from an uncommitted `local.properties`, and why a `live` release
build now refuses to proceed without an explicit API address rather than defaulting to
a domain nobody owns.

## Privacy

**Bank SMS is parsed on the device.** What reaches the server is the structured
result — amount, direction, merchant, the last four digits of the account — and not
the message body, unless you turn that on explicitly in Settings. Shipping every bank
SMS somewhere to be parsed there would make a much better dataset and a much worse
app to trust.

`READ_SMS` and `RECEIVE_SMS` are requested when you first enable the feature, not at
install, and the app works fully without them. The `demo` build removes them from its
manifest entirely, so it cannot read messages even if it wanted to.

Receipt photos go to the app's own cache directory, so capturing one needs no storage
permission at all. *Pick from gallery* needs `READ_EXTERNAL_STORAGE` on Android 12 and
below, so it is declared with `maxSdkVersion="32"` rather than unbounded — scoped
storage made it a no-op from API 29 onward, and the original app requested it for
every version, which Play flags.

A share link exposes one expense and nothing else, can be revoked, and is rate
limited separately from the authenticated API.

## Scripts

Run from the repository root.

| Command | What it does |
| --- | --- |
| `npm run setup` | Install, start Postgres, write `.env`, migrate, seed, build. The one-command path. |
| `npm run dev` | API in watch mode on `PORT` (4000 by default). |
| `npm run dev:worker` | The OCR/outbox worker in watch mode, in a second terminal. |
| `npm test` | Every JS/TS suite: `packages/shared`, then `apps/api`. |
| `npm run test:shared` / `test:api` | One at a time. |
| `npm run typecheck` | `tsc --noEmit` across the workspaces. |
| `npm run build` | Builds `@snaptab/shared`, then generates the Prisma client and compiles the API. |
| `npm run db:migrate` | Applies migrations in development. |
| `npm run db:seed` | Seeds the category taxonomy; add `SEED_DEMO=1` for a sample account. |
| `npm run db:studio` | Prisma Studio, for poking at rows. |
| `npm run sync:assets` | Copies `packages/shared/data/*.json` into the app's assets. Run after editing a rule file. |
| `npm run android:assemble` | `./gradlew assembleDebug` for both flavours. |
| `npm run android:test` | The Android JVM unit tests. |
| `npm run android:release` | `./gradlew assembleDemoRelease` — the minified build, no signing key needed. |
| `npm run docker:up` / `docker:down` | The full stack, API included. |

## Where things are

| I want to… | Look at |
| --- | --- |
| add or change an endpoint | `apps/api/src/modules/*.routes.ts`, then a test in `apps/api/test/` |
| change split or money maths | `packages/shared/src/{split,money}.ts` **and** `apps/android/…/core/Money.kt` |
| teach the SMS parser a new bank | `packages/shared/data/sms-rules.json`, then `npm run sync:assets` |
| change the category suggestions | `packages/shared/data/categories.json` |
| change the database shape | `apps/api/prisma/schema.prisma`, then `npm run db:migrate` |
| swap the OCR engine | `apps/api/src/ocr/` — implement `OcrProvider`, register it in `index.ts` |
| add a screen | `apps/android/app/src/main/java/com/snaptab/app/ui/screen/` |
| change what release builds keep | `apps/android/app/proguard-rules.pro` — and test with a *release* build |

Screen and ViewModel live in the same package on purpose. They change together, and a
`ui/viewmodel/` folder holding thirteen unrelated ViewModels — which is what this
project had — makes you navigate two trees to follow one feature.

## Documentation

| | |
| --- | --- |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How the pieces fit, and why each awkward part is awkward. Money, splitting, the domain model, balances, the scan pipeline, bank alerts, auth, the outbox. |
| [docs/API.md](docs/API.md) | Every endpoint, with real request and response shapes, error codes and rate limits. |
| [docs/ANDROID.md](docs/ANDROID.md) | App layout, the two flavours, release builds and R8, the icon, what to know before changing things. |
| [docs/CI.md](docs/CI.md) | What CI runs, where secrets go, getting the demo APK, cutting a signed release. |
| [docs/AUDIT.md](docs/AUDIT.md) | The 32 findings in the original app — including the zero-byte `app/build.gradle.kts` that meant it could not build at all. |

## License

MIT. See [LICENSE](LICENSE).
