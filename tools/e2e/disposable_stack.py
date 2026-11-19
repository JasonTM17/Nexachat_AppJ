#!/usr/bin/env python3
"""Disposable backend provisioner for NexaChat device E2E journeys.

Provisions a fresh, isolated backend stack for one device E2E run on top of
the shared local Postgres container:

- a throwaway Postgres database (caller-supplied name or random suffix),
- a free TCP port from the 18000-18999 scan range,
- generated JWT_SECRET (hex) and NEXACHAT_REFRESH_REPLAY_KEY (canonical
  Base64 of 32 bytes - the exact formats the server validates),
- env.sh + uploads dir under a caller-chosen run directory,
- start/stop of `java -jar` (newest server/target jar) with a PID file and
  /actuator/health/readiness polling,
- user seeding over the /api/v1/auth register/login contract, and
- dropdb that refuses any database not recorded in the run dir's state file.

Standard library only. Database access runs through `docker exec <container> psql`.

Safety rules:
- Refuses any database name that does not contain 'nexachat'.
- Never adopts or drops the shared development database 'nexachat'.
- dropdb drops only the database recorded in the run dir's state file.
- Refuses any target host that is not local (e.g. localhost, 127.0.0.1, ::1).
- stop() terminates only the PID recorded in its own PID file.

Usage:
    python tools/e2e/disposable_stack.py provision <run_dir> [--db-name N] [--port P]
    python tools/e2e/disposable_stack.py start <run_dir> [--jar J] [--readiness-timeout S]
    python tools/e2e/disposable_stack.py stop <run_dir> [--grace S]
    python tools/e2e/disposable_stack.py status <run_dir> [--json]
    python tools/e2e/disposable_stack.py seed <run_dir> --user "+84900000001=An" [--user ...]
    python tools/e2e/disposable_stack.py dropdb <run_dir>
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import secrets
import signal
import socket
import subprocess
import sys
import time
from typing import Any, Callable, Dict, Iterable, List, Optional, Sequence, Tuple

# --- Shared-stack defaults (server/src/main/resources/application.yml,
# --- docker-compose.yml, .env.example).
DEFAULT_CONTAINER = "nexachat-db-1"
DEFAULT_DB_HOST = "localhost"
DEFAULT_DB_PORT = 5433
DEFAULT_DB_USER = "nexachat"
DEFAULT_DB_PASSWORD = "nexachat"
SHARED_DB_NAME = "nexachat"  # docker-compose POSTGRES_DB; never adopt or drop
MAINTENANCE_DB = "postgres"  # psql connect target for CREATE/DROP DATABASE
DEFAULT_DB_NAME_PREFIX = "nexachat_e2e_"
RANDOM_DB_NAME_ATTEMPTS = 5

DEFAULT_PORT_RANGE = (18000, 18999)  # device-E2E backend scan range (18xxx)
SERVER_BIND_HOST = "127.0.0.1"  # loopback bind; adb reverse maps device localhost here

DEFAULT_READINESS_TIMEOUT = 90.0
DEFAULT_READINESS_POLL_INTERVAL = 0.5
DEFAULT_STOP_GRACE_SECONDS = 10.0
DEFAULT_PSQL_TIMEOUT = 30.0
HEALTH_PROBE_TIMEOUT = 2.0

READINESS_PATH = "/actuator/health/readiness"

# --- Seeding contract (mirrors tools/e2e/e2e_common.py ensure_user/login).
REGISTER_PATH = "/api/v1/auth/register"
LOGIN_PATH = "/api/v1/auth/login"
SEED_PASSWORD = "password123"
REGISTER_ATTEMPTS = 4
REGISTER_RETRY_WAIT_CAP = 120.0
LOGIN_ATTEMPTS = 3
LOGIN_RETRY_WAIT_CAP = 60.0

# --- Run-dir layout.
STATE_FILE_NAME = "stack_state.json"
ENV_FILE_NAME = "env.sh"
PID_FILE_NAME = "backend.pid"
LOG_FILE_NAME = "backend.log"
UPLOADS_DIR_NAME = "uploads"
STATE_VERSION = 1
STATE_OWNER = "disposable_stack"

# Offline-friendly device-journey launch defaults (working reference:
# run/w10-offline/backend/run.bat). Local STUN keeps call setup from hanging
# on external servers in offline journeys; journeys can override per stack.
DEFAULT_EXTRA_JAVA_ARGS = ("--nexachat.calls.ice-servers=stun:127.0.0.1:3478",)

_E2E_DIR = os.path.dirname(os.path.abspath(__file__))
_REPO_ROOT = os.path.dirname(os.path.dirname(_E2E_DIR))
DEFAULT_SERVER_TARGET = os.path.join(_REPO_ROOT, "server", "target")

_IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9_.-]+$")
_DB_NAME_RE = re.compile(r"^[a-z_][a-z0-9_]*$")
_ENV_KEY_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
_LOCAL_HOSTS = frozenset({"localhost", "127.0.0.1", "::1", "local", "docker"})
_SHELL_SAFE_VALUE_RE = re.compile(r"^[A-Za-z0-9_./:=+@,-]*$")
_PHONE_RE = re.compile(r"^\+?[0-9]{6,20}$")


class DisposableStackError(Exception):
    """Base error for disposable stack operations."""


class TargetRefusalError(DisposableStackError, ValueError):
    """Raised when a database/host/identifier fails safety verification."""


class StateError(DisposableStackError, RuntimeError):
    """Raised when the run dir's state file is missing or unusable."""


class NotOwnedError(DisposableStackError, ValueError):
    """Raised when dropdb is asked about a database it did not create."""


class PsqlExecutionError(DisposableStackError, RuntimeError):
    """Raised when psql execution fails."""


class ProvisionError(DisposableStackError, RuntimeError):
    """Raised when provisioning a fresh stack fails."""


class PortUnavailableError(DisposableStackError, RuntimeError):
    """Raised when no free TCP port is available in the scan range."""


class ProcessError(DisposableStackError, RuntimeError):
    """Raised when the backend process cannot be started or stopped."""


class ReadinessTimeoutError(DisposableStackError, TimeoutError):
    """Raised when the backend never reports readiness in time."""


