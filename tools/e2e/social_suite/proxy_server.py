"""Ephemeral loopback reverse proxy replicating site/vercel.json rewrites and CSP."""

from __future__ import annotations

import http.client
import mimetypes
import posixpath
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Dict, List, Optional, Tuple


CSP_GLOBAL = (
    "default-src 'none'; style-src 'self'; script-src 'self'; "
    "connect-src https://api.nexachat.io.vn; img-src 'self'; "
    "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
)

CSP_SOCIAL = (
    "default-src 'none'; style-src 'self'; script-src 'self'; "
    "connect-src 'self'; img-src 'self' blob:; media-src blob:; "
    "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
)

CSP_ADMIN = (
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
    "img-src 'self' data:; media-src blob:; font-src 'self'; "
    "connect-src 'self' https:; frame-ancestors 'none'; base-uri 'self'; "
    "form-action 'self'"
)

HOP_BY_HOP_HEADERS = frozenset(
    {
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailers",
        "transfer-encoding",
        "upgrade",
    }
)


def rewrite_api_path(path_with_query: str) -> Optional[str]:
    """Rewrites same-origin /api/* paths to Spring Boot target paths per site/vercel.json.

    Returns None if the path is not an allowed proxied API route.
    """
    parsed = urllib.parse.urlsplit(path_with_query)
    path = parsed.path
    query = f"?{parsed.query}" if parsed.query else ""

    exact_map = {
        "/api/social/v1/session/login": "/api/v1/auth/login",
        "/api/social/v1/session/refresh": "/api/v1/auth/refresh",
        "/api/social/v1/session/logout": "/api/v1/auth/logout",
        "/api/social/v1/reports": "/api/v1/reports",
        "/api/social/v1/config": "/api/social/v1/config",
        "/api/social/v1/posts": "/api/social/v1/posts",
        "/api/social/v1/images": "/api/social/v1/images",
        "/api/social/v1/videos": "/api/social/v1/videos",
    }
    if path in exact_map:
        return exact_map[path] + query

    # NOTE (site/vercel.json parity): on reconciled main (15b57759), site/vercel.json
    # does not yet define explicit rewrites for /api/social/v1/authors/:path* or
    # /api/v1/files/:path* (owned by M2). This proxy intentionally forwards both
    # /api/social/v1/authors/ and /api/v1/* (including /api/v1/files/*) to Spring Boot
    # so browser avatar/author tests execute cleanly while M2 updates site/vercel.json.
    social_subtrees = (
        "/api/social/v1/posts/",
        "/api/social/v1/comments/",
        "/api/social/v1/images/",
        "/api/social/v1/videos/",
        "/api/social/v1/authors/",
    )
    if any(path.startswith(prefix) for prefix in social_subtrees):
        return path + query

    # Admin SPA (built with VITE_API_URL="") and Android / avatar file API calls
    if path.startswith("/api/v1/"):
        return path + query

    return None


def headers_for_path(path: str) -> List[Tuple[str, str]]:
    """Returns exact site/vercel.json response headers for a given URL path."""
    headers: List[Tuple[str, str]] = [
        ("X-Content-Type-Options", "nosniff"),
        ("Referrer-Policy", "no-referrer"),
        ("X-Frame-Options", "DENY"),
        ("Strict-Transport-Security", "max-age=63072000; includeSubDomains; preload"),
    ]

    if path.startswith("/api/social/v1/") or path.startswith("/api/v1/files/"):
        headers.extend(
            [
                ("Cache-Control", "private, no-store"),
                ("CDN-Cache-Control", "no-store"),
                ("Vercel-CDN-Cache-Control", "no-store"),
            ]
        )

    if path == "/social" or path.startswith("/social/"):
        headers.append(("Content-Security-Policy", CSP_SOCIAL))
    elif path == "/admin" or path.startswith("/admin/"):
        headers.append(("Permissions-Policy", "camera=(), microphone=(), geolocation=()"))
        headers.append(("Content-Security-Policy", CSP_ADMIN))
    elif not path.startswith("/api/"):
        headers.append(("Content-Security-Policy", CSP_GLOBAL))

    if path == "/join" or path.startswith("/join/"):
        headers.append(("Cache-Control", "no-store"))

    return headers


