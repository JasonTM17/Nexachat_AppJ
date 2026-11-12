"""Offline unit tests for tools/e2e/mail_sink.py (fake Mailpit HTTP API)."""

from __future__ import annotations

import json
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

import mail_sink


def sample_reset_password_text(code: str = "482910") -> str:
    """Mirrors OtpMailContent.forCode(RESET_PASSWORD, code) text layout."""
    english_action = "reset your NexaChat password"
    vietnamese_action = "đặt lại mật khẩu NexaChat"
    return (
        f"NexaChat security code: {code}\n"
        f"Use this code to {english_action}. It expires in 10 minutes.\n\n"
        f"Mã bảo mật NexaChat: {code}\n"
        f"Dùng mã này để {vietnamese_action}. Mã hết hạn sau 10 phút.\n\n"
        "If you did not request a password reset, ignore this email.\n"
        "Nếu bạn không yêu cầu đặt lại mật khẩu, hãy bỏ qua email này."
    )


def sample_reset_password_html(code: str = "482910") -> str:
    english_action = "reset your NexaChat password"
    vietnamese_action = "đặt lại mật khẩu NexaChat"
    return (
        f"<p>NexaChat security code: <strong>{code}</strong></p>"
        f"<p>Use this code to {english_action}. It expires in 10 minutes.</p>"
        f"<hr><p>Mã bảo mật NexaChat: <strong>{code}</strong></p>"
        f"<p>Dùng mã này để {vietnamese_action}. Mã hết hạn sau 10 phút.</p>"
        "<p>If you did not request this, ignore this email."
        "<br>Nếu bạn không yêu cầu thao tác này, hãy bỏ qua email này.</p>"
    )


class FakeMailpitState:
    def __init__(self) -> None:
        self.messages: list[dict[str, Any]] = []
        self._seq = 0

    def add(
        self,
        to: str,
        *,
        subject: str = "NexaChat password reset code / Mã đặt lại mật khẩu NexaChat",
        text: str,
        html: str | None = None,
        created: str | None = None,
    ) -> dict[str, Any]:
        self._seq += 1
        message_id = f"msg-{self._seq:04d}"
        created_ts = created or time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime())
        summary = {
            "ID": message_id,
            "Subject": subject,
            "To": [{"Name": "", "Address": to}],
            "Created": created_ts,
        }
        full = {
            **summary,
            "Text": text,
            "HTML": html or "",
        }
        self.messages.append({"summary": summary, "full": full})
        return full


class FakeMailpitHandler(BaseHTTPRequestHandler):
    state: FakeMailpitState

    def log_message(self, format: str, *args: Any) -> None:
        return

    def do_GET(self) -> None:
        if self.path.startswith("/api/v1/messages"):
            payload = {
                "total": len(self.state.messages),
                "count": len(self.state.messages),
                "messages": [m["summary"] for m in self.state.messages],
            }
            self._json_response(200, payload)
            return
        prefix = "/api/v1/message/"
        if self.path.startswith(prefix):
            message_id = self.path[len(prefix) :].split("?", 1)[0]
            for item in self.state.messages:
                if item["summary"]["ID"] == message_id:
                    self._json_response(200, item["full"])
                    return
            self._json_response(404, {"error": "not found"})
            return
        self._json_response(404, {"error": "not found"})

    def do_DELETE(self) -> None:
        if self.path.startswith("/api/v1/messages"):
            self.state.messages.clear()
            self._json_response(200, {"ok": True})
            return
        self._json_response(404, {"error": "not found"})

    def _json_response(self, status: int, payload: Any) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class FakeMailpitServer:
    def __init__(self) -> None:
        self.state = FakeMailpitState()
        FakeMailpitHandler.state = self.state
        self._httpd = ThreadingHTTPServer(("127.0.0.1", 0), FakeMailpitHandler)
        self._httpd.daemon_threads = True
        self._thread = threading.Thread(target=self._httpd.serve_forever, daemon=True)
        self._thread.start()
        port = self._httpd.server_address[1]
        self.http_base = f"http://127.0.0.1:{port}"

    def close(self) -> None:
        self._httpd.shutdown()
        self._httpd.server_close()


class MailSinkApiTest(unittest.TestCase):
    def setUp(self) -> None:
        self._server = FakeMailpitServer()
        self._prev_override = mail_sink._override_http_base
        mail_sink._override_http_base = self._server.http_base

    def tearDown(self) -> None:
        mail_sink._override_http_base = self._prev_override
        self._server.close()

    def test_extract_otp_on_bilingual_sample(self) -> None:
        text = sample_reset_password_text("917364")
        html = sample_reset_password_html("917364")
        otp = mail_sink.extract_otp({"Text": text, "HTML": html})
        self.assertEqual(otp, "917364")

    def test_wait_timeout_on_empty_inbox(self) -> None:
        with self.assertRaises(mail_sink.MailSinkError) as ctx:
            mail_sink.wait_for_message("nobody@example.test", timeout=0.6)
        self.assertIn("timed out", str(ctx.exception).lower())

    def test_wait_returns_latest_of_two_messages(self) -> None:
        address = "user@example.test"
        self._server.state.add(
            address,
            text=sample_reset_password_text("111111"),
            created="2020-01-01T00:00:00.000Z",
        )
        self._server.state.add(
            address,
            text=sample_reset_password_text("222222"),
            created="2020-01-02T00:00:00.000Z",
        )
        message = mail_sink.wait_for_message(address, timeout=2.0)
        self.assertEqual(mail_sink.extract_otp(message), "222222")

    def test_clear_empties_inbox(self) -> None:
        self._server.state.add("user@example.test", text=sample_reset_password_text())
        mail_sink.clear()
        with self.assertRaises(mail_sink.MailSinkError):
            mail_sink.wait_for_message("user@example.test", timeout=0.5)

    def test_subject_filter(self) -> None:
        address = "filter@example.test"
        self._server.state.add(
            address,
            subject="Other",
            text="NexaChat security code: 333333\nMã bảo mật NexaChat: 333333",
        )
        self._server.state.add(
            address,
            subject="NexaChat account deletion code",
            text=sample_reset_password_text("444444"),
        )
        message = mail_sink.wait_for_message(
            address,
            subject_contains="account deletion",
            timeout=2.0,
        )
        self.assertEqual(mail_sink.extract_otp(message), "444444")


class MailSinkDockerInjectionTest(unittest.TestCase):
    def test_docker_runner_is_injectable(self) -> None:
        calls: list[list[str]] = []

        def fake_runner(argv: list[str], **kwargs: Any) -> Any:
            calls.append(argv)
            return type("R", (), {"returncode": 1, "stdout": "", "stderr": "no container"})()

        prev = mail_sink._docker_runner
        mail_sink._docker_runner = fake_runner
        try:
            with self.assertRaises(mail_sink.MailSinkError):
                mail_sink.start()
            self.assertTrue(any(cmd[:2] == ["docker", "run"] for cmd in calls))
        finally:
            mail_sink._docker_runner = prev


if __name__ == "__main__":
    unittest.main()
