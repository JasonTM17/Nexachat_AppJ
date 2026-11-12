#!/usr/bin/env python3
"""Unit tests for the controlled reminder clock helper.

Tests the SQL generation, column consistency, refusal on non-local/non-nexachat
targets, UUID validation against SQL injection, timeout handling, and output parsing.
All database access is faked through an injectable psql runner.
"""

from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import uuid

# Ensure tools/e2e is on sys.path regardless of execution working directory
_DIR = Path(__file__).resolve().parent
if str(_DIR) not in sys.path:
    sys.path.insert(0, str(_DIR))

import reminder_clock
from reminder_clock import (
    DEFAULT_CONTAINER,
    DEFAULT_DB_NAME,
    DEFAULT_DB_USER,
    DEFAULT_HOST,
    PsqlExecutionError,
    ReminderClock,
    ReminderNotFoundError,
    ReminderTimeoutError,
    TargetRefusalError,
    derive_occurrence_hex,
    is_local_host,
    make_due,
    occurrence_hex,
    parse_reminder_row,
    show,
    validate_target,
    validate_uuid,
    wait_fired,
)

SAMPLE_UUID = "e7b8f2d0-4251-4e78-b11c-2f928e44c219"
SAMPLE_REMIND_AT = "2026-11-12 14:00:00+00"
SAMPLE_REMIND_MS = 1794492000000


class FakePsqlRunner:
    """Mock psql runner that records invocations and returns canned outputs."""

    def __init__(self, responses: list[str] | str = ""):
        self.queries: list[dict[str, str]] = []
        if isinstance(responses, str):
            self.responses = [responses]
        else:
            self.responses = list(responses)
        self.call_count = 0

    def __call__(
        self,
        sql: str,
        *,
        db_name: str = DEFAULT_DB_NAME,
        container: str = DEFAULT_CONTAINER,
        user: str = DEFAULT_DB_USER,
        host: str = DEFAULT_HOST,
        timeout: float = 10.0,
    ) -> str:
        self.queries.append({
            "sql": sql,
            "db_name": db_name,
            "container": container,
            "user": user,
            "host": host,
        })
        if self.call_count < len(self.responses):
            resp = self.responses[self.call_count]
        else:
            resp = self.responses[-1] if self.responses else ""
        self.call_count += 1
        return resp


