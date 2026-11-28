"""HTTP client and structured test check/capability primitives for social_suite."""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Optional, Sequence, Tuple


DUAL_FEED_PREFIXES = ("/api/v1/feed", "/api/social/v1")


class E2EFailure(AssertionError):
    """Raised when a test assertion fails."""


class PendingMilestone(Exception):
    """Raised in --mode auto when a required milestone capability is absent."""


class BlockedCapability(Exception):
    """Raised when an optional host/device capability (e.g. emulator) is absent."""


def check(condition: bool, message: str) -> None:
    if not condition:
        raise E2EFailure(message)


def expect_status(resp: "Response", statuses: int | Sequence[int], what: str) -> None:
    if isinstance(statuses, int):
        statuses = (statuses,)
    check(
        resp.status in statuses,
        f"{what}: expected HTTP {'/'.join(map(str, statuses))}, got {resp}",
    )


def expect_error(resp: "Response", status: int, code: str, what: str) -> None:
    check(
        resp.status == status,
        f"{what}: expected HTTP {status} {code}, got {resp}",
    )
    check(
        resp.error_code() == code,
        f"{what}: expected error.code={code!r}, got {resp.error_code()!r} ({resp})",
    )


class Response:
    def __init__(self, status: int, headers: Dict[str, str], body: bytes) -> None:
        self.status = status
        self.headers = headers
        self.body = body

    @property
    def text(self) -> str:
        try:
            return self.body.decode("utf-8")
        except UnicodeDecodeError:
            return self.body.decode("latin-1")

    def json(self) -> Any:
        if not self.body:
            return None
        return json.loads(self.text)

    def error_code(self) -> Optional[str]:
        try:
            data = self.json()
        except ValueError:
            return None
        if isinstance(data, dict) and isinstance(data.get("error"), dict):
            return data["error"].get("code")
        return None

    def error_message(self) -> Optional[str]:
        try:
            data = self.json()
        except ValueError:
            return None
        if isinstance(data, dict) and isinstance(data.get("error"), dict):
            return data["error"].get("message")
        return None

    def header(self, name: str, default: Optional[str] = None) -> Optional[str]:
        target = name.lower()
        for key, value in (self.headers or {}).items():
            if key.lower() == target:
                return value
        return default

    def __repr__(self) -> str:
        snippet = self.text[:240] if self.body else ""
        return f"<HTTP {self.status} {snippet!r}>"


def build_multipart_body(
    files: Sequence[Tuple[str, str, str, bytes]],
    fields: Optional[Dict[str, str]] = None,
    boundary: Optional[str] = None,
) -> Tuple[bytes, str]:
    """Builds multipart/form-data supporting 0, 1, or N file parts."""
    boundary = boundary or ("sociale2e" + uuid.uuid4().hex)
    parts: List[bytes] = []
    for name, value in (fields or {}).items():
        parts.append(
            (
                f'--{boundary}\r\n'
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'
                f"{value}\r\n"
            ).encode("utf-8")
        )
    for field_name, filename, content_type, data in files:
        parts.append(
            (
                f'--{boundary}\r\n'
                f'Content-Disposition: form-data; name="{field_name}"; filename="{filename}"\r\n'
                f"Content-Type: {content_type}\r\n\r\n"
            ).encode("utf-8")
        )
        parts.append(data)
        parts.append(b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode("utf-8"))
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def retry_after_seconds(resp: Response, default: int = 15) -> int:
    try:
        data = resp.json() or {}
    except ValueError:
        data = {}
    details = (data.get("error") or {}).get("details") or {} if isinstance(data, dict) else {}
    val = details.get("retryAfterSeconds")
    if val is None:
        val = resp.header("retry-after")
    try:
        return max(1, int(val))
    except (TypeError, ValueError):
        return default


def reaction_entry(reactions: Optional[List[dict]], emoji: str) -> Optional[dict]:
    for item in reactions or []:
        if item.get("emoji") == emoji:
            return item
    return None


def reaction_path(prefix: str, post_id: str, emoji: str) -> str:
    return f"{prefix}/posts/{post_id}/reactions/{urllib.parse.quote(emoji, safe='')}"


