"""Stdlib-only offline barrier regressions. No backend, adb or device access."""

from __future__ import annotations

import concurrent.futures
import http.client
import json
import socket
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

try:
    from .response_barrier import Barrier
except ImportError:
    from response_barrier import Barrier


def request(origin, path, method="GET", token=None, body=None, headers=None, headers_received=None):
    target = urlsplit(origin)
    conn = http.client.HTTPConnection(target.hostname, target.port, timeout=4)
    request_headers = dict(headers or {})
    if token is not None:
        request_headers["Authorization"] = "Bearer " + token
    if isinstance(body, dict):
        body = json.dumps(body).encode()
        request_headers["Content-Type"] = "application/json"
    try:
        conn.request(method, path, body=body, headers=request_headers)
        response = conn.getresponse()
        if headers_received is not None:
            headers_received.set()
        return response.status, response.getheaders(), response.read()
    finally:
        conn.close()


def json_request(origin, path, body=None):
    status, _, data = request(origin, path, "GET" if body is None else "POST", body=body)
    return status, json.loads(data)


class _Upstream(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def handle(self):
        try:
            super().handle()
        except OSError:
            pass  # Timeout/shutdown tests intentionally disconnect this peer.

    def log_message(self, *_args):
        pass

    def _reply(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        with self.server.lock:
            self.server.seen.append((self.command, self.path, body, dict(self.headers)))
        self.server.arrived.set()
        if self.path == "/blocked":
            self.server.proceed.wait(4)
        if self.path == "/disconnect":
            self.close_connection = True
            return
        if self.path == "/chunked":
            self.send_response(200)
            self.send_header("Transfer-Encoding", "chunked")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(b"3\r\none\r\n3\r\ntwo\r\n0\r\n\r\n")
            self.close_connection = True
            return
        status = {"/error": 503, "/empty": 204, "/cached": 304}.get(self.path, 200)
        response_body = (b"upstream error" if status == 503 else
                         body if self.command in ("POST", "PATCH", "PUT") else self.path.encode())
        self.send_response(status)
        if status != 204:
            self.send_header("Content-Length", str(len(response_body) + (self.path == "/truncated")))
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Set-Cookie", "one=1")
        self.send_header("Set-Cookie", "two=2")
        self.send_header("Connection", "close, X-Hop")
        self.send_header("X-Hop", "remove-me")
        self.end_headers()
        if self.path == "/body-blocked":
            self.server.body_started.set()
            self.server.proceed.wait(4)
        if self.command != "HEAD" and status not in (204, 304):
            try:
                self.wfile.write(response_body)
            except OSError:
                pass
        self.close_connection = True

    do_GET = do_POST = do_PATCH = do_PUT = do_HEAD = _reply


class BarrierTests(unittest.TestCase):
    def setUp(self):
        class FakeServer(ThreadingHTTPServer):
            request_queue_size = 64
        self.upstream = FakeServer(("127.0.0.1", 0), _Upstream)
        self.upstream.daemon_threads = False
        self.upstream.seen = []
        self.upstream.lock = threading.Lock()
        self.upstream.arrived = threading.Event()
        self.upstream.proceed = threading.Event()
        self.upstream.body_started = threading.Event()
        self.thread = threading.Thread(target=self.upstream.serve_forever,
                                       kwargs={"poll_interval": 0.01})
        self.thread.start()
        self.origin = f"http://127.0.0.1:{self.upstream.server_port}"
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=16)

    def tearDown(self):
        self.upstream.proceed.set()
        self.pool.shutdown(wait=True)
        self.upstream.shutdown()
        self.upstream.server_close()
        self.thread.join(2)
        self.assertFalse(self.thread.is_alive())

    def async_request(self, barrier, path, **kwargs):
        return self.pool.submit(request, barrier.url, path, **kwargs)

    def test_hold_is_after_upstream_completion_and_release_is_fifo(self):
        with Barrier(self.origin) as barrier:
            barrier.hold(path=r"^/api/conversations$")
            first = self.async_request(barrier, "/api/conversations?cursor=1")
            held = barrier.wait_held(1)
            self.assertEqual("/api/conversations", held[0]["path"])
            self.assertGreaterEqual(held[0]["age"], 0)
            self.assertTrue(self.upstream.arrived.is_set())
            self.assertFalse(first.done())
            second = self.async_request(barrier, "/api/conversations?cursor=2")
            held = barrier.wait_held(2)
            self.assertFalse(second.done())
            self.assertEqual([held[0]["id"]], barrier.release(count=1))
            self.assertEqual(b"/api/conversations?cursor=1", first.result(2)[2])
            self.assertFalse(second.done())
            self.assertEqual([held[1]["id"]], barrier.release())
            self.assertEqual(200, second.result(2)[0])

    def test_account_b_and_other_method_pass_while_account_a_is_held(self):
        with Barrier(self.origin) as barrier:
            barrier.hold(path=r"^/api/conversations$", token_contains="fixture-A")
            account_a = self.async_request(barrier, "/api/conversations", token="fixture-A")
            barrier.wait_held(1)
            self.assertEqual(200, request(barrier.url, "/api/conversations", token="fixture-B")[0])
            self.assertEqual(b"committed", request(barrier.url, "/api/conversations", method="POST",
                                                  token="fixture-A", body=b"committed")[2])
            self.assertEqual(200, request(barrier.url, "/other", token="fixture-A")[0])
            self.assertFalse(account_a.done())
            barrier.release()
            self.assertEqual(200, account_a.result(2)[0])

    def test_committed_post_response_sends_no_headers_until_release(self):
        with Barrier(self.origin) as barrier:
            barrier.hold(path="^/commit$", method="POST")
            headers_received = threading.Event()
            pending = self.async_request(barrier, "/commit", method="POST", body=b"committed-A",
                                         headers_received=headers_received)
            barrier.wait_held(1)
            self.assertEqual(b"committed-A", self.upstream.seen[-1][2])
            self.assertFalse(headers_received.wait(0.03))
            barrier.release()
            self.assertEqual(b"committed-A", pending.result(2)[2])
            self.assertTrue(headers_received.is_set())

    def test_control_api_add_list_release_reset_and_bad_input(self):
        with Barrier(self.origin) as barrier:
            status, rule = json_request(barrier.control_url, "/rules", {
                "method": "GET", "path": "^/held$", "action": "hold",
                "token_contains": "fixture-A"})
            self.assertEqual(201, status)
            pending = self.async_request(barrier, "/held?private=value", token="fixture-A")
            held = barrier.wait_held(1)
            status, listing = json_request(barrier.control_url, "/held")
            self.assertEqual(200, status)
            self.assertEqual(rule["id"], listing["held"][0]["rule_id"])
            self.assertNotIn("fixture-A", json.dumps(listing))
            self.assertNotIn("private", json.dumps(listing))
            status, result = json_request(barrier.control_url, f"/release/{held[0]['id']}", {})
            self.assertEqual((200, [held[0]["id"]]), (status, result["released"]))
            self.assertEqual(200, pending.result(2)[0])
            pending = self.async_request(barrier, "/held", token="fixture-A")
            barrier.wait_held(1)
            self.assertEqual(200, json_request(barrier.control_url, "/release/all", {})[0])
            pending.result(2)
            pending = self.async_request(barrier, "/held", token="fixture-A")
            barrier.wait_held(1)
            self.assertEqual(200, json_request(barrier.control_url, "/reset", {})[0])
            self.assertEqual(200, pending.result(2)[0])
            self.assertEqual(200, request(barrier.url, "/held", token="fixture-A")[0])
            for body in ({"path": "["}, {"path": "/", "action": "unknown"},
                         {"path": "/", "delay_ms": -1}, {"path": "/", "surprise": True}):
                self.assertEqual(400, json_request(barrier.control_url, "/rules", body)[0])
            self.assertEqual(400, request(barrier.control_url, "/rules", "POST", body=b"{")[0])
            self.assertEqual(404, json_request(barrier.control_url, "/unknown")[0])
            self.assertEqual(400, json_request(barrier.control_url, "/release/no-id", {})[0])

    def test_auto_release_and_delay_are_bounded(self):
        with Barrier(self.origin, max_hold_time=0.18) as barrier:
            barrier.hold(path="/auto")
            pending = self.async_request(barrier, "/auto")
            barrier.wait_held(1)
            with self.assertLogs("response_barrier", level="WARNING") as logs:
                self.assertEqual(200, pending.result(2)[0])
            self.assertIn("auto-released", " ".join(logs.output))
            self.assertEqual([], barrier.held())
            barrier.add_rule(path="/delay", action="delay_ms", delay_ms=80)
            start = time.monotonic()
            pending = self.async_request(barrier, "/delay")
            barrier.wait_held(1)
            self.assertFalse(pending.done())
            self.assertEqual(200, pending.result(2)[0])
            self.assertGreaterEqual(time.monotonic() - start, 0.07)
            barrier.add_rule(path="/capped", action="delay_ms", delay_ms=5000)
            with self.assertLogs("response_barrier", level="WARNING"):
                self.assertEqual(200, request(barrier.url, "/capped")[0])

    def test_chunked_and_no_body_responses(self):
        with Barrier(self.origin) as barrier:
            barrier.hold(path="/chunked")
            pending = self.async_request(barrier, "/chunked")
            barrier.wait_held(1)
            barrier.release()
            status, headers, body = pending.result(2)
            self.assertEqual((200, b"onetwo"), (status, body))
            self.assertIn(("Content-Length", "6"), headers)
            self.assertNotIn("transfer-encoding", {k.lower() for k, _ in headers})
            self.assertEqual((204, b""), request(barrier.url, "/empty")[::2])
            status, headers, body = request(barrier.url, "/cached")
            self.assertEqual((304, b""), (status, body))
            self.assertIn(("Content-Length", "7"), headers)

    def test_reset_invalidates_matches_still_waiting_for_upstream(self):
        with Barrier(self.origin) as barrier:
            barrier.hold(path="/blocked")
            pending = self.async_request(barrier, "/blocked")
            self.assertTrue(self.upstream.arrived.wait(2))
            self.assertEqual([], barrier.held())
            barrier.reset()
            self.upstream.proceed.set()
            self.assertEqual(200, pending.result(2)[0])
            self.assertEqual([], barrier.held())

    def test_concurrent_requests_and_release_all(self):
        with Barrier(self.origin, max_held=12) as barrier:
            barrier.hold(path="/concurrent/")
            futures = [self.async_request(barrier, f"/concurrent/{i}") for i in range(12)]
            held = barrier.wait_held(12)
            self.assertEqual(12, len({item["id"] for item in held}))
            self.assertFalse(any(future.done() for future in futures))
            self.assertEqual([item["id"] for item in held], barrier.release())
            self.assertEqual(12, sum(future.result(2)[0] == 200 for future in futures))

    def test_capacity_overflow_passes_with_explicit_warning(self):
        with Barrier(self.origin, max_held=1) as barrier:
            barrier.hold(path="/capacity")
            first = self.async_request(barrier, "/capacity/1")
            barrier.wait_held(1)
            with self.assertLogs("response_barrier", level="WARNING") as logs:
                self.assertEqual(200, request(barrier.url, "/capacity/2")[0])
            self.assertIn("capacity reached", " ".join(logs.output))
            self.assertEqual(1, len(barrier.held()))
            barrier.release()
            first.result(2)

    def test_connection_limit_rejects_excess_without_spawning_more_workers(self):
        with Barrier(self.origin, max_connections=1) as barrier:
            barrier.hold(path="/limited")
            pending = self.async_request(barrier, "/limited")
            barrier.wait_held(1)
            self.assertEqual(503, request(barrier.url, "/limited")[0])
            self.assertEqual(503, request(barrier.url, "/limited", "POST", body=b"x" * 65536)[0])
            self.assertEqual(1, len(barrier._servers[0]._sockets))
            # The independent control listener remains usable at proxy capacity.
            self.assertEqual(200, json_request(barrier.control_url, "/release/all", {})[0])
            self.assertEqual(200, pending.result(2)[0])

    def test_upstream_error_passthrough_and_transport_failure(self):
        with Barrier(self.origin) as barrier:
            barrier.hold(path="/error")
            pending = self.async_request(barrier, "/error")
            barrier.wait_held(1)
            barrier.release()
            status, headers, body = pending.result(2)
            self.assertEqual((503, b"upstream error"), (status, body))
            self.assertIn(("Content-Type", "application/octet-stream"), headers)
            self.assertEqual(502, request(barrier.url, "/disconnect")[0])
            self.assertEqual(502, request(barrier.url, "/truncated")[0])

    def test_upstream_read_timeout_is_bounded(self):
        with Barrier(self.origin, io_timeout=0.12) as barrier:
            self.assertEqual(502, request(barrier.url, "/blocked")[0])
            self.upstream.proceed.set()

    def test_body_headers_and_head_forwarding(self):
        with Barrier(self.origin) as barrier:
            status, headers, body = request(barrier.url, "/echo", "PATCH", body=b"\x00bytes\xff",
                                            headers={"Connection": "close, X-Hop", "X-Hop": "drop"})
            self.assertEqual((200, b"\x00bytes\xff"), (status, body))
            self.assertEqual(["one=1", "two=2"], [v for k, v in headers if k == "Set-Cookie"])
            self.assertNotIn("x-hop", {k.lower() for k, _ in headers})
            self.assertNotIn("X-Hop", self.upstream.seen[-1][3])
            status, headers, body = request(barrier.url, "/head", "HEAD")
            self.assertEqual((200, b""), (status, body))
            self.assertIn(("Content-Length", "5"), headers)

    def test_unsupported_transports_and_bodies_are_rejected(self):
        with Barrier(self.origin, max_body_bytes=8) as barrier:
            with self.assertLogs("response_barrier", level="WARNING"):
                self.assertEqual(501, request(barrier.url, "/ws", headers={
                    "Connection": "Upgrade", "Upgrade": "websocket"})[0])
                self.assertEqual(501, request(barrier.url, "example.invalid:443", "CONNECT")[0])
            self.assertEqual(400, request(barrier.url, "http://example.invalid/")[0])
            self.assertEqual(400, request(barrier.url, "/", "POST", body=b"too-long-body")[0])
            self.assertEqual(400, request(barrier.url, "/", "POST", body=b"0\r\n\r\n",
                                          headers={"Transfer-Encoding": "chunked"})[0])
            self.assertEqual([], self.upstream.seen)
            self.assertEqual(502, request(barrier.url, "/larger-than-limit")[0])

    def test_shutdown_releases_held_workers_and_frees_both_ports(self):
        before = {thread.ident for thread in threading.enumerate()}
        barrier = Barrier(self.origin)
        with barrier:
            barrier.hold(path="/shutdown")
            pending = self.async_request(barrier, "/shutdown")
            barrier.wait_held(1)
            # An incomplete client request also must not leave a worker behind.
            idle = socket.create_connection(("127.0.0.1", barrier.port), timeout=2)
            idle.sendall(b"GET / HTTP/1.1\r\n")
        idle.close()
        with self.assertRaises((OSError, http.client.HTTPException)):
            pending.result(2)
        self.assertEqual([], barrier.held())
        for port in (barrier.port, barrier.control_port):
            with socket.socket() as sock:
                sock.bind(("127.0.0.1", port))
                sock.listen()
        # ThreadPoolExecutor's reusable worker is expected to remain until teardown.
        leaked = [thread.name for thread in threading.enumerate() if thread.ident not in before
                  and not thread.name.startswith("ThreadPoolExecutor")]
        self.assertEqual([], leaked)
        barrier.close()  # Idempotent.

    def test_shutdown_interrupts_upstream_body_read(self):
        with Barrier(self.origin, io_timeout=3) as barrier:
            pending = self.async_request(barrier, "/body-blocked")
            self.assertTrue(self.upstream.body_started.wait(2))
            start = time.monotonic()
        self.assertLess(time.monotonic() - start, 1.5)
        self.upstream.proceed.set()
        try:
            self.assertEqual(502, pending.result(2)[0])
        except (OSError, http.client.HTTPException):
            pass  # Closing the downstream socket is also a valid shutdown outcome.

    def test_partial_start_failure_closes_the_first_listener(self):
        with socket.socket() as occupied, socket.socket() as available:
            occupied.bind(("127.0.0.1", 0))
            occupied.listen()
            available.bind(("127.0.0.1", 0))
            free_port = available.getsockname()[1]
            available.close()
            with self.assertRaises(OSError):
                with Barrier(self.origin, port=free_port, control_port=occupied.getsockname()[1]):
                    self.fail("second listener should not bind")
            with socket.socket() as rebound:
                rebound.bind(("127.0.0.1", free_port))
                rebound.listen()

    def test_wait_timeout_and_configuration_validation(self):
        with Barrier(self.origin, max_rules=1) as barrier:
            with self.assertRaises(TimeoutError):
                barrier.wait_held(1, timeout=0.01)
            barrier.hold(path="/")
            with self.assertRaises(ValueError):
                barrier.hold(path="/another")
        for upstream in ("https://localhost", "http://user:pass@localhost", "http://localhost/api"):
            with self.assertRaises(ValueError):
                Barrier(upstream)
        for kwargs in ({"max_held": 0}, {"io_timeout": float("nan")}, {"max_hold_time": 0}):
            with self.assertRaises(ValueError):
                Barrier(self.origin, **kwargs)


if __name__ == "__main__":
    unittest.main(verbosity=2)
