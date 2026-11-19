"""World-model tests for media_oracle: fixtures, byte oracle, REST helpers."""

from __future__ import annotations

import hashlib
import io
import json
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import media_oracle as oracle
from e2e_common import Response


class FixtureDeterminismTest(unittest.TestCase):
    def test_every_fixture_builds_identical_bytes_twice(self):
        for name in oracle.FIXTURE_NAMES:
            with self.subTest(name=name):
                first = oracle.build_fixture(name)
                second = oracle.build_fixture(name)
                self.assertEqual(first.data, second.data)
                self.assertEqual(first.sha256, second.sha256)
                self.assertTrue(first.filename)
                self.assertTrue(first.mime_type)

    def test_seed_changes_the_payload_where_supported(self):
        self.assertNotEqual(
            oracle.build_fixture("png", seed=1).data,
            oracle.build_fixture("png", seed=2).data,
        )
        self.assertNotEqual(
            oracle.build_fixture("pdf", seed=1).sha256,
            oracle.build_fixture("pdf", seed=2).sha256,
        )
        # jpeg is a fixed canonical blob: seed does not apply.
        self.assertEqual(
            oracle.build_fixture("jpeg", seed=1).data,
            oracle.build_fixture("jpeg", seed=2).data,
        )

    def test_unknown_fixture_name_is_rejected(self):
        with self.assertRaisesRegex(oracle.MediaOracleError, "unknown fixture"):
            oracle.build_fixture("webp")


class PngWriterTest(unittest.TestCase):
    def _chunks(self, data: bytes):
        self.assertTrue(data.startswith(oracle.PNG_SIGNATURE))
        pos = len(oracle.PNG_SIGNATURE)
        out = []
        while pos < len(data):
            length = struct.unpack(">I", data[pos:pos + 4])[0]
            tag = data[pos + 4:pos + 8]
            payload = data[pos + 8:pos + 8 + length]
            crc = struct.unpack(">I", data[pos + 8 + length:pos + 12 + length])[0]
            self.assertEqual(crc, oracle.binascii.crc32(tag + payload) & 0xFFFFFFFF)
            out.append((tag, payload))
            pos += 12 + length
        return out

    def test_png_structure_is_valid_and_decodable(self):
        fixture = oracle.png_fixture(seed=7, width=16, height=10)
        chunks = self._chunks(fixture.data)
        tags = [tag for tag, _ in chunks]
        self.assertEqual(tags, [b"IHDR", b"IDAT", b"IEND"])
        width, height, depth, color, comp, filt, interlace = struct.unpack(
            ">IIBBBBB", chunks[0][1]
        )
        self.assertEqual((width, height, depth, color, comp, filt, interlace),
                         (16, 10, 8, 2, 0, 0, 0))
        raw = zlib.decompress(chunks[1][1])
        # height scanlines, each = 1 filter byte + width*3 RGB bytes.
        self.assertEqual(len(raw), 10 * (1 + 16 * 3))
        self.assertTrue(all(raw[y * (1 + 48)] == 0 for y in range(10)))

    def test_png_rejects_nonpositive_dimensions(self):
        with self.assertRaises(oracle.MediaOracleError):
            oracle.render_png(0, 4, lambda x, y: (0, 0, 0))
        with self.assertRaises(oracle.MediaOracleError):
            oracle.png_fixture(width=-1)


class MimeSniffParityTest(unittest.TestCase):
    """Fixtures must carry the magic bytes the server's MagicBytes expects."""

    def test_fixture_magic_bytes_match_server_signatures(self):
        cases = {
            "png": bytes.fromhex("89504E47"),    # MagicBytes.EXACT_SIGNATURES
            "jpeg": bytes.fromhex("FFD8FF"),
            "pdf": b"%PDF-",
        }
        for name, magic in cases.items():
            with self.subTest(name=name):
                self.assertTrue(oracle.build_fixture(name).data.startswith(magic))

    def test_pdf_fixture_has_xref_and_eof(self):
        data = oracle.pdf_fixture().data
        self.assertIn(b"xref\n", data)
        self.assertIn(b"trailer\n", data)
        self.assertTrue(data.rstrip().endswith(b"%%EOF"))
        self.assertIn(b"nexachat-e2e-media-", data)


class VerifyResultTest(unittest.TestCase):
    def test_identical_payloads_match(self):
        fixture = oracle.png_fixture()
        result = oracle.verify_payload(fixture, fixture.data)
        self.assertTrue(result.match)
        self.assertEqual(result.expected_sha256, fixture.sha256)
        self.assertEqual(result.actual_sha256, fixture.sha256)
        self.assertEqual(result.expected_size, fixture.size)
        self.assertIsNone(result.first_mismatch_offset)

    def test_content_mismatch_reports_first_diff_offset(self):
        fixture = oracle.pdf_fixture()
        corrupted = bytearray(fixture.data)
        corrupted[10] ^= 0xFF
        result = oracle.verify_payload(fixture, bytes(corrupted))
        self.assertFalse(result.match)
        self.assertEqual(result.first_mismatch_offset, 10)
        self.assertNotEqual(result.expected_sha256, result.actual_sha256)
        self.assertEqual(result.expected_size, result.actual_size)

    def test_truncated_payload_is_a_mismatch(self):
        fixture = oracle.jpeg_fixture()
        result = oracle.verify_payload(fixture, fixture.data[:-4])
        self.assertFalse(result.match)
        self.assertIsNone(result.first_mismatch_offset)
        self.assertEqual(result.expected_size - 4, result.actual_size)

    def test_sources_may_be_paths_fixtures_or_bytes(self):
        fixture = oracle.text_fixture()
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "payload.txt"
            path.write_bytes(fixture.data)
            self.assertEqual(oracle.sha256_hex(path), fixture.sha256)
            self.assertEqual(oracle.sha256_hex(fixture.data), fixture.sha256)
            self.assertTrue(oracle.verify_payload(fixture, path).match)
            missing = Path(tmp) / "nope.bin"
            with self.assertRaises(oracle.MediaOracleError):
                oracle.verify_payload(missing, fixture.data)


