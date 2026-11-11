# Firebase Realtime Database (opt-in)

The Firebase Realtime Database (RTDB) path is an optional transport for
ephemeral state: presence, typing indicators and an optional call-status
projection. PostgreSQL and the Spring API remain the only durable authority for
accounts, messages, receipts, membership, privacy settings and call history.

**Status: candidate, disabled by default.** The server auth bridge (S1),
authorization-mirror projection writer (S2, `server/src/main/java/com/nexachat/firebase/projection/`),
and Android ephemeral router/source (S3, `android/app/src/main/java/com/nexachat/core/realtime/ephemeral/`)
are implemented in the codebase. However, the feature remains **disabled by default**
(`FIREBASE_REALTIME_ENABLED=false`, `FIREBASE_PROJECTION_ENABLED=false`, and Android
`firebaseRealtimeEnabled=false`), and STOMP over WebSocket remains the authoritative and default
realtime transport for presence and typing indicators. Do not install the rules or enable
`FIREBASE_REALTIME_ENABLED` for real users until every rollout gate below is
met. `docs/operations.md` owns the environment variables and the Compose
caveat; this page owns the rules contract, its tests and the known gaps.

## Data model

Every path below is written by the privileged server REST client except
`presence` and `typing`, which are written by the owning client inside the
lease. Clients can read only the leaves named here. Parent and list reads are
denied by the rules.

| Path | Writer | Lifetime / contract |
|---|---|---|
| `/control/enabled` | server | Global kill switch. Every client rule requires `true`. |
| `/accounts/{uid}/{active,showPresence}` | server | Account state mirror. `showPresence=false` denies peers, not the owner. |
| `/sessions/{uid}/{bridgeId}/until` | server | Bridge lease in epoch milliseconds, at most 120 seconds and never beyond the Nexa access-token expiry. |
| `/members/{conversationId}/{uid}` | server | Membership lease in epoch milliseconds, at most 120 seconds, regenerated from PostgreSQL. |
| `/presenceReaders/{subjectUid}/{viewerUid}` | server | Value is the DIRECT conversation id that authorizes the viewer. **Only DIRECT conversations may be projected here.** |
| `/blocked/{uid}/{otherUid}` | server | Reserved block mirror. Empty until an authoritative block feature exists; both directions deny. |
| `/presence/{uid}/{bridgeId}` | owning client | `{online,lastSeen,updatedAt,leaseUntil}`. One record per connection, so multiple devices coexist. |
| `/typing/{conversationId}/{uid}` | member client | `{bridgeId,updatedAt,expiresAt}`; `expiresAt` is at most five seconds after the server time and writes are throttled to one per three seconds. |
| `/calls/{callId}` | server | Read-only participant projection with `expiresAt`; never signaling or call authority. |

## Server endpoints

The controller is absent unless `FIREBASE_REALTIME_ENABLED=true` and
`FIREBASE_DATABASE_URL` is a valid HTTPS database URL. All three routes require
a NexaChat Bearer token and return `Cache-Control: no-store`.

| Route | Request | Response |
|---|---|---|
| `POST /api/v1/realtime/firebase-token` | Empty body | `200 {customToken, databaseUrl, bridgeId, leaseUntil, transport:"FIREBASE"}`; the custom token carries the Nexa UUID as its Firebase UID and a fresh `bridgeId` claim. |
| `POST /api/v1/realtime/firebase-lease` | `{bridgeId, conversationIds}` with at most 30 current memberships | `200 {leaseUntil, transport:"FIREBASE"}`; renewal fails with `NOT_MEMBER` after a removal, `TOKEN_EXPIRED` for an expired access token, and `TOKEN_INVALID` after logout or password change. |
| `DELETE /api/v1/realtime/firebase-session/{bridgeId}` | None | `204`; revokes only that bridge and its presence record. |

## Installing the rules

1. Review `tools/firebase-rules/database.rules.json`. It is deny-by-default and
   has no macros or environment substitutions.
2. Run the offline contract suite described below against the exact file you
   intend to deploy.
3. Install the reviewed JSON in the Firebase console, or deploy it with the
   Firebase CLI from `tools/firebase-rules` after selecting the production
   project explicitly. The repository does not run a deploy; project selection
   and IAM review are operator actions.
4. Confirm the service account has Firebase Authentication and RTDB
   permissions, and that the S2 projection writer is running **before**
   enabling any client. Without projections, every client read is denied by
   design.

