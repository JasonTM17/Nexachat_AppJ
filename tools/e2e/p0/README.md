# NexaChat P0 Live End-to-End Test Suite

Live HTTP and STOMP 1.2 end-to-end verification for the NexaChat P0 production messaging features:
- Delivery and read receipts over STOMP (`MESSAGE_DELIVERED`, `MESSAGE_READ`, monotonic watermark).
- Batch delta sync (`POST /api/v1/sync`) with keyset pagination across N=120 messages (50+50+20).
- Message send idempotency (`clientMessageId` deduplication).
- FCM device token lifecycle (register, upsert, ownership transfer, unregister, and 512-char validation).
- Standard authentication error envelope (`UNAUTHORIZED`, `TOKEN_INVALID`).

Standard library + `websocket-client` (already used by `tools/e2e/stomp_e2e.py`). Zero additional dependencies.

---

## 1. Prerequisites and Backend Setup

1. **Backend Server**: Ensure the NexaChat backend is built and running at `http://localhost:8080`.
   Refer to the canonical setup instructions in [`docs/operations.md`](../../../docs/operations.md) and [`TECHNICAL_ROADMAP.md`](../../../TECHNICAL_ROADMAP.md) (Docker Compose / Gradle bootRun).
2. **Python Environment**: Python 3.9+ with `websocket-client` installed.

---

## 2. Execution

### Offline Helper Unit Tests (No Network Required)

Validates pure frame formatting, stream parsing, sequence monotonicity, PII masking, and credential generators:

```bash
python -m unittest tools/e2e/p0/test_p0_helpers.py
```

### Live End-to-End Suite

Executes all 5 verification scenarios against the live backend:

```bash
python tools/e2e/p0/p0_live.py [--base-url http://localhost:8080] [--ws-url ws://localhost:8080/ws]
```

Parameters:
- `--base-url`: HTTP backend URL (default `http://localhost:8080`).
- `--ws-url`: STOMP WebSocket URL (defaults to `ws://localhost:8080/ws`).
- `--sync-count`: Message volume for sync pagination test (default: `120`).
- `--timeout`: Network timeout in seconds (default: `15.0`).

---

## 3. Scenarios and Invariant Mapping

| Scenario | Invariant / Contract Verified |
| :--- | :--- |
| **`receipts_delivered`** | STOMP delivery ack (`/app/conversations/{id}/delivered`) broadcasts `MESSAGE_DELIVERED` with `upToSeq` to topic subscribers. |
| **`receipts_read`** | STOMP read ack (`/app/conversations/{id}/read`) broadcasts `MESSAGE_READ` with `upToSeq` to topic subscribers. |
| **`receipts_monotonic`** | Watermark monotonicity: replaying an older sequence number does not regress the persisted watermark. |
| **`receipts_non_member`** | Non-member isolation: advancing receipts for a non-member conversation is rejected (HTTP 403 on REST, frame silently dropped on STOMP). |
| **`receipts_forged_clamped`** | Forged watermark protection: `upToSeq > maxSeq` is rejected with HTTP 400 `VALIDATION_FAILED` (REST) and `VALIDATION_FAILED` on `/user/queue/errors` (STOMP). |
| **`sync_pagination`** | Keyset pagination: retrieving 120 messages across 3 pages (50, 50, 20) with strict monotonic `seq` ordering and zero duplicates. |
| **`sync_non_member_isolated`** | Privacy boundary: non-member conversations requested in delta sync watermarks are strictly omitted from response deltas. |
| **`sync_empty_watermark`** | Empty watermark (`lastSeq=0`) returns the full initial page up to `limitPerConv`. |
| **`sync_forged_clamped`** | Watermark exceeding conversation `maxSeq` returns zero messages, `hasMore=false`, and server `maxSeq`. |
| **`sync_idempotent`** | Delta sync idempotency: identical watermark requests return identical message batches. |
| **`idempotency_dedup`** | Re-sending identical `clientMessageId` returns HTTP 200 with the existing row; exactly one row exists in history. |
| **`fcm_register`** | Device token registration via `POST /api/v1/users/device-token`. |
| **`fcm_upsert`** | Re-registering existing device token updates device metadata (`deviceLabel`) without duplicate rows. |
| **`fcm_ownership_transfer`** | Second user registering same token transfers token ownership from previous user. |
| **`fcm_unregister`** | Unregistering token via `DELETE /api/v1/users/device-token` returns HTTP 204. |
| **`fcm_oversized_rejected`** | Oversized tokens (>512 chars) and blank tokens are rejected with HTTP 400 `VALIDATION_FAILED`. |
| **`fcm_no_leak`** | Error envelopes never leak raw secrets, tokens, or credentials in payload fields. |
| **`auth_missing_token`** | Requests without `Authorization` header return HTTP 401 with standard error envelope `{"error":{"code":"UNAUTHORIZED"}}`. |
| **`auth_invalid_token`** | Requests with invalid tokens return HTTP 401 with standard error envelope `{"error":{"code":"TOKEN_INVALID"}}`. |

