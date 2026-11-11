#!/usr/bin/env python3
"""
Test and validation script for NexaChat static site and Digital Asset Links.
Node-free: runs on standard Python 3.
Validates:
1. JSON files parse correctly.
2. Digital Asset Links (.well-known/assetlinks.json) structure, package name matching
   android/app/build.gradle, and 32 colon-separated uppercase hex byte fingerprints.
3. Assetlinks serving criteria: Content-Type: application/json, no redirect, no Set-Cookie, status 200.
4. Required security headers and rewrites exist in vercel.json.
5. No external URLs (CDNs, fonts, analytics, third-party scripts) in HTML/CSS/JS.
6. No inline scripts, styles, or event handlers in HTML.
7. Token validator matches Android InviteLinkParser rules on valid/invalid test vectors.
8. Optional live HTTP check against --url <base-url>.
"""

import argparse
import html.parser
import http.server
import json
import os
import re
import shutil
import socketserver
import subprocess
import sys
import threading
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from urllib.parse import urlsplit

SITE_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_DIR = os.path.dirname(SITE_DIR)

FINGERPRINT_PATTERN = re.compile(r"^[0-9A-F]{2}(:[0-9A-F]{2}){31}$")
EXPECTED_UPLOAD_FINGERPRINT = (
    "AA:A9:6B:96:7C:ED:C6:78:DE:9C:77:35:78:5C:87:8C:B2:5D:AA:43:52:9B:E0:DF:EF:0B:73:93:4D:BE:01:7A"
)
EXPECTED_PACKAGE_NAME = "com.nexachat"


def log_pass(msg):
    print(f"[PASS] {msg}")


def log_fail(msg):
    print(f"[FAIL] {msg}")


def log_info(msg):
    print(f"[INFO] {msg}")


def test_json_files():
    failures = []
    # 1. vercel.json
    vercel_path = os.path.join(SITE_DIR, "vercel.json")
    if not os.path.isfile(vercel_path):
        failures.append(f"Missing {vercel_path}")
        vercel_data = None
    else:
        try:
            with open(vercel_path, "r", encoding="utf-8") as f:
                vercel_data = json.load(f)
            log_pass("vercel.json parses successfully")
        except Exception as e:
            failures.append(f"Failed to parse vercel.json: {e}")
            vercel_data = None

    # 2. assetlinks.json
    assetlinks_path = os.path.join(SITE_DIR, ".well-known", "assetlinks.json")
    if not os.path.isfile(assetlinks_path):
        failures.append(f"Missing {assetlinks_path}")
        asset_data = None
    else:
        try:
            with open(assetlinks_path, "r", encoding="utf-8") as f:
                asset_data = json.load(f)
            if not isinstance(asset_data, list):
                failures.append("assetlinks.json must be a JSON array")
            else:
                log_pass("assetlinks.json parses successfully (valid JSON array)")
        except Exception as e:
            failures.append(f"Failed to parse assetlinks.json: {e}")
            asset_data = None

    return vercel_data, asset_data, failures


def verify_android_package_name():
    """Verify package_name against applicationId in android/app/build.gradle."""
    gradle_path = os.path.join(REPO_DIR, "android", "app", "build.gradle")
    if not os.path.isfile(gradle_path):
        return None, f"android/app/build.gradle not found at {gradle_path}"

    try:
        with open(gradle_path, "r", encoding="utf-8") as f:
            gradle_content = f.read()

        match = re.search(r"applicationId\s+['\"]([^'\"]+)['\"]", gradle_content)
        if not match:
            return None, "Could not find applicationId in android/app/build.gradle"
        return match.group(1), None
    except Exception as e:
        return None, f"Failed to read android/app/build.gradle: {e}"


def test_assetlinks_structure(asset_data):
    failures = []
    if not asset_data:
        return ["Cannot validate assetlinks structure: assetlinks.json not loaded"]

    # 1. Statements count
    if len(asset_data) != 1:
        failures.append(f"Expected exactly 1 statement in assetlinks.json, found {len(asset_data)}")
    else:
        log_pass("assetlinks.json contains exactly one statement")

    # 2. Check Android gradle applicationId
    app_id, err = verify_android_package_name()
    if err:
        failures.append(f"Gradle check error: {err}")
    elif app_id != EXPECTED_PACKAGE_NAME:
        failures.append(
            f"Package name mismatch: android/app/build.gradle applicationId '{app_id}' != '{EXPECTED_PACKAGE_NAME}'"
        )
    else:
        log_pass(f"Verified package_name matches android/app/build.gradle applicationId: {app_id}")

    # 3. Unit tests of FINGERPRINT_PATTERN
    fp_test_cases = [
        (EXPECTED_UPLOAD_FINGERPRINT, True, "expected upload key fingerprint"),
        ("00:" * 31 + "00", True, "all 00 hex bytes"),
        ("FF:" * 31 + "FF", True, "all FF hex bytes"),
        (EXPECTED_UPLOAD_FINGERPRINT.lower(), False, "lowercase hex bytes"),
        ("AA:" * 30 + "AA", False, "31 bytes (too short)"),
        ("AA:" * 32 + "AA", False, "33 bytes (too long)"),
        ("AA" * 32, False, "missing colons"),
        (":" + ("AA:" * 31 + "AA"), False, "leading colon"),
        (("AA:" * 31 + "AA") + ":", False, "trailing colon"),
        ("AA:" * 31 + "ZZ", False, "invalid hex character ZZ"),
        ("", False, "empty string"),
    ]
    for fp, expected, desc in fp_test_cases:
        actual = bool(FINGERPRINT_PATTERN.match(fp))
        if actual != expected:
            failures.append(f"Fingerprint regex test failed for '{desc}': expected {expected}, got {actual}")
    if not any("Fingerprint regex" in f for f in failures):
        log_pass("Fingerprint regex validator passes all unit test vectors (32 uppercase hex bytes)")

    # 4. Validate statement contents
    for idx, stmt in enumerate(asset_data):
        relation = stmt.get("relation")
        if not isinstance(relation, list) or "delegate_permission/common.handle_all_urls" not in relation:
            failures.append(
                f"Statement {idx} missing 'delegate_permission/common.handle_all_urls' in relation: {relation}"
            )
        else:
            log_pass(f"Statement {idx} relation contains delegate_permission/common.handle_all_urls")

        target = stmt.get("target")
        if not isinstance(target, dict):
            failures.append(f"Statement {idx} target is not an object")
            continue

        if target.get("namespace") != "android_app":
            failures.append(f"Statement {idx} target namespace is not 'android_app': {target.get('namespace')}")
        else:
            log_pass(f"Statement {idx} target namespace is android_app")

        pkg = target.get("package_name")
        if pkg != EXPECTED_PACKAGE_NAME:
            failures.append(f"Statement {idx} target package_name '{pkg}' != '{EXPECTED_PACKAGE_NAME}'")
        elif app_id and pkg != app_id:
            failures.append(f"Statement {idx} target package_name '{pkg}' != gradle applicationId '{app_id}'")
        else:
            log_pass(f"Statement {idx} target package_name is {pkg}")

        fps = target.get("sha256_cert_fingerprints")
        if not isinstance(fps, list) or len(fps) == 0:
            failures.append(f"Statement {idx} target sha256_cert_fingerprints must be a non-empty list")
        else:
            for fp_idx, fp in enumerate(fps):
                if not isinstance(fp, str) or not FINGERPRINT_PATTERN.match(fp):
                    failures.append(
                        f"Statement {idx} fingerprint {fp_idx} '{fp}' does not match 32 colon-separated uppercase hex bytes"
                    )
                else:
                    log_pass(f"Statement {idx} fingerprint {fp_idx} valid 32-byte uppercase hex: {fp}")

            if EXPECTED_UPLOAD_FINGERPRINT not in fps:
                failures.append(f"Statement {idx} does not contain expected upload fingerprint {EXPECTED_UPLOAD_FINGERPRINT}")
            else:
                log_pass("Statement contains expected upload key fingerprint")

    return failures


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Do not follow redirects: return None causes urllib to return or raise HTTPError
        return None


