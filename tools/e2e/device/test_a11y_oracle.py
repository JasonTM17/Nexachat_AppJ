"""World-model tests for a11y_oracle: command builders, parser, oracles, CLI.

Everything runs without a device: oracles are pure functions over synthetic
node dicts and a realistic ``uiautomator dump`` XML fixture; command builders
are asserted as strings only. Compatibility with the real ``adb_ui.Node``
shape is proven by wrapping the same fixture elements in that class. The
decorative-rid resolution reads the app's own layout XML (synthetic layout
dirs, plus the repo's checked-out default) -- still no device.
"""

from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
import warnings
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

# Synthetic app-layout dir for decorative-rid resolution: both markers the
# app's own XML uses (view_settings_row.xml pins the real rowIcon/rowChevron
# pair), a non-decorative control that must stay flagged, a decorative element
# with no resolvable id, and a platform id that must never match. The second
# file drops xmlns:android to pin the namespace-optional attribute fallback.
DECORATIVE_LAYOUTS = {
    "view_row.xml": """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/rowContent"
    android:layout_width="match_parent"
    android:layout_height="wrap_content">

    <ImageView
        android:id="@+id/rowIcon"
        android:layout_width="24dp"
        android:layout_height="24dp"
        android:importantForAccessibility="no"
        android:src="@drawable/ic_settings" />

    <ImageView
        android:id="@+id/rowChevron"
        android:layout_width="16dp"
        android:layout_height="16dp"
        android:contentDescription="@null"
        android:src="@drawable/ic_arrow" />

    <ImageView
        android:id="@+id/sendButton"
        android:layout_width="48dp"
        android:layout_height="48dp" />

    <ImageView
        android:layout_width="24dp"
        android:layout_height="24dp"
        android:importantForAccessibility="no" />

    <ImageView
        android:id="@android:id/text1"
        android:layout_width="24dp"
        android:layout_height="24dp"
        android:importantForAccessibility="no" />
</LinearLayout>
""",
    "view_row_plain.xml": """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout id="@+id/plainContent"
    layout_width="match_parent"
    layout_height="wrap_content">

    <ImageView
        id="@+id/plainIcon"
        layout_width="24dp"
        layout_height="24dp"
        importantForAccessibility="no" />
</LinearLayout>
""",
}

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
            (f"adb -s {self.SERIAL} shell cmd locale set-device-locale vi-VN",))
        self.assertEqual(
            oracle.get_locale(self.SERIAL),
            (f"adb -s {self.SERIAL} shell getprop persist.sys.locale",))
        self.assertEqual(
            oracle.restore_locale(self.SERIAL, "en-US"),
            (f"adb -s {self.SERIAL} shell cmd locale set-device-locale en-US",))

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

    def test_decorative_rids_are_suppressed_and_non_decorative_stay_flagged(self):
        # The API 36 blind spot: rowIcon sits in the dump without a desc, but
        # the app's own layout marks it decorative; sendButton is a genuine
        # unnamed image and must survive the suppression.
        nodes = [
            N(**{"class": "android.widget.ImageView",
                 "resource-id": "com.nexachat.debug:id/rowIcon"},
               bounds="[0,0][24,24]"),
            N(**{"class": "android.widget.ImageView",
                 "resource-id": "com.nexachat.debug:id/sendButton"},
               bounds="[0,100][148,148]"),
        ]
        findings = oracle.missing_desc_images(nodes, decorative_rids={"rowIcon"})
        self.assertEqual([f["rid"] for f in findings], ["sendButton"])
        # Without the static signal the dump-only oracle still flags both.
        self.assertEqual(sorted(f["rid"] for f in oracle.missing_desc_images(nodes)),
                         ["rowIcon", "sendButton"])

    def test_none_or_empty_decorative_rids_keep_the_dump_only_behavior(self):
        for empty in (None, frozenset()):
            with self.subTest(empty=empty):
                self.assertEqual(
                    sorted(f["rid"] for f in oracle.missing_desc_images(
                        FIXTURE_NODES, decorative_rids=empty)),
                    ["brandLogo", "passwordToggle"])

    def test_suppression_only_touches_findings_never_named_images(self):
        nodes = [
            N(**{"class": "android.widget.ImageView",
                 "resource-id": "com.nexachat.debug:id/rowIcon",
                 "content-desc": "Settings"}, bounds="[0,0][24,24]"),
        ]
        self.assertEqual(
            oracle.missing_desc_images(nodes, decorative_rids={"rowIcon"}), [])


