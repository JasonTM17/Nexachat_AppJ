#!/usr/bin/env python3
"""NexaChat STOMP end-to-end test against the real backend.

Drives two seed users over raw WebSocket + STOMP 1.2 (same wire the Android
client uses) and asserts the message lifecycle events and the privacy-aware
presence contract:

  * MESSAGE_CREATED (live send and clientMessageId re-broadcast)
  * MESSAGE_UPDATED payload == the REST MessageResponse
  * MESSAGE_DELETED payload {messageId, deletedAt}
  * USER_ONLINE / USER_OFFLINE with the 5 s flap coalescing
  * no presence at all while showPresence=false
  * RATE_LIMITED on /user/queue/errors past 30 SENDs / 10 s

Usage:
    python tools/e2e/stomp_e2e.py [--base-url http://localhost:8080]

Needs `websocket-client` (already present on the reference machine); no other
third-party package. Tokens are never printed.
"""

from __future__ import annotations

import argparse
import json
import time
import uuid
from collections import deque

import websocket

from e2e_common import (
    Client,
    E2EFailure,
    check,
    direct_conversation,
    ensure_user,
    history,
    login,
    main_guard,
    send_text,
)

# Dedicated fixture accounts: presence is per-user, so the demo accounts
# (which may hold a live session from another client) cannot prove it.
SUBJECT_PHONE = "+84900000101"
OBSERVER_PHONE = "+84900000102"

COALESCE_SECONDS = 5.0


class StompConnection:
    """Minimal STOMP 1.2 client: CONNECT / SUBSCRIBE / SEND / DISCONNECT."""

    def __init__(self, ws_url: str, access_token: str, label: str):
        self.label = label
        self.ws = websocket.create_connection(ws_url, timeout=10)
        self.ws.settimeout(10)
        self._buffer = ""
        self._frames: deque = deque()
        self.skipped: list = []
        self._send_frame(
            "CONNECT",
            {
                "accept-version": "1.2",
                "heart-beat": "0,0",
                "Authorization": "Bearer " + access_token,
            },
            "",
        )
        frame = self.wait_for(lambda f: f[0] == "CONNECTED", 10, "CONNECTED")
        self.user_name = frame[1].get("user-name")
        self._subscriptions = 0

    # -- wire ----------------------------------------------------------
    def _send_frame(self, command: str, headers: dict, body: str) -> None:
        lines = [command] + [f"{key}:{value}" for key, value in headers.items()]
        self.ws.send("\n".join(lines) + "\n\n" + body + "\x00")

    def subscribe(self, destination: str) -> str:
        self._subscriptions += 1
        subscription_id = f"sub-{self._subscriptions}"
        self._send_frame(
            "SUBSCRIBE", {"id": subscription_id, "destination": destination}, ""
        )
        return subscription_id

    def send_message(self, conversation_id: str, client_message_id: str, content: str) -> None:
        body = json.dumps(
            {
                "clientMessageId": client_message_id,
                "type": "TEXT",
                "content": content,
                "attachmentIds": [],
            }
        )
        self._send_frame(
            "SEND",
            {
                "destination": f"/app/conversations/{conversation_id}/messages",
                "content-type": "application/json",
                "content-length": str(len(body.encode("utf-8"))),
            },
            body,
        )

    # -- receive -------------------------------------------------------
    def _parse_buffer(self) -> None:
        while "\x00" in self._buffer:
            raw, self._buffer = self._buffer.split("\x00", 1)
            raw = raw.lstrip("\n")
            if not raw.strip():
                continue
            head, _, body = raw.partition("\n\n")
            lines = head.split("\n")
            command = lines[0]
            headers = {}
            for line in lines[1:]:
                if ":" in line:
                    key, value = line.split(":", 1)
                    headers[key] = value
            self._frames.append((command, headers, body))

    def wait_for(self, predicate, timeout: float, description: str):
        deadline = time.time() + timeout
        while True:
            while self._frames:
                frame = self._frames.popleft()
                if predicate(frame):
                    return frame
                self.skipped.append(frame)
            remaining = deadline - time.time()
            if remaining <= 0:
                raise E2EFailure(
                    f"{self.label}: timed out after {timeout:.1f}s waiting for {description}"
                )
            self.ws.settimeout(remaining)
            try:
                chunk = self.ws.recv()
            except websocket.WebSocketTimeoutException:
                raise E2EFailure(
                    f"{self.label}: timed out after {timeout:.1f}s waiting for {description}"
                )
            if chunk is None:
                raise E2EFailure(f"{self.label}: socket closed while waiting for {description}")
            self._buffer += chunk if isinstance(chunk, str) else chunk.decode("utf-8")
            self._parse_buffer()

    def drain(self, duration: float) -> list:
        """Collect every frame that arrives in the next `duration` seconds."""
        collected = list(self._collect_queued())
        deadline = time.time() + duration
        while True:
            remaining = deadline - time.time()
            if remaining <= 0:
                break
            self.ws.settimeout(remaining)
            try:
                chunk = self.ws.recv()
            except websocket.WebSocketTimeoutException:
                break
            self._buffer += chunk if isinstance(chunk, str) else chunk.decode("utf-8")
            self._parse_buffer()
            collected.extend(self._collect_queued())
        return collected

    def _collect_queued(self):
        while self._frames:
            yield self._frames.popleft()

    def close(self) -> None:
        try:
            self._send_frame("DISCONNECT", {}, "")
        except OSError:
            pass
        try:
            self.ws.close()
        except OSError:
            pass


