"""Offline tests for media_driver: push/hash chain, picker parsing, oracle.

Mirrors the existing fake-adapter style (no device, no server): a fake Adb
serves ``push``/``sha256sum``/``pull``/``dumpsys``, and a fake client plays
the attachment REST surface (multipart upload, message send, content/files
serving with Range and the owner/member/non-member rule). The tests prove the
oracle math end to end (fixture bytes -> device hash -> upload -> served hash)
and the error classification the journey statuses rely on: MediaDeviceError
for harness/device failures, MediaProductError for broken product contracts.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
import tempfile
import unittest
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import media_driver as driver
from e2e_common import Response


class FakeAdb:
    """Structured-argv fake mirroring stable_journeys.Adb's contract."""

    def __init__(self):
        self.files: dict[str, bytes] = {}
        self.commands: list[tuple[str, ...]] = []
        self.sha256sum_broken = False
        self.sha256sum_override: dict[str, str] = {}
        self.pull_corrupts = False
        self.fail_push = False
        self.dumpsys = ""

    def command(self, *args, timeout=60.0, check=True):
        argv = tuple(str(value) for value in args)
        self.commands.append(argv)
        if argv[0] == "push":
            if self.fail_push:
                raise RuntimeError("adb push refused by device")
            _cmd, local, remote = argv
            self.files[remote] = Path(local).read_bytes()
            return "1 file pushed"
        if argv[:2] == ("shell", "sha256sum"):
            remote = argv[2]
            if self.sha256sum_broken:
                return "sha256sum: not found"
            if remote in self.sha256sum_override:
                return self.sha256sum_override[remote]
            if remote not in self.files:
                return f"sha256sum: {remote}: No such file or directory"
            digest = hashlib.sha256(self.files[remote]).hexdigest()
            return f"{digest}  {remote}"
        if argv[0] == "pull":
            _cmd, remote, local = argv
            data = self.files.get(remote)
            if data is None:
                raise RuntimeError(f"adb pull: {remote} does not exist")
            if self.pull_corrupts:
                data = data + b"\x00"
            Path(local).write_bytes(data)
            return "1 file pulled"
        if argv[:2] == ("shell", "rm"):
            self.files.pop(argv[-1], None)
            return ""
        if argv[:3] == ("shell", "dumpsys", "activity"):
            return self.dumpsys
        if argv[:3] == ("shell", "cmd", "statusbar"):
            return ""
        raise AssertionError(f"unexpected adb command {argv}")


