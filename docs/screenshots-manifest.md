# NexaChat Android — Screenshot Capture Manifest

Capture inventory and recapture plan for the final report. Routes/strings verified against
`android/app/src/main/java/com/nexachat/core/ui/AppNavHost.kt` (`Routes`),
the feature screens, and `res/values*/strings.xml`. Seed data from `demo/seed.sql`.

## 0. Ground truth (read first)

- Backend up + seeded (`docker compose up -d db app`; `psql … < demo/seed.sql`).
  Accounts, all password **`password123`**: `+84900000001` **An** (group ADMIN),
  `+84900000002` **Binh** (MEMBER), `+84900000003` **Chi**, `+84900000004` **Dung**.
  The live bcrypt hash in `seed.sql` verifies `password123` (confirmed against
  the seed hash); `README.md` / `docs/demo-runbook.md` agree. If rows from an
  older draft seed (`demo@1234`) are present, run
  `DELETE FROM users WHERE phone LIKE '+8490000%';` then re-seed.
- Seeded conversations: direct An↔Binh `aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa`
  (Binh `last_read_seq=4` of 6 → unread 2); group **Nhom Do An**
  `bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb` (Binh 8/10 → unread 2, Dung 0/10).
- `API_BASE_URL` is baked at build time (`app/build.gradle.kts` → `BuildConfig`
  → `core/common/AppConfig.kt`), default `http://10.0.2.2:8080` (emulator host
  loopback). LAN: set `API_BASE_URL=http://<LAN-IP>:8080` in `android/local.properties`
  and rebuild, or `-PapiBaseUrl=…`. Cleartext HTTP is debug-build only.
- Default language follows system. Force VI/EN in-app: Profile → `settings_language`
  → `LanguageSettingsScreen` (activity recreates; choice persists via `AppLocaleManager`).
  Language rows render endonyms by design (`language_name_vietnamese`/`_english`,
  `translatable="false"`).
- Capture status: all 22 planned PNGs are present. The original 10 files were
  preserved; 12 additional captures cover group, media, interactions, typing,
  reply quote, and English UI. A second set of 21 live design-system
  screenshots (EN/VI + dark, Material 3) is in `docs/design/screens/`; see
  `docs/design/design-notes.md`. Inventory of that set:

| # | File | Screen / theme |
|---|---|---|
| 1 | `01-login-light-en.png` | Login · light EN |
| 2 | `02-register-light-en.png` | Register · light EN |
| 3 | `03-inbox-light-en.png` | Inbox · light EN |
| 4 | `04-group-chat-light-en.png` | Group chat · light EN |
| 5 | `05-group-detail-light-en.png` | Group detail · light EN |
| 6 | `06-contacts-light-en.png` | Contacts · light EN |
| 7 | `07-search-light-en.png` | Search · light EN |
| 8 | `08-search-results-light-en.png` | Search results · light EN |
| 9 | `09-contact-detail-light-en.png` | Contact detail · light EN |
| 10 | `10-profile-light-en.png` | Profile · light EN |
| 11 | `11-language-settings-light-en.png` | Language · light EN |
| 12 | `12-language-settings-light-vi.png` | Language · light VI |
| 13 | `13-profile-light-vi.png` | Profile · light VI |
| 14 | `14-inbox-light-vi.png` | Inbox · light VI |
| 15 | `15-create-group-light-vi.png` | Create group · light VI |
| 16 | `16-direct-chat-reply-pinned-light-vi.png` | Reply + pin · light VI |
| 17 | `17-media-picker-light-vi.png` | Media picker · light VI |
| 18 | `18-reactions-light-vi.png` | Reactions · light VI |
| 19 | `19-jump-pinned-light-vi.png` | Jump-to-pin · light VI |
| 20 | `20-direct-chat-dark-vi.png` | Direct chat · dark VI |
| 21 | `21-inbox-dark-vi.png` | Inbox · dark VI |

**Hygiene flag (for Lead):** several design screens still show test/probe
message content from the live demo session — `16`, `17`, `18`, `19`, `20`
contain strings such as `relproof`, `outbox rearm proof`, `socket probe from
Binh`, `post-restart push probe`, `unreadbadgeprobe2robe`, `receiptprobe`,
`JWT_REFRESH_LIVE_20261104`, `JWT_AFTER_EXPIRY_20261104`, and a pinned
`1000000076.png`; `03`, `14`, `21` show `nexachat-verify-file.txt`,
`hi Dung from UI`, `An renamed the group to "NexaChat Group"`; `06`/`15` show
the extra test account `Em Test (+849000000099)`. They are left in place for
the Lead to decide (re-capture or accept).

