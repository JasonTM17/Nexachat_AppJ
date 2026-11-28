"""Unified 4-tier Release QA & Live Verification package for NexaChat (Features #1-#16).

Provides shared safety guards, secret redaction, canonical Feature #1-#16
metadata, ReleaseCheck dataclasses, and the execution context shared across
Tier 1-4 checks and live production verification.
"""

from __future__ import annotations

import re
import secrets
import sys
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple
from urllib.parse import urlsplit

E2E_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
if str(E2E_ROOT) not in sys.path:
    sys.path.insert(0, str(E2E_ROOT))

from tools.e2e.social_suite.client import (  # noqa: E402
    BlockedCapability,
    E2EFailure,
    PendingMilestone,
    Response,
    SocialApiClient,
    check,
    expect_error,
    expect_status,
)

EPHEMERAL_PHONE_RE = re.compile(r"^\+84908\d{6}$")
LOOPBACK_HOSTS = frozenset({"127.0.0.1", "localhost", "::1", "10.0.2.2"})

FEATURE_NAMES: Dict[str, str] = {
    "F1": "Auth & Account — Phone/Email Registration & Login",
    "F2": "Auth & Account — Google Sign-In & Sign-Up",
    "F3": "Auth & Account — Account Recovery & Self-Service Deletion",
    "F4": "Messaging — Direct & Group Chat + Outbox + Sync",
    "F5": "Messaging — Privacy, Safety & Preferences",
    "F6": "Groups & Collaboration — Invites, Polls, Reminders",
    "F7": "Media — Attachments, Avatars & Range Streaming",
    "F8": "Calls — 1-to-1 WebRTC Voice & Video Calls",
    "F9": "AI & Security — Summary, Ask RAG, Translation, E2EE",
    "F10": "Streaks — Direct Chat & Check-in Streaks",
    "F11": "Social (Android + Backend) — Public Feed, Edit, Search, Author Profile",
    "F12": "Social Web (site/social/) — Browser Client, Kill Switch & CMS",
    "F13": "Admin Web (admin-web/ -> site/admin/) — Staff Moderation & Social Settings",
    "F14": "Public Site (site/) — Marketing, Download, Join, Legal & App Links",
    "F15": "Design System — Tonal Plum Unification",
    "F16": "Release, CI, Repo Hygiene & Live Render/Vercel Deploy Pipeline",
}

_JWT_RE = re.compile(r"\beyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\b")
_BEARER_RE = re.compile(r"(Bearer\s+)[A-Za-z0-9._\-+/=]{12,}", re.IGNORECASE)
_PASSWORD_JSON_RE = re.compile(
    r'("(?:password|oldPassword|newPassword)"\s*:\s*")((?:\\.|[^"\\])*)(")',
    re.IGNORECASE,
)
_PASSWORD_KV_RE = re.compile(r"(password\s*[=:]\s*)[^\s,;\"']+", re.IGNORECASE)
_NON_EPHEMERAL_PHONE_RE = re.compile(r"(?<!\d)(\+(?!84908\d{6}\b)\d{8,15})(?!\d)")


class SafetyGuardError(RuntimeError):
    """Raised when an operation violates local-vs-live safety invariants."""


def is_loopback_url(url: str) -> bool:
    """Returns True only if the URL points to a known loopback/emulator host."""
    if not url:
        return False
    parsed = urlsplit(url.strip())
    host = (parsed.hostname or "").strip("[]").lower()
    return host in LOOPBACK_HOSTS


def assert_ephemeral_phone(phone: str) -> str:
    """Enforces the strict +84908xxxxxx disposable-account phone range."""
    value = (phone or "").strip()
    if not EPHEMERAL_PHONE_RE.match(value):
        raise SafetyGuardError(
            f"Refusing non-ephemeral phone {redact_secrets(value)!r}; "
            "live-safe flows MUST use +84908xxxxxx"
        )
    return value


def generate_ephemeral_phone() -> str:
    """Generates a random phone strictly inside +84908xxxxxx."""
    suffix = f"{secrets.randbelow(1_000_000):06d}"
    return assert_ephemeral_phone(f"+84908{suffix}")


def generate_strong_password() -> str:
    """Generates a strong per-run throwaway password meeting server complexity rules."""
    return f"Qa!{secrets.token_hex(8)}9Z"


