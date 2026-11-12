#!/usr/bin/env python3
"""Offline-testable, HTTP-only reverse proxy that pauses *responses*.

Rules are sampled when a request arrives, but enter /held only after the full
upstream response has arrived. This is the account-switch barrier: /held proves
the server has finished, while the client has received no response headers yet.
Control binds to loopback, has no authentication, and must stay on loopback.
WebSocket upgrades and CONNECT are explicitly refused (501); see README.md.
"""

from __future__ import annotations

import argparse
import http.client
import io
import json
import logging
import math
import re
import socket
import socketserver
import threading
import time
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlsplit

LOG = logging.getLogger("response_barrier")
HOP_HEADERS = {"connection", "keep-alive", "proxy-authenticate",
               "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade"}


def _end_to_end(headers):
    blocked = HOP_HEADERS | {"host", "content-length"}
    for value in headers.get_all("Connection", []):
        blocked.update(part.strip().lower() for part in value.split(","))
    return [(key, value) for key, value in headers.items() if key.lower() not in blocked]


@dataclass
class _Rule:
    id: int
    method: str
    path: re.Pattern
    token_contains: str | None
    action: str
    delay_ms: float
    generation: int


@dataclass
class _Held:
    id: int
    path: str
    since: float
    deadline: float
    rule_id: int


class _Server(socketserver.ThreadingMixIn, HTTPServer):
    # Join every worker on close. Active sockets are interrupted before joining.
    daemon_threads = False
    allow_reuse_address = True
    request_queue_size = 128

    def __init__(self, address, handler, barrier):
        self.barrier = barrier
        self._slots = threading.BoundedSemaphore(barrier.max_connections)
        self._sockets = set()
        self._lock = threading.Lock()
        super().__init__(address, handler)

    def process_request(self, request, address):
        if not self._slots.acquire(blocking=False):
            request.settimeout(min(0.1, self.barrier.io_timeout))
            try:
                request.sendall(b"HTTP/1.1 503 Service Unavailable\r\n"
                                b"Content-Length: 0\r\nConnection: close\r\n\r\n")
                # A close with unread request bytes can send RST and discard
                # the 503 on Windows. Half-close output, then drain briefly so
                # a normal HTTP client can read the status and close cleanly.
                request.shutdown(socket.SHUT_WR)
                deadline = time.monotonic() + min(0.1, self.barrier.io_timeout)
                while time.monotonic() < deadline:
                    request.settimeout(max(0.001, deadline - time.monotonic()))
                    if not request.recv(65536):
                        break
            except OSError:
                pass
            self.shutdown_request(request)
            return
        with self._lock:
            self._sockets.add(request)
        try:
            super().process_request(request, address)
        except BaseException:
            self._finished(request)
            raise

    def _finished(self, request):
        with self._lock:
            self._sockets.discard(request)
        self._slots.release()

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self._finished(request)

    def interrupt_clients(self):
        with self._lock:
            for request in self._sockets:
                _interrupt(request)


def _interrupt(sock):
    if sock is not None:
        try:
            sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass


class _ResponseReader(io.RawIOBase):
    """Poll cancellation without poisoning socket.makefile after a timeout.

    On Windows, shutdown from another thread need not wake a timed recv/select.
    A raw recv timeout is retryable; socket.makefile's buffered timeout is not.
    """

    def __init__(self, sock, barrier):
        super().__init__()
        self.sock, self.barrier = sock, barrier

    def readable(self):
        return True

    def readinto(self, buffer):
        deadline = time.monotonic() + self.barrier.io_timeout
        while not self.barrier._stopped:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("upstream read timed out")
            self.sock.settimeout(min(0.1, remaining))
            try:
                return self.sock.recv_into(buffer)
            except socket.timeout:
                continue
        raise OSError("barrier stopped")


