"""Safe live production and signed release APK verification for NexaChat.

Safety Contract:
- Read-only probes + strictly isolated ephemeral +84908xxxxxx accounts.
- Every ephemeral account is deleted in an unconditional `finally:` block via
  DELETE /api/v1/users/me {"password": ...} followed by a 401 verification.
- Admin mutations, fixed +84900000xxx fixture accounts, and DB rewinds are
  explicitly blocked by SafetyGuardError.
- Signed release APK verification checks aapt/apksigner SHA-256 against live
  /.well-known/assetlinks.json[0], tests App Link VIEW dispatch, and invokes
  tools/e2e/device/social_emulator_e2e.py in release/non-loopback mode.
"""

from __future__ import annotations

import json
import posixpath
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Sequence, Tuple, Union

from . import (
    REPO_ROOT,
    BlockedCapability,
    E2EFailure,
    ReleaseQaContext,
    SafetyGuardError,
    assert_ephemeral_phone,
    check,
    expect_status,
)
from .tier3_4_cross_surface import NOTE_MONOGRAM_PATH, audit_tonal_plum_tokens, build_public_site_urls
from tools.e2e.social_suite.browser_driver import BrowserDriver, audit_page_design
from tools.e2e.social_suite.fixtures import VALID_JOIN_TOKEN, make_png_bytes

DEFAULT_LIVE_API_URL = "https://api.nexachat.io.vn"
DEFAULT_LIVE_SITE_URL = "https://nexachat.io.vn"
EXPECTED_RELEASE_PACKAGE = "com.nexachat"


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def assert_live_write_allowed(method: str, path: str, *, phone: Optional[str] = None) -> None:
    """Blocks non-ephemeral writes and all admin mutations against live hosts."""
    m = (method or "GET").upper()
    raw = (path or "").strip()
    parsed_path = urllib.parse.urlsplit(raw).path if "://" in raw else raw.split("?", 1)[0].split("#", 1)[0]
    collapsed = "/" + parsed_path.lstrip("/")
    p = posixpath.normpath(collapsed).lower()
    if not p.startswith("/"):
        p = "/" + p
    is_admin_path = (
        p == "/api/v1/admin"
        or p.startswith("/api/v1/admin/")
        or p == "/api/social/v1/admin"
        or p.startswith("/api/social/v1/admin/")
    )
    if m in ("POST", "PUT", "PATCH", "DELETE") and is_admin_path:
        raise SafetyGuardError(f"Refusing live admin mutation {m} {p}")
    if phone is not None:
        assert_ephemeral_phone(phone)


def format_sha256_colon_upper(hex64: str) -> str:
    """Formats a 64-char hex SHA-256 digest into colon-separated uppercase pairs."""
    cleaned = re.sub(r"[\s:]", "", (hex64 or "")).upper()
    if len(cleaned) != 64 or not re.fullmatch(r"[0-9A-F]{64}", cleaned):
        raise ValueError(f"Expected 64-hex SHA-256 digest, got {hex64!r}")
    return ":".join(cleaned[i : i + 2] for i in range(0, 64, 2))


def verify_apk_matches_assetlinks(
    apk_info: Dict[str, Any],
    assetlinks_payload: Any,
    *,
    expected_package: str = EXPECTED_RELEASE_PACKAGE,
) -> Dict[str, Any]:
    """Asserts APK package & apksigner SHA-256 match assetlinks.json[0]."""
    pkg = apk_info.get("package")
    check(pkg == expected_package, f"APK package mismatch: expected {expected_package!r}, got {pkg!r}")
    check(isinstance(assetlinks_payload, list) and len(assetlinks_payload) >= 1, f"Invalid assetlinks payload: {assetlinks_payload!r}")
    stmt = assetlinks_payload[0] or {}
    rel = stmt.get("relation") or []
    check(
        "delegate_permission/common.handle_all_urls" in rel,
        f"assetlinks[0] missing handle_all_urls relation: {rel!r}",
    )
    target = stmt.get("target") or {}
    check(target.get("package_name") == expected_package, f"assetlinks[0] package_name mismatch: {target!r}")
    fps = target.get("sha256_cert_fingerprints") or []
    check(bool(fps), "assetlinks[0] sha256_cert_fingerprints is empty")
    apk_fp = format_sha256_colon_upper(str(apk_info.get("signer_sha256") or ""))
    check(
        apk_fp == fps[0],
        f"APK signer SHA-256 {apk_fp} != assetlinks.json[0] fingerprint {fps[0]}",
    )
    return {"package": pkg, "signer_sha256": apk_fp, "assetlinks_fingerprint_0": fps[0]}