class PushFixtureTest(unittest.TestCase):
    def test_push_writes_host_copy_pushes_and_verifies_on_device(self):
        fixture = driver.build_fixture("pdf")
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as host_dir:
            pushed = driver.push_fixture(adb, fixture, host_dir=host_dir)
            # The host copy in the evidence dir holds the exact pushed bytes.
            host_copy = Path(pushed["host_copy"])
            self.assertEqual(host_copy.name, fixture.filename)
            self.assertEqual(host_copy.read_bytes(), fixture.data)
        self.assertEqual(pushed["remote_path"], f"/sdcard/Download/{fixture.filename}")
        self.assertEqual(pushed["sha256"], fixture.sha256)
        self.assertEqual(pushed["verified_by"], "sha256sum")
        self.assertEqual(pushed["size_bytes"], fixture.size)
        self.assertIn(("shell", "sha256sum", pushed["remote_path"]), adb.commands)
        push_argv = next(argv for argv in adb.commands if argv[0] == "push")
        self.assertEqual(push_argv[2], pushed["remote_path"])
        # And the device holds the same bytes.
        self.assertEqual(adb.files[pushed["remote_path"]], fixture.data)

    def test_unusable_sha256sum_falls_back_to_pull_digest(self):
        fixture = driver.build_fixture("png")
        adb = FakeAdb()
        adb.sha256sum_broken = True
        with tempfile.TemporaryDirectory() as host_dir:
            pushed = driver.push_fixture(adb, fixture, host_dir=host_dir)
        self.assertEqual(pushed["sha256"], fixture.sha256)
        self.assertEqual(pushed["verified_by"], "pull")

    def test_digest_mismatch_on_device_is_a_harness_error(self):
        fixture = driver.build_fixture("txt")
        adb = FakeAdb()
        adb.sha256sum_broken = True
        adb.pull_corrupts = True
        with tempfile.TemporaryDirectory() as host_dir:
            with self.assertRaisesRegex(
                driver.MediaDeviceError, "hash to .* expected"
            ):
                driver.push_fixture(adb, fixture, host_dir=host_dir)
            # The corrupting pull left no fallback file behind.
            leftovers = [p for p in Path(host_dir).glob("pulled-*")]
            self.assertEqual(leftovers, [])

    def test_sha256sum_reporting_wrong_digest_is_caught(self):
        fixture = driver.build_fixture("txt")
        adb = FakeAdb()
        remote = f"/sdcard/Download/{fixture.filename}"
        # Prime the file so the push succeeds, then poison the digest line.
        adb.files[remote] = fixture.data
        adb.sha256sum_override[remote] = (
            "0000000000000000000000000000000000000000000000000000000000000000  " + remote
        )
        with tempfile.TemporaryDirectory() as host_dir:
            with self.assertRaisesRegex(driver.MediaDeviceError, "hash to"):
                driver.push_fixture(adb, fixture, host_dir=host_dir)

    def test_failed_push_is_a_harness_error(self):
        adb = FakeAdb()
        adb.fail_push = True
        with tempfile.TemporaryDirectory() as host_dir:
            with self.assertRaisesRegex(driver.MediaDeviceError, "adb push"):
                driver.push_fixture(
                    adb, driver.build_fixture("pdf"), host_dir=host_dir
                )

    def test_unsafe_fixture_filename_is_refused_before_any_adb_call(self):
        from media_oracle import Fixture

        adb = FakeAdb()
        evil = Fixture("evil", "../escape.pdf", "application/pdf", b"%PDF-1.4\n")
        with tempfile.TemporaryDirectory() as host_dir:
            with self.assertRaisesRegex(driver.MediaDeviceError, "push-safe"):
                driver.push_fixture(adb, evil, host_dir=host_dir)
        self.assertEqual(adb.commands, [])


class PickerComponentTest(unittest.TestCase):
    def test_top_resumed_activity_is_parsed_on_new_apis(self):
        dump = (
            "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
            "  topResumedActivity=ComponentInfo{"
            "com.android.documentsui/com.android.documentsui.picker.PickActivity}\n"
        )
        component = driver.resumed_activity_component(dump)
        self.assertEqual(
            component, "com.android.documentsui/com.android.documentsui.picker.PickActivity"
        )
        self.assertTrue(driver.is_system_picker_component(component))

    def test_m_resumed_activity_is_parsed_on_older_apis(self):
        dump = (
            "  mResumedActivity=ComponentInfo{"
            "com.google.android.providers.media.module/"
            "com.google.android.providers.media.photopicker.activity.PickerActivity}\n"
        )
        component = driver.resumed_activity_component(dump)
        self.assertTrue(driver.is_system_picker_component(component))

    def test_app_components_are_not_system_pickers(self):
        dump = (
            "  topResumedActivity=ComponentInfo{"
            "com.nexachat.debug/com.nexachat.MainActivity}\n"
        )
        component = driver.resumed_activity_component(dump)
        self.assertFalse(driver.is_system_picker_component(component))
        self.assertFalse(driver.is_system_picker_component(None))
        self.assertIsNone(driver.resumed_activity_component(""))


class AttachmentStore:
    """Server truth: uploaded bytes, bound messages, membership rule."""

    def __init__(self, mutate_served=None, upload_status=201,
                 outsider_is_member=False):
        self.attachments: dict[str, dict] = {}
        self.messages: list[dict] = []
        self.deleted: list[str] = []
        self.serve_mutate = mutate_served
        self.upload_status = upload_status
        self.outsider_is_member = outsider_is_member
        self.upload_requests: list[dict] = []
        self.range_requests: list[str | None] = []

    def can_read(self, token: str, attachment: dict) -> bool:
        if attachment["owner"] == token or token in attachment["members"]:
            return True
        return self.outsider_is_member and attachment["owner"] != token


