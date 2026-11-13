# Changelog

All notable changes to NexaChat are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); releases use
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.2.5] - 2026-11-13

Fix release after a full review of the Android app, server, website and docs.
The `v1.2.5` app works with the running `v1.2.1` server; the server fixes below
take effect only after the owner approves a redeploy. No migration, no new
required environment variable. See
[docs/release-notes/v1.2.5.md](docs/release-notes/v1.2.5.md).

### Android

**Fixed**

- Photo avatars stay circular, the camera badge no longer covers initials, and
  the inbox has no stray gap; names wrap at large font scale; online status is
  read with the name.
- Chat: operation-specific Retry on the inline error, TalkBack announcements for
  errors, sending, reactions and read receipts, a failed edit keeps its draft,
  attachment and voice sends quote the reply target, and a permanently denied
  microphone offers app settings.
- Unread badges keep messages newer than the read receipt, synced messages are
  not double-counted with the outbox, and leaving a group no longer clears other
  conversations from the inbox.
- Groups: the only admin is told why leaving is unavailable; leaving a group that
  was opened directly returns to a single inbox.
- Account: sign-out on other devices reports when it cannot be confirmed,
  accounts without a password no longer offer Change password, deletion codes
  can be resent, and message notifications are dismissed when the session ends.
- Sign-in: login and register replace each other on the back stack, and an
  invite is kept through Google onboarding and rotation.
- Discovery, invite loading, polls and reminders, archive, summary actions and
  the media viewer show readable errors with a retry.

### Server

**Fixed** (effective after the next server deploy)

- Group authority is revalidated after locking and join-request decisions are
  atomic; null sync watermarks are rejected; the last account sign-in method
  cannot be removed.
- Reminders keep their occurrence after a retryable push failure; backups refuse
  completed destinations, publish from a fresh staging directory and include
  query-string avatar references.
- The scheduled embedding backfill stays on the local provider (no message text
  leaves the server without consent); calls to deleted accounts are rejected.
- Docker Compose forwards the documented server feature settings.

### Website

**Fixed**

- Account guidance matches Google sign-up and password-free deletion; Vietnamese
  and English jump links and a working install path on the download and invite
  pages.

### Documentation and tooling

- Historical audit evidence is bannered, E2E evidence is labelled honestly,
  contributor and operator docs are aligned, a tools index was added, and CI now
  runs the offline tool unit tests and the Android accessibility check.

## [1.2.4] - 2026-11-13

Android-only release after `v1.2.3`: calmer plum palette and a second UI polish
pass. The server stays at `v1.2.1`; no migration, no new environment variable.
See [docs/release-notes/v1.2.4.md](docs/release-notes/v1.2.4.md).

### Android

**Changed**

- Colours follow the Stitch plum: filled controls (floating button, checked
  chips, unread badge, send button, camera badge) keep the same plum with white
  text in light and dark mode instead of a pale pink fill at night; surfaces and
  dividers are quieter; the dark accent for text and icons is softer.
- Avatars use three plum tones instead of eight unrelated pastels; sender names
  in group chats use six distinct colours and no longer reuse the error red.
- Inbox: one compact filter row (labels only when they exist), your own name and
  photo in the top bar, an Active Now rail that lists only people who are online,
  and no blank preview line for conversations without a message.
- Chat: the inline error has its own surface; the message field has a comfortable
  inset.
- Profile: the theme is shown once, on the three chips; a long display name is
  centred; the toolbar title uses the shared title style.

## [1.2.3] - 2026-11-13

Android-only release after `v1.2.2`: whole-app UI pass and accessibility fixes.
The server stays at `v1.2.1`; no migration, no new environment variable. See
[docs/release-notes/v1.2.3.md](docs/release-notes/v1.2.3.md).

### Android

**Changed**

- Inbox, chat toolbar/composer/search/banners, message bubbles, contacts and
  search, group detail, profile, action and forward sheets, media viewer (dark
  stage) and summary sheet use shared shapes, spacing and colours; the inbox
  gains a Contacts button and a non-extending new-chat button.
- Register validates the display name like Complete profile.

**Fixed**

