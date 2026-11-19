# Testing

## Server tests

Server tests need JDK 21 or newer, Maven, and a reachable PostgreSQL instance.
Create a private, disposable database first, then point `IT_DB_URL` at it:

```powershell
psql -h localhost -p 5433 -U nexachat -d postgres -c 'CREATE DATABASE nexachat_test OWNER nexachat;'
$env:IT_DB_URL = 'jdbc:postgresql://localhost:5433/nexachat_test'
$env:IT_DB_USER = 'nexachat'
$env:IT_DB_PASSWORD = '<local database password>'
mvn -q -f server/pom.xml verify
```

`IT_DB_URL` must include the base database name. The test role also needs
permission to create and drop databases on the same PostgreSQL server: migration
tests and `PollFlowTest` create uniquely named scratch databases and remove them
when their test scope ends. The base database is used by the other integration
suites and can be reused for consecutive runs.

`mvn test` runs unit tests and the real-PostgreSQL `PollFlowTest`. `mvn verify`
also runs the `*IT` suites through Maven Failsafe. Maven test forks load their
fixed server settings from `src/test/resources/hermetic-test.properties`; the
`it` profile records the same defaults in `application-it.yml`. FCM is disabled,
RAG uses the lexical provider with blank API keys, and summary LLM providers are
disabled, regardless of exported developer environment values. Only `IT_DB_URL`,
`IT_DB_USER`, and `IT_DB_PASSWORD` select the test database connection.

For concurrent full-suite runs on one PostgreSQL server, create a different
private base database for each run and give each run its own `IT_DB_URL`. The
scratch databases used by migration and poll tests remain unique within and
across runs. If both Maven processes use this checkout, also pass a different
`-Dnexachat.test.build-directory=target/<run-name>` to each command so their
compiled classes and reports stay separate.

### Public feed, streak and summary-scope tests

Server suites for the three features live next to their packages. The `*Test`
classes are pure unit tests and need no database; the `*IT` classes extend
`FeatureItSupport` and need the PostgreSQL setup above.

| Area | Unit (`mvn test`) | Integration (`mvn verify`, Failsafe `*IT`) |
| --- | --- | --- |
| Public feed | `server/src/test/java/com/nexachat/feed/FeedServiceTest.java`, `FeedCursorTest.java` | `FeedFlowIT`, `FeedAdminFlowIT` (moderation and audit rows), `FeedRetentionIT` (purge job) |
| Streaks | `server/src/test/java/com/nexachat/streak/StreakRulesTest.java`, `StreakServiceTest.java` | `StreakFlowIT` |
| Summary scope | `server/src/test/java/com/nexachat/summary/SummaryPeriodsTest.java`, `SummaryRequestTest.java`, `SummaryServiceTest.java` | `SummaryScopeApiIT`, `SummaryApiIT` |

```powershell
# Unit tests only, no database
mvn -q -f server/pom.xml test -Dtest='StreakRulesTest,StreakServiceTest,FeedServiceTest,FeedCursorTest,SummaryPeriodsTest,SummaryRequestTest,SummaryServiceTest'

# Integration tests (IT_DB_URL, IT_DB_USER, IT_DB_PASSWORD set as above);
# -Dit.test selects the Failsafe classes, -Dtest keeps Surefire to the listed unit classes
mvn -q -f server/pom.xml verify -Dtest='FeedServiceTest' -Dsurefire.failIfNoSpecifiedTests=false `
  -Dit.test='FeedFlowIT,FeedAdminFlowIT,FeedRetentionIT,StreakFlowIT,SummaryScopeApiIT,SummaryApiIT'
```

Android JVM tests for the same features are under
`android/app/src/test/java/com/nexachat/feature/feed/`,
`android/app/src/test/java/com/nexachat/feature/streak/` and
`android/app/src/test/java/com/nexachat/summary/` (scope, header text and
citation chips also in `feature/summary/`):

```bash
cd android && ./gradlew :app:testDebugUnitTest \
  --tests 'com.nexachat.feature.feed.*' \
  --tests 'com.nexachat.feature.streak.*' \
  --tests 'com.nexachat.summary.*' \
  --tests 'com.nexachat.feature.summary.*'
