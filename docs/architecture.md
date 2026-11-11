# NexaChat Architecture

> Current implementation view: Android app (Java 21 LTS, XML Views + Fragments +
> ViewBinding, ViewModel + LiveData, RxJava 3, offline-first Room v13) → REST
> `/api/v1` + STOMP over WebSocket → Spring Boot 3.5.16 modular monolith → PostgreSQL 16
> (Flyway V1–V20 + V22 + V24–V28; V21/V23 reserved) + file volume. Conversation RAG is available through the
> server API (`POST /api/v1/conversations/{id}/ask`) and Android Ask screen
> ([docs/rag.md](rag.md)).
> Call signaling shares STOMP (`server/call`,
> `app/call/core/StompCallSignalingClient.java`), paired with Android call screens
> (`CallActivity`, `IncomingCallActivity`) and WebRTC audio (`WebRtcMediaEngine`);
> see [docs/calls.md](calls.md).

## 1. Container diagram (§7.1 — sơ đồ tổng)

Overall system containers and their connections:

```mermaid
flowchart LR
    subgraph Device["Android (Java 21 LTS, XML Views + ViewBinding)"]
        UI["Fragments + XML Views<br/>(Material 3)"]
        VM["ViewModel + LiveData<br/>(RxJava 3 streams)"]
        REPO["Repositories<br/>(offline-first)"]
        subgraph DS["Data Sources"]
            REST["Retrofit/OkHttp<br/>+ AuthInterceptor/Authenticator"]
            STOMP["StompClient<br/>(OkHttp WebSocket + STOMP frames)"]
            ROUTER["EphemeralStateRouter<br/>(STOMP default, opt-in Firebase)"]
            ROOM[("Room DB v13 (RxJava 3)<br/>cache · caller-owned labels · owner-scoped outbox · offline_outbox · users · call_logs")]
            STORE["RxDataStore + TokenStore<br/>(Android Keystore AES)"]
            SETTINGS["Local theme + locale settings"]
        end
        UI --> VM --> REPO
        REPO --> REST & STOMP & ROUTER & ROOM & STORE
        UI --> SETTINGS
    end

    subgraph Server["Backend — Modular Monolith (Java 21 LTS + Spring Boot 3.5.16)"]
        direction TB
        SEC["Spring Security<br/>JWT filter · Rate limit"]
        subgraph Mod["Modules"]
            AUTH["auth"]
            GOOGLE["google auth"]
            RECOVERY["recovery"]
            USER["user · contact"]
            CONV["conversation"]
            MSG["message"]
            ATT["attachment"]
            PRES["presence"]
            FIREBASE["firebase bridge"]
            CALL["call signaling"]
            SUM["summary"]
            NOTIF["notification"]
            RAG["rag"]
            REMINDER["reminder"]
            POLL["poll"]
            LABEL["label"]
            INVITE["invite"]
            ARCHIVE["archive"]
            COMMON["common: errors, logging,<br/>config, E.164 util"]
        end
        WS["WebSocket/STOMP layer<br/>@MessageMapping +<br/>ChannelInterceptor authZ"]
        BROKER["SimpleBroker<br/>(in-process)"]
        PRESENCE["PresenceSessionRegistry<br/>(in-memory, per node)"]
    end

    PG[("PostgreSQL 16<br/>Flyway V1–V20 + V22 + V24–V28 migrations<br/>seq · watermarks · idempotency · calls · rag · reactions · reminders · polls · labels · invites · mentions · pin/seen · token_cutoff · retention · refresh replay · tombstoning · otps · google_signin")]
    VOL[("Local file volume<br/>(StorageService boundary)")]
    FCM[["FCM (HTTP v1 OAuth2, GoogleHttpFcmGateway)"]]

    REST -- "HTTPS REST /api/v1" --> SEC
    STOMP -- "WSS · STOMP frames" --> WS
    SEC --> Mod
    WS --> SEC
    WS --> MSG
    WS --> CALL
    WS --> PRESENCE
    PRES --> PRESENCE --> BROKER
    MSG --> BROKER --> STOMP
    Mod --> PG
    ATT --> VOL
    MSG -. "khi WS offline (FCM data push)" .-> FCM
```

## 2. Backend module diagram

The monolith separates feature code by package and deploys as one backend
unit. Individual modules use the controller/service/repository layers they
need; the diagram is a package overview, not a claim that every package has
all three layers.

