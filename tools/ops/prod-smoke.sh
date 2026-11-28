#!/usr/bin/env bash
# tools/ops/prod-smoke.sh — post-deploy smoke test for a NexaChat API base URL.
#
# Read-only by default. It waits for a sleeping server (Render Free wakes in
# about a minute), then checks health, transport security, the browser CORS
# pair used by site/delete-account.js, unauthenticated rejection, the public
# OTP request, and the /ws upgrade.
#
# --write additionally proves the account/avatar/deletion round-trip with
# disposable accounts in the reserved +84908 test range. Nothing is written
# without it.
#
# The API identifies accounts by E.164 phone, not email, so the public OTP
# probe uses the reserved non-routable test number +84908000000 once and never
# touches a real address.
#
# Usage:
#   bash tools/ops/prod-smoke.sh [options] <base-url>
#   bash tools/ops/prod-smoke.sh --self-test
#
# Examples:
#   bash tools/ops/prod-smoke.sh https://api.nexachat.io.vn
#   bash tools/ops/prod-smoke.sh --write http://localhost:8080
#   bash tools/ops/prod-smoke.sh --self-test
#
# Exit codes: 0 all checks passed, 1 one or more checks failed, 2 usage error.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BASE_URL=""
SITE_URL=""
DO_WRITE=false
WAIT_SECONDS=120
HTTP_TIMEOUT=20
REQUIRE_HSTS=false
SELF_TEST=false
SOCIAL_WEB_ENABLED=""
POST_ID=""

# The site's deletion form (site/delete-account.js:209,253) calls exactly these
# two routes cross-origin from the public site.
SITE_ORIGIN="${PROD_SMOKE_SITE_ORIGIN:-https://nexachat.io.vn}"
OTP_PHONE="${PROD_SMOKE_OTP_PHONE:-+84908000000}"
CORS_PATHS=(
    "/api/v1/account-deletion/request"
    "/api/v1/account-deletion/confirm"
)

FAILED=0
WORK_DIR=""
HDR=""
BODY=""
CURL_ERR=""
HTTP_CODE=""
HTTP_RC=0
PYTHON_BIN="${PYTHON_BIN:-}"

usage() {
    cat <<'USAGE'
Usage: bash tools/ops/prod-smoke.sh [options] <base-url>
       bash tools/ops/prod-smoke.sh --self-test

Options:
  --site-url URL    also verify public site /, /social/, /admin/, /join/,
                    and /.well-known/assetlinks.json
  --write           register disposable accounts, upload/fetch an avatar,
                    verify ice-config/Range 206/feed round-trip, delete them,
                    and verify the cleanup
  --wait SECONDS    health wake-up timeout (default 120)
  --timeout SECONDS per-request timeout (default 20)
  --require-hsts    fail when an https base URL sends no HSTS header
  --self-test       run the offline stub-server test suite
  -h, --help        show this help

Environment overrides:
  PYTHON_BIN                 python3/python used for JSON parsing
  PROD_SMOKE_SITE_ORIGIN     browser origin checked by CORS (default
                             https://nexachat.io.vn)
  PROD_SMOKE_OTP_PHONE       reserved fake phone for the OTP probe
                             (default +84908000000)

The read-only run polls /actuator/health (the actuator config exposes only
health) and sends exactly one public OTP request.
USAGE
}

log_pass() { printf 'PASS  %s\n' "$*"; }
log_fail() {
    printf 'FAIL  %s\n' "$*" >&2
    FAILED=$((FAILED + 1))
}
log_warn() { printf 'WARN  %s\n' "$*"; }
log_info() { printf 'INFO  %s\n' "$*"; }

die() {
    printf 'ERROR %s\n' "$*" >&2
    exit 2
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --site-url)
            [ "$#" -ge 2 ] || die "--site-url requires a value"
            SITE_URL="$2"
            shift
            ;;
        --write)
            DO_WRITE=true
            ;;
        --wait)
            [ "$#" -ge 2 ] || die "--wait requires a value"
            WAIT_SECONDS="$2"
            shift
            ;;
        --timeout)
            [ "$#" -ge 2 ] || die "--timeout requires a value"
            HTTP_TIMEOUT="$2"
            shift
            ;;
        --require-hsts)
            REQUIRE_HSTS=true
            ;;
        --self-test)
            SELF_TEST=true
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        -*)
            die "unknown option '$1' (use --help)"
            ;;
        *)
            [ -z "$BASE_URL" ] || die "expected one base URL, got '$1' (use --help)"
            BASE_URL="$1"
            ;;
    esac
    shift
done

find_python() {
    if [ -n "$PYTHON_BIN" ]; then
        command -v "$PYTHON_BIN" >/dev/null 2>&1 || die "PYTHON_BIN=$PYTHON_BIN is not executable"
        return 0
    fi
    if command -v python3 >/dev/null 2>&1; then
        PYTHON_BIN=python3
    elif command -v python >/dev/null 2>&1; then
        PYTHON_BIN=python
    else
        die "python3 or python is required for JSON parsing"
    fi
    "$PYTHON_BIN" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' \
        || die "Python 3.8+ is required"
}

