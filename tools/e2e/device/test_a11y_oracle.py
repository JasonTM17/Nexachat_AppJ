"""World-model tests for a11y_oracle: command builders, parser, oracles, CLI.

Everything runs without a device: oracles are pure functions over synthetic
node dicts and a realistic ``uiautomator dump`` XML fixture; command builders
are asserted as strings only. Compatibility with the real ``adb_ui.Node``
shape is proven by wrapping the same fixture elements in that class.
"""

from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import a11y_oracle as oracle
import adb_ui


def N(**attrs) -> dict[str, str]:
    """A synthetic node dict with the attribute keys a real dump carries."""
    base = {
        "text": "", "resource-id": "", "class": "", "package": "com.nexachat.debug",
        "content-desc": "", "checkable": "false", "checked": "false",
        "clickable": "false", "enabled": "true", "focusable": "false",
        "focused": "false", "scrollable": "false", "long-clickable": "false",
        "password": "false", "selected": "false", "bounds": "[0,0][0,0]",
    }
    base.update(attrs)
    return base


# A realistic uiautomator dump shape (login-ish screen) with planted defects:
# phoneInput (focusable, no own label), passwordToggle (unlabeled ImageButton,
# 28x28px, no desc -> three oracles), brandLogo (unnamed ImageView, NAF),
# hiddenOverlay (focusable, zero bounds), floatingDebug (focusable, entirely
# off-screen, no scrollable reach), rowChi (below the fold but inside the
# scrollable list -> must NOT be flagged).
FIXTURE_XML = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.nexachat.debug" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1080,2400]">
    <node index="0" text="NexaChat" resource-id="com.nexachat.debug:id/authTitle" class="android.widget.TextView" package="com.nexachat.debug" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[60,140][1020,260]" />
    <node index="1" text="" resource-id="com.nexachat.debug:id/errorBanner" class="android.widget.TextView" package="com.nexachat.debug" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[60,300][1020,360]" />
    <node index="2" text="Continue with Google" resource-id="com.nexachat.debug:id/googleSignIn" class="android.widget.Button" package="com.nexachat.debug" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[60,400][1020,520]" />
    <node index="3" text="" resource-id="com.nexachat.debug:id/phoneInput" class="android.widget.EditText" package="com.nexachat.debug" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="true" focused="true" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[60,560][1020,680]" />
    <node index="4" text="" resource-id="com.nexachat.debug:id/passwordToggle" class="android.widget.ImageButton" package="com.nexachat.debug" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[1000,720][1028,748]" />
    <node index="5" text="••••" resource-id="com.nexachat.debug:id/passwordInput" class="android.widget.EditText" package="com.nexachat.debug" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="true" selected="false" bounds="[60,720][980,840]" />
    <node index="6" text="" resource-id="com.nexachat.debug:id/brandLogo" class="android.widget.ImageView" package="com.nexachat.debug" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" NAF="true" bounds="[420,900][660,1140]" />
    <node index="7" text="" resource-id="com.nexachat.debug:id/contactThumb" class="androidx.appcompat.widget.AppCompatImageView" package="com.nexachat.debug" content-desc="Contact photo of Binh" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[420,1180][560,1320]" />
    <node index="8" text="" resource-id="com.nexachat.debug:id/inboxMore" class="android.widget.ImageButton" package="com.nexachat.debug" content-desc="More options" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[940,1180][1030,1270]" />
    <node index="9" text="" resource-id="com.nexachat.debug:id/conversationList" class="androidx.recyclerview.widget.RecyclerView" package="com.nexachat.debug" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="true" long-clickable="false" password="false" selected="false" bounds="[0,1300][1080,2400]" />
    <node index="10" text="Binh" resource-id="com.nexachat.debug:id/rowBinh" class="android.widget.LinearLayout" package="com.nexachat.debug" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,1300][1080,1472]" />
    <node index="11" text="Chi" resource-id="com.nexachat.debug:id/rowChi" class="android.widget.LinearLayout" package="com.nexachat.debug" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,2400][1080,2572]" />
    <node index="12" text="" resource-id="com.nexachat.debug:id/hiddenOverlay" class="android.widget.TextView" package="com.nexachat.debug" content-desc="Loading" checkable="false" checked="false" clickable="false" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][0,0]" />
    <node index="13" text="" resource-id="com.nexachat.debug:id/floatingDebug" class="android.widget.Button" package="com.nexachat.debug" content-desc="Debug overlay" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[1200,2500][1300,2600]" />
  </node>
