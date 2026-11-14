# Production Runbook

Operational runbook for the NexaChat `v1.1.0` production deployment on free-tier infrastructure.

## 1. Production Topology

| Component | Provider / Tier | Region | Coordinates / Endpoints | Notes |
|---|---|---|---|---|
| **Web Service** | Render Free Tier (512 MB RAM, 0.1 vCPU) | Singapore (`singapore`) | `https://api.nexachat.io.vn` | Linux/amd64 container built from `server/Dockerfile`. TLS terminated by Render reverse proxy. |
| **Database** | Neon Serverless Postgres (PostgreSQL 18) | Singapore (`ap-southeast-1`) | `ep-....ap-southeast-1.neon.tech:5432` | SSL mode `require`. Must use direct endpoint (not the `-pooler` endpoint; PgBouncer transaction mode conflicts with session advisory locks used by Flyway and ScheduledAdvisoryLock; see `render.yaml:57-59` and `docs/operations/deploy-render.md:30-34`). |
| **Object Storage** | Supabase Storage (S3-compatible API) | Singapore (`ap-southeast-1`) | Supabase S3 endpoint / bucket | Stores user avatars (`avatars/`) and message attachments (`attachments/`). Max file size 25 MB. |
| **Push Notifications** | Firebase Cloud Messaging (FCM v1) | Global / Google Cloud | `fcm.googleapis.com` | OAuth2 service-account authentication via `FCM_SERVICE_ACCOUNT_JSON`. |
| **Realtime Bridge (Opt-in)** | Firebase Realtime Database (RTDB) | Singapore / Asia-SE1 | `https://<project>-default-rtdb.asia-southeast1.firebasedatabase.app` | Ephemeral presence, typing, and call-status projection. Security rules in `tools/firebase-rules/database.rules.json`. |
| **Static Web Site** | Vercel (Hobby Free tier) | Global Edge | `https://nexachat.io.vn` | Serves `site/` static landing page, `/.well-known/assetlinks.json`, `/join/*` fallback, `/delete-account.html`. |
| **Client Distribution** | GitHub Releases | Global CDN | `github.com/.../releases/tag/v1.1.0` | Signed release APK (`app-release.apk`) distributed directly to Android users. |

---

## 2. Deploy and Rollback Procedures

### Deployment Process

1. **Trigger**: Push to production deployment branch or trigger manual deploy in Render Dashboard.
2. **Build**: Render builds the container using `server/Dockerfile` (multi-stage Maven build with Eclipse Temurin 21 JRE noble runtime).
3. **Startup & Migrations**:
   - JVM flags configure cgroup memory allocation: the `render.yaml` `JAVA_OPTS` block (`render.yaml:44-55`) is the source of truth for production (`-XX:MaxRAMPercentage=60.0`, `-XX:+UseSerialGC`, `-Xss512k`, `-XX:TieredStopAtLevel=1`, `-XX:+ExitOnOutOfMemoryError`, and AppCDS archive `-XX:SharedArchiveFile=/app/application.jsa`), overriding the container image default at `server/Dockerfile:80`. Readiness is locally measured at 120–185 s with the archive and 167–328 s without, at 0.1 CPU / 512 MB (readiness on Render not yet measured).
   - `ProductionDataSourceConfiguration` validates that required credentials (`spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`, and `STORAGE_ROOT` when provider is `local`) are present and non-empty (`server/src/main/java/com/nexachat/config/ProductionDataSourceConfiguration.java:18-30`).
   - `ProductionJwtSecretGuard` validates that `JWT_SECRET` is not the development default and has at least 8 distinct characters (`server/src/main/java/com/nexachat/config/ProductionJwtSecretGuard.java:48`).
   - Flyway executes forward migrations (`V1`–`V20`, `V22`, and `V24`–`V33`; `V21` and `V23` are reserved and have no files) with validation enabled (`validate-on-migrate: true`, `server/src/main/resources/application-prod.yml:47`).
