"""Unit tests for paired backup capture."""

from __future__ import annotations

import io
import json
import logging
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

import backup  # noqa: E402
import manifest  # noqa: E402


class ManifestHelpersTest(unittest.TestCase):
    def test_storage_key_from_avatar_url(self) -> None:
        url = "/api/v1/files/avatars/550e8400-e29b-41d4-a716-446655440000.png"
        self.assertEqual(
            manifest.storage_key_from_avatar_url(url),
            "avatars/550e8400-e29b-41d4-a716-446655440000.png",
        )

    def test_rejects_traversal_keys(self) -> None:
        self.assertFalse(manifest.is_canonical_storage_key("../etc/passwd"))
        self.assertFalse(manifest.is_canonical_storage_key("attachments/../secret.txt"))

    def test_mismatched_manifest_version(self) -> None:
        with self.assertRaises(ValueError):
            manifest.assert_manifest_version({"format_version": 99})


class BackupNegativeTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.output = Path(self._tmp.name)

    def test_requires_quiesced_flag(self) -> None:
        with self.assertRaises(SystemExit):
            backup.run_backup("fixture", self.output, quiesced=False)

    def test_detects_interrupted_capture(self) -> None:
        manifest.write_manifest(manifest.manifest_path(self.output), {"format_version": 1})
        with self.assertRaises(SystemExit):
            backup.run_backup("fixture", self.output, quiesced=True)

    def test_refuses_concurrent_lock(self) -> None:
        manifest.lock_path(self.output).parent.mkdir(parents=True, exist_ok=True)
        manifest.lock_path(self.output).write_text("other\n", encoding="utf-8")
        with self.assertRaises(SystemExit):
            backup.run_backup("fixture", self.output, quiesced=True)

    def test_traversal_key_blocked_on_copy(self) -> None:
        root = Path(self._tmp.name) / "storage"
        root.mkdir()
        dest = Path(self._tmp.name) / "out"
        with self.assertRaises(ValueError):
            backup.copy_local_object(root, "../escape", dest)

    def test_missing_referenced_object_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "backup"
            storage = Path(tmp) / "uploads"
            storage.mkdir()
            key = "attachments/33333333-3333-3333-3333-333333333333.txt"
            env = {
                "BACKUP_DB_URL": "jdbc:postgresql://localhost:5433/nexachat_v12_backup_src",
                "BACKUP_DB_USER": "nexachat",
                "BACKUP_DB_PASSWORD": "local-only",
                "NEXACHAT_STORAGE_PROVIDER": "local",
                "NEXACHAT_STORAGE_ROOT": str(storage),
            }
            with mock.patch.dict(os.environ, env, clear=False):
                with mock.patch.object(backup, "active_writers", return_value=0):
                    with mock.patch.object(backup, "query_referenced_keys_psql", return_value={key}):
                        with self.assertRaises(SystemExit) as ctx:
                            backup.run_backup("fixture", output, quiesced=True)
                        self.assertIn("missing", str(ctx.exception).lower())

    def test_active_writers_block_capture(self) -> None:
        with mock.patch.dict(
            os.environ,
            {
                "BACKUP_DB_URL": "jdbc:postgresql://localhost:5433/nexachat_v12_backup_src",
                "BACKUP_DB_USER": "nexachat",
                "BACKUP_DB_PASSWORD": "local-only",
            },
            clear=False,
        ):
            with mock.patch.object(backup, "active_writers", return_value=2):
                with self.assertRaises(SystemExit) as ctx:
                    backup.run_backup("fixture", self.output, quiesced=True)
                self.assertIn("Active writers", str(ctx.exception))

    def test_secret_redacting_formatter(self) -> None:
        formatter = manifest.SecretRedactingFormatter(["s3cr3t-pass"])
        record = logging.LogRecord("x", logging.INFO, "", 0, "connected password=s3cr3t-pass", (), None)
        with self.assertRaises(RuntimeError):
            formatter.format(record)

    def test_pg_dump_argv_never_embeds_password(self) -> None:
        db = {
            "host": "127.0.0.1",
            "port": "5433",
            "database": "nexachat_v12_backup_src",
            "user": "nexachat",
            "password": "never-on-argv-pw",
        }
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp) / "dump.sql"
            env = {"BACKUP_PG_CONTAINER": ""}
            with mock.patch.dict(os.environ, env, clear=False):
                os.environ.pop("BACKUP_PG_CONTAINER", None)
                with mock.patch("subprocess.run") as run_mock:
                    run_mock.return_value = mock.Mock(returncode=0, stdout=b"dump", stderr=b"")
                    backup.run_pg_dump(db, dest)
            args, kwargs = run_mock.call_args
            cmd = args[0]
            run_env = kwargs.get("env") or {}
            self.assertEqual(run_env.get("PGPASSWORD"), "never-on-argv-pw")
            for token in cmd:
                self.assertNotIn("never-on-argv-pw", token)


class BackupCaptureTest(unittest.TestCase):
    def test_successful_capture_writes_complete_last(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "backup"
            storage = Path(tmp) / "uploads"
            key_dir = storage / "attachments"
            key_dir.mkdir(parents=True)
            key = "attachments/11111111-1111-1111-1111-111111111111.txt"
            payload_bytes = b"hello-backup"
            (storage / key).write_bytes(payload_bytes)
            dump = b"-- synthetic dump\n"
            env = {
                "BACKUP_DB_URL": "jdbc:postgresql://localhost:5433/nexachat_v12_backup_src",
                "BACKUP_DB_USER": "nexachat",
                "BACKUP_DB_PASSWORD": "local-only",
                "NEXACHAT_STORAGE_PROVIDER": "local",
                "NEXACHAT_STORAGE_ROOT": str(storage),
                "BACKUP_PG_CONTAINER": "fake-db",
            }
            with mock.patch.dict(os.environ, env, clear=False):
                with mock.patch.object(backup, "active_writers", return_value=0):
                    with mock.patch.object(backup, "query_referenced_keys_psql", return_value={key}):
                        with mock.patch.object(backup, "run_pg_dump", side_effect=lambda _db, dest: dest.write_bytes(dump)):
                            with mock.patch("subprocess.run") as run_mock:
                                run_mock.return_value = mock.Mock(returncode=0, stdout="", stderr="")
                                backup.run_backup("fixture", output, quiesced=True)
            self.assertTrue(manifest.complete_marker_path(output).is_file())
            self.assertTrue(manifest.manifest_path(output).is_file())
            data = json.loads(manifest.manifest_path(output).read_text(encoding="utf-8"))
            self.assertEqual(data["reconciliation"]["referenced"], 1)
            self.assertEqual(data["reconciliation"]["present"], 1)
            obj_path = output / manifest.OBJECTS_DIR / backup.object_backup_name(key)
            self.assertEqual(manifest.sha256_bytes(payload_bytes), manifest.sha256_file(obj_path))


if __name__ == "__main__":
    unittest.main()
