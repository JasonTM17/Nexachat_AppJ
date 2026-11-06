# Known Limitations

This page describes constraints visible in the current source. It is not a
deployment certification; use the current CI and release evidence for build,
test, and runtime status.

## 1. Backend scope and scaling

| Limitation | Current behavior | Impact / next step |
|---|---|---|
| Single-node realtime broker | Spring `SimpleBroker` and `PresenceSessionRegistry` are process-local (`server/src/main/java/com/nexachat/config/WebSocketConfig.java`, `server/src/main/java/com/nexachat/presence/PresenceSessionRegistry.java`). | Events and online-session counts do not span backend instances. A multi-node deployment needs shared presence state and a broker relay. |
| Live subscription after member removal | Membership is checked at SUBSCRIBE, but removing a member does not revoke an already-open broker subscription; a still-connected removed member may receive later topic frames until it disconnects (`GroupService.java:217`, `StompAuthChannelInterceptor.java:160`, `MessageBroadcastListener.java:48-73`). | Reported as a deferred High finding in `reports/codex-sec.md`; no round-2 report exists in this audit round, so the status is **open**. Revoke subscriptions on removal or filter frames by current membership, then test with an open removed-member socket. |
| In-memory rate limits | `RateLimitService` keeps bounded token buckets in the server process. | Budgets reset on restart and are independent across nodes; use a shared store before scaling out. |
| Presence privacy and scope | Presence is sent only to DIRECT peers while the subject's `show_presence` setting is on; groups receive no presence. Hiding one's own state does not hide peers' state from that user. | This is a coarse account-level, one-way control, not per-contact visibility. |
| Presence state | Presence is tracked in the in-memory `PresenceSessionRegistry` and is single-node. | Online state does not span backend instances; a multi-node deployment needs shared presence state. |
| Message search index | Message search uses escaped `unaccent` + `ILIKE` substring matching scoped to a conversation; there is no trigram index for the substring predicate (`server/src/main/java/com/nexachat/message/MessageRepository.java`). | Larger conversations may require scanning many rows. Measure realistic data before selecting a trigram or full-text index. |
| Soft-deleted attachment retention | Deleting a message removes reaction rows and any pin and makes its attachment inaccessible through the deleted message, but keeps attachment metadata and stored bytes. | Storage is not reclaimed automatically. A retention/purge policy and a safe background cleanup are future work. |
| Global message sequence | PostgreSQL `BIGSERIAL` values are unique and monotonic globally, not contiguous per conversation (`server/src/main/resources/db/migration/V1__init.sql`). | Sequence gaps are normal; consumers must use ordered cursors, not infer row counts from sequence differences. |
| Access-token revocation | Logout-all and password change revoke refresh-token families; already issued access JWTs remain valid until their short expiry. | A revoked session may keep API access for the remainder of the access-token TTL. An access-token denylist would change this behavior. |
| Phone ownership | Registration uses phone number plus password and does not verify phone ownership with OTP. | The service cannot prove that a registrant controls the number. Adding verification requires an external messaging provider. |
| Phone enumeration | Registration reports `PHONE_EXISTS`; login uses a uniform invalid-credentials response. | An unauthenticated caller can learn whether a phone is registered. Rate limiting reduces, but does not remove, that disclosure. |
| CORS defaults | Server API and STOMP origin patterns are permissive in the current configuration (`server/src/main/java/com/nexachat/config/SecurityConfig.java`, `WebSocketConfig.java`). | Restrict allowed origins by environment before public deployment. |
| Call feature | Call signaling, ICE configuration, server call history and the Android call UI are shipped (`server/call`, `android/.../call`); the live call E2E (`tools/e2e/calls/call_e2e.py`) passes and on-device screens are captured (`docs/design/screens/s6-*`). | Audio has not been verified between two real devices (see the call-audio row below); the signaling/UI path is otherwise demo-ready. |
| Call infrastructure | Call state and STOMP delivery use the single-node in-memory registry and `SimpleBroker` (`docs/calls.md`). | Multiple server instances need shared call coordination and a broker relay. |
| Call NAT traversal | The default ICE configuration has public STUN only and no TURN. | Strict NAT or mobile networks may not establish media; a shared LAN/Wi-Fi path is the expected demo environment. |
| Call scope | Only DIRECT conversations with exactly two members can call; calls are audio-only. | Group calls are out of scope. |
| Call delivery while offline | An offline callee receives no push notification; the ring expires as a missed call and leaves a history row plus a localized chat line. | No FCM integration exists (see Android limitations). |
| Call audio path | Audio media is negotiated by the Android client and depends on WebRTC availability on the device. The live E2E and on-device run verified signaling, SDP/ICE relay, mute/speaker routing and teardown, but not an audio stream between two WebRTC endpoints. | A missing or failed WebRTC stack degrades the call; a real two-device audio test is still required before claiming a media journey. |
| Inbox call preview direction | The inbox renders a `CALL|...` system message through the same resolver as chat bubbles, but always as the incoming phrasing (`android/app/src/main/java/com/nexachat/feature/inbox/InboxScreen.kt:221-228`). | The preview cannot distinguish outgoing from incoming calls; it shows the incoming label for both directions. Pass the viewer's direction when a richer preview model is introduced. |
| Conversation summary | The summary route, extractive local engine, optional DeepSeek LLM engine, consent gate, Flyway V6 tables and Android summary UI are shipped; `s4-*`/`s5-*` screenshots cover the sheet, engine badges, consent flow and landscape. The local engine is extractive: it selects and labels existing messages and does not generate new prose. The LLM engine is optional and requires both an operator key and the user's `aiSummaryConsent`; without either, no text leaves the server. | Consent is account-level: messages of other members are sent (redacted) on the requester's behalf, with no per-conversation veto. A real DeepSeek call was exercised by the live smoke script and by the device run against the live backend; latency was about 16 s on the reference call, above the 10 s target in the plan. |
| Summary privacy | With consent and an operator key, the server sends only redacted usable message text of other members on the requester's behalf; there is no per-conversation veto. | A member cannot block their messages from being summarized in a conversation they belong to. Add a per-conversation opt-out if required. |
| Summary redaction scope | Phone, e-mail and URL redaction runs on text sent to the external LLM. The local extractive engine quotes existing messages, so a selected message that contains such a token is shown unredacted to the requester, who can already read it. | Not a disclosure to a third party. Redact the local output as well if summaries are ever exported or shared. |
| Summary consent revocation | Revoking `aiSummaryConsent` makes later requests use the local engine and no cached LLM output is served (live E2E scenario). A revocation that races a request whose LLM call is already in flight was not tested. | At most one in-flight summary may complete with the provider chosen before the revocation. |
| Call session retention | `CallRegistry` keeps terminal call sessions in its in-memory map (`server/src/main/java/com/nexachat/call/CallRegistry.java`); no long-run or load test measured the effect. | Memory grows with call volume until restart. Add eviction of terminal sessions before long-lived deployments. |
| Browser client | The supported realtime client is the native Android app using raw WebSocket + STOMP; no browser UI or SockJS fallback is provided. | There is no desktop/web messaging client. |

