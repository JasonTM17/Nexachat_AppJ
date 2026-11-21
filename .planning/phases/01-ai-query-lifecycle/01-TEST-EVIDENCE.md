# AI query verification evidence

**Updated:** 2026-11-21 (UTC)
**Status:** Final executable gates passed; independent phase verification follows.

## Final gates

| Gate | Suites | Tests | Failures / errors / skips | Reports |
|------|--------|-------|---------------------------|---------|
| Backend AI unit and lexical benchmark | 18 | 147 | 0 / 0 / 0 | `server/target/gsd-ai-final/surefire-reports` |
| PostgreSQL and HTTP/API | 12 | 81 | 0 / 0 / 0 | `server/target/gsd-ai-final/failsafe-reports` |
| Android Ask, Summary and Summary UI JVM | 17 | 118 | 0 / 0 / 0 | `android/app/build/test-results/testDebugUnitTest` |
| **Total** | **47** | **346** | **0 / 0 / 0** | |

Maven finished with exit 0 and BUILD SUCCESS in 3 minutes 56 seconds, at `2026-11-22T01:33:00+07:00` (`2026-11-21T18:33:00Z`). The final Android gate finished with BUILD SUCCESS in 56 seconds after the last Android implementation commit. Counts were independently aggregated from each `TEST-*.xml` report, including every selected suite.

## Exact backend command

```powershell
$env:IT_DB_URL = 'jdbc:postgresql://127.0.0.1:55439/nexachat_gsd_ai'
$env:IT_DB_USER = 'nexachat'
# IT_DB_PASSWORD was injected from the dedicated disposable container without printing it.
mvn -o -B -f server/pom.xml verify `
  '-Dtest=AskServiceTest,AskServiceStampedeTest,AskServiceFallbackTest,RagRetrievalServiceTest,RagBenchmarkEvaluationTest,RagOutputValidatorTest,RagPromptBuilderTest,RagOptOutTest,RagLlmAnswerServiceFallbackTest,SummaryServiceTest,SummaryCacheWinnerOptOutTest,SummaryCachePayloadTest,SummaryProviderSelectorTest,LlmSummaryProviderTest,LlmSummaryProviderFallbackTest,LlmSummaryOptOutTest,LlmOutputValidatorTest,CitationGuardTest' `
  '-Dit.test=RagContextRaceIT,AskApiIT,RagCitationHashIT,AskEditUncitedCacheIT,AskRecallUncitedCacheIT,AskOptOutUncitedCacheIT,SummaryApiIT,SummaryScopeApiIT,SummaryOptOutIT,SummaryResponseBoundaryApiIT,ConversationAiOptOutIT,E2eeFlowIT#aiEndpointsRefuseEncryptedConversations' `
  '-Dnexachat.test.build-directory=target/gsd-ai-final'
```

Raw final Maven log: `.tmp_gsd_ai_final.log`. Source compiled for Java 21 using the available Java 23 runtime. PostgreSQL 16.16 ran in the dedicated `nexachat-gsd-ai-postgres` container bound only to loopback port 55439. Flyway validated all 44 migration files through version 46 and Hibernate schema validation succeeded. Provider traffic used loopback fake servers and synthetic message fixtures. The Summary executor used and then dropped its separate private boundary database; the parent gate used only `nexachat_gsd_ai`.

## Exact Android command

With `ANDROID_HOME=D:\Android\Sdk`:

```powershell
android\gradlew.bat -p android :app:testDebugUnitTest `
  --tests 'com.nexachat.feature.ask.*' `
  --tests 'com.nexachat.summary.*' `
  --tests 'com.nexachat.feature.summary.*' `
  --console=plain
