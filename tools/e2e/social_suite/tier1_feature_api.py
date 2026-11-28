"""Tier 1: Core feature API & static contract checks across F1-F16 (80 checks)."""

from __future__ import annotations

import uuid
from typing import List

from .client import (
    DUAL_FEED_PREFIXES,
    CheckCase,
    SuiteContext,
    check,
    expect_error,
    expect_status,
    reaction_entry,
    reaction_path,
)
from .fixtures import (
    DISTINCT_EMOJIS,
    FIXTURE_PASSWORD,
    load_valid_mp4_bytes,
    make_jpeg_bytes,
    make_png_bytes,
    make_webp_bytes,
)


def _assert_author_public(author: dict) -> None:
    check(isinstance(author, dict), "author object missing")
    check(
        set(author.keys()) <= {"id", "displayName", "avatarUrl"},
        f"author leaked extra keys: {sorted(author.keys())}",
    )
    check(bool(author.get("id")), "author.id missing")


def _ensure_web_and_video(ctx: SuiteContext, web: bool = True, video: bool = True) -> dict:
    return ctx.set_social_flags(web, video)


# --------------------------------------------------------------------------- F1
def _t1_f1_01(ctx: SuiteContext) -> None:
    ctx.set_social_flags(False, False)
    resp = ctx.proxy.get("/api/social/v1/config")
    expect_status(resp, 200, "GET /api/social/v1/config")
    cc = (resp.header("cache-control") or "").lower()
    check("no-store" in cc, f"config missing no-store Cache-Control: {cc!r}")
    data = resp.json()
    check(set(data.keys()) == {"webEnabled", "videoUploadsEnabled", "announcement"}, f"unexpected keys: {data}")
    check(data["webEnabled"] is False, "expected webEnabled=false")
    check(data["videoUploadsEnabled"] is False, "expected videoUploadsEnabled=false")


def _t1_f1_02(ctx: SuiteContext) -> None:
    # When webEnabled=false and videoUploadsEnabled=true in DB, public config masks videoUploadsEnabled=false
    ctx.set_social_flags(False, True)
    cfg = ctx.proxy.get("/api/social/v1/config")
    expect_status(cfg, 200, "config while webEnabled=false")
    check(cfg.json()["webEnabled"] is False and cfg.json()["videoUploadsEnabled"] is False,
          f"expected masked videoUploadsEnabled=false: {cfg.json()}")
    denied = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reader"))
    expect_error(denied, 403, "SOCIAL_FEATURE_DISABLED", "GET /api/social/v1/posts when disabled")


def _t1_f1_03(ctx: SuiteContext) -> None:
    ctx.set_social_flags(False, False)
    anon_cfg = ctx.spring.get("/api/v1/feed/config")
    expect_status(anon_cfg, 401, "unauthenticated /api/v1/feed/config")
    legacy_cfg = ctx.spring.get("/api/v1/feed/config", token=ctx.token("reader"))
    check(legacy_cfg.status == 404, f"expected 404 on authenticated /api/v1/feed/config, got {legacy_cfg}")
    android_feed = ctx.spring.get("/api/v1/feed/posts", token=ctx.token("reader"))
    expect_status(android_feed, 200, "legacy /api/v1/feed/posts while webEnabled=false")


def _t1_f1_04(ctx: SuiteContext) -> None:
    ctx.set_social_flags(True, False)
    cfg = ctx.proxy.get("/api/social/v1/config")
    expect_status(cfg, 200, "config after enabling web")
    check(cfg.json()["webEnabled"] is True, "expected webEnabled=true")
    posts = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reader"))
    expect_status(posts, 200, "GET /api/social/v1/posts after enabling web")


def _t1_f1_05(ctx: SuiteContext) -> None:
    ctx.set_social_flags(True, False)
    mp4 = load_valid_mp4_bytes()
    for prefix in DUAL_FEED_PREFIXES:
        up = ctx.spring.upload_multipart(
            f"{prefix}/videos",
            token=ctx.token("author"),
            files=[("file", "clip.mp4", "video/mp4", mp4)],
        )
        expect_error(up, 403, "SOCIAL_FEATURE_DISABLED", f"POST {prefix}/videos when video off")
        bind = ctx.spring.post(
            f"{prefix}/posts",
            token=ctx.token("author"),
            body={"body": ctx.text("video off"), "clientPostId": str(uuid.uuid4()), "videoId": str(uuid.uuid4())},
        )
        expect_error(bind, 403, "SOCIAL_FEATURE_DISABLED", f"POST {prefix}/posts videoId when video off")


# --------------------------------------------------------------------------- F2
SESSION_PROBE_PHONE = "+84900000956"


def _t1_f2_01(ctx: SuiteContext) -> None:
    # Register an ephemeral 7th account so session-revocation tests never disturb the 6 feed fixtures
    reg = ctx.spring.register_or_login(SESSION_PROBE_PHONE, FIXTURE_PASSWORD, "Social E2E SessionProbe")
    ctx.shared["f2_probe_uid"] = str(reg["user"]["id"])
    resp = ctx.proxy.post(
        "/api/social/v1/session/login",
        body={"identifier": SESSION_PROBE_PHONE, "password": FIXTURE_PASSWORD},
    )
    expect_status(resp, 200, "proxy social login")
    data = resp.json()
    check(bool(data.get("accessToken")) and bool(data.get("refreshToken")), "login missing tokens")
    check((data.get("user") or {}).get("id") == ctx.shared["f2_probe_uid"], "login user.id mismatch")
    ctx.shared["f2_session"] = data


def _t1_f2_02(ctx: SuiteContext) -> None:
    tok = ctx.shared["f2_session"]["accessToken"]
    me = ctx.spring.get("/api/v1/users/me", token=tok)
    expect_status(me, 200, "GET /api/v1/users/me")
    check(me.json().get("id") == ctx.shared["f2_probe_uid"], "me.id mismatch")


def _t1_f2_03(ctx: SuiteContext) -> None:
    sess = ctx.shared["f2_session"]
    idem = str(uuid.uuid4())
    ref = ctx.proxy.post(
        "/api/social/v1/session/refresh",
        body={"refreshToken": sess["refreshToken"]},
        headers={"Idempotency-Key": idem},
    )
    expect_status(ref, 200, "proxy session refresh")
    new_data = ref.json()
    check(new_data["accessToken"] != sess["accessToken"], "refresh did not issue new accessToken")
    check(new_data["refreshToken"] != sess["refreshToken"], "refresh did not rotate refreshToken")
    ctx.shared["f2_prev_refresh"] = sess["refreshToken"]
    ctx.shared["f2_idem"] = idem
    ctx.shared["f2_session"] = new_data


def _t1_f2_04(ctx: SuiteContext) -> None:
    # Replaying same Idempotency-Key returns same rotated pair; reusing old token with new key fails
    replay = ctx.proxy.post(
        "/api/social/v1/session/refresh",
        body={"refreshToken": ctx.shared["f2_prev_refresh"]},
        headers={"Idempotency-Key": ctx.shared["f2_idem"]},
    )
    expect_status(replay, 200, "idempotent refresh replay")
    check(replay.json()["refreshToken"] == ctx.shared["f2_session"]["refreshToken"],
          "idempotent replay did not return same refreshToken")

    reuse = ctx.proxy.post(
        "/api/social/v1/session/refresh",
        body={"refreshToken": ctx.shared["f2_prev_refresh"]},
        headers={"Idempotency-Key": str(uuid.uuid4())},
    )
    expect_status(reuse, 401, "reuse rotated refresh token")


def _t1_f2_05(ctx: SuiteContext) -> None:
    fresh = ctx.proxy.post(
        "/api/social/v1/session/login",
        body={"identifier": SESSION_PROBE_PHONE, "password": FIXTURE_PASSWORD},
    )
    expect_status(fresh, 200, "fresh login for logout test")
    d = fresh.json()
    out = ctx.proxy.post(
        "/api/social/v1/session/logout",
        token=d["accessToken"],
        body={"refreshToken": d["refreshToken"]},
    )
    expect_status(out, 204, "proxy session logout")
    after = ctx.proxy.post(
        "/api/social/v1/session/refresh",
        body={"refreshToken": d["refreshToken"]},
        headers={"Idempotency-Key": str(uuid.uuid4())},
    )
    expect_status(after, 401, "refresh after logout must fail")


# --------------------------------------------------------------------------- F3
def _t1_f3_01(ctx: SuiteContext) -> None:
    _ensure_web_and_video(ctx, True, True)
    body = ctx.text("shared primary post")
    resp = ctx.create_post_safe("author", f"  {body}  ", prefix="/api/v1/feed")
    expect_status(resp, 201, "create text post")
    post = resp.json()
    ctx.shared["post_author"] = post
    check(post.get("body") == body, f"body not trimmed: {post.get('body')!r}")
    check(post.get("mine") is True, "mine should be true for author")
    check(post.get("commentCount") == 0, "expected commentCount=0")
    check(post.get("reactions") == [], "expected reactions=[]")
    _assert_author_public(post.get("author"))


