"""Stdlib-only offline barrier regressions. No backend, adb or device access."""

from __future__ import annotations

import base64
import concurrent.futures
import hashlib
import http.client
import json
import socket
import socketserver
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
                    "Connection": "Upgrade", "Upgrade": "h2c"})[0])
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


# --- WebSocket tunnel fixtures (stdlib RFC 6455) -------------------------

_WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def _ws_accept(key):
    return base64.b64encode(
        hashlib.sha1((key + _WS_GUID).encode("ascii")).digest()).decode("ascii")


def _ws_encode(opcode, payload, *, mask, fin=True):
    if isinstance(payload, str):
        payload = payload.encode("utf-8")
    header = bytearray(((0x80 if fin else 0) | opcode,))
    mask_bit = 0x80 if mask else 0
    size = len(payload)
    if size < 126:
        header.append(mask_bit | size)
    elif size <= 0xFFFF:
        header.append(mask_bit | 126)
        header += size.to_bytes(2, "big")
    else:
        header.append(mask_bit | 127)
        header += size.to_bytes(8, "big")
    if mask:
        header += b"\x11\x22\x33\x44"
        payload = bytes(b ^ b"\x11\x22\x33\x44"[i & 3] for i, b in enumerate(payload))
    return bytes(header) + payload


class _WsReader:
    """Buffered RFC 6455 reader shared by the test client and upstream."""

    def __init__(self, sock, *, mask_out, pending=b"", require_mask=False):
        self.sock = sock
        self.mask_out = mask_out
        self.require_mask = require_mask
        self.buf = bytearray(pending)
        self.pongs = []

    def _exact(self, count, deadline):
        while len(self.buf) < count:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("websocket read timed out")
            self.sock.settimeout(remaining)
            try:
                chunk = self.sock.recv(min(65536, count - len(self.buf) + 4096))
            except socket.timeout:
                continue
            if not chunk:
                raise EOFError("websocket peer closed")
            self.buf += chunk
        out = bytes(self.buf[:count])
        del self.buf[:count]
        return out

    def frame(self, deadline):
        first, second = self._exact(2, deadline)
        fin, opcode = bool(first & 0x80), first & 0x0F
        masked, size = bool(second & 0x80), second & 0x7F
        if self.require_mask and not masked:
            raise ConnectionError("client-role frame arrived unmasked")
        if size == 126:
            size = int.from_bytes(self._exact(2, deadline), "big")
        elif size == 127:
            size = int.from_bytes(self._exact(8, deadline), "big")
        key = self._exact(4, deadline) if masked else b""
        payload = self._exact(size, deadline) if size else b""
        if masked:
            payload = bytes(b ^ key[i & 3] for i, b in enumerate(payload))
        return fin, opcode, payload

    def send(self, opcode, payload, fin=True):
        self.sock.sendall(_ws_encode(opcode, payload, mask=self.mask_out, fin=fin))

    def message(self, deadline):
        """Next complete data message as (opcode, payload); None on close/EOF."""
        frag_op, parts = None, []
        while True:
            try:
                fin, opcode, payload = self.frame(deadline)
            except (EOFError, TimeoutError, OSError):
                return None
            if opcode == 0x9:
                self.send(0xA, payload)
                continue
            if opcode == 0xA:
                self.pongs.append(payload)
                continue
            if opcode == 0x8:
                return None
            if opcode == 0x0:
                if frag_op is None:
                    return None
                parts.append(payload)
                if fin:
                    return frag_op, b"".join(parts)
            elif opcode in (0x1, 0x2):
                frag_op, parts = opcode, [payload]
                if fin:
                    return opcode, payload
            else:
                return None


