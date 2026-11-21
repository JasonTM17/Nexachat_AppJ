---
phase: 01-ai-query-lifecycle
reviewed: 2026-11-21T18:08:30Z
initial_reviewed: 2026-11-21T17:17:15Z
depth: standard
diff_base: cc8239df
files_reviewed: 12
initial_files_reviewed: 9
files_reviewed_list:
  - server/src/main/java/com/nexachat/rag/AskExecution.java
  - server/src/main/java/com/nexachat/rag/AskResponseWriter.java
  - server/src/main/java/com/nexachat/rag/AskService.java
  - server/src/main/java/com/nexachat/rag/RagProperties.java
  - server/src/main/java/com/nexachat/rag/RagQuestionCache.java
  - server/src/main/resources/application.yml
  - server/src/main/resources/db/migration/V46__rag_context_revision.sql
  - server/src/test/java/com/nexachat/rag/AskServiceFallbackTest.java
  - server/src/test/java/com/nexachat/rag/AskApiIT.java
  - server/src/test/java/com/nexachat/rag/AskServiceStampedeTest.java
  - server/src/test/java/com/nexachat/rag/AskServiceTest.java
  - server/src/test/java/com/nexachat/rag/RagBenchmarkEvaluationTest.java
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
---

# Phase 1: RAG Code Review Report

**Reviewed:** 2026-11-21T18:08:30Z (closure); initial review 2026-11-21T17:17:15Z
**Depth:** standard
**Files Reviewed:** 12 (initial scope 9; closure additionally inspected execution/writer/API regressions)
**Status:** clean — both historical blockers verified closed

## Summary

Initially reviewed the completed RAG backend changes since `cc8239df`, including their cache, retrieval, E2EE and HTTP response call chains. The two races below were subsequently repaired by the root orchestrator. Independent narrow source and regression review confirms both are closed at response byte selection. No open finding remains in this review's scope; the original findings are preserved as history.

The concurrent Android and Summary implementation changes and the integration test being authored by the orchestrator were excluded. No structural pre-pass was supplied. Production source was not modified, and no commit was created. Findings below are established by the explicit control flow and reachable transaction ordering; this reviewer did not run additional tests.

## Narrative Findings (AI reviewer)

## Historical Critical Issues (resolved)

### CR-01: E2EE activation can be admitted as a valid new context

**Classification:** BLOCKER

**Resolution:** CLOSED, independently verified 2026-11-21T18:08:30Z against the current response-boundary fixes.

**File:** `D:/@University/Nexachat_AppJ/server/src/main/java/com/nexachat/rag/AskService.java:153`

**Related locations:** `AskService.java:130-140`, `RagQuestionCache.java:104-121`, `JdbcRagMessageIndex.java:31-45`, `E2eeConversationService.java:63-95`.

**Issue:** The E2EE flag is checked at lines 133-137, before the context snapshot is captured at line 153. `ContextSnapshot` contains the revision and expiry only. With experimental activation enabled and the devices ready, `E2eeConversationService.setEnabled` allows enabling E2EE on a DIRECT conversation containing existing plaintext; it does not remove that history. If activation commits between the flag read and snapshot capture, the ask captures the *post-activation* revision. Retrieval still selects the existing plaintext because its SQL checks message encryption, not the conversation E2EE flag. Cache saving and the final `contextUnchanged` check both accept that new revision. The request can generate, cache and return an answer even though the conversation is now encrypted and the endpoint promises `E2EE_AI_UNAVAILABLE`. If configured, existing plaintext can also be sent to the embedding or answer provider.

**Reproduction:** Start an ask on a non-encrypted DIRECT conversation with a plaintext fact; pause after the false E2EE read. Commit successful E2EE activation, then resume snapshot capture and computation. The unchanged post-activation revision passes both fences, and the answer proceeds instead of being refused. A deterministic unit regression can switch the mocked E2EE state and revision inside the `contextSnapshot` stub, after the earlier repository read.

