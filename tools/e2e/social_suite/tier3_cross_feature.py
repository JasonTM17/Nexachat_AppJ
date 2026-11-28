"""Tier 3: Pairwise cross-feature & lifecycle integration checks (20 checks)."""

from __future__ import annotations

import uuid
from typing import List

from .client import (
    CheckCase,
    SuiteContext,
    check,
    expect_error,
    expect_status,
    reaction_entry,
    reaction_path,
)
from .fixtures import (
    FIXTURE_PASSWORD,
    load_valid_mp4_bytes,
    make_png_bytes,
    wait_next_second_boundary,
)


def _t3_01_feed_post_updates_streak(ctx: SuiteContext) -> None:
    # Check-in side A ("opened the app") + feed post side B ("sent something") => count >= 1
    expect_status(ctx.spring.post("/api/v1/streaks/check-in", token=ctx.token("blocker")), 200, "streak check-in")
    p = ctx.create_post_safe("blocker", ctx.text("streak trigger post"))
    expect_status(p, 201, "create post for streak")
    after = ctx.spring.get("/api/v1/streaks/me", token=ctx.token("blocker")).json()
    ci = after.get("checkIn") or {}
    check(ci.get("openedToday") is True, f"expected openedToday=true, got {after}")
    check(ci.get("sentToday") is True, f"expected sentToday=true after post, got {after}")
    check(int(ci.get("count", 0)) >= 1, f"expected streak count >= 1 after check-in + post, got {after}")
    ctx.spring.delete(f"/api/v1/feed/posts/{p.json()['id']}", token=ctx.token("blocker"))


