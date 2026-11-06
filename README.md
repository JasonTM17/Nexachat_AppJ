# NexaChat

**NexaChat** is a real-time Android messaging platform: register by phone number,
find and connect with other users, chat 1-1 and in groups over WebSocket/STOMP,
send media/files, edit and delete messages, search conversation history, react,
pin, reply and forward text — in a bilingual (Vi/En) Material 3 UI that keeps
message sending available offline.

> Đồ án nhóm (course group project) · Kotlin + Java core modules + Jetpack Compose (client) ·
> Java + Spring Boot (server) · PostgreSQL · STOMP over raw WebSocket.
> Built for a viva defense — shipped behavior points to source; in-progress work is labeled.

## Quick start

Before the first start, copy `.env.example` to `.env` and replace the local
database password and JWT secret placeholders; see [docs/operations.md](docs/operations.md).

```bash
docker compose up -d db app                                    # backend + PostgreSQL 16
docker exec -i $(docker compose ps -q db) psql -U nexachat -d nexachat < demo/seed.sql
cd android && ./gradlew assembleDebug && adb install app/build/outputs/apk/debug/app-debug.apk
```

Health check: `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`.
Setup, tests and the LAN demo: [CONTRIBUTING.md](CONTRIBUTING.md) ·
[docs/demo-runbook.md](docs/demo-runbook.md).

## Features

Rubric coverage — the full mapping with test evidence is in
[docs/rtm.md](docs/rtm.md).

| Rubric | Shipped | Where |
|---|---|---|
| R2 — account management | Register / login (E.164 phone, bcrypt 12), JWT + rotating refresh tokens with reuse detection, logout / logout-all, change password | `server/auth`, `app/feature/auth`, `core/storage` |
| R2 — contacts | Phone-number search, add/remove contact, alias names | `server/user`, `server/contact`, `app/feature/contacts` |
| R2 — direct chat | 1-1 messaging, optimistic send + outbox retry, read receipts, typing indicator, history paging | `server/message`, `server/ws`, `app/feature/chat` |
| R2 — message management | Edit own text messages, soft-delete own messages, conversation-scoped text search, presence controls and direct-peer status | `server/message`, `server/presence`, `app/feature/chat`, `app/feature/profile` |
| R2 — group chat | Create group, add/remove/leave members, admin roles, rename, system messages | `server/conversation`, `app/feature/group` |
| R3 — preferences | Local `SYSTEM` / `LIGHT` / `DARK` theme and server-backed show-presence preference | `core/ui`, `app/feature/profile`, `server/user` |
| R3 — multimedia/files | Image/video/audio/file upload (magic-byte whitelist, 25MB), owner-or-member download authZ, viewer + ACTION_VIEW | `server/attachment`, `app/feature/media` |
| R3 — i18n | Vi/En resource parity: 227 string entries per locale (225 translatable; base, call and summary files), per-app locale (API 33+ native, <33 config-context) | `res/values*`, `core/common/LocaleManager` |
| R3 — rotation/state | Room-as-SSOT, `SavedStateHandle` drafts, rotation-safe; chat draft/scroll survive process death (transient forms reset — see known-limitations) | `core/database`, `feature/*/ViewModel` |
| R4 — creative | Reply/quote + emoji reactions (toggle) + pinned messages (≤3, group-admin), text-only forward | `server/message`, `app/feature/chat/ChatInteractions`, `core/ui/ForwardFilter` |
| Extra — voice calls | 1-1 audio calls: STOMP signaling, ICE config, server call history, Android call screens, localized call log | `server/call`, `app/call` |
| Extra — AI catch-up summary | Extractive local engine + optional DeepSeek LLM behind consent, citations, Android summary UI | `server/summary`, `app/summary` |
| R5 — UI + tests | Material 3, ≥48dp touch targets and shared state components; test status is reported by the current CI run | `core/ui`, `server/src/test`, `app/src/test` |
| R6 — presentation | This README, `docs/`, viva question bank | `docs/viva-questions.md` |
| R1 — submission | Monorepo + packaging checklist | `docs/packaging-checklist.md` |