- Voice recording is operable with TalkBack (click to start/send, Cancel action).
- Chat history load failure offers Retry; in-chat search shows loading, empty and
  error states.
- Shared loading, empty and error views announce state changes; invite screens
  announce status; remove-member and summary action checkboxes have names.
- Summary Copy and Share are disabled when the summary failed.
- Profile load failure offers Retry; camera badge contrast in dark mode.
- Login/Register tab switch is single-top; the loading overlay no longer names
  Google for phone sign-in; Complete profile shows the overlay while saving;
  the delete-account confirm button no longer flashes before the profile loads.
- Group member selection is rebound correctly on scroll.
- Chat title no longer overlaps the header avatar (title margin was applied in pixels).
## [1.2.2] - 2026-11-13

Android-only release after `v1.2.1`: restyled sign-in, register, complete-profile,
sign-in-methods and delete-account screens. The server stays at `v1.2.1`; no
migration, no new environment variable. See
[docs/release-notes/v1.2.2.md](docs/release-notes/v1.2.2.md).

### Android

**Changed**

- Login and Register share a segmented Sign in / Create account switch, leading
  field icons, a shared error card and 8dp spacing.
- The Google button shows the standard four-colour Google "G" instead of a
  brand-tinted mark; the "or" divider hides together with the button.
- Complete-profile, sign-in-methods card and delete-account screens use the
  same field and error styles.

**Fixed**

- Delete account waits for the profile before choosing the password or email-code
  flow, and offers Retry when the profile cannot load.
- A cancelled Google chooser while linking no longer shows an error.
- A missing Google Play services shows a specific message.
- Wording: "link Google in Profile" instead of "Settings"; Vietnamese Google
  sign-in error reads naturally.
- Docs: OAuth setup lists `com.nexachat.debug` for debug and `com.nexachat` for
  release builds.

## [1.2.1] - 2026-11-13

Patch release after `v1.2.0`: a Google-only account can delete itself with an
email code. No new migration. Deploy the server before distributing the APK.
See [docs/release-notes/v1.2.1.md](docs/release-notes/v1.2.1.md).

### Server

**Added**

- `POST /users/me/deletion-code` (authenticated, `202`) emails a 6-digit code
  (10 min, 5 attempts, single use, 60 s cooldown) to the verified email of a
  passwordless account; `409 PASSWORD_REQUIRED` for accounts with a password.
- `DELETE /users/me` accepts `{password?, code?}`.
- `GET /users/me` returns `passwordSet`.

### Android

**Fixed**

- Account deletion works for Google-only accounts through the email code flow.

### Known limitations

- A Google-only account cannot add a phone number or set a password.
- Message summaries are not purged when a message is recalled.

## [1.2.0] - 2026-11-13

Minor release after `v1.1.2`: sign up and sign in with Google, bounded sync v2
(Flyway V32 + Room 14), paired backup tooling, durable notifications, and the
v2 visual refresh. Deploy the server before distributing the APK.
See [docs/release-notes/v1.2.0.md](docs/release-notes/v1.2.0.md).

### Server

**Added**

- `POST /auth/google` creates an account for an unknown Google `sub` (`201`,
  `newAccount: true`) when `NEXACHAT_GOOGLE_SIGNUP_ENABLED=true`. The account
  is never matched by email; creation needs a verified Google email; a taken
  email answers `409 GOOGLE_EMAIL_IN_USE`. Flyway **V33** makes `password_hash`
  nullable and requires an active user to have a phone, a password, or a
  verified email.
- Contacts by email: `POST /contacts {email}` and `GET /users/search?email=`
  (exact match, verified and active users only).
- Flyway **V32** and negotiated sync v2 on `POST /sync` (`syncVersion: 2`,
  `changeCursor`, `changes`, `reset`, `generation`); legacy fields stay for v1.1
  clients. `NEXACHAT_SYNC_CHANGE_CURSORS_ENABLED` (default `true`): set `false` to disable it.
- Paired PostgreSQL + object capture/restore CLI under `tools/backup/`.
- Recipient-bound FCM payloads and one push per physical device.
- Prometheus storage meters (`nexachat.storage.*`) with bounded labels.

**Changed**

