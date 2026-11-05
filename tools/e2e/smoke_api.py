#!/usr/bin/env python3
"""NexaChat API smoke test - real HTTP against a running backend.

Two seed users drive the edit/delete/search/settings contract end to end and
the shared DIRECT conversation they create is the only data written. Every
check is fail-fast (exit 1 on the first broken expectation) and the run is
repeatable: messages are prefixed with `e2e-<timestamp>` and soft-deleted at
the end.

Usage:
    python tools/e2e/smoke_api.py [--base-url http://localhost:8080]

Requires only Python 3.9+ (no third-party packages, no jq).
"""

from __future__ import annotations

import argparse
import time
import uuid

from e2e_common import (
    USER_A,
    USER_B,
    USER_C,
    Client,
    E2EFailure,
    check,
    direct_conversation,
    history,
    login,
    main_guard,
    run,
    search,
    send_text,
)


def main() -> int:
    parser = argparse.ArgumentParser(description="NexaChat API smoke test")
    parser.add_argument("--base-url", default="http://localhost:8080")
    args = parser.parse_args()

    client = Client(args.base_url)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    prefix = f"e2e-{stamp}"
    created: list[str] = []
    state: dict = {}

    def steps(log) -> None:
        # ---------------------------------------------------------- health
        health = client.get("/actuator/health")
        check(health.status == 200, f"health expected 200, got {health}")
        check(health.json().get("status") == "UP", f"health not UP: {health}")
        log.ok("health UP", "GET /actuator/health")

        # ----------------------------------------------------------- auth
        a = login(client, USER_A["phone"])
        b = login(client, USER_B["phone"])
        c = login(client, USER_C["phone"])
        token_a, id_a = a["accessToken"], a["user"]["id"]
        token_b, id_b = b["accessToken"], b["user"]["id"]
        token_c = c["accessToken"]
        me = client.get("/api/v1/users/me", token=token_a)
        check(me.status == 200 and me.json()["id"] == id_a, "GET /users/me mismatch")
        log.ok("login A/B + non-member C", f"A={USER_A['name']} B={USER_B['name']} C={USER_C['name']}")

        # ------------------------------------------------- direct conversation
        status_first, conversation = direct_conversation(
            client, token_a, USER_B["phone"]
        )
        status_second, again = direct_conversation(client, token_a, USER_B["phone"])
        check(
            status_second == 200,
            f"second direct create should be 200 (idempotent), got {status_second}",
        )
        check(
            conversation["id"] == again["id"],
            "direct create is not idempotent (different ids)",
        )
        conversation_id = conversation["id"]
        state["conversation_id"] = conversation_id
        state["token"] = token_a
        member_ids = {member["userId"] for member in conversation["members"]}
        check(
            member_ids == {id_a, id_b},
            f"direct members {member_ids} != {{{id_a}, {id_b}}}",
        )
        inbox = client.get("/api/v1/conversations", token=token_a)
        check(inbox.status == 200, f"inbox expected 200, got {inbox}")
        check(
            any(entry["id"] == conversation_id for entry in inbox.json()),
            "created conversation missing from A's inbox",
        )
        log.ok(
            "direct conversation create/get",
            f"{status_first} then {status_second}, id={conversation_id}",
        )

        # ---------------------------------------------------------- send
        message = send_text(
            client, token_a, conversation_id, f"{prefix} alpha kestrel marker"
        )
        message_id, seq = message["id"], message["seq"]
        created.append(message_id)
        check(message["senderId"] == id_a, "sent message sender mismatch")
        check(message["content"] == f"{prefix} alpha kestrel marker", "sent content mismatch")
        log.ok("A sends TEXT", f"id={message_id} seq={seq}")

        page = history(client, token_b, conversation_id, limit=50)
        found = [item for item in page["items"] if item["id"] == message_id]
        check(len(found) == 1, "B's history does not contain A's message")
        check(found[0]["content"] == message["content"], "B sees different content")
        log.ok("B reads history", f"items={len(page['items'])}")

        # Search fixtures: literal % and _ must stay literal.
        percent = send_text(
            client, token_a, conversation_id, f"{prefix} literal 100% done"
        )
        underscore = send_text(
            client, token_a, conversation_id, f"{prefix} literal a_b token"
        )
        undecorated = send_text(
            client, token_a, conversation_id, f"{prefix} literal axb token"
        )
        for row in (percent, underscore, undecorated):
            created.append(row["id"])

        # ---------------------------------------------------------- edit
        edited = client.patch(
            f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
            token=token_a,
            body={"content": f"{prefix} alpha kestrel edited"},
        )
        check(edited.status == 200, f"edit expected 200, got {edited}")
        edited_row = edited.json()
        check(edited_row["content"] == f"{prefix} alpha kestrel edited", "edit content mismatch")
        check(edited_row.get("editedAt"), "edit response has no editedAt")
        check((edited_row["id"], edited_row["seq"]) == (message_id, seq), "edit changed id/seq")
        edited_at = edited_row["editedAt"]
        log.ok("A edits own message", f"editedAt={edited_at}")

        # Same content again: 200 no-op, editedAt unchanged.
        same = client.patch(
            f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
            token=token_a,
            body={"content": f"{prefix} alpha kestrel edited"},
        )
        check(same.status == 200, f"identical edit expected 200, got {same}")
        check(
            same.json().get("editedAt") == edited_at,
            f"identical edit changed editedAt: {same.json().get('editedAt')} != {edited_at}",
        )
        log.ok("identical edit is a no-op", "editedAt unchanged")

        # ------------------------------------------------- error paths
        foreign = client.patch(
            f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
            token=token_b,
            body={"content": f"{prefix} hijack"},
        )
        check(foreign.status == 403, f"B edit expected 403, got {foreign}")
        check(
            foreign.error_code() == "NOT_MESSAGE_AUTHOR",
            f"B edit code {foreign.error_code()} != NOT_MESSAGE_AUTHOR",
        )
        log.ok("B cannot edit A's message", "403 NOT_MESSAGE_AUTHOR")

        blank = client.patch(
            f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
            token=token_a,
            body={"content": "   "},
        )
        check(blank.status == 400, f"blank edit expected 400, got {blank}")
        check(
            blank.error_code() == "VALIDATION_FAILED",
            f"blank edit code {blank.error_code()} != VALIDATION_FAILED",
        )
        log.ok("blank edit rejected", "400 VALIDATION_FAILED")

        # -------------------------------------------------------- search
        hit_query = f"{prefix} alpha kestrel"
        hit = search(client, token_a, conversation_id, hit_query)
        check(hit.status == 200, f"search expected 200, got {hit}")
        hit_items = hit.json()["items"]
        check(len(hit_items) == 1, f"search hit returned {len(hit_items)} items")
        check(hit_items[0]["id"] == message_id, "search hit is not the edited message")
        check(
            hit_items[0]["content"] == f"{prefix} alpha kestrel edited",
            "search hit carries stale content",
        )
        check(
            hit_items[0]["conversationId"] == conversation_id,
            "search leaked another conversation",
        )
        log.ok("search hit", f"unique marker -> 1 item, seq={hit_items[0]['seq']}")

        miss = search(client, token_a, conversation_id, f"zzz-no-{stamp}")
        check(miss.status == 200, f"search miss expected 200, got {miss}")
        check(miss.json()["items"] == [], "search miss returned items")
        check(miss.json()["hasMore"] is False, "search miss hasMore != false")
        log.ok("search miss", "empty items, hasMore=false")

        short = search(client, token_a, conversation_id, "k")
        check(short.status == 400, f"1-char search expected 400, got {short}")
        check(
            short.error_code() == "VALIDATION_FAILED",
            f"1-char search code {short.error_code()}",
        )
        log.ok("search floor", "1 char -> 400 VALIDATION_FAILED")

        literal_percent = search(client, token_a, conversation_id, "0%")
        check(literal_percent.status == 200, f"'0%' search failed: {literal_percent}")
        percent_items = literal_percent.json()["items"]
        check(
            len(percent_items) == 1 and percent_items[0]["id"] == percent["id"],
            f"'0%' matched {[i['id'] for i in percent_items]} (expected literal only)",
        )
        log.ok("search treats % literally", "1 item containing '100%'")

        literal_underscore = search(client, token_a, conversation_id, "a_b")
        check(literal_underscore.status == 200, f"'a_b' search failed: {literal_underscore}")
        underscore_items = literal_underscore.json()["items"]
        check(
            len(underscore_items) == 1 and underscore_items[0]["id"] == underscore["id"],
            f"'a_b' matched {[i['id'] for i in underscore_items]} (expected literal 'a_b' only)",
        )
        log.ok("search treats _ literally", "'a_b' does not match 'axb'")

        page_one = search(client, token_a, conversation_id, prefix, limit=2)
        check(page_one.status == 200, f"paged search failed: {page_one}")
        body_one = page_one.json()
        check(body_one["hasMore"] is True, "paged search hasMore != true")
        check(len(body_one["items"]) == 2, "paged search ignored limit=2")
        cursor = body_one["nextBeforeSeq"]
        check(cursor is not None, "paged search returned no nextBeforeSeq")
        page_two = search(client, token_a, conversation_id, prefix, limit=2, beforeSeq=cursor)
        check(page_two.status == 200, f"second search page failed: {page_two}")
        overlap = {
            item["id"] for item in body_one["items"]
        } & {item["id"] for item in page_two.json()["items"]}
        check(overlap == set(), f"search pages overlap: {overlap}")
        check(
            all(item["seq"] < cursor for item in page_two.json()["items"]),
            "search page 2 returned seq >= beforeSeq",
        )
        log.ok("search pagination", f"limit=2 cursor={cursor}, no overlap")

        outsider = search(client, token_c, conversation_id, hit_query)
        check(outsider.status == 403, f"non-member search expected 403, got {outsider}")
        check(
            outsider.error_code() == "NOT_MEMBER",
            f"non-member search code {outsider.error_code()} != NOT_MEMBER",
        )
        log.ok("non-member search denied", "403 NOT_MEMBER")

        # --------------------------------------------------- delete + tombstone
        deleted = client.delete(
            f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
            token=token_a,
        )
        check(deleted.status == 204, f"delete expected 204, got {deleted}")
        check(deleted.body == b"", "delete 204 returned a body")
        again_delete = client.delete(
            f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
            token=token_a,
        )
        check(again_delete.status == 204, f"second delete expected 204, got {again_delete}")
        edit_deleted = client.patch(
            f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
            token=token_a,
            body={"content": f"{prefix} resurrect"},
        )
        check(edit_deleted.status == 409, f"edit deleted expected 409, got {edit_deleted}")
        check(
            edit_deleted.error_code() == "MESSAGE_DELETED",
            f"edit deleted code {edit_deleted.error_code()} != MESSAGE_DELETED",
        )
        log.ok("delete is idempotent, tombstone not editable", "204, 204, 409 MESSAGE_DELETED")

        tomb_page = history(client, token_b, conversation_id, limit=50)
        tomb = [item for item in tomb_page["items"] if item["id"] == message_id]
        check(len(tomb) == 1, "tombstone missing from history")
        tomb = tomb[0]
        check("content" in tomb and tomb["content"] is None, "tombstone content is not JSON null")
        check(tomb.get("deletedAt"), "tombstone has no deletedAt")
        check(tomb["attachments"] == [], f"tombstone attachments {tomb['attachments']}")
        check(tomb["reactions"] == [], f"tombstone reactions {tomb['reactions']}")
        check(tomb["seq"] == seq, "tombstone lost its seq")
        log.ok("history tombstone shape", "content=null, attachments=[], reactions=[], seq kept")

        after_delete = search(client, token_a, conversation_id, hit_query)
        check(
            after_delete.status == 200 and after_delete.json()["items"] == [],
            "search returned a deleted message",
        )
        log.ok("search excludes tombstones", "unique marker -> 0 items")

        # ------------------------------------------------------- settings
        settings = client.get("/api/v1/users/me/settings", token=token_a)
        check(settings.status == 200, f"settings GET expected 200, got {settings}")
        check(settings.json().get("showPresence") is True, "seed showPresence is not true")
        hidden = client.patch(
            "/api/v1/users/me/settings", token=token_a, body={"showPresence": False}
        )
        check(hidden.status == 200 and hidden.json()["showPresence"] is False, "hide failed")
        reread = client.get("/api/v1/users/me/settings", token=token_a)
        check(reread.json()["showPresence"] is False, "settings did not persist hide")
        restored = client.patch(
            "/api/v1/users/me/settings", token=token_a, body={"showPresence": True}
        )
        check(
            restored.status == 200 and restored.json()["showPresence"] is True,
            "restore showPresence failed",
        )
        log.ok("settings round-trip", "true -> false -> true")

        # -------------------------------------------------- unauthenticated
        anonymous = client.get(
            f"/api/v1/conversations/{conversation_id}/messages"
        )
        check(anonymous.status == 401, f"anonymous history expected 401, got {anonymous}")
        check(
            anonymous.error_code() == "UNAUTHORIZED",
            f"anonymous code {anonymous.error_code()} != UNAUTHORIZED",
        )
        anonymous_edit = client.patch(
            f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
            body={"content": "anonymous"},
        )
        check(anonymous_edit.status == 401, f"anonymous edit expected 401, got {anonymous_edit}")
        log.ok("unauthenticated requests rejected", "401 UNAUTHORIZED")

    code = 1
    try:
        code = run("NexaChat API smoke", steps)
    finally:
        # Cleanup runs on failure too; only the fixtures this run created are
        # touched and the deletes are idempotent.
        conversation_id = state.get("conversation_id")
        if conversation_id:
            removed = cleanup_fixtures(
                client, state["token"], conversation_id, created
            )
            print(f"[cleanup] {removed}/{len(created)} fixture messages soft-deleted")
    return code


def cleanup_fixtures(client: Client, token: str, conversation_id: str, message_ids) -> int:
    removed = 0
    for message_id in message_ids:
        try:
            response = client.delete(
                f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
                token=token,
            )
        except E2EFailure:
            continue
        if response.status == 204:
            removed += 1
    return removed


if __name__ == "__main__":
    main_guard(main)
