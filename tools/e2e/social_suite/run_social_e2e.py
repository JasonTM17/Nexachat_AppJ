#!/usr/bin/env python3
"""CLI runner for the 4-tier NexaChat Social E2E suite.

Usage:
    python tools/e2e/social_suite/run_social_e2e.py --standalone --tier 1,2,3,4 --mode auto
    python tools/e2e/social_suite/run_social_e2e.py --base-url http://127.0.0.1:8080 --tier 1,2 --mode strict
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from dataclasses import asdict
from pathlib import Path
from typing import Dict, List, Optional, Sequence

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
    from tools.e2e.social_suite.client import (  # noqa: E402
        BlockedCapability,
        CheckCase,
        CheckOutcome,
        E2EFailure,
        PendingMilestone,
        SocialApiClient,
        SuiteContext,
    )
    from tools.e2e.social_suite.fixtures import NOT_AN_IMAGE_BYTES  # noqa: E402
    from tools.e2e.social_suite.stack_harness import StackHarness  # noqa: E402
    from tools.e2e.social_suite.tier1_feature_api import get_tier1_checks  # noqa: E402
    from tools.e2e.social_suite.tier2_boundary_edge import get_tier2_checks  # noqa: E402
    from tools.e2e.social_suite.tier3_cross_feature import get_tier3_checks  # noqa: E402
    from tools.e2e.social_suite.tier4_browser_e2e import get_tier4_checks  # noqa: E402
else:
    from .client import (
        BlockedCapability,
        CheckCase,
        CheckOutcome,
        E2EFailure,
        PendingMilestone,
        SocialApiClient,
        SuiteContext,
    )
    from .fixtures import NOT_AN_IMAGE_BYTES
    from .stack_harness import StackHarness
    from .tier1_feature_api import get_tier1_checks
    from .tier2_boundary_edge import get_tier2_checks
    from .tier3_cross_feature import get_tier3_checks
    from .tier4_browser_e2e import get_tier4_checks


def parse_tiers(raw: str) -> List[int]:
    val = (raw or "all").strip().lower()
    if val == "all":
        return [1, 2, 3, 4]
    tiers: List[int] = []
    for part in val.split(","):
        part = part.strip()
        if not part:
            continue
        num = int(part)
        if num not in (1, 2, 3, 4):
            raise ValueError(f"Invalid tier {num!r}; expected 1..4")
        if num not in tiers:
            tiers.append(num)
    if not tiers:
        raise ValueError("No tiers selected")
    return tiers


def select_checks(
    all_checks: Sequence[CheckCase],
    tiers: Sequence[int],
    mode: str,
    only: Optional[str] = None,
) -> List[CheckCase]:
    selected = [c for c in all_checks if c.tier in tiers]
    if mode == "baseline":
        selected = [c for c in selected if c.milestone_gate == "BASELINE"]
    if only:
        tokens = [t.strip().lower() for t in only.split(",") if t.strip()]
        selected = [
            c
            for c in selected
            if any(
                tok in c.check_id.lower()
                or tok == c.feature.lower()
                or tok in c.title.lower()
                for tok in tokens
            )
        ]
    return selected


def probe_capabilities(ctx: SuiteContext) -> None:
    """Probes live server & static surfaces to detect landed M1-M4 capabilities."""
    tok = ctx.token("author")
    rnd_uuid = str(uuid.uuid4())

    # M1 probes
    patch_probe = ctx.spring.patch(
        f"/api/v1/feed/posts/{rnd_uuid}",
        token=tok,
        body={"body": "probe"},
    )
    ctx.caps.m1_edit = patch_probe.status == 404 and patch_probe.error_code() == "POST_NOT_FOUND"

    author_probe = ctx.spring.get(f"/api/v1/feed/authors/{rnd_uuid}", token=tok)
    ctx.caps.m1_authors = author_probe.status == 404 and author_probe.error_code() == "USER_NOT_FOUND"

    search_probe = ctx.spring.get(
        "/api/v1/feed/posts",
        token=tok,
        params={"authorId": "not-a-uuid"},
    )
    ctx.caps.m1_search = search_probe.status == 400

    # Two-file multipart probe: baseline binds 1st file (415 MIME_NOT_ALLOWED), M1 returns 400 VALIDATION_FAILED
    mp_probe = ctx.spring.upload_multipart(
        "/api/v1/feed/images",
        token=tok,
        files=[
            ("file", "a.png", "image/png", NOT_AN_IMAGE_BYTES),
            ("file", "b.png", "image/png", NOT_AN_IMAGE_BYTES),
        ],
    )
    ctx.caps.m1_multipart_guard = mp_probe.status == 400 and mp_probe.error_code() == "VALIDATION_FAILED"
    ctx.caps.m1_range_video = ctx.caps.m1_edit and ctx.caps.m1_multipart_guard

    # M2 probes (static /social/ and /join/)
    if ctx.proxy_base_url:
        soc_html = ctx.proxy.get("/social/index.html").text
        soc_js = ctx.proxy.get("/social/social-app.js").text
        soc_css = ctx.proxy.get("/social/social.css").text
        join_html = ctx.proxy.get("/join/index.html").text

        stale_copy = ("ảnh đã có" in soc_js) or ("existing images" in soc_js)
        has_m2_ui = ("authorId" in soc_js) and ("details" in soc_html or "report-details" in soc_html)
        ctx.caps.m2_social_features = (not stale_copy) and has_m2_ui

        footer_idx = join_html.find("<footer")
        ctx.caps.m2_join_footer = footer_idx != -1 and "/social/" in join_html[footer_idx:]

        ctx.caps.m2_design_tokens = (
            "SOCIAL" in soc_html and "--surface-lowest" in soc_css and "avatar-tone-7" in soc_css
        )

        # M3 probe: inspect ephemeral admin bundle for M3 MODERATOR gate string/marker if present
        # Also verified dynamically in T4.S2b via real browser login.
        import re
        adm_html = ctx.proxy.get("/admin/").text
        scripts = re.findall(r'src="(/admin/assets/[^"]+\.js)"', adm_html)
        if scripts:
            adm_js = ctx.proxy.get(scripts[0]).text
            # Baseline Layout.tsx unconditionally includes nav.social for all staff; M3 gates on role === 'ADMIN'
            ctx.caps.m3_moderator_gate = ("social.adminOnly" in adm_js) or ("adminOnly" in adm_js)
            ctx.caps.m3_draft_conflict_fix = ctx.caps.m3_moderator_gate

    # M4 probe: verify Android feed sources & emulator E2E runner (or explicit --android-apk)
    repo_root = Path(__file__).resolve().parents[3]
    has_feed_fragment = (
        repo_root
        / "android"
        / "app"
        / "src"
        / "main"
        / "java"
        / "com"
        / "nexachat"
        / "feature"
        / "feed"
        / "PublicFeedFragment.java"
    ).is_file()
    has_emu_runner = (repo_root / "tools" / "e2e" / "device" / "social_emulator_e2e.py").is_file()
    has_explicit_apk = bool(ctx.android_apk and Path(ctx.android_apk).is_file())
    ctx.caps.m4_android_apk = (has_feed_fragment or has_explicit_apk) and has_emu_runner


def evaluate_exit_code(outcomes: Sequence[CheckOutcome], mode: str) -> int:
    failed = sum(1 for o in outcomes if o.status == "FAIL")
    pending = sum(1 for o in outcomes if o.status == "PENDING_MILESTONE")
    if failed > 0:
        return 1
    if mode == "strict" and pending > 0:
        return 1
    return 0


def run_checks(ctx: SuiteContext, checks: Sequence[CheckCase]) -> List[CheckOutcome]:
    outcomes: List[CheckOutcome] = []
    for case in checks:
        t0 = time.monotonic()
        try:
            case.fn(ctx)
            # In Tier 1 F4.04, check actual Accept-Ranges header if set
            status = "PASS"
            detail = ""
        except PendingMilestone as pm:
            status = "PENDING_MILESTONE"
            detail = str(pm)
        except BlockedCapability as bc:
            status = "BLOCKED_CAPABILITY"
            detail = str(bc)
        except E2EFailure as err:
            status = "FAIL"
            detail = str(err)
        except Exception as err:  # noqa: BLE001
            status = "FAIL"
            detail = f"{type(err).__name__}: {err}"
        dt_ms = int((time.monotonic() - t0) * 1000)
        outcomes.append(
            CheckOutcome(
                check_id=case.check_id,
                feature=case.feature,
                tier=case.tier,
                milestone_gate=case.milestone_gate,
                title=case.title,
                status=status,
                detail=detail,
                duration_ms=dt_ms,
            )
        )
        suffix = f": {detail}" if detail else ""
        print(f"{status} {case.check_id} [{case.feature}|{case.milestone_gate}] {case.title}{suffix}", flush=True)
    return outcomes


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="NexaChat Social 4-tier E2E test suite")
    mode_group = p.add_mutually_exclusive_group()
    mode_group.add_argument("--standalone", action="store_true", default=True, help="Provision isolated disposable stack (default)")
    mode_group.add_argument("--base-url", default=None, help="Target existing local Spring Boot URL instead of disposable stack")
    p.add_argument("--jar", default=None, help="Explicit executable Spring Boot jar path")
    p.add_argument("--rebuild-jar", action="store_true", help="Rebuild server jar before standalone run")
    p.add_argument("--keep-stack-on-fail", action="store_true", help="Keep disposable DB/process if any check fails")
    p.add_argument("--i-know-this-is-not-prod", action="store_true", help="Allow non-loopback --base-url")
    p.add_argument("--admin-vite-port", type=int, default=None, help="Serve admin-web via Vite dev on port instead of ephemeral build")
    p.add_argument("--tier", default="1,2,3,4", help="Comma-separated tiers (1,2,3,4) or 'all'")
    p.add_argument("--mode", choices=("auto", "strict", "baseline"), default="auto", help="Capability-gate strictness")
    p.add_argument("--only", default=None, help="Filter checks by ID, feature (F1..F16), or title substring")
    p.add_argument("--json-report", default=None, help="Write structured JSON report to path")
    p.add_argument("--screenshots-dir", default=None, help="Save Tier 4 Playwright full-page screenshots to directory")
    p.add_argument("--android", action="store_true", help="Run optional emulator UI journey on emulator-5554")
    p.add_argument("--android-serial", default="emulator-5554", help="ADB serial for --android")
    p.add_argument("--android-apk", default=None, help="Explicit APK path for --android T4.S7")
    p.add_argument("--android-package", default=None, help="Android package name for --android T4.S7 (e.g. com.nexachat)")
    p.add_argument("--list", action="store_true", help="List selected checks and exit")
    return p


def main(argv: Optional[List[str]] = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    args = build_parser().parse_args(argv)
    tiers = parse_tiers(args.tier)

    all_checks: List[CheckCase] = [
        *get_tier1_checks(),
        *get_tier2_checks(),
        *get_tier3_checks(),
        *get_tier4_checks(),
    ]
    selected = select_checks(all_checks, tiers, args.mode, args.only)
    if args.list:
        for c in selected:
            print(f"{c.check_id}\tT{c.tier}\t{c.feature}\t{c.milestone_gate}\t{c.title}")
        print(f"Total: {len(selected)} checks")
        return 0

    if not selected:
        print("No checks matched selection.", file=sys.stderr)
        return 2

    t_start = time.monotonic()
    with StackHarness(
        base_url=args.base_url,
        jar=args.jar,
        rebuild_jar=args.rebuild_jar,
        keep_stack_on_fail=args.keep_stack_on_fail,
        i_know_this_is_not_prod=args.i_know_this_is_not_prod,
        admin_vite_port=args.admin_vite_port,
    ) as harness:
        spring_client = SocialApiClient(harness.spring_base_url)
        proxy_client = SocialApiClient(harness.proxy_base_url)
        ctx = SuiteContext(
            spring_client=spring_client,
            proxy_client=proxy_client,
            sessions=harness.sessions,
            mode=args.mode,
            proxy_base_url=harness.proxy_base_url,
            spring_base_url=harness.spring_base_url,
            admin_base_url=harness.admin_base_url,
            android_enabled=args.android,
            android_serial=args.android_serial,
            android_apk=args.android_apk,
            android_package=args.android_package,
            screenshots_dir=args.screenshots_dir,
        )
        probe_capabilities(ctx)
        print(
            f"== NexaChat Social E2E ({args.mode}) | spring={harness.spring_base_url} "
            f"| proxy={harness.proxy_base_url} | checks={len(selected)} ==",
            flush=True,
        )
        print(f"capabilities={asdict(ctx.caps)}", flush=True)
        outcomes = run_checks(ctx, selected)
        exit_code = evaluate_exit_code(outcomes, args.mode)
        if exit_code != 0:
            harness.had_failure = True

        # Best-effort cleanup of any residual blocks/suspends inside standalone too
        for blocker_role, target_role in list(ctx.cleanup_blocks):
            try:
                ctx.ensure_block(blocker_role, target_role, False)
            except Exception:
                pass

    counts: Dict[str, int] = {
        k: sum(1 for o in outcomes if o.status == k)
        for k in ("PASS", "FAIL", "PENDING_MILESTONE", "BLOCKED_CAPABILITY")
    }
    total_ms = int((time.monotonic() - t_start) * 1000)
    print(
        f"\nsummary ({total_ms}ms): {counts['PASS']} PASS, {counts['FAIL']} FAIL, "
        f"{counts['PENDING_MILESTONE']} PENDING_MILESTONE, {counts['BLOCKED_CAPABILITY']} BLOCKED_CAPABILITY",
        flush=True,
    )

    if args.json_report:
        report = {
            "mode": args.mode,
            "tiers": tiers,
            "duration_ms": total_ms,
            "counts": counts,
            "capabilities": asdict(ctx.caps),
            "screenshots": ctx.screenshots_saved,
            "exit_code": exit_code,
            "outcomes": [asdict(o) for o in outcomes],
        }
        out_path = Path(args.json_report)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(report, indent=2), encoding="utf-8")

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
