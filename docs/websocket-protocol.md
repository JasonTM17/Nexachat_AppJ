# NexaChat WebSocket / STOMP Protocol

> Realtime contract between the Android client and the Spring Boot backend;
> REST covers auth, search, attachments and resync. Version 1.0 — 2026-11-04.

## 1. Endpoint and transport

- Endpoint: `ws://host/ws` (WSS in production, `wss://host/ws`), standard
  HTTP upgrade; no session cookie and no query-string token.
- Transport: raw WebSocket speaking STOMP 1.2 frames.
- **No SockJS.** The only client is the native Android app (OkHttp
  WebSocket + STOMP frames). SockJS exists to give browsers a fallback when
  WebSocket is blocked; with no browser client there is no fallback to
  maintain, so `/ws/websocket` is not exposed and both sides stay simpler.

## 2. Authentication — CONNECT frame

The access token is sent once, in the `Authorization` header of the CONNECT
frame:

```text
CONNECT
accept-version:1.2
authorization:Bearer <access-token>
heart-beat:10000,10000

^@
```

Why a header and not `?token=` on the URL:

- URLs are written to proxy, ingress and application access logs and also
  land in history, analytics and screenshots — a long-lived token leak.
- The header stays inside the CONNECT frame, so reconnects never reuse a
  logged URL.

The server validates the JWT in the inbound channel interceptor before it
processes CONNECT. A rejected CONNECT is dropped and the server does not
guarantee an `ERROR` frame; the client may see the CONNECT acknowledgement
timeout instead. Before every socket attempt, `RealtimeSession` checks the
stored access-token expiry and calls the shared, mutex-protected
`TokenRefresher.refreshIfNeeded()` when it has expired. The authenticated user
is bound to the session so `/user/**` destinations resolve.

## 3. Heartbeat

`heart-beat:10000,10000` means the client sends a STOMP heartbeat every 10 s
and expects one from the server every 10 s.

- Either side closes the socket when heartbeats stop; a dead connection
  (device asleep, NAT timeout, silent loss) is detected in ~30 s (three missed
  10 s intervals — `StompClient.DEAD_AFTER_MS = 30_000`) — fast enough for the
  demo and unread cleanup.
- Heartbeats are transport liveness only; they carry no app data.

## 4. Destinations

| Direction | Destination | Frame | Body |
|---|---|---|---|
| C→S | `/app/conversations/{id}/messages` | SEND | `{clientMessageId,type,content,replyToId?,attachmentIds[]}` |
| C→S | `/app/conversations/{id}/typing` | SEND | `{}` |
| C→S | `/app/conversations/{id}/read` | SEND | `{upToSeq}` |
| S→C | `/topic/conversations.{id}` | SUBSCRIBE | event envelope (§5); authorized by membership |
| S→C | `/user/queue/inbox` | SUBSCRIBE | event envelope |
| S→C | `/user/queue/errors` | SUBSCRIBE | `{code,clientMessageId?}` |

Notes:

- `{id}` is the conversation UUID; the topic uses a dot
  (`/topic/conversations.42…`), not a slash.
- All bodies are `application/json`. Binary media goes via REST and is
  referenced by `attachmentIds`; nothing binary goes over STOMP.
- `/user/queue/*` is a per-connection user queue from the CONNECT principal;
  it is not shared between devices.
- SEND is rate-limited per user: 30 `messages` / 10 s and 60 `typing` / 10 s,
  configured by `nexachat.rate-limit.stomp.*`. An over-budget frame is dropped
  and reported on `/user/queue/errors` as
  `{"code":"RATE_LIMITED","clientMessageId":"<uuid>"}` (typing omits the id);
  the session is not closed.
- `content` is capped at 10,000 characters (`SendMessageRequest` `@Size` +
  `MessageService.MAX_CONTENT_LENGTH`); a longer body is `VALIDATION_FAILED`
  before any insert.

## 5. Event envelope

Every server event uses the same envelope:

```json
{"eventType": "MESSAGE_CREATED", "conversationId": "42…", "serverTs": "2026-11-04T10:31:05.123Z", "payload": {}}
```

| eventType | payload summary |
|---|---|
| `MESSAGE_CREATED` | message: `seq`, `clientMessageId`, `senderId`, `type`, `content`, `replyToId?`, `createdAt` |
| `MESSAGE_UPDATED` | message id + edited `content`/`editedAt` |
| `MESSAGE_DELETED` | message id + `deletedAt` |
| `MESSAGE_READ` | conversation id, reader id, `upToSeq` |
| `REACTION_UPDATED` | message id, emoji, actor, added/removed |
| `PIN_UPDATED` | conversation id, pinned message id or null |
| `USER_TYPING` | conversation id, sender id, `typing` boolean |
| `CONVERSATION_UPDATED` | changed fields (title, avatar, members) |

