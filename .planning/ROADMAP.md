# Roadmap: AI RAG and summary query reliability

## Overview

One scoped milestone on the existing NexaChat application: investigate actual gaps, implement query coordination and UI lifecycle fixes, then verify the complete AI flows.

## Phases

- [x] **Phase 1: AI query lifecycle and race condition hardening** - Complete RAG and summary flows with safe per-query coordination and deterministic evidence. (completed 2026-11-21)

## Phase Details

### Phase 1: AI query lifecycle and race condition hardening

**Goal**: Current AI queries complete reliably without obsolete in-flight work, invalid cache context or late Android callbacks affecting their results.
**Depends on**: Nothing (existing functionality)
**Requirements**: AI-01, AI-02, AI-03, AI-04, AI-05, AI-06, AI-07
**Success Criteria** (what must be TRUE):

1. Identical concurrent asks avoid duplicate provider work; visibility and context changes isolate queries.
2. A stalled or failed ask leader cannot indefinitely park followers; cancellation and subsequent recovery work.
3. Cache invalidation and consent/citation safeguards survive concurrent context mutations.
4. Android Ask and Summary ignore obsolete callbacks and preserve only the current query's state.
5. Focused backend, Android and integration tests verify the existing AI flows plus identified races; documentation records evidence.

**Plans**: 4 plans

Plans:

- [x] 01-01-PLAN.md - Transactional RAG context revision, bounded followers and PostgreSQL race regressions.
- [x] 01-02-PLAN.md - Android Summary cache/lifecycle and Ask queued callback guarantees.
- [x] 01-04-PLAN.md - Cached external Summary consent and current opt-out replay guards.
- [x] 01-03-PLAN.md - Integrated AI flow verification, documentation and GSD evidence.

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. AI query lifecycle and race condition hardening | 4/4 | Complete    | 2026-11-21 |
