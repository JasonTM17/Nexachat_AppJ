# NexaChat Known Limitations & Deferred Work

Current-state inventory for the final-project report ("hạn chế"). Snapshot basis:
final frozen tree on `main` based on `64d47c0` (2026-11-05), including the
uncommitted stability fixes. Sources: the execution ledger
(`plans/261103-2322-nexachat-master-design/reports/execution-ledger.md`),
`docs/security.md`, `docs/interactions-demo.md`, `docs/i18n-audit.md`,
`docs/rotation-audit.md`, and the cited source files.
Paths are repo-relative; `…/` abbreviates `server/src/main/java/com/nexachat/` and
`android/app/src/main/java/com/nexachat/`.

## 1. Explicitly out of scope by design

| Limitation | Why deferred | User-facing impact | Future work |
| --- | --- | --- | --- |
| No end-to-end encryption | JWT auth + TLS transport is the chosen threat model; E2EE was never in MVP scope (`docs/security.md:147-148`). | Message and file content is plaintext at rest; a database/backup leak exposes content. | Per-conversation key exchange + client crypto; server-side key management. |
| No WebRTC voice/video calling | Plan ranks it the highest stability risk and excludes it from mandatory scope (`plan.md:83,100,369`); Message Interactions was chosen as the creative feature instead (`plan.md:100`). | No in-app call button; the demo covers chat, media and interactions only. | Signaling + STUN/TURN design is pre-drawn (`plan.md:83`); add as a later phase. |
| No push notifications (FCM) | Optional tier; a foreground demo is sufficient (`plan.md:88,98`). | No notification while backgrounded; unread badges update on next launch/refresh. | FCM integration point is pre-drawn (`plan.md:275-286`). |
| Single-instance broker | `SimpleBroker` is in-process fan-out, correct for one instance (`WebSocketConfig.java:58`). | Works for the one-backend demo; cannot fan out across multiple server nodes. | Broker relay (RabbitMQ/STOMP) + move `RateLimitService` buckets to Redis/JDBC (`docs/security.md`; `docs/websocket-protocol.md` §8; `plan.md:919`). |
| In-memory rate limiter | Buckets live in a bounded LRU map (10,000 keys, 15 min idle TTL) in one JVM (`RateLimitService.java:24-27`). | Correct for one instance; two nodes get two independent budgets, and a restart clears the budgets. | Bucket4j Redis/JDBC backend. |
| Message `seq` is a global PostgreSQL sequence | `BIGSERIAL UNIQUE` (`V1__init.sql:90`) gives a unique, monotonic per-conversation order, but seq values are shared across conversations, so gaps are possible (a rolled-back insert or another conversation's message consumes a value). | Cursor pagination (`afterSeq`/`beforeSeq`) is unaffected — it is exclusive `>` / `<`; only "seq == row count" assumptions break. | Per-conversation counters would need locking or a max+1 race; global seq is the simpler correct choice. |

## 2. Deferred review findings (ledger LATER)

| Finding | What / why deferred | User-facing impact | Future work |
| --- | --- | --- | --- |
| Inbox pagination is server-optional, client-unbounded | `GET /conversations` accepts optional `limit`/`cursor` (`ConversationInboxController.java:29-33`; `docs/api-contract.md:210,221`), but the Android client always requests the full list (`ConversationRepository.refreshInbox` passes `limit=null`), and the reconcile prune runs only when the payload is provably complete (an envelope with `hasMore`/`nextBeforeSeq` is treated as partial). | Users with many conversations still load them all at once; no incremental inbox paging on the device. | Page from the client and keep the partial-payload prune guard honest. |
| Presence `USER_ONLINE`/`USER_OFFLINE` | Vocabulary exists (`WsEventType.java:8-9`) but only the subset emitted this phase is produced; needs connect/disconnect listeners. Deferred (`ledger:121,130`). The online dot was removed from the UI rather than faked. | No online dot / last-seen; users cannot tell who is online. | Broker connect/disconnect listeners + contract surface. |
| Offline queue for reactions/pins | Outbox covers message sends only (`MessageRepository.kt:354-376`); reactions/pins are optimistic with revert-on-failure (`MessageRepository.kt:560-685`). Deferred (`ledger:129`). | React/pin tapped while offline reverts; no retry queue. | Extend the outbox to interaction actions. |
| Message edit/delete | `deletedAt`/`editedAt` are plumbed through responses and reply previews (`MessageService.java:450-458,489-501`; `MessageResponse.java:33-34`), but no edit/delete endpoint ships. Deferred (`ledger:129`). | Users cannot edit or remove a sent message; deleted messages are unreachable. | Ship edit/delete endpoints + client actions; gate react/pin on `deletedAt`. |
| Access JWT survives logout-all / password change | Logout-all and password change revoke **refresh** tokens only (`AuthService.java:138-159`); there is no access-token denylist, so an already-issued access JWT stays valid until its 15-minute expiry. | A stolen access token remains usable for ≤15 min after "sign out all devices". | Access-token denylist (jti + short TTL) or a shorter access TTL. |
| Registration reveals `PHONE_EXISTS` | Registration must tell the caller the phone is taken (`AuthService.register` → 409); login is the surface protected by uniform `INVALID_CREDENTIALS` + dummy bcrypt. | An unauthenticated caller can test whether a phone number is registered. | Rate-limited registration probing is the realistic mitigation; hiding existence would break the UX. |
| Dev-grade permissive CORS | `allowedOriginPatterns("*")` and STOMP `setAllowedOriginPatterns("*")` (`SecurityConfig.java:87`; `WebSocketConfig.java`). | Any origin may call the API; acceptable only for the local demo. | Narrow per environment before any public deployment. |
| Instrumented test coverage | The `androidTest` source set, `room-testing` dependency and `MigrationTestHelper` migration test exist and pass 3/3 on an API-35 emulator (`android/app/build.gradle.kts`; `android/app/src/androidTest/.../RoomMigrationTest.kt`), but there is no instrumented **UI** regression suite; rotation/i18n evidence is still unit-level (`docs/rotation-audit.md:1-8`). | Migration chain is verified on-device; UI state survival is not. | Add instrumented UI/rotation tests. |
| Lint warnings | `./gradlew lintDebug` reports **0 errors, 73 warnings** (`android/app/build/reports/lint-results-debug.txt`) — mostly `GradleDependency`/`AndroidGradlePluginVersion` and a few `UnusedResources`. | No functional impact; the warnings are advisory. | Upgrade dependencies / prune unused resources when the next version lands. |

## 3. Environment limits hit during build

- **Testcontainers unusable on Docker Desktop 29.** docker-java 1.20.4 cannot
  negotiate the named pipe, so ITs were rewired to env-JDBC against the compose
  Postgres (`ledger:33-35,50-53`; `server/pom.xml:25`;
  `server/src/test/resources/application-it.yml:6`). ITs still run real Postgres —
  e.g. `AuthFlowIT.java:56` defaults to
  `jdbc:postgresql://localhost:5433/nexachat` — so coverage is preserved, but
  container lifecycle is not exercised locally.
- **Debug LAN demo uses cleartext `http://` / `ws://`.** The debug source-set
  override permits cleartext for the configured LAN server
  (`android/app/src/debug/res/xml/network_security_config.xml`); use a trusted
  network because traffic is visible to other network peers. The main/release
  config remains restrictive except for emulator host `10.0.2.2`
  (`android/app/src/main/res/xml/network_security_config.xml`). Production needs
  TLS termination + WSS (`docs/security.md:140-143`).
- **Local environment drift.** Port 5432 is occupied, so compose maps `5433:5432`
  (`ledger:51`); the dev JWT secret is a visible placeholder
  (`application.yml:28`), while prod fails fast on unset `JWT_SECRET`
  (`application-prod.yml`) and on missing/unresolved datasource credentials
  (`ProductionDataSourceConfiguration.java:21-38`). Compose supplies
  `SPRING_DATASOURCE_*` so a fresh clone still boots.

## 4. Honest UX limits

- **Quote/pin jump is bounded to ~20 pages of history.** Tapping a quote or pin
  banner pages backwards through history (`MAX_JUMP_PAGES = 20` ≈ 600 messages,
  `MessageRepository.kt:916-917,292-317`) before scrolling to the target
  (`ChatScreen.kt:125-137`). A message older than that budget reports
  `reply_unavailable` ("Message unavailable") instead of jumping.
- **Reaction bar is hidden on own pending messages.** `replyable = status == SENT`
  gates the bar (`ChatScreen.kt:357,416`) and `toggleReaction` returns early
  otherwise (`ChatViewModel.kt:274`). Pending/outbox rows show no reaction
  affordance.
- **Server-down-while-network-up recovers only while foregrounded.** The
  re-arm triggers are a validated network *transition* (`NetworkMonitor`),
  app foreground (`ProcessLifecycleOwner.onStart` → `onAppForeground`), a
  user action (`ensureConnected` from `openConversation` and the send path),
  and — while the app is foregrounded with a valid session — a bounded
  backoff timer (5 s doubling to 60 s max) that re-arms a parked loop
  (`RealtimeSession.kt:320-358`). Backgrounded, the timer stops, so a
  backend that recovers while the app is behind another one waits for the
  next foreground/network transition/user action.
