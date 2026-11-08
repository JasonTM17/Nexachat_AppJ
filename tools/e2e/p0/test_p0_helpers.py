"""Unit tests for pure P0 E2E helper functions.

No network access required; runs purely offline with Python stdlib unittest.
Usage:
    python -m unittest tools/e2e/p0/test_p0_helpers.py
"""

from __future__ import annotations

import unittest
from pathlib import Path
import sys

# Ensure tools/e2e/p0 is in path
sys.path.insert(0, str(Path(__file__).resolve().parent))

from p0_helpers import (
    build_stomp_frame,
    check_monotonic_watermark,
    clamp_watermark,
    generate_test_password,
    generate_test_phone,
    mask_id,
    mask_password,
    mask_phone,
    mask_token,
    parse_stomp_buffer,
    parse_stomp_frame,
    verify_monotonic_sequence,
    verify_no_duplicates,
)


class MaskingHelpersTest(unittest.TestCase):
    def test_mask_phone_vietnamese_mobile(self):
        self.assertEqual(mask_phone("+84908123456"), "+84908***456")
        self.assertEqual(mask_phone("+84908999888"), "+84908***888")

    def test_mask_phone_generic_format(self):
        self.assertEqual(mask_phone("0908123456"), "0908***456")

    def test_mask_phone_short_or_empty(self):
        self.assertEqual(mask_phone("1234"), "***")
        self.assertEqual(mask_phone(""), "")
        self.assertEqual(mask_phone(None), "")

    def test_mask_token_redacts_completely(self):
        sample_jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig"
        self.assertEqual(mask_token(sample_jwt), "[REDACTED_TOKEN]")
        self.assertEqual(mask_token("short-tok"), "[REDACTED_TOKEN]")
        self.assertEqual(mask_token(""), "[EMPTY]")
        self.assertEqual(mask_token(None), "[EMPTY]")

    def test_mask_password_redacts_completely(self):
        self.assertEqual(mask_password("secret_password_123!"), "[REDACTED_PASSWORD]")
        self.assertEqual(mask_password(""), "[EMPTY]")
        self.assertEqual(mask_password(None), "[EMPTY]")

    def test_mask_id_formats(self):
        sample_uuid = "11112222-3333-4444-5555-666677778888"
        self.assertEqual(mask_id(sample_uuid), "1111...8888")
        self.assertEqual(mask_id("12345"), "***")
        self.assertEqual(mask_id(""), "")
        self.assertEqual(mask_id(None), "")


class StompFrameBuildParseTest(unittest.TestCase):
    def test_build_connect_frame(self):
        frame = build_stomp_frame(
            "CONNECT",
            {"accept-version": "1.2", "heart-beat": "0,0", "Authorization": "Bearer tok"},
        )
        self.assertTrue(frame.startswith("CONNECT\n"))
        self.assertIn("accept-version:1.2\n", frame)
        self.assertIn("heart-beat:0,0\n", frame)
        self.assertIn("Authorization:Bearer tok\n", frame)
        self.assertTrue(frame.endswith("\n\n\x00"))

    def test_build_send_frame_with_body(self):
        body = '{"content":"hello"}'
        frame = build_stomp_frame(
            "SEND",
            {"destination": "/app/test", "content-type": "application/json"},
            body=body,
        )
        self.assertTrue(frame.startswith("SEND\n"))
        self.assertIn("destination:/app/test\n", frame)
        self.assertTrue(frame.endswith(f"\n\n{body}\x00"))

    def test_parse_single_frame(self):
        raw = "CONNECTED\nversion:1.2\nuser-name:test-user\n\n\x00"
        cmd, headers, body = parse_stomp_frame(raw)
        self.assertEqual(cmd, "CONNECTED")
        self.assertEqual(headers.get("version"), "1.2")
        self.assertEqual(headers.get("user-name"), "test-user")
        self.assertEqual(body, "")

    def test_parse_frame_with_body(self):
        raw = 'MESSAGE\ndestination:/topic/test\n\n{"seq":42}\x00'
        cmd, headers, body = parse_stomp_frame(raw)
        self.assertEqual(cmd, "MESSAGE")
        self.assertEqual(headers.get("destination"), "/topic/test")
        self.assertEqual(body, '{"seq":42}')

    def test_parse_buffer_multiple_and_partial(self):
        f1 = "CONNECTED\nversion:1.2\n\n\x00"
        f2 = 'MESSAGE\ndestination:/topic/a\n\n{"id":1}\x00'
        partial = "MESSAGE\ndestination:/topic/b\n\n"
        buffer = f1 + "\n\n" + f2 + partial  # includes heartbeat newlines between frames

        frames, remaining = parse_stomp_buffer(buffer)
        self.assertEqual(len(frames), 2)
        self.assertEqual(frames[0][0], "CONNECTED")
        self.assertEqual(frames[1][0], "MESSAGE")
        self.assertEqual(frames[1][2], '{"id":1}')
        self.assertEqual(remaining, partial)

    def test_parse_empty_buffer(self):
        frames, remaining = parse_stomp_buffer("")
        self.assertEqual(frames, [])
        self.assertEqual(remaining, "")