class TestReminderClock(unittest.TestCase):

    def test_local_host_detection(self):
        self.assertTrue(is_local_host("localhost"))
        self.assertTrue(is_local_host("127.0.0.1"))
        self.assertTrue(is_local_host("127.0.0.2"))
        self.assertTrue(is_local_host("::1"))
        self.assertTrue(is_local_host("docker"))
        self.assertFalse(is_local_host("192.168.1.100"))
        self.assertFalse(is_local_host("prod.nexachat.io.vn"))
        self.assertFalse(is_local_host("evil.com"))

    def test_refusal_on_non_nexachat_db(self):
        runner = FakePsqlRunner()
        with self.assertRaises(TargetRefusalError) as ctx:
            ReminderClock(db_name="postgres", runner=runner)
        self.assertIn("must contain 'nexachat'", str(ctx.exception))
        self.assertEqual(runner.call_count, 0)

        with self.assertRaises(TargetRefusalError):
            validate_target("production_db", "localhost")

    def test_refusal_on_non_local_host(self):
        runner = FakePsqlRunner()
        with self.assertRaises(TargetRefusalError) as ctx:
            ReminderClock(db_name="nexachat_prod", host="10.0.0.1", runner=runner)
        self.assertIn("target host must be a local host", str(ctx.exception))
        self.assertEqual(runner.call_count, 0)

        with self.assertRaises(TargetRefusalError):
            validate_target("nexachat_remote", "db.example.internal")

    def test_sql_injection_rejection_for_uuids(self):
        runner = FakePsqlRunner()
        clock = ReminderClock(runner=runner)

        injection_payloads = [
            f"{SAMPLE_UUID}'; DROP TABLE conversation_reminders; --",
            "' OR '1'='1",
            "not-a-valid-uuid",
            "../../../etc/passwd",
            "",
            "12345",
        ]
        for payload in injection_payloads:
            with self.subTest(payload=payload):
                with self.assertRaises(ValueError) as ctx:
                    clock.make_due(payload)
                self.assertIn("Invalid reminder UUID", str(ctx.exception))
                with self.assertRaises(ValueError):
                    clock.show(payload)
                with self.assertRaises(ValueError):
                    clock.wait_fired(payload)
                with self.assertRaises(ValueError):
                    clock.occurrence_hex(payload)

        # Ensure no SQL queries were dispatched
        self.assertEqual(runner.call_count, 0)

    def test_make_due_sql_columns_and_consistency(self):
        # Sample response representing the updated due row
        psql_output = (
            f"{SAMPLE_UUID}|2026-11-12 13:55:00+00|NONE|||||true|{SAMPLE_REMIND_MS}\n"
        )
        runner = FakePsqlRunner(psql_output)
        clock = ReminderClock(runner=runner)

        record = clock.make_due(SAMPLE_UUID, minutes_ago=5)

        self.assertEqual(runner.call_count, 1)
        query = runner.queries[0]["sql"]

        # 1. Real table
        self.assertIn("UPDATE conversation_reminders", query)
        # 2. Due time set in past
        self.assertIn("remind_at = now() - (interval '1 minute' * 5)", query)
        # 3. Consistency with reminders_claim_pair constraint ((claimed_at IS NULL) = (claim_token IS NULL))
        self.assertIn("claimed_at = NULL", query)
        self.assertIn("claim_token = NULL", query)
        # 4. Consistency with active scheduler sweep index (WHERE fired_at IS NULL)
        self.assertIn("fired_at = NULL", query)
        self.assertIn("last_fired_at = NULL", query)
        self.assertIn("updated_at = now()", query)
        # 5. Targeted by valid UUID
        self.assertIn(f"WHERE id = '{SAMPLE_UUID}'", query)

        self.assertEqual(record["id"], SAMPLE_UUID)
        self.assertEqual(record["status"], "DUE")
        self.assertIsNone(record["claimed_at"])
        self.assertIsNone(record["fired_at"])

    def test_make_due_not_found(self):
        runner = FakePsqlRunner("")  # empty output = 0 rows updated
        clock = ReminderClock(runner=runner)

        with self.assertRaises(ReminderNotFoundError) as ctx:
            clock.make_due(SAMPLE_UUID)
        self.assertIn("not found in database", str(ctx.exception))

    def test_show_no_message_content(self):
        psql_output = (
            f"{SAMPLE_UUID}|{SAMPLE_REMIND_AT}|NONE|||||false|{SAMPLE_REMIND_MS}\n"
        )
        runner = FakePsqlRunner(psql_output)
        clock = ReminderClock(runner=runner)

        record = clock.show(SAMPLE_UUID)

        # Verify SQL does not query title or content
        query = runner.queries[0]["sql"]
        self.assertNotIn("title", query.lower())
        self.assertNotIn("content", query.lower())

        # Verify returned dictionary does not contain title or content
        self.assertNotIn("title", record)
        self.assertNotIn("content", record)
        self.assertEqual(record["id"], SAMPLE_UUID)
        self.assertEqual(record["remind_at"], SAMPLE_REMIND_AT)
        self.assertEqual(record["status"], "SCHEDULED")

    def test_wait_fired_returns_when_claimed(self):
        claimed_output = (
            f"{SAMPLE_UUID}|{SAMPLE_REMIND_AT}|NONE||2026-11-12 14:02:00+00|token-uuid||true|{SAMPLE_REMIND_MS}\n"
        )
        runner = FakePsqlRunner(claimed_output)
        clock = ReminderClock(runner=runner)

        record = clock.wait_fired(SAMPLE_UUID, timeout=2.0, poll_interval=0.01)
        self.assertEqual(record["status"], "CLAIMED")
        self.assertIsNotNone(record["claimed_at"])
        self.assertEqual(runner.call_count, 1)

    def test_wait_fired_returns_when_fired(self):
        fired_output = (
            f"{SAMPLE_UUID}|{SAMPLE_REMIND_AT}|NONE|2026-11-12 14:03:00+00|||2026-11-12 14:03:00+00|true|{SAMPLE_REMIND_MS}\n"
        )
        runner = FakePsqlRunner(fired_output)
        clock = ReminderClock(runner=runner)

        record = clock.wait_fired(SAMPLE_UUID, timeout=2.0, poll_interval=0.01)
        self.assertEqual(record["status"], "FIRED")
        self.assertIsNotNone(record["fired_at"])
        self.assertEqual(runner.call_count, 1)

    def test_wait_fired_timeout(self):
        # Output that stays SCHEDULED (not fired, not claimed)
        scheduled_output = (
            f"{SAMPLE_UUID}|2026-11-12 15:00:00+00|NONE|||||false|{SAMPLE_REMIND_MS}\n"
        )
        runner = FakePsqlRunner(scheduled_output)
        clock = ReminderClock(runner=runner)

        with self.assertRaises(ReminderTimeoutError) as ctx:
            clock.wait_fired(SAMPLE_UUID, timeout=0.05, poll_interval=0.01)
        self.assertIn("was not marked fired/claimed within 0.1s", str(ctx.exception))
        self.assertIn("current status: SCHEDULED", str(ctx.exception))
        self.assertGreater(runner.call_count, 1)

    def test_parsing_psql_output_statuses(self):
        # 1. Fired
        r1 = parse_reminder_row(
            f"{SAMPLE_UUID}|{SAMPLE_REMIND_AT}|NONE|2026-11-12 14:01:00+00||||true|{SAMPLE_REMIND_MS}",
            "nexachat",
            SAMPLE_UUID,
        )
        self.assertEqual(r1["status"], "FIRED")

        # 2. Claimed
        r2 = parse_reminder_row(
            f"{SAMPLE_UUID}|{SAMPLE_REMIND_AT}|NONE||2026-11-12 14:01:00+00|uuid||true|{SAMPLE_REMIND_MS}",
            "nexachat",
            SAMPLE_UUID,
        )
        self.assertEqual(r2["status"], "CLAIMED")

        # 3. Due
        r3 = parse_reminder_row(
            f"{SAMPLE_UUID}|{SAMPLE_REMIND_AT}|NONE|||||t|{SAMPLE_REMIND_MS}",
            "nexachat",
            SAMPLE_UUID,
        )
        self.assertEqual(r3["status"], "DUE")

        # 4. Scheduled
        r4 = parse_reminder_row(
            f"{SAMPLE_UUID}|{SAMPLE_REMIND_AT}|NONE|||||f|{SAMPLE_REMIND_MS}",
            "nexachat",
            SAMPLE_UUID,
        )
        self.assertEqual(r4["status"], "SCHEDULED")

    def test_parsing_malformed_output(self):
        with self.assertRaises(PsqlExecutionError):
            parse_reminder_row("only|three|columns", "nexachat", SAMPLE_UUID)

    def test_occurrence_hex_derivation(self):
        # Verify derivation against SHA-256 standard calculation
        key = f"{SAMPLE_UUID}|{SAMPLE_REMIND_MS}".encode("utf-8")
        expected_hex = hashlib.sha256(key).hexdigest()[:32]

        derived = derive_occurrence_hex(SAMPLE_UUID, SAMPLE_REMIND_MS)
        self.assertEqual(derived, expected_hex)
        self.assertEqual(len(derived), 32)
        self.assertTrue(all(c in "0123456789abcdef" for c in derived))

        # Test through ReminderClock
        psql_output = (
            f"{SAMPLE_UUID}|{SAMPLE_REMIND_AT}|NONE|||||false|{SAMPLE_REMIND_MS}\n"
        )
        runner = FakePsqlRunner(psql_output)
        clock = ReminderClock(runner=runner)
        self.assertEqual(clock.occurrence_hex(SAMPLE_UUID), expected_hex)

    def test_cli_make_due(self):
        psql_output = (
            f"{SAMPLE_UUID}|2026-11-12 13:59:00+00|NONE|||||true|{SAMPLE_REMIND_MS}\n"
        )
        runner = FakePsqlRunner(psql_output)
        with patch.object(reminder_clock, "_ACTIVE_PSQL_RUNNER", runner):
            with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
                ret = reminder_clock.main(["make-due", SAMPLE_UUID, "--minutes-ago", "2"])
                self.assertEqual(ret, 0)
                self.assertIn(f"Reminder {SAMPLE_UUID} set due", mock_stdout.getvalue())

    def test_cli_show(self):
        psql_output = (
            f"{SAMPLE_UUID}|{SAMPLE_REMIND_AT}|NONE|||||false|{SAMPLE_REMIND_MS}\n"
        )
        runner = FakePsqlRunner(psql_output)
        with patch.object(reminder_clock, "_ACTIVE_PSQL_RUNNER", runner):
            with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
                ret = reminder_clock.main(["show", SAMPLE_UUID])
                self.assertEqual(ret, 0)
                out = mock_stdout.getvalue()
                self.assertIn(f"id:       {SAMPLE_UUID}", out)
                self.assertIn(f"due_time: {SAMPLE_REMIND_AT}", out)
                self.assertIn("status:   SCHEDULED", out)
                self.assertNotIn("title", out.lower())

    def test_cli_show_json(self):
        psql_output = (
            f"{SAMPLE_UUID}|{SAMPLE_REMIND_AT}|NONE|||||false|{SAMPLE_REMIND_MS}\n"
        )
        runner = FakePsqlRunner(psql_output)
        with patch.object(reminder_clock, "_ACTIVE_PSQL_RUNNER", runner):
            with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
                ret = reminder_clock.main(["show", SAMPLE_UUID, "--json"])
                self.assertEqual(ret, 0)
                parsed = json.loads(mock_stdout.getvalue())
                self.assertEqual(parsed["id"], SAMPLE_UUID)
                self.assertEqual(parsed["due_time"], SAMPLE_REMIND_AT)
                self.assertEqual(parsed["status"], "SCHEDULED")

    def test_cli_occurrence(self):
        psql_output = (
            f"{SAMPLE_UUID}|{SAMPLE_REMIND_AT}|NONE|||||false|{SAMPLE_REMIND_MS}\n"
        )
        runner = FakePsqlRunner(psql_output)
        expected = derive_occurrence_hex(SAMPLE_UUID, SAMPLE_REMIND_MS)
        with patch.object(reminder_clock, "_ACTIVE_PSQL_RUNNER", runner):
            with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
                ret = reminder_clock.main(["occurrence", SAMPLE_UUID])
                self.assertEqual(ret, 0)
                self.assertEqual(mock_stdout.getvalue().strip(), expected)


if __name__ == "__main__":
    unittest.main()