</hierarchy>"""

CLEAN_XML = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.nexachat.debug" content-desc="" clickable="false" focusable="false" scrollable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node index="0" text="Sign in" resource-id="com.nexachat.debug:id/primaryAction" class="android.widget.Button" package="com.nexachat.debug" content-desc="" clickable="true" focusable="true" enabled="true" bounds="[60,400][1020,520]" />
    <node index="1" text="" resource-id="com.nexachat.debug:id/brandMark" class="android.widget.ImageView" package="com.nexachat.debug" content-desc="NexaChat logo" clickable="false" focusable="false" enabled="true" bounds="[420,900][660,1140]" />
  </node>
</hierarchy>"""

FIXTURE_NODES = oracle.parse_ui_xml(FIXTURE_XML)


class CommandBuilderTest(unittest.TestCase):
    SERIAL = "emulator-5558"

    def test_enable_talkback_flips_both_secure_settings(self):
        commands = oracle.enable_talkback(self.SERIAL)
        self.assertEqual(commands, (
            f"adb -s {self.SERIAL} shell settings put secure "
            "enabled_accessibility_services "
            "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService",
            f"adb -s {self.SERIAL} shell settings put secure accessibility_enabled 1",
        ))

    def test_enable_talkback_accepts_a_service_override(self):
        service = "com.google.android.apps.accessibility.talkback/com.google.android.apps.accessibility.talkback.TalkBackService"
        commands = oracle.enable_talkback(self.SERIAL, service)
        self.assertTrue(commands[0].endswith("enabled_accessibility_services " + service))
        self.assertIn(service, oracle.TALKBACK_SERVICE_CANDIDATES)

    def test_enable_talkback_rejects_malformed_service(self):
        with self.assertRaises(oracle.A11yOracleError):
            oracle.enable_talkback(self.SERIAL, "no-slash service")

    def test_disable_talkback_clears_services_and_flag(self):
        self.assertEqual(oracle.disable_talkback(self.SERIAL), (
            f'adb -s {self.SERIAL} shell settings put secure '
            'enabled_accessibility_services ""',
            f"adb -s {self.SERIAL} shell settings put secure accessibility_enabled 0",
        ))

    def test_font_scale_set_get_restore(self):
        self.assertEqual(oracle.set_font_scale(self.SERIAL, 2.0),
                         (f"adb -s {self.SERIAL} shell settings put system font_scale 2",))
        self.assertEqual(oracle.set_font_scale(self.SERIAL, "1.3"),
                         (f"adb -s {self.SERIAL} shell settings put system font_scale 1.3",))
        self.assertEqual(oracle.get_font_scale(self.SERIAL),
                         (f"adb -s {self.SERIAL} shell settings get system font_scale",))
        self.assertEqual(oracle.restore_font_scale(self.SERIAL, "1.0"),
                         (f"adb -s {self.SERIAL} shell settings put system font_scale 1",))

    def test_font_scale_validation(self):
        for bad in ("abc", 0, -1, None):
            with self.subTest(bad=bad):
                with self.assertRaises(oracle.A11yOracleError):
                    oracle.set_font_scale(self.SERIAL, bad)

    def test_restore_font_scale_skips_unset_snapshots(self):
        for missing in (None, "", "null"):
            with self.subTest(missing=missing):
                self.assertEqual(oracle.restore_font_scale(self.SERIAL, missing), ())

    def test_locale_commands_use_the_settings_and_cmd_patterns(self):
        self.assertEqual(
            oracle.set_locale_hint(self.SERIAL, "vi-VN"),
            (f"adb -s {self.SERIAL} shell settings put system system_locales vi-VN",))
        self.assertEqual(
            oracle.set_locale(self.SERIAL, "vi-VN"),
            (f"adb -s {self.SERIAL} shell cmd locale set vi-VN",))
        self.assertEqual(
            oracle.get_locale(self.SERIAL),
            (f"adb -s {self.SERIAL} shell settings get system system_locales",))
        self.assertEqual(
            oracle.restore_locale(self.SERIAL, "en-US"),
            (f"adb -s {self.SERIAL} shell cmd locale set en-US",))

    def test_locale_validation(self):
        for bad in ("", "en US", "en-US;rm-rf", None, 7):
            with self.subTest(bad=bad):
                with self.assertRaises(oracle.A11yOracleError):
                    oracle.set_locale(self.SERIAL, bad)

    def test_restore_locale_skips_unset_snapshots(self):
        for missing in (None, "", "null"):
            with self.subTest(missing=missing):
                self.assertEqual(oracle.restore_locale(self.SERIAL, missing), ())

    def test_restore_talkback_puts_back_snapshot_verbatim(self):
        self.assertEqual(oracle.restore_talkback(self.SERIAL, "pkg/svc", "1"), (
            f"adb -s {self.SERIAL} shell settings put secure "
            "enabled_accessibility_services pkg/svc",
            f"adb -s {self.SERIAL} shell settings put secure accessibility_enabled 1",
        ))

    def test_restore_talkback_clears_a_null_snapshot_and_defaults_to_off(self):
        self.assertEqual(oracle.restore_talkback(self.SERIAL, None, None), (
            f'adb -s {self.SERIAL} shell settings put secure '
            'enabled_accessibility_services ""',
            f"adb -s {self.SERIAL} shell settings put secure accessibility_enabled 0",
        ))
        # An unreadable accessibility_enabled never leaves TalkBack forced on.
        commands = oracle.restore_talkback(self.SERIAL, "pkg/svc", "garbage")
        self.assertTrue(commands[1].endswith("accessibility_enabled 0"))

    def test_talkback_snapshot_queries(self):
        self.assertEqual(
            oracle.get_enabled_accessibility_services(self.SERIAL),
            (f"adb -s {self.SERIAL} shell settings get secure "
             "enabled_accessibility_services",))
        self.assertEqual(
            oracle.get_accessibility_enabled(self.SERIAL),
            (f"adb -s {self.SERIAL} shell settings get secure accessibility_enabled",))

    def test_serial_is_validated_and_embedded_in_every_command(self):
        for bad in ("", "emu lator", None):
            with self.subTest(bad=bad):
                with self.assertRaises(oracle.A11yOracleError):
                    oracle.set_font_scale(bad, 1.0)

    def test_builders_only_ever_return_command_strings(self):
        builders = (
            lambda: oracle.enable_talkback(self.SERIAL),
            lambda: oracle.disable_talkback(self.SERIAL),
            lambda: oracle.get_enabled_accessibility_services(self.SERIAL),
            lambda: oracle.get_accessibility_enabled(self.SERIAL),
            lambda: oracle.restore_talkback(self.SERIAL, None, None),
            lambda: oracle.set_font_scale(self.SERIAL, 2.0),
            lambda: oracle.get_font_scale(self.SERIAL),
            lambda: oracle.restore_font_scale(self.SERIAL, "1.0"),
            lambda: oracle.set_locale_hint(self.SERIAL, "vi-VN"),
            lambda: oracle.set_locale(self.SERIAL, "vi-VN"),
            lambda: oracle.get_locale(self.SERIAL),
            lambda: oracle.restore_locale(self.SERIAL, "en-US"),
        )
        for build in builders:
            for command in build():
                self.assertIsInstance(command, str)
                self.assertTrue(command.startswith(f"adb -s {self.SERIAL} shell "))


