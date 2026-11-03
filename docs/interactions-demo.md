# P9 Message Interactions — Demo & Viva Playbook

> **Feature:** Reply/Quote + Emoji Reaction + Pin (the R4 "creative" differentiator).
> **Plan:** `plans/261103-2322-nexachat-master-design/phase-11-creative.md` (file is
> named `phase-11-creative.md`; its title says "Phase 10" and the roadmap calls it
> **P9** — the naming is inconsistent in the plan set).
> **Repo snapshot:** `nexachat` @ `1c133b6` (branch `main`), with P9 implementation
> in progress in the working tree. **The repo is being modified concurrently**;
> this document reflects the state at the time of writing (2026-11-04).

## 0. Read this first — evidence status

At the snapshot the feature is **partially implemented**: the server side is
complete (migration, entities, repositories, services, controllers, WS
broadcast), and the Android data layer is complete (DTOs, Retrofit API, Room,
repository, typed WS events, ViewModel state). The **Compose UI is not yet
wired** and the **TC-INT tests are not yet written**.

| P9 piece | Status | Evidence |
|---|---|---|
| `reply_to_id` column | **SHIPPED** | `server/src/main/resources/db/migration/V1__init.sql:96` |
| `Message.replyTo` entity mapping | **SHIPPED** | `server/src/main/java/com/nexachat/message/Message.java:85-88` |
| `SendMessageRequest.replyToId` | **SHIPPED** | `server/.../message/dto/SendMessageRequest.java:26` |
| Reply insert + same-conversation validation | **SHIPPED** | `MessageService.java:107,289-304`; `MessageRepository.java:29-41` |
| `MessageResponse.replyTo` embedded preview + `reactions[]` | **SHIPPED** | `MessageResponse.java:19-35`; `ReplyRefResponse.java`; `ReactionView.java`; `MessageService.java:437-502` |
| `message_reactions` table + composite PK | **SHIPPED** | `server/src/main/resources/db/migration/V3__interactions.sql:15-24` |
| `pinned_messages` table + composite PK | **SHIPPED** | `V3__interactions.sql:26-36` |
| `MessageReaction` / `PinnedMessage` entities + repositories | **SHIPPED** | `MessageReaction.java`, `MessageReactionRepository.java`, `PinnedMessage.java`, `PinnedMessageRepository.java` |
| `ReactionService` toggle | **SHIPPED** | `ReactionService.java:71-92` |
| `PinService` list/pin/unpin + policy + ≤3 | **SHIPPED** | `PinService.java:86-204` |
| REST controllers | **SHIPPED** | `ReactionController.java:38-45`; `PinController.java:45-68` |
| `REACTION_UPDATED` / `PIN_UPDATED` broadcast | **SHIPPED** | `ReactionUpdatedEvent.java`; `PinUpdatedEvent.java`; `InteractionBroadcastListener.java:35-47` |
| Event vocabulary | **SHIPPED** | `server/.../ws/WsEventType.java:16-17` |
| Error codes `NOT_MEMBER` 403, `NOT_ADMIN` 403, `MESSAGE_NOT_FOUND` 404, `PIN_LIMIT` 409 | **SHIPPED** | `server/.../common/error/ApiErrorCode.java:25-27,30` |
| Android DTOs + Retrofit methods | **SHIPPED** | `android/.../core/network/dto/InteractionDtos.kt`; `core/network/MessageApi.kt:64-98` |
| Android Room entities/DAOs + DB migration | **SHIPPED** | `core/database/Entities.kt:100-126`; `Daos.kt:115-195`; `AppDatabase.kt:30-31,40-41,63-64` |
| Android typed WS events | **SHIPPED** | `core/network/stomp/RealtimeSession.kt:63-79,322-339` |
| Android repository: toggle/pin/unpin/optimistic reconcile | **SHIPPED** | `data/MessageRepository.kt:446-579` |
| Android ViewModel state + actions | **SHIPPED** | `feature/chat/ChatViewModel.kt:57-89,133-136,200-255` |
| Android Compose UI (ReactionBar / ReplyPreview / PinnedBanner) | **PLANNED (stub only)** | `feature/interactions/README.md:3-7`; `ChatScreen.kt` renders none of them yet |
| Tests TC-INT-01..04 | **PLANNED** | `phase-11-creative.md:24`; no `ReactionServiceTest` / `PinServiceTest` / `InteractionFlowIT` in `server/src/test` |

