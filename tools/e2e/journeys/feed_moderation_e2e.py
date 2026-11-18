#!/usr/bin/env python3
"""Live E2E journey: public feed, abuse reports and staff feed moderation.

Covers /api/v1/feed/** (posts, images, comments, reactions, blocks
visibility, rate limit), POST /api/v1/reports for POST/COMMENT targets and
/api/v1/admin/feed/** plus the /api/v1/admin/audit trail.

Fixture accounts: +84900000501..+84900000505 and the configured admin phone
+84900000590, display names prefixed "Feed E2E". Content carries a per-run
nonce so the journey is re-runnable against the same database. Tokens are
never printed.

Usage:
    python tools/e2e/journeys/feed_moderation_e2e.py [--base-url http://127.0.0.1:8091]
    python tools/e2e/journeys/feed_moderation_e2e.py --list
    python tools/e2e/journeys/feed_moderation_e2e.py --only reactions,admin_moderation

Contract: docs/architecture/public-feed.md, FeedFlowIT, FeedAdminFlowIT.
"""

from __future__ import annotations

import argparse
import sys
import time
import urllib.parse
import uuid
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from e2e_common import (  # noqa: E402
    Client,
    E2EFailure,
    Response,
    check,
    ensure_user,
    main_guard,
)

DEFAULT_BASE_URL = "http://127.0.0.1:8091"
TAG = "Feed E2E"

USERS = {
    "author": ("+84900000501", TAG + " Author"),
    "reader": ("+84900000502", TAG + " Reader"),
    "blocker": ("+84900000503", TAG + " Blocker"),
    "reactor": ("+84900000504", TAG + " Reactor"),
    "rate": ("+84900000505", TAG + " RateLimit"),
    "admin": ("+84900000590", TAG + " Admin"),
}

# Smallest PNG prefix the server's magic-byte sniffer accepts (same bytes as FeedFlowIT).
PNG_BYTES = bytes.fromhex(
    "89504E470D0A1A0A" "0000000D4948445200000010000000100806000000"
)
NOT_AN_IMAGE = b"this is plain text pretending to be a png"

DISTINCT_EMOJIS = ["\U0001F44D", "\u2764\uFE0F", "\U0001F602", "\U0001F62E",
                   "\U0001F622", "\U0001F525", "\U0001F44F", "\U0001F389"]
NINTH_EMOJI = "\U0001F92F"
MAX_POST_BODY = 1000
MAX_COMMENT_BODY = 500
POSTS_PER_MINUTE = 3


class NotRun(Exception):
    """A stack capability the scenario needs is absent."""


# --------------------------------------------------------------- pure helpers


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="NexaChat feed + moderation live journey")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--list", action="store_true", help="print scenario names and exit")
    parser.add_argument("--only", default=None, help="comma-separated scenario names")
    return parser.parse_args(argv)


def select_scenarios(names: List[str], only: Optional[str]) -> List[str]:
    """Keeps declaration order; raises ValueError on an unknown name."""
    if not only:
        return list(names)
    wanted = [item.strip() for item in only.split(",") if item.strip()]
    unknown = [item for item in wanted if item not in names]
    if unknown:
        raise ValueError("unknown scenario(s): " + ", ".join(unknown))
    return [name for name in names if name in wanted]


def multipart_body(field: str, filename: str, content_type: str, data: bytes,
                   fields: Optional[Dict[str, str]] = None,
                   boundary: Optional[str] = None) -> Tuple[bytes, str]:
    """Builds a multipart/form-data body; returns (bytes, content-type header)."""
    boundary = boundary or ("feede2e" + uuid.uuid4().hex)
    parts: List[bytes] = []
    for name, value in (fields or {}).items():
        parts.append(
            ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n"
             % (boundary, name, value)).encode("utf-8"))
    parts.append(
        ("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
         "Content-Type: %s\r\n\r\n" % (boundary, field, filename, content_type)).encode("utf-8"))
    parts.append(data)
    parts.append(("\r\n--%s--\r\n" % boundary).encode("utf-8"))
    return b"".join(parts), "multipart/form-data; boundary=" + boundary


def post_payload(body: Optional[str], client_post_id: Optional[str] = None,
                 image_id: Optional[str] = None) -> dict:
    payload: dict = {"clientPostId": client_post_id or str(uuid.uuid4())}
    if body is not None:
        payload["body"] = body
    if image_id is not None:
        payload["imageId"] = image_id
    return payload


def comment_payload(body: str, client_comment_id: Optional[str] = None) -> dict:
    return {"body": body, "clientCommentId": client_comment_id or str(uuid.uuid4())}


def report_payload(target_type: str, target_id: str, reason: str,
                   details: Optional[str] = None) -> dict:
    payload = {"targetType": target_type, "targetId": target_id, "reason": reason}
    if details is not None:
        payload["details"] = details
    return payload


def reaction_entry(reactions: List[dict], emoji: str) -> Optional[dict]:
    for item in reactions or []:
        if item.get("emoji") == emoji:
            return item
    return None


def reaction_path(post_id: str, emoji: str) -> str:
    """Emoji path segment, percent-encoded as UTF-8 (urllib refuses raw non-ASCII)."""
    return "/api/v1/feed/posts/%s/reactions/%s" % (post_id, urllib.parse.quote(emoji, safe=""))


