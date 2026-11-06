# NexaChat Viva Question Bank

> Defense Q&A for the final project (Ngân hàng câu hỏi viva/bảo vệ). Every
> answer cites the shipped code — graders check. Path convention:
> `server/src/main/java/com/nexachat/…` and
> `android/app/src/main/java/com/nexachat/…` are abbreviated to the module or
> feature that follows. Read `docs/rtm.md` alongside this file: it maps each
> rubric row to its proof.
>
> How to use: each entry is a likely question (VI + EN), a model answer that
> teaches the *reasoning* (not just the fact), then the follow-up traps
> interviewers actually spring, with the correct response.

---

## 1. Kiến trúc & patterns — Architecture & patterns

### Q1.1 — Tại sao STOMP trên raw WebSocket, không dùng SockJS?

*Why STOMP over raw WebSocket instead of SockJS?*

**Model answer.** SockJS exists to give *browsers* fallback transports when a
real WebSocket is unavailable — it emulates the socket over XHR streaming,
iframe tricks, etc. NexaChat has exactly one client: the native Android app,
which already speaks WebSocket natively via OkHttp and ships its own STOMP
codec (`core/network/stomp/StompClient.kt`, `StompFrame.kt`, proven by
`StompCodecTest`). A fallback transport would be dead code on this client set,
so the server registers a plain endpoint — `registry.addEndpoint("/ws")` with
no `.withSockJS()` call (`config/WebSocketConfig.java:47-54`) — and both sides
stay simpler: no SockJS session protocol, no `/ws/websocket` URL variants, no
XHR fallback surface to secure. STOMP itself still pays for itself: frames
carry `destination`, typed commands and subscription ids, so authZ can hook
`SUBSCRIBE`/`SEND` declaratively (`ws/StompAuthChannelInterceptor.java`) and
Spring routes sends to `@MessageMapping` methods (`ws/WsMessageController`).
The full rationale is written down in `docs/websocket-protocol.md` §1.

**Follow-up traps.**
- *"So SockJS is a bad choice in general?"* — No. Wrong tool *for this client
  set*. If a web client ever ships, add `withSockJS()` on a second endpoint;
  the STOMP contract (destinations, envelope, auth rules) would not change.
- *"What do you lose without SockJS?"* — Only what SockJS adds: transport
  fallback for browsers and its session emulation. The frame-level protocol is
  identical either way.

### Q1.2 — "Persist-before-broadcast" nghĩa là gì, và tại sao phải là AFTER_COMMIT?

*What does persist-before-broadcast mean, and why specifically the AFTER_COMMIT phase?*

**Model answer.** No frame ever leaves the server for a row that is not yet
durable. `MessageService.send` does the writes inside one `@Transactional`
method — insert the message, advance `conversations.last_message_*`, bump the
sender's own read watermark, bind attachments — then *publishes a Spring
event* (`MessageService.java:110-150`). The actual broker send happens in
`MessageBroadcastListener`, annotated
`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`
(`message/MessageBroadcastListener.java:43-51`). Spring delays the listener
until the transaction commits, so a subscriber that receives
`MESSAGE_CREATED` can immediately `GET` the row via REST — which is what makes
the REST-only resync story sound (`docs/websocket-protocol.md` §6). The same
discipline covers `InteractionBroadcastListener` (reactions/pins) and
`ConversationBroadcastListener` (member/group changes). If we broadcast
*inside* the transaction and it then rolled back — say, on a constraint
violation — subscribers would have rendered a phantom message that no REST
read can ever return.

**Follow-up traps.**
- *"Why not just broadcast after the service method returns?"* — A
  `@Transactional` method returns *before* commit; the proxy commits
  afterwards. Calling `convertAndSend` in the controller would still race the
  commit. `AFTER_COMMIT` is the only hook that is guaranteed post-durability.
- *"What if the AFTER_COMMIT listener throws?"* — The transaction is already
  committed, so the message is durable; the frame is lost for that send, and
  the client's `afterSeq` resync on reconnect repairs the gap. Durability
  first, delivery second — that ordering is the whole point.

### Q1.3 — Tại sao UI không render trực tiếp từ WS frame? Room là SSOT nghĩa là gì?

*Why does the UI never render WS frames directly — what does "Room is the single source of truth" buy you?*

**Model answer.** Every render source is a Room `Flow`. The chat screen
collects `MessageRepository.messagesFlow`, which `combine`s three DAO streams
— `messageDao.messagesForConversation` (SENT rows), `outboxDao.pendingOrdered`
(SENDING/FAILED rows) and `reactionDao.reactionsForConversation` — into the
`List<ChatMessage>` the UI sees (`data/MessageRepository.kt:198-210`). A WS
`MESSAGE_CREATED` never touches the UI; it is *written through* to Room
(`onMessageCreated` → `messageDao.upsert`, line ~475-488) and the flow
re-emits. The payoff is four-fold: (1) **process-death safe** — reopen the
app, history is instant, nothing lived only in memory; (2) **one merge point**
— optimistic outbox rows and server rows unify in a single sorted list; (3)
**one dedup rule** — `INSERT … ON CONFLICT IGNORE` by message id means a
redelivered frame or an overlapping history page can never double-render
(`core/database/Daos.kt:18-22`); (4) **testable** — the repository is JVM-unit
tested (`MessageRepositoryTest`) without a composable in sight.

**Follow-up traps.**
- *"Doesn't the Room round-trip add latency?"* — The emit is an in-memory flow
  notification; the ordering correctness it buys (render strictly by `seq`)
  outweighs a sub-millisecond hop.
- *"Two writers — WS ingest and a history page — race on the same row?"* —
  Both go through the same `upsert(IGNORE)`; the stored row wins by id, and
  the list is ordered by `seq` at read time, so arrival order never corrupts
  history.

### Q1.4 — Tại sao modular monolith mà không phải microservices?

*Why a modular monolith instead of microservices?*

**Model answer.** Because the invariants that make NexaChat *correct* are
cross-module and transactional. The send path atomically writes a message row,
advances the denormalized `conversations.last_message_*`, moves a member's
read watermark and binds attachments — that is one DB transaction in
`MessageService.send`. Split that into services and you need distributed
transactions (Saga/outbox on the server side too) to keep the same guarantee —
enormous cost for a 4-member, one-deployment project. What we keep instead is
the *boundary discipline*: the codebase is partitioned by feature package —
`auth`, `user`, `contact`, `conversation`, `message`, `attachment`, `ws`,
`common`, `config` — each with a `package-info.java`, controllers thin,
services owning use cases. The documented cost is the single-node ceiling:
the `SimpleBroker` is in-process fan-out (`WebSocketConfig.java:58`, declared
in `docs/known-limitations.md` §1), and `RateLimitService` buckets are
in-memory (`RateLimitService.java:24-27`); both are explicit, honest
trade-offs rather than hidden ones.

**Follow-up traps.**
- *"How would you scale to two server instances?"* — Replace the simple broker
  with a broker relay (RabbitMQ/ActiveMQ — the STOMP contract is unchanged,
  `docs/websocket-protocol.md` §8), move rate-limit buckets to Redis/JDBC
  (Bucket4j supports it), and swap `LocalStorageService` for object storage.
- *"Isn't a monolith harder to maintain?"* — Only when boundaries rot. The
  package structure is what keeps it maintainable; microservices would move
  the same coupling into the network where it is harder to refactor.

### Q1.5 — Tại sao monorepo? Hai phần build bằng gì?

*Why a monorepo, and how is each side built?*

**Model answer.** `android/`, `server/`, `docs/`, `demo/` live in one
repository because the REST and STOMP contracts change atomically — a feature
PR touches endpoint, envelope, client DTO and screen in one commit, and
`docs/api-contract.md` + `docs/websocket-protocol.md` document the seam.
Builds stay independent: the server is Maven (`server/pom.xml`, Spring Boot
3.4.1, Java 17), the app is Gradle with a version catalog
(`android/gradle/libs.versions.toml` — no hardcoded versions in
`build.gradle.kts`). CI runs all three gates on every PR — `mvn -B verify`
against a Postgres service, `./gradlew testDebugUnitTest`, and a grep-based
secret scan (`.github/workflows/ci.yml`).

**Follow-up traps.**
- *"Doesn't the monorepo couple your releases?"* — For a course project they
  *are* one deployable unit. If they ever split, the contract docs are the
  migration boundary — that is exactly what they were written for.
- *"Any duplication across the two sides?"* — Deliberately minimal: the wire
  DTOs are declared on each side (`core/network/dto/`, `server/…/dto/`), and
  the client tolerates unknown fields — forward-compatible by design
  (`RealtimeSession.kt` collapses unknown `eventType`s into `ChatEvent.Unknown`).

### Q1.6 — Send path qua WS và REST có bị duplicate logic không?

*The message can arrive over STOMP or REST — is the send logic duplicated?*

**Model answer.** No — `MessageService.send` is the single enforcement point
for send authZ, idempotency and sequencing; its javadoc states this
explicitly (`MessageService.java:35-39`). `WsMessageController.send`
(`@MessageMapping("/conversations/{id}/messages")`) and
`MessageController.send` (`POST /api/v1/conversations/{id}/messages`) both
delegate to it. The only difference is the error surface: the WS controller
catches `ApiException` and rethrows `SendRejectedException` carrying the
`clientMessageId` so the error frame routes back to the originating session
(`WsMessageController.java:63-72,93-97`), while REST returns the standard
error envelope. On the client this is what makes the offline fallback
seamless: `MessageRepository.dispatchOutbox` tries the STOMP SEND first and
falls back to the REST POST with the *same* `SendMessageRequest` — identical
`clientMessageId`, identical server behavior (`data/MessageRepository.kt:391-419`).

**Follow-up traps.**
- *"So membership is checked twice on the WS path?"* — Yes, deliberately:
  `StompAuthChannelInterceptor` stops non-members at the frame layer (cheap,
  no business logic invoked), then `requireMember` in the service is the
  authoritative check under the service contract — belt and braces, and the
  service stays safe when invoked from other callers.
- *"Could the two paths ever disagree?"* — Not by construction: same method,
  same transaction, same broadcast. Any divergence would be a bug in one
  adapter, not in the model.

### Q1.7 — Tại sao codebase vừa Kotlin vừa Java?

*Why is the Android codebase split between Kotlin and Java?*

**Model answer.** The split follows the project's language rule, not a
technical limitation. Jetpack Compose is a Kotlin-only UI toolkit, so every
`@Composable` screen and preview stays Kotlin — that is framework necessity.
All **new** non-Compose Android code is written in Java: pure-logic helpers
(`PresenceFormatter`, `SearchHighlightHelper`, `ThemeMapper`, `ForwardFilter`,
`MessageUiRules`, `MessageResolver`, `SearchUiRules`, `SummaryUiRules`,
`PresenceTickerHelper`), the call
core/media/service/UI layer, the summary data layer, the Hilt modules (`di/`),
and their unit tests. Existing Kotlin files (ViewModels, repositories, screens)
are not converted; edits to them stay Kotlin. The server is Java throughout.
The rule keeps the UI idiomatic while making the business and domain logic
uniformly Java for the course's Java-focused review
(`android/app/src/main/java/com/nexachat/core/ui/*.java`,
`android/app/src/main/java/com/nexachat/call/**`,
`android/app/src/main/java/com/nexachat/summary/**`).

**Follow-up traps.**
- *"Is Compose really unavailable in Java?"* — Yes. Compose is built on Kotlin
  coroutines, inline functions and compiler plugins; there is no Java API.
- *"So a Java helper cannot be a Composable?"* — Correct. A Composable that
  needs the logic calls the Java helper; the helper never imports Compose.
- *"Does the mixed language break Hilt or tests?"* — No; both languages compile
  to the same JVM bytecode and Hilt's generated code is language-agnostic. The
  Java helpers have plain JUnit tests (`core/ui/*Test.java`).

---

## 2. Xác thực & bảo mật — Auth & security

### Q2.1 — Thiết kế access token: thuật toán, TTL, claims?

*Access-token design: algorithm, TTL, claims?*

**Model answer.** HS256-signed JWT via jjwt 0.12.6 (`server/pom.xml:45-60`).
Claims: `sub` = user UUID, `iat`, `exp` = `iat + 900s` (15 minutes,
`application.yml:29`), plus a custom `type="access"` claim that
`parseAccessToken` *requires* — a token of any other type is rejected, so a
future non-access token can never be replayed against protected endpoints
(`auth/JwtService.java:30-31,67-86`). HS256 rather than RS256 because one
service both signs and verifies; asymmetric keys only pay off when
independent parties verify without the private key (`JwtService.java:24-26`).
The secret must be ≥32 bytes — the constructor throws
`IllegalStateException` at boot rather than sign with a weak key
(`JwtService.java:33-49`), and `application-prod.yml` gives `JWT_SECRET` *no
default* so prod fails fast instead of silently booting on the dev secret.

**Follow-up traps.**
- *"15 minutes is short — what happens on expiry?"* — By design. The client
  rotates the opaque refresh token (`TokenRefresher`), so a leaked access
  token buys an attacker ≤15 minutes, not a session.