**Migration number:** `phase-11-creative.md:13` says `V2__interactions.sql`, but
`V2` was already `V2__attachments.sql`; the implementation correctly landed as
**`V3__interactions.sql`** (`V3__interactions.sql:1`;
`reports/execution-ledger.md:122`).

---

## 1. Feature contract at a glance

Three interactions, all riding the existing message pipeline:

1. **Reply/Quote** — `replyToId` on send; the reply is a normal message whose
   `reply_to_id` points at the quoted message (`V1__init.sql:96`). The target
   must exist **and** belong to the same conversation
   (`MessageService.java:289-304`). The response/history now embeds a
   `replyTo` preview (`ReplyRefResponse`) and a per-message `reactions[]`
   summary (`MessageResponse.java:19-35`).
2. **Emoji reaction** — toggle keyed by `(message, user, emoji)`; one row per
   user per emoji (`V3__interactions.sql:15-24`). Toggle = `DELETE` then, if
   nothing was removed, `INSERT … ON CONFLICT DO NOTHING`
   (`ReactionService.java:82-86`; `MessageReactionRepository.java:23-46`).
3. **Pin** — up to **3** pins per conversation (`PinService.java:54,136-139`);
   DIRECT = any member, GROUP = ADMIN only (`PinService.java:187-193`).

Realtime surface: `REACTION_UPDATED` and `PIN_UPDATED` are broadcast on
`/topic/conversations.{id}` (`InteractionBroadcastListener.java:35-47`;
`WsEventType.java:16-17`). The events are server→client only; the write path
is REST (`ReactionController.java:38-45`; `PinController.java:45-68`), so **no
new STOMP SEND destination is required** — the interceptor regex only routes
`messages|typing|read` (`StompAuthChannelInterceptor.java:62-64`).

---

## 2. Demo script — 2 devices (target flow)

**Cast (from `demo/seed.sql`):**

| Phone | Account | Role in group `Nhom Do An` (`bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb`) |
|---|---|---|
| A | `+84900000001` **An** | `ADMIN` (`seed.sql:75`) |
| B | `+84900000002` **Binh** | `MEMBER` (`seed.sql:76`) |

Direct chat An↔Binh: `aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa` (`seed.sql:58-61`).
Seed password is `password123` (`seed.sql:9-12`); note `docs/demo-runbook.md:11`
still says `demo@1234` — **doc drift**, use `demo/seed.sql`.

**Status key for steps:** **[S]** = shipped behaviour (verified in repo), **[C]** =
contract/implementation behaviour, **[P]** = planned UI gesture (no composable
in repo yet). Because the server endpoints are live, every step can also be
driven with `curl` if the Compose UI is not wired in time.

### 2.0 Preflight (30 min before)

- [ ] `docker compose up -d`; `docker compose ps` healthy; apply `V3` (Flyway
      runs it automatically on boot).
- [ ] `demo/seed.sql` applied (accounts + group + messages).
- [ ] Phone A logged in as An, Phone B as Binh; both on the same LAN.
- [ ] Server log tail open (`tail -f`) — the log lines are the demo's "proof".

### 2.1 Reply-to-message (DIRECT) — ~30s

1. **[P]** Phone B opens the direct chat with An.
2. **[P]** Swipe right on An's message **"Bình ơi, họp lúc 3 giờ chiều đúng
   không?"** (`seq=1`, id `00000000-0000-4000-8000-000000000001`, `seed.sql:87-89`).
3. **[P]** A `ReplyPreview` appears above the composer showing the quoted
   snippet (planned UI: `feature/interactions/README.md:5`).
4. **[P]** Type `Oke, 3 giờ phòng 2A nhé.` and tap **Send**.
5. **[S]** The client sends STOMP `SEND /app/conversations/aaaaaaaa-…/messages`
   with body
   `{"clientMessageId":"…","type":"TEXT","content":"Oke, 3 giờ phòng 2A nhé.","replyToId":"00000000-0000-4000-8000-000000000001","attachmentIds":[]}`
   (`docs/websocket-protocol.md:55`; `SendMessageRequest.java:26`;
   `ChatViewModel.kt:200-204`).
6. **[S]** Both phones receive `MESSAGE_CREATED` on
   `/topic/conversations.aaaaaaaa-…` with `payload.replyToId` and the embedded
   `payload.replyTo` preview (`MessageResponse.java:19-35`).
