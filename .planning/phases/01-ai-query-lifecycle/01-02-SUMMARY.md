---
phase: 01-ai-query-lifecycle
plan: "02"
subsystem: android
tags: [rag, summary, retrofit, livedata, concurrency, junit]
requires:
  - phase: existing-android-ai-flows
    provides: Ask/Summary repositories, SavedStateHandle and main executor
provides:
  - Summary cache invalidation epochs and latest-request fill ownership
  - Publication-point query and conversation checks for Android AI sheets
  - Deterministic queued-executor lifecycle regression coverage
affects: [01-03, 01-04, ai-consent, android-ai-sheets]
tech-stack:
  added: []
  patterns: [cache-epoch fencing, compare-remove fill ownership, executor publication checks]
key-files:
  created: []
  modified:
    - android/app/src/main/java/com/nexachat/summary/SummaryRepository.java
    - android/app/src/main/java/com/nexachat/summary/SummaryViewModel.java
    - android/app/src/main/java/com/nexachat/feature/ask/AskViewModel.java
    - android/app/src/test/java/com/nexachat/summary/SummaryRepositoryTest.java
    - android/app/src/test/java/com/nexachat/summary/SummaryViewModelTest.java
    - android/app/src/test/java/com/nexachat/feature/ask/AskViewModelTest.java
key-decisions:
  - "A newer Summary request owns cache eligibility even if it fails; older same-epoch callers can receive their own result without filling the cache."
  - "Invalidate Ask publication immediately on receipt of logout, then clear UI state on its main executor."
  - "Requested Summary language is part of cache identity."
requirements-completed: [AI-04, AI-05, AI-06]
duration: 27min
completed: 2026-11-22
---

# Phase 1 Plan 2: Android AI Lifecycle Summary

**Summary cache epochs and current-query publication guards prevent obsolete cache fills, AI answers, saved summary IDs and timezone retries.**

## Performance

- **Duration:** 27 minutes of implementation and verification, measured from the first RED commit; initial SDK preparation excluded.
- **Started:** 2026-11-21T17:01:26Z
- **Completed:** 2026-11-21T17:28:00Z (2026-11-22 in Asia/Bangkok)
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments

- `clearCache()` advances a cache epoch and clears active fill registrations under the cache monitor. Invalidated pending responses cannot refill or emit old success. Membership loss also invalidates cached results.
- Newest-started requests retain fill ownership for each complete cache key. Older same-key callers may still complete, while older fills cannot overwrite refresh results or regain eligibility after a newer failure. Distinct keys, cancellation, synchronous enqueue failure, TTL and bounded cache behavior remain covered.
- Summary success/error/timezone retry side effects run through the main executor and validate query generation plus conversation there. Clear invalidates generation before cancellation; returned obsolete handles are canceled. A defensive conversation change drops the previous saved summary ID.
- Ask rejects queued answers after logout receipt, conversation changes, draft edits, cancel, close or clear. A changed conversation starts without previous-chat prior turns. Reentrant loading observers cannot initiate obsolete API calls, and ordinary cancellation retains valid earlier Ask turns.

## Task Commits

1. **Task 1 RED: Reproduce stale Summary fills** — `95c9ae86` (`test`)
2. **Task 1 GREEN: Fence invalidated and older Summary fills** — `6b02b9ff` (`fix`)
3. **Task 2 RED: Reproduce queued AI lifecycle races** — `b78a2884` (`test`)
4. **Task 2 GREEN: Reject obsolete callbacks at publication** — `d66b33af` (`fix`)

The root orchestrator owns global STATE/ROADMAP/REQUIREMENTS updates and the final phase metadata commit.

## Verification

- Task 1 RED: **15 tests, 5 expected failures** demonstrated invalidation/refresh-ordering/enqueue/language defects before production changes.
- Task 1 GREEN: `SummaryRepositoryTest` and `AiConsentRepositoryTest` passed.
- Task 2 RED: **31 tests, 7 expected failures** demonstrated queued clear, timezone retry, returned handle, conversation, logout and loading-observer defects.
- Final command, with `ANDROID_HOME=D:\Android\Sdk`:

  `android\gradlew.bat -p android :app:testDebugUnitTest --tests 'com.nexachat.feature.ask.*' --tests 'com.nexachat.summary.*' --tests 'com.nexachat.feature.summary.*' --console=plain`

- Final result: **BUILD SUCCESSFUL**, **17 suites / 108 tests / 0 failures / 0 errors / 0 skipped**. Reports are under `android/app/build/test-results/testDebugUnitTest/` and `android/app/build/reports/tests/testDebugUnitTest/`.
- `git diff --check` passed. No tracked files were deleted in task commits.
- Tests use controlled Retrofit fakes, mocked repositories, manually drained executors and local test servers; no live AI provider, emulator or device was needed.

## Files Created/Modified

- `SummaryRepository.java` — epochs, conditional latest-fill cleanup, single completion and requested-language cache identity.
- `SummaryViewModel.java` — generation/conversation validation, serialized retry/publication and conditional handle registration.
- `AskViewModel.java` — immediate logout fence, conversation-bound history and reentrant loading guard.
- `SummaryRepositoryTest.java` — deterministic cache invalidation, fill ordering, failure, cancellation and language tests.
- `SummaryViewModelTest.java` — queued clear/scope/conversation/timezone cases, saved-ID isolation and reentrant cancellation.
- `AskViewModelTest.java` — queued cancel/edit/close/clear/logout/conversation and loading-observer regressions.

## Decisions Made

- Keep existing Retrofit, LiveData, UI states, request DTOs, five-minute TTL and 100-entry cache bound.
- Cache invalidation is separate from supersession: old same-epoch callers may receive their own response; invalidated-epoch callers receive `UNKNOWN` rather than stale success.
- Latest-fill bookkeeping tracks active requests only; compare-remove cleanup never restores an older request as winner.
- Logout permanently fences that Ask sheet's callbacks. The existing logout flow clears/closes the sheet; a new sheet owns subsequent session requests.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Isolate requested Summary languages in cache keys**
- **Found during:** Task 1
- **Issue:** The existing key omitted `SummaryLanguage`, permitting an AUTO result to satisfy a VI/EN request in the same conversation/scope/zone.
- **Fix:** Include language in key construction, equality and hash code.
- **Files modified:** `SummaryRepository.java`, `SummaryRepositoryTest.java`
- **Verification:** Requested-language isolation regression passes.
- **Committed in:** `95c9ae86` (RED), `6b02b9ff` (GREEN)

**Total deviations:** 1 auto-fixed bug; no API or framework change.

## Issues Encountered

- The Gradle wrapper and declared Android SDK/build tools were prepared using the existing SDK at `D:\Android\Sdk`. No project SDK config or dependency declaration changed.
- One new test initially expected an empty prior-turn list, but the existing `AskRequest` contract deliberately normalizes it to `null` for JSON omission. The assertion was corrected to that established contract.
- Existing Gradle 9 deprecation, localSmoke resValue and Java deprecation warnings remain outside this plan's scope.

## User Setup Required

None. Verification is offline with fake providers.

## Next Phase Readiness

Android lifecycle requirements are ready for parent verification and consent-flow integration. Backend integration and phase-wide requirement completion remain owned by the root orchestrator.
