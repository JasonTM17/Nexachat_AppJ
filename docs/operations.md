# Operations

This guide covers the single-node Compose demo and the repository's local CI
checks. Compose reads its settings from the ignored root `.env` file; use
`.env.example` as a placeholder-only template and replace the password and JWT
secret before starting the stack. Do not commit `.env` or signing files.

## Configuration

| Variable | Required in production | Compose default | Placeholder example |
|---|---|---|---|
| `DATABASE_URL` | Yes outside Compose; Compose supplies a default | `jdbc:postgresql://db:5432/nexachat` | `jdbc:postgresql://db:5432/nexachat` |
| `DATABASE_USER` | Yes | None; set in `.env` | `nexachat` |
| `DATABASE_PASSWORD` | Yes | None; set in `.env` | `replace-with-a-local-only-password` |
| `JWT_SECRET` | Yes; at least 32 UTF-8 bytes | None; set in `.env` | `replace-with-at-least-32-random-characters-before-running` |
| `STORAGE_ROOT` | Yes outside Compose; Compose supplies a default | `/app/data/uploads` | `/app/data/uploads` |
| `MANAGEMENT_SERVER_PORT` | No (opt-in loopback isolation; shares API port 8080 by default) | Empty | `8081` |
| `MANAGEMENT_SERVER_ADDRESS` | No (bind address for management server) | Empty | `127.0.0.1` |
| `MANAGEMENT_EXPOSURE` | No (actuator endpoints to expose) | `health` | `health,metrics,prometheus` |
| `MANAGEMENT_DISK_THRESHOLD` | No (disk space warning threshold on storage root) | `1GB` | `1GB` |
| `NEXACHAT_CORS_ALLOWED_ORIGINS` | No (comma-separated browser origins; empty admits no web caller) | Empty | `https://web.nexachat.com` |
| `NEXACHAT_ADMIN_PHONES` | No (comma-separated admin E.164 phones allowed to read `/api/v1/rag/stats`) | Empty | `+84900000001` |
| `NEXACHAT_MAX_JSON_BODY_BYTES` | No (ceiling for JSON request bodies) | `1048576` (1 MiB) | `1048576` |
| `NEXACHAT_VALIDITY_CACHE_TTL_SECONDS` | No (TTL for token revocation cutoff cache) | `60` | `60` |
| `FCM_ENABLED` | No (optional push notifications) | `false` | `false` (set `true` only with valid credentials) |
| `FCM_PROJECT_ID` | Yes when `FCM_ENABLED=true` | Empty | `your-firebase-project-id` |
| `FCM_SERVICE_ACCOUNT_JSON` | Yes when `FCM_ENABLED=true` | Empty | Inline service-account JSON or a path to the key file |
| `FCM_TOKEN_URI` | No (local testing only) | Empty (Google token endpoint) | `https://oauth2.googleapis.com/token` |
| `FCM_API_URL` | No (local testing only) | Empty (Google FCM endpoint) | `https://fcm.googleapis.com/v1/projects/<id>/messages:send` |
| `FCM_CONNECT_TIMEOUT_SECONDS` | No | `5` | `5` |
| `FCM_READ_TIMEOUT_SECONDS` | No | `10` | `10` |
| `FCM_MAX_RETRIES` | No | `2` | `2` |
| `FCM_RETRY_BASE_DELAY_MS` | No | `200` | `200` |
| `NEXACHAT_SUMMARY_LLM_ENABLED` | No (optional external summary LLM) | `false` | `false` |
| `NEXACHAT_SUMMARY_LLM_BASE_URL` | Yes when summary LLM enabled | `https://generativelanguage.googleapis.com/v1beta/openai` | `https://generativelanguage.googleapis.com/v1beta/openai` |
| `NEXACHAT_SUMMARY_LLM_MODEL` | Yes when summary LLM enabled | `gemini-3.1-flash-lite` | `gemini-3.1-flash-lite` |
| `NEXACHAT_SUMMARY_LLM_API_KEY` | Yes when summary LLM enabled | None | `your-gemini-api-key` |
| `NEXACHAT_SUMMARY_LLM_TIMEOUT_MS` | No | `30000` | `30000` |
| `NEXACHAT_SUMMARY_LLM_MAX_INPUT_CHARS` | No | `24000` | `24000` |
| `NEXACHAT_SUMMARY_LLM_MAX_OUTPUT_TOKENS` | No | `4000` | `4000` |
| `NEXACHAT_SUMMARY_LLM_FALLBACK_ENABLED` | No (optional fallback cloud LLM) | `false` | `false` |
| `NEXACHAT_SUMMARY_LLM_FALLBACK_BASE_URL` | Yes when fallback LLM enabled | `https://api.deepseek.com` | `https://api.deepseek.com` |
| `NEXACHAT_SUMMARY_LLM_FALLBACK_MODEL` | Yes when fallback LLM enabled | `deepseek-flash` | `deepseek-flash` |
| `NEXACHAT_SUMMARY_LLM_FALLBACK_API_KEY` | Yes when fallback LLM enabled | None | `your-deepseek-api-key` |
| `NEXACHAT_SUMMARY_LLM_FALLBACK_TIMEOUT_MS` | No | `30000` | `30000` |
| `NEXACHAT_SUMMARY_LOCAL_LLM_ENABLED` | No (optional local Ollama/vLLM tier) | `false` | `false` |
| `NEXACHAT_SUMMARY_LOCAL_LLM_BASE_URL` | No | `http://host.docker.internal:11434/v1` | `http://host.docker.internal:11434/v1` |
| `NEXACHAT_SUMMARY_LOCAL_LLM_MODEL` | No | `qwen2.5:3b` | `qwen2.5:3b` |
| `NEXACHAT_SUMMARY_LOCAL_LLM_API_KEY` | No | `local-ollama` | `local-ollama` |
| `NEXACHAT_SUMMARY_LOCAL_LLM_TIMEOUT_MS` | No | `60000` | `60000` |
| `NEXACHAT_RAG_ENABLED` | No (optional conversation RAG) | `true` | `true` |
| `NEXACHAT_RAG_EMBEDDING_PROVIDER` | No | `lexical` | `lexical` (or `openai-compatible`) |