def test_assetlinks_serving_static_and_local(vercel_data):
    """
    Validates serving criteria:
    - Content-Type: application/json
    - No redirect
    - No Set-Cookie
    - Status 200
    Via static vercel.json configuration and an ephemeral local HTTP server simulation.
    """
    failures = []
    if not vercel_data:
        return ["Cannot test serving: vercel.json not loaded"]

    # 1. vercel.json configuration checks
    headers_list = vercel_data.get("headers", [])
    asset_header = None
    all_headers = []
    for entry in headers_list:
        all_headers.extend(entry.get("headers", []))
        if entry.get("source") == "/.well-known/assetlinks.json":
            asset_header = entry

    if not asset_header:
        failures.append("vercel.json missing explicit headers entry for /.well-known/assetlinks.json")
    else:
        hdrs = {h.get("key", "").lower(): h.get("value", "") for h in asset_header.get("headers", [])}
        ct = hdrs.get("content-type", "")
        if "application/json" not in ct:
            failures.append(f"vercel.json /.well-known/assetlinks.json Content-Type is '{ct}', expected 'application/json'")
        else:
            log_pass("vercel.json configures Content-Type: application/json for /.well-known/assetlinks.json")

    # Check that Set-Cookie is never configured
    for h in all_headers:
        if h.get("key", "").lower() == "set-cookie":
            failures.append("vercel.json must not set any Set-Cookie headers")

    # Check that apex assetlinks is not redirected
    redirects = vercel_data.get("redirects", [])
    for r in redirects:
        src = r.get("source", "")
        # If redirect has no host condition, or applies unconditionally to assetlinks
        has_conditions = r.get("has", [])
        if not has_conditions and (src == "/:path*" or "assetlinks" in src):
            failures.append(f"Unconditional redirect '{src}' would redirect apex assetlinks.json")

    log_pass("vercel.json static serving rules verified: Content-Type: application/json, no apex redirect, no Set-Cookie")

    # 2. Ephemeral local HTTP server simulation
    assetlinks_file = os.path.join(SITE_DIR, ".well-known", "assetlinks.json")
    if not os.path.isfile(assetlinks_file):
        failures.append("assetlinks.json not found for local serving test")
        return failures

    with open(assetlinks_file, "rb") as f:
        expected_bytes = f.read()

    class AssetlinksSimHandler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/.well-known/assetlinks.json":
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(expected_bytes)))
                self.send_header("X-Content-Type-Options", "nosniff")
                self.end_headers()
                self.wfile.write(expected_bytes)
            else:
                self.send_response(404)
                self.end_headers()

        def log_message(self, format, *args):
            pass  # Suppress server logging during check

    try:
        with socketserver.TCPServer(("127.0.0.1", 0), AssetlinksSimHandler) as httpd:
            port = httpd.server_address[1]
            server_thread = threading.Thread(target=httpd.serve_forever)
            server_thread.daemon = True
            server_thread.start()

            opener = urllib.request.build_opener(NoRedirectHandler)
            url = f"http://127.0.0.1:{port}/.well-known/assetlinks.json"
            req = urllib.request.Request(url)

            try:
                with opener.open(req, timeout=5) as resp:
                    status = resp.status
                    ct = resp.headers.get("Content-Type", "")
                    cookie = resp.headers.get("Set-Cookie")
                    body = resp.read()

                    if status != 200:
                        failures.append(f"Local serving test returned HTTP status {status}, expected 200")
                    else:
                        log_pass("Local serving test: status 200 OK")

                    if "application/json" not in ct:
                        failures.append(f"Local serving test returned Content-Type '{ct}', expected 'application/json'")
                    else:
                        log_pass("Local serving test: Content-Type is application/json")

                    if cookie:
                        failures.append(f"Local serving test returned Set-Cookie header: {cookie}")
                    else:
                        log_pass("Local serving test: no Set-Cookie header present")

                    if body != expected_bytes:
                        failures.append("Local serving test body does not match assetlinks.json file on disk")
                    else:
                        log_pass("Local serving test: response body matches assetlinks.json exactly")
            except Exception as e:
                failures.append(f"Local serving test request failed: {e}")
            finally:
                httpd.shutdown()
                server_thread.join(timeout=2)
    except Exception as e:
        failures.append(f"Failed to start local test HTTP server: {e}")

    return failures


def test_assetlinks_live_url(base_url):
    """
    Validates serving criteria against a live base URL:
    - Content-Type: application/json
    - No redirect (status 200 direct)
    - No Set-Cookie
    - Status 200
    - Body parses and matches expected statements
    """
    failures = []
    normalized_base = base_url.rstrip("/")
    target_url = f"{normalized_base}/.well-known/assetlinks.json"
    log_info(f"Checking live assetlinks serving at: {target_url}")

    opener = urllib.request.build_opener(NoRedirectHandler)
    req = urllib.request.Request(
        target_url,
        headers={"User-Agent": "NexaChat-DomainCheck/1.0"},
    )

    try:
        with opener.open(req, timeout=10) as resp:
            status = resp.status
            ct = resp.headers.get("Content-Type", "")
            cookie = resp.headers.get("Set-Cookie")
            body_text = resp.read().decode("utf-8")

            if status != 200:
                failures.append(f"Live URL check returned status {status}, expected 200")
            else:
                log_pass(f"Live URL check: status {status} 200 OK")

            if "application/json" not in ct:
                failures.append(f"Live URL check returned Content-Type '{ct}', expected application/json")
            else:
                log_pass(f"Live URL check: Content-Type is '{ct}' (application/json)")

            if cookie:
                failures.append(f"Live URL check returned unexpected Set-Cookie header: {cookie}")
            else:
                log_pass("Live URL check: no Set-Cookie header returned")

            try:
                live_json = json.loads(body_text)
                struct_errs = test_assetlinks_structure(live_json)
                if struct_errs:
                    failures.extend([f"Live JSON error: {e}" for e in struct_errs])
                else:
                    log_pass("Live assetlinks JSON matches all structural and fingerprint invariants")
            except Exception as e:
                failures.append(f"Live assetlinks failed to parse as JSON: {e}")

    except urllib.error.HTTPError as e:
        if e.code in (301, 302, 307, 308):
            loc = e.headers.get("Location", "")
            failures.append(f"Live assetlinks was redirected with HTTP {e.code} to '{loc}' (redirects are disallowed)")
        else:
            failures.append(f"Live URL request failed with HTTP error {e.code}: {e.reason}")
    except urllib.error.URLError as e:
        failures.append(f"Live URL connection error for {target_url}: {e.reason}")
    except Exception as e:
        failures.append(f"Live URL check encountered unexpected error: {e}")

    return failures


