# NexaChat Security Architecture

Scope: the Spring Boot backend (`server/`) and Android client (`android/`) — REST,
STOMP WebSocket, attachments, rate limiting, and error handling. Every claim cites
the implementing source file.

## 1. Credential security

- Passwords use BCrypt cost 12 (`new BCryptPasswordEncoder(12)`,
  SecurityConfig.java). Register and change-password reject a secret that bcrypt
  cannot hash in full — more than 72 UTF-8 bytes, or containing NUL — and login
  of such a secret is `INVALID_CREDENTIALS` without comparing it to the account
  hash; it still pays one dummy bcrypt compare for timing
  (PasswordPolicy.java, AuthService.java).
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

- Access tokens are HS256 JWTs (`sub` = userId, `iat`, `exp` = iat + 900 s,
  `iss` = `nexachat`, `aud` = `nexachat-api`), 15-minute lifetime
  (`access-ttl-seconds: 900`, application.yml; JwtService.java). HS256 is
  deliberate: one service signs and verifies, so RS256 would add key
  distribution without trust boundaries. The secret must be >= 32 bytes or boot
  fails fast; application-prod.yml has no default, and `ProductionJwtSecretGuard`
  refuses to boot the `prod` profile with the repository's development secret or
  a secret with fewer than eight distinct characters. A `type=access` claim is
  verified on parse, and a token without the expected issuer or audience is
  rejected, blocking replay of other material.
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
- Access-token revocation (Flyway V11): `users.tokens_valid_after` stores the revocation
  cutoff timestamp stamped on logout, logout-all, or password change (`AuthService.java`).
  Both `JwtAuthFilter` and `StompAuthChannelInterceptor` check `iat < cutoff` and reject stale
  access tokens with `401 TOKEN_INVALID`. The lookup is served from an in-memory bounded LRU
  cache (`AccessTokenValidityCache`, `nexachat.security.validity-cache-ttl-seconds`, default 60 s)
  with a database projection fallback (`findAuthValidityById`).

## 3. Transport authorization

- REST is stateless: CSRF disabled, no HTTP session. Only register/login/refresh,
  actuator health, `/error`, and the WebSocket upgrade `/ws` are permitAll;
  every other request requires a Bearer token (SecurityConfig.java).
- Actuator endpoints share the API port `8080` by default; only `/actuator/health` (including its
  liveness/readiness groups) is public. Metrics and Prometheus endpoints require opt-in via
  `MANAGEMENT_EXPOSURE`. Moving the management endpoints to an isolated loopback port is opt-in via
  `MANAGEMENT_SERVER_PORT` and `MANAGEMENT_SERVER_ADDRESS`.
- CORS uses an explicit allow-list (`nexachat.security.cors-allowed-origins`, env `NEXACHAT_CORS_ALLOWED_ORIGINS`).
  The default is empty (admitting no cross-origin browser caller, safe for native mobile clients).
  Wildcard `*` is rejected at boot, and credentials are never enabled.
- The WebSocket handshake (`/ws`) enforces the same CORS allow-list.
- Operator telemetry (`GET /api/v1/rag/stats`) is restricted to administrator phone numbers
  in `nexachat.security.admin-phones` (`NEXACHAT_ADMIN_PHONES`) via `RagStatsAuthorizationManager`;
  non-admin authenticated callers receive `403 FORBIDDEN`.
- JwtAuthFilter parses `Authorization: Bearer <jwt>` once per request and sets a
  typed `JwtPrincipal`. A missing header leaves the request unauthenticated and
  the entry point answers 401 (`UNAUTHORIZED`/`TOKEN_EXPIRED`/`TOKEN_INVALID`)
  in the standard envelope (JwtAuthFilter.java, AuthSecurityErrorHandler.java).
- STOMP does not trust the HTTP handshake for identity: `/ws` is permitAll and
  the JWT travels in the CONNECT (or STOMP 1.2 `STOMP`) frame's native
  `Authorization` header — never a URL query param, which would leak into access
  logs (StompAuthChannelInterceptor.onConnect, SecurityConfig.java). A rejected
  handshake closes the socket, a socket that never authenticates is closed after
  10 s, and one account may hold at most 32 authenticated sockets per node
  (StompSessionRegistry.java).
