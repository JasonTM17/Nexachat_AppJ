---
phase: 01-ai-query-lifecycle
plan: "03"
status: verification_pending
subsystem: testing
tags: [rag, summary, android, integration, documentation]
requires:
  - phase: 01-01
    provides: RAG revision, flight and response guards
  - phase: 01-02
    provides: Android AI lifecycle and session/cache publication fencing
  - phase: 01-04
    provides: Summary consent/exclusion replay and HTTP response fencing
provides:
  - Measured complete scoped AI flow regression evidence
  - Runnable architecture/testing references and requirement coverage matrix
affects: [ai-query-lifecycle, verification]
tech-stack:
  added: []
  patterns: [synthetic provider gates, queued executor tests, disposable database verification]
key-files:
  created:
    - .planning/phases/01-ai-query-lifecycle/01-TEST-EVIDENCE.md
  modified:
    - docs/architecture/rag.md
    - docs/architecture/ai-summary.md
    - docs/quality/testing.md
requirements-completed: []
metrics:
  backend-unit-tests: 147
  postgres-api-tests: 81
  android-jvm-tests: 118
  total-tests: 346
  failures: 0
  errors: 0
  skipped: 0
completed: null
---

# Phase 1 Plan 3: Integrated AI flow verification

**All 346 scoped backend, PostgreSQL/API and Android tests pass; architecture and runnable test references describe the implemented query guarantees.**

## Completed execution tasks

1. Ran the final combined backend gate with 18 unit suites / 147 tests and 12 PostgreSQL/API suites / 81 tests. The final Android gate has 17 suites / 118 tests. Every gate has zero failures, errors and skips. Actual consent/source locking and releasing pooled connections before HTTP backpressure are covered by deterministic API tests.
2. Updated RAG and Summary architecture and testing references. `01-TEST-EVIDENCE.md` records exact commands, report locations, suite counts, the lexical benchmark, RED/GREEN evidence and all seven requirement mappings. Independent goal-backward verification is the parent phase gate and will produce `01-VERIFICATION.md` before phase completion.

## Task commit

| Commit | Result |
|--------|--------|
| `2f23886a` | Architecture/testing updates and final measured test evidence |

Implementation and RED/GREEN commits are recorded in 01-01, 01-02 and 01-04 summaries. This plan preserves their ownership and does not repeat their implementations.

## Verification evidence

- Maven final command: `verify` with the 18 scoped AI unit suites and 12 scoped PostgreSQL/API suites listed in `01-TEST-EVIDENCE.md`, using `target/gsd-ai-final`.
- Final Maven result: BUILD SUCCESS, exit 0, 3 minutes 56 seconds; 147 unit + 81 API tests.
- Android final command: Ask, Summary and Summary UI `:app:testDebugUnitTest` package filters; BUILD SUCCESS in 56 seconds, 118 tests.
- XML aggregation confirms 47 suites / 346 tests / 0 failures / 0 errors / 0 skips.
- Lexical synthetic corpus: 192 lookup questions; recall@3/5, MRR and citation validity 1.000; zero-LLM 70.0%; input reduction 79.4%.
- Final PostgreSQL gate validates Flyway through V46 and Hibernate's schema mapping.
- `git diff --check` passed. GSD schema drift reported no drift. Codebase drift skipped because no STRUCTURE.md exists; there are no earlier GSD phases requiring another prior-phase regression gate.

## Deviations

Independent review identified additional authorization/publication boundary bugs and expanded the owning implementation plans with deterministic regressions. The final gate includes SummaryResponseBoundaryApiIT, provider/citation unit guards, SummaryOptOutIT and the selected E2EE endpoint case to verify the affected flows.

## Limits

Tests use synthetic messages, fake loopback providers and Android JVM infrastructure. No paid/live AI provider, production deployment, Android device/emulator or deployed multi-node load test was performed. Per-instance work sharing and PostgreSQL freshness are documented separately.

## Self-Check: PASSED for execution and documentation

The doc task commit and all listed files/reports exist, every selected suite passed and every requirement is mapped to concrete evidence. Parent goal-backward verification and final phase metadata remain pending until `01-VERIFICATION.md` is produced and accepted.
