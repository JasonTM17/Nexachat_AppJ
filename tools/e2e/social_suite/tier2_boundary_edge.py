"""Tier 2: Boundary, validation, security, OCC, role gate & rate-limit checks (80 checks)."""

from __future__ import annotations

import time
import uuid
from typing import List

from .client import (
    DUAL_FEED_PREFIXES,
    CheckCase,
    SuiteContext,
    check,
    expect_error,
    expect_status,
    reaction_path,
    retry_after_seconds,
)
from .fixtures import (
    DISTINCT_EMOJIS,
    FIXTURE_PASSWORD,
    MAX_ANNOUNCEMENT_BODY,
    MAX_COMMENT_BODY,
    MAX_POST_BODY,
    MAX_REPORT_DETAILS,
    MAX_TITLE_LENGTH,
    NINTH_EMOJI,
    NOT_AN_IMAGE_BYTES,
    POSTS_PER_MINUTE,
    load_audio_only_m4a_bytes,
    load_valid_mp4_bytes,
    make_oversized_mp4_bytes,
    make_png_bytes,
)


def _ensure_t2_post(ctx: SuiteContext) -> dict:
    if "t2_post" not in ctx.shared:
        ctx.set_social_flags(True, True)
        p = ctx.create_post_safe("author", ctx.text("t2 shared post"))
        expect_status(p, 201, "create t2 shared post")
        ctx.shared["t2_post"] = p.json()
    return ctx.shared["t2_post"]


def _ensure_t2_video(ctx: SuiteContext) -> dict:
    if "t2_video" not in ctx.shared:
        ctx.set_social_flags(True, True)
        mp4 = load_valid_mp4_bytes()
        up = ctx.spring.upload_multipart(
            "/api/v1/feed/videos",
            token=ctx.token("reader"),
            files=[("file", "t2.mp4", "video/mp4", mp4)],
        )
        expect_status(up, 201, "upload t2 video")
        vid = up.json()
        p = ctx.create_post_safe("reader", ctx.text("t2 video post"), video_id=vid["videoId"])
        expect_status(p, 201, "create t2 video post")
        ctx.shared["t2_video"] = {"post": p.json(), "videoId": vid["videoId"], "bytes": mp4}
    return ctx.shared["t2_video"]


# --------------------------------------------------------------------------- F1
def _t2_f1_01(ctx: SuiteContext) -> None:
    ctx.set_social_flags(True, False)
    expect_error(ctx.proxy.get("/api/social/v1/posts"), 401, "UNAUTHORIZED", "unauthenticated /api/social/v1/posts")


def _t2_f1_02(ctx: SuiteContext) -> None:
    ctx.set_social_flags(False, False)
    r = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reader"))
    expect_error(r, 403, "SOCIAL_FEATURE_DISABLED", "authenticated /api/social/v1/posts when webEnabled=false")


def _t2_f1_03(ctx: SuiteContext) -> None:
    cur = ctx.get_admin_settings()
    r = ctx.spring.patch(
        "/api/v1/admin/social/settings/flags",
        token=ctx.token("admin"),
        body={"expectedRevision": cur["revision"], "webEnabled": None, "videoUploadsEnabled": False},
    )
    expect_error(r, 400, "VALIDATION_FAILED", "null webEnabled on PATCH /flags")


def _t2_f1_04(ctx: SuiteContext) -> None:
    ctx.set_social_flags(False, False)
    for path in ("/api/social/v1/images", "/api/social/v1/videos"):
        r = ctx.proxy.upload_multipart(
            path,
            token=ctx.token("author"),
            files=[("file", "x.png", "image/png", make_png_bytes())],
        )
        expect_error(r, 403, "SOCIAL_FEATURE_DISABLED", f"POST {path} when webEnabled=false")


def _t2_f1_05(ctx: SuiteContext) -> None:
    ctx.set_social_flags(True, False)
    r = ctx.proxy.post(
        "/api/social/v1/posts",
        token=ctx.token("author"),
        body={"body": ctx.text("video off bind"), "clientPostId": str(uuid.uuid4()), "videoId": str(uuid.uuid4())},
    )
    expect_error(r, 403, "SOCIAL_FEATURE_DISABLED", "bind videoId when videoUploadsEnabled=false")


# --------------------------------------------------------------------------- F2
def _t2_f2_01(ctx: SuiteContext) -> None:
    bad = ctx.proxy.post(
        "/api/social/v1/session/login",
        body={"identifier": ctx.phone("reader"), "password": "wrong-password-123"},
    )
    expect_status(bad, 401, "login with wrong password")


def _t2_f2_02(ctx: SuiteContext) -> None:
    bad_idem = ctx.proxy.post(
        "/api/social/v1/session/refresh",
        body={"refreshToken": "bogus"},
        headers={"Idempotency-Key": "not-a-uuidv4"},
    )
    expect_error(bad_idem, 400, "VALIDATION_FAILED", "non-UUIDv4 Idempotency-Key on refresh")


def _t2_f2_03(ctx: SuiteContext) -> None:
    bad_ref = ctx.proxy.post(
        "/api/social/v1/session/refresh",
        body={"refreshToken": "invalid-refresh-token"},
        headers={"Idempotency-Key": str(uuid.uuid4())},
    )
    expect_status(bad_ref, 401, "invalid refreshToken rejected")


def _t2_f2_04(ctx: SuiteContext) -> None:
    expect_error(ctx.spring.get("/api/v1/admin/me", token=ctx.token("reader")), 403, "FORBIDDEN", "USER denied /admin/me")


def _t2_f2_05(ctx: SuiteContext) -> None:
    out = ctx.proxy.post("/api/social/v1/session/logout", body={"refreshToken": "abc"})
    expect_status(out, 401, "logout without bearer token")


# --------------------------------------------------------------------------- F3
def _t2_f3_01(ctx: SuiteContext) -> None:
    ctx.set_social_flags(True, True)
    for bad in ({"body": "   ", "clientPostId": str(uuid.uuid4())}, {"clientPostId": str(uuid.uuid4())}):
        r = ctx.proxy.post("/api/social/v1/posts", token=ctx.token("author"), body=bad)
        expect_error(r, 400, "VALIDATION_FAILED", f"invalid post payload {bad}")


def _t2_f3_02(ctx: SuiteContext) -> None:
    over = ctx.proxy.post(
        "/api/social/v1/posts",
        token=ctx.token("author"),
        body={"body": "x" * (MAX_POST_BODY + 1), "clientPostId": str(uuid.uuid4())},
    )
    expect_error(over, 400, "VALIDATION_FAILED", "1001-char post body")
    missing_cid = ctx.proxy.post("/api/social/v1/posts", token=ctx.token("author"), body={"body": "hello"})
    expect_error(missing_cid, 400, "VALIDATION_FAILED", "missing clientPostId")


def _t2_f3_03(ctx: SuiteContext) -> None:
    p = _ensure_t2_post(ctx)
    expect_error(
        ctx.proxy.delete(f"/api/social/v1/posts/{p['id']}", token=ctx.token("reader")),
        403,
        "FORBIDDEN",
        "non-author delete post",
    )
    expect_error(
        ctx.proxy.delete(f"/api/social/v1/posts/{uuid.uuid4()}", token=ctx.token("author")),
        404,
        "POST_NOT_FOUND",
        "delete unknown post",
    )


