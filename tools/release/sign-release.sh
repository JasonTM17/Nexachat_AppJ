#!/usr/bin/env bash
# tools/release/sign-release.sh — assemble the release APK and sign it with the LOCAL
# upload keystore. Run from anywhere: tools/release/sign-release.sh [version-tag] [options]
#
# Inputs
#   release/keystore/signing.properties   storeFile, keyAlias,
#                                         keystore.storePassword, keyPassword
#   RELEASE_SIGNING_PROPERTIES            optional alternate properties path
#   $ANDROID_HOME / $ANDROID_SDK_ROOT     must identify the local Android SDK
#   $JAVA_HOME                            falls back to `java` on PATH
#   -PapiBaseUrl                          overrides local.properties and the Gradle default
#
# Outputs (release/ is gitignored — never committed, never shipped)
#   release/nexachat-<tag>-unsigned.apk   verbatim assembleRelease output
#   release/nexachat-<tag>.apk            zipalign'ed, then v1+v2 signed
#
# Requires: Git Bash (or any POSIX shell), JDK 21+, Android SDK build-tools.
set -euo pipefail

say()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

TOOLS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "$TOOLS_DIR/release-url-guard.sh"
# shellcheck source=/dev/null
source "$TOOLS_DIR/release-version.sh"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    cat <<'USAGE'
Usage: tools/release/sign-release.sh [version-tag] [-PapiBaseUrl=<url>] [--allow-http-smoke]

Assemble, align, sign, and verify a release APK using signing properties.
The version tag must be MAJOR.MINOR.PATCH (optional leading v; default: v1.0.0).
Set RELEASE_SIGNING_PROPERTIES to read a properties file outside the repo.
Signed releases require an HTTPS API_BASE_URL. The explicit --allow-http-smoke
flag permits HTTP only for a local smoke build.
Requires ANDROID_HOME or ANDROID_SDK_ROOT, JDK 21+, and a configured keystore.
PowerShell twin: tools/release/sign-release.ps1.
USAGE
    exit 0
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

VERSION_TAG="v1.0.0"
VERSION_TAG_SET=false
API_BASE_URL_SET=false
API_BASE_URL_OVERRIDE=''
ALLOW_HTTP_SMOKE=false
while (($#)); do
    case "$1" in
        --allow-http-smoke)
            ALLOW_HTTP_SMOKE=true
            ;;
        -PapiBaseUrl=*)
            API_BASE_URL_SET=true
            API_BASE_URL_OVERRIDE="${1#*=}"
            ;;
        -PapiBaseUrl)
            (($# >= 2)) || fail "-PapiBaseUrl requires a URL value."
            shift
            API_BASE_URL_SET=true
            API_BASE_URL_OVERRIDE="$1"
            ;;
        -*)
            fail "unknown option (use --help)."
            ;;
        *)
            [[ "$VERSION_TAG_SET" == false ]] || fail "expected only one version tag (use --help)."
            VERSION_TAG="$1"
            VERSION_TAG_SET=true
            ;;
    esac
    shift
done

VERSION_INFO="$(derive_release_version "$VERSION_TAG")" \
    || fail "invalid release version tag."
IFS=' ' read -r VERSION_NAME VERSION_CODE <<< "$VERSION_INFO"

API_BASE_URL_EFFECTIVE="$(resolve_effective_api_base_url \
    "$API_BASE_URL_SET" "$API_BASE_URL_OVERRIDE" "$REPO_ROOT/android/local.properties")"
if ! guard_release_api_base_url "$API_BASE_URL_EFFECTIVE" "$ALLOW_HTTP_SMOKE"; then
    exit 1
fi
GRADLE_ARGS=(
    -PapiBaseUrl="$API_BASE_URL_EFFECTIVE"
    -PreleaseVersionName="$VERSION_NAME"
    -PreleaseVersionCode="$VERSION_CODE"
    -PskipSigning
)

OUT_DIR="release"
PROPS_INPUT="${RELEASE_SIGNING_PROPERTIES:-release/keystore/signing.properties}"
GRADLE_APK="android/app/build/outputs/apk/release/app-release-unsigned.apk"
UNSIGNED_OUT="$OUT_DIR/nexachat-${VERSION_TAG}-unsigned.apk"
SIGNED_OUT="$OUT_DIR/nexachat-${VERSION_TAG}.apk"

# Convert Windows paths (D:\x or D:/x) to POSIX (/d/x) for bash use.
to_unix() {
    local p="$1"
    if [[ "$p" =~ ^[A-Za-z]:[\\/] ]]; then
        if command -v cygpath >/dev/null 2>&1; then
            cygpath -u "$p"; return
        fi
        local drive="${p:0:1}" rest="${p:2}"
        echo "/${drive,,}${rest//\\//}"
    else
        echo "$p"
    fi
}

prop() { grep -E "^$2=" "$1" | head -n1 | cut -d= -f2- | tr -d '\r' ; }

