#!/usr/bin/env python3
"""Accessibility oracle primitives for the blocked ``accessibility-sweep`` device journey.

The sweep row stays ``BLOCKED_CAPABILITY`` until the critical-screen inventory
(``docs/quality/accessibility-inventory.md`` and
``docs/quality/a11y-critical-screens.md``, both PROPOSED) is accepted and a
TalkBack / clipped-text oracle exists. This module supplies the device-side
half of that gap without ever touching a device:

- ADB command *builders* that return ready-to-run command strings for
  enabling/disabling TalkBack, setting/reading the font scale, and
  setting/restoring the locale -- the same ``settings``/``cmd`` patterns
  ``stable_journeys`` and ``patch_window`` already use. Nothing here executes
  adb; the journey (or a human) runs the strings.
- Pure oracles over a parsed UI tree: unlabeled interactive nodes,
  sub-48dp touch targets (the app floor is ``@dimen/min_touch_target``),
  images without content-descriptions, and focus-order candidates.
  ``parse_ui_xml`` turns a ``uiautomator dump`` XML string into the same node
  dicts ``adb_ui`` builds (an element-attrib dict per node, bounds parsed with
  the identical regex); every oracle also accepts ``adb_ui.Node`` objects.

Density convention: ``density`` parameters take the *DPI* value ``adb shell
wm density`` reports (e.g. 420), converted as ``px = dp * dpi / 160``. A
160-based scale multiplier like 2.625 is rejected instead of silently
mis-scaling the floor.

Honesty note: a flat uiautomator dump cannot observe TalkBack's merged
announcements, TouchDelegate expansion, or actual swipe order. Every report
therefore carries ``heuristic_caveats``; findings are triage candidates for a
TalkBack-verified pass, not verdicts.

Decorative-id resolution: API 36 ``uiautomator`` dumps still include views the
app hid from accessibility services (``importantForAccessibility="no"``),
and the dump XML carries no attribute that identifies them -- the first
completed sweep run flagged settings ``rowIcon``/``rowChevron`` as
``missing_desc_images`` even though the app's own layout marks them
decorative. ``load_decorative_rids`` therefore scans the checked-out
``android/app/src/main/res/layout/*.xml`` once per sweep (path configurable,
default the repo's android dir) for ids the layouts mark decorative
(``importantForAccessibility="no"`` or ``contentDescription="@null"``), and
``summarize_findings`` suppresses ``missing_desc_images`` findings on those
rids, reporting the number as ``suppressed_decorative`` so every suppression
stays auditable. A missing layout dir degrades to no suppression with a
warning; the dump oracles keep running either way.

Standard library only.

Usage:
    python tools/e2e/device/a11y_oracle.py commands --serial emulator-5554
    python tools/e2e/device/a11y_oracle.py parse --xml ui.xml
    python tools/e2e/device/a11y_oracle.py sweep --xml ui.xml --density 420 --screen auto
    python tools/e2e/device/a11y_oracle.py sweep --xml ui.xml \
        --layout-dir android/app/src/main/res/layout
    python tools/e2e/device/a11y_oracle.py sweep --xml ui.xml \
        --order authTitle,phoneInput,passwordInput,primaryAction
"""

from __future__ import annotations

import argparse
import functools
import json
import os
import re
import sys
import warnings
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

DEFAULT_SERIAL = os.environ.get("ANDROID_SERIAL", "emulator-5558")
DEFAULT_MIN_DP = 48.0
DEFAULT_DENSITY_DPI = 160.0
# Below this a "density" is certainly a 160-based scale multiplier, not a DPI.
DENSITY_DPI_FLOOR = 40.0

# The repo's checked-out app layouts: the static source for decorative-rid
# resolution (the same parents[3] repo root stable_journeys.ROOT pins).
DEFAULT_LAYOUT_DIR = (
    Path(__file__).resolve().parents[3] / "android" / "app" / "src" / "main" / "res" / "layout"
)
_ANDROID_RES_NS = "http://schemas.android.com/apk/res/android"
# App-local layout ids only ("@+id/name" / "@id/name"); "@android:id/..." never matches.
_LAYOUT_ID_RE = re.compile(r"^@\+?id/([A-Za-z0-9_]+)$")

# TalkBack service components seen on Google-API emulator images. The marvin
# component is the classic one; newer images ship the accessibility package.
TALKBACK_SERVICE = (
    "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
)
TALKBACK_SERVICE_CANDIDATES = (
    TALKBACK_SERVICE,
    "com.google.android.apps.accessibility.talkback/"
    "com.google.android.apps.accessibility.talkback.TalkBackService",
)

# Identical to adb_ui.BOUNDS_RE so parsed boxes always agree with the driver.
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
_LOCALE_RE = re.compile(r"^[A-Za-z]{2,8}(?:[-_][A-Za-z0-9]{2,8})*$")
_SERVICE_RE = re.compile(r"^[A-Za-z0-9._]+/[A-Za-z0-9._]+$")

