#!/usr/bin/env python3
"""Deterministic media fixtures, a SHA-256 byte oracle, and attachment REST helpers.

Groundwork for the blocked ``media`` device journey row: the row needs a known
fixture whose bytes survive the upload/serve round-trip so the persisted hash
can be compared byte-for-byte, and it needs REST helpers matching the real
attachment surface in ``server/src/main/java/com/nexachat/attachment``:

- ``POST /api/v1/attachments`` (multipart, part ``file`` plus optional
  ``width``/``height``/``duration``/``blurHash`` form fields; exactly one file
  part) -> 201 ``AttachmentResponse{id,url,originalName,mimeType,sizeBytes,...}``.
  The row stays unbound until a send references it via ``attachmentIds``.
- ``GET /api/v1/attachments/{id}/content`` -> stored bytes, sniffed MIME.
- ``GET /api/v1/files/{storageKey}`` -> stored bytes by key (the ``url`` field),
  single-range ``Range`` support returning 206.

Fixtures are generated, never downloaded: a pure-python PNG writer (zlib +
CRC32 only) produces valid decodable images, a canonical baseline JPEG is
embedded as a base64 literal (minted once with Pillow 12 -- a JPEG encoder is
not vendored, so the blob is the fixture), and a minimal well-formed PDF plus a
text payload cover the non-image path where the app does not transcode bytes.

Byte-identity caveat for the device half: ``ImageCompressor`` on Android
re-encodes image/* uploads (WEBP on API >= 30), so a PNG picked through the
app's own picker is NOT byte-identical on the server. Byte-hash proof through
the app pipeline needs a non-image fixture (pdf/txt via the document picker);
image fixtures are still correct through the REST path, which stores bytes
verbatim.

Standard library only; ``e2e_common.Client`` carries the REST calls.

Usage:
    python tools/e2e/device/media_oracle.py list
    python tools/e2e/device/media_oracle.py fixture png --out /tmp/f.png
    python tools/e2e/device/media_oracle.py hash /tmp/f.png
    python tools/e2e/device/media_oracle.py verify png /tmp/f.png
    python tools/e2e/device/media_oracle.py upload pdf --base-url http://127.0.0.1:8080 --token <jwt>
    python tools/e2e/device/media_oracle.py roundtrip pdf --base-url ... --phone +84900000001
"""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import os
import struct
import sys
import uuid
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

_E2E_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
if _E2E_DIR not in sys.path:
    sys.path.insert(0, _E2E_DIR)

from e2e_common import Client, E2EFailure, Response, check, login  # noqa: E402

DEFAULT_SEED = 20240508
DEFAULT_WIDTH = 32
DEFAULT_HEIGHT = 24
FILES_PREFIX = "/api/v1/files/"
TOKEN_ENV = "NEXACHAT_TOKEN"

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
PNG_MAGIC_HEX = "89504E47"
JPEG_MAGIC_HEX = "FFD8FF"
PDF_MAGIC = b"%PDF-"

# Canonical 32x24 baseline JPEG (Pillow 12, quality=80, 4:2:0). A minimal JPEG
# encoder is not vendored and none is available in the standard library, so the
# fixture embeds the minted bytes; determinism comes from the literal, and
# `jpeg_fixture()` returns exactly these bytes on every call. Expected
# SHA-256: 957ce17551fa124d84722cfd729b0c68022ada5462e25484d9ca874bf2d259c4.
_JPEG_BLOB_B64 = (
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAYEBQYFBAYGBQYHBwYIChAKCgkJChQODwwQFxQYGBcU"
    "FhYaHSUfGhsjHBYWICwgIyYnKSopGR8tMC0oMCUoKSj/2wBDAQcHBwoIChMKChMoGhYaKCgoKCgo"
    "KCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCj/wAARCAAYACADASIA"
    "AhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQA"
    "AAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3"
    "ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWm"
    "p6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEA"
    "AwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSEx"
    "BhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElK"
    "U1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3"
    "uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDwWw0X"
    "p8tdFYaL0+WursNE6fLXRWOi9Plr0cIzgy3O9tTlLDROny10FppCxpvcYUdSa6y00dY03OAFHU1M"
    "untcuMKRGOg/qa6M0z+jk9G71qP4Y/q/L89l5fomUZs6jWuhLY6L0+WugtNHWNN7jCjqaKK8x1pU"
    "MPOrHeKb+5XP5tyvFVG0rky6e1y4+UiMdF/qa2bDReny0UV+TfW62MrOvXleT/r7j9TyvE1IpJM/"
    "/9k="
)


