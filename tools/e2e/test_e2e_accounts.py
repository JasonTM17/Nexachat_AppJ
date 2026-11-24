"""Offline unit tests for e2e_accounts (manifest + cleanup). No network."""

from __future__ import annotations

import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import e2e_accounts


class FakeResponse:
    def __init__(self, status: int, body=None):
        self.status = status
        self._body = body

    def json(self):
        return self._body


class FakeClient:
    """Scripted REST client: records calls, answers canned responses."""

    def __init__(self, login_statuses=(200,), delete_statuses=(204,),
                 login_body=None):
        self.login_statuses = list(login_statuses)
        self.delete_statuses = list(delete_statuses)
        self.login_body = login_body or {"accessToken": "tok-1"}
        self.calls = []

    def post(self, path, body=None, **_kwargs):
        self.calls.append(("POST", path, body))
        if path == "/api/v1/auth/login":
            status = self.login_statuses.pop(0) if self.login_statuses else 200
            return FakeResponse(status, self.login_body)
        return FakeResponse(500)

    def request(self, method, path, token=None, body=None, **_kwargs):
        self.calls.append((method, path, token, body))
        status = self.delete_statuses.pop(0) if self.delete_statuses else 204
        return FakeResponse(status)

    def delete_calls(self):
        return [c for c in self.calls if c[0] == "DELETE"]

    def login_calls(self):
        return [c for c in self.calls if c[1] == "/api/v1/auth/login"]


def _manifest(path: str, accounts: list[dict]) -> None:
    Path(path).write_text(
        json.dumps({"version": 1, "accounts": accounts}), encoding="utf-8")


def _entry(phone: str, password: str = "password123", backend: str = "http://b"):
    return {"phone": phone, "password": password, "userId": "u",
            "displayName": "n", "source": "test", "base_url": backend,
            "addedAt": "2026-11-24T00:00:00+00:00"}


class RecordTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = str(Path(self.dir.name) / "manifest.json")

    def tearDown(self):
        self.dir.cleanup()

    def test_record_writes_credentials_without_tokens(self):
        entry = e2e_accounts.record(
            "+8499xxx1", "pw-1", source="test", backend="http://b",
            user_id="u-1", display_name="n-1", path=self.path)
        self.assertIsNotNone(entry)
        raw = json.loads(Path(self.path).read_text(encoding="utf-8"))
        self.assertEqual(len(raw["accounts"]), 1)
        stored = raw["accounts"][0]
        for key in ("phone", "password", "userId", "source", "base_url", "addedAt"):
            self.assertIn(key, stored)
        self.assertNotIn("accessToken", json.dumps(raw))
        self.assertNotIn("refreshToken", json.dumps(raw))

    def test_record_upserts_same_phone_and_backend(self):
        e2e_accounts.record("+8499xxx1", "pw-1", path=self.path)
        e2e_accounts.record("+8499xxx1", "pw-2", path=self.path)
        raw = json.loads(Path(self.path).read_text(encoding="utf-8"))
        self.assertEqual(len(raw["accounts"]), 1)
        self.assertEqual(raw["accounts"][0]["password"], "pw-2")

    def test_protected_phones_are_never_recorded(self):
        for phone in ("+84900000001", "+84900000002", "+84900000003",
                      "+84900000101", "+84900000102",
                      "+84900000590", "+84900000312"):
            with self.subTest(phone=phone):
                self.assertIsNone(e2e_accounts.record(phone, "pw", path=self.path))
        self.assertEqual(e2e_accounts.load_manifest(self.path)["accounts"], [])

    def test_rotate_password_updates_only_the_matching_entry(self):
        e2e_accounts.record("+8499xxx1", "old", backend="http://b", path=self.path)
        e2e_accounts.record("+8499xxx2", "keep", backend="http://b", path=self.path)
        self.assertTrue(e2e_accounts.rotate_password(
            "+8499xxx1", "new", backend="http://b", path=self.path))
        raw = e2e_accounts.load_manifest(self.path)["accounts"]
        self.assertEqual(raw[0]["password"], "new")
        self.assertEqual(raw[1]["password"], "keep")

    def test_rotate_password_scoped_by_backend(self):
        e2e_accounts.record("+8499xxx1", "old", backend="http://a", path=self.path)
        self.assertFalse(e2e_accounts.rotate_password(
            "+8499xxx1", "new", backend="http://b", path=self.path))
        raw = e2e_accounts.load_manifest(self.path)["accounts"]
        self.assertEqual(raw[0]["password"], "old")

    def test_corrupt_manifest_loads_empty(self):
        Path(self.path).write_text("{not json", encoding="utf-8")
        self.assertEqual(e2e_accounts.load_manifest(self.path)["accounts"], [])


class CleanupTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = str(Path(self.dir.name) / "manifest.json")
        self.slept = []

    def tearDown(self):
        self.dir.cleanup()

    def _sleep(self, seconds):
        self.slept.append(seconds)

    def _cleanup(self, client, **kwargs):
        return e2e_accounts.cleanup(
            "http://b", log=lambda _msg: None, path=self.path, client=client,
            pace_seconds=0, sleep=self._sleep, **kwargs)

    def test_login_404_is_already_gone_and_pruned(self):
        _manifest(self.path, [_entry("+8499xxx1")])
        client = FakeClient(login_statuses=(404,))
        result = self._cleanup(client)
        self.assertEqual(result["counts"]["already_gone"], 1)
        self.assertEqual(e2e_accounts.load_manifest(self.path)["accounts"], [])
        self.assertEqual(client.delete_calls(), [])

    def test_login_401_is_skipped_and_kept(self):
        _manifest(self.path, [_entry("+8499xxx1")])
        result = self._cleanup(FakeClient(login_statuses=(401,)))
        self.assertEqual(result["counts"]["skipped"], 1)
        self.assertEqual(result["counts"]["failed"], 0)
        remaining = e2e_accounts.load_manifest(self.path)["accounts"]
        self.assertEqual(len(remaining), 1)

    def test_happy_path_login_then_delete_tombstones(self):
        _manifest(self.path, [_entry("+8499xxx1")])
        client = FakeClient(login_statuses=(200,), delete_statuses=(204,))
        result = self._cleanup(client)
        self.assertEqual(result["counts"]["deleted"], 1)
        self.assertEqual(len(client.delete_calls()), 1)
        method, path, token, body = client.delete_calls()[0]
        self.assertEqual((method, path), ("DELETE", "/api/v1/users/me"))
        self.assertEqual(token, "tok-1")
        self.assertEqual(body, {"password": "password123"})
        self.assertEqual(e2e_accounts.load_manifest(self.path)["accounts"], [])

    def test_protected_manifest_entry_is_never_deleted(self):
        _manifest(self.path, [_entry("+84900000001"), _entry("+84900000521"),
                              _entry("+84900000101")])
        client = FakeClient()
        result = self._cleanup(client)
        self.assertEqual(result["counts"]["skipped"], 3)
        self.assertEqual(client.delete_calls(), [])
        self.assertEqual(client.login_calls(), [])
        self.assertEqual(len(e2e_accounts.load_manifest(self.path)["accounts"]), 3)

    def test_delete_401_retries_once_then_skips(self):
        _manifest(self.path, [_entry("+8499xxx1", "stale")])
        client = FakeClient(login_statuses=(200, 200), delete_statuses=(401, 401))
        result = self._cleanup(client)
        self.assertEqual(result["counts"]["skipped"], 1)
        self.assertEqual(len(client.delete_calls()), 2)
        self.assertEqual(len(client.login_calls()), 2)
        self.assertEqual(len(e2e_accounts.load_manifest(self.path)["accounts"]), 1)

    def test_login_429_honours_retry_after(self):
        _manifest(self.path, [_entry("+8499xxx1")])
        client = FakeClient(login_statuses=(429, 200), delete_statuses=(204,))
        result = self._cleanup(client)
        self.assertEqual(result["counts"]["deleted"], 1)
        self.assertEqual(len(client.login_calls()), 2)
        self.assertEqual(len(self.slept), 1)

    def test_dry_run_never_calls_delete(self):
        _manifest(self.path, [_entry("+8499xxx1")])
        client = FakeClient()
        result = self._cleanup(client, dry_run=True)
        self.assertEqual(client.calls, [])
        self.assertEqual(result["accounts"][0]["verdict"], "PENDING")
        self.assertEqual(len(e2e_accounts.load_manifest(self.path)["accounts"]), 1)

    def test_other_backends_are_left_alone(self):
        _manifest(self.path, [_entry("+8499xxx1", backend="http://other")])
        client = FakeClient()
        result = self._cleanup(client)
        self.assertEqual(client.calls, [])
        self.assertEqual(result["accounts"], [])
        self.assertEqual(len(e2e_accounts.load_manifest(self.path)["accounts"]), 1)

    def test_rerun_on_pruned_manifest_converges(self):
        _manifest(self.path, [])
        result = self._cleanup(FakeClient())
        self.assertEqual(result["counts"],
                         {"deleted": 0, "already_gone": 0, "skipped": 0,
                          "stale_pruned": 0, "failed": 0})

    # ---- uniform-401 (anti-enumeration) stale lifecycle (Wukong r2) ----

    def test_login_401_counts_a_miss_toward_the_prune_threshold(self):
        _manifest(self.path, [_entry("+8499xxx1")])
        result = self._cleanup(FakeClient(login_statuses=(401,)))
        self.assertEqual(result["counts"]["skipped"], 1)
        remaining = e2e_accounts.load_manifest(self.path)["accounts"]
        self.assertEqual(len(remaining), 1)
        self.assertEqual(remaining[0]["failed_logins"], 1)

    def test_third_consecutive_401_prunes_the_zombie(self):
        _manifest(self.path, [_entry("+8499xxx1")])
        for expected_skips_before_prune in (1, 2):
            result = self._cleanup(FakeClient(login_statuses=(401,)))
            self.assertEqual(result["counts"]["skipped"], 1, expected_skips_before_prune)
            self.assertEqual(result["counts"]["stale_pruned"], 0)
        result = self._cleanup(FakeClient(login_statuses=(401,)))
        self.assertEqual(result["counts"]["stale_pruned"], 1)
        self.assertEqual(result["counts"]["skipped"], 0)
        self.assertEqual(e2e_accounts.load_manifest(self.path)["accounts"], [])

    def test_history_password_recovers_a_lost_rotation(self):
        entry = _entry("+8499xxx1", password="rotated-new")
        entry["password_history"] = ["old-good"]
        _manifest(self.path, [entry])
        client = FakeClient(login_statuses=(401, 200), delete_statuses=(204,))
        # The fake client answers 401 for every login call (see FakeClient.post),
        # so assert the flow differently below: the code loops history only
        # after the first 401; force scripted statuses per call instead.
        _manifest(self.path, [entry])
        scripted = _ScriptedLoginClient([("login", 401), ("login", 200)],
                                         delete_statuses=[204])
        result = self._cleanup(scripted)
        self.assertEqual(result["counts"]["deleted"], 1)
        remaining = e2e_accounts.load_manifest(self.path)["accounts"]
        self.assertEqual(remaining, [])
        # The recovered credential must have been used for the DELETE body.
        delete_bodies = [call[3] for call in scripted.calls if call[0] == "DELETE"]
        self.assertEqual(delete_bodies, [{"password": "old-good"}])

    def test_recorded_seed_phones_stay_refused_dung_included(self):
        for phone in ("+84900000004", " +84900000004 ",
                      "+84900000001.", " 84900000003", "+84900000512"):
            with self.subTest(phone=phone):
                self.assertIsNone(e2e_accounts.record(phone, "pw", path=self.path))
        self.assertEqual(e2e_accounts.load_manifest(self.path)["accounts"], [])

    def test_refuse_list_couples_to_demo_seed_sql(self):
        seed = Path(__file__).resolve().parents[2] / "demo" / "seed.sql"
        if not seed.exists():
            self.skipTest("demo/seed.sql not present")
        phones = set(re.findall(r"\+84\d{8,10}", seed.read_text(encoding="utf-8")))
        self.assertTrue(phones, "seed.sql yields no phones - coupling test is blind")
        seeds = {p for p in phones if p.startswith("+8490000000")}
        self.assertIn("+84900000004", seeds, "Dung must exist in seed.sql")
        for phone in phones:
            with self.subTest(phone=phone):
                self.assertTrue(e2e_accounts.is_protected(phone),
                                f"seed phone {phone} is not protected")

    def test_concurrent_record_survives_cleanup_write(self):
        _manifest(self.path, [_entry("+8499xxx1")])

        class ConcurrentClient(FakeClient):
            def post(self, path, body=None, **_kwargs):
                e2e_accounts.record("+8499new", "fresh", backend="http://b",
                                    path=self.manifest_path)
                return super().post(path, body, **_kwargs)

        client = ConcurrentClient(login_statuses=(200,), delete_statuses=(204,))
        client.manifest_path = self.path
        result = self._cleanup(client)
        self.assertEqual(result["counts"]["deleted"], 1)
        remaining = e2e_accounts.load_manifest(self.path)["accounts"]
        self.assertEqual([entry["phone"] for entry in remaining], ["+8499new"])

    def test_cleanup_warns_when_no_entry_matches_the_backend(self):
        _manifest(self.path, [_entry("+8499xxx1", backend="http://other")])
        result = self._cleanup(FakeClient())
        self.assertTrue(result["warning"])
        self.assertTrue(result["warning"].startswith("cleanup matched no manifest"))
        self.assertEqual(len(e2e_accounts.load_manifest(self.path)["accounts"]), 1)