HEURISTIC_CAVEATS = (
    "unlabeled_interactive: a flat dump cannot observe TalkBack's merged descendant text; "
    "findings with non-empty descendant_labels may still be announced and need a TalkBack pass to confirm",
    "small_touch_targets: measures the dumped box in px; TouchDelegate or ViewConfiguration "
    "minimum-touch-target expansion is invisible to a dump; an axis whose box edge coincides "
    "with a containing scrollable's edge while the opposite edge is strictly inside is treated "
    "as clipped (the short side may be scrolled/hidden), so that axis is ignored and the node "
    "is listed in clipped_skipped, while any unclipped short axis is still a finding; the "
    "screen edge is not a clipping signal since a designed-flush control is indistinguishable "
    "from a clipped one, and the same designed-flush confound remains inside scrollables — "
    "confirm suspicious entries with a scrolled re-dump; framework alert buttonPanel containers "
    "whose interactive children carry the labels are listed in framework_skipped instead of "
    "being findings",
    "missing_desc_images: API 36 dumps still include views the app marked "
    "importantForAccessibility='no' and carry no attribute identifying them, so the sweep "
    "cross-references the app's own layouts: findings on ids the layouts mark decorative are "
    "suppressed and counted as suppressed_decorative, never silently dropped; TalkBack must "
    "confirm what is announced",
    "focus_order_issues: geometry and presence heuristics over a flat dump; the real traversal "
    "order needs a TalkBack-verified capture (expected_order checks presence and uniqueness, not swipe order)",
)


class A11yOracleError(Exception):
    """A command builder or oracle contract did not hold."""


# ---------------------------------------------------------------------------
# Node access -- adb_ui.Node objects and parse_ui_xml dicts both work
# ---------------------------------------------------------------------------


def _attrs(node: Any) -> dict[str, str]:
    """Returns the attribute dict of a node (``adb_ui.Node.attrs`` or a plain dict)."""
    if isinstance(node, dict):
        return node
    attrs = getattr(node, "attrs", None)
    if isinstance(attrs, dict):
        return attrs
    raise A11yOracleError(
        f"unrecognized node {node!r}: pass adb_ui.Node objects or parse_ui_xml dicts"
    )


def node_text(node: Any) -> str:
    return _attrs(node).get("text", "")


def node_desc(node: Any) -> str:
    return _attrs(node).get("content-desc", "")


def node_rid(node: Any) -> str:
    return _attrs(node).get("resource-id", "")


def rid_suffix(node: Any) -> str:
    """The resource-id suffix after ``id/`` (how stable_journeys matches rids)."""
    rid = node_rid(node)
    return rid.rsplit("/", 1)[-1] if "/" in rid else rid


def node_flag(node: Any, name: str) -> bool:
    return _attrs(node).get(name) == "true"


def node_box(node: Any) -> tuple[int, int, int, int] | None:
    """Parses ``bounds`` exactly like ``adb_ui.Node``: (left, top, right, bottom)."""
    match = BOUNDS_RE.match(_attrs(node).get("bounds", ""))
    return tuple(int(group) for group in match.groups()) if match else None  # type: ignore[return-value]


def is_interactive(node: Any) -> bool:
    """Clickable or focusable, and not disabled (the sweep's actionable set)."""
    attrs = _attrs(node)
    if attrs.get("enabled") == "false":
        return False
    return attrs.get("clickable") == "true" or attrs.get("focusable") == "true"


def box_size(box: tuple[int, int, int, int]) -> tuple[int, int]:
    return (box[2] - box[0], box[3] - box[1])


def box_contains(outer: tuple[int, int, int, int], inner: tuple[int, int, int, int]) -> bool:
    return (
        outer[0] <= inner[0] and inner[2] <= outer[2]
        and outer[1] <= inner[1] and inner[3] <= outer[3]
    )


def boxes_overlap(a: tuple[int, int, int, int], b: tuple[int, int, int, int]) -> bool:
    return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]


def px_from_dp(dp: float, density_dpi: float) -> float:
    """Converts dp to px from a ``wm density`` DPI value (px = dp * dpi / 160)."""
    if dp <= 0:
        raise A11yOracleError(f"dp must be positive, got dp={dp}")
    if density_dpi <= 0:
        raise A11yOracleError(f"density must be positive, got {density_dpi}")
    if density_dpi < DENSITY_DPI_FLOOR:
        raise A11yOracleError(
            f"density {density_dpi} looks like a 160-based scale multiplier, not a DPI; "
            "pass the value `adb shell wm density` reports (e.g. 420)"
        )
    return float(dp) * float(density_dpi) / 160.0


def infer_screen(nodes: list[Any]) -> tuple[int, int] | None:
    """Screen size from the dump's root node bounds (the usual [0,0][W,H] frame)."""
    if not nodes:
        return None
    box = node_box(nodes[0])
    if box is None:
        return None
    width, height = box_size(box)
    return (width, height) if width > 0 and height > 0 else None


