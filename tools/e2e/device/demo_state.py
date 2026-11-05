#!/usr/bin/env python3
"""Demo-state helpers for the device verification pass.

The Android screenshots must show a tidy inbox, so this script inspects and
repairs the *seeded demo data* only (Lan Anh / Minh Khoa / Bao Chau). It talks
to the same REST API as the app and reuses the token cache from
`tools/e2e/e2e_common.py`, so it respects the login rate limit.

Commands:
    python tools/e2e/device/demo_state.py list
    python tools/e2e/device/demo_state.py tidy
    python tools/e2e/device/demo_state.py seed-direct --peer +84900000203
    python tools/e2e/device/demo_state.py mark-read
    python tools/e2e/device/demo_state.py send --as +84900000202 --conversation <id> --text "..."
    python tools/e2e/device/demo_state.py settings --as +84900000201 --presence on
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import uuid

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from e2e_common import Client, login  # noqa: E402

LAN_ANH = "+84900000201"
MINH_KHOA = "+84900000202"
BAO_CHAU = "+84900000203"

# Extra groups created by repeated seed runs (the seed script compares
# `isGroup`, but the inbox DTO exposes `type`), retitled so the demo inbox
# does not show three identical rows.
KEEP_TITLE = "Nhóm Nghiên Cứu"
# The seed script writes the group title without diacritics; accept both so a
# re-run after tidying stays idempotent.
KEEP_TITLE_ALIASES = {KEEP_TITLE, "Nhom Nghien Cuu"}
EXTRA_TITLES = ["Nhóm Đồ Án", "Nhóm Tiếng Anh"]
EXTRA_MESSAGES = [
    "Mai họp nhóm Đồ Án lúc 14h ở thư viện nhé mọi người.",
    "Tuần này lớp mình luyện speaking chủ đề công nghệ.",
]
# Early revisions of this helper posted ASCII-only tails; they are removed so
# the demo groups keep natural Vietnamese copy.
LEGACY_TAILS = {
    "Mai hop nhom Do An luc 14h o thu vien nhe moi nguoi.",
    "Tuan nay lop minh luyen speaking chu de cong nghe.",
}


def conversations(client: Client, token: str) -> list[dict]:
    response = client.get("/api/v1/conversations", token=token)
    response_status = response.status
    data = response.json()
    if response_status != 200:
        raise SystemExit(f"inbox failed: {response_status} {data}")
    return data if isinstance(data, list) else data.get("items", [])


def describe(item: dict) -> str:
    last = item.get("lastMessage") or {}
    snippet = last.get("contentSnippet")
    edited = " (edited)" if last.get("editedAt") else ""
    deleted = " (deleted)" if last.get("deletedAt") else ""
    title = item.get("title") or next(
        (m["displayName"] for m in item.get("members", []) if m["userId"] != item.get("viewerId")),
        "-",
    )
    return f"{item['id']}  {item.get('type'):7} {title!r:24} {snippet!r}{edited}{deleted}"


def cmd_list(args) -> int:
    client = Client()
    token = login(client, args.as_phone)["accessToken"]
    for item in conversations(client, token):
        print(describe(item))
    return 0


def cmd_tidy(args) -> int:
    client = Client()
    session = login(client, LAN_ANH)
    token = session["accessToken"]
    groups = [c for c in conversations(client, token) if c.get("type") == "GROUP"]
    groups.sort(key=lambda c: c["id"])
    named = [g for g in groups if g.get("title") in KEEP_TITLE_ALIASES]
    print(f"groups={len(groups)} named={len(named)}")
    keep = named[-1] if named else None
    if keep is not None and keep.get("title") != KEEP_TITLE:
        response = client.request(
            "PATCH",
            f"/api/v1/conversations/{keep['id']}",
            token=token,
            body={"title": KEEP_TITLE},
        )
        print(f"retitle {keep['id']} -> {KEEP_TITLE!r}: {response.status}")
    extras = [g for g in groups if g is not keep]
    for index, extra in enumerate(extras):
        if index >= len(EXTRA_TITLES):
            print(f"WARN extra group {extra['id']} left untouched (title {extra.get('title')!r})")
            continue
        title = EXTRA_TITLES[index]
        if extra.get("title") != title:
            response = client.request(
                "PATCH",
                f"/api/v1/conversations/{extra['id']}",
                token=token,
                body={"title": title},
            )
            print(f"rename {extra['id']} -> {title!r}: {response.status}")
        sender_phone = MINH_KHOA if index == 0 else BAO_CHAU
        sender = login(client, sender_phone)["accessToken"]
        _drop_legacy_tails(client, sender, extra["id"])
        message = EXTRA_MESSAGES[index]
        response = client.request(
            "POST",
            f"/api/v1/conversations/{extra['id']}/messages",
            token=sender,
            body={
                "type": "TEXT",
                "content": message,
                "clientMessageId": str(
                    uuid.uuid5(uuid.NAMESPACE_URL, f"demo-state:{extra['id']}:tail-v2")
                ),
            },
        )
        print(f"tail message in {extra['id']}: {response.status}")
    return 0


def _drop_legacy_tails(client: Client, token: str, conversation_id: str) -> None:
    response = client.get(
        f"/api/v1/conversations/{conversation_id}/messages",
        token=token,
        params={"limit": 5},
    )
    items = (response.json() or {}).get("items", [])
    for message in items:
        if message.get("content") in LEGACY_TAILS and not message.get("deletedAt"):
            deleted = client.request(
                "DELETE",
                f"/api/v1/conversations/{conversation_id}/messages/{message['id']}",
                token=token,
            )
            print(f"drop legacy tail {message['id']}: {deleted.status}")


def cmd_seed_direct(args) -> int:
    """Creates a clean Lan Anh <-> peer DIRECT thread for screenshots."""
    client = Client()
    lan = login(client, LAN_ANH)
    peer = login(client, args.peer)
    me, other = lan["user"], peer["user"]
    response = client.request(
        "POST", "/api/v1/conversations/direct", token=lan["accessToken"], body={"userId": other["id"]}
    )
    conversation_id = (response.json() or {}).get("id")
    print(f"direct {LAN_ANH} <-> {args.peer}: {response.status} {conversation_id}")
    if not conversation_id:
        return 1

    script = [
        (peer, "CHAU-1", "Lan Anh ơi, cậu xem giúp mình bản tổng hợp phản hồi người dùng nhé.", None),
        (lan, "LAN-1", "Mình vừa xem xong, phần lớn phản hồi đều rất tích cực.", None),
        (lan, "LAN-2", "Mình có góp vài ý ở phần cuối tài liệu.", "CHAU-1"),
        (peer, "CHAU-2", "Cảm ơn cậu! Để mình sửa rồi gửi lại trước tối nay.", None),
        (lan, "LAN-3", "Nhớ gửi kèm số liệu khảo sát mới nhất nhé.", None),
        (peer, "CHAU-3", "Ok, mình ghi lại rồi.", None),
    ]
    created: dict[str, str] = {}
    for session, key, text, reply_key in script:
        body = {
            "type": "TEXT",
            "content": text,
            "clientMessageId": str(uuid.uuid5(uuid.NAMESPACE_URL, f"demo-direct:{conversation_id}:{key}")),
        }
        if reply_key and reply_key in created:
            body["replyToId"] = created[reply_key]
        result = client.request(
            "POST", f"/api/v1/conversations/{conversation_id}/messages",
            token=session["accessToken"], body=body,
        )
        payload = result.json() or {}
        created[key] = payload.get("id")
        print(f"{key}: {result.status}")
    if created.get("LAN-3"):
        pinned = client.request(
            "POST", f"/api/v1/conversations/{conversation_id}/pins",
            token=lan["accessToken"], body={"messageId": created["LAN-3"]},
        )
        print(f"pin LAN-3: {pinned.status}")
    if created.get("CHAU-2"):
        reaction = client.request(
            "POST", f"/api/v1/messages/{created['CHAU-2']}/reactions",
            token=lan["accessToken"], body={"emoji": "❤️"},
        )
        print(f"reaction CHAU-2: {reaction.status}")
    return 0


def cmd_send(args) -> int:
    client = Client()
    token = login(client, args.as_phone)["accessToken"]
    response = client.request(
        "POST",
        f"/api/v1/conversations/{args.conversation}/messages",
        token=token,
        body={
            "type": "TEXT",
            "content": args.text,
            "clientMessageId": str(uuid.uuid4()),
        },
    )
    print(response.status, json.dumps(response.json(), ensure_ascii=False)[:300])
    return 0 if response.status in (200, 201) else 1


def cmd_mark_read(args) -> int:
    client = Client()
    token = login(client, LAN_ANH)["accessToken"]
    for item in conversations(client, token):
        last = (item.get("lastMessage") or {}).get("seq")
        if not last:
            continue
        response = client.request(
            "POST", f"/api/v1/conversations/{item['id']}/read", token=token,
            body={"upToSeq": last},
        )
        print(f"read {item['id']} up to {last}: {response.status}")
    return 0


def cmd_settings(args) -> int:
    client = Client()
    token = login(client, args.as_phone)["accessToken"]
    show = args.presence == "on"
    response = client.request(
        "PATCH", "/api/v1/users/me/settings", token=token, body={"showPresence": show}
    )
    print(response.status, json.dumps(response.json(), ensure_ascii=False)[:200])
    return 0 if response.status == 200 else 1


def main(argv=None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    listing = commands.add_parser("list")
    listing.add_argument("--as", dest="as_phone", default=LAN_ANH)
    listing.set_defaults(func=cmd_list)

    tidy = commands.add_parser("tidy")
    tidy.set_defaults(func=cmd_tidy)

    send = commands.add_parser("send")
    send.add_argument("--as", dest="as_phone", required=True)
    send.add_argument("--conversation", required=True)
    send.add_argument("--text", required=True)
    send.set_defaults(func=cmd_send)

    seed_direct = commands.add_parser("seed-direct")
    seed_direct.add_argument("--peer", default=BAO_CHAU)
    seed_direct.set_defaults(func=cmd_seed_direct)

    mark_read = commands.add_parser("mark-read")
    mark_read.set_defaults(func=cmd_mark_read)

    settings = commands.add_parser("settings")
    settings.add_argument("--as", dest="as_phone", default=LAN_ANH)
    settings.add_argument("--presence", choices=["on", "off"], required=True)
    settings.set_defaults(func=cmd_settings)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