```mermaid
flowchart TB
    subgraph Mod["Modules"]
        AUTH["auth"]
        GOOGLE["google auth"]
        RECOVERY["recovery"]
        USER["user · contact"]
        CONV["conversation"]
        MSG["message"]
        ATT["attachment"]
        PRES["presence"]
        FIREBASE["firebase bridge"]
        CALL["call signaling"]
        SUM["summary"]
        NOTIF["notification"]
        RAG["rag"]
        REMINDER["reminder"]
        POLL["poll"]
        LABEL["label"]
        INVITE["invite"]
        ARCHIVE["archive"]
        COMMON["common: errors, logging,<br/>config, E.164 util"]
    end
```

## 3. Send-message sequence

Persist-before-broadcast with `clientMessageId` idempotency and watermark read
receipts:

```mermaid
sequenceDiagram
    participant A as Máy A (Fragment UI)
    participant S as Spring Backend
    participant DB as PostgreSQL
    participant B as Máy B

    A->>A: optimistic insert Room (SENDING, clientMessageId)
    A->>S: STOMP SEND /app/conversations/42/messages<br/>{clientMessageId, content}
    S->>S: authN (JWT) + authZ (member of 42) + validate
    S->>DB: TX: INSERT messages (seq BIGSERIAL)<br/>ON CONFLICT (sender,clientMessageId) → return existing<br/>UPDATE conversations.last_message_*
    DB-->>S: message{seq, createdAt}
    S-->>A+B: STOMP MESSAGE → /topic/conversations.42<br/>event MESSAGE_CREATED
    A->>A: match clientMessageId → SENT (1 tick)
    B->>B: render + unread++ (watermark chưa đọc)
    B->>S: STOMP SEND /app/conversations/42/delivered {upToSeq}
    S-->>A: STOMP MESSAGE_DELIVERED {userId, upToSeq}<br/>(A renders 2 gray ticks)
    Note over B: khi mở chat → POST /conversations/42/read {upToSeq}<br/>→ A nhận MESSAGE_READ cập nhật 2 vạch xanh ("Đã xem")
```

## 4. Edit, delete, sync, offline outbox, and media

- **Edit:** an authenticated conversation member may edit their own `TEXT`
  message. The server trims and validates content, updates `editedAt`, commits,
  then broadcasts `MESSAGE_UPDATED`. Repeating the same normalized content is
  a no-op. The REST search endpoint searches live `TEXT` rows in one
  conversation using `public.f_unaccent` + `ILIKE` matching, accelerated by
  a composite GIN trigram index `idx_msg_conv_content_trgm` on
  `(conversation_id, f_unaccent(content))` (Flyway V8).
- **Delete:** the author soft-deletes their message. The row and sequence stay
  in history as a tombstone (`content: null`); reactions and pins are removed.
  Attachment metadata and stored bytes remain by default. With the opt-in
  retention job enabled, unbound uploads can be removed after 24 hours and
  attachments of soft-deleted messages after 30 days, provided their storage
  keys are not referenced elsewhere. The deleted message's attachments cannot
  be downloaded through the message binding.
- **Delta Sync:** client `SyncEngine` aggregates all conversation watermark sequences
  from Room v13, requests `POST /api/v1/sync`, and atomically applies new messages,
  edits, tombstones, and reaction updates in a single Room transaction, backed by
  server covering index `idx_messages_delta_sync` on `(conversation_id, seq)` and the
  append-only `reaction_events` audit table (Flyway V10) with additive reaction cursors.
- **Offline Outbox & WorkManager:** pending optimistic messages (`outbox`) and
  the `offline_outbox` action table (RFC 4122 UUIDv5 idempotency keys) are drained by
  `OutboxSyncWorker` via WorkManager with `NetworkType.CONNECTED` constraints and atomic
  lease locking. Message rows are scoped to their account and retained through ordinary
  logout/login; explicit logout-all clears them. Message retries reuse `clientMessageId`.
  Reaction and pin toggles write the optimistic state and enqueue a durable
  action in one Room transaction (`MessageRepository.java`); the dispatcher replays the stored
  idempotency key against idempotent server endpoints (`PUT`/`DELETE /messages/{id}/reactions/{emoji}`
  and pin endpoints with `Idempotency-Key` header, Flyway V10 `request_idempotency`), retries
  transient failures with exponential backoff, and rolls back on a permanent 4xx.