if [ "$SELF_TEST" = true ]; then
    find_python
    exec "$PYTHON_BIN" "$SCRIPT_DIR/prod-smoke-self-test.py"
fi

[ -n "$BASE_URL" ] || die "base URL is required (use --help)"
case "$BASE_URL" in
    */) BASE_URL="${BASE_URL%/}" ;;
esac
if ! [[ "$BASE_URL" =~ ^https?://[^/?#]+$ ]]; then
    die "base URL must be an http(s) origin without a path, query or fragment"
fi
if [ -n "$SITE_URL" ]; then
    case "$SITE_URL" in
        */) SITE_URL="${SITE_URL%/}" ;;
    esac
    if ! [[ "$SITE_URL" =~ ^https?://[^/?#]+$ ]]; then
        die "--site-url must be an http(s) origin without a path, query or fragment"
    fi
fi
[[ "$WAIT_SECONDS" =~ ^[0-9]+$ ]] || die "--wait must be a non-negative integer"
[[ "$HTTP_TIMEOUT" =~ ^[0-9]+$ ]] || die "--timeout must be a non-negative integer"

find_python

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nexachat-prod-smoke.XXXXXX")"
HDR="$WORK_DIR/headers.txt"
BODY="$WORK_DIR/body.txt"
CURL_ERR="$WORK_DIR/curl.err"
trap 'rm -rf "$WORK_DIR"' EXIT

# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

# http METHOD URL [curl options...]
# Result: HTTP_CODE (curl -w value; empty on connection failure) and HTTP_RC.
http() {
    local method="$1"
    local url="$2"
    shift 2
    : > "$HDR"
    : > "$BODY"
    : > "$CURL_ERR"
    HTTP_CODE=""
    HTTP_RC=0
    HTTP_CODE="$(curl -sS --max-time "$HTTP_TIMEOUT" -o "$BODY" -D "$HDR" \
        -w '%{http_code}' -X "$method" "$@" "$url" 2>"$CURL_ERR")" || HTTP_RC=$?
}

header_value() { # headers file, header name (case-insensitive, last one wins)
    local file="$1"
    local want="$2"
    awk -v want="$want" '
        {
            sub(/\r$/, "", $0)
            i = index($0, ":")
            if (i > 0) {
                name = tolower(substr($0, 1, i - 1))
                if (name == tolower(want)) {
                    value = substr($0, i + 2)
                    sub(/^[ \t]+/, "", value)
                    sub(/[ \t]+$/, "", value)
                    last = value
                }
            }
        }
        END { if (last != "") print last }
    ' "$file"
}

# json_get FILE dotted.path — prints nothing when the file is not JSON or the
# path is absent. Only used for small response objects.
json_get() {
    "$PYTHON_BIN" - "$1" "$2" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as handle:
        value = json.load(handle)
except (OSError, ValueError):
    sys.exit(0)

for part in sys.argv[2].split("."):
    if not part:
        continue
    if isinstance(value, dict):
        value = value.get(part)
    elif isinstance(value, list) and part.isdigit():
        index = int(part)
        value = value[index] if 0 <= index < len(value) else None
    else:
        value = None
    if value is None:
        break

if value is not None and not isinstance(value, (dict, list)):
    print(value)
PY
}

body_snippet() {
    if [ -s "$BODY" ]; then
        head -c 240 "$BODY" | tr '\n' ' '
    else
        printf '<empty>'
    fi
}

curl_error_snippet() {
    if [ -s "$CURL_ERR" ]; then
        head -c 240 "$CURL_ERR" | tr '\n' ' '
    else
        printf '<no curl output>'
    fi
}

# Git Bash hands a POSIX /tmp/... path to the native curl.exe, which cannot
# open it. cygpath -m yields a mixed C:/... path that both sides understand;
# on Linux/macOS cygpath is absent and the path is unchanged.
curl_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s' "$1"
    fi
}

expect_status() { # label expected
    local label="$1"
    local expected="$2"
    if [ "$HTTP_RC" -ne 0 ]; then
        log_fail "$label: request failed (curl exit $HTTP_RC: $(curl_error_snippet))"
        return 1
    fi
    if [ "$HTTP_CODE" != "$expected" ]; then
        log_fail "$label: expected HTTP $expected, got ${HTTP_CODE:-none} ($(body_snippet))"
        return 1
    fi
    return 0
}

# ---------------------------------------------------------------------------
# Read-only checks
# ---------------------------------------------------------------------------

wait_for_health() {
    local start now elapsed attempt last status poll
    start="$(date +%s)"
    attempt=0
    last=""
    poll=5
    [ "$WAIT_SECONDS" -lt 5 ] && poll=1

    log_info "Polling $BASE_URL/actuator/health for up to ${WAIT_SECONDS}s (server cold start)"
    while :; do
        attempt=$((attempt + 1))
        http GET "$BASE_URL/actuator/health"
        now="$(date +%s)"
        elapsed=$((now - start))
        status="$(json_get "$BODY" status)"
        if [ "$HTTP_RC" -eq 0 ] && [ "$HTTP_CODE" = "200" ] && [ "$status" = "UP" ]; then
            cp "$HDR" "$WORK_DIR/health.headers"
            log_pass "health: UP at $BASE_URL/actuator/health (${elapsed}s, attempt $attempt)"
            return 0
        fi
        if [ "$HTTP_RC" -ne 0 ]; then
            last="curl exit $HTTP_RC, HTTP ${HTTP_CODE:-none} ($(curl_error_snippet))"
        elif [ -n "$status" ]; then
            last="HTTP ${HTTP_CODE:-none} status=$status ($(body_snippet))"
        else
            last="HTTP ${HTTP_CODE:-none} ($(body_snippet))"
        fi

        if [ "$elapsed" -ge "$WAIT_SECONDS" ]; then
            log_fail "health: not UP within ${WAIT_SECONDS}s (last: $last)"
            return 1
        fi
        sleep "$poll"
    done
}

check_transport_security() {
    case "$BASE_URL" in
        https://*)
            log_pass "TLS: HTTPS health request completed with certificate verification"
            local hsts max_age
            hsts="$(header_value "$WORK_DIR/health.headers" "Strict-Transport-Security")"
            if [ -n "$hsts" ]; then
                max_age="$(printf '%s\n' "$hsts" | tr ';' '\n' \
                    | awk -F= 'tolower($1) == "max-age" { gsub(/[^0-9]/, "", $2); print $2; exit }')"
                if [ -n "$max_age" ] && [ "$max_age" -gt 0 ]; then
                    log_pass "HSTS: max-age=$max_age"
                else
                    log_fail "HSTS: header has no positive max-age: $hsts"
                fi
            elif [ "$REQUIRE_HSTS" = true ]; then
                log_fail "HSTS: missing from /actuator/health (--require-hsts)"
            else
                log_warn "HSTS: not sent by the API; the HTTPS edge may add it (use --require-hsts to enforce)"
            fi
            ;;
        *)
            log_info "TLS/HSTS: skipped for a plain-HTTP base URL"
            ;;
    esac
}

check_cors() {
    local path origin methods
    for path in "${CORS_PATHS[@]}"; do
        http OPTIONS "$BASE_URL$path" \
            -H "Origin: $SITE_ORIGIN" \
            -H "Access-Control-Request-Method: POST" \
            -H "Access-Control-Request-Headers: content-type"
        if [ "$HTTP_RC" -ne 0 ]; then
            log_fail "CORS $path: preflight failed (curl exit $HTTP_RC: $(curl_error_snippet))"
            continue
        fi
        if [ "$HTTP_CODE" != "200" ]; then
            log_fail "CORS $path: preflight returned HTTP ${HTTP_CODE:-none}, expected 200; add $SITE_ORIGIN to NEXACHAT_CORS_ALLOWED_ORIGINS ($(body_snippet))"
            continue
        fi
        origin="$(header_value "$HDR" "Access-Control-Allow-Origin")"
        methods="$(header_value "$HDR" "Access-Control-Allow-Methods")"
        if [ "$origin" != "$SITE_ORIGIN" ]; then
            log_fail "CORS $path: Access-Control-Allow-Origin is '${origin:-missing}', expected '$SITE_ORIGIN'"
        elif ! printf '%s' "$methods" | tr '[:lower:]' '[:upper:]' | grep -q 'POST'; then
            log_fail "CORS $path: Access-Control-Allow-Methods '${methods:-missing}' does not allow POST"
        else
            log_pass "CORS $path: preflight allows $SITE_ORIGIN POST"
        fi
    done
}

check_unauthenticated_rejection() {
    local code
    http GET "$BASE_URL/api/v1/users/me"
    if ! expect_status "unauthenticated GET /api/v1/users/me" 401; then
        return
    fi
    code="$(json_get "$BODY" error.code)"
    case "$code" in
        UNAUTHORIZED|TOKEN_INVALID|TOKEN_EXPIRED)
            log_pass "unauthenticated GET /api/v1/users/me: 401 $code"
            ;;
        *)
            log_fail "unauthenticated GET /api/v1/users/me: 401 without a documented error code (got '${code:-none}')"
            ;;
    esac
}

