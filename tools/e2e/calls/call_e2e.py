#!/usr/bin/env python3
"""Exercise call signaling against a rebuilt local NexaChat server.

Uses dedicated fixture accounts and raw WebSocket/STOMP frames, matching the
Android transport. The primary callers are +84900000121 and +84900000122.
A third supporting account is needed only to put the callee in another active
call while checking the BUSY response.

Usage:
    python tools/e2e/calls/call_e2e.py [--base-url http://localhost:8080]

Needs websocket-client. The script leaves call history and system messages in
the fixture conversations; run it only against a disposable/local database.
Access tokens are never printed.
"""

from __future__ import annotations

import argparse
import json
import sys
import queue
import threading
import time
import uuid
from collections import deque
from pathlib import Path

import websocket

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from e2e_common import (  # noqa: E402
    Client,
    E2EFailure,
    check,
    direct_conversation,
    ensure_user,
    main_guard,
)

CALLER_PHONE = "+84900000121"
CALLEE_PHONE = "+84900000122"
BUSY_PEER_PHONE = "+84900000123"
RING_TIMEOUT_SECONDS = 45.0
HEARTBEAT_INTERVAL_MS = 10_000
HEARTBEAT_INTERVAL_SECONDS = HEARTBEAT_INTERVAL_MS / 1_000


class StompConnection:
    """Minimal STOMP 1.2 client for call and error queues."""

    def __init__(self, ws_url: str, access_token: str, label: str):
        self.label = label
        self.ws = websocket.create_connection(ws_url, timeout=10)
        # A dedicated reader owns every recv() so a short poll timeout can never
        # interrupt a partially received frame or the heartbeat sender's send().
        self.ws.settimeout(None)
        self._inbox: queue.Queue = queue.Queue()
        self._reader = threading.Thread(
            target=self._read_loop, name=f"stomp-reader-{label}", daemon=True
        )
        self._reader.start()
        self._buffer = ""
        self._frames: deque = deque()
        self._observed_frames: deque = deque(maxlen=20)
        self._inbound_heartbeats = 0
        self._subscriptions = 0
        self._heartbeat_stop = threading.Event()
        self._heartbeat_thread = None
        self._send_frame(
            "CONNECT",
            {
                "accept-version": "1.2",
                "heart-beat": f"{HEARTBEAT_INTERVAL_MS},{HEARTBEAT_INTERVAL_MS}",
                "Authorization": "Bearer " + access_token,
            },
            "",
        )
        self.wait_for(lambda frame: frame[0] == "CONNECTED", 10, "CONNECTED")
        self._heartbeat_thread = threading.Thread(
            target=self._send_heartbeats,
            name=f"stomp-heartbeat-{label}",
            daemon=True,
        )
        self._heartbeat_thread.start()
        self.subscribe("/user/queue/calls")
        self.subscribe("/user/queue/errors")

    def _send_frame(self, command: str, headers: dict, body: str) -> None:
        lines = [command] + [f"{key}:{value}" for key, value in headers.items()]
        self.ws.send("\n".join(lines) + "\n\n" + body + "\x00")

    def subscribe(self, destination: str) -> None:
        self._subscriptions += 1
        self._send_frame(
            "SUBSCRIBE",
            {"id": f"sub-{self._subscriptions}", "destination": destination},
            "",
        )

    def send_call(self, conversation_id: str, signal: dict) -> None:
        body = json.dumps(signal, separators=(",", ":"))
        self._send_frame(
            "SEND",
            {
                "destination": f"/app/calls/{conversation_id}/signal",
                "content-type": "application/json",
                "content-length": str(len(body.encode("utf-8"))),
            },
            body,
        )

    def _parse_buffer(self) -> None:
        while "\x00" in self._buffer:
            raw, self._buffer = self._buffer.split("\x00", 1)
            raw = raw.lstrip("\n")
            if not raw.strip():
                continue
            head, _, body = raw.partition("\n\n")
            lines = head.split("\n")
            headers = {}
            for line in lines[1:]:
                if ":" in line:
                    key, value = line.split(":", 1)
                    headers[key] = value
            self._frames.append((lines[0], headers, body))

    def _read_loop(self) -> None:
        while True:
            try:
                chunk = self.ws.recv()
            except Exception as error:  # closed or reset: surface once to the consumer
                self._inbox.put(error)
                return
            self._inbox.put(chunk)
            if chunk is None:
                return

    def _receive(self, timeout: float) -> None:
        try:
            chunk = self._inbox.get(timeout=max(0.05, timeout))
        except queue.Empty:
            return
        if isinstance(chunk, Exception) or chunk is None:
            raise E2EFailure(f"{self.label}: socket closed while waiting for a frame")
        text = chunk if isinstance(chunk, str) else chunk.decode("utf-8")
        if not text.strip("\r\n"):
            self._inbound_heartbeats += 1
            return
        self._buffer += text
        self._parse_buffer()

    def _send_heartbeats(self) -> None:
        while not self._heartbeat_stop.wait(HEARTBEAT_INTERVAL_SECONDS):
            try:
                # Match Android's independent heartbeat coroutine during idle waits.
                self.ws.send("\n")
            except (websocket.WebSocketException, OSError):
                return

    def wait_for(self, predicate, timeout: float, description: str):
        deadline = time.monotonic() + timeout
        while True:
            while self._frames:
                frame = self._frames.popleft()
                if predicate(frame):
                    return frame
                self._remember_frame(frame)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                observed = list(self._observed_frames)
                detail = f"; recent frames: {observed}" if observed else "; no frames received"
                raise E2EFailure(
                    f"{self.label}: timed out after {timeout:.1f}s waiting for {description}{detail}"
                )
            self._receive(remaining)

    def _remember_frame(self, frame) -> None:
        summary = {"command": frame[0]}
        envelope = frame_json(frame)
        if envelope is not None:
            for key in ("eventType", "callId", "code"):
                if envelope.get(key) is not None:
                    summary[key] = envelope[key]
        self._observed_frames.append(summary)

    def wait_event(self, event_type: str, call_id: str, timeout: float = 10.0) -> dict:
        def matches(frame) -> bool:
            envelope = frame_json(frame)
            if frame[0] != "MESSAGE" or envelope is None:
                return False
            if envelope.get("code"):
                raise E2EFailure(f"{self.label}: server returned {envelope['code']}")
            return envelope.get("eventType") == event_type and envelope.get("callId") == call_id

        frame = self.wait_for(matches, timeout, f"{event_type} for {call_id}")
        return frame_json(frame)

    def drain(self, duration: float) -> list:
        collected = []
        deadline = time.monotonic() + duration
        while True:
            while self._frames:
                collected.append(self._frames.popleft())
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return collected
            self._receive(remaining)

    def close(self) -> None:
        self._heartbeat_stop.set()
        if self._heartbeat_thread is not None:
            self._heartbeat_thread.join(timeout=1)
        try:
            self._send_frame("DISCONNECT", {}, "")
        except (websocket.WebSocketException, OSError):
            pass
        try:
            self.ws.close()
        except (websocket.WebSocketException, OSError):
            pass


