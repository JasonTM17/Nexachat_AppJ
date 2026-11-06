# Voice calls

## Design note

Calls use the existing authenticated STOMP connection and the single-node
SimpleBroker. An in-memory registry owns each call's transitions and per-user
busy reservation; PostgreSQL stores the call history through the additive
`V5__calls.sql` migration. A ringing invite expires after 45 seconds.
Disconnecting a participant's last STOMP session ends that participant's call.
The broker's 10 s STOMP heartbeat keeps an idle ringing caller's socket alive,
and the ring timer re-arms itself if it fires a moment before the deadline.

Each terminal call writes one `SYSTEM` conversation message through the server's
existing system-message path. Its `content` is the stable, non-localized value
`CALL|<END_REASON>|<durationSeconds>` (for example, `CALL|COMPLETED|18` or
`CALL|MISSED|0`). Android renders that value as a localized call-log line; the
server does not store localized copy. The winning terminal transition owns the
history and message write, so concurrent terminal frames cannot create extra
system lines.

## Lifecycle

```text
Caller                 Server                         Callee
  | SEND INVITE           |                              |
  |---------------------->| reserve both users; persist  |
  |                       |-- CALL_INVITE -------------->|
  |                       |                              |
  |                       |<------------- SEND ACCEPT ---|
  |<-- CALL_ACCEPTED -----|---- CALL_ACCEPTED ---------->|
  |<================ SDP / ICE relay ===================>|
  | SEND END              |                              |
  |---------------------->| persist terminal row + line  |
  |<-- CALL_ENDED --------|--------- CALL_ENDED -------->|
```

The server authorizes the conversation and caller before reserving a call. It
authorizes the call ID and participant again for every control or media frame.
The callee can accept or reject while ringing; only the caller can cancel a
ringing call. Either participant can end an active call. SDP and ICE are
relayed verbatim only after acceptance.

| State | Accepted client frame | Result |
|---|---|---|
| No call | `INVITE` | Create `RINGING`, reserve caller and callee, notify callee |
| `RINGING` | Callee `ACCEPT` | `ACTIVE`, notify both participants |
| `RINGING` | Callee `REJECT` | `ENDED(REJECTED)` |
| `RINGING` | Caller `CANCEL` | `ENDED(CANCELLED)` |
| `RINGING` | 45-second timeout | `ENDED(MISSED)`, emit `CALL_TIMEOUT` |
| `RINGING` | `SDP`, `ICE`, or `END` | Reject as `CALL_STATE_INVALID` |
| `ACTIVE` | Either participant `SDP` or `ICE` | Relay to the other participant |
| `ACTIVE` | Either participant `END` | `ENDED(COMPLETED)` |
| `ACTIVE` | Either participant's last STOMP session closes | `ENDED(FAILED)` |
| `ENDED` or unknown ID | Any frame | Reject as `CALL_NOT_FOUND` |

A second invite to a callee already in another call creates a terminal `BUSY`
record, sends `CALL_BUSY` only to the caller, and does not notify the callee.
A caller already in a call cannot start another call. Each transition and
per-user reservation is guarded atomically by the in-memory registry.

## STOMP frames

Client frames are JSON sent to
`/app/calls/{conversationId}/signal`:

```json
{
  "type": "INVITE|ACCEPT|REJECT|CANCEL|END|SDP|ICE",
  "callId": "<uuid>",
  "sdp": {"type": "offer|answer", "description": "..."},
  "candidate": {"sdpMid": "audio", "sdpMLineIndex": 0, "candidate": "..."}
}
```

Control frames carry only `type` and `callId`. `SDP` carries `sdp` only;
`ICE` carries `candidate` only. The caller generates the UUID on `INVITE` and
reuses it for the rest of the call.

Server events are delivered to the authenticated participant's own
`/user/queue/calls` subscription:

```json
{
  "eventType": "CALL_INVITE|CALL_ACCEPTED|CALL_REJECTED|CALL_CANCELLED|CALL_ENDED|CALL_BUSY|CALL_TIMEOUT|CALL_SDP|CALL_ICE",
  "callId": "<uuid>",
  "conversationId": "<uuid>",
  "fromUserId": "<uuid>",
  "serverTs": "<ISO-8601 instant>",
  "payload": {
    "type": "INVITE|ACCEPT|REJECT|CANCEL|END|SDP|ICE",
    "callId": "<uuid>",
    "sdp": null,
    "candidate": null,
    "reason": "COMPLETED|REJECTED|CANCELLED|MISSED|BUSY|FAILED",
    "durationSeconds": 0
  }
}
```

Fields that do not apply to an event are null/omitted according to the server's
Jackson configuration. End reasons and duration appear on terminal events.
Malformed frames and call failures use the existing
`/user/queue/errors` envelope, for example `{"code":"CALL_NOT_FOUND"}`.
Errors include `VALIDATION_FAILED`, `NOT_MEMBER`, `RATE_LIMITED`,
`CALL_NOT_FOUND`, and `CALL_STATE_INVALID`. A non-member's call SEND is denied
by the STOMP authorization interceptor before it reaches the controller.

## REST API and persistence

- `GET /api/v1/calls/ice-config` returns
  `{"iceServers":[{"urls":["stun:stun.l.google.com:19302"]}],"ttlSeconds":3600}`
  by default. The route requires authentication.
- `GET /api/v1/conversations/{id}/calls?limit=20&cursor=` returns
  `{items:[...],nextCursor}` for a conversation member. Each item includes
  `callId`, `callerId`, `calleeId`, `state`, `endReason`, `startedAt`,
  `answeredAt`, `endedAt`, and `durationSeconds`. `limit` is 1–100; the opaque
  URL-safe cursor pages by `startedAt` and `callId`.
- `call_records` stores one row per generated call ID. Terminal rows and the
  call-log `SYSTEM` message are committed in the same transaction.

## Limits and configuration

| Setting | Default | Behavior |
|---|---|---|
| `nexachat.calls.ring-timeout-ms` | `45000` | Ring duration before `MISSED` |
| `nexachat.calls.ice-servers` | `stun:stun.l.google.com:19302` | Comma-separated ICE URLs, returned as one ICE server |
| INVITE rate | 20 per user per minute | Uses the existing `RateLimitService` |
| SDP and ICE rate | 300 combined per user per minute | Uses the existing `RateLimitService` |
| SDP description | 16 KB of UTF-8 | Larger descriptions return `VALIDATION_FAILED` |
| ICE candidate | 1 KB of UTF-8 | Larger candidates return `VALIDATION_FAILED` |

Only DIRECT conversations with exactly two members can call. Audio media is
negotiated by the Android client; signaling does not carry audio bytes.

## Limitations

- Call state and STOMP delivery use the single-node in-memory registry and
  SimpleBroker. Multiple server instances need shared call coordination and a
  broker relay.
- The default ICE configuration has public STUN only and no TURN. Strict NAT
  or mobile networks may not establish media; a shared LAN/Wi-Fi path is the
  expected demo environment.
- Calls are audio-only. Group calls are out of scope.
- An offline callee receives no push notification. The ring expires as a
  missed call and leaves a history row and localized chat line.
- If Android notification permission is denied while NexaChat is backgrounded,
  a direct activity launch may be blocked by Android; the incoming call may
  expire as missed.
- Presence privacy settings do not affect calling. A user who hides presence
  can still place and receive calls.
- The audio path has not been verified between two real devices: the live E2E
  and on-device verification exercised signaling, SDP/ICE relay, mute/speaker
  routing and teardown, but not an audio stream between two WebRTC endpoints.
- The inbox preview renders a `CALL|...` line as the incoming phrasing for both
  directions; only the chat bubble knows the viewer's direction.

## Verification

- `python tools/e2e/calls/call_e2e.py` passes against a live server: invite →
  accept → SDP/ICE relay → end, busy, reject, cancel, and the production 45 s
  ring timeout. The run asserts the timeout is not early and that the caller
  received server heartbeats while ringing.
- Android call screens are captured on an API 35 emulator in
  `docs/design/screens/s6-*` (outgoing/incoming, connected, mute, speaker,
  landscape, font scale, permission paths and localized call-log lines).