def ids_of(items: List[dict]) -> List[str]:
    return [str(item.get("id")) for item in items or []]


def retry_after_seconds(response: Response, default: int = 20) -> int:
    """Seconds to wait from a RATE_LIMITED envelope or Retry-After header."""
    try:
        data = response.json() or {}
    except ValueError:
        data = {}
    details = (data.get("error") or {}).get("details") or {} if isinstance(data, dict) else {}
    value = details.get("retryAfterSeconds")
    if value is None:
        for key, header in (response.headers or {}).items():
            if key.lower() == "retry-after":
                value = header
                break
    try:
        return max(1, int(value))
    except (TypeError, ValueError):
        return default


def expect_error(response: Response, status: int, code: str, what: str) -> None:
    check(response.status == status,
          "%s: expected HTTP %d %s, got %r" % (what, status, code, response))
    check(response.error_code() == code,
          "%s: expected error %s, got %s" % (what, code, response.error_code()))


def expect_status(response: Response, statuses, what: str) -> None:
    if isinstance(statuses, int):
        statuses = (statuses,)
    check(response.status in statuses,
          "%s: expected HTTP %s, got %r" % (what, "/".join(map(str, statuses)), response))


def assert_author_public(author: dict) -> None:
    """The feed exposes only id, displayName and avatarUrl for an author."""
    check(isinstance(author, dict), "post author missing")
    check(set(author.keys()) <= {"id", "displayName", "avatarUrl"},
          "author leaks extra fields: %s" % sorted(author.keys()))
    check(bool(author.get("id")), "author id missing")


# --------------------------------------------------------------- live context


class Ctx:
    def __init__(self, client: Client):
        self.client = client
        self.nonce = uuid.uuid4().hex[:8]
        self._sessions: Dict[str, dict] = {}
        self._shared_post: Optional[dict] = None

    def session(self, role: str) -> dict:
        if role not in self._sessions:
            phone, name = USERS[role]
            data = ensure_user(self.client, phone, name)
            self._sessions[role] = {"token": data["accessToken"], "id": data["user"]["id"]}
        return self._sessions[role]

    def token(self, role: str) -> str:
        return self.session(role)["token"]

    def uid(self, role: str) -> str:
        return self.session(role)["id"]

    def text(self, label: str) -> str:
        return "%s %s %s" % (TAG, label, self.nonce)

    # -- feed calls
    def create_post(self, role: str, payload: dict, expect_created: bool = True) -> Response:
        """POST /feed/posts, waiting out the 3/min bucket from an earlier run."""
        response = None
        for _ in range(4):
            response = self.client.post("/api/v1/feed/posts", token=self.token(role), body=payload)
            if response.status == 429 and response.error_code() == "RATE_LIMITED":
                wait = min(65, retry_after_seconds(response) + 1)
                print("[wait] post bucket for %s exhausted, retrying in %ds" % (role, wait))
                time.sleep(wait)
                continue
            break
        if expect_created:
            expect_status(response, 201, "create post as " + role)
        return response

    def new_post(self, role: str, label: str) -> dict:
        return self.create_post(role, post_payload(self.text(label))).json()

    def shared_post(self) -> dict:
        if self._shared_post is None:
            self._shared_post = self.new_post("author", "shared post")
        return self._shared_post

    def get_post(self, role: str, post_id: str) -> Response:
        return self.client.get("/api/v1/feed/posts/" + post_id, token=self.token(role))

    def list_ids(self, role: str, limit: int = 50) -> List[str]:
        response = self.client.get("/api/v1/feed/posts", token=self.token(role),
                                   params={"limit": limit})
        expect_status(response, 200, "list feed as " + role)
        return ids_of(response.json().get("items"))

    def comment(self, role: str, post_id: str, payload: dict) -> Response:
        return self.client.post("/api/v1/feed/posts/%s/comments" % post_id,
                                token=self.token(role), body=payload)

    def comment_ids(self, role: str, post_id: str) -> List[str]:
        response = self.client.get("/api/v1/feed/posts/%s/comments" % post_id,
                                   token=self.token(role), params={"limit": 50})
        expect_status(response, 200, "list comments as " + role)
        return ids_of(response.json().get("items"))

    def comment_count(self, role: str, post_id: str) -> int:
        response = self.get_post(role, post_id)
        expect_status(response, 200, "get post for commentCount")
        return int(response.json().get("commentCount"))

    def react(self, role: str, post_id: str, emoji: str, add: bool = True) -> Response:
        path = reaction_path(post_id, emoji)
        if add:
            return self.client.put(path, token=self.token(role))
        return self.client.delete(path, token=self.token(role))

    def upload(self, role: str, data: bytes, filename: str = "pixel.png",
               content_type: str = "image/png",
               fields: Optional[Dict[str, str]] = None) -> Response:
        raw = multipart_body("file", filename, content_type, data, fields=fields)
        return self.client.request("POST", "/api/v1/feed/images", token=self.token(role),
                                   raw_body=raw)

    def fetch_image(self, role: str, path: str) -> Response:
        return self.client.get(path, token=self.token(role), headers={"Accept": "*/*"})

    def report(self, role: str, payload: dict) -> Response:
        return self.client.post("/api/v1/reports", token=self.token(role), body=payload)

    def block(self, role: str, target_role: str, on: bool = True) -> Response:
        path = "/api/v1/users/%s/block" % self.uid(target_role)
        if on:
            return self.client.post(path, token=self.token(role))
        return self.client.delete(path, token=self.token(role))

    def require_admin(self) -> str:
        token = self.token("admin")
        me = self.client.get("/api/v1/admin/me", token=token)
        if me.status != 200 or (me.json() or {}).get("role") not in ("ADMIN", "MODERATOR"):
            raise NotRun("admin phone %s is not staff on this stack (GET /admin/me -> %d)"
                         % (USERS["admin"][0], me.status))
        return token


