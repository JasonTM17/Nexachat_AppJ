---
phase: 01-ai-query-lifecycle
reviewed: 2026-11-21T18:08:30Z
initial_reviewed: 2026-11-21T17:44:24Z
depth: standard
files_reviewed: 7
initial_files_reviewed: 6
files_reviewed_list:
  - android/app/src/main/java/com/nexachat/di/SummaryModule.java
  - android/app/src/main/java/com/nexachat/summary/SummaryRepository.java
  - android/app/src/main/java/com/nexachat/summary/SummaryViewModel.java
  - android/app/src/main/java/com/nexachat/feature/ask/AskViewModel.java
  - android/app/src/test/java/com/nexachat/summary/SummaryRepositoryTest.java
  - android/app/src/test/java/com/nexachat/summary/SummaryViewModelTest.java
  - android/app/src/test/java/com/nexachat/feature/ask/AskViewModelTest.java
original_findings:
  critical: 2
  warning: 0
  info: 0
  total: 2
findings:
  critical: 0
  warning: 0
  info: 0
  total: 0
status: clean
closure_commit: 3f024bda
---

# Phase 1: Android AI Code Review Report

**Reviewed:** 2026-11-21T18:08:30Z (closure); initial review 2026-11-21T17:44:24Z
**Depth:** standard
**Files Reviewed:** 7 (initial scope 6; closure additionally inspected the DI binding)
**Status:** clean — both historical blockers verified closed

## Summary

Initially reviewed completed Android changes in `95c9ae86`, `6b02b9ff`, `b78a2884` and `d66b33af`, with execution handoff `36dcb61d`. The two defects below were subsequently fixed in `3f024bda`; independent closure review verified the current repository, ViewModel, Hilt binding and deterministic regressions. `6a0dfac6` records the executor's evidence. No open finding remains in the assigned scope. The original findings are preserved as history.

Cache payloads remain bounded to 100 entries, and latest-fill registrations are compare-removed on completion/cancellation or cleared on invalidation; no cache bookkeeping defect was established within this review. The reported 108 scoped JVM tests were not rerun. Findings are based on the reachable call chain and explicit queue ordering. No production source was edited and no commit was created.

## Narrative Findings (AI reviewer)

## Historical Critical Issues (resolved)

### CR-01: Summary cache survives account changes and bypasses the new caller's access scope

**Classification:** BLOCKER

**Resolution:** CLOSED in `3f024bda`, independently verified 2026-11-21T18:08:30Z.

**File:** `D:/@University/Nexachat_AppJ/android/app/src/main/java/com/nexachat/summary/SummaryRepository.java:53-60`

**Related locations:** `SummaryRepository.java:124-128`, `di/SummaryModule.java:46-49`, `summary/AiConsentRepository.java:146-158`, `core/network/SessionEvents.java:24-30`.

**Issue:** `SummaryRepository` is a process singleton and its cache key contains conversation/scope/count/language/zone/day, without user or session identity. Neither the repository nor its provider subscribes to session termination or account changes. Its only external cache-clear caller is `AiConsentRepository`, which clears when the published consent boolean changes. A logout followed by login with the same consent boolean leaves existing summaries and pending fills eligible for the next account. A cache hit returns before calling the authenticated API, so server membership, admission-time and per-user UNREAD filtering are bypassed. In a conversation shared by accounts A and B, B can receive A's summary, including history from before B's admission or A's unread window. Closing the old sheet does not clear this singleton.

**Reproduction:** In one process, fill a Summary cache entry as account A; emit logout and establish account B with the same AI consent value; request the same conversation and request key within five minutes. The old cached response is delivered with no new API call. The same ordering with a pending A response can fill the singleton after logout because no session event advances `cacheEpoch`.

**Fix:** Bind Summary cache identity and pending-fill eligibility to a session generation/user identity, or synchronously advance the epoch and clear cache on session closing/logout/account changes. Register that fence at the singleton data boundary so it survives sheet destruction and covers pending requests. Ensure a newly established account starts with an empty or separately keyed cache. Add a controlled-session regression that fills as A, terminates the session, establishes B with equal consent, and asserts a network request rather than A's cached summary; also finish an A request after termination and assert no fill or old success.

