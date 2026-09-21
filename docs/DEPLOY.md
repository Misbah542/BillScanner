# Deploying the API

The app needs four things from wherever this runs: **HTTPS**, a **stable domain**, a
**Postgres**, and an **object store**. Get those four right and the app works; get the
domain wrong and you ship an APK that can never reach its server.

- [Read this first: the domain is baked into the APK](#read-this-first-the-domain-is-baked-into-the-apk)
- [What has to run](#what-has-to-run)
- [Which cloud](#which-cloud)
- [Google Cloud — Cloud Run](#google-cloud--cloud-run)
- [AWS — App Runner](#aws--app-runner)
- [Oracle Cloud — a free ARM VM](#oracle-cloud--a-free-arm-vm)
- [Object storage on each cloud](#object-storage-on-each-cloud)
- [Migrations](#migrations)
- [Environment variables](#environment-variables)
- [Pointing the app at it](#pointing-the-app-at-it)
- [Things that will bite you](#things-that-will-bite-you)

## Read this first: the domain is baked into the APK

`snaptab.apiBaseUrl.release` is compiled into the release build as a `BuildConfig`
constant. An installed APK cannot be told about a new address — you would have to ship an
update and wait for everyone to take it.

So decide the hostname **before** you cut a release, and make it one you control:
`api.yourdomain.com`, not the platform's generated URL. Cloud Run gives you
`snaptab-api-xxxxx-uc.a.run.app`, App Runner gives you
`xxxxx.eu-west-1.awsapprunner.com` — both are stable in practice, but both belong to a
service and a region you may want to move off. A CNAME you own costs nothing and means
the APK never has to change.

It must be **https**. The release build's network security config permits no cleartext at
all, so a plain-HTTP API fails with no useful error on the device. All three options below
terminate TLS for you.

## What has to run

Two processes from **the same image**, plus a migration step:

| | Command | Why |
| --- | --- | --- |
| API | `node dist/index.js` (the image default) | Serves HTTP. Stateless — scale it freely. |
| Worker | `node dist/workers/index.js` | Runs OCR and drains the outbox. Needs to be **always on**: it polls. |
| Migrations | `npx prisma migrate deploy` | Once per deploy, before the new revision takes traffic. |

The worker is the part that does not fit a request-driven platform. It polls Postgres for
queued scans and unsent notifications, so a container that is frozen between requests, or
scaled to zero, does no work. Each section below says how to keep it running.

You can also run both in one container for small volumes — start the worker as a child of
the API — but they are separate on purpose: OCR is slow and bursty, and you do not want a
scan storm competing with people loading their home screen.

## Which cloud

| | Cloud Run (GCP) | App Runner (AWS) | Oracle VM |
| --- | --- | --- | --- |
| Effort | low | low | medium |
| HTTPS + domain | free, managed | free, managed | you set up Caddy or nginx |
| Postgres | Cloud SQL (~$9/mo min) | RDS (free 12 months, then ~$15) | in Docker on the box, free |
| Object storage | Cloud Storage | S3 | Object Storage |
| Scales to zero | yes | no (min 1 instance) | n/a |
| Always-free tier | Cloud Run yes, Cloud SQL **no** | no | **yes, genuinely** |
| Worker | second service, `--min-instances=1` | second service | just another container |
| You patch the OS | no | no | **yes** |

**Recommendation: Cloud Run.** It takes the existing Dockerfile unchanged, gives managed
HTTPS and a custom domain, and the two-services-one-image shape fits the API/worker split
exactly. Pair it with Cloud SQL if you want one bill, or with **Neon** or **Supabase** if
you want a Postgres with a real free tier — both are plain Postgres and the connection
string is all that changes.

**If cost is the deciding factor, Oracle.** The always-free ARM allocation (4 cores,
24GB RAM) runs the whole stack — API, worker, Postgres, Caddy — in Docker on one box, for
nothing, permanently. The trade is that you are the one applying security updates and
taking backups. For a personal app that is a reasonable deal; for other people's money it
is a commitment worth being honest with yourself about.

**AWS App Runner** is the middle: as easy as Cloud Run, but no scale-to-zero, so the floor
is roughly $5–10/month before RDS.

## Google Cloud — Cloud Run

```bash
gcloud config set project YOUR_PROJECT
gcloud services enable run.googleapis.com sqladmin.googleapis.com artifactregistry.googleapis.com

# Postgres
gcloud sql instances create snaptab-db --database-version=POSTGRES_16 \
  --tier=db-f1-micro --region=asia-south1
gcloud sql databases create snaptab --instance=snaptab-db
gcloud sql users set-password postgres --instance=snaptab-db --password='...'

# Build the image. The Dockerfile is at apps/api/Dockerfile but the build context is the
# repo root, because it copies packages/shared too.
gcloud builds submit --tag gcr.io/YOUR_PROJECT/snaptab-api -f apps/api/Dockerfile .
```

Store the secrets once, rather than passing them on the command line where they land in
your shell history and in the service's describe output:

```bash
printf '%s' "$(openssl rand -base64 48)" | gcloud secrets create snaptab-jwt --data-file=-
printf '%s' 'postgresql://postgres:...@/snaptab?host=/cloudsql/YOUR_PROJECT:asia-south1:snaptab-db' \
  | gcloud secrets create snaptab-db-url --data-file=-
```

Deploy the API:

```bash
gcloud run deploy snaptab-api \
  --image gcr.io/YOUR_PROJECT/snaptab-api \
  --region asia-south1 \
  --allow-unauthenticated \
  --add-cloudsql-instances YOUR_PROJECT:asia-south1:snaptab-db \
  --set-secrets 'JWT_SECRET=snaptab-jwt:latest,DATABASE_URL=snaptab-db-url:latest' \
  --set-env-vars 'NODE_ENV=production,STORAGE_DRIVER=s3,S3_BUCKET=snaptab-receipts,S3_ENDPOINT=https://storage.googleapis.com,S3_REGION=auto,PUBLIC_BASE_URL=https://api.yourdomain.com,APP_SHARE_BASE_URL=https://api.yourdomain.com/t,OCR_PROVIDER=google-vision'
```

Cloud Run sets `PORT` to 8080; the app reads it, so nothing to configure.

Deploy the worker as a **second service from the same image**, with the command
overridden and kept warm:

```bash
gcloud run deploy snaptab-worker \
  --image gcr.io/YOUR_PROJECT/snaptab-api \
  --region asia-south1 \
  --no-allow-unauthenticated \
  --command node --args dist/workers/index.js \
  --min-instances 1 --no-cpu-throttling \
  --add-cloudsql-instances YOUR_PROJECT:asia-south1:snaptab-db \
  --set-secrets 'JWT_SECRET=snaptab-jwt:latest,DATABASE_URL=snaptab-db-url:latest' \
  --set-env-vars 'NODE_ENV=production,STORAGE_DRIVER=s3,S3_BUCKET=snaptab-receipts,S3_ENDPOINT=https://storage.googleapis.com,S3_REGION=auto,OCR_PROVIDER=google-vision'
```

`--min-instances 1` and `--no-cpu-throttling` are both required. Without the first it
scales to zero and never wakes, because nothing sends it a request. Without the second,
Cloud Run freezes the CPU between requests and the poller simply stops — the container
looks healthy and does nothing, which is the worst kind of broken.

Then map the domain:

```bash
gcloud beta run domain-mappings create --service snaptab-api \
  --domain api.yourdomain.com --region asia-south1
```

## AWS — App Runner

Push the image to ECR, then create the service from it. App Runner gives managed HTTPS and
reads `PORT` the same way.

```bash
aws ecr create-repository --repository-name snaptab-api
docker build -f apps/api/Dockerfile -t snaptab-api .
docker tag snaptab-api "$ACCOUNT.dkr.ecr.$REGION.amazonaws.com/snaptab-api:latest"
aws ecr get-login-password | docker login --username AWS --password-stdin "$ACCOUNT.dkr.ecr.$REGION.amazonaws.com"
docker push "$ACCOUNT.dkr.ecr.$REGION.amazonaws.com/snaptab-api:latest"
```

Create an RDS Postgres 16 instance in the same VPC, then create two App Runner services
from the one image — the API on the default command, the worker with the command
overridden to `node dist/workers/index.js`. App Runner has no scale-to-zero, so the worker
needs nothing special beyond existing.

Give the services an **instance role** with `s3:GetObject`, `s3:PutObject` and
`s3:DeleteObject` on the bucket, and then leave `S3_ACCESS_KEY_ID` and
`S3_SECRET_ACCESS_KEY` unset — the SDK picks the role up automatically and there is no key
to leak or rotate. That is the one real advantage of running on the same cloud as your
storage.

Secrets go in Secrets Manager and are referenced from the service configuration.

## Oracle Cloud — a free ARM VM

Create an **Ampere A1** instance (ARM, up to 4 OCPU / 24GB on the always-free tier) with
Ubuntu 22.04, open ports 80 and 443 in the security list, then:

```bash
sudo apt update && sudo apt install -y docker.io docker-compose-v2 git
sudo usermod -aG docker ubuntu   # log out and back in

git clone https://github.com/Misbah542/BillScanner.git snaptab && cd snaptab
cp apps/api/.env.example apps/api/.env
# edit: JWT_SECRET, DATABASE_URL, PUBLIC_BASE_URL, APP_SHARE_BASE_URL
```

`docker-compose.yml` already brings up Postgres and the API. Add the worker and a reverse
proxy that gets you a certificate:

```yaml
# docker-compose.override.yml
services:
  worker:
    build:
      context: .
      dockerfile: apps/api/Dockerfile
    command: node dist/workers/index.js
    restart: unless-stopped
    depends_on:
      postgres: { condition: service_healthy }
    env_file: apps/api/.env

  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    ports: ['80:80', '443:443']
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy-data:/data

volumes:
  caddy-data:
```

```
# Caddyfile — Caddy gets and renews the certificate on its own
api.yourdomain.com {
    reverse_proxy api:4000
}
```

```bash
docker compose up -d
docker compose exec api npx prisma migrate deploy
```

The image is built for whatever architecture you build it on, so building on the ARM box
is the simplest path — no cross-compilation, and `node:22-alpine` is multi-arch.

Here `STORAGE_DRIVER=local` is a legitimate choice, because the container has a real disk
under it. Mount a volume at `/data/storage`, set `STORAGE_LOCAL_DIR=/data/storage`, and
**back it up** — it is the one piece of state the server cannot refetch.

## Object storage on each cloud

The `s3` driver talks to any S3-compatible store, so the same code covers all three. Only
the environment changes.

**AWS S3** — nothing but the bucket and region:

```
STORAGE_DRIVER=s3
S3_BUCKET=snaptab-receipts
S3_REGION=ap-south-1
```

**Google Cloud Storage** — through its S3-compatible XML API. Create an HMAC key for a
service account (Cloud Storage → Settings → Interoperability):

```
STORAGE_DRIVER=s3
S3_BUCKET=snaptab-receipts
S3_ENDPOINT=https://storage.googleapis.com
S3_REGION=auto
S3_ACCESS_KEY_ID=GOOG1E...
S3_SECRET_ACCESS_KEY=...
```

**Oracle Object Storage** — through its S3 compatibility endpoint, with a Customer Secret
Key from your user's profile:

```
STORAGE_DRIVER=s3
S3_BUCKET=snaptab-receipts
S3_ENDPOINT=https://YOUR_NAMESPACE.compat.objectstorage.ap-mumbai-1.oraclecloud.com
S3_REGION=ap-mumbai-1
S3_ACCESS_KEY_ID=...
S3_SECRET_ACCESS_KEY=...
```

**Keep the bucket private.** Receipts are photographs of people's restaurant bills, often
with the last four digits of a card on them. The API streams them through an authenticated
route and `publicUrl()` deliberately always returns null, so a private bucket costs you
nothing and a public one is a data leak waiting for someone to guess a key.

## Migrations

Run them **once per deploy, before the new revision serves traffic** — never from the
container's start command. Every starting instance would race every other, and a rollback
would find the schema already moved on.

```bash
# Cloud Run: a one-off job against the same image
gcloud run jobs create snaptab-migrate \
  --image gcr.io/YOUR_PROJECT/snaptab-api \
  --region asia-south1 \
  --command npx --args 'prisma,migrate,deploy' \
  --add-cloudsql-instances YOUR_PROJECT:asia-south1:snaptab-db \
  --set-secrets 'DATABASE_URL=snaptab-db-url:latest,JWT_SECRET=snaptab-jwt:latest'
gcloud run jobs execute snaptab-migrate --region asia-south1 --wait
```

Seed the category taxonomy once, on a fresh database — the same job with
`--args 'tsx,prisma/seed.ts'`, or `npm run db:seed` wherever you can reach the database.
Leave `SEED_DEMO` unset in production; it creates a sample account.

## Environment variables

Required, or the process refuses to start — `env.ts` validates the whole set at boot and
crashes with a readable list rather than failing later on somebody's request:

| | |
| --- | --- |
| `DATABASE_URL` | Postgres connection string |
| `JWT_SECRET` | 32+ chars, `openssl rand -base64 48` |
| `PUBLIC_BASE_URL` | `https://api.yourdomain.com` |
| `APP_SHARE_BASE_URL` | `https://api.yourdomain.com/t` |

Worth setting in production:

| | |
| --- | --- |
| `NODE_ENV=production` | Turns off the pretty logger and the dev OTP echo |
| `STORAGE_DRIVER=s3` + the `S3_*` set | Anywhere without a real disk |
| `OCR_PROVIDER=google-vision` + `GOOGLE_VISION_API_KEY` | Real receipt parsing; `stub` returns fixed output |
| `GOOGLE_CLIENT_IDS` | Enables Google sign-in. Unset, `/v1/auth/google` returns 501 and the app hides the button |
| `FCM_SERVER_KEY` | Push. Unset, notifications are written to the database and shown in-app only |
| `SMTP_URL`, `SMS_GATEWAY_URL`, `SMS_GATEWAY_KEY` | Actually delivering OTPs. **Unset, codes are printed to the log** — fine for a demo, not for real users |

The full list with defaults is `apps/api/.env.example`.

## Pointing the app at it

```properties
# apps/android/local.properties
snaptab.apiBaseUrl.release=https://api.yourdomain.com/
```

Trailing slash included — Retrofit requires it. Then build a signed release per
[CI.md](CI.md#building-a-release). A live release build refuses to proceed without this
set, on purpose.

Check the server is reachable and configured the way you think before you build:

```bash
curl -s https://api.yourdomain.com/healthz              # liveness, no database
curl -s https://api.yourdomain.com/readyz               # readiness, 503 if Postgres is down
curl -s https://api.yourdomain.com/v1/config | jq       # which sign-in methods are live
```

`/v1/config` is the useful one: it reports whether Google sign-in is enabled and which OCR
provider is in use, so you can tell a misconfigured deploy from a working one without
installing anything.

Point the two health endpoints at the platform's probes — `/healthz` for liveness, because
it deliberately does not touch the database and so stays up while Postgres restarts, and
`/readyz` for readiness, because it does and will hold traffic back until the database is
actually there.

## Things that will bite you

**Rate limiting is per-instance.** `express-rate-limit` keeps its counters in memory, so
with three instances the effective limit is three times what it says. The OTP limits are
the ones that matter — 5 requests per 15 minutes becomes 15. For a personal deployment on
one instance this is moot; before opening it up, move the store to Redis.

**Cloud SQL has no free tier.** Cloud Run does, generously, which makes the database the
whole bill. Neon and Supabase both have real Postgres free tiers and are a connection
string away.

**A frozen worker looks healthy.** This is the failure worth knowing in advance: on Cloud
Run without `--no-cpu-throttling`, the worker's container is up, its logs are quiet, and
scans sit in `QUEUED` forever. If uploads stop being parsed, check that first.

**The database is the queue.** There is no Redis and no broker — scans and notifications
drain from Postgres tables. That is one less thing to run, and it means the database is the
single point of failure: size it and back it up accordingly.

**OTPs go to the log without a mail or SMS gateway.** Anyone with log access can sign in as
anyone. Fine while it is only you; set `SMTP_URL` before it is not.

**Receipt uploads are capped at 12MB** (`SCAN_MAX_BYTES`). If your platform's own request
limit is lower, uploads fail at the proxy with an error the app cannot explain. App Runner
allows larger; check before raising it.
