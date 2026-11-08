#!/usr/bin/env python3
"""Record an Android screen via adb and convert it to a lightweight GIF.

Usage:
    python tools/gif/record.py --out docs/media/gif/01-login.gif --seconds 10 [--serial <device-id>]
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Callable, Sequence

MAX_DURATION_SECONDS = 180
MAX_GIF_BYTES = 6 * 1024 * 1024  # 6 MB
DEFAULT_SIZE = "720x1280"
DEFAULT_BIT_RATE = "4M"

PALETTE_FILTER_PRIMARY = (
    "fps=10,scale=360:-1:flags=lanczos,split[s0][s1];"
    "[s0]palettegen=max_colors=96[p];"
    "[s1][p]paletteuse=dither=bayer:bayer_scale=4"
)

PALETTE_FILTER_FALLBACK = (
    "fps=8,scale=320:-1:flags=lanczos,split[s0][s1];"
    "[s0]palettegen=max_colors=96[p];"
    "[s1][p]paletteuse=dither=bayer:bayer_scale=4"
)


def build_screenrecord_cmd(
    serial: str | None,
    seconds: int,
    device_path: str,
    size: str = DEFAULT_SIZE,
    bit_rate: str = DEFAULT_BIT_RATE,
) -> list[str]:
    """Construct the adb shell screenrecord command with time limit capped at 180s."""
    effective_seconds = min(max(1, int(seconds)), MAX_DURATION_SECONDS)
    cmd = ["adb"]
    if serial:
        cmd.extend(["-s", serial])
    cmd.extend([
        "shell",
        "screenrecord",
        "--time-limit",
        str(effective_seconds),
        "--size",
        size,
        "--bit-rate",
        bit_rate,
        device_path,
    ])
    return cmd


def build_pull_cmd(serial: str | None, device_path: str, local_path: str) -> list[str]:
    """Construct the adb pull command."""
    cmd = ["adb"]
    if serial:
        cmd.extend(["-s", serial])
    cmd.extend(["pull", device_path, local_path])
    return cmd


def build_rm_cmd(serial: str | None, device_path: str) -> list[str]:
    """Construct the adb shell rm command."""
    cmd = ["adb"]
    if serial:
        cmd.extend(["-s", serial])
    cmd.extend(["shell", "rm", "-f", device_path])
    return cmd


def build_ffmpeg_cmd(input_mp4: str, output_gif: str, filter_complex: str) -> list[str]:
    """Construct the ffmpeg conversion command."""
    return ["ffmpeg", "-y", "-i", input_mp4, "-vf", filter_complex, output_gif]


def convert_mp4_to_gif(
    input_mp4: str,
    output_gif: str,
    max_bytes: int = MAX_GIF_BYTES,
    run_cmd: Callable[[Sequence[str]], None] | None = None,
    get_size: Callable[[str], int] | None = None,
) -> bool:
    """Convert MP4 to GIF using 2-pass palette, retrying with fallback resolution/fps if > max_bytes."""
    runner = run_cmd or (lambda cmd: subprocess.run(cmd, check=True, capture_output=True))
    sizer = get_size or (lambda path: os.path.getsize(path))

    # Output directory creation
    out_path = Path(output_gif)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    # 1. Primary conversion: fps=10, scale=360
    primary_cmd = build_ffmpeg_cmd(input_mp4, output_gif, PALETTE_FILTER_PRIMARY)
    runner(primary_cmd)

    size = sizer(output_gif)
    if size <= max_bytes:
        return True

    # 2. Fallback conversion: fps=8, scale=320
    fallback_cmd = build_ffmpeg_cmd(input_mp4, output_gif, PALETTE_FILTER_FALLBACK)
    runner(fallback_cmd)

    fallback_size = sizer(output_gif)
    if fallback_size <= max_bytes:
        return True

    raise ValueError(
        f"Generated GIF exceeds size cap {max_bytes} bytes "
        f"(primary: {size} bytes, fallback: {fallback_size} bytes)"
    )


def record_screen_to_gif(
    output_gif: str,
    seconds: int,
    serial: str | None = None,
    run_cmd: Callable[[Sequence[str]], None] | None = None,
) -> None:
    """End-to-end screen recording to GIF."""
    runner = run_cmd or (lambda cmd: subprocess.run(cmd, check=True))
    remote_tmp = f"/data/local/tmp/nexachat_rec_{int(time.time())}.mp4"

    with tempfile.NamedTemporaryFile(suffix=".mp4", delete=False) as temp_file:
        local_mp4 = temp_file.name

    try:
        # Record on device
        record_cmd = build_screenrecord_cmd(serial, seconds, remote_tmp)
        print(f"Recording screen for {min(seconds, MAX_DURATION_SECONDS)}s...")
        runner(record_cmd)

        # Pull from device
        pull_cmd = build_pull_cmd(serial, remote_tmp, local_mp4)
        print("Pulling recorded MP4...")
        runner(pull_cmd)

        # Convert to GIF
        print(f"Converting to GIF: {output_gif}...")
        convert_mp4_to_gif(local_mp4, output_gif)
        print(f"Successfully created: {output_gif} ({os.path.getsize(output_gif)} bytes)")
    finally:
        # Cleanup remote temporary file
        try:
            rm_cmd = build_rm_cmd(serial, remote_tmp)
            subprocess.run(rm_cmd, check=False, capture_output=True)
        except Exception:
            pass
        # Cleanup local temporary file
        if os.path.exists(local_mp4):
            try:
                os.remove(local_mp4)
            except OSError:
                pass


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Record Android screen via adb and convert to a lightweight GIF."
    )
    parser.add_argument(
        "--out",
        required=True,
        help="Destination path for GIF (e.g. docs/media/gif/01-login-register.gif)",
    )
    parser.add_argument(
        "--seconds",
        type=int,
        required=True,
        help="Recording duration in seconds (capped at 180s)",
    )
    parser.add_argument(
        "--serial",
        default=None,
        help="Specific adb device serial number (optional)",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    try:
        record_screen_to_gif(
            output_gif=args.out,
            seconds=args.seconds,
            serial=args.serial,
        )
        return 0
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