## Offline rules contract tests

Prerequisites: Node.js 20 or newer, Java 21 (the emulator needs Java 11+), and
npm. The first run installs npm dependencies and downloads the pinned RTDB
emulator JAR; later runs need no network and no Google credentials.

```bash
cd tools/firebase-rules
npm ci
npm test
```

`npm test` starts the local database emulator on `127.0.0.1:19007` (hub
`127.0.0.1:19008`), loads `database.rules.json`, and runs the matrix with the
demo project `demo-nexachat-presence`. `firebase emulators:exec` propagates the
Node test exit code, so any failed assertion fails the command. The isolated CI
job `firebase-rules` runs the same steps; it needs no repository secrets. The
existing `server-test` job runs with no `FIREBASE_*` variables, so the disabled
STOMP configuration remains the default server gate.

The matrix covers:

- anonymous denial on every path, parent/list reads, and unknown paths;
- server-only mirror reads, writes and deletes (including self-granted grants);
- presence: owner publish/read, DIRECT peer reads without a contacts mirror,
  `showPresence`, inactive/missing accounts, either-direction blocks, group-only
  peers, membership and session expiry, bridge-claim binding, online/offline
  payload validation, partial writes, unknown fields, node deletion, revoked
  leases, and per-device records;
- typing: owner publish, three-second throttle, five-second TTL ceiling,
  bridge binding, forged timestamps, non-members, expired memberships, block
  and account gates, own-bridge deletion, expired/stale reads, and group typing
  independent of `showPresence`;
- calls: participant reads, non-participant and non-member denial, expiry,
  block and account gates, membership revocation, and denial of every client
  write;
- live listener cancellation when a reader grant is revoked.

It does **not** cover the Firebase Auth emulator or custom-token exchange, the
Admin SDK/IAM path, the S2 projection writer, the Android client, sweeper and
failed-PATCH recovery, billing, or real-device behavior. Those stay separate
rollout gates.

## Known rule gaps

These are properties of the reviewed candidate rules, not of the tests. Tests
marked `KNOWN GAP` assert the current behaviour so a future rules change is
visible. S5 does not edit production rules; the owners of the projection writer
and a future rules revision must decide whether to change them.

| Severity | Gap | Consequence / required mitigation |
|---|---|---|
| MEDIUM | A permitted viewer can read an offline device's `lastSeen` while another device of the same user is online (`/presence/{uid}` returns every bridge record and rules cannot hide a field). | This contradicts the "hide last-seen while online" policy for a modified client. Keep client-side hiding, and either zero the offline markers in the projection or add a server-maintained aggregate that exposes only the logical value. |
| MEDIUM | Rules cannot tell a DIRECT reader edge from a group one: if the server writes a group conversation id to `/presenceReaders`, every member with that id passes the presence check. | S2 must never project a non-DIRECT conversation into `presenceReaders`; add a conversation-type field to the mirror before any other writer exists, or validate the edge in the writer with a test. |
| LOW | Deleting a typing leaf and recreating it bypasses the three-second throttle (`delete` is not rate-limited; `validate` does not run on delete). | Quota/abuse only, acknowledged by the design. Keep operational quotas and alerting. |
| LOW | A client may delete its own presence node instead of writing the offline payload; absence is treated as offline but the last-seen marker is lost. | No cross-user effect. The client contract uses `onDisconnect` with the offline payload; privileged cleanup remains authoritative. |

## Rollout checklist

- [ ] S2 projections merged and tested (including DIRECT-only presence edges).
- [ ] S3 Android transport merged with STOMP fallback and session-epoch fencing.
- [ ] `tools/firebase-rules` suite green in CI on the exact rules file.
- [ ] Service-account IAM and database URL verified for the target project.
- [ ] `FIREBASE_REALTIME_ENABLED` and client flag enabled for a small cohort.
- [ ] Metrics/alerts for bootstrap, renew, mirror retries and fallback count.
- [ ] Rollback proven by setting the flags back to `false` and restarting.
- [ ] Propagation window measured before a wider rollout.

## References

- Firebase RTDB security rules: <https://firebase.google.com/docs/database/security>
- RTDB emulator: <https://firebase.google.com/docs/emulator-suite/connect_rtdb>
- Firebase custom tokens: <https://firebase.google.com/docs/auth/admin/create-custom-tokens>