def frame_json(frame):
    if frame[0] != "MESSAGE":
        return None
    try:
        return json.loads(frame[2])
    except json.JSONDecodeError:
        return None


def signal(kind: str, call_id: str, **fields) -> dict:
    return {"type": kind, "callId": call_id, **fields}


def expect_reason(event: dict, reason: str) -> None:
    check(event.get("payload", {}).get("reason") == reason,
          f"{event.get('eventType')} reason was not {reason}")


def message_sequence(client: Client, token: str, conversation_id: str) -> int:
    response = client.get(
        f"/api/v1/conversations/{conversation_id}/messages",
        token=token,
        params={"limit": 100},
    )
    check(response.status == 200, "could not read conversation history")
    items = (response.json() or {}).get("items", [])
    return max((int(item.get("seq", 0)) for item in items), default=0)


def assert_new_call_line(client: Client, token: str, conversation_id: str,
                         before_seq: int, reason: str, call_id: str) -> None:
    response = client.get(
        f"/api/v1/conversations/{conversation_id}/messages",
        token=token,
        params={"afterSeq": before_seq, "limit": 100},
    )
    check(response.status == 200, "could not read messages after the call")
    lines = [
        item for item in (response.json() or {}).get("items", [])
        if item.get("type") == "SYSTEM"
        and str(item.get("content", "")).startswith(f"CALL|{reason}|")
    ]
    item = history_item(client, token, conversation_id, call_id)
    expected = f"CALL|{reason}|{item.get('durationSeconds')}"
    check(len(lines) == 1 and lines[0].get("content") == expected,
          f"expected exactly one {expected} system line, got {len(lines)}")


