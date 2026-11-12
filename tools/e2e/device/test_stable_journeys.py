"""Offline safety and result-classification tests for stable_journeys."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import stable_journeys as journeys


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
            "calls-injected-notification", "injected-notifications",
        })
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
            "auth-login", "chat-send-receive",
            "calls-injected-notification", "injected-notifications",
        ])
        self.assertEqual(len(runnable) + len(blocked), len(journeys.ROWS))
        self.assertIn("STOMP", blocked["auth-session-lifecycle"])
        self.assertIn("501", blocked["auth-session-lifecycle"])
        self.assertIn("expired", blocked["otp-forgot-password"])
        self.assertIn("server_env()", blocked["otp-forgot-password"])
        self.assertIn("Room 14", blocked["room-13-14-upgrade"])
        self.assertIn("Room 14", blocked["v111-rc-upgrade"])
        self.assertIn("seed user", blocked["account-deletion"])
        self.assertNotIn("calls-injected-notification", blocked)
        self.assertNotIn("injected-notifications", blocked)
        for row_id in (
            "chat-interactions", "media", "groups-contacts", "polls-reminders",
            "archive-preferences", "labels-lifecycle", "calls-signaling",
            "summary-ask", "offline-sync", "accessibility-sweep",
        ):
            self.assertIn("BLOCKED_CAPABILITY:", blocked[row_id])

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

        for row_id in ("otp-forgot-password", "account-deletion", "auth-session-lifecycle",
                       "room-13-14-upgrade", "v111-rc-upgrade"):
            with self.subTest(row_id=row_id):
                with self.assertRaises(journeys.CapabilityBlocked):
                    journeys.run_registered_row(journeys.ROW_BY_ID[row_id], {"mail_sink": Boom()})

    def test_executors_refuse_a_non_reserved_serial(self):
        cases = (
            journeys.run_calls_injected_notification,
            journeys.run_injected_notifications,
            journeys.run_otp_forgot_password,
            journeys.run_account_deletion,
            journeys.run_auth_session_lifecycle,
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

    def test_message_executor_uses_inject_push_for_both_kinds(self):
        row = journeys.ROW_BY_ID["injected-notifications"]
        adb_calls = []

        class Adb:
            serial = "emulator-5554"

            def command(self, *args, **kwargs):
                adb_calls.append(args)
                if "notification" in args:
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
        self.assertFalse(any("broadcast" in args for args in adb_calls))

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

    def test_barrier_hold_still_leaves_stomp_blocked(self):
        row = journeys.ROW_BY_ID["auth-session-lifecycle"]

        class Barrier:
            def __init__(self):
                self.held = None
                self.closed = False

            def hold(self, **kwargs):
                self.held = kwargs

            def close(self):
                self.closed = True

        barrier = Barrier()
        with self.assertRaises(journeys.CapabilityBlocked) as caught:
            journeys.run_auth_session_lifecycle(row, {
                "serial": "emulator-5554",
                "base_url": "http://127.0.0.1:18093",
                "row": row,
                "account_a_token": "token-a",
                "barrier_factory": lambda url: barrier,
            })
        self.assertEqual(barrier.held["path"], r"^/api/v1/conversations$")
        self.assertTrue(barrier.closed)
        self.assertIn("STOMP", str(caught.exception))
        self.assertIn("501", str(caught.exception))

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


if __name__ == "__main__":
    unittest.main()
