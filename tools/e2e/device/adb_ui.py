#!/usr/bin/env python3
"""Small adb UI driver used by the device verification pass.

Wraps `uiautomator dump` + `input` so screenshots and taps never rely on
hardcoded pixel coordinates. Only the Python standard library and the adb
binary on PATH are required.

Examples (serial defaults to $ANDROID_SERIAL or emulator-5558):

    python tools/e2e/device/adb_ui.py tree
    python tools/e2e/device/adb_ui.py tap-text "Dang nhap"
    python tools/e2e/device/adb_ui.py tap-desc "Tim kiem"
    python tools/e2e/device/adb_ui.py type "+84900000201"
    python tools/e2e/device/adb_ui.py shot ../../docs/media/screens/out.png
    python tools/e2e/device/adb_ui.py wait-text "Tin nhan" --timeout 20
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

DEFAULT_SERIAL = os.environ.get("ANDROID_SERIAL", "emulator-5558")
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
# Characters that `adb shell input text` treats as metacharacters (AOSP Input.java).
# ``+`` is intentionally omitted: the manual login probe sends it unescaped and
# ``\+`` is dropped on the nexachat-audit image used for patch window runs.
_ADB_INPUT_METACHAR_RE = re.compile(r"[\\&;|`*{}[\]()#%'\",.:<>^$!=~\-_/]")


def escape_adb_input_text(value: str) -> str:
    """Escape a string for `adb shell input text` (spaces become ``%s``)."""
    parts: list[str] = []
    for character in value:
        if character == " ":
            parts.append("%s")
        elif _ADB_INPUT_METACHAR_RE.match(character):
            parts.append("\\" + character)
        else:
            parts.append(character)
    return "".join(parts)


def adb(serial: str, *args: str, binary: bool = False, timeout: float = 60.0):
    command = ["adb", "-s", serial, *args]
    completed = subprocess.run(
        command, capture_output=True, timeout=timeout, check=False
    )
    if completed.returncode != 0:
        message = completed.stderr.decode("utf-8", "replace").strip()
        raise RuntimeError(f"{' '.join(command)} failed: {message}")
    return completed.stdout if binary else completed.stdout.decode("utf-8", "replace")


class Node:
    def __init__(self, element: ET.Element):
        self.attrs = element.attrib
        match = BOUNDS_RE.match(self.attrs.get("bounds", ""))
        self.box = tuple(int(group) for group in match.groups()) if match else None

    @property
    def text(self) -> str:
        return self.attrs.get("text", "")

    @property
    def desc(self) -> str:
        return self.attrs.get("content-desc", "")

    @property
    def cls(self) -> str:
        return self.attrs.get("class", "")

    @property
    def clickable(self) -> bool:
        return self.attrs.get("clickable") == "true"

    @property
    def center(self) -> tuple[int, int]:
        left, top, right, bottom = self.box
        return (left + right) // 2, (top + bottom) // 2

    def matches(self, value: str, case_sensitive: bool = False) -> bool:
        haystacks = (self.text, self.desc)
        if case_sensitive:
            return any(value in hay for hay in haystacks)
        needle = value.casefold()
        return any(needle in hay.casefold() for hay in haystacks)

    def describe(self) -> str:
        parts = [self.cls.rsplit(".", 1)[-1]]
        if self.text:
            parts.append(f"text={self.text!r}")
        if self.desc:
            parts.append(f"desc={self.desc!r}")
        if self.box:
            parts.append(f"bounds={self.box}")
        parts.append("clickable" if self.clickable else "static")
        return "  ".join(parts)


class Device:
    def __init__(self, serial: str = DEFAULT_SERIAL):
        self.serial = serial

    def shell(self, *args: str) -> str:
        return adb(self.serial, "shell", *args)

    def nodes(self) -> list[Node]:
        # `uiautomator dump` fails (exit 0, no file) while the window animates,
        # so retry a few times before giving up.
        for _ in range(5):
            self.shell("uiautomator", "dump", "/sdcard/ui.xml")
            xml = adb(self.serial, "exec-out", "cat", "/sdcard/ui.xml")
            if xml.lstrip().startswith("<?xml") or xml.lstrip().startswith("<hierarchy"):
                root = ET.fromstring(xml)
                return [Node(element) for element in root.iter("node")]
            time.sleep(1.0)
        raise RuntimeError("uiautomator dump did not produce a hierarchy")

    def find(self, text: str | None = None, desc: str | None = None,
             cls: str | None = None) -> list[Node]:
        results = []
        for node in self.nodes():
            if text is not None and not node.matches(text):
                continue
            if desc is not None and not node.matches(desc):
                continue
            if cls is not None and cls not in node.cls:
                continue
            results.append(node)
        return results

    def wait(self, text: str, timeout: float = 20.0) -> Node:
        deadline = time.time() + timeout
        while time.time() < deadline:
            matches = self.find(text=text)
            if matches:
                return matches[0]
            time.sleep(0.5)
        raise TimeoutError(f"text {text!r} not found within {timeout}s")

    def tap(self, x: int, y: int) -> None:
        self.shell("input", "tap", str(x), str(y))

    def tap_node(self, node: Node) -> None:
        if not node.box:
            raise RuntimeError(f"node without bounds: {node.describe()}")
        x, y = node.center
        self.shell("input", "tap", str(x), str(y))

    def tap_text(self, text: str) -> Node:
        matches = self.find(text=text)
        if not matches:
            raise LookupError(f"text {text!r} not on screen")
        self.tap_node(matches[0])
        return matches[0]

    def tap_desc(self, desc: str) -> Node:
        matches = self.find(desc=desc)
        if not matches:
            raise LookupError(f"description {desc!r} not on screen")
        self.tap_node(matches[0])
        return matches[0]

    def type_text(self, value: str) -> None:
        # `input text` only accepts ASCII; Vietnamese input comes from seeded
        # data, never from typing in this helper.
        self.shell("input", "text", escape_adb_input_text(value))

    def screenshot(self, path: str) -> None:
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        png = adb(self.serial, "exec-out", "screencap", "-p", binary=True)
        with open(path, "wb") as handle:
            handle.write(png)

    def key(self, code: str) -> None:
        self.shell("input", "keyevent", code)

    def delete_chars(self, count: int) -> None:
        """Send `count` DEL key events in one `input` call (fast clear)."""
        self.shell("input", "keyevent", *(["KEYCODE_DEL"] * count))

    def move_end(self) -> None:
        self.shell("input", "keyevent", "KEYCODE_MOVE_END")

    def long_press(self, node: Node, duration_ms: int = 800) -> None:
        x, y = node.center
        self.shell("input", "swipe", str(x), str(y), str(x), str(y), str(duration_ms))

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> None:
        self.shell(
            "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(duration_ms)
        )

    def rotate(self, rotation: int) -> None:
        """0 = portrait, 1 = landscape-left, 2 = reverse portrait, 3 = right."""
        self.shell("settings", "put", "system", "accelerometer_rotation", "0")
        self.shell("settings", "put", "system", "user_rotation", str(rotation))


def main(argv=None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-s", "--serial", default=DEFAULT_SERIAL)
    commands = parser.add_subparsers(dest="command", required=True)

    commands.add_parser("tree")
    tap_text = commands.add_parser("tap-text")
    tap_text.add_argument("text")
    tap_desc = commands.add_parser("tap-desc")
    tap_desc.add_argument("desc")
    type_text = commands.add_parser("type")
    type_text.add_argument("value")
    shot = commands.add_parser("shot")
    shot.add_argument("path")
    wait = commands.add_parser("wait-text")
    wait.add_argument("text")
    wait.add_argument("--timeout", type=float, default=20.0)
    key = commands.add_parser("key")
    key.add_argument("code")
    long_press_text = commands.add_parser("longpress-text")
    long_press_text.add_argument("text")
    long_press_text.add_argument("--ms", type=int, default=800)
    long_press_desc = commands.add_parser("longpress-desc")
    long_press_desc.add_argument("desc")
    long_press_desc.add_argument("--ms", type=int, default=800)
    swipe = commands.add_parser("swipe")
    swipe.add_argument("coords", nargs=4, type=int)
    swipe.add_argument("--ms", type=int, default=300)
    tap_at = commands.add_parser("tap-at")
    tap_at.add_argument("coords", nargs=2, type=int)
    rotate = commands.add_parser("rotate")
    rotate.add_argument("rotation", type=int, choices=[0, 1, 2, 3])

    args = parser.parse_args(argv)
    device = Device(args.serial)

    if args.command == "tree":
        for node in device.nodes():
            if node.text or node.desc or node.clickable:
                print(node.describe())
    elif args.command == "tap-text":
        print(device.tap_text(args.text).describe())
    elif args.command == "tap-desc":
        print(device.tap_desc(args.desc).describe())
    elif args.command == "type":
        device.type_text(args.value)
    elif args.command == "shot":
        device.screenshot(args.path)
        print(args.path)
    elif args.command == "wait-text":
        print(device.wait(args.text, args.timeout).describe())
    elif args.command == "key":
        device.key(args.code)
    elif args.command == "longpress-text":
        matches = device.find(text=args.text)
        if not matches:
            raise LookupError(f"text {args.text!r} not on screen")
        device.long_press(matches[0], args.ms)
    elif args.command == "longpress-desc":
        matches = device.find(desc=args.desc)
        if not matches:
            raise LookupError(f"description {args.desc!r} not on screen")
        device.long_press(matches[0], args.ms)
    elif args.command == "swipe":
        device.swipe(*args.coords, args.ms)
    elif args.command == "tap-at":
        device.tap(*args.coords)
    elif args.command == "rotate":
        device.rotate(args.rotation)
    return 0


if __name__ == "__main__":
    sys.exit(main())
