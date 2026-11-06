# NexaChat Android — Screenshot Manifest

Inventory of every PNG under `docs/design/screens/`, what it proves, and how the
Stage 2 set was captured on a real runtime. Ground truth is the directory
itself: if a file is listed here it exists; every file in the directory is listed.

Screenshots live in **`docs/design/screens/`** (this directory). The older plan
that referenced `docs/screenshots/` and the deleted `03-inbox-light-en`,
`14-inbox-light-vi`, `16-direct-chat-reply-pinned-light-vi`,
`17-media-picker-light-vi`, `18-reactions-light-vi`, `19-jump-pinned-light-vi`,
`20-direct-chat-dark-vi`, `21-inbox-dark-vi` and `direct-chat-light-en` is
superseded — those files carried probe/e2e strings and were removed; their
replacements are the `s2-*` re-captures below.

## Shared evidence frames

These byte-identical pairs intentionally share an evidence frame because each
frame shows both named states:

- `docs/screenshots/06c-direct-typing-vi.png` and
  `docs/screenshots/12a-reply-quote-vi.png`: the typing indicator and reply quote.
- `docs/design/screens/s2-03-edited-label-light-vi.png` and
  `docs/design/screens/s2-05-tombstone-light-vi.png`: edited labels and deleted-message tombstones.

## 0. Capture environment

- AVD `nexachat-audit` (Android 15, `google_apis/x86_64`, Pixel 6 profile,
  1080×2400), headless boot on port 5558, one emulator only.
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`, `applicationId`
  `com.nexachat.debug`, `API_BASE_URL` default `http://10.0.2.2:8080`
  (no `local.properties` override needed on the emulator).
- Backend: compose stack on the host (`app` on 8080, Postgres on 5433), demo
  data from `tools/seed_demo_chat.py` (`Lan Anh`, `Minh Khoa`, `Bao Chau`,
  direct chat + `Nhóm Nghiên Cứu`).
- Status bar is fixed with demo mode (`sysui_demo_allowed`, clock 9:41, battery
  87 %, notifications hidden); navigation is gesture navigation. Android resets
  demo mode when SystemUI restarts, so the last four captures
  (`s2-36 … s2-39`, font-scale set) show the device clock instead of 9:41 — the
  rest show 9:41. The AVD has no internet-validated network, so a small
  "Wi-Fi off" glyph remains in the status bar — it is device chrome, not app
  content.
- `adb` helpers used for every capture: `tools/e2e/device/adb_ui.py`
  (`uiautomator dump` → tap / long-press / type / screenshot).
  Device locale is switched in-app (Profile → Ngôn ngữ / Language) and the
  theme in Profile → Chủ đề / Theme.

## 1. Inventory — Stage 1 screens (kept)

| File | Screen | Theme / locale |
|---|---|---|
| `01-login-light-en.png` | Login | light / en |
| `01-login-dark-en.png` | Login | dark / en |
| `02-register-light-en.png` | Register | light / en |
| `02-register-dark-en.png` | Register | dark / en |
| `04-group-chat-light-en.png` | Group chat | light / en |
| `05-group-detail-light-en.png` | Group detail | light / en |
| `05-group-detail-light-vi.png` | Group detail | light / vi |
| `06-contacts-light-en.png` | Contacts | light / en |
| `contacts-light-vi.png` | Contacts | light / vi |
| `contacts-dark-vi.png` | Contacts | dark / vi |
| `07-search-light-en.png` | Contact search (empty) | light / en |
| `07-search-light-vi.png` | Contact search (empty) | light / vi |
| `08-search-results-light-en.png` | Contact search results | light / en |
| `08-search-results-light-vi.png` | Contact search results | light / vi |
| `09-contact-detail-light-en.png` | Contact detail sheet | light / en |
| `10-profile-light-en.png` | Profile | light / en |
| `13-profile-light-vi.png` | Profile | light / vi |
| `profile-dark-vi.png` | Profile | dark / vi |
| `11-language-settings-light-en.png` | Language settings | light / en |
| `12-language-settings-light-vi.png` | Language settings | light / vi |
| `language-settings-dark-vi.png` | Language settings | dark / vi |
| `15-create-group-light-vi.png` | Create group | light / vi |
| `group-chat-light-vi.png` | Group chat | light / vi |
| `group-detail-dark-vi.png` | Group detail | dark / vi |
| `22-chat-landscape-light-vi.png` | Chat, landscape | light / vi |