# ------------------------------------------------------------------ scenarios


def scenario_create_text_post(ctx: Ctx) -> None:
    body = ctx.text("shared post")
    payload = post_payload(body)
    created = ctx.create_post("author", payload)
    post = created.json()
    ctx._shared_post = post
    check(post.get("body") == body, "created post body mismatch")
    check(post.get("mine") is True, "author's own post not mine=true")
    check(post.get("commentCount") == 0, "new post commentCount != 0")
    check(post.get("reactions") == [], "new post reactions not []")
    check(post.get("image") is None, "text post carries an image")
    assert_author_public(post.get("author"))
    check(post["author"]["id"] == ctx.uid("author"), "author id mismatch")
    check(post["author"]["displayName"] == USERS["author"][1], "author displayName mismatch")

    check(post["id"] in ctx.list_ids("reader"), "new post missing from reader's feed page")
    read = ctx.get_post("reader", post["id"])
    expect_status(read, 200, "reader get post")
    check(read.json().get("body") == body and read.json().get("mine") is False,
          "reader view body/mine mismatch")

    for label, bad in (
        ("blank body without image", post_payload("   ")),
        ("missing body and image", post_payload(None)),
        ("oversize body", post_payload("x" * (MAX_POST_BODY + 1))),
        ("missing clientPostId", {"body": ctx.text("no client id")}),
    ):
        response = ctx.client.post("/api/v1/feed/posts", token=ctx.token("author"), body=bad)
        expect_error(response, 400, "VALIDATION_FAILED", label)

    expect_error(ctx.get_post("reader", str(uuid.uuid4())), 404, "POST_NOT_FOUND",
                 "get unknown post")
    bad_cursor = ctx.client.get("/api/v1/feed/posts", token=ctx.token("reader"),
                                params={"cursor": "not-a-cursor"})
    expect_error(bad_cursor, 400, "VALIDATION_FAILED", "malformed cursor")
    anonymous = ctx.client.get("/api/v1/feed/posts")
    expect_error(anonymous, 401, "UNAUTHORIZED", "anonymous feed list")


def scenario_image_post(ctx: Ctx) -> None:
    upload = ctx.upload("author", PNG_BYTES, fields={"width": "16", "height": "16"})
    expect_status(upload, 201, "upload PNG")
    image = upload.json()
    image_id = image.get("imageId")
    check(bool(image_id), "upload returned no imageId")
    check(image.get("mimeType") == "image/png", "mimeType %s" % image.get("mimeType"))
    check(image.get("url") == "/api/v1/feed/images/" + image_id, "image url mismatch")
    check(image.get("width") == 16 and image.get("height") == 16, "dimensions not echoed")

    own = ctx.fetch_image("author", image["url"])
    expect_status(own, 200, "owner fetches unbound image")
    check(own.body == PNG_BYTES, "served bytes differ from upload")
    expect_error(ctx.fetch_image("reader", image["url"]), 404, "FILE_NOT_FOUND",
                 "other user fetches unbound image")

    stolen = ctx.client.post("/api/v1/feed/posts", token=ctx.token("reader"),
                             body=post_payload(ctx.text("stolen image"), image_id=image_id))
    expect_error(stolen, 400, "VALIDATION_FAILED", "bind another user's image")

    post = ctx.create_post("author", post_payload(None, image_id=image_id)).json()
    check(post.get("body") is None, "image-only post has a body")
    check((post.get("image") or {}).get("url") == image["url"], "post image url mismatch")
    check(post["image"].get("mimeType") == "image/png", "post image mimeType mismatch")

    served = ctx.fetch_image("reader", image["url"])
    expect_status(served, 200, "reader fetches bound image")
    content_type = {k.lower(): v for k, v in served.headers.items()}.get("content-type", "")
    check(content_type.startswith("image/png"), "served content-type %s" % content_type)
    check({k.lower(): v for k, v in served.headers.items()}.get("x-content-type-options")
          == "nosniff", "image missing nosniff header")

    rebind = ctx.client.post("/api/v1/feed/posts", token=ctx.token("author"),
                             body=post_payload(ctx.text("rebind"), image_id=image_id))
    expect_error(rebind, 400, "VALIDATION_FAILED", "bind an already-attached image")

    expect_error(ctx.upload("author", NOT_AN_IMAGE), 415, "MIME_NOT_ALLOWED",
                 "upload non-image bytes labelled image/png")
    expect_error(ctx.upload("author", PNG_BYTES, fields={"width": "20000", "height": "4"}),
                 400, "VALIDATION_FAILED", "absurd width")
    expect_error(ctx.fetch_image("reader", "/api/v1/feed/images/" + str(uuid.uuid4())),
                 404, "FILE_NOT_FOUND", "unknown image")

    # Author delete takes the image with the post.
    expect_status(ctx.client.delete("/api/v1/feed/posts/" + post["id"],
                                    token=ctx.token("author")), 204, "delete image post")
    expect_error(ctx.fetch_image("reader", image["url"]), 404, "FILE_NOT_FOUND",
                 "image of deleted post")


