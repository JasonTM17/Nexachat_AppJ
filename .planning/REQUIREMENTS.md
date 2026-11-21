# Requirements: AI RAG and summary query reliability

**Defined:** 2026-11-21
**Core Value:** Correct current-query results from conversation-grounded AI under concurrency.

## v1 Requirements

### Backend query coordination

- [ ] **AI-01**: Identical concurrent asks share expensive computation while different questions, languages, histories, consent epochs and membership visibility remain isolated.
- [ ] **AI-02**: Waiting for an in-flight ask has one bounded deadline, interruption is respected, failures clean up, and later requests can recover.
- [ ] **AI-03**: Conversation changes cannot cause a new query to join obsolete work or replay an answer from invalid cache context; existing consent and citation guards remain enforced.

### Android query lifecycle

- [ ] **AI-04**: Ask callbacks from canceled, superseded or previous-conversation requests cannot mutate the active state or follow-up thread.
- [ ] **AI-05**: Summary callbacks from canceled, superseded or previous-scope/conversation requests cannot mutate the active state.

### End-to-end verification

- [ ] **AI-06**: Existing Ask and Summary success, cache, fallback, error, consent, E2EE and citation flows remain covered; deterministic regression tests reproduce and prevent newly identified races.
- [ ] **AI-07**: Document the implemented concurrency contract and verification evidence, with GSD plan summaries and requirement traceability.

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| AI-01 | Phase 1 | Pending |
| AI-02 | Phase 1 | Pending |
| AI-03 | Phase 1 | Pending |
| AI-04 | Phase 1 | Pending |
| AI-05 | Phase 1 | Pending |
| AI-06 | Phase 1 | Pending |
| AI-07 | Phase 1 | Pending |