def _resolve_screen(nodes: list[Any], screen: Any) -> tuple[int, int] | None:
    if screen is None:
        return None
    if isinstance(screen, str):
        if screen.strip().casefold() != "auto":
            raise A11yOracleError(
                f"screen must be 'auto', a (width, height) pair, or None; got {screen!r}"
            )
        return infer_screen(nodes)
    if isinstance(screen, (tuple, list)) and len(screen) == 2:
        width, height = screen
        for value in (width, height):
            if isinstance(value, bool) or not isinstance(value, (int, float)) or value <= 0:
                raise A11yOracleError(f"screen dimensions must be positive numbers: {screen!r}")
        return (int(width), int(height))
    raise A11yOracleError(f"screen must be 'auto', a (width, height) pair, or None; got {screen!r}")


# ---------------------------------------------------------------------------
# XML parser -- same shape as adb_ui.Device.nodes()
# ---------------------------------------------------------------------------


def parse_ui_xml(xml: str | bytes) -> list[dict[str, str]]:
    """Flattens a ``uiautomator dump`` into node dicts in document order.

    Each node dict is the element's attribute map verbatim -- the exact shape
    ``adb_ui.Node`` keeps in ``.attrs`` (``text``, ``content-desc``,
    ``resource-id``, ``class``, ``clickable``, ``focusable``, ``checked``,
    ``enabled``, ``scrollable``, ``bounds``, optional ``NAF``), so oracles can
    consume either representation. Raises on unparsable or node-less dumps.
    """
    if isinstance(xml, bytes):
        xml = xml.decode("utf-8", "replace")
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as error:
        raise A11yOracleError(f"ui dump is not parsable XML: {error}") from error
    nodes = [dict(element.attrib) for element in root.iter("node")]
    if not nodes:
        raise A11yOracleError("ui dump contains no <node> elements")
    return nodes


# ---------------------------------------------------------------------------
# Decorative-rid resolution -- the app's own layout XML, scanned once per sweep
# ---------------------------------------------------------------------------


def _android_attr(element: ET.Element, name: str) -> str | None:
    """A layout element's ``android:`` attribute (namespace optional, so
    synthetic fixtures without ``xmlns:android`` parse too)."""
    if name in element.attrib:
        return element.attrib[name]
    return element.attrib.get(f"{{{_ANDROID_RES_NS}}}{name}")


@functools.lru_cache(maxsize=8)
def load_decorative_rids(layout_dir: str | os.PathLike[str] | None = DEFAULT_LAYOUT_DIR
                         ) -> frozenset[str]:
    """Rid suffixes the app's own layouts mark decorative, for ``missing_desc_images``.

    API 36 ``uiautomator`` dumps still include views the app hid from
    accessibility services, and the dump carries no attribute that identifies
    them -- the dump alone cannot tell decoration from an unnamed control.
    This resolves that ambiguity from the only legitimate remaining signal,
    the checked-out layout XML: every ``*.xml`` under ``layout_dir`` is
    scanned for ids carrying either decorative marker
    (``importantForAccessibility="no"`` or ``contentDescription="@null"``).
    Elements without an app-local id cannot be resolved by rid and are
    skipped; platform ``@android:id/...`` values never match.

    ``layout_dir=None`` disables suppression outright; a missing or unreadable
    layout dir degrades to no suppression with a warning (the dump oracles
    keep running). Results are cached per path so a sweep scans the layouts
    once, not once per cell; tests reset with ``load_decorative_rids.cache_clear()``.
    """
    if layout_dir is None:
        return frozenset()
    path = Path(layout_dir)
    if not path.is_dir():
        warnings.warn(
            f"a11y_oracle: layout dir {path} does not exist; missing_desc_images "
            "decorative suppression is disabled",
            stacklevel=2,
        )
        return frozenset()
    rids: set[str] = set()
    for file in sorted(path.glob("*.xml")):
        try:
            root = ET.parse(file).getroot()
        except (ET.ParseError, OSError, UnicodeError) as error:
            warnings.warn(
                f"a11y_oracle: skipping unreadable layout {file.name}: {error}",
                stacklevel=2,
            )
            continue
        for element in root.iter():
            decorative = (
                _android_attr(element, "importantForAccessibility") == "no"
                or _android_attr(element, "contentDescription") == "@null"
            )
            if not decorative:
                continue
            match = _LAYOUT_ID_RE.match((_android_attr(element, "id") or "").strip())
            if match:
                rids.add(match.group(1))
    return frozenset(rids)


# ---------------------------------------------------------------------------
# ADB command builders -- strings only, nothing is executed
# ---------------------------------------------------------------------------


def _validate_serial(serial: str) -> str:
    if not isinstance(serial, str) or not serial or re.search(r"\s", serial):
        raise A11yOracleError(f"serial must be a non-empty token without whitespace, got {serial!r}")
    return serial


