#!/usr/bin/env python3
"""Read-only STOMP observer used while driving the Android UI by hand.

Connects a second demo user over the same raw WebSocket + STOMP 1.2 wire the
app uses, subscribes to one conversation topic and to `/user/queue/presence`,
and prints every envelope with a timestamp. That is how "the other side sees
it in real time" is proven when only one emulator is available.

The connection also counts as an authenticated session, so the same script
drives the presence checks: start it to go online, stop it (Ctrl+C) to go
offline.

Examples:
    python tools/e2e/device/realtime_probe.py --as +84900000202 \
        --conversation 62e29e0e-a740-4e0f-a60e-41065ccbaedb --seconds 90
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time

import websocket

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from e2e_common import Client, login  # noqa: E402


class FrameReader:
    """Minimal STOMP reader: subscribe, then stream MESSAGE frames."""

    def __init__(self, ws_url: str, token: str):
        self.ws = websocket.create_connection(ws_url, timeout=10)
        self.buffer = ""
        self.subscriptions = 0
        self._frame(
            "CONNECT",
            {"accept-version": "1.2", "heart-beat": "0,0", "Authorization": "Bearer " + token},
        )
        self._wait_connected()

    def _frame(self, command: str, headers: dict, body: str = "") -> None:
        lines = [command] + [f"{key}:{value}" for key, value in headers.items()]
        self.ws.send("\n".join(lines) + "\n\n" + body + "\x00")

    def _read(self) -> None:
        while "\x00" in self.buffer:
            raw, self.buffer = self.buffer.split("\x00", 1)
            raw = raw.lstrip("\n")
            if raw.strip():
                yield raw

    def _wait_connected(self) -> None:
        deadline = time.time() + 10
        while time.time() < deadline:
            self.ws.settimeout(max(0.1, deadline - time.time()))
            try:
                chunk = self.ws.recv()
            except websocket.WebSocketTimeoutException:
                break
            self.buffer += chunk if isinstance(chunk, str) else chunk.decode("utf-8")
            for raw in self._read():
                if raw.startswith("CONNECTED"):
                    return
        raise SystemExit("STOMP CONNECT did not succeed")

    def subscribe(self, destination: str) -> None:
        self.subscriptions += 1
        self._frame("SUBSCRIBE", {"id": f"sub-{self.subscriptions}", "destination": destination})

    def events(self, seconds: float):
        deadline = time.time() + seconds
        while time.time() < deadline:
            self.ws.settimeout(max(0.1, min(1.0, deadline - time.time())))
            try:
                chunk = self.ws.recv()
            except websocket.WebSocketTimeoutException:
                continue
            except websocket.WebSocketConnectionClosedException:
                return
            self.buffer += chunk if isinstance(chunk, str) else chunk.decode("utf-8")
            for raw in self._read():
                head, _, body = raw.partition("\n\n")
                if not head.startswith("MESSAGE"):
                    continue
                try:
                    envelope = json.loads(body)
                except ValueError:
                    continue
                yield envelope

    def close(self) -> None:
        try:
            self._frame("DISCONNECT", {})
        except OSError:
            pass
        try:
            self.ws.close()
        except OSError:
            pass


def summarize(envelope: dict) -> str:
    event_type = envelope.get("eventType")
    payload = envelope.get("payload") or {}
    if event_type in {"MESSAGE_UPDATED", "MESSAGE_CREATED"}:
        detail = {key: payload.get(key) for key in ("id", "seq", "content", "editedAt", "deletedAt")}
    elif event_type == "MESSAGE_DELETED":
        detail = {key: payload.get(key) for key in ("messageId", "deletedAt")}
    elif event_type in {"USER_ONLINE", "USER_OFFLINE"}:
        detail = {key: payload.get(key) for key in ("userId", "online", "lastSeenAt")}
    else:
        detail = payload if len(json.dumps(payload)) < 200 else {"keys": sorted(payload)}
    return f"{event_type} {json.dumps(detail, ensure_ascii=False)}"


def main(argv=None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--as", dest="phone", required=True)
    parser.add_argument("--conversation", action="append", default=[])
    parser.add_argument("--presence", action="store_true", help="also watch /user/queue/presence")
    parser.add_argument("--seconds", type=float, default=60.0)
    parser.add_argument("--base-url", default="http://localhost:8080")
    args = parser.parse_args(argv)

    client = Client(args.base_url)
    session = login(client, args.phone)
    ws_url = args.base_url.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
    reader = FrameReader(ws_url, session["accessToken"])
    for conversation_id in args.conversation:
        reader.subscribe(f"/topic/conversations.{conversation_id}")
        print(f"subscribed topic {conversation_id}", flush=True)
    if args.presence:
        reader.subscribe("/user/queue/presence")
        print("subscribed presence queue", flush=True)
    print(f"online as {args.phone} for {args.seconds:.0f}s", flush=True)
    started = time.strftime("%H:%M:%S")
    try:
        for envelope in reader.events(args.seconds):
            print(f"{time.strftime('%H:%M:%S')} {summarize(envelope)}", flush=True)
    finally:
        reader.close()
        print(f"disconnected ({started} -> {time.strftime('%H:%M:%S')})", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
