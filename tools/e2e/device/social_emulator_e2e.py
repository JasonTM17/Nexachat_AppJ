#!/usr/bin/env python3
"""Real Android emulator (emulator-5554) deep E2E & integration verifier for NexaChat Social.

Runs against an isolated DisposableStack (fresh Postgres DB + random 18xxx port +
adb reverse tcp:8080 tcp:<app_port>) so it never collides with web-e2e-verifier
or the shared development database.

Covers 5 end-to-end journeys on emulator-5554:
  1. Entry & Design System (Sign in -> Inbox -> Feed, 64dp toolbar, Light/Dark
     Tonal Plum, >=48dp touch targets & contentDescriptions, VI + EN parity)
  2. Create Text, Photo & MP4 Video Posts + Playback (Glide, Media3 ExoPlayer,
     HTTP Range 206 verification, zero ExoPlayer/AndroidRuntime errors)
  3. Multi-Emoji Reactions, Comments, Edit & State Sync (picker + pill tap,
     PostDetailFragment pill composer, comment create/edit/delete, post edit,
     immediate PublicFeedFragment state sync)
  4. Search, Author Profile Sheet & Social -> Chat/Call Handoff (REST seed User B,
     Vietnamese accent-insensitive search, Author Profile BottomSheet + postCount,
     Filter posts chip, Message -> 1:1 Chat + Call entry + Inbox row, Report
     Post/Comment sheet, Delete own post + snackbar)
  5. Runtime Flag Gate (Admin API toggles videoUploadsEnabled=false -> graceful
     Android compose disablement without crash -> restore videoUploadsEnabled=true)

Stdlib + existing tools/e2e helpers only.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Iterable, Sequence

_THIS_DIR = Path(__file__).resolve().parent
_E2E_DIR = _THIS_DIR.parent
_REPO_ROOT = _THIS_DIR.parents[2]
for _p in (str(_THIS_DIR), str(_E2E_DIR)):
    if _p not in sys.path:
        sys.path.insert(0, _p)

import a11y_oracle  # noqa: E402
import adb_ui  # noqa: E402
import disposable_stack as ds  # noqa: E402
from e2e_common import Client, E2EFailure, check, direct_conversation  # noqa: E402
import media_oracle  # noqa: E402

SERIAL = "emulator-5554"
PACKAGE = "com.nexachat.debug"
MAIN_ACTIVITY = "com.nexachat.MainActivity"
EMULATOR_LOOPBACK_PORT = 8080
EMULATOR_API_BASE_URL = f"http://127.0.0.1:{EMULATOR_LOOPBACK_PORT}"
EMULATOR_STACK_PORT_RANGE = (18700, 18999)

USER_A = {"phone": "+84900000701", "name": "An Nguyen"}
USER_B = {"phone": "+84900000702", "name": "Binh Tran"}
ADMIN_USER = {"phone": "+84900000990", "name": "Social Admin"}
ADMIN_PHONES_CSV = ADMIN_USER["phone"]
SEED_PASSWORD = ds.SEED_PASSWORD

REQUIRED_SCREENSHOTS = {
    "feed_light": ("feed_light.png", "Public Feed in Tonal Plum Light mode"),
    "feed_dark": ("feed_dark.png", "Public Feed in Tonal Plum Dark mode"),
    "video_post_playing": ("video_post_playing.png", "MP4 video post playing via Media3 ExoPlayer"),
    "post_detail_edited_comment": ("post_detail_edited_comment.png", "Post Detail with edited comment badge"),
    "author_profile_sheet": ("author_profile_sheet.png", "Author Profile BottomSheet with postCount"),
    "social_chat_handoff": ("social_chat_handoff.png", "Social to 1:1 Chat handoff with User B"),
}

EDITED_BADGE_CANDIDATES = ("(edited)", "(đã chỉnh sửa)")
FEED_ENTRY_LABELS = ("Public feed", "Bảng tin công khai", "Public Feed", "Bảng tin")
MESSAGE_AUTHOR_LABELS = ("Message", "Message author", "Nhắn tin", "Nhắn tin cho tác giả")
FILTER_POSTS_LABELS = ("Filter posts by author", "Lọc bài viết theo tác giả", "Filter posts")
REPORT_LABELS = ("Report", "Báo cáo")
REPORT_SENT_LABELS = ("Report sent", "Đã gửi báo cáo")
EDIT_LABELS = ("Edit", "Chỉnh sửa")
DELETE_POST_LABELS = ("Delete post", "Xoá bài viết", "Xóa bài viết")
DELETE_COMMENT_LABELS = ("Delete comment", "Xoá bình luận", "Xóa bình luận")
DELETE_CONFIRM_LABELS = ("Delete", "Xoá", "Xóa")
POST_DELETED_LABELS = ("Post deleted", "Đã xoá bài viết", "Đã xóa bài viết")
ZERO_COMMENT_LABELS = ("No comments", "Chưa có bình luận", "0 comments")

_PROCESS_LINE_RE = re.compile(r"Process:\s*([^,\s]+),\s*PID:\s*(\d+)")
_PID_ONLY_RE = re.compile(r"AndroidRuntime(?:\(\s*\d+\))?:\s*PID:\s*\d+\s*$")
_APP_FRAME_RE = re.compile(r"\bat\s+com\.nexachat\.")
_EXO_ERROR_RE = re.compile(
    r"(ExoPlayerImplInternal|PlaybackException|MediaCodecVideoRenderer|MediaCodecAudioRenderer|LoadTask)\s*:?.*(ERROR|Exception|failed|error)",
    re.IGNORECASE,
)


class ProductFailure(E2EFailure):
    """Raised when the Android app or backend violates a product contract."""


class HarnessFailure(RuntimeError):
    """Raised when emulator, adb, build, or disposable stack infra fails."""


# ---------------------------------------------------------------------------
# Pure command builders & fixture helpers (unit-tested offline)
# ---------------------------------------------------------------------------


def build_gradle_assemble_cmd(
    repo_root: Path, api_base_url: str = EMULATOR_API_BASE_URL
) -> list[str]:
    """Builds the Gradle assembleDebug command with -PapiBaseUrl."""
    android_dir = Path(repo_root) / "android"
    gradlew = android_dir / ("gradlew.bat" if os.name == "nt" else "gradlew")
    return [
        str(gradlew),
        "-p",
        str(android_dir),
        "assembleDebug",
        f"-PapiBaseUrl={api_base_url}",
    ]


def build_adb_reverse_cmd(
    serial: str, remote_port: int, local_port: int
) -> list[str]:
    """Builds `adb -s <serial> reverse tcp:<remote_port> tcp:<local_port>`."""
    return ["adb", "-s", serial, "reverse", f"tcp:{int(remote_port)}", f"tcp:{int(local_port)}"]


def build_adb_reverse_remove_cmd(serial: str, remote_port: int) -> list[str]:
    """Builds `adb -s <serial> reverse --remove tcp:<remote_port>`."""
    return ["adb", "-s", serial, "reverse", "--remove", f"tcp:{int(remote_port)}"]


def build_uimode_night_cmd(serial: str, night: bool) -> list[str]:
    """Builds `adb -s <serial> shell cmd uimode night yes|no`."""
    return ["adb", "-s", serial, "shell", "cmd", "uimode", "night", "yes" if night else "no"]


def build_media_scan_cmd(serial: str) -> list[str]:
    """Builds API 33+ MediaStore scan_volume command for external_primary."""
    return [
        "adb", "-s", serial, "shell", "content", "call",
        "--uri", "content://media", "--method", "scan_volume", "--arg", "external_primary",
    ]


def ensure_valid_jpeg_fixture(dest_path: Path, width: int = 320, height: int = 240) -> Path:
    """Writes a valid JPEG image fixture (Pillow 320x240 or canonical JPEG blob)."""
    dest_path = Path(dest_path)
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        from PIL import Image, ImageDraw  # type: ignore

        img = Image.new("RGB", (width, height), (115, 63, 104))
        draw = ImageDraw.Draw(img)
        draw.rectangle([24, 24, width - 24, height - 24], fill=(252, 215, 244))
        draw.rectangle([48, 48, width - 48, height - 48], fill=(36, 107, 87))
        img.save(dest_path, format="JPEG", quality=92)
    except ImportError:
        dest_path.write_bytes(media_oracle.jpeg_fixture().data)
    data = dest_path.read_bytes()
    if not data.startswith(b"\xff\xd8\xff"):
        raise HarnessFailure(f"Generated JPEG fixture is invalid: {dest_path}")
    return dest_path


def ensure_valid_mp4_fixture(repo_root: Path, dest_path: Path) -> Path:
    """Copies a valid H.264 MP4 fixture from server/src/test/resources/fixtures."""
    dest_path = Path(dest_path)
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    fixtures_dir = Path(repo_root) / "server" / "src" / "test" / "resources" / "fixtures"
    for candidate_name in ("video_h264_aac_isom.mp4", "feed_video_h264_64x48.mp4"):
        candidate = fixtures_dir / candidate_name
        if candidate.is_file():
            data = candidate.read_bytes()
            if b"ftyp" in data[:32]:
                shutil.copy2(candidate, dest_path)
                return dest_path
    raise HarnessFailure(f"No valid H.264 MP4 fixture found under {fixtures_dir}")


# ---------------------------------------------------------------------------
# Pure verification oracles (unit-tested offline)
# ---------------------------------------------------------------------------


def parse_crash_logcat(log_text: str, package: str = PACKAGE) -> list[str]:
    """Extracts FATAL EXCEPTION / ANR blocks attributable to `package`."""
    lines = log_text.splitlines()
    findings: list[str] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if "FATAL EXCEPTION" not in line and "ANR in" not in line:
            i += 1
            continue
        block = [line]
        j = i + 1
        while j < len(lines) and j <= i + 60:
            nxt = lines[j]
            if "FATAL EXCEPTION" in nxt or "ANR in" in nxt:
                break
            block.append(nxt)
            j += 1
        i = j
        proc_match = next((_PROCESS_LINE_RE.search(entry) for entry in block if _PROCESS_LINE_RE.search(entry)), None)
        if proc_match is not None:
            proc_name = proc_match.group(1)
            if proc_name == package or proc_name.startswith(package + ":"):
                findings.append("\n".join(block))
            continue
        if "ANR in" in line:
            if package in "\n".join(block):
                findings.append("\n".join(block))
            continue
        if any(_APP_FRAME_RE.search(entry) for entry in block):
            findings.append("\n".join(block))
            continue
        if any(_PID_ONLY_RE.search(entry) for entry in block):
            continue
        findings.append("\n".join(block))
    return findings


def parse_exoplayer_errors(log_text: str, package: str = PACKAGE) -> list[str]:
    """Detects Media3/ExoPlayer playback errors or AndroidRuntime crashes."""
    errors: list[str] = []
    for line in log_text.splitlines():
        if _EXO_ERROR_RE.search(line):
            errors.append(line.strip())
        elif "AndroidRuntime" in line and ("FATAL EXCEPTION" in line or package in line):
            errors.append(line.strip())
    return errors


def verify_toolbar_height_dp(
    nodes: Sequence[Any],
    toolbar_rid: str,
    density_dpi: float,
    min_dp: float = 64.0,
) -> float:
    """Asserts toolbar height >= min_dp (tolerate 1px rounding) and returns measured dp."""
    toolbar = next(
        (n for n in nodes if a11y_oracle.rid_suffix(n) == toolbar_rid),
        None,
    )
    if toolbar is None:
        raise ProductFailure(f"Toolbar {toolbar_rid!r} not found in UI hierarchy")
    box = a11y_oracle.node_box(toolbar)
    if box is None:
        raise ProductFailure(f"Toolbar {toolbar_rid!r} has no bounds")
    height_px = box[3] - box[1]
    height_dp = height_px * 160.0 / float(density_dpi)
    if height_dp + 1.0 < min_dp:
        raise ProductFailure(
            f"Toolbar {toolbar_rid!r} height is {height_dp:.1f}dp ({height_px}px @ {density_dpi}dpi), "
            f"expected >= {min_dp:.1f}dp"
        )
    return height_dp


def check_feed_a11y(
    nodes: Sequence[Any],
    *,
    density_dpi: float,
    layout_dir: Any = a11y_oracle.DEFAULT_LAYOUT_DIR,
    screen_name: str = "feed",
) -> dict[str, Any]:
    """Checks >=48dp touch targets and missing contentDescriptions on Feed screens."""
    report = a11y_oracle.summarize_findings(
        list(nodes),
        min_dp=48.0,
        density=density_dpi,
        screen="auto",
        layout_dir=layout_dir,
    )
    small = report["checks"]["small_touch_targets"]["findings"]
    unlabeled = report["checks"]["unlabeled_interactive"]["findings"]
    missing_img = report["checks"]["missing_desc_images"]["findings"]
    app_small = [f for f in small if not _is_system_ui_rid(f)]
    app_unlabeled = [f for f in unlabeled if not _is_system_ui_rid(f)]
    app_missing_img = [f for f in missing_img if not _is_system_ui_rid(f)]
    if app_small:
        raise ProductFailure(
            f"[{screen_name}] sub-48dp interactive touch targets: {app_small[:3]}"
        )
    return {
        "screen": screen_name,
        "small_touch_targets": app_small,
        "unlabeled_interactive": app_unlabeled,
        "missing_desc_images": app_missing_img,
        "node_count": report["node_count"],
    }


def _is_system_ui_rid(finding: dict[str, Any]) -> bool:
    rid = str(finding.get("rid") or "")
    return rid in {"navigationBarBackground", "statusBarBackground", "back", "home", "recent_apps"}


def verify_state_sync_card(
    nodes: Sequence[Any],
    *,
    expected_body: str,
    edited_labels: tuple[str, ...] = EDITED_BADGE_CANDIDATES,
    expected_reactions: int | None = None,
    expected_comments: int | None = None,
) -> dict[str, Any]:
    """Verifies PublicFeedFragment card reflects updated body, edited badge, reaction & comment counts."""
    texts = [a11y_oracle.node_text(n).strip() for n in nodes if a11y_oracle.node_text(n).strip()]
    descs = [a11y_oracle.node_desc(n).strip() for n in nodes if a11y_oracle.node_desc(n).strip()]
    all_strings = texts + descs

    if not any(expected_body in s for s in all_strings):
        raise ProductFailure(
            f"Feed card did not sync updated post body {expected_body!r}; visible={texts[:20]}"
        )
    if not any(any(badge.casefold() in s.casefold() for badge in edited_labels) for s in all_strings):
        raise ProductFailure(
            f"Feed card missing edited badge {edited_labels}; visible={texts[:20]}"
        )

    reaction_matched = True
    if expected_reactions is not None:
        if expected_reactions == 0:
            reaction_matched = True
        else:
            reaction_matched = any(str(expected_reactions) in s for s in all_strings)
        if not reaction_matched:
            raise ProductFailure(
                f"Feed card did not sync reaction count {expected_reactions}; visible={texts[:20]}"
            )

    comment_matched = True
    if expected_comments is not None:
        comment_nodes = [
            n for n in nodes
            if "postcomments" in a11y_oracle.rid_suffix(n).lower()
            or "comment" in a11y_oracle.node_text(n).casefold()
            or "bình luận" in a11y_oracle.node_text(n).casefold()
        ]
        if expected_comments == 0:
            comment_matched = any(
                any(z.casefold() in a11y_oracle.node_text(n).casefold() for z in ZERO_COMMENT_LABELS)
                or "0" in a11y_oracle.node_text(n)
                for n in comment_nodes
            )
        else:
            comment_matched = any(
                str(expected_comments) in (a11y_oracle.node_text(n) + " " + a11y_oracle.node_desc(n))
                for n in comment_nodes
            )
        if not comment_matched:
            raise ProductFailure(
                f"Feed card did not sync comment count {expected_comments}; visible={texts[:20]}"
            )

    return {
        "body_synced": True,
        "edited_badge_synced": True,
        "reactions_synced": reaction_matched,
        "comments_synced": comment_matched,
    }


def check_http_range_206(
    client: Client,
    token: str,
    video_id_or_url: str,
    expected_bytes: bytes,
    *,
    prefix: str = "/api/v1/feed",
) -> dict[str, Any]:
    """Verifies GET /videos/{videoId} serves HTTP 206 Partial Content with exact bytes."""
    end = min(63, len(expected_bytes) - 1)
    if end < 0:
        raise HarnessFailure("expected_bytes is empty")
    path = video_id_or_url if video_id_or_url.startswith("/") else f"{prefix}/videos/{video_id_or_url}"
    resp = client.get(path, token=token, headers={"Range": f"bytes=0-{end}"})
    if resp.status != 206:
        raise ProductFailure(
            f"GET {path} with Range bytes=0-{end} expected 206, got HTTP {resp.status}"
        )
    headers_lower = {k.lower(): v for k, v in (resp.headers or {}).items()}
    accept_ranges = headers_lower.get("accept-ranges", "")
    content_range = headers_lower.get("content-range", "")
    if "bytes" not in accept_ranges.lower():
        raise ProductFailure(f"GET {path} 206 missing Accept-Ranges: bytes (got {accept_ranges!r})")
    expected_cr = f"bytes 0-{end}/{len(expected_bytes)}"
    if content_range != expected_cr:
        raise ProductFailure(
            f"GET {path} Content-Range={content_range!r}, expected {expected_cr!r}"
        )
    if resp.body != expected_bytes[: end + 1]:
        raise ProductFailure(
            f"GET {path} 206 body bytes mismatch (got {len(resp.body)} bytes)"
        )
    return {
        "status": 206,
        "accept_ranges": accept_ranges,
        "content_range": content_range,
        "bytes_verified": len(resp.body),
    }


# ---------------------------------------------------------------------------
# Live ADB / UI automation runner
# ---------------------------------------------------------------------------


@dataclass
class RunConfig:
    serial: str = SERIAL
    repo_root: Path = _REPO_ROOT
    screenshots_dir: Path = field(default_factory=lambda: Path(tempfile.gettempdir()) / "nexachat_social_screens")
    run_dir: Path | None = None
    apk_path: Path | None = None
    build_apk: bool = True
    jar_path: Path | None = None
    keep_stack: bool = False


class SocialEmulatorRunner:
    """Orchestrates isolated backend + emulator-5554 verification across Journeys 1-5."""

    def __init__(
        self,
        config: RunConfig,
        *,
        cmd_runner: Callable[..., subprocess.CompletedProcess] | None = None,
        stack_factory: Callable[..., ds.DisposableStack] | None = None,
        client_factory: Callable[[str], Client] | None = None,
        ui_factory: Callable[[str], adb_ui.Device] | None = None,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self.config = config
        self._cmd_runner = cmd_runner or self._default_cmd_runner
        self._stack_factory = stack_factory or self._default_stack_factory
        self._client_factory = client_factory or (lambda url: Client(url, timeout=30.0))
        self._ui_factory = ui_factory or (lambda serial: adb_ui.Device(serial))
        self._sleep = sleep

    @staticmethod
    def _default_cmd_runner(
        argv: Sequence[str], *, timeout: float = 120.0, check: bool = True
    ) -> subprocess.CompletedProcess:
        proc = subprocess.run(
            list(argv),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
        )
        if check and proc.returncode != 0:
            raise HarnessFailure(
                f"Command failed ({proc.returncode}): {' '.join(argv[:6])}\n"
                f"STDOUT: {proc.stdout[-1000:]}\nSTDERR: {proc.stderr[-1000:]}"
            )
        return proc

    @staticmethod
    def _default_stack_factory(run_dir: str, repo_root: Path) -> ds.DisposableStack:
        return ds.DisposableStack(
            run_dir,
            server_target=str(repo_root / "server" / "target"),
        )

    def _adb(self, *args: str, timeout: float = 60.0, check: bool = True) -> str:
        proc = self._cmd_runner(["adb", "-s", self.config.serial, *args], timeout=timeout, check=check)
        return (proc.stdout or "").strip()

    def verify_emulator_ready(self) -> float:
        """Verifies emulator-5554 is online, booted, and returns wm density DPI."""
        devices_out = self._cmd_runner(["adb", "devices", "-l"], timeout=15.0).stdout
        if f"{self.config.serial}" not in devices_out or "device" not in devices_out:
            raise HarnessFailure(f"{self.config.serial} is not attached/ready: {devices_out}")
        boot = self._adb("shell", "getprop", "sys.boot_completed", timeout=15.0)
        if boot.strip() != "1":
            raise HarnessFailure(f"{self.config.serial} sys.boot_completed={boot!r}, expected '1'")
        density_out = self._adb("shell", "wm", "density", timeout=15.0)
        m = re.search(r"(\d+)", density_out)
        return float(m.group(1)) if m else 160.0

    def resolve_or_build_apk(self) -> Path:
        if self.config.apk_path is not None:
            apk = Path(self.config.apk_path)
            if not apk.is_file():
                raise HarnessFailure(f"Explicit APK not found: {apk}")
            return apk
        if self.config.build_apk:
            cmd = build_gradle_assemble_cmd(self.config.repo_root, EMULATOR_API_BASE_URL)
            self._cmd_runner(cmd, timeout=600.0, check=True)
        default_apk = (
            Path(self.config.repo_root)
            / "android" / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
        )
        if not default_apk.is_file():
            raise HarnessFailure(f"Debug APK not found at {default_apk}")
        return default_apk

    def snapshot_device_state(self) -> dict[str, str]:
        locale = self._adb("shell", "getprop", "persist.sys.locale", timeout=15.0, check=False)
        night = self._adb("shell", "cmd", "uimode", "night", timeout=15.0, check=False)
        nav = self._adb("shell", "settings", "get", "secure", "navigation_mode", timeout=15.0, check=False)
        return {
            "locale": locale or "en-US",
            "night": "yes" if "yes" in night.lower() else "no",
            "navigation_mode": nav or "0",
        }

    def restore_device_state(self, snapshot: dict[str, str]) -> None:
        try:
            self._cmd_runner(build_uimode_night_cmd(self.config.serial, snapshot.get("night") == "yes"), timeout=20.0, check=False)
        except Exception:
            pass
        try:
            loc = snapshot.get("locale") or "en-US"
            self._adb("shell", "cmd", "locale", "set-device-locale", loc, timeout=20.0, check=False)
        except Exception:
            pass
        try:
            if snapshot.get("navigation_mode"):
                self._adb("shell", "settings", "put", "secure", "navigation_mode", snapshot["navigation_mode"], timeout=15.0, check=False)
        except Exception:
            pass

    def capture_screenshot(self, ui: adb_ui.Device, key: str) -> Path:
        filename, _desc = REQUIRED_SCREENSHOTS[key]
        out = Path(self.config.screenshots_dir) / filename
        out.parent.mkdir(parents=True, exist_ok=True)
        ui.screenshot(str(out))
        if not out.is_file() or out.stat().st_size < 1000:
            raise HarnessFailure(f"Screenshot {out} is empty or missing")
        return out

    def push_media_fixtures(self, work_dir: Path) -> dict[str, str]:
        jpg_local = ensure_valid_jpeg_fixture(work_dir / "e2e_social_photo.jpg")
        mp4_local = ensure_valid_mp4_fixture(self.config.repo_root, work_dir / "e2e_social_video.mp4")
        jpg_remote = "/sdcard/Pictures/e2e_social_photo.jpg"
        mp4_remote = "/sdcard/Movies/e2e_social_video.mp4"
        self._adb("push", str(jpg_local), jpg_remote, timeout=30.0)
        self._adb("push", str(mp4_local), mp4_remote, timeout=30.0)
        self._cmd_runner(build_media_scan_cmd(self.config.serial), timeout=30.0, check=False)
        return {"jpeg": jpg_remote, "mp4": mp4_remote, "mp4_local": str(mp4_local), "jpg_local": str(jpg_local)}

    # --- UI helpers ---

    def _nodes(self, ui: adb_ui.Device, timeout: float = 20.0) -> list[adb_ui.Node]:
        deadline = time.monotonic() + timeout
        last_err: Exception | None = None
        while time.monotonic() < deadline:
            try:
                return ui.nodes()
            except Exception as err:
                last_err = err
                self._sleep(0.7)
        raise HarnessFailure(f"Failed to read UI hierarchy: {last_err}")

    @staticmethod
    def _by_rid(nodes: Sequence[adb_ui.Node], suffix: str) -> adb_ui.Node | None:
        return next((n for n in nodes if a11y_oracle.rid_suffix(n) == suffix), None)

    @staticmethod
    def _by_text(
        nodes: Sequence[adb_ui.Node],
        candidates: Iterable[str],
        *,
        clickable_only: bool = False,
    ) -> adb_ui.Node | None:
        cands = tuple(c.casefold() for c in candidates)
        matches = []
        for n in nodes:
            if clickable_only and not n.clickable:
                continue
            txt = (n.text or "").strip().casefold()
            desc = (n.desc or "").strip().casefold()
            if any(c in txt or c in desc for c in cands):
                matches.append(n)
        if not matches:
            return None
        clickable_match = next((n for n in matches if n.clickable), None)
        return clickable_match or matches[0]

    def _wait_rid(self, ui: adb_ui.Device, suffix: str, timeout: float = 25.0) -> adb_ui.Node:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            node = self._by_rid(self._nodes(ui, timeout=10.0), suffix)
            if node is not None:
                return node
            self._sleep(0.5)
        raise ProductFailure(f"Expected UI element with id/{suffix} did not appear within {timeout}s")

    def _wait_any_text(
        self,
        ui: adb_ui.Device,
        candidates: Iterable[str],
        timeout: float = 25.0,
        *,
        clickable_only: bool = False,
    ) -> adb_ui.Node:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            node = self._by_text(
                self._nodes(ui, timeout=10.0),
                candidates,
                clickable_only=clickable_only,
            )
            if node is not None:
                return node
            self._sleep(0.5)
        raise ProductFailure(f"Expected UI text {list(candidates)} did not appear within {timeout}s")

    def _launch_app(self, clear_data: bool = False) -> None:
        if clear_data:
            self._adb("shell", "pm", "clear", PACKAGE, timeout=30.0)
        for perm in (
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.RECORD_AUDIO",
        ):
            self._adb("shell", "pm", "grant", PACKAGE, perm, timeout=15.0, check=False)
        self._adb("shell", "am", "start", "-n", f"{PACKAGE}/{MAIN_ACTIVITY}", timeout=30.0)
        self._sleep(3.0)

    def _sign_in_user_a(self, ui: adb_ui.Device) -> None:
        phone_node = self._wait_rid(ui, "phoneInput", timeout=30.0)
        ui.tap_node(phone_node)
        self._sleep(0.4)
        ui.type_text(USER_A["phone"])
        ui.key("KEYCODE_BACK")
        self._sleep(0.3)
        pw_node = self._wait_rid(ui, "passwordInput", timeout=15.0)
        ui.tap_node(pw_node)
        self._sleep(0.4)
        ui.type_text(SEED_PASSWORD)
        ui.key("KEYCODE_BACK")
        self._sleep(0.4)
        action = self._wait_rid(ui, "primaryAction", timeout=15.0)
        ui.tap_node(action)

        deadline = time.monotonic() + 60.0
        while time.monotonic() < deadline:
            nodes = self._nodes(ui, timeout=10.0)
            streak_done = self._by_rid(nodes, "streakMilestoneDone")
            if streak_done is not None and streak_done.clickable:
                ui.tap_node(streak_done)
                self._sleep(0.8)
                continue
            if self._by_rid(nodes, "conversationList") is not None or self._by_rid(nodes, "inboxUserAvatar") is not None:
                return
            self._sleep(0.8)
        raise ProductFailure("User A did not reach Inbox after signing in")

    def _open_public_feed_from_inbox(self, ui: adb_ui.Device) -> None:
        nodes = self._nodes(ui)
        btn = self._by_rid(nodes, "inboxPublicButton")
        if btn is not None and btn.box and (btn.box[2] - btn.box[0]) > 0:
            ui.tap_node(btn)
        else:
            more = self._wait_rid(ui, "moreButton", timeout=15.0)
            ui.tap_node(more)
            item = self._wait_any_text(ui, FEED_ENTRY_LABELS, timeout=10.0)
            ui.tap_node(item)
        self._sleep(1.0)
        # Dismiss first-use notice dialog if shown
        nodes = self._nodes(ui)
        if self._by_rid(nodes, "feedToolbar") is None:
            ok_btn = self._by_rid(nodes, "button1") or self._by_text(nodes, ("Got it", "Đã hiểu", "OK"))
            if ok_btn is not None:
                ui.tap_node(ok_btn)
                self._sleep(0.5)
        self._wait_rid(ui, "feedToolbar", timeout=20.0)

    def _pick_from_system_picker(self, ui: adb_ui.Device, media_kind: str) -> None:
        """Selects the pushed JPEG or MP4 inside Android PhotoPicker/DocumentsUI."""
        deadline = time.monotonic() + 25.0
        while time.monotonic() < deadline:
            nodes = self._nodes(ui, timeout=10.0)
            picker_nodes = [
                n for n in nodes
                if any(pkg in str(n.attrs.get("package", "")) for pkg in (
                    "photopicker", "media.module", "documentsui", "providers.media"
                ))
                and n.box is not None
            ]
            # API 36 Compose PhotoPicker puts "Photo taken on ..." / "Video taken on ..."
            # on the inner non-clickable child node inside the clickable grid tile.
            prefix = "video taken" if media_kind == "video" else "photo taken"
            candidates = [
                n for n in picker_nodes
                if prefix in (n.desc or "").casefold()
                and (n.box[2] - n.box[0]) >= 40
                and (n.box[3] - n.box[1]) >= 40
            ]
            if not candidates:
                candidates = [
                    n for n in picker_nodes
                    if n.clickable
                    and n.attrs.get("long-clickable") == "true"
                    and (n.box[2] - n.box[0]) >= 40
                    and (n.box[3] - n.box[1]) >= 40
                ]
            if not candidates:
                candidates = [
                    n for n in picker_nodes
                    if n.clickable
                    and (
                        "e2e_social" in (n.text or "").casefold()
                        or "thumbnail" in a11y_oracle.rid_suffix(n).casefold()
                        or "icon_thumb" in a11y_oracle.rid_suffix(n).casefold()
                    )
                ]
            if candidates:
                ui.tap_node(candidates[0])
                self._sleep(1.2)
                after = self._nodes(ui, timeout=10.0)
                still_in_picker = any(
                    "photopicker" in str(n.attrs.get("package", "")) for n in after
                )
                if still_in_picker:
                    add_btn = self._by_text(after, ("Add", "Done", "Chọn", "Thêm"))
                    if add_btn is not None:
                        ui.tap_node(add_btn)
                        self._sleep(1.0)
                return
            self._sleep(0.7)
        raise HarnessFailure(f"Could not select {media_kind} in Android system picker")

    def _type_into_field(
        self, ui: adb_ui.Device, rid: str, text: str, *, clear_first: bool = False
    ) -> None:
        for attempt in range(3):
            node = self._wait_rid(ui, rid, timeout=15.0)
            ui.tap_node(node)
            self._sleep(0.5)
            if clear_first or (node.text and node.text != text):
                current_node = self._by_rid(self._nodes(ui, timeout=10.0), rid) or node
                existing = current_node.text or ""
                if existing and existing != current_node.attrs.get("hint", ""):
                    ui.move_end()
                    self._sleep(0.2)
                    ui.delete_chars(len(existing) + 15)
                    self._sleep(0.2)
            ui.type_text(text)
            self._sleep(0.6)
            after = self._by_rid(self._nodes(ui, timeout=10.0), rid)
            if after is not None and (after.text or "").strip() == text.strip():
                return
            clear_first = True
        raise HarnessFailure(f"Field {rid!r} did not retain expected text {text!r} (got {after.text if after else None!r})")

    def _replace_edit_text(self, ui: adb_ui.Device, node: adb_ui.Node, new_text: str) -> None:
        rid = a11y_oracle.rid_suffix(node)
        if rid:
            self._type_into_field(ui, rid, new_text, clear_first=True)
            return
        ui.tap_node(node)
        self._sleep(0.5)
        ui.move_end()
        self._sleep(0.2)
        existing_len = max(len(node.text or "") + 15, 50)
        ui.delete_chars(existing_len)
        ui.type_text(new_text)
        self._sleep(0.5)

    def _submit_report_sheet(self, ui: adb_ui.Device) -> None:
        spam_chip = self._wait_rid(ui, "reasonSpam", timeout=10.0)
        ui.tap_node(spam_chip)
        self._sleep(0.3)
        details = self._by_rid(self._nodes(ui), "reportDetailsInput")
        if details is not None:
            ui.tap_node(details)
            ui.type_text("Automated E2E report verification")
            ui.key("KEYCODE_BACK")
            self._sleep(0.3)
        submit = self._wait_rid(ui, "reportSubmit", timeout=10.0)
        ui.tap_node(submit)
        self._wait_any_text(ui, REPORT_SENT_LABELS, timeout=15.0)

    def run_all(self) -> dict[str, Any]:
        """Executes the full real-emulator verification suite against a fresh stack."""
        density_dpi = self.verify_emulator_ready()
        apk_path = self.resolve_or_build_apk()
        snapshot = self.snapshot_device_state()

        run_dir = self.config.run_dir or Path(tempfile.mkdtemp(prefix="nexachat_emu_e2e_"))
        run_dir.mkdir(parents=True, exist_ok=True)
        stack = self._stack_factory(str(run_dir), self.config.repo_root)
        reverse_active = False
        report: dict[str, Any] = {
            "serial": self.config.serial,
            "density_dpi": density_dpi,
            "apk": str(apk_path),
            "screenshots": {},
            "journeys": {},
        }

        try:
            isolated_port = ds.find_free_port(port_range=EMULATOR_STACK_PORT_RANGE)
            state = stack.provision(app_port=isolated_port, admin_phones=ADMIN_PHONES_CSV)
            jar_arg = str(self.config.jar_path) if self.config.jar_path else None
            started_state = stack.start(jar=jar_arg, readiness_timeout=110.0)
            pid = (started_state or {}).get("pid")
            if pid is not None and hasattr(stack, "process_controller") and not stack.process_controller.is_alive(pid):
                raise HarnessFailure(f"Started backend pid {pid} exited immediately (port {isolated_port} collision?)")
            app_port = int(state["app_port"])
            base_url = state["base_url"]
            report["stack_base_url"] = base_url
            report["db_name"] = state["db_name"]

            # Map emulator 127.0.0.1:8080 -> host isolated 18xxx port
            self._cmd_runner(
                build_adb_reverse_cmd(self.config.serial, EMULATOR_LOOPBACK_PORT, app_port),
                timeout=15.0,
            )
            reverse_active = True

            client = self._client_factory(base_url)
            seeded = stack.seed([USER_A, USER_B, ADMIN_USER], client=client)
            tokens = {rec["phone"]: rec for rec in seeded}
            token_a = tokens[USER_A["phone"]]["access_token"]
            token_b = tokens[USER_B["phone"]]["access_token"]
            user_b_id = tokens[USER_B["phone"]]["user_id"]
            token_admin = tokens[ADMIN_USER["phone"]]["access_token"]

            direct_conversation(client, token_a, USER_B["phone"])

            # V50 ships web_enabled=false and video_uploads_enabled=false; enable both for E2E
            adm_init = client.get("/api/v1/admin/social/settings", token=token_admin)
            check(adm_init.status == 200, f"GET initial admin social settings failed: {adm_init}")
            init_rev = int((adm_init.json() or {})["revision"])
            enable_resp = client.patch(
                "/api/v1/admin/social/settings/flags",
                token=token_admin,
                body={"expectedRevision": init_rev, "webEnabled": True, "videoUploadsEnabled": True},
            )
            check(enable_resp.status == 200, f"Enable social flags failed: {enable_resp}")

            fixtures = self.push_media_fixtures(run_dir)
            mp4_bytes = Path(fixtures["mp4_local"]).read_bytes()

            self._adb("install", "-r", str(apk_path), timeout=180.0)
            self._adb("logcat", "-c", timeout=15.0)
            self._adb("logcat", "-b", "crash", "-c", timeout=15.0, check=False)

            ui = self._ui_factory(self.config.serial)

            self._cmd_runner(build_uimode_night_cmd(self.config.serial, False), timeout=15.0)
            self._adb("shell", "cmd", "locale", "set-device-locale", "en-US", timeout=15.0, check=False)
            self._launch_app(clear_data=True)

            # ---- Journey 1: Entry & Design System ----
            self._sign_in_user_a(ui)
            self._open_public_feed_from_inbox(ui)

            feed_nodes_light = self._nodes(ui)
            toolbar_dp = verify_toolbar_height_dp(feed_nodes_light, "feedToolbar", density_dpi, min_dp=64.0)
            a11y_light = check_feed_a11y(feed_nodes_light, density_dpi=density_dpi, screen_name="feed_light")
            report["screenshots"]["feed_light"] = str(self.capture_screenshot(ui, "feed_light"))

            self._cmd_runner(build_uimode_night_cmd(self.config.serial, True), timeout=15.0)
            self._sleep(1.5)
            self._wait_rid(ui, "feedToolbar", timeout=15.0)
            report["screenshots"]["feed_dark"] = str(self.capture_screenshot(ui, "feed_dark"))
            self._cmd_runner(build_uimode_night_cmd(self.config.serial, False), timeout=15.0)
            self._sleep(1.0)

            self._adb("shell", "cmd", "locale", "set-device-locale", "vi-VN", timeout=15.0, check=False)
            self._sleep(1.5)
            self._wait_any_text(ui, ("Bảng tin công khai",), timeout=15.0)
            self._adb("shell", "cmd", "locale", "set-device-locale", "en-US", timeout=15.0, check=False)
            self._sleep(1.5)
            self._wait_any_text(ui, ("Public feed",), timeout=15.0)

            report["journeys"]["journey_1_design_system"] = {
                "toolbar_dp": toolbar_dp,
                "a11y_light": a11y_light,
                "vi_en_parity": True,
            }

            # ---- Journey 2: Create Text, Photo & MP4 Video Posts + Playback ----
            stamp = uuid.uuid4().hex[:6]
            text_body = f"E2E text post {stamp}"
            photo_body = f"E2E photo post {stamp}"
            video_body = f"E2E video post {stamp}"

            self._create_post_via_ui(ui, text_body, media_kind=None)
            self._wait_any_text(ui, (text_body,), timeout=20.0)

            self._create_post_via_ui(ui, photo_body, media_kind="image")
            self._wait_any_text(ui, (photo_body,), timeout=25.0)
            photo_node = self._wait_rid(ui, "postImage", timeout=15.0)
            check(
                photo_node.box is not None and (photo_node.box[3] - photo_node.box[1]) > 0,
                "Photo post image view has zero height",
            )

            self._create_post_via_ui(ui, video_body, media_kind="video")
            self._wait_any_text(ui, (video_body,), timeout=25.0)

            posts_resp = client.get("/api/v1/feed/posts", token=token_a, params={"limit": "10"})
            check(posts_resp.status == 200, f"GET /api/v1/feed/posts returned {posts_resp.status}")
            items = (posts_resp.json() or {}).get("items") or []
            video_post = next((p for p in items if p.get("body") == video_body), None)
            check(video_post is not None and video_post.get("video"), "Uploaded MP4 video post not found via API")
            video_obj = video_post["video"]
            video_url = str(video_obj.get("url") or "")
            video_id = str(video_obj.get("id") or video_url.rsplit("/", 1)[-1])
            check(bool(video_id), f"Video post missing id/url: {video_obj!r}")
            range_result = check_http_range_206(client, token_a, video_url or video_id, mp4_bytes)

            nodes = self._nodes(ui)
            play_btn = self._wait_rid(ui, "postVideoPlayPause", timeout=15.0)
            ui.tap_node(play_btn)
            self._sleep(0.35)
            check(self._by_rid(self._nodes(ui), "postVideoError") is None, "postVideoError overlay appeared")
            report["screenshots"]["video_post_playing"] = str(self.capture_screenshot(ui, "video_post_playing"))
            self._sleep(0.6)
            mute_btn = self._by_rid(self._nodes(ui), "postVideoMute")
            if mute_btn is not None:
                ui.tap_node(mute_btn)
                self._sleep(0.5)

            exo_log = self._adb("logcat", "-d", "-v", "brief", timeout=30.0)
            exo_errors = parse_exoplayer_errors(exo_log)
            if exo_errors:
                raise ProductFailure(f"ExoPlayer/AndroidRuntime errors in logcat: {exo_errors[:3]}")

            report["journeys"]["journey_2_media_playback"] = {
                "text_post": text_body,
                "photo_post": photo_body,
                "video_post": video_body,
                "video_id": video_id,
                "http_range_206": range_result,
                "exoplayer_errors": 0,
            }

            # Refresh Feed Light & Dark screenshots with live populated post cards
            report["screenshots"]["feed_light"] = str(self.capture_screenshot(ui, "feed_light"))
            self._cmd_runner(build_uimode_night_cmd(self.config.serial, True), timeout=15.0)
            self._sleep(1.2)
            self._wait_rid(ui, "feedToolbar", timeout=15.0)
            report["screenshots"]["feed_dark"] = str(self.capture_screenshot(ui, "feed_dark"))
            self._cmd_runner(build_uimode_night_cmd(self.config.serial, False), timeout=15.0)
            self._sleep(1.0)

            # ---- Journey 3: Multi-Emoji Reactions, Comments, Edit & State Sync ----
            for emoji in ("\U0001F44D", "\u2764\uFE0F"):
                react_btn = self._wait_rid(ui, "postReact", timeout=15.0)
                ui.tap_node(react_btn)
                self._sleep(0.5)
                picker_nodes = self._nodes(ui)
                emoji_tile = next((n for n in picker_nodes if emoji in (n.text or "")), None)
                check(emoji_tile is not None, f"Reaction emoji {emoji!r} not visible in picker")
                ui.tap_node(emoji_tile)
                self._sleep(0.7)

            # Pill tap to toggle off second emoji -> leaves 1 reaction
            heart_pill = next((n for n in self._nodes(ui) if "\u2764" in (n.text or "")), None)
            if heart_pill is not None:
                ui.tap_node(heart_pill)
                self._sleep(0.7)

            # Open PostDetailFragment on the top post (video_body)
            post_node = self._wait_any_text(ui, (video_body,), timeout=15.0)
            ui.tap_node(post_node)
            self._wait_rid(ui, "detailComposer", timeout=15.0)

            comment_text = f"First comment {stamp}"
            edited_comment_text = f"Edited comment {stamp}"
            edited_post_text = f"Edited video post {stamp}"

            self._type_into_field(ui, "detailInput", comment_text)
            ui.key("KEYCODE_BACK")
            c_send = self._wait_rid(ui, "detailSend", timeout=10.0)
            ui.tap_node(c_send)
            self._wait_any_text(ui, (comment_text,), timeout=15.0)

            # Inline edit own comment via detailEditBanner + detailInput + detailSend
            # Scroll detailList slightly so the comment card & menu are comfortably above the composer
            detail_list = self._wait_rid(ui, "detailList", timeout=10.0)
            if detail_list.box:
                l, t, r, b = detail_list.box
                cx = (l + r) // 2
                ui.swipe(cx, b - 40, cx, max(t + 40, b - 240), 300)
                self._sleep(0.5)
            c_menu = self._wait_rid(ui, "commentMenu", timeout=10.0)
            ui.tap_node(c_menu)
            edit_item = self._wait_any_text(ui, EDIT_LABELS, timeout=10.0)
            ui.tap_node(edit_item)
            self._wait_rid(ui, "detailEditBanner", timeout=10.0)
            c_edit_input = self._wait_rid(ui, "detailInput", timeout=10.0)
            self._replace_edit_text(ui, c_edit_input, edited_comment_text)
            ui.key("KEYCODE_BACK")
            ui.tap_node(self._wait_rid(ui, "detailSend", timeout=10.0))
            self._wait_any_text(ui, (edited_comment_text,), timeout=15.0)
            self._wait_any_text(ui, EDITED_BADGE_CANDIDATES, timeout=10.0)
            detail_list2 = self._by_rid(self._nodes(ui), "detailList")
            if detail_list2 and detail_list2.box:
                l, t, r, b = detail_list2.box
                cx = (l + r) // 2
                ui.swipe(cx, b - 40, cx, max(t + 40, b - 260), 300)
                self._sleep(0.5)
            report["screenshots"]["post_detail_edited_comment"] = str(
                self.capture_screenshot(ui, "post_detail_edited_comment")
            )

            # Scroll back up in detailList so postMenu is visible for Edit Post
            detail_list3 = self._by_rid(self._nodes(ui), "detailList")
            if detail_list3 and detail_list3.box:
                l, t, r, b = detail_list3.box
                cx = (l + r) // 2
                ui.swipe(cx, t + 40, cx, min(b - 40, t + 320), 300)
                self._sleep(0.5)
            p_menu = self._wait_rid(ui, "postMenu", timeout=10.0)
            ui.tap_node(p_menu)
            p_edit = self._wait_any_text(ui, EDIT_LABELS, timeout=10.0)
            ui.tap_node(p_edit)
            comp_in = self._wait_rid(ui, "composeInput", timeout=15.0)
            self._replace_edit_text(ui, comp_in, edited_post_text)
            ui.key("KEYCODE_BACK")
            comp_save = self._wait_rid(ui, "composeSubmit", timeout=10.0)
            ui.tap_node(comp_save)
            self._wait_rid(ui, "detailComposer", timeout=15.0)
            self._wait_any_text(ui, (edited_post_text,), timeout=15.0)

            # Scroll down so commentMenu is comfortably above composer before deleting own comment
            detail_list4 = self._by_rid(self._nodes(ui), "detailList")
            if detail_list4 and detail_list4.box:
                l, t, r, b = detail_list4.box
                cx = (l + r) // 2
                ui.swipe(cx, b - 40, cx, max(t + 40, b - 240), 300)
                self._sleep(0.5)
            c_menu2 = self._wait_rid(ui, "commentMenu", timeout=10.0)
            ui.tap_node(c_menu2)
            del_item = self._wait_any_text(ui, DELETE_COMMENT_LABELS, timeout=10.0)
            ui.tap_node(del_item)
            confirm_del = self._wait_any_text(
                ui, DELETE_CONFIRM_LABELS, timeout=10.0, clickable_only=True
            )
            ui.tap_node(confirm_del)
            self._sleep(1.2)

            # Navigate back to PublicFeedFragment and verify immediate state sync
            ui.key("KEYCODE_BACK")
            self._wait_rid(ui, "feedToolbar", timeout=15.0)
            sync_report = verify_state_sync_card(
                self._nodes(ui),
                expected_body=edited_post_text,
                expected_reactions=1,
                expected_comments=0,
            )
            report["journeys"]["journey_3_sync"] = sync_report

            # ---- Journey 4: Search, Author Profile Sheet & Social -> Chat/Call Handoff ----
            vi_post_body = f"Cà phê sáng Sài Gòn {stamp}"
            seed_b = client.post(
                "/api/v1/feed/posts",
                token=token_b,
                body={"clientPostId": str(uuid.uuid4()), "body": vi_post_body},
            )
            check(seed_b.status in (200, 201), f"Seed User B post failed: {seed_b}")
            seed_b_post_id = (seed_b.json() or {})["id"]
            seed_b_comment = client.post(
                f"/api/v1/feed/posts/{seed_b_post_id}/comments",
                token=token_b,
                body={"clientCommentId": str(uuid.uuid4()), "body": f"B comment {stamp}"},
            )
            check(seed_b_comment.status in (200, 201), f"Seed User B comment failed: {seed_b_comment}")

            self._pull_or_refresh_feed(ui)
            search_in = self._wait_rid(ui, "feedSearchInput", timeout=15.0)
            ui.tap_node(search_in)
            ui.type_text("ca phe")
            ui.key("KEYCODE_ENTER")
            self._wait_any_text(ui, (vi_post_body,), timeout=15.0)
            clear_btn = self._wait_rid(ui, "feedSearchClear", timeout=10.0)
            ui.tap_node(clear_btn)
            self._sleep(1.0)

            # Tap User B's postAuthorHeader (first post in feed is User B's newest post)
            author_hdr = self._wait_rid(ui, "postAuthorHeader", timeout=15.0)
            ui.tap_node(author_hdr)
            self._wait_any_text(ui, (USER_B["name"],), timeout=15.0)
            self._wait_rid(ui, "authorProfilePostCount", timeout=15.0)
            report["screenshots"]["author_profile_sheet"] = str(
                self.capture_screenshot(ui, "author_profile_sheet")
            )

            # Tap "Filter posts" -> verify dismissible feedAuthorFilterChip
            filter_btn = self._wait_rid(ui, "authorProfileFilter", timeout=10.0)
            ui.tap_node(filter_btn)
            chip = self._wait_rid(ui, "feedAuthorFilterChip", timeout=15.0)
            ui.tap_node(chip)
            self._sleep(1.0)

            # Open Author Profile Sheet again -> tap Message -> 1:1 Chat + Call entry + Inbox row
            author_hdr2 = self._wait_rid(ui, "postAuthorHeader", timeout=15.0)
            ui.tap_node(author_hdr2)
            msg_btn = self._wait_rid(ui, "authorProfileMessage", timeout=15.0)
            ui.tap_node(msg_btn)
            composer = self._wait_rid(ui, "messageInput", timeout=20.0)
            self._wait_rid(ui, "callButton", timeout=10.0)
            handoff_msg = f"Handoff chat {stamp}"
            self._wait_rid(ui, "messageInput", timeout=20.0)
            self._type_into_field(ui, "messageInput", handoff_msg)
            send_chat = self._wait_rid(ui, "sendButton", timeout=10.0)
            ui.tap_node(send_chat)
            self._wait_any_text(ui, (handoff_msg,), timeout=15.0)
            report["screenshots"]["social_chat_handoff"] = str(
                self.capture_screenshot(ui, "social_chat_handoff")
            )

            # Navigate out of ChatFragment -> InboxFragment (dismiss IME first if open)
            for _ in range(4):
                cur_nodes = self._nodes(ui)
                if self._by_rid(cur_nodes, "conversationList") is not None:
                    break
                ui.key("KEYCODE_BACK")
                self._sleep(1.0)
            self._wait_rid(ui, "conversationList", timeout=15.0)
            self._wait_any_text(ui, (USER_B["name"],), timeout=15.0)
            self._wait_any_text(ui, (handoff_msg,), timeout=10.0)
            self._open_public_feed_from_inbox(ui)

            # Report User B's post + User B's comment
            p_menu_b = self._wait_rid(ui, "postMenu", timeout=15.0)
            ui.tap_node(p_menu_b)
            ui.tap_node(self._wait_any_text(ui, REPORT_LABELS, timeout=10.0))
            self._submit_report_sheet(ui)

            ui.tap_node(self._wait_any_text(ui, (vi_post_body,), timeout=15.0))
            self._wait_rid(ui, "detailComposer", timeout=15.0)
            c_menu_b = self._wait_rid(ui, "commentMenu", timeout=15.0)
            ui.tap_node(c_menu_b)
            ui.tap_node(self._wait_any_text(ui, REPORT_LABELS, timeout=10.0))
            self._submit_report_sheet(ui)
            ui.key("KEYCODE_BACK")
            self._wait_rid(ui, "feedToolbar", timeout=15.0)

            # Delete User A's own edited post via menu and verify snackbar + removal
            # Scroll feedList down slightly if User A's card is below User B's card
            for _ in range(3):
                if self._by_text(self._nodes(ui), (edited_post_text,)) is not None:
                    menus = [n for n in self._nodes(ui) if a11y_oracle.rid_suffix(n) == "postMenu"]
                    if len(menus) >= 2:
                        break
                fl = self._by_rid(self._nodes(ui), "feedList")
                if fl and fl.box:
                    l, t, r, b = fl.box
                    cx = (l + r) // 2
                    ui.swipe(cx, b - 40, cx, max(t + 40, b - 240), 300)
                    self._sleep(0.6)
            self._wait_any_text(ui, (edited_post_text,), timeout=15.0)
            menus = [n for n in self._nodes(ui) if a11y_oracle.rid_suffix(n) == "postMenu"]
            # Pick the postMenu closest in y above edited_post_text
            edited_node = self._wait_any_text(ui, (edited_post_text,), timeout=10.0)
            if len(menus) >= 2 and edited_node.box:
                ey = edited_node.box[1]
                above = [m for m in menus if m.box and m.box[1] <= ey]
                target_menu = max(above, key=lambda m: m.box[1]) if above else menus[-1]
            else:
                target_menu = menus[-1]
            ui.tap_node(target_menu)
            ui.tap_node(self._wait_any_text(ui, DELETE_POST_LABELS, timeout=10.0))
            ui.tap_node(self._wait_any_text(ui, DELETE_CONFIRM_LABELS, timeout=10.0, clickable_only=True))
            self._wait_any_text(ui, POST_DELETED_LABELS, timeout=15.0)
            self._sleep(1.0)
            check(
                self._by_text(self._nodes(ui), (edited_post_text,)) is None,
                "Deleted post remained visible in Feed after delete",
            )

            report["journeys"]["journey_4_handoff"] = {
                "user_b_id": user_b_id,
                "accent_insensitive_search": "ca phe",
                "chat_message_sent": handoff_msg,
                "report_post_and_comment": True,
                "delete_own_post_snackbar": True,
            }

            # ---- Journey 5: Runtime Flag Gate ----
            adm_state = client.get("/api/v1/admin/social/settings", token=token_admin)
            check(adm_state.status == 200, f"GET admin social settings failed: {adm_state}")
            rev = int((adm_state.json() or {})["revision"])
            web_en = bool((adm_state.json() or {}).get("webEnabled", True))

            toggle_off = client.patch(
                "/api/v1/admin/social/settings/flags",
                token=token_admin,
                body={"expectedRevision": rev, "webEnabled": web_en, "videoUploadsEnabled": False},
            )
            check(toggle_off.status == 200, f"Disable videoUploadsEnabled failed: {toggle_off}")
            rev2 = int((toggle_off.json() or {})["revision"])

            try:
                ui.tap_node(self._wait_rid(ui, "feedCompose", timeout=15.0))
                self._wait_rid(ui, "composeToolbar", timeout=15.0)
                self._sleep(1.5)
                compose_nodes = self._nodes(ui)
                vid_btn = self._wait_rid(ui, "composeAddVideo", timeout=10.0)
                check(
                    vid_btn.attrs.get("enabled") == "false",
                    "composeAddVideo stayed enabled while videoUploadsEnabled=false",
                )
                self._wait_rid(ui, "composeVideoDisabledNote", timeout=10.0)
                ui.key("KEYCODE_BACK")
                self._wait_rid(ui, "feedToolbar", timeout=15.0)
            finally:
                client.patch(
                    "/api/v1/admin/social/settings/flags",
                    token=token_admin,
                    body={"expectedRevision": rev2, "webEnabled": web_en, "videoUploadsEnabled": True},
                )

            report["journeys"]["journey_5_flag_gate"] = {"video_uploads_disabled_gracefully": True}

            # ---- Final Crash Buffer Gate ----
            crash_log = self._adb("logcat", "-d", "-b", "crash", timeout=20.0, check=False)
            crashes = parse_crash_logcat(crash_log, PACKAGE)
            if crashes:
                raise ProductFailure(f"Crash buffer has NexaChat crashes:\n{crashes[0]}")
            report["crash_count"] = 0
            report["status"] = "PASS"
            return report
        finally:
            self.restore_device_state(snapshot)
            if reverse_active:
                try:
                    self._cmd_runner(
                        build_adb_reverse_remove_cmd(self.config.serial, EMULATOR_LOOPBACK_PORT),
                        timeout=15.0,
                        check=False,
                    )
                except Exception:
                    pass
            if not self.config.keep_stack:
                try:
                    stack.stop()
                except Exception:
                    pass
                try:
                    stack.dropdb()
                except Exception:
                    pass

    def _create_post_via_ui(
        self, ui: adb_ui.Device, body: str, *, media_kind: str | None
    ) -> None:
        fab = self._wait_rid(ui, "feedCompose", timeout=15.0)
        ui.tap_node(fab)
        self._wait_rid(ui, "composeInput", timeout=15.0)
        self._type_into_field(ui, "composeInput", body)
        ui.key("KEYCODE_BACK")
        self._sleep(0.3)
        if media_kind == "image":
            add_img = self._wait_rid(ui, "composeAddImage", timeout=10.0)
            ui.tap_node(add_img)
            self._pick_from_system_picker(ui, "image")
        elif media_kind == "video":
            add_vid = self._wait_rid(ui, "composeAddVideo", timeout=10.0)
            check(
                add_vid.attrs.get("enabled") == "true",
                "composeAddVideo is disabled while videoUploadsEnabled=true",
            )
            ui.tap_node(add_vid)
            self._pick_from_system_picker(ui, "video")
        submit = self._wait_rid(ui, "composeSubmit", timeout=10.0)
        ui.tap_node(submit)
        self._wait_rid(ui, "feedToolbar", timeout=30.0)

    def _pull_or_refresh_feed(self, ui: adb_ui.Device) -> None:
        nodes = self._nodes(ui)
        refresh = self._by_rid(nodes, "feedRefresh") or self._by_rid(nodes, "feedList")
        if refresh is not None and refresh.box:
            l, t, r, b = refresh.box
            cx = (l + r) // 2
            ui.swipe(cx, t + 40, cx, min(b - 20, t + 320), 350)
            self._sleep(1.5)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=SERIAL)
    parser.add_argument("--screenshots-dir", required=True)
    parser.add_argument("--run-dir", default=None)
    parser.add_argument("--apk", default=None)
    parser.add_argument("--no-build-apk", action="store_true")
    parser.add_argument("--jar", default=None)
    parser.add_argument("--keep-stack", action="store_true")
    args = parser.parse_args(argv)

    cfg = RunConfig(
        serial=args.serial,
        screenshots_dir=Path(args.screenshots_dir).resolve(),
        run_dir=Path(args.run_dir).resolve() if args.run_dir else None,
        apk_path=Path(args.apk).resolve() if args.apk else None,
        build_apk=not args.no_build_apk,
        jar_path=Path(args.jar).resolve() if args.jar else None,
        keep_stack=bool(args.keep_stack),
    )
    runner = SocialEmulatorRunner(cfg)
    report = runner.run_all()
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
