"""Offline unit tests for the pure helpers in ai_privacy_e2e.py (no network)."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ai_privacy_e2e as journey  # noqa: E402


def _ask(mode="EXTRACTIVE", answer="The pin is x", citations=None, cached=False, llm_calls=0,
         omitted=False):
    if citations is None:
        citations = [{"messageId": "m1", "seq": 3, "snippet": "The pin is x"}]
    return {"answer": answer, "citations": citations, "mode": mode, "contextOmitted": omitted,
            "usage": {"llmCalls": llm_calls, "inputChars": 0, "cached": cached, "provider": None}}


class ArgsTest(unittest.TestCase):
    def test_defaults(self):
        args = journey.parse_args([])
        self.assertEqual(args.base_url, "http://127.0.0.1:8092")
        self.assertFalse(args.list)
        self.assertEqual(args.only, "")

    def test_flags(self):
        args = journey.parse_args(["--base-url", "http://x:1", "--list", "--only", "a,b"])
        self.assertEqual((args.base_url, args.list, args.only), ("http://x:1", True, "a,b"))

    def test_select_scenarios(self):
        names = [name for name, _ in journey.SCENARIOS]
        self.assertEqual(journey.select_scenarios(names, ""), names)
        self.assertEqual(journey.select_scenarios(names, " translate_contract , ask_rate_limit"),
                         ["translate_contract", "ask_rate_limit"])
        with self.assertRaises(ValueError):
            journey.select_scenarios(names, "nope")

    def test_scenario_names_unique(self):
        names = [name for name, _ in journey.SCENARIOS]
        self.assertEqual(len(names), len(set(names)))


class PayloadTest(unittest.TestCase):
    def test_codename_letters_only_and_stable(self):
        first = journey.codename("abc", "ask")
        self.assertEqual(first, journey.codename("abc", "ask"))
        self.assertNotEqual(first, journey.codename("abc", "edit"))
        self.assertNotEqual(first, journey.codename("abd", "ask"))
        self.assertTrue(first.isalpha() and first.startswith("zq") and len(first) == 11)

    def test_bodies(self):
        self.assertEqual(journey.ask_body("Q?"), {"question": "Q?"})
        self.assertEqual(journey.ask_body("Q?", "EN"), {"question": "Q?", "language": "EN"})
        self.assertEqual(journey.summary_body(), {"scope": "LAST_N", "refresh": False, "limit": 5})
        self.assertEqual(journey.summary_body(scope="TODAY"), {"scope": "TODAY", "refresh": False})

    def test_cited_ids(self):
        self.assertEqual(journey.cited_ids(_ask()), ["m1"])
        self.assertEqual(journey.cited_ids(None), [])
        self.assertEqual(journey.cited_ids({"citations": None}), [])


class AskValidatorTest(unittest.TestCase):
    def test_valid_modes(self):
        self.assertIsNone(journey.validate_ask_response(_ask()))
        self.assertIsNone(journey.validate_ask_response(_ask("CACHE", cached=True)))
        self.assertIsNone(journey.validate_ask_response(_ask("LLM", llm_calls=1)))
        self.assertIsNone(journey.validate_ask_response(
            _ask("NOT_FOUND", answer=None, citations=[], omitted=True)))

    def test_invalid(self):
        self.assertIsNotNone(journey.validate_ask_response([]))
        self.assertIsNotNone(journey.validate_ask_response(_ask("WEIRD")))
        self.assertIsNotNone(journey.validate_ask_response(_ask("CACHE", cached=False)))
        self.assertIsNotNone(journey.validate_ask_response(_ask("EXTRACTIVE", cached=True)))
        self.assertIsNotNone(journey.validate_ask_response(_ask("EXTRACTIVE", llm_calls=1)))
        self.assertIsNotNone(journey.validate_ask_response(_ask(citations=[])))
        self.assertIsNotNone(journey.validate_ask_response(_ask(answer="")))
        self.assertIsNotNone(journey.validate_ask_response(_ask("NOT_FOUND")))
        self.assertIsNotNone(journey.validate_ask_response(
            _ask(citations=[{"messageId": "m1", "seq": "3", "snippet": ""}])))
        leaking = _ask()
        leaking["vector"] = [0.1]
        self.assertIsNotNone(journey.validate_ask_response(leaking))


class OtherValidatorsTest(unittest.TestCase):
    def _summary(self, **overrides):
        body = {"summaryId": "s", "conversationId": "c", "generatedBy": "LOCAL",
                "createdAt": "2026-11-19T00:00:00Z", "cached": False, "contextOmitted": False,
                "messageCount": 5, "keyPoints": [], "decisions": [], "actionItems": [],
                "questions": [], "topics": [], "participants": []}
        body.update(overrides)
        return body

    def test_summary(self):
        self.assertIsNone(journey.validate_summary_response(self._summary()))
        self.assertIsNotNone(journey.validate_summary_response(self._summary(generatedBy="X")))
        self.assertIsNotNone(journey.validate_summary_response(self._summary(cached="no")))
        self.assertIsNotNone(journey.validate_summary_response(self._summary(messageCount=0)))
        self.assertIsNotNone(journey.validate_summary_response(self._summary(topics=None)))

    def test_translate(self):
        ok = {"text": "t", "sourceLang": "vi", "targetLang": "vi", "cached": False, "model": None}
        self.assertIsNone(journey.validate_translate_response(ok))
        self.assertIsNotNone(journey.validate_translate_response(dict(ok, text="")))
        self.assertIsNotNone(journey.validate_translate_response(dict(ok, cached=None)))
        self.assertIsNotNone(journey.validate_translate_response("x"))

    def test_classify_summary_purge(self):
        self.assertEqual(journey.classify_summary_purge(False)[0], "PASS")
        self.assertEqual(journey.classify_summary_purge(True)[0], "FAIL")
        verdict, reason = journey.classify_summary_purge(True, assume_pre_138=True)
        self.assertEqual(verdict, "NOT_RUN")
        self.assertIn("#138", reason)

    def test_seconds_until_next_minute(self):
        self.assertAlmostEqual(journey.seconds_until_next_minute(120.0), 61.0)
        self.assertAlmostEqual(journey.seconds_until_next_minute(179.5), 1.5)


if __name__ == "__main__":
    unittest.main()
