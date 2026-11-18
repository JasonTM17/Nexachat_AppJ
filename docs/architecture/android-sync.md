# Android sync checkpoints

The Android database is Room schema v21; the sync-v2 checkpoint columns and
triggers described here landed in the 13→14 migration. Room stores message
`changeVersion`/`generation` and one active cursor per
account/conversation. The cursor primary key includes generation; a unique
account/conversation index prevents retaining an older active generation.
Migration 13→14 adds columns/table/indexes without rebuilding history or either
outbox. Deleting cached messages (including a partial deletion) or a conversation
invalidates its cursor through SQLite triggers installed on upgrade and fresh open.

The Android client opts into the ruled v2 contract with `syncVersion=2`, scalar
`changeCursor=0` on first reconciliation, and the saved conversation generation
on subsequent pages. Complete v2 field presence is required for every returned
delta. An old, disabled, unknown-version or partial-v2 response triggers a fresh
legacy request; its v2 rows are never partially applied. Downgrade clears saved
revision authority without deleting history or queued sends. Legacy mode retains
timestamp-based reconciliation without v2 current-state convergence. On edit or
recall overflow, the server caps `serverTime` at the included change boundary;
the Android fallback retries from the earliest returned server time across
watermark batches after sequence and reaction cursors drain. Retry/page bounds
leave pending work for the next sync.
The server side shipped in Flyway V32 (`change_version`/`sync_generation`
columns) and answers negotiated v2 requests by default; the fallback/downgrade
paths above still apply whenever the server declines the negotiation
(`nexachat.sync.change-cursors-enabled=false`) or a deployment predates V32.

Each page applies rows, deletion/reply/pin cleanup, preferences and its message
and reaction checkpoints in one Room transaction. Account identity and P01
session epoch are checked at the write boundary. Faults before commit replay;
faults after commit resume from the durable cursor. A reset-only response clears
server cache state, preserves queued sends, replaces the generation, and starts
from zero. Omitted membership removes that conversation's cache and account's
queued sends. A delayed page whose cursor/cache scope changed is discarded.

Sync, history, acknowledgements and lifecycle events use the same revision
comparison. Same-generation lower/equal revisions cannot rewind a row; a higher
server revision can supersede older optimistic local state. Unversioned frames
cannot downgrade a versioned row. New generations require reset before replacing
existing versioned rows. Late reply pages cannot restore snippets for a locally
tombstoned parent. Events may update rows before initial reconciliation but never
advance the feed cursor. After explicit downgrade, old v2 events are ignored for
that session until successful negotiation.

Reactions retain an independent event cursor, conservative `since` and first-page
baseline. Completion follows both message and reaction streams. Missing/expired
reaction coverage initiates a cursor-zero full row walk, including empty reaction
aggregates, followed by replay from the pre-walk baseline. Checkpoints are per
conversation, so a later unrelated batch cannot hide an earlier scope's changes.
The client processes at most 100 conversations per request and 60 pages per batch
(20 pages with two bounded continuations). Reaching that bound leaves pending
work for the next sync; no-progress pages fail. Concurrent FCM/reconnect callers
share one session flight; disposing one subscriber leaves the shared drain alive.

Verification uses fixture JSON and the existing JUnit/Mockito/Room migration
libraries. `RoomV14MigrationInstrumentedTest` covers populated 13→14, outboxes,
cache invalidation and transaction fault/reopen behavior; it is an instrumented
test that requires an emulator or device and does not run in CI (see
`docs/quality/testing.md` and `CONTRIBUTING.md` for the invocation). Live
v2/legacy compatibility against the integrated server is covered by the
`SyncCursor*IT` integration tests under `server/src/test`.
Room has no downgrade path; repairs ship in a higher-versionCode APK.
