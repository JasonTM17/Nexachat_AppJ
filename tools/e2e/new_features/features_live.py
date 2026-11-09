#!/usr/bin/env python3
"""Live E2E coverage for the archive, inbox flags, reminders, polls, AI
opt-out, conversation pin, seen-privacy, idempotency and revoked-token
features.

The suite creates its own synthetic accounts (random phones/passwords generated
at runtime), exercises the merged REST/STOMP contract against a running
backend, and cleans up every artefact an endpoint allows it to remove. Tokens,
passwords and phone numbers are never printed; ids are masked.

Usage:
    python tools/e2e/new_features/features_live.py
    python tools/e2e/new_features/features_live.py --base-url http://127.0.0.1:8080
    python tools/e2e/new_features/features_live.py --self-test

The default base URL is ``API_BASE_URL`` or ``http://localhost:8080``. A
non-loopback URL is refused unless ``--i-know-this-is-not-prod`` is passed.
``--allow-skip`` turns a required skip into a success (for an offline machine);
a FAIL always exits non-zero.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.parse
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

HERE = Path(__file__).resolve().parent
E2E_DIR = HERE.parent
P0_DIR = E2E_DIR / "p0"
for path in (E2E_DIR, P0_DIR, HERE):
    if str(path) not in sys.path:
        sys.path.insert(0, str(path))

from e2e_common import Client, E2EFailure, Response  # noqa: E402
from features_helpers import (  # noqa: E402
    DEFAULT_BASE_URL,
    CheckLog,
    anonymous_poll_leaks_nobody,
    find_archive_item,
    find_inbox_entry,
    generate_test_password,
    generate_test_phone,
    iso_utc,
    is_local_base_url,
    mask_id,
    mask_phone,
    multipart_body,
    png_1x1,
    poll_option_by_label,
    utc_after,
)

try:  # The P0 helper is the existing STOMP client; websocket-client is an e2e dependency.
    from p0_live import P0StompConnection
except ImportError:  # pragma: no cover - exercised only on machines without the dep
    P0StompConnection = None


# --- fixtures -----------------------------------------------------------------

@dataclass
class Fixtures:
    user_a: dict[str, Any]
    user_b: dict[str, Any]
    user_c: dict[str, Any]
    user_d: dict[str, Any]
    direct_id: str
    poll_group_id: str
    pin_groups: list[str] = field(default_factory=list)
    message_refs: list[tuple[str, str]] = field(default_factory=list)

    def track(self, response: Response, token: str | None = None) -> dict[str, Any]:
        data = response.json() or {}
        message_id = data.get("id")
        if message_id:
            self.message_refs.append((message_id, token or self.user_a["token"]))
        return data


def _error_code(response: Response | None) -> str | None:
    if response is None:
        return None
    try:
        return response.error_code()
    except Exception:
        return None


def _status(response: Response | None) -> Any:
    return getattr(response, "status", None)


def safe_exception(exc: Exception) -> str:
    """Exception text with phone-like and JWT-like material redacted."""
    text = f"{type(exc).__name__}: {exc}"
    text = re.sub(r"eyJ[A-Za-z0-9_\-.]{16,}", "[REDACTED_TOKEN]", text)
    text = re.sub(r"\+?\d{10,15}", "[REDACTED_PHONE]", text)
    return text[:300]


def create_user(client: Client, display_name: str) -> dict[str, Any]:
    """Registers a throwaway account, retrying login/register rate limits."""
    last_status = None
    for attempt in range(10):
        phone = generate_test_phone()
        password = generate_test_password()
        response = client.post(
            "/api/v1/auth/register",
            body={"phone": phone, "password": password, "displayName": display_name},
        )
        last_status = response.status
        if response.status in (200, 201):
            data = response.json()
            return {
                "id": data["user"]["id"],
                "phone": phone,
                "password": password,
                "token": data["accessToken"],
            }
        if response.status == 409:  # generated collision; draw another number
            continue
        if response.status == 429:
            details = (response.json() or {}).get("error", {}).get("details") or {}
            wait = min(60, max(1, int(details.get("retryAfterSeconds", 15))))
            time.sleep(wait + 0.5)
            continue
        break
    raise E2EFailure(f"register failed with HTTP {last_status}")


def create_direct(client: Client, token: str, phone: str) -> tuple[int, dict[str, Any]]:
    response = client.post("/api/v1/conversations/direct", token=token, body={"phone": phone})
    if response.status not in (200, 201):
        raise E2EFailure(f"direct conversation failed with HTTP {response.status}")
    return response.status, response.json()


def create_group(client: Client, token: str, title: str,
                 member_ids: Iterable[str]) -> dict[str, Any]:
    response = client.post(
        "/api/v1/conversations/group",
        token=token,
        body={"title": title, "memberIds": list(member_ids)},
    )
    if response.status != 201:
        raise E2EFailure(f"group creation failed with HTTP {response.status}")
    return response.json()


def send_message(client: Client, token: str, conversation_id: str, *,
                 message_type: str = "TEXT", content: str | None = None,
                 attachment_ids: list[str] | None = None) -> Response:
    body: dict[str, Any] = {
        "clientMessageId": str(uuid.uuid4()),
        "type": message_type,
        "attachmentIds": attachment_ids or [],
    }
    if content is not None:
        body["content"] = content
    return client.post(f"/api/v1/conversations/{conversation_id}/messages", token=token, body=body)


def upload_attachment(client: Client, token: str, *, filename: str,
                      content_type: str, data: bytes,
                      fields: dict[str, str] | None = None) -> Response:
    body, multipart_type = multipart_body(
        fields=fields, filename=filename, content_type=content_type, data=data
    )
    return client.request(
        "POST", "/api/v1/attachments", token=token, raw_body=(body, multipart_type)
    )


def inbox(client: Client, token: str, params: dict[str, Any] | None = None) -> Response:
    return client.get("/api/v1/conversations", token=token, params=params)


def inbox_entry(client: Client, token: str, conversation_id: str,
                params: dict[str, Any] | None = None) -> dict[str, Any] | None:
    response = inbox(client, token, params)
    if response.status != 200:
        return None
    return find_inbox_entry(response.json() or [], conversation_id)


def setup_fixtures(client: Client, log: CheckLog, *, pin_groups: int = 6) -> Fixtures:
    print("Creating throwaway accounts and conversations...")
    user_a = create_user(client, "E2E New A")
    user_b = create_user(client, "E2E New B")
    user_c = create_user(client, "E2E New C")
    user_d = create_user(client, "E2E New D")
    print(
        f"Fixture A id={mask_id(user_a['id'])} phone={mask_phone(user_a['phone'])}; "
        f"B id={mask_id(user_b['id'])} phone={mask_phone(user_b['phone'])}; "
        f"C id={mask_id(user_c['id'])} phone={mask_phone(user_c['phone'])}; "
        f"D id={mask_id(user_d['id'])} phone={mask_phone(user_d['phone'])}"
    )

    _, direct = create_direct(client, user_a["token"], user_b["phone"])
    poll_group = create_group(
        client, user_a["token"], "E2E poll group", [user_b["id"]]
    )
    groups = [
        create_group(client, user_a["token"], f"E2E pin group {index}", [user_b["id"]])
        for index in range(1, pin_groups + 1)
    ]
    print(
        f"Direct conversation {mask_id(direct['id'])}; poll group "
        f"{mask_id(poll_group['id'])}; {len(groups)} pin groups created"
    )
    return Fixtures(
        user_a=user_a,
        user_b=user_b,
        user_c=user_c,
        user_d=user_d,
        direct_id=direct["id"],
        poll_group_id=poll_group["id"],
        pin_groups=[group["id"] for group in groups],
    )


# --- archive browser ----------------------------------------------------------

def scenario_archive(log: CheckLog, client: Client, fx: Fixtures) -> None:
    conv = fx.direct_id
    token = fx.user_a["token"]
    path = f"/api/v1/conversations/{conv}/archive"

    image = upload_attachment(
        client, token, filename="e2e.png", content_type="image/png", data=png_1x1(),
        fields={"width": "1", "height": "1", "blurHash": "LEHV6nWB2yk8pyo0adR*.7kCMdnj"},
    )
    log.expect_status("archive_upload_image", image, 201, "POST", "/api/v1/attachments")
    if image.status != 201:
        return
    image_data = image.json()
    image_id = image_data["id"]

    document = upload_attachment(
        client, token, filename="e2e.txt", content_type="text/plain",
        data=b"NexaChat archive E2E fixture\n",
    )
    log.expect_status("archive_upload_file", document, 201, "POST", "/api/v1/attachments")
    if document.status != 201:
        return
    document_id = document.json()["id"]

    image_msg = send_message(
        client, token, conv, message_type="IMAGE", content="archive image",
        attachment_ids=[image_id],
    )
    log.expect_status(
        "archive_send_image", image_msg, 201, "POST",
        f"/api/v1/conversations/{conv}/messages",
    )
    if image_msg.status not in (200, 201):
        return
    fx.track(image_msg)

    file_msg = send_message(
        client, token, conv, message_type="FILE", content="archive file",
        attachment_ids=[document_id],
    )
    log.expect_status(
        "archive_send_file", file_msg, 201, "POST",
        f"/api/v1/conversations/{conv}/messages",
    )
    if file_msg.status not in (200, 201):
        return
    file_data = fx.track(file_msg)

    link_message = send_message(
        client, token, conv,
        content=(
            "Links https://example.com/e2e-a, https://example.com/e2e-b; "
            "and https://example.com/e2e-c. javascript:alert(1) stays out."
        ),
    )
    log.expect_status(
        "archive_send_links", link_message, 201, "POST",
        f"/api/v1/conversations/{conv}/messages",
    )
    if link_message.status not in (200, 201):
        return
    fx.track(link_message)

    # MEDIA tab
    media = client.get(path, token=token, params={"type": "MEDIA", "limit": 50})
    log.expect_status("archive_media_status", media, 200, "GET", path)
    media_items = (media.json() or {}).get("items") or [] if media.status == 200 else []
    image_item = find_archive_item(media_items, attachment_id=image_id)
    log.expect(
        "archive_media_contains_image", image_item is not None,
        "GET", path, _status(media), "attachment found in MEDIA tab",
    )
    if image_item is not None:
        log.expect(
            "archive_media_kind", image_item.get("kind") == "IMAGE",
            "GET", path, _status(media), f"kind={image_item.get('kind')}",
        )
        attachment = image_item.get("attachment") or {}
        log.expect(
            "archive_media_metadata",
            attachment.get("blurHash") == "LEHV6nWB2yk8pyo0adR*.7kCMdnj"
            and attachment.get("width") == 1
            and attachment.get("height") == 1,
            "GET", path, _status(media), "blurHash/width/height preserved",
        )

    # FILE tab
    files = client.get(path, token=token, params={"type": "FILE", "limit": 50})
    log.expect_status("archive_file_status", files, 200, "GET", path)
    file_items = (files.json() or {}).get("items") or [] if files.status == 200 else []
    file_item = find_archive_item(file_items, attachment_id=document_id)
    log.expect(
        "archive_file_contains_document", file_item is not None,
        "GET", path, _status(files), "text attachment found in FILE tab",
    )
    if file_item is not None:
        log.expect(
            "archive_file_kind", file_item.get("kind") == "FILE",
            "GET", path, _status(files), f"kind={file_item.get('kind')}",
        )

    # LINK tab and cursor paging one link at a time.
    links = client.get(path, token=token, params={"type": "LINK", "limit": 50})
    log.expect_status("archive_link_status", links, 200, "GET", path)
    link_items = (links.json() or {}).get("items") or [] if links.status == 200 else []
    link_urls = [(item.get("link") or {}).get("url") for item in link_items]
    expected_links = {
        "https://example.com/e2e-a",
        "https://example.com/e2e-b",
        "https://example.com/e2e-c",
    }
    log.expect(
        "archive_link_extraction", expected_links.issubset(set(link_urls)),
        "GET", path, _status(links), f"{len(expected_links)} expected URLs present",
    )
    log.expect(
        "archive_link_scheme_filter",
        links.status == 200
        and not any(str(url).startswith("javascript:") for url in link_urls
                    if url is not None),
        "GET", path, _status(links), "javascript: URL absent",
    )

    first_page = client.get(path, token=token, params={"type": "LINK", "limit": 1})
    log.expect_status("archive_cursor_page1", first_page, 200, "GET", path)
    first_data = first_page.json() or {} if first_page.status == 200 else {}
    first_cursor = first_data.get("nextCursor")
    log.expect(
        "archive_cursor_next", bool(first_cursor),
        "GET", path, _status(first_page), "nextCursor present on a partial page",
    )
    seen_urls: list[str] = [
        (item.get("link") or {}).get("url")
        for item in first_data.get("items") or []
    ]
    cursor = first_cursor
    pages = 0
    while cursor and pages < 5:
        page = client.get(path, token=token, params={"type": "LINK", "limit": 1, "cursor": cursor})
        if page.status != 200:
            log.expect(
                "archive_cursor_page", False, "GET", path, page.status,
                "paging request failed",
            )
            break
        data = page.json() or {}
        for item in data.get("items") or []:
            seen_urls.append((item.get("link") or {}).get("url"))
        cursor = data.get("nextCursor")
        pages += 1
    log.expect(
        "archive_cursor_no_duplicates",
        len(seen_urls) == len(set(seen_urls)) and expected_links.issubset(set(seen_urls)),
        "GET", path, 200, f"{pages} cursor page(s), no duplicate URLs",
    )

    # Filters
    sender_filter = client.get(
        path, token=token, params={"type": "MEDIA", "senderId": str(uuid.uuid4())}
    )
    log.expect_status("archive_sender_filter_status", sender_filter, 200, "GET", path)
    log.expect(
        "archive_sender_filter_empty",
        ((sender_filter.json() or {}).get("items") or []) == []
        if sender_filter.status == 200 else False,
        "GET", path, _status(sender_filter), "unknown sender returns no items",
    )

    window = client.get(
        path, token=token,
        params={
            "type": "MEDIA",
            "from": iso_utc(utc_after(days=-1)),
            "to": iso_utc(utc_after(days=1)),
        },
    )
    log.expect_status("archive_window_status", window, 200, "GET", path)
    log.expect(
        "archive_window_contains",
        find_archive_item((window.json() or {}).get("items") or [], attachment_id=image_id)
        is not None if window.status == 200 else False,
        "GET", path, _status(window), "inclusive from/to window contains the image",
    )

    inverted = client.get(
        path, token=token,
        params={
            "type": "MEDIA",
            "from": iso_utc(utc_after(days=2)),
            "to": iso_utc(utc_after(days=1)),
        },
    )
    log.expect_status("archive_inverted_window", inverted, 400, "GET", path)
    log.expect(
        "archive_inverted_window_code", _error_code(inverted) == "VALIDATION_FAILED",
        "GET", path, _status(inverted), f"code={_error_code(inverted)}",
    )

    invalid_type = client.get(path, token=token, params={"type": "AUDIO"})
    log.expect_status("archive_invalid_type", invalid_type, 400, "GET", path)

    bad_cursor = client.get(path, token=token, params={"type": "MEDIA", "cursor": "not-a-cursor"})
    log.expect_status("archive_bad_cursor", bad_cursor, 400, "GET", path)

    non_member = client.get(path, token=fx.user_c["token"], params={"type": "MEDIA"})
    log.expect_status("archive_non_member", non_member, 403, "GET", path)
    log.expect(
        "archive_non_member_code", _error_code(non_member) == "NOT_MEMBER",
        "GET", path, _status(non_member), f"code={_error_code(non_member)}",
    )

    # A tombstoned FILE message disappears from the archive.
    deleted = client.delete(
        f"/api/v1/conversations/{conv}/messages/{file_data['id']}", token=token
    )
    log.expect_status(
        "archive_delete_message", deleted, 204, "DELETE",
        f"/api/v1/conversations/{conv}/messages/{{messageId}}",
    )
    after_delete = client.get(path, token=token, params={"type": "FILE", "limit": 50})
    log.expect_status("archive_after_delete_status", after_delete, 200, "GET", path)
    log.expect(
        "archive_tombstone_excluded",
        find_archive_item((after_delete.json() or {}).get("items") or [],
                          attachment_id=document_id) is None
        if after_delete.status == 200 else False,
        "GET", path, _status(after_delete), "deleted message attachment absent",
    )


# --- inbox mute / archive flags ----------------------------------------------

def _entry_flag(client: Client, token: str, conversation_id: str, flag: str,
                params: dict[str, Any] | None = None) -> Any:
    entry = inbox_entry(client, token, conversation_id, params)
    return None if entry is None else entry.get(flag)


def scenario_inbox_flags(log: CheckLog, client: Client, fx: Fixtures) -> None:
    conv = fx.direct_id
    path = f"/api/v1/conversations/{conv}/mute"
    archive_path = f"/api/v1/conversations/{conv}/archive"
    token_b = fx.user_b["token"]
    token_a = fx.user_a["token"]

    log.expect(
        "inbox_initial_flags",
        _entry_flag(client, token_b, conv, "muted") is False
        and _entry_flag(client, token_b, conv, "archived") is False,
        "GET", "/api/v1/conversations", 200, "muted=false archived=false",
    )

    mute_forever = client.put(path, token=token_b, body={"forever": True})
    log.expect_status("mute_forever", mute_forever, 204, "PUT", path)
    log.expect(
        "mute_forever_inbox", _entry_flag(client, token_b, conv, "muted") is True,
        "GET", "/api/v1/conversations", 200, "caller sees muted=true",
    )
    log.expect(
        "mute_per_member",
        _entry_flag(client, token_a, conv, "muted") is False,
        "GET", "/api/v1/conversations", 200, "other member remains unmuted",
    )

    timed_until = iso_utc(utc_after(hours=2))
    mute_timed = client.put(path, token=token_b, body={"mutedUntil": timed_until})
    log.expect_status("mute_timed", mute_timed, 204, "PUT", path)
    entry = inbox_entry(client, token_b, conv)
    log.expect(
        "mute_timed_until",
        entry is not None and entry.get("muted") is True and entry.get("mutedUntil"),
        "GET", "/api/v1/conversations", 200, "mutedUntil echoed on the inbox row",
    )

    unmute = client.delete(path, token=token_b)
    log.expect_status("unmute", unmute, 204, "DELETE", path)
    log.expect(
        "unmute_inbox", _entry_flag(client, token_b, conv, "muted") is False,
        "GET", "/api/v1/conversations", 200, "muted=false after unmute",
    )

    both = client.put(
        path, token=token_b,
        body={"forever": True, "mutedUntil": iso_utc(utc_after(hours=1))},
    )
    log.expect_status("mute_both_rejected", both, 400, "PUT", path)

    past = client.put(
        path, token=token_b, body={"mutedUntil": iso_utc(utc_after(hours=-1))}
    )
    log.expect_status("mute_past_rejected", past, 400, "PUT", path)

    non_member = client.put(path, token=fx.user_c["token"], body={"forever": True})
    log.expect_status("mute_non_member", non_member, 403, "PUT", path)

    archived = client.put(archive_path, token=token_b)
    log.expect_status("archive_conversation", archived, 204, "PUT", archive_path)
    default_entry = inbox_entry(client, token_b, conv)
    archived_entry = inbox_entry(client, token_b, conv, {"archived": "true"})
    log.expect(
        "archive_hidden_default", default_entry is None,
        "GET", "/api/v1/conversations", 200, "archived row absent from default inbox",
    )
    log.expect(
        "archive_listed_when_filtered",
        archived_entry is not None and archived_entry.get("archived") is True,
        "GET", "/api/v1/conversations?archived=true", 200, "archived row listed",
    )
    log.expect(
        "inbox_other_member_unaffected",
        archived_entry is not None and inbox_entry(client, token_a, conv) is not None,
        "GET", "/api/v1/conversations", 200, "other member still sees it",
    )

    unarchive = client.delete(archive_path, token=token_b)
    log.expect_status("unarchive_conversation", unarchive, 204, "DELETE", archive_path)
    log.expect(
        "unarchive_default_inbox",
        unarchive.status == 204 and inbox_entry(client, token_b, conv) is not None,
        "GET", "/api/v1/conversations", 200, "row restored to default inbox",
    )

    non_member_archive = client.put(archive_path, token=fx.user_c["token"])
    log.expect_status("inbox_archive_non_member", non_member_archive, 403, "PUT", archive_path)


# --- reminders ----------------------------------------------------------------

def scenario_reminders(log: CheckLog, client: Client, fx: Fixtures) -> None:
    conv = fx.direct_id
    path = f"/api/v1/conversations/{conv}/reminders"
    token_a = fx.user_a["token"]
    token_b = fx.user_b["token"]

    body = {
        "title": "E2E reminder",
        "remindAt": iso_utc(utc_after(hours=2)),
        "repeat": "NONE",
    }
    created = client.post(path, token=token_a, body=body)
    log.expect_status("reminder_create", created, 201, "POST", path)
    if created.status != 201:
        return
    reminder = created.json()
    reminder_id = reminder["id"]
    log.expect(
        "reminder_create_fields",
        reminder.get("createdBy") == fx.user_a["id"]
        and reminder.get("canEdit") is True
        and reminder.get("title") == "E2E reminder",
        "POST", path, created.status, "creator/canEdit/title echoed",
    )

    listed = client.get(path, token=token_b)
    log.expect_status("reminder_list_member", listed, 200, "GET", path)
    listed_items = listed.json() or [] if listed.status == 200 else []
    seen = next((item for item in listed_items if item.get("id") == reminder_id), None)
    log.expect(
        "reminder_list_visible", seen is not None,
        "GET", path, _status(listed), "other member sees the shared reminder",
    )
    log.expect(
        "reminder_can_edit_flag",
        seen is not None and seen.get("canEdit") is False,
        "GET", path, _status(listed), "non-creator canEdit=false in DIRECT",
    )

    foreign_edit = client.patch(path + f"/{reminder_id}", token=token_b, body={"title": "nope"})
    log.expect_status("reminder_foreign_edit", foreign_edit, 403, "PATCH", path + "/{id}")
    log.expect(
        "reminder_foreign_edit_code", _error_code(foreign_edit) == "FORBIDDEN",
        "PATCH", path + "/{id}", _status(foreign_edit), f"code={_error_code(foreign_edit)}",
    )

    non_member_list = client.get(path, token=fx.user_c["token"])
    log.expect_status("reminder_non_member", non_member_list, 403, "GET", path)

    edited = client.patch(
        path + f"/{reminder_id}", token=token_a, body={"title": "E2E reminder edited"}
    )
    log.expect_status("reminder_edit", edited, 200, "PATCH", path + "/{id}")
    log.expect(
        "reminder_edit_value", (edited.json() or {}).get("title") == "E2E reminder edited",
        "PATCH", path + "/{id}", _status(edited), "title updated",
    )

    empty_edit = client.patch(path + f"/{reminder_id}", token=token_a, body={})
    log.expect_status("reminder_empty_edit", empty_edit, 400, "PATCH", path + "/{id}")

    past = client.post(
        path, token=token_a,
        body={"title": "past", "remindAt": iso_utc(utc_after(hours=-2))},
    )
    log.expect_status("reminder_past_rejected", past, 400, "POST", path)

    deleted = client.delete(path + f"/{reminder_id}", token=token_a)
    log.expect_status("reminder_delete", deleted, 204, "DELETE", path + "/{id}")
    after = client.get(path, token=token_a)
    log.expect(
        "reminder_deleted_absent",
        all(item.get("id") != reminder_id for item in (after.json() or []))
        if after.status == 200 else False,
        "GET", path, _status(after), "deleted reminder absent from board",
    )

    # Idempotency-Key replay: 201 then 200 with the same row, different payload 409.
    key = str(uuid.uuid4())
    idem_body = {
        "title": "E2E idempotent reminder",
        "remindAt": iso_utc(utc_after(hours=3)),
        "repeat": "DAILY",
    }
    first = client.post(path, token=token_a, body=idem_body, headers={"Idempotency-Key": key})
    log.expect_status("reminder_idem_create", first, 201, "POST", path)
    replay = client.post(path, token=token_a, body=idem_body, headers={"Idempotency-Key": key})
    log.expect_status("reminder_idem_replay", replay, 200, "POST", path)
    log.expect(
        "reminder_idem_same_row",
        first.status == 201 and replay.status == 200
        and (first.json() or {}).get("id") == (replay.json() or {}).get("id"),
        "POST", path, _status(replay), "replay returned the original reminder",
    )
    reused = client.post(
        path, token=token_a,
        body={**idem_body, "title": "different payload"},
        headers={"Idempotency-Key": key},
    )
    log.expect_status("reminder_idem_reused", reused, 409, "POST", path)
    log.expect(
        "reminder_idem_reused_code",
        _error_code(reused) == "IDEMPOTENCY_KEY_REUSED",
        "POST", path, _status(reused), f"code={_error_code(reused)}",
    )
    if first.status == 201:
        client.delete(path + f"/{first.json()['id']}", token=token_a)


# --- generic Idempotency-Key replay on reactions -----------------------------

def scenario_idempotency(log: CheckLog, client: Client, fx: Fixtures) -> None:
    conv = fx.direct_id
    token = fx.user_a["token"]
    sent = send_message(client, token, conv, content="E2E reaction idempotency")
    log.expect_status(
        "idempotency_send_message", sent, 201, "POST",
        f"/api/v1/conversations/{conv}/messages",
    )
    if sent.status not in (200, 201):
        return
    message_id = sent.json()["id"]
    fx.track(sent)
    reaction_path = f"/api/v1/messages/{message_id}/reactions"
    key = str(uuid.uuid4())
    emoji = "\U0001f44d"  # thumbs up

    first = client.post(
        reaction_path, token=token, body={"emoji": emoji},
        headers={"Idempotency-Key": key},
    )
    log.expect_status("idempotency_reaction_add", first, 204, "POST", reaction_path)
    replay = client.post(
        reaction_path, token=token, body={"emoji": emoji},
        headers={"Idempotency-Key": key},
    )
    log.expect_status("idempotency_reaction_replay", replay, 204, "POST", reaction_path)

    history = client.get(
        f"/api/v1/conversations/{conv}/messages", token=token, params={"limit": 20}
    )
    target = None
    if history.status == 200:
        target = next(
            (item for item in (history.json() or {}).get("items") or []
             if item.get("id") == message_id),
            None,
        )
    reactions = (target or {}).get("reactions") or []
    matching = next((row for row in reactions if row.get("emoji") == emoji), None)
    log.expect(
        "idempotency_reaction_not_toggled",
        matching is not None and matching.get("count") == 1,
        "GET", f"/api/v1/conversations/{conv}/messages", _status(history),
        "replay did not toggle the reaction off",
    )

    reused = client.post(
        reaction_path, token=token, body={"emoji": "\U0001f44e"},
        headers={"Idempotency-Key": key},
    )
    log.expect_status("idempotency_reaction_reused", reused, 409, "POST", reaction_path)
    log.expect(
        "idempotency_reaction_reused_code",
        _error_code(reused) == "IDEMPOTENCY_KEY_REUSED",
        "POST", reaction_path, _status(reused), f"code={_error_code(reused)}",
    )

    client.delete(
        reaction_path + "/" + urllib.parse.quote(emoji, safe=""), token=token
    )


# --- polls --------------------------------------------------------------------

def scenario_polls(log: CheckLog, client: Client, fx: Fixtures) -> None:
    group = fx.poll_group_id
    path = f"/api/v1/conversations/{group}/polls"
    token_a = fx.user_a["token"]
    token_b = fx.user_b["token"]

    created = client.post(
        path, token=token_a,
        body={"question": "E2E lunch?", "options": ["Yes", "No"], "multipleChoice": False},
    )
    log.expect_status("poll_create", created, 201, "POST", path)
    if created.status != 201:
        return
    poll = created.json()
    poll_id = poll["id"]
    yes = poll_option_by_label(poll, "Yes")
    no = poll_option_by_label(poll, "No")
    log.expect(
        "poll_create_shape",
        poll.get("status") == "OPEN" and yes is not None and no is not None,
        "POST", path, created.status, "OPEN with two options",
    )
    if yes is None or no is None:
        return

    listed = client.get(path, token=token_b)
    log.expect_status("poll_list", listed, 200, "GET", path)
    log.expect(
        "poll_list_contains",
        any(item.get("id") == poll_id for item in (listed.json() or {}).get("items") or [])
        if listed.status == 200 else False,
        "GET", path, _status(listed), "poll visible to another member",
    )

    non_member = client.get(path, token=fx.user_c["token"])
    log.expect_status("poll_non_member", non_member, 403, "GET", path)
    log.expect(
        "poll_non_member_code", _error_code(non_member) == "NOT_MEMBER",
        "GET", path, _status(non_member), f"code={_error_code(non_member)}",
    )

    direct_poll = client.post(
        f"/api/v1/conversations/{fx.direct_id}/polls", token=token_a,
        body={"question": "Direct?", "options": ["A", "B"]},
    )
    log.expect_status("poll_direct_rejected", direct_poll, 400, "POST",
                      f"/api/v1/conversations/{fx.direct_id}/polls")
    log.expect(
        "poll_direct_code", _error_code(direct_poll) == "NOT_A_GROUP",
        "POST", f"/api/v1/conversations/{fx.direct_id}/polls",
        _status(direct_poll), f"code={_error_code(direct_poll)}",
    )

    vote_path = f"{path}/{poll_id}/vote"
    voted = client.put(vote_path, token=token_b, body={"optionIds": [yes["id"]]})
    log.expect_status("poll_vote", voted, 200, "PUT", vote_path)
    voted_poll = voted.json() or {}
    yes_after = poll_option_by_label(voted_poll, "Yes")
    log.expect(
        "poll_vote_counted",
        voted_poll.get("myOptionIds") == [yes["id"]]
        and voted_poll.get("voterCount") == 1
        and yes_after is not None and yes_after.get("voteCount") == 1
        and fx.user_b["id"] in (yes_after.get("voterIds") or []),
        "PUT", vote_path, _status(voted), "selection, count and voter id recorded",
    )
    first_version = voted_poll.get("version")

    changed = client.put(vote_path, token=token_b, body={"optionIds": [no["id"]]})
    log.expect_status("poll_vote_change", changed, 200, "PUT", vote_path)
    changed_poll = changed.json() or {}
    log.expect(
        "poll_vote_change_counts",
        changed_poll.get("myOptionIds") == [no["id"]]
        and (poll_option_by_label(changed_poll, "Yes") or {}).get("voteCount") == 0
        and (poll_option_by_label(changed_poll, "No") or {}).get("voteCount") == 1
        and changed_poll.get("version", 0) > (first_version or 0),
        "PUT", vote_path, _status(changed), "selection replaced and version advanced",
    )

    retracted = client.delete(vote_path, token=token_b)
    log.expect_status("poll_retract", retracted, 200, "DELETE", vote_path)
    retracted_poll = retracted.json() or {}
    log.expect(
        "poll_retract_empty",
        retracted_poll.get("myOptionIds") == [] and retracted_poll.get("voterCount") == 0,
        "DELETE", vote_path, _status(retracted), "selection and voter count cleared",
    )

    # Vote again, then an ordinary member cannot close/delete.
    client.put(vote_path, token=token_b, body={"optionIds": [yes["id"]]})
    foreign_close = client.post(f"{path}/{poll_id}/close", token=token_b)
    log.expect_status("poll_close_foreign", foreign_close, 403, "POST",
                      f"{path}/{{pollId}}/close")
    log.expect(
        "poll_close_foreign_code", _error_code(foreign_close) == "NOT_ADMIN",
        "POST", f"{path}/{{pollId}}/close", _status(foreign_close),
        f"code={_error_code(foreign_close)}",
    )
    foreign_delete = client.delete(f"{path}/{poll_id}", token=token_b)
    log.expect_status("poll_delete_foreign", foreign_delete, 403, "DELETE",
                      f"{path}/{{pollId}}")

    closed = client.post(f"{path}/{poll_id}/close", token=token_a)
    log.expect_status("poll_close", closed, 200, "POST", f"{path}/{{pollId}}/close")
    closed_poll = closed.json() or {}
    log.expect(
        "poll_closed_status", closed_poll.get("status") == "CLOSED",
        "POST", f"{path}/{{pollId}}/close", _status(closed), "status=CLOSED",
    )
    closed_version = closed_poll.get("version")

    blocked = client.put(vote_path, token=token_b, body={"optionIds": [no["id"]]})
    log.expect_status("poll_vote_after_close", blocked, 409, "PUT", vote_path)
    log.expect(
        "poll_vote_after_close_code", _error_code(blocked) == "POLL_CLOSED",
        "PUT", vote_path, _status(blocked), f"code={_error_code(blocked)}",
    )
    same = client.put(vote_path, token=token_b, body={"optionIds": [yes["id"]]})
    log.expect_status("poll_vote_after_close_same", same, 200, "PUT", vote_path)
    log.expect(
        "poll_vote_after_close_same_version",
        (same.json() or {}).get("version") == closed_version,
        "PUT", vote_path, _status(same), "identical selection stays version-stable",
    )

    changes = client.get(f"{path}/changes", token=token_a, params={"since": 0})
    log.expect_status("poll_changes", changes, 200, "GET", f"{path}/changes")
    change_items = (changes.json() or {}).get("items") or [] if changes.status == 200 else []
    versions = [item.get("version") for item in change_items]
    log.expect(
        "poll_changes_shape",
        versions == sorted(versions) and any(item.get("pollId") == poll_id for item in change_items),
        "GET", f"{path}/changes", _status(changes), "ascending versions include the poll",
    )
    next_since = (changes.json() or {}).get("nextSince", 0) if changes.status == 200 else 0
    client.delete(f"{path}/{poll_id}", token=token_a)

    # Anonymous poll: no member, including the creator, sees voterIds.
    anonymous = client.post(
        path, token=token_a,
        body={
            "question": "E2E anonymous?",
            "options": ["Alpha", "Beta"],
            "anonymous": True,
        },
    )
    log.expect_status("poll_anonymous_create", anonymous, 201, "POST", path)
    if anonymous.status != 201:
        return
    anon = anonymous.json()
    anon_id = anon["id"]
    alpha = poll_option_by_label(anon, "Alpha")
    if alpha is None:
        log.expect("poll_anonymous_option", False, "POST", path, created.status,
                   "anonymous poll options missing")
        return
    anon_vote_path = f"{path}/{anon_id}/vote"
    anon_vote = client.put(anon_vote_path, token=token_b, body={"optionIds": [alpha["id"]]})
    log.expect_status("poll_anonymous_vote", anon_vote, 200, "PUT", anon_vote_path)
    anon_as_creator = client.get(f"{path}/{anon_id}", token=token_a)
    log.expect_status("poll_anonymous_get", anon_as_creator, 200, "GET", f"{path}/{{pollId}}")
    anon_body = anon_as_creator.json() or {} if anon_as_creator.status == 200 else {}
    log.expect(
        "poll_anonymous_no_leak",
        anon_as_creator.status == 200
        and anon_body.get("anonymous") is True
        and anon_body.get("voterCount") == 1
        and anonymous_poll_leaks_nobody(anon_body, fx.user_b["id"]),
        "GET", f"{path}/{{pollId}}", _status(anon_as_creator),
        "voterIds omitted and voter id absent",
    )

    anon_deleted = client.delete(f"{path}/{anon_id}", token=token_a)
    log.expect_status("poll_anonymous_delete", anon_deleted, 204, "DELETE",
                      f"{path}/{{pollId}}")
    gone = client.get(f"{path}/{anon_id}", token=token_a)
    log.expect_status("poll_deleted_get", gone, 404, "GET", f"{path}/{{pollId}}")
    deleted_feed = client.get(
        f"{path}/changes", token=token_a, params={"since": next_since}
    )
    log.expect_status("poll_changes_after_delete", deleted_feed, 200, "GET",
                      f"{path}/changes")
    tombstone = next(
        (item for item in (deleted_feed.json() or {}).get("items") or []
         if item.get("pollId") == anon_id),
        None,
    )
    log.expect(
        "poll_changes_tombstone",
        tombstone is not None and tombstone.get("deleted") is True,
        "GET", f"{path}/changes", _status(deleted_feed),
        "deleted poll reported as a tombstone",
    )


# --- AI opt-out ---------------------------------------------------------------

def scenario_ai_opt_out(log: CheckLog, client: Client, fx: Fixtures) -> None:
    conv = fx.direct_id
    path = f"/api/v1/conversations/{conv}/ai-opt-out"
    token_a = fx.user_a["token"]
    token_b = fx.user_b["token"]

    initial = client.get(path, token=token_a)
    log.expect_status("ai_opt_out_initial", initial, 200, "GET", path)
    log.expect(
        "ai_opt_out_initial_value", (initial.json() or {}).get("aiOptOut") is False,
        "GET", path, _status(initial), "default false",
    )

    opted_out = client.put(path, token=token_a)
    log.expect_status("ai_opt_out_set", opted_out, 200, "PUT", path)
    log.expect(
        "ai_opt_out_set_value",
        (opted_out.json() or {}).get("aiOptOut") is True
        and (opted_out.json() or {}).get("conversationId") == conv,
        "PUT", path, _status(opted_out), "aiOptOut=true for the caller",
    )
    read_back = client.get(path, token=token_a)
    log.expect(
        "ai_opt_out_read_back", (read_back.json() or {}).get("aiOptOut") is True,
        "GET", path, _status(read_back), "GET reflects the opt-out",
    )
    entry_a = inbox_entry(client, token_a, conv)
    entry_b = inbox_entry(client, token_b, conv)
    log.expect(
        "ai_opt_out_inbox_scoped",
        entry_a is not None and entry_a.get("aiOptOut") is True
        and entry_b is not None and entry_b.get("aiOptOut") is False,
        "GET", "/api/v1/conversations", 200, "inbox flag is caller-scoped",
    )

    non_member = client.put(path, token=fx.user_c["token"])
    log.expect_status("ai_opt_out_non_member", non_member, 403, "PUT", path)

    cleared = client.delete(path, token=token_a)
    log.expect_status("ai_opt_out_clear", cleared, 200, "DELETE", path)
    log.expect(
        "ai_opt_out_clear_value", (cleared.json() or {}).get("aiOptOut") is False,
        "DELETE", path, _status(cleared), "aiOptOut=false",
    )


# --- conversation pin and cap -------------------------------------------------

def scenario_pins(log: CheckLog, client: Client, fx: Fixtures) -> None:
    token_a = fx.user_a["token"]
    base = "/api/v1/conversations"

    if len(fx.pin_groups) < 6:
        log.skip("pin_setup", "SETUP", base, "six conversations required", required=True)
        return

    for index, conv in enumerate(fx.pin_groups[:5]):
        response = client.put(f"{base}/{conv}/pin", token=token_a)
        log.expect_status(f"pin_{index + 1}", response, 204, "PUT", f"{base}/{{id}}/pin")

    over = client.put(f"{base}/{fx.pin_groups[5]}/pin", token=token_a)
    log.expect_status("pin_cap_sixth", over, 409, "PUT", f"{base}/{{id}}/pin")
    log.expect(
        "pin_cap_code", _error_code(over) == "CONVERSATION_PIN_LIMIT",
        "PUT", f"{base}/{{id}}/pin", _status(over), f"code={_error_code(over)}",
    )

    repin = client.put(f"{base}/{fx.pin_groups[0]}/pin", token=token_a)
    log.expect_status("pin_repin_at_cap", repin, 204, "PUT", f"{base}/{{id}}/pin")

    entries = inbox(client, token_a)
    log.expect_status("pin_inbox", entries, 200, "GET", base)
    inbox_rows = entries.json() or [] if entries.status == 200 else []
    log.expect(
        "pin_inbox_order",
        len(inbox_rows) >= 5 and all(row.get("pinned") is True for row in inbox_rows[:5]),
        "GET", base, _status(entries), "pinned conversations sort first",
    )

    for conv in fx.pin_groups[:5]:
        client.delete(f"{base}/{conv}/pin", token=token_a)

    after_unpin = client.put(f"{base}/{fx.pin_groups[5]}/pin", token=token_a)
    log.expect_status("pin_after_slot_freed", after_unpin, 204, "PUT", f"{base}/{{id}}/pin")
    client.delete(f"{base}/{fx.pin_groups[5]}/pin", token=token_a)

    non_member = client.put(f"{base}/{fx.direct_id}/pin", token=fx.user_c["token"])
    log.expect_status("pin_non_member", non_member, 403, "PUT", f"{base}/{{id}}/pin")


# --- seen privacy over STOMP --------------------------------------------------

def _has_read_event(frames: Iterable[tuple[str, dict[str, str], str]],
                    user_id: str) -> bool:
    for command, _headers, body in frames:
        if command != "MESSAGE" or "MESSAGE_READ" not in body:
            continue
        try:
            payload = json.loads(body).get("payload") or {}
        except (ValueError, TypeError):
            continue
        if payload.get("userId") == user_id:
            return True
    return False


def scenario_seen_privacy(log: CheckLog, client: Client, fx: Fixtures, ws_url: str) -> None:
    path = f"/api/v1/conversations/{fx.direct_id}/read"
    if P0StompConnection is None:
        log.skip("seen_privacy", "STOMP", path, "websocket-client is not installed",
                 required=True)
        return
    stomp_a = None
    stomp_b = None
    try:
        stomp_a = P0StompConnection(ws_url, fx.user_a["token"], label="SeenA")
        stomp_b = P0StompConnection(ws_url, fx.user_b["token"], label="SeenB")
        stomp_a.subscribe(f"/topic/conversations.{fx.direct_id}")
        stomp_b.subscribe(f"/topic/conversations.{fx.direct_id}")

        sent = send_message(client, fx.user_a["token"], fx.direct_id, content="seen e2e 1")
        log.expect_status("seen_send_message", sent, 201, "POST",
                          f"/api/v1/conversations/{fx.direct_id}/messages")
        if sent.status not in (200, 201):
            return
        fx.track(sent)
        seq1 = sent.json()["seq"]
        time.sleep(0.5)
        stomp_a.drain(0.5)
        stomp_b.drain(0.5)

        hidden = client.patch(
            "/api/v1/users/me/settings", token=fx.user_a["token"],
            body={"showSeen": False},
        )
        log.expect_status("seen_hide_setting", hidden, 200, "PATCH",
                          "/api/v1/users/me/settings")
        log.expect(
            "seen_hide_value", (hidden.json() or {}).get("showSeen") is False,
            "PATCH", "/api/v1/users/me/settings", _status(hidden), "showSeen=false",
        )
        hidden_applied = hidden.status == 200 and (hidden.json() or {}).get("showSeen") is False

        # B read must not reach hidden A.
        stomp_b.send_read_ack(fx.direct_id, seq1)
        leaked_to_hidden = stomp_a.drain(2.0)
        log.expect(
            "seen_hidden_recipient_silent",
            hidden_applied and not _has_read_event(leaked_to_hidden, fx.user_b["id"]),
            "STOMP", f"/topic/conversations.{fx.direct_id}", "no frame",
            "hidden member received no peer read receipt",
        )

        # A read must not be broadcast while A hides seen.
        marked = client.post(path, token=fx.user_a["token"], body={"upToSeq": seq1})
        log.expect_status("seen_hidden_read_rest", marked, 204, "POST", path)
        leaked_to_b = stomp_b.drain(2.0)
        log.expect(
            "seen_hidden_sender_silent",
            hidden_applied and marked.status == 204
            and not _has_read_event(leaked_to_b, fx.user_a["id"]),
            "STOMP", f"/topic/conversations.{fx.direct_id}", "no frame",
            "hidden sender's read receipt not broadcast",
        )
        entry = inbox_entry(client, fx.user_a["token"], fx.direct_id)
        log.expect(
            "seen_hidden_own_watermark",
            hidden_applied and entry is not None and entry.get("lastReadSeq") == seq1,
            "GET", "/api/v1/conversations", 200,
            "hidden member's own lastReadSeq still advances",
        )

        # Restore visibility: a subsequent read is broadcast to the peer.
        shown = client.patch(
            "/api/v1/users/me/settings", token=fx.user_a["token"],
            body={"showSeen": True},
        )
        log.expect_status("seen_show_setting", shown, 200, "PATCH",
                          "/api/v1/users/me/settings")
        shown_applied = shown.status == 200 and (shown.json() or {}).get("showSeen") is True
        if not shown_applied:
            log.expect(
                "seen_visible_receipt", False,
                "STOMP", f"/topic/conversations.{fx.direct_id}", "not applied",
                "showSeen could not be restored; delivery check skipped",
            )
            return
        sent2 = send_message(client, fx.user_b["token"], fx.direct_id, content="seen e2e 2")
        log.expect_status("seen_send_second", sent2, 201, "POST",
                          f"/api/v1/conversations/{fx.direct_id}/messages")
        if sent2.status not in (200, 201):
            return
        fx.track(sent2, fx.user_b["token"])
        seq2 = sent2.json()["seq"]
        time.sleep(0.5)
        stomp_b.drain(0.5)
        stomp_a.drain(0.5)
        visible_read = client.post(path, token=fx.user_a["token"], body={"upToSeq": seq2})
        log.expect_status("seen_visible_read_rest", visible_read, 204, "POST", path)
        stomp_b.wait_for(
            lambda frame: _has_read_event([frame], fx.user_a["id"]),
            timeout=5.0,
            desc="MESSAGE_READ for visible reader",
        )
        log.expect(
            "seen_visible_receipt", True,
            "STOMP", f"/topic/conversations.{fx.direct_id}", "MESSAGE_READ",
            "visible reader's receipt delivered to the peer",
        )
    except Exception as exc:
        log.expect("seen_privacy_exception", False, "STOMP",
                   f"/topic/conversations.{fx.direct_id}", "error", safe_exception(exc))
    finally:
        client.patch(
            "/api/v1/users/me/settings", token=fx.user_a["token"],
            body={"showSeen": True},
        )
        if stomp_a:
            stomp_a.close()
        if stomp_b:
            stomp_b.close()


# --- revoked token over STOMP -------------------------------------------------

def _wait_for_socket_close(connection, timeout: float = 6.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        connection.ws.settimeout(max(0.1, deadline - time.time()))
        try:
            chunk = connection.ws.recv()
        except Exception as exc:
            # A read timeout means the socket is still open; a closed/reset
            # socket raises a transport error (websocket-client's closed
            # exception is not a TimeoutError subclass).
            if isinstance(exc, TimeoutError) or "timeout" in type(exc).__name__.lower():
                return False
            return True
        if chunk in (None, "", b""):
            return True
    return False


def scenario_revoked_token(log: CheckLog, client: Client, fx: Fixtures, ws_url: str) -> None:
    if P0StompConnection is None:
        log.skip("revoked_stomp", "STOMP", "/ws", "websocket-client is not installed",
                 required=True)
        return
    connection = None
    try:
        connection = P0StompConnection(ws_url, fx.user_d["token"], label="Revoked")
        log.expect("revoked_connect_before", True, "STOMP", "/ws", "CONNECTED",
                   "throwaway session connected")
        # The access token's iat and the revocation cutoff are second-precision;
        # wait one clock tick so the token is strictly older than the cutoff.
        time.sleep(1.5)
        logout = client.post("/api/v1/auth/logout-all", token=fx.user_d["token"])
        log.expect_status("revoked_logout_all", logout, 204, "POST",
                          "/api/v1/auth/logout-all")
        log.expect(
            "revoked_existing_session_closed",
            logout.status == 204 and _wait_for_socket_close(connection),
            "STOMP", "/ws", "closed", "existing session closed after revocation",
        )
        rest_after = client.get("/api/v1/users/me", token=fx.user_d["token"])
        log.expect_status("revoked_rest_401", rest_after, 401, "GET", "/api/v1/users/me")
        log.expect(
            "revoked_rest_code",
            _error_code(rest_after) in ("TOKEN_INVALID", "TOKEN_EXPIRED"),
            "GET", "/api/v1/users/me", _status(rest_after), f"code={_error_code(rest_after)}",
        )

        rejected = False
        try:
            stale = P0StompConnection(ws_url, fx.user_d["token"], label="Stale")
            stale.close()
        except Exception:
            rejected = True
        log.expect(
            "revoked_reconnect_rejected", rejected and rest_after.status == 401,
            "STOMP", "/ws", "rejected" if rejected else "CONNECTED",
            "revoked access token cannot open a STOMP session",
        )
    except Exception as exc:
        log.expect("revoked_stomp_exception", False, "STOMP", "/ws", "error",
                   safe_exception(exc))
    finally:
        if connection:
            connection.close()


# --- cleanup ------------------------------------------------------------------

def cleanup(log: CheckLog, client: Client, fx: Fixtures) -> None:
    """Best-effort removal of every artefact the API can delete.

    Accounts and conversations have no delete endpoint; per-member flags,
    reactions, reminders, polls and messages are reverted/soft-deleted.
    """
    try:
        client.patch(
            "/api/v1/users/me/settings", token=fx.user_a["token"],
            body={"showSeen": True},
        )
        client.patch(
            "/api/v1/users/me/settings", token=fx.user_b["token"],
            body={"showSeen": True},
        )
    except Exception:
        pass
    for conv in [fx.direct_id, fx.poll_group_id, *fx.pin_groups]:
        for method, path in (
            ("DELETE", f"/api/v1/conversations/{conv}/pin"),
            ("DELETE", f"/api/v1/conversations/{conv}/archive"),
            ("DELETE", f"/api/v1/conversations/{conv}/mute"),
        ):
            for token in (fx.user_a["token"], fx.user_b["token"]):
                try:
                    client.request(method, path, token=token)
                except Exception:
                    pass
    try:
        client.delete(
            f"/api/v1/conversations/{fx.direct_id}/ai-opt-out",
            token=fx.user_a["token"],
        )
    except Exception:
        pass
    for message_id, token in fx.message_refs:
        try:
            client.delete(
                f"/api/v1/conversations/{fx.direct_id}/messages/{message_id}",
                token=token,
            )
        except Exception:
            pass
    print("CLEANUP best-effort flags/messages reverted; no account-delete endpoint exists")


# --- orchestration ------------------------------------------------------------

def check_server_health(client: Client) -> bool:
    try:
        health = client.get("/actuator/health")
        if health.status in (200, 204):
            return True
    except E2EFailure:
        pass
    try:
        probe = client.post("/api/v1/auth/login", body={})
        return probe.status in (400, 401, 415)
    except E2EFailure:
        return False


def derive_ws_url(base_url: str) -> str:
    if base_url.startswith("https://"):
        return "wss://" + base_url[len("https://"):].rstrip("/") + "/ws"
    if base_url.startswith("http://"):
        return "ws://" + base_url[len("http://"):].rstrip("/") + "/ws"
    return "ws://" + base_url.rstrip("/") + "/ws"


def run_suite(client: Client, log: CheckLog, ws_url: str, *, with_stomp: bool = True,
              pin_groups: int = 6) -> Fixtures | None:
    fx = None
    try:
        fx = setup_fixtures(client, log, pin_groups=pin_groups)
    except Exception as exc:
        log.expect("fixture_setup", False, "SETUP", "/api/v1/auth/register", "error",
                   safe_exception(exc))
        return None

    scenarios = [
        ("1. Archive browser", lambda: scenario_archive(log, client, fx)),
        ("2. Inbox mute/archive flags", lambda: scenario_inbox_flags(log, client, fx)),
        ("3. Reminders", lambda: scenario_reminders(log, client, fx)),
        ("4. Idempotency-Key replay", lambda: scenario_idempotency(log, client, fx)),
        ("5. Group polls", lambda: scenario_polls(log, client, fx)),
        ("6. AI opt-out", lambda: scenario_ai_opt_out(log, client, fx)),
        ("7. Conversation pin cap", lambda: scenario_pins(log, client, fx)),
    ]
    if with_stomp:
        scenarios.append(("8. Seen privacy", lambda: scenario_seen_privacy(
            log, client, fx, ws_url)))
        scenarios.append(("9. Revoked token STOMP", lambda: scenario_revoked_token(
            log, client, fx, ws_url)))

    try:
        for title, scenario in scenarios:
            print(f"\n--- {title} ---")
            try:
                scenario()
            except Exception as exc:
                log.expect(
                    f"{title.split('. ', 1)[-1].lower().replace(' ', '_')}_scenario",
                    False, "SCENARIO", title, "exception", safe_exception(exc),
                )
    finally:
        cleanup(log, client, fx)
    return fx


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="NexaChat live E2E coverage for the new archive/inbox/reminder/poll features"
    )
    parser.add_argument(
        "--base-url",
        default=os.environ.get("API_BASE_URL") or DEFAULT_BASE_URL,
        help="HTTP backend base URL (default: API_BASE_URL or http://localhost:8080)",
    )
    parser.add_argument("--ws-url", default="", help="STOMP WebSocket URL (default derived)")
    parser.add_argument("--timeout", type=float, default=15.0, help="HTTP timeout in seconds")
    parser.add_argument(
        "--allow-skip", action="store_true",
        help="Treat required skips as success (offline machine or absent transport)",
    )
    parser.add_argument(
        "--i-know-this-is-not-prod", action="store_true",
        help="Allow a non-loopback base URL (never point this at production)",
    )
    parser.add_argument(
        "--self-test", action="store_true",
        help="Run the suite against an in-memory mocked transport (no backend)",
    )
    args = parser.parse_args(argv)

    if args.self_test:
        from features_selftest import run_self_test

        return run_self_test()

    base_url = args.base_url.rstrip("/")
    if not is_local_base_url(base_url) and not args.i_know_this_is_not_prod:
        print(
            f"REFUSED: {base_url} is not a loopback URL. "
            "Pass --i-know-this-is-not-prod only for a disposable non-production host."
        )
        return 2

    ws_url = args.ws_url or derive_ws_url(base_url)
    log = CheckLog(allow_skip=args.allow_skip)

    print("=" * 78)
    print("   NEXACHAT NEW-FEATURE LIVE E2E SUITE")
    print(f"   Target: {base_url} (WS: {ws_url})")
    print("=" * 78)

    client = Client(base_url=base_url, timeout=args.timeout)
    if not check_server_health(client):
        print(f"\nNOT_RUN: no backend answering at {base_url}.")
        for name, method, path in (
            ("archive", "GET", "/api/v1/conversations/{id}/archive"),
            ("inbox_flags", "PUT", "/api/v1/conversations/{id}/mute"),
            ("reminders", "POST", "/api/v1/conversations/{id}/reminders"),
            ("polls", "POST", "/api/v1/conversations/{id}/polls"),
            ("ai_opt_out", "PUT", "/api/v1/conversations/{id}/ai-opt-out"),
            ("pins", "PUT", "/api/v1/conversations/{id}/pin"),
            ("seen_privacy", "STOMP", "/ws"),
            ("revoked_token", "STOMP", "/ws"),
        ):
            log.skip(name, method, path, "backend offline", required=True)
        return log.summary()

    run_suite(client, log, ws_url, with_stomp=True)
    return log.summary()


if __name__ == "__main__":
    sys.exit(main())
