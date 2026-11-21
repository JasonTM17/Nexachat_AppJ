---
phase: 01-ai-query-lifecycle
verified: 2026-11-21T18:44:39Z
status: passed
score: 12/12 must-haves verified
requirements_verified: 7/7
overrides_applied: 0
---

# Phase 1: AI query lifecycle verification

**Phase goal:** Current AI queries complete reliably without obsolete in-flight work, invalid cache context or late Android callbacks affecting their results.

**Status:** passed

**Re-verification:** No — initial independent goal-backward verification.

## Verification method

Verified production source, its callers, actual SQL, controlled-race test assertions and XML reports independently of SUMMARY claims. All five ROADMAP success criteria are retained. PLAN truths that clearly restate those criteria are mapped to the same row; seven additional detailed truths remain explicit below. No override was used.

There is no project AGENTS.md or project-local skill directory. The phase has no declared human-check or shell-probe contract. The final verifier performed additional fast pure/read-only checks without rerunning the broad suites or starting services.

## Goal achievement

### Observable truths

| # | Truth and contract source | Status | Evidence |
|---|---------------------------|--------|----------|
| 1 | Identical concurrent asks avoid duplicate provider work; visibility/context changes isolate queries. SC1; 01-01 truth 1. | VERIFIED | `AskService` captures admission, source snapshot and consent before forming its immutable `AskKey`; `putIfAbsent` shares only the exact key. `hashInput` includes follow-up history. The 13 `AskServiceStampedeTest` cases assert real provider/embedding call counts and separate users, questions, histories, languages, consent epochs, admission, revision and expiry. |
| 2 | A stalled/failed Ask leader cannot indefinitely park followers; interruption and recovery work. SC2; 01-01 truth 2. | VERIFIED | `await` uses one `System.nanoTime` deadline and timed interruptible `get`; timeout returns NOT_FOUND without computing again. Interruption restores the flag. RuntimeException/Error complete the leader exceptionally; cleanup and timeout both compare-remove the observed future. Tests cover timeout, flag preservation, later recovery and a late old leader unable to remove its replacement. |
| 3 | Cache invalidation and consent/citation safeguards survive concurrent source changes, including uncited edit/recall/opt-out/expiry. SC3; 01-01 truth 3. | VERIFIED | V46 triggers advance transactional `ai_context_version`; cache reads join the current version. Cache writes lock user then parent conversation before the cache advisory lock, recheck context and citation hashes, and cap TTL at source expiry. `AskExecution` carries context into the locked JSON writer; expiry is checked again after serialization. Seven real PostgreSQL `RagContextRaceIT` cases assert zero obsolete cache rows and a fresh subsequent answer/replay, plus rollback/no-op/new-message and commit ordering. Final `AskApiIT` has 19 cases. |
| 4 | Android Ask and Summary reject obsolete callbacks and preserve current query state. SC4; 01-02 truth 1. | VERIFIED | Both ViewModels validate token and conversation inside the main executor publication task. Ask logout sets a volatile invalidation fence before main cleanup; changed conversations drop prior turns. Summary uses an atomic token plus repository epoch and monitor at publication. Cancel/dismiss/clear/scope/conversation/reentrant/queued regressions assert state, saved IDs, requests and history, not merely cancellation method counts. |
| 5 | Focused backend/Android/integration tests cover existing AI flows and identified races; documentation accurately records evidence/configuration/limits. SC5; 01-03 truths 1–2. | VERIFIED | Independently aggregated 147 backend unit tests in 18 suites, 81 PostgreSQL/API tests in 12 suites and 118 Android JVM tests in 17 suites; all have zero failures/errors/skips. Source matches the documented per-node sharing, configured wait bounds, source revision/expiry, JSON locks and session/cache epochs. Provider/citation, membership, E2EE, scopes, validation and fallback suites are present in the final report sets. |
| 6 | Clearing Summary cache fences old pending fills; a newer refresh owns cache eligibility. 01-02 truth 2. | VERIFIED | `SummaryRepository` serializes epoch changes, latest-fill registration/removal and success delivery under its monitor. Completion fills only when compare-removal succeeds and its epoch is current. A failed/finished newer request never reinstates older eligibility. The singleton is actually provided with synchronous SessionEvents; session closing/logout/reauthentication/availability clear cache and fence pending work. Controlled-call tests exercise A→B sessions with unchanged consent, pending A completion, distinct keys, cancellation, enqueue failure and refresh ordering. |
| 7 | An obsolete invalid-timezone error cannot start a retry after cancellation, supersession or invalidation. 01-02 truth 3. | VERIFIED | Summary retry is executed only inside guarded `publish`; the single `zoneFallback` flag prevents retry loops. Real-repository queued tests complete the error, clear cache, then drain and assert no additional API call. Superseded-scope and dismiss cases are also asserted. |
| 8 | Every milestone requirement is traceable to actual implementation and executable evidence. 01-03 truth 3. | VERIFIED | All AI-01…AI-07 occur in PLAN frontmatter and REQUIREMENTS.md; the coverage table below accounts for each. Four committed plan summaries, runnable architecture/testing references and the measured evidence document exist. This report supplies the final independent verification artifact; pending global checkboxes are parent-owned bookkeeping. |
| 9 | External Summary cache replay cannot bypass its captured consent epoch. 01-04 truth 1. | VERIFIED | `cachedResponse` calls production `SummaryConsentRecheck` for the read snapshot and creates `SummaryExecution` with that snapshot. The controller calls `summarizeForResponse`, and `SummaryResponseWriter` checks the snapshot under the user SHARE lock through actual JSON serialization. Post-store checks also protect own insert/refresh and concurrent winners. Unit and fresh/cached/refresh API revoke/regrant regressions exercise these branches. |
| 10 | External Summary lookup, flight and store identity binds the complete immutable exclusion set, including uncited authors. 01-04 truth 2. | VERIFIED | The service captures `Set.copyOf` once. `flightKey`, external lookup and external store use the same content-hash overload; its length-prefixed fields include a sorted nonempty UUID exclusion set. LOCAL and historical empty-set keys retain their identity. Tests cover mutable input sets, uncited senders, late post-purge fills and new-key separation. |
| 11 | Changed exclusions cannot authorize cached, newly generated, waiter or concurrent-winner external output. 01-04 truth 3. | VERIFIED | `verifyExclusionsUnchanged` runs on replay, before store and before final generated/winner selection. `SummaryExecution` freezes the expected set. At HTTP selection, the writer takes user SHARE then conversation SHARE; V46 opt-out changes must update that same parent. It checks exact current exclusions and serializes while both locks remain held. The 14 `SummaryResponseBoundaryApiIT` cases include nine actual HTTP suppression cases and real `pg_blocking_pids` probes for both mutation locks. |
| 12 | LOCAL fallback replay and bounded Summary single-flight behavior remain intact. 01-04 truth 4. | VERIFIED | LOCAL responses discard external metadata and skip external policy checks. Provider-failure LOCAL replay is limited to the circuit-breaker window; refresh bypasses replay/flight waiting. Summary waiters share one deadline, bounded re-coalescing and explicit interrupt handling; leader cleanup completes waiters even on error. Existing SummaryService and provider tests still execute. The boundary API independently compares unchanged LOCAL/LLM JSON and proves slow HTTP releases the pooled lease and both mutation locks. |

