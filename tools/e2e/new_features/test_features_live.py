"""Offline unit tests for the new-feature live suite's own logic.

No backend and no websocket server are required. Run with:
    python -m unittest tools/e2e/new_features/test_features_live.py
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
for path in (HERE, HERE.parent, HERE.parent / "p0"):
    if str(path) not in sys.path:
        sys.path.insert(0, str(path))

import features_live  # noqa: E402
from features_helpers import (  # noqa: E402
    CheckLog,
    anonymous_poll_leaks_nobody,
    is_local_base_url,
    multipart_body,
)


class CheckLogExitTest(unittest.TestCase):
    def test_failure_always_exits_non_zero(self):
        log = CheckLog(allow_skip=True)
        log.expect("bad", False, "GET", "/x", 500)
        self.assertEqual(1, log.summary())

    def test_required_skip_needs_allow_skip(self):
        for allow_skip, expected in ((False, 1), (True, 0)):
            log = CheckLog(allow_skip=allow_skip)
            log.skip("missing", "GET", "/x", "absent", required=True)
            self.assertEqual(expected, log.summary())

    def test_optional_skip_does_not_fail(self):
        log = CheckLog(allow_skip=False)
        log.skip("optional", "STOMP", "/ws", "not installed", required=False)
        self.assertEqual(0, log.summary())

    def test_pass_exits_zero(self):
        log = CheckLog()
        log.expect_status("ok", type("R", (), {"status": 200, "error_code": lambda: None})(),
                          200, "GET", "/x")
        self.assertEqual(0, log.summary())


class BaseUrlGuardTest(unittest.TestCase):
    def test_loopback_urls_are_local(self):
        for url in (
            "http://localhost:8080",
            "http://127.0.0.1:8080",
            "https://127.0.0.42",
            "http://[::1]:8080",
        ):
            self.assertTrue(is_local_base_url(url), url)

    def test_non_loopback_and_malformed_urls_are_not_local(self):
        for url in (
            "https://example.com",
            "http://10.0.0.5:8080",
            "http://localhost.evil.example",
            "ftp://localhost",
            "not-a-url",
            "",
        ):
            self.assertFalse(is_local_base_url(url), url)

    def test_main_refuses_remote_url_without_override(self):
        self.assertEqual(2, features_live.main(["--base-url", "https://example.com"]))


class RedactionTest(unittest.TestCase):
    def test_safe_exception_masks_phone_and_token(self):
        text = features_live.safe_exception(
            RuntimeError("login +84908123456 failed with eyJhbGciOiJIUzI1NiJ9.payload.signature")
        )
        self.assertNotIn("+84908123456", text)
        self.assertNotIn("eyJhbGciOiJIUzI1NiJ9", text)
        self.assertIn("[REDACTED_PHONE]", text)
        self.assertIn("[REDACTED_TOKEN]", text)


class StompFramePredicateTest(unittest.TestCase):
    def test_read_event_matches_only_the_requested_user(self):
        body = '{"eventType":"MESSAGE_READ","payload":{"userId":"u-1","upToSeq":7}}'
        frame = ("MESSAGE", {}, body)
        self.assertTrue(features_live._has_read_event([frame], "u-1"))
        self.assertFalse(features_live._has_read_event([frame], "u-2"))

    def test_other_events_and_malformed_bodies_do_not_match(self):
        self.assertFalse(features_live._has_read_event(
            [("MESSAGE", {}, '{"eventType":"MESSAGE_CREATED","payload":{"userId":"u-1"}}')],
            "u-1",
        ))
        self.assertFalse(features_live._has_read_event([("MESSAGE", {}, "not-json")], "u-1"))


class FakeSocket:
    def __init__(self, behaviour):
        self.behaviour = behaviour

    def settimeout(self, _timeout):
        pass

    def recv(self):
        if self.behaviour == "closed":
            raise ConnectionError("closed")
        if self.behaviour == "none":
            return None
        if self.behaviour == "empty":
            return ""
        raise TimeoutError("timeout")


class SocketCloseTest(unittest.TestCase):
    def test_closed_socket_is_detected(self):
        for behaviour in ("closed", "none", "empty"):
            connection = type("C", (), {"ws": FakeSocket(behaviour)})()
            self.assertTrue(features_live._wait_for_socket_close(connection, timeout=0.5))

    def test_open_socket_is_not_reported_closed(self):
        connection = type("C", (), {"ws": FakeSocket("timeout")})()
        self.assertFalse(features_live._wait_for_socket_close(connection, timeout=0.2))


class PollLeakTest(unittest.TestCase):
    def test_anonymous_poll_without_voter_ids_is_clean(self):
        poll = {
            "anonymous": True,
            "options": [
                {"id": "o1", "voteCount": 1},
                {"id": "o2", "voteCount": 0},
            ],
        }
        self.assertTrue(anonymous_poll_leaks_nobody(poll, "user-b"))

    def test_anonymous_poll_with_voter_ids_or_voter_is_not_clean(self):
        with_ids = {"anonymous": True, "options": [{"id": "o1", "voterIds": ["user-b"]}]}
        self.assertFalse(anonymous_poll_leaks_nobody(with_ids, "user-b"))
        with_voter_elsewhere = {"anonymous": True, "closedBy": "user-b", "options": []}
        self.assertFalse(anonymous_poll_leaks_nobody(with_voter_elsewhere, "user-b"))


class MultipartTest(unittest.TestCase):
    def test_multipart_contains_file_and_text_fields(self):
        body, content_type = multipart_body(
            fields={"blurHash": "abc123"},
            filename="photo.png",
            content_type="image/png",
            data=b"\x89PNG\r\n",
        )
        self.assertTrue(content_type.startswith("multipart/form-data; boundary="))
        self.assertIn(b'name="blurHash"', body)
        self.assertIn(b'filename="photo.png"', body)
        self.assertIn(b"Content-Type: image/png", body)
        self.assertTrue(body.endswith(b"--\r\n"))


if __name__ == "__main__":
    unittest.main()
