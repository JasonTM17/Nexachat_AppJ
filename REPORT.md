# Report — sync page wipe of conversation history (branch `fix/sync-page-wipe`)

## Root cause

| Site | Effect |
|---|---|
| `android/app/src/main/java/com/nexachat/data/SyncPageStore.java:244-247` (`apply`, removed in this fix) | For every conversation in the page's `requested` watermark batch that has no delta in the response, `LocalConversationCleaner.deleteInTransaction(db, conversation, owner)` + `db.syncCursorDao().delete(owner, conversation)` ran — deleting pins, outbox, reactions, **all messages**, and translations. This is the confirmed wipe. |
| `android/app/src/main/java/com/nexachat/data/SyncEngine.java:867-869` (`applyDeltaBundle`, removed in this fix) | The legacy lane inferred the same "omission = removal" contract via `missingConversations(requested, response)` (`:577-594`) and wiped identically. Same defect. |

A drain issues **many independent page requests** (`SyncV2Consumer.sync`, chunks of 100 watermarks plus per-conversation continuation pages). The wipe evaluated each page in isolation: one page whose response omitted a conversation destroyed its entire local history, even though the very next request would have returned its data.

## Contract answer: omission is NOT authoritative (client bug)

Server evidence — `server/src/main/java/com/nexachat/message/MessageService.java`:

- `syncDeltaV2` (`:1091-1103`): a requested conversation is **omitted** only when `flags == null` or the `findSyncFence` projection is empty (`fence == null`) — i.e. when no `ConversationMember` row for the caller is **visible under the request transaction's READ_COMMITTED snapshot**. Pagination never omits: a conversation with zero changes still gets a delta (`:1094-1102` … `deltas.add` at `:1136-1154`).
- `findMemberFlags` (`server/.../ConversationMemberRepository.java:158-168`) and `findSyncFence` (`server/.../MessageRepository.java:267-272`) are plain membership joins — no tombstone, no "removed" list; `SyncResponse` carries no explicit removal signal.
- Legacy lane (`:923-930`) has the identical `continue`.

Why transient absence happens for a still-valid conversation: the drain is a **stateless sequence of separate HTTP requests**, each evaluated against the snapshot visible at that instant. Membership visibility (`flags`/`fence` reads issued per request, `:891-897`, `:1101`) can flicker between consecutive page transactions during churn (member row visibility, batched flags read vs. later per-conversation fence re-check), so a conversation the caller belongs to can be absent from one page and present on the next. That is exactly the observed incident — the wiped DM reappeared in the inbox from a later read with **all 62 fixture messages still server-side** — so a single page's omission cannot be taken as an authoritative removal. Conclusion: **client-side defect; fix the client, do not hack the server.**

## Fix shape

Per the brief's recommended direction (no dedicated removed/tombstone list exists in `SyncResponse`): the client must never wipe from mere per-page absence. Removal stays with the authoritative paths that already handle it and are untouched here: self-leave (`ConversationRepository.leaveGroup:850-875`), the STOMP self-removal frame, and the inbox reconcile prune.

- `SyncPageStore.apply`: removed the `!returned.contains(conversation)` wipe loop (and the now-unused `returned` set); the page-commit transaction no longer deletes anything for conversations the response omitted.
- `SyncEngine.applyDeltaBundle`: removed the legacy-lane `missing` wipe and the `missingConversations` helper; an empty-delta bundle returns before opening a write transaction.
- `LocalConversationCleaner` itself, the e2e harness, and the #284 drain-reader/guard regressions (`SyncDrainProbeTest`, `LocalConversationCleanerTest`) are untouched and still pass.

## Test evidence (test-first)

Commits (on `fix/sync-page-wipe`):

1. `c2706ed4` — `SyncEngineV2Test.requestedConversationAbsentFromASinglePageKeepsItsHistoryAndCursor`: seeds a conversation with one committed message + cursor; server returns a v2 envelope whose deltas omit the conversation. **Fails on HEAD**:

   ```
   java.lang.AssertionError: absence from one page must not wipe local history
       at SyncEngineV2Test.requestedConversationAbsentFromASinglePageKeepsItsHistoryAndCursor(SyncEngineV2Test.java:434)
   34 tests completed, 1 failed  (HEAD, :app:testDebugUnitTest)
   ```

2. `e62446d4` — the fix, plus flipping the two tests that had pinned the old (wrong) omission-wipe contract:
   - `SyncEngineV2Test.membershipOmissionDeletesOnlyItsAccountCursorAndCachedScope` → `membershipOmissionDeletesNoCachedScopeAndNoAccountCursor` (keeps its cross-account guard: another account's cursor untouched, now also asserts own history/cursor survive and nothing is deleted).
   - `SyncEngineTest.syncRemovesConversationOmittedFromASuccessfulDeltaResponse` → `syncKeepsConversationAbsentFromASuccessfulDeltaResponse` (asserts no delete of row/messages/reactions/pins/outbox for the omitted conversation; serverTime still persists).

   **After the fix**: all three tests pass.

   ```
   :app:testDebugUnitTest --tests "com.nexachat.data.*"
   35 test classes, 474 tests, 0 failures, 0 errors
   (includes SyncEngineV2Test, SyncEngineTest, SyncPageStoreTransactionSafetyTest,
    SyncDrainProbeTest, LocalConversationCleanerTest, ConversationRepositoryTest, ...)
   ```

## Residual risks

- **Stale cache for genuinely-removed conversations is now pruned only by the reconcile/leave/STOMP paths.** If a user is removed server-side (kicked) without an inbox reconcile running, the local conversation lingers until the next inbox reconcile walk. No data loss; a liveness trade deliberately taken.
- **Server-side improvement recommended:** add an explicit `removedConversations` tombstone list to `SyncResponse` so the client can distinguish authority-level removal from transient absence in one response (out of scope here; would also let the client prune kick-removals synchronously).
- The failed run's exact trigger (which server omission branch the verify-285 page hit) cannot be proven from the transcript alone; the fix is contract-level and covers both omission sites regardless of which branch produced the transient absence.
- E2e re-verification of verify-285 scenario is still pending (out of scope: the e2e harness is untouchable per the brief).
