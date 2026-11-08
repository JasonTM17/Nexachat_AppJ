# Demo media

Still frames captured from live Android emulators (`emulator-5554` / `nexachat-audit` and `emulator-5556` / `campuscore-p07-native`, 1080×2400 portrait, 2400×1080 landscape) running the R8-minified release build against the local Spring Boot 3 + PostgreSQL 16 backend.

## UI Showcase Frames

| File | What it shows |
| --- | --- |
| `screens/stitch-01-inbox-dark.png` | Dark Mode Inbox (`#0B0E17` obsidian canvas, `✦ AI` badge, pill search bar, `ACTIVE NOW` strip prioritizing direct peers with purple story rings & green online dots, 4 filter chips, bottom nav + compose FAB) |
| `screens/stitch-02-inbox-light.png` | Light Mode Inbox (`#F8FAFC` light canvas, `#FFFFFF` conversation cards, `ACTIVE NOW` strip, `#7C3AED` active filter chip) |
| `screens/stitch-03-inbox-unread-chip.png` | Inbox filtered dynamically by the `Unread (1)` electric violet pill chip |
| `screens/stitch-04-chat-bubbles-reaction.png` | Chat screen with unified `#151B2B` header, `36dp` peer avatar, `#182034` incoming bubble, `#5B21B6` electric violet outgoing bubble, and `👍 1` reaction pill |
| `screens/stitch-05-in-chat-search.png` | In-conversation message search bar docked cleanly below the unified chat header |
| `screens/stitch-06-ai-summary-sheet.png` | Conversation summary bottom sheet (`Local` + `Last 50` violet chips) showing generated Overview, Key points with citation pills (`↗ #6029`), Action items with checkboxes, and Open questions |
| `screens/stitch-07-profile-dark.png` | Profile settings in Dark Mode (Setting cards, `#7C3AED` active `Dark` chip, presence & AI summary consent switches) |
| `screens/stitch-08-profile-light.png` | Profile settings in Light Mode (high-contrast dark back arrow, `#7C3AED` active `Light` chip, setting cards) |
| `screens/stitch-09-contacts-cards.png` | Contacts screen with `18dp` rounded `MaterialCardView` rows, green online presence dots, and phone subtitles |
| `screens/stitch-10-create-group.png` | New group creation screen with card container, focused purple outline input, member checkboxes, and pill button |

## Core Feature Frames (`screens/01-*` .. `screens/17-*`)

| File | What it shows |
| --- | --- |
| `screens/01-login-dark-en.png` | Sign-in card (`✦ NEXACHAT WORKSPACE`), dark/light Material 3 styling |
| `screens/02-register-dark-en.png` | Create-account form with `✦ NEXACHAT WORKSPACE` pill badge |
| `screens/03-inbox-dark-en.png` | Dark Mode Inbox with `ACTIVE NOW` strip, unread badges, and 4 filter chips |
| `screens/04-direct-chat-dark-en.png` | Chat with electric violet outgoing bubble and `👍 1` reaction chip |
| `screens/05-group-chat-dark-en.png` | Group chat with system creation message and composer |
| `screens/06-group-detail-dark-en.png` | Group members, admin role chip, add member and leave group actions |
| `screens/07-contacts-dark-en.png` | Contacts list with contact cards, online dots, and phone subtitles |
| `screens/07-search-phone-dark-en.png` | User search screen with rounded purple-focused search bar |
| `screens/08-profile-top-dark-en.png` | Profile settings in Dark Mode with theme chips, language, presence, and AI summary consent |
| `screens/09-outgoing-call-dark-en.png` | Outgoing 1-to-1 voice call screen, ringing |
| `screens/11-call-failure-dark-en.png` | Call failure dialog with connection status reason |
| `screens/12-summary-dark-en.png` | Conversation summary sheet with generated Overview, Key points, citations, and Action items |
| `screens/13-inbox-light-vi.png` | Light Mode Inbox (Vietnamese) |
| `screens/14-chat-light-vi.png` | Direct chat in Light Mode (Vietnamese) |
| `screens/15-chat-light-en.png` | Chat screen in Light Mode (English) |
| `screens/16-chat-landscape-dark-en.png` | Chat screen in Dark Mode landscape orientation (2400×1080) |
| `screens/17-offline-chat-dark-en.png` | Offline/disconnected banner in chat with queued outbox message |

## Animated Walkthroughs (GIFs)

Screen recordings of core workflows (login/register, realtime send/receive, offline queue/reconnect, media attachments with blurhash placeholder, voice message, 1-to-1 voice calls, and AI summary) are captured via the recording and palette conversion tooling in [`tools/gif/`](../../tools/gif/README.md).