class NodeAccessTest(unittest.TestCase):
    def test_dict_and_attr_object_shapes_agree(self):
        as_dict = N(text="Binh", bounds="[10,20][110,120]", clickable="true")
        as_attrs = type("NodeLike", (), {"attrs": dict(as_dict)})()
        self.assertEqual(oracle.node_text(as_dict), oracle.node_text(as_attrs))
        self.assertEqual(oracle.node_box(as_dict), (10, 20, 110, 120))
        self.assertEqual(oracle.node_box(as_attrs), (10, 20, 110, 120))
        self.assertTrue(oracle.is_interactive(as_dict))
        self.assertEqual(oracle.rid_suffix(
            N(**{"resource-id": "com.nexachat.debug:id/sendButton"})), "sendButton")
        self.assertEqual(oracle.rid_suffix(N(**{"resource-id": ""})), "")

    def test_unrecognized_node_raises(self):
        with self.assertRaises(oracle.A11yOracleError):
            oracle.node_text("not-a-node")

    def test_box_helpers(self):
        self.assertEqual(oracle.box_size((0, 0, 1080, 2400)), (1080, 2400))
        self.assertTrue(oracle.box_contains((0, 0, 100, 100), (10, 10, 90, 90)))
        self.assertFalse(oracle.box_contains((0, 0, 100, 100), (10, 10, 190, 190)))
        self.assertTrue(oracle.boxes_overlap((0, 0, 100, 100), (50, 50, 150, 150)))
        self.assertFalse(oracle.boxes_overlap((0, 0, 100, 100), (100, 0, 200, 100)))
        self.assertIsNone(oracle.node_box(N(bounds="[bad]")))

    def test_px_from_dp_takes_a_wm_density_dpi(self):
        self.assertEqual(oracle.px_from_dp(48, 420), 126.0)
        self.assertEqual(oracle.px_from_dp(48, 160), 48.0)
        for bad in ((0, 420), (48, 0), (48, -1)):
            with self.subTest(bad=bad):
                with self.assertRaises(oracle.A11yOracleError):
                    oracle.px_from_dp(*bad)

    def test_a_scale_multiplier_is_rejected_not_mis_scaled(self):
        with self.assertRaisesRegex(oracle.A11yOracleError, "wm density"):
            oracle.px_from_dp(48, 2.625)

    def test_screen_resolution(self):
        nodes = FIXTURE_NODES
        self.assertEqual(oracle.infer_screen(nodes), (1080, 2400))
        self.assertEqual(oracle.focus_order_issues(nodes, screen="auto")[0]
                         ["issue"], "focus_no_bounds")
        for bad in ("bogus", (0, 100), (100,), 42):
            with self.subTest(bad=bad):
                with self.assertRaises(oracle.A11yOracleError):
                    oracle.focus_order_issues(nodes, screen=bad)


