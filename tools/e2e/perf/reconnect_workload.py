#!/usr/bin/env python3
"""Measure the F6 reconnect workload against an isolated local NexaChat stack.

The script seeds real conversations through the REST API, performs the same
bounded /api/v1/sync pagination used by SyncEngine, and reconnects a raw STOMP
client five times. It writes token-free measurements outside the repository.
It never starts, stops, or changes Docker containers; the caller owns the
isolated `nexachat_l1` Compose stack.
"""

from __future__ import annotations

import argparse
import collections
import json
import math
import os
import re
import subprocess
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

E2E_DIR = Path(__file__).resolve().parents[1]
DEVICE_DIR = E2E_DIR / "device"
ROOT = E2E_DIR.parents[1]
sys.path.insert(0, str(E2E_DIR))
sys.path.insert(0, str(DEVICE_DIR))

from e2e_common import Client, E2EFailure, PASSWORD, check  # noqa: E402
from realtime_probe import FrameReader  # noqa: E402

CONVERSATION_COUNT = 30
SYNC_LIMIT = 50
GAP_MESSAGE_COUNT = 55
RECONNECT_CYCLES = 5
RSS_LIMIT_MIB = 460.0
SYNC_LIMIT_MS = 3000.0

SEED_USERS = {
    "an": "+84900000001",
    "binh": "+84900000002",
    "chi": "+84900000003",
}


def login_local(client: Client, phone: str) -> dict[str, Any]:
    """Log in without touching the shared e2e token cache."""
    for attempt in range(5):
        response = client.post(
            "/api/v1/auth/login",
            body={"phone": phone, "password": PASSWORD},
        )
        if response.status == 429 and attempt < 4:
            details = (response.json() or {}).get("error", {}).get("details") or {}
            time.sleep(min(60, max(2, int(details.get("retryAfterSeconds", 10)) + 1)))
            continue
        check(response.status == 200, f"seed-account login returned HTTP {response.status}")
        data = response.json() or {}
        check(bool(data.get("accessToken")), "seed-account login returned no access token")
        check(data.get("user", {}).get("phone") == phone, "seed-account identity mismatch")
        return data
    raise E2EFailure("seed-account login retry budget exhausted")


def send_text(client: Client, token: str, conversation_id: str, content: str,
              pace: "WritePacer", actor: str) -> tuple[str, dict[str, Any]]:
    pace.before(actor)
    client_message_id = str(uuid.uuid4())
    response = client.post(
        f"/api/v1/conversations/{conversation_id}/messages",
        token=token,
        body={
            "clientMessageId": client_message_id,
            "type": "TEXT",
            "content": content,
            "attachmentIds": [],
        },
    )
    check(response.status in (200, 201), f"message send returned HTTP {response.status}")
    row = response.json() or {}
    check(row.get("clientMessageId") == client_message_id, "message response changed clientMessageId")
    return client_message_id, row


class WritePacer:
    """Keep every author below 25 sends in any rolling ten-second window."""

    def __init__(self) -> None:
        self.sent: dict[str, collections.deque[float]] = collections.defaultdict(collections.deque)

    def before(self, actor: str) -> None:
        times = self.sent[actor]
        while times and time.monotonic() - times[0] >= 10.0:
            times.popleft()
        if len(times) >= 25:
            time.sleep(max(0.01, 10.05 - (time.monotonic() - times[0])))
            self.before(actor)
            return
        times.append(time.monotonic())


def parse_bytes(value: str) -> int:
    match = re.match(r"\s*([0-9.]+)\s*([A-Za-z]+)\s*$", value)
    if not match:
        raise ValueError(f"unrecognized Docker byte quantity: {value!r}")
    amount = float(match.group(1))
    unit = match.group(2).lower()
    factors = {
        "b": 1,
        "kb": 1000,
        "kib": 1024,
        "mb": 1000**2,
        "mib": 1024**2,
        "gb": 1000**3,
        "gib": 1024**3,
    }
    if unit not in factors:
        raise ValueError(f"unrecognized Docker byte unit: {unit}")
    return int(amount * factors[unit])


