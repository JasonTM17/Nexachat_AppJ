"""Offline unittest suite for tools/e2e/release_qa/.

Runs completely offline with zero Docker, Maven, Android emulator, or live
network dependencies using local ThreadingHTTPServer stubs and injected fakes.
"""

from __future__ import annotations

import io
import json
import subprocess
import sys
import tempfile
import threading
import unittest
from contextlib import redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, List, Tuple
from unittest.mock import patch

E2E_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
if str(E2E_ROOT) not in sys.path:
    sys.path.insert(0, str(E2E_ROOT))

from tools.e2e.release_qa import (  # noqa: E402
    BlockedCapability,
    E2EFailure,
    ReleaseCheck,
    ReleaseCheckOutcome,
    ReleaseQaContext,
    SafetyGuardError,
    SocialApiClient,
    assert_ephemeral_phone,
    is_loopback_url,
    redact_secrets,
)
from tools.e2e.release_qa.live_verify import (  # noqa: E402
    assert_live_write_allowed,
    build_adb_app_link_cmd,
    build_release_emulator_cmd,
    format_sha256_colon_upper,
    verify_apk_matches_assetlinks,
    verify_live_release_apk,
)
from tools.e2e.release_qa.run_release_qa import (  # noqa: E402
    _execute_checks,
    build_json_report,
    evaluate_exit_code,
    filter_live_safe_checks,
    get_all_checks,
    main as cli_main,
    validate_target_safety,
)
from tools.e2e.release_qa import tier1_2_platform_api, tier3_4_cross_surface  # noqa: E402
from tools.e2e.release_qa.tier1_2_platform_api import (  # noqa: E402
    get_tier1_checks,
    get_tier2_checks,
)
from tools.e2e.release_qa.tier3_4_cross_surface import (  # noqa: E402
    NOTE_MONOGRAM_PATH,
    audit_tonal_plum_tokens,
    build_public_site_urls,
    effective_touch_target_height,
    get_tier3_checks,
    get_tier4_checks,
    verify_public_page_note_monogram,
)
from tools.e2e.social_suite.browser_driver import contrast_ratio, parse_css_color  # noqa: E402


# ---------------------------------------------------------------------------
# 1. Threshold & Registry Accounting
# ---------------------------------------------------------------------------


class TestThresholdAndRegistryAccounting(unittest.TestCase):
    def test_tier_and_feature_minimums(self) -> None:
        t1 = get_tier1_checks()
        t2 = get_tier2_checks()
        t3 = get_tier3_checks()
        t4 = get_tier4_checks()
        all_checks = get_all_checks()

        self.assertGreaterEqual(len(t1), 80)
        self.assertGreaterEqual(len(t2), 80)
        self.assertGreaterEqual(len(t3), 16)
        self.assertGreaterEqual(len(t4), 8)
        self.assertEqual(len(t4), 16)
        self.assertGreaterEqual(len(all_checks), 184)

        ids = [c.check_id for c in all_checks]
        self.assertEqual(len(ids), len(set(ids)), "Duplicate check_id found")

        for i in range(1, 17):
            feat = f"F{i}"
            t1_f = [c for c in t1 if c.feature == feat]
            t2_f = [c for c in t2 if c.feature == feat]
            self.assertGreaterEqual(len(t1_f), 5, f"{feat} T1 < 5")
            self.assertGreaterEqual(len(t2_f), 5, f"{feat} T2 < 5")

        t4_ids = {c.check_id for c in t4}
        for i in range(1, 17):
            self.assertIn(f"T4.S{i}", t4_ids)

    def test_list_cli_prints_all_16_features(self) -> None:
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = cli_main(["--list"])
        self.assertEqual(rc, 0)
        out = buf.getvalue()
        for i in range(1, 17):
            self.assertIn(f"F{i}\t", out)
        self.assertIn("Total=", out)

    def test_exit_code_evaluation(self) -> None:
        pass_o = ReleaseCheckOutcome("A", "F1", 1, "t", True, "s", "PASS")
        blk_o = ReleaseCheckOutcome("B", "F2", 1, "t", True, "s", "BLOCKED_CAPABILITY")
        fail_o = ReleaseCheckOutcome("C", "F3", 1, "t", True, "s", "FAIL")
        self.assertEqual(evaluate_exit_code([pass_o, blk_o], strict_capabilities=False), 0)
        self.assertEqual(evaluate_exit_code([pass_o, blk_o], strict_capabilities=True), 1)
        self.assertEqual(evaluate_exit_code([pass_o, fail_o], strict_capabilities=False), 1)


# ---------------------------------------------------------------------------
# 2. Safety Guards & Redaction
# ---------------------------------------------------------------------------


