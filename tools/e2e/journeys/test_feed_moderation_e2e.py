"""Offline tests for feed_moderation_e2e pure helpers (no network)."""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import feed_moderation_e2e as journey  # noqa: E402
from e2e_common import E2EFailure, Response  # noqa: E402


def response(status, body=None, headers=None):
    raw = b"" if body is None else json.dumps(body).encode("utf-8")
    return Response(status, headers or {}, raw)


class ArgsTest(unittest.TestCase):
    def test_defaults(self):
        args = journey.parse_args([])
        self.assertEqual(args.base_url, "http://127.0.0.1:8091")
        self.assertFalse(args.list)
        self.assertIsNone(args.only)

    def test_flags(self):
        args = journey.parse_args(["--base-url", "http://x:1", "--list", "--only", "a,b"])
        self.assertEqual((args.base_url, args.list, args.only), ("http://x:1", True, "a,b"))


class SelectTest(unittest.TestCase):
    names = [name for name, _ in journey.SCENARIOS]

    def test_all_when_no_filter(self):
        self.assertEqual(journey.select_scenarios(self.names, None), self.names)

    def test_keeps_declaration_order(self):
        picked = journey.select_scenarios(self.names, " reports , create_text_post ")
        self.assertEqual(picked, ["create_text_post", "reports"])

    def test_unknown_name_rejected(self):
        with self.assertRaises(ValueError):
            journey.select_scenarios(self.names, "nope")

    def test_scenario_names_unique(self):
        self.assertEqual(len(self.names), len(set(self.names)))


class PayloadTest(unittest.TestCase):
    def test_post_payload_shapes(self):
        payload = journey.post_payload("hi", client_post_id="c1")
        self.assertEqual(payload, {"clientPostId": "c1", "body": "hi"})
        image_only = journey.post_payload(None, image_id="img")
        self.assertNotIn("body", image_only)
        self.assertEqual(image_only["imageId"], "img")
        self.assertTrue(image_only["clientPostId"])

    def test_comment_and_report_payloads(self):
        self.assertEqual(journey.comment_payload("b", "c")["clientCommentId"], "c")
        report = journey.report_payload("POST", "t", "SPAM")
        self.assertEqual(report, {"targetType": "POST", "targetId": "t", "reason": "SPAM"})
        self.assertEqual(journey.report_payload("COMMENT", "t", "SPAM", "d")["details"], "d")

    def test_multipart_body(self):
        data, content_type = journey.multipart_body(
            "file", "a.png", "image/png", b"\x89PNG", fields={"width": "16"}, boundary="BND")
        self.assertEqual(content_type, "multipart/form-data; boundary=BND")
        self.assertIn(b'name="width"\r\n\r\n16\r\n', data)
        self.assertIn(b'name="file"; filename="a.png"\r\nContent-Type: image/png\r\n\r\n\x89PNG',
                      data)
        self.assertTrue(data.endswith(b"\r\n--BND--\r\n"))

    def test_png_fixture_has_magic(self):
        self.assertTrue(journey.PNG_BYTES.startswith(b"\x89PNG\r\n\x1a\n"))
        self.assertFalse(journey.NOT_AN_IMAGE.startswith(b"\x89PNG"))

    def test_reaction_path_percent_encodes(self):
        path = journey.reaction_path("p", "\U0001F44D")
        self.assertEqual(path, "/api/v1/feed/posts/p/reactions/%F0%9F%91%8D")
        path.encode("ascii")

    def test_emoji_fixture(self):
        self.assertEqual(len(set(journey.DISTINCT_EMOJIS)), 8)
        self.assertNotIn(journey.NINTH_EMOJI, journey.DISTINCT_EMOJIS)


class ValidatorTest(unittest.TestCase):
    def test_reaction_entry(self):
        items = [{"emoji": "a", "count": 1, "mine": True}]
        self.assertEqual(journey.reaction_entry(items, "a")["count"], 1)
        self.assertIsNone(journey.reaction_entry(items, "b"))
        self.assertIsNone(journey.reaction_entry(None, "a"))

    def test_ids_of(self):
        self.assertEqual(journey.ids_of([{"id": "x"}, {"id": "y"}]), ["x", "y"])
        self.assertEqual(journey.ids_of(None), [])

    def test_retry_after_from_details_header_default(self):
        body = {"error": {"code": "RATE_LIMITED", "details": {"retryAfterSeconds": 7}}}
        self.assertEqual(journey.retry_after_seconds(response(429, body)), 7)
        self.assertEqual(journey.retry_after_seconds(
            response(429, {"error": {"code": "RATE_LIMITED"}}, {"Retry-After": "12"})), 12)
        self.assertEqual(journey.retry_after_seconds(response(429)), 20)

    def test_expect_error(self):
        ok = response(400, {"error": {"code": "VALIDATION_FAILED"}})
        journey.expect_error(ok, 400, "VALIDATION_FAILED", "x")
        with self.assertRaises(E2EFailure):
            journey.expect_error(ok, 404, "VALIDATION_FAILED", "x")
        with self.assertRaises(E2EFailure):
            journey.expect_error(ok, 400, "POST_NOT_FOUND", "x")

    def test_expect_status(self):
        journey.expect_status(response(201, {}), (200, 201), "x")
        with self.assertRaises(E2EFailure):
            journey.expect_status(response(500, {}), 200, "x")

    def test_assert_author_public(self):
        journey.assert_author_public({"id": "u", "displayName": "n", "avatarUrl": None})
        with self.assertRaises(E2EFailure):
            journey.assert_author_public({"id": "u", "phone": "+84"})
        with self.assertRaises(E2EFailure):
            journey.assert_author_public(None)


if __name__ == "__main__":
    unittest.main()
