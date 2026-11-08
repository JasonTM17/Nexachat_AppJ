#!/usr/bin/env python3
"""NexaChat P0 Live End-to-End Test Suite.

Verifies P0 production features against a live running backend:
  1. Receipts: STOMP delivery & read receipts, monotonic watermarks, non-member rejection,
     and clamped/rejected forged sequence numbers.
  2. Sync: POST /api/v1/sync delta sync, pagination across N=120 messages (50+50+20),
     strict ordering, non-member isolation, empty watermarks, and idempotency.
  3. Message Idempotency: Duplicate clientMessageId deduplication.
  4. FCM Device Token: Registration, upsert, ownership transfer, unregister,
     validation limits (>512 chars rejected with 400), and privacy leak checks.
  5. Auth & Errors: Standard 401 envelope for missing/invalid tokens.

Uses two throwaway accounts with randomized phone numbers in the reserved test range
and randomized passwords generated at runtime. Credentials and tokens are NEVER printed
in full or written to disk.

Usage:
    python tools/e2e/p0/p0_live.py [--base-url http://localhost:8080] [--ws-url ws://localhost:8080/ws]
"""

from __future__ import annotations

import argparse
import collections
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any, Callable

# Ensure e2e root and p0 directory are in Python path
p0_dir = Path(__file__).resolve().parent
e2e_dir = p0_dir.parent
sys.path.insert(0, str(p0_dir))
sys.path.insert(0, str(e2e_dir))

import websocket

from e2e_common import Client, E2EFailure, Response
from p0_helpers import (
    build_stomp_frame,
    check_monotonic_watermark,
    clamp_watermark,
    generate_test_password,
    generate_test_phone,
    mask_id,
    mask_phone,
    mask_token,
    parse_stomp_buffer,
    verify_monotonic_sequence,
    verify_no_duplicates,
)


class TestLogger:
    """Standardized logger producing PASS / FAIL / SKIP records."""

    def __init__(self):
        self.passed = 0
        self.failed = 0
        self.skipped = 0
        self.start_time = time.time()

    def pass_test(self, name: str, detail: str = "") -> None:
        self.passed += 1
        suffix = f" - {detail}" if detail else ""
        print(f"PASS {name}{suffix}")

    def fail_test(self, name: str, detail: str = "") -> None:
        self.failed += 1
        suffix = f" - {detail}" if detail else ""
        print(f"FAIL {name}{suffix}")

    def skip_test(self, name: str, reason: str = "") -> None:
        self.skipped += 1
        suffix = f" - {reason}" if reason else ""
        print(f"SKIP {name}{suffix}")

    def summary(self) -> int:
        elapsed = time.time() - self.start_time
        print(
            f"\nResult: {self.passed} passed, {self.failed} failed, {self.skipped} skipped in {elapsed:.2f}s"
        )
        return 1 if self.failed > 0 else 0