def _t1_f3_02(ctx: SuiteContext) -> None:
    _ensure_web_and_video(ctx, True, True)
    body = ctx.text("social prefix post")
    resp = ctx.create_post_safe("reader", body, prefix="/api/social/v1", via_proxy=True)
    expect_status(resp, 201, "create post via /api/social/v1 proxy")
    post = resp.json()
    ctx.shared["post_reader_del"] = post
    for prefix in DUAL_FEED_PREFIXES:
        got = ctx.proxy.get(f"{prefix}/posts/{post['id']}", token=ctx.token("author"))
        expect_status(got, 200, f"GET {prefix}/posts/{{id}}")
        check(got.json().get("body") == body and got.json().get("mine") is False, "viewer DTO mismatch")


def _t1_f3_03(ctx: SuiteContext) -> None:
    client_id = str(uuid.uuid4())
    body = ctx.text("idempotent replay post")
    r1 = ctx.create_post_safe("blocker", body, client_post_id=client_id)
    expect_status(r1, 201, "first idempotent post create")
    r2 = ctx.create_post_safe("blocker", body, client_post_id=client_id)
    expect_status(r2, 200, "second idempotent post replay")
    check(r2.json()["id"] == r1.json()["id"], "replay created different post id")
    ctx.shared["post_blocker_cap"] = r1.json()


def _t1_f3_04(ctx: SuiteContext) -> None:
    p1 = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("author"), params={"limit": 2})
    expect_status(p1, 200, "page 1 posts")
    d1 = p1.json()
    items1 = d1.get("items") or []
    check(len(items1) == 2 and bool(d1.get("nextCursor")), "expected 2 items + nextCursor")
    p2 = ctx.proxy.get(
        "/api/social/v1/posts",
        token=ctx.token("author"),
        params={"limit": 2, "cursor": d1["nextCursor"]},
    )
    expect_status(p2, 200, "page 2 posts")
    ids1 = {x["id"] for x in items1}
    ids2 = {x["id"] for x in (p2.json().get("items") or [])}
    check(not (ids1 & ids2), "cursor pagination overlapped items")


def _t1_f3_05(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_edit, "PATCH /posts/{id} not deployed yet")
    target = ctx.shared["post_reader_del"]
    new_body = ctx.text("edited reader post")
    patch = ctx.proxy.patch(
        f"/api/social/v1/posts/{target['id']}",
        token=ctx.token("reader"),
        body={"body": new_body},
    )
    expect_status(patch, 200, "PATCH /api/social/v1/posts/{id}")
    patched = patch.json()
    check(patched.get("body") == new_body, "patched post body mismatch")
    check(bool(patched.get("editedAt")), "patched post missing editedAt")

    # Verify DELETE /posts/{id} works cleanly
    d_resp = ctx.proxy.delete(f"/api/social/v1/posts/{target['id']}", token=ctx.token("reader"))
    expect_status(d_resp, 204, "DELETE /api/social/v1/posts/{id}")
    expect_error(
        ctx.proxy.get(f"/api/social/v1/posts/{target['id']}", token=ctx.token("author")),
        404,
        "POST_NOT_FOUND",
        "deleted post fetch",
    )
    ctx.shared["post_reader_del_deleted"] = True


# --------------------------------------------------------------------------- F4
def _t1_f4_01(ctx: SuiteContext) -> None:
    _ensure_web_and_video(ctx, True, True)
    png = make_png_bytes(16, 16)
    up = ctx.proxy.upload_multipart(
        "/api/social/v1/images",
        token=ctx.token("author"),
        files=[("file", "dot.png", "image/png", png)],
        fields={"width": "16", "height": "16"},
    )
    expect_status(up, 201, "upload PNG image")
    img = up.json()
    check(img.get("mimeType") == "image/png", f"unexpected mimeType {img.get('mimeType')}")
    check(img.get("width") == 16 and img.get("height") == 16, "dimensions not echoed")

    post_resp = ctx.create_post_safe("author", None, image_id=img["imageId"], prefix="/api/social/v1", via_proxy=True)
    expect_status(post_resp, 201, "create image-only post")
    post = post_resp.json()
    ctx.shared["image_post"] = post
    ctx.shared["image_bytes"] = png
    check(post.get("body") is None, "image-only post should have null body")
    check((post.get("image") or {}).get("mimeType") == "image/png", "post image mimeType mismatch")

    fetched = ctx.proxy.get(f"/api/social/v1/images/{img['imageId']}", token=ctx.token("reader"), headers={"Accept": "*/*"})
    expect_status(fetched, 200, "reader fetches bound PNG")
    check(fetched.body == png, "fetched PNG bytes mismatch")
    check(fetched.header("x-content-type-options") == "nosniff", "missing nosniff header")


def _t1_f4_02(ctx: SuiteContext) -> None:
    jpg = make_jpeg_bytes(20, 20)
    up = ctx.spring.upload_multipart(
        "/api/v1/feed/images",
        token=ctx.token("reader"),
        files=[("file", "photo.jpg", "image/jpeg", jpg)],
    )
    expect_status(up, 201, "upload JPEG image")
    img = up.json()
    check(img.get("mimeType") == "image/jpeg", f"JPEG mimeType mismatch: {img.get('mimeType')}")
    got = ctx.spring.get(img["url"], token=ctx.token("reader"), headers={"Accept": "*/*"})
    expect_status(got, 200, "owner fetches unbound JPEG")
    check(got.body == jpg, "JPEG bytes mismatch")


def _t1_f4_03(ctx: SuiteContext) -> None:
    webp = make_webp_bytes(20, 20)
    up = ctx.spring.upload_multipart(
        "/api/v1/feed/images",
        token=ctx.token("blocker"),
        files=[("file", "photo.webp", "image/webp", webp)],
    )
    expect_status(up, 201, "upload WebP image")
    img = up.json()
    check(img.get("mimeType") == "image/webp", f"WebP mimeType mismatch: {img.get('mimeType')}")
    got = ctx.spring.get(img["url"], token=ctx.token("blocker"), headers={"Accept": "*/*"})
    expect_status(got, 200, "owner fetches unbound WebP")
    check(got.body == webp, "WebP bytes mismatch")


def _t1_f4_04(ctx: SuiteContext) -> None:
    _ensure_web_and_video(ctx, True, True)
    mp4 = load_valid_mp4_bytes()
    up = ctx.proxy.upload_multipart(
        "/api/social/v1/videos",
        token=ctx.token("reactor"),
        files=[("file", "clip.mp4", "video/mp4", mp4)],
    )
    expect_status(up, 201, "upload MP4 video")
    vid = up.json()
    check(vid.get("mimeType") == "video/mp4", f"video mimeType mismatch: {vid}")
    check(bool(vid.get("videoId")), "missing videoId")

    post_resp = ctx.create_post_safe(
        "reactor",
        ctx.text("video post"),
        video_id=vid["videoId"],
        prefix="/api/social/v1",
        via_proxy=True,
    )
    expect_status(post_resp, 201, "create video post")
    post = post_resp.json()
    ctx.shared["video_post"] = post
    ctx.shared["video_id"] = vid["videoId"]
    ctx.shared["video_bytes"] = mp4
    check(post.get("image") is None, "video post must have image=null")
    check((post.get("video") or {}).get("mimeType") == "video/mp4", "post.video.mimeType mismatch")

    got = ctx.proxy.get(f"/api/social/v1/videos/{vid['videoId']}", token=ctx.token("author"), headers={"Accept": "*/*"})
    expect_status(got, 200, "fetch bound MP4")
    check(got.body == mp4, "MP4 bytes mismatch")
    check("no-store" in (got.header("cache-control") or "").lower(), "video missing no-store")


def _t1_f4_05(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_range_video, "HTTP Range 206 video streaming not deployed yet")
    video_id = ctx.shared["video_id"]
    mp4 = ctx.shared["video_bytes"]
    for prefix in DUAL_FEED_PREFIXES:
        full = ctx.proxy.get_range(f"{prefix}/videos/{video_id}", token=ctx.token("author"))
        expect_status(full, 200, f"GET {prefix}/videos/{{id}} full")
        check((full.header("accept-ranges") or "").lower() == "bytes", f"missing Accept-Ranges: bytes on {prefix}")

        part = ctx.proxy.get_range(f"{prefix}/videos/{video_id}", token=ctx.token("author"), range_header="bytes=0-511")
        expect_status(part, 206, f"Range 0-511 on {prefix}/videos/{{id}}")
        check(part.body == mp4[:512], "206 byte slice mismatch")
        check(part.header("content-range") == f"bytes 0-511/{len(mp4)}", f"Content-Range mismatch: {part.header('content-range')}")


# --------------------------------------------------------------------------- F5
def _t1_f5_01(ctx: SuiteContext) -> None:
    post_id = ctx.shared["post_author"]["id"]
    r1 = ctx.proxy.put(reaction_path("/api/social/v1", post_id, "\u2764\ufe0f"), token=ctx.token("reactor"))
    expect_status(r1, 200, "PUT heart reaction")
    entry = reaction_entry(r1.json().get("reactions"), "\u2764\ufe0f")
    check(entry is not None and entry["count"] == 1 and entry["mine"] is True, f"bad reaction entry: {entry}")

    r2 = ctx.proxy.put(reaction_path("/api/social/v1", post_id, "\u2764\ufe0f"), token=ctx.token("reactor"))
    expect_status(r2, 200, "idempotent replay PUT heart")
    entry2 = reaction_entry(r2.json().get("reactions"), "\u2764\ufe0f")
    check(entry2 is not None and entry2["count"] == 1, "idempotent replay changed count")


