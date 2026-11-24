"""Offline unit tests for the runnable-row list in tools/e2e/README.md.

Freezes the device-matrix rows the README advertises as runnable so the list
cannot drift back to a stale subset. The expected ids are literal here on
purpose: this test never parses docs/quality/testing.md, so an unrelated
branch can add a row there without failing this test.

Repair (2026-11-24, tools lane of the issue-sweep wave): this guard last
matched the README before offline-sync was re-advertised (post-v1216b
campaign) and before the mail rows moved into the dstack batch, and because
CI never ran it, main carried 7 dormant failures. The frozen sets now match
the advertised reality, every blocked row must stay documented in the board
table instead of being silently dropped, the account-deletion covenant stays
frozen verbatim, and CI runs this file so drift can never go silent again.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

README = Path(__file__).resolve().parent / "README.md"

# Rows the "Runnable rows are ..." sentence advertises as runnable,
# including the --dstack provision token and the two mail rows it gates.
ADVERTISED_RUNNABLE = (
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
    "offline-sync",
    "accessibility-sweep",
    "--dstack",
    "otp-forgot-password",
    "account-deletion",
)

# Journeys that exist in the harness but must never be advertised as
# runnable: they are BLOCKED_CAPABILITY (or have no board pass recorded)
# and belong in the board table with their disposition instead.
BLOCKED_ROWS = (
    "auth-session-lifecycle",
    "media",
    "calls-signaling",
    "room-13-14-upgrade",
    "v111-rc-upgrade",
)

# The account-deletion covenant: the advertised sentence must keep the
# always-last + disposable-account-only wording verbatim.
ACCOUNT_DELETION_COVENANT = "(always last; disposable account only)"


def readme_text() -> str:
    return README.read_text(encoding="utf-8")


def runnable_paragraph(text: str) -> str:
    """Return the 'Stable journeys' paragraph that lists the runnable rows."""
    match = re.search(r"Runnable rows are(.*?)(?:\n\n|\Z)", text, re.S)
    if match is None:
        raise AssertionError("README has no 'Runnable rows are' paragraph")
    return " ".join(match.group(1).split())


def advertised_tokens(paragraph: str) -> list[str]:
    """Return the backticked tokens of the paragraph's first sentence."""
    body = paragraph.split(". ", 1)[0]
    return re.findall(r"`([^`]+)`", body)


def board_table_lines(text: str) -> dict:
    """Return row-name -> line for lines that start a board table row."""
    rows = {}
    for line in text.splitlines():
        match = re.match(r"\|\s*`([^`]+)`\s*\|", line)
        if match:
            rows[match.group(1)] = line
    return rows


class ReadmeRunnableRowsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.text = readme_text()
        self.runnable = runnable_paragraph(self.text)

    def test_advertised_tokens_equal_frozen_ids(self) -> None:
        tokens = advertised_tokens(self.runnable)
        self.assertEqual(
            sorted(tokens),
            sorted(ADVERTISED_RUNNABLE),
            f"README advertised runnable rows drifted: {tokens}",
        )

    def test_advertised_ids_are_unique(self) -> None:
        tokens = advertised_tokens(self.runnable)
        self.assertEqual(len(tokens), len(set(tokens)), tokens)

    def test_blocked_rows_stay_unadvertised_and_documented(self) -> None:
        advertised = set(advertised_tokens(self.runnable))
        board = board_table_lines(self.text)
        for row in BLOCKED_ROWS:
            with self.subTest(row=row):
                self.assertNotIn(row, advertised)
                self.assertIn(row, board, f"'{row}' lost its board-table row")

    def test_account_deletion_covenant_stays_frozen(self) -> None:
        self.assertIn(ACCOUNT_DELETION_COVENANT, self.runnable)
        self.assertIn("--dstack", advertised_tokens(self.runnable))

    def test_offline_sync_is_advertised(self) -> None:
        # offline-sync returned to the advertised list after the v1216b
        # campaign reruns; dropping it again must be a deliberate change
        # that updates README and this guard together.
        self.assertIn("offline-sync", advertised_tokens(self.runnable))


if __name__ == "__main__":
    unittest.main()
