# Phase 1 source coverage audit

**Date:** 2026-11-21
**Discovery:** Level 0, existing Java/Spring/Retrofit/LiveData/PostgreSQL patterns confirmed by focused research; no new library or provider integration.

| Source | ID | Item | Plans | Status |
|--------|----|------|-------|--------|
| GOAL | - | Reliable current-query results without obsolete flights/cache/UI | 01, 02, 04, 03 | COVERED |
| REQ | AI-01 | Identical coalescing and all visibility inputs isolated | 01, 03 | COVERED |
| REQ | AI-02 | Bounded interruptible wait and recovery | 01, 03 | COVERED |
| REQ | AI-03 | Source freshness plus consent/citation guards | 01, 04, 03 | COVERED |
| REQ | AI-04 | Ask lifecycle and thread publication | 02, 03 | COVERED |
| REQ | AI-05 | Summary lifecycle and cache invalidation | 02, 03 | COVERED |
| REQ | AI-06 | Existing flow and deterministic race coverage | 01, 02, 04, 03 | COVERED |
| REQ | AI-07 | Documentation and GSD evidence | 03 | COVERED |
| RESEARCH | RAG-1 | Unbounded join, failed/stalled/interrupted leader behavior | 01 | COVERED |
| RESEARCH | RAG-2 | Admission and content omitted from flight identity | 01 | COVERED |
| RESEARCH | RAG-3 | MAX(seq) misses edits/recall/opt-out and uncited refill | 01 | COVERED |
| RESEARCH | RAG-4 | Expiry before retention cleanup and safe lock order | 01 | COVERED |
| RESEARCH | AND-1 | Summary clear/queued callback, obsolete returned handle | 02 | COVERED |
| RESEARCH | AND-2 | Cache epoch and newer refresh fill ordering | 02 | COVERED |
| RESEARCH | AND-3 | Timezone retry callback thread and queued Ask boundaries | 02 | COVERED |
| RESEARCH | SUM-1 | Cached external replay skips consent recheck | 04 | COVERED |
| RESEARCH | SUM-2 | Complete exclusion identity covers uncited overview and changed-context wait/cache/winner/generation | 04 | COVERED |
| RESEARCH | ENV | Discover Android SDK/JDK; synthetic PostgreSQL | 02, 03 | COVERED |
| CONTEXT | C-01 | Preserve current API/provider/consent/offline behavior | 01, 02, 04, 03 | COVERED |
| CONTEXT | C-02 | Only concrete existing flow gaps, no new AI product | 01, 02, 04 | COVERED |
| CONTEXT | C-03 | GSD research/plan/check/execute/verify/local commits | All | COVERED |
| CONTEXT | C-04 | No external live provider call required | 03 | COVERED |

CONTEXT.md decisions have no D-NN IDs; C-NN audit labels above map their exact statements without inventing user decision IDs.

## Dependency and ownership

| Plan | Needs | Creates | Wave | Owner |
|------|-------|---------|------|-------|
| 01-01 | Existing RAG stack and PostgreSQL | Context revision/cache guards, bounded asks and race evidence | 1 | Backend executor; root owns RagContextRaceIT |
| 01-02 | Existing Android stack | Summary cache/lifecycle guards and both sheets' regressions | 1 | Android executor |
| 01-04 | Existing Summary stack | Cached consent/current opt-out rechecks | 1 | Root |
| 01-03 | 01-01, 01-02, 01-04 verified implementations | Integrated test evidence and documentation | 2 | Root |

No same-wave file overlap. Plan 03 has no new production feature; it verifies and documents the implementations from wave 1.

## Plan-check revision

Checker blocker addressed in 01-04: citation/participant checks alone cannot establish that an overview did not use an excluded author's uncited text. External lookup, flight and store keys now bind the complete expected exclusion set; equality is rechecked before selecting cached, concurrent-winner and generated external output. Direct/waited uncited-overview regressions and post-purge fill isolation are explicit acceptance evidence.
