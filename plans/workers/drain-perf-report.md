# Drain-duration profiling report (issue #266)

Worker: drain-perf (branch `agent/drain-perf-plan`, worktree `.claude/worktrees/drain-perf`)
Base: `4561c11b fix(android): keep live-message writes off the held writer connection (#264)`
Method: read-only code analysis + prior harness evidence. **No product code was changed and
no measurements were run in this session.** Every wall-clock number below was measured by
an earlier session on this codebase and is cited; everything else is code-derived and
labeled as such. Branch paths are repo-relative from `android/app/src/main/java/`.

The journey under budget pressure is `offline-sync` in
`tools/e2e/device/stable_journeys.py` (fixture: 105 seeded groups + one direct
conversation carrying 61 seeded changes, i.e. a >100-watermark set). Its
sync-batching assertion ("a1") polls for ≥2 bounded `POST /api/v1/sync` batches
within `_OFFLINE_SYNC_POLL_S = 180.0` (`stable_journeys.py:5947`, poll loop at
`:7481-7499`); the brief records a1 FAIL with a2 (marker/tail render) PASS once
the drain completes. Harness comments preserve the failure shape: a 60 s clock
"expired mid-drain (run-35/-38 attempt-1)" (`:7288-7295`) and "run-27 attempt-1
read the buffer ~2.5 minutes early and counted 0 while the real batches were
still draining" (`:7490-7493`). The harness even had to grow a
`_offline_wait_sync_quiet` helper with a **240 s** timeout because "pulling a
fresh inbox generation mid-burst supersedes the in-flight write before it
lands" (`:6675-6690`). That is the pacing issue this report explains.

---

## 1. Where drain wall-clock time actually goes

