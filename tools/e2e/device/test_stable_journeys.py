"""Offline safety and result-classification tests for stable_journeys."""

from __future__ import annotations

import hashlib
import json
import re
import sys
import tempfile
import unittest
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import stable_journeys as journeys
import patch_window
from e2e_common import Response


class SerialAndProductionGuardsTest(unittest.TestCase):
    def test_only_reserved_serial_is_accepted(self):
        self.assertEqual(journeys.validate_serial("emulator-5554"), "emulator-5554")
        for serial in ("emulator-5570", "emulator-5558"):
            with self.subTest(serial=serial), self.assertRaisesRegex(
                journeys.HarnessError, "never touch another device"
            ):
                journeys.validate_serial(serial)

    def test_production_url_is_refused_for_mutating_journeys(self):
        row = journeys.ROW_BY_ID["chat-send-receive"]
        with self.assertRaisesRegex(journeys.HarnessError, "refusing production"):
            journeys.validate_base_url("https://api.nexachat.io.vn", (row,), False)
        with self.assertRaisesRegex(journeys.HarnessError, "only rows explicitly marked read-only"):
            journeys.validate_base_url("https://api.nexachat.io.vn", (row,), True)

    def test_base_url_rejects_embedded_credentials_or_query(self):
        row = journeys.ROW_BY_ID["auth-login"]
        for url in (
            "http://user:secret@127.0.0.1:18093",
            "http://127.0.0.1:18093?token=secret",
        ):
            with self.subTest(url=url), self.assertRaises(journeys.HarnessError):
                journeys.validate_base_url(url, (row,), False)


class MatrixAndDryRunTest(unittest.TestCase):
    def test_phase08_and_candidate_c1_rows_are_registered_or_blocked(self):
        refs = {ref for row in journeys.ROWS for ref in row.matrix_refs}
        expected = {
            "phase-08:Auth", "phase-08:Chat", "phase-08:Media", "phase-08:Groups",
            "phase-08:Labels", "phase-08:Calls", "phase-08:Summary and ask",
            "phase-08:Sync/offline", "phase-08:Account deletion",
            "c1-5:Auth/session (login slice)", "c1-5:OTP/forgot password",
            "c1-5:Chat send/receive", "c1-5:Media", "c1-5:Groups", "c1-5:Polls",
            "c1-5:Reminders", "c1-5:Archive/labels/preferences", "c1-5:Invites",
            "c1-5:Calls K1", "c1-5:Summary/Ask", "c1-5:Account deletion",
            "c1-5:Offline/sync O1/O2",
        }
        self.assertTrue(expected.issubset(refs), sorted(expected - refs))
        runnable = {row.id for row in journeys.ROWS if row.capability_gap is None}
        self.assertEqual(runnable, {
            "auth-login", "chat-send-receive",
            "chat-interactions", "summary-ask", "otp-forgot-password",
            "archive-preferences", "groups-contacts", "labels-lifecycle",
            "polls-reminders", "offline-sync",
            "calls-injected-notification", "injected-notifications",
            "accessibility-sweep",
        })
        self.assertIn("REST session ops",
                      journeys.ROW_BY_ID["auth-session-lifecycle"].capability_gap)
        self.assertIn("STOMP hold",
                      journeys.ROW_BY_ID["auth-session-lifecycle"].capability_gap)
        self.assertEqual(journeys.ROWS[-1].id, "account-deletion")
        self.assertEqual(len(journeys.ACCESSIBILITY_VARIANTS), 8)
        for row in journeys.ROWS:
            self.assertTrue(row.id)
            self.assertTrue(row.preconditions)
            self.assertTrue(row.steps)
            self.assertTrue(row.assertions)
            self.assertTrue(row.captures)
            self.assertTrue(row.cleanup)
            self.assertTrue(all(len(item) > 1 for item in row.matrix_refs))
            self.assertTrue(all(len(item) > 1 for item in row.cleanup))

    def test_account_deletion_is_forced_to_last_in_selection(self):
        rows = journeys.select_rows("account-deletion,auth-login")
        self.assertEqual([row.id for row in rows], ["auth-login", "account-deletion"])

    def test_dry_run_touches_neither_adb_nor_rest(self):
        output = __import__("io").StringIO()
        with mock.patch("sys.stdout", output), mock.patch.object(journeys.subprocess, "run") as run:
            code = journeys.main([
                "--dry-run", "--rows", "auth-login,chat-send-receive",
                "--base-url", "http://127.0.0.1:18093",
            ])
        self.assertEqual(code, 0)
        run.assert_not_called()
        plan = json.loads(output.getvalue())
        self.assertFalse(plan["device_and_server_touched"])
        self.assertEqual([row["id"] for row in plan["rows"]], ["auth-login", "chat-send-receive"])
        self.assertEqual(plan["emulator_base_url"], "http://10.0.2.2:18093")
        for command in plan["preflight"]["adb_argv"]:
            self.assertEqual(command[:3], ["adb", "-s", "emulator-5554"])
        self.assertEqual(
            plan["preflight"]["rest"],
            [{"method": "GET", "url": "http://127.0.0.1:18093/actuator/health/readiness"}],
        )
        chat = plan["rows"][1]
        self.assertEqual(chat["stomp"]["url"], "ws://127.0.0.1:18093/ws")
        self.assertEqual(chat["cleanup"], ["Soft-delete only the two unique messages using their author sessions"])


class ResultClassificationTest(unittest.TestCase):
    class Response:
        def __init__(self, status, body=None):
            self.status = status
            self._body = body or {}

        def json(self):
            return self._body

    class ReadinessClient:
        def __init__(self, status):
            self.status = status

        def get(self, path):
            return ResultClassificationTest.Response(self.status)

    class NoAdbCalls:
        def __init__(self):
            self.calls = []

        def command(self, *args, **kwargs):
            self.calls.append(args)
            return "device"

    def test_readiness_503_is_environment_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app-debug.apk"
            apk.write_bytes(b"offline fixture")
            adb = self.NoAdbCalls()
            with self.assertRaisesRegex(journeys.EnvironmentFailure, "HTTP 503") as caught:
                journeys.preflight(
                    "http://127.0.0.1:18093", apk, adb,
                    self.ReadinessClient(503),
                )
        self.assertEqual(journeys._status_for_error(caught.exception), "FAIL_HARNESS_OR_ENVIRONMENT")
        self.assertEqual(adb.calls, [])

    def test_preflight_skips_shared_readiness_for_self_provisioned_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app-debug.apk"
            apk.write_bytes(b"offline fixture")

            class NoServer:
                def get(self, path):
                    raise AssertionError("readiness must not be probed")

            class BootedAdb:
                def command(self, *args, **kwargs):
                    if args == ("get-state",):
                        return "device"
                    if args[:3] == ("shell", "getprop", "sys.boot_completed"):
                        return "1"
                    if args[:3] == ("shell", "getprop", "ro.build.version.sdk"):
                        return "36"
                    raise AssertionError(args)

            # Must not raise: the --dstack mail rows provision their own backend.
            journeys.preflight("http://127.0.0.1:18093", apk, BootedAdb(),
                               NoServer(), require_shared_server=False)

    class FakeAdb:
        def command(self, *args, **kwargs):
            return ""

    class FakeDevice:
        def screenshot(self, path):
            Path(path).write_bytes(b"evidence")

    class FakePatch:
        @staticmethod
        def ui_nodes(device, timeout=8):
            return []

        @staticmethod
        def filtered_logcat(baseline):
            return [], False

    class FakeClient:
        records = []

    def _context(self, evidence_root):
        return {
            "evidence_root": Path(evidence_root),
            "adb": self.FakeAdb(),
            "device": self.FakeDevice(),
            "helpers": (None, None, self.FakePatch()),
            "client": self.FakeClient(),
            "stomp_events": [],
            "frame_reader": None,
        }

    def test_assertion_mismatch_with_captured_evidence_is_product_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "evidence"
            root.mkdir()
            calls = []

            def mismatch(row, context):
                calls.append(row.id)
                raise journeys.ProductFailure("expected marker was absent")

            results = journeys.execute_rows(
                [journeys.ROW_BY_ID["auth-login"]], self._context(root), mismatch
            )
            self.assertEqual(results[0]["status"], "FAIL_PRODUCT")
            self.assertEqual(len(calls), 2)  # exactly one rerun
            self.assertTrue((root / "auth-login" / "attempt-1" / "screen.png").is_file())
            self.assertEqual(results[0]["retry_count"], 1)

    def test_one_rerun_then_final_result_stands(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "evidence"
            root.mkdir()
            calls = []

            def fail_twice(row, context):
                calls.append(context["attempt"])
                raise journeys.HarnessError("offline fixture failed")

            result = journeys.execute_rows(
                [journeys.ROW_BY_ID["auth-login"]], self._context(root), fail_twice
            )[0]
        self.assertEqual(calls, [1, 2])
        self.assertEqual(result["status"], "FAIL_HARNESS_OR_ENVIRONMENT")
        self.assertEqual(result["retry_count"], 1)

    def test_settings_restore_runs_when_row_raises(self):
        calls = []

        class Patch:
            def restore_device_settings(self, before):
                calls.append("network-font-locale")

            def settings_snapshot(self):
                return {
                    "wifi_on": "1", "mobile_data": "1", "airplane_mode_on": "0",
                    "font_scale": "1.0", "system_locales": "en-US",
                }

        class Adb:
            def command(self, *args, **kwargs):
                calls.append(args)
                return "Night mode: yes"

        before = {
            "wifi_on": "1", "mobile_data": "1", "airplane_mode_on": "0",
            "font_scale": "1.0", "system_locales": "en-US", "night_mode": "Night mode: yes",
        }

        def failed_row():
            raise journeys.ProductFailure("assertion failed")

        with self.assertRaises(journeys.ProductFailure):
            journeys.with_settings_restored(
                failed_row, lambda: journeys.restore_settings(Adb(), Patch(), before)
            )
        self.assertIn("network-font-locale", calls)
        self.assertIn(("shell", "cmd", "uimode", "night", "yes"), calls)


class RedactionTest(unittest.TestCase):
    class Response:
        status = 200
        text = ""

        @staticmethod
        def json():
            return {
                "accessToken": "access-secret-value",
                "refreshToken": "refresh-secret-value",
                "passwordHash": "hash-secret-value",
                "note": "Bearer bearer-secret-value",
            }

    class Client:
        base_url = "http://127.0.0.1:18093"
        timeout = 15

        def request(self, method, path, **kwargs):
            return RedactionTest.Response()

    def test_authorization_and_sensitive_fields_are_redacted(self):
        client = journeys.RecordingClient(self.Client())
        client.post(
            "/api/v1/auth/login",
            token="header-secret-value",
            body={"password": "password-secret-value", "phone": "+84900000001", "code": "123456"},
        )
        transcript = json.dumps(client.records)
        for secret in (
            "header-secret-value", "access-secret-value", "refresh-secret-value",
            "hash-secret-value", "password-secret-value", "+84900000001", "123456",
            "bearer-secret-value",
        ):
            self.assertNotIn(secret, transcript)
        self.assertEqual(client.records[0]["headers"]["Authorization"], "[REDACTED]")
        self.assertEqual(client.records[0]["response"]["accessToken"], "[REDACTED]")


class _AccountDeletionNode:
    """Minimal uiautomator node shape used by the deletion device fixture."""

    def __init__(self, text="", desc="", rid="", cls="", box=None, clickable=True):
        self.text = text
        self.desc = desc
        self.cls = cls
        self.box = box or (0, 0, 320, 60)
        self.clickable = clickable
        self.attrs = {"resource-id": f"com.nexachat.debug:id/{rid}" if rid else ""}

    def strip(self):  # parity with the real Node helpers
        return self


class _AccountDeletionWorld:
    """Scripted app: login -> inbox -> profile -> delete-account screens."""

    WRONG_PASSWORD_TEXT = "Current password is incorrect"
    CODE_SENT_TEXT = ("We sent a confirmation code to your verified email address")

    def __init__(self, store):
        self.store = store
        self.screen = "login"
        self.account = None
        self.keyboard = False
        self.login_inputs = {}
        self.error_text = ""
        self.code_requested = False
        self.wrong_password_refused = False
        self.passwordless_fixture_applied = False
        self.deleted_via_code = False
        self.sql_log = []

    def cold_start(self):
        self.screen = "inbox" if self.account else "login"
        self.keyboard = False
        self.login_inputs = {}

    @property
    def inbox_titles(self):
        # Only the doomed account ever had the peer conversation; the
        # re-registered phone starts with a clean (non-cached) inbox.
        if (self.account and self.store.is_doomed(self.account["phone"])
                and not self.store.reused):
            return [self.store.peer_name]
        return []

    def tree(self):
        nodes = []
        if self.screen == "login":
            nodes.append(_AccountDeletionNode(
                rid="phoneInput", cls="android.widget.EditText",
                text=self.login_inputs.get("phoneInput", "")))
            nodes.append(_AccountDeletionNode(
                rid="passwordInput", cls="android.widget.EditText",
                text=self.login_inputs.get("passwordInput", "")))
            nodes.append(_AccountDeletionNode(text="Sign in", rid="primaryAction",
                                              cls="android.widget.Button"))
        elif self.screen == "inbox":
            nodes.append(_AccountDeletionNode(rid="inboxUserAvatar"))
            nodes.append(_AccountDeletionNode(rid="conversationList",
                                             box=(0, 300, 320, 620)))
            for index, title in enumerate(self.inbox_titles):
                nodes.append(_AccountDeletionNode(
                    text=title, box=(10, 310 + index * 2, 310, 312 + index * 2)))
        elif self.screen == "profile":
            nodes.append(_AccountDeletionNode(
                text=self.account["displayName"] if self.account else "",
                rid="profileName"))
            nodes.append(_AccountDeletionNode(rid="profileColumn",
                                             box=(0, 0, 320, 640)))
            nodes.append(_AccountDeletionNode(text="Sign out", rid="logoutRow"))
            nodes.append(_AccountDeletionNode(text="Delete account",
                                              rid="deleteAccountRow"))
        elif self.screen == "delete":
            nodes.append(_AccountDeletionNode(rid="deleteAccountToolbar"))
            if self.store.is_passwordless(self.account):
                nodes.append(_AccountDeletionNode(text="Send code",
                                                  rid="sendDeletionCode"))
                if self.code_requested:
                    nodes.append(_AccountDeletionNode(
                        text=self.CODE_SENT_TEXT, rid="deletionCodeSentMessage"))
                    nodes.append(_AccountDeletionNode(
                        rid="deletionCodeInput", cls="android.widget.EditText"))
            else:
                nodes.append(_AccountDeletionNode(rid="passwordInput",
                                                  cls="android.widget.EditText"))
            nodes.append(_AccountDeletionNode(text="Delete my account",
                                              rid="confirmDelete",
                                              cls="android.widget.Button"))
            if self.error_text:
                nodes.append(_AccountDeletionNode(text=self.error_text,
                                                  rid="inlineError"))
        return nodes


class _AccountDeletionDeviceFixture:
    """Device/adb/patch/client/mail fakes for the account-deletion executor."""

    PHONE = "+84993334445"
    EMAIL = "delete-device@example.test"
    PEER_NAME = "Journey Peer fixture"

    class Response:
        def __init__(self, status, body=None):
            self.status = status
            self._body = body if body is not None else {}

        def json(self):
            return self._body

    class Store:
        def __init__(self):
            self.users = {}          # phone -> {"password", "displayName", "deleted"}
            self.passwordless = set()
            self.doomed = set()
            self.reused = False
            self.deletion_code = "654321"
            self.messages = []

        def is_passwordless(self, account):
            return account is not None and account["phone"] in self.passwordless

        def is_doomed(self, phone):
            return phone in self.doomed

    class Mail:
        def __init__(self, codes):
            self.codes = list(codes)
            self.n = 0
            self.stopped = False

        def start(self):
            return None

        def stop(self):
            self.stopped = True

        def server_env(self):
            return {"NEXACHAT_MAIL_PROVIDER": "smtp",
                    "MAIL_SMTP_HOST": "127.0.0.1", "MAIL_SMTP_PORT": "1025",
                    "MAIL_SMTP_USERNAME": "", "MAIL_SMTP_PASSWORD": "",
                    "MAIL_FROM": "noreply@nexachat.io.vn"}

        def wait_for_message(self, to, subject_contains=None, timeout=60):
            code = self.codes[self.n]
            self.n += 1
            return {"Text": code}

        def extract_otp(self, message):
            return message["Text"]

        def clear(self):
            return None

    class Client:
        def __init__(self, store, world):
            self.store = store
            self.world = world
            self.confirmed = False

        def post(self, path, token=None, body=None, headers=None):
            R = _AccountDeletionDeviceFixture.Response
            body = body or {}
            if path.endswith("/auth/register"):
                phone = body["phone"]
                if phone in self.store.users:
                    self.store.reused = True
                self.store.users[phone] = {"password": body["password"],
                                           "displayName": body["displayName"],
                                           "deleted": False}
                return R(201, {"accessToken": f"tok-{phone}",
                               "user": {"id": phone, "phone": phone}})
            if path.endswith("/users/me/email/verify"):
                return R(200, {})
            if path.endswith("/account-deletion/request"):
                return R(202, {})
            if path.endswith("/account-deletion/confirm"):
                self.confirmed = True
                self.store.users[body["phone"]]["deleted"] = True
                return R(204, None)
            if path.endswith("/conversations/direct"):
                return R(201, {"id": "conv-del", "type": "DIRECT"})
            if "/messages" in path:
                self.store.messages.append(body["content"])
                return R(201, {"id": "m1"})
            raise AssertionError(path)

        def put(self, path, token=None, body=None, headers=None):
            return _AccountDeletionDeviceFixture.Response(202, {})

        def get(self, path, token=None, params=None, headers=None):
            R = _AccountDeletionDeviceFixture.Response
            phone = (token or "").removeprefix("tok-")
            user = self.store.users.get(phone)
            if path == "/api/v1/users/me":
                if user is None or user["deleted"]:
                    return R(401)
                return R(200, {"phone": phone})
            if path == "/api/v1/conversations/conv-del/messages":
                return R(200, {"items": [{"content": content}
                                         for content in self.store.messages]})
            raise AssertionError(path)

        def request(self, method, path, token=None, body=None, headers=None):
            phone = (token or "").removeprefix("tok-")
            user = self.store.users.get(phone)
            if method == "DELETE" and path == "/api/v1/users/me":
                if user and body and body.get("password") == user["password"]:
                    user["deleted"] = True
                    return _AccountDeletionDeviceFixture.Response(204, None)
                return _AccountDeletionDeviceFixture.Response(401, {})
            raise AssertionError((method, path))

    class Device:
        def __init__(self, world, store):
            self.world = world
            self.store = store

        def nodes(self):
            return self.world.tree()

        def tap_node(self, node):
            w = self.world
            rid = node.attrs["resource-id"].rsplit("/", 1)[-1]
            if w.screen == "login" and rid == "primaryAction":
                phone = w.login_inputs.get("phoneInput", "")
                password = w.login_inputs.get("passwordInput", "")
                user = self.store.users.get(phone)
                if user and not user["deleted"] and password == user["password"]:
                    w.account = {"phone": phone,
                                 "displayName": user["displayName"]}
                    w.screen = "inbox"
                return
            if w.screen == "inbox" and rid == "inboxUserAvatar":
                w.screen = "profile"
                return
            if w.screen == "profile" and rid == "deleteAccountRow":
                w.screen = "delete"
                w.error_text = ""
                w.code_requested = False
                return
            if w.screen == "delete":
                if rid == "confirmDelete":
                    if self.store.is_passwordless(w.account):
                        code = w.login_inputs.get("deletionCodeInput", "")
                        if code == self.store.deletion_code:
                            self.store.users[w.account["phone"]]["deleted"] = True
                            w.deleted_via_code = True
                            w.account = None
                            w.screen = "login"
                        else:
                            w.error_text = "Wrong or expired code"
                    else:
                        w.wrong_password_refused = True
                        w.error_text = w.WRONG_PASSWORD_TEXT
                elif rid == "sendDeletionCode":
                    w.code_requested = True

        def key(self, code):
            w = self.world
            if w.keyboard:
                w.keyboard = False
                return
            if w.screen == "delete":
                w.screen = "profile"
            elif w.screen == "profile":
                w.screen = "inbox"

        def swipe(self, x1, y1, x2, y2, duration):
            return None

        def screenshot(self, path):
            Path(path).write_bytes(b"png")

    class Adb:
        serial = "emulator-5554"

        def __init__(self, world):
            self.world = world

        def command(self, *args, **kwargs):
            if "force-stop" in args:
                return ""
            if args[:2] == ("shell", "am") and "start" in args:
                self.world.cold_start()
                return ""
            if args[:3] == ("shell", "input", "keyevent"):
                self.world.keyboard = False
                return ""
            return ""

    class Patch:
        PASSWORD = "password123"

        @staticmethod
        def ui_nodes(device, timeout=8):
            return device.nodes()

        @staticmethod
        def node_by_id(nodes, suffix):
            return next((node for node in nodes
                         if node.attrs.get("resource-id", "").endswith("/" + suffix)),
                        None)

        @staticmethod
        def text_matches(node, candidates):
            value = (node.text + " " + node.desc).casefold()
            return any(candidate.casefold() in value for candidate in candidates)

        @staticmethod
        def login_form_visible(nodes):
            ids = {node.attrs.get("resource-id", "").rsplit("/", 1)[-1]
                   for node in nodes}
            return {"phoneInput", "passwordInput"} <= ids and any(
                node.text.strip() == "Sign in" for node in nodes)

        @staticmethod
        def inbox_visible(nodes):
            return any(node.attrs.get("resource-id", "").endswith("/inboxUserAvatar")
                       for node in nodes)

        @staticmethod
        def fill_login_field(ui, field_id, value):
            ui.world.login_inputs[field_id] = value
            ui.world.keyboard = True

    def __init__(self, test):
        self.test = test
        self.phone = self.PHONE
        self.store = self.Store()
        self.store.peer_name = self.PEER_NAME
        self.world = _AccountDeletionWorld(self.store)
        self.store.doomed.add(self.phone)
        self.client = self.Client(self.store, self.world)
        # codes: recovery-email verify, then the in-app deletion code.
        self.mail = self.Mail(["112233", self.store.deletion_code])

    def context(self, row, stack_port=None):
        """Context whose base_url sits on a free port; the disposable stack
        handle serves stack_port (defaults to the same port so the app can
        reach it; a different port simulates the shared-server conflict)."""
        import socket
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        base_url = f"http://127.0.0.1:{port}"
        stack_base = f"http://127.0.0.1:{stack_port or port}"
        world = self.world
        store = self.store

        def sql(statement, db_name=None):
            world.sql_log.append(statement)
            if "password_hash = NULL" in statement:
                world.passwordless_fixture_applied = True
                store.passwordless.add(self.phone)

        def apply_env(env):
            return {"base_url": stack_base, "client": self.client,
                    "sql": sql, "close": lambda: None}

        class Api:
            USER_A = {"phone": "+84900000001"}

        return {
            "serial": "emulator-5554",
            "base_url": base_url,
            "row": row,
            "row_dir": Path(tempfile.mkdtemp()),
            "selected_ids": ("auth-login", "account-deletion"),
            "disposable_phone": self.phone,
            "disposable_email": self.EMAIL,
            "mail_sink": self.mail,
            "client": self.client,
            "apply_server_env": apply_env,
            "adb": self.Adb(world),
            "device": self.Device(world, store),
            "helpers": (Api(), None, self.Patch()),
            "ui_timeout": 2,
        }


class WiringDecisionTest(unittest.TestCase):
    def test_list_shows_runnable_rows_and_remaining_gaps(self):
        output = __import__("io").StringIO()
        with mock.patch("sys.stdout", output):
            self.assertEqual(journeys.main(["--list"]), 0)
        runnable = []
        blocked = {}
        for line in output.getvalue().splitlines():
            if line.startswith("ID "):
                continue
            row_id, _title, _refs, disposition = [part.strip() for part in line.split(" | ")]
            if disposition.startswith("BLOCKED_CAPABILITY:"):
                blocked[row_id] = disposition
            else:
                runnable.append(row_id)
        self.assertEqual(runnable, [
            "auth-login", "otp-forgot-password",
            "chat-send-receive", "chat-interactions",
            "groups-contacts", "polls-reminders", "archive-preferences",
            "labels-lifecycle",
            "calls-injected-notification", "summary-ask", "offline-sync",
            "injected-notifications", "accessibility-sweep",
        ])
        self.assertEqual(len(runnable) + len(blocked), len(journeys.ROWS))
        self.assertIn("Room 14", blocked["room-13-14-upgrade"])
        self.assertIn("Room 14", blocked["v111-rc-upgrade"])
        self.assertIn("seed user", blocked["account-deletion"])
        self.assertIn("REST session ops", blocked["auth-session-lifecycle"])
        self.assertIn("STOMP hold", blocked["auth-session-lifecycle"])
        self.assertIn("stays unproven", blocked["auth-session-lifecycle"])
        self.assertNotIn("calls-injected-notification", blocked)
        self.assertNotIn("injected-notifications", blocked)
        self.assertNotIn("summary-ask", blocked)
        self.assertNotIn("archive-preferences", blocked)
        self.assertNotIn("groups-contacts", blocked)
        self.assertNotIn("labels-lifecycle", blocked)
        self.assertNotIn("polls-reminders", blocked)
        self.assertNotIn("offline-sync", blocked)
        for row_id in (
            "media",
            "calls-signaling",
        ):
            self.assertIn("BLOCKED_CAPABILITY:", blocked[row_id])
        self.assertNotIn("accessibility-sweep", blocked)

    def test_upgrade_plan_names_install_and_stays_blocked(self):
        output = __import__("io").StringIO()
        with mock.patch("sys.stdout", output), mock.patch.object(journeys.subprocess, "run") as run:
            code = journeys.main(["--dry-run", "--rows", "room-13-14-upgrade,v111-rc-upgrade"])
        self.assertEqual(code, 0)
        run.assert_not_called()
        plan = json.loads(output.getvalue())
        for row in plan["rows"]:
            text = json.dumps(row["helper_calls"])
            self.assertIn("fixture_apks.fetch", text)
            self.assertIn("install", text)
            self.assertIn("-r", text)
            self.assertIn("Room 14", row["capability_gap"])
        self.assertIn("response_barrier.Barrier.hold", json.dumps(
            [dict(call) for call in journeys.HELPER_CALLS["auth-session-lifecycle"]]
        ))

    def test_registered_run_does_not_start_blocked_helpers(self):
        class Boom:
            def start(self):
                raise AssertionError("blocked row started a helper")

        # Mail rows now have executors: with no apply_server_env they must raise
        # CapabilityBlocked inside the mail-sink gate, before Boom.start() runs.
        mail_context = {
            "mail_sink": Boom(),
            "serial": "emulator-5554",
            "base_url": "http://127.0.0.1:18093",
            "selected_ids": ("account-deletion",),
        }
        for row_id in ("otp-forgot-password", "account-deletion"):
            with self.subTest(row_id=row_id):
                with self.assertRaises(journeys.CapabilityBlocked):
                    journeys.run_registered_row(
                        journeys.ROW_BY_ID[row_id], dict(mail_context))
        for row_id in ("room-13-14-upgrade", "v111-rc-upgrade"):
            with self.subTest(row_id=row_id):
                with self.assertRaises(journeys.CapabilityBlocked):
                    journeys.run_registered_row(journeys.ROW_BY_ID[row_id], {"mail_sink": Boom()})

    def test_auth_session_lifecycle_dispatches_to_its_executor(self):
        row = journeys.ROW_BY_ID["auth-session-lifecycle"]
        # The gap now states the partial-coverage contract (plan 2.3(b)): the
        # REST session ops are proven, the paused-frame STOMP hold stays
        # unproven. The registered executor still runs — a gap never silences
        # dispatch.
        self.assertIn("REST session ops", row.capability_gap)
        self.assertIn("STOMP hold", row.capability_gap)
        # No serial in context: the executor must run and fail as a harness
        # error, proving the row still dispatches despite its gap.
        with self.assertRaises(journeys.HarnessError):
            journeys.run_registered_row(row, {"mail_sink": object()})

    def test_executors_refuse_a_non_reserved_serial(self):
        cases = (
            journeys.run_chat_interactions,
            journeys.run_summary_ask,
            journeys.run_archive_preferences,
            journeys.run_labels_lifecycle,
            journeys.run_groups_contacts,
            journeys.run_polls_reminders,
            journeys.run_calls_injected_notification,
            journeys.run_injected_notifications,
            journeys.run_otp_forgot_password,
            journeys.run_account_deletion,
            journeys.run_auth_session_lifecycle,
            journeys.run_accessibility_sweep,
            journeys.run_room_13_14_upgrade,
            journeys.run_v111_rc_upgrade,
        )
        for executor in cases:
            with self.subTest(executor=executor.__name__):
                with self.assertRaisesRegex(journeys.HarnessError, "never touch another device"):
                    executor(journeys.ROWS[0], {"serial": "emulator-5570"})

    def test_push_executor_refuses_production_and_calls_inject_push(self):
        row = journeys.ROW_BY_ID["calls-injected-notification"]
        with self.assertRaisesRegex(journeys.HarnessError, "refusing production"):
            journeys.run_calls_injected_notification(row, {
                "serial": "emulator-5554",
                "base_url": "https://api.nexachat.io.vn",
                "row": row,
            })

        adb_calls = []
        state = {"declined": False}

        class Node:
            def __init__(self, text):
                self.text = text

        class Adb:
            serial = "emulator-5554"

            def command(self, *args, **kwargs):
                adb_calls.append(args)
                if "pidof" in args:
                    return ""
                if len(args) >= 3 and args[2] == "activity":
                    return "com.nexachat.call.ui.IncomingCallActivity"
                return ""

        class Patch:
            @staticmethod
            def ui_nodes(device, timeout=8):
                if state["declined"]:
                    return []
                return [Node("Incoming voice call"), Node("Accept"), Node("Decline")]

            @staticmethod
            def grant_test_permissions():
                return {}

        class Device:
            def tap_node(self, node):
                if node.text in {"Decline", "Từ chối"}:
                    state["declined"] = True

        class Api:
            USER_A = {"phone": "+84900000001"}
            USER_C = {"phone": "+84900000002"}

            @staticmethod
            def direct_conversation(client, token, phone):
                return 200, {"id": "conv-200"}

        class Response:
            def __init__(self, body):
                self.status = 200
                self._body = body

            def json(self):
                return self._body

        class Client:
            def get(self, path, token=None, params=None, headers=None):
                return Response({"items": []})

        injected = []

        def fake_main(argv):
            injected.append(list(argv))
            return 0

        def session(api, recorder, phone):
            if phone.endswith("0001"):
                return {"accessToken": "tok-an", "user": {"id": "user-an", "phone": phone}}
            return {"accessToken": "tok-binh", "user": {"id": "user-binh", "phone": phone}}

        import inject_push
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session), \
                mock.patch.object(inject_push, "main", side_effect=fake_main):
            result = journeys.run_calls_injected_notification(row, {
                "serial": "emulator-5554",
                "base_url": "http://127.0.0.1:18093",
                "row": row,
                "adb": Adb(),
                "device": Device(),
                "helpers": (Api(), None, Patch()),
                "client": Client(),
                "ui_timeout": 1,
            })
        self.assertEqual(result["label"], "INJECTED")
        self.assertEqual(injected[0][1], "emulator-5554")
        self.assertIn("call", injected[0])
        self.assertNotIn("broadcast", injected[0])
        flat = [" ".join(args) for args in adb_calls]
        self.assertFalse(any("broadcast" in command or "force-stop" in command for command in flat))
        self.assertTrue(any("am kill" in command for command in flat))

    def test_call_executor_uses_notification_surface_on_heads_up_only_builds(self):
        # API 34+: the call row lives only in `dumpsys notification`; the
        # IncomingCallActivity is non-exported so `am start` cannot stand in
        # for a user tap, and the heads-up peek collapses before uiautomator
        # catches it. The harness falls back to the DUMP-gated decline hook,
        # which fires the notification's own decline PendingIntent — the same
        # CallForegroundService -> CallManager.reject path as a user tap.
        row = journeys.ROW_BY_ID["calls-injected-notification"]
        adb_calls = []
        state = {"declined": False}

        class Adb:
            serial = "emulator-5554"

            def command(self, *args, **kwargs):
                adb_calls.append(args)
                joined = " ".join(str(arg) for arg in args)
                if "pidof" in args:
                    return ""
                if "DECLINE_INCOMING_CALL" in joined:
                    state["declined"] = True
                    return ""
                if "dumpsys" in args and "notification" in args:
                    if state["declined"]:
                        return ""
                    return (
                        "NotificationRecord key=0|com.nexachat.debug|4701|null|1000\n"
                        "android.title=String (Incoming voice call)\n"
                        '"Accept" "Decline"\n'
                    )
                if "dumpsys" in args and "activity" in args:
                    return "com.nexachat.call.ui.IncomingCallActivity"
                return ""

        class Patch:
            @staticmethod
            def ui_nodes(device, timeout=8):
                return []  # peek never lands inside a uiautomator dump

            @staticmethod
            def grant_test_permissions():
                return {}

        class Device:
            def tap_node(self, node):
                if node.text in {"Decline", "Từ chối"}:
                    state["declined"] = True

        class Api:
            USER_A = {"phone": "+84900000001"}
            USER_C = {"phone": "+84900000002"}

            @staticmethod
            def direct_conversation(client, token, phone):
                return 200, {"id": "conv-200"}

        class Response:
            def __init__(self, body):
                self.status = 200
                self._body = body

            def json(self):
                return self._body

        class Client:
            def get(self, path, token=None, params=None, headers=None):
                return Response({"items": []})

        def session(api, recorder, phone):
            if phone.endswith("0001"):
                return {"accessToken": "tok-an", "user": {"id": "user-an", "phone": phone}}
            return {"accessToken": "tok-binh", "user": {"id": "user-binh", "phone": phone}}

        import inject_push
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session), \
                mock.patch.object(inject_push, "main", return_value=0):
            result = journeys.run_calls_injected_notification(row, {
                "serial": "emulator-5554",
                "base_url": "http://127.0.0.1:18093",
                "row": row,
                "adb": Adb(),
                "device": Device(),
                "helpers": (Api(), None, Patch()),
                "client": Client(),
                "ui_timeout": 1,
            })
        self.assertEqual(result["label"], "INJECTED")
        self.assertEqual(result["incoming_surface"], "notification")
        self.assertTrue(state["declined"])
        flat = [" ".join(str(arg) for arg in args) for args in adb_calls]
        self.assertFalse(any(
            "am start" in command and "IncomingCallActivity" in command
            for command in flat))
        self.assertTrue(any("DECLINE_INCOMING_CALL" in command for command in flat))

    def test_message_executor_uses_inject_push_for_both_kinds(self):
        row = journeys.ROW_BY_ID["injected-notifications"]
        adb_calls = []

        class Adb:
            serial = "emulator-5554"

            def command(self, *args, **kwargs):
                adb_calls.append(args)
                if "notification" in args:
                    cleared = any(
                        "CLEAR_NOTIFICATIONS" in str(arg)
                        for call in adb_calls for arg in call)
                    if cleared:
                        return ""
                    return (
                        "NotificationRecord key=0|com.nexachat.debug|7|null|1000\n"
                        "You have a new message\nA reminder is due\n"
                    )
                return ""

        class Api:
            USER_A = {"phone": "+84900000001"}
            USER_C = {"phone": "+84900000002"}

            @staticmethod
            def direct_conversation(client, token, phone):
                return 200, {"id": "conv-200"}

            @staticmethod
            def send_text(client, token, conversation_id, content):
                return {"id": "message-1", "seq": 8, "content": content}

        class Response:
            def __init__(self, status, body=None):
                self.status = status
                self._body = body or {}

            def json(self):
                return self._body

        class Client:
            def __init__(self):
                self.deletes = []

            def post(self, path, token=None, body=None, headers=None):
                return Response(201, {"id": "reminder-1"})

            def delete(self, path, token=None, params=None, headers=None):
                self.deletes.append(path)
                return Response(204)

            def get(self, path, token=None, params=None, headers=None):
                return Response(200, {"items": []})

        injected = []

        def fake_main(argv):
            injected.append(list(argv))
            return 0

        def session(api, recorder, phone):
            if phone.endswith("0001"):
                return {"accessToken": "tok-an", "user": {"id": "user-an", "phone": phone}}
            return {"accessToken": "tok-binh", "user": {"id": "user-binh", "phone": phone}}

        import inject_push
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session), \
                mock.patch.object(inject_push, "main", side_effect=fake_main):
            result = journeys.run_injected_notifications(row, {
                "serial": "emulator-5554",
                "base_url": "http://127.0.0.1:18093",
                "row": row,
                "adb": Adb(),
                "device": object(),
                "helpers": (Api(), None, object()),
                "client": Client(),
                "ui_timeout": 1,
            })
        self.assertEqual(result["label"], "INJECTED")
        self.assertFalse(result["payload_body_in_shade"])
        kinds = [argv[argv.index(kind)] for argv in injected for kind in ("message", "reminder") if kind in argv]
        self.assertEqual(kinds, ["message", "reminder"])
        self.assertTrue(all(argv[1] == "emulator-5554" for argv in injected))
        # Injection must route through inject_push, never a raw INJECT_PUSH
        # broadcast; the debug CLEAR_NOTIFICATIONS cleanup broadcast is allowed.
        self.assertFalse(any(
            "INJECT_PUSH" in str(args) for args in adb_calls))

    def test_otp_flow_blocks_on_expiry_and_stops_the_sink(self):
        row = journeys.ROW_BY_ID["otp-forgot-password"]
        phone = "+84991112233"

        class Mail:
            def __init__(self):
                self.codes = ["482910", "193847", "550012"]
                self.n = 0
                self.started = False
                self.stopped = False

            def start(self):
                self.started = True

            def stop(self):
                self.stopped = True

            def server_env(self):
                return {
                    "NEXACHAT_MAIL_PROVIDER": "smtp",
                    "MAIL_SMTP_HOST": "127.0.0.1",
                    "MAIL_SMTP_PORT": "1025",
                    "MAIL_SMTP_USERNAME": "",
                    "MAIL_SMTP_PASSWORD": "",
                    "MAIL_FROM": "noreply@nexachat.io.vn",
                }

            def wait_for_message(self, to, subject_contains=None, timeout=60):
                code = self.codes[self.n]
                self.n += 1
                return {"Text": code}

            def extract_otp(self, message):
                return message["Text"]

            def clear(self):
                return None

        class Response:
            def __init__(self, status, body=None):
                self.status = status
                self._body = body

            def json(self):
                return {} if self._body is None else self._body

        class Client:
            def __init__(self):
                self.used = set()

            def post(self, path, token=None, body=None, headers=None):
                body = body or {}
                if path.endswith("/register"):
                    return Response(201, {"accessToken": "old-token", "user": {"id": "u1", "phone": body["phone"]}})
                if path.endswith("/email/verify"):
                    return Response(200, {"verified": True})
                if path.endswith("/password-reset/request"):
                    return Response(202, {})
                if path.endswith("/password-reset/confirm"):
                    code = body["code"]
                    if code in {"000000", "111111"} or code in self.used:
                        return Response(400, {})
                    self.used.add(code)
                    return Response(204, None)
                if path.endswith("/login"):
                    if body["password"] == journeys.DISPOSABLE_PASSWORD:
                        return Response(401, {})
                    return Response(200, {"accessToken": "new-token"})
                raise AssertionError(path)

            def put(self, path, token=None, body=None, headers=None):
                return Response(202, {})

            def get(self, path, token=None, params=None, headers=None):
                return Response(401, {})

        mail = Mail()
        applied = []
        with self.assertRaises(journeys.CapabilityBlocked) as caught:
            journeys.run_otp_forgot_password(row, {
                "serial": "emulator-5554",
                "base_url": "http://127.0.0.1:18093",
                "row": row,
                "disposable_phone": phone,
                "disposable_email": "otp@example.test",
                "mail_sink": mail,
                "client": Client(),
                "sleep": lambda seconds: None,
                "apply_server_env": applied.append,
            })
        self.assertTrue(mail.started)
        self.assertTrue(mail.stopped)
        self.assertEqual(mail.n, 3)
        self.assertEqual(applied[0]["NEXACHAT_MAIL_PROVIDER"], "smtp")
        text = str(caught.exception)
        self.assertIn("expired", text)
        for code in mail.codes:
            self.assertNotIn(code, text)

    def test_account_deletion_refuses_seed_users_and_blocks_after_otp(self):
        row = journeys.ROW_BY_ID["account-deletion"]
        with self.assertRaisesRegex(journeys.HarnessError, "seed user"):
            journeys.run_account_deletion(row, {
                "serial": "emulator-5554",
                "base_url": "http://127.0.0.1:18093",
                "row": row,
                "selected_ids": ("auth-login", "account-deletion"),
                "disposable_phone": "+84900000001",
            })
        with self.assertRaisesRegex(journeys.HarnessError, "must run last"):
            journeys.run_account_deletion(row, {
                "serial": "emulator-5554",
                "base_url": "http://127.0.0.1:18093",
                "row": row,
                "selected_ids": ("account-deletion", "auth-login"),
                "disposable_phone": "+84991112233",
            })

        class Mail:
            def __init__(self):
                self.codes = ["246810", "135790"]
                self.n = 0
                self.stopped = False

            def start(self):
                return None

            def stop(self):
                self.stopped = True

            def server_env(self):
                return {"NEXACHAT_MAIL_PROVIDER": "smtp", "MAIL_SMTP_HOST": "127.0.0.1",
                        "MAIL_SMTP_PORT": "1025", "MAIL_SMTP_USERNAME": "",
                        "MAIL_SMTP_PASSWORD": "", "MAIL_FROM": "noreply@nexachat.io.vn"}

            def wait_for_message(self, to, subject_contains=None, timeout=60):
                code = self.codes[self.n]
                self.n += 1
                return {"Text": code}

            def extract_otp(self, message):
                return message["Text"]

            def clear(self):
                return None

        class Response:
            def __init__(self, status, body=None):
                self.status = status
                self._body = body

            def json(self):
                return {} if self._body is None else self._body

        class Client:
            def __init__(self):
                self.gets = 0
                self.deleted = False

            def post(self, path, token=None, body=None, headers=None):
                body = body or {}
                if path.endswith("/register"):
                    return Response(201, {"accessToken": "tok", "user": {"id": "u", "phone": body["phone"]}})
                if path.endswith("/email/verify"):
                    return Response(200, {})
                if path.endswith("/account-deletion/request"):
                    return Response(202, {})
                if path.endswith("/account-deletion/confirm"):
                    return Response(204, None)
                raise AssertionError(path)

            def put(self, path, token=None, body=None, headers=None):
                return Response(202, {})

            def get(self, path, token=None, params=None, headers=None):
                self.gets += 1
                return Response(200 if self.gets == 1 else 401, {"phone": "+84991112233"} if self.gets == 1 else {})

            def request(self, method, path, token=None, body=None, headers=None):
                self.deleted = method == "DELETE"
                return Response(401, {})

        mail = Mail()
        client = Client()
        with self.assertRaises(journeys.CapabilityBlocked) as caught:
            journeys.run_account_deletion(row, {
                "serial": "emulator-5554",
                "base_url": "http://127.0.0.1:18093",
                "row": row,
                "selected_ids": ("auth-login", "account-deletion"),
                "disposable_phone": "+84991112233",
                "disposable_email": "delete@example.test",
                "mail_sink": mail,
                "client": client,
                "apply_server_env": lambda env: None,
            })
        self.assertTrue(client.deleted)
        self.assertTrue(mail.stopped)
        self.assertEqual(mail.n, 2)
        self.assertIn("cache", str(caught.exception))
        for code in mail.codes:
            self.assertNotIn(code, str(caught.exception))

    def test_account_deletion_blocks_when_the_stack_misses_the_app_port(self):
        row = journeys.ROW_BY_ID["account-deletion"]
        fixture = _AccountDeletionDeviceFixture(self)
        context = fixture.context(row, stack_port=18443)
        with mock.patch.object(journeys.time, "sleep"):
            with self.assertRaises(journeys.CapabilityBlocked) as caught:
                journeys.run_account_deletion(row, context)
        text = str(caught.exception)
        self.assertIn("10.0.2.2", text)
        self.assertIn("cache", text)
        # The REST/OTP deletion still ran before the explicit gap.
        self.assertEqual(fixture.mail.n, 2)
        self.assertTrue(fixture.client.confirmed)
        self.assertTrue(fixture.mail.stopped)
        for code in fixture.mail.codes:
            self.assertNotIn(code, text)

    def test_account_deletion_drives_the_in_app_ui_slice(self):
        row = journeys.ROW_BY_ID["account-deletion"]
        fixture = _AccountDeletionDeviceFixture(self)
        context = fixture.context(row)
        with mock.patch.object(journeys.time, "sleep"):
            result = journeys.run_account_deletion(row, context)
        for key in ("sign_in", "wrong_password_ui", "in_app_deletion",
                    "token_rejection", "peer_history", "cache_and_reuse"):
            self.assertIn(key, result)
        world = fixture.world
        # The wrong password was refused in the UI and the account survived it.
        self.assertTrue(world.wrong_password_refused)
        # The passwordless (V33 Google-only) fixture flipped the UI to the
        # code flow, the Mailpit code confirmed the deletion, and the app
        # returned to the login form.
        self.assertTrue(world.passwordless_fixture_applied)
        self.assertTrue(world.deleted_via_code)
        self.assertEqual(world.screen, "inbox")
        self.assertEqual(world.account["phone"], fixture.phone)
        # The re-registered phone signed in to an inbox with no cached row.
        self.assertEqual(world.inbox_titles, [])
        # The OTP never leaked into the results or the sql transcript.
        transcript = json.dumps(result) + json.dumps(world.sql_log)
        for code in fixture.mail.codes:
            self.assertNotIn(code, transcript)

    def test_otp_flow_passes_on_a_stack_handle_and_proves_expiry(self):
        row = journeys.ROW_BY_ID["otp-forgot-password"]
        phone = "+84991112233"

        class Mail:
            def __init__(self):
                self.codes = ["482910", "193847", "550012", "778899"]
                self.n = 0
                self.stopped = False

            def start(self):
                return None

            def stop(self):
                self.stopped = True

            def server_env(self):
                return {"NEXACHAT_MAIL_PROVIDER": "smtp", "MAIL_SMTP_HOST": "127.0.0.1",
                        "MAIL_SMTP_PORT": "1025", "MAIL_SMTP_USERNAME": "",
                        "MAIL_SMTP_PASSWORD": "", "MAIL_FROM": "noreply@nexachat.io.vn"}

            def wait_for_message(self, to, subject_contains=None, timeout=60):
                code = self.codes[self.n]
                self.n += 1
                return {"Text": code}

            def extract_otp(self, message):
                return message["Text"]

            def clear(self):
                return None

        class Response:
            def __init__(self, status, body=None):
                self.status = status
                self._body = body

            def json(self):
                return {} if self._body is None else self._body

        class Client:
            def __init__(self):
                self.used = set()
                self.expired_attempts = []

            def post(self, path, token=None, body=None, headers=None):
                body = body or {}
                if path.endswith("/register"):
                    return Response(201, {"accessToken": "old-token", "user": {"id": "u1", "phone": body["phone"]}})
                if path.endswith("/email/verify"):
                    return Response(200, {"verified": True})
                if path.endswith("/password-reset/request"):
                    return Response(202, {})
                if path.endswith("/password-reset/confirm"):
                    code = body["code"]
                    if code in {"000000", "111111"} or code in self.used:
                        return Response(400, {})
                    if code == "778899":
                        self.expired_attempts.append(code)
                        return Response(400, {})
                    self.used.add(code)
                    return Response(204, None)
                if path.endswith("/login"):
                    if body["password"] == journeys.DISPOSABLE_PASSWORD:
                        return Response(401, {})
                    return Response(200, {"accessToken": "new-token"})
                raise AssertionError(path)

            def put(self, path, token=None, body=None, headers=None):
                return Response(202, {})

            def get(self, path, token=None, params=None, headers=None):
                return Response(401, {})

            def request(self, method, path, token=None, body=None, headers=None):
                return self.post(path, token=token, body=body)

        mail = Mail()
        shared_client = Client()
        stack_client = Client()
        sql_calls = []
        closed = []

        def apply_env(env):
            return {
                "client": stack_client,
                "base_url": "http://127.0.0.1:19000",
                "sql": sql_calls.append,
                "close": lambda: closed.append(True),
            }

        context = {
            "serial": "emulator-5554",
            "base_url": "http://127.0.0.1:18093",
            "row": row,
            "disposable_phone": phone,
            "disposable_email": "otp@example.test",
            "mail_sink": mail,
            "client": shared_client,
            "sleep": lambda seconds: None,
            "apply_server_env": apply_env,
        }
        result = journeys.run_otp_forgot_password(row, context)
        self.assertEqual(result["expired_code"], "aged OTP was refused by the disposable stack")
        # The action ran against the stack client, and the context was restored.
        self.assertEqual(stack_client.expired_attempts, ["778899"])
        self.assertIs(context["client"], shared_client)
        self.assertEqual(context["base_url"], "http://127.0.0.1:18093")
        self.assertNotIn("dstack_handle", context)
        # The expiry proof rewrote expires_at on the disposable database.
        self.assertTrue(any("expires_at" in stmt for stmt in sql_calls))
        self.assertEqual(closed, [True])
        self.assertTrue(mail.stopped)
        for code in mail.codes:
            self.assertNotIn(code, str(result))

    def test_otp_flow_without_apply_env_never_starts_the_sink(self):
        row = journeys.ROW_BY_ID["otp-forgot-password"]

        class Boom:
            def start(self):
                raise AssertionError("mail sink started without a server hook")

        with self.assertRaises(journeys.CapabilityBlocked):
            journeys.run_otp_forgot_password(row, {
                "serial": "emulator-5554",
                "base_url": "http://127.0.0.1:18093",
                "row": row,
                "disposable_phone": "+84991112233",
                "mail_sink": Boom(),
            })

    def test_upgrade_executors_do_not_fetch_or_install(self):
        for row_id, executor in (
            ("room-13-14-upgrade", journeys.run_room_13_14_upgrade),
            ("v111-rc-upgrade", journeys.run_v111_rc_upgrade),
        ):
            row = journeys.ROW_BY_ID[row_id]
            with mock.patch.object(journeys.subprocess, "run") as run:
                with self.assertRaises(journeys.CapabilityBlocked) as caught:
                    executor(row, {
                        "serial": "emulator-5554",
                        "base_url": "http://127.0.0.1:18093",
                        "row": row,
                    })
            run.assert_not_called()
            self.assertIn("Room 14", str(caught.exception))