def _t2_f3_04(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_edit, "PATCH /posts/{id} validation not deployed yet")
    p = _ensure_t2_post(ctx)
    expect_error(
        ctx.proxy.patch(f"/api/social/v1/posts/{p['id']}", token=ctx.token("author"), body={"body": "   "}),
        400,
        "VALIDATION_FAILED",
        "PATCH blank post body",
    )
    expect_error(
        ctx.proxy.patch(
            f"/api/social/v1/posts/{p['id']}",
            token=ctx.token("author"),
            body={"body": "x" * (MAX_POST_BODY + 1)},
        ),
        400,
        "VALIDATION_FAILED",
        "PATCH 1001-char post body",
    )


def _t2_f3_05(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_edit, "PATCH /posts/{id} authz not deployed yet")
    p = _ensure_t2_post(ctx)
    expect_error(
        ctx.proxy.patch(f"/api/social/v1/posts/{p['id']}", token=ctx.token("reader"), body={"body": "hijack"}),
        403,
        "FORBIDDEN",
        "non-author PATCH post",
    )
    expect_error(
        ctx.proxy.patch(f"/api/social/v1/posts/{uuid.uuid4()}", token=ctx.token("author"), body={"body": "nope"}),
        404,
        "POST_NOT_FOUND",
        "PATCH unknown post",
    )


# --------------------------------------------------------------------------- F4
def _t2_f4_01(ctx: SuiteContext) -> None:
    ctx.set_social_flags(True, True)
    r = ctx.proxy.upload_multipart(
        "/api/social/v1/images",
        token=ctx.token("author"),
        files=[("file", "fake.png", "image/png", NOT_AN_IMAGE_BYTES)],
    )
    expect_error(r, 415, "MIME_NOT_ALLOWED", "spoofed PNG bytes")


def _t2_f4_02(ctx: SuiteContext) -> None:
    png = make_png_bytes(8, 8)
    for fields in ({"width": "20000", "height": "4"}, {"width": "-1", "height": "4"}):
        r = ctx.proxy.upload_multipart(
            "/api/social/v1/images",
            token=ctx.token("author"),
            files=[("file", "bad_dim.png", "image/png", png)],
            fields=fields,
        )
        expect_error(r, 400, "VALIDATION_FAILED", f"invalid dimensions {fields}")


def _t2_f4_03(ctx: SuiteContext) -> None:
    for trailing in (False, True):
        m4a = load_audio_only_m4a_bytes(trailing_moov=trailing)
        r = ctx.proxy.upload_multipart(
            "/api/social/v1/videos",
            token=ctx.token("author"),
            files=[("file", "audio.mp4", "video/mp4", m4a)],
        )
        expect_error(r, 415, "MIME_NOT_ALLOWED", f"audio-only M4A (trailing={trailing})")


def _t2_f4_04(ctx: SuiteContext) -> None:
    big = make_oversized_mp4_bytes()
    r = ctx.proxy.upload_multipart(
        "/api/social/v1/videos",
        token=ctx.token("author"),
        files=[("file", "big.mp4", "video/mp4", big)],
    )
    expect_error(r, 413, "FILE_TOO_LARGE", ">4 MiB MP4 upload")


def _t2_f4_05(ctx: SuiteContext) -> None:
    ctx.require_milestone(
        "M1",
        ctx.caps.m1_range_video and ctx.caps.m1_multipart_guard,
        "Range 206/416 & MultipartRequests.requireSingleFile not deployed yet",
    )
    vid = _ensure_t2_video(ctx)
    total = len(vid["bytes"])
    suffix = ctx.proxy.get_range(
        f"/api/social/v1/videos/{vid['videoId']}",
        token=ctx.token("author"),
        range_header="bytes=-128",
    )
    expect_status(suffix, 206, "suffix Range bytes=-128")
    check(suffix.body == vid["bytes"][-128:], "suffix range body mismatch")

    oob = ctx.proxy.get_range(
        f"/api/social/v1/videos/{vid['videoId']}",
        token=ctx.token("author"),
        range_header=f"bytes={total + 100}-",
    )
    expect_status(oob, 416, "out-of-bounds Range 416")
    check(oob.header("content-range") == f"bytes */{total}", f"416 Content-Range mismatch: {oob.header('content-range')}")

    # Single-file multipart guard: 0 file parts & 2 file parts -> 400
    no_file = ctx.proxy.upload_multipart("/api/social/v1/images", token=ctx.token("author"), files=[], fields={"width": "8"})
    expect_error(no_file, 400, "VALIDATION_FAILED", "0 file parts on POST /images")
    png = make_png_bytes(8, 8)
    two_files = ctx.proxy.upload_multipart(
        "/api/social/v1/images",
        token=ctx.token("author"),
        files=[("file", "a.png", "image/png", png), ("file", "b.png", "image/png", png)],
    )
    expect_error(two_files, 400, "VALIDATION_FAILED", "2 file parts on POST /images")


# --------------------------------------------------------------------------- F5
def _t2_f5_01(ctx: SuiteContext) -> None:
    p = _ensure_t2_post(ctx)
    expect_error(
        ctx.proxy.put(reaction_path("/api/social/v1", p["id"], "x" * 17), token=ctx.token("reactor")),
        400,
        "VALIDATION_FAILED",
        "17-char emoji",
    )


def _t2_f5_02(ctx: SuiteContext) -> None:
    expect_error(
        ctx.proxy.put(reaction_path("/api/social/v1", str(uuid.uuid4()), DISTINCT_EMOJIS[0]), token=ctx.token("reactor")),
        404,
        "POST_NOT_FOUND",
        "react to unknown post",
    )


def _t2_f5_03(ctx: SuiteContext) -> None:
    p = _ensure_t2_post(ctx)
    for emoji in DISTINCT_EMOJIS:
        expect_status(ctx.proxy.put(reaction_path("/api/social/v1", p["id"], emoji), token=ctx.token("blocker")), 200, "add distinct emoji")
    expect_error(
        ctx.proxy.put(reaction_path("/api/social/v1", p["id"], NINTH_EMOJI), token=ctx.token("blocker")),
        400,
        "VALIDATION_FAILED",
        "9th distinct emoji rejected",
    )


def _t2_f5_04(ctx: SuiteContext) -> None:
    p = _ensure_t2_post(ctx)
    # 65 rejections must not drain the 60/min reaction bucket
    for idx in range(65):
        r = ctx.spring.put(reaction_path("/api/v1/feed", p["id"], NINTH_EMOJI), token=ctx.token("blocker"))
        expect_error(r, 400, "VALIDATION_FAILED", f"9th emoji cap rejection #{idx+1}")
    expect_status(
        ctx.spring.delete(reaction_path("/api/v1/feed", p["id"], DISTINCT_EMOJIS[0]), token=ctx.token("blocker")),
        200,
        "reaction still allowed after cap rejections",
    )


