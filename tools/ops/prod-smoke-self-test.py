#!/usr/bin/env python3
"""Offline self-test for tools/ops/prod-smoke.sh.

Starts a stub NexaChat API on 127.0.0.1 and runs the real smoke script against
it. Four scenarios prove that the script detects both success and failure:

- healthy, read-only: every check passes and exactly one OTP request is sent;
- healthy, --write: register/login/avatar/delete/cleanup all pass;
- broken: the health probe fails cleanly and the run stops;
- degraded: health is UP but CORS, 401, OTP and /ws checks all fail clearly;
- write-leak: the script catches an avatar that survives account deletion.

No external network, no containers, no credentials.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent
SMOKE_SCRIPT = TOOLS_DIR / "prod-smoke.sh"
BASH_BIN: str | None = None
SITE_ORIGIN = "https://nexachat.io.vn"
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
PNG_BYTES = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)


class StubState:
    def __init__(self, mode: str) -> None:
        self.mode = mode
        self.users: dict[str, dict] = {}
        self.tokens: dict[str, str] = {}
        self.avatars: dict[str, bytes] = {}
        self.avatar_owner: dict[str, str] = {}
        self.next_id = 0
        self.otp_requests = 0
        self.lock = threading.Lock()

    def new_token(self, phone: str) -> str:
        self.next_id += 1
        token = f"stub-token-{self.next_id}"
        self.tokens[token] = phone
        return token

    def active_phone(self, authorization: str | None) -> str | None:
        if not authorization or not authorization.startswith("Bearer "):
            return None
        token = authorization[len("Bearer "):].strip()
        phone = self.tokens.get(token)
        if phone is None:
            return None
        user = self.users.get(phone)
        if user is None or user["deleted"]:
            return None
        return phone


def make_handler(state: StubState):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_args) -> None:  # keep the test output quiet
            return

        # -- helpers -------------------------------------------------------
        def _body(self) -> bytes:
            length = int(self.headers.get("Content-Length", "0") or "0")
            return self.rfile.read(length) if length else b""

        def _send(self, status: int, payload: bytes = b"", content_type: str | None = None,
                  headers: dict[str, str] | None = None) -> None:
            self.send_response(status)
            if content_type:
                self.send_header("Content-Type", content_type)
            for name, value in (headers or {}).items():
                self.send_header(name, value)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            if payload and self.command != "HEAD":
                self.wfile.write(payload)

        def _json(self, status: int, value: dict) -> None:
            self._send(status, json.dumps(value).encode("utf-8"), "application/json")

        def _error(self, status: int, code: str) -> None:
            self._json(status, {"error": {"code": code, "message": code}})

        # -- routing -------------------------------------------------------
        def do_OPTIONS(self) -> None:
            if state.mode in ("broken", "degraded"):
                self._send(403, b"Invalid CORS request", "text/plain")
                return
            if self.path not in (
                "/api/v1/account-deletion/request",
                "/api/v1/account-deletion/confirm",
            ):
                self._send(404, b"", "text/plain")
                return
            if self.headers.get("Origin") != SITE_ORIGIN:
                self._send(403, b"Invalid CORS request", "text/plain")
                return
            self._send(
                200,
                b"",
                headers={
                    "Access-Control-Allow-Origin": SITE_ORIGIN,
                    "Access-Control-Allow-Methods": "GET, POST, PATCH, PUT, DELETE, OPTIONS",
                    "Access-Control-Allow-Headers": "content-type",
                },
            )

        def do_GET(self) -> None:
            path = self.path.split("?", 1)[0]
            if state.mode == "broken":
                self._error(503, "UNAVAILABLE")
                return
            if path == "/actuator/health":
                self._json(200, {"status": "UP"})
                return
            if path == "/api/v1/users/me":
                if state.mode == "degraded":
                    self._json(200, {"id": "stub"})
                    return
                authorization = self.headers.get("Authorization")
                if not authorization:
                    self._error(401, "UNAUTHORIZED")
                    return
                phone = state.active_phone(authorization)
                if phone is None:
                    self._error(401, "TOKEN_INVALID")
                    return
                self._json(200, {"id": state.users[phone]["id"]})
                return
            if path.startswith("/api/v1/files/avatars/"):
                if state.mode == "degraded":
                    self._json(200, {"avatarUrl": path})
                    return
                authorization = self.headers.get("Authorization")
                if not authorization:
                    self._error(401, "UNAUTHORIZED")
                    return
                phone = state.active_phone(authorization)
                if phone is None:
                    self._error(401, "TOKEN_INVALID")
                    return
                data = state.avatars.get(path)
                if data is None:
                    self._error(404, "FILE_NOT_FOUND")
                    return
                self._send(200, data, "image/png", {"X-Content-Type-Options": "nosniff"})
                return
            if path == "/ws":
                if state.mode == "degraded":
                    self._error(404, "NOT_FOUND")
                    return
                key = self.headers.get("Sec-WebSocket-Key", "")
                accept = base64.b64encode(
                    hashlib.sha1((key + WS_GUID).encode("ascii")).digest()
                ).decode("ascii")
                self.send_response(101, "Switching Protocols")
                self.send_header("Upgrade", "websocket")
                self.send_header("Connection", "Upgrade")
                self.send_header("Sec-WebSocket-Accept", accept)
                self.send_header("Sec-WebSocket-Protocol", "v12.stomp")
                self.end_headers()
                # The smoke client closes the socket as soon as it has the 101;
                # do not wait for another request line on that connection.
                self.close_connection = True
                return
            self._error(404, "NOT_FOUND")

        def do_POST(self) -> None:
            path = self.path.split("?", 1)[0]
            body = self._body()
            if state.mode == "broken":
                self._error(503, "UNAVAILABLE")
                return
            if path == "/api/v1/auth/password-reset/request":
                if state.mode == "degraded":
                    self._error(500, "INTERNAL_ERROR")
                    return
                state.otp_requests += 1
                self._json(202, {})
                return
            if path == "/api/v1/auth/register":
                if state.mode == "degraded":
                    self._error(500, "INTERNAL_ERROR")
                    return
                try:
                    payload = json.loads(body.decode("utf-8"))
                except ValueError:
                    self._error(400, "VALIDATION_FAILED")
                    return
                phone = str(payload.get("phone", ""))
                with state.lock:
                    if phone in state.users:
                        self._error(409, "PHONE_EXISTS")
                        return
                    state.next_id += 1
                    user_id = f"stub-user-{state.next_id}"
                    state.users[phone] = {
                        "id": user_id,
                        "phone": phone,
                        "password": payload.get("password", ""),
                        "display_name": payload.get("displayName", ""),
                        "avatar_url": None,
                        "deleted": False,
                    }
                    token = state.new_token(phone)
                self._json(
                    201,
                    {
                        "user": {
                            "id": user_id,
                            "phone": phone,
                            "displayName": payload.get("displayName", ""),
                            "avatarUrl": None,
                        },
                        "accessToken": token,
                        "accessTokenExpiresIn": 900,
                        "refreshToken": "stub-refresh",
                        "recallWindowSeconds": 86400,
                        "groupAdminMayRecallAfterWindow": True,
                    },
                )
                return
            if path == "/api/v1/auth/login":
                if state.mode == "degraded":
                    self._error(500, "INTERNAL_ERROR")
                    return
                try:
                    payload = json.loads(body.decode("utf-8"))
                except ValueError:
                    self._error(400, "VALIDATION_FAILED")
                    return
                phone = str(payload.get("phone", ""))
                with state.lock:
                    user = state.users.get(phone)
                    if user is None or user["deleted"] or user["password"] != payload.get("password"):
                        self._error(401, "INVALID_CREDENTIALS")
                        return
                    token = state.new_token(phone)
                    user_id = user["id"]
                self._json(
                    200,
                    {
                        "user": {
                            "id": user_id,
                            "phone": phone,
                            "displayName": user["display_name"],
                            "avatarUrl": user["avatar_url"],
                        },
                        "accessToken": token,
                        "accessTokenExpiresIn": 900,
                        "refreshToken": "stub-refresh",
                        "recallWindowSeconds": 86400,
                        "groupAdminMayRecallAfterWindow": True,
                    },
                )
                return
            if path == "/api/v1/users/me/avatar":
                if state.mode == "degraded":
                    self._error(500, "INTERNAL_ERROR")
                    return
                phone = state.active_phone(self.headers.get("Authorization"))
                if phone is None:
                    self._error(401, "UNAUTHORIZED")
                    return
                with state.lock:
                    state.next_id += 1
                    url = f"/api/v1/files/avatars/stub-{state.next_id}.png"
                    state.avatars[url] = PNG_BYTES
                    state.avatar_owner[url] = phone
                    state.users[phone]["avatar_url"] = url
                self._json(200, {"avatarUrl": url})
                return
            self._error(404, "NOT_FOUND")

        def do_DELETE(self) -> None:
            path = self.path.split("?", 1)[0]
            body = self._body()
            if path != "/api/v1/users/me":
                self._error(404, "NOT_FOUND")
                return
            if state.mode == "degraded":
                self._error(500, "INTERNAL_ERROR")
                return
            phone = state.active_phone(self.headers.get("Authorization"))
            if phone is None:
                self._error(401, "TOKEN_INVALID")
                return
            try:
                payload = json.loads(body.decode("utf-8"))
            except ValueError:
                self._error(400, "VALIDATION_FAILED")
                return
            with state.lock:
                user = state.users[phone]
                if user["password"] != payload.get("password"):
                    self._error(401, "WRONG_PASSWORD")
                    return
                user["deleted"] = True
                for token in [t for t, owner in state.tokens.items() if owner == phone]:
                    del state.tokens[token]
                if state.mode != "write-leak":
                    for url in [u for u, owner in state.avatar_owner.items() if owner == phone]:
                        del state.avatars[url]
                        del state.avatar_owner[url]
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()

    return Handler


def find_bash() -> str | None:
    """Pick a bash that shares the host network (Git Bash on Windows, never WSL)."""
    override = os.environ.get("PROD_SMOKE_BASH")
    candidates: list[str | None] = [override]
    if os.name == "nt":
        program_files = os.environ.get("ProgramFiles", r"C:\Program Files")
        candidates.extend(
            [
                str(Path(program_files) / "Git" / "bin" / "bash.exe"),
                str(Path(program_files) / "Git" / "usr" / "bin" / "bash.exe"),
            ]
        )
    candidates.append(shutil.which("bash"))
    for candidate in candidates:
        if not candidate:
            continue
        path = Path(candidate)
        if not path.is_file():
            continue
        # C:\Windows\System32\bash.exe is the WSL launcher: its 127.0.0.1 is
        # a different network namespace, so the stub server would be unreachable.
        if "system32" in str(path).lower():
            continue
        return str(path)
    return None


class StubServer(ThreadingHTTPServer):
    """Threaded stub server that ignores a client closing a WS probe socket."""

    daemon_threads = True

    def handle_error(self, request, client_address) -> None:
        error = sys.exc_info()[1]
        if isinstance(error, (ConnectionResetError, BrokenPipeError, ConnectionAbortedError)):
            return
        super().handle_error(request, client_address)


def run_case(name: str, mode: str, extra_args: list[str], expect_success: bool,
             markers: list[str], timeout: int = 120) -> bool:
    state = StubState(mode)
    server = StubServer(("127.0.0.1", 0), make_handler(state))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    port = server.server_address[1]
    base_url = f"http://127.0.0.1:{port}"
    # Run from tools/ with a relative script name: Git Bash on Windows cannot
    # open a native D:\... path passed by Python.
    command = [str(BASH_BIN), SMOKE_SCRIPT.name, *extra_args, base_url]
    proc = None
    output = ""
    try:
        proc = subprocess.run(
            command, cwd=str(TOOLS_DIR), capture_output=True, text=True, timeout=timeout
        )
        output = (proc.stdout or "") + (proc.stderr or "")
    except subprocess.TimeoutExpired as expired:
        output = (expired.stdout or "") + (expired.stderr or "")
        if isinstance(output, bytes):
            output = output.decode("utf-8", "replace")
    finally:
        server.shutdown()
        server.server_close()
    ok = proc is not None and (proc.returncode == 0) == expect_success
    details: list[str] = []
    if proc is None:
        details.append(f"timed out after {timeout}s")
    elif not ok:
        details.append(f"exit code {proc.returncode}, expected success={expect_success}")
    for marker in markers:
        if marker not in output:
            details.append(f"missing output: {marker!r}")
    if mode == "healthy" and state.otp_requests != 1:
        details.append(f"expected exactly one OTP request, got {state.otp_requests}")
    if ok and not details:
        print(f"PASS  self-test: {name}")
        return True
    print(f"FAIL  self-test: {name}")
    for detail in details:
        print(f"      {detail}")
    print("      --- smoke output ---")
    for line in output.splitlines():
        print(f"      {line}")
    return False


def verify_websocket_probe_uses_http_scheme() -> bool:
    """curl 8.11+ treats ws(s):// as native WebSocket; manual Upgrade must use http(s)."""
    text = SMOKE_SCRIPT.read_text(encoding="utf-8")
    match = re.search(
        r"check_websocket_handshake\(\) \{(.*?)^\}",
        text,
        flags=re.DOTALL | re.MULTILINE,
    )
    if match is None:
        print("FAIL  self-test: check_websocket_handshake not found in prod-smoke.sh")
        return False
    block = match.group(1)
    if '"$probe_url"' not in block:
        print(
            "FAIL  self-test: WebSocket upgrade probe must curl the http(s) /ws URL "
            "(probe_url), not ws(s)://"
        )
        return False
    curl_block = block[block.find("curl") :]
    if re.search(r'"\$ws_url"\s*2>"\$CURL_ERR"', curl_block):
        print(
            "FAIL  self-test: WebSocket curl target must not be ws(s):// "
            "(incompatible with curl 8.11+ manual handshake headers)"
        )
        return False
    return True


