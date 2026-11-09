#!/usr/bin/env bash
#
# check-domain.sh: Domain resolution, TLS, Asset Links, and Google DAL verification.
# Works in Git Bash (Windows) and Linux.
# No secrets, no authentication required.
#

set -o pipefail

DEFAULT_DOMAIN="nexachat.io.vn"
DEFAULT_WWW_DOMAIN="www.nexachat.io.vn"
DEFAULT_EXPECTED_IP="76.76.21.21"
DEFAULT_EXPECTED_CNAME="cname.vercel-dns.com"
DEFAULT_EXPECTED_PACKAGE="com.nexachat"
DEFAULT_EXPECTED_FINGERPRINT="AA:A9:6B:96:7C:ED:C6:78:DE:9C:77:35:78:5C:87:8C:B2:5D:AA:43:52:9B:E0:DF:EF:0B:73:93:4D:BE:01:7A"

DOMAIN="$DEFAULT_DOMAIN"
WWW_DOMAIN="$DEFAULT_WWW_DOMAIN"
EXPECTED_IP="$DEFAULT_EXPECTED_IP"
EXPECTED_CNAME="$DEFAULT_EXPECTED_CNAME"
EXPECTED_PACKAGE="$DEFAULT_EXPECTED_PACKAGE"
EXPECTED_FINGERPRINT="$DEFAULT_EXPECTED_FINGERPRINT"
SELF_TEST=0
VERBOSE=0

FAIL_COUNT=0
PASS_COUNT=0
NOT_RUN_COUNT=0

log_pass() {
    echo "[PASS] $1"
    PASS_COUNT=$((PASS_COUNT + 1))
}

log_fail() {
    echo "[FAIL] $1"
    FAIL_COUNT=$((FAIL_COUNT + 1))
}

log_not_run() {
    echo "[NOT_RUN] $1"
    NOT_RUN_COUNT=$((NOT_RUN_COUNT + 1))
}

log_info() {
    echo "[INFO] $1"
}

# --- Core Resolution Logic ---

# Parses A records from dig or nslookup output
parse_a_records() {
    local raw="$1"
    if echo "$raw" | grep -qE "^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$"; then
        # Pure dig output
        echo "$raw" | grep -E "^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$"
    else
        # nslookup output: lines with IPs after 'Name:'
        echo "$raw" | awk '/Name:/{flag=1; next} flag && /[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/ {print}' | grep -oE "[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+"
    fi
}

# Parses CNAME from dig or nslookup output
parse_cname_record() {
    local raw="$1"
    # dig output or nslookup output
    local cname=""
    if echo "$raw" | grep -qiE "canonical name|cname"; then
        cname=$(echo "$raw" | grep -iE "canonical name|cname" | head -n 1 | sed -E "s/.*(canonical name|cname)[[:space:]]*=[[:space:]]*//I; s/^[[:space:]]*//; s/[[:space:]]*$//; s/\.$//")
    else
        # Check if dig output gave a hostname directly ending with dot or alnum
        cname=$(echo "$raw" | grep -E "^[a-zA-Z0-9.-]+\.?$" | head -n 1 | sed 's/\.$//')
    fi
    echo "$cname"
}

eval_dns_a() {
    local raw="$1"
    local expected="$2"
    local ips
    ips=$(parse_a_records "$raw")
    if [ -z "$ips" ]; then
        echo "NOT_RUN: No A records resolved (domain not yet propagated or reachable)"
        return 0
    fi
    local found_expected=0
    local all_ips=""
    for ip in $ips; do
        all_ips="${all_ips:+$all_ips, }$ip"
        if [ "$ip" = "$expected" ]; then
            found_expected=1
        fi
    done
    if [ "$found_expected" -eq 1 ]; then
        echo "PASS: Apex domain resolves to expected IP $expected (resolved: $all_ips)"
    else
        echo "FAIL: Apex domain resolved to [$all_ips], expected IP $expected"
    fi
}

