#!/usr/bin/env bash
# tools/sign-release.sh — assemble the release APK and sign it with the LOCAL
# dev upload keystore. Run from anywhere:  tools/sign-release.sh [version-tag]
#
# Inputs
#   release/keystore/signing.properties   storeFile, keyAlias,
#                                         keystore.storePassword, keyPassword
#   $ANDROID_HOME / $ANDROID_SDK_ROOT     falls back to D:\Android\Sdk
#   $JAVA_HOME                            falls back to `java` on PATH
#
# Outputs (release/ is gitignored — never committed, never shipped)
#   release/nexachat-<tag>-unsigned.apk   verbatim assembleRelease output
#   release/nexachat-<tag>.apk            zipalign'ed, then v1+v2 signed
#
# Requires: Git Bash (or any POSIX shell), JDK 17+, Android SDK build-tools.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

VERSION_TAG="${1:-v1.0.0}"
OUT_DIR="release"
PROPS="release/keystore/signing.properties"
GRADLE_APK="android/app/build/outputs/apk/release/app-release-unsigned.apk"
UNSIGNED_OUT="$OUT_DIR/nexachat-${VERSION_TAG}-unsigned.apk"
SIGNED_OUT="$OUT_DIR/nexachat-${VERSION_TAG}.apk"

say()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# Convert Windows paths (D:\x or D:/x) to POSIX (/d/x) for bash use.
to_unix() {
    local p="$1"
    if [[ "$p" =~ ^[A-Za-z]:[\\/] ]]; then
        if command -v cygpath >/dev/null 2>&1; then
            cygpath -u "$p"; return
        fi
        local drive="${p:0:1}"
        echo "/${drive,,}${p:2}" | tr '\\' '/'
    else
        echo "$p"
    fi
}

prop() { grep -E "^$2=" "$1" | head -n1 | cut -d= -f2- | tr -d '\r' ; }

# ---- 0. Resolve tools --------------------------------------------------------
SDK="$(to_unix "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-D:\\Android\\Sdk}}")"
[[ -d "$SDK/build-tools" ]] || fail "Android SDK build-tools not found under '$SDK' (set ANDROID_HOME)."
# Newest build-tools version wins (34.0.0 < 35.0.0 < 36.1.0).
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -n1)"
[[ -n "$BT" && -f "$BT/lib/apksigner.jar" ]] || fail "apksigner.jar not found in newest build-tools dir."
[[ -f "$BT/zipalign.exe" || -f "$BT/zipalign" ]] || fail "zipalign not found in $BT."
ZIPALIGN="$BT/zipalign"

if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA_BIN="$(to_unix "$JAVA_HOME")/bin/java"
else
    JAVA_BIN="java"
fi
command -v "$JAVA_BIN" >/dev/null 2>&1 || JAVA_BIN="${JAVA_BIN}.exe"
command -v "$JAVA_BIN" >/dev/null 2>&1 || fail "java not found (set JAVA_HOME to JDK 17+)."

[[ -f "$PROPS" ]] || fail "$PROPS missing — see tools/README-release.md §1."
STORE_FILE="$(prop "$PROPS" storeFile)"
KEY_ALIAS="$(prop "$PROPS" keyAlias)"
STORE_PASS="$(prop "$PROPS" keystore.storePassword)"
KEY_PASS="$(prop "$PROPS" keyPassword)"
[[ -f "$STORE_FILE" ]] || fail "Keystore '$STORE_FILE' not found — generate it first (tools/README-release.md §1)."
[[ -n "$KEY_ALIAS" && -n "$STORE_PASS" && -n "$KEY_PASS" ]] || fail "Incomplete $PROPS."

say "build-tools : $BT"
say "java        : $JAVA_BIN"
say "keystore    : $STORE_FILE (alias $KEY_ALIAS)"

# ---- 1. Assemble unsigned release APK ---------------------------------------
say "assembleRelease (Gradle)…"
(cd android && ./gradlew assembleRelease --console=plain)
[[ -f "$GRADLE_APK" ]] || fail "Gradle finished but $GRADLE_APK is missing."
mkdir -p "$OUT_DIR"
cp -f "$GRADLE_APK" "$UNSIGNED_OUT"
say "unsigned APK → $UNSIGNED_OUT ($(du -h "$UNSIGNED_OUT" | cut -f1))"

# ---- 2. zipalign (must happen BEFORE signing for v2 signatures) --------------
ALIGNED="$(mktemp -u --suffix=.apk)"
trap 'rm -f "$ALIGNED"' EXIT
"$ZIPALIGN" -f -p -v 4 "$UNSIGNED_OUT" "$ALIGNED" >/dev/null
"$ZIPALIGN" -c -v 4 "$ALIGNED" | tail -n1

# ---- 3. apksigner sign (v2/v3 schemes auto-selected from minSdk=26; ----------
#      v4 disabled — the .idsig sidecar is only needed for incremental installs)
"$JAVA_BIN" -jar "$BT/lib/apksigner.jar" sign \
    --ks "$STORE_FILE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$STORE_PASS" \
    --key-pass "pass:$KEY_PASS" \
    --v4-signing-enabled false \
    --out "$SIGNED_OUT" \
    "$ALIGNED"

# ---- 4. Verify ---------------------------------------------------------------
say "apksigner verify --print-certs"
"$JAVA_BIN" -jar "$BT/lib/apksigner.jar" verify --print-certs "$SIGNED_OUT"

say "DONE"
echo "  signed   : $SIGNED_OUT ($(du -h "$SIGNED_OUT" | cut -f1))"
echo "  unsigned : $UNSIGNED_OUT ($(du -h "$UNSIGNED_OUT" | cut -f1))"
echo "Next: tools/package-submission.sh"
