# Architecture

How SnapTab is put together, and — more usefully — why the awkward parts are
awkward. Every decision below was made because the obvious alternative breaks in
a specific way that is named.

- [The shape of the system](#the-shape-of-the-system)
- [Money](#money)
- [Splitting](#splitting)
- [The domain model](#the-domain-model)
- [Balances and settlements](#balances-and-settlements)
- [Monthly spend](#monthly-spend)
- [The scan pipeline](#the-scan-pipeline)
- [Bank alerts](#bank-alerts)
- [Category suggestions](#category-suggestions)
- [Auth](#auth)
- [People who have not signed up](#people-who-have-not-signed-up)
- [Notifications and the outbox](#notifications-and-the-outbox)
- [Sharing a bill](#sharing-a-bill)
- [The Android app](#the-android-app)
- [Testing](#testing)

## The shape of the system

```
  Android app (Kotlin, Compose)
      │
      │  HTTPS, JSON, bearer access token
      ▼
  apps/api  ──  Express router ── services ── Prisma ── PostgreSQL
      │                                          │
      │                                          └── OutboxEvent
      │                                                  │
      └── receipt image ── object storage                 ▼
                              │                    worker: push, OCR
                              └────────────────────────┘

  packages/shared  imported by the API; its JSON rule files are copied
                   into the app's assets, so both sides parse identically
```

One process serves HTTP. A second process (`npm run dev:worker`) drains the OCR
queue and the outbox. They share the database and nothing else, so either can be
restarted or scaled on its own. There is no Redis and no message broker: the work
queue is a table, and a row is claimed with a conditional update — `UPDATE …
WHERE id = ? AND processed_at IS NULL`, acted on only if it reports one row
changed — so two workers polling the same table cannot both run the same event.
That is enough at this volume and removes a whole piece of infrastructure from
the deployment.

## Money

Every amount in this codebase is an integer count of the currency's minor unit —
paise for INR, cents for USD — carried in a field whose name ends in `Minor`.
`totalMinor: 257500` is ₹2,575.00.

`Float` and `Double` do not appear in the money path at all, and must not be
added to it. `0.1 + 0.2 !== 0.3` is not a curiosity here; it is a bill where the
item amounts visibly fail to add up to the total, and a split where one person is
charged a paisa that nobody else is credited. Postgres holds these as `BigInt`,
JSON carries them as integers, Kotlin as `Long`.

Parsing and formatting live in `packages/shared/src/money.ts`, mirrored in
`apps/android/…/core/Money.kt`. Two details there are worth knowing:

**Group separators are ambiguous and Indian grouping is not 3-3-3.** `2,57,500.00`
is lakh grouping; `2.575,00` is European, where the comma is the decimal point.
`stripGroupSeparators()` decides which separator is the decimal by which one
appears last, then strips the other. A regex that assumes three-digit Western
groups throws on the first Indian receipt it meets, which is what the original
code did.

**A number in OCR text is matched whole or not at all.** The amount pattern is

```
\d{1,3}(?:,\d{2,3})+(?:\.\d{1,2})?   |   \d+(?:\.\d{1,2})?
```

The `+` on the grouped branch is the load-bearing character. With `*` there, the
grouped branch matches `100` inside `1000.00` and succeeds, so `Sub Total 1000.00`
parses as ₹100 and the rest of the line is discarded. Three receipt tests and
several SMS rules were wrong in exactly this way.

## Splitting

Four methods, all in `packages/shared/src/split.ts`:

| Method | Input | Used for |
| --- | --- | --- |
| `EQUAL` | nothing | the common case |
| `EXACT` | an amount per person | "I only had the dosa" |
| `PERCENT` | basis points per person | "60/40, it was mostly my order" |
| `SHARES` | a weight per person | "two of us, one of them" |

There is also `computeItemizedSplit`, which assigns each line item to the people
who ate it and apportions tax and service charge in proportion to what each
person's items came to.

**Shares always sum to the total, exactly.** ₹100 three ways is 33.34 / 33.33 /
33.33. Rounding each share independently gives ₹99.99 and a bill that is
permanently one paisa unsettled, which is a support ticket. `apportion()` uses
largest-remainder (Hamilton) apportionment: floor every share, then hand the
leftover units out one at a time to the largest remainders. Ties break toward
`preferIndex`, which callers set to the payer, so the person who paid absorbs the
odd paisa rather than being owed it.

`PERCENT` takes **basis points**, not percentages, for the reason above: 33.33%
is not representable and three of them do not make 100%. 3333 + 3333 + 3334 basis
points do.

Validation throws `SplitError` with a machine-readable code (`ITEM_UNASSIGNED`,
`PERCENT_SUM`, `EXACT_SUM`, `NO_PARTICIPANTS`, …) that the API passes to the
client so the app can highlight the offending row. The item-assignment check runs
before the roster check, so an unassigned item is reported as "nobody is on the
paneer tikka" rather than the less useful "no participants".

`minimalTransfers()` reduces a group's pairwise debts to the fewest payments that
clear them: everyone's net position is computed, then the largest creditor is
matched against the largest debtor until nothing is left. Four people who each
owe two others settle in two transfers instead of six.

## The domain model

The spine is `Expense`, not `Bill`. `apps/api/prisma/schema.prisma` has the
definitions; the discriminators are:

```
Expense.kind    PERSONAL | SHARED
Expense.source  SCAN | ALERT | MANUAL | IMPORT
```

A card debit for a chai is a `PERSONAL` expense, `source = ALERT`, with no
items, no group and no shares. A scanned restaurant bill split five ways is a
`SHARED` expense, `source = SCAN`, with `ExpenseItem`s, `ExpenseTaxLine`s,
`ExpenseShare`s and `ExpenseItemShare`s. Same table, same history, same monthly
total.

The alternative — separate `Bill` and `Transaction` tables — was rejected because
every query that matters crosses them. "What did I spend in September" would be a
union; "attach this receipt to that debit" would be a migration between tables
rather than an update. A `kind` column costs one enum and saves all of that.

Around the spine:

- `User`, `Identity`, `Session`, `OtpChallenge`, `Device` — identity and auth
- `Group`, `GroupMember`, `GroupInvite` — who you split with
- `Category`, `MerchantCategoryMemory` — classification, global and per-user
- `Scan` — an upload and its OCR lifecycle
- `BankAlert` — a parsed SMS, before it becomes an expense
- `Settlement` — a payment between two people
- `ShareLink` — a public, read-only view of one expense
- `Notification`, `OutboxEvent` — what to tell people, and reliably

## Balances and settlements

**`ExpenseShare` is the single source of truth for who owes what.** Each share
carries `amountMinor` (what this person owes on this expense) and `paidMinor`
(what they have put in so far). A settlement, when created, is applied to the
shares it clears.

This is worth stating plainly because getting it wrong is subtle and expensive.
An earlier version subtracted settlements in `balancesFor()` *and* wrote them into
`share.paidMinor`. Both are correct in isolation; together they count every
payment twice, so paying a debt off in full left you owed the same amount again,
with a negative balance. A test caught it. The rule now is that settlements only
ever move `paidMinor`, and balances are computed from shares alone.

## Monthly spend

Defined as: **your personal expenses in full, plus your share of the shared
ones.**

Not what left your account. If you put ₹4,000 on your card for a table of five,
₹800 of that is yours and ₹3,200 is a loan. Counting the full ₹4,000 makes the
person who always pays look like the person who always overspends, which is both
wrong and discouraging. The amount you fronted is reported separately as
`paidOutMinor`, alongside `owedToYouMinor`, so the month reads as three honest
numbers instead of one misleading one.

`GET /v1/insights/monthly` returns the month's total, a per-category breakdown,
the split between personal and shared, and those lending figures.

## The scan pipeline

OCR runs on the server, not the phone.

```
POST /v1/scans  (multipart image, optional Idempotency-Key)
   → 202 Accepted { scanId, status: QUEUED }
   worker: QUEUED → PROCESSING → SUCCEEDED | FAILED
   → GET /v1/scans/:id  until terminal (or a push arrives)
   → POST /v1/expenses with the parsed draft, edited by the user
```

Server-side because it can be improved without shipping an app release. A
merchant whose receipt parses badly is a rule change deployed in minutes, not a
Play Store review and a month of waiting for users to update. It also keeps the
model choice open — the provider is an interface:

```ts
interface OcrProvider {
  recognize(input: OcrInput): Promise<OcrResult>;
}
```

`stub` returns a fixed receipt and needs no credentials, so tests and a fresh
checkout work offline. `google-vision` is the real one. `OCR_PROVIDER` picks.

Asynchronous because OCR takes seconds and a request that holds a connection open
that long is a request that times out on a train. The upload returns immediately
with an id; the app polls, or takes a push if it has been granted one. An
`Idempotency-Key` header makes a retry on a flaky connection return the original
scan instead of paying for the same image twice.

The image itself goes to object storage (`local` or `s3`), never into Postgres.
`GET /v1/scans/:id/image` streams it back, authorised, so the review screen can
show the receipt next to the parsed items.

## Bank alerts

**Parsing happens on the device.** The receiver in
`apps/android/…/sms/SmsReceiver.kt` reads the message, extracts the fields, and
uploads only those — amount, direction, merchant, the last four digits of the
account, the bank, the reference number. The body is uploaded only if the user
explicitly turns that on in Settings. Shipping every bank SMS to a server to be
parsed there would be a much better dataset and a much worse app to trust.

The rules live in `packages/shared/data/sms-rules.json` and are read by both the
TypeScript parser and the Kotlin one. One file, because the fingerprint that
de-duplicates alerts is derived from the parse: if the two implementations
disagree about where the merchant name ends, the same message gets two
fingerprints and the user sees it twice. `npm run sync:assets` copies the file
into the app's assets, and `/v1/alerts/rules/version` lets the app notice its
bundle is stale.

De-duplication is exact on the bank's reference number when there is one, and
otherwise on amount + account mask + normalised merchant + calendar day. A
multipart SMS that arrives in two pieces, or a bank that sends the same alert
twice, notifies once.

Two parser details that cost real debugging:

**A balance in the message is not a reason to reject it.** Patterns that threw
away anything containing `avl bal` were rejecting genuine debits, because most
banks print the running balance after the transaction. Balance-only messages are
excluded properly instead — by requiring a debit or credit marker (`debited`,
`spent`, `paid`, `txn of`, `credited`, …) to be present at all.

**Card narrations use `*` as a separator.** `UPI/SWIGGY*ORDER/HDFC` is Swiggy,
not "Swiggy Order". `normalizeMerchant()` truncates at the first `*`.

When the app is closed, the notification carries three actions: **Log it**, which
files a personal expense without opening the app; **Scan bill**, which opens the
camera with the alert attached so the receipt lands on the same expense; and
**Ignore**. Opening the alert in the app asks where the money goes — personal, or
one of the groups you are already in. It never offers to create a group there,
because a group is a thing with people and a running balance in it, and the
answer to "which group?" in that moment is always one that already exists.

## Category suggestions

Three layers, most specific first:

1. **What you did last time.** `MerchantCategoryMemory` records that *you* file
   Blue Tokai under Coffee, even if the taxonomy says Restaurant.
2. **The shared taxonomy.** `packages/shared/data/categories.json` maps merchant
   patterns and MCC-ish hints onto categories.
3. **Receipt text.** Words on the bill itself — "table", "GSTIN", "litres".

The result is a suggestion with a confidence, never a silent assignment;
`Expense.categorySource` records whether a human or the classifier chose, so the
memory layer only ever learns from actual corrections.

## Auth

Passwordless. Email OTP, phone OTP, or Google.

No passwords means no password database, no reset flow, and no reuse risk from
someone else's breach. OTPs expire in ten minutes, are rate limited per contact
and per IP, and are stored as a SHA-256 digest compared in constant time. SHA-256
rather than Argon2id deliberately: a six-digit code drawn from a CSPRNG and
thrown away in ten minutes has no low-entropy guess to protect against, so a slow
KDF would only slow down the honest request. The rate limit is what stops
guessing, not the hash.

Access tokens are short-lived (15 minutes) JWTs. Refresh tokens are opaque,
stored hashed, rotate on every use, and belong to a family. **Presenting a
refresh token that has already been used revokes the whole family** — the only
way that happens is that a token was copied, so every session descended from it
is ended and the user signs in again. Losing a session is the cheaper failure.

Google sign-in verifies the id token against Google's JWKS, checking `aud`
against `GOOGLE_CLIENT_IDS`. With that unset, `/v1/auth/google` returns 501 and
`/v1/config` reports `google: false`, so the app hides the button instead of
offering one that cannot work.

## People who have not signed up

You add someone to a group by email or phone, and they may well have neither
account nor intention of getting one. That person becomes a `User` with
`status = INVITED`: a real row, with real shares and a real balance, and no way
to sign in. When they eventually do sign in with that email or phone, the
identity is claimed and their history is already there.

The alternative — refusing to record a share until the person registers — makes
the app useless for the case it exists for, which is the friend who will pay you
back on Tuesday and never install anything.

## Notifications and the outbox

Creating an expense should not fail because a push provider is having an
afternoon. So nothing is sent inline. The transaction that writes the expense
also writes an `OutboxEvent`, and commits both or neither. The worker reads the
outbox, sends, and marks. A provider outage delays notifications and loses
nothing; the request itself is unaffected.

`Notification` rows are also the in-app inbox, so a user who has push disabled
still sees what happened.

## Sharing a bill

`POST /v1/share-links` mints an unguessable token for one expense.
`GET /t/:token` renders it read-only, outside `/v1` and outside auth, so it can
be pasted into a group chat and opened by people with no account.

The link exposes the one expense and nothing else — not the group, not the other
expenses, not anyone's balance. Scope is stored on the row (`ShareScope`), links
can be revoked, and the public routes are rate limited separately from the
authenticated API.

## The Android app

Single-module, MVVM, Compose, Hilt, Room for the offline queue, WorkManager for
retries. Details and the screen list are in [ANDROID.md](ANDROID.md); the parts
that matter architecturally:

- `core/Money.kt` mirrors the shared money and apportionment code, so a split
  preview computed offline matches what the server will store.
- `BankAlertParser.kt` reads the same rule JSON as the TypeScript parser.
- Retrofit with **kotlinx-serialization**, not Gson. The DTOs are annotated
  `@SerialName`, which Gson ignores — that mismatch is why the original app
  displayed every total as zero.
- A `demo` product flavour swaps the Retrofit API for an in-memory fake, so the
  UI can be built and reviewed before the backend is deployed.

## Testing

| Suite | Count | What it covers |
| --- | --- | --- |
| `packages/shared` | 95 | money parsing and formatting, apportionment, the four split methods, itemised splits, minimal transfers, categories, SMS rules, receipt parsing |
| `apps/api` | 131 | every endpoint over HTTP with Supertest, against a real Postgres |
| `apps/android` | JVM unit | `Money` against the same vectors as the TS suite, `BankAlertParser` against real message shapes |

The API tests run against a real database because the interesting failures are
transactional: a settlement applied to shares, an outbox row committed with the
expense that produced it, a refresh-token family revoked on reuse. A mocked
Prisma client asserts that the code called the functions it was written to call,
which is not the same as asserting the data is right — the double-counting
balance bug would have passed a mocked suite and did fail the real one.

`Money` is tested against the same vectors in both languages on purpose. The two
implementations exist to agree, and the tests are what holds them to it.