- `PATCH /auth/password` answers `409 NO_PASSWORD_SET` for Google-only users.
- Writers take advisory locks in a stable order; object bytes are stored before
  the database bind transaction and removed when it rolls back.

**Fixed**

- Prometheus scrape `ClassCastException` on a tagged timer.
- Backup `pg_dump` streaming and PG18 GUC handling.

### Android

**Added**

- Continue with Google on the register and login screens, a complete-profile
  screen after a new Google account, and a sign-in methods card in Profile.
- Room **14** sync checkpoints and atomic v2 page apply with legacy fallback.
- Message and reminder notifications through durable `WorkManager` work.
- v2 semantic color, type, shape, and spacing tokens; shared bubble, composer,
  and state components.

**Fixed**

- Reproduced UI defects (summary chips, unread badge, IME insets, group detail
  states) and the label dialog double observer.
- Sync recovery: duplicate ACK handling, reaction ordering, tracking release.

### Documentation

- Added `docs/backup-recovery.md`, `docs/free-tier-observability.md`,
  `docs/android-sync.md`; manual Render deploy sequence with per-key env updates.

### Security

- Google accounts are keyed by `sub`, never linked by email.
- Backup tools read secrets from env or `*_FILE`, never argv.
- Reminder push payloads carry an opaque `occurrence` instead of `remindAt`.

### Known limitations

- A Google-only account cannot add a phone, set a password, or delete itself in
  this release: in-app deletion needs a password and the email-code deletion
  flow looks accounts up by phone.
- Message summaries are not purged when a message is recalled.

## [1.1.2] - 2026-11-12

Logo-only release: the Note mark replaces the legacy logo on the Android
launcher, splash, sign-in, notifications, and the public website. No in-app
palette, typography, or layout refresh.
See [docs/release-notes/v1.1.2.md](docs/release-notes/v1.1.2.md).

### Android

**Changed**

- Adaptive launcher layers, in-app auth/splash mark, and call notification
  silhouette use the Note vector geometry with dedicated logo color tokens.

### Website

**Changed**

- Favicon, Open Graph image, and `docs/media/logo/` masters carry the Note
  mark; static site accents align with the web Note palette.

## [1.1.1] - 2026-11-12

Patch after `v1.1.0` (server deploy on Render plus a signed Android APK).
See [docs/release-notes/v1.1.1.md](docs/release-notes/v1.1.1.md).

### Server

**Changed**

- Runtime image builds and ships an AppCDS archive; production `JAVA_OPTS`
  on Render load it via `-XX:SharedArchiveFile=/app/application.jsa`
  (`fa6864e9`).

**Fixed**

- Prometheus meters keep tag keys consistent across registrations
  (`6da7a292`).
- Logback defines appenders only inside active Spring profiles, removing
  duplicate-appender warnings at startup (`1acc4135`).
- Multipart upload scratch is created and probed at boot when
  `NEXACHAT_MULTIPART_TEMP_DIR` is set (`fff743ea`).
- S3 storage client call, attempt, connection and socket deadlines are read
  from configuration with validated defaults (`fbcacae5`).

### Android

**Fixed**

- Delta sync runs on realtime reconnect so messages missed in non-active
  conversations appear after network recovery (`09de3ccb`).
- Incoming WebRTC calls restore from the notification after process death
  (`cac1ddd3`).

### Documentation

**Changed**

- Operations and deploy guides describe the live Supabase storage topology,
  direct Neon endpoint, multipart scratch path, JVM/AppCDS measurements and
  S3 timeout configuration (`50e73120`, `ad063364`, `846e5431`, `d72396d6`,
  `fa85c8ec`, `b4729b67`, `b4e113c3`).
- Known-limitations retention wording matches shipped configuration
  (`cab7a54c`).

## [1.1.0] - 2026-11-11

First public Android release (`v1.1.0`), distributed as a signed APK via
GitHub Releases and `site/download.html`.

### Server

**Added**

- Presence (`V4__presence.sql`), message edit/delete/search, user settings,
  1-to-1 call signaling and call history (`V5__calls.sql`), conversation
  summaries (`V6__conversation_summaries.sql`), receipts and delta sync
  (`V7__p0_receipts_sync_fcm.sql`).