class ParseUiXmlTest(unittest.TestCase):
    def test_fixture_parses_into_adb_ui_shaped_dicts(self):
        nodes = FIXTURE_NODES
        self.assertEqual(len(nodes), 15)
        # Document order, root first -- exactly what adb_ui.Device.nodes() does.
        self.assertEqual(nodes[0]["class"], "android.widget.FrameLayout")
        self.assertEqual(nodes[0]["bounds"], "[0,0][1080,2400]")
        # The attribute names a real dump carries (adb_ui.Node.attrs keys).
        for key in ("text", "content-desc", "resource-id", "class", "package",
                    "clickable", "enabled", "focusable", "checked", "bounds"):
            self.assertIn(key, nodes[4])
        self.assertEqual(nodes[3]["text"], "Continue with Google")
        self.assertEqual(nodes[4]["text"], "")
        self.assertEqual(nodes[7]["NAF"], "true")

    def test_bytes_input_is_accepted(self):
        self.assertEqual(len(oracle.parse_ui_xml(FIXTURE_XML.encode("utf-8"))), 15)

    def test_garbage_xml_raises(self):
        with self.assertRaises(oracle.A11yOracleError):
            oracle.parse_ui_xml("this is not xml")

    def test_nodeless_dump_raises(self):
        with self.assertRaises(oracle.A11yOracleError):
            oracle.parse_ui_xml("<hierarchy rotation='0'></hierarchy>")