The Compose app uses `db:5432` on the private Compose network. A server started
directly with Maven on the host uses `localhost:5433` from `application.yml`;
if overriding environment variables for that process, use the host address.
The database stores its initial role/password in the named volume on first
initialization, so changing `DATABASE_PASSWORD` alone does not rotate an
already-initialized database role.

### Push notifications (FCM)

The server binds `nexachat.fcm.*` in `application.yml` and `FcmProperties.java`.
When `FCM_ENABLED=true` it activates `GoogleHttpFcmGateway`, which sends FCM v1
data messages. When `FCM_ENABLED=false` (the default) it activates
`MockFcmGateway`, which records pushes without any network access.

`GoogleHttpFcmGateway` uses Google's OAuth2 service-account flow: it reads the
service-account JSON, signs an RS256 JWT assertion with the JDK, exchanges it at
the account's `token_uri` for an access token scoped to
`https://www.googleapis.com/auth/firebase.messaging`, caches the token in memory
until five minutes before expiry, and sends `Authorization: Bearer <token>` on
every request. A `401` refreshes the token once and retries once. A token that
comes back `UNREGISTERED` or `INVALID_ARGUMENT` is removed through the existing
device-token cleanup path. The private key, access token, token-endpoint
response, and device tokens are never written to logs.

`FCM_SERVICE_ACCOUNT_JSON` accepts either the JSON inline or a filesystem path
to a JSON file. With `FCM_ENABLED=true`, a missing or malformed value, or an
empty `FCM_PROJECT_ID`, stops startup with a clear message instead of failing
on the first send.

`FCM_TOKEN_URI` and `FCM_API_URL` are optional overrides for running the server
against a local fake Google endpoint; leave them empty in production.

Real delivery requires an active Firebase project, a service account with the
Firebase Cloud Messaging API enabled, and an Android client with a matching
`android/app/google-services.json` that registers its device token. None of
these external credentials or files are bundled in the repository, and
end-to-end delivery is not covered by CI.

#### Android client: `google-services.json`

The Android app must be registered in the same Firebase project so it can
obtain an FCM registration token. The app id is `com.nexachat` and debug builds
append `.debug`, so the debug application id is `com.nexachat.debug`; register
both ids if release builds should receive pushes too.

