#!/usr/bin/env python3
"""Cheap latency sanity for the F1/F2/F4 routes on a real backend.

Creates one bulk corpus (500 prefixed TEXT messages, <= 20 req/s) in the
DIRECT conversation between seed users An and Chi, then samples 50 edits,
50 deletes and 50 searches and reports p50/p95. Edit and delete share the
server's 30/min/user write budget, so the samples are spread over four seed
authors (two per DIRECT conversation); search runs as one caller under the
60/min read budget.

Usage:
    python tools/e2e/perf_e2e.py [--base-url http://localhost:8080]
                                 [--bulk 500] [--samples 50]

The bulk rows are left in place (clearly prefixed `e2e-bulk-<timestamp>`) -
see README.md for the cleanup note.
"""

from __future__ import annotations

import argparse
import math
import time

from e2e_common import (
    USER_A,
    USER_B,
    USER_C,
    Client,
    check,
    direct_conversation,
    history,
    login,
    main_guard,
    run,
    search,
    send_text,
)

USER_D = {"phone": "+84900000004", "name": "Dung"}
RATE_LIMIT_RPS = 20.0


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    index = min(len(ordered) - 1, math.ceil(fraction * len(ordered)) - 1)
    return ordered[max(0, index)]


def report(name: str, samples: list[float]) -> str:
    return (
        f"{name:7s} n={len(samples)} "
        f"p50={percentile(samples, 0.50):.1f}ms "
        f"p95={percentile(samples, 0.95):.1f}ms "
        f"max={max(samples):.1f}ms"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="NexaChat latency sanity")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--bulk", type=int, default=500)
    parser.add_argument("--samples", type=int, default=50)
    args = parser.parse_args()

    client = Client(args.base_url, timeout=30.0)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    prefix = f"e2e-bulk-{stamp}"

    def steps(log) -> None:
        sessions = {}
        for user in (USER_A, USER_B, USER_C, USER_D):
            session = login(client, user["phone"])
            sessions[user["name"]] = (session["accessToken"], session["user"]["id"])

        token_a, me_a = sessions["An"]
        token_b, me_b = sessions["Chi"]
        token_c, me_c = sessions["Binh"]
        token_d, me_d = sessions["Dung"]
        conv_ab = direct_conversation(client, token_a, USER_B["phone"])[1]["id"]
        conv_cd = direct_conversation(client, token_c, USER_D["phone"])[1]["id"]
        log.ok("fixture conversations", f"bulk={conv_ab} samples={conv_cd}")

        # ------------------------------------------------------- bulk corpus
        author_ids: dict[str, list[str]] = {me_a: [], me_b: [], me_c: [], me_d: []}
        started = time.time()
        interval = 1.0 / RATE_LIMIT_RPS
        next_slot = time.perf_counter()

        def paced_send(token: str, author: str, conversation_id: str, content: str) -> None:
            nonlocal next_slot
            row = send_text(client, token, conversation_id, content)
            author_ids[author].append(row["id"])
            next_slot += interval
            delay = next_slot - time.perf_counter()
            if delay > 0:
                time.sleep(delay)

        target = max(60, args.bulk)
        for index in range(target):
            if index % 2 == 0:
                token, author, conversation = token_a, me_a, conv_ab
            else:
                token, author, conversation = token_b, me_b, conv_ab
            content = f"{prefix}-{index:04d}"
            if index % 10 == 0:
                content += " kestrel sample"
            paced_send(token, author, conversation, content)

        # Small fixture for the second pair of sample authors (25 each:
        # 12 edits + 13 deletes).
        for index in range(50):
            if index % 2 == 0:
                token, author = token_c, me_c
            else:
                token, author = token_d, me_d
            paced_send(token, author, conv_cd, f"{prefix}-cd-{index:04d}")

        elapsed = time.time() - started
        sent = target + 50
        log.ok(
            "bulk corpus",
            f"{sent} messages in {elapsed:.1f}s ({sent / elapsed:.1f} req/s)",
        )

        # ------------------------------------------------------ edit samples
        # 13/13/12/12 edits + 12/12/13/13 deletes per author stays under the
        # shared 30/min/user write budget.
        plan = [
            (token_a, conv_ab, me_a, 13, 12),
            (token_b, conv_ab, me_b, 13, 12),
            (token_c, conv_cd, me_c, 12, 13),
            (token_d, conv_cd, me_d, 12, 13),
        ]
        edit_latencies: list[float] = []
        delete_latencies: list[float] = []
        for token, conversation_id, author, edit_count, delete_count in plan:
            ids = author_ids[author]
            checks(ids, edit_count + delete_count, author)
            for message_id in ids[:edit_count]:
                started_at = time.perf_counter()
                response = client.patch(
                    f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
                    token=token,
                    body={"content": f"{prefix}-edited-{message_id[:8]}"},
                )
                edit_latencies.append((time.perf_counter() - started_at) * 1000)
                check(response.status == 200, f"edit sample failed: {response}")
            for message_id in ids[edit_count : edit_count + delete_count]:
                started_at = time.perf_counter()
                response = client.delete(
                    f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
                    token=token,
                )
                delete_latencies.append((time.perf_counter() - started_at) * 1000)
                check(response.status == 204, f"delete sample failed: {response}")
        log.ok("edit samples", report("edit", edit_latencies))
        log.ok("delete samples", report("delete", delete_latencies))

        # ---------------------------------------------------- search samples
        search_latencies: list[float] = []
        cursor = None
        for _ in range(args.samples):
            params = {"limit": 20}
            if cursor:
                params["beforeSeq"] = cursor
            started_at = time.perf_counter()
            response = search(client, token_a, conv_ab, prefix, **params)
            search_latencies.append((time.perf_counter() - started_at) * 1000)
            check(response.status == 200, f"search sample failed: {response}")
            body = response.json()
            cursor = body.get("nextBeforeSeq") if body.get("hasMore") else None
        log.ok("search samples", report("search", search_latencies))

        corpus = history(client, token_a, conv_ab, limit=1)
        log.ok("corpus size", f"conversation latest seq={corpus['items'][0]['seq']}")

    return run("NexaChat latency sanity", steps)


def checks(ids: list[str], needed: int, author: str) -> None:
    check(len(ids) >= needed, f"author {author} has {len(ids)} messages, needs {needed}")


if __name__ == "__main__":
    main_guard(main)
