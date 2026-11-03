# NexaChat

**NexaChat** is a real-time Android messaging platform: register by phone number,
find and connect with other users, chat 1-1 and in groups over WebSocket/STOMP,
send media/files, in a bilingual (Vi/En) Material 3 UI.

> Đồ án nhóm · Kotlin + Jetpack Compose (client) · Java + Spring Boot (backend) ·
> PostgreSQL · WebSocket/STOMP.

## Repo layout

```text
nexachat/
├─ android/   Kotlin + Jetpack Compose app (Hilt, Room, OkHttp, STOMP client)
├─ server/    Spring Boot modular monolith (REST /api/v1 + STOMP /ws, Flyway)
├─ docs/      Architecture, RTM, packaging checklist, demo runbook, report
└─ demo/      Seed data + LAN demo scripts
```

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 (Temurin) | `java -version` |
| Maven | 3.9+ | or use `./mvnw` in `server/` |
| Android SDK | minSdk 26 · targetSdk 34 | via Android Studio |
| Docker | recent | PostgreSQL via `docker compose` |

## Quickstart — server

```bash
# 1. Start PostgreSQL 16 (first terminal)
docker compose up -d db

# 2. Run the backend (second terminal)
cd server
./mvnw spring-boot:run        # or: mvn spring-boot:run
```

Health check: `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`.
REST base: `http://localhost:8080/api/v1` · WebSocket: `ws://localhost:8080/ws`.

## Quickstart — android

1. Open the `android/` folder in Android Studio (Hedgehog+).
2. Ensure `android/local.properties` points at your SDK
   (`sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk` — auto-generated,
   never committed).
3. For a physical device on LAN, set the backend base URL to your laptop's LAN
   IP; for USB, `adb reverse tcp:8080 tcp:8080` and use `localhost`.
4. Run `app` on an emulator or device.

## Running tests

```bash
# Backend unit + integration tests
cd server && mvn test

# Android unit tests
cd android && ./gradlew testDebugUnitTest
```

CI (`/.github/workflows/ci.yml`) runs both plus a secret scan on every PR and
on pushes to `main`/`develop` — the suite must stay green to merge.

## Demo notes

- Demo runs on **LAN** — no internet needed. See `docs/demo-runbook.md` for the
  preflight checklist and the ~8-minute script.
- Seeded accounts (**demo only — never use elsewhere**):

  | Phone | Name | Password |
  |---|---|---|
  | `+84900000001` | Minh | `demo@1234` |
  | `+84900000002` | Lan | `demo@1234` |
  | `+84900000003` | Huy | `demo@1234` |

  Apply with: `psql -h localhost -U nexachat -d nexachat -f demo/seed.sql`

## AI Usage Declaration / Tuyên bố sử dụng AI

AI tools were used to assist design brainstorming, boilerplate generation, and
test-case suggestions. **Every merged line — AI-assisted or not — has been
reviewed and can be explained by its owner** (the "explain-back" rule: no code
is merged unless the merging member can explain it under defense questioning).

| Member | Role | AI-assisted areas | Owner confirms explain-back |
|---|---|---|---|
| A | Backend Lead (auth, realtime) | _fill in_ | ☐ |
| B | Backend + DevOps (domain, infra) | _fill in_ | ☐ |
| C | Android Lead (chat core) | _fill in_ | ☐ |
| D | Android + QA/Docs (UX, quality) | _fill in_ | ☐ |

Each PR also declares AI involvement in the description (see
`.github/pull_request_template.md`).

## Architecture

NexaChat is a **modular monolith**: the Android client (Compose + MVVM/UDF +
offline-first Room) talks REST `/api/v1` for request/response flows and a
STOMP-over-WebSocket channel for realtime push; the Spring Boot backend is split
into `auth`, `user`/`contact`, `conversation`, `message`, `attachment`, and
`common` modules behind Spring Security JWT + rate limiting, persisting to
PostgreSQL 16 (Flyway migrations) and a `StorageService`-abstracted file volume.
Send path is persist-before-broadcast with `clientMessageId` idempotency; unread
state uses a per-member `last_read_seq` watermark. Diagrams and rationale:
[docs/architecture.md](docs/architecture.md) · requirements traceability:
[docs/rtm.md](docs/rtm.md).

## Contributing

Conventional commits (`feat(server/auth): …`, `fix(app/chat): …`), PR per
`feature/<scope>` branch into `develop`, ≥1 cross-review before squash merge.
Never commit secrets, keystores, `.env`, or `local.properties` — `.gitignore`
and the CI secret scan enforce this.