## Architecture

```mermaid
flowchart LR
    subgraph Android["android/ — Kotlin + Java core, Jetpack Compose, Hilt"]
        UI["Compose UI<br/>(feature/*)"] --> VM["ViewModels<br/>(UDF, SavedStateHandle)"]
        VM --> REPO["Repositories"]
        REPO --> ROOM[("Room v7 — SSOT<br/>messages · conversations<br/>outbox · reactions · pins · presence")]
        REPO --> TS["TokenStore<br/>AES/GCM @ AndroidKeyStore"]
        REPO --> ST["StompClient<br/>(OkHttp raw WS)"]
        REPO --> RT["Retrofit APIs"]
    end

    subgraph Server["server/ — Spring Boot 3.4 modular monolith"]
        SEC["SecurityConfig<br/>JWT filter · stateless"]
        INT["StompAuthChannelInterceptor<br/>CONNECT authN · SUB/SEND authZ"]
        SVC["Services: auth · user · contact<br/>conversation · message · attachment · presence · call signaling · summary"]
        EVT["@TransactionalEventListener<br/>AFTER_COMMIT broadcast"]
        BR["SimpleBroker<br/>/topic + /user/queue"]
    end

    DB[("PostgreSQL 16<br/>Flyway V1–V6<br/>seq · watermarks · idempotency · calls")]
    FS["File volume<br/>(uploads)"]

    RT -- "REST /api/v1 (JWT Bearer)" --> SEC --> SVC
    ST -- "STOMP /ws (CONNECT: Authorization header)" --> INT --> SVC
    SVC --> DB
    SVC --> FS
    SVC --> EVT --> BR
    BR -. "MESSAGE_CREATED/UPDATED/DELETED · READ · TYPING<br/>PRESENCE · REACTION_UPDATED · PIN_UPDATED · CONVERSATION_UPDATED" .-> ST
```

Two transport contracts, one enforcement point: REST `/api/v1` for
request/response (auth, search, attachments, history, resync) and
STOMP-over-WebSocket `/ws` for realtime push — both funnel through the same
services (`MessageService.send` serves the `@MessageMapping` and the REST
POST identically). Persist-before-broadcast: events fan out only
`AFTER_COMMIT`, so a received frame always describes a committed row. Client
side, Room is the only render source — WS frames are written through, never
rendered directly. Rationale and diagrams: [docs/architecture.md](docs/architecture.md) ·
[docs/websocket-protocol.md](docs/websocket-protocol.md) · [docs/api-contract.md](docs/api-contract.md).

## Tech stack

| Layer | Server (`server/`) | Android (`android/`) |
|---|---|---|
| Language | Java 17 | Kotlin 2.0.21 + Java core helpers |
| Framework | Spring Boot 3.4.1 (Web, WebSocket, Security, Data JPA, Validation, Actuator) | Jetpack Compose (BOM 2024.12.01) + Material 3, Navigation |
| Auth/crypto | jjwt 0.12.6 (HS256), BCrypt cost 12, SHA-256 token hashes | AES/GCM/NoPadding via AndroidKeyStore |
| Realtime | spring-websocket + STOMP, SimpleBroker | OkHttp 4.12 WebSocket + own STOMP codec |
| Persistence | PostgreSQL 16, Flyway V1–V6, `ddl-auto: validate` | Room 2.6.1 (v7, explicit migrations), DataStore and SharedPreferences |
| Files | `StorageService` abstraction, `MagicBytes` sniffing, 25MB cap | Coil 2.7, media3 1.5.1 |
| Other | libphonenumber 8.13.45, bucket4j 8.10.1 rate limiting | Hilt 2.52 DI, Retrofit 2.11 + Moshi, coroutines 1.9 |
| Build/test | Maven + failsafe (`*IT` @ `verify`), Testcontainers dep | Gradle + version catalog, JUnit4/MockK/Turbine |
| Deploy | Multi-stage `server/Dockerfile`, `docker-compose.yml` | `API_BASE_URL` via `local.properties`/`-PapiBaseUrl` |

