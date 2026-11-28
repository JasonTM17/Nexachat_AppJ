"""Offline unit tests for tools/e2e/device/social_emulator_e2e.py.

Runs completely offline (no emulator, no Docker, no Gradle): tests command
builders, crash/ExoPlayer logcat parsing, 64dp toolbar & 48dp a11y checks,
state-sync card verification, HTTP Range 206 oracle, fixture builders, and
runner cleanup guarantees.
"""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import a11y_oracle  # noqa: E402
from e2e_common import Response  # noqa: E402
import social_emulator_e2e as see  # noqa: E402


def _node(
    *,
    rid: str = "",
    cls: str = "android.view.View",
    text: str = "",
    desc: str = "",
    bounds: str = "[0,0][100,48]",
    clickable: str = "false",
    focusable: str = "false",
    enabled: str = "true",
    scrollable: str = "false",
) -> dict[str, str]:
    return {
        "resource-id": f"com.nexachat.debug:id/{rid}" if rid else "",
        "class": cls,
        "text": text,
        "content-desc": desc,
        "bounds": bounds,
        "clickable": clickable,
        "focusable": focusable,
        "enabled": enabled,
        "scrollable": scrollable,
        "checked": "false",
        "focused": "false",
    }


class CommandBuilderTest(unittest.TestCase):
    def test_gradle_cmd_includes_api_base_url(self):
        cmd = see.build_gradle_assemble_cmd(Path("/repo"), "http://127.0.0.1:8080")
        self.assertIn("assembleDebug", cmd)
        self.assertIn("-PapiBaseUrl=http://127.0.0.1:8080", cmd)
        self.assertTrue(cmd[0].endswith("gradlew.bat" if os.name == "nt" else "gradlew"))

    def test_adb_reverse_and_remove_cmds(self):
        self.assertEqual(
            see.build_adb_reverse_cmd("emulator-5554", 8080, 18432),
            ["adb", "-s", "emulator-5554", "reverse", "tcp:8080", "tcp:18432"],
        )
        self.assertEqual(
            see.build_adb_reverse_remove_cmd("emulator-5554", 8080),
            ["adb", "-s", "emulator-5554", "reverse", "--remove", "tcp:8080"],
        )

    def test_uimode_and_media_scan_cmds(self):
        self.assertEqual(
            see.build_uimode_night_cmd("emulator-5554", True),
            ["adb", "-s", "emulator-5554", "shell", "cmd", "uimode", "night", "yes"],
        )
        self.assertEqual(
            see.build_uimode_night_cmd("emulator-5554", False),
            ["adb", "-s", "emulator-5554", "shell", "cmd", "uimode", "night", "no"],
        )
        scan = see.build_media_scan_cmd("emulator-5554")
        self.assertIn("scan_volume", scan)
        self.assertIn("external_primary", scan)


class LogcatOracleTest(unittest.TestCase):
    def test_ignores_foreign_and_uiautomator_crashes(self):
        log = (
            "E AndroidRuntime: FATAL EXCEPTION: main\n"
            "E AndroidRuntime: Process: com.android.systemui, PID: 1234\n"
            "E AndroidRuntime: java.lang.NullPointerException\n"
            "E AndroidRuntime: FATAL EXCEPTION: main\n"
            "E AndroidRuntime: PID: 5555\n"
        )
        self.assertEqual(see.parse_crash_logcat(log), [])

    def test_detects_nexachat_fatal_and_anr(self):
        log = (
            "E AndroidRuntime: FATAL EXCEPTION: main\n"
            "E AndroidRuntime: Process: com.nexachat.debug, PID: 9999\n"
            "E AndroidRuntime: java.lang.IllegalStateException: boom\n"
        )
        crashes = see.parse_crash_logcat(log)
        self.assertEqual(len(crashes), 1)
        self.assertIn("com.nexachat.debug", crashes[0])

    def test_detects_exoplayer_errors(self):
        clean = "I ExoPlayerImpl: Init 1.4.1\n"
        self.assertEqual(see.parse_exoplayer_errors(clean), [])
        bad = "E ExoPlayerImplInternal: Playback error\n"
        self.assertEqual(len(see.parse_exoplayer_errors(bad)), 1)


