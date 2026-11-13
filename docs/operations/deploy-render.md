# Deploy the API on Render + Neon + Supabase Storage

The first public release runs the API on Render Free, Postgres on Neon Free and
uploads on Supabase Storage (with Cloudflare R2 as a supported alternative). The site stays on Vercel at `nexachat.io.vn`; the API
is `https://api.nexachat.io.vn`.

| Piece | Where | Notes |
|---|---|---|
| API | Render web service `nexachat-api` | 0.1 CPU / 512 MB, sleeps after 15 idle minutes, ephemeral disk; API-created, manual deploy |
| Database | Neon Postgres | persistent, free compute, region Singapore |
| Uploads | Supabase Storage (or Cloudflare R2) | private S3-compatible bucket, region `ap-southeast-1` (path-style) |
| Site | Vercel | unchanged |

References: [Render Blueprint spec](https://render.com/docs/blueprint-spec),
[Render Free limits](https://render.com/docs/free), [Render port binding](https://render.com/docs/web-services#port-binding),
[Render health checks](https://render.com/docs/health-checks), [Render custom domains](https://render.com/docs/custom-domains),
[Neon connection pooling](https://neon.com/docs/connect/connection-pooling).

## 1. Create the Neon database

1. Create a Neon project in **AWS ap-southeast-1 (Singapore)** — the same region
   as the Render service — with PostgreSQL 16, database `nexachat`, role
   `nexachat`.
2. In **Connect**, turn **Connection pooling off** and copy the **direct**
   connection string. Append `?sslmode=require`.
3. Convert it to JDBC for Render:
   `jdbc:postgresql://<host>/nexachat?sslmode=require`, then set
   `DATABASE_URL`, `DATABASE_USER` and `DATABASE_PASSWORD`.

Use the direct string, not the `-pooler` one. Flyway and the scheduled jobs use
**session-level** advisory locks (`server/src/main/java/com/nexachat/ops/ScheduledAdvisoryLock.java:30`),
which Neon's PgBouncer transaction mode does not support. The Render profile
caps Hikari at 5 connections (`server/src/main/resources/application-render.yml`),
far below Neon's 105 available application connections on 0.25 CU.

Neon Free includes **100 CU-hours per project per month** — 400 hours at
0.25 CU. Let the compute scale to zero when idle (it suspends after 5 minutes);
an always-on database would exhaust the monthly allowance in about 16 days.

Flyway runs on every boot (`application.yml:33`, validation at
`application-prod.yml:46-47`), so the first start applies all migrations; there
is no separate migration command.

## 2. Create the storage bucket (Supabase Storage or Cloudflare R2)

The deployed topology uses **Supabase Storage** (S3 protocol, region `ap-southeast-1`,
private bucket, path-style). **Cloudflare R2** remains a supported alternative.

### Supabase Storage (deployed)

1. Supabase dashboard → **Storage** → create a private bucket named
   `nexachat-uploads` (public access disabled).
2. Project Settings → **Storage** (or API) → S3 Access Keys → generate S3 Access Keys.
3. Note the Access Key ID (`NEXACHAT_STORAGE_S3_ACCESS_KEY_ID`), Secret Access Key
   (`NEXACHAT_STORAGE_S3_SECRET_ACCESS_KEY`), region `ap-southeast-1` (`NEXACHAT_STORAGE_S3_REGION`),
   and the endpoint `https://<project-ref>.storage.supabase.co/storage/v1/s3`
   (`NEXACHAT_STORAGE_S3_ENDPOINT`).
4. Path-style addressing is enforced automatically by `S3StorageConfiguration`
   (`server/src/main/java/com/nexachat/attachment/S3StorageConfiguration.java:31`).

### Cloudflare R2 (supported alternative)

1. Cloudflare dashboard → **R2** → create a private bucket named
   `nexachat-uploads` (no public access, no custom domain).
2. **Manage R2 API Tokens** → create a token with **Object Read & Write**,
   limited to that bucket.
3. Note the Access Key ID, Secret Access Key, region `auto`, and the endpoint
   `https://<account-id>.r2.cloudflarestorage.com`.

## 3. Render service topology and deployment

The production service `nexachat-api` was created through the **Render API** with
auto-deploy disabled (`autoDeploy: "no"`). It is **not** managed by the
`render.yaml` Blueprint.

Because auto-deploy is disabled and the service is not Blueprint-managed:
- Pushing to `main` does **not** deploy. The `autoDeployTrigger: commit` line in
  `render.yaml` applies only if the service is ever re-created from the Blueprint.
- Environment variables in `render.yaml` are **not** applied automatically.
  `render.yaml` serves as the reference specification for variables and JVM settings.
- Every release requires an environment variable diff against `render.yaml` and a
  manual deploy of the exact commit SHA.

### Initial service creation

Production was provisioned via the Render API (`POST https://api.render.com/v1/services`)
with runtime `docker`, `dockerfilePath: ./server/Dockerfile`, `dockerContext: ./server`,
and auto-deploy off. If a service is instead created from the Blueprint:
1. Render Dashboard → **New** → **Blueprint** → select repository and apply `render.yaml`.
2. Fill the values prompted by `sync: false` (the secrets listed in §5).

### Environment variable diff

Before deploying, diff the service environment variables against `render.yaml`:

1. Fetch current environment variables from Render:
   ```sh
   curl -fsS -H "Authorization: Bearer <RENDER_API_KEY>" \
     https://api.render.com/v1/services/<service-id>/env-vars
   ```
   (or inspect Render Dashboard → `nexachat-api` → **Environment**).
2. Compare values against the `envVars` block in `render.yaml`.
3. Set or update any added or modified variable with one call per changed key
   (or update that key in the Render Dashboard):
   ```sh
   curl -fsS -X PUT -H "Authorization: Bearer <RENDER_API_KEY>" -H "Content-Type: application/json" \
     -d '{"value": "<VALUE>"}' https://api.render.com/v1/services/<service-id>/env-vars/<KEY>
   ```
   The bulk `PUT .../env-vars` replaces every variable, so never use it for a single change.
   For example, v1.1.1 required setting `JAVA_OPTS` (with
   `-XX:SharedArchiveFile=/app/application.jsa`) and
   `NEXACHAT_MULTIPART_TEMP_DIR=/app/data/multipart`, each with its own call.

### Manual deploy of an exact SHA

Deployments target an exact commit SHA that has passed all CI checks:

```sh
curl -fsS -X POST \
  -H "Authorization: Bearer <RENDER_API_KEY>" \
  -H "Content-Type: application/json" \
  -d '{"commitId": "<commit-sha>"}' \
  https://api.render.com/v1/services/<service-id>/deploys
```

Or in the Render Dashboard: select `nexachat-api` → **Manual Deploy** →
**Deploy a specific commit** → enter `<commit-sha>`.

Wait for the deployment status to become `live`.

### Post-deploy verification

Verify the live endpoints from a machine:

```sh
curl -fsS https://api.nexachat.io.vn/actuator/health/liveness
curl -fsS https://api.nexachat.io.vn/actuator/health
curl -fsS https://api.nexachat.io.vn/actuator/health/readiness
```

`readiness` covers `db`, `flyway`, `diskSpace` and `refreshReplay`
(`application.yml:54-58`). If `refreshReplay` is DOWN, the generated key was
removed; strict refresh rotation still works, but replay recovery is off.

Run the smoke test:
```sh
bash tools/ops/prod-smoke.sh https://api.nexachat.io.vn
bash tools/ops/prod-smoke.sh https://api.nexachat.io.vn --write
```

### Rollback

To roll back:
1. Redeploy the previous deploy's commit SHA via Render API or Dashboard.
2. Restore the previous environment variable values if any were changed, one
   call per key as in the env-var diff above. The bulk `PUT .../env-vars`
   replaces every variable, so never use it for a single change.
3. Database migrations are forward-only (`validate-on-migrate: true`). If a
   migration has already run against Neon Postgres, keep the application
   backward-compatible or deploy a forward fix commit.

## 4. Add the custom domain

1. Render service → **Settings** → **Custom Domains** → add
   `api.nexachat.io.vn`.
2. At the DNS provider add `CNAME api → <service>.onrender.com` (the exact
   target is shown in the dashboard). Remove any `AAAA` record for `api`.
3. Click **Verify**. Render issues the TLS certificate automatically.
4. Leave the apex `nexachat.io.vn` A record on Vercel untouched.

## 5. Environment variables

`JWT_SECRET` and the database coordinates have no default and fail startup when
missing (`ProductionDataSourceConfiguration.java:16-22`,
`ProductionJwtSecretGuard.java:24-40`); `STORAGE_ROOT` is required only when the
storage provider is `local` (S1). The reference `render.yaml` declares (applied
manually to the service):

| Variable | Source of truth | Notes |
|---|---|---|
| `PORT` | `application.yml:2` | `8080`; Render's default is `10000` |
| `SPRING_PROFILES_ACTIVE` | `logback-spring.xml:10` | `prod,render,json-logs` |
| `JAVA_OPTS` | `render.yaml` (overrides `server/Dockerfile:80`) | 512 MB JVM fit; loads the AppCDS archive built into the image |
| `DATABASE_URL` | `application-prod.yml:55` | direct Neon JDBC URL, `sslmode=require` |
| `DATABASE_USER` | `application-prod.yml:56` | |
| `DATABASE_PASSWORD` | `application-prod.yml:57` | secret |
| `JWT_SECRET` | `application-prod.yml:79` | generated; ≥ 32 bytes (`JwtService.java:37-49`) |
| `NEXACHAT_REFRESH_REPLAY_KEY` | `application.yml:101` | generated; base64 of exactly 32 bytes |
| `NEXACHAT_CORS_ALLOWED_ORIGINS` | `application.yml:117` | `https://nexachat.io.vn` for web account deletion |
| `NEXACHAT_TRUSTED_PROXY_CIDRS` | `application.yml:126` | `10.0.0.0/8`; Render private-network peer range, see §8 |
| `NEXACHAT_CLIENT_IP_HEADER` | `application.yml:130` | `CF-Connecting-IP`; Cloudflare overwrites it, see §8 |
| `STORAGE_ROOT` | `application-prod.yml:84` | `/app/data/uploads`; also the `diskSpace` health path |
| `NEXACHAT_STORAGE_PROVIDER` | `application.yml:146` | `s3` |
| `NEXACHAT_STORAGE_S3_ENDPOINT` | `application.yml:149` | Supabase S3 endpoint `https://<project-ref>.storage.supabase.co/storage/v1/s3` (or R2 endpoint) |
| `NEXACHAT_STORAGE_S3_REGION` | `application.yml:150` | `ap-southeast-1` for Supabase (`auto` for R2) |
| `NEXACHAT_STORAGE_S3_BUCKET` | `application.yml:151` | `nexachat-uploads` |
| `NEXACHAT_STORAGE_S3_ACCESS_KEY_ID` | `application.yml:152` | secret |
| `NEXACHAT_STORAGE_S3_SECRET_ACCESS_KEY` | `application.yml:153` | secret |
| `NEXACHAT_MAIL_PROVIDER` | `application.yml:89` | `resend`; SMTP ports are blocked on Render Free |
| `RESEND_API_KEY` | `application.yml:98` | secret |
| `RESEND_FROM` | `application.yml:90` | sender on a domain verified in Resend |
| `FCM_ENABLED` | `application.yml:294` | `false` until credentials exist |
| `FCM_PROJECT_ID` | `application.yml:295` | |
| `FCM_SERVICE_ACCOUNT_JSON` | `application.yml:297` | full JSON inline, no file needed (`GoogleServiceAccountCredentialsFactory.java:58-101`) |
| `FIREBASE_REALTIME_ENABLED` | `application.yml:307` | `false` |
| `FIREBASE_DATABASE_URL` | `application.yml:309` | only when the bridge is enabled |
| `NEXACHAT_GOOGLE_SIGNIN_ENABLED` | `application.yml:107` | `false`; all `/api/v1/auth/google/*` answer 404 while off |
| `NEXACHAT_GOOGLE_SIGNUP_ENABLED` | `nexachat.google.signin.signup-enabled` | `false`; Google **account creation** from an unknown Google `sub`; needs sign-in enabled |
| `NEXACHAT_GOOGLE_WEB_CLIENT_ID` | `application.yml:108` | secret when sign-in is enabled; expected ID token `aud` |
| `NEXACHAT_GOOGLE_ANDROID_CLIENT_IDS` | `application.yml:110` | secret when sign-in is enabled; comma-separated Android OAuth IDs trusted as `azp` |
| `NEXACHAT_GOOGLE_JWKS_URI` | `application.yml:111` | public Google JWKS URL; override only in tests |
| `NEXACHAT_GOOGLE_NONCE_TTL_SECONDS` | `application.yml:112` | `300` |

Line numbers reflect the current server codebase on main.

OAuth client IDs are **not** stored in git. After the Blueprint creates the
service, set production values by hand: Render Dashboard → **Environment**, or
the Render REST API with **one `PUT` per key** (same pattern as other secrets in
[docs/operations/production-runbook.md](production-runbook.md)). Keys declared with
`sync: false` in [render.yaml](../../render.yaml) (`NEXACHAT_GOOGLE_WEB_CLIENT_ID`,
`NEXACHAT_GOOGLE_ANDROID_CLIENT_IDS`) must be filled before enabling sign-in.
Combine debug and release Android OAuth client IDs into a single
`NEXACHAT_GOOGLE_ANDROID_CLIENT_IDS` value (comma-separated); the server does
not read separate debug/release env var names. Step-by-step console setup:
[docs/operations/google-signin-setup.md](google-signin-setup.md).

To turn push on, set `FCM_PROJECT_ID` and `FCM_SERVICE_ACCOUNT_JSON`, then
change `FCM_ENABLED` to `true` and redeploy. Enabling FCM with empty
credentials fails startup (`FcmTokenProvider.java:54-63`).

Render Free cannot reach SMTP ports 25/465/587, so Gmail SMTP cannot be used
here. Resend uses HTTPS and is the configured provider.

## 6. Point the site and the app at the API

- **Site:** `site/delete-account.html:19` already sets
  `nexachat-api-base` to `https://api.nexachat.io.vn`, and the CSP in
  `site/vercel.json:41` allows exactly that origin in `connect-src`.
  `python site/check.py` keeps the two in sync. If the API origin changes,
  change both files and the CORS value together. Deploy the API first: with the
  meta set, the page enables the form and shows an error if the API is not
  reachable yet.
- **Android:** build with `-PapiBaseUrl=https://api.nexachat.io.vn`
  (`android/app/build.gradle:76-77,182-183`). Release builds require an HTTPS
  URL.

## 7. Cold starts and free-tier limits

- Render spins a Free service down after **15 minutes without traffic** unless
  an uptime monitor keeps it awake. Spring Boot adds its own boot time on top of
  container provisioning: in local runs capped at 512 MB / 0.1 CPU, readiness
  took **168 s without** the AppCDS archive and **130 s with** it on the same
  host (Spring reported started in 157 s and 120 s); a busier host measured the
  same pair at **330 s** and **216 s**. The spread is host load, so compare runs
  made back to back. Readiness reported `db`, `flyway`, `diskSpace` and
  `refreshReplay` UP. The first live deploy (Singapore, Free) measured **123 s**
  from container start to a healthy check; Spring Boot itself reported started
  in 106.6 s, including the first Flyway run on Neon. The Android client waits up to 150 s for health before an
  auth request. Render allows 15 minutes for a new deploy to pass its health
  check. `tools/perf/jvm-footprint.sh` reproduces the local measurement (it
  builds the image, caps the container at 0.1 CPU / 512 MB, starts a throw-away
  Postgres, records time to liveness/readiness, RSS and the native-memory
  summary, then uploads eight 20 MB files).
- **Supported keep-alive setup:** An uptime monitor pings
  `https://api.nexachat.io.vn/actuator/health/liveness` every 5 minutes. It keeps
  Render awake. Render's 750 free hours cover one always-on service
  (~720–744 h/month). The liveness check (`server/src/main/resources/application.yml:55-56`)
  never touches the database (database connectivity is checked under
  `/actuator/health/readiness`, `application.yml:57-58`), so Neon still suspends.
- **Neon free budget and sleep profile:** Neon Free provides 100 CU-hours per project
  per month (~400 h at 0.25 CU), so the Neon compute must suspend after 5 minutes
  without queries; an always-on database would exhaust the monthly allowance in about
  16 days. The `render` profile (`server/src/main/resources/application-render.yml`)
  configures:
  - Hikari `minimum-idle: 0` (`application-render.yml:29`) and `idle-timeout: 60000`
    (`application-render.yml:30`), so idle database connections are closed after 1
    minute rather than held open;
  - 30-minute background job intervals for RAG backfill (`nexachat.rag.index.backfill-interval-ms: 1800000`,
    `application-render.yml:45`) and refresh-replay token cleanup (`nexachat.auth.refresh-replay.cleanup-interval-ms: 1800000`,
    `application-render.yml:48`), ensuring scheduled tasks do not wake Neon every minute.
- **Reminder scheduler and Neon idle:** `ReminderScheduler` arms an in-memory
  timer for the next due moment (`server/src/main/java/com/nexachat/reminder/ReminderScheduler.java:166-205`)
  and re-arms after commit (`onScheduleChanged`, lines 144–149), with a 30-minute idle fallback
  configured on Render (`nexachat.reminders.max-idle-ms: 1800000` at `server/src/main/resources/application-render.yml:40-42`).
  Reminders wake the database only when one is due or on the 30-minute safety idle tick,
  allowing Neon to suspend its compute during periods without user activity.
- The Android client times out at 10 s connect / 15 s read
  (`android/app/src/main/java/com/nexachat/core/network/NetworkModule.java:45-46`).
  If a request arrives during a cold start or redeploy, the client displays a
  localized waiting indicator and retries with bounded exponential backoff (the
  retry window is being re-tuned) rather than failing immediately.
- RSS was **348–358 MiB** after startup with the AppCDS archive (**397–400 MiB**
  without it), and the anonymous-memory peak during eight concurrent 20 MB
  uploads (`nexachat.uploads.max-concurrent: 2`, so most attempts get `429`) was
  **416–444 MiB** with the archive (**479 MiB** without it). 512 MB holds the
  app, but leaves little room for attachments buffered in memory
  (`AttachmentService.java:122` reads a 25 MB upload into a byte array). Compare
  pairs measured back to back: the host's load moves both numbers.
- The filesystem is ephemeral: without persistent object storage (Supabase Storage
  or Cloudflare R2), uploads disappear on every restart and sleep.
- 0.1 CPU is shared by HTTP, WebSocket, Flyway and scheduled jobs; keep
  retention jobs and the avatar sweep off until the plan is raised. With the
  supported keep-alive setup keeping Render awake 24/7, scheduled work runs
  without container sleep interruption; if Render is ever allowed to sleep,
  scheduled work only runs while the service is awake: a reminder that falls due
  during a sleep is delivered on the next wake-up, not at its exact time.
- One instance only. The scheduled jobs already use Postgres advisory locks, so
  scaling later does not double-run them.

## 8. Client IP behind Render's proxy

Public traffic reaches a Render web service through Cloudflare and Render's load
balancer, which terminates TLS and forwards plain HTTP to the container, so the
socket peer is a proxy, not the user
([Render: Web Services](https://render.com/docs/web-services),
[Render: DDoS protection](https://render.com/articles/how-render-handles-ddos-attacks)).
Render publishes no proxy CIDR and no Render-specific client-address header.

- **`X-Forwarded-For` is append-only.** Cloudflare appends to a caller-supplied
  `X-Forwarded-For`, so its left-most entry is attacker-controlled
  ([Render: PocketBase on Render, "Making PocketBase see the real client IP"](https://render.com/articles/host-pocketbase-on-render#making-pocketbase-see-the-real-client-ip)).
- **`CF-Connecting-IP` is authoritative.** Cloudflare writes it on every request
  to a Render web service and overwrites whatever the caller sent
  ([Render: PocketBase on Render](https://render.com/articles/host-pocketbase-on-render#making-pocketbase-see-the-real-client-ip);
  [Cloudflare HTTP headers](https://developers.cloudflare.com/fundamentals/reference/http-request-headers/)).

The service configuration therefore sets `NEXACHAT_TRUSTED_PROXY_CIDRS=10.0.0.0/8`
and `NEXACHAT_CLIENT_IP_HEADER=CF-Connecting-IP` (as specified in `render.yaml`). Render does not publish the peer
range; its load balancer connects over the
[private network](https://render.com/docs/private-network) and the observed peer
is in `10.0.0.0/8`
([measured Render request](https://rohitpaulk.com/articles/render-rails-remote-ip)).
If Render moves the peer outside that range, rate-limit buckets fall back to the
shared proxy peer (safe, but coarse); re-run the probe below after any platform
change.

`ClientIpResolver` reads the configured header only when the socket peer is in
the trusted range. With `X-Forwarded-For` it walks the chain right-to-left and
returns the first address outside the trusted ranges; a malformed chain falls
back to the proxy peer. A caller-supplied left-most entry can never choose a
bucket. A single-value header with more than one value also falls back to the
peer. Spring's own `getRemoteAddr()` still carries the left-most forwarded
entry, so do not key a new security control on it; use `ClientIpResolver`.

### Post-deploy probe

Run this from two different networks (for example an office connection and a
phone hotspot). Never run it from CI or against a production system you do not
own.

1. On network A, send 11 bad logins with a different fake `X-Forwarded-For`
   each time. The response must become `429` by the 11th request (login budget
   is 10 per 5 minutes; run the whole probe inside that window):

   ```sh
   for i in $(seq 1 11); do
     curl -s -o /dev/null -w "%{http_code}\n" \
       -H 'Content-Type: application/json' \
       -H "X-Forwarded-For: 198.51.100.$i" \
       -d '{"phone":"+84900000000","password":"wrong"}' \
       https://api.nexachat.io.vn/api/v1/auth/login
   done
   ```

2. On network A, send one more bad login with a different fake
   `CF-Connecting-IP` and `X-Forwarded-For`. It must stay `429`; any other
   status means the fake header chose a fresh bucket.

3. On network B, send one bad login: it must not be `429` (fresh bucket;
   expected `401`), then ten more must reach `429`. A `429` on the first
   network-B request means the peer did not match
   `NEXACHAT_TRUSTED_PROXY_CIDRS` and every client still shares the proxy
   bucket.

Read Render's HTTP request log for the two `429` sequences: the status codes and
timestamps must line up. The application deliberately logs no client IPs, so the
bucket behaviour above is the confirmation.
