"""Pure helper utilities for NexaChat P0 E2E tests.

Zero external dependencies (Python stdlib only).
Covers:
- Secrets & PII masking (tokens, passwords, phones, IDs).
- STOMP 1.2 frame building and stream/buffer parsing.
- Sequence monotonic verification and watermark clamping.
- Safe random throwaway credential generators.
"""

from __future__ import annotations

import random
import secrets
import string
from typing import Any, Iterable, Sequence


# --- Masking & Redaction Helpers ---

def mask_phone(phone: str | None) -> str:
    """Masks phone numbers to avoid logging PII.

    Keeps the country/telecom prefix and last 3 digits, masking the middle.
    Example: '+84908123456' -> '+84908***456'
    """
    if not phone:
        return ""
    phone = phone.strip()
    if len(phone) < 8:
        return "***"
    if phone.startswith("+84") and len(phone) >= 11:
        # Keep +84 and next 3 digits (e.g. +84908), mask middle, keep last 3
        prefix = phone[:6]
        suffix = phone[-3:]
        return f"{prefix}***{suffix}"
    # Generic phone masking: keep first 4, last 3
    return f"{phone[:4]}***{phone[-3:]}"


def mask_token(token: str | None) -> str:
    """Redacts authentication tokens completely for log safety."""
    if not token:
        return "[EMPTY]"
    return "[REDACTED_TOKEN]"


def mask_password(password: str | None) -> str:
    """Redacts passwords completely."""
    if not password:
        return "[EMPTY]"
    return "[REDACTED_PASSWORD]"


def mask_id(identifier: str | None) -> str:
    """Masks long IDs / UUIDs for compact, safe logging.

    Example: '11112222-3333-4444-5555-666677778888' -> '1111...8888'
    """
    if not identifier:
        return ""
    s = str(identifier).strip()
    if len(s) <= 8:
        return "***"
    return f"{s[:4]}...{s[-4:]}"


# --- STOMP Frame Building & Parsing ---

def build_stomp_frame(
    command: str,
    headers: dict[str, str] | None = None,
    body: str = "",
) -> str:
    """Constructs a raw STOMP 1.2 frame terminated with null byte (\x00)."""
    lines = [command]
    if headers:
        for key, value in headers.items():
            lines.append(f"{key}:{value}")
    header_block = "\n".join(lines)
    return f"{header_block}\n\n{body}\x00"


def parse_stomp_frame(raw_frame: str) -> tuple[str, dict[str, str], str]:
    """Parses a single STOMP frame string into (command, headers, body).

    Leading newlines/heartbeats and trailing null bytes are stripped.
    """
    cleaned = raw_frame.lstrip("\r\n").rstrip("\x00")
    if not cleaned:
        return "", {}, ""
    head, _, body = cleaned.partition("\n\n")
    lines = head.split("\n")
    command = lines[0].strip()
    headers: dict[str, str] = {}
    for line in lines[1:]:
        line = line.strip("\r")
        if ":" in line:
            key, value = line.split(":", 1)
            headers[key] = value
    return command, headers, body


def parse_stomp_buffer(buffer: str) -> tuple[list[tuple[str, dict[str, str], str]], str]:
    """Splits a socket buffer by null bytes into parsed STOMP frames and remainder."""
    frames: list[tuple[str, dict[str, str], str]] = []
    while "\x00" in buffer:
        raw, buffer = buffer.split("\x00", 1)
        raw = raw.lstrip("\r\n")
        if not raw.strip():
            continue
        command, headers, body = parse_stomp_frame(raw)
        if command:
            frames.append((command, headers, body))
    return frames, buffer


# --- Invariants & Monotonic Sequence Checks ---

def check_monotonic_watermark(current_seq: int, update_seq: int) -> int:
    """Calculates updated watermark ensuring monotonic non-regression."""
    if update_seq < 0 or current_seq < 0:
        raise ValueError("Watermark sequences must be non-negative")
    return max(current_seq, update_seq)


def verify_monotonic_sequence(seqs: Sequence[int]) -> bool:
    """Checks that a sequence of sequence numbers is strictly increasing (seq[i] < seq[i+1])."""
    if len(seqs) <= 1:
        return True
    for i in range(len(seqs) - 1):
        if seqs[i] >= seqs[i + 1]:
            return False
    return True


def verify_no_duplicates(items: Iterable[Any]) -> bool:
    """Checks that all items in an iterable are unique."""
    seen: set[Any] = set()
    for item in items:
        if item in seen:
            return False
        seen.add(item)
    return True


def clamp_watermark(requested_seq: int, max_seq: int) -> int:
    """Clamps a watermark request to not exceed maximum sequence."""
    if requested_seq < 0 or max_seq < 0:
        raise ValueError("Sequences must be non-negative")
    return min(requested_seq, max_seq)


# --- Random Test Fixture Generators ---

def generate_test_phone(prefix: str = "+84908") -> str:
    """Generates a random 12-character Vietnamese E.164 phone in reserved test range.

    Prefix defaults to '+84908' (MobiFone mobile range in Vietnam).
    Combined with 6 random digits, this yields a valid 9-digit national subscriber number.
    Distinct from demo/seed range '+84900...'.
    """
    suffix = random.randint(100000, 999999)
    return f"{prefix}{suffix}"


def generate_test_password(length: int = 20) -> str:
    """Generates a strong, random password satisfying bcrypt constraints (8..72 chars)."""
    if length < 8 or length > 72:
        raise ValueError("Password length must be between 8 and 72 characters")
    # Guarantee at least one uppercase, lowercase, digit, and punctuation
    upper = secrets.choice(string.ascii_uppercase)
    lower = secrets.choice(string.ascii_lowercase)
    digit = secrets.choice(string.digits)
    punct = secrets.choice("@#$%^&*!_+-=")
    pool = string.ascii_letters + string.digits + "@#$%^&*!_+-="
    rest = [secrets.choice(pool) for _ in range(length - 4)]
    combined = [upper, lower, digit, punct] + rest
    secrets.SystemRandom().shuffle(combined)
    return "".join(combined)
