# Release & Packaging Checklist

> Pre-release acceptance and clean-machine verification checklist for NexaChat.
> Tick every verification gate before cutting a release archive.

## Release capability acceptance table

| # | Capability / Gate | Required Verification Evidence | ☐ |
|---|---|---|---|
| C1 | Release archive & clean tree | Archive contains `.git/`, `README.md`, `docs/`, `demo/`, signed release APK; **excludes** `build/`, `target/`, `.gradle/`, keystores, `.env*`, `local.properties` | ☐ |
| C2 | Direct chat realtime + history + unread + read receipts | `MessageFlowIT`, `ChatViewModelTest` pass + demo runbook steps 3–4 | ☐ |
| C3 | Group chat + member management + authorization | `GroupFlowIT`, `GroupServiceTest` pass + demo runbook step 5 | ☐ |
| C4 | Phone discovery & contacts (E.164) | `ContactConversationIT`, `ContactServiceTest` pass + demo runbook step 2 | ☐ |
| C5 | Account & auth lifecycle (register/login/logout/password) | `AuthFlowIT`, `AuthServiceTest`, `TokenServiceTest` pass + demo runbook step 1 | ☐ |
| C6 | Bilingual UI (Vi/En) in-app switching | `StringParityTest`, `AppLocaleManagerTest` pass + demo runbook step 8 | ☐ |
| C7 | Multimedia & file sharing (upload/sniffing/download/viewer) | `AttachmentFlowIT`, `MagicBytesTest` pass + demo runbook step 6 | ☐ |
| C8 | Configuration-change & state survival | `ChatViewModelTest`, `docs/rotation-audit.md` + demo runbook step 8 | ☐ |
| C9 | Rich message interactions (reply/reaction/pin/forward) | `InteractionFlowIT`, `ForwardFilterTest` pass + demo runbook step 7 | ☐ |
| C10 | Stitch Material 3 UI (Dark & Light Mode) + unit/IT suites | All CI gates green + Stitch Dark/Light Mode showcase (`docs/media/README.md`) | ☐ |
| C11 | End-to-end LAN runbook rehearsal | `docs/demo-runbook.md` verified end-to-end across 2 devices/emulators | ☐ |
| C12 | Repository hygiene & commit traceability | Clean Conventional Commit history; secret scan passes | ☐ |

## Release archive checklist

Run on a **clean machine** (fresh clone) before cutting a release archive:

- [ ] Archive **contains** `.git/` (full commit history).
- [ ] Archive contains `README.md`, `docs/report.docx`, `docs/media/screens/`,
      `docs/screenshots/`, `demo/`, and the release-signed APK.
- [ ] Archive does **not** contain: `build/`, `**/build/`, `target/`,
      `node_modules/`, `.gradle/`, `*.keystore`, `*.jks`,
      `keystore.properties`, `.env*`, `local.properties`,
      `google-services.json`, `server/uploads/`, `data/`, `*.log`.
      Verify: `git ls-files | grep -Ei 'keystore|\.env|local\.properties'
      | grep -vE '\.example$'` returns nothing (the two tracked `*.example`
      templates are expected).
- [ ] APK `release` is signed with the local release keystore (keystore itself
      is never committed or packaged); release tag `v1.0.0` exists.
- [ ] Fresh-clone verification: `docker compose up -d db app` → `mvn -B verify`
      green → `demo/seed.sql` loads → 2 devices log in and chat over LAN
      (Android client: Java 21 LTS, Fragments, ViewBinding, Room RxJava 3,
      RxJava 3, Glide; unit tests JUnit 4 + Mockito).
- [ ] CI on the release commit is all green (`server-test`, `android-test`,
      `secret-scan`).

## Fresh-clone verification (clean machine — exact steps)

```bash
# 1. Extract archive → confirm .git/ exists at repo root
git log --oneline -5

# 2. Backend up (Docker) + health
docker compose up -d db app        # first build ~3–5 min
curl http://localhost:8080/actuator/health   # {"status":"UP"}

# 3. Seed demo data
docker exec -i $(docker compose ps -q db) psql -U nexachat -d nexachat < demo/seed.sql

# 4. Full test gates (JDK 21 LTS)
cd server && mvn -B verify                          # Surefire unit + Failsafe *IT against PostgreSQL (0 failures)
cd ../android && ./gradlew testDebugUnitTest        # JUnit 4 + Mockito + RxJava 3 JVM unit suite (0 failures)
```

## APK install & demo accounts

