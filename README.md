# SnapTab

Point your camera at a bill, get the items back as a list, and split it with
whoever was there. SnapTab also watches your bank's debit and credit SMS, so the
spending that never comes with a receipt — a card tap, a UPI transfer — still
lands in your month.

This repository is a monorepo: the Node.js backend, the Android app and the
domain rules they share all live here and move together.

```
snaptab/
├── apps/
│   ├── api/            Node.js + Express + Prisma backend (@snaptab/api)
│   └── android/        Kotlin + Jetpack Compose app (Gradle, not an npm workspace)
├── packages/
│   └── shared/         Domain rules used by the API and the clients (@snaptab/shared)
├── docs/
│   ├── ARCHITECTURE.md How the pieces fit, and why the money maths is the way it is
│   ├── API.md          Every endpoint, with request and response shapes
│   ├── ANDROID.md      App layout, build flavours, running without a server
│   ├── CI.md           What CI runs, where secrets go, getting the demo APK
│   └── AUDIT.md        What the original app was missing, before the rebuild
├── scripts/
│   ├── setup.mjs                One-command setup
│   └── sync-shared-assets.mjs   Copies shared rule data into the app's assets
├── docker-compose.yml  Postgres + the API, for a local stack
└── package.json        npm workspaces root
```

Why a monorepo: the split arithmetic, the money parser and the bank-SMS rule
table are needed on both sides. Kept in two repositories they drift, and the
first symptom is a split preview on the phone that disagrees with the server by
a paisa. Here, `packages/shared` is the single definition, the API imports it
directly, and `npm run sync:assets` copies its JSON rule files into the app's
assets so the Kotlin parsers read the same tables.

## Quick start

Requires Node 20.11+ (`.nvmrc` pins 22) and Docker for Postgres. The Android app
additionally needs JDK 17 and the Android SDK — see [docs/ANDROID.md](docs/ANDROID.md).

```bash
git clone https://github.com/Misbah542/BillScanner.git snaptab
cd snaptab
npm run setup      # installs, starts Postgres, migrates, seeds demo data, builds
npm run dev        # API on http://localhost:4000
```

`npm run setup` is idempotent — run it again after pulling.

Check it came up:

```bash
curl -s localhost:4000/healthz            # liveness, does not touch the database
curl -s localhost:4000/readyz             # readiness, fails if Postgres is unreachable
curl -s localhost:4000/v1/config | jq     # which sign-in methods are on, rule versions
```

To do it by hand instead:

```bash
npm install
cp apps/api/.env.example apps/api/.env    # then edit JWT_SECRET
docker compose up -d postgres
npm run db:migrate
SEED_DEMO=1 npm run db:seed
npm run build
npm run dev
```

### Running the app against it

The app reads its base URL from `apps/android/local.properties`:

```bash
cp apps/android/local.properties.example apps/android/local.properties
# then set sdk.dir; snaptab.apiBaseUrl already defaults to the emulator's host
cd apps/android && ./gradlew installLiveDebug
```

`10.0.2.2` is how the Android emulator reaches the host machine; `localhost`
inside the emulator is the emulator.

### Running the app with no backend at all

The `demo` flavour ships an in-memory implementation of the whole API with
seeded groups, expenses and bank alerts, so every screen is reachable before the
server exists:

```bash
cd apps/android && ./gradlew installDemoDebug
```

It installs alongside the real app as *SnapTab Demo*. See
[docs/ANDROID.md](docs/ANDROID.md#demo-flavour) for what it fakes and what it
deliberately does not.

## Scripts

Run these from the repository root.

| Command | What it does |
| --- | --- |
| `npm run setup` | Install, start Postgres, migrate, seed, build. The one-command path. |
| `npm run dev` | API in watch mode on `PORT` (4000 by default). |
| `npm run dev:worker` | The outbox/scan worker in watch mode, in a second terminal. |
| `npm test` | Every JS/TS suite: `packages/shared` then `apps/api`. |
| `npm run typecheck` | `tsc --noEmit` across the workspaces. |
| `npm run build` | Builds `@snaptab/shared`, then generates the Prisma client and compiles the API. |
| `npm run db:migrate` | Applies migrations in development (`prisma migrate dev`). |
| `npm run db:seed` | Seeds the category taxonomy; add `SEED_DEMO=1` for sample data. |
| `npm run db:studio` | Prisma Studio, for poking at rows. |
| `npm run sync:assets` | Copies `packages/shared/data/*.json` into the app's assets. Run it after editing a rule file. |
| `npm run android:assemble` | `./gradlew assembleDebug` for both flavours. |
| `npm run android:test` | The Android JVM unit tests. |
| `npm run docker:up` / `docker:down` | The full stack, API included. |

## Tests

```bash
npm test                                  # 95 shared + 131 API tests
npm test --workspace @snaptab/shared      # money, splits, categories, SMS, receipts
npm test --workspace @snaptab/api         # HTTP-level, against a real Postgres
cd apps/android && ./gradlew testDebugUnitTest
```

The API suite talks to a real database rather than a mock, because the things
worth testing here are transactional — a settlement applied to shares, an outbox
row committed with the expense that produced it. It truncates between files, so
`apps/api/vitest.config.ts` sets `fileParallelism: false`.

Copy `apps/api/.env.test.example` to `apps/api/.env.test` first, and point it at a
scratch database — the suite truncates every table in it.

## Conventions worth knowing before you write code

**Money is an integer count of minor units** — paise, cents — named `…Minor`,
end to end: `BigInt` on the wire, `BigInt` in Postgres, `Long` in Kotlin. There
is no `Float` or `Double` anywhere in the money path, and there must not be.
Formatting and parsing live in `packages/shared/src/money.ts` and its Kotlin
mirror `core/Money.kt`.

**Split shares always sum to the total.** Dividing ₹100 three ways gives
33.34 / 33.33 / 33.33, never 33.33 × 3 with a paisa evaporating. That is
largest-remainder apportionment, implemented once in `apportion()` and mirrored
in Kotlin so a preview on the phone matches the server exactly. Odd units go to
whoever paid.

**An expense is the spine, not a bill.** `Expense.kind` is `PERSONAL` or
`SHARED`, and `Expense.source` records where it came from (`SCAN`, `ALERT`,
`MANUAL`, `IMPORT`). A lone card debit is a first-class expense with no items
and no split; a scanned restaurant bill is the same row with items, tax lines
and shares hanging off it.

**Monthly spend counts your share, not your outlay.** Personal expenses in
full, plus your share of shared ones. Money you fronted for other people is
`paidOutMinor` — a loan you are owed, not something you spent.

The reasoning behind each of these is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## CI and secrets

`.github/workflows/ci.yml` runs on every push: a hygiene job that fails if an env
file, key or keystore has been committed, then the shared and API suites against a
throwaway Postgres, then the Android unit tests, lint and a demo APK you can
download from the run's artifacts.

**CI needs no secrets, and nothing secret is in this repository.** The only env
files committed are `apps/api/.env.example` and `apps/api/.env.test`, both
placeholders. Real values belong to the environment that runs the code — and, for
CI, to `Settings → Secrets and variables → Actions`. [docs/CI.md](docs/CI.md) has
the table of what would need one, and what is safe about the demo APK before you
post it anywhere.

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

## Privacy

The SMS parser runs **on the device**. What reaches the server is the structured
result — amount, direction, merchant name, the last four digits of the account —
and not the message body, unless you turn that on explicitly in Settings. The
`READ_SMS` and `RECEIVE_SMS` permissions are requested when you first enable the
feature, not at install, and the app works without them.

## License

MIT. See [LICENSE](LICENSE).
