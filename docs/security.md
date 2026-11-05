# NexaChat Security Story

Scope: the Spring Boot backend (`server/`) — REST, STOMP WebSocket, attachments,
rate limiting, error handling. Claims cite source files for viva defence.

## 1. Credential security

- Passwords use BCrypt cost 12 (`new BCryptPasswordEncoder(12)`,
  SecurityConfig.java); registration caps them at 8–72 chars because bcrypt
  truncates beyond 72 bytes (RegisterRequest.java).
- Phones are normalized to E.164 with Google libphonenumber before any lookup or
  storage (PhoneNormalizer.java, default region VN). Register maps a bad number
  to `VALIDATION_FAILED`, while login deliberately maps the same failure to
  `INVALID_CREDENTIALS`, so login never reveals why (AuthService.java).
- Anti-enumeration: an unknown phone and a wrong password both return the
  identical `INVALID_CREDENTIALS` envelope (AuthService.invalidCredentials). An
  unknown account still pays one full bcrypt comparison against a dummy hash
  computed once at startup (AuthService.java), so response timing cannot reveal
  whether the phone exists.
- Registration races are closed by the database: `saveAndFlush` forces the UNIQUE
  constraint immediately and the resulting `DataIntegrityViolationException` is
  mapped back to the same `PHONE_EXISTS` contract (AuthService.java).

## 2. Token model

- Access tokens are HS256 JWTs (`sub` = userId, `iat`, `exp` = iat + 900 s),
  15-minute lifetime (`access-ttl-seconds: 900`, application.yml;
  JwtService.java). HS256 is deliberate: one service signs and verifies, so
  RS256 would add key distribution without trust boundaries. The secret must be
  >= 32 bytes or boot fails fast; application-prod.yml drops the dev default. A
  `type=access` claim is verified on parse, blocking replay of other material.
- Refresh tokens are opaque: 48 SecureRandom bytes → 64 base64url characters,
  generated per session (TokenService.generateOpaqueToken). Only
  `SHA-256(token)` hex is stored (`token_hash CHAR(64) UNIQUE`,
  RefreshToken.java / V1__init.sql); the raw value is shown once and never
  persisted, so a leaked database row is not a usable token.
- Rotation: 14-day TTL (`refresh-ttl-days: 14`), single use. Rotation reads the
  row under `SELECT … FOR UPDATE`
  (RefreshTokenRepository.findByTokenHashForUpdate, PESSIMISTIC_WRITE) so two
  concurrent refreshes cannot both mint successors; the old row is revoked and
  linked to its replacement via `replaced_by`, leaving an auditable chain
  RT1 → RT2 → … (TokenService.rotate).
- Reuse detection: a revoked token yields `TOKEN_REUSED`; unknown/expired yields
  `TOKEN_INVALID`. AuthService.refresh then revokes the user's whole family in
  a separate transaction before the 401 (rotate's transaction rolls back;
  TokenService.revokeAllForUser + RefreshTokenRepository). Logout is idempotent
  and ownership-checked; password change revokes all other sessions.

## 3. Transport authorization

- REST is stateless: CSRF disabled, no HTTP session. Only register/login/refresh,
  actuator health, `/error`, and the WebSocket upgrade `/ws` are permitAll;
  every other request requires a Bearer token (SecurityConfig.java).
- JwtAuthFilter parses `Authorization: Bearer <jwt>` once per request and sets a
  typed `JwtPrincipal`. A missing header leaves the request unauthenticated and
  the entry point answers 401 (`UNAUTHORIZED`/`TOKEN_EXPIRED`/`TOKEN_INVALID`)
  in the standard envelope (JwtAuthFilter.java, AuthSecurityErrorHandler.java).
- STOMP does not trust the HTTP handshake for identity: `/ws` is permitAll and
  the JWT travels in the CONNECT frame's native `Authorization` header — never a
  URL query param, which would leak into access logs
  (StompAuthChannelInterceptor.onConnect, SecurityConfig.java).