check_otp_request() {
    local body code
    printf '{"phone":"%s"}' "$OTP_PHONE" > "$WORK_DIR/otp-request.json"
    # Exactly one request: public OTP buckets are per phone and per IP.
    http POST "$BASE_URL/api/v1/auth/password-reset/request" \
        -H "Content-Type: application/json" \
        --data-binary "@$(curl_path "$WORK_DIR/otp-request.json")"
    if [ "$HTTP_RC" -ne 0 ]; then
        log_fail "OTP request: request failed (curl exit $HTTP_RC: $(curl_error_snippet))"
        return
    fi
    if [ "$HTTP_CODE" != "202" ]; then
        code="$(json_get "$BODY" error.code)"
        log_fail "OTP request: expected HTTP 202 with the generic body, got ${HTTP_CODE:-none}${code:+ ($code)} ($(body_snippet))"
        return
    fi
    body="$(tr -d '[:space:]' < "$BODY")"
    if [ "$body" = "{}" ]; then
        log_pass "OTP request: 202 {} for the reserved fake phone (one call, generic response)"
    else
        log_fail "OTP request: expected an empty JSON object, got '$(body_snippet)'"
    fi
}

check_websocket_handshake() {
    local ws_url probe_url status upgrade accept rc ws_key
    case "$BASE_URL" in
        https://*) ws_url="wss://${BASE_URL#https://}/ws" ;;
        *) ws_url="ws://${BASE_URL#http://}/ws" ;;
    esac
    probe_url="${BASE_URL%/}/ws"
    # A fresh 16-byte RFC 6455 nonce; no literal key material in the script.
    if command -v openssl >/dev/null 2>&1; then
        ws_key="$(openssl rand -base64 16 | tr -d '\r\n')"
    elif [ -r /dev/urandom ]; then
        ws_key="$(head -c 16 /dev/urandom | base64 | tr -d '\r\n')"
    else
        ws_key="$(printf '%s' "$$-$(date +%s)-$RANDOM" | base64 | cut -c1-24)"
    fi
    : > "$HDR"
    : > "$BODY"
    : > "$CURL_ERR"
    rc=0
    curl -sS --http1.1 --max-time 5 -o "$BODY" -D "$HDR" \
        -H "Connection: Upgrade" \
        -H "Upgrade: websocket" \
        -H "Sec-WebSocket-Version: 13" \
        -H "Sec-WebSocket-Key: $ws_key" \
        -H "Sec-WebSocket-Protocol: v12.stomp" \
        "$probe_url" 2>"$CURL_ERR" || rc=$?
    status="$(head -n 1 "$HDR" 2>/dev/null | tr -d '\r')"
    upgrade="$(header_value "$HDR" "Upgrade")"
    accept="$(header_value "$HDR" "Sec-WebSocket-Accept")"
    if ! printf '%s' "$status" | grep -q ' 101'; then
        log_fail "WebSocket: expected HTTP 101 upgrade at $ws_url, got '${status:-no response}' (curl exit $rc)"
        return
    fi
    if [ "$(printf '%s' "$upgrade" | tr '[:upper:]' '[:lower:]')" != "websocket" ] || [ -z "$accept" ]; then
        log_fail "WebSocket: 101 without a valid Upgrade/Sec-WebSocket-Accept header"
        return
    fi
    log_pass "WebSocket: /ws upgraded to STOMP (101, Sec-WebSocket-Accept present)"
}

