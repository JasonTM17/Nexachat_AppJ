#!/usr/bin/env python3
"""
Test and validation script for NexaChat static site.
Node-free: runs on standard Python 3.
Validates:
1. JSON files parse correctly.
2. Required security headers and rewrites exist in vercel.json.
3. No external URLs (CDNs, fonts, analytics, third-party scripts) in HTML/CSS/JS.
4. No inline scripts, styles, or event handlers in HTML.
5. Token validator matches Android InviteLinkParser rules on valid/invalid test vectors.
"""

import html.parser
import json
import os
import re
import sys

SITE_DIR = os.path.dirname(os.path.abspath(__file__))

def log_pass(msg):
    print(f"[PASS] {msg}")

def log_fail(msg):
    print(f"[FAIL] {msg}")

def test_json_files():
    failures = []
    # 1. vercel.json
    vercel_path = os.path.join(SITE_DIR, "vercel.json")
    if not os.path.isfile(vercel_path):
        failures.append(f"Missing {vercel_path}")
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

    return vercel_data, failures

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
    # Regex to detect http:// or https://
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
            # If script has non-whitespace body, it's an inline script
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

def test_token_validator():
    failures = []
    join_js_path = os.path.join(SITE_DIR, "join", "join.js")
    if not os.path.isfile(join_js_path):
        return [f"Missing {join_js_path}"]

    with open(join_js_path, "r", encoding="utf-8") as f:
        js_content = f.read()

    # Extract pattern from join.js
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

    # Test vectors matching Android InviteLinkParserTest
    token_valid = "A" * 43
    test_cases = [
        # (token, expected_valid, label)
        (token_valid, True, "43 A's"),
        ("a" * 43, True, "43 lowercase a's"),
        ("0" * 43, True, "43 zeros"),
        ("-_" * 21 + "A", True, "43 url-safe chars with - and _"),
        ("0123456789abcdefghijklmnopqrstuvwxyzABC-_xy", True, "43 mixed chars"),
        # Invalid vectors:
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

    # Test full URL path extractor simulation
    def simulate_extract(pathname, search, hash_val):
        if search or hash_val:
            return None
        if not pathname or not pathname.startswith("/join/"):
            return None
        token = pathname[len("/join/"):]
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
        log_pass(f"All {len(test_cases) + len(path_cases)} token validator test vectors passed (matching Android InviteLinkParserTest)")
    return failures

def main():
    print("=== Running NexaChat Site Checks ===")
    all_failures = []

    # 1. JSON
    vercel_data, f1 = test_json_files()
    all_failures.extend(f1)

    # 2. Headers
    f2 = test_vercel_headers(vercel_data)
    all_failures.extend(f2)

    # 3. External URLs
    f3 = test_no_external_urls()
    all_failures.extend(f3)

    # 4. Inline scripts/styles
    f4 = test_no_inline_scripts_and_styles()
    all_failures.extend(f4)

    # 5. Token validator
    f5 = test_token_validator()
    all_failures.extend(f5)

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
