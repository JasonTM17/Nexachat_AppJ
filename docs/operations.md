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

The Compose app uses `db:5432` on the private Compose network. A server started
directly with Maven on the host uses `localhost:5433` from `application.yml`;
if overriding environment variables for that process, use the host address.
The database stores its initial role/password in the named volume on first
initialization, so changing `DATABASE_PASSWORD` alone does not rotate an
already-initialized database role.

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

Back up before replacing the app image. Flyway migrations apply on app startup
and are forward-only: add a new versioned SQL migration; never edit one already
applied to a database.

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
| Android release build `android/gradlew.bat :app:assembleRelease --no-parallel --max-workers=1` | Historical FAIL from an earlier ops pass: KSP Hilt rejected `CallActivity` before it extended `ComponentActivity`. Current source is `@AndroidEntryPoint` on `CallActivity extends ComponentActivity` (`CallActivity.java:42-43`). This row is not a current release-build result. |
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