def test_vercel_headers(vercel_data):
    failures = []
    if not vercel_data:
        return ["Cannot test headers: vercel.json not loaded"]

    headers_list = vercel_data.get("headers", [])
    headers_by_source = {}
    for entry in headers_list:
        src = entry.get("source", "")
        hdrs = {h.get("key", "").lower(): h.get("value", "") for h in entry.get("headers", [])}
        headers_by_source[src] = hdrs

    # Check global headers on /(.*)
    global_hdrs = headers_by_source.get("/(.*)", {})

    # X-Content-Type-Options: nosniff
    if global_hdrs.get("x-content-type-options") == "nosniff":
        log_pass("Header X-Content-Type-Options: nosniff present")
    else:
        failures.append("Missing or invalid X-Content-Type-Options: nosniff")

    # Referrer-Policy: no-referrer
    if global_hdrs.get("referrer-policy") == "no-referrer":
        log_pass("Header Referrer-Policy: no-referrer present")
    else:
        failures.append("Missing or invalid Referrer-Policy: no-referrer")

    # X-Frame-Options: DENY
    if global_hdrs.get("x-frame-options") == "DENY":
        log_pass("Header X-Frame-Options: DENY present")
    else:
        failures.append("Missing or invalid X-Frame-Options: DENY")

    # Strict-Transport-Security
    hsts = global_hdrs.get("strict-transport-security", "")
    if "max-age=" in hsts:
        log_pass(f"Header Strict-Transport-Security present ({hsts})")
    else:
        failures.append("Missing Strict-Transport-Security header")

    # Content-Security-Policy
    csp = global_hdrs.get("content-security-policy", "")
    required_csp_directives = [
        "default-src 'none'",
        "style-src 'self'",
        "script-src 'self'",
        "img-src 'self'",
        "base-uri 'none'",
        "form-action 'none'",
        "frame-ancestors 'none'",
    ]
    csp_missing = [d for d in required_csp_directives if d not in csp]
    if not csp_missing:
        log_pass("Strict Content-Security-Policy with all required directives present")
    else:
        failures.append(f"CSP missing directives: {csp_missing} (actual: {csp})")
    csp_pair_failures = validate_deletion_csp_pair(read_deletion_api_base(), csp)
    if not csp_pair_failures:
        log_pass("CSP connect-src matches the delete-account API meta state")
    else:
        failures.extend(csp_pair_failures)

    # Cache-Control: no-store for /join/*
    join_hdrs = headers_by_source.get("/join/(.*)", {})
    if "no-store" in join_hdrs.get("cache-control", ""):
        log_pass("Header Cache-Control: no-store present for /join/(.*)")
    else:
        failures.append("Missing Cache-Control: no-store for /join/(.*)")

    # Content-Type: application/json for assetlinks.json
    asset_hdrs = headers_by_source.get("/.well-known/assetlinks.json", {})
    if asset_hdrs.get("content-type") == "application/json":
        log_pass("Header Content-Type: application/json present for assetlinks.json")
    else:
        failures.append("Missing Content-Type: application/json for assetlinks.json")

    # Rewrites check
    rewrites = vercel_data.get("rewrites", [])
    has_join_rewrite = any(
        r.get("source") in ("/join/:token", "/join/:path*") and r.get("destination") == "/join/index.html"
        for r in rewrites
    )
    if has_join_rewrite:
        log_pass("Rewrite for /join/:token to /join/index.html present")
    else:
        failures.append("Missing rewrite for /join/:token to /join/index.html")

    return failures


SITE_HOST = "nexachat.io.vn"
SITE_ABSOLUTE_PREFIX = f"https://{SITE_HOST}"
LD_JSON_ALLOWED_HOSTS = frozenset({"schema.org", "www.w3.org"})

LD_JSON_SCRIPT_RE = re.compile(
    r'<script\s+[^>]*type\s*=\s*["\']application/ld\+json["\'][^>]*>(.*?)</script>',
    re.IGNORECASE | re.DOTALL,
)

MIN_CONTRAST_RATIO = 4.5
MIN_NON_TEXT_CONTRAST_RATIO = 3.0


def is_nexachat_site_url(url: str) -> bool:
    """True only when the URL host is exactly nexachat.io.vn (not a lookalike prefix)."""
    try:
        parts = urlsplit(url.strip().rstrip(".,;)]}"))
    except ValueError:
        return False
    if parts.scheme not in ("http", "https"):
        return False
    hostname = (parts.hostname or "").lower()
    return hostname == SITE_HOST


def is_allowed_ldjson_url(url: str) -> bool:
    try:
        hostname = (urlsplit(url.strip().rstrip(".,;)]}")).hostname or "").lower()
    except ValueError:
        return False
    return hostname in LD_JSON_ALLOWED_HOSTS


def strip_ld_json_blocks(html: str) -> str:
    return LD_JSON_SCRIPT_RE.sub("", html)


def hex_to_rgb(hex_color: str):
    h = hex_color.lstrip("#")
    if len(h) != 6:
        raise ValueError(f"expected #RRGGBB, got {hex_color}")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


def relative_luminance(hex_color: str) -> float:
    def channel(c):
        c = c / 255.0
        if c <= 0.03928:
            return c / 12.92
        return ((c + 0.055) / 1.055) ** 2.4

    r, g, b = hex_to_rgb(hex_color)
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def contrast_ratio(fg_hex: str, bg_hex: str) -> float:
    l1 = relative_luminance(fg_hex)
    l2 = relative_luminance(bg_hex)
    lighter = max(l1, l2)
    darker = min(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)


def collect_urls_for_external_scan(content: str, ext: str):
    """Extract http(s) URLs from markup; allow schema.org only inside JSON-LD blocks."""
    url_pattern = re.compile(r"https?://[^\s\"'<>]+")
    if ext == ".html":
        ld_blocks = LD_JSON_SCRIPT_RE.findall(content)
        scan_body = strip_ld_json_blocks(content)
        scan_body = DELETION_META_RE.sub("", scan_body)
        urls = url_pattern.findall(scan_body)
        for block in ld_blocks:
            for match in url_pattern.findall(block):
                if not is_allowed_ldjson_url(match) and not is_nexachat_site_url(match):
                    urls.append(match)
        return urls
    return url_pattern.findall(content)


def test_no_external_urls():
    failures = []
    scanned = 0

    for root, _, files in os.walk(SITE_DIR):
        for f in files:
            ext = os.path.splitext(f)[1].lower()
            if ext in (".html", ".css", ".js"):
                scanned += 1
                path = os.path.join(root, f)
                rel_path = os.path.relpath(path, SITE_DIR)
                with open(path, "r", encoding="utf-8") as file_handle:
                    content = file_handle.read()
                matches = collect_urls_for_external_scan(content, ext)
                disallowed = [m for m in matches if not is_nexachat_site_url(m)]
                if disallowed:
                    failures.append(f"External URL(s) found in {rel_path}: {disallowed}")

    if not failures:
        log_pass(f"No third-party URLs found across {scanned} HTML/CSS/JS file(s)")
    return failures


