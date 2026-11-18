"""Unit tests for run_journeys' pure logic: line parsing, journey selection,
summary rows and the aggregate exit code. No subprocesses or network."""

from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import run_journeys


def make_result(name, ran=True, exit_code=0, timed_out=False,
                passed=0, failed=0, not_run=0):
    result = run_journeys.JourneyResult(name=name, ran=ran, exit_code=exit_code,
                                        timed_out=timed_out)
    result.counts = {"pass": passed, "fail": failed, "not_run": not_run}
    return result


class ParseResultLineTest(unittest.TestCase):
    def test_prefixes(self):
        self.assertEqual(run_journeys.parse_result_line("PASS login works"), "pass")
        self.assertEqual(run_journeys.parse_result_line("FAIL broken thing"), "fail")
        self.assertEqual(
            run_journeys.parse_result_line("NOT_RUN needs a device"), "not_run")

    def test_indented_and_unrelated_lines(self):
        self.assertEqual(
            run_journeys.parse_result_line("  PASS indented"), "pass")
        self.assertIsNone(run_journeys.parse_result_line("PASSWORD is not a prefix"))
        self.assertIsNone(run_journeys.parse_result_line("[PASS] bracketed"))
        self.assertIsNone(run_journeys.parse_result_line(""))
        self.assertIsNone(run_journeys.parse_result_line("random output"))


class SelectedJourneysTest(unittest.TestCase):
    def test_default_order_is_fixed(self):
        self.assertEqual(run_journeys.selected_journeys(None), run_journeys.JOURNEYS)

    def test_only_filters_but_keeps_fixed_order(self):
        self.assertEqual(
            run_journeys.selected_journeys("admin_streak,feed_moderation"),
            ["feed_moderation", "admin_streak"])

    def test_only_rejects_unknown_names(self):
        with self.assertRaises(SystemExit):
            run_journeys.selected_journeys("feed_moderation,bogus")

    def test_only_rejects_empty_selection(self):
        for selection in ("", " , "):
            with self.subTest(selection=selection), self.assertRaises(SystemExit):
                run_journeys.selected_journeys(selection)


class StatusOfTest(unittest.TestCase):
    def test_statuses(self):
        self.assertEqual(run_journeys.status_of(make_result("a", ran=False)), "not_run")
        self.assertEqual(run_journeys.status_of(make_result("b", passed=1)), "ok")
        self.assertEqual(run_journeys.status_of(make_result("c", exit_code=2)), "exit=2")
        self.assertEqual(
            run_journeys.status_of(make_result("d", exit_code=None, timed_out=True)),
            "timeout")


class SummaryRowsTest(unittest.TestCase):
    def test_counts_and_exit(self):
        rows = run_journeys.summary_rows([
            make_result("feed_moderation", passed=10, failed=0, not_run=1),
            make_result("ai_privacy", ran=False),
            make_result("groups_privacy", exit_code=1, passed=3, failed=2),
        ])
        self.assertEqual(rows[0], ("feed_moderation", "10", "0", "1", "ok"))
        self.assertEqual(rows[1], ("ai_privacy", "0", "0", "0", "not_run"))
        self.assertEqual(rows[2], ("groups_privacy", "3", "2", "0", "exit=1"))

    def test_render_includes_header_and_rows(self):
        text = run_journeys.render_summary([make_result("feed_moderation", passed=4)])
        self.assertIn("journey", text)
        self.assertIn("pass", text)
        self.assertIn("feed_moderation", text)
        self.assertIn("exit", text)


class ComputeExitCodeTest(unittest.TestCase):
    def test_success_with_optional_scenario_skip(self):
        results = [make_result("a", passed=5), make_result("b", passed=1, not_run=3)]
        self.assertEqual(run_journeys.compute_exit_code(results), 0)

    def test_nonzero_exit_fails(self):
        results = [make_result("a", passed=1), make_result("b", exit_code=1, passed=1)]
        self.assertEqual(run_journeys.compute_exit_code(results), 1)

    def test_timeout_fails(self):
        results = [make_result("a", exit_code=None, timed_out=True)]
        self.assertEqual(run_journeys.compute_exit_code(results), 1)

    def test_missing_selected_journey_fails(self):
        results = [make_result("a", passed=1), make_result("b", ran=False)]
        self.assertEqual(run_journeys.compute_exit_code(results), 1)

    def test_no_passing_evidence_fails(self):
        for results in ([], [make_result("a")], [make_result("a", not_run=3)]):
            with self.subTest(results=results):
                self.assertEqual(run_journeys.compute_exit_code(results), 1)

    def test_fail_line_fails_even_if_child_exits_zero(self):
        self.assertEqual(
            run_journeys.compute_exit_code([make_result("a", passed=1, failed=1)]), 1)


if __name__ == "__main__":
    unittest.main()
