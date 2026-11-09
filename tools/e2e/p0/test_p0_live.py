"""Offline regression tests for required live-suite skips and FCM row semantics."""

from __future__ import annotations

from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

import p0_live


class _Response:
    def __init__(self, status: int):
        self.status = status


class _NotFoundClient:
    def post(self, *_args, **_kwargs):
        return _Response(404)


class P0LiveExitCodeTest(unittest.TestCase):
    def test_offline_required_scenarios_fail_unless_allow_skip_is_set(self):
        for extra_args, expected in (([], 1), (["--allow-skip"], 0)):
            output = StringIO()
            with patch.object(p0_live, "check_server_health", return_value=False), \
                    patch.object(sys, "argv", ["p0_live.py", *extra_args]), \
                    redirect_stdout(output):
                result = p0_live.main()
            self.assertEqual(expected, result)
            self.assertIn("Server at", output.getvalue())

    def test_required_404_skips_fail_unless_allow_skip_is_set(self):
        client = _NotFoundClient()
        scenarios = (
            lambda logger: p0_live.run_sync_scenario(
                logger, client, {"token": "a"}, {"token": "b"}, "conversation"
            ),
            lambda logger: p0_live.run_fcm_scenario(
                logger, client, {"token": "a"}, {"token": "b"}
            ),
        )
        for run_scenario in scenarios:
            for allow_skip, expected in ((False, 1), (True, 0)):
                logger = p0_live.TestLogger(allow_skip=allow_skip)
                output = StringIO()
                with redirect_stdout(output):
                    run_scenario(logger)
                    result = logger.summary()
                self.assertEqual(expected, result)
                self.assertIn("SKIP", output.getvalue())

    def test_per_user_fcm_rows_require_distinct_stable_ids(self):
        self.assertTrue(p0_live.per_user_device_rows_preserved("a1", "b1", "a1", "b1"))
        self.assertFalse(p0_live.per_user_device_rows_preserved("a1", "a1", "a1", "a1"))
        self.assertFalse(p0_live.per_user_device_rows_preserved("a1", "b1", "a2", "b1"))


if __name__ == "__main__":
    unittest.main()
