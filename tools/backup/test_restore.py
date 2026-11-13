"""Unit tests for paired backup restore."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

import manifest  # noqa: E402
import restore  # noqa: E402


def _write_backup(
    directory: Path,
    *,
    complete: bool = True,
    version: int = manifest.FORMAT_VERSION,
    corrupt_object: bool = False,
    missing_object: bool = False,
) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    dump = directory / manifest.DUMP_NAME
    dump.write_bytes(b"SELECT 1;\n")
    dump_sha = manifest.sha256_file(dump)
    key = "attachments/22222222-2222-2222-2222-222222222222.txt"
    rel = key.replace("/", "__")
    obj_dir = directory / manifest.OBJECTS_DIR
    obj_dir.mkdir()
    obj_path = obj_dir / rel
    if not missing_object:
        obj_path.write_bytes(b"payload")
    payload = {
        "format_version": version,
        "target": "fixture",
        "database": {"dump_file": manifest.DUMP_NAME, "sha256": dump_sha},
        "objects": {},
        "reconciliation": {"referenced": 0 if missing_object else 1, "present": 0, "missing": [key] if missing_object else []},
    }
    if not missing_object:
        sha = manifest.sha256_file(obj_path)
        if corrupt_object:
            sha = "0" * 64
        payload["objects"][key] = {"backup_name": rel, "sha256": sha, "size_bytes": obj_path.stat().st_size}
        payload["reconciliation"]["present"] = 1
        payload["reconciliation"]["missing"] = []
    manifest.write_manifest(manifest.manifest_path(directory), payload)
    if complete:
        manifest.write_complete_marker(directory)
    return manifest.manifest_path(directory)


class RestoreNegativeTest(unittest.TestCase):
    def test_restore_psql_argv_never_embeds_password(self) -> None:
        secret = "restore-secret-never-on-argv"
        cmd = restore.build_restore_psql_argv("nexachat-db-1", "nexachat", "nexachat_restore_v12")
        for token in cmd:
            self.assertNotIn(secret, token)

    def test_requires_restore_pg_container_for_import(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest_path = _write_backup(Path(tmp) / "bundle")
            storage = Path(tmp) / "empty-storage"
            storage.mkdir()
            env = {
                "RESTORE_DB_USER": "nexachat",
                "RESTORE_STORAGE_ROOT": str(storage),
            }
            with mock.patch.dict("os.environ", env, clear=True):
                with self.assertRaises(SystemExit) as ctx:
                    restore.run_restore(manifest_path, "nexachat_restore_v12", False, False)
                self.assertIn("RESTORE_PG_CONTAINER", str(ctx.exception))

    def test_rejects_incomplete_backup(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            backup_dir = Path(tmp)
            manifest_path = _write_backup(backup_dir, complete=False)
            with self.assertRaises(ValueError):
                restore.run_restore(manifest_path, "nexachat_restore_v12", True, True)

    def test_rejects_wrong_db_prefix(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest_path = _write_backup(Path(tmp))
            with self.assertRaises(SystemExit):
                restore.run_restore(manifest_path, "production_db", True, True)

    def test_rejects_non_empty_storage(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            storage = Path(tmp) / "storage"
            storage.mkdir()
            (storage / "leftover.bin").write_bytes(b"x")
            manifest_path = _write_backup(Path(tmp) / "bundle")
            env = {
                "RESTORE_STORAGE_ROOT": str(storage),
                "RESTORE_DB_USER": "nexachat",
                "RESTORE_PG_CONTAINER": "nexachat-db-1",
            }
            with mock.patch.dict("os.environ", env, clear=False):
                with mock.patch.object(restore, "assert_empty_database"):
                    with self.assertRaises(SystemExit):
                        restore.run_restore(manifest_path, "nexachat_restore_v12", False, False)

    def test_corrupt_object_hash(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest_path = _write_backup(Path(tmp), corrupt_object=True)
            env = {"RESTORE_DB_USER": "nexachat", "RESTORE_DB_PASSWORD": "x"}
            with mock.patch.dict("os.environ", env, clear=False):
                with self.assertRaises(SystemExit):
                    restore.run_restore(manifest_path, "nexachat_restore_v12", False, True)

    def test_missing_object_in_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest_path = _write_backup(Path(tmp), missing_object=True)
            env = {"RESTORE_DB_USER": "nexachat", "RESTORE_DB_PASSWORD": "x"}
            with mock.patch.dict("os.environ", env, clear=False):
                with self.assertRaises(SystemExit):
                    restore.run_restore(manifest_path, "nexachat_restore_v12", False, True)

    def test_avatar_url_object_key_restores_to_decoded_path(self) -> None:
        avatar_id = "550e8400-e29b-41d4-a716-446655440000"
        key = f"avatars/{avatar_id}.png"
        url_key = f"/api/v1/files/{key}?v=2"
        with tempfile.TemporaryDirectory() as tmp:
            backup_dir = Path(tmp) / "bundle"
            backup_dir.mkdir()
            dump = backup_dir / manifest.DUMP_NAME
            dump.write_bytes(b"SELECT 1;\n")
            rel = key.replace("/", "__")
            obj_dir = backup_dir / manifest.OBJECTS_DIR
            obj_dir.mkdir()
            (obj_dir / rel).write_bytes(b"avatar-bytes")
            payload = {
                "format_version": manifest.FORMAT_VERSION,
                "database": {"dump_file": manifest.DUMP_NAME, "sha256": manifest.sha256_file(dump)},
                "objects": {
                    url_key: {
                        "backup_name": rel,
                        "sha256": manifest.sha256_file(obj_dir / rel),
                        "size_bytes": 12,
                    }
                },
                "reconciliation": {"referenced": 1, "present": 1, "missing": []},
            }
            manifest.write_manifest(manifest.manifest_path(backup_dir), payload)
            manifest.write_complete_marker(backup_dir)
            restored = Path(tmp) / "storage"
            restored.mkdir()
            restore.restore_objects(backup_dir, payload, restored)
            self.assertEqual((restored / key).read_bytes(), b"avatar-bytes")
            self.assertFalse((restored / url_key.lstrip("/")).exists())

    def test_mismatched_manifest_version(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest_path = _write_backup(Path(tmp), version=0)
            env = {"RESTORE_DB_USER": "nexachat", "RESTORE_DB_PASSWORD": "x"}
            with mock.patch.dict("os.environ", env, clear=False):
                with self.assertRaises(SystemExit) as ctx:
                    restore.run_restore(manifest_path, "nexachat_restore_v12", False, True)
                self.assertIn("format_version", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