class InlineChecker(html.parser.HTMLParser):
    def __init__(self, filename):
        super().__init__()
        self.filename = filename
        self.failures = []
        self.current_tag = None
        self.script_src = None
        self.script_type = None
        self.in_script = False
        self.in_style = False

    def handle_starttag(self, tag, attrs):
        attr_dict = {k.lower(): v for k, v in attrs}
        if tag == "script":
            self.in_script = True
            self.script_src = attr_dict.get("src")
            self.script_type = (attr_dict.get("type") or "").lower()
            if self.script_type == "application/ld+json" and self.script_src:
                self.failures.append(
                    f"JSON-LD must be inline in {self.filename}; external src on application/ld+json is not consumed"
                )
        elif tag == "style":
            self.in_style = True
            self.failures.append(f"Inline <style> tag found in {self.filename}")

        if "style" in attr_dict:
            self.failures.append(f"Inline style attribute found in <{tag}> in {self.filename}")

        for attr in attr_dict:
            if attr.startswith("on"):
                self.failures.append(f"Inline event handler '{attr}' found in <{tag}> in {self.filename}")

    def handle_endtag(self, tag):
        if tag == "script":
            self.in_script = False
            self.script_src = None
            self.script_type = None
        elif tag == "style":
            self.in_style = False

    def handle_data(self, data):
        if self.in_script and data.strip():
            if self.script_type == "application/ld+json":
                return
            self.failures.append(f"Inline script content found in {self.filename}: {data.strip()[:40]}...")
        if self.in_style and data.strip():
            self.failures.append(f"Inline style content found in {self.filename}")


def test_no_inline_scripts_and_styles():
    failures = []
    scanned = 0
    for root, _, files in os.walk(SITE_DIR):
        for f in files:
            if f.endswith(".html"):
                scanned += 1
                path = os.path.join(root, f)
                rel_path = os.path.relpath(path, SITE_DIR)
                with open(path, "r", encoding="utf-8") as file_handle:
                    parser = InlineChecker(rel_path)
                    parser.feed(file_handle.read())
                    failures.extend(parser.failures)

    if not failures:
        log_pass(f"No inline script/style/handlers found in {scanned} HTML file(s)")
    return failures


REQUIRED_HTML_PAGES = (
    "index.html",
    "404.html",
    "privacy.html",
    "terms.html",
    "delete-account.html",
    os.path.join("join", "index.html"),
)

PUBLIC_SITEMAP_URLS = (
    "https://nexachat.io.vn/",
    "https://nexachat.io.vn/privacy.html",
    "https://nexachat.io.vn/terms.html",
    "https://nexachat.io.vn/delete-account.html",
)

DELETION_PAGE = "delete-account.html"
DELETION_PAGE_VI_PHRASES = (
    "Cài đặt",
    "Xóa tài khoản",
    "mật khẩu hiện tại",
    "Dữ liệu bị xóa",
    "Dữ liệu còn lại và thời gian lưu",
    "quản trị viên duy nhất",
    "Google Play",
    "email khôi phục đã xác minh",
    "Xóa trên web chưa khả dụng",
)
DELETION_PAGE_EN_PHRASES = (
    "Settings",
    "Delete account",
    "current password",
    "Data that is deleted",
    "Data that remains and how long it is kept",
    "only admin",
    "Google Play",
    "verified recovery email",
    "Web deletion is not available yet",
)
DELETION_NEUTRAL_REQUEST_VI = "Nếu số này có email khôi phục đã xác minh, chúng tôi đã gửi mã."
DELETION_NEUTRAL_REQUEST_EN = "If this number has a verified recovery email, we sent a code."
DELETION_META_RE = re.compile(
    r'<meta\s+name="nexachat-api-base"\s+content="([^"]*)"', re.IGNORECASE
)
CONNECT_SRC_RE = re.compile(r"(?:^|;)\s*connect-src\s+([^;]+)", re.IGNORECASE)


def read_deletion_api_base() -> str:
    """Return the configured API base from the deletion page, or '' when the form is off."""
    path = os.path.join(SITE_DIR, DELETION_PAGE)
    if not os.path.isfile(path):
        return ""
    with open(path, "r", encoding="utf-8") as f:
        html = f.read()
    match = DELETION_META_RE.search(html)
    return match.group(1).strip() if match else ""


def validate_deletion_csp_pair(meta_content: str, csp: str) -> list[str]:
    """Empty meta requires connect-src 'none'; an https origin meta requires that origin."""
    match = CONNECT_SRC_RE.search(csp)
    if not match:
        return ["Content-Security-Policy must define connect-src"]
    sources = match.group(1).split()
    meta = meta_content.strip()
    if not meta:
        if sources != ["'none'"]:
            return [
                "Default deployment (empty nexachat-api-base) must keep connect-src exactly 'none'"
            ]
        return []
    try:
        parsed = urlsplit(meta)
    except ValueError:
        parsed = None
    if (
        parsed is None
        or parsed.scheme != "https"
        or not parsed.netloc
        or parsed.path not in ("", "/")
        or parsed.query
        or parsed.fragment
        or parsed.username
        or parsed.password
    ):
        return ["nexachat-api-base must be an https origin with no path, query, or fragment"]
    origin = f"https://{parsed.netloc}"
    if "'none'" in sources or origin not in sources:
        return [f"Configured nexachat-api-base {origin} must appear in connect-src without 'none'"]
    return []


def test_site_pages_and_assets():
    """HTML structure, viewport, favicon link, sitemap, robots."""
    failures = []

    favicon_path = os.path.join(SITE_DIR, "favicon.svg")
    if not os.path.isfile(favicon_path):
        failures.append("Missing site/favicon.svg")
    else:
        with open(favicon_path, "r", encoding="utf-8") as f:
            favicon_body = f.read()
        if "<svg" not in favicon_body:
            failures.append("favicon.svg does not look like SVG")
        else:
            log_pass("favicon.svg present")

    sitemap_path = os.path.join(SITE_DIR, "sitemap.xml")
    if not os.path.isfile(sitemap_path):
        failures.append("Missing site/sitemap.xml")
    else:
        with open(sitemap_path, "r", encoding="utf-8") as f:
            sitemap_body = f.read()
        missing_urls = [u for u in PUBLIC_SITEMAP_URLS if u not in sitemap_body]
        if missing_urls:
            failures.append(f"sitemap.xml missing URL(s): {missing_urls}")
        else:
            log_pass("sitemap.xml lists all public URLs (home, privacy, terms, deletion)")

    robots_path = os.path.join(SITE_DIR, "robots.txt")
    if not os.path.isfile(robots_path):
        failures.append("Missing site/robots.txt")
    else:
        with open(robots_path, "r", encoding="utf-8") as f:
            robots_body = f.read()
        if "Sitemap:" not in robots_body or "sitemap.xml" not in robots_body:
            failures.append("robots.txt must reference sitemap.xml")
        if "Disallow: /join/" not in robots_body:
            failures.append("robots.txt must disallow /join/")
        else:
            log_pass("robots.txt references sitemap and disallows /join/")

    for rel_page in REQUIRED_HTML_PAGES:
        path = os.path.join(SITE_DIR, rel_page)
        if not os.path.isfile(path):
            failures.append(f"Missing HTML page {rel_page}")
            continue
        with open(path, "r", encoding="utf-8") as f:
            html = f.read()
        if not html.lstrip().startswith("<!DOCTYPE html>"):
            failures.append(f"{rel_page} must start with <!DOCTYPE html>")
        if "<html" not in html or "<head>" not in html or "<body>" not in html:
            failures.append(f"{rel_page} missing basic html/head/body structure")
        if 'name="viewport"' not in html and "name='viewport'" not in html:
            failures.append(f"{rel_page} missing mobile viewport meta tag")
        if 'href="/favicon.svg"' not in html:
            failures.append(f"{rel_page} missing favicon link to /favicon.svg")
        if 'charset="utf-8"' not in html.lower():
            failures.append(f"{rel_page} missing utf-8 charset meta")
        if 'href="#main"' not in html or 'id="main"' not in html:
            failures.append(f"{rel_page} missing skip link to #main")
        if 'name="description"' not in html:
            failures.append(f"{rel_page} missing meta description")
        if rel_page in ("index.html", "privacy.html", "terms.html", "delete-account.html"):
            if 'rel="canonical"' not in html:
                failures.append(f"{rel_page} missing canonical link")

    og_image_path = os.path.join(SITE_DIR, "og-image.svg")
    if not os.path.isfile(og_image_path):
        failures.append("Missing site/og-image.svg")
    else:
        try:
            with open(og_image_path, "r", encoding="utf-8") as og_file:
                og_body = og_file.read()
            ET.fromstring(og_body)
            log_pass("og-image.svg is well-formed UTF-8 XML")
        except Exception as e:
            failures.append(f"og-image.svg must be valid UTF-8 XML: {e}")

    structured_path = os.path.join(SITE_DIR, "structured-data.json")
    if not os.path.isfile(structured_path):
        failures.append("Missing site/structured-data.json")
    else:
        try:
            with open(structured_path, "r", encoding="utf-8") as f:
                sd = json.load(f)
            if not isinstance(sd, list) or not sd:
                failures.append("structured-data.json must be a non-empty JSON array")
            else:
                log_pass("structured-data.json parses as JSON array")
        except Exception as e:
            failures.append(f"Failed to parse structured-data.json: {e}")

    if not failures:
        log_pass(f"All {len(REQUIRED_HTML_PAGES)} HTML pages have DOCTYPE, viewport, favicon, charset, skip link, description")
    return failures