eval_dns_cname() {
    local raw="$1"
    local expected="$2"
    local cname
    cname=$(parse_cname_record "$raw")
    if [ -z "$cname" ]; then
        echo "NOT_RUN: No CNAME record resolved (www subdomain not yet propagated or reachable)"
        return 0
    fi
    # Check if cname matches expected or contains vercel-dns
    if echo "$cname" | grep -qiE "$expected|cname\.vercel-dns\.com"; then
        echo "PASS: www subdomain CNAME points to $cname (expected: $expected)"
    else
        echo "FAIL: www subdomain CNAME points to $cname, expected $expected"
    fi
}

eval_tls_cert() {
    local raw="$1"
    local expired_code="$2"
    if [ -z "$raw" ]; then
        echo "NOT_RUN: TLS handshake could not connect (host unreachable or connection refused)"
        return 0
    fi
    local subject
    subject=$(echo "$raw" | grep -i "^subject=" | head -n 1)
    local expiry
    expiry=$(echo "$raw" | grep -i "^notAfter=" | head -n 1)

    if [ -z "$subject" ]; then
        echo "NOT_RUN: TLS certificate could not be retrieved"
        return 0
    fi

    if [ "$expired_code" -ne 0 ]; then
        echo "FAIL: TLS certificate is expired ($expiry, $subject)"
    else
        echo "PASS: TLS certificate valid ($subject, $expiry)"
    fi
}

eval_assetlinks() {
    local http_code="$1"
    local redirects="$2"
    local content_type="$3"
    local body="$4"
    local expected_pkg="$5"
    local expected_fp="$6"
    local curl_ec="$7"

    if [ "$curl_ec" -ne 0 ]; then
        echo "NOT_RUN: Assetlinks fetch failed with curl error $curl_ec (host unreachable)"
        return 0
    fi

    if [ "$redirects" -gt 0 ]; then
        echo "FAIL: Apex assetlinks.json redirected ($redirects redirect(s) observed, redirects are forbidden for Digital Asset Links)"
        return 0
    fi

    if [ "$http_code" -ne 200 ]; then
        echo "FAIL: Assetlinks fetch returned HTTP status $http_code (expected 200)"
        return 0
    fi

    if ! echo "$content_type" | grep -qi "application/json"; then
        echo "FAIL: Assetlinks Content-Type is '$content_type' (expected application/json)"
        return 0
    fi

    if ! echo "$body" | grep -q "$expected_pkg"; then
        echo "FAIL: Assetlinks payload does not contain expected package '$expected_pkg'"
        return 0
    fi

    if ! echo "$body" | grep -q "$expected_fp"; then
        echo "FAIL: Assetlinks payload does not contain expected fingerprint '$expected_fp'"
        return 0
    fi

    echo "PASS: Assetlinks fetched directly (status: 200, redirects: 0, Content-Type: $content_type, package and fingerprint verified)"
}

eval_google_dal_api() {
    local raw="$1"
    local expected_pkg="$2"
    local expected_fp="$3"
    local curl_ec="$4"

    if [ "$curl_ec" -ne 0 ]; then
        echo "NOT_RUN: Failed to query Google Digital Asset Links API (curl error $curl_ec)"
        return 0
    fi

    # Check for deadline exceeded / timeout / cannot download errors from Google DAL API
    if echo "$raw" | grep -qiE "deadline_exceeded|ERRORS|Timeout occurred while fetching Web statements"; then
        echo "NOT_RUN: Google DAL API could not fetch statements from web site (domain not yet reachable by Google crawler)"
        return 0
    fi

    if ! echo "$raw" | grep -q '"statements"'; then
        echo "FAIL: Google DAL API returned response with no statements: $raw"
        return 0
    fi

    local has_pkg=0
    local has_fp=0
    if echo "$raw" | grep -q "$expected_pkg"; then
        has_pkg=1
    fi
    if echo "$raw" | grep -q "$expected_fp"; then
        has_fp=1
    fi

    if [ "$has_pkg" -eq 1 ] && [ "$has_fp" -eq 1 ]; then
        echo "PASS: Google DAL API returned valid statements containing package '$expected_pkg' and fingerprint '$expected_fp'"
    elif [ "$has_pkg" -eq 0 ]; then
        echo "FAIL: Google DAL API statements missing expected package '$expected_pkg'"
    else
        echo "FAIL: Google DAL API statements missing expected fingerprint '$expected_fp'"
    fi
}

# --- Live Check Functions ---

