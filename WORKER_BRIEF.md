# Worker brief — sync page wipe of conversation history (confirmed product defect)

## Mission
Root-cause and fix the confirmed defect where `SyncPageStore.apply` wipes a
conversation's entire local `messages` history when that conversation is absent
from a single sync page response. Deliver: diagnosis + test-first fix + focused
regression evidence. Do NOT merge; commit on branch `fix/sync-page-wipe`.

## Confirmed live evidence (2026-11-22)
- Device run `verify-285-final/20261122T012026Z-df3059be` offline-sync attempt-2:
  `FAIL_PRODUCT — the seeded fixture burst did not reach the open conversation`.
- Manual probe on the live app afterwards: opened the DM → `messageList`
  RecyclerView present, **0 `messageBody` nodes**, scroll produced nothing.
- Inbox row for the same DM renders normally with fresh preview text
  (`sj-ot2-f5fff005`) → `conversations` row was re-created by a later delta.
- REST transcript: all 62 fixture sends HTTP 201; all recall DELETEs succeeded.
  Server-side data is complete ⇒ local wipe, not missing data.

## Suspect code (file:line)
- `android/app/src/main/java/com/nexachat/data/SyncPageStore.java`
  - `apply(owner, epoch, requested, response)` — builds `returned` set from
    `delta.getConversationId()` per response conv (:159-163).
  - :244-247 — `for (String conversation : requested.keySet())` → if
    `!returned.contains(conversation)` → `LocalConversationCleaner.deleteInTransaction(db, conversation, owner)`
    + `db.syncCursorDao().delete(owner, conversation)`. **This is the wipe.**
- `android/app/src/main/java/com/nexachat/data/SyncV2Consumer.java:65-90` —
  builds per-page `requested` watermark batch; calls `store.apply` at :88.
- `android/app/src/main/java/com/nexachat/data/SyncEngine.java:867-869` —
  `missing` set → same `LocalConversationCleaner` wipe (second site; review
  whether it shares the defect).
- `android/app/src/main/java/com/nexachat/data/LocalConversationCleaner.java` —
  deletes pins, outbox, reactions, **all messages**, translations for the conv.

## Open contract question (resolve FIRST — this decides the fix)
Is a conversation absent from one page response *authoritatively* removed?
- Check the server sync handler (`server/` module — find the v2 sync endpoint
  serving `SyncRequest`/`SyncResponse`) for the omission contract: does a conv
  get omitted only when deleted/inaccessible, or can it be omitted transiently
  (pagination budget, watermark already satisfied, concurrent churn)?
- If omission is authoritative ⇒ bug is server-side; document + do NOT hack the
  client.
- If omission is non-authoritative (the observed behavior: the DM reappears in
  the inbox with data still server-side) ⇒ the client must NOT wipe on
  per-page absence. Likely fix shape: only wipe when the server explicitly
  signals removal (dedicated removed/tombstone list in the response, or the
  `missing` set semantics), never from mere absence in a page.

## Constraints
- Test-first: write a failing unit test reproducing "requested conv absent from
  page → local history must survive" (or the server-contract equivalent) BEFORE
  the fix; show it fails on HEAD, passes after.
- Keep authoritative-removal behavior intact for genuinely-removed convs
  (leave/prune/STOMP self-removal — see #284 regression tests for the sibling
  class; do not weaken them).
- Do not touch `LocalConversationCleaner` itself, the e2e harness, or files
  outside `android/app/src/main/java/com/nexachat/data/` +
  `android/app/src/test/...` (+ server contract docs if needed for diagnosis).
- Reuse existing test conventions; focused suite only
  (`SyncPageStoreTest`/`SyncDrainProbeTest`-adjacent classes).
- Bounded: ≤3 focused commits; if the contract question turns out server-side,
  STOP after documenting and return findings instead of forcing a client fix.

## Return format
`REPORT.md` at worktree root: root cause (file:line), contract answer with
server-code evidence, fix shape, test-before/after output, residual risks.
