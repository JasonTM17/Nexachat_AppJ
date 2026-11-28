"""Deterministic fixture pool, in-memory session store, and media payloads.

All tokens are held strictly in memory for the duration of a suite run and are
never shared with %TEMP%/nexachat-e2e-tokens.json or printed to stdout/logs.
"""

from __future__ import annotations

import io
import os
import struct
import time
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Optional, Tuple

try:
    from PIL import Image
except ImportError:  # pragma: no cover - Pillow is installed in CI/dev host
    Image = None


REPO_ROOT = Path(__file__).resolve().parents[3]
SERVER_FIXTURES_DIR = REPO_ROOT / "server" / "src" / "test" / "resources" / "fixtures"

FIXTURE_PASSWORD = "password123"

# Fixed 6-account fixture pool staying safely within RateLimitInterceptor's
# 10 req / 5 min per-IP auth cap.
FIXTURE_ACCOUNTS: Dict[str, Tuple[str, str]] = {
    "author": ("+84900000951", "Social E2E Author"),
    "reader": ("+84900000952", "Social E2E Reader"),
    "blocker": ("+84900000953", "Social E2E Blocker"),
    "reactor": ("+84900000954", "Social E2E Reactor"),
    "moderator": ("+84900000955", "Social E2E Moderator"),
    "admin": ("+84900000990", "Social E2E Admin"),
}

ADMIN_PHONES_CSV = "+84900000990"

DISTINCT_EMOJIS = [
    "\U0001F44D",  # thumbs up
    "\u2764\uFE0F",  # heart
    "\U0001F602",  # joy
    "\U0001F62E",  # open mouth
    "\U0001F622",  # cry
    "\U0001F525",  # fire
    "\U0001F44F",  # clap
    "\U0001F389",  # tada
]
NINTH_EMOJI = "\U0001F92F"

MAX_POST_BODY = 1000
MAX_COMMENT_BODY = 500
MAX_REPORT_DETAILS = 500
MAX_TITLE_LENGTH = 100
MAX_ANNOUNCEMENT_BODY = 500
MAX_VIDEO_BYTES = 4 * 1024 * 1024
POSTS_PER_MINUTE = 3
COMMENTS_PER_MINUTE = 10

NOT_AN_IMAGE_BYTES = b"this is plain text pretending to be an image"
VALID_JOIN_TOKEN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef0123456789_"  # 43 chars base64url


@dataclass
class SessionRecord:
    role_key: str
    phone: str
    display_name: str
    user_id: str
    access_token: str
    refresh_token: str
    server_role: str = "USER"


class SessionStore:
    """In-memory session store isolated per suite run."""

    def __init__(self) -> None:
        self._records: Dict[str, SessionRecord] = {}

    def set(self, record: SessionRecord) -> None:
        self._records[record.role_key] = record

    def get(self, role_key: str) -> SessionRecord:
        if role_key not in self._records:
            raise KeyError(f"Fixture account {role_key!r} has not been seeded")
        return self._records[role_key]

    def token(self, role_key: str) -> str:
        return self.get(role_key).access_token

    def uid(self, role_key: str) -> str:
        return self.get(role_key).user_id

    def phone(self, role_key: str) -> str:
        return self.get(role_key).phone

    def all_records(self) -> Dict[str, SessionRecord]:
        return dict(self._records)


def _minimal_png_bytes(width: int = 16, height: int = 16, color: Tuple[int, int, int] = (115, 63, 104)) -> bytes:
    """Pure-Python deterministic PNG generator fallback."""
    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    raw_rows = b"".join(b"\x00" + bytes(color) * width for _ in range(height))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw_rows))
        + chunk(b"IEND", b"")
    )


def make_png_bytes(width: int = 16, height: int = 16, color: Tuple[int, int, int] = (115, 63, 104)) -> bytes:
    if Image is not None:
        buf = io.BytesIO()
        Image.new("RGB", (width, height), color).save(buf, format="PNG")
        return buf.getvalue()
    return _minimal_png_bytes(width, height, color)


