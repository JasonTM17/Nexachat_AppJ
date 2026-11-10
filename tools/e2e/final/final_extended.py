#!/usr/bin/env python3
"""Extended live scenarios for the round-4 final E2E pass.

Runs against the same disposable stack as ``final_live.py``. Tokens, passwords
and phone numbers are never printed. The database container must already be
one ``final_live.py`` accepts (name prefix ``nexachat_e2e_grok2``).

Retention is not repeated here: ``final_live.py`` drives it through the
retention configuration. This script always records that scenario as NOT_RUN,
so the report points at the other script's real result.

Required environment
--------------------
The stack from ``final_live.py``: app at ``--base-url``, its Postgres container
at ``--db-container``, and the app container name at ``--app-container`` (used
for ``MockFcmGateway`` log counts and the restart scenario). Docker must be on
``PATH``, and this script needs the third-party ``websocket-client`` package
(``pip install websocket-client``).

Set ``FCM_ENABLED=false`` literally in the app container when the run expects
``MockFcmGateway``: a host ``FCM_ENABLED=true`` overrides the compose env file
and enables the real FCM gateway, so the push counters would stay at zero.

The retention phase lives in ``final_live.py`` and needs
``NEXACHAT_RETENTION_INTERVAL_MS`` of at least ``60000``,
``MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,metrics``, and an admin
phone in ``NEXACHAT_ADMIN_PHONES``.

Exit status is 1 when any scenario fails and 0 when all pass or are not run.
A shell pipeline that ends in ``tee`` hides that status: use ``set -o
pipefail`` or inspect ``${PIPESTATUS[0]}``.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
E2E_DIR = HERE.parent
NEW_FEATURES = E2E_DIR / "new_features"
for path in (E2E_DIR, NEW_FEATURES, HERE):
    if str(path) not in sys.path:
        sys.path.insert(0, str(path))

import websocket  # noqa: E402

from e2e_common import Client, E2EFailure  # noqa: E402
from features_helpers import iso_utc, mask_id, utc_after  # noqa: E402
from features_live import create_group, create_user  # noqa: E402
from final_live import Database, quote_uuid  # noqa: E402

SENDS_PER_WINDOW = 24
WINDOW_SECONDS = 10.5
GAP_COUNT = 500
FANOUT_CLIENTS = 20
FANOUT_MESSAGES = 8


class Results:
    def __init__(self) -> None:
        self.rows: list[tuple[str, str, str]] = []

    def add(self, name: str, status: str, detail: str) -> None:
        self.rows.append((name, status, detail))
        print(f"[{status}] {name}: {detail}", flush=True)

    def finish(self) -> int:
        failed = [row for row in self.rows if row[1] == "FAIL"]
        passed = [row for row in self.rows if row[1] == "PASS"]
        other = len(self.rows) - len(failed) - len(passed)
        print(
            f"{len(passed)} passed, {len(failed)} failed, {other} not run",
            flush=True,
        )
        return 1 if failed else 0


def push_counts(container: str) -> dict[str, int]:
    proc = subprocess.run(
        ["docker", "logs", container],
        capture_output=True,
        text=True,
        errors="replace",
        check=False,
    )
    text = (proc.stdout or "") + (proc.stderr or "")
    return {
        "NEW_MESSAGE": text.count("MockFcmGateway: Recorded data push of type NEW_MESSAGE"),
        "REMINDER": text.count("MockFcmGateway: Recorded data push of type REMINDER"),
    }


def post_message(
    client: Client,
    token: str,
    conversation_id: str,
    content: str,
    *,
    client_message_id: str | None = None,
    reply_to: str | None = None,
    mentions: list[dict[str, Any]] | None = None,
) -> Any:
    body: dict[str, Any] = {
        "clientMessageId": client_message_id or str(uuid.uuid4()),
        "type": "TEXT",
        "content": content,
        "attachmentIds": [],
    }
    if reply_to is not None:
        body["replyToId"] = reply_to
    if mentions is not None:
        body["mentions"] = mentions
    delay = 1.0
    last = None
    for _ in range(8):
        last = client.post(
            f"/api/v1/conversations/{conversation_id}/messages",
            token=token,
            body=body,
        )
        if last.status != 429:
            return last
        time.sleep(delay)
        delay = min(delay * 2, 12)
    return last


def send_paced(
    client: Client,
    token: str,
    conversation_id: str,
    contents: list[str],
) -> list[dict[str, Any]]:
    sent: list[dict[str, Any]] = []
    window_start = time.monotonic()
    in_window = 0
    for content in contents:
        if in_window >= SENDS_PER_WINDOW:
            remain = WINDOW_SECONDS - (time.monotonic() - window_start)
            if remain > 0:
                time.sleep(remain)
            window_start = time.monotonic()
            in_window = 0
        response = post_message(client, token, conversation_id, content)
        if response is None or response.status not in (200, 201):
            status = None if response is None else response.status
            raise E2EFailure(f"paced send HTTP {status} after {len(sent)} messages")
        body = response.json() or {}
        sent.append({"id": body.get("id"), "seq": body.get("seq"), "content": content})
        in_window += 1
    return sent


def seed_ask_cache(db: Database, conversation_id: str, message_id: str) -> None:
    db.scalar(
        "INSERT INTO rag_ask_cache "
        "(conversation_id, model, question_hash, question_vector, language, "
        "payload_json, cited_message_ids, cited_content_hash, expires_at) "
        f"VALUES ({quote_uuid(conversation_id)}, 'lexical', "
        f"'{('ab' * 32)}', decode('{('00' * 64)}', 'hex'), 'EN', "
        "'{\"answer\":\"seeded\",\"citations\":[],\"mode\":\"EXTRACTIVE\"}'::jsonb, "
        f"ARRAY[{quote_uuid(message_id)}]::uuid[], '{('cd' * 32)}', "
        "now() + interval '1 hour')"
    )


def cache_count(db: Database, conversation_id: str) -> int:
    return db.count(
        "SELECT count(*) FROM rag_ask_cache "
        f"WHERE conversation_id = {quote_uuid(conversation_id)}"
    )


def message_count_for_client_id(db: Database, client_message_id: str) -> int:
    return db.count(
        "SELECT count(*) FROM messages "
        f"WHERE client_message_id = {quote_uuid(client_message_id)}"
    )


def inbox_entries(client: Client, token: str, **params: str) -> list[dict[str, Any]]:
    response = client.get("/api/v1/conversations", token=token, params=params or None)
    if response.status != 200 or not isinstance(response.json(), list):
        raise E2EFailure(f"inbox HTTP {response.status}")
    return response.json()


def entry_for(entries: list[dict[str, Any]], conversation_id: str) -> dict[str, Any] | None:
    for entry in entries:
        if entry.get("id") == conversation_id:
            return entry
    return None


class Stomp:
    def __init__(self, ws_url: str, token: str) -> None:
        self.ws = websocket.create_connection(ws_url, timeout=10)
        self.ws.settimeout(10)
        self._buffer = ""
        self._frames: list[tuple[str, dict[str, str], str]] = []
        self._sub = 0
        self._send("CONNECT", {
            "accept-version": "1.2",
            "heart-beat": "0,0",
            "Authorization": "Bearer " + token,
        }, "")
        self.wait(lambda frame: frame[0] == "CONNECTED", 10, "CONNECTED")

    def _send(self, command: str, headers: dict[str, str], body: str) -> None:
        lines = [command] + [f"{key}:{value}" for key, value in headers.items()]
        self.ws.send("\n".join(lines) + "\n\n" + body + "\x00")

    def subscribe(self, destination: str, receipt: str | None = None) -> str:
        self._sub += 1
        subscription_id = f"sub-{self._sub}"
        headers = {"id": subscription_id, "destination": destination}
        if receipt:
            headers["receipt"] = receipt
        self._send("SUBSCRIBE", headers, "")
        return subscription_id

    def send(self, destination: str, body: str, receipt: str | None = None) -> None:
        headers = {
            "destination": destination,
            "content-type": "application/json",
            "content-length": str(len(body.encode("utf-8"))),
        }
        if receipt:
            headers["receipt"] = receipt
        self._send("SEND", headers, body)

    def _parse(self) -> None:
        while "\x00" in self._buffer:
            raw, self._buffer = self._buffer.split("\x00", 1)
            raw = raw.lstrip("\n")
            if not raw.strip():
                continue
            head, _, body = raw.partition("\n\n")
            lines = head.split("\n")
            headers: dict[str, str] = {}
            for line in lines[1:]:
                if ":" in line:
                    key, value = line.split(":", 1)
                    headers[key] = value
            self._frames.append((lines[0], headers, body))

    def wait(self, predicate, timeout: float, description: str):
        deadline = time.monotonic() + timeout
        while True:
            for index, frame in enumerate(self._frames):
                if predicate(frame):
                    return self._frames.pop(index)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise E2EFailure(f"timed out waiting for {description}")
            self.ws.settimeout(remaining)
            try:
                chunk = self.ws.recv()
            except websocket.WebSocketTimeoutException as exc:
                raise E2EFailure(f"timed out waiting for {description}") from exc
            if not chunk:
                raise E2EFailure(f"socket closed waiting for {description}")
            self._buffer += chunk if isinstance(chunk, str) else chunk.decode("utf-8")
            self._parse()

    def drain(self, duration: float) -> list[tuple[str, dict[str, str], str]]:
        collected = list(self._frames)
        self._frames.clear()
        deadline = time.monotonic() + duration
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            self.ws.settimeout(remaining)
            try:
                chunk = self.ws.recv()
            except websocket.WebSocketTimeoutException:
                break
            if not chunk:
                break
            self._buffer += chunk if isinstance(chunk, str) else chunk.decode("utf-8")
            self._parse()
            collected.extend(self._frames)
            self._frames.clear()
        return collected

    def close(self) -> None:
        try:
            self._send("DISCONNECT", {}, "")
        except OSError:
            pass
        try:
            self.ws.close()
        except OSError:
            pass


def scenario_idempotent(results: Results, client: Client, db: Database, user: dict, peer: dict) -> None:
    created = client.post(
        "/api/v1/conversations/direct",
        token=user["token"],
        body={"phone": peer["phone"]},
    )
    if created.status not in (200, 201):
        results.add("idempotent_send", "FAIL", f"direct HTTP {created.status}")
        return
    conversation_id = created.json()["id"]
    client_message_id = str(uuid.uuid4())
    first = post_message(
        client, user["token"], conversation_id, "idempotent-once",
        client_message_id=client_message_id,
    )
    second = post_message(
        client, user["token"], conversation_id, "idempotent-once",
        client_message_id=client_message_id,
    )
    rows = message_count_for_client_id(db, client_message_id)
    same = (first.json() or {}).get("id") == (second.json() or {}).get("id")
    ok = (
        first.status == 201
        and second.status == 200
        and same
        and rows == 1
    )
    results.add(
        "idempotent_send",
        "PASS" if ok else "FAIL",
        f"first={first.status} second={second.status} same_id={same} rows={rows}",
    )


def scenario_recall_derived(
    results: Results, client: Client, db: Database, user: dict, peer: dict,
) -> None:
    created = client.post(
        "/api/v1/conversations/direct",
        token=user["token"],
        body={"phone": peer["phone"]},
    )
    if created.status not in (200, 201):
        results.add("recall_purges_derived", "FAIL", f"direct HTTP {created.status}")
        return
    conversation_id = created.json()["id"]
    token_text = "violet-heron-recall-42"
    link = "https://example.com/e2e-recall-violet-heron"
    sent = post_message(
        client, user["token"], conversation_id, f"see {link} token {token_text}",
    )
    if sent.status != 201:
        results.add("recall_purges_derived", "FAIL", f"send HTTP {sent.status}")
        return
    message_id = sent.json()["id"]
    reacted = client.put(
        f"/api/v1/messages/{message_id}/reactions/%F0%9F%91%8D",
        token=peer["token"],
    )
    reply = post_message(
        client, peer["token"], conversation_id, "quoting the recalled note",
        reply_to=message_id,
    )
    search_before = client.get(
        f"/api/v1/conversations/{conversation_id}/messages/search",
        token=user["token"],
        params={"q": token_text},
    )
    archive_before = client.get(
        f"/api/v1/conversations/{conversation_id}/archive",
        token=user["token"],
        params={"type": "LINK"},
    )
    seed_ask_cache(db, conversation_id, message_id)
    before_cache = cache_count(db, conversation_id)
    deleted = client.delete(
        f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
        token=user["token"],
    )
    search_after = client.get(
        f"/api/v1/conversations/{conversation_id}/messages/search",
        token=user["token"],
        params={"q": token_text},
    )
    archive_after = client.get(
        f"/api/v1/conversations/{conversation_id}/archive",
        token=user["token"],
        params={"type": "LINK"},
    )
    history = client.get(
        f"/api/v1/conversations/{conversation_id}/messages",
        token=user["token"],
        params={"limit": "20"},
    )
    items = (history.json() or {}).get("items") or []
    recalled = next((item for item in items if item.get("id") == message_id), None)
    quoted = next((item for item in items if item.get("id") == (reply.json() or {}).get("id")), None)
    reply_to = (quoted or {}).get("replyTo") or {}
    search_hits_before = len((search_before.json() or {}).get("items") or [])
    search_hits_after = len((search_after.json() or {}).get("items") or [])
    links_before = [
        ((item.get("link") or {}).get("url"))
        for item in ((archive_before.json() or {}).get("items") or [])
    ]
    links_after = [
        ((item.get("link") or {}).get("url"))
        for item in ((archive_after.json() or {}).get("items") or [])
    ]
    after_cache = cache_count(db, conversation_id)
    ok = (
        reacted.status == 204
        and reply.status == 201
        and search_before.status == 200
        and search_hits_before >= 1
        and link in links_before
        and before_cache >= 1
        and deleted.status == 204
        and search_hits_after == 0
        and link not in links_after
        and after_cache == 0
        and recalled is not None
        and recalled.get("content") is None
        and recalled.get("reactions") == []
        and reply_to.get("deleted") is True
        and reply_to.get("content") is None
    )
    results.add(
        "recall_purges_derived",
        "PASS" if ok else "FAIL",
        "react="
        f"{reacted.status} search={search_hits_before}->{search_hits_after} "
        f"link_before={link in links_before} link_after={link in links_after} "
        f"cache={before_cache}->{after_cache} delete={deleted.status} "
        f"content_null={recalled is not None and recalled.get('content') is None} "
        f"reactions={None if recalled is None else recalled.get('reactions')} "
        f"reply_deleted={reply_to.get('deleted')} reply_content_null={reply_to.get('content') is None}",
    )


def scenario_edit_cache(
    results: Results, client: Client, db: Database, user: dict, peer: dict,
) -> None:
    created = client.post(
        "/api/v1/conversations/direct",
        token=user["token"],
        body={"phone": peer["phone"]},
    )
    if created.status not in (200, 201):
        results.add("edit_invalidates_ask_cache", "FAIL", f"direct HTTP {created.status}")
        return
    conversation_id = created.json()["id"]
    sent = post_message(client, user["token"], conversation_id, "cache token amber-lynx-7")
    if sent.status != 201:
        results.add("edit_invalidates_ask_cache", "FAIL", f"send HTTP {sent.status}")
        return
    message_id = sent.json()["id"]
    seed_ask_cache(db, conversation_id, message_id)
    before = cache_count(db, conversation_id)
    edited = client.patch(
        f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
        token=user["token"],
        body={"content": "cache token amber-lynx-7 edited"},
    )
    after = cache_count(db, conversation_id)
    ok = before >= 1 and edited.status == 200 and after == 0
    results.add(
        "edit_invalidates_ask_cache",
        "PASS" if ok else "FAIL",
        f"before={before} edit={edited.status} after={after}",
    )


def scenario_invite_rotation(results: Results, client: Client, admin: dict, peer: dict, joiner: dict) -> None:
    group = create_group(client, admin["token"], "E2E rotate", [peer["id"]])
    first = client.post(
        f"/api/v1/conversations/{group['id']}/invite-link",
        token=admin["token"],
        body={"requiresApproval": False, "maxUses": 5},
    )
    if first.status != 201 or not (first.json() or {}).get("token"):
        results.add("invite_rotation", "FAIL", f"create HTTP {first.status}")
        return
    old_token = first.json()["token"]
    second = client.post(
        f"/api/v1/conversations/{group['id']}/invite-link",
        token=admin["token"],
        body={"requiresApproval": False, "maxUses": 5},
    )
    if second.status != 201 or not (second.json() or {}).get("token"):
        results.add("invite_rotation", "FAIL", f"rotate HTTP {second.status}")
        return
    new_token = second.json()["token"]

    def preview_or_join(method: str, token_value: str, user_token: str):
        delay = 2.0
        response = None
        for _ in range(4):
            if method == "GET":
                response = client.get(
                    f"/api/v1/invite-links/{token_value}", token=user_token,
                )
            else:
                response = client.post(
                    f"/api/v1/invite-links/{token_value}/join", token=user_token,
                )
            if response.status != 429:
                return response
            time.sleep(delay)
            delay = min(delay * 2, 20)
        return response

    old_preview = preview_or_join("GET", old_token, joiner["token"])
    new_preview = preview_or_join("GET", new_token, joiner["token"])
    old_join = preview_or_join("POST", old_token, joiner["token"])
    new_join = preview_or_join("POST", new_token, joiner["token"])
    rotated = old_token != new_token
    ok = (
        rotated
        and old_preview.status == 404
        and new_preview.status == 200
        and old_join.status == 404
        and new_join.status == 200
        and (new_join.json() or {}).get("status") == "JOINED"
    )
    results.add(
        "invite_rotation",
        "PASS" if ok else "FAIL",
        f"rotated={rotated} old_preview={old_preview.status} new_preview={new_preview.status} "
        f"old_join={old_join.status} new_join={new_join.status} "
        f"join_status={(new_join.json() or {}).get('status')}",
    )


def scenario_stomp_ack(results: Results, client: Client, ws_url: str, admin: dict, peer: dict, outsider: dict) -> None:
    group = create_group(client, admin["token"], "E2E ack deny", [peer["id"]])
    sent = post_message(client, admin["token"], group["id"], "ack target")
    if sent.status != 201:
        results.add("stomp_ack_non_member", "FAIL", f"send HTTP {sent.status}")
        return
    seq = int(sent.json()["seq"])
    before = 0
    outsider_socket = None
    member_socket = None
    try:
        member_socket = Stomp(ws_url, peer["token"])
        member_socket.subscribe(f"/topic/conversations.{group['id']}")
        outsider_socket = Stomp(ws_url, outsider["token"])
        outsider_socket.subscribe("/user/queue/errors")
        body = json.dumps({"upToSeq": seq})
        outsider_socket.send(
            f"/app/conversations/{group['id']}/delivered",
            body,
            receipt="ack-non-member",
        )
        alias = json.dumps({"conversationId": group["id"], "upToSeq": seq})
        outsider_socket.send("/app/chat.delivery.ack", alias, receipt="ack-alias-non-member")
        frames = outsider_socket.drain(2.0)
        member_frames = member_socket.drain(1.0)
        receipts = [frame for frame in frames if frame[0] == "RECEIPT"]
        delivered_events = []
        for frame in member_frames:
            if frame[0] != "MESSAGE":
                continue
            try:
                payload = json.loads(frame[2] or "{}")
            except json.JSONDecodeError:
                continue
            if payload.get("eventType") == "MESSAGE_DELIVERED":
                delivered_events.append(payload)
        after_rows = client.get(
            f"/api/v1/conversations/{group['id']}/messages",
            token=peer["token"],
            params={"limit": "5"},
        )
        ok = not receipts and not delivered_events and after_rows.status == 200
        results.add(
            "stomp_ack_non_member",
            "PASS" if ok else "FAIL",
            f"receipts={len(receipts)} delivered_events={len(delivered_events)} "
            f"frames={len(frames)} history={after_rows.status} before_seq_hint={before}",
        )
    except E2EFailure as exc:
        results.add("stomp_ack_non_member", "FAIL", str(exc)[:240])
    finally:
        if outsider_socket is not None:
            outsider_socket.close()
        if member_socket is not None:
            member_socket.close()


def scenario_removed_member(results: Results, client: Client, db: Database, ws_url: str, admin: dict, peer: dict) -> None:
    group = create_group(client, admin["token"], "E2E remove sub", [peer["id"]])
    socket = None
    try:
        socket = Stomp(ws_url, peer["token"])
        socket.subscribe(f"/topic/conversations.{group['id']}")
        removed = client.delete(
            f"/api/v1/conversations/{group['id']}/members/{peer['id']}",
            token=admin["token"],
        )
        still = db.count(
            "SELECT count(*) FROM conversation_members "
            f"WHERE conversation_id = {quote_uuid(group['id'])} "
            f"AND user_id = {quote_uuid(peer['id'])}"
        )
        sent = post_message(client, admin["token"], group["id"], "after removal")
        try:
            frames = socket.drain(2.5)
        except (OSError, websocket.WebSocketException):
            frames = []
        seen = False
        message_id = (sent.json() or {}).get("id")
        for frame in frames:
            if message_id and message_id in (frame[2] or ""):
                seen = True
        resub_receipts = []
        try:
            socket.subscribe(f"/topic/conversations.{group['id']}", receipt="resub-removed")
            resub_receipts = [
                frame for frame in socket.drain(1.5) if frame[0] == "RECEIPT"
            ]
        except (OSError, websocket.WebSocketException):
            resub_receipts = []
        ok = (
            removed.status in (200, 204)
            and still == 0
            and sent.status == 201
            and not seen
            and not resub_receipts
        )
        results.add(
            "removed_member_loses_subscribe",
            "PASS" if ok else "FAIL",
            f"remove={removed.status} members={still} send={sent.status} "
            f"saw_message={seen} resub_receipts={len(resub_receipts)} frames={len(frames)}",
        )
    except E2EFailure as exc:
        results.add("removed_member_loses_subscribe", "FAIL", str(exc)[:240])
    finally:
        if socket is not None:
            socket.close()


def scenario_isolation(results: Results, client: Client, admin: dict, peer: dict) -> None:
    group = create_group(client, admin["token"], "E2E isolation", [peer["id"]])
    conversation_id = group["id"]
    label = client.post(
        "/api/v1/labels",
        token=admin["token"],
        body={"name": "E2E private", "colorId": "INDIGO"},
    )
    if label.status not in (200, 201):
        results.add("preference_isolation", "FAIL", f"label HTTP {label.status}")
        return
    label_id = label.json()["id"]
    assigned = client.put(
        f"/api/v1/conversations/{conversation_id}/labels/{label_id}",
        token=admin["token"],
    )
    pinned = client.put(f"/api/v1/conversations/{conversation_id}/pin", token=admin["token"])
    muted = client.put(
        f"/api/v1/conversations/{conversation_id}/mute",
        token=admin["token"],
        body={"forever": True},
    )
    archived = client.put(
        f"/api/v1/conversations/{conversation_id}/archive",
        token=admin["token"],
    )
    owner_labels = client.get("/api/v1/labels", token=admin["token"])
    peer_labels = client.get("/api/v1/labels", token=peer["token"])
    owner_inbox = inbox_entries(client, admin["token"], archived="true")
    peer_inbox = inbox_entries(client, peer["token"])
    peer_archived = inbox_entries(client, peer["token"], archived="true")
    peer_labeled = inbox_entries(client, peer["token"], labelId=label_id)
    owner = entry_for(owner_inbox, conversation_id)
    peer_row = entry_for(peer_inbox, conversation_id)
    peer_arch_row = entry_for(peer_archived, conversation_id)
    owner_ids = {item.get("id") for item in (owner_labels.json() or [])}
    peer_ids = {item.get("id") for item in (peer_labels.json() or [])}
    ok = (
        assigned.status == 204
        and pinned.status == 204
        and muted.status == 204
        and archived.status == 204
        and label_id in owner_ids
        and label_id not in peer_ids
        and owner is not None
        and owner.get("pinned") is True
        and owner.get("muted") is True
        and owner.get("archived") is True
        and peer_row is not None
        and peer_row.get("pinned") is False
        and peer_row.get("muted") is False
        and peer_row.get("archived") is False
        and peer_arch_row is None
        and entry_for(peer_labeled, conversation_id) is None
    )
    results.add(
        "preference_isolation",
        "PASS" if ok else "FAIL",
        f"assign={assigned.status} pin={pinned.status} mute={muted.status} archive={archived.status} "
        f"owner_label={label_id in owner_ids} peer_label={label_id in peer_ids} "
        f"owner_flags={None if owner is None else (owner.get('pinned'), owner.get('muted'), owner.get('archived'))} "
        f"peer_flags={None if peer_row is None else (peer_row.get('pinned'), peer_row.get('muted'), peer_row.get('archived'))} "
        f"peer_archived_hidden={peer_arch_row is None} peer_label_hidden={entry_for(peer_labeled, conversation_id) is None}",
    )


def scenario_mentions(results: Results, client: Client, app_container: str, admin: dict, peer: dict) -> None:
    group = create_group(client, admin["token"], "E2E mentions", [peer["id"]])
    muted = client.put(
        f"/api/v1/conversations/{group['id']}/mute",
        token=peer["token"],
        body={"forever": True},
    )
    registered = client.post(
        "/api/v1/users/device-token",
        token=peer["token"],
        body={"token": "e2e-mention-" + uuid.uuid4().hex, "platform": "android", "deviceLabel": "e2e"},
    )
    before = push_counts(app_container)["NEW_MESSAGE"]
    plain = post_message(client, admin["token"], group["id"], "ordinary muted text")
    time.sleep(1.0)
    after_plain = push_counts(app_container)["NEW_MESSAGE"]
    content = "ping member"
    mentioned = post_message(
        client, admin["token"], group["id"], content,
        mentions=[{"userId": peer["id"], "offset": 0, "length": len(content)}],
    )
    time.sleep(1.0)
    after_mention = push_counts(app_container)["NEW_MESSAGE"]
    ok = (
        muted.status == 204
        and registered.status == 200
        and plain.status == 201
        and mentioned.status == 201
        and after_plain == before
        and after_mention == before + 1
    )
    results.add(
        "mention_notification",
        "PASS" if ok else "FAIL",
        f"mute={muted.status} token={registered.status} plain={plain.status} "
        f"mention={mentioned.status} pushes={before}->{after_plain}->{after_mention}",
    )


def scenario_reminder(results: Results, client: Client, db: Database, app_container: str, user: dict, peer: dict) -> None:
    created = client.post(
        "/api/v1/conversations/direct",
        token=user["token"],
        body={"phone": peer["phone"]},
    )
    if created.status not in (200, 201):
        results.add("reminder_fires", "FAIL", f"direct HTTP {created.status}")
        return
    conversation_id = created.json()["id"]
    registered = client.post(
        "/api/v1/users/device-token",
        token=user["token"],
        body={"token": "e2e-reminder-" + uuid.uuid4().hex, "platform": "android", "deviceLabel": "e2e"},
    )
    before = push_counts(app_container)["REMINDER"]
    reminder = client.post(
        f"/api/v1/conversations/{conversation_id}/reminders",
        token=user["token"],
        body={
            "title": "e2e due",
            "remindAt": iso_utc(utc_after(seconds=3)),
            "repeat": "NONE",
        },
    )
    if reminder.status != 201:
        results.add("reminder_fires", "FAIL", f"create HTTP {reminder.status} token={registered.status}")
        return
    reminder_id = reminder.json()["id"]
    fired = "null"
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        fired = db.scalar(
            "SELECT COALESCE(fired_at::text, 'null') FROM conversation_reminders "
            f"WHERE id = {quote_uuid(reminder_id)}"
        )
        if fired != "null":
            break
        time.sleep(0.5)
    after = push_counts(app_container)["REMINDER"]
    ok = registered.status == 200 and fired != "null" and after >= before + 1
    results.add(
        "reminder_fires",
        "PASS" if ok else "FAIL",
        f"token={registered.status} fired_at_set={fired != 'null'} pushes={before}->{after}",
    )


def scenario_polls(results: Results, client: Client, admin: dict, peer: dict, outsider: dict) -> None:
    group = create_group(client, admin["token"], "E2E polls", [peer["id"]])
    created = client.post(
        f"/api/v1/conversations/{group['id']}/polls",
        token=admin["token"],
        body={
            "question": "which letters",
            "options": ["Alpha", "Beta", "Gamma"],
            "multipleChoice": True,
        },
    )
    if created.status != 201:
        results.add("polls_vote_change_close", "FAIL", f"create HTTP {created.status}")
        return
    poll = created.json()
    options = {item["label"]: item["id"] for item in poll.get("options") or []}
    first = client.put(
        f"/api/v1/conversations/{group['id']}/polls/{poll['id']}/vote",
        token=peer["token"],
        body={"optionIds": [options["Alpha"], options["Beta"]]},
    )
    changed = client.put(
        f"/api/v1/conversations/{group['id']}/polls/{poll['id']}/vote",
        token=peer["token"],
        body={"optionIds": [options["Gamma"]]},
    )
    denied = client.put(
        f"/api/v1/conversations/{group['id']}/polls/{poll['id']}/vote",
        token=outsider["token"],
        body={"optionIds": [options["Alpha"]]},
    )
    closed = client.post(
        f"/api/v1/conversations/{group['id']}/polls/{poll['id']}/close",
        token=admin["token"],
    )
    after_close = client.put(
        f"/api/v1/conversations/{group['id']}/polls/{poll['id']}/vote",
        token=peer["token"],
        body={"optionIds": [options["Alpha"]]},
    )
    body = changed.json() or {}
    counts = {item.get("label"): item.get("voteCount") for item in body.get("options") or []}
    mine = body.get("myOptionIds") or []
    ok = (
        first.status == 200
        and changed.status == 200
        and counts.get("Alpha") == 0
        and counts.get("Beta") == 0
        and counts.get("Gamma") == 1
        and mine == [options["Gamma"]]
        and denied.status == 403
        and closed.status == 200
        and (closed.json() or {}).get("status") == "CLOSED"
        and after_close.status != 200
    )
    results.add(
        "polls_vote_change_close",
        "PASS" if ok else "FAIL",
        f"first={first.status} change={changed.status} counts={counts} "
        f"mine_len={len(mine)} outsider={denied.status} close={closed.status} "
        f"closed_status={(closed.json() or {}).get('status')} after_close={after_close.status}",
    )


def sync_pages(client: Client, token: str, conversation_id: str, last_seq: int, since: str) -> dict[str, Any]:
    new_messages: list[dict[str, Any]] = []
    deleted: list[str] = []
    cursor = last_seq
    pages = 0
    has_more = True
    max_seq = None
    while has_more and pages < 12:
        response = client.post(
            "/api/v1/sync",
            token=token,
            body={
                "watermarks": [{"conversationId": conversation_id, "lastSeq": cursor}],
                "limitPerConv": 100,
                "since": since,
            },
        )
        if response.status != 200:
            raise E2EFailure(f"sync HTTP {response.status} page={pages}")
        deltas = (response.json() or {}).get("deltas") or []
        if len(deltas) != 1:
            raise E2EFailure(f"sync deltas={len(deltas)}")
        delta = deltas[0]
        batch = delta.get("newMessages") or []
        new_messages.extend(batch)
        for message_id in delta.get("deletedMessageIds") or []:
            if message_id not in deleted:
                deleted.append(message_id)
        has_more = bool(delta.get("hasMore"))
        max_seq = delta.get("maxSeq")
        pages += 1
        if not batch:
            break
        cursor = int(batch[-1]["seq"])
    return {
        "new": new_messages,
        "deleted": deleted,
        "pages": pages,
        "maxSeq": max_seq,
        "hasMore": has_more,
    }


def scenario_xff(results: Results, client: Client, admin: dict, peer: dict, outsider: dict) -> None:
    """Spoofed X-Forwarded-For must not mint a fresh per-IP preview bucket.

    Bucket4j refills greedily, so the 61st call can still succeed. The
    unchanged final_live.py assertion stops there. This check keeps going, the
    same way P1InviteIpRateLimitIT does, and requires a 429 before a new
    forwarded address could have granted another full window.
    """
    group = create_group(client, admin["token"], "E2E xff ext", [peer["id"]])
    created = client.post(
        f"/api/v1/conversations/{group['id']}/invite-link",
        token=admin["token"],
        body={"requiresApproval": False},
    )
    if created.status != 201 or not (created.json() or {}).get("token"):
        results.add("spoofed_xff_eventually_limited", "FAIL", f"create HTTP {created.status}")
        return
    raw_token = created.json()["token"]
    callers = [admin, outsider]
    first_429 = None
    started = time.perf_counter()
    for index in range(1, 76):
        caller = callers[index % 2]
        response = client.get(
            f"/api/v1/invite-links/{raw_token}",
            token=caller["token"],
            headers={"X-Forwarded-For": f"198.51.100.{index}"},
        )
        if response.status == 429 and response.error_code() == "RATE_LIMITED":
            first_429 = index
            break
        if response.status != 200:
            results.add(
                "spoofed_xff_eventually_limited",
                "FAIL",
                f"unexpected HTTP {response.status} at {index}",
            )
            return
    elapsed = time.perf_counter() - started
    ok = first_429 is not None and first_429 <= 75 and elapsed < 30
    results.add(
        "spoofed_xff_eventually_limited",
        "PASS" if ok else "FAIL",
        f"first_429={first_429} elapsed={elapsed:.1f}s",
    )


def scenario_delta_gap(results: Results, client: Client, db: Database, user: dict, peer: dict) -> None:
    created = client.post(
        "/api/v1/conversations/direct",
        token=user["token"],
        body={"phone": peer["phone"]},
    )
    if created.status not in (200, 201):
        results.add("delta_sync_gap_tombstones", "FAIL", f"direct HTTP {created.status}")
        return
    conversation_id = created.json()["id"]
    prefix = send_paced(
        client, user["token"], conversation_id,
        [f"prefix-{index:02d}" for index in range(8)],
    )
    watermark = int(prefix[-1]["seq"])
    since = db.scalar("SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')")
    tombstones = prefix[:2]
    for item in tombstones:
        deleted = client.delete(
            f"/api/v1/conversations/{conversation_id}/messages/{item['id']}",
            token=user["token"],
        )
        if deleted.status != 204:
            results.add("delta_sync_gap_tombstones", "FAIL", f"prefix recall HTTP {deleted.status}")
            return
    gap = send_paced(
        client, user["token"], conversation_id,
        [f"gap-{index:04d}" for index in range(GAP_COUNT)],
    )
    gap_tombstone = gap[GAP_COUNT // 2]
    recalled = client.delete(
        f"/api/v1/conversations/{conversation_id}/messages/{gap_tombstone['id']}",
        token=user["token"],
    )
    if recalled.status != 204:
        results.add("delta_sync_gap_tombstones", "FAIL", f"gap recall HTTP {recalled.status}")
        return
    try:
        pages = sync_pages(client, peer["token"], conversation_id, watermark, since)
    except E2EFailure as exc:
        results.add("delta_sync_gap_tombstones", "FAIL", str(exc)[:240])
        return
    got_ids = [item.get("id") for item in pages["new"]]
    expected_ids = [item["id"] for item in gap]
    seqs = [int(item["seq"]) for item in pages["new"]]
    contiguous = seqs == list(range(seqs[0], seqs[0] + len(seqs))) if seqs else False
    tombstone_row = next((item for item in pages["new"] if item.get("id") == gap_tombstone["id"]), None)
    prefix_ids = {item["id"] for item in tombstones}
    ok = (
        got_ids == expected_ids
        and len(got_ids) == len(set(got_ids)) == GAP_COUNT
        and contiguous
        and prefix_ids.issubset(set(pages["deleted"]))
        and tombstone_row is not None
        and tombstone_row.get("content") is None
        and tombstone_row.get("deletedAt")
        and pages["hasMore"] is False
    )
    results.add(
        "delta_sync_gap_tombstones",
        "PASS" if ok else "FAIL",
        f"pages={pages['pages']} got={len(got_ids)} unique={len(set(got_ids))} "
        f"contiguous={contiguous} prefix_tombstones={prefix_ids.issubset(set(pages['deleted']))} "
        f"gap_content_null={tombstone_row is not None and tombstone_row.get('content') is None} "
        f"hasMore={pages['hasMore']} maxSeq={pages['maxSeq']}",
    )


def scenario_fanout(results: Results, client: Client, ws_url: str, admin: dict, peer: dict) -> None:
    group = create_group(client, admin["token"], "E2E fanout", [peer["id"]])
    sockets: list[Stomp] = []
    try:
        errors: list[str] = []

        def open_one(box: list[Stomp | None]) -> None:
            try:
                socket = Stomp(ws_url, peer["token"])
                socket.subscribe(f"/topic/conversations.{group['id']}")
                box.append(socket)
            except Exception as exc:  # noqa: BLE001 — recorded per client
                errors.append(type(exc).__name__)
                box.append(None)

        boxes: list[list[Stomp | None]] = []
        threads = []
        for _ in range(FANOUT_CLIENTS):
            box: list[Stomp | None] = []
            boxes.append(box)
            thread = threading.Thread(target=open_one, args=(box,))
            threads.append(thread)
            thread.start()
        for thread in threads:
            thread.join(timeout=30)
        sockets = [box[0] for box in boxes if box and box[0] is not None]
        if len(sockets) != FANOUT_CLIENTS:
            results.add(
                "fanout_20_ordered",
                "FAIL",
                f"connected={len(sockets)} errors={errors[:5]}",
            )
            return
        time.sleep(0.4)
        contents = [f"fan-{index:02d}" for index in range(FANOUT_MESSAGES)]
        sent = send_paced(client, admin["token"], group["id"], contents)
        expected = [item["id"] for item in sent]
        time.sleep(1.5)
        mismatches = 0
        for socket in sockets:
            found: list[str] = []
            for frame in socket.drain(1.0):
                if frame[0] != "MESSAGE":
                    continue
                try:
                    payload = json.loads(frame[2] or "{}")
                except json.JSONDecodeError:
                    continue
                if payload.get("eventType") != "MESSAGE_CREATED":
                    continue
                message_id = (payload.get("payload") or {}).get("id")
                if message_id in expected and message_id not in found:
                    found.append(message_id)
            ordered = [message_id for message_id in expected if message_id in found]
            if ordered != expected:
                mismatches += 1
        ok = mismatches == 0
        results.add(
            "fanout_20_ordered",
            "PASS" if ok else "FAIL",
            f"clients={len(sockets)} messages={len(expected)} mismatches={mismatches}",
        )
    except E2EFailure as exc:
        results.add("fanout_20_ordered", "FAIL", str(exc)[:240])
    finally:
        for socket in sockets:
            socket.close()


def wait_health(base_url: str, timeout: float = 120.0) -> bool:
    deadline = time.monotonic() + timeout
    probe = Client(base_url, timeout=5)
    while time.monotonic() < deadline:
        try:
            response = probe.get("/actuator/health")
        except (E2EFailure, OSError, ConnectionError):
            time.sleep(2)
            continue
        if response.status == 200 and (response.json() or {}).get("status") == "UP":
            return True
        time.sleep(2)
    return False


def scenario_restart(
    results: Results,
    client: Client,
    app_container: str,
    user: dict,
    peer: dict,
) -> None:
    created = client.post(
        "/api/v1/conversations/direct",
        token=user["token"],
        body={"phone": peer["phone"]},
    )
    if created.status not in (200, 201):
        results.add("restart_resync", "FAIL", f"direct HTTP {created.status}")
        return
    conversation_id = created.json()["id"]
    before = send_paced(
        client, user["token"], conversation_id,
        [f"restart-{index}" for index in range(5)],
    )
    proc = subprocess.run(
        ["docker", "restart", app_container],
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        results.add("restart_resync", "FAIL", "docker restart failed")
        return
    if not wait_health(client.base_url):
        results.add("restart_resync", "FAIL", "health did not return")
        return

    def get_retry(token: str, path: str, params: dict[str, str] | None = None):
        last_error = "unavailable"
        for _ in range(8):
            try:
                response = client.get(path, token=token, params=params)
            except (E2EFailure, OSError, ConnectionError) as exc:
                last_error = type(exc).__name__
                time.sleep(2)
                continue
            if response.status == 200:
                return response
            last_error = f"HTTP {response.status}"
            if response.status != 401:
                time.sleep(2)
                continue
            return response
        raise E2EFailure(last_error)

    probe = get_retry(user["token"], "/api/v1/users/me")
    if probe.status == 401:
        results.add("restart_resync", "FAIL", "access token rejected after restart")
        return
    history = get_retry(
        peer["token"],
        f"/api/v1/conversations/{conversation_id}/messages",
        {"limit": "20"},
    )
    items = (history.json() or {}).get("items") or []
    ids = [item.get("id") for item in items]
    expected = [item["id"] for item in before]
    extra = None
    for _ in range(8):
        try:
            extra = post_message(client, user["token"], conversation_id, "restart-after")
        except (E2EFailure, OSError, ConnectionError):
            time.sleep(2)
            continue
        if extra.status in (200, 201):
            break
        time.sleep(2)
    if extra is None:
        results.add("restart_resync", "FAIL", "send after restart failed")
        return
    history_after = get_retry(
        peer["token"],
        f"/api/v1/conversations/{conversation_id}/messages",
        {"limit": "20"},
    )
    ids_after = [item.get("id") for item in ((history_after.json() or {}).get("items") or [])]
    present_once = all(ids.count(message_id) == 1 for message_id in expected)
    still_once = all(ids_after.count(message_id) == 1 for message_id in expected)
    new_id = (extra.json() or {}).get("id")
    ok = (
        history.status == 200
        and present_once
        and set(expected).issubset(set(ids))
        and extra.status == 201
        and still_once
        and ids_after.count(new_id) == 1
    )
    results.add(
        "restart_resync",
        "PASS" if ok else "FAIL",
        f"health_ok=true before={len(expected)} present_once={present_once} "
        f"after_send={extra.status} still_once={still_once} new_once={ids_after.count(new_id) == 1}",
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Extended NexaChat live scenarios",
        epilog=(
            "Environment: the disposable stack from final_live.py, with the app container name "
            "for MockFcmGateway push counts and the restart scenario. Set FCM_ENABLED=false "
            "literally in the app container when the run expects the mock gateway (a host "
            "FCM_ENABLED=true overrides the compose env file). Retention is driven by "
            "final_live.py and needs NEXACHAT_RETENTION_INTERVAL_MS>=60000, "
            "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,metrics and an admin phone in "
            "NEXACHAT_ADMIN_PHONES. Requires websocket-client. Exit status is 1 when any "
            "scenario fails; a pipeline ending in tee hides it unless pipefail is set."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--base-url", default=os.environ.get("API_BASE_URL") or "http://127.0.0.1:18091")
    parser.add_argument("--db-container", default="nexachat_e2e_grok2-r4-db")
    parser.add_argument("--db-user", default="e2egrok2")
    parser.add_argument("--db-name", default="nexachat_e2e_grok2")
    parser.add_argument("--app-container", default="nexachat_r4_e2e-app-1")
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--only", default="", help="Comma-separated scenario keys to run")
    args = parser.parse_args(argv)

    client = Client(args.base_url, timeout=args.timeout)
    database = Database(args.db_container, args.db_user, args.db_name)
    ws_url = args.base_url.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
    results = Results()
    results.add(
        "retention_job",
        "NOT_RUN",
        "exercised by final_live.py retention-seed and retention-observe on this stack",
    )

    print("Creating three throwaway accounts...", flush=True)
    admin = create_user(client, "E2E ext admin")
    peer = create_user(client, "E2E ext peer")
    outsider = create_user(client, "E2E ext outsider")
    print(
        f"accounts admin={mask_id(admin['id'])} peer={mask_id(peer['id'])} "
        f"outsider={mask_id(outsider['id'])}",
        flush=True,
    )

    scenarios = [
        ("idempotent", lambda: scenario_idempotent(results, client, database, admin, peer)),
        ("recall", lambda: scenario_recall_derived(results, client, database, admin, peer)),
        ("edit_cache", lambda: scenario_edit_cache(results, client, database, admin, peer)),
        ("isolation", lambda: scenario_isolation(results, client, admin, peer)),
        ("mentions", lambda: scenario_mentions(results, client, args.app_container, admin, peer)),
        ("reminder", lambda: scenario_reminder(results, client, database, args.app_container, admin, peer)),
        ("polls", lambda: scenario_polls(results, client, admin, peer, outsider)),
        ("ack", lambda: scenario_stomp_ack(results, client, ws_url, admin, peer, outsider)),
        ("removed", lambda: scenario_removed_member(results, client, database, ws_url, admin, peer)),
        ("rotation", lambda: scenario_invite_rotation(results, client, admin, peer, outsider)),
        ("delta", lambda: scenario_delta_gap(results, client, database, admin, peer)),
        ("xff", lambda: scenario_xff(results, client, admin, peer, outsider)),
        ("fanout", lambda: scenario_fanout(results, client, ws_url, admin, peer)),
        ("restart", lambda: scenario_restart(results, client, args.app_container, admin, peer)),
    ]
    selected = {item.strip() for item in args.only.split(",") if item.strip()}
    for name, scenario in scenarios:
        if selected and name not in selected:
            continue
        try:
            scenario()
        except Exception as exc:  # noqa: BLE001 — one scenario must not abort the rest
            results.add(name, "FAIL", type(exc).__name__ + ": " + str(exc)[:200])
    return results.finish()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except E2EFailure as exc:
        print(f"[FAIL] {exc}", flush=True)
        sys.exit(1)