**Fix:** Capture the context revision before reading the E2EE gate, then verify the captured snapshot is unchanged before admitting provider work; alternatively load the E2EE flag and revision together in one SQL snapshot and reject an enabled flag from that same snapshot. Preserve membership checking before revealing the flag. Add a regression for activation between the earlier gate and context capture, asserting refusal and zero retrieval/provider/cache-fill work.

### CR-02: The context fence ends before HTTP response serialization

**Classification:** BLOCKER

**Resolution:** CLOSED, independently verified 2026-11-21T18:08:30Z against the current response-boundary fixes.

**File:** `D:/@University/Nexachat_AppJ/server/src/main/java/com/nexachat/rag/AskService.java:160-164`

**Related locations:** `AskExecution.java:6`, `AskController.java:49-50`, `AskResponseWriter.java:44-58`, `RagQuestionCache.java:124-127`.

**Issue:** The new final context validation runs only while `askForResponse` is returning. `AskExecution` discards the context snapshot, and the controller subsequently passes it to `AskResponseWriter`, whose transaction locks and rechecks only the user's consent. An edit, recall, opt-out or E2EE activation committed after the service check but before serialization cannot change the already selected response. Likewise, a source expiry crossed while waiting for the consent lock is never rechecked. Cache invalidation does not remove the detached in-memory response. As a result, the HTTP endpoint can serialize obsolete text or citations after the source has already been withdrawn. This bypasses the freshness guarantee of the added service fence, including for followers and cache hits.

**Reproduction:** Obtain a successful `AskExecution` from `askForResponse`; commit recall or opt-out of its source in a separate transaction while keeping requester consent unchanged; invoke `AskResponseWriter.write`. The writer serializes the original successful answer because it has neither conversation identity nor an expected context snapshot to validate. For expiry, advance the clock across the captured expiry between service return and writer execution. These can be tested deterministically without a live provider.

**Fix:** Carry the conversation ID and expected context snapshot in `AskExecution`. In the writer's existing transaction, acquire the consent lock and conversation `FOR SHARE` lock in the established user-to-conversation order, then revalidate revision, expiry and conversation availability before selecting and serializing the response. Select `NOT_FOUND` with current omission metadata when the snapshot has moved. Keep network I/O outside the database transaction. Add writer regressions for recall/uncited edit, opt-out and expiry between service completion and serialization.

## Closure verification

- **CR-01:** `AskService` captures `ContextSnapshot` after membership admission and before reading the E2EE gate. Activation during capture is observed by the gate; later activation changes the captured revision and invalidates result selection. `AskServiceTest.e2eeActivationDuringSnapshotCaptureStopsAllProviderWork` exercises the original deterministic hook and asserts typed refusal with no embedding/cache lookup/LLM work.
- **CR-02:** `AskExecution` carries the conversation ID and expected context snapshot to `AskResponseWriter`. Inside one transaction the writer takes the user consent shared lock, then `RagQuestionCache.lockContextForResponse` takes the conversation shared lock and revalidates revision/expiry before serialization. Source mutations conflict with the parent lock. A second context check rejects bytes if time-based source expiry crosses serialization. Suppression recomputes omission metadata; network writes and flush remain after transaction completion.
- **Regressions inspected:** `AskApiIT.sourceMutationAfterExecutionSuppressesSerializedAnswer` covers uncited edit, recall, opt-out, expiry and E2EE. The unchanged-response and expiry-crossing-serialization tests exercise successful selection and discarded candidate bytes. Root corrected PostgreSQL fixtures for schema-valid E2EE and millisecond-stable expiry. This reviewer did not rerun the root's API gate or claim a result for a gate still in progress.
- **Lock order:** The new response locks remain user → conversation. Message source reads are MVCC reads; the writer adds no message/cache-row lock and performs no HTTP I/O in the transaction. This preserves the checked ordering against cache fills, source writers, retention and account deletion.

---

_Reviewer: gsd-code-reviewer_
_Depth: standard_