- *"Why not put roles/claims in the JWT?"* — There is exactly one role
  boundary that matters (membership, checked per conversation against the DB
  anyway). Embedding roles would just create a staleness problem between
  `iat` and now.

### Q2.2 — Refresh token: rotation, reuse detection, family revocation hoạt động thế nào?

*How do refresh-token rotation, reuse detection and family revocation work?*

**Model answer.** The refresh token is a 64-char opaque string (48
`SecureRandom` bytes, base64url-no-padding — `TokenService.java:123-127`),
never a JWT. On every `/auth/refresh`, `TokenService.rotate`
(`auth/TokenService.java:68-90`): (1) `SELECT … FOR UPDATE` the row
(`RefreshTokenRepository.findByTokenHashForUpdate`, `@Lock(PESSIMISTIC_WRITE)`
— two concurrent rotates serialize on the token row so they cannot both mint
successors); (2) if `revoked_at` is set → `RefreshTokenException.reused`; if
expired → `invalid`; (3) otherwise mint the successor, mark the old row
`revoked_at` + `replaced_by → new row id` — an auditable chain RT1→RT2→… .
The subtlety lives in `AuthService.refresh`: it is deliberately **not**
`@Transactional`, because when `rotate` throws reuse/expiry, the family
revocation (`tokenService.revokeAllForUser`) must commit in its *own*
transaction *before* the 401 is raised — rolling it back with the rotation
would silently defeat reuse detection (`AuthService.java:33-37,115-132`).
Net effect: an attacker replaying a stolen old token nukes every session of
that user — the legitimate user's next refresh also fails, forcing
re-login, which is exactly the loud signal you want on theft.

**Follow-up traps.**
- *"What does replaced_by buy you — why not just delete the old row?"* —
  Auditability: you can reconstruct the exact rotation chain during a
  postmortem, and a presented-but-revoked token is distinguishable from an
  unknown one (reuse signal vs garbage input).
- *"Why does the row lock matter?"* — Without `FOR UPDATE`, two parallel
  refreshes both read `revoked_at IS NULL`, both insert a successor — two
  divergent chains, and the later one never triggers the reuse alarm. The
  pessimistic lock makes rotation linearizable per token.

### Q2.3 — Tại sao refresh token lưu dưới dạng sha256 hash?

*Why store only the SHA-256 of the refresh token?*

**Model answer.** `token_hash CHAR(64)` in `refresh_tokens` (V1 migration,
`V1__init.sql:28`); only `sha256Hex(raw)` ever touches the DB
(`TokenService.java:114-121`), while the raw value is returned to the client
exactly once. A database leak — SQL dump, backup, read-only injection — then
yields hashes, not usable tokens: an attacker would need a preimage of a
384-bit random value. Note the contrast with passwords: bcrypt is for
*low-entropy* secrets needing a slow KDF and salt; the refresh token is
already 48 cryptographically random bytes, so plain SHA-256 is the right
strength — anything slower would cost the server without buying security.
The hash is also the lookup key (`findByTokenHash`), keeping the equality
check inside the index.

**Follow-up traps.**
- *"Why not encrypt the column instead?"* — Encryption needs a key the server
  can reach; a leak that reads the DB probably reads the config too. A hash
  has no key to steal — the raw token simply does not exist server-side
  outside request scope.
- *"Rainbow table?"* — Irrelevant: the input space is 2^384, not a password
  dictionary.

### Q2.4 — Token lưu trên Android như thế nào? Tại sao Keystore + AES/GCM?

*How are tokens stored on Android — why AndroidKeyStore + AES/GCM?*

**Model answer.** Two layers, each doing one job (`core/storage/`). The AES
key is generated *inside* AndroidKeyStore under alias `NexaChatTokenKey` —
AES-256, GCM, no padding (`AesGcmTokenCipher.kt:32-46`) — and never leaves
the (hardware-backed where available) keystore; app code only ever sees the
cipher. What lands in DataStore Preferences is `Base64(IV ‖ ciphertext+tag)`
per token (`TokenStore.kt:53-59`), so a rooted backup or a leaked
`preferences_pb` file is ciphertext without a key. GCM specifically because
it is *authenticated* encryption: tampered prefs fail `doFinal` instead of
decrypting to garbage (`AesGcmTokenCipher.kt:12-18`), and a fresh random
12-byte IV per encryption avoids the GCM IV-reuse catastrophe — `packGcm`
prepends the IV to the blob (`AesGcmTokenCipher.kt:77-94`, unit-tested on the
JVM). Belt line: `android:allowBackup="false"` in the manifest removes the
backup-exfiltration path entirely (`AndroidManifest.xml:10,18-19`).

**Follow-up traps.**
- *"Why DataStore at all — why not keep tokens in memory?"* — Sessions must
  survive process death (a logged-out-per-kill app is unusable). DataStore is
  the container, Keystore-AES is the protection — the two are orthogonal.
- *"What if the keystore entry is wiped?"* — `decryptString` wraps `runCatching`;
  undecryptable is treated as logged-out (`TokenStore.kt:85-94`) — fail
  closed, never crash.

### Q2.5 — STOMP SUBSCRIBE/SEND kiểm tra quyền ở đâu?

*Where is authorization enforced on STOMP SUBSCRIBE and SEND?*

**Model answer.** In `StompAuthChannelInterceptor` on the client-inbound
channel — the JWT is *not* trusted at the HTTP upgrade layer (the handshake
is `permitAll` in `SecurityConfig` because the token travels in the STOMP
`Authorization` header, never a URL query param that would leak into access
logs). Three gates (`ws/StompAuthChannelInterceptor.java:103-224`): CONNECT
parses the Bearer token into a `StompUserPrincipal` (fail → frame dropped, no
CONNECTED reply); SUBSCRIBE allows `/topic/conversations.{uuid}` only when
`conversation_members` contains the session user — `existsByConversation_IdAndUser_Id`
— and only `/user/queue/{inbox,errors,presence,calls}` (`USER_QUEUE`,
`StompAuthChannelInterceptor.java:81`). Any other `/user/queue/*` destination
is denied. SEND re-runs the same
membership check on `/app/conversations/{uuid}/(messages|typing|read)` before
the frame can reach a `@MessageMapping`. Everything else is deny-by-default,
and denial is silent — return `null`, the frame is dropped, the sender
observes a subscription that never delivers. 27 unit tests in
`StompAuthChannelInterceptorTest` cover the matrix,
including the per-user message/typing rate-limit buckets.

**Follow-up traps.**
- *"Why re-check membership at SEND when the service checks too?"* — The
  interceptor stops non-members without invoking business logic at all, and
  it protects *every* future mapping automatically. The service check stays
  authoritative because the interceptor could be bypassed by another adapter.
- *"Silent drop vs ERROR frame — why silent?"* — Returning `null` leaks
  nothing (no oracle distinguishing "not a member" from "doesn't exist"),
  and it is the channel-interceptor contract; business-level rejections do
  get an explicit error via `/user/queue/errors` (Q3.6).

### Q2.6 — File đính kèm: ai được download? Upload chặn gì?

*Attachment authorization — who may download, and what does upload enforce?*

**Model answer.** Upload is *upload-first, bind-at-send* (V2 migration):
`POST /api/v1/attachments` stores an unbound row (`message_id NULL`,
`owner_id` = uploader — `V2__attachments.sql`). The client never supplies a
trusted MIME: `AttachmentService.upload` reads the real bytes through
`StorageService.store`, which re-sniffs via `MagicBytes` — a whitelist of
signatures (JPEG/PNG/WebP/GIF/MP4/MP3-MP4-OGG audio/PDF/office/ZIP/…) — so an
`.exe` renamed `.png` dies as `MIME_NOT_ALLOWED` (415)
(`attachment/AttachmentService.java:87-119`, `common/MagicBytes.java`).
`storage_key` is server-minted `<subdir>/<uuid>.<sniffed-ext>`; the client
filename is sanitized to display metadata only (`sanitizeOriginalName`,
line 199-222). **Download authZ** (`assertReadable`, line 161-172): the
requester must be the owner *or* a member of the conversation the file is
bound to; unbound uploads are owner-only. The same rule guards both
`GET /attachments/{id}/content` and the raw `/files/**` serving path
(`assertStorageKeyReadable`, line 150-154) — neither door bypasses the
other. Bonus: at send time, `bindAttachments` requires each id exist, be
owned by the sender, still be unbound, and sniff-match the declared message
type (`MessageService.java:371-396`).

**Follow-up traps.**
- *"Why not trust Content-Type?"* — It is client-supplied. The magic-byte
  check is the only ground truth; `MagicBytesTest` includes the
  disguised-executable cases.
- *"A member could fetch an attachment by guessing the id?"* — UUIDs are not
  the boundary; membership is. Guessing an id of a conversation you are not
  in still hits `NOT_MEMBER` (403).

### Q2.7 — Chống enumeration/timing attack làm ở đâu?

*Where do you defend against user-enumeration and timing attacks?*

**Model answer.** Login returns the *same* `INVALID_CREDENTIALS` whether the
phone is unknown or the password is wrong — and the equalization is
deliberate, not accidental (`AuthService.java:90-105,183`). An unparseable
phone at login also maps to `INVALID_CREDENTIALS` (unlike register, where
bad input honestly returns `VALIDATION_FAILED`). The non-obvious part: an
unknown account *still pays one bcrypt compare* — `passwordEncoder.matches`
against `dummyHash`, a well-formed bcrypt hash computed once at startup
(`AuthService.java:64,101`). Without it, "user doesn't exist" returns in
microseconds while "wrong password" takes ~250ms of bcrypt — timing alone
enumerates valid accounts.

**Follow-up traps.**
- *"Registration leaks existence anyway (PHONE_EXISTS)?"* — Yes, and that is
  honest: registration *must* tell the caller the phone is taken. The
  enumeration surface that matters is *login* (unauthenticated, high-value
  target), which is where the uniform response lives. Register is
  additionally rate-limited per IP (10/5min — `RateLimitInterceptor.java:37-40`).
- *"Why compute dummyHash at startup, not a constant?"* — A literal string
  risks not being a valid bcrypt hash (format checks would short-circuit
  `matches` and break timing parity); encoding once at boot guarantees the
  compare does full work (`AuthService.java:64`).

### Q2.8 — Rate limiting được áp dụng thế nào?

*How is rate limiting applied?*

**Model answer.** Bucket4j token-buckets via `RateLimitService`
(`auth/RateLimitService.java`) — greedy refill so the budget trickles back
continuously rather than refilling as a lump at the window boundary. Four MVC
interceptors, wired in `config/WebConfig.java:32-40`: per-IP buckets on the
credential endpoints — login 10/5min, register 10/5min, refresh 30/5min
(`RateLimitInterceptor.java:37-40`); per-user buckets on
`GET /users/search` (30/min), `POST /contacts` (30/min), and conversation
message edit/delete (30/min shared) and message search (60/min). They run as
MVC interceptors rather than servlet filters, so rejections flow through
`GlobalExceptionHandler` as the standard
`429 {"error":{"code":"RATE_LIMITED","retryAfterSeconds"}}` envelope.
Client IP is `getRemoteAddr()` — `X-Forwarded-For` is deliberately *not*
trusted because it is client-supplied and spoofable without a trusted proxy
(`RateLimitInterceptor.java:27-29`). STOMP SEND is bucket-limited too, with
two separate per-user budgets: messages 30 / 10 s (`stomp:messages:<userId>`)
and typing 60 / 10 s (`stomp:typing:<userId>`)
(`StompAuthChannelInterceptor.java:205-213`), with the denial reported to the
originating session as a `/user/queue/errors` `RATE_LIMITED` frame. Buckets
are a bounded in-memory LRU (10,000 keys, 15 min idle) — single-node scope.

**Follow-up traps.**
- *"In-memory buckets — what breaks at scale?"* — Two instances get two
  independent budgets. Declared limitation (`docs/known-limitations.md` §1);
  the fix is a Bucket4j Redis/JDBC backend, no API change.
- *"Why is the STOMP bucket separate from the REST ones?"* — Different
  transport, different rejection path: REST throws `ApiException` into the
  standard 429 envelope, while a STOMP SEND has no HTTP response — the frame
  is dropped and the correlated `clientMessageId` is reported on the error
  queue so the client can flip its outbox row to `FAILED`.

### Q2.9 — Tại sao đăng ký không dùng OTP?

*Why no OTP/verification code on registration?*

**Model answer.** Scope and threat model, stated honestly. Phone-OTP
verification requires an SMS gateway (cost, external dependency, and it would
make the LAN-demo-ability impossible — the demo must run with no internet).
The chosen model is *phone-as-username + password*, which keeps
`register`/`login` self-contained and lets the security budget go to the
parts an attacker actually hits in this deployment: bcrypt cost 12, uniform
login errors + timing equalization, per-IP rate limits on the credential
endpoints, rotation with reuse detection. The trade-off is recorded: the
system cannot prove phone ownership, so a user can register a number they do
not own — acceptable for a course demo, listed as honest scope rather than
pretended away.

**Follow-up traps.**
- *"If you had one more week, where does OTP go?"* — `PhoneService` already
  normalizes to E.164 (libphonenumber); OTP slots in as a verification step
  before `AuthService.register` issues the session — the auth endpoints and
  rate-limit rule for an `/auth/otp` path mirror the existing pattern.