class RuntimeSampler:
    """Sample Docker stats and cgroup v2 memory while the workload runs."""

    def __init__(self, container: str) -> None:
        self.container = container
        self.samples: list[dict[str, Any]] = []
        self.errors: list[str] = []
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def _sample(self) -> None:
        sample: dict[str, Any] = {"elapsed_s": round(time.monotonic() - self.started, 3)}
        try:
            stats = subprocess.run(
                ["docker", "stats", "--no-stream", "--format", "{{.MemUsage}}", self.container],
                check=True,
                capture_output=True,
                text=True,
                timeout=15,
            ).stdout.strip()
            used, _, limit = stats.partition("/")
            sample["docker_stats_raw"] = stats
            sample["docker_stats_used_bytes"] = parse_bytes(used)
            sample["docker_stats_limit_bytes"] = parse_bytes(limit)
        except Exception as error:  # preserve a failing sampler as evidence
            self.errors.append(f"docker_stats:{type(error).__name__}")
        try:
            current = subprocess.run(
                ["docker", "exec", self.container, "cat", "/sys/fs/cgroup/memory.current"],
                check=True,
                capture_output=True,
                text=True,
                timeout=15,
            ).stdout.strip()
            sample["cgroup_current_bytes"] = int(current)
        except Exception as error:
            self.errors.append(f"cgroup_current:{type(error).__name__}")
        self.samples.append(sample)

    def start(self) -> None:
        self.started = time.monotonic()
        self._sample()

        def loop() -> None:
            while not self._stop.wait(1.0):
                self._sample()

        self._thread = threading.Thread(target=loop, name="l1-memory-sampler", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=20)
        self._sample()

    def summary(self) -> dict[str, Any]:
        docker_values = [s["docker_stats_used_bytes"] for s in self.samples
                         if "docker_stats_used_bytes" in s]
        cgroup_values = [s["cgroup_current_bytes"] for s in self.samples
                         if "cgroup_current_bytes" in s]
        peak = None
        try:
            peak_text = subprocess.run(
                ["docker", "exec", self.container, "cat", "/sys/fs/cgroup/memory.peak"],
                check=True,
                capture_output=True,
                text=True,
                timeout=15,
            ).stdout.strip()
            peak = int(peak_text)
        except Exception as error:
            self.errors.append(f"cgroup_peak:{type(error).__name__}")
        return {
            "sample_count": len(self.samples),
            "docker_stats_peak_bytes": max(docker_values) if docker_values else None,
            "docker_stats_peak_mib": round(max(docker_values) / (1024**2), 2) if docker_values else None,
            "cgroup_sample_peak_bytes": max(cgroup_values) if cgroup_values else None,
            "cgroup_sample_peak_mib": round(max(cgroup_values) / (1024**2), 2) if cgroup_values else None,
            "cgroup_lifetime_peak_bytes": peak,
            "cgroup_lifetime_peak_mib": round(peak / (1024**2), 2) if peak is not None else None,
            "samples": self.samples,
            "sampling_errors": sorted(set(self.errors)),
        }


def render_java_opts(path: Path) -> str:
    lines = path.read_text(encoding="utf-8").splitlines()
    for index, line in enumerate(lines):
        if line.strip() != "- key: JAVA_OPTS":
            continue
        for following in lines[index + 1:]:
            if following.strip().startswith("value:"):
                if ">-" not in following:
                    raise RuntimeError("render.yaml JAVA_OPTS is not a folded block scalar")
                block: list[str] = []
                for candidate in lines[lines.index(following, index + 1) + 1:]:
                    if candidate.strip() and len(candidate) - len(candidate.lstrip()) < 10:
                        break
                    if candidate.strip():
                        block.append(candidate.strip())
                return " ".join(block)
            if following.strip().startswith("- key:"):
                break
    raise RuntimeError("render.yaml JAVA_OPTS entry was not found")


