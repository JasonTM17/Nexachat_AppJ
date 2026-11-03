# NexaChat REST API Contract

> Complete endpoint catalog for the project report / viva. Every route below is
> derived from the shipped controllers under
> `server/src/main/java/com/nexachat/` — nothing is invented. The WebSocket /
> STOMP contract is documented separately in
> [`docs/websocket-protocol.md`](websocket-protocol.md); this file only links it.

## Conventions

- **Base URL:** `http://localhost:8080` · **Path prefix:** `/api/v1`
- **Auth:** JWT access token in the `Authorization: Bearer <accessToken>`
  header. Public (no token): `POST /auth/register`, `POST /auth/login`,
  `POST /auth/refresh`, `/actuator/health`, and the `/ws` handshake (the JWT
  travels in the STOMP CONNECT frame there instead).
- **Tokens:** access TTL 900 s (`accessTokenExpiresIn`); refresh tokens rotate
  on use; `PATCH /auth/password` revokes other sessions by design.
- **Content type:** `application/json` unless noted (avatar upload is
  `multipart/form-data`). Timestamps are ISO-8601 UTC (`Instant`).
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
| `NOT_ADMIN` | 403 | Group role check (reserved for group phase) |
| `MESSAGE_NOT_FOUND` | 404 | Reply target message missing |
| `FILE_NOT_FOUND` | 404 | Stored file / attachment key missing |
| `PIN_LIMIT` | 409 | Unused (future pin feature) |
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
  retrying the exact same body returns the stored message instead of duplicating it.
- `POST /contacts`, `PATCH /contacts/{id}`, `DELETE /contacts/{id}` and
  `POST /conversations/{id}/read` are safe to retry.
- `POST /auth/refresh` is **not** idempotent: each success rotates the token
  pair. Replaying an already-rotated refresh token returns
  `401 TOKEN_REUSED` and revokes the whole session family.

## Response wire formats

JSON examples of the shapes referenced in the tables. Null/absent fields are
omitted where noted (`@JsonInclude(NON_NULL)` applies to messages).

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
`contactUserId`, `aliasName` and `createdAt`.

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
  "members": [
    { "userId": "22222222-2222-4222-8222-222222222222",
      "displayName": "Binh", "phone": "+84900000002", "avatarUrl": null }
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

`MessageResponse` as returned by send and history (null fields omitted):

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

## Contacts — `/api/v1/contacts`

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `GET /contacts` | Bearer | — | `200` `ContactResponse[]` | `UNAUTHORIZED` 401 |
| `POST /contacts` | Bearer | `{phone}` | `201` `ContactResponse` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `USER_NOT_FOUND` 404 · `ALREADY_CONTACT` 409 · `RATE_LIMITED` 429 (30/min) |
| `PATCH /contacts/{userId}` | Bearer | `{aliasName?}` — JSON `null` clears the alias | `200` `ContactResponse` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `USER_NOT_FOUND` 404 |
| `DELETE /contacts/{userId}` | Bearer | — | `204` empty (idempotent: deleting a non-contact is still 204) | `UNAUTHORIZED` 401 |

`ContactResponse` = `{contactUserId, phone, displayName, aliasName, createdAt}`;
`aliasName` is the owner's override, `null` means render `displayName`.

## Conversations — `/api/v1/conversations`

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `POST /conversations/direct` | Bearer | `{phone}` **or** `{userId}` — exactly one (XOR) | `201` created / `200` existing — same `ConversationResponse` either way (idempotent) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `USER_NOT_FOUND` 404 |
| `GET /conversations` | Bearer | — | `200` `InboxEntry[]`, ordered `lastMessageSeq DESC` (no-message chats last) | `UNAUTHORIZED` 401 |
| `GET /conversations/{conversationId}/messages` | Bearer | Query `beforeSeq?`, `afterSeq?` (mutually exclusive), `limit?` (default 30, max 100) | `200` `MessagePageResponse` — items newest-first | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 |
| `POST /conversations/{conversationId}/messages` | Bearer | `{clientMessageId, type, content?, replyToId?, attachmentIds?}` | `201` created / `200` dedup hit — `MessageResponse` | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 · `MESSAGE_NOT_FOUND` 404 · `FILE_NOT_FOUND` 404 |
| `POST /conversations/{conversationId}/read` | Bearer | `{upToSeq}` — non-negative global seq, watermark is monotonic | `204` empty (broadcasts `MESSAGE_READ`) | `VALIDATION_FAILED` 400 · `UNAUTHORIZED` 401 · `NOT_MEMBER` 403 · `CONVERSATION_NOT_FOUND` 404 |

`ConversationResponse` = `{id, type ("DIRECT"), members: [{userId, displayName, phone}], createdAt}`.
`InboxEntry` = `{id, type, title, members[], lastMessage?{seq, type, contentSnippet, senderId, createdAt}, unreadCount, lastReadSeq}`.
`MessagePageResponse` = `{items: MessageResponse[], nextBeforeSeq, hasMore}`; echo `nextBeforeSeq` as `beforeSeq` for the next older page.
`MessageResponse` = `{id, conversationId, seq, senderId, senderDisplayName, type, content, clientMessageId, replyToId, attachments[], createdAt, editedAt}` (null fields omitted).
`SendMessageRequest.type` accepts `TEXT`, `IMAGE`, `VIDEO`, `AUDIO`, `FILE`; `SYSTEM` is server-only. `TEXT` requires non-blank `content`. Re-sending the same `clientMessageId` in the same conversation returns the stored row (idempotent); reusing it in a different conversation is `VALIDATION_FAILED`.

## Files — `/api/v1/files`

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `GET /files/**` | Bearer | Storage key is the path after `/api/v1/files/` (the `avatarUrl` returned by upload), normalized and confined to the storage root | `200` raw bytes, sniffed `Content-Type`, `Content-Disposition: inline` | `UNAUTHORIZED` 401 · `FILE_NOT_FOUND` 404 · `INTERNAL_ERROR` 500 |

There is no generic multipart upload endpoint yet: only the avatar upload above
writes files; every stored object is read back through this endpoint.

## WebSocket / STOMP

Realtime transport is STOMP 1.2 over `ws://localhost:8080/ws` (WSS in
production). Authentication uses the **CONNECT frame header**
`authorization: Bearer <accessToken>` — never a query-string token.

Application destinations mirror the REST surface and share the same service
layer (`MessageService.send` / `markRead`), so validation and authorization rules
above apply unchanged:

- Send message: `SEND /app/conversations/{id}/messages`
- Typing signal: `SEND /app/conversations/{id}/typing`
- Read receipt: `SEND /app/conversations/{id}/read`
- Events: `SUBSCRIBE /topic/conversations.{id}` and `/user/queue/inbox`
- Errors: `SUBSCRIBE /user/queue/errors` → `{code, clientMessageId?}`

The full destination table, event envelope and frame contracts live in
[`docs/websocket-protocol.md`](websocket-protocol.md) — see its §4 Destinations
and §5 Event envelope. This file intentionally does not duplicate them.