class TestSafetyGuards(unittest.TestCase):
    def test_local_mode_refuses_non_loopback_urls_with_exit_2(self) -> None:
        rc1 = cli_main(["--mode", "local", "--base-url", "https://api.nexachat.io.vn"])
        self.assertEqual(rc1, 2)
        rc2 = cli_main(["--mode", "local", "--site-url", "https://nexachat.io.vn"])
        self.assertEqual(rc2, 2)
        validate_target_safety("local", "http://127.0.0.1:8080", "http://localhost:8081")
        self.assertTrue(is_loopback_url("http://127.0.0.1:8080"))
        self.assertTrue(is_loopback_url("http://localhost:3000"))
        self.assertFalse(is_loopback_url("https://api.nexachat.io.vn"))

    def test_ephemeral_phone_guard(self) -> None:
        self.assertEqual(assert_ephemeral_phone("+84908123456"), "+84908123456")
        for bad in ("+84900000951", "+84901234567", "+15551234567", "0908123456", ""):
            with self.assertRaises(SafetyGuardError):
                assert_ephemeral_phone(bad)

    def test_live_write_guard_blocks_admin_mutations(self) -> None:
        for bad_method, bad_path in (
            ("PATCH", "/api/v1/admin/social/settings/flags"),
            ("POST", "/api/v1/admin"),
            ("POST", "//api/v1/admin/social/settings/flags"),
            ("POST", "/api/v1/./admin"),
            ("POST", "/api/v1/foo/../admin"),
            ("DELETE", "/API/V1/ADMIN/users"),
            ("PATCH", "/api/social/v1/admin"),
            ("PATCH", "/api/social/v1/./foo/../ADMIN/settings"),
            ("DELETE", "//api/social/v1/admin/rules/1"),
        ):
            with self.assertRaises(SafetyGuardError):
                assert_live_write_allowed(bad_method, bad_path)
        with self.assertRaises(SafetyGuardError):
            assert_live_write_allowed("POST", "/api/v1/users/me", phone="+84900000951")
        assert_live_write_allowed("GET", "/api/v1/admin/overview")
        assert_live_write_allowed("DELETE", "/api/v1/users/me", phone="+84908654321")

    def test_live_mode_filters_out_non_live_safe_checks(self) -> None:
        all_checks = get_all_checks()
        runnable, skipped = filter_live_safe_checks(all_checks)
        self.assertTrue(all(c.live_safe for c in runnable))
        self.assertTrue(any(not c.live_safe for c in skipped))

        called: List[str] = []
        fake_ctx = ReleaseQaContext(spring=SocialApiClient("https://api.nexachat.io.vn"), base_url="https://api.nexachat.io.vn")
        sample = [
            ReleaseCheck("X1", "F1", 1, "safe", True, "test", lambda c: called.append("X1")),
            ReleaseCheck("X2", "F13", 1, "mutating", False, "test", lambda c: called.append("X2")),
        ]
        outcomes = _execute_checks(sample, fake_ctx, live_mode=True)
        self.assertEqual(called, ["X1"])
        self.assertEqual(outcomes[0].status, "PASS")
        self.assertEqual(outcomes[1].status, "SKIPPED_LIVE_GUARD")

    def test_redact_secrets_scrubs_jwt_bearer_password_and_non_ephemeral_phones(self) -> None:
        raw = (
            'Authorization: Bearer secret_token_value_12345 '
            'jwt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSJ9.sig12345678 '
            '{"password": "MySecretPassword!"} '
            'fixture=+84900000951 eph=+84908123456'
        )
        scrubbed = redact_secrets(raw)
        self.assertNotIn("secret_token_value_12345", scrubbed)
        self.assertNotIn("eyJhbGciOiJIUzI1NiJ9", scrubbed)
        self.assertNotIn("MySecretPassword!", scrubbed)
        self.assertNotIn("+84900000951", scrubbed)
        self.assertIn("+84908123456", scrubbed)

        escaped_json = '{"password": "My \\"Quoted\\" Pass!"}'
        scrubbed_escaped = redact_secrets(escaped_json)
        self.assertEqual(scrubbed_escaped, '{"password": "[REDACTED_PASSWORD]"}')
        self.assertNotIn("Quoted", scrubbed_escaped)
        self.assertNotIn("Pass!", scrubbed_escaped)

        pw_change_json = '{"oldPassword": "Old\\"Secret1", "newPassword": "New\\"Secret2"}'
        scrubbed_pw_change = redact_secrets(pw_change_json)
        self.assertEqual(
            scrubbed_pw_change,
            '{"oldPassword": "[REDACTED_PASSWORD]", "newPassword": "[REDACTED_PASSWORD]"}',
        )
        self.assertNotIn("Old", scrubbed_pw_change)
        self.assertNotIn("New", scrubbed_pw_change)


# ---------------------------------------------------------------------------
# 3. Mock HTTP Server & Contract Tests (No network/Docker)
# ---------------------------------------------------------------------------


class _MockBackendState:
    def __init__(self) -> None:
        self.users_by_phone: Dict[str, dict] = {}
        self.users_by_token: Dict[str, dict] = {}
        self.refresh_tokens: Dict[str, dict] = {}
        self.deleted_phones: List[str] = []
        self.login_failures: Dict[str, int] = {}
        self.scheduled_ids: List[str] = []
        self.direct_convs: Dict[Tuple[str, str], str] = {}
        self.messages_by_conv: Dict[str, List[dict]] = {}
        self.labels: Dict[str, dict] = {}
        self.glossaries: Dict[str, List[dict]] = {}
        self.password_changes: List[dict] = []
        self.settings: Dict[str, Any] = {
            "showPresence": True,
            "showSeen": True,
            "aiSummaryConsent": False,
            "translateTargetLang": "vi",
            "streakReminders": False,
        }
        self.seq = 0
        self.conv_counter = 0
        self.token_counter = 0


