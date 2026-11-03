# Demo Runbook

> LAN demo for NexaChat (from master design §19). Run the preflight ~30 minutes
> before showtime; the script is paced to ~8 minutes and mapped to rubric rows.
> Deep feature evidence and viva Q&A live in `docs/interactions-demo.md`.

## 0. Prerequisites

| Need | Check |
|---|---|
| Docker Desktop running | `docker version` shows Server section |
| JDK 17 + Maven (only for non-Docker server runs) | `mvn -v` |
| Android SDK / Android Studio + `adb` on PATH | `adb version` |
| Demo APK built (release-demo or debug) | `android/` → Gradle `installDebug` / `assembleRelease` |
| Laptop + phones on the **same Wi-Fi** (or hotspot, §3) | — |
| Fresh clone works: no `.env` needed — compose has dev defaults | — |

Optional: `JWT_SECRET` env var for a non-default signing secret
(`export JWT_SECRET=...` before `docker compose up`; the app container reads it
via passthrough — see `docker-compose.yml`).

## 1. Fresh clone → chatting in ≤ 10 minutes

| # | Step | Command / action | Verify |
|---|---|---|---|
| 1 | Start DB + backend | `docker compose up -d db app` (first run builds the image, ~3–5 min: `docker compose build app`) | `docker compose ps` → `db` healthy, `app` healthy |
| 2 | Health check | `curl http://localhost:8080/actuator/health` | `{"status":"UP"}` — this endpoint is `permitAll` |
| 3 | Seed demo data | `docker exec -i $(docker compose ps -q db) psql -U nexachat -d nexachat < demo/seed.sql` | completes silently (re-runnable) |
| 4 | Install APK on 2 devices | `adb -s <device> install app-release.apk` or Gradle install | app icon on both |
| 5 | Point app at backend | see §2 "Pointing the app at the backend" | login screen loads |
| 6 | Log in | Phone A: `+84900000001` / `password123`; Phone B: `+84900000002` / `password123` | inbox shows seeded chats |
| 7 | Send a message A → B | type + send | B receives in realtime (WS); reply, reaction, pin work |

**One-shot version:** `./demo/lan-up.sh` (Git Bash; PowerShell fallback:
`powershell -ExecutionPolicy Bypass -File .\demo\lan-up.ps1`) does steps 1–3,
detects the LAN IP, and prints the URLs + accounts.

**Backup before showtime:** `./demo/backup.sh` dumps Postgres to
`demo/backups/nexachat-<timestamp>.sql` (gitignored). Restore:
`docker exec -i $(docker compose ps -q db) psql -U nexachat -d nexachat < demo/backups/<file>.sql`.

## 2. Pointing the app at the backend

The Android client's base URL is a `buildConfigField` resolved at build
time (`app/build.gradle.kts` → `BuildConfig.API_BASE_URL` →
`core/common/AppConfig.kt`). Override it **without touching source** —
put one line in `android/local.properties` and rebuild:

```properties
API_BASE_URL=http://<LAN-IP>:8080
```

(or `./gradlew -PapiBaseUrl=http://<LAN-IP>:8080 assembleDebug`).

REST, WebSocket (`ws(s)://…/ws` derived from the scheme), and file URLs all
derive from this constant:

| Target | `API_BASE_URL` | Cleartext allowed? |
|---|---|---|
| Emulator → host dev server | `http://10.0.2.2:8080` (default, nothing to set) | ✅ |
| Physical phone → laptop on LAN | `http://<LAN-IP>:8080` in `local.properties` | ✅ debug builds allow cleartext |
| Physical phone → `adb reverse` | `http://127.0.0.1:8080` in `local.properties` | ✅ debug builds allow cleartext |

**Cleartext:** debug builds ship `app/src/debug/res/xml/network_security_config.xml`
with `cleartextTrafficPermitted="true"` — any LAN IP just works. Release
builds fall back to the restrictive main config (`10.0.2.2` only); production
must be WSS/HTTPS anyway. So the LAN demo needs **only** the
`local.properties` line + rebuild — no XML edits, nothing to revert.

## 3. LAN setup

1. Laptop + both phones on the **same Wi-Fi** (no client isolation — many
   campus/corp networks block peer-to-peer; use the hotspot fallback if unsure).
2. `./demo/lan-up.sh` prints the LAN IP (or `ipconfig` → "IPv4 Address" on the
   Wi-Fi adapter).
3. **Windows firewall:** allow inbound TCP 8080 the first time Docker Desktop
   asks, or pre-create the rule:
   `netsh advfirewall firewall add rule name="NexaChat 8080" dir=in action=allow protocol=TCP localport=8080`
4. Sanity check from a phone browser: `http://<LAN-IP>:8080/actuator/health`
   should return `{"status":"UP"}` — this proves LAN reachability before you
   touch the app.

### Fallbacks

| Situation | Fallback |
|---|---|
| Wi-Fi blocks peer traffic | **Phone hotspot:** run the hotspot on one demo phone, join laptop + other phone to it; LAN IP becomes the `192.168.43.x`-style address — re-run `lan-up.sh` |
| No usable Wi-Fi at all | **`adb reverse` over USB:** `adb reverse tcp:8080 tcp:8080`, set `API_BASE_URL=http://127.0.0.1:8080` in `local.properties`, rebuild |
| Nothing physical works | Emulator on the same laptop — default `10.0.2.2` config works with zero changes |

