#!/usr/bin/env bash
# Test suite for site/vercel.json ignoreCommand.
# Validates Vercel build-skip behavior across:
# 1. Unset or empty VERCEL_GIT_PREVIOUS_SHA (must exit 1 -> build)
# 2. Missing previous commit object in a shallow clone (must exit 1 -> build, never 128)
# 3. Changes in site/ directory between commits (must exit 1 -> build)
# 4. Changes outside site/ directory with site/ unchanged (must exit 0 -> skip)
# 5. Non-existent / corrupt commit SHA (must exit 1 -> build, never 128)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# This script is one level below the repository's tools directory.
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
VERCEL_JSON="$REPO_DIR/site/vercel.json"

if [[ ! -f "$VERCEL_JSON" ]]; then
    printf 'FAIL: vercel.json not found at %s\n' "$VERCEL_JSON" >&2
    exit 1
fi

# Extract ignoreCommand from site/vercel.json
if command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN="python3"
elif command -v python >/dev/null 2>&1; then
    PYTHON_BIN="python"
else
    printf 'FAIL: python is required to parse vercel.json\n' >&2
    exit 1
fi

IGNORE_CMD="$("$PYTHON_BIN" -c 'import json, sys; print(json.load(open(sys.argv[1]))["ignoreCommand"])' "$VERCEL_JSON")"
if [[ -z "$IGNORE_CMD" ]]; then
    printf 'FAIL: ignoreCommand is empty in %s\n' "$VERCEL_JSON" >&2
    exit 1
fi

printf 'Testing ignoreCommand:\n  %s\n\n' "$IGNORE_CMD"

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

pass() {
    printf 'PASS: %s\n' "$*"
}

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# Helper to execute the ignoreCommand in a given directory with given environment variables
# Returns the exit code
run_cmd() {
    local target_dir="$1"
    local prev_sha="$2"
    local curr_sha="$3"
    local code=0

    (
        cd "$target_dir"
        if [[ -n "$prev_sha" ]]; then
            export VERCEL_GIT_PREVIOUS_SHA="$prev_sha"
        else
            unset VERCEL_GIT_PREVIOUS_SHA
        fi
        if [[ -n "$curr_sha" ]]; then
            export VERCEL_GIT_COMMIT_SHA="$curr_sha"
        else
            unset VERCEL_GIT_COMMIT_SHA
        fi
        eval "$IGNORE_CMD"
    ) || code=$?

    return "$code"
}

# ---------------------------------------------------------------------------
# Test 1: No previous SHA (empty or unset)
# ---------------------------------------------------------------------------
code=0
run_cmd "$REPO_DIR/site" "" "HEAD" || code=$?
if [[ "$code" -ne 1 ]]; then
    fail "Test 1 (empty VERCEL_GIT_PREVIOUS_SHA): expected exit code 1 (build), got $code"
fi
pass "Test 1: empty VERCEL_GIT_PREVIOUS_SHA exits 1 (build)"

code=0
run_cmd "$REPO_DIR/site" "" "" || code=$?
if [[ "$code" -ne 1 ]]; then
    fail "Test 1b (both SHAs empty): expected exit code 1 (build), got $code"
fi
pass "Test 1b: unset SHAs exit 1 (build)"

# ---------------------------------------------------------------------------
# Setup synthetic git repository for deterministic commit diff testing
# ---------------------------------------------------------------------------
FIXTURE_REPO="$WORK_DIR/fixture_repo"
git init -q "$FIXTURE_REPO"
(
    cd "$FIXTURE_REPO"
    git config core.autocrlf false
    git config user.name "NexaChat Test"
    git config user.email "test@nexachat.local"
    mkdir -p site server

    # Commit 1: initial baseline
    echo "<h1>Site v1</h1>" > site/index.html
    echo "class ServerV1 {}" > server/Server.java
    git add .
    git commit -qm "Commit 1: baseline"

    # Commit 2: change in site/
    echo "<h1>Site v2</h1>" > site/index.html
    git add site/index.html
    git commit -qm "Commit 2: site modified"

    # Commit 3: change only in server/ (site unchanged)
    echo "class ServerV2 {}" > server/Server.java
    git add server/Server.java
    git commit -qm "Commit 3: server only modified"
)

SHA1="$(git -C "$FIXTURE_REPO" rev-parse HEAD~2)"
SHA2="$(git -C "$FIXTURE_REPO" rev-parse HEAD~1)"
SHA3="$(git -C "$FIXTURE_REPO" rev-parse HEAD)"

# ---------------------------------------------------------------------------
# Test 2: Changed site/ directory
# ---------------------------------------------------------------------------
code=0
run_cmd "$FIXTURE_REPO/site" "$SHA1" "$SHA2" || code=$?
if [[ "$code" -ne 1 ]]; then
    fail "Test 2 (changed site/): expected exit code 1 (build), got $code"
fi
pass "Test 2: changed site/ exits 1 (build)"

# ---------------------------------------------------------------------------
# Test 3: Unchanged site/ directory (only server/ changed)
# ---------------------------------------------------------------------------
code=0
run_cmd "$FIXTURE_REPO/site" "$SHA2" "$SHA3" || code=$?
if [[ "$code" -ne 0 ]]; then
    fail "Test 3 (unchanged site/): expected exit code 0 (skip), got $code"
fi
pass "Test 3: unchanged site/ exits 0 (skip)"

# ---------------------------------------------------------------------------
# Test 4: Missing object in shallow clone (made with git clone --depth 1 file://...)
# ---------------------------------------------------------------------------
SHALLOW_REPO="$WORK_DIR/shallow_repo"
git clone --depth 1 -q "file://$FIXTURE_REPO" "$SHALLOW_REPO"

# In the shallow repo, only SHA3 exists. SHA1 and SHA2 are missing objects.
code=0
run_cmd "$SHALLOW_REPO/site" "$SHA2" "$SHA3" || code=$?
if [[ "$code" -eq 128 ]]; then
    fail "Test 4 (shallow clone with missing previous SHA): git diff crashed with exit code 128 (bad object). Vercel requires exit 1 to build."
elif [[ "$code" -ne 1 ]]; then
    fail "Test 4 (shallow clone with missing previous SHA): expected exit code 1 (build), got $code"
fi
pass "Test 4: shallow clone with missing previous SHA safely exits 1 (build, no exit 128 crash)"

# ---------------------------------------------------------------------------
# Test 5: Synthetic corrupt / missing 40-character SHA
# ---------------------------------------------------------------------------
FAKE_SHA="3a98841000000000000000000000000000000000"
code=0
run_cmd "$REPO_DIR/site" "$FAKE_SHA" "HEAD" || code=$?
if [[ "$code" -eq 128 ]]; then
    fail "Test 5 (corrupt 40-char SHA): git diff crashed with exit code 128. Vercel requires exit 1 to build."
elif [[ "$code" -ne 1 ]]; then
    fail "Test 5 (corrupt 40-char SHA): expected exit code 1 (build), got $code"
fi
pass "Test 5: non-existent commit SHA exits 1 (build, no exit 128 crash)"

printf '\nALL IGNORE COMMAND TESTS PASSED!\n'