## 1. Required screenshots (one row per feature evidence)

| # | File(s) | Screen / real route | Navigation path & preconditions | Rubric |
|---|---|---|---|---|
| 1 | `01-splash-vi.png` | Splash · `splash` (`Routes.SPLASH`) | Cold launch (force-stop → launch); capture during the session check before auto-nav. Shows the brand logo, app title, and progress indicator (`splash_tagline` was removed). | R2 auth/session restore (FR-A); R5 UI |
| 2 | `02-login-vi.png` | Login · `login` (`Routes.LOGIN`) | Fresh install / logged-out → splash routes here. VI labels `auth_phone_label` “Số điện thoại”, `action_sign_in` “Đăng nhập”. | R2 account (FR-A1–A3); R3 i18n (FR-F1) |
| 3 | `03-register-validation-vi.png` | Register · `register` (`Routes.REGISTER`) | Login → `action_create_account` “Tạo tài khoản”. Enter name+phone, short password, submit → inline `error_password_short` “Mật khẩu tối thiểu 8 ký tự”. | R2 account (FR-A4–A6) |
| 4 | `04-inbox-unread-vi.png` | Inbox · `inbox` (`Routes.INBOX`) | Log in as **Binh** (device B) → inbox. Two `Badge`s visible: direct chat (2) and group “Nhom Do An” (2). Title `inbox_title` “Tin nhắn”. | R2 chat (FR-C unread); R5 UI |
| 5 | `05a-contacts-vi.png`, `05b-search-vi.png` | Contacts `contacts` → Search `search` (`Routes.CONTACTS`/`SEARCH`) | Inbox top-bar person icon → Contacts (An sees Binh, Chi). Top-bar search icon → type `+84900000003` (leading `+` → exact phone lookup) → Chi. Label `search_label` “SĐT hoặc tên”; FAB `action_add_contact`. | R2 search/add by phone (FR-B1–B4) |
| 6 | `06a-direct-sent-vi.png`, `06b-direct-received-vi.png`, `06c-direct-typing-vi.png` | Chat · `chat/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa` | A=An, B=Binh open the seeded direct chat. The set shows sent and received bubbles plus the live `chat_typing` “đang nhập…” indicator. | R2 direct chat (FR-C1–C7); R4 realtime |
| 7 | `07-group-chat-vi.png` | Chat · `chat/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb` | As **An**, Inbox → “Nhom Do An”. Incoming bubbles carry sender names (`showSender = isGroup && !mine`) — An/Chi/Dung/Binh all visible. | R2 group chat (FR-D1–D5) |
| 8 | `08-group-members-vi.png` | Group detail · `group/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb/detail` (`Routes.GROUP_DETAIL`) | In group chat, top-bar info icon (`cd_group_info`) → member list. As **An** (ADMIN): ADMIN `Badge`s (`group_role_admin`), add-member row, remove/rename icons visible. | R2 group authZ (FR-D2/D4) |
| 9 | `09a-media-picker-vi.png`, `09b-image-message-vi.png` | Attach sheet + chat · `media/{attachmentId}?name=&mime=` | Chat → attach icon (`cd_attach`) → `MediaPickerSheet`: `media_pick_visual` “Ảnh hoặc video” / `media_pick_document` “Tệp hoặc âm thanh”. Pick a gallery photo → `PendingAttachmentStrip` upload → image bubble (tap opens `MEDIA_VIEWER`). | R3 media (FR-E1/E2) |
| 10 | `10-file-attachment-vi.png` | Chat · FILE bubble (`MediaRow`) | Chat → attach → “Tệp hoặc âm thanh” → SAF picker → choose a small PDF/txt from Downloads (<25 MB, `error_attachment_too_large`). Row shows icon + name + size. Size uses dot decimal by design (`i18n-audit.md` §3). | R3 media (FR-E3/E4) |
| 11 | `11-reactions-vi.png` | Chat · long-press menu + chips | Long-press a **SENT** bubble → `ReactionBarRow` (👍❤️😂😮😢🙏) → tap 👍 → `ReactionChipsRow` chip with count 1. Do on An’s device on a Binh message. | R4 creative (FR-H1) |
| 12 | `12a-reply-quote-vi.png`, `12b-pinned-banner-vi.png` | Chat · reply preview/quote + `PinnedBanner` | Captures a sent reply with its quoted source, then the pinned banner (`pinned_count` “1/3”). | R4 creative (FR-H2/H3) |
| 13 | `13a-profile-vi.png`, `13b-language-settings-vi.png` | Profile `profile` → `settings/language` (`Routes.PROFILE`/`LANGUAGE_SETTINGS`) | Inbox top-bar account icon → Profile (name edit `cd_edit_name`, `profile_change_password` stub disabled). Tap `settings_language` “Ngôn ngữ” → radio list: “Theo hệ thống” / “Tiếng Việt” / “English”. | R2 account (FR-A5); R3 i18n (FR-F1/F2) |
| 14 | `14a-inbox-en.png`, `14b-chat-en.png`, `14c-language-settings-en.png` | EN variants of rows 4, 6/7, 13b | After the VI pass: Profile → Language → **English** (one switch). Re-open Inbox/chat/settings. EN strings: `inbox_title` “Chats”, `chat_input_hint` “Message”, `chat_typing` “typing…”, `action_logout` “Sign out”. | R3 i18n (FR-F1/F2); R5 EN/VI parity (96/96 keys) |