def redact_secrets(text: str) -> str:
    """Scrubs JWTs, Bearer tokens, passwords, and non-ephemeral phone numbers."""
    if not text:
        return text
    out = _JWT_RE.sub("[REDACTED_JWT]", text)
    out = _BEARER_RE.sub(r"\1[REDACTED_TOKEN]", out)
    out = _PASSWORD_JSON_RE.sub(r"\1[REDACTED_PASSWORD]\3", out)
    out = _PASSWORD_KV_RE.sub(r"\1[REDACTED_PASSWORD]", out)
    out = _NON_EPHEMERAL_PHONE_RE.sub("[REDACTED_PHONE]", out)
    return out


@dataclass(frozen=True)
class ReleaseCheck:
    """Canonical check registration item across Tiers 1-4."""

    check_id: str
    feature: str
    tier: int
    title: str
    live_safe: bool
    source_suite: str
    fn: Callable[["ReleaseQaContext"], None]


@dataclass
class ReleaseCheckOutcome:
    """Result record emitted by the release QA orchestrator."""

    check_id: str
    feature: str
    tier: int
    title: str
    live_safe: bool
    source_suite: str
    status: str  # PASS | FAIL | BLOCKED_CAPABILITY | SKIPPED_LIVE_GUARD
    detail: str = ""
    duration_ms: int = 0


