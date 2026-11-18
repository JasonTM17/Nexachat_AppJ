#!/usr/bin/env python3
"""Live E2E journey: groups, rejoin visibility, disappearing messages,
contacts/blocks and the E2EE device + activation contract.

Fixture accounts live in the ``+849000007xx`` range with display names prefixed
``Groups E2E``; demo seed users are never touched and access tokens are never
printed. Every scenario is independently runnable (``--only``) and the whole
journey is re-runnable against the same database: content carries a per-run
nonce, device ids/keys are derived deterministically from the phone number, and
each scenario restores the state it changes.

Usage:
    python tools/e2e/journeys/groups_privacy_e2e.py [--base-url URL]
    python tools/e2e/journeys/groups_privacy_e2e.py --list
    python tools/e2e/journeys/groups_privacy_e2e.py --only group_lifecycle

Contract sources: ``docs/architecture/api-contract.md`` (Groups, Messages,
Disappearing messages, Contacts, Safety), ``docs/architecture/e2ee.md`` and the
matching ITs (``GroupFlowIT``, ``InboxAdmissionScopeIT``,
``DisappearingMessagesIT``, ``E2eeFlowIT``, ``E2eeActivationPolicyIT``).
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import re
import sys
import uuid
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from e2e_common import (  # noqa: E402
    Client,
    E2EFailure,
    check,
    direct_conversation,
    ensure_user,
    history,
    main_guard,
    search,
    send_text,
)

DEFAULT_BASE_URL = "http://127.0.0.1:8093"

TAG = "Groups E2E"

PHONE_ADMIN = "+84900000701"
PHONE_MEMBER = "+84900000702"
PHONE_THIRD = "+84900000703"
PHONE_OUTSIDER = "+84900000704"
PHONE_BLOCK_PEER = "+84900000705"
PHONE_STRANGER = "+84900000706"
PHONE_E2EE_PEER = "+84900000707"

# Fixture phone that is never registered: the "unknown user" negative path.
PHONE_UNKNOWN = "+84900000799"

ACCOUNTS = {
    "admin": (PHONE_ADMIN, TAG + " Admin"),
    "member": (PHONE_MEMBER, TAG + " Member"),
    "third": (PHONE_THIRD, TAG + " Third"),
    "outsider": (PHONE_OUTSIDER, TAG + " Outsider"),
    "blockPeer": (PHONE_BLOCK_PEER, TAG + " BlockPeer"),
    "stranger": (PHONE_STRANGER, TAG + " Stranger"),
    "e2eePeer": (PHONE_E2EE_PEER, TAG + " E2eePeer"),
}

# Allow-listed disappearing timers (DisappearingMessagesService.ALLOWED_SECONDS).
DISAPPEARING_ALLOWED = (0, 3600, 86400, 604800, 2592000, 7776000)
DISAPPEARING_SMALLEST = 3600

# Fixed namespace so a phone always maps to the same device id across runs.
DEVICE_NAMESPACE = uuid.UUID("2f6b1d0e-6a1c-5a3e-9d47-6f0c2b8a4e11")

_INSTANT_RE = re.compile(
    r"^(?P<base>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})"
    r"(?:\.(?P<frac>\d+))?"
    r"(?P<offset>Z|[+-]\d{2}:\d{2})?$"
)


class NotRun(Exception):
    """A scenario whose prerequisite capability is absent on this stack."""


# --------------------------------------------------------------------- helpers


def run_nonce() -> str:
    """Short unique token so every run's content and titles differ."""
    return uuid.uuid4().hex[:8]


def parse_instant(value: str) -> datetime:
    """Parse a Java ``Instant`` (``...Z`` with 0-9 fractional digits)."""
    match = _INSTANT_RE.match(str(value).strip())
    if match is None:
        raise ValueError(f"not an ISO-8601 instant: {value!r}")
    fraction = (match.group("frac") or "")[:6].ljust(6, "0")
    offset = match.group("offset") or "Z"
    if offset == "Z":
        offset = "+00:00"
    return datetime.fromisoformat(f"{match.group('base')}.{fraction}{offset}")


def device_public_key(phone: str, salt: str = "primary") -> str:
    """Deterministic base64url-no-padding 32-byte X25519-shaped public key.

    The server only validates the encoding and length (``E2eeDeviceService``),
    so a derived key is a valid fixture and keeps re-registration idempotent.
    """
    digest = hashlib.sha256(f"nexachat-e2e-e2ee|{phone}|{salt}".encode("utf-8")).digest()
    return base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


def device_id_for(phone: str, salt: str = "primary") -> str:
    return str(uuid.uuid5(DEVICE_NAMESPACE, f"nexachat-e2e-e2ee|{phone}|{salt}"))


def wrapped_key_for(device_id: str) -> str:
    """Deterministic base64url wrapped content key (server never unwraps it)."""
    digest = hashlib.sha256(f"nexachat-e2ee-wrap|{device_id}".encode("utf-8")).digest()
    return base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


def encrypted_content(nonce: str) -> str:
    """``e2ee1:``-prefixed opaque payload; the server validates the prefix only."""
    digest = hashlib.sha256(f"nexachat-e2ee-content|{nonce}".encode("utf-8")).digest()
    return "e2ee1:" + base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


def envelopes_for(device_ids) -> list:
    return [
        {"deviceId": str(device_id), "wrappedKey": wrapped_key_for(str(device_id))}
        for device_id in device_ids
    ]


def assert_error(response, status: int, code: str) -> None:
    check(
        response.status == status,
        f"expected HTTP {status}, got {response.status} ({response.text[:200]})",
    )
    check(
        response.error_code() == code,
        f"expected error {code}, got {response.error_code()} ({response.text[:200]})",
    )