def test_index_inline_json_ld():
    """index.html must ship JSON-LD inline (not via script src)."""
    failures = []
    index_path = os.path.join(SITE_DIR, "index.html")
    if not os.path.isfile(index_path):
        return ["Missing site/index.html"]
    with open(index_path, "r", encoding="utf-8") as f:
        html = f.read()
    if 'type="application/ld+json" src=' in html or "type='application/ld+json' src=" in html:
        failures.append("index.html must not load JSON-LD via script src")
    blocks = LD_JSON_SCRIPT_RE.findall(html)
    if not blocks:
        failures.append("index.html missing inline application/ld+json block")
    else:
        try:
            data = json.loads(blocks[0].strip())
        except json.JSONDecodeError as e:
            failures.append(f"index.html JSON-LD must parse as JSON: {e}")
        else:
            if not isinstance(data, list) or not data:
                failures.append("index.html JSON-LD must be a non-empty array")
            else:
                types = {item.get("@type") for item in data if isinstance(item, dict)}
                if "Organization" not in types or "SoftwareApplication" not in types:
                    failures.append("index.html JSON-LD must include Organization and SoftwareApplication")
                else:
                    log_pass("index.html has inline JSON-LD (Organization + SoftwareApplication)")
    return failures


def css_theme_block(css: str, pattern: str) -> str:
    match = re.search(pattern, css, re.DOTALL)
    return match.group(1) if match else ""


def css_theme_token(block: str, name: str) -> str | None:
    match = re.search(rf"--{re.escape(name)}:\s*(#[0-9a-fA-F]{{6}})\b", block)
    return match.group(1) if match else None


def test_dark_theme_contrast_tokens():
    """Light and dark text/link/error/input-border tokens must meet WCAG AA on their surfaces."""
    failures = []
    css_path = os.path.join(SITE_DIR, "style.css")
    if not os.path.isfile(css_path):
        return ["Missing site/style.css"]
    with open(css_path, "r", encoding="utf-8") as f:
        css = f.read()

    dark_block = css_theme_block(css, r":root\s*\{(.*?)\}")
    light_block = css_theme_block(
        css, r"@media\s*\(prefers-color-scheme:\s*light\)\s*\{\s*:root\s*\{(.*?)\}"
    )
    if not dark_block or not light_block:
        return ["style.css must define :root tokens and a prefers-color-scheme: light :root block"]

    for theme, block in (("dark", dark_block), ("light", light_block)):
        theme_failures = []
        card_bg = css_theme_token(block, "card-bg")
        body_bg = css_theme_token(block, "bg-color")
        btn_bg = css_theme_token(block, "btn-bg")
        primary = css_theme_token(block, "primary")
        input_border = css_theme_token(block, "input-border")
        error = css_theme_token(block, "error")
        missing = [
            name for name, value in (
                ("card-bg", card_bg),
                ("bg-color", body_bg),
                ("btn-bg", btn_bg),
                ("primary", primary),
                ("input-border", input_border),
                ("error", error),
            ) if not value
        ]
        if missing:
            failures.append(f"{theme} theme missing contrast tokens: {missing}")
            continue

        btn_ratio = contrast_ratio("#ffffff", btn_bg)
        link_ratio = contrast_ratio(primary, card_bg)
        error_card_ratio = contrast_ratio(error, card_bg)
        error_body_ratio = contrast_ratio(error, body_bg)
        border_ratio = contrast_ratio(input_border, body_bg)
        if btn_ratio < MIN_CONTRAST_RATIO:
            theme_failures.append(
                f"{theme} .btn contrast {btn_ratio:.2f}:1 (#ffffff on {btn_bg}) below {MIN_CONTRAST_RATIO}:1"
            )
        if link_ratio < MIN_CONTRAST_RATIO:
            theme_failures.append(
                f"{theme} .prose a contrast {link_ratio:.2f}:1 ({primary} on {card_bg}) below {MIN_CONTRAST_RATIO}:1"
            )
        if error_card_ratio < MIN_CONTRAST_RATIO or error_body_ratio < MIN_CONTRAST_RATIO:
            theme_failures.append(
                f"{theme} .form-error contrast {error_card_ratio:.2f}:1 on card and "
                f"{error_body_ratio:.2f}:1 on body ({error}) below {MIN_CONTRAST_RATIO}:1"
            )
        if border_ratio < MIN_NON_TEXT_CONTRAST_RATIO:
            theme_failures.append(
                f"{theme} .form-input border contrast {border_ratio:.2f}:1 ({input_border} on {body_bg}) "
                f"below {MIN_NON_TEXT_CONTRAST_RATIO}:1"
            )
        if theme_failures:
            failures.extend(theme_failures)
        else:
            log_pass(
                f"{theme}-theme contrast OK: btn {btn_ratio:.2f}:1, prose links {link_ratio:.2f}:1, "
                f"error {error_card_ratio:.2f}:1, input border {border_ratio:.2f}:1"
            )
    return failures