class HealthProbeError(DisposableStackError, RuntimeError):
    """Raised when the readiness endpoint is unreachable."""


class SeedError(DisposableStackError, RuntimeError):
    """Raised when user seeding fails the register/login contract."""


# --- Validation helpers (reminder_clock.py semantics). ---


def is_local_host(host: str) -> bool:
    """Checks whether the host represents a local machine or container."""
    if not isinstance(host, str):
        return False
    normalized = host.strip().lower()
    if normalized in _LOCAL_HOSTS:
        return True
    if normalized.startswith("127."):
        return True
    return False


def validate_target(db_name: str, host: str) -> None:
    """Refuses unless the DB name contains 'nexachat' and host is local."""
    if not isinstance(db_name, str) or "nexachat" not in db_name.lower():
        raise TargetRefusalError(
            f"Refusing database name {db_name!r}; name must contain 'nexachat'"
        )
    if not is_local_host(host):
        raise TargetRefusalError(
            f"Refusing database host {host!r}; target host must be a local host"
        )


def validate_safe_identifier(label: str, value: str) -> str:
    """Ensures container and user names do not contain shell metacharacters."""
    if not isinstance(value, str) or not _IDENTIFIER_RE.fullmatch(value):
        raise ValueError(f"Invalid safe identifier for {label}: {value!r}")
    return value


def validate_db_name(db_name: str) -> str:
    """Ensures a lowercase Postgres identifier that contains 'nexachat'."""
    if not isinstance(db_name, str) or not _DB_NAME_RE.fullmatch(db_name):
        raise TargetRefusalError(
            f"Refusing database name {db_name!r}; must match {_DB_NAME_RE.pattern}"
        )
    if "nexachat" not in db_name:
        raise TargetRefusalError(
            f"Refusing database name {db_name!r}; name must contain 'nexachat'"
        )
    return db_name


# --- Secret and name generation. ---


def generate_jwt_secret() -> str:
    """128 hex chars; comfortably above the 64-char minimum in application.yml."""
    return secrets.token_hex(64)


def generate_refresh_replay_key() -> str:
    """Canonical Base64 of 32 random bytes (RefreshReplayCipher.KEY_BYTES)."""
    raw = secrets.token_bytes(32)
    return base64.b64encode(raw).decode("ascii")


def generate_db_name(prefix: str = DEFAULT_DB_NAME_PREFIX) -> str:
    """Random disposable database name: prefix + 8 hex chars."""
    return f"{prefix}{secrets.token_hex(4)}"


# --- URL builders. ---


def jdbc_url_for(db_name: str, *, host: str = DEFAULT_DB_HOST, port: int = DEFAULT_DB_PORT) -> str:
    return f"jdbc:postgresql://{host}:{port}/{db_name}"


def base_url_for(app_port: int, *, host: str = SERVER_BIND_HOST) -> str:
    return f"http://{host}:{int(app_port)}"


def readiness_url_for(app_port: int, *, host: str = SERVER_BIND_HOST) -> str:
    return base_url_for(app_port, host=host) + READINESS_PATH


# --- Run-dir paths and state file. ---


def state_file_path(run_dir: str) -> str:
    return os.path.join(run_dir, STATE_FILE_NAME)


def env_file_path(run_dir: str) -> str:
    return os.path.join(run_dir, ENV_FILE_NAME)


def pid_file_path(run_dir: str) -> str:
    return os.path.join(run_dir, PID_FILE_NAME)


def log_file_path(run_dir: str) -> str:
    return os.path.join(run_dir, LOG_FILE_NAME)


def uploads_dir_path(run_dir: str) -> str:
    return os.path.join(run_dir, UPLOADS_DIR_NAME)


