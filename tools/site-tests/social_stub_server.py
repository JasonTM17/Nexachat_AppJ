#!/usr/bin/env python3
"""Loopback Social/API fixture for browser proof only; not a production stand-in."""

import base64
import json
import re
import time
import uuid
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse

ROOT = Path(__file__).resolve().parents[2] / "site"
SOCIAL_CSP = (
    "default-src 'none'; style-src 'self'; script-src 'self'; "
    "connect-src 'self'; img-src 'self' blob:; media-src blob:; base-uri 'none'; "
    "form-action 'none'; frame-ancestors 'none'"
)
IMAGE_ID = "55555555-5555-5555-5555-555555555555"
VIDEO_ID = "66666666-6666-6666-6666-666666666666"
BAD_VIDEO_ID = "77777777-7777-7777-7777-777777777777"
# Bytes with a valid-looking MP4 brand but no playable media: browsers fire a
# decode error on them, which exercises the player failure path.
BAD_VIDEO_BYTES = bytes([0, 0, 0, 24]) + b"ftypisom" + bytes([0, 0, 2, 0]) + b"isomiso2" + bytes(64)
VIDEO_FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "server" / "src" / "test" / "resources" / "fixtures" / "feed_video_h264_64x48.mp4"
)
VIDEO_BYTES = VIDEO_FIXTURE.read_bytes() if VIDEO_FIXTURE.exists() else b""
IMAGE_TRIGGER = "[image-fixture]"
PNG_BYTES = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)
USERS = {
    "one@example.com": {
        "id": "11111111-1111-1111-1111-111111111111",
        "displayName": "An Nguyen",
        "password": "pass-one",
    },
    "two@example.com": {
        "id": "22222222-2222-2222-2222-222222222222",
        "displayName": "Binh Tran",
        "password": "pass-two",
    },
}

posts = []
comments = []
reports = []
tokens = {}
reaction_rows = set()


def now():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def public_user(user):
    return {"id": user["id"], "displayName": user["displayName"], "avatarUrl": None}


def reactions_for(post_id, viewer_id):
    rows = [row for row in reaction_rows if row[0] == post_id]
    emojis = sorted({row[2] for row in rows})
    return [
        {
            "emoji": emoji,
            "count": sum(1 for row in rows if row[2] == emoji),
            "mine": (post_id, viewer_id, emoji) in reaction_rows,
        }
        for emoji in emojis
    ]


def post_json(post, viewer_id):
    return {
        "id": post["id"],
        "author": post["author"],
        "body": post["body"],
        "image": post.get("image"),
        **({"video": post["video"]} if post.get("video") else {}),
        "createdAt": post["createdAt"],
        "commentCount": sum(
            1 for row in comments if row["postId"] == post["id"] and not row.get("deleted")
        ),
        "reactions": reactions_for(post["id"], viewer_id),
        "mine": post["author"]["id"] == viewer_id,
    }


def comment_json(comment, viewer_id):
    return {
        "id": comment["id"],
        "postId": comment["postId"],
        "author": comment["author"],
        "body": comment["body"],
        "createdAt": comment["createdAt"],
        "mine": comment["author"]["id"] == viewer_id,
    }


def auth_payload(user, access, refresh):
    return {
        "user": {"id": user["id"], "displayName": user["displayName"]},
        "accessToken": access,
        "accessTokenExpiresIn": 3600,
        "refreshToken": refresh,
        "recallWindowSeconds": 0,
        "groupAdminMayRecallAfterWindow": False,
    }


