"""Regression tests for the release R8 mapping checker helpers."""

from __future__ import annotations

import importlib.util
import os
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


if __name__ == "__main__":
    unittest.main()