def _t1_f5_02(ctx: SuiteContext) -> None:
    post_id = ctx.shared["post_author"]["id"]
    r = ctx.spring.put(reaction_path("/api/v1/feed", post_id, "\u2764\ufe0f"), token=ctx.token("reader"))
    expect_status(r, 200, "second user adds heart")
    entry = reaction_entry(r.json().get("reactions"), "\u2764\ufe0f")
    check(entry is not None and entry["count"] == 2 and entry["mine"] is True, f"expected count=2: {entry}")

    viewer = ctx.spring.get(f"/api/v1/feed/posts/{post_id}", token=ctx.token("author")).json()
    entry_a = reaction_entry(viewer.get("reactions"), "\u2764\ufe0f")
    check(entry_a is not None and entry_a["count"] == 2 and entry_a["mine"] is False, "author mine should be false")


def _t1_f5_03(ctx: SuiteContext) -> None:
    post_id = ctx.shared["post_author"]["id"]
    d1 = ctx.proxy.delete(reaction_path("/api/social/v1", post_id, "\u2764\ufe0f"), token=ctx.token("reactor"))
    expect_status(d1, 200, "DELETE heart reaction")
    entry = reaction_entry(d1.json().get("reactions"), "\u2764\ufe0f")
    check(entry is not None and entry["count"] == 1 and entry["mine"] is False, f"expected count=1 after delete: {entry}")
    d2 = ctx.proxy.delete(reaction_path("/api/social/v1", post_id, "\u2764\ufe0f"), token=ctx.token("reactor"))
    expect_status(d2, 200, "idempotent DELETE reaction")
    check(reaction_entry(d2.json().get("reactions"), "\u2764\ufe0f")["count"] == 1, "idempotent delete changed count")


def _t1_f5_04(ctx: SuiteContext) -> None:
    cap_post = ctx.shared["post_blocker_cap"]["id"]
    for idx, emoji in enumerate(DISTINCT_EMOJIS):
        prefix = DUAL_FEED_PREFIXES[idx % 2]
        resp = ctx.proxy.put(reaction_path(prefix, cap_post, emoji), token=ctx.token("reactor"))
        expect_status(resp, 200, f"add distinct emoji #{idx+1} via {prefix}")
    post = ctx.proxy.get(f"/api/social/v1/posts/{cap_post}", token=ctx.token("reactor")).json()
    check(len(post.get("reactions") or []) == 8, f"expected 8 distinct reactions, got {len(post.get('reactions') or [])}")


def _t1_f5_05(ctx: SuiteContext) -> None:
    post_id = ctx.shared["post_author"]["id"]
    feed = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reader"), params={"limit": 50}).json()
    found = [p for p in (feed.get("items") or []) if p["id"] == post_id]
    check(bool(found), "post missing from feed")
    entry = reaction_entry(found[0].get("reactions"), "\u2764\ufe0f")
    check(entry is not None and entry["count"] == 1 and entry["mine"] is True, "feed list reaction aggregate mismatch")


# --------------------------------------------------------------------------- F6
def _t1_f6_01(ctx: SuiteContext) -> None:
    post_id = ctx.shared["post_author"]["id"]
    body = ctx.text("first comment")
    cid = str(uuid.uuid4())
    resp = ctx.proxy.post(
        f"/api/social/v1/posts/{post_id}/comments",
        token=ctx.token("reader"),
        body={"body": body, "clientCommentId": cid},
    )
    expect_status(resp, 201, "create comment")
    c = resp.json()
    ctx.shared["comment_reader"] = c
    ctx.shared["comment_reader_cid"] = cid
    check(c.get("body") == body and c.get("postId") == post_id and c.get("mine") is True, f"comment mismatch: {c}")
    _assert_author_public(c.get("author"))
    post = ctx.spring.get(f"/api/v1/feed/posts/{post_id}", token=ctx.token("author")).json()
    check(post.get("commentCount") == 1, f"expected commentCount=1, got {post.get('commentCount')}")


def _t1_f6_02(ctx: SuiteContext) -> None:
    post_id = ctx.shared["post_author"]["id"]
    c = ctx.shared["comment_reader"]
    replay = ctx.spring.post(
        f"/api/v1/feed/posts/{post_id}/comments",
        token=ctx.token("reader"),
        body={"body": c["body"], "clientCommentId": ctx.shared["comment_reader_cid"]},
    )
    expect_status(replay, 200, "clientCommentId replay")
    check(replay.json()["id"] == c["id"], "replay created a second comment")
    post = ctx.spring.get(f"/api/v1/feed/posts/{post_id}", token=ctx.token("author")).json()
    check(post.get("commentCount") == 1, "comment replay modified commentCount")


def _t1_f6_03(ctx: SuiteContext) -> None:
    post_id = ctx.shared["post_author"]["id"]
    c2 = ctx.spring.post(
        f"/api/v1/feed/posts/{post_id}/comments",
        token=ctx.token("author"),
        body={"body": ctx.text("second comment"), "clientCommentId": str(uuid.uuid4())},
    )
    expect_status(c2, 201, "second comment")
    c3 = ctx.spring.post(
        f"/api/v1/feed/posts/{post_id}/comments",
        token=ctx.token("reactor"),
        body={"body": ctx.text("third comment"), "clientCommentId": str(uuid.uuid4())},
    )
    expect_status(c3, 201, "third comment")
    ctx.shared["comment_author"] = c2.json()
    ctx.shared["comment_reactor"] = c3.json()

    p1 = ctx.proxy.get(f"/api/social/v1/posts/{post_id}/comments", token=ctx.token("author"), params={"limit": 2})
    expect_status(p1, 200, "comments page 1")
    d1 = p1.json()
    check(len(d1["items"]) == 2 and bool(d1.get("nextCursor")), "expected 2 comments + cursor")
    check(d1["items"][0]["id"] == ctx.shared["comment_reader"]["id"], "expected oldest-first ordering")
    p2 = ctx.proxy.get(
        f"/api/social/v1/posts/{post_id}/comments",
        token=ctx.token("author"),
        params={"limit": 2, "cursor": d1["nextCursor"]},
    )
    expect_status(p2, 200, "comments page 2")
    check(any(item["id"] == c3.json()["id"] for item in p2.json()["items"]), "third comment missing on page 2")


def _t1_f6_04(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_edit, "PATCH /comments/{id} not deployed yet")
    c = ctx.shared["comment_reactor"]
    new_body = ctx.text("edited comment body")
    patch = ctx.proxy.patch(
        f"/api/social/v1/comments/{c['id']}",
        token=ctx.token("reactor"),
        body={"body": new_body},
    )
    expect_status(patch, 200, "PATCH /api/social/v1/comments/{id}")
    updated = patch.json()
    check(updated.get("body") == new_body, "patched comment body mismatch")
    check(bool(updated.get("editedAt")), "patched comment missing editedAt")


def _t1_f6_05(ctx: SuiteContext) -> None:
    post_id = ctx.shared["post_author"]["id"]
    c = ctx.shared["comment_reactor"]
    before = ctx.spring.get(f"/api/v1/feed/posts/{post_id}", token=ctx.token("author")).json()["commentCount"]
    d = ctx.proxy.delete(f"/api/social/v1/comments/{c['id']}", token=ctx.token("reactor"))
    expect_status(d, 204, "DELETE own comment")
    after = ctx.spring.get(f"/api/v1/feed/posts/{post_id}", token=ctx.token("author")).json()["commentCount"]
    check(after == before - 1, f"commentCount did not decrement: {before} -> {after}")


# --------------------------------------------------------------------------- F7
def _t1_f7_01(ctx: SuiteContext) -> None:
    post = ctx.shared["post_author"]
    details = ctx.text("spam details")
    r = ctx.proxy.post(
        "/api/social/v1/reports",
        token=ctx.token("blocker"),
        body={"targetType": "POST", "targetId": post["id"], "reason": "HARASSMENT", "details": details},
    )
    expect_status(r, 201, "report post via /api/social/v1/reports")
    rep = r.json()
    ctx.shared["report_post"] = rep
    check(rep["targetType"] == "POST" and rep["targetId"] == post["id"], "report target mismatch")
    check(rep["status"] == "OPEN" and rep["messageSnapshot"] == post["body"], "report snapshot/status mismatch")


def _t1_f7_02(ctx: SuiteContext) -> None:
    comment = ctx.shared["comment_author"]
    r = ctx.spring.post(
        "/api/v1/reports",
        token=ctx.token("blocker"),
        body={"targetType": "COMMENT", "targetId": comment["id"], "reason": "SPAM", "details": "spam comment"},
    )
    expect_status(r, 201, "report comment via /api/v1/reports")
    ctx.shared["report_comment"] = r.json()
    check(r.json()["messageSnapshot"] == comment["body"], "comment snapshot mismatch")