```

The admin console `/feed` moderation page is covered by
`admin-web/tests/unit/feed.test.tsx` (Vitest):

```bash
cd admin-web && npm ci && npm test
```

These suites use fakes and a local PostgreSQL; they do not prove the feed on a
deployed backend, the streak reminder push through real FCM, or a device run.

### Sync cursor rollback drill (opt-in)

`SyncCursorRollbackIT` is skipped in ordinary `mvn verify` runs. Enable it only
when rehearsing sync v2 forward recovery on a disposable PostgreSQL server:

```powershell
$env:NEXACHAT_ROLLBACK_DRILL = '1'
$env:V31_SERVER_JAR = '<path to frozen V31 server jar>'
$env:V31_SOURCE_SHA = '9216773920fc59684564170eebde13b33ca7fbc1'
```

With `NEXACHAT_ROLLBACK_DRILL=1`, a missing or invalid `V31_SERVER_JAR` or
`V31_SOURCE_SHA` still fails the test (fail-closed). The helper script
[`server/scripts/rehearse-sync-v2-rollback.ps1`](../../server/scripts/rehearse-sync-v2-rollback.ps1)
builds the frozen V31 artifact, sets these variables, and runs the drill. Contract
context lives in [docs/architecture/api-contract.md](../architecture/api-contract.md) (sync v2 rollback section).

## Firebase rules tests

The optional Firebase Realtime Database candidate has an offline rules contract
matrix. It needs Node.js 20 or newer and Java 21; the first run installs npm
dependencies and downloads the pinned database emulator JAR, and later runs
need no network or Google credentials. The suite uses the fixed demo project
`demo-nexachat-presence`, the database emulator on `127.0.0.1:19007` and the
emulator hub on `127.0.0.1:19008`.

```bash
cd tools/firebase-rules
npm ci
npm test
```

A failed assertion makes `firebase emulators:exec` exit non-zero. The isolated
CI job `firebase-rules` runs the same command. Scope, data model and the two
MEDIUM known rule gaps are documented in
[docs/architecture/firebase-realtime.md](../architecture/firebase-realtime.md).

## Live end-to-end scripts

These Python runners (and their thin shell wrappers) are the supported live E2E
evidence against a running backend. They hit real HTTP and, where noted, STOMP
WebSocket endpoints (`docs/architecture/api-contract.md`, `docs/architecture/websocket-protocol.md`).

| Script | Role |
| --- | --- |
| `tools/e2e/smoke_api.py` | REST smoke: auth, send/history, edit/delete, search, settings |
| `tools/e2e/stomp_e2e.py` | STOMP message lifecycle, presence, rate limits |
| `tools/e2e/p0/p0_live.py` | P0 receipts, delta sync, idempotency, FCM token, auth envelopes |
| `tools/e2e/new_features/features_live.py` | Archive, polls, reminders, labels, pins, invite links, recall |
| `tools/e2e/final/final_live.py` | Disposable-stack invite, retention, recall cache checks |
| `tools/e2e/final/final_extended.py` | Extended STOMP/REST matrix on the `nexachat_e2e_grok2` stack |
| `tools/e2e/summary/summary_e2e.py` | AI summary API against a live backend |
| `tools/e2e/calls/call_e2e.py` | Call signaling journey |
| `tools/e2e/journeys/run_journeys.py` | Sequential runner for the `journeys/*_e2e.py` journey suites (feed moderation, AI privacy, groups privacy, admin/streaks) |

`tools/e2e/final/final_live.py` covers the checks that need a disposable stack:
parallel joins at `maxUses`, revoke during a join, approval limits, recall
purging the ask cache, and a spoofed `X-Forwarded-For` that must not reset the
per-IP invite preview bucket (a `429 RATE_LIMITED` has to appear within 80
requests; Bucket4j refills greedily, so the first 60 need not all succeed).
The opt-in retention seed/observe phases use the same stack.
`tools/e2e/final/final_extended.py` adds
idempotent send, recall/edit cache invalidation, STOMP receipt and removed-member
handling, labels/pin/mute/archive isolation, mentions, reminders, polls, a
500-message delta gap, 20-client fan-out, and an app-container restart. The
extended script needs `websocket-client`; both scripts need Docker and a DB
container whose name starts with `nexachat_e2e_grok2` (the scripts refuse the
demo containers).

Pass the stack coordinates with `--base-url`, `--db-container`, `--db-user` and
`--db-name`; `final_extended.py` also takes `--app-container` for the
`MockFcmGateway` push counts and `--only` for a subset of scenarios. See
`python tools/e2e/final/<script>.py --help` for the required environment,
including the literal `FCM_ENABLED=false`, retention settings and admin phone.

## Device journeys (emulator)

`tools/e2e/device/stable_journeys.py` drives the debug APK on a reserved
emulator (`--serial emulator-5554` only; it refuses other serials and any
production base URL) and writes per-attempt evidence — screenshots, UI trees,
logcat, REST/STOMP transcripts — to an `--evidence-dir` outside the repo.
Rows whose capability disposition reads `Runnable with existing helpers`
(`python tools/e2e/device/stable_journeys.py --list`):

| Row | Asserts |
| --- | --- |
| `auth-login` | Seeded sign-in reaches the inbox |
| `chat-send-receive` | Device send renders; peer sees it via REST/STOMP |
| `chat-interactions` | Long-press sheet interactions verified on device + peer REST/STOMP: react, reply, edit, recall, forward, read receipt |
| `calls-injected-notification` | Dead-process injected `CALL_INVITE` surfaces the heads-up notification (API 34+ cannot full-screen or launch the call activity in background) and declines through the real `CallForegroundService` path; no phantom server call-history row |
| `injected-notifications` | Injected `NEW_MESSAGE`/`REMINDER` posts shade notifications without leaking the payload body |
| `summary-ask` | Local summary + Ask sheet: citation chip jump, EXTRACTIVE answer quoting a seeded fact, sources chip lands on the cited message, peer CACHE replay, ai-opt-out contextOmitted round-trip |
| `archive-preferences` | Media/files/links tabs + paging + sender filter; archive undo snackbar; mute/pin/label/seen/presence flags caller-only, peer-clean, survive pull-to-refresh |
| `groups-contacts` | Contacts list = GET /contacts; UI group create/rename/add/remove; invite link + on-device approval queue; deep-link return; revoke; leave; banned member denied |
| `labels-lifecycle` | Label create/assign/filter/unfilter/delete via UI = REST; rotation + force-stop keep exactly one dialog surface; reopen refetches (#181); runtime chips must be clickable |
| `polls-reminders` | Group board Polls + Reminders tabs: poll create/vote/change/close agree with peer REST (`version`, `voterCount`, `myOptionIds`, `/polls/changes` monotonic, closed-poll vote refused); reminder create/edit/delete agree with REST; `reminder_clock` pulls the persisted one-off due under the real scheduler and the board drops it; a near-term `remindAt` PATCH lets the production sweep fire a DAILY row whose next occurrence shows on the board. Needs `NEXACHAT_E2E_REMINDER_DB` when the stack's database is not `nexachat` |
| `offline-sync` | Cuts wifi/data, queues two texts + a 👍 through the real UI, survives force-stop offline, then flushes exactly once per `clientMessageId` (REST + optional `docker exec` DB count via `NEXACHAT_E2E_DB_CONTAINER`/`NEXACHAT_E2E_DB_NAME`, default `nexachat-db-1`/`nexachat`). Seeds 105 disposable groups so the paged inbox walk reaches the deepest-seeded row `sj-os-<tag>-001`, proving >100 conversations sit in Room, then reconciles 30 edits + 30 recalls + a marker in the second 100-watermark `POST /api/v1/sync` batch across a cold restart (OkHttp logcat lines prove the multi-batch fan-out). Ends with a real profile sign-out/login A→B→A: B cannot see the An/Chi-only fixture and A's inbox reconverges. Room itself is not queried — `nexachat.db` is SQLCipher and the device image has no `sqlite3`, so local truth is asserted through the UI render |

Every other row is `BLOCKED_CAPABILITY` until its missing driver or oracle is
built; `--list` prints the exact blocker per row. API 36 notes: runtime
notification permission must be granted after each `pm clear`, `cmd
notification cancel` no longer exists (cleanup goes through the DUMP-gated
debug receiver), and `am broadcast` arrives with an unattributed sender uid.

`--dstack` wires the mail rows to a disposable backend provisioned by
`tools/e2e/disposable_stack.py`: `otp-forgot-password` runs its full
request/wrong/expired/reused/resend flow to PASS on the private stack (OTP
expiry is proven by rewinding `expires_at` in the disposable database), and
`account-deletion` executes its mail/REST portion there but still reports
`BLOCKED_CAPABILITY` for the in-app assertions (sign-in UI, deleted-account
cache, peer view). Without `--dstack` both rows refuse before Mailpit or a
database is touched.

## Local development with Mailpit (Email OTP & Account Deletion)

For testing recovery-email verification (`PUT /api/v1/users/me/email` +
`POST /api/v1/users/me/email/verify`), password reset
(`POST /api/v1/auth/password-reset/request` + `/confirm`), and self-service
account deletion (`POST /api/v1/account-deletion/request` + `/confirm`)
without external email service credentials, use [Mailpit](https://github.com/axllent/mailpit)
as a local SMTP sink:

```bash
# Run Mailpit in Docker (SMTP on 1025, web UI on 8025)
docker run -d --name mailpit -p 1025:1025 -p 8025:8025 axllent/mailpit
```

In `.env` (or environment variables for Spring Boot):
```properties
NEXACHAT_MAIL_PROVIDER=smtp
MAIL_SMTP_HOST=localhost
MAIL_SMTP_PORT=1025
MAIL_SMTP_USERNAME=
MAIL_SMTP_PASSWORD=
MAIL_FROM=noreply@nexachat.io.vn
```

Open `http://localhost:8025` in your browser to view incoming 6-digit OTP codes and test
the verification and confirmation flows.

## Android testing & Release R8 verification

```bash
# JVM unit tests (JUnit 4, Mockito, XML parsing)
cd android && ./gradlew testDebugUnitTest

# Lint check
cd android && ./gradlew lintDebug

# Build unsigned release APK to produce ProGuard/R8 mapping files
cd android && ./gradlew :app:assembleRelease -PskipSigning

# Verify R8 keep rules preserved reflection-dependent classes
python tools/checks/check-release-r8-mapping.py
```

`tools/checks/check-release-r8-mapping.py` inspects `mapping.txt` and `usage.txt` to verify
that ProGuard/R8 did not rename or remove reflection-dependent wire models
(Retrofit APIs, Moshi/Gson DTOs, Room DAOs/migrations, Google ID token credentials,
and WorkManager workers). See [docs/release/release-checklist.md](../release/release-checklist.md).

String parity:

```bash
python tools/checks/check-android-strings.py --self-test
python tools/checks/check-android-strings.py
```

The JVM suite, including `ContrastUnitTest`, checks resource XML and computed
contrast. It does not pass a device, TalkBack, 200% font, or light/dark
capture matrix. Saved HTML screenshots are references, not a native pass.
Release R8 mapping stays a separate command and is not implied by
`testDebugUnitTest`.

## Static site validation & JavaScript unit tests

```bash
# Node-free validation of site HTML, security headers, assetlinks, and ignoreCommand
python site/check.py

# Headless unit tests for site/delete-account.js (DOM shim + Node native test runner)
cd tools/site-tests && npm test
```

- `site/check.py`: Validates HTML formatting, Digital Asset Links (`.well-known/assetlinks.json`)
  SHA-256 fingerprint syntax, Vercel security headers (`vercel.json`), invite token parsing,
  and Vercel `ignoreCommand` execution logic.
- `tools/site-tests`: Runs 23 automated tests against `site/delete-account.js` covering
  form validation, OTP step transitions, rate-limit messages (429), error states, and URL
  parameter safety (ensuring phone and OTP credentials are never exposed in query strings).
  See [tools/site-tests/README.md](../../tools/site-tests/README.md).

## Markdown link verification

```bash
python tools/checks/check-markdown-links.py
```

Validates that all relative Markdown links in repository documentation resolve to real files
and anchors, preventing documentation drift.