- SUBSCRIBE authZ: `/topic/conversations.{uuid}` is allowed only when
  `conversation_members` contains `(conversationId, userId)`;
  only `/user/queue/inbox`, `/user/queue/errors`, `/user/queue/presence`, and
  `/user/queue/calls` are allowed for the authenticated user's own destination;
  direct `/user/{id}/queue/*`, unknown queues, and every other destination are denied
  by default
  (StompAuthChannelInterceptor.onSubscribe).
- SEND authZ: `/app/conversations/{uuid}/{messages|typing|read|delivered}` and
  `/app/chat.{delivery|read}.ack` re-check membership before any `@MessageMapping` runs.
  The chat-level aliases carry `conversationId` in JSON, so the interceptor parses
  that UUID and checks `(conversationId, userId)`; a missing or malformed ID
  continues to payload conversion/validation and cannot advance a watermark.
  Conversation handlers retain their service-level membership checks, and unknown
  destinations are dropped (StompAuthChannelInterceptor.onSend); denial returns `null`.

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
  .store). Text formats carry no signature: they are accepted only when declared
  as `text/plain`, `text/markdown` or `text/csv`, decode as strict UTF-8, and do
  not start with HTML, SVG, script or XML markup; those bytes would be served
  inline from this origin, so they are `415 MIME_NOT_ALLOWED`
  (LocalStorageService.sniffTextContent).