def _make_mock_handler(state: _MockBackendState):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format: str, *args: Any) -> None:
            return

        def _send_json(self, status: int, obj: Any, extra_headers: Dict[str, str] | None = None) -> None:
            raw = b"" if obj is None else json.dumps(obj).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            if extra_headers:
                for k, v in extra_headers.items():
                    self.send_header(k, v)
            self.end_headers()
            if raw:
                self.wfile.write(raw)

        def _read_json(self) -> dict:
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0:
                return {}
            return json.loads(self.rfile.read(length).decode("utf-8"))

        def _bearer_user(self) -> dict | None:
            auth = self.headers.get("Authorization") or ""
            if auth.startswith("Bearer "):
                return state.users_by_token.get(auth[7:])
            return None

        def do_POST(self) -> None:
            path = self.path.split("?", 1)[0]
            body = self._read_json()

            if path == "/api/v1/auth/register":
                phone = body["phone"]
                uid = f"00000000-0000-4000-8000-{len(state.users_by_phone) + 1:012d}"
                state.token_counter += 1
                tok = f"tok-{state.token_counter}"
                rt = f"ref-{state.token_counter}"
                rec = {
                    "id": uid,
                    "phone": phone,
                    "password": body["password"],
                    "displayName": body["displayName"],
                    "token": tok,
                }
                state.users_by_phone[phone] = rec
                state.users_by_token[tok] = rec
                state.refresh_tokens[rt] = {"user": rec, "revoked": False}
                self._send_json(
                    201,
                    {
                        "accessToken": tok,
                        "refreshToken": rt,
                        "user": {"id": uid, "phone": phone, "displayName": rec["displayName"]},
                    },
                )
                return

            if path == "/api/v1/auth/login":
                ident = body.get("identifier", "")
                pw = body.get("password", "")
                rec = state.users_by_phone.get(ident)
                if not rec or rec["password"] != pw:
                    cnt = state.login_failures.get(ident, 0) + 1
                    state.login_failures[ident] = cnt
                    if cnt >= 5:
                        self._send_json(
                            429,
                            {"error": {"code": "SIGN_IN_LOCKED", "details": {"scope": "account", "retryAfterSeconds": 900}}},
                            {"Retry-After": "900"},
                        )
                        return
                    self._send_json(401, {"error": {"code": "INVALID_CREDENTIALS"}})
                    return
                state.token_counter += 1
                tok = f"tok-{state.token_counter}"
                rt = f"ref-{state.token_counter}"
                state.users_by_token[tok] = rec
                state.refresh_tokens[rt] = {"user": rec, "revoked": False}
                self._send_json(200, {"accessToken": tok, "refreshToken": rt})
                return

            if path == "/api/v1/auth/refresh":
                rt_in = body.get("refreshToken", "")
                entry = state.refresh_tokens.get(rt_in)
                if not entry or entry["revoked"]:
                    self._send_json(401, {"error": {"code": "TOKEN_INVALID"}})
                    return
                entry["revoked"] = True
                rec = entry["user"]
                state.token_counter += 1
                tok = f"tok-{state.token_counter}"
                rt_new = f"ref-{state.token_counter}"
                state.users_by_token[tok] = rec
                state.refresh_tokens[rt_new] = {"user": rec, "revoked": False}
                self._send_json(200, {"accessToken": tok, "refreshToken": rt_new})
                return

            if path == "/api/v1/labels":
                user = self._bearer_user()
                if user is None:
                    self._send_json(401, {"error": {"code": "UNAUTHORIZED"}})
                    return
                name = (body.get("name") or "").strip()
                color_id = body.get("colorId")
                valid_colors = {"INDIGO", "BLUE", "CYAN", "TEAL", "EMERALD", "AMBER", "ROSE", "PURPLE"}
                if not name or color_id not in valid_colors:
                    self._send_json(400, {"error": {"code": "VALIDATION_FAILED"}})
                    return
                lid = f"33333333-3333-4333-8333-{len(state.labels) + 1:012d}"
                lbl = {"id": lid, "userId": user["id"], "name": name, "colorId": color_id}
                state.labels[lid] = lbl
                self._send_json(201, {"id": lid, "name": name, "colorId": color_id})
                return

            if path in ("/api/v1/auth/google/nonce", "/api/v1/auth/google", "/api/v1/auth/identities/google/nonce", "/api/v1/auth/identities/google"):
                self._send_json(404, {"error": {"code": "NOT_FOUND"}})
                return

            if path == "/api/v1/conversations/direct":
                user = self._bearer_user()
                p1 = user["phone"] if user else ""
                p2 = body.get("phone", "")
                key = (min(p1, p2), max(p1, p2))
                cid = state.direct_convs.get(key)
                if not cid:
                    state.conv_counter += 1
                    cid = f"11111111-1111-4111-8111-{state.conv_counter:012d}"
                    state.direct_convs[key] = cid
                    state.messages_by_conv[cid] = []
                self._send_json(200, {"id": cid, "type": "DIRECT"})
                return

            if path.endswith("/messages"):
                user = self._bearer_user()
                cid = path.split("/")[4]
                state.seq += 1
                msg = {
                    "id": f"msg-{state.seq}",
                    "seq": state.seq,
                    "content": body.get("content", ""),
                    "senderName": user["displayName"] if user else "",
                }
                state.messages_by_conv.setdefault(cid, []).append(msg)
                self._send_json(201, msg)
                return

            if path.endswith("/scheduled-messages"):
                if len(state.scheduled_ids) >= 50:
                    self._send_json(409, {"error": {"code": "SCHEDULED_LIMIT", "details": {"limit": 50}}})
                    return
                sid = f"sched-{len(state.scheduled_ids) + 1}"
                state.scheduled_ids.append(sid)
                self._send_json(201, {"id": sid})
                return

            if path == "/api/v1/sync":
                wm = (body.get("watermarks") or [{}])[0]
                cid = wm.get("conversationId", "")
                gen = wm.get("generation")
                cur = wm.get("changeCursor", 0)
                upper = 2
                should_reset = (
                    (gen is not None and gen != 1)
                    or cur > upper
                    or (cur > 0 and gen is None)
                )
                self._send_json(
                    200,
                    {
                        "syncVersion": 2,
                        "deltas": [
                            {
                                "conversationId": cid,
                                "generation": 1,
                                "changeUpperBound": upper,
                                "changeCursor": 0 if should_reset else cur,
                                "changeHasMore": False,
                                "reset": should_reset,
                                "messages": [] if should_reset else state.messages_by_conv.get(cid, []),
                            }
                        ],
                    },
                )
                return

            if path == "/api/v1/conversations/group":
                state.conv_counter += 1
                gid = f"22222222-2222-4222-8222-{state.conv_counter:012d}"
                state.messages_by_conv[gid] = []
                self._send_json(201, {"id": gid, "type": "GROUP"})
                return

            if path.endswith("/ask"):
                cid = path.split("/")[4]
                conv_msgs = state.messages_by_conv.get(cid, [])
                q = body.get("question", "")
                if "Minh Trâm" in q:
                    mt_msgs = [m for m in conv_msgs if m["senderName"] == "Minh Trâm" and "phòng Hoa Sữa" in m["content"]]
                    if mt_msgs:
                        hit = mt_msgs[0]
                        self._send_json(
                            200,
                            {
                                "mode": "EXTRACTIVE",
                                "answer": "Minh Trâm nói sẽ trình bày bản demo ở phòng Hoa Sữa.",
                                "citations": [{"messageId": hit["id"], "seq": hit["seq"]}],
                            },
                        )
                        return
                if "Tú Anh" in q and "nghiệm thu" in q:
                    ta_msgs = [m for m in conv_msgs if m["senderName"] == "Tú Anh" and "16 tháng 10" in m["content"]]
                    if ta_msgs:
                        hit = ta_msgs[0]
                        self._send_json(200, {"mode": "EXTRACTIVE", "answer": "16 tháng 10", "citations": [{"messageId": hit["id"], "seq": hit["seq"]}]})
                        return
                if "Tú Anh" in q and "đón đoàn" in q:
                    ta_places = [m for m in conv_msgs if m["senderName"] == "Tú Anh" and "đón đoàn" in m["content"]]
                    if len(ta_places) == 1 and "Cửa Nam" in ta_places[0]["content"]:
                        hit = ta_places[0]
                        self._send_json(200, {"mode": "EXTRACTIVE", "answer": "Cửa Nam", "citations": [{"messageId": hit["id"], "seq": hit["seq"]}]})
                        return
                self._send_json(200, {"mode": "NOT_FOUND", "notFoundReason": "NO_EVIDENCE", "citations": []})
                return

            self._send_json(404, {"error": {"code": "NOT_FOUND"}})

        def do_PUT(self) -> None:
            path = self.path.split("?", 1)[0]
            body = self._read_json()
            user = self._bearer_user()
            if user is None:
                self._send_json(401, {"error": {"code": "UNAUTHORIZED"}})
                return
            if "/labels/" in path:
                lid = path.rsplit("/", 1)[-1]
                lbl = state.labels.get(lid)
                if not lbl or lbl["userId"] != user["id"]:
                    self._send_json(404, {"error": {"code": "NOT_FOUND"}})
                    return
                self._send_json(204, None)
                return
            if path.endswith("/translation-glossary"):
                cid = path.split("/")[4]
                terms = body.get("terms")
                if not isinstance(terms, list):
                    self._send_json(400, {"error": {"code": "VALIDATION_FAILED"}})
                    return
                for item in terms:
                    if not isinstance(item, dict) or not (item.get("term") or "").strip() or "replacement" not in item:
                        self._send_json(400, {"error": {"code": "VALIDATION_FAILED"}})
                        return
                state.glossaries[cid] = terms
                self._send_json(200, {"terms": terms})
                return
            self._send_json(404, {"error": {"code": "NOT_FOUND"}})

        def do_GET(self) -> None:
            path = self.path.split("?", 1)[0]
            if path in ("/api/v1/auth/identities",):
                self._send_json(404, {"error": {"code": "NOT_FOUND"}})
                return
            if path.endswith("/translation-glossary"):
                cid = path.split("/")[4]
                self._send_json(200, {"terms": state.glossaries.get(cid, [])})
                return
            if path.endswith("/streak"):
                cid = path.split("/")[4]
                user = self._bearer_user()
                if user is None:
                    self._send_json(401, {"error": {"code": "UNAUTHORIZED"}})
                    return
                if cid.startswith("22222222"):
                    self._send_json(400, {"error": {"code": "VALIDATION_FAILED"}})
                    return
                if "Listener" in user["displayName"] or "Out" in user["displayName"]:
                    self._send_json(403, {"error": {"code": "NOT_MEMBER"}})
                    return
                self._send_json(200, {"count": 1, "bestCount": 1})
                return
            self._send_json(404, {"error": {"code": "NOT_FOUND"}})

        def do_PATCH(self) -> None:
            path = self.path.split("?", 1)[0]
            body = self._read_json()
            if path == "/api/v1/auth/password":
                user = self._bearer_user()
                if user is None:
                    self._send_json(401, {"error": {"code": "UNAUTHORIZED"}})
                    return
                old_pw = body.get("oldPassword", "")
                new_pw = body.get("newPassword", "")
                if old_pw != user["password"]:
                    self._send_json(400, {"error": {"code": "WRONG_PASSWORD"}})
                    return
                if not isinstance(new_pw, str) or len(new_pw) < 8:
                    self._send_json(400, {"error": {"code": "VALIDATION_FAILED"}})
                    return
                user["password"] = new_pw
                keep_rt = (body.get("refreshToken") or "").strip()
                state.password_changes.append({"userId": user["id"], "kept": bool(keep_rt)})
                for rt, entry in state.refresh_tokens.items():
                    if entry["user"]["id"] == user["id"] and rt != keep_rt:
                        entry["revoked"] = True
                self._send_json(204, None)
                return
            if path == "/api/v1/users/me/settings":
                state.settings.update(body)
                self._send_json(200, state.settings)
                return
            self._send_json(404, {"error": {"code": "NOT_FOUND"}})

        def do_DELETE(self) -> None:
            path = self.path.split("?", 1)[0]
            if path == "/api/v1/auth/identities/google":
                self._send_json(404, {"error": {"code": "NOT_FOUND"}})
                return
            if path.startswith("/api/v1/labels/"):
                lid = path.rsplit("/", 1)[-1]
                state.labels.pop(lid, None)
                self._send_json(204, None)
                return
            if path == "/api/v1/users/me":
                user = self._bearer_user()
                if user:
                    state.deleted_phones.append(user["phone"])
                    for tok, u in list(state.users_by_token.items()):
                        if u["id"] == user["id"]:
                            state.users_by_token.pop(tok, None)
                self._send_json(204, None)
                return
            if "/scheduled-messages/" in path:
                sid = path.rsplit("/", 1)[-1]
                if sid in state.scheduled_ids:
                    state.scheduled_ids.remove(sid)
                self._send_json(204, None)
                return
            self._send_json(404, {"error": {"code": "NOT_FOUND"}})

    return Handler