class UnlabeledInteractiveTest(unittest.TestCase):
    def test_flags_unlabeled_clickable_and_focusable_nodes(self):
        nodes = [
            N(clickable="true", bounds="[0,0][100,100]"),
            N(focusable="true", bounds="[0,100][100,200]"),
            N(text="Binh", clickable="true", bounds="[0,200][100,300]"),
            N(**{"content-desc": "More options", "clickable": "true",
                 "bounds": "[0,300][100,400]"}),
        ]
        findings = oracle.unlabeled_interactive(nodes)
        self.assertEqual(len(findings), 2)
        self.assertEqual([f["issue"] for f in findings],
                         ["unlabeled_interactive", "unlabeled_interactive"])
        self.assertTrue(all(f["descendant_labels"] == [] for f in findings))

    def test_disabled_and_noninteractive_nodes_are_not_flagged(self):
        nodes = [
            N(clickable="true", enabled="false", bounds="[0,0][100,100]"),
            N(bounds="[0,100][100,200]"),
        ]
        self.assertEqual(oracle.unlabeled_interactive(nodes), [])

    def test_descendant_labels_are_captured_for_a_merged_container(self):
        row = N(clickable="true", **{"class": "android.widget.LinearLayout",
                                     "resource-id": "com.nexachat.debug:id/row"},
                bounds="[0,100][540,300]")
        inside = N(text="Binh", bounds="[10,120][200,180]")
        elsewhere = N(text="Elsewhere", bounds="[600,120][700,180]")
        findings = oracle.unlabeled_interactive([row, inside, elsewhere])
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0]["descendant_labels"], ["Binh"])

    def test_password_dots_are_a_label(self):
        nodes = [N(focusable="true", text="••••", password="true",
                   bounds="[0,0][400,120]")]
        self.assertEqual(oracle.unlabeled_interactive(nodes), [])

    def test_fixture_findings(self):
        findings = oracle.unlabeled_interactive(FIXTURE_NODES)
        self.assertEqual([f["rid"] for f in findings],
                         ["phoneInput", "passwordToggle"])
        self.assertEqual(findings[0]["focusable"], True)
        self.assertEqual(findings[1]["clickable"], True)
        self.assertEqual(findings[1]["descendant_labels"], [])


class SmallTouchTargetsTest(unittest.TestCase):
    def test_floor_converts_dp_to_px_from_the_dpi(self):
        # 48dp at 420dpi (wm density) is 126px: 126 is fine, 125 is a finding.
        nodes = [
            N(clickable="true", bounds="[0,0][126,126]"),
            N(clickable="true", bounds="[0,200][125,326]"),
        ]
        findings = oracle.small_touch_targets(nodes, min_dp=48, density=420)
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0]["floor_px"], 126.0)
        self.assertEqual(findings[0]["width_px"], 125)
        self.assertEqual(findings[0]["below_floor"], ["width"])

    def test_one_short_dimension_is_still_a_finding(self):
        nodes = [N(clickable="true", bounds="[0,0][400,40]")]  # floor 48 at 160dpi
        findings = oracle.small_touch_targets(nodes)
        self.assertEqual(findings[0]["below_floor"], ["height"])

    def test_skips_noninteractive_unmeasurable_and_zero_area_nodes(self):
        nodes = [
            N(clickable="false", bounds="[0,0][20,20]"),          # not interactive
            N(clickable="true", bounds=""),                        # unparsable bounds
            N(clickable="true", bounds="[0,0][0,0]"),             # zero area
            N(clickable="true", enabled="false", bounds="[0,0][20,20]"),
        ]
        self.assertEqual(oracle.small_touch_targets(nodes), [])

    def test_invalid_floor_raises(self):
        nodes = [N(clickable="true", bounds="[0,0][20,20]")]
        for min_dp, density in ((0, 420), (48, 0), (48, -1), (48, 2.625)):
            with self.subTest((min_dp, density)):
                with self.assertRaises(oracle.A11yOracleError):
                    oracle.small_touch_targets(nodes, min_dp=min_dp, density=density)

    def test_fixture_flags_only_the_28px_toggle(self):
        findings = oracle.small_touch_targets(FIXTURE_NODES)
        self.assertEqual([f["rid"] for f in findings], ["passwordToggle"])
        self.assertEqual(findings[0]["below_floor"], ["width", "height"])
        self.assertEqual(findings[0]["width_px"], 28)
        self.assertEqual(findings[0]["height_px"], 28)