def adb_shell(serial: str, *args: str) -> str:
    """One complete adb invocation as a shell-ready string."""
    return " ".join(("adb", "-s", _validate_serial(serial), "shell", *args))


def _validate_locale(locale: str) -> str:
    if not isinstance(locale, str) or not _LOCALE_RE.match(locale):
        raise A11yOracleError(f"locale must look like 'vi-VN' or 'en_US', got {locale!r}")
    return locale


def _validate_scale(scale: Any) -> str:
    try:
        value = float(scale)
    except (TypeError, ValueError):
        raise A11yOracleError(f"font scale must be a number, got {scale!r}") from None
    if value <= 0:
        raise A11yOracleError(f"font scale must be positive, got {scale!r}")
    return f"{value:g}"


def enable_talkback(serial: str, service: str = TALKBACK_SERVICE) -> tuple[str, ...]:
    """Commands that turn on TalkBack via the secure settings (no UI interaction)."""
    if not isinstance(service, str) or not _SERVICE_RE.match(service):
        raise A11yOracleError(f"TalkBack service must be 'package/Component', got {service!r}")
    return (
        adb_shell(serial, "settings", "put", "secure", "enabled_accessibility_services", service),
        adb_shell(serial, "settings", "put", "secure", "accessibility_enabled", "1"),
    )


def disable_talkback(serial: str) -> tuple[str, ...]:
    """Commands that turn off every accessibility service (restore first!)."""
    return (
        adb_shell(serial, "settings", "put", "secure", "enabled_accessibility_services", '""'),
        adb_shell(serial, "settings", "put", "secure", "accessibility_enabled", "0"),
    )


def get_enabled_accessibility_services(serial: str) -> tuple[str, ...]:
    """Snapshot query: the ``enabled_accessibility_services`` secure setting."""
    return (adb_shell(serial, "settings", "get", "secure", "enabled_accessibility_services"),)


def get_accessibility_enabled(serial: str) -> tuple[str, ...]:
    """Snapshot query: the ``accessibility_enabled`` secure setting."""
    return (adb_shell(serial, "settings", "get", "secure", "accessibility_enabled"),)


def restore_talkback(serial: str, services: str | None,
                     enabled: str | None) -> tuple[str, ...]:
    """Puts back a snapshotted TalkBack state (see the get_* queries).

    A missing/``null`` snapshot clears the services list; an unreadable
    ``accessibility_enabled`` falls back to ``0`` so a swept device is never
    left with TalkBack force-enabled.
    """
    _validate_serial(serial)
    restored = services if services not in (None, "", "null") else '""'
    flag = str(enabled).strip() if str(enabled).strip() in ("0", "1") else "0"
    return (
        adb_shell(serial, "settings", "put", "secure", "enabled_accessibility_services", restored),
        adb_shell(serial, "settings", "put", "secure", "accessibility_enabled", flag),
    )


def set_font_scale(serial: str, scale: Any) -> tuple[str, ...]:
    """The font-scale variant switch used by the sweep (settings system, like
    ``stable_journeys.set_accessibility_variant``)."""
    return (adb_shell(serial, "settings", "put", "system", "font_scale", _validate_scale(scale)),)


def get_font_scale(serial: str) -> tuple[str, ...]:
    return (adb_shell(serial, "settings", "get", "system", "font_scale"),)


def restore_font_scale(serial: str, saved: str | None) -> tuple[str, ...]:
    """Restores a snapshotted ``font_scale``; an unset/``null`` snapshot is a no-op
    (the same guard ``patch_window.restore_device_settings`` uses)."""
    if saved in (None, "", "null"):
        return ()
    return set_font_scale(serial, saved)


def set_locale_hint(serial: str, locale: str) -> tuple[str, ...]:
    """Writes ``system_locales`` at the settings level. A hint, not an apply:
    a bare settings write is not guaranteed to broadcast, so the supported
    path is ``set_locale`` (``cmd locale set``) and restores go through it."""
    return (adb_shell(serial, "settings", "put", "system", "system_locales", _validate_locale(locale)),)


def set_locale(serial: str, locale: str) -> tuple[str, ...]:
    """The supported locale switch (``cmd locale set``, like the sweep variant)."""
    return (adb_shell(serial, "cmd", "locale", "set", _validate_locale(locale)),)


def get_locale(serial: str) -> tuple[str, ...]:
    """Snapshot query matching ``patch_window.settings_snapshot``'s key."""
    return (adb_shell(serial, "settings", "get", "system", "system_locales"),)


def restore_locale(serial: str, saved: str | None) -> tuple[str, ...]:
    """Restores a snapshotted locale via ``cmd locale set``; unset/``null`` is a
    no-op (the guard ``patch_window.restore_device_settings`` uses)."""
    if saved in (None, "", "null"):
        return ()
    return set_locale(serial, saved)


