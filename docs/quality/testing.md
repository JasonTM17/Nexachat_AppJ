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
[`server/scripts/rehearse-sync-v2-rollback.ps1`](../server/scripts/rehearse-sync-v2-rollback.ps1)
builds the frozen V31 artifact, sets these variables, and runs the drill. Contract
context lives in [docs/api-contract.md](api-contract.md) (sync v2 rollback section).

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
[docs/firebase-realtime.md](firebase-realtime.md).

## Live end-to-end scripts

These Python runners (and their thin shell wrappers) are the supported live E2E
evidence against a running backend. They hit real HTTP and, where noted, STOMP
WebSocket endpoints (`docs/api-contract.md`, `docs/websocket-protocol.md`).

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

`tools/e2e/P0E2ETestRunner.java` is an **illustrative synthetic fixture** only: it
runs 81 JDK-only assertions on locally constructed data without starting the server
or Android app. Do not treat its output as live E2E acceptance. See
[`tools/e2e/README.md`](../tools/e2e/README.md).

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

## Local development with Mailpit (Email OTP & Account Deletion)

For testing recovery-email verification (`POST /api/v1/users/me/email/request`),
password reset (`POST /api/v1/auth/recovery/reset-password/request`), and
self-service account deletion (`POST /api/v1/auth/recovery/delete-account/request`)
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
and WorkManager workers). See [docs/release-checklist.md](release-checklist.md).

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
  See [tools/site-tests/README.md](../tools/site-tests/README.md).

## Markdown link verification

```bash
python tools/checks/check-markdown-links.py
```

Validates that all relative Markdown links in repository documentation resolve to real files
and anchors, preventing documentation drift.
