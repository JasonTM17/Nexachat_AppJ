#!/usr/bin/env sh
set -eu
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT/server"
exec mvn -Dtest=GoldSetEvaluationTest test