def member_roles(payload: dict) -> dict:
    return {
        str(member.get("userId")): member.get("role")
        for member in payload.get("members") or []
    }


def member_ids(payload: dict) -> list:
    return [str(member.get("userId")) for member in payload.get("members") or []]


def has_expiry(message: dict) -> bool:
    return message.get("expiresAt") is not None


def expiry_offset_seconds(message: dict):
    """``expiresAt - createdAt`` in seconds, or ``None`` when either is absent."""
    expires_at = message.get("expiresAt")
    created_at = message.get("createdAt")
    if not expires_at or not created_at:
        return None
    return (parse_instant(expires_at) - parse_instant(created_at)).total_seconds()


def page_contents(page: dict) -> list:
    return [item.get("content") or "" for item in page.get("items") or []]


def page_contains(page: dict, token: str) -> bool:
    return any(token in content for content in page_contents(page))


def page_ids(page: dict) -> set:
    return {str(item.get("id")) for item in page.get("items") or []}


def find_message(page: dict, message_id: str):
    for item in page.get("items") or []:
        if str(item.get("id")) == str(message_id):
            return item
    return None


def inbox_entry(payload, conversation_id: str):
    for entry in payload or []:
        if str(entry.get("id")) == str(conversation_id):
            return entry
    return None


def blocked_user_ids(payload: dict) -> set:
    return {str(item.get("userId")) for item in payload.get("items") or []}


def contact_user_ids(payload) -> set:
    return {str(item.get("contactUserId")) for item in payload or []}


def device_ids_of(state: dict) -> list:
    return [str(device.get("deviceId")) for device in state.get("devices") or []]


def select_scenarios(names, only):
    """Resolve ``--only`` against the ordered scenario list."""
    if not only:
        return list(names)
    requested = [part.strip() for part in only.split(",") if part.strip()]
    unknown = [name for name in requested if name not in names]
    if unknown:
        raise ValueError("unknown scenario(s): " + ", ".join(unknown))
    return [name for name in names if name in requested]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="NexaChat groups / rejoin / disappearing / contacts / e2ee E2E"
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument(
        "--list", action="store_true", help="print the scenario names and exit"
    )
    parser.add_argument(
        "--only", default=None, help="comma-separated scenario names to run"
    )
    return parser


def parse_args(argv=None):
    return build_parser().parse_args(argv)


# ------------------------------------------------------------------ API wrappers


def create_group(client: Client, token: str, title: str, member_ids_list) -> dict:
    response = client.post(
        "/api/v1/conversations/group",
        token=token,
        body={"title": title, "memberIds": list(member_ids_list)},
    )
    check(response.status == 201, f"create group expected 201, got {response}")
    return response.json()


def patch_group(client: Client, token: str, conversation_id: str, body: dict):
    return client.patch(
        f"/api/v1/conversations/{conversation_id}", token=token, body=body
    )


def add_members(client: Client, token: str, conversation_id: str, user_ids):
    return client.post(
        f"/api/v1/conversations/{conversation_id}/members",
        token=token,
        body={"userIds": list(user_ids)},
    )


def remove_member(client: Client, token: str, conversation_id: str, user_id: str):
    return client.delete(
        f"/api/v1/conversations/{conversation_id}/members/{user_id}", token=token
    )


def leave_group(client: Client, token: str, conversation_id: str):
    return client.post(
        f"/api/v1/conversations/{conversation_id}/leave", token=token
    )


def conversation_history(client: Client, token: str, conversation_id: str, **params):
    return client.get(
        f"/api/v1/conversations/{conversation_id}/messages", token=token, params=params
    )


def set_disappearing(client: Client, token: str, conversation_id: str, seconds: int):
    return client.put(
        f"/api/v1/conversations/{conversation_id}/disappearing",
        token=token,
        body={"seconds": seconds},
    )


def send_message(client: Client, token: str, conversation_id: str, content: str, **extra):
    body = {
        "clientMessageId": str(uuid.uuid4()),
        "type": "TEXT",
        "content": content,
        "attachmentIds": [],
    }
    body.update(extra)
    return client.post(
        f"/api/v1/conversations/{conversation_id}/messages", token=token, body=body
    )


def inbox(client: Client, token: str):
    response = client.get("/api/v1/conversations", token=token)
    check(response.status == 200, f"inbox expected 200, got {response}")
    return response.json()


def e2ee_state(client: Client, token: str, conversation_id: str):
    return client.get(f"/api/v1/conversations/{conversation_id}/e2ee", token=token)


def set_e2ee(client: Client, token: str, conversation_id: str, enabled: bool):
    return client.put(
        f"/api/v1/conversations/{conversation_id}/e2ee",
        token=token,
        body={"enabled": enabled},
    )


def register_device(client: Client, token: str, device_id: str, public_key: str):
    return client.put(
        f"/api/v1/e2ee/devices/{device_id}", token=token, body={"publicKey": public_key}
    )


def revoke_device(client: Client, token: str, device_id: str):
    return client.delete(f"/api/v1/e2ee/devices/{device_id}", token=token)


def block_user(client: Client, token: str, user_id: str):
    return client.post(f"/api/v1/users/{user_id}/block", token=token)


def unblock_user(client: Client, token: str, user_id: str):
    return client.delete(f"/api/v1/users/{user_id}/block", token=token)


def block_state(client: Client, token: str, user_id: str):
    return client.get(f"/api/v1/users/{user_id}/block", token=token)


def blocked_list(client: Client, token: str):
    return client.get("/api/v1/users/blocked", token=token)


def add_contact(client: Client, token: str, **body):
    return client.post("/api/v1/contacts", token=token, body=body)


def list_contacts(client: Client, token: str):
    return client.get("/api/v1/contacts", token=token)


