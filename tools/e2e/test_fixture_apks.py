#!/usr/bin/env python3
"""tools/e2e/test_fixture_apks.py - Offline unit tests for fixture_apks.py.

Stdlib only. Uses a local http.server to test:
- ok download and verification
- wrong checksum rejected and temp file cleaned up
- truncated download leaves no final file
- existing verified file is reused without re-download
- describe / parser / signer assertions
"""

from __future__ import annotations

import collections
import hashlib
import http.server
import os
import shutil
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from tools.e2e.fixture_apks import (
    assert_shared_signer,
    compute_file_sha256,
    describe,
    fetch,
    find_android_tools,
    normalize_tag,
    parse_aapt_badging,
    parse_apksigner_output,
    parse_sha256,
    resolve_apk_argument,
)

FAKE_APK_CONTENT = b"PK\x03\x04\x14\x00fake-apk-binary-content-1234567890"
FAKE_APK_HASH = hashlib.sha256(FAKE_APK_CONTENT).hexdigest().lower()
WRONG_HASH = "0000000000000000000000000000000000000000000000000000000000000000"


class MockReleaseServer(http.server.HTTPServer):
    def __init__(self, server_address, handler_class):
        super().__init__(server_address, handler_class)
        self.request_counts = collections.defaultdict(int)


class MockReleaseHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Suppress standard logging during tests
        pass

    def do_GET(self):
        self.server.request_counts[self.path] += 1

        # 1. OK route
        if self.path == "/ok/v1.0.0/nexachat-v1.0.0.apk.sha256":
            body = f"{FAKE_APK_HASH}  nexachat-v1.0.0.apk\n".encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if self.path == "/ok/v1.0.0/nexachat-v1.0.0.apk":
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(len(FAKE_APK_CONTENT)))
            self.end_headers()
            self.wfile.write(FAKE_APK_CONTENT)
            return

        # 2. Bad checksum route
        if self.path == "/bad-sha/v1.0.0/nexachat-v1.0.0.apk.sha256":
            body = f"{WRONG_HASH}  nexachat-v1.0.0.apk\n".encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if self.path == "/bad-sha/v1.0.0/nexachat-v1.0.0.apk":
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(len(FAKE_APK_CONTENT)))
            self.end_headers()
            self.wfile.write(FAKE_APK_CONTENT)
            return

        # 3. Truncated route
        if self.path == "/trunc/v1.0.0/nexachat-v1.0.0.apk.sha256":
            body = f"{FAKE_APK_HASH}  nexachat-v1.0.0.apk\n".encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if self.path == "/trunc/v1.0.0/nexachat-v1.0.0.apk":
            # Declare 10,000 bytes but only send 10 bytes and close connection abruptly
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", "10000")
            self.end_headers()
            self.wfile.write(b"0123456789")
            self.wfile.flush()
            self.close_connection = True
            return

        # 4. Reuse route
        if self.path == "/reuse/v1.0.0/nexachat-v1.0.0.apk.sha256":
            body = f"{FAKE_APK_HASH}  nexachat-v1.0.0.apk\n".encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if self.path == "/reuse/v1.0.0/nexachat-v1.0.0.apk":
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(len(FAKE_APK_CONTENT)))
            self.end_headers()
            self.wfile.write(FAKE_APK_CONTENT)
            return

        self.send_response(404)
        self.end_headers()