class AuthSessionLifecycleExecutorTest(unittest.TestCase):
    """Offline fake REST/STOMP world for run_auth_session_lifecycle.

    The fake client implements the real revocation semantics (logout stamps
    every access token dead, refresh rotates, password change revokes all
    sessions) so the executor's 401/204 assertions are meaningful. The fake
    barrier records hold rules; the fake readers replay scripted STOMP
    envelopes keyed by the account phone inside each access token.
    """

    MARKER = "sj-paused-offline"
    PHONE_REST = "+84991110001"
    PHONE_A = "+84991110002"
    PHONE_B = "+84991110003"

    class Response:
        def __init__(self, status, body=None):
            self.status = status
            self._body = body or {}

        def json(self):
            return self._body

    class FakeAuthClient:
        def __init__(self):
            self.users = {}
            self.sessions = {}
            self.clock = 0
            self.conversations = {}
            self.deleted_users = []
            self.calls = []

        def _issue(self, phone):
            user = self.users[phone]
            self.clock += 1
            access = f"at-{phone}-{self.clock}"
            refresh = f"rt-{phone}-{self.clock}"
            self.sessions[access] = {"phone": phone, "iat": self.clock,
                                     "uid": user["id"]}
            user["refresh_alive"].add(refresh)
            return {"accessToken": access, "refreshToken": refresh,
                    "user": {"id": user["id"], "phone": phone}}

        def _user_for_token(self, token):
            session = self.sessions.get(token or "")
            if session is None:
                return None
            user = self.users.get(session["phone"])
            if (user is None or user["deleted"] or user["id"] != session["uid"]
                    or session["iat"] <= user["access_floor"]):
                return None
            return user

        @staticmethod
        def _phone_of(users, user):
            return next(p for p, u in users.items() if u is user)

        def post(self, path, token=None, body=None, headers=None):
            self.calls.append(("POST", path))
            body = body or {}
            if path == "/api/v1/auth/register":
                phone = body.get("phone")
                if phone in self.users and not self.users[phone]["deleted"]:
                    return AuthSessionLifecycleExecutorTest.Response(409, {})
                self.users[phone] = {"password": body.get("password"),
                                     "id": f"uid-{phone}", "access_floor": 0,
                                     "refresh_alive": set(), "deleted": False}
                return AuthSessionLifecycleExecutorTest.Response(201, self._issue(phone))
            if path == "/api/v1/auth/login":
                user = self.users.get(body.get("phone"))
                if (user is None or user["deleted"]
                        or user["password"] != body.get("password")):
                    return AuthSessionLifecycleExecutorTest.Response(401, {})
                return AuthSessionLifecycleExecutorTest.Response(200, self._issue(body["phone"]))
            if path == "/api/v1/auth/refresh":
                refresh = body.get("refreshToken")
                owner = next((u for u in self.users.values()
                              if refresh in u["refresh_alive"]), None)
                if owner is None:
                    return AuthSessionLifecycleExecutorTest.Response(401, {})
                owner["refresh_alive"].discard(refresh)
                phone = self._phone_of(self.users, owner)
                return AuthSessionLifecycleExecutorTest.Response(200, self._issue(phone))
            if path in ("/api/v1/auth/logout", "/api/v1/auth/logout-all"):
                user = self._user_for_token(token)
                if user is None:
                    return AuthSessionLifecycleExecutorTest.Response(401, {})
                user["access_floor"] = self.clock
                if path.endswith("logout-all"):
                    user["refresh_alive"].clear()
                else:
                    user["refresh_alive"].discard(body.get("refreshToken"))
                return AuthSessionLifecycleExecutorTest.Response(204, {})
            if path == "/api/v1/conversations/direct":
                if self._user_for_token(token) is None:
                    return AuthSessionLifecycleExecutorTest.Response(401, {})
                self.conversations.setdefault("conv-direct-1", {"messages": []})
                return AuthSessionLifecycleExecutorTest.Response(201, {"id": "conv-direct-1", "type": "DIRECT"})
            if "/messages" in path:
                if self._user_for_token(token) is None:
                    return AuthSessionLifecycleExecutorTest.Response(401, {})
                cid = path.split("/")[4]
                conv = self.conversations.setdefault(cid, {"messages": []})
                conv["messages"].append(
                    {"id": f"msg-{len(conv['messages']) + 1}",
                     "content": body.get("content")})
                return AuthSessionLifecycleExecutorTest.Response(201, conv["messages"][-1])
            return AuthSessionLifecycleExecutorTest.Response(404, {})

        def get(self, path, token=None, params=None, headers=None):
            self.calls.append(("GET", path))
            if path == "/api/v1/users/me":
                user = self._user_for_token(token)
                if user is None:
                    return AuthSessionLifecycleExecutorTest.Response(401, {})
                return AuthSessionLifecycleExecutorTest.Response(
                    200, {"id": user["id"],
                          "phone": self._phone_of(self.users, user)})
            if "/messages" in path:
                cid = path.split("/")[4]
                conv = self.conversations.get(cid, {"messages": []})
                return AuthSessionLifecycleExecutorTest.Response(200, {"items": list(conv["messages"])})
            return AuthSessionLifecycleExecutorTest.Response(404, {})

        def patch(self, path, token=None, body=None, headers=None):
            self.calls.append(("PATCH", path))
            user = self._user_for_token(token)
            if user is None:
                return AuthSessionLifecycleExecutorTest.Response(401, {})
            phone = self._phone_of(self.users, user)
            if self.users[phone]["password"] != (body or {}).get("oldPassword"):
                return AuthSessionLifecycleExecutorTest.Response(400, {})
            self.users[phone]["password"] = body.get("newPassword")
            user["access_floor"] = self.clock
            user["refresh_alive"].clear()
            return AuthSessionLifecycleExecutorTest.Response(204, {})

        def request(self, method, path, token=None, body=None, params=None,
                    raw_body=None, headers=None):
            if method == "DELETE" and path == "/api/v1/users/me":
                user = self._user_for_token(token)
                if user is None:
                    return AuthSessionLifecycleExecutorTest.Response(401, {})
                phone = self._phone_of(self.users, user)
                if self.users[phone]["password"] != (body or {}).get("password"):
                    return AuthSessionLifecycleExecutorTest.Response(401, {})
                self.users[phone]["deleted"] = True
                self.deleted_users.append(phone)
                return AuthSessionLifecycleExecutorTest.Response(204, {})
            return AuthSessionLifecycleExecutorTest.Response(404, {})

    class FakeBarrier:
        def __init__(self, upstream):
            self.upstream = upstream
            self.url = "http://127.0.0.1:9"
            self.rules = []
            self.released = 0
            self.closed = False

        def hold(self, **kwargs):
            self.rules.append(kwargs)
            return len(self.rules)

        def wait_held(self, n=1, timeout=5.0):
            return [{"id": 1, "path": "s2c MESSAGE /topic/conversations.conv-direct-1"}]

        def release(self, *args, **kwargs):
            self.released += 1
            return [1]

        def close(self):
            self.closed = True

    class FakeReader:
        """Replays scripted STOMP envelopes; each events() call drains one script."""

        def __init__(self, ws_url, token, call_scripts):
            self.ws_url = ws_url
            self.token = token
            self.call_scripts = [list(script) for script in call_scripts]
            self.subscriptions = []
            self.closed = False

        def subscribe(self, destination):
            self.subscriptions.append(destination)

        def events(self, seconds):
            script = self.call_scripts.pop(0) if self.call_scripts else []
            for kind in script:
                if kind == "marker":
                    yield {"eventType": "MESSAGE_CREATED",
                           "conversationId": "conv-direct-1",
                           "payload": {"id": "msg-1",
                                       "content": AuthSessionLifecycleExecutorTest.MARKER},
                           "_destination": self.subscriptions[0],
                           "_subscription": "sub-1"}

        def close(self):
            self.closed = True

    @staticmethod
    def _reader_factory(script_by_token_phone):
        made = []

        def factory(ws_url, token):
            phone = next((p for p in script_by_token_phone if p in token), "")
            reader = AuthSessionLifecycleExecutorTest.FakeReader(
                ws_url, token, script_by_token_phone.get(phone, []))
            made.append(reader)
            return reader

        factory.made = made
        return factory

    def setUp(self):
        self.row = journeys.ROW_BY_ID["auth-session-lifecycle"]
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.client = self.FakeAuthClient()
        self.barrier = self.FakeBarrier("http://127.0.0.1:18093")
        self.readers = self._reader_factory({
            self.PHONE_A: [[]],
            self.PHONE_B: [["marker"], []],
        })

    def _context(self):
        return {
            "serial": "emulator-5554",
            "base_url": "http://127.0.0.1:18093",
            "row": self.row,
            "client": self.client,
            "barrier_factory": lambda upstream: self.barrier,
            "frame_reader_factory": self.readers,
            "auth_phone": self.PHONE_REST,
            "auth_pause_phone_a": self.PHONE_A,
            "auth_pause_phone_b": self.PHONE_B,
            "auth_pause_marker": self.MARKER,
            "row_dir": Path(self.tmp.name),
            "evidence_root": Path(self.tmp.name),
            "stomp_events": [],
        }

    def _artifact(self):
        return json.loads(
            (Path(self.tmp.name) / "auth-scenarios.json").read_text(encoding="utf-8"))

    def test_rest_and_paused_frame_driven_reports_partial_coverage(self):
        context = self._context()
        result = journeys.run_auth_session_lifecycle(self.row, context)
        artifact = self._artifact()
        driven = {item["scenario"] for item in artifact["scenarios"]}
        blocked = {item["scenario"]: item["reason"] for item in artifact["blocked"]}
        self.assertEqual(driven, {
            "register", "login", "invalid-credentials-rest", "refresh",
            "logout", "logout-all", "password-change",
            "paused-frame-a-to-b-isolation",
        })
        self.assertEqual(set(blocked), {
            "register-ui", "password-change-ui", "invalid-credentials-ui",
            "saved-session-restart", "logout-ui", "logout-all-ui",
            "account-switch-ui",
        })
        for name in ("invalid-credentials-ui", "saved-session-restart",
                     "logout-ui", "logout-all-ui", "account-switch-ui"):
            self.assertIn("device-bound campaign context", blocked[name])
        self.assertIn("mail sink", blocked["register-ui"])
        # The row passes on its REST-provable core and reports the undriven
        # scenarios explicitly instead of collapsing into BLOCKED_CAPABILITY.
        self.assertEqual(set(result["scenarios"]), driven)
        self.assertEqual(result["blocked"], artifact["blocked"])
        self.assertIn("partial", result["coverage"])
        self.assertIn("the REST session lifecycle is proven", result["coverage"])
        self.assertIn("unproven: register-ui", result["coverage"])
        self.assertIn("account-switch-ui", result["coverage"])
        # The driven STOMP hold is never listed as unproven.
        self.assertNotIn("paused-frame-a-to-b-isolation", result["coverage"])
        # REST lifecycle really issued the auth calls.
        paths = [path for _method, path in self.client.calls]
        for expected in ("/api/v1/auth/register", "/api/v1/auth/login",
                         "/api/v1/auth/refresh", "/api/v1/auth/logout",
                         "/api/v1/auth/logout-all", "/api/v1/auth/password"):
            self.assertIn(expected, paths)
        # The tunnel hold targeted account A's STOMP token on the topic.
        self.assertEqual(len(self.barrier.rules), 1)
        rule = self.barrier.rules[0]
        self.assertEqual(rule["method"], "MESSAGE")
        self.assertTrue(rule["path"].startswith("^/topic/conversations"))
        token_a = next(t for t in self.client.sessions
                       if self.PHONE_A in t)
        self.assertEqual(rule["token_contains"], token_a)
        self.assertEqual(self.barrier.released, 1)
        self.assertTrue(self.barrier.closed)
        # Both readers subscribed to the same topic through the barrier URL.
        readers = self.readers.made
        self.assertEqual(len(readers), 2)
        self.assertTrue(all(r.ws_url.endswith("/ws") for r in readers))
        self.assertEqual(readers[0].subscriptions, ["/topic/conversations.conv-direct-1"])
        self.assertEqual(readers[1].subscriptions, ["/topic/conversations.conv-direct-1"])
        self.assertTrue(readers[0].closed and readers[1].closed)
        # Disposable accounts were deleted through DELETE /users/me.
        self.assertEqual(len(self.client.deleted_users), 3)
        # Driven STOMP evidence landed in the shared transcript list.
        self.assertTrue(any(e.get("eventType") == "MESSAGE_CREATED"
                            for e in context["stomp_events"]))
        for secret in ("at-+", "rt-+", journeys.DISPOSABLE_PASSWORD,
                       journeys._AUTH_NEW_PASSWORD):
            self.assertNotIn(secret,
                             json.dumps(artifact))
            self.assertNotIn(secret, json.dumps(result))

    def test_held_frame_crossing_into_b_fails_the_row(self):
        readers = self._reader_factory({
            self.PHONE_A: [[]],
            self.PHONE_B: [["marker"], ["marker"]],
        })
        context = self._context()
        context["frame_reader_factory"] = readers
        with self.assertRaises(journeys.ProductFailure) as caught:
            journeys.run_auth_session_lifecycle(self.row, context)
        self.assertIn("crossed into account B", str(caught.exception))

    def test_wait_held_timeout_is_a_product_failure(self):
        class NeverHeld(self.FakeBarrier):
            def wait_held(self, n=1, timeout=5.0):
                raise TimeoutError("nothing held")

        barrier = NeverHeld("http://127.0.0.1:18093")
        context = self._context()
        context["barrier_factory"] = lambda upstream: barrier
        with self.assertRaises(journeys.ProductFailure) as caught:
            journeys.run_auth_session_lifecycle(self.row, context)
        self.assertIn("was not held", str(caught.exception))

    def test_rest_only_stack_reports_the_stomp_hold_as_unproven(self):
        """Plan 2.3(b): without a WS hold tunnel the row still passes its
        REST-provable core and names the paused-frame hold as unproven."""

        def unavailable_factory(upstream):
            raise RuntimeError("no WS tunnel on this stack")

        context = self._context()
        context["barrier_factory"] = unavailable_factory
        result = journeys.run_auth_session_lifecycle(self.row, context)
        artifact = self._artifact()
        driven = {item["scenario"] for item in artifact["scenarios"]}
        self.assertEqual(driven, {
            "register", "login", "invalid-credentials-rest", "refresh",
            "logout", "logout-all", "password-change",
        })
        blocked = {item["scenario"]: item["reason"] for item in artifact["blocked"]}
        self.assertIn("the WS hold tunnel could not be established",
                      blocked["paused-frame-a-to-b-isolation"])
        self.assertIn("stays unproven on this stack",
                      blocked["paused-frame-a-to-b-isolation"])
        self.assertIn("unproven: paused-frame-a-to-b-isolation",
                      result["coverage"])
        self.assertIn("the REST session lifecycle is proven", result["coverage"])
        # The disposable accounts (rest + the two pause accounts registered
        # before the tunnel attempt) were still cleaned up.
        self.assertEqual(len(self.client.deleted_users), 3)

    def test_rotated_away_refresh_token_reuse_fails_the_row(self):
        class ReusingRefresh(self.FakeAuthClient):
            """Defect: rotation leaves the rotated-away refresh token usable."""

            def post(self, path, token=None, body=None, headers=None):
                if path == "/api/v1/auth/refresh":
                    self.calls.append(("POST", path))
                    refresh = (body or {}).get("refreshToken")
                    owner = next((user for user in self.users.values()
                                  if refresh in user["refresh_alive"]), None)
                    if owner is None:
                        return AuthSessionLifecycleExecutorTest.Response(401, {})
                    phone = self._phone_of(self.users, owner)
                    return AuthSessionLifecycleExecutorTest.Response(
                        200, self._issue(phone))
                return super().post(path, token=token, body=body, headers=headers)

        context = self._context()
        context["client"] = ReusingRefresh()
        with self.assertRaises(journeys.ProductFailure) as caught:
            journeys.run_auth_session_lifecycle(self.row, context)
        self.assertIn("rotated-away refresh token rejection returned HTTP 200",
                      str(caught.exception))
        # Cleanup still removed the disposable account after the failure.
        self.assertEqual(len(context["client"].deleted_users), 1)

    def test_logout_all_sparing_a_concurrent_session_fails_the_row(self):
        class SparingLogoutAll(self.FakeAuthClient):
            """Defect: logout-all revokes only the newest session's token."""

            def post(self, path, token=None, body=None, headers=None):
                if path == "/api/v1/auth/logout-all":
                    self.calls.append(("POST", path))
                    user = self._user_for_token(token)
                    if user is None:
                        return AuthSessionLifecycleExecutorTest.Response(401, {})
                    user["access_floor"] = self.clock
                    refreshes = sorted(user["refresh_alive"])
                    user["refresh_alive"].clear()
                    if refreshes:
                        user["refresh_alive"].add(refreshes[-1])
                    return AuthSessionLifecycleExecutorTest.Response(204, {})
                return super().post(path, token=token, body=body, headers=headers)

        context = self._context()
        context["client"] = SparingLogoutAll()
        with self.assertRaises(journeys.ProductFailure) as caught:
            journeys.run_auth_session_lifecycle(self.row, context)
        self.assertIn("logout-all concurrent session refresh token rejection "
                      "returned HTTP 200", str(caught.exception))
        self.assertEqual(len(context["client"].deleted_users), 1)

    def test_single_logout_killing_every_session_fails_the_row(self):
        class LogoutKillsAll(self.FakeAuthClient):
            """Defect: single logout revokes every session like logout-all."""

            def post(self, path, token=None, body=None, headers=None):
                if path == "/api/v1/auth/logout":
                    self.calls.append(("POST", path))
                    user = self._user_for_token(token)
                    if user is None:
                        return AuthSessionLifecycleExecutorTest.Response(401, {})
                    user["access_floor"] = self.clock
                    user["refresh_alive"].clear()
                    return AuthSessionLifecycleExecutorTest.Response(204, {})
                return super().post(path, token=token, body=body, headers=headers)

        context = self._context()
        context["client"] = LogoutKillsAll()
        with self.assertRaises(journeys.ProductFailure) as caught:
            journeys.run_auth_session_lifecycle(self.row, context)
        self.assertIn("concurrent-session refresh after single logout "
                      "returned HTTP 401", str(caught.exception))
        self.assertEqual(len(context["client"].deleted_users), 1)