def _t1_f7_03(ctx: SuiteContext) -> None:
    post = ctx.shared["post_author"]
    dup = ctx.proxy.post(
        "/api/social/v1/reports",
        token=ctx.token("blocker"),
        body={"targetType": "POST", "targetId": post["id"], "reason": "SPAM"},
    )
    expect_status(dup, 200, "duplicate open report")
    check(dup.json()["id"] == ctx.shared["report_post"]["id"], "duplicate report created a new row")


def _t1_f7_04(ctx: SuiteContext) -> None:
    mine = ctx.spring.get("/api/v1/reports/mine", token=ctx.token("blocker"), params={"limit": 20})
    expect_status(mine, 200, "GET /api/v1/reports/mine")
    ids = {item["id"] for item in (mine.json().get("items") or [])}
    check(ctx.shared["report_post"]["id"] in ids and ctx.shared["report_comment"]["id"] in ids,
          "filed reports missing from /reports/mine")


def _t1_f7_05(ctx: SuiteContext) -> None:
    rep_id = ctx.shared["report_post"]["id"]
    res = ctx.spring.post(
        f"/api/v1/admin/feed/reports/{rep_id}/resolve",
        token=ctx.token("moderator"),
        body={"status": "DISMISSED"},
    )
    expect_status(res, 204, "resolve report DISMISSED")
    refile = ctx.proxy.post(
        "/api/social/v1/reports",
        token=ctx.token("blocker"),
        body={"targetType": "POST", "targetId": ctx.shared["post_author"]["id"], "reason": "OTHER"},
    )
    expect_status(refile, 201, "refile after resolve")
    check(refile.json()["id"] != rep_id, "refile reused resolved report id")
    ctx.spring.post(
        f"/api/v1/admin/feed/reports/{refile.json()['id']}/resolve",
        token=ctx.token("moderator"),
        body={"status": "DISMISSED"},
    )


# --------------------------------------------------------------------------- F8
def _t1_f8_01(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_authors, "GET /authors/{id} not deployed yet")
    author_id = ctx.uid("author")
    for prefix in DUAL_FEED_PREFIXES:
        resp = ctx.proxy.get(f"{prefix}/authors/{author_id}", token=ctx.token("reader"))
        expect_status(resp, 200, f"GET {prefix}/authors/{{id}}")
        prof = resp.json()
        check(prof.get("userId") == author_id, "author profile userId mismatch")
        check(bool(prof.get("displayName")), "author profile displayName missing")
        check(isinstance(prof.get("postCount"), int) and prof["postCount"] >= 2, f"unexpected postCount: {prof}")


def _t1_f8_02(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_search, "GET /posts?authorId= not deployed yet")
    author_id = ctx.uid("author")
    for prefix in DUAL_FEED_PREFIXES:
        resp = ctx.proxy.get(f"{prefix}/posts", token=ctx.token("reader"), params={"authorId": author_id, "limit": 20})
        expect_status(resp, 200, f"GET {prefix}/posts?authorId=")
        items = resp.json().get("items") or []
        check(len(items) >= 2, "expected at least 2 author posts")
        check(all(item["author"]["id"] == author_id for item in items), "authorId filter leaked other authors")


def _t1_f8_03(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_search, "GET /posts?authorId= pagination not deployed yet")
    author_id = ctx.uid("author")
    p1 = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reader"), params={"authorId": author_id, "limit": 1})
    expect_status(p1, 200, "author filter page 1")
    d1 = p1.json()
    check(len(d1["items"]) == 1 and bool(d1.get("nextCursor")), "expected 1 item + nextCursor for author filter")
    p2 = ctx.proxy.get(
        "/api/social/v1/posts",
        token=ctx.token("reader"),
        params={"authorId": author_id, "limit": 1, "cursor": d1["nextCursor"]},
    )
    expect_status(p2, 200, "author filter page 2")
    check(p2.json()["items"][0]["id"] != d1["items"][0]["id"], "author filter cursor overlapped")


def _t1_f8_04(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_authors, "GET /authors/{id} postCount sync not deployed yet")
    uid = ctx.uid("reactor")
    before = ctx.proxy.get(f"/api/social/v1/authors/{uid}", token=ctx.token("reader")).json()["postCount"]
    created = ctx.create_post_safe("reactor", ctx.text("postCount delta"))
    expect_status(created, 201, "create post for postCount check")
    mid = ctx.proxy.get(f"/api/social/v1/authors/{uid}", token=ctx.token("reader")).json()["postCount"]
    check(mid == before + 1, f"postCount did not increment: {before} -> {mid}")
    ctx.proxy.delete(f"/api/social/v1/posts/{created.json()['id']}", token=ctx.token("reactor"))
    after = ctx.proxy.get(f"/api/social/v1/authors/{uid}", token=ctx.token("reader")).json()["postCount"]
    check(after == before, f"postCount did not decrement on delete: {mid} -> {after}")


def _t1_f8_05(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_authors, "GET /authors/{id} field check not deployed yet")
    prof = ctx.proxy.get(f"/api/social/v1/authors/{ctx.uid('author')}", token=ctx.token("reader")).json()
    check(set(prof.keys()) <= {"userId", "displayName", "avatarUrl", "postCount"}, f"extra keys leaked: {prof}")
    check("phone" not in prof and "email" not in prof, "PII leaked in author profile")


# --------------------------------------------------------------------------- F9
def _ensure_search_post(ctx: SuiteContext) -> dict:
    if "search_post" not in ctx.shared:
        resp = ctx.create_post_safe("reader", f"C\u00e0 ph\u00ea s\u1eefa \u0111\u00e1 S\u00e0i G\u00f2n {ctx.nonce}")
        expect_status(resp, 201, "create Vietnamese search post")
        ctx.shared["search_post"] = resp.json()
    return ctx.shared["search_post"]


def _t1_f9_01(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_search, "GET /posts?q= not deployed yet")
    sp = _ensure_search_post(ctx)
    for prefix in DUAL_FEED_PREFIXES:
        r = ctx.proxy.get(f"{prefix}/posts", token=ctx.token("author"), params={"q": ctx.nonce})
        expect_status(r, 200, f"GET {prefix}/posts?q=")
        ids = {x["id"] for x in (r.json().get("items") or [])}
        check(sp["id"] in ids, f"search post missing on {prefix}?q=")


def _t1_f9_02(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_search, "case-insensitive ?q= not deployed yet")
    sp = _ensure_search_post(ctx)
    r = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("author"), params={"q": f"S\u00c0I G\u00d2N {ctx.nonce.upper()}"})
    expect_status(r, 200, "case-insensitive search")
    ids = {x["id"] for x in (r.json().get("items") or [])}
    check(sp["id"] in ids, "case-insensitive search missed post")


def _t1_f9_03(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_search, "accent-insensitive ?q= not deployed yet")
    sp = _ensure_search_post(ctx)
    r = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("author"), params={"q": "ca phe sua da"})
    expect_status(r, 200, "accent-insensitive search")
    ids = {x["id"] for x in (r.json().get("items") or [])}
    check(sp["id"] in ids, "accent-insensitive search 'ca phe sua da' missed 'Cà phê sữa đá'")


def _t1_f9_04(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_search, "combined authorId + q not deployed yet")
    sp = _ensure_search_post(ctx)
    r_match = ctx.proxy.get(
        "/api/social/v1/posts",
        token=ctx.token("author"),
        params={"authorId": ctx.uid("reader"), "q": "ca phe"},
    )
    expect_status(r_match, 200, "authorId + q matching")
    check(sp["id"] in {x["id"] for x in r_match.json()["items"]}, "combined authorId+q missed post")

    r_other = ctx.proxy.get(
        "/api/social/v1/posts",
        token=ctx.token("author"),
        params={"authorId": ctx.uid("author"), "q": "ca phe sua da"},
    )
    expect_status(r_other, 200, "authorId + q non-matching author")
    check(sp["id"] not in {x["id"] for x in r_other.json()["items"]}, "combined filter returned wrong author")


def _t1_f9_05(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_search, "non-matching ?q= not deployed yet")
    r = ctx.proxy.get(
        "/api/social/v1/posts",
        token=ctx.token("author"),
        params={"q": f"no_such_token_{uuid.uuid4().hex}"},
    )
    expect_status(r, 200, "non-matching search")
    check(r.json() == {"items": [], "nextCursor": None}, f"expected empty page, got {r.json()}")


# --------------------------------------------------------------------------- F10
def _t1_f10_01(ctx: SuiteContext) -> None:
    _ensure_web_and_video(ctx, True, True)
    post_b = ctx.shared["post_blocker_cap"]
    ctx.ensure_block("reader", "blocker", True)
    expect_error(
        ctx.proxy.get(f"/api/social/v1/posts/{post_b['id']}", token=ctx.token("reader")),
        404,
        "POST_NOT_FOUND",
        "reader views blocked author's post",
    )
    feed = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reader"), params={"limit": 50}).json()
    check(all(p["id"] != post_b["id"] for p in feed["items"]), "blocked post appeared in feed")


