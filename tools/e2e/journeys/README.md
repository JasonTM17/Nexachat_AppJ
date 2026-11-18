# Live E2E journeys

End-to-end user journeys against a running NexaChat backend. Each script is a
self-contained scenario that registers its own throwaway accounts in a private
phone range, drives the real REST/STOMP API, prints `PASS ` / `FAIL ` /
`NOT_RUN ` lines, and exits non-zero on failure.

| Script | Feature area | Fixture phones |
| --- | --- | --- |
| `feed_moderation_e2e.py` | Public feed posting, reporting and the admin moderation queue | `+849000005xx`, admin `+84900000590` |
| `ai_privacy_e2e.py` | AI privacy: RAG ask, consent opt-out, translate | `+849000006xx` |
| `groups_privacy_e2e.py` | Groups privacy: rejoin visibility, disappearing messages, blocks, E2EE devices | `+849000007xx` |
| `admin_streak_e2e.py` | Admin console surfaces and chat streaks | `+849000008xx`, admin `+84900000890` |

## Backend requirements

- `NEXACHAT_ADMIN_PHONES` must include both admin phones
  (`+84900000590,+84900000890`).
- `NEXACHAT_E2EE_V1_ACTIVATION_ENABLED=true` (E2EE device journey).
- No external AI keys — summaries/RAG/translate must run on the local
  fallbacks; the journeys assert `LOCAL`/lexical behavior.
- FCM/Firebase disabled or mocked; mail provider empty.

## Run

```bash
# all journeys, in fixed order
python tools/e2e/journeys/run_journeys.py --base-url http://127.0.0.1:8091

# a subset (fixed order is preserved regardless of the order given)
python tools/e2e/journeys/run_journeys.py --base-url http://127.0.0.1:8092 \
    --only feed_moderation,admin_streak

# individual journey
python tools/e2e/journeys/feed_moderation_e2e.py --base-url http://127.0.0.1:8091
```

`--timeout` bounds each journey's wall-clock budget (default 600 s); the
runner exits 1 if any selected journey is missing, exits non-zero, times
out, prints a `FAIL` line, or reports no passing scenarios.

## NOT_RUN semantics

A journey script may print `NOT_RUN <scenario>: <reason>` for a check whose
preconditions the stack does not provide. This does not fail the run if
the journey also reports at least one `PASS` and no failures. A missing
selected `*_e2e.py` file is reported as `NOT_RUN` in the summary and fails
the run. A zero exit does not imply every scenario ran.