## 2. Inventory — Stage 2 features (`s2-*`, captured on the emulator)

Every screen below was captured on the running app; the functional checks that
back them are in the device verification report `deepseek-device.md`.

| File | Screen / evidence | Theme / locale |
|---|---|---|
| `s2-01-action-sheet-light-vi.png` | Long-press action sheet on an own message: reactions, Reply, Copy, Forward, Pin, Edit, Delete | light / vi |
| `s2-02-edit-mode-light-vi.png` | Edit mode: banner with the original snippet, prefilled composer, cancel | light / vi |
| `s2-03-edited-label-light-vi.png` | "Đã chỉnh sửa" label on edited bubbles | light / vi |
| `s2-04-delete-dialog-light-vi.png` | Delete confirmation dialog ("Xoá tin nhắn?") | light / vi |
| `s2-05-tombstone-light-vi.png` | Tombstone bubbles ("Tin nhắn đã bị xoá") + edited labels | light / vi |
| `s2-06-in-chat-search-light-vi.png` | In-chat search "chao" matching "Chào" with highlight | light / vi |
| `s2-07-forward-picker-light-vi.png` | Forward picker sheet with conversation list and "Gửi" | light / vi |
| `s2-08-presence-chat-light-vi.png` | Direct chat header "Đang hoạt động" + green dot (peer online) | light / vi |
| `s2-09-presence-inbox-light-vi.png` | Inbox online dot on the direct row | light / vi |
| `s2-10-settings-light-vi.png` | Profile: Appearance (theme) + Privacy (activity status) sections | light / vi |
| `s2-11-inbox-light-vi.png` | Inbox with two direct chats, three groups, tombstone preview | light / vi |
| `s2-12-direct-chat-reply-pinned-light-vi.png` | Direct chat: pinned banner, reply quote, reaction chip | light / vi |
| `s2-13-reactions-light-vi.png` | Reaction bar on long-press | light / vi |
| `s2-14-media-picker-light-vi.png` | Media picker sheet ("Ảnh hoặc video" / "Tệp hoặc âm thanh") | light / vi |
| `s2-15-jump-pinned-light-vi.png` | Jump-to-pinned: banner advanced to 2/2, target bubble pulsing | light / vi |
| `s2-16-theme-dialog-light-vi.png` | Theme dialog (system / light / dark) | light / vi |
| `s2-17-settings-dark-vi.png` | Profile in dark theme | dark / vi |
| `s2-21-inbox-dark-vi.png` | Inbox | dark / vi |
| `s2-22-direct-chat-dark-vi.png` | Direct chat with pinned banner and reply quote | dark / vi |
| `s2-23-action-sheet-dark-vi.png` | Action sheet | dark / vi |
| `s2-24-edit-mode-dark-vi.png` | Edit mode | dark / vi |
| `s2-25-delete-dialog-dark-vi.png` | Delete confirmation | dark / vi |
| `s2-26-tombstone-dark-vi.png` | Tombstones + edited labels | dark / vi |
| `s2-27-in-chat-search-dark-vi.png` | In-chat search with highlight | dark / vi |
| `s2-28-forward-picker-dark-vi.png` | Forward picker | dark / vi |
| `s2-29-presence-chat-dark-vi.png` | Presence header + dot | dark / vi |
| `s2-30-theme-dialog-dark-vi.png` | Theme dialog | dark / vi |
| `s2-31-inbox-dark-en.png` | Inbox | dark / en |
| `s2-32-settings-dark-en.png` | Profile settings | dark / en |
| `s2-33-action-sheet-dark-en.png` | Action sheet (Reply / Copy / Forward / Pin / Edit / Delete) | dark / en |
| `s2-34-search-dark-en.png` | In-chat search | dark / en |
| `s2-35-search-landscape-dark-en.png` | Search in landscape (query preserved) | dark / en |
| `s2-36-chat-font130-dark-en.png` | Chat at font scale 1.3 | dark / en |
| `s2-37-sheet-font130-dark-en.png` | Action sheet at font scale 1.3 | dark / en |
| `s2-38-chat-font200-dark-en.png` | Chat at font scale 2.0 | dark / en |
| `s2-39-sheet-font200-dark-en.png` | Action sheet at font scale 2.0 | dark / en |