def _t1_f10_02(ctx: SuiteContext) -> None:
    post_r = _ensure_search_post(ctx)
    expect_error(
        ctx.spring.get(f"/api/v1/feed/posts/{post_r['id']}", token=ctx.token("blocker")),
        404,
        "POST_NOT_FOUND",
        "blocked author views blocker's post",
    )


def _t1_f10_03(ctx: SuiteContext) -> None:
    post_b = ctx.shared["post_blocker_cap"]
    expect_status(
        ctx.proxy.get(f"/api/social/v1/posts/{post_b['id']}", token=ctx.token("author")),
        200,
        "third party still sees blocked user's post",
    )


def _t1_f10_04(ctx: SuiteContext) -> None:
    post_b = ctx.shared["post_blocker_cap"]
    expect_error(
        ctx.proxy.get(f"/api/social/v1/posts/{post_b['id']}/comments", token=ctx.token("reader")),
        404,
        "POST_NOT_FOUND",
        "list comments under blocked post",
    )
    expect_error(
        ctx.proxy.put(reaction_path("/api/social/v1", post_b["id"], "\U0001F44D"), token=ctx.token("reader")),
        404,
        "POST_NOT_FOUND",
        "react to blocked post",
    )
    expect_error(
        ctx.proxy.post(
            "/api/social/v1/reports",
            token=ctx.token("reader"),
            body={"targetType": "POST", "targetId": post_b["id"], "reason": "SPAM"},
        ),
        404,
        "POST_NOT_FOUND",
        "report blocked post",
    )


def _t1_f10_05(ctx: SuiteContext) -> None:
    try:
        ctx.require_milestone("M1", ctx.caps.m1_authors and ctx.caps.m1_search, "author filter/profile under block not deployed")
        empty_filter = ctx.proxy.get(
            "/api/social/v1/posts",
            token=ctx.token("reader"),
            params={"authorId": ctx.uid("blocker")},
        )
        expect_status(empty_filter, 200, "authorId filter on blocked user")
        check(empty_filter.json() == {"items": [], "nextCursor": None}, "blocked authorId leaked items")
        expect_error(
            ctx.proxy.get(f"/api/social/v1/authors/{ctx.uid('blocker')}", token=ctx.token("reader")),
            404,
            "USER_NOT_FOUND",
            "author profile on blocked user",
        )
    finally:
        ctx.ensure_block("reader", "blocker", False)
    expect_status(
        ctx.proxy.get(f"/api/social/v1/posts/{ctx.shared['post_blocker_cap']['id']}", token=ctx.token("reader")),
        200,
        "post visible again after unblock",
    )


# --------------------------------------------------------------------------- F11
def _t1_f11_01(ctx: SuiteContext) -> None:
    p = ctx.create_post_safe("reactor", ctx.text("f11 delete visibility"))
    expect_status(p, 201, "create post for delete check")
    pid = p.json()["id"]
    expect_status(ctx.spring.delete(f"/api/v1/feed/posts/{pid}", token=ctx.token("reactor")), 204, "delete post")
    expect_error(ctx.spring.get(f"/api/v1/feed/posts/{pid}", token=ctx.token("author")), 404, "POST_NOT_FOUND", "get deleted post")
    expect_error(ctx.spring.get(f"/api/v1/feed/posts/{pid}/comments", token=ctx.token("author")), 404, "POST_NOT_FOUND", "comments of deleted post")


def _t1_f11_02(ctx: SuiteContext) -> None:
    img_id = ctx.shared["image_post"]["image"]["url"].rsplit("/", 1)[-1]
    vid_id = ctx.shared["video_id"]
    expect_error(
        ctx.proxy.get(f"/api/social/v1/images/{vid_id}", token=ctx.token("author")),
        404,
        "FILE_NOT_FOUND",
        "cross-kind GET /images/{videoId}",
    )
    expect_error(
        ctx.proxy.get(f"/api/social/v1/videos/{img_id}", token=ctx.token("author")),
        404,
        "FILE_NOT_FOUND",
        "cross-kind GET /videos/{imageId}",
    )


def _t1_f11_03(ctx: SuiteContext) -> None:
    # Unauthenticated /api/v1/files/<key> and bogus key denied
    anon = ctx.spring.get("/api/v1/files/feed/nonexistent-key")
    expect_status(anon, 401, "unauthenticated /api/v1/files")
    other = ctx.spring.get("/api/v1/files/feed/nonexistent-key", token=ctx.token("reader"))
    check(other.status in (403, 404), f"expected 403/404 on unknown /files key, got {other}")


def _t1_f11_04(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_edit, "M1 comment visibility leak fix not deployed yet")
    p = ctx.create_post_safe("blocker", ctx.text("f11 parent post"))
    expect_status(p, 201, "create parent post")
    pid = p.json()["id"]
    c = ctx.spring.post(
        f"/api/v1/feed/posts/{pid}/comments",
        token=ctx.token("reader"),
        body={"body": ctx.text("orphan comment"), "clientCommentId": str(uuid.uuid4())},
    )
    expect_status(c, 201, "comment on post")
    cid = c.json()["id"]
    expect_status(ctx.spring.delete(f"/api/v1/feed/posts/{pid}", token=ctx.token("blocker")), 204, "delete parent post")
    denied = ctx.spring.delete(f"/api/v1/feed/comments/{cid}", token=ctx.token("author"))
    expect_error(denied, 404, "COMMENT_NOT_FOUND", "delete comment under deleted parent must not leak 403")


def _t1_f11_05(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_edit, "M1 owner hidden/deleted media denial not deployed yet")
    png = make_png_bytes(12, 12)
    up = ctx.spring.upload_multipart(
        "/api/v1/feed/images",
        token=ctx.token("reactor"),
        files=[("file", "owned.png", "image/png", png)],
    )
    expect_status(up, 201, "upload image for owner denial test")
    img_id = up.json()["imageId"]
    post = ctx.create_post_safe("reactor", ctx.text("owner media post"), image_id=img_id)
    expect_status(post, 201, "bind image")
    pid = post.json()["id"]
    expect_status(
        ctx.spring.post(f"/api/v1/admin/feed/posts/{pid}/hide", token=ctx.token("moderator"), body={"reason": "hide media"}),
        204,
        "hide post",
    )
    expect_error(
        ctx.spring.get(f"/api/v1/feed/images/{img_id}", token=ctx.token("reactor")),
        404,
        "FILE_NOT_FOUND",
        "owner must not read image of hidden post",
    )


# --------------------------------------------------------------------------- F12
def _t1_f12_01(ctx: SuiteContext) -> None:
    s = ctx.get_admin_settings()
    for key in ("revision", "webEnabled", "videoUploadsEnabled", "draft", "published"):
        check(key in s, f"admin social settings missing {key}")


def _t1_f12_02(ctx: SuiteContext) -> None:
    before = ctx.get_admin_settings()
    updated = ctx.spring.patch(
        "/api/v1/admin/social/settings/flags",
        token=ctx.token("admin"),
        body={
            "expectedRevision": before["revision"],
            "webEnabled": True,
            "videoUploadsEnabled": True,
        },
    )
    expect_status(updated, 200, "PATCH flags")
    check(updated.json()["revision"] == before["revision"] + 1, "revision did not increment")
    audit = ctx.spring.get(
        "/api/v1/admin/audit",
        token=ctx.token("admin"),
        params={"action": "SOCIAL_FLAGS_UPDATE", "size": 5},
    )
    expect_status(audit, 200, "audit SOCIAL_FLAGS_UPDATE")
    check(len(audit.json().get("items") or []) >= 1, "SOCIAL_FLAGS_UPDATE audit row missing")


def _t1_f12_03(ctx: SuiteContext) -> None:
    cur = ctx.get_admin_settings()
    draft = {
        "titleVi": f"Th\u00f4ng b\u00e1o {ctx.nonce}",
        "bodyVi": "N\u1ed9i dung th\u1eed nghi\u1ec7m",
        "titleEn": f"Notice {ctx.nonce}",
        "bodyEn": "Test announcement copy",
    }
    saved = ctx.spring.put(
        "/api/v1/admin/social/settings/announcement/draft",
        token=ctx.token("admin"),
        body={"expectedRevision": cur["revision"], **draft},
    )
    expect_status(saved, 200, "PUT announcement draft")
    check(saved.json()["draft"] == draft, "saved draft mismatch")
    cfg = ctx.proxy.get("/api/social/v1/config").json()
    if cfg.get("announcement") is not None:
        check(cfg["announcement"]["titleEn"] != draft["titleEn"], "saving draft leaked to live config")


def _t1_f12_04(ctx: SuiteContext) -> None:
    cur = ctx.get_admin_settings()
    pub = ctx.spring.post(
        "/api/v1/admin/social/settings/announcement/publish",
        token=ctx.token("admin"),
        body={"expectedRevision": cur["revision"]},
    )
    expect_status(pub, 200, "POST announcement publish")
    cfg = ctx.proxy.get("/api/social/v1/config").json()
    check((cfg.get("announcement") or {}).get("titleEn") == f"Notice {ctx.nonce}", "published copy missing in /config")

    unpub = ctx.spring.post(
        "/api/v1/admin/social/settings/announcement/unpublish",
        token=ctx.token("admin"),
        body={"expectedRevision": pub.json()["revision"]},
    )
    expect_status(unpub, 200, "POST announcement unpublish")
    check(unpub.json()["published"] is None and unpub.json()["draft"] is not None,
          "unpublish should clear live copy and preserve draft")
    check(ctx.proxy.get("/api/social/v1/config").json().get("announcement") is None, "unpublished copy still in /config")