- **Media Pipeline & Streaming:** client-side image compression applies a 1920px
  long-edge cap, EXIF orientation correction, GPS metadata scrubbing, and BlurHash placeholder
  generation (`attachments.blur_hash`, Flyway V8). Media downloads stream via HTTP Range
  (`Range: bytes=a-b`, 206 Partial Content / 416), cached through an authenticated
  origin-validated Glide loader with a 2 GiB bounded disk cache. Voice playback uses Media3
  with lazy 48-bar waveform extraction cached per attachment. Server-side upload sniffing
  (`MagicBytes.java`) inspects ISO-BMFF track handlers (`trak/mdia/hdlr`), classifying Android
  `MediaRecorder` voice notes under generic brands (`isom`/`mp42`) as `audio/mp4` (not `video/mp4`),
  and `/api/v1/files/**` serves with a bounded 64 KiB prefix and tail read window for `moov`-at-end files.
- **Access-Token Revocation (Flyway V11):** `users.tokens_valid_after` stores an access-token
  revocation cutoff stamped on logout, logout-all, or password change (`AuthService.java`).
  `JwtAuthFilter` and `StompAuthChannelInterceptor` check incoming token `iat < cutoff` and
  reject revoked sessions with `401 TOKEN_INVALID`. Lookups are cached in memory for up to 60 s
  via `AccessTokenValidityCache` (`nexachat.security.validity-cache-ttl-seconds`).
- **Observability & Management Port:** Spring Boot Actuator endpoints share the API port (`8080`)
  by default so health checks work out-of-the-box. Operators can opt in to loopback isolation
  by setting `MANAGEMENT_SERVER_PORT` (e.g. 8081) and `MANAGEMENT_SERVER_ADDRESS=127.0.0.1`.
- **Conversation RAG ("Ask this chat"):** server-side RAG module (`server/rag/**`, Flyway V9
  `message_embeddings`, `rag_ask_cache`, `rag_ask_request_limits`) exposes
  `POST /api/v1/conversations/{id}/ask`. It executes hybrid BM25 lexical ranking and vector
  cosine ranking with reciprocal-rank fusion (`k = 60`), MMR diversification, strict citation
  validation against retrieved context, a 3,000-character context budget, and deterministic
  256-d local lexical embedding fallback. Aggregate telemetry (`GET /api/v1/rag/stats`) is
  restricted to authorized admin phone numbers (`NEXACHAT_ADMIN_PHONES`).
  The Android Ask screen is available in `android/app/src/main/java/com/nexachat/feature/ask/`.
- **AI Conversation Summary:** summary service (`server/summary/**`, Flyway V6) uses an
  extractive local MMR engine or an optional external OpenAI-compatible LLM (tested with
  Gemini `gemini-3.1-flash-lite` via Gemini's OpenAI-compatible base URL) gated by account-level
  `aiSummaryConsent` and server complexity thresholds.
- **Presence:** each authenticated STOMP session contributes to an
  in-memory per-user count. After commit, presence goes only to DIRECT peers
  when the subject allows presence. Hiding one's presence is one-way; it does
  not hide the peers from that user. Last-disconnect offline notification is
  coalesced for five seconds.

## 5. V12–V28 feature modules & realtime extensions

- **Per-member conversation preferences (V12, V16):** `conversation_members`
  carries `muted` / `muted_until` / `archived` and `pinned` / `pinned_at`.
  Mute, archive and pin are caller-scoped: the inbox, delta sync and push
  policy read the caller's own row, and no endpoint exposes another member's
  preference. `users.show_seen` (V16) is the reciprocal read-receipt switch,
  enforced in both directions on the STOMP outbound channel.
- **Per-conversation AI opt-out (V13):** `conversation_ai_opt_outs` excludes an
  opted-out author's messages from external AI input (summary LLM, RAG
  embeddings/answers and cached-answer replay). The account-level
  `aiSummaryConsent` and the per-conversation flag are combined; the provider
  rechecks both immediately before an outbound request.
- **Shared reminders (V14):** `conversation_reminders` is a durable board with
  repeat rules and an `anchor_at` so monthly repeats clamp to month end. The
  scheduler claims due rows with `FOR UPDATE SKIP LOCKED` plus a lease, pushes a
  data-only FCM message to every current member, then advances or completes the
  row under a row lock. Delivery is at-least-once.
