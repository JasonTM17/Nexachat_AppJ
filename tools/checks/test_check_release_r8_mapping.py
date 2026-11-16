"""Regression tests for the release R8 mapping checker helpers."""

from __future__ import annotations

import importlib.util
import os
import tempfile
import unittest
from pathlib import Path


SCRIPT_PATH = Path(
    os.environ.get(
        "R8_CHECKER_SCRIPT",
        Path(__file__).with_name("check-release-r8-mapping.py"),
    )
).resolve()
SPEC = importlib.util.spec_from_file_location("check_release_r8_mapping", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise ImportError(f"cannot load R8 checker from {SCRIPT_PATH}")
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class HiltDeclaredTypesTests(unittest.TestCase):
    def test_top_level_annotated_class_uses_its_source_name(self) -> None:
        source = "package pkg; @HiltAndroidApp class App {}"

        self.assertEqual(CHECKER.hilt_declared_types("pkg", "App", source), {"pkg.App"})

    def test_nested_entry_point_uses_nested_binary_name(self) -> None:
        source = """package pkg;
class Outer {
    @EntryPoint
    @InstallIn(SingletonComponent.class)
    interface Inner {}
}
"""

        declared = CHECKER.hilt_declared_types("pkg", "Outer", source)

        self.assertEqual(declared, {"pkg.Outer$Inner"})
        self.assertNotIn("pkg.Outer", declared)

    def test_class_literal_in_annotation_arguments_is_not_the_declaration(self) -> None:
        source = """package pkg;
@InstallIn(SingletonComponent.class)
class TopLevelModule {}
"""

        self.assertEqual(
            CHECKER.hilt_declared_types("pkg", "TopLevelModule", source),
            {"pkg.TopLevelModule"},
        )

    def test_source_without_hilt_annotation_returns_no_types(self) -> None:
        source = "package pkg; class PlainType {}"

        self.assertEqual(CHECKER.hilt_declared_types("pkg", "PlainType", source), set())


class RoomAnonymousClassCountTests(unittest.TestCase):
    def test_migrations_and_room_callback_are_counted_separately(self) -> None:
        migrations = "\n".join(
            f"addMigrations(new Migration({version}, {version + 1}) {{}});"
            for version in range(1, 14)
        )
        source = f"""class AppDatabase {{
{migrations}
    new Callback() {{ public void onCreate() {{}} }};
}}
"""

        self.assertEqual(CHECKER.count_room_anonymous_classes(source), (13, 1))

    def test_only_migrations_have_no_other_anonymous_bodies(self) -> None:
        migrations = "\n".join(
            f"addMigrations(new Migration({version}, {version + 1}) {{}});"
            for version in range(1, 6)
        )

        self.assertEqual(CHECKER.count_room_anonymous_classes(migrations), (5, 0))


class LayoutBehaviorLookupsTests(unittest.TestCase):
    def _res_dir(self, files: dict[str, str]) -> Path:
        root = Path(self._tmp.name)
        for relative, content in files.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        return root

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_qualified_class_name_in_attribute_is_reported(self) -> None:
        res = self._res_dir(
            {
                "layout/screen.xml": """<?xml version="1.0"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">
    <View android:id="@+id/rail"
        app:layout_behavior="com.example.feature.RailBehavior" />
</androidx.coordinatorlayout.widget.CoordinatorLayout>
"""
            }
        )

        lookups = CHECKER.layout_behavior_lookups(res)

        self.assertEqual(
            lookups,
            {"com.example.feature.RailBehavior": "screen.xml app:layout_behavior"},
        )

    def test_string_indirection_resolves_through_app_values(self) -> None:
        res = self._res_dir(
            {
                "values/strings.xml": """<?xml version="1.0"?>
<resources>
    <string name="rail_behavior">com.example.feature.RailBehavior</string>
</resources>
""",
                "layout-land/screen.xml": """<?xml version="1.0"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout
    xmlns:app="http://schemas.android.com/apk/res-auto">
    <View app:layout_behavior="@string/rail_behavior" />
</androidx.coordinatorlayout.widget.CoordinatorLayout>
""",
            }
        )

        lookups = CHECKER.layout_behavior_lookups(res)

        self.assertIn("com.example.feature.RailBehavior", lookups)

    def test_unresolvable_and_non_class_values_are_skipped(self) -> None:
        res = self._res_dir(
            {
                "layout/screen.xml": """<?xml version="1.0"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout
    xmlns:app="http://schemas.android.com/apk/res-auto">
    <View app:layout_behavior="@string/library_owned_behavior" />
    <View app:layout_behavior="anchor" />
</androidx.coordinatorlayout.widget.CoordinatorLayout>
"""
            }
        )

        self.assertEqual(CHECKER.layout_behavior_lookups(res), {})


class KeepCoversBehaviorTests(unittest.TestCase):
    CLASS = "com.nexachat.feature.contacts.ContactLetterRailBehavior"

    def test_explicit_class_keep_covers_the_behavior(self) -> None:
        rules = (
            "-keep class com.nexachat.feature.contacts.ContactLetterRailBehavior "
            "{ <init>(...); }"
        )

        self.assertTrue(CHECKER.keep_covers_behavior(rules, self.CLASS))

    def test_generic_behavior_keep_covers_the_behavior(self) -> None:
        rules = (
            "-keep class * extends "
            "androidx.coordinatorlayout.widget.CoordinatorLayout$Behavior {\n"
            "    public <init>(android.content.Context, android.util.AttributeSet);\n"
            "}"
        )

        self.assertTrue(CHECKER.keep_covers_behavior(rules, self.CLASS))

    def test_keep_on_another_base_type_does_not_cover(self) -> None:
        rules = "-keep class * extends androidx.room.migration.Migration { *; }"

        self.assertFalse(CHECKER.keep_covers_behavior(rules, self.CLASS))

    def test_member_and_annotation_conditioned_keeps_do_not_cover(self) -> None:
        rules = (
            "-keepclassmembers class * { @com.squareup.moshi.Json <fields>; }\n"
            "-keep @androidx.annotation.Keep class * { *; }\n"
            "-keepnames class com.nexachat.feature.contacts.ContactLetterRailBehavior"
        )

        self.assertFalse(CHECKER.keep_covers_behavior(rules, self.CLASS))

    def test_allow_modifiers_do_not_cover(self) -> None:
        rules = (
            "-keep,allowobfuscation class "
            "com.nexachat.feature.contacts.ContactLetterRailBehavior { *; }"
        )

        self.assertFalse(CHECKER.keep_covers_behavior(rules, self.CLASS))

    def test_unrelated_package_keep_does_not_cover(self) -> None:
        rules = "-keep class com.nexachat.core.network.dto.** { *; }"

        self.assertFalse(CHECKER.keep_covers_behavior(rules, self.CLASS))


if __name__ == "__main__":
    unittest.main()