The v2 drain is a strictly serial pump: one HTTP round trip, then one full
Room writer transaction, repeated up to 60 times per 100-watermark batch
(`SyncV2Consumer.java:13` — "Bounded HTTP drain. Every accepted page is
durable before constructing its continuation"). It runs on a single
`Schedulers.io()` thread (`SyncEngine.java:169-170`) and every page pays four
serial costs: cursor snapshot reads → HTTP fetch → pre-commit guard → row apply
transaction + commit/invalidation. Wall-clock = pages × per-page cost. The
hypotheses below are ranked by expected share, with the evidence for each.

### H1 — Page count × (HTTP RTT + transaction): the dominant term (high confidence)

What inflates the page count on a fresh install:

1. **50-change ceiling per conversation per page.** The client hardcodes it
   (`SyncV2Consumer.java:54` — `new SyncRequest(requested, 50, ...)`), the
   client validator enforces it (`SyncPageStore.java:67` —
   `delta.getChanges().size() > 50` → `LegacyRequired`), and the server clamps
   it (`server/src/main/java/com/nexachat/message/MessageService.java:102` —
   `SYNC_V2_CHANGE_PAGE_MAX = 50`, applied at `:914`; the change query pages at
   `limit + 1` rows, `:1121-1123`). A conversation with 2,000 historical changes
   (a fresh install walks *every* change, since the first cursor is 0 with no
   generation, `SyncPageStore.java:39-40`; server reset logic at
   `MessageService.java:1109-1113` leaves `reset=false` so the full walk pages
   from zero) needs 40 pages of that conversation **alone**.
2. **Reaction cursors force continuation round trips.** The server cuts the
   reaction page at `maxReactionUpdates = 200` events per conversation
   (`SyncProperties.java:22`) and sets
   `hasMore = changeHasMore || reactionCursor != null`
   (`MessageService.java:1143`), so a conversation whose reaction page is full
   must be re-requested even when its change feed is done.
3. **Baseline-replay epilogue pages.** Fresh cursors start
   `rebuildingReactions = true` (`SyncPageStore.java:39-40`); when a
   conversation's pages finally quiesce, `replayBaseline` forces one extra
   near-empty page per conversation just to clear the baseline
   (`SyncPageStore.java:196-203`, re-added to `pending` at `:209`).
4. **Batch budget arithmetic.** 100 watermarks per request
   (`SyncV2Consumer.java:32`, `SyncEngine.java:55`) × 60-page budget
   (`SyncV2Consumer.java:37` = `MAX_DRAIN_PAGES(20) × (MAX_BOUNDED_RETRIES(2)+1)`,
   `SyncEngine.java:58-60`) means the w16 fixture can legitimately emit
   ~120 bounded pages for two batches.

Measured corroboration (prior sessions, this codebase): one reconnect drain
re-issued **88 sync posts, "mostly 158/159-byte single-conversation
continuation pages"** before going quiet, versus 6 requests for a
push-triggered sync of the same state
(`plans/emu-hunt/reports/runtime-report.md:144-147`); the pre-fix storm
showed **99 posts / 90 s** (`runtime-report.md:50`). A drain that is still
issuing pages at minute two is exactly what a1's 180 s window caught.

**Drain-restart amplifier (verify, medium confidence).** Any concurrent local
deletion of a `messages`/`conversations` row wipes that conversation's
`sync_cursors` row via the installed triggers (`AppDatabase.java:360-365`).
The next page's pre-commit guard compares the re-read cursor against the
snapshot and throws `CancellationException("Sync cache changed before page
commit")` (`SyncPageStore.java:121-126`), which `sync()` swallows as complete
(`SyncEngine.java:171-173`). The *next* caller then restarts the drain from
cursor zero — a full re-walk. The reconnect path itself fires the competing
work: `resyncOnReconnect` runs `sync()` + outbox flush + forward history pull
+ read receipts (`MessageRepository.java:1712-1719`), and the same reconnect
signal triggers a debounced inbox refresh whose reconcile path can prune
conversation rows (`ConversationRepository.java:858-874` debounce pump,
`:1260-1294` reconcile/prune, `LocalConversationCleaner.java:23-32` deletes).
The harness explicitly stopped pulling inboxes mid-burst for this reason
(`stable_journeys.py:6675-6690`). If restarts are frequent, wall-clock is
 dominated by re-walks, not by any single page.

### H2 — Per-row statement volume inside each page transaction (high confidence)

Every accepted change row costs, inside the held writer transaction:

- `byIdInTransaction` **three times**: the `isNew` pre-check
  (`SyncPageStore.java:157-158`), the applier's `old` read for revision
  comparison (`MessageStateApplier.java:56`), and the `committed` re-read for
  unread/outbox accounting (`SyncPageStore.java:161-162`);
- one upsert/update (`MessageStateApplier.java:71-72`);
- **two** `conversations` UPDATEs — `updateLastMessageInTransaction` plus
  `updateLastMessageContentInTransaction` (`MessageStateApplier.java:73-81`);
- a per-row `incrementUnreadInTransaction` (`SyncPageStore.java:163-166`) and
  a possible outbox delete (`:167-174`);
- plus per-row CPU: Moshi attachments encode, three ISO-8601 parses, mention
  codec, E2EE resolve (`MessageStateApplier.java:132-151`).

That is ~7–10 SQLCipher statements + JSON/crypto per row. The legacy lane
already does the batched version of this work — one
`upsertAllInTransaction`, one summed unread increment, one last-message write
per conversation (`SyncEngine.java:684-712`); **the v2 path regressed to
per-row**.

Per page, before any row is touched, the pre-commit guard runs **two SELECTs
per requested conversation — including unchanged ones** —
(`SyncPageStore.java:121-126`: `read()` + `conversationDao().byIdSync()`), and
the snapshot loop re-reads every pending conversation's cursor from Room
(`SyncV2Consumer.java:44-53`). At a 100-watermark batch that is 200 guard
SELECTs + 100 cursor reads per page, on every page, on the same single
connection the transaction holds. Per-code-cost corroboration: on this stack a
single STOMP frame's apply (Room write + inbox refresh + chat re-render)
measured ~700 ms/frame — the 61-frame fixture burst took ~42 s
(`stable_journeys.py:7285-7295`), and issue #184's comments document "60s+
connection-pool waits" under accumulated sync load
(`LocalConversationCleaner.java:11-16`,
`ConversationRepository.java:1271-1273`).

### H3 — Invalidation fan-out per page commit (medium confidence — measure before fixing)

Each page transaction touches `messages`, `conversations`, `message_reactions`,
`outbox`, `sync_cursors` → Room's InvalidationTracker re-runs every subscribed
Flowable after commit: the full-table inbox scan
(`ConversationDao.java:23-24`, consumed at `ConversationRepository.java:309`),
the open chat's whole history (`MessageDao.java:62-63`, consumed at
`MessageRepository.java:393-428`; subscribed a second time by `pinsFlow`,
`:435-453`), the per-chat header row (`ConversationDao.java:40-41`), reactions
and outbox flows. With chat + inbox open during the journey, each of the
~60-120 page commits re-runs all of them on Room's shared query executor and
re-renders the UI; the in-repo evidence that this contends with the writer is
the issue #184 note that a refresh occupying "that two-thread pool while
holding the writer connection" froze the app
(`ConversationRepository.java:1271-1273`). Share of total drain time is
unknown — this is the main thing §2 must measure.