def scenario_comments(ctx: Ctx) -> None:
    post_id = ctx.shared_post()["id"]
    before = ctx.comment_count("author", post_id)

    client_id = str(uuid.uuid4())
    body = ctx.text("reader comment")
    created = ctx.comment("reader", post_id, comment_payload(body, client_id))
    expect_status(created, 201, "reader comments")
    comment = created.json()
    check(comment.get("body") == body and comment.get("postId") == post_id,
          "comment body/postId mismatch")
    check(comment.get("mine") is True, "commenter's comment not mine")
    assert_author_public(comment.get("author"))
    check(ctx.comment_count("author", post_id) == before + 1, "commentCount did not increment")

    replay = ctx.comment("reader", post_id, comment_payload(body, client_id))
    expect_status(replay, 200, "comment clientCommentId replay")
    check(replay.json().get("id") == comment["id"], "replay returned a different comment")
    check(ctx.comment_count("author", post_id) == before + 1, "replay changed commentCount")

    listed = ctx.client.get("/api/v1/feed/posts/%s/comments" % post_id,
                            token=ctx.token("author"), params={"limit": 50})
    expect_status(listed, 200, "author lists comments")
    mine_flags = {item["id"]: item.get("mine") for item in listed.json().get("items", [])}
    check(mine_flags.get(comment["id"]) is False, "reader's comment listed as mine for author")

    expect_error(ctx.comment("reader", post_id, comment_payload("   ")), 400,
                 "VALIDATION_FAILED", "blank comment")
    expect_error(ctx.comment("reader", post_id, comment_payload("y" * (MAX_COMMENT_BODY + 1))),
                 400, "VALIDATION_FAILED", "oversize comment")
    expect_error(ctx.comment("reader", str(uuid.uuid4()), comment_payload(body)), 404,
                 "POST_NOT_FOUND", "comment on unknown post")

    # The post author is not the comment author: delete is FORBIDDEN.
    denied = ctx.client.delete("/api/v1/feed/comments/" + comment["id"],
                               token=ctx.token("author"))
    expect_error(denied, 403, "FORBIDDEN", "non-author deletes comment")
    check(ctx.comment_count("author", post_id) == before + 1, "denied delete moved commentCount")

    own = ctx.client.delete("/api/v1/feed/comments/" + comment["id"], token=ctx.token("reader"))
    expect_status(own, 204, "author deletes own comment")
    check(ctx.comment_count("author", post_id) == before, "commentCount did not decrement")
    check(comment["id"] not in ctx.comment_ids("author", post_id), "deleted comment still listed")
    again = ctx.client.delete("/api/v1/feed/comments/" + comment["id"], token=ctx.token("reader"))
    expect_error(again, 404, "COMMENT_NOT_FOUND", "delete already-deleted comment")


def scenario_reactions(ctx: Ctx) -> None:
    post_id = ctx.shared_post()["id"]
    thumbs = DISTINCT_EMOJIS[0]
    ctx.react("reactor", post_id, thumbs, add=False)  # clean slate on the shared post
    ctx.react("reader", post_id, thumbs, add=False)

    first = ctx.react("reactor", post_id, thumbs)
    expect_status(first, 200, "add reaction")
    entry = reaction_entry(first.json().get("reactions"), thumbs)
    check(entry is not None and entry["count"] == 1 and entry["mine"] is True,
          "after add expected count=1 mine=true, got %s" % entry)

    replay = ctx.react("reactor", post_id, thumbs)
    expect_status(replay, 200, "replay add reaction")
    entry = reaction_entry(replay.json().get("reactions"), thumbs)
    check(entry is not None and entry["count"] == 1, "replay changed the count: %s" % entry)

    other = ctx.react("reader", post_id, thumbs)
    entry = reaction_entry(other.json().get("reactions"), thumbs)
    check(entry is not None and entry["count"] == 2 and entry["mine"] is True,
          "second user expected count=2 mine=true, got %s" % entry)

    removed = ctx.react("reactor", post_id, thumbs, add=False)
    expect_status(removed, 200, "remove reaction")
    entry = reaction_entry(removed.json().get("reactions"), thumbs)
    check(entry is not None and entry["count"] == 1 and entry["mine"] is False,
          "after remove expected count=1 mine=false, got %s" % entry)
    removed_again = ctx.react("reactor", post_id, thumbs, add=False)
    expect_status(removed_again, 200, "replay remove reaction")
    entry = reaction_entry(removed_again.json().get("reactions"), thumbs)
    check(entry is not None and entry["count"] == 1, "replayed remove changed count: %s" % entry)

    read = ctx.get_post("author", post_id).json()
    entry = reaction_entry(read.get("reactions"), thumbs)
    check(entry is not None and entry["count"] == 1 and entry["mine"] is False,
          "GET post reaction aggregate mismatch: %s" % entry)

    expect_error(ctx.react("reactor", post_id, "x" * 17), 400, "VALIDATION_FAILED",
                 "17-character emoji")
    expect_error(ctx.react("reactor", str(uuid.uuid4()), thumbs), 404, "POST_NOT_FOUND",
                 "react on unknown post")

    # Distinct-emoji cap on a fresh post (8 per user per post).
    cap_post = ctx.new_post("blocker", "reaction cap post")["id"]
    for emoji in DISTINCT_EMOJIS:
        expect_status(ctx.react("reactor", cap_post, emoji), 200, "distinct emoji " + emoji)
    expect_status(ctx.react("reactor", cap_post, thumbs), 200, "re-add placed emoji under cap")
    # A cap rejection must not consume the 60/min reaction bucket: 70 rapid
    # rejections would exhaust it (and turn into 429) if they did.
    for attempt in range(70):
        rejected = ctx.react("reactor", cap_post, NINTH_EMOJI)
        expect_error(rejected, 400, "VALIDATION_FAILED",
                     "9th distinct emoji (attempt %d)" % (attempt + 1))
    after = ctx.react("reactor", cap_post, DISTINCT_EMOJIS[1], add=False)
    expect_status(after, 200, "reaction still allowed after cap rejections")
    final = ctx.react("reactor", cap_post, NINTH_EMOJI)
    expect_status(final, 200, "9th emoji fits once one was removed")


