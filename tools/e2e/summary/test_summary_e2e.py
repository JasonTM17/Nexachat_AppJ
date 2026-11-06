"""Unit tests for pure helpers in summary_e2e.py (no live backend)."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


def _load_summary_e2e():
    path = Path(__file__).resolve().parent / "summary_e2e.py"
    spec = importlib.util.spec_from_file_location("summary_e2e_under_test", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules["summary_e2e_under_test"] = module
    spec.loader.exec_module(module)
    return module


mod = _load_summary_e2e()


class SummaryE2eHelperTests(unittest.TestCase):
    def test_collect_response_text_joins_sections(self):
        summary = {
            "overview": "Tóm tắt",
            "keyPoints": [{"text": "Điểm chính", "messageIds": ["a"]}],
            "decisions": [],
            "actionItems": [{"text": "Việc", "dueHint": "Thứ sáu", "messageIds": ["b"]}],
            "questions": [],
            "topics": ["dự án"],
            "participants": [{"displayName": "Alpha", "messageCount": 1}],
        }
        text = mod.collect_response_text(summary)
        self.assertIn("Tóm tắt", text)
        self.assertIn("Điểm chính", text)
        self.assertIn("Thứ sáu", text)
        self.assertIn("dự án", text)
        self.assertIn("Alpha", text)

    def test_contract_requires_response_redaction_only_for_llm(self):
        self.assertFalse(mod.contract_requires_response_redaction("LOCAL"))
        self.assertTrue(mod.contract_requires_response_redaction("LLM"))

    def test_response_contains_redacted_pii_detects_seed_markers(self):
        self.assertTrue(mod.response_contains_redacted_pii(mod.PII_EMAIL))
        self.assertTrue(mod.response_contains_redacted_pii(mod.PII_PHONE))
        self.assertTrue(mod.response_contains_redacted_pii(mod.PII_URL))
        self.assertFalse(mod.response_contains_redacted_pii("Không có dữ liệu nhạy cảm"))

    def test_assert_citations_in_window(self):
        summary = {
            "keyPoints": [{"text": "x", "messageIds": ["id-1"]}],
            "decisions": [],
            "actionItems": [],
            "questions": [],
        }
        mod.assert_citations_in_window(summary, {"id-1"})
        with self.assertRaises(mod.E2EFailure):
            mod.assert_citations_in_window(summary, {"other"})

    def test_assert_error(self):
        class FakeResponse:
            status = 400

            @staticmethod
            def error_code():
                return "TOO_FEW_MESSAGES"

        mod.assert_error(FakeResponse(), 400, "TOO_FEW_MESSAGES")
        with self.assertRaises(mod.E2EFailure):
            mod.assert_error(FakeResponse(), 403, "NOT_MEMBER")

    def test_vietnamese_corpus_at_least_forty_lines(self):
        lines = mod.vietnamese_corpus_messages()
        self.assertGreaterEqual(len(lines), 40)


if __name__ == "__main__":
    unittest.main()