- *"Isn't passwordless better?"* — Different model, not better-or-worse:
  OTP-as-login moves the secret to the phone network (SIM-swap risk) instead
  of removing it.

### Q2.10 — STOMP limiter hoạt động thế nào? Giới hạn thành thật là gì?

*How does the STOMP limiter work, and what are its honest limits?*

**Model answer.** Two independent per-user token buckets live in
`StompAuthChannelInterceptor` (`ws/StompAuthChannelInterceptor.java:166-224`):
`messages` at 30 / 10 s and `typing` at 60 / 10 s, configured by
`nexachat.rate-limit.stomp.*` (application.yml). `consumeSendAllowance` calls
`RateLimitService.tryConsume("stomp:<action>:<userId>", capacity, window)`;
an over-budget frame is **dropped**, not queued, and the denial is reported to
the originating session on `/user/queue/errors` as
`{"code":"RATE_LIMITED","clientMessageId":"<uuid>"}` (typing carries no id).
The session is not closed — the limit is a flood guard, not a ban. The buckets
are Bucket4j greedy-refill token buckets in a bounded in-memory LRU: at most
10,000 keys, idle keys evicted after 15 min (`RateLimitService.java:24-27`).
Honest limits: single-node only (two instances get two independent budgets),
a restart clears the budgets, and the LRU bound means a burst of 10,001
distinct identities evicts the oldest — acceptable for a chat where identities
are logged-in users, not arbitrary IPs.

**Follow-up traps.**
- *"Why drop the frame instead of buffering it?"* — A chat message is only
  useful now; a queued burst would arrive stale and out of order. The sender's
  outbox stays `SENDING` and the user can retry explicitly.
- *"Why is typing bucketed separately?"* — Typing is high-frequency and
  ephemeral; a shared 30/10 s bucket would let a typist starve real messages.
  Separate 60/10 s keeps typing responsive without touching message capacity.
- *"What proves it?"* — `StompAuthChannelInterceptorTest`
  (`send_messagesRateLimit_isPerUserAndReportsCorrelatedErrorWithoutDisconnecting`,
  `send_typingRateLimitIsSeparateAndCannotCreateMessageTraffic`).

---

## 3. Realtime & messaging

### Q3.1 — Sắp xếp tin nhắn theo seq hay timestamp? Seq là global hay per-conversation?

*Message ordering — seq or timestamps? Is seq global or per-conversation?*

**Model answer.** Strictly by `seq`, never by timestamps or arrival order.
`messages.seq` is a global `BIGSERIAL UNIQUE` (`V1__init.sql:90`) — the
database sequence assigns it at INSERT, so concurrent senders draw distinct
seqs with no per-conversation counter or locking (`MessageService.java:40-46`).
Within one conversation seq is still monotonic; gaps are harmless (a seq can
be "spent" by another conversation or a rolled-back insert). The contract
(`docs/websocket-protocol.md` §6): clients render and diff by `payload.seq`;
`serverTs` is display-only. Client clocks are never trusted — Room orders
`ORDER BY seq ASC` (`Daos.kt:25`) and the inbox's `updateLastMessage` guards
with `seq > lastMessageSeq` so a delayed lower-seq event cannot rewind the
projection (`Daos.kt:90-96`).

**Follow-up traps.**
- *"Why not per-conversation seq — wouldn't global gaps confuse the resync?"* —
  Resync uses `afterSeq` (exclusive `>`), so gaps cost nothing; a
  per-conversation counter needs either locking or a max+1 race — strictly
  worse for correctness under concurrency.
- *"Two messages same millisecond — createdAt tie?"* — Exactly why timestamps
  cannot be the sort key: they are not unique and not monotonic across
  concurrent writers. `seq` is both.

### Q3.2 — clientMessageId idempotency hoạt động thế nào?

*How does clientMessageId idempotency work?*

**Model answer.** `clientMessageId` is a sender-generated UUID, and the
idempotency contract lives *in the database*, not in service code:
`UNIQUE (sender_id, client_message_id)` (`V1__init.sql:100`) plus
`INSERT … ON CONFLICT (sender_id, client_message_id) DO NOTHING`
(`MessageRepository.java:30-40`). No check-then-insert window exists — two
racing sends collide at the constraint and exactly one inserts.
`MessageService.send` then *re-reads the row that won* and re-broadcasts it
(`MessageService.java:120-149`): on a dedup hit the retrying sender still
receives `MESSAGE_CREATED` — that frame *is* its SENT acknowledgment, and
receivers dedup by message id so the re-broadcast is harmless
(`docs/websocket-protocol.md` §6). A misbehaving client that reuses the key
for a *different* conversation gets `VALIDATION_FAILED`, not silent
corruption (line 130-135). Client side: the outbox row is keyed by
`clientMessageId` and deleted when the matching broadcast lands — but only
when the sender is actually me (`MessageRepository.kt:482-484`), so a
foreign sender's key can never clear my pending row.

**Follow-up traps.**
- *"Why UNIQUE(sender_id, client_message_id), not client_message_id alone?"* —
  The id is only unique per sender by contract — two different users can
  legitimately mint the same UUID (collisions or copy-paste clients). Scoping
  the key by sender makes that case legal instead of a false dedup.
- *"At-least-once delivery means receivers need dedup too?"* — Yes — Room's
  `INSERT IGNORE` on the message PK (`Daos.kt:18-22`) is the receiver-side
  half of the same rule.

### Q3.3 — Read receipt dùng watermark — tại sao monotonic mà không per-message?

*Read receipts use a watermark — why monotonic last_read_seq, not per-message state?*

**Model answer.** `conversation_members.last_read_seq BIGINT` — one number
per (conversation, user), the highest seq the user has seen
(`V1__init.sql:79`). Unread count is then `messages.seq > last_read_seq` — a
comparison, not a join over per-message rows. Per-message read state would
be a row per reader per message — write amplification and a storage shape
that scales with group size × traffic; the watermark stores O(1) state per
member and one `UPDATE` marks an arbitrary batch read at once. The advance
is monotonic *in SQL*: `SET lastReadSeq = :seq WHERE … AND lastReadSeq < :seq`
(`ConversationMemberRepository.advanceReadWatermark`) — an out-of-order or
replayed `upToSeq` can never *un-read* messages. The server also rejects an
`upToSeq` greater than the conversation's latest `seq`
(`MessageService.java:248-252`), and the broadcast carries the *persisted*
watermark, not the requested one (`MessageService.java:254-255`), so a
replayed frame states the same truth. `markRead` broadcasts `MESSAGE_READ`
even when the watermark did not move (`MessageService.java:239-256`). Client
side,
`sendRead` dedups against `lastMarkedRead` before hitting the network
(`MessageRepository.kt:432-441`).

**Follow-up traps.**
- *"What does per-message buy that you lose?"* — Per-message receipts (who
  read *which* message in a group). The trade is deliberate for this scope:
  group "seen by" lists would need the join table; the watermark answers the
  badge and the partner receipt cheaply.
- *"Concurrent markRead from two devices of the same user?"* — Both advance
  the same row; monotonic `WHERE` makes the outcome order-independent —
  whichever lands larger wins, and a smaller late write is a no-op.

### Q3.4 — Reconnect xong client resync như thế nào? afterSeq vs beforeSeq?

*How does the client resync after reconnect — afterSeq vs beforeSeq?*

**Model answer.** Two directions, one index. `beforeSeq` pages *backwards*
into history (infinite scroll up — exclusive cursor, newest-first,
`idx_msg_conv_seq (conversation_id, seq DESC)`; `MessageService.getHistory`,
line 158-202). `afterSeq` pages *forwards* — the reconnect path. On every WS
`Open`, `RealtimeSession` emits `resyncRequests`; `MessageRepository.resyncForward`
takes `messageDao.maxSeq(conversationId)` and loops
`GET …/messages?afterSeq=<max>` until `hasMore` is false or the page stops
advancing — so a >1-page gap can never leave a permanent hole
(`MessageRepository.kt:626-645`). A cold conversation (no local rows) falls
back to the newest page. This is the other half of persist-before-broadcast:
every missed event is guaranteed to be a committed row, so REST alone is a
complete recovery channel — no special catch-up protocol needed.

**Follow-up traps.**
- *"Why not just re-fetch the last page?"* — If more than a page arrived while
  offline you would permanently skip the middle. `afterSeq` walks forward
  *from where the client actually is* — the local `MAX(seq)` is the cursor.
- *"Infinite loop guard?"* — The loop exits when `!hasMore` or
  `lastSeq <= afterSeq` — a page that does not advance the cursor terminates
  rather than spinning (`MessageRepository.kt:704`).

### Q3.5 — Typing indicator: throttle/TTL làm ở đâu?

*Typing indicator — where is it throttled, and how does it expire?*

**Model answer.** Two numbers in `ChatViewModel`
(`feature/chat/ChatViewModel.kt:406-407`): outbound,
`TYPING_SEND_INTERVAL_MS = 3_000` — `onInputChange` emits at most one
`sendTyping` per 3 s while the draft is non-blank (line 211-218), so a fast
typist cannot flood the topic; inbound, `PEER_TYPING_TTL_MS = 5_000` — each
`USER_TYPING` event sets `peerTyping` and a coroutine TTL turns it off 5 s
later unless refreshed (line 196-207). Server side the typing SEND is a
`{}` body to `/app/conversations/{id}/typing`; `WsMessageController.typing`
fans it straight onto the topic with no persistence (typing is ephemeral —
never a row). The simple broker has no per-subscriber filter, so the sender
is *not* excluded server-side; the payload carries `userId` and each client
drops its own signal (`WsMessageController.java:74-80`,
`MessageRepository.kt:449-451` + `isMine` check in the ViewModel).

**Follow-up traps.**
- *"Why client-side throttle as well as a server bucket?"* — Client throttle
  keeps the common case quiet; the server still enforces a separate typing
  bucket (60 / 10 s) so a malicious or buggy client cannot flood the topic
  (`StompAuthChannelInterceptor.java:211-213`). The message bucket (30/10 s)
  is untouched by typing.
- *"Why a TTL instead of a 'stopped typing' event?"* — Cheaper and
  crash-safe: a client that dies mid-typing can never leave a stuck
  indicator — the TTL expires on its own.

### Q3.6 — Error frame về /user/queue/errors được định tuyến thế nào? (Bug destination-rewrite?)

*How are /user/queue/errors frames routed — tell me about the destination-rewrite bug.*

**Model answer.** Server side: a service-level `ApiException` during a
`@MessageMapping` becomes `SendRejectedException` carrying the request's
`clientMessageId`; `@MessageExceptionHandler` +
`@SendToUser(value="/queue/errors", broadcast=false)` returns
`{code, clientMessageId}` to *the originating session only*
(`ws/WsMessageController.java:93-118`). The trap is on the *receive* side:
Spring's user-destination resolver **rewrites** `/user/queue/errors` to a
session-specific form — the delivered MESSAGE's `destination` header reads
like `/queue/errors-user<sessionId>`, not the subscribed destination. An
early client implementation filtered on the destination header and silently
dropped every error frame. The fix: match on the **`subscription` header**
— the broker stamps the id *we* chose (`sub-N` from
`StompClient.subscribe`, `StompClient.kt:242-243`) — so `dispatch` compares
`frame.headers["subscription"]` against
`subscriptionIdFor(USER_ERRORS_DESTINATION)`
(`RealtimeSession.kt:412-418`). On a match the body decodes to
`ChatEvent.SendRejected`, which flips the outbox row `SENDING → FAILED`
(`MessageRepository.kt:465-467`).

Malformed STOMP body conversion or bean-validation failures use the generic
messaging exception handler and return `VALIDATION_FAILED` without
`clientMessageId`; unexpected handler exceptions return `INTERNAL_ERROR`, also
without the id, because the body may not have parsed
(`ws/WsMessageController.java`).

**Follow-up traps.**
- *"Why does Spring rewrite the destination at all?"* — `/user/queue/errors`
  is a logical name; the broker must resolve it to *this* session's queue so
  the frame cannot be delivered to another session of the same user. The
  rewrite is the isolation mechanism — the bug was trusting the delivered
  header instead of the subscription id the protocol gives you for exactly
  this purpose.
- *"What proves the fix?"* — `MessageRepositoryTest` exercises the
  SendRejected → FAILED path; the decode path is covered by the stomp tests.
  And behaviorally: a rejected send now shows a FAILED bubble instead of
  hanging in SENDING.

### Q3.7 — Tin mới đẩy về topic hay /user/queue/inbox? Khi nào dùng cái nào?

*When does an event go to the conversation topic vs per-user inbox queue?*

