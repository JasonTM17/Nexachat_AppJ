#!/usr/bin/env python3
"""Controlled reminder clock helper for NexaChat E2E tests.

Moves a reminder's due time in the local database to make occurrences come due
on demand without waiting minutes for the scheduler or touching production code.

Standard library only. Database access runs through `docker exec <container> psql`.

Safety rules:
- Refuses any database name that does not contain 'nexachat'.
- Refuses any target host that is not local (e.g. localhost, 127.0.0.1, ::1).
- Validates all reminder IDs as UUIDs to prevent SQL injection.

Usage:
    python tools/e2e/reminder_clock.py make-due <reminder_id> [--minutes-ago 1]
    python tools/e2e/reminder_clock.py wait-fired <reminder_id> [--timeout 10]
    python tools/e2e/reminder_clock.py show <reminder_id>
    python tools/e2e/reminder_clock.py occurrence <reminder_id>
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
from typing import Any, Callable, Dict, Optional
import uuid

DEFAULT_CONTAINER = "nexachat-db-1"
DEFAULT_DB_NAME = "nexachat"
DEFAULT_DB_USER = "nexachat"
DEFAULT_HOST = "localhost"
DEFAULT_TIMEOUT = 10.0
DEFAULT_POLL_INTERVAL = 0.5

_IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9_.-]+$")
_LOCAL_HOSTS = frozenset({"localhost", "127.0.0.1", "::1", "local", "docker"})


class ReminderClockError(Exception):
    """Base error for reminder clock operations."""


class TargetRefusalError(ReminderClockError, ValueError):
    """Raised when target database or host fails safety verification."""


class ReminderNotFoundError(ReminderClockError, ValueError):
    """Raised when the specified reminder ID does not exist in the database."""


class PsqlExecutionError(ReminderClockError, RuntimeError):
    """Raised when psql execution fails."""


class ReminderTimeoutError(ReminderClockError, TimeoutError):
    """Raised when wait_fired times out."""


def is_local_host(host: str) -> bool:
    """Checks whether the host represents a local machine or container."""
    if not isinstance(host, str):
        return False
    normalized = host.strip().lower()
    if normalized in _LOCAL_HOSTS:
        return True
    if normalized.startswith("127."):
        return True
    return False


def validate_target(db_name: str, host: str) -> None:
    """Refuses unless the DB name contains 'nexachat' and host is local."""
    if not isinstance(db_name, str) or "nexachat" not in db_name.lower():
        raise TargetRefusalError(
            f"Refusing database name {db_name!r}; name must contain 'nexachat'"
        )
    if not is_local_host(host):
        raise TargetRefusalError(
            f"Refusing database host {host!r}; target host must be a local host"
        )


def validate_safe_identifier(label: str, value: str) -> str:
    """Ensures container and user names do not contain shell metacharacters."""
    if not isinstance(value, str) or not _IDENTIFIER_RE.fullmatch(value):
        raise ValueError(f"Invalid safe identifier for {label}: {value!r}")
    return value


def validate_uuid(reminder_id: str) -> str:
    """Validates that reminder_id is a valid UUID, preventing SQL injection."""
    try:
        parsed = uuid.UUID(str(reminder_id))
        return str(parsed)
    except (ValueError, AttributeError, TypeError) as err:
        raise ValueError(f"Invalid reminder UUID: {reminder_id!r}") from err


def derive_occurrence_hex(reminder_id: str, remind_at_ms: int) -> str:
    """Derives the 32-hex occurrence ID carried in reminder push payloads.

    Matches FcmReminderPushSender.occurrenceKey:
    SHA-256(reminderId + '|' + remindAt.toEpochMilli()) formatted as first 16 bytes
    (32 lowercase hex characters).
    """
    valid_id = validate_uuid(reminder_id)
    key = f"{valid_id}|{int(remind_at_ms)}".encode("utf-8")
    return hashlib.sha256(key).hexdigest()[:32]


def execute_psql_docker(
    sql: str,
    *,
    db_name: str = DEFAULT_DB_NAME,
    container: str = DEFAULT_CONTAINER,
    user: str = DEFAULT_DB_USER,
    host: str = DEFAULT_HOST,
    timeout: float = DEFAULT_TIMEOUT,
) -> str:
    """Default database execution runner via `docker exec <container> psql`."""
    validate_target(db_name=db_name, host=host)
    validate_safe_identifier("container", container)
    validate_safe_identifier("user", user)

    command = [
        "docker", "exec", "-i", container,
        "psql", "-U", user, "-d", db_name,
        "-v", "ON_ERROR_STOP=1", "-tA", "-F", "|"
    ]
    try:
        completed = subprocess.run(
            command,
            input=sql.encode("utf-8"),
            capture_output=True,
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise PsqlExecutionError(f"psql execution timed out after {timeout}s") from exc
    except OSError as exc:
        raise PsqlExecutionError(f"Failed to spawn docker process: {exc}") from exc

    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", "replace").strip()
        raise PsqlExecutionError(f"psql exited with code {completed.returncode}: {detail}")

    return completed.stdout.decode("utf-8", "replace")


# Injectable default runner hook
_ACTIVE_PSQL_RUNNER: Callable[..., str] = execute_psql_docker


def set_active_psql_runner(runner: Callable[..., str]) -> None:
    """Sets the active module-level psql runner (used for tests or mocks)."""
    global _ACTIVE_PSQL_RUNNER
    _ACTIVE_PSQL_RUNNER = runner


def parse_reminder_row(output: str, db_name: str, reminder_id: str) -> Dict[str, Any]:
    """Parses pipe-delimited psql output into a reminder dict."""
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if not lines:
        raise ReminderNotFoundError(
            f"Reminder {reminder_id} not found in database {db_name}"
        )

    # Use first result line
    fields = lines[0].split("|")
    if len(fields) < 9:
        raise PsqlExecutionError(
            f"Unexpected psql output format ({len(fields)} fields): {lines[0]!r}"
        )

    row_id = fields[0]
    remind_at = fields[1]
    repeat_rule = fields[2]
    fired_at = fields[3] or None
    claimed_at = fields[4] or None
    claim_token = fields[5] or None
    last_fired_at = fields[6] or None
    is_due = fields[7].lower() in ("t", "true", "1")
    remind_at_ms = int(fields[8]) if fields[8] else None

    # Derive lifecycle status:
    # 1. FIRED: one-off reminder completed (fired_at is set)
    # 2. CLAIMED: scheduler currently holds the lease (claimed_at is set)
    # 3. DUE: remind_at is in the past, eligible for claim sweep
    # 4. SCHEDULED: upcoming in the future
    if fired_at is not None:
        status = "FIRED"
    elif claimed_at is not None:
        status = "CLAIMED"
    elif is_due:
        status = "DUE"
    else:
        status = "SCHEDULED"

    return {
        "id": row_id,
        "remind_at": remind_at,
        "repeat_rule": repeat_rule,
        "fired_at": fired_at,
        "claimed_at": claimed_at,
        "claim_token": claim_token,
        "last_fired_at": last_fired_at,
        "is_due": is_due,
        "remind_at_ms": remind_at_ms,
        "status": status,
    }


class ReminderClock:
    """Manages reminder clock adjustments and occurrence monitoring."""

    def __init__(
        self,
        db_name: str = DEFAULT_DB_NAME,
        container: str = DEFAULT_CONTAINER,
        user: str = DEFAULT_DB_USER,
        host: str = DEFAULT_HOST,
        timeout: float = DEFAULT_TIMEOUT,
        runner: Optional[Callable[..., str]] = None,
    ):
        validate_target(db_name=db_name, host=host)
        validate_safe_identifier("container", container)
        validate_safe_identifier("user", user)
        self.db_name = db_name
        self.container = container
        self.user = user
        self.host = host
        self.timeout = timeout
        self.runner = runner or _ACTIVE_PSQL_RUNNER

    def _query(self, sql: str) -> str:
        return self.runner(
            sql,
            db_name=self.db_name,
            container=self.container,
            user=self.user,
            host=self.host,
            timeout=self.timeout,
        )

    def get_reminder(self, reminder_id: str) -> Dict[str, Any]:
        """Fetches reminder state (no user message/title content)."""
        valid_id = validate_uuid(reminder_id)
        sql = (
            "SELECT id, remind_at, repeat_rule, fired_at, claimed_at, claim_token, last_fired_at, "
            "(remind_at <= now())::text AS is_due, "
            "(EXTRACT(EPOCH FROM remind_at) * 1000)::bigint AS remind_at_ms "
            f"FROM conversation_reminders WHERE id = '{valid_id}';"
        )
        output = self._query(sql)
        return parse_reminder_row(output, self.db_name, valid_id)

    def make_due(self, reminder_id: str, minutes_ago: int = 1) -> Dict[str, Any]:
        """Sets the next occurrence / due time so the scheduler claims it on its next sweep.

        Maintains database consistency:
        - remind_at = now() - interval '1 minute' * minutes_ago
        - claimed_at = NULL and claim_token = NULL (satisfies reminders_claim_pair constraint
          and ensures scheduler lockDueIds FOR UPDATE SKIP LOCKED claims it immediately)
        - fired_at = NULL (makes it active so idx_reminders_due and lockDueIds include it)
        - last_fired_at = NULL (clears previous firing timestamp)
        - updated_at = now()
        """
        valid_id = validate_uuid(reminder_id)
        if not isinstance(minutes_ago, (int, float)) or minutes_ago < 0:
            raise ValueError(f"minutes_ago must be non-negative, got {minutes_ago!r}")

        # Build parameterized SQL
        sql = (
            "UPDATE conversation_reminders "
            f"SET remind_at = now() - (interval '1 minute' * {int(minutes_ago)}), "
            "    claimed_at = NULL, "
            "    claim_token = NULL, "
            "    fired_at = NULL, "
            "    last_fired_at = NULL, "
            "    updated_at = now() "
            f"WHERE id = '{valid_id}' "
            "RETURNING id, remind_at, repeat_rule, fired_at, claimed_at, claim_token, last_fired_at, "
            "(remind_at <= now())::text AS is_due, "
            "(EXTRACT(EPOCH FROM remind_at) * 1000)::bigint AS remind_at_ms;"
        )
        output = self._query(sql)
        return parse_reminder_row(output, self.db_name, valid_id)

    def wait_fired(
        self,
        reminder_id: str,
        timeout: float = DEFAULT_TIMEOUT,
        poll_interval: float = DEFAULT_POLL_INTERVAL,
    ) -> Dict[str, Any]:
        """Polls until the occurrence is marked fired or claimed, bounded by timeout."""
        valid_id = validate_uuid(reminder_id)
        deadline = time.monotonic() + timeout
        last_record: Optional[Dict[str, Any]] = None

        while True:
            record = self.get_reminder(valid_id)
            last_record = record
            # Marked fired or claimed:
            # 1. claimed_at is set (scheduler in-flight lease)
            # 2. fired_at is set (one-off reminder completed)
            # 3. last_fired_at is set (repeating reminder advanced or one-off completed)
            if (
                record["claimed_at"] is not None
                or record["fired_at"] is not None
                or record["last_fired_at"] is not None
                or record["status"] in ("FIRED", "CLAIMED")
            ):
                return record

            if time.monotonic() >= deadline:
                break

            sleep_duration = min(poll_interval, max(0.0, deadline - time.monotonic()))
            if sleep_duration > 0:
                time.sleep(sleep_duration)

        current_status = last_record["status"] if last_record else "UNKNOWN"
        raise ReminderTimeoutError(
            f"Reminder {valid_id} was not marked fired/claimed within {timeout:.1f}s "
            f"(current status: {current_status})"
        )

    def occurrence_hex(self, reminder_id: str) -> str:
        """Returns the 32-hex occurrence id carried by FCM push payloads.

        Derivable from the database row using:
        SHA-256(reminderId + '|' + remindAt.toEpochMilli())[:16].hex()
        matching FcmReminderPushSender.occurrenceKey.
        """
        record = self.get_reminder(reminder_id)
        remind_at_ms = record.get("remind_at_ms")
        if remind_at_ms is None:
            raise ValueError(
                f"Cannot derive occurrence hex for reminder {reminder_id}: remind_at timestamp is null"
            )
        return derive_occurrence_hex(record["id"], remind_at_ms)

    def show(self, reminder_id: str) -> Dict[str, Any]:
        """Returns reminder metadata (id, due time, status; no message content)."""
        return self.get_reminder(reminder_id)


# Module-level convenience functions
def make_due(
    reminder_id: str,
    minutes_ago: int = 1,
    *,
    db_name: str = DEFAULT_DB_NAME,
    container: str = DEFAULT_CONTAINER,
    user: str = DEFAULT_DB_USER,
    host: str = DEFAULT_HOST,
    runner: Optional[Callable[..., str]] = None,
) -> Dict[str, Any]:
    clock = ReminderClock(
        db_name=db_name, container=container, user=user, host=host, runner=runner
    )
    return clock.make_due(reminder_id, minutes_ago=minutes_ago)


def wait_fired(
    reminder_id: str,
    timeout: float = DEFAULT_TIMEOUT,
    poll_interval: float = DEFAULT_POLL_INTERVAL,
    *,
    db_name: str = DEFAULT_DB_NAME,
    container: str = DEFAULT_CONTAINER,
    user: str = DEFAULT_DB_USER,
    host: str = DEFAULT_HOST,
    runner: Optional[Callable[..., str]] = None,
) -> Dict[str, Any]:
    clock = ReminderClock(
        db_name=db_name, container=container, user=user, host=host, runner=runner
    )
    return clock.wait_fired(reminder_id, timeout=timeout, poll_interval=poll_interval)


def occurrence_hex(
    reminder_id: str,
    *,
    db_name: str = DEFAULT_DB_NAME,
    container: str = DEFAULT_CONTAINER,
    user: str = DEFAULT_DB_USER,
    host: str = DEFAULT_HOST,
    runner: Optional[Callable[..., str]] = None,
) -> str:
    clock = ReminderClock(
        db_name=db_name, container=container, user=user, host=host, runner=runner
    )
    return clock.occurrence_hex(reminder_id)


def show(
    reminder_id: str,
    *,
    db_name: str = DEFAULT_DB_NAME,
    container: str = DEFAULT_CONTAINER,
    user: str = DEFAULT_DB_USER,
    host: str = DEFAULT_HOST,
    runner: Optional[Callable[..., str]] = None,
) -> Dict[str, Any]:
    clock = ReminderClock(
        db_name=db_name, container=container, user=user, host=host, runner=runner
    )
    return clock.show(reminder_id)


def build_parser() -> argparse.ArgumentParser:
    """Builds the CLI argument parser."""
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )

    # Shared DB target flags
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument(
        "--container", default=DEFAULT_CONTAINER, help=f"DB container (default: {DEFAULT_CONTAINER})"
    )
    common.add_argument(
        "--db-name", default=DEFAULT_DB_NAME, help=f"Postgres database (default: {DEFAULT_DB_NAME})"
    )
    common.add_argument(
        "--db-user", default=DEFAULT_DB_USER, help=f"Postgres user (default: {DEFAULT_DB_USER})"
    )
    common.add_argument(
        "--db-host", default=DEFAULT_HOST, help=f"Host target check (default: {DEFAULT_HOST})"
    )

    subparsers = parser.add_subparsers(dest="command", required=True)

    # make-due
    p_make = subparsers.add_parser("make-due", parents=[common], help="Make reminder due now")
    p_make.add_argument("reminder_id", help="UUID of reminder to move due")
    p_make.add_argument(
        "--minutes-ago", type=int, default=1, help="Minutes in the past to set remind_at (default: 1)"
    )

    # wait-fired
    p_wait = subparsers.add_parser(
        "wait-fired", parents=[common], help="Wait until occurrence is claimed or fired"
    )
    p_wait.add_argument("reminder_id", help="UUID of reminder to wait for")
    p_wait.add_argument(
        "--timeout", type=float, default=DEFAULT_TIMEOUT, help=f"Timeout in seconds (default: {DEFAULT_TIMEOUT})"
    )
    p_wait.add_argument(
        "--poll-interval", type=float, default=DEFAULT_POLL_INTERVAL, help="Polling interval in seconds"
    )

    # show
    p_show = subparsers.add_parser(
        "show", parents=[common], help="Show reminder id, due time and status (no message content)"
    )
    p_show.add_argument("reminder_id", help="UUID of reminder to show")
    p_show.add_argument("--json", action="store_true", help="Output in JSON format")

    # occurrence
    p_occ = subparsers.add_parser(
        "occurrence", parents=[common], help="Derive the 32-hex occurrence id for push injection"
    )
    p_occ.add_argument("reminder_id", help="UUID of reminder")

    return parser


def main(argv: Optional[list[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])

    try:
        clock = ReminderClock(
            db_name=args.db_name,
            container=args.container,
            user=args.db_user,
            host=args.db_host,
        )

        if args.command == "make-due":
            record = clock.make_due(args.reminder_id, minutes_ago=args.minutes_ago)
            print(f"Reminder {record['id']} set due at {record['remind_at']} (status: {record['status']})")
            return 0

        elif args.command == "wait-fired":
            record = clock.wait_fired(
                args.reminder_id, timeout=args.timeout, poll_interval=args.poll_interval
            )
            print(
                f"Reminder {record['id']} reached status {record['status']} "
                f"(fired_at: {record['fired_at']}, claimed_at: {record['claimed_at']}, "
                f"last_fired_at: {record['last_fired_at']})"
            )
            return 0

        elif args.command == "show":
            record = clock.show(args.reminder_id)
            if args.json:
                print(json.dumps({
                    "id": record["id"],
                    "due_time": record["remind_at"],
                    "status": record["status"],
                }, indent=2))
            else:
                print(f"id:       {record['id']}")
                print(f"due_time: {record['remind_at']}")
                print(f"status:   {record['status']}")
            return 0

        elif args.command == "occurrence":
            hex_id = clock.occurrence_hex(args.reminder_id)
            print(hex_id)
            return 0

        else:
            parser.print_help(sys.stderr)
            return 2

    except ReminderClockError as err:
        print(f"reminder_clock: error: {err}", file=sys.stderr)
        return 1
    except ValueError as err:
        print(f"reminder_clock: error: {err}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