- Attachment BlurHash and trigram message search
  (`V8__attachment_blurhash_search_trigram.sql`); HTTP byte-range attachment
  downloads; conversation RAG with the
  `POST /api/v1/conversations/{id}/ask` endpoint (`V9__conversation_rag.sql`).
- Reaction events, request idempotency and user device tokens
  (`V10__reaction_events_idempotency_device_tokens.sql`).
- Access-token revocation cutoff (`V11__user_access_token_cutoff.sql`) on
  logout, logout-all and password change.
- Per-member mute/archive (`V12`), per-conversation AI opt-out (`V13`),
  recurring reminders (`V14`), group polls (`V15`), per-member pin and seen
  privacy (`V16`), group mentions and `mentionAll` (`V17`), private labels
  (`V18`), and group invite links with join requests and approval (`V20`).
- Conversation archive browsing for media, files and links; reaction delta
  cursors; durable reminder claims; versioned poll change feed; retention
  cleanup jobs and indexes (`V22`).
- Self-service account deletion and tombstoning (`V26__user_account_deletion.sql`)
  via `DELETE /api/v1/users/me`, releasing the phone number for re-registration,
  invalidating active sessions, promoting group admin successors, and anonymizing PII.
- Email OTP account recovery and password reset (`V27__account_recovery_email_otp.sql`).
- Google Sign-In with link-first account association, single-use link nonces,
  and ID token verification against Google JWKS (`V28__google_signin.sql`).
- AI summary consent epoch (`V29__user_ai_summary_consent_epoch.sql`) immediately
  invalidating cached answers and in-flight responses on consent revocation.
- Opt-in Firebase auth bridge and server-side authorization mirroring to Firebase
  RTDB (`control/presencePrivacyVersion` v2).
- WebRTC 1-to-1 call status projection to Firebase RTDB (`callStatus/<callId>`)
  with lease expiration.
- Avatar orphan sweep coordinator (`AvatarOrphanSweepCoordinator`) for scheduled
  cleanup of unreferenced avatar objects with fail-closed safety.
- FCM HTTP v1 delivery with an OAuth2 service-account flow, token caching,
  bounded retries and a mock gateway for local/test runs.
- Conversation summary engines: local extractive, OpenAI-compatible cloud
  provider (Gemini), optional fallback provider and a local Ollama/vLLM tier.
  The extractive tier makes no external call; the cloud and server-model tiers
  require the requesting user's consent and honor each participant's
  per-conversation opt-out. An author's account-level `aiSummaryConsent` is not
  checked for summaries requested by someone else.
- RAG retrieval with hybrid BM25/vector ranking, reciprocal-rank fusion, MMR
  diversification, answer caching and admin-only aggregate telemetry.
- Actuator health groups, metrics, Prometheus exposure, JSON logging, request
  IDs, slow-repository warnings and bounded job metrics.
- Security controls: CORS allow-list, security headers, JSON and chunked-body
  size limits, credential and feature rate limits, trusted-proxy CIDRs,
  admin-phone gating for non-health actuator endpoints, and STOMP SEND/ACK
  rate limits.
- Keyset inbox pagination, batched member-flag reads for delta sync, partial
  delta-sync indexes (`V19`), and removal of an unused inbox index (`V24`).

**Changed**

- Server runs on Java 21, Spring Boot 3.4 and PostgreSQL 16 with Flyway
  validation and `ddl-auto: validate`.
- Uploads keep a 25 MB ceiling; text messages are capped at 10,000
  characters; recalls default to a 24-hour author window.
- FCM, summary providers, RAG embeddings and retention jobs are opt-in and
  default to local or disabled behaviour.
- Refresh token owner lookups re-indexed via composite index on
  `(user_id, id, revoked_at)` (`V30__refresh_token_user_index.sql`), replacing
  the partial index.

**Fixed**

- Soft-deleted messages no longer inflate inbox unread counts.
- Inbox and delta sync handle membership loss, deleted last messages, page
  limits, server time and inaccessible conversations consistently.
- Reaction and pin idempotency reserve keys before mutating state; reaction
  delta cursors serialize correctly.
- Call ring timers re-arm, call state rolls back when history persistence
  fails, and live presence is withdrawn when visibility is revoked.
