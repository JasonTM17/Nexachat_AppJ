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
| `FCM_ENABLED` | No (optional push notifications) | `false` | `false` (set `true` only with valid credentials) |
| `FCM_PROJECT_ID` | Yes when `FCM_ENABLED=true` | Empty | `your-firebase-project-id` |
| `FCM_SERVICE_ACCOUNT_JSON` | Yes when `FCM_ENABLED=true` | Empty | Inline service-account JSON or a path to the key file |
| `FCM_TOKEN_URI` | No (local testing only) | Empty (Google token endpoint) | `https://oauth2.googleapis.com/token` |
| `FCM_API_URL` | No (local testing only) | Empty (Google FCM endpoint) | `https://fcm.googleapis.com/v1/projects/<id>/messages:send` |
| `NEXACHAT_SUMMARY_LLM_ENABLED` | No (optional external summary LLM) | `false` | `false` |
| `NEXACHAT_SUMMARY_LLM_BASE_URL` | Yes when summary LLM enabled | `https://generativelanguage.googleapis.com/v1beta/openai/` | `https://generativelanguage.googleapis.com/v1beta/openai/` |
| `NEXACHAT_SUMMARY_LLM_MODEL` | Yes when summary LLM enabled | `gemini-3.1-flash-lite` | `gemini-3.1-flash-lite` |
| `NEXACHAT_SUMMARY_LLM_API_KEY` | Yes when summary LLM enabled | None | `your-openai-or-gemini-api-key` |
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

## Start, stop, and health

```sh
docker compose up -d db app
docker compose ps
curl -fsS http://127.0.0.1:8081/actuator/health
```

| Service | Host → container | Use |
|---|---:|---|
| App | `8080 → 8080` | REST API, STOMP WebSocket |
| Management | `8081 → 8081` (loopback) | Health, metrics, Prometheus |
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

The server exposes three observability surfaces, all on a separate
loopback-bound management port so the public API port never serves actuator
endpoints:

| Surface | Default | How to enable |
|---|---|---|
| Health (`/actuator/health`) | Exposed | Always |
| Metrics (`/actuator/metrics`) | Hidden | `MANAGEMENT_EXPOSURE=health,metrics` |
| Prometheus (`/actuator/prometheus`) | Hidden | `MANAGEMENT_EXPOSURE=health,metrics,prometheus` |

The management port defaults to `8081` and binds to `127.0.0.1`:

```yaml
management:
  server:
    port: ${MANAGEMENT_PORT:8081}
    address: ${MANAGEMENT_ADDRESS:127.0.0.1}
```

Do not bind the management port to a public interface without adding
authentication in front of it. The Compose health check must use the
management port (`http://127.0.0.1:8081/actuator/health`), not `8080`.

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

Back up before replacing the app image. Flyway migrations (currently V1 through V10,
`V1__init.sql` to `V10__reaction_events_idempotency_device_tokens.sql`) apply on app startup
and are forward-only: add a new versioned SQL migration; never edit one already
applied to a database. Migrations V8–V10 introduce:
- `V8__attachment_blurhash_search_trigram.sql`: `attachments.blur_hash` column and composite GIN trigram index `idx_msg_conv_content_trgm` on `(conversation_id, f_unaccent(content))`.
- `V9__conversation_rag.sql`: `message_embeddings` table with bytea vectors, `rag_ask_cache`, and `rag_ask_request_limits`.
- `V10__reaction_events_idempotency_device_tokens.sql`: `reaction_events` audit table for delta sync, `request_idempotency` replay cache, and `UNIQUE(user_id, token)` device token constraint.

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
| Android release build `android/gradlew.bat :app:assembleRelease --no-parallel --max-workers=1` | Historical FAIL from an earlier ops pass: Hilt `annotationProcessor` rejected `CallActivity` before it extended `ComponentActivity`. Current source is `@AndroidEntryPoint` on `CallActivity extends ComponentActivity` (`CallActivity.java:42-43`). This row is not a current release-build result. |
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