class DesignAndA11yOracleTest(unittest.TestCase):
    def test_toolbar_height_64dp_passes_and_56dp_fails(self):
        nodes_64 = [_node(rid="feedToolbar", bounds="[0,0][320,64]")]
        dp = see.verify_toolbar_height_dp(nodes_64, "feedToolbar", density_dpi=160.0)
        self.assertAlmostEqual(dp, 64.0)

        nodes_56 = [_node(rid="feedToolbar", bounds="[0,0][320,56]")]
        with self.assertRaises(see.ProductFailure):
            see.verify_toolbar_height_dp(nodes_56, "feedToolbar", density_dpi=160.0)

    def test_check_feed_a11y_rejects_sub_48dp_target(self):
        bad_nodes = [
            _node(cls="android.widget.FrameLayout", bounds="[0,0][320,640]"),
            _node(
                rid="postReact",
                cls="android.widget.Button",
                text="React",
                bounds="[10,100][50,136]",  # 40x36dp < 48dp
                clickable="true",
                focusable="true",
            ),
        ]
        with self.assertRaises(see.ProductFailure):
            see.check_feed_a11y(bad_nodes, density_dpi=160.0, layout_dir=None)

    def test_check_feed_a11y_passes_valid_48dp_nodes(self):
        good_nodes = [
            _node(cls="android.widget.FrameLayout", bounds="[0,0][320,640]"),
            _node(
                rid="postReact",
                cls="android.widget.Button",
                text="React",
                desc="React to post",
                bounds="[10,100][90,148]",  # 80x48dp
                clickable="true",
                focusable="true",
            ),
        ]
        res = see.check_feed_a11y(good_nodes, density_dpi=160.0, layout_dir=None)
        self.assertEqual(res["small_touch_targets"], [])


class StateSyncAndRangeTest(unittest.TestCase):
    def test_verify_state_sync_card_en_and_vi(self):
        nodes = [
            _node(rid="postBody", text="Updated post body"),
            _node(rid="postTime", text="Just now"),
            _node(rid="postEdited", text="(đã chỉnh sửa)"),
            _node(rid="postReactions", desc="👍 2"),
            _node(rid="postComments", text="No comments"),
        ]
        res = see.verify_state_sync_card(
            nodes, expected_body="Updated post body", expected_reactions=2, expected_comments=0
        )
        self.assertTrue(res["body_synced"])
        self.assertTrue(res["edited_badge_synced"])

        with self.assertRaises(see.ProductFailure):
            see.verify_state_sync_card(nodes, expected_body="Missing body")

    def test_check_http_range_206_passes_and_rejects_200(self):
        payload = b"0123456789" * 10

        class FakeClient:
            def __init__(self, status: int, body: bytes, headers: dict[str, str]):
                self._status = status
                self._body = body
                self._headers = headers

            def get(self, path, token=None, headers=None):
                return Response(self._status, self._headers, self._body)

        ok_client = FakeClient(
            206,
            payload[:64],
            {"Accept-Ranges": "bytes", "Content-Range": f"bytes 0-63/{len(payload)}"},
        )
        res = see.check_http_range_206(ok_client, "tok", "vid-1", payload)
        self.assertEqual(res["status"], 206)
        self.assertEqual(res["bytes_verified"], 64)

        bad_client = FakeClient(200, payload, {})
        with self.assertRaises(see.ProductFailure):
            see.check_http_range_206(bad_client, "tok", "vid-1", payload)


class FixtureBuilderTest(unittest.TestCase):
    def test_jpeg_and_mp4_fixtures(self):
        repo_root = Path(__file__).resolve().parents[3]
        with tempfile.TemporaryDirectory() as tmp:
            jpg = see.ensure_valid_jpeg_fixture(Path(tmp) / "test.jpg")
            self.assertTrue(jpg.read_bytes().startswith(b"\xff\xd8\xff"))
            mp4 = see.ensure_valid_mp4_fixture(repo_root, Path(tmp) / "test.mp4")
            self.assertIn(b"ftyp", mp4.read_bytes()[:32])


