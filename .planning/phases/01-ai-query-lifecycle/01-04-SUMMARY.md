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
  - Authorization locks retained until public JSON serialization completes, with HTTP I/O afterward
affects: [ai-query-lifecycle, summary, privacy]
tech-stack:
  added: []
  patterns: [immutable exclusion snapshots, sorted length-prefixed cache identity, response selection rechecks]
key-files:
  created:
    - server/src/main/java/com/nexachat/summary/SummaryExecution.java
    - server/src/main/java/com/nexachat/summary/SummaryResponseWriter.java
    - server/src/test/java/com/nexachat/summary/SummaryResponseBoundaryApiIT.java
  modified:
    - server/src/main/java/com/nexachat/summary/SummaryService.java
    - server/src/test/java/com/nexachat/summary/SummaryServiceTest.java
    - server/src/test/java/com/nexachat/summary/SummaryCacheWinnerOptOutTest.java
    - server/src/main/java/com/nexachat/summary/SummaryController.java
    - server/src/main/java/com/nexachat/summary/SummaryConsentRecheck.java
    - server/src/test/java/com/nexachat/testutil/ItPhoneRanges.java
key-decisions:
  - "Suppress changed external exclusion context with existing AI_CONSENT_REQUIRED instead of adding regeneration retries."
  - "Preserve existing contentHash helpers, the empty-exclusion hash and LOCAL cache identity."
  - "Bind the complete sorted exclusion UUID set even when excluded authors have no citation or participant entry."
  - "Serialize external Summary JSON while holding user then conversation SHARE locks; release the transaction before HTTP I/O."
requirements-completed: [AI-03, AI-06]
duration: 56min
completed: 2026-11-22
---

# Phase 1 Plan 4: Summary Replay Consent and Exclusions Summary

**External Summary replay and JSON serialization check persisted consent and complete exclusion context; stale uncited overview output is suppressed, late fills remain isolated and slow HTTP clients hold no authorization locks.**

## Performance

- **Started:** 2026-11-21T17:33:50Z
- **Completed:** 2026-11-21T18:30:04Z
- **Tasks:** 2/2 plus CR-01 review gap fix
- **Implementation/test files:** 9, including 3 created

## Accomplishments

- Every direct, leader re-probe, waiter re-probe and final cached replay goes through the shared response builder, which invokes `SummaryConsentRecheck.verifyUnchanged` for the actual returned generation and the window-read consent snapshot.
- The immutable expected opt-out set is captured before external lookup or provider work. Its complete sorted UUID identity is included in the LLM flight, lookup and store hashes. The historical empty-set hash and all LOCAL/provider-failure fallback keys remain compatible.
- Persisted exclusion equality is checked before external cache output is selected, before a generation is saved, after a concurrent save returns, and before the final response is constructed. Citation and participant checks remain additional protections.
- An external result produced under changed context raises the existing typed `AI_CONSENT_REQUIRED`. This adds no retry loop and preserves the existing bounded flight waits and provider routing.
- A concurrent old fill that lands after purge can retain its old-key row, but its response is suppressed and a subsequent request under new exclusions cannot find that row. The deterministic regression verifies the new hash, safe regeneration and unchanged-context cache replay.
- The controller passes a package-private `SummaryExecution` to `SummaryResponseWriter`. Its immutable consent/exclusion facts remain available through the final transaction: user SHARE lock, conversation SHARE lock, persisted authorization validation and public DTO serialization. HTTP writes and metrics occur after the transaction completes.

## Task Commits

1. **Task 1 RED: Cached consent races** — `e7c26473` (`test`)
2. **Task 1 GREEN: Shared cached consent recheck** — `330ca337` (`fix`)
3. **Task 2 RED: Uncited exclusion races** — `17ea060b` (`test`)
4. **Task 2 GREEN: Complete exclusion identity and response guards** — `2e1bce10` (`fix`)
5. **Initial plan metadata** — `0d9d196f` (`docs`)
6. **CR-01 RED: Own-store and HTTP serialization races** — `a3605c1a` (`test`)
7. **CR-01 GREEN: Authorization fence through serialization** — `cad19b24` (`fix`)

## Verification

```text
mvn -o -B -f server/pom.xml test -Dtest=SummaryServiceTest,SummaryCacheWinnerOptOutTest,SummaryCachePayloadTest -Dnexachat.test.build-directory=target/gsd-summary-worker
```

- Task 1 RED: 35 tests, exactly 3 new consent replay failures; existing tests passed.
- Task 1 GREEN: 35 tests, no failures or errors.
- Task 2 RED: 6 opt-out tests, exactly 5 new exclusion race failures; legacy visible-participant winner guard passed. The generation fixture was corrected to return a real mocked save outcome so RED demonstrated missing suppression rather than an unstubbed cache mock.
- Initial plan GREEN: **44 tests, 0 failures, 0 errors, 0 skipped**: `SummaryServiceTest` 36; `SummaryCacheWinnerOptOutTest` 6; `SummaryCachePayloadTest` 2.
- Regressions cover consent epoch movement on direct and parked-waiter replay; unchanged consent response locking; mutable exclusion snapshot isolation; uncited overview movement during direct cache lookup, generation, concurrent save and waiting; late post-purge fill identity; complete order-independent exclusion hashes; valid LOCAL replay during exclusion movement.
- Existing concurrency, timeout, leader Error wake-up, interrupt, consent sentinel, provider-failure fallback and payload tests remain passing.
- The initial unit gate used no live AI or database. The review addendum below uses a private disposable PostgreSQL database and a synthetic in-process provider. The root orchestrator owns the broader `SummaryApiIT`/`SummaryScopeApiIT` and integrated backend gate in plan 01-03.

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

