#!/usr/bin/env bash
# NexaChat API smoke test. jq is not installed on the reference machine, so the
# Python stdlib implementation is the primary path (see README.md).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python "$here/smoke_api.py" "$@"
