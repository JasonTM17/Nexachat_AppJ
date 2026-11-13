# Play Store data-safety and listing facts

Source: app and server code in this repository on `main`. This is a code-derived worksheet for the Google
Play Data safety form and the privacy policy. It is not a legal opinion and not
a submitted declaration. Answers describe the release build with the network
security policy in `android/app/src/main/`; the `debug` and `localSmoke` source
sets are development-only and are not shipped.

Each answer is marked:

- **Supported by code (main)** — the repository implements the described
  behaviour on `main`.
- **Supported by code (branch)** — implemented on the named branch, not in
  `main`.
- **Pending merge** — depends on one or more named branches; re-verify at the
  merge commit before submitting the form.
- **Not supported** — the repository has no code path for that collection.
- **Needs owner decision** — the answer depends on the shipped build, operator
  configuration, or a product/legal decision that the repository cannot make.

The form's "collected" means data transmitted off the device and stored. Data
that exists only in app-private storage is listed separately in
[On-device storage](#on-device-storage).

Paths below are repository-relative. Unless a path starts with `android/` or
`server/`, server Java paths are under `server/src/main/java/com/nexachat/`,
server resources are under `server/src/main/resources/`, and Android paths are
under `android/app/src/main/`. A path marked `branch <name>:` is read from that
branch, not from `main`.

## Listing facts from the build

| Fact | Value | Evidence | Status |
|---|---|---|---|
| App name | NexaChat | `android/app/src/main/res/values/strings.xml:3`, `android/app/src/main/res/values-vi/strings.xml:3` | Supported by code (main) |
| Release application ID | `com.nexachat` | `android/app/build.gradle:155` | Supported by code (main) |
| Debug / localSmoke IDs | `com.nexachat.debug`, `com.nexachat.localsmoke` | `android/app/build.gradle:205`, `:216` | Supported by code (main) |
| minSdk / targetSdk / compileSdk | 26 / 36 / 36 | `android/app/build.gradle:152-157` | Supported by code (main) |
| Default version name / code | `1.0.0` / `1`, overridden by release properties | `android/app/build.gradle:158-159` | Supported by code (main) |
| Locales | English (default) and Vietnamese | `android/app/src/main/res/values/`, `android/app/src/main/res/values-vi/`; `core/common/AppLocaleManager.java:19-24` | Supported by code (main) |
| ABI filters | `arm64-v8a`, `x86_64` | `android/app/build.gradle:170` | Supported by code (main) |
| Ads / in-app purchases | No ad, billing, or purchase SDK in the dependency catalog | `android/gradle/libs.versions.toml:31-108`, `android/app/build.gradle:332-400` | Supported by code (main; owner still declares "No ads") |
| Analytics / crash SDK | No analytics or crash SDK is declared or called by app code. Firebase dependencies include `firebase-messaging` (for push notifications) and `firebase-auth` / `firebase-database` (isolated to the optional ephemeral presence and typing transport, disabled by default via buildConfigField `FIREBASE_REALTIME_ENABLED`); no analytics (`firebase-analytics`), crash reporting (`firebase-crashlytics`), or performance SDK is declared. Firebase SDK-internal telemetry is not visible in this repository. | `android/gradle/libs.versions.toml:108`, `android/app/build.gradle:398-405`; imports are Firebase core, FCM (`notification/NexaFirebaseMessagingService.java:6-7`), GMS `Tasks` (`notification/FirebaseTokenSource.java:4`), and ephemeral transport (`core/realtime/ephemeral/FirebaseEphemeralStateSource.java:8-17`; `call/ui/FirebaseCallStatusDisplayAdapter.java:4`); no analytics or crash SDK | Supported by code (main) |
| Backend required | Yes; the app is a client for a NexaChat REST/STOMP server | `core/common/AppConfig.java:8-19`, `core/network/NetworkModule.java:73-78` | Supported by code (main) |
| Release transport | HTTPS/WSS only; cleartext is blocked by the release network policy | `android/app/src/main/res/xml/network_security_config.xml:10-16`; `tools/release/release-url-guard.sh:78-88` | Supported by code (main) |
| Account creation | Yes, by phone + password (`POST /api/v1/auth/register`). When the operator enables Google sign-in and signup (`NEXACHAT_GOOGLE_SIGNUP_ENABLED=true`), `POST /api/v1/auth/google` can also create an account from a verified Google ID token for an unlinked `sub` (`V33__google_signup.sql` allows Google-only users without phone/password). Otherwise an unlinked Google account is refused. | `auth/AuthController.java`; `auth/google/GoogleAuthController.java:62-67`; `auth/google/GoogleSignInService.java:97-153`; `db/migration/V28__google_signin.sql`, `V33__google_signup.sql`; `application.yml:61-73`, `:113` | Supported by code (main); Google signup is operator-enabled |
| Sign-in methods | Phone + password; optional Google sign-in (link or signup when enabled). The Google feature is off unless the operator enables it. | `auth/google/GoogleAuthController.java:47-86`; `auth/google/GoogleSignInService.java:97-153`, `:186-220`; `application.yml:61-73`, `:113` | Supported by code (main; feature flag) |
| Account deletion | In-app Profile (Settings) > Delete account, confirmed with the current password or a verified-recovery-email code, calls `DELETE /api/v1/users/me`; the public page `site/delete-account.html` describes the same steps and the lost-device flow (phone + code to a verified recovery email; the web form is hidden until the operator configures its API origin). | `user/AccountDeletionService.java`; `user/UserController.java:62-77`; `android/app/src/main/java/com/nexachat/feature/profile/DeleteAccountFragment.java`; `site/delete-account.html`; `db/migration/V26__user_account_deletion.sql` | Supported by code (main) |

## Data the app sends to the NexaChat server

The following data leaves the device and is stored by the server unless a
retention job or an explicit delete action removes it.

| Data | What is sent / stored | Evidence | Status |
|---|---|---|---|
| Account identity | E.164 phone number, password, display name; password is BCrypt-hashed server-side and never returned | `auth/dto/RegisterRequest.java:12-16`; `user/User.java:33-42`; `config/SecurityConfig.java:67` | Supported by code (main) |
| Recovery email (optional) | A user-supplied email address, normalized to lowercase and stored in `users.email`; it is **verified** by a 6-digit code before use, and `users.email_verified_at` records the verification. The address is owner-only: it is not in `UserResponse`, other users' profile views, or search. It is used for account-recovery, account-deletion, and password-reset codes, not for marketing. A verified address is unique among active accounts. | `db/migration/V27__account_recovery_email_otp.sql:1-11`; `user/dto/MyProfileResponse.java`; `user/dto/UserResponse.java`; `recovery/EmailOtpService.java:44-56`; `user/EmailRecoveryService.java`; `user/UserController.java:79-97` | Supported by code (main) |
| Email OTP records | Per-code row: purpose (`VERIFY_EMAIL`, `DELETE_ACCOUNT`, `RESET_PASSWORD`), recipient address, an HMAC-SHA256 hash of the 6-digit code, expiry, attempt count and consumed timestamp. The plaintext code is not stored. | `db/migration/V27__account_recovery_email_otp.sql:13-31`; `recovery/OtpCodeHasher.java:23-43`; `recovery/EmailOtpService.java:22-23`, `:108-132` | Supported by code (main) |
| Google identity (optional) | Google OpenID Connect `sub` (the immutable account identifier), the Google email at link time, and the link timestamp, stored in `user_identities`. The email is display-only and never used to match accounts. Linking requires the current password. The first Google sign-in for an unlinked `sub` creates an account only when signup is enabled; otherwise it is refused. | `db/migration/V28__google_signin.sql`, `V33__google_signup.sql`; `auth/google/UserIdentity.java`; `auth/google/GoogleSignInService.java:97-153`, `:186-220`; `application.yml:113` | Supported by code (main); signup is operator-enabled |
| Session device label | The `User-Agent` request header is stored as `refresh_tokens.device_label`; refresh tokens are stored only as SHA-256 hashes | `auth/AuthController.java:28`, `:42-56`; `db/migration/V1__init.sql:25-34`; `auth/TokenService.java:133-137` | Supported by code (main) |
| Profile name and avatar | Display name, avatar image bytes; avatar URL is part of the public profile response | `user/dto/UpdateProfileRequest.java:9-11`; `attachment/AvatarController.java:50-60`; `user/dto/UserResponse.java:12-23` | Supported by code (main) |
| Phone discovery | The user types a phone number or display-name prefix. An exact phone lookup returns the matching profile with the phone. A display-name prefix search returns profiles but omits the `phone` field unless the caller already has that user as a contact. | `data/ContactsRepository.java:120-127`; `user/UserController.java:51-56`; `user/UserService.java:76-107`; `user/dto/UserResponse.java:12-23` | Supported by code (main) |
| Contacts | A user can add another user by phone; the server stores the contact edge and the owner's private alias | `db/migration/V1__init.sql:41-48`; `contact/ContactService.java` | Supported by code (main) |
| Messages | Text, message type, client message id, reply target, mentions, edit/delete state, timestamps | `db/migration/V1__init.sql:88-101`; `db/migration/V17__message_mentions.sql`; `message/MessageService.java:406-449` | Supported by code (main) |
| Attachments | File bytes and metadata: original name, MIME, size, width/height, duration, BlurHash; stored on the file volume | `db/migration/V1__init.sql:108-120`; `attachment/AttachmentService.java`; `application.yml:99-100` | Supported by code (main) |
| Read / delivered watermarks | Read and delivered sequence watermarks | `db/migration/V7__p0_receipts_sync_fcm.sql`; `docs/websocket-protocol.md` | Supported by code (main) |
| Reactions, pins | Emoji reactions, reaction events, pinned message ids | `db/migration/V3__interactions.sql`, `V10__reaction_events_idempotency_device_tokens.sql` | Supported by code (main) |
| Groups and invites | Group title, members, roles, invite-token hashes, join requests, bans | `db/migration/V20__group_invite_links.sql`; `docs/api-contract.md:714-720` | Supported by code (main) |
| Polls | Question, options, votes, close state, versioned change feed | `db/migration/V15__group_polls.sql` | Supported by code (main) |
| Reminders | Title, time, repeat rule, delivery state | `db/migration/V14__conversation_reminders.sql` | Supported by code (main) |
| Labels | Private label name/colour and per-conversation assignments | `db/migration/V18__conversation_labels.sql` | Supported by code (main) |
| Presence | Last-seen timestamp, online state; `showPresence` / `showSeen` settings | `db/migration/V4__presence.sql`; `user/User.java:50-72`; `docs/known-limitations.md:14-15` | Supported by code (main) |
| Calls | Invite/accept/reject/end, SDP and ICE payloads; SDP/ICE are relayed in memory, not persisted; call metadata is stored | `docs/calls.md:45-46`, `:115-116`; `db/migration/V5__calls.sql` | Supported by code (main) |
| Push registration | FCM registration token and platform; only when Firebase is configured and a session exists | `notification/DeviceTokenSyncExecutor.java:65-101`; `notification/DeviceTokenService.java:31-62`; `db/migration/V7__p0_receipts_sync_fcm.sql:15-25` | Supported by code (main) |
| AI request data | Summary scope or RAG question, and, when external AI is enabled, redacted message text sent to the configured provider | `application.yml:162-224`; `docs/ai-summary.md:91-96`; `docs/rag.md:77-112` | Supported by code (main); external call is owner-configured |

The app does **not** read the device address book: the manifest has no
`READ_CONTACTS`, and no `ContactsContract` usage exists. Discovery uses a typed
phone number or display-name prefix (`data/ContactsRepository.java:120-127`).
The manifest also has no location, camera, storage, phone-state, SMS, or calendar
permission (`android/app/src/main/AndroidManifest.xml:6-16`).

Recovery email and Google identity data are owner-only. `MyProfileResponse`
returns `email`/`emailVerified` only on `GET /users/me`; `UserResponse`, which
feeds auth responses, profile updates, search results and member views, does not
contain the recovery email or the Google identity.

## Data safety form: collection and security

| Form question | Answer from this code | Evidence | Status |
|---|---|---|---|
| Does the app collect or share any required user data type? | Yes. Account, optional recovery email, optional Google identity, message, media, interaction, call-signaling metadata, and device-token data reach the server. | See the tables above and `application.yml` configuration. | Supported by code (main) |
| Is all collected user data encrypted in transit? | Yes for the release build: cleartext is disabled and signed releases require an `https://` API base URL. TLS termination itself is a deployment responsibility that the owner must confirm. | `android/app/src/main/res/xml/network_security_config.xml:10-16`; `tools/release/release-url-guard.sh:78-88`; `docs/operations.md:353-356` | Supported by code (main) |
| Do you provide a way for users to request that their data be deleted? | Yes. The app provides Profile (Settings) > Delete account with the current password or a verified-recovery-email code (`DELETE /api/v1/users/me`). The public page `site/delete-account.html` explains the same steps and, when its API origin is configured, the lost-device flow: phone number + a 6-digit code sent to the verified recovery email. Users without a verified recovery email are routed to the developer contact on the Play listing. Deletion is a server-side tombstone; sent messages and attachments stay visible to other members attributed to a deleted account. | `user/UserController.java:62-77`; `user/AccountDeletionService.java`; `recovery/AccountDeletionController.java`; `recovery/AccountRecoveryService.java:54-81`; `android/app/src/main/java/com/nexachat/feature/profile/DeleteAccountFragment.java`; `site/delete-account.html` | Supported by code (main) |

## Data safety form: data types

The purpose labels below use Play's common purposes: **App functionality**,
**Account management**, **Developer communications**, and **Fraud prevention,
security, and compliance**. No analytics, advertising, or personalisation
purpose applies.

| Play data type | Handled by NexaChat | Required or optional | Purpose | Status |
|---|---|---|---|---|
| Personal info → Name | Display name, shown to other users | Required at registration | Account management; app functionality | Supported by code (main) |
| Personal info → Phone number | E.164 number; used for login, returned in user profiles, and shown in display-name search only to callers who already have the user as a contact | Required at registration | Account management; app functionality | Supported by code (main) |
| Personal info → Email address | Optional recovery email; used only for account-recovery, account-deletion and password-reset security codes; never shown to other users and not used for marketing | Optional | Account management; fraud prevention, security, and compliance | Supported by code (main) |
| Personal info → User IDs | Internal account UUID; additionally the Google OpenID Connect `sub` when the user links Google | Required for the NexaChat account; Google ID optional | Account management; app functionality | Supported by code (main); Google ID optional |
| Messages → In-app messages | Text messages, edits, tombstones, reply/mention metadata | Required for messaging | App functionality | Supported by code (main) |
| Photos and videos → Photos | Avatar image and image attachments chosen by the user | Optional (user chooses) | App functionality | Supported by code (main) |
| Photos and videos → Videos | Video attachments chosen by the user | Optional | App functionality | Supported by code (main) |
| Audio files → Voice or sound recordings | Voice messages recorded with `RECORD_AUDIO`; no call recording code exists | Optional | App functionality | Supported by code (main) |
| Files and docs | Arbitrary file attachments up to 25 MB | Optional | App functionality | Supported by code (main) |
| Contacts | Device address book is **not** read. A manually typed phone number can create a private contact edge. Whether Play treats that as "Contacts" is a form-reading decision. | Optional | App functionality | Needs owner decision |
| App activity → Other user-generated content | Reactions, pins, labels, polls and votes, reminders, group invites, call history | Optional | App functionality | Supported by code (main) |
| App activity → App interactions | No analytics or interaction-event SDK; the app does not record taps/screens for analytics | — | — | Not supported |
| App activity → In-app search history | Search queries are sent as request parameters; no search-history table or local search history exists, so no history is stored | Optional when used | App functionality (transient) | Not supported |
| Location | No location permission or location API. Chat images are normally compressed with location metadata removed; a non-privacy compression failure falls back to the original, and avatar images and video attachments are stored as selected without that scrubber (see owner decisions). | — | — | Not supported |
| Financial info | No payment, billing, or financial feature | — | — | Not supported |
| Health and fitness | No health API or permission | — | — | Not supported |
| Calendar | No calendar permission or event access; reminders are in-app only | — | — | Not supported |
| Web browsing | No browser or WebView history | — | — | Not supported |
| SMS / MMS | No SMS permission or message access | — | — | Not supported |
| App info and performance → Crash logs / diagnostics | No Crashlytics/Sentry/analytics SDK; server logs are operator-side and are not device diagnostics | — | — | Not supported |
| Device or other IDs → FCM registration token | Token stored on the server and used to address push messages | Optional (push) | App functionality (push) | Supported by code (main) |
| Device or other IDs → Firebase installation ID | The Firebase SDK maintains its own installation identity; app code does not read it | Optional (push) | App functionality (push) | Needs owner decision (third-party SDK disclosure) |

## Data shared with third parties

Play distinguishes data processed by a service provider from data shared for a
third party's own purposes. The repository only shows the following outbound
paths; the owner must confirm the shipped configuration and the processor terms.

| Recipient | Data | When it leaves the deployment | Evidence | Status |
|---|---|---|---|---|
| Google Firebase Cloud Messaging | Device registration token; data-only payload: conversation/message ids, sequence, sender display name, call ids/names/timestamps, reminder ids and repeat rule. Message text and attachment bytes are not in the payload, but the sender's display name is. | The client contacts Firebase and obtains a token when the build has `google-services.json`; the server sends a payload only when `FCM_ENABLED=true` with a service account | `android/app/build.gradle:9-17`; `application.yml:225-236`; `notification/DefaultFcmPushService.java:65-77`, `:89-98`; `reminder/FcmReminderPushSender.java:29-31`, `:67-71` | Needs owner decision for the shipped build |
| Google Identity Services (Credential Manager) | Google account email and Google `sub` identifier, returned to the app in an ID token when the user chooses Sign in with Google or links an account. The app sends the ID token and a server nonce to NexaChat; NexaChat stores only `sub` and the email at link time. The ID token itself is not stored. | Only when a user links or signs in with Google; the feature is off unless `NEXACHAT_GOOGLE_SIGNIN_ENABLED=true` and client IDs are configured | `android/app/src/main/java/com/nexachat/core/auth/CredentialManagerGoogleSignIn.java:52-95`; `core/auth/GoogleSignInFlow.java:40-70`; `core/network/dto/GoogleSignInRequest.java`; `server/src/main/java/com/nexachat/auth/google/GoogleIdTokenVerifier.java`; `user_identities` in `db/migration/V28__google_signin.sql` | Supported by code (main); owner must complete the OAuth consent screen and decide the form disclosure |
| Transactional email provider (SMTP relay or Resend) | The verified recovery email address, the 6-digit code, and a bilingual message that states the purpose and the 10-minute validity. No marketing mail exists. | Only when the operator configures `NEXACHAT_MAIL_PROVIDER=smtp` or `resend` and a user requests a code; the default empty provider disables mail and makes authenticated email endpoints answer `503 MAIL_UNAVAILABLE`, while public requests still return `202` without sending | `mail/MailConfiguration.java`; `mail/ResendMailSender.java:30-48`; `mail/SmtpMailSender.java`; `recovery/OtpMailContent.java`; `recovery/OtpMailDispatcher.java` | Supported by code (main); provider name depends on configuration |
| Google Firebase Realtime Database and Firebase Auth (opt-in ephemeral bridge) | When enabled: (1) Auth: NexaChat user UUID as Firebase UID (`auth.uid`) and a server-minted `bridgeId` claim. (2) Server-projected state: `/control/enabled`, `/control/presencePrivacyVersion` (v2); `/accounts/{uid}/{active,showPresence}`; session lease (`/sessions/{uid}/{bridgeId}/until`, <=120s); membership lease (`/members/{cid}/{uid}`, <=120s, <=30 conversations); 1:1 direct presence readers (`/presenceReaders/{subjectUid}/{viewerUid}`); reserved `/blocked`; passive call status (`/calls/{callId}` with `cid`, `callerId`, `calleeId`, `state`, `updatedAt`, `expiresAt`; client writes denied; no WebRTC SDP/ICE or audio). (3) Client-written ephemeral state: `/presence/{uid}/{bridgeId}` (`online` boolean, `updatedAt`, `leaseUntil`; `lastSeen` is strictly 0 under Presence Privacy v2; real `lastSeen` remains on server); `/typing/{cid}/{uid}` (`updatedAt`, `expiresAt` with max 5s TTL, 1 write/3s throttle). Excluded: no phone, email, password, display name, message text, attachment bytes, media, contacts, call audio, or NexaChat auth tokens. Retention: ephemeral only (5s–120s); cleared on disconnect/logout/revocation; purged on account deletion. Play classification: Collected by the app via its service provider for App Functionality (or Shared if service-provider DPA is not executed). | Only when `FIREBASE_REALTIME_ENABLED=true` and `FIREBASE_DATABASE_URL` is set on the server, AND `firebaseRealtimeEnabled=true` is enabled in Android; call status projection additionally requires `FIREBASE_CALL_STATUS_ENABLED=true` and `firebaseCallStatusEnabled=true`. Both server and Android flags are OFF by default; STOMP over WebSocket remains the default and authoritative transport | `android/app/build.gradle:78-85`, `:184-185`, `:398-402`; `android/app/src/main/java/com/nexachat/core/realtime/ephemeral/FirebaseEphemeralStateSource.java:154-207`, `:323-352`, `:397-410`, `:819-826`; `android/app/src/main/java/com/nexachat/core/realtime/ephemeral/EphemeralStateRouter.java:42-55`, `:74-76`; `android/app/src/main/java/com/nexachat/call/ui/FirebaseCallStatusDisplayAdapter.java:28-74`; `server/src/main/java/com/nexachat/firebase/auth/FirebaseRealtimeAuthService.java:54-102`; `server/src/main/java/com/nexachat/firebase/client/FirebaseRtdbRestClient.java:55-58`, `:289-297`; `server/src/main/java/com/nexachat/firebase/call/FirebaseCallStatusProjectionService.java:58-115`; `server/src/main/java/com/nexachat/firebase/projection/FirebaseAuthorizationProjectionService.java:110-129`; `server/src/main/java/com/nexachat/firebase/projection/FirebaseAuthorizationLeaseSweepJob.java:45-69`; `server/src/main/java/com/nexachat/firebase/auth/FirebaseSessionRevocationListener.java:35-57`; `server/src/main/resources/application.yml:279-285`; `tools/firebase-rules/database.rules.json:1-67`; `docs/firebase-realtime.md:8-195` | Supported by code (main), disabled by default; owner decision to enable |
| External summary LLM | Redacted eligible message text and the summary request; sender names become `U1..Un`, message ids and phone/email/URL tokens are removed | Only when an operator configures a summary key, the provider is enabled, and the requesting user has `aiSummaryConsent=true`. Text from other participants in the window is included unless they opted out in that conversation; their own `aiSummaryConsent` flag is not checked. Primary is Gemini-compatible; optional fallback is DeepSeek-compatible; a local server model can be configured instead. | `application.yml:162-190`; `docs/ai-summary.md:91-96`; `docs/known-limitations.md:33`; `docs/rag.md:77-112` | Needs owner decision; default config makes no external call |
| External embedding provider | Redacted question and candidate message text for embedding | The ask path uses an external provider only when the requesting user consents. The background backfill uses the deployment's primary provider (external when configured) and filters only per-conversation opt-outs, not each author's `aiSummaryConsent`; enabling it therefore sends non-opted-out message text without per-author consent. The default `lexical` provider stays on the server. | `application.yml:190-224`; `docs/rag.md:44-52`, `:77-98`, `:127-133`; `rag/RagEmbeddingService.java:63-75`, `:101-124`, `:140-149`, `:199-201`; `rag/RagEmbeddingProviders.java:48-51` | Needs owner decision; default config is offline |
| Google public STUN | Source IP and ICE candidates during call setup; no message or account content | During a call when the default ICE configuration is used; there is no TURN server. This is a network dependency, not an app data-sharing feature. | `docs/calls.md:115-116`, `:165` | Supported by code (main) |
| Ad, analytics, crash, or social SDKs | None | — | `android/gradle/libs.versions.toml:31-108`; `android/app/build.gradle:332-400` | Not supported |

Other NexaChat users can see the display name, avatar, and phone number returned
by profile responses. An authenticated caller can look up an exact phone number,
and a display-name prefix search returns matching profiles; the `phone` field is
omitted from those search results unless the caller already has the user as a
contact (`user/UserService.java:96-107`; `user/dto/UserResponse.java:12-23`;
`data/ContactsRepository.java:125-127`). Recovery email and Google identity are
never included in those responses. The phone-field behavior is app functionality,
not a third-party share, but the privacy policy must describe it, and the owner
should confirm the contact exception is intended.

The embedding backfill is one AI path where another participant's consent is
not checked. The ask path resolves its provider from the requesting user's
persisted consent (`rag/RagEmbeddingService.java:199-201`); the scheduled
backfill calls `providers.primary()` and excludes only messages whose author
opted out of AI in that conversation (`rag/RagEmbeddingService.java:101-124`,
`:140-149`). If the operator enables an external embedding provider, message
text from every non-opted-out author is sent to that provider even if that author
never set `aiSummaryConsent=true`. This needs a privacy-policy disclosure and a
legal basis, or the operator should keep the default `lexical` provider.

### Firebase Realtime Database and Auth: Data Safety details

The Firebase Realtime Database (RTDB) and Firebase Auth bridge provides an optional,
ephemeral realtime transport for online presence, typing indicators, and passive call
status projection. Both server-side components (`server/src/main/java/com/nexachat/firebase/`)
and Android client components (`android/app/src/main/java/com/nexachat/core/realtime/ephemeral/`
and `com.nexachat.call.ui.FirebaseCallStatusDisplayAdapter`) are implemented in the codebase,
governed by contract-tested security rules (`tools/firebase-rules/database.rules.json`).

#### 1. Play Store classification: Collection vs Sharing

- **Play definition of "Collection"**: Data transmitted off the device is considered
  collected by the app. When the Firebase flags are enabled, the Android client
  transmits ephemeral presence and typing state off-device to Firebase RTDB via
  `FirebaseEphemeralStateSource.java`.
- **Play definition of "Sharing" vs Service Provider**: Play states that transferring
  data to a third party that processes it solely on behalf of the developer (a "service
  provider") under written data processing terms (such as Google Cloud / Firebase Data
  Processing and Security Terms) is disclosed as **Collected** for **App functionality**,
  rather than **Shared**.
- **Owner action**: If the operator maintains standard Google Cloud / Firebase service-provider
  terms, this data is declared as **Collected** for App functionality. If the operator
  has not executed or verified service provider terms with Google, the transfer must be
  declared as **Shared** with Google.

#### 2. Feature flags and default configuration

- **Backend flags** (`server/src/main/resources/application.yml:279-285`):
  - `nexachat.firebase.realtime-enabled: ${FIREBASE_REALTIME_ENABLED:false}` (default `false`)
  - `nexachat.firebase.call-status-enabled: ${FIREBASE_CALL_STATUS_ENABLED:false}` (default `false`)
  - `nexachat.firebase.database-url: ${FIREBASE_DATABASE_URL:}` (default empty `""`)
  - `nexachat.firebase.realtime-default: ${FIREBASE_REALTIME_DEFAULT:stomp}` (default `stomp`)
- **Android client flags** (`android/app/build.gradle:78-85`, `:184-185`):
  - `firebaseRealtimeEnabled` (default `false`, sets `BuildConfig.FIREBASE_REALTIME_ENABLED`)
  - `firebaseCallStatusEnabled` (default `false`, sets `BuildConfig.FIREBASE_CALL_STATUS_ENABLED`)
- **Default state**: Both server and client flags are **OFF by default**. With the flags off,
  the Android app attaches no Firebase listeners, initiates no Firebase Auth or RTDB connections,
  and STOMP over WebSocket remains the sole, authoritative realtime transport.

#### 3. Exact data reaching Firebase when flags are enabled

When the feature flags are explicitly turned on, the following data reaches Firebase:

- **Authentication / Firebase Auth**:
  - The server issues a custom token with the NexaChat user UUID as the Firebase UID (`auth.uid`)
    and a fresh server-minted `bridgeId` UUID as a custom claim (`FirebaseRealtimeAuthService.java:59-63`).
  - The Android client exchanges this token via `FirebaseAuth.signInWithCustomToken(token)`
    (`FirebaseEphemeralStateSource.java:193-195`).
- **Presence (`/presence/{uid}/{bridgeId}`)**:
  - *Online payload*: `{online: true, lastSeen: 0, updatedAt: ServerValue.TIMESTAMP, leaseUntil: leaseUntilEpochMs}`
    (`FirebaseEphemeralStateSource.java:397-402`).
  - *Offline payload* (`onDisconnect()` or clean shutdown): `{online: false, lastSeen: 0, updatedAt: 0, leaseUntil: 0}`
    (`FirebaseEphemeralStateSource.java:819-826`).
  - *Presence Privacy v2 (`control/presencePrivacyVersion: 2`)*: In RTDB, the `lastSeen`
    timestamp field is **always strictly 0 (zero)** (`FirebaseEphemeralStateSource.java:400`, `:822`).
    Real `last_seen_at` timestamps are never written to Firebase; PostgreSQL remains the sole
    authority for last-seen times. Security rules strictly enforce `data.child('lastSeen').val() == 0`
    for offline records.
  - *Reader authorization*: Peer presence reads are restricted to 1:1 DIRECT conversation
    partners who hold active membership leases via `/presenceReaders/{subjectUid}/{viewerUid}`
    (`tools/firebase-rules/database.rules.json:21-31`; `FirebaseAuthorizationProjectionService.java:80-92`).
    Group members cannot read presence through RTDB without an independent DIRECT conversation.
- **Typing indicators (`/typing/{conversationId}/{uid}`)**:
  - *Payload*: `{bridgeId, updatedAt: ServerValue.TIMESTAMP, expiresAt: now + 5000}`
    (`FirebaseEphemeralStateSource.java:345-349`).
  - *Throttling and TTL*: Writes are rate-limited to 1 per 3 seconds; TTL is at most 5 seconds
    (`EphemeralTimeRules.java`). Client deletion is denied by security rules (`newData.exists()`)
    to prevent resetting the throttle. Stale records are cleared by TTL expiration or the server sweeper.
- **Call status projection (`/calls/{callId}`)**:
  - *When enabled*: Requires `FIREBASE_CALL_STATUS_ENABLED=true` on the server and
    `firebaseCallStatusEnabled=true` on Android.
  - *Payload*: Projected by server REST client with `{cid, callerId, calleeId, state, updatedAt, expiresAt}`
    where `state` is `RINGING`, `ACTIVE`, or `ENDED` (`FirebaseCallStatusProjectionService.java:105-115`).
  - *Permissions*: Read-only for call participants (`auth.uid == callerId || auth.uid == calleeId`);
    client writes are completely denied (`.write: false`).
  - *Passive UI only*: Consumed by `FirebaseCallStatusDisplayAdapter.java:37-74` for header/list
    display only. WebRTC signaling (SDP offers/answers, ICE candidates) and call audio NEVER
    touch Firebase; they remain relayed in-memory and via STOMP (`/topic/call.*`).
- **Server authorization & lease mirrors**:
  - `/control/enabled`: global kill switch boolean (`tools/firebase-rules/database.rules.json:28`).
  - `/control/presencePrivacyVersion`: integer version `2` gate (`docs/firebase-realtime.md:29-30`).
  - `/accounts/{uid}/{active,showPresence}`: active account state and presence visibility preference.
  - `/sessions/{uid}/{bridgeId}/until`: bridge lease epoch ms (<= 120s, bounded by access token expiry).
  - `/members/{conversationId}/{uid}`: membership lease epoch ms (<= 120s, up to 30 active conversations).
  - `/presenceReaders/{subjectUid}/{viewerUid}`: 1:1 direct conversation reader authorization grants.
  - `/blocked/{uid}/{otherUid}`: reserved mutual block mirror.

#### 4. Data that NEVER reaches Firebase

- **No personal credentials or contact info**: No phone numbers, email addresses, passwords,
  or display names (display name is present only in FCM push payloads when FCM is enabled, not in RTDB).
- **No message content or media**: No message text, attachment bytes, image/voice/video files, or file URLs.
- **No address book or contacts**: Device contacts are not read; NexaChat contact edges are not mirrored.
- **No WebRTC audio or signaling**: Call SDP payloads, ICE candidates, and audio media never reach Firebase.
- **No user interactions or social data**: No read/delivered receipts, reaction emojis, polls/votes,
  reminders, labels, or pins.
- **No NexaChat auth credentials**: NexaChat access tokens and refresh tokens stay strictly off Firebase.

#### 5. Retention and deletion

- **Strictly ephemeral lifetimes**:
  - Typing indicators: TTL <= 5 seconds.
  - Session leases: <= 120 seconds.
  - Membership leases: <= 120 seconds.
  - Call status projections: `RINGING` <= 45s; `ACTIVE` <= 120s (renewed every 60s); `ENDED` <= 60s.
- **Disconnect cleanup**: Client disconnect triggers `onDisconnect().setValue(offlinePresencePayload())`,
  setting `online: false` and zeroing all timestamp fields (`FirebaseEphemeralStateSource.java:403-408`).
- **Periodic sweeper**: `FirebaseAuthorizationLeaseSweepJob.java:45-69` runs periodically
  (default interval 60s) to delete expired `members/`, `typing/`, `sessions/`, and `presence/` records.
- **Session revocation / logout**: `FirebaseSessionRevocationListener.java:35-57` deletes `sessions/`,
  `presence/`, and `typing/` paths and revokes Firebase Auth refresh tokens when access tokens are revoked.
- **Account deletion**: `FirebaseAuthorizationProjectionService.onAccountDeleted`
  (`FirebaseAuthorizationProjectionService.java:110-129`) immediately purges all user nodes in
  RTDB (`accounts/{uid}`, `sessions/{uid}`, `presence/{uid}`, `presenceReaders/{uid}`,
  `blocked/{uid}`, `members/*/{uid}`, `typing/*/{uid}`, and participant calls at `calls/{callId}`).

## Data processed only in memory

| Data | Where it lives | Evidence |
|---|---|---|
| Call SDP and ICE payloads | In-memory call registry/relay, not a database table; WebRTC signaling never reaches Firebase even when optional call-status projection is enabled | `docs/calls.md:45-46`; `docs/firebase-realtime.md:38`, `:113-114`; `db/migration/V5__calls.sql` stores only call metadata |
| Typing indicators | STOMP frame by default; when optional `FIREBASE_REALTIME_ENABLED` is on, ephemeral `/typing/{cid}/{uid}` indicator with max 5s TTL | `docs/websocket-protocol.md`; `docs/firebase-realtime.md:37`; `android/app/src/main/java/com/nexachat/core/realtime/ephemeral/FirebaseEphemeralStateSource.java:323-352` |
| Online presence | In-memory session registry and STOMP by default; `users.last_seen_at` is persisted separately in PostgreSQL. When optional `FIREBASE_REALTIME_ENABLED` is on, ephemeral online/offline state is mirrored to Firebase RTDB (`/presence/{uid}/{bridgeId}`) with `lastSeen` strictly set to 0 (Presence Privacy v2) | `docs/known-limitations.md:14-15`; `docs/firebase-realtime.md:36`, `:137-140`; `android/app/src/main/java/com/nexachat/core/realtime/ephemeral/FirebaseEphemeralStateSource.java:397-402`, `:819-826` |
| Rate-limit buckets and client IPs | In-memory bounded buckets; not persisted by the rate-limit code | `docs/security.md:216-221`; `config/ClientIpResolver.java` |
| Raw AI prompts and model output | Not stored; only the validated summary/answer payload is cached | `docs/ai-summary.md:96`; `db/migration/V6__conversation_summaries.sql`, `V9__conversation_rag.sql` |
| OTP plaintext code | Generated in memory and sent in the email; the database stores only the HMAC-SHA256 hash. The recipient's mailbox and the email provider necessarily see the code. | `recovery/OtpCodeHasher.java:31-37`; `recovery/EmailOtpService.java:22-23`, `:141-149`; `db/migration/V27__account_recovery_email_otp.sql:13-24` |
| Google ID token | Held transiently by the Android client and sent to the server for verification; not persisted by the app or the server. The server stores the `sub` and email at link time, or creates a Google-only account when signup is enabled. | `core/auth/GoogleSignInFlow.java:40-70`; `core/network/dto/GoogleSignInRequest.java`; `auth/google/GoogleSignInService.java:97-153`; `db/migration/V28__google_signin.sql`, `V33__google_signup.sql` |

## On-device storage

| Store | Contents | Protection | Evidence |
|---|---|---|---|
| Room database `nexachat.db` (version 13) | Messages, conversations, reactions, pins, users, call logs, outbox, offline outbox, cached label ids and preferences | App-private SQLite; not SQLCipher-encrypted | `core/database/DatabaseModule.java:24-38`; `core/database/AppDatabase.java:16-28` |
| Preferences DataStore `nexachat_tokens` | Access and refresh tokens; FCM registration token; recall-window config; sync cursors; locally hidden message ids | Tokens and FCM token: Base64 of `IV || AES-GCM` ciphertext with the key in AndroidKeyStore. Sync cursors, hidden ids and expiry timestamps are plaintext preferences. | `core/storage/TokenStore.java:23-31`, `:395-410`, `:519-547`; `core/storage/AesGcmTokenCipher.java:15-35`; `core/storage/StorageModule.java:19-30` |
| SharedPreferences `nexachat_settings` | Theme mode | App-private plaintext | `data/SettingsRepository.java:36-37`, `:105-107` |
| SharedPreferences `nexachat_locale` | App locale | App-private plaintext | `core/common/AppLocaleManager.java:19-24`, `:110` |
| `cacheDir/attachments` | Downloaded attachment files named by id | App-private cache | `data/AttachmentRepository.java:207-214`, `:345-346` |
| Glide and OkHttp caches | Image thumbnails and HTTP responses; combined ceiling 2 GiB | App-private cache | `core/media/MediaCacheConfig.java:8-16`; `core/media/MediaGlideModule.java:19-23`; `core/network/NetworkModule.java:49` |
| `cacheDir/media-uploads` and voice files | Temporary compressed images and voice recordings | App-private cache | `core/media/ImageCompressor.java:28`; `feature/chat/voice/VoiceRecorder.java:74-79` |
| Google ID token | In memory during sign-in or link; not written to DataStore or Room | Transient | `core/auth/GoogleSignInFlow.java:40-70`; `core/network/dto/GoogleSignInRequest.java` |
| Backup / device transfer | All app data excluded | `allowBackup=false`; cloud-backup and device-transfer rules exclude every private domain | `android/app/src/main/AndroidManifest.xml:20-29`; `android/app/src/main/res/xml/backup_rules.xml:4-12`; `android/app/src/main/res/xml/data_extraction_rules.xml:3-26`; `android/app/src/test/java/com/nexachat/security/AndroidPrivacyConfigurationTest.java:24-58` |

### Local logout and account boundaries

- `AccountDataCleaner.clearForLogout` clears the Room cache while preserving the
  ordinary outbox, and `clearAllTables()` when logout-all explicitly removes
  queued sends (`data/AccountDataCleaner.java:83-113`). The FCM token and pending
  unregister request stay encrypted in DataStore so a later authenticated sync
  can unregister the device (`data/AuthRepository.java:167-207`;
  `notification/DeviceTokenRegistrationManager.java:69-81`).
- `TokenStore.clear()` removes access/refresh tokens, recall config, hidden ids
  and sync cursors, but not the FCM token or a pending unregister
  (`core/storage/TokenStore.java:519-547`).
- **Account deletion is the exception.** Deleting the account clears the entire
  Room database, including the outbox and offline outbox, clears the OkHttp/Glide/media
  caches, clears the pending device-unregister residue, and then clears the session
  tokens before returning to Login (`data/AuthRepository.java`; `core/media/MediaCacheCleaner.java`).
- Ordinary logout (and logout-all) still does **not** clear the media caches in
  `cacheDir`; downloaded attachments, thumbnails and HTTP cache entries can
  outlive logout until Android evicts the cache or the user clears app data.
  This is a product/privacy decision, not an implemented wipe.
- Uninstalling the app or clearing app data is the complete local wipe for a user
  who does not delete the account. Account deletion also wipes the app's local
  data as described above.

## Retention and deletion

All server retention jobs are disabled by default; the global switch is also
off and dry-run is on (`application.yml:104-159`; `docs/operations.md:747-754`).
Enabling a job does not delete messages, conversations, or users;
`docs/operations.md:822` states those rows are never removed by these jobs.
Account deletion is an explicit user action, not a retention job.

| Data | Default retention behaviour | Evidence |
|---|---|---|
| Account, messages, conversations, contacts, labels, memberships | No automatic deletion by a retention job; the account owner can delete the account explicitly (below). Message recall is a soft delete, not a row delete. | `docs/operations.md:822`; `message/MessageService.java:406-449`; `user/AccountDeletionService.java` |
| Recovery email | Stored in `users.email` after verification until the user changes/removes it or deletes the account. Cleared by the deletion tombstone. | `user/User.java:218-229`; `recovery/EmailOtpService.java:100-106` |
| Email OTP records | The code is valid for 10 minutes, dies after 5 wrong attempts, and a new code invalidates older unconsumed codes of the same purpose. Account deletion deletes the user's `email_otps` rows; there is still no retention job for consumed or expired rows left on other accounts. | `recovery/EmailOtpService.java:22-23`, `:108-132`; `recovery/EmailOtpRepository.java`; `user/AccountDeletionJdbcSupport.java:77` |
| Linked Google identity | Stored until the user unlinks Google. Unlink deletes the row immediately; account deletion deletes Google identity and link-nonce rows in the same transaction. | `auth/google/GoogleSignInService.java:186-220`; `user/AccountDeletionJdbcSupport.java:63-76`; `db/migration/V28__google_signin.sql` |
| Message tombstones | Row remains; optional job after 30 days clears content, mention flags and embeddings. Attachments remain unless the attachment job runs. | `application.yml:143-145`; `docs/known-limitations.md:18`, `:51` |
| Attachments | Optional job: unbound uploads after 24 h, attachments of soft-deleted messages after 30 days | `application.yml:139-142`; `docs/operations.md:800-802` |
| Refresh tokens | Hash rows remain until the optional 30-day cleanup runs | `application.yml:124-126`; `db/migration/V1__init.sql:25-34` |
| Device tokens | Optional cleanup after 180 stale days | `application.yml:127-129`; `docs/operations.md:792-793` |
| Fired one-shot reminders / ended calls | Optional cleanup after 30 / 365 days | `application.yml:130-138`; `docs/operations.md:794-799` |
| Polls | Optional job redacts aged payloads and removes options/votes after 365 days; the poll tombstone remains | `application.yml:133-135`; `docs/operations.md:796-797` |
| Summaries and RAG caches | 24 h TTL by default; expired rows are only purged when the optional summaries/rag-cache job is enabled. Account deletion deletes the summary and ask caches of every conversation the user belonged to (the caches are conversation-scoped, not requester-scoped). | `db/migration/V6__conversation_summaries.sql`; `V9__conversation_rag.sql`; `application.yml:148-159`; `user/AccountDeletionJdbcSupport.java:25-45` |
| Derived copies of message text | Recall or edit deletes cached summaries whose sequence window contains the changed message, and recall purges the RAG ask cache for the conversation; the ask cache also refuses a replay when a cited message is gone or its author opted out. Expired rows remain until the optional summaries/rag-cache job runs. | `message/MessageService.java:406-449`; `summary/MessageSummaryCacheInvalidationListener.java:10-33`; `summary/SummaryCacheRepository.java:103-121`; `rag/MessageRecallAskCacheListener.java:12-22`; `docs/rag.md:108-113`; `db/migration/V6__conversation_summaries.sql`, `V9__conversation_rag.sql`; `application.yml:148-159` |
| Firebase ephemeral state (leases, presence, typing, call status) | Disabled by default. When enabled, strictly ephemeral: typing TTL <= 5s; session/membership leases <= 120s; active call status lease <= 120s (renewed every 60s); presence cleared on disconnect via `onDisconnect()`. Swept by periodic lease job (`FirebaseAuthorizationLeaseSweepJob`), cleared on session revocation / logout (`FirebaseSessionRevocationListener`), and completely purged on account deletion (`FirebaseAuthorizationProjectionService`). | `docs/firebase-realtime.md:27-39`, `:118-122`; `server/src/main/java/com/nexachat/firebase/projection/FirebaseAuthorizationLeaseSweepJob.java:45-69`; `server/src/main/java/com/nexachat/firebase/auth/FirebaseSessionRevocationListener.java:35-57`; `server/src/main/java/com/nexachat/firebase/projection/FirebaseAuthorizationProjectionService.java:110-129` |
| Server backups | `demo/backup.sh` creates SQL dumps on demand; no backup retention period is defined in the repository | `docs/operations.md:650-674` |

Deletion actions that exist:

- The account owner can delete the account in the app: Profile (Settings) >
  Delete account, confirmed with the current password, calls
  `DELETE /api/v1/users/me` and returns `204`. One transaction revokes every
  refresh token and sets the access-token cutoff, deletes push/device
  registrations, deletes contact edges in both directions, removes every group
  membership through the leave path (admin hand-over rules apply, including the
  sole-admin promotion), deletes the avatar file after commit, deletes unbound
  uploads (file and metadata), private labels, group join requests, AI
  consent/request-limit rows, the summary and ask caches of the user's
  conversations, and (when Firebase is enabled) purges all user nodes in
  Firebase RTDB (`accounts/`, `sessions/`, `presence/`, `presenceReaders/`,
  `blocked/`, `members/`, `typing/`, and calls involving the user at `calls/`),
  then sets `users.deleted_at`, clears the phone (released for
  re-registration), replaces the display name with a neutral value, clears
  profile/privacy fields and overwrites the password hash with an unusable
  tombstone. Direct conversations and sent messages/attachments stay visible to
  other members attributed to the deleted account. After deletion, login with
  the old phone is treated as an unknown user, search never returns the account,
  profile reads show the neutral deleted state, and registering the same phone
  creates a new, unrelated account. Migrations V26 and V27; in-app
  `DeleteAccountFragment`; public page `site/delete-account.html`.
- The lost-device web flow on `site/delete-account.html` posts the phone number to
  `POST /api/v1/account-deletion/request` and the code to
  `POST /api/v1/account-deletion/confirm`. The public request always returns
  `202` with a neutral message whether or not the phone exists or has a verified
  email; the code is mailed only to an existing, non-deleted account with a
  verified recovery email. Confirmation returns `204` and deletes through the
  same `AccountDeletionService` tombstone path. The page hides the form and says
  web deletion is not available until the operator sets its
  `nexachat-api-base` meta value and the server CORS/CSP allow that origin.
- A message author may recall their own message within 24 h; a current group
  admin may recall any group message. The row becomes a tombstone, mentions,
  reactions and pins are removed, and attachment bytes remain by default
  (`message/MessageService.java:406-449`; `message/MessageRecallProperties.java:14-15`).
- Logout clears the local Room cache and tokens as described above; it does not
  delete server data.
- Logout-all clears all local tables, including the outbox
  (`data/AuthRepository.java:195-202`; `data/AccountDataCleaner.java:109-112`).

Recovery email and deletion caveats:

- Account deletion clears `users.email`/`email_verified_at`, deletes the user's
  `email_otps` rows, and removes Google identity rows and link nonces in the same
  transaction. There is still no scheduled cleanup job for stale OTP rows that
  belong to active accounts.
- Deleting the account also deletes the user's `conversation_ai_opt_outs` row.
  Messages that remain visible from that account are therefore no longer
  excluded from AI prompts by that opt-out; the retained text becomes eligible
  for AI features again. The owner should confirm this is intended or keep the
  opt-out row for retained messages.
- The deletion service deletes the summary and ask caches for every conversation
  the user belonged to, not only entries the user requested, because the cache
  tables are conversation-scoped and have no requester column. This can remove
  cached answers other members triggered.

Deletion actions that do **not** exist:

- No self-service data export.
- No hard-delete endpoint for an individual message; recall is a soft delete
  (author within 24 h, group admin any time).
- No automatic hard delete of a tombstoned account or its retained messages.
- No public deletion route for a user without a verified recovery email. That
  user is routed to the developer contact on the Play listing.
- No separate "delete my data" request form.

## Encryption

| Path | Current behaviour | Evidence |
|---|---|---|
| Android release traffic | Cleartext HTTP/WS is disabled in the release network policy; only the system CA store is trusted | `android/app/src/main/res/xml/network_security_config.xml:10-16`; `android/app/src/test/java/com/nexachat/security/AndroidPrivacyConfigurationTest.java:60-80` |
| Signed release build | `tools/release/release-url-guard.sh` rejects an `API_BASE_URL` that is not `https://` | `tools/release/release-url-guard.sh:78-88` |
| Debug / localSmoke | Cleartext is permitted only in these development variants | `android/app/src/debug/res/xml/network_security_config.xml:10-12`; `android/app/src/localSmoke/res/xml/network_security_config.xml:9-11`; `android/app/src/test/java/com/nexachat/security/AndroidPrivacyConfigurationTest.java:82-113` |
| Server edge | TLS is expected to terminate at a reverse proxy; the Compose stack itself is local HTTP | `docs/operations.md:353-356` |
| Server to FCM / LLM | HTTPS endpoints; summary/RAG base URLs reject plain `http://` except loopback | `docs/ai-summary.md:125-128`; `docs/rag.md:63-66` |
| Recovery email delivery | SMTP uses STARTTLS with a required upgrade on the configured port (default 587); Resend uses HTTPS `https://api.resend.com/emails`. The code is sent in the email body and is visible to the mailbox provider. | `application.yml` (spring.mail STARTTLS); `mail/SmtpMailSender.java`; `mail/ResendMailSender.java:30-48` |
| Google identity verification | The server fetches Google's rotating public keys over HTTPS and verifies the ID token signature, issuer, audience and expiry; no client secret is stored in the app. | `auth/google/GoogleIdTokenVerifier.java`; `application.yml:69` |
| Firebase bridge and ephemeral transport | HTTPS Realtime Database REST calls and Firebase Admin SDK calls from server; TLS (HTTPS and WSS) from Android client to Firebase Auth and Realtime Database; service-account key stays server-side. | `server/src/main/java/com/nexachat/firebase/client/FirebaseRtdbRestClient.java`; `android/app/src/main/java/com/nexachat/core/realtime/ephemeral/FirebaseEphemeralStateSource.java:172-196`; `docs/firebase-realtime.md:42-45` |
| At rest on device | Room and SharedPreferences are not encrypted; session and FCM tokens are Keystore-encrypted | `core/database/DatabaseModule.java:24-38`; `core/storage/TokenStore.java:23-31` |
| At rest on server | PostgreSQL and the attachment volume are not encrypted by the app; recovery email, OTP hashes and Google identity rows are plain database columns protected by database access controls. No end-to-end encryption. | `docs/known-limitations.md:58`; `db/migration/V1__init.sql`; `db/migration/V27__account_recovery_email_otp.sql`; `db/migration/V28__google_signin.sql` |

The release policy proves the app cannot silently use cleartext. It does not
prove that a particular production deployment has a valid TLS certificate or
that the server is reachable; that remains an operator check.

## Manifest permissions and user-facing reasons

All permissions are declared in `android/app/src/main/AndroidManifest.xml:6-16`.
There is no `READ_CONTACTS`, location, camera, storage, phone-state, SMS, or
calendar permission. Google sign-in uses the Credential Manager API and adds no
runtime permission.

| Permission | Type | Why the app requests it | Where used | Status |
|---|---|---|---|---|
| `INTERNET` | Normal | Connect to the NexaChat REST/STOMP server and to Google Credential Manager | `android/app/src/main/AndroidManifest.xml:6`; all network features | Supported by code (main) |
| `ACCESS_NETWORK_STATE` | Normal | Detect connectivity loss for the offline banner and outbox retry | `android/app/src/main/AndroidManifest.xml:5-7`; outbox sync | Supported by code (main) |
| `RECORD_AUDIO` | Dangerous (runtime) | Record voice messages and provide microphone audio during a call | `feature/chat/ChatFragment.java:471-473`; `call/ui/CallActivity.java:415-437`; `call/ui/IncomingCallActivity.java:297-320`; rationale `android/app/src/main/res/values/strings_call.xml:25` | Supported by code (main) |
| `FOREGROUND_SERVICE` | Normal | Keep an active call's foreground service alive | `call/service/CallForegroundService`; `android/app/src/main/AndroidManifest.xml:76-80` | Supported by code (main) |
| `FOREGROUND_SERVICE_MICROPHONE` | Normal | Declare microphone use by the ongoing call service | `android/app/src/main/AndroidManifest.xml:76-80` (`foregroundServiceType="microphone|phoneCall"`) | Supported by code (main) |
| `FOREGROUND_SERVICE_PHONE_CALL` | Normal | Declare the call foreground-service type | `android/app/src/main/AndroidManifest.xml:76-80` | Supported by code (main) |
| `POST_NOTIFICATIONS` | Runtime (Android 13+) | Show incoming-call and missed-call notifications | `call/ui/CallActivity.java:443-458`; `call/ui/IncomingCallActivity.java:326-341`; `call/service/CallNotifications.java`; rationale `android/app/src/main/res/values/strings_call.xml:28` | Supported by code (main) |
| `USE_FULL_SCREEN_INTENT` | Normal / special access | Show the incoming-call screen over the lock screen | `call/service/CallNotifications.java:85`; `android/app/src/main/AndroidManifest.xml:13` | Supported by code (main); Play declaration needed |
| `MODIFY_AUDIO_SETTINGS` | Normal | Route call audio to earpiece/speaker and request audio focus | `call/media/WebRtcMediaEngine.java:362-377`; `feature/chat/voice/AndroidVoiceAudioFocus.java` | Supported by code (main) |
| `VIBRATE` | Normal | Vibrate for an incoming call | `call/media/CallRingtonePlayer.java:15`, `:46-53` | Supported by code (main) |
| `WAKE_LOCK` | Normal | Keep the CPU awake briefly while handling an FCM push and while a call uses the proximity lock | `notification/NexaFirebaseMessagingService.java:20-38`; `call/ui/CallActivity.java:551` | Supported by code (main) |

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
   configured in the app or repository. The policy text must also cover the
   recovery email, the deletion form, and Google sign-in before submission.
2. **Account deletion** — Play requires an in-app deletion path and a web
   deletion URL for apps that create accounts. The repository includes
   `DELETE /api/v1/users/me`, `site/delete-account.html`, and the Android
   Settings flow; enable the web form (API base meta, CORS and CSP), deploy the
   page, and set its URL in Play. Decide whether to add a retention job for stale
   `email_otps` rows on active accounts.
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
12. **Post-logout media cache** — ordinary logout still does not evict
    downloaded attachments and image caches; account deletion does. Decide
    whether ordinary logout must also wipe them.
13. **App Links / Play signing** — the release App Links fingerprint must match
    the Play App Signing certificate; this is tracked in
    `docs/known-limitations.md:53` and is outside the Data safety form.
14. **Display-name search** — the API now omits the `phone` field from
    display-name prefix results unless the caller already has that user as a
    contact (`user/UserService.java:96-107`). Confirm the contact exception is
    intended and disclose it in the privacy policy.
15. **Google sign-in disclosure** — the owner must create the Google Cloud OAuth
    consent screen and client IDs, register the release/Play App Signing SHA-1,
    and decide whether Google identity data is described as collected directly
    by NexaChat or as a Google service. Google sign-in is off by default;
    Google-only account creation requires `NEXACHAT_GOOGLE_SIGNUP_ENABLED=true`.
    Unlinking the last sign-in method is refused.
16. **Email provider** — choose SMTP (the owner's mail account with an app
    password) or Resend, verify the sending domain, and confirm the provider's
    retention/DPA terms. Mail is disabled by default and the public deletion
    request still returns `202` without sending.
17. **Firebase bridge and ephemeral transport** — The Firebase Realtime Database
    security rules (`tools/firebase-rules/database.rules.json`), server
    projections and backfill/sweep jobs (`server/src/main/java/com/nexachat/firebase/`),
    and Android client ephemeral transport (`FirebaseEphemeralStateSource`,
    `EphemeralStateRouter`, `FirebaseCallStatusDisplayAdapter`) are implemented
    and contract-tested. However, the feature is disabled by default on both
    backend (`FIREBASE_REALTIME_ENABLED=false`, `FIREBASE_CALL_STATUS_ENABLED=false`)
    and Android (`firebaseRealtimeEnabled=false`, `firebaseCallStatusEnabled=false`),
    with STOMP over WebSocket remaining the default and authoritative transport.
    The owner must decide whether to enable Firebase RTDB for production builds.
    If enabled, the owner must deploy `database.rules.json` to the production
    Firebase project, confirm Google Cloud / Firebase service provider (Data
    Processing Addendum) terms so that ephemeral presence/typing processing is
    classified as Collected for App Functionality rather than a third-party
    share, and disclose the ephemeral collection in the Data Safety form and
    privacy policy. If kept disabled (default), no Realtime Database connection
    is initiated.
18. **OTP row cleanup** — decide whether to add a retention job for consumed or
    expired `email_otps` rows on active accounts. Account deletion already
    deletes the user's OTP rows.
19. **AI opt-out after deletion** — decide whether deleting
    `conversation_ai_opt_outs` (so retained messages become eligible for AI
    again) is the intended policy, or keep the opt-out row while the messages
    are retained.
20. **Web deletion page deployment** — `site/delete-account.html` is in the
    repository but is not live until the operator deploys it with a configured
    API base meta, CORS, and CSP. Re-check the published page before using it as
    the Play deletion URL.

## Verification notes

- Source inspected: Android manifest, backup/network XML, Gradle catalog,
  privacy configuration test, Room entities and database module, DataStore and
  Keystore storage, attachment/media caches, auth/logout/deletion flow, Google
  Credential Manager client, server Flyway migrations V1–V33, controllers,
  account-deletion and email-OTP services, the mail adapters, Firebase bridge
  and RTDB projection code (`server/src/main/java/com/nexachat/firebase/`),
  Android ephemeral transport (`core/realtime/ephemeral/` and
  `FirebaseCallStatusDisplayAdapter`), RTDB security rules and contract tests
  (`tools/firebase-rules/`), retention runner, FCM/LLM configuration, and the
  linked architecture/security/operations/RAG/summary/firebase-realtime docs.
- Account deletion was checked against the server contract (`DELETE
  /api/v1/users/me`, password or recovery code, `204`, V26 `deleted_at`, neutral display
  name, released phone, V27 email columns, OTP hashing) but not executed against
  a built server, APK, deployed site or Play Console; this document is static
  source evidence.
- Build, unit tests, instrumentation, and emulator checks were **NOT_RUN**: this
  document is static source evidence and the change does not modify code; the
  docs gate is `python tools/checks/check-markdown-links.py`.
- Play Console state, production TLS certificates, production FCM/AI/Google/
  Firebase keys, live email delivery, and legal/privacy-policy review are **not
  verified** by this repository.
