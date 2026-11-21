---
phase: 01-ai-query-lifecycle
reviewed: 2026-11-21T17:58:09Z
depth: standard
files_reviewed: 8
files_reviewed_list:
  - server/src/main/java/com/nexachat/summary/SummaryService.java
  - server/src/test/java/com/nexachat/summary/SummaryServiceTest.java
  - server/src/test/java/com/nexachat/summary/SummaryCacheWinnerOptOutTest.java
  - server/src/main/java/com/nexachat/summary/SummaryExecution.java
  - server/src/main/java/com/nexachat/summary/SummaryResponseWriter.java
  - server/src/main/java/com/nexachat/summary/SummaryController.java
  - server/src/main/java/com/nexachat/summary/SummaryConsentRecheck.java
  - server/src/test/java/com/nexachat/summary/SummaryResponseBoundaryApiIT.java
findings:
  critical: 0
  warning: 0
  info: 0
  total: 0
original_findings:
  critical: 1
  warning: 0
  info: 0
  total: 1
status: clean
---

# Phase 1: Backend Summary Code Review Report

**Reviewed:** 2026-11-21T17:58:09Z
**Depth:** standard
**Files Reviewed:** 8
**Status:** clean

## Summary

Reviewed the completed fixes in `330ca337` and `2e1bce10`, their preceding regression changes, and the `01-04-SUMMARY.md` handoff. Cross-referenced the narrow response path through `SummaryConsentRecheck`, `SummaryCacheRepository`, `SummaryController`, `SummaryResponse`, the window reader and the provider's exclusion filter. No source was modified or committed. The reported 44 scoped tests were not rerun.

The service-level replay/exclusion defects and the HTTP serialization boundary defect are now closed. The final narrow review inspected the stable response-boundary implementation in `cad19b24` and the regression source already available in the workspace. No active findings remain. The original finding below is retained as history, not an outstanding ship blocker. No tests were rerun by this reviewer.

## Narrative Findings (AI reviewer)

## Critical Issues

### CR-01: Summary releases its authorization fence before storing and serializing the response — CLOSED

**Classification:** BLOCKER

**Status:** CLOSED by `cad19b24`; closure independently verified against the response wiring, transaction boundaries and deterministic regression source. The issue and reproduction below describe the original implementation.

**File:** `D:/@University/Nexachat_AppJ/server/src/main/java/com/nexachat/summary/SummaryService.java:418-424`

**Related locations:** `SummaryService.java:448-450,477-517`, `SummaryConsentRecheck.java:74-84`, `SummaryController.java:32-42`.

**Issue:** `SummaryConsentRecheck.verifyUnchanged` opens a `TransactionTemplate` only around its locked consent SELECT and closes the transaction before it returns. Neither the cached response nor the generated response is serialized in that transaction. `SummaryController` returns a detached `SummaryResponse`, which Spring serializes after service completion. A consent revocation or exclusion change committed after the last check but before serialization therefore cannot suppress that response. The complete exclusion hash protects subsequent cache lookups, not the already selected in-memory overview.

The own-generation path exposes an earlier variant: consent is checked at line 448 before the cache write, but after `saveIfAbsent` reports `inserted=true` or `replace` returns, consent is not checked again; the check at lines 508-511 applies only to a concurrent cached winner. A revocation committed during the save window can therefore return successful LLM output even before service completion. The cache requester's shared row lock proves the account is active, but it does not compare the generation's expected consent epoch. The final exclusion check at lines 513-514 is also an unlocked read and ends before JSON serialization. This affects direct cached replay, parked waiter replay, concurrent winner, refresh and freshly generated output.

**Reproduction:** For the generated path, let the real consent recheck see `(true, epoch=0)`. In the controlled `saveIfAbsent` or `replace` response hook, move persisted consent to `(false, epoch=1)` and return an inserted entry. Current code returns LLM output because `cached=false` skips the last consent recheck. For the HTTP boundary, obtain a valid cached or generated service response, commit consent revocation or an opt-out affecting an uncited overview author, then serialize that response. The selected overview is still serialized. An API regression can wrap the service completion in a spy hook that commits the mutation before returning to the controller.