7. **[P]** Phone A renders the reply bubble with the quote above it.

> **Narrate:** "A reply is just a message with a validated foreign key. The
> server rejects a `replyToId` that does not exist or belongs to another
> conversation — `MessageService.resolveReplyTo` (`MessageService.java:289-304`)
> returns `MESSAGE_NOT_FOUND` / `VALIDATION_FAILED`."

### 2.2 Emoji reaction toggle on the partner's message — ~30s

1. **[P]** Phone A long-presses Binh's message **"Được, bao mình."** (`seq=6`,
   id `00000000-0000-4000-8000-000000000006`, `seed.sql:102-104`).
2. **[P]** The `ReactionBar` appears (`feature/interactions/README.md:4`);
   tap **👍**.
3. **[S]** `POST /api/v1/messages/00000000-0000-4000-8000-000000000006/reactions`
   with `{"emoji":"👍"}` → **204** (`ReactionController.java:38-45`).
4. **[S]** Server broadcasts `REACTION_UPDATED` on
   `/topic/conversations.aaaaaaaa-…` with payload
   `{"messageId":"…","reactions":[{"emoji":"👍","count":1,"userIds":["…An…"]}]}`
   (`ReactionUpdatedPayload.java:12`; `ReactionSlice.java:13`;
   `InteractionBroadcastListener.java:35-40`). Phone B shows the 👍 chip with
   count **1**.
5. **[P]** Tap **👍** again on Phone A.
6. **[S]** Same `POST` toggles the row off → **204**; `REACTION_UPDATED` with
   `reactions:[]` (the full post-toggle aggregate, replace-not-merge); the chip
   disappears on both phones (`ReactionService.java:82-89`).

> **Narrate:** "The same endpoint is a toggle. The composite primary key is what
> makes the toggle race-safe — see §4.1. The broadcast carries the message's
> complete reaction set, so clients replace their cache instead of merging —
> out-of-order frames cannot drift."

### 2.3 Pin a message in the group as ADMIN — ~30s

1. **[P]** Phone A opens the group **Nhom Do An**.
2. **[P]** Long-press Chi's message **"Em làm xong phần API rồi, đang viết
   slide."** (`seq=8`, id `00000000-0000-4000-8000-000000000008`, `seed.sql:109-111`).
3. **[P]** Tap **Ghim** (Pin).
4. **[S]** `POST /api/v1/conversations/bbbbbbbb-…/pins` with
   `{"messageId":"00000000-0000-4000-8000-000000000008"}` → **204**
   (`PinController.java:52-59`).
5. **[S]** Server broadcasts `PIN_UPDATED` on `/topic/conversations.bbbbbbbb-…`
   with payload `{"messageId":"…","pinned":true}` (`PinUpdatedPayload.java:10`;
   `InteractionBroadcastListener.java:42-47`). A `PinnedBanner` appears at the
   top of the chat on **both** phones.
6. **[P]** Tap the `PinnedBanner` → the list jumps to the pinned message
   (`feature/interactions/README.md:6`).
7. **[S]** *(Optional, proves the cap)* Pin two more messages → 3 pins OK; a
   4th `POST /pins` → **409 `PIN_LIMIT`** (`PinService.java:136-139`;
   `ApiErrorCode.java:30`). Show the error toast.

### 2.4 Non-admin denied — ~20s

1. **[P]** Phone B (Binh, `MEMBER`) long-presses a group message → taps **Ghim**.
2. **[S]** `POST /api/v1/conversations/bbbbbbbb-…/pins` → **403 `NOT_ADMIN`**
   (`PinService.java:187-193`; `ApiErrorCode.java:26`).
3. **[S]** **No `PIN_UPDATED` is broadcast** — Phone A sees nothing change.
4. **[P]** Phone B shows the error state (toast: *"Chỉ quản trị viên mới được
   ghim"*).

> **Narrate:** "Membership is checked first (a non-member gets `NOT_MEMBER`
> 403), then the group role (a member who is not an admin gets `NOT_ADMIN`
> 403) — `PinService.requirePinRole` (`PinService.java:187-193`). The UI hides
> the action, but the server is the authority."

### 2.5 Timing & fallbacks

- Total interactions segment: **~2 minutes** (the runbook allocates 45 s to
  step 7 — `docs/demo-runbook.md:30`; expand it or trim §2.3's optional cap
  demo).
