#!/usr/bin/env bash
set -euo pipefail

TOOLS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SIGNER="$TOOLS_DIR/sign-release.sh"

if ! grep -q 'guard_release_api_base_url' "$SIGNER"; then
    printf 'FAIL: sign-release.sh has no API_BASE_URL guard before signing.\n' >&2
    exit 1
fi

# shellcheck source=release-url-guard.sh
source "$TOOLS_DIR/release-url-guard.sh"

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
PROPERTIES="$WORK_DIR/local.properties"

run_signer_without_android_sdk() {
    (
        unset ANDROID_HOME ANDROID_SDK_ROOT
        bash "$SIGNER" "$@"
    )
}

printf 'sdk.dir=/sdk\nAPI_BASE_URL=https://release.example.test:8443/api/v1?view=smoke\n' > "$PROPERTIES"

resolved="$(resolve_effective_api_base_url false '' "$PROPERTIES")"
[[ "$resolved" == 'https://release.example.test:8443/api/v1?view=smoke' ]] \
    || fail 'local.properties did not supply API_BASE_URL'
output="$(guard_release_api_base_url "$resolved" false 2>&1)" \
    || fail 'HTTPS API_BASE_URL was rejected'
[[ "$output" == *'scheme=https host=release.example.test'* ]] \
    || fail 'HTTPS summary did not contain only its scheme and host'
[[ "$output" != *'/api/v1'* && "$output" != *'view=smoke'* ]] \
    || fail 'HTTPS summary disclosed the URL path or query'

resolved="$(resolve_effective_api_base_url true 'http://localhost:8080/smoke?view=local' "$PROPERTIES")"
[[ "$resolved" == 'http://localhost:8080/smoke?view=local' ]] \
    || fail 'explicit Gradle property did not override local.properties'
if output="$(guard_release_api_base_url "$resolved" false 2>&1)"; then
    fail 'HTTP API_BASE_URL was accepted without the local-smoke override'
fi
[[ "$output" == *'scheme=http host=localhost'* ]] \
    || fail 'HTTP summary did not contain only its scheme and host'
[[ "$output" != *'/smoke'* && "$output" != *'view=local'* ]] \
    || fail 'HTTP summary disclosed the URL path or query'

output="$(guard_release_api_base_url "$resolved" true 2>&1)" \
    || fail '--allow-http-smoke did not permit the local HTTP URL'
[[ "$output" == *'scheme=http host=localhost'* ]] \
    || fail 'local-smoke summary did not contain only its scheme and host'

resolved="$(resolve_effective_api_base_url false '' "$WORK_DIR/missing.properties")"
[[ "$resolved" == 'http://10.0.2.2:8080' ]] \
    || fail 'the Gradle default API_BASE_URL was not selected'
if output="$(guard_release_api_base_url "$resolved" false 2>&1)"; then
    fail 'the insecure Gradle default was accepted for signing'
fi

if output="$(run_signer_without_android_sdk -PapiBaseUrl=http://localhost:8080/api/v1 2>&1)"; then
    fail 'the signing script accepted HTTP without its local-smoke override'
fi
[[ "$output" == *'scheme=http host=localhost'* ]] \
    || fail 'the signing script did not report the HTTP scheme and host'
[[ "$output" != *'/api/v1'* && "$output" != *'assembleRelease (Gradle)'* ]] \
    || fail 'the HTTP guard disclosed URL details or reached Gradle'

if output="$(run_signer_without_android_sdk -PapiBaseUrl=https://release.example.test:8443/api/v1 2>&1)"; then
    fail 'the signing script unexpectedly passed its missing-SDK precondition'
fi
[[ "$output" == *'scheme=https host=release.example.test'* ]] \
    || fail 'the signing script did not accept the HTTPS URL guard'
[[ "$output" == *'ANDROID_HOME or ANDROID_SDK_ROOT'* ]] \
    || fail 'the HTTPS guard did not proceed to the Android SDK precondition'
[[ "$output" != *'/api/v1'* && "$output" != *'assembleRelease (Gradle)'* ]] \
    || fail 'the HTTPS guard disclosed URL details or reached Gradle'

printf 'PASS: API_BASE_URL precedence, HTTPS requirement, local-smoke override, and redacted summary.\n'