- **Group polls (V15):** `polls` / `poll_options` / `poll_votes` with a
  `poll_version_seq` sequence. Votes replace the caller's selection atomically
  under a poll-row lock; the change feed
  (`GET /conversations/{id}/polls/changes`) is a compacted current-state feed
  ordered by `version`.
- **Group mentions and `@all` (V17):** `message_mentions` stores UTF-16 ranges
  into the stored content; validation rejects out-of-bounds, overlapping or
  surrogate-splitting ranges, non-members, duplicates and non-TEXT/non-GROUP
  messages. `mentionAll` is a separate group-admin flag.
- **Private labels (V18):** `conversation_labels` and
  `conversation_label_assignments` are caller-private; inbox entries carry the
  caller's label IDs and the inbox can be filtered by `labelId`.
- **Delta-sync indexes (V19):** partial indexes on
  `(conversation_id, edited_at)` and `(conversation_id, deleted_at)` keep the
  edited/deleted scans bounded; the common insert path does not touch them.
- **Group invite links (V20):** `group_invite_links` stores SHA-256 token
  hashes (raw tokens returned once), `group_join_requests` holds the approval
  queue, and `group_invite_bans` blocks removed users from rejoining via the
  same link. Joins and approvals serialize on the conversation row to enforce
  the member cap.
- **Recall window and muted push policy:** author recall is bounded by
  `nexachat.message.recall-window` (default 24 h, inclusive) with
  `409 RECALL_WINDOW_EXPIRED`; group admins bypass it. `NEW_MESSAGE` pushes are
  skipped for muted members, except when the message mentions them or has
  `mentionAll: true`, when a `SYSTEM` event is about that member, and for
  `CALL_INVITE` (a call must ring).
- **Keyed refresh replay (V25):** `users.encrypted_refresh_response` stores
  AES-GCM encrypted responses under rotating keys to handle network timeouts on token
  refresh without treating retried requests as token reuse.
- **Account deletion and phone reuse (V26):** soft-delete / tombstoning
  via `users.deleted_at = now()`, setting `phone = NULL` to free the E.164 phone
  for re-registration. Invalidates active sessions via `users.tokens_valid_after`,
  auto-leaves all groups (promoting a successor admin if needed), scrubs contacts,
  push tokens, private labels, join requests, Google identities, email OTPs, and
  AI caches, and purges avatar files from disk. Historical messages and attachments
  remain in direct and group chats with the author displayed as neutral `Deleted account`.
  Self-service deletion is available in-app (`DELETE /api/v1/users/me`) and on the web
  (`site/delete-account.html`) via recovery-email OTP.
- **Account recovery & email OTP (V27):** `email_otps` stores HMAC-SHA256 digests
  of 6-digit codes (10-minute expiry, constant-time compare, 5-attempt limit, 60 s cooldown)
  for email verification (`POST /api/v1/users/me/email/request`), password reset
  (`POST /api/v1/auth/recovery/reset-password/request`), and web account deletion. Pluggable
  `MailSender` supports SMTP (Gmail, local Mailpit for dev) and Resend.
- **Google sign-in (V28):** link-first OpenID Connect identity integration
  (`user_identities`, `google_signin_nonces`). Validates Google ID tokens via
  public JWKS (`https://www.googleapis.com/oauth2/v3/certs`) using Spring Security's
  `NimbusJwtDecoder` without requiring a client secret. Single-use nonces prevent replay.
  Linking requires re-authenticating with the account's password; automatic account creation
  and email-based linking are omitted to prevent account takeover.
- **Ephemeral state router (STOMP default, opt-in Firebase):** Android `EphemeralStateRouter`
  manages presence and typing subscriptions. While STOMP is always active and the default,
  an optional Firebase RTDB bridge and projection writer (`server/firebase/**`,
  `docs/firebase-realtime.md`) can be enabled via configuration flags (default off).

Theme mode (`SYSTEM`, `LIGHT`, `DARK`) and locale are local Android settings;
they are applied by the app and are not persisted as server account settings.

*Why monolith, not microservices:* modules are separated by package with clear
boundaries, deployed as one unit — clean enough to discuss architecture,
simple enough for the team to operate; microservices at this scale only add
network/distributed-transaction complexity.