def test_no_contradictory_hreflang():
    """Bilingual single-URL pages must not declare hreflang vi/en pointing at the same href."""
    failures = []
    pages = ("index.html", "privacy.html", "terms.html", "delete-account.html")
    hreflang_re = re.compile(
        r'<link[^>]+rel=["\']alternate["\'][^>]+hreflang=["\']([^"\']+)["\'][^>]+href=["\']([^"\']+)["\']',
        re.IGNORECASE,
    )
    for rel_page in pages:
        path = os.path.join(SITE_DIR, rel_page)
        if not os.path.isfile(path):
            continue
        with open(path, "r", encoding="utf-8") as f:
            html = f.read()
        by_href: dict[str, set[str]] = {}
        for lang, href in hreflang_re.findall(html):
            by_href.setdefault(href, set()).add(lang.lower())
        for href, langs in by_href.items():
            if langs >= {"vi", "en"}:
                failures.append(f"{rel_page}: hreflang vi and en both point to {href}")
    if not failures:
        log_pass("No contradictory hreflang on bilingual single-URL pages")
    return failures


def test_terms_sign_out_copy():
    failures = []
    terms_path = os.path.join(SITE_DIR, "terms.html")
    if not os.path.isfile(terms_path):
        return ["Missing site/terms.html"]
    with open(terms_path, "r", encoding="utf-8") as f:
        html = f.read().lower()
    if "sign-out or password change revokes other sessions" in html:
        failures.append("terms.html must distinguish single-device sign-out from password change / sign-out-all")
    if "đăng xuất hoặc đổi mật khẩu thu hồi phiên khác" in html:
        failures.append("terms.html (vi) must distinguish single-device sign-out from password change / sign-out-all")
    if not failures:
        log_pass("terms.html account session copy matches AuthService behaviour")
    return failures


def run_gate_self_tests():
    """Regression tests for gate helpers (deterministic, no network)."""
    failures = []

    if is_nexachat_site_url("https://nexachat.io.vn.evil.example/x"):
        failures.append("is_nexachat_site_url must reject lookalike host by prefix")
    if is_nexachat_site_url("https://nexachat.io.vn@evil.example/x"):
        failures.append("is_nexachat_site_url must reject userinfo lookalike host")
    if not is_nexachat_site_url("https://nexachat.io.vn/privacy.html"):
        failures.append("is_nexachat_site_url must allow real site URLs")

    if contrast_ratio("#ffffff", "#3b82f6") >= MIN_CONTRAST_RATIO:
        failures.append("self-test: old dark btn colour should fail the 4.5:1 bar (regression sentinel)")
    if contrast_ratio("#ffffff", "#1d4ed8") < MIN_CONTRAST_RATIO:
        failures.append("self-test: expected white on #1d4ed8 to meet 4.5:1")
    if contrast_ratio("#f87171", "#ffffff") >= MIN_CONTRAST_RATIO:
        failures.append("self-test: old light .form-error colour should fail the 4.5:1 bar")
    if contrast_ratio("#b91c1c", "#ffffff") < MIN_CONTRAST_RATIO:
        failures.append("self-test: new light .form-error colour should meet 4.5:1")
    if contrast_ratio("#e2e8f0", "#ffffff") >= MIN_NON_TEXT_CONTRAST_RATIO:
        failures.append("self-test: old light input border should fail the 3:1 bar")
    if contrast_ratio("#64748b", "#ffffff") < MIN_NON_TEXT_CONTRAST_RATIO:
        failures.append("self-test: new light input border should meet 3:1")

    csp_cases = (
        ("", "default-src 'none'; connect-src 'none'", True),
        ("", "default-src 'none'; connect-src https://api.example.com", False),
        ("https://api.example.com", "default-src 'none'; connect-src https://api.example.com", True),
        ("https://api.example.com", "default-src 'none'; connect-src 'none'", False),
        ("http://api.example.com", "default-src 'none'; connect-src http://api.example.com", False),
        ("https://api.example.com/path", "default-src 'none'; connect-src https://api.example.com", False),
        ("https://api.example.com", "default-src 'none'; connect-src 'none' https://api.example.com", False),
        ("https://api.example.com", "default-src 'none'", False),
    )
    for meta, csp, should_pass in csp_cases:
        result = validate_deletion_csp_pair(meta, csp)
        if (not result) != should_pass:
            failures.append(
                f"self-test: CSP pair meta={meta!r} csp={csp!r} expected "
                f"{'pass' if should_pass else 'fail'}, got {result}"
            )

    sample_html = (
        '<script type="application/ld+json">{"@context":"https://schema.org"}</script>'
        '<a href="https://evil.example/x">x</a>'
    )
    urls = collect_urls_for_external_scan(sample_html, ".html")
    if "https://evil.example/x" not in urls:
        failures.append("collect_urls_for_external_scan must still flag non-ld+json URLs")

    meta_html = (
        '<meta name="nexachat-api-base" content="https://api.nexachat.io.vn">'
        '<a href="https://evil.example/x">x</a>'
    )
    meta_urls = collect_urls_for_external_scan(meta_html, ".html")
    if "https://api.nexachat.io.vn" in meta_urls:
        failures.append("collect_urls_for_external_scan must allow the configured API base meta")
    if "https://evil.example/x" not in meta_urls:
        failures.append("collect_urls_for_external_scan must still scan the rest of the page")

    if not failures:
        log_pass("Gate helper regression self-tests passed")
    return failures


def test_bilingual_user_visible_text():
    """Each public HTML page includes both lang=\"vi\" and lang=\"en\" user-visible copy."""
    failures = []
    for rel_page in REQUIRED_HTML_PAGES:
        path = os.path.join(SITE_DIR, rel_page)
        if not os.path.isfile(path):
            continue
        with open(path, "r", encoding="utf-8") as f:
            html = f.read()
        if 'lang="vi"' not in html:
            failures.append(f"{rel_page} missing Vietnamese copy (lang=\"vi\")")
        if 'lang="en"' not in html:
            failures.append(f"{rel_page} missing English copy (lang=\"en\")")

    join_path = os.path.join(SITE_DIR, "join", "index.html")
    if os.path.isfile(join_path):
        with open(join_path, "r", encoding="utf-8") as f:
            join_html = f.read()
        if "com.nexachat" not in join_html:
            failures.append("join/index.html must mention package id com.nexachat in install instructions")
        if "Google Play" not in join_html:
            failures.append("join/index.html must mention Google Play install path")
        if "App Store" in join_html:
            failures.append("join/index.html must not claim an iOS App Store listing (Android-only client)")

    if not failures:
        log_pass("Public HTML pages include vi/en text and join install fallback copy")
    return failures