Design system: tokens, component states and the Stitch→Compose mapping are in
[docs/design/design-notes.md](docs/design/design-notes.md); live emulator
screenshots are in [docs/design/screens/](docs/design/screens/).

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 (Temurin) | `java -version` |
| Maven | 3.9+ | system `mvn` (there is no `server/mvnw` wrapper) |
| Android SDK | minSdk 26 · targetSdk 35 | via Android Studio |
| Docker | recent | PostgreSQL + app image via `docker compose` |

## Quickstart — server

Before starting Compose, configure the root `.env` from `.env.example` as
described in [docs/operations.md](docs/operations.md).

**Containerized (everything in Docker):**

```bash
docker compose up -d db app   # builds server image on first run (~minutes)
```

**Dev (compose DB + local Maven, hot reload):**

```bash
docker compose up -d db       # PostgreSQL 16, host port 5433 → 5432
cd server
mvn spring-boot:run           # Flyway migrates on first boot
```

Health check: `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`.
REST base: `http://localhost:8080/api/v1` · WebSocket: `ws://localhost:8080/ws`.
From the repository root, seed data (demo accounts below) is optional:

```sh
docker compose exec -T db psql -U nexachat -d nexachat < demo/seed.sql
```

With host `psql`, run this from the repository root instead:

```sh
psql -h localhost -p 5433 -U nexachat -d nexachat < demo/seed.sql
```

## Quickstart — android

1. Open `android/` in Android Studio (Hedgehog+).
2. Create `android/local.properties` (never committed):

   ```properties
   sdk.dir=<path-to-Android-SDK>
   # emulator → host loopback alias (default): http://10.0.2.2:8080
   # physical device on LAN → your laptop's IP:
   API_BASE_URL=http://192.168.x.x:8080
   ```

   (`API_BASE_URL` can also be passed as `./gradlew -PapiBaseUrl=…`; the value
   lands in `BuildConfig.API_BASE_URL`, `app/build.gradle.kts:13-35`.)

3. Build and install:

   ```bash
   cd android
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

   or just press ▶ in Android Studio. USB debugging alternative:
   `adb reverse tcp:8080 tcp:8080` then keep `localhost`/`10.0.2.2` as the host.

## Demo accounts

Seeded by `demo/seed.sql` — **demo only, never use these credentials elsewhere**:

| Phone | Name | Password |
|---|---|---|
| `+84900000001` | An | `password123` |
| `+84900000002` | Binh | `password123` |
| `+84900000003` | Chi | `password123` |
| `+84900000004` | Dung | `password123` |

The seed creates a DIRECT chat (An↔Binh), the GROUP "Nhom Do An", contacts and
unread badges; it is re-runnable (`ON CONFLICT DO NOTHING`). The demo runs
entirely on LAN — no internet needed; the ~8-minute script and preflight
checklist are in [docs/demo-runbook.md](docs/demo-runbook.md). Note the debug
build alone permits LAN cleartext — the release config blocks it
(`app/src/debug/res/xml/network_security_config.xml`).

## Running tests

```bash
# Server — unit tests plus integration tests against real PostgreSQL
cd server && mvn verify
#   unit loop only (faster, skips *IT):
cd server && mvn test