class _WsClient:
    """RFC 6455 client used to drive the barrier's downstream leg."""

    def __init__(self, origin, path="/ws", *, version="13", include_key=True,
                 extra_headers=None):
        target = urlsplit(origin)
        self.sock = socket.create_connection((target.hostname, target.port), timeout=4)
        self.sock.settimeout(4)
        lines = [
            f"GET {path} HTTP/1.1",
            f"Host: {target.hostname}:{target.port}",
            "Upgrade: websocket",
            "Connection: Upgrade",
        ]
        if include_key:
            key = base64.b64encode(b"0123456789abcdef").decode("ascii")
            lines.append(f"Sec-WebSocket-Key: {key}")
        if version:
            lines.append(f"Sec-WebSocket-Version: {version}")
        for name, value in (extra_headers or {}).items():
            lines.append(f"{name}: {value}")
        self.sock.sendall(("\r\n".join(lines) + "\r\n\r\n").encode("latin-1"))
        head = bytearray()
        while b"\r\n\r\n" not in head and len(head) <= 65536:
            chunk = self.sock.recv(4096)
            if not chunk:
                break
            head += chunk
        status_line = bytes(head).split(b"\r\n", 1)[0]
        try:
            self.status = int(status_line.split()[1])
        except (IndexError, ValueError):
            self.status = 0
        _, _, pending = bytes(head).partition(b"\r\n\r\n")
        self.reader = _WsReader(self.sock, mask_out=True, pending=pending)

    def stomp(self, command, headers=None, body=""):
        lines = [command] + [f"{key}:{value}" for key, value in (headers or {}).items()]
        self.reader.send(0x1, "\n".join(lines) + "\n\n" + body + "\x00")

    def connect_stomp(self, token):
        self.stomp("CONNECT", {"accept-version": "1.2", "heart-beat": "0,0",
                               "Authorization": "Bearer " + token})

    def recv_stomp(self, timeout):
        """Next STOMP frame as (command, headers, body); None on timeout/close."""
        deadline = time.monotonic() + timeout
        while True:
            got = self.reader.message(deadline)
            if got is None:
                return None
            opcode, payload = got
            if opcode != 0x1:
                continue
            text = payload.decode("utf-8", "replace").lstrip("\n")
            if not text:
                continue
            frame = text.split("\x00", 1)[0]
            head, _, body = frame.partition("\n\n")
            lines = head.split("\n")
            command = lines[0].strip() if lines else ""
            headers = {}
            for line in lines[1:]:
                key, sep, value = line.partition(":")
                if sep:
                    headers[key.strip()] = value.strip()
            return command, headers, body

    def recv_frame(self, timeout):
        """Next raw frame as (opcode, payload); None on timeout/close."""
        deadline = time.monotonic() + timeout
        try:
            _fin, opcode, payload = self.reader.frame(deadline)
        except (EOFError, TimeoutError, OSError):
            return None
        return opcode, payload

    def close(self):
        try:
            self.reader.send(0x8, b"")
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