check_social_config() {
    local cc parsed
    http GET "$BASE_URL/api/social/v1/config"
    if ! expect_status "social config" 200; then
        return
    fi
    cc="$(header_value "$HDR" "Cache-Control")"
    if ! printf '%s' "$cc" | tr '[:upper:]' '[:lower:]' | grep -q 'no-store'; then
        log_fail "social config: Cache-Control '${cc:-missing}' missing no-store"
        return
    fi
    parsed="$("$PYTHON_BIN" - "$BODY" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as handle:
        d = json.load(handle)
except (OSError, ValueError):
    sys.exit(1)

if not isinstance(d, dict):
    sys.exit(1)
if not isinstance(d.get("webEnabled"), bool):
    sys.exit(1)
if not isinstance(d.get("videoUploadsEnabled"), bool):
    sys.exit(1)
if "announcement" not in d:
    sys.exit(1)
ann = d["announcement"]
if ann is not None:
    if not isinstance(ann, dict):
        sys.exit(1)
    for k in ("titleVi", "bodyVi", "titleEn", "bodyEn"):
        if k in ann and not isinstance(ann[k], str):
            sys.exit(1)
print("true" if d["webEnabled"] else "false")
PY
    )" || {
        log_fail "social config: invalid JSON schema ($(body_snippet))"
        return
    }
    SOCIAL_WEB_ENABLED="$parsed"
    log_pass "social config: 200 no-store (webEnabled=$SOCIAL_WEB_ENABLED)"
}

check_calls_ice_config_unauth() {
    local code
    http GET "$BASE_URL/api/v1/calls/ice-config"
    if ! expect_status "unauthenticated GET /api/v1/calls/ice-config" 401; then
        return
    fi
    code="$(json_get "$BODY" error.code)"
    case "$code" in
        UNAUTHORIZED|TOKEN_INVALID|TOKEN_EXPIRED)
            log_pass "unauthenticated GET /api/v1/calls/ice-config: 401 $code"
            ;;
        *)
            log_fail "unauthenticated GET /api/v1/calls/ice-config: 401 without documented error code (got '${code:-none}')"
            ;;
    esac
}