def _t2_f5_05(ctx: SuiteContext) -> None:
    p = _ensure_t2_post(ctx)
    expect_status(
        ctx.spring.put(reaction_path("/api/v1/feed", p["id"], NINTH_EMOJI), token=ctx.token("blocker")),
        200,
        "9th emoji succeeds once one removed",
    )


# --------------------------------------------------------------------------- F6
def _t2_f6_01(ctx: SuiteContext) -> None:
    p = _ensure_t2_post(ctx)
    expect_error(
        ctx.proxy.post(
            f"/api/social/v1/posts/{p['id']}/comments",
            token=ctx.token("reader"),
            body={"body": "   ", "clientCommentId": str(uuid.uuid4())},
        ),
        400,
        "VALIDATION_FAILED",
        "blank comment",
    )


def _t2_f6_02(ctx: SuiteContext) -> None:
    p = _ensure_t2_post(ctx)
    expect_error(
        ctx.proxy.post(
            f"/api/social/v1/posts/{p['id']}/comments",
            token=ctx.token("reader"),
            body={"body": "y" * (MAX_COMMENT_BODY + 1), "clientCommentId": str(uuid.uuid4())},
        ),
        400,
        "VALIDATION_FAILED",
        "501-char comment",
    )


def _t2_f6_03(ctx: SuiteContext) -> None:
    expect_error(
        ctx.proxy.post(
            f"/api/social/v1/posts/{uuid.uuid4()}/comments",
            token=ctx.token("reader"),
            body={"body": "hello", "clientCommentId": str(uuid.uuid4())},
        ),
        404,
        "POST_NOT_FOUND",
        "comment on unknown post",
    )


def _t2_f6_04(ctx: SuiteContext) -> None:
    p = _ensure_t2_post(ctx)
    c = ctx.spring.post(
        f"/api/v1/feed/posts/{p['id']}/comments",
        token=ctx.token("reader"),
        body={"body": ctx.text("reader comment for authz"), "clientCommentId": str(uuid.uuid4())},
    )
    expect_status(c, 201, "create comment")
    cid = c.json()["id"]
    ctx.shared["t2_comment_id"] = cid
    expect_error(
        ctx.proxy.delete(f"/api/social/v1/comments/{cid}", token=ctx.token("author")),
        403,
        "FORBIDDEN",
        "post author cannot delete reader comment",
    )


def _t2_f6_05(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_edit, "PATCH /comments/{id} boundaries not deployed yet")
    cid = ctx.shared["t2_comment_id"]
    expect_error(
        ctx.proxy.patch(f"/api/social/v1/comments/{cid}", token=ctx.token("reader"), body={"body": "   "}),
        400,
        "VALIDATION_FAILED",
        "PATCH blank comment",
    )
    expect_error(
        ctx.proxy.patch(
            f"/api/social/v1/comments/{cid}",
            token=ctx.token("reader"),
            body={"body": "z" * (MAX_COMMENT_BODY + 1)},
        ),
        400,
        "VALIDATION_FAILED",
        "PATCH 501-char comment",
    )
    expect_error(
        ctx.proxy.patch(f"/api/social/v1/comments/{cid}", token=ctx.token("author"), body={"body": "hijack"}),
        403,
        "FORBIDDEN",
        "non-author PATCH comment",
    )
    expect_error(
        ctx.proxy.patch(f"/api/social/v1/comments/{uuid.uuid4()}", token=ctx.token("reader"), body={"body": "missing"}),
        404,
        "COMMENT_NOT_FOUND",
        "PATCH unknown comment",
    )


# --------------------------------------------------------------------------- F7
def _t2_f7_01(ctx: SuiteContext) -> None:
    expect_error(
        ctx.proxy.post(
            "/api/social/v1/reports",
            token=ctx.token("reactor"),
            body={"targetType": "POST", "targetId": str(uuid.uuid4()), "reason": "SPAM"},
        ),
        404,
        "POST_NOT_FOUND",
        "report unknown post",
    )


def _t2_f7_02(ctx: SuiteContext) -> None:
    expect_error(
        ctx.proxy.post(
            "/api/social/v1/reports",
            token=ctx.token("reactor"),
            body={"targetType": "COMMENT", "targetId": str(uuid.uuid4()), "reason": "SPAM"},
        ),
        404,
        "COMMENT_NOT_FOUND",
        "report unknown comment",
    )


def _t2_f7_03(ctx: SuiteContext) -> None:
    p = _ensure_t2_post(ctx)
    expect_error(
        ctx.proxy.post(
            "/api/social/v1/reports",
            token=ctx.token("reactor"),
            body={"targetType": "POST", "targetId": p["id"], "reason": "NOT_A_REASON"},
        ),
        400,
        "VALIDATION_FAILED",
        "unknown report reason",
    )


def _t2_f7_04(ctx: SuiteContext) -> None:
    p = _ensure_t2_post(ctx)
    expect_error(
        ctx.proxy.post(
            "/api/social/v1/reports",
            token=ctx.token("reactor"),
            body={"targetType": "POST", "targetId": p["id"]},
        ),
        400,
        "VALIDATION_FAILED",
        "missing report reason",
    )


def _t2_f7_05(ctx: SuiteContext) -> None:
    p = _ensure_t2_post(ctx)
    expect_error(
        ctx.proxy.post(
            "/api/social/v1/reports",
            token=ctx.token("reactor"),
            body={"targetType": "POST", "targetId": p["id"], "reason": "OTHER", "details": "d" * (MAX_REPORT_DETAILS + 1)},
        ),
        400,
        "VALIDATION_FAILED",
        "501-char report details",
    )


# --------------------------------------------------------------------------- F8
def _t2_f8_01(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_authors, "GET /authors/{id} not deployed yet")
    expect_error(
        ctx.proxy.get(f"/api/social/v1/authors/{uuid.uuid4()}", token=ctx.token("reader")),
        404,
        "USER_NOT_FOUND",
        "unknown author profile",
    )


def _t2_f8_02(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_authors, "GET /authors/{id} UUID validation not deployed yet")
    r = ctx.proxy.get("/api/social/v1/authors/not-a-uuid", token=ctx.token("reader"))
    expect_status(r, 400, "malformed author UUID")


def _t2_f8_03(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_search, "GET /posts?authorId= validation not deployed yet")
    r = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reader"), params={"authorId": "not-a-uuid"})
    expect_status(r, 400, "malformed authorId query param")


def _t2_f8_04(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_search, "GET /posts?authorId= unknown not deployed yet")
    r = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reader"), params={"authorId": str(uuid.uuid4())})
    expect_status(r, 200, "unknown authorId returns empty page")
    check(r.json() == {"items": [], "nextCursor": None}, f"expected empty page, got {r.json()}")


def _t2_f8_05(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_authors, "GET /authors/{id} block check not deployed yet")
    try:
        ctx.ensure_block("reader", "blocker", True)
        expect_error(
            ctx.proxy.get(f"/api/social/v1/authors/{ctx.uid('blocker')}", token=ctx.token("reader")),
            404,
            "USER_NOT_FOUND",
            "blocked author profile returns 404",
        )
    finally:
        ctx.ensure_block("reader", "blocker", False)


