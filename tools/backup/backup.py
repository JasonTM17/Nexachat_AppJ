#!/usr/bin/env python3
"""Paired database + object backup for NexaChat (local fixture scope)."""

from __future__ import annotations

import argparse
import logging
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import uuid
from pathlib import Path
from typing import BinaryIO
from urllib.parse import urlparse

from manifest import (
    COMPLETE_MARKER,
    DUMP_NAME,
    FORMAT_VERSION,
    OBJECTS_DIR,
    Reconciliation,
    configure_logging,
    canonical_storage_reference,
    is_canonical_storage_key,
    lock_path,
    manifest_path,
    sha256_file,
    utc_now_iso,
    write_complete_marker,
    write_manifest,
)

LOGGER = logging.getLogger(__name__)


def read_secret(name: str, file_var: str | None = None) -> str | None:
    value = os.environ.get(name)
    if value:
        return value
    file_key = file_var or f"{name}_FILE"
    file_path = os.environ.get(file_key)
    if not file_path:
        return None
    return Path(file_path).read_text(encoding="utf-8").strip()


def parse_db_config() -> dict[str, str]:
    raw = os.environ.get("BACKUP_DB_URL") or os.environ.get("DATABASE_URL")
    if not raw:
        raise SystemExit("Set BACKUP_DB_URL or DATABASE_URL for database access.")
    jdbc = raw.startswith("jdbc:")
    url = raw[5:] if jdbc else raw
    parsed = urlparse(url)
    host = parsed.hostname or "localhost"
    port = str(parsed.port or 5432)
    database = (parsed.path or "/").lstrip("/") or "nexachat"
    user = os.environ.get("BACKUP_DB_USER") or os.environ.get("DATABASE_USER")
    password = read_secret("BACKUP_DB_PASSWORD", "BACKUP_DB_PASSWORD_FILE") or read_secret(
        "DATABASE_PASSWORD", "DATABASE_PASSWORD_FILE"
    )
    if not user or not password:
        raise SystemExit("Database user/password must come from environment or *_FILE vars.")
    return {
        "host": host,
        "port": port,
        "database": database,
        "user": user,
        "password": password,
    }


def storage_kind() -> str:
    provider = (os.environ.get("NEXACHAT_STORAGE_PROVIDER") or "local").strip().lower()
    if provider not in {"local", "s3"}:
        raise SystemExit("NEXACHAT_STORAGE_PROVIDER must be local or s3.")
    return provider


def local_storage_root() -> Path:
    root = os.environ.get("NEXACHAT_STORAGE_ROOT") or os.environ.get("STORAGE_ROOT") or "./data/uploads"
    return Path(root).expanduser().resolve()


def refuse_existing_destination(output_dir: Path) -> None:
    """Reject a destination that already holds a bundle before any write."""
    if not output_dir.exists():
        return
    if not output_dir.is_dir():
        raise SystemExit(f"Backup destination {output_dir} is not a directory.")
    if any(output_dir.iterdir()):
        raise SystemExit(
            "Backup destination is not empty; refusing to modify a completed or partial bundle."
        )


def acquire_lock(output_dir: Path, owner: str) -> str:
    """Create this process's lock with exclusive creation. Returns the owner token."""
    output_dir.mkdir(parents=True, exist_ok=True)
    lock = lock_path(output_dir)
    token = f"{owner}:{os.getpid()}:{uuid.uuid4()}"
    try:
        fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        try:
            existing = lock.read_text(encoding="utf-8").strip()
        except OSError:
            existing = "(unreadable)"
        raise SystemExit(f"Concurrent or partial capture: lock exists ({existing!r}).")
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(token + "\n")
    return token


def release_lock(output_dir: Path, token: str) -> None:
    """Remove the lock only when it still belongs to this capture."""
    lock = lock_path(output_dir)
    try:
        current = lock.read_text(encoding="utf-8").strip()
    except (FileNotFoundError, OSError):
        return
    if current != token:
        return
    try:
        lock.unlink()
    except FileNotFoundError:
        return


def publish_staging(staging: Path, output_dir: Path) -> None:
    """Move a finished staging tree into the destination, marker last."""
    entries = sorted(staging.iterdir(), key=lambda path: path.name == COMPLETE_MARKER)
    for entry in entries:
        dest = output_dir / entry.name
        if dest.exists():
            raise SystemExit("Refusing to overwrite backup destination content.")
        shutil.move(os.fspath(entry), os.fspath(dest))


def docker_db_host(db: dict[str, str]) -> str:
    if db["host"] in {"localhost", "127.0.0.1"}:
        return os.environ.get("BACKUP_DOCKER_DB_HOST", "host.docker.internal")
    return db["host"]


