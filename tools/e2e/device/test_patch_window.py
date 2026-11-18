"""Focused tests for the target-device permission dump parser."""

import unittest
from unittest.mock import Mock, patch
from xml.etree.ElementTree import Element

from adb_ui import Node, escape_adb_input_text
from patch_window import E2EFailure, fill_login_field, inbox_visible, parse_permission_state


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


class InboxVisibleTest(unittest.TestCase):
    def test_peer_title_on_chat_screen_is_not_the_inbox(self) -> None:
        chat_title = Node(Element("node", {"text": "Binh", "bounds": "[0,0][10,10]"}))
        self.assertFalse(inbox_visible([chat_title]))

    def test_avatar_entry_point_marks_the_inbox(self) -> None:
        avatar = Node(Element("node", {
            "resource-id": "com.nexachat.debug:id/inboxUserAvatar",
            "bounds": "[0,0][10,10]",
        }))
        self.assertTrue(inbox_visible([avatar]))


class LoginFieldFocusTest(unittest.TestCase):
    @staticmethod
    def password_field(focused: bool) -> Node:
        return Node(Element("node", {
            "resource-id": "com.nexachat.debug:id/passwordInput",
            "focused": str(focused).lower(),
            "bounds": "[0,0][100,40]",
        }))

    def test_does_not_type_if_tap_leaves_focus_elsewhere(self) -> None:
        ui = Mock()
        field = self.password_field(False)
        with patch("patch_window.ui_nodes", side_effect=[[field], [field]]):
            with self.assertRaisesRegex(E2EFailure, "did not receive input focus"):
                fill_login_field(ui, "passwordInput", "fixture")
        ui.tap_node.assert_called_once_with(field)
        ui.type_text.assert_not_called()

    def test_types_when_current_snapshot_confirms_focus(self) -> None:
        ui = Mock()
        initial = self.password_field(False)
        with patch("patch_window.ui_nodes", side_effect=[[initial], [self.password_field(True)]]):
            fill_login_field(ui, "passwordInput", "fixture")
        ui.tap_node.assert_called_once_with(initial)
        ui.type_text.assert_called_once_with("fixture")


if __name__ == "__main__":
    unittest.main()
