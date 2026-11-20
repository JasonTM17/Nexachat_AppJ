"""Offline unit tests for the runnable-row list in tools/e2e/README.md.

Freezes the device-matrix rows the README advertises as runnable so the list
cannot drift back to a stale subset. The expected ids are literal here on
purpose: this test never parses docs/quality/testing.md, so an unrelated
branch can add a row there without failing this test.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

README = Path(__file__).resolve().parent / "README.md"

RUNNABLE_ROWS = (
    "auth-login",
    "chat-send-receive",
    "chat-interactions",
    "calls-injected-notification",
    "injected-notifications",
    "summary-ask",
    "archive-preferences",
    "groups-contacts",
    "labels-lifecycle",
    "polls-reminders",
    "accessibility-sweep",
)

BLOCKED_ROWS = (
    "auth-session-lifecycle",
    "otp-forgot-password",
    "media",
    "upgrade",
    "account-deletion",
)


def readme_text() -> str:
    return README.read_text(encoding="utf-8")


def runnable_paragraph(text: str) -> str:
    """Return the 'Stable journeys' paragraph that lists the runnable rows."""
    match = re.search(r"Runnable rows are(.*?)(?:\n\n|\Z)", text, re.S)
    if match is None:
        raise AssertionError("README has no 'Runnable rows are' paragraph")
    return " ".join(match.group(1).split())


def runnable_ids(paragraph: str) -> list[str]:
    """Return the leading comma/`and` separated id list, stopping at sentence end."""
    body = paragraph.split(". ", 1)[0]
    return re.findall(r"`([^`]+)`", body)


def backticked(paragraph: str) -> list[str]:
    return re.findall(r"`([^`]+)`", paragraph)


class ReadmeRunnableRowsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.text = readme_text()
        self.runnable = runnable_paragraph(self.text)

    def test_runnable_names_equal_frozen_ids(self) -> None:
        names = runnable_ids(self.runnable)
        self.assertEqual(
            sorted(names),
            sorted(RUNNABLE_ROWS),
            f"README runnable rows drifted: {names}",
        )

    def test_runnable_ids_are_unique(self) -> None:
        names = runnable_ids(self.runnable)
        self.assertEqual(len(names), len(set(names)), names)

    def test_blocked_rows_stay_blocked(self) -> None:
        self.assertIn("BLOCKED_CAPABILITY", self.runnable)
        named = set(backticked(self.runnable)) - set(RUNNABLE_ROWS)
        for row in BLOCKED_ROWS:
            with self.subTest(row=row):
                if row == "upgrade":
                    self.assertIn("upgrade rows", self.runnable)
                    continue
                self.assertIn(row, named)
                self.assertNotIn(row, runnable_ids(self.runnable))

    def test_offline_sync_not_advertised(self) -> None:
        self.assertNotIn("offline-sync", self.text)


if __name__ == "__main__":
    unittest.main()
