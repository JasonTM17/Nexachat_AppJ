#!/usr/bin/env bash
# Manual, opt-in provider check. Runs ONE call through the real adapter path
# (LlmClient + PromptBuilder + LlmOutputValidator) on the fixed synthetic
# Vietnamese conversation in LiveLlmSmoke. The gitignored .env is loaded into
# the process environment and never printed; output is limited to status,
# latency, token counts, the validation verdict and per-section item counts.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
env_file="$repo_root/.env"
if [ ! -f "$env_file" ]; then
    echo 'configuration unavailable' >&2
    exit 1
fi

while IFS= read -r line; do
    case "$line" in
        NEXACHAT_SUMMARY_LLM_API_KEY=*) export NEXACHAT_SUMMARY_LLM_API_KEY="${line#*=}" ;;
        NEXACHAT_SUMMARY_LLM_BASE_URL=*) export NEXACHAT_SUMMARY_LLM_BASE_URL="${line#*=}" ;;
        NEXACHAT_SUMMARY_LLM_MODEL=*) export NEXACHAT_SUMMARY_LLM_MODEL="${line#*=}" ;;
        NEXACHAT_SUMMARY_LLM_ENABLED=*) export NEXACHAT_SUMMARY_LLM_ENABLED="${line#*=}" ;;
    esac
done < "$env_file"

export RUN_LIVE_LLM=1
cd "$repo_root/server"
mvn -q -Dtest=LiveLlmSmoke -DRUN_LIVE_LLM=1 test