class _WsUpstreamHandler(socketserver.BaseRequestHandler):
    """Minimal WS+STOMP upstream: CONNECT->CONNECTED, SEND->MESSAGE echo."""

    def handle(self):
        sock = self.request
        sock.settimeout(4)
        head = bytearray()
        try:
            while b"\r\n\r\n" not in head and len(head) <= 65536:
                chunk = sock.recv(4096)
                if not chunk:
                    return
                head += chunk
        except OSError:
            return
        header_block, _, leftover = bytes(head).partition(b"\r\n\r\n")
        headers = {}
        for line in header_block.split(b"\r\n")[1:]:
            name, sep, value = line.partition(b":")
            if sep:
                headers[name.decode("latin-1").strip().lower()] = \
                    value.decode("latin-1").strip()
        server = self.server
        with server.lock:
            server.connections += 1
            server.live.add(sock)
        try:
            if server.reject_upgrade or "sec-websocket-key" not in headers:
                sock.sendall(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n"
                             b"Connection: close\r\n\r\n")
                return
            accept = _ws_accept(headers["sec-websocket-key"])
            sock.sendall(("HTTP/1.1 101 Switching Protocols\r\n"
                          "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                          f"Sec-WebSocket-Accept: {accept}\r\n\r\n").encode("latin-1"))
            reader = _WsReader(sock, mask_out=False, pending=leftover,
                               require_mask=True)
            with server.lock:
                server.readers.append(reader)
            while True:
                got = reader.message(time.monotonic() + 15)
                if got is None:
                    return
                opcode, payload = got
                if opcode != 0x1:
                    continue
                text = payload.decode("utf-8", "replace")
                with server.lock:
                    server.texts.append(text)
                raw_frame = text.lstrip("\n")
                if not raw_frame or "\x00" not in raw_frame:
                    continue
                frame = raw_frame.split("\x00", 1)[0]
                head_part, _, body = frame.partition("\n\n")
                cmd_lines = head_part.split("\n")
                command = cmd_lines[0].strip().upper() if cmd_lines else ""
                destination = ""
                for line in cmd_lines[1:]:
                    key, sep, value = line.partition(":")
                    if sep and key.strip().lower() == "destination":
                        destination = value.strip()
                with server.lock:
                    server.events.append((command, destination, body))
                if command in ("CONNECT", "STOMP"):
                    reader.send(0x1, "CONNECTED\nversion:1.2\n\n\x00")
                elif command == "SEND":
                    if body == "@ping":
                        reader.send(0x9, b"upstream-ping")
                        continue
                    topic = destination
                    prefix, suffix = "/app/conversations/", "/messages"
                    if destination.startswith(prefix) and destination.endswith(suffix):
                        topic = "/topic/conversations." + \
                            destination[len(prefix):-len(suffix)]
                    reader.send(0x1, f"MESSAGE\ndestination:{topic}\n"
                                    f"subscription:sub-0\n\n{body}\x00")
                elif command == "DISCONNECT":
                    return
        finally:
            with server.lock:
                server.live.discard(sock)
                server.connections -= 1
            try:
                sock.close()
            except OSError:
                pass


class _WsUpstreamServer(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address):
        super().__init__(address, _WsUpstreamHandler)
        self.events = []
        self.texts = []
        self.readers = []
        self.live = set()
        self.lock = threading.Lock()
        self.reject_upgrade = False
        self.connections = 0


class WsTunnelTests(unittest.TestCase):
    def setUp(self):
        self.upstream = _WsUpstreamServer(("127.0.0.1", 0))
        self.upstream_thread = threading.Thread(
            target=self.upstream.serve_forever, kwargs={"poll_interval": 0.01})
        self.upstream_thread.start()
        self.origin = f"http://127.0.0.1:{self.upstream.server_address[1]}"
        self.clients = []

    def tearDown(self):
        for client in self.clients:
            client.close()
        with self.upstream.lock:
            live = list(self.upstream.live)
        for sock in live:
            try:
                sock.close()
            except OSError:
                pass
        self.upstream.shutdown()
        self.upstream.server_close()
        self.upstream_thread.join(2)
        self.assertFalse(self.upstream_thread.is_alive())

    def _client(self, origin, token=None, **kwargs):
        client = _WsClient(origin, **kwargs)
        self.clients.append(client)
        if client.status == 101 and token is not None:
            client.connect_stomp(token)
        return client

    def test_upgrade_is_accepted_and_stomp_frames_flow_both_ways(self):
        with Barrier(self.origin) as barrier:
            client = self._client(barrier.url, "token-a")
            self.assertEqual(101, client.status)
            command, _headers, _body = client.recv_stomp(3)
            self.assertEqual("CONNECTED", command)
            client.stomp("SEND", {"destination": "/app/conversations/9/messages"},
                         body='{"content":"hello"}')
            command, headers, body = client.recv_stomp(3)
            self.assertEqual("MESSAGE", command)
            self.assertEqual("/topic/conversations.9", headers["destination"])
            self.assertEqual('{"content":"hello"}', body)
            client.reader.send(0x1, "\n")  # STOMP heartbeat passes through
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                with self.upstream.lock:
                    if "\n" in self.upstream.texts:
                        break
                time.sleep(0.05)
            else:
                self.fail("STOMP heartbeat never reached the upstream")
            with self.upstream.lock:
                commands = [event[0] for event in self.upstream.events]
            self.assertEqual(["CONNECT", "SEND"], commands)

    def test_inbound_message_is_held_per_token_until_release(self):
        with Barrier(self.origin) as barrier:
            barrier.hold(path=r"^/topic/conversations\.", method="MESSAGE",
                         token_contains="token-a")
            client_a = self._client(barrier.url, "token-a")
            client_a.recv_stomp(3)
            client_b = self._client(barrier.url, "token-b")
            client_b.recv_stomp(3)
            client_a.stomp("SEND", {"destination": "/app/conversations/9/messages"},
                           body="a-frame")
            client_b.stomp("SEND", {"destination": "/app/conversations/9/messages"},
                           body="b-frame")
            command, _headers, body = client_b.recv_stomp(3)
            self.assertEqual("b-frame", body)
            held = barrier.wait_held(1, timeout=3)
            self.assertTrue(held[0]["path"].startswith("s2c MESSAGE"))
            self.assertIn("/topic/conversations.9", held[0]["path"])
            self.assertIsNone(client_a.recv_stomp(0.4))
            self.assertEqual([held[0]["id"]], barrier.release())
            command, _headers, body = client_a.recv_stomp(3)
            self.assertEqual("a-frame", body)
            self.assertEqual([], barrier.held())

    def test_client_to_server_message_is_held_before_upstream(self):
        with Barrier(self.origin) as barrier:
            barrier.hold(path=r"^/app/", method="SEND", token_contains="token-a")
            client = self._client(barrier.url, "token-a")
            client.recv_stomp(3)
            client.stomp("SEND", {"destination": "/app/conversations/9/messages"},
                         body="held-send")
            held = barrier.wait_held(1, timeout=3)
            self.assertIn("c2s SEND", held[0]["path"])
            deadline = time.monotonic() + 0.5
            seen_send = False
            while time.monotonic() < deadline:
                with self.upstream.lock:
                    seen_send = any(event[0] == "SEND" for event in self.upstream.events)
                if seen_send:
                    break
                time.sleep(0.05)
            self.assertFalse(seen_send)
            barrier.release()
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                with self.upstream.lock:
                    if any(event[0] == "SEND" for event in self.upstream.events):
                        break
                time.sleep(0.05)
            else:
                self.fail("upstream never received the held SEND")
            _command, _headers, body = client.recv_stomp(3)
            self.assertEqual("held-send", body)

    def test_held_frame_is_dropped_when_its_connection_closes(self):
        with Barrier(self.origin) as barrier:
            barrier.hold(path=r"^/topic/", method="MESSAGE", token_contains="token-a")
            client_a = self._client(barrier.url, "token-a")
            client_a.recv_stomp(3)
            client_b = self._client(barrier.url, "token-b")
            client_b.recv_stomp(3)
            client_a.stomp("SEND", {"destination": "/app/conversations/9/messages"},
                           body="a-frame")
            held = barrier.wait_held(1, timeout=3)
            client_a.close()
            time.sleep(0.3)
            self.assertIn(held[0]["id"], barrier.release())
            self.assertEqual([], barrier.held())
            client_b.stomp("SEND", {"destination": "/app/conversations/9/messages"},
                           body="b-frame")
            _command, _headers, body = client_b.recv_stomp(3)
            self.assertEqual("b-frame", body)

    def test_fragmented_client_message_is_reassembled_upstream(self):
        with Barrier(self.origin) as barrier:
            client = self._client(barrier.url, "token-a")
            client.recv_stomp(3)
            frame_text = ("SEND\ndestination:/app/conversations/9/messages\n\n"
                          "reassembled-payload\x00")
            client.reader.send(0x1, frame_text[:17], fin=False)
            client.reader.send(0x0, frame_text[17:], fin=True)
            _command, _headers, body = client.recv_stomp(3)
            self.assertEqual("reassembled-payload", body)
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                with self.upstream.lock:
                    hits = sum("reassembled-payload" in text
                               for text in self.upstream.texts)
                if hits:
                    break
                time.sleep(0.05)
            else:
                self.fail("upstream never received the reassembled message")
            self.assertEqual(1, hits)

    def test_ping_is_answered_and_close_propagates(self):
        with Barrier(self.origin) as barrier:
            client = self._client(barrier.url, "token-a")
            client.recv_stomp(3)
            client.reader.send(0x9, b"ping-1")
            got = client.recv_frame(3)
            self.assertEqual((0xA, b"ping-1"), got)
            client.stomp("SEND", {"destination": "/app/echo"}, body="@ping")
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                if any(reader.pongs for reader in self.upstream.readers):
                    break
                time.sleep(0.05)
            self.assertTrue(any(reader.pongs for reader in self.upstream.readers))
            self.assertIsNone(client.recv_stomp(0.3))
            client.reader.send(0x8, b"")
            deadline = time.monotonic() + 3
            while self.upstream.connections and time.monotonic() < deadline:
                time.sleep(0.05)
            self.assertEqual(0, self.upstream.connections)

    def test_malformed_websocket_handshake_is_rejected(self):
        with Barrier(self.origin) as barrier:
            no_key = self._client(barrier.url, include_key=False)
            self.assertEqual(400, no_key.status)
            bad_version = self._client(barrier.url, version="12")
            self.assertEqual(400, bad_version.status)

    def test_upstream_handshake_rejection_is_forwarded(self):
        self.upstream.reject_upgrade = True
        with Barrier(self.origin) as barrier:
            client = self._client(barrier.url, "token-a")
            self.assertEqual(403, client.status)


if __name__ == "__main__":
    unittest.main(verbosity=2)
