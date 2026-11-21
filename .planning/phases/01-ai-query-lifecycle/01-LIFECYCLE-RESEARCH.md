# Phase 1: AI query lifecycle - Android and Summary research

**Researched:** 2026-11-21
**Domain:** Android request lifecycle, process-local summary cache, backend summary coordination
**Confidence:** HIGH for source findings; environment availability is a bounded local probe
**Scope:** Android Ask/Summary and backend Summary only. Backend Ask/RAG cache research belongs to the orchestrator.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- Keep the current API, retrieval/provider choices, consent behavior and offline fallbacks.
- Scope work to concrete gaps discovered in existing code and tests.
- Treat "bao phủ luồng" as completing and testing existing request lifecycle branches, not building a new AI product.
- Use GSD research -> plan -> check -> execute -> verify with local atomic commits.

### Agent's Discretion

Implementation patterns and test selection follow the codebase and discovered evidence. No external live provider call is required.

### Deferred Ideas

No additional deferred ideas were stated in CONTEXT.md.
</user_constraints>

## Summary

The backend Summary service already implements a reusable bounded single-flight pattern with a monotonic deadline, three leadership/wait rounds, interruption handling, cache re-probes, and unconditional waiter wake-up even if a leader throws an Error. These behaviors already have deterministic tests. [VERIFIED: SummaryService.java:197-250,294-309; SummaryServiceTest.java:559-1005]

The clearest missing lifecycle guards are in Android Summary: clearing a ViewModel does not invalidate queued callbacks; clearing the repository cache does not invalidate pending fills; and an older network request can overwrite a newer refresh in the same cache key. Android Ask already invalidates request tokens on cancel, draft changes, close, logout and clear, but its tests primarily use immediate execution and therefore do not exercise the queued callback boundary. [VERIFIED: SummaryViewModel.java:119-121; SummaryRepository.java:69-77,115-118,154-162; AskViewModel.java:62-71,104-109,136-151,222-228,292-298; AskViewModelTest.java]

**Primary recommendation:** Retain the existing frameworks. Add explicit cache epochs and latest-fill identity in SummaryRepository, invalidate SummaryViewModel on clear, serialize callback side effects through its main executor, and add controlled executor/fake-call regression tests for both sheets. [VERIFIED: existing generation/executor pattern in AskViewModel.java:155-228 and AiConsentRepository.java:146-160; missing guards listed below]

## Architectural Responsibility Map

| Capability | Primary tier | Secondary tier | Evidence / recommendation |
|------------|--------------|----------------|---------------------------|
| Active query UI and transient Ask thread | Android ViewModel | Repository cancellation | Token validation belongs at actual UI publication, after executor queuing. [VERIFIED: AskViewModel.java:222-228] |
| Summary process cache freshness | Android repository | Consent repository | Synchronize epoch checks and cache mutation on the cache lock. [VERIFIED: SummaryRepository.java:115-118,139-163; AiConsentRepository.java:150-155] |
| Duplicate provider work and bounded waiting | Backend service | Database cache | Reuse SummaryService's existing monotonic wait pattern. [VERIFIED: SummaryService.java:197-250] |
| Consent-valid external summary replay | Backend Summary service | Consent repository | Apply the existing response recheck to replayed external rows, not only new generation. [VERIFIED: SummaryService.java:339-360,437; SummaryConsentRecheck.java:75-87] |

## Project Constraints (from AGENTS.md)

No AGENTS.md was found at the project root or in the repository file inventory. No project-local .codex/skills or .agents/skills directory was found in the checked locations. [VERIFIED: Get-Content/Test-Path and rg --files --hidden probes on 2026-11-21]

## Standard Stack

Use the repository-pinned versions; no new packages are needed. These are the versions declared in this checkout, not claims that they are the globally newest releases. [VERIFIED: android/gradle/libs.versions.toml; android/app/build.gradle; server/pom.xml]

| Component | Version | Role |
|-----------|---------|------|
| Android Gradle plugin / Gradle wrapper | 8.9.3 / 8.14.3 | Android build |
| Android SDK / Java compile source | compileSdk 36 / Java 17 | Android compile inputs |
| AndroidX lifecycle | 2.8.7 | SavedStateHandle, ViewModel, LiveData |
| Retrofit / OkHttp / Moshi | 2.11.0 / 4.12.0 / 1.15.1 | Cancellable network boundary and response models |
| JUnit / Mockito / arch core testing | 4.13.2 / 5.14.2 / 2.2.0 | JVM tests, callbacks and LiveData control |
| Backend Java / Spring Boot | 21 / 3.5.16 | Existing summary service and tests |

## Architecture Patterns

### 1. Validate the request identity at the publication point

AskViewModel captures a generation token and executes callbacks through `mainExecutor`; the token is checked inside the queued task, immediately before updating the state. This correctly suppresses a callback that was received before cancel but executed after cancel. Keep this pattern for Summary and test it with a manually drained executor. [VERIFIED: AskViewModel.java:176-211,222-228]

