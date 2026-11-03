# NexaChat Known Limitations & Deferred Work

Read-only audit for the final-project report ("hạn chế"). Snapshot: `nexachat` @
`734188e` (branch `main`, 2026-11-04). Sources: the execution ledger
(`plans/261103-2322-nexachat-master-design/reports/execution-ledger.md`),
`docs/security.md`, `docs/interactions-demo.md`, `docs/i18n-audit.md`,
`docs/rotation-audit.md`, and the cited source files. No source file was changed.
Paths are repo-relative; `…/` abbreviates `server/src/main/java/com/nexachat/` and
`android/app/src/main/java/com/nexachat/`.

## 1. Explicitly out of scope by design

| Limitation | Why deferred | User-facing impact | Future work |
| --- | --- | --- | --- |
| No end-to-end encryption | JWT auth + TLS transport is the chosen threat model; E2EE was never in MVP scope (`docs/security.md:147-148`). | Message and file content is plaintext at rest; a database/backup leak exposes content. | Per-conversation key exchange + client crypto; server-side key management. |
| No WebRTC voice/video calling | Plan ranks it the highest stability risk and excludes it from mandatory scope (`plan.md:83,100,369`); Message Interactions was chosen as the creative feature instead (`plan.md:100`). | No in-app call button; the demo covers chat, media and interactions only. | Signaling + STUN/TURN design is pre-drawn (`plan.md:83`); add as a later phase. |
| No push notifications (FCM) | Optional tier; a foreground demo is sufficient (`plan.md:88,98`). | No notification while backgrounded; unread badges update on next launch/refresh. | FCM integration point is pre-drawn (`plan.md:275-286`). |
| Single-instance broker | `SimpleBroker` is in-process fan-out, correct for one instance (`docs/security.md:144-146`; `WebSocketConfig.java:58`). | Works for the one-backend demo; cannot fan out across multiple server nodes. | Broker relay (RabbitMQ/STOMP) + move `RateLimitService` buckets to Redis/JDBC (`docs/security.md:146`; `docs/websocket-protocol.md:131-134`; `plan.md:919`). |

## 2. Deferred review findings (ledger LATER)

| Finding | What / why deferred | User-facing impact | Future work |
| --- | --- | --- | --- |
| SEND rate limiting | Only REST paths are bucket-limited (`WebConfig.java:34-38`); STOMP SEND has no throttle (`StompAuthChannelInterceptor.java` contains no rate/limit code). Deferred (`ledger:86,130`). | A connected client can flood `/app/conversations/{id}/messages`; only membership authZ applies. | Per-user STOMP SEND bucket + `RATE_LIMITED` error frame. |
| Inbox pagination params | `GET /conversations` takes no cursor/limit and returns the full member list (`ConversationInboxController.java:30`; `docs/api-contract.md:209`); ledger flags ignored pagination params (`ledger:86`). | Users with many conversations load them all at once; no incremental inbox paging. | Add `beforeSeq`/`limit` to the inbox query and page from the client. |
| Proactive token refresh before WS reconnect | Client refreshes only after a 401/403 handshake failure (`RealtimeSession.kt:208-211`); no pre-expiry refresh. Deferred (`ledger:86,130`). | A reconnect with a stale access token drops the socket once before recovery; brief offline window. | Refresh on a timer before `exp`, then reconnect. |
| Presence `USER_ONLINE`/`USER_OFFLINE` | Vocabulary exists (`WsEventType.java:20-21`) but only the subset emitted this phase is produced (`WsEventType.java:8-9`); needs connect/disconnect listeners. Deferred (`ledger:121,130`). | No online dot / last-seen; users cannot tell who is online. | Broker connect/disconnect listeners + contract surface. |
| `listPins` N+1 (bounded) | `listPins` calls `messageService.toResponse` per pin (`PinService.java:98-108`), which runs attachment/reaction/reply queries per message (`MessageService.java:437-447`); bounded by the ≤3 pin cap (`PinService.java:54`). | Extra DB round-trips when the pin banner loads; invisible at ≤3 pins. | Batch per-message hydration like the history page. |
| Offline queue for reactions/pins | Outbox covers message sends only (`MessageRepository.kt:105-110`); reactions/pins are optimistic with revert-on-failure (`MessageRepository.kt:490-579`). Deferred (`ledger:129`). | React/pin tapped while offline reverts; no retry queue. | Extend the outbox to interaction actions. |
| Pin order REST-vs-WS cosmetic | REST lists oldest pin first (`PinService.java:94`); the Android banner projects newest first (`Daos.kt:176`; `MessageRepository.kt:201-203`). Deferred (`ledger:129`). | Banner order can differ from the REST list; cosmetic only. | Agree one ordering in the contract. |
| Emoji normalization | Validation checks blank and ≤16 code points only (`ReactionService.java:108-122`); no NFC/NFD normalization. Deferred (`ledger:129`). | Visually identical emoji in different Unicode forms can split into separate chips. | Normalize to NFC before the PK/aggregate. |
| Message edit/delete | `deletedAt`/`editedAt` are plumbed through responses and reply previews (`MessageService.java:457,485-498`; `MessageResponse.java:33-34`), but no edit/delete endpoint ships. Deferred (`ledger:129`). | Users cannot edit or remove a sent message; deleted messages are unreachable. | Ship edit/delete endpoints + client actions; gate react/pin on `deletedAt`. |
| `androidTest` / `MigrationTestHelper` infra | Instrumented deps exist (`android/app/build.gradle.kts:107-111`) but there is no `androidTest` source set or `MigrationTestHelper` dependency; ledger flags it (`ledger:129`). | No on-device migration/UI regression suite; rotation/i18n evidence is unit-level only (`docs/rotation-audit.md:1-8`). | Add `androidTest` + Room migration tests. |