### H4 — Cursor/token round-trip overhead per page (minor)

Each page pays `tokens.readTokens().blockingGet()` inside the write fence
(`SyncPageStore.java:272-273`), an `Instant.parse` per conversation per page
(`SyncV2Consumer.java:51-52`), and trigger execution on the reset/cleanup
delete paths (`AppDatabase.java:360-365`). Real but small; fix opportunistically
with H2's batched reads.

**Ranking:** H1 (page count) is dominant for the fresh-install journey; H2
amplifies every page and dominates the per-page transaction time; H3 may
double the effective cost of H1's commits but needs measurement; H4 is noise.
Nothing suggests server-side page assembly (per page: 100 fences
`MessageService.java:1102`, 100 change queries `:1121`, hydration, 100 max-seq
queries `:1152`) is the bottleneck on the local stack, but §2's fetch/apply
split will show it if it is.

---

## 2. Smallest measurable instrumentation to confirm the ranking

**Already present:** nothing in the sync path times itself — no
`android.util.Log`, no clocks anywhere in `com/nexachat/data` or the DAOs. The
only seams are the `beforeCommit`/`afterCommit` fault-point Runnables at the
page-commit boundary (`SyncEngine.java:215-218` → `SyncPageStore.java:20-21`),
`hasPendingContinuation` (`SyncEngine.java:147-149`), and harness-side OkHttp
logcat regexes (`_OFFLINE_SYNC_POST_RE` / `_OFFLINE_SYNC_BYTES_RE`,
`stable_journeys.py:5942-5944`). All prior numbers came from wire/UI
observation, which cannot split fetch vs apply vs observer cost.

**Add (debug-gated, production-identical behavior — a data class + a no-op
default hook, in the style of the existing `onFlightJoined` seam,
`SyncEngine.java:81`):**

1. **Page split timer — `SyncV2Consumer.java:38-58`.** One span around
   `fetch.apply(request).blockingGet()` (`:55`) and one around
   `store.apply(...)` (`:57`); record `requested.size()`, response row count
   (`Σ delta.getChanges().size()`, reaction updates, watermarks in the body),
   and per-page result `next.size()`. Confirms H1's page count and splits
   HTTP RTT vs local apply.
2. **Guard vs row-loop split — `SyncPageStore.apply`.** Separate spans for the
   pre-commit guard (`:121-126`) and the delta loop (`:127-216`), plus
   `requested.size()` and `same()` failure count. Confirms the H2 guard share
   and the H1 restart amplifier (a `CancellationException` after guard
   mismatch = one restart; count them per drain).
3. **Drain totals — `SyncEngine.syncAttempt:195-199`.** Wall clock of the
   whole `SyncV2Consumer.sync` call, pages issued, and
   `hasPendingContinuation` at exit. The number a1's budget actually waits on.
4. **Observer re-run counters — repository layer, `doOnNext` only.**
   `ConversationRepository.inboxFlow` (`:309`) and
   `MessageRepository.messagesFlow` (`:393`): count emissions + span while a
   drain is active vs idle. Confirms/refutes H3 without touching emission
   semantics.