class TestFetchOffline(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = MockReleaseServer(("127.0.0.1", 0), MockReleaseHandler)
        cls.server_thread = threading.Thread(target=cls.server.serve_forever)
        cls.server_thread.daemon = True
        cls.server_thread.start()
        cls.port = cls.server.server_port
        cls.base_url = f"http://127.0.0.1:{cls.port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.server_thread.join(timeout=2.0)

    def setUp(self):
        self.test_dir = Path(tempfile.mkdtemp(prefix="nexachat_test_fixtures_"))

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_fetch_ok(self):
        """Test successful download, checksum verification, and atomic file write."""
        base = f"{self.base_url}/ok"
        result_path = fetch("v1.0.0", dest_dir=self.test_dir, base_url=base)

        expected_apk = self.test_dir / "nexachat-v1.0.0.apk"
        expected_sha = self.test_dir / "nexachat-v1.0.0.apk.sha256"

        self.assertEqual(result_path, expected_apk)
        self.assertTrue(expected_apk.is_file(), "APK file should exist")
        self.assertTrue(expected_sha.is_file(), "Checksum file should exist")
        self.assertEqual(expected_apk.read_bytes(), FAKE_APK_CONTENT)
        self.assertEqual(compute_file_sha256(expected_apk), FAKE_APK_HASH)

    def test_fetch_wrong_checksum_rejected(self):
        """Test that a checksum mismatch refuses the download and leaves no final file."""
        base = f"{self.base_url}/bad-sha"
        expected_apk = self.test_dir / "nexachat-v1.0.0.apk"

        with self.assertRaises(ValueError) as ctx:
            fetch("v1.0.0", dest_dir=self.test_dir, base_url=base)

        self.assertIn("Checksum mismatch", str(ctx.exception))
        self.assertFalse(expected_apk.exists(), "Final APK file must NOT exist after checksum failure")

        # Ensure no dangling temporary files remain
        tmp_files = list(self.test_dir.glob(".*.tmp"))
        self.assertEqual(tmp_files, [], "No temporary files should remain")

    def test_fetch_truncated_download_leaves_no_final_file(self):
        """Test that a truncated download aborts and leaves no final file."""
        base = f"{self.base_url}/trunc"
        expected_apk = self.test_dir / "nexachat-v1.0.0.apk"

        with self.assertRaises(Exception):
            fetch("v1.0.0", dest_dir=self.test_dir, base_url=base)

        self.assertFalse(expected_apk.exists(), "Final APK file must NOT exist after truncated download")
        tmp_files = list(self.test_dir.glob(".*.tmp"))
        self.assertEqual(tmp_files, [], "No temporary files should remain")

    def test_existing_verified_file_reused_without_redownload(self):
        """Test that an existing verified file is reused without fetching from network."""
        base = f"{self.base_url}/reuse"
        apk_path = self.test_dir / "nexachat-v1.0.0.apk"
        sha_path = self.test_dir / "nexachat-v1.0.0.apk.sha256"

        # Pre-seed the verified fixture and checksum
        apk_path.write_bytes(FAKE_APK_CONTENT)
        sha_path.write_text(f"{FAKE_APK_HASH}  nexachat-v1.0.0.apk\n", encoding="utf-8")

        apk_url_path = "/reuse/v1.0.0/nexachat-v1.0.0.apk"
        initial_requests = self.server.request_counts[apk_url_path]

        result_path = fetch("v1.0.0", dest_dir=self.test_dir, base_url=base)

        self.assertEqual(result_path, apk_path)
        # Verify no network request was made for the APK asset
        self.assertEqual(
            self.server.request_counts[apk_url_path],
            initial_requests,
            "Existing verified file should not trigger an APK download",
        )


class TestDescribeAndHelpers(unittest.TestCase):
    def test_normalize_tag(self):
        self.assertEqual(normalize_tag("1.1.0"), "v1.1.0")
        self.assertEqual(normalize_tag("v1.1.0"), "v1.1.0")
        self.assertEqual(normalize_tag("custom-tag"), "custom-tag")

    def test_parse_sha256(self):
        valid_hash = "e7419a48696b99b5832a83e028b17b6dcba6a666324db65cba2796dc9d84bfda"
        raw1 = f"{valid_hash}  nexachat-v1.1.0.apk\n"
        self.assertEqual(parse_sha256(raw1, "nexachat-v1.1.0.apk"), valid_hash)

        raw2 = f" {valid_hash} \n"
        self.assertEqual(parse_sha256(raw2), valid_hash)

        with self.assertRaises(ValueError):
            parse_sha256("invalid checksum content")

    def test_parse_aapt_badging(self):
        sample_output = """
package: name='com.nexachat' versionCode='1001001' versionName='1.1.1' compileSdkVersion='34'
sdkVersion:'26'
targetSdkVersion:'34'
application-label:'NexaChat'
"""
        parsed = parse_aapt_badging(sample_output)
        self.assertEqual(parsed["package"], "com.nexachat")
        self.assertEqual(parsed["versionCode"], "1001001")
        self.assertEqual(parsed["versionName"], "1.1.1")

    def test_parse_aapt_badging_invalid(self):
        with self.assertRaises(ValueError):
            parse_aapt_badging("invalid output without package")

    def test_parse_apksigner_output(self):
        sample_output = """
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
Signer #1 certificate DN: CN=NexaChat Dev, OU=Mobile, O=NexaChat
Signer #1 certificate SHA-256 digest: e7419a48696b99b5832a83e028b17b6dcba6a666324db65cba2796dc9d84bfda
Signer #1 certificate SHA-1 digest: 0123456789abcdef0123456789abcdef01234567
"""
        parsed = parse_apksigner_output(sample_output)
        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0], "e7419a48696b99b5832a83e028b17b6dcba6a666324db65cba2796dc9d84bfda")

    def test_missing_android_home(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(RuntimeError) as ctx:
                find_android_tools()
            self.assertIn("ANDROID_HOME", str(ctx.exception))

    def test_describe_nonexistent_file(self):
        with self.assertRaises(FileNotFoundError):
            describe("/path/to/nonexistent/apk.apk")

    def test_assert_shared_signer_match(self):
        fake_info = {
            "apk": "dummy.apk",
            "package": "com.nexachat",
            "versionCode": "1001000",
            "versionName": "1.1.0",
            "signer_sha256": "e7419a48696b99b5832a83e028b17b6dcba6a666324db65cba2796dc9d84bfda",
        }
        with patch("tools.e2e.fixture_apks.describe", return_value=fake_info):
            self.assertTrue(assert_shared_signer("apk1.apk", "apk2.apk"))

    def test_assert_shared_signer_mismatch(self):
        info1 = {
            "apk": "apk1.apk",
            "signer_sha256": "e7419a48696b99b5832a83e028b17b6dcba6a666324db65cba2796dc9d84bfda",
        }
        info2 = {
            "apk": "apk2.apk",
            "signer_sha256": "1111111111111111111111111111111111111111111111111111111111111111",
        }
        with patch("tools.e2e.fixture_apks.describe", side_effect=[info1, info2]):
            with self.assertRaises(AssertionError) as ctx:
                assert_shared_signer("apk1.apk", "apk2.apk")
            self.assertIn("do not share the same signer certificate", str(ctx.exception))

    def test_resolve_apk_argument(self):
        with tempfile.TemporaryDirectory() as tmp:
            dest_dir = Path(tmp)
            f1 = dest_dir / "nexachat-v1.1.0.apk"
            f1.touch()

            self.assertEqual(resolve_apk_argument("v1.1.0", dest_dir), f1)
            self.assertEqual(resolve_apk_argument("1.1.0", dest_dir), f1)
            self.assertEqual(resolve_apk_argument(str(f1), dest_dir), f1)


if __name__ == "__main__":
    unittest.main()
