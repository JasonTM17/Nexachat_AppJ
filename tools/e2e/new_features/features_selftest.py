"""Offline self-test for the new-feature live suite.

``features_live.py --self-test`` runs every non-STOMP scenario against this
in-memory fake transport. The fake implements the same routes and state rules
the live checks assert, so a green self-test proves the script's own path
building, request bodies, status expectations and cleanup sequencing without a
backend. It is intentionally small: it is a test double for the script, not a
second server implementation.
"""

from __future__ import annotations

import json
import re
import urllib.parse
import uuid
from datetime import datetime
from typing import Any

from e2e_common import Response
from features_helpers import iso_utc, now_utc

_UUID = r"[0-9a-fA-F-]{36}"


def _json_response(status: int, payload: Any = None) -> Response:
    if payload is None:
        return Response(status, {}, b"")
    return Response(status, {"Content-Type": "application/json"},
                    json.dumps(payload).encode("utf-8"))


def _error(status: int, code: str) -> Response:
    return _json_response(status, {"error": {"code": code, "message": code}})


def _multipart_parts(raw: bytes, content_type: str) -> dict[str, tuple[str | None, bytes, str]]:
    """Very small multipart parser: returns name -> (filename, data, content type)."""
    match = re.search(r"boundary=([^;]+)", content_type or "")
    if not match:
        return {}
    boundary = ("--" + match.group(1).strip('"')).encode()
    parts: dict[str, tuple[str | None, bytes, str]] = {}
    for chunk in raw.split(boundary):
        chunk = chunk.strip(b"\r\n")
        if not chunk or chunk == b"--":
            continue
        head, _, body = chunk.partition(b"\r\n\r\n")
        header_text = head.decode("utf-8", errors="replace")
        name_match = re.search(r'name="([^"]+)"', header_text)
        if not name_match:
            continue
        filename_match = re.search(r'filename="([^"]*)"', header_text)
        type_match = re.search(r"Content-Type:\s*([^\r\n]+)", header_text)
        parts[name_match.group(1)] = (
            filename_match.group(1) if filename_match else None,
            body,
            type_match.group(1).strip() if type_match else "application/octet-stream",
        )
    return parts