class RunnerCleanupTest(unittest.TestCase):
    def test_cleanup_removes_reverse_and_stops_stack_on_failure(self):
        commands: list[list[str]] = []

        class FakeProc:
            def __init__(self, stdout: str = "", returncode: int = 0):
                self.stdout = stdout
                self.stderr = ""
                self.returncode = returncode

        def fake_cmd_runner(argv, *, timeout=120.0, check=True):
            argv_list = list(argv)
            commands.append(argv_list)
            if argv_list[:3] == ["adb", "devices", "-l"]:
                return FakeProc("emulator-5554 device product:emu64xa\n")
            if "sys.boot_completed" in argv_list:
                return FakeProc("1\n")
            if "density" in argv_list:
                return FakeProc("Physical density: 160\n")
            if "install" in argv_list:
                raise see.HarnessFailure("simulated install failure")
            return FakeProc("")

        class FakeStack:
            def __init__(self):
                self.stopped = False
                self.dropped = False

            def provision(self, **kwargs):
                port = int(kwargs.get("app_port") or 18700)
                return {"app_port": port, "base_url": f"http://127.0.0.1:{port}", "db_name": "nexachat_e2e_test"}

            def start(self, **_kwargs):
                return {}

            def seed(self, users, **_kwargs):
                return [
                    {"phone": u["phone"], "name": u["name"], "user_id": f"uid-{i}", "access_token": f"tok-{i}"}
                    for i, u in enumerate(users)
                ]

            def stop(self):
                self.stopped = True

            def dropdb(self):
                self.dropped = True

        fake_stack = FakeStack()

        class FakeClient:
            def get(self, path, token=None, body=None, headers=None, params=None):
                return Response(200, {}, b'{"revision":1,"webEnabled":false,"videoUploadsEnabled":false}')

            def post(self, path, token=None, body=None, headers=None):
                return Response(200, {}, b'{"id":"conv-1","type":"DIRECT"}')

            def patch(self, path, token=None, body=None, headers=None):
                return Response(200, {}, b'{"revision":2,"webEnabled":true,"videoUploadsEnabled":true}')

        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "app-debug.apk"
            apk.write_bytes(b"apk")
            cfg = see.RunConfig(
                repo_root=Path(__file__).resolve().parents[3],
                screenshots_dir=Path(tmp) / "screens",
                run_dir=Path(tmp) / "run",
                apk_path=apk,
                build_apk=False,
            )
            runner = see.SocialEmulatorRunner(
                cfg,
                cmd_runner=fake_cmd_runner,
                stack_factory=lambda *_a: fake_stack,
                client_factory=lambda _u: FakeClient(),
                sleep=lambda _s: None,
            )
            with self.assertRaises(see.HarnessFailure):
                runner.run_all()

        self.assertTrue(fake_stack.stopped)
        self.assertTrue(fake_stack.dropped)
        rev_cmd = next(c for c in commands if c[:4] == ["adb", "-s", "emulator-5554", "reverse"] and c[4] == "tcp:8080")
        assigned_port = int(rev_cmd[5].split(":")[1])
        self.assertGreaterEqual(assigned_port, see.EMULATOR_STACK_PORT_RANGE[0])
        self.assertLessEqual(assigned_port, see.EMULATOR_STACK_PORT_RANGE[1])
        self.assertIn(["adb", "-s", "emulator-5554", "reverse", "--remove", "tcp:8080"], commands)

    def test_pick_from_api36_photopicker_compose_nodes(self):
        import adb_ui
        import xml.etree.ElementTree as ET

        xml = (
            '<hierarchy><node package="com.google.android.photopicker" bounds="[0,0][320,640]">'
            '<node package="com.google.android.photopicker" clickable="true" long-clickable="true" bounds="[0,422][106,528]">'
            '<node package="com.google.android.photopicker" content-desc="Photo taken on Nov 28, 2026 1:42 PM" clickable="false" bounds="[0,422][106,528]" />'
            '</node></node></hierarchy>'
        )
        root = ET.fromstring(xml)
        picker_nodes = [adb_ui.Node(el) for el in root.iter("node")]
        app_nodes = [adb_ui.Node(ET.fromstring('<node package="com.nexachat.debug" bounds="[0,0][320,640]" />'))]

        class FakeUi:
            def __init__(self):
                self.tapped = []
                self.calls = 0

            def nodes(self):
                self.calls += 1
                return picker_nodes if self.calls == 1 else app_nodes

            def tap_node(self, node):
                self.tapped.append(node.center)

        runner = see.SocialEmulatorRunner(see.RunConfig(build_apk=False), sleep=lambda _s: None)
        ui = FakeUi()
        runner._pick_from_system_picker(ui, "image")
        self.assertEqual(ui.tapped, [(53, 475)])

    def test_by_text_clickable_only_skips_dialog_title(self):
        import adb_ui
        import xml.etree.ElementTree as ET

        xml = (
            '<hierarchy>'
            '<node text="Delete this comment?" clickable="false" bounds="[20,200][300,240]" />'
            '<node text="Delete" clickable="true" bounds="[200,320][280,368]" />'
            '</hierarchy>'
        )
        nodes = [adb_ui.Node(el) for el in ET.fromstring(xml).iter("node")]
        picked = see.SocialEmulatorRunner._by_text(
            nodes, see.DELETE_CONFIRM_LABELS, clickable_only=True
        )
        self.assertIsNotNone(picked)
        self.assertEqual(picked.text, "Delete")
        self.assertTrue(picked.clickable)

    def test_type_into_field_retries_on_truncated_ime_input(self):
        import adb_ui
        import xml.etree.ElementTree as ET

        def make_node(text, hint="Write a post"):
            el = ET.fromstring(
                f'<node resource-id="com.nexachat.debug:id/composeInput" '
                f'text="{text}" hint="{hint}" clickable="true" bounds="[16,104][304,219]" />'
            )
            return [adb_ui.Node(el)]

        class FakeUi:
            def __init__(self):
                self.typed = []
                self.deleted = []
                self.current = "Write a post"

            def nodes(self):
                return make_node(self.current)

            def tap_node(self, _n):
                pass

            def move_end(self):
                pass

            def delete_chars(self, count):
                self.deleted.append(count)
                self.current = ""

            def type_text(self, value):
                self.typed.append(value)
                # First attempt truncates; second attempt succeeds
                self.current = value[:-4] if len(self.typed) == 1 else value

        runner = see.SocialEmulatorRunner(see.RunConfig(build_apk=False), sleep=lambda _s: None)
        ui = FakeUi()
        runner._type_into_field(ui, "composeInput", "E2E text post abcdef")
        self.assertEqual(len(ui.typed), 2)
        self.assertEqual(ui.current, "E2E text post abcdef")
        self.assertTrue(len(ui.deleted) >= 1)