def inspect_runtime(app_container: str, expected_java_opts: str) -> dict[str, Any]:
    result = subprocess.run(
        ["docker", "inspect", app_container], check=True, capture_output=True, text=True, timeout=15
    )
    container = json.loads(result.stdout)[0]
    env = container.get("Config", {}).get("Env", [])
    actual_java_opts = next((item.removeprefix("JAVA_OPTS=") for item in env
                             if item.startswith("JAVA_OPTS=")), "")
    state = container.get("State", {})
    return {
        "container_id": container.get("Id"),
        "image_ref": container.get("Config", {}).get("Image"),
        "image_id": container.get("Image"),
        "java_opts_match_render_yaml": actual_java_opts == expected_java_opts,
        "restart_count": container.get("RestartCount"),
        "running": state.get("Running"),
        "oom_killed": state.get("OOMKilled"),
        "exit_code": state.get("ExitCode"),
    }


def call_sync(client: Client, token: str, watermarks: dict[str, dict[str, Any]],
              since: str | None, latencies: list[float], expected_ids: set[str] | None = None
              ) -> tuple[dict[str, dict[str, Any]], str | None, list[dict[str, Any]], int]:
    pending = [dict(value) for value in watermarks.values()]
    current = {value["conversationId"]: dict(value) for value in pending}
    all_messages: list[dict[str, Any]] = []
    pages = 0
    last_server_time = since
    while pending:
        check(pages < 20, "sync exceeded Android's 20-page bound")
        request_body: dict[str, Any] = {"watermarks": pending, "limitPerConv": SYNC_LIMIT}
        if since is not None:
            request_body["since"] = since
        started = time.perf_counter()
        response = client.post("/api/v1/sync", token=token, body=request_body)
        latencies.append((time.perf_counter() - started) * 1000)
        check(response.status == 200, f"POST /api/v1/sync returned HTTP {response.status}")
        body = response.json() or {}
        pages += 1
        if body.get("serverTime"):
            last_server_time = body["serverTime"]
        next_pending: list[dict[str, Any]] = []
        for delta in body.get("deltas", []):
            conversation_id = delta.get("conversationId")
            if not conversation_id or conversation_id not in current:
                continue
            before = current[conversation_id]
            requested = pending_watermark(pending, conversation_id)
            requested_seq = int(requested["lastSeq"])
            requested_cursor = requested.get("reactionCursor")
            messages = delta.get("newMessages", []) or []
            all_messages.extend(messages)
            for message in messages:
                if message.get("seq") is not None:
                    before["lastSeq"] = max(int(before["lastSeq"]), int(message["seq"]))
            if delta.get("hasMore"):
                next_seq = int(before["lastSeq"])
                if not messages:
                    next_seq = max(next_seq, int(delta.get("maxSeq") or 0))
                cursor = delta.get("reactionCursor")
                next_watermark = {
                    "conversationId": conversation_id,
                    "lastSeq": next_seq,
                }
                if cursor is not None:
                    next_watermark["reactionCursor"] = cursor
                    before["reactionCursor"] = cursor
                elif before.get("reactionCursor") is not None:
                    next_watermark["reactionCursor"] = before["reactionCursor"]
                if (next_seq == requested_seq
                        and next_watermark.get("reactionCursor") == requested_cursor):
                    raise E2EFailure("sync page reported hasMore without advancing its cursor")
                next_pending.append(next_watermark)
        pending = next_pending
    if expected_ids is not None:
        received = {str(item.get("clientMessageId")) for item in all_messages}
        check(expected_ids.issubset(received), "sync pages did not return all expected gap messages")
    return current, last_server_time, all_messages, pages


def pending_watermark(items: list[dict[str, Any]], conversation_id: str) -> dict[str, Any]:
    return next(item for item in items if item["conversationId"] == conversation_id)


def stomp_send(reader: FrameReader, conversation_id: str, client_message_id: str,
               content: str, timeout: float = 15.0) -> dict[str, Any]:
    body = json.dumps({
        "clientMessageId": client_message_id,
        "type": "TEXT",
        "content": content,
        "attachmentIds": [],
    }, separators=(",", ":"))
    reader._frame("SEND", {
        "destination": f"/app/conversations/{conversation_id}/messages",
        "content-type": "application/json",
        "content-length": str(len(body.encode("utf-8"))),
    }, body)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for event in reader.events(min(1.0, deadline - time.monotonic())):
            payload = event.get("payload") or {}
            if (event.get("eventType") == "MESSAGE_CREATED"
                    and payload.get("clientMessageId") == client_message_id):
                return event
            if event.get("code"):
                raise E2EFailure("STOMP message was rejected by the server")
    raise E2EFailure("timed out waiting for STOMP MESSAGE_CREATED")