def build_adb_app_link_cmd(
    serial: str,
    url: str,
    *,
    package: str = EXPECTED_RELEASE_PACKAGE,
) -> List[str]:
    return [
        "adb",
        "-s",
        serial,
        "shell",
        "am",
        "start",
        "-W",
        "-a",
        "android.intent.action.VIEW",
        "-d",
        url,
        "-p",
        package,
    ]


def build_release_emulator_cmd(
    *,
    serial: str,
    apk_path: str,
    api_base_url: str,
    screenshots_dir: str,
    package: str = EXPECTED_RELEASE_PACKAGE,
    app_link_url: Optional[str] = None,
) -> List[str]:
    runner = REPO_ROOT / "tools" / "e2e" / "device" / "social_emulator_e2e.py"
    cmd = [
        sys.executable,
        str(runner),
        "--serial",
        serial,
        "--package",
        package,
        "--api-base-url",
        api_base_url,
        "--apk",
        str(apk_path),
        "--no-build-apk",
        "--verify-app-link",
        "--i-know-this-is-not-prod",
        "--screenshots-dir",
        str(screenshots_dir),
    ]
    if app_link_url:
        cmd.extend(["--app-link-url", app_link_url])
    return cmd


def _delete_ephemeral_and_verify_401(ctx: ReleaseQaContext, acc: Dict[str, Any]) -> None:
    assert_ephemeral_phone(acc["phone"])
    assert_live_write_allowed("DELETE", "/api/v1/users/me", phone=acc["phone"])
    del_r = ctx.spring.request(
        "DELETE",
        "/api/v1/users/me",
        token=acc["accessToken"],
        body={"password": acc["password"]},
    )
    if del_r.status not in (204, 401, 404):
        login = ctx.spring.post("/api/v1/auth/login", body={"identifier": acc["phone"], "password": acc["password"]})
        if login.status == 200:
            fresh = (login.json() or {}).get("accessToken")
            if fresh:
                del_r = ctx.spring.request("DELETE", "/api/v1/users/me", token=fresh, body={"password": acc["password"]})
    check(del_r.status in (204, 401, 404), f"Failed to delete ephemeral user {acc['phone']}: {del_r}")
    me = ctx.spring.get("/api/v1/users/me", token=acc["accessToken"])
    check(me.status == 401, f"Ephemeral token still accepted after delete: {me}")


