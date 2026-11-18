"""Offline tests for the pure helpers of admin_streak_e2e.py (no network)."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import admin_streak_e2e as journey  # noqa: E402


def page(items=None):
    return {"items": items or [], "page": 0, "size": 25, "total": 0, "hasMore": False}


def health(**overrides):
    payload = {
        "status": "UP", "checkedAt": "2026-01-01T00:00:00Z", "uptimeSeconds": 5,
        "services": [{"name": n, "status": "UP", "detail": "ok"}
                     for n in ("database", "flyway", "storage", "calls", "alert-pipeline")],
    }
    payload.update(overrides)
    return payload


class ArgsTest(unittest.TestCase):
    def test_defaults(self):
        args = journey.parse_args([])
        self.assertEqual(args.base_url, "http://127.0.0.1:8094")
        self.assertFalse(args.list)
        self.assertEqual(args.only, "")

    def test_flags(self):
        args = journey.parse_args(["--base-url", "http://x:1", "--list", "--only", "a,b"])
        self.assertEqual((args.base_url, args.list, args.only), ("http://x:1", True, "a,b"))

    def test_select_keeps_declared_order(self):
        names = ["a", "b", "c"]
        self.assertEqual(journey.select_scenarios(names, ""), names)
        self.assertEqual(journey.select_scenarios(names, " c, a "), ["a", "c"])

    def test_select_rejects_unknown(self):
        with self.assertRaises(ValueError):
            journey.select_scenarios(["a"], "a,zzz")

    def test_scenario_names_unique(self):
        names = [name for name, _ in journey.SCENARIOS]
        self.assertEqual(len(names), len(set(names)))


class LeakTest(unittest.TestCase):
    def test_finds_nested_credentials(self):
        payload = {"user": {"id": 1, "passwordHash": "x"},
                   "list": [{"refresh-token": "y"}, {"ok": 1}]}
        self.assertEqual(journey.find_forbidden_keys(payload),
                         ["user.passwordHash", "list[0].refresh-token"])

    def test_clean_detail(self):
        detail = {"user": {"id": "u", "phone": "+84", "role": "USER", "suspendedAt": None},
                  "deviceCount": 0, "sessionCount": 1}
        self.assertEqual(journey.find_forbidden_keys(detail), [])


class ShapeTest(unittest.TestCase):
    def test_page(self):
        self.assertTrue(journey.is_page(page()))
        self.assertFalse(journey.is_page({"items": []}))
        self.assertFalse(journey.is_page(dict(page(), hasMore="no")))

    def test_validate_admin_get(self):
        v = journey.validate_admin_get
        self.assertIsNone(v("/api/v1/admin/me", {"userId": "x", "role": "ADMIN"}))
        self.assertIsNotNone(v("/api/v1/admin/me", {"userId": "x", "role": "USER"}))
        self.assertIsNone(v("/api/v1/admin/users", page()))
        self.assertIsNotNone(v("/api/v1/admin/audit", []))
        self.assertIsNone(v("/api/v1/admin/rules", []))
        self.assertIsNone(v("/api/v1/admin/overview",
                            {"kpis": {}, "days": 7, "series": [], "generatedAt": "t"}))
        self.assertIsNotNone(v("/api/v1/admin/overview", {"kpis": {}}))
        self.assertIsNotNone(v("/api/v1/admin/unknown", {}))
        for path in journey.ADMIN_GET_ENDPOINTS:
            self.assertNotEqual(v(path, None), "no validator for " + path)

    def test_system_health(self):
        self.assertIsNone(journey.validate_system_health(health()))
        self.assertIsNone(journey.validate_system_health(health(status="DEGRADED")))
        self.assertIsNotNone(journey.validate_system_health(health(status="WEIRD")))
        self.assertIsNotNone(journey.validate_system_health(health(services=[])))
        leaky = health()
        leaky["services"][0]["detail"] = "jdbc:postgresql://db password=x"
        self.assertIn("secret", journey.validate_system_health(leaky))


class StreakHelpersTest(unittest.TestCase):
    def test_pairs(self):
        pairs = journey.streak_pairs(journey.STREAK_POOL)
        self.assertEqual(len(pairs), 6)
        self.assertTrue(all(a != b for a, b in pairs))

    def test_untouched(self):
        self.assertTrue(journey.streak_untouched_today(
            {"mySentToday": False, "peerSentToday": False}))
        self.assertFalse(journey.streak_untouched_today(
            {"mySentToday": True, "peerSentToday": False}))
        self.assertFalse(journey.streak_untouched_today({}))

    def test_expected_counts(self):
        self.assertEqual(journey.expected_counts_after_completion(
            {"count": 0, "lastDay": None}), (1,))
        self.assertEqual(journey.expected_counts_after_completion(
            {"count": 4, "lastDay": "2026-01-01"}), (1, 5))

    def test_audit_has(self):
        entries = [{"action": "USER_SUSPEND", "targetId": "t", "reason": "x nonce1"}]
        self.assertTrue(journey.audit_has(entries, "USER_SUSPEND", "t"))
        self.assertTrue(journey.audit_has(entries, "USER_SUSPEND", "t", "nonce1"))
        self.assertFalse(journey.audit_has(entries, "USER_SUSPEND", "t", "nonce2"))
        self.assertFalse(journey.audit_has(entries, "USER_UNSUSPEND", "t"))

    def test_fixture_phones_in_range(self):
        phones = [journey.ADMIN_PHONE, journey.PLAIN_PHONE, journey.TARGET_PHONE,
                  *journey.STREAK_POOL]
        self.assertEqual(len(set(phones)), len(phones))
        self.assertTrue(all(p.startswith("+849000008") for p in phones))
        self.assertTrue(all(journey.NAMES[p].startswith("Admin E2E") for p in phones))


if __name__ == "__main__":
    unittest.main()