**Fix:** Carry the expected consent snapshot and immutable complete exclusion identity in a non-public Summary execution result. Introduce a response writer that acquires user consent `FOR SHARE`, then conversation `FOR SHARE` in the established user-to-conversation order, revalidates the expected consent and exclusions, and selects/serializes the JSON bytes inside that transaction. The conversation lock must conflict with the existing V46 opt-out revision trigger. Keep all HTTP I/O after transaction completion. Preserve the intended LOCAL behavior: local summaries do not require external consent or exclusion equality. Apply the final boundary consistently to cache hits, waiters, winners, refresh and own-generation output, and add deterministic regressions for post-check consent movement and mutation between service completion and serialization. Correct the current consent-recheck documentation, which claims the response was serialized before its lock was released.

## Confirmed service-level closures

- **Cached consent replay:** Direct lookup, leader re-probe, parked waiter re-probe and final lookup call the common `cachedResponse` builder, which verifies persisted consent for the actual returned generation. The direct cache and parked-waiter regressions in `SummaryServiceTest` exercise epoch movement and unchanged replay.
- **Complete immutable exclusion identity:** `Set.copyOf` at `SummaryService.java:181` freezes the expected set. Flight, external lookup and external store use the same complete set. The hash at lines 679-682 length-prefixes a tag/count and sorted UUIDs; the order-independent regression covers the empty, singleton and multi-user sets. LOCAL and empty-set compatibility are retained.
- **Late fill after purge:** A fill that lands under the old exclusion key cannot match a lookup under a different current set. Final service checks suppress the old output, and the controlled late-fill regression verifies new-key regeneration followed by unchanged-context replay.
- **Concurrent winner and waiter:** Winner payloads are checked against current complete exclusions and the citation/participant guards; parked waiters retain their expected set and pass through the same replay builder. The uncited overview regressions cover lookup, generation, save winner and waiter movement.

These service-level closures remain in place; the final response writer also closes CR-01's HTTP boundary.

## CR-01 closure evidence

- **Expected context survives selection:** `SummaryExecution` carries the actual consent snapshot captured for the window/provider preparation, immutable complete exclusions and the selected `SummaryResponse`. Direct replay, leader/waiter replay, concurrent winner, fresh generation and refresh all return this execution context. The snapshot is not freshly sampled after generation.
- **Own insert and refresh are rechecked:** `storeGenerated` receives the generation snapshot and unconditionally verifies unchanged consent after storing, including successful own insertion, refresh and concurrent winners. `SummaryServiceTest` adds deterministic own-insert/refresh save hooks that move consent and require suppression.
- **The real endpoint owns the final fence:** `SummaryController` calls `summarizeForResponse` and delegates to `SummaryResponseWriter`. For LLM output the writer acquires the user consent SHARE lock, then the conversation SHARE lock, verifies the expected consent and complete persisted exclusions, and calls `ObjectMapper.writeValueAsBytes` in that same transaction. The existing V46 opt-out trigger updates the parent conversation and therefore conflicts with the conversation lock. Missing user/conversation rows reject publication.
- **Serialization and transport are separated:** HTTP status, content headers, output stream write and flush occur after the transaction completes. The boundary regression blocks actual network output and proves the connection lease is returned and consent/opt-out mutations can commit while that output remains blocked.
- **Compatibility and observability:** LOCAL execution clears external context and serializes without requiring external consent/exclusion equality. The writer serializes the original selected response, preserving metadata and cache labels, and records the existing summary metric once on successful endpoint selection. The preliminary consent-recheck documentation now assigns final serialization ownership to the writer.
- **Regression evidence:** The fully read `SummaryResponseBoundaryApiIT` contains 14 cases: fresh/cache/refresh crossed with revoke/regrant/opt-out (9), LLM/LOCAL public JSON compatibility (2), real PostgreSQL blocking during JSON for revoke/opt-out (2), and transport outside the transaction (1). The executor reported 46 scoped unit tests plus all 14 boundary cases passing. The parent subsequently reported the final combined gates passing: 147 backend unit tests / 18 suites, 81 backend API tests / 12 suites, and 118 Android tests / 17 suites. Those runs were not repeated by this reviewer.

---

_Reviewer: gsd-code-reviewer_
_Depth: standard_