class _ResponseSocket:
    def __init__(self, sock, barrier):
        self.sock, self.barrier = sock, barrier

    def makefile(self, _mode):
        return io.BufferedReader(_ResponseReader(self.sock, self.barrier))


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def setup(self):
        super().setup()
        self.connection.settimeout(self.server.barrier.io_timeout)

    def handle(self):
        try:
            super().handle()
        except OSError:
            pass  # Shutdown/disconnect can interrupt a partially received request.

    def log_message(self, *_args):
        # Never emit request targets, Authorization values or raw upstream errors.
        pass

    def _send(self, status, body=b"", headers=(), representation_length=None):
        self.close_connection = True
        try:
            self.send_response_only(status)
            for key, value in headers:
                self.send_header(key, value)
            if status not in (204, 304) or (status == 304 and representation_length is not None):
                self.send_header("Content-Length", str(len(body) if representation_length is None
                                                       else representation_length))
            self.send_header("Connection", "close")
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)
        except OSError:
            pass  # Disconnected clients do not stop other held responses.

    def _json(self, status, value):
        self._send(status, json.dumps(value).encode(), [("Content-Type", "application/json")])

    def _body(self, limit):
        if self.headers.get("Transfer-Encoding"):
            raise ValueError("chunked request bodies are unsupported")
        lengths = self.headers.get_all("Content-Length", [])
        if len(lengths) > 1:
            raise ValueError("duplicate Content-Length")
        size = int(lengths[0]) if lengths else 0
        if size < 0 or size > limit:
            raise ValueError("body exceeds configured limit")
        data = self.rfile.read(size)
        if len(data) != size:
            raise ValueError("incomplete body")
        return data

    def handle_expect_100(self):
        self._json(417, {"error": "Expect is unsupported"})
        return False


class _ProxyHandler(_Handler):
    def _forward(self):
        barrier = self.server.barrier
        if self.headers.get("Upgrade") or self.command == "CONNECT":
            LOG.warning("REST-only barrier refused an upgrade or CONNECT request")
            self._json(501, {"error": "REST only: WebSocket upgrades and CONNECT are unsupported"})
            return
        try:
            target = urlsplit(self.path)
        except ValueError:
            self._json(400, {"error": "invalid request target"})
            return
        if not self.path.startswith("/") or target.netloc or target.scheme or target.fragment:
            self._json(400, {"error": "use an origin-form request target"})
            return
        rule = barrier._match(self.command, target.path, self.headers.get("Authorization", ""))
        try:
            body = self._body(barrier.max_body_bytes)
        except (ValueError, OSError):
            self._json(400, {"error": "invalid or unsupported request body"})
            return
        connection = http.client.HTTPConnection(barrier._host, barrier._port,
                                                timeout=barrier.io_timeout)
        upstream_socket = None
        response = None
        try:
            connection.connect()
            # http.client may close its socket reference after reading a
            # Connection: close header while the response file still owns it.
            # Keep an independent handle for the cancellation-aware response
            # reader, including when HTTPConnection closes its original handle.
            upstream_socket = connection.sock.dup()
            with barrier._condition:
                if barrier._stopped:
                    return
                barrier._upstream_sockets.add(upstream_socket)
            # Keep duplicate end-to-end headers (e.g. Set-Cookie on the return leg).
            connection.putrequest(self.command, self.path, skip_accept_encoding=True)
            for key, value in _end_to_end(self.headers):
                connection.putheader(key, value)
            connection.putheader("Content-Length", str(len(body)))
            connection.putheader("Connection", "close")
            connection.endheaders(body)
            connection.response_class = lambda _sock, **kwargs: http.client.HTTPResponse(
                _ResponseSocket(upstream_socket, barrier), **kwargs)
            response = connection.getresponse()
            status = response.status
            headers = _end_to_end(response.headers)
            representation_length = (response.getheader("Content-Length")
                                     if self.command == "HEAD" or status == 304 else None)
            # Bound buffering; a barrier is for REST fixtures, not large streams.
            data = response.read(barrier.max_body_bytes + 1)
            if len(data) > barrier.max_body_bytes:
                raise ValueError("upstream response too large")
            if (self.command != "HEAD" and status != 304
                    and response.getheader("Content-Length") is not None
                    and len(data) != int(response.getheader("Content-Length"))):
                raise ValueError("incomplete upstream response")
        except (OSError, http.client.HTTPException, ValueError):
            self._json(502, {"error": "upstream unavailable or response invalid"})
            return
        finally:
            if response is not None:
                response.close()
            connection.close()
            with barrier._condition:
                barrier._upstream_sockets.discard(upstream_socket)
            if upstream_socket is not None:
                upstream_socket.close()
        if rule is not None and not barrier._pause(rule, target.path):
            return
        if not barrier._stopped:
            self._send(status, data, headers, representation_length)

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = do_OPTIONS = do_CONNECT = _forward