def make_jpeg_bytes(width: int = 16, height: int = 16, color: Tuple[int, int, int] = (36, 107, 87)) -> bytes:
    if Image is None:  # pragma: no cover
        raise RuntimeError("Pillow is required to synthesize JPEG fixture bytes")
    buf = io.BytesIO()
    Image.new("RGB", (width, height), color).save(buf, format="JPEG", quality=90)
    return buf.getvalue()


def make_webp_bytes(width: int = 16, height: int = 16, color: Tuple[int, int, int] = (115, 63, 104)) -> bytes:
    if Image is None:  # pragma: no cover
        raise RuntimeError("Pillow is required to synthesize WebP fixture bytes")
    buf = io.BytesIO()
    Image.new("RGB", (width, height), color).save(buf, format="WEBP")
    return buf.getvalue()


def load_valid_mp4_bytes() -> bytes:
    path = SERVER_FIXTURES_DIR / "feed_video_h264_64x48.mp4"
    if not path.is_file():
        raise FileNotFoundError(f"Missing MP4 fixture: {path}")
    return path.read_bytes()


def load_h264_aac_mp4_bytes() -> bytes:
    path = SERVER_FIXTURES_DIR / "video_h264_aac_isom.mp4"
    if not path.is_file():
        return load_valid_mp4_bytes()
    return path.read_bytes()


def load_audio_only_m4a_bytes(trailing_moov: bool = False) -> bytes:
    name = "voice_aac_isom_moov_end.m4a" if trailing_moov else "voice_aac_isom_faststart.m4a"
    path = SERVER_FIXTURES_DIR / name
    if not path.is_file():
        raise FileNotFoundError(f"Missing M4A fixture: {path}")
    return path.read_bytes()


def make_oversized_mp4_bytes() -> bytes:
    base = load_valid_mp4_bytes()
    pad_needed = MAX_VIDEO_BYTES + 64 - len(base)
    if pad_needed <= 0:
        return base
    # Append a valid-looking MP4 'free' box so container prefix remains valid MP4
    free_size = max(8, pad_needed)
    return base + struct.pack(">I", free_size) + b"free" + (b"\x00" * (free_size - 8))


def wait_next_second_boundary() -> None:
    """Sleep past the current whole-second boundary for JwtAuthFilter cutoffs."""
    now = time.time()
    frac = now - int(now)
    sleep_for = max(0.05, 1.08 - frac)
    time.sleep(sleep_for)


def seed_fixture_pool(client, store: SessionStore) -> SessionStore:
    """Registers the 6-account fixture pool once and promotes moderator."""
    for role_key, (phone, display_name) in FIXTURE_ACCOUNTS.items():
        data = client.register_or_login(phone, FIXTURE_PASSWORD, display_name)
        user = data.get("user") or {}
        store.set(
            SessionRecord(
                role_key=role_key,
                phone=phone,
                display_name=user.get("displayName") or display_name,
                user_id=str(user["id"]),
                access_token=data["accessToken"],
                refresh_token=data.get("refreshToken", ""),
                server_role=user.get("role") or ("ADMIN" if role_key == "admin" else "USER"),
            )
        )

    # Ensure moderator has MODERATOR role via ADMIN API (requires 1 login after 1s boundary)
    admin_rec = store.get("admin")
    mod_rec = store.get("moderator")
    me_mod = client.get("/api/v1/admin/me", token=mod_rec.access_token)
    if me_mod.status != 200 or (me_mod.json() or {}).get("role") != "MODERATOR":
        resp = client.patch(
            f"/api/v1/admin/users/{mod_rec.user_id}/role",
            token=admin_rec.access_token,
            body={"role": "MODERATOR", "reason": "Social E2E suite moderator fixture"},
        )
        if resp.status != 200:
            raise AssertionError(f"Failed to promote moderator fixture: {resp}")
        # AdminUserService.changeRole revokes prior tokens at second resolution
        wait_next_second_boundary()
        login_data = client.login_with_retry(mod_rec.phone, FIXTURE_PASSWORD)
        mod_rec.access_token = login_data["accessToken"]
        mod_rec.refresh_token = login_data.get("refreshToken", "")
        mod_rec.server_role = "MODERATOR"
        store.set(mod_rec)

    return store