def update_alias(client: Client, token: str, user_id: str, alias):
    return client.patch(
        f"/api/v1/contacts/{user_id}", token=token, body={"aliasName": alias}
    )


def delete_contact(client: Client, token: str, user_id: str):
    return client.delete(f"/api/v1/contacts/{user_id}", token=token)


def ensure_device(client: Client, token: str, phone: str, salt: str = "primary") -> str:
    """Idempotent device registration; returns the device id."""
    device_id = device_id_for(phone, salt)
    response = register_device(client, token, device_id, device_public_key(phone, salt))
    check(response.status == 200, f"register device expected 200, got {response}")
    payload = response.json()
    check(
        str(payload.get("deviceId")) == device_id,
        f"device PUT returned id {payload.get('deviceId')!r}",
    )
    check(
        payload.get("publicKey") == device_public_key(phone, salt),
        "device PUT did not echo the registered public key",
    )
    return device_id


# --------------------------------------------------------------------- session


class Session:
    """Lazily registers the fixture accounts and caches their tokens."""

    def __init__(self, client: Client, nonce: str):
        self.client = client
        self.nonce = nonce
        self._users = {}

    def user(self, key: str) -> dict:
        if key not in self._users:
            phone, name = ACCOUNTS[key]
            data = ensure_user(self.client, phone, name)
            self._users[key] = {
                "key": key,
                "id": data["user"]["id"],
                "token": data["accessToken"],
                "phone": phone,
                "name": name,
            }
        return self._users[key]


# ------------------------------------------------------------------- scenarios


def scenario_group_lifecycle(session: Session) -> None:
    """Create → rename → add a third member; non-admin/outsider refusals."""
    client = session.client
    admin = session.user("admin")
    member = session.user("member")
    third = session.user("third")
    outsider = session.user("outsider")

    title = f"{TAG} lifecycle {session.nonce}"
    group = create_group(client, admin["token"], title, [member["id"]])
    group_id = group["id"]
    check(group.get("type") == "GROUP", f"created type is {group.get('type')!r}")
    check(
        len(group.get("members") or []) == 2,
        f"expected 2 members, got {len(group.get('members') or [])}",
    )
    check(
        set(member_ids(group)) == {admin["id"], member["id"]},
        "created group roster does not match the requested members",
    )
    check(group.get("e2eeEnabled") is False, "a new group must not be e2ee-enabled")
    check(
        group.get("disappearingSeconds") == 0,
        f"a new group timer is {group.get('disappearingSeconds')!r}",
    )

    renamed = f"{TAG} lifecycle renamed {session.nonce}"
    patched = patch_group(client, admin["token"], group_id, {"title": renamed})
    check(patched.status == 200, f"rename expected 200, got {patched}")
    payload = patched.json()
    check(payload.get("title") == renamed, f"rename returned {payload.get('title')!r}")
    check(payload.get("change") == "UPDATED", f"rename change is {payload.get('change')!r}")
    roles = member_roles(payload)
    check(roles.get(admin["id"]) == "ADMIN", f"creator role is {roles.get(admin['id'])!r}")
    check(
        roles.get(member["id"]) == "MEMBER",
        f"listed member role is {roles.get(member['id'])!r}",
    )

    added = add_members(client, admin["token"], group_id, [third["id"]])
    check(added.status == 204, f"add member expected 204, got {added}")
    after_add = patch_group(client, admin["token"], group_id, {"title": renamed})
    check(after_add.status == 200, f"post-add snapshot expected 200, got {after_add}")
    roles = member_roles(after_add.json())
    check(len(roles) == 3, f"expected 3 members after the add, got {len(roles)}")
    check(roles.get(third["id"]) == "MEMBER", f"added role is {roles.get(third['id'])!r}")

    readd = add_members(client, admin["token"], group_id, [third["id"]])
    check(readd.status == 204, f"idempotent re-add expected 204, got {readd}")
    still = patch_group(client, admin["token"], group_id, {"title": renamed})
    check(
        len(member_roles(still.json())) == 3,
        "idempotent re-add changed the roster",
    )

    member_add = add_members(client, member["token"], group_id, [outsider["id"]])
    assert_error(member_add, 403, "NOT_ADMIN")
    member_remove = remove_member(client, member["token"], group_id, third["id"])
    assert_error(member_remove, 403, "NOT_ADMIN")
    member_patch = patch_group(client, member["token"], group_id, {"title": "nope"})
    assert_error(member_patch, 403, "NOT_ADMIN")

    outsider_patch = patch_group(client, outsider["token"], group_id, {"title": "nope"})
    assert_error(outsider_patch, 403, "NOT_MEMBER")
    outsider_add = add_members(client, outsider["token"], group_id, [outsider["id"]])
    assert_error(outsider_add, 403, "NOT_MEMBER")
    outsider_history = conversation_history(client, outsider["token"], group_id)
    assert_error(outsider_history, 403, "NOT_MEMBER")

    empty_patch = patch_group(client, admin["token"], group_id, {})
    assert_error(empty_patch, 400, "VALIDATION_FAILED")
    blank_title = patch_group(client, admin["token"], group_id, {"title": "   "})
    assert_error(blank_title, 400, "VALIDATION_FAILED")
    long_title = patch_group(client, admin["token"], group_id, {"title": "x" * 81})
    assert_error(long_title, 400, "VALIDATION_FAILED")
    empty_add = add_members(client, admin["token"], group_id, [])
    assert_error(empty_add, 400, "VALIDATION_FAILED")
    unknown_add = add_members(client, admin["token"], group_id, [str(uuid.uuid4())])
    assert_error(unknown_add, 404, "USER_NOT_FOUND")

    blank_create = client.post(
        "/api/v1/conversations/group",
        token=admin["token"],
        body={"title": "  ", "memberIds": [member["id"]]},
    )
    assert_error(blank_create, 400, "VALIDATION_FAILED")
    self_only = client.post(
        "/api/v1/conversations/group",
        token=admin["token"],
        body={"title": f"{TAG} solo {session.nonce}", "memberIds": [admin["id"]]},
    )
    assert_error(self_only, 400, "VALIDATION_FAILED")
    anonymous = client.post(
        "/api/v1/conversations/group",
        body={"title": f"{TAG} anon {session.nonce}", "memberIds": [member["id"]]},
    )
    assert_error(anonymous, 401, "UNAUTHORIZED")

    removed = remove_member(client, admin["token"], group_id, third["id"])
    check(removed.status == 204, f"remove member expected 204, got {removed}")
    third_history = conversation_history(client, third["token"], group_id)
    assert_error(third_history, 403, "NOT_MEMBER")

    sole_admin_leave = leave_group(client, admin["token"], group_id)
    assert_error(sole_admin_leave, 400, "VALIDATION_FAILED")
    sole_admin_self = remove_member(client, admin["token"], group_id, admin["id"])
    assert_error(sole_admin_self, 400, "VALIDATION_FAILED")

    member_leave = leave_group(client, member["token"], group_id)
    check(member_leave.status == 204, f"member leave expected 204, got {member_leave}")
    member_history = conversation_history(client, member["token"], group_id)
    assert_error(member_history, 403, "NOT_MEMBER")