**Score:** 12/12 truths verified.

### Required artifacts

Each production artifact was checked for existence, substantive implementation and real usage; mere existence was not accepted.

| Artifact | Status | Substantive implementation and wiring |
|----------|--------|----------------------------------------|
| `server/src/main/resources/db/migration/V46__rag_context_revision.sql` | VERIFIED | Version columns, invalidation and three trigger functions; live read-only SQL confirms V46 applied and all three triggers enabled. |
| `server/src/main/java/com/nexachat/rag/RagQuestionCache.java` | VERIFIED | JDBC version/expiry queries, transactional guarded write, citation hash verification and response lock; called by AskService and AskResponseWriter. |
| `server/src/test/java/com/nexachat/rag/RagContextRaceIT.java` | VERIFIED | Uses actual fake HTTP provider, latches and PostgreSQL; final Failsafe XML contains seven passing cases. |
| `android/app/src/main/java/com/nexachat/summary/SummaryRepository.java` | VERIFIED | Real Retrofit calls, bounded cache, epochs, fill ownership, session subscriptions and invalidation listeners; Hilt provider injects SessionEvents. |
| `android/app/src/main/java/com/nexachat/summary/SummaryViewModel.java` | VERIFIED | Real request/cancellation, saved state, atomic generation and guarded main publication; Summary sheet observes and renders its state. |
| `android/app/src/test/java/com/nexachat/feature/ask/AskViewModelTest.java` | VERIFIED | Controlled API and queued executor tests assert current state/history under cancellation, logout and conversation changes; included in final Android XML. |
| `docs/quality/testing.md` | VERIFIED | Runnable exact suite filters and coverage matrix including both response-boundary APIs, E2EE and provider/citation guards. |
| `.planning/phases/01-ai-query-lifecycle/01-VERIFICATION.md` | VERIFIED | This independently authored report accounts for all contracts, source/data-flow/wiring and measured evidence. |
| `server/src/main/java/com/nexachat/summary/SummaryService.java` | VERIFIED | Real window loading, provider preparation/generation, cache lookup/store and conditional replay; controller invokes its internal execution path. |
| `server/src/test/java/com/nexachat/summary/SummaryServiceTest.java` | VERIFIED | Latch/callback-controlled consent, exclusions, waiter and store races; final XML has 38 passing cases. |
| Additional Ask/Summary execution and writer classes | VERIFIED | Internal metadata records do not alter public DTOs; both production controllers invoke their writers. JSON is serialized before transaction release, and HTTP I/O follows release. |