- If the WS socket is down: the REST send path still works
  (`docs/api-contract.md:211`), and reactions/pins are REST anyway. On reconnect
  the client resyncs via REST (`MessageRepository.kt:348-356`).
- If the Compose UI is not ready: drive the same endpoints with `curl` against
  the LAN IP and show the `REACTION_UPDATED` / `PIN_UPDATED` frames in the
  server log — the contract is the proof.

---

## 3. API reference

### 3.1 REST endpoints

| Method | Path | Body | Success | Errors | Status |
|---|---|---|---|---|---|
| `POST` | `/api/v1/conversations/{conversationId}/messages` | `{clientMessageId, type, content?, replyToId?, attachmentIds?}` | `201` created / `200` dedup — `MessageResponse` | `400 VALIDATION_FAILED` · `401 UNAUTHORIZED` · `403 NOT_MEMBER` · `404 CONVERSATION_NOT_FOUND` · `404 MESSAGE_NOT_FOUND` (reply target) · `404 FILE_NOT_FOUND` | **SHIPPED** (`MessageController.java:60-70`) |
| `POST` | `/api/v1/messages/{messageId}/reactions` | `{emoji}` (non-blank, ≤16 code points) | `204` empty | `400 VALIDATION_FAILED` (blank/too long) · `401` · `403 NOT_MEMBER` · `404 MESSAGE_NOT_FOUND` | **SHIPPED** (`ReactionController.java:38-45`; `ReactionService.java:67-80,113-122`) |
| `GET` | `/api/v1/conversations/{conversationId}/pins` | — | `200` `PinnedMessageView[]` (oldest pin first) | `401` · `403 NOT_MEMBER` · `404 CONVERSATION_NOT_FOUND` | **SHIPPED** (`PinController.java:45-50`; `PinService.java:86-112`) |
| `POST` | `/api/v1/conversations/{conversationId}/pins` | `{messageId}` | `204` | `400 VALIDATION_FAILED` (missing id / cross-conversation) · `401` · `403 NOT_MEMBER` · `403 NOT_ADMIN` (GROUP non-admin) · `404 CONVERSATION_NOT_FOUND` · `404 MESSAGE_NOT_FOUND` · `409 PIN_LIMIT` | **SHIPPED** (`PinController.java:52-59`; `PinService.java:119-145,187-204`) |
| `DELETE` | `/api/v1/conversations/{conversationId}/pins/{messageId}` | — | `204` | same as `POST /pins` | **SHIPPED** (`PinController.java:61-68`; `PinService.java:152-166`) |

Notes:

- The reaction endpoint is a **toggle**: the same `POST` with the same
  `(user, emoji)` adds the row the first time and removes it the second
  (`ReactionService.java:82-86`). The 204 deliberately has no body; the
  post-toggle state arrives via `REACTION_UPDATED`.
- Pin authorization is **conversation-type dependent**: DIRECT = any member,
  GROUP = ADMIN only (`PinService.java:187-193`).
- Pins are **idempotent**: re-pinning an already-pinned message is a no-op, not
  a `PIN_LIMIT` hit — the ceiling only guards *new* pins
  (`PinService.java:135-142`).
- All errors use the standard envelope (`GlobalExceptionHandler.java:38-41`).

### 3.2 WebSocket events

| Event | Direction | Destination | Payload (concrete DTO) | Source |
|---|---|---|---|---|
| `REACTION_UPDATED` | S→C | `/topic/conversations.{id}` | `{messageId, reactions: [{emoji, count, userIds}]}` — full post-toggle aggregate, replace-not-merge | `ReactionUpdatedPayload.java:12`; `ReactionSlice.java:13`; `InteractionBroadcastListener.java:35-40` |
| `PIN_UPDATED` | S→C | `/topic/conversations.{id}` | `{messageId, pinned: true\|false}` | `PinUpdatedPayload.java:10`; `InteractionBroadcastListener.java:42-47` |

Both use the frozen envelope `{eventType, conversationId, serverTs, payload}`
(`EventEnvelope.java:16-23`; `docs/websocket-protocol.md:73-77`). Only members
can subscribe to the topic (`StompAuthChannelInterceptor.java:113-137`), so
interaction events are only delivered to conversation members. The listener is
**topic-only** — no `/user/queue/inbox` push — because a reaction or pin changes
no unread badge (`InteractionBroadcastListener.java:20-22`).