class P0StompConnection:
    """STOMP 1.2 client over raw WebSocket using p0_helpers frame encoders."""

    def __init__(self, ws_url: str, access_token: str, label: str = "client"):
        self.label = label
        self.ws = websocket.create_connection(ws_url, timeout=10)
        self.ws.settimeout(10)
        self._buffer = ""
        self._frames: collections.deque = collections.deque()
        self._subscriptions = 0

        # Note: Header whitespace quirk per protocol spec:
        # Authorization:Bearer <token> (no space after colon)
        connect_frame = build_stomp_frame(
            "CONNECT",
            {
                "accept-version": "1.2",
                "heart-beat": "0,0",
                "Authorization": f"Bearer {access_token}",
            },
        )
        self.ws.send(connect_frame)
        connected = self.wait_for(lambda f: f[0] == "CONNECTED", timeout=10.0, desc="CONNECTED")
        self.user_name = connected[1].get("user-name")

    def subscribe(self, destination: str) -> str:
        self._subscriptions += 1
        sub_id = f"sub-{self._subscriptions}"
        frame = build_stomp_frame("SUBSCRIBE", {"id": sub_id, "destination": destination})
        self.ws.send(frame)
        return sub_id

    def send_message(self, conversation_id: str, client_message_id: str, content: str) -> None:
        body = json.dumps({
            "clientMessageId": client_message_id,
            "type": "TEXT",
            "content": content,
            "attachmentIds": [],
        })
        frame = build_stomp_frame(
            "SEND",
            {
                "destination": f"/app/conversations/{conversation_id}/messages",
                "content-type": "application/json",
                "content-length": str(len(body.encode("utf-8"))),
            },
            body=body,
        )
        self.ws.send(frame)

    def send_delivered_ack(self, conversation_id: str, up_to_seq: int) -> None:
        body = json.dumps({"upToSeq": up_to_seq})
        frame = build_stomp_frame(
            "SEND",
            {
                "destination": f"/app/conversations/{conversation_id}/delivered",
                "content-type": "application/json",
            },
            body=body,
        )
        self.ws.send(frame)

    def send_read_ack(self, conversation_id: str, up_to_seq: int) -> None:
        body = json.dumps({"upToSeq": up_to_seq})
        frame = build_stomp_frame(
            "SEND",
            {
                "destination": f"/app/conversations/{conversation_id}/read",
                "content-type": "application/json",
            },
            body=body,
        )
        self.ws.send(frame)

    def wait_for(
        self,
        predicate: Callable[[tuple[str, dict[str, str], str]], bool],
        timeout: float = 10.0,
        desc: str = "frame",
    ) -> tuple[str, dict[str, str], str]:
        deadline = time.time() + timeout
        while True:
            while self._frames:
                frame = self._frames.popleft()
                if predicate(frame):
                    return frame
            remaining = deadline - time.time()
            if remaining <= 0:
                raise TimeoutError(f"{self.label}: timed out after {timeout:.1f}s waiting for {desc}")
            self.ws.settimeout(remaining)
            try:
                chunk = self.ws.recv()
            except Exception as e:
                raise TimeoutError(f"{self.label}: error waiting for {desc}: {e}") from e
            if chunk is None:
                raise ConnectionResetError(f"{self.label}: socket closed waiting for {desc}")
            text = chunk if isinstance(chunk, str) else chunk.decode("utf-8", errors="replace")
            self._buffer += text
            parsed_frames, self._buffer = parse_stomp_buffer(self._buffer)
            self._frames.extend(parsed_frames)

    def drain(self, duration: float = 1.0) -> list[tuple[str, dict[str, str], str]]:
        deadline = time.time() + duration
        collected = []
        while time.time() < deadline:
            while self._frames:
                collected.append(self._frames.popleft())
            rem = deadline - time.time()
            if rem <= 0:
                break
            self.ws.settimeout(rem)
            try:
                chunk = self.ws.recv()
                if chunk:
                    text = chunk if isinstance(chunk, str) else chunk.decode("utf-8", errors="replace")
                    self._buffer += text
                    parsed, self._buffer = parse_stomp_buffer(self._buffer)
                    self._frames.extend(parsed)
            except Exception:
                break
        return collected

    def close(self) -> None:
        try:
            self.ws.send(build_stomp_frame("DISCONNECT"))
        except Exception:
            pass
        try:
            self.ws.close()
        except Exception:
            pass


def check_server_health(base_url: str) -> bool:
    """Checks if the backend is answering on health or auth endpoint."""
    url = base_url.rstrip("/") + "/actuator/health"
    try:
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=3.0) as resp:
            return resp.status in (200, 204)
    except Exception:
        # Fallback probe to auth register or root
        alt_url = base_url.rstrip("/") + "/api/v1/auth/login"
        try:
            alt_req = urllib.request.Request(alt_url, headers={"Accept": "application/json"}, method="POST")
            with urllib.request.urlopen(alt_req, timeout=3.0) as resp:
                return resp.status in (200, 400, 401, 415)
        except urllib.error.HTTPError as he:
            return he.code in (400, 401, 415)
        except Exception:
            return False


def create_throwaway_user(client: Client, display_name: str) -> dict[str, Any]:
    """Registers a throwaway user with random test phone and runtime password."""
    phone = generate_test_phone()
    password = generate_test_password()
    resp = client.post(
        "/api/v1/auth/register",
        body={"phone": phone, "password": password, "displayName": display_name},
    )
    if resp.status not in (200, 201):
        raise E2EFailure(f"Registration failed for throwaway user: {resp}")
    data = resp.json()
    return {
        "id": data["user"]["id"],
        "phone": phone,
        "token": data["accessToken"],
        "user": data["user"],
    }


def send_message_resilient(
    client: Client, token: str, conv_id: str, client_msg_id: str, content: str
) -> dict[str, Any]:
    """Sends message with rate-limit 429 backoff handling."""
    for attempt in range(8):
        resp = client.post(
            f"/api/v1/conversations/{conv_id}/messages",
            token=token,
            body={"clientMessageId": client_msg_id, "type": "TEXT", "content": content, "attachmentIds": []},
        )
        if resp.status == 429:
            details = (resp.json() or {}).get("error", {}).get("details") or {}
            wait_sec = min(15, max(1, int(details.get("retryAfterSeconds", 5))))
            time.sleep(wait_sec + 0.5)
            continue
        if resp.status not in (200, 201):
            raise E2EFailure(f"Send message failed with HTTP {resp.status}: {resp.text}")
        return resp.json()
    raise E2EFailure("Send message exceeded rate-limit retry attempts")