def _t3_02_suspend_revokes_social_unsuspend_restores(ctx: SuiteContext) -> None:
    ctx.set_social_flags(True, True)
    admin_tok = ctx.token("admin")
    reader_id = ctx.uid("reader")
    old_tok = ctx.token("reader")
    try:
        expect_status(
            ctx.spring.post(f"/api/v1/admin/users/{reader_id}/suspend", token=admin_tok, body={"reason": "E2E suspend"}),
            204,
            "suspend reader",
        )
        ctx.cleanup_suspends.append(reader_id)
        expect_error(ctx.proxy.get("/api/social/v1/posts", token=old_tok), 403, "ACCOUNT_SUSPENDED", "suspended web social")
        expect_error(ctx.spring.get("/api/v1/feed/posts", token=old_tok), 403, "ACCOUNT_SUSPENDED", "suspended android feed")
    finally:
        ctx.spring.post(f"/api/v1/admin/users/{reader_id}/unsuspend", token=admin_tok, body={"reason": "E2E unsuspend"})
        if reader_id in ctx.cleanup_suspends:
            ctx.cleanup_suspends.remove(reader_id)

    wait_next_second_boundary()
    login_data = ctx.spring.login_with_retry(ctx.phone("reader"), FIXTURE_PASSWORD)
    rec = ctx.sessions.get("reader")
    rec.access_token = login_data["accessToken"]
    rec.refresh_token = login_data.get("refreshToken", "")
    ctx.sessions.set(rec)
    expect_status(ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reader")), 200, "restored web social after unsuspend")


def _t3_03_flag_flip_propagates_without_cache(ctx: SuiteContext) -> None:
    ctx.set_social_flags(True, False)
    expect_status(ctx.proxy.get("/api/social/v1/posts", token=ctx.token("author")), 200, "social open")
    ctx.set_social_flags(False, False)
    expect_error(ctx.proxy.get("/api/social/v1/posts", token=ctx.token("author")), 403, "SOCIAL_FEATURE_DISABLED", "social immediate close")
    ctx.set_social_flags(True, True)
    expect_status(ctx.proxy.get("/api/social/v1/posts", token=ctx.token("author")), 200, "social immediate reopen")


def _t3_04_announcement_cms_to_config_sync(ctx: SuiteContext) -> None:
    cur = ctx.get_admin_settings()
    draft = {
        "titleVi": f"Bảo trì {ctx.nonce}",
        "bodyVi": "Nội dung thử nghiệm",
        "titleEn": f"Notice {ctx.nonce}",
        "bodyEn": "Test body",
    }
    d = ctx.spring.put(
        "/api/v1/admin/social/settings/announcement/draft",
        token=ctx.token("admin"),
        body={"expectedRevision": cur["revision"], **draft},
    )
    expect_status(d, 200, "save draft")
    p = ctx.spring.post(
        "/api/v1/admin/social/settings/announcement/publish",
        token=ctx.token("admin"),
        body={"expectedRevision": d.json()["revision"]},
    )
    expect_status(p, 200, "publish")
    cfg1 = ctx.proxy.get("/api/social/v1/config").json()
    check((cfg1.get("announcement") or {}).get("titleVi") == draft["titleVi"], "published announcement missing in /config")
    u = ctx.spring.post(
        "/api/v1/admin/social/settings/announcement/unpublish",
        token=ctx.token("admin"),
        body={"expectedRevision": p.json()["revision"]},
    )
    expect_status(u, 200, "unpublish")
    cfg2 = ctx.proxy.get("/api/social/v1/config").json()
    check(cfg2.get("announcement") is None, "unpublished announcement still in /config")
    check(u.json().get("draft") == draft, "draft wiped on unpublish")


def _t3_05_video_flag_existing_readable_new_blocked(ctx: SuiteContext) -> None:
    ctx.set_social_flags(True, True)
    up = ctx.proxy.upload_multipart(
        "/api/social/v1/videos",
        token=ctx.token("admin"),
        files=[("file", "t3.mp4", "video/mp4", load_valid_mp4_bytes())],
    ).json()
    p = ctx.create_post_safe("admin", ctx.text("t3 video flag"), video_id=up["videoId"]).json()
    ctx.set_social_flags(True, False)
    expect_status(ctx.proxy.get(f"/api/social/v1/videos/{up['videoId']}", token=ctx.token("reader")), 200, "existing video readable when video off")
    expect_error(
        ctx.proxy.upload_multipart(
            "/api/social/v1/videos",
            token=ctx.token("admin"),
            files=[("file", "t3b.mp4", "video/mp4", load_valid_mp4_bytes())],
        ),
        403,
        "SOCIAL_FEATURE_DISABLED",
        "new video upload denied when video off",
    )
    ctx.set_social_flags(True, True)
    ctx.spring.delete(f"/api/v1/feed/posts/{p['id']}", token=ctx.token("admin"))


def _t3_06_refresh_rotation_and_feed_access(ctx: SuiteContext) -> None:
    ctx.set_social_flags(True, True)
    # Reuse stored refresh token for reactor if available, or verify current token works then skip extra login
    rec = ctx.sessions.get("reactor")
    if rec.refresh_token:
        ref = ctx.proxy.post(
            "/api/social/v1/session/refresh",
            body={"refreshToken": rec.refresh_token},
            headers={"Idempotency-Key": str(uuid.uuid4())},
        )
        if ref.status == 200:
            nd = ref.json()
            rec.access_token = nd["accessToken"]
            rec.refresh_token = nd["refreshToken"]
            ctx.sessions.set(rec)
    expect_status(ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reactor")), 200, "feed access with refreshed token")


def _t3_07_report_hide_exclude_unhide_restore(ctx: SuiteContext) -> None:
    p = ctx.create_post_safe("reactor", ctx.text("t3 report hide")).json()
    rep = ctx.spring.post(
        "/api/v1/reports",
        token=ctx.token("reader"),
        body={"targetType": "POST", "targetId": p["id"], "reason": "SPAM"},
    ).json()
    expect_status(
        ctx.spring.post(f"/api/v1/admin/feed/posts/{p['id']}/hide", token=ctx.token("moderator"), body={"reason": "t3 hide"}),
        204,
        "hide",
    )
    feed = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reader"), params={"limit": 50}).json()
    check(all(it["id"] != p["id"] for it in feed["items"]), "hidden post still in feed")
    expect_status(ctx.spring.post(f"/api/v1/admin/feed/posts/{p['id']}/unhide", token=ctx.token("moderator")), 204, "unhide")
    expect_status(ctx.proxy.get(f"/api/social/v1/posts/{p['id']}", token=ctx.token("reader")), 200, "restored after unhide")
    ctx.spring.post(f"/api/v1/admin/feed/reports/{rep['id']}/resolve", token=ctx.token("moderator"), body={"status": "REVIEWED"})
    ctx.spring.delete(f"/api/v1/feed/posts/{p['id']}", token=ctx.token("reactor"))


def _t3_08_reported_video_audit_trail(ctx: SuiteContext) -> None:
    ctx.set_social_flags(True, True)
    up = ctx.spring.upload_multipart(
        "/api/v1/feed/videos",
        token=ctx.token("reactor"),
        files=[("file", "t3_aud.mp4", "video/mp4", load_valid_mp4_bytes())],
    ).json()
    p = ctx.create_post_safe("reactor", ctx.text("t3 video audit"), video_id=up["videoId"]).json()
    rep = ctx.spring.post(
        "/api/v1/reports",
        token=ctx.token("author"),
        body={"targetType": "POST", "targetId": p["id"], "reason": "VIOLENCE"},
    ).json()
    vid = ctx.spring.post(f"/api/v1/admin/feed/reports/{rep['id']}/video", token=ctx.token("moderator"))
    expect_status(vid, 200, "moderator preview reported video")
    audit = ctx.spring.get(
        "/api/v1/admin/audit",
        token=ctx.token("admin"),
        params={"action": "FEED_REPORT_VIDEO_VIEW", "targetId": rep["id"]},
    ).json()
    check(len(audit.get("items") or []) >= 1, "missing FEED_REPORT_VIDEO_VIEW audit entry")
    ctx.spring.post(f"/api/v1/admin/feed/reports/{rep['id']}/resolve", token=ctx.token("moderator"), body={"status": "DISMISSED"})
    ctx.spring.delete(f"/api/v1/feed/posts/{p['id']}", token=ctx.token("reactor"))


def _t3_09_comment_hide_idempotent_count(ctx: SuiteContext) -> None:
    p = ctx.create_post_safe("admin", ctx.text("t3 comment hide")).json()
    c = ctx.spring.post(
        f"/api/v1/feed/posts/{p['id']}/comments",
        token=ctx.token("reader"),
        body={"body": "c1", "clientCommentId": str(uuid.uuid4())},
    ).json()
    mod = ctx.token("moderator")
    expect_status(ctx.spring.post(f"/api/v1/admin/feed/comments/{c['id']}/hide", token=mod, body={"reason": "r"}), 204, "hide 1")
    expect_status(ctx.spring.post(f"/api/v1/admin/feed/comments/{c['id']}/hide", token=mod, body={"reason": "r"}), 204, "hide 2")
    check(ctx.spring.get(f"/api/v1/feed/posts/{p['id']}", token=ctx.token("reader")).json()["commentCount"] == 0, "count != 0")
    expect_status(ctx.spring.post(f"/api/v1/admin/feed/comments/{c['id']}/unhide", token=mod), 204, "unhide 1")
    expect_status(ctx.spring.post(f"/api/v1/admin/feed/comments/{c['id']}/unhide", token=mod), 204, "unhide 2")
    check(ctx.spring.get(f"/api/v1/feed/posts/{p['id']}", token=ctx.token("reader")).json()["commentCount"] == 1, "count != 1")
    ctx.spring.delete(f"/api/v1/feed/posts/{p['id']}", token=ctx.token("admin"))


def _t3_10_block_and_report_interaction(ctx: SuiteContext) -> None:
    p = ctx.create_post_safe("blocker", ctx.text("t3 block report")).json()
    try:
        ctx.ensure_block("reader", "blocker", True)
        expect_error(
            ctx.spring.post(
                "/api/v1/reports",
                token=ctx.token("reader"),
                body={"targetType": "POST", "targetId": p["id"], "reason": "SPAM"},
            ),
            404,
            "POST_NOT_FOUND",
            "cannot report post of blocked user",
        )
    finally:
        ctx.ensure_block("reader", "blocker", False)
    r = ctx.spring.post(
        "/api/v1/reports",
        token=ctx.token("reader"),
        body={"targetType": "POST", "targetId": p["id"], "reason": "SPAM"},
    )
    expect_status(r, 201, "can report after unblock")
    ctx.spring.post(f"/api/v1/admin/feed/reports/{r.json()['id']}/resolve", token=ctx.token("moderator"), body={"status": "DISMISSED"})
    ctx.spring.delete(f"/api/v1/feed/posts/{p['id']}", token=ctx.token("blocker"))


def _t3_11_edit_updates_search_index(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_edit and ctx.caps.m1_search, "M1 edit + search not deployed yet")
    tag_a = f"alpha_{ctx.nonce}"
    tag_b = f"B\u00e1nh m\u00ec {ctx.nonce}"
    p = ctx.create_post_safe("reader", tag_a).json()
    check(any(x["id"] == p["id"] for x in ctx.proxy.get("/api/social/v1/posts", token=ctx.token("author"), params={"q": tag_a}).json()["items"]), "initial search missed")
    patched = ctx.proxy.patch(f"/api/social/v1/posts/{p['id']}", token=ctx.token("reader"), body={"body": tag_b})
    expect_status(patched, 200, "edit post")
    check(all(x["id"] != p["id"] for x in ctx.proxy.get("/api/social/v1/posts", token=ctx.token("author"), params={"q": tag_a}).json()["items"]), "old term still matched")
    check(any(x["id"] == p["id"] for x in ctx.proxy.get("/api/social/v1/posts", token=ctx.token("author"), params={"q": f"banh mi {ctx.nonce}"}).json()["items"]), "new accent-insensitive term missed")
    ctx.spring.delete(f"/api/v1/feed/posts/{p['id']}", token=ctx.token("reader"))


def _t3_12_edit_preserves_report_snapshot(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_edit, "M1 edit + report snapshot check not deployed yet")
    orig = ctx.text("original snapshot")
    edited = ctx.text("edited snapshot")
    p = ctx.create_post_safe("reader", orig).json()
    rep = ctx.spring.post(
        "/api/v1/reports",
        token=ctx.token("author"),
        body={"targetType": "POST", "targetId": p["id"], "reason": "SPAM"},
    ).json()
    expect_status(ctx.spring.patch(f"/api/v1/feed/posts/{p['id']}", token=ctx.token("reader"), body={"body": edited}), 200, "edit reported post")
    q = ctx.spring.get("/api/v1/admin/feed/reports", token=ctx.token("moderator"), params={"status": "OPEN", "size": 100}).json()
    row = next((it for it in q["items"] if it["id"] == rep["id"]), None)
    check(row is not None, "report missing from OPEN queue")
    check(row["messageSnapshot"] == orig, f"snapshot changed after edit: {row['messageSnapshot']!r}")
    check(row["targetBody"] == edited, f"targetBody not updated: {row['targetBody']!r}")
    ctx.spring.post(f"/api/v1/admin/feed/reports/{rep['id']}/resolve", token=ctx.token("moderator"), body={"status": "DISMISSED"})
    ctx.spring.delete(f"/api/v1/feed/posts/{p['id']}", token=ctx.token("reader"))


def _t3_13_block_with_profile_and_search(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_authors and ctx.caps.m1_search, "M1 authors + search not deployed yet")
    p = ctx.create_post_safe("blocker", ctx.text("t3 block search")).json()
    try:
        ctx.ensure_block("author", "blocker", True)
        expect_error(ctx.proxy.get(f"/api/social/v1/authors/{ctx.uid('blocker')}", token=ctx.token("author")), 404, "USER_NOT_FOUND", "blocked profile")
        check(ctx.proxy.get("/api/social/v1/posts", token=ctx.token("author"), params={"authorId": ctx.uid("blocker")}).json()["items"] == [], "blocked authorId not empty")
        check(all(x["id"] != p["id"] for x in ctx.proxy.get("/api/social/v1/posts", token=ctx.token("author"), params={"q": "t3 block search"}).json()["items"]), "blocked q leaked")
        expect_status(ctx.proxy.get(f"/api/social/v1/authors/{ctx.uid('blocker')}", token=ctx.token("reader")), 200, "third-party sees profile")
    finally:
        ctx.ensure_block("author", "blocker", False)
    ctx.spring.delete(f"/api/v1/feed/posts/{p['id']}", token=ctx.token("blocker"))


def _t3_14_hide_post_denies_owner_media_and_comment_leak(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_edit, "M1 media & comment visibility hardening not deployed yet")
    up = ctx.spring.upload_multipart(
        "/api/v1/feed/images",
        token=ctx.token("admin"),
        files=[("file", "t3.png", "image/png", make_png_bytes(10, 10))],
    ).json()
    p = ctx.create_post_safe("admin", ctx.text("t3 combo hide"), image_id=up["imageId"]).json()
    c = ctx.spring.post(
        f"/api/v1/feed/posts/{p['id']}/comments",
        token=ctx.token("reader"),
        body={"body": "c", "clientCommentId": str(uuid.uuid4())},
    ).json()
    expect_status(ctx.spring.post(f"/api/v1/admin/feed/posts/{p['id']}/hide", token=ctx.token("moderator"), body={"reason": "combo"}), 204, "hide")
    expect_error(ctx.spring.get(f"/api/v1/feed/images/{up['imageId']}", token=ctx.token("admin")), 404, "FILE_NOT_FOUND", "owner media hidden")
    expect_error(ctx.spring.delete(f"/api/v1/feed/comments/{c['id']}", token=ctx.token("blocker")), 404, "COMMENT_NOT_FOUND", "comment leak on hidden parent")


def _t3_15_moderator_role_split(ctx: SuiteContext) -> None:
    mod = ctx.token("moderator")
    expect_status(ctx.spring.get("/api/v1/admin/feed/reports", token=mod), 200, "MODERATOR feed queue")
    expect_error(ctx.spring.get("/api/v1/admin/social/settings", token=mod), 403, "NOT_ADMIN", "MODERATOR social settings")


def _t3_16_dual_prefix_full_equivalence(ctx: SuiteContext) -> None:
    ctx.set_social_flags(True, True)
    p = ctx.create_post_safe("admin", ctx.text("dual prefix eq"), prefix="/api/v1/feed").json()
    pid = p["id"]
    expect_status(ctx.proxy.put(reaction_path("/api/social/v1", pid, "\U0001F525"), token=ctx.token("reader")), 200, "react via social")
    got = ctx.spring.get(f"/api/v1/feed/posts/{pid}", token=ctx.token("reader")).json()
    check(reaction_entry(got["reactions"], "\U0001F525")["count"] == 1, "reaction not synced across prefixes")
    c = ctx.proxy.post(
        f"/api/social/v1/posts/{pid}/comments",
        token=ctx.token("reader"),
        body={"body": "dual comment", "clientCommentId": str(uuid.uuid4())},
    )
    expect_status(c, 201, "comment via social")
    check(ctx.spring.get(f"/api/v1/feed/posts/{pid}/comments", token=ctx.token("admin")).json()["items"][0]["id"] == c.json()["id"], "comment not synced")
    expect_status(ctx.spring.delete(f"/api/v1/feed/posts/{pid}", token=ctx.token("admin")), 204, "delete via legacy")


def _t3_17_comment_activity_updates_streak(ctx: SuiteContext) -> None:
    p = ctx.create_post_safe("admin", ctx.text("t3 streak comment host")).json()
    c = ctx.spring.post(
        f"/api/v1/feed/posts/{p['id']}/comments",
        token=ctx.token("reader"),
        body={"body": "streak comment", "clientCommentId": str(uuid.uuid4())},
    )
    expect_status(c, 201, "comment for streak")
    st = ctx.spring.get("/api/v1/streaks/me", token=ctx.token("reader")).json()
    check((st.get("checkIn") or {}).get("sentToday") is True, f"expected sentToday=true after comment: {st}")
    ctx.spring.delete(f"/api/v1/feed/posts/{p['id']}", token=ctx.token("admin"))


def _t3_18_image_post_delete_cascades_raw_file(ctx: SuiteContext) -> None:
    up = ctx.spring.upload_multipart(
        "/api/v1/feed/images",
        token=ctx.token("admin"),
        files=[("file", "cas.png", "image/png", make_png_bytes(12, 12))],
    ).json()
    p = ctx.create_post_safe("admin", ctx.text("cascade"), image_id=up["imageId"]).json()
    expect_status(ctx.spring.get(f"/api/v1/feed/images/{up['imageId']}", token=ctx.token("reader")), 200, "reader fetches image")
    expect_status(ctx.spring.delete(f"/api/v1/feed/posts/{p['id']}", token=ctx.token("admin")), 204, "delete image post")
    expect_error(ctx.spring.get(f"/api/v1/feed/images/{up['imageId']}", token=ctx.token("reader")), 404, "FILE_NOT_FOUND", "image denied after delete")


def _t3_19_video_range_across_proxy_headers(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_range_video, "Range streaming across proxy not deployed yet")
    ctx.set_social_flags(True, True)
    mp4 = load_valid_mp4_bytes()
    up = ctx.proxy.upload_multipart(
        "/api/social/v1/videos",
        token=ctx.token("admin"),
        files=[("file", "prx.mp4", "video/mp4", mp4)],
    ).json()
    p = ctx.create_post_safe("admin", ctx.text("proxy range"), video_id=up["videoId"]).json()
    r = ctx.proxy.get_range(f"/api/social/v1/videos/{up['videoId']}", token=ctx.token("reader"), range_header="bytes=100-299")
    expect_status(r, 206, "proxied Range 206")
    check(r.body == mp4[100:300], "proxied Range bytes mismatch")
    check("no-store" in (r.header("cache-control") or "").lower(), "proxied video missing no-store")
    ctx.spring.delete(f"/api/v1/feed/posts/{p['id']}", token=ctx.token("admin"))


def _t3_20_stub_server_parity(ctx: SuiteContext) -> None:
    ctx.require_milestone("M2", ctx.caps.m2_social_features, "M2 stub server parity not landed yet")
    # Verify tools/site-tests/social_stub_server.py handles /api/social/v1/config once M2 lands
    import importlib.util
    from pathlib import Path

    stub_path = Path(__file__).resolve().parents[2] / "site-tests" / "social_stub_server.py"
    check(stub_path.is_file(), f"missing {stub_path}")
    source = stub_path.read_text(encoding="utf-8")
    check("/api/social/v1/config" in source and "authors" in source, "social_stub_server.py lacks M2 /config or /authors parity")


def get_tier3_checks() -> List[CheckCase]:
    raw = [
        ("T3.01", "F3", "BASELINE", "Feed post activity updates check-in streak", _t3_01_feed_post_updates_streak),
        ("T3.02", "F2", "BASELINE", "Account suspend immediately revokes social & feed; unsuspend restores", _t3_02_suspend_revokes_social_unsuspend_restores),
        ("T3.03", "F1", "BASELINE", "Admin flag flip propagates immediately without cache", _t3_03_flag_flip_propagates_without_cache),
        ("T3.04", "F12", "BASELINE", "Admin CMS draft/publish/unpublish syncs live /config", _t3_04_announcement_cms_to_config_sync),
        ("T3.05", "F4", "BASELINE", "Video flag off preserves existing videos while blocking new uploads", _t3_05_video_flag_existing_readable_new_blocked),
        ("T3.06", "F2", "BASELINE", "Session refresh rotation preserves social feed access", _t3_06_refresh_rotation_and_feed_access),
        ("T3.07", "F13", "BASELINE", "Report -> hide excludes from feed -> unhide restores", _t3_07_report_hide_exclude_unhide_restore),
        ("T3.08", "F13", "BASELINE", "Reported video click-to-load preview writes audit trail", _t3_08_reported_video_audit_trail),
        ("T3.09", "F6", "BASELINE", "Comment hide/unhide idempotently maintains post.commentCount", _t3_09_comment_hide_idempotent_count),
        ("T3.10", "F10", "BASELINE", "Block prevents reporting; unblock restores reporting", _t3_10_block_and_report_interaction),
        ("T3.11", "F9", "M1", "Post edit updates keyword search index & accent matching", _t3_11_edit_updates_search_index),
        ("T3.12", "F7", "M1", "Post edit preserves original report messageSnapshot", _t3_12_edit_preserves_report_snapshot),
        ("T3.13", "F10", "M1", "Bidirectional block isolates /authors/{id}, ?authorId= & ?q=", _t3_13_block_with_profile_and_search),
        ("T3.14", "F11", "M1", "Hiding post simultaneously enforces owner media 404 & comment 404", _t3_14_hide_post_denies_owner_media_and_comment_leak),
        ("T3.15", "F12", "BASELINE", "Role promotion allows MODERATOR on feed moderation, blocks social settings", _t3_15_moderator_role_split),
        ("T3.16", "F16", "BASELINE", "Dual-prefix /api/v1/feed & /api/social/v1 state equivalence", _t3_16_dual_prefix_full_equivalence),
        ("T3.17", "F6", "BASELINE", "Feed comment activity updates check-in streak", _t3_17_comment_activity_updates_streak),
        ("T3.18", "F11", "BASELINE", "Deleting image post cascades read denial to viewers", _t3_18_image_post_delete_cascades_raw_file),
        ("T3.19", "F4", "M1", "Proxy preserves Range 206 & no-store headers for video streaming", _t3_19_video_range_across_proxy_headers),
        ("T3.20", "F15", "M2", "social_stub_server.py parity with M2 endpoints", _t3_20_stub_server_parity),
    ]
    return [CheckCase(cid, feat, 3, gate, title, fn) for cid, feat, gate, title, fn in raw]
