#!/usr/bin/env python3
"""Fill template fields in tracked Markdown without exposing user values.

Usage (from the repository root):
    python tools/fill-placeholders.py --list
    python tools/fill-placeholders.py --example
    python tools/fill-placeholders.py --values values.json [--dry-run] [--build]

The JSON file is an object whose keys are exact placeholder spellings such as
``{{GROUP_NAME}}`` and ``_name_`` and whose values are strings. Repeated legacy
underscore markers can use per-occurrence keys printed by ``--example`` (for
example ``_name_@README.md:267``); the plain marker value is their fallback.
MSSV values must match the loose ASCII pattern ``[A-Za-z0-9]{1,32}``. Values
must be non-empty for every field, contain no control characters, and contain
no ``{{`` sequence. Keep the values JSON outside the repository or in a
Git-ignored path. The generated ``values.example.json`` contains empty values
only and may be copied to an ignored ``values.json``.

The legacy underscore syntax is limited to the existing ``_name_`` and
``_fill in_`` template markers; use ``{{UPPER_SNAKE_CASE}}`` for new fields so
Markdown emphasis and code identifiers are not mistaken for placeholders.
"""
from __future__ import annotations

import argparse
import difflib
import io
import json
import os
import re
import subprocess
import sys
import tarfile
import unicodedata
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXAMPLE_PATH = "values.example.json"
BRACE_TOKEN_RE = re.compile(r"\{\{[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*\}\}")
UNDERSCORE_TOKEN_RE = re.compile(r"(?<![\w])_(?:name|fill in)_(?![\w])")
PLACEHOLDER_RE = re.compile(
    rf"(?:{BRACE_TOKEN_RE.pattern})|(?:{UNDERSCORE_TOKEN_RE.pattern})"
)
UNDERSCORE_TOKENS = {"_name_", "_fill in_"}
MSSV_VALUE_RE = re.compile(r"^[A-Za-z0-9]{1,32}$")


class PlaceholderError(Exception):
    """An actionable input or repository error safe to display to the user."""


@dataclass(frozen=True)
class Occurrence:
    token: str
    path: str
    line: int
    start: int
    end: int
    same_token_line_index: int


Inventory = dict[str, list[Occurrence]]