# --------------------------------------------------------------------------- F9
def _t2_f9_01(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_search, "GET /posts?q= not deployed yet")
    r = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reader"), params={"q": "   "})
    expect_status(r, 200, "blank whitespace q treated as unfiltered")


def _t2_f9_02(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_search, "GET /posts?q= max length not deployed yet")
    ok = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reader"), params={"q": "a" * 100})
    expect_status(ok, 200, "100-char q accepted")
    bad = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reader"), params={"q": "a" * 101})
    expect_error(bad, 400, "VALIDATION_FAILED", "101-char q rejected")


def _t2_f9_03(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_search, "GET /posts?q= wildcard escaping not deployed yet")
    # '%' and '_' must not act as SQL LIKE match-all wildcards
    r_pct = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reader"), params={"q": "%%%"})
    expect_status(r_pct, 200, "SQL wildcard %%% in q")
    check(r_pct.json()["items"] == [], f"'%%%' matched posts as wildcard: {r_pct.json()['items']}")


def _t2_f9_04(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_search, "GET /posts?q= SQL meta chars not deployed yet")
    r = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reader"), params={"q": "'; DROP TABLE public_posts; --"})
    expect_status(r, 200, "SQL injection metacharacters in q")
    check(r.json()["items"] == [], "unexpected match on SQL string")


def _t2_f9_05(ctx: SuiteContext) -> None:
    expect_error(
        ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reader"), params={"cursor": "not-a-cursor"}),
        400,
        "VALIDATION_FAILED",
        "malformed feed cursor",
    )


# --------------------------------------------------------------------------- F10
def _t2_f10_01(ctx: SuiteContext) -> None:
    expect_error(
        ctx.spring.post(f"/api/v1/users/{ctx.uid('reader')}/block", token=ctx.token("reader")),
        400,
        "VALIDATION_FAILED",
        "self-block",
    )


def _t2_f10_02(ctx: SuiteContext) -> None:
    expect_error(
        ctx.spring.post(f"/api/v1/users/{uuid.uuid4()}/block", token=ctx.token("reader")),
        404,
        "USER_NOT_FOUND",
        "block unknown user",
    )


def _t2_f10_03(ctx: SuiteContext) -> None:
    vid = _ensure_t2_video(ctx)
    try:
        ctx.ensure_block("blocker", "reader", True)
        expect_error(
            ctx.proxy.get(f"/api/social/v1/videos/{vid['videoId']}", token=ctx.token("blocker")),
            404,
            "FILE_NOT_FOUND",
            "blocked user cannot fetch video",
        )
        expect_error(
            ctx.proxy.post(
                f"/api/social/v1/posts/{vid['post']['id']}/comments",
                token=ctx.token("blocker"),
                body={"body": "blocked comment", "clientCommentId": str(uuid.uuid4())},
            ),
            404,
            "POST_NOT_FOUND",
            "blocked user cannot comment",
        )
    finally:
        ctx.ensure_block("blocker", "reader", False)


def _t2_f10_04(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_search, "GET /posts?q= under block not deployed yet")
    p = _ensure_t2_post(ctx)
    try:
        ctx.ensure_block("blocker", "author", True)
        res = ctx.proxy.get("/api/social/v1/posts", token=ctx.token("blocker"), params={"q": ctx.nonce})
        expect_status(res, 200, "search while blocking author")
        check(all(it["id"] != p["id"] for it in res.json()["items"]), "blocked author's post appeared in search")
    finally:
        ctx.ensure_block("blocker", "author", False)


def _t2_f10_05(ctx: SuiteContext) -> None:
    p = _ensure_t2_post(ctx)
    ctx.ensure_block("blocker", "author", True)
    ctx.ensure_block("blocker", "author", False)
    expect_status(ctx.proxy.get(f"/api/social/v1/posts/{p['id']}", token=ctx.token("blocker")), 200, "unblock restores immediately")


# --------------------------------------------------------------------------- F11
def _t2_f11_01(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_edit, "M1 comment parent deleted visibility fix not deployed yet")
    p = ctx.create_post_safe("author", ctx.text("f11 deleted parent")).json()
    c = ctx.spring.post(
        f"/api/v1/feed/posts/{p['id']}/comments",
        token=ctx.token("reader"),
        body={"body": "under deleted", "clientCommentId": str(uuid.uuid4())},
    ).json()
    expect_status(ctx.spring.delete(f"/api/v1/feed/posts/{p['id']}", token=ctx.token("author")), 204, "delete parent")
    expect_error(
        ctx.spring.delete(f"/api/v1/feed/comments/{c['id']}", token=ctx.token("blocker")),
        404,
        "COMMENT_NOT_FOUND",
        "third-party delete comment under deleted parent",
    )


def _t2_f11_02(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_edit, "M1 comment parent hidden visibility fix not deployed yet")
    p = ctx.create_post_safe("author", ctx.text("f11 hidden parent")).json()
    c = ctx.spring.post(
        f"/api/v1/feed/posts/{p['id']}/comments",
        token=ctx.token("reader"),
        body={"body": "under hidden", "clientCommentId": str(uuid.uuid4())},
    ).json()
    expect_status(
        ctx.spring.post(f"/api/v1/admin/feed/posts/{p['id']}/hide", token=ctx.token("moderator"), body={"reason": "hide parent"}),
        204,
        "hide parent",
    )
    expect_error(
        ctx.spring.delete(f"/api/v1/feed/comments/{c['id']}", token=ctx.token("blocker")),
        404,
        "COMMENT_NOT_FOUND",
        "third-party delete comment under hidden parent",
    )


def _t2_f11_03(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_edit, "M1 owner deleted image denial not deployed yet")
    up = ctx.spring.upload_multipart(
        "/api/v1/feed/images",
        token=ctx.token("reactor"),
        files=[("file", "del.png", "image/png", make_png_bytes(10, 10))],
    ).json()
    p = ctx.create_post_safe("reactor", ctx.text("owner del img"), image_id=up["imageId"]).json()
    expect_status(ctx.spring.delete(f"/api/v1/feed/posts/{p['id']}", token=ctx.token("reactor")), 204, "owner deletes image post")
    expect_error(
        ctx.spring.get(f"/api/v1/feed/images/{up['imageId']}", token=ctx.token("reactor")),
        404,
        "FILE_NOT_FOUND",
        "owner must get 404 on image of deleted post",
    )


def _t2_f11_04(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_edit, "M1 owner hidden video denial not deployed yet")
    up = ctx.spring.upload_multipart(
        "/api/v1/feed/videos",
        token=ctx.token("reactor"),
        files=[("file", "hid.mp4", "video/mp4", load_valid_mp4_bytes())],
    ).json()
    p = ctx.create_post_safe("reactor", ctx.text("owner hid vid"), video_id=up["videoId"]).json()
    expect_status(
        ctx.spring.post(f"/api/v1/admin/feed/posts/{p['id']}/hide", token=ctx.token("moderator"), body={"reason": "hide vid"}),
        204,
        "hide video post",
    )
    expect_error(
        ctx.spring.get(f"/api/v1/feed/videos/{up['videoId']}", token=ctx.token("reactor")),
        404,
        "FILE_NOT_FOUND",
        "owner must get 404 on video of hidden post",
    )