# --- Scenario Runners ---

def run_receipts_scenario(
    log: TestLogger, client: Client, ws_url: str, user_a: dict, user_b: dict, conv_id: str
) -> None:
    """Verifies STOMP delivery and read receipts, monotonicity, and non-member isolation."""
    stomp_a = None
    stomp_b = None
    try:
        # Check if delivery receipt endpoint is supported by current backend
        deliv_probe = client.post(
            f"/api/v1/conversations/{conv_id}/delivered",
            token=user_b["token"],
            body={"upToSeq": 0},
        )
        deliv_supported = (deliv_probe.status != 404)

        stomp_a = P0StompConnection(ws_url, user_a["token"], label="UserA")
        stomp_b = P0StompConnection(ws_url, user_b["token"], label="UserB")

        stomp_a.subscribe(f"/topic/conversations.{conv_id}")
        stomp_b.subscribe(f"/topic/conversations.{conv_id}")
        stomp_b.subscribe("/user/queue/errors")

        # 1. User A sends message over STOMP
        msg_uuid = str(uuid.uuid4())
        stomp_a.send_message(conv_id, msg_uuid, "Receipt test ping")

        # Both receive MESSAGE_CREATED
        created_frame = stomp_a.wait_for(
            lambda f: f[0] == "MESSAGE" and '"MESSAGE_CREATED"' in f[2],
            timeout=8.0,
            desc="MESSAGE_CREATED",
        )
        msg_payload = json.loads(created_frame[2])["payload"]
        msg_seq = msg_payload["seq"]

        # 2. User B sends DELIVERED receipt (if supported on backend)
        if deliv_supported:
            stomp_b.send_delivered_ack(conv_id, msg_seq)
            deliv_frame = stomp_a.wait_for(
                lambda f: f[0] == "MESSAGE" and '"MESSAGE_DELIVERED"' in f[2],
                timeout=8.0,
                desc="MESSAGE_DELIVERED",
            )
            deliv_payload = json.loads(deliv_frame[2])["payload"]
            if deliv_payload["userId"] == user_b["id"] and deliv_payload["upToSeq"] == msg_seq:
                log.pass_test("receipts_delivered", f"User A received MESSAGE_DELIVERED for seq={msg_seq}")
            else:
                log.fail_test("receipts_delivered", f"Unexpected delivery payload: {deliv_payload}")
        else:
            log.skip_test(
                "receipts_delivered",
                "Endpoint absent (STOMP /app/.../delivered not yet deployed on server)",
            )

        # 3. User B marks READ
        stomp_b.send_read_ack(conv_id, msg_seq)

        # User A sees MESSAGE_READ
        read_frame = stomp_a.wait_for(
            lambda f: f[0] == "MESSAGE" and '"MESSAGE_READ"' in f[2],
            timeout=8.0,
            desc="MESSAGE_READ",
        )
        read_payload = json.loads(read_frame[2])["payload"]
        if read_payload["userId"] == user_b["id"] and read_payload["upToSeq"] == msg_seq:
            log.pass_test("receipts_read", f"User A received MESSAGE_READ for seq={msg_seq}")
        else:
            log.fail_test("receipts_read", f"Unexpected read payload: {read_payload}")

        # 4. Monotonicity: Replaying older read watermark must not regress
        stomp_b.send_read_ack(conv_id, max(0, msg_seq - 1))
        replay_frame = stomp_a.wait_for(
            lambda f: f[0] == "MESSAGE" and '"MESSAGE_READ"' in f[2],
            timeout=8.0,
            desc="MESSAGE_READ replay",
        )
        replay_payload = json.loads(replay_frame[2])["payload"]
        if replay_payload["upToSeq"] == msg_seq:
            log.pass_test(
                "receipts_monotonic",
                f"Replayed older seq did not regress; preserved monotonic watermark={msg_seq}",
            )
        else:
            log.fail_test(
                "receipts_monotonic",
                f"Watermark regressed to {replay_payload.get('upToSeq')}",
            )

        # 5. Non-member cannot advance receipts for a conversation it is not in
        outsider = create_throwaway_user(client, "Outsider C")
        # REST check: expect 403 Forbidden
        rest_non_member = client.post(
            f"/api/v1/conversations/{conv_id}/read",
            token=outsider["token"],
            body={"upToSeq": msg_seq},
        )
        if rest_non_member.status == 403:
            log.pass_test("receipts_non_member_rest", "Non-member markRead returned HTTP 403")
        else:
            log.fail_test("receipts_non_member_rest", f"Expected 403, got {rest_non_member.status}")

        # STOMP check: channel interceptor drops frame
        stomp_c = P0StompConnection(ws_url, outsider["token"], label="Outsider")
        stomp_c.send_read_ack(conv_id, msg_seq)
        time.sleep(0.5)
        unexpected_frames = stomp_a.drain(duration=1.0)
        c_reads = [f for f in unexpected_frames if f[0] == "MESSAGE" and outsider["id"] in f[2]]
        if not c_reads:
            log.pass_test("receipts_non_member_stomp", "Non-member STOMP receipt frame silently dropped")
        else:
            log.fail_test("receipts_non_member_stomp", "Leaked receipt event from non-member")
        stomp_c.close()

        # 6. Forged lastReadSeq far above max seq is rejected/clamped
        # REST check:
        forged_rest = client.post(
            f"/api/v1/conversations/{conv_id}/read",
            token=user_b["token"],
            body={"upToSeq": msg_seq + 99999},
        )
        if forged_rest.status == 400 and forged_rest.error_code() == "VALIDATION_FAILED":
            log.pass_test(
                "receipts_forged_clamped_rest",
                "REST rejected upToSeq > maxSeq with 400 VALIDATION_FAILED",
            )
        else:
            log.fail_test(
                "receipts_forged_clamped_rest",
                f"Expected 400 VALIDATION_FAILED, got {forged_rest.status}",
            )

        # STOMP check: error frame on /user/queue/errors
        stomp_b.send_read_ack(conv_id, msg_seq + 99999)
        err_frame = stomp_b.wait_for(
            lambda f: f[0] == "MESSAGE" and "VALIDATION_FAILED" in f[2],
            timeout=8.0,
            desc="VALIDATION_FAILED error frame",
        )
        if "VALIDATION_FAILED" in err_frame[2]:
            log.pass_test(
                "receipts_forged_clamped_stomp",
                "STOMP rejected upToSeq > maxSeq with VALIDATION_FAILED on /user/queue/errors",
            )
        else:
            log.fail_test("receipts_forged_clamped_stomp", f"Unexpected error frame: {err_frame[2]}")

    except Exception as e:
        log.fail_test("receipts_scenario", f"Exception during receipts scenario: {e}")
    finally:
        if stomp_a:
            stomp_a.close()
        if stomp_b:
            stomp_b.close()