class TestMockHttpAndRagContracts(unittest.TestCase):
    def setUp(self) -> None:
        self.state = _MockBackendState()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), _make_mock_handler(self.state))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.ctx = ReleaseQaContext(spring=SocialApiClient(self.base_url), base_url=self.base_url)

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()

    def test_sign_in_locked_google_404_sched_50_sync_v2_and_pr384_ask(self) -> None:
        t1 = {c.check_id: c for c in get_tier1_checks()}
        t2 = {c.check_id: c for c in get_tier2_checks()}

        t2["T2.F1.05"].fn(self.ctx)  # SIGN_IN_LOCKED 429
        t1["T1.F2.01"].fn(self.ctx)  # Google nonce 404
        t1["T1.F2.02"].fn(self.ctx)  # Google signin 404
        t2["T2.F4.04"].fn(self.ctx)  # Scheduled 50 cap + cancel + re-POST
        t2["T2.F4.05"].fn(self.ctx)  # Sync v2 generation/cursor/missing reset
        t1["T1.F9.04"].fn(self.ctx)  # PR #384 Ask RAG positive citations
        t2["T2.F9.04"].fn(self.ctx)  # PR #384 Ask RAG abstentions
        t1["T1.F10.03"].fn(self.ctx)  # Reciprocal direct chat streak >= 1
        t2["T2.F10.01"].fn(self.ctx)  # Streak 401/403 NOT_MEMBER/group error

        # Clear messages before abstention vs positive sequence check
        self.assertGreaterEqual(len(self.state.deleted_phones), 6)
        for ph in self.state.deleted_phones:
            self.assertRegex(ph, r"^\+84908\d{6}$")

    def test_ephemeral_cleanup_runs_on_check_failure(self) -> None:
        def failing_check(ctx: ReleaseQaContext) -> None:
            ctx.register_ephemeral("Cleanup On Fail")
            raise E2EFailure("boom")

        before = len(self.state.deleted_phones)
        outcomes = _execute_checks(
            [ReleaseCheck("FAIL.1", "F1", 1, "boom", True, "test", failing_check)],
            self.ctx,
            live_mode=False,
        )
        self.assertEqual(outcomes[0].status, "FAIL")
        self.assertEqual(len(self.state.deleted_phones), before + 1)
        report = build_json_report(outcomes, mode="local")
        self.assertEqual(report["summary"]["total"], 1)

    def test_password_change_labels_and_glossary_contracts(self) -> None:
        t1 = {c.check_id: c for c in get_tier1_checks()}
        t3 = {c.check_id: c for c in get_tier3_checks()}

        t1["T1.F1.05"].fn(self.ctx)  # PATCH /api/v1/auth/password (omitting refreshToken -> revokes all)
        t3["T3.01"].fn(self.ctx)     # PATCH /api/v1/auth/password (keeping device 2 refreshToken)
        t1["T1.F5.02"].fn(self.ctx)  # POST /api/v1/labels with colorId=PURPLE
        t1["T1.F9.03"].fn(self.ctx)  # PUT/GET translation-glossary with term/replacement

        self.assertEqual(len(self.state.password_changes), 2)
        self.assertFalse(self.state.password_changes[0]["kept"])
        self.assertTrue(self.state.password_changes[1]["kept"])

        # Negative contract proof: legacy payload keys must fail 400
        acc = self.ctx.register_ephemeral("Neg DTO")
        try:
            bad_lbl = self.ctx.spring.post(
                "/api/v1/labels",
                token=acc["accessToken"],
                body={"name": "Bad", "color": "#733F68"},
            )
            self.assertEqual(bad_lbl.status, 400)
            cid = self.ctx.open_direct(acc["accessToken"], acc["phone"])
            bad_gl = self.ctx.spring.put(
                f"/api/v1/conversations/{cid}/translation-glossary",
                token=acc["accessToken"],
                body={"terms": [{"source": "NexaChat", "target": "NexaChat"}]},
            )
            self.assertEqual(bad_gl.status, 400)
        finally:
            self.ctx.cleanup_all_ephemeral()