def _t1_f12_05(ctx: SuiteContext) -> None:
    mod_tok = ctx.token("moderator")
    expect_status(ctx.spring.get("/api/v1/admin/feed/reports", token=mod_tok), 200, "MODERATOR feed queue")
    expect_error(
        ctx.spring.get("/api/v1/admin/social/settings", token=mod_tok),
        403,
        "NOT_ADMIN",
        "MODERATOR denied on /admin/social/settings",
    )


# --------------------------------------------------------------------------- F13
def _ensure_moderation_fixtures(ctx: SuiteContext) -> dict:
    if "mod_fixtures" in ctx.shared:
        return ctx.shared["mod_fixtures"]
    _ensure_web_and_video(ctx, True, True)
    post = ctx.create_post_safe("admin", ctx.text("moderation target post")).json()
    comment = ctx.spring.post(
        f"/api/v1/feed/posts/{post['id']}/comments",
        token=ctx.token("admin"),
        body={"body": ctx.text("moderation target comment"), "clientCommentId": str(uuid.uuid4())},
    ).json()
    mp4 = load_valid_mp4_bytes()
    v_up = ctx.spring.upload_multipart(
        "/api/v1/feed/videos",
        token=ctx.token("admin"),
        files=[("file", "rep.mp4", "video/mp4", mp4)],
    ).json()
    v_post = ctx.create_post_safe("admin", ctx.text("moderation video post"), video_id=v_up["videoId"]).json()

    r_post = ctx.spring.post(
        "/api/v1/reports",
        token=ctx.token("reader"),
        body={"targetType": "POST", "targetId": post["id"], "reason": "SPAM", "details": "queue details"},
    ).json()
    r_comment = ctx.spring.post(
        "/api/v1/reports",
        token=ctx.token("reader"),
        body={"targetType": "COMMENT", "targetId": comment["id"], "reason": "OTHER"},
    ).json()
    r_video = ctx.spring.post(
        "/api/v1/reports",
        token=ctx.token("reader"),
        body={"targetType": "POST", "targetId": v_post["id"], "reason": "HARASSMENT"},
    ).json()
    data = {
        "post": post,
        "comment": comment,
        "video_post": v_post,
        "mp4": mp4,
        "r_post": r_post,
        "r_comment": r_comment,
        "r_video": r_video,
    }
    ctx.shared["mod_fixtures"] = data
    return data


def _t1_f13_01(ctx: SuiteContext) -> None:
    fx = _ensure_moderation_fixtures(ctx)
    q = ctx.spring.get("/api/v1/admin/feed/reports", token=ctx.token("moderator"), params={"status": "OPEN", "size": 100})
    expect_status(q, 200, "MODERATOR lists OPEN reports")
    items = q.json().get("items") or []
    found = [it for it in items if it["id"] == fx["r_post"]["id"]]
    check(bool(found), "reported post missing from OPEN queue")
    row = found[0]
    check(row.get("details") == "queue details", "report details missing in queue")
    check("phone" not in row and "reporterPhone" not in row, "queue leaked phone")


def _t1_f13_02(ctx: SuiteContext) -> None:
    fx = _ensure_moderation_fixtures(ctx)
    pid = fx["post"]["id"]
    mod = ctx.token("moderator")
    expect_status(
        ctx.spring.post(f"/api/v1/admin/feed/posts/{pid}/hide", token=mod, body={"reason": "E2E hide post"}),
        204,
        "hide post",
    )
    expect_error(ctx.spring.get(f"/api/v1/feed/posts/{pid}", token=ctx.token("reader")), 404, "POST_NOT_FOUND", "hidden post read")
    audit = ctx.spring.get("/api/v1/admin/audit", token=mod, params={"action": "FEED_POST_HIDE", "targetId": pid}).json()
    check(len(audit.get("items") or []) == 1, "expected 1 FEED_POST_HIDE audit row")
    expect_status(ctx.spring.post(f"/api/v1/admin/feed/posts/{pid}/unhide", token=mod), 204, "unhide post")
    expect_status(ctx.spring.get(f"/api/v1/feed/posts/{pid}", token=ctx.token("reader")), 200, "unhidden post visible")


def _t1_f13_03(ctx: SuiteContext) -> None:
    fx = _ensure_moderation_fixtures(ctx)
    pid = fx["post"]["id"]
    cid = fx["comment"]["id"]
    mod = ctx.token("moderator")
    before = ctx.spring.get(f"/api/v1/feed/posts/{pid}", token=ctx.token("reader")).json()["commentCount"]
    expect_status(
        ctx.spring.post(f"/api/v1/admin/feed/comments/{cid}/hide", token=mod, body={"reason": "E2E hide comment"}),
        204,
        "hide comment",
    )
    mid = ctx.spring.get(f"/api/v1/feed/posts/{pid}", token=ctx.token("reader")).json()["commentCount"]
    check(mid == before - 1, f"commentCount not decremented: {before} -> {mid}")
    expect_status(ctx.spring.post(f"/api/v1/admin/feed/comments/{cid}/unhide", token=mod), 204, "unhide comment")
    after = ctx.spring.get(f"/api/v1/feed/posts/{pid}", token=ctx.token("reader")).json()["commentCount"]
    check(after == before, f"commentCount not restored: {mid} -> {after}")


def _t1_f13_04(ctx: SuiteContext) -> None:
    fx = _ensure_moderation_fixtures(ctx)
    mod = ctx.token("moderator")
    expect_status(
        ctx.spring.post(f"/api/v1/admin/feed/reports/{fx['r_post']['id']}/resolve", token=mod, body={"status": "REVIEWED"}),
        204,
        "resolve REVIEWED",
    )
    expect_status(
        ctx.spring.post(f"/api/v1/admin/feed/reports/{fx['r_comment']['id']}/resolve", token=mod, body={"status": "DISMISSED"}),
        204,
        "resolve DISMISSED",
    )
    audit = ctx.spring.get(
        "/api/v1/admin/audit",
        token=mod,
        params={"action": "FEED_REPORT_RESOLVE", "targetId": fx["r_comment"]["id"]},
    ).json()
    check(len(audit.get("items") or []) == 1, "missing FEED_REPORT_RESOLVE audit row")


def _t1_f13_05(ctx: SuiteContext) -> None:
    fx = _ensure_moderation_fixtures(ctx)
    mod = ctx.token("moderator")
    rid = fx["r_video"]["id"]
    vid = ctx.spring.post(f"/api/v1/admin/feed/reports/{rid}/video", token=mod, headers={"Accept": "*/*"})
    expect_status(vid, 200, "POST reported video preview")
    check((vid.header("content-type") or "").startswith("video/mp4"), f"bad content-type: {vid.header('content-type')}")
    check(vid.body == fx["mp4"], "reported video preview bytes mismatch")
    audit = ctx.spring.get(
        "/api/v1/admin/audit",
        token=mod,
        params={"action": "FEED_REPORT_VIDEO_VIEW", "targetId": rid},
    ).json()
    check(len(audit.get("items") or []) >= 1, "missing FEED_REPORT_VIDEO_VIEW audit row")
    ctx.spring.post(f"/api/v1/admin/feed/reports/{rid}/resolve", token=mod, body={"status": "DISMISSED"})


# --------------------------------------------------------------------------- F14
def _t1_f14_01(ctx: SuiteContext) -> None:
    from .fixtures import VALID_JOIN_TOKEN

    resp = ctx.proxy.get(f"/join/{VALID_JOIN_TOKEN}")
    expect_status(resp, 200, "GET /join/<token>")
    check("no-store" in (resp.header("cache-control") or "").lower(), "join missing Cache-Control: no-store")
    check("invite-valid" in resp.text and "join.js" in resp.text, "join HTML missing expected markers")


def _t1_f14_02(ctx: SuiteContext) -> None:
    soc = ctx.proxy.get("/social/")
    expect_status(soc, 200, "GET /social/")
    check("media-src blob:" in (soc.header("content-security-policy") or ""), "missing /social CSP")
    adm = ctx.proxy.get("/admin/login")
    expect_status(adm, 200, "GET /admin/login")
    check("style-src 'self' 'unsafe-inline'" in (adm.header("content-security-policy") or ""), "missing /admin CSP")


def _t1_f14_03(ctx: SuiteContext) -> None:
    for path, mime_sub in (
        ("/social/social-app.js", "javascript"),
        ("/social/social.css", "text/css"),
        ("/join/join.js", "javascript"),
        ("/favicon.svg", "image/svg+xml"),
    ):
        r = ctx.proxy.get(path)
        expect_status(r, 200, f"GET static {path}")
        check(mime_sub in (r.header("content-type") or "").lower(), f"bad mime for {path}: {r.header('content-type')}")
        check(r.header("x-content-type-options") == "nosniff", f"missing nosniff on {path}")