class FakeClient:
    """The attachment REST surface as the fake server sees it."""

    base_url = "http://127.0.0.1:18093"
    timeout = 30

    def __init__(self, store: AttachmentStore):
        self.store = store

    def request(self, method, path, token=None, body=None, params=None,
                raw_body=None, headers=None):
        if method == "POST" and path == "/api/v1/attachments":
            return self._upload(raw_body)
        if method == "POST" and path.endswith("/messages"):
            return self._send_message(path, token, body)
        if method == "GET":
            return self._get(path, token, headers)
        if method == "DELETE" and "/messages/" in path:
            message_id = path.rsplit("/", 1)[1]
            message = next(
                (m for m in self.store.messages if m["id"] == message_id), None)
            if message is None:
                return Response(404, {}, b'{"error":{"code":"NOT_FOUND"}}')
            message["deletedAt"] = "2026-01-01T00:00:00Z"
            self.store.deleted.append(message_id)
            return Response(204, {}, b"")
        return Response(404, {}, b'{"error":{"code":"NOT_FOUND"}}')

    def get(self, path, token=None, params=None, headers=None):
        return self.request("GET", path, token=token, headers=headers)

    def post(self, path, token=None, body=None, headers=None):
        return self.request("POST", path, token=token, body=body, headers=headers)

    def delete(self, path, token=None, params=None, headers=None):
        return self.request("DELETE", path, token=token, params=params,
                            headers=headers)

    def _upload(self, raw_body):
        data, content_type = raw_body
        boundary = re.search(r"boundary=(\S+)", content_type).group(1)
        self.store.upload_requests.append(
            {"size": len(data), "content_type": content_type})
        if self.store.upload_status != 201:
            return Response(
                self.store.upload_status, {},
                b'{"error":{"code":"MIME_NOT_ALLOWED"}}')
        parts = data.split((f"--{boundary}").encode())
        filename = mime = None
        fields: dict[str, str] = {}
        payload = b""
        for part in parts:
            if b'name="file"' not in part:
                match = re.search(rb'name="([^"]+)"\r\n\r\n([^\r]*)', part)
                if match:
                    fields[match.group(1).decode()] = match.group(2).decode()
                continue
            filename = re.search(rb'filename="([^"]+)"', part).group(1).decode()
            mime = re.search(rb"Content-Type: ([^\r]+)", part).group(1).decode()
            payload = part.split(b"\r\n\r\n", 1)[1]
            # Each middle segment still carries the CRLF that precedes the
            # next boundary; it is transport, not payload.
            if payload.endswith(b"\r\n"):
                payload = payload[:-2]
        if filename is None:
            return Response(400, {}, b'{"error":{"code":"VALIDATION_FAILED"}}')
        attachment_id = str(uuid.uuid4())
        attachment = {
            "id": attachment_id,
            "url": f"/api/v1/files/attachments/{attachment_id}",
            "originalName": filename,
            "mimeType": mime,
            "sizeBytes": len(payload),
            "width": fields.get("width"),
            "height": fields.get("height"),
            "owner": None,
            "members": {"tok-an", "tok-binh"},
            "data": payload,
        }
        self.store.attachments[attachment_id] = attachment
        return Response(201, {}, json.dumps(
            {key: value for key, value in attachment.items()
             if key not in ("owner", "members", "data")}).encode())

    def _send_message(self, path, token, body):
        attachment_ids = list((body or {}).get("attachmentIds") or [])
        attachment = self.store.attachments.get(attachment_ids[0]) if attachment_ids else None
        if attachment is None:
            return Response(404, {}, b'{"error":{"code":"ATTACHMENT_NOT_FOUND"}}')
        attachment["owner"] = token
        message = {"id": f"m-{len(self.store.messages) + 1}",
                   "conversationId": path.split("/")[4],
                   "type": (body or {}).get("type"),
                   "attachmentIds": attachment_ids,
                   "deletedAt": None}
        self.store.messages.append(message)
        return Response(201, {}, json.dumps(message).encode())

    def _serve(self, attachment, headers):
        data = attachment["data"]
        if self.store.serve_mutate is not None:
            data = self.store.serve_mutate(data)
        range_header = (headers or {}).get("Range")
        if range_header is not None:
            self.store.range_requests.append(range_header)
            start, end = (int(value) for value in
                          range_header[len("bytes="):].split("-"))
            end = min(end, len(data) - 1)
            slice_bytes = data[start:end + 1]
            return Response(206, {
                "Content-Range": f"bytes {start}-{end}/{len(data)}",
                "Content-Type": attachment["mimeType"],
            }, slice_bytes)
        return Response(200, {"Content-Type": attachment["mimeType"]}, data)

    def _get(self, path, token, headers):
        if path.startswith("/api/v1/attachments/") and path.endswith("/content"):
            attachment_id = path[len("/api/v1/attachments/"):-len("/content")]
            attachment = self.store.attachments.get(attachment_id)
            if attachment is None:
                return Response(404, {}, b'{"error":{"code":"ATTACHMENT_NOT_FOUND"}}')
            if not self.store.can_read(token, attachment):
                return Response(403, {}, b'{"error":{"code":"NOT_MEMBER"}}')
            return self._serve(attachment, headers)
        if path.startswith("/api/v1/files/"):
            key = path[len("/api/v1/files/"):]
            attachment = next(
                (a for a in self.store.attachments.values()
                 if a["url"].endswith("/" + key)), None)
            if attachment is None:
                return Response(404, {}, b'{"error":{"code":"ATTACHMENT_NOT_FOUND"}}')
            if not self.store.can_read(token, attachment):
                return Response(403, {}, b'{"error":{"code":"NOT_MEMBER"}}')
            return self._serve(attachment, headers)
        return Response(404, {}, b'{"error":{"code":"NOT_FOUND"}}')


