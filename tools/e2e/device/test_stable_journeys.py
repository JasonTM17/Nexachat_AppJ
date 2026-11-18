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
            "auth-login", "chat-send-receive", "chat-interactions", "summary-ask",
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
            "auth-login", "chat-send-receive", "chat-interactions",
            "calls-injected-notification", "summary-ask", "injected-notifications",
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
        self.assertNotIn("summary-ask", blocked)
        for row_id in (
            "media", "groups-contacts", "polls-reminders",
            "archive-preferences", "labels-lifecycle", "calls-signaling",
            "offline-sync", "accessibility-sweep",
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
            journeys.run_chat_interactions,
            journeys.run_summary_ask,
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
                return ChatInteractionsExecutorTest.Response(200, [
                    {"id": self.direct_id, "type": "DIRECT"},
                    {"id": self.group_id, "type": "GROUP", "title": "NexaChat Team"},
                ])
            if "/messages" in path:
                cid = path.split("/")[4]
                return ChatInteractionsExecutorTest.Response(
                    200, {"items": [dict(m) for m in self.store.messages.get(cid, [])]})
            return ChatInteractionsExecutorTest.Response(404)

        def post(self, path, token=None, body=None, headers=None):
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


if __name__ == "__main__":
    unittest.main()