def utc_now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def save_state(run_dir: str, state: Dict[str, Any]) -> None:
    """Atomically writes the state file so dropdb always sees a full record."""
    path = state_file_path(run_dir)
    tmp_path = path + ".tmp"
    with open(tmp_path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(state, handle, indent=2, sort_keys=True)
        handle.write("\n")
    os.replace(tmp_path, path)


def load_state(run_dir: str) -> Optional[Dict[str, Any]]:
    """Loads the state file; None when absent, StateError when corrupt."""
    path = state_file_path(run_dir)
    if not os.path.isfile(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, ValueError) as err:
        raise StateError(f"{path} is not a readable stack state: {err}") from err
    if not isinstance(data, dict):
        raise StateError(f"{path} must contain a JSON object, got {type(data).__name__}")
    return data


def read_pid_file(run_dir: str) -> Optional[int]:
    """Returns the recorded backend PID, or None when absent/stale."""
    try:
        with open(pid_file_path(run_dir), "r", encoding="ascii") as handle:
            text = handle.read().strip()
    except OSError:
        return None
    if not text:
        return None
    try:
        return int(text)
    except ValueError:
        return None


def write_pid_file(run_dir: str, pid: int) -> None:
    with open(pid_file_path(run_dir), "w", encoding="ascii") as handle:
        handle.write(f"{int(pid)}\n")


def remove_pid_file(run_dir: str) -> None:
    try:
        os.remove(pid_file_path(run_dir))
    except OSError:
        pass


# --- env.sh rendering. ---


def to_posix_path(path: str) -> str:
    """Forward-slash form so the same env.sh works under bash on Windows."""
    return str(path).replace("\\", "/")


def shell_quote(value: Any) -> str:
    """Quotes a value for `export K=V` lines; bare when unambiguous."""
    text = str(value)
    if _SHELL_SAFE_VALUE_RE.fullmatch(text):
        return text
    return "'" + text.replace("'", "'\\''") + "'"


def build_env(state: Dict[str, Any]) -> Dict[str, str]:
    """Builds the backend environment from the state record.

    Order matters: these keys render into env.sh in exactly this order,
    mirroring the working reference stack at run/w10-offline/backend/env.sh.
    """
    env: Dict[str, str] = {
        "DATABASE_URL": state["jdbc_url"],
        "DATABASE_USER": state["db_user"],
        "DATABASE_PASSWORD": state["db_password"],
        "SERVER_PORT": str(state["app_port"]),
        "JWT_SECRET": state["jwt_secret"],
        "NEXACHAT_REFRESH_REPLAY_KEY": state["refresh_replay_key"],
        "STORAGE_ROOT": to_posix_path(state["uploads_dir"]),
        "NEXACHAT_STORAGE_PROVIDER": "local",
        "NEXACHAT_ADMIN_PHONES": str(state.get("admin_phones", "")),
        "NEXACHAT_E2EE_V1_ACTIVATION_ENABLED": (
            "true" if state.get("e2ee_activation_enabled", True) else "false"
        ),
        "NEXACHAT_GOOGLE_SIGNIN_ENABLED": "false",
        "NEXACHAT_GOOGLE_SIGNUP_ENABLED": "false",
        "FCM_ENABLED": "false",
        "FIREBASE_REALTIME_ENABLED": "false",
        "FIREBASE_CALL_STATUS_ENABLED": "false",
        "NEXACHAT_SUMMARY_LLM_ENABLED": "false",
        "NEXACHAT_SUMMARY_LLM_FALLBACK_ENABLED": "false",
        "NEXACHAT_SUMMARY_LOCAL_LLM_ENABLED": "false",
        "NEXACHAT_RAG_EMBEDDING_PROVIDER": "lexical",
        "NEXACHAT_MAIL_PROVIDER": "",
        "NEXACHAT_RETENTION_ENABLED": "false",
        "NEXACHAT_TRUSTED_PROXY_CIDRS": "",
    }
    extra = state.get("extra_env") or {}
    for key in sorted(extra):
        env[str(key)] = str(extra[key])
    return env


def render_env_sh(state: Dict[str, Any]) -> str:
    """Renders the sourceable env.sh content for this stack."""
    header = (
        f"# Disposable NexaChat device-E2E backend (port {state['app_port']}) "
        f"- db {state['db_name']}. Generated by tools/e2e/disposable_stack.py; "
        "NOT for repo/commit."
    )
    lines = [header]
    for key, value in build_env(state).items():
        lines.append(f"export {key}={shell_quote(value)}")
    return "\n".join(lines) + "\n"


def write_env_file(state: Dict[str, Any]) -> None:
    path = state.get("env_file") or env_file_path(state["run_dir"])
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(render_env_sh(state))


# --- Port picking (18xxx scan). ---


def socket_bind_probe(host: str, port: int) -> bool:
    """True when host:port can be bound right now."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        try:
            sock.bind((host, port))
        except OSError:
            return False
    return True


def find_free_port(
    port_range: Tuple[int, int] = DEFAULT_PORT_RANGE,
    host: str = SERVER_BIND_HOST,
    probe: Optional[Callable[[str, int], bool]] = None,
) -> int:
    """Scans the range for a bindable port; injectable probe for tests."""
    start, end = port_range
    if not isinstance(start, int) or not isinstance(end, int) or end < start:
        raise ValueError(f"invalid port range {port_range!r}")
    probe = probe or socket_bind_probe
    for port in range(start, end + 1):
        if probe(host, port):
            return port
    raise PortUnavailableError(f"no free TCP port in {start}-{end} on {host}")


# --- Jar discovery. ---


def find_newest_jar(server_target_dir: str) -> str:
    """Newest server/target/nexachat-server-*.jar (skips sources/javadoc)."""
    directory = os.path.abspath(server_target_dir)
    try:
        names = os.listdir(directory)
    except OSError as err:
        raise ProvisionError(f"server target directory not found: {directory}") from err
    candidates = []
    for name in names:
        if not name.startswith("nexachat-server-") or not name.endswith(".jar"):
            continue
        if name.endswith(("-sources.jar", "-javadoc.jar", ".jar.original")):
            continue
        path = os.path.join(directory, name)
        if os.path.isfile(path):
            candidates.append(path)
    if not candidates:
        raise ProvisionError(f"no nexachat-server-*.jar under {directory}")
    return max(candidates, key=lambda path: (os.path.getmtime(path), path))


# --- Database access via docker exec psql. ---


def execute_psql_docker(
    sql: str,
    *,
    db_name: str,
    container: str = DEFAULT_CONTAINER,
    user: str = DEFAULT_DB_USER,
    host: str = DEFAULT_DB_HOST,
    timeout: float = DEFAULT_PSQL_TIMEOUT,
) -> str:
    """Runs SQL through `docker exec <container> psql` on a local target.

    db_name must be a validated 'nexachat' database or the maintenance
    database (CREATE/DROP DATABASE cannot run from inside their own target).
    """
    if db_name != MAINTENANCE_DB:
        if not isinstance(db_name, str) or not _DB_NAME_RE.fullmatch(db_name):
            raise TargetRefusalError(
                f"Refusing database name {db_name!r}; must match {_DB_NAME_RE.pattern}"
            )
        if "nexachat" not in db_name.lower():
            raise TargetRefusalError(
                f"Refusing database name {db_name!r}; name must contain 'nexachat'"
            )
    if not is_local_host(host):
        raise TargetRefusalError(
            f"Refusing database host {host!r}; target host must be a local host"
        )
    validate_safe_identifier("container", container)
    validate_safe_identifier("user", user)

    command = [
        "docker", "exec", "-i", container,
        "psql", "-U", user, "-d", db_name,
        "-v", "ON_ERROR_STOP=1", "-tA", "-F", "|",
    ]
    try:
        completed = subprocess.run(
            command,
            input=sql.encode("utf-8"),
            capture_output=True,
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise PsqlExecutionError(f"psql execution timed out after {timeout}s") from exc
    except OSError as exc:
        raise PsqlExecutionError(f"Failed to spawn docker process: {exc}") from exc

    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", "replace").strip()
        raise PsqlExecutionError(
            f"psql exited with code {completed.returncode}: {detail}"
        )

    return completed.stdout.decode("utf-8", "replace")


# Injectable module-level hooks (tests and CLI flows swap these).
_ACTIVE_PSQL_RUNNER: Callable[..., str] = execute_psql_docker


def set_active_psql_runner(runner: Callable[..., str]) -> None:
    """Sets the active module-level psql runner (used for tests or mocks)."""
    global _ACTIVE_PSQL_RUNNER
    _ACTIVE_PSQL_RUNNER = runner


# --- Health probe. ---


class _HttpResponse:
    """Minimal response object (status/body/json), e2e_common-compatible."""

    def __init__(self, status: int, body: bytes):
        self.status = status
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


class UrllibJsonClient:
    """Minimal e2e_common.Client-compatible JSON POST client."""

    def __init__(self, base_url: str, timeout: float = 15.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def post(self, path: str, body: Any = None) -> _HttpResponse:
        import urllib.error
        import urllib.request

        url = self.base_url + path
        data = json.dumps(body).encode("utf-8") if body is not None else None
        request = urllib.request.Request(
            url,
            data=data,
            headers={"Accept": "application/json", "Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return _HttpResponse(response.status, response.read())
        except urllib.error.HTTPError as error:
            return _HttpResponse(error.code, error.read())
        except urllib.error.URLError as error:
            raise SeedError(f"POST {url} failed: {error.reason}") from error


def urllib_health_probe(url: str, timeout: float = HEALTH_PROBE_TIMEOUT) -> Tuple[int, Any]:
    """GETs the readiness URL; raises HealthProbeError when unreachable."""
    import urllib.error
    import urllib.request

    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            status, body = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, body = error.code, error.read()
    except (urllib.error.URLError, OSError) as error:
        reason = getattr(error, "reason", error)
        raise HealthProbeError(f"{url} unreachable: {reason}") from error
    try:
        payload = json.loads(body.decode("utf-8", "replace")) if body else None
    except ValueError:
        payload = None
    return status, payload


# --- Backend process control. ---


def _is_raw_pid_alive(pid: int) -> bool:
    """Alive check for a PID this process does not own a handle to."""
    if pid is None or pid <= 0:
        return False
    if os.name == "nt":
        try:
            completed = subprocess.run(
                ["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"],
                capture_output=True,
                timeout=15,
                check=False,
            )
        except (OSError, subprocess.SubprocessError):
            return False
        return f'"{pid}"' in completed.stdout.decode("utf-8", "replace")
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def _terminate_raw_pid(pid: int, grace_seconds: float) -> None:
    """Terminates exactly this PID (never a name or the whole tree)."""
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/PID", str(pid), "/F"],
            capture_output=True,
            timeout=30,
            check=False,
        )
        return
    try:
        os.kill(pid, signal.SIGTERM)
    except (ProcessLookupError, PermissionError) as err:
        raise ProcessError(f"cannot terminate pid {pid}: {err}") from err
    deadline = time.monotonic() + max(0.0, grace_seconds)
    while _is_raw_pid_alive(pid):
        if time.monotonic() >= deadline:
            try:
                os.kill(pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                pass
            return
        time.sleep(0.2)


class SubprocessController:
    """Default java process controller: Popen spawn, PID-targeted terminate."""

    def __init__(self):
        self._handles: Dict[int, subprocess.Popen] = {}

    def spawn(self, command: Sequence[str], env: Dict[str, str], log_file: str) -> int:
        log_handle = open(log_file, "ab")
        try:
            process = subprocess.Popen(
                list(command),
                env=dict(env),
                stdout=log_handle,
                stderr=subprocess.STDOUT,
                stdin=subprocess.DEVNULL,
            )
        finally:
            log_handle.close()
        self._handles[process.pid] = process
        return process.pid

    def is_alive(self, pid: Optional[int]) -> bool:
        if pid is None:
            return False
        handle = self._handles.get(pid)
        if handle is not None:
            return handle.poll() is None
        return _is_raw_pid_alive(int(pid))

    def terminate(self, pid: int, grace_seconds: float = DEFAULT_STOP_GRACE_SECONDS) -> None:
        handle = self._handles.pop(pid, None)
        if handle is not None:
            handle.terminate()
            try:
                handle.wait(timeout=max(0.0, grace_seconds))
            except subprocess.TimeoutExpired:
                handle.kill()
                handle.wait(timeout=5.0)
            return
        _terminate_raw_pid(int(pid), max(0.0, grace_seconds))


_ACTIVE_PROCESS_CONTROLLER = SubprocessController()
_ACTIVE_HEALTH_PROBE = urllib_health_probe
_ACTIVE_CLIENT_FACTORY = UrllibJsonClient


def set_active_process_controller(controller: Any) -> None:
    global _ACTIVE_PROCESS_CONTROLLER
    _ACTIVE_PROCESS_CONTROLLER = controller


def set_active_health_probe(probe: Callable[..., Tuple[int, Any]]) -> None:
    global _ACTIVE_HEALTH_PROBE
    _ACTIVE_HEALTH_PROBE = probe


def set_active_client_factory(factory: Callable[[str], Any]) -> None:
    global _ACTIVE_CLIENT_FACTORY
    _ACTIVE_CLIENT_FACTORY = factory


# --- User seeding (e2e_common.py register/login contract). ---


def build_register_body(phone: str, display_name: str, password: str = SEED_PASSWORD) -> Dict[str, str]:
    """POST /api/v1/auth/register body, exactly as e2e_common.ensure_user."""
    return {"phone": phone, "password": password, "displayName": display_name}


def build_login_body(phone: str, password: str = SEED_PASSWORD) -> Dict[str, str]:
    """POST /api/v1/auth/login body, exactly as e2e_common.login."""
    return {"phone": phone, "password": password}


def _response_brief(response: Any) -> str:
    status = getattr(response, "status", "?")
    text = getattr(response, "text", "")
    return f"HTTP {status} {str(text)[:300]!r}"


def _retry_wait(response: Any, cap: float) -> float:
    """Extracts error.details.retryAfterSeconds + 1, bounded like e2e_common."""
    details: Dict[str, Any] = {}
    try:
        data = response.json()
    except Exception:  # non-JSON error body: fall back to the default wait
        data = None
    if isinstance(data, dict):
        error = data.get("error")
        if isinstance(error, dict) and isinstance(error.get("details"), dict):
            details = error["details"]
    try:
        seconds = int(details.get("retryAfterSeconds", 15)) + 1
    except (TypeError, ValueError):
        seconds = 16
    return float(min(cap, max(1, seconds)))


def _auth_record(phone: str, name: str, response: Any) -> Dict[str, str]:
    """Validates the shared auth response shape and builds the seed record."""
    try:
        data = response.json()
    except Exception as err:
        raise SeedError(f"auth response for {phone} is not JSON: {err}") from err
    if not isinstance(data, dict) or not data.get("accessToken"):
        raise SeedError(f"auth response for {phone} returned no accessToken")
    user = data.get("user")
    if not isinstance(user, dict) or not user.get("id"):
        raise SeedError(f"auth response for {phone} returned no user id")
    if user.get("phone") != phone:
        raise SeedError(
            f"auth response for {phone} returned user {user.get('phone')!r}"
        )
    return {
        "phone": phone,
        "name": name,
        "user_id": user["id"],
        "access_token": data["accessToken"],
    }


def _normalize_users(users: Iterable[Any]) -> List[Dict[str, str]]:
    records = []
    for user in users:
        if isinstance(user, dict):
            phone, name = user.get("phone"), user.get("name")
        else:
            phone, name = user
        if not isinstance(phone, str) or not isinstance(name, str) or not phone or not name:
            raise ValueError(f"each user needs a phone and name, got {user!r}")
        records.append({"phone": phone, "name": name})
    if not records:
        raise ValueError("seed requires at least one user")
    return records


def seed_users(
    client: Any,
    users: Iterable[Any],
    *,
    sleep: Callable[[float], None] = time.sleep,
) -> List[Dict[str, str]]:
    """Registers each user, or logs in when the account already exists.

    Contract (e2e_common.ensure_user/login): register returns 200/201 for a
    fresh account and 409 when the phone exists; 429 responses carry
    error.details.retryAfterSeconds and the budget is waited out instead of
    failing the run. Returns phone/name/user id/access token records.
    """
    seeded: List[Dict[str, str]] = []
    for user in _normalize_users(users):
        phone, name = user["phone"], user["name"]

        response = None
        for attempt in range(REGISTER_ATTEMPTS):
            response = client.post(REGISTER_PATH, body=build_register_body(phone, name))
            if response.status in (200, 201):
                seeded.append(_auth_record(phone, name, response))
                break
            if response.status == 429 and attempt < REGISTER_ATTEMPTS - 1:
                sleep(_retry_wait(response, REGISTER_RETRY_WAIT_CAP))
                continue
            break
        if response is not None and response.status in (200, 201):
            continue

        if response is None or response.status != 409:
            raise SeedError(
                f"register {phone} expected 200/201/409, got {_response_brief(response)}"
            )

        for attempt in range(LOGIN_ATTEMPTS):
            response = client.post(LOGIN_PATH, body=build_login_body(phone))
            if response.status == 200:
                seeded.append(_auth_record(phone, name, response))
                break
            if response.status == 429 and attempt < LOGIN_ATTEMPTS - 1:
                sleep(_retry_wait(response, LOGIN_RETRY_WAIT_CAP))
                continue
            raise SeedError(f"login {phone} expected 200, got {_response_brief(response)}")
    return seeded


# --- The disposable stack itself. ---


class DisposableStack:
    """Provisions and drives one disposable backend under a run directory."""

    def __init__(
        self,
        run_dir: str,
        *,
        container: str = DEFAULT_CONTAINER,
        db_user: str = DEFAULT_DB_USER,
        db_password: str = DEFAULT_DB_PASSWORD,
        db_host: str = DEFAULT_DB_HOST,
        db_port: int = DEFAULT_DB_PORT,
        server_target: str = DEFAULT_SERVER_TARGET,
        runner: Optional[Callable[..., str]] = None,
        port_picker: Optional[Callable[[], int]] = None,
        process_controller: Any = None,
        health_probe: Optional[Callable[..., Tuple[int, Any]]] = None,
        client_factory: Optional[Callable[[str], Any]] = None,
        sleep: Callable[[float], None] = time.sleep,
    ):
        self.run_dir = os.path.abspath(run_dir)
        validate_safe_identifier("container", container)
        validate_safe_identifier("db_user", db_user)
        if not is_local_host(db_host):
            raise TargetRefusalError(
                f"Refusing database host {db_host!r}; target host must be a local host"
            )
        self.container = container
        self.db_user = db_user
        self.db_password = db_password
        self.db_host = db_host
        self.db_port = int(db_port)
        self.server_target = server_target
        self.runner = runner or _ACTIVE_PSQL_RUNNER
        self.port_picker = port_picker or find_free_port
        self.process_controller = process_controller or _ACTIVE_PROCESS_CONTROLLER
        self.health_probe = health_probe or _ACTIVE_HEALTH_PROBE
        self.client_factory = client_factory or _ACTIVE_CLIENT_FACTORY
        self.sleep = sleep

    # --- state plumbing ---

    def load_state_required(self) -> Dict[str, Any]:
        state = load_state(self.run_dir)
        if state is None:
            raise StateError(
                f"no {STATE_FILE_NAME} under {self.run_dir}; run provision first"
            )
        return state

    def _psql(self, sql: str, *, db_name: str) -> str:
        return self.runner(
            sql,
            db_name=db_name,
            container=self.container,
            user=self.db_user,
            host=self.db_host,
            timeout=DEFAULT_PSQL_TIMEOUT,
        )

    def _db_exists(self, db_name: str) -> bool:
        output = self._psql(
            f"SELECT 1 FROM pg_database WHERE datname = '{db_name}';",
            db_name=MAINTENANCE_DB,
        )
        return bool(output.strip())

    def _create_database(self, db_name: str) -> None:
        self._psql(f'CREATE DATABASE "{db_name}";', db_name=MAINTENANCE_DB)

    def _drop_database(self, db_name: str) -> None:
        # postgres:16 supports WITH (FORCE): clears straggler connections.
        self._psql(
            f'DROP DATABASE IF EXISTS "{db_name}" WITH (FORCE);',
            db_name=MAINTENANCE_DB,
        )

    def _validated_state_db_name(self, state: Dict[str, Any]) -> str:
        db_name = state.get("db_name")
        try:
            return validate_db_name(db_name)
        except ValueError as err:
            raise NotOwnedError(
                f"state file records an invalid database name {db_name!r}: {err}"
            ) from err

    # --- provision ---

    def provision(
        self,
        db_name: Optional[str] = None,
        app_port: Optional[int] = None,
        *,
        admin_phones: str = "",
        e2ee_activation_enabled: bool = True,
        extra_env: Optional[Dict[str, str]] = None,
        extra_java_args: Optional[Sequence[str]] = None,
    ) -> Dict[str, Any]:
        """Creates a fresh DB, picks a port, and writes env.sh + state."""
        if load_state(self.run_dir) is not None:
            raise ProvisionError(
                f"{state_file_path(self.run_dir)} already exists; "
                "use a fresh run dir (or dropdb first)"
            )
        if db_name is not None:
            validate_db_name(db_name)
            if db_name == SHARED_DB_NAME:
                raise ProvisionError(
                    f"refusing to adopt the shared development database {SHARED_DB_NAME!r}"
                )
            attempts = 1
        else:
            attempts = RANDOM_DB_NAME_ATTEMPTS

        chosen: Optional[str] = None
        for _ in range(attempts):
            candidate = db_name if db_name is not None else generate_db_name()
            validate_db_name(candidate)
            if not self._db_exists(candidate):
                chosen = candidate
                break
        if chosen is None:
            if db_name is not None:
                raise ProvisionError(
                    f"database {db_name!r} already exists on container "
                    f"{self.container}; pass a fresh --db-name"
                )
            raise ProvisionError(
                f"no free database name found after {attempts} random attempts"
            )

        if app_port is None:
            app_port = self.port_picker()
        else:
            app_port = int(app_port)
            if not 1 <= app_port <= 65535:
                raise ValueError(f"invalid app port {app_port}")
            if app_port == self.db_port:
                raise ProvisionError(
                    f"app port {app_port} collides with the database port"
                )

        uploads_dir = uploads_dir_path(self.run_dir)
        os.makedirs(uploads_dir, exist_ok=True)
        self._create_database(chosen)

        state: Dict[str, Any] = {
            "version": STATE_VERSION,
            "owner": STATE_OWNER,
            "run_dir": self.run_dir,
            "db_name": chosen,
            "db_created_by": STATE_OWNER,
            "container": self.container,
            "db_user": self.db_user,
            "db_password": self.db_password,
            "db_host": self.db_host,
            "db_port": self.db_port,
            "jdbc_url": jdbc_url_for(chosen, host=self.db_host, port=self.db_port),
            "app_port": app_port,
            "base_url": base_url_for(app_port),
            "readiness_url": readiness_url_for(app_port),
            "jwt_secret": generate_jwt_secret(),
            "refresh_replay_key": generate_refresh_replay_key(),
            "admin_phones": str(admin_phones),
            "e2ee_activation_enabled": bool(e2ee_activation_enabled),
            "extra_env": {str(k): str(v) for k, v in (extra_env or {}).items()},
            "extra_java_args": [
                str(arg)
                for arg in (
                    extra_java_args
                    if extra_java_args is not None
                    else DEFAULT_EXTRA_JAVA_ARGS
                )
            ],
            "uploads_dir": uploads_dir,
            "env_file": env_file_path(self.run_dir),
            "pid_file": pid_file_path(self.run_dir),
            "log_file": log_file_path(self.run_dir),
            "jar_path": None,
            "pid": None,
            "provisioned_at": utc_now_iso(),
            "started_at": None,
            "ready_at": None,
            "stopped_at": None,
            "dropped": False,
            "dropped_at": None,
        }
        try:
            write_env_file(state)
            save_state(self.run_dir, state)
        except Exception:
            # Never leave a database behind a half-written run dir.
            try:
                self._drop_database(chosen)
            except DisposableStackError:
                pass
            raise
        return state

    # --- start / stop ---

    def _java_args(self, state: Dict[str, Any]) -> List[str]:
        return [
            f"--server.address={SERVER_BIND_HOST}",
            f"--server.port={state['app_port']}",
            *state.get("extra_java_args", []),
        ]

    def _wait_ready(self, url: str, timeout: float, poll_interval: float) -> None:
        """Polls /actuator/health/readiness until it reports status UP."""
        deadline = time.monotonic() + max(0.0, timeout)
        last_error = "no probe attempted"
        while True:
            try:
                status, payload = self.health_probe(url, timeout=HEALTH_PROBE_TIMEOUT)
            except HealthProbeError as err:
                last_error = str(err)
            else:
                if (
                    status == 200
                    and isinstance(payload, dict)
                    and payload.get("status") == "UP"
                ):
                    return
                last_error = f"HTTP {status} {payload!r}"
            if time.monotonic() >= deadline:
                raise ReadinessTimeoutError(
                    f"{url} not ready within {timeout:.1f}s (last: {last_error})"
                )
            self.sleep(max(0.0, min(poll_interval, deadline - time.monotonic())))

    def _terminate_pid(self, state: Dict[str, Any], pid: int) -> None:
        try:
            if self.process_controller.is_alive(pid):
                self.process_controller.terminate(
                    pid, grace_seconds=DEFAULT_STOP_GRACE_SECONDS
                )
        except DisposableStackError:
            pass  # best-effort cleanup; the original error is the story
        finally:
            remove_pid_file(self.run_dir)
            state["pid"] = None
            save_state(self.run_dir, state)

    def start(
        self,
        jar: Optional[str] = None,
        *,
        readiness_timeout: float = DEFAULT_READINESS_TIMEOUT,
        poll_interval: float = DEFAULT_READINESS_POLL_INTERVAL,
    ) -> Dict[str, Any]:
        """Spawns `java -jar` for this stack and waits for readiness."""
        state = self.load_state_required()
        pid = read_pid_file(self.run_dir)
        if pid is not None and self.process_controller.is_alive(pid):
            raise ProcessError(f"backend already running (pid {pid}); stop first")

        jar_path = jar or state.get("jar_path") or find_newest_jar(self.server_target)
        if not os.path.isfile(jar_path):
            raise ProvisionError(f"jar not found: {jar_path}")
        state["jar_path"] = os.path.abspath(jar_path)

        env = dict(os.environ)
        env.update(build_env(state))
        command = ["java", "-jar", state["jar_path"], *self._java_args(state)]

        pid = self.process_controller.spawn(command, env, state["log_file"])
        write_pid_file(self.run_dir, pid)
        state["pid"] = pid
        state["started_at"] = utc_now_iso()
        save_state(self.run_dir, state)
        try:
            self._wait_ready(state["readiness_url"], readiness_timeout, poll_interval)
        except BaseException:
            # Never leave an orphaned process behind a failed start.
            self._terminate_pid(state, pid)
            raise
        state["ready_at"] = utc_now_iso()
        save_state(self.run_dir, state)
        return state

    def stop(self, grace_seconds: float = DEFAULT_STOP_GRACE_SECONDS) -> Dict[str, Any]:
        """Terminates only the PID recorded in this stack's own PID file."""
        state = self.load_state_required()
        pid = read_pid_file(self.run_dir)
        if pid is None or not self.process_controller.is_alive(pid):
            if pid is not None:
                remove_pid_file(self.run_dir)  # stale PID file
            state["pid"] = None
            save_state(self.run_dir, state)
            return state

        self.process_controller.terminate(pid, grace_seconds=grace_seconds)
        if self.process_controller.is_alive(pid):
            raise ProcessError(f"backend pid {pid} did not exit within {grace_seconds}s")
        remove_pid_file(self.run_dir)
        state["pid"] = None
        state["stopped_at"] = utc_now_iso()
        save_state(self.run_dir, state)
        return state

    # --- status / seed / dropdb ---

    def status(self) -> Dict[str, Any]:
        """Local-only report: files, PID liveness, and DB existence."""
        state = load_state(self.run_dir)
        if state is None:
            return {
                "run_dir": self.run_dir,
                "provisioned": False,
                "running": False,
            }
        pid = read_pid_file(self.run_dir)
        running = pid is not None and self.process_controller.is_alive(pid)
        dropped = bool(state.get("dropped"))
        db_exists = False
        if not dropped and state.get("db_created_by") == STATE_OWNER:
            try:
                db_exists = self._db_exists(self._validated_state_db_name(state))
            except DisposableStackError:
                db_exists = False
        return {
            "provisioned": True,
            "run_dir": self.run_dir,
            "db_name": state.get("db_name"),
            "db_exists": db_exists,
            "dropped": dropped,
            "container": state.get("container"),
            "app_port": state.get("app_port"),
            "base_url": state.get("base_url"),
            "jar_path": state.get("jar_path"),
            "pid": pid if running else None,
            "running": running,
            "env_file": state.get("env_file"),
            "pid_file": state.get("pid_file"),
            "provisioned_at": state.get("provisioned_at"),
            "started_at": state.get("started_at"),
            "stopped_at": state.get("stopped_at"),
        }

    def seed(
        self,
        users: Iterable[Any],
        *,
        client: Any = None,
        password: str = SEED_PASSWORD,
    ) -> List[Dict[str, str]]:
        """Registers/logs in the given phone/name pairs; returns tokens + ids."""
        state = self.load_state_required()
        if client is None:
            client = self.client_factory(state["base_url"])
        return seed_users(client, users, sleep=self.sleep)

    def dropdb(self) -> Dict[str, Any]:
        """Drops only the database recorded in this run dir's state file."""
        try:
            state = self.load_state_required()
        except StateError as err:
            raise NotOwnedError(
                f"nothing owned here: {err}"
            ) from err
        if state.get("db_created_by") != STATE_OWNER:
            raise NotOwnedError(
                f"{state_file_path(self.run_dir)} does not record a database "
                f"created by disposable_stack; refusing to drop "
                f"{state.get('db_name')!r}"
            )
        db_name = self._validated_state_db_name(state)
        if db_name == SHARED_DB_NAME:
            raise NotOwnedError(
                f"refusing to drop {db_name!r}; that is the shared development database"
            )
        if state.get("dropped"):
            raise NotOwnedError(
                f"database {db_name!r} was already dropped at {state.get('dropped_at')}"
            )
        pid = read_pid_file(self.run_dir)
        if pid is not None and self.process_controller.is_alive(pid):
            raise ProcessError(
                f"backend still running (pid {pid}); stop before dropping the database"
            )
        self._drop_database(db_name)
        state["dropped"] = True
        state["dropped_at"] = utc_now_iso()
        save_state(self.run_dir, state)
        return state

    def sql(self, sql: str, *, db_name: Optional[str] = None) -> str:
        """Runs psql against the provisioned database (or an explicitly named one).

        The default target is the database recorded in the run dir state, so
        callers cannot accidentally hit the shared development database.
        """
        state = self.load_state_required()
        target = db_name or self._validated_state_db_name(state)
        return self._psql(sql, db_name=target)


# --- CLI. ---


def _parse_env_pairs(values: Optional[Sequence[str]]) -> Dict[str, str]:
    extra: Dict[str, str] = {}
    for value in values or []:
        key, sep, val = value.partition("=")
        if not sep or not _ENV_KEY_RE.fullmatch(key):
            raise ValueError(f"--extra-env expects KEY=VALUE, got {value!r}")
        extra[key] = val
    return extra


def _parse_user_specs(values: Optional[Sequence[str]]) -> List[Dict[str, str]]:
    users: List[Dict[str, str]] = []
    for value in values or []:
        phone, sep, name = value.partition("=")
        if not sep:
            raise ValueError(f"--user expects PHONE=NAME, got {value!r}")
        phone, name = phone.strip(), name.strip()
        if not phone or not name:
            raise ValueError(f"--user expects PHONE=NAME, got {value!r}")
        if not _PHONE_RE.fullmatch(phone):
            raise ValueError(f"--user phone {phone!r} is not an E.164-style number")
        users.append({"phone": phone, "name": name})
    if not users:
        raise ValueError("seed requires at least one --user PHONE=NAME")
    return users


def build_parser() -> argparse.ArgumentParser:
    """Builds the CLI argument parser."""
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )

    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("run_dir", help="caller-chosen run directory for this stack")
    common.add_argument(
        "--container", default=DEFAULT_CONTAINER,
        help=f"DB container (default: {DEFAULT_CONTAINER})",
    )
    common.add_argument(
        "--db-user", default=DEFAULT_DB_USER,
        help=f"Postgres user (default: {DEFAULT_DB_USER})",
    )
    common.add_argument(
        "--db-password", default=DEFAULT_DB_PASSWORD,
        help=f"Postgres password (default: {DEFAULT_DB_PASSWORD})",
    )
    common.add_argument(
        "--db-host", default=DEFAULT_DB_HOST,
        help=f"Host target check + JDBC host (default: {DEFAULT_DB_HOST})",
    )
    common.add_argument(
        "--db-port", type=int, default=DEFAULT_DB_PORT,
        help=f"Host-mapped Postgres port (default: {DEFAULT_DB_PORT})",
    )

    subparsers = parser.add_subparsers(dest="command", required=True)

    p_provision = subparsers.add_parser(
        "provision", parents=[common], help="Create a fresh DB + env.sh under run_dir"
    )
    p_provision.add_argument(
        "--db-name", default=None,
        help="Database name (default: nexachat_e2e_<random suffix>)",
    )
    p_provision.add_argument(
        "--port", type=int, default=None,
        help=f"Backend port (default: first free port in {DEFAULT_PORT_RANGE[0]}-{DEFAULT_PORT_RANGE[1]})",
    )
    p_provision.add_argument(
        "--admin-phones", default="",
        help="Comma-separated E.164 numbers allowed to read operator telemetry",
    )
    p_provision.add_argument(
        "--no-e2ee-activation", dest="e2ee_activation_enabled",
        action="store_false", default=True,
        help="Keep the E2EE v1 activation rollout gate off",
    )
    p_provision.add_argument(
        "--extra-env", action="append", default=None, metavar="KEY=VALUE",
        help="Additional env.sh export (repeatable)",
    )
    p_provision.add_argument(
        "--java-arg", action="append", default=None, metavar="ARG",
        help="Replace the default extra java args (repeatable)",
    )
    p_provision.add_argument(
        "--server-target", default=DEFAULT_SERVER_TARGET,
        help=f"server/target dir for later start() jar discovery (default: {DEFAULT_SERVER_TARGET})",
    )

    p_start = subparsers.add_parser(
        "start", parents=[common], help="Spawn java -jar and wait for readiness"
    )
    p_start.add_argument("--jar", default=None, help="Explicit jar path (default: newest under server/target)")
    p_start.add_argument(
        "--readiness-timeout", type=float, default=DEFAULT_READINESS_TIMEOUT,
        help=f"Seconds to wait for readiness (default: {DEFAULT_READINESS_TIMEOUT})",
    )
    p_start.add_argument(
        "--poll-interval", type=float, default=DEFAULT_READINESS_POLL_INTERVAL,
        help=f"Readiness poll interval in seconds",
    )
    p_start.add_argument(
        "--server-target", default=DEFAULT_SERVER_TARGET,
        help=f"server/target dir for jar discovery (default: {DEFAULT_SERVER_TARGET})",
    )

    p_stop = subparsers.add_parser(
        "stop", parents=[common], help="Terminate this stack's own backend PID"
    )
    p_stop.add_argument(
        "--grace", type=float, default=DEFAULT_STOP_GRACE_SECONDS,
        help=f"Seconds before SIGKILL/taskkill escalation (default: {DEFAULT_STOP_GRACE_SECONDS})",
    )

    p_status = subparsers.add_parser(
        "status", parents=[common], help="Report files, PID liveness, DB existence"
    )
    p_status.add_argument("--json", action="store_true", help="Output JSON")

    p_seed = subparsers.add_parser(
        "seed", parents=[common],
        help="Register/login --user PHONE=NAME accounts; prints tokens as JSON",
    )
    p_seed.add_argument(
        "--user", action="append", default=None, metavar="PHONE=NAME",
        help="Seed account (repeatable, e.g. --user \"+84900000001=An\")",
    )

    p_drop = subparsers.add_parser(
        "dropdb", parents=[common],
        help="Drop the database recorded in this run dir's state file",
    )

    return parser


