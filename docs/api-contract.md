# NexaChat REST API Contract

> Endpoint catalog for the current REST contract. Routes below are derived from
> controllers under `server/src/main/java/com/nexachat/`.
> The WebSocket / STOMP contract is documented in [`docs/websocket-protocol.md`](websocket-protocol.md).
> Conversation RAG is detailed in [`docs/rag.md`](rag.md) (server API available, Android entry in progress).

## Domain index

| Domain / Resource | Base path | Key capabilities |
|---|---|---|
| [Auth & Users](#auth--apiv1auth-and-users-apiv1users) | `/api/v1/auth`, `/api/v1/users` | Register, login, token refresh, password, profile, settings, device token |
| [Contacts](#contacts--apiv1contacts) | `/api/v1/contacts` | Contact directory, search by phone, contact alias names |
| [Conversations & Messages](#direct-conversations--apiv1conversations-and-messages-apiv1conversationsidmessages) | `/api/v1/conversations` | 1-1 chat, message sending, editing, soft-deletion, trigram search, read marks |
| [Groups](#groups--apiv1conversations-type-group) | `/api/v1/conversations/group` | Group creation, member addition/removal, admin roles, title/avatar updates |
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
| `NOT_ADMIN` | 403 | Group role check failed (member ops, group pin) |
| `NOT_MESSAGE_AUTHOR` | 403 | Caller is not the message author |
| `MESSAGE_NOT_FOUND` | 404 | Message missing or belongs to another conversation |
| `MESSAGE_DELETED` | 409 | Deleted message cannot be edited, reacted to, or pinned |
| `FILE_NOT_FOUND` | 404 | Stored file / attachment key missing |
| `ATTACHMENT_NOT_FOUND` | 404 | Attachment record does not exist |
| `PIN_LIMIT` | 409 | Conversation already has the maximum 3 pinned messages |
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
  client bug and returns `409 IDEMPOTENCY_KEY_REUSED`. Keys expire after 24
  hours. Absent header = no replay protection (the toggle endpoint still
  toggles on every call).
- `POST /auth/refresh` is **not** idempotent: each success rotates the token
  pair. Replaying an already-rotated refresh token returns
  `401 TOKEN_REUSED` and revokes the whole session family.

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
  "lastReadSeq": 4
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
  "createdAt": "2026-11-04T09:10:00Z"
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
login 10 req / 5 min, refresh 30 req / 5 min.

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
| `GET /conversations` | Bearer | Optional `limit?` (clamped to 1–100) and stable opaque `cursor?`; omit both for the full list | `200` `InboxEntry[]`, ordered `lastMessageSeq DESC` (no-message chats last) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 |
| `GET /conversations/{conversationId}/messages` | Bearer | Query `beforeSeq?`, `afterSeq?` (mutually exclusive), `limit?` (default 30, max 100) | `200` `MessagePageResponse` — `beforeSeq` newest-first; `afterSeq` oldest-first | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 |
| `POST /conversations/{conversationId}/messages` | Bearer | `{clientMessageId, type, content?, replyToId?, attachmentIds?}` — content ≤10,000 characters; at most 10 attachment IDs | `201` created / `200` dedup hit — `MessageResponse` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 · `MESSAGE_NOT_FOUND` 404 · `FILE_NOT_FOUND` 404 |
| `PATCH /conversations/{conversationId}/messages/{messageId}` | Bearer, message author | `{content}` — trimmed, non-blank, ≤10,000 chars; TEXT only | `200` current `MessageResponse`; identical content is a no-op | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_MESSAGE_AUTHOR` 403 · `MESSAGE_NOT_FOUND` 404 · `MESSAGE_DELETED` 409 · `RATE_LIMITED` 429 (30/min per user, shared with delete) |
| `DELETE /conversations/{conversationId}/messages/{messageId}` | Bearer, message author | — | `204` (idempotent soft delete; retains seq, clears content, reactions and pin) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_MESSAGE_AUTHOR` 403 · `MESSAGE_NOT_FOUND` 404 · `RATE_LIMITED` 429 (30/min per user, shared with edit) |
| `GET /conversations/{conversationId}/messages/search` | Bearer member | `q` (trimmed, 2–100 chars), optional `limit` (1–50, default 20), `beforeSeq?` | `200` `MessagePageResponse`, newest-first; literal, case- and accent-insensitive TEXT substring match | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 · `RATE_LIMITED` 429 (60/min per user) |
| `POST /conversations/{conversationId}/read` | Bearer | `{upToSeq}` — non-negative seq no greater than that conversation's latest; watermark is monotonic | `204` empty (broadcasts the persisted `MESSAGE_READ` watermark) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 |

`PATCH` broadcasts `MESSAGE_UPDATED` after commit only when content changes.
Identical normalized content is a successful no-op and produces no update event.
If the changed message is the conversation's last message, the existing
`CONVERSATION_UPDATED` inbox push also asks clients to refresh the snippet.
`DELETE` retains the row and sequence,
sets `deletedAt`, clears content, and removes reaction rows and any pin. It does
not purge attachment metadata or stored file bytes; deleted-message attachment
URLs are no longer readable. History returns a tombstone with `content: null`,
`attachments: []`, and `reactions: []`. A deleted last message remains the
inbox `lastMessage`, with `deletedAt` and `contentSnippet: null`; clients render
their localized deleted-message label. Reply previews keep their id/sender,
with `content: null` and `deleted: true`.

Search uses PostgreSQL `unaccent` + `ILIKE` with escaped `%`, `_`, and `\` so
those characters are literal. Flyway V4 installs `unaccent`; filtering and
newest-first pagination are scoped and ordered for the existing
`idx_msg_conv_seq` index. Flyway V8 adds a composite GIN trigram index on
`(conversation_id, unaccent(content))` so the substring predicate is indexable
on large conversations; PostgreSQL's cost planner may still prefer a table
scan on small datasets.

`GET /users/me/settings` returns `{showPresence: boolean, aiSummaryConsent: boolean}`;
`aiSummaryConsent` defaults to `false`. `PATCH /users/me/settings` accepts either
field independently; an omitted field remains unchanged, and an empty object is
rejected. Provider selection uses persisted `users.ai_summary_consent`, and the LLM
adapter rechecks consent immediately before an outbound request, so revoking
consent after selection prevents that request. Inbox entries for DIRECT conversations may include
`peerPresence: {online, lastSeenAt}`; group and hidden-peer values are omitted.
Presence changes are described in the WebSocket section and are in-memory on a
single backend node.

`ConversationResponse` = `{id, type, members: [{userId, displayName, phone}], createdAt}`.
`InboxEntry` = `{id, type, title, avatarUrl, members: [{userId, displayName, phone, avatarUrl, role}], lastMessage?{seq, type, contentSnippet, senderId, createdAt, deletedAt}, unreadCount, lastReadSeq, peerPresence?{online, lastSeenAt}}`; null `contentSnippet` marks a deleted last message. Null optional values may be omitted.
`MessagePageResponse` = `{items: MessageResponse[], nextBeforeSeq, hasMore}`; echo `nextBeforeSeq` as `beforeSeq` for the next older page only for `beforeSeq` history/search paging. `afterSeq` pages are oldest-first and set `nextBeforeSeq` to null.
`MessageResponse` = `{id, conversationId, seq, senderId, senderDisplayName, type, content, clientMessageId, replyToId, replyTo?, reactions?, attachments[], createdAt, editedAt, deletedAt}` (optional null fields omitted; deleted `content` is explicit `null`).
`replyTo` preview = `{id, senderId, senderDisplayName, type, content, deleted}`; `reactions[]` = `{emoji, count, byMe}`.
`SendMessageRequest.type` accepts `TEXT`, `IMAGE`, `VIDEO`, `AUDIO`, `FILE`; `SYSTEM` is server-only. `TEXT` requires non-blank `content`. Re-sending the same `clientMessageId` in the same conversation returns and re-broadcasts the stored row without inserting a second message; receivers deduplicate by message id. Reusing it in a different conversation is `VALIDATION_FAILED`.
Inbox pagination uses query parameters `limit` and `cursor`. With no parameters, the complete list and byte-compatible response body are unchanged. A `limit` page is clamped to 1–100 items; `X-Next-Cursor` contains the next opaque keyset cursor only when another page exists. A cursor without a limit returns all remaining entries.

## Groups — `/api/v1/conversations` (type `GROUP`)

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `POST /conversations/group` | Bearer | `{title, memberIds[]}` — non-blank title ≤80 chars; at least one other member after deduplication | `201` `ConversationResponse` (creator = ADMIN) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `USER_NOT_FOUND` 404 |
| `PATCH /conversations/{conversationId}` | Bearer ADMIN | `{title?, avatarUrl?}` — at least one field; empty `avatarUrl` clears it | `200` `ConversationUpdatePayload` (broadcasts `CONVERSATION_UPDATED`; title changes also post a SYSTEM message) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `CONVERSATION_NOT_FOUND` 404 |
| `POST /conversations/{conversationId}/members` | Bearer ADMIN | `{userIds[]}` | `204` (SYSTEM `MEMBER_ADDED` message broadcast) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `USER_NOT_FOUND` 404 |
| `DELETE /conversations/{conversationId}/members/{userId}` | Bearer ADMIN | — | `204` (SYSTEM `MEMBER_REMOVED` broadcast; sole admin cannot be removed) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `VALIDATION_FAILED` 400 |
| `POST /conversations/{conversationId}/leave` | Bearer | — | `204` (SYSTEM `MEMBER_LEFT` broadcast; sole admin blocked) | `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `VALIDATION_FAILED` 400 |
| `POST /conversations/{conversationId}/avatar` | Bearer ADMIN | `multipart/form-data` field `file`; image magic bytes only, stored under `avatars/` | `200` `{avatarUrl}` (broadcasts `CONVERSATION_UPDATED`) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `NOT_ADMIN` 403 · `FILE_TOO_LARGE` 413 · `MIME_NOT_ALLOWED` 415 |

## Attachments — `/api/v1/attachments`

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `POST /attachments` | Bearer | `multipart/form-data`, field `file` plus optional `width`, `height`, `duration` and `blurHash` — upload-first: the attachment is bound to a message later via `SendMessageRequest.attachmentIds` | `201` `AttachmentResponse` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `FILE_TOO_LARGE` 413 · `MIME_NOT_ALLOWED` 415 |
| `GET /attachments/{id}/content` | Bearer member of the bound conversation (owner only while unbound) | — | `200` raw bytes, server-sniffed `Content-Type`, inline `Content-Disposition`, `X-Content-Type-Options: nosniff` | `UNAUTHORIZED` 401 · `FORBIDDEN`/`NOT_MEMBER` 403 · `ATTACHMENT_NOT_FOUND` 404 · `FILE_NOT_FOUND` 404 |

`AttachmentResponse` = `{id, url, originalName, mimeType, sizeBytes, width?, height?, durationMs?, blurHash?}`. Internal owner/message/storage fields and creation time are not exposed in this DTO. `blurHash` is the optional BlurHash placeholder for image attachments; it is `null` (omitted on the wire) for non-image and legacy rows.
Upload validation reads magic bytes, not the client-declared MIME; text types pass a UTF-8 check (`LocalStorageService`). `blurHash` is accepted only on image uploads and must be 6–64 characters from the BlurHash base83 alphabet; anything else is `400 VALIDATION_FAILED`.

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

`SyncResponse` = `{deltas: [{conversationId, newMessages, editedMessages, deletedMessageIds, reactionUpdates, maxSeq, hasMore, reactionCursor?, reactionsFullRefresh}], serverTime}`.
Supported by server index `idx_messages_delta_sync` on `(conversation_id, seq) INCLUDE (edited_at, deleted_at)`.

`reactionUpdates` is derived from the append-only `reaction_events` table, so a
removal is reported even though it deletes the `message_reactions` row. The
page is bounded by `nexachat.sync.max-reaction-updates` (default 200 events,
therefore at most 200 message ids). When the page is full, `hasMore=true` and
`reactionCursor` carries the last event id consumed; the client echoes it as
the watermark's `reactionCursor` on the next request so the follow-up page
cannot skip rows (`reaction_events` is ordered by `id`). If `since` is older
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
and at least five usable messages.

## Conversation ask API (RAG)

`POST /api/v1/conversations/{conversationId}/ask` (Bearer, conversation member)
answers a question about the caller's own conversation. The design, privacy
rules and cost controls are specified in [`docs/rag.md`](rag.md). Note that the
server API is available, while the Android entry point is currently in progress.

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
  "usage": { "llmCalls": 0, "inputChars": 0, "cached": false, "provider": null }
}
```

`mode` is `CACHE` (question cache hit), `NOT_FOUND` (answerability gate, no LLM
call), `EXTRACTIVE` (one cited message answers a lookup-like question) or `LLM`
(retrieved, budgeted context). `answer` is `null` for `NOT_FOUND`; `citations`
is never null and every cited message belongs to the conversation. `usage`
reports the number of LLM calls, the message characters sent to the LLM,
whether the answer came from the cache, and `provider` (`primary` or `fallback`
for an `LLM` answer, otherwise null).

Errors use the standard envelope: `400 VALIDATION_FAILED`, `403 NOT_MEMBER`,
`404 CONVERSATION_NOT_FOUND`, `429 RATE_LIMITED` (ask bucket: 20/minute and
200/day per user). Provider failures degrade to `NOT_FOUND` or `EXTRACTIVE`;
the endpoint never answers `503` for a provider problem.

`GET /api/v1/rag/stats` (Bearer) returns aggregate RAG counters only:
`{requests, cacheHits, notFound, extractive, llmCalls, llmFallbackCalls,
llmFailures, inputChars, meanInputChars, embeddingCalls, embeddingFailures,
indexedMessages}`. No message text, questions or vectors are exposed.

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
- Read receipt: `SEND /app/conversations/{id}/read` (or `/app/chat.read.ack`)
- Call signaling: `SEND /app/calls/{id}/signal` (details in [`docs/calls.md`](calls.md))
- Events: `SUBSCRIBE /topic/conversations.{id}` and `/user/queue/inbox`
- Errors: `SUBSCRIBE /user/queue/errors` → `{code, clientMessageId?}`; a rate-limited message frame returns `{"code":"RATE_LIMITED","clientMessageId":"<uuid>"}`, while a rate-limited typing frame omits `clientMessageId`.

Message SEND is limited to 30 per 10 seconds per authenticated user by default (`nexachat.rate-limit.stomp.messages-per-window`, `nexachat.rate-limit.stomp.window-seconds`). Typing SEND is separately limited to 60 per 10 seconds (`nexachat.rate-limit.stomp.typing-per-window`, `nexachat.rate-limit.stomp.typing-window-seconds`). Excess frames are dropped and return `RATE_LIMITED` on `/user/queue/errors` without closing the session.

The full destination table, event envelope and frame contracts live in
[`docs/websocket-protocol.md`](websocket-protocol.md) — see its §4 Destinations
and §5 Event envelope. This file intentionally does not duplicate them.
