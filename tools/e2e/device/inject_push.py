#!/usr/bin/env python3
"""Inject a debug-build push into com.nexachat.debug on emulator-5554.

The command is an explicit-component broadcast. Release builds do not register
the receiver. This helper never contacts a server and refuses URLs and credentials.

Global flags (--serial, --dry-run) go before the payload kind.

Examples:
    python tools/e2e/device/inject_push.py message \\
        --recipient-id user-me --conversation-id conversation-1 \\
        --message-id message-1 --seq 8
    python tools/e2e/device/inject_push.py reminder \\
        --recipient-id user-me --conversation-id conversation-1 \\
        --reminder-id reminder-1 --occurrence 0123456789abcdef0123456789abcdef
    python tools/e2e/device/inject_push.py call \\
        --recipient-id user-me --conversation-id conv-200 \\
        --call-id call-100 --caller-id user-alice --caller-name Alice
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time

SERIAL = "emulator-5554"
PACKAGE = "com.nexachat.debug"
RECEIVER = "com.nexachat.debug.DebugPushInjectionReceiver"
COMPONENT = f"{PACKAGE}/{RECEIVER}"
ACTION = "com.nexachat.debug.INJECT_PUSH"
CALL_RING_MS = 45_000
MAX_POINTER_BYTES = 512
MAX_REMINDER_BYTES = 2048
PRODUCTION_HOST = "nexachat.io.vn"

MESSAGE_KEYS = ("type", "recipientId", "conversationId", "messageId", "seq")
REMINDER_KEYS = ("type", "recipientId", "conversationId", "reminderId", "occurrence")
CALL_KEYS = (
    "type",
    "recipientId",
    "conversationId",
    "callId",
    "callerId",
    "callerName",
    "serverTs",
    "ringExpiresAt",
)

_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:@+-]{0,127}$")
_OCCURRENCE_RE = re.compile(r"^[0-9a-f]{32}$")
_JWT_RE = re.compile(r"^[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}$")
_API_KEY_RE = re.compile(r"AIza[0-9A-Za-z_-]{20,}")
_FCM_TOKEN_RE = re.compile(r":APA91[0-9A-Za-z_-]{20,}")


class InjectPushError(ValueError):
    """The injection request is not a local debug payload."""


def require_serial(serial: str) -> str:
    if serial != SERIAL:
        raise InjectPushError(
            f"refusing serial {serial!r}; injection is reserved for {SERIAL}"
        )
    return serial


def require_safe(label: str, value: str) -> str:
    if not isinstance(value, str) or value == "":
        raise InjectPushError(f"{label} must be a non-empty string")
    if value.startswith("-") or any(ch in value for ch in "\r\n\x00"):
        raise InjectPushError(f"{label} contains a character adb would misread")
    folded = value.casefold()
    if "://" in value or PRODUCTION_HOST in folded or "googleapis.com" in folded:
        raise InjectPushError(f"{label} looks like a URL")
    if (
        value.startswith("eyJ")
        or _JWT_RE.fullmatch(value) is not None
        or _API_KEY_RE.search(value) is not None
        or _FCM_TOKEN_RE.search(value) is not None
        or folded.startswith("bearer ")
        or "ya29." in value
    ):
        raise InjectPushError(f"{label} looks like a token")
    return value


def require_id(label: str, value: str) -> str:
    require_safe(label, value)
    if _ID_RE.fullmatch(value) is None:
        raise InjectPushError(f"{label} is not an id")
    return value


def require_positive_long(label: str, value: str) -> str:
    require_safe(label, value)
    try:
        number = int(value, 10)
    except ValueError as error:
        raise InjectPushError(f"{label} must be a positive integer") from error
    if number <= 0 or str(number) != value:
        raise InjectPushError(f"{label} must be a positive integer")
    return value


def _utf8_len(payload: dict[str, str]) -> int:
    return sum(len(key.encode("utf-8")) + len(value.encode("utf-8")) for key, value in payload.items())


def require_payload(payload: dict[str, str]) -> dict[str, str]:
    kind = payload.get("type")
    expected = {
        "NEW_MESSAGE": MESSAGE_KEYS,
        "REMINDER": REMINDER_KEYS,
        "CALL_INVITE": CALL_KEYS,
    }.get(kind or "")
    if expected is None or tuple(payload) != expected:
        raise InjectPushError("payload keys do not match a NEW_MESSAGE, REMINDER, or CALL_INVITE")
    for key, value in payload.items():
        require_safe(key, value)
    limit = MAX_REMINDER_BYTES if kind == "REMINDER" else MAX_POINTER_BYTES
    size = _utf8_len(payload)
    if size > limit:
        raise InjectPushError(f"payload is {size} UTF-8 bytes; the processor limit is {limit}")
    return payload


def build_message(recipient_id: str, conversation_id: str, message_id: str, seq: str) -> dict[str, str]:
    payload = {
        "type": "NEW_MESSAGE",
        "recipientId": require_id("recipientId", recipient_id),
        "conversationId": require_id("conversationId", conversation_id),
        "messageId": require_id("messageId", message_id),
        "seq": require_positive_long("seq", str(seq)),
    }
    return require_payload(payload)


def build_reminder(
    recipient_id: str, conversation_id: str, reminder_id: str, occurrence: str
) -> dict[str, str]:
    require_safe("occurrence", occurrence)
    if _OCCURRENCE_RE.fullmatch(occurrence) is None:
        raise InjectPushError("occurrence must be 32 lowercase hex characters")
    payload = {
        "type": "REMINDER",
        "recipientId": require_id("recipientId", recipient_id),
        "conversationId": require_id("conversationId", conversation_id),
        "reminderId": require_id("reminderId", reminder_id),
        "occurrence": occurrence,
    }
    return require_payload(payload)


def build_call(
    recipient_id: str,
    conversation_id: str,
    call_id: str,
    caller_id: str,
    caller_name: str,
    server_ts: str | None = None,
    ring_expires_at: str | None = None,
    now_ms: int | None = None,
) -> dict[str, str]:
    now = int(time.time() * 1000) if now_ms is None else now_ms
    server = str(now) if server_ts is None else require_positive_long("serverTs", str(server_ts))
    ring = (
        str(int(server) + CALL_RING_MS)
        if ring_expires_at is None
        else require_positive_long("ringExpiresAt", str(ring_expires_at))
    )
    if int(ring) <= int(server):
        raise InjectPushError("ringExpiresAt must be later than serverTs")
    if int(ring) <= now:
        raise InjectPushError("ringExpiresAt must be in the future")
    require_safe("callerName", caller_name)
    if caller_name.strip() != caller_name or len(caller_name) > 80:
        raise InjectPushError("callerName must be 1..80 characters without surrounding space")
    payload = {
        "type": "CALL_INVITE",
        "recipientId": require_id("recipientId", recipient_id),
        "conversationId": require_id("conversationId", conversation_id),
        "callId": require_id("callId", call_id),
        "callerId": require_id("callerId", caller_id),
        "callerName": caller_name,
        "serverTs": server,
        "ringExpiresAt": ring,
    }
    return require_payload(payload)


def adb_argv(payload: dict[str, str], serial: str = SERIAL) -> list[str]:
    require_serial(serial)
    require_payload(payload)
    command = [
        "adb",
        "-s",
        serial,
        "shell",
        "am",
        "broadcast",
        "-n",
        COMPONENT,
        "-a",
        ACTION,
    ]
    for key, value in payload.items():
        command.extend(["--es", key, value])
    return command


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=SERIAL)
    parser.add_argument("--dry-run", action="store_true")
    shared = argparse.ArgumentParser(add_help=False)
    shared.add_argument("--recipient-id", required=True)
    shared.add_argument("--conversation-id", required=True)
    kinds = parser.add_subparsers(dest="kind", required=True)

    message = kinds.add_parser("message", parents=[shared])
    message.add_argument("--message-id", required=True)
    message.add_argument("--seq", required=True)

    reminder = kinds.add_parser("reminder", parents=[shared])
    reminder.add_argument("--reminder-id", required=True)
    reminder.add_argument("--occurrence", required=True)

    call = kinds.add_parser("call", parents=[shared])
    call.add_argument("--call-id", required=True)
    call.add_argument("--caller-id", required=True)
    call.add_argument("--caller-name", required=True)
    call.add_argument("--server-ts")
    call.add_argument("--ring-expires-at")
    return parser.parse_args(argv)


def payload_from_args(args: argparse.Namespace, now_ms: int | None = None) -> dict[str, str]:
    require_serial(args.serial)
    if args.kind == "message":
        return build_message(args.recipient_id, args.conversation_id, args.message_id, args.seq)
    if args.kind == "reminder":
        return build_reminder(
            args.recipient_id, args.conversation_id, args.reminder_id, args.occurrence
        )
    if args.kind == "call":
        return build_call(
            args.recipient_id,
            args.conversation_id,
            args.call_id,
            args.caller_id,
            args.caller_name,
            args.server_ts,
            args.ring_expires_at,
            now_ms=now_ms,
        )
    raise InjectPushError(f"unknown payload kind {args.kind!r}")


def main(argv: list[str] | None = None) -> int:
    try:
        args = parse_args(sys.argv[1:] if argv is None else argv)
        command = adb_argv(payload_from_args(args), args.serial)
    except InjectPushError as error:
        print(f"inject_push: {error}", file=sys.stderr)
        return 2
    if args.dry_run:
        json.dump(command, sys.stdout)
        print()
        return 0
    completed = subprocess.run(command, check=False)
    return completed.returncode


if __name__ == "__main__":
    sys.exit(main())
