# NexaChat Security Checklist — Phase 12

Server-scope security pass for the Spring Boot backend (`server/`). Every
claim cites `file:line`; test names point at the proof. Complements
`docs/security.md` (the narrative) with the audit-table form.

## 1. Per-endpoint authorization table

REST transport: stateless Bearer — only `POST /api/v1/auth/{register,login,refresh}`,
`/actuator/health`, `/error`, `/ws` are `permitAll`; everything else requires
a token (`SecurityConfig.java:60-71`). Missing/garbage Bearer → 401 envelope
via `JwtAuthFilter` + `AuthSecurityErrorHandler.java:35-63`.

| Endpoint | AuthN | AuthZ rule | Negative-test evidence |
| --- | --- | --- | --- |
| POST /api/v1/auth/register | permitAll | IP rate-limit 10/5min; phone UNIQUE race → 409 (`AuthService.java:75-84`) | `AuthFlowIT.duplicatePhoneIs409`, `registerValidationErrors`; `AuthServiceTest.register_*` |
| POST /api/v1/auth/login | permitAll | anti-enumeration: identical INVALID_CREDENTIALS + dummy bcrypt compare (`AuthService.java:50-64, 90-107`) | `AuthFlowIT.wrongLoginIs401Not404`; `AuthServiceTest.login_*` |
| POST /api/v1/auth/refresh | permitAll | single-use rotation, FOR UPDATE lock; reuse/expiry → family revoke before 401 (`TokenService.java:68-90`, `AuthService.java:115-127`) | `AuthFlowIT.registerLoginRefreshReuse`; `TokenServiceTest.rotate_*` |
| POST /api/v1/auth/logout | Bearer | ownership-checked revoke — foreign token untouched (`TokenService.java:94-98`) | `TokenServiceTest.revokeIfOwned_*`; `AuthServiceTest.logout_delegatesToOwnershipCheckedRevoke` |
| POST /api/v1/auth/logout-all | Bearer | revokes caller's whole family (`AuthService.java:138-140`) | `AuthServiceTest.logoutAll_revokesWholeFamily` |
| PATCH /api/v1/auth/password | Bearer | old-password verify, then revoke-all(-except-kept) (`AuthService.java:143-161`) | `AuthServiceTest.changePassword_*` (incl. unknown-user 404) |
| GET /api/v1/users/me | Bearer | self only (principal id) | `AuthFlowIT.protectedEndpointRequiresBearer` |
| PATCH /api/v1/users/me | Bearer | self only | `UserServiceTest.updateDisplayName_*` |
| GET /api/v1/users/search | Bearer | caller excluded; 30/min per user (`UserSearchRateLimitInterceptor.java:45-46`) | `ContactConversationIT.userSearch_*`; `UserServiceTest.search_*` |
| GET /api/v1/contacts | Bearer | owner-scoped query (`ContactService.list`) | `ContactServiceTest.list_delegatesToProjectionQuery` |
| POST /api/v1/contacts | Bearer | self-add 400; dup 409; 30/min per user (`ContactAddRateLimitInterceptor.java:29-31`) | `ContactConversationIT.contactsLifecycle`; `ContactServiceTest.add_*` |
| PATCH /api/v1/contacts/{userId} | Bearer | composite ContactId(owner,callee) — cannot name another owner | `ContactServiceTest.updateAlias_*` |
| DELETE /api/v1/contacts/{userId} | Bearer | owner-scoped; idempotent 204 | `ContactServiceTest.delete_*` |
| POST /api/v1/conversations/direct | Bearer | target must exist; self 400; direct_key dedup | `ConversationServiceTest.openDirect_*`; `ContactConversationIT.directConversation*` |
| GET /api/v1/conversations (inbox) | Bearer | member-scoped query — no way to name another user; optional `limit`/`cursor` keyset paging (`InboxService.java:40,62,73-101`) | `MessageFlowIT.nonMemberCannotReadWriteOrWatermark` (invisible in non-member inbox); `GroupFlowIT` kicked-user inbox drop; `InboxServiceTest.listInbox_scopesEveryQueryToCallerUserId` |
| POST /api/v1/conversations/group | Bearer | creator → ADMIN, rest → MEMBER | `GroupFlowIT.groupCreateAndMemberValidation` |
| POST /api/v1/conversations/{id}/members | Bearer | member → ADMIN (`GroupService.addMembers`) | `GroupFlowIT` MEMBER → 403 NOT_ADMIN; `GroupServiceTest.addMembers_*` |
| DELETE /api/v1/conversations/{id}/members/{userId} | Bearer | ADMIN or self; sole-admin guard (`GroupService.java:217-249`) | `GroupFlowIT`; `GroupServiceTest.removeMember_*` |
| POST /api/v1/conversations/{id}/leave | Bearer | member; sole-admin-with-members blocked | `GroupFlowIT`; `GroupServiceTest.leave_*` |
| PATCH /api/v1/conversations/{id} | Bearer | member check BEFORE admin check (`GroupService.java:260-262`) | `GroupFlowIT` non-member → 403 NOT_MEMBER, member → 403 NOT_ADMIN; `GroupServiceTest.updateGroup_notMember_throwsNotMember` |
| POST /api/v1/conversations/{id}/avatar | Bearer ADMIN | member → ADMIN (`GroupService.setGroupAvatar`); `MagicBytes` sniff + `StorageService` under `avatars/` (same as user avatar) | `GroupServiceTest.setGroupAvatar_*` (member/non-member/non-admin); live: REST upload → inbox `avatarUrl`, authed GET 200, anon 401 |
| GET /api/v1/conversations/{id}/messages | Bearer | member (`MessageService.requireMember`) | `MessageFlowIT` → 403 NOT_MEMBER; `MessageServiceTest.getHistory_notMember` |
| POST /api/v1/conversations/{id}/messages | Bearer | member; attachment claims owner-checked | `MessageFlowIT` non-member send → 403 + 0 rows; `MessageServiceTest.send_*` |
| POST /api/v1/conversations/{id}/read | Bearer | member | `MessageFlowIT` → 403; `MessageServiceTest.markRead_notMember` |
| GET /api/v1/conversations/{id}/pins | Bearer | member (`PinService.listPins`) | `InteractionFlowIT` non-member → 403; `PinServiceTest.listPins_*` |
| POST /api/v1/conversations/{id}/pins | Bearer | member; GROUP → ADMIN; ≤3 ceiling | `InteractionFlowIT` non-admin → 403 NOT_ADMIN, 4th → 409 PIN_LIMIT; `PinServiceTest.pin_*` |
| DELETE /api/v1/conversations/{id}/pins/{mid} | Bearer | member; GROUP → ADMIN; foreign message → 400 | `PinServiceTest.unpin_*` |
| POST /api/v1/messages/{id}/reactions | Bearer | member of the message's conversation (`ReactionService`) | `InteractionFlowIT` non-member → 403; `ReactionServiceTest.toggle_*` |
| POST /api/v1/attachments | Bearer | owner-owned row; ≤25MB; magic-byte allow-list | `AttachmentFlowIT` exe→415, >25MB→413, no-auth→401; `AttachmentServiceTest.upload_*`; `LocalStorageServiceTest` |
| GET /api/v1/attachments/{id}/content | Bearer | owner, or member of bound conversation; unbound → owner-only (`AttachmentService.java:161-172`) | `AttachmentFlowIT` member 200 / non-member 403 / unknown 404; `AttachmentServiceTest.loadContent_*` |
| GET /api/v1/files/** | Bearer | key rejects `..`/`\`/blank (`FileController.java:62-83`); attachment keys → same owner/member rule (`AttachmentService.java:150-154`); avatar keys auth-only | `AttachmentFlowIT` files-path non-member 403; `FileControllerTest` (traversal/backslash/prefix/unauth); `LocalStorageServiceTest.read_*` |
| POST /api/v1/users/me/avatar | Bearer | self; fixed `avatars` subdir (`AvatarController.java:37-56`) | covered by storage-layer tests |
| STOMP CONNECT /ws | token in STOMP header (never URL) | `JwtService.parseAccessToken` → `StompUserPrincipal` (`StompAuthChannelInterceptor.java:137-156`) | `StompAuthChannelInterceptorTest.connect_*`; `MessageFlowIT` bad CONNECT dropped |
| STOMP SUBSCRIBE /topic/conversations.{uuid} | session principal | member check (`StompAuthChannelInterceptor.java:160-184`) | `StompAuthChannelInterceptorTest.subscribe_*` |
| STOMP SUBSCRIBE /user/queue/* | session principal | own-queue only by resolver | `StompAuthChannelInterceptorTest.subscribe_userQueue_*` |
| STOMP SEND /app/conversations/{id}/{messages\|typing\|read} | session principal | member check + per-user buckets (messages 30/10 s, typing 60/10 s), deny-by-default (`StompAuthChannelInterceptor.java:188-244`) | `StompAuthChannelInterceptorTest.send_*`; `MessageFlowIT` non-member SEND → 0 rows |

## 2. Password & token handling facts

- **Passwords:** BCrypt cost 12 (`SecurityConfig.java:49-52`); registration
  caps length at 8–72 chars because bcrypt truncates beyond 72 bytes
  (`RegisterRequest.java:14`). Hashes never leave `users.password_hash`.
- **Login timing:** unknown phone still pays one bcrypt compare against a
  startup-computed dummy hash (`AuthService.java:50-64, 100-101`); unknown
  phone, wrong password, and unparseable phone all return identical
  `INVALID_CREDENTIALS` 401.
- **Access tokens:** HS256, `sub`=userId, `type=access` verified on parse,
  `exp` = iat + 900 s (`JwtService.java:20-22, 51-59, 74-76`). Secret must be
  ≥32 bytes or boot fails (`JwtService.java:33-46`).
- **Refresh tokens:** 48 SecureRandom bytes → 64-char base64url, only
  `SHA-256(token)` hex persisted (`TokenService.java:39-40, 113-121`); 14-day
  TTL; rotation under `SELECT … FOR UPDATE` with `replaced_by` chain; reuse →
  `TOKEN_REUSED` + family revocation committed before the 401.
- **Secrets in config:** dev defaults exist only for the local profile —
  `application.yml:28` (JWT dev secret), `application.yml:8` (DB password
  `nexachat`, matching docker-compose). Prod profile requires env
  `JWT_SECRET` with no fallback, and `ProductionDataSourceConfiguration`
  fails boot when `spring.datasource.username`/`password` are missing or
  unresolved (`application-prod.yml`, `ProductionDataSourceConfiguration.java:16-34`).

## 3. Secret scan — PASS

- `grep -rniE "(password|secret|api[_-]?key|token|private[_-]?key)\s*[:=]\s*['\"][^'\"]{6,}"`
  over `src/main/java/` → **no hardcoded credentials**.
- `grep -rniE "BEGIN .*PRIVATE KEY|AKIA[0-9A-Z]{16}|ghp_[a-zA-Z0-9]{36}|sk-[a-zA-Z0-9]{20,}"`
  over `src/` → **no keys/cloud/GitHub/OpenAI tokens**.
- Config secrets are env-var references (`application.yml:8, 28`,
  `application-prod.yml:41, 60`); the only literal secret is the test-scoped
  `application-it.yml` jwt secret, clearly marked `it-only…not-for-prod`.

## 4. Error envelope — no stack traces — PASS

- One envelope `{"error":{"code","message","details?"}}` everywhere
  (`ErrorResponse`, `GlobalExceptionHandler.java:38-131`,
  `AuthSecurityErrorHandler.java:35-63`).
- The catch-all handler logs the exception class only (no exception message or
  stack trace) and returns only `INTERNAL_ERROR`
  (`GlobalExceptionHandler.java:125-131`); `IllegalArgumentException` also logs
  only the exception class (`GlobalExceptionHandler.java:118-123`).
- `MaxUploadSizeExceededException` → 413 `FILE_TOO_LARGE`
  (`GlobalExceptionHandler.java:99-103`); validation errors carry an
  insertion-ordered `details` map (`GlobalExceptionHandler.java:43-61`).

## 5. Rate-limit coverage

| Path | Bucket | Limit | Implementation |
| --- | --- | --- | --- |
| POST /api/v1/auth/login | per-IP | 10 / 5 min | `RateLimitInterceptor.java:38` |
| POST /api/v1/auth/register | per-IP | 10 / 5 min | `RateLimitInterceptor.java:39` |
| POST /api/v1/auth/refresh | per-IP | 30 / 5 min | `RateLimitInterceptor.java:40` |
| GET /api/v1/users/search | per-user | 30 / 1 min | `UserSearchRateLimitInterceptor.java:45-47` |
| POST /api/v1/contacts | per-user | 30 / 1 min | `ContactAddRateLimitInterceptor.java:29-31` |
| STOMP SEND /app/conversations/{id}/messages | per-user | 30 / 10 s | `StompAuthChannelInterceptor.java:205-208` |
| STOMP SEND /app/conversations/{id}/typing | per-user | 60 / 10 s | `StompAuthChannelInterceptor.java:209-212` |

Registration wiring: `WebConfig.java:33-40`. `X-Forwarded-For` deliberately
untrusted (`RateLimitInterceptor.java:27-29`); post-auth buckets key on the
JWT subject. Buckets are in-memory Bucket4j in a bounded LRU (10,000 keys,
15 min idle TTL) — single-node scope (`RateLimitService.java:24-27`); horizontal
scale needs Redis/JDBC (known limit, `docs/security.md`).

## 6. Log scan — no secrets logged — PASS

Grep of every `log.*` call in `src/main/java/` for
`password|token|secret|credential|authorization`: only metadata is ever
logged — user/conversation/message ids, event names, and exception *types*:

- `AuthService.java:85,124,161` — event name and `reuse`/`expiry` keyword (no
  token value or phone number).
- `StompAuthChannelInterceptor.java:140,147,151,174,182,202,222,227,242` —
  rejection reasons and rate-limited action name (no token or exception message).
- `GlobalExceptionHandler.java:120,128` — exception simple class name only (no
  message or stack trace).
- `WsMessageController.java:111-123` — fixed warning for payload validation or
  exception simple class name for unexpected failures; the error frame to the
  client carries only the code.
- No `System.out` / print of request bodies, headers, or credentials anywhere.

## 7. Negative-test coverage matrix (phase-12 audit)

✓ = pre-existing, + = added this pass.

| Service | Negative coverage |
| --- | --- |
| AuthService | ✓ dup/invalid/race register; wrong/unknown/unparseable login (timing); reused/unknown refresh; wrong-old password · + unknown-user changePassword, logout/logoutAll delegation |
| TokenService | ✓ rotate unknown/revoked/expired; revokeIfOwned foreign-token; bulk revoke |
| ContactService | ✓ unknown/dup/self/invalid add; blank/missing alias; idempotent delete |
| ConversationService | ✓ openDirect unknown/self/both/neither/race |
| GroupService | ✓ create validations; addMembers non-admin/not-member/unknown/empty/direct/missing; removeMember non-admin/target-not-member/sole-admin; leave not-member/sole-admin · + updateGroup not-member |
| MessageService | ✓ send not-member + contract matrix (reply cross-conv, attachment ownership/binding/mime, >10, TEXT-carrying-ids); markRead/getHistory not-member |
| InboxService | + caller-scoping anchor (queries keyed by caller userId); IT-level non-member invisibility ✓ |
| AttachmentService | ✓ upload magic/oversize/missing/traversal/dimension; loadContent unbound-non-owner/bound-non-member/unknown · + storage-key unbound non-owner/owner |
| LocalStorageService | ✓ store traversal/exe/unknown/empty/oversize · + read() traversal/missing/happy |
| ReactionService | ✓ message-missing/not-member/blank/long emoji |
| PinService | ✓ pin non-admin/not-member/limit/foreign/missing/idempotent; unpin non-admin · + unpin not-member/foreign-message, listPins not-member/missing-conv |
| FileController | + NEW FileControllerTest: `..`/backslash/wrong-prefix keys rejected before storage; null principal → 401; member-check before read |
| StompAuthChannelInterceptor | ✓ CONNECT missing/malformed/bad-token; SUBSCRIBE non-member/unauth/foreign/malformed; SEND non-member/unauth/unknown · + message/typing rate-limit and correlated `RATE_LIMITED` error frame |

## Known limits (carried from docs/security.md)

- Android client is pure Java 21 (XML views, ViewBinding, `build.gradle`).
  Cleartext HTTP is limited to emulator host `10.0.2.2`
  (`network_security_config.xml`; `usesCleartextTraffic="false"`). Tokens sit
  in `TokenStore` (RxDataStore) encrypted with `AES/GCM/NoPadding` via
  AndroidKeyStore (`AesGcmTokenCipher.java`). Production needs TLS and WSS.
- Kicked/removed members: after removal or self-leave commits, `GroupService`
  revokes existing `/topic/conversations.{uuid}` subscriptions, and
  `StompConversationMembershipOutboundInterceptor` rechecks current membership
  on every outbound conversation-topic frame.
- Dev-grade CORS `*` and STOMP `setAllowedOriginPatterns("*")` — narrow per
  environment before public deploy.
- No E2E encryption; TLS terminates at the production edge.