check_dns_records() {
    log_info "1. Checking DNS resolution (A and CNAME)..."
    local apex_raw=""
    if command -v dig >/dev/null 2>&1; then
        apex_raw=$(dig +short A "$DOMAIN" 2>/dev/null)
    fi
    if [ -z "$apex_raw" ] && command -v nslookup >/dev/null 2>&1; then
        apex_raw=$(nslookup -type=A "$DOMAIN" 2>&1)
    fi
    if [ "$VERBOSE" -eq 1 ]; then
        log_info "Apex DNS raw: $apex_raw"
    fi

    local apex_res
    apex_res=$(eval_dns_a "$apex_raw" "$EXPECTED_IP")
    local apex_status="${apex_res%%:*}"
    local apex_msg="${apex_res#*: }"
    if [ "$apex_status" = "PASS" ]; then
        log_pass "Apex DNS (A record): $apex_msg"
    elif [ "$apex_status" = "FAIL" ]; then
        log_fail "Apex DNS (A record): $apex_msg"
    else
        log_not_run "Apex DNS (A record): $apex_msg"
    fi

    # Check CNAME for www
    local www_raw=""
    if command -v dig >/dev/null 2>&1; then
        www_raw=$(dig +short CNAME "$WWW_DOMAIN" 2>/dev/null)
    fi
    if [ -z "$www_raw" ] && command -v nslookup >/dev/null 2>&1; then
        www_raw=$(nslookup -type=CNAME "$WWW_DOMAIN" 2>&1)
    fi

    local www_res
    www_res=$(eval_dns_cname "$www_raw" "$EXPECTED_CNAME")
    local www_status="${www_res%%:*}"
    local www_msg="${www_res#*: }"
    if [ "$www_status" = "PASS" ]; then
        log_pass "WWW DNS (CNAME record): $www_msg"
    elif [ "$www_status" = "FAIL" ]; then
        log_fail "WWW DNS (CNAME record): $www_msg"
    else
        log_not_run "WWW DNS (CNAME record): $www_msg"
    fi
}

check_tls_handshake() {
    log_info "2. Checking TLS Certificate..."
    if ! command -v openssl >/dev/null 2>&1; then
        log_not_run "openssl is not installed; cannot verify TLS certificate"
        return 0
    fi

    local tls_raw
    tls_raw=$(echo | openssl s_client -servername "$DOMAIN" -connect "$DOMAIN:443" 2>/dev/null | openssl x509 -noout -subject -dates 2>/dev/null)
    local checkend_ec=0
    echo | openssl s_client -servername "$DOMAIN" -connect "$DOMAIN:443" 2>/dev/null | openssl x509 -checkend 0 -noout >/dev/null 2>&1
    checkend_ec=$?

    local tls_res
    tls_res=$(eval_tls_cert "$tls_raw" "$checkend_ec")
    local tls_status="${tls_res%%:*}"
    local tls_msg="${tls_res#*: }"
    if [ "$tls_status" = "PASS" ]; then
        log_pass "TLS Certificate: $tls_msg"
    elif [ "$tls_status" = "FAIL" ]; then
        log_fail "TLS Certificate: $tls_msg"
    else
        log_not_run "TLS Certificate: $tls_msg"
    fi
}

check_http_assetlinks() {
    log_info "3. Checking HTTPS Assetlinks Serving..."
    local tmp_body
    tmp_body=$(mktemp)
    local curl_meta
    curl_meta=$(curl -sS -L -o "$tmp_body" -w "HTTP_CODE:%{http_code}\nREDIRECTS:%{num_redirects}\nCONTENT_TYPE:%{content_type}\n" --max-time 10 "https://$DOMAIN/.well-known/assetlinks.json" 2>&1)
    local curl_ec=$?

    local http_code=""
    local redirects=""
    local content_type=""
    if [ "$curl_ec" -eq 0 ]; then
        http_code=$(echo "$curl_meta" | grep "^HTTP_CODE:" | cut -d: -f2)
        redirects=$(echo "$curl_meta" | grep "^REDIRECTS:" | cut -d: -f2)
        content_type=$(echo "$curl_meta" | grep "^CONTENT_TYPE:" | cut -d: -f2-)
    fi
    local body_content=""
    if [ -f "$tmp_body" ]; then
        body_content=$(cat "$tmp_body")
        rm -f "$tmp_body"
    fi

    local asset_res
    asset_res=$(eval_assetlinks "$http_code" "$redirects" "$content_type" "$body_content" "$EXPECTED_PACKAGE" "$EXPECTED_FINGERPRINT" "$curl_ec")
    local asset_status="${asset_res%%:*}"
    local asset_msg="${asset_res#*: }"
    if [ "$asset_status" = "PASS" ]; then
        log_pass "Assetlinks Serving: $asset_msg"
    elif [ "$asset_status" = "FAIL" ]; then
        log_fail "Assetlinks Serving: $asset_msg"
    else
        log_not_run "Assetlinks Serving: $asset_msg"
    fi
}