- Group avatars stay on the application origin; invite approval and rotation
  races are serialized; FCM payloads exclude message content and device
  tokens.
- Message recall removes mentions, reactions and pins, hides the message from
  search and archive, and purges the conversation's cached RAG answers;
  editing a message purges cached answers and its stored embedding.
- Attachment uploads reject active markup disguised as text and case-variant
  storage keys; oversized bodies are rejected before buffering.
- Lock ordering and deadlock prevention: acquired Firebase bridge locks in stripe
  order, kept RAG backfill and avatar orphan sweep advisory locks on dedicated
  pooled connections, and serialized account deletion with attachment uploads.
- STOMP token revocation serialized with outbound message delivery to prevent
  frame leaks to revoked sessions.
- In-flight ask request stampede coalesced; prompt JSON encoding secured against
  injection; Unicode phone number redaction in summary and RAG pipelines.
- Auto-configured HTTP client pinned to JDK HttpClient without unbounded background
  retry.
- Rate-limit IT isolation using injected time sources to eliminate integration
  test flakiness.
- Avatar reference queries and summary deletion races protected against concurrent
  account deletions.

**Security**

- Production startup rejects the repository development JWT secret, weak
  secrets lacking at least eight distinct characters, and access tokens
  without the expected issuer/audience.
- Password registration rejects values whose UTF-8 form exceeds the bcrypt
  input limit.
- STOMP CONNECT requires a valid token; subscriptions and receipt ACKs are
  membership-checked; expired sessions stop receiving frames.
- Attachment downloads stay member-scoped and carry `nosniff`;
  `Content-Disposition` is explicit.
- Persistent administrative kill switch for Firebase realtime bridge
  (`control/bridgeDisabled`).
- Hardened email OTP verification: rate-limited requests, single-use tokens,
  automatic purge during account deletion, and invalidation of pending OTPs.
- Ephemeral presence privacy rules in Firebase RTDB (presence v2 direct and group
  reader edge scoping).
- Google JWKS fetch bounded with an absolute deadline.

### Android

**Added**

- Complete Java 21 client on XML Views, Material 3, Hilt, RxJava 3 and Room
  v13 (exported schemas 6-13, explicit migrations).
- Auth, contacts, direct and group chat, group administration, media
  attachments, voice notes, edit/delete/recall, search, replies, reactions,
  pins, forwarding, mentions, labels, mute/archive, reminders, polls, invite
  links, archive browsing and seen-privacy settings.
- Google Sign-In with Credential Manager / Google ID, link-first account binding
  in Profile Settings, and link-scoped nonce validation.
- Forgot password and account recovery flow with email OTP.
- Recovery email settings in Profile (add, verify, delete).
- In-app self-service account deletion flow with confirmation and session cleanup.
- Opt-in Firebase realtime presence, typing indicators, and call status display
  on the active call screen.
- Offline-first messaging: Room outbox with optimistic sends, durable
  reaction/pin actions, delta sync with tombstones and gap handling, and
  WorkManager draining.
- Realtime STOMP client with reconnect backoff, parked retry, foreground and
  network-regain re-arm, typing indicators and delivery/read receipts.
- WebRTC 1-to-1 audio calls: signaling, ICE configuration, call history,
  foreground service, incoming/outgoing/dark call screens, call log and
  network-roaming recovery.
- Conversation summaries and "Ask this chat" UI, including consent controls
  and source citations.
- FCM token registration and sync wake-up handling; App Links filter with
  `autoVerify` for `https://nexachat.io.vn/join/<token>` plus the
  `nexachat://` fallback. The Play signing SHA-256 must still be appended to
  `assetlinks.json` before Play-distributed installs verify.
- Media pipeline: image compression with EXIF orientation and GPS scrubbing,
  BlurHash placeholders, authenticated Glide disk cache and range-backed
  media viewing.
- EN/VI string parity, per-app locale switching, light/dark/system themes,
  large-font and accessibility fixes, rotation and process-death restore.
- Material 3 and Midnight Obsidian/Electric Violet visual refresh, launcher
  icon, adaptive branding and updated screenshots.

**Changed**