**Model answer.** Two surfaces with different jobs (`docs/websocket-protocol.md` §4).
`/topic/conversations.{id}` is the room channel — members *watching* the
conversation get `MESSAGE_CREATED`, `MESSAGE_UPDATED`, `MESSAGE_DELETED`,
`MESSAGE_READ`, `USER_TYPING`, `REACTION_UPDATED`, `PIN_UPDATED`, and
`CONVERSATION_UPDATED`. Presence events go to `/user/queue/presence`, not the
conversation topic.
`/user/queue/inbox` is the per-session badge channel — `MESSAGE_CREATED` is
additionally pushed to *every member's* inbox queue so their unread count
updates even when nobody is subscribed to the topic
(`MessageBroadcastListener.java:43-51`). Deliberate asymmetries:
`MESSAGE_READ` is topic-only (receipts matter to people in the room);
reaction/pin events are topic-only too — they change no unread badge
(`InteractionBroadcastListener.java:14-22`); `CONVERSATION_UPDATED` fans out
to the topic *and* to per-user inboxes including the *removed* user — who is
no longer in `conversation_members`, so the event carries
`extraAudienceUserIds` explicitly (`ConversationBroadcastListener.java:43-57`).

**Follow-up traps.**
- *"Why push MESSAGE_CREATED to inbox at all — isn't the topic enough?"* —
  A user on the inbox screen is subscribed to zero topics. Without the inbox
  push their badge/order would only update on the next REST refresh; the
  push keeps the list live, which is the demo-visible behavior.
- *"Security on the inbox queue?"* — The user-destination resolver binds
  `/user/**` to the CONNECT principal's session — a client can subscribe but
  the broker only ever delivers *its own* queue (`StompAuthChannelInterceptor`
  javadoc, line 43-47).

### Q3.8 — Tại sao duplicate send vẫn re-broadcast? Không phải thừa sao?

*Why does a duplicate send still re-broadcast — isn't that redundant?*

**Model answer.** It is deliberate and frozen after review. `MessageService.send`
inserts with `ON CONFLICT (sender_id, client_message_id) DO NOTHING`; when the
insert affects 0 rows it re-reads the winning row and **publishes
`MESSAGE_CREATED` for it anyway** (`MessageService.java:121-149`). That frame
is the retrying sender's SENT acknowledgment: without it, a sender that
retried after losing the first broadcast (reconnect, timeout, REST fallback)
would stay `SENDING` forever. Receivers deduplicate by message id — Room
`INSERT IGNORE` on the message PK (`Daos.kt:18-22`) — so the extra frame is
harmless to everyone else. A `clientMessageId` reused in a *different*
conversation is `VALIDATION_FAILED`, not a silent cross-room dedup
(`MessageService.java:130-135`).

**Follow-up traps.**
- *"Why not suppress the event on a dedup hit?"* — That was tried and reverted:
  it traded a harmless extra frame for a stuck outbox row. The dedup is
  DB-level; the broadcast is the recovery signal.
- *"Two identical frames arrive — does the recipient double-render?"* — No:
  the message id is the primary key, so the second insert is ignored and the
  flow re-emits the same row.
- *"Proof?"* — `MessageServiceTest.send_duplicateInSameConversation_returnsExistingAndRebroadcasts`
  and `MessageFlowIT.concurrentDuplicateClientMessageIdReturnsOneRowAndRebroadcastsBothCalls`
  (real Postgres, concurrent senders).

### Q3.9 — Presence được giới hạn và ẩn theo cài đặt thế nào?

*How is presence scoped and controlled by the privacy setting?*

**Model answer.** `GET/PATCH /api/v1/users/me/settings` stores the user's
`showPresence` preference. The server tracks authenticated STOMP sessions in
the in-memory `PresenceSessionRegistry`; only DIRECT peers receive online and
last-seen updates, and group conversations receive none. Turning presence off
suppresses that user's state from peer inboxes and events. It is one-way: the
user can still see peers who have enabled presence. The last session disconnect
records `last_seen_at`; an offline event is delayed up to five seconds to avoid
a short reconnect flicker (`presence/PresenceSessionRegistry.java`,
`PresenceService.java`, `UserSettingsService.java`). This state is per server
process, so it does not scale across backend nodes (`docs/known-limitations.md`).

**Follow-up traps.**
- *"Can a user see presence from group members?"* — No; presence fan-out and
  inbox status are restricted to DIRECT peers.
- *"Does hiding my status hide the other person's status from me?"* — No, the
  preference controls only the subject's own presence.

### Q3.10 — Edit/delete tin nhắn hoạt động và đồng bộ thế nào?

*How do message edits and deletions work and stay synchronized?*

**Model answer.** The REST routes are
`PATCH` and `DELETE /api/v1/conversations/{id}/messages/{messageId}`. Only a
conversation member who authored the message may mutate it. Edit accepts live
`TEXT` messages, trims and validates the content, and sets `editedAt`. An unchanged normalized value is a successful
no-op. Delete is a soft delete: it retains the row and `seq`, sets `deletedAt`,
clears content, and removes reactions and pins. After commit, the server sends
`MESSAGE_UPDATED` or `MESSAGE_DELETED`; clients update Room, which remains the
render source. Deleted messages remain tombstones in history, while their
attachment metadata and stored bytes are retained but cannot be read through
that deleted message (`MessageService.java`, `MessageBroadcastListener.java`,
`MessageMutationServiceTest`).

**Follow-up traps.**
- *"Can I edit a photo or delete another user's message?"* — No; only the
  author can mutate, and only `TEXT` messages can be edited.
- *"Can a deleted message still be reacted to or pinned?"* — No; both services
  reject it with `MESSAGE_DELETED`.

### Q3.11 — Tìm kiếm tin nhắn dùng thuật toán và giới hạn nào?

*What search method and limits apply to conversation messages?*