class SocialApiClient:
    """Pure stdlib HTTP client for Spring Boot and Vercel proxy endpoints."""

    def __init__(self, base_url: str, timeout: float = 20.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def request(
        self,
        method: str,
        path: str,
        token: Optional[str] = None,
        body: Any = None,
        params: Optional[Dict[str, Any]] = None,
        raw_body: Optional[Tuple[bytes, str]] = None,
        headers: Optional[Dict[str, str]] = None,
    ) -> Response:
        url = self.base_url + path
        if params:
            filtered = {k: v for k, v in params.items() if v is not None}
            if filtered:
                url += "?" + urllib.parse.urlencode(filtered, quote_via=urllib.parse.quote)
        data = None
        req_headers: Dict[str, str] = {"Accept": "application/json"}
        if raw_body is not None:
            data, content_type = raw_body
            req_headers["Content-Type"] = content_type
        elif body is not None:
            data = json.dumps(body).encode("utf-8")
            req_headers["Content-Type"] = "application/json"
        if token:
            req_headers["Authorization"] = f"Bearer {token}"
        if headers:
            req_headers.update(headers)

        req = urllib.request.Request(url, data=data, headers=req_headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return Response(resp.status, dict(resp.headers), resp.read())
        except urllib.error.HTTPError as err:
            return Response(err.code, dict(err.headers), err.read())
        except urllib.error.URLError as err:
            raise E2EFailure(f"{method} {url} transport error: {err.reason}") from err

    def get(
        self,
        path: str,
        token: Optional[str] = None,
        params: Optional[Dict[str, Any]] = None,
        headers: Optional[Dict[str, str]] = None,
    ) -> Response:
        return self.request("GET", path, token=token, params=params, headers=headers)

    def post(
        self,
        path: str,
        token: Optional[str] = None,
        body: Any = None,
        headers: Optional[Dict[str, str]] = None,
    ) -> Response:
        return self.request("POST", path, token=token, body=body, headers=headers)

    def put(
        self,
        path: str,
        token: Optional[str] = None,
        body: Any = None,
        headers: Optional[Dict[str, str]] = None,
    ) -> Response:
        return self.request("PUT", path, token=token, body=body, headers=headers)

    def patch(
        self,
        path: str,
        token: Optional[str] = None,
        body: Any = None,
        headers: Optional[Dict[str, str]] = None,
    ) -> Response:
        return self.request("PATCH", path, token=token, body=body, headers=headers)

    def delete(
        self,
        path: str,
        token: Optional[str] = None,
        params: Optional[Dict[str, Any]] = None,
        headers: Optional[Dict[str, str]] = None,
    ) -> Response:
        return self.request("DELETE", path, token=token, params=params, headers=headers)

    def upload_multipart(
        self,
        path: str,
        token: str,
        files: Sequence[Tuple[str, str, str, bytes]],
        fields: Optional[Dict[str, str]] = None,
    ) -> Response:
        raw = build_multipart_body(files=files, fields=fields)
        return self.request("POST", path, token=token, raw_body=raw)

    def get_range(self, path: str, token: str, range_header: Optional[str] = None) -> Response:
        headers = {"Accept": "*/*"}
        if range_header is not None:
            headers["Range"] = range_header
        return self.get(path, token=token, headers=headers)

    def login_with_retry(self, identifier: str, password: str) -> dict:
        resp: Optional[Response] = None
        for attempt in range(3):
            resp = self.post(
                "/api/v1/auth/login",
                body={"identifier": identifier, "password": password},
            )
            if resp.status == 429 and attempt < 2:
                wait = min(65, retry_after_seconds(resp) + 1)
                time.sleep(wait)
                continue
            break
        assert resp is not None
        expect_status(resp, 200, f"login {identifier}")
        data = resp.json()
        check(bool(data.get("accessToken")), f"login {identifier} returned empty accessToken")
        return data

    def register_or_login(self, phone: str, password: str, display_name: str) -> dict:
        resp: Optional[Response] = None
        for attempt in range(4):
            resp = self.post(
                "/api/v1/auth/register",
                body={"phone": phone, "password": password, "displayName": display_name},
            )
            if resp.status in (200, 201):
                return resp.json()
            if resp.status == 429 and attempt < 3:
                wait = min(120, retry_after_seconds(resp) + 1)
                time.sleep(wait)
                continue
            break
        assert resp is not None
        if resp.status == 409:
            return self.login_with_retry(phone, password)
        raise E2EFailure(f"register {phone} expected 201/409, got {resp}")


@dataclass
class CheckCase:
    check_id: str
    feature: str
    tier: int
    milestone_gate: str  # BASELINE | M1 | M2 | M3 | M4
    title: str
    fn: Callable[["SuiteContext"], None]


@dataclass
class CheckOutcome:
    check_id: str
    feature: str
    tier: int
    milestone_gate: str
    title: str
    status: str  # PASS | FAIL | PENDING_MILESTONE | BLOCKED_CAPABILITY
    detail: str = ""
    duration_ms: int = 0


@dataclass
class CapabilitySnapshot:
    m1_edit: bool = False
    m1_authors: bool = False
    m1_search: bool = False
    m1_range_video: bool = False
    m1_multipart_guard: bool = False
    m2_social_features: bool = False
    m2_join_footer: bool = False
    m2_design_tokens: bool = False
    m3_moderator_gate: bool = False
    m3_draft_conflict_fix: bool = False
    m4_android_apk: bool = False

    @property
    def m1_all(self) -> bool:
        return (
            self.m1_edit
            and self.m1_authors
            and self.m1_search
            and self.m1_range_video
            and self.m1_multipart_guard
        )


class SuiteContext:
    """Shared execution context across Tiers 1–4 with in-memory sessions and cleanup."""

    def __init__(
        self,
        spring_client: SocialApiClient,
        proxy_client: SocialApiClient,
        sessions: Any,
        mode: str = "auto",
        proxy_base_url: str = "",
        spring_base_url: str = "",
        admin_base_url: str = "",
        android_enabled: bool = False,
        android_serial: str = "emulator-5554",
        android_apk: Optional[str] = None,
        android_package: Optional[str] = None,
        screenshots_dir: Optional[str] = None,
    ) -> None:
        self.spring = spring_client
        self.proxy = proxy_client
        self.sessions = sessions
        self.mode = mode  # auto | strict | baseline
        self.proxy_base_url = proxy_base_url
        self.spring_base_url = spring_base_url
        self.admin_base_url = admin_base_url or (f"{proxy_base_url.rstrip('/')}/admin" if proxy_base_url else "")
        self.android_enabled = android_enabled
        self.android_serial = android_serial
        self.android_apk = android_apk
        self.android_package = android_package
        self.screenshots_dir: Optional[Path] = Path(screenshots_dir) if screenshots_dir else None
        self.screenshots_saved: List[str] = []
        self.nonce = uuid.uuid4().hex[:8]
        self.caps = CapabilitySnapshot()
        self.shared: Dict[str, Any] = {}
        self.cleanup_blocks: List[Tuple[str, str]] = []
        self.cleanup_suspends: List[str] = []

    def capture_screenshot(self, page: Any, filename: str, *, full_page: bool = True) -> Optional[Path]:
        if self.screenshots_dir is None:
            return None
        self.screenshots_dir.mkdir(parents=True, exist_ok=True)
        out_path = self.screenshots_dir / filename
        page.screenshot(path=str(out_path), full_page=full_page)
        resolved = str(out_path.resolve())
        if resolved not in self.screenshots_saved:
            self.screenshots_saved.append(resolved)
        return out_path

    def token(self, role_key: str) -> str:
        return self.sessions.token(role_key)

    def uid(self, role_key: str) -> str:
        return self.sessions.uid(role_key)

    def phone(self, role_key: str) -> str:
        return self.sessions.phone(role_key)

    def text(self, label: str) -> str:
        return f"Social E2E {label} {self.nonce}"

    def set_social_flags(self, web_enabled: bool, video_enabled: bool) -> dict:
        admin_tok = self.token("admin")
        state = self.spring.get("/api/v1/admin/social/settings", token=admin_tok)
        expect_status(state, 200, "GET admin social settings")
        current = state.json()
        if (
            current.get("webEnabled") == web_enabled
            and current.get("videoUploadsEnabled") == video_enabled
        ):
            return current
        updated = self.spring.patch(
            "/api/v1/admin/social/settings/flags",
            token=admin_tok,
            body={
                "expectedRevision": current["revision"],
                "webEnabled": web_enabled,
                "videoUploadsEnabled": video_enabled,
            },
        )
        expect_status(updated, 200, "PATCH admin social flags")
        return updated.json()

    def get_admin_settings(self) -> dict:
        resp = self.spring.get("/api/v1/admin/social/settings", token=self.token("admin"))
        expect_status(resp, 200, "GET admin social settings")
        return resp.json()

    def create_post_safe(
        self,
        role_key: str,
        body: Optional[str],
        *,
        prefix: str = "/api/v1/feed",
        client_post_id: Optional[str] = None,
        image_id: Optional[str] = None,
        video_id: Optional[str] = None,
        via_proxy: bool = False,
    ) -> Response:
        client = self.proxy if via_proxy else self.spring
        payload: Dict[str, Any] = {"clientPostId": client_post_id or str(uuid.uuid4())}
        if body is not None:
            payload["body"] = body
        if image_id is not None:
            payload["imageId"] = image_id
        if video_id is not None:
            payload["videoId"] = video_id
        resp: Optional[Response] = None
        for attempt in range(4):
            resp = client.post(f"{prefix}/posts", token=self.token(role_key), body=payload)
            if resp.status == 429 and resp.error_code() == "RATE_LIMITED" and attempt < 3:
                wait = min(65, retry_after_seconds(resp) + 1)
                time.sleep(wait)
                continue
            break
        assert resp is not None
        return resp

    def ensure_block(self, blocker_role: str, target_role: str, blocked: bool) -> None:
        path = f"/api/v1/users/{self.uid(target_role)}/block"
        if blocked:
            resp = self.spring.post(path, token=self.token(blocker_role))
            expect_status(resp, 204, f"{blocker_role} blocks {target_role}")
            pair = (blocker_role, target_role)
            if pair not in self.cleanup_blocks:
                self.cleanup_blocks.append(pair)
        else:
            self.spring.delete(path, token=self.token(blocker_role))

    def require_milestone(self, gate: str, available: bool, reason: str) -> None:
        if gate == "BASELINE":
            return
        if not available:
            raise PendingMilestone(f"{gate}: {reason}")