class _ScriptedLoginClient:
    """Login script keyed per call, so 401-then-history retry is testable."""

    def __init__(self, login_script, delete_statuses=(204,)):
        self.login_script = list(login_script)
        self.delete_statuses = list(delete_statuses)
        self.calls = []

    def post(self, path, body=None, **_kwargs):
        self.calls.append(("POST", path, body))
        if path == "/api/v1/auth/login":
            kind, status = self.login_script.pop(0)
            return FakeResponse(status, {"accessToken": f"tok-{len(self.calls)}"})
        return FakeResponse(500)

    def request(self, method, path, token=None, body=None, **_kwargs):
        self.calls.append((method, path, token, body))
        status = self.delete_statuses.pop(0) if self.delete_statuses else 204
        return FakeResponse(status)

    def test_pace_sleeps_between_logins(self):
        _manifest(self.path, [_entry("+8499xxx1"), _entry("+8499xxx2")])
        client = FakeClient(login_statuses=(200, 200), delete_statuses=(204, 204))
        e2e_accounts.cleanup(
            "http://b", log=lambda _msg: None, path=self.path, client=client,
            pace_seconds=1.0, sleep=self._sleep)
        self.assertEqual(len(client.login_calls()), 2)
        self.assertEqual(len(self.slept), 1)
        self.assertGreaterEqual(self.slept[0], 0)


if __name__ == "__main__":
    unittest.main()