def event_envelope(frame):
    command, headers, body = frame
    if command != "MESSAGE":
        return None
    return json.loads(body)


def message_event(frame, event_type: str):
    envelope = event_envelope(frame)
    return envelope is not None and envelope.get("eventType") == event_type


def presence_event(frame, event_type: str, user_id: str):
    envelope = event_envelope(frame)
    return (
        envelope is not None
        and envelope.get("eventType") == event_type
        and envelope.get("payload", {}).get("userId") == user_id
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="NexaChat STOMP E2E")
    parser.add_argument("--base-url", default="http://localhost:8080")
    args = parser.parse_args()

    client = Client(args.base_url)
    ws_url = args.base_url.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
    print("== NexaChat STOMP E2E ==")
    stamp = time.strftime("%Y%m%d-%H%M%S")
    session_a = ensure_user(client, SUBJECT_PHONE, "E2E Subject")
    session_b = ensure_user(client, OBSERVER_PHONE, "E2E Observer")
    token_a, me_a = session_a["accessToken"], session_a["user"]["id"]
    token_b, me_b = session_b["accessToken"], session_b["user"]["id"]
    conversation_id = direct_conversation(client, token_a, OBSERVER_PHONE)[1]["id"]

    created: list[str] = []
    passed = 0
    started = time.time()

    def ok(name: str, detail: str = "") -> None:
        nonlocal passed
        passed += 1
        print(f"[PASS] {name}" + (f" - {detail}" if detail else ""))

    a = None
    b = None
    try:
        # B subscribes first so A's connect is observable.
        b = StompConnection(ws_url, token_b, "B")
        check(b.user_name == me_b, f"B connected as {b.user_name}, expected {me_b}")
        b.subscribe(f"/topic/conversations.{conversation_id}")
        b.subscribe("/user/queue/presence")
        b.subscribe("/user/queue/errors")
        # SUBSCRIBE is processed asynchronously; let the broker register the
        # presence queue before A connects, or the first event can be missed.
        time.sleep(0.5)

        a = StompConnection(ws_url, token_a, "A")
        check(a.user_name == me_a, f"A connected as {a.user_name}, expected {me_a}")
        a.subscribe(f"/topic/conversations.{conversation_id}")
        a.subscribe("/user/queue/errors")

        frame = b.wait_for(
            lambda f: presence_event(f, "USER_ONLINE", me_a), 8, "USER_ONLINE(A)"
        )
        envelope = event_envelope(frame)
        check(envelope["conversationId"] is None, "USER_ONLINE leaked a conversationId")
        check(envelope["payload"]["online"] is True, "USER_ONLINE online != true")
        check(envelope["payload"]["lastSeenAt"] is None, "USER_ONLINE lastSeenAt != null")
        ok("presence USER_ONLINE", json.dumps(envelope["payload"]))

        # ------------------------------------------------ MESSAGE_CREATED
        content_1 = f"e2e-stomp-{stamp}-created"
        client_message_id = str(uuid.uuid4())
        a.send_message(conversation_id, client_message_id, content_1)
        frame = b.wait_for(
            lambda f: message_event(f, "MESSAGE_CREATED"), 8, "MESSAGE_CREATED on B"
        )
        created_event = event_envelope(frame)
        payload = created_event["payload"]
        check(payload["content"] == content_1, "MESSAGE_CREATED content mismatch")
        check(payload["clientMessageId"] == client_message_id, "MESSAGE_CREATED key mismatch")
        check(created_event["conversationId"] == conversation_id, "wrong conversationId")
        message_id = payload["id"]
        created.append(message_id)
        own = a.wait_for(
            lambda f: message_event(f, "MESSAGE_CREATED"), 8, "MESSAGE_CREATED echo on A"
        )
        check(
            event_envelope(own)["payload"]["id"] == message_id,
            "sender echo carries another message id",
        )
        ok(
            "MESSAGE_CREATED delivered to peer + sender",
            f"id={message_id} seq={payload['seq']}",
        )

        # ------------------------------------------------ MESSAGE_UPDATED
        edited_text = content_1 + " edited"
        edited = client.patch(
            f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
            token=token_a,
            body={"content": edited_text},
        )
        check(edited.status == 200, f"REST edit failed: {edited}")
        rest_row = edited.json()
        frame = b.wait_for(
            lambda f: message_event(f, "MESSAGE_UPDATED"), 8, "MESSAGE_UPDATED on B"
        )
        update_payload = event_envelope(frame)["payload"]
        check(update_payload == rest_row, "MESSAGE_UPDATED payload != REST MessageResponse")
        check(bool(update_payload.get("editedAt")), "MESSAGE_UPDATED has no editedAt")
        ok(
            "MESSAGE_UPDATED payload equals REST row",
            f"editedAt={update_payload['editedAt']}",
        )

        # ------------------------------------------------ MESSAGE_DELETED
        deleted = client.delete(
            f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
            token=token_a,
        )
        check(deleted.status == 204, f"REST delete failed: {deleted}")
        frame = b.wait_for(
            lambda f: message_event(f, "MESSAGE_DELETED"), 8, "MESSAGE_DELETED on B"
        )
        deleted_envelope = event_envelope(frame)
        deleted_payload = deleted_envelope["payload"]
        check(
            {"messageId", "deletedAt"} <= set(deleted_payload),
            f"MESSAGE_DELETED payload keys {sorted(deleted_payload)}",
        )
        check(deleted_payload["messageId"] == message_id, "MESSAGE_DELETED wrong id")
        check(deleted_payload.get("deletedAt"), "MESSAGE_DELETED has no deletedAt")
        ok(
            "MESSAGE_DELETED carries {messageId, deletedAt}",
            json.dumps(deleted_payload),
        )

        # --------------------------------- duplicate-send re-broadcast
        content_2 = f"e2e-stomp-{stamp}-dedup"
        dedup_key = str(uuid.uuid4())
        a.send_message(conversation_id, dedup_key, content_2)
        first = b.wait_for(
            lambda f: message_event(f, "MESSAGE_CREATED"), 8, "first dedup broadcast"
        )
        first_id = event_envelope(first)["payload"]["id"]
        created.append(first_id)
        a.send_message(conversation_id, dedup_key, content_2)
        second = b.wait_for(
            lambda f: message_event(f, "MESSAGE_CREATED"), 8, "re-broadcast"
        )
        check(
            event_envelope(second)["payload"]["id"] == first_id,
            "duplicate send re-broadcast a different message id",
        )
        rows = [
            item
            for item in history(client, token_b, conversation_id, limit=50)["items"]
            if item.get("clientMessageId") == dedup_key
        ]
        check(len(rows) == 1, f"duplicate send stored {len(rows)} rows")
        ok("duplicate SEND re-broadcasts the same row", f"id={first_id}, 1 row")

        # --------------------------------------- presence coalescing
        a.ws.settimeout(10)
        a.close()
        a = None
        offline_started = time.time()
        frame = b.wait_for(
            lambda f: presence_event(f, "USER_OFFLINE", me_a), 10, "USER_OFFLINE(A)"
        )
        offline_after = time.time() - offline_started
        offline_payload = event_envelope(frame)["payload"]
        check(offline_payload["online"] is False, "USER_OFFLINE online != false")
        check(offline_payload.get("lastSeenAt"), "USER_OFFLINE lastSeenAt is null")
        check(
            offline_after >= COALESCE_SECONDS - 0.5,
            f"USER_OFFLINE after {offline_after:.2f}s, coalescing window not honoured",
        )
        ok(
            "USER_OFFLINE after coalescing window",
            f"{offline_after:.2f}s, payload={json.dumps(offline_payload)}",
        )

        a = StompConnection(ws_url, token_a, "A")
        a.subscribe(f"/topic/conversations.{conversation_id}")
        a.subscribe("/user/queue/errors")
        frame = b.wait_for(
            lambda f: presence_event(f, "USER_ONLINE", me_a), 8, "USER_ONLINE after reconnect"
        )
        check(event_envelope(frame)["payload"]["online"] is True, "reconnect online != true")
        ok("USER_ONLINE after reconnect", json.dumps(event_envelope(frame)["payload"]))

        # Flap: leave and come back inside the window -> no frames at all.
        b.drain(0.5)
        a.close()
        a = None
        time.sleep(1.5)
        a = StompConnection(ws_url, token_a, "A")
        a.subscribe(f"/topic/conversations.{conversation_id}")
        a.subscribe("/user/queue/errors")
        flapped = [
            frame
            for frame in b.drain(COALESCE_SECONDS + 2.0)
            if event_envelope(frame)
            and event_envelope(frame).get("eventType") in ("USER_ONLINE", "USER_OFFLINE")
        ]
        check(
            flapped == [],
            f"flap inside the window emitted {[event_envelope(f)['eventType'] for f in flapped]}",
        )
        ok("5 s flap coalescing", "reconnect inside the window emits nothing")

        # ---------------------------------------------- privacy switch
        hidden = client.patch(
            "/api/v1/users/me/settings", token=token_a, body={"showPresence": False}
        )
        check(hidden.status == 200, f"hide presence failed: {hidden}")
        b.drain(0.3)
        a.close()
        a = None
        quiet = [
            frame
            for frame in b.drain(COALESCE_SECONDS + 2.0)
            if event_envelope(frame)
            and event_envelope(frame).get("eventType") in ("USER_ONLINE", "USER_OFFLINE")
        ]
        check(quiet == [], f"hidden subject still emitted {len(quiet)} presence frames")
        a = StompConnection(ws_url, token_a, "A")
        a.subscribe(f"/topic/conversations.{conversation_id}")
        a.subscribe("/user/queue/errors")
        quiet_online = [
            frame
            for frame in b.drain(2.0)
            if event_envelope(frame)
            and event_envelope(frame).get("eventType") in ("USER_ONLINE", "USER_OFFLINE")
        ]
        check(quiet_online == [], "hidden subject emitted USER_ONLINE")
        ok("showPresence=false suppresses presence", "no online/offline frames")

        inbox = client.get("/api/v1/conversations", token=token_b)
        entry = next(e for e in inbox.json() if e["id"] == conversation_id)
        check(
            entry.get("peerPresence") is None,
            f"hidden peer still exposes peerPresence={entry.get('peerPresence')}",
        )
        ok("inbox hides peerPresence while hidden", "peerPresence absent")

        restored = client.patch(
            "/api/v1/users/me/settings", token=token_a, body={"showPresence": True}
        )
        check(restored.status == 200, f"restore presence failed: {restored}")
        b.drain(0.2)
        a.close()
        a = None
        b.wait_for(
            lambda f: presence_event(f, "USER_OFFLINE", me_a), 10, "USER_OFFLINE after restore"
        )
        a = StompConnection(ws_url, token_a, "A")
        a.subscribe(f"/topic/conversations.{conversation_id}")
        a.subscribe("/user/queue/errors")
        b.wait_for(
            lambda f: presence_event(f, "USER_ONLINE", me_a), 8, "USER_ONLINE after restore"
        )
        ok("privacy restored", "offline/online events resume")

        # ----------------------------------------------- rate limiting
        burst_key = str(uuid.uuid4())
        burst_text = f"e2e-stomp-{stamp}-ratelimit"
        a.send_message(conversation_id, burst_key, burst_text)  # ensure a fresh window
        for _ in range(44):
            a.send_message(conversation_id, burst_key, burst_text)
        error_frame = a.wait_for(
            lambda f: f[0] == "MESSAGE"
            and json.loads(f[2]).get("code") == "RATE_LIMITED",
            15,
            "RATE_LIMITED on /user/queue/errors",
        )
        error_payload = json.loads(error_frame[2])
        check(
            error_payload.get("clientMessageId") == burst_key,
            "RATE_LIMITED lost the clientMessageId",
        )
        rows = [
            item
            for item in history(client, token_b, conversation_id, limit=50)["items"]
            if item.get("clientMessageId") == burst_key
        ]
        check(len(rows) == 1, f"rate-limited burst stored {len(rows)} rows (expected 1)")
        created.append(rows[0]["id"])
        ok(
            "STOMP SEND rate limit",
            f"45 frames -> RATE_LIMITED, {len(rows)} stored row",
        )
    finally:
        for connection in (a, b):
            if connection is not None:
                try:
                    connection.close()
                except Exception:  # noqa: BLE001 - closing must never mask a failure
                    pass
        removed = 0
        for message_id in created:
            response = client.delete(
                f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
                token=token_a,
            )
            if response.status == 204:
                removed += 1
        # Restore the seed default even if the run failed mid-way.
        client.patch("/api/v1/users/me/settings", token=token_a, body={"showPresence": True})
        print(f"[cleanup] {removed}/{len(created)} STOMP fixture messages soft-deleted")

    print(f"\nNexaChat STOMP E2E: {passed} checks passed in {time.time() - started:.1f}s")
    return 0


if __name__ == "__main__":
    main_guard(main)