class ReleaseQaContext:
    """Shared execution context for Release QA Tiers 1-4 and live verification."""

    def __init__(
        self,
        *,
        spring: SocialApiClient,
        proxy: Optional[SocialApiClient] = None,
        sessions: Any = None,
        mode: str = "local",
        base_url: str = "",
        site_url: str = "",
        admin_base_url: str = "",
        mail_sink: Any = None,
        db_info: Optional[Dict[str, str]] = None,
        android_enabled: bool = False,
        android_serial: str = "emulator-5554",
        apk_path: Optional[str] = None,
        screenshots_dir: Optional[str] = None,
        strict_public_plum: bool = False,
        social_suite_ctx: Any = None,
    ) -> None:
        self.spring = spring
        self.proxy = proxy or spring
        self.sessions = sessions
        self.mode = mode
        self.base_url = base_url.rstrip("/")
        self.site_url = site_url.rstrip("/")
        self.admin_base_url = admin_base_url.rstrip("/") if admin_base_url else (
            f"{self.site_url}/admin" if self.site_url else ""
        )
        self.mail_sink = mail_sink
        self.db_info = db_info
        self.android_enabled = android_enabled
        self.android_serial = android_serial
        self.apk_path = apk_path
        self.screenshots_dir: Optional[Path] = Path(screenshots_dir) if screenshots_dir else None
        self.screenshots_saved: List[str] = []
        self.strict_public_plum = strict_public_plum
        self.social_suite_ctx = social_suite_ctx
        self.nonce = uuid.uuid4().hex[:8]
        self.shared: Dict[str, Any] = {}
        self.ephemeral_accounts: List[Dict[str, Any]] = []

    def is_local_target(self) -> bool:
        return is_loopback_url(self.base_url)

    def require_local_target(self, reason: str) -> None:
        if not self.is_local_target():
            raise SafetyGuardError(
                f"Refused non-loopback operation ({reason}) against {self.base_url!r}"
            )

    def require_local_db(self, reason: str) -> Dict[str, str]:
        self.require_local_target(reason)
        if not self.db_info:
            raise BlockedCapability(f"Local disposable DB metadata unavailable for {reason}")
        return self.db_info

    def require_sessions(self, reason: str) -> Any:
        self.require_local_target(reason)
        if self.sessions is None:
            raise BlockedCapability(f"Fixture session pool unavailable for {reason}")
        return self.sessions

    def token(self, role_key: str) -> str:
        sessions = self.require_sessions(f"fixture role {role_key}")
        return sessions.token(role_key)

    def uid(self, role_key: str) -> str:
        sessions = self.require_sessions(f"fixture uid {role_key}")
        return sessions.uid(role_key)

    def phone(self, role_key: str) -> str:
        sessions = self.require_sessions(f"fixture phone {role_key}")
        return sessions.phone(role_key)

    def register_ephemeral(
        self,
        display_name: str,
        *,
        phone: Optional[str] = None,
        password: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Registers an ephemeral +84908xxxxxx account and tracks it for guaranteed cleanup."""
        chosen_phone = assert_ephemeral_phone(phone or generate_ephemeral_phone())
        chosen_pw = password or generate_strong_password()
        resp = self.spring.post(
            "/api/v1/auth/register",
            body={
                "phone": chosen_phone,
                "password": chosen_pw,
                "displayName": display_name,
            },
        )
        if resp.status == 409 and phone is None:
            chosen_phone = generate_ephemeral_phone()
            resp = self.spring.post(
                "/api/v1/auth/register",
                body={
                    "phone": chosen_phone,
                    "password": chosen_pw,
                    "displayName": display_name,
                },
            )
        expect_status(resp, (200, 201), f"register ephemeral {chosen_phone}")
        data = resp.json() or {}
        user = data.get("user") or {}
        record = {
            "phone": chosen_phone,
            "password": chosen_pw,
            "displayName": user.get("displayName") or display_name,
            "userId": str(user["id"]),
            "accessToken": data["accessToken"],
            "refreshToken": data.get("refreshToken", ""),
        }
        self.ephemeral_accounts.append(record)
        return record

    def cleanup_ephemeral(self, account: Dict[str, Any]) -> None:
        """Deletes a single ephemeral account via DELETE /api/v1/users/me."""
        phone = account.get("phone", "")
        if phone:
            assert_ephemeral_phone(phone)
        tok = account.get("accessToken")
        pw = account.get("password")
        if not tok:
            return
        resp = self.spring.request(
            "DELETE",
            "/api/v1/users/me",
            token=tok,
            body={"password": pw} if pw else {},
        )
        if resp.status not in (204, 401, 404):
            # Best-effort fallback: refresh or re-login if access token rotated
            if pw and phone:
                login_resp = self.spring.post(
                    "/api/v1/auth/login",
                    body={"identifier": phone, "password": pw},
                )
                if login_resp.status == 200:
                    fresh_tok = (login_resp.json() or {}).get("accessToken")
                    if fresh_tok:
                        self.spring.request(
                            "DELETE",
                            "/api/v1/users/me",
                            token=fresh_tok,
                            body={"password": pw},
                        )

    def cleanup_all_ephemeral(self) -> None:
        """Best-effort cleanup of all tracked ephemeral accounts in reverse order."""
        while self.ephemeral_accounts:
            acc = self.ephemeral_accounts.pop()
            try:
                self.cleanup_ephemeral(acc)
            except Exception:
                pass

    def open_direct(self, token: str, peer_phone: str) -> str:
        resp = self.spring.post(
            "/api/v1/conversations/direct",
            token=token,
            body={"phone": peer_phone},
        )
        expect_status(resp, (200, 201), "open direct conversation")
        conv = resp.json() or {}
        check(conv.get("type") == "DIRECT" and bool(conv.get("id")), f"unexpected direct conv: {conv}")
        return str(conv["id"])

    def send_text(self, token: str, conversation_id: str, content: str) -> dict:
        resp = self.spring.post(
            f"/api/v1/conversations/{conversation_id}/messages",
            token=token,
            body={
                "clientMessageId": str(uuid.uuid4()),
                "type": "TEXT",
                "content": content,
                "attachmentIds": [],
            },
        )
        expect_status(resp, (200, 201), "send text message")
        return resp.json() or {}

    def ws_url(self) -> str:
        if self.base_url.startswith("https://"):
            return "wss://" + self.base_url[len("https://"):] + "/ws"
        if self.base_url.startswith("http://"):
            return "ws://" + self.base_url[len("http://"):] + "/ws"
        return self.base_url + "/ws"


__all__ = [
    "BlockedCapability",
    "E2E_ROOT",
    "E2EFailure",
    "EPHEMERAL_PHONE_RE",
    "FEATURE_NAMES",
    "LOOPBACK_HOSTS",
    "PendingMilestone",
    "REPO_ROOT",
    "ReleaseCheck",
    "ReleaseCheckOutcome",
    "ReleaseQaContext",
    "Response",
    "SafetyGuardError",
    "SocialApiClient",
    "assert_ephemeral_phone",
    "check",
    "expect_error",
    "expect_status",
    "generate_ephemeral_phone",
    "generate_strong_password",
    "is_loopback_url",
    "redact_secrets",
]