def scenario_delete_post(ctx: Ctx) -> None:
    client_id = str(uuid.uuid4())
    payload = post_payload(ctx.text("to be deleted"), client_post_id=client_id)
    post = ctx.create_post("reader", payload).json()
    replay = ctx.create_post("reader", payload, expect_created=False)
    expect_status(replay, 200, "clientPostId replay")
    check(replay.json().get("id") == post["id"], "replay created a second post")

    check(post["id"] in ctx.list_ids("author"), "post missing from other user's feed")
    denied = ctx.client.delete("/api/v1/feed/posts/" + post["id"], token=ctx.token("author"))
    expect_error(denied, 403, "FORBIDDEN", "non-author deletes post")

    expect_status(ctx.client.delete("/api/v1/feed/posts/" + post["id"],
                                    token=ctx.token("reader")), 204, "author deletes post")
    expect_error(ctx.get_post("author", post["id"]), 404, "POST_NOT_FOUND",
                 "deleted post for others")
    expect_error(ctx.get_post("reader", post["id"]), 404, "POST_NOT_FOUND",
                 "deleted post for its author")
    check(post["id"] not in ctx.list_ids("author"), "deleted post still in feed")
    expect_error(ctx.comment("author", post["id"], comment_payload(ctx.text("late"))), 404,
                 "POST_NOT_FOUND", "comment on deleted post")
    expect_error(ctx.report("author", report_payload("POST", post["id"], "SPAM")), 404,
                 "POST_NOT_FOUND", "report deleted post")
    again = ctx.client.delete("/api/v1/feed/posts/" + post["id"], token=ctx.token("reader"))
    expect_error(again, 404, "POST_NOT_FOUND", "delete already-deleted post")


def scenario_block_visibility(ctx: Ctx) -> None:
    ctx.block("reader", "blocker", on=False)  # idempotent cleanup of an aborted run
    post = ctx.new_post("blocker", "blocked author post")
    comment = ctx.comment("blocker", post["id"], comment_payload(ctx.text("blocked comment")))
    expect_status(comment, 201, "blocker comments own post")
    comment_id = comment.json()["id"]
    check(post["id"] in ctx.list_ids("reader"), "post not visible before block")
    try:
        expect_status(ctx.block("reader", "blocker"), 204, "reader blocks author")
        expect_error(ctx.get_post("reader", post["id"]), 404, "POST_NOT_FOUND",
                     "blocker views blocked author's post")
        check(post["id"] not in ctx.list_ids("reader"), "blocked author's post still listed")
        expect_error(ctx.react("reader", post["id"], DISTINCT_EMOJIS[0]), 404,
                     "POST_NOT_FOUND", "react to blocked author's post")
        expect_error(ctx.report("reader", report_payload("COMMENT", comment_id, "SPAM")), 404,
                     "COMMENT_NOT_FOUND", "report comment under blocked author's post")
        # The edge is checked both ways: the blocked author loses the blocker's posts too.
        reader_post = ctx.new_post("reader", "blocker-side post")
        expect_error(ctx.get_post("blocker", reader_post["id"]), 404, "POST_NOT_FOUND",
                     "blocked author views blocker's post")
        expect_status(ctx.client.delete("/api/v1/feed/posts/" + reader_post["id"],
                                        token=ctx.token("reader")), 204, "cleanup reader post")
        # Third parties are unaffected.
        expect_status(ctx.get_post("author", post["id"]), 200, "third party still sees post")
        reverse = ctx.client.get("/api/v1/feed/posts/%s/comments" % post["id"],
                                 token=ctx.token("reader"))
        expect_error(reverse, 404, "POST_NOT_FOUND", "list comments of blocked author's post")
    finally:
        ctx.block("reader", "blocker", on=False)
    expect_status(ctx.get_post("reader", post["id"]), 200, "post visible again after unblock")
    expect_error(ctx.client.post("/api/v1/users/%s/block" % ctx.uid("reader"),
                                 token=ctx.token("reader")), 400, "VALIDATION_FAILED",
                 "self-block")