4. **Liveness Probe**: Render checks container liveness via `curl -fsS http://localhost:8080/actuator/health/liveness` (`server/Dockerfile:82-83`).
5. **Post-Deploy Smoke Check**: Run `tools/ops/prod-smoke.sh` immediately after deployment completes.

### Rollback Strategy

1. **Render Redeployment**:
   - Open the Render Dashboard -> Select `nexachat-api` Web Service -> Navigate to **Deploys**.
   - Locate the previous successful deploy commit and click **Rollback to this deploy**.
2. **Forward-Only Flyway Constraint**:
   - **Critical Rule**: Database migrations are strictly forward-only.
   - If the rolled-back release applied a new Flyway migration (e.g. `V31`), rolling back the application container to an older commit will cause Spring Boot to fail startup because `validate-on-migrate: true` detects applied migrations unknown to the older codebase.
   - **Rollback via Forward Fix**: When a new migration has already run against Neon Postgres, rollback of application logic must be executed as a **forward fix**:
     1. Create a new commit on `main` that keeps database schema compatibility (or applies a compensating migration `V{N+1}`).
     2. Deploy this new commit to Render.
     3. Never manually delete rows from `flyway_schema_history` in production without explicit schema reconciliation.
3. **Backup Topology and Paired Recovery Scope**:
   - **Demo Script Limitation**: `demo/backup.sh` is strictly scoped to local Docker Compose (`docker compose ps -q db`). It cannot connect to hosted Neon Postgres and cannot back up Supabase S3 object storage. Running it on production either fails or dumps an unintended local container.
   - **Paired Database and Object Storage Recovery**: Production chat history and media span two systems: Neon Postgres (relational metadata, messages, users) and Supabase Storage S3 (attachment and avatar binaries). Backups and restores must be coordinated as a consistent pair. Restoring database state without corresponding object storage recovery causes dangling attachment references or unrecoverable media loss.

---

## 3. Cold-Start Behaviour

### Free-Tier Sleep Cycle

