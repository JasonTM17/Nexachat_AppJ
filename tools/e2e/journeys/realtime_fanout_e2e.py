#!/usr/bin/env python3
"""Live E2E journey: multi-client realtime fan-out over STOMP + REST (GH#321).

Three dedicated fixture accounts (``+849000009xx``) share one GROUP. Each
client holds a raw WebSocket STOMP session subscribed to BOTH broadcast
surfaces — ``/topic/conversations.{id}`` as sub ``s0`` and
``/user/queue/inbox`` as sub ``s1`` — with the negotiated heart-beat
(``10000,10000``) kept alive by a bare ``\\n`` every 5 s. After an idle soak
past the 30 s idle-kill line, nine interleaved REST sends (3 per user) must
fan out as 27 unique ``MESSAGE_CREATED`` deliveries on each surface — all
clients x all messages, sender echo included — with the subscription header
echoing ``s0``/``s1`` and the envelope payload matching the REST row exactly.
A resent ``clientMessageId`` re-broadcasts the stored row without adding a
history row.

Fixture accounts register through ``e2e_common.ensure_user``, so the first
run lands them in the e2e account manifest for the GH#322 cleanup sweep.

Usage:
    python tools/e2e/journeys/realtime_fanout_e2e.py [--base-url URL] [--soak 40]

Needs ``websocket-client``; no other third-party package. Tokens are never
printed.
"""

from __future__ import annotations

import argparse
import json
import sys
import threading
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from e2e_common import (  # noqa: E402
    Client,
    E2EFailure,
    check,
    ensure_user,
    history,
    main_guard,
)

try:  # websocket-client is a documented prerequisite for the STOMP journeys
    import websocket
except ImportError:  # the offline unit tests still import this module
    websocket = None

DEFAULT_BASE_URL = "http://127.0.0.1:8080"

FANOUT_PHONES = ("+84900000901", "+84900000902", "+84900000903")
FANOUT_NAMES = ("Realtime Fanout A", "Realtime Fanout B", "Realtime Fanout C")
FANOUT_LABELS = ("A", "B", "C")

TOPIC_SUB = "s0"
INBOX_SUB = "s1"
SUBSCRIPTION_IDS = (TOPIC_SUB, INBOX_SUB)

# Same wire values the Android client advertises; the server dead-after is
# ~3 missed beats, so beat at half the negotiated interval.
HEARTBEAT_ADVERTISE = "10000,10000"
BEAT_SECONDS = 5.0
SOAK_SECONDS = 40.0
DRAIN_SECONDS = 10.0
MESSAGES_PER_USER = 3


# --------------------------------------------------------------- pure helpers


def parse_frames(buffer: str) -> tuple[list, str]:
    """Split a recv buffer into (command, headers, body) frames + remainder.

    Bare ``\\n`` heart-beats carry no NUL terminator and stay in the
    remainder; an empty raw segment before a terminator is a beat too.
    """
    frames = []
    while "\x00" in buffer:
        raw, buffer = buffer.split("\x00", 1)
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
        frames.append((lines[0], headers, body))
    return frames, buffer


def message_created_envelope(frame):
    """The parsed envelope for a MESSAGE MESSAGE_CREATED frame, else None."""
    command, _headers, body = frame
    if command != "MESSAGE":
        return None
    try:
        envelope = json.loads(body)
    except ValueError:
        return None
    if envelope.get("eventType") != "MESSAGE_CREATED":
        return None
    return envelope


def unique_deliveries(deliveries, subscription: str | None = None) -> set:
    """Distinct (client_idx, message_id) deliveries, optionally per surface."""
    return {
        (item["client"], item["message_id"])
        for item in deliveries
        if subscription is None or item["subscription"] == subscription
    }


def missing_deliveries(deliveries, client_count: int, message_ids,
                       subscription: str) -> list:
    """(client_idx, message_id) pairs absent from the fan-out matrix."""
    seen = unique_deliveries(deliveries, subscription)
    return [
        (client, message_id)
        for client in range(client_count)
        for message_id in message_ids
        if (client, message_id) not in seen
    ]


def first_arrival_latencies(deliveries, sent_at_by_id) -> list:
    """First-arrival latency (s) per (client, message_id) delivery."""
    first: dict = {}
    for item in deliveries:
        key = (item["client"], item["message_id"])
        if key not in first or item["arrived"] < first[key]["arrived"]:
            first[key] = item
    return [
        item["arrived"] - sent_at_by_id[item["message_id"]]
        for item in first.values()
        if item["message_id"] in sent_at_by_id
    ]