def scenario_reports(ctx: Ctx) -> None:
    post = ctx.shared_post()
    created = ctx.report("reader", report_payload("POST", post["id"], "HARASSMENT",
                                                  details=ctx.text("report details")))
    expect_status(created, 201, "report post")
    report = created.json()
    check(report.get("targetType") == "POST" and report.get("targetId") == post["id"],
          "report target mismatch")
    check(report.get("status") == "OPEN", "new report not OPEN")
    check(report.get("reason") == "HARASSMENT", "report reason mismatch")
    check(report.get("messageSnapshot") == post["body"], "post snapshot is not the post body")

    duplicate = ctx.report("reader", report_payload("POST", post["id"], "SPAM"))
    expect_status(duplicate, 200, "duplicate open report")
    check(duplicate.json().get("id") == report["id"], "duplicate report created a new row")

    comment = ctx.comment("author", post["id"], comment_payload(ctx.text("reported comment")))
    expect_status(comment, 201, "author comments for report")
    comment = comment.json()
    comment_report = ctx.report("reader", report_payload("COMMENT", comment["id"], "SPAM"))
    expect_status(comment_report, 201, "report comment")
    check(comment_report.json().get("messageSnapshot") == comment["body"],
          "comment snapshot is not the comment body")

    mine = ctx.client.get("/api/v1/reports/mine", token=ctx.token("reader"),
                          params={"limit": 20})
    expect_status(mine, 200, "list my reports")
    mine_ids = ids_of(mine.json().get("items"))
    check(report["id"] in mine_ids and comment_report.json()["id"] in mine_ids,
          "filed reports missing from /reports/mine")

    # Negative paths use another account: every submit that reaches the
    # service spends the reporter's 20/hour bucket (rate limit runs first).
    expect_error(ctx.report("reactor", report_payload("POST", str(uuid.uuid4()), "SPAM")),
                 404, "POST_NOT_FOUND", "report unknown post")
    expect_error(ctx.report("reactor", report_payload("COMMENT", str(uuid.uuid4()), "SPAM")),
                 404, "COMMENT_NOT_FOUND", "report unknown comment")
    expect_error(ctx.report("reactor", {"targetType": "POST", "targetId": post["id"],
                                        "reason": "NOT_A_REASON"}),
                 400, "VALIDATION_FAILED", "unknown report reason")
    expect_error(ctx.report("reactor", {"targetType": "POST", "targetId": post["id"]}),
                 400, "VALIDATION_FAILED", "missing report reason")


def _find_report(ctx: Ctx, token: str, status: str, report_id: str) -> Optional[dict]:
    page = ctx.client.get("/api/v1/admin/feed/reports", token=token,
                          params={"status": status, "size": 100})
    expect_status(page, 200, "admin lists %s reports" % status)
    data = page.json()
    for key in ("items", "page", "size", "total", "hasMore"):
        check(key in data, "admin report page missing %s" % key)
    for item in data["items"]:
        if item.get("id") == report_id:
            return item
    return None