- ISO-BMFF track sniffing: Android `MediaRecorder` voice notes produce generic `.m4a`
  files with compatible brands (`isom`, `mp42`, `mp41`). A brand-only check would
  misclassify them as `video/mp4`. `MagicBytes.java` walks the ISO-BMFF box tree and
  inspects track handlers (`trak/mdia/hdlr`): only `soun` audio tracks yield `audio/mp4`,
  whereas any `vide` track yields `video/mp4`. If `moov` is missing or truncated, the check
  falls back to the brand. On `/api/v1/files/**`, `FileController` inspects a bounded 64 KiB
  prefix and trailing 64 KiB window (`readSuffix`) for `moov`-at-end containers without
  buffering entire files into memory.
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
- Group avatars: `PATCH /conversations/{id}` accepts only a relative
  `/api/v1/files/` path. An absolute URL whose path is already under that prefix
  is rewritten to the relative form; another host, scheme, user-info, `..` or
  `\` is `400 VALIDATION_FAILED` (AvatarUrlPolicy.java). Stored avatars therefore
  cannot send members' devices to another origin.
- Member-checked download: `GET /api/v1/attachments/{id}/content` needs Bearer;
  owner OR member of the bound conversation may read (unbound → owner-only);
  unknown id → 404 `ATTACHMENT_NOT_FOUND`, otherwise 403 `NOT_MEMBER`
  (AttachmentService.assertReadable). The raw `/api/v1/files/**` path enforces
  the same rule for keys mapped to attachment rows, so neither door bypasses
  the other (FileController.serve → AttachmentService.assertStorageKeyReadable);
  avatar keys stay public to any authenticated user. Content-Type is the
  upload-time sniff; the inline filename is re-sanitized, and both serve paths
  send `X-Content-Type-Options: nosniff` (`AttachmentController.content`,
  `FileController.serve`) so a browser cannot re-interpret a uploaded payload.

## 5. Rate limits

- Pre-auth credential endpoints are IP-keyed (login 10/5 min, register 10/5 min,
  refresh 30/5 min, Google nonce 30/5 min, Google sign-in 20/5 min;
  RateLimitInterceptor.java) **and** phone-keyed (login and register 20/5 min per
  normalized E.164 phone; AuthService.java), preventing distributed IP rotation
  from brute-forcing an account. Without
  `NEXACHAT_TRUSTED_PROXY_CIDRS`, rate limits key off the socket peer and
  `X-Forwarded-For` is treated as client-spoofable; when the immediate peer
  matches a configured CIDR, `ClientIpResolver` uses the forwarded client IP for
  invite and other IP-keyed buckets (`ClientIpCaptureThenForwardedHeaderFilter`).
- Body bounds: JSON request bodies are bounded by `nexachat.security.max-json-body-bytes`
  (`NEXACHAT_MAX_JSON_BODY_BYTES`, default 1 MiB); oversized payloads are rejected
  with `413 PAYLOAD_TOO_LARGE` before Jackson deserialization (RequestBodySizeLimitFilter.java).
  Multipart file uploads keep their separate 25 MB ceiling.
- Post-auth endpoints are keyed by the JWT subject: user search 30/min and
  contact-add 30/min, bucket key `user:<id>` (UserSearchRateLimitInterceptor
  .java, ContactAddRateLimitInterceptor.java). The IP fallback exists only for a
  stray unauthenticated call that the security layer rejects anyway.
- V12–V20 feature writes add per-user buckets: labels 30/min (shared),
  polls 10/min create / 120/min vote / 30/min manage, invite resolve 60/min and
  join 20/min (also keyed per client IP to blunt token guessing), reminders
  30/min create, and message edit/delete 30/min plus search 60/min. Keys and
  defaults are in [`docs/api-contract.md`](api-contract.md#operational-notes).
- STOMP SEND is per-user as well, with independent buckets: 30 messages /
  10 s (`stomp:messages:<userId>`), 60 typing frames / 10 s
  (`stomp:typing:<userId>`), and 120 receipt acknowledgment frames / 10 s
  (`stomp:acks:<userId>`), configured by `nexachat.rate-limit.stomp.*`
  (application.yml). A denial drops the frame and reports `RATE_LIMITED` to
  the originating session as a `/user/queue/errors` frame carrying the
  correlated `clientMessageId` (typing and acks carry none)
  (StompAuthChannelInterceptor.consumeSendAllowance). Inbound STOMP frames are capped
  at 64 KiB (`StompSessionRegistry.MAX_MESSAGE_BYTES` applied as the WebSocket
  transport message-size limit); a larger frame is closed by the transport.
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
  `FILE_TOO_LARGE` 413, `PAYLOAD_TOO_LARGE` 413, `RATE_LIMITED` 429.
- REST exception logs contain the exception class only; `GlobalExceptionHandler`
  does not log exception messages or stack traces, and its response contains a
  sanitized error envelope. `WsMessageController` maps payload conversion or
  validation failures to `VALIDATION_FAILED`, unexpected handler failures to
  `INTERNAL_ERROR`, and logs no exception message, stack trace, or payload.
- Validation errors are structured and stable: field violations become an
  insertion-ordered `details` map (GlobalExceptionHandler.handleValidation).

## 7. Google sign-in (OpenID Connect)

- Off by default (`nexachat.google.signin.enabled`). While off, every endpoint
  answers `404 NOT_FOUND` (GoogleSignInService.requireEnabled), the decoder bean
  rejects without any network access, and the Android build without a web client
  ID never renders the entry points. Enabling the feature without
  `nexachat.google.signin.web-client-id` or at least one trusted Android client
  ID fails application startup
  (GoogleSignInConfiguration.decoderFor) — there is no insecure fallback.
- The ID token is verified with Spring Security's `NimbusJwtDecoder` over Google's
  JWKS (`https://www.googleapis.com/oauth2/v3/certs`, cached by Nimbus and
  refreshed when Google rotates to an unknown key ID). RS256 is the only accepted
  algorithm; the validator chain requires `exp`/`nbf` within a 60-second skew,
  `iss` equal to `accounts.google.com` or `https://accounts.google.com`, `aud`
  exactly equal to the configured web client ID (a token with any additional
  audience is rejected), a `sub`, an `iat` no more than 60 s in the future, and —
  when Google sets it — an `azp` that is the web client or one of the configured
  Android client IDs (`nexachat.google.signin.android-client-ids`)
  (GoogleIdTokenValidators.java). There is no custom crypto.
- The identity key is the immutable `sub`; email is stored for display only and
  never used to match or link an account (UserIdentity.java). A Google account
  can belong to at most one user and a user to at most one Google account, both
  enforced by UNIQUE constraints (V28); a concurrent link loses on the constraint
  and receives the same generic `409` (GoogleSignInService.link).
- Replay protection: `POST /auth/google/nonce` issues a 256-bit nonce, persists
  only its SHA-256 hex with a five-minute expiry, and the token's `nonce` claim
  must match. Each nonce is bound to its purpose (`signin` for the public route,
  `link` for the authenticated route). A link nonce is also bound to the account
  and unique access-token `jti` that requested it, using only a SHA-256 hash;
  a nonce cannot be spent by another account or another access-token instance.
  New access tokens receive a random `jti`; older tokens without one may use
  normal APIs but must refresh before starting a Google link flow. The service
  consumes the nonce with one conditional `UPDATE ... WHERE purpose = ? AND
  consumed_at IS NULL AND expires_at > now()`
  in a `REQUIRES_NEW` transaction, so concurrent requests and a later rollback
  of the sign-in transaction cannot both use (or release) the nonce
  (GoogleNonceService.java). All failures — malformed, wrong audience, wrong
  issuer, expired, bad signature, wrong nonce — collapse to
  `401 GOOGLE_SIGN_IN_FAILED`.
- Linking and unlinking require the account password (bcrypt comparison) and are
  rate-limited per user (10/5 min); an unlinked Google account gets the generic
  `404 GOOGLE_ACCOUNT_NOT_LINKED` and the message never reveals whether the
  Google account or the NexaChat account exists. Automatic account creation from
  Google and email-based linking are intentionally absent (account-takeover
  risk). Deleting a user cascades the identity row in the schema; the
  account-deletion flow must delete identity rows because it tombstones users
  instead of deleting them.

## Known limits & future work

- The Android client is pure Java 21 (`android/`, Groovy `build.gradle`, XML
  views, ViewBinding, Hilt `annotationProcessor`). In production, cleartext HTTP/WS
  is disabled globally (`android/app/src/main/res/xml/network_security_config.xml`
  sets `cleartextTrafficPermitted="false"` with zero exemptions); cleartext access
  to `10.0.2.2` (emulator) and LAN dev IPs is strictly isolated to the `debug`
  and `localSmoke` variants (`android/app/src/debug/res/xml/network_security_config.xml`,
  `android/app/src/localSmoke/res/xml/network_security_config.xml`). Session tokens
  are stored by `TokenStore` on RxDataStore as Base64 of `IV || AES-GCM`
  ciphertext; the key is `AES/GCM/NoPadding` in AndroidKeyStore
  (`core/storage/AesGcmTokenCipher.java`). Production needs TLS termination
  and WSS.
- `enableSimpleBroker("/topic","/queue")` is in-process fan-out
  (WebSocketConfig.java): correct for one instance; horizontal scaling needs a
  broker relay, and RateLimitService's in-memory buckets need Redis/JDBC.
- No end-to-end encryption: message and file content is plaintext at rest and
  protected only in transit (TLS in production).
- Access-token revocation is immediate on a single node and propagates across multiple
  instances within the validity cache TTL (`nexachat.security.validity-cache-ttl-seconds`,
  default 60 s). Refresh-token families rotate on use with reuse detection.
- The prod profile fails fast when datasource or storage coordinates are
  missing or unresolved (`ProductionDataSourceConfiguration`); compose supplies
  `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` and `STORAGE_ROOT` so a
  configured checkout still boots.
- Kicked/removed members: after a member removal or self-leave commits,
  `GroupService` revokes existing `/topic/conversations.{uuid}` subscriptions.
  Every outbound conversation-topic frame also rechecks the recipient's current
  membership, closing the queued-SUBSCRIBE race. `GroupFlowIT` covers admin
  removal, self-leave, and an authorized SUBSCRIBE held until after removal.
- CORS and WebSocket handshake origins default to an empty allow-list (admitting no
  cross-origin browser callers), configurable via `NEXACHAT_CORS_ALLOWED_ORIGINS`.
