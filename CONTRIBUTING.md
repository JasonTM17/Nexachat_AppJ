# Contributing to NexaChat

NexaChat is a course-project monorepo: a Spring Boot server (`server/`) and an
Android app (`android/`). This document covers environment setup, running and
testing the project, and the contribution rules.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 (target) | `server/pom.xml` → `java.version=17`; Gradle `compileOptions` Java 17; CI uses Temurin 17. Use JDK 17 for the supported build configuration. |
| Maven | 3.9+ | Verified: Apache Maven 3.9.12. There is **no** `server/mvnw` wrapper — use the system `mvn`. |
| Android SDK | compileSdk/targetSdk 35, minSdk 26 | `ANDROID_HOME` / `ANDROID_SDK_ROOT` set; `android/local.properties` carries `sdk.dir` (gitignored). |
| Gradle | 8.10 wrapper | `android/gradlew` (wrapper jar committed). AGP 8.7.3, Java 17, Hilt 2.52 (`annotationProcessor`), Room 2.6.1 (`room-rxjava3`), RxJava 3.1.9 (see `android/gradle/libs.versions.toml`). |
| Docker Desktop | recent | Verified: Docker Compose v5.6.0. PostgreSQL 16 runs on host port 5433. |
| Python | 3.x + `python-docx` | Only for regenerating `docs/report.docx`. Verified: Python 3.14.2, python-docx 1.2.0. |
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
[docs/demo-runbook.md](docs/demo-runbook.md) for the full demo procedure.

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
  auth/ user/ contact/ conversation/ message/ attachment/ presence/ call/ summary/ ws/ common/ config/
android/app/src/main/java/com/nexachat/
  call/                        Java call core/media/service/UI
  core/{common,database,network,storage,ui}/
  data/                       repositories (Room write-through, outbox, resync)
  di/                         Hilt modules (`annotationProcessor`)
  feature/{auth,chat,contacts,group,inbox,interactions,media,profile,splash,summary}/
  summary/                    Java summary ViewModel + repository layer
server/src/main/resources/db/migration/   Flyway V1–V6
android/gradle/libs.versions.toml         dependency version catalog
docs/                                     contracts, security, runbooks, audits
demo/                                    seed.sql + LAN scripts
tools/                                    report generator + release packaging
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
  - [ ] Every line can be explained under defence questioning (explain-back rule).

## Code style

- Java: 4-space indent; keep lines within 140 characters (current max 135).
- XML/YAML/JSON/TOML: 2-space indent. Shell: 2-space; PowerShell/Python: 4-space.
- Repository stores LF for text (`.gitattributes`); do not commit CRLF text files.
- No formatter plugin (spotless/Checkstyle) is configured; match
  the surrounding code style instead of reformatting whole files.

## Regenerating `docs/report.docx`

`docs/report.md` is the canonical source; the `.docx` is generated from it:

```bash
python tools/build-report.py    # requires python-docx >= 1.1
```

`docs/report.md` is canonical; `tools/build-report.py` parses it directly, so
edit only the markdown and regenerate. The script verifies the generated
document (heading/table counts, no leftover markdown artefacts) before
replacing the previous `.docx`.

## Never commit

`release/` (keystore, signing properties, built APKs), `.env*`, `*.keystore`,
`*.jks`, `keystore.properties`, `signing.properties`, `local.properties`,
`google-services.json`, `server/data/`, `server/uploads/`, `demo/backups/`,
build outputs (`build/`, `target/`, `.gradle/`), `docs/evidence/`,
and the `.agentkit/`, `.agents/`, `.claude/`, `.codex/`, `.devin/`, `plans/`
workspace directories. `.gitignore` and the CI secret scan enforce most of this.
