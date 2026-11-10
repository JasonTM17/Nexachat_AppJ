# Play Store data-safety and listing facts

Source: app and server code at `42831fe` (2026-11-10); site inventory includes
the privacy and terms pages merged with this document. This is a code-derived
worksheet for the Google Play Data safety form and the privacy policy. It is not
a legal opinion and not a submitted declaration. Answers describe the release
build with the network security policy in `android/app/src/main/`; the `debug`
and `localSmoke` source sets are development-only and are not shipped.

Each answer is marked:

- **Supported by code** — the repository implements the described behaviour.
- **Not supported** — the repository has no code path for that collection.
- **Needs owner decision** — the answer depends on the shipped build, operator
  configuration, or a product/legal decision that the repository cannot make.

The form's "collected" means data transmitted off the device and stored. Data
that exists only in app-private storage is listed separately in
[On-device storage](#on-device-storage).

Paths below are repository-relative. Unless a path starts with `android/` or
`server/`, server Java paths are under `server/src/main/java/com/nexachat/`,
server resources are under `server/src/main/resources/`, and Android paths are
under `android/app/src/main/`.

## Listing facts from the build

| Fact | Value | Evidence | Status |
|---|---|---|---|
| App name | NexaChat | `android/app/src/main/res/values/strings.xml:3`, `android/app/src/main/res/values-vi/strings.xml:3` | Supported by code |
| Release application ID | `com.nexachat` | `android/app/build.gradle:143` | Supported by code |
| Debug / localSmoke IDs | `com.nexachat.debug`, `com.nexachat.localsmoke` | `android/app/build.gradle:192`, `:203` | Supported by code |
| minSdk / targetSdk / compileSdk | 26 / 36 / 36 | `android/app/build.gradle:140-145` | Supported by code |
| Default version name / code | `1.0.0` / `1`, overridden by release properties | `android/app/build.gradle:123-136` | Supported by code |
| Locales | English (default) and Vietnamese | `android/app/src/main/res/values/`, `android/app/src/main/res/values-vi/`; `core/common/AppLocaleManager.java:19-24` | Supported by code |
| ABI filters | `arm64-v8a`, `x86_64` | `android/app/build.gradle:157-159` | Supported by code |
| Ads / in-app purchases | No ad, billing, or purchase SDK in the dependency catalog | `android/gradle/libs.versions.toml:40-112`, `android/app/build.gradle:319-387` | Supported by code (owner still declares "No ads") |
| Analytics / crash SDK | No analytics or crash SDK is declared or called by app code; `firebase-messaging` is the only Firebase module. Firebase SDK-internal telemetry is not visible in this repository. | `android/gradle/libs.versions.toml:100-102`, `android/app/build.gradle:375-376`; imports are Firebase core (`notification/FirebaseConfigDetector.java:7`), `firebase-messaging` (`notification/NexaFirebaseMessagingService.java:6-7`), and GMS `Tasks` (`notification/FirebaseTokenSource.java:4`) only; no analytics or crash SDK | Supported by code |
| Backend required | Yes; the app is a client for a NexaChat REST/STOMP server | `core/common/AppConfig.java:8-19`, `core/network/NetworkModule.java:73-78` | Supported by code |
| Release transport | HTTPS/WSS only; cleartext is blocked by the release network policy | `android/app/src/main/res/xml/network_security_config.xml:10-16`; `tools/release-url-guard.sh:78-88` | Supported by code |
| Account creation | Yes, by phone + password | `server/src/main/java/com/nexachat/auth/AuthController.java:40-45`; Android auth feature | Supported by code |
| Account deletion | No in-app or REST path | `tools/fcm-live-check/README.md:51-52`; no deletion endpoint in `server/**/Controller.java` | Not supported |

## Data the app sends to the NexaChat server

The following data leaves the device and is stored by the server unless a
retention job or an explicit delete action removes it.

| Data | What is sent / stored | Evidence | Status |
|---|---|---|---|
| Account identity | E.164 phone number, password, display name; password is BCrypt-hashed server-side and never returned | `auth/dto/RegisterRequest.java:12-16`; `user/User.java:33-42`; `config/SecurityConfig.java:66` | Supported by code |
| Session device label | The `User-Agent` request header is stored as `refresh_tokens.device_label`; refresh tokens are stored only as SHA-256 hashes | `auth/AuthController.java:28`, `:42-56`; `db/migration/V1__init.sql:25-34`; `auth/TokenService.java:133-137` | Supported by code |
| Profile name and avatar | Display name, avatar image bytes; avatar URL is part of the public profile response | `user/dto/UpdateProfileRequest.java:9-11`; `attachment/AvatarController.java:50-60`; `user/dto/UserResponse.java:11-15` | Supported by code |
| Phone discovery | The user types a phone number or display-name prefix; the server returns matching profiles including the phone field | `data/ContactsRepository.java:120-127`; `user/UserController.java:47-55`; `user/UserService.java:77-94`; `user/dto/UserResponse.java:11` | Supported by code |
| Contacts | A user can add another user by phone; the server stores the contact edge and the owner's private alias | `db/migration/V1__init.sql:41-48`; `contact/ContactService.java` | Supported by code |
| Messages | Text, message type, client message id, reply target, mentions, edit/delete state, timestamps | `db/migration/V1__init.sql:88-101`; `db/migration/V17__message_mentions.sql`; `message/MessageService.java:1083-1089` | Supported by code |
| Attachments | File bytes and metadata: original name, MIME, size, width/height, duration, BlurHash; stored on the file volume | `db/migration/V1__init.sql:108-120`; `attachment/AttachmentService.java`; `application.yml:85-86` | Supported by code |
| Read / delivered watermarks | Read and delivered sequence watermarks | `db/migration/V7__p0_receipts_sync_fcm.sql`; `docs/websocket-protocol.md` | Supported by code |
| Reactions, pins | Emoji reactions, reaction events, pinned message ids | `db/migration/V3__interactions.sql`, `V10__reaction_events_idempotency_device_tokens.sql` | Supported by code |
| Groups and invites | Group title, members, roles, invite-token hashes, join requests, bans | `db/migration/V20__group_invite_links.sql`; `docs/api-contract.md:127-128` | Supported by code |
| Polls | Question, options, votes, close state, versioned change feed | `db/migration/V15__group_polls.sql` | Supported by code |
| Reminders | Title, time, repeat rule, delivery state | `db/migration/V14__conversation_reminders.sql` | Supported by code |
| Labels | Private label name/colour and per-conversation assignments | `db/migration/V18__conversation_labels.sql` | Supported by code |
| Presence | Last-seen timestamp, online state; `showPresence` / `showSeen` settings | `db/migration/V4__presence.sql`; `user/User.java:47-60`; `docs/known-limitations.md:14-15` | Supported by code |
| Calls | Invite/accept/reject/end, SDP and ICE payloads; SDP/ICE are relayed in memory, not persisted; call metadata is stored | `docs/calls.md:45-46`, `:115-116`; `db/migration/V5__calls.sql` | Supported by code |
| Push registration | FCM registration token and platform; only when Firebase is configured and a session exists | `notification/DeviceTokenSyncExecutor.java:65-101`; `notification/DeviceTokenService.java:31-62`; `db/migration/V7__p0_receipts_sync_fcm.sql:15-25` | Supported by code |
| AI request data | Summary scope or RAG question, and, when external AI is enabled, redacted message text sent to the configured provider | `application.yml:148-210`; `docs/ai-summary.md:90-95`; `docs/rag.md:77-112` | Supported by code; external call is owner-configured |

The app does **not** read the device address book: the manifest has no
`READ_CONTACTS`, and no `ContactsContract` usage exists. Discovery uses a typed
phone number or display-name prefix (`data/ContactsRepository.java:120-127`).
The manifest also has no location, camera, storage, phone-state, SMS, or calendar
permission (`android/app/src/main/AndroidManifest.xml:6-16`).

## Data safety form: collection and security

| Form question | Answer from this code | Evidence | Status |
|---|---|---|---|
| Does the app collect or share any required user data type? | Yes. Account, message, media, interaction, call-signaling metadata, and device-token data reach the server. | See the two tables above and `application.yml` configuration. | Supported by code |
| Is all collected user data encrypted in transit? | Yes for the release build: cleartext is disabled and signed releases require an `https://` API base URL. TLS termination itself is a deployment responsibility that the owner must confirm. | `android/app/src/main/res/xml/network_security_config.xml:10-16`; `tools/release-url-guard.sh:78-88`; `docs/operations.md:261-266` | Supported by code |
| Do you provide a way for users to request that their data be deleted? | No self-service path exists. There is no account-deletion endpoint, Android screen, or deletion URL page, and retention jobs never delete user rows. The owner must add one before submission. | `tools/fcm-live-check/README.md:51-52`; `docs/operations.md:706-709`; no deletion route in `server/**/Controller.java` | Not supported |

## Data safety form: data types

The purpose labels below use Play's common purposes: **App functionality**,
**Account management**, **Developer communications**, and **Fraud prevention,
security, and compliance**. No analytics, advertising, or personalisation
purpose applies.

| Play data type | Handled by NexaChat | Required or optional | Purpose | Status |
|---|---|---|---|---|
| Personal info → Name | Display name, shown to other users | Required at registration | Account management; app functionality | Supported by code |
| Personal info → Phone number | E.164 number; used for login, returned in user profiles, and included in display-name search results | Required at registration | Account management; app functionality | Supported by code |
| Personal info → User IDs | Internal account UUID | Required | Account management; app functionality | Supported by code |
| Messages → In-app messages | Text messages, edits, tombstones, reply/mention metadata | Required for messaging | App functionality | Supported by code |
| Photos and videos → Photos | Avatar image and image attachments chosen by the user | Optional (user chooses) | App functionality | Supported by code |
| Photos and videos → Videos | Video attachments chosen by the user | Optional | App functionality | Supported by code |
| Audio files → Voice or sound recordings | Voice messages recorded with `RECORD_AUDIO`; no call recording code exists | Optional | App functionality | Supported by code |
| Files and docs | Arbitrary file attachments up to 25 MB | Optional | App functionality | Supported by code |
| Contacts | Device address book is **not** read. A manually typed phone number can create a private contact edge. Whether Play treats that as "Contacts" is a form-reading decision. | Optional | App functionality | Needs owner decision |
| App activity → Other user-generated content | Reactions, pins, labels, polls and votes, reminders, group invites, call history | Optional | App functionality | Supported by code |
| App activity → App interactions | No analytics or interaction-event SDK; the app does not record taps/screens for analytics | — | — | Not supported |
| App activity → In-app search history | Search queries are sent as request parameters; no search-history table or local search history exists, so no history is stored | Optional when used | App functionality (transient) | Not supported |
| Location | No location permission or location API. Chat images are normally compressed with location metadata removed; a non-privacy compression failure falls back to the original, and avatar images and video attachments are stored as selected without that scrubber (see owner decisions). | — | — | Not supported |
| Financial info | No payment, billing, or financial feature | — | — | Not supported |
| Health and fitness | No health API or permission | — | — | Not supported |
| Calendar | No calendar permission or event access; reminders are in-app only | — | — | Not supported |
| Web browsing | No browser or WebView history | — | — | Not supported |
| SMS / MMS | No SMS permission or message access | — | — | Not supported |
| App info and performance → Crash logs / diagnostics | No Crashlytics/Sentry/analytics SDK; server logs are operator-side and are not device diagnostics | — | — | Not supported |
| Device or other IDs → FCM registration token | Token stored on the server and used to address push messages | Optional (push) | App functionality (push) | Supported by code |
| Device or other IDs → Firebase installation ID | The Firebase SDK maintains its own installation identity; app code does not read it | Optional (push) | App functionality (push) | Needs owner decision (third-party SDK disclosure) |

## Data shared with third parties

Play distinguishes data processed by a service provider from data shared for a
third party's own purposes. The repository only shows the following outbound
paths; the owner must confirm the shipped configuration and the processor terms.

| Recipient | Data | When it leaves the deployment | Evidence | Status |
|---|---|---|---|---|
| Google Firebase Cloud Messaging | Device registration token; data-only payload: conversation/message ids, sequence, sender display name, call ids/names/timestamps, reminder ids and repeat rule. Message text and attachment bytes are not in the payload, but the sender's display name is. | The client contacts Firebase and obtains a token when the build has `google-services.json`; the server sends a payload only when `FCM_ENABLED=true` with a service account | `android/app/build.gradle:9-17`; `application.yml:211-222`; `notification/DefaultFcmPushService.java:65-77`, `:89-98`; `reminder/FcmReminderPushSender.java:29-31`, `:67-71` | Needs owner decision for the shipped build |
| External summary LLM | Redacted eligible message text and the summary request; sender names become `U1..Un`, message ids and phone/email/URL tokens are removed | Only when an operator configures a summary key, the provider is enabled, and the requesting user has `aiSummaryConsent=true`. Text from other participants in the window is included unless they opted out in that conversation; their own `aiSummaryConsent` flag is not checked. Primary is Gemini-compatible; optional fallback is DeepSeek-compatible; a local server model can be configured instead. | `application.yml:148-175`; `docs/ai-summary.md:90-95`; `docs/known-limitations.md:33`; `docs/rag.md:77-112` | Needs owner decision; default config makes no external call |
| External embedding provider | Redacted question and candidate message text for embedding | The ask path uses an external provider only when the requesting user consents. The background backfill uses the deployment's primary provider (external when configured) and filters only per-conversation opt-outs, not each author's `aiSummaryConsent`; enabling it therefore sends non-opted-out message text without per-author consent. The default `lexical` provider stays on the server. | `application.yml:176-210`; `docs/rag.md:44-52`, `:77-98`, `:127-133`; `rag/RagEmbeddingService.java:63-75`, `:101-124`, `:140-149`, `:199-201`; `rag/RagEmbeddingProviders.java:48-51` | Needs owner decision; default config is offline |
| Google public STUN | Source IP and ICE candidates during call setup; no message or account content | During a call when the default ICE configuration is used; there is no TURN server. This is a network dependency, not an app data-sharing feature. | `docs/calls.md:115-116`, `:165` | Supported by code |
| Ad, analytics, crash, or social SDKs | None | — | `android/gradle/libs.versions.toml:40-112`; `android/app/build.gradle:319-387` | Not supported |

Other NexaChat users can see the display name, avatar, and phone number returned
by profile/search responses. Any authenticated user can search a display-name
prefix and receive the matching profiles' phone field, not only an exact phone
lookup. That is app functionality, not a third-party share, but the privacy
policy must describe it, and the owner should decide whether to mask or omit the
phone field in name search (`user/UserService.java:89-94`;
`user/dto/UserResponse.java:11`; `data/ContactsRepository.java:125-127`).

The embedding backfill is one AI path where another participant's consent is
not checked. The ask path resolves its provider from the requesting user's
persisted consent (`rag/RagEmbeddingService.java:199-201`); the scheduled
backfill calls `providers.primary()` and excludes only messages whose author
opted out of AI in that conversation (`rag/RagEmbeddingService.java:101-124`,
`:140-149`). If the operator enables an external embedding provider, message
text from every non-opted-out author is sent to that provider even if that author
never set `aiSummaryConsent=true`. This needs a privacy-policy disclosure and a
legal basis, or the operator should keep the default `lexical` provider.

## Data processed only in memory

| Data | Where it lives | Evidence |
|---|---|---|
| Call SDP and ICE payloads | In-memory call registry/relay, not a database table | `docs/calls.md:45-46`; `db/migration/V5__calls.sql` stores only call metadata |
| Typing indicators | STOMP frame only | `docs/websocket-protocol.md` |
| Online presence | In-memory session registry; `users.last_seen_at` is persisted separately | `docs/known-limitations.md:14-15` |
| Rate-limit buckets and client IPs | In-memory bounded buckets; not persisted by the rate-limit code | `docs/security.md:193-198`; `config/ClientIpResolver.java` |
| Raw AI prompts and model output | Not stored; only the validated summary/answer payload is cached | `docs/ai-summary.md:95`; `db/migration/V6__conversation_summaries.sql`, `V9__conversation_rag.sql` |

## On-device storage

| Store | Contents | Protection | Evidence |
|---|---|---|---|
| Room database `nexachat.db` | Messages, conversations, reactions, pins, users, call logs, outbox, offline outbox, cached label ids and preferences | App-private SQLite; not SQLCipher-encrypted | `core/database/DatabaseModule.java:24-38`; `core/database/AppDatabase.java:16-28` |
| Preferences DataStore `nexachat_tokens` | Access and refresh tokens; FCM registration token; recall-window config; sync cursors; locally hidden message ids | Tokens and FCM token: Base64 of `IV || AES-GCM` ciphertext with the key in AndroidKeyStore. Sync cursors, hidden ids and expiry timestamps are plaintext preferences. | `core/storage/TokenStore.java:23-31`, `:365-383`, `:437-485`; `core/storage/AesGcmTokenCipher.java:15-35`; `core/storage/StorageModule.java:19-30` |
| SharedPreferences `nexachat_settings` | Theme mode | App-private plaintext | `data/SettingsRepository.java:36-37`, `:105-107` |
| SharedPreferences `nexachat_locale` | App locale | App-private plaintext | `core/common/AppLocaleManager.java:19-24`, `:110` |
| `cacheDir/attachments` | Downloaded attachment files named by id | App-private cache | `data/AttachmentRepository.java:207-214`, `:345-346` |
| Glide and OkHttp caches | Image thumbnails and HTTP responses; combined ceiling 2 GiB | App-private cache | `core/media/MediaCacheConfig.java:8-16`; `core/media/MediaGlideModule.java:19-23`; `core/network/NetworkModule.java:49` |
| `cacheDir/media-uploads` and voice files | Temporary compressed images and voice recordings | App-private cache | `core/media/ImageCompressor.java:28`; `feature/chat/voice/VoiceRecorder.java:74-79` |
| Backup / device transfer | All app data excluded | `allowBackup=false`; cloud-backup and device-transfer rules exclude every private domain | `android/app/src/main/AndroidManifest.xml:20-29`; `android/app/src/main/res/xml/backup_rules.xml:4-12`; `android/app/src/main/res/xml/data_extraction_rules.xml:3-26`; `android/app/src/test/java/com/nexachat/security/AndroidPrivacyConfigurationTest.java:24-58` |

### Local logout and account boundaries

- `AccountDataCleaner.clearForLogout` clears the Room cache while preserving the
  ordinary outbox, and `clearAllTables()` when logout-all explicitly removes
  queued sends (`data/AccountDataCleaner.java:64-113`). The FCM token and pending
  unregister request stay encrypted in DataStore so a later authenticated sync
  can unregister the device (`data/AuthRepository.java:167-207`;
  `notification/DeviceTokenRegistrationManager.java:69-81`).
- `TokenStore.clear()` removes access/refresh tokens, recall config, hidden ids
  and sync cursors, but not the FCM token or a pending unregister
  (`core/storage/TokenStore.java:487-517`).
- The media caches in `cacheDir` are **not** cleared by `AccountDataCleaner` or
  `TokenStore.clear()`. Downloaded attachments, thumbnails and HTTP cache
  entries can outlive logout until Android evicts the cache or the user clears
  app data. This is a product/privacy decision, not an implemented wipe.
- Uninstalling the app or clearing app data is the only complete local wipe
  documented by the code.

## Retention and deletion

All server retention jobs are disabled by default; the global switch is also
off and dry-run is on (`application.yml:90-145`; `docs/operations.md:631-638`).
Enabling a job does not delete messages, conversations, or users;
`docs/operations.md:706-709` states those rows are never removed by these jobs.

| Data | Default retention behaviour | Evidence |
|---|---|---|
| Account, messages, conversations, contacts, labels, memberships | No automatic deletion; message recall is a soft delete, not a row delete | `docs/operations.md:706-709`; `message/MessageService.java:391-434` |
| Message tombstones | Row remains; optional job after 30 days clears content, mention flags and embeddings. Attachments remain unless the attachment job runs. | `application.yml:129-131`; `docs/known-limitations.md:18`, `:51` |
| Attachments | Optional job: unbound uploads after 24 h, attachments of soft-deleted messages after 30 days | `application.yml:125-128`; `docs/operations.md:684-686` |
| Refresh tokens | Hash rows remain until the optional 30-day cleanup runs | `application.yml:110-112`; `db/migration/V1__init.sql:25-34` |
| Device tokens | Optional cleanup after 180 stale days | `application.yml:113-115`; `docs/operations.md:676-677` |
| Fired one-shot reminders / ended calls | Optional cleanup after 30 / 365 days | `application.yml:116-124`; `docs/operations.md:678-683` |
| Polls | Optional job redacts aged payloads and removes options/votes after 365 days; the poll tombstone remains | `application.yml:119-121`; `docs/operations.md:680-681` |
| Summaries and RAG caches | 24 h TTL by default; expired rows are only purged when the optional summaries/rag-cache job is enabled | `db/migration/V6__conversation_summaries.sql`; `V9__conversation_rag.sql`; `application.yml:134-145` |
| Derived copies of message text | Summary-cache rows can contain text from later-recalled messages until their 24 h expiry; the content hash changes on edit/delete, so the row is not replayed. The RAG ask cache is purged for the conversation on recall. Expired rows remain until the optional job runs. | `message/MessageService.java:391-434`; `rag/MessageRecallAskCacheListener.java:9-26`; `summary/SummaryService.java:75`, `:96`; `docs/rag.md:99-108`; `db/migration/V6__conversation_summaries.sql`, `V9__conversation_rag.sql`; `application.yml:134-145` |
| Server backups | `demo/backup.sh` creates SQL dumps on demand; no backup retention period is defined in the repository | `docs/operations.md:535-560` |

Deletion actions that exist:

- A message author may recall their own message within 24 h; a current group
  admin may recall any group message. The row becomes a tombstone, mentions,
  reactions and pins are removed, and attachment bytes remain by default
  (`message/MessageService.java:391-434`; `message/MessageRecallProperties.java:14-15`).
- Logout clears the local Room cache and tokens as described above; it does not
  delete server data.
- Logout-all clears all local tables, including the outbox
  (`data/AuthRepository.java:195-202`; `data/AccountDataCleaner.java:109-112`).

Deletion actions that do **not** exist:

- No account-deletion endpoint or in-app screen. `tools/fcm-live-check/README.md:51-52`
  explicitly records that the public API has no account-deletion endpoint.
- No self-service data-export or "delete my data" request page.
- No account-deletion page or request form under `site/`. The site does host a
  privacy policy (`site/privacy.html`) and terms (`site/terms.html`); the rest
  is the landing page, join page, 404 page and static assets
  (`site/index.html`, `site/join/index.html`, `site/404.html`,
  `site/robots.txt`, `site/sitemap.xml`, `site/.well-known/assetlinks.json`,
  `site/style.css`, `site/favicon.svg`, `site/vercel.json`).

This is a Play policy decision the owner must resolve before shipping an app
that creates accounts.

## Encryption

| Path | Current behaviour | Evidence |
|---|---|---|
| Android release traffic | Cleartext HTTP/WS is disabled in the release network policy; only the system CA store is trusted | `android/app/src/main/res/xml/network_security_config.xml:10-16`; `android/app/src/test/java/com/nexachat/security/AndroidPrivacyConfigurationTest.java:60-80` |
| Signed release build | `tools/release-url-guard.sh` rejects an `API_BASE_URL` that is not `https://` | `tools/release-url-guard.sh:78-88` |
| Debug / localSmoke | Cleartext is permitted only in these development variants | `android/app/src/debug/res/xml/network_security_config.xml:10-12`; `android/app/src/localSmoke/res/xml/network_security_config.xml:9-11`; `android/app/src/test/java/com/nexachat/security/AndroidPrivacyConfigurationTest.java:82-113` |
| Server edge | TLS is expected to terminate at a reverse proxy; the Compose stack itself is local HTTP | `docs/operations.md:261-266` |
| Server to FCM / LLM | HTTPS endpoints; summary/RAG base URLs reject plain `http://` except loopback | `docs/ai-summary.md:115-122`; `docs/rag.md:63-66` |
| At rest on device | Room and SharedPreferences are not encrypted; session and FCM tokens are Keystore-encrypted | `core/database/DatabaseModule.java:24-38`; `core/storage/TokenStore.java:23-31` |
| At rest on server | PostgreSQL and attachment volume are not encrypted by the app; no end-to-end encryption | `docs/known-limitations.md:58`; `db/migration/V1__init.sql` |

The release policy proves the app cannot silently use cleartext. It does not
prove that a particular production deployment has a valid TLS certificate or
that the server is reachable; that remains an operator check.

## Manifest permissions and user-facing reasons

All permissions are declared in `android/app/src/main/AndroidManifest.xml:6-16`.
There is no `READ_CONTACTS`, location, camera, storage, phone-state, SMS, or
calendar permission.

| Permission | Type | Why the app requests it | Where used | Status |
|---|---|---|---|---|
| `INTERNET` | Normal | Connect to the NexaChat REST/STOMP server | `android/app/src/main/AndroidManifest.xml:6`; all network features | Supported by code |
| `ACCESS_NETWORK_STATE` | Normal | Detect connectivity loss for the offline banner and outbox retry | `android/app/src/main/AndroidManifest.xml:5-7`; outbox sync | Supported by code |
| `RECORD_AUDIO` | Dangerous (runtime) | Record voice messages and provide microphone audio during a call | `feature/chat/ChatFragment.java:459-461`; `call/ui/CallActivity.java:415-437`; `call/ui/IncomingCallActivity.java:297-320`; rationale `android/app/src/main/res/values/strings_call.xml:25` | Supported by code |
| `FOREGROUND_SERVICE` | Normal | Keep an active call's foreground service alive | `call/service/CallForegroundService`; `android/app/src/main/AndroidManifest.xml:76-80` | Supported by code |
| `FOREGROUND_SERVICE_MICROPHONE` | Normal | Declare microphone use by the ongoing call service | `android/app/src/main/AndroidManifest.xml:76-80` (`foregroundServiceType="microphone|phoneCall"`) | Supported by code |
| `FOREGROUND_SERVICE_PHONE_CALL` | Normal | Declare the call foreground-service type | `android/app/src/main/AndroidManifest.xml:76-80` | Supported by code |
| `POST_NOTIFICATIONS` | Runtime (Android 13+) | Show incoming-call and missed-call notifications | `call/ui/CallActivity.java:443-458`; `call/ui/IncomingCallActivity.java:326-341`; `call/service/CallNotifications.java`; rationale `android/app/src/main/res/values/strings_call.xml:28` | Supported by code |
| `USE_FULL_SCREEN_INTENT` | Normal / special access | Show the incoming-call screen over the lock screen | `call/service/CallNotifications.java:85`; `android/app/src/main/AndroidManifest.xml:13` | Supported by code; Play declaration needed |
| `MODIFY_AUDIO_SETTINGS` | Normal | Route call audio to earpiece/speaker and request audio focus | `call/media/WebRtcMediaEngine.java:362-377`; `feature/chat/voice/AndroidVoiceAudioFocus.java` | Supported by code |
| `VIBRATE` | Normal | Vibrate for an incoming call | `call/media/CallRingtonePlayer.java:15`, `:46-53` | Supported by code |
| `WAKE_LOCK` | Normal | Keep the CPU awake briefly while handling an FCM push and while a call uses the proximity lock | `notification/NexaFirebaseMessagingService.java:20-38`; `call/ui/CallActivity.java:551` | Supported by code |

The microphone permission has explicit in-app strings:
`voice_permission_required` for voice messages
(`android/app/src/main/res/values/strings.xml:282` and the Vietnamese
counterpart) and `call_mic_rationale` for calls
(`android/app/src/main/res/values/strings_call.xml:25`). The call screens also
show `call_notifications_rationale` before requesting notification permission
(`android/app/src/main/res/values/strings_call.xml:28`). The app does not
request permission for contacts, location, camera, or external storage.

## Listing and policy items the repository cannot answer

These are owner decisions; do not infer them from code:

1. **Privacy policy URL** — required by Play for an app that creates accounts
   and collects personal data. The site hosts a privacy policy at
   `site/privacy.html` (and terms at `site/terms.html`), but no policy URL is
   configured in the app or repository.
2. **Account deletion** — Play requires an in-app deletion path and a web
   deletion URL for apps that let users create accounts. The repository has
   neither, and retention jobs never delete user rows.
3. **Target audience and content rating** — no target-age declaration or content
   rating questionnaire is in the repository.
4. **Ads declaration** — no ad SDK exists, but the owner must still answer the
   Play "contains ads" question for the shipped listing.
5. **Data safety submission** — the final form answers, contact email, and
   whether data is "shared" or only processed by service providers are legal
   and product decisions. This document is the code map, not the submission.
6. **Store listing facts** — title, short/full description, category, tags,
   screenshots, feature graphic, and app-access instructions for review are not
   in the repository.
7. **Production FCM configuration** — the shipped build may or may not include
   `google-services.json`, and the server may or may not enable FCM. That changes
   the "Device or other IDs" and FCM sharing answers. Firebase SDK-internal
   installation/telemetry behaviour is not visible in app code; check Google's
   current FCM disclosure before finalizing the form.
8. **Production AI configuration** — external summary/embedding providers are
   disabled by default. If the operator enables them, the privacy policy must
   name the provider, the legal basis, and the opt-out path. The embedding
   backfill does not check each author's `aiSummaryConsent` (only the
   per-conversation opt-out), so enabling an external embedding provider is a
   separate legal/privacy decision.