## 4. Preflight checklist (§19.1 — chạy 30' trước giờ)

- [ ] `docker compose up -d db app` on the demo laptop; `docker compose ps`
      shows both **healthy**; LAN IP noted.
- [ ] `./demo/backup.sh` snapshot taken (safety net for RK-11).
- [ ] Seed applied: `+84900000001/An`, `+84900000002/Binh`,
      `+84900000003/Chi`, `+84900000004/Dung` — password `password123`.
- [ ] 2 physical devices + 1 emulator with APK installed, `API_BASE_URL` set
      per §2, all pre-logged in; `adb reverse` ready if LAN is flaky; backup
      hotspot on.
- [ ] Projector setup: screen 1 = phone A, screen 2 = phone B (scrcpy).
- [ ] Pre-recorded fallback demo video ready (RK-3); server log tail open:
      `docker compose logs -f app`.

## 5. Demo script ~8 minutes (§19.2 — map rubric)

| # | Bước | Thời lượng | Rubric |
|---|---|---|---|
| 1 | Mở app: splash → session restore vào thẳng inbox (nói: token refresh ngầm) | 30s | R2, R6 |
| 2 | Lan tìm Minh bằng SĐT → thêm contact → tạo direct chat | 60s | R2 (search, add phone) |
| 3 | Chat 1-1 realtime hai chiều; **airplane mode** máy B (wifi-off alone keeps cellular), gửi tin → bubble pending (spinner) → bật mạng lại → ~15s WS tự reconnect + outbox flush → tin tới SENT (offline + idempotency, không cần navigate). Socket chết lặng trong background cũng tự hồi khi app foreground — reconnect loop park chứ không exit | 90s | R2, demo kỹ thuật |
| 4 | Read receipt "Đã xem"; unread badge inbox | 30s | R2 |
| 5 | Tạo nhóm 3 người, chat nhóm, thêm/xóa member (máy bị xóa không nhận tin) | 90s | R2 (group + authZ) |
| 6 | Gửi ảnh (Photo Picker), video (ExoPlayer), PDF (mở app ngoài) + progress upload | 60s | R3 media |
| 7 | Creative: reply một tin, react 👍, ghim tin trong nhóm — see `docs/interactions-demo.md` | 45s | R4 |
| 8 | Đổi ngôn ngữ En→Vi trong Settings; xoay màn hình giữa khung chat (draft/history còn) | 45s | R3 i18n+rotation |
| 9 | Chạy `./gradlew test` + `server test` trên CI → toàn xanh | 60s | R5 |
| 10 | Show kiến trúc 1 slide + ERD + luồng tin nhắn trên `docker compose logs -f app` | 60s | R6 |

## 6. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `docker compose up -d db` fails: port already in use | Local Postgres or old container holds 5433 | Edit compose `db.ports` host side, e.g. `"15433:5432"` (app is unaffected — it reaches `db:5432` internally); update `DATABASE_URL`/IT envs accordingly |
| `app` container unhealthy | DB not healthy yet, or bad `JWT_SECRET` env | `docker compose logs app`; Flyway runs on boot — first start takes ~30–60 s |
| Phone browser can't open `…:8080/actuator/health` | Firewall or different subnet | Windows firewall rule (§3.3); confirm same Wi-Fi; try hotspot fallback |
| WS won't connect (REST works) | Wrong IP in `API_BASE_URL`, stale token, or proxy stripping `Upgrade` | Re-check `AppConfig.kt`; re-login to refresh JWT; `docker compose logs app` shows STOMP handshake |
| Cleartext HTTP blocked on physical phone | A release (non-debug) build — release keeps the restrictive `10.0.2.2`-only config | Rebuild `assembleDebug` — debug builds permit cleartext to any host (§2) |
| Emulator can't reach backend | — | Default `10.0.2.2` already maps to host `localhost`; verify `docker compose ps` and host `curl localhost:8080/actuator/health` |
| Seeded users "wrong password" | Rows from a pre-`password123` seed still present (an old draft used `demo@1234`) | `DELETE FROM users WHERE phone LIKE '+8490000%';` then re-apply `demo/seed.sql` |
| Messages don't sync after DB rebuild | Stale server-side clock/volume | `docker compose down -v && docker compose up -d db app` then re-seed (< 2 min total) |

## 7. Fallbacks during the show (risk register RK-3, RK-11, RK-12)

| Failure | Immediate fallback |
|---|---|
| LAN flaky / device can't reach backend (RK-3) | `adb reverse tcp:8080 tcp:8080` over USB; switch to backup hotspot; last resort: emulator + device on same laptop |
| Realtime demo dies mid-show | Play the pre-recorded demo video, continue live for the parts that still work |
| PostgreSQL/docker breaks right before demo (RK-11) | `docker compose down -v` + `up -d db app` + `demo/seed.sql` = rebuild < 2 min; restore latest `demo/backups/` dump instead of re-seeding |
| Demo device dies / low battery (RK-12) | 3 spare devices + emulator, all charged; screenshots + recorded video cover remaining steps |
| WS send fails | REST send path (`POST /conversations/{id}/messages`) is the designed fallback — demo keeps working, mention it as a feature |

**Rule:** never improvise a new flow live — degraded demo with narration beats a
frozen screen. Rehearse the script ≥3 times end-to-end before defense day.