check_site_endpoints() {
    local csp loc cc ctype
    local expected_fp="E7:41:9A:48:A4:9D:20:4E:55:70:60:6E:B8:33:96:8D:CD:A7:28:E2:AC:99:D7:24:3B:2C:FE:8C:15:8A:39:DF"

    http GET "$SITE_URL/"
    if expect_status "site /" 200; then
        csp="$(header_value "$HDR" "Content-Security-Policy")"
        if [ -z "$csp" ]; then
            log_fail "site /: missing Content-Security-Policy header"
        elif ! grep -q 'NexaChat' "$BODY"; then
            log_fail "site /: body missing NexaChat marker"
        else
            log_pass "site /: 200 with CSP"
        fi
    fi

    http GET "$SITE_URL/social/"
    if expect_status "site /social/" 200; then
        csp="$(header_value "$HDR" "Content-Security-Policy")"
        if ! printf '%s' "$csp" | grep -q "connect-src 'self'" \
            || ! printf '%s' "$csp" | grep -q "img-src 'self' blob:" \
            || ! printf '%s' "$csp" | grep -q "media-src blob:"; then
            log_fail "site /social/: CSP missing strict connect-src/img-src/media-src directives (got '${csp:-missing}')"
        elif ! grep -qE 'social-app\.js|NexaChat Social' "$BODY"; then
            log_fail "site /social/: body missing social-app.js / NexaChat Social marker"
        else
            log_pass "site /social/: 200 with strict CSP"
        fi
    fi

    http GET "$SITE_URL/admin/"
    if expect_status "site /admin/" 200; then
        loc="$(header_value "$HDR" "Location")"
        if [ -n "$loc" ]; then
            log_fail "site /admin/: unexpected Location header '$loc'"
        elif grep -qiE 'vercel\.com/sso|Authentication Required|_vercel_sso_nonce' "$BODY"; then
            log_fail "site /admin/: Vercel SSO protection detected"
        elif ! grep -qE 'NexaChat Admin|/admin/assets/' "$BODY"; then
            log_fail "site /admin/: body missing NexaChat Admin or /admin/assets/ marker"
        else
            log_pass "site /admin/: 200 without SSO redirect"
        fi
    fi

    http GET "$SITE_URL/join/ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef0123456789_"
    if expect_status "site /join/<token>" 200; then
        cc="$(header_value "$HDR" "Cache-Control")"
        if ! printf '%s' "$cc" | tr '[:upper:]' '[:lower:]' | grep -q 'no-store'; then
            log_fail "site /join/<token>: Cache-Control '${cc:-missing}' missing no-store"
        elif ! grep -q 'invite-valid' "$BODY" || ! grep -q 'join.js' "$BODY"; then
            log_fail "site /join/<token>: body missing invite-valid or join.js"
        else
            log_pass "site /join/<token>: 200 no-store"
        fi
    fi

    http GET "$SITE_URL/.well-known/assetlinks.json"
    if expect_status "site /.well-known/assetlinks.json" 200; then
        ctype="$(header_value "$HDR" "Content-Type")"
        if ! printf '%s' "$ctype" | tr '[:upper:]' '[:lower:]' | grep -q 'application/json'; then
            log_fail "site /.well-known/assetlinks.json: Content-Type '${ctype:-missing}' not application/json"
        elif ! "$PYTHON_BIN" - "$BODY" "$expected_fp" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as handle:
        d = json.load(handle)
except (OSError, ValueError):
    sys.exit(1)

want_fp = sys.argv[2]
if not isinstance(d, list) or len(d) < 1 or not isinstance(d[0], dict):
    sys.exit(1)
rel = d[0].get("relation") or []
target = d[0].get("target") or {}
fps = target.get("sha256_cert_fingerprints") or []
if "delegate_permission/common.handle_all_urls" not in rel:
    sys.exit(1)
if target.get("package_name") != "com.nexachat":
    sys.exit(1)
if not fps or fps[0] != want_fp:
    sys.exit(1)
PY
        then
            log_fail "site /.well-known/assetlinks.json: invalid package_name or release SHA-256 fingerprint"
        else
            log_pass "site /.well-known/assetlinks.json: 200 valid com.nexachat release fingerprint"
        fi
    fi
}

# ---------------------------------------------------------------------------
# --write flow
# ---------------------------------------------------------------------------

declare -A ACC_PHONE=([subject]="" [observer]="")
declare -A ACC_PASS=([subject]="" [observer]="")
declare -A ACC_TOKEN=([subject]="" [observer]="")
declare -A ACC_ID=([subject]="" [observer]="")
declare -A ACC_DELETED=([subject]=false [observer]=false)
AVATAR_URL=""
AVATAR_FILE=""

new_test_phone() {
    printf '+84908%06d' "$(( (RANDOM * 32768 + RANDOM) % 1000000 ))"
}

new_test_password() {
    printf 'Nx7@%s-%sZq' "$(date +%s)" "$RANDOM"
}

# register_account subject|observer display-name
register_account() {
    local who="$1"
    local display="$2"
    local attempt phone password
    for attempt in 1 2 3 4 5; do
        phone="$(new_test_phone)"
        if [ "$who" = "observer" ] && [ "$phone" = "${ACC_PHONE[subject]}" ]; then
            continue
        fi
        password="$(new_test_password)"
        printf '{"phone":"%s","password":"%s","displayName":"%s"}' \
            "$phone" "$password" "$display" > "$WORK_DIR/register.json"
        http POST "$BASE_URL/api/v1/auth/register" \
            -H "Content-Type: application/json" \
            --data-binary "@$(curl_path "$WORK_DIR/register.json")"
        if [ "$HTTP_RC" -ne 0 ]; then
            log_fail "write: register request failed (curl exit $HTTP_RC: $(curl_error_snippet))"
            return 1
        fi
        if [ "$HTTP_CODE" = "201" ]; then
            ACC_PHONE[$who]="$phone"
            ACC_PASS[$who]="$password"
            ACC_TOKEN[$who]="$(json_get "$BODY" accessToken)"
            ACC_ID[$who]="$(json_get "$BODY" user.id)"
            if [ -z "${ACC_TOKEN[$who]}" ] || [ -z "${ACC_ID[$who]}" ]; then
                log_fail "write: register 201 without accessToken/user.id"
                return 1
            fi
            log_pass "write: registered disposable $who account $phone (reserved +84908 range)"
            return 0
        fi
        if [ "$HTTP_CODE" = "409" ] && [ "$(json_get "$BODY" error.code)" = "PHONE_EXISTS" ]; then
            continue
        fi
        log_fail "write: register returned HTTP ${HTTP_CODE:-none}, expected 201 ($(body_snippet))"
        return 1
    done
    log_fail "write: no free disposable phone after 5 attempts"
    return 1
}