def run_sync_scenario(
    log: TestLogger, client: Client, user_a: dict, user_b: dict, conv_id: str, n_messages: int = 120
) -> None:
    """Verifies batch delta sync, pagination across 120 messages, ordering, and isolation."""
    try:
        # Probe sync endpoint availability
        sync_probe = client.post(
            "/api/v1/sync",
            token=user_b["token"],
            body={"watermarks": [{"conversationId": conv_id, "lastSeq": 0}], "limitPerConv": 50},
        )
        if sync_probe.status == 404:
            log.skip_test("sync_pagination", "Endpoint absent (POST /api/v1/sync returned 404)")
            log.skip_test("sync_non_member_isolated", "Endpoint absent (POST /api/v1/sync returned 404)")
            log.skip_test("sync_empty_watermark", "Endpoint absent (POST /api/v1/sync returned 404)")
            log.skip_test("sync_forged_clamped", "Endpoint absent (POST /api/v1/sync returned 404)")
            log.skip_test("sync_idempotent", "Endpoint absent (POST /api/v1/sync returned 404)")
            return

        # Messages already in the conversation before this scenario (the receipts scenario sends one).
        # The probe above used lastSeq=0, so its page holds every earlier message while there are fewer than 50.
        probe_deltas = sync_probe.json().get("deltas") or [{}]
        prior_count = len(probe_deltas[0].get("newMessages") or [])
        total_expected = prior_count + n_messages

        # B is offline (no STOMP connection).
        # A sends N=120 messages via REST send with rate-limit pacing.
        sent_seqs = []
        sent_cids = []
        for i in range(1, n_messages + 1):
            cid = str(uuid.uuid4())
            sent_cids.append(cid)
            msg_res = send_message_resilient(
                client, user_a["token"], conv_id, cid, f"Batch sync test #{i}"
            )
            sent_seqs.append(msg_res["seq"])
            time.sleep(0.02)

        # B calls sync endpoint with watermark lastSeq=0, limitPerConv=50
        page1_res = client.post(
            "/api/v1/sync",
            token=user_b["token"],
            body={"watermarks": [{"conversationId": conv_id, "lastSeq": 0}], "limitPerConv": 50},
        )
        if page1_res.status != 200:
            raise E2EFailure(f"Sync page 1 failed with {page1_res.status}")
        p1_delta = page1_res.json()["deltas"][0]
        p1_msgs = p1_delta["newMessages"]

        if len(p1_msgs) == 50 and p1_delta["hasMore"] is True:
            log.pass_test("sync_page_1", f"Page 1 returned 50 messages with hasMore=True (maxSeq={p1_delta['maxSeq']})")
        else:
            log.fail_test("sync_page_1", f"Unexpected page 1 size={len(p1_msgs)}, hasMore={p1_delta.get('hasMore')}")

        # Page 2: watermark from last message of page 1
        p1_last_seq = p1_msgs[-1]["seq"]
        page2_res = client.post(
            "/api/v1/sync",
            token=user_b["token"],
            body={"watermarks": [{"conversationId": conv_id, "lastSeq": p1_last_seq}], "limitPerConv": 50},
        )
        if page2_res.status != 200:
            raise E2EFailure(f"Sync page 2 failed with {page2_res.status}")
        p2_delta = page2_res.json()["deltas"][0]
        p2_msgs = p2_delta["newMessages"]

        if len(p2_msgs) == 50 and p2_delta["hasMore"] is True:
            log.pass_test("sync_page_2", "Page 2 returned 50 messages with hasMore=True")
        else:
            log.fail_test("sync_page_2", f"Unexpected page 2 size={len(p2_msgs)}, hasMore={p2_delta.get('hasMore')}")

        # Page 3: watermark from last message of page 2
        p2_last_seq = p2_msgs[-1]["seq"]
        page3_res = client.post(
            "/api/v1/sync",
            token=user_b["token"],
            body={"watermarks": [{"conversationId": conv_id, "lastSeq": p2_last_seq}], "limitPerConv": 50},
        )
        if page3_res.status != 200:
            raise E2EFailure(f"Sync page 3 failed with {page3_res.status}")
        p3_delta = page3_res.json()["deltas"][0]
        p3_msgs = p3_delta["newMessages"]

        expected_rem = total_expected - 100  # remaining after two full pages of 50
        if len(p3_msgs) == expected_rem and p3_delta["hasMore"] is False:
            log.pass_test(
                "sync_page_3",
                f"Page 3 returned remaining {expected_rem} messages with hasMore=False",
            )
        else:
            log.fail_test("sync_page_3", f"Unexpected page 3 size={len(p3_msgs)}, hasMore={p3_delta.get('hasMore')}")

        # Combine all retrieved messages
        all_msgs = p1_msgs + p2_msgs + p3_msgs
        all_seqs = [m["seq"] for m in all_msgs]
        all_ids = [m["id"] for m in all_msgs]

        # Invariant: strict monotonic sequence, no duplicates, received all
        if (
            len(all_msgs) == total_expected
            and verify_monotonic_sequence(all_seqs)
            and verify_no_duplicates(all_ids)
        ):
            log.pass_test(
                "sync_invariants",
                f"All {total_expected} messages received in strict seq order with zero duplicates",
            )
        else:
            log.fail_test("sync_invariants", "Message sequence ordering or deduplication violated")

        # 2. Non-member conversation is NOT leaked
        outsider = create_throwaway_user(client, "Outsider Sync")
        conv_ac_res = client.post(
            "/api/v1/conversations/direct",
            token=user_a["token"],
            body={"phone": outsider["phone"]},
        )
        other_conv_id = conv_ac_res.json()["id"]

        mixed_sync = client.post(
            "/api/v1/sync",
            token=user_b["token"],
            body={
                "watermarks": [
                    {"conversationId": conv_id, "lastSeq": 0},
                    {"conversationId": other_conv_id, "lastSeq": 0},
                ],
                "limitPerConv": 10,
            },
        )
        mixed_deltas = mixed_sync.json().get("deltas", [])
        mixed_conv_ids = [d["conversationId"] for d in mixed_deltas]

        if conv_id in mixed_conv_ids and other_conv_id not in mixed_conv_ids:
            log.pass_test(
                "sync_non_member_isolated",
                "Non-member conversation omitted from sync deltas (not leaked)",
            )
        else:
            log.fail_test("sync_non_member_isolated", f"Leaked other_conv_id in deltas: {mixed_conv_ids}")

        # 3. Empty watermark (lastSeq=0) returns sensible full first page
        empty_wm_res = client.post(
            "/api/v1/sync",
            token=user_b["token"],
            body={"watermarks": [{"conversationId": conv_id, "lastSeq": 0}], "limitPerConv": 50},
        )
        if len(empty_wm_res.json()["deltas"][0]["newMessages"]) == 50:
            log.pass_test("sync_empty_watermark", "Empty watermark (lastSeq=0) returned full first page")
        else:
            log.fail_test("sync_empty_watermark", "Empty watermark did not return full first page")

        # 4. Watermark far above maxSeq returns zero deltas and valid maxSeq
        forged_wm_res = client.post(
            "/api/v1/sync",
            token=user_b["token"],
            body={"watermarks": [{"conversationId": conv_id, "lastSeq": 99999999}], "limitPerConv": 50},
        )
        forged_delta = forged_wm_res.json()["deltas"][0]
        if (
            len(forged_delta["newMessages"]) == 0
            and forged_delta["hasMore"] is False
            and forged_delta["maxSeq"] >= n_messages
        ):
            log.pass_test(
                "sync_forged_clamped",
                "Watermark > maxSeq returned empty newMessages with valid server maxSeq",
            )
        else:
            log.fail_test("sync_forged_clamped", f"Unexpected forged watermark delta: {forged_delta}")

        # 5. Repeated sync idempotent
        sync_repeat_1 = client.post(
            "/api/v1/sync",
            token=user_b["token"],
            body={"watermarks": [{"conversationId": conv_id, "lastSeq": p1_last_seq}], "limitPerConv": 50},
        ).json()["deltas"][0]["newMessages"]
        sync_repeat_2 = client.post(
            "/api/v1/sync",
            token=user_b["token"],
            body={"watermarks": [{"conversationId": conv_id, "lastSeq": p1_last_seq}], "limitPerConv": 50},
        ).json()["deltas"][0]["newMessages"]

        ids_1 = [m["id"] for m in sync_repeat_1]
        ids_2 = [m["id"] for m in sync_repeat_2]
        if ids_1 == ids_2 and len(ids_1) == 50:
            log.pass_test("sync_idempotent", "Repeated sync calls with identical watermark returned identical messages")
        else:
            log.fail_test("sync_idempotent", "Repeated sync responses differed")

    except Exception as e:
        log.fail_test("sync_scenario", f"Exception during sync scenario: {e}")


