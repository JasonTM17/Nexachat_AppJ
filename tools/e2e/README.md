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

## Deterministic REST response barrier (offline utility)

`response_barrier.py` is a standard-library HTTP reverse proxy for the
`auth-session-lifecycle` paused-response account-switch scenario. It forwards
the request immediately, buffers the complete upstream response, then holds
**all response headers and body** until release. A `/held` entry therefore
means the upstream has completed; switch the client to account B before
releasing account A's response. This utility alone does not prove Android's
account isolation; the app journey must assert that separately.

```bash
python tools/e2e/response_barrier.py --upstream http://127.0.0.1:8080 \
    --port 18090 --control-port 18091 --max-hold-time 30
python -m unittest tools.e2e.test_response_barrier -v
```

For an Android emulator debug build, build from `android/` with
`./gradlew -PapiBaseUrl=http://10.0.2.2:18090 assembleDebug` (Windows:
`./gradlew.bat -PapiBaseUrl=http://10.0.2.2:18090 assembleDebug`). This overrides
`API_BASE_URL` in `local.properties` and the default `http://10.0.2.2:8080`.
The emulator host-loopback alias reaches the proxy; the proxy reaches the
backend on host port 8080. Changing the port requires rebuilding the APK.
The commands above only build; device installation and the account-switch
journey belong to the device owner. Debug permits cleartext HTTP.

Python scripts can import `Barrier` from `tools.e2e.response_barrier`:

```python
with Barrier("http://127.0.0.1:8080") as b:
    b.hold(method="GET", path=r"^/api/v1/conversations$", token_contains=account_a_token)
    # Start account A's request to b.url asynchronously (or trigger it in the app).
    held = b.wait_held(1, timeout=5)
    # Switch to B and assert B's independent request/state before releasing A.
    b.release(held[0]["id"])
    # Assert B still owns the visible UI and persisted state after A arrives late.
```

The control API is on `http://127.0.0.1:18091` (ephemeral ports by default in
the Python API), independent of proxy request capacity:

| Endpoint | JSON input / output |
| --- | --- |
| `POST /rules` | `{"method":"GET","path":"^/api/v1/conversations$","action":"hold","token_contains":"fixture-A"}` returns `201 {"id":...}`. Omit `token_contains` to match any Authorization value. |
| `POST /rules` | `{"path":"^/api/v1/conversations$","action":"delay_ms","delay_ms":500}` delays the response; `method` defaults to `GET`. |
| `GET /held` | `{"held":[{"id":...,"path":...,"age":...,"rule_id":...}]}`; age is seconds since buffering completed. Query strings and Authorization values are excluded. |
| `POST /release/<id>` | `{}` releases that response id; returns `{"released":[...]}`. |
| `POST /release/all` | `{}` releases the current queue in arrival order. |
| `POST /reset` | `{}` removes rules, releases held responses and invalidates matches whose upstream response is still in flight. |

Rules are persistent and first-match wins, matching the HTTP method and a
Python regex search on the URL path (without query). Rule ids and held-response
ids are distinct; release uses the latter. Python `b.release(count=1)` releases
the oldest response, while `b.release()` releases the current queue. Release
order describes unblocking order; operating-system scheduling can reorder
completion across independent client sockets. A reset does not cancel requests
or undo upstream writes. Install rules before triggering account A's request.

Limits: REST over **plain HTTP only** on both legs, with no TLS termination,
HTTPS upstream or CONNECT tunneling. WebSocket upgrades return **501** with a
REST-only explanation and a warning; the proxy **does not carry STOMP**, whose
URL is also derived from Android's API base URL. Use it for isolated REST race
testing, not a complete realtime journey. Request chunking and `Expect:
100-continue` are rejected; upstream chunked responses are decoded and buffered.
HEAD, duplicate response headers, HTTP error status/body and ordinary binary
request/response bodies are preserved. Bodies default to at most 32 MiB each;
oversized requests return 400 and invalid/oversized/unreachable upstream
responses return a generic 502. Streaming/SSE is unsupported.

Bounds default to 32 held responses, 128 rules, 64 active connections per
listener and 5 seconds per blocking I/O operation; Python constructor options
can lower them. At held capacity, an additional response passes with an explicit
warning. At connection capacity, an additional client gets 503. Holds and delays
are capped by `max_hold_time` (30 seconds); timed-out holds auto-release with an
id-only warning. A test must observe its `/held` entry and finish the account
switch before that deadline. Context exit/Ctrl+C stops listeners, interrupts
clients/upstream reads and joins all workers; other in-progress socket I/O can
take up to its I/O timeout to unwind. Hostname lookup uses the OS resolver; use
a numeric loopback upstream for deterministic offline runs.

The unauthenticated control listener always binds to loopback; the proxy also
defaults to loopback. Rules/regexes and upstream are trusted local test inputs.
Do not expose the control API or use real production tokens. Access logging is
disabled and warnings contain no tokens or request targets; rule creation never
echoes its Authorization matcher. No device is needed for the offline tests.

## Cleanup / side effects

- `smoke_api.py` and `stomp_e2e.py` soft-delete every message they created (run
  cleanup in a `finally`, so a failed run still cleans up). Tombstone rows remain
  in the conversation, as the contract requires.
- The DIRECT conversations they create (An↔Chi, the dedicated pair) are
  idempotent and persist.
- `perf_e2e.py` **leaves** its `e2e-bulk-<timestamp>` rows in place (≈550 deleted
  or edited rows would exceed the 30 writes/min/user budget to clean up); run it
  against a demo database you can reseed.