> **Contract note:** `docs/websocket-protocol.md:85-86` summarises the payloads
> as "message id, emoji, actor, added/removed" and "conversation id, pinned
> message id or null". The shipped DTOs are more precise (full aggregate for
> reactions; `{messageId, pinned}` for pins). The doc summary is the looser
> contract; the DTOs are the wire truth.

### 3.3 Error codes

| Code | HTTP | Where |
|---|---|---|
| `VALIDATION_FAILED` | 400 | blank/oversized emoji (`ReactionService.java:113-122`); missing/cross-conversation `messageId` (`PinService.java:127-129,196-204`) |
| `UNAUTHORIZED` | 401 | missing/invalid Bearer token (security layer) |
| `NOT_MEMBER` | 403 | caller not in the conversation (`ReactionService.java:78-80`; `PinService.java:181-185`) |
| `NOT_ADMIN` | 403 | GROUP pin by a non-admin (`PinService.java:187-193`) |
| `MESSAGE_NOT_FOUND` | 404 | reaction target or pin target missing (`ReactionService.java:74-76`; `PinService.java:196-204`) |
| `CONVERSATION_NOT_FOUND` | 404 | pin list/pin on a missing conversation (`PinService.java:88-90,175-179`) |
| `PIN_LIMIT` | 409 | 4th distinct pin (`PinService.java:136-139`) |

---

## 4. Design notes worth 1–2 viva answers

### 4.1 Why is the reaction key `(message_id, user_id, emoji)`?

**Answer.** The primary key encodes the exact business rule: *one user may
react once with a given emoji, but may use several different emojis*. The DDL
is `PRIMARY KEY (message_id, user_id, emoji)` (`V3__interactions.sql:15-24`),
which is also why the toggle is a **row insert/delete**, not a counter update:

- **No lost updates.** `UPDATE … SET count = count + 1` is a read-modify-write;
  two concurrent taps can lose one. The shipped toggle is `DELETE` then, if
  nothing was removed, `INSERT … ON CONFLICT DO NOTHING`
  (`ReactionService.java:82-86`; `MessageReactionRepository.java:23-46`) — the
  PK resolves any interleaving.
- **Exact counts.** `count = COUNT(*)` per `(message, emoji)`; no counter column
  to drift or go negative. The broadcast aggregate is computed in-transaction
  from the same rows (`ReactionService.java:98-106`).
- **Multi-emoji users.** `(message_id, user_id)` would cap a user at one emoji,
  contradicting FR-H2 "1 user nhiều emoji" (`plan.md:142`).
- **Race-safe toggle.** Two devices tapping 👍 at the same instant produce one
  row; both clients converge on the same aggregate after `REACTION_UPDATED`.

**Viva line:** "The composite PK is the concurrency arbiter — it replaces a
check-then-insert race with a single atomic statement, the same pattern as
`messages_sender_client_unique` (`V1__init.sql:100`)."

### 4.2 Why are pins capped at 3, and how is the cap made race-safe?

**Answer.** A pin is a *highlight*, not storage. Past ~3 items the banner stops
being scannable and becomes a second chat list; the cap keeps the feature
legible and the demo deterministic. The limit is enforced **server-side**
(`409 PIN_LIMIT`, `ApiErrorCode.java:30`; `PinService.java:54,136-139`) because
a client-side cap is not authorization.

The race-safety detail is the interesting part: `pin()` and `unpin()` both take
`SELECT … FOR UPDATE` on the **conversation row**
(`ConversationRepository.java:24-26`; `PinService.java:175-179`), so the
exists/count/insert sequence is serialized per conversation. Two racing pins
cannot both pass a "count < 3" check. The composite PK
`(conversation_id, message_id)` additionally makes a repeat pin a no-op at the
DB level (`PinnedMessageRepository.java:20-28`).

**Viva line:** "The cap is a domain rule, so it is enforced in the service and
reported as a stable error code (`PIN_LIMIT`, 409). The `FOR UPDATE` lock on the
parent conversation row is what makes the check-then-insert safe — the same
discipline as `direct_key` for DIRECT creation."

### 4.3 Why does persist-before-broadcast matter for reactions too?

**Answer.** The reaction/pin paths publish a Spring event inside the transaction
and fan out `AFTER_COMMIT` (`ReactionService.java:33-35,88-89`;
`PinService.java:42-46,143-144`; `InteractionBroadcastListener.java:35-47`) —
the same pattern as `MessageService`/`MessageBroadcastListener`
(`MessageService.java:45-47`; `MessageBroadcastListener.java:16-23,43-51`).