Both sheets use fragment-scoped ViewModels constructed from a conversation argument (`new ViewModelProvider(this)`). Normal navigation creates a separate sheet for each conversation. Neither ViewModel currently observes conversation changes in SavedStateHandle. A callback only checks generation, not conversation identity. If implementing defensive handling of changed handles for AI-04/05, capture the conversation identity and verify it at publication; clear any prior Ask thread before starting under a different conversation. Do not describe handle mutation as a verified normal navigation path. [VERIFIED: AskBottomSheetFragment.java:52-55,77; SummaryBottomSheetFragment.java:85-88,131; AskViewModel.java:162,224; SummaryViewModel.java:146,199]

### 2. Cache invalidation must fence pending writes

Use a repository cache epoch captured atomically when a network request starts. `clearCache()` increments that epoch while clearing the map. Accept a fill only if its epoch still matches; perform check and put under the same lock. The epoch also provides a place to suppress old callbacks after consent invalidation. [VERIFIED: current clear/put are separate operations in SummaryRepository.java:115-118,154-162; consent changes invoke clearCache in AiConsentRepository.java:150-155]

For same-key requests, track which network request is the newest eligible fill. Older calls may still complete for their own callers, but must not replace a newer request's cache entry. Track in-flight identity rather than an unbounded permanent per-key generation map; clean registration conditionally so older completion cannot remove the newer registration. [VERIFIED: current unconditional same-key cache.put in SummaryRepository.java:156; current cache is bounded at 100 entries but no pending-fill identity exists]

### 3. Backend bounded single-flight signatures to reuse

The existing Summary API includes a constructor accepting `long singleFlightWaitMs`; unit tests pass a small value. Production obtains `llm.timeout-ms`, `llm.local-timeout-ms`, and `llm-fallback.timeout-ms` with defaults 30,000 / 60,000 / 30,000 ms, then sums them. [VERIFIED: SummaryService.java:105-146,148-165]

The orchestration computes one deadline for all rounds:

```java
// Existing SummaryService.java:197,294-309
long waitDeadlineNanos = System.nanoTime()
        + TimeUnit.MILLISECONDS.toNanos(singleFlightWaitMs);
private boolean awaitWinner(CompletableFuture<Void> flight, long waitDeadlineNanos)
```

`awaitWinner` calls `flight.get(remainingMs, TimeUnit.MILLISECONDS)`. Timeout or failed leader may re-coalesce only while the shared deadline remains; interruption restores the interrupted flag and throws the typed `INTERNAL_ERROR`. Leader cleanup removes its own map entry before completing the future in `finally`, including on Error. Re-check the cache after each wake and before new provider work. [VERIFIED: SummaryService.java:197-250,294-309]

## Concrete Findings and Regression Scenarios

| Finding | Evidence | Minimal fix / deterministic test |
|---------|----------|----------------------------------|
| Summary callback can publish after ViewModel clear | `onCleared()` only cancels the handle; `publish()` still accepts the unchanged token. [VERIFIED: SummaryViewModel.java:119-121,197-209] | Increment generation before cancellation. Queue success/error, clear via ViewModelStore, drain executor, assert no READY/error or saved summary id from old callback. |
| Consent clear can be followed by stale cache refill | Response writes unconditionally; clear only empties map. [VERIFIED: SummaryRepository.java:72-77,115-118,154-156; AiConsentRepository.java:150-155] | Hold response A; clear cache; deliver A; assert next request goes to API and old id is absent. Add epoch captured before call creation/registration and checked at fill. |
| Old same-key response overwrites a newer refresh | Both responses use unconditional `cache.put`. [VERIFIED: SummaryRepository.java:51,76,156] | Start old A and newer refresh B; deliver B then A; assert cache keeps B. Repeat with B failure to define latest-request policy explicitly. |
| Summary timezone retry mutates lifecycle on callback thread | `onError` directly invokes `start` before entering `mainExecutor`. [VERIFIED: SummaryViewModel.java:162-167] | Route error handling and retry through the main executor with token check there. Queue INVALID_TIME_ZONE, dismiss/new request, then drain; assert no stale retry. Existing tests only cover already-stale callback invocation. |
| Summary obsolete returned handle is not canceled | Registration only handles token equality and has no `else`. [VERIFIED: SummaryViewModel.java:177-179] | Match Ask's conditional registration/cancel pattern; test callback/observer reentrancy with request superseded before repository returns its handle. |
| Ask queued lifecycle edge lacks test coverage | ViewModel tests construct with Runnable::run; cancellation tests also let repository suppress callback first. [VERIFIED: AskViewModelTest.java:148-191,createViewModel; FakeAskApi.java] | Receive result so repository queues publication, then cancel/edit/close/logout/clear before draining; assert draft/thread unchanged and current result wins. Existing token code should pass most cases. |
| External Summary cache replay can bypass post-generation consent guard | `replayIfCached` returns `cachedResponse` without `verifyUnchanged`; only `generateAndStore` invokes the recheck. User settings update advances epoch but does not delete these rows. [VERIFIED: SummaryService.java:339-360,437; UserSettingsService.java:109-111; User.java:245-249] | Invoke existing recheck before returning a cached LLM result using the read consent snapshot. Test consent e0 at window read, moved e1 before cached replay, verify suppression/no provider. Include waiter replay after leader finishes. |