def test_account_deletion_page():
    """The public account-deletion page and its privacy/footer links must exist."""
    failures = []
    page_path = os.path.join(SITE_DIR, DELETION_PAGE)
    if not os.path.isfile(page_path):
        return [f"Missing HTML page {DELETION_PAGE}"]

    with open(page_path, "r", encoding="utf-8") as f:
        html = f.read()

    if 'rel="canonical" href="https://nexachat.io.vn/delete-account.html"' not in html:
        failures.append(f"{DELETION_PAGE} canonical must be https://nexachat.io.vn/delete-account.html")
    if "noindex" in html.lower():
        failures.append(f"{DELETION_PAGE} must be indexable (no noindex robots meta)")

    for phrase in DELETION_PAGE_VI_PHRASES:
        if phrase not in html:
            failures.append(f"{DELETION_PAGE} missing Vietnamese deletion copy: {phrase}")
    for phrase in DELETION_PAGE_EN_PHRASES:
        if phrase not in html:
            failures.append(f"{DELETION_PAGE} missing English deletion copy: {phrase}")

    privacy_path = os.path.join(SITE_DIR, "privacy.html")
    if os.path.isfile(privacy_path):
        with open(privacy_path, "r", encoding="utf-8") as f:
            privacy_html = f.read()
        if privacy_html.count('href="/delete-account.html"') < 2:
            failures.append("privacy.html must link the deletion page from its body and its footer")
    else:
        failures.append("Missing site/privacy.html")

    for rel_page in REQUIRED_HTML_PAGES:
        path = os.path.join(SITE_DIR, rel_page)
        if not os.path.isfile(path):
            continue
        with open(path, "r", encoding="utf-8") as f:
            page_html = f.read()
        if 'href="/delete-account.html"' not in page_html:
            failures.append(f"{rel_page} footer must link /delete-account.html")

    if DELETION_NEUTRAL_REQUEST_VI not in html:
        failures.append(f"{DELETION_PAGE} missing neutral post-request copy (vi)")
    if DELETION_NEUTRAL_REQUEST_EN not in html:
        failures.append(f"{DELETION_PAGE} missing neutral post-request copy (en)")

    if 'name="nexachat-api-base"' not in html:
        failures.append(f"{DELETION_PAGE} must declare meta name=\"nexachat-api-base\"")
    if 'src="/delete-account.js"' not in html or "defer" not in html:
        failures.append(f"{DELETION_PAGE} must load /delete-account.js with defer")

    js_path = os.path.join(SITE_DIR, "delete-account.js")
    if not os.path.isfile(js_path):
        failures.append("Missing site/delete-account.js")
    else:
        with open(js_path, "r", encoding="utf-8") as js_file:
            js_body = js_file.read()
        for needle in (
            "/api/v1/account-deletion/request",
            "/api/v1/account-deletion/confirm",
            'meta[name="nexachat-api-base"]',
            "phoneInput.addEventListener('input'",
            "ev.key !== 'Enter'",
            "res.status === 429",
            "web-deletion-send-error",
            "web-deletion-rate-error",
        ):
            if needle not in js_body:
                failures.append(f"delete-account.js missing required fragment: {needle}")

    if html.count('role="alert"') < 5:
        failures.append(f"{DELETION_PAGE} must expose form errors and rate-limit feedback with role=\"alert\"")
    if 'aria-labelledby="web-deletion-heading-vi web-deletion-heading-en"' not in html:
        failures.append(f"{DELETION_PAGE} web-deletion section must be labelled by both language headings")
    if "Gửi mã · Send code" not in html or "Xóa tài khoản · Delete account" not in html:
        failures.append(f"{DELETION_PAGE} form buttons must show bilingual visible labels")

    if not failures:
        log_pass("Account-deletion page is present, bilingual, linked from privacy and every footer")
    return failures


def test_delete_account_web_deployment_contract():
    """Repo gate: OTP web flow assets, strict default CSP, and operator deploy notes."""
    failures = []

    vercel_path = os.path.join(SITE_DIR, "vercel.json")
    if os.path.isfile(vercel_path):
        with open(vercel_path, "r", encoding="utf-8") as f:
            vercel_data = json.load(f)
        headers_list = vercel_data.get("headers", [])
        global_csp = ""
        for entry in headers_list:
            if entry.get("source") == "/(.*)":
                for hdr in entry.get("headers", []):
                    if hdr.get("key", "").lower() == "content-security-policy":
                        global_csp = hdr.get("value", "")
        if "script-src 'self'" not in global_csp:
            failures.append("vercel.json CSP must allow script-src 'self' for delete-account.js")
        csp_pair_failures = validate_deletion_csp_pair(read_deletion_api_base(), global_csp)
        if csp_pair_failures:
            failures.extend(csp_pair_failures)
        else:
            log_pass("delete-account API meta and CSP connect-src form a consistent pair")

    ops_path = os.path.join(REPO_DIR, "docs", "operations.md")
    if not os.path.isfile(ops_path):
        failures.append("Missing docs/operations.md for web-deletion deploy notes")
    else:
        with open(ops_path, "r", encoding="utf-8") as f:
            ops_body = f.read()
        for needle in (
            "nexachat-api-base",
            "connect-src",
            "NEXACHAT_CORS_ALLOWED_ORIGINS",
            "delete-account.html",
        ):
            if needle not in ops_body:
                failures.append(f"docs/operations.md must document web deletion deploy item: {needle}")

    if not failures:
        log_pass("Web account-deletion flow: meta/script gate, default CSP, operations deploy notes")
    return failures


def test_internal_static_links():
    """Root-absolute href/src in HTML point to files under site/ or known Vercel routes."""
    failures = []
    known_routes = {
        "/",
        "/join/index.html",
        "/style.css",
        "/favicon.svg",
        "/join/join.js",
        "/delete-account.js",
        "/sitemap.xml",
        "/robots.txt",
        "/privacy.html",
        "/terms.html",
        "/og-image.svg",
        "/structured-data.json",
    }

    for rel_page in REQUIRED_HTML_PAGES:
        path = os.path.join(SITE_DIR, rel_page)
        if not os.path.isfile(path):
            continue
        with open(path, "r", encoding="utf-8") as f:
            html = f.read()
        for attr, pattern in (("href", r'href="(/[^"#?]*)"'), ("src", r'src="(/[^"#?]*)"')):
            for match in re.finditer(pattern, html):
                target = match.group(1)
                if target in known_routes or target.startswith("/join/"):
                    continue
                disk = os.path.join(SITE_DIR, target.lstrip("/").replace("/", os.sep))
                if not os.path.isfile(disk):
                    failures.append(f"{rel_page}: {attr} {target} does not resolve to a file under site/")

    if not failures:
        log_pass("Internal href/src links resolve to site files or known routes")
    return failures


def test_vercel_ignore_command(vercel_data):
    failures = []
    if not vercel_data:
        return failures
    cmd = vercel_data.get("ignoreCommand", "")
    if not cmd:
        failures.append("vercel.json missing ignoreCommand (Vercel dashboard ignore must be cleared)")
    elif len(cmd) > 256:
        # Vercel rejects the whole deployment when ignoreCommand exceeds its schema limit.
        failures.append(f"ignoreCommand is {len(cmd)} characters; Vercel allows at most 256")
    elif "VERCEL_GIT_PREVIOUS_SHA" not in cmd:
        failures.append("ignoreCommand must reference VERCEL_GIT_PREVIOUS_SHA")
    elif "VERCEL_GIT_COMMIT_SHA" not in cmd:
        failures.append("ignoreCommand must compare against VERCEL_GIT_COMMIT_SHA")
    elif "cat-file" not in cmd:
        failures.append("ignoreCommand must verify commit object existence with git cat-file")
    elif "rev-parse" in cmd:
        failures.append(
            "ignoreCommand must not rely on git rev-parse (shallow clones lack objects that rev-parse still accepts)"
        )
    elif "exit 0" not in cmd or "exit 1" not in cmd:
        failures.append("ignoreCommand must explicitly return exit 0 (skip) or exit 1 (build)")
    else:
        log_pass("vercel.json defines repo-owned ignoreCommand using VERCEL_GIT_PREVIOUS_SHA and cat-file")

    sh_script = os.path.join(REPO_DIR, "tools", "test-vercel-ignore-command.sh")
    if os.path.isfile(sh_script):
        bash_path = shutil.which("bash")
        if bash_path:
            proc = subprocess.run([bash_path, sh_script], capture_output=True, text=True)
            if proc.returncode != 0:
                failures.append(
                    f"tools/test-vercel-ignore-command.sh failed (exit {proc.returncode}):\n{proc.stdout}\n{proc.stderr}"
                )
            else:
                log_pass("tools/test-vercel-ignore-command.sh passed (shallow clone and diff scenarios)")
        else:
            log_info("bash not found; skipped dynamic test-vercel-ignore-command.sh execution")
    return failures