**Model answer.** `GET /api/v1/conversations/{id}/messages/search` searches
non-deleted `TEXT` rows in one conversation. `q` is trimmed and must contain
2–100 characters; the page defaults to 20 and is capped at 50, with optional
`beforeSeq` pagination. The query uses escaped `unaccent` + `ILIKE`, so the
match is case- and accent-insensitive and `%`, `_`, and `\` are treated
literally. It is limited to 60 requests per user per minute. There is no
trigram index yet, so larger conversations may require scanning many rows
(`MessageService.java`, `MessageRepository.java`,
`MessageRateLimitInterceptor.java`, `docs/known-limitations.md`).

**Follow-up traps.**
- *"Does it search attachments or deleted messages?"* — No; only live text
  content is matched.
- *"Why escape `%` and `_`?"* — They have wildcard meaning in `LIKE`; escaping
  them preserves literal substring search.

### Q3.12 — Edit/delete gửi hai lần thì sao? Soft delete vs hard delete?

*What happens when the same edit or delete is sent twice — and why soft delete?*

**Model answer.** Both mutations are idempotent at the service level. A repeated
`PATCH` with the same normalized content is a successful no-op and emits no
`MESSAGE_UPDATED`; a repeated `DELETE` finds `deletedAt` already set and returns
the same `204` without clearing anything twice. Delete is a **soft delete**: the
row and its global `seq` are retained, `deletedAt` is set, `content` is cleared,
and reaction rows plus any pin are removed. The client renders a tombstone
("Tin nhắn đã bị xoá") at the original position instead of dropping the bubble,
so history stays coherent and reply quotes keep their target. A hard delete would
break `reply_to_id` and pin/reaction FK integrity and would leave gaps in the
sequence that other clients may already have rendered
(`MessageService.java`, `MessageMutationServiceTest`, `MessageDeletedEvent.java`).
Attachment metadata and stored bytes are retained but are no longer readable
through the deleted message.

**Follow-up traps.**
- *"Does a tombstone keep its reactions?"* — No; reactions are removed on
  delete, and reacting to a deleted message returns `MESSAGE_DELETED`.
- *"Can I restore a deleted message?"* — Not through the API; the content is
  cleared. Restoration would need a backup/undo feature.
- *"Why keep the row at all?"* — Reply quotes, pin history, and sequence
  ordering all reference the message id; soft delete preserves referential
  integrity.

### Q3.13 — Presence: tại sao trễ 5 giây và sao chỉ DIRECT peer?

*Why is the offline event delayed five seconds, and why do only direct peers see presence?*

**Model answer.** Presence is scoped to DIRECT peers because that is the only
place the UI shows it (chat header + inbox dot); groups receive no presence, and
the `show_presence` setting is one-way — hiding your own state does not hide
others' state from you. Hiding while online publishes `USER_OFFLINE` with
`lastSeenAt` null to direct peers; showing it again while online publishes
`USER_ONLINE`. A disconnect while already hidden does not send a second
offline event. The five-second delay is a **coalescing window**: when a
session disconnects, the server waits up to 5 s before emitting `USER_OFFLINE`,
so a quick reconnect (network blip, activity switch, heartbeat timeout) cancels
the transient offline event instead of flickering the peer's UI. The delay is
bounded and server-side (`PresenceSessionRegistry`), and the last disconnect
still persists `users.last_seen_at`. The registry is in-memory and single-node,
so it does not span backend instances.

**Follow-up traps.**
- *"What if the user stays offline longer than 5 s?"* — The offline event is
  emitted after the window and peers flip to "Hoạt động X phút trước".
- *"Is the 5 s delay client or server?"* — Server. The client applies
  `USER_ONLINE` immediately and only learns about offline through the event.
- *"Can I hide from one person only?"* — No; it is an account-level one-way
  switch, not per-contact.

### Q3.14 — Search: unaccent + ILIKE và escape `%`/`_` — tại sao không dùng trigram?

*How does message search fold diacritics, why are `%` and `_` literal, and why is there no trigram index?*

**Model answer.** The query runs as escaped `unaccent` + `ILIKE` against
non-deleted `TEXT` rows in one conversation (`MessageRepository`). `unaccent`
strips diacritics so `khoa` matches `Khoa`/`KHOA`, and `ILIKE` folds case. `%`,
`_`, and `\` are escaped first so they are treated as literal characters, not
`LIKE` wildcards — a user searching `100%` should find the literal text, not
everything starting with `100`. There is currently **no trigram index** on the
substring predicate; at demo scale the planner scans the conversation's rows
using the existing `(conversation_id, seq)` index, and the page is bounded
(default 20, max 50). This is recorded honestly as a scale limitation rather
than claimed as a fast substring search.

**Follow-up traps.**
- *"Why not `pg_trgm`?"* — `pg_trgm` is installed (V1) and used for the
  displayName search index; the message substring predicate does not have a
  trigram index yet, so large conversations may scan many rows.
- *"Does search see attachments or deleted messages?"* — No; only live text.
- *"Is the limit 2–100 characters?"* — Yes; shorter or longer `q` is
  `VALIDATION_FAILED`.

---

## 4. Creative feature — Reply / Reaction / Pin

### Q4.1 — Reaction toggle thiết kế thế nào? Tại sao composite PK?

*How does the reaction toggle work — why is the composite PK the whole design?*

**Model answer.** `message_reactions` has `PRIMARY KEY (message_id, user_id, emoji)`
(`V3__interactions.sql:15-21`) — the PK *is* the toggle contract. One row =
one user's one emoji on one message; it cannot exist twice, by construction.
`ReactionService.toggle` then has *no check-then-act window*: `DELETE` the
row; if nothing was deleted, `INSERT … ON CONFLICT DO NOTHING`
(`ReactionService.java:82-86`). Two concurrent toggles of the same emoji can
never duplicate or corrupt — the PK arbitrates every interleaving. Emoji is
`VARCHAR(16)`, validated in code points so ZWJ sequences and flags are
measured the way PostgreSQL counts them; the emoji is also normalized to NFC
  before the PK lookup (`ReactionService.java:114-122`), so visually identical
  forms in different Unicode compositions share one chip.
After the toggle the *whole aggregate* is computed in-transaction and
broadcast `REACTION_UPDATED {messageId, reactions:[{emoji,count,userIds}]}`
AFTER COMMIT — receivers replace their summary wholesale; the payload
replaces, never merges, client state (`ReactionService.java:88-106`,
`InteractionBroadcastListener.java:35-40`).

**Follow-up traps.**
- *"Why not a unique constraint + a 'reacted' boolean column?"* — A boolean
  row can linger false and still occupies the key; delete-or-insert uses
  *existence itself* as the state — no tombstones, no ambiguity.
- *"Concurrent add + remove on the same key — deadlock?"* — Single-row DML
  under the PK: worst case one transaction briefly locks the index entry;
  the operations are idempotent so either serial order is a legal outcome.

### Q4.2 — Pin tối đa 3 — chống race thế nào? Tại sao FOR UPDATE?

*The ≤3-pin ceiling — how is it race-safe? Why SELECT FOR UPDATE?*

**Model answer.** `PinService.MAX_PINS_PER_CONVERSATION = 3`
(`message/PinService.java:55`). The check is `exists → count < 3 →
insert` — classic check-then-act, so *both* pin and unpin first take
`SELECT … FOR UPDATE` on the parent conversation row
(`findByIdForUpdate` via `lockConversation`, `PinService.java:186-187`). The
conversation row becomes a per-room mutex: every pin/unpin in that room
serializes, so two racing pins cannot both pass the count check — the second
blocks, then sees count=3 and fails `PIN_LIMIT` (409). The composite PK
`(conversation_id, message_id)` (`V3__interactions.sql:26-32`) makes a repeat
pin a no-op by construction (`insertIgnoringConflict`); `pin`/`unpin` are
idempotent — re-pinning an already-pinned message is not a `PIN_LIMIT` hit
(`PinService.java:132-152`), and even no-ops broadcast `PIN_UPDATED` so a
replayed frame still states the truth (`PinService.java:150-151,175-176`).
`listPins` returns the pins **newest-first** (`OrderByPinnedAtDesc`) and
batch-hydrates each entry's attachments/reactions/reply preview in one pass
(`PinService.java:92-118`), so the banner order and the REST order agree and
the bounded ≤3 list costs a constant number of queries.

**Follow-up traps.**
- *"Why not a DB constraint or trigger for the ceiling?"* — A
  count-dependent constraint is not expressible declaratively without
  triggers; a single-row lock is the smallest correct mechanism and it also
  serializes unpin, which a trigger on insert would not.
- *"Why lock the conversation row rather than the pin table?"* — There is no
  single "pins" row to lock before the insert exists; the parent row is the
  stable, always-present mutex per room (and it doubles as the existence
  check + the DIRECT/GROUP type source, `PinService.java:175-178`).

### Q4.3 — DIRECT và GROUP phân quyền khác nhau thế nào?

*How do authorization rules differ between DIRECT and GROUP conversations?*

**Model answer.** The conversation *type* drives the policy, checked against
the member row's `role`. Pinning: in DIRECT, **any member** may pin/unpin;
in GROUP, **ADMIN only** — `requirePinRole` throws `NOT_ADMIN` for a
non-admin group member (`PinService.java:192-198`). The same rule is
  surfaced client-side: `conversations.myRole` (added in Room v6 by
  `MIGRATION_5_6`, still present in Room v7) persists the
caller's member row from the inbox payload, `ChatViewModel.canPin =
!isGroup || myRole == "ADMIN"` gates the long-press Pin item, and the
server remains the real boundary if the gate is bypassed. Group
management mirrors it: add member,
remove other member, rename/re-avatar = ADMIN-only (`GroupService.java` —
`requireAdmin`, line 343-347), while *self-leave* is any member. One
integrity rule: a group with members must keep ≥1 ADMIN, so the sole admin
cannot leave (`GroupService.java:45-49,230-236`).

**Follow-up traps.**
- *"Where does the role live?"* — `conversation_members.role` (`ADMIN`/
`MEMBER`, `V1__init.sql:77`); DIRECT rows carry `MEMBER` — the type check is
  what makes "any member" the DIRECT rule without a special role.
- *"Can a member escalate?"* — No client-visible path sets `role` except
  creation (creator = ADMIN) and the sole-admin promotion guard inside
  `removeMember` — role changes are not exposed as an endpoint.

### Q4.4 — Tại sao reaction state trả về qua broadcast mà không qua REST response?

*Why does reaction state propagate via broadcast instead of the REST response?*

**Model answer.** Because the *authoritative* update is the aggregate — the
whole `{emoji, count, userIds}` set — and every member needs it, not just
the caller. `POST /messages/{id}/reactions` deliberately returns `204`
(`ReactionController.java:38-39`); the durable state fans out as
`REACTION_UPDATED` on the topic AFTER COMMIT. The caller's optimistic Room
write covers the instant-feedback window (`MessageRepository.toggleReaction`,
line 560-592); the broadcast is what *reconciles* it — the payload replaces
the stored summary wholesale (`reactionDao.replaceForMessage`), and the
client's own toggle arrives back over the same channel as other members'
toggles, so all clients converge on one wire shape. A REST-body response
would create two competing truths — the caller's response vs everyone
else's broadcast — with slightly different semantics to reconcile.

**Follow-up traps.**
- *"So the REST response is wasted?"* — It still carries status
  (`204`/`403`/`404`/`409`) — authZ and validation failures arrive
  synchronously and revert the optimistic write. The *payload* is what the
  broadcast owns.
- *"Caller offline — no optimistic reconcile?"* — The `REACTION_UPDATED` is
  still durable state on the server; a later history fetch re-embeds
  `reactions` on the message rows and `storeHistoryItems` replaces the set
  for messages that carried the field (`MessageRepository.kt:325-341`).

### Q4.5 — Reply đến tin đã xóa thì sao?

*What happens to a reply whose target message was deleted?*

**Model answer.** The FK (`messages.reply_to_id`) survives the deletion —
reply rows keep pointing at the target id — and the *preview* degrades
gracefully. `replyRefsById` builds `ReplyRefResponse` per target: when
`deleted_at` is set it emits `deleted=true` and **content=null** — the id
and sender survive, the body does not (`MessageService.java:498-509`, plan
§9.3.6 soft-delete model). The UI renders the "Tin nhắn đã bị xóa"
placeholder from that flag. Two supporting choices: the send path requires
the reply target to exist and live in the *same* conversation — quoting
across conversations is rejected `VALIDATION_FAILED`
(`MessageService.java:304-325`) — and the client denormalizes
`replyToSenderName`/`replyToSnippet` onto its own row (added in Room migration v2→v3,
`Entities.kt:29-35`), so the quote block still renders when the original is
outside the loaded window.

**Follow-up traps.**
- *"Why not cascade-delete the replies?"* — A reply is its own message;
  deleting it would destroy the replier's content over someone else's
  deletion. The FK is intentionally *not* `ON DELETE CASCADE` (soft-delete
  model — `deleted_at` keeps the row anyway).
- *"What happens to a reply when its target is deleted?"* — The reply remains;
  its preview keeps identity but has `content: null` and `deleted: true`. The
  target itself remains a tombstone in history.

### Q4.6 — Tại sao broadcast ngay cả no-op? "Replayed frame is truthful" nghĩa là gì?

*Why broadcast even on a no-op — what does "a replayed frame is truthful" mean?*

**Model answer.** Idempotent *event* design, not just idempotent writes.
`PinService.pin` publishes `PIN_UPDATED {messageId, pinned:true}` even when
the message was already pinned (`PinService.java:136-146`); `markRead`
publishes `MESSAGE_READ` even when the watermark did not move
(`MessageService.java:239-256`); `toggle` always emits the post-toggle
aggregate (`ReactionService.java:88-89`). The payload always states *current
durable truth*, never a delta — so a redelivered, replayed or out-of-order
frame cannot corrupt a receiver: applying it twice yields the same state.
That property is what lets the client apply frames blindly — receivers do
not track "did I already see this event", they just re-apply the stated
truth (reaction set replace, pin upsert/delete, watermark advance are all
monotonic or set-semantics).

**Follow-up traps.**
- *"Delta payloads would be smaller — why the full set?"* — A delta requires
  the receiver to merge correctly and to have seen every prior delta; the
  full-state frame is self-healing — whatever state you hold, applying it
  lands you on truth.
- *"Does the no-op broadcast waste traffic?"* — Marginal cost, real benefit:
  a second client of the same user that missed the first frame converges on
  the replay.

### Q4.7 — Jump-to-quote/pin phân trang giới hạn thế nào?

*How is quote/pin jump paging bounded?*

**Model answer.** `ChatViewModel.jumpToMessage` calls
`MessageRepository.loadUntilLoaded(conversationId, messageId)`, which pages
**backwards** through history with `refreshHistory(beforeSeq = oldestLoaded)`
until the target exists or the budget is spent
(`MessageRepository.kt:292-317`). The budget is `MAX_JUMP_PAGES = 20`
(`MessageRepository.kt:916-917`) — at the 30-per-page history size that is
about 600 messages. If the target is found, `jumpTargets` emits and
`ChatScreen` scrolls to it (`ChatScreen.kt:125-137`); if the budget runs out,
the UI shows `reply_unavailable` ("Message unavailable") rather than silently
doing nothing. The bound exists because a unbounded backward walk on a
10,000-message conversation would hammer the server and the UI; a bounded,
explicit "unreachable" is honest.

**Follow-up traps.**
- *"Why 20 pages?"* — It covers the realistic demo/QA window (~600 messages)
  while bounding worst-case requests. A user can still reach older messages by
  scrolling normally; jump is a convenience, not the only path.
- *"What if the target is in a gap / deleted?"* — Deleted messages keep their
  row, so the jump still lands; the bubble renders the deleted placeholder.
- *"Proof?"* — `MessageRepositoryTest` covers `loadUntilLoaded`; the
  reverse-index mapping is unit-tested by `ChatUiLogicTest`.

### Q4.8 — Chuyển tiếp tin nhắn hỗ trợ loại nội dung nào?

*Which message types can the user forward?*

**Model answer.** Forwarding is a client action for live `TEXT` messages only.
The user picks a target conversation, and the app sends the same text as a new
message there; the forwarded row gets a new message identity and normal send
semantics. Deleted messages and every attachment/media type are excluded by
`ForwardFilter.canForward` (`android/app/src/main/java/com/nexachat/core/ui/ForwardFilter.java`)
and `ChatViewModel.forwardMessage`. This is not a server-side forward record
or a copy of the original message metadata.

**Follow-up traps.**
- *"Are attachments forwarded too?"* — No; forwarding is text-only.
- *"Does the recipient see the original sender or source conversation?"* —
  No; the app creates a new text message authored by the forwarding user.

---

## 5. Offline & persistence

### Q5.1 — Outbox pattern: SENDING/FAILED, retry hoạt động thế nào?

*The outbox pattern — SENDING/FAILED states and retry mechanics?*

**Model answer.** `send()` first writes an `OutboxEntity(clientMessageId =
randomUUID, state = SENDING)` — the bubble appears instantly, offline or not
(`MessageRepository.kt:354-376`). `dispatchOutbox` then tries the STOMP SEND;
if the socket is down it falls back to the REST POST *with the same
request* (line 391-419). The ack is the `MESSAGE_CREATED` echo carrying the
same `clientMessageId` — `onMessageCreated`/`acknowledge` deletes the outbox
row and upserts the real message (line 421-427, 475-488). Failures are
classified, not guessed: a *server rejection* (`/user/queue/errors` or an
HTTP error like `NOT_MEMBER`) is a real answer → `FAILED`, tap-to-retry via
`retry()`; a *transport failure* is not an answer → stays `SENDING` and the
next flush picks it up. `flushOutbox` dispatches every `SENDING` row in
`createdAt` order on connect/resync (`pendingList`, `Daos.kt:133-134`). The
row is *deleted on success, never flipped to SENT* — so `{SENDING, FAILED}`
is exhaustive and there is no ambiguous third state (`Entities.kt:84-105`).
Crucially, a `FAILED` row is not dropped on app restart — `attachmentsJson`
persists the media payload so a retry re-derives `attachmentIds`
(Entities + MIGRATION_1_2).

**Follow-up traps.**
- *"Why not mark FAILED on any network error?"* — A timeout tells you
  nothing: the send may have landed. Marking FAILED would tempt the user to
  "retry" a delivered message; keeping SENDING lets the server-side
  `ON CONFLICT` dedup make a retry safe regardless.
- *"Two flushes racing?"* — The server-side idempotency key makes a double
  dispatch a no-op; the outbox delete is keyed by `clientMessageId` and only
  fires when the ack is mine.

### Q5.2 — Room migrations v1→v7: những gì thay đổi, tại sao explicit Migration?

*Room migrations v1→v7 — what changed, and why explicit Migration objects?*

**Model answer.** `AppDatabase` is at `version = 7` with
`exportSchema = true` — Room writes `schemas/…/7.json` at build time, which is
what makes the migration chain instrumented-testable; `autoMigrations` are
still avoided because the exported history only helps if the SQL is explicit
and reviewed. Every upgrade is an explicit SQL `Migration`, versioned like
the server's Flyway files (`core/database/AppDatabase.kt` — six explicit
migrations registered in `DatabaseModule`).
`MIGRATION_1_2` is one additive ALTER —
`ALTER TABLE outbox ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT ''` —
so pending attachment sends survive a restart with their id list intact
(line 48-54; the Kotlin default `""` matches the SQL default, so pre-v2
rows read identically). `MIGRATION_2_3` is all additive too: two nullable
preview columns on `messages` (`replyToSenderName`, `replyToSnippet`) plus
the two interaction tables — `message_reactions` (PK `(messageId, emoji)`,
index on `conversationId`) and `pinned_messages` (PK
`(conversationId, messageId)`) — with index names matching Room's
`index_<table>_<columns>` convention or schema validation fails
(line 61-92). `MIGRATION_3_4` adds `conversations.peerReadSeq` — the
client-only OTHER-member read watermark that powers the DIRECT "Seen"
receipt (merge-preserved across refreshes since no REST field feeds it).
`MIGRATION_4_5` adds `conversations.avatarUrl` for group avatars.
`MIGRATION_5_6` adds `conversations.myRole` — the caller's own member role
from the inbox payload, used to gate ADMIN-only affordances like group
pin. `MIGRATION_6_7` adds message `editedAtEpochMs` and `deletedAtEpochMs`,
plus conversation `otherUserId` and `lastMessageDeleted` for presence and
tombstone inbox rendering. Additive-only is deliberate: a migrated install
keeps its existing rows.

**Follow-up traps.**
- *"Why does the reaction PK include only (messageId, emoji)?"* — The table
  stores *pre-aggregated* summaries `{count, reactedByMe}` — the REST/WS
  shape cannot be decomposed into per-user rows, so one row per (message,
  emoji) is the finest granularity the wire gives (`Entities.kt:115-126`).
- *"What if a migration were missed?"* — Room throws on version mismatch.
  `RoomMigrationTest` source covers upgrades from v1 and v6 and asserts the v7
  shape plus preserved rows (`android/app/src/androidTest/.../RoomMigrationTest.kt`).
  Check current CI for execution status; there is no instrumented **UI**
  regression suite (`docs/known-limitations.md`).

### Q5.3 — Resync và pagination khác nhau thế nào? Khi nào dùng cái nào?

*Resync vs pagination — what's the difference and when is each used?*

**Model answer.** Same endpoint, opposite directions and different cursors.
*Pagination* = `beforeSeq` — the user scrolls up, `loadOlder` passes the
oldest loaded `seq` (`ChatViewModel.kt:390-401`), and the server returns the
newest-first page strictly older than it (exclusive cursor, no overlap,
`MessageService.getHistory`). *Resync* = `afterSeq` — reconnect recovery,
`resyncForward` walks forward from the local `MAX(seq)` until contiguous
(`MessageRepository.kt:692-707`). The distinction that matters:
`beforeSeq` is *user-driven, best-effort, single-page at a time*;
`afterSeq` is *system-driven, must be gap-free*, so it loops until the
cursor stops advancing or `hasMore` is false. Both write through the same
`storeHistoryItems` path — messages upsert by id and reaction summaries
replace wholesale *only* for messages that carried the field (an absent
`reactions` field must not wipe local state, line 330-340).

**Follow-up traps.**
- *"Why not just use the inbox's last_message_seq as the resync cursor?"* —
  The cursor must be *what the client actually stored*, not the server's
  latest — a stale local max means "fetch everything I'm missing", and the
  `>`-exclusive semantic makes a no-gap walk trivially correct.
- *"Unread badge during resync?"* — `unreadCount` is recomputed from the
  server inbox row on refresh and adjusted locally by `incrementUnread` for
  incoming events — resync only writes history, it does not fake reads
  (`ConversationDao.incrementUnread`, `Daos.kt:98-99`).
- *"Does the inbox page too?"* — The server supports optional `limit`/`cursor`
  keyset paging on `GET /conversations` (`ConversationInboxController.java:29-33`,
  `InboxService.java:73-101`), but the Android client always requests the full
  list (`limit=null`); the reconcile prune runs only when the payload is
  provably complete. That is a documented, honest limitation
  (`docs/known-limitations.md` §2).

### Q5.4 — Optimistic update thất bại: "compare-and-restore" race là gì?

*The compare-and-restore race on failed optimistic updates — explain it.*

**Model answer.** The naive optimistic-update bug: write optimistically →
call fails → restore the *old snapshot* — but meanwhile a *fresher* truth
landed (a `REACTION_UPDATED` from another member, a `PIN_UPDATED`). Blind
restore overwrites that fresher state with a stale one. The fix is compare
before restoring (`MessageRepository.kt`): on failure, `toggleReaction`
restores the pre-call snapshot *only if* the current stored set still equals
the optimistic write — `if (reactionDao.forMessage(messageId) == optimistic)`
(line 586-588); if anything fresher arrived, the server truth wins and we
keep it. Same pattern for pins — `pinMessage` deletes its optimistic row
only if the stored row is still *ours* (`pinDao.find(…) == entry`,
line 662-664); `unpinMessage` restores only while the row is still absent
(line 679-681). Failures surface on `interactionEvents` as a snackbar rather
than a silent revert.

**Follow-up traps.**
- *"Why is this better than a version counter?"* — The entity equality *is*
  the version check, for free: if Room's current value differs from what we
  optimistically wrote, somebody newer won — no extra bookkeeping.
- *"What about reactions/pins while fully offline?"* — They revert and
  signal — the outbox deliberately covers message sends only; extending it
  to interactions is a named deferred item (`docs/known-limitations.md` §2).

### Q5.5 — Logout/dổi session: local state dọn thế nào?

*On logout or session death, how is local state cleaned?*

**Model answer.** Server-side truth and client storage are wiped together.
`TokenRefresher` is the single rejection funnel: a `TOKEN_INVALID`/
`TOKEN_REUSED` (4xx) from `/auth/refresh` → `tokenStore.clear()` +
`SessionEvents.emitLoggedOut()` (`core/network/TokenRefresher.kt:92-97`).
`MessageRepository.wipeOnLogout` subscribes that event and clears *every*
Room table — messages, conversations, outbox, reactions, pins — plus the
in-memory `lastMarkedRead` and `activeConversation`
(`data/MessageRepository.kt:710-723`). `RealtimeSession.observeTokens`
watches `hasValidRefresh`: a disappearing token cancels the connection loop
and closes the socket so nothing lingers
(`core/network/stomp/RealtimeSession.kt:197-213`). The principle: a dead
session's local copies of server data go with it — the next login is a cold
sync, not a merge with a stranger's cache.

**Follow-up traps.**
- *"Why not keep history for UX?"* — The data is keyed to the *account*, not
  the device; keeping it leaks one user's chats to whoever logs in next on
  the same device.
- *"Pending outbox on logout?"* — Cleared too — a dead session cannot
  legally send, and keeping the rows would send them under the next login's
  identity.

### Q5.6 — Offline send → có mạng lại: ai "đánh thức" kết nối?

*A message queued offline stays pending — who wakes the socket when the
network returns?*

**Model answer.** One ownership line: `runConnectionLoop` in
`RealtimeSession` retries `ReconnectPolicy.MAX_ATTEMPTS` (5) with
exponential backoff (1s→16s, ±20% jitter), then **parks** instead of exiting —
an unbounded loop would burn battery on a dead network. Four re-arm triggers:
**(a) user action** — `openConversation` calls `ensureConnected()`
(`data/MessageRepository.kt:253`); **(b) network regain** —
`NetworkMonitor` registers a `ConnectivityManager` callback on
`NET_CAPABILITY_INTERNET + VALIDATED` — *validated* matters: a captive
portal that "connects" without internet must not wake the loop
(`core/network/NetworkMonitor.kt:47-60`); **(c) app foreground** —
`onAppForeground` re-arms a parked loop when the user returns;
**(d) a bounded backoff timer** while foregrounded (5 s doubling to 60 s max)
so a backend that recovers with no connectivity transition is still picked up
(`RealtimeSession.kt:320-358`). `observeNetworkRegain` gates the
re-arm on `!isOpen` and a live refresh token, so a logged-out app never
spins a loop and a wifi↔cell handover with a healthy socket is a no-op
(`RealtimeSession.kt:249-255`). On `Open`, `resyncRequests` fires →
`MessageRepository.flushOutbox()` replays SENDING rows in `createdAt`
order; the server dedups by `clientMessageId`, so a retried send can never
double-post (`data/MessageRepository.kt:385-389,509-518`). Proven
on-device: airplane on → send → pending bubble → airplane off → ~15s →
SENT, outbox empty, no duplicates (report §13.4(d)).

**Follow-up traps.**
- *"Why not retry forever instead of re-arm?"* — Infinite retry on a dead
  network is wasted work; the retry *budget* is for transient blips, the
  regain/foreground/timer signals are for real recovery — different failure
  classes.
- *"Server down but WiFi up?"* — Covered while the app is foregrounded: the
  parked loop re-arms on its own bounded timer
  (`docs/known-limitations.md` §2). Backgrounded, the timer stops (battery)
  and the next foreground/network transition/user action re-arms.
- *"What if the user sends while the loop is dead?"* — `dispatchOutbox`
  falls back to REST `POST /messages` when `!isOpen` — the outbox is a
  queue, not a WS-only path (`MessageRepository.kt:406-416`).

---

## 6. i18n / state / UX

### Q6.1 — Per-app locale: API 33+ và dưới 33 khác nhau thế nào?

*Per-app locale — how do API 33+ and below differ?*

**Model answer.** Two platform mechanisms behind one `AppLocaleManager`
(`core/common/LocaleManager.kt`) — `androidx.appcompat` is deliberately
absent from the dependency graph, so `AppCompatDelegate.setApplicationLocales`
is not available. **API 33+** (`Build.VERSION_CODES.TIRAMISU`):
`android.app.LocaleManager.setApplicationLocales` — the *system* persists
the tag, applies a configuration change to every activity, and restores it
across cold starts; no app-side storage (`LocaleManager.kt:48-58,67-70`).
**API <33**: the BCP-47 tag is stored in the `nexachat_locale`
SharedPreferences file and merged into each activity's configuration by
`wrapContext`, called from `MainActivity.attachBaseContext`
(`MainActivity.kt:29-30`, `LocaleManager.kt:91-99`) —
`createConfigurationContext` with `config.setLocales`; `Locale.setDefault`
is also aligned so `java.util` formatters follow, and `recreate()` rebuilds
the running activity in the new locale. An empty tag = "follow system",
via `LocaleList.getEmptyLocaleList()`.

**Follow-up traps.**
- *"Why not just always use the <33 path?"* — On 33+ the platform mechanism
  is more correct: it handles recreates, process death and per-app isolation
  for free — the manual path exists only where the API doesn't.
- *"Persisted where on 33+?"* — In the framework — `applicationLocales`
  reads back the system's own record; duplicating it in prefs would create
  two sources of truth.

### Q6.2 — Tại sao SharedPreferences mà không DataStore cho locale?

*Why SharedPreferences and not DataStore for the locale tag?*

**Model answer.** Because `attachBaseContext` is **synchronous** — it runs
before the activity's resources exist and *cannot suspend*, while DataStore's
first read is a suspending coroutine call
(`LocaleManager.kt:40-41`). The locale must be applied *at* base-context
attach on API <33 — a suspend read would arrive too late, the activity
would inflate in the wrong locale, and you would need a second recreate.
SharedPreferences gives a blocking, already-loaded read that fits the hook.
This is the narrow, correct use of the legacy API — it stores exactly one
string, read at one well-defined moment. (Contrast: `TokenStore` *does* use
DataStore — its reads are all in coroutine context where suspension is
legal.)

**Follow-up traps.**
- *"Could you preload DataStore into a field?"* — A race by another name:
  you would have to guarantee the cache is warm before *any* activity
  attach, including the first one — which is exactly the synchronous problem
  SharedPreferences already solves.
- *"Consistency risk of two storage systems?"* — None: the pref is only
  consulted below API 33; on 33+ the platform owns the value
  (`LocaleManager.kt:48-58`).

### Q6.3 — SavedStateHandle vs rememberSaveable — khi nào dùng cái nào?

*SavedStateHandle vs rememberSaveable — when each?*

**Model answer.** They survive different things. `rememberSaveable` persists
through *config change and system-initiated process death* but lives at the
composable — the ViewModel can outlive it and it dies with the *activity's*
saved-state record. `SavedStateHandle` keeps state in the ViewModel's own
saved-state bundle — survives process death *and* stays owned by the VM,
testable without Compose. NexaChat's rule of thumb (see
`docs/rotation-audit.md`): **anything worth surviving process death lives in
the ViewModel or Room** — the chat draft is `savedStateHandle.getStateFlow(KEY_INPUT)`
(`ChatViewModel.kt:105`), the `conversationId` arrives via `SavedStateHandle`
nav args (line 60-61); rememberSaveable is reserved for pure-UI trivia
(expanded menus etc.) where loss is invisible. The deeper truth: *render*
state survives in Room — after a process death the messages are still there
because Room is the SSOT; VM/handle state is only for ephemeral
user-in-progress input.

**Follow-up traps.**
- *"Why not put the draft in Room?"* — Wrong ownership: a draft is
  screen-scoped, not data — writing it to the SSOT would pollute the
  pipeline and need lifecycle cleanup the saved-state handle gives free.
- *"Does SavedStateHandle survive rotation?"* — Trivially — the VM survives
  rotation regardless; the handle's extra mile is process death.

### Q6.4 — M3 states và 48dp — UX baseline chứng minh ở đâu?

*Material 3 states and 48dp touch targets — where is the UX baseline proven?*

**Model answer.** Two layers. **M3 componentry**: the app is Material 3
end-to-end (`androidx.compose.material3`, BOM-managed —
`libs.versions.toml:10,40`), with shared state components —
`core/ui/components/LoadingState.kt`, `EmptyState.kt`, `ErrorState.kt` — so
every screen renders loading/empty/error through one vocabulary instead of
per-screen improvisations. **Touch targets**: the interaction UI keeps the
Android ≥48dp minimum explicitly — emoji glyphs sit in `size(48.dp)`
hit areas, the ~30dp reaction chip gets a 48dp transparent hit box, the
quote/reply bar and pin banner enforce `height(48.dp)`/`heightIn(min=48.dp)`
(`feature/chat/ChatInteractions.kt:63,95,170,216,279`, `EmptyState.kt:61`).
Interactive affordances are gated by data, not vibes: reaction bar and
reply/pin only on `SENT` messages (`replyable = status == SENT`).

**Follow-up traps.**
- *"Is 48dp a hard requirement or a nicety?"* — It's the Android
  accessibility minimum touch target (`48 x 48dp`); interactive elements
  below it are an a11y defect, not a style choice — the audit treats it as
  the floor.
- *"Where does i18n fit M3?"* — User-facing strings are externalized in
  paired English and Vietnamese resource files. The base `strings.xml` has
  142 entries per locale (140 translatable); `strings_call.xml` adds 45 and
  `strings_summary.xml` adds 40, for 227 entries (225 translatable) per locale.
  Each file pair has matching resource names. The linked i18n audit contains
  the fuller review.

### Q6.5 — Bilingual song ngữ implement ở tầng nào? Có missing key không?

*Where is bilingual support implemented — are there missing keys?*

**Model answer.** Pure Android resource resolution: paired resource files
under `values/` (English default) and `values-vi/`. The base `strings.xml`
contains 142 entries per locale (140 marked translatable), `strings_call.xml`
adds 45 and `strings_summary.xml` adds 40, for 227 entries total
(225 translatable) per locale. Each English/Vietnamese file pair has matching names.
Strings are referenced via `stringResource()` in composables; switching is
the `AppLocaleManager` path of Q6.1 — the framework picks `values-vi` once the
configuration locale is `vi`. The current counts and the exact counting command
are recorded in `docs/i18n-audit.md`. Server-side content
is language-agnostic: error payloads carry machine `code`s
(`ErrorResponse`, `ApiErrorCode`), so clients map codes → localized strings
(`feature/auth/ApiErrorText.kt`, `feature/chat/ChatErrorText.kt`) instead of
displaying English server text.

**Follow-up traps.**
- *"Why map error codes client-side instead of localized server messages?"* —
  The client owns its locale; the server would need per-request locale
  negotiation for strings only it produces, and a code like `PIN_LIMIT` is a
  better contract anyway — UIs render it consistently in both languages.
- *"RTL support?"* — `android:supportsRtl="true"` in the manifest — M3
  layouts mirror automatically; the demo languages are LTR but the flag is
  the honest declaration.

### Q6.6 — Theme mode ở đâu và lưu thế nào?

*Where is theme mode selected and stored?*

**Model answer.** The app supports `SYSTEM`, `LIGHT`, and `DARK` theme modes.
`SettingsRepository` persists the choice locally in `SharedPreferences`, and
the root UI resolves the mode against the platform's system-dark setting via
the Java `ThemeMapper` (`android/app/src/main/java/com/nexachat/data/SettingsRepository.kt`,
`android/app/src/main/java/com/nexachat/core/ui/ThemeMapper.java`). Theme mode
is device-local and is not sent to the server; the server-backed account
setting in the same settings screen is `showPresence`.

**Follow-up traps.**
- *"What does SYSTEM mean?"* — Follow Android's current system theme.
- *"Does changing the theme update other devices?"* — No, each device stores
  its own theme preference.

---

## 7. Testing & deployment

### Q7.1 — Tại sao integration test chạy trên Postgres thật, không dùng H2?

*Why do integration tests run against real Postgres, not H2?*

**Model answer.** Because the correctness being tested *is* Postgres
behavior, and H2 cannot fake it. The load-bearing mechanisms are
dialect-specific: `INSERT … ON CONFLICT … DO NOTHING` (message idempotency
and reaction/pin upserts), `SELECT … FOR UPDATE` (rotation serialization,
pin ceiling), the `BIGSERIAL` seq semantics, the `WHERE lastReadSeq < :seq`
monotonic update, PostgreSQL extensions (`pgcrypto`, `pg_trgm`, `unaccent`)
and the `CHECK`
constraints in `V1__init.sql` (e.g. `(type='DIRECT') = (direct_key IS NOT NULL)`).
H2's compatibility mode emulates some syntax but not the concurrency or
constraint semantics — a green H2 suite would be green *about a different
database*. So every `*IT` boots the real Flyway-migrated Postgres and
exercises the actual code paths (`AuthFlowIT`, `MessageFlowIT`,
`GroupFlowIT`, `InteractionFlowIT`, `AttachmentFlowIT`,
`ContactConversationIT`).

**Follow-up traps.**
- *"Isn't a real DB slower/flakier?"* — Yes, deliberately accepted: failsafe
  runs `*IT` at `mvn verify` (not `mvn test`), so the fast unit loop stays
  fast and the DB-bound suite is a gate, not a tax on every edit
  (`server/pom.xml:139-156`).
- *"What does an IT prove that unit tests can't?"* — Transactional +
  constraint behavior under real interleavings: rotation races,
  ON CONFLICT dedup, member-check rows — `MessageServiceTest` (mocked) and
  `MessageFlowIT` (real) are complements, not duplicates.

### Q7.2 — Testcontainers hay compose-JDBC? Trade-off là gì?

*Testcontainers vs JDBC-against-compose — what's the trade-off?*

**Model answer.** The dependency is declared (`pom.xml:119-129`,
testcontainers 1.20.4) and was the original design — container-per-suite is
the cleaner isolation story. What shipped instead is **env-JDBC against the
compose Postgres**: `application-it.yml` + `AuthFlowIT.java:53-59` read
`IT_DB_URL/IT_DB_USER/IT_DB_PASSWORD`, defaulting to
`jdbc:postgresql://localhost:5433/nexachat` (the compose port mapping —
`docker-compose.yml:11`). The suite therefore uses a compose-managed real
Postgres locally rather than creating a fresh test container. CI points
`IT_DB_URL` at the workflow's PostgreSQL service on `:5432` (`ci.yml`).

