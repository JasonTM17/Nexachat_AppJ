"""Offline tests for the emulator-5554 debug push helper.

    python -m unittest tools/e2e/device/test_inject_push.py
"""

from __future__ import annotations

import io
import json
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import inject_push


MESSAGE = ["message", "--recipient-id", "user-me", "--conversation-id", "conversation-1",
           "--message-id", "message-1", "--seq", "8"]
REMINDER = ["reminder", "--recipient-id", "user-me", "--conversation-id", "conversation-1",
            "--reminder-id", "reminder-1", "--occurrence", "0123456789abcdef0123456789abcdef"]
CALL = ["call", "--recipient-id", "user-me", "--conversation-id", "conv-200",
        "--call-id", "call-100", "--caller-id", "user-alice", "--caller-name", "Alice Nguyen"]


class SerialGuardTest(unittest.TestCase):
    def test_command_is_pinned_to_emulator_5554(self):
        command = inject_push.adb_argv(inject_push.build_message(
            "user-me", "conversation-1", "message-1", "8"))
        self.assertEqual(command[:7], [
            "adb", "-s", "emulator-5554", "shell", "am", "broadcast", "-n"])
        self.assertEqual(
            command[7],
            "com.nexachat.debug/com.nexachat.debug.DebugPushInjectionReceiver")
        self.assertNotIn("emulator-5558", command)
        self.assertEqual(command.count("emulator-5554"), 1)

    def test_other_serials_are_refused(self):
        payload = inject_push.build_message("user-me", "conversation-1", "message-1", "8")
        for serial in ("emulator-5558", "emulator-5570", "", "emulator-5554 "):
            with self.assertRaises(inject_push.InjectPushError):
                inject_push.adb_argv(payload, serial=serial)

    def test_cli_rejects_a_different_serial_before_adb(self):
        stderr = io.StringIO()
        with redirect_stderr(stderr), mock.patch("inject_push.subprocess.run") as run:
            code = inject_push.main(["--serial", "emulator-5558", "--dry-run", *MESSAGE])
        self.assertEqual(code, 2)
        self.assertIn("emulator-5554", stderr.getvalue())
        run.assert_not_called()


class PayloadShapeTest(unittest.TestCase):
    def test_message_reminder_and_call_shapes(self):
        message = inject_push.build_message("user-me", "conversation-1", "message-1", "8")
        self.assertEqual(list(message), list(inject_push.MESSAGE_KEYS))
        self.assertEqual(message["type"], "NEW_MESSAGE")
        self.assertEqual(message["seq"], "8")

        reminder = inject_push.build_reminder(
            "user-me", "conversation-1", "reminder-1", "0123456789abcdef0123456789abcdef")
        self.assertEqual(list(reminder), list(inject_push.REMINDER_KEYS))
        self.assertEqual(reminder["type"], "REMINDER")

        call = inject_push.build_call(
            "user-me", "conv-200", "call-100", "user-alice", "Alice Nguyen",
            now_ms=1_700_000_000_000)
        self.assertEqual(list(call), list(inject_push.CALL_KEYS))
        self.assertEqual(call["type"], "CALL_INVITE")
        self.assertEqual(call["serverTs"], "1700000000000")
        self.assertEqual(call["ringExpiresAt"], "1700000045000")
        self.assertEqual(call["callerName"], "Alice Nguyen")

    def test_string_extras_keep_caller_name_as_one_argument(self):
        payload = inject_push.build_call(
            "user-me", "conv-200", "call-100", "user-alice", "Alice Nguyen",
            server_ts="1700000000000", ring_expires_at="1700000045000",
            now_ms=1_700_000_000_000)
        command = inject_push.adb_argv(payload)
        self.assertEqual(command[command.index("callerName") + 1], "Alice Nguyen")
        self.assertEqual(command[command.index("-a") + 1], inject_push.ACTION)

    def test_invalid_shapes_are_refused(self):
        with self.assertRaises(inject_push.InjectPushError):
            inject_push.build_message("user-me", "conversation-1", "message-1", "0")
        with self.assertRaises(inject_push.InjectPushError):
            inject_push.build_reminder(
                "user-me", "conversation-1", "reminder-1", "0123456789ABCDEF0123456789ABCDEF")
        with self.assertRaises(inject_push.InjectPushError):
            inject_push.build_call(
                "user-me", "conv-200", "call-100", "user-alice", "Alice",
                server_ts="1700000045000", ring_expires_at="1700000000000",
                now_ms=1_600_000_000_000)

    def test_dry_run_prints_the_command_and_does_not_call_adb(self):
        stdout = io.StringIO()
        with redirect_stdout(stdout), mock.patch("inject_push.subprocess.run") as run:
            code = inject_push.main(["--dry-run", *MESSAGE])
        self.assertEqual(code, 0)
        command = json.loads(stdout.getvalue())
        self.assertEqual(command[1:3], ["-s", "emulator-5554"])
        self.assertIn("NEW_MESSAGE", command)
        self.assertIn("recipientId", command)
        run.assert_not_called()


class SecretRefusalTest(unittest.TestCase):
    def test_production_urls_are_refused(self):
        urls = (
            "https://nexachat.io.vn/join/abc",
            "http://api.nexachat.io.vn",
            "HTTPS://NEXACHAT.IO.VN/path",
            "http://10.0.2.2:8080",
            "https://fcm.googleapis.com/v1/projects/x/messages:send",
        )
        for url in urls:
            with self.assertRaises(inject_push.InjectPushError):
                inject_push.build_message(url, "conversation-1", "message-1", "8")

    def test_tokens_are_refused(self):
        tokens = (
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLW1lIn0.signature",
            "Bearer access-token-value",
            "AIzaSyA123456789012345678901234567",
            "fcm-token:APA91b000000000000000000000000000",
            "ya29.a0AfH6SMC1234567890",
        )
        for token in tokens:
            with self.assertRaises(inject_push.InjectPushError):
                inject_push.build_message("user-me", "conversation-1", token, "8")
            with self.assertRaises(inject_push.InjectPushError):
                inject_push.build_call(
                    "user-me", "conv-200", "call-100", "user-alice", token,
                    now_ms=1_700_000_000_000)

    def test_token_flag_is_not_a_supported_argument(self):
        with self.assertRaises(SystemExit):
            inject_push.parse_args([*MESSAGE, "--token", "secret"])


if __name__ == "__main__":
    unittest.main()
