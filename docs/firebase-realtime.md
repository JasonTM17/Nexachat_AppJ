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
| `/control/presencePrivacyVersion` | server | Peer presence reads require version `2`. The authorization backfill removes legacy offline timestamp rows before publishing this marker; it is written atomically with reopening the global gate. |
| `/accounts/{uid}/{active,showPresence}` | server | Account state mirror. `showPresence=false` denies peers, not the owner. |
| `/sessions/{uid}/{bridgeId}/until` | server | Bridge lease in epoch milliseconds, at most 120 seconds and never beyond the Nexa access-token expiry. |
| `/members/{conversationId}/{uid}` | server | Membership lease in epoch milliseconds, at most 120 seconds, regenerated from PostgreSQL. |
| `/presenceReaders/{subjectUid}/{viewerUid}` | server | `{cid,type:"DIRECT"}` identifies the DIRECT conversation that authorizes the viewer. Rules require the explicit type and active membership for both users. |
| `/blocked/{uid}/{otherUid}` | server | Reserved block mirror. Empty until an authoritative block feature exists; both directions deny. |
| `/presence/{uid}/{bridgeId}` | owning client | `{online,lastSeen,updatedAt,leaseUntil}`. One record per connection, so multiple devices coexist. Offline records use zero for all timestamp and lease fields; the server presence path remains authoritative for last-seen time. |
| `/typing/{conversationId}/{uid}` | member client | `{bridgeId,updatedAt,expiresAt}`; `expiresAt` is at most five seconds after server time and writes are throttled to one per three seconds. Clients cannot delete the leaf to reset the throttle; TTL and the server sweeper clear it. |
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
  denial until legacy-data migration is marked complete,
  `showPresence`, inactive/missing accounts, either-direction blocks, group-only
  peers, membership and session expiry, bridge-claim binding, online/offline
  payload validation, zeroed offline metadata, partial writes, unknown fields,
  denied client deletion, revoked leases, and per-device records;
- typing: owner publish, three-second throttle, five-second TTL ceiling,
  bridge binding, forged timestamps, non-members, expired memberships, block
  and account gates, denied client deletion and delete/recreate bypass, expired/stale
  reads, and group typing independent of `showPresence`;
- calls: participant reads, non-participant and non-member denial, expiry,
  block and account gates, membership revocation, and denial of every client
  write;
- live listener cancellation when a reader grant is revoked.

It does **not** cover the Firebase Auth emulator or custom-token exchange, the
Admin SDK/IAM path, the S2 projection writer, the Android client, sweeper and
failed-PATCH recovery, billing, or real-device behavior. Those stay separate
rollout gates.

## Presence and typing privacy guarantees

- Offline per-device presence carries no time or lease information: `lastSeen`,
  `updatedAt` and `leaseUntil` must all be zero. Firebase presence therefore
  reports online/offline state only; last-seen time remains on the authoritative
  server presence path.
- Peer reads remain denied until the server backfill has removed legacy
  offline `lastSeen` rows from every account and atomically publishes
  `control/presencePrivacyVersion: 2` with `control/enabled: true`. A failed or
  partial migration leaves peer reads closed. The marker is one-time; current
  rules prevent clients from recreating timestamp-bearing offline rows.
- Presence readers carry an explicit `type:"DIRECT"` and `cid`; rules require
  both active membership leases. Legacy string edges and group edges are denied.
- Client presence deletion is denied. `onDisconnect` writes the zeroed offline
  payload, while server revocation and the bounded lease sweeper can remove rows.
- Client typing deletion is denied because RTDB does not validate deletes and
  that would reset the three-second throttle. The Android client leaves a
  five-second TTL record; the server sweeper removes expired leaves.

## Rollout checklist

- [ ] S2 projections merged and tested (including DIRECT-only presence edges).
- [ ] S3 Android transport merged with STOMP fallback and session-epoch fencing.
- [ ] `tools/firebase-rules` suite green in CI on the exact rules file.
- [ ] Install the exact reviewed rules before enabling the server backfill; peer reads stay closed until privacy version 2 is published.
- [ ] Drain older server instances that can write timestamped offline presence before the one-time privacy backfill runs.
- [ ] Capture a runtime receipt proving peer reads are denied before migration and admitted only after cleanup and marker publication.
- [ ] Service-account IAM and database URL verified for the target project.
- [ ] `FIREBASE_REALTIME_ENABLED` and client flag enabled for a small cohort.
- [ ] Metrics/alerts for bootstrap, renew, mirror retries and fallback count.
- [ ] Rollback proven by setting the flags back to `false` and restarting.
- [ ] Propagation window measured before a wider rollout.

## References

- Firebase RTDB security rules: <https://firebase.google.com/docs/database/security>
- RTDB emulator: <https://firebase.google.com/docs/emulator-suite/connect_rtdb>
- Firebase custom tokens: <https://firebase.google.com/docs/auth/admin/create-custom-tokens>