def resolve_static_subpath(url_path: str) -> str:
    """Applies site/vercel.json SPA rewrites for /social, /join, and /admin."""
    if url_path in ("/social", "/social/"):
        return "/social/index.html"
    if url_path.startswith("/join/"):
        return "/join/index.html"
    if url_path == "/join":
        return "/join/index.html"
    if url_path == "/":
        return "/index.html"
    return url_path


def _guess_mime(path: Path) -> str:
    ext = path.suffix.lower()
    explicit = {
        ".html": "text/html; charset=utf-8",
        ".js": "application/javascript; charset=utf-8",
        ".mjs": "application/javascript; charset=utf-8",
        ".css": "text/css; charset=utf-8",
        ".json": "application/json; charset=utf-8",
        ".svg": "image/svg+xml",
        ".png": "image/png",
        ".jpg": "image/jpeg",
        ".jpeg": "image/jpeg",
        ".webp": "image/webp",
        ".woff2": "font/woff2",
        ".txt": "text/plain; charset=utf-8",
    }
    if ext in explicit:
        return explicit[ext]
    guessed, _ = mimetypes.guess_type(str(path))
    return guessed or "application/octet-stream"


def _safe_join(root: Path, rel_url_path: str) -> Optional[Path]:
    clean = posixpath.normpath("/" + rel_url_path).lstrip("/")
    if not clean:
        clean = "index.html"
    candidate = (root / clean).resolve()
    root_resolved = root.resolve()
    try:
        candidate.relative_to(root_resolved)
    except ValueError:
        return None
    return candidate


class _ProxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format: str, *args) -> None:  # noqa: A002
        return

    def do_GET(self) -> None:
        self._dispatch("GET")

    def do_HEAD(self) -> None:
        self._dispatch("HEAD")

    def do_POST(self) -> None:
        self._dispatch("POST")

    def do_PUT(self) -> None:
        self._dispatch("PUT")

    def do_PATCH(self) -> None:
        self._dispatch("PATCH")

    def do_DELETE(self) -> None:
        self._dispatch("DELETE")

    def do_OPTIONS(self) -> None:
        self._dispatch("OPTIONS")

    def _dispatch(self, method: str) -> None:
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path

        if path.startswith("/api/"):
            target_path = rewrite_api_path(self.path)
            if target_path is None:
                self._send_bytes(404, b'{"error":{"code":"NOT_FOUND"}}', "application/json", path)
                return
            self._proxy_to_spring(method, target_path, path)
            return

        if method not in ("GET", "HEAD"):
            self._send_bytes(405, b"Method Not Allowed", "text/plain; charset=utf-8", path)
            return

        if path == "/admin" or path.startswith("/admin/"):
            self._serve_admin(path, method)
            return

        self._serve_site(path, method)

    def _serve_admin(self, url_path: str, method: str) -> None:
        admin_dist: Optional[Path] = self.server.admin_dist_dir  # type: ignore[attr-defined]
        if admin_dist is None:
            self._send_bytes(404, b"Admin dist not configured", "text/plain; charset=utf-8", url_path)
            return
        sub = url_path[len("/admin"):]
        if not sub or sub == "/":
            target = admin_dist / "index.html"
        else:
            candidate = _safe_join(admin_dist, sub.lstrip("/"))
            if candidate is not None and candidate.is_file():
                target = candidate
            else:
                target = admin_dist / "index.html"
        if not target.is_file():
            self._send_bytes(404, b"Not Found", "text/plain; charset=utf-8", url_path)
            return
        data = target.read_bytes()
        self._send_bytes(200, data if method == "GET" else b"", _guess_mime(target), url_path, len(data))

    def _serve_site(self, url_path: str, method: str) -> None:
        site_dir: Path = self.server.site_dir  # type: ignore[attr-defined]
        direct = _safe_join(site_dir, url_path.lstrip("/"))
        if direct is None:
            self._send_bytes(403, b"Forbidden", "text/plain; charset=utf-8", url_path)
            return
        if direct.is_file():
            candidate = direct
        else:
            rewritten = resolve_static_subpath(url_path)
            candidate = _safe_join(site_dir, rewritten.lstrip("/"))
            if candidate is None:
                self._send_bytes(403, b"Forbidden", "text/plain; charset=utf-8", url_path)
                return
            if candidate.is_dir():
                candidate = candidate / "index.html"
        if not candidate.is_file():
            self._send_bytes(404, b"Not Found", "text/plain; charset=utf-8", url_path)
            return
        data = candidate.read_bytes()
        self._send_bytes(200, data if method == "GET" else b"", _guess_mime(candidate), url_path, len(data))

    def _send_bytes(
        self,
        status: int,
        body: bytes,
        content_type: str,
        url_path: str,
        content_length: Optional[int] = None,
    ) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body) if content_length is None else content_length))
        for key, value in headers_for_path(url_path):
            self.send_header(key, value)
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _proxy_to_spring(self, method: str, target_path: str, original_path: str) -> None:
        length = int(self.headers.get("Content-Length", "0") or "0")
        body = self.rfile.read(length) if length > 0 else None

        forward_headers: Dict[str, str] = {}
        for key, value in self.headers.items():
            low = key.lower()
            if low in HOP_BY_HOP_HEADERS or low in ("host", "content-length"):
                continue
            forward_headers[key] = value
        if body is not None:
            forward_headers["Content-Length"] = str(len(body))

        spring_host: str = self.server.spring_host  # type: ignore[attr-defined]
        spring_port: int = self.server.spring_port  # type: ignore[attr-defined]
        conn = http.client.HTTPConnection(spring_host, spring_port, timeout=30.0)
        try:
            conn.request(method, target_path, body=body, headers=forward_headers)
            resp = conn.getresponse()
            resp_body = resp.read()
            self.send_response(resp.status, resp.reason)

            seen_lower = set()
            for key, value in resp.getheaders():
                low = key.lower()
                if low in HOP_BY_HOP_HEADERS or low == "content-length":
                    continue
                self.send_header(key, value)
                seen_lower.add(low)

            for key, value in headers_for_path(original_path):
                low = key.lower()
                if low in seen_lower:
                    continue
                self.send_header(key, value)
                seen_lower.add(low)

            if resp.status != 204 and resp.status != 304:
                self.send_header("Content-Length", str(len(resp_body)))
            self.end_headers()
            if method != "HEAD" and resp_body:
                self.wfile.write(resp_body)
        finally:
            conn.close()


