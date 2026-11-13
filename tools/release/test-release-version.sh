#!/usr/bin/env bash
set -euo pipefail

TOOLS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "$TOOLS_DIR/release-version.sh"

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

expect_version() {
    local tag="$1" expected_name="$2" expected_code="$3" actual
    actual="$(derive_release_version "$tag")" || fail "valid version tag was rejected: $tag"
    [[ "$actual" == "$expected_name $expected_code" ]] \
        || fail "unexpected derivation for $tag"
}

expect_invalid() {
    local tag="$1"
    if derive_release_version "$tag" >/dev/null 2>&1; then
        fail "invalid version tag was accepted: $tag"
    fi
}

expect_version v1.0.0 1.0.0 1000000
expect_version 1.2.3 1.2.3 1002003
expect_version v0.1.0 0.1.0 1000
expect_version v2100.0.0 2100.0.0 2100000000

for invalid in '' v0.0.0 v01.2.3 1.1000.0 1.0.1000 2100.0.1 v1.2.3-rc.1 latest; do
    expect_invalid "$invalid"
done

if output="$(bash "$TOOLS_DIR/sign-release.sh" latest 2>&1)"; then
    fail 'sign-release.sh accepted a non-semantic version tag'
fi
[[ "$output" == *'invalid release version tag'* ]] \
    || fail 'sign-release.sh did not reject an invalid tag before tool resolution'
[[ "$output" != *'ANDROID_HOME'* ]] \
    || fail 'sign-release.sh resolved the Android SDK before validating the version tag'

printf 'PASS: semantic version tags derive stable Android versionName/versionCode values and reject invalid or out-of-range inputs.\n'
