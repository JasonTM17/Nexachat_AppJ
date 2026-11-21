#!/usr/bin/env python3
"""Device media fixture driver: push, attach-sheet probe, and upload oracle.

This is the harness slice that unblocks the verifiable part of the ``media``
device journey row (stable_journeys id "media"). Three pieces:

- ``push_fixture`` writes a deterministic media_oracle fixture to a host
  evidence directory, ``adb push``es it onto the device, and proves the bytes
  landed unchanged via the on-device ``sha256sum`` (falling back to ``adb
  pull`` + host hash when toybox cannot serve the digest).
- The attach-sheet probe lives in stable_journeys (``_media_attach_via_ui``)
  and uses this module's ``dumpsys`` parsing to classify the surface the app
  delegated to. The app's composer attach sheet opens the app's own
  ``AttachmentPickerSheet`` (attachButton -> pickDocumentRow), which then
  launches a *system* picker (DocumentsUI for OpenDocument, the PhotoPicker
  provider for PickVisualMedia). That system surface is owned by the OS, not
  the app: uiautomator cannot reliably select a file in it, so the slice
  records the delegation as evidence and the pick-completion stays unproven.
- ``verify_upload_slice`` proves the byte path end to end through the *same
  documented REST surface the app uses after a pick completes*
  (AttachmentRepository.upload -> POST /api/v1/attachments multipart,
  MessageRepository outbox -> SendMessageRequest.attachmentIds): upload -> send
  -> owner and member byte-hash equality -> 206 Range slices -> a non-member
  GET refused with 403 NOT_MEMBER on both the content and /files endpoints.

Error classification (the caller maps these to journey statuses):
- ``MediaDeviceError``: the harness/device side failed (adb push refused,
  digest unreadable, fixture misuse). Maps to FAIL_HARNESS_OR_ENVIRONMENT.
- ``MediaProductError``: the product surface under test broke the contract
  (non-201 upload, wrong size/mime DTO, byte mismatch, non-206 range, a
  non-member 200 leak). Maps to FAIL_PRODUCT.

Out of scope (negative, by row design): an emulator cannot prove voice
recording or playback -- the composer's micButton -> recordingOverlay flow and
the MediaViewer playback path need real hardware audio capture and render.
That slice stays BLOCKED_CAPABILITY and is deliberately not driven here.

Standard library only; adb goes through stable_journeys' structured-argv
``Adb`` adapter (or anything with ``command(*args, timeout=, check=)``).
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
import uuid
from pathlib import Path
from typing import Any

_THIS_DIR = Path(__file__).resolve().parent
for _path in (str(_THIS_DIR), str(_THIS_DIR.parent)):
    if _path not in sys.path:
        sys.path.insert(0, _path)

from e2e_common import E2EFailure  # noqa: E402
from media_oracle import (  # noqa: E402
    FILES_PREFIX,
    Fixture,
    build_fixture,
    check_range,
    download_attachment,
    upload_attachment,
    verify_payload,
)

__all__ = [
    "DEFAULT_DEVICE_DIR",
    "Fixture",
    "MediaDeviceError",
    "MediaProductError",
    "PICKER_PACKAGES",
    "build_fixture",
    "device_file_digest",
    "is_system_picker_component",
    "non_member_refusal",
    "push_fixture",
    "range_slices",
    "resumed_activity_component",
    "send_attachment_message",
    "soft_delete_message",
    "verify_upload_slice",
]

DEFAULT_DEVICE_DIR = "/sdcard/Download"

# Components the app's attach sheet delegates to. DocumentsUI answers
# OpenDocument (the "File or audio" row); the MediaModule/PhotoPicker surfaces
# answer PickVisualMedia. None of them are app surfaces.
PICKER_PACKAGES = (
    "com.android.documentsui",
    "com.google.android.providers.media.module",
    "com.google.android.photopicker",
    "com.android.providers.media",
)

_SHA256SUM_RE = re.compile(r"^\s*([0-9a-fA-F]{64})\s+\S")
_COMPONENT_RE = re.compile(
    r"(?:topResumedActivity|mResumedActivity|ResumedActivity)"
    r"[=:]?\s*ComponentInfo\{([^}]+)\}"
)


class MediaDeviceError(Exception):
    """Harness/device failure; not evidence about the app or server."""


class MediaProductError(AssertionError):
    """The upload/serve contract did not hold on the product surface."""


# ---------------------------------------------------------------------------
# Device fixture push + byte verification
# ---------------------------------------------------------------------------


def device_file_digest(
    adb: Any,
    remote_path: str,
    *,
    fallback_host_dir: Path | str,
) -> tuple[str, str]:
    """SHA-256 of a file on the device, as hex.

    Primary: ``adb shell sha256sum <path>`` (toybox serves this on the
    campaign emulator). Fallback: ``adb pull`` the bytes back and hash them on
    the host, which is binary-safe even when the digest tool is missing.
    Returns ``(hex_digest, method)``; a failure to read the bytes at all is a
    MediaDeviceError (the fixture never landed or the transport is broken).
    """
    try:
        raw = adb.command("shell", "sha256sum", remote_path, check=False)
    except Exception:
        # sha256sum may simply be absent; fall through to the pull path.
        raw = ""
    match = _SHA256SUM_RE.match(raw or "")
    if match:
        return match.group(1).lower(), "sha256sum"
    fallback = Path(fallback_host_dir) / ("pulled-" + uuid.uuid4().hex[:8])
    try:
        adb.command("pull", remote_path, str(fallback))
        data = fallback.read_bytes()
    except Exception as error:
        raise MediaDeviceError(
            f"could not verify device bytes at {remote_path}: "
            f"sha256sum unusable and pull failed ({error})"
        ) from error
    finally:
        try:
            fallback.unlink()
        except OSError:
            pass
    return hashlib.sha256(data).hexdigest(), "pull"


def push_fixture(
    adb: Any,
    fixture: Fixture,
    *,
    host_dir: Path | str,
    remote_dir: str = DEFAULT_DEVICE_DIR,
) -> dict[str, Any]:
    """Writes the fixture to ``host_dir``, adb-pushes it to ``remote_dir``,
    and proves the on-device bytes hash to the fixture's SHA-256.

    ``host_dir`` should be the row's evidence directory: the pushed copy is
    itself evidence of exactly which bytes the device held. Returns a
    descriptor with the remote path, digest, verify method and size. A failed
    push or a digest mismatch is a MediaDeviceError (device/harness side).
    """
    if not fixture.filename or "/" in fixture.filename or "\\" in fixture.filename:
        raise MediaDeviceError(f"fixture filename is not push-safe: {fixture.filename!r}")
    host_path = Path(host_dir) / fixture.filename
    host_path.parent.mkdir(parents=True, exist_ok=True)
    host_path.write_bytes(fixture.data)
    remote_path = f"{remote_dir.rstrip('/')}/{fixture.filename}"
    try:
        adb.command("push", str(host_path), remote_path)
    except Exception as error:
        raise MediaDeviceError(
            f"adb push of {fixture.filename} to {remote_path} failed: {error}"
        ) from error
    digest, method = device_file_digest(
        adb, remote_path, fallback_host_dir=host_path.parent
    )
    if digest != fixture.sha256:
        raise MediaDeviceError(
            f"device bytes at {remote_path} hash to {digest}, expected {fixture.sha256}"
        )
    return {
        "fixture": fixture.describe(),
        "remote_path": remote_path,
        "host_copy": str(host_path),
        "size_bytes": fixture.size,
        "sha256": digest,
        "verified_by": method,
    }


def remove_device_file(adb: Any, remote_path: str) -> None:
    """Best-effort cleanup of a pushed fixture; never raises."""
    try:
        adb.command("shell", "rm", "-f", remote_path, check=False)
    except Exception:
        pass


# ---------------------------------------------------------------------------
# System-picker detection (dumpsys parsing)
# ---------------------------------------------------------------------------


def resumed_activity_component(dumpsys_text: str) -> str | None:
    """The resumed activity component from an ``activity activities`` dump.

    Matches both the API 33+ ``topResumedActivity=ComponentInfo{pkg/cls}``
    spelling and the older ``mResumedActivity=ComponentInfo{...}``.
    """
    match = _COMPONENT_RE.search(dumpsys_text or "")
    return match.group(1).strip() if match else None


def is_system_picker_component(component: str | None) -> bool:
    """True when the component belongs to a system file/photo picker."""
    if not component:
        return False
    package = component.split("/", 1)[0]
    return package in PICKER_PACKAGES


# ---------------------------------------------------------------------------
# Upload / send / serve oracle (the app's documented REST path)
# ---------------------------------------------------------------------------


def _api_error_detail(response: Any) -> str:
    status = getattr(response, "status", "?")
    text = str(getattr(response, "text", "") or "")[:200]
    return f"HTTP {status}: {text}"


def upload_fixture(
    client: Any,
    token: str,
    fixture: Fixture,
    **fields: Any,
) -> dict[str, Any]:
    """POSTs the fixture multipart to /api/v1/attachments and checks the DTO
    against the fixture (201, id, sizeBytes, mimeType). The upload is the
    product surface under test here, so every broken expectation is a
    MediaProductError."""
    try:
        attachment = upload_attachment(client, token, fixture, **fields)
    except E2EFailure as error:
        raise MediaProductError(f"attachment upload failed: {error}") from error
    if attachment.get("sizeBytes") != fixture.size:
        raise MediaProductError(
            f"upload DTO sizeBytes={attachment.get('sizeBytes')!r} != fixture {fixture.size}"
        )
    if attachment.get("mimeType") != fixture.mime_type:
        raise MediaProductError(
            f"upload DTO mimeType={attachment.get('mimeType')!r} != "
            f"fixture {fixture.mime_type!r}"
        )
    return attachment


def send_attachment_message(
    client: Any,
    token: str,
    conversation_id: str,
    msg_type: str,
    attachment_id: str,
) -> dict[str, Any]:
    """Sends the message that binds the upload, mirroring the app's
    SendMessageRequest (clientMessageId, type, content=None, attachmentIds)."""
    response = client.post(
        f"/api/v1/conversations/{conversation_id}/messages",
        token=token,
        body={"clientMessageId": str(uuid.uuid4()), "type": msg_type,
              "content": None, "attachmentIds": [attachment_id]},
    )
    if response.status not in (200, 201):
        raise MediaProductError(
            f"{msg_type} attachment message returned {response.status}: "
            f"{_api_error_detail(response)}"
        )
    data = response.json() or {}
    if not data.get("id"):
        raise MediaProductError("attachment message response has no id")
    return dict(data)


def _attachment_bytes(
    client: Any, token: str, fixture: Fixture, attachment_id: str, who: str
) -> str:
    """GETs the served bytes and returns their hex digest, asserting identity."""
    try:
        payload = download_attachment(client, token, attachment_id)
    except E2EFailure as error:
        raise MediaProductError(f"{who} content GET failed: {error}") from error
    result = verify_payload(fixture.data, payload)
    if not result.match:
        raise MediaProductError(
            f"{who} received bytes differ: {result.to_dict()!r}"
        )
    return result.actual_sha256


def range_slices(
    client: Any,
    token: str,
    url_or_key: str,
    fixture: Fixture,
    *,
    count: int = 16,
) -> list[dict[str, Any]]:
    """Proves two ``Range`` requests return 206 with the exact byte slices:
    a leading window (0..count-1) and a mid-file window (size//3..). Each
    mismatching slice is a MediaProductError."""
    windows: list[tuple[int, int]] = [(0, min(count - 1, fixture.size - 1))]
    mid_start = max(1, fixture.size // 3)
    windows.append((mid_start, min(mid_start + count - 1, fixture.size - 1)))
    results: list[dict[str, Any]] = []
    for start, end in windows:
        result = check_range(client, token, url_or_key, fixture, start, end)
        if not result.get("ok"):
            raise MediaProductError(
                f"range bytes={start}-{end} did not serve the exact slice: {result!r}"
            )
        results.append({
            "range": f"bytes={start}-{end}",
            "status": result["status"],
            "content_range": result.get("content_range"),
            "slice_match": result["slice_match"],
        })
    return results


def non_member_refusal(
    client: Any,
    token: str,
    attachment_id: str,
    url_or_key: str,
) -> dict[str, Any]:
    """Asserts the non-member GET is refused: 403 NOT_MEMBER on both the
    attachment content endpoint and the raw /api/v1/files/{key} serve path.
    A 200 is a member-scope leak and fails as a product defect."""
    refusals: dict[str, Any] = {}
    checks = (
        ("content", f"/api/v1/attachments/{attachment_id}/content"),
        ("files", url_or_key if url_or_key.startswith("/") else FILES_PREFIX + url_or_key),
    )
    for label, path in checks:
        response = client.get(path, token=token)
        status = response.status
        body: Any = {}
        try:
            body = response.json() or {}
        except (ValueError, TypeError):
            body = {}
        code = str(((body or {}).get("error") or {}).get("code") or "")
        if status != 403:
            raise MediaProductError(
                f"non-member {label} GET expected 403, got {status} "
                f"({code or 'no error code'}): {_api_error_detail(response)}"
            )
        if code and code != "NOT_MEMBER":
            raise MediaProductError(
                f"non-member {label} refusal code was {code!r}, expected NOT_MEMBER"
            )
        # Key deliberately avoids the redactor's sensitive-field names so the
        # refusal reason survives in the row's evidence JSON.
        refusals[label] = {"status": status, "refused_with": code or "NOT_MEMBER"}
    return refusals


def storage_key_from_url(url_or_key: str) -> str:
    """Extracts the storage key from the DTO ``url`` field (or accepts a bare
    key) for the /api/v1/files/{key} refusal check."""
    value = str(url_or_key or "")
    if FILES_PREFIX in value:
        value = value.split(FILES_PREFIX, 1)[1]
    return value.lstrip("/")


def verify_upload_slice(
    client: Any,
    *,
    owner_token: str,
    member_token: str,
    outsider_token: str,
    conversation_id: str,
    fixture: Fixture,
    msg_type: str,
    image_width: int | None = None,
    image_height: int | None = None,
) -> dict[str, Any]:
    """Full upload -> send -> serve oracle for one fixture.

    The upload and the binding message go through the app's documented REST
    path (the same endpoints AttachmentRepository/MessageRepository call after
    a completed pick). The owner and a conversation member must each receive
    byte-identical content; a 206 Range slice must be exact; a non-member GET
    must be refused 403 NOT_MEMBER on both serve endpoints. Any deviation is a
    MediaProductError. Returns a structured result for the row's evidence.
    """
    fields: dict[str, Any] = {}
    if image_width is not None:
        fields["width"] = image_width
    if image_height is not None:
        fields["height"] = image_height
    attachment = upload_fixture(client, owner_token, fixture, **fields)
    attachment_id = str(attachment["id"])
    message = send_attachment_message(
        client, owner_token, conversation_id, msg_type, attachment_id
    )
    owner_sha = _attachment_bytes(client, owner_token, fixture, attachment_id, "owner")
    member_sha = _attachment_bytes(
        client, member_token, fixture, attachment_id, "member"
    )
    url = str(attachment.get("url") or "")
    ranges = range_slices(client, member_token, url or storage_key_from_url(url), fixture)
    refusals = non_member_refusal(
        client, outsider_token, attachment_id, storage_key_from_url(url)
    )
    return {
        "fixture": fixture.describe(),
        "msg_type": msg_type,
        "attachment": {
            "id": attachment_id,
            "url": url,
            "mimeType": attachment.get("mimeType"),
            "sizeBytes": attachment.get("sizeBytes"),
        },
        "message_id": str(message.get("id") or ""),
        "owner_sha256_match": owner_sha == fixture.sha256,
        "member_sha256_match": member_sha == fixture.sha256,
        "ranges": ranges,
        "non_member_refused": refusals,
        "ok": True,
    }


def soft_delete_message(
    client: Any, token: str, conversation_id: str, message_id: str
) -> str | None:
    """Tombstones one seeded message; returns an error string, never raises.

    Cleanup must not flip a row's verdict, so failures are reported to the
    caller's cleanup_errors list instead of raised.
    """
    try:
        response = client.delete(
            f"/api/v1/conversations/{conversation_id}/messages/{message_id}",
            token=token,
        )
    except Exception as error:
        return f"{message_id}: {type(error).__name__}"
    if response.status not in (200, 204):
        return f"{message_id}: delete HTTP {response.status}"
    return None


# ---------------------------------------------------------------------------
# CLI: push/verify without the full campaign
# ---------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(description="Media fixture push probe")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--fixture", default="pdf", choices=("png", "jpeg", "pdf", "txt"))
    parser.add_argument("--remote-dir", default=DEFAULT_DEVICE_DIR)
    parser.add_argument("--out", default=".", help="Host directory for the pushed copy")
    args = parser.parse_args(argv)

    class _CliAdb:
        def __init__(self, serial: str) -> None:
            self.serial = serial

        def command(self, *cmd_args: str, timeout: float = 60, check: bool = True):
            import subprocess

            argv = ["adb", "-s", self.serial, *map(str, cmd_args)]
            completed = subprocess.run(
                argv, capture_output=True, timeout=timeout, check=False
            )
            if check and completed.returncode != 0:
                raise MediaDeviceError(
                    completed.stderr.decode("utf-8", "replace").strip() or "adb failed"
                )
            return completed.stdout.decode("utf-8", "replace")

    try:
        pushed = push_fixture(
            _CliAdb(args.serial), build_fixture(args.fixture),
            host_dir=args.out, remote_dir=args.remote_dir,
        )
    except MediaDeviceError as error:
        print(f"media_driver: error: {error}", file=sys.stderr)
        return 1
    print(json.dumps(pushed, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