1. Open the Firebase console (<https://console.firebase.google.com/>) and select
   your Firebase project.
2. Project settings (gear icon) → **Your apps** → **Add app** → **Android**.
3. Register `com.nexachat.debug` as the Android package name, then repeat for
   `com.nexachat`.
4. Download `google-services.json` and place it at
   `android/app/google-services.json`. The root `.gitignore` already excludes
   `google-services.json`, so the file stays out of git unless you decide
   otherwise; never commit it.
5. Build and run the app. It requests an FCM token on first launch and registers
   it through `POST /api/v1/users/device-token`; the server then has a token it
   can send to.

Without this file the app cannot obtain a token, so the server-side checks above
pass while no phone ever receives a push.

To verify a setup with one test push:

1. Put the service-account JSON inline in `FCM_SERVICE_ACCOUNT_JSON` (or set it
   to a key file path) and set `FCM_PROJECT_ID`.
2. Start the server. A successful start logs
   `FCM gateway initialised for project <id>`; a missing or malformed key stops
   startup.
3. Register a device token for a user (the access token is a NexaChat JWT from
   login):

   ```sh
   curl -X POST http://localhost:8080/api/v1/users/device-token \
     -H "Authorization: Bearer <access-token>" \
     -H "Content-Type: application/json" \
     -d '{"token":"<fcm-registration-token>","platform":"ANDROID","deviceLabel":"Test phone"}'
   ```

4. From another account, send that user a message. The server dispatches a
   data-only push (`type=NEW_MESSAGE`) that the client uses to sync; message
   content never passes through FCM.

### Android client (FCM)

`android/app/google-services.json` is gitignored and must be supplied locally. The
Google Services Gradle plugin (`com.google.gms.google-services`, pinned in
`android/gradle/libs.versions.toml`) is applied only when that file exists, so a
fresh clone still builds and the no-Firebase code path stays exercised.
`./gradlew :app:checkFirebaseConfig` prints `Firebase configured: yes|no` per variant
plus the resolved client package name; it never prints keys or app ids.

The debug variant uses application id `com.nexachat.debug` and release uses
`com.nexachat`; both clients must exist in the same Firebase project. The plugin
matches the client by application id and generates `google_app_id` for each variant.

Token registration is a silent sync wake-up, not a visible notification:

1. `NexaFirebaseMessagingService.onNewToken` stores the token and enqueues
   `DeviceTokenSyncWorker` when a session exists; login and session restoration do the
   same through `DeviceTokenRegistrationManager.onSessionAvailable`.
2. The worker reads the stored token, or calls `FirebaseMessaging.getToken()` once when
   Firebase is configured, and registers it through `POST /api/v1/users/device-token`.
   Fetch or registration failures return `Result.retry()`; WorkManager backs off
   exponentially (initial 30 s, at most 4 retries).
3. The token is stored encrypted in DataStore and never logged.

Message pushes only wake the sync engine, so no notification channel is created for
them and no message notification is posted. `POST_NOTIFICATIONS` is declared in the
manifest and requested by the call screens, which do post visible call
notifications; it is not needed for the silent push path.

Emulator verification (needs a Google Play system image):

1. `cd android && ./gradlew :app:assembleDebug` and
   `adb -s emulator-5560 install -r app/build/outputs/apk/debug/app-debug.apk`.
2. Log in. The app registers its token by itself; read it back from the server:
   `docker compose exec -T db psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT platform, device_label, created_at FROM user_device_tokens ORDER BY created_at DESC LIMIT 5;"`.
3. Send the app to the background (Home, not force-stop), then from another account
   send that user a message with the FCM-enabled server running. The data-only push
   wakes the app to sync; on next launch the message must be present without a
   manual refresh. Android does not deliver FCM to an app the user force-stopped,
   so a force-stopped device only recovers through the launch sync.

## Security

The server stores account credentials (bcrypt password hashes, E.164 phones),
refresh-token hashes, message and attachment content, and short-lived access
JWTs. The trust boundaries are the network edge (TLS terminated at the reverse
proxy), the REST/STOMP authentication layer, and per-conversation authorization.

### Access-token revocation

Access tokens are HS256 JWTs with a 15-minute lifetime. Logout, logout-all and
password change stamp `users.tokens_valid_after` (added by Flyway `V11`); the
JWT filter rejects any token whose `iat` is before that cutoff, so a stolen
access token stops working immediately instead of living out its lifetime.

- The cutoff is stored at one-second resolution because JWT `iat` has no
  sub-second precision: a token issued in the same second as the logout is
  indistinguishable from one minted just after it, leaving at most one second of
  slack.
- The filter caches "account exists + cutoff" per user id in a bounded LRU
  (`nexachat.security.validity-cache-ttl-seconds`, default 60, capped at the
  access-token lifetime) so the hot path does no database work; a cache miss
  falls back to a projection query.
- Logout, logout-all and password change evict the cached entry, so revocation
  is immediate on a single node. In a multi-node deployment each node keeps its
  own cache, so revocation propagates within one TTL.

Refresh-token rotation is unchanged: logout revokes only the presented refresh
token, and a password change keeps the supplied session token alive.

### Cross-origin and headers

- CORS is an explicit allow-list from `nexachat.security.cors-allowed-origins`
  (env `NEXACHAT_CORS_ALLOWED_ORIGINS`, comma-separated). The default is empty:
  no cross-origin caller is admitted. `*` is rejected at startup. Credentials
  are never enabled, so no origin can ride a logged-in browser session. The
  native Android client sends no `Origin` header and is unaffected.
- The WebSocket handshake (`/ws`) uses the same allow-list.
- Every response carries `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY` and `Referrer-Policy: no-referrer`. Auth responses
  additionally carry `Cache-Control: no-store`.
- Only `/actuator/health` is public. Every other actuator endpoint requires a
  Bearer token and only `health` is exposed.

### Admin-only telemetry

`GET /api/v1/rag/stats` returns aggregate RAG counters (no message text,
questions, vectors or user identifiers) but is operator telemetry, not user
data. It is restricted to the E.164 numbers in
`nexachat.security.admin-phones` (env `NEXACHAT_ADMIN_PHONES`); when the list
is empty the endpoint is denied to everyone. The decision is re-evaluated per
request from the user row, so removing a phone takes effect immediately.

### Rate limits and body bounds

- Credential endpoints are limited per source IP (login 10/5 min, register
  10/5 min, refresh 30/5 min) **and** per normalized phone (login and register
  20/5 min), so rotating IPs cannot brute-force one account.
- JSON request bodies larger than `nexachat.security.max-json-body-bytes`
  (env `NEXACHAT_MAX_JSON_BODY_BYTES`, default 1 MiB) are rejected with
  `413 PAYLOAD_TOO_LARGE` before the dispatcher parses them. Multipart uploads
  keep their own 25 MB ceiling. A chunked/streamed body without a
  `Content-Length` is counted on the request input stream against the same
  limit, so the bound does not depend on a reverse proxy; a proxy
  (`client_max_body_size`) remains useful as defence in depth.
- `/api/v1/files/**` normalizes the storage key and rejects any key containing
  `..` or `\`; the storage layer re-checks that the resolved path stays inside
  the configured root before opening it.

## Start, stop, and health

```sh
docker compose up -d db app
docker compose ps
curl -fsS http://127.0.0.1:8080/actuator/health
```

| Service | Host → container | Use |
|---|---:|---|
| App | `8080 → 8080` | REST API, STOMP WebSocket |
| PostgreSQL | `5433 → 5432` | Local tools and integration tests |

The app also serves STOMP WebSocket at `ws://localhost:8080/ws`. Only the
health actuator endpoint is exposed by default; metrics and Prometheus stay
behind `MANAGEMENT_EXPOSURE` (see [Observability](#observability)). The named
`pgdata` and `app-uploads` volumes persist database rows and uploads across
container replacement.

Stop and resume the services without deleting those volumes:

```sh
docker compose stop app db
docker compose start db app
```

Use `http://10.0.2.2:8080` from the Android emulator. A physical phone needs
the LAN URL printed by `demo/lan-up.sh` or `demo/lan-up.ps1`; the scripts refuse
to start if a non-NexaChat process is listening on host port `8080` or `5433`.

## Logs

Spring Boot and PostgreSQL log to container stdout/stderr; Docker retains them.
Follow one service or inspect both:

```sh
docker compose logs --tail=200 -f app
docker compose logs --tail=200 db
```

Do not paste raw logs into public issues; they may contain account identifiers
or request metadata.

## Observability

The server exposes three observability surfaces. Only health is reachable by
default, on the API port; metrics and Prometheus are opt-in and should be moved
to a loopback-bound management port before exposure:

| Surface | Default | How to enable |
|---|---|---|
| Health (`/actuator/health`) | Exposed | Always |
| Metrics (`/actuator/metrics`) | Hidden | `MANAGEMENT_EXPOSURE=health,metrics` |
| Prometheus (`/actuator/prometheus`) | Hidden | `MANAGEMENT_EXPOSURE=health,metrics,prometheus` |

By default the actuator shares the application API port `8080` (so default container and host health checks against `http://localhost:8080/actuator/health` work without configuring an extra port). Operators can opt in to moving the management server to an isolated loopback port by setting `MANAGEMENT_SERVER_PORT=8081` and `MANAGEMENT_SERVER_ADDRESS=127.0.0.1`:

```yaml
management:
  server:
    port: 8081
    address: 127.0.0.1
```

Do not bind the management port to a public interface without adding
authentication in front of it. When you move the port, point the Compose and
Dockerfile health checks at it too.

### Health

`/actuator/health` is public and returns component names and statuses only
(`show-details: never`). It includes:

- `db` — a `SELECT 1` validation query against the configured DataSource.
- `flyway` — Flyway validation plus the applied version and pending count
  (details hidden).
- `diskSpace` — free space on the upload directory (`nexachat.storage.root`)
  with a configurable threshold (`MANAGEMENT_DISK_THRESHOLD`, default `1GB`).
- `ping` — liveness.

Two groups are exposed:

- `/actuator/health/liveness` — `ping`.
- `/actuator/health/readiness` — `db`, `flyway`, `diskSpace`.

### Metrics

Metrics use Micrometer. Tags are bounded; no user ids, phone numbers, email
addresses, message text or tokens are used as tag values. The names are:

| Name | Type | Tags |
|---|---|---|
| `nexachat.messages.sent` | counter | `type`, `outcome` (`CREATED`/`REPLAY`) |
| `nexachat.outbox.replays` | counter | — |
| `nexachat.idempotency.replays` | counter | — |
| `nexachat.stomp.events` | counter | `event` (`CONNECT`/`SUBSCRIBE`/`UNSUBSCRIBE`/`DISCONNECT`) |
| `nexachat.delta_sync.duration` | timer (histogram buckets) | `outcome` (`SUCCESS`/`ERROR`) |
| `nexachat.delta_sync.response_bytes` | summary | `size_bucket` (`<1KB`, `1KB-10KB`, `10KB-100KB`, `100KB-1MB`, `>1MB`) |
| `nexachat.push.sends` | counter | `result` (`SUCCESS`/`FAILURE`/`UNREGISTERED`/`ERROR`) |
| `nexachat.call.transitions` | counter | `action`, `to`, `reason` |
| `nexachat.rag.requests` | counter | `mode` (`CACHE`/`EXTRACTIVE`/`LLM`/`NOT_FOUND`), `provider` |
| `nexachat.summary.requests` | counter | `provider`, `mode`, `fallback` |
| `nexachat.llm.duration` | timer | `source` (`rag`/`summary`), `provider`, `outcome`, `fallback` |
| `nexachat.ratelimit.rejections` | counter | `reason` (rule prefix) |
| `nexachat.auth.failures` | counter | `reason` (`TOKEN_EXPIRED`/`TOKEN_INVALID`/`UNAUTHORIZED`/`FORBIDDEN`) |
| `nexachat.repository.calls` | timer | `method`, `outcome` |
| `nexachat.rag.stats.*` | counters | aggregate RAG counts (requests, cache hits, LLM calls/failures, embeddings, indexed messages) |

`http.server.requests`, JVM, HikariCP and process metrics come from
Spring Boot Actuator automatically.

### Scraping

Prometheus scrapes the management port (loopback only by default). In Compose,
map the port to the host loopback if you need local scraping:

```yaml
ports:
  - "127.0.0.1:8081:8081"
```

```yaml
scrape_configs:
  - job_name: nexachat
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["127.0.0.1:8081"]
```

### Alerts

Example alerts (also in `tools/observability/prometheus-alerts.yml`):

```promql
# 5xx rate above 1% over 5 minutes
sum(rate(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[5m]))
  / sum(rate(http_server_requests_seconds_count[5m])) > 0.01

# delta-sync p95 above 2 seconds
histogram_quantile(0.95,
  sum(rate(nexachat_delta_sync_duration_seconds_bucket[5m])) by (le)) > 2

# push failure rate above 5%
sum(rate(nexachat_push_sends_total{result!="SUCCESS"}[15m]))
  / sum(rate(nexachat_push_sends_total[15m])) > 0.05

# LLM fallback rate above 20%
sum(rate(nexachat_llm_duration_seconds_count{outcome="FAILURE"}[15m]))
  / sum(rate(nexachat_llm_duration_seconds_count[15m])) > 0.20
```

### Structured JSON logs

Set `SPRING_PROFILES_ACTIVE=prod,json-logs` (or add `json-logs` to the active
profiles) to emit one JSON object per line:

```json
{"timestamp":"2026-11-08T12:00:00Z","level":"INFO","logger":"com.nexachat.auth.AuthService","thread":"http-nio-8080-exec-1","traceId":"6f1c2e0e-...","message":"Registered new account"}
```

The `traceId` comes from `X-Request-Id` (accepted when it matches
`[A-Za-z0-9._-]{1,64}`, otherwise generated) and is echoed on the response.
The JSON layout masks bearer tokens, JWTs, phone numbers, FCM registration
tokens, API keys and `content`/`body`/`text`/`message` fields before writing.
The same redaction is applied to exception text.

### Slow repository calls

Repository calls slower than `nexachat.observability.slow-query-threshold-ms`
(default `500`) are logged at WARN with the method name only:

```
Slow repository call MessageRepository.findRowsAfter took 812 ms
```

Arguments are never logged, because they can carry user data or message text.

## Backup and restore

Create a timestamped SQL backup under the gitignored `demo/backups/` directory:

```sh
bash demo/backup.sh
```

PowerShell users can run `powershell.exe -NoProfile -File demo/backup.ps1`.
Both scripts discover the database name and user from the running DB container,
keep existing backup files, and remove a partial temporary dump on failure.

Restore is destructive to the current database contents. Stop the app first,
choose a backup file, then run the following from Bash/Git Bash at the repo
root; the dump uses `--clean --if-exists` and replaces schema objects/data:

```sh
docker compose stop app
DB_CID="$(docker compose ps -q db)"
BACKUP=demo/backups/nexachat-YYYYMMDD-HHMMSS.sql # replace with the chosen backup file
docker exec -i "$DB_CID" sh -c 'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < "$BACKUP"
docker compose start app
```

Keep a second backup before restoring. The restore command was not run during
this audit because it changes database data.

## Troubleshooting

If a port is in use, inspect the services first:

```sh
docker compose ps
```

`demo/lan-up.sh` and `demo/lan-up.ps1` stop with an error when host port 8080
or 5433 belongs to a process outside this Compose project. Stop that process
or choose another deployment host before retrying; do not remove the database
volume to resolve a port conflict.

If the app is unhealthy while PostgreSQL is starting, inspect readiness and
the database log before restarting the app:

```sh
docker compose ps db
docker compose logs --tail=100 db
docker compose logs --tail=100 app
```

The Compose dependency waits for the database health check before starting the
app. From the Android emulator, use `http://10.0.2.2:8080`; `localhost` inside
the emulator points back to the emulator itself. A physical phone must use the
LAN address printed by the demo script and be on the same network.

## Upgrade

Back up before replacing the app image. Flyway migrations (currently V1 through V11,
`V1__init.sql` to `V11__user_access_token_cutoff.sql`) apply on app startup
and are forward-only: add a new versioned SQL migration; never edit one already
applied to a database. Migrations V8–V11 introduce:
- `V8__attachment_blurhash_search_trigram.sql`: `attachments.blur_hash` column and composite GIN trigram index `idx_msg_conv_content_trgm` on `(conversation_id, f_unaccent(content))`.
- `V9__conversation_rag.sql`: `message_embeddings` table with bytea vectors, `rag_ask_cache`, and `rag_ask_request_limits`.
- `V10__reaction_events_idempotency_device_tokens.sql`: `reaction_events` audit table for delta sync, `request_idempotency` replay cache, and `UNIQUE(user_id, token)` device token constraint.
- `V11__user_access_token_cutoff.sql`: `users.tokens_valid_after`, the access-token revocation cutoff.

```sh
bash demo/backup.sh
docker compose build app
docker compose up -d app
```

If a migration fails, keep the app stopped while investigating. Recovery to a
pre-upgrade database state requires restoring the pre-upgrade backup; starting
an older app against a newer schema is not a rollback procedure.

## JWT secret rotation

`JWT_SECRET` signs access JWTs. Replacing it and restarting the app invalidates
all access JWTs signed with the old value; clients must refresh or sign in
again. Refresh tokens are separate opaque values stored in the database as
SHA-256 hashes, so rotating the signing secret does not revoke those refresh
tokens. All app instances in a deployment must use the same current secret.

## Android release

The Android release build produces hardened, minified, and resource-shrunk artifacts (`app-release.apk` / `app-release.aab`) with R8 full mode enabled.

### Signing configuration hygiene

Signing credentials must never be committed to the repository. The build configuration in `android/app/build.gradle` resolves release signing inputs from environment variables, Gradle properties, or an untracked `release/keystore/signing.properties` file:

- **Environment variables**: `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`
- **Gradle project properties**: `releaseStoreFile`, `releaseStorePassword`, `releaseKeyAlias`, `releaseKeyPassword`
- **Local properties file**: `release/keystore/signing.properties` (gitignored) with keys `storeFile`, `keystore.storePassword`, `keyAlias`, `keyPassword`

If release packaging is requested (`assembleRelease`, `packageRelease`, `bundleRelease`) without complete signing credentials and without the unsigned flag, the build fails immediately with a descriptive error.

For CI pipelines, local testing, or verification without signing keys, pass `-PskipSigning` (or set `SKIP_SIGNING=true`) to build an unsigned release artifact:

```sh
cd android
./gradlew :app:assembleRelease -PskipSigning --no-parallel --max-workers=2
```

The resulting unsigned APK is located at `android/app/build/outputs/apk/release/app-release-unsigned.apk`.

### Hardening and security boundaries

1. **R8 full mode and resource shrinking**: Enabled via `android.enableR8.fullMode=true` in `gradle.properties`, and `minifyEnabled true`, `shrinkResources true` in `build.gradle`. Dead code, unused resources, and unneeded metadata are stripped.
2. **Reflective ProGuard keep rules**: Rules in `android/app/proguard-rules.pro` retain names and signatures for reflective Moshi DTOs (`com.nexachat.core.network.dto.**`, `com.nexachat.feature.ask.**`, `com.nexachat.summary.**`), Retrofit API interfaces, Room entities/DAOs, Hilt entry points, WorkManager workers (`OutboxSyncWorker`, `DeviceTokenSyncWorker`), Media3/ExoPlayer components, Glide modules, WebRTC native JNI methods, and Firebase Messaging.
3. **Network security**: Cleartext HTTP/WS is disabled globally in production (`android/app/src/main/res/xml/network_security_config.xml` has `cleartextTrafficPermitted="false"` with zero exemptions). Cleartext access to `10.0.2.2` (emulator) and LAN dev IPs is strictly isolated to debug builds (`android/app/src/debug/res/xml/network_security_config.xml`).
4. **App backup disabled (`android:allowBackup="false"`)**: Backups are disabled to prevent extraction of sensitive local data via `adb backup` or cloud sync. Allowing backup would expose:
   - Decrypted SQLite database containing cached messages, contact lists, conversation watermarks, and drafts.
   - Session tokens and user authentication state stored in DataStore preferences.
   - Downloaded attachment files and cached media in the application cache directory.
5. **No sensitive logging**: `HttpLoggingInterceptor` is stripped in release builds (`BuildConfig.DEBUG` guarded), and no tokens, credentials, or payload bodies are logged.

## Run CI checks locally

The GitHub workflow runs Maven verification with a job-local PostgreSQL 16
service, Android unit tests/lint/debug assembly, Markdown link checking, shell
syntax checks, and a committed-secret scan. Equivalent local commands are:

```sh
(cd server && mvn -B verify)
(cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon --no-parallel --max-workers=1)
python tools/check-markdown-links.py
bash -n demo/*.sh tools/*.sh
```

For local `mvn verify`, set `IT_DB_URL`, `IT_DB_USER`, and `IT_DB_PASSWORD` to a
disposable PostgreSQL test database (the Compose DB maps to host port `5433`).
The Android command above limits workers to avoid competing with other local
Gradle builds. Run `shellcheck demo/*.sh tools/*.sh` when ShellCheck is
installed.

## Audit command status

These results describe this release-audit run. `NOT_RUN` commands are operator
examples whose effects were intentionally not triggered during the audit.

| Command | Result |
|---|---|
| `docker compose config --quiet` | PASS |
| `python` YAML parse of `.github/workflows/ci.yml` | PASS |
| `.env.example` placeholder-only check | PASS — checked without displaying values |
| `docker compose up -d db app` | NOT_RUN — would start/reconcile shared services |
| `docker compose ps` | PASS — existing `nexachat-app-1` and `nexachat-db-1` were healthy |
| `curl -fsS http://localhost:8080/actuator/health` | PASS — `{"status":"UP"}` |
| `docker compose stop app db` / `docker compose start db app` | NOT_RUN — would change shared service state |
| `docker compose logs --tail=200 -f app` / `docker compose logs --tail=200 db` | NOT_RUN — live log inspection not required |
| `bash demo/backup.sh` / `powershell.exe -NoProfile -File demo/backup.ps1` | NOT_RUN — would create a local DB backup |
| Restore command above | NOT_RUN — writes/replaces database data |
| `docker compose build app` / `docker compose up -d app` | NOT_RUN — would replace/restart the shared app |
| `docker build --tag nexachat-app:ops-check ./server` | PASS — fresh source snapshot; image size 493 MB; Maven test execution skipped in image build |
| `docker run --rm --entrypoint id nexachat-app:ops-check` | PASS — UID/GID `10001`, non-root `nexachat` |
| `docker image inspect nexachat-app:ops-check` | PASS — layered `JarLauncher` entrypoint, 13 layers |
| `docker image rm nexachat-app:ops-check` | PASS — only the temporary audit tag removed |
| `cd server && mvn -q -Dtest=ProductionConfigurationTest test` | PASS — 6 tests, 0 failures/errors/skips |
| `(cd server && mvn -B verify)` | NOT_RUN — full suite not run by this worker |
| `(cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon --no-parallel --max-workers=1)` | NOT_RUN — another Gradle daemon was BUSY |
| Android release build `android/gradlew.bat :app:assembleRelease -PskipSigning --no-parallel --max-workers=2` | PASS — unsigned APK generated (`app-release-unsigned.apk`, 28.58 MB), R8 full mode minification and resource shrinking enabled, 0 errors |
| `python -B tools/check-markdown-links.py docs/operations.md` | PASS — no broken relative links |
| `python -B tools/check-markdown-links.py` | PASS — 56 relative links checked across 43 files |
| `bash -n demo/*.sh tools/*.sh` | PASS |
| PowerShell parser and `--help` checks for the four `.ps1` scripts | PASS |
| Python syntax and `--help` checks for the root `tools/*.py` scripts | PASS |
| `python -B tools/test_seed_demo_chat.py` | PASS — 5 tests |
| `shellcheck demo/*.sh tools/*.sh` | NOT_RUN — ShellCheck is not installed |
| `actionlint .github/workflows/ci.yml` | NOT_RUN — actionlint is not installed |
| GitHub Actions workflow run | NOT_RUN — static workflow configuration is not live CI evidence |
| Database-readiness and log commands in Troubleshooting | NOT_RUN — conditional operator examples |
| `git diff --check` | PASS — no whitespace errors; Git reported expected line-ending notices |