def scenario_admin_moderation(ctx: Ctx) -> None:
    admin = ctx.require_admin()
    post = ctx.new_post("author", "moderated post")
    post_id = post["id"]
    comment = ctx.comment("author", post_id, comment_payload(ctx.text("moderated comment")))
    expect_status(comment, 201, "comment for moderation")
    comment_id = comment.json()["id"]
    post_report = ctx.report("blocker", report_payload("POST", post_id, "SPAM",
                                                       details=ctx.text("admin queue")))
    expect_status(post_report, 201, "report post for admin")
    post_report_id = post_report.json()["id"]
    comment_report = ctx.report("blocker", report_payload("COMMENT", comment_id, "OTHER"))
    expect_status(comment_report, 201, "report comment for admin")
    comment_report_id = comment_report.json()["id"]

    # Non-staff is refused on every admin feed route.
    plain = ctx.token("reader")
    for label, response in (
        ("list reports", ctx.client.get("/api/v1/admin/feed/reports", token=plain)),
        ("hide post", ctx.client.post("/api/v1/admin/feed/posts/%s/hide" % post_id, token=plain,
                                      body={"reason": "x"})),
        ("hide comment", ctx.client.post("/api/v1/admin/feed/comments/%s/hide" % comment_id,
                                         token=plain, body={"reason": "x"})),
        ("resolve report", ctx.client.post(
            "/api/v1/admin/feed/reports/%s/resolve" % post_report_id, token=plain,
            body={"status": "DISMISSED"})),
        ("audit", ctx.client.get("/api/v1/admin/audit", token=plain)),
    ):
        expect_error(response, 403, "FORBIDDEN", "non-admin " + label)
    check(ctx.get_post("reader", post_id).status == 200, "non-admin hide had an effect")

    queued = _find_report(ctx, admin, "OPEN", post_report_id)
    check(queued is not None, "post report not in admin OPEN queue")
    check(queued.get("targetType") == "POST" and queued.get("targetId") == post_id,
          "queue target mismatch")
    check(queued.get("reporterId") == ctx.uid("blocker"), "queue reporterId mismatch")
    check(queued.get("authorId") == ctx.uid("author"), "queue authorId mismatch")
    check(queued.get("targetBody") == post["body"], "queue targetBody mismatch")
    check(queued.get("targetHidden") is False and queued.get("targetDeleted") is False,
          "queue target state mismatch before hide")
    check("phone" not in queued and "reporterPhone" not in queued, "queue leaks phone")
    expect_error(ctx.client.get("/api/v1/admin/feed/reports", token=admin,
                                params={"status": "BOGUS"}),
                 400, "VALIDATION_FAILED", "unknown status filter")

    # Hide post: reason is mandatory.
    hide_path = "/api/v1/admin/feed/posts/%s/hide" % post_id
    expect_error(ctx.client.post(hide_path, token=admin, body={}), 400, "VALIDATION_FAILED",
                 "hide without reason")
    expect_error(ctx.client.post(hide_path, token=admin, body={"reason": "   "}), 400,
                 "VALIDATION_FAILED", "hide with blank reason")
    expect_error(ctx.client.post(hide_path, token=admin, body={"reason": "r" * 501}), 400,
                 "VALIDATION_FAILED", "hide with oversize reason")
    expect_error(ctx.client.post("/api/v1/admin/feed/posts/%s/hide" % uuid.uuid4(),
                                 token=admin, body={"reason": "x"}),
                 404, "POST_NOT_FOUND", "hide unknown post")
    reason = ctx.text("hide reason")
    expect_status(ctx.client.post(hide_path, token=admin, body={"reason": reason}), 204,
                  "admin hides post")
    expect_status(ctx.client.post(hide_path, token=admin, body={"reason": reason}), 204,
                  "admin hides post again (idempotent)")
    for role in ("reader", "author"):
        expect_error(ctx.get_post(role, post_id), 404, "POST_NOT_FOUND",
                     "hidden post for " + role)
    check(post_id not in ctx.list_ids("reader"), "hidden post still in feed")
    hidden_view = _find_report(ctx, admin, "OPEN", post_report_id)
    check(hidden_view is not None and hidden_view.get("targetHidden") is True,
          "queue does not show targetHidden after hide")

    audit = ctx.client.get("/api/v1/admin/audit", token=admin,
                           params={"action": "FEED_POST_HIDE", "targetId": post_id})
    expect_status(audit, 200, "admin reads audit")
    entries = audit.json().get("items") or []
    check(len(entries) == 1, "expected exactly one FEED_POST_HIDE audit row, got %d" % len(entries))
    check(entries[0].get("actorId") == ctx.uid("admin") and entries[0].get("reason") == reason
          and entries[0].get("targetType") == "POST", "audit row mismatch: %s" % entries[0])

    expect_status(ctx.client.post("/api/v1/admin/feed/posts/%s/unhide" % post_id, token=admin),
                  204, "admin unhides post")
    expect_status(ctx.get_post("reader", post_id), 200, "unhidden post visible")
    check(post_id in ctx.list_ids("reader"), "unhidden post missing from feed")

    # Comment hide/unhide moves commentCount.
    count = ctx.comment_count("reader", post_id)
    comment_hide = "/api/v1/admin/feed/comments/%s/hide" % comment_id
    expect_error(ctx.client.post(comment_hide, token=admin, body={}), 400, "VALIDATION_FAILED",
                 "hide comment without reason")
    expect_status(ctx.client.post(comment_hide, token=admin, body={"reason": reason}), 204,
                  "admin hides comment")
    expect_status(ctx.client.post(comment_hide, token=admin, body={"reason": reason}), 204,
                  "admin hides comment again")
    check(ctx.comment_count("reader", post_id) == count - 1,
          "commentCount not decremented once by hide")
    check(comment_id not in ctx.comment_ids("reader", post_id), "hidden comment still listed")
    expect_error(ctx.report("reactor", report_payload("COMMENT", comment_id, "SPAM")), 404,
                 "COMMENT_NOT_FOUND", "report hidden comment")
    expect_status(ctx.client.post("/api/v1/admin/feed/comments/%s/unhide" % comment_id,
                                  token=admin), 204, "admin unhides comment")
    expect_status(ctx.client.post("/api/v1/admin/feed/comments/%s/unhide" % comment_id,
                                  token=admin), 204, "admin unhides comment again")
    check(ctx.comment_count("reader", post_id) == count, "commentCount not restored by unhide")
    check(comment_id in ctx.comment_ids("reader", post_id), "unhidden comment not listed")
    expect_error(ctx.client.post("/api/v1/admin/feed/comments/%s/hide" % uuid.uuid4(),
                                 token=admin, body={"reason": "x"}),
                 404, "COMMENT_NOT_FOUND", "hide unknown comment")

    # Resolve reports.
    resolve = "/api/v1/admin/feed/reports/%s/resolve"
    expect_error(ctx.client.post(resolve % post_report_id, token=admin, body={"status": "OPEN"}),
                 400, "VALIDATION_FAILED", "resolve to OPEN")
    expect_error(ctx.client.post(resolve % uuid.uuid4(), token=admin,
                                 body={"status": "REVIEWED"}),
                 404, "NOT_FOUND", "resolve unknown report")
    expect_status(ctx.client.post(resolve % post_report_id, token=admin,
                                  body={"status": "REVIEWED"}), 204, "resolve REVIEWED")
    expect_status(ctx.client.post(resolve % comment_report_id, token=admin,
                                  body={"status": "DISMISSED"}), 204, "resolve DISMISSED")
    check(_find_report(ctx, admin, "OPEN", post_report_id) is None,
          "reviewed report still in OPEN queue")
    reviewed = _find_report(ctx, admin, "REVIEWED", post_report_id)
    check(reviewed is not None and reviewed.get("status") == "REVIEWED",
          "report not listed as REVIEWED")
    dismissed = _find_report(ctx, admin, "DISMISSED", comment_report_id)
    check(dismissed is not None and dismissed.get("targetType") == "COMMENT",
          "comment report not listed as DISMISSED")
    mine = ctx.client.get("/api/v1/reports/mine", token=ctx.token("blocker"),
                          params={"limit": 20}).json().get("items") or []
    statuses = {item["id"]: item.get("status") for item in mine}
    check(statuses.get(post_report_id) == "REVIEWED", "reporter does not see REVIEWED status")

    resolved_audit = ctx.client.get("/api/v1/admin/audit", token=admin,
                                    params={"action": "FEED_REPORT_RESOLVE",
                                            "targetId": comment_report_id}).json()
    rows = resolved_audit.get("items") or []
    check(len(rows) == 1 and (rows[0].get("detail") or {}).get("status") == "DISMISSED",
          "FEED_REPORT_RESOLVE audit row missing or wrong: %s" % rows)
    # Once closed, the reporter may file a fresh report on the same target.
    refile = ctx.report("blocker", report_payload("POST", post_id, "SPAM"))
    expect_status(refile, 201, "re-report after resolution")
    check(refile.json()["id"] != post_report_id, "re-report reused the closed row")
    expect_status(ctx.client.post(resolve % refile.json()["id"], token=admin,
                                  body={"status": "DISMISSED"}), 204, "dismiss re-report")


