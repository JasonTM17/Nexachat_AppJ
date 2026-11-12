"""Local Mailpit helper for E2E OTP journeys (password reset, account deletion).

Standard library only. Starts a named Mailpit container for SMTP capture and
polls its HTTP API for bilingual OTP messages. Docker invocations go through
``run_docker`` so unit tests can inject a fake runner.
"""

from __future__ import annotations

import json
import re
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Callable

CONTAINER_NAME = "nexachat-e2e-mailpit"
MAILPIT_IMAGE = "axllent/mailpit"
SMTP_CONTAINER_PORT = 1025
HTTP_CONTAINER_PORT = 8025
DEFAULT_MAIL_FROM = "noreply@nexachat.io.vn"
_POLL_INTERVAL_SEC = 0.25

_OTP_PATTERNS = (
    re.compile(r"NexaChat security code:\s*(\d{6})", re.IGNORECASE),
    re.compile(r"Mã bảo mật NexaChat:\s*(\d{6})", re.IGNORECASE),
    re.compile(r"<strong>(\d{6})</strong>", re.IGNORECASE),
)

# Injectable in tests; when set, all docker argv lists are passed here instead of subprocess.
_docker_runner: Callable[..., subprocess.CompletedProcess] | None = None

# When set, API calls use this base URL instead of persisted container state.
_override_http_base: str | None = None


class MailSinkError(RuntimeError):
    """Mail sink operation failed."""


def run_docker(argv: list[str], **kwargs: Any) -> subprocess.CompletedProcess:
    if _docker_runner is not None:
        return _docker_runner(argv, **kwargs)
    return subprocess.run(argv, capture_output=True, text=True, check=False, **kwargs)


def _state_path() -> Path:
    return Path(tempfile.gettempdir()) / "nexachat-e2e-mailpit.json"


def _load_state() -> dict[str, Any] | None:
    path = _state_path()
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise MailSinkError(f"could not read mail sink state at {path}: {error}") from error
    if not isinstance(data, dict):
        raise MailSinkError(f"invalid mail sink state at {path}")
    return data


def _save_state(smtp_port: int, http_port: int) -> None:
    payload = {
        "smtp_port": smtp_port,
        "http_port": http_port,
        "http_base": f"http://127.0.0.1:{http_port}",
        "smtp_host": "127.0.0.1",
    }
    _state_path().write_text(json.dumps(payload, indent=2), encoding="utf-8")


def _clear_state_file() -> None:
    path = _state_path()
    if path.is_file():
        path.unlink()


def _container_running() -> bool:
    result = run_docker(
        ["docker", "inspect", "-f", "{{.State.Running}}", CONTAINER_NAME],
    )
    return result.returncode == 0 and result.stdout.strip() == "true"


def _container_exists() -> bool:
    result = run_docker(["docker", "inspect", CONTAINER_NAME])
    return result.returncode == 0


def _parse_host_port(docker_port_output: str) -> int:
    line = docker_port_output.strip().splitlines()[0].strip()
    # 127.0.0.1:49152 or 0.0.0.0:49152
    if ":" not in line:
        raise MailSinkError(f"unexpected docker port output: {line!r}")
    return int(line.rsplit(":", 1)[-1])


def _read_mapped_ports() -> tuple[int, int]:
    smtp = run_docker(["docker", "port", CONTAINER_NAME, f"{SMTP_CONTAINER_PORT}/tcp"])
    http = run_docker(["docker", "port", CONTAINER_NAME, f"{HTTP_CONTAINER_PORT}/tcp"])
    if smtp.returncode != 0 or http.returncode != 0:
        raise MailSinkError("could not read Mailpit port mappings from docker")
    return _parse_host_port(smtp.stdout), _parse_host_port(http.stdout)