class Handler(SimpleHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def end_headers(self):
        self.send_header("Cache-Control", "private, no-store")
        if urlparse(self.path).path.startswith("/social"):
            self.send_header("Content-Security-Policy", SOCIAL_CSP)
        super().end_headers()

    def json_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def send_json(self, status, value):
        raw = json.dumps(value).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def send_empty(self, status):
        self.send_response(status)
        self.end_headers()

    def actor(self):
        header = self.headers.get("Authorization", "")
        user = tokens.get(header.removeprefix("Bearer "))
        if not user:
            self.send_json(401, {"error": {"code": "UNAUTHORIZED"}})
        return user

    def find_post(self, post_id):
        return next(
            (post for post in posts if post["id"] == post_id and not post.get("deleted")),
            None,
        )

    def do_POST(self):
        path = urlparse(self.path).path
        if (self.headers.get("Content-Type") or "").startswith("multipart/form-data"):
            user = self.actor()
            if not user:
                return
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length)
            if path == "/api/social/v1/videos":
                # Test-only sentinel: a 2xx upload response without any media
                # id, proving the client refuses to degrade into a text post.
                if b"missing-media-id.mp4" in body:
                    self.send_json(201, {
                        "url": "/api/social/v1/videos/" + VIDEO_ID,
                        "mimeType": "video/mp4",
                    })
                    return
                if b"decode-fail.mp4" in body:
                    self.send_json(201, {
                        "videoId": BAD_VIDEO_ID,
                        "url": "/api/social/v1/videos/" + BAD_VIDEO_ID,
                        "mimeType": "video/mp4",
                    })
                    return
                self.send_json(201, {
                    "videoId": VIDEO_ID,
                    "url": "/api/social/v1/videos/" + VIDEO_ID,
                    "mimeType": "video/mp4",
                })
                return
            if path == "/api/social/v1/images":
                self.send_json(201, {
                    "imageId": IMAGE_ID,
                    "url": "/api/v1/feed/images/" + IMAGE_ID,
                    "width": 1,
                    "height": 1,
                    "mimeType": "image/png",
                })
                return
            self.send_json(404, {"error": {"code": "NOT_FOUND"}})
            return
        data = self.json_body()
        if path == "/api/social/v1/session/login":
            user = USERS.get(data.get("identifier", ""))
            if not user or user["password"] != data.get("password"):
                self.send_json(401, {"error": {"code": "UNAUTHORIZED"}})
                return
            access = "access-" + user["id"][:8]
            refresh = "refresh-" + user["id"][:8]
            tokens[access] = user
            self.send_json(200, auth_payload(user, access, refresh))
            return
        if path == "/api/social/v1/session/refresh":
            user = next(
                (item for item in USERS.values() if data.get("refreshToken") == "refresh-" + item["id"][:8]),
                None,
            )
            if not user:
                self.send_json(401, {"error": {"code": "UNAUTHORIZED"}})
                return
            access = "access-{}-{}".format(user["id"][:8], len(tokens) + 1)
            tokens[access] = user
            self.send_json(200, auth_payload(user, access, data.get("refreshToken")))
            return

        user = self.actor()
        if not user:
            return
        if path == "/api/social/v1/session/logout":
            self.send_empty(204)
            return
        if path == "/api/social/v1/posts":
            found = next(
                (
                    post
                    for post in posts
                    if post["author"]["id"] == user["id"]
                    and post["clientPostId"] == data.get("clientPostId")
                    and not post.get("deleted")
                ),
                None,
            )
            if found:
                self.send_json(200, post_json(found, user["id"]))
                return
            image = None
            if IMAGE_TRIGGER in (data.get("body") or "") or data.get("imageId"):
                image = {
                    "url": "/api/v1/feed/images/" + IMAGE_ID,
                    "width": 1,
                    "height": 1,
                    "mimeType": "image/png",
                }
            video = None
            if data.get("videoId"):
                video = {
                    "url": "/api/social/v1/videos/" + data["videoId"],
                    "mimeType": "video/mp4",
                }
            post = {
                "id": str(uuid.uuid4()),
                "author": public_user(user),
                "body": (data.get("body") or "").strip(),
                "image": image,
                "video": video,
                "clientPostId": data.get("clientPostId"),
                "createdAt": now(),
            }
            posts.insert(0, post)
            self.send_json(201, post_json(post, user["id"]))
            return

        match = re.fullmatch(r"/api/social/v1/posts/([0-9a-f-]{36})/comments", path)
        if match:
            if not self.find_post(match.group(1)):
                self.send_json(404, {"error": {"code": "POST_NOT_FOUND"}})
                return
            found = next(
                (
                    row
                    for row in comments
                    if row["author"]["id"] == user["id"]
                    and row["postId"] == match.group(1)
                    and row["clientCommentId"] == data.get("clientCommentId")
                    and not row.get("deleted")
                ),
                None,
            )
            if found:
                self.send_json(200, comment_json(found, user["id"]))
                return
            comment = {
                "id": str(uuid.uuid4()),
                "postId": match.group(1),
                "author": public_user(user),
                "body": (data.get("body") or "").strip(),
                "clientCommentId": data.get("clientCommentId"),
                "createdAt": now(),
            }
            comments.append(comment)
            self.send_json(201, comment_json(comment, user["id"]))
            return
        if path == "/api/social/v1/reports":
            reports.append(data)
            self.send_json(201, {"id": str(uuid.uuid4())})
            return
        self.send_json(404, {"error": {"code": "NOT_FOUND"}})

    def do_GET(self):
        path = urlparse(self.path).path
        if not path.startswith("/api/social/v1/"):
            if path in ("/social", "/social/"):
                self.path = "/social/index.html"
            return super().do_GET()

        user = self.actor()
        if not user:
            return
        if path == "/api/social/v1/posts":
            visible = [post for post in posts if not post.get("deleted")]
            self.send_json(
                200,
                {"items": [post_json(post, user["id"]) for post in visible[:20]], "nextCursor": None},
            )
            return
        if path == "/api/social/v1/videos/" + BAD_VIDEO_ID:
            self.send_response(200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", str(len(BAD_VIDEO_BYTES)))
            self.end_headers()
            self.wfile.write(BAD_VIDEO_BYTES)
            return
        if path == "/api/social/v1/videos/" + VIDEO_ID:
            self.send_response(200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", str(len(VIDEO_BYTES)))
            self.end_headers()
            self.wfile.write(VIDEO_BYTES)
            return
        if path == "/api/social/v1/images/" + IMAGE_ID:
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(PNG_BYTES)))
            self.end_headers()
            self.wfile.write(PNG_BYTES)
            return
        match = re.fullmatch(r"/api/social/v1/posts/([0-9a-f-]{36})", path)
        if match:
            post = self.find_post(match.group(1))
            if not post:
                self.send_json(404, {"error": {"code": "POST_NOT_FOUND"}})
                return
            self.send_json(200, post_json(post, user["id"]))
            return
        match = re.fullmatch(r"/api/social/v1/posts/([0-9a-f-]{36})/comments", path)
        if match:
            rows = [
                row
                for row in comments
                if row["postId"] == match.group(1) and not row.get("deleted")
            ]
            self.send_json(
                200,
                {"items": [comment_json(row, user["id"]) for row in rows], "nextCursor": None},
            )
            return
        self.send_json(404, {"error": {"code": "NOT_FOUND"}})

    def do_PUT(self):
        user = self.actor()
        if not user:
            return
        match = re.fullmatch(
            r"/api/social/v1/posts/([0-9a-f-]{36})/reactions/(.+)",
            urlparse(self.path).path,
        )
        if match:
            reaction_rows.add((match.group(1), user["id"], unquote(match.group(2))))
            self.send_json(200, {"reactions": reactions_for(match.group(1), user["id"])})
            return
        self.send_json(404, {"error": {"code": "NOT_FOUND"}})

    def do_DELETE(self):
        user = self.actor()
        if not user:
            return
        match = re.fullmatch(
            r"/api/social/v1/posts/([0-9a-f-]{36})/reactions/(.+)",
            urlparse(self.path).path,
        )
        if match:
            reaction_rows.discard((match.group(1), user["id"], unquote(match.group(2))))
            self.send_json(200, {"reactions": reactions_for(match.group(1), user["id"])})
            return
        self.send_json(404, {"error": {"code": "NOT_FOUND"}})


if __name__ == "__main__":
    import sys

    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8899
    handler = partial(Handler, directory=str(ROOT))
    print(f"listening {port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), handler).serve_forever()