**Follow-up traps.**
- *"What do you lose vs Testcontainers?"* — Per-run isolation and
  lifecycle automation (fresh container, no state bleed). Mitigations: IT
  cleanup deletes only test-namespace users (`+8490%` *except* the
  `+849000%` demo-seed range — `demo/seed.sql:16-18` guard comment), so the
  seed survives test runs.
- *"Could you just mock the DB?"* — That re-opens the H2 question: the
  whole point is verifying real constraint/transaction behavior.

### Q7.3 — Negative authZ test matrix gồm những gì?

*What does the negative authorization test matrix look like?*

**Model answer.** Two layers. **Frame layer** —
`StompAuthChannelInterceptorTest` covers CONNECT with
missing/garbage/expired token; SUBSCRIBE to a conversation topic as a
non-member; SUBSCRIBE to a foreign-shaped destination (deny-by-default);
SEND to a non-member conversation; only `/user/queue/{inbox,errors,presence,calls}` is allowed;
denied frames are dropped by the interceptor. **Service layer** tests the
`NOT_MEMBER` path per use case: `MessageServiceTest`, `ReactionServiceTest`,
`PinServiceTest`, `GroupServiceTest`, and `AttachmentServiceTest`. The ITs
prove authorization rules end-to-end against real rows. The security table
mapping every endpoint to its rule is
`docs/security-checklist.md` §1.