# ---------------------------------------------------------------------------
# Oracles -- pure functions over a parsed ui-tree node list
# ---------------------------------------------------------------------------


def _finding(issue: str, node: Any, **extra: Any) -> dict[str, Any]:
    attrs = _attrs(node)
    finding: dict[str, Any] = {
        "issue": issue,
        "class": attrs.get("class", ""),
        "rid": rid_suffix(node),
        "text": attrs.get("text", ""),
        "content_desc": attrs.get("content-desc", ""),
        "bounds": attrs.get("bounds", ""),
        "clickable": node_flag(node, "clickable"),
        "focusable": node_flag(node, "focusable"),
        "checked": node_flag(node, "checked"),
    }
    if attrs.get("NAF") == "true":
        finding["naf"] = True
    finding.update(extra)
    return finding


def _contained_labels(node: Any, nodes: list[Any]) -> list[str]:
    """Texts/descs of nodes geometrically inside this node (flat-dump proxy for
    the children TalkBack may merge into a clickable container)."""
    box = node_box(node)
    if box is None:
        return []
    labels: list[str] = []
    for other in nodes:
        if other is node:
            continue
        other_box = node_box(other)
        if other_box is None or not box_contains(box, other_box):
            continue
        for label in (node_text(other).strip(), node_desc(other).strip()):
            if label and label not in labels:
                labels.append(label)
    return labels


# Framework AlertDialog button bar: its own text is empty by design and the
# labels live on the child buttons. Exact match only -- app rids never qualify.
FRAMEWORK_LABELLED_CONTAINER_RID = "android:id/buttonPanel"