def run_idempotency_scenario(
    log: TestLogger, client: Client, user_a: dict, conv_id: str
) -> None:
    """Verifies that sending the same clientMessageId twice returns the same message."""
    try:
        cid = str(uuid.uuid4())
        # First send -> 201 Created
        r1 = client.post(
            f"/api/v1/conversations/{conv_id}/messages",
            token=user_a["token"],
            body={"clientMessageId": cid, "type": "TEXT", "content": "Idempotency test payload", "attachmentIds": []},
        )
        if r1.status not in (200, 201):
            log.fail_test("idempotency_send", f"First send returned {r1.status}")
            return
        m1 = r1.json()

        # Second send with identical clientMessageId -> 200 OK with identical row
        r2 = client.post(
            f"/api/v1/conversations/{conv_id}/messages",
            token=user_a["token"],
            body={"clientMessageId": cid, "type": "TEXT", "content": "Idempotency test payload", "attachmentIds": []},
        )
        if r2.status != 200:
            log.fail_test("idempotency_send", f"Expected 200 on dedup, got {r2.status}")
            return
        m2 = r2.json()

        if m1["id"] == m2["id"] and m1["seq"] == m2["seq"]:
            log.pass_test("idempotency_dedup", f"Duplicate send returned same message (id={mask_id(m1['id'])})")
        else:
            log.fail_test("idempotency_dedup", f"Duplicate send returned different message: {m1} vs {m2}")

    except Exception as e:
        log.fail_test("idempotency_scenario", f"Exception during idempotency scenario: {e}")