class _SocialProxyHTTPServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(
        self,
        server_address: Tuple[str, int],
        site_dir: Path,
        spring_host: str,
        spring_port: int,
        admin_dist_dir: Optional[Path] = None,
    ) -> None:
        self.site_dir = site_dir
        self.spring_host = spring_host
        self.spring_port = spring_port
        self.admin_dist_dir = admin_dist_dir
        super().__init__(server_address, _ProxyHandler)


class VercelProxyServer:
    """In-process ephemeral reverse proxy for site/ + admin/ + Spring Boot."""

    def __init__(
        self,
        site_dir: Path,
        spring_base_url: str,
        admin_dist_dir: Optional[Path] = None,
    ) -> None:
        parsed = urllib.parse.urlsplit(spring_base_url)
        if not parsed.hostname or not parsed.port:
            raise ValueError(f"Expected host:port in spring_base_url, got {spring_base_url!r}")
        self.site_dir = Path(site_dir)
        self.spring_host = parsed.hostname
        self.spring_port = parsed.port
        self.admin_dist_dir = Path(admin_dist_dir) if admin_dist_dir else None
        self._httpd: Optional[_SocialProxyHTTPServer] = None
        self._thread: Optional[threading.Thread] = None

    @property
    def base_url(self) -> str:
        if self._httpd is None:
            raise RuntimeError("Proxy server is not running")
        host, port = self._httpd.server_address[:2]
        return f"http://{host}:{port}"

    def start(self) -> str:
        if self._httpd is not None:
            return self.base_url
        self._httpd = _SocialProxyHTTPServer(
            ("127.0.0.1", 0),
            site_dir=self.site_dir,
            spring_host=self.spring_host,
            spring_port=self.spring_port,
            admin_dist_dir=self.admin_dist_dir,
        )
        self._thread = threading.Thread(
            target=self._httpd.serve_forever,
            name="social-e2e-proxy",
            daemon=True,
        )
        self._thread.start()
        return self.base_url

    def stop(self) -> None:
        if self._httpd is not None:
            self._httpd.shutdown()
            self._httpd.server_close()
            self._httpd = None
        if self._thread is not None:
            self._thread.join(timeout=5.0)
            self._thread = None

    def __enter__(self) -> "VercelProxyServer":
        self.start()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.stop()