## 2.1 Inventory — Stage 3 defect fixes & device verification (`s3-*`)

Screenshots captured on `emulator-5558` validating the Round 3 defect resolutions (D1, D3, D4, D5, D6, font scale, Call button):

| File | Screen / evidence | Theme / locale |
|---|---|---|
| `s3-01-settings-dark-vi.png` | Dark theme settings verifying fixed light status-bar icons on dark scrim (D4 fix) | dark / vi |
| `s3-02-sheet-rotated-landscape-dark-vi.png` | Action sheet surviving device rotation to landscape (D5 fix) | dark / vi |
| `s3-03-edit-rotated-portrait-dark-vi.png` | Edit mode banner and composer prefill surviving device rotation without crash (D1 fix) | dark / vi |
| `s3-04-forward-target-navigated-dark-vi.png` | Forward snackbar "Xem" navigation landing in target conversation (D3 fix) | dark / vi |
| `s3-05-search-error-retry-dark-vi.png` | In-chat search error rendering localized error message and "Thử lại" retry button (D6 fix) | dark / vi |
| `s3-06-sheet-font130-dark-vi.png` | Action sheet at font scale 1.3 with full visibility and scrolling | dark / vi |
| `s3-07-sheet-font200-dark-vi.png` | Action sheet at font scale 2.0 with smooth scrolling preserving all actions | dark / vi |
| `s3-08-direct-chat-call-button-dark-vi.png` | Direct chat top bar with 48dp Call button ("Gọi điện") and relative presence subtitle | dark / vi |

## 2.2 Inventory — Phase 6 conversation catch-up summary UI (`s4-*`, captured on emulator-5558)

Screenshots captured on `emulator-5558` validating the Phase 6 conversation summary UI features (unread catch-up banner, overflow menu entry, ModalBottomSheet with scope chips and error/retry views, landscape orientation, AI consent toggle in Settings, first-use consent confirmation dialog, Light and Dark themes in Vietnamese and English):

| File | Screen / evidence | Theme / locale |
|---|---|---|
| `s4-01-unread-banner-dark-vi.png` | Unread catch-up banner ("38 tin chưa đọc · Tóm tắt") and dismiss icon above conversation | dark / vi |
| `s4-02-overflow-menu-dark-vi.png` | Top-bar overflow menu displaying "Tóm tắt cuộc trò chuyện" action in group chat | dark / vi |
| `s4-03-sheet-error-dark-vi.png` | Summary bottom sheet in dark theme: drag handle, "Cục bộ" badge, scope chips (Chưa đọc / 50 tin gần nhất / Hôm nay), typed error view with "Thử lại", and bottom dock (Tạo lại / Sao chép / Chia sẻ) | dark / vi |
| `s4-04-sheet-landscape-dark-vi.png` | Summary bottom sheet surviving device rotation to landscape (rotation-safe `rememberSaveable` state) | dark / vi |
| `s4-05-sheet-scope-today-dark-vi.png` | Summary bottom sheet with "Hôm nay" scope chip selected | dark / vi |
| `s4-06-sheet-light-vi.png` | Summary bottom sheet rendered in light theme with high contrast and Material 3 styling | light / vi |
| `s4-07-settings-consent-light-en.png` | Profile Settings in English: Privacy section showing "Use external AI for summaries" toggle and required consequence copy | light / en |
| `s4-08-sheet-error-light-en.png` | Summary bottom sheet in English: "Conversation summary", "Local" badge, "Unread" / "Last 50" / "Today" filter chips, "Could not generate summary" + "Retry", "Copy", "Share into chat" | light / en |
| `s4-09-consent-dialog-dark-vi.png` | First-use consent confirmation dialog ("Bật tóm tắt bằng AI?") with explanatory warning and "Hủy" / "Đồng ý" buttons | dark / vi |
| `s4-10-settings-consent-dark-vi.png` | Profile Settings in Vietnamese dark theme: "QUYỀN RIÊNG TƯ" with "Dùng AI ngoài để tóm tắt" toggle and consequence copy | dark / vi |
| `s4-11-settings-consent-light-vi.png` | Profile Settings in Vietnamese light theme: AI summary consent switch and consequence copy | light / vi |