def upload_png(client: Client, token: str) -> tuple[int, float]:
    # A real 1x1 PNG fixture; bytes are uploaded through the production REST path.
    png = bytes.fromhex(
        "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
        "0000000b49444154789c636000020000050001a5f645400000000049454e44ae426082"
    )
    boundary = "----nexachat-l1-" + uuid.uuid4().hex
    body = (
        f"--{boundary}\r\n"
        'Content-Disposition: form-data; name="file"; filename="l1.png"\r\n'
        "Content-Type: image/png\r\n\r\n"
    ).encode("ascii") + png + f"\r\n--{boundary}--\r\n".encode("ascii")
    started = time.perf_counter()
    response = client.request(
        "POST", "/api/v1/attachments", token=token,
        raw_body=(body, f"multipart/form-data; boundary={boundary}"),
    )
    elapsed = (time.perf_counter() - started) * 1000
    return response.status, elapsed


def db_counts(container: str, db_name: str, client_ids: set[str]) -> dict[str, int]:
    if not client_ids:
        return {}
    values = ",".join(f"'{uuid.UUID(item)}'" for item in sorted(client_ids))
    sql = (
        "SELECT client_message_id::text, count(*)::text FROM messages "
        f"WHERE client_message_id = ANY(ARRAY[{values}]::uuid[]) "
        "GROUP BY client_message_id ORDER BY client_message_id;"
    )
    result = subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-U", "nexachat", "-d", db_name,
         "-tA", "-F", "|", "-v", "ON_ERROR_STOP=1", "-c", sql],
        check=True, capture_output=True, text=True, timeout=30,
    )
    counts: dict[str, int] = {}
    for line in result.stdout.splitlines():
        if "|" in line:
            client_id, count = line.strip().split("|", 1)
            counts[client_id] = int(count)
    return counts


def filtered_log_excerpt(container: str, output_path: Path) -> list[str]:
    result = subprocess.run(
        ["docker", "logs", "--timestamps", container],
        capture_output=True, text=True, timeout=30, check=False,
    )
    patterns = re.compile(
        r"OutOfMemory|OOMKilled|HikariPool.*(?:timeout|Timeout)|connection acquisition.*timeout|"
        r"Started NexaChatApplication|Connection is not available"
    )
    excerpt = [line for line in (result.stdout + result.stderr).splitlines() if patterns.search(line)]
    output_path.write_text("\n".join(excerpt) + ("\n" if excerpt else ""), encoding="utf-8")
    return excerpt


def nearest_rank_p95(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(0.95 * len(ordered)) - 1)]