class MissingDescImagesTest(unittest.TestCase):
    def test_image_classes_without_names_are_flagged(self):
        nodes = [
            N(**{"class": "android.widget.ImageView"}, bounds="[0,0][48,48]"),
            N(**{"class": "android.widget.ImageButton"}, bounds="[0,100][48,148]"),
            N(**{"class": "androidx.appcompat.widget.AppCompatImageView"},
              bounds="[0,200][48,248]"),
            N(**{"class": "com.google.android.material.imageview.ShapeableImageView"},
              bounds="[0,300][48,348]"),
        ]
        self.assertEqual(len(oracle.missing_desc_images(nodes)), 4)

    def test_named_images_and_non_image_classes_pass(self):
        nodes = [
            N(**{"class": "android.widget.ImageView",
                 "content-desc": "NexaChat logo"}, bounds="[0,0][48,48]"),
            N(**{"class": "android.widget.ImageButton", "text": "Send"},
              bounds="[0,100][48,148]"),
            N(**{"class": "android.widget.TextView"}, bounds="[0,200][48,248]"),
            N(**{"class": "android.widget.Button", "text": "Sign in"},
              bounds="[0,300][48,348]"),
        ]
        self.assertEqual(oracle.missing_desc_images(nodes), [])

    def test_fixture_flags_the_unnamed_image_and_image_button(self):
        findings = oracle.missing_desc_images(FIXTURE_NODES)
        self.assertEqual(sorted(f["rid"] for f in findings),
                         ["brandLogo", "passwordToggle"])
        brand_logo = next(f for f in findings if f["rid"] == "brandLogo")
        self.assertIs(brand_logo.get("naf"), True)


class FocusOrderTest(unittest.TestCase):
    SCREEN = (1080, 2400)

    def test_focusable_without_measurable_bounds_is_flagged(self):
        nodes = [
            N(focusable="true", bounds="[0,0][0,0]"),
            N(focusable="true", bounds=""),
            N(clickable="true", bounds="[0,0][0,0]"),  # clickable, not focusable
        ]
        findings = oracle.focus_order_issues(nodes, screen=self.SCREEN)
        self.assertEqual([f["issue"] for f in findings],
                         ["focus_no_bounds", "focus_no_bounds"])

    def test_off_screen_without_scrollable_reach_is_flagged(self):
        nodes = [N(focusable="true", bounds="[1200,2500][1300,2600]")]
        findings = oracle.focus_order_issues(nodes, screen=self.SCREEN)
        self.assertEqual(findings[0]["issue"], "focus_off_screen")
        self.assertEqual(findings[0]["screen"], [1080, 2400])

    def test_below_fold_child_of_a_scrollable_is_reachable(self):
        nodes = [
            N(**{"resource-id": "com.nexachat.debug:id/list",
                 "class": "androidx.recyclerview.widget.RecyclerView"},
               scrollable="true", bounds="[0,1300][1080,2400]"),
            N(focusable="true", bounds="[0,2400][1080,2572]"),  # past the fold
        ]
        self.assertEqual(oracle.focus_order_issues(nodes, screen=self.SCREEN), [])

    def test_without_a_screen_the_offscreen_check_is_skipped(self):
        nodes = [N(focusable="true", bounds="[1200,2500][1300,2600]")]
        self.assertEqual(oracle.focus_order_issues(nodes, screen=None), [])

    def test_expected_order_catches_missing_unfocusable_and_duplicate(self):
        nodes = [
            N(**{"resource-id": "com.nexachat.debug:id/a"}, focusable="true",
               bounds="[0,0][100,100]"),
            N(**{"resource-id": "com.nexachat.debug:id/b"},
               bounds="[0,100][100,200]"),  # present but not focusable
            N(**{"resource-id": "com.nexachat.debug:id/c"}, focusable="true",
               bounds="[0,200][100,300]"),
            N(**{"resource-id": "com.nexachat.debug:id/c"}, focusable="true",
               bounds="[0,300][100,400]"),
        ]
        findings = oracle.focus_order_issues(
            nodes, expected_order=("a", "b", "c", "missing"))
        issues = {f["issue"] for f in findings}
        self.assertEqual(issues,
                         {"expected_focus_not_focusable", "duplicate_focus_target",
                          "expected_focus_missing"})
        duplicate = next(f for f in findings if f["issue"] == "duplicate_focus_target")
        self.assertEqual(duplicate["rid"], "c")
        self.assertEqual(duplicate["count"], 2)
        not_focusable = next(
            f for f in findings if f["issue"] == "expected_focus_not_focusable")
        self.assertEqual(not_focusable["expected_rid"], "b")

    def test_a_fully_good_order_produces_no_expected_findings(self):
        nodes = [
            N(**{"resource-id": "com.nexachat.debug:id/authTitle"}, focusable="true",
               bounds="[0,0][1080,120]"),
            N(**{"resource-id": "com.nexachat.debug:id/primaryAction"}, focusable="true",
               bounds="[0,2000][1080,2120]"),
        ]
        self.assertEqual(
            oracle.focus_order_issues(nodes, screen=self.SCREEN,
                                      expected_order=("authTitle", "primaryAction")),
            [])

    def test_expected_order_entries_must_be_rid_suffixes(self):
        with self.assertRaises(oracle.A11yOracleError):
            oracle.focus_order_issues(FIXTURE_NODES, expected_order=("",))

    def test_fixture_flags_only_the_unplaceable_and_unreachable_nodes(self):
        findings = oracle.focus_order_issues(FIXTURE_NODES, screen=self.SCREEN)
        self.assertEqual([(f["issue"], f["rid"]) for f in findings],
                         [("focus_no_bounds", "hiddenOverlay"),
                          ("focus_off_screen", "floatingDebug")])