- A received `REACTION_UPDATED` / `PIN_UPDATED` therefore **guarantees the row
  is already committed and queryable via REST**. A reconnecting client can
  resync purely with REST — no replay channel.
- If the broadcast happened before commit and the transaction rolled back,
  clients would render a reaction that does not exist; on the next resync it
  would vanish — the event was a lie.
- The payload is a **full-state replace**, not a delta, so out-of-order or
  replayed frames are idempotent: `ReactionUpdatedPayload` carries the whole
  `reactions[]` set and clients replace their cache
  (`ReactionUpdatedPayload.java:7-12`; `MessageRepository.kt:446-454`).
- Like `markRead`, the pin event is published even on a no-op — a replayed frame
  states the truth and receivers converge (`PinService.java:42-46,164-165`).

**Viva line:** "Persist-before-broadcast is what makes the event stream
trustworthy without a replay mechanism — the same reason `MESSAGE_CREATED` is
published after commit."

### 4.4 Why can any member pin in DIRECT but only an ADMIN in GROUP?

**Answer.** DIRECT conversations have **no ADMIN role**: both members are
`MEMBER` (`ConversationMember.java:26-28`; `V1__init.sql:77`). Requiring
`ADMIN` in a 1-1 chat would make pinning impossible — there is no admin to
satisfy the check. GROUP conversations have a real `ADMIN`
(`ConversationMember.java:39-43`), and pins are admin-only so there is one
authoritative curator and no "pin war" between members. This mirrors the
existing group mutation rules: add/remove/rename are all ADMIN-only
(`GroupService.java:148-151,250-254,304-308`).

The rule is enforced in the **service**, not only the UI: membership first
(`403 NOT_MEMBER`), then role for GROUP (`403 NOT_ADMIN`) —
`PinService.requirePinRole` (`PinService.java:187-193`). The UI hides the
action for non-admins, but the server remains the authority — the same
belt-and-braces principle as the STOMP interceptor
(`StompAuthChannelInterceptor.java:113-162`) under the service-level checks.

**Viva line:** "Authorization follows the conversation's role model: DIRECT is
peer-to-peer (no admin exists), GROUP has an admin by design — so the pin rule
is 'member in DIRECT, admin in GROUP', and it is checked server-side."

### 4.5 Bonus: why does the reaction payload carry `userIds`, and why is the emoji limited to 16 code points?

- `ReactionSlice` carries the full reactor id list so **every** receiving client
  computes its own `byMe` from one topic frame — the frame has one shape for
  the whole audience (`ReactionSlice.java:7-13`). The REST summary
  (`ReactionView`) is viewer-relative and carries only `byMe`
  (`ReactionView.java:5-8`).
- The emoji is bounded to 16 **code points**, not Java `char`s, so multi-code-unit
  emoji (flags, ZWJ sequences) are measured the way PostgreSQL measures
  `VARCHAR(16)` (`ReactionService.java:108-122`;
  `V3__interactions.sql:18`).

---

## 5. Viva Q&A — 8 interactions questions

**Q1. Why is the reaction primary key `(message_id, user_id, emoji)` and not a
counter column or `(message_id, user_id)`?**
A. The PK encodes the rule "one row per user per emoji": a user can use several
emojis but not the same emoji twice (`V3__interactions.sql:15-24`; `plan.md:142`).
A counter would need `count = count + 1` (lost update under concurrency) and
could drift; a `(message, user)` PK would cap a user at one emoji. The PK also
makes the toggle atomic — `DELETE` then `INSERT … ON CONFLICT DO NOTHING`
(`ReactionService.java:82-86`) — so concurrent taps converge.

**Q2. Two taps on 👍 — how does the server avoid double-counting or a lost
update?**
A. The toggle is a row insert/delete guarded by the composite PK, not a
read-modify-write. The first tap inserts `(message, user, 👍)`; the second
deletes it. A concurrent double-tap produces one row because the PK rejects the
duplicate. `COUNT(*)` is therefore always exact, and the broadcast aggregate is
computed from those rows in the same transaction
(`ReactionService.java:98-106`).