## 2. Capture checklist

**Device prep**
- Two devices/emulators on the same Wi-Fi/LAN as the backend host. Emulator uses
  the default `10.0.2.2:8080`; a physical device needs the `local.properties`
  `API_BASE_URL=http://<LAN-IP>:8080` rebuild. `adb reverse tcp:8080 tcp:8080`
  only if you point the app at `localhost`/`127.0.0.1`.
- Log **An** into device A and **Binh** into device B once; keep both sessions alive.
- Clear app data (or reinstall) for the logged-out shots (rows 1–3); force-stop +
  relaunch to reach splash each time.
- Set VI before the first pass (system locale `vi_VN`, or in-app Language → Tiếng Việt).

**Ordering**
1. VI pass only first: auth/logged-out shots (1–3), then log in and shoot 4–13.
2. Switch language to English exactly once, then shoot row 14. No further re-logins.
3. Keep chat → media → interactions contiguous so navigation is not repeated.

**Naming** — `NN[a-z]-slug-locale.png`, two-digit sequence = capture order,
optional letter for a set of related screens, locale suffix `-vi`/`-en`,
lowercase hyphen slug. The report references the exact names in the table.

**Where to save** — `docs/screenshots/` (contains all 22 planned PNGs and is not
matched by `.gitignore`). **Recommendation: commit them** as report evidence for RTM rows
R2–R5; they contain only seed/demo accounts, no real personal data. If any shot
accidentally captures real user data or an absolute host path, exclude it and
re-capture. Do not add a broad `docs/screenshots/` ignore rule.

**Recapture order (minimal re-logins)**
1. 0:30 — device A, logged out, VI: 01 splash → 02 login → 03 register validation.
2. 0:30 — log in An (A) + Binh (B) in parallel. On B: 04 inbox unread, 05 contacts/search, 13a/13b profile + language (still VI).
3. 1:00 — direct chat with A↔B: 06a sent (A) + 06b received/typing (B), 11 reactions, 12a reply.
4. 1:00 — on A: 07 group chat, 08 member management, 12b pinned banner, 09 media picker/image, 10 file attachment.
5. 0:30 — on A: Language → English → 14a/14b/14c EN variants. Current set is complete: one login per device, one language switch.

## 3. Optional adb helper (emulator captures)

```bash
# Host-side capture, run from the repo root (cmd.exe / Git Bash — binary-safe redirect)
adb exec-out screencap -p > docs/screenshots/01-splash-vi.png

# Multi-device: pin the serial
adb -s emulator-5554 exec-out screencap -p > docs/screenshots/06b-direct-chat-received-typing-vi.png

# Only if API_BASE_URL points at localhost/127.0.0.1 (default 10.0.2.2 does not need it)
adb reverse tcp:8080 tcp:8080
```

PowerShell caveat: `>` in Windows PowerShell 5.1 writes UTF-16 and corrupts PNGs —
use `cmd /c "adb exec-out screencap -p > docs\screenshots\01-splash-vi.png"` instead.