**Follow-up traps.**
- *"Any positive-only test debt?"* — The matrix is deliberately negative-
  heavy for exactly the viva reason: authZ is proved by denial, not by the
  happy path — each interceptor/service test names the 403/401 it expects.
- *"What about the client?"* — Android tests verify client behavior on
  denial (revert + snackbar, `MessageRepositoryTest`); server enforcement is
  server-side — the client is UX, not a boundary.

### Q7.4 — Dockerfile multi-stage: từng stage làm gì, tại sao?

*Walk the multi-stage Dockerfile — what does each stage do and why?*

**Model answer.** Two stages (`server/Dockerfile`). **Build**:
`maven:3.9-eclipse-temurin-17` — copies `pom.xml` alone and runs
`mvn dependency:go-offline` first, so the dependency layer is cached
independently of `src/` (a source-only change does not re-download the
internet); then `mvn package -DskipTests`. **Runtime**:
`eclipse-temurin:17-jre` (JRE only — no JDK, no Maven, no source in the
final image), installs `curl` for the `HEALTHCHECK`, creates a non-root
`nexachat` user (`USER nexachat` — the container does not run as root),
copies only the repackaged `nexachat-server-*.jar`, prepares
`/app/data` for the uploads volume, `EXPOSE 8080`, `HEALTHCHECK` curls
`/actuator/health` (which is `permitAll` + exposed by
`management.endpoints…include=health`), `ENTRYPOINT` honors `JAVA_OPTS`.

**Follow-up traps.**
- *"Why skip tests in the image build?"* — The gate is `mvn verify` locally
  and in CI; re-running the suite inside every image build would need a
  database in the build context and doubles build time for no extra signal.
- *"Why non-root?"* — Container escapes and file-permission surfaces: the
  uploads volume is `chown`ed to the `nexachat` user; a compromised process
  has no root to escalate from.

### Q7.5 — LAN demo dùng cleartext HTTP — có an toàn không?

*The LAN demo runs cleartext HTTP/WS — is that safe?*

**Model answer.** It is *scoped* to be acceptable — debug-build only, and
the release path is honest about it. `usesCleartextTraffic="false"` in the
manifest + the main `network_security_config` permits cleartext to exactly
one host — `10.0.2.2`, the emulator's alias for the dev machine
(`app/src/main/res/xml/network_security_config.xml`). The **debug** source
set then *overrides* that config with `cleartextTrafficPermitted=true` —
only inside `app-debug.apk`, so a release APK physically cannot do cleartext
(`app/src/debug/res/xml/network_security_config.xml:1-11`). That is how a
physical phone demos over LAN `http://<laptop-ip>:8080` while the shipped
posture stays TLS-only. Server-side, `application-prod.yml` documents TLS
termination at the reverse proxy (`forward-headers-strategy=framework` so
redirects/URLs render as https/wss) — the app itself speaks plain HTTP *inside*
the trusted network, and `docs/known-limitations.md` §2 states the LAN
cleartext caveat verbatim.

**Follow-up traps.**
- *"Aren't tokens sniffable on the LAN then?"* — Yes — on the demo LAN,
  within the debug build, which is exactly why the declaration is scoped:
  the risk window is a controlled demo network, and production is WSS/HTTPS.
  Say the trade-off plainly rather than claiming cleartext is fine.
- *"Why not TLS for the demo?"* — Self-signed certs on a LAN IP cost more
  setup than the demo earns; the *posture* (debug-only escape hatch,
  release blocked) is the security work.

### Q7.6 — CI gates gồm những gì? Có chặn được merge xấu không?

*What does CI enforce — can it actually block a bad merge?*

**Model answer.** The CI workflow runs on PRs and pushes to `main`/`develop`
(`.github/workflows/ci.yml`). Its jobs run `mvn -B verify` against a
PostgreSQL 16 service, Android unit tests plus lint and debug assembly, a
Markdown-link and shell-syntax check, and a committed-secret scan. The workflow
definition describes gates; only a completed run for the reviewed revision
proves their result. Maven Failsafe binds `*IT` to `verify`, so the server job
includes the database integration tests. The exact current pass/fail status
belongs to the matching CI run, not a fixed count in this Q&A.

