"""Shared helpers for the NexaChat end-to-end scripts.

Standard library only: the CI/demo machines may not have jq, and the smoke
script must run offline against a local backend. Every check raises
E2EFailure, so a caller stops at the first broken contract assertion and
exits non-zero.
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

DEFAULT_BASE_URL = "http://localhost:8080"

# Seed accounts from demo/seed.sql (password in docs/screenshots-manifest.md).
# A and B share a DIRECT conversation created by these scripts; C is a seed
# user with no membership in it, used for negative authorization checks.
PASSWORD = "password123"
USER_A = {"phone": "+84900000001", "name": "An"}
USER_B = {"phone": "+84900000003", "name": "Chi"}
USER_C = {"phone": "+84900000002", "name": "Binh"}


class E2EFailure(AssertionError):
    """A contract expectation did not hold."""


def check(condition: bool, message: str) -> None:
    if not condition:
        raise E2EFailure(message)


def expect(condition: bool, message: str) -> None:
    check(condition, message)


class Response:
    def __init__(self, status: int, headers: dict, body: bytes):
        self.status = status
        self.headers = headers
        self.body = body

    @property
    def text(self) -> str:
        try:
            return self.body.decode("utf-8")
        except UnicodeDecodeError:
            return self.body.decode("latin-1")

    def json(self):
        if not self.body:
            return None
        return json.loads(self.text)

    def error_code(self):
        data = self.json()
        if isinstance(data, dict) and isinstance(data.get("error"), dict):
            return data["error"].get("code")
        return None

    def __repr__(self) -> str:
        return f"<HTTP {self.status} {self.text[:300]!r}>"


class Client:
    def __init__(self, base_url: str = DEFAULT_BASE_URL, timeout: float = 15.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def request(
        self,
        method: str,
        path: str,
        token: str | None = None,
        body=None,
        params: dict | None = None,
        raw_body: tuple[bytes, str] | None = None,
    ) -> Response:
        url = self.base_url + path
        if params:
            url += "?" + urllib.parse.urlencode(params, quote_via=urllib.parse.quote)
        data = None
        headers = {"Accept": "application/json"}
        if raw_body is not None:
            data, content_type = raw_body
            headers["Content-Type"] = content_type
        elif body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return Response(response.status, dict(response.headers), response.read())
        except urllib.error.HTTPError as error:
            return Response(error.code, dict(error.headers), error.read())
        except urllib.error.URLError as error:
            raise E2EFailure(f"{method} {url} failed: {error.reason}") from error

    def get(self, path, token=None, params=None) -> Response:
        return self.request("GET", path, token=token, params=params)

    def post(self, path, token=None, body=None) -> Response:
        return self.request("POST", path, token=token, body=body)

    def patch(self, path, token=None, body=None) -> Response:
        return self.request("PATCH", path, token=token, body=body)

    def delete(self, path, token=None) -> Response:
        return self.request("DELETE", path, token=token)


def login(client: Client, phone: str) -> dict:
    """Logs in, reusing a still-valid token cached in the OS temp dir.

    Login is limited to 10 requests / 5 min per IP, and the whole E2E suite
    signs in several seed users per run. The cache (0600 on POSIX) keeps a
    token for at most 9 minutes and validates it with GET /users/me before
    use; delete `nexachat-e2e-tokens.json` from the temp dir to force a fresh
    login. Tokens are never printed or written into the repo.
    """
    cached = _cached_session(client, phone)
    if cached is not None:
        return cached
    for attempt in range(3):
        response = client.post(
            "/api/v1/auth/login", body={"phone": phone, "password": PASSWORD}
        )
        if response.status == 429 and attempt < 2:
            # Shared demo backend: other clients consume the 10/5 min per-IP
            # login budget. Wait for the bucket instead of failing the run.
            details = (response.json() or {}).get("error", {}).get("details") or {}
            wait = min(60, int(details.get("retryAfterSeconds", 15)) + 1)
            print(f"[wait] login budget for {phone} exhausted, retrying in {wait}s")
            time.sleep(wait)
            continue
        check(
            response.status == 200,
            f"login {phone} expected 200, got {response}",
        )
        break
    data = response.json()
    check(bool(data.get("accessToken")), f"login {phone} returned no accessToken")
    check(
        data["user"]["phone"] == phone,
        f"login {phone} returned user {data['user']['phone']}",
    )
    _store_session(phone, data["accessToken"], data["user"]["id"])
    return data


def _cache_file() -> str:
    return os.path.join(tempfile.gettempdir(), "nexachat-e2e-tokens.json")


def _read_cache() -> dict:
    try:
        with open(_cache_file(), "r", encoding="utf-8") as handle:
            data = json.load(handle)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _write_cache(cache: dict) -> None:
    path = _cache_file()
    try:
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(cache, handle)
        os.chmod(path, 0o600)
    except OSError:
        pass


def _cached_session(client: Client, phone: str):
    entry = _read_cache().get(phone)
    if not entry or entry.get("expiresAt", 0) <= time.time():
        return None
    token = entry.get("accessToken")
    if not token:
        return None
    response = client.get("/api/v1/users/me", token=token)
    if response.status != 200 or response.json().get("id") != entry.get("userId"):
        return None
    return {"accessToken": token, "user": response.json()}


def _store_session(phone: str, token: str, user_id: str) -> None:
    cache = _read_cache()
    cache[phone] = {
        "accessToken": token,
        "userId": user_id,
        # Access tokens live 15 min; stop reusing well before that.
        "expiresAt": time.time() + 9 * 60,
    }
    _write_cache(cache)


def ensure_user(client: Client, phone: str, display_name: str) -> dict:
    """Registers the fixture account, or logs in when it already exists.

    Dedicated accounts keep the presence assertions deterministic: the demo
    accounts may have a live session from another client, and a user's second
    session suppresses their presence events by design.
    """
    response = client.post(
        "/api/v1/auth/register",
        body={"phone": phone, "password": PASSWORD, "displayName": display_name},
    )
    if response.status in (200, 201):
        data = response.json()
        _store_session(phone, data["accessToken"], data["user"]["id"])
        return data
    check(
        response.status in (409, 429),
        f"register {phone} expected 201/409, got {response}",
    )
    return login(client, phone)


def direct_conversation(client: Client, token: str, peer_phone: str) -> dict:
    """Idempotent: 201 on first creation, 200 when it already exists."""
    response = client.post(
        "/api/v1/conversations/direct", token=token, body={"phone": peer_phone}
    )
    check(
        response.status in (200, 201),
        f"create direct conversation expected 200/201, got {response}",
    )
    conversation = response.json()
    check(conversation.get("type") == "DIRECT", "conversation is not DIRECT")
    return response.status, conversation


def send_text(client: Client, token: str, conversation_id: str, content: str) -> dict:
    response = client.post(
        f"/api/v1/conversations/{conversation_id}/messages",
        token=token,
        body={
            "clientMessageId": str(uuid.uuid4()),
            "type": "TEXT",
            "content": content,
            "attachmentIds": [],
        },
    )
    check(
        response.status in (200, 201),
        f"send message expected 200/201, got {response}",
    )
    return response.json()


def history(client: Client, token: str, conversation_id: str, **params) -> dict:
    response = client.get(
        f"/api/v1/conversations/{conversation_id}/messages", token=token, params=params
    )
    check(response.status == 200, f"history expected 200, got {response}")
    return response.json()


def search(client: Client, token: str, conversation_id: str, q: str, **params) -> Response:
    return client.get(
        f"/api/v1/conversations/{conversation_id}/messages/search",
        token=token,
        params={"q": q, **params},
    )


class StepLog:
    """Prints one PASS line per assertion group and records failures."""

    def __init__(self, title: str):
        self.title = title
        self.started = time.time()
        self.passed = 0

    def ok(self, name: str, detail: str = "") -> None:
        self.passed += 1
        suffix = f" - {detail}" if detail else ""
        print(f"[PASS] {name}{suffix}")

    def finish(self) -> None:
        elapsed = time.time() - self.started
        print(f"\n{self.title}: {self.passed} checks passed in {elapsed:.1f}s")


def run(title: str, steps) -> int:
    log = StepLog(title)
    print(f"== {title} ==")
    try:
        steps(log)
    except E2EFailure as failure:
        print(f"[FAIL] {failure}")
        return 1
    log.finish()
    return 0


def main_guard(fn) -> None:
    try:
        sys.exit(fn())
    except KeyboardInterrupt:
        sys.exit(130)