def _t1_f14_04(ctx: SuiteContext) -> None:
    _ensure_web_and_video(ctx, True, True)
    r = ctx.proxy.get("/api/social/v1/config")
    expect_status(r, 200, "proxy /api/social/v1/config")
    check("no-store" in (r.header("cache-control") or "").lower(), "missing no-store on proxied /api/social/v1/*")
    unmapped = ctx.proxy.get("/api/social/v1/nonexistent_wildcard_probe")
    check(unmapped.status == 404, f"expected 404 on unmapped /api/social/v1 route, got {unmapped}")


def _t1_f14_05(ctx: SuiteContext) -> None:
    ctx.require_milestone("M2", ctx.caps.m2_join_footer, "/join/index.html footer /social/ link not landed yet")
    html = ctx.proxy.get("/join/index.html").text
    footer_idx = html.find("<footer")
    check(footer_idx != -1, "missing <footer> in join/index.html")
    footer_html = html[footer_idx:]
    check("/social/" in footer_html, "expected /social/ link inside <footer><nav> in join/index.html")


# --------------------------------------------------------------------------- F15
def _t1_f15_01(ctx: SuiteContext) -> None:
    css = ctx.proxy.get("/social/social.css").text.lower()
    for token in ("#fff7fc", "#733f68", "#fcd7f4", "#246b57", "#151316", "#f4b2e2", "#67355d", "#8cd6b7"):
        check(token in css, f"Tonal Plum token {token} missing from social.css")


def _t1_f15_02(ctx: SuiteContext) -> None:
    html = ctx.proxy.get("/social/").text
    js = ctx.proxy.get("/social/social-app.js").text
    for bad in ("fonts.googleapis.com", "fonts.gstatic.com", "cdn.tailwindcss.com", "unpkg.com", "<style>"):
        check(bad not in html and bad not in js, f"forbidden external/inline reference {bad!r} found")


def _t1_f15_03(ctx: SuiteContext) -> None:
    adm_html = ctx.proxy.get("/admin/").text
    check("/admin/assets/" in adm_html, "ephemeral admin bundle missing /admin/assets/ script reference")
    # Ensure no hardcoded prod URL in built JS
    import re
    scripts = re.findall(r'src="(/admin/assets/[^"]+\.js)"', adm_html)
    check(bool(scripts), "could not locate admin JS asset in /admin/index.html")
    js = ctx.proxy.get(scripts[0]).text
    check("api.nexachat.io.vn" not in js, "ephemeral admin bundle must not embed production URL")


def _t1_f15_04(ctx: SuiteContext) -> None:
    ctx.require_milestone("M2", ctx.caps.m2_social_features, "M2 /social/ copy & features not landed yet")
    js = ctx.proxy.get("/social/social-app.js").text
    html = ctx.proxy.get("/social/").text
    check("ảnh đã có" not in js and "ảnh đã có" not in html, "stale VI copy 'ảnh đã có' still present")
    check("existing images" not in js, "stale EN copy 'existing images' still present")


def _t1_f15_05(ctx: SuiteContext) -> None:
    ctx.require_milestone("M2", ctx.caps.m2_design_tokens, "M2 Tonal Plum design unification not landed yet")
    html = ctx.proxy.get("/social/").text
    css = ctx.proxy.get("/social/social.css").text
    check("SOCIAL" in html and "--surface-lowest" in css and "avatar-tone-7" in css,
          "M2 design system tokens (SOCIAL badge, 5-step surface ramp, 8-hue avatars) incomplete")


# --------------------------------------------------------------------------- F16
def _t1_f16_01(ctx: SuiteContext) -> None:
    ctx.set_social_flags(False, False)
    android_list = ctx.spring.get("/api/v1/feed/posts", token=ctx.token("reader"))
    expect_status(android_list, 200, "Android /api/v1/feed/posts while webEnabled=false")
    web_list = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reader"))
    expect_error(web_list, 403, "SOCIAL_FEATURE_DISABLED", "Web /api/social/v1/posts while webEnabled=false")


def _t1_f16_02(ctx: SuiteContext) -> None:
    ctx.set_social_flags(False, False)
    created = ctx.create_post_safe("blocker", ctx.text("android offline-web post"), prefix="/api/v1/feed")
    expect_status(created, 201, "Android create post while webEnabled=false")
    pid = created.json()["id"]
    c = ctx.spring.post(
        f"/api/v1/feed/posts/{pid}/comments",
        token=ctx.token("reader"),
        body={"body": "android comment", "clientCommentId": str(uuid.uuid4())},
    )
    expect_status(c, 201, "Android comment while webEnabled=false")
    r = ctx.spring.put(reaction_path("/api/v1/feed", pid, "\U0001F44D"), token=ctx.token("reader"))
    expect_status(r, 200, "Android react while webEnabled=false")
    d = ctx.spring.delete(f"/api/v1/feed/posts/{pid}", token=ctx.token("blocker"))
    expect_status(d, 204, "Android delete post while webEnabled=false")


def _t1_f16_03(ctx: SuiteContext) -> None:
    ctx.set_social_flags(False, True)
    mp4 = load_valid_mp4_bytes()
    up = ctx.spring.upload_multipart(
        "/api/v1/feed/videos",
        token=ctx.token("reactor"),
        files=[("file", "android.mp4", "video/mp4", mp4)],
    )
    expect_status(up, 201, "Android video upload while webEnabled=false & videoUploadsEnabled=true")
    p = ctx.create_post_safe("reactor", ctx.text("android video"), video_id=up.json()["videoId"], prefix="/api/v1/feed")
    expect_status(p, 201, "Android video post while webEnabled=false")
    ctx.spring.delete(f"/api/v1/feed/posts/{p.json()['id']}", token=ctx.token("reactor"))


def _t1_f16_04(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_all, "M1 endpoints not deployed yet")
    ctx.set_social_flags(False, True)
    p = ctx.create_post_safe("reactor", ctx.text("android m1 patch"), prefix="/api/v1/feed")
    expect_status(p, 201, "Android create post")
    pid = p.json()["id"]
    patched = ctx.spring.patch(f"/api/v1/feed/posts/{pid}", token=ctx.token("reactor"), body={"body": ctx.text("android patched")})
    expect_status(patched, 200, "Android PATCH /posts/{id} while webEnabled=false")
    prof = ctx.spring.get(f"/api/v1/feed/authors/{ctx.uid('reactor')}", token=ctx.token("reader"))
    expect_status(prof, 200, "Android GET /authors/{id} while webEnabled=false")
    srch = ctx.spring.get("/api/v1/feed/posts", token=ctx.token("reader"), params={"q": "android patched"})
    expect_status(srch, 200, "Android GET /posts?q= while webEnabled=false")
    ctx.spring.delete(f"/api/v1/feed/posts/{pid}", token=ctx.token("reactor"))


def _t1_f16_05(ctx: SuiteContext) -> None:
    _ensure_web_and_video(ctx, True, True)
    img_post = ctx.shared["image_post"]
    vid_post = ctx.shared["video_post"]
    check(img_post["image"]["url"].startswith("/api/v1/feed/images/"), f"unexpected image.url: {img_post['image']['url']}")
    check(vid_post["video"]["url"].startswith("/api/v1/feed/videos/"), f"unexpected video.url: {vid_post['video']['url']}")


