"""Pure helpers for the new-feature live E2E suite.

Standard library only (the P0 helpers this module reuses are stdlib too).
Everything here is import-safe without a backend: the live script, its
``--self-test`` mode and the offline unit tests all share these functions.
"""

from __future__ import annotations

import base64
import json
import sys
import time
import urllib.parse
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

_P0_DIR = Path(__file__).resolve().parent.parent / "p0"
if str(_P0_DIR) not in sys.path:
    sys.path.insert(0, str(_P0_DIR))

from p0_helpers import (  # noqa: E402  (path set above)
    generate_test_password,
    generate_test_phone,
    mask_id,
    mask_phone,
    mask_token,
)

__all__ = [
    "DEFAULT_BASE_URL",
    "CheckLog",
    "anonymous_poll_leaks_nobody",
    "find_archive_item",
    "find_inbox_entry",
    "generate_test_password",
    "generate_test_phone",
    "is_local_base_url",
    "iso_utc",
    "mask_id",
    "mask_phone",
    "mask_token",
    "multipart_body",
    "now_utc",
    "png_1x1",
    "poll_option_by_label",
    "utc_after",
]

DEFAULT_BASE_URL = "http://localhost:8080"

# Loopback aliases only. A LAN address, a tunnel or a public hostname is not
# "local" for the purposes of this guard, even when it happens to be a test
# deployment: the suite writes reminders/polls and must never touch a shared
# environment by accident.
_LOCAL_HOSTS = frozenset({"localhost", "127.0.0.1", "::1", "0.0.0.0"})


def is_local_base_url(base_url: str) -> bool:
    """True only for an http(s) URL whose host is a loopback address."""
    try:
        parts = urllib.parse.urlsplit(base_url)
    except ValueError:
        return False
    if parts.scheme not in ("http", "https"):
        return False
    host = (parts.hostname or "").lower()
    if not host:
        return False
    return host in _LOCAL_HOSTS or host.startswith("127.")


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def utc_after(*, seconds: float = 0, minutes: float = 0, hours: float = 0,
              days: float = 0) -> datetime:
    return now_utc() + timedelta(
        seconds=seconds, minutes=minutes, hours=hours, days=days
    )


def iso_utc(moment: datetime) -> str:
    """Seconds-precision ISO-8601 with a ``Z`` suffix (Instant.parse-friendly)."""
    return moment.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def png_1x1() -> bytes:
    """A real 1x1 transparent PNG: only the magic bytes matter server-side."""
    return base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
        "YPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    )


def multipart_body(
    *,
    fields: Mapping[str, str] | None = None,
    file_field: str = "file",
    filename: str,
    content_type: str,
    data: bytes,
) -> tuple[bytes, str]:
    """Builds a multipart/form-data body and returns ``(body, content_type)``."""
    boundary = "----nexachat-e2e-" + uuid_hex()
    lines: list[bytes] = []
    for name, value in (fields or {}).items():
        lines.append(f"--{boundary}\r\n".encode())
        lines.append(f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode())
        lines.append(str(value).encode("utf-8") + b"\r\n")
    lines.append(f"--{boundary}\r\n".encode())
    lines.append(
        (
            f'Content-Disposition: form-data; name="{file_field}"; '
            f'filename="{filename}"\r\n'
        ).encode()
    )
    lines.append(f"Content-Type: {content_type}\r\n\r\n".encode())
    lines.append(data + b"\r\n")
    lines.append(f"--{boundary}--\r\n".encode())
    return b"".join(lines), f"multipart/form-data; boundary={boundary}"


def uuid_hex() -> str:
    import uuid

    return uuid.uuid4().hex


def find_inbox_entry(entries: Iterable[dict], conversation_id: str) -> dict | None:
    for entry in entries or []:
        if entry.get("id") == conversation_id:
            return entry
    return None


def find_archive_item(items: Iterable[dict], *, attachment_id: str | None = None,
                      link_url: str | None = None) -> dict | None:
    for item in items or []:
        if attachment_id is not None:
            attachment = item.get("attachment") or {}
            if attachment.get("id") == attachment_id:
                return item
        if link_url is not None:
            link = item.get("link") or {}
            if link.get("url") == link_url:
                return item
    return None


def poll_option_by_label(poll: dict, label: str) -> dict | None:
    for option in poll.get("options") or []:
        if option.get("label") == label:
            return option
    return None


def anonymous_poll_leaks_nobody(poll: dict, voter_id: str) -> bool:
    """Every option must omit ``voterIds`` and the voter id must not appear."""
    encoded = json.dumps(poll)
    if voter_id and voter_id in encoded:
        return False
    for option in poll.get("options") or []:
        if "voterIds" in option and option["voterIds"] is not None:
            return False
    return True


class CheckLog:
    """PASS/FAIL/SKIP records, each carrying method, endpoint and status.

    A required skip (an endpoint or transport the contract promises) fails the
    run unless ``allow_skip`` is set. Any failure always fails the run.
    """

    def __init__(self, allow_skip: bool = False):
        self.passed = 0
        self.failed = 0
        self.skipped = 0
        self.required_skipped = 0
        self.allow_skip = allow_skip
        self.started = time.time()

    def _line(self, verdict: str, name: str, method: str, path: str,
              status: Any, detail: str = "") -> str:
        endpoint = f"{method} {path}" if method else path
        suffix = f" - {detail}" if detail else ""
        return f"{verdict} {name} [{endpoint} -> {status}]{suffix}"

    def record(self, ok: bool, name: str, method: str, path: str,
               status: Any, detail: str = "") -> bool:
        if ok:
            self.passed += 1
            print(self._line("PASS", name, method, path, status, detail))
        else:
            self.failed += 1
            print(self._line("FAIL", name, method, path, status, detail))
        return ok

    def expect(self, name: str, condition: bool, method: str, path: str,
               status: Any, detail: str = "") -> bool:
        return self.record(condition, name, method, path, status, detail)

    def expect_status(self, name: str, response, expected: int | Sequence[int],
                      method: str, path: str, detail: str = "") -> bool:
        expected_codes = (expected,) if isinstance(expected, int) else tuple(expected)
        ok = response is not None and response.status in expected_codes
        if not ok:
            wanted = "/".join(str(code) for code in expected_codes)
            code = getattr(response, "error_code", lambda: None)()
            failure = f"expected {wanted}, got {getattr(response, 'status', 'no response')}"
            if code:
                failure += f" {code}"
            detail = f"{failure} - {detail}" if detail else failure
        return self.expect(name, ok, method, path, getattr(response, "status", None), detail)

    def skip(self, name: str, method: str, path: str, reason: str, *,
             required: bool = False) -> None:
        self.skipped += 1
        if required:
            self.required_skipped += 1
        print(self._line("SKIP", name, method, path, "not run", reason))

    def summary(self) -> int:
        elapsed = time.time() - self.started
        print(
            f"\nResult: {self.passed} passed, {self.failed} failed, "
            f"{self.skipped} skipped ({self.required_skipped} required) "
            f"in {elapsed:.2f}s"
        )
        return 1 if self.failed > 0 or (
            self.required_skipped > 0 and not self.allow_skip
        ) else 0