class _ControlHandler(_Handler):
    def do_GET(self):
        if self.path == "/held":
            self._json(200, {"held": self.server.barrier.held()})
        else:
            self._json(404, {"error": "unknown control endpoint"})

    def do_POST(self):
        barrier = self.server.barrier
        try:
            raw = self._body(65536)
            value = json.loads(raw) if raw else {}
            if not isinstance(value, dict):
                raise ValueError("expected a JSON object")
            if self.path == "/rules":
                rule_id = barrier.add_rule(**value)
                self._json(201, {"id": rule_id})
            elif self.path.startswith("/release/"):
                key = self.path.removeprefix("/release/")
                released = barrier.release(None if key == "all" else int(key))
                self._json(200, {"released": released})
            elif self.path == "/reset":
                self._json(200, {"released": barrier.reset()})
            else:
                self._json(404, {"error": "unknown control endpoint"})
        except (ValueError, TypeError, re.error, OSError):
            self._json(400, {"error": "invalid control request"})


class Barrier:
    """Context-managed REST barrier. Release ids identify responses, not rules.

    release() releases all currently held responses in arrival order;
    release(id) releases one; release(count=n) releases the oldest n.
    Ordering means unblocking order, not TCP/client completion scheduling.
    reset() removes rules, releases responses and invalidates in-flight matches.
    """

    def __init__(self, upstream, *, host="127.0.0.1", port=0, control_port=0,
                 max_held=32, max_hold_time=30.0, io_timeout=5.0,
                 max_connections=64, max_body_bytes=32 * 1024 * 1024, max_rules=128):
        target = urlsplit(upstream)
        if (target.scheme != "http" or not target.hostname or target.username is not None
                or target.password is not None or target.path not in ("", "/")
                or target.query or target.fragment):
            raise ValueError("upstream must be an HTTP origin without credentials or a path")
        for number in (max_held, max_connections, max_body_bytes, max_rules):
            if isinstance(number, bool) or not isinstance(number, int) or number <= 0:
                raise ValueError("limits must be positive integers")
        for number in (max_hold_time, io_timeout):
            if not math.isfinite(number) or number <= 0:
                raise ValueError("timeouts must be finite and positive")
        self._host, self._port = target.hostname, target.port or 80
        self.host, self.port, self.control_port = host, port, control_port
        self.max_held, self.max_hold_time = max_held, max_hold_time
        self.io_timeout, self.max_connections = io_timeout, max_connections
        self.max_body_bytes, self.max_rules = max_body_bytes, max_rules
        self._condition = threading.Condition()
        self._rules = []
        self._held = {}
        self._upstream_sockets = set()
        self._generation = 0
        self._next_id = 1
        self._stopped = False
        self._servers = []
        self._threads = []

    @property
    def url(self):
        return f"http://{self.host}:{self.port}"

    @property
    def control_url(self):
        return f"http://127.0.0.1:{self.control_port}"

    def __enter__(self):
        if self._servers or self._stopped:
            raise RuntimeError("a Barrier instance can only be started once")
        try:
            for address, handler in (((self.host, self.port), _ProxyHandler),
                                     (("127.0.0.1", self.control_port), _ControlHandler)):
                server = _Server(address, handler, self)
                self._servers.append(server)
                thread = threading.Thread(target=server.serve_forever,
                                          kwargs={"poll_interval": 0.02},
                                          name="response-barrier-listener")
                thread.start()
                self._threads.append(thread)
            self.port, self.control_port = [server.server_port for server in self._servers]
        except BaseException:
            self.close()
            raise
        return self

    def __exit__(self, *_args):
        self.close()

    def close(self):
        with self._condition:
            self._stopped = True
            self._held.clear()
            self._rules.clear()
            for upstream_socket in self._upstream_sockets:
                _interrupt(upstream_socket)
            self._condition.notify_all()
        for server, thread in zip(self._servers, self._threads):
            if thread.is_alive():
                server.shutdown()
        for server in self._servers:
            server.interrupt_clients()
            server.server_close()
        for thread in self._threads:
            thread.join()

    def add_rule(self, *, path, method="GET", token_contains=None, action="hold", delay_ms=0):
        if (not isinstance(method, str) or not re.fullmatch(r"[A-Z]+", method)
                or not isinstance(path, str) or len(path) > 1024
                or (token_contains is not None and not isinstance(token_contains, str))
                or action not in ("hold", "delay_ms")
                or isinstance(delay_ms, bool) or not isinstance(delay_ms, (int, float))
                or not math.isfinite(delay_ms) or delay_ms < 0):
            raise ValueError("invalid rule")
        pattern = re.compile(path)
        with self._condition:
            if self._stopped or len(self._rules) >= self.max_rules:
                raise ValueError("barrier stopped or rule limit reached")
            rule_id = self._next_id
            self._next_id += 1
            self._rules.append(_Rule(rule_id, method, pattern, token_contains, action,
                                     delay_ms, self._generation))
            return rule_id

    def hold(self, *, path, method="GET", token_contains=None):
        return self.add_rule(path=path, method=method, token_contains=token_contains)

    def _match(self, method, path, token):
        with self._condition:
            return next((rule for rule in self._rules if rule.method == method
                         and rule.path.search(path)
                         and (rule.token_contains is None or rule.token_contains in token)), None)

    def _pause(self, rule, path):
        with self._condition:
            if self._stopped:
                return False
            if rule.generation != self._generation:
                return True
            if len(self._held) >= self.max_held:
                # Fail open with an explicit signal; do not silently claim a hold.
                LOG.warning("response barrier capacity reached; response bypassed")
                return True
            now = time.monotonic()
            duration = (self.max_hold_time if rule.action == "hold" else
                        min(rule.delay_ms / 1000, self.max_hold_time))
            held_id = self._next_id
            self._next_id += 1
            self._held[held_id] = _Held(held_id, path, now, now + duration, rule.id)
            self._condition.notify_all()
            while held_id in self._held and not self._stopped:
                remaining = self._held[held_id].deadline - time.monotonic()
                if remaining <= 0:
                    del self._held[held_id]
                    if rule.action == "hold" or rule.delay_ms / 1000 > self.max_hold_time:
                        LOG.warning("response barrier auto-released id=%d at max hold time", held_id)
                    self._condition.notify_all()
                    break
                self._condition.wait(remaining)
            return not self._stopped

    def held(self):
        with self._condition:
            now = time.monotonic()
            return [{"id": held.id, "path": held.path, "age": now - held.since,
                     "rule_id": held.rule_id} for held in self._held.values()]

    def wait_held(self, n=1, timeout=5.0):
        if n < 0 or timeout < 0 or not math.isfinite(timeout):
            raise ValueError("invalid wait")
        deadline = time.monotonic() + timeout
        with self._condition:
            while len(self._held) < n:
                remaining = deadline - time.monotonic()
                if self._stopped or remaining <= 0:
                    raise TimeoutError(f"expected {n} held responses; observed {len(self._held)}")
                self._condition.wait(remaining)
            return self.held()

    def release(self, response_id=None, *, count=None):
        if count is not None and (response_id is not None or not isinstance(count, int) or count < 0):
            raise ValueError("use response_id or a nonnegative count")
        with self._condition:
            ids = ([response_id] if response_id is not None else list(self._held)[:count])
            released = [held_id for held_id in ids if self._held.pop(held_id, None) is not None]
            self._condition.notify_all()
            return released

    def reset(self):
        with self._condition:
            self._generation += 1
            self._rules.clear()
            return self.release()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", default="http://127.0.0.1:8080")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18090)
    parser.add_argument("--control-port", type=int, default=18091)
    parser.add_argument("--max-held", type=int, default=32)
    parser.add_argument("--max-hold-time", type=float, default=30.0)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    try:
        with Barrier(**vars(args)) as barrier:
            LOG.info("REST response barrier port=%d control_port=%d", barrier.port, barrier.control_port)
            threading.Event().wait()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
