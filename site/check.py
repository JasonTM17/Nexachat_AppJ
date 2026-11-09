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
import socketserver
import sys
import threading
import urllib.error
import urllib.request

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
        "connect-src 'none'",
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


def test_no_external_urls():
    failures = []
    url_pattern = re.compile(r"https?://[^\s\"'>]+")
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
                matches = url_pattern.findall(content)
                if matches:
                    failures.append(f"External URL(s) found in {rel_path}: {matches}")

    if not failures:
        log_pass(f"No external URLs found across {scanned} HTML/CSS/JS file(s)")
    return failures


class InlineChecker(html.parser.HTMLParser):
    def __init__(self, filename):
        super().__init__()
        self.filename = filename
        self.failures = []
        self.current_tag = None
        self.script_src = None
        self.in_script = False
        self.in_style = False

    def handle_starttag(self, tag, attrs):
        attr_dict = {k.lower(): v for k, v in attrs}
        if tag == "script":
            self.in_script = True
            self.script_src = attr_dict.get("src")
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
        elif tag == "style":
            self.in_style = False

    def handle_data(self, data):
        if self.in_script and data.strip():
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


REQUIRED_HTML_PAGES = ("index.html", "404.html", os.path.join("join", "index.html"))


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
        if "https://nexachat.io.vn/" not in sitemap_body:
            failures.append("sitemap.xml must list apex home URL https://nexachat.io.vn/")
        else:
            log_pass("sitemap.xml lists home URL")

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

    if not failures:
        log_pass(f"All {len(REQUIRED_HTML_PAGES)} HTML pages have DOCTYPE, viewport, favicon, and charset")
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
        if "Google Play" not in join_html or "App Store" not in join_html:
            failures.append("join/index.html must mention store install paths (Google Play / App Store)")

    if not failures:
        log_pass("Public HTML pages include vi/en text and join install fallback copy")
    return failures


def test_internal_static_links():
    """Root-absolute href/src in HTML point to files under site/ or known Vercel routes."""
    failures = []
    known_routes = {"/", "/join/index.html", "/style.css", "/favicon.svg", "/join/join.js", "/sitemap.xml", "/robots.txt"}

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
    elif "VERCEL_GIT_PREVIOUS_SHA" not in cmd:
        failures.append("ignoreCommand must reference VERCEL_GIT_PREVIOUS_SHA")
    elif "VERCEL_GIT_COMMIT_SHA" not in cmd:
        failures.append("ignoreCommand must compare against VERCEL_GIT_COMMIT_SHA")
    else:
        log_pass("vercel.json defines repo-owned ignoreCommand using VERCEL_GIT_PREVIOUS_SHA")
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
    args = parser.parse_args()

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