PROPS="$(to_unix "$PROPS_INPUT")"

# ---- 0. Resolve tools --------------------------------------------------------
SDK_VALUE="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$SDK_VALUE" ]] || fail "ANDROID_HOME or ANDROID_SDK_ROOT must point to the Android SDK."
SDK="$(to_unix "$SDK_VALUE")"
[[ -d "$SDK/build-tools" ]] || fail "Android SDK build-tools were not found (set ANDROID_HOME)."
# Newest build-tools version wins (34.0.0 < 35.0.0 < 36.1.0).
BT="$(printf '%s\n' "$SDK"/build-tools/*/ | sort -V | tail -n1)"
[[ -n "$BT" && -f "$BT/lib/apksigner.jar" ]] || fail "apksigner.jar was not found in the installed build-tools."
[[ -f "$BT/zipalign.exe" || -f "$BT/zipalign" ]] || fail "zipalign was not found in the installed build-tools."
# Windows build-tools ship zipalign.exe; POSIX images ship a bare zipalign.
if [[ -f "$BT/zipalign.exe" ]]; then ZIPALIGN="$BT/zipalign.exe"; else ZIPALIGN="$BT/zipalign"; fi

if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA_BIN="$(to_unix "$JAVA_HOME")/bin/java"
else
    JAVA_BIN="java"
fi
command -v "$JAVA_BIN" >/dev/null 2>&1 || JAVA_BIN="${JAVA_BIN}.exe"
command -v "$JAVA_BIN" >/dev/null 2>&1 || fail "java not found (set JAVA_HOME to JDK 21+)."

[[ -f "$PROPS" ]] || fail "Signing properties file not found; see tools/release/README.md §1."
STORE_FILE_VALUE="$(prop "$PROPS" storeFile || true)"
KEY_ALIAS="$(prop "$PROPS" keyAlias || true)"
STORE_PASS="$(prop "$PROPS" keystore.storePassword || true)"
KEY_PASS="$(prop "$PROPS" keyPassword || true)"
STORE_FILE="$(to_unix "$STORE_FILE_VALUE")"
[[ -n "$STORE_FILE_VALUE" && -f "$STORE_FILE" ]] || fail "Configured keystore file not found."
[[ -n "$KEY_ALIAS" && -n "$STORE_PASS" && -n "$KEY_PASS" ]] || fail "Signing properties are incomplete."

say "build-tools : newest installed Android SDK build-tools"
say "java        : configured JDK runtime"
say "keystore    : configured (alias $KEY_ALIAS)"

# ---- 1. Assemble release APK ------------------------------------------------
say "assembleRelease (Gradle)…"
(cd android && ./gradlew assembleRelease "${GRADLE_ARGS[@]}" --no-parallel --max-workers=2 --console=plain)
if [[ ! -f "$GRADLE_APK" && -f "android/app/build/outputs/apk/release/app-release.apk" ]]; then
    GRADLE_APK="android/app/build/outputs/apk/release/app-release.apk"
fi
[[ -f "$GRADLE_APK" ]] || fail "Gradle finished but $GRADLE_APK is missing."
mkdir -p "$OUT_DIR"
cp -f "$GRADLE_APK" "$UNSIGNED_OUT"
say "unsigned APK → $UNSIGNED_OUT ($(du -h "$UNSIGNED_OUT" | cut -f1))"

# ---- 2. zipalign (must happen BEFORE signing for v2 signatures) --------------
ALIGNED="$(mktemp --suffix=.apk)"
trap 'rm -f "$ALIGNED"' EXIT
"$ZIPALIGN" -f -p -v 4 "$UNSIGNED_OUT" "$ALIGNED" >/dev/null
"$ZIPALIGN" -c -v 4 "$ALIGNED" | tail -n1

# ---- 3. apksigner sign (v2/v3 schemes auto-selected from minSdk=26; ----------
#      v4 disabled — the .idsig sidecar is only needed for incremental installs)
RELEASE_STORE_PASSWORD="$STORE_PASS" \
RELEASE_KEY_PASSWORD="$KEY_PASS" \
"$JAVA_BIN" -jar "$BT/lib/apksigner.jar" sign \
    --ks "$STORE_FILE_VALUE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:RELEASE_STORE_PASSWORD \
    --key-pass env:RELEASE_KEY_PASSWORD \
    --v4-signing-enabled false \
    --out "$SIGNED_OUT" \
    "$ALIGNED" \
    </dev/null

# ---- 4. Verify ---------------------------------------------------------------
say "apksigner verify --print-certs"
"$JAVA_BIN" -jar "$BT/lib/apksigner.jar" verify --print-certs "$SIGNED_OUT"

say "DONE"
echo "  signed   : $SIGNED_OUT ($(du -h "$SIGNED_OUT" | cut -f1))"
echo "  unsigned : $UNSIGNED_OUT ($(du -h "$UNSIGNED_OUT" | cut -f1))"