class AdbUiCompatTest(unittest.TestCase):
    """The oracles must accept the real driver's Node objects unchanged."""

    def test_real_adb_ui_nodes_produce_the_same_findings_as_dicts(self):
        root = ET.fromstring(FIXTURE_XML)
        real = [adb_ui.Node(element) for element in root.iter("node")]
        self.assertEqual(len(real), len(FIXTURE_NODES))
        self.assertEqual([f["rid"] for f in oracle.unlabeled_interactive(real)],
                         ["phoneInput", "passwordToggle"])
        self.assertEqual([f["rid"] for f in oracle.small_touch_targets(real)],
                         ["passwordToggle"])
        self.assertEqual(sorted(f["rid"] for f in oracle.missing_desc_images(real)),
                         ["brandLogo", "passwordToggle"])
        self.assertEqual([(f["issue"], f["rid"]) for f in oracle.focus_order_issues(
            real, screen="auto")],
            [("focus_no_bounds", "hiddenOverlay"),
             ("focus_off_screen", "floatingDebug")])

    def test_report_is_identical_across_both_node_shapes(self):
        root = ET.fromstring(FIXTURE_XML)
        real = [adb_ui.Node(element) for element in root.iter("node")]
        self.assertEqual(
            oracle.summarize_findings(real, screen="auto")["checks"],
            oracle.summarize_findings(FIXTURE_NODES, screen="auto")["checks"],
        )


class SummarizeReportTest(unittest.TestCase):
    def test_clean_tree_is_ok_and_json_serializable(self):
        nodes = oracle.parse_ui_xml(CLEAN_XML)
        report = oracle.summarize_findings(nodes, screen="auto")
        self.assertTrue(report["ok"])
        self.assertEqual(report["node_count"], 3)
        self.assertEqual(report["interactive_count"], 1)
        for entry in report["checks"].values():
            self.assertEqual(entry["count"], 0)
            self.assertTrue(entry["ok"])
        # The report must survive an evidence-capture round trip.
        self.assertEqual(json.loads(json.dumps(report)), report)
        self.assertTrue(report["heuristic_caveats"])

    def test_fixture_report_counts_every_planted_defect(self):
        report = oracle.summarize_findings(FIXTURE_NODES, screen="auto")
        self.assertFalse(report["ok"])
        self.assertEqual(report["node_count"], 15)
        self.assertEqual(report["interactive_count"], 10)
        checks = report["checks"]
        self.assertEqual(checks["unlabeled_interactive"]["count"], 2)
        self.assertEqual(checks["small_touch_targets"]["count"], 1)
        self.assertEqual(checks["small_touch_targets"]["floor_px"], 48.0)
        self.assertEqual(checks["missing_desc_images"]["count"], 2)
        self.assertEqual(checks["focus_order_issues"]["count"], 2)
        self.assertEqual(checks["focus_order_issues"]["screen"], [1080, 2400])

    def test_expected_order_lifts_the_focus_count(self):
        report = oracle.summarize_findings(
            FIXTURE_NODES, screen="auto",
            expected_order=("authTitle", "googleSignIn", "phoneInput",
                            "passwordInput", "sendButton"))
        focus = report["checks"]["focus_order_issues"]
        self.assertEqual(focus["count"], 3)
        self.assertIn("sendButton", [f.get("rid") for f in focus["findings"]])