9. **Full-screen intent / foreground-service declarations** — the app declares
   `USE_FULL_SCREEN_INTENT` and phone-call/microphone foreground-service types;
   the owner must confirm the Play Console declarations and policy eligibility.
10. **Manual contact list** — decide whether a user-typed contact edge counts as
    "Contacts" on the form or as ordinary app content.
11. **Embedded media metadata** — chat images are normally scrubbed of location
    metadata, but a non-privacy compression failure falls back to the original
    (`data/AttachmentRepository.java:132-135`), and the avatar path and video
    attachments store the selected file without a scrubber. Decide whether to
    scrub them or disclose the residual risk.
12. **Post-logout media cache** — decide whether logout must evict downloaded
    attachments and image caches; the current code does not.
13. **App Links / Play signing** — the release App Links fingerprint must match
    the Play App Signing certificate; this is tracked in
    `docs/known-limitations.md:53` and is outside the Data safety form.
14. **Display-name search** — decide whether returning the phone field for a
    display-name prefix is intended; the current API does so for any
    authenticated caller (`user/UserService.java:89-94`). Mask/omit the field or
    disclose the behavior in the privacy policy.

## Verification notes

- Source inspected: Android manifest, backup/network XML, Gradle catalog,
  privacy configuration test, Room entities and database module, DataStore and
  Keystore storage, attachment/media caches, auth/logout flow, server Flyway
  migrations, controllers, retention runner, FCM/LLM configuration, and the
  linked architecture/security/operations/RAG/summary docs at `42831fe`.
- Site inventory includes `site/privacy.html` and `site/terms.html` from the
  site refresh merged with this document; app and server production code is
  unchanged between `2ecafae` and `42831fe`.
- Build, unit tests, instrumentation, and emulator checks were **NOT_RUN**: this
  document is static source evidence and the change does not modify code; the
  docs gate is `python tools/check-markdown-links.py`.
- Play Console state, production TLS certificates, production FCM/AI keys, and
  legal/privacy-policy review are **not verified** by this repository.