def get_tier1_checks() -> List[CheckCase]:
    raw = [
        ("T1.F1.01", "F1", "BASELINE", "Anonymous /config schema & no-store headers", _t1_f1_01),
        ("T1.F1.02", "F1", "BASELINE", "webEnabled=false masks videoUploadsEnabled & blocks /api/social/v1", _t1_f1_02),
        ("T1.F1.03", "F1", "BASELINE", "Legacy /api/v1/feed/config 404 & feed independence", _t1_f1_03),
        ("T1.F1.04", "F1", "BASELINE", "Enabling webEnabled opens /api/social/v1 immediately", _t1_f1_04),
        ("T1.F1.05", "F1", "BASELINE", "videoUploadsEnabled=false blocks video upload/bind on both prefixes", _t1_f1_05),
        ("T1.F2.01", "F2", "BASELINE", "Proxy /api/social/v1/session/login returns session tokens", _t1_f2_01),
        ("T1.F2.02", "F2", "BASELINE", "GET /api/v1/users/me validates Bearer token identity", _t1_f2_02),
        ("T1.F2.03", "F2", "BASELINE", "Proxy refresh rotates access & refresh tokens", _t1_f2_03),
        ("T1.F2.04", "F2", "BASELINE", "Idempotent refresh replay vs rotated-token reuse rejection", _t1_f2_04),
        ("T1.F2.05", "F2", "BASELINE", "Proxy logout returns 204 and revokes refresh token", _t1_f2_05),
        ("T1.F3.01", "F3", "BASELINE", "POST /api/v1/feed/posts trims body & returns public DTO", _t1_f3_01),
        ("T1.F3.02", "F3", "BASELINE", "POST /api/social/v1/posts via proxy & dual-prefix read", _t1_f3_02),
        ("T1.F3.03", "F3", "BASELINE", "clientPostId idempotent 200 replay", _t1_f3_03),
        ("T1.F3.04", "F3", "BASELINE", "Keyset cursor pagination without overlap", _t1_f3_04),
        ("T1.F3.05", "F3", "M1", "PATCH /posts/{id} sets editedAt & DELETE /posts/{id} soft-deletes", _t1_f3_05),
        ("T1.F4.01", "F4", "BASELINE", "PNG upload, image-only post bind & nosniff serving", _t1_f4_01),
        ("T1.F4.02", "F4", "BASELINE", "JPEG upload magic-byte sniff & byte round-trip", _t1_f4_02),
        ("T1.F4.03", "F4", "BASELINE", "WebP upload magic-byte sniff & byte round-trip", _t1_f4_03),
        ("T1.F4.04", "F4", "BASELINE", "MP4 upload, post bind & no-store video streaming", _t1_f4_04),
        ("T1.F4.05", "F4", "M1", "HTTP Range 206 Partial Content & Accept-Ranges on both prefixes", _t1_f4_05),
        ("T1.F5.01", "F5", "BASELINE", "PUT emoji reaction idempotent add", _t1_f5_01),
        ("T1.F5.02", "F5", "BASELINE", "Multi-user reaction count & mine flag aggregation", _t1_f5_02),
        ("T1.F5.03", "F5", "BASELINE", "DELETE emoji reaction idempotent removal", _t1_f5_03),
        ("T1.F5.04", "F5", "BASELINE", "8 distinct emojis supported across both prefixes", _t1_f5_04),
        ("T1.F5.05", "F5", "BASELINE", "Reaction summaries persisted in feed list pages", _t1_f5_05),
        ("T1.F6.01", "F6", "BASELINE", "POST comment increments commentCount & sets mine", _t1_f6_01),
        ("T1.F6.02", "F6", "BASELINE", "clientCommentId 200 replay avoids double count", _t1_f6_02),
        ("T1.F6.03", "F6", "BASELINE", "Oldest-first comment keyset pagination", _t1_f6_03),
        ("T1.F6.04", "F6", "M1", "PATCH /comments/{id} updates body & sets editedAt", _t1_f6_04),
        ("T1.F6.05", "F6", "BASELINE", "DELETE own comment decrements commentCount", _t1_f6_05),
        ("T1.F7.01", "F7", "BASELINE", "Report POST with details via /api/social/v1/reports", _t1_f7_01),
        ("T1.F7.02", "F7", "BASELINE", "Report COMMENT with details via /api/v1/reports", _t1_f7_02),
        ("T1.F7.03", "F7", "BASELINE", "Duplicate open report returns 200 same row", _t1_f7_03),
        ("T1.F7.04", "F7", "BASELINE", "GET /api/v1/reports/mine lists filed reports", _t1_f7_04),
        ("T1.F7.05", "F7", "BASELINE", "Re-report succeeds with new id after resolution", _t1_f7_05),
        ("T1.F8.01", "F8", "M1", "GET /authors/{id} returns public profile & postCount", _t1_f8_01),
        ("T1.F8.02", "F8", "M1", "GET /posts?authorId= filters feed to author on both prefixes", _t1_f8_02),
        ("T1.F8.03", "F8", "M1", "GET /posts?authorId= paginates via keyset cursor", _t1_f8_03),
        ("T1.F8.04", "F8", "M1", "Author postCount tracks create & delete", _t1_f8_04),
        ("T1.F8.05", "F8", "M1", "Author profile DTO omits PII fields", _t1_f8_05),
        ("T1.F9.01", "F9", "M1", "GET /posts?q= matches keyword on both prefixes", _t1_f9_01),
        ("T1.F9.02", "F9", "M1", "GET /posts?q= is case-insensitive", _t1_f9_02),
        ("T1.F9.03", "F9", "M1", "GET /posts?q= is Vietnamese accent-insensitive", _t1_f9_03),
        ("T1.F9.04", "F9", "M1", "GET /posts?authorId=&q= intersects both filters", _t1_f9_04),
        ("T1.F9.05", "F9", "M1", "Non-matching ?q= returns empty page", _t1_f9_05),
        ("T1.F10.01", "F10", "BASELINE", "Block hides blocked author's posts from feed & detail", _t1_f10_01),
        ("T1.F10.02", "F10", "BASELINE", "Bidirectional block hides blocker's posts from blocked user", _t1_f10_02),
        ("T1.F10.03", "F10", "BASELINE", "Third-party viewer unaffected by block", _t1_f10_03),
        ("T1.F10.04", "F10", "BASELINE", "Block denies comments, reactions & reports between peers", _t1_f10_04),
        ("T1.F10.05", "F10", "M1", "Block hides ?authorId= & /authors/{id}; unblock restores", _t1_f10_05),
        ("T1.F11.01", "F11", "BASELINE", "Deleted post denies post & comment reads with 404", _t1_f11_01),
        ("T1.F11.02", "F11", "BASELINE", "Cross-kind /images/{videoId} & /videos/{imageId} return 404", _t1_f11_02),
        ("T1.F11.03", "F11", "BASELINE", "Raw /api/v1/files/{key} denies unauthorized reads", _t1_f11_03),
        ("T1.F11.04", "F11", "M1", "Comment delete under deleted parent returns 404 (no 403 leak)", _t1_f11_04),
        ("T1.F11.05", "F11", "M1", "Owner media read denied (404) after post hidden", _t1_f11_05),
        ("T1.F12.01", "F12", "BASELINE", "ADMIN reads /api/v1/admin/social/settings", _t1_f12_01),
        ("T1.F12.02", "F12", "BASELINE", "ADMIN PATCH /flags increments revision & writes audit", _t1_f12_02),
        ("T1.F12.03", "F12", "BASELINE", "ADMIN saves bilingual draft without altering live /config", _t1_f12_03),
        ("T1.F12.04", "F12", "BASELINE", "ADMIN publish/unpublish announcement updates /config & keeps draft", _t1_f12_04),
        ("T1.F12.05", "F12", "BASELINE", "MODERATOR allowed on /admin/feed, denied 403 NOT_ADMIN on /admin/social/settings", _t1_f12_05),
        ("T1.F13.01", "F13", "BASELINE", "MODERATOR lists OPEN feed reports without leaking phones", _t1_f13_01),
        ("T1.F13.02", "F13", "BASELINE", "MODERATOR hide & unhide post + FEED_POST_HIDE audit", _t1_f13_02),
        ("T1.F13.03", "F13", "BASELINE", "MODERATOR hide & unhide comment updates commentCount", _t1_f13_03),
        ("T1.F13.04", "F13", "BASELINE", "MODERATOR resolve REVIEWED/DISMISSED + audit trail", _t1_f13_04),
        ("T1.F13.05", "F13", "BASELINE", "MODERATOR reported video preview + FEED_REPORT_VIDEO_VIEW audit", _t1_f13_05),
        ("T1.F14.01", "F14", "BASELINE", "Proxy /join/<token> returns 200 with Cache-Control: no-store", _t1_f14_01),
        ("T1.F14.02", "F14", "BASELINE", "Proxy /social/ and /admin/ emit exact vercel.json CSP headers", _t1_f14_02),
        ("T1.F14.03", "F14", "BASELINE", "Static assets return 200 with nosniff & valid MIME", _t1_f14_03),
        ("T1.F14.04", "F14", "BASELINE", "Proxy /api/social/v1/ emits no-store & rejects unmapped paths", _t1_f14_04),
        ("T1.F14.05", "F14", "M2", "/join/ places /social/ links inside <footer><nav>", _t1_f14_05),
        ("T1.F15.01", "F15", "BASELINE", "social.css declares Tonal Plum light/dark tokens", _t1_f15_01),
        ("T1.F15.02", "F15", "BASELINE", "/social/ contains zero external CDNs/fonts or inline style", _t1_f15_02),
        ("T1.F15.03", "F15", "BASELINE", "Ephemeral /admin/ bundle omits production API URL", _t1_f15_03),
        ("T1.F15.04", "F15", "M2", "/social/ removes stale 'ảnh đã có' / 'existing images' copy", _t1_f15_04),
        ("T1.F15.05", "F15", "M2", "/social/ implements Tonal Plum brand plate, surface ramp & 8-hue avatars", _t1_f15_05),
        ("T1.F16.01", "F16", "BASELINE", "Android /api/v1/feed/posts stays 200 while webEnabled=false", _t1_f16_01),
        ("T1.F16.02", "F16", "BASELINE", "Android create/comment/react/delete work while webEnabled=false", _t1_f16_02),
        ("T1.F16.03", "F16", "BASELINE", "Android video upload/post works while webEnabled=false", _t1_f16_03),
        ("T1.F16.04", "F16", "M1", "Android M1 edit/profile/search work while webEnabled=false", _t1_f16_04),
        ("T1.F16.05", "F16", "BASELINE", "Media URLs follow canonical legacy /api/v1/feed/{images|videos}/{id}", _t1_f16_05),
    ]
    return [CheckCase(cid, feat, 1, gate, title, fn) for cid, feat, gate, title, fn in raw]