def _run_git(root: Path, *args: str) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        ["git", "-C", str(root), *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def tracked_markdown_paths(root: Path) -> list[str]:
    result = _run_git(root, "ls-files", "-z", "--", "*.md")
    if result.returncode != 0:
        raise PlaceholderError("Could not enumerate tracked Markdown files with git ls-files.")
    paths = []
    for raw in result.stdout.split(b"\0"):
        if not raw:
            continue
        try:
            relative = raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise PlaceholderError("A tracked Markdown path is not valid UTF-8.") from exc
        if relative.lower().endswith(".md") and (root / relative).is_file():
            paths.append(Path(relative).as_posix())
    return sorted(set(paths))


def find_placeholders(text: str, relative_path: str) -> list[Occurrence]:
    occurrences: list[Occurrence] = []
    seen_on_line: dict[tuple[str, int], int] = {}
    for line_number, line in enumerate(text.splitlines(), start=1):
        for match in PLACEHOLDER_RE.finditer(line):
            token = match.group(0)
            key = (token, line_number)
            seen_on_line[key] = seen_on_line.get(key, 0) + 1
            occurrences.append(Occurrence(
                token=token,
                path=relative_path,
                line=line_number,
                start=match.start(),
                end=match.end(),
                same_token_line_index=seen_on_line[key],
            ))
    return occurrences


def scan_working_tree(root: Path) -> tuple[Inventory, dict[str, str]]:
    inventory: Inventory = {}
    contents: dict[str, str] = {}
    for relative in tracked_markdown_paths(root):
        try:
            content = (root / relative).read_bytes().decode("utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            raise PlaceholderError(f"Could not read tracked Markdown file {relative} as UTF-8.") from exc
        contents[relative] = content
        for occurrence in find_placeholders(content, relative):
            inventory.setdefault(occurrence.token, []).append(occurrence)
    return inventory, contents


def scan_head_tree(root: Path) -> Inventory:
    """Read the tracked HEAD snapshot so a repeat run recognizes applied keys."""
    inventory: Inventory = {}
    result = _run_git(root, "archive", "--format=tar", "HEAD", "*.md")
    if result.returncode != 0:
        # A new repository may have an index but no HEAD yet.
        return inventory
    try:
        with tarfile.open(fileobj=io.BytesIO(result.stdout), mode="r:") as archive:
            for member in archive.getmembers():
                if not member.isfile() or not member.name.lower().endswith(".md"):
                    continue
                stream = archive.extractfile(member)
                if stream is None:
                    continue
                try:
                    content = stream.read().decode("utf-8")
                except UnicodeDecodeError:
                    continue
                relative = Path(member.name).as_posix()
                for occurrence in find_placeholders(content, relative):
                    inventory.setdefault(occurrence.token, []).append(occurrence)
    except tarfile.TarError as exc:
        raise PlaceholderError("Could not read the tracked Markdown snapshot from Git.") from exc
    return inventory


def selector_key(occurrence: Occurrence, token_count: int) -> str:
    if occurrence.token not in UNDERSCORE_TOKENS or token_count < 2:
        return occurrence.token
    selector = f"{occurrence.token}@{occurrence.path}:{occurrence.line}"
    if occurrence.same_token_line_index > 1:
        selector += f"#{occurrence.same_token_line_index}"
    return selector


def value_keys(inventory: Inventory) -> set[str]:
    keys: set[str] = set(inventory)
    for token, occurrences in inventory.items():
        if token in UNDERSCORE_TOKENS and len(occurrences) > 1:
            keys.update(selector_key(item, len(occurrences)) for item in occurrences)
    return keys


def _example_registry(root: Path) -> set[str]:
    path = root / EXAMPLE_PATH
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return set()
    if not isinstance(raw, dict):
        return set()
    return {key for key in raw if isinstance(key, str)}


def _resolve_value(occurrence: Occurrence, inventory: Inventory, values: dict[str, str]) -> str | None:
    token_occurrences = inventory[occurrence.token]
    selector = selector_key(occurrence, len(token_occurrences))
    specific = values.get(selector)
    if specific is not None and specific.strip():
        return specific
    base = values.get(occurrence.token)
    if base is not None and base.strip():
        return base
    return None


def _check_values(values: object) -> dict[str, str]:
    if not isinstance(values, dict):
        raise PlaceholderError("The values JSON must be an object mapping placeholder keys to strings.")
    checked: dict[str, str] = {}
    for key, value in values.items():
        if not isinstance(key, str) or not isinstance(value, str):
            raise PlaceholderError("Every values JSON entry must have a string key and string value.")
        label = json.dumps(key, ensure_ascii=False)
        if any(unicodedata.category(char) == "Cc" for char in value):
            raise PlaceholderError(f"Value for {label} contains a control character.")
        if "{{" in value:
            raise PlaceholderError(f"Value for {label} contains a nested '{{{{' sequence.")
        if "MSSV" in key.upper().split("@", 1)[0] and value.strip():
            if not MSSV_VALUE_RE.fullmatch(value):
                raise PlaceholderError(
                    f"Value for {label} must match [A-Za-z0-9]{{1,32}} (ASCII letters and digits only)."
                )
        checked[key] = value
    return checked


def _is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _is_tracked(root: Path, relative: str) -> bool:
    result = _run_git(root, "ls-files", "--error-unmatch", "--", relative)
    return result.returncode == 0


def _is_ignored(root: Path, relative: str) -> bool:
    result = _run_git(root, "check-ignore", "--quiet", "--no-index", "--", relative)
    return result.returncode == 0


def validate_values_path(path: Path, root: Path) -> Path:
    lexical = Path(os.path.abspath(path.expanduser()))
    try:
        resolved = lexical.resolve(strict=True)
    except OSError as exc:
        raise PlaceholderError("The values JSON file does not exist or cannot be accessed.") from exc

    root_resolved = root.resolve()
    in_repository_paths = [candidate for candidate in {lexical, resolved} if _is_within(candidate, root_resolved)]
    for candidate in in_repository_paths:
        relative = candidate.relative_to(root_resolved).as_posix()
        if _is_tracked(root, relative):
            raise PlaceholderError("The values JSON file is tracked by Git; move it outside the repository or use an ignored path.")
        if not _is_ignored(root, relative):
            raise PlaceholderError("The values JSON file is inside the repository but is not Git-ignored.")
    return resolved


def load_values(path: Path, root: Path) -> dict[str, str]:
    safe_path = validate_values_path(path, root)
    try:
        raw = json.loads(safe_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise PlaceholderError("The values file must be valid UTF-8 JSON.") from exc
    return _check_values(raw)


def _format_location(occurrence: Occurrence) -> str:
    return f"{occurrence.path}:{occurrence.line}"


def print_inventory(inventory: Inventory) -> int:
    total = sum(len(occurrences) for occurrences in inventory.values())
    files = {item.path for occurrences in inventory.values() for item in occurrences}
    for token in sorted(inventory):
        occurrences = inventory[token]
        print(f"{token}: {len(occurrences)}")
        for occurrence in occurrences:
            selector = ""
            if token in UNDERSCORE_TOKENS and len(occurrences) > 1:
                selector = f" -> {selector_key(occurrence, len(occurrences))}"
            print(f"  {_format_location(occurrence)}{selector}")
    print(f"Overall: {total} placeholder occurrence(s) across {len(inventory)} token(s) in {len(files)} file(s).")
    return 1 if total else 0


def _example_data(inventory: Inventory) -> dict[str, str]:
    result: dict[str, str] = {}
    for token in sorted(inventory):
        result[token] = ""
        if token in UNDERSCORE_TOKENS and len(inventory[token]) > 1:
            for occurrence in inventory[token]:
                result[selector_key(occurrence, len(inventory[token]))] = ""
    return result


def write_example(root: Path, inventory: Inventory) -> int:
    target = root / EXAMPLE_PATH
    content = json.dumps(_example_data(inventory), ensure_ascii=False, indent=2) + "\n"
    if target.exists():
        try:
            existing = target.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            raise PlaceholderError("Existing values.example.json cannot be read as UTF-8.") from exc
        if existing != content:
            raise PlaceholderError("values.example.json already exists with different content; preserved it.")
        print("values.example.json already matches the current placeholder inventory.")
        return 0
    target.write_text(content, encoding="utf-8", newline="\n")
    print(f"Wrote {EXAMPLE_PATH} with {len(_example_data(inventory))} empty value entr(y/ies).")
    print("Copy it to an ignored values.json, fill the values, then run --values values.json.")
    return 0


def validate_tokens(values: dict[str, str], current: Inventory, historical: Inventory, root: Path) -> None:
    allowed = value_keys(current) | value_keys(historical) | _example_registry(root)
    unknown = sorted(set(values) - allowed)
    if unknown:
        labels = ", ".join(json.dumps(key, ensure_ascii=False) for key in unknown)
        raise PlaceholderError("Unknown placeholder key(s): " + labels)


def validate_occurrence_values(inventory: Inventory, values: dict[str, str]) -> None:
    missing: list[str] = []
    for token in sorted(inventory):
        for occurrence in inventory[token]:
            if _resolve_value(occurrence, inventory, values) is None:
                key = selector_key(occurrence, len(inventory[token]))
                if key not in missing:
                    missing.append(key)
    if missing:
        raise PlaceholderError("Missing non-empty value(s) for: " + ", ".join(missing))


def validate_already_applied(historical: Inventory, values: dict[str, str]) -> None:
    if not historical:
        if values:
            raise PlaceholderError("No current or committed placeholders match the supplied values.")
        return
    # The working tree has no placeholders. Requiring the complete original
    # mapping makes a repeat invocation safe even when the Markdown line that
    # documented a token was also removed after the last template snapshot.
    validate_occurrence_values(historical, values)


def _updated_contents(inventory: Inventory, contents: dict[str, str], values: dict[str, str]) -> tuple[dict[str, str], dict[str, int]]:
    updated: dict[str, str] = {}
    counts: dict[str, int] = {}
    occurrences_by_file: dict[str, list[Occurrence]] = {}
    for occurrences in inventory.values():
        for occurrence in occurrences:
            occurrences_by_file.setdefault(occurrence.path, []).append(occurrence)
    for relative, original in contents.items():
        file_occurrences = occurrences_by_file.get(relative, [])
        if not file_occurrences:
            continue
        changed = original
        for occurrence in sorted(
            file_occurrences,
            key=lambda item: _absolute_offset(original, item),
            reverse=True,
        ):
            value = _resolve_value(occurrence, inventory, values)
            if value is None:
                raise PlaceholderError(f"No value supplied for a placeholder in {relative}:{occurrence.line}.")
            absolute_start = _absolute_offset(original, occurrence)
            absolute_end = absolute_start + (occurrence.end - occurrence.start)
            changed = changed[:absolute_start] + value + changed[absolute_end:]
            counts[relative] = counts.get(relative, 0) + 1
        updated[relative] = changed
    return updated, counts


def _absolute_offset(text: str, occurrence: Occurrence) -> int:
    offset = 0
    for _ in range(occurrence.line - 1):
        newline = text.find("\n", offset)
        if newline < 0:
            return len(text)
        offset = newline + 1
    return offset + occurrence.start


def _print_diff(relative: str, before: str, after: str) -> None:
    old_lines = before.splitlines(keepends=True)
    new_lines = after.splitlines(keepends=True)
    diff = difflib.unified_diff(
        old_lines,
        new_lines,
        fromfile=f"a/{relative}",
        tofile=f"b/{relative}",
    )
    for line in diff:
        sys.stdout.write(line)


def apply_values(root: Path, values: dict[str, str], dry_run: bool = False) -> int:
    current, contents = scan_working_tree(root)
    historical = scan_head_tree(root)
    validate_tokens(values, current, historical, root)

    if current:
        validate_occurrence_values(current, values)
        updated, counts = _updated_contents(current, contents, values)
    else:
        validate_already_applied(historical, values)
        print("No changes needed; all supplied values are already applied.")
        print("After applying, regenerate the report with: python tools/build-report.py")
        return 0

    if dry_run:
        for relative, after in updated.items():
            _print_diff(relative, contents[relative], after)
        total = sum(counts.values())
        print(f"DRY RUN: {total} replacement(s) in {len(counts)} file(s); no files written.")
        return 0

    for relative, after in updated.items():
        (root / relative).write_bytes(after.encode("utf-8"))
    summary = ", ".join(f"{path} ({count})" for path, count in sorted(counts.items()))
    print(f"Updated {sum(counts.values())} placeholder occurrence(s): {summary}.")
    print("Next regenerate the report with: python tools/build-report.py")
    return 0


def _build_report(root: Path) -> int:
    script = root / "tools" / "build-report.py"
    result = subprocess.run([sys.executable, str(script)], cwd=root, check=False)
    return result.returncode


def make_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="List, preview, and safely fill placeholders in tracked Markdown.",
        epilog=(
            "Values JSON schema: an object mapping exact placeholder strings to string values, "
            "for example {\"{{GROUP_NAME}}\": \"NexaChat\"}. Run --example to generate the "
            "complete empty schema. MSSV values must match [A-Za-z0-9]{1,32}. The values file "
            "must be outside the repository or Git-ignored. After a successful apply, run "
            "python tools/build-report.py to regenerate docs/report.docx."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--list", action="store_true", help="list tracked Markdown placeholders; exit 1 if any remain")
    modes.add_argument("--values", type=Path, metavar="JSON", help="ignored or external JSON object mapping exact placeholder tokens to values")
    modes.add_argument("--example", action="store_true", help="write values.example.json with all tokens and empty values")
    parser.add_argument("--dry-run", action="store_true", help="show the diff for --values without writing files")
    parser.add_argument("--build", action="store_true", help="run tools/build-report.py after a successful apply")
    return parser


def main(argv: list[str] | None = None, root: Path = ROOT) -> int:
    try:
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass
        parser = make_parser()
        args = parser.parse_args(argv)
        if args.dry_run and args.values is None:
            parser.error("--dry-run requires --values")
        if args.build and args.values is None:
            parser.error("--build requires --values")
        if args.dry_run and args.build:
            parser.error("--dry-run cannot be combined with --build")

        current, _ = scan_working_tree(root)
        if args.list:
            return print_inventory(current)
        if args.example:
            historical = scan_head_tree(root)
            combined = dict(current)
            for token, occurrences in historical.items():
                combined.setdefault(token, occurrences)
            return write_example(root, combined)

        values = load_values(args.values, root)
        result = apply_values(root, values, dry_run=args.dry_run)
        if result == 0 and args.build and not args.dry_run:
            return _build_report(root)
        return result
    except PlaceholderError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
