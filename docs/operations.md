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

#### Android client: `google-services.json`

The Android app must be registered in the same Firebase project so it can
obtain an FCM registration token. The app id is `com.nexachat` and debug builds
append `.debug`, so the debug application id is `com.nexachat.debug`; register
both ids if release builds should receive pushes too.

1. Open the Firebase console (<https://console.firebase.google.com/>) and select
   the project (`nguyenson-58d0b`).
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
3. Force-stop the app, then from another account send that user a message with the
   FCM-enabled server running. The data-only push wakes the app to sync; on next
   launch the message must be present without a manual refresh.

## Start, stop, and health

```sh
docker compose up -d db app
docker compose ps
curl -fsS http://localhost:8080/actuator/health
```

| Service | Host → container | Use |
|---|---:|---|
| App | `8080 → 8080` | REST API, STOMP WebSocket, health check |
| PostgreSQL | `5433 → 5432` | Local tools and integration tests |

The app also serves STOMP WebSocket at `ws://localhost:8080/ws`. Only the health actuator endpoint is exposed. The
named `pgdata` and `app-uploads` volumes persist database rows and uploads
across container replacement.

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
