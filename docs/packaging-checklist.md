# Packaging & Acceptance Checklist

> Rubric acceptance checklist (master design §20.1) — tick every box before
> submission. The packaging checklist below (adapted from §20.1-C1, §15–P12,
> and phase-14 tasks — plan §20 has no separate §20.3) is what C1 verifies on a
> clean machine.

## C1–C12 rubric acceptance table (§20.1 — tick before submitting)

| # | Hạng mục | Bằng chứng yêu cầu | ☐ |
|---|---|---|---|
| C1 | Bài nộp (R1) | Zip `GroupXX_MSSV1…zip`; có `.git/`; **không** có `build/`, `node_modules/`, keystore, `keystore.properties`; APK; `.docx`; thư mục screenshots; README khai báo AI | ☐ |
| C2 | Chat trực tiếp realtime + history + unread + read receipt | TC-C1..C7 pass + demo script bước 3–4 | ☐ |
| C3 | Chat nhóm + member mgmt + authZ | TC-GRP pass + demo bước 5 | ☐ |
| C4 | Tìm kiếm + thêm bằng SĐT (E.164) | TC-CONTACT pass + demo bước 2 | ☐ |
| C5 | Quản lý tài khoản (register/login/logout/profile/đổi mk) | TC-AUTH pass + demo bước 1 | ☐ |
| C6 | Đa ngôn ngữ Vi/En chuyển trong app | TC-I18N + demo bước 8 | ☐ |
| C7 | Multimedia & file (ảnh/video/audio/file + progress + open) | TC-FILE + demo bước 6 | ☐ |
| C8 | Xoay màn hình không mất state | TC-UI-ROT + demo bước 8 | ☐ |
| C9 | Creative: reply/reaction/pin | TC-INT + demo bước 7 | ☐ |
| C10 | UI rõ ràng (M3, mọi state) + toàn bộ unit test pass | CI run cuối xanh 100% + screenshots | ☐ |
| C11 | Thuyết trình/demo/vấn đáp | Diễn tập ≥2 lần; mỗi member giải thích slice mình; Q&A bank học thuộc | ☐ |
| C12 | Đóng góp thực chất | `git shortlog -sn` cân đối; mỗi member ≥N PR merged | ☐ |

## Submission packaging checklist

Run on a **clean machine** (fresh clone from the zip) before archiving:

- [ ] Zip named `GroupXX_MSSV1_MSSV2_….zip` per course convention.
- [ ] Zip **contains** `.git/` (full history — contribution evidence).
- [ ] Zip contains `README.md` with the completed AI Usage Declaration,
      `docs/report.docx`, `docs/screenshots/` (all features, both languages),
      `demo/`, and the release-signed APK.
- [ ] Zip does **not** contain: `build/`, `**/build/`, `target/`,
      `node_modules/`, `.gradle/`, `*.keystore`, `*.jks`,
      `keystore.properties`, `.env*`, `local.properties`,
      `google-services.json`, `server/uploads/`, `data/`, `*.log`.
      Verify: `git ls-files | grep -Ei 'keystore|\.env|local\.properties'
      | grep -vE '\.example$'` returns nothing (the two tracked `*.example`
      templates are expected).
- [ ] APK `release` is signed with the LOCAL keystore (keystore itself is
      never committed/submitted); tag `v1.0.0` exists.
- [ ] Fresh-clone verification: `docker compose up -d db` → `mvn -B verify`
      green → `demo/seed.sql` loads → 2 devices log in and chat over LAN.
- [ ] CI on the final commit is all green (server-test, android-test,
      secret-scan).
- [ ] `git shortlog -sn` shows balanced contributions across members
      (coefficient evidence).

## Fresh-clone verification (clean machine — exact steps)

```bash
# 1. Unzip GroupXX_MSSV….zip → confirm .git/ exists at repo root
git log --oneline -5

# 2. Backend up (Docker) + health
docker compose up -d db app        # first build ~3–5 min
curl http://localhost:8080/actuator/health   # {"status":"UP"}

# 3. Seed demo data
docker exec -i $(docker compose ps -q db) psql -U nexachat -d nexachat < demo/seed.sql

# 4. Full test gates
cd server && mvn -B verify                          # final XML run: 310 tests (280 unit + 30 IT)
cd ../android && ./gradlew testDebugUnitTest        # final XML run: 202 tests (20 classes)
```

