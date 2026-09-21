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

**CI needs none, and that is deliberate.** Every job runs on committed defaults:

- The API tests use a throwaway Postgres service, `OCR_PROVIDER=stub` (no
  credentials, fixed output) and a `JWT_SECRET` written inline in the workflow — a
  disposable value for a disposable database in a container that is destroyed
  minutes later. It is not a secret, and treating it as one would only hide it from
  the people who need to read the workflow.
- The Android build reads `snaptab.apiBaseUrl` and `snaptab.googleClientId` from
  `local.properties`, which is not committed. Absent, they fall back to
  `http://10.0.2.2:4000/` and an empty string, and an empty Google client id simply
  means the sign-in screen does not offer Google.

So nothing needs to go into GitHub secrets today. **Anything that later does goes
in `Settings → Secrets and variables → Actions`**, and is referenced as
`${{ secrets.NAME }}` — never written into a file in the repository.

The things that would need it, when you get to them:

| Secret | Needed for | Notes |
| --- | --- | --- |
| `GOOGLE_VISION_API_KEY` | real OCR in a deployed API | Not needed by CI; the tests use the stub. |
| `GOOGLE_CLIENT_IDS` | Google sign-in on the API side | Comma-separated. Not secret in the cryptographic sense — a client id is public — but keep it configurable. |
| `JWT_SECRET` | any real deployment | `openssl rand -base64 48`. Rotating it signs everyone out, which is the intended behaviour after a compromise. |
| `FCM_SERVER_KEY` | push notifications | Without it, notifications are written to the database and shown in-app only. |
| `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | a signed release APK or AAB | Decode the keystore to a temp file in the job, sign, and let the runner be destroyed. Never commit the `.jks`, and never `echo` a password into the log. |
| `GOOGLE_SERVICES_JSON` | Firebase Messaging in a release build | Written to `apps/android/app/google-services.json` during the job. The build applies the plugin only if the file exists, so its absence is not an error. |

One rule behind all of it: **a secret belongs to an environment, not to a
repository.** If a value differs between your laptop, CI and production, it is
configuration and it goes in the environment. If it would embarrass you in a public
log, it goes in secrets. If it is the same everywhere and harmless, like the OCR
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

## Adding release signing later

Generate a keystore, keep it somewhere that is not the repository, and add the four
secrets from the table above. Then in `apps/android/app/build.gradle.kts`, read
them from the environment rather than from a file:

```kotlin
signingConfigs {
    create("release") {
        val keystore = System.getenv("ANDROID_KEYSTORE_PATH")
        if (keystore != null) {
            storeFile = file(keystore)
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }
}
```

Guarding on null keeps a local `./gradlew assembleRelease` working without the
secrets, which is what you want when you are only checking that R8 does not break
anything.

Losing the keystore means you can never update that app on Play again, so back it
up somewhere that is not a laptop and not this repository.