class FakeClient:
    """In-memory implementation of the routes the live suite touches."""

    def __init__(self):
        self.users: dict[str, dict[str, Any]] = {}
        self.tokens: dict[str, str] = {}
        self.conversations: dict[str, dict[str, Any]] = {}
        self.attachments: dict[str, dict[str, Any]] = {}
        self.messages: dict[str, dict[str, Any]] = {}
        self.idempotency: dict[tuple[str, str], dict[str, Any]] = {}
        self.seq = 0
        self.version = 0

    # -- transport ----------------------------------------------------------
    def request(self, method: str, path: str, token: str | None = None, body=None,
                params: dict | None = None, raw_body=None,
                headers: dict[str, str] | None = None) -> Response:
        url = urllib.parse.urlsplit(path)
        route = url.path
        params = {k: v for k, v in (params or {}).items() if v is not None}
        headers = headers or {}
        try:
            return self._dispatch(method.upper(), route, token, body, params,
                                  raw_body, headers)
        except Exception:
            return _error(500, "INTERNAL_ERROR")

    def get(self, path, token=None, params=None, headers=None) -> Response:
        return self.request("GET", path, token=token, params=params, headers=headers)

    def post(self, path, token=None, body=None, headers=None) -> Response:
        return self.request("POST", path, token=token, body=body, headers=headers)

    def put(self, path, token=None, body=None, headers=None) -> Response:
        return self.request("PUT", path, token=token, body=body, headers=headers)

    def patch(self, path, token=None, body=None, headers=None) -> Response:
        return self.request("PATCH", path, token=token, body=body, headers=headers)

    def delete(self, path, token=None, params=None, headers=None) -> Response:
        return self.request("DELETE", path, token=token, params=params, headers=headers)

    # -- helpers ------------------------------------------------------------
    def _user(self, token: str | None) -> dict[str, Any] | None:
        user_id = self.tokens.get(token or "")
        return self.users.get(user_id) if user_id else None

    def _conv(self, conversation_id: str) -> dict[str, Any] | None:
        return self.conversations.get(conversation_id)

    def _member(self, conv: dict[str, Any], user_id: str) -> dict[str, Any] | None:
        return conv["members"].get(user_id)

    def _next_seq(self) -> int:
        self.seq += 1
        return self.seq

    def _next_version(self) -> int:
        self.version += 1
        return self.version

    def _idempotent(self, user_id: str, key: str | None, endpoint: str,
                    payload: str, action):
        if not key:
            return action()
        stored = self.idempotency.get((user_id, key))
        if stored is not None:
            if stored["endpoint"] != endpoint or stored["payload"] != payload:
                return _error(409, "IDEMPOTENCY_KEY_REUSED")
            cached = stored["response"]
            if cached.status == 201:
                return Response(200, cached.headers, cached.body)
            return cached
        response = action()
        if response.status < 300:
            self.idempotency[(user_id, key)] = {
                "endpoint": endpoint, "payload": payload, "response": response,
            }
        return response

    def _inbox_entry(self, conv: dict[str, Any], user_id: str) -> dict[str, Any]:
        member = conv["members"][user_id]
        muted_until = member.get("muted_until")
        muted = bool(member.get("muted")) and (
            muted_until is None or muted_until > now_utc()
        )
        last = conv["messages"][-1] if conv["messages"] else None
        peer = next((uid for uid in conv["members"] if uid != user_id), None)
        title = conv["title"] or (self.users[peer]["display_name"] if peer else "Chat")
        entry: dict[str, Any] = {
            "id": conv["id"],
            "type": conv["type"],
            "title": title,
            "avatarUrl": None,
            "members": [
                {
                    "userId": uid,
                    "displayName": self.users[uid]["display_name"],
                    "phone": self.users[uid]["phone"],
                    "avatarUrl": None,
                    "role": member_row.get("role", "MEMBER"),
                }
                for uid, member_row in conv["members"].items()
            ],
            "lastMessage": None if last is None else {
                "seq": last["seq"], "type": last["type"],
                "contentSnippet": None if last.get("deleted") else (last.get("content") or ""),
                "senderId": last["senderId"], "createdAt": last["createdAt"],
                "deletedAt": last["createdAt"] if last.get("deleted") else None,
            },
            "unreadCount": 0,
            "lastReadSeq": member.get("last_read_seq", 0),
            "muted": muted,
            "archived": bool(member.get("archived")),
            "aiOptOut": bool(conv.setdefault("ai_opt_out", {}).get(user_id)),
            "pinned": bool(member.get("pinned")),
        }
        if muted and muted_until is not None:
            entry["mutedUntil"] = iso_utc(muted_until)
        if member.get("pinned"):
            entry["pinnedAt"] = iso_utc(now_utc())
        return entry

    def _poll_view(self, pol: dict[str, Any], viewer_id: str) -> dict[str, Any]:
        options = []
        for option in pol["options"]:
            voters = sorted(pol["votes"].get(option["id"], set()))
            row: dict[str, Any] = {
                "id": option["id"], "position": option["position"],
                "label": option["label"], "voteCount": len(voters),
            }
            if not pol["anonymous"]:
                row["voterIds"] = voters
            options.append(row)
        closed = pol.get("closed_at") is not None
        return {
            "id": pol["id"],
            "conversationId": pol["conversation_id"],
            "createdBy": pol["created_by"],
            "question": pol["question"],
            "multipleChoice": pol["multiple_choice"],
            "anonymous": pol["anonymous"],
            "status": "CLOSED" if closed else "OPEN",
            "closedAt": pol.get("closed_at"),
            "closedBy": pol.get("closed_by"),
            "createdAt": pol["created_at"],
            "version": pol["version"],
            "voterCount": len(set().union(*pol["votes"].values())) if pol["votes"] else 0,
            "options": options,
            "myOptionIds": [
                option_id for option_id, voters in pol["votes"].items()
                if viewer_id in voters
            ],
        }

    # -- router -------------------------------------------------------------
    def _dispatch(self, method: str, route: str, token, body, params,
                  raw_body, headers) -> Response:
        # Public/auth
        if route == "/actuator/health" and method == "GET":
            return _json_response(200, {"status": "UP"})
        if route == "/api/v1/auth/register" and method == "POST":
            body = body or {}
            user_id = str(uuid.uuid4())
            access = str(uuid.uuid4())
            self.users[user_id] = {
                "id": user_id, "phone": body.get("phone", ""),
                "password": body.get("password"), "display_name": body.get("displayName", "User"),
                "settings": {"showPresence": True, "showSeen": True, "aiSummaryConsent": False},
            }
            self.tokens[access] = user_id
            return _json_response(201, {
                "user": {"id": user_id, "phone": body.get("phone", ""),
                         "displayName": body.get("displayName", "User"), "avatarUrl": None},
                "accessToken": access, "accessTokenExpiresIn": 900,
                "refreshToken": str(uuid.uuid4()),
            })
        if route == "/api/v1/auth/login" and method == "POST":
            return _error(400, "VALIDATION_FAILED")

        user = self._user(token)
        if user is None:
            return _error(401, "TOKEN_INVALID")
        user_id = user["id"]

        if route == "/api/v1/auth/logout-all" and method == "POST":
            for access, owner in list(self.tokens.items()):
                if owner == user_id:
                    del self.tokens[access]
            return _json_response(204)

        if route == "/api/v1/users/me" and method == "GET":
            return _json_response(200, {
                "id": user_id, "phone": user["phone"],
                "displayName": user["display_name"], "avatarUrl": None,
            })
        if route == "/api/v1/users/me/settings" and method == "GET":
            return _json_response(200, dict(user["settings"]))
        if route == "/api/v1/users/me/settings" and method == "PATCH":
            body = body or {}
            if not any(k in body for k in ("showPresence", "showSeen", "aiSummaryConsent")):
                return _error(400, "VALIDATION_FAILED")
            for key in ("showPresence", "showSeen", "aiSummaryConsent"):
                if key in body:
                    user["settings"][key] = bool(body[key])
            return _json_response(200, dict(user["settings"]))

        # Conversations
        if route == "/api/v1/conversations/direct" and method == "POST":
            peer_phone = (body or {}).get("phone")
            peer = next((u for u in self.users.values() if u["phone"] == peer_phone), None)
            if peer is None:
                return _error(404, "USER_NOT_FOUND")
            for conv in self.conversations.values():
                if conv["type"] == "DIRECT" and set(conv["members"]) == {user_id, peer["id"]}:
                    return _json_response(200, self._conversation_view(conv))
            conv = {
                "id": str(uuid.uuid4()), "type": "DIRECT", "title": None,
                "createdAt": iso_utc(now_utc()),
                "members": {
                    user_id: {"role": "MEMBER", "last_read_seq": 0},
                    peer["id"]: {"role": "MEMBER", "last_read_seq": 0},
                },
                "messages": [], "reminders": [], "polls": [], "ai_opt_out": {},
            }
            self.conversations[conv["id"]] = conv
            return _json_response(201, self._conversation_view(conv))
        if route == "/api/v1/conversations/group" and method == "POST":
            body = body or {}
            member_ids = [member_id for member_id in body.get("memberIds", [])
                          if member_id in self.users and member_id != user_id]
            if len(body.get("title", "").strip()) == 0 or not member_ids:
                return _error(400, "VALIDATION_FAILED")
            conv = {
                "id": str(uuid.uuid4()), "type": "GROUP",
                "title": body["title"].strip(),
                "createdAt": iso_utc(now_utc()),
                "members": {user_id: {"role": "ADMIN", "last_read_seq": 0}},
                "messages": [], "reminders": [], "polls": [], "ai_opt_out": {},
            }
            for member_id in member_ids:
                conv["members"][member_id] = {"role": "MEMBER", "last_read_seq": 0}
            self.conversations[conv["id"]] = conv
            return _json_response(201, self._conversation_view(conv))
        if route == "/api/v1/conversations" and method == "GET":
            archived = str(params.get("archived", "false")).lower() == "true"
            rows = []
            for conv in self.conversations.values():
                if user_id not in conv["members"]:
                    continue
                member = conv["members"][user_id]
                if bool(member.get("archived")) != archived:
                    continue
                rows.append(self._inbox_entry(conv, user_id))
            rows.sort(key=lambda row: (
                not row["pinned"],
                -(row["lastMessage"]["seq"] if row["lastMessage"] else 0),
            ))
            return _json_response(200, rows)

        match = re.fullmatch(
            r"/api/v1/conversations/(%s)/(mute|archive|pin|ai-opt-out)" % _UUID, route
        )
        if match:
            conv = self._conv(match.group(1))
            if conv is None:
                return _error(404, "CONVERSATION_NOT_FOUND")
            if user_id not in conv["members"]:
                return _error(403, "NOT_MEMBER")
            action = match.group(2)
            member = conv["members"][user_id]
            if action == "archive" and method == "GET":
                return self._archive(conv, params)
            if action == "mute":
                if method == "PUT":
                    body = body or {}
                    forever = body.get("forever") is True
                    until = body.get("mutedUntil")
                    if forever and until:
                        return _error(400, "VALIDATION_FAILED")
                    if forever:
                        member["muted"] = True
                        member["muted_until"] = None
                    elif until:
                        try:
                            parsed = datetime.fromisoformat(until.replace("Z", "+00:00"))
                        except ValueError:
                            return _error(400, "VALIDATION_FAILED")
                        if parsed <= now_utc():
                            return _error(400, "VALIDATION_FAILED")
                        member["muted"] = True
                        member["muted_until"] = parsed
                    else:
                        return _error(400, "VALIDATION_FAILED")
                    return _json_response(204)
                member["muted"] = False
                member["muted_until"] = None
                return _json_response(204)
            if action == "archive":
                member["archived"] = method == "PUT"
                return _json_response(204)
            if action == "pin":
                if method == "PUT":
                    if member.get("pinned"):
                        return _json_response(204)
                    count = sum(
                        1 for conv_row in self.conversations.values()
                        if user_id in conv_row["members"]
                        and conv_row["members"][user_id].get("pinned")
                    )
                    if count >= 5:
                        return _error(409, "CONVERSATION_PIN_LIMIT")
                    member["pinned"] = True
                else:
                    member["pinned"] = False
                return _json_response(204)
            # ai-opt-out
            opt_out = conv["ai_opt_out"]
            if method == "GET":
                return _json_response(200, {
                    "conversationId": conv["id"],
                    "aiOptOut": bool(opt_out.get(user_id)),
                })
            opt_out[user_id] = method == "PUT"
            return _json_response(200, {
                "conversationId": conv["id"], "aiOptOut": bool(opt_out.get(user_id)),
            })

        match = re.fullmatch(r"/api/v1/conversations/(%s)/messages" % _UUID, route)
        if match:
            conv = self._conv(match.group(1))
            if conv is None:
                return _error(404, "CONVERSATION_NOT_FOUND")
            if user_id not in conv["members"]:
                return _error(403, "NOT_MEMBER")
            if method == "POST":
                body = body or {}
                message_id = str(uuid.uuid4())
                attachment_ids = body.get("attachmentIds") or []
                for attachment_id in attachment_ids:
                    attachment = self.attachments.get(attachment_id)
                    if attachment is None or attachment["owner"] != user_id:
                        return _error(404, "FILE_NOT_FOUND")
                    if attachment["message_id"] is not None:
                        return _error(400, "VALIDATION_FAILED")
                    message_type = body.get("type")
                    if message_type == "IMAGE" and not attachment["mime"].startswith("image/"):
                        return _error(400, "VALIDATION_FAILED")
                    if message_type == "VIDEO" and not attachment["mime"].startswith("video/"):
                        return _error(400, "VALIDATION_FAILED")
                    if message_type == "AUDIO" and not attachment["mime"].startswith("audio/"):
                        return _error(400, "VALIDATION_FAILED")
                    attachment["message_id"] = message_id
                row = {
                    "id": message_id, "conversationId": conv["id"],
                    "seq": self._next_seq(), "senderId": user_id,
                    "senderDisplayName": self.users[user_id]["display_name"],
                    "type": body.get("type", "TEXT"),
                    "content": body.get("content"),
                    "clientMessageId": body.get("clientMessageId"),
                    "attachments": [self.attachments[aid] for aid in attachment_ids],
                    "reactions": [],
                    "createdAt": iso_utc(now_utc()),
                    "deleted": False,
                }
                conv["messages"].append(row)
                self.messages[message_id] = row
                return _json_response(201, self._message_view(row))
            if method == "GET":
                limit = int(params.get("limit", 30))
                rows = [self._message_view(row) for row in conv["messages"] if not row.get("deleted")]
                rows.reverse()
                return _json_response(200, {
                    "items": rows[:limit], "nextBeforeSeq": None, "hasMore": False,
                })

        match = re.fullmatch(
            r"/api/v1/conversations/(%s)/messages/(%s)" % (_UUID, _UUID), route
        )
        if match and method == "DELETE":
            conv = self._conv(match.group(1))
            row = self.messages.get(match.group(2))
            if conv is None or row is None or row["conversationId"] != conv["id"]:
                return _error(404, "MESSAGE_NOT_FOUND")
            if row["senderId"] != user_id:
                return _error(403, "NOT_MESSAGE_AUTHOR")
            row["deleted"] = True
            row["content"] = None
            return _json_response(204)

        if route == "/api/v1/attachments" and method == "POST":
            parts = _multipart_parts(raw_body[0] if raw_body else b"",
                                     raw_body[1] if raw_body else "")
            file_part = parts.get("file")
            if file_part is None or not file_part[1]:
                return _error(400, "VALIDATION_FAILED")
            filename, data, declared = file_part
            mime = "image/png" if data.startswith(b"\x89PNG") else declared
            if mime == "text/plain" and b"\x00" in data:
                return _error(415, "MIME_NOT_ALLOWED")
            attachment_id = str(uuid.uuid4())
            blur_hash = parts.get("blurHash", (None, b"", ""))[1].decode() or None
            if blur_hash and not mime.startswith("image/"):
                return _error(400, "VALIDATION_FAILED")
            self.attachments[attachment_id] = {
                "id": attachment_id, "owner": user_id, "mime": mime,
                "name": filename, "data": data, "blur_hash": blur_hash,
                "width": int(parts["width"][1]) if "width" in parts else None,
                "height": int(parts["height"][1]) if "height" in parts else None,
                "message_id": None,
            }
            return _json_response(201, {
                "id": attachment_id,
                "url": f"/api/v1/attachments/{attachment_id}/content",
                "originalName": filename, "mimeType": mime, "sizeBytes": len(data),
                "width": self.attachments[attachment_id]["width"],
                "height": self.attachments[attachment_id]["height"],
                "blurHash": blur_hash,
            })

        match = re.fullmatch(r"/api/v1/conversations/(%s)/reminders" % _UUID, route)
        if match:
            conv = self._conv(match.group(1))
            if conv is None:
                return _error(404, "CONVERSATION_NOT_FOUND")
            member = self._member(conv, user_id)
            if member is None:
                return _error(403, "NOT_MEMBER")
            if method == "GET":
                rows = sorted(
                    (row for row in conv["reminders"] if not row.get("deleted")),
                    key=lambda row: row["remindAt"],
                )
                return _json_response(200, [
                    self._reminder_view(row, conv, user_id) for row in rows
                ])
            body = body or {}
            if method == "POST":
                title = (body.get("title") or "").strip()
                remind_at = body.get("remindAt")
                if not title or not remind_at:
                    return _error(400, "VALIDATION_FAILED")
                try:
                    parsed = datetime.fromisoformat(remind_at.replace("Z", "+00:00"))
                except ValueError:
                    return _error(400, "VALIDATION_FAILED")
                if parsed <= now_utc():
                    return _error(400, "VALIDATION_FAILED")
                repeat = body.get("repeat") or "NONE"
                key = headers.get("Idempotency-Key")
                endpoint = f"POST {route}"

                def create_reminder():
                    row = {
                        "id": str(uuid.uuid4()), "conv": conv["id"], "title": title,
                        "remindAt": parsed, "repeat": repeat, "createdBy": user_id,
                        "createdAt": iso_utc(now_utc()), "deleted": False,
                    }
                    conv["reminders"].append(row)
                    return _json_response(201, self._reminder_view(row, conv, user_id))

                return self._idempotent(
                    user_id, key, endpoint,
                    f"{title}|{parsed.isoformat()}|{repeat}", create_reminder,
                )

        match = re.fullmatch(
            r"/api/v1/conversations/(%s)/reminders/(%s)" % (_UUID, _UUID), route
        )
        if match:
            conv = self._conv(match.group(1))
            if conv is None:
                return _error(404, "CONVERSATION_NOT_FOUND")
            member = self._member(conv, user_id)
            if member is None:
                return _error(403, "NOT_MEMBER")
            row = next((item for item in conv["reminders"]
                        if item["id"] == match.group(2) and not item.get("deleted")), None)
            if row is None:
                return _error(404, "NOT_FOUND")
            creator = row["createdBy"] == user_id
            admin = conv["type"] == "GROUP" and member.get("role") == "ADMIN"
            if not creator and not admin:
                return _error(403, "NOT_ADMIN" if conv["type"] == "GROUP" else "FORBIDDEN")
            if method == "DELETE":
                row["deleted"] = True
                return _json_response(204)
            body = body or {}
            if not any(key in body for key in ("title", "remindAt", "repeat")):
                return _error(400, "VALIDATION_FAILED")
            if "title" in body:
                row["title"] = body["title"].strip()
            if "remindAt" in body:
                try:
                    row["remindAt"] = datetime.fromisoformat(
                        body["remindAt"].replace("Z", "+00:00")
                    )
                except ValueError:
                    return _error(400, "VALIDATION_FAILED")
            if "repeat" in body:
                row["repeat"] = body["repeat"]
            return _json_response(200, self._reminder_view(row, conv, user_id))

        # Polls
        match = re.fullmatch(r"/api/v1/conversations/(%s)/polls" % _UUID, route)
        if match:
            conv = self._conv(match.group(1))
            if conv is None:
                return _error(404, "CONVERSATION_NOT_FOUND")
            if user_id not in conv["members"]:
                return _error(403, "NOT_MEMBER")
            if conv["type"] != "GROUP":
                return _error(400, "NOT_A_GROUP")
            if method == "POST":
                body = body or {}
                options = body.get("options") or []
                if not body.get("question") or len(options) < 2:
                    return _error(400, "VALIDATION_FAILED")
                poll = {
                    "id": str(uuid.uuid4()), "conversation_id": conv["id"],
                    "created_by": user_id, "question": body["question"],
                    "multiple_choice": bool(body.get("multipleChoice")),
                    "anonymous": bool(body.get("anonymous")),
                    "created_at": iso_utc(now_utc()), "version": self._next_version(),
                    "closed_at": None, "closed_by": None,
                    "options": [
                        {"id": str(uuid.uuid4()), "position": index, "label": label}
                        for index, label in enumerate(options)
                    ],
                    "votes": {},
                }
                conv["polls"].append(poll)
                return _json_response(201, self._poll_view(poll, user_id))
            if method == "GET":
                rows = list(reversed(conv["polls"]))
                return _json_response(200, {
                    "items": [self._poll_view(poll, user_id)
                              for poll in rows if not poll.get("deleted")],
                    "nextBefore": None,
                })

        match = re.fullmatch(r"/api/v1/conversations/(%s)/polls/changes" % _UUID, route)
        if match and method == "GET":
            conv = self._conv(match.group(1))
            if conv is None:
                return _error(404, "CONVERSATION_NOT_FOUND")
            if user_id not in conv["members"]:
                return _error(403, "NOT_MEMBER")
            if conv["type"] != "GROUP":
                return _error(400, "NOT_A_GROUP")
            since = int(params.get("since", 0))
            rows = sorted(conv["polls"], key=lambda poll: poll["version"])
            items = []
            for poll in rows:
                if poll["version"] <= since:
                    continue
                item = {"pollId": poll["id"], "version": poll["version"],
                        "deleted": bool(poll.get("deleted"))}
                if not poll.get("deleted"):
                    item["poll"] = self._poll_view(poll, user_id)
                items.append(item)
            return _json_response(200, {
                "items": items,
                "nextSince": items[-1]["version"] if items else since,
                "hasMore": False,
            })

        match = re.fullmatch(
            r"/api/v1/conversations/(%s)/polls/(%s)(/vote|/close)?" % (_UUID, _UUID),
            route,
        )
        if match:
            conv = self._conv(match.group(1))
            if conv is None:
                return _error(404, "CONVERSATION_NOT_FOUND")
            if user_id not in conv["members"]:
                return _error(403, "NOT_MEMBER")
            if conv["type"] != "GROUP":
                return _error(400, "NOT_A_GROUP")
            poll = next((item for item in conv["polls"]
                         if item["id"] == match.group(2) and not item.get("deleted")), None)
            if poll is None:
                return _error(404, "POLL_NOT_FOUND")
            suffix = match.group(3) or ""
            if method == "GET" and not suffix:
                return _json_response(200, self._poll_view(poll, user_id))
            if suffix == "/vote":
                if method == "PUT":
                    option_id = ((body or {}).get("optionIds") or [None])[0]
                    if option_id not in {option["id"] for option in poll["options"]}:
                        return _error(400, "VALIDATION_FAILED")
                    current = {oid for oid, voters in poll["votes"].items()
                               if user_id in voters}
                    if current == {option_id}:
                        return _json_response(200, self._poll_view(poll, user_id))
                    if poll.get("closed_at"):
                        return _error(409, "POLL_CLOSED")
                    for voters in poll["votes"].values():
                        voters.discard(user_id)
                    poll["votes"].setdefault(option_id, set()).add(user_id)
                    poll["version"] = self._next_version()
                    return _json_response(200, self._poll_view(poll, user_id))
                if method == "DELETE":
                    current = {oid for oid, voters in poll["votes"].items()
                               if user_id in voters}
                    if not current:
                        return _json_response(200, self._poll_view(poll, user_id))
                    if poll.get("closed_at"):
                        return _error(409, "POLL_CLOSED")
                    for voters in poll["votes"].values():
                        voters.discard(user_id)
                    poll["version"] = self._next_version()
                    return _json_response(200, self._poll_view(poll, user_id))
            if suffix == "/close" and method == "POST":
                member = conv["members"][user_id]
                if poll["created_by"] != user_id and member.get("role") != "ADMIN":
                    return _error(403, "NOT_ADMIN")
                if poll.get("closed_at") is None:
                    poll["closed_at"] = iso_utc(now_utc())
                    poll["closed_by"] = user_id
                    poll["version"] = self._next_version()
                return _json_response(200, self._poll_view(poll, user_id))

        match = re.fullmatch(r"/api/v1/conversations/(%s)/polls/(%s)" % (_UUID, _UUID), route)
        if match and method == "DELETE":
            conv = self._conv(match.group(1))
            if conv is None:
                return _error(404, "CONVERSATION_NOT_FOUND")
            if user_id not in conv["members"]:
                return _error(403, "NOT_MEMBER")
            if conv["type"] != "GROUP":
                return _error(400, "NOT_A_GROUP")
            poll = next((item for item in conv["polls"]
                         if item["id"] == match.group(2)), None)
            if poll is None or poll.get("deleted"):
                return _error(404, "POLL_NOT_FOUND")
            member = conv["members"][user_id]
            if poll["created_by"] != user_id and member.get("role") != "ADMIN":
                return _error(403, "NOT_ADMIN")
            poll["deleted"] = True
            poll["version"] = self._next_version()
            return _json_response(204)

        # Reactions
        match = re.fullmatch(r"/api/v1/messages/(%s)/reactions" % _UUID, route)
        if match and method == "POST":
            row = self.messages.get(match.group(1))
            if row is None:
                return _error(404, "MESSAGE_NOT_FOUND")
            body = body or {}
            emoji = body.get("emoji")
            key = headers.get("Idempotency-Key")

            def add_reaction():
                existing = next((r for r in row["reactions"]
                                 if r["emoji"] == emoji and r["userId"] == user_id), None)
                if existing:
                    row["reactions"].remove(existing)
                else:
                    row["reactions"].append({"emoji": emoji, "userId": user_id})
                return _json_response(204)

            return self._idempotent(user_id, key, f"POST {route}",
                                    f"{row['id']}|{emoji}", add_reaction)

        match = re.fullmatch(
            r"/api/v1/messages/(%s)/reactions/(.+)" % _UUID, route
        )
        if match and method == "DELETE":
            row = self.messages.get(match.group(1))
            if row is None:
                return _error(404, "MESSAGE_NOT_FOUND")
            emoji = urllib.parse.unquote(match.group(2))
            row["reactions"] = [r for r in row["reactions"]
                                if not (r["emoji"] == emoji and r["userId"] == user_id)]
            return _json_response(204)

        match = re.fullmatch(r"/api/v1/conversations/(%s)/read" % _UUID, route)
        if match and method == "POST":
            conv = self._conv(match.group(1))
            if conv is None:
                return _error(404, "CONVERSATION_NOT_FOUND")
            member = self._member(conv, user_id)
            if member is None:
                return _error(403, "NOT_MEMBER")
            member["last_read_seq"] = (body or {}).get("upToSeq", 0)
            return _json_response(204)

        return _error(404, "NOT_FOUND")

    # -- view builders ------------------------------------------------------
    def _conversation_view(self, conv: dict[str, Any]) -> dict[str, Any]:
        return {
            "id": conv["id"], "type": conv["type"],
            "members": [
                {"userId": uid, "displayName": self.users[uid]["display_name"],
                 "phone": self.users[uid]["phone"]}
                for uid in conv["members"]
            ],
            "createdAt": conv["createdAt"],
            "aiOptOut": False,
        }

    def _message_view(self, row: dict[str, Any]) -> dict[str, Any]:
        counts: dict[str, int] = {}
        for reaction in row["reactions"]:
            counts[reaction["emoji"]] = counts.get(reaction["emoji"], 0) + 1
        return {
            "id": row["id"], "conversationId": row["conversationId"],
            "seq": row["seq"], "senderId": row["senderId"],
            "senderDisplayName": row["senderDisplayName"], "type": row["type"],
            "content": row["content"], "clientMessageId": row["clientMessageId"],
            "attachments": [
                {"id": attachment["id"], "url": "", "originalName": attachment["name"],
                 "mimeType": attachment["mime"], "sizeBytes": len(attachment["data"])}
                for attachment in row["attachments"]
            ],
            "reactions": [
                {"emoji": emoji, "count": count, "byMe": False}
                for emoji, count in counts.items()
            ],
            "createdAt": row["createdAt"],
        }

    def _reminder_view(self, row: dict[str, Any], conv: dict[str, Any],
                       user_id: str) -> dict[str, Any]:
        member = conv["members"][user_id]
        can_edit = row["createdBy"] == user_id or (
            conv["type"] == "GROUP" and member.get("role") == "ADMIN"
        )
        return {
            "id": row["id"], "conversationId": conv["id"], "title": row["title"],
            "remindAt": iso_utc(row["remindAt"]), "repeat": row["repeat"],
            "createdBy": row["createdBy"],
            "createdByName": self.users[row["createdBy"]]["display_name"],
            "canEdit": can_edit, "createdAt": row["createdAt"],
            "updatedAt": row["createdAt"],
        }

    def _archive(self, conv: dict[str, Any], params: dict[str, Any]) -> Response:
        kind = params.get("type")
        if kind not in ("MEDIA", "FILE", "LINK"):
            return _error(400, "VALIDATION_FAILED")
        from_ts = params.get("from")
        to_ts = params.get("to")
        try:
            from_dt = datetime.fromisoformat(from_ts.replace("Z", "+00:00")) if from_ts else None
            to_dt = datetime.fromisoformat(to_ts.replace("Z", "+00:00")) if to_ts else None
        except ValueError:
            return _error(400, "VALIDATION_FAILED")
        if from_dt and to_dt and from_dt > to_dt:
            return _error(400, "VALIDATION_FAILED")
        limit = int(params.get("limit", 30))
        if limit < 1 or limit > 50:
            return _error(400, "VALIDATION_FAILED")
        sender = params.get("senderId")
        cursor = params.get("cursor")
        cursor_seq = None
        cursor_offset = 0
        if cursor:
            try:
                _kind, seq_text, offset_text = cursor.split(":")
                cursor_seq = int(seq_text)
                cursor_offset = int(offset_text)
            except (ValueError, AttributeError):
                return _error(400, "VALIDATION_FAILED")

        messages = [row for row in reversed(conv["messages"])
                    if not row.get("deleted")]

        if kind == "LINK":
            links: list[tuple[int, dict[str, Any], str]] = []
            for row in messages:
                if sender and row["senderId"] != sender:
                    continue
                if from_dt and datetime.fromisoformat(
                        row["createdAt"].replace("Z", "+00:00")) < from_dt:
                    continue
                if to_dt and datetime.fromisoformat(
                        row["createdAt"].replace("Z", "+00:00")) > to_dt:
                    continue
                for url in re.findall(r"https?://[^\s,;]+", row.get("content") or ""):
                    links.append((row["seq"], row, url.rstrip(".")))
            start = 0
            if cursor_seq is not None:
                start = next((index for index, item in enumerate(links)
                              if item[0] == cursor_seq), 0) + cursor_offset
            page = links[start:start + limit]
            items = [
                {
                    "messageId": row["id"], "seq": seq, "senderId": row["senderId"],
                    "createdAt": row["createdAt"], "kind": "LINK",
                    "attachment": None, "link": {"url": url},
                }
                for seq, row, url in page
            ]
            next_cursor = None
            if start + limit < len(links):
                last_seq = page[-1][0] if page else 0
                used = sum(1 for item in links[:start + limit] if item[0] == last_seq)
                next_cursor = f"LINK:{last_seq}:{used}"
            return _json_response(200, {"items": items, "nextCursor": next_cursor})

        items = []
        for row in messages:
            if sender and row["senderId"] != sender:
                continue
            if from_dt and datetime.fromisoformat(
                    row["createdAt"].replace("Z", "+00:00")) < from_dt:
                continue
            if to_dt and datetime.fromisoformat(
                    row["createdAt"].replace("Z", "+00:00")) > to_dt:
                continue
            for attachment in row["attachments"]:
                mime = attachment["mime"]
                is_media = mime.startswith("image/") or mime.startswith("video/")
                if (kind == "MEDIA") != is_media:
                    continue
                item_kind = ("IMAGE" if mime.startswith("image/")
                             else "VIDEO" if mime.startswith("video/") else "FILE")
                items.append({
                    "messageId": row["id"], "seq": row["seq"],
                    "senderId": row["senderId"], "createdAt": row["createdAt"],
                    "kind": item_kind, "link": None,
                    "attachment": {
                        "id": attachment["id"], "fileName": attachment["name"],
                        "mimeType": mime, "sizeBytes": len(attachment["data"]),
                        "durationMs": None, "blurHash": attachment["blur_hash"],
                        "width": attachment["width"], "height": attachment["height"],
                    },
                })
        return _json_response(200, {"items": items[:limit], "nextCursor": None})


def run_self_test() -> int:
    """Run all non-STOMP scenarios against the fake and verify the guard."""
    import features_live as live

    print("=" * 78)
    print("   NEXACHAT NEW-FEATURE SUITE OFFLINE SELF-TEST (mocked transport)")
    print("=" * 78)
    log = live.CheckLog(allow_skip=False)
    fx = live.run_suite(FakeClient(), log, "", with_stomp=False)
    if fx is None:
        print("SELF-TEST FAIL: fixture setup did not complete")
        return 1

    # Guard logic must refuse a non-loopback base URL before any network call.
    guard = live.main(["--base-url", "https://example.com"])
    guard_ok = guard == 2
    log.record(guard_ok, "self_test_non_local_guard", "GUARD", "--base-url", guard,
               "non-loopback URL refused without the override flag")

    result = log.summary()
    print("SELF-TEST PASS" if result == 0 else "SELF-TEST FAIL")
    return result


if __name__ == "__main__":
    raise SystemExit(run_self_test())
