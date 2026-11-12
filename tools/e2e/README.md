# NexaChat end-to-end scripts

Real-traffic verification of the message-lifecycle and presence contract against a
running backend (`docs/api-contract.md`, `docs/websocket-protocol.md`). They are
demo/CI smoke tools, not part of the app build.

## Prerequisites

- Backend healthy at `http://localhost:8080` and Postgres seeded (`demo/seed.sql`).
- Python 3.9+.
  - `smoke_api.py` and `perf_e2e.py`: standard library only.
  - `stomp_e2e.py`: `websocket-client` (`pip install websocket-client`).
- `jq` is **not** installed on the reference machine, so the smoke script is a
  Python-stdlib implementation of the same checks (the brief's sanctioned
  fallback); `smoke-api.sh` / `smoke-api.ps1` are thin wrappers.

Accounts: `smoke_api.py` uses the seed users **An** (`+84900000001`) and **Chi**
(`+84900000003`) plus **Binh** (`+84900000002`) as the non-member; all use
`password123` (`demo/seed.sql`). `stomp_e2e.py` additionally
registers a dedicated pair (`+84900000101`, `+84900000102`) because presence is
per-user: the demo accounts may hold a live session from another client, and a
second session suppresses that user's presence events by design.

Login is limited to 10 requests / 5 min per IP. Access tokens are cached for at
most 9 minutes in `%TEMP%/nexachat-e2e-tokens.json` (`/tmp` on POSIX) and
validated with `GET /users/me` before reuse; delete that file to force a fresh
login. Tokens are never printed.

## Run

```bash
bash tools/e2e/smoke-api.sh              # or: pwsh tools/e2e/smoke-api.ps1
python tools/e2e/stomp_e2e.py            # ~30 s: events, presence, rate limit
python tools/e2e/perf_e2e.py             # ~2 min: 550-message corpus + latency
python tools/e2e/summary/summary_e2e.py    # AI summary API (fixture +849000003xx)
```

Optional: `--base-url http://host:8080` on any script; `--bulk/--samples` for
`perf_e2e.py`.

Every check is fail-fast: the first broken expectation prints `[FAIL] <detail>`
and exits non-zero.

## What is verified

| Script | Checks |
| --- | --- |
| `smoke_api.py` | health; two-user login; idempotent DIRECT create; send/history; edit (`editedAt` set, identical edit no-op, foreign author 403 `NOT_MESSAGE_AUTHOR`, blank 400); delete idempotent (204/204), edit-after-delete 409 `MESSAGE_DELETED`; tombstone history shape (`content:null`, `attachments:[]`, `reactions:[]`, seq kept); search hit/miss/2-char floor/literal `%` and `_`/cursor paging/non-member 403 `NOT_MEMBER`; settings round-trip; unauthenticated 401. |
| `stomp_e2e.py` | `MESSAGE_CREATED` to peer and sender; `MESSAGE_UPDATED` payload equals the REST row; `MESSAGE_DELETED {messageId, deletedAt}`; duplicate `clientMessageId` re-broadcast with one row; `USER_ONLINE`/`USER_OFFLINE` with the 5 s coalescing window and flap suppression; `showPresence=false` silence plus hidden `peerPresence`; `RATE_LIMITED` on `/user/queue/errors` past 30 SENDs / 10 s. |
| `perf_e2e.py` | 500-message corpus paced at 25 sends / 10 s per author (under the 30 sends / 10 s bucket), then 50 samples each of edit/delete/search with p50/p95. |

## New-feature live suite

`tools/e2e/new_features/features_live.py` is one self-contained command for the
merged archive/inbox/reminder/poll/AI/privacy features. It creates its own
throwaway accounts (never the seed accounts), runs the checks, and cleans up
every flag, reminder, poll, reaction and message an endpoint allows it to
remove.

```bash
python tools/e2e/new_features/features_live.py                 # API_BASE_URL or localhost:8080
python tools/e2e/new_features/features_live.py --allow-skip    # offline machine: required skips exit 0
python tools/e2e/new_features/features_live.py --self-test     # mocked transport, no backend
python -m unittest tools/e2e/new_features/test_features_live.py
```

- Every check prints `PASS`/`FAIL` with the HTTP method, endpoint and status;
  any `FAIL` or required `SKIP` exits non-zero (`--allow-skip` only excuses
  skips).
- A non-loopback `--base-url` is refused unless
  `--i-know-this-is-not-prod` is passed.
- `--self-test` runs the non-STOMP scenarios against an in-memory transport, so
  the script's own request/assertion logic is verified without a server.
- Tokens, passwords and phone numbers are never printed; ids are masked.