- Release builds enable R8 full mode and resource shrinking, with keep rules
  for Moshi DTOs, Retrofit interfaces, Room migrations, Hilt, WorkManager,
  Media3, Glide, WebRTC, Firebase RTDB models, call status, presence v2, and
  Google ID DTOs.
- Release networking rejects cleartext; debug and `localSmoke` variants keep
  LAN exceptions. Backups are disabled and release logging excludes tokens
  and payload bodies.
- Release versioning uses `MAJOR.MINOR.PATCH` tags and
  `versionCode = MAJOR * 1,000,000 + MINOR * 1,000 + PATCH`.
- Accessibility & UI polish: live region announcements on error banners, IME
  action handling, improved 4.5:1 button contrast, and Vietnamese translation
  parity across auth and settings screens.

**Fixed**

- Token refresh is single-flight and fenced by a session epoch; transient or
  throttled refreshes no longer sign the user out.
- Realtime session epoch fences: serialized connect/disconnect, attempt-scoped
  socket cleanup, and invalidation of disposed STOMP callbacks to eliminate stale
  token and session leaks.
- Authentication secrets: cleared and redacted in-memory passwords and tokens;
  excluded passwords from saved instance state.
- Refresh token terminal recovery: routed expired or unrecoverable refresh attempts
  cleanly to the sign-in screen.
- Offline outbox rows are owner-scoped and survive account cache clears; the
  same account keeps queued messages while another account cannot see them.
- Inbox first-page refresh no longer drops conversations created while a
  request is in flight, and terminal delivery clears the in-flight guard.
- Reconnect loops re-arm after network regain and foregrounding instead of
  parking permanently.
- Chat scroll-follow, unread banners, landscape layout, mention picker,
  call-screen restoration, bubble contrast and large-font rendering behave
  consistently.
- Release R8 keeps reflective summary, call, poll, label, invite, reminder
  and archive models; release-only `ClassCastException` paths were removed.
- Count strings use `getQuantityString`; EN/VI placeholders and apostrophes
  are checked.

### Site

**Added**

- Static landing site for `nexachat.io.vn` with bilingual content, 404 page,
  robots/sitemap, favicon and a `/join/*` fallback page that opens the app or
  shows install instructions.
- APK download page (`site/download.html`) with direct release downloads, system
  requirements (Android 8.0+), SHA-256 verification instructions, and sitemap entry.
- Web-based account deletion flow (`site/delete-account.html`) for lost-device
  recovery via email OTP, validated against API contracts.
- Terms of Service (`site/terms.html`), updated Privacy Policy (`site/privacy.html`),
  and JSON-LD structured metadata aligned with Play Store Data Safety declarations.
- JavaScript unit tests (`test/site-delete-account-js`) and CI validation for
  deletion form behaviour, WCAG AA contrast, and CSP compliance.
- `/.well-known/assetlinks.json` with the upload-key SHA-256 fingerprint and
  the `com.nexachat` package name.
- Vercel configuration for security headers (CSP, HSTS, `nosniff`,
  `Referrer-Policy`, `X-Frame-Options`), assetlinks `Content-Type`, `www`
  redirect, `/join/*` rewrite and an `ignoreCommand` that builds safely when
  `VERCEL_GIT_PREVIOUS_SHA` is missing.
- `site/check.py` validation for JSON, assetlinks, headers, token rules,
  external requests and inline script/style.
- Domain tooling (`tools/domain/check-domain.sh`) for DNS, TLS and App Links
  verification, plus the domain/App Links runbook.

**Fixed**

- Vercel `ignoreCommand` fixed to prevent Git fatal exit 128 on shallow clones
  and kept within Vercel's 256-character schema limit.

### Operations and tooling

**Added**

- Signed APK release workflow (`.github/workflows/android-release.yml`) triggered
  on version tags (`v*`) to build, sign, checksum, and publish releases to GitHub
  Releases.
- CI API contract check (`tools/check-api-contract.py`) integrated into
  `repo-hygiene` with self-test verification.
- Offline Firebase RTDB rules contract verification in CI.
- Multi-stage `server/Dockerfile` with pinned base-image digests, non-root
  `nexachat` user, layered Spring Boot extraction, health check and JVM
  `MaxRAMPercentage`/`ExitOnOutOfMemoryError` flags.
