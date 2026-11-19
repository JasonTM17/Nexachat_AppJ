"""Docs-only regression: the summary-zone limitation matches ai-summary.md.

docs/quality/known-limitations.md must not claim that
docs/architecture/ai-summary.md still describes TODAY as since 00:00 UTC;
that doc defines TODAY as the caller's local calendar day with an
Asia/Ho_Chi_Minh fallback when timeZone is omitted. Stdlib only, offline,
no server, database or Android device access.
"""

from __future__ import annotations

import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
KNOWN_LIMITATIONS_PATH = REPO_ROOT / "docs" / "quality" / "known-limitations.md"
AI_SUMMARY_PATH = REPO_ROOT / "docs" / "architecture" / "ai-summary.md"

ROW_LABEL = '| Summary scope and "Today" zone |'

# Signatures of the removed stale sentence ("... still describes `TODAY`
# as since 00:00 UTC."), counting as a failure both the exact wording and a
# loose restatement of the UTC claim.
STALE_CLAIM_MARKERS = (
    "00:00 UTC",
    "still describes `TODAY`",
)


def _rows_with_label(markdown: str) -> list[str]:
    return [line for line in markdown.splitlines() if line.startswith(ROW_LABEL)]


class SummaryZoneLimitationDocsTest(unittest.TestCase):
    def setUp(self):
        self.limitations = KNOWN_LIMITATIONS_PATH.read_text(encoding="utf-8")
        self.ai_summary = AI_SUMMARY_PATH.read_text(encoding="utf-8")

    def test_summary_zone_row_is_present_and_still_links_to_the_architecture_doc(self):
        rows = _rows_with_label(self.limitations)
        self.assertEqual(1, len(rows), "the Summary scope and Today-zone row must stay unique")
        self.assertIn("../architecture/ai-summary.md", rows[0],
                      "the limitation row must keep its link to the architecture doc")
        self.assertTrue(AI_SUMMARY_PATH.is_file(),
                        "the linked docs/architecture/ai-summary.md must exist")

    def test_stale_utc_claim_is_gone_from_known_limitations(self):
        for marker in STALE_CLAIM_MARKERS:
            self.assertNotIn(marker, self.limitations,
                             f"stale summary-zone claim {marker!r} is still present; "
                             "ai-summary.md defines TODAY as the caller's local day "
                             "with the Asia/Ho_Chi_Minh fallback, not since 00:00 UTC")

    def test_row_agrees_with_the_caller_local_day_documentation(self):
        # The architecture doc (read here, never edited by this lane) defines
        # the TODAY window and its zone fallback; the limitation row must
        # describe the same behavior instead of asserting a UTC discrepancy.
        self.assertIn("`[00:00, next 00:00)` of the caller's local day", self.ai_summary)
        self.assertIn("`Asia/Ho_Chi_Minh`", self.ai_summary)
        row = _rows_with_label(self.limitations)[0]
        self.assertIn("`Asia/Ho_Chi_Minh`", row)
        self.assertIn("local calendar day", row)


if __name__ == "__main__":
    unittest.main(verbosity=2)
