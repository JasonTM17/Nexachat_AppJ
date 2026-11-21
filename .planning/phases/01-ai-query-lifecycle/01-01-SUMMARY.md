---
phase: 01-ai-query-lifecycle
plan: "01"
status: complete
subsystem: api
tags: [rag, postgres, concurrency, cache, expiry]
requires: []
provides:
  - Transactional source revisions and expiry-bound cache fills
  - Bounded interruptible visibility-scoped Ask followers
  - Consent and source fencing through HTTP JSON serialization
affects: [ai-query-lifecycle, rag]
tech-stack:
  added: []
  patterns: [source snapshots, conditional flight cleanup, shared response locks]
key-files:
  created:
    - server/src/main/resources/db/migration/V46__rag_context_revision.sql
    - server/src/test/java/com/nexachat/rag/RagContextRaceIT.java
  modified:
    - server/src/main/java/com/nexachat/rag/RagQuestionCache.java
    - server/src/main/java/com/nexachat/rag/AskService.java
    - server/src/main/java/com/nexachat/rag/AskExecution.java
    - server/src/main/java/com/nexachat/rag/AskResponseWriter.java
    - server/src/main/java/com/nexachat/rag/RagProperties.java
    - server/src/main/resources/application.yml
    - .env.example
    - server/src/test/java/com/nexachat/rag/AskServiceStampedeTest.java
    - server/src/test/java/com/nexachat/rag/AskServiceTest.java
    - server/src/test/java/com/nexachat/rag/AskServiceFallbackTest.java
    - server/src/test/java/com/nexachat/rag/RagBenchmarkEvaluationTest.java
    - server/src/test/java/com/nexachat/rag/AskApiIT.java
key-decisions:
  - Source transactions advance a dedicated AI revision; expiry is captured separately.
  - Cache writes acquire the conversation row before the cache advisory lock.
  - A timed-out follower returns NOT_FOUND without repeating provider work.
  - JSON selection holds consent then source shared locks; socket writes run after commit.
requirements-completed: [AI-01, AI-02, AI-03]
completed: 2026-11-21
---

# Phase 1 Plan 1: RAG query coordination

**Identical current-context asks share work with a bounded wait; obsolete context cannot refill cache or pass the JSON response boundary.**

## Completed tasks

1. Flyway V46 adds `conversations.ai_context_version`, versions Ask cache rows and invalidates legacy rows. Triggers advance the revision for relevant TEXT, opt-out and E2EE mutations, while rollback and no-op updates preserve valid snapshots. `ContextSnapshot(revision, expiresAt)` captures source identity and the earliest eligible future expiry. Exact/semantic reads and guarded writes require current source and citation validity; TTL is capped at source expiry.
2. Ask keys include user, conversation, question/history hash, language, consent epoch, membership admission and source snapshot. Followers use one monotonic timed interruptible wait. Timeout returns NOT_FOUND without another provider call and removes only the observed flight; interruption restores the flag; failed/late leaders cannot remove replacements. Every caller checks the captured context before selecting a response.
3. PostgreSQL regressions pause actual fake provider work during uncited edit, recall, opt-out and expiry. They verify obsolete output suppression, no stale refill, unchanged replay, source commit ordering, rollback/no-op/new-message revisions and expiry-limited cache.

## Review gap closure

The independent review found E2EE sampling and service-to-serialization gaps. Snapshot capture now precedes the E2EE gate. `AskExecution` carries the original source snapshot to `AskResponseWriter`, which takes the user consent shared lock followed by the conversation shared lock and serializes under that transaction. A final expiry check discards bytes if time crossed the source expiry during serialization. HTTP I/O runs outside the transaction. The public JSON schema is unchanged. Both findings are closed in `01-RAG-REVIEW.md`.

## Task commits

| Task | Commit | Result |
|------|--------|--------|
| 1 | `1a09f370` | Transactional revision and expiry/cache guards |
| 2 RED | `891aadb5` | Reproduced revision sharing, unbounded wait and ignored interruption |
| 2 GREEN | `5594e50e` | Bounded context-scoped flights |
| Initial handoff | `754297e3` | Recorded completed executor tasks and pending root test task |
| 3 | `2bfab6f3` | Real PostgreSQL source/cache races |
| Review fixes | `9704cdd2` | E2EE capture order and locked JSON response selection |

## Verification

- Initial focused GREEN: 31 tests, zero failures/errors/skips.
- RAG unit/benchmark gate before review: 65 tests, zero failures/errors/skips in `server/target/gsd-rag-final/surefire-reports`.
- PostgreSQL/API gate before review: 24 tests, zero failures/errors/skips in `server/target/gsd-rag-final/failsafe-reports`, including 7 `RagContextRaceIT` cases.
- Review gate after final fixes: `AskServiceTest` 14 and 7 selected `AskApiIT` cases, zero failures/errors/skips in `server/target/gsd-response-check`. The selected API cases cover uncited edit/recall/opt-out/expiry and E2EE after service return, valid unchanged JSON, and expiry crossing JSON serialization.
- The first review run exposed two fixture defects: GROUP E2EE violated the production DIRECT-only schema; nanosecond instants could precede PostgreSQL's rounded microsecond expiry. The corrected fixtures use a valid DIRECT conversation and millisecond-aligned expiry. A resumed run also required restoring the disposable container's actual test password; no application code changed for authentication.
- Flyway V46 applied and validated against private synthetic PostgreSQL 16 databases on loopback port 55439.
- Lexical benchmark: 32 conversations, 6,400 messages, 192 questions; recall@3/5, MRR and citation validity 1.000; zero-LLM 70.0%; input reduction 79.4%.
- Final combined backend regression gate is owned by 01-03 and recorded in `01-TEST-EVIDENCE.md`.

## Deviations

- Updated existing service/fallback/benchmark fixtures to explicit context snapshots; revision -1 is never a production wildcard.
- Closed both evidenced response-boundary bugs as automatic fixes, preserving API modes and request/response fields.

## Limits

Coordination is per application instance. PostgreSQL still enforces cache context freshness across instances. Verification uses synthetic messages and fake providers; no live-provider, deployment, device or multi-node load claim is made.

## Self-Check: PASSED

All three tasks, listed implementation files and task commits exist. Scoped regression reports are green. Parent-wide requirements and final phase verification remain tracked by 01-03.