class MediaOracleError(Exception):
    """A fixture or oracle contract did not hold."""


@dataclass(frozen=True)
class Fixture:
    """A deterministic test payload with the metadata an upload needs."""

    name: str
    filename: str
    mime_type: str
    data: bytes

    @property
    def size(self) -> int:
        return len(self.data)

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.data).hexdigest()

    def describe(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "filename": self.filename,
            "mime_type": self.mime_type,
            "size": self.size,
            "sha256": self.sha256,
        }


@dataclass(frozen=True)
class VerifyResult:
    """Structured outcome of comparing an expected payload to an actual one."""

    match: bool
    expected_sha256: str
    actual_sha256: str
    expected_size: int
    actual_size: int
    first_mismatch_offset: int | None

    def to_dict(self) -> dict[str, Any]:
        return {
            "match": self.match,
            "expected_sha256": self.expected_sha256,
            "actual_sha256": self.actual_sha256,
            "expected_size": self.expected_size,
            "actual_size": self.actual_size,
            "first_mismatch_offset": self.first_mismatch_offset,
        }


# ---------------------------------------------------------------------------
# Fixture builders
# ---------------------------------------------------------------------------


def _png_chunk(tag: bytes, payload: bytes) -> bytes:
    crc = binascii.crc32(tag + payload) & 0xFFFFFFFF
    return struct.pack(">I", len(payload)) + tag + payload + struct.pack(">I", crc)


def render_png(
    width: int,
    height: int,
    pixel: Callable[[int, int], tuple[int, int, int]],
) -> bytes:
    """Encodes an 8-bit RGB PNG with zlib only -- no imaging dependency.

    The output is a fully valid PNG (signature, IHDR, IDAT, IEND with correct
    CRCs) that BitmapFactory and the server's magic-byte sniffer both accept.
    """
    if width <= 0 or height <= 0:
        raise MediaOracleError(f"PNG dimensions must be positive, got {width}x{height}")
    scanlines = bytearray()
    for y in range(height):
        scanlines.append(0)  # filter type 0 (None) per scanline
        for x in range(width):
            r, g, b = pixel(x, y)
            scanlines += bytes((r & 0xFF, g & 0xFF, b & 0xFF))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        PNG_SIGNATURE
        + _png_chunk(b"IHDR", ihdr)
        + _png_chunk(b"IDAT", zlib.compress(bytes(scanlines), 9))
        + _png_chunk(b"IEND", b"")
    )


def _fixture_pixel(seed: int, width: int, height: int) -> Callable[[int, int], tuple[int, int, int]]:
    """A seed-stamped pattern: hue from the seed, gradient, white grid every 8px.

    Recognizable on a device screenshot and byte-deterministic for a given
    (seed, width, height).
    """

    def pixel(x: int, y: int) -> tuple[int, int, int]:
        base = (seed * 37) & 0xFF
        if x % 8 == 0 or y % 8 == 0:
            return (255 - base, base, 0x80)
        return (base, (x * 255) // max(1, width - 1), (y * 255) // max(1, height - 1))

    return pixel


def png_fixture(seed: int = DEFAULT_SEED, width: int = DEFAULT_WIDTH,
                height: int = DEFAULT_HEIGHT) -> Fixture:
    data = render_png(width, height, _fixture_pixel(seed, width, height))
    return Fixture("png", f"e2e-png-{seed}.png", "image/png", data)


def jpeg_fixture() -> Fixture:
    """The embedded canonical JPEG blob; identical bytes on every call."""
    return Fixture("jpeg", "e2e-jpeg.jpg", "image/jpeg", base64.b64decode(_JPEG_BLOB_B64))


def minimal_pdf(label: str) -> bytes:
    """Builds a small well-formed PDF (with xref) embedding ``label`` as text."""
    safe_label = "".join(ch if ch not in "()" else "-" for ch in label)
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 72 72] "
        b"/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        b"BT /F1 10 Tf 8 36 Td (" + safe_label.encode("ascii") + b") Tj ET",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>",
    ]
    stream_marker = b"stream\n"
    out = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for index, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{index} 0 obj\n".encode("ascii")
        if index == 4:
            out += f"<< /Length {len(body)} >>\n".encode("ascii") + stream_marker
            out += body + b"\nendstream\nendobj\n"
        else:
            out += body + b"\nendobj\n"
    xref_at = len(out)
    count = len(objects) + 1
    out += f"xref\n0 {count}\n".encode("ascii")
    out += b"0000000000 65535 f \n"
    for offset in offsets[1:]:
        out += f"{offset:010d} 00000 n \n".encode("ascii")
    out += (
        f"trailer\n<< /Size {count} /Root 1 0 R >>\nstartxref\n{xref_at}\n%%EOF\n"
    ).encode("ascii")
    return bytes(out)