## 2.3 Inventory — Live-backend conversation catch-up summary & presence ticker (`s5-*`, captured on emulator-5558)

Screenshots captured on `emulator-5558` validating the live backend summary integration (V5/V6 deployed, `LOCAL` extractive engine and `LLM` external path, real section counts and citations, consent toggle sync, landscape rotation, citation jump with pulsing highlight, composer prefill, and offline peer presence clock ticker advancing relative time):

| File | Screen / evidence | Theme / locale |
|---|---|---|
| `s5-01-unread-banner-dark-vi.png` | Unread catch-up banner ("35 tin chưa đọc · Tóm tắt") and dismiss icon above conversation | dark / vi |
| `s5-02-sheet-too-few-dark-vi.png` | Summary bottom sheet showing typed `TOO_FEW_MESSAGES` error ("Chưa đủ tin nhắn · Cần tối thiểu 5 tin nhắn để tạo tóm tắt.") | dark / vi |
| `s5-03-sheet-ready-dark-vi.png` | Live backend local summary with "Cục bộ" badge, "50 tin gần nhất", Overview, Key points, Decisions, and citation chips | dark / vi |
| `s5-04-sheet-action-items-dark-vi.png` | Action items with interactive checkboxes and citation chips + Open questions section | dark / vi |
| `s5-05-sheet-topics-dock-dark-vi.png` | Topic chips (`#dùng`, `#đóng`, `#tóm tắt`, `#tin`, `#người`) and bottom action dock (Tạo lại / Sao chép / Chia sẻ) | dark / vi |
| `s5-06-shared-into-composer-dark-vi.png` | Share into chat prefilling composer with formatted summary draft and enabled send button | dark / vi |
| `s5-07-overflow-menu-dark-vi.png` | Top-bar overflow menu showing "Tóm tắt cuộc trò chuyện" action in group chat | dark / vi |
| `s5-08-sheet-scope-today-dark-vi.png` | Summary bottom sheet with "Hôm nay" (TODAY) scope filter selected and populated | dark / vi |
| `s5-09-sheet-landscape-dark-vi.png` | Summary bottom sheet surviving device rotation to landscape (2400x1080) with state intact | dark / vi |
| `s5-10-citation-jump-dark-vi.png` | Citation chip tap jumping and animating scroll up to source message with pulse highlight | dark / vi |
| `s5-11-sheet-error-offline-dark-vi.png` | Summary bottom sheet offline network error view ("Không thể kết nối máy chủ" + "Thử lại") | dark / vi |
| `s5-12-settings-consent-off-dark-vi.png` | Profile Settings in dark theme with AI summary consent toggle OFF | dark / vi |
| `s5-13-consent-dialog-dark-vi.png` | First-use consent confirmation dialog ("Bật tóm tắt bằng AI?") with explanatory warning and "Hủy" / "Đồng ý" | dark / vi |
| `s5-14-settings-consent-on-dark-vi.png` | Profile Settings with AI summary consent toggle ON (verified PATCH reaches server `aiSummaryConsent: true`) | dark / vi |
| `s5-15-settings-rollback-dark-vi.png` | Profile Settings showing optimistic toggle rollback to previous state when network PATCH fails | dark / vi |
| `s5-16-settings-consent-light-vi.png` | Profile Settings in light theme (Vietnamese) with consent toggle and consequence disclosure copy | light / vi |
| `s5-17-sheet-ready-light-vi.png` | Summary bottom sheet in light theme showing "AI" badge (LLM path enabled via user consent) | light / vi |
| `s5-18-sheet-skeleton-light-vi.png` | Summary skeleton shimmer loading placeholders for overview, list items, and action chips in light theme | light / vi |
| `s5-19-settings-consent-light-en.png` | Profile Settings in English: Privacy section showing "Use external AI for summaries" toggle and disclosure copy | light / en |
| `s5-20-unread-banner-light-en.png` | Unread catch-up banner in English ("73 unread · Summary") | light / en |
| `s5-21-sheet-ready-light-en.png` | Summary bottom sheet in English: "Conversation summary", "AI" badge, "Last 50", "Overview", "Key points" | light / en |
| `s5-22-presence-ticker-1min-dark-vi.png` | Direct chat top bar with offline peer showing relative presence subtitle advanced to "Hoạt động 1 phút trước" via UI clock ticker | dark / vi |