## APK install & demo accounts

- [ ] **Launch gate (mandatory before tagging a release):** install the
      *signed* APK and confirm it reaches the login screen without a crash:
      `adb install release/nexachat-*.apk &&
       adb shell am start -n com.nexachat/com.nexachat.MainActivity &&
       sleep 5 && adb shell pidof com.nexachat` must print a PID and logcat
      must show no `FATAL EXCEPTION`. Unit/IT tests cannot see DI-init or
      theme-window defects — this gate exists because one launch crash
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

## Docs inventory (must be inside the zip)

- [ ] `README.md` — overview, quickstart, completed AI declaration.
- [ ] `docs/report.docx` + `docs/report.md` — final report (generated by
      `tools/build-report.py`).
- [ ] `docs/architecture.md` — Mermaid diagrams (container, modules, send
      sequence).
- [ ] `docs/api-contract.md` — REST endpoint catalog.
- [ ] `docs/websocket-protocol.md` — STOMP frame/event contract.
- [ ] `docs/security.md` + `docs/security-checklist.md` — threat model.
- [ ] `docs/rtm.md` — requirement traceability matrix.
- [ ] `docs/demo-runbook.md` — preflight + 8-minute script + fallbacks.
- [ ] `docs/known-limitations.md` — honest limitations.
- [ ] `docs/i18n-audit.md` + `docs/rotation-audit.md` — Vi/En parity (224/224
      entries, 222 translatable) and per-screen rotation evidence.
- [ ] `docs/interactions-demo.md` — creative feature playbook.
- [ ] `docs/screenshots/` (22 feature shots) + `docs/design/screens/`
      (21 design-system shots) + `docs/design/design-notes.md`.
- [ ] `demo/` — `seed.sql`, `lan-up.sh`, `lan-up.ps1`, `backup.sh`.
- [ ] `.github/workflows/ci.yml` + `.github/pull_request_template.md`.
- [ ] `tools/build-report.py` — report generator.

## Test commands (for the grader)

| Suite | Command | Expected |
|---|---|---|
| Server unit + integration | `docker compose up -d db` then `cd server && mvn -B verify` | final XML run: 310 tests green (280 unit + 30 IT on real Postgres via env-JDBC; local default `localhost:5433`) |
| Android unit | `cd android && ./gradlew testDebugUnitTest` | final XML run: 202 green (20 classes) |
| CI equivalent | `.github/workflows/ci.yml` | server-test (postgres svc `:5432` + `IT_DB_*` env), android-test, secret-scan |

## Remaining submission TODO (team inputs and final packaging)

- [x] **Screenshots** — 22 feature PNGs captured across Vietnamese and English,
      including login/register, inbox, direct/group chat, contacts/search,
      media, interactions, profile, and language settings. Inventory:
      `docs/screenshots-manifest.md`. Plus 21 live design-system screenshots
      (EN/VI + dark) in `docs/design/screens/`.
- [ ] **Member names / MSSV** — replace `{{MEMBER_*}}`, `{{MSSV_*}}`,
      `{{GROUP_NAME}}`, `{{COURSE_NAME}}`, `{{INSTRUCTOR}}`, `{{SEMESTER}}`,
      `{{DATE}}`, `{{PCT_*}}`, `{{AI_AREA_*}}` placeholders in
      `docs/report.md`, then re-run `python tools/build-report.py` to
      regenerate `docs/report.docx`.
- [ ] **Demo video (fallback, RK-3)** — record the 8-minute runbook script
      end-to-end; keep on the demo laptop.
- [ ] **Rehearsal log** — run the demo script ≥3 times (C11); note pass/fail
      per step.
- [x] **Release APK** — rebuilt from the current working-tree source with
      `tools/sign-release.sh`; `apksigner verify` passed, install and launch
      succeeded on both API 35 emulators, and the >900-second expiry/reconnect
      flow delivered a live chat message and Seen receipt on the signed release.
      Keep the local keystore OUT of the zip.
- [ ] **Zip** — name `GroupXX_MSSV1_MSSV2_….zip`; re-check the "does not
      contain" list above on the final artifact.