## Existing Backend Summary Coverage to Preserve

`SummaryServiceTest` already covers identical concurrency (559), different windows (615), failed leader replacement (677), slow leader with one shared deadline (773), Error wake-up (856), interrupted waiter (931), provider fallback replay inside/after circuit window (1010,1079), and consent changes before/during generation (165-392). Retain these and use them as executable analogues for Ask coordination tests. [VERIFIED: SummaryServiceTest.java]

External prompt filtering re-reads conversation opt-outs immediately before prompt construction, and generation consent is rechecked before storage. Do not replace these behaviors. An initial `optedOut` set is reused after waiting in SummaryService, so a broader opt-out replay race audit remains prudent if the orchestrator adds a persisted AI context revision. [VERIFIED: LlmSummaryProvider.java:130-144,196-203; SummaryService.java:180,199,245,437]

## Don't Hand-Roll

| Problem | Use existing facility | Evidence |
|---------|-----------------------|----------|
| Lifecycle cancellation | Retrofit Call.cancel plus ViewModel generation check | [CITED: official Retrofit 2.11.0 Call.java; VERIFIED: AskRepository.java:106-131, AskViewModel.java:222-228] |
| Main-thread publication | Injected summary_main Executor | [VERIFIED: SummaryModule.java:26-32] |
| Cache locking | Existing synchronized cache critical sections, extended with epoch/fill checks | [VERIFIED: SummaryRepository.java:115-162] |
| Latch/executor tests | Existing fakes, Mockito, ArchTaskExecutor delegate and CountDownLatch helpers | [VERIFIED: FakeAskApi.java; LiveDataTestExecutor.java; LiveDataTestSupport.java] |

## Environment Availability

| Dependency | Probe result | Execution guidance |
|------------|--------------|--------------------|
| Java | PATH Java/Javac 23; JDK 20,23,24 under Program Files/Java | Java 21+ backend requirement met; select a Gradle-supported installed JDK explicitly if needed. [VERIFIED: java -version; directory probe] |
| Maven | Present at D:/ADMIN/apache-maven-3.9.11-bin/apache-maven-3.9.11/bin/mvn.cmd | Backend tests available. [VERIFIED: Get-Command] |
| Android SDK | ANDROID_HOME and ANDROID_SDK_ROOT unset; no android/local.properties; default ADMIN AppData Android-36 path absent | SDK may exist elsewhere. Discover path before Android Gradle build; this probe does not establish that no SDK is installed. [VERIFIED: environment/Test-Path probes] |
| Gradle | Wrapper files committed; ADMIN default Gradle distribution cache absent in checked path | May download wrapper or reuse another cache; run wrapper after SDK discovery. [VERIFIED: wrapper properties and path probe] |
| adb | Present on PATH at D:/platform-tools-latest-windows/platform-tools/adb.exe | Not required for deterministic JVM tests. [VERIFIED: Get-Command] |
| Context7 CLI | Not on PATH in checked session | Used official Android docs and Retrofit source instead; no package installed. [VERIFIED: Get-Command ctx7 probe] |

Focused Android command after prerequisites are resolved:

```powershell
android\gradlew.bat -p android :app:testDebugUnitTest --tests 'com.nexachat.feature.ask.*' --tests 'com.nexachat.summary.*' --tests 'com.nexachat.feature.summary.*'
```

## Validation Architecture

Detailed Nyquist scaffold omitted because `workflow.nyquist_validation` is explicitly false. The concrete deterministic scenarios above remain required acceptance evidence. [VERIFIED: .planning/config.json]

## Security Domain

This phase changes lifecycle/caching rather than authentication or cryptography. Preserve existing member-only server routes, E2EE gating, consent epochs and valid-source citations; stale callbacks/cache fills must not re-expose results after access or consent invalidation. These are project-specific controls, not a claim of full ASVS compliance. [VERIFIED: SummaryController.java:24-37; SummaryWindowService.java:41-72; SummaryService.java:473-558; .planning/REQUIREMENTS.md]

## Sources and Confidence

- [VERIFIED: repository source] Android files and server Summary files named inline, read on 2026-11-21; line numbers refer to the pre-implementation checkout.
- [CITED: https://developer.android.com/reference/androidx/lifecycle/ViewModel] Fetched HTTP 200 on 2026-11-21; `onCleared` lifecycle cleanup documented.
- [CITED: https://raw.githubusercontent.com/square/retrofit/2.11.0/retrofit/src/main/java/retrofit2/Call.java] Fetched HTTP 200 on 2026-11-21; cancellation attempts to cancel an in-flight call, and async execution notifies callbacks.

## Assumptions Log

No new package/framework capability is locked from training knowledge. Conversation argument mutation is a defensive scenario, explicitly not claimed as the normal navigation flow. Android SDK location remains an environment discovery task.

## Research Result

Research complete; no production files were modified and no commits were made by this researcher. Backend Ask/RAG integration details remain owned by the orchestrator.