def scenario_rejoin_visibility(session: Session) -> None:
    """Pre-admission history/search stay hidden after a remove + re-add."""
    client = session.client
    admin = session.user("admin")
    member = session.user("member")
    third = session.user("third")
    outsider = session.user("outsider")

    title = f"{TAG} rejoin {session.nonce}"
    group = create_group(client, admin["token"], title, [member["id"], third["id"]])
    group_id = group["id"]

    pre_token = f"PREJOIN{session.nonce}"
    post_token = f"POSTJOIN{session.nonce}"

    send_text(client, admin["token"], group_id, f"{pre_token} sent before the re-admission")
    before = history(client, third["token"], group_id, limit=100)
    check(page_contains(before, pre_token), "member cannot read a message sent while admitted")

    removed = remove_member(client, admin["token"], group_id, third["id"])
    check(removed.status == 204, f"remove member expected 204, got {removed}")

    removed_history = conversation_history(client, third["token"], group_id)
    assert_error(removed_history, 403, "NOT_MEMBER")
    removed_send = send_message(
        client, third["token"], group_id, f"{TAG} removed send {session.nonce}"
    )
    assert_error(removed_send, 403, "NOT_MEMBER")
    removed_search = search(client, third["token"], group_id, pre_token)
    assert_error(removed_search, 403, "NOT_MEMBER")
    check(
        inbox_entry(inbox(client, third["token"]), group_id) is None,
        "a removed member still sees the group in their inbox",
    )

    readded = add_members(client, admin["token"], group_id, [third["id"]])
    check(readded.status == 204, f"re-add expected 204, got {readded}")

    after = history(client, third["token"], group_id, limit=100)
    check(
        not page_contains(after, pre_token),
        "re-added member can read a message sent before their latest admission",
    )
    after_search = search(client, third["token"], group_id, pre_token)
    check(after_search.status == 200, f"re-added search expected 200, got {after_search}")
    check(
        not page_ids(after_search.json()),
        "search leaks a pre-admission message to the re-added member",
    )

    send_text(client, admin["token"], group_id, f"{post_token} sent after the re-admission")
    latest = history(client, third["token"], group_id, limit=100)
    check(
        page_contains(latest, post_token),
        "re-added member cannot read a message sent after their latest admission",
    )
    latest_search = search(client, third["token"], group_id, post_token)
    check(latest_search.status == 200, f"post-admission search expected 200, got {latest_search}")
    check(
        len(latest_search.json().get("items") or []) == 1,
        "post-admission search did not return exactly the new message",
    )

    admin_view = history(client, admin["token"], group_id, limit=100)
    check(
        page_contains(admin_view, pre_token),
        "the admin lost a message they were admitted to",
    )

    outsider_history = conversation_history(client, outsider["token"], group_id)
    assert_error(outsider_history, 403, "NOT_MEMBER")
    outsider_search = search(client, outsider["token"], group_id, pre_token)
    assert_error(outsider_search, 403, "NOT_MEMBER")


