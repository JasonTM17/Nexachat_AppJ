#!/usr/bin/env python3
"""Final-pass live scenarios against a disposable NexaChat backend.

Phases
------
http
    Invite concurrency, in-flight revoke, approval maxUses, recall vs ask
    cache, and spoofed X-Forwarded-For. The two race checks hold the
    conversation row through psql in this suite's own database container.
retention-seed
    Creates the rows the retention jobs must keep and the rows they may remove,
    then ages only the removable ones. Writes ids to the state file.
retention-observe --expect dry-run|deleted
    Reads actuator counters and the database. dry-run requires observed counts
    and zero deletions. deleted requires the aged rows to be gone and the
    active rows to remain.

The database container name must start with ``nexachat_e2e_grok2``. Tokens,
passwords and phone numbers are never printed.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
from typing import Any, Callable

HERE = Path(__file__).resolve().parent
E2E_DIR = HERE.parent
NEW_FEATURES = E2E_DIR / "new_features"
for path in (E2E_DIR, NEW_FEATURES):
    if str(path) not in sys.path:
        sys.path.insert(0, str(path))

from e2e_common import Client, E2EFailure  # noqa: E402
from features_helpers import iso_utc, mask_id, png_1x1, utc_after  # noqa: E402
from features_live import create_group, create_user, send_message, upload_attachment  # noqa: E402

UUID_RE = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)
FORBIDDEN_CONTAINERS = {"nexachat-db-1", "nexachat-app-1", "emulator-5570"}
ALLOWED_PREFIX = "nexachat_e2e_grok2"
PREVIEW_LIMIT = 60
MAX_USES = 2
PARALLEL_JOINERS = 6
KEY_PATTERN = r"^attachments/[0-9a-fA-F-]{36}[.][a-z0-9]+$"
RETENTION_JOBS = ("invite-links", "reminders", "polls", "attachments")


class Results:
    def __init__(self) -> None:
        self.rows: list[tuple[str, bool, str]] = []

    def add(self, name: str, ok: bool, detail: str) -> None:
        self.rows.append((name, ok, detail))
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")

    def finish(self) -> int:
        failed = [row for row in self.rows if not row[1]]
        print(f"{len(self.rows) - len(failed)} passed, {len(failed)} failed")
        return 1 if failed else 0


def assert_container(name: str) -> None:
    if (
        name in FORBIDDEN_CONTAINERS
        or name.startswith("campusute")
        or not name.startswith(ALLOWED_PREFIX)
    ):
        raise SystemExit(f"refusing database container {name}")


def quote_uuid(value: str) -> str:
    if not UUID_RE.match(value):
        raise E2EFailure("refusing a non-uuid SQL literal")
    return "'" + value + "'"


class Database:
    def __init__(self, container: str, user: str, name: str) -> None:
        assert_container(container)
        if not re.fullmatch(r"[A-Za-z0-9_]+", user):
            raise SystemExit("refusing database user")
        if not re.fullmatch(r"[A-Za-z0-9_]+", name):
            raise SystemExit("refusing database name")
        self.container = container
        self.user = user
        self.name = name

    def run(self, sql: str, *, wait: bool = True) -> subprocess.CompletedProcess[bytes] | subprocess.Popen[bytes]:
        command = [
            "docker", "exec", "-i", self.container,
            "psql", "-U", self.user, "-d", self.name,
            "-v", "ON_ERROR_STOP=1", "-tA",
        ]
        if wait:
            return subprocess.run(command, input=sql.encode(), capture_output=True, check=False)
        return subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def scalar(self, sql: str) -> str:
        completed = self.run(sql)
        assert isinstance(completed, subprocess.CompletedProcess)
        if completed.returncode != 0:
            detail = completed.stderr.decode("utf-8", "replace")[:400]
            raise E2EFailure(f"psql failed: {detail}")
        return completed.stdout.decode("utf-8", "replace").strip()

    def count(self, sql: str) -> int:
        value = self.scalar(sql)
        if not value.isdigit():
            raise E2EFailure(f"expected a count, got {value[:40]!r}")
        return int(value)


def state_path() -> Path:
    override = os.environ.get("E2E_STATE_FILE", "").strip()
    if override:
        return Path(override)
    return Path(tempfile.gettempdir()) / "nexachat-e2e-final-retention.json"


def write_state(payload: dict[str, Any]) -> None:
    path = state_path()
    path.write_text(json.dumps(payload), encoding="utf-8")
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


def read_state() -> dict[str, Any]:
    path = state_path()
    if not path.is_file():
        raise E2EFailure("retention state file is missing; run retention-seed first")
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise E2EFailure("retention state file is not an object")
    return data


def register_many(client: Client, count: int, label: str) -> list[dict[str, Any]]:
    return [create_user(client, f"{label} {index}") for index in range(count)]


def hold_conversation(db: Database, conversation_id: str, seconds: int) -> subprocess.Popen[bytes]:
    sql = (
        "BEGIN;\n"
        f"SELECT 1 FROM conversations WHERE id = {quote_uuid(conversation_id)} FOR UPDATE;\n"
        f"SELECT pg_sleep({int(seconds)});\n"
        "COMMIT;\n"
    )
    process = db.run(sql, wait=False)
    assert isinstance(process, subprocess.Popen)
    assert process.stdin is not None
    process.stdin.write(sql.encode())
    process.stdin.close()
    return process


def wait_for_sleep(db: Database, timeout: float = 8.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            active = db.count(
                "SELECT count(*) FROM pg_stat_activity "
                "WHERE state = 'active' AND query LIKE 'SELECT pg_sleep%'"
            )
        except E2EFailure:
            active = 0
        if active >= 1:
            return True
        time.sleep(0.2)
    return False


def stop_process(process: subprocess.Popen[bytes] | None) -> None:
    if process is None or process.poll() is not None:
        return
    process.kill()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass


def member_count(db: Database, conversation_id: str, user_ids: list[str]) -> int:
    if not user_ids:
        return 0
    ids = ", ".join(quote_uuid(user_id) for user_id in user_ids)
    return db.count(
        "SELECT count(*) FROM conversation_members "
        f"WHERE conversation_id = {quote_uuid(conversation_id)} AND user_id IN ({ids})"
    )


def scenario_parallel_joins(results: Results, client: Client, db: Database) -> None:
    admin = create_user(client, "E2E cap admin")
    partner = create_user(client, "E2E cap partner")
    joiners = register_many(client, PARALLEL_JOINERS, "E2E joiner")
    group = create_group(client, admin["token"], "E2E max uses", [partner["id"]])
    created = client.post(
        f"/api/v1/conversations/{group['id']}/invite-link",
        token=admin["token"],
        body={"requiresApproval": False, "maxUses": MAX_USES},
    )
    if created.status != 201 or not (created.json() or {}).get("token"):
        results.add("invite_parallel_setup", False, f"create link HTTP {created.status}")
        return
    raw_token = created.json()["token"]
    link_id = created.json()["id"]
    process = hold_conversation(db, group["id"], 12)
    try:
        if not wait_for_sleep(db):
            results.add("invite_parallel_lock", False, "conversation row lock did not start")
            return
        statuses: list[int] = []
        joined_box = [0]
        barrier = threading.Barrier(PARALLEL_JOINERS)
        lock = threading.Lock()

        def one(user: dict[str, Any]) -> None:
            barrier.wait(timeout=5)
            response = client.post(
                f"/api/v1/invite-links/{raw_token}/join", token=user["token"]
            )
            body = response.json() or {}
            with lock:
                statuses.append(response.status)
                if response.status == 200 and body.get("status") == "JOINED":
                    joined_box[0] += 1

        threads = [threading.Thread(target=one, args=(user,)) for user in joiners]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=30)
        joined = joined_box[0]
    finally:
        if process.poll() is None:
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                stop_process(process)
    use_count = db.count(
        f"SELECT use_count FROM group_invite_links WHERE id = {quote_uuid(link_id)}"
    )
    members = member_count(db, group["id"], [user["id"] for user in joiners])
    no_server_error = statuses and all(status < 500 for status in statuses)
    within_cap = joined <= MAX_USES and use_count <= MAX_USES and members <= MAX_USES
    reached_cap = joined == MAX_USES and use_count == MAX_USES and members == MAX_USES
    results.add(
        "invite_parallel_max_uses",
        bool(no_server_error and within_cap and reached_cap and len(statuses) == PARALLEL_JOINERS),
        f"statuses={sorted(statuses)} joined={joined} useCount={use_count} members={members} cap={MAX_USES}",
    )


def scenario_revoke_inflight(results: Results, client: Client, db: Database) -> None:
    admin = create_user(client, "E2E revoke admin")
    partner = create_user(client, "E2E revoke partner")
    joiner = create_user(client, "E2E revoke joiner")
    group = create_group(client, admin["token"], "E2E revoke inflight", [partner["id"]])
    created = client.post(
        f"/api/v1/conversations/{group['id']}/invite-link",
        token=admin["token"],
        body={"requiresApproval": False},
    )
    if created.status != 201 or not (created.json() or {}).get("token"):
        results.add("invite_revoke_setup", False, f"create link HTTP {created.status}")
        return
    raw_token = created.json()["token"]
    link_id = created.json()["id"]
    outcome: dict[str, Any] = {}
    process = hold_conversation(db, group["id"], 10)
    try:
        if not wait_for_sleep(db):
            results.add("invite_revoke_lock", False, "conversation row lock did not start")
            return

        def do_join() -> None:
            outcome["join"] = client.post(
                f"/api/v1/invite-links/{raw_token}/join", token=joiner["token"]
            )

        worker = threading.Thread(target=do_join)
        worker.start()
        time.sleep(1.0)
        revoke_started = time.perf_counter()
        outcome["revoke"] = client.delete(
            f"/api/v1/conversations/{group['id']}/invite-link", token=admin["token"]
        )
        outcome["revoke_seconds"] = time.perf_counter() - revoke_started
        outcome["lock_still_held"] = process.poll() is None
        worker.join(timeout=25)
    finally:
        if process.poll() is None:
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                stop_process(process)
    join_response = outcome.get("join")
    revoke_response = outcome.get("revoke")
    membership = member_count(db, group["id"], [joiner["id"]])
    revoked = db.count(
        "SELECT count(*) FROM group_invite_links "
        f"WHERE id = {quote_uuid(link_id)} AND revoked_at IS NOT NULL"
    )
    follow = client.post(f"/api/v1/invite-links/{raw_token}/join", token=joiner["token"])
    ok = (
        revoke_response is not None
        and revoke_response.status == 204
        and outcome.get("lock_still_held") is True
        and join_response is not None
        and join_response.status == 404
        and membership == 0
        and revoked == 1
        and follow.status == 404
    )
    join_status = join_response.status if join_response is not None else "missing"
    revoke_status = revoke_response.status if revoke_response is not None else "missing"
    results.add(
        "invite_revoke_inflight",
        ok,
        "revoke="
        f"{revoke_status} while_locked={outcome.get('lock_still_held')} "
        f"join={join_status} members={membership} revoked={revoked} follow={follow.status}",
    )


def scenario_approval_max_uses(results: Results, client: Client, db: Database) -> None:
    admin = create_user(client, "E2E approval admin")
    partner = create_user(client, "E2E approval partner")
    requesters = register_many(client, MAX_USES + 1, "E2E approval")
    group = create_group(client, admin["token"], "E2E approval cap", [partner["id"]])
    created = client.post(
        f"/api/v1/conversations/{group['id']}/invite-link",
        token=admin["token"],
        body={"requiresApproval": True, "maxUses": MAX_USES},
    )
    if created.status != 201 or not (created.json() or {}).get("token"):
        results.add("approval_setup", False, f"create link HTTP {created.status}")
        return
    raw_token = created.json()["token"]
    link_id = created.json()["id"]
    request_ids: list[str] = []
    for user in requesters:
        joined = client.post(f"/api/v1/invite-links/{raw_token}/join", token=user["token"])
        body = joined.json() or {}
        if joined.status != 201 or body.get("status") != "PENDING" or not body.get("requestId"):
            results.add(
                "approval_requests",
                False,
                f"join HTTP {joined.status} status={body.get('status')}",
            )
            return
        request_ids.append(body["requestId"])
    approve_statuses = []
    for request_id in request_ids:
        approved = client.post(
            f"/api/v1/conversations/{group['id']}/join-requests/{request_id}/approve",
            token=admin["token"],
        )
        approve_statuses.append(approved.status)
    members = member_count(db, group["id"], [user["id"] for user in requesters])
    use_count = db.count(
        f"SELECT use_count FROM group_invite_links WHERE id = {quote_uuid(link_id)}"
    )
    extra_member = member_count(db, group["id"], [requesters[-1]["id"]])
    ok = (
        approve_statuses[:MAX_USES] == [200] * MAX_USES
        and approve_statuses[-1] != 200
        and members == MAX_USES
        and use_count == MAX_USES
        and extra_member == 0
    )
    results.add(
        "approval_honours_max_uses",
        ok,
        f"approve={approve_statuses} members={members} useCount={use_count} extra={extra_member}",
    )


def scenario_recall_ask_cache(results: Results, client: Client, db: Database) -> None:
    admin = create_user(client, "E2E ask admin")
    partner = create_user(client, "E2E ask partner")
    created = client.post(
        "/api/v1/conversations/direct",
        token=admin["token"],
        body={"phone": partner["phone"]},
    )
    if created.status not in (200, 201):
        results.add("recall_ask_setup", False, f"direct HTTP {created.status}")
        return
    conversation_id = created.json()["id"]
    sent = send_message(
        client, admin["token"], conversation_id,
        content="The recall cache token is violet-heron-42.",
    )
    if sent.status != 201:
        results.add("recall_ask_send", False, f"send HTTP {sent.status}")
        return
    message_id = sent.json()["id"]
    mode = None
    for _ in range(4):
        asked = client.post(
            f"/api/v1/conversations/{conversation_id}/ask",
            token=admin["token"],
            body={"question": "What is the recall cache token?"},
        )
        mode = (asked.json() or {}).get("mode") if asked.status == 200 else f"HTTP {asked.status}"
        if db.count(
            "SELECT count(*) FROM rag_ask_cache "
            f"WHERE conversation_id = {quote_uuid(conversation_id)}"
        ) > 0:
            break
        time.sleep(1.5)
    before = db.count(
        "SELECT count(*) FROM rag_ask_cache "
        f"WHERE conversation_id = {quote_uuid(conversation_id)}"
    )
    seeded = False
    if before == 0:
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
        seeded = True
        before = db.count(
            "SELECT count(*) FROM rag_ask_cache "
            f"WHERE conversation_id = {quote_uuid(conversation_id)}"
        )
    deleted = client.delete(
        f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
        token=admin["token"],
    )
    after = db.count(
        "SELECT count(*) FROM rag_ask_cache "
        f"WHERE conversation_id = {quote_uuid(conversation_id)}"
    )
    results.add(
        "recall_removes_ask_cache",
        deleted.status == 204 and before > 0 and after == 0,
        f"ask_mode={mode} seeded={seeded} before={before} delete={deleted.status} after={after}",
    )


def scenario_spoofed_forwarded_for(results: Results, client: Client) -> None:
    admin = create_user(client, "E2E xff admin")
    partner = create_user(client, "E2E xff partner")
    group = create_group(client, admin["token"], "E2E xff", [partner["id"]])
    created = client.post(
        f"/api/v1/conversations/{group['id']}/invite-link",
        token=admin["token"],
        body={"requiresApproval": False},
    )
    if created.status != 201 or not (created.json() or {}).get("token"):
        results.add("xff_setup", False, f"create link HTTP {created.status}")
        return
    raw_token = created.json()["token"]
    callers = [admin, partner]
    statuses = []
    for index in range(1, PREVIEW_LIMIT + 2):
        caller = callers[index % 2]
        response = client.get(
            f"/api/v1/invite-links/{raw_token}",
            token=caller["token"],
            headers={"X-Forwarded-For": f"198.51.100.{index}"},
        )
        statuses.append(response.status)
        if index <= PREVIEW_LIMIT and response.status != 200:
            break
    first_sixty = statuses[:PREVIEW_LIMIT]
    last = statuses[PREVIEW_LIMIT] if len(statuses) > PREVIEW_LIMIT else None
    last_code = None
    if last == 429:
        probe = client.get(
            f"/api/v1/invite-links/{raw_token}",
            token=admin["token"],
            headers={"X-Forwarded-For": "203.0.113.50, 198.51.100.200"},
        )
        last_code = probe.error_code()
        last = probe.status
    ok = all(status == 200 for status in first_sixty) and last == 429 and last_code == "RATE_LIMITED"
    results.add(
        "spoofed_xff_does_not_bypass_preview_limit",
        ok,
        f"first_{len(first_sixty)}_ok={all(status == 200 for status in first_sixty)} "
        f"next={last} code={last_code}",
    )


def run_http(client: Client, db: Database) -> int:
    results = Results()
    scenarios: list[tuple[str, Callable[[], None]]] = [
        ("parallel", lambda: scenario_parallel_joins(results, client, db)),
        ("revoke", lambda: scenario_revoke_inflight(results, client, db)),
        ("approval", lambda: scenario_approval_max_uses(results, client, db)),
        ("recall", lambda: scenario_recall_ask_cache(results, client, db)),
        ("xff", lambda: scenario_spoofed_forwarded_for(results, client)),
    ]
    for name, scenario in scenarios:
        try:
            scenario()
        except Exception as exc:
            results.add(name, False, type(exc).__name__)
    return results.finish()


def require_id(body: dict[str, Any] | None, status: int, expected: int) -> str:
    if status != expected or not body or not body.get("id"):
        raise E2EFailure(f"expected HTTP {expected} with an id, got {status}")
    return str(body["id"])


def scenario_retention_seed(client: Client, db: Database) -> int:
    admin = create_user(client, "E2E retention admin")
    partner = create_user(client, "E2E retention partner")
    group = create_group(client, admin["token"], "E2E retention", [partner["id"]])
    direct = client.post(
        "/api/v1/conversations/direct",
        token=admin["token"],
        body={"phone": partner["phone"]},
    )
    if direct.status not in (200, 201):
        raise E2EFailure(f"direct conversation HTTP {direct.status}")
    direct_id = direct.json()["id"]

    aged_link = client.post(
        f"/api/v1/conversations/{group['id']}/invite-link",
        token=admin["token"],
        body={"requiresApproval": False, "maxUses": 1},
    )
    aged_link_id = require_id(aged_link.json(), aged_link.status, 201)
    revoked = client.delete(
        f"/api/v1/conversations/{group['id']}/invite-link", token=admin["token"]
    )
    if revoked.status != 204:
        raise E2EFailure(f"revoke HTTP {revoked.status}")
    active_link = client.post(
        f"/api/v1/conversations/{group['id']}/invite-link",
        token=admin["token"],
        body={"requiresApproval": False},
    )
    active_link_id = require_id(active_link.json(), active_link.status, 201)
    db.scalar(
        "UPDATE group_invite_links "
        "SET revoked_at = now() - interval '3 days', updated_at = now() - interval '3 days' "
        f"WHERE id = {quote_uuid(aged_link_id)}"
    )

    reminder_path = f"/api/v1/conversations/{direct_id}/reminders"
    active_reminder = client.post(
        reminder_path, token=admin["token"],
        body={"title": "keep active", "remindAt": iso_utc(utc_after(hours=4)), "repeat": "NONE"},
    )
    active_reminder_id = require_id(active_reminder.json(), active_reminder.status, 201)
    repeating = client.post(
        reminder_path, token=admin["token"],
        body={"title": "keep repeating", "remindAt": iso_utc(utc_after(hours=5)), "repeat": "DAILY"},
    )
    repeating_id = require_id(repeating.json(), repeating.status, 201)
    fired = client.post(
        reminder_path, token=admin["token"],
        body={"title": "aged fired", "remindAt": iso_utc(utc_after(hours=6)), "repeat": "NONE"},
    )
    fired_id = require_id(fired.json(), fired.status, 201)
    db.scalar(
        "UPDATE conversation_reminders "
        "SET fired_at = now() - interval '3 days', remind_at = now() - interval '3 days' "
        f"WHERE id = {quote_uuid(fired_id)}"
    )

    poll_path = f"/api/v1/conversations/{group['id']}/polls"
    active_poll = client.post(
        poll_path, token=admin["token"],
        body={"question": "keep open", "options": ["Yes", "No"], "multipleChoice": False},
    )
    active_poll_id = require_id(active_poll.json(), active_poll.status, 201)
    aged_poll = client.post(
        poll_path, token=admin["token"],
        body={"question": "aged closed", "options": ["Yes", "No"], "multipleChoice": False},
    )
    aged_poll_body = aged_poll.json() or {}
    aged_poll_id = require_id(aged_poll_body, aged_poll.status, 201)
    option_id = next(
        (option.get("id") for option in aged_poll_body.get("options") or [] if option.get("label") == "Yes"),
        None,
    )
    if not option_id:
        raise E2EFailure("aged poll did not return options")
    voted = client.put(
        f"{poll_path}/{aged_poll_id}/vote",
        token=partner["token"],
        body={"optionIds": [option_id]},
    )
    if voted.status != 200:
        raise E2EFailure(f"vote HTTP {voted.status}")
    closed = client.post(f"{poll_path}/{aged_poll_id}/close", token=admin["token"])
    if closed.status != 200:
        raise E2EFailure(f"close HTTP {closed.status}")
    db.scalar(
        "UPDATE polls SET closed_at = now() - interval '3 days' "
        f"WHERE id = {quote_uuid(aged_poll_id)}"
    )

    referenced = upload_attachment(
        client, admin["token"], filename="keep.png", content_type="image/png", data=png_1x1()
    )
    referenced_id = require_id(referenced.json(), referenced.status, 201)
    bound = send_message(
        client, admin["token"], direct_id,
        message_type="IMAGE", content="referenced attachment", attachment_ids=[referenced_id],
    )
    if bound.status != 201:
        raise E2EFailure(f"bind attachment HTTP {bound.status}")
    unbound = upload_attachment(
        client, admin["token"], filename="aged.png", content_type="image/png", data=png_1x1()
    )
    unbound_id = require_id(unbound.json(), unbound.status, 201)
    db.scalar(
        "UPDATE message_attachments SET created_at = now() - interval '3 hours' "
        f"WHERE id = {quote_uuid(unbound_id)}"
    )
    key_ok = db.count(
        "SELECT count(*) FROM message_attachments "
        f"WHERE id = {quote_uuid(unbound_id)} AND storage_key ~ '{KEY_PATTERN}'"
    )
    referenced_bound = db.count(
        "SELECT count(*) FROM message_attachments "
        f"WHERE id = {quote_uuid(referenced_id)} AND message_id IS NOT NULL"
    )
    if key_ok != 1 or referenced_bound != 1:
        raise E2EFailure(
            f"attachment fixture key_ok={key_ok} referenced_bound={referenced_bound}"
        )

    write_state({
        "adminPhone": admin["phone"],
        "adminPassword": admin["password"],
        "activeInvite": active_link_id,
        "agedInvite": aged_link_id,
        "activeReminder": active_reminder_id,
        "repeatingReminder": repeating_id,
        "firedReminder": fired_id,
        "activePoll": active_poll_id,
        "agedPoll": aged_poll_id,
        "referencedAttachment": referenced_id,
        "unboundAttachment": unbound_id,
    })
    print(
        "retention fixtures ready "
        f"active_invite={mask_id(active_link_id)} aged_invite={mask_id(aged_link_id)} "
        f"key_pattern_matched={key_ok}"
    )
    return 0


def login_admin(client: Client, phone: str, password: str) -> str:
    response = client.post("/api/v1/auth/login", body={"phone": phone, "password": password})
    if response.status != 200 or not (response.json() or {}).get("accessToken"):
        raise E2EFailure(f"admin login HTTP {response.status}")
    return response.json()["accessToken"]


def metric_value(client: Client, token: str, name: str, job: str) -> int | None:
    response = client.get(
        f"/actuator/metrics/{name}",
        token=token,
        params={"tag": f"job:{job}"},
    )
    if response.status == 404:
        return 0
    if response.status != 200:
        return None
    for measurement in (response.json() or {}).get("measurements") or []:
        if measurement.get("statistic") == "COUNT":
            return int(measurement.get("value") or 0)
    return 0


def wait_metrics(client: Client, token: str, name: str, timeout: float = 90.0) -> dict[str, int | None]:
    deadline = time.time() + timeout
    latest = {job: None for job in RETENTION_JOBS}
    while time.time() < deadline:
        latest = {job: metric_value(client, token, name, job) for job in RETENTION_JOBS}
        if all(value is not None and value > 0 for value in latest.values()):
            return latest
        time.sleep(2)
    return latest


def snapshot(db: Database, state: dict[str, Any]) -> dict[str, Any]:
    aged_poll = quote_uuid(state["agedPoll"])
    active_poll = quote_uuid(state["activePoll"])
    question = db.scalar(f"SELECT question FROM polls WHERE id = {aged_poll}")
    return {
        "active_invite": db.count(
            "SELECT count(*) FROM group_invite_links "
            f"WHERE id = {quote_uuid(state['activeInvite'])} AND revoked_at IS NULL"
        ),
        "aged_invite": db.count(
            f"SELECT count(*) FROM group_invite_links WHERE id = {quote_uuid(state['agedInvite'])}"
        ),
        "active_reminder": db.count(
            "SELECT count(*) FROM conversation_reminders "
            f"WHERE id = {quote_uuid(state['activeReminder'])} AND fired_at IS NULL"
        ),
        "repeating_reminder": db.count(
            "SELECT count(*) FROM conversation_reminders "
            f"WHERE id = {quote_uuid(state['repeatingReminder'])}"
        ),
        "fired_reminder": db.count(
            "SELECT count(*) FROM conversation_reminders "
            f"WHERE id = {quote_uuid(state['firedReminder'])}"
        ),
        "active_poll": db.count(f"SELECT count(*) FROM polls WHERE id = {active_poll}"),
        "active_poll_options": db.count(
            f"SELECT count(*) FROM poll_options WHERE poll_id = {active_poll}"
        ),
        "aged_poll": db.count(f"SELECT count(*) FROM polls WHERE id = {aged_poll}"),
        "aged_poll_question": question,
        "aged_poll_options": db.count(
            f"SELECT count(*) FROM poll_options WHERE poll_id = {aged_poll}"
        ),
        "referenced": db.count(
            "SELECT count(*) FROM message_attachments "
            f"WHERE id = {quote_uuid(state['referencedAttachment'])} AND message_id IS NOT NULL"
        ),
        "unbound": db.count(
            "SELECT count(*) FROM message_attachments "
            f"WHERE id = {quote_uuid(state['unboundAttachment'])}"
        ),
    }


def run_observe(client: Client, db: Database, expect: str) -> int:
    state = read_state()
    token = login_admin(client, state["adminPhone"], state["adminPassword"])
    metric_name = (
        "nexachat.retention.rows.observed" if expect == "dry-run"
        else "nexachat.retention.rows.deleted"
    )
    counts = wait_metrics(client, token, metric_name)
    rows = snapshot(db, state)
    print("metrics " + " ".join(f"{job}={counts[job]}" for job in RETENTION_JOBS))
    print(
        "rows "
        f"active_invite={rows['active_invite']} aged_invite={rows['aged_invite']} "
        f"active_reminder={rows['active_reminder']} repeating={rows['repeating_reminder']} "
        f"fired_reminder={rows['fired_reminder']} "
        f"active_poll_options={rows['active_poll_options']} "
        f"aged_poll={rows['aged_poll']} aged_options={rows['aged_poll_options']} "
        f"referenced={rows['referenced']} unbound={rows['unbound']}"
    )
    protected = (
        rows["active_invite"] == 1
        and rows["active_reminder"] == 1
        and rows["repeating_reminder"] == 1
        and rows["active_poll"] == 1
        and rows["active_poll_options"] >= 2
        and rows["referenced"] == 1
    )
    metrics_ok = all(value is not None and value > 0 for value in counts.values())
    if expect == "dry-run":
        unchanged = (
            rows["aged_invite"] == 1
            and rows["fired_reminder"] == 1
            and rows["unbound"] == 1
            and rows["aged_poll_options"] >= 2
            and rows["aged_poll_question"] != "[retained]"
        )
        deleted = {job: metric_value(client, token, "nexachat.retention.rows.deleted", job) for job in RETENTION_JOBS}
        no_deletes = all(value == 0 for value in deleted.values())
        ok = protected and unchanged and metrics_ok and no_deletes
    else:
        removed = (
            rows["aged_invite"] == 0
            and rows["fired_reminder"] == 0
            and rows["unbound"] == 0
            and rows["aged_poll"] == 1
            and rows["aged_poll_question"] == "[retained]"
            and rows["aged_poll_options"] == 0
        )
        ok = protected and removed and metrics_ok
    results = Results()
    results.add(f"retention_{expect}", ok, f"metrics_ok={metrics_ok} protected={protected}")
    return results.finish()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Final-pass NexaChat live scenarios")
    parser.add_argument("--base-url", default=os.environ.get("API_BASE_URL") or "http://127.0.0.1:18082")
    parser.add_argument("--db-container", default="nexachat_e2e_grok2-db-1")
    parser.add_argument("--db-user", default="e2egrok2")
    parser.add_argument("--db-name", default="nexachat_e2e_grok2")
    parser.add_argument("--phase", choices=("http", "retention-seed", "retention-observe"), required=True)
    parser.add_argument("--expect", choices=("dry-run", "deleted"), default="dry-run")
    parser.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args(argv)

    client = Client(args.base_url, timeout=args.timeout)
    database = Database(args.db_container, args.db_user, args.db_name)
    if args.phase == "http":
        return run_http(client, database)
    if args.phase == "retention-seed":
        return scenario_retention_seed(client, database)
    return run_observe(client, database, args.expect)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except E2EFailure as exc:
        print(f"[FAIL] {exc}")
        sys.exit(1)