def scenario_post_rate_limit(ctx: Ctx) -> None:
    token = ctx.token("rate")

    def attempt(body: Optional[str]) -> Response:
        return ctx.client.post("/api/v1/feed/posts", token=token, body=post_payload(body))

    # 1. Drain the 3/min bucket (it may already be partly empty from an earlier run).
    denied = None
    for _ in range(POSTS_PER_MINUTE + 1):
        response = attempt(ctx.text("rate drain"))
        if response.status == 429:
            denied = response
            break
        expect_status(response, 201, "drain post")
    check(denied is not None, "4th post within a minute was not rate limited")
    expect_error(denied, 429, "RATE_LIMITED", "post over 3/min")
    headers = {k.lower(): v for k, v in denied.headers.items()}
    check("retry-after" in headers, "429 without Retry-After header")
    wait = retry_after_seconds(denied)
    check(1 <= wait <= 60, "retryAfterSeconds out of range: %d" % wait)

    # 2. An invalid post is rejected on validation even with an empty bucket.
    expect_error(attempt("   "), 400, "VALIDATION_FAILED", "invalid post with empty bucket")

    # 3. Wait for exactly one token, spend three invalid posts, then the
    #    valid one must still succeed: rejected posts do not burn quota.
    print("[wait] post bucket refill for rate account, %ds" % (wait + 1))
    time.sleep(wait + 1)
    for _ in range(3):
        expect_error(attempt(None), 400, "VALIDATION_FAILED", "invalid post after refill")
        expect_error(attempt("z" * (MAX_POST_BODY + 1)), 400, "VALIDATION_FAILED",
                     "oversize post after refill")
    expect_status(attempt(ctx.text("rate after refill")), 201,
                  "valid post after invalid ones (quota not burned)")
    expect_error(attempt(ctx.text("rate over again")), 429, "RATE_LIMITED",
                 "next post is limited again")


SCENARIOS: List[Tuple[str, Callable[[Ctx], None]]] = [
    ("create_text_post", scenario_create_text_post),
    ("image_post", scenario_image_post),
    ("comments", scenario_comments),
    ("reactions", scenario_reactions),
    ("delete_post", scenario_delete_post),
    ("block_visibility", scenario_block_visibility),
    ("reports", scenario_reports),
    ("admin_moderation", scenario_admin_moderation),
    ("post_rate_limit", scenario_post_rate_limit),
]


def run_scenarios(ctx: Ctx, names: List[str]) -> List[Tuple[str, str, str]]:
    table = dict(SCENARIOS)
    results = []
    for name in names:
        try:
            table[name](ctx)
            results.append((name, "PASS", ""))
            print("PASS " + name, flush=True)
        except NotRun as reason:
            results.append((name, "NOT_RUN", str(reason)))
            print("NOT_RUN %s: %s" % (name, reason), flush=True)
        except E2EFailure as failure:
            results.append((name, "FAIL", str(failure)))
            print("FAIL %s: %s" % (name, failure), flush=True)
        except Exception as error:  # noqa: BLE001 - report, keep going
            results.append((name, "FAIL", "%s: %s" % (type(error).__name__, error)))
            print("FAIL %s: %s: %s" % (name, type(error).__name__, error), flush=True)
    return results


def main(argv: Optional[List[str]] = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    args = parse_args(argv)
    names = [name for name, _ in SCENARIOS]
    if args.list:
        for name in names:
            print(name)
        return 0
    try:
        selected = select_scenarios(names, args.only)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2
    print("== %s journey against %s ==" % (TAG, args.base_url))
    ctx = Ctx(Client(args.base_url))
    results = run_scenarios(ctx, selected)
    failed = [r for r in results if r[1] == "FAIL"]
    summary = {status: sum(1 for r in results if r[1] == status)
               for status in ("PASS", "FAIL", "NOT_RUN")}
    print("\nsummary: %(PASS)d PASS, %(FAIL)d FAIL, %(NOT_RUN)d NOT_RUN" % summary)
    return 1 if failed else 0


if __name__ == "__main__":
    main_guard(main)
