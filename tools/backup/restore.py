#!/usr/bin/env python3
"""Restore a paired NexaChat backup into an empty local fixture target."""

from __future__ import annotations

import argparse
import logging
import os
import shutil
import subprocess
import sys
from pathlib import Path

from manifest import (
    DUMP_NAME,
    OBJECTS_DIR,
    assert_complete_backup,
    assert_manifest_version,
    configure_logging,
    is_canonical_storage_key,
    load_manifest,
    manifest_path,
    sha256_file,
)

LOGGER = logging.getLogger(__name__)

ALLOWED_DB_PREFIX = "nexachat_restore_"


def parse_db_target(target: str) -> str:
    """Target label must embed an allowlisted database name."""
    if not target.startswith(ALLOWED_DB_PREFIX):
        raise SystemExit(
            f"Restore target {target!r} must use database prefix {ALLOWED_DB_PREFIX!r} on localhost."
        )
    return target


def restore_db_host() -> tuple[str, str]:
    host = os.environ.get("RESTORE_DB_HOST", "localhost")
    port = os.environ.get("RESTORE_DB_PORT", "5433")
    if host not in {"localhost", "127.0.0.1"}:
        raise SystemExit("Restore is limited to localhost databases.")
    return host, port


def read_db_password() -> tuple[str, str]:
    user = os.environ.get("RESTORE_DB_USER") or os.environ.get("DATABASE_USER")
    password = os.environ.get("RESTORE_DB_PASSWORD")
    if not password:
        file_path = os.environ.get("RESTORE_DB_PASSWORD_FILE") or os.environ.get("DATABASE_PASSWORD_FILE")
        if file_path:
            password = Path(file_path).read_text(encoding="utf-8").strip()
    if not user or not password:
        raise SystemExit("RESTORE_DB_USER and password env/file are required.")
    return user, password


def assert_empty_database(db_name: str, host: str, port: str, user: str, password: str) -> None:
    container = os.environ.get("BACKUP_PG_CONTAINER", "nexachat-db-1")
    sql = (
        "SELECT count(*)::text FROM pg_tables "
        "WHERE schemaname NOT IN ('pg_catalog','information_schema');"
    )
    proc = subprocess.run(
        ["docker", "exec", container, "psql", "-U", user, "-d", db_name, "-At", "-c", sql],
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        raise SystemExit(f"Cannot inspect restore database {db_name}: {proc.stderr.strip()}")
    if int((proc.stdout or "0").strip()) > 0:
        raise SystemExit(f"Restore target database {db_name!r} is not empty.")


def assert_empty_storage(storage_root: Path) -> None:
    storage_root = storage_root.expanduser().resolve()
    if storage_root.exists():
        if any(storage_root.iterdir()):
            raise SystemExit(f"Storage root {storage_root} must be empty before restore.")
    else:
        storage_root.mkdir(parents=True, exist_ok=True)


def verify_manifest_hashes(backup_dir: Path, payload: dict) -> None:
    dump = backup_dir / payload["database"]["dump_file"]
    expected = payload["database"]["sha256"]
    actual = sha256_file(dump)
    if actual != expected:
        raise SystemExit("Database dump hash mismatch.")
    objects_dir = backup_dir / OBJECTS_DIR
    for key, meta in payload.get("objects", {}).items():
        path = objects_dir / str(meta["backup_name"])
        if sha256_file(path) != meta["sha256"]:
            raise SystemExit(f"Object hash mismatch for {key!r}.")
    reconciliation = payload.get("reconciliation") or {}
    missing = reconciliation.get("missing") or []
    if missing:
        raise SystemExit(f"Manifest records missing references: {missing}")
    if reconciliation.get("referenced") != reconciliation.get("present"):
        raise SystemExit("Manifest reconciliation is not complete.")


def restore_database(db_name: str, backup_dir: Path, payload: dict, user: str, password: str) -> None:
    container = os.environ.get("BACKUP_PG_CONTAINER", "nexachat-db-1")
    dump = backup_dir / payload["database"]["dump_file"]
    proc = subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-U", user, "-d", db_name, "-v", "ON_ERROR_STOP=1"],
        input=dump.read_bytes(),
        capture_output=True,
        check=False,
    )
    if proc.returncode != 0:
        raise SystemExit(f"Database restore failed: {proc.stderr.decode(errors='replace')}")


def restore_objects(backup_dir: Path, payload: dict, storage_root: Path) -> None:
    objects_dir = backup_dir / OBJECTS_DIR
    for key, meta in payload.get("objects", {}).items():
        if not is_canonical_storage_key(key):
            raise SystemExit(f"Manifest contains unsafe key {key!r}")
        source = objects_dir / str(meta["backup_name"])
        dest = (storage_root / key).resolve()
        if not str(dest).startswith(str(storage_root.resolve())):
            raise SystemExit(f"Traversal blocked for {key!r}")
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)


def run_restore(
    manifest_arg: Path,
    target: str,
    require_empty: bool,
    verify_only: bool,
) -> None:
    backup_dir = manifest_arg.parent.resolve()
    assert_complete_backup(backup_dir)
    payload = load_manifest(manifest_path(backup_dir))
    try:
        assert_manifest_version(payload)
    except ValueError as err:
        raise SystemExit(str(err)) from err
    db_name = parse_db_target(target)
    host, port = restore_db_host()
    user, password = read_db_password()
    storage_root = Path(
        os.environ.get("RESTORE_STORAGE_ROOT") or os.environ.get("NEXACHAT_STORAGE_ROOT") or "./data/uploads"
    ).expanduser()
    verify_manifest_hashes(backup_dir, payload)
    if verify_only:
        LOGGER.info("verify-only OK for %s", backup_dir)
        return
    if require_empty:
        assert_empty_database(db_name, host, port, user, password)
        assert_empty_storage(storage_root)
    restore_database(db_name, backup_dir, payload, user, password)
    restore_objects(backup_dir, payload, storage_root)
    LOGGER.info("restore complete db=%s objects=%s", db_name, len(payload.get("objects", {})))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="NexaChat paired backup restore (local fixture)")
    parser.add_argument("--manifest", required=True, type=Path, help="Path to manifest.json")
    parser.add_argument("--target", required=True, help="Allowlisted restore DB name (nexachat_restore_*)")
    parser.add_argument("--require-empty", action="store_true", help="Require empty DB and storage")
    parser.add_argument("--verify", action="store_true", help="Verify hashes only; do not import")
    return parser


def main(argv: list[str] | None = None) -> int:
    configure_logging()
    args = build_parser().parse_args(argv)
    run_restore(args.manifest, args.target, args.require_empty, args.verify)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