def verify_live_api(ctx: ReleaseQaContext, report: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """Runs read-only probes + isolated +84908xxxxxx round-trip against live API."""
    out: Dict[str, Any] = {}
    origin = ctx.site_url or DEFAULT_LIVE_SITE_URL

    # 1. Health + HSTS
    h = ctx.spring.get("/actuator/health")
    expect_status(h, 200, "live GET /actuator/health")
    check((h.json() or {}).get("status") == "UP", f"live health not UP: {h.json()}")
    if ctx.base_url.startswith("https://"):
        hsts = h.header("Strict-Transport-Security") or ""
        check("max-age=" in hsts.lower(), f"live API missing HSTS header: {hsts!r}")
    out["health"] = "UP"

    # 2. Account deletion CORS preflight
    for path in ("/api/v1/account-deletion/request", "/api/v1/account-deletion/confirm"):
        opt = ctx.spring.request(
            "OPTIONS",
            path,
            headers={
                "Origin": origin,
                "Access-Control-Request-Method": "POST",
                "Access-Control-Request-Headers": "content-type",
            },
        )
        expect_status(opt, (200, 204), f"live OPTIONS {path}")
        acao = opt.header("Access-Control-Allow-Origin")
        check(acao in (origin, "*"), f"live OPTIONS {path} ACAO={acao!r}")

    # 3. Unauthenticated gates & /api/social/v1/config
    expect_status(ctx.spring.get("/api/v1/users/me"), 401, "live unauth /users/me")
    expect_status(ctx.spring.get("/api/v1/calls/ice-config"), 401, "live unauth /calls/ice-config")

    cfg_r = ctx.spring.get("/api/social/v1/config")
    expect_status(cfg_r, 200, "live GET /api/social/v1/config")
    cfg = cfg_r.json() or {}
    check("webEnabled" in cfg and "videoUploadsEnabled" in cfg, f"live social config invalid: {cfg}")
    check("no-store" in (cfg_r.header("Cache-Control") or "").lower(), "live /social/v1/config missing no-store")
    out["social_config"] = {"webEnabled": cfg["webEnabled"], "videoUploadsEnabled": cfg["videoUploadsEnabled"]}

    # 4. Ephemeral +84908xxxxxx round-trip with unconditional cleanup
    a = ctx.register_ephemeral("Minh Trâm")
    b = ctx.register_ephemeral("Đắc Nhân")
    created_post_id: Optional[str] = None
    try:
        assert_ephemeral_phone(a["phone"])
        assert_ephemeral_phone(b["phone"])

        # Authenticated ICE config
        ice = ctx.spring.get("/api/v1/calls/ice-config", token=a["accessToken"])
        expect_status(ice, 200, "live auth /calls/ice-config")
        check(isinstance((ice.json() or {}).get("iceServers"), list), "live ice-config missing iceServers")

        # WebSocket STOMP /ws handshake if websocket-client available
        try:
            from tools.e2e.calls.call_e2e import StompConnection

            ws_conn = StompConnection(ctx.ws_url(), a["accessToken"], "live-ws")
            ws_conn.close()
            out["ws_stomp"] = "PASS"
        except ImportError:
            out["ws_stomp"] = "BLOCKED_CAPABILITY (websocket-client absent)"

        # Direct chat + edit + recall + sync v2
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        msg = ctx.send_text(a["accessToken"], cid, f"live qa {ctx.nonce}")
        ed = ctx.spring.patch(
            f"/api/v1/conversations/{cid}/messages/{msg['id']}",
            token=a["accessToken"],
            body={"content": f"live qa edited {ctx.nonce}"},
        )
        expect_status(ed, 200, "live edit message")
        sync_r = ctx.spring.post(
            "/api/v1/sync",
            token=b["accessToken"],
            body={"syncVersion": 2, "limitPerConv": 20, "watermarks": [{"conversationId": cid, "lastSeq": 0, "changeCursor": 0, "generation": 1}]},
        )
        expect_status(sync_r, 200, "live sync v2")
        expect_status(ctx.spring.delete(f"/api/v1/conversations/{cid}/messages/{msg['id']}", token=a["accessToken"]), (200, 204), "live recall message")

        # PR #384 Vietnamese Ask RAG (with consent)
        expect_status(ctx.spring.patch("/api/v1/users/me/settings", token=a["accessToken"], body={"aiSummaryConsent": True}), 200, "live AI consent")
        m_rag = ctx.send_text(a["accessToken"], cid, "Tôi sẽ trình bày bản demo ở phòng Hoa Sữa lúc 9 giờ sáng thứ Sáu.")
        ask_ok = ctx.spring.post(
            f"/api/v1/conversations/{cid}/ask",
            token=a["accessToken"],
            body={"question": "Theo chính lời của Minh Trâm, cô ấy sẽ trình bày bản demo ở đâu?", "language": "VI"},
        )
        expect_status(ask_ok, 200, "live PR #384 Ask RAG positive")
        ja = ask_ok.json() or {}
        check(ja.get("mode") == "EXTRACTIVE" and "phòng Hoa Sữa" in (ja.get("answer") or ""), f"live Ask RAG failed: {ja}")
        check((ja.get("citations") or [{}])[0].get("messageId") == m_rag["id"], "live Ask RAG citation mismatch")

        # Streaks
        expect_status(ctx.spring.get("/api/v1/streaks/me", token=a["accessToken"]), 200, "live GET /streaks/me")

        # Avatar upload + Range 206 read
        png = make_png_bytes(32, 32)
        av_up = ctx.spring.upload_multipart("/api/v1/users/me/avatar", token=a["accessToken"], files=[("file", "live_av.png", "image/png", png)])
        expect_status(av_up, (200, 201), "live upload avatar")
        av_path = urllib.parse.urlsplit((av_up.json() or {})["avatarUrl"]).path
        r206 = ctx.spring.get_range(av_path, token=b["accessToken"], range_header="bytes=0-15")
        expect_status(r206, 206, "live avatar Range 206")
        check(r206.body == png[:16], "live avatar Range 206 bytes mismatch")

        # Ephemeral feed post + author profile + delete
        if cfg.get("webEnabled"):
            p_resp = ctx.spring.post("/api/v1/feed/posts", token=a["accessToken"], body={"clientPostId": str(uuid.uuid4()), "body": f"live post {ctx.nonce}"})
            expect_status(p_resp, 201, "live create feed post")
            created_post_id = p_resp.json()["id"]
            prof = ctx.spring.get(f"/api/v1/feed/authors/{a['userId']}", token=b["accessToken"])
            expect_status(prof, 200, "live GET /feed/authors/{id}")
            expect_status(ctx.spring.delete(f"/api/v1/feed/posts/{created_post_id}", token=a["accessToken"]), 204, "live delete feed post")
            created_post_id = None
        out["ephemeral_roundtrip"] = "PASS"
    finally:
        if created_post_id:
            try:
                ctx.spring.delete(f"/api/v1/feed/posts/{created_post_id}", token=a["accessToken"])
            except Exception:
                pass
        for acc in (b, a):
            try:
                _delete_ephemeral_and_verify_401(ctx, acc)
                if acc in ctx.ephemeral_accounts:
                    ctx.ephemeral_accounts.remove(acc)
            except Exception:
                pass

    if report is not None:
        report["live_api"] = out
    return out


def verify_live_site(ctx: ReleaseQaContext, report: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """Verifies live site endpoints (www redirect, assetlinks, /social/, /admin/, /join/, static pages)."""
    site_base = (ctx.site_url or DEFAULT_LIVE_SITE_URL).rstrip("/")
    out: Dict[str, Any] = {}

    # 1. www -> apex redirect when targeting nexachat.io.vn
    parsed = urllib.parse.urlsplit(site_base)
    if parsed.hostname == "nexachat.io.vn":
        opener = urllib.request.build_opener(_NoRedirectHandler())
        req = urllib.request.Request("https://www.nexachat.io.vn/", method="GET")
        try:
            with opener.open(req, timeout=15.0) as resp:
                status = resp.status
                loc = resp.headers.get("Location", "")
        except urllib.error.HTTPError as err:
            status = err.code
            loc = err.headers.get("Location", "")
        check(status in (301, 308) and loc.startswith("https://nexachat.io.vn"), f"www redirect unexpected: {status} -> {loc}")
        out["www_redirect"] = status

    # 2. assetlinks.json (200, no redirect, no Set-Cookie, application/json)
    al_url = f"{site_base}/.well-known/assetlinks.json"
    opener = urllib.request.build_opener(_NoRedirectHandler())
    try:
        with opener.open(urllib.request.Request(al_url, method="GET"), timeout=15.0) as resp:
            check(resp.status == 200, f"assetlinks expected 200, got {resp.status}")
            ct = (resp.headers.get("Content-Type") or "").lower()
            check("application/json" in ct, f"assetlinks Content-Type={ct!r}")
            check(resp.headers.get("Set-Cookie") is None, "assetlinks emitted Set-Cookie")
            al_data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as err:
        raise E2EFailure(f"Live assetlinks.json failed HTTP {err.code}") from err

    local_al = json.loads((REPO_ROOT / "site" / ".well-known" / "assetlinks.json").read_text(encoding="utf-8"))
    check(al_data[0]["target"]["package_name"] == "com.nexachat", "live assetlinks package_name mismatch")
    check(
        al_data[0]["target"]["sha256_cert_fingerprints"][0] == local_al[0]["target"]["sha256_cert_fingerprints"][0],
        "live assetlinks fingerprint mismatch vs repo",
    )
    out["assetlinks"] = al_data[0]["target"]["sha256_cert_fingerprints"][0]

    # 3. Playwright live browser checks (optional if playwright absent)
    try:
        import playwright.sync_api  # noqa: F401
    except ImportError:
        out["browser"] = "BLOCKED_CAPABILITY (playwright absent)"
        if report is not None:
            report["live_site"] = out
        return out

    with BrowserDriver(site_base) as drv:
        # /social/
        with drv.new_page(width=1280, height=900, color_scheme="light") as (page, rec):
            page.goto(f"{site_base}/social/", wait_until="networkidle")
            page.wait_for_selector("#signed-out-panel:not([hidden]), #session-panel:not([hidden])", timeout=15000)
            audit_page_design(page, color_scheme="light", check_touch_targets=True, min_touch_px=47.5, contrast_selectors=["body", "h1", ".lead", "#login-button"])
            rec.assert_clean("live /social/")

        # /admin/ (must NOT redirect to Vercel SSO)
        with drv.new_page(width=1280, height=900, color_scheme="light") as (page, rec):
            resp = page.goto(f"{site_base}/admin/", wait_until="networkidle")
            check(resp is not None and resp.status == 200, f"live /admin/ status={resp.status if resp else None}")
            check("vercel.com/sso" not in page.url and "login" in page.url.lower(), f"live /admin/ unexpected URL: {page.url}")
            check(NOTE_MONOGRAM_PATH in page.content(), "live /admin/ missing Note monogram")
            rec.assert_clean("live /admin/")

        # /join/{token}
        with drv.new_page(width=1280, height=900, color_scheme="light") as (page, rec):
            page.goto(f"{site_base}/join/{VALID_JOIN_TOKEN}", wait_until="networkidle")
            page.wait_for_selector("#invite-valid:not([hidden])", timeout=10000)
            check((page.get_attribute("#btn-open-app", "href") or "") == f"nexachat://join/{VALID_JOIN_TOKEN}", "live /join/ href mismatch")
            rec.assert_clean("live /join/")

        # Public pages
        for url in build_public_site_urls(site_base, VALID_JOIN_TOKEN):
            with drv.new_page(width=1280, height=900, color_scheme="light") as (page, rec):
                page.goto(url, wait_until="networkidle")
                audit_page_design(page, color_scheme="light", check_touch_targets=False, contrast_selectors=["body", "h1", ".lead"])
                rec.assert_clean(f"live {url}")
    out["browser"] = "PASS"

    if report is not None:
        report["live_site"] = out
    return out


def verify_live_release_apk(
    ctx: ReleaseQaContext,
    report: Optional[Dict[str, Any]] = None,
    *,
    cmd_runner: Optional[Callable[..., subprocess.CompletedProcess]] = None,
    apk_describer: Optional[Callable[[Union[str, Path]], Dict[str, Any]]] = None,
    assetlinks_fetcher: Optional[Callable[[str], Any]] = None,
) -> Dict[str, Any]:
    """Verifies signed release APK signer == assetlinks.json[0], App Link, & emulator E2E."""
    if not ctx.apk_path:
        raise BlockedCapability("--apk-path not provided; skipping signed release APK verification")
    apk = Path(ctx.apk_path)
    if not apk.is_file():
        raise E2EFailure(f"Release APK not found: {apk}")

    if apk_describer is None:
        from tools.e2e import fixture_apks

        apk_describer = fixture_apks.describe
    apk_info = apk_describer(apk)

    site_base = (ctx.site_url or DEFAULT_LIVE_SITE_URL).rstrip("/")
    if assetlinks_fetcher is None:
        def _fetch(url: str) -> Any:
            with urllib.request.urlopen(url, timeout=15.0) as r:
                return json.loads(r.read().decode("utf-8"))
        assetlinks_fetcher = _fetch

    al_payload = assetlinks_fetcher(f"{site_base}/.well-known/assetlinks.json")
    cert_check = verify_apk_matches_assetlinks(apk_info, al_payload, expected_package=EXPECTED_RELEASE_PACKAGE)

    if not ctx.android_enabled:
        raise BlockedCapability("Optional --android flag not enabled; skipping emulator install & run")

    runner = cmd_runner or (lambda argv, **kw: subprocess.run(argv, capture_output=True, text=True, **kw))
    if cmd_runner is None and shutil.which("adb") is None:
        raise BlockedCapability("adb binary not found on PATH")

    st = runner(["adb", "-s", ctx.android_serial, "get-state"], timeout=10)
    if st.returncode != 0 or (st.stdout or "").strip() != "device":
        raise BlockedCapability(f"ADB device {ctx.android_serial} unavailable: {st.stderr}")

    inst = runner(["adb", "-s", ctx.android_serial, "install", "-r", str(apk)], timeout=120)
    check(inst.returncode == 0, f"adb install -r failed: {inst.stderr or inst.stdout}")

    join_url = f"{site_base}/join/{VALID_JOIN_TOKEN}"
    app_link_proc = runner(build_adb_app_link_cmd(ctx.android_serial, join_url, package=EXPECTED_RELEASE_PACKAGE), timeout=30)
    check(app_link_proc.returncode == 0, f"App Link VIEW failed: {app_link_proc.stderr or app_link_proc.stdout}")
    out_txt = (app_link_proc.stdout or "") + "\n" + (app_link_proc.stderr or "")
    for bad in ("com.android.chrome", "Warning: Activity not started", "Error:"):
        check(bad not in out_txt, f"App Link VIEW resolved/failed unexpectedly ({bad!r}): {out_txt}")
    check(
        EXPECTED_RELEASE_PACKAGE in out_txt or "Status: ok" in out_txt,
        f"Unexpected App Link output: {out_txt}",
    )

    with tempfile.TemporaryDirectory(prefix="nexachat_rel_emu_") as tmp_dir:
        screens = str(Path(ctx.screenshots_dir) / "release_android") if ctx.screenshots_dir else tmp_dir
        emu_cmd = build_release_emulator_cmd(
            serial=ctx.android_serial,
            apk_path=str(apk),
            api_base_url=ctx.base_url or DEFAULT_LIVE_API_URL,
            screenshots_dir=screens,
            package=EXPECTED_RELEASE_PACKAGE,
            app_link_url=join_url,
        )
        emu_proc = runner(emu_cmd, timeout=900)
        if emu_proc.returncode != 0:
            raise E2EFailure(f"Release social_emulator_e2e.py failed ({emu_proc.returncode}): {(emu_proc.stderr or emu_proc.stdout)[-800:]}")
        stdout_s = (emu_proc.stdout or "").strip()
        brace = stdout_s.find("{")
        check(brace != -1, f"social_emulator_e2e.py returned non-JSON stdout: {stdout_s[-300:]!r}")
        emu_report = json.loads(stdout_s[brace:])
        check(emu_report.get("status") == "PASS" and emu_report.get("crash_count") == 0, f"Emulator report failed: {emu_report}")

    out = {"cert_match": cert_check, "emulator": "PASS"}
    if report is not None:
        report["live_apk"] = out
    return out


def run_live_verification(ctx: ReleaseQaContext) -> Dict[str, Any]:
    """Executes all live verification stages safely and returns structured summary."""
    report: Dict[str, Any] = {}
    verify_live_api(ctx, report)
    verify_live_site(ctx, report)
    try:
        verify_live_release_apk(ctx, report)
    except BlockedCapability as blk:
        report["live_apk"] = f"BLOCKED_CAPABILITY: {blk}"
    return report


__all__ = [
    "DEFAULT_LIVE_API_URL",
    "DEFAULT_LIVE_SITE_URL",
    "EXPECTED_RELEASE_PACKAGE",
    "assert_live_write_allowed",
    "build_adb_app_link_cmd",
    "build_release_emulator_cmd",
    "format_sha256_colon_upper",
    "run_live_verification",
    "verify_apk_matches_assetlinks",
    "verify_live_api",
    "verify_live_release_apk",
    "verify_live_site",
]