- SUBSCRIBE authZ: `/topic/conversations.{uuid}` is allowed only when
  `conversation_members` contains `(conversationId, userId)`;
  only `/user/queue/inbox`, `/user/queue/errors`, `/user/queue/presence`, and
  `/user/queue/calls` are allowed for the authenticated user's own destination;
  direct `/user/{id}/queue/*`, unknown queues, and every other destination are denied
  by default
  (StompAuthChannelInterceptor.onSubscribe).
- SEND authZ: `/app/conversations/{uuid}/{messages|typing|read}` re-checks
  membership before any `@MessageMapping` runs — belt-and-braces under
  MessageService.requireMember — and unknown destinations are dropped
  (StompAuthChannelInterceptor.onSend); denial returns `null`.

## 4. Upload hardening

- Endpoint and size: `POST /api/v1/attachments` needs a Bearer token and a
  `multipart/form-data` part `file` (optional `width`/`height`/`duration`); 201
  returns `{id, url, originalName, mimeType, sizeBytes, …}`. The 25 MB ceiling
  is enforced at every layer — multipart config (application.yml),
  `AttachmentService` (declared size and actual byte length), `LocalStorageService`
  before any write, and `AvatarController` — and maps to 413 `FILE_TOO_LARGE`
  (AttachmentController.java, GlobalExceptionHandler.java).
- Type: declared `Content-Type` and client filename are never trusted. Storage
  re-sniffs the leading bytes against fixed signatures and an allow-list
  (JPEG/PNG/WEBP/GIF/MP4/M4A/MP3/OGG/PDF/ZIP/DOC plus OOXML prefixes); unknown
  bytes yield 415 `MIME_NOT_ALLOWED` (MagicBytes.java, LocalStorageService
  .store). Fail-closed: no recognizable signature (including plain text, though
  `text/plain` is listed in ALLOWED_MIMES) means rejection, never guessing.