def percentile(values, p: float):
    """Nearest-rank percentile: the smallest value whose cumulative rank is
    at least p x N (the standard p50/p95 sampling convention for latencies)."""
    ordered = sorted(values)
    if not ordered:
        return None
    rank = int(-(-p * len(ordered) // 1))  # ceil(p * N)
    rank = max(1, rank)
    return ordered[min(rank, len(ordered)) - 1]


# ------------------------------------------------------------------- transport


class FanoutClient:
    """One raw-WS STOMP session: CONNECT, s0 topic + s1 inbox, 5 s beat."""

    def __init__(self, ws_url: str, token: str, label: str, index: int):
        self.label = label
        self.index = index
        check(
            websocket is not None,
            "websocket-client is required (pip install websocket-client)",
        )
        self.ws = websocket.create_connection(
            ws_url, timeout=15, suppress_origin=True, subprotocols=["v12.stomp"])
        self.ws.settimeout(15)
        self.user_name = None
        self.deliveries: list[dict] = []
        self.server_beats = 0
        self.last_recv = time.monotonic()
        self.error: str | None = None
        self._buffer = ""
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._send_frame(
            "CONNECT",
            {
                "accept-version": "1.2",
                "heart-beat": HEARTBEAT_ADVERTISE,
                # Server quirk (websocket-protocol.md): no space after ':'.
                "Authorization": "Bearer " + token,
            },
            "",
        )
        command, headers, body = self._wait_for(
            lambda frame: frame[0] in ("CONNECTED", "ERROR"), 15, "CONNECTED")
        check(
            command == "CONNECTED",
            f"{self.label}: CONNECT refused: {str(body)[:160]}",
        )
        self.user_name = headers.get("user-name")

    def _send_frame(self, command: str, headers: dict, body: str) -> None:
        lines = [command] + [f"{key}:{value}" for key, value in headers.items()]
        self.ws.send("\n".join(lines) + "\n\n" + body + "\x00")

    def subscribe(self, subscription_id: str, destination: str) -> None:
        self._send_frame(
            "SUBSCRIBE", {"id": subscription_id, "destination": destination}, "")

    def _wait_for(self, predicate, timeout: float, description: str):
        deadline = time.monotonic() + timeout
        while True:
            frames, self._buffer = parse_frames(self._buffer)
            for frame in frames:
                if predicate(frame):
                    return frame
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise E2EFailure(
                    f"{self.label}: timed out after {timeout:.1f}s waiting for {description}")
            self.ws.settimeout(min(remaining, 5.0))
            try:
                chunk = self.ws.recv()
            except websocket.WebSocketTimeoutException:
                continue
            if not chunk:
                raise E2EFailure(
                    f"{self.label}: socket closed while waiting for {description}")
            self._buffer += chunk if isinstance(chunk, str) else chunk.decode("utf-8")

    def start(self) -> None:
        threading.Thread(target=self._beat_loop, daemon=True).start()
        threading.Thread(target=self._recv_loop, daemon=True).start()

    def _beat_loop(self) -> None:
        while not self._stop.wait(BEAT_SECONDS):
            try:
                self.ws.send("\n")  # STOMP heart-beat is a bare newline
            except Exception:  # noqa: BLE001 - the recv loop owns the socket error
                return

    def _recv_loop(self) -> None:
        while not self._stop.is_set():
            try:
                chunk = self.ws.recv()
            except websocket.WebSocketTimeoutException:
                # The server heart-beats at the negotiated ~10 s interval; a
                # recv timeout means a beat was missed. The dead-after is ~3
                # missed beats, so flag only sustained silence, never jitter.
                if time.monotonic() - self.last_recv > 30:
                    self.error = "no server heart-beat for 30s"
                    return
                continue
            except Exception as error:  # noqa: BLE001 - record, then exit
                if not self._stop.is_set():
                    self.error = f"{type(error).__name__}: {error}"
                return
            if not chunk:
                if not self._stop.is_set():
                    self.error = "socket closed by peer"
                return
            self._ingest(chunk if isinstance(chunk, str) else chunk.decode("utf-8"))

    def _ingest(self, chunk: str) -> None:
        self.last_recv = time.monotonic()
        if not chunk.strip("\n"):
            self.server_beats += 1  # server-side heart-beat chunk
        with self._lock:
            self._buffer += chunk
            frames, self._buffer = parse_frames(self._buffer)
        for frame in frames:
            headers = frame[1]
            envelope = message_created_envelope(frame)
            if envelope is None:
                continue
            payload = envelope.get("payload") or {}
            self.deliveries.append(
                {
                    "client": self.index,
                    "subscription": headers.get("subscription"),
                    "message_id": payload.get("id"),
                    "client_message_id": payload.get("clientMessageId"),
                    "sender_id": payload.get("senderId"),
                    "content": payload.get("content"),
                    "conversation_id": envelope.get("conversationId"),
                    "arrived": time.monotonic(),
                }
            )

    def close(self) -> None:
        self._stop.set()
        try:
            self._send_frame("DISCONNECT", {}, "")
        except Exception:  # noqa: BLE001 - closing must never mask a failure
            pass
        try:
            self.ws.close()
        except Exception:  # noqa: BLE001
            pass


# -------------------------------------------------------------------- journey


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="NexaChat multi-client realtime fan-out E2E")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--soak", type=float, default=SOAK_SECONDS, metavar="SECONDS",
                        help=f"idle heart-beat window before the sends (default {SOAK_SECONDS:.0f})")
    return parser