def _t2_f11_05(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_edit, "M1 FeedImageAccess owner check not deployed yet")
    # Unbound image remains readable by owner until bound/swept
    up = ctx.spring.upload_multipart(
        "/api/v1/feed/images",
        token=ctx.token("reactor"),
        files=[("file", "unbound.png", "image/png", make_png_bytes(10, 10))],
    ).json()
    expect_status(
        ctx.spring.get(f"/api/v1/feed/images/{up['imageId']}", token=ctx.token("reactor")),
        200,
        "unbound image still readable by owner",
    )


# --------------------------------------------------------------------------- F12
def _t2_f12_01(ctx: SuiteContext) -> None:
    expect_status(ctx.spring.get("/api/v1/admin/social/settings"), 401, "anon /admin/social/settings")
    expect_error(
        ctx.spring.get("/api/v1/admin/social/settings", token=ctx.token("reader")),
        403,
        "FORBIDDEN",
        "USER /admin/social/settings",
    )


def _t2_f12_02(ctx: SuiteContext) -> None:
    cur = ctx.get_admin_settings()
    stale = cur["revision"] - 1
    for method, path, body in (
        ("PATCH", "/api/v1/admin/social/settings/flags", {"expectedRevision": stale, "webEnabled": False, "videoUploadsEnabled": False}),
        ("PUT", "/api/v1/admin/social/settings/announcement/draft", {"expectedRevision": stale, "titleVi": "a", "bodyVi": "b", "titleEn": "c", "bodyEn": "d"}),
        ("POST", "/api/v1/admin/social/settings/announcement/publish", {"expectedRevision": stale}),
        ("POST", "/api/v1/admin/social/settings/announcement/unpublish", {"expectedRevision": stale}),
    ):
        r = ctx.spring.request(method, path, token=ctx.token("admin"), body=body)
        expect_error(r, 409, "SOCIAL_SETTINGS_CONFLICT", f"stale revision on {method} {path}")
    after = ctx.get_admin_settings()
    check(after["revision"] == cur["revision"], "409 conflict mutated revision")


def _t2_f12_03(ctx: SuiteContext) -> None:
    cur = ctx.get_admin_settings()
    if cur.get("draft") is None:
        r = ctx.spring.post(
            "/api/v1/admin/social/settings/announcement/publish",
            token=ctx.token("admin"),
            body={"expectedRevision": cur["revision"]},
        )
        expect_error(r, 400, "VALIDATION_FAILED", "publish without saved draft")
    else:
        # Verify draft fields cannot be blank
        r = ctx.spring.put(
            "/api/v1/admin/social/settings/announcement/draft",
            token=ctx.token("admin"),
            body={"expectedRevision": cur["revision"], "titleVi": " ", "bodyVi": "b", "titleEn": "c", "bodyEn": "d"},
        )
        expect_error(r, 400, "VALIDATION_FAILED", "blank titleVi rejected")


def _t2_f12_04(ctx: SuiteContext) -> None:
    cur = ctx.get_admin_settings()
    bad_title = ctx.spring.put(
        "/api/v1/admin/social/settings/announcement/draft",
        token=ctx.token("admin"),
        body={"expectedRevision": cur["revision"], "titleVi": "t" * (MAX_TITLE_LENGTH + 1), "bodyVi": "b", "titleEn": "c", "bodyEn": "d"},
    )
    expect_error(bad_title, 400, "VALIDATION_FAILED", "101-char draft titleVi")
    bad_body = ctx.spring.put(
        "/api/v1/admin/social/settings/announcement/draft",
        token=ctx.token("admin"),
        body={"expectedRevision": cur["revision"], "titleVi": "t", "bodyVi": "b" * (MAX_ANNOUNCEMENT_BODY + 1), "titleEn": "c", "bodyEn": "d"},
    )
    expect_error(bad_body, 400, "VALIDATION_FAILED", "501-char draft bodyVi")


def _t2_f12_05(ctx: SuiteContext) -> None:
    mod = ctx.token("moderator")
    for method, path, body in (
        ("GET", "/api/v1/admin/social/settings", None),
        ("PATCH", "/api/v1/admin/social/settings/flags", {"expectedRevision": 1, "webEnabled": False, "videoUploadsEnabled": False}),
        ("PUT", "/api/v1/admin/social/settings/announcement/draft", {"expectedRevision": 1, "titleVi": "a", "bodyVi": "b", "titleEn": "c", "bodyEn": "d"}),
        ("POST", "/api/v1/admin/social/settings/announcement/publish", {"expectedRevision": 1}),
        ("POST", "/api/v1/admin/social/settings/announcement/unpublish", {"expectedRevision": 1}),
    ):
        r = ctx.spring.request(method, path, token=mod, body=body)
        expect_error(r, 403, "NOT_ADMIN", f"MODERATOR denied on {method} {path}")


# --------------------------------------------------------------------------- F13
def _t2_f13_01(ctx: SuiteContext) -> None:
    plain = ctx.token("reader")
    for method, path, body in (
        ("GET", "/api/v1/admin/feed/reports", None),
        ("POST", f"/api/v1/admin/feed/posts/{uuid.uuid4()}/hide", {"reason": "x"}),
        ("POST", f"/api/v1/admin/feed/comments/{uuid.uuid4()}/hide", {"reason": "x"}),
        ("POST", f"/api/v1/admin/feed/reports/{uuid.uuid4()}/resolve", {"status": "DISMISSED"}),
        ("POST", f"/api/v1/admin/feed/reports/{uuid.uuid4()}/video", None),
    ):
        expect_error(ctx.spring.request(method, path, token=plain, body=body), 403, "FORBIDDEN", f"USER denied {method} {path}")


def _t2_f13_02(ctx: SuiteContext) -> None:
    p = _ensure_t2_post(ctx)
    mod = ctx.token("moderator")
    for body in ({}, {"reason": "   "}, {"reason": "r" * 501}):
        expect_error(
            ctx.spring.post(f"/api/v1/admin/feed/posts/{p['id']}/hide", token=mod, body=body),
            400,
            "VALIDATION_FAILED",
            f"hide invalid reason {body}",
        )


def _t2_f13_03(ctx: SuiteContext) -> None:
    mod = ctx.token("moderator")
    expect_error(
        ctx.spring.post(f"/api/v1/admin/feed/posts/{uuid.uuid4()}/hide", token=mod, body={"reason": "missing"}),
        404,
        "POST_NOT_FOUND",
        "hide unknown post",
    )
    expect_error(
        ctx.spring.post(f"/api/v1/admin/feed/comments/{uuid.uuid4()}/hide", token=mod, body={"reason": "missing"}),
        404,
        "COMMENT_NOT_FOUND",
        "hide unknown comment",
    )