`serverTs` is server time; clients use it for display and, with
`payload.seq`, as the resync watermark.

## 6. Delivery guarantees

**Persist before broadcast.** The server commits the message row and updates
`conversations.last_message_id` / `last_message_seq` in one transaction, then
publishes the event. A received `MESSAGE_CREATED` therefore guarantees the
row is already queryable via REST.

**Resync after reconnect.** The client fetches
`GET /api/v1/conversations/{id}/messages?afterSeq=<lastSeq>` and then relies on
the live stream, closing the gap for events missed while offline.

**Idempotency.** `clientMessageId` is a sender-generated UUID, unique per
`(sender_id, client_message_id)`. A retried SEND hits `ON CONFLICT DO NOTHING`
and returns the existing row, so no duplicate is created — and the server
**re-broadcasts** `MESSAGE_CREATED` for the deduplicated row, because that
frame is the retrying sender's SENT acknowledgment. Receivers deduplicate by
message id, so the re-broadcast is harmless (`MessageService.java:121-149`).
The sender flips its optimistic Room row from `SENDING` to `SENT` by matching
`clientMessageId`. Reusing the key in a *different* conversation is
`VALIDATION_FAILED`, not a silent cross-room dedup.

**Ordering.** Render and diff by monotonic `seq` (BIGSERIAL), never by arrival
time or `serverTs`; a delayed lower-`seq` event still inserts in position.

**Read watermark.** `upToSeq` must be non-negative and cannot exceed the
conversation's latest `seq`; the persisted watermark is monotonic (never moves
backwards) and `MESSAGE_READ` broadcasts the *persisted* value, so a replayed
frame is truthful.

**Reconnect backoff.** On socket loss the client retries with delays
`1s, 2s, 4s, 8s, 16s` plus ±20% jitter, at most 5 attempts
(`ReconnectPolicy.MAX_ATTEMPTS`). After the budget is spent the loop parks and
REST remains the send path; it is re-armed by a validated network regain, app
foreground, a user action (`ensureConnected`), and — while the app is
foregrounded with a valid session — a bounded backoff timer (5 s doubling to
60 s max). Backgrounded, only the explicit triggers apply. A successful
CONNECT resets the counter.

## 7. Authorization (two layers)

1. **CONNECT = authentication.** The JWT proves who the user is; a rejected
   token means no session and no `CONNECTED` acknowledgement. Clients should
   refresh from the stored refresh token after local access-token expiry.
2. **SUBSCRIBE / SEND = authorization.** Every subscription to
   `/topic/conversations.{id}` and every SEND to `/app/conversations/{id}/…`
   is checked against `conversation_members` for the authenticated user. A
   non-member gets an `ERROR` frame (`code=FORBIDDEN`) and the subscription is
   rejected. The check runs in a channel interceptor, not only in the UI.

`typing` and `read` frames are membership-checked too.

## 8. Broker topology

The server uses Spring's `SimpleBroker`: one in-JVM pub/sub broker, no
external dependency — enough for the demo and a single-instance deployment,
but not horizontally scalable (events reach only sessions on the same JVM).
When multi-instance is needed, replace it with a broker relay
(RabbitMQ/ActiveMQ); the frame contract above does not change.

## 9. Example frames

### 9.1 CONNECT success

```text
CONNECT
accept-version:1.2
authorization:Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMTEx...
heart-beat:10000,10000

^@
```

```text
CONNECTED
version:1.2
heart-beat:10000,10000
user-name:11111111-1111-4111-8111-111111111111

^@
```

### 9.2 SEND → broadcast round trip

```text
SEND
destination:/app/conversations/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/messages
content-type:application/json

{"clientMessageId":"f0000000-0000-4000-8000-000000000007","type":"TEXT","content":"Bình ơi, họp lúc 3 giờ chiều đúng không?","replyToId":null,"attachmentIds":[]}
^@
```

```text
MESSAGE
destination:/topic/conversations.aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa
content-type:application/json
subscription:sub-1

{"eventType":"MESSAGE_CREATED","conversationId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","serverTs":"2026-11-04T10:31:05.123Z","payload":{"id":"00000000-0000-4000-8000-000000000007","seq":7,"senderId":"11111111-1111-4111-8111-111111111111","clientMessageId":"f0000000-0000-4000-8000-000000000007","type":"TEXT","content":"Bình ơi, họp lúc 3 giờ chiều đúng không?","replyToId":null,"createdAt":"2026-11-04T10:31:05.118Z"}}
^@
```

The sender matches `clientMessageId` → `SENT`; the recipient renders at `payload.seq`.