def history_item(client: Client, token: str, conversation_id: str, call_id: str) -> dict:
    response = client.get(f"/api/v1/conversations/{conversation_id}/calls", token=token)
    check(response.status == 200, "call history request failed")
    for item in (response.json() or {}).get("items", []):
        if item.get("callId") == call_id:
            return item
    raise E2EFailure(f"call history did not contain {call_id}")


def main() -> int:
    parser = argparse.ArgumentParser(description="NexaChat call signaling E2E")
    parser.add_argument("--base-url", default="http://localhost:8080")
    args = parser.parse_args()

    client = Client(args.base_url)
    ws_url = args.base_url.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
    print("== NexaChat call E2E ==")

    caller = ensure_user(client, CALLER_PHONE, "Call E2E Caller")
    callee = ensure_user(client, CALLEE_PHONE, "Call E2E Callee")
    busy_peer = ensure_user(client, BUSY_PEER_PHONE, "Call E2E Busy Peer")
    caller_id = caller["user"]["id"]
    callee_id = callee["user"]["id"]
    caller_token = caller["accessToken"]
    callee_token = callee["accessToken"]
    caller_conv = direct_conversation(client, caller_token, CALLEE_PHONE)[1]["id"]
    busy_conv = direct_conversation(client, busy_peer["accessToken"], CALLEE_PHONE)[1]["id"]

    connections: list[StompConnection] = []
    try:
        a = StompConnection(ws_url, caller_token, "caller")
        b = StompConnection(ws_url, callee_token, "callee")
        c = StompConnection(ws_url, busy_peer["accessToken"], "busy-peer")
        connections.extend((a, b, c))
        time.sleep(0.3)

        # INVITE -> ACCEPT -> SDP/ICE -> END.
        call_id = str(uuid.uuid4())
        before = message_sequence(client, caller_token, caller_conv)
        a.send_call(caller_conv, signal("INVITE", call_id))
        invite = b.wait_event("CALL_INVITE", call_id)
        check(invite.get("fromUserId") == caller_id, "CALL_INVITE sender mismatch")
        b.send_call(caller_conv, signal("ACCEPT", call_id))
        a.wait_event("CALL_ACCEPTED", call_id)
        b.wait_event("CALL_ACCEPTED", call_id)

        offer = {"type": "offer", "description": "v=0\r\no=nexachat 1 1 IN IP4 127.0.0.1"}
        a.send_call(caller_conv, signal("SDP", call_id, sdp=offer))
        relayed_sdp = b.wait_event("CALL_SDP", call_id)
        check(relayed_sdp["payload"]["sdp"] == offer, "SDP was not relayed verbatim")
        candidate = {
            "sdpMid": "audio",
            "sdpMLineIndex": 0,
            "candidate": "candidate:1 1 UDP 1 127.0.0.1 9 typ host",
        }
        b.send_call(caller_conv, signal("ICE", call_id, candidate=candidate))
        relayed_ice = a.wait_event("CALL_ICE", call_id)
        check(relayed_ice["payload"]["candidate"] == candidate,
              "ICE candidate was not relayed verbatim")
        a.send_call(caller_conv, signal("END", call_id))
        ended_a = a.wait_event("CALL_ENDED", call_id)
        ended_b = b.wait_event("CALL_ENDED", call_id)
        expect_reason(ended_a, "COMPLETED")
        expect_reason(ended_b, "COMPLETED")
        check(history_item(client, caller_token, caller_conv, call_id)["endReason"] == "COMPLETED",
              "completed call missing from history")
        assert_new_call_line(client, caller_token, caller_conv, before, "COMPLETED", call_id)
        print("[PASS] invite, accept, SDP/ICE relay, end, history, and one system line")

        # Put B in a separate call so A can receive a true BUSY response.
        active_busy_id = str(uuid.uuid4())
        c_before = message_sequence(client, busy_peer["accessToken"], busy_conv)
        c.send_call(busy_conv, signal("INVITE", active_busy_id))
        b.wait_event("CALL_INVITE", active_busy_id)
        b.send_call(busy_conv, signal("ACCEPT", active_busy_id))
        c.wait_event("CALL_ACCEPTED", active_busy_id)
        b.wait_event("CALL_ACCEPTED", active_busy_id)

        busy_id = str(uuid.uuid4())
        a_before = message_sequence(client, caller_token, caller_conv)
        a.send_call(caller_conv, signal("INVITE", busy_id))
        busy = a.wait_event("CALL_BUSY", busy_id)
        expect_reason(busy, "BUSY")
        check(not any(
            (envelope := frame_json(frame))
            and envelope.get("eventType") == "CALL_BUSY"
            and envelope.get("callId") == busy_id
            for frame in b.drain(0.5)
        ), "busy callee unexpectedly received CALL_BUSY")
        check(history_item(client, caller_token, caller_conv, busy_id)["endReason"] == "BUSY",
              "busy call missing from history")
        assert_new_call_line(client, caller_token, caller_conv, a_before, "BUSY", busy_id)
        c.send_call(busy_conv, signal("END", active_busy_id))
        c.wait_event("CALL_ENDED", active_busy_id)
        b.wait_event("CALL_ENDED", active_busy_id)
        assert_new_call_line(client, busy_peer["accessToken"], busy_conv, c_before,
                             "COMPLETED", active_busy_id)
        print("[PASS] busy callee notified only through CALL_BUSY to caller")

        # Rejection.
        reject_id = str(uuid.uuid4())
        before = message_sequence(client, caller_token, caller_conv)
        a.send_call(caller_conv, signal("INVITE", reject_id))
        b.wait_event("CALL_INVITE", reject_id)
        b.send_call(caller_conv, signal("REJECT", reject_id))
        rejected_a = a.wait_event("CALL_REJECTED", reject_id)
        rejected_b = b.wait_event("CALL_REJECTED", reject_id)
        expect_reason(rejected_a, "REJECTED")
        expect_reason(rejected_b, "REJECTED")
        check(history_item(client, caller_token, caller_conv, reject_id)["endReason"] == "REJECTED",
              "rejected call missing from history")
        assert_new_call_line(client, caller_token, caller_conv, before, "REJECTED", reject_id)
        print("[PASS] reject and call history")

        # Caller cancellation while still ringing.
        cancel_id = str(uuid.uuid4())
        before = message_sequence(client, caller_token, caller_conv)
        a.send_call(caller_conv, signal("INVITE", cancel_id))
        b.wait_event("CALL_INVITE", cancel_id)
        a.send_call(caller_conv, signal("CANCEL", cancel_id))
        cancelled_a = a.wait_event("CALL_CANCELLED", cancel_id)
        cancelled_b = b.wait_event("CALL_CANCELLED", cancel_id)
        expect_reason(cancelled_a, "CANCELLED")
        expect_reason(cancelled_b, "CANCELLED")
        check(history_item(client, caller_token, caller_conv, cancel_id)["endReason"] == "CANCELLED",
              "cancelled call missing from history")
        assert_new_call_line(client, caller_token, caller_conv, before, "CANCELLED", cancel_id)
        print("[PASS] cancel and call history")

        # Production/default timeout (no test-only fast flag).
        timeout_id = str(uuid.uuid4())
        before = message_sequence(client, caller_token, caller_conv)
        timeout_started = time.monotonic()
        a.send_call(caller_conv, signal("INVITE", timeout_id))
        b.wait_event("CALL_INVITE", timeout_id)
        # The idle callee must keep exchanging heartbeats while the caller waits, as a real client does.
        ring_deadline = time.monotonic() + RING_TIMEOUT_SECONDS + 12
        while True:
            b._receive(0.2)
            try:
                timed_out_a = a.wait_event("CALL_TIMEOUT", timeout_id, 1)
                break
            except E2EFailure:
                if time.monotonic() >= ring_deadline:
                    raise
        timed_out_b = b.wait_event("CALL_TIMEOUT", timeout_id, 10)
        elapsed = time.monotonic() - timeout_started
        expect_reason(timed_out_a, "MISSED")
        expect_reason(timed_out_b, "MISSED")
        check(elapsed >= RING_TIMEOUT_SECONDS - 1,
              f"timeout arrived early after {elapsed:.1f}s")
        check(history_item(client, caller_token, caller_conv, timeout_id)["endReason"] == "MISSED",
              "missed call missing from history")
        assert_new_call_line(client, caller_token, caller_conv, before, "MISSED", timeout_id)
        check(a._inbound_heartbeats >= 2,
              f"caller received only {a._inbound_heartbeats} server heartbeats during ringing")
        print(
            f"[PASS] 45-second timeout ({elapsed:.1f}s), "
            f"{a._inbound_heartbeats} server heartbeats, history, and system line"
        )
        return 0
    finally:
        for connection in connections:
            connection.close()


if __name__ == "__main__":
    main_guard(main)
