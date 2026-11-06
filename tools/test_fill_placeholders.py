#!/usr/bin/env python3
"""Offline tests for report-placeholder inventory and substitution."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("fill-placeholders.py")
SPEC = importlib.util.spec_from_file_location("fill_placeholders", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
fill_placeholders = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = fill_placeholders
SPEC.loader.exec_module(fill_placeholders)


class FillPlaceholdersTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name) / "repo"
        (cls.root / "docs").mkdir(parents=True)
        (cls.root / ".gitignore").write_text(
            "values*.json\n!values.example.json\n", encoding="utf-8"
        )
        (cls.root / "README.md").write_bytes(
            "# Team\r\n- _name_\r\n- _name_\r\n- _fill in_\r\n".encode("utf-8")
        )
        (cls.root / "docs" / "report.md").write_bytes(
            "# Report\r\nGroup: `{{GROUP_NAME}}`\r\nStudent: `{{MSSV_1}}`\r\n".encode("utf-8")
        )
        cls._git("init", "--quiet")
        cls._git("add", "--", ".gitignore", "README.md", "docs/report.md")
        env = os.environ.copy()
        env.update({
            "GIT_AUTHOR_NAME": "Placeholder Test",
            "GIT_AUTHOR_EMAIL": "placeholder-test@example.invalid",
            "GIT_COMMITTER_NAME": "Placeholder Test",
            "GIT_COMMITTER_EMAIL": "placeholder-test@example.invalid",
        })
        subprocess.run(
            ["git", "-C", str(cls.root), "commit", "--quiet", "-m", "baseline"],
            check=True,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temporary.cleanup()

    def setUp(self) -> None:
        (self.root / ".gitignore").write_text(
            "values*.json\n!values.example.json\n", encoding="utf-8"
        )
        (self.root / "README.md").write_bytes(
            "# Team\r\n- _name_\r\n- _name_\r\n- _fill in_\r\n".encode("utf-8")
        )
        (self.root / "docs" / "report.md").write_bytes(
            "# Report\r\nGroup: `{{GROUP_NAME}}`\r\nStudent: `{{MSSV_1}}`\r\n".encode("utf-8")
        )
        for name in ("values.json", "values.example.json", "personal-data.json", "untracked.md"):
            path = self.root / name
            if path.exists():
                path.unlink()

    @classmethod
    def _git(cls, *args: str) -> None:
        subprocess.run(
            ["git", "-C", str(cls.root), *args],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

    def _write_values(self, values: dict[str, str]) -> Path:
        path = self.root / "values.json"
        path.write_text(json.dumps(values, ensure_ascii=False, indent=2), encoding="utf-8")
        return path

    def _apply(self, values: dict[str, str], *flags: str) -> tuple[int, str, str]:
        path = self._write_values(values)
        stdout = io.StringIO()
        stderr = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = fill_placeholders.main(["--values", str(path), *flags], root=self.root)
        return result, stdout.getvalue(), stderr.getvalue()

    def _complete_values(self) -> dict[str, str]:
        return {
            "{{GROUP_NAME}}": "NexaChat",
            "{{MSSV_1}}": "22A001",
            "_name_": "Nguyễn Ánh",
            "_fill in_": "AI-assisted testing",
        }

    def test_tracked_tokens_and_locations_are_listed(self) -> None:
        (self.root / "untracked.md").write_text("{{UNTRACKED}}\n", encoding="utf-8")
        inventory, _ = fill_placeholders.scan_working_tree(self.root)

        self.assertEqual(len(inventory["{{GROUP_NAME}}"]), 1)
        self.assertEqual(len(inventory["{{MSSV_1}}"]), 1)
        self.assertEqual(len(inventory["_name_"]), 2)
        self.assertEqual(len(inventory["_fill in_"]), 1)
        self.assertNotIn("{{UNTRACKED}}", inventory)

        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            result = fill_placeholders.main(["--list"], root=self.root)
        self.assertEqual(result, 1)
        self.assertIn("README.md:2", stdout.getvalue())
        self.assertIn("{{GROUP_NAME}}: 1", stdout.getvalue())
        self.assertIn("Overall: 5 placeholder occurrence(s)", stdout.getvalue())

    def test_dry_run_shows_diff_without_writing(self) -> None:
        before = {
            path: (self.root / path).read_bytes()
            for path in ("README.md", "docs/report.md")
        }

        result, stdout, stderr = self._apply(self._complete_values(), "--dry-run")

        self.assertEqual(result, 0, stderr)
        self.assertIn("DRY RUN", stdout)
        self.assertIn("+Group: `NexaChat`", stdout)
        self.assertEqual(
            before,
            {path: (self.root / path).read_bytes() for path in before},
        )

    def test_missing_value_is_refused_without_writing(self) -> None:
        before = (self.root / "docs" / "report.md").read_bytes()

        result, _, stderr = self._apply({"{{GROUP_NAME}}": "NexaChat"})

        self.assertEqual(result, 2)
        self.assertIn("Missing non-empty value", stderr)
        self.assertEqual(before, (self.root / "docs" / "report.md").read_bytes())

    def test_unknown_token_is_refused_without_writing(self) -> None:
        before = (self.root / "docs" / "report.md").read_bytes()
        values = self._complete_values()
        values["{{MEBMER_1}}"] = "Someone"

        result, _, stderr = self._apply(values)

        self.assertEqual(result, 2)
        self.assertIn("Unknown placeholder key", stderr)
        self.assertEqual(before, (self.root / "docs" / "report.md").read_bytes())

    def test_mssv_must_be_alphanumeric(self) -> None:
        values = self._complete_values()
        values["{{MSSV_1}}"] = "22-A001"

        result, _, stderr = self._apply(values)

        self.assertEqual(result, 2)
        self.assertIn("ASCII letters and digits only", stderr)

    def test_control_characters_and_nested_tokens_are_refused(self) -> None:
        values = self._complete_values()
        values["{{GROUP_NAME}}"] = "Nexa\nChat"
        result, _, stderr = self._apply(values)
        self.assertEqual(result, 2)
        self.assertIn("control character", stderr)

        values["{{GROUP_NAME}}"] = "{{OTHER_TOKEN}}"
        result, _, stderr = self._apply(values)
        self.assertEqual(result, 2)
        self.assertIn("nested", stderr)

    def test_example_and_repeat_apply_preserve_vietnamese_and_crlf(self) -> None:
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            example_result = fill_placeholders.main(["--example"], root=self.root)
        self.assertEqual(example_result, 0)
        example = json.loads((self.root / "values.example.json").read_text(encoding="utf-8"))
        self.assertEqual(example["{{GROUP_NAME}}"], "")
        self.assertIn("_name_@README.md:2", example)
        self.assertIn("_name_@README.md:3", example)
        self.assertTrue(all(value == "" for value in example.values()))

        values = {
            "{{GROUP_NAME}}": "NexaChat",
            "{{MSSV_1}}": "22A001",
            "_name_": "",
            "_name_@README.md:2": "Nguyễn Ánh",
            "_name_@README.md:3": "Trần Bình",
            "_fill in_": "AI-assisted testing",
        }
        result, _, stderr = self._apply(values)
        self.assertEqual(result, 0, stderr)

        readme_bytes = (self.root / "README.md").read_bytes()
        report_bytes = (self.root / "docs" / "report.md").read_bytes()
        self.assertIn("Nguyễn Ánh".encode("utf-8"), readme_bytes)
        self.assertIn("Trần Bình".encode("utf-8"), readme_bytes)
        for content in (readme_bytes, report_bytes):
            self.assertEqual(content.count(b"\r\n"), content.count(b"\n"))
            self.assertNotIn(b"\n", content.replace(b"\r\n", b""))

        second_result, second_stdout, second_stderr = self._apply(values)
        self.assertEqual(second_result, 0, second_stderr)
        self.assertIn("No changes needed", second_stdout)

        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            list_result = fill_placeholders.main(["--list"], root=self.root)
        self.assertEqual(list_result, 0)
        self.assertIn("Overall: 0 placeholder occurrence(s)", stdout.getvalue())

    def test_values_file_must_be_ignored_when_inside_repo(self) -> None:
        ignored = self.root / "values.json"
        ignored.write_text("{}", encoding="utf-8")
        self.assertEqual(fill_placeholders.validate_values_path(ignored, self.root), ignored.resolve())

        visible = self.root / "personal-data.json"
        visible.write_text("{}", encoding="utf-8")
        with self.assertRaises(fill_placeholders.PlaceholderError):
            fill_placeholders.validate_values_path(visible, self.root)


if __name__ == "__main__":
    unittest.main()
