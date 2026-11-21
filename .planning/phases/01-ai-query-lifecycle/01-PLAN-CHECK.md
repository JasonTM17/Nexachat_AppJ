---
phase: 01-ai-query-lifecycle
status: passed
checked: 2026-11-21
blockers: 0
warnings: 0
---

# Phase 1 pre-execution plan check

## VERIFICATION PASSED

Four plans checked; structural validation passed for all 9 tasks. Revision 1 of 01-04 closes the behavioral blocker. Unaffected plans 01-01 and 01-02 passed independently; the complete revised plan set is now ready for execution.

## Coverage

| Requirement | Plans | Result |
|-------------|-------|--------|
| AI-01 | 01-01, 01-03 | Covered |
| AI-02 | 01-01, 01-03 | Covered |
| AI-03 | 01-01, 01-04, 01-03 | Covered |
| AI-04 | 01-02, 01-03 | Covered |
| AI-05 | 01-02, 01-03 | Covered |
| AI-06 | All plans | Covered |
| AI-07 | 01-03 | Covered |

## Resolved BLOCKER: Summary replay freshness must cover uncited source

**Plan:** 01-04, Task 2. **Dimensions:** requirement coverage, key links, verification derivation.

The task re-reads current opt-outs and passes them to existing `replayAllowed`, but that method checks only cited message authors and listed participants. `SummaryService.contentHash` does not bind the opt-out set. An old external cache object read before an opt-out purge can contain the newly excluded author's text in its uncited overview while citing only another author and listing no excluded participant. The refreshed set therefore does not cause rejection. The same limitation affects a concurrent cache winner. This fails the task's own truth that a waiter replays only a summary allowed by current opt-out state and AI-03's context freshness guarantee.

**Required revision:** Bind full source/exclusion freshness for external cached/winner Summary output, or conservatively reject/recompute external playback whenever exclusions relevant to its source window changed after the authorization/source snapshot. Ensure old in-flight generated output cannot refill a cache under a new exclusion state. Include deterministic old-cache/winner tests whose overview carries excluded uncited text and whose citations/participants are otherwise allowed. Preserve valid summaries generated with unchanged exclusions and native LOCAL semantics. The plan must specify this full guard rather than relying solely on `replayAllowed`.

**Resolution verified:** Revised 01-04 Task 2 captures an immutable complete exclusion set; hashes sorted UUID exclusions into external cache, flight and store identity; and requires a persisted equality recheck before external cached, concurrent-winner and generated result selection. Changed exclusions cause bounded re-preparation or native LOCAL suppression, and old post-purge fills remain under the obsolete key. Explicit direct-cache, waiter, winner and generation-race tests use uncited overview text with otherwise allowed citations/participants. Citation checks remain supplementary. Unchanged exclusions and LOCAL keys/semantics are preserved. This addresses the exact uncovered behavior without expanding the API or framework scope.

## Other dimensions

- All phase requirement IDs appear in frontmatter; PROJECT active requirements are represented.
- All tasks have files, action, automated verification and measurable done criteria.
- Dependencies are acyclic: 01-01/02/04 wave 1; 01-03 wave 2 depends on all three.
- Source revision/expiry is wired from migration through cache and flight identity; context is captured before expensive work. Conversation row then advisory save locking matches existing mutation order.
- Bounded follower wait, interrupted status, compare-and-remove cleanup and distinct visibility inputs are explicit.
- Android cache epoch/latest-fill policy and publication-point generation/conversation checks are explicit, with queued callback tests.
- Source references for reusable service/repository APIs and existing test analogues are present. New methods/columns are identified as creation work.
- Scope is within thresholds: 3/2/2/2 tasks, 7/6/4/3 declared files.
- Locked context decisions are honored; no framework/deployment/live-provider scope is added.
- Dimension 7c: SKIPPED (no architectural responsibility map).
- Dimension 8: SKIPPED (nyquist_validation explicitly disabled).
- Dimension 9: PASS (no incompatible cross-plan transformations).
- Dimension 10: SKIPPED (no AGENTS.md or local skill directory found).
- Dimension 11: PASS (no unresolved research questions requiring decisions).
- Dimension 12: SKIPPED (no PATTERNS.md).

## Structured issues

```yaml
issues: []
```

No application or test process was run for this static plan check; production files were not modified.