Coverage: archive browser (`MEDIA`/`FILE`/`LINK`, sender/date filters, keyset
cursor, tombstone exclusion, non-member 403); inbox mute/unmute and
archive/unarchive flags (per-member); reminders CRUD plus creator/admin authz;
polls create/vote/change/retract/close/delete, anonymous vote-id omission and
the change feed; per-conversation AI opt-out; conversation pin (5-pin cap with
`409 CONVERSATION_PIN_LIMIT`); `showSeen=false` in both directions over STOMP;
`Idempotency-Key` replay on reminders and reactions; revoked-token STOMP
connect rejection and REST 401; group invite link create/preview/join/revoke;
group mentions and admin `mentionAll`; label CRUD, assignment and the 3-label
cap; author recall inside the window and `409 RECALL_WINDOW_EXPIRED` after it.

The recall-after check waits `E2E_RECALL_WAIT_SECONDS` (default 70) and expects
the server window to be shorter than that wait. A live run that proves the
minimum window sets `NEXACHAT_MESSAGE_RECALL_WINDOW=PT1M`. Muted-conversation
push suppression has no HTTP hook: set `E2E_APP_CONTAINER` to the app container
(the suite counts `MockFcmGateway: Recorded data push of type NEW_MESSAGE`
lines; the server does not log the device label) or `E2E_PUSH_LOG_FILE` for
the offline self-test. With
neither set, that scenario is `NOT_RUN` and does not fail the process.

There is no account-delete or conversation-delete endpoint, so the throwaway
accounts, their DIRECT conversation and the test GROUP conversations remain in
the database; all mutable state is reverted. Run it against a disposable local
database.

## Final-pass scenarios

`tools/e2e/final/final_live.py` covers the checks that need a disposable
database and, for two of them, a short row lock held through `psql` in the
suite's own Postgres container:

```bash
python tools/e2e/final/final_live.py --phase http \
    --base-url http://127.0.0.1:18082 \
    --db-container nexachat_e2e_grok2-db-1 \
    --db-user e2egrok2 --db-name nexachat_e2e_grok2
python tools/e2e/final/final_live.py --phase retention-seed ...
python tools/e2e/final/final_live.py --phase retention-observe --expect dry-run ...
python tools/e2e/final/final_live.py --phase retention-observe --expect deleted ...
```

- N parallel joins on a `maxUses=2` link never admit more than 2 members.
- A revoke that lands while a join is waiting on the conversation row does not
  reopen the link.
- Approving pending join requests stops at `maxUses`.
- Recalling a message removes that conversation's ask-cache rows.
- A spoofed `X-Forwarded-For` does not reset the per-IP invite preview bucket
  (60 previews / minute on the socket peer).
- Retention is opt-in. Seed aged rows, restart with dry-run, read
  `nexachat.retention.rows.observed`, then restart with deletion enabled.
  Active invites, unfired and repeating reminders, open polls, and attachments
  that are still bound to a live message stay. The script refuses any database
  container whose name is not the `nexachat_e2e_grok2` project.

## Controlled reminder clock helper

`tools/e2e/reminder_clock.py` adjusts reminder due times in the local PostgreSQL database (`docker exec <container> psql`) so occurrences come due deterministically during E2E verification (`polls-reminders` harness row) without waiting minutes for wall-clock sweeps or modifying production code:

```bash
python tools/e2e/reminder_clock.py make-due <reminder-id> [--minutes-ago 1]
python tools/e2e/reminder_clock.py wait-fired <reminder-id> [--timeout 10]
python tools/e2e/reminder_clock.py show <reminder-id>
python tools/e2e/reminder_clock.py occurrence <reminder-id>
```

- `make-due`: moves `remind_at` into the past while resetting `claimed_at = NULL`, `claim_token = NULL`, and `fired_at = NULL` so the row is immediately claimable by the scheduler's due sweep while preserving the `reminders_claim_pair` constraint.
- `wait-fired`: bounded poll until the occurrence is marked claimed or completed/fired; raises a clear error on timeout.
- `show`: prints reminder ID, due time, and status (`DUE`, `CLAIMED`, `FIRED`, or `SCHEDULED`) without printing message title/content.
- `occurrence`: derives the 32-hex occurrence identifier carried by push payloads (`SHA-256(reminderId + '|' + remindAt.toEpochMilli())[:16].hex()`) matching `FcmReminderPushSender.occurrenceKey`.
- Safety: refuses any database whose name does not contain `nexachat`, refuses non-local targets, and validates all IDs as UUIDs.

## Cleanup / side effects

- `smoke_api.py` and `stomp_e2e.py` soft-delete every message they created (run
  cleanup in a `finally`, so a failed run still cleans up). Tombstone rows remain
  in the conversation, as the contract requires.
- The DIRECT conversations they create (An↔Chi, the dedicated pair) are
  idempotent and persist.
- `perf_e2e.py` **leaves** its `e2e-bulk-<timestamp>` rows in place (≈550 deleted
  or edited rows would exceed the 30 writes/min/user budget to clean up); run it
  against a demo database you can reseed.