5. **Zero-app-change cross-checks (harness, already possible today):**
   first→last `POST /api/v1/sync` wall clock from the existing logcat regexes;
   `Davey!`/skipped-frame lines for UI contention; `dumpsys gfxinfo`. These
   bound the drain even before the seam exists.

Each counter is unit-testable via the existing package-local seams (the
`SyncEngineV2Test` style), so the instrumentation itself lands with tests and
no device dependency.

---

## 3. Candidate fixes, ranked by effort/effect

Every option below preserves the #264 invariant — only synchronous
`*InTransaction` statements inside held writer transactions, no Rx-queued
writes inside them (`SyncPageStore.java:117-120`, `:134-135`, `:235-237`;
`ConversationDao.java:180-184`) — and the #201/#264 durable-page contract
(`SyncV2Consumer.java:13`).

### Fix A — Batch the per-page guard and cursor reads (effort: small, effect: medium)

Replace the per-conversation guard SELECTs (`SyncPageStore.java:121-126`) and
snapshot reads (`SyncV2Consumer.java:44-53`) with two IN-list statements:
`SyncCursorDao.findBatch(owner, ids)` and a `ConversationDao` ids-batch read,
folding missing rows to the same defaults `read()` produces today
(`SyncPageStore.java:37-41`). Removes up to 300 statements per page at full
batches. Semantic risk: **low** — same comparisons, same snapshot, one
statement; keep per-conversation result identity so the guard's
`same()`/`byIdSync` logic is byte-identical.

### Fix B — Restore legacy-parity batching inside the page transaction (effort: medium, effect: high on fresh installs)

In `SyncPageStore.apply`'s delta loop:
- make `MessageStateApplier.apply` return the committed row (it already
  computes it) so the three `byIdInTransaction` reads collapse to one;
- accumulate accepted-new-incoming counts and issue **one**
  `incrementUnreadInTransaction` per delta (the legacy path's proven shape,
  `SyncEngine.java:662-695`);
- fold the two per-row conversation UPDATEs
  (`MessageStateApplier.java:73-81`) into one pair of writes per delta,
  replaying the same `:seq > lastMessageSeq` / `lastMessageSeq = :seq` guards
  in Java over the accepted rows in page order — deterministic fold, identical
  end state.

Semantic risk: **medium, fully testable** — cursor ordering is untouched
(`changeCursor` still advances by the last accepted row's `changeVersion`;
the truncation boundary check `SyncPageStore.java:102-105` and no-progress
check `:204-206` stay). The risk is exactly the fold equivalence, so it ships
with a differential unit test (per-row vs folded end state over the same
fixture pages, including deletes/edits/outbox/unread cases) plus the existing
`SyncPageStoreTransactionSafetyTest` suite.

### Fix C — Page-size bump 50 → 200, coordinated client+server (effort: small-medium, effect: high on H1)

Client: the hardcoded `50` (`SyncV2Consumer.java:54`) and the validator bound
(`SyncPageStore.java:67`) move to one shared constant. Server:
`SYNC_V2_CHANGE_PAGE_MAX` (`MessageService.java:102`) 50 → 200, ideally
property-driven via `SyncProperties` for rollout, with `SyncV2ChangePageClampIT`
updated to pin the new clamp. Cuts change-feed pages for a 2,000-change
conversation from 40 to 10. Semantic risk: **low for ordering** (per-page
monotonic `changeVersion` is enforced either way), **medium for pacing**: one
commit now holds the writer ~4× longer (pair with Fix B so per-page statement
count drops as rows per page rise), and one response can carry up to 100
watermarks × 200 changes — recommend a **server total-row budget per response**
(e.g. cap total changes across the page, shrinking the watermark count when
the page size rises) rather than a bare per-conversation bump, so neither the
HTTP payload nor the transaction length explodes. Budget arithmetic
(`MAX_DRAIN_PAGES`) is untouched.

### Fix D — Observer suppression / emission coalescing during drain (effort: medium-large, effect: high only if H3 dominates)

