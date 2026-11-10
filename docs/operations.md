# Operations

This guide covers the single-node Compose demo and the repository's local CI
checks. Compose reads its settings from the ignored root `.env` file; use
`.env.example` as a placeholder-only template and replace the password and JWT
secret before starting the stack. Do not commit `.env` or signing files.

## Configuration

Boolean feature flags and `*.enabled` settings (for example `FCM_ENABLED`,
`NEXACHAT_RAG_ENABLED`) must be exactly `true` or `false` in the environment or
`.env` file. Spring Boot 3.5 no longer treats values such as `1`, `yes`, or
`on` as enabled.

| Variable | Required in production | Compose default | Placeholder example |
|---|---|---|---|
| `DATABASE_URL` | Yes outside Compose; Compose supplies a default | `jdbc:postgresql://db:5432/nexachat` | `jdbc:postgresql://db:5432/nexachat` |
| `DATABASE_USER` | Yes | None; set in `.env` | `nexachat` |
| `DATABASE_PASSWORD` | Yes | None; set in `.env` | `replace-with-a-local-only-password` |
| `JWT_SECRET` | Yes; at least 32 UTF-8 bytes, not the repository development value, at least 8 distinct characters | None; set in `.env` | `replace-with-at-least-32-random-characters-before-running` |
| `STORAGE_ROOT` | Yes outside Compose; Compose supplies a default | `/app/data/uploads` | `/app/data/uploads` |
| `MANAGEMENT_SERVER_PORT` | No (opt-in loopback isolation; shares API port 8080 by default) | Empty | `8081` |
| `MANAGEMENT_SERVER_ADDRESS` | No (bind address for management server) | Empty | `127.0.0.1` |
| `MANAGEMENT_EXPOSURE` | No (actuator endpoints to expose) | `health` | `health,metrics,prometheus` |
| `MANAGEMENT_DISK_THRESHOLD` | No (disk space warning threshold on storage root) | `1GB` | `1GB` |
| `NEXACHAT_CORS_ALLOWED_ORIGINS` | No (comma-separated browser origins; empty admits no web caller) | Empty | `https://web.nexachat.com` |
| `NEXACHAT_ADMIN_PHONES` | No (comma-separated admin E.164 phones allowed to read `/api/v1/rag/stats`) | Empty | `+84900000001` |
| `NEXACHAT_MAX_JSON_BODY_BYTES` | No (ceiling for JSON request bodies) | `1048576` (1 MiB) | `1048576` |
| `NEXACHAT_VALIDITY_CACHE_TTL_SECONDS` | No (TTL for token revocation cutoff cache) | `60` | `60` |
| `NEXACHAT_TRUSTED_PROXY_CIDRS` | No (comma-separated CIDRs/exact IPs of reverse proxies that may set `X-Forwarded-For`; empty = rate limits always use the socket peer) | Empty | `10.0.0.0/8,172.16.0.0/12` |
| `NEXACHAT_GOOGLE_SIGNIN_ENABLED` | No (optional "Sign in with Google"; all Google routes answer 404 while off) | `false` | `false` |
| `NEXACHAT_GOOGLE_WEB_CLIENT_ID` | Yes when Google sign-in is enabled; startup fails closed without it | Empty | `<id>.apps.googleusercontent.com` |
| `NEXACHAT_GOOGLE_ANDROID_CLIENT_IDS` | Yes when Google sign-in is enabled (comma-separated Android OAuth client IDs trusted as token `azp`) | Empty while disabled | `<id>.apps.googleusercontent.com` |
| `NEXACHAT_GOOGLE_JWKS_URI` | No (Google's rotating public keys; override only for tests) | `https://www.googleapis.com/oauth2/v3/certs` | `https://www.googleapis.com/oauth2/v3/certs` |
| `NEXACHAT_GOOGLE_NONCE_TTL_SECONDS` | No (single-use sign-in nonce lifetime) | `300` | `300` |
| `FCM_ENABLED` | No (optional push notifications) | `false` | `false` (set `true` only with valid credentials) |
| `FCM_PROJECT_ID` | Yes when `FCM_ENABLED=true` | Empty | `your-firebase-project-id` |
| `FCM_SERVICE_ACCOUNT_JSON` | Yes when `FCM_ENABLED=true` | Empty | Inline service-account JSON or a path to the key file |
| `FCM_TOKEN_URI` | No (local testing only) | Empty (Google token endpoint) | `https://oauth2.googleapis.com/token` |
| `FCM_API_URL` | No (local testing only) | Empty (Google FCM endpoint) | `https://fcm.googleapis.com/v1/projects/<id>/messages:send` |
| `FCM_CONNECT_TIMEOUT_SECONDS` | No | `5` | `5` |
| `FCM_READ_TIMEOUT_SECONDS` | No | `10` | `10` |
| `FCM_MAX_RETRIES` | No | `2` | `2` |
| `FCM_RETRY_BASE_DELAY_MS` | No | `200` | `200` |
| `FIREBASE_REALTIME_ENABLED` | No (opt-in Firebase Auth bridge) | `false` | `false` |
| `FIREBASE_DATABASE_URL` | Required when the bridge is enabled | Empty (bridge stays disabled) | `https://<database>.<region>.firebasedatabase.app` |
| `FIREBASE_REALTIME_DEFAULT` | No (reserved rollout default) | `stomp` | `stomp` |
| `FIREBASE_CALL_STATUS_ENABLED` | No (call status is not enabled by this bridge) | `false` | `false` |
| `FIREBASE_CONNECT_TIMEOUT_SECONDS` | No | `5` | `5` |
| `FIREBASE_READ_TIMEOUT_SECONDS` | No | `10` | `10` |
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

### Production profile

Compose starts the app with `SPRING_PROFILES_ACTIVE=prod,json-logs`. In
`server/src/main/resources/application-prod.yml` the prod profile also:

- has no defaults for `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`,
  `JWT_SECRET` or `STORAGE_ROOT` (startup fails if one is unset),
- validates Flyway checksums (`validate-on-migrate: true`) and turns
  `spring.jpa.open-in-view` off,
- caps Hikari at 10 connections per instance (two instances stay below the
  default Postgres `max_connections=100`) and enables gzip response compression,
- keeps only `/actuator/health` exposed; the Compose and image health checks
  probe its `liveness` group, while `readiness` covers database, Flyway and
  storage space.

The `server/Dockerfile` runs as the non-root `nexachat` user and adds
`-XX:+ExitOnOutOfMemoryError` to `JAVA_OPTS`, so a container is replaced rather
than left alive after an OOM.

The default Compose `app` service uses a read-only root filesystem: uploads
persist only on the `app-uploads` volume mounted at `/app/data`, and a
`tmpfs` at `/tmp` (uid/gid `10001`) covers JVM and multipart scratch I/O.
`cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]`, and
`stop_grace_period: 25s` (above the prod graceful-shutdown budget of 20s) apply
to the app container. The Postgres service image is pinned by the linux/amd64
manifest digest in `docker-compose.yml` (not the floating `postgres:16-alpine`
tag). **ARM64 hosts** (Apple Silicon or Linux aarch64) cannot run that digest as
written: Docker pulls a platform-specific manifest, so `docker compose up` fails
unless you change the pin (for example use the multi-arch index digest from the
registry, pin the `linux/arm64` manifest for that tag, or set `platform:
linux/amd64` on the `db` service and accept QEMU emulation).

The Compose `app` service declares `deploy.resources.limits` of **2 CPUs** and
**2G RAM** plus the cgroup hardening above. On a plain `docker compose up`
without Swarm, those limits are not always enforced the same way as on a cluster;
this repository has **not** re-measured effective limits with `docker stats`.
Treat the YAML values as a starting budget and tune for your host before
production.

### Feature configuration and retention

The V12–V20 feature flags, rate limits and ceilings are listed in
[`docs/api-contract.md`](api-contract.md#operational-notes). The operational
schedulers and retention windows are:

| Property | Default | Meaning |
|---|---|---|
| `nexachat.reminders.enabled` | `true` | Enables the reminder due-time poll (`ReminderScheduler`). |
| `nexachat.reminders.poll-interval-ms` | `1000` | Reminder poll cadence. |
| `nexachat.reminders.initial-delay-ms` | `5000` | First poll delay after startup. |
| `nexachat.reminders.batch-size` | `10` | Due reminders claimed per poll. |
| `nexachat.reminders.claim-lease-seconds` | `600` | Claim lease; a crashed claim becomes due again after this. |
| `nexachat.reminders.push-timeout-ms` | `10000` | Reminder FCM fan-out timeout. |
| `nexachat.sync.reaction-retention-days` | `30` | Reaction-event retention horizon for delta sync; purged only when `NEXACHAT_RETENTION_REACTION_EVENTS_ENABLED=true` (see [Data retention and cleanup](#data-retention-and-cleanup)). |
| `nexachat.sync.max-reaction-updates` | `200` | Reaction events scanned per conversation per delta request. |
| `nexachat.group.max-members` | `100` | Group capacity enforced on invite joins and approvals. |

Reminder delivery is at-least-once: a crash after FCM accepts a push but before
the database advance can produce a duplicate. Completed one-off reminders stay in
the database until an operator enables the corresponding retention job (see
[Data retention and cleanup](#data-retention-and-cleanup)); repeating reminders keep
`fired_at IS NULL` and advance to their next future occurrence.

Reaction events (`reaction_events`, V10) and idempotency rows (`request_idempotency`,
V10) are purged by `RetentionJobRunner` only when their job switches are enabled.
A delta request whose `since` is older than `nexachat.sync.reaction-retention-days`
receives `reactionsFullRefresh=true` even when no purge has run yet.

Flyway `V22` adds retention-oriented indexes; bounded cleanup jobs live in
`RetentionJobRunner` and are disabled by default (`NEXACHAT_RETENTION_ENABLED=false`).

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

Compose interpolates `${FCM_ENABLED:-false}` from the **host environment before**
the `.env` file. An exported host `FCM_ENABLED=true` therefore overrides the
`.env` value and selects the real Google gateway; if you want the mock gateway,
unset it in the shell (`env -u FCM_ENABLED docker compose up -d app`) before
starting Compose.

Real delivery requires an active Firebase project, a service account with the
Firebase Cloud Messaging API enabled, and an Android client with a matching
`android/app/google-services.json` that registers its device token. None of
these external credentials or files are bundled in the repository, and
end-to-end delivery is not covered by CI.

### Firebase realtime auth bridge (staged rollout)

The Firebase bridge is disabled by default. It creates its Spring beans and
contacts Firebase only when `FIREBASE_REALTIME_ENABLED=true` **and**
`FIREBASE_DATABASE_URL` is non-empty. If either condition is false, the bridge
routes are absent and the existing STOMP service continues to work. A non-empty
URL must be the HTTPS root URL of a Firebase Realtime Database; an invalid URL
fails startup. Keep the URL empty in `.env` until the Firebase project and rules
are ready.

This bridge reuses `FCM_PROJECT_ID` and `FCM_SERVICE_ACCOUNT_JSON`; FCM push can
remain disabled independently with `FCM_ENABLED=false`. The service account
must belong to the same project and have the Firebase Authentication and
Realtime Database permissions required by the deployment. Provide it through
the process environment or a protected key file; never commit the key. The
backend uses it to sign custom auth tokens, revoke Firebase refresh tokens, and
write private lease state over the RTDB REST API.

Before enabling the bridge in a Firebase project:

1. Enable Firebase Authentication and create a Realtime Database in the chosen
   region. Keep the database in locked mode during setup.
2. Review and install the complete rules candidate at
   `tools/firebase-rules/database.rules.json`. The default rules deny access;
   user accounts, control state, and current conversation memberships must be
   projected before authorized Firebase reads can succeed. The projection and
   Firebase Emulator authorization checks are owned by later rollout steps;
   this S1 bridge alone does not make a client data path ready for production.
3. Inject `FIREBASE_REALTIME_ENABLED=true` and the exact region-specific
   `FIREBASE_DATABASE_URL` into the backend runtime. Verify the database URL,
   service-account project, installed rules, and projection before directing
   any client to Firebase. `FIREBASE_REALTIME_DEFAULT` stays `stomp` and
   `FIREBASE_CALL_STATUS_ENABLED` stays `false` for this stage.
4. Configure a project budget and billing alerts, then monitor database reads,
   writes, and download volume during a staged rollout.

The current Compose file does not forward `FIREBASE_*` variables into the app
container. Setting them only in `.env` will not enable this bridge in Compose;
the deployment configuration must explicitly pass the reviewed Firebase
variables to the backend before the feature is enabled. To turn the bridge off,
set `FIREBASE_REALTIME_ENABLED=false` and restart the backend. Existing STOMP
clients and routes remain available.

Bridge issue, renewal, and single-session deletion hold a lock on that user's
PostgreSQL row through the Firebase request so they serialize with logout and
password-change revocation on every server instance sharing the database. RTDB
REST calls use the configured connect/read timeouts; Firebase Admin requests
use the same timeout settings. Google service-account token refresh has retries
disabled and uses the Google Auth HTTP client's bounded 20-second connect/read
defaults. A slow Firebase request can therefore delay another bridge operation
or logout for that same user until its timeout; other users use different rows.
If a database commit fails after a lease write, the response is not returned and
the orphaned lease expires within 120 seconds.

**Mute gating.** `NEW_MESSAGE` pushes are skipped for members who muted the
conversation (per-member `muted` / `muted_until` on `conversation_members`; a
past `muted_until` no longer mutes), including `SYSTEM` membership and title
notices. A muted member still receives a data-only push pointer when the
message explicitly mentions them or has `mentionAll: true`
(`mentioned="true"`), and when a `SYSTEM` event is about that member — they
were removed from the group or their role changed — so nobody is removed or
demoted silently. A removed member's mute row is deleted with their membership,
so the removal notice still reaches them. Avatar-only changes broadcast
`CONVERSATION_UPDATED` and post no SYSTEM message, so they produce no
`NEW_MESSAGE` push to gate. The payload contains no message content.
`CALL_INVITE` pushes are never gated. A muted conversation still receives
messages, receipts and live WebSocket events, and its `unreadCount` keeps
advancing.

#### Reminder delivery

By default, the scheduler starts after five seconds and polls once per second.
It reads due reminders from PostgreSQL and persists each claim with a lease
before sending pushes. `FOR UPDATE SKIP LOCKED` lets multiple instances
claim different rows; expired leases make abandoned work eligible again after a
crash. Repeating reminders send one catch-up push after downtime, then advance
to the next future occurrence. Delivery is at-least-once: a crash after FCM
accepts a push but before the database advance can produce a duplicate. See
`ReminderScheduler`, `ReminderClaimService` and `ReminderSchedulerIT`.

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
JWT filter rejects any token whose `iat` is at or before that cutoff, so a stolen
access token stops working immediately instead of living out its lifetime.

- The cutoff is stored at one-second resolution because JWT `iat` has no
  sub-second precision: tokens whose `iat` is equal to the cutoff second fail
  closed. A token minted in that same second after revocation may need a refresh
  after the next second boundary.
- The filter caches "account exists + cutoff" per user id in a bounded LRU
  (`nexachat.security.validity-cache-ttl-seconds`, default 60, capped at the
  access-token lifetime) so the hot path does no database work; a cache miss
  falls back to a projection query.
- Logout, logout-all and password change evict the cached entry, so revocation
  is immediate on a single node. In a multi-node deployment each node keeps its
  own cache, so revocation propagates within one TTL.
- After the revocation transaction commits, the local `StompSessionRegistry`
  closes that user's tracked WebSocket sessions. The event is process-local;
  multi-instance deployments do not broadcast the close to other servers.

Logout revokes only the presented refresh-token lineage. It leaves other
lineages' cached replay responses intact, even though the account-wide access
cutoff also invalidates their existing access JWTs. Logout-all and password
change still invalidate replay responses for the sessions they revoke.

### Keyed refresh replay

`NEXACHAT_REFRESH_REPLAY_KEY` enables response recovery for a refresh request
that includes a canonical UUIDv4 `Idempotency-Key`. Generate a Base64 value from
32 cryptographically random bytes (for example, `openssl rand -base64 32`),
store it in the deployment secret manager, and inject the same value into every
server instance. Do not commit it, print it in logs, or reuse `JWT_SECRET`.
`.env.example` intentionally leaves the local value blank.

The first keyed rotation stores the encrypted response with its predecessor for
30 seconds. Keep the key stable across restarts and replicas. There are no key
versions or grace-key lookup: changing or removing the key during that window
makes an outstanding retry return `409 REFRESH_RECOVERY_UNAVAILABLE`. Rotate
only after the previous key has been unused for at least 30 seconds.

If the key is missing or malformed, startup logs one warning and keyed requests
fall back to legacy strict rotation; they do not return `503` for configuration
absence and do not store recovery payloads. A retry of that spent predecessor
returns `401 TOKEN_REUSED` and revokes the family. The readiness component
`refreshReplay` is `DOWN` in the `prod` profile while the key is unavailable;
the process still starts and serves strict refresh rotation, so this check does
not block the fallback request path.

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
- Only `/actuator/health` (including its `liveness` and `readiness` groups) is
  public. Every other actuator endpoint requires a Bearer token and only
  `health` is exposed.

### Admin-only telemetry

`GET /api/v1/rag/stats` returns aggregate RAG counters (no message text,
questions, vectors or user identifiers) but is operator telemetry, not user
data. It is restricted to the E.164 numbers in
`nexachat.security.admin-phones` (env `NEXACHAT_ADMIN_PHONES`); when the list
is empty the endpoint is denied to everyone. The decision is re-evaluated per
request from the user row, so removing a phone takes effect immediately.

### Reverse proxies and client IP for rate limits

Production enables `server.forward-headers-strategy=framework` so Spring honors
`X-Forwarded-Proto` and `X-Forwarded-Host` from a TLS terminator. That filter
can also rewrite `getRemoteAddr()` from `X-Forwarded-For`, which clients could
otherwise spoof when they reach the app directly.

Per-IP rate-limit buckets (credential endpoints, invite resolve/join, and the
small IP fallbacks on authenticated interceptors) therefore use the **socket peer
address** captured before the forwarded-header filter runs. Only when the peer
matches an entry in `nexachat.security.trusted-proxy-cidrs` (env
`NEXACHAT_TRUSTED_PROXY_CIDRS`, comma-separated CIDRs or exact IPs) does the
service use the framework-rewritten client address. The default empty list is
safe for direct exposure; set it to the subnet your reverse proxy connects from
when TLS terminates in front of the JVM.

### Rate limits and body bounds

- Credential endpoints are limited per source IP (login 10/5 min, register
  10/5 min, refresh 30/5 min) **and** per normalized phone (login and register
  20/5 min), so rotating IPs cannot brute-force one account.
- V12–V20 feature writes use per-user token buckets: labels 30/min (shared),
  polls 10/min create / 120/min vote / 30/min manage, invite resolve 60/min and
  join 20/min (also per client IP), reminders 30/min create, message edit/delete
  30/min and search 60/min. Keys and defaults are in
  [`docs/api-contract.md`](api-contract.md#operational-notes).
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
to a loopback-bound management port before exposure. Health remains public;
every other exposed Actuator endpoint requires a valid JWT for a phone in
`NEXACHAT_ADMIN_PHONES`:

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

Keep the management port bound to loopback. If you expose it through an edge,
retain the admin-JWT check and add network controls. When you move the port,
point the Compose and Dockerfile health checks at it too.

### Health

`/actuator/health` is public and returns component names and statuses only
(`show-details: never`). It includes:

- `db` — a `SELECT 1` validation query against the configured DataSource.
- `flyway` — Flyway validation plus the applied version and pending count
  (details hidden).
- `diskSpace` — free space on the upload directory (`nexachat.storage.root`)
  with a configurable threshold (`MANAGEMENT_DISK_THRESHOLD`, default `1GB`).
- `ping` — liveness.

Two groups are exposed and both are public:

- `/actuator/health/liveness` — `ping`; this is what the Compose and Dockerfile
  health checks call.
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

Compose already sets `SPRING_PROFILES_ACTIVE=prod,json-logs`. For a standalone
deployment, set the same value (or add `json-logs` to the active profiles) to
emit one JSON object per line:

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

Back up before replacing the app image. Flyway migrations V1 through V25 apply
on app startup and are forward-only: add a new versioned SQL migration; never
edit one already applied to a database. The full migration list with intent is in
[`docs/api-contract.md`](api-contract.md#migration-history); the migrations after
V11 introduce:

- `V12__conversation_member_mute_archive.sql`: per-member `muted` /
  `muted_until` / `archived` columns on `conversation_members`.
- `V13__conversation_ai_opt_outs.sql`: `conversation_ai_opt_outs`.
- `V14__conversation_reminders.sql`: `conversation_reminders` plus the due-scan,
  active-board and fired-retention indexes.
- `V15__group_polls.sql`: `polls`, `poll_options`, `poll_votes` and the
  `poll_version_seq` change-feed sequence.
- `V16__conversation_pin_seen_privacy.sql`: per-member `pinned` / `pinned_at`
  and `users.show_seen`.
- `V17__message_mentions.sql`: `message_mentions` and `messages.mention_all`.
- `V18__conversation_labels.sql`: `conversation_labels` and
  `conversation_label_assignments`.
- `V19__query_performance_indexes.sql`: partial delta-sync indexes
  `idx_messages_conv_edited` / `idx_messages_conv_deleted`.
- `V20__group_invite_links.sql`: `group_invite_links`, `group_join_requests`,
  `group_invite_bans`.

`V21` is reserved for the recall-window worker and `V23` is intentionally
unused; neither has a file. `V22__retention_cleanup_indexes.sql` adds the
retention indexes, `V24__hot_path_perf.sql` drops the unused `idx_conv_inbox`,
and `V25__refresh_rotation_replay.sql` adds encrypted refresh-response replay
metadata and cleanup support. Ensure every migration file V1–V20, `V22`,
`V24` and `V25` is present in the source tree before rebuilding the dev app container; the image packages
the server migration resources used by Flyway at startup.

```sh
bash demo/backup.sh
docker compose build app
docker compose up -d app
```

If a migration fails, keep the app stopped while investigating. Recovery to a
pre-upgrade database state requires restoring the pre-upgrade backup; starting
an older app against a newer schema is not a rollback procedure.

## Data retention and cleanup

Retention cleanup runs in the server process. Every job is disabled by default,
and the global switch is also off. When enabled, the scheduler executes at most
one ordered batch per enabled job per interval. Database batches use
`SKIP LOCKED`; batch size, statement timeout, and transaction timeout are
validated and bounded. Start with dry-run counts, review the metrics and
retention policy, then explicitly disable dry-run before allowing deletion.

For example, this enables only the expired idempotency cleanup in dry-run mode:

```sh
export NEXACHAT_RETENTION_ENABLED=true
export NEXACHAT_RETENTION_IDEMPOTENCY_ENABLED=true
export NEXACHAT_RETENTION_DRY_RUN=true
```

After reviewing `nexachat.retention.rows.observed`, set
`NEXACHAT_RETENTION_DRY_RUN=false` to delete eligible rows in batches. Restart
the server after changing environment values. Supply these values to the server
process through the deployment's environment configuration; setting them only
in a host shell does not pass them into a container. The checked-in Compose demo forwards every `NEXACHAT_RETENTION_*` variable from
`.env` into the `app` service (defaults keep all jobs off). Do not enable the
global switch alone if no cleanup job should run.

All retention properties, their environment variables, and defaults are listed
below. Every job-level `enabled` switch defaults to `false`.

| Property | Environment variable | Default | Effect / validation |
| --- | --- | --- | --- |
| `nexachat.retention.enabled` | `NEXACHAT_RETENTION_ENABLED` | `false` | Global opt-in. |
| `nexachat.retention.dry-run` | `NEXACHAT_RETENTION_DRY_RUN` | `true` | Count candidates without deleting. |
| `nexachat.retention.batch-size` | `NEXACHAT_RETENTION_BATCH_SIZE` | `100` | Rows per job batch; 1–500. |
| `nexachat.retention.statement-timeout-ms` | `NEXACHAT_RETENTION_STATEMENT_TIMEOUT_MS` | `2000` | PostgreSQL statement timeout; 100–30000 ms. |
| `nexachat.retention.transaction-timeout-seconds` | `NEXACHAT_RETENTION_TRANSACTION_TIMEOUT_SECONDS` | `15` | Per-job transaction timeout; 5–60 s. |
| `nexachat.retention.interval-ms` | `NEXACHAT_RETENTION_INTERVAL_MS` | `3600000` | Scheduler delay; 60000–86400000 ms. |
| `nexachat.retention.jobs.join-requests.enabled` | `NEXACHAT_RETENTION_JOIN_REQUESTS_ENABLED` | `false` | Remove only reviewed requests after the retention age. |
| `nexachat.retention.jobs.join-requests.retention-days` | `NEXACHAT_RETENTION_JOIN_REQUESTS_RETENTION_DAYS` | `30` | Age from `reviewed_at`; 1–3650 days. Pending requests are preserved. |
| `nexachat.retention.jobs.invite-bans.enabled` | `NEXACHAT_RETENTION_INVITE_BANS_ENABLED` | `false` | Remove link-specific bans after their invite reaches the grace age. Global bans are preserved. |
| `nexachat.retention.jobs.invite-links.enabled` | `NEXACHAT_RETENTION_INVITE_LINKS_ENABLED` | `false` | Remove expired, revoked, or used-up links after grace, once requests and link-specific bans are gone. |
| `nexachat.retention.jobs.invite-links.grace-days` | `NEXACHAT_RETENTION_INVITE_LINKS_GRACE_DAYS` | `30` | Grace after expiry/revocation/last use; 1–365 days. |
| `nexachat.retention.jobs.idempotency.enabled` | `NEXACHAT_RETENTION_IDEMPOTENCY_ENABLED` | `false` | Remove expired request replay records. |
| `nexachat.retention.jobs.idempotency.ttl-hours` | `NEXACHAT_RETENTION_IDEMPOTENCY_TTL_HOURS` | `24` | Idempotency record age; 1–8760 hours. |
| `nexachat.retention.jobs.refresh-tokens.enabled` | `NEXACHAT_RETENTION_REFRESH_TOKENS_ENABLED` | `false` | Remove expired or revoked refresh tokens after the retention age. |
| `nexachat.retention.jobs.refresh-tokens.retention-days` | `NEXACHAT_RETENTION_REFRESH_TOKENS_RETENTION_DAYS` | `30` | Age; 1–3650 days. |
| `nexachat.retention.jobs.device-tokens.enabled` | `NEXACHAT_RETENTION_DEVICE_TOKENS_ENABLED` | `false` | Remove stale device tokens. |
| `nexachat.retention.jobs.device-tokens.stale-days` | `NEXACHAT_RETENTION_DEVICE_TOKENS_STALE_DAYS` | `180` | Age since last use, or since update for never-used tokens; 1–3650 days. |
| `nexachat.retention.jobs.reminders.enabled` | `NEXACHAT_RETENTION_REMINDERS_ENABLED` | `false` | Remove only fired one-shot reminders. |
| `nexachat.retention.jobs.reminders.retention-days` | `NEXACHAT_RETENTION_REMINDERS_RETENTION_DAYS` | `30` | Age since fire; 1–3650 days. Recurring and unfired reminders remain. |
| `nexachat.retention.jobs.polls.enabled` | `NEXACHAT_RETENTION_POLLS_ENABLED` | `false` | Redact aged poll payloads and remove options/votes. The poll row remains as a change-feed tombstone. |
| `nexachat.retention.jobs.polls.retention-days` | `NEXACHAT_RETENTION_POLLS_RETENTION_DAYS` | `365` | Age from the earliest close, scheduled close, or deletion time; 1–3650 days. |
| `nexachat.retention.jobs.call-records.enabled` | `NEXACHAT_RETENTION_CALL_RECORDS_ENABLED` | `false` | Remove only ended call records. |
| `nexachat.retention.jobs.call-records.retention-days` | `NEXACHAT_RETENTION_CALL_RECORDS_RETENTION_DAYS` | `365` | Age since end; 1–3650 days. |
| `nexachat.retention.jobs.attachments.enabled` | `NEXACHAT_RETENTION_ATTACHMENTS_ENABLED` | `false` | Remove eligible attachment rows and stored files after safe-key/reference checks. |
| `nexachat.retention.jobs.attachments.unbound-hours` | `NEXACHAT_RETENTION_ATTACHMENTS_UNBOUND_HOURS` | `24` | Age for uploads not bound to a message; 1–8760 hours. |
| `nexachat.retention.jobs.attachments.deleted-message-days` | `NEXACHAT_RETENTION_ATTACHMENTS_DELETED_MESSAGE_DAYS` | `30` | Age since message soft deletion; 1–3650 days. |
| `nexachat.retention.jobs.message-tombstones.enabled` | `NEXACHAT_RETENTION_MESSAGE_TOMBSTONES_ENABLED` | `false` | Clear old tombstone content, mention flags, embeddings, and mention rows; message rows remain. |
| `nexachat.retention.jobs.message-tombstones.retention-days` | `NEXACHAT_RETENTION_MESSAGE_TOMBSTONES_RETENTION_DAYS` | `30` | Age since soft deletion; 1–3650 days. |
| `nexachat.retention.jobs.reaction-events.enabled` | `NEXACHAT_RETENTION_REACTION_EVENTS_ENABLED` | `false` | Remove old reaction delta events. |
| `nexachat.sync.reaction-retention-days` | `NEXACHAT_SYNC_REACTION_RETENTION_DAYS` | `30` | Reaction event retention horizon; 1–3650 days. Clients older than this window receive a full reaction refresh. |
| `nexachat.retention.jobs.summaries.enabled` | `NEXACHAT_RETENTION_SUMMARIES_ENABLED` | `false` | Remove expired message summaries. |
| `nexachat.retention.jobs.summary-limits.enabled` | `NEXACHAT_RETENTION_SUMMARY_LIMITS_ENABLED` | `false` | Remove expired summary request windows. |
| `nexachat.retention.jobs.summary-limits.minute-retention-days` | `NEXACHAT_RETENTION_SUMMARY_LIMITS_MINUTE_DAYS` | `1` | Keep minute windows for 1–30 days. |
| `nexachat.retention.jobs.summary-limits.day-retention-days` | `NEXACHAT_RETENTION_SUMMARY_LIMITS_DAY_DAYS` | `2` | Keep day windows for 1–365 days. |
| `nexachat.retention.jobs.rag-cache.enabled` | `NEXACHAT_RETENTION_RAG_CACHE_ENABLED` | `false` | Remove expired RAG answer cache records. |
| `nexachat.retention.jobs.rag-limits.enabled` | `NEXACHAT_RETENTION_RAG_LIMITS_ENABLED` | `false` | Remove expired RAG request windows. |
| `nexachat.retention.jobs.rag-limits.minute-retention-days` | `NEXACHAT_RETENTION_RAG_LIMITS_MINUTE_DAYS` | `1` | Keep minute windows for 1–30 days. |
| `nexachat.retention.jobs.rag-limits.day-retention-days` | `NEXACHAT_RETENTION_RAG_LIMITS_DAY_DAYS` | `2` | Keep day windows for 1–365 days. |

The scheduler reports Micrometer counters `nexachat.retention.job.runs`
(tagged by job and outcome), `nexachat.retention.rows.observed`,
`nexachat.retention.rows.deleted`, `nexachat.retention.files.deleted`, and
`nexachat.retention.job.errors`. Dry-run rows are reported as observed; deletion
and file counters increase only when the corresponding removal succeeds.

Message, conversation, and user rows are never removed by these jobs. Poll
records stay as compact versioned tombstones so existing change-feed cursors
continue to work. Enable retention in a private/staging environment first and
compare the dry-run counts with your organization's retention requirements.

## JWT secret rotation

`JWT_SECRET` signs access JWTs. In the `prod` profile,
`ProductionJwtSecretGuard` refuses to start with the repository's development
default or a secret with fewer than eight distinct characters; HS256 still
requires at least 32 bytes. Replacing the secret and restarting the app
invalidates all access JWTs signed with the old value; clients must refresh or
sign in again. Refresh tokens are separate opaque values stored in the database as
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

`tools/sign-release.sh` accepts `MAJOR.MINOR.PATCH` tags, optionally with a
leading `v`; it sets Android `versionName` to the normalized tag and derives
`versionCode = MAJOR * 1000000 + MINOR * 1000 + PATCH`. Minor and patch
components are limited to 0–999 and Android's version-code ceiling is enforced.
Direct Gradle builds can set `-PreleaseVersionName` and
`-PreleaseVersionCode`; without them, project defaults remain `1.0.0` and `1`.

### Hardening and security boundaries

1. **R8 full mode and resource shrinking**: Enabled via `android.enableR8.fullMode=true` in `gradle.properties`, and `minifyEnabled true`, `shrinkResources true` in `build.gradle`. Dead code, unused resources, and unneeded metadata are stripped.
2. **Reflective ProGuard keep rules**: Rules in `android/app/proguard-rules.pro` retain names and signatures for reflective Moshi DTOs (network/STOMP, ask, summary, archive, poll, label, invite and reminder models), Retrofit API interfaces, Room entities/DAOs/migrations, Hilt entry points, WorkManager workers (`OutboxSyncWorker`, `DeviceTokenSyncWorker`), Media3/ExoPlayer components, Glide modules, WebRTC native JNI methods, and Firebase Messaging. After building a release variant, run `python tools/check-release-r8-mapping.py` to check the mapping file for the app's reflective class names.
3. **Network security**: Cleartext HTTP/WS is disabled globally in production (`android/app/src/main/res/xml/network_security_config.xml` has `cleartextTrafficPermitted="false"` with zero exemptions). Cleartext access to `10.0.2.2` (emulator) and LAN dev IPs is strictly isolated to the `debug` and `localSmoke` variants (`android/app/src/debug/res/xml/network_security_config.xml`, `android/app/src/localSmoke/res/xml/network_security_config.xml`).
4. **App backup disabled (`android:allowBackup="false"`)**: Backups are disabled to prevent extraction of sensitive local data via `adb backup` or cloud sync. Allowing backup would expose:
   - Decrypted SQLite database containing cached messages, contact lists, conversation watermarks, and drafts.
   - Session tokens and user authentication state stored in DataStore preferences.
   - Downloaded attachment files and cached media in the application cache directory.
5. **No sensitive logging**: `HttpLoggingInterceptor` is stripped in release builds (`BuildConfig.DEBUG` guarded), and no tokens, credentials, or payload bodies are logged.

## Static site: web account deletion (lost device)

The public page `site/delete-account.html` can call the unauthenticated account-deletion OTP endpoints when the API origin is configured at deploy time. The repository ships with an empty API base so browsers make no cross-origin requests until an operator enables the flow.

1. **HTML meta** — Set `content` on `<meta name="nexachat-api-base" content="...">` in `site/delete-account.html` to the HTTPS API origin (no trailing slash), e.g. `https://api.nexachat.io.vn`. Rebuild or redeploy the static site after editing.
2. **Content-Security-Policy** — In `site/vercel.json`, set the global `connect-src` directive to the same API origin (replacing `'none'`). The meta value and the CSP value must change together; an empty meta with a configured origin, or a configured meta with `connect-src 'none'`, fails the site gate.
3. **Server CORS** — Set `NEXACHAT_CORS_ALLOWED_ORIGINS` on the API to include exactly `https://nexachat.io.vn` (and `https://www.nexachat.io.vn` only if you serve the apex redirect host). No wildcard; omitting the site origin blocks browser `fetch` from the deletion form.

`python site/check.py` gates the meta tag, external `delete-account.js`, the default-or-configured `connect-src` pair, and these operator notes.

## Run CI checks locally

The GitHub workflow runs Maven verification with a job-local PostgreSQL 16
service, Android unit tests/lint/debug assembly, Markdown link and static-site
checks, Android string/plural/a11y checks, `actionlint`, shell syntax checks,
and a committed-secret scan. Equivalent local commands are:

```sh
(cd server && mvn -B verify)
(cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon --no-parallel --max-workers=1)
python tools/check-markdown-links.py
python site/check.py
python tools/check-android-strings.py
bash -n demo/*.sh tools/*.sh
```

For local server verification, set `IT_DB_URL`, `IT_DB_USER`, and
`IT_DB_PASSWORD` to a private, disposable PostgreSQL test database; for the
local Compose PostgreSQL host, use port `5433` and create a separate test
database first. Then run `(cd server && mvn -B verify)`. The integration tests
run Flyway and write test data, so do not point them at the app database or a
shared/production database. Unset every inherited `FCM_*` environment variable
before testing: `FCM_ENABLED=true` selects the real Google gateway, while the
credential and URL overrides can enable external calls or route them away from
the local fake endpoints. The default is the mock gateway. The Android command
above limits workers to avoid competing with other local Gradle builds. Run
`shellcheck demo/*.sh tools/*.sh` when ShellCheck is installed.

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
