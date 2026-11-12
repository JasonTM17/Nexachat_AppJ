#!/usr/bin/env python3
"""Paired database + object backup for NexaChat (local fixture scope)."""

from __future__ import annotations

import argparse
import logging
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlparse

from manifest import (
    DUMP_NAME,
    FORMAT_VERSION,
    OBJECTS_DIR,
    Reconciliation,
    complete_marker_path,
    configure_logging,
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


def acquire_lock(output_dir: Path, owner: str) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    lock = lock_path(output_dir)
    if lock.exists():
        try:
            existing = lock.read_text(encoding="utf-8").strip()
        except OSError:
            existing = "(unreadable)"
        raise SystemExit(f"Concurrent or partial capture: lock exists ({existing!r}).")
    lock.write_text(owner + "\n", encoding="utf-8")


def release_lock(output_dir: Path) -> None:
    lock = lock_path(output_dir)
    if lock.is_file():
        lock.unlink()


def detect_partial_output(output_dir: Path) -> None:
    if complete_marker_path(output_dir).exists() and not manifest_path(output_dir).exists():
        raise SystemExit("Partial output: complete marker without manifest.")
    if manifest_path(output_dir).exists() and not complete_marker_path(output_dir).exists():
        raise SystemExit("Partial output: manifest without complete marker (interrupted capture).")


def query_referenced_keys_psql(db: dict[str, str]) -> set[str]:
    """Collect keys using only docker/psql (stdlib rehearsal path)."""
    sql = """
COPY (
  SELECT storage_key FROM message_attachments
  UNION
  SELECT substring(avatar_url from '.*/api/v1/files/(.+)$') AS storage_key
  FROM (
    SELECT avatar_url FROM users WHERE avatar_url LIKE '%/api/v1/files/%'
    UNION ALL
    SELECT avatar_url FROM conversations WHERE avatar_url LIKE '%/api/v1/files/%'
  ) avatars
  WHERE substring(avatar_url from '.*/api/v1/files/(.+)$') IS NOT NULL
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
        env = os.environ.copy()
        env["PGPASSWORD"] = db["password"]
        proc = subprocess.run(
            [
                "psql",
                "-h",
                db["host"],
                "-p",
                db["port"],
                "-U",
                db["user"],
                "-d",
                db["database"],
                "-At",
                "-c",
                sql,
            ],
            capture_output=True,
            text=True,
            check=False,
            env=env,
        )
    if proc.returncode != 0:
        raise SystemExit(f"Failed to inventory storage references: {proc.stderr.strip()}")
    keys: set[str] = set()
    for line in proc.stdout.splitlines():
        key = line.strip()
        if key and is_canonical_storage_key(key):
            keys.add(key)
    return keys


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
        env = os.environ.copy()
        env["PGPASSWORD"] = db["password"]
        proc = subprocess.run(
            [
                "psql",
                "-h",
                db["host"],
                "-p",
                db["port"],
                "-U",
                db["user"],
                "-d",
                db["database"],
                "-At",
                "-c",
                sql,
            ],
            capture_output=True,
            text=True,
            check=False,
            env=env,
        )
    if proc.returncode != 0:
        raise SystemExit(f"Failed to check active writers: {proc.stderr.strip()}")
    return int((proc.stdout or "0").strip() or "0")


def run_pg_dump(db: dict[str, str], dest: Path) -> None:
    image = os.environ.get("BACKUP_PGDOCKER_IMAGE", "postgres:18")
    container = os.environ.get("BACKUP_PG_CONTAINER")
    dest.parent.mkdir(parents=True, exist_ok=True)
    if container:
        proc = subprocess.run(
            [
                "docker",
                "exec",
                container,
                "pg_dump",
                "-U",
                db["user"],
                "-d",
                db["database"],
                "--no-owner",
                "--no-privileges",
            ],
            capture_output=True,
            check=False,
        )
        if proc.returncode != 0:
            raise SystemExit(f"pg_dump failed: {proc.stderr.decode(errors='replace')}")
        dest.write_bytes(proc.stdout)
        return
    with tempfile.NamedTemporaryFile(delete=False) as password_file:
        password_file.write(db["password"].encode("utf-8"))
        password_path = password_file.name
    try:
        cmd = [
            "docker",
            "run",
            "--rm",
            "-e",
            f"PGPASSWORD={db['password']}",
            image,
            "pg_dump",
            "-h",
            db["host"],
            "-p",
            db["port"],
            "-U",
            db["user"],
            "-d",
            db["database"],
            "--no-owner",
            "--no-privileges",
        ]
        if db["host"] in {"localhost", "127.0.0.1"}:
            cmd = [
                "docker",
                "run",
                "--rm",
                "--network=host",
                "-e",
                f"PGPASSWORD={db['password']}",
                image,
                "pg_dump",
                "-h",
                db["host"],
                "-p",
                db["port"],
                "-U",
                db["user"],
                "-d",
                db["database"],
                "--no-owner",
                "--no-privileges",
            ]
        proc = subprocess.run(cmd, capture_output=True, check=False)
        if proc.returncode != 0:
            raise SystemExit(f"pg_dump via {image} failed: {proc.stderr.decode(errors='replace')}")
        dest.write_bytes(proc.stdout)
    finally:
        Path(password_path).unlink(missing_ok=True)


def copy_local_object(storage_root: Path, storage_key: str, dest: Path) -> int:
    if not is_canonical_storage_key(storage_key):
        raise ValueError(f"refusing unsafe storage key {storage_key!r}")
    source = (storage_root / storage_key).resolve()
    if not str(source).startswith(str(storage_root)):
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


def run_backup(target: str, output_dir: Path, quiesced: bool) -> dict[str, object]:
    if not quiesced:
        raise SystemExit("Refusing backup without --quiesced acknowledgement.")
    detect_partial_output(output_dir)
    acquire_lock(output_dir, target)
    db = parse_db_config()
    kind = storage_kind()
    writers = active_writers(db)
    if writers > 0:
        release_lock(output_dir)
        raise SystemExit(f"Active writers detected ({writers}); quiesce synthetic jobs first.")
    referenced = sorted(query_referenced_keys_psql(db))
    objects_dir = output_dir / OBJECTS_DIR
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
            release_lock(output_dir)
            raise SystemExit(str(err)) from err
    reconciliation = Reconciliation(len(referenced), len(present), missing)
    if missing:
        release_lock(output_dir)
        raise SystemExit(f"Referenced objects missing from storage: {missing}")
    dump_path = output_dir / DUMP_NAME
    run_pg_dump(db, dump_path)
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
        },
        "objects": object_entries,
        "reconciliation": reconciliation.as_dict(),
    }
    write_manifest(manifest_path(output_dir), payload)
    write_complete_marker(output_dir)
    release_lock(output_dir)
    LOGGER.info(
        "backup complete target=%s bytes=%s objects=%s",
        target,
        dump_path.stat().st_size + sum(int(v["size_bytes"]) for v in object_entries.values()),
        len(object_entries),
    )
    return payload


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
