#!/usr/bin/env python3
"""Static accessibility scanner for Android layouts.

Scans Android layouts and values files for common accessibility defects:
  - A1: ImageView/ImageButton without contentDescription or importantForAccessibility="no".
  - A2: Clickable/focusable view with touch target under 48dp without minHeight/minWidth or padding.
  - A3: Text size set in dp or px instead of sp.
  - A4: Fixed-height TextView/Button (dp height without minHeight) that clips at 200% font scale.
  - A5: EditText without hint/labelFor/autofillHints or a TextInputLayout parent label.
  - A6: maxLines / singleLine on text that is a message, title or error (WARN).

Rules A1, A2, A3 are ERROR severity and cause non-zero exit in CI for new findings.
Rules A4, A5, A6 are WARN severity.
Baseline file support (tools/android-a11y-baseline.json) ensures legacy findings do not
break builds, while any newly introduced violations fail CI.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
import xml.parsers.expat as expat
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
APP_NS = "{http://schemas.android.com/apk/res-auto}"
TOOLS_NS = "{http://schemas.android.com/tools}"

DEFAULT_RES_DIR = Path("android/app/src/main/res")
DEFAULT_BASELINE_FILE = Path("tools/android-a11y-baseline.json")

RULE_SEVERITY: dict[str, str] = {
    "A1": "ERROR",
    "A2": "ERROR",
    "A3": "ERROR",
    "A4": "WARN",
    "A5": "WARN",
    "A6": "WARN",
}

RULE_DESCRIPTIONS: dict[str, str] = {
    "A1": "ImageView/ImageButton missing contentDescription or importantForAccessibility=\"no\"",
    "A2": "Interactive view with declared fixed touch target under 48dp",
    "A3": "Text size defined in dp or px instead of sp",
    "A4": "Fixed-height TextView/Button clipping at 200% font scale",
    "A5": "EditText missing hint, labelFor, autofillHints, or TextInputLayout parent label",
    "A6": "maxLines / singleLine on message, title, or error text",
}

A6_KEYWORDS = frozenset(
    {
        "title",
        "name",
        "header",
        "headline",
        "question",
        "subject",
        "subtitle",
        "label",
        "topic",
        "message",
        "msg",
        "body",
        "preview",
        "snippet",
        "pinned",
        "reply",
        "detail",
        "details",
        "desc",
        "description",
        "content",
        "error",
        "err",
        "warning",
        "warn",
        "alert",
    }
)


@dataclass(frozen=True)
class Finding:
    rule_id: str
    severity: str
    file_path: str
    line: int
    element_id: str | None
    tag: str
    message: str


def parse_xml_bytes(raw_bytes: bytes) -> tuple[ET.ElementTree, dict[ET.Element, int]]:
    """Parse XML bytes into an ElementTree and map each Element to its 1-based source line."""
    parser = expat.ParserCreate(namespace_separator=" ")
    stack: list[ET.Element] = []
    root: ET.Element | None = None
    line_map: dict[ET.Element, int] = {}

    def start_element(name: str, attrs: dict[str, str]) -> None:
        nonlocal root
        if " " in name:
            uri, local = name.rsplit(" ", 1)
            tag = f"{{{uri}}}{local}"
        else:
            tag = name

        attrib: dict[str, str] = {}
        for k, v in attrs.items():
            if " " in k:
                uri, local = k.rsplit(" ", 1)
                attrib[f"{{{uri}}}{local}"] = v
            else:
                attrib[k] = v

        elem = ET.Element(tag, attrib)
        line_map[elem] = parser.CurrentLineNumber
        if stack:
            stack[-1].append(elem)
        else:
            root = elem
        stack.append(elem)

    def end_element(name: str) -> None:
        if stack:
            stack.pop()

    def char_data(data: str) -> None:
        if stack:
            elem = stack[-1]
            if len(elem):
                elem[-1].tail = (elem[-1].tail or "") + data
            else:
                elem.text = (elem.text or "") + data

    parser.StartElementHandler = start_element
    parser.EndElementHandler = end_element
    parser.CharacterDataHandler = char_data
    parser.Parse(raw_bytes, True)

    if root is None:
        root = ET.Element("empty")
    return ET.ElementTree(root), line_map


def parse_xml_file(path: Path) -> tuple[ET.ElementTree, dict[ET.Element, int]]:
    """Parse an XML file into an ElementTree and line number mapping."""
    return parse_xml_bytes(path.read_bytes())


class DimenResolver:
    """Loads and resolves dimension and style tokens across Android values directories."""

    def __init__(self, res_dir: Path | None = None):
        self.dimens: dict[str, str] = {}
        self.styles: dict[str, dict[str, str]] = {}
        if res_dir and res_dir.is_dir():
            self.load_from_res(res_dir)

    def load_from_res(self, res_dir: Path) -> None:
        """Scan values*/{dimens,styles,themes}*.xml and all values xml for dimens and text appearance."""
        for path in sorted(res_dir.glob("values*/*.xml")):
            try:
                tree, _ = parse_xml_file(path)
            except Exception:
                continue
            root = tree.getroot()
            for d in root.findall("dimen"):
                name = d.attrib.get("name")
                if name:
                    self.dimens[name] = (d.text or "").strip()
            for s in root.findall("style"):
                s_name = s.attrib.get("name")
                if not s_name:
                    continue
                style_items: dict[str, str] = {}
                for item in s.findall("item"):
                    i_name = item.attrib.get("name", "")
                    if i_name.startswith("android:"):
                        i_name = i_name[8:]
                    style_items[i_name] = (item.text or "").strip()
                self.styles[s_name] = style_items

    def add_dimen(self, name: str, value: str) -> None:
        """Add an explicit dimension definition (useful for testing)."""
        self.dimens[name] = value

    def add_style(self, name: str, items: dict[str, str]) -> None:
        """Add an explicit style definition (useful for testing)."""
        self.styles[name] = items

    def resolve_dimen(self, val: str | None, visited: set[str] | None = None) -> str | None:
        """Recursively resolve @dimen/reference to concrete dimension string."""
        if not val:
            return None
        val = val.strip()
        if val.startswith("@dimen/"):
            name = val[7:]
            if visited is None:
                visited = set()
            if name in visited:
                return None
            visited.add(name)
            target = self.dimens.get(name)
            return self.resolve_dimen(target, visited) if target else None
        return val

    def parse_dp(self, val: str | None) -> float | None:
        """Parse value into dp float, resolving dimens. Returns None for wrap/match/unrecognized."""
        resolved = self.resolve_dimen(val)
        if not resolved:
            return None
        m = re.match(r"^([+-]?\d+(?:\.\d+)?)dp$", resolved)
        if m:
            return float(m.group(1))
        if resolved in ("0dp", "0"):
            return 0.0
        return None

    def parse_sp(self, val: str | None) -> float | None:
        """Parse value into sp float, resolving dimens."""
        resolved = self.resolve_dimen(val)
        if not resolved:
            return None
        m = re.match(r"^([+-]?\d+(?:\.\d+)?)sp$", resolved)
        if m:
            return float(m.group(1))
        return None

    def resolve_text_size_sp(self, elem: ET.Element) -> float:
        """Determine text size in sp from android:textSize or textAppearance, defaulting to 14sp."""
        ts = elem.attrib.get(f"{ANDROID_NS}textSize")
        sp = self.parse_sp(ts)
        if sp is not None:
            return sp

        appear = elem.attrib.get(f"{ANDROID_NS}textAppearance") or elem.attrib.get(f"{APP_NS}textAppearance")
        if appear:
            sname = appear.split("/")[-1].replace("?attr/", "")
            for candidate in (
                sname,
                f"TextAppearance.NexaChat.{sname}",
                f"TextAppearance.Material3.{sname}",
                f"Widget.NexaChat.{sname}",
            ):
                if candidate in self.styles and "textSize" in self.styles[candidate]:
                    style_sp = self.parse_sp(self.styles[candidate]["textSize"])
                    if style_sp is not None:
                        return style_sp
        return 14.0


class Baseline:
    """Manages baseline findings to suppress existing legacy findings in CI."""

    def __init__(self, entries: list[dict[str, Any]] | None = None):
        self.entries = list(entries or [])

    @classmethod
    def load(cls, path: Path) -> Baseline:
        if not path.is_file():
            return cls([])
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            return cls(data.get("findings", []))
        except Exception as exc:
            print(f"Warning: Failed to load baseline from {path}: {exc}", file=sys.stderr)
            return cls([])

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        data = {
            "version": 1,
            "total_findings": len(self.entries),
            "findings": self.entries,
        }
        path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

    @classmethod
    def from_findings(cls, findings: list[Finding]) -> Baseline:
        entries = []
        for f in findings:
            entries.append(
                {
                    "rule": f.rule_id,
                    "severity": f.severity,
                    "file": f.file_path,
                    "line": f.line,
                    "element_id": f.element_id,
                    "tag": f.tag,
                    "message": f.message,
                }
            )
        return cls(entries)

    def partition(self, findings: list[Finding]) -> tuple[list[Finding], list[Finding]]:
        """Splits findings into (suppressed_findings, new_findings)."""
        available_with_id: dict[tuple[str, str, str], int] = {}
        available_without_id: dict[tuple[str, str, str], int] = {}

        for entry in self.entries:
            rule = entry.get("rule", "")
            file_ = entry.get("file", "")
            eid = entry.get("element_id")
            tag = entry.get("tag", "")
            if eid:
                key = (rule, file_, eid)
                available_with_id[key] = available_with_id.get(key, 0) + 1
            else:
                key_no_id = (rule, file_, tag)
                available_without_id[key_no_id] = available_without_id.get(key_no_id, 0) + 1

        suppressed: list[Finding] = []
        new_findings: list[Finding] = []

        for f in findings:
            if f.element_id:
                key = (f.rule_id, f.file_path, f.element_id)
                if available_with_id.get(key, 0) > 0:
                    available_with_id[key] -= 1
                    suppressed.append(f)
                    continue
            else:
                key_no_id = (f.rule_id, f.file_path, f.tag)
                if available_without_id.get(key_no_id, 0) > 0:
                    available_without_id[key_no_id] -= 1
                    suppressed.append(f)
                    continue
            new_findings.append(f)

        return suppressed, new_findings


def scan_layout_tree(
    tree: ET.ElementTree,
    line_map: dict[ET.Element, int],
    file_path: str,
    resolver: DimenResolver,
) -> list[Finding]:
    """Scan an XML layout tree against rules A1..A6."""
    findings: list[Finding] = []
    root = tree.getroot()

    label_for_targets: set[str] = set()
    for e in root.iter():
        lf = e.attrib.get(f"{ANDROID_NS}labelFor")
        if lf:
            label_for_targets.add(lf.split("/")[-1])

    parent_map: dict[ET.Element, ET.Element] = {c: p for p in root.iter() for c in p}

    for elem in root.iter():
        tag = elem.tag.split("}")[-1]
        line = line_map.get(elem, 1)
        eid = elem.attrib.get(f"{ANDROID_NS}id")
        raw_id = eid.split("/")[-1] if eid else ""

        # --- Rule A1 ---
        # ImageView/ImageButton without contentDescription that is not importantForAccessibility="no"
        is_image = tag in ("ImageView", "ImageButton") or tag.endswith("ImageView") or tag.endswith("ImageButton")
        if is_image:
            cd = elem.attrib.get(f"{ANDROID_NS}contentDescription")
            ifa = elem.attrib.get(f"{ANDROID_NS}importantForAccessibility")
            if (cd is None or cd == "" or cd == "@null") and ifa not in ("no", "noHideDescendants"):
                findings.append(
                    Finding(
                        rule_id="A1",
                        severity=RULE_SEVERITY["A1"],
                        file_path=file_path,
                        line=line,
                        element_id=eid,
                        tag=tag,
                        message=f'{tag} without contentDescription and importantForAccessibility is not "no"',
                    )
                )

        # --- Rule A2 ---
        # clickable/focusable view with declared fixed layout_width/layout_height under 48dp
        # and no minHeight/minWidth or padding that makes up 48dp (resolve @dimen references)
        c = elem.attrib.get(f"{ANDROID_NS}clickable")
        f = elem.attrib.get(f"{ANDROID_NS}focusable")
        is_button = tag in ("Button", "ImageButton") or tag.endswith("Button") or tag.endswith("ImageButton")
        is_interactive = (c == "true" or f == "true" or is_button)
        if c == "false" and f == "false":
            is_interactive = False

        if is_interactive:
            w_str = elem.attrib.get(f"{ANDROID_NS}layout_width")
            h_str = elem.attrib.get(f"{ANDROID_NS}layout_height")
            w_dp = resolver.parse_dp(w_str)
            h_dp = resolver.parse_dp(h_str)

            min_w = resolver.parse_dp(elem.attrib.get(f"{ANDROID_NS}minWidth")) or 0.0
            min_h = resolver.parse_dp(elem.attrib.get(f"{ANDROID_NS}minHeight")) or 0.0

            pad = resolver.parse_dp(elem.attrib.get(f"{ANDROID_NS}padding"))
            pad_h = resolver.parse_dp(elem.attrib.get(f"{ANDROID_NS}paddingHorizontal"))
            pad_v = resolver.parse_dp(elem.attrib.get(f"{ANDROID_NS}paddingVertical"))
            pad_start = (
                resolver.parse_dp(elem.attrib.get(f"{ANDROID_NS}paddingStart"))
                or resolver.parse_dp(elem.attrib.get(f"{ANDROID_NS}paddingLeft"))
                or 0.0
            )
            pad_end = (
                resolver.parse_dp(elem.attrib.get(f"{ANDROID_NS}paddingEnd"))
                or resolver.parse_dp(elem.attrib.get(f"{ANDROID_NS}paddingRight"))
                or 0.0
            )
            pad_top = resolver.parse_dp(elem.attrib.get(f"{ANDROID_NS}paddingTop")) or 0.0
            pad_bottom = resolver.parse_dp(elem.attrib.get(f"{ANDROID_NS}paddingBottom")) or 0.0

            eff_pad_w = (2 * pad_h) if pad_h is not None else ((2 * pad) if pad is not None else (pad_start + pad_end))
            eff_pad_h = (2 * pad_v) if pad_v is not None else ((2 * pad) if pad is not None else (pad_top + pad_bottom))

            w_violates = False
            if w_dp is not None and 0 < w_dp < 48:
                if max(w_dp + eff_pad_w, min_w) < 48:
                    w_violates = True

            h_violates = False
            if h_dp is not None and 0 < h_dp < 48:
                if max(h_dp + eff_pad_h, min_h) < 48:
                    h_violates = True

            if w_violates or h_violates:
                dims = []
                if w_violates:
                    dims.append(f"width={w_str} (effective {w_dp + eff_pad_w:.0f}dp < 48dp)")
                if h_violates:
                    dims.append(f"height={h_str} (effective {h_dp + eff_pad_h:.0f}dp < 48dp)")
                findings.append(
                    Finding(
                        rule_id="A2",
                        severity=RULE_SEVERITY["A2"],
                        file_path=file_path,
                        line=line,
                        element_id=eid,
                        tag=tag,
                        message=f"Touch target under 48dp without compensating padding/minDimension: {', '.join(dims)}",
                    )
                )

        # --- Rule A3 ---
        # text size set in dp or px instead of sp
        ts_attr = elem.attrib.get(f"{ANDROID_NS}textSize")
        if ts_attr:
            resolved_ts = resolver.resolve_dimen(ts_attr)
            if resolved_ts and (resolved_ts.endswith("dp") or resolved_ts.endswith("px")):
                findings.append(
                    Finding(
                        rule_id="A3",
                        severity=RULE_SEVERITY["A3"],
                        file_path=file_path,
                        line=line,
                        element_id=eid,
                        tag=tag,
                        message=f"textSize set in dp or px ({ts_attr}) instead of sp",
                    )
                )

        # --- Rule A4 ---
        # fixed-height TextView/Button (dp height without minHeight) that clips at 200% font scale
        is_tv_or_btn = (
            tag in ("TextView", "Button") or tag.endswith("TextView") or tag.endswith("Button")
        ) and not ("EditText" in tag or tag == "ImageButton" or tag.endswith("ImageButton"))
        if is_tv_or_btn:
            h_str = elem.attrib.get(f"{ANDROID_NS}layout_height")
            h_dp = resolver.parse_dp(h_str)
            min_h = resolver.parse_dp(elem.attrib.get(f"{ANDROID_NS}minHeight"))
            if h_dp is not None and h_dp > 0 and (min_h is None or min_h < h_dp):
                ts_sp = resolver.resolve_text_size_sp(elem)
                scaled_ts = 2.0 * ts_sp
                pad_v = (
                    resolver.parse_dp(elem.attrib.get(f"{ANDROID_NS}paddingVertical"))
                    or resolver.parse_dp(elem.attrib.get(f"{ANDROID_NS}padding"))
                    or 0.0
                ) * 2
                pad_top = resolver.parse_dp(elem.attrib.get(f"{ANDROID_NS}paddingTop")) or 0.0
                pad_bottom = resolver.parse_dp(elem.attrib.get(f"{ANDROID_NS}paddingBottom")) or 0.0
                eff_pad_v = pad_v if pad_v > 0 else (pad_top + pad_bottom)

                lines_count = 1
                lines_attr = elem.attrib.get(f"{ANDROID_NS}lines") or elem.attrib.get(f"{ANDROID_NS}maxLines")
                if lines_attr and lines_attr.isdigit():
                    lines_count = max(1, int(lines_attr))

                needed_h = (lines_count * scaled_ts * 1.15) + eff_pad_v
                if h_dp < needed_h:
                    findings.append(
                        Finding(
                            rule_id="A4",
                            severity=RULE_SEVERITY["A4"],
                            file_path=file_path,
                            line=line,
                            element_id=eid,
                            tag=tag,
                            message=f"Fixed height {h_str} ({h_dp:.0f}dp) clips at 200% font scale (requires at least {needed_h:.0f}dp)",
                        )
                    )

        # --- Rule A5 ---
        # EditText without hint/labelFor/autofillHints or a TextInputLayout parent label
        if "EditText" in tag:
            hint = elem.attrib.get(f"{ANDROID_NS}hint") or elem.attrib.get(f"{APP_NS}hint")
            autofill = elem.attrib.get(f"{ANDROID_NS}autofillHints")
            self_lf = elem.attrib.get(f"{ANDROID_NS}labelFor")
            has_label_for = (raw_id in label_for_targets) if raw_id else False

            parent = parent_map.get(elem)
            parent_tag = parent.tag.split("}")[-1] if parent is not None else ""
            parent_hint = None
            if parent is not None and "TextInputLayout" in parent_tag:
                parent_hint = (
                    parent.attrib.get(f"{ANDROID_NS}hint")
                    or parent.attrib.get(f"{APP_NS}hint")
                    or parent.attrib.get(f"{APP_NS}placeholderText")
                    or parent.attrib.get(f"{ANDROID_NS}labelFor")
                )

            has_label = bool(hint or autofill or self_lf or has_label_for or parent_hint)
            if not has_label:
                findings.append(
                    Finding(
                        rule_id="A5",
                        severity=RULE_SEVERITY["A5"],
                        file_path=file_path,
                        line=line,
                        element_id=eid,
                        tag=tag,
                        message="EditText missing hint, labelFor, autofillHints, or TextInputLayout parent label",
                    )
                )

        # --- Rule A6 ---
        # maxLines / singleLine on text that is a message, title or error (list as WARN: may clip at large font)
        if "TextView" in tag and not ("EditText" in tag):
            ml = elem.attrib.get(f"{ANDROID_NS}maxLines")
            sl = elem.attrib.get(f"{ANDROID_NS}singleLine")
            if ml is not None or sl == "true":
                lower_id = raw_id.lower()
                text = elem.attrib.get(f"{ANDROID_NS}text", "").lower()
                app_text = elem.attrib.get(f"{APP_NS}text", "").lower()
                appear = (elem.attrib.get(f"{ANDROID_NS}textAppearance") or "").lower()

                is_target = any(kw in lower_id for kw in A6_KEYWORDS)
                if not is_target:
                    is_target = any(
                        kw in text or kw in app_text for kw in ("title", "message", "error", "header", "preview")
                    )
                if not is_target:
                    is_target = any(kw in appear for kw in ("title", "headline", "error"))

                if is_target:
                    lim = f"maxLines={ml}" if ml is not None else "singleLine=true"
                    findings.append(
                        Finding(
                            rule_id="A6",
                            severity=RULE_SEVERITY["A6"],
                            file_path=file_path,
                            line=line,
                            element_id=eid,
                            tag=tag,
                            message=f"{lim} on message/title/error text may clip at large font",
                        )
                    )

    return findings


def scan_values_file(path: Path, res_dir: Path, resolver: DimenResolver) -> list[Finding]:
    """Scan a values file (e.g. styles, themes) for static a11y violations like textSize in dp/px."""
    findings: list[Finding] = []
    try:
        tree, line_map = parse_xml_file(path)
    except Exception:
        return findings

    rel_path = path.relative_to(res_dir).as_posix()
    root = tree.getroot()

    for s in root.findall("style"):
        s_line = line_map.get(s, 1)
        s_name = s.attrib.get("name")
        for item in s.findall("item"):
            i_name = item.attrib.get("name", "")
            if i_name in ("android:textSize", "textSize"):
                i_val = (item.text or "").strip()
                resolved = resolver.resolve_dimen(i_val)
                if resolved and (resolved.endswith("dp") or resolved.endswith("px")):
                    i_line = line_map.get(item, s_line)
                    findings.append(
                        Finding(
                            rule_id="A3",
                            severity=RULE_SEVERITY["A3"],
                            file_path=rel_path,
                            line=i_line,
                            element_id=s_name,
                            tag="style:item",
                            message=f"textSize in style defined in dp or px ({i_val}) instead of sp",
                        )
                    )
    return findings


def scan_repository(
    res_dir: Path,
) -> tuple[list[Finding], DimenResolver]:
    """Scan all layout*/**/*.xml and res/values*/{dimens,styles,themes}.xml in res_dir."""
    resolver = DimenResolver(res_dir)
    all_findings: list[Finding] = []

    # 1. Scan layouts
    for layout_path in sorted(res_dir.glob("layout*/**/*.xml")):
        rel = layout_path.relative_to(res_dir).as_posix()
        try:
            tree, line_map = parse_xml_file(layout_path)
            findings = scan_layout_tree(tree, line_map, rel, resolver)
            all_findings.extend(findings)
        except Exception as exc:
            print(f"Error reading layout {layout_path}: {exc}", file=sys.stderr)

    # 2. Scan values*/{dimens,styles,themes}*.xml
    for values_path in sorted(res_dir.glob("values*/*.xml")):
        name = values_path.name.lower()
        if any(name.startswith(p) for p in ("dimen", "style", "theme")):
            findings = scan_values_file(values_path, res_dir, resolver)
            all_findings.extend(findings)

    return all_findings, resolver


def format_table_report(
    findings: list[Finding],
    suppressed: list[Finding],
    new_findings: list[Finding],
) -> str:
    """Format table output per layout file, summary per rule, and top 10 files."""
    lines: list[str] = []

    # Group all findings by file
    files_map: dict[str, list[Finding]] = {}
    for f in findings:
        files_map.setdefault(f.file_path, []).append(f)

    lines.append("=" * 80)
    lines.append(" Android Static Accessibility Layout Scan")
    lines.append("=" * 80)

    if not findings:
        lines.append("No accessibility violations found.")
    else:
        for file_path in sorted(files_map.keys()):
            file_findings = sorted(files_map[file_path], key=lambda x: x.line)
            lines.append(f"\nLayout: {file_path}")
            lines.append("-" * 80)
            lines.append(f"{'Line':<6} {'Rule':<5} {'Severity':<9} {'Element ID':<26} {'Message'}")
            lines.append("-" * 80)
            for f in file_findings:
                eid_str = (f.element_id or "<none>")[:25]
                lines.append(f"{f.line:<6} {f.rule_id:<5} {f.severity:<9} {eid_str:<26} {f.message}")

    lines.append("\n" + "=" * 80)
    lines.append(" Scan Summary per Rule")
    lines.append("-" * 80)
    lines.append(f"{'Rule':<6} {'Severity':<9} {'Total':<7} {'New':<6} {'Description'}")
    lines.append("-" * 80)

    total_counts: dict[str, int] = {}
    new_counts: dict[str, int] = {}
    for f in findings:
        total_counts[f.rule_id] = total_counts.get(f.rule_id, 0) + 1
    for f in new_findings:
        new_counts[f.rule_id] = new_counts.get(f.rule_id, 0) + 1

    for rule_id in sorted(RULE_SEVERITY.keys()):
        sev = RULE_SEVERITY[rule_id]
        tot = total_counts.get(rule_id, 0)
        new_c = new_counts.get(rule_id, 0)
        desc = RULE_DESCRIPTIONS.get(rule_id, "")
        lines.append(f"{rule_id:<6} {sev:<9} {tot:<7} {new_c:<6} {desc}")

    lines.append("-" * 80)
    total_findings_count = len(findings)
    suppressed_count = len(suppressed)
    new_findings_count = len(new_findings)
    new_error_count = sum(1 for f in new_findings if f.severity == "ERROR")
    new_warn_count = sum(1 for f in new_findings if f.severity == "WARN")

    lines.append(f"Total findings in tree: {total_findings_count}")
    lines.append(f"Suppressed by baseline: {suppressed_count}")
    lines.append(f"New findings:           {new_findings_count} ({new_error_count} ERROR, {new_warn_count} WARN)")

    # Top 10 files table
    if findings:
        lines.append("\n" + "=" * 80)
        lines.append(" Top 10 Files by Findings Count")
        lines.append("-" * 80)
        lines.append(f"{'Rank':<5} {'Count':<7} {'File'}")
        lines.append("-" * 80)
        file_counts = sorted(
            [(fp, len(items)) for fp, items in files_map.items()],
            key=lambda x: (-x[1], x[0]),
        )
        for idx, (fp, count) in enumerate(file_counts[:10], start=1):
            lines.append(f"{idx:<5} {count:<7} {fp}")

    lines.append("=" * 80)
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Static Android Accessibility Layout Scanner")
    parser.add_argument(
        "--res-dir",
        type=Path,
        default=DEFAULT_RES_DIR,
        help=f"Path to Android res directory (default: {DEFAULT_RES_DIR})",
    )
    parser.add_argument(
        "--baseline",
        type=Path,
        default=DEFAULT_BASELINE_FILE,
        help=f"Path to baseline JSON file (default: {DEFAULT_BASELINE_FILE})",
    )
    parser.add_argument(
        "--generate-baseline",
        action="store_true",
        help="Generate baseline JSON file from current scan and exit 0",
    )
    parser.add_argument(
        "--no-baseline",
        action="store_true",
        help="Ignore baseline file and report all findings as new",
    )
    parser.add_argument(
        "--format",
        choices=["table", "json"],
        default="table",
        help="Output format: table or json (default: table)",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run self-test suite and exit",
    )

    args = parser.parse_args(argv)

    if args.self_test:
        import unittest
        loader = unittest.TestLoader()
        suite = loader.discover(str(Path(__file__).resolve().parent), pattern="test_check_android_a11y.py")
        runner = unittest.TextTestRunner(verbosity=2)
        result = runner.run(suite)
        return 0 if result.wasSuccessful() else 1

    if not args.res_dir.is_dir():
        print(f"Error: Android res directory not found at {args.res_dir}", file=sys.stderr)
        return 2

    findings, _ = scan_repository(args.res_dir)

    if args.generate_baseline:
        baseline = Baseline.from_findings(findings)
        baseline.save(args.baseline)
        print(f"Generated baseline with {len(findings)} findings at {args.baseline}")
        return 0

    if args.no_baseline or not args.baseline.is_file():
        suppressed: list[Finding] = []
        new_findings = findings
    else:
        baseline = Baseline.load(args.baseline)
        suppressed, new_findings = baseline.partition(findings)

    if args.format == "json":
        output = {
            "total_findings": len(findings),
            "suppressed_count": len(suppressed),
            "new_count": len(new_findings),
            "new_error_count": sum(1 for f in new_findings if f.severity == "ERROR"),
            "new_warn_count": sum(1 for f in new_findings if f.severity == "WARN"),
            "findings": [asdict(f) for f in findings],
            "new_findings": [asdict(f) for f in new_findings],
        }
        print(json.dumps(output, indent=2))
    else:
        report = format_table_report(findings, suppressed, new_findings)
        print(report)

    # Exit 1 only for ERROR rules A1, A2, A3 among new findings
    new_error_count = sum(1 for f in new_findings if f.severity == "ERROR")
    if new_error_count > 0:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