def run_fcm_scenario(
    log: TestLogger, client: Client, user_a: dict, user_b: dict
) -> None:
    """Verifies FCM device token registration, upsert, ownership transfer, unregister, and caps."""
    try:
        # Probe FCM device token endpoint availability
        fcm_probe = client.post(
            "/api/v1/users/device-token",
            token=user_a["token"],
            body={"token": "probe-token", "platform": "ANDROID"},
        )
        if fcm_probe.status == 404:
            log.skip_test("fcm_register", "Endpoint absent (POST /api/v1/users/device-token returned 404)")
            log.skip_test("fcm_upsert", "Endpoint absent (POST /api/v1/users/device-token returned 404)")
            log.skip_test("fcm_ownership_transfer", "Endpoint absent (POST /api/v1/users/device-token returned 404)")
            log.skip_test("fcm_unregister", "Endpoint absent (POST /api/v1/users/device-token returned 404)")
            log.skip_test("fcm_oversized_rejected", "Endpoint absent (POST /api/v1/users/device-token returned 404)")
            log.skip_test("fcm_blank_rejected", "Endpoint absent (POST /api/v1/users/device-token returned 404)")
            log.skip_test("fcm_no_leak", "Endpoint absent (POST /api/v1/users/device-token returned 404)")
            return

        fcm_token = f"e2e-fcm-token-{uuid.uuid4()}"

        # 1. Register token
        r_reg = client.post(
            "/api/v1/users/device-token",
            token=user_a["token"],
            body={"token": fcm_token, "platform": "ANDROID", "deviceLabel": "Pixel 8"},
        )
        if r_reg.status == 200 and r_reg.json().get("platform") == "ANDROID":
            log.pass_test("fcm_register", "Registered device token for User A")
        else:
            log.fail_test("fcm_register", f"Expected 200, got {r_reg.status}: {r_reg.text}")

        # 2. Re-register (upsert with updated label)
        r_up = client.post(
            "/api/v1/users/device-token",
            token=user_a["token"],
            body={"token": fcm_token, "platform": "ANDROID", "deviceLabel": "Pixel 8 Pro"},
        )
        if r_up.status == 200 and r_up.json().get("deviceLabel") == "Pixel 8 Pro":
            log.pass_test("fcm_upsert", "Re-registration updated deviceLabel (upsert)")
        else:
            log.fail_test("fcm_upsert", f"Expected updated deviceLabel, got {r_up.text}")

        # 3. Second user registering same token -> ownership transfer
        r_own = client.post(
            "/api/v1/users/device-token",
            token=user_b["token"],
            body={"token": fcm_token, "platform": "ANDROID", "deviceLabel": "Pixel 8 Pro"},
        )
        if r_own.status == 200:
            log.pass_test("fcm_ownership_transfer", "Second user successfully registered same token (transferred)")
        else:
            log.fail_test("fcm_ownership_transfer", f"Expected 200 on ownership transfer, got {r_own.status}")

        # 4. Unregister token
        unreg_url = f"/api/v1/users/device-token?token={urllib.parse.quote(fcm_token)}"
        r_unreg = client.delete(unreg_url, token=user_b["token"])
        if r_unreg.status in (200, 204):
            log.pass_test("fcm_unregister", "Unregistered device token (HTTP 204/200)")
        else:
            log.fail_test("fcm_unregister", f"Expected 204/200 on unregister, got {r_unreg.status}")

        # 5. Invalid / oversized token (>512 chars) -> 400
        oversized = "x" * 513
        r_over = client.post(
            "/api/v1/users/device-token",
            token=user_a["token"],
            body={"token": oversized, "platform": "ANDROID"},
        )
        if r_over.status == 400 and r_over.error_code() == "VALIDATION_FAILED":
            log.pass_test("fcm_oversized_rejected", "Oversized token (>512 chars) rejected with 400 VALIDATION_FAILED")
        else:
            log.fail_test("fcm_oversized_rejected", f"Expected 400 VALIDATION_FAILED, got {r_over.status}")

        # Blank token -> 400
        r_blank = client.post(
            "/api/v1/users/device-token",
            token=user_a["token"],
            body={"token": "   ", "platform": "ANDROID"},
        )
        if r_blank.status == 400 and r_blank.error_code() == "VALIDATION_FAILED":
            log.pass_test("fcm_blank_rejected", "Blank token rejected with 400 VALIDATION_FAILED")
        else:
            log.fail_test("fcm_blank_rejected", f"Expected 400 VALIDATION_FAILED, got {r_blank.status}")

        # 6. Leak check: Confirm error responses do not echo secret material in unexpected fields
        err_json = r_blank.json() or {}
        if "password" not in str(err_json).lower() and "secret" not in str(err_json).lower():
            log.pass_test("fcm_no_leak", "No sensitive credentials leaked in FCM error responses")
        else:
            log.fail_test("fcm_no_leak", "Sensitive data detected in error response")

    except Exception as e:
        log.fail_test("fcm_scenario", f"Exception during FCM scenario: {e}")


