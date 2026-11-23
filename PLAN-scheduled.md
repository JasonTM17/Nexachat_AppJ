---
status: in-progress
---
# v1.2.18 — quality hardening + scheduled messages

Outcome: ship v1.2.18 (server + Android) to production with: Firebase revocation fix (#291),
inbox collapsing header on short screens, a11y oracle clipping fix, AAB release artifact,
and the new Scheduled messages feature (user-approved 2026-11-23: "Tất cả luôn nhé";
in-chat search and multi-pin already exist — no work). Storage: leave Supabase as is (user), runbook note only.

Non-goals: E2EE scheduled messages, attachments in scheduled messages, editing a scheduled message (cancel + recreate).

## Phases
1. #291 merge (CI green).
2. Scheduled messages — server (V46, REST, dispatcher mirroring reminder claim/lease pattern).
3. Scheduled messages — Android (long-press send → schedule; list sheet with cancel; EN/VI).
4. Inbox collapsing header (short screens) — Android.
5. a11y oracle: exclude viewport/scroll-clipped nodes from size/label checks (E2E harness, opencode worker).
6. AAB: release workflow also builds + attaches a signed .aab (+ sha256).
7. Docs: CHANGELOG, release notes v1.2.18, runbook Supabase pause note, API docs.
8. Release: PR CI 8/8, backup, deploy SHA, smoke --write, tag, verify APK/AAB, upgrade test.

## Scheduled messages design (settled)
- Table scheduled_messages(id uuid pk, conversation_id fk cascade, sender_id fk cascade, client_message_id uuid not null,
  body text not null (1..max message length), send_at timestamptz not null, status varchar check in (PENDING,SENT,CANCELED,FAILED),
  failure_code varchar null, sent_message_id uuid null, claimed_at/claim_token pair, created_at, updated_at;
  unique(sender_id, client_message_id); partial index (send_at,id) where status='PENDING'; index (conversation_id, sender_id, send_at) where PENDING.
- REST under /api/v1/conversations/{id}/scheduled-messages: POST {clientMessageId, body, sendAt} → 201 (idempotent replay → 200 same row);
  GET → caller's PENDING rows for that conversation ordered by send_at; DELETE /{scheduledId} → 204 (only own PENDING; SENT/CANCELED → 409).
  Rules: member only (404 like other conversation routes for non-members); E2EE conversation → 409 E2EE_UNSUPPORTED (reuse existing error code if one exists);
  sendAt in [now+60s, now+365d] else 400; ≤ 50 PENDING per sender per conversation → 409 SCHEDULED_LIMIT.
- Dispatcher: @Scheduled poll (configurable, default 15s) + claim with FOR UPDATE SKIP LOCKED and a lease (mirror ReminderClaimService);
  at send: re-check membership/E2EE; send through MessageService.send(senderId, conversationId, SendMessageRequest with clientMessageId = row.client_message_id)
  so a crash between send and mark is deduplicated by messages_sender_client_unique; mark SENT + sent_message_id; on business failure mark FAILED with code
  (NOT_MEMBER, E2EE, ...); transient failure → release claim for retry. Catches up on boot (Render free sleeps; sends late, never early).
- Account deletion: rows cascade with the user (check AccountDeletionService for explicit cleanup lists).

## Wukong (GLM 5.3) adversarial review — settled amendments
Full report: run/release/wukong-sched.out. All 11 findings accepted; design below supersedes.

1. Ghost sends from deleted accounts: users are tombstoned (deleted_at), not row-deleted — cascade never fires.
   → `deleteLockedAccount` adds `UPDATE scheduled_messages SET status='CANCELED', failure_code='ACCOUNT_DELETED' WHERE sender_id=? AND status='PENDING'`;
   dispatch-time guard `findAuthValidityById(senderId).isPresent()` else FAILED(ACCOUNT_DELETED).
2. Cancel-vs-dispatch race: DELETE only when `status='PENDING' AND (claimed_at IS NULL OR claimed_at < now()-lease)` else 409 DISPATCHING;
   mark-SENT guarded by `status='PENDING' AND claim_token=:token`.
3. Crash between send and mark drops FCM push: `sendAndMark` runs in ONE @Transactional (send + token-guarded mark together).
   Claim is its own short txn; send+mark in a second txn; never hold claim-scan locks into send.
4. Dedicated ThreadPoolTaskScheduler for the dispatcher (mirror ReminderScheduler), not shared @Scheduled pool.
5. sendAndMark per row on a small worker pool (4 threads) — each row txn is atomic, parallel-safe.
6. FAILED only for an allowlist: NOT_MEMBER, E2EE_REQUIRED/E2EE_NOT_ENABLED, VALIDATION_FAILED, ACCOUNT_DELETED, SCHEDULED_LIMIT.
   SUSPENDED/BLOCKED/transient → release with exponential backoff (min(2^n*15s, 10min)); dead-letter FAILED(FAILED_RETRY_EXHAUSTED) after send_at+24h.
7. POST idempotent replay returns 200 only when existing row is PENDING; else 409 echoing status.
   GET returns PENDING + FAILED(with failure_code) + recent SENT for the caller; FAILED transition publishes an event so author's devices learn.
8. Terminal transitions scrub `body` to '' (idempotency key + audit fields remain) — retention/E2EE-privacy hygiene.
9. Documented: claim txn and send+mark txn never overlap; no claim-scan locks held during send.
10. POST clamps sendAt to [now()+60s, now()+365d] instead of rejecting; response echoes the clamped value.
11. Poll loop drains batches within one tick up to a 30s budget before yielding.
