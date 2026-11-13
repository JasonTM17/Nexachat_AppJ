#!/usr/bin/env python3
"""Restore a paired NexaChat backup into an empty local fixture target."""

from __future__ import annotations

import argparse
import logging
import os
import shutil
import subprocess
import sys
import warnings
from pathlib import Path

from manifest import (
    DUMP_NAME,
    OBJECTS_DIR,
    assert_complete_backup,
    assert_manifest_version,
    configure_logging,
    canonical_storage_reference,
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


def restore_pg_container() -> str:
    container = os.environ.get("RESTORE_PG_CONTAINER")
    if not container:
        raise SystemExit("Set RESTORE_PG_CONTAINER for local database restore.")
    return container


def read_db_user() -> str:
    user = os.environ.get("RESTORE_DB_USER") or os.environ.get("DATABASE_USER")
    if not user:
        raise SystemExit("RESTORE_DB_USER is required.")
    return user


def build_restore_psql_argv(container: str, user: str, db_name: str) -> list[str]:
    return [
        "docker",
        "exec",
        "-i",
        container,
        "psql",
        "-U",
        user,
        "-d",
        db_name,
        "-v",
        "ON_ERROR_STOP=1",
    ]


def assert_empty_database(db_name: str, user: str) -> None:
    container = restore_pg_container()
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


def restore_database(db_name: str, backup_dir: Path, payload: dict, user: str) -> None:
    container = restore_pg_container()
    dump = backup_dir / payload["database"]["dump_file"]
    cmd = build_restore_psql_argv(container, user, db_name)
    with dump.open("rb") as dump_handle:
        proc = subprocess.run(
            cmd,
            stdin=dump_handle,
            capture_output=True,
            check=False,
        )
    if proc.returncode != 0:
        raise SystemExit(f"Database restore failed: {proc.stderr.decode(errors='replace')}")


def restore_objects(backup_dir: Path, payload: dict, storage_root: Path) -> None:
    objects_dir = backup_dir / OBJECTS_DIR
    root = storage_root.expanduser().resolve()
    for raw_key, meta in payload.get("objects", {}).items():
        key = canonical_storage_reference(str(raw_key))
        if not is_canonical_storage_key(key):
            raise SystemExit(f"Unresolved local storage reference {raw_key!r}")
        source = objects_dir / str(meta["backup_name"])
        dest = (root / key).resolve()
        if not dest.is_relative_to(root):
            raise SystemExit(f"Traversal blocked for {key!r}")
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)


def run_restore(
    manifest_arg: Path,
    target: str,
    require_empty: bool,
    verify_only: bool,
) -> None:
    if require_empty:
        warnings.warn(
            "--require-empty is always enforced and the flag is ignored",
            DeprecationWarning,
            stacklevel=2,
        )
    backup_dir = manifest_arg.parent.resolve()
    assert_complete_backup(backup_dir)
    payload = load_manifest(manifest_path(backup_dir))
    try:
        assert_manifest_version(payload)
    except ValueError as err:
        raise SystemExit(str(err)) from err
    db_name = parse_db_target(target)
    user = read_db_user()
    storage_root = Path(
        os.environ.get("RESTORE_STORAGE_ROOT") or os.environ.get("NEXACHAT_STORAGE_ROOT") or "./data/uploads"
    )
    verify_manifest_hashes(backup_dir, payload)
    if verify_only:
        LOGGER.info("verify-only OK for %s", backup_dir)
        return
    assert_empty_database(db_name, user)
    assert_empty_storage(storage_root)
    restore_database(db_name, backup_dir, payload, user)
    restore_objects(backup_dir, payload, storage_root)
    LOGGER.info("restore complete db=%s objects=%s", db_name, len(payload.get("objects", {})))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="NexaChat paired backup restore (local fixture)")
    parser.add_argument("--manifest", required=True, type=Path, help="Path to manifest.json")
    parser.add_argument("--target", required=True, help="Allowlisted restore DB name (nexachat_restore_*)")
    parser.add_argument(
        "--require-empty",
        action="store_true",
        help="Deprecated: restore always requires an empty DB and storage dir",
    )
    parser.add_argument("--verify", action="store_true", help="Verify hashes only; do not import")
    return parser


def main(argv: list[str] | None = None) -> int:
    configure_logging()
    args = build_parser().parse_args(argv)
    run_restore(args.manifest, args.target, args.require_empty, args.verify)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
