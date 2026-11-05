#!/usr/bin/env bash
# NexaChat STOMP end-to-end test (message lifecycle + presence + rate limit).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python "$here/stomp_e2e.py" "$@"
