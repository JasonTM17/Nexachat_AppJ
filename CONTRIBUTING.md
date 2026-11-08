# Contributing to NexaChat

NexaChat is a real-time messaging platform monorepo: a Spring Boot 3.4 server
(`server/`) and a Java 21 LTS Android app (`android/`). This document covers
environment setup, running and testing the project, and contribution standards.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 LTS (target) | `server/pom.xml` → `java.version=21`; Gradle `compileOptions` Java 21 (`VERSION_21`); CI uses Temurin 21. Use JDK 21 for the supported build configuration. |
| Maven | 3.9+ | Verified: Apache Maven 3.9.12. There is **no** `server/mvnw` wrapper — use the system `mvn`. |
| Android SDK | compileSdk/targetSdk 35, minSdk 26 | `ANDROID_HOME` / `ANDROID_SDK_ROOT` set; `android/local.properties` carries `sdk.dir` (gitignored). |
| Gradle | 8.14.3 wrapper | `android/gradlew` (wrapper jar committed). AGP 8.7.3, Java 21 LTS, Hilt 2.52 (`annotationProcessor`), Room 2.6.1 (`room-rxjava3`), RxJava 3.1.9 (see `android/gradle/libs.versions.toml`). |
| Docker Desktop | recent | Verified: Docker Compose v5.6.0. PostgreSQL 16 runs on host port 5433. |
| Python | 3.x | Used by `tools/check-markdown-links.py` and E2E test utilities. |
| `adb` | Android SDK platform-tools | For installing the app on a device/emulator. |

## Clone and run

```bash
git clone <repository-url> nexachat
cd nexachat

# Configure local database credentials and JWT secret from the placeholder template.
cp .env.example .env
# Edit .env and replace the local-only password and JWT secret placeholders.

# 1. Backend + database (first run builds the image, a few minutes)
docker compose up -d db app
curl http://localhost:8080/actuator/health      # {"status":"UP"}

# 2. Seed demo accounts (optional; re-runnable)
docker exec -i $(docker compose ps -q db) psql -U nexachat -d nexachat < demo/seed.sql

# 3. Android app
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

`demo/lan-up.sh` (Git Bash) or `demo/lan-up.ps1` (PowerShell) performs steps
1–2, detects the LAN IP, and prints the URLs and demo accounts. See
[docs/operations.md](docs/operations.md) for deployment and runtime configuration details.

To point a physical device at the backend, add one line to
`android/local.properties` and rebuild (debug builds allow cleartext):

```properties
API_BASE_URL=http://<LAN-IP>:8080
```

## Running the tests

```bash
# Server: unit + PostgreSQL integration tests (*IT via failsafe)
cd server && mvn -B verify

# Android: JVM unit tests + lint
cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug
```

Integration tests (`*IT`) need a real PostgreSQL. Locally they default to the
compose database on `localhost:5433`; CI sets `IT_DB_URL` / `IT_DB_USER` /
`IT_DB_PASSWORD` for its service container (see `.github/workflows/ci.yml`).

Instrumented migration test (requires an emulator/device, not run in CI):

```bash
cd android
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.nexachat.core.database.RoomMigrationTest
```

## Project layout

```text
server/src/main/java/com/nexachat/
  auth/ user/ contact/ conversation/ message/ attachment/ presence/ call/ summary/ notification/ ws/ common/ config/
android/app/src/main/java/com/nexachat/
  call/                        Java call core/media/service/UI
  core/{common,database,network,storage,ui}/
  data/                       repositories (Room write-through, outbox, resync)
  di/                         Hilt modules (`annotationProcessor`)
  feature/{auth,chat,contacts,group,inbox,interactions,media,profile,splash,summary}/
  summary/                    Java summary ViewModel + repository layer
server/src/main/resources/db/migration/   Flyway V1–V7
android/gradle/libs.versions.toml         dependency version catalog
docs/                                     architecture, contracts, security, operations
demo/                                     seed.sql + LAN bootstrap scripts
tools/                                    release signing, link checks, E2E and summary evaluation scripts
```

## Branching and commits

- Branch from `main`/`develop` as `feature/<scope>` (never `codex/`).
- Use [Conventional Commits](https://www.conventionalcommits.org): `feat`,
  `fix`, `docs`, `chore`, `ci`, optionally with a scope — e.g.
  `feat(server/auth): …`, `fix(app/chat): …`.
- One concern per commit; keep the diff reviewable.

## Pull requests

- Target `develop` (or `main` for releases); require at least one cross-review.
- Fill in `.github/pull_request_template.md`.
- Checklist:
  - [ ] Tests run and green (`mvn -B verify`, `./gradlew :app:testDebugUnitTest :app:lintDebug`).
  - [ ] New user-facing strings have matching EN/VI resource names in paired files under `values/` and `values-vi/` (`strings.xml`, `strings_call.xml`, `strings_summary.xml`).
  - [ ] No secrets, keystores, `.env*`, `local.properties`, `release/` or `server/data` in the diff.
  - [ ] Documentation updated when behaviour or contracts change (`docs/`).

## Code style

- Java: 4-space indent; keep lines within 140 characters (current max 135).
- XML/YAML/JSON/TOML: 2-space indent. Shell: 2-space; PowerShell/Python: 4-space.
- Repository stores LF for text (`.gitattributes`); do not commit CRLF text files.
- No formatter plugin (spotless/Checkstyle) is configured; match
  the surrounding code style instead of reformatting whole files.

## Never commit

`release/` (keystore, signing properties, built APKs), `.env*`, `*.keystore`,
`*.jks`, `keystore.properties`, `signing.properties`, `local.properties`,
`google-services.json`, `server/data/`, `server/uploads/`, `demo/backups/`,
build outputs (`build/`, `target/`, `.gradle/`), `docs/evidence/`,
and the `.agentkit/`, `.agents/`, `.claude/`, `.codex/`, `.devin/`, `plans/`
workspace directories. `.gitignore` and the CI secret scan enforce most of this.