class SeedFixtureHealTest(unittest.TestCase):
    """Offline REST world for the local-mode seed fixture self-heal.

    The fake server answers the same endpoints the healer drives through the
    recording client: PATCH /users/me echoes the stored display_name, the
    inbox serves default and archived views, and POST /conversations/group
    re-creates the consumed forward target. A drift the server refuses to
    heal must surface as FAIL_HARNESS_OR_ENVIRONMENT, never as a silent
    pass.
    """

    PHONE_AN = "+84900000001"
    PHONE_BINH = "+84900000002"
    PHONE_CHI = "+84900000003"
    PHONE_DUNG = "+84900000004"
    TOKENS = {PHONE_AN: "tok-an", PHONE_BINH: "tok-binh",
              PHONE_CHI: "tok-chi", PHONE_DUNG: "tok-dung"}

    class Response:
        def __init__(self, status, body=None):
            self.status = status
            self._body = body

        def json(self):
            return self._body

    class Api:
        USER_A = {"phone": "+84900000001", "name": "An"}
        USER_B = {"phone": "+84900000003", "name": "Chi"}
        USER_C = {"phone": "+84900000002", "name": "Binh"}

        def __init__(self):
            self.direct_status = 200

        def direct_conversation(self, recorder, token, phone):
            return self.direct_status, {"id": "conv-an-binh"}

    GROUP = {"id": "conv-team", "type": "GROUP", "title": "NexaChat Team",
             "members": [{"userId": "user-an"}, {"userId": "user-binh"},
                         {"userId": "user-chi"}, {"userId": "user-dung"}]}

    class Client:
        """Canned local server: profile PATCHes, inbox views, group ops."""

        def __init__(self, display_names, inbox, archived=None, search_ids=None):
            self.display_names = dict(display_names)
            self.inbox = list(inbox)
            self.archived = list(archived or [])
            self.search_ids = dict(search_ids or {})
            self.calls = []
            self.patch_status = 200
            self.group_create_status = 201
            self.member_add_status = 200

        def _response(self, status, body=None):
            return SeedFixtureHealTest.Response(status, body)

        def get(self, path, token=None, params=None, headers=None):
            if path == "/api/v1/conversations":
                if (params or {}).get("archived") == "true":
                    return self._response(200, list(self.archived))
                return self._response(200, list(self.inbox))
            if path == "/api/v1/users/search":
                found = self.search_ids.get((params or {}).get("phone"))
                return self._response(200, [{"id": found}] if found else [])
            return self._response(404)

        def patch(self, path, token=None, body=None, headers=None):
            self.calls.append(("PATCH", path, dict(body or {})))
            if self.patch_status != 200:
                return self._response(self.patch_status, {})
            phone = next(item for item, value in SeedFixtureHealTest.TOKENS.items()
                         if value == token)
            name = (body or {}).get("displayName")
            self.display_names[phone] = name
            return self._response(200, {"displayName": name})

        def post(self, path, token=None, body=None, headers=None):
            self.calls.append(("POST", path, dict(body or {})))
            if path == "/api/v1/conversations/group":
                if self.group_create_status != 201:
                    return self._response(self.group_create_status, {})
                member_ids = list((body or {}).get("memberIds") or [])
                entry = {"id": "conv-team-new", "type": "GROUP",
                         "title": (body or {}).get("title"),
                         "members": [{"userId": "user-an"}] +
                         [{"userId": member} for member in member_ids]}
                self.inbox.append(entry)
                return self._response(201, {"id": entry["id"]})
            if path.endswith("/members"):
                if self.member_add_status != 200:
                    return self._response(self.member_add_status, {})
                entry = next(item for item in self.inbox + self.archived
                             if item.get("id") == path.split("/")[4])
                entry.setdefault("members", []).append(
                    {"userId": ((body or {}).get("userIds") or [None])[0]})
                return self._response(200, {})
            return self._response(404)

        def delete(self, path, token=None, params=None, headers=None):
            self.calls.append(("DELETE", path, None))
            if path.endswith("/archive"):
                entry = next(item for item in self.archived
                             if item.get("id") == path.split("/")[4])
                self.archived.remove(entry)
                self.inbox.append(entry)
                return self._response(204, {})
            return self._response(404)

    def _sessions(self, client):
        def session(api, recorder, phone):
            return {
                "accessToken": SeedFixtureHealTest.TOKENS[phone],
                "user": {"id": "user-" + SeedFixtureHealTest.TOKENS[phone][4:],
                         "phone": phone,
                         "displayName": client.display_names.get(phone, "")},
            }
        return session

    def _healthy_names(self):
        return {self.PHONE_AN: "An", self.PHONE_BINH: "Binh",
                self.PHONE_CHI: "Chi", self.PHONE_DUNG: "Dung"}

    def _ensure(self, api, client):
        with mock.patch.object(journeys, "_actor_session",
                                side_effect=self._sessions(client)):
            return journeys.ensure_seed_fixture_state(api, client)

    def test_healthy_fixture_reports_no_heals(self):
        client = self.Client(self._healthy_names(), [dict(self.GROUP)])
        report = self._ensure(self.Api(), client)
        self.assertEqual(report["heals"], [])
        self.assertEqual(
            [call[0] for call in client.calls], [])  # no PATCH/POST was needed
        self.assertEqual(report["checked"][-1], "NexaChat Team")

    def test_drifted_display_names_are_restored_over_rest(self):
        names = self._healthy_names()
        names[self.PHONE_AN] = "An Nguyen"      # the observed board drift
        names[self.PHONE_BINH] = "Binh Le"
        client = self.Client(names, [dict(self.GROUP)])
        report = self._ensure(self.Api(), client)
        self.assertEqual(client.display_names, self._healthy_names())
        patches = [call for call in client.calls if call[0] == "PATCH"]
        self.assertEqual([call[2]["displayName"] for call in patches],
                         ["An", "Binh"])
        self.assertTrue(any("An" in heal and "An Nguyen" in heal
                            for heal in report["heals"]))
        self.assertTrue(any("Binh" in heal and "Binh Le" in heal
                            for heal in report["heals"]))

    def test_display_name_heal_refused_by_server_is_environment_failure(self):
        names = self._healthy_names()
        names[self.PHONE_AN] = "An Nguyen"
        client = self.Client(names, [dict(self.GROUP)])
        client.patch_status = 400
        with self.assertRaisesRegex(journeys.EnvironmentFailure, "An Nguyen"):
            self._ensure(self.Api(), client)

    def test_missing_forward_target_is_recreated_with_seed_peers(self):
        client = self.Client(self._healthy_names(), [],
                             search_ids={self.PHONE_CHI: "user-chi",
                                         self.PHONE_DUNG: "user-dung"})
        an = {"accessToken": self.TOKENS[self.PHONE_AN], "user": {"id": "user-an"}}
        binh = {"accessToken": self.TOKENS[self.PHONE_BINH],
                "user": {"id": "user-binh"}}
        group_id, heals = journeys._ensure_forward_target_group(
            self.Api(), client, an, binh)
        self.assertEqual(group_id, "conv-team-new")
        create = next(call for call in client.calls
                      if call[0] == "POST" and call[1] == "/api/v1/conversations/group")
        self.assertEqual(create[2]["title"], "NexaChat Team")
        self.assertEqual(create[2]["memberIds"],
                         ["user-binh", "user-chi", "user-dung"])
        self.assertTrue(any("re-created" in heal for heal in heals))

    def test_unresolvable_seed_peers_still_yield_a_binh_group(self):
        client = self.Client(self._healthy_names(), [])  # search answers empty
        an = {"accessToken": self.TOKENS[self.PHONE_AN], "user": {"id": "user-an"}}
        binh = {"accessToken": self.TOKENS[self.PHONE_BINH],
                "user": {"id": "user-binh"}}
        group_id, _heals = journeys._ensure_forward_target_group(
            self.Api(), client, an, binh)
        create = next(call for call in client.calls
                      if call[0] == "POST" and call[1] == "/api/v1/conversations/group")
        self.assertEqual(create[2]["memberIds"], ["user-binh"])
        self.assertEqual(group_id, "conv-team-new")

    def test_failed_group_recreate_is_environment_failure(self):
        client = self.Client(self._healthy_names(), [])
        client.group_create_status = 500
        an = {"accessToken": self.TOKENS[self.PHONE_AN], "user": {"id": "user-an"}}
        binh = {"accessToken": self.TOKENS[self.PHONE_BINH],
                "user": {"id": "user-binh"}}
        with self.assertRaisesRegex(journeys.EnvironmentFailure, "re-seeding"):
            journeys._ensure_forward_target_group(self.Api(), client, an, binh)

    def test_archived_forward_target_is_restored_to_the_default_inbox(self):
        client = self.Client(self._healthy_names(), [], archived=[dict(self.GROUP)])
        an = {"accessToken": self.TOKENS[self.PHONE_AN], "user": {"id": "user-an"}}
        binh = {"accessToken": self.TOKENS[self.PHONE_BINH],
                "user": {"id": "user-binh"}}
        group_id, heals = journeys._ensure_forward_target_group(
            self.Api(), client, an, binh)
        self.assertEqual(group_id, "conv-team")
        self.assertTrue(any("archived forward target" in heal for heal in heals))
        self.assertEqual(client.inbox[0]["id"], "conv-team")
        self.assertFalse(any(call[0] == "POST" for call in client.calls))

    def test_forward_target_that_lost_binh_regains_him(self):
        group = dict(self.GROUP)
        group["members"] = [{"userId": "user-an"}]
        client = self.Client(self._healthy_names(), [group])
        an = {"accessToken": self.TOKENS[self.PHONE_AN], "user": {"id": "user-an"}}
        binh = {"accessToken": self.TOKENS[self.PHONE_BINH],
                "user": {"id": "user-binh"}}
        group_id, heals = journeys._ensure_forward_target_group(
            self.Api(), client, an, binh)
        self.assertEqual(group_id, "conv-team")
        add = next(call for call in client.calls
                   if call[0] == "POST" and call[1].endswith("/members"))
        self.assertEqual(add[2], {"userIds": ["user-binh"]})
        self.assertTrue(any("regained Binh" in heal for heal in heals))

    def test_member_add_refused_falls_back_to_a_fresh_group(self):
        group = dict(self.GROUP)
        group["members"] = [{"userId": "user-an"}]
        client = self.Client(self._healthy_names(), [group])
        client.member_add_status = 403  # An is no longer the group admin
        an = {"accessToken": self.TOKENS[self.PHONE_AN], "user": {"id": "user-an"}}
        binh = {"accessToken": self.TOKENS[self.PHONE_BINH],
                "user": {"id": "user-binh"}}
        group_id, heals = journeys._ensure_forward_target_group(
            self.Api(), client, an, binh)
        self.assertEqual(group_id, "conv-team-new")
        self.assertTrue(any("re-created" in heal for heal in heals))

    def test_recreated_direct_conversation_is_reported_as_a_heal(self):
        api = self.Api()
        api.direct_status = 201
        client = self.Client(self._healthy_names(), [dict(self.GROUP)])
        report = self._ensure(api, client)
        self.assertTrue(any("direct conversation re-created" in heal
                            for heal in report["heals"]))

    def test_run_campaign_preflight_wiring_heals_only_shared_local_runs(self):
        # The healer itself is covered above; this pins the gating: rows that
        # provision their own --dstack backend never trigger the shared-local
        # fixture heal.
        self.assertIsInstance(journeys._DSTACK_SELF_PROVISIONED_ROWS, frozenset)
        self.assertIn("otp-forgot-password", journeys._DSTACK_SELF_PROVISIONED_ROWS)
        self.assertIn("account-deletion", journeys._DSTACK_SELF_PROVISIONED_ROWS)


class ChatInteractionsExecutorTest(unittest.TestCase):
    """Offline fake device+REST world for run_chat_interactions.

    The device tree is re-rendered from a small world model on every
    uiautomator dump; taps/long-presses drive the same transitions the real
    app does, and REST history answers from the same store so a wrong tap
    stalls a wait instead of faking a pass.
    """

    class Node:
        def __init__(self, text="", desc="", rid="", box=(0, 0, 320, 48), clickable=True):
            self.text = text
            self.desc = desc
            self.attrs = {"resource-id": ("com.nexachat.debug:id/" + rid) if rid else ""}
            self.box = box
            self.clickable = clickable
            self.cls = "android.widget.TextView"

        @property
        def center(self):
            left, top, right, bottom = self.box
            return (left + right) // 2, (top + bottom) // 2

    class Store:
        """Server truth: conversation -> message list; STOMP event queue."""
        def __init__(self):
            self.seq = 100
            self.next_id = 0
            self.messages = {}
            self.events = []

        def create(self, cid, content, sender_id, sender_name,
                   reply_to_id=None, reply_to=None):
            self.next_id += 1
            self.seq += 1
            msg = {
                "id": f"m-{self.next_id}", "conversationId": cid, "seq": self.seq,
                "senderId": sender_id, "senderDisplayName": sender_name,
                "type": "TEXT", "content": content, "replyToId": reply_to_id,
                "replyTo": reply_to, "reactions": [], "attachments": [],
                "createdAt": "2026-01-01T00:00:00Z", "editedAt": None, "deletedAt": None,
            }
            self.messages.setdefault(cid, []).append(msg)
            self.events.append((f"/topic/conversations.{cid}",
                                {"eventType": "MESSAGE_CREATED", "payload": dict(msg)}))
            return msg

        def by_id(self, cid, mid):
            return next((m for m in self.messages.get(cid, []) if m["id"] == mid), None)

        def id_for_content(self, cid, content):
            msg = next((m for m in self.messages.get(cid, []) if m["content"] == content), None)
            return msg["id"] if msg else None

        def toggle_reaction(self, cid, mid, emoji, user_id):
            msg = self.by_id(cid, mid)
            msg["reactions"] = [{"emoji": emoji, "count": 1, "byMe": True}]
            self.events.append((f"/topic/conversations.{cid}", {
                "eventType": "REACTION_UPDATED",
                "payload": {"messageId": mid,
                            "reactions": [{"emoji": emoji, "count": 1, "userIds": [user_id]}]},
            }))

        def edit(self, cid, mid, content):
            msg = self.by_id(cid, mid)
            msg["content"] = content
            msg["editedAt"] = "2026-01-01T00:01:00Z"
            self.events.append((f"/topic/conversations.{cid}",
                                {"eventType": "MESSAGE_UPDATED", "payload": dict(msg)}))

        def mark_deleted(self, cid, mid):
            msg = self.by_id(cid, mid)
            msg["deletedAt"] = "2026-01-01T00:02:00Z"
            msg["content"] = None
            self.events.append((f"/topic/conversations.{cid}", {
                "eventType": "MESSAGE_DELETED",
                "payload": {"messageId": mid, "deletedAt": msg["deletedAt"]},
            }))

    class World:
        """Device surface state re-read by every uiautomator dump."""
        def __init__(self, store, direct_id, group_id):
            self.store = store
            self.direct_id = direct_id
            self.group_id = group_id
            self.screen = "inbox"
            self.sheet_open = False
            self.sheet_scrolled = False
            self.banner = None  # ("reply"|"edit", target_id, quoted_text)
            self.composer = ""
            self.longpress_id = None
            self.longpress_content = None
            self.confirm_open = False
            self.picker_open = False
            self.picker_selected = False
            self.read_upto = 0
            # Seed drift simulation: True means earlier journeys consumed the
            # 'NexaChat Team' group so it is absent from every inbox view.
            self.hide_group = False

        def tree(self):
            N = ChatInteractionsExecutorTest.Node
            if self.screen == "inbox":
                return [N(rid="inboxUserAvatar"), N(text="Binh"),
                        N(text="Chi"), N(text="NexaChat Team")]
            nodes = [N(text="Binh")]
            for msg in self.store.messages.get(self.direct_id, []):
                if msg["deletedAt"]:
                    nodes.append(N(text="This message was deleted", rid="messageBody"))
                    continue
                nodes.append(N(text=msg["content"], rid="messageBody"))
                reply = msg.get("replyTo")
                if reply:
                    nodes.append(N(text=reply["senderDisplayName"], rid="replyAuthor"))
                    nodes.append(N(text=reply["content"], rid="replySnippet"))
                if msg.get("editedAt"):
                    nodes.append(N(text="Edited", rid="editedLabel"))
                for entry in msg.get("reactions", []):
                    suffix = "yours" if entry.get("byMe") else ""
                    nodes.append(N(text=f"{entry['emoji']} {entry['count']}",
                                   desc=f"{entry['emoji']}, {entry['count']}, {suffix}"))
                if msg["senderId"] == "user-an":
                    nodes.append(N(desc="Read" if msg["seq"] <= self.read_upto else "Sent",
                                   rid="deliveryIcon"))
            if self.banner:
                kind, _target, quoted = self.banner
                prefix = "An" if kind == "reply" else "Edit"
                nodes.append(N(text=f"{prefix}: {quoted}", rid="replyPreview"))
            nodes.append(N(text=self.composer or "Message", rid="messageInput",
                           box=(60, 580, 260, 628)))
            if self.composer or (self.banner and self.banner[0] == "edit"):
                editing = self.banner and self.banner[0] == "edit"
                nodes.append(N(desc="Save changes" if editing else "Send", rid="sendButton"))
            if self.sheet_open:
                nodes.append(N(text="Message actions", rid="messageActionsTitle"))
                for label in ("Like", "Love", "Laugh", "Surprised", "Sad", "Thanks"):
                    nodes.append(N(desc=label, rid="reactionTile"))
                rows = [("Reply", 0), ("Copy", 0), ("Forward", 0), ("Translate", 0),
                        ("Star", 0), ("Report", 0), ("Edit", 1), ("Recall", 1)]
                y = 240
                for label, needs_scroll in rows:
                    if needs_scroll and not self.sheet_scrolled:
                        continue
                    nodes.append(N(text=label, rid="actionLabel", box=(0, y, 320, y + 44)))
                    y += 44
                nodes.append(N(rid="messageActionList", box=(0, 200, 320, 620)))
            if self.confirm_open:
                nodes.append(N(text="Recall message?"))
                nodes.append(N(text="Cancel"))
                nodes.append(N(text="Delete"))
            if self.picker_open:
                nodes.append(N(rid="forwardDialogRoot", box=(0, 100, 320, 640)))
                nodes.append(N(text="Forward message", rid="forwardTitle"))
                for name in ("Binh", "Chi", "NexaChat Team"):
                    nodes.append(N(text=name, desc=name))
                nodes.append(N(text="Send", rid="forwardSend"))
            return nodes

    class Device:
        def __init__(self, world):
            self.world = world

        def nodes(self):
            return self.world.tree()

        def tap_node(self, node):
            w = self.world
            rid = node.attrs["resource-id"]
            if w.screen == "inbox" and node.text == "Binh":
                w.screen = "chat"
                return
            if w.picker_open:
                if node.desc == "NexaChat Team":
                    w.picker_selected = True
                    return
                if rid.endswith("/forwardSend") and w.picker_selected:
                    w.picker_open = False
                    w.store.create(w.group_id, w.longpress_content, "user-an", "An")
                    return
            if w.confirm_open:
                if node.text == "Delete":
                    w.confirm_open = False
                    w.store.mark_deleted(w.direct_id, w.longpress_id)
                elif node.text == "Cancel":
                    w.confirm_open = False
                return
            if w.sheet_open:
                if node.desc == "Like":
                    w.sheet_open = False
                    w.store.toggle_reaction(w.direct_id, w.longpress_id, "👍", "user-an")
                    return
                if rid.endswith("/actionLabel"):
                    label = node.text
                    w.sheet_open = False
                    if label == "Reply":
                        w.banner = ("reply", w.longpress_id, w.longpress_content)
                    elif label == "Edit":
                        w.banner = ("edit", w.longpress_id, w.longpress_content)
                        w.composer = w.longpress_content
                    elif label == "Recall":
                        w.confirm_open = True
                    elif label == "Forward":
                        w.picker_open = True
                    return
            if rid.endswith("/messageInput"):
                return
            if rid.endswith("/sendButton"):
                content = w.composer.strip()
                if not content:
                    return
                if w.banner and w.banner[0] == "edit":
                    w.store.edit(w.direct_id, w.banner[1], content)
                elif w.banner and w.banner[0] == "reply":
                    target = w.store.by_id(w.direct_id, w.banner[1])
                    w.store.create(
                        w.direct_id, content, "user-an", "An",
                        reply_to_id=w.banner[1],
                        reply_to={"id": w.banner[1], "senderId": "user-an",
                                  "senderDisplayName": "An", "type": "TEXT",
                                  "content": target["content"], "deleted": False})
                else:
                    w.store.create(w.direct_id, content, "user-an", "An")
                w.banner = None
                w.composer = ""

        def long_press(self, node, duration_ms=800):
            w = self.world
            if node.attrs["resource-id"].endswith("/messageBody") and w.screen == "chat":
                w.longpress_content = node.text
                w.longpress_id = w.store.id_for_content(w.direct_id, node.text)
                w.sheet_open = True
                w.sheet_scrolled = False

        def type_text(self, value):
            self.world.composer += value

        def move_end(self):
            return None

        def delete_chars(self, count):
            self.world.composer = self.world.composer[:-count] if count else self.world.composer

        def swipe(self, x1, y1, x2, y2, duration_ms=300):
            self.world.sheet_scrolled = True

        def screenshot(self, path):
            Path(path).write_bytes(b"png")

        def key(self, code):
            return None

    class Patch:
        @staticmethod
        def ui_nodes(device, timeout=8):
            return device.nodes()

        @staticmethod
        def node_by_id(nodes, suffix):
            return next((n for n in nodes
                         if n.attrs["resource-id"].endswith("/" + suffix)), None)

        @staticmethod
        def named_conversation_row(nodes, display_name):
            return next((n for n in nodes
                         if n.text.strip() == display_name or n.desc.strip() == display_name), None)

        @staticmethod
        def find_chat_composer(nodes):
            node = ChatInteractionsExecutorTest.Patch.node_by_id(nodes, "messageInput")
            assert node is not None
            return node

        @staticmethod
        def grant_test_permissions():
            return {}

        @staticmethod
        def filtered_logcat(baseline):
            return [], False

    class Adb:
        serial = "emulator-5554"

        def command(self, *args, **kwargs):
            return ""

    class Response:
        def __init__(self, status, body=None):
            self.status = status
            self._body = body

        def json(self):
            return self._body

    class Client:
        base_url = "http://127.0.0.1:18093"
        timeout = 30

        def __init__(self, store, world, direct_id, group_id):
            self.store = store
            self.world = world
            self.direct_id = direct_id
            self.group_id = group_id

        def get(self, path, token=None, params=None, headers=None):
            if path == "/api/v1/conversations":
                # 'NexaChat Team' sits beyond page 1 of An's stale-heavy inbox;
                # a paged fetch must never see it — the executor reads unpaged.
                items = [{"id": self.direct_id, "type": "DIRECT"}]
                if not self.world.hide_group:
                    items.append({"id": self.group_id, "type": "GROUP",
                                  "title": "NexaChat Team",
                                  "members": [{"userId": "user-an"},
                                              {"userId": "user-binh"}]})
                if (params or {}).get("limit"):
                    items = items[:1]
                return ChatInteractionsExecutorTest.Response(200, items)
            if "/messages" in path:
                cid = path.split("/")[4]
                return ChatInteractionsExecutorTest.Response(
                    200, {"items": [dict(m) for m in self.store.messages.get(cid, [])]})
            return ChatInteractionsExecutorTest.Response(404)

        def post(self, path, token=None, body=None, headers=None):
            if path == "/api/v1/conversations/group":
                # The executor's re-seed path: the consumed forward target is
                # re-created, so it reappears in every inbox view.
                self.world.hide_group = False
                return ChatInteractionsExecutorTest.Response(201, {"id": self.group_id})
            if path.endswith("/read"):
                self.world.read_upto = int((body or {}).get("upToSeq") or 0)
                self.store.events.append((f"/topic/conversations.{self.direct_id}", {
                    "eventType": "MESSAGE_READ",
                    "payload": {"userId": "user-binh", "upToSeq": self.world.read_upto},
                }))
                return ChatInteractionsExecutorTest.Response(204)
            return ChatInteractionsExecutorTest.Response(404)

        def delete(self, path, token=None, params=None, headers=None):
            parts = path.split("/")
            cid, mid = parts[4], parts[6]
            msg = self.store.by_id(cid, mid)
            if msg is not None and not msg.get("deletedAt"):
                msg["deletedAt"] = "2026-01-01T00:03:00Z"
                msg["content"] = None
            return ChatInteractionsExecutorTest.Response(204)

    class Reader:
        def __init__(self, store):
            self.store = store
            self.subscriptions = []

        def subscribe(self, destination):
            self.subscriptions.append(destination)

        def events(self, seconds):
            pending, self.store.events = self.store.events, []
            out = []
            for destination, envelope in pending:
                envelope["_destination"] = destination
                out.append(envelope)
            return iter(out)

        def close(self):
            return None

    def _world_fixture(self):
        direct_id, group_id = "conv-an-binh", "conv-team"
        store = self.Store()
        world = self.World(store, direct_id, group_id)
        client = self.Client(store, world, direct_id, group_id)
        reader = self.Reader(store)
        return store, world, client, reader, direct_id, group_id

    def test_executor_drives_react_reply_edit_recall_forward_and_receipt(self):
        store, world, client, reader, direct_id, group_id = self._world_fixture()
        row = journeys.ROW_BY_ID["chat-interactions"]

        class Api:
            USER_A = {"phone": "+84900000001"}
            USER_C = {"phone": "+84900000002"}

            @staticmethod
            def direct_conversation(client_obj, token, phone):
                return 200, {"id": direct_id}

        def session(api, recorder, phone):
            if phone.endswith("0001"):
                return {"accessToken": "tok-an", "user": {"id": "user-an", "phone": phone}}
            return {"accessToken": "tok-binh", "user": {"id": "user-binh", "phone": phone}}

        context = {
            "serial": "emulator-5554",
            "base_url": "http://127.0.0.1:18093",
            "row": row,
            "row_dir": Path(tempfile.mkdtemp()),
            "adb": self.Adb(),
            "device": self.Device(world),
            "helpers": (Api(), None, self.Patch()),
            "client": client,
            "stomp_events": [],
            "frame_reader": None,
            "frame_reader_factory": lambda url, token: reader,
            "ui_timeout": 2,
        }
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session):
            result = journeys.run_chat_interactions(row, context)

        self.assertEqual(set(result) - {"cleanup"}, {
            "reaction", "reply", "edit", "recall", "forward", "read_receipt"})
        direct = store.messages[direct_id]
        contents = [m["content"] for m in direct]
        # Every unique marker reached the store exactly once; all were cleaned
        # up by the executor's soft-delete sweep.
        self.assertTrue(all(m["deletedAt"] for m in direct))
        self.assertTrue(any(m["replyToId"] for m in direct))
        self.assertTrue(any(m["editedAt"] for m in direct))
        self.assertTrue(any(m["reactions"] for m in direct))
        group_msgs = store.messages[group_id]
        self.assertEqual(len(group_msgs), 1)
        self.assertTrue(group_msgs[0]["deletedAt"])  # swept, not left live
        self.assertTrue(world.read_upto > 0)
        seen = {env["eventType"] for env in context["stomp_events"]}
        self.assertTrue({"MESSAGE_CREATED", "REACTION_UPDATED", "MESSAGE_UPDATED",
                         "MESSAGE_DELETED", "MESSAGE_READ"} <= seen)
        self.assertIn(f"/topic/conversations.{group_id}", reader.subscriptions)
        self.assertFalse(any(c.startswith("sj-") for c in contents if c))

    def test_executor_cleans_up_partial_state_after_a_product_failure(self):
        store, world, client, reader, direct_id, group_id = self._world_fixture()
        row = journeys.ROW_BY_ID["chat-interactions"]

        class Api:
            USER_A = {"phone": "+84900000001"}
            USER_C = {"phone": "+84900000002"}

            @staticmethod
            def direct_conversation(client_obj, token, phone):
                return 200, {"id": direct_id}

        def session(api, recorder, phone):
            if phone.endswith("0001"):
                return {"accessToken": "tok-an", "user": {"id": "user-an", "phone": phone}}
            return {"accessToken": "tok-binh", "user": {"id": "user-binh", "phone": phone}}

        # The product bug: tapping the reaction tile does nothing, so the chip
        # assertion must fail as FAIL_PRODUCT — and the already-sent message
        # must still be swept.
        device = self.Device(world)
        real_tap = device.tap_node

        def broken_tap(node):
            if device.world.sheet_open and node.desc == "Like":
                device.world.sheet_open = False
                return
            real_tap(node)

        device.tap_node = broken_tap
        context = {
            "serial": "emulator-5554",
            "base_url": "http://127.0.0.1:18093",
            "row": row,
            "row_dir": Path(tempfile.mkdtemp()),
            "adb": self.Adb(),
            "device": device,
            "helpers": (Api(), None, self.Patch()),
            "client": client,
            "stomp_events": [],
            "frame_reader": None,
            "frame_reader_factory": lambda url, token: reader,
            "ui_timeout": 1,
        }
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session):
            with self.assertRaises(journeys.ProductFailure):
                journeys.run_chat_interactions(row, context)
        # The sent message reached the store and was tombstoned by cleanup even
        # though the reaction assertion failed.
        self.assertEqual(len(store.messages[direct_id]), 1)
        self.assertTrue(store.messages[direct_id][0]["deletedAt"])

    def test_executor_reseeds_a_consumed_forward_target_instead_of_env_failing(self):
        store, world, client, reader, direct_id, group_id = self._world_fixture()
        world.hide_group = True  # earlier journeys consumed the seed group
        row = journeys.ROW_BY_ID["chat-interactions"]

        class Api:
            USER_A = {"phone": "+84900000001", "name": "An"}
            USER_B = {"phone": "+84900000003", "name": "Chi"}
            USER_C = {"phone": "+84900000002", "name": "Binh"}

            @staticmethod
            def direct_conversation(client_obj, token, phone):
                return 200, {"id": direct_id}

        def session(api, recorder, phone):
            if phone.endswith("0001"):
                return {"accessToken": "tok-an", "user": {"id": "user-an", "phone": phone}}
            return {"accessToken": "tok-binh", "user": {"id": "user-binh", "phone": phone}}

        context = {
            "serial": "emulator-5554",
            "base_url": "http://127.0.0.1:18093",
            "row": row,
            "row_dir": Path(tempfile.mkdtemp()),
            "adb": self.Adb(),
            "device": self.Device(world),
            "helpers": (Api(), None, self.Patch()),
            "client": client,
            "stomp_events": [],
            "frame_reader": None,
            "frame_reader_factory": lambda url, token: reader,
            "ui_timeout": 2,
        }
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session):
            result = journeys.run_chat_interactions(row, context)

        # The forward landed in the re-seeded group and was swept afterwards.
        self.assertTrue(result["forward"])
        group_msgs = store.messages[group_id]
        self.assertEqual(len(group_msgs), 1)
        self.assertTrue(group_msgs[0]["deletedAt"])
        self.assertFalse(world.hide_group)
        self.assertTrue(context["seed_heals"])