def _t2_f13_04(ctx: SuiteContext) -> None:
    mod = ctx.token("moderator")
    expect_error(
        ctx.spring.post(f"/api/v1/admin/feed/reports/{uuid.uuid4()}/resolve", token=mod, body={"status": "OPEN"}),
        400,
        "VALIDATION_FAILED",
        "resolve to OPEN",
    )
    expect_error(
        ctx.spring.post(f"/api/v1/admin/feed/reports/{uuid.uuid4()}/resolve", token=mod, body={"status": "REVIEWED"}),
        404,
        "NOT_FOUND",
        "resolve unknown report",
    )


def _t2_f13_05(ctx: SuiteContext) -> None:
    # Reported video preview on a deleted post returns 404 FILE_NOT_FOUND
    ctx.set_social_flags(True, True)
    up = ctx.spring.upload_multipart(
        "/api/v1/feed/videos",
        token=ctx.token("author"),
        files=[("file", "delvid.mp4", "video/mp4", load_valid_mp4_bytes())],
    ).json()
    p = ctx.create_post_safe("author", ctx.text("deleted video report"), video_id=up["videoId"]).json()
    rep = ctx.spring.post(
        "/api/v1/reports",
        token=ctx.token("reader"),
        body={"targetType": "POST", "targetId": p["id"], "reason": "SPAM"},
    ).json()
    expect_status(ctx.spring.delete(f"/api/v1/feed/posts/{p['id']}", token=ctx.token("author")), 204, "delete reported video post")
    expect_error(
        ctx.spring.post(f"/api/v1/admin/feed/reports/{rep['id']}/video", token=ctx.token("moderator")),
        404,
        "FILE_NOT_FOUND",
        "video preview on deleted post",
    )
    ctx.spring.post(f"/api/v1/admin/feed/reports/{rep['id']}/resolve", token=ctx.token("moderator"), body={"status": "DISMISSED"})


# --------------------------------------------------------------------------- F14
def _t2_f14_01(ctx: SuiteContext) -> None:
    check(ctx.proxy.get("/api/social/v1/admin/me", token=ctx.token("admin")).status == 404, "proxy must not expose wildcard /api/social/v1/*")


def _t2_f14_02(ctx: SuiteContext) -> None:
    r = ctx.proxy.get("/social/../../server/pom.xml")
    check(r.status in (403, 404), f"path traversal must be blocked, got {r.status}")


def _t2_f14_03(ctx: SuiteContext) -> None:
    # Already-attached image & cross-user image bind rejected with 400
    up = ctx.spring.upload_multipart(
        "/api/v1/feed/images",
        token=ctx.token("author"),
        files=[("file", "own.png", "image/png", make_png_bytes(8, 8))],
    ).json()
    stolen = ctx.create_post_safe("reader", ctx.text("steal img"), image_id=up["imageId"])
    expect_error(stolen, 400, "VALIDATION_FAILED", "bind another user's image")


def _t2_f14_04(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_edit, "M1 video rebind message fix not deployed yet")
    vid = _ensure_t2_video(ctx)
    rebind = ctx.spring.post(
        "/api/v1/feed/posts",
        token=ctx.token("reader"),
        body={"body": ctx.text("rebind vid"), "clientPostId": str(uuid.uuid4()), "videoId": vid["videoId"]},
    )
    expect_error(rebind, 400, "VALIDATION_FAILED", "rebind attached video")
    msg = (rebind.error_message() or "").lower()
    check("imageid" not in msg, f"video rebind error misleadingly mentions imageId: {msg!r}")


def _t2_f14_05(ctx: SuiteContext) -> None:
    # Wrong MIME for /images with MP4 bytes -> 415
    mp4 = load_valid_mp4_bytes()
    r = ctx.spring.upload_multipart(
        "/api/v1/feed/images",
        token=ctx.token("author"),
        files=[("file", "vid_as_img.png", "image/png", mp4)],
    )
    expect_error(r, 415, "MIME_NOT_ALLOWED", "MP4 uploaded to /images rejected")


# --------------------------------------------------------------------------- F15 (rate limits & encoding)
def _t2_f15_01(ctx: SuiteContext) -> None:
    # Drain moderator's 3/min post bucket (moderator hasn't created any posts yet)
    tok = ctx.token("moderator")
    denied = None
    for _ in range(POSTS_PER_MINUTE + 1):
        r = ctx.spring.post(
            "/api/v1/feed/posts",
            token=tok,
            body={"body": ctx.text("rate drain"), "clientPostId": str(uuid.uuid4())},
        )
        if r.status == 429:
            denied = r
            break
        expect_status(r, 201, "moderator drain post")
    check(denied is not None, "4th post in 1 min was not rate-limited")
    expect_error(denied, 429, "RATE_LIMITED", "post rate limit 429")
    check(denied.header("retry-after") is not None, "429 missing Retry-After header")
    ctx.shared["mod_rate_wait"] = retry_after_seconds(denied)


def _t2_f15_02(ctx: SuiteContext) -> None:
    # Wait for refill, send invalid payloads, verify bucket NOT burned
    tok = ctx.token("moderator")
    wait = min(65, int(ctx.shared.get("mod_rate_wait", 60)) + 1)
    time.sleep(wait)
    for _ in range(3):
        expect_error(
            ctx.spring.post("/api/v1/feed/posts", token=tok, body={"clientPostId": str(uuid.uuid4())}),
            400,
            "VALIDATION_FAILED",
            "invalid empty post after refill",
        )
        expect_error(
            ctx.spring.post(
                "/api/v1/feed/posts",
                token=tok,
                body={"body": "x" * (MAX_POST_BODY + 1), "clientPostId": str(uuid.uuid4())},
            ),
            400,
            "VALIDATION_FAILED",
            "oversize post after refill",
        )
    ok = ctx.spring.post(
        "/api/v1/feed/posts",
        token=tok,
        body={"body": ctx.text("rate refill preserved"), "clientPostId": str(uuid.uuid4())},
    )
    expect_status(ok, 201, "valid post succeeds after invalid payloads")
    ctx.shared["mod_refill_post"] = ok.json()


def _t2_f15_03(ctx: SuiteContext) -> None:
    ctx.require_milestone("M1", ctx.caps.m1_edit, "M1 dead-replay quota preservation fix not deployed yet")
    # Replay of a deleted clientPostId returns 404 without burning quota
    cid = str(uuid.uuid4())
    p = ctx.create_post_safe("blocker", ctx.text("dead replay"), client_post_id=cid).json()
    expect_status(ctx.spring.delete(f"/api/v1/feed/posts/{p['id']}", token=ctx.token("blocker")), 204, "delete for dead replay")
    dead = ctx.spring.post("/api/v1/feed/posts", token=ctx.token("blocker"), body={"body": "dead", "clientPostId": cid})
    expect_error(dead, 404, "POST_NOT_FOUND", "dead clientPostId replay")