def pdf_fixture(seed: int = DEFAULT_SEED) -> Fixture:
    """A pass-through fixture: ImageCompressor ignores non-image MIME types, so
    these bytes are what actually land in storage even via the document picker."""
    data = minimal_pdf(f"nexachat-e2e-media-{seed}")
    return Fixture("pdf", f"e2e-media-{seed}.pdf", "application/pdf", data)


def text_fixture(seed: int = DEFAULT_SEED) -> Fixture:
    data = f"nexachat e2e media fixture seed={seed}\n".encode("utf-8") * 8
    return Fixture("txt", f"e2e-media-{seed}.txt", "text/plain", data)


def fixture_from_bytes(name: str, filename: str, mime_type: str, data: bytes) -> Fixture:
    """Wraps an existing on-disk payload (e.g. the server-test mp4/m4a fixtures
    under ``server/src/test/resources/fixtures/``) as a named Fixture."""
    return Fixture(name, filename, mime_type, bytes(data))


_BUILDERS: dict[str, Callable[..., Fixture]] = {
    "png": lambda seed, width, height: png_fixture(seed, width, height),
    "jpeg": lambda seed, width, height: jpeg_fixture(),
    "pdf": lambda seed, width, height: pdf_fixture(seed),
    "txt": lambda seed, width, height: text_fixture(seed),
}
FIXTURE_NAMES = tuple(sorted(_BUILDERS))


def build_fixture(
    name: str,
    *,
    seed: int = DEFAULT_SEED,
    width: int = DEFAULT_WIDTH,
    height: int = DEFAULT_HEIGHT,
) -> Fixture:
    builder = _BUILDERS.get(name)
    if builder is None:
        raise MediaOracleError(
            f"unknown fixture {name!r}; expected one of {', '.join(FIXTURE_NAMES)}"
        )
    return builder(seed, width, height)


# ---------------------------------------------------------------------------
# SHA-256 byte oracle
# ---------------------------------------------------------------------------


def read_source(source: bytes | bytearray | str | Path | Fixture) -> bytes:
    """Accepts raw bytes, a Fixture, or a filesystem path and returns bytes."""
    if isinstance(source, Fixture):
        return source.data
    if isinstance(source, (bytes, bytearray)):
        return bytes(source)
    path = Path(source)
    try:
        return path.read_bytes()
    except OSError as exc:
        raise MediaOracleError(f"cannot read payload {path}: {exc}") from exc


def sha256_hex(source: bytes | bytearray | str | Path | Fixture) -> str:
    """SHA-256 hex digest of bytes, a Fixture, or a file's contents."""
    return hashlib.sha256(read_source(source)).hexdigest()


def expected_sha256(source: bytes | str | Path | Fixture) -> str:
    """The hash a downloaded payload must equal for byte identity."""
    return sha256_hex(source)


