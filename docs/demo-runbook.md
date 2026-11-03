# Demo Runbook

> LAN demo for NexaChat (from master design §19). Run the preflight ~30 minutes
> before showtime; the script is paced to ~8 minutes and mapped to rubric rows.

## 1. Preflight checklist (§19.1 — chạy 30' trước giờ)

- [ ] `docker compose up -d` backend+DB on the demo laptop; `docker compose ps`
      healthy; note the LAN IP.
- [ ] Seed script applied: accounts `+84900000001/Minh`, `+84900000002/Lan`,
      `+84900000003/Huy` (standard password `demo@1234`), plus one empty group
      ready.
- [ ] 2 physical devices + 1 emulator with `release-demo` APK installed, all
      pre-logged into the 3 accounts; `adb reverse` ready if LAN is flaky;
      backup hotspot on.
- [ ] Projector setup: screen 1 = phone A, screen 2 = phone B (scrcpy).
- [ ] Pre-recorded fallback demo video ready (RK-3); server log tail open in a
      terminal (`tail -f`).

## 2. Demo script ~8 minutes (§19.2 — map rubric)

| # | Bước | Thời lượng | Rubric |
|---|---|---|---|
| 1 | Mở app: splash → session restore vào thẳng inbox (nói: token refresh ngầm) | 30s | R2, R6 |
| 2 | Lan tìm Minh bằng SĐT → thêm contact → tạo direct chat | 60s | R2 (search, add phone) |
| 3 | Chat 1-1 realtime hai chiều; tắt wifi máy B, gửi tin → tin xám → bật lại → tin tới (offline + idempotency) | 90s | R2, demo kỹ thuật |
| 4 | Read receipt "Đã xem"; unread badge inbox | 30s | R2 |
| 5 | Tạo nhóm 3 người, chat nhóm, thêm/xóa member (máy bị xóa không nhận tin) | 90s | R2 (group + authZ) |
| 6 | Gửi ảnh (Photo Picker), video (ExoPlayer), PDF (mở app ngoài) + progress upload | 60s | R3 media |
| 7 | Creative: reply một tin, react 👍, ghim tin trong nhóm | 45s | R4 |
| 8 | Đổi ngôn ngữ En→Vi trong Settings; xoay màn hình giữa khung chat (draft/history còn) | 45s | R3 i18n+rotation |
| 9 | Chạy `./gradlew test` + `server test` trên CI → toàn xanh | 60s | R5 |
| 10 | Show kiến trúc 1 slide + ERD + luồng tin nhắn trên log server | 60s | R6 |

## 3. Fallbacks (from risk register RK-3, RK-11, RK-12)

| Failure | Immediate fallback |
|---|---|
| LAN flaky / device can't reach backend (RK-3) | `adb reverse tcp:8080 tcp:8080` over USB; switch to backup hotspot; last resort: emulator + device on same laptop |
| Realtime demo dies mid-show | Play the pre-recorded demo video, continue live for the parts that still work |
| PostgreSQL/docker breaks right before demo (RK-11) | `docker compose down -v` + up + migrate + `demo/seed.sql` = rebuild < 2 min; keep a DB dump backup; H2-file fallback profile available |
| Demo device dies / low battery (RK-12) | 3 spare devices + emulator, all charged; screenshots + recorded video cover remaining steps |
| WS send fails | REST send path (`POST /conversations/{id}/messages`) is the designed fallback — demo keeps working, mention it as a feature |

**Rule:** never improvise a new flow live — degraded demo with narration beats a
frozen screen. Rehearse the script ≥3 times end-to-end before defense day.
