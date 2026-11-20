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


class TranslateBudgetTest(unittest.TestCase):
    def test_free_lanes_never_charge(self):
        budget = journey.TranslateBudget(per_minute=2, per_day=2)
        for _ in range(5):
            self.assertEqual(budget.attempt(charge=False), "free")
        self.assertEqual((budget.minute_used, budget.day_used), (0, 0))

    def test_charged_attempts_spend_both_windows(self):
        budget = journey.TranslateBudget(per_minute=3, per_day=5)
        self.assertEqual([budget.attempt() for _ in range(3)],
                         ["allowed", "allowed", "allowed"])
        self.assertEqual((budget.minute_used, budget.day_used), (3, 3))

    def test_minute_refusal_never_touches_the_day_window(self):
        budget = journey.TranslateBudget(per_minute=1, per_day=5)
        budget.attempt()
        self.assertEqual(budget.attempt(), "minute_refused")
        self.assertEqual(budget.attempt(), "minute_refused")
        self.assertEqual((budget.minute_used, budget.day_used), (1, 1))

    def test_day_refusal_refunds_the_minute_token(self):
        budget = journey.TranslateBudget(per_minute=3, per_day=2)
        self.assertEqual([budget.attempt() for _ in range(2)], ["allowed", "allowed"])
        # The minute window still has capacity, but the day window refuses:
        # the minute token is consumed and then refunded, so the minute count
        # stays put — repeated refusals never leak a token.
        for _ in range(3):
            self.assertEqual(budget.attempt(), "day_refused")
        self.assertEqual((budget.minute_used, budget.day_used), (2, 2))

    def test_free_lanes_work_after_refusals(self):
        budget = journey.TranslateBudget(per_minute=1, per_day=1)
        budget.attempt()
        self.assertEqual(budget.attempt(), "minute_refused")
        self.assertEqual(budget.attempt(charge=False), "free")


class RevokeBatchSimulationTest(unittest.TestCase):
    def test_revoke_before_the_batch_refuses_every_charged_item(self):
        self.assertEqual(
            journey.simulate_revoke_mid_batch(
                ["charged", "charged", "charged", "shortcut"], revoke_after=0),
            ["AI_CONSENT_REQUIRED", "AI_CONSENT_REQUIRED",
             "AI_CONSENT_REQUIRED", "shortcut"])

    def test_revoke_after_two_generations_refuses_the_rest(self):
        self.assertEqual(
            journey.simulate_revoke_mid_batch(["charged"] * 4, revoke_after=2),
            ["translated", "translated", "AI_CONSENT_REQUIRED", "AI_CONSENT_REQUIRED"])

    def test_free_lanes_ignore_the_revoke(self):
        self.assertEqual(
            journey.simulate_revoke_mid_batch(
                ["cached", "shortcut", "charged", "charged"], revoke_after=1),
            ["cached", "shortcut", "translated", "AI_CONSENT_REQUIRED"])

    def test_no_revoke_translates_every_charged_item(self):
        for revoke_after in (3, 9):
            self.assertEqual(
                journey.simulate_revoke_mid_batch(["charged"] * 3, revoke_after=revoke_after),
                ["translated"] * 3)

    def test_outcomes_match_the_item_count(self):
        items = ["charged", "shortcut", "charged", "cached", "charged"]
        self.assertEqual(len(journey.simulate_revoke_mid_batch(items, 2)), len(items))


class BatchItemOutcomeTest(unittest.TestCase):
    def test_success_and_refusal_shapes(self):
        self.assertEqual(journey.batch_item_outcome(
            {"messageId": "m1", "text": "salut", "error": None}), "translated")
        self.assertEqual(journey.batch_item_outcome(
            {"messageId": "m1", "text": None, "error": "RATE_LIMITED"}),
            "refused:RATE_LIMITED")

    def test_malformed_shapes(self):
        for item in (None, {}, {"text": "x", "error": None},
                     {"messageId": "m", "text": "x", "error": "E"},
                     {"messageId": "m", "text": None, "error": None}):
            self.assertEqual(journey.batch_item_outcome(item), "malformed")


if __name__ == "__main__":
    unittest.main()