def main() -> int:
    global BASH_BIN
    if not SMOKE_SCRIPT.is_file():
        print(f"FAIL  self-test: {SMOKE_SCRIPT} not found")
        return 1
    BASH_BIN = find_bash()
    if BASH_BIN is None:
        print("FAIL  self-test: a host bash (Git Bash on Windows, not WSL) is required")
        return 1
    if not verify_websocket_probe_uses_http_scheme():
        return 1

    failures = 0
    cases = [
        (
            "healthy read-only run passes",
            "healthy",
            ["--wait", "5"],
            True,
            [
                "PASS  health: UP",
                "PASS  CORS /api/v1/account-deletion/request: preflight allows",
                "PASS  CORS /api/v1/account-deletion/confirm: preflight allows",
                "PASS  unauthenticated GET /api/v1/users/me: 401 UNAUTHORIZED",
                "PASS  OTP request: 202 {}",
                "PASS  WebSocket: /ws upgraded",
                "PASS  prod smoke: all checks passed",
            ],
        ),
        (
            "healthy --write run proves account and avatar cleanup",
            "healthy",
            ["--write", "--wait", "5"],
            True,
            [
                "PASS  write: registered disposable subject account",
                "PASS  write: avatar fetched back byte-for-byte",
                "PASS  write: deleted disposable subject account",
                "PASS  write: avatar bytes removed after deletion",
                "PASS  write: deleted disposable observer account",
                "PASS  write: account, avatar and session cleanup verified",
            ],
        ),
        (
            "sleeping server is reported as a health failure",
            "broken",
            ["--wait", "1"],
            False,
            ["FAIL  health: not UP within 1s"],
        ),
        (
            "degraded server fails every read-only check",
            "degraded",
            ["--wait", "1"],
            False,
            [
                "FAIL  CORS /api/v1/account-deletion/request: preflight returned HTTP 403",
                "FAIL  CORS /api/v1/account-deletion/confirm: preflight returned HTTP 403",
                "FAIL  unauthenticated GET /api/v1/users/me: expected HTTP 401, got 200",
                "FAIL  OTP request: expected HTTP 202",
                "FAIL  WebSocket: expected HTTP 101",
            ],
        ),
        (
            "avatar that survives deletion is detected",
            "write-leak",
            ["--write", "--wait", "5"],
            False,
            ["FAIL  write: avatar file still served after account deletion"],
        ),
    ]
    for name, mode, extra_args, expect_success, markers in cases:
        if not run_case(name, mode, extra_args, expect_success, markers):
            failures += 1

    if failures:
        print(f"FAIL  self-test: {failures} scenario(s) failed")
        return 1
    print("PASS  self-test: all prod-smoke scenarios passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