def _unlabeled_with_skipped(
    nodes: list[Any]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Returns (findings, framework_skipped) so both stay auditable in the report."""
    findings: list[dict[str, Any]] = []
    framework_skipped: list[dict[str, Any]] = []
    for node in nodes:
        if not is_interactive(node):
            continue
        if node_text(node).strip() or node_desc(node).strip():
            continue
        labels = _contained_labels(node, nodes)
        entry = _finding("unlabeled_interactive", node, descendant_labels=labels)
        # A label borrowed from a merely-overlapping window must not suppress
        # the finding: only labels on interactive contained nodes count.
        interactive_labels = [
            label
            for other in nodes
            if other is not node
            and is_interactive(other)
            and node_box(other) is not None
            and box_contains(node_box(node), node_box(other))
            for label in (node_text(other).strip(), node_desc(other).strip())
            if label
        ]
        if node_rid(node) == FRAMEWORK_LABELLED_CONTAINER_RID and interactive_labels:
            framework_skipped.append(entry)
            continue
        findings.append(entry)
    return findings, framework_skipped


def unlabeled_interactive(nodes: list[Any]) -> list[dict[str, Any]]:
    """Interactive nodes whose own text and content-desc are both empty.

    Strict by design: this app carries row semantics on the interactive node
    itself (merged bubbles, SettingsRowView, a11y delegates -- see
    docs/quality/a11y-critical-screens.md), so an unlabeled interactive node
    is a defect candidate even when a child somewhere carries text. Each
    finding lists ``descendant_labels`` so a TalkBack pass can confirm which
    ones the merge actually announces. The one exception is the framework
    ``android:id/buttonPanel`` container with labeled children: it is listed in
    the report's ``framework_skipped`` list instead of being a finding.
    """
    return _unlabeled_with_skipped(nodes)[0]


def _clipped_axes(node: Any, nodes: list[Any]) -> list[str]:
    """Axes whose box edge is cut off by a containing scrollable.

    Only scrollable containers qualify: an edge coinciding with the *screen*
    edge is just as often a designed-flush control (bottom bar, corner FAB)
    as a clipped one, and a flat dump cannot tell those apart. A scrollable
    qualifies only when it fully encloses the node (``box_contains``). An edge
    counts as clipped when it coincides with the scrollable's edge while the
    opposite edge is strictly inside -- a box that exactly fills its scrollable
    is fully visible, not clipped.

    Residual confound: a control *designed* flush with a scrollable's edge
    (e.g. an icon at a list's left edge) is still skipped on that axis; one
    flat dump cannot separate the two cases (see HEURISTIC_CAVEATS).
    """
    box = node_box(node)
    if box is None:
        return []
    left, top, right, bottom = box
    containers: list[tuple[int, int, int, int]] = []
    for other in nodes:
        if not node_flag(other, "scrollable"):
            continue
        other_box = node_box(other)
        if other_box is not None and box_contains(other_box, box):
            containers.append(other_box)
    clipped: list[str] = []
    if any((top == c[1] and bottom < c[3]) or (bottom == c[3] and top > c[1])
           for c in containers):
        clipped.append("height")
    if any((left == c[0] and right < c[2]) or (right == c[2] and left > c[0])
           for c in containers):
        clipped.append("width")
    return clipped


def _small_touch_targets_with_skipped(
    nodes: list[Any], min_dp: float, density: float
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Returns (findings, clipped_skipped) so both stay auditable in the report."""
    floor_px = px_from_dp(min_dp, density)
    findings: list[dict[str, Any]] = []
    clipped_skipped: list[dict[str, Any]] = []
    for node in nodes:
        if not is_interactive(node):
            continue
        box = node_box(node)
        if box is None:
            continue
        width, height = box_size(box)
        if width <= 0 or height <= 0:
            continue
        below = [name for name, size in (("width", width), ("height", height))
                 if size < floor_px]
        if not below:
            continue
        finding = _finding(
            "small_touch_target", node,
            width_px=width, height_px=height,
            min_dp=min_dp, density=density, floor_px=floor_px,
            below_floor=list(below),
        )
        clipped = _clipped_axes(node, nodes)
        # Only short axes are candidates for a finding, so only those can be
        # recorded as skipped (an incidental clipped edge above floor is noise).
        skipped_axes = [axis for axis in below if axis in clipped]
        unclipped = [axis for axis in below if axis not in clipped]
        if not unclipped:
            # Every short axis is clipped: informational only, never a finding.
            clipped_skipped.append({**finding, "clipped_axes": skipped_axes})
            continue
        # A clipped axis is ignored for the verdict, but the measured axes are
        # kept whole and the swallowed ones stay auditable on the finding.
        if skipped_axes:
            finding["clipped_axes"] = skipped_axes
        findings.append(finding)
    return findings, clipped_skipped


def small_touch_targets(nodes: list[Any], min_dp: float = DEFAULT_MIN_DP,
                        density: float = DEFAULT_DENSITY_DPI) -> list[dict[str, Any]]:
    """Interactive nodes whose unclipped dumped box is under ``min_dp`` in any dimension.

    ``density`` is the DPI ``adb shell wm density`` reports (e.g. 420); the
    floor in px is ``min_dp * density / 160``. Nodes without a measurable box
    (missing or zero-area bounds) are skipped here and surface in
    ``focus_order_issues`` instead. An axis cut off at a containing scrollable's
    or the screen's edge is ignored: a short side at a clip boundary is hidden
    content, not a too-small target. Nodes with every short axis clipped are
    reported in the report's ``clipped_skipped`` list, not here.
    """
    return _small_touch_targets_with_skipped(nodes, min_dp, density)[0]


def missing_desc_images(nodes: list[Any], decorative_rids: Any = None) -> list[dict[str, Any]]:
    """Image-class nodes in the dump with no content-desc (and no text).

    The old claim that decoration hidden via ``importantForAccessibility='no'``
    never appears in a dump was wrong: API 36 dumps still surface such views,
    and the dump XML carries no attribute that identifies them, so this dump
    oracle alone cannot tell decoration from an unnamed control. The
    legitimate tie-breaker is the app's own source: ``decorative_rids`` (rid
    suffixes from :func:`load_decorative_rids`, which scans the checked-out
    layout XML) are suppressed here, and ``summarize_findings`` reports how
    many findings were suppressed as ``suppressed_decorative`` so the
    suppression stays auditable. With no ``decorative_rids`` the oracle keeps
    its dump-only behavior and flags every unnamed image. A ``NAF``
    attribute on the node adds evidence.
    """
    decorative = decorative_rids or ()
    findings: list[dict[str, Any]] = []
    for node in nodes:
        simple_class = _attrs(node).get("class", "").rsplit(".", 1)[-1]
        if not (simple_class.endswith("ImageView") or simple_class.endswith("ImageButton")):
            continue
        if node_desc(node).strip() or node_text(node).strip():
            continue
        if rid_suffix(node) in decorative:
            continue
        findings.append(_finding("missing_desc_image", node))
    return findings


def _scrollable_reaches(scrollable: tuple[int, int, int, int],
                        box: tuple[int, int, int, int]) -> bool:
    """Whether a node beyond the scrollable's (and possibly the screen's) edge
    can be scrolled into view: it is fully contained, or it extends past the
    scrollable along one axis while staying inside the cross axis. List
    children dumped past a NestedScrollView/RecyclerView edge are exactly this
    case (see the a11y bounds comments in stable_journeys)."""
    h_inside = scrollable[0] <= box[0] and box[2] <= scrollable[2]
    v_inside = scrollable[1] <= box[1] and box[3] <= scrollable[3]
    if h_inside and v_inside:
        return True
    if h_inside and (box[3] > scrollable[3] or box[1] < scrollable[1]):
        return True
    if v_inside and (box[2] > scrollable[2] or box[0] < scrollable[0]):
        return True
    return False


def focus_order_issues(nodes: list[Any], *, screen: Any = None,
                        expected_order: Any = None) -> list[dict[str, Any]]:
    """Focusable nodes a TalkBack traversal may not reach, plus expected-order
    presence checks.

    Heuristics over the flat dump (see HEURISTIC_CAVEATS):
    - ``focus_no_bounds``: focusable but the box is missing or zero-area, so
      the node cannot be placed in any traversal order;
    - ``focus_off_screen``: focusable, entirely outside ``screen`` (a
      ``(width, height)`` pair or ``'auto'`` for the root node bounds), and not
      reachable from any scrollable node's box -- i.e. nothing can scroll it
      into reach;
    - with ``expected_order`` (rid suffixes in the documented TalkBack order):
      ``expected_focus_missing`` / ``expected_focus_not_focusable`` /
      ``duplicate_focus_target`` catch the per-screen orders pinned in
      docs/quality/a11y-critical-screens.md.
    """
    resolved = _resolve_screen(nodes, screen)
    screen_box = (0, 0, *resolved) if resolved is not None else None
    findings: list[dict[str, Any]] = []

    scrollable_boxes: list[tuple[int, int, int, int]] = []
    for node in nodes:
        if node_flag(node, "scrollable"):
            box = node_box(node)
            if box is not None and box_size(box)[0] > 0 and box_size(box)[1] > 0:
                scrollable_boxes.append(box)

    for node in nodes:
        if not node_flag(node, "focusable"):
            continue
        box = node_box(node)
        if box is None or box_size(box)[0] <= 0 or box_size(box)[1] <= 0:
            findings.append(_finding("focus_no_bounds", node))
            continue
        if screen_box is None:
            continue
        if boxes_overlap(box, screen_box):
            continue
        if any(_scrollable_reaches(scrollable, box) for scrollable in scrollable_boxes):
            continue
        findings.append(_finding("focus_off_screen", node, screen=list(resolved or ())))

    if expected_order:
        by_suffix: dict[str, list[Any]] = {}
        for node in nodes:
            by_suffix.setdefault(rid_suffix(node), []).append(node)
        for rid in expected_order:
            if not isinstance(rid, str) or not rid.strip():
                raise A11yOracleError(
                    f"expected_order entries must be non-empty rid suffixes, got {rid!r}"
                )
            matches = by_suffix.get(rid.strip(), [])
            focusables = [node for node in matches if node_flag(node, "focusable")]
            if not matches:
                findings.append({"issue": "expected_focus_missing", "rid": rid.strip()})
            elif not focusables:
                findings.append(
                    _finding("expected_focus_not_focusable", matches[0], expected_rid=rid.strip())
                )
            elif len(focusables) > 1:
                findings.append({
                    "issue": "duplicate_focus_target",
                    "rid": rid.strip(),
                    "count": len(focusables),
                    "bounds": [node_box(node) for node in focusables],
                })
    return findings


def summarize_findings(nodes: list[Any], *, min_dp: float = DEFAULT_MIN_DP,
                       density: float = DEFAULT_DENSITY_DPI, screen: Any = None,
                       expected_order: Any = None,
                       layout_dir: Any = DEFAULT_LAYOUT_DIR) -> dict[str, Any]:
    """Runs every oracle and folds the findings into one JSON-ready report.

    ``layout_dir`` resolves the decorative rids the app's own layouts declare
    (see :func:`load_decorative_rids`, called once per sweep via its cache):
    ``missing_desc_images`` findings on those rids are suppressed and counted
    as ``suppressed_decorative`` so the suppression stays auditable.
    ``layout_dir=None`` disables suppression outright; a missing directory
    only warns and leaves the dump-only findings in place. Skipped nodes are
    exposed as informational ``clipped_skipped`` / ``framework_skipped`` lists
    (with counts) inside their check sections; ``count``/``ok`` still describe
    ``findings`` only.
    """
    resolved = _resolve_screen(nodes, screen)
    decorative = (
        frozenset() if layout_dir is None else load_decorative_rids(layout_dir)
    )
    images_unsuppressed = missing_desc_images(nodes)
    images = (missing_desc_images(nodes, decorative_rids=decorative)
              if decorative else images_unsuppressed)
    unlabeled_findings, framework_skipped = _unlabeled_with_skipped(nodes)
    touch_findings, clipped_skipped = _small_touch_targets_with_skipped(
        nodes, min_dp=min_dp, density=density
    )
    checks: dict[str, dict[str, Any]] = {
        "unlabeled_interactive": {
            "findings": unlabeled_findings,
            "framework_skipped": framework_skipped,
            "framework_skipped_count": len(framework_skipped),
        },
        "small_touch_targets": {
            "min_dp": min_dp, "density": density,
            "floor_px": px_from_dp(min_dp, density),
            "findings": touch_findings,
            "clipped_skipped": clipped_skipped,
            "clipped_skipped_count": len(clipped_skipped),
        },
        "missing_desc_images": {
            "findings": images,
            "suppressed_decorative": len(images_unsuppressed) - len(images),
        },
        "focus_order_issues": {
            "screen": list(resolved) if resolved is not None else None,
            "expected_order": list(expected_order) if expected_order else None,
            "findings": focus_order_issues(
                nodes, screen=resolved, expected_order=expected_order
            ),
        },
    }
    for entry in checks.values():
        entry["count"] = len(entry["findings"])
        entry["ok"] = entry["count"] == 0
    return {
        "ok": all(entry["ok"] for entry in checks.values()),
        "node_count": len(nodes),
        "interactive_count": sum(1 for node in nodes if is_interactive(node)),
        "checks": checks,
        "heuristic_caveats": list(HEURISTIC_CAVEATS),
    }


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _read_xml(source: str) -> str:
    if source == "-":
        return sys.stdin.read()
    try:
        return Path(source).read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        raise A11yOracleError(f"cannot read ui dump {source}: {error}") from error


def _parse_screen_arg(value: str) -> Any:
    if value.strip().casefold() == "auto":
        return "auto"
    match = re.fullmatch(r"(\d+)[xX](\d+)", value.strip())
    if not match:
        raise A11yOracleError(f"--screen must be WxH or 'auto', got {value!r}")
    width, height = int(match.group(1)), int(match.group(2))
    if width <= 0 or height <= 0:
        raise A11yOracleError(f"--screen dimensions must be positive, got {value!r}")
    return (width, height)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("-s", "--serial", default=DEFAULT_SERIAL)
    subparsers = parser.add_subparsers(dest="command", required=True)

    commands = subparsers.add_parser(
        "commands", help="Print the adb command strings (nothing is executed)")
    commands.add_argument("--talkback-service", default=TALKBACK_SERVICE)
    commands.add_argument("--font-scale", default="2.0")
    commands.add_argument("--restore-font-scale", default="1.0")
    commands.add_argument("--locale", default="vi-VN")
    commands.add_argument("--restore-locale", default="en-US")
    commands.add_argument("--restore-talkback-services", default="")
    commands.add_argument("--restore-accessibility-enabled", default="0")

    sweep = subparsers.add_parser(
        "sweep", help="Run every oracle on a ui dump and print the JSON report")
    sweep.add_argument("--xml", required=True, help="uiautomator dump path, or - for stdin")
    sweep.add_argument("--density", type=float, default=DEFAULT_DENSITY_DPI,
                       help="Device DPI from `adb shell wm density` (e.g. 420); "
                            "a 160-based scale like 2.625 is rejected")
    sweep.add_argument("--min-dp", type=float, default=DEFAULT_MIN_DP,
                       help="Touch-target floor in dp (app floor is 48)")
    sweep.add_argument("--screen", default="auto",
                       help="'WxH' or 'auto' (root node bounds); off-screen focus check")
    sweep.add_argument("--order", default=None,
                       help="Comma-separated expected rid suffixes in TalkBack order")
    sweep.add_argument("--layout-dir", default=str(DEFAULT_LAYOUT_DIR),
                       help="App res/layout dir scanned once for ids the layouts mark "
                            "decorative (importantForAccessibility='no' / "
                            "contentDescription='@null'); a missing dir disables "
                            "missing_desc_images suppression with a warning")

    parse = subparsers.add_parser(
        "parse", help="Print a ui dump as the JSON node dicts the oracles consume")
    parse.add_argument("--xml", required=True, help="uiautomator dump path, or - for stdin")
    return parser


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = build_parser()
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])

    try:
        if args.command == "commands":
            lines = [
                *get_enabled_accessibility_services(args.serial),
                *get_accessibility_enabled(args.serial),
                *enable_talkback(args.serial, args.talkback_service),
                *disable_talkback(args.serial),
                *restore_talkback(args.serial, args.restore_talkback_services,
                                  args.restore_accessibility_enabled),
                *get_font_scale(args.serial),
                *set_font_scale(args.serial, args.font_scale),
                *restore_font_scale(args.serial, args.restore_font_scale),
                *get_locale(args.serial),
                *set_locale_hint(args.serial, args.locale),
                *set_locale(args.serial, args.locale),
                *restore_locale(args.serial, args.restore_locale),
            ]
            print("\n".join(lines))
            return 0

        if args.command == "parse":
            nodes = parse_ui_xml(_read_xml(args.xml))
            print(json.dumps(nodes, indent=2))
            return 0

        if args.command == "sweep":
            nodes = parse_ui_xml(_read_xml(args.xml))
            expected = (
                [part.strip() for part in args.order.split(",") if part.strip()]
                if args.order else None
            )
            report = summarize_findings(
                nodes, min_dp=args.min_dp, density=args.density,
                screen=_parse_screen_arg(args.screen), expected_order=expected,
                layout_dir=args.layout_dir,
            )
            print(json.dumps(report, indent=2))
            return 0 if report["ok"] else 1

        parser.print_help(sys.stderr)
        return 2
    except A11yOracleError as error:
        print(f"a11y_oracle: error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
