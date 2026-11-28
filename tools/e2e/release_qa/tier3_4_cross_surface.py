"""Tier 3 (pairwise cross-feature) and Tier 4 (real-world cross-surface E2E) checks.

Implements:
- 20 Tier 3 pairwise cross-feature checks (T3.01..T3.20)
- 16 Tier 4 real-world scenarios (T4.S1..T4.S16) covering:
  * Full 8-route real-backend Admin Console Playwright E2E (/overview, /users,
    /alerts, /rules, /audit, /system, /feed, /social with PR #385 formRef &
    8e448100 MODERATOR gate)
  * Public Site Playwright E2E (/, /download.html, /delete-account.html CORS/OTP,
    /join/{token}, /privacy.html, /terms.html)
  * Cross-Surface Tonal Plum UI consistency, WCAG AA contrast, Note monogram,
    200% text-zoom, and 390px mobile overflow audit
  * Delegation to social_suite T4, social_emulator_e2e.py, and live_verify.py
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
import time
import urllib.parse
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

from . import (
    REPO_ROOT,
    BlockedCapability,
    E2EFailure,
    ReleaseCheck,
    ReleaseQaContext,
    check,
    expect_error,
    expect_status,
    generate_ephemeral_phone,
    generate_strong_password,
)
from tools.e2e.social_suite.browser_driver import (
    BrowserDriver,
    audit_page_design,
    contrast_ratio,
    normalize_hex_color,
    parse_css_color,
)
from tools.e2e.social_suite.fixtures import (
    FIXTURE_PASSWORD,
    VALID_JOIN_TOKEN,
    load_audio_only_m4a_bytes,
    load_valid_mp4_bytes,
    make_png_bytes,
)

NOTE_MONOGRAM_PATH = "M24 20H36L64 60V20H80V84H66L40 47V72L24 84Z"
_FAVICON_SVG_LINK_RE = re.compile(
    r"""<link\b(?=[^>]*\brel\s*=\s*["']icon["'])(?=[^>]*\bhref\s*=\s*["']/favicon\.svg["'])[^>]*>""",
    re.IGNORECASE,
)


def _has_android_hex_color(xml_text: str, hex_color: str) -> bool:
    hex6 = hex_color.lstrip("#")
    return bool(re.search(r"#(?:ff)?" + re.escape(hex6) + r"\b", xml_text, re.IGNORECASE))


def verify_public_page_note_monogram(page: Any, url: str, site_base: str) -> None:
    """Verifies Note monogram presence on public pages (inline SVG on lockup pages, /favicon.svg on prose pages)."""
    html = page.content()
    path = urllib.parse.urlsplit(url).path or "/"
    if path in ("/delete-account.html", "/privacy.html", "/terms.html"):
        check(
            bool(_FAVICON_SVG_LINK_RE.search(html)),
            f'{url} missing <link rel="icon" href="/favicon.svg">',
        )
        favicon_url = urllib.parse.urljoin(site_base.rstrip("/") + "/", "/favicon.svg")
        resp = page.request.get(favicon_url)
        check(resp.status == 200, f"{url} /favicon.svg returned HTTP {resp.status}")
        check(NOTE_MONOGRAM_PATH in resp.text(), f"{url} /favicon.svg missing Note monogram SVG")
    else:
        check(NOTE_MONOGRAM_PATH in html, f"{url} missing Note monogram SVG")


# ---------------------------------------------------------------------------
# Pure Testable Helpers (Design Audit, Touch Target, Public URL Matrix)
# ---------------------------------------------------------------------------


def effective_touch_target_height(
    box_height: float,
    *,
    has_expanded_hit_ring: bool = False,
    inset_px: float = 6.0,
) -> float:
    """Computes effective vertical hit target accounting for ::before { inset: -6px }."""
    if has_expanded_hit_ring:
        return box_height + (2.0 * inset_px)
    return box_height


def audit_tonal_plum_tokens(
    tokens: Dict[str, str],
    *,
    scheme: str,
    surface: str,
    strict_public_plum: bool = False,
) -> List[str]:
    """Validates computed Tonal Plum CSS tokens across Social, Admin, and Public Site."""
    failures: List[str] = []
    if scheme not in ("light", "dark"):
        return [f"Unknown color scheme: {scheme}"]

    if surface in ("social", "admin"):
        expected = (
            {
                "canvas": "#151316",
                "primary": "#f4b2e2",
                "container": "#67355d",
                "text": "#e8e0e5",
            }
            if scheme == "dark"
            else {
                "canvas": "#fff7fc",
                "primary": "#733f68",
                "container": "#fcd7f4",
                "text": "#1d1b1e",
            }
        )
        for key, want in expected.items():
            got = normalize_hex_color(tokens.get(key, ""))
            if got != want:
                failures.append(f"{surface} {scheme} {key}: expected {want}, got {got}")
        if "inter" not in (tokens.get("fontFamily") or "").lower():
            failures.append(f"{surface} {scheme} fontFamily missing Inter: {tokens.get('fontFamily')!r}")
        if tokens.get("cardRadius") and tokens["cardRadius"] != "16px":
            failures.append(f"{surface} {scheme} cardRadius expected 16px, got {tokens['cardRadius']!r}")
        if tokens.get("pillRadius") and tokens["pillRadius"] not in ("999px", "9999px"):
            failures.append(f"{surface} {scheme} pillRadius expected 999px, got {tokens['pillRadius']!r}")
    elif surface == "public":
        canvas = normalize_hex_color(tokens.get("canvas", ""))
        primary = normalize_hex_color(tokens.get("primary", ""))
        plum_canvas = "#151316" if scheme == "dark" else "#fff7fc"
        slate_canvas = "#0f172a" if scheme == "dark" else "#f8fafc"
        if strict_public_plum:
            if canvas != plum_canvas:
                failures.append(f"public {scheme} canvas expected Tonal Plum {plum_canvas}, got {canvas}")
        else:
            if canvas not in (plum_canvas, slate_canvas):
                failures.append(f"public {scheme} canvas unexpected: {canvas}")
        expected_primary = "#f4b2e2" if scheme == "dark" else "#733f68"
        if primary not in (expected_primary, "#dda7cc", "#733f68"):
            failures.append(f"public {scheme} primary unexpected: {primary}")
    return failures


def build_public_site_urls(site_url: str, join_token: str = VALID_JOIN_TOKEN) -> List[str]:
    base = site_url.rstrip("/")
    return [
        f"{base}/",
        f"{base}/download.html",
        f"{base}/delete-account.html",
        f"{base}/join/{join_token}",
        f"{base}/privacy.html",
        f"{base}/terms.html",
    ]


def _require_browser_driver(own_origin: str) -> BrowserDriver:
    try:
        import playwright.sync_api  # noqa: F401
    except ImportError as err:
        raise BlockedCapability(f"Playwright unavailable: {err}") from err
    if not own_origin:
        raise BlockedCapability("No site/proxy URL configured for browser E2E")
    return BrowserDriver(own_origin)


def _social_ctx(ctx: ReleaseQaContext) -> Any:
    if ctx.social_suite_ctx is not None:
        return ctx.social_suite_ctx
    ctx.require_local_target("social_suite delegation")
    sessions = ctx.require_sessions("social_suite delegation")
    from tools.e2e.social_suite.client import SuiteContext
    from tools.e2e.social_suite.run_social_e2e import probe_capabilities

    sctx = SuiteContext(
        spring_client=ctx.spring,
        proxy_client=ctx.proxy,
        sessions=sessions,
        mode="auto",
        proxy_base_url=ctx.site_url,
        spring_base_url=ctx.base_url,
        admin_base_url=ctx.admin_base_url,
        android_enabled=ctx.android_enabled,
        android_serial=ctx.android_serial,
        android_apk=ctx.apk_path,
        screenshots_dir=str(ctx.screenshots_dir) if ctx.screenshots_dir else None,
    )
    probe_capabilities(sctx)
    ctx.social_suite_ctx = sctx
    return sctx


def _cleanup_accounts(ctx: ReleaseQaContext, accounts: Sequence[Dict[str, Any]]) -> None:
    for acc in reversed(accounts):
        try:
            ctx.cleanup_ephemeral(acc)
            if acc in ctx.ephemeral_accounts:
                ctx.ephemeral_accounts.remove(acc)
        except Exception:
            pass


def _make_stomp_connection(ctx: ReleaseQaContext, token: str, label: str):
    try:
        from tools.e2e.calls.call_e2e import StompConnection
    except ImportError as err:
        raise BlockedCapability(f"websocket-client unavailable for STOMP: {err}") from err
    return StompConnection(ctx.ws_url(), token, label)


def _stomp_frame_json(frame: Any) -> Optional[dict]:
    if not frame or frame[0] != "MESSAGE":
        return None
    try:
        return json.loads(frame[2])
    except Exception:
        return None


def _wait_stomp_error(conn: Any, expected_code: str, *, timeout: float = 10.0) -> dict:
    def matches(frame: Any) -> bool:
        env = _stomp_frame_json(frame)
        return bool(env and env.get("code") == expected_code)

    frame = conn.wait_for(matches, timeout, f"WsErrorResponse code={expected_code}")
    return _stomp_frame_json(frame) or {}


def _admin_ui_login(page: Any, rec: Any, admin_base_url: str, identifier: str, password: str) -> None:
    page.goto(f"{admin_base_url}/login", wait_until="networkidle")
    page.fill("#identifier", identifier)
    page.fill("#password", password)
    page.click('button[type="submit"]')
    page.wait_for_url("**/overview", timeout=15000)
    rec.assert_clean("admin UI login")


# ---------------------------------------------------------------------------
# Tier 3: Pairwise Cross-Feature Checks (20 checks >= 16)
# ---------------------------------------------------------------------------


def _t3_01_multi_device_refresh_and_password_change_revocation(ctx: ReleaseQaContext) -> None:
    accs = [ctx.register_ephemeral("T301 MultiDev")]
    try:
        a = accs[0]
        l2 = ctx.spring.post("/api/v1/auth/login", body={"identifier": a["phone"], "password": a["password"]})
        expect_status(l2, 200, "second device login")
        d2 = l2.json()
        ref = ctx.spring.post("/api/v1/auth/refresh", body={"refreshToken": d2["refreshToken"]})
        expect_status(ref, 200, "device 2 refresh")
        d2_ref = ref.json()
        new_pw = generate_strong_password()
        cp = ctx.spring.patch(
            "/api/v1/auth/password",
            token=d2_ref["accessToken"],
            body={
                "oldPassword": a["password"],
                "newPassword": new_pw,
                "refreshToken": d2_ref["refreshToken"],
            },
        )
        expect_status(cp, 204, "PATCH /api/v1/auth/password")
        a["password"] = new_pw
        # Device 1's refresh token must be revoked while device 2's kept refreshToken stays valid
        old_ref = ctx.spring.post("/api/v1/auth/refresh", body={"refreshToken": a["refreshToken"]})
        expect_status(old_ref, 401, "old refresh revoked after password change")
        keep_ref = ctx.spring.post("/api/v1/auth/refresh", body={"refreshToken": d2_ref["refreshToken"]})
        expect_status(keep_ref, 200, "kept device 2 refresh token stays valid")
        a["accessToken"] = keep_ref.json()["accessToken"]
        time.sleep(1.1)
        l3 = ctx.spring.post("/api/v1/auth/login", body={"identifier": a["phone"], "password": new_pw})
        expect_status(l3, 200, "login with updated password")
        a["accessToken"] = l3.json()["accessToken"]
    finally:
        _cleanup_accounts(ctx, accs)


def _t3_02_deletion_promotes_group_admin_and_releases_phone(ctx: ReleaseQaContext) -> None:
    phone_a = generate_ephemeral_phone()
    pw = generate_strong_password()
    a1 = ctx.register_ephemeral("T302 SoleAdmin", phone=phone_a, password=pw)
    heir = ctx.register_ephemeral("T302 Heir")
    a2 = None
    try:
        grp = ctx.spring.post(
            "/api/v1/conversations/group",
            token=a1["accessToken"],
            body={"title": f"T302 Grp {ctx.nonce}", "memberIds": [heir["userId"]]},
        )
        expect_status(grp, 201, "create group")
        gid = grp.json()["id"]
        expect_status(
            ctx.spring.request("DELETE", "/api/v1/users/me", token=a1["accessToken"], body={"password": pw}),
            204,
            "delete sole group admin",
        )
        inv = ctx.spring.post(f"/api/v1/conversations/{gid}/invite-link", token=heir["accessToken"])
        expect_status(inv, 201, "promoted heir creates invite link")
        a2 = ctx.register_ephemeral("T302 ReReg", phone=phone_a, password=pw)
        check(a2["userId"] != a1["userId"], "re-registered phone kept old userId")
    finally:
        _cleanup_accounts(ctx, [acc for acc in (heir, a2) if acc is not None])


def _t3_03_block_suppresses_chat_and_unblock_restores(ctx: ReleaseQaContext) -> None:
    a = ctx.register_ephemeral("T303 Blocker")
    b = ctx.register_ephemeral("T303 Peer")
    try:
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        expect_status(ctx.spring.post(f"/api/v1/users/{b['userId']}/block", token=a["accessToken"]), 204, "block")
        denied = ctx.spring.post(
            f"/api/v1/conversations/{cid}/messages",
            token=b["accessToken"],
            body={"clientMessageId": str(uuid.uuid4()), "type": "TEXT", "content": "blocked", "attachmentIds": []},
        )
        expect_status(denied, 403, "blocked peer denied messaging")
        expect_status(ctx.spring.delete(f"/api/v1/users/{b['userId']}/block", token=a["accessToken"]), 204, "unblock")
        restored = ctx.send_text(b["accessToken"], cid, f"restored {ctx.nonce}")
        check(bool(restored.get("id")), "message after unblock failed")
    finally:
        _cleanup_accounts(ctx, [a, b])


def _t3_04_show_seen_privacy_with_sync_v2_delivery(ctx: ReleaseQaContext) -> None:
    a = ctx.register_ephemeral("T304 SeenA")
    b = ctx.register_ephemeral("T304 SeenB")
    try:
        expect_status(ctx.spring.patch("/api/v1/users/me/settings", token=b["accessToken"], body={"showSeen": False}), 200, "showSeen=false")
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        m = ctx.send_text(a["accessToken"], cid, f"sync seen {ctx.nonce}")
        sync_r = ctx.spring.post(
            "/api/v1/sync",
            token=b["accessToken"],
            body={"syncVersion": 2, "limitPerConv": 20, "watermarks": [{"conversationId": cid, "lastSeq": 0, "changeCursor": 0, "generation": 1}]},
        )
        expect_status(sync_r, 200, "sync v2 delivery")
        ctx.spring.post(f"/api/v1/conversations/{cid}/read", token=b["accessToken"], body={"seq": m["seq"]})
        conv = ctx.spring.get("/api/v1/conversations", token=a["accessToken"]).json()
        rows = conv if isinstance(conv, list) else (conv or {}).get("items", [])
        match = next((x for x in rows if x.get("id") == cid), None)
        if match and "peerLastReadSeq" in match:
            check((match.get("peerLastReadSeq") or 0) == 0, f"showSeen=false leaked peerLastReadSeq: {match}")
    finally:
        _cleanup_accounts(ctx, [a, b])


def _t3_05_group_approval_invite_then_member_chat(ctx: ReleaseQaContext) -> None:
    o = ctx.register_ephemeral("T305 Owner")
    s = ctx.register_ephemeral("T305 Seed")
    j = ctx.register_ephemeral("T305 Joiner")
    try:
        gid = ctx.spring.post(
            "/api/v1/conversations/group",
            token=o["accessToken"],
            body={"title": f"T305 {ctx.nonce}", "memberIds": [s["userId"]]},
        ).json()["id"]
        tok = ctx.spring.post(
            f"/api/v1/conversations/{gid}/invite-link",
            token=o["accessToken"],
            body={"requiresApproval": True},
        ).json()["token"]
        expect_status(ctx.spring.post(f"/api/v1/invite-links/{tok}/join", token=j["accessToken"]), (200, 201), "request join")
        reqs = ctx.spring.get(f"/api/v1/conversations/{gid}/join-requests", token=o["accessToken"]).json()
        rows = reqs if isinstance(reqs, list) else (reqs or {}).get("items", [])
        req = next((r for r in rows if r.get("userId") == j["userId"] or (r.get("user") or {}).get("id") == j["userId"]), None)
        check(req is not None, f"join request missing: {rows}")
        expect_status(ctx.spring.post(f"/api/v1/conversations/{gid}/join-requests/{req['id']}/approve", token=o["accessToken"]), (200, 204), "approve")
        msg = ctx.send_text(j["accessToken"], gid, f"hello group {ctx.nonce}")
        check(bool(msg.get("id")), "approved member could not message group")
    finally:
        _cleanup_accounts(ctx, [o, s, j])


def _t3_06_attachment_range_206_and_recall_404(ctx: ReleaseQaContext) -> None:
    a = ctx.register_ephemeral("T306 MediaA")
    b = ctx.register_ephemeral("T306 MediaB")
    try:
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        m4a = load_audio_only_m4a_bytes(trailing_moov=False)
        up = ctx.spring.upload_multipart(
            "/api/v1/attachments",
            token=a["accessToken"],
            files=[("file", "note.m4a", "audio/mp4", m4a)],
        )
        expect_status(up, (200, 201), "upload m4a")
        aid = up.json()["id"]
        msg = ctx.spring.post(
            f"/api/v1/conversations/{cid}/messages",
            token=a["accessToken"],
            body={"clientMessageId": str(uuid.uuid4()), "type": "AUDIO", "content": "", "attachmentIds": [aid]},
        )
        expect_status(msg, (200, 201), "send audio attachment")
        mid = msg.json()["id"]
        r206 = ctx.spring.get_range(f"/api/v1/attachments/{aid}/content", token=b["accessToken"], range_header="bytes=0-63")
        expect_status(r206, 206, "peer Range 206")
        check(r206.body == m4a[: len(r206.body)], "Range 206 bytes mismatch")
        expect_status(ctx.spring.delete(f"/api/v1/conversations/{cid}/messages/{mid}", token=a["accessToken"]), (200, 204), "recall audio msg")
        after = ctx.spring.get(f"/api/v1/attachments/{aid}/content", token=b["accessToken"])
        check(after.status in (403, 404), f"recalled message attachment expected 403/404, got {after}")
    finally:
        _cleanup_accounts(ctx, [a, b])


def _t3_07_stomp_call_writes_history_to_conversation(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("STOMP call -> conversation history")
    a = ctx.register_ephemeral("T307 CallA")
    b = ctx.register_ephemeral("T307 CallB")
    ca = cb = None
    try:
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        ca = _make_stomp_connection(ctx, a["accessToken"], "t307-a")
        cb = _make_stomp_connection(ctx, b["accessToken"], "t307-b")
        call_id = str(uuid.uuid4())
        ca.send_call(cid, {"type": "INVITE", "callId": call_id})
        cb.wait_event("CALL_INVITE", call_id)
        cb.send_call(cid, {"type": "ACCEPT", "callId": call_id})
        ca.wait_event("CALL_ACCEPTED", call_id)
        ca.send_call(cid, {"type": "END", "callId": call_id})
        ca.wait_event("CALL_ENDED", call_id)
        hist = ctx.spring.get(f"/api/v1/conversations/{cid}/messages", token=a["accessToken"])
        expect_status(hist, 200, "conversation messages after call")
        items = hist.json().get("items", [])
        check(any(it.get("type") == "CALL" or "call" in (it.get("type") or "").lower() for it in items), "call system record missing from chat history")
    finally:
        for conn in (ca, cb):
            if conn is not None:
                conn.close()
        _cleanup_accounts(ctx, [a, b])


def _t3_08_ai_consent_gate_and_vietnamese_ask_rag(ctx: ReleaseQaContext) -> None:
    a = ctx.register_ephemeral("Minh Trâm")
    b = ctx.register_ephemeral("Đắc Nhân")
    try:
        expect_status(ctx.spring.patch("/api/v1/users/me/settings", token=a["accessToken"], body={"aiSummaryConsent": False}), 200, "opt out")
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        m = ctx.send_text(a["accessToken"], cid, "Tôi sẽ trình bày bản demo ở phòng Hoa Sữa lúc 9 giờ sáng thứ Sáu.")
        sum_r = ctx.spring.post(f"/api/v1/conversations/{cid}/summary", token=a["accessToken"], body={"scope": "RECENT", "language": "VI"})
        check(sum_r.status in (400, 403) or (sum_r.status == 200 and bool((sum_r.json() or {}).get("contextOmitted"))), f"opt-out summary gate failed: {sum_r}")
        expect_status(ctx.spring.patch("/api/v1/users/me/settings", token=a["accessToken"], body={"aiSummaryConsent": True}), 200, "opt in")
        ask_r = ctx.spring.post(
            f"/api/v1/conversations/{cid}/ask",
            token=a["accessToken"],
            body={"question": "Theo chính lời của Minh Trâm, cô ấy sẽ trình bày bản demo ở đâu?", "language": "VI"},
        )
        expect_status(ask_r, 200, "PR #384 Ask after opt-in")
        ja = ask_r.json() or {}
        check(ja.get("mode") == "EXTRACTIVE" and "phòng Hoa Sữa" in (ja.get("answer") or ""), f"unexpected Ask output: {ja}")
        check((ja.get("citations") or [{}])[0].get("messageId") == m["id"], "citation mismatch")
    finally:
        _cleanup_accounts(ctx, [a, b])


def _delegate_social_t3(ctx: ReleaseQaContext, check_ids: Sequence[str]) -> None:
    sctx = _social_ctx(ctx)
    from tools.e2e.social_suite.tier3_cross_feature import get_tier3_checks as social_t3

    by_id = {c.check_id: c for c in social_t3()}
    for cid in check_ids:
        by_id[cid].fn(sctx)


def _t3_09_social_post_comment_streak(ctx: ReleaseQaContext) -> None:
    _delegate_social_t3(ctx, ("T3.01", "T3.17"))


def _t3_10_suspend_revokes_social_unsuspend_restores(ctx: ReleaseQaContext) -> None:
    _delegate_social_t3(ctx, ("T3.02",))


def _t3_11_admin_flags_and_cms_sync_to_config(ctx: ReleaseQaContext) -> None:
    _delegate_social_t3(ctx, ("T3.03", "T3.04"))


def _t3_12_video_flag_existing_readable_new_blocked(ctx: ReleaseQaContext) -> None:
    _delegate_social_t3(ctx, ("T3.05",))


def _t3_13_report_hide_unhide_and_video_audit(ctx: ReleaseQaContext) -> None:
    _delegate_social_t3(ctx, ("T3.07", "T3.08"))


def _t3_14_block_prevents_report_and_isolates_authors_search(ctx: ReleaseQaContext) -> None:
    _delegate_social_t3(ctx, ("T3.10", "T3.13"))


def _t3_15_edit_updates_search_and_preserves_report_snapshot(ctx: ReleaseQaContext) -> None:
    _delegate_social_t3(ctx, ("T3.11", "T3.12"))


def _t3_16_hide_and_delete_cascade_media_and_comments(ctx: ReleaseQaContext) -> None:
    _delegate_social_t3(ctx, ("T3.14", "T3.18"))


def _t3_17_moderator_role_split(ctx: ReleaseQaContext) -> None:
    _delegate_social_t3(ctx, ("T3.15",))


def _t3_18_dual_prefix_and_proxy_range_no_store(ctx: ReleaseQaContext) -> None:
    _delegate_social_t3(ctx, ("T3.16", "T3.19"))


def _t3_19_join_page_social_footer_csp_no_store(ctx: ReleaseQaContext) -> None:
    r = ctx.proxy.get(f"/join/{VALID_JOIN_TOKEN}")
    expect_status(r, 200, "GET /join/{token}")
    cc = (r.header("Cache-Control") or "").lower()
    check("no-store" in cc, f"/join/ missing Cache-Control: no-store ({cc!r})")
    csp = r.header("Content-Security-Policy") or ""
    check("default-src 'none'" in csp and "frame-ancestors 'none'" in csp, f"/join/ weak CSP: {csp!r}")
    idx = r.text.find("<footer")
    check(idx != -1 and "/social/" in r.text[idx:], "/join/ missing /social/ link in <footer>")


def _t3_20_cross_surface_tonal_plum_static_parity(ctx: ReleaseQaContext) -> None:
    social_css = (REPO_ROOT / "site" / "social" / "social.css").read_text(encoding="utf-8").lower()
    admin_css = (REPO_ROOT / "admin-web" / "src" / "styles.css").read_text(encoding="utf-8").lower()
    site_css = (REPO_ROOT / "site" / "style.css").read_text(encoding="utf-8").lower()
    light_xml_path = REPO_ROOT / "android" / "app" / "src" / "main" / "res" / "values" / "colors_nexachat.xml"
    night_xml_path = REPO_ROOT / "android" / "app" / "src" / "main" / "res" / "values-night" / "colors_nexachat.xml"
    check(light_xml_path.is_file(), f"missing {light_xml_path}")
    check(night_xml_path.is_file(), f"missing {night_xml_path}")
    android_light_xml = light_xml_path.read_text(encoding="utf-8")
    android_night_xml = night_xml_path.read_text(encoding="utf-8")
    for tok in ("#fff7fc", "#733f68", "#151316"):
        check(tok in social_css, f"social.css missing {tok}")
        check(tok in admin_css, f"admin-web styles.css missing {tok}")
        if tok == "#151316":
            check(_has_android_hex_color(android_night_xml, tok), f"android values-night colors_nexachat.xml missing {tok}")
        else:
            check(_has_android_hex_color(android_light_xml, tok), f"android values colors_nexachat.xml missing {tok}")
    if ctx.strict_public_plum:
        for tok in ("#fff7fc", "#733f68", "#151316"):
            check(tok in site_css, f"site/style.css missing {tok}")


# ---------------------------------------------------------------------------
# Tier 4: Real-World Cross-Surface Application Scenarios (T4.S1..T4.S16)
# ---------------------------------------------------------------------------


def _t4_s1_multi_device_and_social_session_logout(ctx: ReleaseQaContext) -> None:
    _t3_01_multi_device_refresh_and_password_change_revocation(ctx)
    sctx = _social_ctx(ctx)
    expect_status(ctx.proxy.get("/api/social/v1/posts", token=sctx.token("reader")), 200, "social web feed active")


def _t4_s2_google_oidc_gate_and_boundaries(ctx: ReleaseQaContext) -> None:
    r = ctx.spring.post("/api/v1/auth/google/nonce")
    if r.status == 404:
        accs = [ctx.register_ephemeral("T4S2 Google Gate")]
        try:
            tok = accs[0]["accessToken"]
            expect_error(ctx.spring.post("/api/v1/auth/google", body={"idToken": "x", "nonce": "y"}), 404, "NOT_FOUND", "google disabled")
            expect_error(ctx.spring.get("/api/v1/auth/identities", token=tok), 404, "NOT_FOUND", "identities disabled")
            expect_error(ctx.spring.post("/api/v1/auth/identities/google/nonce", token=tok), 404, "NOT_FOUND", "link nonce disabled")
            expect_error(ctx.spring.request("DELETE", "/api/v1/auth/identities/google", token=tok, body={"password": accs[0]["password"]}), 404, "NOT_FOUND", "unlink disabled")
        finally:
            _cleanup_accounts(ctx, accs)
        raise BlockedCapability("Google OIDC disabled on target stack (404 fail-closed gate verified; mock JWKS exchange gated)")
    expect_status(r, 200, "google nonce enabled")
    nonce = r.json()["nonce"]
    bad = ctx.spring.post("/api/v1/auth/google", body={"idToken": "invalid.jwt.token", "nonce": nonce})
    check(bad.status in (400, 401), f"invalid OIDC token must fail 400/401, got {bad}")


def _t4_s3_web_delete_account_browser_otp_flow(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("Playwright /delete-account.html OTP deletion")
    if ctx.mail_sink is None:
        raise BlockedCapability("MailSink unavailable for /delete-account.html OTP test")
    driver = _require_browser_driver(ctx.site_url)
    phone = generate_ephemeral_phone()
    pw = generate_strong_password()
    victim = ctx.register_ephemeral("T4S3 Victim", phone=phone, password=pw)
    heir = ctx.register_ephemeral("T4S3 Heir")
    re_reg = None
    try:
        grp = ctx.spring.post(
            "/api/v1/conversations/group",
            token=victim["accessToken"],
            body={"title": f"S3 Heir {ctx.nonce}", "memberIds": [heir["userId"]]},
        )
        expect_status(grp, 201, "create group for S3")
        gid = grp.json()["id"]

        email = f"qa.s3.{ctx.nonce}.{uuid.uuid4().hex[:6]}@example.test"
        expect_status(ctx.spring.put("/api/v1/users/me/email", token=victim["accessToken"], body={"email": email}), 202, "link email")
        otp1 = ctx.mail_sink.extract_otp(ctx.mail_sink.wait_for_message(email, timeout=30.0))
        expect_status(ctx.spring.post("/api/v1/users/me/email/verify", token=victim["accessToken"], body={"code": otp1}), 200, "verify email")

        with driver as drv:
            with drv.new_page(width=1280, height=900, color_scheme="light", locale="en-US") as (page, rec):
                # Override <meta name="nexachat-api-base"> or route {apiBase}/api/v1/account-deletion/* to local spring
                page.goto(f"{ctx.site_url}/delete-account.html#lang-en", wait_until="networkidle")
                meta_base = page.get_attribute('meta[name="nexachat-api-base"]', "content") or ""
                routed_base = ""
                if meta_base and not meta_base.startswith(ctx.base_url):
                    routed_base = meta_base.rstrip("/")
                    # Route meta API base calls transparently to local disposable spring backend
                    def _proxy_del(route):
                        req = route.request
                        rel = urllib.parse.urlsplit(req.url).path
                        if req.method == "OPTIONS":
                            route.fulfill(
                                status=204,
                                headers={
                                    "Access-Control-Allow-Origin": ctx.site_url,
                                    "Access-Control-Allow-Methods": "POST, OPTIONS",
                                    "Access-Control-Allow-Headers": "Content-Type",
                                },
                            )
                            return
                        body = json.loads(req.post_data or "{}")
                        resp = ctx.spring.post(rel, body=body)
                        route.fulfill(
                            status=resp.status,
                            headers={
                                "Content-Type": "application/json",
                                "Access-Control-Allow-Origin": ctx.site_url,
                            },
                            body=resp.text,
                        )

                    page.route(f"{routed_base}/api/v1/account-deletion/*", _proxy_del)

                page.wait_for_selector("#web-deletion-form-wrap:not([hidden])", timeout=10000)
                # Empty phone validation
                page.click("#btn-send-code")
                page.wait_for_selector("#deletion-phone-error:not([hidden])", timeout=5000)
                check(page.get_attribute("#deletion-phone", "aria-invalid") == "true", "empty phone missing aria-invalid=true")

                # Valid ephemeral phone -> 202
                page.fill("#deletion-phone", phone)
                page.click("#btn-send-code")
                page.wait_for_selector("#request-feedback:not([hidden])", timeout=15000)
                page.wait_for_selector("#post-send-fields:not([hidden])", timeout=15000)

                # Confirm button disabled until 6-digit code + checkbox
                check(page.is_disabled("#btn-delete-account"), "delete button should start disabled")
                page.fill("#deletion-code", "000000")
                page.check("#deletion-confirm")
                check(not page.is_disabled("#btn-delete-account"), "delete button should enable with 6-digit code + confirm")

                # Wrong OTP -> 400 error message
                with rec.allow(statuses=(400,), url_substrings=("/api/v1/account-deletion/confirm",)):
                    page.click("#btn-delete-account")
                    page.wait_for_selector("#deletion-code-error:not([hidden])", timeout=10000)

                # Real Mailpit OTP -> 204 success
                otp_del = ctx.mail_sink.extract_otp(ctx.mail_sink.wait_for_message(email, timeout=30.0))
                page.fill("#deletion-code", otp_del)
                page.click("#btn-delete-account")
                page.wait_for_selector("#web-deletion-success:not([hidden])", timeout=15000)
                if routed_base:
                    allowed_routed = {
                        f"{routed_base}/api/v1/account-deletion/request",
                        f"{routed_base}/api/v1/account-deletion/confirm",
                    }
                    unexpected_ext = [u for u in rec.external_requests if u not in allowed_routed]
                    check(
                        not unexpected_ext,
                        f"T4.S3 /delete-account.html unexpected external URLs: {unexpected_ext}",
                    )
                    rec.external_requests = unexpected_ext
                rec.assert_clean("T4.S3 /delete-account.html")

        # Verify promoted group admin & phone re-registration
        inv = ctx.spring.post(f"/api/v1/conversations/{gid}/invite-link", token=heir["accessToken"])
        expect_status(inv, 201, "heir promoted after web deletion")
        re_reg = ctx.register_ephemeral("T4S3 ReReg", phone=phone, password=pw)
        check(re_reg["userId"] != victim["userId"], "re-registration after web deletion did not allocate fresh userId")
    finally:
        _cleanup_accounts(ctx, [acc for acc in (heir, re_reg) if acc is not None])


def _t4_s4_sync_v2_and_stomp_reconnect(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("T4.S4 sync v2 + STOMP reconnect")
    a = ctx.register_ephemeral("T4S4 OutA")
    b = ctx.register_ephemeral("T4S4 OutB")
    conn = None
    try:
        cid = ctx.open_direct(a["accessToken"], b["phone"])
        m1 = ctx.send_text(a["accessToken"], cid, f"offline-1 {ctx.nonce}")
        conn = _make_stomp_connection(ctx, b["accessToken"], "s4-b")
        conn.subscribe(f"/topic/conversations/{cid}")
        conn.close()
        conn = None
        m2 = ctx.send_text(a["accessToken"], cid, f"while-disconnected {ctx.nonce}")
        conn = _make_stomp_connection(ctx, b["accessToken"], "s4-b2")
        sync_r = ctx.spring.post(
            "/api/v1/sync",
            token=b["accessToken"],
            body={"syncVersion": 2, "limitPerConv": 20, "watermarks": [{"conversationId": cid, "lastSeq": m1["seq"], "changeCursor": 0, "generation": 1}]},
        )
        expect_status(sync_r, 200, "sync v2 catchup")
        d0 = sync_r.json()["deltas"][0]
        check(any(m.get("id") == m2["id"] for m in d0.get("messages", [])), "missed message during STOMP disconnect")
    finally:
        if conn is not None:
            conn.close()
        _cleanup_accounts(ctx, [a, b])


def _t4_s5_privacy_safety_and_chat_color(ctx: ReleaseQaContext) -> None:
    _t3_04_show_seen_privacy_with_sync_v2_delivery(ctx)
    _t3_03_block_suppresses_chat_and_unblock_restores(ctx)
    chat_color = (REPO_ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "nexachat" / "data" / "ChatColor.java").read_text(encoding="utf-8")
    for c in ("PLUM", "INDIGO", "BLUE", "TEAL", "GREEN", "AMBER", "ROSE", "SLATE"):
        check(c in chat_color, f"ChatColor missing {c}")


def _t4_s6_group_join_browser_poll_and_reminder(ctx: ReleaseQaContext) -> None:
    _t3_05_group_approval_invite_then_member_chat(ctx)
    driver = _require_browser_driver(ctx.site_url)
    with driver as drv:
        with drv.new_page(width=1280, height=800, color_scheme="light") as (page, rec):
            page.goto(f"{ctx.site_url}/join/{VALID_JOIN_TOKEN}", wait_until="networkidle")
            page.wait_for_selector("#invite-valid:not([hidden])", timeout=10000)
            href = page.get_attribute("#btn-open-app", "href") or ""
            check(href == f"nexachat://join/{VALID_JOIN_TOKEN}", f"unexpected deep-link href: {href!r}")
            page.goto(f"{ctx.site_url}/join/too_short", wait_until="networkidle")
            page.wait_for_selector("#invite-invalid:not([hidden])", timeout=10000)
            rec.assert_clean("T4.S6 /join/ valid & invalid")


def _t4_s7_media_m4a_range_206_and_recall_404(ctx: ReleaseQaContext) -> None:
    _t3_06_attachment_range_206_and_recall_404(ctx)


def _t4_s8_webrtc_call_signaling_and_optional_rtp(ctx: ReleaseQaContext) -> None:
    _t3_07_stomp_call_writes_history_to_conversation(ctx)


def _t4_s9_pr384_vietnamese_ask_rag_and_summary(ctx: ReleaseQaContext) -> None:
    _t3_08_ai_consent_gate_and_vietnamese_ask_rag(ctx)


def _t4_s10_streak_freeze_and_reset_lifecycle(ctx: ReleaseQaContext) -> None:
    from .tier1_2_platform_api import get_tier2_checks

    by_id = {c.check_id: c for c in get_tier2_checks()}
    by_id["T2.F10.02"].fn(ctx)
    by_id["T2.F10.03"].fn(ctx)


def _t4_s11_android_emulator_feed_e2e(ctx: ReleaseQaContext) -> None:
    sctx = _social_ctx(ctx)
    from tools.e2e.social_suite.tier4_browser_e2e import get_tier4_checks as social_t4

    t4_s7 = next(c for c in social_t4() if c.check_id == "T4.S7")
    t4_s7.fn(sctx)


def _t4_s12_web_social_bilingual_avatars_and_kill_switch(ctx: ReleaseQaContext) -> None:
    sctx = _social_ctx(ctx)
    from tools.e2e.social_suite.tier4_browser_e2e import get_tier4_checks as social_t4

    by_id = {c.check_id: c for c in social_t4()}
    for cid in ("T4.S1", "T4.S1b", "T4.S3"):
        by_id[cid].fn(sctx)


def _t4_s13_admin_console_full_8_routes_real_backend(ctx: ReleaseQaContext) -> None:
    ctx.require_local_target("T4.S13 full 8-route real-backend Admin Playwright E2E")
    sctx = _social_ctx(ctx)
    driver = _require_browser_driver(ctx.site_url)
    admin_url = ctx.admin_base_url or f"{ctx.site_url}/admin"
    admin_phone = sctx.phone("admin")
    mod_phone = sctx.phone("moderator")
    reader_phone = sctx.phone("reader")
    reader_uid = sctx.uid("reader")
    admin_tok = sctx.token("admin")

    # Ensure reader is not suspended & flags start enabled
    sctx.set_social_flags(True, True)
    ctx.spring.post(f"/api/v1/admin/users/{reader_uid}/unsuspend", token=admin_tok, body={"reason": "S13 pre-clean"})

    # Seed a SCAM_PHISHING message for /alerts and a feed video report for /feed
    cid_ar = ctx.open_direct(sctx.token("reader"), admin_phone)
    ctx.send_text(sctx.token("reader"), cid_ar, f"Urgent wire transfer {ctx.nonce} send account now")

    up_vid = ctx.spring.upload_multipart(
        "/api/v1/feed/videos",
        token=sctx.token("reactor"),
        files=[("file", "s13.mp4", "video/mp4", load_valid_mp4_bytes())],
    )
    expect_status(up_vid, (200, 201), "S13 upload report video")
    rep_post = sctx.create_post_safe("reactor", f"S13 reportable video {ctx.nonce}", video_id=up_vid.json()["videoId"]).json()
    rep = ctx.spring.post(
        "/api/v1/reports",
        token=sctx.token("author"),
        body={"targetType": "POST", "targetId": rep_post["id"], "reason": "SPAM"},
    ).json()

    created_rule_name = f"S13Rule_{ctx.nonce}"
    rule_pattern = f"s13badword{ctx.nonce}"

    try:
        with driver as drv:
            # 1. MODERATOR gate (8e448100): hidden /admin/social link + .empty[role="status"] + zero settings API calls
            with drv.new_page(width=1440, height=900, color_scheme="light", locale="en-US") as (page, rec):
                social_reqs: List[str] = []
                page.on("request", lambda r: social_reqs.append(r.url) if "/api/v1/admin/social/settings" in r.url else None)
                _admin_ui_login(page, rec, admin_url, mod_phone, FIXTURE_PASSWORD)
                check(page.locator('aside.sidebar nav a[href="/admin/social"]').count() == 0, "MODERATOR saw /admin/social nav link")
                page.goto(f"{admin_url}/social", wait_until="networkidle")
                page.wait_for_selector('.empty[role="status"]', timeout=10000)
                check(page.locator("#social-flags-title").count() == 0, "MODERATOR saw #social-flags-title")
                check(len(social_reqs) == 0, f"MODERATOR triggered /social/settings API calls: {social_reqs}")
                rec.assert_clean("T4.S13 MODERATOR gate")

            # 2. ADMIN full 8-route walkthrough
            with drv.new_page(width=1440, height=900, color_scheme="light", locale="en-US") as (page, rec):
                # Unauth redirect check
                page.goto(f"{admin_url}/overview", wait_until="networkidle")
                page.wait_for_url("**/login", timeout=10000)

                _admin_ui_login(page, rec, admin_url, admin_phone, FIXTURE_PASSWORD)

                # /admin/overview
                page.wait_for_selector(".kpi-grid .card.kpi", timeout=10000)
                check(page.locator(".kpi-grid .card.kpi").count() == 10, "expected 10 KPI cards on /overview")
                check(page.locator(".chart-row svg.spark").count() >= 2, "expected 2 spark charts on /overview")
                page.click('a.kpi-link[href="/admin/alerts"]')
                page.wait_for_url("**/alerts", timeout=10000)

                # /admin/alerts
                page.wait_for_selector(".filters select.select", timeout=10000)
                # Filter SCAM_PHISHING if present
                cat_select = page.locator(".filters select.select").nth(2)
                if cat_select.locator('option[value="SCAM_PHISHING"]').count() > 0:
                    cat_select.select_option("SCAM_PHISHING")
                    page.wait_for_timeout(400)
                if page.locator("tbody tr").count() > 0:
                    page.locator("tbody tr").first.click()
                    page.wait_for_selector(".drawer[role='dialog']", timeout=10000)
                    reveal_btn = page.locator(".drawer .action-row button", has_text=re.compile(r"Reveal", re.I))
                    if reveal_btn.count() > 0:
                        reveal_btn.first.click()
                        page.fill("#confirm-reason", f"S13 reveal audit {ctx.nonce}")
                        page.locator('[role="alertdialog"] button.btn.primary').click()
                        page.wait_for_selector(".drawer .snippet", timeout=10000)
                    note_area = page.locator(".drawer textarea.textarea")
                    if note_area.count() > 0:
                        note_area.fill(f"S13 note {ctx.nonce}")
                        page.locator(".drawer button", has_text=re.compile(r"Add note", re.I)).click()
                        page.wait_for_selector('.toast[role="status"]', timeout=10000)
                    page.locator(".drawer button.drawer-close").click()

                # /admin/users
                page.goto(f"{admin_url}/users", wait_until="networkidle")
                page.fill(".filters input.input", reader_phone)
                page.wait_for_timeout(500)
                page.wait_for_selector("tbody tr", timeout=10000)
                page.locator("tbody tr").first.click()
                page.wait_for_selector(".drawer[role='dialog']", timeout=10000)
                page.locator(".drawer .action-row button", has_text=re.compile(r"Suspend", re.I)).first.click()
                page.fill("#confirm-reason", f"S13 suspend {ctx.nonce}")
                page.locator('[role="alertdialog"] button.btn').last.click()
                page.wait_for_selector('.toast[role="status"]', timeout=10000)
                expect_error(ctx.spring.get("/api/v1/feed/posts", token=sctx.token("reader")), 403, "ACCOUNT_SUSPENDED", "suspended reader rejected")
                # Unsuspend
                page.locator(".drawer .action-row button", has_text=re.compile(r"Unsuspend", re.I)).first.click()
                page.fill("#confirm-reason", f"S13 unsuspend {ctx.nonce}")
                page.locator('[role="alertdialog"] button.btn').last.click()
                page.wait_for_selector('.toast[role="status"]', timeout=10000)
                page.locator(".drawer button.drawer-close").click()
                # Deleted users toggle
                page.locator('.filters button.btn.ghost').click()
                page.wait_for_timeout(300)
                page.locator('.filters button.btn.ghost').click()

                # /admin/rules
                page.goto(f"{admin_url}/rules", wait_until="networkidle")
                page.locator("button.btn.primary", has_text=re.compile(r"New rule", re.I)).click()
                page.fill("#rule-name", created_rule_name)
                page.fill("#rule-pattern", rule_pattern)
                page.locator("button.btn.primary", has_text=re.compile(r"Save", re.I)).click()
                if page.locator("#confirm-reason").count() > 0:
                    page.fill("#confirm-reason", f"S13 add rule {ctx.nonce}")
                page.locator('[role="alertdialog"] button.btn.primary').click()
                page.wait_for_selector('.toast[role="status"]', timeout=10000)
                # Rule Tester card
                tester_pat = page.locator('input[placeholder="Pattern"]')
                if tester_pat.count() > 0:
                    tester_pat.fill(rule_pattern)
                    page.locator("textarea.textarea").last.fill(f"hello {rule_pattern} world")
                    page.locator("button.btn", has_text=re.compile(r"Run test", re.I)).click()
                    page.wait_for_timeout(300)
                # Delete created rule
                row = page.locator("tbody tr", has_text=created_rule_name)
                check(row.count() >= 1, "created rule missing from /rules table")
                row.locator("button.btn.danger.sm").click()
                if page.locator("#confirm-reason").count() > 0:
                    page.fill("#confirm-reason", f"S13 delete rule {ctx.nonce}")
                page.locator('[role="alertdialog"] button.btn.danger').click()
                page.wait_for_selector('.toast[role="status"]', timeout=10000)

                # /admin/audit (non-UUID guard + valid UUID filter)
                page.goto(f"{admin_url}/audit", wait_until="networkidle")
                actor_in = page.locator(".filters input.input").nth(0)
                actor_in.fill("not-a-uuid")
                page.wait_for_timeout(400)
                rec.assert_clean("T4.S13 /audit non-UUID guard")
                actor_in.fill(sctx.uid("admin"))
                page.wait_for_timeout(400)

                # /admin/system
                page.goto(f"{admin_url}/system", wait_until="networkidle")
                page.wait_for_selector("#rag-panel-title", timeout=10000)
                check(page.locator(".status-grid .status-item").count() >= 5, "expected >= 5 services on /system")

                # /admin/feed
                page.goto(f"{admin_url}/feed", wait_until="networkidle")
                page.wait_for_selector("tbody tr", timeout=10000)
                exp_btn = page.locator("tbody tr button[aria-expanded]").first
                if exp_btn.count() > 0:
                    exp_btn.click()
                    vid_btn = page.locator(".report-video button")
                    if vid_btn.count() > 0:
                        vid_btn.first.click()
                        page.wait_for_selector(".report-video video", timeout=10000)

                # /admin/social (8e448100 inline TITLE_MAX=100 + PR #385 formRef 409 retention)
                page.goto(f"{admin_url}/social", wait_until="networkidle")
                page.wait_for_selector("#social-cms-title", timeout=10000)
                vi_title_in = page.locator(".social-draft-lang").nth(0).locator("input.input")
                vi_body_in = page.locator(".social-draft-lang").nth(0).locator("textarea.input")
                en_title_in = page.locator(".social-draft-lang").nth(1).locator("input.input")
                en_body_in = page.locator(".social-draft-lang").nth(1).locator("textarea.input")

                vi_title_in.fill("x" * 101)
                page.wait_for_selector("#social-title-Vi-error.form-error", timeout=5000)
                check(page.is_disabled(".social-cms-actions button.btn.ghost"), "Save draft should be disabled when title > 100")

                kept_vi_title = f"S13 Kept VI {ctx.nonce}"
                kept_en_body = f"S13 Kept EN Body {ctx.nonce}"
                vi_title_in.fill(kept_vi_title)
                vi_body_in.fill(f"S13 VI Body {ctx.nonce}")
                en_title_in.fill(f"S13 EN Title {ctx.nonce}")
                en_body_in.fill(kept_en_body)

                # Trigger out-of-band revision bump to force 409 SOCIAL_SETTINGS_CONFLICT
                cur_cfg = sctx.get_admin_settings()
                expect_status(
                    ctx.spring.patch(
                        "/api/v1/admin/social/settings/flags",
                        token=admin_tok,
                        body={
                            "expectedRevision": cur_cfg["revision"],
                            "webEnabled": cur_cfg["webEnabled"],
                            "videoUploadsEnabled": not cur_cfg["videoUploadsEnabled"],
                        },
                    ),
                    200,
                    "bump revision out-of-band",
                )
                with rec.allow(statuses=(409,), url_substrings=("/api/v1/admin/social/settings/announcement/draft",)):
                    page.locator(".social-cms-actions button.btn.ghost").click()
                    page.wait_for_selector('.social-banner.is-warn[role="alert"]', timeout=10000)
                check(vi_title_in.input_value() == kept_vi_title, "PR #385 formRef lost unsaved VI title on 409")
                check(en_body_in.input_value() == kept_en_body, "PR #385 formRef lost unsaved EN body on 409")
                # Retry Save draft now succeeds
                page.locator(".social-cms-actions button.btn.ghost").click()
                page.wait_for_selector('.toast[role="status"]', timeout=10000)
                rec.assert_clean("T4.S13 ADMIN 8-route walkthrough")
    finally:
        # Restore reader session token if suspended/unsuspended, flags, and delete seeded report post
        sctx.set_social_flags(True, True)
        ctx.spring.post(f"/api/v1/admin/feed/reports/{rep['id']}/resolve", token=sctx.token("moderator"), body={"status": "DISMISSED"})
        ctx.spring.delete(f"/api/v1/feed/posts/{rep_post['id']}", token=sctx.token("reactor"))
        ctx.spring.post(f"/api/v1/admin/users/{reader_uid}/unsuspend", token=admin_tok, body={"reason": "S13 cleanup"})
        login_r = ctx.spring.login_with_retry(reader_phone, FIXTURE_PASSWORD)
        r_rec = ctx.sessions.get("reader")
        r_rec.access_token = login_r["accessToken"]
        r_rec.refresh_token = login_r.get("refreshToken", r_rec.refresh_token)
        ctx.sessions.set(r_rec)


def _t4_s14_public_site_browser_and_assetlinks(ctx: ReleaseQaContext) -> None:
    driver = _require_browser_driver(ctx.site_url)
    al = ctx.proxy.get("/.well-known/assetlinks.json")
    expect_status(al, 200, "assetlinks.json")
    check((al.json() or [{}])[0].get("target", {}).get("package_name") == "com.nexachat", "assetlinks package_name")
    with driver as drv:
        with drv.new_page(width=1280, height=900, color_scheme="light") as (page, rec):
            page.goto(f"{ctx.site_url}/", wait_until="networkidle")
            check(page.locator('a[href^="/download.html"]').count() >= 1, "missing download CTA on /")
            check(page.locator('footer.site-footer nav a[href^="/social/"]').count() >= 1, "missing /social/ footer link")
            for url in build_public_site_urls(ctx.site_url, VALID_JOIN_TOKEN)[1:]:
                page.goto(url, wait_until="networkidle")
                check("NexaChat" in page.content(), f"missing NexaChat on {url}")
            rec.assert_clean("T4.S14 public site pages")


def _t4_s15_cross_surface_tonal_plum_wcag_zoom_overflow(ctx: ReleaseQaContext) -> None:
    driver = _require_browser_driver(ctx.site_url)
    site_base = ctx.site_url.rstrip("/")
    admin_base = (ctx.admin_base_url or f"{site_base}/admin").rstrip("/")

    with driver as drv:
        # 1. Web Social (/social/) Light/Dark + 1440/390 + 200% zoom
        for width, height in ((1440, 900), (390, 844)):
            for scheme in ("light", "dark"):
                with drv.new_page(width=width, height=height, color_scheme=scheme, locale="vi-VN") as (page, rec):
                    page.goto(f"{site_base}/social/", wait_until="networkidle")
                    page.wait_for_selector("#signed-out-panel:not([hidden])", timeout=15000)
                    res = audit_page_design(
                        page,
                        color_scheme=scheme,
                        check_touch_targets=True,
                        min_touch_px=47.5,
                        contrast_selectors=["body", "h1", ".lead", ".muted", "#login-button", "#open-chat-link"],
                        token_names=["--canvas", "--primary", "--primary-tonal"],
                    )
                    body_st = page.evaluate(
                        """() => {
                          const b = getComputedStyle(document.body);
                          const c = getComputedStyle(document.querySelector('#signed-out-panel'));
                          const btn = getComputedStyle(document.querySelector('#login-button'));
                          return { text: b.color, fontFamily: b.fontFamily, cardRadius: c.borderRadius, pillRadius: btn.borderRadius };
                        }"""
                    )
                    failures = audit_tonal_plum_tokens(
                        {
                            "canvas": res["tokens"].get("--canvas", ""),
                            "primary": res["tokens"].get("--primary", ""),
                            "container": res["tokens"].get("--primary-tonal", ""),
                            **body_st,
                        },
                        scheme=scheme,
                        surface="social",
                    )
                    check(not failures, f"S15 social {width}x{height} {scheme}: {failures}")
                    check(NOTE_MONOGRAM_PATH in page.content(), "social page missing Note monogram")
                    if width == 390:
                        # 200% text-zoom overflow check
                        page.evaluate("() => { document.documentElement.style.fontSize = '32px'; }")
                        zoom_ov = page.evaluate("() => document.documentElement.scrollWidth > window.innerWidth")
                        check(not zoom_ov, "200% text-zoom caused horizontal overflow on /social/")
                    rec.assert_clean(f"S15 social {width}x{height} {scheme}")

        # 2. Admin Web (/admin/login + authenticated routes when local)
        authed_admin_routes: List[str] = []
        if ctx.is_local_target() and ctx.sessions is not None:
            authed_admin_routes = ["/overview", "/users", "/alerts", "/feed", "/social", "/rules", "/audit", "/system"]
        for scheme in ("light", "dark"):
            with drv.new_page(width=1440, height=900, color_scheme=scheme, locale="en-US") as (page, rec):
                # Audit /login BEFORE authenticating (App.tsx redirects authed sessions away from /login)
                page.goto(f"{admin_base}/login", wait_until="networkidle")
                if scheme == "dark" and page.get_attribute("html", "data-theme") != "dark":
                    tog = page.locator("button.theme-toggle")
                    if tog.count() > 0:
                        tog.first.click()
                audit_page_design(
                    page,
                    color_scheme=scheme,
                    check_touch_targets=False,
                    contrast_selectors=["body", "h1", ".login-sub", ".login-submit", ".label"],
                )
                login_st = page.evaluate(
                    """() => {
                      const root = getComputedStyle(document.documentElement);
                      const b = getComputedStyle(document.body);
                      return {
                        canvas: root.getPropertyValue('--bg').trim(),
                        primary: root.getPropertyValue('--primary').trim(),
                        container: root.getPropertyValue('--container').trim(),
                        text: b.color,
                        fontFamily: b.fontFamily,
                      };
                    }"""
                )
                failures = audit_tonal_plum_tokens(login_st, scheme=scheme, surface="admin")
                check(not failures, f"S15 admin /login {scheme}: {failures}")
                check(NOTE_MONOGRAM_PATH in page.content(), "/admin/login missing Note monogram")

                if authed_admin_routes:
                    _admin_ui_login(page, rec, admin_base, ctx.phone("admin"), FIXTURE_PASSWORD)
                for route in authed_admin_routes:
                    page.goto(f"{admin_base}{route}", wait_until="networkidle")
                    if scheme == "dark" and page.get_attribute("html", "data-theme") != "dark":
                        tog = page.locator("button.theme-toggle")
                        if tog.count() > 0:
                            tog.first.click()
                    audit_page_design(
                        page,
                        color_scheme=scheme,
                        check_touch_targets=False,
                        contrast_selectors=["body", "h1", ".login-sub", ".login-submit", ".label"],
                    )
                    adm_st = page.evaluate(
                        """() => {
                          const root = getComputedStyle(document.documentElement);
                          const b = getComputedStyle(document.body);
                          return {
                            canvas: root.getPropertyValue('--bg').trim(),
                            primary: root.getPropertyValue('--primary').trim(),
                            container: root.getPropertyValue('--container').trim(),
                            text: b.color,
                            fontFamily: b.fontFamily,
                          };
                        }"""
                    )
                    failures = audit_tonal_plum_tokens(adm_st, scheme=scheme, surface="admin")
                    check(not failures, f"S15 admin {route} {scheme}: {failures}")
                rec.assert_clean(f"S15 admin matrix {scheme}")

        # 3. Public Site pages (Light & Dark, 1440 & 390)
        for url in build_public_site_urls(site_base, VALID_JOIN_TOKEN):
            for width, height in ((1440, 900), (390, 844)):
                for scheme in ("light", "dark"):
                    with drv.new_page(width=width, height=height, color_scheme=scheme) as (page, rec):
                        page.goto(url, wait_until="networkidle")
                        audit_page_design(
                            page,
                            color_scheme=scheme,
                            check_touch_targets=True,
                            min_touch_px=43.5,
                            touch_target_selector=".btn, .language-switch a, main button, main input, main select, main textarea, header a, header button",
                            contrast_selectors=["body", "h1", ".lead", ".btn"],
                        )
                        pub_st = page.evaluate(
                            """() => {
                              const root = getComputedStyle(document.documentElement);
                              return {
                                canvas: root.getPropertyValue('--bg-color').trim() || getComputedStyle(document.body).backgroundColor,
                                primary: root.getPropertyValue('--primary').trim(),
                              };
                            }"""
                        )
                        failures = audit_tonal_plum_tokens(
                            pub_st,
                            scheme=scheme,
                            surface="public",
                            strict_public_plum=ctx.strict_public_plum,
                        )
                        check(not failures, f"S15 public {url} {width}x{height} {scheme}: {failures}")
                        verify_public_page_note_monogram(page, url, site_base)
                        rec.assert_clean(f"S15 public {url} {width}x{height} {scheme}")


def _t4_s16_live_prod_and_release_apk_e2e(ctx: ReleaseQaContext) -> None:
    if ctx.is_local_target():
        raise BlockedCapability("T4.S16 live production verification skipped in --mode local; run --mode live")
    from .live_verify import run_live_verification

    run_live_verification(ctx)


# ---------------------------------------------------------------------------
# Registry Exports
# ---------------------------------------------------------------------------


def get_tier3_checks() -> List[ReleaseCheck]:
    raw: List[Tuple[str, str, str, bool, str, Any]] = [
        ("T3.01", "F1", "Multi-device login + refresh + password change revokes older session", True, "release_qa", _t3_01_multi_device_refresh_and_password_change_revocation),
        ("T3.02", "F3", "Account deletion promotes earliest remaining group member & frees phone", True, "release_qa", _t3_02_deletion_promotes_group_admin_and_releases_phone),
        ("T3.03", "F5", "User block suppresses direct chat & unblock restores delivery", True, "release_qa", _t3_03_block_suppresses_chat_and_unblock_restores),
        ("T3.04", "F4", "showSeen=false privacy preserves sync v2 delivery while hiding read seq", True, "release_qa", _t3_04_show_seen_privacy_with_sync_v2_delivery),
        ("T3.05", "F6", "Approval-gated group invite -> approve -> new member sends message", True, "release_qa", _t3_05_group_approval_invite_then_member_chat),
        ("T3.06", "F7", "Chat attachment Range 206 read & message recall revokes media (404)", True, "release_qa", _t3_06_attachment_range_206_and_recall_404),
        ("T3.07", "F8", "WebRTC STOMP call lifecycle writes call record to direct chat history", False, "call_e2e", _t3_07_stomp_call_writes_history_to_conversation),
        ("T3.08", "F9", "AI opt-out gates summary while opt-in unlocks PR #384 Vietnamese Ask RAG", True, "release_qa", _t3_08_ai_consent_gate_and_vietnamese_ask_rag),
        ("T3.09", "F10", "Social post & comment activity advances check-in streak", False, "social_suite", _t3_09_social_post_comment_streak),
        ("T3.10", "F11", "Account suspend immediately revokes social & feed; unsuspend restores", False, "social_suite", _t3_10_suspend_revokes_social_unsuspend_restores),
        ("T3.11", "F12", "Admin flag flip & CMS announcement sync immediately to /config", False, "social_suite", _t3_11_admin_flags_and_cms_sync_to_config),
        ("T3.12", "F11", "Video flag off keeps existing videos readable while blocking new uploads", False, "social_suite", _t3_12_video_flag_existing_readable_new_blocked),
        ("T3.13", "F13", "Report -> hide -> unhide -> reported video preview writes audit entry", False, "social_suite", _t3_13_report_hide_unhide_and_video_audit),
        ("T3.14", "F5", "Block denies post reporting & isolates /authors/{id}, ?authorId= & ?q=", False, "social_suite", _t3_14_block_prevents_report_and_isolates_authors_search),
        ("T3.15", "F11", "Post edit updates search index & preserves original report snapshot", False, "social_suite", _t3_15_edit_updates_search_and_preserves_report_snapshot),
        ("T3.16", "F7", "Hiding/deleting media post cascades 404 to media & child comments", False, "social_suite", _t3_16_hide_and_delete_cascade_media_and_comments),
        ("T3.17", "F13", "MODERATOR role allowed on feed reports, denied 403 on social settings", False, "social_suite", _t3_17_moderator_role_split),
        ("T3.18", "F12", "Dual-prefix /api/v1/feed & /api/social/v1 equivalence + proxy Range 206", False, "social_suite", _t3_18_dual_prefix_and_proxy_range_no_store),
        ("T3.19", "F14", "Public /join/{token} integrates /social/ footer link, CSP & no-store", True, "release_qa", _t3_19_join_page_social_footer_csp_no_store),
        ("T3.20", "F15", "Cross-surface Tonal Plum CSS token parity across Web, Admin & Android", True, "release_qa", _t3_20_cross_surface_tonal_plum_static_parity),
    ]
    return [ReleaseCheck(cid, feat, 3, title, live_safe, src, fn) for cid, feat, title, live_safe, src, fn in raw]


def get_tier4_checks() -> List[ReleaseCheck]:
    raw: List[Tuple[str, str, str, bool, str, Any]] = [
        ("T4.S1", "F1", "Multi-device sign-in, refresh recovery & password change + Web Social", False, "release_qa", _t4_s1_multi_device_and_social_session_logout),
        ("T4.S2", "F2", "Google OIDC Sign-Up/Link/Unlink gate & boundaries", True, "release_qa", _t4_s2_google_oidc_gate_and_boundaries),
        ("T4.S3", "F3", "Playwright /delete-account.html CORS/OTP deletion -> admin promotion + re-reg", False, "release_qa+mail_sink", _t4_s3_web_delete_account_browser_otp_flow),
        ("T4.S4", "F4", "STOMP disconnect/reconnect + sync v2 catchup", False, "release_qa", _t4_s4_sync_v2_and_stomp_reconnect),
        ("T4.S5", "F5", "Reciprocal showSeen=false, block isolation & Android ChatColor contract", True, "release_qa", _t4_s5_privacy_safety_and_chat_color),
        ("T4.S6", "F6", "Group approval invite + Playwright /join/{token} valid & invalid handoff", True, "release_qa", _t4_s6_group_join_browser_poll_and_reminder),
        ("T4.S7", "F7", "Audio .m4a + Range 206 + recall 404 media lifecycle", True, "release_qa", _t4_s7_media_m4a_range_206_and_recall_404),
        ("T4.S8", "F8", "1-to-1 WebRTC STOMP call signaling + conversation history record", False, "call_e2e", _t4_s8_webrtc_call_signaling_and_optional_rtp),
        ("T4.S9", "F9", "PR #384 Vietnamese Ask RAG + AI opt-out gate", True, "release_qa", _t4_s9_pr384_vietnamese_ask_rag_and_summary),
        ("T4.S10", "F10", "Direct Streak rolling 7-day freeze + 2-day cooldown reset preserving bestCount", False, "release_qa+psql", _t4_s10_streak_freeze_and_reset_lifecycle),
        ("T4.S11", "F11", "Android emulator (emulator-5554) Social Feed E2E via social_emulator_e2e.py", False, "social_suite+device", _t4_s11_android_emulator_feed_e2e),
        ("T4.S12", "F12", "Playwright Web Social bilingual, avatars (blob: + 8-hue), author modal & kill-switch", False, "social_suite", _t4_s12_web_social_bilingual_avatars_and_kill_switch),
        ("T4.S13", "F13", "Playwright Admin Console full 8-route real-backend E2E + PR #385 & 8e448100", False, "release_qa", _t4_s13_admin_console_full_8_routes_real_backend),
        ("T4.S14", "F14", "Playwright Public Site (/, /download, /delete-account, /join, /privacy, /terms)", True, "release_qa", _t4_s14_public_site_browser_and_assetlinks),
        ("T4.S15", "F15", "Cross-surface Tonal Plum UI, WCAG AA contrast, 200% zoom & 390px overflow audit", True, "release_qa", _t4_s15_cross_surface_tonal_plum_wcag_zoom_overflow),
        ("T4.S16", "F16", "Live production (api.nexachat.io.vn, nexachat.io.vn) & signed release APK E2E", True, "live_verify", _t4_s16_live_prod_and_release_apk_e2e),
    ]
    return [ReleaseCheck(cid, feat, 4, title, live_safe, src, fn) for cid, feat, title, live_safe, src, fn in raw]


__all__ = [
    "NOTE_MONOGRAM_PATH",
    "audit_tonal_plum_tokens",
    "build_public_site_urls",
    "effective_touch_target_height",
    "get_tier3_checks",
    "get_tier4_checks",
]
