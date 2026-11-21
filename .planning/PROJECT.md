# NexaChat AI query reliability

## What This Is

An existing Android Java chat application with a Spring Boot 3.5 / Java 21 backend and PostgreSQL. This GSD milestone completes and verifies the existing conversation RAG and catch-up summary flows under concurrent requests.

## Core Value

Every AI query returns an answer grounded in the caller's current conversation context, and obsolete requests never overwrite current UI state or reuse invalid work.

## Requirements

### Validated

- Conversation-scoped hybrid RAG retrieval, citations, cache, consent and extractive fallback already exist.
- Structured catch-up summaries with scoped windows, provider routing, cache and consent already exist.
- Android Ask and Summary sheets, repositories and ViewModels already exist.

### Active

- Complete query flow coverage and close concrete concurrency gaps in backend and Android.
- Bound duplicate-query waiting and isolate work by all inputs that affect visibility and responses.
- Verify cache freshness and deterministic concurrency regressions without external AI calls.

### Out of Scope

- New AI frameworks, deployment, paid provider smoke tests, or unrelated product features.

## Context

User request (2026-11-21): "Hoàn thiện chức năng AI RAG tóm tắt bao phủ luồng, tối ưu race condition cho mỗi lần truy vấn. Dùng gsd workflow để thực thi."
Existing references: docs/architecture/rag.md, docs/architecture/ai-summary.md, docs/quality/testing.md.
Initial checkout: main, clean worktree. No existing .planning or AGENTS.md found.

## Constraints

- Preserve current API schemas and privacy, membership and E2EE guards.
- Use existing Java, Spring, Retrofit and LiveData patterns.
- Prefer deterministic latch-controlled race tests; use disposable integration databases.
- Run GSD research, planning, plan checking, execution, verification and record atomic local commits.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| One scoped milestone on the existing codebase | The request targets existing functionality | Pending |
| Offline synthetic verification | Reproducible tests without external message disclosure or provider charges | Pending |

---
Last updated: 2026-11-21
