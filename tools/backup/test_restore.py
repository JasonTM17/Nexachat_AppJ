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
            env = {"RESTORE_STORAGE_ROOT": str(storage), "RESTORE_DB_USER": "nexachat", "RESTORE_DB_PASSWORD": "x"}
            with mock.patch.dict("os.environ", env, clear=False):
                with mock.patch.object(restore, "assert_empty_database"):
                    with self.assertRaises(SystemExit):
                        restore.run_restore(manifest_path, "nexachat_restore_v12", True, False)

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