def _wait_for_smtp_port(host: str, port: int, timeout: float = 30.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with socket.create_connection((host, port), timeout=1.0):
                return
        except OSError:
            time.sleep(0.2)
    raise MailSinkError(f"SMTP port {host}:{port} did not accept connections within {timeout}s")


def start() -> dict[str, Any]:
    """Start or reuse the named Mailpit container. Returns host port mapping info."""
    if _container_running():
        smtp_port, http_port = _read_mapped_ports()
        _save_state(smtp_port, http_port)
        return {
            "status": "reused",
            "smtp_host": "127.0.0.1",
            "smtp_port": smtp_port,
            "http_port": http_port,
            "http_base": f"http://127.0.0.1:{http_port}",
        }

    if _container_exists():
        result = run_docker(["docker", "start", CONTAINER_NAME])
        if result.returncode != 0:
            raise MailSinkError(
                f"docker start {CONTAINER_NAME} failed: {result.stderr.strip() or result.stdout.strip()}"
            )
        smtp_port, http_port = _read_mapped_ports()
        _save_state(smtp_port, http_port)
        _wait_for_smtp_port("127.0.0.1", smtp_port)
        return {
            "status": "started_existing",
            "smtp_host": "127.0.0.1",
            "smtp_port": smtp_port,
            "http_port": http_port,
            "http_base": f"http://127.0.0.1:{http_port}",
        }

    result = run_docker(
        [
            "docker",
            "run",
            "-d",
            "--name",
            CONTAINER_NAME,
            "-p",
            f"127.0.0.1::{SMTP_CONTAINER_PORT}",
            "-p",
            f"127.0.0.1::{HTTP_CONTAINER_PORT}",
            MAILPIT_IMAGE,
        ],
    )
    if result.returncode != 0:
        raise MailSinkError(
            f"docker run {CONTAINER_NAME} failed: {result.stderr.strip() or result.stdout.strip()}"
        )
    smtp_port, http_port = _read_mapped_ports()
    _save_state(smtp_port, http_port)
    _wait_for_smtp_port("127.0.0.1", smtp_port)
    return {
        "status": "created",
        "smtp_host": "127.0.0.1",
        "smtp_port": smtp_port,
        "http_port": http_port,
        "http_base": f"http://127.0.0.1:{http_port}",
    }


def stop() -> None:
    """Remove only the named Mailpit container."""
    run_docker(["docker", "rm", "-f", CONTAINER_NAME])
    _clear_state_file()


def http_base() -> str:
    if _override_http_base:
        return _override_http_base.rstrip("/")
    state = _load_state()
    if state and isinstance(state.get("http_base"), str):
        return state["http_base"].rstrip("/")
    raise MailSinkError("mail sink is not started; run start() or mail_sink.py start first")


def server_env() -> dict[str, str]:
    """Environment variables for a local NexaChat server using this Mailpit instance."""
    state = _load_state()
    if not state:
        raise MailSinkError("mail sink is not started; run start() first")
    smtp_host = str(state.get("smtp_host", "127.0.0.1"))
    smtp_port = int(state["smtp_port"])
    return {
        "NEXACHAT_MAIL_PROVIDER": "smtp",
        "MAIL_SMTP_HOST": smtp_host,
        "MAIL_SMTP_PORT": str(smtp_port),
        "MAIL_SMTP_USERNAME": "",
        "MAIL_SMTP_PASSWORD": "",
        "MAIL_FROM": DEFAULT_MAIL_FROM,
    }


def _http_json(method: str, url: str, timeout: float = 10.0) -> Any:
    request = urllib.request.Request(url, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read()
    except urllib.error.HTTPError as error:
        body = error.read()
        raise MailSinkError(f"{method} {url} failed with HTTP {error.code}: {body[:200]!r}") from error
    except urllib.error.URLError as error:
        raise MailSinkError(f"{method} {url} failed: {error}") from error
    if not body:
        return None
    return json.loads(body.decode("utf-8"))


def _recipient_addresses(entry: dict[str, Any]) -> list[str]:
    addresses: list[str] = []
    for key in ("To", "Cc", "Bcc"):
        raw = entry.get(key) or []
        if not isinstance(raw, list):
            continue
        for item in raw:
            if isinstance(item, dict):
                addr = item.get("Address") or item.get("address")
                if addr:
                    addresses.append(str(addr).lower())
    return addresses


def _message_matches(entry: dict[str, Any], to: str, subject_contains: str | None) -> bool:
    target = to.strip().lower()
    if target not in _recipient_addresses(entry):
        return False
    if subject_contains:
        subject = str(entry.get("Subject") or "")
        if subject_contains.lower() not in subject.lower():
            return False
    return True


def _list_messages() -> list[dict[str, Any]]:
    base = http_base()
    payload = _http_json("GET", f"{base}/api/v1/messages?limit=200")
    if not isinstance(payload, dict):
        return []
    messages = payload.get("messages")
    if not isinstance(messages, list):
        return []
    return [m for m in messages if isinstance(m, dict)]


def _fetch_message_body(message_id: str) -> dict[str, Any]:
    base = http_base()
    payload = _http_json("GET", f"{base}/api/v1/message/{urllib.parse.quote(message_id, safe='')}")
    if not isinstance(payload, dict):
        raise MailSinkError(f"unexpected Mailpit message payload for {message_id}")
    return payload


def wait_for_message(
    to: str,
    subject_contains: str | None = None,
    timeout: float = 60.0,
) -> dict[str, Any]:
    """Poll Mailpit until a message for ``to`` arrives. Returns the full message JSON."""
    deadline = time.monotonic() + timeout
    last_error: str | None = None
    while time.monotonic() < deadline:
        try:
            candidates = [
                m
                for m in _list_messages()
                if _message_matches(m, to, subject_contains)
            ]
        except MailSinkError as error:
            last_error = str(error)
            time.sleep(_POLL_INTERVAL_SEC)
            continue
        if candidates:
            candidates.sort(key=lambda m: str(m.get("Created") or ""))
            summary = candidates[-1]
            message_id = summary.get("ID") or summary.get("Id") or summary.get("id")
            if not message_id:
                raise MailSinkError("Mailpit list entry missing message ID")
            return _fetch_message_body(str(message_id))
        time.sleep(_POLL_INTERVAL_SEC)
    hint = f" ({last_error})" if last_error else ""
    raise MailSinkError(
        f"timed out after {timeout}s waiting for mail to {to!r}"
        + (f" with subject containing {subject_contains!r}" if subject_contains else "")
        + hint
    )


def extract_otp(message: dict[str, Any] | str) -> str:
    """Extract the 6-digit OTP from a NexaChat bilingual mail body."""
    if isinstance(message, str):
        blob = message
    else:
        parts = [
            str(message.get("Text") or ""),
            str(message.get("HTML") or message.get("Html") or ""),
            str(message.get("Snippet") or ""),
        ]
        blob = "\n".join(parts)
    found: list[str] = []
    for pattern in _OTP_PATTERNS:
        for match in pattern.findall(blob):
            found.append(match)
    if not found:
        raise MailSinkError("no 6-digit NexaChat OTP found in message body")
    unique = sorted(set(found))
    if len(unique) != 1:
        raise MailSinkError(f"ambiguous OTP codes in message body: {unique}")
    return unique[0]


def clear() -> None:
    """Delete all messages in the Mailpit inbox."""
    base = http_base()
    _http_json("DELETE", f"{base}/api/v1/messages")


def _print_env() -> None:
    for key, value in server_env().items():
        print(f"{key}={value}")


def main(argv: list[str] | None = None) -> int:
    args = argv if argv is not None else sys.argv[1:]
    if not args or args[0] in ("-h", "--help"):
        print(
            "Usage: python tools/e2e/mail_sink.py start|stop|env|otp <email> [--timeout SEC]",
            file=sys.stderr,
        )
        return 0 if args and args[0] in ("-h", "--help") else 1

    cmd = args[0]
    try:
        if cmd == "start":
            info = start()
            print(json.dumps(info, indent=2))
            return 0
        if cmd == "stop":
            stop()
            print(f"removed container {CONTAINER_NAME}")
            return 0
        if cmd == "env":
            _print_env()
            return 0
        if cmd == "otp":
            if len(args) < 2:
                print("otp requires an email address", file=sys.stderr)
                return 1
            timeout = 60.0
            rest = args[2:]
            if rest and rest[0] == "--timeout" and len(rest) >= 2:
                timeout = float(rest[1])
            message = wait_for_message(args[1], timeout=timeout)
            print(extract_otp(message))
            return 0
        print(f"unknown command: {cmd}", file=sys.stderr)
        return 1
    except MailSinkError as error:
        print(f"[FAIL] {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