class SummaryAskExecutorTest(unittest.TestCase):
    """Offline fake device+REST world for run_summary_ask.

    The world model mirrors the real surfaces probed on emulator-5554: the
    overflow menu entries, the summary sheet's UNREAD->LAST_N scope switch,
    its "-> jump" citationChip, the ask sheet's answer card + Sources
    citationButton + EXTRACTIVE badge, and the server-side ask cache +
    ai-opt-out exclusion contract. Taps mutate the world so a wrong tap
    stalls a wait instead of faking a pass.
    """

    class Node:
        def __init__(self, text="", desc="", rid="", box=(0, 0, 320, 48), clickable=True):
            self.text = text
            self.desc = desc
            self.attrs = {"resource-id": ("com.nexachat.debug:id/" + rid) if rid else ""}
            self.box = box
            self.clickable = clickable
            self.cls = "android.widget.TextView"

    class Store:
        """Server truth: messages, the ask cache, consent and ai-opt-out."""
        def __init__(self):
            self.seq = 100
            self.next_id = 0
            self.messages = []
            self.consent = False       # An's aiSummaryConsent
            self.binh_opt_out = False  # Binh's per-conversation ai-opt-out
            self.asks = 0

        def create(self, cid, content, sender_id, sender_name):
            self.next_id += 1
            self.seq += 1
            msg = {
                "id": f"m-{self.next_id}", "conversationId": cid, "seq": self.seq,
                "senderId": sender_id, "senderDisplayName": sender_name,
                "type": "TEXT", "content": content,
                "createdAt": "2026-01-01T00:00:00Z", "deletedAt": None,
            }
            self.messages.append(msg)
            return msg

        def live(self):
            return [m for m in self.messages if not m["deletedAt"]]

        def fact(self):
            return next((m for m in self.messages
                         if m["content"] and "locker passphrase" in m["content"]), None)

        def ask(self, cid, question):
            self.asks += 1
            fact = self.fact()
            if self.binh_opt_out or fact is None or not fact["content"]:
                return {
                    "mode": "NOT_FOUND", "answer": None, "citations": [],
                    "contextOmitted": self.binh_opt_out,
                    "usage": {"cached": False, "llmCalls": 0},
                }
            return {
                "mode": "CACHE", "answer": fact["content"],
                "citations": [{"messageId": fact["id"], "seq": fact["seq"],
                               "snippet": fact["content"]}],
                "contextOmitted": False,
                "usage": {"cached": True, "llmCalls": 0},
            }

    class World:
        """Device surface state re-read by every uiautomator dump."""
        def __init__(self, store, direct_id):
            self.store = store
            self.direct_id = direct_id
            self.screen = "inbox"
            self.menu_open = False
            self.summary_open = False
            self.summary_scope = "UNREAD"
            self.ask_open = False
            self.ask_draft = ""
            self.ask_answered = False
            self.ask_errors = False   # failure variant: send surfaces NOT_FOUND text
            self.fact_visible = False

        def tree(self):
            N = SummaryAskExecutorTest.Node
            if self.screen == "inbox":
                return [N(text="Binh"), N(text="NexaChat Team")]
            nodes = [N(text="Binh"), N(desc="Message actions", rid="moreButton")]
            # Only the tail of the list is "rendered"; the seeded fact (index 0)
            # stays off-screen until a citation jump lands on it.
            live = self.store.live()
            visible = live[-5:]
            if self.fact_visible:
                visible = live[:1] + [m for m in visible if m not in live[:1]]
            for msg in visible:
                text = "This message was deleted" if msg["deletedAt"] else msg["content"]
                nodes.append(N(text=text, rid="messageBody"))
            nodes.append(N(text="Message", rid="messageInput", box=(60, 580, 260, 628)))
            if self.menu_open:
                for label in ("Ask this chat", "Summarize conversation",
                              "Search in chat", "Mute notifications"):
                    nodes.append(N(text=label, rid="menuLabel"))
            if self.summary_open:
                nodes.append(N(rid="summarySheetRoot", box=(0, 60, 320, 640)))
                nodes.append(N(text="Conversation summary", rid="summaryTitle"))
                for scope in ("Unread", "Last N", "Today"):
                    nodes.append(N(text=scope, rid="summaryScopeChip"))
                if self.summary_scope == "LAST_N":
                    nodes.append(N(text="Last 50 · Local", rid="summaryResultHeader"))
                    nodes.append(N(text="Key points from the conversation",
                                   rid="summaryBulletText"))
                    nodes.append(N(text="↗ Binh · 9:00 AM", rid="citationChip"))
                else:
                    nodes.append(N(text="At least 5 messages are required",
                                   rid="summaryErrorText"))
            if self.ask_open:
                nodes.append(N(rid="askSheetRoot", box=(0, 60, 320, 640)))
                nodes.append(N(text="Ask this chat", rid="askTitle"))
                nodes.append(N(desc="Close ask this chat", rid="askClose"))
                nodes.append(N(text=self.ask_draft, rid="askQuestion",
                               box=(16, 528, 232, 624)))
                nodes.append(N(text="Ask", rid="askSend"))
                if self.ask_errors:
                    nodes.append(N(text="No matching message was found.",
                                   rid="askStatusMessage"))
                elif self.ask_answered:
                    fact = self.store.fact()
                    nodes.append(N(text=fact["content"], rid="askAnswer"))
                    nodes.append(N(text="Sources", rid="askCitationsLabel"))
                    nodes.append(N(text=fact["content"],
                                   desc="Open message: " + fact["content"],
                                   rid="citationButton"))
                    nodes.append(N(text="From a message", rid="askModeBadge"))
            return nodes

    class Device:
        def __init__(self, world):
            self.world = world

        def nodes(self):
            return self.world.tree()

        def tap_node(self, node):
            w = self.world
            rid = node.attrs["resource-id"]
            if w.screen == "inbox" and node.text == "Binh":
                w.screen = "chat"
                return
            if rid.endswith("/moreButton"):
                w.menu_open = True
                return
            if w.menu_open:
                w.menu_open = False
                if node.text == "Summarize conversation":
                    w.summary_open = True
                    w.summary_scope = "UNREAD"
                elif node.text == "Ask this chat":
                    w.ask_open = True
                return
            if w.summary_open:
                if node.text == "Last N":
                    w.summary_scope = "LAST_N"
                elif rid.endswith("/citationChip"):
                    w.summary_open = False  # real jump lands on an unread row
                return
            if w.ask_open:
                if rid.endswith("/askQuestion"):
                    return
                if rid.endswith("/askSend"):
                    if w.ask_draft.strip():
                        if w.ask_errors:
                            pass  # status text is already rendered
                        else:
                            w.ask_answered = True
                    return
                if rid.endswith("/citationButton"):
                    w.ask_open = False
                    w.fact_visible = True
                    return

        def type_text(self, value):
            if self.world.ask_open:
                self.world.ask_draft += value

        def swipe(self, x1, y1, x2, y2, duration_ms=300):
            return None

        def screenshot(self, path):
            Path(path).write_bytes(b"png")

        def key(self, code):
            w = self.world
            if w.menu_open:
                w.menu_open = False
            elif w.ask_open:
                w.ask_open = False
            elif w.summary_open:
                w.summary_open = False

    class Patch:
        @staticmethod
        def ui_nodes(device, timeout=8):
            return device.nodes()

        @staticmethod
        def named_conversation_row(nodes, display_name):
            return next((n for n in nodes
                         if n.text.strip() == display_name or n.desc.strip() == display_name),
                        None)

        @staticmethod
        def grant_test_permissions():
            return {}

        @staticmethod
        def filtered_logcat(baseline):
            return [], False

    class Adb:
        serial = "emulator-5554"

        def command(self, *args, **kwargs):
            return ""

    class Response:
        def __init__(self, status, body=None):
            self.status = status
            self._body = body

        def json(self):
            return self._body

    class Client:
        base_url = "http://127.0.0.1:18093"
        timeout = 30

        def __init__(self, store, direct_id):
            self.store = store
            self.direct_id = direct_id

        def _opt_out_path(self, path):
            return path == f"/api/v1/conversations/{self.direct_id}/ai-opt-out"

        def get(self, path, token=None, params=None, headers=None):
            R = SummaryAskExecutorTest.Response
            if path == "/api/v1/users/me/settings":
                return R(200, {"aiSummaryConsent": self.store.consent})
            if self._opt_out_path(path):
                return R(200, {"aiOptOut": self.store.binh_opt_out,
                               "conversationId": self.direct_id})
            return R(404)

        def patch(self, path, token=None, body=None, headers=None):
            R = SummaryAskExecutorTest.Response
            if path == "/api/v1/users/me/settings":
                self.store.consent = bool((body or {}).get("aiSummaryConsent"))
                return R(200, {"aiSummaryConsent": self.store.consent})
            return R(404)

        def put(self, path, token=None, body=None, headers=None):
            R = SummaryAskExecutorTest.Response
            if self._opt_out_path(path):
                self.store.binh_opt_out = True
                return R(200, {"aiOptOut": True, "conversationId": self.direct_id})
            return R(404)

        def post(self, path, token=None, body=None, headers=None):
            R = SummaryAskExecutorTest.Response
            if path.endswith("/read"):
                return R(204)
            if path.endswith("/summary"):
                return R(200, {
                    "summaryId": "sum-1", "conversationId": self.direct_id,
                    "generatedBy": "LOCAL", "cached": False, "contextOmitted": False,
                    "messageCount": len(self.store.live()), "keyPoints": ["point"],
                    "decisions": [], "actionItems": [], "questions": [],
                    "topics": [], "participants": ["An", "Binh"],
                    "createdAt": "2026-01-01T00:01:00Z",
                })
            if path.endswith("/ask"):
                return R(200, self.store.ask(self.direct_id, (body or {}).get("question", "")))
            return R(404)

        def delete(self, path, token=None, params=None, headers=None):
            R = SummaryAskExecutorTest.Response
            if self._opt_out_path(path):
                self.store.binh_opt_out = False
                return R(200, {"aiOptOut": False, "conversationId": self.direct_id})
            parts = path.split("/")
            if len(parts) == 7 and parts[5] == "messages":
                msg = next((m for m in self.store.messages if m["id"] == parts[6]), None)
                if msg is not None:
                    msg["deletedAt"] = "2026-01-01T00:03:00Z"
                    msg["content"] = None
                return R(204)
            return R(404)

    def _fixture(self, ask_errors=False):
        direct_id = "conv-an-binh"
        store = self.Store()
        world = self.World(store, direct_id)
        world.ask_errors = ask_errors
        client = self.Client(store, direct_id)

        class Api:
            USER_A = {"phone": "+84900000001"}
            USER_C = {"phone": "+84900000002"}

            @staticmethod
            def direct_conversation(client_obj, token, phone):
                return 200, {"id": direct_id}

            @staticmethod
            def send_text(client_obj, token, cid, content):
                return store.create(cid, content, "user-binh", "Binh")

        def session(api, recorder, phone):
            if phone.endswith("0001"):
                return {"accessToken": "tok-an", "user": {"id": "user-an", "phone": phone}}
            return {"accessToken": "tok-binh", "user": {"id": "user-binh", "phone": phone}}

        context = {
            "serial": "emulator-5554",
            "base_url": "http://127.0.0.1:18093",
            "row": journeys.ROW_BY_ID["summary-ask"],
            "row_dir": Path(tempfile.mkdtemp()),
            "adb": self.Adb(),
            "device": self.Device(world),
            "helpers": (Api(), None, self.Patch()),
            "client": client,
            "ui_timeout": 2,
        }
        return store, world, client, context, session

    def test_executor_drives_summary_ask_citation_jump_and_opt_out(self):
        store, world, client, context, session = self._fixture()
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session):
            result = journeys.run_summary_ask(journeys.ROW_BY_ID["summary-ask"], context)

        self.assertIn("LOCAL", result["summary"])
        self.assertIn("EXTRACTIVE", result["ask_answer"])
        self.assertEqual(result["ask_rest_verify"],
                         "CACHE replay cites the seeded message id and seq")
        self.assertIn("cited messageBody", result["citation_jump"])
        # The whole flow ran: consent was enabled, the fact+fillers were seeded,
        # the opt-out round-trip asked three times (verify/omitted/restored).
        self.assertTrue(world.fact_visible)       # ask citation jump rendered the fact
        self.assertEqual(store.asks, 3)
        # Cleanup: seeded rows tombstoned, consent + opt-out restored to pre-run.
        self.assertTrue(all(m["deletedAt"] for m in store.messages))
        self.assertFalse(store.consent)
        self.assertFalse(store.binh_opt_out)
        self.assertFalse(world.summary_open or world.ask_open or world.menu_open)

    def test_executor_restores_state_and_sweeps_after_an_ask_failure(self):
        store, world, client, context, session = self._fixture(ask_errors=True)
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session):
            with self.assertRaises(journeys.ProductFailure) as caught:
                journeys.run_summary_ask(journeys.ROW_BY_ID["summary-ask"], context)
        self.assertIn("ask sheet surfaced an error", str(caught.exception))
        # The seed happened before the failure, so every row-owned message and
        # both settings must still be cleaned up / restored.
        self.assertEqual(len(store.messages), journeys._ASK_FILLER_COUNT + 1)
        self.assertTrue(all(m["deletedAt"] for m in store.messages))
        self.assertFalse(store.consent)
        self.assertFalse(store.binh_opt_out)
        self.assertFalse(world.ask_open)


class ArchivePreferencesExecutorTest(unittest.TestCase):
    """Offline fake device+REST world for run_archive_preferences.

    The world mirrors the surfaces probed on emulator-5554: the chat overflow
    (text1 dialog rows), the shared-media screen's tabs/sender filter/30-row
    paging window, the inbox long-press actions dialog and Archived chip, the
    archive snackbar's Undo, the caller-private label CheckBox dialog, and the
    profile showSeen/showPresence switches. Per-member flags live in the
    store keyed by caller token so peer-leak assertions are real, and the
    STOMP queue only emits MESSAGE_READ while the reader's showSeen allows it.
    """

    class Node:
        def __init__(self, text="", desc="", rid="", box=(0, 0, 320, 48),
                     clickable=True, cls="android.widget.TextView", checked=None):
            self.text = text
            self.desc = desc
            self.attrs = {"resource-id": ("com.nexachat.debug:id/" + rid) if rid else ""}
            if checked is not None:
                self.attrs["checked"] = "true" if checked else "false"
            self.box = box
            self.clickable = clickable
            self.cls = cls

        @property
        def center(self):
            left, top, right, bottom = self.box
            return (left + right) // 2, (top + bottom) // 2

    class Store:
        """Server truth: messages/attachments, per-member flags, labels,
        settings, and the STOMP event queue."""

        def __init__(self):
            self.seq = 100
            self.next_id = 0
            self.next_att_id = 0
            self.next_label_id = 0
            self.messages = []
            self.attachments = {}
            self.labels = {"an": [], "binh": []}
            self.flags = {
                caller: {"muted": False, "mutedUntil": None, "pinned": False,
                         "pinnedAt": None, "archived": False, "labels": set()}
                for caller in ("an", "binh")
            }
            self.settings = {
                "an": {"showSeen": True, "showPresence": True, "aiSummaryConsent": True},
                "binh": {"showSeen": True, "showPresence": True, "aiSummaryConsent": True},
            }
            self.events = []

        @staticmethod
        def links_of(content):
            return [token for token in (content or "").split()
                    if token.startswith("https://")][:10]

        def create(self, cid, content, sender_id, attachment_ids=None, msg_type="TEXT"):
            self.next_id += 1
            self.seq += 1
            msg = {
                "id": f"m-{self.next_id}", "conversationId": cid, "seq": self.seq,
                "senderId": sender_id,
                "senderDisplayName": "Binh" if sender_id == "user-binh" else "An",
                "type": msg_type, "content": content,
                "attachments": [dict(self.attachments[aid])
                                for aid in (attachment_ids or [])],
                "createdAt": "2026-01-01T00:00:00Z", "deletedAt": None,
            }
            self.messages.append(msg)
            return msg

        def archive_items(self, cid, archive_type, sender_id=None):
            items = []
            live = (m for m in self.messages
                    if m["conversationId"] == cid and not m["deletedAt"])
            for msg in sorted(live, key=lambda m: m["seq"], reverse=True):
                base = {"messageId": msg["id"], "seq": msg["seq"],
                        "senderId": msg["senderId"], "createdAt": msg["createdAt"]}
                if archive_type == "LINK":
                    for url in self.links_of(msg["content"]):
                        items.append({**base, "kind": "LINK",
                                      "attachment": None, "link": {"url": url}})
                else:
                    for att in msg["attachments"]:
                        if archive_type == "MEDIA" and msg["type"] == "IMAGE":
                            items.append({**base, "kind": "IMAGE", "link": None,
                                          "attachment": dict(att)})
                        elif archive_type == "FILE" and msg["type"] == "FILE":
                            items.append({**base, "kind": "FILE", "link": None,
                                          "attachment": dict(att)})
            if sender_id:
                items = [item for item in items if item["senderId"] == sender_id]
            return items

        def peer_presence_for(self, caller):
            peer = "binh" if caller == "an" else "an"
            if not self.settings[peer]["showPresence"]:
                return None
            return {"online": True, "lastSeenAt": "2026-01-01T00:00:00Z"}

    class World:
        """Device surface state re-read by every uiautomator dump."""

        def __init__(self, store, direct_id):
            self.store = store
            self.direct_id = direct_id
            self.screen = "inbox"
            self.inbox_filter = "all"
            self.menu_open = False
            self.mute_dialog = False
            self.inbox_dialog = False
            self.label_dialog = False
            self.sender_dialog = False
            self.snackbar_archived = False
            self.tab = "media"
            self.sender = None
            self.offset = 0
            self.hide_tabs = False  # failure variant

        def archive_rows(self):
            kind = {"media": "MEDIA", "file": "FILE", "link": "LINK"}[self.tab]
            sender = {"user-binh": "user-binh", "user-an": "user-an"}.get(self.sender)
            items = self.store.archive_items(self.direct_id, kind, sender_id=sender)
            return items[self.offset:self.offset + 10]

        def _dialog(self, cls, *texts):
            return [ArchivePreferencesExecutorTest.Node(text=text, rid="text1",
                                                        cls=cls) for text in texts]

        def tree(self):
            N = ArchivePreferencesExecutorTest.Node
            s = self.store
            flags = s.flags["an"]
            nodes = []
            if self.screen == "inbox":
                nodes.append(N(rid="inboxUserAvatar"))
                # Real layout: the 'Active now' presence strip (which also
                # carries a 'Binh' label while the peer is online) sits ABOVE
                # the conversationList RecyclerView — row lookups must be
                # scoped inside the list or they hit the strip.
                nodes.append(N(text="Binh", desc="Binh, Active now",
                               box=(100, 196, 184, 283)))
                for rid in ("filterAllChip", "filterUnreadChip",
                            "filterGroupsChip", "filterArchivedChip"):
                    nodes.append(N(rid=rid))
                nodes.append(N(rid="conversationList", box=(0, 347, 320, 620)))
                for lab in s.labels["an"]:
                    nodes.append(N(text=lab["name"]))
                if self.inbox_filter == "all":
                    if not flags["archived"]:
                        nodes.append(N(text="Binh", desc="Binh, Online",
                                       box=(76, 355, 263, 379)))
                    nodes.append(N(text="NexaChat Team", box=(76, 427, 263, 451)))
                    nodes.append(N(text="Chi", box=(76, 499, 260, 523)))
                elif self.inbox_filter == "archived" and flags["archived"]:
                    # The app's Room projection filters the flag out of the
                    # rendered variant; a settled view drops it in ~2-3s.
                    nodes.append(N(text="Binh", desc="Binh, Online",
                                   box=(76, 355, 263, 379)))
                if self.snackbar_archived:
                    nodes.append(N(text="Conversation archived"))
                    nodes.append(N(text="Undo"))
            elif self.screen == "chat":
                nodes.append(N(text="Binh"))
                nodes.append(N(rid="moreButton"))
                nodes.append(N(text="Message", rid="messageInput", box=(60, 580, 260, 628)))
            elif self.screen == "archive":
                if not self.hide_tabs:
                    nodes.append(N(rid="archiveTabs"))
                    for tab_text in ("Photos & videos", "Files", "Links"):
                        nodes.append(N(text=tab_text))
                    chip = {"user-binh": "Sender: Binh", "user-an": "Sender: An"}.get(
                        self.sender, "All senders")
                    nodes.append(N(text=chip, rid="archiveSenderFilter"))
                    if self.sender:
                        nodes.append(N(text="Clear", rid="archiveClearFilters"))
                    nodes.append(N(rid="archiveList", box=(0, 100, 320, 620)))
                    for item in self.archive_rows():
                        if self.tab == "media":
                            nodes.append(N(text=item["attachment"]["fileName"],
                                           rid="archiveMediaTitle"))
                        elif self.tab == "file":
                            nodes.append(N(text=item["attachment"]["fileName"],
                                           rid="archiveRowTitle"))
                        else:
                            nodes.append(N(text=item["link"]["url"], rid="archiveRowTitle"))
            elif self.screen == "profile":
                nodes.append(N(text="An", rid="profileName"))
                nodes.append(N(rid="profileColumn", box=(0, 0, 320, 640)))
                nodes.append(N(rid="showSeenRow"))
                nodes.append(N(rid="showSeen", checked=s.settings["an"]["showSeen"]))
                nodes.append(N(rid="showPresenceRow"))
                nodes.append(N(rid="showPresence", checked=s.settings["an"]["showPresence"]))
            if self.menu_open:
                nodes += self._dialog(
                    "android.widget.CheckedTextView",
                    "Ask this chat", "Search in chat", "Summarize conversation",
                    "End-to-end encryption", "Chat info", "Disappearing messages",
                    "Unmute notifications" if flags["muted"] else "Mute notifications",
                    "Unarchive" if flags["archived"] else "Archive",
                    "Unpin conversation" if flags["pinned"] else "Pin conversation",
                    "Conversation settings", "Assign labels", "Shared media",
                    "Starred messages", "Block Binh", "Report")
            if self.mute_dialog:
                nodes += self._dialog(
                    "android.widget.CheckedTextView",
                    "For 1 hour", "For 4 hours", "Until 8:00 AM",
                    "Until turned back on")
            if self.inbox_dialog:
                rows = ["Unpin conversation" if flags["pinned"] else "Pin conversation"]
                rows += (["Unmute notifications"] if flags["muted"]
                         else ["For 1 hour", "For 4 hours", "Until 8:00 AM",
                               "Until turned back on"])
                rows += ["Unarchive" if flags["archived"] else "Archive",
                         "Assign labels"]
                nodes += self._dialog("android.widget.CheckedTextView", *rows)
            if self.sender_dialog:
                nodes.append(N(text="Sender"))
                nodes += self._dialog(
                    "android.widget.CheckedTextView", "All senders", "An", "Binh")
            if self.label_dialog:
                nodes.append(N(text="Conversation labels"))
                for lab in s.labels["an"]:
                    nodes.append(N(text=lab["name"], cls="android.widget.CheckBox",
                                   checked=lab["id"] in flags["labels"]))
                nodes.append(N(text="Done"))
            return nodes

    class Device:
        def __init__(self, world):
            self.world = world

        def nodes(self):
            return self.world.tree()

        def _overflow(self, node):
            w = self.world
            s = w.store
            w.menu_open = False
            text = node.text.strip()
            if text == "Mute notifications":
                w.mute_dialog = True
            elif text == "Unmute notifications":
                s.flags["an"].update(muted=False, mutedUntil=None)
            elif text == "Pin conversation":
                s.flags["an"].update(pinned=True, pinnedAt="2026-01-01T00:00:00Z")
            elif text == "Unpin conversation":
                s.flags["an"].update(pinned=False, pinnedAt=None)
            elif text == "Archive":
                s.flags["an"]["archived"] = True
                w.screen = "inbox"
                w.snackbar_archived = True
            elif text == "Unarchive":
                s.flags["an"]["archived"] = False
            elif text == "Assign labels":
                w.label_dialog = True
            elif text == "Shared media":
                w.screen = "archive"
                w.tab = "media"
                w.sender = None
                w.offset = 0

        def _inbox_sheet(self, node):
            w = self.world
            s = w.store
            w.inbox_dialog = False
            text = node.text.strip()
            if text == "Pin conversation":
                s.flags["an"].update(pinned=True, pinnedAt="2026-01-01T00:00:00Z")
            elif text == "Unpin conversation":
                s.flags["an"].update(pinned=False, pinnedAt=None)
            elif text == "Unmute notifications":
                s.flags["an"].update(muted=False, mutedUntil=None)
            elif text in ("For 1 hour", "For 4 hours", "Until 8:00 AM"):
                s.flags["an"].update(muted=True, mutedUntil="2026-01-01T01:00:00Z")
            elif text == "Until turned back on":
                s.flags["an"].update(muted=True, mutedUntil=None)
            elif text == "Archive":
                s.flags["an"]["archived"] = True
            elif text == "Unarchive":
                s.flags["an"]["archived"] = False
            elif text == "Assign labels":
                w.label_dialog = True

        def tap_node(self, node):
            w = self.world
            s = w.store
            rid = node.attrs["resource-id"]
            text = node.text.strip()
            if w.sender_dialog:
                w.sender_dialog = False
                if text == "Binh":
                    w.sender = "user-binh"
                elif text == "An":
                    w.sender = "user-an"
                else:
                    w.sender = None
                return
            if w.mute_dialog:
                w.mute_dialog = False
                if text == "Until turned back on":
                    s.flags["an"].update(muted=True, mutedUntil=None)
                else:
                    s.flags["an"].update(muted=True, mutedUntil="2026-01-01T01:00:00Z")
                return
            if w.menu_open:
                self._overflow(node)
                return
            if w.inbox_dialog:
                self._inbox_sheet(node)
                return
            if w.label_dialog:
                if "CheckBox" in node.cls:
                    lab = next((l for l in s.labels["an"] if l["name"] == text), None)
                    if lab is not None:
                        if lab["id"] in s.flags["an"]["labels"]:
                            s.flags["an"]["labels"].discard(lab["id"])
                        else:
                            s.flags["an"]["labels"].add(lab["id"])
                elif text == "Done":
                    w.label_dialog = False
                return
            if w.screen == "inbox":
                if rid.endswith("/inboxUserAvatar"):
                    w.screen = "profile"
                elif rid.endswith("/filterArchivedChip"):
                    w.inbox_filter = "archived"
                elif rid.endswith("/filterAllChip"):
                    w.inbox_filter = "all"
                elif text == "Undo" and w.snackbar_archived:
                    s.flags["an"]["archived"] = False
                    w.snackbar_archived = False
                elif text == "Binh" and node.box and node.box[1] >= 347:
                    # Only the conversationList row opens the chat; the
                    # presence-strip card (above y=347) ignores taps.
                    w.screen = "chat"
                return
            if w.screen == "chat":
                if rid.endswith("/moreButton"):
                    w.menu_open = True
                return
            if w.screen == "archive":
                if text in ("Photos & videos", "Files", "Links"):
                    w.tab = {"Photos & videos": "media", "Files": "file",
                             "Links": "link"}[text]
                    w.offset = 0
                elif rid.endswith("/archiveSenderFilter"):
                    w.sender_dialog = True
                elif rid.endswith("/archiveClearFilters"):
                    w.sender = None
                return
            if w.screen == "profile":
                if rid.endswith("/showSeen"):
                    s.settings["an"]["showSeen"] = not s.settings["an"]["showSeen"]
                elif rid.endswith("/showPresence"):
                    s.settings["an"]["showPresence"] = not s.settings["an"]["showPresence"]
                return

        def long_press(self, node, duration_ms=800):
            w = self.world
            if (w.screen == "inbox" and node.box and node.box[1] >= 347
                    and (node.text.strip() == "Binh"
                         or node.desc.strip() == "Binh")):
                # Only the conversationList row surfaces the actions sheet;
                # long-pressing the presence-strip card does nothing.
                w.inbox_dialog = True

        def swipe(self, x1, y1, x2, y2, duration_ms=300):
            w = self.world
            if w.screen == "inbox":
                return
            if w.screen != "archive":
                return
            if y2 < y1:
                w.offset += 10
            else:
                w.offset = 0

        def type_text(self, value):
            return None

        def screenshot(self, path):
            Path(path).write_bytes(b"png")

        def key(self, code):
            w = self.world
            if w.sender_dialog:
                w.sender_dialog = False
            elif w.mute_dialog:
                w.mute_dialog = False
            elif w.inbox_dialog:
                w.inbox_dialog = False
            elif w.label_dialog:
                w.label_dialog = False
            elif w.menu_open:
                w.menu_open = False
            elif w.screen == "archive":
                w.screen = "chat"
            elif w.screen == "profile":
                w.screen = "inbox"
            elif w.screen == "chat":
                w.screen = "inbox"

    class Patch:
        @staticmethod
        def ui_nodes(device, timeout=8):
            return device.nodes()

        @staticmethod
        def named_conversation_row(nodes, display_name):
            return next((n for n in nodes
                         if n.text.strip() == display_name or n.desc.strip() == display_name),
                        None)

        @staticmethod
        def grant_test_permissions():
            return {}

        @staticmethod
        def filtered_logcat(baseline):
            return [], False

    class Adb:
        serial = "emulator-5554"

        def command(self, *args, **kwargs):
            return ""

    class Response:
        def __init__(self, status, body=None):
            self.status = status
            self._body = body

        def json(self):
            return self._body

    class Client:
        base_url = "http://127.0.0.1:18093"
        timeout = 30

        def __init__(self, store, direct_id):
            self.store = store
            self.direct_id = direct_id

        @staticmethod
        def _caller(token):
            return "an" if token == "tok-an" else "binh"

        def _sender(self, token):
            return "user-" + self._caller(token)

        def request(self, method, path, token=None, body=None,
                    params=None, raw_body=None, headers=None):
            if raw_body is not None:
                data, content_type = raw_body
                marker = b'filename="'
                start = data.index(marker) + len(marker)
                filename = data[start:data.index(b'"', start)].decode()
                self.store.next_att_id += 1
                att_id = f"att-{self.store.next_att_id}"
                self.store.attachments[att_id] = {
                    "id": att_id, "fileName": filename, "mimeType": content_type,
                }
                body = dict(self.store.attachments[att_id])
                body["originalName"] = filename
                return ArchivePreferencesExecutorTest.Response(201, body)
            handler = {"GET": self.get, "POST": self.post, "PATCH": self.patch,
                       "PUT": self.put, "DELETE": self.delete}[method]
            return handler(path, token=token, body=body, params=params, headers=headers)

        def get(self, path, token=None, params=None, headers=None):
            R = ArchivePreferencesExecutorTest.Response
            caller = self._caller(token)
            if path == "/api/v1/users/me/settings":
                return R(200, dict(self.store.settings[caller]))
            if path == "/api/v1/labels":
                return R(200, [dict(lab) for lab in self.store.labels[caller]])
            if path == "/api/v1/conversations":
                archived = (params or {}).get("archived") == "true"
                flags = self.store.flags[caller]
                if bool(flags["archived"]) != archived:
                    return R(200, [])
                entry = {"id": self.direct_id, "type": "DIRECT",
                         "muted": flags["muted"], "mutedUntil": flags["mutedUntil"],
                         "pinned": flags["pinned"], "pinnedAt": flags["pinnedAt"],
                         "archived": flags["archived"], "aiOptOut": False,
                         "labels": sorted(flags["labels"]),
                         "peerPresence": self.store.peer_presence_for(caller)}
                return R(200, [entry])
            if path.endswith("/archive"):
                params = params or {}
                items = self.store.archive_items(
                    self.direct_id, params.get("type"),
                    sender_id=params.get("senderId"))
                limit = int(params.get("limit") or 30)
                cursor = params.get("cursor")
                start = int(str(cursor).rsplit("-", 1)[1]) if cursor else 0
                page = items[start:start + limit]
                nxt = f"c-{start + limit}" if start + limit < len(items) else None
                return R(200, {"items": page, "nextCursor": nxt})
            return R(404)

        def post(self, path, token=None, body=None, headers=None):
            R = ArchivePreferencesExecutorTest.Response
            caller = self._caller(token)
            if path == "/api/v1/labels":
                self.store.next_label_id += 1
                lab = {"id": f"lab-{self.store.next_label_id}",
                       "name": (body or {}).get("name"),
                       "colorId": (body or {}).get("colorId")}
                self.store.labels[caller].append(lab)
                return R(201, dict(lab))
            if path.endswith("/messages"):
                msg = self.store.create(
                    self.direct_id, (body or {}).get("content"), self._sender(token),
                    attachment_ids=(body or {}).get("attachmentIds"),
                    msg_type=(body or {}).get("type") or "TEXT")
                return R(201, dict(msg))
            if path.endswith("/read"):
                if self.store.settings[caller]["showSeen"]:
                    self.store.events.append((f"/topic/conversations.{self.direct_id}", {
                        "eventType": "MESSAGE_READ",
                        "payload": {"userId": self._sender(token),
                                    "upToSeq": (body or {}).get("upToSeq")},
                    }))
                return R(204)
            return R(404)

        def patch(self, path, token=None, body=None, headers=None):
            R = ArchivePreferencesExecutorTest.Response
            caller = self._caller(token)
            if path == "/api/v1/users/me/settings":
                self.store.settings[caller].update(body or {})
                return R(200, dict(self.store.settings[caller]))
            return R(404)

        def put(self, path, token=None, body=None, headers=None):
            R = ArchivePreferencesExecutorTest.Response
            caller = self._caller(token)
            flags = self.store.flags[caller]
            if path.endswith("/mute"):
                flags.update(muted=True, mutedUntil=(body or {}).get("mutedUntil"))
                return R(204)
            if path.endswith("/archive"):
                flags["archived"] = True
                return R(204)
            if path.endswith("/pin"):
                flags.update(pinned=True, pinnedAt="2026-01-01T00:00:00Z")
                return R(204)
            if "/labels/" in path:
                flags["labels"].add(path.rsplit("/", 1)[1])
                return R(204)
            return R(404)

        def delete(self, path, token=None, params=None, headers=None):
            R = ArchivePreferencesExecutorTest.Response
            caller = self._caller(token)
            flags = self.store.flags[caller]
            if path.endswith("/mute"):
                flags.update(muted=False, mutedUntil=None)
                return R(204)
            if path.endswith("/archive"):
                flags["archived"] = False
                return R(204)
            if path.endswith("/pin"):
                flags.update(pinned=False, pinnedAt=None)
                return R(204)
            if "/conversations/" in path and "/labels/" in path:
                flags["labels"].discard(path.rsplit("/", 1)[1])
                return R(204)
            if path.startswith("/api/v1/labels/"):
                label_id = path.rsplit("/", 1)[1]
                self.store.labels[caller] = [
                    lab for lab in self.store.labels[caller] if lab["id"] != label_id]
                return R(204)
            if "/messages/" in path:
                mid = path.rsplit("/", 1)[1]
                msg = next((m for m in self.store.messages if m["id"] == mid), None)
                if msg is not None:
                    msg["deletedAt"] = "2026-01-01T00:03:00Z"
                    msg["content"] = None
                return R(204)
            return R(404)

    class Reader:
        def __init__(self, store):
            self.store = store
            self.subscriptions = []

        def subscribe(self, destination):
            self.subscriptions.append(destination)

        def events(self, seconds):
            pending, self.store.events = self.store.events, []
            out = []
            for destination, envelope in pending:
                envelope["_destination"] = destination
                out.append(envelope)
            return iter(out)

        def close(self):
            return None

    def _fixture(self, hide_tabs=False):
        direct_id = "conv-an-binh"
        store = self.Store()
        world = self.World(store, direct_id)
        world.hide_tabs = hide_tabs
        client = self.Client(store, direct_id)
        reader = self.Reader(store)

        class Api:
            USER_A = {"phone": "+84900000001"}
            USER_C = {"phone": "+84900000002"}

            @staticmethod
            def direct_conversation(client_obj, token, phone):
                return 200, {"id": direct_id, "e2eeEnabled": False}

            @staticmethod
            def send_text(client_obj, token, cid, content):
                sender = "user-an" if token == "tok-an" else "user-binh"
                return store.create(cid, content, sender)

        def session(api, recorder, phone):
            if phone.endswith("0001"):
                return {"accessToken": "tok-an", "user": {"id": "user-an", "phone": phone}}
            return {"accessToken": "tok-binh", "user": {"id": "user-binh", "phone": phone}}

        context = {
            "serial": "emulator-5554",
            "base_url": "http://127.0.0.1:18093",
            "row": journeys.ROW_BY_ID["archive-preferences"],
            "row_dir": Path(tempfile.mkdtemp()),
            "adb": self.Adb(),
            "device": self.Device(world),
            "helpers": (Api(), None, self.Patch()),
            "client": client,
            "stomp_events": [],
            "frame_reader": None,
            "frame_reader_factory": lambda url, token: reader,
            "ui_timeout": 2,
        }
        return store, world, client, context, session

    def test_executor_drives_archive_browser_flags_labels_and_privacy(self):
        store, world, client, context, session = self._fixture()
        row = journeys.ROW_BY_ID["archive-preferences"]
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session):
            result = journeys.run_archive_preferences(row, context)

        self.assertIn("caller-side", result["mute"])
        self.assertIn("Undo action reverted it", result["archive_undo"])
        self.assertIn("peer entry and peer /labels stay clean", result["labels"])
        self.assertIn("survived pull-to-refresh", result["refresh"])
        self.assertIn("suppressed MESSAGE_READ", result["show_seen"])
        self.assertIn("peerPresence", result["show_presence"])
        # Every seeded message was tombstoned; flags/settings/label restored.
        self.assertEqual(len(store.messages), 7)  # image, file, 4 link texts, An's link
        self.assertTrue(all(m["deletedAt"] for m in store.messages))
        self.assertEqual(store.flags["an"], {"muted": False, "mutedUntil": None,
                                             "pinned": False, "pinnedAt": None,
                                             "archived": False, "labels": set()})
        self.assertEqual(store.flags["binh"]["labels"], set())
        self.assertFalse(store.flags["binh"]["muted"])
        self.assertFalse(store.flags["binh"]["pinned"])
        self.assertFalse(store.flags["binh"]["archived"])
        self.assertEqual(store.labels["an"], [])
        self.assertTrue(store.settings["an"]["showSeen"])
        self.assertTrue(store.settings["an"]["showPresence"])
        self.assertTrue(store.settings["binh"]["showSeen"])
        # The peer topic received MESSAGE_READ only while showSeen was on.
        reads = [env for env in context["stomp_events"]
                 if env.get("eventType") == "MESSAGE_READ"]
        self.assertTrue(any((env.get("payload") or {}).get("userId") == "user-an"
                            for env in reads))
        self.assertIn("/topic/conversations.conv-an-binh",
                      context["frame_reader"].subscriptions)
        # World ended back on a sane surface for the next row.
        self.assertEqual(world.screen, "inbox")
        self.assertFalse(world.snackbar_archived or world.menu_open or world.inbox_dialog)

    def test_executor_sweeps_fixtures_when_the_archive_screen_never_opens(self):
        store, world, client, context, session = self._fixture(hide_tabs=True)
        row = journeys.ROW_BY_ID["archive-preferences"]
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session):
            with self.assertRaises(journeys.ProductFailure) as caught:
                journeys.run_archive_preferences(row, context)
        self.assertIn("shared-media screen did not open", str(caught.exception))
        # The failure happened after the full fixture seed, so the sweep must
        # still tombstone every row-owned message and drop the created label.
        self.assertEqual(len(store.messages), 7)
        self.assertTrue(all(m["deletedAt"] for m in store.messages))
        self.assertEqual(store.labels["an"], [])
        self.assertEqual(store.flags["an"]["archived"], False)
        self.assertEqual(store.flags["an"]["labels"], set())