def scenario_disappearing_messages(session: Session) -> None:
    """Timer allow-list, insert-time expiry stamping, non-retroactivity, authz."""
    client = session.client
    admin = session.user("admin")
    member = session.user("member")
    outsider = session.user("outsider")

    _, conversation = direct_conversation(client, admin["token"], PHONE_MEMBER)
    conversation_id = conversation["id"]

    reset = set_disappearing(client, admin["token"], conversation_id, 0)
    check(reset.status == 200, f"clear timer expected 200, got {reset}")
    check(
        reset.json().get("disappearingSeconds") == 0,
        f"cleared timer is {reset.json().get('disappearingSeconds')!r}",
    )

    before = send_text(
        client, admin["token"], conversation_id, f"{TAG} before timer {session.nonce}"
    )
    check(not has_expiry(before), "a message sent with the timer off carries expiresAt")

    enabled = set_disappearing(client, admin["token"], conversation_id, DISAPPEARING_SMALLEST)
    check(enabled.status == 200, f"set timer expected 200, got {enabled}")
    check(
        enabled.json().get("disappearingSeconds") == DISAPPEARING_SMALLEST,
        f"timer is {enabled.json().get('disappearingSeconds')!r}",
    )

    after = send_text(
        client, member["token"], conversation_id, f"{TAG} after timer {session.nonce}"
    )
    check(has_expiry(after), "a message sent with the timer on has no expiresAt")
    offset = expiry_offset_seconds(after)
    check(
        offset is not None and abs(offset - DISAPPEARING_SMALLEST) < 0.5,
        f"expiresAt is {offset!r}s after createdAt, expected {DISAPPEARING_SMALLEST}",
    )

    page = history(client, admin["token"], conversation_id, limit=50)
    stamped = find_message(page, after["id"])
    check(stamped is not None, "the expiring message is missing from history")
    check(has_expiry(stamped), "history dropped expiresAt for an expiring message")
    stamped_offset = expiry_offset_seconds(stamped)
    check(
        stamped_offset is not None and abs(stamped_offset - DISAPPEARING_SMALLEST) < 0.5,
        f"history expiresAt offset is {stamped_offset!r}s",
    )
    unstamped = find_message(page, before["id"])
    check(unstamped is not None, "the pre-timer message is missing from history")
    check(
        not has_expiry(unstamped),
        "enabling the timer retroactively stamped an existing message",
    )

    for rejected in (42, 60, 300, -1):
        response = set_disappearing(client, admin["token"], conversation_id, rejected)
        assert_error(response, 400, "VALIDATION_FAILED")

    group = create_group(
        client, admin["token"], f"{TAG} timer group {session.nonce}", [member["id"]]
    )
    group_id = group["id"]
    member_timer = set_disappearing(client, member["token"], group_id, DISAPPEARING_SMALLEST)
    assert_error(member_timer, 403, "NOT_ADMIN")
    outsider_timer = set_disappearing(client, outsider["token"], group_id, DISAPPEARING_SMALLEST)
    assert_error(outsider_timer, 403, "NOT_MEMBER")
    missing = set_disappearing(client, admin["token"], str(uuid.uuid4()), DISAPPEARING_SMALLEST)
    assert_error(missing, 404, "CONVERSATION_NOT_FOUND")
    anonymous = client.put(
        f"/api/v1/conversations/{conversation_id}/disappearing", body={"seconds": 3600}
    )
    assert_error(anonymous, 401, "UNAUTHORIZED")

    group_on = set_disappearing(client, admin["token"], group_id, 86400)
    check(group_on.status == 200, f"admin group timer expected 200, got {group_on}")
    check(
        group_on.json().get("disappearingSeconds") == 86400,
        f"group timer is {group_on.json().get('disappearingSeconds')!r}",
    )
    group_off = set_disappearing(client, admin["token"], group_id, 0)
    check(group_off.status == 200, f"group timer clear expected 200, got {group_off}")
    check(
        group_off.json().get("disappearingSeconds") == 0,
        "group timer did not clear",
    )

    cleared = set_disappearing(client, admin["token"], conversation_id, 0)
    check(cleared.status == 200, f"clear timer expected 200, got {cleared}")
    plain = send_text(
        client, admin["token"], conversation_id, f"{TAG} after clear {session.nonce}"
    )
    check(not has_expiry(plain), "a message sent after clearing the timer carries expiresAt")


def scenario_disappearing_expiry(session: Session) -> None:
    """Expiry purge observation — not reachable inside the run budget."""
    raise NotRun(
        "smallest allow-listed timer is "
        f"{DISAPPEARING_SMALLEST}s (> 60s) and the purge sweep runs every 60s, "
        "so 'message gone from history after expiry' cannot be observed in a "
        "journey run; scenario_disappearing_messages asserts the expiresAt "
        "metadata instead"
    )