## 2.6. Stage 6 — 1-to-1 Voice Call verification (`s6-*`)

| File | Screen | Theme / locale |
|---|---|---|
| `s6-01-outgoing-calling-dark-vi.png` | Outgoing calling screen ("Đang gọi…", avatar initials "MK", "Minh Khoa", "HỦY CUỘC GỌI") in dark theme | dark / vi |
| `s6-02-outgoing-calling-light-vi.png` | Outgoing calling screen in light theme | light / vi |
| `s6-03-outgoing-calling-dark-en.png` | Outgoing calling screen in English ("Calling…", "CANCEL CALL") | dark / en |
| `s6-04-outgoing-calling-landscape-vi.png` | Outgoing calling screen in landscape orientation (2400×1080) with centered controls | dark / vi |
| `s6-05-outgoing-calling-fontscale15-vi.png` | Outgoing calling screen at 1.5× font scale with no label clipping | dark / vi |
| `s6-06-connected-call-dark-vi.png` | Connected call screen with mute, speaker, and "KẾT THÚC" controls active | dark / vi |
| `s6-07-connected-call-muted-dark-vi.png` | Connected call screen with microphone muted (button toggle state showing "BẬT MIC") | dark / vi |
| `s6-08-connected-call-speaker-dark-vi.png` | Connected call screen with speakerphone enabled (button toggle state showing "LOA TRONG") | dark / vi |
| `s6-09-connected-call-landscape-vi.png` | Connected call screen surviving device rotation to landscape (2400×1080) | dark / vi |
| `s6-10-incoming-call-activity-dark-vi.png` | Incoming call full-screen activity over lock screen with "TỪ CHỐI" and "TRẢ LỜI" action buttons | dark / vi |
| `s6-11-incoming-call-notification-vi.png` | Incoming call heads-up notification in notification shade with "Trả lời" and "Từ chối" quick actions | dark / vi |
| `s6-12-chat-history-call-log-vi.png` | Direct chat conversation rendering localized outgoing call duration bubble ("Cuộc gọi thoại đi · 0:18") | dark / vi |
| `s6-13-chat-missed-call-line-vi.png` | Direct chat conversation rendering localized cancelled call bubble ("Cuộc gọi thoại đã hủy") | dark / vi |
| `s6-14-permission-dialog-mic-vi.png` | System `RECORD_AUDIO` permission request dialog triggered on call initiation | dark / vi |
| `s6-15-permission-denied-fallback-dialog-vi.png` | Informational dialog on microphone permission denial explaining mic requirement for calling | dark / vi |

## 2.7. Stage 7 — Offline / Airplane-mode and core-flow regression pass (`s7-*`, captured on emulator-5558)

Screenshots captured on headless Android 15 AVD `nexachat-audit` (`emulator-5558`, 1080×2400 / 2400×1080) against live backend on `localhost:8080`, validating offline message queuing, tap-to-retry affordance, atomic reconnection dispatch, offline attachment error handling, offline cached inbox with localized call-log previews, offline call initiation error handling, rotation and process death (`am kill`) in chat and summary sheet, live locale switching, and font scale 2.0:

| File | Screen / evidence | Theme / locale |
|---|---|---|
| `s7-01-offline-chat-pending-retry-dark-vi.png` | Open chat with Airplane mode ON: composed message displays in pending/sending state with "Không có kết nối. Kiểm tra mạng" banner and "Mất kết nối" top bar | dark / vi |
| `s7-02-online-chat-delivered-resume-dark-vi.png` | Open chat after Airplane mode OFF: queued message delivered exactly once with "Đã gửi" mark, peer presence and typing resume ("Đang hoạt động" + dot) | dark / vi |
| `s7-03-attachment-send-offline-failed-dark-vi.png` | Attachment send attempted while offline: displays "Không có kết nối. Kiểm tra mạng" error and "!" error state on pending attachment strip card with remove button | dark / vi |
| `s7-04-inbox-cached-offline-call-log-dark-vi.png` | Cached inbox rows displayed offline in Vietnamese dark theme: direct chat row shows localized call log preview ("Cuộc gọi thoại đến · 0:03") | dark / vi |
| `s7-05-inbox-cached-offline-call-log-dark-en.png` | Cached inbox rows displayed offline in English dark theme: direct chat row shows localized call log preview ("Incoming voice call · 0:03") | dark / en |
| `s7-06-call-offline-error-dialog-dark-vi.png` | Outgoing call initiation while offline: shows clear modal error dialog ("Cuộc gọi thất bại" + "HỦY") | dark / vi |
| `s7-07-chat-rotation-landscape-dark-vi.png` | Direct chat surviving configuration change to landscape (2400×1080): message list, pinned banner, and composer intact | dark / vi |
| `s7-08-chat-process-death-restored-dark-vi.png` | Direct chat restored after background process death (`am kill`): full message history, pinned item, and delivery states preserved | dark / vi |
| `s7-09-summary-sheet-rotation-landscape-dark-vi.png` | Summary bottom sheet surviving configuration change to landscape (2400×1080) with summary scope and content intact | dark / vi |
| `s7-10-summary-sheet-process-death-restored-dark-vi.png` | Summary bottom sheet restored after background process death (`am kill`): `rememberSaveable` sheet state remains open and recovers content | dark / vi |
| `s7-11-inbox-fontscale200-dark-vi.png` | Conversation inbox at accessibility font scale 2.0 (Vietnamese, dark theme): full text legibility without clipping | dark / vi |
| `s7-12-chat-fontscale200-dark-vi.png` | Direct chat conversation at accessibility font scale 2.0 (Vietnamese, dark theme): message bubbles scale properly with clean wrap | dark / vi |
| `s7-13-inbox-fontscale200-dark-en.png` | Conversation inbox at accessibility font scale 2.0 (English, dark theme): complete text hierarchy without truncation | dark / en |
| `s7-14-chat-fontscale200-dark-en.png` | Direct chat conversation at accessibility font scale 2.0 (English, dark theme): bubbles and timestamps scale cleanly | dark / en |
| `s7-15-settings-language-switch-live-dark.png` | Profile Settings language selection screen showing live in-app language switching (System default / Tiếng Việt / English) | dark / vi |

## 2.8. Stage 8 — Call-failure reasons and viewer-relative call direction (`s8-*`)

Captured on the `nexachat-audit` AVD (`emulator-5558`, Android 15, 1080×2400) against the live backend on `localhost:8080`. Validates specific offline and busy failure reasons, plus viewer-relative inbox call previews in Vietnamese and English.

| File | Screen / evidence | Theme / locale |
|---|---|---|
| `s8-01-call-offline-error-dialog-dark-vi.png` | Airplane-mode outgoing call failure with title “Cuộc gọi thất bại” and a specific connection-reason line | dark / vi |
| `s8-02-call-offline-error-dialog-dark-en.png` | Airplane-mode outgoing call failure with title “Call failed” and a specific connection-reason line | dark / en |
| `s8-03-inbox-outgoing-call-dark-vi.png` | Inbox preview for the viewer-placed call (“Cuộc gọi thoại đi · 0:00”) | dark / vi |
| `s8-04-inbox-outgoing-call-dark-en.png` | Inbox preview for the viewer-placed call (“Outgoing voice call · 0:00”) | dark / en |
| `s8-05-inbox-incoming-call-dark-vi.png` | Inbox preview for the call received from the peer (“Cuộc gọi thoại đến · 0:00”) | dark / vi |
| `s8-06-inbox-incoming-call-dark-en.png` | Inbox preview for the call received from the peer (“Incoming voice call · 0:00”) | dark / en |
| `s8-07-call-busy-error-dialog-dark-vi.png` | Busy-peer failure with title “Cuộc gọi thất bại” and reason “Người dùng đang bận” | dark / vi |