class GroupsContactsExecutorTest(unittest.TestCase):
    """Offline fake device+REST world for run_groups_contacts.

    The world mirrors the surfaces probed on emulator-5554: the contacts list
    and its create-group entry point, the create-group member picker, the
    group chat toolbar, group detail (member list, rename/remove/leave
    dialogs), the add-member bottom sheet, the invite-manage screen (approval
    switch, share URL, join-request queue, revoke) and the invite-join deep
    link's already-member state. One Store backs both the fake REST client
    and the UI mutations so REST/UI agreement assertions are real.
    """

    USERS = {
        "user-an": ("An", "+84900000001", "tok-an"),
        "user-binh": ("Binh", "+84900000002", "tok-binh"),
        "user-chi": ("Chi", "+84900000003", "tok-chi"),
        "user-dung": ("Dung", "+84900000004", "tok-dung"),
    }

    class Node:
        def __init__(self, text="", desc="", rid="", box=(0, 0, 320, 48),
                     clickable=True, cls="android.widget.TextView", checked=None,
                     enabled=None):
            self.text = text
            self.desc = desc
            self.attrs = {"resource-id": ("com.nexachat.debug:id/" + rid) if rid else ""}
            if checked is not None:
                self.attrs["checked"] = "true" if checked else "false"
            self.attrs["enabled"] = "true" if enabled is None or enabled else "false"
            self.box = box
            self.clickable = clickable
            self.cls = cls

        @property
        def center(self):
            left, top, right, bottom = self.box
            return (left + right) // 2, (top + bottom) // 2

    class Store:
        """Server truth: one disposable group, its invite link, join requests,
        removal bans and the per-caller inbox projection."""

        CONTACTS = [
            {"contactUserId": "user-binh", "displayName": "Binh",
             "phone": "+84900000002"},
            {"contactUserId": "user-chi", "displayName": "Chi",
             "phone": "+84900000003"},
            {"contactUserId": "user-dung", "displayName": "Dung",
             "phone": "+84900000004"},
        ]
        NAME_TO_ID = {"An": "user-an", "Binh": "user-binh", "Chi": "user-chi",
                      "Dung": "user-dung"}

        def __init__(self):
            self.cid = "grp-1"
            self.created = False
            self.title = ""
            self.members = {}      # uid -> {"displayName", "role"}
            self.banned = set()
            self.link = None       # {"token", "requiresApproval", "revoked"}
            self.requests = []     # {"id", "userId", "displayName", "status"}
            self.next_request = 0

        def create_group(self, title, picked_names):
            self.created = True
            self.title = title
            self.members = {"user-an": {"displayName": "An", "role": "ADMIN"}}
            for name in picked_names:
                uid = self.NAME_TO_ID[name]
                self.members[uid] = {"displayName": name, "role": "MEMBER"}

        def add_member(self, name):
            uid = self.NAME_TO_ID[name]
            self.members[uid] = {"displayName": name, "role": "MEMBER"}
            self.banned.discard(uid)

        def remove_member(self, uid, banned=True):
            self.members.pop(uid, None)
            if banned:
                self.banned.add(uid)

        def create_link(self, requires_approval):
            self.link = {"token": "t" * 43, "requiresApproval": requires_approval,
                         "revoked": False}

        def join(self, uid):
            if self.link is None or self.link["revoked"]:
                return 404, None
            if uid in self.members:
                return 200, {"status": "ALREADY_MEMBER", "conversationId": self.cid}
            if uid in self.banned:
                return 403, {"error": "removed member"}
            if self.link["requiresApproval"]:
                for request in self.requests:
                    if request["userId"] == uid and request["status"] == "PENDING":
                        return 200, {"status": "PENDING", "conversationId": self.cid,
                                     "requestId": request["id"]}
                self.next_request += 1
                request = {"id": f"req-{self.next_request}", "userId": uid,
                           "displayName": self.NAME_TO_ID and
                           next(n for n, i in self.NAME_TO_ID.items() if i == uid),
                           "status": "PENDING"}
                self.requests.append(request)
                return 201, {"status": "PENDING", "conversationId": self.cid,
                             "requestId": request["id"]}
            self.members[uid] = {"displayName": next(
                n for n, i in self.NAME_TO_ID.items() if i == uid), "role": "MEMBER"}
            return 201, {"status": "JOINED", "conversationId": self.cid}

        def member_entries(self):
            return [{"userId": uid, "displayName": member["displayName"],
                     "phone": GroupsContactsExecutorTest.USERS[uid][1],
                     "avatarUrl": None, "role": member["role"]}
                    for uid, member in self.members.items()]

        def inbox_entry(self):
            return {"id": self.cid, "type": "GROUP", "title": self.title,
                    "members": self.member_entries()}

    class World:
        def __init__(self, store):
            self.store = store
            self.screen = "inbox"
            self.history = []
            self.name_input = ""
            self.rename_input = ""
            self.picked = set()
            self.sheet_selected = set()
            self.approval_draft = False
            self.share_url = None
            self.rename_dialog = False
            self.remove_dialog = None
            self.leave_dialog = False
            self.revoke_dialog = False
            self.add_sheet = False
            self.hide_added_members = False  # failure variant

        def go(self, screen):
            self.history.append(self.screen)
            self.screen = screen

        def cold_start(self):
            """Force-stop + relaunch: a fresh task landing on the inbox."""
            self.screen = "inbox"
            self.history.clear()
            self.name_input = ""
            self.rename_input = ""
            self.picked = set()
            self.sheet_selected = set()
            self.rename_dialog = False
            self.remove_dialog = None
            self.leave_dialog = False
            self.revoke_dialog = False
            self.add_sheet = False

        def tree(self):
            N = GroupsContactsExecutorTest.Node
            s = self.store
            nodes = []
            if self.screen == "inbox":
                nodes.append(N(rid="inboxUserAvatar"))
                nodes.append(N(rid="inboxContactsButton"))
                nodes.append(N(rid="conversationList", box=(0, 300, 320, 620)))
                if "user-an" in s.members:
                    nodes.append(N(text=s.title, box=(76, 360, 300, 390)))
            elif self.screen == "contacts":
                nodes.append(N(rid="createGroupEntry"))
                nodes.append(N(rid="contactList", box=(0, 200, 320, 620)))
                for contact in s.CONTACTS:
                    nodes.append(N(text=contact["displayName"], rid="name",
                                   box=(60, 210 + 60 * s.CONTACTS.index(contact), 300, 260)))
            elif self.screen == "group-create":
                nodes.append(N(text=self.name_input, rid="groupNameInput",
                               cls="android.widget.EditText"))
                nodes.append(N(rid="memberPickerList", box=(0, 200, 320, 560)))
                for index, contact in enumerate(s.CONTACTS):
                    top = 210 + 60 * index
                    nodes.append(N(rid="rowContent",
                                   checked=contact["displayName"] in self.picked,
                                   box=(0, top, 320, top + 56)))
                    nodes.append(N(text=contact["displayName"], rid="name",
                                   box=(60, top, 300, top + 50)))
                nodes.append(N(text="Create group", rid="createButton",
                               enabled=bool(self.name_input.strip()) and bool(self.picked)))
            elif self.screen == "chat":
                nodes.append(N(text=s.title))
                nodes.append(N(rid="chatToolbar", box=(0, 0, 320, 56)))
                nodes.append(N(text="Message", rid="messageInput"))
            elif self.screen == "group-detail":
                nodes.append(N(text=s.title, rid="groupTitle"))
                nodes.append(N(rid="memberList", box=(0, 200, 320, 620)))
                top = 210
                for uid, member in s.members.items():
                    if self.hide_added_members and uid == "user-chi":
                        continue
                    nodes.append(N(text=member["displayName"], rid="memberName",
                                   box=(60, top, 200, top + 40)))
                    if member["role"] == "ADMIN":
                        nodes.append(N(text="Admin", rid="memberRole",
                                       box=(210, top, 300, top + 40)))
                    else:
                        nodes.append(N(desc=f"Remove {member['displayName']} from this group?",
                                       rid="removeMember",
                                       box=(270, top, 318, top + 40)))
                    top += 60
                nodes.append(N(text="Add members", rid="addMemberButton"))
                nodes.append(N(text="Invite link", rid="inviteLinkButton"))
                nodes.append(N(text="Leave group", rid="leaveGroupButton"))
            elif self.screen == "invite-manage":
                has_link = s.link is not None and not s.link["revoked"]
                nodes.append(N(text="Active invite link" if has_link else "No active link.",
                               rid="linkStatus"))
                nodes.append(N(rid="approvalSwitch",
                               checked=(s.link["requiresApproval"] if has_link
                                        else self.approval_draft)))
                nodes.append(N(text="Rotate link" if has_link else "Create link",
                               rid="createLinkButton"))
                if self.share_url:
                    nodes.append(N(text=self.share_url, rid="shareUrl"))
                nodes.append(N(text="Revoke link", rid="revokeButton", enabled=has_link))
                nodes.append(N(text="Refresh requests", rid="refreshRequestsButton"))
                nodes.append(N(rid="requestList", box=(0, 300, 320, 620)))
                pending = [r for r in s.requests if r["status"] == "PENDING"]
                if not pending:
                    nodes.append(N(text="No pending requests.", rid="emptyRequests"))
                for index, request in enumerate(pending):
                    top = 310 + 80 * index
                    nodes.append(N(text=request["displayName"], rid="requestName",
                                   box=(60, top, 200, top + 40)))
                    nodes.append(N(text="Approve", rid="approveButton",
                                   box=(60, top + 40, 150, top + 78)))
                    nodes.append(N(text="Reject", rid="rejectButton",
                                   box=(160, top + 40, 240, top + 78)))
            elif self.screen == "invite-join":
                nodes.append(N(text=s.title, rid="groupTitle"))
                if s.link and s.link["requiresApproval"]:
                    nodes.append(N(text="An admin must approve your request.",
                                   rid="approvalNote"))
                nodes.append(N(text="You are already a member.", rid="statusText"))
                nodes.append(N(text="Open group chat", rid="openChatButton"))
            if self.add_sheet:
                nodes.append(N(rid="addMemberSheetRoot"))
                nodes.append(N(rid="addMemberList", box=(0, 300, 320, 620)))
                addable = [c for c in s.CONTACTS
                           if c["contactUserId"] not in s.members]
                for index, contact in enumerate(addable):
                    top = 310 + 60 * index
                    nodes.append(N(rid="rowContent",
                                   checked=contact["displayName"] in self.sheet_selected,
                                   box=(0, top, 320, top + 56)))
                    nodes.append(N(text=contact["displayName"], rid="name",
                                   box=(60, top, 300, top + 50)))
                nodes.append(N(text="Add", rid="addMemberConfirm",
                               enabled=bool(self.sheet_selected)))
                nodes.append(N(text="Invite via link", rid="addMemberInviteLink"))
            if self.rename_dialog:
                nodes.append(N(text="Rename group"))
                nodes.append(N(text=self.rename_input, cls="android.widget.EditText"))
                nodes.append(N(text="Save"))
                nodes.append(N(text="Cancel"))
            if self.remove_dialog is not None:
                nodes.append(N(text="Remove member"))
                nodes.append(N(text=f"Remove {self.remove_dialog} from this group?"))
                nodes.append(N(text="Remove"))
                nodes.append(N(text="Cancel"))
            if self.leave_dialog:
                # The live AlertDialog title repeats the button label; tapping
                # the non-clickable title must not fire the leave.
                nodes.append(N(text="Leave group", clickable=False))
                nodes.append(N(text="Leave this group?"))
                nodes.append(N(text="Leave group", cls="android.widget.Button"))
                nodes.append(N(text="Cancel"))
            if self.revoke_dialog:
                nodes.append(N(text="Revoke this invite link?"))
                nodes.append(N(text="Revoke link", cls="android.widget.Button"))
                nodes.append(N(text="Cancel"))
            return nodes

    class Device:
        def __init__(self, world):
            self.world = world

        def nodes(self):
            return self.world.tree()

        def _dialog(self, node, rid, text):
            w = self.world
            s = w.store
            if w.rename_dialog:
                if "EditText" in node.cls:
                    return True  # focusing the field keeps the dialog open
                if text == "Save":
                    s.title = w.rename_input
                    w.rename_dialog = False
                elif text == "Cancel":
                    w.rename_dialog = False
                return True
            if w.remove_dialog is not None:
                if text == "Remove":
                    s.remove_member(s.NAME_TO_ID[w.remove_dialog])
                w.remove_dialog = None
                return True
            if w.leave_dialog:
                w.leave_dialog = False
                if (text == "Leave group" and node.clickable
                        and "Button" in node.cls
                        and not rid.endswith("leaveGroupButton")):
                    s.remove_member("user-an", banned=False)
                    w.screen = "inbox"
                    w.history.clear()
                return True
            if w.revoke_dialog:
                w.revoke_dialog = False
                if text == "Revoke link" and not rid.endswith("revokeButton"):
                    s.link["revoked"] = True
                return True
            return False

        def tap_node(self, node):
            w = self.world
            s = w.store
            rid = node.attrs["resource-id"]
            text = node.text.strip()
            if self._dialog(node, rid, text):
                return
            if w.add_sheet:
                w.add_sheet = False
                if rid.endswith("/name"):
                    name = text
                    if name in w.sheet_selected:
                        w.sheet_selected.discard(name)
                    else:
                        w.sheet_selected.add(name)
                    w.add_sheet = True
                elif rid.endswith("addMemberConfirm"):
                    for name in w.sheet_selected:
                        s.add_member(name)
                    w.sheet_selected = set()
                elif rid.endswith("addMemberInviteLink"):
                    w.go("invite-manage")
                else:
                    w.add_sheet = True  # tapped elsewhere: sheet stays
                return
            if w.screen == "inbox":
                if rid.endswith("inboxContactsButton"):
                    w.go("contacts")
                elif text == w.store.title and node.box and node.box[1] >= 300:
                    w.go("chat")
                return
            if w.screen == "contacts":
                if rid.endswith("createGroupEntry"):
                    w.go("group-create")
                return
            if w.screen == "group-create":
                if rid.endswith("groupNameInput"):
                    return
                if rid.endswith("/name"):
                    name = text
                    if name in w.picked:
                        w.picked.discard(name)
                    else:
                        w.picked.add(name)
                elif rid.endswith("createButton") and w.picked and w.name_input.strip():
                    s.create_group(w.name_input.strip(), set(w.picked))
                    w.go("chat")
                return
            if w.screen == "chat":
                if rid.endswith("chatToolbar"):
                    w.go("group-detail")
                return
            if w.screen == "group-detail":
                if rid.endswith("groupTitle"):
                    w.rename_input = s.title
                    w.rename_dialog = True
                elif rid.endswith("addMemberButton"):
                    w.add_sheet = True
                elif rid.endswith("removeMember"):
                    w.remove_dialog = node.desc.replace("Remove ", "").replace(
                        " from this group?", "")
                elif rid.endswith("inviteLinkButton"):
                    w.share_url = None  # fresh load: plaintext token is not re-served
                    w.go("invite-manage")
                elif rid.endswith("leaveGroupButton"):
                    w.leave_dialog = True
                return
            if w.screen == "invite-manage":
                if rid.endswith("approvalSwitch"):
                    current = (s.link["requiresApproval"]
                               if s.link and not s.link["revoked"] else w.approval_draft)
                    w.approval_draft = not current
                    if s.link and not s.link["revoked"]:
                        s.link["requiresApproval"] = w.approval_draft
                elif rid.endswith("createLinkButton"):
                    s.create_link(w.approval_draft)
                    w.share_url = f"nexachat://join/{s.link['token']}"
                elif rid.endswith("approveButton"):
                    pending = next((r for r in s.requests if r["status"] == "PENDING"), None)
                    if pending:
                        pending["status"] = "APPROVED"
                        s.add_member(pending["displayName"])
                elif rid.endswith("rejectButton"):
                    pending = next((r for r in s.requests if r["status"] == "PENDING"), None)
                    if pending:
                        pending["status"] = "REJECTED"
                elif rid.endswith("revokeButton"):
                    w.revoke_dialog = True
                return
            if w.screen == "invite-join":
                if rid.endswith("openChatButton"):
                    w.go("chat")
                return

        def type_text(self, value):
            w = self.world
            if w.rename_dialog:
                w.rename_input += value
            elif w.screen == "group-create":
                w.name_input += value

        def delete_chars(self, count):
            w = self.world
            if w.rename_dialog:
                w.rename_input = w.rename_input[:-count] if count else w.rename_input
            elif w.screen == "group-create":
                w.name_input = w.name_input[:-count] if count else w.name_input

        def move_end(self):
            return None

        def swipe(self, x1, y1, x2, y2, duration_ms=300):
            return None  # the fake renders the request section without scrolling

        def key(self, code):
            w = self.world
            if w.rename_dialog:
                w.rename_dialog = False
            elif w.remove_dialog is not None:
                w.remove_dialog = None
            elif w.leave_dialog:
                w.leave_dialog = False
            elif w.revoke_dialog:
                w.revoke_dialog = False
            elif w.add_sheet:
                w.add_sheet = False
            elif w.history:
                w.screen = w.history.pop()
            else:
                w.screen = "inbox"

        def screenshot(self, path):
            Path(path).write_bytes(b"png")

    class Patch:
        @staticmethod
        def ui_nodes(device, timeout=8):
            return device.nodes()

        @staticmethod
        def grant_test_permissions():
            return {}

        @staticmethod
        def filtered_logcat(baseline):
            return [], False

    class Adb:
        serial = "emulator-5554"

        def __init__(self, world):
            self.world = world

        def command(self, *args, **kwargs):
            if args[:2] != ("shell", "am") or "start" not in args:
                return ""
            if "-d" in args:
                # ACTION_VIEW deep link -> the invite join surface.
                self.world.go("invite-join")
            else:
                # Explicit MainActivity relaunch -> cold start on the inbox.
                self.world.cold_start()
            return ""

    class Response:
        def __init__(self, status, body=None):
            self.status = status
            self._body = body

        def json(self):
            return self._body

    class Client:
        base_url = "http://127.0.0.1:18093"
        timeout = 30

        def __init__(self, store):
            self.store = store

        @staticmethod
        def _caller(token):
            return {"tok-an": "user-an", "tok-binh": "user-binh",
                    "tok-chi": "user-chi", "tok-dung": "user-dung"}.get(token)

        def request(self, method, path, token=None, body=None,
                    params=None, raw_body=None, headers=None):
            handler = {"GET": self.get, "POST": self.post, "PATCH": self.patch,
                       "PUT": self.put, "DELETE": self.delete}[method]
            return handler(path, token=token, body=body, params=params, headers=headers)

        def get(self, path, token=None, params=None, headers=None):
            R = GroupsContactsExecutorTest.Response
            s = self.store
            caller = self._caller(token)
            if path == "/api/v1/contacts":
                return R(200, [dict(c) for c in s.CONTACTS])
            if path == "/api/v1/conversations":
                if caller in s.members:
                    return R(200, [s.inbox_entry()])
                return R(200, [])
            if "/api/v1/conversations/" in path and path.endswith("/messages"):
                return R(200, {"items": []}) if caller in s.members else R(403)
            if "/api/v1/conversations/" in path and path.endswith("/invite-link"):
                if s.link and not s.link["revoked"]:
                    return R(200, {"id": "lnk-1", "token": None,
                                   "requiresApproval": s.link["requiresApproval"]})
                return R(404)
            if "/api/v1/conversations/" in path and path.endswith("/join-requests/me"):
                mine = [r for r in s.requests if r["userId"] == caller]
                if not mine:
                    return R(404)
                latest = mine[-1]
                return R(200, {"id": latest["id"], "status": latest["status"],
                               "user": {"id": caller,
                                        "displayName": latest["displayName"]}})
            if "/api/v1/conversations/" in path and path.endswith("/join-requests"):
                status = (params or {}).get("status")
                rows = [r for r in s.requests
                        if status is None or r["status"] == status]
                return R(200, [{"id": r["id"], "status": r["status"],
                                "user": {"id": r["userId"],
                                         "displayName": r["displayName"]}}
                               for r in rows])
            if path.startswith("/api/v1/invite-links/"):
                if s.link is None or s.link["revoked"]:
                    return R(404)
                return R(200, {"conversationId": s.cid, "title": s.title,
                               "memberCount": len(s.members),
                               "requiresApproval": s.link["requiresApproval"],
                               "isMember": caller in s.members})
            return R(404)

        def post(self, path, token=None, body=None, headers=None):
            R = GroupsContactsExecutorTest.Response
            s = self.store
            caller = self._caller(token)
            if path.startswith("/api/v1/invite-links/") and path.endswith("/join"):
                status, payload = s.join(caller)
                return R(status, payload)
            if "/api/v1/conversations/" in path and path.endswith("/leave"):
                s.members.pop(caller, None)
                return R(204)
            if "/api/v1/conversations/" in path and "/join-requests/" in path \
                    and path.endswith("/reject"):
                rid = path.rsplit("/", 2)[1]
                for request in s.requests:
                    if request["id"] == rid:
                        request["status"] = "REJECTED"
                        return R(200, {"id": rid, "status": "REJECTED"})
                return R(404)
            return R(404)

        def patch(self, path, token=None, body=None, headers=None):
            return GroupsContactsExecutorTest.Response(404)

        def put(self, path, token=None, body=None, headers=None):
            return GroupsContactsExecutorTest.Response(404)

        def delete(self, path, token=None, params=None, headers=None):
            R = GroupsContactsExecutorTest.Response
            s = self.store
            if "/api/v1/conversations/" in path and "/members/" in path:
                uid = path.rsplit("/", 1)[1]
                s.remove_member(uid)
                return R(204)
            if "/api/v1/conversations/" in path and path.endswith("/invite-link"):
                if s.link:
                    s.link["revoked"] = True
                    return R(204)
                return R(404)
            return R(404)

    def _fixture(self, hide_added_members=False):
        store = self.Store()
        world = self.World(store)
        world.hide_added_members = hide_added_members
        client = self.Client(store)

        class Api:
            USER_A = {"phone": "+84900000001"}
            USER_C = {"phone": "+84900000002"}
            USER_B = {"phone": "+84900000003"}

        def session(api, recorder, phone):
            uid = {"+84900000001": "user-an", "+84900000002": "user-binh",
                   "+84900000003": "user-chi", "+84900000004": "user-dung"}[phone]
            return {"accessToken": self.USERS[uid][2],
                    "user": {"id": uid, "phone": phone}}

        context = {
            "serial": "emulator-5554",
            "base_url": "http://127.0.0.1:18093",
            "row": journeys.ROW_BY_ID["groups-contacts"],
            "row_dir": Path(tempfile.mkdtemp()),
            "adb": self.Adb(world),
            "device": self.Device(world),
            "helpers": (Api(), None, self.Patch()),
            "client": client,
            "ui_timeout": 2,
        }
        return store, world, client, context, session

    def test_executor_drives_group_invite_and_contact_surfaces(self):
        store, world, client, context, session = self._fixture()
        row = journeys.ROW_BY_ID["groups-contacts"]
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session):
            result = journeys.run_groups_contacts(row, context)

        self.assertIn("agreed with GET /contacts", result["contacts"])
        self.assertIn("An ADMIN + Binh", result["create"])
        self.assertIn("UI and REST", result["rename"])
        self.assertIn("member list and REST agree", result["add_member"])
        self.assertIn("history reads 403", result["remove_member"])
        self.assertIn("requiresApproval=true", result["invite_link"])
        self.assertIn("PENDING -> APPROVED", result["approval"])
        self.assertIn("Open group chat landed on its composer", result["invite_return"])
        self.assertIn("admin read 404", result["revoke"])
        self.assertIn("last member left", result["leave"])
        # Server truth after the full journey: the disposable group is orphaned.
        self.assertEqual(store.members, {})
        self.assertTrue(store.link["revoked"])
        self.assertEqual([r["status"] for r in store.requests], ["APPROVED"])
        self.assertTrue(store.title.startswith("sj-grp-") and store.title.endswith("-r"))
        # Every participant's inbox projection is empty.
        self.assertEqual(store.inbox_entry()["members"], [])
        self.assertEqual(world.screen, "inbox")

    def test_executor_sweeps_the_group_when_the_member_list_lies(self):
        store, world, client, context, session = self._fixture(hide_added_members=True)
        row = journeys.ROW_BY_ID["groups-contacts"]
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session):
            with self.assertRaises(journeys.ProductFailure) as caught:
                journeys.run_groups_contacts(row, context)
        self.assertIn("Chi did not appear in the member list", str(caught.exception))
        # The REST side really added Chi, so the sweep must remove her plus the
        # other members and An must leave the disposable group behind.
        self.assertEqual(store.members, {})
        self.assertIn("user-chi", store.banned)

    def test_registered_dispatch_routes_groups_contacts(self):
        sentinel = mock.Mock(return_value={"summary": "ok"})
        with mock.patch.object(journeys, "run_groups_contacts", sentinel):
            result = journeys.run_registered_row(
                journeys.ROW_BY_ID["groups-contacts"], {"serial": "emulator-5554"})
        self.assertEqual(result, {"summary": "ok"})
        sentinel.assert_called_once()

    def test_registered_dispatch_routes_labels_lifecycle(self):
        sentinel = mock.Mock(return_value={"summary": "ok"})
        with mock.patch.object(journeys, "run_labels_lifecycle", sentinel):
            result = journeys.run_registered_row(
                journeys.ROW_BY_ID["labels-lifecycle"], {"serial": "emulator-5554"})
        self.assertEqual(result, {"summary": "ok"})
        sentinel.assert_called_once()

    def test_registered_dispatch_routes_polls_reminders(self):
        sentinel = mock.Mock(return_value={"summary": "ok"})
        with mock.patch.object(journeys, "run_polls_reminders", sentinel):
            result = journeys.run_registered_row(
                journeys.ROW_BY_ID["polls-reminders"], {"serial": "emulator-5554"})
        self.assertEqual(result, {"summary": "ok"})
        sentinel.assert_called_once()