def verify_payload(
    expected: bytes | str | Path | Fixture,
    actual: bytes | str | Path | Fixture,
) -> VerifyResult:
    """Compares two payloads by hash and size; reports the first differing byte
    offset (``None`` when identical or only sizes differ before any shared byte).
    """
    expected_bytes = read_source(expected)
    actual_bytes = read_source(actual)
    shared = min(len(expected_bytes), len(actual_bytes))
    first_diff: int | None = None
    for offset in range(shared):
        if expected_bytes[offset] != actual_bytes[offset]:
            first_diff = offset
            break
    match = (
        first_diff is None
        and len(expected_bytes) == len(actual_bytes)
        and hashlib.sha256(expected_bytes).digest() == hashlib.sha256(actual_bytes).digest()
    )
    return VerifyResult(
        match=match,
        expected_sha256=hashlib.sha256(expected_bytes).hexdigest(),
        actual_sha256=hashlib.sha256(actual_bytes).hexdigest(),
        expected_size=len(expected_bytes),
        actual_size=len(actual_bytes),
        first_mismatch_offset=first_diff,
    )


# ---------------------------------------------------------------------------
# Attachment REST helpers (stdlib multipart over e2e_common.Client)
# ---------------------------------------------------------------------------


def encode_multipart(
    file_bytes: bytes,
    filename: str,
    mime_type: str,
    fields: dict[str, str] | None = None,
    *,
    boundary: str | None = None,
) -> tuple[bytes, str]:
    """Builds a ``multipart/form-data`` body with one ``file`` part plus text
    fields, matching ``AttachmentController.upload`` (single file part only)."""
    boundary = boundary or "----nexachat-e2e-" + uuid.uuid4().hex
    if '"' in filename or "\r" in filename or "\n" in filename:
        raise MediaOracleError(f"unsafe multipart filename {filename!r}")
    body = bytearray()
    for key, value in (fields or {}).items():
        body += (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{key}"\r\n\r\n'
            f"{value}\r\n"
        ).encode("utf-8")
    body += (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: {mime_type}\r\n\r\n"
    ).encode("utf-8")
    body += file_bytes
    body += f"\r\n--{boundary}--\r\n".encode("utf-8")
    return bytes(body), f"multipart/form-data; boundary={boundary}"


def upload_attachment(
    client: Client,
    token: str,
    fixture: Fixture,
    *,
    width: int | None = None,
    height: int | None = None,
    duration: int | None = None,
    blur_hash: str | None = None,
) -> dict:
    """POSTs the fixture to ``/api/v1/attachments``; returns the 201 JSON body
    (``id``, ``url``, ``originalName``, ``mimeType``, ``sizeBytes``, ...)."""
    fields: dict[str, str] = {}
    if width is not None:
        fields["width"] = str(width)
    if height is not None:
        fields["height"] = str(height)
    if duration is not None:
        fields["duration"] = str(duration)
    if blur_hash is not None:
        fields["blurHash"] = blur_hash
    body, content_type = encode_multipart(
        fixture.data, fixture.filename, fixture.mime_type, fields
    )
    response = client.request(
        "POST", "/api/v1/attachments", token=token, raw_body=(body, content_type)
    )
    check(
        response.status == 201,
        f"attachment upload expected 201, got {response}",
    )
    data = response.json() or {}
    check(bool(data.get("id")), f"upload response has no id: {data!r}")
    return data


def download_attachment(client: Client, token: str, attachment_id: str) -> bytes:
    """GETs ``/api/v1/attachments/{id}/content`` and returns the stored bytes."""
    response = client.get(
        f"/api/v1/attachments/{attachment_id}/content", token=token
    )
    check(
        response.status == 200,
        f"attachment content expected 200, got {response.status}: {response.text[:200]}",
    )
    return response.body


def fetch_file(
    client: Client,
    token: str,
    url_or_key: str,
    *,
    byte_range: str | None = None,
) -> Response:
    """GETs ``/api/v1/files/{key}`` (accepts a storage key or a resolved ``url``)
    and returns the raw Response so callers can inspect status/headers."""
    path = url_or_key if url_or_key.startswith("/") else FILES_PREFIX + url_or_key
    headers = {"Range": byte_range} if byte_range is not None else None
    return client.get(path, token=token, headers=headers)