def outside_repository(path: Path) -> bool:
    try:
        path.resolve().relative_to(ROOT.resolve())
        return False
    except ValueError:
        return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18093")
    parser.add_argument("--app-container", default="nexachat_l1-app-1")
    parser.add_argument("--db-container", default="nexachat_l1-db-1")
    parser.add_argument("--db-name", default="nexachat_l1")
    parser.add_argument("--evidence-dir", required=True)
    args = parser.parse_args()

    evidence_dir = Path(args.evidence_dir).expanduser().resolve()
    check(outside_repository(evidence_dir), "evidence-dir must stay outside the repository")
    evidence_dir.mkdir(parents=True, exist_ok=True)
    run_id = uuid.uuid4().hex[:10]
    prefix = f"l1load-{run_id}"
    started_at = datetime.now(timezone.utc).isoformat()
    evidence_path = evidence_dir / f"workload-{run_id}.json"
    log_path = evidence_dir / f"workload-{run_id}-log-excerpt.txt"
    run: dict[str, Any] = {
        "run_id": run_id,
        "started_at_utc": started_at,
        "base_url": args.base_url,
        "container_names": {"app": args.app_container, "db": args.db_container},
        "thresholds": {
            "oom_or_restart": "none",
            "hikari_acquisition_timeouts": 0,
            "message_rows_per_client_message_id": 1,
            "sync_p95_ms_exclusive": SYNC_LIMIT_MS,
            "rss_mib_exclusive_from_docker_stats_and_cgroup": RSS_LIMIT_MIB,
        },
        "delayed_storage": "NOT_RUN: local filesystem storage has no supported delay control",
        "sync_latencies_ms": [],
        "sent_client_message_ids": [],
    }
    sampler: RuntimeSampler | None = None
    reader: FrameReader | None = None
    all_ids: set[str] = set()
    latencies: list[float] = []
    try:
        runtime = inspect_runtime(args.app_container, render_java_opts(ROOT / "render.yaml"))
        run["runtime_before"] = runtime
        check(runtime["java_opts_match_render_yaml"], "running app JAVA_OPTS differ from render.yaml")
        health = Client(args.base_url, timeout=15).get("/actuator/health/liveness")
        check(health.status == 200, f"local app liveness returned HTTP {health.status}")

        client = Client(args.base_url, timeout=60)
        user_a = login_local(client, SEED_USERS["an"])
        user_b = login_local(client, SEED_USERS["binh"])
        user_c = login_local(client, SEED_USERS["chi"])
        token_a, token_b, token_c = (
            user_a["accessToken"], user_b["accessToken"], user_c["accessToken"]
        )
        user_ids = (user_b["user"]["id"], user_c["user"]["id"])
        ws_url = args.base_url.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
        watermarks: dict[str, dict[str, Any]] = {}
        conversations: list[str] = []
        client_message_ids: set[str] = set()
        all_ids = client_message_ids
        pacer = WritePacer()
        sampler = RuntimeSampler(args.app_container)
        sampler.start()

        for index in range(CONVERSATION_COUNT):
            response = client.post(
                "/api/v1/conversations/group", token=token_a,
                body={"title": f"{prefix}-conversation-{index:02d}", "memberIds": list(user_ids)},
            )
            check(response.status == 201, f"group fixture {index} returned HTTP {response.status}")
            conversation_id = str((response.json() or {}).get("id", ""))
            check(bool(conversation_id), f"group fixture {index} returned no conversation id")
            conversations.append(conversation_id)
            author_token, actor = (token_b, "binh") if index % 2 == 0 else (token_c, "chi")
            message_id, _ = send_text(
                client, author_token, conversation_id,
                f"{prefix}-baseline-{index:02d}", pacer, actor,
            )
            client_message_ids.add(message_id)

        check(len(conversations) == CONVERSATION_COUNT, "did not create 30 fixture conversations")
        initial = {item: {"conversationId": item, "lastSeq": 0} for item in conversations}
        watermarks, since, initial_messages, initial_pages = call_sync(
            client, token_a, initial, None, latencies,
        )
        check(len({item.get("conversationId") for item in initial_messages}) == CONVERSATION_COUNT,
              "initial sync did not cache all 30 conversations")
        run["cached_conversations"] = CONVERSATION_COUNT
        run["initial_sync"] = {
            "messages_received": len(initial_messages),
            "pages": initial_pages,
            "watermarks": len(watermarks),
            "server_time": since,
        }

        gap_ids: set[str] = set()
        for index in range(GAP_MESSAGE_COUNT):
            author_token, actor = (token_b, "binh") if index % 2 == 0 else (token_c, "chi")
            message_id, _ = send_text(
                client, author_token, conversations[0],
                f"{prefix}-gap-{index:02d}", pacer, actor,
            )
            client_message_ids.add(message_id)
            gap_ids.add(message_id)
        watermarks, since, gap_messages, gap_pages = call_sync(
            client, token_a, watermarks, since, latencies, expected_ids=gap_ids,
        )
        check(gap_pages > 1, "55-message gap did not cross a sync page boundary")
        run["paged_gap"] = {
            "messages_sent": GAP_MESSAGE_COUNT,
            "messages_received": len(gap_messages),
            "pages": gap_pages,
            "first_conversation_id": conversations[0],
        }

        reader = FrameReader(ws_url, token_a)
        reader.subscribe("/user/queue/inbox")
        reader.subscribe(f"/topic/conversations.{conversations[0]}")
        reconnect_incoming: list[str] = []
        reconnect_outbox: list[str] = []
        for cycle in range(RECONNECT_CYCLES):
            reader.close()
            reader = None
            incoming_id, _ = send_text(
                client, token_b, conversations[0],
                f"{prefix}-missed-{cycle + 1}", pacer, "binh",
            )
            client_message_ids.add(incoming_id)
            replacement = FrameReader(ws_url, token_a)
            replacement.subscribe("/user/queue/inbox")
            replacement.subscribe(f"/topic/conversations.{conversations[0]}")
            watermarks, since, recovered, page_count = call_sync(
                client, token_a, watermarks, since, latencies, expected_ids={incoming_id},
            )
            check(any(str(item.get("clientMessageId")) == incoming_id for item in recovered),
                  f"reconnect {cycle + 1} sync omitted its offline gap")
            outgoing_id = str(uuid.uuid4())
            event = stomp_send(
                replacement, conversations[0], outgoing_id,
                f"{prefix}-outbox-{cycle + 1}",
            )
            payload = event.get("payload") or {}
            if payload.get("seq") is not None:
                watermarks[conversations[0]]["lastSeq"] = max(
                    int(watermarks[conversations[0]]["lastSeq"]), int(payload["seq"])
                )
            if cycle == 2:
                # Simulate a lost acknowledgement retry with the same durable key.
                body = json.dumps({
                    "clientMessageId": outgoing_id,
                    "type": "TEXT",
                    "content": f"{prefix}-outbox-{cycle + 1}",
                    "attachmentIds": [],
                }, separators=(",", ":"))
                replacement._frame("SEND", {
                    "destination": f"/app/conversations/{conversations[0]}/messages",
                    "content-type": "application/json",
                    "content-length": str(len(body.encode("utf-8"))),
                }, body)
            client_message_ids.add(outgoing_id)
            reconnect_incoming.append(incoming_id)
            reconnect_outbox.append(outgoing_id)
            reader = replacement
            run.setdefault("reconnect_cycles", []).append({
                "cycle": cycle + 1,
                "restored": True,
                "sync_pages": page_count,
                "incoming_message_recovered": True,
                "outbox_message_acknowledged": True,
                "duplicate_same_id_retry": cycle == 2,
            })

        reader.close()
        reader = None

        barrier = threading.Barrier(2)
        concurrent_id = str(uuid.uuid4())

        def concurrent_send() -> dict[str, Any]:
            barrier.wait(timeout=10)
            started = time.perf_counter()
            response = client.post(
                f"/api/v1/conversations/{conversations[1]}/messages", token=token_b,
                body={
                    "clientMessageId": concurrent_id,
                    "type": "TEXT",
                    "content": f"{prefix}-parallel-send",
                    "attachmentIds": [],
                },
            )
            elapsed = (time.perf_counter() - started) * 1000
            check(response.status in (200, 201), f"concurrent send returned HTTP {response.status}")
            check((response.json() or {}).get("clientMessageId") == concurrent_id,
                  "concurrent send changed clientMessageId")
            return {"http_status": response.status, "duration_ms": round(elapsed, 2)}

        def concurrent_upload() -> dict[str, Any]:
            barrier.wait(timeout=10)
            status, elapsed = upload_png(client, token_c)
            check(status in (200, 201), f"concurrent upload returned HTTP {status}")
            return {"http_status": status, "duration_ms": round(elapsed, 2)}

        with ThreadPoolExecutor(max_workers=2, thread_name_prefix="l1-concurrent") as pool:
            send_future = pool.submit(concurrent_send)
            upload_future = pool.submit(concurrent_upload)
            concurrent_results = {"send": send_future.result(), "upload": upload_future.result()}
        client_message_ids.add(concurrent_id)
        run["concurrent_send_and_upload"] = concurrent_results

        sampler.stop()
        run["memory"] = sampler.summary()
        runtime_after = inspect_runtime(args.app_container, render_java_opts(ROOT / "render.yaml"))
        run["runtime_after"] = runtime_after
        log_lines = filtered_log_excerpt(args.app_container, log_path)
        run["log_excerpt_path"] = log_path.name
        run["hikari_timeout_matches"] = sum(
            1 for line in log_lines
            if re.search(r"HikariPool.*(?:timeout|Timeout)|Connection is not available", line)
        )
        run["sync_latencies_ms"] = [round(value, 2) for value in latencies]
        run["sync_sample_count"] = len(latencies)
        run["sync_p95_ms"] = round(nearest_rank_p95(latencies) or 0.0, 2)
        run["sent_client_message_ids"] = sorted(client_message_ids)
        counts = db_counts(args.db_container, args.db_name, client_message_ids)
        run["database_client_message_counts"] = counts
        run["exactly_once"] = (
            len(counts) == len(client_message_ids)
            and all(counts.get(item) == 1 for item in client_message_ids)
        )

        memory = run["memory"]
        docker_peak = memory.get("docker_stats_peak_mib")
        cgroup_peak = memory.get("cgroup_lifetime_peak_mib")
        if cgroup_peak is None:
            cgroup_peak = memory.get("cgroup_sample_peak_mib")
        checks = {
            "render_java_opts_applied": runtime_after["java_opts_match_render_yaml"],
            "container_running": runtime_after["running"] is True,
            "no_oom_or_restart": runtime_after["oom_killed"] is False and runtime_after["restart_count"] == 0,
            "no_hikari_acquisition_timeout": run["hikari_timeout_matches"] == 0,
            "every_client_message_id_exactly_once": run["exactly_once"],
            "sync_p95_under_3_seconds": run["sync_p95_ms"] < SYNC_LIMIT_MS,
            "docker_stats_rss_under_460_mib": docker_peak is not None and docker_peak < RSS_LIMIT_MIB,
            "cgroup_rss_under_460_mib": cgroup_peak is not None and cgroup_peak < RSS_LIMIT_MIB,
            "all_five_reconnect_cycles": len(run.get("reconnect_cycles", [])) == RECONNECT_CYCLES,
        }
        run["checks"] = {name: "PASS" if passed else "FAIL" for name, passed in checks.items()}
        run["status"] = "PASS" if all(checks.values()) else "FAIL"
        run["completed_at_utc"] = datetime.now(timezone.utc).isoformat()
        evidence_path.write_text(json.dumps(run, indent=2) + "\n", encoding="utf-8")
        for name, passed in checks.items():
            print(f"[{('PASS' if passed else 'FAIL')}] {name}")
        print(f"sync_requests={len(latencies)} p95_ms={run['sync_p95_ms']}")
        print(f"docker_stats_peak_mib={docker_peak} cgroup_peak_mib={cgroup_peak}")
        print(f"client_message_ids={len(client_message_ids)} exact_once={run['exactly_once']}")
        print(f"evidence={evidence_path}")
        return 0 if all(checks.values()) else 1
    except Exception as error:
        run["status"] = "FAIL"
        run["failure"] = f"{type(error).__name__}: {error}"
        run["sync_latencies_ms"] = [round(value, 2) for value in latencies]
        run["sent_client_message_ids"] = sorted(all_ids)
        if sampler is not None:
            try:
                sampler.stop()
                run["memory"] = sampler.summary()
            except Exception:
                pass
        try:
            log_lines = filtered_log_excerpt(args.app_container, log_path)
            run["hikari_timeout_matches"] = sum(
                1 for line in log_lines
                if re.search(r"HikariPool.*(?:timeout|Timeout)|Connection is not available", line)
            )
            run["log_excerpt_path"] = log_path.name
        except Exception:
            pass
        run["completed_at_utc"] = datetime.now(timezone.utc).isoformat()
        evidence_path.write_text(json.dumps(run, indent=2) + "\n", encoding="utf-8")
        print(f"[FAIL] {type(error).__name__}: {error}")
        print(f"evidence={evidence_path}")
        return 1
    finally:
        if reader is not None:
            reader.close()


if __name__ == "__main__":
    sys.exit(main())