- Path safety: the key is server-minted `<subdir>/<UUID>.<sniffed-ext>`; the
  subdir rejects `..`, `/`, `\` and is charset-restricted
  (LocalStorageService.safeSubdir); resolved paths are normalized and confined
  to the root on store and read. The client filename is sanitized to display
  metadata (no directories, control/quoting chars, 255 cap —
  AttachmentService.sanitizeOriginalName).
- Lifecycle (upload-first, bind-at-send, V2__attachments.sql): an upload inserts
  an UNBOUND row owned by the caller (`message_id NULL`). At send time,
  MessageService validates the static contract (TEXT carries none;
  IMAGE/VIDEO/AUDIO/FILE require at least one, max 10) and claims each id in the
  same transaction only if it exists, belongs to the sender, is still unbound,
  and its sniffed MIME family matches the declared type
  (MessageService.validateAttachmentContract / bindAttachments).
- Member-checked download: `GET /api/v1/attachments/{id}/content` needs Bearer;
  owner OR member of the bound conversation may read (unbound → owner-only);
  unknown id → 404 `ATTACHMENT_NOT_FOUND`, otherwise 403 `NOT_MEMBER`
  (AttachmentService.assertReadable). The raw `/api/v1/files/**` path enforces
  the same rule for keys mapped to attachment rows, so neither door bypasses
  the other (FileController.serve → AttachmentService.assertStorageKeyReadable);
  avatar keys stay public to any authenticated user. Content-Type is the
  upload-time sniff; the inline filename is re-sanitized, and both serve paths
  send `X-Content-Type-Options: nosniff` (AttachmentController.java:74,
  FileController.java:58) so a browser cannot re-interpret a uploaded payload.

## 5. Rate limits

- Pre-auth credential endpoints are IP-keyed (no user identity yet): login
  10/5 min, register 10/5 min, refresh 30/5 min (RateLimitInterceptor.java);
  `X-Forwarded-For` is ignored as client-spoofable without a trusted proxy.
- Post-auth endpoints are keyed by the JWT subject: user search 30/min and
  contact-add 30/min, bucket key `user:<id>` (UserSearchRateLimitInterceptor
  .java, ContactAddRateLimitInterceptor.java). The IP fallback exists only for a
  stray unauthenticated call that the security layer rejects anyway.
- STOMP SEND is per-user as well, with two independent buckets: 30 messages /
  10 s (`stomp:messages:<userId>`) and 60 typing frames / 10 s
  (`stomp:typing:<userId>`), configured by `nexachat.rate-limit.stomp.*`
  (application.yml). A denial drops the frame and reports `RATE_LIMITED` to
  the originating session as a `/user/queue/errors` frame carrying the
  correlated `clientMessageId` (typing carries none)
  (StompAuthChannelInterceptor.java:166-224).
- Buckets are Bucket4j greedy-refill token buckets in a bounded in-memory LRU
  map (RateLimitService.java): the budget trickles back continuously instead of
  bursting at a window boundary; at most 10,000 keys are retained and an idle
  key is evicted after 15 minutes (swept every 64 operations), so a burst of
  unique identities cannot grow the map without bound. Denials return 429
  `RATE_LIMITED` with `retryAfterSeconds` through the standard envelope.

## 6. Error hygiene

- One envelope everywhere: `{"error":{"code","message","details?"}}`
  (ErrorResponse.java), produced by GlobalExceptionHandler for application and
  validation errors and by AuthSecurityErrorHandler for 401/403 security
  failures — clients never see Spring's default HTML/error body.
- Codes and HTTP statuses come from a single enum (ApiErrorCode.java) and are a
  stable public contract: e.g. `INVALID_CREDENTIALS` 401, `NOT_MEMBER` 403,
  `PHONE_EXISTS` 409, `ATTACHMENT_NOT_FOUND` 404, `MIME_NOT_ALLOWED` 415,
  `FILE_TOO_LARGE` 413, `RATE_LIMITED` 429.
- REST exception logs contain the exception class only; `GlobalExceptionHandler`
  does not log exception messages or stack traces, and its response contains a
  sanitized error envelope. `WsMessageController` maps payload conversion or
  validation failures to `VALIDATION_FAILED`, unexpected handler failures to
  `INTERNAL_ERROR`, and logs no exception message, stack trace, or payload.
- Validation errors are structured and stable: field violations become an
  insertion-ordered `details` map (GlobalExceptionHandler.handleValidation).

## Known limits & future work

- Cleartext HTTP is allowed by the Android client only for the emulator host
  `10.0.2.2` (android/app/src/main/res/xml/network_security_config.xml;
  AndroidManifest.xml sets `usesCleartextTraffic="false"`). Production needs TLS
  termination and WSS.
- `enableSimpleBroker("/topic","/queue")` is in-process fan-out
  (WebSocketConfig.java): correct for one instance; horizontal scaling needs a
  broker relay, and RateLimitService's in-memory buckets need Redis/JDBC.
- No end-to-end encryption: message and file content is plaintext at rest and
  protected only in transit (TLS in production).
- Logout-all / password change revoke refresh tokens only; there is no
  access-token denylist, so an already-issued access JWT stays valid until its
  15-minute expiry (`AuthService.java:138-159`).
- The prod profile fails fast when datasource credentials are missing or
  unresolved (`ProductionDataSourceConfiguration.java:21-38`); compose supplies
  `SPRING_DATASOURCE_*` so a fresh clone still boots.
- Kicked/removed members: after a member removal or self-leave commits,
  `GroupService` revokes existing `/topic/conversations.{uuid}` subscriptions.
  Every outbound conversation-topic frame also rechecks the recipient's current
  membership, closing the queued-SUBSCRIBE race. `GroupFlowIT` covers admin
  removal, self-leave, and an authorized SUBSCRIBE held until after removal.
- Dev-grade permissive origins — CORS `*` and STOMP
  `setAllowedOriginPatterns("*")` (SecurityConfig.java, WebSocketConfig.java) —
  must be narrowed per environment before any public deployment.