# ---------------------------------------------------------------------------
# 4. Mock Playwright Design Math & ADB/Assetlinks Contracts
# ---------------------------------------------------------------------------


class TestMockPlaywrightAndAdbContracts(unittest.TestCase):
    def test_tonal_plum_design_math_and_touch_targets(self) -> None:
        self.assertEqual(effective_touch_target_height(32.0, has_expanded_hit_ring=True, inset_px=6.0), 44.0)
        self.assertEqual(effective_touch_target_height(48.0, has_expanded_hit_ring=False), 48.0)

        fg = parse_css_color("#ffffff")[:3]
        bg = parse_css_color("#733f68")[:3]
        self.assertGreaterEqual(contrast_ratio(fg, bg), 4.5)

        good_social = {
            "canvas": "rgb(255, 247, 252)",
            "primary": "#733f68",
            "container": "#fcd7f4",
            "text": "#1d1b1e",
            "fontFamily": "Inter, sans-serif",
            "cardRadius": "16px",
            "pillRadius": "999px",
        }
        self.assertEqual(audit_tonal_plum_tokens(good_social, scheme="light", surface="social"), [])

        slate_public = {"canvas": "#f8fafc", "primary": "#733f68"}
        self.assertEqual(audit_tonal_plum_tokens(slate_public, scheme="light", surface="public", strict_public_plum=False), [])
        self.assertTrue(audit_tonal_plum_tokens(slate_public, scheme="light", surface="public", strict_public_plum=True))

    def test_apk_signer_matches_assetlinks_and_mismatch_fails(self) -> None:
        hex64 = "e7419a48a49d204e5570606eb833968dcda728e2ac99d7243b2cfe8c158a39df"
        colon = format_sha256_colon_upper(hex64)
        self.assertEqual(colon, "E7:41:9A:48:A4:9D:20:4E:55:70:60:6E:B8:33:96:8D:CD:A7:28:E2:AC:99:D7:24:3B:2C:FE:8C:15:8A:39:DF")

        assetlinks = [
            {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {"namespace": "android_app", "package_name": "com.nexachat", "sha256_cert_fingerprints": [colon]},
            }
        ]
        res = verify_apk_matches_assetlinks({"package": "com.nexachat", "signer_sha256": hex64}, assetlinks)
        self.assertEqual(res["signer_sha256"], colon)

        with self.assertRaises(E2EFailure):
            verify_apk_matches_assetlinks({"package": "com.nexachat", "signer_sha256": "00" * 32}, assetlinks)
        with self.assertRaises(E2EFailure):
            verify_apk_matches_assetlinks({"package": "com.nexachat.debug", "signer_sha256": hex64}, assetlinks)

    def test_adb_app_link_and_release_emulator_commands_and_runner(self) -> None:
        cmd_view = build_adb_app_link_cmd("emulator-5554", "https://nexachat.io.vn/join/abc", package="com.nexachat")
        self.assertEqual(
            cmd_view,
            ["adb", "-s", "emulator-5554", "shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", "https://nexachat.io.vn/join/abc", "-p", "com.nexachat"],
        )

        emu_cmd = build_release_emulator_cmd(
            serial="emulator-5554",
            apk_path="nexachat-release.apk",
            api_base_url="https://api.nexachat.io.vn",
            screenshots_dir="screens",
        )
        for flag in ("--package", "com.nexachat", "--api-base-url", "https://api.nexachat.io.vn", "--apk", "nexachat-release.apk", "--no-build-apk", "--verify-app-link", "--i-know-this-is-not-prod"):
            self.assertIn(flag, emu_cmd)

        hex64 = "e7419a48a49d204e5570606eb833968dcda728e2ac99d7243b2cfe8c158a39df"
        colon = format_sha256_colon_upper(hex64)
        assetlinks = [{"relation": ["delegate_permission/common.handle_all_urls"], "target": {"namespace": "android_app", "package_name": "com.nexachat", "sha256_cert_fingerprints": [colon]}}]

        with tempfile.TemporaryDirectory(prefix="nexachat_apk_test_") as tmp_dir:
            fake_apk = Path(tmp_dir) / "nexachat-release.apk"
            fake_apk.write_bytes(b"PK\x03\x04fakeapk")
            ctx = ReleaseQaContext(
                spring=SocialApiClient("https://api.nexachat.io.vn"),
                base_url="https://api.nexachat.io.vn",
                site_url="https://nexachat.io.vn",
                android_enabled=True,
                apk_path=str(fake_apk),
            )
            calls: List[List[str]] = []

            def fake_runner(argv: List[str], **kw: Any) -> subprocess.CompletedProcess:
                calls.append(list(argv))
                if argv[1:4] == ["-s", "emulator-5554", "get-state"]:
                    return subprocess.CompletedProcess(argv, 0, stdout="device\n", stderr="")
                if "install" in argv:
                    return subprocess.CompletedProcess(argv, 0, stdout="Success\n", stderr="")
                if "am" in argv:
                    return subprocess.CompletedProcess(argv, 0, stdout="Status: ok\nActivity: com.nexachat/.MainActivity\n", stderr="")
                return subprocess.CompletedProcess(argv, 0, stdout='{"status": "PASS", "crash_count": 0}\n', stderr="")

            res = verify_live_release_apk(
                ctx,
                cmd_runner=fake_runner,
                apk_describer=lambda _p: {"package": "com.nexachat", "signer_sha256": hex64},
                assetlinks_fetcher=lambda _u: assetlinks,
            )
            self.assertEqual(res["emulator"], "PASS")
            self.assertEqual(len(calls), 4)

            # Offline ADB device -> BlockedCapability
            def offline_runner(argv: List[str], **kw: Any) -> subprocess.CompletedProcess:
                return subprocess.CompletedProcess(argv, 1, stdout="", stderr="error: device 'emulator-5554' not found")

            with self.assertRaises(BlockedCapability):
                verify_live_release_apk(
                    ctx,
                    cmd_runner=offline_runner,
                    apk_describer=lambda _p: {"package": "com.nexachat", "signer_sha256": hex64},
                    assetlinks_fetcher=lambda _u: assetlinks,
                )

            # Chrome browser fallback on exit 0 MUST FAIL (no tautological returncode==0 bypass)
            def chrome_fallback_runner(argv: List[str], **kw: Any) -> subprocess.CompletedProcess:
                if argv[1:4] == ["-s", "emulator-5554", "get-state"]:
                    return subprocess.CompletedProcess(argv, 0, stdout="device\n", stderr="")
                if "install" in argv:
                    return subprocess.CompletedProcess(argv, 0, stdout="Success\n", stderr="")
                if "am" in argv:
                    return subprocess.CompletedProcess(
                        argv,
                        0,
                        stdout="Warning: Activity not started, resolved to com.android.chrome\n",
                        stderr="",
                    )
                return subprocess.CompletedProcess(argv, 0, stdout='{"status": "PASS", "crash_count": 0}\n', stderr="")

            with self.assertRaises(E2EFailure):
                verify_live_release_apk(
                    ctx,
                    cmd_runner=chrome_fallback_runner,
                    apk_describer=lambda _p: {"package": "com.nexachat", "signer_sha256": hex64},
                    assetlinks_fetcher=lambda _u: assetlinks,
                )

    def test_static_repo_checks_pass_and_detect_missing_android_tokens(self) -> None:
        t1 = {c.check_id: c for c in get_tier1_checks()}
        t3 = {c.check_id: c for c in get_tier3_checks()}
        ctx = ReleaseQaContext(spring=SocialApiClient("http://127.0.0.1:8080"), base_url="http://127.0.0.1:8080")

        # Real repo assets must pass T1.F15.03, T1.F16.05, and T3.20
        t1["T1.F15.03"].fn(ctx)
        t1["T1.F16.05"].fn(ctx)
        t3["T3.20"].fn(ctx)

        # Removing an expected Android hex token in either light or night XML must fail
        with tempfile.TemporaryDirectory(prefix="nexachat_plum_repo_") as tmp_dir:
            root = Path(tmp_dir)
            (root / "site" / "social").mkdir(parents=True)
            (root / "admin-web" / "src").mkdir(parents=True)
            (root / "android" / "app" / "src" / "main" / "res" / "values").mkdir(parents=True)
            (root / "android" / "app" / "src" / "main" / "res" / "values-night").mkdir(parents=True)

            (root / "site" / "social" / "social.css").write_text("#fff7fc #733f68 #151316", encoding="utf-8")
            (root / "admin-web" / "src" / "styles.css").write_text("#fff7fc #733f68 #151316", encoding="utf-8")
            (root / "site" / "style.css").write_text("#f8fafc #733f68", encoding="utf-8")
            light_xml_path = root / "android" / "app" / "src" / "main" / "res" / "values" / "colors_nexachat.xml"
            night_xml_path = root / "android" / "app" / "src" / "main" / "res" / "values-night" / "colors_nexachat.xml"

            valid_light = "<resources><color>#FFFFF7FC</color><color>#FF733F68</color><color>#FFFCD7F4</color></resources>"
            valid_night = "<resources><color>#FF151316</color><color>#FFF4B2E2</color><color>#FF67355D</color></resources>"

            with (
                patch.object(tier1_2_platform_api, "REPO_ROOT", root),
                patch.object(tier3_4_cross_surface, "REPO_ROOT", root),
            ):
                light_xml_path.write_text(valid_light, encoding="utf-8")
                night_xml_path.write_text(valid_night, encoding="utf-8")
                t1["T1.F15.03"].fn(ctx)
                t3["T3.20"].fn(ctx)

                # Missing light token (#FF733F68) fails both T1.F15.03 and T3.20
                light_xml_path.write_text("<resources><color>#FFFFF7FC</color><color>#FFFCD7F4</color></resources>", encoding="utf-8")
                with self.assertRaises(E2EFailure):
                    t1["T1.F15.03"].fn(ctx)
                with self.assertRaises(E2EFailure):
                    t3["T3.20"].fn(ctx)

                # Restore light, remove night #FF151316 -> both T1.F15.03 and T3.20 fail (no #151316 bypass)
                light_xml_path.write_text(valid_light, encoding="utf-8")
                night_xml_path.write_text("<resources><color>#FFF4B2E2</color><color>#FF67355D</color></resources>", encoding="utf-8")
                with self.assertRaises(E2EFailure):
                    t1["T1.F15.03"].fn(ctx)
                with self.assertRaises(E2EFailure):
                    t3["T3.20"].fn(ctx)

                # Missing night #FFF4B2E2 fails T1.F15.03
                night_xml_path.write_text("<resources><color>#FF151316</color><color>#FF67355D</color></resources>", encoding="utf-8")
                with self.assertRaises(E2EFailure):
                    t1["T1.F15.03"].fn(ctx)

    def test_t4_s15_public_site_note_monogram_rule(self) -> None:
        site_dir = REPO_ROOT / "site"
        favicon_svg = (site_dir / "favicon.svg").read_text(encoding="utf-8")
        pages_by_path = {
            "/": (site_dir / "index.html").read_text(encoding="utf-8"),
            "/download.html": (site_dir / "download.html").read_text(encoding="utf-8"),
            "/delete-account.html": (site_dir / "delete-account.html").read_text(encoding="utf-8"),
            "/join/valid_token": (site_dir / "join" / "index.html").read_text(encoding="utf-8"),
            "/privacy.html": (site_dir / "privacy.html").read_text(encoding="utf-8"),
            "/terms.html": (site_dir / "terms.html").read_text(encoding="utf-8"),
        }

        class _FakeApiResp:
            def __init__(self, status: int, body: str) -> None:
                self.status = status
                self._body = body

            def text(self) -> str:
                return self._body

        class _FakeRequest:
            def __init__(self, status: int, body: str) -> None:
                self._status = status
                self._body = body
                self.requested_urls: List[str] = []

            def get(self, url: str) -> _FakeApiResp:
                self.requested_urls.append(url)
                return _FakeApiResp(self._status, self._body)

        class _FakePage:
            def __init__(self, html: str, fav_status: int = 200, fav_body: str = favicon_svg) -> None:
                self._html = html
                self.request = _FakeRequest(fav_status, fav_body)

            def content(self) -> str:
                return self._html

        site_base = "https://nexachat.io.vn"
        for url in build_public_site_urls(site_base, "valid_token"):
            path = "/" if url == site_base + "/" else url[len(site_base) :]
            fake_page = _FakePage(pages_by_path[path])
            verify_public_page_note_monogram(fake_page, url, site_base)
            if path in ("/delete-account.html", "/privacy.html", "/terms.html"):
                self.assertEqual(fake_page.request.requested_urls, [f"{site_base}/favicon.svg"])
            else:
                self.assertEqual(fake_page.request.requested_urls, [])

        # Lockup pages (/, /download.html, /join/) require inline NOTE_MONOGRAM_PATH
        with self.assertRaises(E2EFailure):
            verify_public_page_note_monogram(_FakePage("<html><body>No SVG</body></html>"), f"{site_base}/", site_base)
        with self.assertRaises(E2EFailure):
            verify_public_page_note_monogram(
                _FakePage('<html><head><link rel="icon" href="/favicon.svg"></head></html>'),
                f"{site_base}/download.html",
                site_base,
            )
        with self.assertRaises(E2EFailure):
            verify_public_page_note_monogram(
                _FakePage('<html><head><link rel="icon" href="/favicon.svg"></head></html>'),
                f"{site_base}/join/valid_token",
                site_base,
            )

        # Prose pages require <link rel="icon" href="/favicon.svg"> + 200 SVG containing NOTE_MONOGRAM_PATH
        with self.assertRaises(E2EFailure):
            verify_public_page_note_monogram(
                _FakePage("<html><head></head><body>Delete</body></html>"),
                f"{site_base}/delete-account.html",
                site_base,
            )
        with self.assertRaises(E2EFailure):
            verify_public_page_note_monogram(
                _FakePage(pages_by_path["/privacy.html"], fav_status=404, fav_body="Not Found"),
                f"{site_base}/privacy.html",
                site_base,
            )
        with self.assertRaises(E2EFailure):
            verify_public_page_note_monogram(
                _FakePage(pages_by_path["/terms.html"], fav_status=200, fav_body="<svg></svg>"),
                f"{site_base}/terms.html",
                site_base,
            )
        self.assertIn(NOTE_MONOGRAM_PATH, favicon_svg)


if __name__ == "__main__":
    unittest.main()
