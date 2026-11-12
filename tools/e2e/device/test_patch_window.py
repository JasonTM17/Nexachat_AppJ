"""Focused tests for the target-device permission dump parser."""

import unittest

from adb_ui import escape_adb_input_text
from patch_window import E2EFailure, parse_permission_state


# Scrubbed excerpt from the captured Android 15 `dumpsys package com.nexachat.debug` output.
# The package record id, user inode/data fields, paths, and unrelated entries were removed.
SCRUBBED_PACKAGE_DUMP = """\
Packages:
  Package [com.nexachat.debug] ([scrubbed]):
    requested permissions:
      [scrubbed]
    install permissions:
      [scrubbed]
    User 0: [scrubbed]
      runtime permissions:
        android.permission.POST_NOTIFICATIONS: granted=true, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
        android.permission.RECORD_AUDIO: granted=true, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
      disabledComponents:
    User 10: [scrubbed]
      runtime permissions:
        android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
        android.permission.RECORD_AUDIO: granted=true, flags=[ USER_SET|USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
      disabledComponents:
Queries:
"""


class PermissionDumpParserTest(unittest.TestCase):
    def test_scrubbed_android_15_dump_requires_an_explicit_current_user_state(self) -> None:
        self.assertEqual(
            parse_permission_state(
                SCRUBBED_PACKAGE_DUMP, "0", "android.permission.POST_NOTIFICATIONS"
            ),
            "granted",
        )
        self.assertEqual(
            parse_permission_state(
                SCRUBBED_PACKAGE_DUMP, "0", "android.permission.RECORD_AUDIO"
            ),
            "granted",
        )
        self.assertEqual(
            parse_permission_state(
                SCRUBBED_PACKAGE_DUMP, "10", "android.permission.POST_NOTIFICATIONS"
            ),
            "denied",
        )
        with self.assertRaisesRegex(E2EFailure, "one explicit runtime grant state"):
            parse_permission_state(
                SCRUBBED_PACKAGE_DUMP, "0", "android.permission.UNKNOWN"
            )


class AdbInputEscapeTest(unittest.TestCase):
    def test_phone_account_and_password_are_shell_safe(self) -> None:
        self.assertEqual(escape_adb_input_text("+84900000001"), "+84900000001")
        self.assertEqual(escape_adb_input_text("password123"), "password123")
        self.assertEqual(escape_adb_input_text("line one line two"), "line%sone%sline%stwo")


if __name__ == "__main__":
    unittest.main()