def main(argv: Optional[List[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])

    try:
        stack = DisposableStack(
            args.run_dir,
            container=args.container,
            db_user=args.db_user,
            db_password=args.db_password,
            db_host=args.db_host,
            db_port=args.db_port,
            server_target=getattr(args, "server_target", DEFAULT_SERVER_TARGET),
        )

        if args.command == "provision":
            state = stack.provision(
                db_name=args.db_name,
                app_port=args.port,
                admin_phones=args.admin_phones,
                e2ee_activation_enabled=args.e2ee_activation_enabled,
                extra_env=_parse_env_pairs(args.extra_env),
                extra_java_args=args.java_arg,
            )
            print(f"provisioned db {state['db_name']} on {state['container']}")
            print(f"jdbc:     {state['jdbc_url']}")
            print(f"app port: {state['app_port']} -> {state['base_url']}")
            print(f"env file: {state['env_file']}")
            print(f"uploads:  {state['uploads_dir']}")
            return 0

        if args.command == "start":
            state = stack.start(
                jar=args.jar,
                readiness_timeout=args.readiness_timeout,
                poll_interval=args.poll_interval,
            )
            print(
                f"backend pid {state['pid']} listening at {state['base_url']} "
                f"(readiness OK, log: {state['log_file']})"
            )
            return 0

        if args.command == "stop":
            pid_before = read_pid_file(stack.run_dir)
            was_running = (
                pid_before is not None
                and stack.process_controller.is_alive(pid_before)
            )
            state = stack.stop(grace_seconds=args.grace)
            if was_running:
                print(f"backend stopped (pid {pid_before})")
            else:
                print("backend not running")
            return 0

        if args.command == "status":
            report = stack.status()
            if args.json:
                print(json.dumps(report, indent=2))
            else:
                for key in sorted(report):
                    print(f"{key}: {report[key]}")
            return 0

        if args.command == "seed":
            records = stack.seed(_parse_user_specs(args.user))
            print(json.dumps(records, indent=2))
            return 0

        if args.command == "dropdb":
            state = stack.dropdb()
            print(f"dropped database {state['db_name']}")
            return 0

        parser.print_help(sys.stderr)
        return 2

    except DisposableStackError as err:
        print(f"disposable_stack: error: {err}", file=sys.stderr)
        return 1
    except ValueError as err:
        print(f"disposable_stack: error: {err}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