**Q3. Why does a reaction need persist-before-broadcast? It is just a counter.**
A. Because the event must be truthful. The row is committed first, then a
`ReactionUpdatedEvent` is published and fanned out `AFTER_COMMIT`
(`ReactionService.java:33-35,88-89`; `InteractionBroadcastListener.java:35-40`).
A received `REACTION_UPDATED` then guarantees the row is queryable via REST, so
a reconnecting client can resync without a replay channel. A pre-commit
broadcast that rolled back would make the UI show a reaction that does not
exist. The full-set payload also makes replay idempotent.

**Q4. Why exactly 3 pins? Where is the limit enforced and what does the client
get?**
A. Three keeps the pin banner scannable (a highlight, not storage). The limit
is enforced server-side in `PinService.pin()` inside the insert transaction,
and the client gets `409 PIN_LIMIT` (`PinService.java:54,136-139`;
`ApiErrorCode.java:30`). A client-side cap would not be authorization. The
`SELECT … FOR UPDATE` on the conversation row
(`ConversationRepository.java:24-26`) serializes racing pins so the check is
not a check-then-insert race.

**Q5. Why can any member pin in a DIRECT chat but only an ADMIN in a GROUP?**
A. DIRECT has no ADMIN — both members are `MEMBER`
(`ConversationMember.java:26-28`), so an ADMIN check would make pinning
impossible. GROUP has an ADMIN role, and pins are admin-only to keep one
authoritative curator, mirroring add/remove/rename
(`GroupService.java:304-308`; `PinService.java:187-193`).

**Q6. A group member who is not an admin taps Pin — what happens, and which
layer returns the error?**
A. The UI hides the action, but if the request is sent, `PinService.pin()` locks
the conversation, checks membership (`403 NOT_MEMBER` for non-members), then
`requirePinRole` (`403 NOT_ADMIN` for members who are not admins)
(`PinService.java:126-133,181-193`). No `PIN_UPDATED` is broadcast. For REST the
service-level check is the enforcement point; for WS the SUBSCRIBE interceptor
additionally ensures only members receive the topic events
(`StompAuthChannelInterceptor.java:113-137`).

**Q7. Why must `replyToId` point to a message in the same conversation?**
A. `MessageService.resolveReplyTo` loads the target and verifies the
conversation id (`MessageService.java:289-304`). Otherwise a client could quote
an arbitrary message id from another conversation — an information leak and a
broken quote preview — and the foreign key alone (`V1__init.sql:96`) only
guarantees existence, not context. A missing target returns
`MESSAGE_NOT_FOUND` (404); a cross-conversation target returns
`VALIDATION_FAILED` (400). The same rule is applied to pin targets
(`PinService.java:196-204`).

**Q8. A client was offline while someone reacted or pinned. How does it catch
up — WS or REST?**
A. REST is the source of truth; WS is push only. On reconnect the client fires
a resync request (`RealtimeSession.kt:127-131,180`) and pages history with
`afterSeq` (`MessageRepository.kt:348-356,363`); pins are refreshed with
`GET /pins` (`MessageRepository.kt:541-550`) and reactions are re-read with the
message payload (`MessageService.reactionsByMessage`,
`MessageService.java:465-483`). Missed `REACTION_UPDATED` / `PIN_UPDATED`
frames are not replayed — the REST resync closes the gap
(`docs/websocket-protocol.md:100-102`). This is the same guarantee that makes
persist-before-broadcast sufficient.

---

## 6. Implementation checklist (snapshot)

**Done (server):**
- [x] `V3__interactions.sql` — `message_reactions` PK `(message_id, user_id,
      emoji)`; `pinned_messages` PK `(conversation_id, message_id)`
      (`V3__interactions.sql:15-36`).
- [x] Entities + repositories with `ON CONFLICT DO NOTHING` writes
      (`MessageReactionRepository.java:23-46`; `PinnedMessageRepository.java:20-41`).
- [x] `ReactionService` toggle + aggregate broadcast (`ReactionService.java`).
- [x] `PinService` list/pin/unpin, DIRECT/GROUP policy, `FOR UPDATE` + ≤3
      (`PinService.java`).
- [x] `ReactionController` / `PinController` (`ReactionController.java`;
      `PinController.java`).
- [x] `ReactionUpdatedEvent` / `PinUpdatedEvent` + `InteractionBroadcastListener`
      (`InteractionBroadcastListener.java`).
- [x] `MessageResponse.replyTo` + `reactions[]`; `MessageService` viewer-relative
      assembly (`MessageResponse.java`; `MessageService.java:437-502`).