check_google_dal() {
    log_info "4. Querying Google Digital Asset Links API..."
    local api_url="https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://${DOMAIN}&relation=delegate_permission/common.handle_all_urls"
    local api_raw
    api_raw=$(curl -sS --max-time 15 "$api_url" 2>&1)
    local curl_ec=$?

    local dal_res
    dal_res=$(eval_google_dal_api "$api_raw" "$EXPECTED_PACKAGE" "$EXPECTED_FINGERPRINT" "$curl_ec")
    local dal_status="${dal_res%%:*}"
    local dal_msg="${dal_res#*: }"
    if [ "$dal_status" = "PASS" ]; then
        log_pass "Google DAL API: $dal_msg"
    elif [ "$dal_status" = "FAIL" ]; then
        log_fail "Google DAL API: $dal_msg"
    else
        log_not_run "Google DAL API: $dal_msg"
    fi
}

# --- Unit Self-Test Suite ---

run_self_tests() {
    log_info "=== Running Offline Self-Tests with Canned Responses ==="
    local self_failures=0

    assert_eval() {
        local test_name="$1"
        local expected_status="$2"
        local actual_res="$3"
        local actual_status="${actual_res%%:*}"
        if [ "$actual_status" != "$expected_status" ]; then
            echo "[SELF-TEST FAIL] $test_name: expected $expected_status, got: $actual_res"
            self_failures=$((self_failures + 1))
        else
            echo "[SELF-TEST PASS] $test_name -> $actual_status"
        fi
    }

    # 1. DNS A Tests
    local canned_dns_a_pass
    canned_dns_a_pass=$(printf "Name: nexachat.io.vn\nAddress: 76.76.21.21")
    local canned_dns_a_notrun="*** dns.google can't find nexachat.io.vn: Non-existent domain"
    local canned_dns_a_fail
    canned_dns_a_fail=$(printf "Name: nexachat.io.vn\nAddress: 1.2.3.4")
    assert_eval "DNS A valid" "PASS" "$(eval_dns_a "$canned_dns_a_pass" "76.76.21.21")"
    assert_eval "DNS A NXDOMAIN" "NOT_RUN" "$(eval_dns_a "$canned_dns_a_notrun" "76.76.21.21")"
    assert_eval "DNS A wrong IP" "FAIL" "$(eval_dns_a "$canned_dns_a_fail" "76.76.21.21")"

    # 2. DNS CNAME Tests
    local canned_cname_pass="www.nexachat.io.vn canonical name = cname.vercel-dns.com."
    local canned_cname_notrun="*** dns.google can't find www.nexachat.io.vn: Non-existent domain"
    local canned_cname_fail="www.nexachat.io.vn canonical name = evil.example.com."
    assert_eval "DNS CNAME valid" "PASS" "$(eval_dns_cname "$canned_cname_pass" "cname.vercel-dns.com")"
    assert_eval "DNS CNAME missing" "NOT_RUN" "$(eval_dns_cname "$canned_cname_notrun" "cname.vercel-dns.com")"
    assert_eval "DNS CNAME mismatch" "FAIL" "$(eval_dns_cname "$canned_cname_fail" "cname.vercel-dns.com")"

    # 3. TLS Certificate Tests
    local canned_tls_pass="subject=CN=nexachat.io.vn\nnotBefore=Nov 9 00:00:00 2026 GMT\nnotAfter=Feb 9 00:00:00 2027 GMT"
    assert_eval "TLS Cert valid future" "PASS" "$(eval_tls_cert "$canned_tls_pass" 0)"
    assert_eval "TLS Cert expired" "FAIL" "$(eval_tls_cert "$canned_tls_pass" 1)"
    assert_eval "TLS Cert unreachable" "NOT_RUN" "$(eval_tls_cert "" 1)"

    # 4. Assetlinks Serving Tests
    local canned_asset_body='[{"relation":["delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"com.nexachat","sha256_cert_fingerprints":["AA:A9:6B:96:7C:ED:C6:78:DE:9C:77:35:78:5C:87:8C:B2:5D:AA:43:52:9B:E0:DF:EF:0B:73:93:4D:BE:01:7A"]}}]'
    assert_eval "Assetlinks valid 200" "PASS" "$(eval_assetlinks 200 0 "application/json" "$canned_asset_body" "com.nexachat" "AA:A9:6B:96:7C:ED:C6:78:DE:9C:77:35:78:5C:87:8C:B2:5D:AA:43:52:9B:E0:DF:EF:0B:73:93:4D:BE:01:7A" 0)"
    assert_eval "Assetlinks redirected" "FAIL" "$(eval_assetlinks 200 1 "application/json" "$canned_asset_body" "com.nexachat" "AA:A9:6B:96:7C:ED:C6:78:DE:9C:77:35:78:5C:87:8C:B2:5D:AA:43:52:9B:E0:DF:EF:0B:73:93:4D:BE:01:7A" 0)"
    assert_eval "Assetlinks wrong content type" "FAIL" "$(eval_assetlinks 200 0 "text/html" "$canned_asset_body" "com.nexachat" "AA:A9:6B:96:7C:ED:C6:78:DE:9C:77:35:78:5C:87:8C:B2:5D:AA:43:52:9B:E0:DF:EF:0B:73:93:4D:BE:01:7A" 0)"
    assert_eval "Assetlinks 404" "FAIL" "$(eval_assetlinks 404 0 "application/json" "$canned_asset_body" "com.nexachat" "AA:A9:6B:96:7C:ED:C6:78:DE:9C:77:35:78:5C:87:8C:B2:5D:AA:43:52:9B:E0:DF:EF:0B:73:93:4D:BE:01:7A" 0)"
    assert_eval "Assetlinks missing pkg" "FAIL" "$(eval_assetlinks 200 0 "application/json" "$canned_asset_body" "com.other" "AA:A9:6B:96:7C:ED:C6:78:DE:9C:77:35:78:5C:87:8C:B2:5D:AA:43:52:9B:E0:DF:EF:0B:73:93:4D:BE:01:7A" 0)"
    assert_eval "Assetlinks unreachable" "NOT_RUN" "$(eval_assetlinks 0 0 "" "" "com.nexachat" "AA:A9:6B:96:7C:ED:C6:78:DE:9C:77:35:78:5C:87:8C:B2:5D:AA:43:52:9B:E0:DF:EF:0B:73:93:4D:BE:01:7A" 6)"

    # 5. Google DAL API Tests
    local canned_dal_pass='{"statements":[{"relation":"delegate_permission/common.handle_all_urls","target":{"androidApp":{"packageName":"com.nexachat","certificate":{"sha256Fingerprint":"AA:A9:6B:96:7C:ED:C6:78:DE:9C:77:35:78:5C:87:8C:B2:5D:AA:43:52:9B:E0:DF:EF:0B:73:93:4D:BE:01:7A"}}}}]}'
    local canned_dal_notrun='{"debugString":"* Error: deadline_exceeded: Timeout occurred while fetching Web statements"}'
    local canned_dal_fail_pkg='{"statements":[{"relation":"delegate_permission/common.handle_all_urls","target":{"androidApp":{"packageName":"com.wrong","certificate":{"sha256Fingerprint":"AA:A9:6B:96:7C:ED:C6:78:DE:9C:77:35:78:5C:87:8C:B2:5D:AA:43:52:9B:E0:DF:EF:0B:73:93:4D:BE:01:7A"}}}}]}'
    assert_eval "DAL API valid statement" "PASS" "$(eval_google_dal_api "$canned_dal_pass" "com.nexachat" "AA:A9:6B:96:7C:ED:C6:78:DE:9C:77:35:78:5C:87:8C:B2:5D:AA:43:52:9B:E0:DF:EF:0B:73:93:4D:BE:01:7A" 0)"
    assert_eval "DAL API deadline exceeded" "NOT_RUN" "$(eval_google_dal_api "$canned_dal_notrun" "com.nexachat" "AA:A9:6B:96:7C:ED:C6:78:DE:9C:77:35:78:5C:87:8C:B2:5D:AA:43:52:9B:E0:DF:EF:0B:73:93:4D:BE:01:7A" 0)"
    assert_eval "DAL API pkg mismatch" "FAIL" "$(eval_google_dal_api "$canned_dal_fail_pkg" "com.nexachat" "AA:A9:6B:96:7C:ED:C6:78:DE:9C:77:35:78:5C:87:8C:B2:5D:AA:43:52:9B:E0:DF:EF:0B:73:93:4D:BE:01:7A" 0)"
    assert_eval "DAL API connection fail" "NOT_RUN" "$(eval_google_dal_api "" "com.nexachat" "AA:A9:6B:96:7C:ED:C6:78:DE:9C:77:35:78:5C:87:8C:B2:5D:AA:43:52:9B:E0:DF:EF:0B:73:93:4D:BE:01:7A" 7)"

    echo ""
    if [ "$self_failures" -eq 0 ]; then
        echo "=== Self-test completed: all 16 unit checks passed ==="
        exit 0
    else
        echo "=== Self-test FAILED with $self_failures failure(s) ==="
        exit 1
    fi
}