def main(argv=None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    args = build_parser().parse_args(argv)
    base_url = args.base_url.rstrip("/")
    client = Client(base_url, timeout=30.0)
    ws_url = base_url.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
    nonce = uuid.uuid4().hex[:8]
    print(f"== NexaChat realtime fan-out E2E ({base_url}, soak {args.soak:.0f}s) ==")

    state: dict = {"users": [], "conns": [], "sent": [], "created": []}
    results: list[tuple[str, str, str]] = []

    def phase(name: str, fn) -> bool:
        try:
            detail = fn() or ""
        except E2EFailure as failure:
            results.append((name, "FAIL", str(failure)))
            print(f"FAIL {name}: {failure}")
            return False
        except Exception as error:  # noqa: BLE001 - report, never abort mid-phase
            results.append((name, "FAIL", f"unexpected {type(error).__name__}: {error}"))
            print(f"FAIL {name}: unexpected {type(error).__name__}: {error}")
            return False
        results.append((name, "PASS", detail))
        print(f"PASS {name}" + (f": {detail}" if detail else ""))
        return True

    def provision() -> str:
        health = client.get("/actuator/health")
        check(
            health.status == 200 and (health.json() or {}).get("status") == "UP",
            f"backend health is not UP: {health}",
        )
        for phone, name in zip(FANOUT_PHONES, FANOUT_NAMES):
            session = ensure_user(client, phone, name)
            state["users"].append(
                {"token": session["accessToken"], "id": session["user"]["id"],
                 "phone": phone})
        member_ids = [user["id"] for user in state["users"][1:]]
        group = client.post(
            "/api/v1/conversations/group", token=state["users"][0]["token"],
            body={"title": f"fanout-e2e-{nonce}", "memberIds": member_ids})
        check(group.status == 201, f"create group expected 201, got {group}")
        state["conversation_id"] = group.json()["id"]
        return f"3 users, group {state['conversation_id']}"

    def connect() -> str:
        for index, (user, label) in enumerate(
                zip(state["users"], FANOUT_LABELS)):
            conn = FanoutClient(ws_url, user["token"], label, index)
            check(
                conn.user_name == user["id"],
                f"{label}: CONNECTED as {conn.user_name}, expected {user['id']}",
            )
            conn.subscribe(TOPIC_SUB,
                           f"/topic/conversations.{state['conversation_id']}")
            conn.subscribe(INBOX_SUB, "/user/queue/inbox")
            conn.start()
            state["conns"].append(conn)
        # SUBSCRIBE is processed asynchronously; let the broker register both
        # subs on every session before traffic starts.
        time.sleep(1.0)
        return f"3 clients CONNECTED, subs {TOPIC_SUB}/{INBOX_SUB}"

    def soak() -> str:
        time.sleep(args.soak)
        for conn in state["conns"]:
            check(
                conn.error is None,
                f"{conn.label}: session died inside the soak: {conn.error}",
            )
        beats = [conn.server_beats for conn in state["conns"]]
        return f"{args.soak:.0f}s idle, server beats per client {beats}"

    def send() -> str:
        for round_no in range(MESSAGES_PER_USER):
            for index, user in enumerate(state["users"]):
                client_message_id = str(uuid.uuid4())
                sent_at = time.monotonic()  # the broadcast can beat the response
                response = client.post(
                    f"/api/v1/conversations/{state['conversation_id']}/messages",
                    token=user["token"],
                    body={
                        "clientMessageId": client_message_id,
                        "type": "TEXT",
                        "content": f"fanout-{nonce}-{index}-{round_no}",
                        "attachmentIds": [],
                    })
                check(response.status in (200, 201),
                      f"send {index}-{round_no} got {response}")
                row = response.json()
                state["sent"].append(
                    {"client_message_id": client_message_id, "row": row,
                     "sender": index, "sent_at": sent_at})
                state["created"].append(row["id"])
        return f"{len(state['sent'])} interleaved REST sends"

    def drain() -> str:
        expected = len(state["sent"])
        deadline = time.monotonic() + DRAIN_SECONDS
        while time.monotonic() < deadline:
            deliveries = _all_deliveries(state)
            if all(
                len(unique_deliveries(deliveries, sub))
                >= expected * len(state["conns"])
                for sub in SUBSCRIPTION_IDS
            ):
                break
            time.sleep(0.4)
        for conn in state["conns"]:
            check(
                conn.error is None,
                f"{conn.label}: session died during send/drain: {conn.error}",
            )
        deliveries = _all_deliveries(state)
        state["deliveries"] = deliveries
        counts = {sub: len(unique_deliveries(deliveries, sub))
                  for sub in SUBSCRIPTION_IDS}
        return f"unique deliveries per surface {counts}"

    def assert_delivery() -> str:
        message_ids = {item["row"]["id"] for item in state["sent"]}
        missing = missing_deliveries(
            state["deliveries"], len(state["conns"]), message_ids, TOPIC_SUB)
        check(not missing, f"topic fan-out missing {len(missing)} deliveries: "
                           f"{missing[:4]}{'...' if len(missing) > 4 else ''}")
        sent_at = {item["row"]["id"]: item["sent_at"] for item in state["sent"]}
        latencies = first_arrival_latencies(
            [d for d in state["deliveries"] if d["subscription"] == TOPIC_SUB],
            sent_at)
        p50 = percentile(latencies, 0.50)
        p95 = percentile(latencies, 0.95)
        expected = len(message_ids) * len(state["conns"])
        return (f"{expected}/{expected} topic deliveries "
                f"(p50={p50 * 1000:.0f}ms p95={p95 * 1000:.0f}ms)"
                if p50 is not None else f"{expected}/{expected} topic deliveries")

    def assert_inbox() -> str:
        message_ids = {item["row"]["id"] for item in state["sent"]}
        missing = missing_deliveries(
            state["deliveries"], len(state["conns"]), message_ids, INBOX_SUB)
        check(not missing, f"inbox fan-out missing {len(missing)} deliveries: "
                           f"{missing[:4]}{'...' if len(missing) > 4 else ''}")
        expected = len(message_ids) * len(state["conns"])
        return f"{expected}/{expected} inbox pushes to /user/queue/inbox"

    def assert_envelope() -> str:
        rows = {item["row"]["id"]: item for item in state["sent"]}
        seen = set()
        for item in state["deliveries"]:
            check(
                item["subscription"] in SUBSCRIPTION_IDS,
                f"subscription header is {item['subscription']!r}, "
                f"expected one of {SUBSCRIPTION_IDS}",
            )
            check(
                item["conversation_id"] == state["conversation_id"],
                f"frame for another conversation {item['conversation_id']}",
            )
            check(
                item["client_message_id"] in {s["client_message_id"]
                                              for s in state["sent"]},
                f"unknown clientMessageId {item['client_message_id']}",
            )
            key = (item["client"], item["subscription"], item["message_id"])
            if key in seen:
                continue  # the same delivery asserted once per surface
            seen.add(key)
            sent = rows.get(item["message_id"])
            check(sent is not None,
                  f"frame message id {item['message_id']} is not a REST row")
            row = sent["row"]
            check(
                item["client_message_id"] == row.get("clientMessageId"),
                f"payload clientMessageId {item['client_message_id']} "
                f"!= REST {row.get('clientMessageId')}",
            )
            check(
                item["sender_id"] == row.get("senderId"),
                f"payload senderId {item['sender_id']} != REST {row.get('senderId')}",
            )
            check(
                item["content"] == row.get("content"),
                f"payload content {item['content']!r} != REST {row.get('content')!r}",
            )
        return (f"subscription headers {SUBSCRIPTION_IDS}, "
                f"payloads == REST rows on both surfaces")

    def dupe_replay() -> str:
        victim = state["sent"][0]
        sender = state["users"][victim["sender"]]
        # _all_deliveries concatenates per-client lists (A then B then C), so
        # a flat [before:] slice over the concatenated list is NOT "frames
        # that arrived after `before`" - it is the tail of the last client's
        # list. A client whose recv thread is a few frames behind makes that
        # tail contain stale pre-replay frames, and the count silently loses
        # the other clients (three live runs all failed at exactly 1/3).
        # Per-connection before/after slices plus a replay-time floor on the
        # frame arrival clocks are the honest witness.
        before_by_conn = [len(conn.deliveries) for conn in state["conns"]]
        replay_sent_at = time.monotonic()
        replay = client.post(
            f"/api/v1/conversations/{state['conversation_id']}/messages",
            token=sender["token"],
            body={
                "clientMessageId": victim["client_message_id"],
                "type": "TEXT",
                "content": victim["row"]["content"],
                "attachmentIds": [],
            })
        check(replay.status in (200, 201), f"replay send got {replay}")
        check(
            replay.json().get("id") == victim["row"]["id"],
            "replay returned a different message id",
        )
        # Let the re-broadcast land, then prove nothing new was stored or
        # deduplicated wrongly: history stays one row and the unique
        # delivery matrix does not grow.
        time.sleep(4.0)
        rows = [
            item for item in
            history(client, state["users"][0]["token"],
                    state["conversation_id"], limit=50)["items"]
            if item.get("clientMessageId") == victim["client_message_id"]
        ]
        check(len(rows) == 1,
              f"duplicate clientMessageId stored {len(rows)} rows")
        for sub in SUBSCRIPTION_IDS:
            for conn, before in zip(state["conns"], before_by_conn):
                replays = [
                    d for d in conn.deliveries[before:]
                    if d["subscription"] == sub
                    and d["message_id"] == victim["row"]["id"]
                    and d["arrived"] >= replay_sent_at
                ]
                check(
                    len(replays) == 1,
                    f"{sub}: {conn.label} saw {len(replays)} re-broadcast "
                    f"frames for the replayed id (expected exactly 1)",
                )
        # The re-broadcast frames carry the stored payload.id, so the unique
        # (client, message_id) matrix must not grow: dedupe holds. Compare
        # the frozen snapshot against the per-connection tails that satisfy
        # the replay-time floor, so late pre-replay frames cannot fake
        # growth.
        replay_frames = [
            d for conn, before in zip(state["conns"], before_by_conn)
            for d in conn.deliveries[before:]
            if d["arrived"] >= replay_sent_at
        ]
        unique_before = len(unique_deliveries(state["deliveries"]))
        unique_after = len(unique_deliveries(state["deliveries"] + replay_frames))
        check(
            unique_after == unique_before,
            f"unique delivery count grew from {unique_before} to {unique_after}",
        )
        state["deliveries"] = state["deliveries"] + replay_frames
        return ("1 history row; re-broadcast of the stored id to every "
                "client on both surfaces; dedupe by payload.id holds")

    try:
        established = (
            phase("fanout-provision", provision)
            and phase("fanout-connect", connect)
            and phase("fanout-heartbeat-soak", soak)
            and phase("fanout-send", send)
            and phase("fanout-drain", drain)
        )
        if established:
            phase("fanout-delivery", assert_delivery)
            phase("fanout-inbox", assert_inbox)
            phase("fanout-envelope", assert_envelope)
            phase("fanout-dupe-replay", dupe_replay)
    finally:
        for conn in state["conns"]:
            try:
                conn.close()
            except Exception:  # noqa: BLE001 - closing must never mask a failure
                pass
        removed = 0
        if state["users"]:
            for message_id in state["created"]:
                response = client.delete(
                    f"/api/v1/conversations/{state['conversation_id']}/messages/{message_id}",
                    token=state["users"][0]["token"],
                )
                if response.status == 204:
                    removed += 1
        if state["created"]:
            print(f"[cleanup] {removed}/{len(state['created'])} fan-out "
                  "fixture messages soft-deleted")

    failures = sum(1 for _name, status, _detail in results if status == "FAIL")
    passed = sum(1 for _name, status, _detail in results if status == "PASS")
    print(f"\n{passed}/{len(results)} fan-out checks passed")
    return 1 if failures or not passed else 0


def _all_deliveries(state: dict) -> list:
    deliveries = []
    for conn in state["conns"]:
        deliveries.extend(conn.deliveries)
    return deliveries


if __name__ == "__main__":
    main_guard(main)