def _t2_f15_04(ctx: SuiteContext) -> None:
    # 12 invalid comments must not burn the 10/min comment bucket
    p = _ensure_t2_post(ctx)
    tok = ctx.token("blocker")
    for _ in range(12):
        expect_error(
            ctx.spring.post(
                f"/api/v1/feed/posts/{p['id']}/comments",
                token=tok,
                body={"body": "   ", "clientCommentId": str(uuid.uuid4())},
            ),
            400,
            "VALIDATION_FAILED",
            "invalid comment quota preservation",
        )
    ok = ctx.spring.post(
        f"/api/v1/feed/posts/{p['id']}/comments",
        token=tok,
        body={"body": ctx.text("comment quota intact"), "clientCommentId": str(uuid.uuid4())},
    )
    expect_status(ok, 201, "valid comment after 12 invalid comments")


def _t2_f15_05(ctx: SuiteContext) -> None:
    # Adversarial UTF-8 & HTML/JSON metacharacters preserved verbatim
    special = '<script>alert("xss")</script> & \'quotes\' Ti\u1ebfng Vi\u1ec7t \u2764\ufe0f \\backslash'
    p = ctx.create_post_safe("blocker", special)
    expect_status(p, 201, "special character post")
    check(p.json()["body"] == special, f"special characters corrupted: {p.json()['body']!r}")
    ctx.spring.delete(f"/api/v1/feed/posts/{p.json()['id']}", token=ctx.token("blocker"))


# --------------------------------------------------------------------------- F16 (Android API boundaries)
def _t2_f16_01(ctx: SuiteContext) -> None:
    expect_error(ctx.spring.get("/api/v1/feed/posts"), 401, "UNAUTHORIZED", "anon /api/v1/feed/posts")


def _t2_f16_02(ctx: SuiteContext) -> None:
    r = ctx.spring.get("/api/v1/feed/posts/not-a-uuid", token=ctx.token("reader"))
    expect_status(r, 400, "invalid UUID on /api/v1/feed/posts/{id}")


def _t2_f16_03(ctx: SuiteContext) -> None:
    r = ctx.spring.post(
        "/api/v1/feed/posts",
        token=ctx.token("reader"),
        body={"body": "a" * (MAX_POST_BODY + 1), "clientPostId": str(uuid.uuid4())},
    )
    expect_error(r, 400, "VALIDATION_FAILED", "oversize body on /api/v1/feed/posts")


def _t2_f16_04(ctx: SuiteContext) -> None:
    ctx.set_social_flags(False, True)
    r = ctx.spring.upload_multipart(
        "/api/v1/feed/videos",
        token=ctx.token("reader"),
        files=[("file", "big.mp4", "video/mp4", make_oversized_mp4_bytes())],
    )
    expect_error(r, 413, "FILE_TOO_LARGE", ">4 MiB MP4 on /api/v1/feed/videos while webEnabled=false")


def _t2_f16_05(ctx: SuiteContext) -> None:
    ctx.set_social_flags(False, False)
    r = ctx.spring.upload_multipart(
        "/api/v1/feed/videos",
        token=ctx.token("reader"),
        files=[("file", "ok.mp4", "video/mp4", load_valid_mp4_bytes())],
    )
    expect_error(r, 403, "SOCIAL_FEATURE_DISABLED", "videoUploadsEnabled=false blocks /api/v1/feed/videos")
    expect_status(ctx.spring.get("/api/v1/feed/posts", token=ctx.token("reader")), 200, "Android reads stay 200")