login_account() {
    local who="$1"
    printf '{"phone":"%s","password":"%s"}' \
        "${ACC_PHONE[$who]}" "${ACC_PASS[$who]}" > "$WORK_DIR/login.json"
    http POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        --data-binary "@$(curl_path "$WORK_DIR/login.json")"
    expect_status "write: login $who" 200 || return 1
    ACC_TOKEN[$who]="$(json_get "$BODY" accessToken)"
    if [ -z "${ACC_TOKEN[$who]}" ]; then
        log_fail "write: login $who returned no accessToken"
        return 1
    fi
    log_pass "write: logged in disposable $who account"
}

upload_avatar() {
    local who="$1"
    local png_b64
    png_b64='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='
    AVATAR_FILE="$WORK_DIR/avatar.png"
    if ! printf '%s' "$png_b64" | base64 -d > "$AVATAR_FILE" 2>/dev/null; then
        printf '%s' "$png_b64" | base64 -D > "$AVATAR_FILE"
    fi
    if [ ! -s "$AVATAR_FILE" ]; then
        log_fail "write: could not decode the 1x1 PNG fixture"
        return 1
    fi
    http POST "$BASE_URL/api/v1/users/me/avatar" \
        -H "Authorization: Bearer ${ACC_TOKEN[$who]}" \
        -F "file=@$(curl_path "$AVATAR_FILE");type=image/png"
    expect_status "write: avatar upload" 200 || return 1
    AVATAR_URL="$(json_get "$BODY" avatarUrl)"
    if [ -z "$AVATAR_URL" ]; then
        log_fail "write: avatar upload returned no avatarUrl"
        return 1
    fi
    log_pass "write: avatar uploaded ($AVATAR_URL)"
}