class DecorativeRidResolutionTest(unittest.TestCase):
    """The static decorative-rid resolution that unblinds missing_desc_images.

    API 36 uiautomator dumps include views the app marked
    ``importantForAccessibility='no'`` (the settings rowIcon/rowChevron
    evidence from the first completed sweep run) but carry no attribute
    identifying them, so suppression must come from the app's own layout XML
    and stay auditable.
    """

    def setUp(self) -> None:
        # Each test scans its layout dir fresh; the load cache is per path.
        oracle.load_decorative_rids.cache_clear()
        self.addCleanup(oracle.load_decorative_rids.cache_clear)

    def _layout_dir(self, files: dict[str, str] | None = None,
                    broken: bool = False) -> Path:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        base = Path(tmp.name)
        for name, xml in (files if files is not None else DECORATIVE_LAYOUTS).items():
            (base / name).write_text(xml, encoding="utf-8")
        if broken:
            (base / "broken.xml").write_text("<LinearLayout not-closed",
                                             encoding="utf-8")
        return base

    def test_scans_both_decorative_markers_from_the_layout_dir(self):
        rids = oracle.load_decorative_rids(self._layout_dir())
        # rowIcon: importantForAccessibility='no'; rowChevron:
        # contentDescription='@null'; plainIcon: the namespace-less fallback.
        self.assertEqual(rids, {"rowIcon", "rowChevron", "plainIcon"})

    def test_missing_layout_dir_degrades_to_no_suppression_with_a_warning(self):
        missing = self._layout_dir(files={}) / "no-such-layout-dir"
        with self.assertWarns(UserWarning) as caught:
            rids = oracle.load_decorative_rids(missing)
        self.assertEqual(rids, frozenset())
        self.assertIn("decorative suppression is disabled", str(caught.warning))

    def test_unreadable_layout_files_are_skipped_with_a_warning(self):
        base = self._layout_dir(broken=True)
        with self.assertWarns(UserWarning):
            rids = oracle.load_decorative_rids(base)
        # broken.xml is skipped; the good files still resolve.
        self.assertEqual(rids, {"rowIcon", "rowChevron", "plainIcon"})

    def test_none_disables_suppression_without_a_warning(self):
        with warnings.catch_warnings(record=True) as caught:
            warnings.simplefilter("always")
            self.assertEqual(oracle.load_decorative_rids(None), frozenset())
        self.assertEqual(caught, [])

    def test_the_layout_scan_runs_once_per_path(self):
        # A sweep calls summarize_findings per (variant, screen) cell; the
        # scan itself must happen only once per layout dir.
        base = self._layout_dir()
        first = oracle.load_decorative_rids(base)
        second = oracle.load_decorative_rids(base)
        info = oracle.load_decorative_rids.cache_info()
        self.assertEqual(first, second)
        self.assertEqual(info.misses, 1)
        self.assertEqual(info.hits, 1)

    def test_the_repo_default_marks_the_settings_row_icons_decorative(self):
        # The exact false positives from the sweep evidence: the checked-out
        # view_settings_row.xml declares rowIcon/rowChevron decorative.
        rids = oracle.load_decorative_rids()
        self.assertIn("rowIcon", rids)
        self.assertIn("rowChevron", rids)
        # The fixture's planted defects are decorative nowhere in the repo.
        self.assertNotIn("brandLogo", rids)
        self.assertNotIn("passwordToggle", rids)


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
        # The repo layouts mark neither fixture image decorative: nothing is
        # suppressed, and the count still says so explicitly.
        self.assertEqual(checks["missing_desc_images"]["suppressed_decorative"], 0)

    def _brand_logo_layout_dir(self) -> Path:
        """A layout dir whose one file declares brandLogo decorative."""
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        (Path(tmp.name) / "view_brand.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<ImageView xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:id="@+id/brandLogo"\n'
            '    android:importantForAccessibility="no" />\n',
            encoding="utf-8")
        return Path(tmp.name)

    def test_decorative_suppression_is_counted_and_stays_auditable(self):
        # brandLogo is suppressed via the app's own layout declaration;
        # passwordToggle is a genuine unnamed ImageButton and stays flagged.
        report = oracle.summarize_findings(
            FIXTURE_NODES, screen="auto", layout_dir=self._brand_logo_layout_dir())
        self.assertFalse(report["ok"])
        images = report["checks"]["missing_desc_images"]
        self.assertEqual([f["rid"] for f in images["findings"]], ["passwordToggle"])
        self.assertEqual(images["count"], 1)
        self.assertEqual(images["ok"], False)
        self.assertEqual(images["suppressed_decorative"], 1)
        # The suppression count must survive the evidence-capture round trip.
        self.assertEqual(json.loads(json.dumps(report)), report)

    def test_full_suppression_leaves_the_check_ok_with_the_audit_count(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        (Path(tmp.name) / "view_brand.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<merge xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <ImageView android:id="@+id/brandLogo"\n'
            '        android:importantForAccessibility="no" />\n'
            '    <ImageButton android:id="@+id/passwordToggle"\n'
            '        android:contentDescription="@null" />\n'
            '</merge>\n',
            encoding="utf-8")
        report = oracle.summarize_findings(
            FIXTURE_NODES, screen="auto", layout_dir=Path(tmp.name))
        images = report["checks"]["missing_desc_images"]
        self.assertEqual(images["count"], 0)
        self.assertTrue(images["ok"])
        self.assertEqual(images["suppressed_decorative"], 2)
        # Suppression never silences the other oracles: the toggle is still an
        # unlabeled interactive node and a small touch target.
        self.assertEqual(report["checks"]["unlabeled_interactive"]["count"], 2)
        self.assertEqual(report["checks"]["small_touch_targets"]["count"], 1)

    def test_layout_dir_none_disables_suppression_without_a_scan(self):
        with mock.patch.object(oracle, "load_decorative_rids") as load:
            report = oracle.summarize_findings(
                FIXTURE_NODES, screen="auto", layout_dir=None)
        load.assert_not_called()
        images = report["checks"]["missing_desc_images"]
        self.assertEqual(images["count"], 2)
        self.assertEqual(images["suppressed_decorative"], 0)

    def test_a_missing_layout_dir_degrades_to_unsuppressed_findings(self):
        missing = self._brand_logo_layout_dir() / "no-such-layout-dir"
        with self.assertWarns(UserWarning):
            report = oracle.summarize_findings(
                FIXTURE_NODES, screen="auto", layout_dir=missing)
        images = report["checks"]["missing_desc_images"]
        self.assertEqual(images["count"], 2)
        self.assertEqual(images["suppressed_decorative"], 0)

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

    def test_sweep_layout_dir_plumbs_into_decorative_suppression(self):
        layouts = tempfile.TemporaryDirectory()
        self.addCleanup(layouts.cleanup)
        (Path(layouts.name) / "view_brand.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<ImageView xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:id="@+id/brandLogo"\n'
            '    android:importantForAccessibility="no" />\n',
            encoding="utf-8")
        code, out, _ = self._run([
            "sweep", "--xml", str(self._write(FIXTURE_XML)),
            "--layout-dir", layouts.name,
        ])
        # The fixture keeps its other defects, so the sweep still reports them.
        self.assertEqual(code, 1)
        images = json.loads(out)["checks"]["missing_desc_images"]
        self.assertEqual(images["count"], 1)
        self.assertEqual(images["suppressed_decorative"], 1)
        self.assertEqual([f["rid"] for f in images["findings"]], ["passwordToggle"])

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
        self.assertIn("cmd locale set-device-locale vi-VN", joined)
        self.assertIn("talkback", joined)


class ClippedAndFrameworkSkipsTest(unittest.TestCase):
    """Real false positives from a 320x640 dump become informational entries.

    The oracles must still drop them from ``findings`` while keeping them
    auditable in the report's ``clipped_skipped`` / ``framework_skipped`` lists.
    """

    SCREEN = "[0,0][320,640]"

    def _section(self, nodes: list[dict[str, str]], name: str) -> dict:
        report = oracle.summarize_findings(nodes, layout_dir=None)
        # The dump is still JSON-ready after adding the informational lists.
        self.assertEqual(json.loads(json.dumps(report)), report)
        return report["checks"][name]

    def test_deleted_message_clipped_at_scrollable_top_is_skipped(self):
        nodes = [
            N(bounds=self.SCREEN),
            N(scrollable="true", bounds="[0,88][320,600]"),  # message list
            N(clickable="true", **{"content-desc": "This message was deleted, 20:46"},
              bounds="[12,88][308,96]"),
        ]
        section = self._section(nodes, "small_touch_targets")
        self.assertEqual(section["findings"], [])
        skipped = section["clipped_skipped"]
        self.assertEqual([f["content_desc"] for f in skipped],
                         ["This message was deleted, 20:46"])
        self.assertEqual(skipped[0]["clipped_axes"], ["height"])
        self.assertEqual(skipped[0]["below_floor"], ["height"])
        self.assertEqual(section["clipped_skipped_count"], 1)

    def test_tab_clipped_at_screen_and_tab_strip_right_edge_is_skipped(self):
        nodes = [
            N(bounds=self.SCREEN),
            N(scrollable="true", bounds="[0,0][320,200]"),  # tab strip, taller than its tabs
            N(clickable="true", **{"content-desc": "Links"}, bounds="[304,88][320,136]"),
        ]
        section = self._section(nodes, "small_touch_targets")
        self.assertEqual(section["findings"], [])
        skipped = section["clipped_skipped"]
        self.assertEqual([f["content_desc"] for f in skipped], ["Links"])
        self.assertEqual(skipped[0]["clipped_axes"], ["width"])

    def test_theme_row_clipped_at_scrollable_bottom_is_skipped(self):
        # The real themeRow sits inside fragment_profile's NestedScrollView;
        # a screen-edge coincidence alone no longer counts as clipping.
        nodes = [
            N(bounds=self.SCREEN),
            N(scrollable="true", bounds="[0,88][320,640]"),
            N(clickable="true", **{"resource-id": "com.nexachat.debug:id/themeRow"},
              bounds="[16,595][304,640]"),
        ]
        section = self._section(nodes, "small_touch_targets")
        self.assertEqual(section["findings"], [])
        skipped = section["clipped_skipped"]
        self.assertEqual([f["rid"] for f in skipped], ["themeRow"])
        self.assertEqual(skipped[0]["clipped_axes"], ["height"])

    def test_button_flush_with_screen_bottom_is_still_a_finding(self):
        # Designed-flush is indistinguishable from clipped at the screen edge,
        # so a bottom-bar button with no containing scrollable must not be
        # silently skipped (Wukong F1).
        nodes = [
            N(bounds=self.SCREEN),
            N(clickable="true", **{"content-desc": "Mute"}, bounds="[236,600][284,640]"),
        ]
        section = self._section(nodes, "small_touch_targets")
        self.assertEqual(section["clipped_skipped"], [])
        self.assertEqual(len(section["findings"]), 1)
        self.assertEqual(section["findings"][0]["below_floor"], ["height"])

    def test_small_node_fully_inside_scrollable_is_still_a_finding(self):
        # Negative control: 30x30px at 160dpi (floor 48px), touching no edge.
        nodes = [
            N(bounds=self.SCREEN),
            N(scrollable="true", bounds="[0,100][320,500]"),
            N(clickable="true", bounds="[100,200][130,230]"),
        ]
        section = self._section(nodes, "small_touch_targets")
        self.assertEqual(section["clipped_skipped"], [])
        self.assertEqual(len(section["findings"]), 1)
        self.assertEqual(section["findings"][0]["below_floor"], ["width", "height"])

    def test_clipped_height_does_not_hide_an_unclipped_short_width(self):
        nodes = [
            N(bounds=self.SCREEN),
            N(scrollable="true", bounds="[0,88][320,600]"),
            N(clickable="true", bounds="[100,88][120,120]"),  # 20x32, top clipped
        ]
        section = self._section(nodes, "small_touch_targets")
        self.assertEqual(section["clipped_skipped"], [])
        findings = section["findings"]
        self.assertEqual(len(findings), 1)
        # The measured axes stay whole; the swallowed clip is auditable, not
        # erased from the finding (Wukong F3).
        self.assertEqual(findings[0]["below_floor"], ["width", "height"])
        self.assertEqual(findings[0]["clipped_axes"], ["height"])

    def test_framework_button_panel_with_labeled_children_is_skipped(self):
        nodes = [
            N(clickable="true", **{"resource-id": "android:id/buttonPanel"},
              bounds="[0,500][320,600]"),
            N(text="Cancel", clickable="true", bounds="[20,520][140,568]"),
            N(**{"content-desc": "OK"}, clickable="true", bounds="[180,520][300,568]"),
        ]
        section = self._section(nodes, "unlabeled_interactive")
        self.assertEqual(section["findings"], [])
        skipped = section["framework_skipped"]
        self.assertEqual([f["rid"] for f in skipped], ["buttonPanel"])
        self.assertEqual(sorted(skipped[0]["descendant_labels"]), ["Cancel", "OK"])
        self.assertEqual(section["framework_skipped_count"], 1)

    def test_appcompat_button_panel_rid_is_skipped(self):
        # appcompat AlertDialog (MaterialAlertDialogBuilder) renumbers the
        # buttonPanel id into the app package — the skip must not depend on
        # the ``android:`` prefix (post-v1.2.18 labels-dialog sweep failure).
        nodes = [
            N(clickable="true", **{"resource-id": "com.nexachat.debug:id/buttonPanel"},
              bounds="[0,500][320,600]"),
            N(text="Done", clickable="true", bounds="[180,520][300,568]"),
        ]
        section = self._section(nodes, "unlabeled_interactive")
        self.assertEqual(section["findings"], [])
        skipped = section["framework_skipped"]
        self.assertEqual([f["rid"] for f in skipped], ["buttonPanel"])
        self.assertEqual(skipped[0]["descendant_labels"], ["Done"])
        self.assertEqual(section["framework_skipped_count"], 1)

    def test_button_panel_without_a_labeled_child_remains_a_finding(self):
        nodes = [
            N(clickable="true", **{"resource-id": "android:id/buttonPanel"},
              bounds="[0,500][320,600]"),
        ]
        section = self._section(nodes, "unlabeled_interactive")
        self.assertEqual(section["framework_skipped"], [])
        self.assertEqual([f["rid"] for f in section["findings"]], ["buttonPanel"])

    def test_button_panel_label_on_noninteractive_child_remains_a_finding(self):
        # A label from a non-interactive overlapping node must not suppress the
        # finding — only labels on interactive children count (Wukong F6).
        nodes = [
            N(clickable="true", **{"resource-id": "android:id/buttonPanel"},
              bounds="[0,500][320,600]"),
            N(text="Dialog hint", bounds="[20,520][140,560]"),
        ]
        section = self._section(nodes, "unlabeled_interactive")
        self.assertEqual(section["framework_skipped"], [])
        self.assertEqual([f["rid"] for f in section["findings"]], ["buttonPanel"])


if __name__ == "__main__":
    unittest.main()