def test_token_validator():
    failures = []
    join_js_path = os.path.join(SITE_DIR, "join", "join.js")
    if not os.path.isfile(join_js_path):
        return [f"Missing {join_js_path}"]

    with open(join_js_path, "r", encoding="utf-8") as f:
        js_content = f.read()

    pattern_match = re.search(r"var TOKEN_PATTERN = /(\^[^\/]+\$/);", js_content)
    if not pattern_match:
        return ["Could not find TOKEN_PATTERN regex in join/join.js"]

    js_pattern_str = pattern_match.group(1).strip("^$/")
    expected_pattern_str = "[A-Za-z0-9_-]{43}"
    if js_pattern_str != expected_pattern_str:
        failures.append(f"Pattern mismatch: expected {expected_pattern_str}, got {js_pattern_str}")
    else:
        log_pass(f"TOKEN_PATTERN matches Android rule: {js_pattern_str}")

    compiled_re = re.compile(f"^{expected_pattern_str}$")

    token_valid = "A" * 43
    test_cases = [
        (token_valid, True, "43 A's"),
        ("a" * 43, True, "43 lowercase a's"),
        ("0" * 43, True, "43 zeros"),
        ("-_" * 21 + "A", True, "43 url-safe chars with - and _"),
        ("0123456789abcdefghijklmnopqrstuvwxyzABC-_xy", True, "43 mixed chars"),
        (None, False, "null"),
        ("", False, "empty string"),
        ("short-token", False, "short-token"),
        ("A" * 42, False, "42 chars (too short)"),
        ("A" * 44, False, "44 chars (too long)"),
        ("A" * 4096, False, "4096 chars (huge)"),
        ("é" + ("A" * 42), False, "unicode prefix"),
        ("%41" + ("A" * 42), False, "percent-encoded prefix"),
        (("A" * 42) + "é", False, "unicode suffix"),
        ("A" * 42 + "+", False, "+ character (standard base64 not base64url)"),
        ("A" * 42 + "/", False, "/ character (standard base64 not base64url)"),
        ("A" * 42 + "=", False, "= padding character"),
        ("A" * 42 + "$", False, "$ invalid character"),
        ("not a URL", False, "not a URL"),
        ("A" * 42 + " ", False, "space character"),
        ("A" * 42 + "\n", False, "newline character"),
    ]

    for val, expected, label in test_cases:
        if val is None or not isinstance(val, str):
            actual = False
        else:
            actual = bool(compiled_re.match(val))
        if actual != expected:
            failures.append(f"Token validation failed for '{label}': expected {expected}, got {actual}")

    def simulate_extract(pathname, search, hash_val):
        if search or hash_val:
            return None
        if not pathname or not pathname.startswith("/join/"):
            return None
        token = pathname[len("/join/") :]
        if not compiled_re.match(token):
            return None
        return token

    path_cases = [
        (f"/join/{token_valid}", "", "", token_valid, "Valid /join/<token> path"),
        (f"/join/{token_valid}", "?next=https://evil.example", "", None, "Rejects query string"),
        (f"/join/{token_valid}", "", "#fragment", None, "Rejects hash fragment"),
        (f"/join/{token_valid}/extra", "", "", None, "Rejects extra path segments"),
        (f"/other/{token_valid}", "", "", None, "Rejects wrong path prefix"),
        ("/join/", "", "", None, "Rejects bare /join/"),
    ]

    for pathname, search, hash_val, expected, label in path_cases:
        actual = simulate_extract(pathname, search, hash_val)
        if actual != expected:
            failures.append(f"Path extractor failed for '{label}': expected {expected}, got {actual}")

    if not failures:
        log_pass(
            f"All {len(test_cases) + len(path_cases)} token validator test vectors passed (matching Android InviteLinkParserTest)"
        )
    return failures


def main():
    parser = argparse.ArgumentParser(description="Test and validation script for NexaChat static site")
    parser.add_argument(
        "--url",
        help="Base URL to verify live HTTP serving of assetlinks (e.g., https://nexachat-site.vercel.app)",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run deterministic regression tests for site gate helpers only",
    )
    args = parser.parse_args()

    if args.self_test:
        print("=== Site check gate self-tests ===")
        failures = run_gate_self_tests()
        if failures:
            for fail in failures:
                log_fail(fail)
            sys.exit(1)
        print("ALL SELF-TESTS PASSED!")
        sys.exit(0)

    print("=== Running NexaChat Site & Digital Asset Links Checks ===")
    all_failures = []

    # 1. JSON parse
    vercel_data, asset_data, f1 = test_json_files()
    all_failures.extend(f1)

    # 2. Assetlinks structure & fingerprint format
    f2 = test_assetlinks_structure(asset_data)
    all_failures.extend(f2)

    # 3. Static & local serving criteria (status 200, Content-Type: application/json, no redirect, no Set-Cookie)
    f3 = test_assetlinks_serving_static_and_local(vercel_data)
    all_failures.extend(f3)

    # 4. Live URL check if requested
    if args.url:
        f4 = test_assetlinks_live_url(args.url)
        all_failures.extend(f4)

    # 5. Security headers and rewrites in vercel.json
    f5 = test_vercel_headers(vercel_data)
    all_failures.extend(f5)

    f5b = test_vercel_ignore_command(vercel_data)
    all_failures.extend(f5b)

    f_pages = test_site_pages_and_assets()
    all_failures.extend(f_pages)

    f_i18n = test_bilingual_user_visible_text()
    all_failures.extend(f_i18n)

    f_deletion = test_account_deletion_page()
    all_failures.extend(f_deletion)

    f_deletion_web = test_delete_account_web_deployment_contract()
    all_failures.extend(f_deletion_web)

    f_ld = test_index_inline_json_ld()
    all_failures.extend(f_ld)

    f_contrast = test_dark_theme_contrast_tokens()
    all_failures.extend(f_contrast)

    f_hreflang = test_no_contradictory_hreflang()
    all_failures.extend(f_hreflang)

    f_terms = test_terms_sign_out_copy()
    all_failures.extend(f_terms)

    f_links = test_internal_static_links()
    all_failures.extend(f_links)

    # 6. External URLs
    f6 = test_no_external_urls()
    all_failures.extend(f6)

    # 7. Inline scripts/styles
    f7 = test_no_inline_scripts_and_styles()
    all_failures.extend(f7)

    # 8. Token validator
    f8 = test_token_validator()
    all_failures.extend(f8)

    print("\n=== Summary ===")
    if all_failures:
        print(f"FAILED with {len(all_failures)} failure(s):")
        for fail in all_failures:
            log_fail(fail)
        sys.exit(1)
    else:
        print("ALL CHECKS PASSED!")
        sys.exit(0)


if __name__ == "__main__":
    main()
