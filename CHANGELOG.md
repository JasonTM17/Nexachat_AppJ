# Changelog

All notable changes to NexaChat are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); releases use
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Changes on top of `v1.0.0`: uncommitted working-tree work plus commits made
after the tag.

### Added

- Message edit, delete and search: `PATCH`/`DELETE /conversations/{id}/messages/{messageId}`
  and `GET /conversations/{id}/messages/search`, with `MESSAGE_UPDATED` /
  `MESSAGE_DELETED` STOMP events and `NOT_MESSAGE_AUTHOR` / `MESSAGE_DELETED`
  error codes.
- Presence: `/user/queue/presence`, `USER_ONLINE` / `USER_OFFLINE` events,
  `last_seen_at` / `show_presence` columns (Flyway `V4__presence.sql`,
  `unaccent` extension), a `presence` server package, and Room v7 migration
  (`android/app/schemas/.../7.json`).
- Conversation catch-up summary: `POST /api/v1/conversations/{id}/summary`
  with an extractive local engine, an optional DeepSeek-backed LLM engine,
  citation-backed items, Flyway V6 schema, consent-gated provider selection,
  per-user rate limits, and an Android summary UI with EN/VI engine badges
  (`docs/ai-summary.md`).
- User settings: `GET`/`PATCH /api/v1/users/me/settings`, a theme mode
  (system/light/dark) stored locally, a server-backed show-presence toggle,
  plus a settings screen.
- Text-only message forwarding in the client; attachments are not forwarded.
- Voice calls: STOMP signaling, authenticated ICE configuration and
  direct-call history backed by a server call registry and persisted terminal
  records; Android call core/media/service/UI with incoming/outgoing screens,
  a foreground service, localized call log and EN/VI resources. Live E2E
  (`tools/e2e/calls/call_e2e.py`) and on-device screenshots (`s6-*`) verified.
- REST message rate limiting: per-user edit/delete (30/min) and search
  (60/min) buckets via `MessageRateLimitInterceptor`, configurable in
  `application.yml`.
- Material 3 redesign: shared design-system components (`Avatar`, `ChatBubble`,
  `ChatUiRules`, `LinkUtils`, `Motion`, `SectionHeader`, `SettingsRow`,
  `theme/Elevation`, `theme/Shape`, `theme/Spacing`), in-chat search UI, a
  message action sheet and profile/settings screens.
- Midnight Obsidian & Electric Violet UI overhaul (Dark & Light parity):
  `#0B0E17` dark canvas, `#151B2B` navy cards, `#7C3AED` electric violet accents,
  `AvatarView` online status dot and story ring, Inbox `ACTIVE NOW` strip
  (prioritizing direct peers and deduplicating titles), 4 pill filter chips,
  4-tab bottom navigation bar, unified chat header with `36dp` peer avatar,
  Material card rows for Contacts, and electric violet chip selectors across
  Profile and Conversation Summary sheets (`s7-*-stitch-*`, `docs/media/screens/stitch-*.png`).
- Four new UI design references (`in_chat_search`, `message_action_sheet`,
  `profile_settings`, `theme_privacy_settings`).
- New user-facing strings in paired EN/VI resources, including call-specific
  `strings_call.xml` files (time labels, content descriptions, call controls,
  and settings section titles) — resource-name parity maintained.

### Changed

- Inbox and chat payloads now carry edit/delete metadata, peer presence,
  `otherUserId` and `lastMessageDeleted`.
- `docs/design/stitch/screens/*` regenerated for the redesigned screens.
- `docs/api-contract.md` and `docs/websocket-protocol.md` extended for the
  new endpoints, events and limits.
- Test coverage expanded: Room v7 migration, message mutation/search/rate-limit,
  presence, settings, design-system UI rules and realtime event decoding.

### Fixed

- Deleted messages clear stale reply snippets in Room instead of waiting for
  the next history page.
- The STOMP SimpleBroker now runs a heartbeat scheduler (10 s), so an idle
  ringing caller keeps receiving server heartbeats instead of tripping the
  client's 30 s liveness guard.
- The ring-timeout timer re-arms itself when it fires a moment before the ring
  deadline, instead of leaving a call ringing forever.
- A stale offline presence seed can no longer demote an online peer.
- Group-detail inbox refresh no longer prunes a conversation created while a
  `GET /conversations` was in flight.

### Security

- Per-user STOMP SEND rate limiting with a correlated `RATE_LIMITED` error
  frame (committed in `947cd66`); REST edit/delete/search use their own
  `MessageRateLimitInterceptor` buckets.

### Committed since `v1.0.0` (not tagged)

- `947cd66` — server hardening: upload size caps enforced before buffering,
  10,000-character message cap, read-watermark validation, NFC-normalised
  reactions, newest-first batch-hydrated pins, optional inbox keyset
  pagination, `X-Content-Type-Options: nosniff` on downloads, production
  datasource fail-fast.
- `fc4cce1` — Android hardening: realtime session and token-store fixes,
  upload/download safety, inbox reconcile.
- `223d520` — Material 3 UI design refresh (also listed under Added).
- `cb9a58f` — CI `:app:lintDebug` step and documentation updates.
- `9b5e9cb` — README, security, and protocol documentation synced with shipped
  behaviour.

## [1.0.0] - 2026-11-04

First tagged release (`v1.0.0`, commit `64d47c0`).

### Added

- Account management: registration/login with E.164 phone numbers, bcrypt
  (cost 12), JWT access tokens with rotating refresh tokens and reuse
  detection, logout / logout-all and change-password.
- Contacts: phone-number search, add/remove contacts, alias names.
- Direct and group chat over STOMP/WebSocket with a Room-backed offline
  outbox, optimistic send, read receipts (watermark) and typing indicators.
- Group management: create group, add/remove/leave members, admin roles,
  rename, system messages and group avatars.
- Multimedia: image/video/audio/file upload with magic-byte sniffing and a
  25 MB cap, owner-or-member download authorization, viewer and
  `ACTION_VIEW` sharing.
- i18n: full EN/VI string parity, per-app locale switching (API 33+ native,
  older via config context).
- Rotation-safe state: Room as the single source of truth, `SavedStateHandle`
  drafts.
- Creative interactions: reply/quote, emoji reactions (NFC-normalised) and
  pinned messages (≤3, group-admin gated).
- Material 3 UI with ≥48dp touch targets, offline states and accessibility
  pass.
- Operations: multi-stage `server/Dockerfile`, `docker-compose.yml`, LAN bootstrap
  scripts, and signed-APK release pipeline.
- Documentation: architecture diagrams, REST/STOMP contracts, security
  architecture, operations guide, and UI design documentation.
- CI: server tests (Maven verify), Android unit tests and lint, and a
  committed-secret scan on every PR and push to `main`/`develop`.
