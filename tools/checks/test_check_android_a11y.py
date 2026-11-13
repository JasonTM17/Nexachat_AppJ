#!/usr/bin/env python3
"""Unit tests for the static Android accessibility layout scanner.

Tests:
  - Rules A1 through A6 with fixture XML snippets (passing and failing cases)
  - @dimen resolution (direct, chained, cyclic protection)
  - Baseline functionality (hiding legacy findings, detecting new violations)
  - Values / style inspection
"""
from __future__ import annotations

import importlib.util
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
import sys

# Load tools/checks/check-android-a11y.py via importlib (hyphenated filename)
MODULE_PATH = Path(__file__).resolve().parent / "check-android-a11y.py"
spec = importlib.util.spec_from_file_location("check_android_a11y", MODULE_PATH)
if spec is None or spec.loader is None:
    raise ImportError(f"Cannot load module from {MODULE_PATH}")
a11y = importlib.util.module_from_spec(spec)
sys.modules["check_android_a11y"] = a11y
spec.loader.exec_module(a11y)


class TestAndroidA11yRules(unittest.TestCase):
    def setUp(self) -> None:
        self.resolver = a11y.DimenResolver()
        self.resolver.add_dimen("min_touch_target", "48dp")
        self.resolver.add_dimen("small_target", "32dp")
        self.resolver.add_dimen("text_sp", "14sp")
        self.resolver.add_dimen("text_dp", "14dp")

    def _scan_snippet(self, xml_snippet: str, file_path: str = "layout/test.xml") -> list[a11y.Finding]:
        tree, line_map = a11y.parse_xml_bytes(xml_snippet.encode("utf-8"))
        return a11y.scan_layout_tree(tree, line_map, file_path, self.resolver)

    # -------------------------------------------------------------------------
    # Rule A1: ImageView / ImageButton missing contentDescription
    # -------------------------------------------------------------------------
    def test_a1_image_view_with_content_description_passes(self) -> None:
        xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <ImageView
                android:id="@+id/logo"
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:contentDescription="@string/app_logo" />
        </LinearLayout>
        """
        findings = self._scan_snippet(xml)
        a1_findings = [f for f in findings if f.rule_id == "A1"]
        self.assertEqual(len(a1_findings), 0)

    def test_a1_image_view_with_important_for_a11y_no_passes(self) -> None:
        xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <ImageView
                android:layout_width="24dp"
                android:layout_height="24dp"
                android:importantForAccessibility="no" />
            <ImageButton
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:importantForAccessibility="noHideDescendants" />
        </LinearLayout>
        """
        findings = self._scan_snippet(xml)
        a1_findings = [f for f in findings if f.rule_id == "A1"]
        self.assertEqual(len(a1_findings), 0)

    def test_a1_image_view_missing_content_description_fails(self) -> None:
        xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <ImageView
                android:id="@+id/unlabelledIcon"
                android:layout_width="48dp"
                android:layout_height="48dp" />
        </LinearLayout>
        """
        findings = self._scan_snippet(xml)
        a1_findings = [f for f in findings if f.rule_id == "A1"]
        self.assertEqual(len(a1_findings), 1)
        self.assertEqual(a1_findings[0].element_id, "@+id/unlabelledIcon")
        self.assertEqual(a1_findings[0].severity, "ERROR")

    def test_a1_image_button_with_null_content_description_without_ifa_fails(self) -> None:
        xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <ImageButton
                android:id="@+id/nullDescBtn"
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:contentDescription="@null" />
        </LinearLayout>
        """
        findings = self._scan_snippet(xml)
        a1_findings = [f for f in findings if f.rule_id == "A1"]
        self.assertEqual(len(a1_findings), 1)

    # -------------------------------------------------------------------------
    # Rule A2: Interactive touch target under 48dp
    # -------------------------------------------------------------------------
    def test_a2_interactive_view_48dp_passes(self) -> None:
        xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <Button
                android:layout_width="48dp"
                android:layout_height="48dp" />
            <ImageButton
                android:layout_width="@dimen/min_touch_target"
                android:layout_height="@dimen/min_touch_target"
                android:contentDescription="@string/btn" />
        </LinearLayout>
        """
        findings = self._scan_snippet(xml)
        a2_findings = [f for f in findings if f.rule_id == "A2"]
        self.assertEqual(len(a2_findings), 0)

    def test_a2_interactive_view_compensated_by_padding_passes(self) -> None:
        xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <!-- 24dp + 2 * 12dp padding = 48dp -->
            <ImageButton
                android:layout_width="24dp"
                android:layout_height="24dp"
                android:padding="12dp"
                android:contentDescription="@string/btn" />
        </LinearLayout>
        """
        findings = self._scan_snippet(xml)
        a2_findings = [f for f in findings if f.rule_id == "A2"]
        self.assertEqual(len(a2_findings), 0)

    def test_a2_interactive_view_compensated_by_min_dimension_passes(self) -> None:
        xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <Button
                android:layout_width="24dp"
                android:layout_height="24dp"
                android:minWidth="48dp"
                android:minHeight="48dp" />
        </LinearLayout>
        """
        findings = self._scan_snippet(xml)
        a2_findings = [f for f in findings if f.rule_id == "A2"]
        self.assertEqual(len(a2_findings), 0)

    def test_a2_non_interactive_view_under_48dp_passes(self) -> None:
        xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <!-- Static divider line - not clickable or focusable -->
            <View
                android:layout_width="match_parent"
                android:layout_height="1dp" />
        </LinearLayout>
        """
        findings = self._scan_snippet(xml)
        a2_findings = [f for f in findings if f.rule_id == "A2"]
        self.assertEqual(len(a2_findings), 0)

    def test_a2_interactive_view_under_48dp_fails(self) -> None:
        xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <Button
                android:id="@+id/tinyBtn"
                android:layout_width="32dp"
                android:layout_height="32dp" />
            <View
                android:id="@+id/tinyClickableTile"
                android:clickable="true"
                android:layout_width="24dp"
                android:layout_height="24dp" />
        </LinearLayout>
        """
        findings = self._scan_snippet(xml)
        a2_findings = [f for f in findings if f.rule_id == "A2"]
        self.assertEqual(len(a2_findings), 2)
        self.assertEqual(a2_findings[0].severity, "ERROR")

    # -------------------------------------------------------------------------
    # Rule A3: Text size in dp or px instead of sp
    # -------------------------------------------------------------------------
    def test_a3_text_size_in_sp_passes(self) -> None:
        xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textSize="16sp" />
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textSize="@dimen/text_sp" />
        </LinearLayout>
        """
        findings = self._scan_snippet(xml)
        a3_findings = [f for f in findings if f.rule_id == "A3"]
        self.assertEqual(len(a3_findings), 0)

    def test_a3_text_size_in_dp_or_px_fails(self) -> None:
        xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <TextView
                android:id="@+id/badSizeDp"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textSize="14dp" />
            <TextView
                android:id="@+id/badSizePx"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textSize="18px" />
            <TextView
                android:id="@+id/badSizeDimenDp"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textSize="@dimen/text_dp" />
        </LinearLayout>
        """
        findings = self._scan_snippet(xml)
        a3_findings = [f for f in findings if f.rule_id == "A3"]
        self.assertEqual(len(a3_findings), 3)
        self.assertEqual(a3_findings[0].severity, "ERROR")

    # -------------------------------------------------------------------------
    # Rule A4: Fixed-height TextView/Button clipping at 200% font scale
    # -------------------------------------------------------------------------
    def test_a4_wrap_content_or_adequate_height_passes(self) -> None:
        xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textSize="16sp" />
            <TextView
                android:layout_width="120dp"
                android:layout_height="120dp"
                android:textSize="36sp" />
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="24dp"
                android:minHeight="48dp"
                android:textSize="14sp" />
        </LinearLayout>
        """
        findings = self._scan_snippet(xml)
        a4_findings = [f for f in findings if f.rule_id == "A4"]
        self.assertEqual(len(a4_findings), 0)

    def test_a4_fixed_height_clipping_at_200_scale_fails(self) -> None:
        xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <!-- 14sp at 200% = 28dp, plus line spacing > 20dp -->
            <TextView
                android:id="@+id/clippedText"
                android:layout_width="match_parent"
                android:layout_height="20dp"
                android:textSize="14sp" />
            <Button
                android:id="@+id/clippedButton"
                android:layout_width="match_parent"
                android:layout_height="24dp"
                android:textSize="16sp" />
        </LinearLayout>
        """
        findings = self._scan_snippet(xml)
        a4_findings = [f for f in findings if f.rule_id == "A4"]
        self.assertEqual(len(a4_findings), 2)
        self.assertEqual(a4_findings[0].severity, "WARN")

    # -------------------------------------------------------------------------
    # Rule A5: EditText without hint/labelFor/autofillHints
    # -------------------------------------------------------------------------
    def test_a5_labeled_edit_text_passes(self) -> None:
        xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                      xmlns:app="http://schemas.android.com/apk/res-auto">
            <EditText
                android:id="@+id/edit1"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:hint="@string/user_hint" />
            <EditText
                android:id="@+id/edit2"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:autofillHints="username" />
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:labelFor="@+id/edit3" />
            <EditText
                android:id="@+id/edit3"
                android:layout_width="match_parent"
                android:layout_height="wrap_content" />
            <com.google.android.material.textfield.TextInputLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                app:hint="@string/user_hint">
                <com.google.android.material.textfield.TextInputEditText
                    android:id="@+id/edit4"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content" />
            </com.google.android.material.textfield.TextInputLayout>
        </LinearLayout>
        """
        findings = self._scan_snippet(xml)
        a5_findings = [f for f in findings if f.rule_id == "A5"]
        self.assertEqual(len(a5_findings), 0)

    def test_a5_unlabeled_edit_text_fails(self) -> None:
        xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <EditText
                android:id="@+id/unlabeledEdit"
                android:layout_width="match_parent"
                android:layout_height="wrap_content" />
            <com.google.android.material.textfield.TextInputLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content">
                <com.google.android.material.textfield.TextInputEditText
                    android:id="@+id/unlabeledNestedEdit"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content" />
            </com.google.android.material.textfield.TextInputLayout>
        </LinearLayout>
        """
        findings = self._scan_snippet(xml)
        a5_findings = [f for f in findings if f.rule_id == "A5"]
        self.assertEqual(len(a5_findings), 2)
        self.assertEqual(a5_findings[0].severity, "WARN")

    # -------------------------------------------------------------------------
    # Rule A6: maxLines / singleLine on message, title, or error text
    # -------------------------------------------------------------------------
    def test_a6_unrestricted_or_non_title_passes(self) -> None:
        xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <!-- Unbounded text -->
            <TextView
                android:id="@+id/messageBody"
                android:layout_width="match_parent"
                android:layout_height="wrap_content" />
            <!-- Numerical counter / duration - not message, title, or error -->
            <TextView
                android:id="@+id/voiceDuration"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:maxLines="1" />
            <TextView
                android:id="@+id/voteCount"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:maxLines="1" />
        </LinearLayout>
        """
        findings = self._scan_snippet(xml)
        a6_findings = [f for f in findings if f.rule_id == "A6"]
        self.assertEqual(len(a6_findings), 0)

    def test_a6_max_lines_on_message_title_error_fails(self) -> None:
        xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <TextView
                android:id="@+id/rowTitle"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:maxLines="2" />
            <TextView
                android:id="@+id/chatPreview"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:singleLine="true" />
            <TextView
                android:id="@+id/errorMessage"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:maxLines="1" />
        </LinearLayout>
        """
        findings = self._scan_snippet(xml)
        a6_findings = [f for f in findings if f.rule_id == "A6"]
        self.assertEqual(len(a6_findings), 3)
        self.assertEqual(a6_findings[0].severity, "WARN")


class TestDimenResolver(unittest.TestCase):
    def test_dimen_resolution(self) -> None:
        resolver = a11y.DimenResolver()
        resolver.add_dimen("touch_target", "48dp")
        resolver.add_dimen("touch_alias", "@dimen/touch_target")
        resolver.add_dimen("font_body", "14sp")
        resolver.add_dimen("font_alias", "@dimen/font_body")

        # Direct resolution
        self.assertEqual(resolver.parse_dp("@dimen/touch_target"), 48.0)
        self.assertEqual(resolver.parse_sp("@dimen/font_body"), 14.0)

        # Chained resolution
        self.assertEqual(resolver.parse_dp("@dimen/touch_alias"), 48.0)
        self.assertEqual(resolver.parse_sp("@dimen/font_alias"), 14.0)

        # Cyclic protection
        resolver.add_dimen("cycle1", "@dimen/cycle2")
        resolver.add_dimen("cycle2", "@dimen/cycle1")
        self.assertIsNone(resolver.resolve_dimen("@dimen/cycle1"))


class TestBaselineFunctionality(unittest.TestCase):
    def test_baseline_hides_old_and_shows_new(self) -> None:
        old_finding = a11y.Finding(
            rule_id="A1",
            severity="ERROR",
            file_path="layout/old_screen.xml",
            line=10,
            element_id="@+id/oldIcon",
            tag="ImageView",
            message="Old violation",
        )
        new_finding = a11y.Finding(
            rule_id="A1",
            severity="ERROR",
            file_path="layout/new_screen.xml",
            line=25,
            element_id="@+id/newIcon",
            tag="ImageView",
            message="New violation",
        )

        baseline = a11y.Baseline.from_findings([old_finding])

        suppressed, new_reported = baseline.partition([old_finding, new_finding])

        # Old finding is hidden/suppressed
        self.assertEqual(len(suppressed), 1)
        self.assertEqual(suppressed[0].element_id, "@+id/oldIcon")

        # New finding is reported
        self.assertEqual(len(new_reported), 1)
        self.assertEqual(new_reported[0].element_id, "@+id/newIcon")

    def test_baseline_serialization_roundtrip(self) -> None:
        finding = a11y.Finding(
            rule_id="A6",
            severity="WARN",
            file_path="layout/screen.xml",
            line=42,
            element_id="@+id/title",
            tag="TextView",
            message="Test message",
        )
        baseline = a11y.Baseline.from_findings([finding])

        with tempfile.TemporaryDirectory() as tmpdir:
            file_path = Path(tmpdir) / "baseline.json"
            baseline.save(file_path)
            loaded = a11y.Baseline.load(file_path)

            suppressed, new_items = loaded.partition([finding])
            self.assertEqual(len(suppressed), 1)
            self.assertEqual(len(new_items), 0)


class TestValuesScanning(unittest.TestCase):
    def test_scan_style_text_size_in_dp(self) -> None:
        xml = """
        <resources>
            <style name="TextAppearance.Custom">
                <item name="android:textSize">14dp</item>
            </style>
        </resources>
        """
        resolver = a11y.DimenResolver()
        with tempfile.TemporaryDirectory() as tmpdir:
            vfile = Path(tmpdir) / "styles.xml"
            vfile.write_text(xml, encoding="utf-8")
            findings = a11y.scan_values_file(vfile, Path(tmpdir), resolver)

            self.assertEqual(len(findings), 1)
            self.assertEqual(findings[0].rule_id, "A3")
            self.assertEqual(findings[0].element_id, "TextAppearance.Custom")
            self.assertEqual(findings[0].severity, "ERROR")


if __name__ == "__main__":
    unittest.main()
