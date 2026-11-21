---
phase: 01-ai-query-lifecycle
plan: "01"
status: incomplete
subsystem: api
tags: [rag, postgres, concurrency, cache, expiry]
requires: []
provides:
  - Transactional RAG source revision and expiry-bound cache fills
  - Bounded interruptible Ask followers with admission and context isolation
affects: [ai-query-lifecycle, rag]
tech-stack:
  added: []
  patterns: [source revision snapshots, compare-and-remove single-flight cleanup]
key-files:
  created:
    - server/src/main/resources/db/migration/V46__rag_context_revision.sql
  modified:
    - server/src/main/java/com/nexachat/rag/RagQuestionCache.java
    - server/src/main/java/com/nexachat/rag/AskService.java
    - server/src/main/java/com/nexachat/rag/RagProperties.java
    - server/src/main/resources/application.yml
    - server/src/test/java/com/nexachat/rag/AskServiceStampedeTest.java
    - server/src/test/java/com/nexachat/rag/AskServiceTest.java
    - server/src/test/java/com/nexachat/rag/AskServiceFallbackTest.java
    - server/src/test/java/com/nexachat/rag/RagBenchmarkEvaluationTest.java
key-decisions:
  - Source transactions advance a dedicated AI revision; query-time expiry is captured separately.
  - Cache fills acquire the conversation row before the cache advisory lock.
  - A timed-out follower returns NOT_FOUND without repeating provider work and detaches only its observed flight.
requirements-completed: [AI-01, AI-02]
completed: null
---

# Phase 1 Plan 1: RAG query coordination execution handoff

**Tasks 1 and 2 implement revision/expiry-bound RAG caching and bounded, visibility-scoped single-flight queries. Root-owned Task 3 remains pending.**

## Completed Work

1. V46 adds `conversations.ai_context_version`, versions ask cache rows and invalidates legacy rows. Source triggers advance the revision for relevant TEXT changes, opt-out changes and E2EE changes. No-op source updates do not advance it; cascading conversation deletion is harmless.
2. `ContextSnapshot(revision, expiresAt)` captures the current revision and earliest future expiry of eligible TEXT in one SQL statement. Exact and semantic cache reads require the current revision; writes reject moved revision, passed expiry or invalid citations, and clamp their TTL to source expiry.
3. Ask keys include membership admission and the captured context alongside question/history hash, user, conversation, language and consent epoch. Followers use timed interruptible `get`; timeout never repeats provider work. Conditional cleanup protects replacement flights. Every leader/follower result rechecks its captured context before selection.

## Task Commits

| Task | Commit | Outcome |
|------|--------|---------|
| 1 | `1a09f370` | Transactional revision and expiry/cache guards |
| 2 RED | `891aadb5` | Reproduced revision isolation, timeout and interruption failures; introduced wait configuration contract |
| 2 GREEN | `5594e50e` | Bounded scoped flights and explicit revision test fixtures |
| 3 | Pending | Root owns `RagContextRaceIT` and its API/cache mutation race verification |

## Verification Evidence

- Task 1 baseline: `AskServiceTest,AskServiceFallbackTest`: 17 tests, zero failures.
- Task 2 RED: three new regressions failed against the original coordination behavior (one failed revision-isolation assertion, two follower timeout errors).
- Task 2 GREEN: `AskServiceStampedeTest,AskServiceTest,AskServiceFallbackTest,RagBenchmarkEvaluationTest`: 31 tests, zero failures (13 stampede, 13 service, 4 provider fallback, 1 benchmark).
- PostgreSQL gate: `RagCitationHashIT`: two tests, zero failures. Fresh database applied Flyway V46 successfully (`46|t`); edited/unedited citation hashes and source-trigger/cascade cleanup remained valid.
- Lexical benchmark unchanged: 32 conversations / 6,400 messages / 192 lookup questions; recall@5 1.000, MRR 1.000, citation validity 1.000, zero-LLM 70.0%, input reduction 79.4%.
- Tests used build directory `target/gsd-rag-worker`. The PostgreSQL gate used private database `nexachat_gsd_rag_worker` in the existing disposable container on port 55439; that private database was dropped afterward. Root database `nexachat_gsd_ai` was untouched.

## Remaining Work

- Root must complete and commit Task 3 `RagContextRaceIT` (uncited edit/recall/opt-out/expiry racing generation, unchanged replay, rollback and cache locking), then replace this handoff status with complete evidence.
- AI-03 and AI-06 are deliberately not marked complete by this executor; their full integration evidence depends on Task 3 and the final phase gates.
- Global STATE, ROADMAP and requirement updates remain owned by root as assigned.

## Deviations

- Existing service/fallback/benchmark fixtures were updated to explicit context revisions. Sentinel -1 is never treated as a production wildcard.
- One initial GREEN failure was in Mockito re-stubbing a blocked handler via `when`; switching that test fixture to `doAnswer` resolved it. All focused regressions then passed.
- This is a truthful execution handoff for root-owned Task 3, not a completed plan claim.

## Self-Check: PASSED for Tasks 1 and 2

All implementation files and the three task commits exist. No stub/TODO or unexpected tracked deletion was introduced. No full-plan completion claim is made before Task 3 verification.
