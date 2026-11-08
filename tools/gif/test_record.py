#!/usr/bin/env python3
"""Unit tests for tools/gif/record.py command construction and size-retry logic."""
from __future__ import annotations

import unittest
from tools.gif.record import (
    MAX_DURATION_SECONDS,
    MAX_GIF_BYTES,
    PALETTE_FILTER_PRIMARY,
    PALETTE_FILTER_FALLBACK,
    build_screenrecord_cmd,
    build_pull_cmd,
    build_rm_cmd,
    build_ffmpeg_cmd,
    convert_mp4_to_gif,
    parse_args,
)


class TestGifRecord(unittest.TestCase):

    def test_build_screenrecord_cmd_standard(self):
        cmd = build_screenrecord_cmd(
            serial="emulator-5554",
            seconds=15,
            device_path="/data/local/tmp/test.mp4",
        )
        expected = [
            "adb", "-s", "emulator-5554", "shell", "screenrecord",
            "--time-limit", "15", "--size", "720x1280", "--bit-rate", "4M",
            "/data/local/tmp/test.mp4"
        ]
        self.assertEqual(cmd, expected)

    def test_build_screenrecord_cmd_no_serial(self):
        cmd = build_screenrecord_cmd(
            serial=None,
            seconds=30,
            device_path="/data/local/tmp/test.mp4",
        )
        self.assertEqual(cmd[0], "adb")
        self.assertNotIn("-s", cmd)
        self.assertIn("--time-limit", cmd)
        self.assertEqual(cmd[cmd.index("--time-limit") + 1], "30")

    def test_build_screenrecord_cmd_caps_at_180s(self):
        cmd = build_screenrecord_cmd(
            serial=None,
            seconds=240,
            device_path="/data/local/tmp/test.mp4",
        )
        self.assertIn("--time-limit", cmd)
        self.assertEqual(
            cmd[cmd.index("--time-limit") + 1],
            str(MAX_DURATION_SECONDS),
        )

    def test_build_pull_cmd(self):
        cmd = build_pull_cmd(
            serial="device-1",
            device_path="/data/local/tmp/rec.mp4",
            local_path="local.mp4",
        )
        self.assertEqual(cmd, ["adb", "-s", "device-1", "pull", "/data/local/tmp/rec.mp4", "local.mp4"])

    def test_build_rm_cmd(self):
        cmd = build_rm_cmd(
            serial=None,
            device_path="/data/local/tmp/rec.mp4",
        )
        self.assertEqual(cmd, ["adb", "shell", "rm", "-f", "/data/local/tmp/rec.mp4"])

    def test_build_ffmpeg_cmd(self):
        cmd = build_ffmpeg_cmd("in.mp4", "out.gif", PALETTE_FILTER_PRIMARY)
        self.assertEqual(cmd, ["ffmpeg", "-y", "-i", "in.mp4", "-vf", PALETTE_FILTER_PRIMARY, "out.gif"])

    def test_convert_mp4_to_gif_primary_success(self):
        calls = []

        def mock_runner(cmd):
            calls.append(cmd)

        def mock_sizer(path):
            return 3 * 1024 * 1024  # 3 MB, within 6 MB cap

        res = convert_mp4_to_gif("in.mp4", "out.gif", run_cmd=mock_runner, get_size=mock_sizer)
        self.assertTrue(res)
        self.assertEqual(len(calls), 1)
        self.assertIn(PALETTE_FILTER_PRIMARY, calls[0])

    def test_convert_mp4_to_gif_retries_with_fallback_when_exceeding_cap(self):
        calls = []
        sizes = [8 * 1024 * 1024, 4 * 1024 * 1024]  # 8 MB first, then 4 MB on fallback

        def mock_runner(cmd):
            calls.append(cmd)

        def mock_sizer(path):
            return sizes.pop(0)

        res = convert_mp4_to_gif("in.mp4", "out.gif", run_cmd=mock_runner, get_size=mock_sizer)
        self.assertTrue(res)
        self.assertEqual(len(calls), 2)
        self.assertIn(PALETTE_FILTER_PRIMARY, calls[0])
        self.assertIn(PALETTE_FILTER_FALLBACK, calls[1])

    def test_convert_mp4_to_gif_fails_if_fallback_still_exceeds_cap(self):
        calls = []
        sizes = [9 * 1024 * 1024, 7 * 1024 * 1024]  # Both exceed 6 MB cap

        def mock_runner(cmd):
            calls.append(cmd)

        def mock_sizer(path):
            return sizes.pop(0)

        with self.assertRaises(ValueError) as ctx:
            convert_mp4_to_gif("in.mp4", "out.gif", run_cmd=mock_runner, get_size=mock_sizer)
        self.assertIn("exceeds size cap", str(ctx.exception))
        self.assertEqual(len(calls), 2)

    def test_parse_args(self):
        args = parse_args(["--out", "docs/media/gif/01-test.gif", "--seconds", "15", "--serial", "emu-1"])
        self.assertEqual(args.out, "docs/media/gif/01-test.gif")
        self.assertEqual(args.seconds, 15)
        self.assertEqual(args.serial, "emu-1")


if __name__ == "__main__":
    unittest.main()