def get_tier2_checks() -> List[CheckCase]:
    raw = [
        ("T2.F1.01", "F1", "BASELINE", "Unauthenticated /api/social/v1/posts -> 401", _t2_f1_01),
        ("T2.F1.02", "F1", "BASELINE", "Authenticated /api/social/v1/posts when webEnabled=false -> 403", _t2_f1_02),
        ("T2.F1.03", "F1", "BASELINE", "Null flag rejected 400 on PATCH /flags", _t2_f1_03),
        ("T2.F1.04", "F1", "BASELINE", "Media uploads on /api/social/v1 blocked 403 when webEnabled=false", _t2_f1_04),
        ("T2.F1.05", "F1", "BASELINE", "Post bind with videoId blocked 403 when videoUploadsEnabled=false", _t2_f1_05),
        ("T2.F2.01", "F2", "BASELINE", "Wrong password rejected 401", _t2_f2_01),
        ("T2.F2.02", "F2", "BASELINE", "Non-UUIDv4 Idempotency-Key on /refresh rejected 400", _t2_f2_02),
        ("T2.F2.03", "F2", "BASELINE", "Invalid refreshToken rejected 401", _t2_f2_03),
        ("T2.F2.04", "F2", "BASELINE", "USER denied 403 on /api/v1/admin/me", _t2_f2_04),
        ("T2.F2.05", "F2", "BASELINE", "Logout without bearer token rejected 401", _t2_f2_05),
        ("T2.F3.01", "F3", "BASELINE", "Blank/missing post body without media -> 400", _t2_f3_01),
        ("T2.F3.02", "F3", "BASELINE", "1001-char post body & missing clientPostId -> 400", _t2_f3_02),
        ("T2.F3.03", "F3", "BASELINE", "Non-author delete -> 403; unknown post delete -> 404", _t2_f3_03),
        ("T2.F3.04", "F3", "M1", "PATCH /posts/{id} blank & >1000 char rejected 400", _t2_f3_04),
        ("T2.F3.05", "F3", "M1", "PATCH /posts/{id} non-author -> 403; unknown -> 404", _t2_f3_05),
        ("T2.F4.01", "F4", "BASELINE", "Spoofed text as image/png rejected 415 MIME_NOT_ALLOWED", _t2_f4_01),
        ("T2.F4.02", "F4", "BASELINE", "Invalid image dimensions (20000, -1) rejected 400", _t2_f4_02),
        ("T2.F4.03", "F4", "BASELINE", "Audio-only M4A rejected 415 on POST /videos", _t2_f4_03),
        ("T2.F4.04", "F4", "BASELINE", "Oversized >4 MiB MP4 rejected 413 FILE_TOO_LARGE", _t2_f4_04),
        ("T2.F4.05", "F4", "M1", "Range suffix 206, invalid 416, and 0/2-part multipart 400", _t2_f4_05),
        ("T2.F5.01", "F5", "BASELINE", "17-char emoji rejected 400 VALIDATION_FAILED", _t2_f5_01),
        ("T2.F5.02", "F5", "BASELINE", "React on unknown post -> 404 POST_NOT_FOUND", _t2_f5_02),
        ("T2.F5.03", "F5", "BASELINE", "9th distinct emoji rejected 400 VALIDATION_FAILED", _t2_f5_03),
        ("T2.F5.04", "F5", "BASELINE", "65 cap rejections do not burn 60/min reaction bucket", _t2_f5_04),
        ("T2.F5.05", "F5", "BASELINE", "9th emoji fits after removing one reaction", _t2_f5_05),
        ("T2.F6.01", "F6", "BASELINE", "Blank comment body -> 400 VALIDATION_FAILED", _t2_f6_01),
        ("T2.F6.02", "F6", "BASELINE", "501-char comment body -> 400 VALIDATION_FAILED", _t2_f6_02),
        ("T2.F6.03", "F6", "BASELINE", "Comment on unknown post -> 404 POST_NOT_FOUND", _t2_f6_03),
        ("T2.F6.04", "F6", "BASELINE", "Post author cannot delete commenter's comment -> 403", _t2_f6_04),
        ("T2.F6.05", "F6", "M1", "PATCH /comments/{id} blank/oversize 400, non-author 403, unknown 404", _t2_f6_05),
        ("T2.F7.01", "F7", "BASELINE", "Report unknown post -> 404 POST_NOT_FOUND", _t2_f7_01),
        ("T2.F7.02", "F7", "BASELINE", "Report unknown comment -> 404 COMMENT_NOT_FOUND", _t2_f7_02),
        ("T2.F7.03", "F7", "BASELINE", "Invalid report reason -> 400 VALIDATION_FAILED", _t2_f7_03),
        ("T2.F7.04", "F7", "BASELINE", "Missing report reason -> 400 VALIDATION_FAILED", _t2_f7_04),
        ("T2.F7.05", "F7", "BASELINE", "501-char report details -> 400 VALIDATION_FAILED", _t2_f7_05),
        ("T2.F8.01", "F8", "M1", "GET /authors/{unknown} -> 404 USER_NOT_FOUND", _t2_f8_01),
        ("T2.F8.02", "F8", "M1", "GET /authors/not-a-uuid -> 400", _t2_f8_02),
        ("T2.F8.03", "F8", "M1", "GET /posts?authorId=not-a-uuid -> 400", _t2_f8_03),
        ("T2.F8.04", "F8", "M1", "GET /posts?authorId=<unknown> -> 200 empty page", _t2_f8_04),
        ("T2.F8.05", "F8", "M1", "GET /authors/<blocked> -> 404 USER_NOT_FOUND", _t2_f8_05),
        ("T2.F9.01", "F9", "M1", "Blank whitespace q treated safely -> 200", _t2_f9_01),
        ("T2.F9.02", "F9", "M1", "q=100 accepted; q=101 rejected 400 VALIDATION_FAILED", _t2_f9_02),
        ("T2.F9.03", "F9", "M1", "SQL LIKE wildcards (%%%) in q escaped literally", _t2_f9_03),
        ("T2.F9.04", "F9", "M1", "SQL injection string in q safely returns empty page", _t2_f9_04),
        ("T2.F9.05", "F9", "BASELINE", "Malformed feed cursor -> 400 VALIDATION_FAILED", _t2_f9_05),
        ("T2.F10.01", "F10", "BASELINE", "Self-block rejected 400 VALIDATION_FAILED", _t2_f10_01),
        ("T2.F10.02", "F10", "BASELINE", "Block unknown user -> 404 USER_NOT_FOUND", _t2_f10_02),
        ("T2.F10.03", "F10", "BASELINE", "Blocked user denied video bytes & comment creation", _t2_f10_03),
        ("T2.F10.04", "F10", "M1", "Blocked author excluded from keyword search results", _t2_f10_04),
        ("T2.F10.05", "F10", "BASELINE", "Unblock restores visibility immediately", _t2_f10_05),
        ("T2.F11.01", "F11", "M1", "Delete comment under deleted parent -> 404 (not 403)", _t2_f11_01),
        ("T2.F11.02", "F11", "M1", "Delete comment under hidden parent -> 404 (not 403)", _t2_f11_02),
        ("T2.F11.03", "F11", "M1", "Owner denied 404 on image of deleted post", _t2_f11_03),
        ("T2.F11.04", "F11", "M1", "Owner denied 404 on video of hidden post", _t2_f11_04),
        ("T2.F11.05", "F11", "M1", "Unbound image remains readable by owner", _t2_f11_05),
        ("T2.F12.01", "F12", "BASELINE", "Anon 401 & USER 403 on /admin/social/settings", _t2_f12_01),
        ("T2.F12.02", "F12", "BASELINE", "Stale expectedRevision returns 409 SOCIAL_SETTINGS_CONFLICT", _t2_f12_02),
        ("T2.F12.03", "F12", "BASELINE", "Blank draft field rejected 400", _t2_f12_03),
        ("T2.F12.04", "F12", "BASELINE", "Oversize draft title (>100) & body (>500) rejected 400", _t2_f12_04),
        ("T2.F12.05", "F12", "BASELINE", "MODERATOR denied 403 NOT_ADMIN on all 5 /admin/social/settings routes", _t2_f12_05),
        ("T2.F13.01", "F13", "BASELINE", "Plain USER denied 403 on all /admin/feed/** routes", _t2_f13_01),
        ("T2.F13.02", "F13", "BASELINE", "Hide post missing/blank/oversize reason -> 400", _t2_f13_02),
        ("T2.F13.03", "F13", "BASELINE", "Hide unknown post/comment -> 404", _t2_f13_03),
        ("T2.F13.04", "F13", "BASELINE", "Resolve to OPEN -> 400; unknown report -> 404", _t2_f13_04),
        ("T2.F13.05", "F13", "BASELINE", "Reported video preview on deleted post -> 404 FILE_NOT_FOUND", _t2_f13_05),
        ("T2.F14.01", "F14", "BASELINE", "Proxy blocks wildcard /api/social/v1/admin/* route", _t2_f14_01),
        ("T2.F14.02", "F14", "BASELINE", "Proxy blocks static path traversal", _t2_f14_02),
        ("T2.F14.03", "F14", "BASELINE", "Cross-user image bind rejected 400", _t2_f14_03),
        ("T2.F14.04", "F14", "M1", "Video rebind error message avoids imageId wording", _t2_f14_04),
        ("T2.F14.05", "F14", "BASELINE", "MP4 uploaded to /images rejected 415 MIME_NOT_ALLOWED", _t2_f14_05),
        ("T2.F15.01", "F15", "BASELINE", "Post 3/min drain triggers 429 RATE_LIMITED with Retry-After", _t2_f15_01),
        ("T2.F15.02", "F15", "BASELINE", "Invalid post payloads after refill do not burn quota", _t2_f15_02),
        ("T2.F15.03", "F15", "M1", "Dead clientPostId replay returns 404 without burning quota", _t2_f15_03),
        ("T2.F15.04", "F15", "BASELINE", "12 invalid comments do not burn 10/min comment bucket", _t2_f15_04),
        ("T2.F15.05", "F15", "BASELINE", "UTF-8, quotes & HTML metacharacters preserved verbatim", _t2_f15_05),
        ("T2.F16.01", "F16", "BASELINE", "Unauthenticated /api/v1/feed/posts -> 401", _t2_f16_01),
        ("T2.F16.02", "F16", "BASELINE", "Malformed UUID on /api/v1/feed/posts/{id} -> 400", _t2_f16_02),
        ("T2.F16.03", "F16", "BASELINE", "Oversize body on /api/v1/feed/posts -> 400", _t2_f16_03),
        ("T2.F16.04", "F16", "BASELINE", "Oversize MP4 on /api/v1/feed/videos while web off -> 413", _t2_f16_04),
        ("T2.F16.05", "F16", "BASELINE", "videoUploadsEnabled=false blocks /api/v1/feed/videos while reads stay 200", _t2_f16_05),
    ]
    return [CheckCase(cid, feat, 2, gate, title, fn) for cid, feat, gate, title, fn in raw]