class LabelsLifecycleExecutorTest(unittest.TestCase):
    """Offline fake device+REST world for run_labels_lifecycle.

    The world mirrors the surfaces probed on emulator-5554: the inbox long-press
    actions sheet, the 'Conversation labels' assignment dialog (CheckBox per
    fetched label, 'Manage labels'/'Done' buttons), the manage mode (per-label
    'Label: X'/'Edit label X'/'Delete label X' rows + 'Create label'), the
    'New label' editor (hint-desc EditText, color RadioButtons, Save/Cancel),
    the 'Delete label?' confirm, and the inbox labelFilterChips carousel that
    only renders while labels exist. Per-conversation label ids live in the
    store keyed by caller so REST /labels, entry.labels and ?labelId= agree
    with what the UI toggles — including the peer's empty views.

    Recreation is modelled honestly: rotate() only re-renders the same dialog,
    HOME + `am start` resumes it, `am force-stop` drops it, and a reopened
    dialog refetches the store labels (the post-#181 contract). Failure
    variants duplicate the rendered surface after rotation or keep the reopen
    stale, and the run must classify them as ProductFailure and still sweep
    the labels it created.
    """

    class Node:
        def __init__(self, text="", desc="", rid="", box=(0, 0, 320, 48),
                     clickable=True, cls="android.widget.TextView", checked=None):
            self.text = text
            self.desc = desc
            self.attrs = {"resource-id": ("com.nexachat.debug:id/" + rid) if rid else ""}
            if checked is not None:
                self.attrs["checked"] = "true" if checked else "false"
            self.box = box
            self.clickable = clickable
            self.cls = cls

        @property
        def center(self):
            left, top, right, bottom = self.box
            return (left + right) // 2, (top + bottom) // 2

    class Store:
        """Server truth: per-caller label lists and per-conversation label ids."""

        CONVS = ("conv-an-binh", "conv-team", "conv-chi")

        def __init__(self):
            self.next_label_id = 0
            self.labels = {"an": [], "binh": []}
            self.conv_labels = {
                cid: {"an": set(), "binh": set()} for cid in self.CONVS}
            self.types = {"conv-an-binh": "DIRECT", "conv-team": "GROUP",
                          "conv-chi": "DIRECT"}

        def create_label(self, caller, name, color="INDIGO"):
            self.next_label_id += 1
            lab = {"id": f"lab-{self.next_label_id}", "name": name,
                   "colorId": color}
            self.labels[caller].append(lab)
            return lab

        def delete_label(self, caller, label_id):
            self.labels[caller] = [
                lab for lab in self.labels[caller] if lab["id"] != label_id]
            for per in self.conv_labels.values():
                per[caller].discard(label_id)

        def by_name(self, caller, name):
            return next((lab for lab in self.labels[caller]
                         if lab["name"] == name), None)

        def entry(self, caller, cid):
            return {"id": cid, "type": self.types[cid],
                    "archived": False, "muted": False, "mutedUntil": None,
                    "pinned": False, "pinnedAt": None, "aiOptOut": False,
                    "labels": sorted(self.conv_labels[cid][caller]),
                    "peerPresence": {"online": True,
                                     "lastSeenAt": "2026-01-01T00:00:00Z"}}

        def filtered(self, caller, label_id):
            return [cid for cid in self.CONVS
                    if label_id in self.conv_labels[cid][caller]]

    class World:
        """Device surface state re-read by every uiautomator dump."""

        def __init__(self, store, direct_id):
            self.store = store
            self.direct_id = direct_id
            self.screen = "inbox"
            self.app_running = True
            self.foreground = True
            self.inbox_dialog = False
            self.label_dialog = False
            self.dialog_mode = "assign"
            self.editor_dialog = False
            self.editor_text = ""
            self.ime = False
            self.delete_dialog = False
            self.delete_target = None
            self.label_filter = None        # None or set(conv ids)
            self.rotation = 0
            self.logcat = ["D NexaChat: app start"]
            # Labels the activity-scoped view-model has fetched; UI mutations
            # publish into it immediately, a reopen refetches the whole store
            # (unless the world simulates the pre-#181 stale cache).
            self.fetched = {lab["id"] for lab in store.labels["an"]}
            self.stale_reopen = False       # failure variant
            self.duplicate_after_rotate = False  # failure variant
            self.rotation_leak = False      # failure variant
            self._duplicated = False

        def open_label_dialog(self):
            self.label_dialog = True
            self.dialog_mode = "assign"
            if not self.stale_reopen:
                self.fetched = {lab["id"] for lab in self.store.labels["an"]}

        def _fetched_labels(self):
            return [lab for lab in self.store.labels["an"]
                    if lab["id"] in self.fetched]

        def _visible_convs(self):
            convs = LabelsLifecycleExecutorTest.Store.CONVS
            if self.label_filter is None:
                return list(convs)
            return [cid for cid in convs if cid in self.label_filter]

        def tree(self):
            N = LabelsLifecycleExecutorTest.Node
            s = self.store
            nodes = []
            if not self.app_running or not self.foreground:
                return nodes
            if self.screen == "inbox":
                nodes.append(N(rid="inboxUserAvatar"))
                nodes.append(N(rid="inboxFilterScroll", box=(0, 291, 320, 347)))
                # 'Active now' presence strip above the list (same 'Binh' text)
                nodes.append(N(text="Binh", desc="Binh, Active now",
                               box=(100, 196, 184, 283)))
                if s.labels["an"]:
                    nodes.append(N(text="All labels",
                                   cls="android.widget.RadioButton",
                                   box=(8, 295, 96, 343)))
                    for index, lab in enumerate(s.labels["an"]):
                        left = 100 + index * 110
                        nodes.append(N(
                            text=lab["name"],
                            desc=f"Filter inbox by {lab['name']}",
                            cls="android.widget.RadioButton",
                            box=(left, 295, min(left + 100, 312), 343)))
                    nodes.append(N(text="Manage labels",
                                   desc="Manage conversation labels",
                                   cls="android.widget.Button",
                                   box=(214, 295, 312, 343)))
                nodes.append(N(rid="conversationList", box=(0, 347, 320, 620)))
                visible = self._visible_convs()
                if "conv-an-binh" in visible:
                    nodes.append(N(text="Binh", desc="Binh, Online",
                                   box=(76, 355, 263, 379)))
                if "conv-team" in visible:
                    nodes.append(N(text="NexaChat Team", box=(76, 427, 263, 451)))
                if "conv-chi" in visible:
                    nodes.append(N(text="Chi", box=(76, 499, 260, 523)))
                if self.label_filter is not None and not visible:
                    nodes.append(N(text='No conversations have the "x" label.'))
            if self.inbox_dialog:
                for text in ("Pin conversation", "For 1 hour", "For 4 hours",
                             "Until 8:00 AM", "Until turned back on",
                             "Archive", "Assign labels"):
                    nodes.append(N(text=text, rid="text1",
                                   cls="android.widget.CheckedTextView"))
            if self.label_dialog:
                copies = 2 if self._duplicated else 1
                for _ in range(copies):
                    if self.dialog_mode == "assign":
                        nodes.append(N(text="Conversation labels"))
                        labels = self._fetched_labels()
                        if not labels:
                            nodes.append(N(text="Create a label before assigning it."))
                        for lab in labels:
                            nodes.append(N(
                                text=lab["name"],
                                desc=f"Assign label {lab['name']}",
                                cls="android.widget.CheckBox",
                                checked=(lab["id"] in
                                         s.conv_labels[self.direct_id]["an"])))
                        nodes.append(N(text="Manage labels",
                                       cls="android.widget.Button"))
                    else:
                        nodes.append(N(text="Manage labels"))
                        for lab in self._fetched_labels():
                            nodes.append(N(text=lab["name"],
                                           desc=f"Label: {lab['name']}"))
                            nodes.append(N(desc=f"Edit label {lab['name']}",
                                           cls="android.widget.Button"))
                            nodes.append(N(desc=f"Delete label {lab['name']}",
                                           cls="android.widget.Button"))
                        nodes.append(N(text="Back to conversation",
                                       cls="android.widget.Button"))
                        nodes.append(N(text="Create label",
                                       cls="android.widget.Button"))
                    nodes.append(N(text="Done", cls="android.widget.Button"))
            if self.editor_dialog:
                nodes.append(N(text="New label"))
                nodes.append(N(text=self.editor_text,
                               desc="Label name (1–30 characters)",
                               cls="android.widget.EditText",
                               box=(24, 200, 296, 252)))
                for color in ("Indigo", "Blue", "Teal"):
                    nodes.append(N(text=color, cls="android.widget.RadioButton"))
                nodes.append(N(text="Save", cls="android.widget.Button"))
                nodes.append(N(text="Cancel", cls="android.widget.Button"))
            if self.delete_dialog:
                nodes.append(N(text="Delete label?"))
                nodes.append(N(text="Delete", cls="android.widget.Button"))
                nodes.append(N(text="Cancel", cls="android.widget.Button"))
            return nodes

    class Device:
        def __init__(self, world):
            self.world = world

        def nodes(self):
            return self.world.tree()

        def _assign_toggle(self, name):
            w = self.world
            lab = next((lab for lab in w._fetched_labels()
                        if lab["name"] == name), None)
            if lab is None:
                return
            ids = w.store.conv_labels[w.direct_id]["an"]
            if lab["id"] in ids:
                ids.discard(lab["id"])
            else:
                ids.add(lab["id"])

        def tap_node(self, node):
            w = self.world
            s = w.store
            text = node.text.strip()
            desc = node.desc.strip()
            rid = node.attrs["resource-id"]
            if w.delete_dialog:
                w.delete_dialog = False
                if text == "Delete" and w.delete_target:
                    s.delete_label("an", w.delete_target)
                    w.fetched.discard(w.delete_target)
                w.delete_target = None
                return
            if w.editor_dialog:
                if desc == "Label name (1–30 characters)":
                    w.ime = True
                    return
                if "RadioButton" in node.cls:
                    return            # color pick keeps the editor open
                if text in ("Save", "Cancel"):
                    w.editor_dialog = False
                    w.ime = False
                    if text == "Save":
                        lab = s.create_label("an", w.editor_text or "unnamed",
                                             color="BLUE")
                        w.fetched.add(lab["id"])
                        w.editor_text = ""
                return
            if w.inbox_dialog:
                w.inbox_dialog = False
                if text == "Assign labels":
                    w.open_label_dialog()
                return
            if w.label_dialog:
                if w.dialog_mode == "assign":
                    if "CheckBox" in node.cls and desc.startswith("Assign label "):
                        self._assign_toggle(desc[len("Assign label "):])
                    elif text == "Manage labels" and "Button" in node.cls:
                        w.dialog_mode = "manage"
                    elif text == "Done":
                        w.label_dialog = False
                    return
                if text == "Create label":
                    w.editor_dialog = True
                    w.editor_text = ""
                elif desc.startswith("Delete label "):
                    name = desc[len("Delete label "):]
                    lab = s.by_name("an", name)
                    w.delete_dialog = True
                    w.delete_target = lab["id"] if lab else None
                elif text == "Back to conversation":
                    w.dialog_mode = "assign"
                elif text == "Done":
                    w.label_dialog = False
                return
            if w.screen == "inbox":
                if desc.startswith("Filter inbox by "):
                    lab = s.by_name("an", desc[len("Filter inbox by "):])
                    if lab is not None:
                        w.label_filter = set(s.filtered("an", lab["id"]))
                elif text == "All labels":
                    w.label_filter = None
                elif text == "Binh" and node.box and node.box[1] >= 347:
                    w.screen = "chat"
                return

        def long_press(self, node, duration_ms=800):
            w = self.world
            if (w.screen == "inbox" and node.box and node.box[1] >= 347
                    and node.text.strip() == "Binh"):
                w.inbox_dialog = True

        def swipe(self, x1, y1, x2, y2, duration_ms=300):
            return None

        def type_text(self, value):
            self.world.editor_text = value

        def screenshot(self, path):
            Path(path).write_bytes(b"png")

        def rotate(self, rotation):
            w = self.world
            w.rotation = rotation
            if w.duplicate_after_rotate:
                w._duplicated = True
            if w.rotation_leak:
                w.logcat.append(
                    "E WindowManager: android.view.WindowLeaked: Activity "
                    "com.nexachat.MainActivity has leaked window "
                    "DecorView@abc[MainActivity] that was originally added here")

        def key(self, code):
            w = self.world
            if code == "KEYCODE_HOME":
                w.foreground = False
                return
            if w.ime:
                w.ime = False
                return
            if w.delete_dialog:
                w.delete_dialog = False
            elif w.editor_dialog:
                w.editor_dialog = False
            elif w.label_dialog:
                w.label_dialog = False
            elif w.inbox_dialog:
                w.inbox_dialog = False
            elif w.screen == "chat":
                w.screen = "inbox"

    class Patch:
        @staticmethod
        def ui_nodes(device, timeout=8):
            return device.nodes()

        @staticmethod
        def named_conversation_row(nodes, display_name):
            return next((n for n in nodes
                         if n.text.strip() == display_name
                         or n.desc.strip() == display_name), None)

        @staticmethod
        def grant_test_permissions():
            return {}

        @staticmethod
        def filtered_logcat(baseline):
            return [], False

    class Adb:
        serial = "emulator-5554"

        def __init__(self, world):
            self.world = world
            self.settings = {"accelerometer_rotation": "1", "user_rotation": "0"}

        def command(self, *args, timeout=None, check=True, **kwargs):
            w = self.world
            if args[:4] == ("shell", "settings", "get", "system"):
                return self.settings.get(args[4], "")
            if args[:4] == ("shell", "settings", "put", "system"):
                self.settings[args[4]] = args[5]
                return ""
            if args[:3] == ("shell", "am", "force-stop"):
                w.app_running = False
                w.label_dialog = w.inbox_dialog = False
                w.editor_dialog = w.delete_dialog = False
                return ""
            if args[:3] == ("shell", "am", "start"):
                if w.app_running:
                    w.foreground = True        # resume: dialog surface stays
                else:                          # cold start: fresh task, inbox
                    w.app_running = True
                    w.foreground = True
                    w.screen = "inbox"
                return "Starting: Intent { cmp=com.nexachat.debug/.MainActivity }"
            if args[:3] == ("shell", "logcat", "-d"):
                return "\n".join(w.logcat)
            if args[:2] == ("shell", "cmd"):
                return ""
            return ""

    class Response:
        def __init__(self, status, body=None):
            self.status = status
            self._body = body

        def json(self):
            return self._body

    class Client:
        base_url = "http://127.0.0.1:18093"
        timeout = 30

        def __init__(self, store):
            self.store = store

        @staticmethod
        def _caller(token):
            return "an" if token == "tok-an" else "binh"

        def request(self, method, path, token=None, body=None,
                    params=None, raw_body=None, headers=None):
            handler = {"GET": self.get, "POST": self.post, "PATCH": self.patch,
                       "PUT": self.put, "DELETE": self.delete}[method]
            return handler(path, token=token, body=body, params=params,
                           headers=headers)

        def get(self, path, token=None, params=None, headers=None):
            R = LabelsLifecycleExecutorTest.Response
            caller = self._caller(token)
            if path == "/api/v1/labels":
                return R(200, [dict(lab) for lab in self.store.labels[caller]])
            if path == "/api/v1/conversations":
                params = params or {}
                if "labelId" in params:
                    ids = self.store.filtered(caller, params["labelId"])
                    return R(200, [self.store.entry(caller, cid) for cid in ids])
                # All seeded conversations are unarchived, so both
                # archived=true and the implicit archived=false reads see them.
                if params.get("archived") == "true":
                    return R(200, [])
                return R(200, [self.store.entry(caller, cid)
                               for cid in LabelsLifecycleExecutorTest.Store.CONVS])
            return R(404)

        def post(self, path, token=None, body=None, params=None, headers=None):
            R = LabelsLifecycleExecutorTest.Response
            caller = self._caller(token)
            if path == "/api/v1/labels":
                lab = self.store.create_label(
                    caller, (body or {}).get("name"),
                    color=(body or {}).get("colorId") or "INDIGO")
                return R(201, dict(lab))
            return R(404)

        def patch(self, path, token=None, body=None, params=None, headers=None):
            return LabelsLifecycleExecutorTest.Response(404)

        def put(self, path, token=None, body=None, params=None, headers=None):
            R = LabelsLifecycleExecutorTest.Response
            caller = self._caller(token)
            parts = path.split("/")
            if len(parts) == 7 and parts[3] == "conversations" and parts[5] == "labels":
                self.store.conv_labels[parts[4]][caller].add(parts[6])
                return R(204)
            return R(404)

        def delete(self, path, token=None, params=None, headers=None):
            R = LabelsLifecycleExecutorTest.Response
            caller = self._caller(token)
            parts = path.split("/")
            if len(parts) == 7 and parts[3] == "conversations" and parts[5] == "labels":
                self.store.conv_labels[parts[4]][caller].discard(parts[6])
                return R(204)
            if path.startswith("/api/v1/labels/"):
                self.store.delete_label(caller, path.rsplit("/", 1)[1])
                return R(204)
            return R(404)

    def _fixture(self, stale_reopen=False, duplicate_after_rotate=False,
                 rotation_leak=False):
        direct_id = "conv-an-binh"
        store = self.Store()
        world = self.World(store, direct_id)
        world.stale_reopen = stale_reopen
        world.duplicate_after_rotate = duplicate_after_rotate
        world.rotation_leak = rotation_leak
        client = self.Client(store)

        class Api:
            USER_A = {"phone": "+84900000001"}
            USER_C = {"phone": "+84900000002"}

            @staticmethod
            def direct_conversation(client_obj, token, phone):
                return 200, {"id": direct_id, "e2eeEnabled": False}

        def session(api, recorder, phone):
            if phone.endswith("0001"):
                return {"accessToken": "tok-an",
                        "user": {"id": "user-an", "phone": phone}}
            return {"accessToken": "tok-binh",
                    "user": {"id": "user-binh", "phone": phone}}

        context = {
            "serial": "emulator-5554",
            "base_url": "http://127.0.0.1:18093",
            "row": journeys.ROW_BY_ID["labels-lifecycle"],
            "row_dir": Path(tempfile.mkdtemp()),
            "adb": self.Adb(world),
            "device": self.Device(world),
            "helpers": (Api(), None, self.Patch()),
            "client": client,
            "ui_timeout": 2,
        }
        return store, world, client, context, session

    def test_executor_drives_label_crud_filter_recreation_and_cleanup(self):
        store, world, client, context, session = self._fixture()
        row = journeys.ROW_BY_ID["labels-lifecycle"]
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session):
            result = journeys.run_labels_lifecycle(row, context)

        self.assertIn("REST /labels agrees", result["create"])
        self.assertIn("peer clean", result["assign"])
        self.assertIn("?labelId= agrees", result["filter"])
        self.assertIn("one dialog surface", result["recreate"])
        self.assertIn("force-stop drops the dialog", result["restart"])
        self.assertIn("refetches REST-created labels", result["refetch"])
        self.assertIn("hides the filter chip row", result["delete_last"])
        # The row swept only what it created; no labels or assignments remain.
        self.assertEqual(store.labels["an"], [])
        self.assertEqual(store.labels["binh"], [])
        self.assertTrue(all(not per["an"] for per in store.conv_labels.values()))
        # Rotation ran and the settings were restored to the captured values.
        self.assertEqual(world.rotation, 0)
        self.assertEqual(context["adb"].settings["accelerometer_rotation"], "1")
        self.assertEqual(context["adb"].settings["user_rotation"], "0")
        # World ended on a sane surface for the next row.
        self.assertEqual(world.screen, "inbox")
        self.assertFalse(world.label_dialog or world.inbox_dialog
                         or world.editor_dialog or world.delete_dialog)
        # Six run-scoped captures were written under the row dir.
        for name in ("label-created.png", "label-assigned.png",
                     "label-filtered.png", "label-dialog-landscape.png",
                     "label-reopen-refetch.png", "label-deleted.png",
                     "labels-cleared.png"):
            self.assertTrue((context["row_dir"] / name).exists(), name)

    def test_executor_sweeps_labels_when_rotation_renders_two_surfaces(self):
        store, world, client, context, session = self._fixture(
            duplicate_after_rotate=True)
        row = journeys.ROW_BY_ID["labels-lifecycle"]
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session):
            with self.assertRaises(journeys.ProductFailure) as caught:
                journeys.run_labels_lifecycle(row, context)
        self.assertIn("exactly one label-dialog title", str(caught.exception))
        # label A was already created+assigned when the duplicate surfaced;
        # the sweep must still unassign and delete it.
        self.assertEqual(store.labels["an"], [])
        self.assertTrue(all(not per["an"] for per in store.conv_labels.values()))

    def test_stale_reopen_is_a_product_failure_and_cleanup_runs(self):
        store, world, client, context, session = self._fixture(stale_reopen=True)
        row = journeys.ROW_BY_ID["labels-lifecycle"]
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session):
            with self.assertRaises(journeys.ProductFailure) as caught:
                journeys.run_labels_lifecycle(row, context)
        self.assertIn("did not appear when the dialog reopened",
                      str(caught.exception))
        self.assertEqual(store.labels["an"], [])
        self.assertTrue(all(not per["an"] for per in store.conv_labels.values()))


class PollsRemindersExecutorTest(unittest.TestCase):
    """Offline fake device+REST world for run_polls_reminders.

    The world mirrors the surfaces probed on emulator-5554: the chat overflow
    'Group board' row, the boardTabs strip (Reminders/Polls content-descs), the
    poll create sheet (question + two option rows rendered by default), the
    poll card (optionControl/optionCount pairs, pollVoterCount, pollMeta,
    pollVote, pollManage), the 'Close poll?' confirmation, the reminder editor
    (title/date/time/repeat + Save), the reminder card menu (generic 'title'
    Edit/Delete rows) and the 'Delete this reminder?' confirmation. One Store
    backs both the fake REST client and the UI render so REST/UI agreement
    assertions are real; the fake reminder_clock stands in for the docker
    fixture and applies the production nextAfter semantics (+1 day for DAILY,
    drop-from-list for a fired one-off).
    """

    USERS = {
        "user-an": ("An", "+84900000001", "tok-an"),
        "user-binh": ("Binh", "+84900000002", "tok-binh"),
    }
    PHONE_TO_UID = {"+84900000001": "user-an", "+84900000002": "user-binh"}
    REPEAT_TO_WIRE = {"No repeat": "NONE", "Daily": "DAILY",
                      "Weekly": "WEEKLY", "Monthly": "MONTHLY"}
    MONTHS = ("Jan", "Feb", "Mar", "Apr", "May", "Jun",
              "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    @staticmethod
    def _iso(dt):
        return dt.astimezone(timezone.utc).isoformat()

    @staticmethod
    def _time_label(dt):
        hour = dt.hour % 12 or 12
        ampm = "AM" if dt.hour < 12 else "PM"
        return (f"{PollsRemindersExecutorTest.MONTHS[dt.month - 1]} "
                f"{dt.day}, {dt.year} {hour}:{dt.minute:02d} {ampm}")

    class Node:
        def __init__(self, text="", desc="", rid="", box=(0, 0, 320, 48),
                     clickable=True, cls="android.widget.TextView"):
            self.text = text
            self.desc = desc
            self.attrs = {"resource-id": ("com.nexachat.debug:id/" + rid) if rid else "",
                          "enabled": "true"}
            self.box = box
            self.clickable = clickable
            self.cls = cls

        @property
        def center(self):
            left, top, right, bottom = self.box
            return (left + right) // 2, (top + bottom) // 2

    class Store:
        """Server truth: the disposable group, one poll with a version feed,
        and the reminder board."""

        def __init__(self):
            self.cid = "grp-1"
            self.title = ""
            self.members = {}       # uid -> {"displayName", "role"}
            self.poll = None        # {"id","question","options","closed","closedAt","version"}
            self.changes = []       # {"pollId","version","deleted"}
            self.reminders = []     # {"id","title","remind_at","repeat","fired","deleted"}
            self.seq = 0

        def _next(self, prefix):
            self.seq += 1
            return f"{prefix}-{self.seq}"

        def create_group(self, title, member_ids):
            self.title = title
            self.members = {"user-an": {"displayName": "An", "role": "ADMIN"}}
            for uid in member_ids:
                name = PollsRemindersExecutorTest.USERS[uid][0]
                self.members[uid] = {"displayName": name, "role": "MEMBER"}

        def inbox_entry(self):
            return {"id": self.cid, "type": "GROUP", "title": self.title,
                    "members": [{"userId": uid,
                                 "displayName": member["displayName"],
                                 "role": member["role"]}
                                for uid, member in self.members.items()]}

        # --- polls ---------------------------------------------------------
        def create_poll(self, question, labels):
            self.poll = {"id": self._next("poll"), "question": question,
                         "options": [{"id": self._next("opt"), "label": label,
                                      "votes": set()} for label in labels],
                         "closed": False, "closedAt": None, "version": 1}
            self._bump_poll(first=True)

        def _bump_poll(self, first=False):
            if not first:
                self.poll["version"] += 1
            self.changes.append({"pollId": self.poll["id"],
                                 "version": self.poll["version"],
                                 "deleted": False})

        def vote(self, uid, option_ids):
            if self.poll is None:
                return 404
            if self.poll["closed"]:
                return 409
            wanted = set(option_ids or [])
            known = {opt["id"] for opt in self.poll["options"]}
            if not wanted <= known:
                return 400
            for opt in self.poll["options"]:
                opt["votes"].discard(uid)
                if opt["id"] in wanted:
                    opt["votes"].add(uid)
            self._bump_poll()
            return 200

        def vote_labels(self, uid, labels):
            if self.poll is None:
                return 404
            wanted = {opt["id"] for opt in self.poll["options"]
                      if opt["label"] in set(labels)}
            return self.vote(uid, sorted(wanted))

        def close_poll(self):
            self.poll["closed"] = True
            self.poll["closedAt"] = PollsRemindersExecutorTest._iso(
                datetime.now(timezone.utc))
            self._bump_poll()

        def delete_poll(self):
            if self.poll is None:
                return
            self.poll["version"] += 1
            self.changes.append({"pollId": self.poll["id"],
                                 "version": self.poll["version"],
                                 "deleted": True})
            self.poll = None

        def poll_dto(self, caller):
            poll = self.poll
            options = [{"id": opt["id"], "position": index,
                        "label": opt["label"],
                        "voteCount": len(opt["votes"]),
                        "voterIds": sorted(opt["votes"])}
                       for index, opt in enumerate(poll["options"])]
            voters = set()
            for opt in poll["options"]:
                voters |= opt["votes"]
            dto = {"id": poll["id"], "conversationId": self.cid,
                   "question": poll["question"],
                   "status": "CLOSED" if poll["closed"] else "OPEN",
                   "version": poll["version"],
                   "voterCount": len(voters),
                   "options": options,
                   "myOptionIds": [opt["id"] for opt in poll["options"]
                                   if caller in opt["votes"]]}
            if poll["closedAt"]:
                dto["closedAt"] = poll["closedAt"]
            return dto

        # --- reminders -----------------------------------------------------
        def _reminder(self, rid):
            return next((r for r in self.reminders
                         if r["id"] == rid and not r["deleted"]), None)

        def create_reminder(self, title, repeat):
            self.reminders.append({
                "id": self._next("rem"), "title": title,
                "remind_at": datetime.now(timezone.utc) + timedelta(hours=1),
                "repeat": repeat, "fired": False, "deleted": False})

        def patch_reminder(self, rid, body):
            row = self._reminder(rid)
            if row is None:
                return 404
            if "title" in body and body["title"]:
                row["title"] = body["title"]
            if "remindAt" in body and body["remindAt"]:
                row["remind_at"] = datetime.fromisoformat(
                    str(body["remindAt"]).replace("Z", "+00:00"))
            if "repeat" in body and body["repeat"]:
                row["repeat"] = body["repeat"]
            return 200

        def delete_reminder(self, rid):
            row = self._reminder(rid)
            if row is None:
                return 404
            row["deleted"] = True
            return 204

        def fire(self, rid):
            """The production sweep: a one-off completes and leaves the
            upcoming list; a repeating row advances one repeat period."""
            row = self._reminder(rid)
            assert row is not None, f"fire() on unknown reminder {rid}"
            now = PollsRemindersExecutorTest._iso(datetime.now(timezone.utc))
            if row["repeat"] == "NONE":
                row["fired"] = True
                return {"status": "FIRED", "fired_at": now,
                        "last_fired_at": now}
            days = {"DAILY": 1, "WEEKLY": 7, "MONTHLY": 30}[row["repeat"]]
            row["remind_at"] = row["remind_at"] + timedelta(days=days)
            return {"status": "FIRED", "last_fired_at": now}

        def active_reminders(self):
            return [self._reminder_dto(r) for r in self.reminders
                    if not r["deleted"] and not r["fired"]]

        def _reminder_dto(self, row):
            return {"id": row["id"], "conversationId": self.cid,
                    "title": row["title"],
                    "remindAt": PollsRemindersExecutorTest._iso(row["remind_at"]),
                    "repeat": row["repeat"],
                    "createdBy": "user-an", "createdByName": "An",
                    "canEdit": True}

        def reminder_time_text(self, row):
            return PollsRemindersExecutorTest._time_label(
                row["remind_at"].astimezone(timezone.utc))

    class World:
        def __init__(self, store):
            self.store = store
            self.screen = "inbox"
            self.tab = "reminders"
            self.overflow = False
            self.poll_sheet = False
            self.poll_q = ""
            self.poll_opts = ["", ""]
            self.poll_selected = set()
            self.poll_menu = False
            self.close_dialog = False
            self.editor = False
            self.editor_id = None
            self.editor_title = ""
            self.editor_repeat = "No repeat"
            self.repeat_dialog = False
            self.reminder_menu = False
            self.menu_target = None
            self.delete_dialog = False
            self.focused = None      # (rid, index) of the IME target
            self.keep_vote_after_close = False  # failure variant

        def open_editor(self, rid):
            self.editor = True
            self.editor_id = rid
            if rid is None:
                self.editor_title = ""
                self.editor_repeat = "No repeat"
            else:
                row = self.store._reminder(rid)
                self.editor_title = row["title"]
                wire = row["repeat"]
                self.editor_repeat = next(
                    label for label, w in
                    PollsRemindersExecutorTest.REPEAT_TO_WIRE.items()
                    if w == wire)

        def _reminders_tree(self):
            N = PollsRemindersExecutorTest.Node
            nodes = [N(rid="reminderAdd")]
            rows = self.store.active_reminders()
            if not rows:
                nodes.append(N(text="No reminders yet", rid="reminderEmpty"))
                return nodes
            nodes.append(N(rid="reminderList", box=(0, 300, 320, 620)))
            top = 310
            for row in rows:
                nodes.append(N(text=row["title"], rid="reminderTitle",
                               box=(60, top, 300, top + 40)))
                nodes.append(N(text=self.store.reminder_time_text(
                                 self.store._reminder(row["id"])),
                               rid="reminderTime", box=(60, top + 40, 300, top + 70)))
                nodes.append(N(rid="reminderMenu",
                               box=(280, top, 320, top + 40)))
                top += 90
            return nodes

        def _polls_tree(self):
            N = PollsRemindersExecutorTest.Node
            nodes = [N(rid="pollAdd")]
            poll = self.store.poll
            if poll is None:
                nodes.append(N(text="No polls yet", rid="pollEmpty"))
                return nodes
            nodes.append(N(rid="pollList", box=(0, 300, 320, 620)))
            nodes.append(N(text=poll["question"], rid="pollQuestion",
                           box=(60, 310, 260, 340)))
            nodes.append(N(rid="pollOptions", box=(0, 340, 320, 470)))
            top = 345
            for opt in poll["options"]:
                nodes.append(N(text=opt["label"], rid="optionControl",
                               box=(20, top, 300, top + 30)))
                count = len(opt["votes"])
                suffix = "vote" if count == 1 else "votes"
                nodes.append(N(text=f"{count} {suffix}", rid="optionCount",
                               box=(60, top + 30, 300, top + 55)))
                top += 62
            voters = len(set().union(*(o["votes"] for o in poll["options"])))
            suffix = "voter" if voters == 1 else "voters"
            nodes.append(N(text=f"{voters} {suffix}", rid="pollVoterCount",
                           box=(60, 475, 300, 505)))
            if poll["closed"]:
                meta = ("Closed · Closed " + PollsRemindersExecutorTest._time_label(
                    datetime.now(timezone.utc)))
            else:
                meta = "Active · Open"
            nodes.append(N(text=meta, rid="pollMeta", box=(60, 505, 300, 535)))
            if not poll["closed"] or self.keep_vote_after_close:
                nodes.append(N(text="Vote", rid="pollVote",
                               box=(60, 545, 150, 585)))
                nodes.append(N(text="Retract vote", rid="pollRetract",
                               box=(160, 545, 280, 585)))
            nodes.append(N(rid="pollManage", box=(280, 300, 320, 340)))
            return nodes

        def tree(self):
            N = PollsRemindersExecutorTest.Node
            s = self.store
            nodes = []
            if self.screen == "inbox":
                nodes.append(N(rid="inboxUserAvatar"))
                nodes.append(N(rid="conversationList", box=(0, 300, 320, 620)))
                if "user-an" in s.members:
                    nodes.append(N(text=s.title, box=(76, 360, 300, 390)))
            elif self.screen == "chat":
                nodes.append(N(text=s.title))
                nodes.append(N(rid="chatToolbar", box=(0, 0, 320, 56)))
                nodes.append(N(rid="messageInput"))
                nodes.append(N(rid="moreButton"))
            elif self.screen == "board":
                nodes.append(N(rid="boardToolbar"))
                nodes.append(N(rid="boardTabs"))
                nodes.append(N(desc="Reminders", box=(0, 60, 160, 110)))
                nodes.append(N(desc="Polls", box=(160, 60, 320, 110)))
                nodes += (self._polls_tree() if self.tab == "polls"
                          else self._reminders_tree())
            if self.overflow:
                nodes.append(N(rid="select_dialog_listview",
                               box=(0, 200, 320, 620)))
                for index, label in enumerate(
                        ("Shared media", "Group board", "Mute notifications")):
                    nodes.append(N(text=label, rid="text1",
                                   box=(0, 210 + 60 * index, 320, 260 + 60 * index)))
            if self.poll_sheet:
                nodes.append(N(rid="pollCreateHandle"))
                nodes.append(N(text=self.poll_q, rid="pollQuestionInput",
                               cls="android.widget.EditText",
                               box=(0, 400, 320, 450)))
                nodes.append(N(text=self.poll_opts[0], rid="optionInput",
                               cls="android.widget.EditText",
                               box=(0, 460, 320, 505)))
                nodes.append(N(text=self.poll_opts[1], rid="optionInput",
                               cls="android.widget.EditText",
                               box=(0, 510, 320, 555)))
                nodes.append(N(rid="pollAddOption"))
                nodes.append(N(text="Cancel", rid="pollCreateCancel"))
                nodes.append(N(text="Create", rid="pollCreateSubmit"))
            if self.editor:
                nodes.append(N(text="Edit reminder" if self.editor_id
                               else "New reminder", rid="reminderEditorTitle"))
                nodes.append(N(text=self.editor_title,
                               rid="reminderEditorTitleInput",
                               cls="android.widget.EditText"))
                nodes.append(N(text="Date", rid="reminderEditorDate"))
                nodes.append(N(text="Time", rid="reminderEditorTime"))
                nodes.append(N(text=f"Repeat: {self.editor_repeat}",
                               rid="reminderEditorRepeat"))
                nodes.append(N(text="Cancel", rid="reminderEditorCancel"))
                nodes.append(N(text="Save", rid="reminderEditorSave"))
            if self.poll_menu:
                nodes.append(N(text="Close poll", rid="title",
                               box=(0, 300, 320, 350)))
                nodes.append(N(text="Delete poll", rid="title",
                               box=(0, 350, 320, 400)))
            if self.close_dialog:
                nodes.append(N(text="Close this poll? Votes can no longer change."))
                nodes.append(N(text="Close poll", rid="button1",
                               cls="android.widget.Button"))
                nodes.append(N(text="Cancel", cls="android.widget.Button"))
            if self.repeat_dialog:
                for label in PollsRemindersExecutorTest.REPEAT_TO_WIRE:
                    nodes.append(N(text=label, rid="text1",
                                   cls="android.widget.CheckedTextView"))
            if self.reminder_menu:
                nodes.append(N(text="Edit", rid="title"))
                nodes.append(N(text="Delete", rid="title"))
            if self.delete_dialog:
                nodes.append(N(text="Delete this reminder?"))
                nodes.append(N(text="Delete", rid="button1",
                               cls="android.widget.Button"))
                nodes.append(N(text="Cancel", cls="android.widget.Button"))
            return nodes

    class Device:
        def __init__(self, world):
            self.world = world

        def nodes(self):
            return self.world.tree()

        def _option_index(self, node):
            siblings = sorted(
                (n for n in self.world.tree()
                 if n.attrs["resource-id"].endswith("/optionInput")),
                key=lambda n: n.box[1])
            for index, sibling in enumerate(siblings):
                if sibling.box == node.box:
                    return index
            return 0

        def tap_node(self, node):
            w = self.world
            s = w.store
            rid = node.attrs["resource-id"]
            text = node.text.strip()
            desc = node.desc.strip()
            cls = node.cls
            if w.delete_dialog:
                if text == "Delete" and "Button" in cls:
                    s.delete_reminder(w.menu_target)
                w.delete_dialog = False
                return
            if w.close_dialog:
                if text == "Close poll" and "Button" in cls:
                    s.close_poll()
                w.close_dialog = False
                return
            if w.repeat_dialog:
                if text in PollsRemindersExecutorTest.REPEAT_TO_WIRE:
                    w.editor_repeat = text
                w.repeat_dialog = False
                return
            if w.poll_menu:
                if text == "Close poll":
                    w.close_dialog = True
                elif text == "Delete poll":
                    s.delete_poll()
                w.poll_menu = False
                return
            if w.reminder_menu:
                if text == "Edit":
                    w.open_editor(w.menu_target)
                elif text == "Delete":
                    w.delete_dialog = True
                w.reminder_menu = False
                return
            if w.overflow:
                w.overflow = False
                if text == "Group board" and (
                        rid.endswith("text1") or "CheckedTextView" in cls):
                    w.screen = "board"
                    w.tab = "reminders"
                return
            if w.poll_sheet:
                if rid.endswith("pollQuestionInput"):
                    w.focused = ("pollQuestionInput", 0)
                elif rid.endswith("optionInput"):
                    w.focused = ("optionInput", self._option_index(node))
                elif rid.endswith("pollCreateSubmit"):
                    question = w.poll_q.strip()
                    options = [o.strip() for o in w.poll_opts if o.strip()]
                    if question and len(options) >= 2:
                        s.create_poll(question, options)
                        w.poll_sheet = False
                elif rid.endswith("pollCreateCancel"):
                    w.poll_sheet = False
                return
            if w.editor:
                if rid.endswith("reminderEditorTitleInput"):
                    w.focused = ("reminderEditorTitleInput", 0)
                elif rid.endswith("reminderEditorRepeat"):
                    w.repeat_dialog = True
                elif rid.endswith("reminderEditorSave"):
                    title = w.editor_title.strip()
                    if title:
                        repeat = (PollsRemindersExecutorTest
                                  .REPEAT_TO_WIRE[w.editor_repeat])
                        if w.editor_id is None:
                            s.create_reminder(title, repeat)
                        else:
                            s.patch_reminder(
                                w.editor_id, {"title": title, "repeat": repeat})
                        w.editor = False
                        w.editor_id = None
                elif rid.endswith("reminderEditorCancel"):
                    w.editor = False
                    w.editor_id = None
                return
            if w.screen == "inbox":
                if text == s.title and node.box and node.box[1] >= 300:
                    w.screen = "chat"
                return
            if w.screen == "chat":
                if rid.endswith("moreButton"):
                    w.overflow = True
                return
            if w.screen == "board":
                if desc == "Polls":
                    w.tab = "polls"
                elif desc == "Reminders":
                    w.tab = "reminders"
                elif rid.endswith("pollAdd"):
                    w.poll_sheet = True
                    w.poll_q = ""
                    w.poll_opts = ["", ""]
                elif rid.endswith("optionControl"):
                    w.poll_selected = {text}
                elif rid.endswith("pollVote"):
                    s.vote_labels("user-an", sorted(w.poll_selected))
                elif rid.endswith("pollManage"):
                    w.poll_menu = True
                elif rid.endswith("reminderAdd"):
                    w.open_editor(None)
                elif rid.endswith("reminderMenu"):
                    active = s.active_reminders()
                    if active:
                        w.menu_target = active[0]["id"]
                        w.reminder_menu = True
                return

        def type_text(self, value):
            w = self.world
            focused = w.focused
            if focused == ("pollQuestionInput", 0):
                w.poll_q += value
            elif focused is not None and focused[0] == "optionInput":
                w.poll_opts[focused[1]] += value
            elif focused == ("reminderEditorTitleInput", 0):
                w.editor_title += value

        def delete_chars(self, count):
            if not count:
                return
            w = self.world
            focused = w.focused
            if focused == ("pollQuestionInput", 0):
                w.poll_q = w.poll_q[:-count]
            elif focused is not None and focused[0] == "optionInput":
                w.poll_opts[focused[1]] = w.poll_opts[focused[1]][:-count]
            elif focused == ("reminderEditorTitleInput", 0):
                w.editor_title = w.editor_title[:-count]

        def move_end(self):
            return None

        def swipe(self, x1, y1, x2, y2, duration_ms=300):
            return None

        def key(self, code):
            w = self.world
            if w.focused is not None:
                w.focused = None
                return
            if w.delete_dialog or w.close_dialog or w.repeat_dialog \
                    or w.poll_menu or w.reminder_menu or w.overflow:
                w.delete_dialog = w.close_dialog = w.repeat_dialog = False
                w.poll_menu = w.reminder_menu = w.overflow = False
                return
            if w.poll_sheet:
                w.poll_sheet = False
                return
            if w.editor:
                w.editor = False
                w.editor_id = None
                return
            if w.screen == "board":
                w.screen = "chat"
                return
            w.screen = "inbox"

        def screenshot(self, path):
            Path(path).write_bytes(b"png")

    class Patch:
        @staticmethod
        def ui_nodes(device, timeout=8):
            return device.nodes()

        @staticmethod
        def grant_test_permissions():
            return {}

        @staticmethod
        def filtered_logcat(baseline):
            return [], False

    class Adb:
        serial = "emulator-5554"

        def command(self, *args, **kwargs):
            if args[:2] == ("shell", "date"):
                return datetime.now(timezone.utc).strftime(
                    "%Y-%m-%dT%H:%M:%S%z")
            return ""

    class Response:
        def __init__(self, status, body=None):
            self.status = status
            self._body = body

        def json(self):
            return self._body

    class Clock:
        """Stands in for reminder_clock.ReminderClock over the Store."""

        def __init__(self, store):
            self.store = store

        def make_due(self, reminder_id, minutes_ago=1):
            row = self.store._reminder(reminder_id)
            assert row is not None
            row["remind_at"] = datetime.now(timezone.utc) - timedelta(
                minutes=minutes_ago)
            return {"status": "DUE"}

        def wait_fired(self, reminder_id, timeout=20, poll_interval=0.5):
            return self.store.fire(reminder_id)

    class Client:
        base_url = "http://127.0.0.1:18093"
        timeout = 30

        def __init__(self, store):
            self.store = store

        @staticmethod
        def _caller(token):
            return {"tok-an": "user-an", "tok-binh": "user-binh"}.get(token)

        def request(self, method, path, token=None, body=None,
                    params=None, raw_body=None, headers=None):
            handler = {"GET": self.get, "POST": self.post, "PATCH": self.patch,
                       "PUT": self.put, "DELETE": self.delete}[method]
            return handler(path, token=token, body=body, params=params,
                           headers=headers)

        @staticmethod
        def _segment(path, marker):
            return path.split(marker, 1)[1].split("/", 1)[0]

        def get(self, path, token=None, params=None, headers=None):
            R = PollsRemindersExecutorTest.Response
            s = self.store
            caller = self._caller(token)
            if path == "/api/v1/conversations":
                if caller in s.members:
                    return R(200, [s.inbox_entry()])
                return R(200, [])
            if path.endswith("/polls/changes"):
                return R(200, {"items": list(s.changes),
                               "nextSince": s.changes[-1]["version"]
                               if s.changes else 0, "hasMore": False})
            if path.endswith("/polls"):
                items = [s.poll_dto(caller)] if s.poll else []
                return R(200, {"items": items, "nextBefore": None})
            if path.endswith("/reminders"):
                return R(200, s.active_reminders())
            return R(404)

        def post(self, path, token=None, body=None, headers=None):
            R = PollsRemindersExecutorTest.Response
            s = self.store
            caller = self._caller(token)
            if path == "/api/v1/conversations/group":
                s.create_group(str((body or {}).get("title") or ""),
                               list((body or {}).get("memberIds") or []))
                return R(201, {"id": s.cid})
            if path.endswith("/leave"):
                s.members.pop(caller, None)
                return R(204)
            if "/polls/" in path and path.endswith("/close"):
                if s.poll is None or s.poll["closed"]:
                    return R(409)
                s.close_poll()
                return R(200, s.poll_dto(caller))
            return R(404)

        def put(self, path, token=None, body=None, headers=None):
            R = PollsRemindersExecutorTest.Response
            s = self.store
            caller = self._caller(token)
            if "/polls/" in path and path.endswith("/vote"):
                status = s.vote(caller, list((body or {}).get("optionIds") or []))
                return R(status, s.poll_dto(caller) if status == 200 else None)
            return R(404)

        def patch(self, path, token=None, body=None, headers=None):
            R = PollsRemindersExecutorTest.Response
            s = self.store
            if "/reminders/" in path:
                rid = self._segment(path, "/reminders/")
                status = s.patch_reminder(rid, body or {})
                if status != 200:
                    return R(status)
                return R(200, s._reminder_dto(s._reminder(rid)))
            return R(404)

        def delete(self, path, token=None, params=None, headers=None):
            R = PollsRemindersExecutorTest.Response
            s = self.store
            if "/polls/" in path:
                s.delete_poll()
                return R(204)
            if "/reminders/" in path:
                return R(s.delete_reminder(self._segment(path, "/reminders/")))
            if "/members/" in path:
                uid = self._segment(path, "/members/")
                s.members.pop(uid, None)
                return R(204)
            return R(404)

    def _fixture(self, keep_vote_after_close=False):
        store = self.Store()
        world = self.World(store)
        world.keep_vote_after_close = keep_vote_after_close
        client = self.Client(store)

        class Api:
            USER_A = {"phone": "+84900000001"}
            USER_C = {"phone": "+84900000002"}
            PASSWORD = "password123"

        def session(api, recorder, phone):
            uid = self.PHONE_TO_UID[phone]
            return {"accessToken": self.USERS[uid][2],
                    "user": {"id": uid, "phone": phone}}

        context = {
            "serial": "emulator-5554",
            "base_url": "http://127.0.0.1:18093",
            "row": journeys.ROW_BY_ID["polls-reminders"],
            "row_dir": Path(tempfile.mkdtemp()),
            "adb": self.Adb(),
            "device": self.Device(world),
            "helpers": (Api(), None, self.Patch()),
            "client": client,
            "ui_timeout": 2,
            "reminder_clock": self.Clock(store),
        }
        return store, world, client, context, session

    def test_executor_drives_poll_votes_close_and_reminder_lifecycle(self):
        store, world, client, context, session = self._fixture()
        row = journeys.ROW_BY_ID["polls-reminders"]
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session):
            result = journeys.run_polls_reminders(row, context)

        self.assertIn("OPEN with both options", result["poll_create"])
        self.assertIn("version rose twice", result["poll_vote"])
        self.assertIn("2 voters", result["poll_peer"])
        self.assertIn("monotonic", result["poll_close"])
        self.assertIn("one-off", result["reminder_create"])
        self.assertIn("patched the title", result["reminder_edit"])
        self.assertIn("dropped the one-off", result["reminder_fired"])
        self.assertIn("next day's occurrence", result["reminder_daily"])
        self.assertIn("from board and REST", result["reminder_delete"])
        # Server truth after the full journey + sweep: nothing survives.
        self.assertIsNone(store.poll)
        self.assertTrue(all(r["deleted"] or r["fired"]
                            for r in store.reminders))
        self.assertEqual(store.members, {})
        self.assertEqual(world.screen, "inbox")
        # The version feed observed the close before the cleanup tombstone.
        versions = [item["version"] for item in store.changes]
        self.assertEqual(versions[:5], [1, 2, 3, 4, 5])

    def test_executor_sweeps_when_the_closed_poll_keeps_its_vote_control(self):
        store, world, client, context, session = self._fixture(
            keep_vote_after_close=True)
        row = journeys.ROW_BY_ID["polls-reminders"]
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session):
            with self.assertRaises(journeys.ProductFailure) as caught:
                journeys.run_polls_reminders(row, context)
        self.assertIn("closed poll still exposes a Vote control",
                      str(caught.exception))
        # The poll was closed for real; the sweep still deletes it, and An's
        # disposable group is left behind empty.
        self.assertIsNone(store.poll)
        self.assertEqual(store.members, {})

    def test_make_due_row_missing_fails_as_environment_with_db_hint(self):
        # A fixture that cannot find the reminder row (e.g. the stack serves
        # another database) must be a harness/environment failure carrying the
        # NEXACHAT_E2E_REMINDER_DB hint, never a false FAIL_PRODUCT.
        module = journeys._import_reminder_clock()
        store, world, client, context, session = self._fixture()

        class MissingRowClock(self.Clock):
            def make_due(self, reminder_id, minutes_ago=1):
                raise module.ReminderNotFoundError(
                    f"Reminder {reminder_id} not found in database nexachat")

        context["reminder_clock"] = MissingRowClock(store)
        row = journeys.ROW_BY_ID["polls-reminders"]
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session):
            with self.assertRaises(journeys.EnvironmentFailure) as caught:
                journeys.run_polls_reminders(row, context)
        self.assertIn("not found", str(caught.exception))
        self.assertIn("NEXACHAT_E2E_REMINDER_DB", str(caught.exception))

    def test_make_due_psql_failure_fails_as_environment(self):
        # psql missing/failing (docker exec refused, container down) must be
        # a harness/environment failure with a container hint.
        module = journeys._import_reminder_clock()
        store, world, client, context, session = self._fixture()

        class PsqlDownClock(self.Clock):
            def make_due(self, reminder_id, minutes_ago=1):
                raise module.PsqlExecutionError(
                    "psql exited with code 1: connection refused")

        context["reminder_clock"] = PsqlDownClock(store)
        row = journeys.ROW_BY_ID["polls-reminders"]
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session", side_effect=session):
            with self.assertRaises(journeys.EnvironmentFailure) as caught:
                journeys.run_polls_reminders(row, context)
        self.assertIn("could not pull the row due", str(caught.exception))
        self.assertIn("psql", str(caught.exception))
        self.assertIn("DB container", str(caught.exception))

    def test_unusable_reminder_db_target_fails_as_environment(self):
        # A refused NEXACHAT_E2E_REMINDER_DB target must surface as a clear
        # classified environment error, not a raw ValueError from the fixture.
        store, world, client, context, session = self._fixture()
        context.pop("reminder_clock")
        row = journeys.ROW_BY_ID["polls-reminders"]
        with mock.patch.dict("os.environ",
                             {"NEXACHAT_E2E_REMINDER_DB": "postgres"}):
            with mock.patch.object(journeys, "_assert_device_account_an"), \
                    mock.patch.object(journeys, "_actor_session", side_effect=session):
                with self.assertRaises(journeys.EnvironmentFailure) as caught:
                    journeys.run_polls_reminders(row, context)
        self.assertIn("reminder_clock DB fixture is not usable",
                      str(caught.exception))
        self.assertIn("Refusing database name", str(caught.exception))
        self.assertIn("NEXACHAT_E2E_REMINDER_DB", str(caught.exception))


