# CI, secrets and builds

## What runs, and when

`.github/workflows/ci.yml` runs on every push and every pull request, in three jobs.

**`hygiene`** goes first and takes seconds, so a leak fails the run before anything
expensive starts. It fails the build if a `.env` file, a `local.properties`, a
`.pem`/`.p12`/`.jks`/`.keystore`, a `google-services.json` or a `key.properties`
has been committed, and it fails if one of the two allowed templates
(`apps/api/.env.example`, `apps/android/local.properties.example`) has picked up
something that looks like a real value rather than a placeholder.

**`js`** builds `@snaptab/shared`, checks that the app's bundled copies of the
shared rule files are in sync with the originals, typechecks both workspaces,
applies the migrations to a throwaway Postgres 16 service and runs the shared (95)
and API (131) suites.

The sync check matters more than it looks. `packages/shared/data/sms-rules.json` is
read by the TypeScript parser and copied into the Android assets for the Kotlin
one. If the copies drift, the two parsers compute different fingerprints for the
same bank SMS and the user sees the same transaction twice. So a stale asset is a
failed build, not a warning.

**`android`** runs the unit tests for both flavours, lints, builds the demo APK and
uploads it.

## Secrets

**`ci.yml` needs none, and that is deliberate.** Every job in it runs on committed
defaults. Only `release.yml`, which signs a release, uses secrets — and it runs only
when a maintainer asks.

- The API tests use a throwaway Postgres service, `OCR_PROVIDER=stub` (no
  credentials, fixed output) and a `JWT_SECRET` written inline in the workflow — a
  disposable value for a disposable database in a container that is destroyed
  minutes later. It is not a secret, and treating it as one would only hide it from
  the people who need to read the workflow.
- The Android build reads `snaptab.apiBaseUrl` and `snaptab.googleClientId` from
  `local.properties`, which is not committed. Absent, they fall back to
  `http://10.0.2.2:4000/` and an empty string, and an empty Google client id simply
  means the sign-in screen does not offer Google.

**Everything secret goes in `Settings → Secrets and variables → Actions`** and is
referenced as `${{ secrets.NAME }}` — never written into a file in the repository.

| Secret | Needed for | Notes |
| --- | --- | --- |
| `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | a signed release APK or AAB | Used by `release.yml`. See [Building a release](#building-a-release). |
| `ANDROID_RELEASE_API_BASE_URL` | a `live` release build | Which API the released app talks to. Not secret so much as per-deployment, and there is no default. |
| `JWT_SECRET` | any real API deployment | `openssl rand -base64 48`. Rotating it signs everyone out, which is the intended behaviour after a compromise. |
| `GOOGLE_VISION_API_KEY` | real OCR in a deployed API | Not needed by CI; the tests use the stub. |
| `GOOGLE_CLIENT_IDS` | Google sign-in on the API side | Comma-separated. A client id is not secret in the cryptographic sense, but keep it configurable. |
| `FCM_SERVER_KEY` | push notifications | Without it, notifications are written to the database and shown in-app only. |
| `GOOGLE_SERVICES_JSON` | Firebase Messaging in a release build | Write it to `apps/android/app/google-services.json` during the job. The build applies the plugin only if that file exists, so its absence is not an error. |

### What is in this repository, in full

The repo is public, so this is the complete list of committed files that hold
configuration, and what is in each:

| File | Contents |
| --- | --- |
| `apps/api/.env.example` | Variable names with placeholder values — `JWT_SECRET=dev-only-change-me-…`, every key blank. Copied to `.env`, which is git-ignored. |
| `apps/api/.env.test.example` | The same for the test suite. Copied to `.env.test`, git-ignored. |
| `apps/android/local.properties.example` | `sdk.dir` and commented-out keys. Copied to `local.properties`, git-ignored. |
| `.github/workflows/ci.yml` | A `JWT_SECRET` for a database that exists for 30 seconds inside a container that is then destroyed. Deliberately in the clear: hiding it would only stop people reading the workflow. |
| `docker-compose.yml` | `snaptab`/`snaptab` for a local Postgres on `localhost`, and `JWT_SECRET` as a required variable with no default, so `docker compose up` fails rather than starting with a guessable one. |

No `.env`, no `local.properties`, no keystore and no `google-services.json` is
committed, and the `hygiene` job fails the build if one ever is. Anyone cloning this
gets templates and has to supply their own values, which is the point.

One rule behind all of it: **a secret belongs to an environment, not to a
repository.** If a value differs between your laptop, CI and production, it is
configuration and it belongs in the environment. If it would embarrass you in a public
log, it belongs in secrets. If it is the same everywhere and harmless, like the OCR
stub setting, put it in the workflow where it can be read.

## Getting the demo APK

CI builds it on every push. To download one:

1. Open the **Actions** tab, pick the run for your commit.
2. Scroll to **Artifacts** and download `snaptab-demo-debug`.
3. Unzip, then `adb install -r app-demo-debug.apk`.

Or build it locally, which is faster if you have the SDK:

```bash
cd apps/android
./gradlew installDemoDebug     # builds and installs to the attached device
```

Check the device is visible first with `adb devices`; an `unauthorized` line means
the phone is waiting for you to accept the USB-debugging prompt on its screen.

## What is in the demo APK, and what is not

The demo flavour is the build meant to be handed round, so it is built to be safe
to hand round.

- **It talks to no server.** `FakeSnapTabApi` serves every screen from seeded
  in-memory data. There is no base URL to leak and no traffic to intercept.
- **It cannot read your messages.** `src/demo/AndroidManifest.xml` removes
  `RECEIVE_SMS` and `READ_SMS` with `tools:node="remove"`, and removes the receiver
  with them. This is not a runtime toggle that could be flipped: the permissions
  are not in the APK, so the OS would refuse them. The bank alerts you see in its
  inbox are sample data.
- **Its sample data is invented.** The names are fictional and the email addresses
  are `@example.com`, which is reserved by RFC 2606 and can never belong to anyone.
- **It is a debug build,** signed with the standard Android debug keystore. That
  keystore is public and shared by every developer, so a debug APK proves nothing
  about who built it — fine for testing, never for distribution as the real app.
- **It has a different application id** (`com.snaptab.app.demo`) and a clay icon
  rather than teal, so it installs alongside the real app and is obvious in the
  drawer.

Two things to know before posting a debug APK publicly:

**An APK is not a black box.** Anything the build put into `BuildConfig` — a base
URL, an API key, a client id — is readable in a few seconds with `apktool` or
`jadx`. That is why `snaptab.apiBaseUrl` and `snaptab.googleClientId` come from an
uncommitted `local.properties`: whatever is in it at build time ships inside the
APK. Build the demo flavour with an empty or default `local.properties` and there
is nothing in there worth reading.

**Debug builds permit cleartext to loopback.** `src/debug/res/xml/network_security_config.xml`
allows plain HTTP to `10.0.2.2`, `127.0.0.1` and `localhost`, which is what a local
API needs. Release builds use the config in `src/main`, which permits no cleartext
at all. The demo flavour still builds as a debug variant, so it carries the
permissive config — harmless, since those addresses are the device itself and the
demo makes no network calls, but it is the reason a debug APK is not a release APK.

## Building a release

`.github/workflows/release.yml` builds a signed release. It is separate from `ci.yml`
because it touches the signing key: CI runs on every push and must never need a
secret, so the one workflow that does runs only when a maintainer asks.

Run it from **Actions → Release → Run workflow**, choosing:

- **flavour** — `demo` (no server, no SMS permissions, safe to post anywhere) or
  `live` (talks to your API).
- **artifact** — `apk` for a direct download people sideload, `aab` for the Play
  Store.

Pushing a tag like `v0.1.0` builds a `live` AAB.

Locally:

```bash
cd apps/android
./gradlew assembleDemoRelease     # needs nothing configured
./gradlew assembleLiveRelease     # needs the two settings below
./gradlew bundleLiveRelease       # an AAB for Play
```

### What a live release needs

Two things, neither of them in the repository.

**1. The API address.** There is deliberately no default. It used to default to
`https://api.snaptab.app/`, a domain nobody here owns — and since an APK is trivially
decompiled, that address is visible to everyone who downloads one, so whoever
registers the domain first receives the sign-in traffic of every install. A live
release build now fails until you say where it points:

```properties
# apps/android/local.properties
snaptab.apiBaseUrl.release=https://api.yourdomain.com/
```

It must be `https`. `assembleDemoRelease` needs none of this, because it reaches no
network at all.

**2. A signing key.** Generate one once:

```bash
keytool -genkeypair -v -keystore ~/snaptab-release.jks \
  -alias snaptab -keyalg RSA -keysize 4096 -validity 10000
```

Then either point `local.properties` at it:

```properties
snaptab.keystorePath=/Users/you/snaptab-release.jks
snaptab.keystorePassword=…
snaptab.keyAlias=snaptab
snaptab.keyPassword=…
```

or, for CI, add these repository secrets under
**Settings → Secrets and variables → Actions**:

| Secret | How to produce it |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 ~/snaptab-release.jks` (macOS: `base64 -i ~/snaptab-release.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | the store password |
| `ANDROID_KEY_ALIAS` | `snaptab`, or whatever you passed to `-alias` |
| `ANDROID_KEY_PASSWORD` | the key password |
| `ANDROID_RELEASE_API_BASE_URL` | `https://api.yourdomain.com/` |

The workflow checks all five are present before it builds anything, decodes the
keystore into the runner's temp directory (never the workspace, where a later step
could archive it), and verifies with `apksigner` that what came out is actually
signed. A release that silently came out unsigned is the failure this workflow exists
to prevent, so it is checked rather than assumed.

**Back up the keystore somewhere that is neither this repository nor only your
laptop.** It is the one secret that cannot be rotated: lose it and you can never
publish an update to this app again, because Play identifies an app by its signing
certificate. Leak it and someone else can publish an update that Android will accept
as yours.

With nothing configured, a release build is simply **unsigned** — which still
exercises R8 and proves the minified build works, but will not install. That is
deliberate: falling back to the debug key would produce an installable APK signed
with a certificate every Android developer on earth already has the private key for,
which is worse than one that will not install.

### Keep mapping.txt

R8 obfuscates, so a stack trace from a release build is unreadable without the
mapping file for that exact build. `apps/android/app/build/outputs/mapping/*/mapping.txt`
is included in the release artifact. Keep it with the release; regenerating it is not
possible.

### Why CI builds a release on every push

`ci.yml` runs `assembleDemoRelease`, which needs no key and no API address. The point
is R8: **a missing keep rule does not fail the build**, it produces an APK that
installs and then throws on its first API call, and debug builds never run R8 so
normal testing cannot catch it. The prototype's `proguard-rules.pro` was exactly this
trap — Gson rules for an app that uses kotlinx-serialization, and no serialization
keep rules at all, so every release response would have failed to deserialise.
Building the minified variant on every push is what makes that a build failure
instead of a bug report.