def scenario_contacts_and_blocks(session: Session) -> None:
    """Contacts CRUD + directed blocks on channel creation and direct sends."""
    client = session.client
    admin = session.user("admin")
    block_peer = session.user("blockPeer")
    stranger = session.user("stranger")

    delete_contact(client, admin["token"], block_peer["id"])
    added = add_contact(client, admin["token"], phone=PHONE_BLOCK_PEER)
    check(added.status == 201, f"add contact expected 201, got {added}")
    contact = added.json()
    check(
        str(contact.get("contactUserId")) == block_peer["id"],
        f"contactUserId is {contact.get('contactUserId')!r}",
    )
    check(contact.get("phone") == PHONE_BLOCK_PEER, f"contact phone is {contact.get('phone')!r}")
    check(
        contact.get("displayName") == block_peer["name"],
        f"contact displayName is {contact.get('displayName')!r}",
    )
    check(contact.get("aliasName") is None, "a fresh contact already has an alias")

    duplicate = add_contact(client, admin["token"], phone=PHONE_BLOCK_PEER)
    assert_error(duplicate, 409, "ALREADY_CONTACT")
    self_add = add_contact(client, admin["token"], phone=PHONE_ADMIN)
    assert_error(self_add, 400, "VALIDATION_FAILED")
    unknown_add = add_contact(client, admin["token"], phone=PHONE_UNKNOWN)
    assert_error(unknown_add, 404, "USER_NOT_FOUND")
    both = add_contact(
        client, admin["token"], phone=PHONE_BLOCK_PEER, email="groups-e2e@example.com"
    )
    assert_error(both, 400, "VALIDATION_FAILED")
    neither = add_contact(client, admin["token"])
    assert_error(neither, 400, "VALIDATION_FAILED")

    listed = list_contacts(client, admin["token"])
    check(listed.status == 200, f"list contacts expected 200, got {listed}")
    check(
        block_peer["id"] in contact_user_ids(listed.json()),
        "the added contact is missing from GET /contacts",
    )

    alias = update_alias(client, admin["token"], block_peer["id"], f"Alias {session.nonce}")
    check(alias.status == 200, f"alias update expected 200, got {alias}")
    check(
        alias.json().get("aliasName") == f"Alias {session.nonce}",
        f"alias is {alias.json().get('aliasName')!r}",
    )
    blank_alias = update_alias(client, admin["token"], block_peer["id"], "   ")
    assert_error(blank_alias, 400, "VALIDATION_FAILED")
    cleared = update_alias(client, admin["token"], block_peer["id"], None)
    check(cleared.status == 200, f"alias clear expected 200, got {cleared}")
    check(cleared.json().get("aliasName") is None, "alias was not cleared")

    # --- block on channel creation: no direct conversation exists for this pair
    unblock_user(client, admin["token"], stranger["id"])
    blocked = block_user(client, admin["token"], stranger["id"])
    check(blocked.status == 204, f"block expected 204, got {blocked}")
    probe = block_state(client, admin["token"], stranger["id"])
    check(probe.status == 200, f"block probe expected 200, got {probe}")
    check(probe.json().get("blocked") is True, "block probe did not report the block")
    page = blocked_list(client, admin["token"])
    check(page.status == 200, f"blocked list expected 200, got {page}")
    check(
        stranger["id"] in blocked_user_ids(page.json()),
        "the blocked user is missing from GET /users/blocked",
    )

    blocked_start = client.post(
        "/api/v1/conversations/direct", token=stranger["token"], body={"phone": PHONE_ADMIN}
    )
    assert_error(blocked_start, 404, "USER_NOT_FOUND")
    own_start = client.post(
        "/api/v1/conversations/direct", token=admin["token"], body={"phone": PHONE_STRANGER}
    )
    assert_error(own_start, 403, "BLOCKED")
    self_block = block_user(client, admin["token"], admin["id"])
    assert_error(self_block, 400, "VALIDATION_FAILED")
    unknown_block = block_user(client, admin["token"], str(uuid.uuid4()))
    assert_error(unknown_block, 404, "USER_NOT_FOUND")

    unblocked = unblock_user(client, admin["token"], stranger["id"])
    check(unblocked.status == 204, f"unblock expected 204, got {unblocked}")
    probe_after = block_state(client, admin["token"], stranger["id"])
    check(probe_after.status == 200, f"block probe expected 200, got {probe_after}")
    check(probe_after.json().get("blocked") is False, "unblock did not clear the block")

    # --- block on an existing direct chat: typed refusal + peer suppression
    _, conversation = direct_conversation(client, admin["token"], PHONE_BLOCK_PEER)
    conversation_id = conversation["id"]
    unblock_user(client, admin["token"], block_peer["id"])
    blocked = block_user(client, admin["token"], block_peer["id"])
    check(blocked.status == 204, f"block expected 204, got {blocked}")

    refused = send_message(
        client, admin["token"], conversation_id, f"{TAG} blocked send {session.nonce}"
    )
    assert_error(refused, 403, "BLOCKED")

    suppressed = send_message(
        client, block_peer["token"], conversation_id, f"{TAG} suppressed {session.nonce}"
    )
    check(
        suppressed.status == 201,
        f"a blocked sender's send must look accepted, got {suppressed}",
    )
    suppressed_id = suppressed.json()["id"]
    own_view = history(client, block_peer["token"], conversation_id, limit=50)
    check(
        suppressed_id in page_ids(own_view),
        "the blocked sender cannot read their own suppressed message",
    )
    blocker_view = history(client, admin["token"], conversation_id, limit=50)
    check(
        suppressed_id not in page_ids(blocker_view),
        "a peer-suppressed message reached the blocker",
    )

    unblocked = unblock_user(client, admin["token"], block_peer["id"])
    check(unblocked.status == 204, f"unblock expected 204, got {unblocked}")
    restored = send_message(
        client, admin["token"], conversation_id, f"{TAG} after unblock {session.nonce}"
    )
    check(restored.status == 201, f"send after unblock expected 201, got {restored}")
    restored_id = restored.json()["id"]
    peer_view = history(client, block_peer["token"], conversation_id, limit=50)
    check(
        restored_id in page_ids(peer_view),
        "the peer cannot read the message sent after the unblock",
    )
    blocker_view = history(client, admin["token"], conversation_id, limit=50)
    check(
        suppressed_id not in page_ids(blocker_view),
        "unblocking retro-delivered a peer-suppressed message",
    )