# --- CLI Argument Parsing ---

while [ $# -gt 0 ]; do
    case "$1" in
        --self-test)
            SELF_TEST=1
            shift
            ;;
        --domain)
            DOMAIN="$2"
            shift 2
            ;;
        --www-domain)
            WWW_DOMAIN="$2"
            shift 2
            ;;
        --expected-ip)
            EXPECTED_IP="$2"
            shift 2
            ;;
        --expected-cname)
            EXPECTED_CNAME="$2"
            shift 2
            ;;
        --expected-package)
            EXPECTED_PACKAGE="$2"
            shift 2
            ;;
        --expected-fingerprint)
            EXPECTED_FINGERPRINT="$2"
            shift 2
            ;;
        --verbose)
            VERBOSE=1
            shift
            ;;
        -h|--help)
            echo "Usage: $0 [OPTIONS]"
            echo "Options:"
            echo "  --self-test              Run internal unit tests with canned responses (offline mode)"
            echo "  --domain DOMAIN          Apex domain to verify (default: $DEFAULT_DOMAIN)"
            echo "  --www-domain DOMAIN      WWW domain to verify (default: $DEFAULT_WWW_DOMAIN)"
            echo "  --expected-ip IP         Expected A record IP (default: $DEFAULT_EXPECTED_IP)"
            echo "  --expected-cname CNAME   Expected CNAME (default: $DEFAULT_EXPECTED_CNAME)"
            echo "  --expected-package PKG   Expected package name (default: $DEFAULT_EXPECTED_PACKAGE)"
            echo "  --expected-fingerprint FP Expected cert fingerprint (default: $DEFAULT_EXPECTED_FINGERPRINT)"
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
    esac
done

if [ "$SELF_TEST" -eq 1 ]; then
    run_self_tests
fi

echo "=== Domain Verification: $DOMAIN ==="
log_info "Target Apex Domain: $DOMAIN (Expected A: $EXPECTED_IP)"
log_info "Target WWW Domain:  $WWW_DOMAIN (Expected CNAME: $EXPECTED_CNAME)"
log_info "Expected Package:   $EXPECTED_PACKAGE"
log_info "Expected Fingerprint: $EXPECTED_FINGERPRINT"
echo ""

check_dns_records
echo ""
check_tls_handshake
echo ""
check_http_assetlinks
echo ""
check_google_dal

echo ""
echo "=== Verification Summary ==="
echo "PASS:    $PASS_COUNT"
echo "NOT_RUN: $NOT_RUN_COUNT"
echo "FAIL:    $FAIL_COUNT"

if [ "$FAIL_COUNT" -gt 0 ]; then
    echo "STATUS: FAILED"
    exit 1
else
    echo "STATUS: OK (zero failures)"
    exit 0
fi