avatar_full_url() {
    case "$AVATAR_URL" in
        /*) printf '%s%s' "$BASE_URL" "$AVATAR_URL" ;;
        "$BASE_URL"/*) printf '%s' "$AVATAR_URL" ;;
        http://*|https://*)
            # Never send a Bearer token to a foreign origin.
            printf ''
            ;;
        *) printf '' ;;
    esac
}

fetch_avatar() {
    local who="$1"
    local url content_type
    url="$(avatar_full_url)"
    if [ -z "$url" ]; then
        log_fail "write: avatarUrl is not a usable URL: '$AVATAR_URL'"
        return 1
    fi
    http GET "$url" -H "Authorization: Bearer ${ACC_TOKEN[$who]}"
    expect_status "write: avatar fetch" 200 || return 1
    content_type="$(header_value "$HDR" "Content-Type")"
    if ! printf '%s' "$content_type" | grep -q '^image/png'; then
        log_fail "write: avatar fetch Content-Type is '${content_type:-missing}', expected image/png"
        return 1
    fi
    if ! cmp -s "$AVATAR_FILE" "$BODY"; then
        log_fail "write: avatar bytes differ from the uploaded fixture"
        return 1
    fi
    log_pass "write: avatar fetched back byte-for-byte as image/png"
}

check_calls_ice_config_auth() {
    local who="$1"
    http GET "$BASE_URL/api/v1/calls/ice-config" -H "Authorization: Bearer ${ACC_TOKEN[$who]}"
    expect_status "write: calls ice-config" 200 || return 1
    if ! "$PYTHON_BIN" - "$BODY" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as handle:
        d = json.load(handle)
except (OSError, ValueError):
    sys.exit(1)

if not isinstance(d, dict):
    sys.exit(1)
if not isinstance(d.get("iceServers"), list):
    sys.exit(1)
ttl = d.get("ttlSeconds")
if not isinstance(ttl, int) or ttl <= 0:
    sys.exit(1)
PY
    then
        log_fail "write: calls ice-config invalid JSON schema ($(body_snippet))"
        return 1
    fi
    log_pass "write: authenticated GET /api/v1/calls/ice-config returned 200"
}

fetch_avatar_range() {
    local who="$1"
    local url accept_ranges content_range
    url="$(avatar_full_url)"
    if [ -z "$url" ]; then
        log_fail "write: avatarUrl is not a usable URL for Range check: '$AVATAR_URL'"
        return 1
    fi
    http GET "$url" -H "Authorization: Bearer ${ACC_TOKEN[$who]}" -H "Range: bytes=0-15"
    expect_status "write: avatar Range 206" 206 || return 1
    accept_ranges="$(header_value "$HDR" "Accept-Ranges")"
    content_range="$(header_value "$HDR" "Content-Range")"
    if ! printf '%s' "$accept_ranges" | tr '[:upper:]' '[:lower:]' | grep -q 'bytes'; then
        log_fail "write: avatar Range 206 missing Accept-Ranges: bytes (got '${accept_ranges:-missing}')"
        return 1
    fi
    case "$content_range" in
        "bytes 0-15/"*) ;;
        *)
            log_fail "write: avatar Range 206 Content-Range '${content_range:-missing}' does not start with 'bytes 0-15/'"
            return 1
            ;;
    esac
    if ! "$PYTHON_BIN" - "$AVATAR_FILE" "$BODY" <<'PY'
import sys

with open(sys.argv[1], "rb") as f1, open(sys.argv[2], "rb") as f2:
    want = f1.read(16)
    got = f2.read()
sys.exit(0 if got == want and len(got) == 16 else 1)
PY
    then
        log_fail "write: avatar Range bytes=0-15 payload mismatch"
        return 1
    fi
    log_pass "write: avatar Range bytes=0-15 returned 206 matching uploaded bytes"
}

check_feed_roundtrip_write() {
    local who="$1"
    local cid
    cid="$("$PYTHON_BIN" -c 'import uuid; print(uuid.uuid4())')"
    printf '{"clientPostId":"%s","body":"prod-smoke ephemeral"}' "$cid" > "$WORK_DIR/feed-post.json"
    http POST "$BASE_URL/api/v1/feed/posts" \
        -H "Authorization: Bearer ${ACC_TOKEN[$who]}" \
        -H "Content-Type: application/json" \
        --data-binary "@$(curl_path "$WORK_DIR/feed-post.json")"
    expect_status "write: feed post create" 201 || return 1
    POST_ID="$(json_get "$BODY" id)"
    if [ -z "$POST_ID" ]; then
        log_fail "write: feed post create 201 without id"
        return 1
    fi
    if [ "$SOCIAL_WEB_ENABLED" = "true" ]; then
        http GET "$BASE_URL/api/social/v1/posts?limit=1" -H "Authorization: Bearer ${ACC_TOKEN[$who]}"
        expect_status "write: social feed read" 200 || return 1
    else
        http GET "$BASE_URL/api/v1/feed/posts?limit=1" -H "Authorization: Bearer ${ACC_TOKEN[$who]}"
        expect_status "write: feed read" 200 || return 1
    fi
    http DELETE "$BASE_URL/api/v1/feed/posts/$POST_ID" -H "Authorization: Bearer ${ACC_TOKEN[$who]}"
    expect_status "write: feed post delete" 204 || return 1
    POST_ID=""
    log_pass "write: ephemeral feed post create/read/delete verified (201/200/204)"
}

delete_account() {
    local who="$1"
    printf '{"password":"%s"}' "${ACC_PASS[$who]}" > "$WORK_DIR/delete.json"
    http DELETE "$BASE_URL/api/v1/users/me" \
        -H "Authorization: Bearer ${ACC_TOKEN[$who]}" \
        -H "Content-Type: application/json" \
        --data-binary "@$(curl_path "$WORK_DIR/delete.json")"
    expect_status "write: delete $who account" 204 || return 1
    ACC_DELETED[$who]=true
    log_pass "write: deleted disposable $who account (204)"
}

check_login_rejected_after_delete() {
    local who="$1"
    local code
    printf '{"phone":"%s","password":"%s"}' \
        "${ACC_PHONE[$who]}" "${ACC_PASS[$who]}" > "$WORK_DIR/login.json"
    http POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        --data-binary "@$(curl_path "$WORK_DIR/login.json")"
    if [ "$HTTP_CODE" != "401" ]; then
        log_fail "write: deleted $who login expected 401, got ${HTTP_CODE:-none} ($(body_snippet))"
        return 1
    fi
    code="$(json_get "$BODY" error.code)"
    if [ "$code" != "INVALID_CREDENTIALS" ]; then
        log_fail "write: deleted $who login expected INVALID_CREDENTIALS, got '${code:-none}'"
        return 1
    fi
    log_pass "write: deleted $who account can no longer log in (401 INVALID_CREDENTIALS)"
}

check_token_rejected_after_delete() {
    local who="$1"
    local code
    http GET "$BASE_URL/api/v1/users/me" -H "Authorization: Bearer ${ACC_TOKEN[$who]}"
    if [ "$HTTP_CODE" != "401" ]; then
        log_fail "write: deleted $who token expected 401, got ${HTTP_CODE:-none} ($(body_snippet))"
        return 1
    fi
    code="$(json_get "$BODY" error.code)"
    if [ "$code" != "TOKEN_INVALID" ]; then
        log_fail "write: deleted $who token expected TOKEN_INVALID, got '${code:-none}'"
        return 1
    fi
    log_pass "write: deleted $who access token is revoked (401 TOKEN_INVALID)"
}

check_avatar_removed() {
    local who="$1"
    local url code attempt rc
    url="$(avatar_full_url)"
    if [ -z "$url" ]; then
        log_fail "write: cannot verify avatar cleanup; avatarUrl is not a same-origin URL: '$AVATAR_URL'"
        return 1
    fi
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        http GET "$url" -H "Authorization: Bearer ${ACC_TOKEN[$who]}"
        rc="$HTTP_RC"
        if [ "$rc" -eq 0 ] && [ "$HTTP_CODE" = "404" ]; then
            code="$(json_get "$BODY" error.code)"
            case "$code" in
                FILE_NOT_FOUND|ATTACHMENT_NOT_FOUND)
                    log_pass "write: avatar bytes removed after deletion (404 $code)"
                    return 0
                    ;;
            esac
        fi
        sleep 1
    done
    if [ "$rc" -ne 0 ]; then
        log_fail "write: avatar fetch after deletion failed (curl exit $rc), cannot prove cleanup"
    elif [ "$HTTP_CODE" = "200" ]; then
        log_fail "write: avatar file still served after account deletion (200, expected 404)"
    else
        log_fail "write: avatar cleanup check expected 404 FILE_NOT_FOUND, got ${HTTP_CODE:-none} ($(body_snippet))"
    fi
    return 1
}

cleanup_write_accounts() {
    if [ -n "$POST_ID" ] && [ -n "${ACC_TOKEN[subject]}" ]; then
        http DELETE "$BASE_URL/api/v1/feed/posts/$POST_ID" \
            -H "Authorization: Bearer ${ACC_TOKEN[subject]}" >/dev/null 2>&1 || true
        POST_ID=""
    fi
    local who
    for who in subject observer; do
        if [ "${ACC_DELETED[$who]}" = true ]; then
            continue
        fi
        if [ -z "${ACC_TOKEN[$who]}" ] || [ -z "${ACC_PASS[$who]}" ]; then
            continue
        fi
        printf '{"password":"%s"}' "${ACC_PASS[$who]}" > "$WORK_DIR/delete.json"
        http DELETE "$BASE_URL/api/v1/users/me" \
            -H "Authorization: Bearer ${ACC_TOKEN[$who]}" \
            -H "Content-Type: application/json" \
            --data-binary "@$(curl_path "$WORK_DIR/delete.json")"
        if [ "$HTTP_CODE" = "204" ]; then
            ACC_DELETED[$who]=true
            log_info "cleanup: deleted leftover $who account"
        else
            log_fail "cleanup: could not delete leftover $who account (HTTP ${HTTP_CODE:-none}); remove it manually"
        fi
    done
}

run_write_flow() {
    log_info "Write flow: disposable accounts, avatar round-trip, delete and verify"
    if ! register_account subject "Prod Smoke Subject"; then
        cleanup_write_accounts
        return 1
    fi
    if ! login_account subject; then
        cleanup_write_accounts
        return 1
    fi
    if ! register_account observer "Prod Smoke Observer"; then
        cleanup_write_accounts
        return 1
    fi
    if ! login_account observer; then
        cleanup_write_accounts
        return 1
    fi
    if ! upload_avatar subject; then
        cleanup_write_accounts
        return 1
    fi
    if ! fetch_avatar subject; then
        cleanup_write_accounts
        return 1
    fi
    if ! check_calls_ice_config_auth subject; then
        cleanup_write_accounts
        return 1
    fi
    if ! fetch_avatar_range subject; then
        cleanup_write_accounts
        return 1
    fi
    if ! check_feed_roundtrip_write subject; then
        cleanup_write_accounts
        return 1
    fi
    if ! delete_account subject; then
        cleanup_write_accounts
        return 1
    fi
    if ! check_login_rejected_after_delete subject; then
        cleanup_write_accounts
        return 1
    fi
    if ! check_token_rejected_after_delete subject; then
        cleanup_write_accounts
        return 1
    fi
    if ! check_avatar_removed observer; then
        cleanup_write_accounts
        return 1
    fi
    if ! delete_account observer; then
        cleanup_write_accounts
        return 1
    fi
    if ! check_login_rejected_after_delete observer; then
        cleanup_write_accounts
        return 1
    fi
    log_pass "write: account, avatar and session cleanup verified end to end"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

log_info "NexaChat production smoke: $BASE_URL"

if ! wait_for_health; then
    if [ "$FAILED" -eq 0 ]; then
        log_fail "health: not reachable"
    fi
    printf 'prod smoke: %s check(s) failed\n' "$FAILED" >&2
    exit 1
fi

check_transport_security
check_cors
check_unauthenticated_rejection
check_otp_request
check_websocket_handshake
check_social_config
check_calls_ice_config_unauth

if [ -n "$SITE_URL" ]; then
    check_site_endpoints
fi

if [ "$DO_WRITE" = true ]; then
    run_write_flow || true
else
    log_info "Write flow skipped (pass --write to exercise accounts, storage and deletion)"
fi

if [ "$FAILED" -ne 0 ]; then
    printf 'prod smoke: %s check(s) failed\n' "$FAILED" >&2
    exit 1
fi
log_pass "prod smoke: all checks passed"
exit 0