def check_range(
    client: Client,
    token: str,
    url_or_key: str,
    fixture: Fixture,
    start: int,
    end: int,
) -> dict[str, Any]:
    """Asserts a ``Range: bytes=start-end`` request returns 206 whose body is
    exactly the fixture slice -- the row's 'Range is 206' assertion."""
    if not (0 <= start <= end):
        raise MediaOracleError(f"invalid range {start}-{end}")
    response = fetch_file(client, token, url_or_key, byte_range=f"bytes={start}-{end}")
    expected_slice = fixture.data[start : end + 1]
    result = {
        "status": response.status,
        "content_range": response.headers.get("Content-Range"),
        "expected_slice_size": len(expected_slice),
        "actual_size": len(response.body),
        "slice_match": response.body == expected_slice,
    }
    result["ok"] = response.status == 206 and result["slice_match"]
    return result


def upload_and_verify(
    client: Client,
    token: str,
    fixture: Fixture,
    **upload_kwargs: Any,
) -> dict[str, Any]:
    """Upload -> download -> hash-verify; returns the attachment DTO plus the
    structured VerifyResult. This is the REST half of the media row's
    'Persisted byte hash and MIME are correct' assertion."""
    attachment = upload_attachment(client, token, fixture, **upload_kwargs)
    payload = download_attachment(client, token, attachment["id"])
    result = verify_payload(fixture.data, payload)
    return {
        "ok": result.match
        and attachment.get("sizeBytes") == fixture.size
        and attachment.get("mimeType") == fixture.mime_type,
        "attachment": attachment,
        "verify": result.to_dict(),
    }


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _resolve_token(args: argparse.Namespace, client: Client) -> str:
    token = getattr(args, "token", None) or os.environ.get(TOKEN_ENV)
    if token:
        return token
    phone = getattr(args, "phone", None)
    if phone:
        return login(client, phone)["accessToken"]
    raise MediaOracleError(
        f"no credentials: pass --token, set ${TOKEN_ENV}, or use --phone to log in"
    )