# Android — JVM unit tests
cd android && ./gradlew testDebugUnitTest
```

Integration tests (`*IT`, failsafe) run against real PostgreSQL — locally the
compose DB on `:5433` via `IT_DB_URL/IT_DB_USER/IT_DB_PASSWORD` env vars, in CI
the workflow's postgres service on `:5432` (`.github/workflows/ci.yml`).
Test counts and pass/fail status are revision-specific; use the current CI run
for release evidence. CI is configured to run the server and Android suites,
Android lint, and a committed-secret scan on every PR and push to
`main`/`develop`.

## Repo layout

```text
nexachat/
├─ android/   Kotlin + Java core modules + Jetpack Compose app
│  ├─ app/src/main/java/com/nexachat/
│  │  ├─ core/       common · database (Room) · network (Retrofit, stomp/) · storage · ui
│  │  ├─ call/       Java call core/media/service/UI
│  │  ├─ data/       repositories (Room write-through, outbox, resync)
│  │  ├─ di/         Hilt modules (call, summary)
│  │  ├─ feature/    auth · chat · contacts · group · inbox · interactions · media · profile · splash
│  │  └─ summary/    Java summary data layer
│  ├─ app/schemas/   Room-exported schema JSON (migration-test assets)
│  └─ gradle/libs.versions.toml   # dependency version catalog
├─ server/    Spring Boot modular monolith (REST /api/v1 + STOMP /ws)
│  ├─ src/main/java/com/nexachat/{auth,user,contact,conversation,message,attachment,presence,call,summary,ws,common,config}/
│  ├─ src/main/resources/db/migration/V1–V6   # Flyway migrations
│  └─ Dockerfile + pom.xml
├─ docs/      contracts, security, runbooks, audits, viva bank (index: docs/README.md)
│  ├─ design/       design tokens + Stitch references + live screenshots
│  ├─ screenshots/  feature evidence
│  └─ evidence/     local interaction captures (gitignored)
├─ demo/      seed.sql + LAN scripts (lan-up.sh / lan-up.ps1, backup.sh)
├─ tools/     build-report.py · sign-release.sh · package-submission.sh · check-markdown-links.py
├─ .github/   workflows/ci.yml · pull_request_template.md · ISSUE_TEMPLATE/
├─ README.md · CONTRIBUTING.md · CHANGELOG.md
└─ docker-compose.yml
```

## Documentation

The canonical, grouped index is [docs/README.md](docs/README.md). Key entry
points: [docs/architecture.md](docs/architecture.md) ·
[docs/api-contract.md](docs/api-contract.md) ·
[docs/websocket-protocol.md](docs/websocket-protocol.md) ·
[docs/security.md](docs/security.md) · [docs/rtm.md](docs/rtm.md) ·
[docs/demo-runbook.md](docs/demo-runbook.md) ·
[docs/viva-questions.md](docs/viva-questions.md) ·
[docs/known-limitations.md](docs/known-limitations.md) ·
[CONTRIBUTING.md](CONTRIBUTING.md) · [CHANGELOG.md](CHANGELOG.md).

## Team & AI declaration / Tuyên bố sử dụng AI

AI tools were used to assist design brainstorming, boilerplate generation, and
test-case suggestions. **Every merged line — AI-assisted or not — has been
reviewed and can be explained by its owner** (the "explain-back" rule: no code
is merged unless the merging member can explain it under defense questioning).

| Member | Role | AI-assisted areas | Owner confirms explain-back |
|---|---|---|---|
| A — _name_ | Backend Lead (auth, realtime) | _fill in_ | ☐ |
| B — _name_ | Backend + DevOps (domain, infra) | _fill in_ | ☐ |
| C — _name_ | Android Lead (chat core) | _fill in_ | ☐ |
| D — _name_ | Android + QA/Docs (UX, quality) | _fill in_ | ☐ |

Each PR also declares AI involvement in the description (see
`.github/pull_request_template.md`).

## Contributing

Conventional commits (`feat(server/auth): …`, `fix(app/chat): …`), PR per
`feature/<scope>` branch into `develop`, ≥1 cross-review before squash merge.
Never commit secrets, keystores, `.env`, or `local.properties` — `.gitignore`
and the CI secret scan enforce this.

## License & status

Course project (đồ án) built for education — no production license is
asserted. It is a single-node demo system by design; the named trade-offs
(single-instance broker, LAN cleartext debug path, no E2EE/OTP/FCM) are
documented in [docs/known-limitations.md](docs/known-limitations.md).
