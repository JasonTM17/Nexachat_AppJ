# NexaChat REST API Contract

> Endpoint catalog for the current REST contract. Routes below are derived from
> controllers under `server/src/main/java/com/nexachat/`.
> The WebSocket / STOMP contract is documented in [`docs/websocket-protocol.md`](websocket-protocol.md).
> Conversation RAG is detailed in [`docs/rag.md`](rag.md) (server API and Android Ask screen available).

## Domain index

| Domain / Resource | Base path | Key capabilities |
|---|---|---|
| [Auth & Users](#auth--apiv1auth) | `/api/v1/auth`, `/api/v1/users` | Register, login, token refresh, password, profile, settings, device token |
| [Contacts](#contacts--apiv1contacts) | `/api/v1/contacts` | Contact directory, search by phone, contact alias names |
| [Conversations & Messages](#conversations--apiv1conversations) | `/api/v1/conversations` | 1-1 chat, message sending, editing, soft-deletion, trigram search, read marks, per-member mute/archive/pin, seen privacy, per-conversation AI opt-out |
| [Conversation archive](#conversation-archive--apiv1conversationsidarchive) | `/api/v1/conversations/{id}/archive` | Member-scoped storage browser: media, files and links, filtered and keyset-paged |
| [Groups](#groups--apiv1conversations-type-group) | `/api/v1/conversations/group` | Group creation, member addition/removal, admin roles, title/avatar updates |
| [Group Invite Links](#group-invite-links--apiv1conversationsidinvite-link-and-apiv1invite-links) | `/api/v1/conversations/{id}/invite-link`, `/api/v1/invite-links` | High-entropy hashed invite link tokens, admin approval toggle, member preview & join, approval queue |
| [Reminders](#reminders--apiv1conversationsidreminders) | `/api/v1/conversations/{id}/reminders` | Shared "Nhắc hẹn" board, repeat rules, durable FCM fan-out |
| [Group polls](#group-polls--apiv1conversationsidpolls) | `/api/v1/conversations/{id}/polls` | Poll creation, voting, results, close/delete, versioned change feed |
| [Attachments](#attachments--apiv1attachments) | `/api/v1/attachments` | Multipart upload with magic-byte sniff, BlurHash, and metadata |
| [Interactions (Reactions & Pins)](#interactions--reactions-apiv1messages-pins-apiv1conversationsidpins) | `/api/v1/messages`, `/api/v1/conversations` | Idempotent emoji reactions (POST/PUT/DELETE) and pinned messages |
| [Files](#files--apiv1files) | `/api/v1/files` | Streaming media download with HTTP Range support (`bytes=a-b`) |
| [Delta Sync](#delta-sync--apiv1sync) | `/api/v1/sync` | Keyset watermark resync for offline recovery, reaction event audit stream |
| [Calls](#calls--apiv1calls-and-apiv1conversations) | `/api/v1/calls` | ICE configuration, direct call history, and WebRTC signaling integration |
| [Conversation Summary](#conversation-summary-api) | `/api/v1/conversations/{id}/summary` | Consent-gated catch-up summary (local extractive & OpenAI-compatible LLM) |
| [Conversation Ask (RAG)](#conversation-ask-api-rag) | `/api/v1/conversations/{id}/ask` | Hybrid retrieval-augmented chat QA with citation validation and local lexical fallback |
| [WebSocket / STOMP](#websocket--stomp) | `/ws` | Realtime messaging, delivery/read receipts, typing, and presence |

## Conventions

- **Base URL:** `http://localhost:8080` · **Path prefix:** `/api/v1`
- **Auth:** JWT access token in the `Authorization: Bearer <accessToken>`
  header. Public (no token): `POST /auth/register`, `POST /auth/login`,
  `POST /auth/refresh`, `/actuator/health`, and the `/ws` handshake (the JWT
  travels in the STOMP CONNECT frame there instead).
- **Tokens:** access TTL 900 s (`accessTokenExpiresIn`); refresh TTL 14 days;
  refresh tokens rotate on use; `PATCH /auth/password` revokes other sessions
  by design.
- **Content type:** `application/json` unless noted (user/group avatars and
  attachments use `multipart/form-data`). Timestamps are ISO-8601 UTC (`Instant`).
- **Error envelope** (all non-2xx responses):

  ```json
  {
    "error": {
      "code": "PHONE_EXISTS",
      "message": "Phone number is already registered",
      "details": { "phone": "+84901234567" }
    }
  }
  ```

  `details` is omitted when empty. Codes come from
  `common/error/ApiErrorCode.java`.

### Error code reference

| Code | HTTP | Meaning |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Body failed bean validation or a service rule |
| `INVALID_CREDENTIALS` | 401 | Unknown phone or wrong password on login |
| `TOKEN_EXPIRED` | 401 | Access/refresh token past its TTL |
| `TOKEN_INVALID` | 401 | Malformed, unknown or revoked token |
| `TOKEN_REUSED` | 401 | Rotated refresh token presented again (family revoked) |
| `UNAUTHORIZED` | 401 | Missing/invalid Bearer token |
| `WRONG_PASSWORD` | 401 | `oldPassword` did not match on password change |
| `FORBIDDEN` | 403 | Authenticated but not allowed |
| `PHONE_EXISTS` | 409 | Register: phone already registered |
| `USER_NOT_FOUND` | 404 | Target user does not exist |
| `ALREADY_CONTACT` | 409 | Contact already present |
| `CONVERSATION_NOT_FOUND` | 404 | Conversation id does not exist |
| `NOT_MEMBER` | 403 | Caller is not a member of the conversation |
| `NOT_ADMIN` | 403 | Group role check failed (member ops, group pin, poll close/delete) |
| `NOT_A_GROUP` | 400 | Poll operation on a DIRECT conversation |
| `POLL_NOT_FOUND` | 404 | Poll missing, deleted, or belongs to another conversation |
| `POLL_CLOSED` | 409 | Vote/retract attempted after explicit close or expiry |
| `NOT_MESSAGE_AUTHOR` | 403 | Caller is not the message author |
| `MESSAGE_NOT_FOUND` | 404 | Message missing or belongs to another conversation |
| `MESSAGE_DELETED` | 409 | Deleted message cannot be edited, reacted to, or pinned |
| `FILE_NOT_FOUND` | 404 | Stored file / attachment key missing |
| `ATTACHMENT_NOT_FOUND` | 404 | Attachment record does not exist |
| `PIN_LIMIT` | 409 | Conversation already has the maximum 3 pinned messages |
| `CONVERSATION_PIN_LIMIT` | 409 | User already pinned the maximum 5 conversations |
| `IDEMPOTENCY_KEY_REUSED` | 409 | Same `Idempotency-Key` reused for a different endpoint or payload |
| `MIME_NOT_ALLOWED` | 415 | Uploaded bytes failed the magic-byte sniff |
| `FILE_TOO_LARGE` | 413 | Upload exceeds the configured max size |
| `RATE_LIMITED` | 429 | Per-user/IP rate limit exceeded |
| `NOT_FOUND` | 404 | Unknown path |
| `METHOD_NOT_ALLOWED` | 405 | Wrong HTTP method for the path |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Request `Content-Type` not accepted |
| `INTERNAL_ERROR` | 500 | Unhandled server failure |

### Global error behavior

- `400 VALIDATION_FAILED` from bean validation carries field errors in
  `details` (e.g. `{"field": "password", "message": "size must be between 8 and 72"}`).
  Service-rule violations carry a human-readable `message`, usually without details.
- `401 UNAUTHORIZED` / `TOKEN_EXPIRED` / `TOKEN_INVALID` are produced by the
  security layer (`JwtAuthFilter` + `AuthSecurityErrorHandler`) before the
  controller runs; `403 FORBIDDEN` is the authenticated-but-not-allowed case.
- `429 RATE_LIMITED` carries `details.retryAfterSeconds`. Buckets are per user
  when authenticated, otherwise per client IP.
- Unknown paths return `404 NOT_FOUND`; a wrong method on an existing path
  returns `405 METHOD_NOT_ALLOWED`; a bad `Content-Type` returns
  `415 UNSUPPORTED_MEDIA_TYPE`.
- Every non-2xx response uses the envelope above — there is no HTML error page
  under `/api/v1/**`.

### Idempotency and retries

- `POST /conversations/direct` is idempotent by design: `201` on first create,
  `200` with the same body afterwards.
- `POST /conversations/{id}/messages` is idempotent per `clientMessageId`:
  retrying the same key returns and re-broadcasts the stored message instead of
  duplicating it; receivers deduplicate by message id.
- REST message sends are limited to 30 requests per 10 seconds per authenticated
  user by default; excess requests return `429 RATE_LIMITED` with
  `details.retryAfterSeconds` (configured by the STOMP message-window properties).
- `POST /contacts`, `PATCH /contacts/{id}`, `DELETE /contacts/{id}` and
  `POST /conversations/{id}/read` are safe to retry.
- The mutating reaction endpoints (`POST`/`PUT`/`DELETE
  /messages/{id}/reactions[/{emoji}]`) and pin endpoints
  (`POST /conversations/{id}/pins`, `DELETE /conversations/{id}/pins/{messageId}`)
  accept an optional `Idempotency-Key` header (a UUID). The first request with a
  given `(user, key)` runs the operation and caches the outcome; a repeat with
  the same key, endpoint and payload returns the original result without
  running it again. Reusing a key for a different endpoint or payload is a
  client bug and returns `409 IDEMPOTENCY_KEY_REUSED`. Concurrent requests
  with the same key serialize on a database reservation, so only one request
  runs the mutation. Keys expire after 24 hours. Absent header = no replay
  protection (the toggle endpoint still toggles on every call).
- `POST /conversations/{id}/reminders` uses the same header and cache: a
  replay returns `200` with the original reminder, while the first create
  returns `201`. A key reused for a different reminder payload is
  `409 IDEMPOTENCY_KEY_REUSED`.
- `POST /auth/refresh` is **not** idempotent: each success rotates the token
  pair. Replaying an already-rotated refresh token returns
  `401 TOKEN_REUSED` and revokes the whole refresh family plus its existing
  access tokens.

## Response wire formats

JSON examples of the shapes referenced in the tables. Optional null fields are
omitted where noted; tombstone message content and preview content are explicit
JSON `null` values.

```json
{
  "user": {
    "id": "11111111-1111-4111-8111-111111111111",
    "phone": "+84900000001",
    "displayName": "An",
    "avatarUrl": "/api/v1/files/avatars/example.png"
  },
  "accessToken": "eyJhbGciOi...",
  "accessTokenExpiresIn": 900,
  "refreshToken": "d3f1... (opaque, DB-backed)"
}
```

`AuthResponse` is `{user: UserResponse, accessToken, accessTokenExpiresIn, refreshToken}`.
`UserResponse` = `{id, phone, displayName, avatarUrl}`; `ContactResponse` adds
`contactUserId`, `aliasName`, `avatarUrl` and `createdAt`.

```json
{
  "id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "type": "DIRECT",
  "members": [
    { "userId": "22222222-2222-4222-8222-222222222222",
      "displayName": "Binh", "phone": "+84900000002" }
  ],
  "createdAt": "2026-11-04T09:00:00Z"
}
```

`GET /conversations` returns an array of inbox entries:

```json
{
  "id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "type": "DIRECT",
  "title": "Binh",
  "avatarUrl": null,
  "members": [
    { "userId": "22222222-2222-4222-8222-222222222222",
      "displayName": "Binh", "phone": "+84900000002", "avatarUrl": null,
      "role": "MEMBER" }
  ],
  "lastMessage": {
    "seq": 6, "type": "TEXT", "contentSnippet": "Được, bao mình.",
    "senderId": "22222222-2222-4222-8222-222222222222",
    "createdAt": "2026-11-04T09:05:00Z"
  },
  "unreadCount": 2,
  "lastReadSeq": 4,
  "muted": false,
  "archived": false,
  "pinned": false
}
```

`MessageResponse` as returned by send and history (optional null fields omitted;
`content` is explicit `null` on a deleted-message tombstone):

```json
{
  "id": "00000000-0000-4000-8000-000000000007",
  "conversationId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "seq": 7,
  "senderId": "11111111-1111-4111-8111-111111111111",
  "senderDisplayName": "Demo",
  "type": "TEXT",
  "content": "Hello from REST",
  "clientMessageId": "3f2b8c1e-9a4d-4e6f-8b7c-1d2e3f4a5b6c",
  "attachments": [],
  "createdAt": "2026-11-04T09:10:00Z",
  "mentions": [],
  "mentionAll": false
}
```

Each item of `attachments` is an `AttachmentResponse` =
`{id, url, originalName, mimeType, sizeBytes, width?, height?, durationMs?}`.

## Auth — `/api/v1/auth`

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `POST /auth/register` | Public | `{phone, password, displayName}` — password 8–72 chars, displayName ≤60 | `201` `AuthResponse` | `VALIDATION_FAILED` 400 · `PHONE_EXISTS` 409 · `RATE_LIMITED` 429 |
| `POST /auth/login` | Public | `{phone, password}` | `200` `AuthResponse` | `VALIDATION_FAILED` 400 · `INVALID_CREDENTIALS` 401 · `RATE_LIMITED` 429 |
| `POST /auth/refresh` | Public | `{refreshToken}` | `200` `AuthResponse` (new token pair) | `VALIDATION_FAILED` 400 · `TOKEN_INVALID` / `TOKEN_EXPIRED` / `TOKEN_REUSED` 401 · `RATE_LIMITED` 429 |
| `POST /auth/logout` | Bearer | `{refreshToken}` | `204` empty | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 |
| `POST /auth/logout-all` | Bearer | — | `204` empty | `UNAUTHORIZED` 401 |
| `PATCH /auth/password` | Bearer | `{oldPassword, newPassword, refreshToken?}` — pass `refreshToken` to keep that session alive | `204` empty | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `WRONG_PASSWORD` 401 · `USER_NOT_FOUND` 404 |

`AuthResponse` = `{user: UserResponse, accessToken, accessTokenExpiresIn, refreshToken}`.
`UserResponse` = `{id, phone, displayName, avatarUrl}`. Rate limits: register and
login 10 req / 5 min, refresh 30 req / 5 min (keyed per client IP and per phone).

**Token revocation (Flyway V11):** `POST /auth/logout`, `POST /auth/logout-all`, and `PATCH /auth/password` stamp the user's access-token revocation cutoff (`users.tokens_valid_after`) and evict the user's entry from `AccessTokenValidityCache`. Any access token whose `iat` precedes that cutoff is immediately rejected on subsequent REST requests and WebSocket STOMP CONNECT frames with `401 TOKEN_INVALID`.

## Users — `/api/v1/users`

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `GET /users/me` | Bearer | — | `200` `UserResponse` | `UNAUTHORIZED` 401 |
| `PATCH /users/me` | Bearer | `{displayName?}` — absent/null keeps current name, blank is rejected | `200` `UserResponse` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 |
| `GET /users/search` | Bearer | Query `?phone=` (exact E.164 lookup) **or** `?q=` (displayName prefix, caller excluded) — exactly one required | `200` `UserResponse[]` — 0–1 for phone, ≤20 for `q` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `RATE_LIMITED` 429 (30/min per user) |
| `POST /users/me/avatar` | Bearer | `multipart/form-data` field `file`; image magic bytes only | `200` `{avatarUrl}` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `MIME_NOT_ALLOWED` 415 · `FILE_TOO_LARGE` 413 |
| `POST /users/device-token` | Bearer | `{token, platform?, deviceLabel?}` (alias: `/devices/token`) — token ≤512 chars, platform `ANDROID` or `IOS`, label ≤100 chars; uniqueness is `(user, token)`, so one physical device may hold a row per account | `200` `DeviceTokenResponse` `{id, token, platform, deviceLabel, updatedAt}` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 |
| `DELETE /users/device-token` | Bearer | Query `?token=` or body `{token}` (alias: `/devices/token`) | `204` empty | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 |

## Contacts — `/api/v1/contacts`

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `GET /contacts` | Bearer | — | `200` `ContactResponse[]` | `UNAUTHORIZED` 401 |
| `POST /contacts` | Bearer | `{phone}` | `201` `ContactResponse` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `USER_NOT_FOUND` 404 · `ALREADY_CONTACT` 409 · `RATE_LIMITED` 429 (30/min) |
| `PATCH /contacts/{userId}` | Bearer | `{aliasName?}` — JSON `null` clears the alias | `200` `ContactResponse` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `USER_NOT_FOUND` 404 |
| `DELETE /contacts/{userId}` | Bearer | — | `204` empty (idempotent: deleting a non-contact is still 204) | `UNAUTHORIZED` 401 |

`ContactResponse` = `{contactUserId, phone, displayName, aliasName, avatarUrl, createdAt}`;
`aliasName` is the owner's override, `null` means render `displayName`.

## Conversations — `/api/v1/conversations`

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `POST /conversations/direct` | Bearer | `{phone}` **or** `{userId}` — exactly one (XOR) | `201` created / `200` existing — same `ConversationResponse` either way (idempotent) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `USER_NOT_FOUND` 404 |
| `GET /conversations` | Bearer | Optional `limit?` (clamped to 1–100), stable opaque `cursor?`, `archived?`, and caller-owned `labelId?`; repeat `labelId` on each cursor page. Omit `limit`/`cursor` for the full filtered list | `200` `InboxEntry[]`, pinned conversations first, then `lastMessageSeq DESC` (no-message chats last), each with the caller's `labels: [uuid]` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 |
| `GET /conversations/{conversationId}/messages` | Bearer | Query `beforeSeq?`, `afterSeq?` (mutually exclusive), `limit?` (default 30, max 100) | `200` `MessagePageResponse` — `beforeSeq` newest-first; `afterSeq` oldest-first | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 |
| `POST /conversations/{conversationId}/messages` | Bearer | `{clientMessageId, type, content?, replyToId?, attachmentIds?, mentions?, mentionAll?}` — content ≤10,000 characters; at most 10 attachment IDs and 20 mentions | `201` created / `200` dedup hit — `MessageResponse` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `CONVERSATION_NOT_FOUND` 404 · `MESSAGE_NOT_FOUND` 404 · `FILE_NOT_FOUND` 404 |
| `PATCH /conversations/{conversationId}/messages/{messageId}` | Bearer, message author | `{content, mentions?, mentionAll?}` — trimmed, non-blank, ≤10,000 chars; TEXT only | `200` current `MessageResponse`; unchanged content and mention state is a no-op | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `NOT_MESSAGE_AUTHOR` 403 · `MESSAGE_NOT_FOUND` 404 · `MESSAGE_DELETED` 409 · `RATE_LIMITED` 429 (30/min per user, shared with delete) |
| `DELETE /conversations/{conversationId}/messages/{messageId}` | Bearer, message author | — | `204` (idempotent soft delete; retains seq, clears content, mention metadata, reactions and pin) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_MESSAGE_AUTHOR` 403 · `MESSAGE_NOT_FOUND` 404 · `RATE_LIMITED` 429 (30/min per user, shared with edit) |
| `GET /conversations/{conversationId}/messages/search` | Bearer member | `q` (trimmed, 2–100 chars), optional `limit` (1–50, default 20), `beforeSeq?` | `200` `MessagePageResponse`, newest-first; literal, case- and accent-insensitive TEXT substring match | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 · `RATE_LIMITED` 429 (60/min per user) |
| `POST /conversations/{conversationId}/read` | Bearer | `{upToSeq}` — non-negative seq no greater than that conversation's latest; watermark is monotonic | `204` empty (broadcasts the persisted `MESSAGE_READ` watermark unless the caller has `showSeen=false`) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 |
| `PUT /conversations/{conversationId}/mute` | Bearer member | `{mutedUntil?, forever?}` — exactly one of `mutedUntil` (ISO-8601 UTC instant, strictly in the future, at most 365 days from now) or `forever: true` | `204` empty (idempotent; the mute is stored for the caller only) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 |
| `DELETE /conversations/{conversationId}/mute` | Bearer member | — | `204` empty (idempotent no-op when already unmuted) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 |
| `PUT /conversations/{conversationId}/archive` | Bearer member | — | `204` empty (idempotent; hidden from the default inbox) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 |
| `DELETE /conversations/{conversationId}/archive` | Bearer member | — | `204` empty (idempotent no-op when not archived) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 |
| `PUT /conversations/{conversationId}/pin` | Bearer member | — | `204` empty (idempotent; pinned conversations sort first in the caller's inbox; ≤5 pinned conversations per user) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 · `CONVERSATION_PIN_LIMIT` 409 |
| `DELETE /conversations/{conversationId}/pin` | Bearer member | — | `204` empty (idempotent no-op when not pinned) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 |
| `PUT /conversations/{conversationId}/ai-opt-out` | Bearer member | — | `200` `{conversationId, aiOptOut: true}` (idempotent; sets the caller's own opt-out for this conversation) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 |
| `DELETE /conversations/{conversationId}/ai-opt-out` | Bearer member | — | `200` `{conversationId, aiOptOut: false}` (idempotent) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 |
| `GET /conversations/{conversationId}/ai-opt-out` | Bearer member | — | `200` `{conversationId, aiOptOut: <bool>}` — the caller's own state only | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 |

## Conversation labels — `/api/v1/labels`

Labels are private per-user preferences. Names are trimmed, 1–30 Unicode
characters, and case-insensitively unique per user; each user may create at
most 10. `colorId` is one of `INDIGO`, `BLUE`, `CYAN`, `TEAL`, `EMERALD`,
`AMBER`, `ROSE`, or `PURPLE`. Each caller can assign up to 3 of their labels
to each conversation they belong to. Assignment, removal and label deletion
are idempotent; deleting a label or leaving/being removed from a conversation
clears its assignments. All writes share a 30-per-minute per-user rate limit.

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `GET /labels` | Bearer | — | `200` caller's labels ordered by name | `UNAUTHORIZED` 401 |
| `POST /labels` | Bearer | `{name, colorId}` | `201` `LabelResponse` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `RATE_LIMITED` 429 |
| `PATCH /labels/{labelId}` | Bearer, label owner | Optional `{name?, colorId?}`; omitted fields stay unchanged | `200` `LabelResponse` | `VALIDATION_FAILED` 400 · `NOT_FOUND` 404 · `UNAUTHORIZED` 401 · `RATE_LIMITED` 429 |
| `DELETE /labels/{labelId}` | Bearer | — | `204` (idempotent; deletes caller's label and assignments) | `UNAUTHORIZED` 401 · `RATE_LIMITED` 429 |
| `PUT /conversations/{conversationId}/labels/{labelId}` | Bearer member and label owner | — | `204` (idempotent; at most 3 labels per conversation for the caller) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 · `NOT_FOUND` 404 · `RATE_LIMITED` 429 |
| `DELETE /conversations/{conversationId}/labels/{labelId}` | Bearer member and label owner | — | `204` (idempotent) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 · `NOT_FOUND` 404 · `RATE_LIMITED` 429 |
Message `mentions` are optional request objects `{userId, offset, length}`.
They are accepted only on `TEXT` messages in a `GROUP`; each target must be a
current member, target IDs must be unique, and at most 20 mentions are allowed.
`offset` and `length` count UTF-16 code units in the stored content (send keeps
the submitted text; edit trims leading and trailing whitespace before storing);
the range must be non-empty, in bounds, non-overlapping, and cannot split a
Unicode surrogate pair. Mention metadata is structured only: the server does
not infer mentions from `@` text. `mentionAll: true` is a separate group-message
flag, permitted only for current admins (`NOT_ADMIN` 403); it does not expand
into a `mentions` array. The existing per-user message send limit also covers
`mentionAll` (30 sends per 10 seconds by default); it has no separate quota.
`MessageResponse` always includes `mentions` (empty when absent) and
`mentionAll` (false when absent).
On edit, omitted `mentions` and `mentionAll` fields preserve their stored state;
`mentions: []` clears explicit targets and `mentionAll: false` clears the group
flag. Preserved ranges are validated against the new normalized content, so an
edit that makes a stored range invalid must send updated mention data or fail
with `VALIDATION_FAILED`. Deleting a message clears both forms of mention data.
History, search, delta sync and STOMP message frames expose these fields only to
current conversation members. Archive items and AI opt-out state responses do
not include structured mention metadata.

Mute, archive and pin are **per-member preferences**: they are stored on the
caller's `conversation_members` row and are never visible to, or affect, any
other member. Pinned conversations sort ahead of the activity order (pinned
rows first, then `lastMessageSeq DESC` within each group); the pin ceiling is
five conversations per user and applies only to NEW pins, so re-pinning an
already pinned conversation is an idempotent `204` even at the ceiling.
A muted conversation suppresses the recipient's `NEW_MESSAGE` data push (see
[Push notifications](operations.md#push-notifications-fcm)); the conversation
still receives messages, read/delivery receipts and live WebSocket events, and
`unreadCount` keeps advancing. **Push rule:** every `NEW_MESSAGE` push is gated
by mute, including `SYSTEM` messages (group membership and title notices). A
muted member still receives a `NEW_MESSAGE` push when the message explicitly
mentions that member or has `mentionAll: true` (those push pointers carry
`mentioned: "true"`), or when the `SYSTEM` event's subject is that member —
they were removed from the group or their role changed — so nobody is removed
or demoted silently. A removed member's mute row is deleted with their
membership, so the removal notice reaches them even if they had muted the
conversation. Avatar-only changes broadcast `CONVERSATION_UPDATED` and post no
SYSTEM message, so they produce no `NEW_MESSAGE` push to gate. `CALL_INVITE`
pushes always go out (a call must ring). Mute expiry is lazy: a `mutedUntil`
instant in the past is treated as not muted, so no cleanup job is required.
Archiving only changes inbox visibility — the default
`GET /conversations` (and every request that omits `archived`) excludes archived
rows, while `archived=true` returns only archived rows.

**Client convention — Zalo-style mute presets.** The preset choices
"1 hour", "4 hours", "until 8:00 AM" and "until turned back on" are computed on
the client into the `PUT /mute` body: the first three become a `mutedUntil`
instant (the next local 08:00 for "until 8:00 AM", converted to UTC) and
"until turned back on" becomes `{forever: true}`. The server stores only the
resulting instant or flag — it never interprets preset labels — and the same
365-day bound and future-instant validation apply. `DELETE /mute` is the client's
"turn back on" action.

`PATCH` broadcasts `MESSAGE_UPDATED` after commit when normalized content or
mention metadata changes. An identical content and mention state is a successful
no-op and produces no update event.
If the changed message is the conversation's last message, the existing
`CONVERSATION_UPDATED` inbox push also asks clients to refresh the snippet.
`DELETE` retains the row and sequence, sets `deletedAt`, clears content, and
removes mention rows, reaction rows and any pin. It does
not purge attachment metadata or stored file bytes; deleted-message attachment
URLs are no longer readable. History returns a tombstone with `content: null`,
`attachments: []`, `reactions: []`, `mentions: []`, and `mentionAll: false`. A
deleted last message remains the inbox `lastMessage`, with `deletedAt` and
`contentSnippet: null`; clients render
their localized deleted-message label. Reply previews keep their id/sender,
with `content: null` and `deleted: true`.

Search uses PostgreSQL `unaccent` + `ILIKE` with escaped `%`, `_`, and `\` so
those characters are literal. Flyway V4 installs `unaccent`; filtering and
newest-first pagination are scoped and ordered for the existing
`idx_msg_conv_seq` index. Flyway V8 adds a composite GIN trigram index on
`(conversation_id, unaccent(content))` so the substring predicate is indexable
on large conversations; PostgreSQL's cost planner may still prefer a table
scan on small datasets.

`GET /users/me/settings` returns `{showPresence: boolean, showSeen: boolean, aiSummaryConsent: boolean}`;
`showSeen` and `showPresence` default to `true`, `aiSummaryConsent` defaults to
`false`. `PATCH /users/me/settings` accepts any of the three fields
independently; an omitted field remains unchanged, and an empty object is
rejected.

`showSeen=false` is the Zalo-style reciprocal "hidden seen" state: the caller's
`MESSAGE_READ` receipts are not broadcast and the caller does not receive other
members' `MESSAGE_READ` receipts. The caller's own `lastReadSeq` and
`unreadCount` still advance exactly as before, and `MESSAGE_DELIVERED` receipts
are unaffected. This is enforced server-side in both directions; see
[`docs/websocket-protocol.md`](websocket-protocol.md#6-delivery-guarantees).
Provider selection uses persisted `users.ai_summary_consent`, and the LLM
adapter rechecks consent immediately before an outbound request, so revoking
consent after selection prevents that request. The account-level consent is
combined with the per-conversation opt-out (`PUT/DELETE
/conversations/{id}/ai-opt-out`): an opted-out member's messages are excluded
from external AI input in that conversation even when another member consented.
Inbox entries for DIRECT conversations may include
`peerPresence: {online, lastSeenAt}`; group and hidden-peer values are omitted.
Presence changes are described in the WebSocket section and are in-memory on a
single backend node.

`ConversationResponse` = `{id, type, members: [{userId, displayName, phone}], createdAt, aiOptOut}`.
`LabelResponse` = `{id, name, colorId, createdAt, updatedAt}`.
`InboxEntry` = `{id, type, title, avatarUrl, members: [{userId, displayName, phone, avatarUrl, role}], lastMessage?{seq, type, contentSnippet, senderId, createdAt, deletedAt}, unreadCount, lastReadSeq, muted, mutedUntil?, archived, pinned, pinnedAt?, labels: [uuid], aiOptOut, peerPresence?{online, lastSeenAt}}`; `labels` contains only the authenticated caller's label IDs and is always an array. Null `contentSnippet` marks a deleted last message. `muted`/`archived`/`pinned` are the **caller's own** per-member flags and are always present; `mutedUntil` is the caller's mute expiry (ISO-8601 UTC) and is omitted when the mute is forever or the conversation is not muted; `pinnedAt` is the start of the caller's current pin (ISO-8601 UTC) and is omitted when the conversation is not pinned. Null optional values may be omitted.
`aiOptOut` is the caller's own per-conversation AI opt-out state; another member's choice is never included. The same state is available directly from `GET /conversations/{id}/ai-opt-out`.
`MessagePageResponse` = `{items: MessageResponse[], nextBeforeSeq, hasMore}`; echo `nextBeforeSeq` as `beforeSeq` for the next older page only for `beforeSeq` history/search paging. `afterSeq` pages are oldest-first and set `nextBeforeSeq` to null.
`MessageResponse` = `{id, conversationId, seq, senderId, senderDisplayName, type, content, clientMessageId, replyToId, replyTo?, reactions?, attachments[], mentions[], mentionAll, createdAt, editedAt, deletedAt}` (`mentions` is empty and `mentionAll` false when unused; optional null fields are omitted; deleted `content` is explicit `null`).
`replyTo` preview = `{id, senderId, senderDisplayName, type, content, deleted}`; `reactions[]` = `{emoji, count, byMe}`.
`SendMessageRequest.type` accepts `TEXT`, `IMAGE`, `VIDEO`, `AUDIO`, `FILE`; `SYSTEM` is server-only. `TEXT` requires non-blank `content`. Re-sending the same `clientMessageId` in the same conversation returns and re-broadcasts the stored row without inserting a second message; receivers deduplicate by message id. Reusing it in a different conversation is `VALIDATION_FAILED`.
Inbox pagination uses query parameters `limit` and `cursor`. With no parameters, the complete list and byte-compatible response body are unchanged. A `limit` page is clamped to 1–100 items; `X-Next-Cursor` contains the next opaque keyset cursor only when another page exists. A cursor without a limit returns all remaining entries.

## Conversation archive — `/api/v1/conversations/{id}/archive`

The conversation storage browser ("Kho lưu trữ"): media, documents and shared
links for one conversation. Read-only, member-only, newest first.

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `GET /conversations/{conversationId}/archive` | Bearer member | Query `type=MEDIA\|FILE\|LINK` (required), optional `senderId=<uuid>`, `from=<ISO-8601>`, `to=<ISO-8601>`, `limit=<1..50, default 30>`, `cursor=<opaque>` | `200` `ArchiveResponse` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 |

Items are ordered by the message's global `seq` descending. `from`/`to` are
inclusive and filter on the message creation instant; `from` later than `to`
is `400`. `senderId` filters on the message author. `cursor` is an opaque,
signed keyset position: a malformed, altered or cross-conversation/cross-tab
value is `400`; it is never used for authorization, so membership is
re-checked on every request. New messages inserted while a client is paging
have a higher `seq` and therefore never shift, duplicate or skip an older
page. `nextCursor` is `null` on the last page.

Tab semantics:

- `MEDIA` returns `IMAGE` and `VIDEO` attachments (the `kind` of each item);
  voice notes (`messages.type = 'AUDIO'`) are excluded from both tabs.
- `FILE` returns every other attachment. One item is emitted per attachment,
  so a message with two files yields two items.
- `LINK` returns http(s) URLs parsed from live `TEXT` messages only — at most
  10 unique URLs per message, in first-occurrence order, with sentence
  punctuation and unbalanced closing brackets trimmed. `javascript:` and
  `data:` are never emitted. The server does not fetch URLs or build previews.
- Messages with `deletedAt` set (tombstones) are excluded from every tab.

`ArchiveResponse` = `{items: ArchiveItem[], nextCursor}`.

```json
{
  "items": [
    {
      "messageId": "00000000-0000-4000-8000-000000000007",
      "seq": 7,
      "senderId": "11111111-1111-4111-8111-111111111111",
      "createdAt": "2026-11-04T09:10:00Z",
      "kind": "IMAGE",
      "attachment": {
        "id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        "fileName": "photo.png",
        "mimeType": "image/png",
        "sizeBytes": 20480,
        "durationMs": null,
        "blurHash": "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
        "width": 1080,
        "height": 1920
      },
      "link": null
    },
    {
      "messageId": "00000000-0000-4000-8000-000000000006",
      "seq": 6,
      "senderId": "22222222-2222-4222-8222-222222222222",
      "createdAt": "2026-11-04T09:05:00Z",
      "kind": "LINK",
      "attachment": null,
      "link": { "url": "https://example.com/article" }
    }
  ],
  "nextCursor": "eyJ2Ijoi..."
}
```

`ArchiveItem` carries exactly one of `attachment`/`link`; the other is an
explicit JSON `null`. `ArchiveAttachmentView` =
`{id, fileName, mimeType, sizeBytes, durationMs, blurHash, width, height}`;
`durationMs`, `blurHash`, `width` and `height` are `null` when not known.
`ArchiveLinkView` = `{url}`.

## Groups — `/api/v1/conversations` (type `GROUP`)

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `POST /conversations/group` | Bearer | `{title, memberIds[]}` — non-blank title ≤80 chars; at least one other member after deduplication | `201` `ConversationResponse` (creator = ADMIN) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `USER_NOT_FOUND` 404 |
| `PATCH /conversations/{conversationId}` | Bearer ADMIN | `{title?, avatarUrl?}` — at least one field; empty `avatarUrl` clears it | `200` `ConversationUpdatePayload` (broadcasts `CONVERSATION_UPDATED`; title changes also post a SYSTEM message) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `CONVERSATION_NOT_FOUND` 404 |
| `POST /conversations/{conversationId}/members` | Bearer ADMIN | `{userIds[]}` | `204` (SYSTEM `MEMBER_ADDED` message broadcast) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `USER_NOT_FOUND` 404 |
| `DELETE /conversations/{conversationId}/members/{userId}` | Bearer ADMIN | — | `204` (SYSTEM `MEMBER_REMOVED` broadcast; sole admin cannot be removed) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `VALIDATION_FAILED` 400 |
| `POST /conversations/{conversationId}/leave` | Bearer | — | `204` (SYSTEM `MEMBER_LEFT` broadcast; sole admin blocked) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `VALIDATION_FAILED` 400 |
| `POST /conversations/{conversationId}/avatar` | Bearer ADMIN | `multipart/form-data` field `file`; image magic bytes only, stored under `avatars/` | `200` `{avatarUrl}` (broadcasts `CONVERSATION_UPDATED`) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `FILE_TOO_LARGE` 413 · `MIME_NOT_ALLOWED` 415 |

## Group Invite Links — `/api/v1/conversations/{id}/invite-link` and `/api/v1/invite-links`

Group invite links (Zalo "link nhóm / duyệt thành viên") allow group administrators
to create, rotate, and revoke high-entropy invite link tokens (stored securely
as SHA-256 hashes, raw tokens never logged or stored plaintext). Group admins can
toggle whether joining requires administrator approval. Authenticated users can resolve
safe previews without leaking member lists, join directly if approval is off, or
submit a join request if approval is on.

### Endpoints

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `POST /conversations/{conversationId}/invite-link` | Bearer ADMIN | `{expiresAt?, maxUses?, requiresApproval?}` — optional `expiresAt` (ISO-8601, future ≤30 days); optional `maxUses` (≥1); optional `requiresApproval` (boolean, default `false`) | `201` `InviteLinkResponse` (includes raw `token` for initial copy; rotates any existing active link) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `NOT_A_GROUP` 400 · `CONVERSATION_NOT_FOUND` 404 |
| `GET /conversations/{conversationId}/invite-link` | Bearer ADMIN | — | `200` `InviteLinkResponse` (metadata only; `token` is `null` because it is stored hashed) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `NOT_A_GROUP` 400 · `NOT_FOUND` 404 (if no active link) |
| `PATCH /conversations/{conversationId}/invite-link` | Bearer ADMIN | `{requiresApproval}` | `200` `InviteLinkResponse` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `NOT_A_GROUP` 400 · `NOT_FOUND` 404 |
| `DELETE /conversations/{conversationId}/invite-link` | Bearer ADMIN | — | `204` No Content (revokes active link) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `NOT_A_GROUP` 400 |
| `GET /invite-links/{token}` | Bearer | — | `200` `InvitePreviewResponse` `{conversationId, title, avatarUrl, memberCount, requiresApproval, isMember}` (never member lists) | `UNAUTHORIZED` 401 · `NOT_FOUND` 404 (uniform 404 for unknown/expired/revoked/used-up links) · `RATE_LIMITED` 429 |
| `POST /invite-links/{token}/join` | Bearer | — | `200` `{status:"JOINED"\|"ALREADY_MEMBER", conversationId}` or `201` `{status:"PENDING", conversationId, requestId}` | `VALIDATION_FAILED` 400 (e.g. member cap) · `UNAUTHORIZED` 401 · `FORBIDDEN` 403 (banned/removed user or rejected request) · `NOT_FOUND` 404 (uniform 404) · `RATE_LIMITED` 429 |
| `GET /conversations/{conversationId}/join-requests` | Bearer ADMIN | Optional `?status=PENDING\|APPROVED\|REJECTED` | `200` `JoinRequestResponse[]` | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `NOT_A_GROUP` 400 · `CONVERSATION_NOT_FOUND` 404 |
| `GET /conversations/{conversationId}/join-requests/me` | Bearer | — | `200` `JoinRequestResponse` (caller's own latest request) | `UNAUTHORIZED` 401 · `NOT_A_GROUP` 400 · `NOT_FOUND` 404 · `CONVERSATION_NOT_FOUND` 404 |
| `POST /conversations/{conversationId}/join-requests/{requestId}/approve` | Bearer ADMIN | — | `200` `JoinRequestResponse` (adds member, posts SYSTEM message, updates `status="APPROVED"`; idempotent) | `VALIDATION_FAILED` 400 (e.g. member cap or already rejected) · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `NOT_A_GROUP` 400 · `NOT_FOUND` 404 |
| `POST /conversations/{conversationId}/join-requests/{requestId}/reject` | Bearer ADMIN | — | `200` `JoinRequestResponse` (`status="REJECTED"`; idempotent) | `VALIDATION_FAILED` 400 (e.g. already approved) · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `NOT_A_GROUP` 400 · `NOT_FOUND` 404 |

### Invariants & Security Rules

- **Direct Conversations:** Direct chats cannot have invite links. Any attempt returns `400 NOT_A_GROUP`.
- **Hashed Storage & Entropy:** Tokens contain 256 bits of entropy (URL-safe base64, 43 chars) and are stored hashed with SHA-256 (`token_hash VARCHAR(64) UNIQUE`). The raw token is returned only once when created/rotated and is never logged or exposed in later GET endpoints. Constant-time comparison (`MessageDigest.isEqual`) is used for hash verification.
- **Uniform 404:** Non-existent, expired, revoked, and used-up links return a uniform `404 NOT_FOUND` with `"Invite link not found or inactive"` without leaking whether the link previously existed or what group it belonged to.
- **Safe Preview:** `GET /invite-links/{token}` returns only `conversationId`, `title`, `avatarUrl`, `memberCount`, `requiresApproval`, and `isMember`. Member rosters are never exposed.
- **Banned/Removed Users:** If a member was removed by an admin (`MEMBER_REMOVED`), they are recorded in `group_invite_bans` and cannot rejoin via the same invite link (`403 FORBIDDEN`) unless an admin explicitly re-invites them (`POST /conversations/{id}/members`).
- **Member Cap & Concurrency:** Groups enforce a maximum member capacity (`nexachat.group.max-members`, default 100). Joins and approvals serialize on the conversation row with `SELECT ... FOR UPDATE` so racing joiners cannot exceed the cap. Exceeding the cap returns `400 VALIDATION_FAILED` with `details.limit`.
- **Rate Limiting:** Link preview and join actions are rate limited per user (default 60 resolves/min, 20 joins/min) and per client IP bucket to prevent token brute-forcing (`429 RATE_LIMITED`).
- **Idempotency:** Repeated join requests while already a member return `200 ALREADY_MEMBER`; repeated join requests while approval is pending return `200 PENDING` with the existing request ID without duplicating rows. Repeated approval or rejection by admin is an idempotent 200 no-op.


## Reminders — `/api/v1/conversations/{id}/reminders`

The shared "Nhắc hẹn" board for a DIRECT or GROUP conversation: any member
creates a reminder, every member sees it, and its creator or a GROUP admin
edits/deletes it. A durable database-backed scheduler fires at `remindAt`,
pushes an FCM data message to every current member, then advances a repeating
reminder.

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `GET /conversations/{conversationId}/reminders` | Bearer member | — | `200` `ReminderResponse[]`, `remindAt` ascending (future active rows only) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 |
| `POST /conversations/{conversationId}/reminders` | Bearer member | `{title, remindAt, repeat?}` — title trimmed 1–200 chars; `remindAt` future and ≤365 days ahead; `repeat` `NONE\|DAILY\|WEEKLY\|MONTHLY` (default `NONE`); optional `Idempotency-Key` (UUID) | `201` created / `200` idempotent replay — `ReminderResponse` | `VALIDATION_FAILED` 400 (including the 50-active ceiling) · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 · `IDEMPOTENCY_KEY_REUSED` 409 · `RATE_LIMITED` 429 (30/min per user) |
| `PATCH /conversations/{conversationId}/reminders/{reminderId}` | Bearer; creator or GROUP admin | `{title?, remindAt?, repeat?}` — at least one field; each supplied field follows the create rules | `200` `ReminderResponse` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 (GROUP) · `FORBIDDEN` 403 (DIRECT) · `CONVERSATION_NOT_FOUND` 404 · `NOT_FOUND` 404 |
| `DELETE /conversations/{conversationId}/reminders/{reminderId}` | Bearer; creator or GROUP admin | — | `204` empty | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 (GROUP) · `FORBIDDEN` 403 (DIRECT) · `CONVERSATION_NOT_FOUND` 404 · `NOT_FOUND` 404 |

`ReminderResponse`:

```json
{
  "id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  "conversationId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "title": "Họp team",
  "remindAt": "2026-12-01T02:00:00Z",
  "repeat": "WEEKLY",
  "createdBy": "22222222-2222-4222-8222-222222222222",
  "createdByName": "Binh",
  "canEdit": true,
  "createdAt": "2026-11-09T09:00:00Z",
  "updatedAt": "2026-11-09T09:00:00Z"
}
```

- `remindAt` is the next occurrence the server will fire. `repeat` is the
  rule; `NONE` reminders disappear from the board after firing, while
  repeating reminders advance to their next future occurrence.
- `canEdit` is computed for the caller: true for the creator, or for a GROUP
  admin. A DIRECT non-creator cannot edit; a GROUP plain member cannot edit
  another member's reminder.
- Only future, active reminders are listed. A conversation may hold at most
  50 active reminders; the 51st create is `400 VALIDATION_FAILED` with
  `details.limit = 50`. Completed one-off rows are excluded from the count and
  purged after 30 days.
- An unknown conversation is `404 CONVERSATION_NOT_FOUND`; a non-member is
  `403 NOT_MEMBER` on every verb. A missing, deleted, or already-fired
  reminder id is `404 NOT_FOUND`.
- `POST` accepts an optional `Idempotency-Key` (UUID, 24-hour cache). The first
  request returns `201`; a replay of the same endpoint and payload returns
  `200` with the original reminder without creating a second row. Reusing the
  key with a different payload is `409 IDEMPOTENCY_KEY_REUSED`. A replay
  returns the original reminder even if its `remindAt` has since passed.
- Creation is rate-limited per user (30 requests per minute by default);
  excess requests are `429 RATE_LIMITED` with `details.retryAfterSeconds`.
- The server scheduler is restart- and multi-instance-safe: it claims due rows
  with `FOR UPDATE SKIP LOCKED` plus a lease, sends the push, then advances or
  completes the row under a row lock. A missed repeating occurrence after
  downtime produces one catch-up push, not one per missed interval. A crash
  between push acceptance and the advance commit can duplicate a push; the
  claim lease makes the reminder retry instead of dropping it.
- At `remindAt` the server sends a data-only FCM push to every current member's
  registered device tokens (shared device tokens are de-duplicated):

```json
{
  "type": "REMINDER",
  "reminderId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  "conversationId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "title": "Họp team",
  "remindAt": "2026-12-01T02:00:00Z",
  "repeat": "WEEKLY"
}
```

No message content travels through FCM. The reminder board has no WebSocket
event; clients refresh `GET /conversations/{id}/reminders` when the board opens
and when a `REMINDER` push arrives. Conversation mute is not part of this
contract yet, so pushes are not filtered per member.
## Group polls — `/api/v1/conversations/{id}/polls`
Polls exist only in GROUP conversations. Any current member may create and
vote; the poll creator or a group ADMIN may close or delete. A former member's
votes stay counted but that user cannot vote again, because membership is
rechecked on every request. A DIRECT conversation returns `400 NOT_A_GROUP`;
a non-member of any conversation returns `403 NOT_MEMBER` before the type is
checked.
| `POST /conversations/{id}/polls` | Bearer member | `{question, options[], multipleChoice?, anonymous?, closesAt?}` — question 1–200 chars; 2–10 unique options, each 1–100 chars; optional expiry in the future and at most 30 days out | `201` `PollResponse` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 · `NOT_A_GROUP` 400 · `RATE_LIMITED` 429 (10/min per user) |
| `GET /conversations/{id}/polls` | Bearer member | `limit?` (1–100, default 50), `before?` (id of the previous page's last item) | `200` `PollPageResponse`, newest first | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 · `NOT_A_GROUP` 400 |
| `GET /conversations/{id}/polls/changes` | Bearer member | `since?` (last applied `version`, default 0), `limit?` (1–200, default 100) | `200` `PollChangesResponse`, ascending `version` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 · `NOT_A_GROUP` 400 |
| `GET /conversations/{id}/polls/{pollId}` | Bearer member | — | `200` `PollResponse` | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 · `NOT_A_GROUP` 400 · `POLL_NOT_FOUND` 404 |
| `PUT /conversations/{id}/polls/{pollId}/vote` | Bearer member | `{optionIds[]}` — the complete selection: exactly 1 for single-choice, 1–10 for multiple-choice; duplicates ignored | `200` `PollResponse` (idempotent replacement) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 · `POLL_NOT_FOUND` 404 · `NOT_A_GROUP` 400 · `POLL_CLOSED` 409 · `RATE_LIMITED` 429 (120/min per user) |
| `DELETE /conversations/{id}/polls/{pollId}/vote` | Bearer member | — | `200` `PollResponse` (idempotent retract) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 · `POLL_NOT_FOUND` 404 · `NOT_A_GROUP` 400 · `POLL_CLOSED` 409 · `RATE_LIMITED` 429 (120/min per user, shared with vote) |
| `POST /conversations/{id}/polls/{pollId}/close` | Bearer creator or group ADMIN | — | `200` `PollResponse`; repeat close is a no-op | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `CONVERSATION_NOT_FOUND` 404 · `POLL_NOT_FOUND` 404 · `NOT_A_GROUP` 400 · `RATE_LIMITED` 429 (30/min per user) |
| `DELETE /conversations/{id}/polls/{pollId}` | Bearer creator or group ADMIN | — | `204` empty; repeat delete is a no-op | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `CONVERSATION_NOT_FOUND` 404 · `POLL_NOT_FOUND` 404 · `NOT_A_GROUP` 400 · `RATE_LIMITED` 429 (30/min per user, shared with close) |
`PollResponse` = `{id, conversationId, createdBy, question, multipleChoice, anonymous, status, closesAt?, closedAt?, closedBy?, createdAt, version, voterCount, options[], myOptionIds[]}`.
- `status` is `OPEN` or `CLOSED`. It is `CLOSED` when `closedAt` is set or
  `closesAt` has been reached; the expiry boundary is inclusive (a poll whose
  `closesAt` equals the current instant is already closed).
- `options[]` = `{id, position, label, voteCount, voterIds?}`. `voterIds` is
  present only on a non-anonymous poll, sorted by UUID string; on an anonymous
  poll it is omitted for everyone, including the creator. `voteCount` always
  equals `voterIds.size()` when the list is present.
- `myOptionIds` is always the caller's own current selection (empty when none)
  and never exposes another member's identity.
- `voterCount` is the number of distinct users who voted; in a multiple-choice
  poll it can be smaller than the sum of option `voteCount`s.
- `version` is a server-assigned monotonic revision. It changes on create,
  a real vote/retract change, the first close, and the first delete. A no-op
  replay keeps the same version, which makes retries safe. Poll writes are
  serialized per conversation, so revision order matches commit order; the
  change feed is a compacted current-state feed, meaning several changes to
  one poll between two reads are collapsed into its latest revision.
- `PollPageResponse` = `{items: PollResponse[], nextBefore?}`; `nextBefore` is
  the id to echo as `before` for the next older page and is omitted on the
  last page.
- `PollChangesResponse` = `{items: [{pollId, version, deleted, poll?}], nextSince, hasMore}`.
  The feed includes deleted polls as `{pollId, version, deleted:true}` with no
  `poll` body. Echo `nextSince` on the next call. This endpoint is the
  realtime substitute for polls: the frozen `ws/WsEventType` enum has no poll
  event, so a client that wants a live board polls this feed and merges by
  `pollId`/`version`.
- Deletion is a tombstone: the poll disappears from `GET /polls`, detail
  returns `404 POLL_NOT_FOUND`, and the change feed still reports it once.
- Votes are concurrency-safe: every write locks the poll row and replaces the
  caller's selection atomically; the `(poll_id, option_id, user_id)` primary
  key makes a duplicate vote impossible. A vote that exactly matches the
  stored selection succeeds without changing `version`, even after close, so
  an offline outbox replay cannot fail on a state it already applied.
- `Idempotency-Key` is not needed for vote/retract/close/delete: their verbs
  are already idempotent. Poll creation has no dedup key.

## Attachments — `/api/v1/attachments`

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `POST /attachments` | Bearer | `multipart/form-data`, field `file` plus optional `width`, `height`, `duration` and `blurHash` — upload-first: the attachment is bound to a message later via `SendMessageRequest.attachmentIds` | `201` `AttachmentResponse` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `FILE_TOO_LARGE` 413 · `MIME_NOT_ALLOWED` 415 |
| `GET /attachments/{id}/content` | Bearer member of the bound conversation (owner only while unbound) | — | `200` raw bytes, server-sniffed `Content-Type`, inline `Content-Disposition`, `X-Content-Type-Options: nosniff` | `UNAUTHORIZED` 401 · `FORBIDDEN`/`NOT_MEMBER` 403 · `ATTACHMENT_NOT_FOUND` 404 · `FILE_NOT_FOUND` 404 |

`AttachmentResponse` = `{id, url, originalName, mimeType, sizeBytes, width?, height?, durationMs?, blurHash?}`. Internal owner/message/storage fields and creation time are not exposed in this DTO. `blurHash` is the optional BlurHash placeholder for image attachments; it is `null` (omitted on the wire) for non-image and legacy rows.
Upload validation reads magic bytes, not the client-declared MIME; text types pass a UTF-8 check (`LocalStorageService`). `blurHash` is accepted only on image uploads and must be 6–64 characters from the BlurHash base83 alphabet; anything else is `400 VALIDATION_FAILED`.

ISO-BMFF (`ftyp`) files are classified by their tracks, not the brand alone: Android's `MediaRecorder` writes voice notes as `.m4a` under a generic `isom`/`mp42` brand, so a brand-only check would report `video/mp4`. The sniffer walks the boxes and reads each `trak/mdia/hdlr` handler — only `soun` tracks yield `audio/mp4`, any `vide` track yields `video/mp4`. A missing, truncated, or malformed `moov` falls back to the brand (`m4a` → `audio/mp4`, `isom`/`mp41`/`mp42` → `video/mp4`, anything else is rejected). The walk is bounded (box count, no per-box allocation) and never throws on hostile boxes.

## Interactions — reactions `/api/v1/messages`, pins `/api/v1/conversations/{id}/pins`

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `POST /messages/{messageId}/reactions` | Bearer member | `{emoji}` (≤16 code points, normalized to NFC) — **toggle**: same (user, emoji) removes | `204` (broadcasts `REACTION_UPDATED`) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `MESSAGE_NOT_FOUND` 404 · `MESSAGE_DELETED` 409 · `IDEMPOTENCY_KEY_REUSED` 409 |
| `PUT /messages/{messageId}/reactions/{emoji}` | Bearer member | emoji in the path (≤16 code points, NFC) — **idempotent add** | `204` (broadcasts `REACTION_UPDATED`) | same as POST |
| `DELETE /messages/{messageId}/reactions/{emoji}` | Bearer member | emoji in the path — **idempotent remove** | `204` (broadcasts `REACTION_UPDATED`) | same as POST |
| `GET /conversations/{conversationId}/pins` | Bearer member | — | `200` `PinnedMessageView[]` newest-first | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 |
| `POST /conversations/{conversationId}/pins` | Bearer member; **GROUP: ADMIN only** | `{messageId}` — same conversation, not deleted | `204` (broadcasts `PIN_UPDATED`; ≤3 per conversation, idempotent re-pin) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `MESSAGE_NOT_FOUND` 404 · `MESSAGE_DELETED` 409 · `PIN_LIMIT` 409 · `IDEMPOTENCY_KEY_REUSED` 409 |
| `DELETE /conversations/{conversationId}/pins/{messageId}` | same as POST | — | `204` (broadcasts `PIN_UPDATED {pinned:false}`; idempotent no-op) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `MESSAGE_NOT_FOUND` 404 · `IDEMPOTENCY_KEY_REUSED` 409 |

`PinnedMessageView` = `{messageId, pinnedBy, pinnedAt, message: MessageResponse}`.
Pin limit serializes under a `FOR UPDATE` lock on the conversation row.
`PUT`/`DELETE` reactions are the safe verbs for an offline outbox retry: a
replayed add/remove never inverts state. The `Idempotency-Key` header applies
to all five mutating endpoints (see [Idempotency and retries](#idempotency-and-retries)).

## Files — `/api/v1/files`

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `GET /files/**` | Bearer | Storage key is the path after `/api/v1/files/` (the `avatarUrl` returned by upload), normalized and confined to the storage root. Optional `Range: bytes=a-b` / `bytes=a-` / `bytes=-n` | `200` raw bytes (no `Range`) or `206 Partial Content` (honoured `Range`); `Accept-Ranges: bytes`, server-sniffed `Content-Type` (octet-stream fallback), `Content-Disposition: inline`, `X-Content-Type-Options: nosniff`; `416` with `Content-Range: bytes */size` for an unsatisfiable/malformed range | `UNAUTHORIZED` 401 · `FILE_NOT_FOUND` 404 · `INTERNAL_ERROR` 500 |

The serving sniff reads a bounded 64 KiB leading window and, only when it holds an ISO-BMFF `ftyp` whose kind is still undecided, a bounded 64 KiB trailing window, so a `moov`-at-end voice note is served as `audio/mp4`. Both windows are capped and the body is streamed; a large file is never buffered whole.

Generic uploads go through `POST /api/v1/attachments` above (upload-first,
bound to a message on send); this endpoint serves every stored object —
attachment keys are checked against the caller's conversation membership.

Files are streamed, never buffered whole. Only the first range of a
multi-range request is honoured (RFC 9110 permits a server to ignore the
rest); `HEAD` behaves like `GET` without a body.

## Delta sync — `/api/v1/sync`

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `POST /sync` (alias `/chat/sync`) | Bearer | `{watermarks: [{conversationId, lastSeq, reactionCursor?}], limitPerConv?, since?}` — 1–100 watermarks | `200` `SyncResponse` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 |

`SyncResponse` = `{deltas: [{conversationId, newMessages, editedMessages, deletedMessageIds, reactionUpdates, maxSeq, hasMore, reactionCursor?, reactionsFullRefresh, muted, mutedUntil?, archived, pinned, pinnedAt?}], serverTime}`. `muted`/`archived`/`pinned` are the **caller's own** per-member flags and are always present; `mutedUntil` is the caller's mute expiry (ISO-8601 UTC), omitted when the mute is forever or the conversation is not muted; `pinnedAt` is omitted when the conversation is not pinned. The flags are carried on every delta so a second device learns a mute/archive/pin change made elsewhere without a separate inbox fetch.
Supported by server index `idx_messages_delta_sync` on `(conversation_id, seq) INCLUDE (edited_at, deleted_at)`.

`reactionUpdates` is derived from the append-only `reaction_events` table, so a
removal is reported even though it deletes the `message_reactions` row. The
page is bounded by `nexachat.sync.max-reaction-updates` (default 200 events,
therefore at most 200 message ids). When the page is full, `hasMore=true` and
`reactionCursor` carries the last event id consumed; the client echoes it as
the watermark's `reactionCursor` on the next request so the follow-up page
and keeps the same `since`. The first page filters events by `created_at > since`;
follow-up pages use the cursor alone so new events committed during pagination
remain visible. Reaction writers and delta readers serialize on a transaction-
scoped conversation lock, which allocates event ids in commit order and prevents
a late commit from landing below an already-issued cursor. If `since` is older
than `nexachat.sync.reaction-retention-days` (default 30), the events needed
for a partial delta may have been purged, so the delta sets
`reactionsFullRefresh=true` and the client must reload reactions from history
instead of applying the (empty) partial page.

## Calls — `/api/v1/calls` and `/api/v1/conversations`

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `GET /calls/ice-config` | Bearer | — | `200` `{iceServers:[{urls:string[]}], ttlSeconds}` | `UNAUTHORIZED` 401 |
| `GET /conversations/{id}/calls` | Bearer member of a DIRECT conversation | `limit?` (default 20, range 1–100), optional opaque `cursor?` | `200` `{items: CallHistoryItem[], nextCursor?}` | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 · `VALIDATION_FAILED` 400 |

Call signaling uses STOMP `SEND /app/calls/{conversationId}/signal` and events
on `/user/queue/calls`; see [`docs/calls.md`](calls.md) for its frame and
state-machine contract.

## Conversation summary API

The current worktree also contains `POST /api/v1/conversations/{conversationId}/summary`.
The route is implemented in `SummaryController` and is shipped. The request and
response shapes, consent rules and provider behavior are specified in
[`docs/ai-summary.md`](ai-summary.md). The server requires conversation membership
and at least five usable messages. The response includes `contextOmitted`, a
neutral flag that is `true` only when the external LLM produced the summary and
some messages were excluded because their authors opted out in that
conversation; the local engine may still use those messages.

## Conversation ask API (RAG)

`POST /api/v1/conversations/{conversationId}/ask` (Bearer, conversation member)
answers a question about the caller's own conversation. The design, privacy
rules and cost controls are specified in [`docs/rag.md`](rag.md). The server API
and Android Ask screen are available.

Request:

```json
{ "question": "When is the server migration?", "language": "AUTO" }
```

`question` is trimmed, 1–500 characters; `language` is `AUTO|VI|EN`
(default `AUTO`, detected from the question).

Response `200`:

```json
{
  "answer": "The server migration is scheduled for March 14 at 02:00.",
  "citations": [
    { "messageId": "uuid", "seq": 12, "snippet": "The server migration is ..." }
  ],
  "mode": "EXTRACTIVE",
  "contextOmitted": false,
  "usage": { "llmCalls": 0, "inputChars": 0, "cached": false, "provider": null }
}
```

`mode` is `CACHE` (question cache hit), `NOT_FOUND` (answerability gate, no LLM
call), `EXTRACTIVE` (one cited message answers a lookup-like question) or `LLM`
(retrieved, budgeted context). `answer` is `null` for `NOT_FOUND`; `citations`
is never null and every cited message belongs to the conversation. `usage`
reports the number of LLM calls, the message characters sent to the LLM,
whether the answer came from the cache, and `provider` (`primary` or `fallback`
for an `LLM` answer, otherwise null). `contextOmitted` is a neutral flag: `true`
when at least one eligible message in the conversation is currently excluded
from retrieval because its author opted out; it never identifies the member.

Errors use the standard envelope: `400 VALIDATION_FAILED`, `403 NOT_MEMBER`,
`404 CONVERSATION_NOT_FOUND`, `429 RATE_LIMITED` (ask bucket: 20/minute and
200/day per user). Provider failures degrade to `NOT_FOUND` or `EXTRACTIVE`;
the endpoint never answers `503` for a provider problem.

`GET /api/v1/rag/stats` (Bearer) returns aggregate RAG counters only:
`{requests, cacheHits, notFound, extractive, llmCalls, llmFallbackCalls,
llmFailures, inputChars, meanInputChars, embeddingCalls, embeddingFailures,
indexedMessages}`. No message text, questions or vectors are exposed.
**Authorization:** This endpoint is operator telemetry restricted to administrator
phone numbers configured in `NEXACHAT_ADMIN_PHONES`. Non-admin authenticated
callers receive `403 FORBIDDEN` (`code: FORBIDDEN`). When `NEXACHAT_ADMIN_PHONES`
is empty or unset, the endpoint is denied to all users.

## WebSocket / STOMP

Realtime transport is STOMP 1.2 over `ws://localhost:8080/ws` (WSS in
production). Authentication uses the **CONNECT frame header**
`Authorization:Bearer <accessToken>` — no space after the colon because the
server does not trim native STOMP header values; never put the token in the URL.

Application destinations mirror the REST surface and share the same service
layer (`MessageService.send` / `markRead`), so validation and authorization rules
above apply unchanged:

- Send message: `SEND /app/conversations/{id}/messages`
- Typing signal: `SEND /app/conversations/{id}/typing`
- Delivery receipt: `SEND /app/conversations/{id}/delivered` (or `/app/chat.delivery.ack`)
- Read receipt: `SEND /app/conversations/{id}/read` (or `/app/chat.read.ack`) — broadcast as `MESSAGE_READ` only when the reader shares seen; hidden recipients do not receive peers' receipts (see the settings section)
- Call signaling: `SEND /app/calls/{id}/signal` (details in [`docs/calls.md`](calls.md))
- Events: `SUBSCRIBE /topic/conversations.{id}` and `/user/queue/inbox`
- Errors: `SUBSCRIBE /user/queue/errors` → `{code, clientMessageId?}`; a rate-limited message frame returns `{"code":"RATE_LIMITED","clientMessageId":"<uuid>"}`, while a rate-limited typing frame omits `clientMessageId`.

Message SEND is limited to 30 per 10 seconds per authenticated user by default (`nexachat.rate-limit.stomp.messages-per-window`, `nexachat.rate-limit.stomp.window-seconds`). Typing SEND is separately limited to 60 per 10 seconds (`nexachat.rate-limit.stomp.typing-per-window`, `nexachat.rate-limit.stomp.typing-window-seconds`). Excess frames are dropped and return `RATE_LIMITED` on `/user/queue/errors` without closing the session.

The full destination table, event envelope and frame contracts live in
[`docs/websocket-protocol.md`](websocket-protocol.md) — see its §4 Destinations
and §5 Event envelope. This file intentionally does not duplicate them.
