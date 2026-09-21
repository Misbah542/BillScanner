# API reference

Base URL `http://localhost:4000` in development. Everything below is under `/v1`
except the health probes and the public share view.

- [Conventions](#conventions)
- [Errors](#errors)
- [Rate limits](#rate-limits)
- [Health and config](#health-and-config)
- [Auth](#auth-v1auth)
- [Users](#users-v1users)
- [Groups](#groups-v1groups)
- [Expenses](#expenses-v1expenses)
- [Scans](#scans-v1scans)
- [Bank alerts](#bank-alerts-v1alerts)
- [Settlements](#settlements-v1settlements)
- [Categories](#categories-v1categories)
- [Insights](#insights-v1insights)
- [Share links](#share-links-v1share-links)
- [Public share view](#public-share-view-t)

## Conventions

**Money is always an integer of minor units** — paise for INR — in a field ending
`Minor`. `"totalMinor": 257500` is ₹2,575.00. Never a float, never a string.

**Percentages are basis points** in fields ending `Bp`: `3333` is 33.33%. This is
so three equal shares can add up to exactly 100%.

**Auth** is `Authorization: Bearer <accessToken>` on everything except
`/healthz`, `/readyz`, `/v1/config`, `/v1/auth/*` (apart from logout) and `/t/*`.
Access tokens last 15 minutes; refresh before they expire.

**Timestamps** are ISO 8601 UTC. `month` parameters are `YYYY-MM`.

**Ids** are UUIDs.

**Paging** is cursor-based: pass the `nextCursor` from a response back as
`cursor`. A response with no `nextCursor` is the last page.

**`x-request-id`** is echoed on every response, and accepted on request if you
want to set it. Quote it in a bug report; it is the log key.

## Errors

One shape, always:

```json
{
  "error": {
    "code": "SPLIT_PERCENT_SUM",
    "message": "The percentages add up to 90%, not 100%.",
    "details": { "totalBp": 9000 }
  }
}
```

`message` is written to be shown to a person — the Android app displays it
directly. `details` is for the client to act on: which field, which item, which
number.

| Status | Codes you will actually see |
| --- | --- |
| 400 | `NO_IMAGE`, `BAD_REQUEST`, and other request-shape problems |
| 401 | `UNAUTHENTICATED`, `TOKEN_EXPIRED`, `SESSION_REVOKED` |
| 403 | `FORBIDDEN`, `NOT_A_MEMBER` |
| 404 | `NOT_FOUND`, `ROUTE_NOT_FOUND` |
| 409 | `ALREADY_EXISTS`, `MEMBER_HAS_BALANCE`, `REFRESH_REUSED` |
| 413 | `PAYLOAD_TOO_LARGE` |
| 422 | `VALIDATION_FAILED`, `SPLIT_*` |
| 429 | `RATE_LIMITED` |
| 501 | `GOOGLE_SIGN_IN_DISABLED` |

`VALIDATION_FAILED` carries one entry per bad field:

```json
{ "error": { "code": "VALIDATION_FAILED", "message": "Some of that could not be accepted.",
  "details": [{ "path": "participants.0.value", "message": "Expected number" }] } }
```

`SPLIT_*` codes come from the shared split engine and name the arithmetic that
failed: `SPLIT_PERCENT_SUM`, `SPLIT_EXACT_SUM`, `SPLIT_ITEM_UNASSIGNED` (with the
`itemId` in `details`), `SPLIT_NO_PARTICIPANTS`.

## Rate limits

Keyed per account once authenticated, per IP before that. Draft-7 `RateLimit-*`
headers are returned.

| Scope | Limit |
| --- | --- |
| `POST /v1/auth/otp/start` | 5 per 15 min |
| `POST /v1/auth/otp/verify`, `/v1/auth/google` | 10 per 15 min |
| `POST /v1/scans`, `/v1/scans/:id/retry` | 120 per hour |
| Any other write | 240 per minute |
| `/t/*` | 60 per minute |

## Health and config

### `GET /healthz`
Liveness. Never touches the database, so it stays up while Postgres restarts.

```json
{ "ok": true, "service": "snaptab-api", "uptimeSeconds": 431 }
```

### `GET /readyz`
Readiness. `503` when Postgres is unreachable, so a rollout waits instead of
routing traffic at a broken instance.

### `GET /v1/config`
What the app asks for on launch: which sign-in methods to offer, and whether its
bundled rule files are stale.

```json
{
  "auth": { "email": true, "phone": true, "google": false },
  "scan": { "maxBytes": 12582912, "provider": "stub" },
  "rules": { "sms": 3, "categories": 2 },
  "shareBaseUrl": "http://localhost:4000/t"
}
```

`auth.google` is `false` when `GOOGLE_CLIENT_IDS` is unset, and the app hides the
button rather than offering one that cannot work.

## Auth (`/v1/auth`)

### `POST /v1/auth/otp/start`
```json
{ "contact": "misbah@example.com" }
```
`contact` may be an email or a phone number; the server works out which. Replies
`202`:

```json
{ "channel": "EMAIL", "sentTo": "m•••••@example.com", "expiresInSeconds": 600, "devCode": "418203" }
```

The response is identical whether or not an account exists — this endpoint cannot
be used to discover who has a SnapTab account. `devCode` is returned outside
production only, so a checkout with no SMTP or SMS gateway can still sign in.

Requesting a new code consumes any outstanding one, so an older code sitting in a
text message stops working.

### `POST /v1/auth/otp/verify`
```json
{ "contact": "misbah@example.com", "code": "418203", "deviceName": "Pixel 8" }
```
```json
{
  "accessToken": "eyJ…", "expiresIn": 900,
  "refreshToken": "aB3…",
  "user": { "id": "…", "name": "Misbah", "email": "misbah@example.com", "currency": "INR" },
  "created": true
}
```
`created` is `true` for a new account. If this contact was already in a group as
an invited placeholder, that account is claimed here, balances and history intact.

### `POST /v1/auth/google`
```json
{ "idToken": "eyJ…", "deviceName": "Pixel 8" }
```
Verifies against Google's JWKS and checks `aud` against `GOOGLE_CLIENT_IDS`.
Same response as `otp/verify`. Returns `501 GOOGLE_SIGN_IN_DISABLED` when no
client id is configured.

### `POST /v1/auth/refresh`
```json
{ "refreshToken": "aB3…" }
```
Returns a new access token **and a new refresh token**; the old one is spent.

Presenting a refresh token that has already been used returns `409
REFRESH_REUSED` and **revokes the entire token family** — every session descended
from it. The only way that happens is that a token was copied, so ending the
sessions is the right outcome.

### `POST /v1/auth/logout` · `POST /v1/auth/logout-everywhere`
Authenticated. `204`. The first ends this session, the second every session.
Access tokens are checked against the session row on each request, so this takes
effect immediately rather than at the end of the token's TTL.

### `GET /v1/auth/methods`
Unauthenticated. `{ "email": true, "phone": true, "google": false }`.

## Users (`/v1/users`)

### `GET /v1/users/me`
```json
{
  "user": { "id": "…", "name": "Misbah", "email": "…", "phone": null,
            "avatarUrl": null, "currency": "INR", "timezone": "Asia/Kolkata",
            "locale": "en-IN", "keepAlertBodies": false, "createdAt": "…" },
  "counts": { "groups": 3, "expensesThisMonth": 22, "unreadNotifications": 2 }
}
```

### `PATCH /v1/users/me`
Any of `name`, `avatarUrl`, `currency`, `locale`, `timezone`, `keepAlertBodies`.

`keepAlertBodies` is the SMS privacy switch. Off by default: bank alert bodies are
parsed on the device and discarded, and only the structured result is uploaded.
Turn it on and the text is kept too, which is only useful for asking why a
message was read the way it was.

### `GET /v1/users/lookup?contact=…`
"Is this person already on SnapTab?", for the add-people screen.

```json
{ "found": true, "user": { "id": "…", "name": "Aditi", "avatarUrl": null }, "kind": "EMAIL" }
```

Returns a name and avatar for a match and nothing at all otherwise — no id, no
contact detail — so it cannot be used to enumerate accounts. Invited placeholders
report `found: false`.

### `GET /v1/users/recent`
People you have split with lately, most recent first, for the top of the
add-people list.

### `GET /v1/users/me/notifications?unreadOnly=&limit=`
The in-app inbox, so a user with push disabled still sees what happened.

### `POST /v1/users/me/notifications/read`
`{ "ids": ["…"] }` — an empty array marks everything read. Returns
`{ "markedRead": 4 }`.

### `PUT /v1/users/me/device`
```json
{ "platform": "ANDROID", "pushToken": "fcm-…", "appVersion": "0.1.0", "name": "Pixel 8" }
```
Idempotent per token. Registering a token that belonged to another account moves
it, so a reflashed phone does not deliver someone else's notifications.

### `DELETE /v1/users/me`
Deletes the account. Refused with `409` while you have a non-zero balance in any
group — leaving would make other people's balances stop adding up.

## Groups (`/v1/groups`)

### `GET /v1/groups`
Your groups with member lists and your net position in each.

### `POST /v1/groups`
```json
{ "name": "Goa trip", "currency": "INR", "iconKey": "palm",
  "defaultSplitMethod": "EQUAL",
  "invite": ["aditi@example.com", "+919812345678"] }
```

Each `invite` entry is an email or a phone number. Someone with no account
becomes an `INVITED` placeholder user — a real row with real shares and a real
balance, claimed when they first sign in with that contact. This is the point: the
friend who will pay you back on Tuesday and never install anything still has to
be splittable.

### `GET /v1/groups/:id`
The group, its members, recent expenses and per-person balances.

### `PATCH /v1/groups/:id`
`name`, `description`, `currency`, `iconKey`, `defaultSplitMethod`. Owner or admin.

### `POST /v1/groups/:id/members`
```json
{ "contacts": ["aditi@example.com"], "displayNames": { "aditi@example.com": "Aditi" } }
```

### `DELETE /v1/groups/:id/members/:userId`
`204`. Refused with `409 MEMBER_HAS_BALANCE` (and the `netMinor` in `details`)
while that person still owes or is owed anything. Removing them silently would
leave the group's balances not adding up.

### `GET /v1/groups/:id/balance`
Who owes whom, and the fewest transfers that would clear it.

### `GET /v1/groups/:id/activity?limit=`
Expenses and settlements interleaved, newest first.

## Expenses (`/v1/expenses`)

The central resource. `kind` is `PERSONAL` or `SHARED`; `source` is `SCAN`,
`ALERT`, `MANUAL` or `IMPORT`. A card debit with no receipt and a five-way
restaurant bill are both rows here.

### `GET /v1/expenses`
| Query | |
| --- | --- |
| `kind` | `all` (default), `personal`, `shared` |
| `groupId`, `categorySlug`, `status` | filters |
| `from`, `to` | ISO dates; `from` inclusive, `to` exclusive |
| `search` | merchant and note |
| `limit`, `cursor` | 1–100, default 25 |

```json
{
  "expenses": [{
    "id": "…", "kind": "SHARED", "source": "SCAN", "status": "OPEN",
    "merchantName": "Mahesh Lunch Home", "occurredAt": "2026-09-14T13:20:00Z",
    "currency": "INR", "totalMinor": 257500,
    "category": { "slug": "restaurant", "name": "Restaurant", "colorHex": "#C2410C" },
    "group": { "id": "…", "name": "Goa trip" },
    "paidBy": { "id": "…", "name": "Misbah" },
    "yourShareMinor": 51500, "yourPaidMinor": 257500,
    "itemCount": 9, "hasReceipt": true
  }],
  "nextCursor": "…"
}
```

### `POST /v1/expenses`
A personal expense is nearly empty:

```json
{ "kind": "PERSONAL", "source": "MANUAL", "currency": "INR",
  "merchantName": "Blue Tokai", "totalMinor": 48000, "categorySlug": "coffee" }
```

A shared one adds the split:

```json
{
  "kind": "SHARED", "source": "SCAN", "groupId": "…", "currency": "INR",
  "merchantName": "Mahesh Lunch Home",
  "itemTotalMinor": 228500, "serviceChargeMinor": 22850,
  "taxMinor": 6168, "roundOffMinor": -18, "totalMinor": 257500,
  "items": [{ "name": "Aerated Beverages", "quantity": 2, "unitPriceMinor": 12000, "amountMinor": 24000 }],
  "taxLines": [{ "kind": "SGST", "rateBp": 250, "amountMinor": 3084 }],
  "splitMethod": "EQUAL",
  "participants": [{ "userId": "…" }, { "email": "aditi@example.com" }],
  "scanId": "…", "alertId": "…"
}
```

- `totalMinor` is required. Everything else about the amounts is optional.
- `participants` entries identify someone by `userId`, `email` or `phone`. An
  email or phone with no account becomes an invited placeholder.
- `value` on a participant is the per-method figure: minor units for `EXACT`,
  basis points for `PERCENT`, a weight for `SHARES`, ignored for `EQUAL`.
- `splitMethod: "ITEMIZED"` uses `itemAssignments` instead of participant values,
  with `extrasMode` deciding whether tax and service charge are apportioned in
  proportion to each person's items (`PROPORTIONAL`, the default) or split evenly.
- `alertId` links the bank alert this came from, so it stops asking.
- If `items` are given and their sum differs from `itemTotalMinor` by more than
  ₹1, the request is rejected — that mismatch is an OCR error worth surfacing, not
  something to silently accept.
- A `PERSONAL` expense with participants is rejected: set `kind: "SHARED"` to
  split it.

Returns `201` with the expense, its items, and the computed shares. **Shares are
guaranteed to sum to `totalMinor` exactly**, with any odd paise going to the payer.

### `GET /v1/expenses/:id`
The whole thing: items, tax lines, shares, per-item assignments, linked scan and
alert.

### `PATCH /v1/expenses/:id`
`merchantName`, `note`, `occurredAt`, `categorySlug`, `kind`, `groupId`. Setting
`categorySlug` by hand records `categorySource: USER` and teaches the per-user
merchant memory, so next time this merchant is suggested your way.

Moving a shared expense to `kind: PERSONAL` clears its shares; moving a personal
one to `SHARED` needs a split next.

### `DELETE /v1/expenses/:id`
`204`. Refused while a settlement references it — delete the settlement first, so
money that changed hands is never silently unrecorded.

### `PUT /v1/expenses/:id/split`
Split, or re-split, an existing expense.

```json
{ "method": "PERCENT",
  "participants": [{ "userId": "a…", "value": 6000 }, { "userId": "b…", "value": 4000 }] }
```

Itemised:

```json
{ "method": "ITEMIZED", "extrasMode": "PROPORTIONAL",
  "itemAssignments": [{ "itemId": "…", "assignments": [{ "userId": "a…", "weight": 1 }] }] }
```

Returns the expense with fresh shares. Amounts already paid are preserved where
the person is still in the split.

### `DELETE /v1/expenses/:id/split`
Removes the split and makes the expense personal again. Refused if anything has
been settled against it.

### `GET /v1/expenses/suggest/category?merchant=&items=&direction=`
```json
{ "suggestions": [
    { "slug": "restaurant", "name": "Restaurant", "confidence": 0.92, "reason": "MERCHANT_PATTERN" },
    { "slug": "coffee", "name": "Coffee", "confidence": 0.41, "reason": "ITEM_KEYWORD" } ],
  "top": "restaurant" }
```
Three layers, most specific first: what you filed this merchant under last time,
then the shared taxonomy, then words on the receipt. Always a suggestion with a
confidence — never a silent assignment.

## Scans (`/v1/scans`)

### `POST /v1/scans`
`multipart/form-data` with the photo as `image`. Send `Idempotency-Key` so a
retry after a dropped connection does not queue the same photo twice.

`202`:

```json
{ "scan": { "id": "…", "status": "QUEUED", "createdAt": "…" },
  "deduplicated": false, "pollAfterMs": 1200 }
```

`200` with `deduplicated: true` means that idempotency key or that exact image was
already accepted, and this is the original scan.

Recognition happens in the worker: `QUEUED → PROCESSING → SUCCEEDED | FAILED`.
The client polls or waits for the `SCAN_READY` push. Server-side, because the
parsing rules can then be fixed for every user without an app release.

### `GET /v1/scans/:id`
```json
{ "scan": {
  "id": "…", "status": "SUCCEEDED", "provider": "google-vision", "attempts": 1,
  "draft": {
    "merchantName": "Mahesh Lunch Home", "occurredAt": "2026-09-14T13:20:00Z",
    "invoiceNumber": "A-2291", "currency": "INR",
    "items": [{ "name": "Aerated Beverages", "quantity": 2, "unitPriceMinor": 12000, "amountMinor": 24000, "confidence": 0.96 }],
    "taxLines": [{ "kind": "SGST", "rateBp": 250, "amountMinor": 3084 }],
    "itemTotalMinor": 228500, "serviceChargeMinor": 22850, "totalMinor": 257500,
    "confidence": 0.88
  },
  "suggestedCategory": { "slug": "restaurant", "confidence": 0.92 } } }
```

`draft` is a **draft**: nothing is stored as an expense until the user confirms it
with `POST /v1/expenses`. Per-item confidences are there so the review screen can
flag the lines worth a second look.

`FAILED` carries a `failureReason`. `pollAfterMs` is present while non-terminal.

### `GET /v1/scans/:id/image`
The original photo, authorised, so the review screen can show it beside the parsed
items.

### `POST /v1/scans/:id/retry`
Re-queues a `FAILED` scan. `409` if it is already running or already succeeded.

## Bank alerts (`/v1/alerts`)

A `BankAlert` is a debit or credit the phone read from an SMS, before it has
become an expense. **The parsing happens on the device** — what arrives here is
the structured result, not the message, unless the user opted into bodies.

### `POST /v1/alerts`
```json
{ "rulesVersion": 3,
  "alerts": [{
    "direction": "DEBIT", "amountMinor": 48600, "currency": "INR",
    "merchantRaw": "SWIGGY", "accountMask": "4412", "accountKind": "CREDIT_CARD",
    "bankId": "hdfc", "bankName": "HDFC Bank", "referenceNumber": "504112938471",
    "channel": "SMS", "occurredAt": "2026-09-19T19:04:00Z",
    "fingerprint": "a3f…", "confidence": 0.91 }] }
```

Up to 100 per call, which is what the offline queue flushes after a spell without
connectivity. `fingerprint` is computed on the device with the shared rules and is
what the server de-duplicates on: exact on the bank's reference number where there
is one, otherwise amount + account mask + normalised merchant + calendar day. A
multipart SMS that arrives in two pieces notifies once.

```json
{ "accepted": 1, "duplicates": 2,
  "alerts": [{ "id": "…", "status": "UNMATCHED", "suggestedCategory": { "slug": "food-delivery", "confidence": 0.88 } }],
  "rulesVersion": 3, "rulesStale": false }
```

`rulesStale: true` means the server has newer rules than the bundle the device
parsed with.

### `GET /v1/alerts?status=&direction=&limit=&cursor=`
The inbox. `status` is `UNMATCHED`, `LINKED`, `SETTLED` or `IGNORED`.

### `POST /v1/alerts/:id/expense`
Turn an alert into an expense. This is the endpoint behind "where does this ₹486
go?".

Personal — the default, and the common case:

```json
{ "kind": "PERSONAL", "categorySlug": "food-delivery" }
```

Into a group you are already in:

```json
{ "kind": "SHARED", "groupId": "…" }
```

With `kind: SHARED` and only a `groupId`, the split is **everyone currently in
that group, equally**. Pass `splitMethod` and `participants` to be specific:

```json
{ "kind": "SHARED", "groupId": "…", "splitMethod": "SHARES",
  "participants": [{ "userId": "a…", "value": 2 }, { "userId": "b…", "value": 1 }] }
```

`kind: SHARED` with neither `groupId` nor `participants` is rejected with a
message naming what is missing. There is no "create a group" here on purpose: the
answer to "which group?" in this moment is always one that already exists.

On a `PERSONAL` expense a `groupId` is still allowed — it files the expense under
the group without splitting it.

`201` with the expense; the alert becomes `LINKED`.

### `POST /v1/alerts/:id/link`
`{ "expenseId": "…" }` — attach the alert to an expense that already exists, for
the case where you scanned the bill and the card SMS arrived afterwards.

```json
{ "alert": { "…": "…" }, "amountsMatch": false, "differenceMinor": 2000 }
```

The difference is reported rather than silently accepted: a ₹20 gap usually means
a tip added on the card machine.

### `POST /v1/alerts/:id/ignore`
Marks it `IGNORED`. For a salary credit or a transfer between your own accounts.

### `GET /v1/alerts/:id/suggestions`
Category suggestions for this alert, plus any existing expenses whose amount and
date make them a plausible match to link to.

### `GET /v1/alerts/rules/version`
`{ "version": 3 }` — cheap enough to check on launch so the app knows its bundled
rule file is stale.

## Settlements (`/v1/settlements`)

### `GET /v1/settlements/balance?groupId=`
```json
{ "balance": {
  "currency": "INR",
  "owedToYouMinor": 128000, "owedByYouMinor": 30000, "netMinor": 98000,
  "people": [{ "user": { "id": "…", "name": "Aditi" }, "netMinor": 51500 }],
  "transfers": [{ "fromUserId": "…", "toUserId": "…", "amountMinor": 51500 }] } }
```

`transfers` is the minimal set that clears everything: four people who each owe
two others settle in two payments, not six.

### `POST /v1/settlements`
```json
{ "toUserId": "…", "amountMinor": 51500, "method": "UPI", "groupId": "…", "note": "upi" }
```

Give exactly one of `fromUserId` or `toUserId` — the other side is you.
`fromUserId` records money received, `toUserId` money sent. Pass `expenseId` to
settle one specific expense rather than a running balance.

The settlement is applied to the `ExpenseShare` rows it clears, in the same
transaction. Shares are the only source of truth for who owes what; settlements
move `paidMinor` and are never counted again on top of that. Returns the
settlement and your recomputed balance.

### `GET /v1/settlements?groupId=&limit=`
History, newest first.

### `DELETE /v1/settlements/:id`
`204`, and the shares it had cleared go back to unpaid. Only the person who
recorded it, and only while it is `PENDING` or `CONFIRMED`.

### `POST /v1/settlements/remind`
`{ "userId": "…", "message": "beers?" }` — a nudge, through the outbox. Rate
limited per recipient per day, because a reminder people can spam is a reminder
people mute.

## Categories (`/v1/categories`)

### `GET /v1/categories`
The built-in taxonomy plus your own, each with icon and colours, and how much you
have spent in each this month.

### `POST /v1/categories`
```json
{ "name": "Climbing gym", "iconKey": "dots", "colorHex": "#5C574F", "tintHex": "#F0EBE0", "kind": "SPEND" }
```

### `DELETE /v1/categories/:id`
`204`. Your own categories only; built-ins cannot be deleted. Expenses in a
deleted category keep existing with no category rather than disappearing.

## Insights (`/v1/insights`)

### `GET /v1/insights/monthly?month=&timezone=&kind=`
The month screen. `month` is `YYYY-MM`, defaulting to the current month in your
timezone.

```json
{ "summary": {
  "month": "2026-09", "currency": "INR",
  "personalMinor": 862000,
  "sharedShareMinor": 214500,
  "spentMinor": 1076500,
  "paidOutMinor": 1580000,
  "owedToYouMinor": 503500,
  "owedByYouMinor": 30000,
  "expenseCount": 41, "personalCount": 33, "sharedCount": 8,
  "byCategory": [{ "slug": "restaurant", "name": "Restaurant", "color": "#C2410C",
                   "spentMinor": 318000, "shareBp": 2955, "count": 11 }],
  "previousSpentMinor": 994000 } }
```

The definition matters: **`spentMinor` is `personalMinor + sharedShareMinor`** —
your personal expenses in full plus *your share* of the shared ones. `paidOutMinor`
is what actually left your account, including other people's shares, and it is
deliberately a separate number. Putting ₹4,000 on your card for a table of five is
₹800 of spending and ₹3,200 of lending; counting the whole thing would make the
person who always pays look like the person who always overspends.

### `GET /v1/insights/trend?months=&timezone=&kind=`
One figure per month, oldest first, for the chart. 2–24 months.

### `GET /v1/insights/merchants?month=&limit=`
Where the money went, biggest first, with a count each.

## Share links (`/v1/share-links`)

### `POST /v1/share-links`
```json
{ "expenseId": "…", "scope": "SUMMARY", "expiresInDays": 30 }
```
Exactly one of `expenseId` or `groupId`. `SUMMARY` leaves out the receipt photo,
`FULL` includes it.

```json
{ "shareLink": { "id": "…", "token": "9fK…", "url": "http://localhost:4000/t/9fK…", "expiresAt": "…" } }
```

### `DELETE /v1/share-links/:id`
Revokes it. `204`.

## Public share view (`/t`)

Unauthenticated, rate limited separately, mounted outside `/v1`. This is the URL
you paste into a group chat.

### `GET /t/:token`
The expense: merchant, date, items, totals, and who owes what. It exposes that one
expense and nothing else — not the group, not the other expenses, not anyone's
balance.

### `GET /t/:token/receipt`
The receipt photo, and only when the link was minted with `scope: FULL`.

Expired and revoked tokens return `404`, not `403`: whether a token ever existed
is not information a stranger needs.