class OfflineSyncExecutorTest(unittest.TestCase):
    """Offline fake device+REST world for run_offline_sync.

    The world mirrors the surfaces probed on emulator-5554: the paged inbox
    list (8 more rows per swipe-up, pull-down refresh), the chat screen
    (composer, send button, message bubbles, long-press action sheet with
    the Like tile and the optimistic chip), the profile -> logoutRow ->
    Sign out confirm -> login form path, and force-stop/relaunch. One Store
    backs REST and the device render so REST/UI agreement assertions are
    real; the world emits scripted OkHttp '--> POST /api/v1/sync' lines
    whose count and body size scale with the caller's conversation set, so
    the >100-watermark multi-batch assertion is exercised, and outbox flush
    happens exactly once on network restore.
    """

    USERS = {
        "user-an": ("An", "+84900000001", "tok-an"),
        "user-binh": ("Binh", "+84900000002", "tok-binh"),
        "user-chi": ("Chi", "+84900000003", "tok-chi"),
    }
    PHONE_TO_UID = {"+84900000001": "user-an", "+84900000002": "user-binh",
                    "+84900000003": "user-chi"}

    class Node:
        def __init__(self, text="", desc="", rid="", box=(0, 0, 320, 48),
                     clickable=True, cls="android.widget.TextView"):
            self.text = text
            self.desc = desc
            self.attrs = {"resource-id": ("com.nexachat.debug:id/" + rid) if rid else "",
                          "enabled": "true"}
            self.box = box
            self.clickable = clickable
            self.cls = cls

        @property
        def center(self):
            left, top, right, bottom = self.box
            return (left + right) // 2, (top + bottom) // 2

    class Store:
        """Server truth: conversations, membership, and message rows."""

        def __init__(self):
            self.conversations = {
                "conv-ab": {"id": "conv-ab", "type": "DIRECT", "title": None,
                            "members": {"user-an", "user-binh"}, "created": 0},
            }
            self.group_seq = 0
            self.msg_seq = 0
            self.messages = {"conv-ab": []}

        def create_group(self, caller, title, member_ids):
            self.group_seq += 1
            cid = f"grp-{self.group_seq - 1:03d}"
            self.conversations[cid] = {
                "id": cid, "type": "GROUP", "title": title,
                "members": {caller} | set(member_ids or []),
                "created": self.group_seq}
            self.messages[cid] = []
            # The real server posts a system message on creation, so the group
            # lands in the messaged block ordered by activity, not the empty
            # tail block.
            self.send(caller, cid, "created the group")
            return {"id": cid, "type": "GROUP", "title": title}

        def inbox(self, caller):
            return [conv for conv in self.conversations.values()
                    if caller in conv["members"]]

        def conversation_of(self, path, prefix, suffix=""):
            tail = path.split(prefix, 1)[1]
            return tail.split(suffix, 1)[0] if suffix else tail.split("/", 1)[0]

        def send(self, caller, cid, content, client_message_id=None):
            conv = self.conversations.get(cid)
            if conv is None or caller not in conv["members"]:
                return None
            self.msg_seq += 1
            msg = {"id": f"m-{self.msg_seq}", "conversationId": cid,
                   "senderId": caller, "content": content,
                   "clientMessageId": client_message_id or f"cm-{self.msg_seq}",
                   "editedAt": None, "deletedAt": None, "reactions": []}
            self.messages.setdefault(cid, []).append(msg)
            return msg

        def edit(self, caller, cid, mid, content):
            msg = self.find(cid, mid)
            if msg is None or msg["senderId"] != caller or msg["deletedAt"]:
                return None
            msg["content"] = content
            msg["editedAt"] = "now"
            return msg

        def delete(self, caller, cid, mid):
            msg = self.find(cid, mid)
            if msg is None or msg["senderId"] != caller:
                return 403
            if msg["deletedAt"]:
                return 404
            msg["deletedAt"] = "now"
            return 204

        def find(self, cid, mid):
            return next((m for m in self.messages.get(cid, [])
                         if m["id"] == mid), None)

        def add_reaction(self, cid, mid, emoji):
            msg = self.find(cid, mid)
            if msg is None:
                return
            existing = next((r for r in msg["reactions"]
                             if r["emoji"] == emoji), None)
            if existing is not None:
                existing["count"] += 1
            else:
                msg["reactions"].append({"emoji": emoji, "count": 1})

        def remove_member(self, cid, uid):
            conv = self.conversations.get(cid)
            if conv is not None:
                conv["members"].discard(uid)

    class World:
        """Device-side state: process, screen stack, Room mirror, outbox."""

        def __init__(self, store):
            self.store = store
            self.account = "user-an"
            self.offline = False
            self.screen = "inbox"
            self.history = []
            self.open_conv = None
            self.loaded = 30
            self.pending_texts = []      # {"conv", "content"} queued offline
            self.pending_reactions = []  # {"conv", "mid", "emoji"}
            self.chip_msgs = set()       # mids with the optimistic chip
            self.action_target = None    # content of the long-pressed bubble
            self.confirm_logout = False
            self.login_inputs = {}
            self.sync_log = []
            self.cold_start()

        def go(self, screen):
            self.history.append(self.screen)
            self.screen = screen

        def conv_count(self):
            return len(self.store.inbox(self.account)) if self.account else 0

        def emit_sync(self):
            if not self.account or self.offline:
                return
            count = self.conv_count()
            batches = max(1, (count + 99) // 100)
            body = 60 + count * 60
            for _ in range(batches):
                self.sync_log.append(
                    "I/okhttp.OkHttpClient: --> POST "
                    f"http://10.0.2.2:8080/api/v1/sync ({body}-byte body)")

        def cold_start(self):
            self.screen = "inbox" if self.account else "login"
            self.history = []
            self.open_conv = None
            self.loaded = 30
            self.action_target = None
            self.confirm_logout = False
            self.flush()
            self.emit_sync()

        def flush(self):
            """The outbox drains exactly once on reconnect/cold start."""
            if self.offline or not self.account:
                return
            for item in list(self.pending_texts):
                self.store.send(self.account, item["conv"], item["content"])
                self.pending_texts.remove(item)
            for item in list(self.pending_reactions):
                self.store.add_reaction(item["conv"], item["mid"], item["emoji"])
                self.pending_reactions.remove(item)

        def inbox_rows(self):
            """[(title, cid)] in Room inbox order: message-bearing rows by
            lastMessageAt DESC first, then the lastMessageAt=0 block by id ASC
            (ConversationDao.inboxFlow). A group that gains a message jumps
            out of the id-sorted tail block, like the marker conversation."""
            messaged = []
            empty = []
            for conv in self.store.inbox(self.account):
                msgs = self.store.messages.get(conv["id"], [])
                if msgs:
                    last = max(int(m["id"].split("-")[1]) for m in msgs)
                    messaged.append((-last, conv))
                else:
                    empty.append(conv)

            def title_of(conv):
                if conv["type"] == "DIRECT":
                    peer = next(uid for uid in conv["members"]
                                if uid != self.account)
                    return OfflineSyncExecutorTest.USERS[peer][0]
                return conv["title"]

            rows = [(title_of(conv), conv["id"])
                    for _last, conv in sorted(messaged,
                                              key=lambda item: item[0])]
            rows.extend((title_of(conv), conv["id"])
                        for conv in sorted(empty, key=lambda c: c["id"]))
            return rows

        def tail_messages(self):
            return self.store.messages.get(self.open_conv, [])[-10:]

        def tree(self):
            N = OfflineSyncExecutorTest.Node
            nodes = []
            if self.screen == "login":
                nodes.append(N(text=self.login_inputs.get("phoneInput", ""),
                               rid="phoneInput", cls="android.widget.EditText"))
                nodes.append(N(text=self.login_inputs.get("passwordInput", ""),
                               rid="passwordInput", cls="android.widget.EditText"))
                nodes.append(N(text="Sign in"))
                nodes.append(N(text="Sign in", rid="primaryAction",
                               cls="android.widget.Button"))
            elif self.screen == "inbox":
                nodes.append(N(rid="inboxUserAvatar"))
                nodes.append(N(rid="conversationList", box=(0, 300, 320, 620)))
                for index, (title, _cid) in enumerate(
                        self.inbox_rows()[:self.loaded]):
                    nodes.append(N(text=title,
                                   box=(10, 310 + index * 2, 310, 312 + index * 2)))
            elif self.screen == "chat":
                nodes.append(N(rid="chatToolbar"))
                nodes.append(N(text="Message", rid="messageInput",
                               cls="android.widget.EditText"))
                nodes.append(N(rid="sendButton"))
                for msg in self.tail_messages():
                    if msg["deletedAt"]:
                        nodes.append(N(text="This message was deleted",
                                       rid="messageBody",
                                       box=(0, 400, 320, 430)))
                        continue
                    nodes.append(N(text=msg["content"], rid="messageBody",
                                   box=(0, 400, 320, 430)))
                    if msg["editedAt"]:
                        nodes.append(N(text="Edited", box=(0, 430, 320, 450)))
                    if msg["reactions"] or msg["id"] in self.chip_msgs:
                        nodes.append(N(desc="👍, 1, yours",
                                       box=(0, 450, 320, 470)))
                for item in self.pending_texts:
                    if item["conv"] == self.open_conv:
                        nodes.append(N(text=item["content"], rid="messageBody",
                                       box=(0, 460, 320, 490)))
            elif self.screen == "profile":
                name = OfflineSyncExecutorTest.USERS[self.account][0]
                nodes.append(N(text=name, rid="profileName"))
                nodes.append(N(rid="profileColumn", box=(0, 0, 320, 640)))
                nodes.append(N(text="Sign out", rid="logoutRow"))
            if self.action_target is not None:
                nodes.append(N(text=self.action_target,
                               rid="messageActionsTitle"))
                nodes.append(N(desc="Like", rid="reactionLike"))
                nodes.append(N(rid="messageActionList",
                               box=(0, 300, 320, 620)))
            if self.confirm_logout:
                nodes.append(N(text="Sign out", clickable=False))
                nodes.append(N(text="Sign out of this account on this device?"))
                nodes.append(N(text="Sign out", cls="android.widget.Button"))
                nodes.append(N(text="Cancel", cls="android.widget.Button"))
            return nodes

        def login(self):
            uid = OfflineSyncExecutorTest.PHONE_TO_UID.get(
                self.login_inputs.get("phoneInput", ""))
            if uid is None:
                return
            self.account = uid
            self.cold_start()

    class Device:
        def __init__(self, world):
            self.world = world

        def nodes(self):
            return self.world.tree()

        def tap_node(self, node):
            w = self.world
            rid = node.attrs["resource-id"]
            text = node.text.strip()
            if w.confirm_logout:
                w.confirm_logout = False
                if text == "Sign out" and node.clickable and "Button" in node.cls:
                    w.account = None
                    w.screen = "login"
                    w.history.clear()   # login starts a new task root
                    w.login_inputs = {}
                return
            if w.action_target is not None:
                target = w._sheet_target
                w.action_target = None
                if node.desc.strip() == "Like":
                    store = w.store
                    msg = next((m for m in
                                store.messages.get(w.open_conv, [])
                                if m["content"] == target), None)
                    if msg is not None:
                        if w.offline:
                            w.pending_reactions.append(
                                {"conv": w.open_conv, "mid": msg["id"],
                                 "emoji": "👍"})
                        else:
                            store.add_reaction(w.open_conv, msg["id"], "👍")
                        w.chip_msgs.add(msg["id"])
                return
            if w.screen == "inbox":
                if rid.endswith("inboxUserAvatar"):
                    w.go("profile")
                    return
                for title, cid in w.inbox_rows():
                    if title == text:
                        w.open_conv = cid
                        w.go("chat")
                        return
                return
            if w.screen == "chat":
                if rid.endswith("sendButton"):
                    content = getattr(w, "typed", "")
                    w.typed = ""
                    if content:
                        if w.offline:
                            w.pending_texts.append(
                                {"conv": w.open_conv, "content": content})
                        else:
                            w.store.send(w.account, w.open_conv, content)
                return
            if w.screen == "profile":
                if rid.endswith("logoutRow"):
                    w.confirm_logout = True
                return
            if w.screen == "login" and rid.endswith("primaryAction"):
                w.login()

        def long_press(self, node):
            w = self.world
            if w.screen == "chat" and \
                    node.attrs["resource-id"].endswith("messageBody"):
                w.action_target = node.text.strip()
                w._sheet_target = node.text.strip()

        def type_text(self, value):
            self.world.typed = getattr(self.world, "typed", "") + value

        def swipe(self, x1, y1, x2, y2, duration_ms=300):
            w = self.world
            if w.screen != "inbox":
                return
            if y2 < y1:
                w.loaded = min(len(w.inbox_rows()), w.loaded + 8)
            else:
                w.loaded = max(30, w.loaded)

        def key(self, code):
            w = self.world
            if w.screen == "login":
                return  # hardware back only dismisses the keyboard
            if w.confirm_logout:
                w.confirm_logout = False
            elif w.action_target is not None:
                w.action_target = None
            elif w.history:
                w.screen = w.history.pop()
            elif w.screen == "chat":
                w.screen = "inbox"
                w.open_conv = None
            elif w.screen == "profile":
                w.screen = "inbox"

        def screenshot(self, path):
            Path(path).write_bytes(b"png")

    class Patch:
        PASSWORD = "password123"

        class ProductDefect(Exception):
            pass

        @staticmethod
        def ui_nodes(device, timeout=8):
            return device.nodes()

        @staticmethod
        def node_by_id(nodes, suffix):
            return next((node for node in nodes
                         if node.attrs.get("resource-id", "")
                         .endswith("/" + suffix)), None)

        @staticmethod
        def text_matches(node, candidates):
            value = (node.text + " " + node.desc).casefold()
            return any(candidate.casefold() in value
                       for candidate in candidates)

        @staticmethod
        def login_form_visible(nodes):
            ids = {node.attrs.get("resource-id", "").rsplit("/", 1)[-1]
                   for node in nodes}
            return {"phoneInput", "passwordInput"} <= ids and any(
                OfflineSyncExecutorTest.Patch.text_matches(
                    node, ("Sign in", "Đăng nhập")) for node in nodes)

        @staticmethod
        def inbox_visible(nodes):
            return OfflineSyncExecutorTest.Patch.node_by_id(
                nodes, "inboxUserAvatar") is not None

        @staticmethod
        def fill_login_field(ui, field_id, value):
            ui.world.login_inputs[field_id] = value

        @staticmethod
        def settings_snapshot():
            return {"wifi_on": "1", "mobile_data": "1",
                    "airplane_mode_on": "0", "font_scale": "1.0",
                    "system_locales": "en-US"}

        @staticmethod
        def cut_network():
            raise RuntimeError("replaced by bound fake")

        @staticmethod
        def restore_test_network(before):
            raise RuntimeError("replaced by bound fake")

        @staticmethod
        def restore_device_settings(before):
            return dict(before or {})

        @staticmethod
        def queue_offline_text(ui, content):
            ui.world.pending_texts.append(
                {"conv": ui.world.open_conv, "content": content})
            deadline = 10
            while deadline and not any(
                    node.text.strip() == content for node in ui.world.tree()):
                deadline -= 1
            if not deadline:
                raise OfflineSyncExecutorTest.Patch.ProductDefect(
                    "offline text was not rendered")

        @staticmethod
        def wait_server_rows(container, db_name, contents, timeout=40):
            raise RuntimeError("replaced by bound fake")

        @staticmethod
        def grant_test_permissions():
            return {}

        @staticmethod
        def sign_in_as_device_account(ui, evidence_dir):
            w = ui.world
            if w.screen == "inbox":
                return
            w.login_inputs["phoneInput"] = "+84900000001"
            w.login_inputs["passwordInput"] = "password123"
            w.login()

        @staticmethod
        def ensure_device_account(ui, evidence_dir, *, fresh_login):
            w = ui.world
            if w.screen == "inbox":
                return
            if w.screen == "login":
                OfflineSyncExecutorTest.Patch.sign_in_as_device_account(
                    ui, evidence_dir)
                return
            w.cold_start()

        @staticmethod
        def filtered_logcat(baseline):
            return [], False

    class Adb:
        serial = "emulator-5554"

        def __init__(self, world):
            self.world = world

        def command(self, *args, **kwargs):
            if "force-stop" in args:
                return ""
            if args[:2] == ("shell", "am") and "start" in args:
                self.world.cold_start()
                return ""
            if args[:1] == ("logcat",):
                return "\n".join(self.world.sync_log)
            return ""

    class Response:
        def __init__(self, status, body=None):
            self.status = status
            self._body = body

        def json(self):
            return self._body

    class Client:
        base_url = "http://127.0.0.1:18093"

        def __init__(self, store):
            self.store = store

        @staticmethod
        def _caller(token):
            return {"tok-an": "user-an", "tok-binh": "user-binh",
                    "tok-chi": "user-chi"}.get(token)

        @staticmethod
        def _segments(path):
            return [part for part in path.split("/") if part]

        def get(self, path, token=None, params=None, headers=None):
            R = OfflineSyncExecutorTest.Response
            s = self.store
            caller = self._caller(token)
            if caller is None:
                return R(401)
            if path == "/api/v1/conversations":
                return R(200, [{"id": conv["id"], "type": conv["type"],
                                "title": conv["title"],
                                "members": sorted(conv["members"])}
                               for conv in s.inbox(caller)])
            seg = self._segments(path)
            if seg[:3] == ["api", "v1", "conversations"] and len(seg) == 5 \
                    and seg[4] == "messages":
                cid = seg[3]
                if caller not in s.conversations.get(cid, {}).get("members", set()):
                    return R(403)
                limit = int((params or {}).get("limit") or 50)
                items = list(reversed(s.messages.get(cid, [])))[:limit]
                return R(200, {"items": items})
            return R(404)

        def post(self, path, token=None, body=None, headers=None):
            R = OfflineSyncExecutorTest.Response
            s = self.store
            caller = self._caller(token)
            if caller is None:
                return R(401)
            if path == "/api/v1/conversations/group":
                group = s.create_group(caller, (body or {}).get("title"),
                                       (body or {}).get("memberIds"))
                return R(201, group)
            seg = self._segments(path)
            if seg[:3] == ["api", "v1", "conversations"] and len(seg) == 5:
                cid = seg[3]
                if seg[4] == "messages":
                    msg = s.send(caller, cid, (body or {}).get("content"),
                                 (body or {}).get("clientMessageId"))
                    return R(201, msg) if msg else R(403)
                if seg[4] == "leave":
                    s.remove_member(cid, caller)
                    return R(204)
            return R(404)

        def patch(self, path, token=None, body=None, headers=None):
            R = OfflineSyncExecutorTest.Response
            s = self.store
            caller = self._caller(token)
            seg = self._segments(path)
            if len(seg) == 6 and seg[:3] == ["api", "v1", "conversations"] \
                    and seg[4] == "messages":
                msg = s.edit(caller, seg[3], seg[5],
                             (body or {}).get("content"))
                return R(200, msg) if msg else R(404)
            return R(404)

        def delete(self, path, token=None, params=None, headers=None):
            R = OfflineSyncExecutorTest.Response
            s = self.store
            caller = self._caller(token)
            seg = self._segments(path)
            if len(seg) == 6 and seg[:3] == ["api", "v1", "conversations"]:
                if seg[4] == "messages":
                    return R(s.delete(caller, seg[3], seg[5]))
                if seg[4] == "members":
                    s.remove_member(seg[3], seg[5])
                    return R(204)
            return R(404)

        def put(self, path, token=None, body=None, headers=None):
            return OfflineSyncExecutorTest.Response(404)

    def _fixture(self, variant=None):
        store = self.Store()
        world = self.World(store)
        world.variant = variant
        client = self.Client(store)
        patch = self.Patch()
        patch.cut_network = lambda: setattr(world, "offline", True)

        def restore(before):
            world.offline = False
            world.flush()
            world.emit_sync()

        patch.restore_test_network = restore

        def server_rows(container, db_name, contents, timeout=40):
            rows = []
            for msgs in store.messages.values():
                for msg in msgs:
                    if msg["content"] in contents:
                        rows.append({"content": msg["content"],
                                     "client_message_id": msg["clientMessageId"],
                                     "count": 1})
            return rows

        patch.wait_server_rows = server_rows

        class Api:
            USER_A = {"phone": "+84900000001"}
            USER_B = {"phone": "+84900000003"}
            USER_C = {"phone": "+84900000002"}
            PASSWORD = "password123"

            @staticmethod
            def direct_conversation(client_obj, token, peer_phone):
                return 200, {"id": "conv-ab", "type": "DIRECT"}

            @staticmethod
            def login(client_obj, phone):
                uid = OfflineSyncExecutorTest.PHONE_TO_UID[phone]
                return {"accessToken": OfflineSyncExecutorTest.USERS[uid][2],
                        "user": {"id": uid, "phone": phone}}

        def session(api, recorder, phone):
            uid = self.PHONE_TO_UID[phone]
            return {"accessToken": self.USERS[uid][2],
                    "user": {"id": uid, "phone": phone}}

        context = {
            "serial": "emulator-5554",
            "base_url": "http://127.0.0.1:18093",
            "row": journeys.ROW_BY_ID["offline-sync"],
            "row_dir": Path(tempfile.mkdtemp()),
            "adb": self.Adb(world),
            "device": self.Device(world),
            "helpers": (Api(), None, patch),
            "client": client,
            "ui_timeout": 2,
        }
        return store, world, client, context, session

    def _run(self, context, session):
        row = journeys.ROW_BY_ID["offline-sync"]
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session",
                                  side_effect=session), \
                mock.patch.object(journeys.time, "sleep"):
            return journeys.run_offline_sync(row, context)

    def test_executor_drives_offline_queue_reconcile_and_account_switch(self):
        store, world, client, context, session = self._fixture()
        result = self._run(context, session)

        self.assertIn("process death", result["offline_queue"])
        self.assertIn("distinct clientMessageIds", result["exact_once"])
        self.assertIn("one server row", result["exact_once"])
        self.assertIn("chip", result["offline_reaction"])
        self.assertIn("105 seeded groups", result["large_set"])
        self.assertIn("30 edits + 30 recalls", result["reconcile"])
        self.assertIn("POST /api/v1/sync", result["multi_batch"])
        self.assertIn("reconverged", result["account_switch"])
        self.assertTrue(result["second_chunk_marker"])

        # Server truth after the journey + sweep: run fixtures tombstoned,
        # the 106 seeded groups are all orphaned, and the device ends on An.
        fixture_msgs = [m for msgs in store.messages.values() for m in msgs
                        if str(m["content"]).startswith("sj-")]
        self.assertTrue(fixture_msgs)
        self.assertTrue(all(m["deletedAt"] for m in fixture_msgs))
        self.assertTrue(all(conv["members"] == set()
                            for conv in store.conversations.values()
                            if conv["type"] == "GROUP"))
        self.assertEqual(world.account, "user-an")
        self.assertEqual(world.screen, "inbox")
        # The >100 sync fan-out really happened on the scripted logcat.
        self.assertGreaterEqual(len(world.sync_log), 4)
        # Queued work landed exactly once with two distinct identities.
        ot = [m for m in store.messages["conv-ab"]
              if m["content"].startswith("sj-ot")]
        self.assertEqual(len(ot), 2)
        self.assertEqual(len({m["clientMessageId"] for m in ot}), 2)

    def test_executor_flags_a_single_batch_sync_as_a_defect(self):
        store, world, client, context, session = self._fixture()
        original = world.emit_sync
        emitted = []

        def single_batch():
            if not emitted:
                emitted.append(True)
                world.sync_log.append(
                    "I/okhttp.OkHttpClient: --> POST "
                    "http://10.0.2.2:8080/api/v1/sync (80-byte body)")

        world.emit_sync = single_batch
        # The constructor's cold start already emitted through the real
        # emitter; clear it so exactly one scripted post reaches the check.
        world.sync_log.clear()
        # The post-marker batch window is polled now; pin it to a single
        # read so the one scripted post still counts exactly once.
        original_poll = journeys._OFFLINE_SYNC_POLL_S
        journeys._OFFLINE_SYNC_POLL_S = 0
        try:
            with self.assertRaises(journeys.ProductFailure) as caught:
                self._run(context, session)
            self.assertIn("at least two bounded batches",
                          str(caught.exception))
        finally:
            journeys._OFFLINE_SYNC_POLL_S = original_poll
            world.emit_sync = original

    def test_executor_flags_a_seeded_row_that_never_converges(self):
        store, world, client, context, session = self._fixture()
        # Never let the chunk-2 marker row render: keep the inbox capped.
        original_rows = world.inbox_rows
        world.inbox_rows = lambda: [
            row for row in original_rows()
            if not row[0].startswith("sj-os-")]
        with self.assertRaises(journeys.ProductFailure):
            self._run(context, session)

    def test_registered_dispatch_routes_offline_sync(self):
        sentinel = mock.Mock(return_value={"summary": "ok"})
        with mock.patch.object(journeys, "run_offline_sync", sentinel):
            result = journeys.run_registered_row(
                journeys.ROW_BY_ID["offline-sync"], {"serial": "emulator-5554"})
        self.assertEqual(result, {"summary": "ok"})
        sentinel.assert_called_once()