- **Render Web Service**: Spins down after 15 minutes of zero HTTP traffic unless kept awake. In the supported production configuration, an external uptime monitor pings `https://api.nexachat.io.vn/actuator/health/liveness` every 5 minutes (`docs/operations/deploy-render.md`), keeping Render awake 24/7 (covered by Render's 750 free instance hours per month).
- **Neon Serverless Postgres**: Automatically suspends compute after 5 minutes of idle connection inactivity. Because the liveness endpoint only checks `ping` (`server/src/main/resources/application.yml:55-56`) and never queries the database, Neon can suspend even while Render stays awake.
- **Wake-up Duration**:
  - Total cold start: In local benchmarking capped at 512 MB / 0.1 CPU, Spring Boot reached readiness in 120–185 s with the AppCDS archive and 167–328 s without (readiness on Render not yet measured; the first live Render deploy without AppCDS measured 123 s from container start to a healthy check, with Spring Boot starting in 106.6 s, including the first Flyway run on Neon). The Android client waits up to 150 s for health before an auth request.
  - Neon Postgres compute resume: ~1–3 seconds added to the first database query when compute has suspended.

### Upstream and Client Behavior

- **HTTP Status during Wake-up**: Render's ingress proxy may return HTTP `502 Bad Gateway` or `504 Gateway Timeout` to incoming requests while the container is provisioning.
- **Android Client Handling**:
  - The Android networking client intercepts timeouts and `502`/`503`/`504` errors when contacting `api.nexachat.io.vn`.
  - Instead of displaying a hard error, the app displays a localized waiting indicator ("Server is starting, please wait…" / "Máy chủ đang khởi động, vui lòng chờ…").
  - The client executes bounded exponential backoff retries while displaying the waiting state; the retry window is being re-tuned to accommodate cold starts.
- **Connection Pool Safety**:
  - In default production configuration (`server/src/main/resources/application-prod.yml:58-65`), HikariCP is configured with `minimum-idle: 2` (line 61), `maximum-pool-size: 10` (line 60), `connection-timeout: 30000` (line 62), `validation-timeout: 5000` (line 63), and `max-lifetime: 1800000` (line 64).
  - On Render, the `render` profile (`server/src/main/resources/application-render.yml:22-34`) overrides these defaults with:
    - `maximum-pool-size: 5` (`application-render.yml:26`)
    - `minimum-idle: 0` (`application-render.yml:29`)
    - `idle-timeout: 60000` (`application-render.yml:30`)
    - `connection-timeout: 20000` (`application-render.yml:31`)
    - `validation-timeout: 3000` (`application-render.yml:32`)
    - `max-lifetime: 1200000` (`application-render.yml:33`)
    - `leak-detection-threshold: 60000` (`application-render.yml:34`)
  - Setting `minimum-idle: 0` and `idle-timeout: 60000` releases idle database connections after 1 minute, allowing Neon Free compute to auto-suspend after 5 idle minutes while the Render service remains awake via liveness pings. Capping `maximum-pool-size: 5` prevents connection thundering herds against Neon's compute endpoint during cold wake-up.

---

## 4. Where Logs Live

| Log Stream | Location / Access | Format & Content |
|---|---|---|
| **API Application Logs** | Render Dashboard -> Web Service -> **Logs** tab | Single-line JSON emitted by `RedactingJsonLayout`. Includes timestamp, thread, log level, correlation `traceId`, logger class, and message. Sensitive credentials, JWTs, and message bodies are scrubbed by `Redactor.java`. |
| **Ingress / HTTP Access** | Render Dashboard -> Web Service -> **Metrics** & **Events** tabs | HTTP method, path, response code, request latency, bandwidth egress, and container lifecycle events (spin-down, wake, deploy). |
| **Neon Postgres Logs** | Neon Console -> Project -> **Monitoring** & **Logs** | Active connection counts, compute hours consumed, slow query logs (>500 ms), auto-suspend and resume events. |
| **Vercel Site Logs** | Vercel Dashboard -> Project -> **Deployments** -> Function/Edge logs | Static asset requests, cache hit/miss rates, and edge middleware logs. |
| **Android Client Logs** | Physical device or emulator: `adb logcat -s NexaChat:*` | Release builds disable `HttpLoggingInterceptor` and debug logging; tokens and chat messages are never logged (`docs/operations/operations.md:1116`). |

---

## 5. Production Smoke Testing (`tools/ops/prod-smoke.sh`)

Post-deployment validation is performed using the `tools/ops/prod-smoke.sh` script against the production API.

### Execution Commands

```bash
# 1. Offline self-test (validates script logic and flag parsing without network requests)
bash tools/ops/prod-smoke.sh --self-test

# 2. Read-only production smoke test (safe to run at any time; leaves zero state)
bash tools/ops/prod-smoke.sh https://api.nexachat.io.vn

# 3. Full read/write verification (creates throwaway test account, uploads avatar, deletes account)
bash tools/ops/prod-smoke.sh https://api.nexachat.io.vn --write
```

### Checks Performed by the Script

1. **Cold-Start Health Polling**: Polls `GET /actuator/health` with bounded backoff (up to 120 seconds) until status is `{"status":"UP"}`.
2. **TLS and Security Headers**: Validates HTTPS connectivity with certificate verification. If the API sends `Strict-Transport-Security`, its `max-age` must be positive; a missing header is only a warning because the HTTPS edge may add it, and fails the run only with `--require-hsts`.
3. **CORS Preflight**: Sends `OPTIONS` request with `Origin: https://nexachat.io.vn` for public endpoints and checks `Access-Control-Allow-Origin: https://nexachat.io.vn`.
4. **Authentication Gate**: Probes protected route `GET /api/v1/users/me` without a Bearer token and verifies HTTP `401 Unauthorized` with a documented error code.
5. **Public OTP Endpoint**: Submits `POST /api/v1/auth/password-reset/request` once with a reserved fake phone number; verifies the uniform `202 {}` response without account enumeration.
6. **WebSocket / STOMP Handshake**: Initiates HTTP Upgrade on `/ws` to confirm the realtime gateway is reachable.
7. **Write Verification (when `--write` is specified)**:
   - Registers a temporary user with a reserved test phone number.
   - Authenticates to obtain JWT access and refresh tokens.
   - Uploads a micro-avatar PNG to test Supabase S3 storage persistence.
   - Fetches back the uploaded avatar bytes and verifies content integrity.
   - Calls `DELETE /api/v1/users/me` to exercise account deletion and cleanup.
   - Verifies that subsequent access with the revoked token returns `401 Unauthorized`.

---

## 6. Firebase Emergency Kill Switch (`control/adminDisabled`)

For architecture and security rules details, see [docs/architecture/firebase-realtime.md](../architecture/firebase-realtime.md).

### Contract and Purpose

- In Firebase Realtime Database (RTDB), `/control/adminDisabled` is a persistent operator-controlled boolean flag.
- When set to `true`, every client security rule in `tools/firebase-rules/database.rules.json` denies **all reads and writes** across `/presence`, `/typing`, and `/calls`.
- Client access remains blocked even if a server reconciliation job publishes `/control/enabled: true`.

### Emergency Kill Activation

If client abuse, leaked credentials, or an RTDB loop occurs:

1. **Via Firebase Console**:
   - Open Firebase Console -> Realtime Database -> **Data** tab.
   - Under the root node, navigate to `control`.
   - Set `adminDisabled: true`.
   - Set `enabled: false`.
2. **Via REST API (curl with Google OAuth2 Bearer token)**:
   ```bash
   TOKEN=$(gcloud auth print-access-token)
   RTDB_URL="<FIREBASE_DATABASE_URL value, no trailing slash>"

   # 1. Engage kill switch
   curl -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d 'true' "$RTDB_URL/control/adminDisabled.json"

   # 2. Close admission gate
   curl -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d 'false' "$RTDB_URL/control/enabled.json"
   ```
3. **Disable Server Bridge**:
   - In Render Dashboard -> Environment Variables -> set `FIREBASE_REALTIME_ENABLED=false`.
   - Trigger a restart/redeploy of the API service.
   - Android clients automatically fall back to STOMP over WebSocket.

### Recovery and Re-enabling

1. Verify server health, security rules, and database credentials.
2. In RTDB, set `/control/adminDisabled: false` (or delete the key).
3. Allow the server `FirebaseAuthorizationBackfillJob` to verify migration state, clean up legacy entries, and publish `/control/presencePrivacyVersion: 2` together with `/control/enabled: true`.

---

## 7. Secret Rotation Checklist

| Secret | Where It Lives | Impact of Rotation | Step-by-Step Rotation Procedure |
|---|---|---|---|
| **Render API Key** | Operator password manager; CI/CD pipeline secrets (`RENDER_API_KEY`) | CLI/API deploys fail if key is stale; running service is unaffected. | 1. Open Render Dashboard -> Account Settings -> **API Keys**.<br/>2. Click **Create API Key**, label it `nexachat-deploy-<date>`, and copy the value.<br/>3. Update CI/CD secret or local deployment scripts.<br/>4. Test access: `curl -H "Authorization: Bearer $KEY" https://api.render.com/v1/services`.<br/>5. Delete the old API key in Render Dashboard. |
| **Neon DB Password** | Neon Console; Render environment variable `DATABASE_PASSWORD` / `DATABASE_URL` | Application loses DB connection until updated on Render (temporary 500/503). | 1. Open Neon Console -> Dashboard -> **Roles / Databases**.<br/>2. Select database role `nexachat` -> click **Reset password**.<br/>3. Copy the newly generated password and pooled connection string.<br/>4. Open Render Dashboard -> Web Service -> **Environment**.<br/>5. Update `DATABASE_PASSWORD` and `DATABASE_URL`.<br/>6. Click **Save Changes** (triggers automatic zero-downtime redeploy).<br/>7. Confirm application boots and `/actuator/health` reports status `UP`. |
| **Supabase Access Token & S3 Keys** | S3 keys: Render environment variables. Access token: operator `.env` only; the server never uses it. | Uploads and downloads fail if credentials are invalid; existing chat rows unaffected. | 1. Open Supabase Dashboard -> Project Settings -> **Storage** -> **S3 Access Keys**.<br/>2. Click **New access key** (Access Key ID and Secret Access Key).<br/>3. In Render Dashboard -> Environment, update the S3 access keys.<br/>4. Open Supabase Dashboard -> Account -> **Access Tokens** -> generate new personal token and update secret store.<br/>5. Save changes on Render and trigger redeploy.<br/>6. Verify avatar upload via `tools/ops/prod-smoke.sh --write`.<br/>7. Revoke previous S3 credentials and access token in Supabase Dashboard. |
| **Firebase Service Account Key** | Google Cloud Console / Firebase Console; Render environment variable `FCM_SERVICE_ACCOUNT_JSON` | Push notifications and RTDB bridge pause until service restarts with new key. | 1. Open Firebase Console -> Project Settings -> **Service accounts**.<br/>2. Click **Generate new private key** and download the JSON file.<br/>3. Open Render Dashboard -> Environment -> update `FCM_SERVICE_ACCOUNT_JSON` with the raw JSON string.<br/>4. Save changes and redeploy Render service.<br/>5. Verify logs for successful FCM gateway and RTDB bridge initialization.<br/>6. Open Google Cloud Console -> **IAM & Admin** -> **Service Accounts** -> select service account -> **Keys** tab -> delete the superseded key. |
| **Vercel Deployment Token** | Local operator environment (`.env`), CI repository secret (`VERCEL_TOKEN`) | Site deployment CLI commands fail; currently live site remains online. | 1. Open [Vercel Account Tokens](https://vercel.com/account/tokens).<br/>2. Click **Create** to generate a new token (e.g. `nexachat-deploy-<date>`).<br/>3. Update `VERCEL_TOKEN` in CI repository secrets and operator environment (`docs/operations/domain-and-app-links.md:154-170`).<br/>4. Validate authentication: `vercel whoami --token=$NEW_TOKEN`.<br/>5. Delete the revoked token in the Vercel Tokens dashboard. |
| **Google OAuth Client Secret** | Google Cloud Console -> APIs & Services -> Credentials; server environment config | Google sign-in token verification fails if client IDs or credentials drift. | 1. Open Google Cloud Console -> **APIs & Services** -> **Credentials**.<br/>2. Under OAuth 2.0 Client IDs, open the Web Client credential.<br/>3. Click **Reset Secret** to generate a new secret.<br/>4. Update `NEXACHAT_GOOGLE_WEB_CLIENT_ID` / secrets in Render Environment if altered.<br/>5. If Android client IDs changed, update `NEXACHAT_GOOGLE_ANDROID_CLIENT_IDS` and rebuild Android app with `-PgoogleWebClientId=...`.<br/>6. Verify Google token verification via `POST /api/v1/auth/google`.<br/>7. Delete expired OAuth credentials in Google Cloud Console. |
| **Resend API Key** | Resend Dashboard; Render environment variable `RESEND_API_KEY` | Account recovery and deletion OTP emails fail (`503 MAIL_UNAVAILABLE`). | 1. Open Resend Dashboard -> **API Keys** -> click **Create API Key** with sending permissions on verified domain.<br/>2. Open Render Dashboard -> Environment -> update `RESEND_API_KEY`.<br/>3. Save changes and redeploy.<br/>4. Test delivery by triggering a password-reset OTP via `tools/ops/prod-smoke.sh`.<br/>5. Delete the old API key in Resend Dashboard (`docs/operations/operations.md:98-100`). |
| **SMTP App Password** | Google Account / Email provider Security settings; Render environment variable `MAIL_SMTP_PASSWORD` | SMTP mail dispatch fails if provider is `smtp`; fallback to Resend if configured. | 1. Open Google Account -> **Security** -> **2-Step Verification** -> **App passwords**.<br/>2. Generate a new 16-character app password named `NexaChat Production`.<br/>3. Open Render Dashboard -> Environment -> update `MAIL_SMTP_PASSWORD`.<br/>4. Save changes and redeploy Render service.<br/>5. Test email dispatch via OTP request.<br/>6. In Google Account security settings, revoke the superseded app password. |
| **JWT Signing Keys (`JWT_SECRET`)** | Render environment variable `JWT_SECRET`; optional `NEXACHAT_REFRESH_REPLAY_KEY` | Existing access tokens become invalid; active mobile clients refresh automatically via DB-stored refresh tokens. | 1. Generate a new cryptographically random string (at least 32 UTF-8 bytes, >=8 distinct characters, e.g. `openssl rand -base64 32`).<br/>2. Open Render Dashboard -> Environment -> update `JWT_SECRET`.<br/>3. If rotating `NEXACHAT_REFRESH_REPLAY_KEY`, generate 32 random bytes Base64 and update.<br/>4. Save changes and redeploy (`docs/operations/operations.md:1066-1076`).<br/>5. Mobile clients will encounter expired access tokens on next call, invoke `POST /api/v1/auth/refresh`, and obtain newly signed JWTs transparently without forcing user logout. |
| **APK Signing Keystore (`nexachat-upload.jks`)** | Secure offline operator vault; GitHub Actions secret `ANDROID_KEYSTORE_BASE64` | **THE KEYSTORE IS NEVER ROTATED.** Android requires monotonic signatures; rotating the key breaks app updates for all existing installs. | **BACKUP ONLY (NO ROTATION)**:<br/>1. The keystore `nexachat-upload.jks` and passwords (`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD`) must be stored in at least two separate secure, encrypted offline media (e.g. encrypted physical drives, password vault).<br/>2. If upload key is lost and Google Play App Signing is active: request an upload key reset via Google Play Console.<br/>3. If self-distributed via GitHub Releases: key loss is catastrophic and requires users to uninstall and reinstall the APK (`docs/release/android-release.md:10-12`). |

---

## 8. Free-Tier Limits, Monitoring, and Upgrade Paths

### 1. Render Free Web Service

- **Limits**:
  - Memory: 512 MB RAM.
  - CPU: 0.1 vCPU.
  - Sleep Policy: Spins down after 15 minutes of inactivity.
  - Monthly Free Hours: 750 free instance hours per month per account.
  - Egress Bandwidth: 100 GB/month.
- **What to Watch**:
  - JVM Out-Of-Memory (OOM) kills: Look for container exit code 137 or `ExitOnOutOfMemoryError` in Render logs. `JAVA_OPTS` limits heap to 75% (~384 MB), leaving ~128 MB for native memory and thread stacks.
  - Free instance hours exhaustion: If multiple services share the account, services suspend upon reaching 750 hours.
  - Cold-start user complaints: 50–75s delay on first connection.
- **Operator Actions & Mitigation**:
  - Keep JVM memory lean; prevent unbounded heap caching.
  - Keep `management.endpoints.web.exposure.include=health` to avoid heavy actuator memory overhead.
- **Upgrade Path**:
  - Render **Starter** plan ($7/month): Dedicated 512 MB RAM, no sleeping (zero cold start), persistent uptime.

### 2. Neon Serverless Postgres Free Tier

- **Limits**:
  - Engine: PostgreSQL 18.
  - Compute: 100 compute hours per month.
  - Auto-suspend: Compute suspends after 5 minutes of inactivity.
  - Storage: 0.5 GiB (512 MB) shared disk storage.
  - Connections: Max 100 concurrent connections.
- **What to Watch**:
  - Disk storage approaching 512 MB (Neon dashboard metrics).
  - Compute hours approaching 100 hours.
  - HikariCP pool exhaustion: Ensure configured pool sizes (`maximum-pool-size: 5` on Render via `application-render.yml:26`, `10` in default prod via `application-prod.yml:60`) remain intact and `leak-detection-threshold: 60000` catches leaked connections.
- **Operator Actions & Mitigation**:
  - Always use the Neon direct connection string (never the `-pooler` endpoint, which conflicts with session advisory locks used by Flyway and ScheduledAdvisoryLock; see `render.yaml:57-59` and `docs/operations/deploy-render.md:30-34`).
  - Enable bounded data retention cleanup to purge expired tokens, tombstones, and inactive records (`server/src/main/resources/application.yml:153-208`). Note: retention cleanup is **not** a single flag. It requires:
    1. Global enablement: `NEXACHAT_RETENTION_ENABLED=true` (`RetentionJobRunner.java:66`).
    2. Disabling dry-run mode: `NEXACHAT_RETENTION_DRY_RUN=false` (`RetentionJobRunner.java:84,233-246`; dry-run defaults to `true`, which only logs/counts candidate rows without deleting them).
    3. Per-job enablement: `NEXACHAT_RETENTION_<JOB>_ENABLED=true` for each target entity (`REFRESH_TOKENS`, `DEVICE_TOKENS`, `MESSAGE_TOMBSTONES`, `REMINDERS`, `POLLS`, `CALL_RECORDS`, `ATTACHMENTS`, etc., all default to `false`).
- **Upgrade Path**:
  - Neon **Launch** plan ($19/month): 10 GiB storage, 300 compute hours, autoscaling up to 4 Compute Units (CU), configurable auto-suspend timer.

### 3. Supabase Storage Free Tier

- **Limits**:
  - Storage: 1 GB total stored objects.
  - Egress Bandwidth: 5 GB per month.
  - Max Upload Size: 50 MB per object (application enforces 25 MB ceiling via `server.servlet.multipart.max-file-size=25MB` and `StorageService.MAX_FILE_SIZE_BYTES=25MB`).
- **What to Watch**:
  - Accumulated attachment bytes approaching 1 GB.
  - Monthly egress download bandwidth approaching 5 GB.
  - Attachment file leaks: Soft-deleted messages retain stored bytes unless cleaned up.
- **Operator Actions & Mitigation**:
  - Enable avatar sweep (`NEXACHAT_AVATAR_SWEEP_ENABLED=true`, `NEXACHAT_AVATAR_SWEEP_DRY_RUN=false`) to delete unreferenced avatar files older than 24 hours (`server/src/main/resources/application.yml:138-142`).
  - Enable attachment retention cleanup by setting `NEXACHAT_RETENTION_ENABLED=true`, `NEXACHAT_RETENTION_DRY_RUN=false`, and `NEXACHAT_RETENTION_ATTACHMENTS_ENABLED=true` (`server/src/main/resources/application.yml:153-156,189-191`). Both global and per-job flags must be active and dry-run disabled to remove unreferenced and soft-deleted attachment binaries (`RetentionJobRunner.java:66,71,84`).
- **Upgrade Path**:
  - Supabase **Pro** plan ($25/month): 100 GB storage included, 250 GB monthly egress bandwidth, $0.021/GB extra storage.

### 4. Firebase Free Plan (Spark)

- **Limits**:
  - RTDB Connections: 100 simultaneous concurrent connections.
  - RTDB Storage: 1 GB stored data.
  - RTDB Download Bandwidth: 10 GB per month.
  - FCM: Free unlimited push notifications.
- **What to Watch**:
  - Concurrent RTDB connections exceeding 100 (triggers connection throttling).
- **Operator Actions & Mitigation**:
  - Maintain STOMP over WebSocket as the default transport; treat Firebase RTDB as optional ephemeral acceleration.
  - If RTDB connections cap out, engage `/control/adminDisabled` to gracefully route all clients back to STOMP.
- **Upgrade Path**:
  - Firebase **Blaze** plan (pay-as-you-go).

### 5. Vercel Free Plan (Hobby)

- **Limits**:
  - Bandwidth: 100 GB per month.
  - Build execution: 6,000 build minutes per month.
- **What to Watch**:
  - Bandwidth consumption on `nexachat.io.vn`.
- **Operator Actions & Mitigation**:
  - Static site assets are cached globally at Vercel Edge; asset links JSON is ~1 KB.
- **Upgrade Path**:
  - Vercel **Pro** plan ($20/user/month).