**Done (Android data layer):**
- [x] `InteractionDtos.kt` + `MessageApi` methods (`MessageApi.kt:64-98`).
- [x] Room `MessageReactionEntity` / `PinnedMessageEntity` + DAOs + DB migration
      (`Entities.kt:100-126`; `Daos.kt:115-195`; `AppDatabase.kt:30-31,63-64`).
- [x] Typed `ChatEvent.ReactionUpdated` / `PinUpdated` (`RealtimeSession.kt:63-79,322-339`).
- [x] Repository optimistic toggle/pin/unpin + reconcile (`MessageRepository.kt:446-579`).
- [x] `ChatViewModel` reply/pin state + actions (`ChatViewModel.kt:57-89,200-255`).

**Remaining:**
- [ ] **Compose UI** — `ReactionBar`, `ReplyPreview`, `PinnedBanner`
      (`feature/interactions/README.md:3-7`); wire them into `ChatScreen.kt`
      (currently renders none of them).
- [ ] **Tests TC-INT-01..04** — react twice → remove; non-admin group pin → 403;
      4th pin → 409; reply DTO (`phase-11-creative.md:24`; `docs/rtm.md:17`;
      `docs/packaging-checklist.md:20`).
- [ ] **Update `docs/websocket-protocol.md:85-86`** to the concrete DTO payload
      shapes (currently the looser summary).
- [ ] **No new STOMP SEND destination needed** — reactions/pins are
      REST-triggered; the interceptor regex `messages|typing|read`
      (`StompAuthChannelInterceptor.java:62-64`) stays unchanged. The two WS
      events are server→client only.

---

## 7. Source index

**Server (shipped):** `server/src/main/resources/db/migration/V1__init.sql`,
`V2__attachments.sql`, `V3__interactions.sql`;
`server/src/main/java/com/nexachat/message/Message.java`, `MessageService.java`,
`MessageController.java`, `MessageRepository.java`, `MessageBroadcastListener.java`,
`MessageReaction.java`, `MessageReactionId.java`, `MessageReactionRepository.java`,
`PinnedMessage.java`, `PinnedMessageId.java`, `PinnedMessageRepository.java`,
`ReactionService.java`, `PinService.java`, `ReactionController.java`,
`PinController.java`, `ReactionUpdatedEvent.java`, `PinUpdatedEvent.java`,
`InteractionBroadcastListener.java`, `dto/SendMessageRequest.java`,
`dto/MessageResponse.java`, `dto/MessageRow.java`, `dto/ReactRequest.java`,
`dto/ReactionUpdatedPayload.java`, `dto/ReactionSlice.java`, `dto/ReactionRow.java`,
`dto/ReactionView.java`, `dto/PinRequest.java`, `dto/PinUpdatedPayload.java`,
`dto/PinnedMessageView.java`, `dto/ReplyRefResponse.java`, `dto/ReplyRefRow.java`;
`server/src/main/java/com/nexachat/ws/WsEventType.java`, `EventEnvelope.java`,
`StompAuthChannelInterceptor.java`;
`server/src/main/java/com/nexachat/common/error/ApiErrorCode.java`,
`GlobalExceptionHandler.java`;
`server/src/main/java/com/nexachat/conversation/ConversationMember.java`,
`ConversationRepository.java`, `GroupService.java`.

**Android (shipped data layer):** `android/app/src/main/java/com/nexachat/core/network/dto/InteractionDtos.kt`,
`core/network/dto/MessageDtos.kt`, `core/network/MessageApi.kt`,
`core/network/stomp/RealtimeSession.kt`, `core/network/stomp/dto/WsDtos.kt`,
`core/database/Entities.kt`, `core/database/Daos.kt`, `core/database/AppDatabase.kt`,
`core/database/DatabaseModule.kt`, `data/MessageRepository.kt`,
`feature/chat/ChatViewModel.kt`, `feature/interactions/README.md`.

**Contract / docs:** `docs/websocket-protocol.md`, `docs/api-contract.md`,
`docs/security.md`, `docs/rtm.md`, `docs/demo-runbook.md`,
`docs/packaging-checklist.md`.

**Plan:** `plans/261103-2322-nexachat-master-design/plan.md` (§8, §9.2, §10.2,
§11.3, §19.2, §20.1), `phase-11-creative.md`,
`reports/execution-ledger.md`.