### CR-02: A Summary queued for UI publication survives cache invalidation

**Classification:** BLOCKER

**Resolution:** CLOSED in `3f024bda`, independently verified 2026-11-21T18:08:30Z.

**File:** `D:/@University/Nexachat_AppJ/android/app/src/main/java/com/nexachat/summary/SummaryViewModel.java:174-178`

**Related locations:** `SummaryViewModel.java:220-229`, `SummaryRepository.java:116-121,217-235`, `di/SummaryModule.java:27-31`, `feature/summary/SummaryBottomSheetFragment.java:138-146,296-298`.

**Issue:** Repository completion validates `cacheEpoch` while invoking `callback.onSuccess`, but the production main executor posts that callback's state mutation to a Handler queue. After the repository callback returns, `clearCache()` can invalidate the epoch before the queued mutation runs. The ViewModel's publication check validates only query generation and conversation; neither changes on cache/consent invalidation. The queued response therefore publishes READY and saves the old summary ID after it has been invalidated. An old timezone error queued before invalidation can similarly start a fresh retry. The sheet's consent observer only updates mode widgets, and switching consent waits for server confirmation before `viewModel.refresh()`; it does not invalidate already queued Summary state when the cache is cleared. The repository's monitor ordering stops old fills but does not extend to the actual UI update.

**Reproduction:** Use a real `SummaryRepository` with the existing controlled Retrofit call and a manually drained main executor. Start a ViewModel request, complete the call successfully so READY is queued, invoke `repository.clearCache()` without starting a new query, then drain the executor. Current code publishes the invalidated summary and sets `summary_last_id`. Repeat with a queued `INVALID_TIME_ZONE` error to observe an obsolete retry. This is different from the existing test, which clears the cache before the repository response arrives.

**Fix:** Carry the request's repository epoch through the callback boundary and validate it again at ViewModel publication, including cache-hit responses and timezone retries. Alternatively expose synchronous invalidation events that advance the ViewModel generation before any queued state can run. On invalidation, cancel active work and clear obsolete READY state and saved summary ID. Keep mutations on the main executor while the invalidation fence becomes visible immediately. Add the precise completion-then-clear-then-drain regression and verify no READY state, saved ID or timezone retry from the old epoch.

## Closure verification

- **CR-01 — session lifetime:** Production `SummaryModule.provideSummaryRepository` now injects `SessionEvents`. The singleton synchronously closes admission and invalidates epochs for session closing, logout and reauthentication; session availability opens admission and clears previous account state even without an intervening logout. Pending old handles retain their old epoch and cannot fill or deliver old success. The three controlled-session tests cover equal-consent A→B, late fills/closed admission, direct replacement and reauthentication.
- **CR-02 — actual publication:** Each ViewModel subscribes to synchronous invalidation, immediately increments an `AtomicLong` query generation, and performs main-executor publication while holding the repository monitor and comparing both query identity and cache epoch. This orders publication against clears. Network success, cache-hit success, timezone retry and already-published READY/saved-ID cleanup have precise regressions.
- **New request before queued cleanup:** `start` detects the moved epoch and removes the old saved ID itself; its new generation supersedes obsolete cleanup. The dedicated regression confirms the new handle is not canceled and its result/ID remain current.
- **Listener lifetime:** `onCleared` first sets the volatile `cleared` fence, advances generation, cancels active work and removes the invalidation listener. Both publication and cleanup reject cleared ViewModels, including a listener callback already present in a CopyOnWrite iteration. The queued-cleanup-after-clear regression covers this path. Repository session subscriptions intentionally match the process singleton lifetime.
- **Evidence:** Existing XML reports under `android/app/build/test-results/testDebugUnitTest/` were read without rerunning tests: **17 scoped suites, 118 tests, 0 failures, 0 errors, 0 skipped**. The initial test count and findings above describe the earlier review state. Closure source cross-references additionally include `android/app/src/main/java/com/nexachat/di/SummaryModule.java`.

---

_Reviewer: gsd-code-reviewer_
_Depth: standard_