### Key link verification

| From → To | Status | Actual connection |
|-----------|--------|-------------------|
| AskService → RagQuestionCache | WIRED | `contextSnapshot` supplies both AskKey and generated cache metadata; `findExact`/`findCandidates`/guarded save and final context check consume it. |
| V46 → RagQuestionCache | WIRED | Migration maintains `conversations.ai_context_version`; cache SQL reads and compares that exact column and writes `rag_ask_cache.context_version`. |
| SummaryRepository ↔ AiConsentRepository | WIRED | The intended invalidation signal is implemented in consumer direction: AiConsentRepository calls `summaryRepository.clearCache()` when consent changes. Cache epochs/listeners fence repository and ViewModel work. |
| SummaryViewModel → SummaryRepository | WIRED | Calls `request`, registers/cancels returned handles conditionally, subscribes to invalidation and reads epoch under the shared publication monitor. |
| RAG architecture → AskService | WIRED | Documented key dimensions, 1…120000 ms wait bounds, timeout/interrupt behavior and snapshot selection match actual service and configuration code. |
| Testing reference → RagContextRaceIT | WIRED | Its explicit Failsafe selection runs the actual file; final XML has all seven cases. |
| SummaryService → SummaryConsentRecheck | WIRED | Cached response, pre-store generation and final stored-response paths invoke `verifyUnchanged` with the captured authorization snapshot. |
| SummaryService → SummaryCacheRepository | WIRED | Lookup, flight and store hashes use the same external exclusion fingerprint; actual cache queries/stores use those values. |
| Controllers → internal execution → writers | WIRED | AskController and SummaryController invoke internal service results and pass them to production writers rather than returning an unguarded public DTO for later Spring serialization. |
| Hilt SessionEvents → SummaryRepository → ViewModel → sheet | WIRED | Singleton provider passes SessionEvents; synchronous epoch/invalidation updates reach atomic VM fences; the sheet observes LiveData and renders response content. |

GSD artifact helpers confirm the declared source/test/document artifacts. Its mechanical key-link helper did not recognize Java symbols, SQL column wiring or semantic documentation links because it sought target path literals in the source. All eight declared connections were therefore traced manually above; those heuristic results are not evidence of orphaned code. No override is needed for the verified intended wiring.

### Data-flow trace

| Artifact | Dynamic data | Real source | Status |
|----------|--------------|-------------|--------|
| Ask endpoint | Retrieved text, answer and citations | JdbcRagMessageIndex SQL over eligible conversation messages → retrieval/provider → AskResponse → locked JSON | FLOWING |
| Summary endpoint | Scoped window, structured summary and citations | SummaryWindowService/member/consent gates → message queries and cache/provider → SummaryResponse → locked external JSON | FLOWING |
| Ask Android sheet | Turns, answers and citations | Retrofit AskApi → AskRepository callbacks → guarded AskViewModel state → sheet `response.getAnswer()` and citation adapter | FLOWING |
| Summary Android sheet | READY content, result header, scope and errors | Retrofit SummaryApi/cache → guarded SummaryViewModel state → SummaryBottomSheetFragment renders actual summary sections | FLOWING |

The in-memory indices, controlled calls and no-op consent helpers are test infrastructure; production profiles use JDBC, real Retrofit bindings and the real consent recheck. Initial empty UI states are subsequently populated by these real paths and are not stubs.

## Independent checks and executable evidence

### Fast behavioral/read-only checks run by this verifier

| Behavior | Command/result | Status |
|----------|----------------|--------|
| Compiled wait configuration clamps to a safe bound | JShell loaded the actual compiled `RagProperties`, asserted default 120000, input 0→1 and input 120001→120000; printed `WAIT_CLAMP_PASS`; exit 0 in 2.12 seconds. | PASS |
| Actual test schema contains V46 | Read-only `docker exec … psql … SELECT count(*) FROM flyway_schema_history WHERE version='46' AND success`; result `V46=1`. | PASS |
| Source revision triggers are really enabled | Read-only `pg_trigger` query for messages/opt-out/E2EE trigger names and `tgenabled='O'`; result `enabled_source_triggers=3`. | PASS |
| Executed suite reports are complete and green | Independently parsed every `TEST-*.xml` in final backend/API/Android report directories; results below. | PASS |