class ScopedFatalDetectionTest(unittest.TestCase):
    """`logcat -d -v brief` excerpts for the FATAL-scoping decision.

    The app block is the standard AndroidRuntime report; the shell block is
    the DumpCommand crash a slow `uiautomator dump` leaves behind when a
    previous dump still holds the UiAutomationService registration.
    """

    APP_FATAL = """\
E/AndroidRuntime( 5123): FATAL EXCEPTION: main
E/AndroidRuntime( 5123): Process: com.nexachat.debug, PID: 5123
E/AndroidRuntime( 5123): java.lang.IllegalStateException: seeded crash
E/AndroidRuntime( 5123): \tat com.nexachat.feature.inbox.InboxFragment.onStart(InboxFragment.java:88)
E/AndroidRuntime( 5123): \tat android.app.Fragment.performStart(Fragment.java:2637)
E/AndroidRuntime( 5123): \tat android.os.Handler.handleCallback(Handler.java:938)
D/NexaChat( 5123): sync finished
"""

    # Real capture from emulator-5554 (API 36): shell app_process crashes
    # print a bare `PID: <n>` — only named app processes print `Process:`.
    SHELL_FATAL = """\
E/AndroidRuntime( 8871): FATAL EXCEPTION: main
E/AndroidRuntime( 8871): PID: 8871
E/AndroidRuntime( 8871): java.lang.IllegalStateException: UiAutomationService android.accessibilityservice.IAccessibilityServiceClient$Stub$Proxy@9f3ab12 already registered!
E/AndroidRuntime( 8871): \tat android.os.Parcel.createExceptionOrNull(Parcel.java:3381)
E/AndroidRuntime( 8871): \tat android.app.UiAutomation.connect(UiAutomation.java:335)
E/AndroidRuntime( 8871): \tat com.android.uiautomator.core.UiAutomationShellWrapper.connect(UiAutomationShellWrapper.java:36)
"""

    OTHER_PROCESS_FATAL = """\
E/AndroidRuntime( 6102): FATAL EXCEPTION: main
E/AndroidRuntime( 6102): Process: com.android.systemui, PID: 6102
E/AndroidRuntime( 6102): java.lang.RuntimeException: fixture
"""

    UNATTRIBUTED_FATAL = """\
E/AndroidRuntime( 9902): FATAL EXCEPTION: main
E/AndroidRuntime( 9902): java.lang.IllegalStateException: truncated dump
"""

    class _Completed:
        def __init__(self, stdout):
            self.stdout = stdout
            self.returncode = 0

    def _filtered_logcat(self, logcat):
        def fake_run(command, **kwargs):
            return self._Completed(logcat)

        with mock.patch.object(patch_window, "run", fake_run):
            return patch_window.filtered_logcat(set())

    def test_app_process_fatal_is_a_product_defect(self):
        lines = self.APP_FATAL.splitlines()
        self.assertTrue(patch_window.fatal_exception_in_app(lines))

    def test_uiautomator_shell_fatal_is_not_a_product_defect(self):
        self.assertFalse(patch_window.fatal_exception_in_app(self.SHELL_FATAL.splitlines()))

    def test_named_foreign_process_fatal_is_not_a_product_defect(self):
        self.assertFalse(patch_window.fatal_exception_in_app(
            self.OTHER_PROCESS_FATAL.splitlines()))

    def test_unattributable_fatal_fails_safe_to_defect(self):
        # A FATAL the filter cannot prove is foreign may still be the app
        # after a crash/restart raced `pidof`; masking it would silently
        # drop evidence of a real app crash.
        self.assertTrue(patch_window.fatal_exception_in_app(
            self.UNATTRIBUTED_FATAL.splitlines()))

    def test_app_stack_frames_flag_a_fatal_missing_its_process_line(self):
        lines = [
            "E/AndroidRuntime( 9902): FATAL EXCEPTION: main",
            "E/AndroidRuntime( 9902): java.lang.IllegalStateException: boom",
            "E/AndroidRuntime( 9902): \tat com.nexachat.core.sync.SyncWorker.run(SyncWorker.java:41)",
        ]
        self.assertTrue(patch_window.fatal_exception_in_app(lines))

    def test_app_subprocess_fatal_is_a_product_defect(self):
        lines = [
            "E/AndroidRuntime( 5123): FATAL EXCEPTION: main",
            "E/AndroidRuntime( 5123): Process: com.nexachat.debug:work, PID: 5123",
            "E/AndroidRuntime( 5123): java.lang.IllegalStateException: boom",
        ]
        self.assertTrue(patch_window.fatal_exception_in_app(lines))

    def test_filtered_logcat_scopes_the_defect_flag(self):
        lines, defect = self._filtered_logcat(self.SHELL_FATAL)
        self.assertFalse(defect)
        # The shell crash stays in the evidence excerpt; it just cannot
        # flip the verdict.
        self.assertTrue(any("FATAL EXCEPTION" in line for line in lines))

    def test_filtered_logcat_still_flags_an_app_fatal(self):
        lines, defect = self._filtered_logcat(self.APP_FATAL)
        self.assertTrue(defect)


class FatalVerdictAndOrphanDumpTest(unittest.TestCase):
    PS_WITH_ORPHAN = """\
  PID ARGS
    1 init
 8871 uiautomator
 8872 app_process /system/bin com.android.commands.uiautomator.Launcher dump /sdcard/ui.xml
 5123 com.nexachat.debug
"""

    class FakeAdb:
        def __init__(self, ps_output=""):
            self.calls = []
            self.ps_output = ps_output

        def command(self, *args, **kwargs):
            self.calls.append(args)
            if args[:3] == ("shell", "ps", "-A"):
                return self.ps_output
            return ""

    class FakeDevice:
        def screenshot(self, path):
            Path(path).write_bytes(b"evidence")

    def _context(self, root, ps_output="", fatal=False):
        class Patch:
            @staticmethod
            def ui_nodes(device, timeout=8):
                return []

            @staticmethod
            def filtered_logcat(baseline):
                return [], fatal

        return {
            "evidence_root": Path(root),
            "adb": self.FakeAdb(ps_output),
            "device": self.FakeDevice(),
            "helpers": (None, None, Patch()),
            "client": ResultClassificationTest.FakeClient(),
            "stomp_events": [],
            "frame_reader": None,
        }

    @staticmethod
    def _ok(row, context):
        return {"summary": "ok"}

    def test_uiautomator_pids_parses_ps_output(self):
        # Both the renamed `uiautomator` comm and the pre-rename
        # `app_process ... uiautomator.Launcher` holder are caught.
        self.assertEqual(journeys._uiautomator_pids(self.PS_WITH_ORPHAN),
                         ["8871", "8872"])
        self.assertEqual(journeys._uiautomator_pids(""), [])
        self.assertEqual(journeys._uiautomator_pids(
            "u0_a123 5123 320 3300000 90000 0 0 S com.nexachat.debug\n"), [])

    def test_orphaned_uiautomator_dump_is_killed_before_the_row(self):
        with tempfile.TemporaryDirectory() as directory:
            context = self._context(Path(directory) / "evidence",
                                    ps_output=self.PS_WITH_ORPHAN)
            results = journeys.execute_rows(
                [journeys.ROW_BY_ID["auth-login"]], context, self._ok)
            self.assertEqual(results[0]["status"], "PASS")
            calls = context["adb"].calls
            kills = [call for call in calls if call[:3] == ("shell", "kill", "-9")]
            self.assertEqual(kills, [("shell", "kill", "-9", "8871"),
                                     ("shell", "kill", "-9", "8872")])
            ps_index = calls.index(("shell", "ps", "-A", "-o", "PID,ARGS"))
            logcat_index = calls.index(("logcat", "-d", "-v", "brief"))
            self.assertLess(ps_index, logcat_index)

    def test_kill_orphan_uiautomators_kills_only_uiautomator_pids(self):
        calls = []

        def fake_adb(*args, **kwargs):
            calls.append(args)
            if args[:3] == ("shell", "ps", "-A"):
                return self.PS_WITH_ORPHAN
            return ""

        with mock.patch.object(patch_window, "adb", fake_adb):
            killed = patch_window.kill_orphan_uiautomators()
        self.assertEqual(killed, ["8871", "8872"])
        self.assertEqual(
            [call for call in calls if call[:3] == ("shell", "kill", "-9")],
            [("shell", "kill", "-9", "8871"),
             ("shell", "kill", "-9", "8872")])

    def test_scoped_shell_crash_leaves_the_row_verdict_alone(self):
        with tempfile.TemporaryDirectory() as directory:
            context = self._context(Path(directory) / "evidence", fatal=False)
            results = journeys.execute_rows(
                [journeys.ROW_BY_ID["auth-login"]], context, self._ok)
            self.assertEqual(results[0]["status"], "PASS")

    def test_app_fatal_still_flips_the_row_to_fail_product(self):
        with tempfile.TemporaryDirectory() as directory:
            context = self._context(Path(directory) / "evidence", fatal=True)
            results = journeys.execute_rows(
                [journeys.ROW_BY_ID["auth-login"]], context, self._ok)
            self.assertEqual(results[0]["status"], "FAIL_PRODUCT")


class AccessibilitySweepExecutorTest(unittest.TestCase):
    """Offline fake device+oracle world for run_accessibility_sweep.

    The world mirrors the surfaces the sweep drives on emulator-5554: the
    seeded An/Binh fixture, the display-settings snapshot/restore pair, the
    wm density read, and a UI tree the real a11y_oracle accepts. Navigation
    helpers are mocked at the journey boundary (they are pinned by their own
    rows' tests); the oracle itself runs for real.
    """

    def setUp(self) -> None:
        import a11y_oracle
        self.oracle = a11y_oracle
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    class _Node:
        """The adb_ui.Node shape: attribute access plus the raw attrs dict the
        a11y_oracle also accepts."""

        def __init__(self, attrs):
            self.attrs = attrs
            self.text = attrs.get("text", "")
            self.desc = attrs.get("content-desc", "")
            self.cls = attrs.get("class", "")
            self.box = None

    def _nodes(self) -> list["_Node"]:
        # A clean tree: one non-interactive labeled root, so every oracle
        # check passes and the cell is an honest PASS.
        return [self._Node({
            "resource-id": "com.nexachat.debug:id/conversationList",
            "class": "androidx.recyclerview.widget.RecyclerView",
            "text": "Inbox", "content-desc": "", "bounds": "[0,0][1080,2400]",
            "clickable": "false", "focusable": "false",
            "enabled": "true", "scrollable": "true",
        })]

    def _world(self, density: str = "Physical density: 420") -> tuple[dict, list]:
        adb_calls: list[tuple] = []

        class FakeAdb:
            calls = adb_calls

            def command(self, *args, timeout=None, check=True):
                adb_calls.append(tuple(args))
                if tuple(args[:3]) == ("shell", "wm", "density"):
                    return density
                if tuple(args[:4]) == ("shell", "cmd", "uimode", "night"):
                    return "Night mode: no"
                if tuple(args[:4]) == ("shell", "settings", "get", "secure"):
                    return {"enabled_accessibility_services": "null",
                            "accessibility_enabled": "0"}.get(args[4], "")
                if tuple(args[:4]) == ("shell", "settings", "get", "global"):
                    return "1.0" if args[4] == "animator_duration_scale" else ""
                return ""

        class FakeDevice:
            def tap_node(self, node):
                pass

            def key(self, key):
                pass

            def screenshot(self, path):
                Path(path).write_bytes(b"png")

        restores: list[dict] = []

        class FakePatch:
            def settings_snapshot(self):
                return {"font_scale": "1.0", "system_locales": "en-US"}

            def restore_device_settings(self, before):
                restores.append(dict(before))

        class FakeApi:
            USER_A = {"phone": "+84900000001", "name": "An"}
            USER_C = {"phone": "+84900000002", "name": "Binh"}

            def direct_conversation(self, recorder, token, phone):
                return None, {"id": "conv-1"}

        patch = FakePatch()
        context = {
            "serial": "emulator-5554",
            "base_url": "http://127.0.0.1:18093",
            "helpers": (FakeApi(), None, patch),
            "client": mock.Mock(),
            "adb": FakeAdb(),
            "device": FakeDevice(),
            "row_dir": self.root / "attempt-1",
            "ui_timeout": 0.5,
        }
        context["row_dir"].mkdir(parents=True, exist_ok=True)
        return context, restores

    def _run(self, context, **overrides):
        patches = [
            mock.patch.object(journeys, "_prepare_seeded_an"),
            mock.patch.object(journeys, "_actor_session",
                              return_value={"accessToken": "t", "user": {"id": "an-id"}}),
            mock.patch.object(journeys, "_offline_rest_once",
                              return_value=mock.Mock(**{"json.return_value": {"id": "grp-1"}})),
            mock.patch.object(journeys, "_offline_send_rest"),
            mock.patch.object(journeys, "_wait_ui_node", return_value=None),
            mock.patch.object(journeys, "_inbox_conversation_row", return_value=object()),
            mock.patch.object(journeys, "_open_chat_menu"),
            mock.patch.object(journeys, "_open_assign_dialog"),
            mock.patch.object(journeys, "_tap_dialog_button"),
            mock.patch.object(journeys, "_ui_nodes", side_effect=lambda context: self._nodes()),
        ]
        for item in overrides.pop("extra_patches", ()):
            patches.append(item)
        assert not overrides, overrides
        for item in patches:
            item.start()
            self.addCleanup(item.stop)
        row = journeys.ROW_BY_ID["accessibility-sweep"]
        return journeys.run_accessibility_sweep(row, context)

    def test_dispatch_routes_accessibility_sweep(self):
        sentinel = mock.Mock(return_value={"summary": "ok"})
        with mock.patch.object(journeys, "run_accessibility_sweep", sentinel):
            result = journeys.run_registered_row(
                journeys.ROW_BY_ID["accessibility-sweep"], {"serial": "emulator-5554"})
        self.assertEqual(result, {"summary": "ok"})
        sentinel.assert_called_once()

    def test_row_is_runnable_and_inventory_pins_real_rids(self):
        row = journeys.ROW_BY_ID["accessibility-sweep"]
        self.assertIsNone(row.capability_gap)
        self.assertEqual([screen["screen"] for screen in journeys.CRITICAL_SCREENS], [
            "inbox", "direct-chat", "group-chat", "archive",
            "settings", "labels", "contacts",
        ])
        # Every marker rid must exist in the android layouts (or be a framework
        # id the app matches on); the inventory may not invent ids.
        layouts = journeys.ROOT / "android" / "app" / "src" / "main" / "res" / "layout"
        xml = "\n".join(
            path.read_text(encoding="utf-8")
            for path in layouts.glob("*.xml")
        )
        framework_ids = {"select_dialog_listview"}
        for screen in journeys.CRITICAL_SCREENS:
            marker = screen["marker_rid"]
            with self.subTest(screen=screen["screen"]):
                if marker.startswith("text:"):
                    # Programmatic screens (the labels dialog) carry no
                    # resource ids; their marker is localized title text and
                    # every alternate must be a real string resource.
                    strings = "\n".join(
                        path.read_text(encoding="utf-8")
                        for path in (journeys.ROOT / "android" / "app" / "src" /
                                     "main" / "res").glob("values*/strings.xml"))
                    for title in marker[5:].split("|"):
                        self.assertIn(f">{title}<", strings,
                                      f"marker title {title!r} is not a string resource")
                    continue
                self.assertTrue(
                    f"@+id/{marker}" in xml or marker in framework_ids,
                    f"marker rid {marker!r} does not exist in the layouts")

    def test_sweep_runs_oracle_on_every_matrix_cell(self):
        context, restores = self._world()
        calls: list[dict] = []
        real = self.oracle.summarize_findings

        def wrapped(nodes, **kwargs):
            calls.append(dict(kwargs))
            return real(nodes, **kwargs)

        with mock.patch.object(self.oracle, "summarize_findings", wrapped):
            result = self._run(context)
        self.assertEqual(len(calls), 8 * 7)
        self.assertTrue(all(call["density"] == 420.0 for call in calls))
        self.assertTrue(all(call["screen"] == "auto" for call in calls))
        self.assertEqual(len(result["cells"]), 8 * 7)
        self.assertTrue(all(cell["status"] == "PASS" for cell in result["cells"]))
        variants = {(cell["locale"], cell["night"], cell["font_scale"])
                    for cell in result["cells"]}
        self.assertEqual(variants, {
            (variant["locale"], variant["night"], variant["font_scale"])
            for variant in journeys.ACCESSIBILITY_VARIANTS
        })
        screens = {cell["screen"] for cell in result["cells"]}
        self.assertEqual(screens, {screen["screen"] for screen in journeys.CRITICAL_SCREENS})
        # Every cell carries oracle assertions, not just a screenshot.
        for cell in result["cells"]:
            self.assertTrue(cell["checks"])
            self.assertTrue(all(entry["ok"] for entry in cell["checks"].values()))
        self.assertEqual(result["density_dpi"], 420.0)
        self.assertEqual(result["talkback"]["enabled_before"], "0")
        self.assertEqual(result["reduced_motion"]["animator_duration_scale_before"], "1.0")
        self.assertTrue(result["heuristic_caveats"])
        # Display settings were restored exactly once with the snapshot.
        self.assertEqual(restores, [{
            "font_scale": "1.0", "system_locales": "en-US",
            "night_mode": "Night mode: no",
        }])
        # TalkBack and reduced-motion state were restored too (a null services
        # snapshot clears the list, an unreadable flag falls back to disabled).
        self.assertIn(("shell", "settings", "put", "secure",
                       "enabled_accessibility_services", '""'),
                      context["adb"].calls)
        self.assertIn(("shell", "settings", "put", "secure",
                       "accessibility_enabled", "0"),
                      context["adb"].calls)
        self.assertIn(("shell", "settings", "put", "global",
                       "animator_duration_scale", "1.0"),
                      context["adb"].calls)

    def test_sweep_restores_settings_when_a_cell_raises(self):
        context, restores = self._world()
        boom = mock.patch.object(
            journeys, "_a11y_sweep_cell", side_effect=journeys.ProductFailure("boom"))
        boom.start()
        self.addCleanup(boom.stop)
        with self.assertRaisesRegex(journeys.ProductFailure, "boom"):
            self._run(context)
        self.assertEqual(restores, [{
            "font_scale": "1.0", "system_locales": "en-US",
            "night_mode": "Night mode: no",
        }])

    def test_sweep_blocks_when_density_is_unreadable(self):
        context, _restores = self._world(density="")
        with self.assertRaisesRegex(journeys.CapabilityBlocked, "wm density"):
            self._run(context)

    def test_sweep_blocks_when_the_oracle_cannot_run(self):
        context, _restores = self._world()

        def broken(nodes, **kwargs):
            raise self.oracle.A11yOracleError("bad dump")

        with mock.patch.object(self.oracle, "summarize_findings", broken):
            with self.assertRaisesRegex(journeys.CapabilityBlocked, "could not run the a11y oracle"):
                self._run(context)
        cells = json.loads((context["row_dir"] / "a11y-sweep-cells.json").read_text())
        self.assertEqual(len(cells), 8 * 7)
        self.assertTrue(all(cell["status"] == "BLOCKED" for cell in cells))

    def test_sweep_fails_product_when_the_oracle_finds_a_defect(self):
        context, _restores = self._world()
        report = {
            "ok": False, "node_count": 1, "interactive_count": 1,
            "checks": {
                "unlabeled_interactive": {
                    "ok": False, "count": 1,
                    "findings": [{"issue": "unlabeled_interactive", "rid": "sendButton",
                                  "text": "", "content_desc": "", "bounds": "[0,0][10,10]"}],
                },
                "small_touch_targets": {"ok": True, "count": 0, "findings": []},
                "missing_desc_images": {"ok": True, "count": 0, "findings": []},
                "focus_order_issues": {"ok": True, "count": 0, "findings": []},
            },
        }
        with mock.patch.object(self.oracle, "summarize_findings", return_value=report):
            with self.assertRaisesRegex(journeys.ProductFailure, "failed the a11y oracle"):
                self._run(context)
        cells = json.loads((context["row_dir"] / "a11y-sweep-cells.json").read_text())
        self.assertEqual(cells[0]["status"], "FINDINGS")
        self.assertEqual(cells[0]["findings"][0]["rid"], "sendButton")


class MediaUploadSliceExecutorTest(unittest.TestCase):
    """Offline fake device+REST world for run_media_upload_slice.

    The Adb fake implements push/sha256sum/rm against a real byte store, so
    the host->device hash chain is genuine (the fixture bytes written by
    media_driver are hashed back through the fake sha256sum). The Device fake
    walks inbox -> chat -> attach sheet -> system picker exactly like the app
    surfaces, dumpsys reports the resumed component, and the Client fake
    plays the attachment REST surface (multipart upload, attachmentIds message
    send, content/files serving with Range, the owner/member/non-member rule,
    and message tombstones). The row must stay BLOCKED_CAPABILITY even when
    every provable slice passes.
    """

    class Node:
        def __init__(self, text="", desc="", rid="", box=(0, 0, 320, 48),
                     clickable=True, cls="android.widget.TextView"):
            self.text = text
            self.desc = desc
            self.attrs = {"resource-id": ("com.nexachat.debug:id/" + rid) if rid else ""}
            self.box = box
            self.clickable = clickable
            self.cls = cls

        @property
        def center(self):
            left, top, right, bottom = self.box
            return (left + right) // 2, (top + bottom) // 2

    class World:
        def __init__(self):
            self.screen = "inbox"
            self.component = None

        def tree(self):
            N = MediaUploadSliceExecutorTest.Node
            nodes = []
            if self.screen == "inbox":
                nodes.append(N(rid="inboxUserAvatar"))
                nodes.append(N(text="Binh", rid="conversationListItem"))
            elif self.screen == "chat":
                nodes.append(N(rid="attachButton"))
                nodes.append(N(rid="messageInput"))
            elif self.screen == "sheet":
                nodes.append(N(rid="attachButton"))
                nodes.append(N(rid="pickVisualRow"))
                nodes.append(N(rid="pickDocumentRow"))
            # "picker" is system UI: the app exposes no tree there.
            return nodes

    class Device:
        def __init__(self, world):
            self.world = world

        def nodes(self):
            return self.world.tree()

        def tap_node(self, node):
            world = self.world
            rid = str(node.attrs.get("resource-id", "")).rsplit("/", 1)[-1]
            if world.screen == "inbox" and node.text.strip() == "Binh":
                world.screen = "chat"
            elif world.screen == "chat" and rid == "attachButton":
                world.screen = "sheet"
            elif world.screen == "sheet" and rid == "pickVisualRow":
                world.screen = "picker"
                world.component = ("com.google.android.providers.media.module/"
                                   "com.google.android.providers.media.photopicker.activity.PickerActivity")
            elif world.screen == "sheet" and rid == "pickDocumentRow":
                world.screen = "picker"
                world.component = ("com.android.documentsui/"
                                   "com.android.documentsui.picker.PickActivity")

        def key(self, code):
            world = self.world
            if world.screen == "picker":
                world.screen = "chat"
            elif world.screen == "sheet":
                world.screen = "chat"
            elif world.screen == "chat":
                world.screen = "inbox"

        def screenshot(self, path):
            Path(path).write_bytes(b"png")

    class Adb:
        serial = "emulator-5554"

        def __init__(self, world):
            self.world = world
            self.files = {}
            self.commands = []
            self.fail_push = False

        def command(self, *args, timeout=60.0, check=True):
            argv = tuple(str(value) for value in args)
            self.commands.append(argv)
            if argv[0] == "push":
                if self.fail_push:
                    raise RuntimeError("device offline")
                self.files[argv[2]] = Path(argv[1]).read_bytes()
                return "1 file pushed"
            if argv[:2] == ("shell", "sha256sum"):
                data = self.files.get(argv[2])
                if data is None:
                    return f"sha256sum: {argv[2]}: No such file or directory"
                return hashlib.sha256(data).hexdigest() + "  " + argv[2]
            if argv[:3] == ("shell", "dumpsys", "activity"):
                if self.world.screen == "picker":
                    return f"topResumedActivity=ComponentInfo{{{self.world.component}}}"
                return ("topResumedActivity=ComponentInfo{"
                        "com.nexachat.debug/com.nexachat.MainActivity}")
            if argv[:2] == ("shell", "rm"):
                self.files.pop(argv[-1], None)
                return ""
            if argv[:3] == ("shell", "cmd", "statusbar"):
                return ""
            raise AssertionError(f"unexpected adb command {argv}")

    class Store:
        def __init__(self):
            self.attachments = {}
            self.messages = []
            self.deleted = []
            self.mutate_served = None

    class Client:
        base_url = "http://127.0.0.1:18093"
        timeout = 30

        def __init__(self, store):
            self.store = store

        def request(self, method, path, token=None, body=None, params=None,
                    raw_body=None, headers=None):
            if method == "POST" and path == "/api/v1/attachments":
                return self._upload(raw_body)
            if method == "POST" and path.endswith("/messages"):
                return self._send_message(path, token, body)
            if method == "GET":
                return self._get(path, token, headers)
            if method == "DELETE" and "/messages/" in path:
                message_id = path.rsplit("/", 1)[1]
                message = next((m for m in self.store.messages
                                if m["id"] == message_id), None)
                if message is None:
                    return Response(404, {}, b'{"error":{"code":"NOT_FOUND"}}')
                message["deletedAt"] = "2026-01-01T00:00:00Z"
                self.store.deleted.append(message_id)
                return Response(204, {}, b"")
            return Response(404, {}, b'{"error":{"code":"NOT_FOUND"}}')

        def get(self, path, token=None, params=None, headers=None):
            return self.request("GET", path, token=token, headers=headers)

        def post(self, path, token=None, body=None, headers=None):
            return self.request("POST", path, token=token, body=body,
                                headers=headers)

        def delete(self, path, token=None, params=None, headers=None):
            return self.request("DELETE", path, token=token, params=params,
                                headers=headers)

        def _upload(self, raw_body):
            data, content_type = raw_body
            boundary = re.search(r"boundary=(\S+)", content_type).group(1)
            payload = b""
            mime = None
            filename = None
            for part in data.split((f"--{boundary}").encode()):
                if b'name="file"' not in part:
                    continue
                filename = re.search(rb'filename="([^"]+)"', part).group(1).decode()
                mime = re.search(rb"Content-Type: ([^\r]+)", part).group(1).decode()
                payload = part.split(b"\r\n\r\n", 1)[1]
                if payload.endswith(b"\r\n"):
                    payload = payload[:-2]
            attachment_id = str(uuid.uuid4())
            self.store.attachments[attachment_id] = {
                "id": attachment_id,
                "url": f"/api/v1/files/attachments/{attachment_id}",
                "mimeType": mime, "sizeBytes": len(payload),
                "owner": token if False else None,
                "members": {"tok-an", "tok-binh"}, "data": payload,
            }
            attachment = self.store.attachments[attachment_id]
            return Response(201, {}, json.dumps({
                "id": attachment_id,
                "url": attachment["url"],
                "originalName": filename,
                "mimeType": mime,
                "sizeBytes": len(payload),
            }).encode())

        def _send_message(self, path, token, body):
            attachment_id = str((body or {}).get("attachmentIds")[0])
            attachment = self.store.attachments.get(attachment_id)
            if attachment is None:
                return Response(404, {}, b'{"error":{"code":"ATTACHMENT_NOT_FOUND"}}')
            attachment["owner"] = token
            message = {"id": f"m-{len(self.store.messages) + 1}",
                       "conversationId": path.split("/")[4],
                       "type": (body or {}).get("type"),
                       "attachmentIds": [attachment_id],
                       "deletedAt": None}
            self.store.messages.append(message)
            return Response(201, {}, json.dumps(message).encode())

        def _serve(self, attachment, headers):
            data = attachment["data"]
            if self.store.mutate_served is not None:
                data = self.store.mutate_served(data)
            range_header = (headers or {}).get("Range")
            if range_header is not None:
                start, end = (int(value) for value in
                              range_header[len("bytes="):].split("-"))
                end = min(end, len(data) - 1)
                return Response(206, {
                    "Content-Range": f"bytes {start}-{end}/{len(data)}",
                    "Content-Type": attachment["mimeType"],
                }, data[start:end + 1])
            return Response(200, {"Content-Type": attachment["mimeType"]}, data)

        def _refused(self):
            return Response(403, {}, b'{"error":{"code":"NOT_MEMBER"}}')

        def _get(self, path, token, headers):
            if path.startswith("/api/v1/attachments/") and path.endswith("/content"):
                attachment_id = path[len("/api/v1/attachments/"):-len("/content")]
                attachment = self.store.attachments.get(attachment_id)
                if attachment is None:
                    return Response(404, {}, b'{"error":{"code":"ATTACHMENT_NOT_FOUND"}}')
                if token != attachment["owner"] and token not in attachment["members"]:
                    return self._refused()
                return self._serve(attachment, headers)
            if path.startswith("/api/v1/files/"):
                key = path[len("/api/v1/files/"):]
                attachment = next((a for a in self.store.attachments.values()
                                  if a["url"].endswith("/" + key)), None)
                if attachment is None:
                    return Response(404, {}, b'{"error":{"code":"ATTACHMENT_NOT_FOUND"}}')
                if token != attachment["owner"] and token not in attachment["members"]:
                    return self._refused()
                return self._serve(attachment, headers)
            return Response(404, {}, b'{"error":{"code":"NOT_FOUND"}}')

    class Patch:
        @staticmethod
        def ui_nodes(device, timeout=8):
            return device.nodes()

        @staticmethod
        def named_conversation_row(nodes, display_name):
            return next((n for n in nodes
                         if n.text.strip() == display_name or n.desc.strip() == display_name),
                        None)

    @staticmethod
    def _session(api, recorder, phone):
        if phone.endswith("0001"):
            return {"accessToken": "tok-an", "user": {"id": "user-an", "phone": phone}}
        if phone.endswith("0002"):
            return {"accessToken": "tok-binh", "user": {"id": "user-binh", "phone": phone}}
        return {"accessToken": "tok-chi", "user": {"id": "user-chi", "phone": phone}}

    def _fixture(self):
        world = self.World()
        adb = self.Adb(world)
        client = self.Client(self.Store())

        class Api:
            USER_A = {"phone": "+84900000001"}
            USER_B = {"phone": "+84900000003"}
            USER_C = {"phone": "+84900000002"}

            @staticmethod
            def direct_conversation(client_obj, token, phone):
                return 200, {"id": "conv-an-binh", "e2eeEnabled": False}

        context = {
            "serial": "emulator-5554",
            "base_url": "http://127.0.0.1:18093",
            "row": journeys.ROW_BY_ID["media"],
            "row_dir": Path(tempfile.mkdtemp()),
            "adb": adb,
            "device": self.Device(world),
            "helpers": (Api(), None, self.Patch()),
            "client": client,
            "ui_timeout": 1,
        }
        return context, world, adb, client

    def test_media_slice_proves_upload_bytes_and_stays_blocked(self):
        context, world, adb, client = self._fixture()
        row = journeys.ROW_BY_ID["media"]
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session",
                                  side_effect=self._session):
            with self.assertRaises(journeys.CapabilityBlocked) as caught:
                journeys.run_media_upload_slice(row, context)
        message = str(caught.exception)
        self.assertIn("upload slice proven", message)
        self.assertIn("system picker", message)
        self.assertIn("voice recording/playback", message)
        self.assertEqual(journeys._status_for_error(caught.exception),
                         "BLOCKED_CAPABILITY")

        evidence = json.loads(
            (context["row_dir"] / "media-slice.json").read_text(encoding="utf-8"))
        # 1. push: both fixtures landed on the device hash-verified.
        self.assertEqual(len(evidence["pushed"]), 2)
        remotes = {item["remote_path"] for item in evidence["pushed"]}
        self.assertEqual(len(remotes), 2)
        for item in evidence["pushed"]:
            self.assertEqual(item["verified_by"], "sha256sum")
            self.assertRegex(item["sha256"], r"^[0-9a-f]{64}$")
        # 2. attach: the app's sheet delegated to both system pickers.
        attach = evidence["attach"]
        self.assertEqual(attach["attach_sheet"], "opened")
        self.assertEqual({d["sheet_row"] for d in attach["delegations"]},
                         {"pickVisualRow", "pickDocumentRow"})
        media_driver = journeys._import_media_driver()
        for delegation in attach["delegations"]:
            self.assertFalse(delegation["pick_completed"])
            self.assertTrue(media_driver.is_system_picker_component(
                delegation["system_picker"]))
        self.assertEqual(len(attach["unproven"]), 2)
        # 3. bytes: both uploads round-tripped with 206 + 403 evidence.
        verified = evidence["verified"]
        self.assertEqual(len(verified), 2)
        self.assertEqual({item["msg_type"] for item in verified}, {"FILE", "IMAGE"})
        for item in verified:
            self.assertTrue(item["ok"])
            self.assertTrue(item["owner_sha256_match"])
            self.assertTrue(item["member_sha256_match"])
            self.assertEqual(len(item["ranges"]), 2)
            self.assertEqual(item["non_member_refused"]["content"],
                             {"status": 403, "refused_with": "NOT_MEMBER"})
            self.assertEqual(item["non_member_refused"]["files"]["status"], 403)
        # The server stored exactly the fixture bytes that were pushed.
        pdf = media_driver.build_fixture("pdf")
        for attachment in client.store.attachments.values():
            digest = hashlib.sha256(attachment["data"]).hexdigest()
            self.assertIn(digest, {pdf.sha256,
                                   media_driver.build_fixture(
                                       "png", width=32, height=24).sha256})
        # Cleanup swept the seeded messages and the device fixtures.
        self.assertEqual(len(client.store.deleted), 2)
        self.assertTrue(all(m["deletedAt"] for m in client.store.messages))
        rm_remotes = {argv[3] for argv in adb.commands
                      if argv[:2] == ("shell", "rm") and len(argv) > 3}
        self.assertEqual(rm_remotes, remotes)
        self.assertEqual(adb.files, {})
        self.assertEqual(world.screen, "inbox")
        for name in ("attach-visual.png", "attach-document.png",
                     "system-picker-visual.png", "system-picker-document.png"):
            self.assertTrue((context["row_dir"] / name).is_file(), name)

    def test_served_byte_corruption_is_a_product_failure(self):
        context, _world, _adb, client = self._fixture()
        client.store.mutate_served = lambda data: data + b"x"
        row = journeys.ROW_BY_ID["media"]
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session",
                                  side_effect=self._session):
            with self.assertRaises(journeys.ProductFailure) as caught:
                journeys.run_media_upload_slice(row, context)
        self.assertIn("media byte oracle failed", str(caught.exception))
        self.assertEqual(journeys._status_for_error(caught.exception),
                         "FAIL_PRODUCT")
        evidence = json.loads(
            (context["row_dir"] / "media-slice.json").read_text(encoding="utf-8"))
        self.assertEqual(len(evidence["pushed"]), 2)
        self.assertEqual(evidence["verified"], [])
        # The failure hit the first REST verify, so the attach slice ran and
        # recorded both delegations before the oracle stopped it.
        self.assertEqual(len(evidence["attach"]["delegations"]), 2)

    def test_push_failure_is_environment_failure(self):
        context, _world, adb, _client = self._fixture()
        adb.fail_push = True
        row = journeys.ROW_BY_ID["media"]
        with mock.patch.object(journeys, "_assert_device_account_an"), \
                mock.patch.object(journeys, "_actor_session",
                                  side_effect=self._session):
            with self.assertRaises(journeys.EnvironmentFailure) as caught:
                journeys.run_media_upload_slice(row, context)
        self.assertIn("media fixture push failed", str(caught.exception))
        self.assertEqual(journeys._status_for_error(caught.exception),
                         "FAIL_HARNESS_OR_ENVIRONMENT")
        evidence = json.loads(
            (context["row_dir"] / "media-slice.json").read_text(encoding="utf-8"))
        self.assertEqual(evidence["pushed"], [])
        self.assertIsNone(evidence["attach"])
        self.assertEqual(len(evidence["unproven"]), 2)

    def test_media_row_dispatches_to_the_slice_executor(self):
        self.assertEqual(journeys._ROW_EXECUTOR_NAMES["media"],
                         "run_media_upload_slice")
        row = journeys.ROW_BY_ID["media"]
        calls = []

        def fake_executor(row_arg, context_arg):
            calls.append((row_arg.id, context_arg["row"].id))
            return {"proved": True}

        with mock.patch.object(journeys, "run_media_upload_slice", fake_executor):
            result = journeys.run_registered_row(
                row, {"row": row, "row_dir": Path(tempfile.mkdtemp())})
        self.assertEqual(calls, [("media", "media")])
        self.assertEqual(result, {"proved": True})
        # The registered executor owns the gate, but the campaign disposition
        # stays BLOCKED_CAPABILITY until the residual gaps are closed.
        self.assertTrue(row.capability_gap)
        self.assertIn("BLOCKED_CAPABILITY:", journeys.render_list((row,)))


if __name__ == "__main__":
    unittest.main()