def scenario_e2ee_devices(session: Session) -> None:
    """Device registration/revocation contract and the member device directory."""
    client = session.client
    e2ee_peer = session.user("e2eePeer")
    outsider = session.user("outsider")
    stranger = session.user("stranger")

    # Device registration is capped at 20/hour per user, so the contract
    # negatives live on `third` and the throwaway on `stranger`; `admin` and
    # `e2eePeer` only carry the directory devices the activation scenario needs.
    device_owner = session.user("third")
    owner_device = ensure_device(client, device_owner["token"], PHONE_THIRD)
    again = register_device(
        client, device_owner["token"], owner_device, device_public_key(PHONE_THIRD)
    )
    check(again.status == 200, f"idempotent device PUT expected 200, got {again}")

    conflict = register_device(
        client, device_owner["token"], owner_device, device_public_key(PHONE_THIRD, "alt")
    )
    assert_error(conflict, 409, "E2EE_DEVICE_CONFLICT")
    malformed = register_device(
        client, device_owner["token"], str(uuid.uuid4()), "not base64!!"
    )
    assert_error(malformed, 400, "VALIDATION_FAILED")
    anonymous = client.put(
        f"/api/v1/e2ee/devices/{uuid.uuid4()}",
        body={"publicKey": device_public_key(PHONE_THIRD)},
    )
    assert_error(anonymous, 401, "UNAUTHORIZED")

    peer_device = ensure_device(client, e2ee_peer["token"], PHONE_E2EE_PEER)

    _, conversation = direct_conversation(client, device_owner["token"], PHONE_E2EE_PEER)
    conversation_id = conversation["id"]
    state = e2ee_state(client, device_owner["token"], conversation_id)
    check(state.status == 200, f"e2ee state expected 200, got {state}")
    payload = state.json()
    check(isinstance(payload.get("enabled"), bool), "e2ee state has no boolean enabled flag")
    check(
        isinstance(payload.get("experimentalV1ActivationAllowed"), bool),
        "e2ee state has no boolean experimentalV1ActivationAllowed flag",
    )
    directory = device_ids_of(payload)
    check(owner_device in directory, "the caller's device is missing from the directory")
    check(peer_device in directory, "the peer's device is missing from the directory")
    check(
        len(directory) == len(set(directory)),
        "the device directory repeats a device id",
    )
    for device in payload.get("devices") or []:
        check(
            str(device.get("userId")) in (device_owner["id"], e2ee_peer["id"]),
            f"the directory leaked a non-member device owner {device.get('userId')!r}",
        )

    group = create_group(
        client,
        device_owner["token"],
        f"{TAG} e2ee group {session.nonce}",
        [e2ee_peer["id"]],
    )
    group_id = group["id"]
    group_get = e2ee_state(client, device_owner["token"], group_id)
    assert_error(group_get, 409, "E2EE_UNSUPPORTED_CONVERSATION")
    group_put = set_e2ee(client, device_owner["token"], group_id, True)
    assert_error(group_put, 409, "E2EE_UNSUPPORTED_CONVERSATION")

    outsider_get = e2ee_state(client, outsider["token"], conversation_id)
    assert_error(outsider_get, 403, "NOT_MEMBER")
    missing = e2ee_state(client, device_owner["token"], str(uuid.uuid4()))
    assert_error(missing, 404, "CONVERSATION_NOT_FOUND")

    # A fresh id per run: a revoked id can never be re-registered, so a fixed
    # one would fail the second run. Revoked rows do not count toward the cap.
    throwaway = str(uuid.uuid4())
    throwaway_key = device_public_key(PHONE_STRANGER, throwaway)
    registered = register_device(client, stranger["token"], throwaway, throwaway_key)
    check(registered.status == 200, f"throwaway device PUT expected 200, got {registered}")
    revoked = revoke_device(client, stranger["token"], throwaway)
    check(revoked.status == 204, f"device revoke expected 204, got {revoked}")
    revoked_again = revoke_device(client, stranger["token"], throwaway)
    check(revoked_again.status == 204, f"idempotent revoke expected 204, got {revoked_again}")
    reregister = register_device(client, stranger["token"], throwaway, throwaway_key)
    assert_error(reregister, 409, "E2EE_DEVICE_CONFLICT")


def ensure_conversation_devices(client: Client, caller: dict, peer: dict, conversation_id: str):
    """Register a device only for a member the directory does not cover yet.

    Device registration is rate limited to 20/hour per user, so a warm database
    must not spend a token on an already-registered device.
    """
    state = e2ee_state(client, caller["token"], conversation_id)
    check(state.status == 200, f"e2ee state expected 200, got {state}")
    owners = {str(device.get("userId")) for device in state.json().get("devices") or []}
    if caller["id"] not in owners:
        ensure_device(client, caller["token"], caller["phone"])
    if peer["id"] not in owners:
        ensure_device(client, peer["token"], peer["phone"])
    return e2ee_state(client, caller["token"], conversation_id)