- [ ] **Launch gate (mandatory before tagging a release):** install the
      *signed* APK and confirm it reaches the login screen without a crash:
      `adb install release/nexachat-*.apk &&
       adb shell am start -n com.nexachat/com.nexachat.MainActivity &&
       sleep 5 && adb shell pidof com.nexachat` must print a PID and logcat
      must show no `FATAL EXCEPTION`. Unit/IT tests cannot see Hilt wiring,
      Fragment/ViewBinding inflation, or theme-window defects — this gate exists
      because one launch crash
      (`unexpected scheme: ws`) shipped past 418 green tests.
- [ ] Install on 2+ devices: `adb -s <device> install
      app/build/outputs/apk/debug/app-debug.apk` (debug variant — required for
      LAN cleartext; the signed release APK is `release/nexachat-v1.0.0.apk`).
- [ ] Point the app at the backend **without source edits**: add
      `API_BASE_URL=http://<LAN-IP>:8080` to `android/local.properties` and
      rebuild (`./gradlew -PapiBaseUrl=http://<LAN-IP>:8080 assembleDebug`
      also works). Emulator default `http://10.0.2.2:8080` needs nothing.
      USB fallback: `adb reverse tcp:8080 tcp:8080` + `127.0.0.1`.
- [ ] Log in with seeded accounts (apply `demo/seed.sql` first; **demo only**):

  | Phone | Name | Password |
  |---|---|---|
  | `+84900000001` | An | `password123` |
  | `+84900000002` | Binh | `password123` |
  | `+84900000003` | Chi | `password123` |
  | `+84900000004` | Dung | `password123` |

## Docs inventory (must be inside the release archive)

- [ ] `README.md` — architecture overview, Stitch UI showcase, quickstart.
- [ ] `docs/report.docx` + `docs/report.md` — engineering report (generated by
      `tools/build-report.py`).
- [ ] `docs/architecture.md` — Mermaid diagrams (container, modules, send
      sequence).
- [ ] `docs/api-contract.md` — REST endpoint catalog.
- [ ] `docs/websocket-protocol.md` — STOMP frame/event contract.
- [ ] `docs/security.md` + `docs/security-checklist.md` — threat model.
- [ ] `docs/rtm.md` — requirement traceability matrix.
- [ ] `docs/demo-runbook.md` — preflight + 8-minute script + fallbacks.
- [ ] `docs/known-limitations.md` — architectural boundaries & trade-offs.
- [ ] `docs/i18n-audit.md` + `docs/rotation-audit.md` — Vi/En parity (221/221
      entries, 219 translatable) and per-screen rotation evidence.
- [ ] `docs/interactions-demo.md` — message interactions playbook.
- [ ] `docs/media/README.md` + `docs/media/screens/` (Stitch Dark & Light Mode
      showcase + core feature frames), `docs/screenshots/`,
      `docs/design/screens/`, and `docs/design/design-notes.md`.
- [ ] `demo/` — `seed.sql`, `lan-up.sh`, `lan-up.ps1`, `backup.sh`.
- [ ] `.github/workflows/ci.yml` + `.github/pull_request_template.md`.
- [ ] `tools/build-report.py` — report generator.

## Test commands

| Suite | Command | Expected |
|---|---|---|
| Server unit + integration | `docker compose up -d db` then `cd server && mvn -B verify` | Surefire unit + Failsafe `*IT` suites green on real PostgreSQL (`server/target/surefire-reports`, `server/target/failsafe-reports`; local default `localhost:5433`) |
| Android unit | `cd android && ./gradlew testDebugUnitTest` | JVM unit suite green (`android/app/build/reports/tests/testDebugUnitTest/`); JUnit 4 + Mockito + RxJava `TestObserver`/`TestSubscriber` |
| CI equivalent | `.github/workflows/ci.yml` | `server-test` (postgres svc `:5432` + `IT_DB_*` env), `android-test`, `secret-scan` |

## Release readiness status

- [x] **Screenshots** — Stitch Dark (`#0B0E17`) & Light (`#F8FAFC`) Mode
      showcase and core workflow PNGs in `docs/media/screens/` (indexed in
      `docs/media/README.md`), feature captures in `docs/screenshots/`
      (`docs/screenshots-manifest.md`), and design-system captures in
      `docs/design/screens/`.
- [x] **Release APK** — built from the Java 21 LTS source tree with
      `tools/sign-release.sh`; `apksigner verify` passed, install and launch
      succeeded on both API 35 emulators, and the >900-second expiry/reconnect
      flow delivered a live chat message and Seen receipt on the signed release.
      Keep the local keystore out of any distributed archive.
- [ ] **Demo video (optional fallback)** — record the 8-minute runbook script
      end-to-end for offline presentation environments.
- [ ] **Release archive (`tools/package-submission.sh`)** — verify against the
      exclusion list above before distribution.