class MultipartEncodingTest(unittest.TestCase):
    def test_body_has_single_file_part_and_fields(self):
        fixture = oracle.png_fixture()
        body, content_type = oracle.encode_multipart(
            fixture.data, fixture.filename, fixture.mime_type,
            {"width": "32", "height": "24"}, boundary="BOUNDARY")
        self.assertIn("boundary=BOUNDARY", content_type)
        self.assertIn(b'name="file"; filename="e2e-png-', body)
        self.assertIn(b'Content-Type: image/png\r\n\r\n', body)
        self.assertIn(b'name="width"\r\n\r\n32', body)
        self.assertIn(b'name="height"\r\n\r\n24', body)
        self.assertTrue(body.endswith(b"\r\n--BOUNDARY--\r\n"))
        self.assertEqual(body.count(b'name="file"'), 1)
        self.assertIn(fixture.data, body)

    def test_unsafe_filename_is_rejected(self):
        with self.assertRaises(oracle.MediaOracleError):
            oracle.encode_multipart(b"x", 'evil"\r\n', "text/plain")


class _FakeClient:
    """Captures the last request and replays canned responses."""

    def __init__(self):
        self.requests = []
        self.responses = []

    def request(self, method, path, **kwargs):
        self.requests.append((method, path, kwargs))
        return self.responses.pop(0)

    def get(self, path, token=None, params=None, headers=None):
        self.requests.append(("GET", path, {"token": token, "headers": headers}))
        return self.responses.pop(0)


class RestHelpersTest(unittest.TestCase):
    def test_upload_posts_multipart_and_returns_dto(self):
        client = _FakeClient()
        attachment = {
            "id": "11111111-2222-3333-4444-555555555555",
            "url": "/api/v1/files/attachments/x.png",
            "mimeType": "image/png",
            "sizeBytes": oracle.png_fixture().size,
        }
        client.responses.append(Response(201, {}, json.dumps(attachment).encode()))
        result = oracle.upload_attachment(
            client, "tok", oracle.png_fixture(), width=32, height=24)
        self.assertEqual(result["id"], attachment["id"])
        method, path, kwargs = client.requests[0]
        self.assertEqual((method, path), ("POST", "/api/v1/attachments"))
        body, content_type = kwargs["raw_body"]
        self.assertIn("multipart/form-data", content_type)
        self.assertIn(b'name="file"', body)
        self.assertIn(b'name="width"\r\n\r\n32', body)
        self.assertEqual(kwargs["token"], "tok")

    def test_download_and_verify_roundtrip(self):
        fixture = oracle.pdf_fixture()
        client = _FakeClient()
        attachment = {"id": "a" * 36, "mimeType": "application/pdf",
                      "sizeBytes": fixture.size, "url": "/api/v1/files/attachments/x.pdf"}
        client.responses.append(Response(201, {}, json.dumps(attachment).encode()))
        client.responses.append(Response(200, {}, fixture.data))
        result = oracle.upload_and_verify(client, "tok", fixture)
        self.assertTrue(result["ok"], result)
        self.assertTrue(result["verify"]["match"])
        self.assertEqual(client.requests[1][1],
                         "/api/v1/attachments/" + "a" * 36 + "/content")

    def test_upload_failure_surfaces_status(self):
        client = _FakeClient()
        client.responses.append(Response(415, {}, b'{"error":{"code":"MIME_NOT_ALLOWED"}}'))
        with self.assertRaises(Exception):
            oracle.upload_attachment(client, "tok", oracle.png_fixture())

    def test_fetch_file_sends_range_header(self):
        fixture = oracle.pdf_fixture()
        client = _FakeClient()
        client.responses.append(
            Response(206, {"Content-Range": "bytes 2-9/100"}, fixture.data[2:10]))
        result = oracle.check_range(client, "tok", "attachments/x.pdf", fixture, 2, 9)
        self.assertTrue(result["ok"], result)
        self.assertEqual(result["status"], 206)
        self.assertTrue(result["slice_match"])
        _, path, kwargs = client.requests[0]
        self.assertEqual(path, "/api/v1/files/attachments/x.pdf")
        self.assertEqual(kwargs["headers"], {"Range": "bytes=2-9"})

    def test_check_range_rejects_bad_bounds(self):
        with self.assertRaises(oracle.MediaOracleError):
            oracle.check_range(_FakeClient(), "t", "k", oracle.pdf_fixture(), 9, 2)


class CliTest(unittest.TestCase):
    def test_list_and_hash(self):
        self.assertEqual(oracle.main(["list"]), 0)
        fixture = oracle.text_fixture()
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "f.txt"
            path.write_bytes(fixture.data)
            out = io.StringIO()
            with mock.patch("sys.stdout", out):
                self.assertEqual(oracle.main(["hash", str(path)]), 0)
            self.assertEqual(out.getvalue().strip(), fixture.sha256)
            out = io.StringIO()
            with mock.patch("sys.stdout", out):
                self.assertEqual(oracle.main(["verify", "txt", str(path)]), 0)
            self.assertTrue(json.loads(out.getvalue())["match"])
            # corruption -> exit 1
            path.write_bytes(fixture.data + b"x")
            with mock.patch("sys.stdout", io.StringIO()):
                self.assertEqual(oracle.main(["verify", "txt", str(path)]), 1)


if __name__ == "__main__":
    unittest.main()