## 3. Environment limits hit during build

- **Testcontainers unusable on Docker Desktop 29.** docker-java 1.20.4 cannot
  negotiate the named pipe, so ITs were rewired to env-JDBC against the compose
  Postgres (`ledger:33-35,50-53`; `server/pom.xml:25`;
  `server/src/test/resources/application-it.yml:6`). ITs still run real Postgres —
  e.g. `AuthFlowIT.java:50` defaults to
  `jdbc:postgresql://localhost:5433/nexachat` — so coverage is preserved, but
  container lifecycle is not exercised locally.
- **LAN demo is cleartext `http://` / `ws://`.** The Android network config
  permits cleartext only for the emulator host `10.0.2.2`
  (`android/app/src/main/res/xml/network_security_config.xml:10-13`); production
  needs TLS termination + WSS (`docs/security.md:140-143`). The runbook's LAN IP
  flow (`docs/demo-runbook.md:8-9`) therefore runs outside that exception unless
  the host is the emulator alias.
- **Local environment drift.** Port 5432 is occupied, so compose maps `5433:5432`
  (`ledger:51`); the dev JWT secret is a visible placeholder
  (`application.yml:28`), while prod fails fast on unset `JWT_SECRET`
  (`application-prod.yml:2`).

## 4. Honest UX limits

- **Pin menu shows for non-admin group members.** `ChatViewModel.togglePin` is
  unconditional because the chat data path exposes no role
  (`ChatViewModel.kt:241-247`); the pin item is enabled by `replyable` only
  (`ChatScreen.kt:407-420`). A non-admin tap gets a server 403 surfaced as a
  snackbar — there is no client-side role check on that path.
- **Quote/pin jump is a no-op outside the loaded window.** `jumpToMessage`
  searches only currently loaded messages and returns silently when the id is
  absent (`ChatScreen.kt:114-120`). Tapping a quote or pin banner for an older
  message does nothing until history is paged.
- **Reaction bar is hidden on own pending messages.** `replyable = status == SENT`
  gates the bar (`ChatScreen.kt:337,393-398`) and `toggleReaction` returns early
  otherwise (`ChatViewModel.kt:235`). Pending/outbox rows show no reaction
  affordance.
- **Server-down-while-network-up does not auto-retry.** The re-arm triggers
  are a validated network *transition* (`NetworkMonitor`) or a user action
  (`ensureConnected` from `openConversation`). If the backend is unreachable
  while the device network stays up (server restart, dead backend), the loop
  exhausts `MAX_ATTEMPTS` and waits for the next user navigation — no
  periodic heartbeat re-arm exists yet (`RealtimeSession.kt:196-211`).