def scenario_e2ee_activation(session: Session) -> None:
    """DIRECT activation, encrypted-send rules, plaintext refusal, turning off."""
    client = session.client
    admin = session.user("admin")
    e2ee_peer = session.user("e2eePeer")
    outsider = session.user("outsider")

    _, conversation = direct_conversation(client, admin["token"], PHONE_E2EE_PEER)
    conversation_id = conversation["id"]

    state = ensure_conversation_devices(client, admin, e2ee_peer, conversation_id)
    if not state.json().get("experimentalV1ActivationAllowed"):
        raise NotRun(
            "nexachat.e2ee.v1-activation-enabled is false on this stack, so a "
            "false->true activation is refused with E2EE_V1_ACTIVATION_DISABLED"
        )

    reset = set_e2ee(client, admin["token"], conversation_id, False)
    check(reset.status == 200, f"disable e2ee expected 200, got {reset}")
    check(reset.json().get("enabled") is False, "e2ee did not start disabled")

    _, peerless = direct_conversation(client, admin["token"], PHONE_OUTSIDER)
    not_ready = set_e2ee(client, admin["token"], peerless["id"], True)
    assert_error(not_ready, 409, "E2EE_PEER_NOT_READY")

    enabled = set_e2ee(client, admin["token"], conversation_id, True)
    check(enabled.status == 200, f"enable e2ee expected 200, got {enabled}")
    check(enabled.json().get("enabled") is True, "e2ee did not turn on")
    active_ids = device_ids_of(enabled.json())
    check(len(active_ids) >= 2, f"expected >=2 active devices, got {len(active_ids)}")

    idempotent = set_e2ee(client, admin["token"], conversation_id, True)
    check(idempotent.status == 200, f"idempotent enable expected 200, got {idempotent}")
    check(idempotent.json().get("enabled") is True, "idempotent enable flipped the flag")

    plaintext = send_message(
        client, admin["token"], conversation_id, f"{TAG} plaintext {session.nonce}"
    )
    assert_error(plaintext, 409, "E2EE_REQUIRED")

    wrong_scheme = send_message(
        client,
        admin["token"],
        conversation_id,
        encrypted_content(session.nonce),
        encryption="PGP",
        e2eeEnvelopes=envelopes_for(active_ids),
    )
    assert_error(wrong_scheme, 400, "VALIDATION_FAILED")

    no_prefix = send_message(
        client,
        admin["token"],
        conversation_id,
        f"{TAG} no prefix {session.nonce}",
        encryption="E2EE_V1",
        e2eeEnvelopes=envelopes_for(active_ids),
    )
    assert_error(no_prefix, 400, "VALIDATION_FAILED")

    stale = send_message(
        client,
        admin["token"],
        conversation_id,
        encrypted_content(session.nonce),
        encryption="E2EE_V1",
        e2eeEnvelopes=envelopes_for(active_ids[:1]),
    )
    assert_error(stale, 409, "E2EE_STALE_DEVICES")

    unknown_device = send_message(
        client,
        admin["token"],
        conversation_id,
        encrypted_content(session.nonce),
        encryption="E2EE_V1",
        e2eeEnvelopes=envelopes_for([str(uuid.uuid4())]),
    )
    assert_error(unknown_device, 409, "E2EE_STALE_DEVICES")

    # The attachment contract runs before the E2EE rules, so a media send needs
    # a non-empty attachmentIds list to reach the documented E2EE refusal.
    media = send_message(
        client,
        admin["token"],
        conversation_id,
        encrypted_content(session.nonce),
        type="IMAGE",
        attachmentIds=[str(uuid.uuid4())],
        encryption="E2EE_V1",
        e2eeEnvelopes=envelopes_for(active_ids),
    )
    assert_error(media, 409, "E2EE_MEDIA_UNSUPPORTED")

    sent = send_message(
        client,
        admin["token"],
        conversation_id,
        encrypted_content(session.nonce),
        encryption="E2EE_V1",
        e2eeEnvelopes=envelopes_for(active_ids),
    )
    check(sent.status == 201, f"encrypted send expected 201, got {sent}")
    message = sent.json()
    check(message.get("encryption") == "E2EE_V1", f"encryption is {message.get('encryption')!r}")
    check(
        str(message.get("content") or "").startswith("e2ee1:"),
        "the stored content lost the e2ee1: envelope prefix",
    )
    envelope_ids = {str(item.get("deviceId")) for item in message.get("e2eeEnvelopes") or []}
    check(
        envelope_ids == set(active_ids),
        "the stored envelope set does not match the member device directory",
    )

    page = history(client, admin["token"], conversation_id, limit=50)
    stored = find_message(page, message["id"])
    check(stored is not None, "the encrypted message is missing from history")
    check(stored.get("encryption") == "E2EE_V1", "history dropped the encryption marker")
    peer_page = history(client, e2ee_peer["token"], conversation_id, limit=50)
    check(
        message["id"] in page_ids(peer_page),
        "the peer cannot read the encrypted message",
    )

    hidden = search(client, admin["token"], conversation_id, "e2ee1")
    check(hidden.status == 200, f"search expected 200, got {hidden}")
    check(
        not page_ids(hidden.json()),
        "search returned an encrypted row (ciphertext must never be searchable)",
    )

    disabled = set_e2ee(client, e2ee_peer["token"], conversation_id, False)
    check(disabled.status == 200, f"disable e2ee expected 200, got {disabled}")
    check(disabled.json().get("enabled") is False, "e2ee did not turn off")

    plain_again = send_message(
        client, admin["token"], conversation_id, f"{TAG} plaintext after off {session.nonce}"
    )
    check(plain_again.status == 201, f"plaintext send after off expected 201, got {plain_again}")
    check(
        plain_again.json().get("encryption") is None,
        "a plaintext send after disabling e2ee carries an encryption marker",
    )

    encrypted_after = send_message(
        client,
        admin["token"],
        conversation_id,
        encrypted_content(session.nonce),
        encryption="E2EE_V1",
        e2eeEnvelopes=envelopes_for(active_ids),
    )
    assert_error(encrypted_after, 409, "E2EE_NOT_ENABLED")

    outsider_toggle = set_e2ee(client, outsider["token"], conversation_id, True)
    assert_error(outsider_toggle, 403, "NOT_MEMBER")


SCENARIOS = (
    ("group_lifecycle", scenario_group_lifecycle),
    ("rejoin_visibility", scenario_rejoin_visibility),
    ("disappearing_messages", scenario_disappearing_messages),
    ("disappearing_expiry", scenario_disappearing_expiry),
    ("contacts_and_blocks", scenario_contacts_and_blocks),
    ("e2ee_devices", scenario_e2ee_devices),
    ("e2ee_activation", scenario_e2ee_activation),
)

SCENARIO_NAMES = tuple(name for name, _ in SCENARIOS)


# ------------------------------------------------------------------------ main


def main(argv=None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    args = parse_args(argv)
    if args.list:
        for name in SCENARIO_NAMES:
            print(name)
        return 0

    try:
        selected = select_scenarios(SCENARIO_NAMES, args.only)
    except ValueError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    client = Client(args.base_url, timeout=30.0)
    print(f"== NexaChat groups/privacy E2E ({args.base_url}) ==")
    try:
        health = client.get("/actuator/health")
        check(
            health.status == 200 and (health.json() or {}).get("status") == "UP",
            f"backend health is not UP: {health}",
        )
    except E2EFailure as failure:
        print(f"FAIL backend_health: {failure}")
        return 1

    session = Session(client, run_nonce())
    print(f"run nonce {session.nonce}")

    handlers = dict(SCENARIOS)
    failures = 0
    for name in selected:
        try:
            handlers[name](session)
        except NotRun as skip:
            print(f"NOT_RUN {name}: {skip}")
        except E2EFailure as failure:
            print(f"FAIL {name}: {failure}")
            failures += 1
        except Exception as error:  # noqa: BLE001 - report, never abort the run
            print(f"FAIL {name}: unexpected {type(error).__name__}: {error}")
            failures += 1
        else:
            print(f"PASS {name}")

    print(f"\n{len(selected) - failures}/{len(selected)} scenarios passed")
    return 1 if failures else 0


if __name__ == "__main__":
    main_guard(main)