class VerifyUploadSliceTest(unittest.TestCase):
    OWNER = {"accessToken": "tok-an"}
    MEMBER = {"accessToken": "tok-binh"}
    OUTSIDER = {"accessToken": "tok-chi"}

    def _run(self, store):
        client = FakeClient(store)
        return client, driver.verify_upload_slice(
            client,
            owner_token=self.OWNER["accessToken"],
            member_token=self.MEMBER["accessToken"],
            outsider_token=self.OUTSIDER["accessToken"],
            conversation_id="conv-an-binh",
            fixture=driver.build_fixture("pdf"),
            msg_type="FILE",
        )

    def test_happy_path_proves_bytes_range_and_refusal(self):
        store = AttachmentStore()
        client, result = self._run(store)
        self.assertTrue(result["ok"])
        self.assertTrue(result["owner_sha256_match"])
        self.assertTrue(result["member_sha256_match"])
        self.assertEqual(len(result["ranges"]), 2)
        self.assertIn("bytes=0-", result["ranges"][0]["range"])
        self.assertNotIn(result["ranges"][0]["range"], result["ranges"][1]["range"])
        for entry in result["ranges"]:
            self.assertEqual(entry["status"], 206)
            self.assertTrue(entry["slice_match"])
        refusal = result["non_member_refused"]
        self.assertEqual(refusal["content"],
                         {"status": 403, "refused_with": "NOT_MEMBER"})
        self.assertEqual(refusal["files"]["refused_with"], "NOT_MEMBER")
        # The upload carried the exact fixture bytes and the image dimensions.
        attachment = store.attachments[result["attachment"]["id"]]
        self.assertEqual(
            hashlib.sha256(attachment["data"]).hexdigest(),
            driver.build_fixture("pdf").sha256)
        self.assertEqual(result["attachment"]["sizeBytes"],
                         driver.build_fixture("pdf").size)
        self.assertEqual(result["attachment"]["mimeType"], "application/pdf")
        self.assertEqual(result["message_id"],
                         store.messages[0]["id"])
        self.assertEqual(store.messages[0]["type"], "FILE")

    def test_image_fixture_sends_width_and_height_fields(self):
        store = AttachmentStore()
        client = FakeClient(store)
        png = driver.build_fixture("png", width=32, height=24)
        result = driver.verify_upload_slice(
            client,
            owner_token=self.OWNER["accessToken"],
            member_token=self.MEMBER["accessToken"],
            outsider_token=self.OUTSIDER["accessToken"],
            conversation_id="conv-an-binh",
            fixture=png,
            msg_type="IMAGE",
            image_width=32,
            image_height=24,
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["msg_type"], "IMAGE")
        attachment = store.attachments[result["attachment"]["id"]]
        self.assertEqual(attachment["width"], "32")
        self.assertEqual(attachment["height"], "24")

    def test_served_byte_mutation_is_a_product_failure(self):
        store = AttachmentStore(mutate_served=lambda data: data + b"x")
        with self.assertRaises(driver.MediaProductError) as caught:
            self._run(store)
        self.assertIn("received bytes differ", str(caught.exception))
        # Taxonomy the stable_journeys wrappers rely on: product defects are
        # assertion failures, device/harness defects are plain exceptions.
        self.assertIsInstance(caught.exception, AssertionError)
        self.assertNotIsInstance(caught.exception, driver.MediaDeviceError)

    def test_non_member_read_is_a_product_failure(self):
        store = AttachmentStore(outsider_is_member=True)
        with self.assertRaisesRegex(
            driver.MediaProductError, "non-member .* expected 403, got 200"
        ):
            self._run(store)

    def test_refusal_with_wrong_error_code_is_a_product_failure(self):
        store = AttachmentStore()
        # Reuse a happy store, then re-check the refusal with a client whose
        # 403 body lies about the error code.
        client, result = self._run(store)  # baseline passes
        class LyingClient(FakeClient):
            def get(self, path, token=None, params=None, headers=None):
                response = super().get(path, token=token, params=params,
                                       headers=headers)
                if (token == "tok-chi" and response.status == 403
                        and "content" in path):
                    return Response(403, {}, b'{"error":{"code":"VALIDATION_FAILED"}}')
                return response

        lying = LyingClient(store)
        with self.assertRaisesRegex(
            driver.MediaProductError, "refusal code was 'VALIDATION_FAILED'"
        ):
            driver.non_member_refusal(
                lying, "tok-chi", result["attachment"]["id"],
                driver.storage_key_from_url(result["attachment"]["url"]))

    def test_upload_rejection_is_a_product_failure(self):
        store = AttachmentStore(upload_status=415)
        with self.assertRaisesRegex(driver.MediaProductError, "upload failed"):
            self._run(store)

    def test_range_without_206_is_a_product_failure(self):
        class FullBodyClient(FakeClient):
            def _serve(self, attachment, headers):
                # Drops the Range header: every serve is a 200 whole body.
                return super()._serve(attachment, None)

        store = AttachmentStore()
        client = FullBodyClient(store)
        fixture = driver.build_fixture("pdf")
        attachment = driver.upload_fixture(client, "tok-an", fixture)
        driver.send_attachment_message(
            client, "tok-an", "conv-an-binh", "FILE", attachment["id"])
        with self.assertRaisesRegex(
            driver.MediaProductError, "did not serve the exact slice"
        ):
            driver.range_slices(
                client, "tok-binh",
                f"/api/v1/files/attachments/{attachment['id']}",
                fixture)

    def test_send_attachment_message_mirrors_the_app_request(self):
        store = AttachmentStore()
        client = FakeClient(store)
        fixture = driver.build_fixture("pdf")
        attachment = driver.upload_fixture(client, "tok-an", fixture)
        message = driver.send_attachment_message(
            client, "tok-an", "conv-an-binh", "FILE", attachment["id"])
        self.assertTrue(message["id"])
        sent = store.messages[0]
        self.assertEqual(sent["conversationId"], "conv-an-binh")
        self.assertEqual(sent["type"], "FILE")

    def test_soft_delete_is_best_effort_and_never_raises(self):
        store = AttachmentStore()
        client = FakeClient(store)
        fixture = driver.build_fixture("pdf")
        attachment = driver.upload_fixture(client, "tok-an", fixture)
        message = driver.send_attachment_message(
            client, "tok-an", "conv-an-binh", "FILE", attachment["id"])
        self.assertIsNone(
            driver.soft_delete_message(client, "tok-an", "conv-an-binh",
                                       message["id"]))
        self.assertIn(message["id"], store.deleted)

        class BrokenClient(FakeClient):
            def delete(self, path, token=None, params=None, headers=None):
                return Response(500, {}, b"boom")

        broken = BrokenClient(store)
        error = driver.soft_delete_message(
            broken, "tok-an", "conv-an-binh", message["id"])
        self.assertIn("HTTP 500", error)


if __name__ == "__main__":
    unittest.main()