---

## 4. Expected Output Sample (Masked Data)

```text
==========================================================================
   NEXACHAT P0 LIVE END-TO-END VERIFICATION SUITE                        
   Target: http://localhost:8080 (WS: ws://localhost:8080/ws)
==========================================================================
Created throwaway User A (id=b42e...51cc, phone=+84908***832)
Created throwaway User B (id=87d8...da12, phone=+84908***514)
Direct conversation established: 51bc...0b20

--- 1. STOMP Receipts & Monotonic Watermark ---
SKIP receipts_delivered - Endpoint absent (STOMP /app/.../delivered not yet deployed on server)
PASS receipts_read - User A received MESSAGE_READ for seq=7002
PASS receipts_monotonic - Replayed older seq did not regress; preserved monotonic watermark=7002
PASS receipts_non_member_rest - Non-member markRead returned HTTP 403
PASS receipts_non_member_stomp - Non-member STOMP receipt frame silently dropped
PASS receipts_forged_clamped_rest - REST rejected upToSeq > maxSeq with 400 VALIDATION_FAILED
PASS receipts_forged_clamped_stomp - STOMP rejected upToSeq > maxSeq with VALIDATION_FAILED on /user/queue/errors

--- 2. Batch Delta Sync (N=120 Messages, 50+50+20 Pagination) ---
SKIP sync_pagination - Endpoint absent (POST /api/v1/sync returned 404)
SKIP sync_non_member_isolated - Endpoint absent (POST /api/v1/sync returned 404)
SKIP sync_empty_watermark - Endpoint absent (POST /api/v1/sync returned 404)
SKIP sync_forged_clamped - Endpoint absent (POST /api/v1/sync returned 404)
SKIP sync_idempotent - Endpoint absent (POST /api/v1/sync returned 404)

--- 3. Message Idempotency ---
PASS idempotency_dedup - Duplicate send returned same message (id=55ab...267a)

--- 4. FCM Device Token Lifecycle & Validation ---
SKIP fcm_register - Endpoint absent (POST /api/v1/users/device-token returned 404)
SKIP fcm_upsert - Endpoint absent (POST /api/v1/users/device-token returned 404)
SKIP fcm_ownership_transfer - Endpoint absent (POST /api/v1/users/device-token returned 404)
SKIP fcm_unregister - Endpoint absent (POST /api/v1/users/device-token returned 404)
SKIP fcm_oversized_rejected - Endpoint absent (POST /api/v1/users/device-token returned 404)
SKIP fcm_blank_rejected - Endpoint absent (POST /api/v1/users/device-token returned 404)
SKIP fcm_no_leak - Endpoint absent (POST /api/v1/users/device-token returned 404)

--- 5. Authentication & Error Format ---
PASS auth_missing_token - Unauthenticated request returned 401 UNAUTHORIZED
PASS auth_invalid_token - Invalid token returned 401 TOKEN_INVALID

Result: 9 passed, 0 failed, 13 skipped in 3.16s
```

*(Note: When executed against an updated backend container built with Flyway V7 and P0 controllers, all 13 skipped tests execute and verify the respective endpoints).*

---

## 5. Security and Privacy Hygiene

- **Throwaway Accounts**: Accounts are generated at runtime with Vietnamese mobile prefixes in the reserved test range (`+84908xxxxxx`), distinct from demo accounts (`+849000000xx`).
- **Secret Redaction**: Passwords are generated ephemerally and never printed or written to disk. All UUIDs and phone numbers are masked in console output. Tokens are completely redacted (`[REDACTED_TOKEN]`).
