---
phase: 01-ai-query-lifecycle
plan: "04"
subsystem: api
tags: [summary, consent, opt-out, cache, single-flight, junit, mockito]
requires:
  - phase: existing-summary
    provides: Consent epoch response lock, scoped cache and bounded single-flight lifecycle
provides:
  - Persisted consent recheck for every external Summary cache replay
  - Complete immutable exclusion identity for external cache lookup, flight and store
  - Suppression of stale uncited overview output across replay, waiter, generation and winner races
affects: [ai-query-lifecycle, summary, privacy]
tech-stack:
  added: []
  patterns: [immutable exclusion snapshots, sorted length-prefixed cache identity, response selection rechecks]
key-files:
  created: []
  modified:
    - server/src/main/java/com/nexachat/summary/SummaryService.java
    - server/src/test/java/com/nexachat/summary/SummaryServiceTest.java
    - server/src/test/java/com/nexachat/summary/SummaryCacheWinnerOptOutTest.java
key-decisions:
  - "Suppress changed external exclusion context with existing AI_CONSENT_REQUIRED instead of adding regeneration retries."
  - "Preserve existing contentHash helpers, the empty-exclusion hash and LOCAL cache identity."
  - "Bind the complete sorted exclusion UUID set even when excluded authors have no citation or participant entry."
requirements-completed: [AI-03, AI-06]
duration: 17min
completed: 2026-11-22
---

# Phase 1 Plan 4: Summary Replay Consent and Exclusions Summary

**External Summary replay checks persisted consent and complete exclusion context; obsolete uncited overview output is suppressed and late old cache fills remain isolated from new-context lookups.**

## Performance

- **Started:** 2026-11-21T17:33:50Z
- **Completed:** 2026-11-21T17:50:56Z
- **Tasks:** 2/2
- **Files modified:** 3

## Accomplishments

- Every direct, leader re-probe, waiter re-probe and final cached replay goes through the shared response builder, which invokes `SummaryConsentRecheck.verifyUnchanged` for the actual returned generation and the window-read consent snapshot.
- The immutable expected opt-out set is captured before external lookup or provider work. Its complete sorted UUID identity is included in the LLM flight, lookup and store hashes. The historical empty-set hash and all LOCAL/provider-failure fallback keys remain compatible.
- Persisted exclusion equality is checked before external cache output is selected, before a generation is saved, after a concurrent save returns, and before the final response is constructed. Citation and participant checks remain additional protections.
- An external result produced under changed context raises the existing typed `AI_CONSENT_REQUIRED`. This adds no retry loop and preserves the existing bounded flight waits and provider routing.
- A concurrent old fill that lands after purge can retain its old-key row, but its response is suppressed and a subsequent request under new exclusions cannot find that row. The deterministic regression verifies the new hash, safe regeneration and unchanged-context cache replay.

## Task Commits

1. **Task 1 RED: Cached consent races** — `e7c26473` (`test`)
2. **Task 1 GREEN: Shared cached consent recheck** — `330ca337` (`fix`)
3. **Task 2 RED: Uncited exclusion races** — `17ea060b` (`test`)
4. **Task 2 GREEN: Complete exclusion identity and response guards** — `2e1bce10` (`fix`)

## Verification

```text
mvn -o -B -f server/pom.xml test -Dtest=SummaryServiceTest,SummaryCacheWinnerOptOutTest,SummaryCachePayloadTest -Dnexachat.test.build-directory=target/gsd-summary-worker
```

- Task 1 RED: 35 tests, exactly 3 new consent replay failures; existing tests passed.
- Task 1 GREEN: 35 tests, no failures or errors.
- Task 2 RED: 6 opt-out tests, exactly 5 new exclusion race failures; legacy visible-participant winner guard passed. The generation fixture was corrected to return a real mocked save outcome so RED demonstrated missing suppression rather than an unstubbed cache mock.
- Final GREEN: **44 tests, 0 failures, 0 errors, 0 skipped**: `SummaryServiceTest` 36; `SummaryCacheWinnerOptOutTest` 6; `SummaryCachePayloadTest` 2.
- Regressions cover consent epoch movement on direct and parked-waiter replay; unchanged consent response locking; mutable exclusion snapshot isolation; uncited overview movement during direct cache lookup, generation, concurrent save and waiting; late post-purge fill identity; complete order-independent exclusion hashes; valid LOCAL replay during exclusion movement.
- Existing concurrency, timeout, leader Error wake-up, interrupt, consent sentinel, provider-failure fallback and payload tests remain passing.
- No live AI or database was used. The root orchestrator owns the PostgreSQL `SummaryApiIT`/`SummaryScopeApiIT` and integrated backend gate in plan 01-03.

## Files Created/Modified

- `SummaryService.java` — cached consent authorization, immutable exclusion capture, complete external hash identity and persisted exclusion response checks.
- `SummaryServiceTest.java` — consent replay and parked-waiter regressions, complete sorted hash coverage, LOCAL compatibility.
- `SummaryCacheWinnerOptOutTest.java` — uncited provenance races and late-fill/new-context separation; existing visible-participant guard preserved.

## Deviations from Plan

- Execution ownership was reassigned from the root to this scoped executor. The root retains shared STATE, ROADMAP, requirements and integration evidence updates to avoid concurrent metadata writes.
- Corrective GREEN commits use `fix` because this plan repairs existing authorization behavior.

## Issues Encountered

None affecting delivery. Existing JDK agent, deprecated API and unrelated unchecked warnings were left unchanged.

## User Setup Required

None.

## Next Phase Readiness

Summary production changes are committed and ready for the root's integrated backend/API verification. No production stubs, new endpoints, schema changes or unresolved implementation blockers were introduced.

## Self-Check: PASSED

- All three modified files and this SUMMARY exist.
- All four task commits were verified with `git cat-file -e <hash>^{commit}`.
- The final focused offline gate passed all 44 tests.
