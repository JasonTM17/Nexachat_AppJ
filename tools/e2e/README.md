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
python tools/e2e/perf_e2e.py             # ~35 s: 550-message corpus + latency
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
| `perf_e2e.py` | 500-message corpus at 20 req/s, then 50 samples each of edit/delete/search with p50/p95. |

## Cleanup / side effects

- `smoke_api.py` and `stomp_e2e.py` soft-delete every message they created (run
  cleanup in a `finally`, so a failed run still cleans up). Tombstone rows remain
  in the conversation, as the contract requires.
- The DIRECT conversations they create (An↔Chi, the dedicated pair) are
  idempotent and persist.
- `perf_e2e.py` **leaves** its `e2e-bulk-<timestamp>` rows in place (≈550 deleted
  or edited rows would exceed the 30 writes/min/user budget to clean up); run it
  against a demo database you can reseed.
