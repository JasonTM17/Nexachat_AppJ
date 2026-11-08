# NexaChat WebSocket / STOMP Protocol

> Realtime contract between the Android client and the Spring Boot backend;
> REST covers auth, search, attachments and resync. Version 1.0 — 2026-11-05.

## 1. Endpoint and transport

- Endpoint: `ws://host/ws` (WSS in production, `wss://host/ws`), standard
  HTTP upgrade; handshake allowed origins are restricted to `NEXACHAT_CORS_ALLOWED_ORIGINS`
  (empty by default; wildcard `*` is rejected at boot); no session cookie and no query-string token.
- Transport: raw WebSocket speaking STOMP 1.2 frames.
- Frame size: incoming raw WebSocket text messages are effectively limited to 16,640 bytes by Spring WebSocket 6.2.1 over Tomcat 10.1.34 (Tomcat's own default is 8,192 bytes). The assembled STOMP decoder limit is 65,536 bytes, but the lower transport limit may close an oversized raw frame before STOMP can return an ERROR frame.
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
Authorization:Bearer <access-token>
heart-beat:10000,10000

^@
```

Why a header and not `?token=` on the URL:

- URLs are written to proxy, ingress and application access logs and also
  land in history, analytics and screenshots — a long-lived token leak.
- The header stays inside the CONNECT frame, so reconnects never reuse a
  logged URL.

The server validates the JWT in the inbound channel interceptor before it
processes CONNECT, checking signature, expiry, and the revocation cutoff
(`users.tokens_valid_after`, Flyway V11). A token issued before the user's
revocation cutoff (after logout or password change) or an otherwise invalid/expired
token is rejected: the rejected CONNECT is dropped and the server does not
guarantee an `ERROR` frame; the client may see the CONNECT acknowledgement
timeout instead. Before every socket attempt, `RealtimeSession` checks the
stored access-token expiry and calls the shared, mutex-protected
`TokenRefresher.refreshIfNeeded()` when it has expired. The authenticated user
is bound to the session so `/user/**` destinations resolve.

**Header whitespace quirk:** the interceptor reads the native
`Authorization` value without trimming it, then requires the value to start
with `Bearer `. Send the STOMP header as `Authorization:Bearer <access-token>`
with no space after the colon. A header serialized as
`Authorization: Bearer <access-token>` is rejected because that leading value
space remains. A rejected CONNECT has no guaranteed `ERROR` frame.

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
| C→S | `/app/conversations/{id}/delivered` | SEND | `{upToSeq}` (delivery watermark ack) |
| C→S | `/app/chat.delivery.ack` | SEND | `{conversationId,upToSeq}` (delivery ack alias) |
| C→S | `/app/chat.read.ack` | SEND | `{conversationId,upToSeq}` (read ack alias) |
| C→S | `/app/calls/{conversationId}/signal` | SEND | call signal JSON; see [`docs/calls.md`](calls.md) |
| S→C | `/topic/conversations.{id}` | SUBSCRIBE | event envelope (§5); authorized by membership |
| S→C | `/user/queue/inbox` | SUBSCRIBE | event envelope |
| S→C | `/user/queue/errors` | SUBSCRIBE | `{code,clientMessageId?}` |
| S→C | `/user/queue/presence` | SUBSCRIBE | `USER_ONLINE` / `USER_OFFLINE` envelope |
| S→C | `/user/queue/calls` | SUBSCRIBE | `CallEvent` JSON; see [`docs/calls.md`](calls.md) |

Notes:

- `{id}` is the conversation UUID; the topic uses a dot
  (`/topic/conversations.42…`), not a slash.
- All bodies are `application/json`. Binary media goes via REST and is
  referenced by `attachmentIds`; nothing binary goes over STOMP.
- `/user/queue/*` is a per-connection user queue from the CONNECT principal;
  it is not shared between devices.
- Supported user-queue subscriptions are exactly `/user/queue/inbox`,
  `/user/queue/errors`, `/user/queue/presence`, and `/user/queue/calls`; each
  resolves only to the authenticated user's own sessions.
- SEND is rate-limited per user: 30 `messages` / 10 s and 60 `typing` / 10 s,
  configured by `nexachat.rate-limit.stomp.*`. An over-budget frame is dropped
  and reported on `/user/queue/errors` as
  `{"code":"RATE_LIMITED","clientMessageId":"<uuid>"}` (typing omits the id);
  the session is not closed.
- `content` is capped at 10,000 characters (`SendMessageRequest` `@Size` +
  `MessageService.MAX_CONTENT_LENGTH`); a longer body is `VALIDATION_FAILED`
  before any insert.

## 5. Event envelope

Conversation and presence events use the following envelope (call signaling
uses a separate `CallEvent` payload on `/user/queue/calls`; see
[`docs/calls.md`](calls.md)):

```json
{"eventType": "MESSAGE_CREATED", "conversationId": "42…", "serverTs": "2026-11-04T10:31:05.123Z", "payload": {}}
```

| eventType | payload summary |
|---|---|
| `MESSAGE_CREATED` | Full `MessageResponse` payload (including `id`, `conversationId`, `seq`, `senderId`, `type`, content/tombstone fields, and timestamps) |
| `MESSAGE_UPDATED` | full `MessageResponse` with edited `content`/`editedAt` |
| `MESSAGE_DELETED` | `{messageId, deletedAt}`; deleted content is never rebroadcast |
| `MESSAGE_DELIVERED` | `{userId, upToSeq}`; conversation id is in the envelope; indicates member has received up to `upToSeq` (renders 2 gray ticks on sender) |
| `MESSAGE_READ` | `{userId, upToSeq}`; conversation id is in the envelope; indicates member has read up to `upToSeq` (renders 2 blue ticks on sender) |
| `REACTION_UPDATED` | `{messageId, reactions:[{emoji, count, userIds}]}`; complete post-toggle reaction state for that message |
| `PIN_UPDATED` | `{messageId, pinned}`; conversation id is in the envelope |
| `USER_TYPING` | `{userId}`; conversation id is in the envelope and the Android client expires the indicator after a local 5 s TTL |
| `CONVERSATION_UPDATED` | `{conversationId, title, avatarUrl, change, members[]}` state snapshot; also refreshes inbox snippets when the last message is edited or deleted |
| `USER_ONLINE` | `{userId, online:true, lastSeenAt:null}`; `conversationId` is null |
| `USER_OFFLINE` | `{userId, online:false, lastSeenAt}`; `conversationId` is null |

Presence is counted per authenticated STOMP session in memory. Only a user's
DIRECT-conversation peers receive that user's state, and only while the subject
has `show_presence=true`; group members do not receive presence. If an online
user hides their own presence, peers receive `USER_OFFLINE` with
`lastSeenAt:null`; showing it again while online sends `USER_ONLINE`. Hidden
users are omitted from peer inboxes, and disconnecting while hidden sends no
duplicate event. This setting is one-way: it does not hide peers' presence from
that user. The last disconnect persists
`users.last_seen_at`; offline delivery is delayed up to five seconds, so a
reconnect in that window cancels the transient offline event. This registry
and Spring `SimpleBroker` are single-node only; multiple backend instances
would need shared presence state and a broker relay.

Malformed STOMP payload conversion or bean-validation failures are sent to
`/user/queue/errors` as `{"code":"VALIDATION_FAILED"}`. Unexpected handler
exceptions use `{"code":"INTERNAL_ERROR"}`. These error responses omit
`clientMessageId`, because an invalid body may not have been parsed far enough
to read it. This behavior is implemented by `WsMessageController`'s generic
messaging exception handler.

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

**Two-tick receipts and read watermarks.** Reception is acknowledged via
`/app/conversations/{id}/delivered` (or `/app/chat.delivery.ack`), advancing
`conversation_members.last_delivered_seq` and broadcasting `MESSAGE_DELIVERED`.
Reading is acknowledged via `/app/conversations/{id}/read` (or `/app/chat.read.ack`),
advancing `last_read_seq` and broadcasting `MESSAGE_READ`. Watermarks are
monotonic and cannot exceed the conversation's latest `seq`. In 1-1 chat,
`messageSeq <= peerDeliveredSeq` renders 2 gray ticks (delivered);
`messageSeq <= peerReadSeq` renders 2 blue ticks (seen/read) (`ChatBubbleRules.java`).

**Resync and delta sync after reconnect.** The client can catch up either by
single conversation via `GET /api/v1/conversations/{id}/messages?afterSeq=<lastSeq>`
or holistically across all conversations via `POST /api/v1/sync` (`SyncEngine.java`),
which returns new messages, edits, tombstones, and reaction deltas in one bundle.

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
   `/topic/conversations.{id}`, SENDs to `/app/conversations/{id}/…`, and
   call signaling for a conversation are checked against its members. The
   interceptor drops denied frames; clients must not rely on receiving an
   `ERROR` frame for a non-member denial. The check runs in a channel
   interceptor, not only in the UI.

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
Authorization:Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMTEx...
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