Fixture accounts, conversations, call records, and messages were removed after capture; targeted row counts were verified as zero.

## 2.9 Inventory — UI pass reference frames (`s9-*`)

Captured on `nexachat-audit` (1080×2400, dark, en, demo clock 9:41) after the
UI polish pass. Fixture names are `UX Pass 1` and `UX Pass 2`.

| File | Screen / evidence | Theme / locale |
|---|---|---|
| `s9-01-inbox-dark-en.png` | Inbox with one direct row | dark / en |
| `s9-02-chat-landscape-draft-dark-en.png` | Landscape direct chat; the composer still holds the typed draft | dark / en |
| `s9-03-profile-dark-en.png` | Profile: language, theme, presence, summary consent, sign out | dark / en |
| `s9-04-inbox-font130-dark-en.png` | Inbox at font scale 1.3 | dark / en |
| `s9-05-login-validation-dark-en.png` | Empty sign-in shows the phone and password errors | dark / en |

## 2.10 Inventory — round-2 reference frames (`s10-*`)

Captured on `nexachat-audit` port 5558 after the Java and contact-copy pass.
Demo mode was not set, so the status bar shows the device clock. No fixture
name or phone number is visible in these frames. The landscape login column
scrolls; the sign-in button meets the gesture bar until the column is scrolled.

| File | Screen / evidence | Theme / locale |
|---|---|---|
| `s10-login-light-en.png` | Sign-in, light | light / en |
| `s10-register-light-en.png` | Create account | light / en |
| `s10-inbox-light-en.png` | Empty inbox after a fixture sign-in | light / en |
| `s10-login-dark-en.png` | Sign-in, dark | dark / en |
| `s10-login-dark-landscape.png` | Sign-in, dark landscape | dark / en |

## 3. Hygiene checklist (applied to every `s2-*` capture)

- No probe/e2e/test strings: the demo data was re-seeded and the two
  socket-probe messages used while verifying realtime were deleted before the
  captures; every PNG was opened and checked.
- No stack traces, no debug banners, no notification icons (demo mode hides
  them); clock fixed at 9:41 except the four font-scale captures, which ran after
  a SystemUI restart cleared demo mode (see §0).
- No truncated bubble text at 1.0 / 1.3 / 2.0 font scale; the action sheet
  scrolls when its items do not fit at large font scales (Edit/Delete are below
  the fold at 1.3+, by design of the scrollable sheet).
- Known device-chrome caveat: a small "Wi-Fi off" glyph stays in the status bar
  (the headless AVD has no internet-validated network). In dark-theme captures
  the status-bar clock/battery icons render dark-on-dark — see the device report
  (`s2-17`, `s2-22`, `s2-31`): that is the `enableEdgeToEdge()` system-bar
  appearance bug reported to the UI owner, not an image artifact.

## 4. Reproducing a capture

```bash
# device: one headless AVD on port 5558, then build/install the debug APK
adb -s emulator-5558 install -r android/app/build/outputs/apk/debug/app-debug.apk

# demo data (idempotent): users Lan Anh / Minh Khoa / Bao Chau + direct chats + group
python tools/seed_demo_chat.py

# drive the UI without hard-coded coordinates and capture
python tools/e2e/device/adb_ui.py tap-text "Minh Khoa"
python tools/e2e/device/adb_ui.py longpress-text "Mai nhớ mang đầy đủ tài liệu"
python tools/e2e/device/adb_ui.py shot docs/design/screens/s2-01-action-sheet-light-vi.png
```

`tools/e2e/device/demo_state.py` repairs demo data (`list`, `tidy`,
`seed-direct`, `mark-read`, `settings`, `send`) and
`tools/e2e/device/realtime_probe.py` opens a second STOMP session
(peer online/offline, live `MESSAGE_UPDATED`/`MESSAGE_DELETED`).