def docker_pg_env(password: str) -> dict[str, str]:
    env = os.environ.copy()
    env["PGPASSWORD"] = password
    return env


def build_docker_pg_tool_command(image: str, tool: str, db: dict[str, str], host: str) -> list[str]:
    """Build docker argv for pg client tools; password is supplied via subprocess env only."""
    return [
        "docker",
        "run",
        "--rm",
        "-e",
        "PGPASSWORD",
        image,
        tool,
        "-h",
        host,
        "-p",
        db["port"],
        "-U",
        db["user"],
        "-d",
        db["database"],
    ]


def build_pg_dump_command(db: dict[str, str], image: str | None = None) -> list[str]:
    image = image or os.environ.get("BACKUP_PGDOCKER_IMAGE", "postgres:18")
    host = docker_db_host(db)
    return build_docker_pg_tool_command(image, "pg_dump", db, host) + [
        "--no-owner",
        "--no-privileges",
    ]


def pg_dump_client_major(image: str, env: dict[str, str]) -> int:
    proc = subprocess.run(
        ["docker", "run", "--rm", image, "pg_dump", "--version"],
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        raise SystemExit(f"pg_dump --version failed: {proc.stderr.strip()}")
    match = re.search(r"(\d+)\.", proc.stdout or proc.stderr)
    if not match:
        raise SystemExit(f"cannot parse pg_dump version from {proc.stdout!r}")
    return int(match.group(1))


def pg_server_major(db: dict[str, str], image: str, env: dict[str, str], host: str) -> int:
    cmd = build_docker_pg_tool_command(image, "psql", db, host) + ["-At", "-c", "SHOW server_version_num;"]
    proc = subprocess.run(cmd, capture_output=True, text=True, env=env, check=False)
    if proc.returncode != 0:
        raise SystemExit(f"server version query failed: {proc.stderr.strip()}")
    raw = (proc.stdout or "").strip()
    if not raw.isdigit():
        raise SystemExit(f"unexpected server_version_num {raw!r}")
    return int(raw) // 10000


COPY_FROM_STDIN_RE = re.compile(r"^\s*COPY\b.*\bFROM\s+stdin\s*;\s*$", re.IGNORECASE)


class DumpLineFilter:
    """Rewrite pg_dump statements without touching COPY data rows.

    A line-based filter is unsafe on its own: a table row can begin with the same
    text as a GUC statement. Only strip while outside a ``COPY ... FROM stdin;``
    block, which ends at the ``\\.`` terminator line.
    """

    def __init__(self, server_major: int) -> None:
        self._server_major = server_major
        self._in_copy_data = False

    def feed(self, line: bytes) -> bytes | None:
        if self._server_major >= 17:
            return line
        try:
            text = line.decode("utf-8")
        except UnicodeDecodeError:
            return line
        if self._in_copy_data:
            if text.rstrip("\r\n") == "\\.":
                self._in_copy_data = False
            return line
        if COPY_FROM_STDIN_RE.match(text):
            self._in_copy_data = True
            return line
        if text.lstrip().startswith("SET transaction_timeout"):
            return None
        return line


def filter_dump_line(line: bytes, server_major: int) -> bytes | None:
    """Filter one isolated statement line; complete dumps must use ``DumpLineFilter``."""
    return DumpLineFilter(server_major).feed(line)


class StderrCollector:
    """Read a child's stderr on its own thread so a full pipe cannot stall the child."""

    def __init__(self, stream: BinaryIO) -> None:
        self._stream = stream
        self._chunks: list[bytes] = []
        self._thread = threading.Thread(target=self._drain, daemon=True)

    def _drain(self) -> None:
        while True:
            chunk = self._stream.read(65536)
            if not chunk:
                return
            self._chunks.append(chunk)

    def start(self) -> None:
        self._thread.start()

    def finish(self) -> bytes:
        self._thread.join()
        return b"".join(self._chunks)


def run_pg_dump(db: dict[str, str], dest: Path) -> dict[str, int]:
    image = os.environ.get("BACKUP_PGDOCKER_IMAGE", "postgres:18")
    host = docker_db_host(db)
    env = docker_pg_env(db["password"])
    client_major = pg_dump_client_major(image, env)
    server_major = pg_server_major(db, image, env, host)
    if client_major < server_major:
        raise SystemExit(
            f"pg_dump client major {client_major} is older than server major {server_major}; "
            f"raise BACKUP_PGDOCKER_IMAGE"
        )
    cmd = build_pg_dump_command(db, image)
    dest.parent.mkdir(parents=True, exist_ok=True)
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    assert proc.stdout is not None
    assert proc.stderr is not None
    stderr_collector = StderrCollector(proc.stderr)
    stderr_collector.start()
    line_filter = DumpLineFilter(server_major)
    with dest.open("wb") as out:
        for line in proc.stdout:
            filtered = line_filter.feed(line)
            if filtered is not None:
                out.write(filtered)
    rc = proc.wait()
    stderr = stderr_collector.finish()
    if rc != 0:
        raise SystemExit(f"pg_dump via {image} failed: {stderr.decode(errors='replace')}")
    return {"pg_dump_client_major": client_major, "pg_server_major": server_major}


def inventory_storage_keys(lines: list[str]) -> set[str]:
    """Resolve attachment keys and avatar URLs to one set of canonical keys.

    Avatar URLs are decoded with the same query, fragment, and percent-encoding
    rules as the server. A local reference that does not resolve is an error;
    it is not dropped from the reconciliation count.
    """
    keys: set[str] = set()
    unresolved: list[str] = []
    for line in lines:
        raw = line.strip()
        if not raw:
            continue
        key = canonical_storage_reference(raw)
        if not is_canonical_storage_key(key):
            unresolved.append(raw)
            continue
        keys.add(key)
    if unresolved:
        raise SystemExit("Unresolved local storage reference: " + ", ".join(unresolved))
    return keys


def query_referenced_keys_psql(db: dict[str, str]) -> set[str]:
    """Collect keys using only docker/psql (stdlib rehearsal path)."""
    sql = """
COPY (
  SELECT storage_key FROM message_attachments
  UNION
  SELECT avatar_url FROM users WHERE avatar_url LIKE '%/api/v1/files/%'
  UNION
  SELECT avatar_url FROM conversations WHERE avatar_url LIKE '%/api/v1/files/%'
) TO STDOUT;
"""
    container = os.environ.get("BACKUP_PG_CONTAINER")
    if container:
        proc = subprocess.run(
            ["docker", "exec", "-i", container, "psql", "-U", db["user"], "-d", db["database"], "-At", "-c", sql],
            capture_output=True,
            text=True,
            check=False,
        )
    else:
        env = docker_pg_env(db["password"])
        proc = subprocess.run(
            build_docker_pg_tool_command(
                os.environ.get("BACKUP_PGDOCKER_IMAGE", "postgres:18"),
                "psql",
                db,
                docker_db_host(db),
            )
            + ["-At", "-c", sql],
            capture_output=True,
            text=True,
            check=False,
            env=env,
        )
    if proc.returncode != 0:
        raise SystemExit(f"Failed to inventory storage references: {proc.stderr.strip()}")
    return inventory_storage_keys(proc.stdout.splitlines())


def active_writers(db: dict[str, str]) -> int:
    sql = """
SELECT count(*)::text FROM pg_stat_activity
WHERE datname = current_database()
  AND pid <> pg_backend_pid()
  AND state <> 'idle';
"""
    container = os.environ.get("BACKUP_PG_CONTAINER")
    if container:
        proc = subprocess.run(
            ["docker", "exec", container, "psql", "-U", db["user"], "-d", db["database"], "-At", "-c", sql],
            capture_output=True,
            text=True,
            check=False,
        )
    else:
        env = docker_pg_env(db["password"])
        proc = subprocess.run(
            build_docker_pg_tool_command(
                os.environ.get("BACKUP_PGDOCKER_IMAGE", "postgres:18"),
                "psql",
                db,
                docker_db_host(db),
            )
            + ["-At", "-c", sql],
            capture_output=True,
            text=True,
            check=False,
            env=env,
        )
    if proc.returncode != 0:
        raise SystemExit(f"Failed to check active writers: {proc.stderr.strip()}")
    return int((proc.stdout or "0").strip() or "0")


def copy_local_object(storage_root: Path, storage_key: str, dest: Path) -> int:
    if not is_canonical_storage_key(storage_key):
        raise ValueError(f"refusing unsafe storage key {storage_key!r}")
    root = storage_root.resolve()
    source = (root / storage_key).resolve()
    if not source.is_relative_to(root):
        raise ValueError(f"traversal blocked for key {storage_key!r}")
    if not source.is_file():
        raise FileNotFoundError(storage_key)
    dest.parent.mkdir(parents=True, exist_ok=True)
    data = source.read_bytes()
    dest.write_bytes(data)
    return len(data)


def copy_s3_object(storage_key: str, dest: Path) -> int:
    try:
        import boto3  # type: ignore[import-not-found]
        from botocore.config import Config  # type: ignore[import-not-found]
    except ImportError as exc:
        raise SystemExit("S3 backup requires boto3 installed in the operator environment.") from exc
    if not is_canonical_storage_key(storage_key):
        raise ValueError(f"refusing unsafe storage key {storage_key!r}")
    endpoint = os.environ.get("NEXACHAT_STORAGE_S3_ENDPOINT")
    bucket = os.environ.get("NEXACHAT_STORAGE_S3_BUCKET")
    access = os.environ.get("NEXACHAT_STORAGE_S3_ACCESS_KEY_ID")
    secret = os.environ.get("NEXACHAT_STORAGE_S3_SECRET_ACCESS_KEY")
    if not all([endpoint, bucket, access, secret]):
        raise SystemExit("S3 env vars missing for backup.")
    client = boto3.client(
        "s3",
        endpoint_url=endpoint,
        aws_access_key_id=access,
        aws_secret_access_key=secret,
        config=Config(signature_version="s3v4"),
    )
    dest.parent.mkdir(parents=True, exist_ok=True)
    response = client.get_object(Bucket=bucket, Key=storage_key)
    body = response["Body"].read()
    dest.write_bytes(body)
    return len(body)


def object_backup_name(storage_key: str) -> str:
    return storage_key.replace("/", "__")


def capture_staged(target: str, staging: Path) -> tuple[dict[str, object], int, int]:
    """Write a complete bundle into an empty staging directory.

    Returns the manifest payload, total captured bytes, and object count.
    """
    db = parse_db_config()
    kind = storage_kind()
    writers = active_writers(db)
    if writers > 0:
        raise SystemExit(f"Active writers detected ({writers}); quiesce synthetic jobs first.")
    referenced = sorted(query_referenced_keys_psql(db))
    objects_dir = staging / OBJECTS_DIR
    objects_dir.mkdir(parents=True, exist_ok=True)
    present: list[str] = []
    missing: list[str] = []
    object_entries: dict[str, dict[str, object]] = {}
    root = local_storage_root()
    for key in referenced:
        rel = object_backup_name(key)
        dest = objects_dir / rel
        try:
            if kind == "local":
                size = copy_local_object(root, key, dest)
            else:
                size = copy_s3_object(key, dest)
            present.append(key)
            object_entries[key] = {
                "backup_name": rel,
                "sha256": sha256_file(dest),
                "size_bytes": size,
            }
        except FileNotFoundError:
            missing.append(key)
        except ValueError as err:
            raise SystemExit(str(err)) from err
    reconciliation = Reconciliation(len(referenced), len(present), missing)
    if missing:
        raise SystemExit(f"Referenced objects missing from storage: {missing}")
    dump_path = staging / DUMP_NAME
    pg_versions = run_pg_dump(db, dump_path)
    payload: dict[str, object] = {
        "format_version": FORMAT_VERSION,
        "target": target,
        "created_at": utc_now_iso(),
        "storage_kind": kind,
        "database": {
            "name": db["database"],
            "host": db["host"],
            "port": db["port"],
            "dump_file": DUMP_NAME,
            "sha256": sha256_file(dump_path),
            **pg_versions,
        },
        "objects": object_entries,
        "reconciliation": reconciliation.as_dict(),
    }
    write_manifest(manifest_path(staging), payload)
    write_complete_marker(staging)
    total_bytes = dump_path.stat().st_size + sum(
        int(entry["size_bytes"]) for entry in object_entries.values()
    )
    return payload, total_bytes, len(object_entries)


def run_backup(target: str, output_dir: Path, quiesced: bool) -> dict[str, object]:
    if not quiesced:
        raise SystemExit("Refusing backup without --quiesced acknowledgement.")
    refuse_existing_destination(output_dir)
    token: str | None = None
    staging: Path | None = None
    try:
        token = acquire_lock(output_dir, target)
        staging = Path(tempfile.mkdtemp(prefix=f".{output_dir.name}.staging-", dir=output_dir.parent))
        payload, total_bytes, object_count = capture_staged(target, staging)
        publish_staging(staging, output_dir)
        LOGGER.info(
            "backup complete target=%s bytes=%s objects=%s",
            target,
            total_bytes,
            object_count,
        )
        return payload
    finally:
        if staging is not None and staging.exists():
            shutil.rmtree(staging, ignore_errors=True)
        if token is not None:
            release_lock(output_dir, token)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="NexaChat paired DB + object backup")
    parser.add_argument("--target", required=True, help="Non-secret capture identity label")
    parser.add_argument("--output", required=True, type=Path, help="Private backup output directory")
    parser.add_argument(
        "--quiesced",
        action="store_true",
        help="Acknowledge writers are quiesced before capture",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    configure_logging()
    args = build_parser().parse_args(argv)
    output_dir = args.output.expanduser().resolve()
    run_backup(args.target, output_dir, args.quiesced)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
