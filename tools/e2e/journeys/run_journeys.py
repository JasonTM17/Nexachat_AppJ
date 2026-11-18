#!/usr/bin/env python3
"""Sequential runner for the live E2E journey scripts.

Discovers ``*_e2e.py`` siblings in a fixed order and runs each one as
``python <file> --base-url <url>`` in a subprocess, streaming its output and
tallying the ``PASS ``/``FAIL ``/``NOT_RUN `` result lines it prints. Each
selected journey must exist, exit zero, and report at least one PASS and no
FAIL lines. A timeout also fails the run.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import threading
from dataclasses import dataclass, field

HERE = os.path.dirname(os.path.abspath(__file__))

# Execution and reporting order, matching the journey plan.
JOURNEYS = ["feed_moderation", "ai_privacy", "groups_privacy", "admin_streak"]

RESULT_PREFIXES = {"PASS ": "pass", "FAIL ": "fail", "NOT_RUN ": "not_run"}

DEFAULT_BASE_URL = "http://127.0.0.1:8080"
DEFAULT_TIMEOUT_S = 600


@dataclass
class JourneyResult:
    name: str
    ran: bool
    exit_code: int | None = None
    timed_out: bool = False
    counts: dict = field(default_factory=lambda: {"pass": 0, "fail": 0, "not_run": 0})


def parse_result_line(line: str) -> str | None:
    """Return 'pass' | 'fail' | 'not_run' for a tally line, else None."""
    line = line.lstrip()
    for prefix, bucket in RESULT_PREFIXES.items():
        if line.startswith(prefix):
            return bucket
    return None


def script_path(journey: str) -> str:
    return os.path.join(HERE, f"{journey}_e2e.py")


def selected_journeys(only: str | None) -> list[str]:
    if only is None:
        return list(JOURNEYS)
    wanted = {part.strip() for part in only.split(",") if part.strip()}
    if not wanted:
        raise SystemExit("--only must name at least one journey")
    unknown = sorted(wanted - set(JOURNEYS))
    if unknown:
        raise SystemExit(
            f"--only names unknown journeys: {', '.join(unknown)} "
            f"(known: {', '.join(JOURNEYS)})"
        )
    return [name for name in JOURNEYS if name in wanted]


def run_journey(journey: str, base_url: str, timeout: int,
                stream=sys.stdout) -> JourneyResult:
    """Run one journey subprocess, streaming its stdout and tallying results."""
    result = JourneyResult(name=journey, ran=True)
    cmd = [sys.executable, script_path(journey), "--base-url", base_url]
    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    timed_out = {"hit": False}

    def _kill() -> None:
        timed_out["hit"] = True
        proc.kill()

    watchdog = threading.Timer(timeout, _kill)
    watchdog.start()
    try:
        assert proc.stdout is not None
        for line in proc.stdout:
            stream.write(line)
            stream.flush()
            bucket = parse_result_line(line)
            if bucket:
                result.counts[bucket] += 1
        result.exit_code = proc.wait()
    finally:
        watchdog.cancel()
    result.timed_out = timed_out["hit"]
    if result.timed_out:
        result.exit_code = None
    return result


def collect_results(base_url: str, timeout: int, only: str | None,
                    stream=sys.stdout) -> list[JourneyResult]:
    results = []
    for journey in selected_journeys(only):
        if not os.path.exists(script_path(journey)):
            stream.write(f"NOT_RUN {journey}: {journey}_e2e.py not found\n")
            missing = JourneyResult(name=journey, ran=False)
            missing.counts["not_run"] = 1
            results.append(missing)
            continue
        stream.write(f"== journey: {journey} ==\n")
        results.append(run_journey(journey, base_url, timeout, stream))
    return results


def status_of(result: JourneyResult) -> str:
    if not result.ran:
        return "not_run"
    if result.timed_out:
        return "timeout"
    if result.exit_code != 0:
        return f"exit={result.exit_code}"
    if result.counts["fail"]:
        return "failed"
    if not result.counts["pass"]:
        return "no_pass"
    return "ok"


def summary_rows(results: list[JourneyResult]) -> list[tuple[str, str, str, str, str]]:
    rows = []
    for r in results:
        rows.append((
            r.name,
            str(r.counts["pass"]),
            str(r.counts["fail"]),
            str(r.counts["not_run"]),
            status_of(r),
        ))
    return rows


def render_summary(results: list[JourneyResult]) -> str:
    header = ("journey", "pass", "fail", "not_run", "exit")
    rows = [header] + summary_rows(results)
    widths = [max(len(row[i]) for row in rows) for i in range(len(header))]
    lines = [" | ".join(cell.ljust(widths[i]) for i, cell in enumerate(row)) for row in rows]
    lines.insert(1, "-+-".join("-" * widths[i] for i in range(len(header))))
    return "\n".join(lines)


def compute_exit_code(results: list[JourneyResult]) -> int:
    """Fail when any selected journey lacks passing execution evidence."""
    return 0 if results and all(status_of(result) == "ok" for result in results) else 1


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL,
                        help="backend base URL handed to each journey script")
    parser.add_argument("--only", default=None, metavar="j1,j2",
                        help="comma-separated journey names to run, in fixed order")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_S, metavar="SECONDS",
                        help="per-journey wall-clock budget (default 600)")
    args = parser.parse_args()

    results = collect_results(args.base_url, args.timeout, args.only)
    print()
    print(render_summary(results))
    return compute_exit_code(results)


if __name__ == "__main__":
    raise SystemExit(main())
