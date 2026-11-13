#!/usr/bin/env python3
"""Android string resources, layout copy, and basic a11y hygiene checker.

Usage (repo root):
    python tools/check-android-strings.py
    python tools/check-android-strings.py --self-test

Uses only the Python standard library. Exits 0 on success, 1 when violations are found.
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
import textwrap
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

PLACEHOLDER_RE = re.compile(r"%(?:(\d+)\$)?([0-9]*\.?[0-9]*[a-zA-Z%])")
DEFAULT_RES = Path("android/app/src/main/res")
STRING_FILE_GLOB = "strings*.xml"
LAYOUT_GLOCS = ("layout", "layout-land")

# Keys where Vietnamese may equal English (brand, counters, format tokens).
IDENTICAL_LOCALE_ALLOWLIST = frozenset(
    {
        "app_name",
        "pinned_count",
        "voice_recording_duration_zero",
        "voice_duration_progress",
        "voice_zero_duration",
        "summary_badge_ai",
        "summary_citation_chip",
        "summary_citation_seq",
        "ui_remove_symbol",
        "time_duration_mm_ss",
        "complete_profile_google_chip",
        "sign_in_method_google",
    }
)

# Format-only or protocol strings that must carry translatable="false" in values/.
TECHNICAL_NAME_SUFFIXES = ("_pattern", "_format", "_separator")
TECHNICAL_NAME_SNIPPETS = ("deep_link", "meta_separator", "blur", "https")

HARDCODED_ATTR_RE = re.compile(
    r'\bandroid:(text|hint|contentDescription)\s*=\s*"([^"]+)"',
    re.IGNORECASE,
)
ICON_TAGS = frozenset(
    {
        "ImageButton",
        "com.nexachat.feature.chat.adapter.ChatActionImageButton",
    }
)

# Translatable strings with a count placeholder followed by a word should use <plurals>.
COUNT_NOUN_BODY_RE = re.compile(r"%(?:\d+\$)?d\s+[^\s<%.]+")
# Additional count copy that is not caught by the generic pattern above.
COUNT_NOUN_STRING_NAMES = frozenset(
    {
        "ask_rate_limited_retry",
        "unread_banner_format",
    }
)
COUNT_NOUN_STRING_ALLOWLIST = frozenset(
    {
        "presence_minutes_ago",
        "presence_hours_ago",
    }
)

VI_PLURAL_LOCALE_SUFFIX = "-vi"


@dataclass
class Finding:
    rule: str
    location: str
    detail: str


@dataclass
class LocaleBundle:
    folder: Path
    strings: dict[str, tuple[str, bool, str]] = field(default_factory=dict)
    plurals: dict[str, dict[str, str]] = field(default_factory=dict)
    duplicates: list[str] = field(default_factory=list)


def repo_root() -> Path:
    here = Path(__file__).resolve().parent
    return here.parent


def locate_res(root: Path, res: Path | None) -> Path:
    candidate = (root / res) if res else (root / DEFAULT_RES)
    if not candidate.is_dir():
        raise SystemExit(f"Android res dir not found: {candidate}")
    return candidate


def parse_string_files(folder: Path) -> LocaleBundle:
    bundle = LocaleBundle(folder=folder)
    if not folder.is_dir():
        return bundle
    seen: dict[str, str] = {}
    for path in sorted(folder.glob(STRING_FILE_GLOB)):
        try:
            tree = ET.parse(path)
        except ET.ParseError as exc:
            bundle.duplicates.append(f"XML parse error in {path.name}: {exc}")
            continue
        root = tree.getroot()
        for node in root.findall("string"):
            name = node.attrib.get("name", "").strip()
            if not name:
                continue
            translatable = node.attrib.get("translatable", "true").lower() != "false"
            body = "".join(node.itertext()).strip()
            if name in seen:
                bundle.duplicates.append(f"{name} in {path.name} (first in {seen[name]})")
            else:
                seen[name] = path.name
            bundle.strings[name] = (body, translatable, path.name)
        for node in root.findall("plurals"):
            name = node.attrib.get("name", "").strip()
            if not name:
                continue
            items: dict[str, str] = {}
            for item in node.findall("item"):
                qty = item.attrib.get("quantity", "").strip()
                if qty:
                    items[qty] = "".join(item.itertext()).strip()
            bundle.plurals[name] = items
    return bundle


def normalized_placeholders(text: str) -> list[str]:
    return [m.group(0) for m in PLACEHOLDER_RE.finditer(text)]


def is_technical_string(name: str, body: str, translatable: bool) -> bool:
    if translatable is False:
        return False
    if re.search(r"https?://", body):
        return True
    if name.endswith("_pattern") or "deep_link" in name:
        return True
    stripped = body.strip()
    if not stripped or "%" not in stripped:
        return False
    format_only = bool(
        re.fullmatch(r"[\s%0-9$.,·\-\–:;/]+", stripped)
        or re.fullmatch(r"%1\$[sd].*%2\$[sd]", stripped)
    )
    if not format_only:
        return False
    if name.endswith("_separator") or name.endswith("_format"):
        return True
    if any(s in name for s in TECHNICAL_NAME_SNIPPETS):
        return True
    return bool(re.fullmatch(r"[%\d$0-9a-zA-Z./\s]+", stripped) and not re.search(r"[A-Za-z]{3,}", stripped))


def compare_locales(base: LocaleBundle, locale: LocaleBundle) -> list[Finding]:
    findings: list[Finding] = []
    base_keys = set(base.strings)
    loc_keys = set(locale.strings)
    for key in sorted(base_keys - loc_keys):
        findings.append(
            Finding(
                "missing_translation",
                f"{locale.folder.name}",
                f"missing key {key} (defined in values/{base.strings[key][2]})",
            )
        )
    for key in sorted(loc_keys - base_keys):
        findings.append(
            Finding(
                "extra_translation",
                f"{locale.folder.name}",
                f"extra key {key} not present in values/",
            )
        )
    for key in sorted(base_keys & loc_keys):
        en_body, en_tr, _ = base.strings[key]
        vi_body, vi_tr, vi_file = locale.strings[key]
        if en_tr != vi_tr:
            findings.append(
                Finding(
                    "translatable_flag_mismatch",
                    f"{locale.folder.name}/{vi_file}",
                    f"{key}: values translatable={en_tr} vs locale translatable={vi_tr}",
                )
            )
        if en_tr and vi_tr and en_body == vi_body and key not in IDENTICAL_LOCALE_ALLOWLIST:
            findings.append(
                Finding(
                    "untranslated_copy",
                    f"{locale.folder.name}/{vi_file}",
                    f"{key} still matches English: {en_body!r}",
                )
            )
        if en_tr and vi_tr:
            en_ph = normalized_placeholders(en_body)
            vi_ph = normalized_placeholders(vi_body)
            if en_ph != vi_ph:
                findings.append(
                    Finding(
                        "placeholder_mismatch",
                        f"{locale.folder.name}/{vi_file}",
                        f"{key}: en {en_ph} vs {locale.folder.name} {vi_ph}",
                    )
                )
        if en_tr and not vi_tr and key in locale.strings:
            pass  # non-translatable may exist only in base
    base_pl = set(base.plurals)
    loc_pl = set(locale.plurals)
    for key in sorted(base_pl - loc_pl):
        findings.append(
            Finding("missing_plural", locale.folder.name, f"missing plurals resource {key}")
        )
    for key in sorted(loc_pl - base_pl):
        findings.append(Finding("extra_plural", locale.folder.name, f"extra plurals resource {key}"))
    for key in sorted(base_pl & loc_pl):
        en_items = base.plurals[key]
        vi_items = locale.plurals[key]
        if locale.folder.name.endswith(VI_PLURAL_LOCALE_SUFFIX):
            if set(vi_items) != {"other"}:
                continue
            if "other" not in en_items:
                findings.append(
                    Finding(
                        "plural_quantity_mismatch",
                        locale.folder.name,
                        f"{key}: EN must define other when VI does",
                    )
                )
        elif set(en_items) != set(vi_items):
            findings.append(
                Finding(
                    "plural_quantity_mismatch",
                    locale.folder.name,
                    f"{key}: quantities en={sorted(en_items)} vi={sorted(vi_items)}",
                )
            )
        for qty in sorted(set(en_items) & set(vi_items)):
            en_ph = normalized_placeholders(en_items[qty])
            vi_ph = normalized_placeholders(vi_items[qty])
            if en_ph != vi_ph:
                findings.append(
                    Finding(
                        "plural_placeholder_mismatch",
                        locale.folder.name,
                        f"{key} [{qty}]: en {en_ph} vs vi {vi_ph}",
                    )
                )
    return findings


def check_values_rules(base: LocaleBundle) -> list[Finding]:
    findings: list[Finding] = []
    for dup in base.duplicates:
        findings.append(Finding("duplicate_key", "values", dup))
    for key, (body, translatable, fname) in base.strings.items():
        if not body.strip() and translatable:
            findings.append(Finding("empty_string", f"values/{fname}", key))
        if translatable and is_technical_string(key, body, translatable):
            findings.append(
                Finding(
                    "technical_not_false",
                    f"values/{fname}",
                    f"{key} should use translatable=\"false\" ({body!r})",
                )
            )
    for key, items in base.plurals.items():
        if "other" not in items:
            findings.append(Finding("plural_missing_other", "values", key))
        if base.folder.name == "values" and "one" not in items:
            findings.append(
                Finding(
                    "plural_missing_one",
                    "values",
                    f"{key}: English plurals should define one and other",
                )
            )
    return findings


def check_locale_plural_rules(locale: LocaleBundle) -> list[Finding]:
    findings: list[Finding] = []
    if not locale.folder.name.endswith(VI_PLURAL_LOCALE_SUFFIX):
        return findings
    for key, items in locale.plurals.items():
        extras = set(items) - {"other"}
        if extras:
            findings.append(
                Finding(
                    "vi_plural_extra_quantity",
                    locale.folder.name,
                    f"{key}: Vietnamese plurals must only define other, not {sorted(extras)}",
                )
            )
    return findings


def scan_count_noun_strings(res: Path) -> list[Finding]:
    findings: list[Finding] = []
    plural_names = base_plurals_names(res)
    values = res / "values"
    for path in sorted(values.glob(STRING_FILE_GLOB)):
        try:
            tree = ET.parse(path)
        except ET.ParseError as exc:
            findings.append(
                Finding("duplicate_key", f"values/{path.name}", f"XML parse error: {exc}")
            )
            continue
        for node in tree.getroot().findall("string"):
            name = node.attrib.get("name", "").strip()
            if not name:
                continue
            translatable = node.attrib.get("translatable", "true").lower() != "false"
            if not translatable:
                continue
            body = "".join(node.itertext()).strip()
            if name in COUNT_NOUN_STRING_ALLOWLIST or name in plural_names:
                continue
            if COUNT_NOUN_BODY_RE.search(body) or name in COUNT_NOUN_STRING_NAMES:
                findings.append(
                    Finding(
                        "count_should_be_plural",
                        f"values/{path.name}",
                        f"{name} should be a <plurals> resource",
                    )
                )
    return findings


def base_plurals_names(res: Path) -> set[str]:
    names: set[str] = set()
    values = res / "values"
    if not values.is_dir():
        return names
    for path in values.glob(STRING_FILE_GLOB):
        try:
            tree = ET.parse(path)
        except ET.ParseError:
            continue
        for node in tree.getroot().findall("plurals"):
            plural_name = node.attrib.get("name", "").strip()
            if plural_name:
                names.add(plural_name)
    return names


def scan_layouts(res: Path) -> list[Finding]:
    findings: list[Finding] = []
    for sub in LAYOUT_GLOCS:
        layout_dir = res / sub
        if not layout_dir.is_dir():
            continue
        for path in sorted(layout_dir.rglob("*.xml")):
            text = path.read_text(encoding="utf-8", errors="replace")
            rel = path.relative_to(res)
            for match in HARDCODED_ATTR_RE.finditer(text):
                attr, value = match.group(1), match.group(2)
                if value.startswith("@"):
                    continue
                if attr.lower() == "text" and value in {"?", ""}:
                    continue
                findings.append(
                    Finding(
                        "hardcoded_layout_text",
                        str(rel),
                        f"android:{attr}=\"{value}\"",
                    )
                )
            for tag_match in re.finditer(r"<([\w.]+)\b[\s\S]*?>", text):
                tag_name = tag_match.group(1)
                if tag_name not in ICON_TAGS:
                    continue
                tag = tag_match.group(0)
                if 'contentDescription="@' in tag:
                    continue
                if 'android:visibility="gone"' in tag or 'visibility="gone"' in tag:
                    continue
                line = text[: tag_match.start()].count("\n") + 1
                findings.append(
                    Finding(
                        "missing_content_description",
                        f"{rel}:{line}",
                        tag_name,
                    )
                )
    return findings


def discover_locales(res: Path) -> list[str]:
    locales: list[str] = []
    for child in sorted(res.iterdir()):
        if not child.is_dir():
            continue
        if child.name == "values" or not child.name.startswith("values"):
            continue
        if any(child.glob(STRING_FILE_GLOB)):
            locales.append(child.name)
    return locales


def audit(res: Path) -> list[Finding]:
    base = parse_string_files(res / "values")
    findings = check_values_rules(base)
    findings.extend(scan_count_noun_strings(res))
    for loc_name in discover_locales(res):
        locale = parse_string_files(res / loc_name)
        findings.extend(compare_locales(base, locale))
        findings.extend(check_locale_plural_rules(locale))
        for dup in locale.duplicates:
            findings.append(Finding("duplicate_key", loc_name, dup))
    findings.extend(scan_layouts(res))
    return findings


def run_self_test() -> int:
    errors: list[str] = []
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp) / "fixture-res"
        values = root / "values"
        vi = root / "values-vi"
        layout = root / "layout"
        values.mkdir(parents=True)
        vi.mkdir(parents=True)
        layout.mkdir(parents=True)
        (values / "strings.xml").write_text(
            textwrap.dedent(
                """\
                <resources>
                    <string name="ok_key">Hello %1$s</string>
                    <string name="tech_only" translatable="false">%1$s · %2$s</string>
                    <string name="needs_false">https://example.test/join</string>
                    <string name="legacy_count">%1$d widgets</string>
                    <plurals name="good_count">
                        <item quantity="one">%d widget</item>
                        <item quantity="other">%d widgets</item>
                    </plurals>
                </resources>
                """
            ),
            encoding="utf-8",
        )
        (vi / "strings.xml").write_text(
            textwrap.dedent(
                """\
                <resources>
                    <string name="ok_key">Xin chào %1$s</string>
                    <string name="tech_only" translatable="false">%1$s · %2$s</string>
                    <string name="missing_in_vi_target">only en</string>
                    <plurals name="good_count">
                        <item quantity="one">%d bad</item>
                        <item quantity="other">%d widget</item>
                    </plurals>
                </resources>
                """
            ),
            encoding="utf-8",
        )
        (layout / "bad.xml").write_text(
            '<TextView android:text="Hardcoded" />\n'
            '<ImageButton android:src="@drawable/ic_close" />\n',
            encoding="utf-8",
        )
        findings = audit(root)
        rules = {f.rule for f in findings}
        expected = {
            "missing_translation",
            "extra_translation",
            "technical_not_false",
            "hardcoded_layout_text",
            "missing_content_description",
            "count_should_be_plural",
            "vi_plural_extra_quantity",
        }
        if not expected.issubset(rules):
            errors.append(f"self-test missing rules: {sorted(expected - rules)}")
        clean_root = Path(tmp) / "clean-res"
        clean_values = clean_root / "values"
        clean_vi = clean_root / "values-vi"
        clean_values.mkdir(parents=True)
        clean_vi.mkdir(parents=True)
        (clean_values / "strings.xml").write_text(
            textwrap.dedent(
                """\
                <resources>
                    <string name="greeting">Hi %1$s</string>
                    <string name="format_line" translatable="false">%1$s · %2$s</string>
                </resources>
                """
            ),
            encoding="utf-8",
        )
        (clean_vi / "strings.xml").write_text(
            textwrap.dedent(
                """\
                <resources>
                    <string name="greeting">Chào %1$s</string>
                    <string name="format_line" translatable="false">%1$s · %2$s</string>
                </resources>
                """
            ),
            encoding="utf-8",
        )
        clean_findings = audit(clean_root)
        if clean_findings:
            errors.append(f"clean fixture should pass, got: {clean_findings}")

    if errors:
        for err in errors:
            print(f"SELF-TEST FAIL: {err}", file=sys.stderr)
        return 1
    print("SELF-TEST PASS")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Check Android string resources and layout copy.")
    parser.add_argument(
        "--res",
        type=Path,
        default=None,
        help="Path to android res dir (default: android/app/src/main/res)",
    )
    parser.add_argument("--self-test", action="store_true", help="Run embedded fixture tests and exit.")
    args = parser.parse_args(argv[1:])
    if args.self_test:
        return run_self_test()

    root = repo_root()
    res = locate_res(root, args.res)
    findings = audit(res)
    if not findings:
        print(f"PASS: no issues in {res.relative_to(root)}")
        return 0
    for item in findings:
        print(f"{item.rule.upper()} {item.location}: {item.detail}")
    print(f"FAIL: {len(findings)} issue(s)")
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