def _fixture_from_args(args: argparse.Namespace) -> Fixture:
    return build_fixture(
        args.name, seed=args.seed, width=args.width, height=args.height
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("list", help="List fixture names")

    fixture = subparsers.add_parser("fixture", help="Write fixture bytes to a file")
    fixture.add_argument("name", choices=FIXTURE_NAMES)
    fixture.add_argument("--out", required=True, help="Output path")
    fixture.add_argument("--seed", type=int, default=DEFAULT_SEED)
    fixture.add_argument("--width", type=int, default=DEFAULT_WIDTH)
    fixture.add_argument("--height", type=int, default=DEFAULT_HEIGHT)

    hash_cmd = subparsers.add_parser("hash", help="SHA-256 of a file or fixture")
    hash_cmd.add_argument("source", help="File path, or a fixture name with --fixture")
    hash_cmd.add_argument("--fixture", action="store_true",
                          help="Treat source as a fixture name")
    hash_cmd.add_argument("--seed", type=int, default=DEFAULT_SEED)
    hash_cmd.add_argument("--width", type=int, default=DEFAULT_WIDTH)
    hash_cmd.add_argument("--height", type=int, default=DEFAULT_HEIGHT)

    verify = subparsers.add_parser(
        "verify", help="Compare a payload against a fixture or hex digest")
    verify.add_argument("expected",
                        help="Fixture name, sha256 hex digest, or file path")
    verify.add_argument("actual", help="File path of the downloaded/extracted payload")
    verify.add_argument("--seed", type=int, default=DEFAULT_SEED)
    verify.add_argument("--width", type=int, default=DEFAULT_WIDTH)
    verify.add_argument("--height", type=int, default=DEFAULT_HEIGHT)

    net = argparse.ArgumentParser(add_help=False)
    net.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://localhost:8080"))
    net.add_argument("--token", default=None,
                     help=f"Bearer JWT (or ${TOKEN_ENV})")
    net.add_argument("--phone", default=None,
                     help="Seed phone to log in via e2e_common.login when no token")

    upload = subparsers.add_parser("upload", parents=[net],
                                   help="Upload a fixture, print the attachment JSON")
    upload.add_argument("name", choices=FIXTURE_NAMES)
    upload.add_argument("--seed", type=int, default=DEFAULT_SEED)
    upload.add_argument("--width", type=int, default=DEFAULT_WIDTH)
    upload.add_argument("--height", type=int, default=DEFAULT_HEIGHT)

    roundtrip = subparsers.add_parser("roundtrip", parents=[net],
                                      help="Upload + download + hash-verify a fixture")
    roundtrip.add_argument("name", choices=FIXTURE_NAMES)
    roundtrip.add_argument("--seed", type=int, default=DEFAULT_SEED)
    roundtrip.add_argument("--width", type=int, default=DEFAULT_WIDTH)
    roundtrip.add_argument("--height", type=int, default=DEFAULT_HEIGHT)

    fetch = subparsers.add_parser("fetch", parents=[net],
                                  help="GET /api/v1/files/{key}, optionally ranged")
    fetch.add_argument("key", help="Storage key or the attachment's url field")
    fetch.add_argument("--range", dest="byte_range", default=None,
                       help="e.g. bytes=0-99")
    fetch.add_argument("--out", default=None, help="Write body to this path")

    return parser


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = build_parser()
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])

    try:
        if args.command == "list":
            for name in FIXTURE_NAMES:
                print(name)
            return 0

        if args.command == "fixture":
            fixture = _fixture_from_args(args)
            Path(args.out).write_bytes(fixture.data)
            print(json.dumps(fixture.describe(), indent=2))
            return 0

        if args.command == "hash":
            if args.fixture:
                print(build_fixture(args.source, seed=args.seed, width=args.width,
                                    height=args.height).sha256)
            else:
                print(sha256_hex(args.source))
            return 0

        if args.command == "verify":
            if args.expected in _BUILDERS:
                expected: Any = build_fixture(
                    args.expected, seed=args.seed, width=args.width, height=args.height)
            elif len(args.expected) == 64 and all(
                    ch in "0123456789abcdefABCDEF" for ch in args.expected):
                actual = read_source(args.actual)
                result = VerifyResult(
                    match=hashlib.sha256(actual).hexdigest().lower()
                    == args.expected.lower(),
                    expected_sha256=args.expected.lower(),
                    actual_sha256=hashlib.sha256(actual).hexdigest(),
                    expected_size=-1,
                    actual_size=len(actual),
                    first_mismatch_offset=None,
                )
                print(json.dumps(result.to_dict(), indent=2))
                return 0 if result.match else 1
            else:
                expected = Path(args.expected)
            result = verify_payload(expected, Path(args.actual))
            print(json.dumps(result.to_dict(), indent=2))
            return 0 if result.match else 1

        client = Client(base_url=args.base_url)
        if args.command == "upload":
            token = _resolve_token(args, client)
            fixture = _fixture_from_args(args)
            print(json.dumps(upload_attachment(client, token, fixture), indent=2))
            return 0

        if args.command == "roundtrip":
            token = _resolve_token(args, client)
            fixture = _fixture_from_args(args)
            result = upload_and_verify(client, token, fixture)
            print(json.dumps(result, indent=2))
            return 0 if result["ok"] else 1

        if args.command == "fetch":
            token = _resolve_token(args, client)
            response = fetch_file(client, token, args.key, byte_range=args.byte_range)
            print(json.dumps({
                "status": response.status,
                "content_type": response.headers.get("Content-Type"),
                "content_range": response.headers.get("Content-Range"),
                "size": len(response.body),
                "sha256": hashlib.sha256(response.body).hexdigest(),
            }, indent=2))
            if args.out:
                Path(args.out).write_bytes(response.body)
            return 0 if response.status in (200, 206) else 1

        parser.print_help(sys.stderr)
        return 2

    except MediaOracleError as err:
        print(f"media_oracle: error: {err}", file=sys.stderr)
        return 1
    except E2EFailure as err:
        print(f"media_oracle: error: {err}", file=sys.stderr)
        return 1
    except ValueError as err:
        print(f"media_oracle: error: {err}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