Expose a drain-active flag from `SyncEngine` (set across `syncAttempt`) and
coalesce `inboxFlow`/`messagesFlow` emissions while it is set: keep the latest
snapshot, emit once at drain end. Writes, invalidation tracking, and the STOMP
live path are untouched — only observation is deferred, and Room's own
transaction-scoped coalescing is preserved. Semantic risk: **medium** — the
inbox/chat go stale for the drain's duration (unread badges, live bubbles
render late). The journey's a2 assertions pass *after* drain completion, so
they survive, but "live-write semantics fixed in #264" means the live path
must stay unsuppressed the moment no drain is active, and mid-drain STOMP
writes must still land and render promptly enough not to regress other
journeys (`stable_journeys.py` waits 60 s for post-restart peer text at
`:7510-7524`). Gate this fix on §2's measurement showing H3 ≥ ~30% of drain
wall clock.

### Explicitly rejected

- **Multi-page transactions** (committing several pages at once): violates
  "Every accepted page is durable before constructing its continuation"
  (`SyncV2Consumer.java:13`) and the documented fault model
  (`docs/architecture/android-sync.md:28-34`). Rejected outright.
- **Prefetching the next page while applying the current one:** same
  durability violation (the continuation depends on the committed cursor).
- **Raising harness budgets (`_OFFLINE_SYNC_POLL_S`) instead of fixing
  pacing:** hides the product regression the journey exists to catch.

---

## 4. Recommended plan

**Phase 0 — Instrument and baseline (no product behavior change).** Land the
§2 seam (page split, guard split, drain totals, observer counters) behind a
no-op default; wire the harness to parse first→last sync-post wall clock.
Record the baseline on the w16 fixture (fresh install, 105 groups + 61-change
direct). *Acceptance for this phase: numbers exist for fetch-ms, apply-ms,
guard-ms, pages, restarts, observer re-runs per drain.*

**Phase 1 — Client-only batching (Fix A + Fix B).** Files:
`data/SyncV2Consumer.java` (snapshot loop), `data/SyncPageStore.java`
(`apply` delta loop), `data/MessageStateApplier.java` (return committed row,
fold preview writes), `core/database/SyncCursorDao.java` +
`core/database/ConversationDao.java` (batched reads). Tests: differential
fold test, `SyncPageStoreTransactionSafetyTest`, `SyncEngineV2Test`,
`SyncAndOutboxRegressionTest` all green; fail-before/pass-after for the new
batching tests. *Acceptance: per-page apply p95 and guard cost drop
measurably from Phase 0 baseline; a1 passes more often but page count is
unchanged (page count is Phase 2's target).*

**Phase 2 — Page-size bump with a total-row budget (Fix C).** Files:
`data/SyncV2Consumer.java:54`, `data/SyncPageStore.java:67`,
`server/.../MessageService.java:102` + `SyncProperties`, server clamp IT.
*Acceptance: fresh-install drain pages on the fixture ≤ ~24 (from ~88-120);
a1 PASS on attempt 1 within its existing 180 s window; drain quiet
(`_offline_wait_sync_quiet`) within 120 s.*

**Phase 3 — Conditional observer coalescing (Fix D).** Only if Phase 0
shows H3 ≥ ~30% of drain wall clock. Files: `data/SyncEngine.java` (drain
flag), `data/ConversationRepository.java:309`, `data/MessageRepository.java:393`.
*Acceptance: observer re-runs per drain ≤ ~2× pages → bounded; zero skipped-frame
storms during drain; a2 render assertions unchanged.*

**Overall acceptance measurement (issue #266 done):** on the `offline-sync`
journey, fresh-install initial-sync drain completes — last `POST /api/v1/sync`
observed, `_offline_wait_sync_quiet` satisfied — in ≤ 120 s p50 over both
attempts, a1 PASS without retry, zero ANR/Davey entries, and the full existing
sync test suite green. Every phase honors #264's transaction-safety invariants
and #201's durable-page contract; no phase reorders cursors or changes
revision-acceptance semantics (`MessageStateApplier.accepts` untouched).

**Residual risk:** the H1 restart amplifier (§1) is plausible but unmeasured —
if Phase 0's restart counter is non-zero, add a small Phase 1.5: suppress the
reconnect's inbox refresh reconcile while a drain is active (the harness
already treats mid-burst pulls as harmful), which is Fix-D-adjacent but
narrower and semantically safer.