class CliTest(unittest.TestCase):
    def _write(self, xml: str) -> Path:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        path = Path(tmp.name) / "ui.xml"
        path.write_text(xml, encoding="utf-8")
        return path

    def _run(self, argv: list[str]) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        with mock.patch("sys.stdout", out), mock.patch("sys.stderr", err):
            code = oracle.main(argv)
        return code, out.getvalue(), err.getvalue()

    def test_parse_prints_the_node_dicts(self):
        code, out, _ = self._run(["parse", "--xml", str(self._write(FIXTURE_XML))])
        self.assertEqual(code, 0)
        nodes = json.loads(out)
        self.assertEqual(len(nodes), 15)
        self.assertEqual(nodes[0]["class"], "android.widget.FrameLayout")

    def test_sweep_on_a_clean_dump_exits_zero(self):
        code, out, _ = self._run(["sweep", "--xml", str(self._write(CLEAN_XML))])
        self.assertEqual(code, 0)
        self.assertTrue(json.loads(out)["ok"])

    def test_sweep_on_the_fixture_exits_one_with_the_json_report(self):
        code, out, _ = self._run(["sweep", "--xml", str(self._write(FIXTURE_XML))])
        self.assertEqual(code, 1)
        report = json.loads(out)
        self.assertFalse(report["ok"])
        self.assertEqual(report["checks"]["small_touch_targets"]["count"], 1)

    def test_sweep_explicit_screen_matches_auto(self):
        auto_code, auto_out, _ = self._run(
            ["sweep", "--xml", str(self._write(FIXTURE_XML)), "--screen", "auto"])
        px_code, px_out, _ = self._run(
            ["sweep", "--xml", str(self._write(FIXTURE_XML)),
             "--screen", "1080x2400"])
        self.assertEqual(auto_code, px_code)
        self.assertEqual(json.loads(auto_out), json.loads(px_out))

    def test_sweep_expected_order_adds_a_missing_finding(self):
        code, out, _ = self._run([
            "sweep", "--xml", str(self._write(FIXTURE_XML)),
            "--order", "authTitle,googleSignIn,phoneInput,passwordInput,sendButton",
        ])
        self.assertEqual(code, 1)
        focus = json.loads(out)["checks"]["focus_order_issues"]
        self.assertEqual(focus["count"], 3)
        self.assertIn("sendButton", [f.get("rid") for f in focus["findings"]])

    def test_sweep_rejects_a_bad_screen_and_missing_dump(self):
        code, _, err = self._run(["sweep", "--xml", str(self._write(FIXTURE_XML)),
                                  "--screen", "bogus"])
        self.assertEqual(code, 1)
        self.assertIn("a11y_oracle: error:", err)
        with tempfile.TemporaryDirectory() as tmp:
            missing = Path(tmp) / "nope.xml"
            code, _, err = self._run(["sweep", "--xml", str(missing)])
        self.assertEqual(code, 1)
        self.assertIn("a11y_oracle: error:", err)

    def test_commands_prints_runnable_adb_lines_only(self):
        code, out, _ = self._run(["-s", "emulator-5554", "commands"])
        self.assertEqual(code, 0)
        lines = [line for line in out.splitlines() if line.strip()]
        self.assertTrue(lines)
        for line in lines:
            self.assertTrue(line.startswith("adb -s emulator-5554 shell "), line)
        joined = "\n".join(lines)
        self.assertIn("enabled_accessibility_services", joined)
        self.assertIn("accessibility_enabled", joined)
        self.assertIn("font_scale", joined)
        self.assertIn("system_locales", joined)
        self.assertIn("cmd locale set vi-VN", joined)
        self.assertIn("talkback", joined)


if __name__ == "__main__":
    unittest.main()
