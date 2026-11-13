"""Offline guard: P0E2ETestRunner must not claim opaque-box E2E acceptance."""

from __future__ import annotations

import pathlib
import re
import unittest


RUNNER = pathlib.Path(__file__).resolve().parent / "P0E2ETestRunner.java"

# Marketing / acceptance phrases that imply product E2E verification without a live target.
FORBIDDEN = (
    re.compile(r"opaque-box\s+E2E", re.IGNORECASE),
    re.compile(r"OPAQUE-BOX\s+E2E\s+TEST\s+RUNNER"),
    re.compile(r"requirement-driven\s+opaque-box", re.IGNORECASE),
    re.compile(r'"ALL PASS \(100%\)"'),
    re.compile(r'\?\s*"ALL PASS \(100%\)"'),
)


class EvidenceLabelsTest(unittest.TestCase):
    def test_runner_does_not_claim_opaque_box_e2e_acceptance(self) -> None:
        text = RUNNER.read_text(encoding="utf-8")
        violations: list[str] = []
        for pattern in FORBIDDEN:
            if pattern.search(text):
                violations.append(pattern.pattern)
        self.assertEqual(
            [],
            violations,
            "P0E2ETestRunner must be labeled as a synthetic fixture, not E2E acceptance",
        )


if __name__ == "__main__":
    unittest.main()