class InvariantAndMonotonicChecksTest(unittest.TestCase):
    def test_monotonic_watermark_advancement(self):
        self.assertEqual(check_monotonic_watermark(10, 20), 20)
        # Monotonic non-regression: older sequence does not reduce watermark
        self.assertEqual(check_monotonic_watermark(20, 15), 20)
        self.assertEqual(check_monotonic_watermark(20, 20), 20)

    def test_monotonic_watermark_negative_rejected(self):
        with self.assertRaises(ValueError):
            check_monotonic_watermark(-1, 5)
        with self.assertRaises(ValueError):
            check_monotonic_watermark(5, -1)

    def test_verify_monotonic_sequence(self):
        self.assertTrue(verify_monotonic_sequence([1, 2, 3, 4, 10, 50]))
        self.assertTrue(verify_monotonic_sequence([]))
        self.assertTrue(verify_monotonic_sequence([42]))
        # Duplicate sequence number
        self.assertFalse(verify_monotonic_sequence([1, 2, 2, 3]))
        # Decreasing sequence number
        self.assertFalse(verify_monotonic_sequence([1, 5, 4, 6]))

    def test_verify_no_duplicates(self):
        self.assertTrue(verify_no_duplicates(["uuid-1", "uuid-2", "uuid-3"]))
        self.assertTrue(verify_no_duplicates([10, 20, 30]))
        self.assertTrue(verify_no_duplicates([]))
        self.assertFalse(verify_no_duplicates(["uuid-1", "uuid-2", "uuid-1"]))

    def test_clamp_watermark(self):
        self.assertEqual(clamp_watermark(100, 50), 50)
        self.assertEqual(clamp_watermark(30, 50), 30)
        self.assertEqual(clamp_watermark(50, 50), 50)
        with self.assertRaises(ValueError):
            clamp_watermark(-1, 50)


class CredentialGeneratorsTest(unittest.TestCase):
    def test_generate_test_phone_format_and_range(self):
        phone1 = generate_test_phone()
        phone2 = generate_test_phone()
        self.assertTrue(phone1.startswith("+84908"))
        self.assertEqual(len(phone1), 12)
        self.assertTrue(phone1[1:].isdigit())
        self.assertTrue(phone2.startswith("+84908"))
        # High probability of being distinct
        self.assertNotEqual(phone1, phone2)

    def test_generate_test_password_properties(self):
        pwd = generate_test_password(24)
        self.assertEqual(len(pwd), 24)
        self.assertTrue(any(c.isupper() for c in pwd))
        self.assertTrue(any(c.islower() for c in pwd))
        self.assertTrue(any(c.isdigit() for c in pwd))
        self.assertTrue(any(c in "@#$%^&*!_+-=" for c in pwd))

    def test_generate_test_password_length_bounds(self):
        with self.assertRaises(ValueError):
            generate_test_password(7)
        with self.assertRaises(ValueError):
            generate_test_password(73)


if __name__ == "__main__":
    unittest.main()