- Full-stack `docker-compose.yml` with PostgreSQL 16, health-gated startup,
  persistent volumes and retention/FCM/summary environment passthrough.
- CI jobs for Maven verify with PostgreSQL 16, Android unit tests/lint/debug
  assembly, repository hygiene (Markdown links, site, string checks,
  actionlint, shell syntax), secret scanning and GHCR server-image publishing
  on `main`.
- Release tooling: `tools/sign-release.sh` (HTTPS URL guard, version
  derivation, zipalign, apksigner verification), PowerShell twin,
  `tools/release-version.sh`, `tools/release-url-guard.sh` and
  `tools/check-release-r8-mapping.py`.
- Backup/restore scripts, LAN demo bootstrap scripts, seed script and
  end-to-end drivers under `tools/e2e/`.

**Changed**

- Production profile validates required datasource, JWT and storage values,
  validates Flyway checksums, disables open-in-view, caps Hikari at 10
  connections per instance, enables compression and exposes only health by
  default.
- Schedulers use database claims or advisory locks and bounded batches so
  multiple instances do not duplicate work.
- Deployment configuration forwarded Firebase realtime settings, service-account
  key mounts, and avatar orphan sweep settings to Docker Compose and deployment
  templates.

**Fixed**

- Container health checks target liveness; readiness includes database,
  Flyway and disk space.
- Compose forwards retention variables; the server image runs as UID/GID
  10001 and stops on OOM.
- Release signing rejects non-HTTPS API URLs except an explicit local smoke
  build; version tests reject malformed or out-of-range tags.

### Documentation

**Added**

- Release notes for v1.1.0 (`docs/release-notes/v1.1.0.md`) in Vietnamese and English.
- Firebase rollout guide (`docs/firebase-rollout.md`) and updated realtime
  architecture (`docs/firebase-realtime.md`) covering presence privacy v2,
  call status paths, and safe rollout order.
- Play Store Data Safety mapping (`docs/play-store-data-safety.md`) updated for
  Firebase RTDB ephemeral transport, recovery email, and account deletion.
- Architecture, REST API contract, STOMP protocol, security, operations,
  testing, calls, AI summary, RAG, known-limitations and design-system
  documentation.
- Domain/App Links runbook, media screenshot index, release signing guide and
  contribution guide.

**Changed**

- README, architecture, API contract, protocol, security, operations and
  testing pages reconciled with the shipped Java 21 Android client, Flyway
  V1-V20/V22/V24–V30, Room v13 and the current security controls.
- Documentation now records FCM delivery, live LLM/embedding calls, call
  audio and device-only test scopes that were not verified in CI.

## [1.0.0] - 2026-11-04

First tagged release (`v1.0.0`).

### Server

- Registration/login with E.164 phone numbers, bcrypt cost 12, JWT access
  tokens with rotating refresh tokens and reuse detection, logout,
  logout-all and password change.
- Contacts, phone search, direct and group conversations, group roles,
  system messages and group avatars.
- STOMP realtime chat, offline outbox, delivery/read receipts and typing
  indicators.
- Media upload/download with magic-byte sniffing, a 25 MB cap and
  member-checked access.
- Reply quotes, emoji reactions and pinned messages.
- Multi-stage Docker image, Compose stack and LAN demo scripts.

### Android

- Phone registration/login, contacts, direct and group chat, offline send,
  read receipts and media messages.
- EN/VI locale switching, light/dark themes, rotation-safe state and
  Material 3 screens.
- Reply, reactions, pins, attachment viewer and sharing.

### Operations

- CI for server tests, Android unit tests/lint and committed-secret scanning.
- Signed APK pipeline and local upload-keystore setup.

### Documentation

- Architecture diagrams, REST/STOMP contracts, security notes, operations
  guide and UI design documentation.

[Unreleased]: https://github.com/JasonTM17/Nexachat_AppJ/compare/v1.1.1...HEAD
[1.1.1]: https://github.com/JasonTM17/Nexachat_AppJ/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/JasonTM17/Nexachat_AppJ/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/JasonTM17/Nexachat_AppJ/releases/tag/v1.0.0