No declared shell probe was missing. Broad suites were not rerun during verification because final reports and corresponding assertions already cover the scoped behaviors, and additional read-only/pure checks resolve configuration/schema wiring.

### Verified report counts

| Report set | Suites | Tests | Failures | Errors | Skips |
|------------|--------|-------|----------|--------|-------|
| `server/target/gsd-ai-final/surefire-reports` | 18 | 147 | 0 | 0 | 0 |
| `server/target/gsd-ai-final/failsafe-reports` | 12 | 81 | 0 | 0 | 0 |
| `android/app/build/test-results/testDebugUnitTest` | 17 | 118 | 0 | 0 | 0 |
| **Total** | **47** | **346** | **0** | **0** | **0** |

The 192 lexical lookup questions run inside one benchmark test, not 192 extra JUnit tests. The reported recall@3/5, MRR and citation validity are 1.000 for that synthetic corpus; input reduction is 79.4% and zero-LLM use 70.0%.

## Requirements coverage

| Requirement | Source plans | Status | Evidence |
|-------------|--------------|--------|----------|
| AI-01 | 01-01, 01-03 | SATISFIED | Complete AskKey dimensions, exact flight ownership and the 13 stampede tests. |
| AI-02 | 01-01, 01-03 | SATISFIED | Monotonic timed wait, preserved interruption, conditional cleanup, failure/timeout/replacement recovery. |
| AI-03 | 01-01, 01-04, 01-03 | SATISFIED | V46 transactional source identity, guarded cache TTL/writes, citation hashes, both JSON fences, complete Summary consent/exclusion identity and real PostgreSQL mutation tests. |
| AI-04 | 01-02, 01-03 | SATISFIED | Ask current-token/conversation/logout checks at publication, prior-turn isolation and queued/reentrant regression assertions. |
| AI-05 | 01-02, 01-03 | SATISFIED | Summary generation/scope/conversation checks, latest-fill/cache/session epochs, synchronous invalidation, saved-ID/retry/cleared-VM tests. |
| AI-06 | All four | SATISFIED | Final service/provider/API/JVM suites cover success, cache, extractive/LLM/LOCAL fallback, error, consent, member, E2EE and citation flows plus all identified races. |
| AI-07 | 01-03 | SATISFIED | Accurate architecture/testing references, four plan summaries, measured evidence, and this independent report with all seven requirement mappings. |

**Orphaned requirements:** none. **Requirement score:** 7/7.

## Anti-pattern and disconfirmation checks

No production stub, orphaned critical path or unresolved source debt marker was found in the phase diff. A negated metadata safety statement in the Android summary matched the mechanical marker scan; it describes absence of introduced debt and is not an unresolved implementation task.

Disconfirmation examined three ways the narrative could mislead:

- Passing mocked cache tests alone would not prove transactional freshness. The real PostgreSQL tests assert cache rows/revisions and actual mutation blockers, and this verifier independently checked enabled schema triggers.
- Repository callback eligibility alone would not prove queued UI publication safety. The real-repository tests use completion→clear→queue-drain ordering and assert state/saved IDs/no timezone retry. Production publication takes the repository monitor and validates epoch/token again.
- A service consent check alone would not prove HTTP safety. The real controller tests mutate policy after service return, and lock tests confirm mutations block during Jackson serialization while slow HTTP holds no database lease or mutation locks.

One low-level combination remains outside the exercised error cases: direct injected Jackson serialization I/O failure. Both writers visibly unwrap the checked IOException before any candidate HTTP bytes are written; this is a test limitation rather than a missing scoped requirement. Existing typed API/provider/error branches remain covered.

## Verification limits

No required human verification item remains within this milestone's explicit synthetic/JVM contract. Device appearance, performance feel, real provider quality, deployed multi-node load and production deployment were not established and are not claimed. Work sharing is per application instance; PostgreSQL supplies cache freshness and response lock ordering.

The parent owns final STATE/ROADMAP/REQUIREMENTS and plan-03 metadata completion. Their pending progress fields do not contradict the substantive implementation and verified evidence.

## Gaps summary

No blocking gap or uncertain must-have remains. All five roadmap criteria, all additional PLAN truths and all seven requirement IDs are accounted for by actual substantive/wired code and executable evidence.

---

_Verifier: gsd-verifier_
_Verified: 2026-11-21T18:44:39Z_
