# Backend Ask/RAG concurrency research

**Date:** 2026-11-21
**Scope:** AI-01, AI-02, AI-03 and backend verification for AI-06.

## Evidence

- AskService.computeOnce uses CompletableFuture.join(): followers cannot be interrupted and have no deadline. Cleanup only runs when the leader exits. AskServiceStampedeTest verifies coalescing and consent epochs but lacks stalled-leader/interruption tests.
- AskKey scopes by conversation/user/question hash/language/consent, but omits admittedFrom and conversation context. A rejoining member or a query started after edit/recall/opt-out can join obsolete work.
- RagQuestionCache.contentVersion uses MAX(TEXT.seq). Edits, recalls and opt-out changes leave that value unchanged. BEFORE_COMMIT listeners purge the cache, but an older LLM generation can write a new row after the purge using uncited stale prompt context.
- Cache rows persist cited hashes, not the complete source revision. citationsValid cannot detect an uncited context mutation. Existing AskEditUncitedCacheIT / AskRecallUncitedCacheIT / AskOptOutUncitedCacheIT cover completed-cache purges, not a generation racing those mutations.
- conversations.change_version is already monotonic for message sync but does not advance for AI opt-out mutations; avoid relying on it alone.
- AskResponseWriter serializes under the user consent lock and releases the connection before writing HTTP bytes. Preserve that behavior.
- Expired messages are excluded by JdbcRagMessageIndex using database now(), even before retention cleanup. Context freshness must account for expiry, including uncited messages.

## Recommended design

1. Add a dedicated monotonic conversation AI context revision in an additive Flyway migration (next available V46). Advance it transactionally on relevant message insert/update/delete and opt-out changes; clear or version existing ask cache rows. Database enforcement also covers direct SQL maintenance and fixture mutations. Avoid expensive hashing of all conversation text per ask.
2. Bind the in-flight key to admittedFrom and the current context revision. Carry the same expected context into retrieval/cache writes, rather than sampling only after question embedding.
3. Persist and check the context version in exact and semantic cache reads. Serialize cache writes against source mutation using a compatible conversation row lock; maintain a single consistent lock order with the existing advisory cache lock.
4. Handle passage of expires_at (no mutation fires a trigger) through context snapshot/expiry guards and expire cache entries no later than relevant source expiry. Do not allow an obsolete flight to refill the cache.
5. Add configurable bounded follower waiting (safe default below Android's 130s read timeout), use future.get with a monotonic deadline, restore interrupt and use the existing INTERNAL_ERROR error convention. On timeout return the existing NOT_FOUND fallback, detach only the observed stalled flight using compare-and-remove so later requests can recover. Never remove a newer flight or start a second provider call inside the timed-out request.
6. Keep duplicate coalescing within the existing user/consent scope; preserve API shape, per-request rate-limit charging, history fingerprint and language isolation.

## Verification

- Latch-controlled AskService tests: one provider call for identical asks; independent questions, histories, languages, membership admissions, consent epochs and context versions; timeout without follower provider work; interruption; failed/stalled leader cleanup and subsequent recovery.
- PostgreSQL integration: old uncited context generation cannot refill the cache after edit, recall, opt-out or expiry; valid unchanged cache still replays; new messages invalidate; triggers/revision behave under transactions and rollback.
- Existing backend RAG + summary unit suites and the 192-question lexical benchmark.
- Existing AskApiIT, SummaryApiIT, SummaryScopeApiIT, citation hash, membership/E2EE and consent tests on a dedicated database.

## Environment

JDK 23 and Maven 3.9.11 are installed. Docker engine is reachable; a dedicated postgres:16-alpine container is being prepared on 127.0.0.1:55439, database nexachat_gsd_ai. No external provider key is required.
