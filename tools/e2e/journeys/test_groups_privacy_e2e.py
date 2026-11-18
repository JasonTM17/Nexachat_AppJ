"""Unit tests for the pure helpers in groups_privacy_e2e.py (no live backend)."""

from __future__ import annotations

import base64
import importlib.util
import re
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path


def _load_journey():
    path = Path(__file__).resolve().parent / "groups_privacy_e2e.py"
    spec = importlib.util.spec_from_file_location("groups_privacy_e2e_under_test", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules["groups_privacy_e2e_under_test"] = module
    spec.loader.exec_module(module)
    return module


mod = _load_journey()


class FakeResponse:
    """Minimal stand-in for e2e_common.Response."""

    def __init__(self, status: int, code=None, text: str = ""):
        self.status = status
        self._code = code
        self.text = text

    def error_code(self):
        return self._code


class InstantParsingTests(unittest.TestCase):
    def test_parses_java_instant_with_six_fraction_digits(self):
        parsed = mod.parse_instant("2026-11-19T09:05:00.123456Z")
        self.assertEqual(parsed.year, 2026)
        self.assertEqual(parsed.microsecond, 123456)
        self.assertEqual(parsed.utcoffset(), timezone.utc.utcoffset(None))

    def test_parses_instant_without_fraction(self):
        parsed = mod.parse_instant("2026-11-19T09:05:00Z")
        self.assertEqual(parsed.microsecond, 0)

    def test_parses_three_fraction_digits(self):
        parsed = mod.parse_instant("2026-11-19T09:05:00.500Z")
        self.assertEqual(parsed.microsecond, 500000)

    def test_parses_explicit_offset(self):
        parsed = mod.parse_instant("2026-11-19T09:05:00+07:00")
        self.assertEqual(parsed.utcoffset().total_seconds(), 7 * 3600)

    def test_truncates_nanosecond_precision(self):
        parsed = mod.parse_instant("2026-11-19T09:05:00.123456789Z")
        self.assertEqual(parsed.microsecond, 123456)

    def test_rejects_non_instant(self):
        with self.assertRaises(ValueError):
            mod.parse_instant("19/11/2026 09:05")


class ExpiryTests(unittest.TestCase):
    def test_has_expiry(self):
        self.assertTrue(mod.has_expiry({"expiresAt": "2026-11-19T10:05:00Z"}))
        self.assertFalse(mod.has_expiry({"expiresAt": None}))
        self.assertFalse(mod.has_expiry({}))

    def test_offset_is_exact_for_the_stamped_timer(self):
        message = {
            "createdAt": "2026-11-19T09:05:00.123456Z",
            "expiresAt": "2026-11-19T10:05:00.123456Z",
        }
        self.assertEqual(mod.expiry_offset_seconds(message), 3600.0)

    def test_offset_is_none_without_both_instants(self):
        self.assertIsNone(mod.expiry_offset_seconds({"createdAt": "2026-11-19T09:05:00Z"}))
        self.assertIsNone(mod.expiry_offset_seconds({"expiresAt": "2026-11-19T09:05:00Z"}))
        self.assertIsNone(mod.expiry_offset_seconds({}))

    def test_allow_list_matches_the_documented_presets(self):
        self.assertEqual(
            mod.DISAPPEARING_ALLOWED, (0, 3600, 86400, 604800, 2592000, 7776000)
        )
        self.assertEqual(mod.DISAPPEARING_SMALLEST, 3600)
        self.assertGreater(mod.DISAPPEARING_SMALLEST, 60)


class DeviceKeyTests(unittest.TestCase):
    def test_public_key_is_32_base64url_bytes_without_padding(self):
        key = mod.device_public_key("+84900000701")
        self.assertNotIn("=", key)
        self.assertRegex(key, r"^[A-Za-z0-9_-]+$")
        self.assertEqual(len(base64.urlsafe_b64decode(key + "==")), 32)

    def test_public_key_is_deterministic_and_salt_sensitive(self):
        self.assertEqual(
            mod.device_public_key("+84900000701"),
            mod.device_public_key("+84900000701"),
        )
        self.assertNotEqual(
            mod.device_public_key("+84900000701"),
            mod.device_public_key("+84900000701", "alt"),
        )
        self.assertNotEqual(
            mod.device_public_key("+84900000701"),
            mod.device_public_key("+84900000702"),
        )

    def test_device_id_is_a_stable_uuid(self):
        first = mod.device_id_for("+84900000701")
        self.assertEqual(first, mod.device_id_for("+84900000701"))
        self.assertEqual(len(first), 36)
        self.assertNotEqual(first, mod.device_id_for("+84900000701", "throwaway"))
        self.assertNotEqual(first, mod.device_id_for("+84900000702"))

    def test_wrapped_key_matches_the_server_pattern(self):
        wrapped = mod.wrapped_key_for(mod.device_id_for("+84900000701"))
        self.assertRegex(wrapped, r"^[A-Za-z0-9_-]+$")
        self.assertLessEqual(len(wrapped), 128)

    def test_encrypted_content_carries_the_e2ee1_prefix(self):
        content = mod.encrypted_content("abc12345")
        self.assertTrue(content.startswith("e2ee1:"))
        payload = content[len("e2ee1:"):]
        self.assertNotIn("=", payload)
        self.assertEqual(len(base64.urlsafe_b64decode(payload + "==")), 32)
        self.assertNotEqual(content, mod.encrypted_content("abc12346"))

    def test_envelopes_cover_every_device_once(self):
        ids = [mod.device_id_for("+84900000701"), mod.device_id_for("+84900000707")]
        envelopes = mod.envelopes_for(ids)
        self.assertEqual([item["deviceId"] for item in envelopes], ids)
        self.assertEqual(len({item["deviceId"] for item in envelopes}), 2)
        for item in envelopes:
            self.assertRegex(item["wrappedKey"], r"^[A-Za-z0-9_-]+$")


class AssertErrorTests(unittest.TestCase):
    def test_accepts_the_documented_status_and_code(self):
        mod.assert_error(FakeResponse(403, "NOT_ADMIN"), 403, "NOT_ADMIN")

    def test_rejects_a_wrong_status(self):
        with self.assertRaises(mod.E2EFailure):
            mod.assert_error(FakeResponse(400, "NOT_ADMIN"), 403, "NOT_ADMIN")

    def test_rejects_a_wrong_code(self):
        with self.assertRaises(mod.E2EFailure):
            mod.assert_error(FakeResponse(403, "NOT_MEMBER"), 403, "NOT_ADMIN")

    def test_rejects_a_missing_code(self):
        with self.assertRaises(mod.E2EFailure):
            mod.assert_error(FakeResponse(403, None), 403, "NOT_ADMIN")


class PayloadViewTests(unittest.TestCase):
    def test_member_roles_and_ids(self):
        payload = {
            "members": [
                {"userId": "u1", "role": "ADMIN"},
                {"userId": "u2", "role": "MEMBER"},
            ]
        }
        self.assertEqual(mod.member_roles(payload), {"u1": "ADMIN", "u2": "MEMBER"})
        self.assertEqual(mod.member_ids(payload), ["u1", "u2"])

    def test_member_views_tolerate_a_missing_list(self):
        self.assertEqual(mod.member_roles({}), {})
        self.assertEqual(mod.member_ids({"members": None}), [])

    def test_page_helpers(self):
        page = {
            "items": [
                {"id": "m1", "content": "PREJOINabc before"},
                {"id": "m2", "content": None},
            ]
        }
        self.assertEqual(mod.page_contents(page), ["PREJOINabc before", ""])
        self.assertTrue(mod.page_contains(page, "PREJOINabc"))
        self.assertFalse(mod.page_contains(page, "POSTJOINabc"))
        self.assertEqual(mod.page_ids(page), {"m1", "m2"})
        self.assertEqual(mod.find_message(page, "m2")["id"], "m2")
        self.assertIsNone(mod.find_message(page, "m3"))

    def test_page_helpers_tolerate_an_empty_page(self):
        self.assertEqual(mod.page_contents({}), [])
        self.assertFalse(mod.page_contains({"items": []}, "x"))
        self.assertEqual(mod.page_ids({"items": None}), set())

    def test_inbox_entry_lookup(self):
        payload = [{"id": "c1"}, {"id": "c2"}]
        self.assertEqual(mod.inbox_entry(payload, "c2")["id"], "c2")
        self.assertIsNone(mod.inbox_entry(payload, "c3"))
        self.assertIsNone(mod.inbox_entry(None, "c1"))

    def test_blocked_and_contact_id_sets(self):
        self.assertEqual(
            mod.blocked_user_ids({"items": [{"userId": "u1"}, {"userId": "u2"}]}),
            {"u1", "u2"},
        )
        self.assertEqual(mod.blocked_user_ids({}), set())
        self.assertEqual(
            mod.contact_user_ids([{"contactUserId": "u1"}, {"contactUserId": "u2"}]),
            {"u1", "u2"},
        )
        self.assertEqual(mod.contact_user_ids(None), set())

    def test_device_ids_of(self):
        state = {"devices": [{"deviceId": "d1"}, {"deviceId": "d2"}]}
        self.assertEqual(mod.device_ids_of(state), ["d1", "d2"])
        self.assertEqual(mod.device_ids_of({}), [])


class ScenarioSelectionTests(unittest.TestCase):
    NAMES = ("a", "b", "c")

    def test_no_only_returns_every_scenario_in_order(self):
        self.assertEqual(mod.select_scenarios(self.NAMES, None), ["a", "b", "c"])
        self.assertEqual(mod.select_scenarios(self.NAMES, ""), ["a", "b", "c"])

    def test_only_keeps_the_declared_order(self):
        self.assertEqual(mod.select_scenarios(self.NAMES, "c,a"), ["a", "c"])

    def test_only_ignores_blank_entries(self):
        self.assertEqual(mod.select_scenarios(self.NAMES, " b , "), ["b"])

    def test_unknown_name_raises(self):
        with self.assertRaises(ValueError) as raised:
            mod.select_scenarios(self.NAMES, "a,zzz")
        self.assertIn("zzz", str(raised.exception))

    def test_declared_scenarios_cover_the_brief(self):
        expected = {
            "group_lifecycle",
            "rejoin_visibility",
            "disappearing_messages",
            "disappearing_expiry",
            "contacts_and_blocks",
            "e2ee_devices",
            "e2ee_activation",
        }
        self.assertEqual(set(mod.SCENARIO_NAMES), expected)
        self.assertEqual(len(mod.SCENARIO_NAMES), len(expected))


class ArgParsingTests(unittest.TestCase):
    def test_defaults(self):
        args = mod.parse_args([])
        self.assertEqual(args.base_url, mod.DEFAULT_BASE_URL)
        self.assertFalse(args.list)
        self.assertIsNone(args.only)

    def test_base_url_override(self):
        args = mod.parse_args(["--base-url", "http://127.0.0.1:9999"])
        self.assertEqual(args.base_url, "http://127.0.0.1:9999")

    def test_list_and_only_flags(self):
        args = mod.parse_args(["--list", "--only", "group_lifecycle"])
        self.assertTrue(args.list)
        self.assertEqual(args.only, "group_lifecycle")

    def test_default_port_is_the_assigned_backend(self):
        self.assertTrue(mod.DEFAULT_BASE_URL.endswith(":8093"))


class FixtureTests(unittest.TestCase):
    def test_run_nonce_is_short_hex(self):
        nonce = mod.run_nonce()
        self.assertRegex(nonce, r"^[0-9a-f]{8}$")
        self.assertNotEqual(nonce, mod.run_nonce())

    def test_fixture_phones_stay_in_the_assigned_range(self):
        pattern = re.compile(r"^\+849000007\d{2}$")
        for phone, _name in mod.ACCOUNTS.values():
            self.assertRegex(phone, pattern)
        self.assertRegex(mod.PHONE_UNKNOWN, pattern)
        self.assertNotIn(mod.PHONE_UNKNOWN, [phone for phone, _ in mod.ACCOUNTS.values()])

    def test_display_names_carry_the_journey_tag(self):
        for _phone, name in mod.ACCOUNTS.values():
            self.assertTrue(name.startswith(mod.TAG))

    def test_account_count_stays_within_the_login_budget(self):
        self.assertLessEqual(len(mod.ACCOUNTS), 8)
        self.assertEqual(len(set(mod.ACCOUNTS)), len(mod.ACCOUNTS))


if __name__ == "__main__":
    unittest.main()