class ReleaseAndLiveModeTest(unittest.TestCase):
    def test_cli_release_flags_and_apk_path_alias(self):
        args = see.build_parser().parse_args(
            [
                "--screenshots-dir",
                "s",
                "--package",
                "com.nexachat",
                "--api-base-url",
                "https://api.nexachat.io.vn",
                "--apk-path",
                "rel.apk",
                "--verify-app-link",
                "--i-know-this-is-not-prod",
            ]
        )
        self.assertEqual(args.package, "com.nexachat")
        self.assertEqual(args.api_base_url, "https://api.nexachat.io.vn")
        self.assertEqual(args.apk, "rel.apk")
        self.assertTrue(args.verify_app_link)
        self.assertTrue(args.i_know_this_is_not_prod)
        self.assertFalse(see.is_loopback_url(args.api_base_url))
        self.assertTrue(see.is_loopback_url("http://127.0.0.1:8080"))
        self.assertTrue(see.is_loopback_url("http://localhost:8080"))

    def test_launch_app_uses_configured_package(self):
        adb_calls: list[list[str]] = []

        class FakeProc:
            stdout = ""
            stderr = ""
            returncode = 0

        def fake_cmd(argv, **_kwargs):
            adb_calls.append(list(argv))
            return FakeProc()

        cfg = see.RunConfig(package="com.nexachat", build_apk=False)
        runner = see.SocialEmulatorRunner(cfg, cmd_runner=fake_cmd, sleep=lambda _s: None)
        runner._launch_app(clear_data=True)

        self.assertIn(["adb", "-s", "emulator-5554", "shell", "pm", "clear", "com.nexachat"], adb_calls)
        self.assertIn(
            ["adb", "-s", "emulator-5554", "shell", "pm", "grant", "com.nexachat", "android.permission.POST_NOTIFICATIONS"],
            adb_calls,
        )
        self.assertIn(
            ["adb", "-s", "emulator-5554", "shell", "am", "start", "-n", "com.nexachat/com.nexachat.MainActivity"],
            adb_calls,
        )
        for call in adb_calls:
            self.assertNotIn("com.nexachat.debug", call)

    def test_parse_crash_logcat_detects_release_anr_and_fatal(self):
        log = (
            "E AndroidRuntime: FATAL EXCEPTION: main\n"
            "E AndroidRuntime: Process: com.nexachat.debug, PID: 1001\n"
            "E AndroidRuntime: java.lang.RuntimeException: debug only\n"
            "E ActivityManager: ANR in com.nexachat.debug (com.nexachat.debug/.MainActivity)\n"
            "E ActivityManager: Reason: Input dispatching timed out\n"
            "E AndroidRuntime: FATAL EXCEPTION: main\n"
            "E AndroidRuntime: Process: com.nexachat, PID: 2002\n"
            "E AndroidRuntime: java.lang.IllegalStateException: release crash\n"
            "E ActivityManager: ANR in com.nexachat (com.nexachat/.MainActivity)\n"
            "E ActivityManager: Reason: Executing service com.nexachat/.SyncService\n"
        )
        findings = see.parse_crash_logcat(log, "com.nexachat")
        self.assertEqual(len(findings), 2)
        self.assertIn("Process: com.nexachat, PID: 2002", findings[0])
        self.assertIn("ANR in com.nexachat", findings[1])
        for finding in findings:
            self.assertNotIn("com.nexachat.debug", finding)

    def test_app_link_cmd_and_ui_oracle(self):
        import adb_ui
        import xml.etree.ElementTree as ET

        url = "https://nexachat.io.vn/join/ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef0123456789_"
        cmd = see.build_app_link_view_cmd("emulator-5554", url, "com.nexachat")
        self.assertEqual(
            cmd,
            [
                "adb",
                "-s",
                "emulator-5554",
                "shell",
                "am",
                "start",
                "-W",
                "-a",
                "android.intent.action.VIEW",
                "-d",
                url,
                "-p",
                "com.nexachat",
            ],
        )

        ok_xml = (
            '<hierarchy>'
            '<node package="com.nexachat" resource-id="com.nexachat:id/toolbar" bounds="[0,0][320,64]" />'
            '<node package="com.nexachat" resource-id="com.nexachat:id/statusText" text="Invite valid" bounds="[16,100][304,140]" />'
            '<node package="com.nexachat" resource-id="com.nexachat:id/signInButton" text="Sign in" bounds="[16,200][304,248]" />'
            '</hierarchy>'
        )
        ok_nodes = [adb_ui.Node(el) for el in ET.fromstring(ok_xml).iter("node")]
        res = see.verify_app_link_ui(ok_nodes, "com.nexachat")
        self.assertEqual(res["package"], "com.nexachat")
        self.assertIn("statusText", res["matched_ids"])
        self.assertIn("signInButton", res["matched_ids"])

        chrome_xml = (
            '<hierarchy>'
            '<node package="com.android.chrome" resource-id="com.android.chrome:id/url_bar" text="nexachat.io.vn" bounds="[0,0][320,64]" />'
            '</hierarchy>'
        )
        chrome_nodes = [adb_ui.Node(el) for el in ET.fromstring(chrome_xml).iter("node")]
        with self.assertRaises(see.ProductFailure):
            see.verify_app_link_ui(chrome_nodes, "com.nexachat")

    def test_live_mode_safety_guard_and_no_disposable_stack(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "app-release.apk"
            apk.write_bytes(b"apk")

            # Guard: non-loopback without --i-know-this-is-not-prod fails immediately
            stack_called = False
            cmd_called = False

            def fail_stack(*_a, **_kw):
                nonlocal stack_called
                stack_called = True
                raise AssertionError("DisposableStack must never be called in live mode")

            def fail_cmd(*_a, **_kw):
                nonlocal cmd_called
                cmd_called = True
                raise AssertionError("adb must not run when safety guard blocks")

            guarded_cfg = see.RunConfig(
                package="com.nexachat",
                api_base_url="https://api.nexachat.io.vn",
                apk_path=apk,
                build_apk=False,
                i_know_this_is_not_prod=False,
            )
            guarded_runner = see.SocialEmulatorRunner(
                guarded_cfg, cmd_runner=fail_cmd, stack_factory=fail_stack, sleep=lambda _s: None
            )
            with self.assertRaises(see.HarnessFailure):
                guarded_runner.run_all()
            self.assertFalse(stack_called)
            self.assertFalse(cmd_called)

            # With --i-know-this-is-not-prod: no DisposableStack, no adb reverse, no admin calls, DELETE /users/me in finally
            commands: list[list[str]] = []
            api_calls: list[tuple[str, str, str | None]] = []

            class FakeProc:
                def __init__(self, stdout: str = ""):
                    self.stdout = stdout
                    self.stderr = ""
                    self.returncode = 0

            def live_cmd_runner(argv, **_kwargs):
                argv_list = list(argv)
                commands.append(argv_list)
                if argv_list[:3] == ["adb", "devices", "-l"]:
                    return FakeProc("emulator-5554 device product:emu64xa\n")
                if "sys.boot_completed" in argv_list:
                    return FakeProc("1\n")
                if "density" in argv_list:
                    return FakeProc("Physical density: 160\n")
                if "install" in argv_list:
                    raise see.HarnessFailure("simulated install failure after registration")
                return FakeProc("")

            class FakeClient:
                def request(self, method, path, token=None, body=None, params=None, headers=None):
                    api_calls.append((method, path, token))
                    return Response(204, {}, b"")

                def post(self, path, token=None, body=None, headers=None):
                    api_calls.append(("POST", path, token))
                    if path == "/api/v1/auth/register":
                        self.assert_phone = (body or {}).get("phone", "")
                        return Response(201, {}, b'{"accessToken":"eph-tok-1","user":{"id":"u-eph"}}')
                    return Response(404, {}, b"{}")

                def get(self, path, token=None, params=None, headers=None):
                    api_calls.append(("GET", path, token))
                    return Response(200, {}, b"{}")

            live_cfg = see.RunConfig(
                package="com.nexachat",
                api_base_url="https://api.nexachat.io.vn",
                apk_path=apk,
                build_apk=False,
                screenshots_dir=Path(tmp) / "screens",
                i_know_this_is_not_prod=True,
            )
            live_runner = see.SocialEmulatorRunner(
                live_cfg,
                cmd_runner=live_cmd_runner,
                stack_factory=fail_stack,
                client_factory=lambda _u: FakeClient(),
                sleep=lambda _s: None,
            )
            with self.assertRaises(see.HarnessFailure):
                live_runner.run_all()

            self.assertFalse(stack_called)
            self.assertFalse(any("reverse" in c for c in commands))
            self.assertFalse(any("/api/v1/admin" in path for _, path, _ in api_calls))
            self.assertIn(("DELETE", "/api/v1/users/me", "eph-tok-1"), api_calls)


if __name__ == "__main__":
    unittest.main()