**Follow-up traps.**
- *"Why a grep secret-scan instead of a real scanner?"* — Scoped to the
  repo's actual secret shapes (private keys, api keys in source/yml); it's
  deterministic, offline, and self-excluded (`--exclude='ci.yml'` so the
  pattern can't match itself). A heavier scanner is a fine upgrade — the
  gate is honest about its width.
- *"Does CI prove the deploy?"* — No — it proves tests + no committed
  secrets; the compose/`Dockerfile` build is exercised separately
  (`docs/packaging-checklist.md`). Don't over-claim CI as deploy evidence.

### Q7.7 — Secrets/profile production xử lý thế nào?

*How are secrets and the production profile handled?*

**Model answer.** Two-tier. **Dev** (`application.yml`): every secret has a
fallback default — `DATABASE_*` → `nexachat/nexachat`,
`JWT_SECRET` → `dev-secret-change-me-…` — so a fresh clone boots with zero
setup (`application.yml:6-8,28`); the dev secret is a *visible placeholder*,
not a hidden real one. **Prod** (`application-prod.yml`, activated by
`SPRING_PROFILES_ACTIVE=prod` in compose): `JWT_SECRET` has **no default**
— `JwtService` fails fast at boot on a missing/short secret (≥32 bytes),
so prod cannot silently boot on the dev key (`application-prod.yml:35`,
`JwtService.java:42-46`); datasource credentials come from the environment and
`ProductionDataSourceConfiguration` fails boot when they are missing or
unresolved (`application-prod.yml:29-31`,
`ProductionDataSourceConfiguration.java:21-38`).
Compose passes `JWT_SECRET`/`SPRING_DATASOURCE_*` through so the container
still boots for a fresh clone (`docker-compose.yml:33`). Client side, the
only "secret" is the TLS-less dev config (Q7.5) and the Keystore-protected
token store — no API keys are baked into the APK.

**Follow-up traps.**
- *"Isn't a committed dev secret still a secret leak?"* — A placeholder
  named `dev-secret-change-me` is not a credential; the contract is that
  prod *requires* a real env var (fail-fast) — the scan gate keeps real
  keys out of the tree.
- *"Where does JWT_SECRET rotation land?"* — New env value → restart → all
  outstanding access tokens fail signature check (15-min TTL bounds the
  window); refresh tokens are DB rows, unaffected.

### Q7.8 — Tại sao prod fail fast khi thiếu DB credentials?

*Why does the prod profile fail fast when datasource credentials are missing?*

**Model answer.** Because a late, confusing failure is worse than an early,
named one. `ProductionDataSourceConfiguration` is a `@Profile("prod")`
`BeanFactoryPostProcessor` that requires `spring.datasource.username` and
`spring.datasource.password` to be present **and** resolvable before any bean
is created (`config/ProductionDataSourceConfiguration.java:21-38`);
`application-prod.yml` declares them without defaults
(`${DATABASE_USER}` / `${DATABASE_PASSWORD}`). Without this guard the context
would boot, and the first query would fail with a generic datasource error —
or worse, a default credential might silently connect to the wrong database.
Compose supplies `SPRING_DATASOURCE_*` so a fresh clone still boots
(`docker-compose.yml`).

**Follow-up traps.**
- *"Why not just let Spring validate at first use?"* — By then the app has
  bound ports, run Flyway, and possibly served health checks; a config error
  should stop startup, not surface as a runtime 500.
- *"Does this cover JWT_SECRET too?"* — Yes, separately: `application-prod.yml`
  gives `JWT_SECRET` no default and `JwtService` fails fast on a missing/short
  secret (Q7.7).
- *"Proof?"* — `ProductionConfigurationTest` asserts the prod profile rejects
  missing/unresolved datasource properties.

---

## 8. Call signaling & AI summary

### Q8.1 — Call signaling: state machine RINGING→ACTIVE→ENDED và vì sao server-authoritative?

*How does call signaling work, and why does the server own the state?*

**Model answer.** Calls ride the authenticated STOMP connection. The caller
sends `INVITE` with a client-generated `callId`; the server reserves both users,
persists a `RINGING` `call_records` row, and pushes `CALL_INVITE` to the callee's
`/user/queue/calls`. From there the transitions are fixed: callee `ACCEPT` →
`ACTIVE`; callee `REJECT` or caller `CANCEL` → `ENDED`; a 45 s ring timeout →
`ENDED(MISSED)`; either participant's `END` in `ACTIVE` → `ENDED(COMPLETED)`;
the last STOMP session of a participant closing → `ENDED(FAILED)`. SDP and ICE
are relayed verbatim only after acceptance. The server is authoritative because
it alone owns the registry, the per-user busy reservation, and the transition
guard: a second invite to a busy callee creates a terminal `BUSY` record without
notifying the callee, and concurrent terminal frames cannot write a second
`SYSTEM` call line — the winning transition owns the history/message write. The
client never declares a state; it sends an intent and renders the event
(`docs/calls.md`, `CallService`, `CallRegistry`, `CallStateMachineTest`).

**Follow-up traps.**
- *"Why not peer-to-peer signaling?"* — The server must authorize both
  participants and arbitrate races (busy, double-accept, timeout); a P2P
  negotiation would need the same rules duplicated on each client.
- *"What ends the call if a client dies?"* — The last STOMP session closing
  transitions the participant's call to `ENDED(FAILED)`.
- *"Is media encrypted?"* — Signaling is TLS/WSS in production; the audio path
  depends on WebRTC's own DTLS-SRTP. No TURN is configured (STUN only).

### Q8.2 — Summary: tại sao extractive-first và citation chống hallucination?

*Why is the local summary engine extractive, and how do citations prevent hallucination?*

**Model answer.** The local engine is deterministic and **extractive**: it
selects existing messages and labels them (decision, action item, question,
topic), rather than generating new prose. Every emitted item carries
`messageIds` that point back to the source messages in the requested window; the
client can jump to them. Because the text is copied from real rows, the summary
cannot invent facts — there is no generative step to hallucinate. A
`CitationGuard` filters any candidate whose cited ids are missing or outside the
window, and the evaluation suite asserts citation validity. The LLM engine is
optional (operator key + user consent) and still returns the same cited
structure; any failure falls back to the local engine with a `fallbackReason`
(`docs/ai-summary.md`, `LocalExtractiveSummaryProvider`, `CitationGuardTest`).

**Follow-up traps.**
- *"So the local summary is just a filter?"* — It is a heuristic selector and
  labeler (cue detection, topic extraction, MMR diversification), not a
  rewriter; quality is measured on the gold set.
- *"Could the LLM hallucinate?"* — The response schema requires `messageIds` for
  every item, so an uncited or out-of-window claim is rejected; provider
  failures degrade to local instead of inventing content.
- *"Why extractive first?"* — It works offline, is deterministic, costs nothing,
  and is always available; the LLM is an enhancement, not a dependency.

### Q8.3 — Consent model: `aiSummaryConsent` và LLM opt-in hoạt động thế nào?

*How does the summary consent model work?*

**Model answer.** The `users.ai_summary_consent` column (Flyway V6) defaults to
`false`. The settings API exposes it as `aiSummaryConsent` alongside
`showPresence` (`GET/PATCH /api/v1/users/me/settings`). Provider selection reads
the persisted value: with consent off, or with no operator key, the service
selects the local engine and **no message text leaves the server**. With both,
the server sends only redacted usable text of the window to the configured
OpenAI-compatible endpoint; sender names become stable labels (`U1`, `U2`), and
message ids never leave the server. Disabling consent therefore both selects
local and prevents the external call (`SummaryProviderSelector`,
`SummaryConsentRepository`, `docs/ai-summary.md`).

**Follow-up traps.**
- *"Is consent per conversation?"* — No. It is an account-level opt-in; there is
  no per-conversation veto yet (see `docs/known-limitations.md`).
- *"What if the LLM fails?"* — The service falls back to local with a
  `fallbackReason` (`NO_CONSENT`, `LLM_DISABLED`, `LLM_TIMEOUT`,
  `LLM_BAD_OUTPUT`, `LLM_HTTP_ERROR`); the endpoint never returns `503`.
- *"Can consent be changed at runtime?"* — Yes, through the settings endpoint;
  the next summary request uses the new value.

### Q8.4 — Summary privacy: những gì thực sự rời khỏi server?

*What actually leaves the server for a summary, and what is redacted?*

**Model answer.** Two gates must both be open before any message text leaves
the server: the operator configured an LLM key **and** enabled the LLM path,
**and** the requesting user's persisted `aiSummaryConsent` is `true`. The
adapter re-checks that persisted value immediately before the outbound call,
so consent revoked after provider selection still blocks it. The server sends
only usable text from the window (`TEXT`, not deleted, not system, each message
truncated to 1,000 characters), passed through `Redactor`: emails, URL paths and
queries (the bare domain is kept), phone numbers of 7–15 digits, long digit
strings and token-like strings become `[REDACTED_*]` markers; sender names
become stable labels `U1..Un`; message ids never leave the server — the prompt
carries short indexes and the server maps them back, dropping unknown indexes.
Every item must cite at least one valid message id inside the input window or
the provider output is rejected (`SummaryProviderSelector.validateCitations`).
Any failure falls back to the local engine with a `fallbackReason`, so the
endpoint never returns `503`. Without the key or consent, no text leaves the
server at all (`Redactor`, `PromptBuilder`, `SummaryProviderSelector`,
`docs/ai-summary.md`).

**Follow-up traps.**
- *"Is consent per conversation?"* — No, it is account-level; there is no
  per-conversation veto yet (`docs/known-limitations.md`).
- *"Can the LLM see message ids?"* — No. Ids stay server-side; the model cites
  short indexes that are resolved back to ids after validation.
- *"What if the LLM invents an uncited claim?"* — Items without a valid
  in-window citation are rejected; on validation failure the whole result
  degrades to local instead of returning unverifiable text.
- *"What is stored?"* — Prompts and raw model output are not stored; only the
  validated summary is cached for 24 hours, and edits/deletes change the cache
  key via the window's content hash.

### Q8.5 — STOMP heartbeats: tại sao một caller đang đổ chuông cần chúng?

*Why does a ringing caller need STOMP heartbeats?*

**Model answer.** Heartbeats are negotiated in `CONNECT`
(`heart-beat:10000,10000`). Spring's SimpleBroker only answers the client's
heartbeat header when it has a scheduler; without `.setTaskScheduler(...)` it
advertises `0,0`. The Android client has a 30 s liveness guard — if no frame
arrives it treats the socket as dead and reconnects. A ringing caller is idle:
no message frames flow while it waits for the callee, so without server
heartbeats the client would tear down a perfectly healthy socket mid-ring.
`WebSocketConfig` sets `.setHeartbeatValue(new long[] {10_000, 10_000})` and a
single-thread `stomp-heartbeat-` scheduler, so the server emits a beat every
10 s and at least two beats fit inside the 30 s guard even with jitter. The
call E2E asserts the caller received server heartbeats while ringing; the same
beats keep presence/typing subscriptions alive on idle screens
(`config/WebSocketConfig.java:57-76`, `tools/e2e/calls/call_e2e.py`).

**Follow-up traps.**
- *"Why 10 s and not exactly 30 s?"* — Two beats must fit inside the guard, so
  the interval has to be comfortably below 30 s; 10 s leaves margin for
  scheduling delay.
- *"Is this SockJS?"* — No. It is raw STOMP 1.2 over WebSocket; heartbeats are
  newline frames negotiated by the `heart-beat` header.
- *"What happens without it?"* — An idle ringing caller trips the guard,
  reconnects, and the ring can drop even though both peers are still online.

### Q8.6 — Ring-timer re-arm: race giữa timer và registry clock

*What is the ring-timer re-arm race and how is it fixed?*

**Model answer.** When a call enters `RINGING`, `CallTimeoutScheduler`
schedules a timeout using the registry's clock and `ringTimeoutMillis`
(default 45000). The timer thread and the registry clock are independent, so a
timer can fire a fraction of a second before the registry considers the ring
deadline reached; `CallService.timeout` then declines to end the call because
it is still `RINGING`. Without a re-arm the call would ring forever. The fix
re-checks the registry after firing: if the session is still `RINGING`, the
timer re-arms itself with a small delay (`RETRY_DELAY_MILLIS = 25 ms`) computed
from the registry clock, so the next firing lands after the real deadline.
Terminal events cancel the scheduled future (`CallTimeoutScheduler.java:53-74`,
`CallTimeoutSchedulerTest`).

**Follow-up traps.**
- *"Why not schedule exactly 45 s ahead?"* — The registry compares against its
  own `Clock`, which can be a hair ahead of the timer; the re-arm makes the
  outcome converge regardless of that skew.
- *"Could it loop forever?"* — No. Each re-arm recomputes the remaining delay
  from a clock that advances, and a terminal transition cancels the future.
- *"How is it verified?"* — The live E2E runs the production 45 s timeout and
  asserts the timeout is not early; unit tests cover scheduling, cancellation
  and the re-arm path.

### Q8.7 — Tại sao call core/media và summary data layer là Java?

*Why are the new call and summary layers Java?*

**Model answer.** Because of the project language rule, not a technical
constraint (Q1.7): Jetpack Compose is Kotlin-only, so `@Composable` screens
stay Kotlin; all **new** non-UI logic is Java. The call feature is the largest
new Java surface — `call/core` (`CallManager`, `CallRepository`,
`CallFrameCodec`, `CallLogFormatter`, call state machine), `call/media`
(`WebRtcMediaEngine`, `CallAudioRoutePolicy`), `call/service` (foreground
service, notification action router) and `call/ui` (`CallActivity` /
`IncomingCallActivity` as Java activities with XML layouts, plus
`CallUiModelMapper`, `CallLogResourceResolver`). The summary data layer
(`summary/*.java`, `AiConsentRepository`, `SummaryRepository`,
`CitationResolver`) is Java too. The Compose screens that present them
(`SummaryBottomSheet.kt`) stay Kotlin and call the Java helpers; existing
Kotlin files are not converted (`android/app/src/main/java/com/nexachat/call/**`,
`android/app/src/main/java/com/nexachat/summary/**`).

**Follow-up traps.**
- *"Why not write the call engine in Kotlin?"* — The rule is a project/course
  decision (Java-focused review), not a language limitation; the only hard
  boundary is Compose's Kotlin compiler plugins.
- *"Does the mix break Hilt or tests?"* — No; both languages compile to JVM
  bytecode, Hilt's generated code is language-agnostic, and the Java helpers
  have plain JUnit tests (`call/**/*Test.java`, `summary/*Test.java`).
- *"Is the call screen really not Compose?"* — Correct for the call UI:
  `CallActivity`/`IncomingCallActivity` are Java activities with XML layouts,
  launched from the Kotlin chat screen via `CallLauncher`.

---

## Quick index — mục lục nhanh

| # | Section | Q&A |
|---|---|---|
| 1 | Kiến trúc & patterns | Q1.1 – Q1.7 |
| 2 | Auth & security | Q2.1 – Q2.10 |
| 3 | Realtime & messaging | Q3.1 – Q3.14 |
| 4 | Creative (reply/reaction/pin/forward) | Q4.1 – Q4.8 |
| 5 | Offline & persistence | Q5.1 – Q5.6 |
| 6 | i18n / state / UX | Q6.1 – Q6.6 |
| 7 | Testing & deployment | Q7.1 – Q7.8 |
| 8 | Call signaling & AI summary | Q8.1 – Q8.7 |

**67 Q&A total.** Claims cite current source where practical; where the honest answer is
"we deferred it", the answer says so and points at `docs/known-limitations.md`.