## Review Addendum: CR-01 Serialization Boundary

**Finding:** `01-SUMMARY-REVIEW.md` identified that preliminary service consent locks ended before cache storage and Spring's later JSON serialization. Own inserted and refresh results also skipped the final service consent recheck.

### Automatic gap fix

**[Rule 1 - Bug] Retain Summary authorization through JSON serialization**

- **Fix:** Added internal `SummaryExecution` carrying the authorizing consent snapshot, immutable complete expected exclusions and selected public response. Every service return path carries the same facts used for its cache/generation identity.
- **Own-store gap:** Both inserted and refresh results now run the post-store consent check with the actual generation snapshot, just as a concurrent winner does.
- **Final HTTP boundary:** `SummaryResponseWriter` acquires requester `FOR SHARE`, then conversation `FOR SHARE`, revalidates persisted consent/exclusions and serializes only `SummaryResponse` while the transaction remains open. The V46 opt-out revision trigger updates the locked conversation parent, ordering opt-out commits against serialization.
- **LOCAL:** Internal authorization fields are cleared for LOCAL responses; local serialization does not require external consent or exclusion equality.
- **HTTP/metrics:** HTTP status, headers, bytes and flush occur after transaction completion. Existing Summary request metrics use the existing `ObservabilityMetrics` collaborator because the controller now calls the internal execution path.
- **Documentation:** Corrected `SummaryConsentRecheck` and service comments to distinguish preliminary checks from the actual serialization fence.
- **Commits:** `a3605c1a` RED; `cad19b24` GREEN.

### Focused regression evidence

- **RED unit:** 38 `SummaryServiceTest` cases, exactly 2 new assertion failures for consent movement inside `saveIfAbsent(inserted=true)` and refresh `replace`.
- **RED API:** 9 actual controller/database cases, exactly 9 `expected 403 / actual 200` failures, with zero errors/skips. Cases cover fresh, cached and refresh results against revoke, revoke/regrant and opt-out affecting an author used only in uncited overview context.
- **GREEN unit:** **46/46** passing: service 38, cache-winner opt-out 6, payload 2.
- **GREEN API:** **14/14** passing in `SummaryResponseBoundaryApiIT`:
  - The 9 service-return-to-serialization mutation cases suppress external JSON with `AI_CONSENT_REQUIRED` and no secret overview bytes.
  - 2 real PostgreSQL lock probes observe `pg_blocking_pids` for requester revocation and the opt-out trigger while the serializer holds the transaction. Each mutation completes after JSON exists and the transaction releases its locks.
  - 2 JSON compatibility cases compare the public DTO against HTTP bytes for unchanged external output and LOCAL replay after consent/exclusion movement. No internal snapshot fields enter public JSON.
  - 1 blocked-client-write case verifies the writer's actual pooled lease is closed and both consent revocation and opt-out complete while HTTP output remains parked.
- Reports: `server/target/gsd-summary-worker/surefire-reports` and `failsafe-reports`.
- Private database: `nexachat_gsd_summary_boundary` in the existing disposable PostgreSQL 16 container on `127.0.0.1:55439`, dropped after verification; root database `nexachat_gsd_ai` was not modified by this executor.

```text
mvn -o -B -f server/pom.xml verify -Dtest=SummaryServiceTest,SummaryCacheWinnerOptOutTest,SummaryCachePayloadTest -Dit.test=SummaryResponseBoundaryApiIT -Dnexachat.test.build-directory=target/gsd-summary-worker
mvn -o -B -f server/pom.xml test-compile failsafe:integration-test failsafe:verify -Dit.test=SummaryResponseBoundaryApiIT -Dnexachat.test.build-directory=target/gsd-summary-worker
```

The unit portion of the first command passed all 46 cases. The final second command passed all 14 API cases after correcting fixture epoch updates and normalizing JSON numeric nodes; production source was unchanged between these runs.

### Test setup adjustments

- Registered the new API suite's non-overlapping `+84712` phone range in `ItPhoneRanges`.
- Used the container-provided password without printing it and unwrapped the Summary service AOP target for deterministic Mockito completion hooks.
- Regrant fixtures explicitly advance the application-managed consent epoch; JSON equality compares parsed JSON on both sides.

No production placeholders, new network endpoints, database columns or unresolved CR-01 implementation work remain. The root owns the combined final gate, reviewer confirmation and shared planning-state updates.

## Self-Check: PASSED

- Initial plan files and four task commits were verified with `git cat-file -e <hash>^{commit}`.
- The initial focused offline gate passed all 44 tests.
- All review addendum files exist; RED `a3605c1a` and GREEN `cad19b24` commits were verified.
- Parsed report XML confirms 38 service, 6 winner opt-out, 2 payload and 14 boundary API cases with zero failures/errors/skips.
