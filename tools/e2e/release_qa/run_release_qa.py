"""Unified 4-tier Release QA & Live Verification CLI orchestrator for NexaChat.

Usage:
    python tools/e2e/release_qa/run_release_qa.py --list
    python tools/e2e/release_qa/run_release_qa.py --self-test
    python tools/e2e/release_qa/run_release_qa.py --mode local --tier 1,2,3,4
    python tools/e2e/release_qa/run_release_qa.py --mode live --base-url https://api.nexachat.io.vn --site-url https://nexachat.io.vn
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import unittest
from collections import Counter
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Set, Tuple

E2E_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
if str(E2E_ROOT) not in sys.path:
    sys.path.insert(0, str(E2E_ROOT))

from tools.e2e.release_qa import (  # noqa: E402
    FEATURE_NAMES,
    BlockedCapability,
    E2EFailure,
    PendingMilestone,
    ReleaseCheck,
    ReleaseCheckOutcome,
    ReleaseQaContext,
    SafetyGuardError,
    SocialApiClient,
    is_loopback_url,
    redact_secrets,
)
from tools.e2e.release_qa.live_verify import (  # noqa: E402
    DEFAULT_LIVE_API_URL,
    DEFAULT_LIVE_SITE_URL,
)
from tools.e2e.release_qa.tier1_2_platform_api import (  # noqa: E402
    get_tier1_checks,
    get_tier2_checks,
)
from tools.e2e.release_qa.tier3_4_cross_surface import (  # noqa: E402
    get_tier3_checks,
    get_tier4_checks,
)


def get_all_checks() -> List[ReleaseCheck]:
    return [
        *get_tier1_checks(),
        *get_tier2_checks(),
        *get_tier3_checks(),
        *get_tier4_checks(),
    ]


def validate_target_safety(
    mode: str,
    base_url: Optional[str],
    site_url: Optional[str],
) -> None:
    """Refuses non-loopback targets in --mode local (must exit 2)."""
    if mode == "local":
        if base_url and not is_loopback_url(base_url):
            raise SafetyGuardError(
                f"Refusing non-loopback --base-url {redact_secrets(base_url)!r} in --mode local; use --mode live"
            )
        if site_url and not is_loopback_url(site_url):
            raise SafetyGuardError(
                f"Refusing non-loopback --site-url {redact_secrets(site_url)!r} in --mode local; use --mode live"
            )


def parse_tiers(tier_arg: str) -> Set[int]:
    raw = (tier_arg or "").strip().lower()
    if raw in ("", "all"):
        return {1, 2, 3, 4}
    tiers: Set[int] = set()
    for part in raw.split(","):
        token = part.strip()
        if not token:
            continue
        if token not in ("1", "2", "3", "4"):
            raise ValueError(f"Invalid tier {token!r}; expected 1..4 or all")
        tiers.add(int(token))
    if not tiers:
        raise ValueError("No valid tiers selected")
    return tiers


def filter_checks(
    checks: Sequence[ReleaseCheck],
    *,
    tiers: Set[int],
    only: Optional[str] = None,
) -> List[ReleaseCheck]:
    selected = [c for c in checks if c.tier in tiers]
    if only:
        needles = [n.strip().lower() for n in only.split(",") if n.strip()]
        selected = [
            c
            for c in selected
            if any(
                n in c.check_id.lower()
                or n == c.feature.lower()
                or n in c.title.lower()
                for n in needles
            )
        ]
    return selected


def filter_live_safe_checks(checks: Sequence[ReleaseCheck]) -> Tuple[List[ReleaseCheck], List[ReleaseCheck]]:
    """Partitions checks into (runnable_live, skipped_mutating) for live safety."""
    runnable = [c for c in checks if c.live_safe]
    skipped = [c for c in checks if not c.live_safe]
    return runnable, skipped


def evaluate_exit_code(
    outcomes: Sequence[ReleaseCheckOutcome],
    *,
    strict_capabilities: bool = False,
) -> int:
    if any(o.status == "FAIL" for o in outcomes):
        return 1
    if strict_capabilities and any(o.status == "BLOCKED_CAPABILITY" for o in outcomes):
        return 1
    return 0


def format_list_output(checks: Sequence[ReleaseCheck]) -> str:
    lines: List[str] = []
    for c in checks:
        scope = "live-safe" if c.live_safe else "local-only"
        lines.append(f"{c.check_id}\tT{c.tier}\t{c.feature}\t{scope}\t{c.title}")

    lines.append("")
    lines.append("Feature Coverage Matrix (F1..F16):")
    lines.append("Feature\tT1\tT2\tT3\tT4\tTotal\tName")
    for idx in range(1, 17):
        feat = f"F{idx}"
        t1 = sum(1 for c in checks if c.feature == feat and c.tier == 1)
        t2 = sum(1 for c in checks if c.feature == feat and c.tier == 2)
        t3 = sum(1 for c in checks if c.feature == feat and c.tier == 3)
        t4 = sum(1 for c in checks if c.feature == feat and c.tier == 4)
        tot = t1 + t2 + t3 + t4
        lines.append(f"{feat}\t{t1}\t{t2}\t{t3}\t{t4}\t{tot}\t{FEATURE_NAMES[feat]}")

    tier_counts = Counter(c.tier for c in checks)
    lines.append("")
    lines.append(
        f"Totals: T1={tier_counts[1]} T2={tier_counts[2]} T3={tier_counts[3]} "
        f"T4={tier_counts[4]} Total={len(checks)}"
    )
    return "\n".join(lines)


class LocalQaEnv:
    """Provisions local DisposableStack + MailSink + VercelProxyServer for Release QA."""

    def __init__(
        self,
        *,
        base_url: Optional[str] = None,
        site_url: Optional[str] = None,
        jar: Optional[str] = None,
        rebuild_jar: bool = False,
        keep_stack_on_fail: bool = False,
    ) -> None:
        self.base_url = base_url.rstrip("/") if base_url else None
        self.site_url = site_url.rstrip("/") if site_url else None
        self.jar = jar
        self.rebuild_jar = rebuild_jar
        self.keep_stack_on_fail = keep_stack_on_fail
        self._harness: Any = None
        self.mail_sink: Any = None
        self._started_mail_sink = False
        self.db_info: Optional[Dict[str, str]] = None
        self.spring_base_url: str = ""
        self.proxy_base_url: str = ""
        self.admin_base_url: str = ""
        self.sessions: Any = None

    def __enter__(self) -> "LocalQaEnv":
        from tools.e2e import disposable_stack as ds
        from tools.e2e import mail_sink
        from tools.e2e.social_suite.fixtures import ADMIN_PHONES_CSV
        from tools.e2e.social_suite.stack_harness import (
            StackHarness,
            find_fallback_jdk_bins,
            resolve_executable_jar,
        )

        # Best-effort Mailpit start for local email-code & OTP checks
        mail_env: Dict[str, str] = {}
        try:
            mail_sink.start()
            mail_env = mail_sink.server_env()
            self.mail_sink = mail_sink
            self._started_mail_sink = True
        except Exception:
            self.mail_sink = None

        extra_env = {
            "NEXACHAT_AUTH_SIGN_IN_BUDGET_MAX_FAILURES": "4",
            "NEXACHAT_TRUSTED_PROXY_CIDRS": "127.0.0.1/32",
            **mail_env,
        }
        extra_java_args = [
            *ds.DEFAULT_EXTRA_JAVA_ARGS,
            "--nexachat.rate-limit.scheduled.create-per-minute=100",
        ]
        parent_env = self

        class _ReleaseStackHarness(StackHarness):
            def _start_disposable_backend(self) -> None:
                assert self.run_dir is not None
                jar_path = resolve_executable_jar(self.repo_root, self.run_dir, self.explicit_jar)
                self.stack = ds.DisposableStack(
                    str(self.run_dir),
                    server_target=str(self.repo_root / "server" / "target"),
                )
                isolated_port = ds.find_free_port(port_range=(18200, 18699))
                state = self.stack.provision(
                    app_port=isolated_port,
                    admin_phones=ADMIN_PHONES_CSV,
                    extra_env=extra_env,
                    extra_java_args=extra_java_args,
                )
                self.spring_base_url = state["base_url"]
                parent_env.db_info = {
                    "container": state["container"],
                    "db_name": state["db_name"],
                    "db_user": state["db_user"],
                }

                orig_path = os.environ.get("PATH", "")
                try:
                    self.stack.start(jar=str(jar_path), readiness_timeout=110.0)
                except ds.DisposableStackError as first_err:
                    fallback_bins = find_fallback_jdk_bins()
                    if not fallback_bins:
                        raise first_err
                    last_err: BaseException = first_err
                    for jdk_bin in fallback_bins:
                        os.environ["PATH"] = str(jdk_bin) + os.pathsep + orig_path
                        try:
                            self.stack.start(jar=str(jar_path), readiness_timeout=110.0)
                            return
                        except ds.DisposableStackError as err:
                            last_err = err
                    os.environ["PATH"] = orig_path
                    raise last_err

        self._harness = _ReleaseStackHarness(
            base_url=self.base_url,
            jar=self.jar,
            rebuild_jar=self.rebuild_jar,
            keep_stack_on_fail=self.keep_stack_on_fail,
            i_know_this_is_not_prod=False,
        )
        self._harness.__enter__()
        self.spring_base_url = self._harness.spring_base_url
        self.proxy_base_url = self.site_url or self._harness.proxy_base_url
        self.admin_base_url = self._harness.admin_base_url
        self.sessions = self._harness.sessions
        return self

    def mark_failure(self) -> None:
        if self._harness is not None:
            self._harness.had_failure = True

    def __exit__(self, exc_type, exc, tb) -> None:
        if self._harness is not None:
            self._harness.__exit__(exc_type, exc, tb)
            self._harness = None
        if self._started_mail_sink and self.mail_sink is not None:
            try:
                self.mail_sink.stop()
            except Exception:
                pass


def _execute_checks(
    checks: Sequence[ReleaseCheck],
    ctx: ReleaseQaContext,
    *,
    live_mode: bool = False,
) -> List[ReleaseCheckOutcome]:
    outcomes: List[ReleaseCheckOutcome] = []
    for chk in checks:
        if live_mode and not chk.live_safe:
            outcomes.append(
                ReleaseCheckOutcome(
                    check_id=chk.check_id,
                    feature=chk.feature,
                    tier=chk.tier,
                    title=chk.title,
                    live_safe=chk.live_safe,
                    source_suite=chk.source_suite,
                    status="SKIPPED_LIVE_GUARD",
                    detail="Skipped mutating/local-only check in live mode",
                    duration_ms=0,
                )
            )
            print(f"[SKIP] {chk.check_id} ({chk.feature}) {chk.title}")
            continue

        t0 = time.monotonic()
        status = "PASS"
        detail = ""
        try:
            chk.fn(ctx)
        except (BlockedCapability, PendingMilestone) as blk:
            status = "BLOCKED_CAPABILITY"
            detail = redact_secrets(str(blk))
        except SafetyGuardError as sge:
            status = "FAIL"
            detail = redact_secrets(f"SafetyGuardError: {sge}")
        except (E2EFailure, AssertionError) as err:
            status = "FAIL"
            detail = redact_secrets(str(err))
        except Exception as err:
            status = "FAIL"
            detail = redact_secrets(f"{type(err).__name__}: {err}")
        finally:
            ctx.cleanup_all_ephemeral()

        dur = int(round((time.monotonic() - t0) * 1000))
        outcomes.append(
            ReleaseCheckOutcome(
                check_id=chk.check_id,
                feature=chk.feature,
                tier=chk.tier,
                title=chk.title,
                live_safe=chk.live_safe,
                source_suite=chk.source_suite,
                status=status,
                detail=detail,
                duration_ms=dur,
            )
        )
        line = f"[{status}] {chk.check_id} ({chk.feature}, T{chk.tier}, {dur}ms) {chk.title}"
        if detail:
            line += f" — {detail}"
        print(redact_secrets(line))
    return outcomes


def build_json_report(outcomes: Sequence[ReleaseCheckOutcome], *, mode: str) -> Dict[str, Any]:
    by_status = dict(Counter(o.status for o in outcomes))
    by_tier = {f"T{t}": sum(1 for o in outcomes if o.tier == t) for t in (1, 2, 3, 4)}
    by_feature = {f"F{i}": sum(1 for o in outcomes if o.feature == f"F{i}") for i in range(1, 17)}
    return {
        "mode": mode,
        "summary": {
            "total": len(outcomes),
            "by_status": by_status,
            "by_tier": by_tier,
            "by_feature": by_feature,
            "duration_ms": sum(o.duration_ms for o in outcomes),
        },
        "outcomes": [
            {
                "check_id": o.check_id,
                "feature": o.feature,
                "tier": o.tier,
                "title": o.title,
                "live_safe": o.live_safe,
                "source_suite": o.source_suite,
                "status": o.status,
                "detail": redact_secrets(o.detail),
                "duration_ms": o.duration_ms,
            }
            for o in outcomes
        ],
    }


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--list", action="store_true", help="List all registered F1..F16 checks and counts")
    p.add_argument("--mode", choices=("local", "live", "all"), default="local")
    p.add_argument("--tier", default="1,2,3,4", help="Comma-separated tiers (1,2,3,4) or 'all'")
    p.add_argument("--only", default=None, help="Filter checks by ID, feature (e.g. F9), or title substring")
    p.add_argument("--base-url", default=None, help="Backend API base URL")
    p.add_argument("--site-url", default=None, help="Public/proxy site base URL")
    p.add_argument("--apk-path", default=None, help="Path to signed release APK (com.nexachat)")
    p.add_argument("--android", action="store_true", help="Enable emulator-5554 Android checks")
    p.add_argument("--android-serial", default="emulator-5554")
    p.add_argument("--jar", default=None, help="Path to nexachat-server jar for local DisposableStack")
    p.add_argument("--rebuild-jar", action="store_true", help="Rebuild backend jar before local run")
    p.add_argument("--keep-stack-on-fail", action="store_true")
    p.add_argument("--screenshots-dir", default=None)
    p.add_argument("--json-out", default=None, help="Write redacted JSON summary report to this path")
    p.add_argument("--strict-capabilities", action="store_true", help="Exit 1 if any check is BLOCKED_CAPABILITY")
    p.add_argument("--strict-public-plum", action="store_true", help="Enforce Tonal Plum canvas on public site pages")
    p.add_argument("--self-test", action="store_true", help="Run offline unittest self-test suite")
    return p


def _run_self_test() -> int:
    test_dir = Path(__file__).resolve().parent
    suite = unittest.defaultTestLoader.discover(start_dir=str(test_dir), pattern="test_release_qa.py")
    res = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if res.wasSuccessful() else 1


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    all_checks = get_all_checks()

    if args.self_test:
        return _run_self_test()

    try:
        tiers = parse_tiers(args.tier)
    except ValueError as err:
        print(f"Argument error: {err}", file=sys.stderr)
        return 2

    selected = filter_checks(all_checks, tiers=tiers, only=args.only)
    if args.list:
        print(format_list_output(selected))
        return 0

    if not selected:
        print("No checks matched --tier / --only selection", file=sys.stderr)
        return 2

    try:
        validate_target_safety(args.mode, args.base_url, args.site_url)
    except SafetyGuardError as err:
        print(f"SafetyGuardError: {redact_secrets(str(err))}", file=sys.stderr)
        return 2

    all_outcomes: List[ReleaseCheckOutcome] = []

    if args.mode in ("local", "all"):
        local_base = args.base_url if (args.mode == "local" and args.base_url) else None
        local_site = args.site_url if (args.mode == "local" and args.site_url) else None
        with LocalQaEnv(
            base_url=local_base,
            site_url=local_site,
            jar=args.jar,
            rebuild_jar=args.rebuild_jar,
            keep_stack_on_fail=args.keep_stack_on_fail,
        ) as env:
            ctx = ReleaseQaContext(
                spring=SocialApiClient(env.spring_base_url),
                proxy=SocialApiClient(env.proxy_base_url),
                sessions=env.sessions,
                mode="local",
                base_url=env.spring_base_url,
                site_url=env.proxy_base_url,
                admin_base_url=env.admin_base_url,
                mail_sink=env.mail_sink,
                db_info=env.db_info,
                android_enabled=args.android,
                android_serial=args.android_serial,
                apk_path=args.apk_path,
                screenshots_dir=args.screenshots_dir,
                strict_public_plum=args.strict_public_plum,
            )
            local_outcomes = _execute_checks(selected, ctx, live_mode=False)
            if any(o.status == "FAIL" for o in local_outcomes):
                env.mark_failure()
            all_outcomes.extend(local_outcomes)

    if args.mode in ("live", "all"):
        live_api = (args.base_url or DEFAULT_LIVE_API_URL).rstrip("/")
        live_site = (args.site_url or DEFAULT_LIVE_SITE_URL).rstrip("/")
        ctx = ReleaseQaContext(
            spring=SocialApiClient(live_api),
            proxy=SocialApiClient(live_site),
            sessions=None,
            mode="live",
            base_url=live_api,
            site_url=live_site,
            admin_base_url=f"{live_site}/admin",
            mail_sink=None,
            db_info=None,
            android_enabled=args.android,
            android_serial=args.android_serial,
            apk_path=args.apk_path,
            screenshots_dir=args.screenshots_dir,
            strict_public_plum=args.strict_public_plum,
        )
        live_outcomes = _execute_checks(selected, ctx, live_mode=True)
        all_outcomes.extend(live_outcomes)

    if args.json_out:
        out_path = Path(args.json_out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        report = build_json_report(all_outcomes, mode=args.mode)
        out_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")

    return evaluate_exit_code(all_outcomes, strict_capabilities=args.strict_capabilities)


__all__ = [
    "LocalQaEnv",
    "build_json_report",
    "build_parser",
    "evaluate_exit_code",
    "filter_checks",
    "filter_live_safe_checks",
    "format_list_output",
    "get_all_checks",
    "main",
    "parse_tiers",
    "validate_target_safety",
]

if __name__ == "__main__":
    sys.exit(main())
