"""Offline safety and result-classification tests for stable_journeys."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
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
            "archive-preferences", "groups-contacts", "labels-lifecycle",
            "polls-reminders",
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
            "groups-contacts", "polls-reminders", "archive-preferences",
            "labels-lifecycle",
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
        self.assertNotIn("archive-preferences", blocked)
        self.assertNotIn("groups-contacts", blocked)
        self.assertNotIn("labels-lifecycle", blocked)
        self.assertNotIn("polls-reminders", blocked)
        for row_id in (
            "media",
            "calls-signaling",
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
            journeys.run_archive_preferences,
            journeys.run_labels_lifecycle,
            journeys.run_groups_contacts,
            journeys.run_polls_reminders,
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


if __name__ == "__main__":
    unittest.main()