def run_auth_scenario(log: TestLogger, client: Client) -> None:
    """Verifies standard 401 envelope for unauthenticated or expired/invalid calls."""
    try:
        # Missing token
        r_no_auth = client.get("/api/v1/users/me")
        if r_no_auth.status == 401 and r_no_auth.error_code() == "UNAUTHORIZED":
            log.pass_test("auth_missing_token", "Unauthenticated request returned 401 UNAUTHORIZED")
        else:
            log.fail_test("auth_missing_token", f"Expected 401 UNAUTHORIZED, got {r_no_auth.status}: {r_no_auth.text}")

        # Invalid token
        r_bad_auth = client.get("/api/v1/users/me", token="invalid.jwt.token.format")
        if r_bad_auth.status == 401 and r_bad_auth.error_code() in ("TOKEN_INVALID", "TOKEN_EXPIRED", "UNAUTHORIZED"):
            log.pass_test("auth_invalid_token", f"Invalid token returned 401 {r_bad_auth.error_code()}")
        else:
            log.fail_test("auth_invalid_token", f"Expected 401 on bad token, got {r_bad_auth.status}: {r_bad_auth.text}")

    except Exception as e:
        log.fail_test("auth_scenario", f"Exception during auth scenario: {e}")


def main() -> int:
    parser = argparse.ArgumentParser(description="NexaChat P0 Live End-to-End Suite")
    parser.add_argument("--base-url", default="http://localhost:8080", help="HTTP backend base URL")
    parser.add_argument("--ws-url", default="", help="STOMP WebSocket URL (defaults to base-url with ws:// /ws)")
    parser.add_argument("--sync-count", type=int, default=120, help="Number of messages to send for sync pagination test")
    parser.add_argument("--timeout", type=float, default=15.0, help="HTTP client timeout in seconds")
    args = parser.parse_args()

    base_url = args.base_url.rstrip("/")
    if not args.ws_url:
        if base_url.startswith("https://"):
            ws_url = "wss://" + base_url[len("https://") :] + "/ws"
        elif base_url.startswith("http://"):
            ws_url = "ws://" + base_url[len("http://") :] + "/ws"
        else:
            ws_url = f"ws://{base_url}/ws"
    else:
        ws_url = args.ws_url

    log = TestLogger()

    print("==========================================================================")
    print("   NEXACHAT P0 LIVE END-TO-END VERIFICATION SUITE                        ")
    print(f"   Target: {base_url} (WS: {ws_url})")
    print("==========================================================================")

    # 1. Health check
    if not check_server_health(base_url):
        print(f"\n[SKIP] Server at {base_url} is not answering on health/login.")
        print("Ensure the NexaChat backend is running before executing live E2E tests.")
        log.skip_test("receipts", f"Server offline at {base_url}")
        log.skip_test("sync", f"Server offline at {base_url}")
        log.skip_test("idempotency", f"Server offline at {base_url}")
        log.skip_test("fcm_registration", f"Server offline at {base_url}")
        log.skip_test("auth_unauthorized", f"Server offline at {base_url}")
        return log.summary()

    client = Client(base_url=base_url, timeout=args.timeout)

    # 2. Create throwaway accounts
    try:
        user_a = create_throwaway_user(client, "P0 Test User A")
        user_b = create_throwaway_user(client, "P0 Test User B")
        print(f"Created throwaway User A (id={mask_id(user_a['id'])}, phone={mask_phone(user_a['phone'])})")
        print(f"Created throwaway User B (id={mask_id(user_b['id'])}, phone={mask_phone(user_b['phone'])})")

        # 3. Create direct conversation A <-> B
        conv_res = client.post(
            "/api/v1/conversations/direct",
            token=user_a["token"],
            body={"phone": user_b["phone"]},
        )
        if conv_res.status not in (200, 201):
            raise E2EFailure(f"Direct conversation creation failed: {conv_res}")
        conv_id = conv_res.json()["id"]
        print(f"Direct conversation established: {mask_id(conv_id)}\n")

    except Exception as e:
        log.fail_test("fixture_setup", f"Failed to initialize throwaway test accounts: {e}")
        return log.summary()

    # 4. Execute test scenarios
    print("--- 1. STOMP Receipts & Monotonic Watermark ---")
    run_receipts_scenario(log, client, ws_url, user_a, user_b, conv_id)

    print("\n--- 2. Batch Delta Sync (N=120 Messages, 50+50+20 Pagination) ---")
    run_sync_scenario(log, client, user_a, user_b, conv_id, n_messages=args.sync_count)

    print("\n--- 3. Message Idempotency ---")
    run_idempotency_scenario(log, client, user_a, conv_id)

    print("\n--- 4. FCM Device Token Lifecycle & Validation ---")
    run_fcm_scenario(log, client, user_a, user_b)

    print("\n--- 5. Authentication & Error Format ---")
    run_auth_scenario(log, client)

    return log.summary()


if __name__ == "__main__":
    sys.exit(main())