## 2. Android and offline behavior

| Limitation | Current behavior | Impact / next step |
|---|---|---|
| Interaction actions while offline | The durable outbox covers message sends. Reactions and pins use optimistic updates with failure rollback rather than a durable action queue (`android/app/src/main/java/com/nexachat/data/MessageRepository.kt`). | Offline reactions and pins are not retried automatically. |
| Attachment forwarding | Message forwarding supports live `TEXT` content only (`android/app/src/main/java/com/nexachat/core/ui/ForwardFilter.java`). | Images, audio, video, files, and deleted messages cannot be forwarded through this action. |
| Client inbox paging | The server accepts optional `limit`/`cursor` paging, but the Android client currently requests the complete inbox (`android/app/src/main/java/com/nexachat/data/ConversationRepository.kt`). | Very large inboxes are loaded in one request; client keyset paging remains future work. |
| Deep history jump | Quote/pin jumps page at most 20 history pages (`MessageRepository.MAX_JUMP_PAGES`). | A target beyond the bound may not be reached by the jump flow. |
| Reconnect while backgrounded | A parked connection loop re-arms from a timer while foregrounded; while backgrounded it waits for a network, foreground, or user-action trigger (`RealtimeSession.kt`). | A recovered server may not be reached until the app returns to the foreground or another trigger occurs. |
| Instrumented UI coverage | The repository has a Room migration instrumentation test, but no instrumented UI/rotation regression suite. | JVM tests do not prove device lifecycle behavior; add UI instrumentation if lifecycle coverage is required. Check the current CI run for migration execution status. |
| Debug LAN transport | The Android debug source set permits cleartext HTTP/WS for LAN demos (`android/app/src/debug/res/xml/network_security_config.xml`). | Credentials and traffic can be observed on an untrusted network. Use only a trusted demo LAN; production requires HTTPS/WSS. |
| Push notifications | Firebase Cloud Messaging is not integrated. | No notification is delivered while the app is backgrounded or stopped. |
| End-to-end encryption | Transport security is TLS-dependent; message and file content is not end-to-end encrypted. | Server/database or backup access can expose stored content. |

## 3. Verification boundary

Test totals and pass/fail status are intentionally not fixed in this document.
The checkout may contain partial local reports while a build is running or
after a failed run. Use the current CI result and the release audit report for
the exact revision under review. Unit tests do not prove a hosted deployment,
multi-node broker behavior, or a real call-media journey.