```

Raw final Android log: `android/app/build/gsd-summary-review-final.log`. Tests use controlled Retrofit calls, queued executors, local test servers and synchronous session events.

## PostgreSQL/API suite breakdown

| Suite | Tests |
|-------|-------|
| E2eeFlowIT selected AI endpoint case | 1 |
| AskApiIT | 19 |
| AskEditUncitedCacheIT | 1 |
| AskOptOutUncitedCacheIT | 1 |
| AskRecallUncitedCacheIT | 1 |
| RagCitationHashIT | 2 |
| RagContextRaceIT | 7 |
| ConversationAiOptOutIT | 6 |
| SummaryApiIT | 14 |
| SummaryOptOutIT | 5 |
| SummaryResponseBoundaryApiIT | 14 |
| SummaryScopeApiIT | 10 |
| **Total** | **81** |

## Lexical benchmark

```text
rag-eval: conversations=32 messages=6400 lookupQuestions=192 recall@3=1.000 recall@5=1.000 mrr=1.000 citationValidity=1.000 zeroLlm=70.0% meanInputChars=1640.6 baselineChars=7951.4 reduction=79.4%
```

This is an existing synthetic lexical corpus, not a live-provider evaluation. The 192 questions are evaluations inside one benchmark test and are not added to the 346 JUnit test count.

## Deterministic regression evidence

- RAG RED reproduced obsolete revision sharing, unbounded follower waiting and ignored interruption. The final 13 stampede tests cover work sharing, isolated question/history/language/consent/admission/source keys, timeout recovery, interruption and late leader cleanup.
- Seven PostgreSQL `RagContextRaceIT` tests pause actual fake provider work during uncited edit, recall, opt-out and expiry, and verify cache state, revision rollback/no-op/new-message behavior, expiry-capped TTL and source commit ordering.
- Seven new `AskApiIT` cases cover source changes after execution and expiry crossing JSON serialization; the final 19-test suite also preserves consent, cache, member, validation, citation and nonblocking HTTP behavior.
- Summary initial RED reproduced three cached-consent replay gaps and five exclusion-race failures. The final immutable complete exclusion identity separates lookup, flights and late old fills, including uncited senders and concurrent winners.
- Summary response-boundary RED reproduced two own insert/refresh consent races and nine actual HTTP 403-versus-200 failures. The final 14 boundary API tests cover fresh/cached/refresh revoke/regrant/uncited-opt-out suppression, unchanged public JSON, LOCAL replay, actual requester/conversation lock blockers during serialization and pooled connection/lock release under HTTP backpressure.
- Android cache and queued lifecycle regressions were committed RED before GREEN. The review continuation reproduced seven additional session/queued-invalidation failures before fixing session epochs, cache-hit and network publication, timezone retry, READY state, saved IDs, listener cleanup and invalidation followed by a new request. All 118 final scoped tests passed.

## Requirement coverage

| Requirement | Implementation and executable evidence |
|-------------|----------------------------------------|
| AI-01 | AskService visibility-scoped key and conditional single-flight ownership; AskServiceStampedeTest |
| AI-02 | Timed monotonic interruptible follower wait and compare-remove recovery; AskServiceStampedeTest |
| AI-03 | V46 source revisions, expiry-aware cache, locked Ask JSON selection, Summary consent/full exclusions and locked JSON; RagContextRaceIT, AskApiIT, SummaryServiceTest, SummaryCacheWinnerOptOutTest, SummaryResponseBoundaryApiIT |
| AI-04 | Ask generation/conversation/logout publication checks; AskViewModelTest and Ask repository/UI suites |
| AI-05 | Summary latest-fill/cache/session epochs, synchronous invalidation and guarded UI publication; SummaryRepositoryTest, SummaryViewModelTest and Summary UI suites |
| AI-06 | Success/cache/extractive/provider fallback/error/consent/member/E2EE/citation/scope coverage in all selected service/provider/API and Android suites |
| AI-07 | Updated architecture and testing docs, four plan summaries, this measured evidence and independent 01-VERIFICATION.md |

## Additional workflow checks

- `git diff --check` passed.
- GSD schema drift check reported no drift. Real Flyway/Hibernate validation ran in the final PostgreSQL gate.
- Codebase drift check was skipped because this scoped project has no STRUCTURE.md.
- No prior GSD phases exist for an additional prior-phase regression gate.
- Nyquist validation is disabled in the project configuration.
- Review history and closure evidence are recorded separately in the RAG, Android and Summary review artifacts.

## Limits

Verification covers synthetic service/HTTP/database behavior and Android JVM lifecycle behavior. No paid/live AI provider, production deployment, Android device/emulator run or deployed multi-node load test was performed. Work sharing is per application instance; PostgreSQL provides cache freshness and response locking.
