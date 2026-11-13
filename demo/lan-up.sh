#!/usr/bin/env bash
# ============================================================================
# NexaChat LAN demo bootstrap — PRIMARY script (Git Bash on Windows, also works
# on macOS/Linux). PowerShell fallback: demo/lan-up.ps1
#
# What it does:
#   1. Detects this machine's LAN IP.
#   2. Starts `docker compose up -d db app` (or db only, if the app image has
#      not been built yet).
#   3. Waits for healthchecks, then applies demo/seed.sql.
#   4. Prints the demo accounts + API/WS base URLs for the phones.
#
# Usage:  ./demo/lan-up.sh        (from the repo root or demo/)
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  cat <<'USAGE'
Usage: demo/lan-up.sh

Start or reuse the NexaChat Compose demo, apply the idempotent demo seed, and
print the exact HTTP/WebSocket URLs to enter in a phone build.
The script refuses host ports 8080 or 5433 when a non-NexaChat listener owns
them. PowerShell twin: demo/lan-up.ps1.
USAGE
  exit 0
fi
if [[ $# -ne 0 ]]; then
  echo "ERROR: unexpected argument '$1' (use --help)." >&2
  exit 2
fi

# Compose may already be publishing these ports for the NexaChat demo. Permit
# only that exact project; any other host listener is a conflict.
port_is_listening() {
  local port="$1" output
  if command -v powershell.exe >/dev/null 2>&1; then
    powershell.exe -NoProfile -NonInteractive -Command \
      "if (Get-NetTCPConnection -State Listen -LocalPort ${port} -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }" \
      >/dev/null 2>&1
    return $?
  fi
  if command -v ss >/dev/null 2>&1; then
    output="$(ss -H -ltn "sport = :${port}" 2>/dev/null)" || return 2
    [[ -n "${output}" ]]
    return $?
  fi
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"${port}" -sTCP:LISTEN -t >/dev/null 2>&1
    return $?
  fi
  if command -v netstat >/dev/null 2>&1; then
    netstat -ano -p tcp 2>/dev/null | awk -v suffix=":${port}" \
      '$1 == "TCP" && $2 ~ (suffix "$") && ($4 == "LISTENING" || $4 == "LISTEN") { found=1 }
       END { exit !found }'
    return $?
  fi
  echo "ERROR: no supported TCP listener inspection command is available." >&2
  return 2
}

nexachat_publishes_port() {
  local port="$1" mappings
  mappings="$(docker ps --filter label=com.docker.compose.project=nexachat \
    --format '{{.Ports}}' 2>/dev/null || true)"
  printf '%s\n' "${mappings}" | tr ',' '\n' | grep -Eq ":${port}->"
}

for port in 8080 5433; do
  if port_is_listening "${port}"; then
    if nexachat_publishes_port "${port}"; then
      echo "==> Reusing NexaChat's existing listener on TCP ${port}."
    else
      echo "ERROR: TCP port ${port} is held by a non-NexaChat listener; refusing to start." >&2
      exit 1
    fi
  else
    status=$?
    if [[ "${status}" -ne 1 ]]; then
      echo "ERROR: could not safely inspect TCP port ${port}; refusing to start." >&2
      exit "${status}"
    fi
  fi
done

# --- 1. LAN IP ---------------------------------------------------------------
detect_lan_ip() {
  # Prefer PowerShell (Windows-first): first private IPv4 on a real NIC,
  # skipping loopback, APIPA (169.254.x), and virtual adapters.
  local ip=""
  if command -v powershell.exe >/dev/null 2>&1; then
    ip="$(powershell.exe -NoProfile -Command \
      "\$i = Get-NetIPAddress -AddressFamily IPv4 | Where-Object { \$_.InterfaceAlias -notmatch 'Loopback|vEthernet|VMware|VirtualBox|WSL|Docker|Bluetooth|\*' -and \$_.IPAddress -notmatch '^(127\.|169\.254\.)' }; \$p = (\$i | Where-Object { \$_.IPAddress -like '192.168.*' } | Select-Object -First 1).IPAddress; if (-not \$p) { \$p = (\$i | Where-Object { \$_.IPAddress -match '^(10\.|172\.(1[6-9]|2[0-9]|3[01])\.)' } | Select-Object -First 1).IPAddress }; if (-not \$p) { \$p = (\$i | Select-Object -First 1).IPAddress }; \$p" \
      2>/dev/null | tr -d ' \r\n')"
  fi
  # Fallback: ipconfig parse (English locale), prefer 192.168.* lines.
  if [ -z "${ip}" ]; then
    ip="$(ipconfig 2>/dev/null | awk '/IPv4/ {gsub(/\r/,""); print $NF}' | grep -m1 '^192\.168\.' || true)"
  fi
  if [ -z "${ip}" ]; then
    ip="$(ipconfig 2>/dev/null | awk '/IPv4/ {gsub(/\r/,""); print $NF; exit}' | tr -d ' \r\n')"
  fi
  echo "${ip}"
}

LAN_IP="$(detect_lan_ip)"
if [ -z "${LAN_IP}" ]; then
  echo "WARN: could not detect LAN IP — using 127.0.0.1. Phones on LAN will not" >&2
  echo "      reach this machine; use 'adb reverse tcp:8080 tcp:8080' instead." >&2
  LAN_IP="127.0.0.1"
fi
echo "==> LAN IP: ${LAN_IP}"

# --- 2. Start services -------------------------------------------------------
APP_IMAGE_BUILT=0
  if docker image inspect nexachat-app:1.0.0 >/dev/null 2>&1; then
  APP_IMAGE_BUILT=1
fi

if [ "${APP_IMAGE_BUILT}" -eq 1 ]; then
  echo "==> Starting db + app (image nexachat-app:1.0.0 found)"
  docker compose up -d db app
else
  echo "==> Starting db only — app image not built yet."
  echo "    Build once with:  docker compose build app   (then re-run this script)"
  docker compose up -d db
fi

# --- 3. Wait for health + seed ------------------------------------------------
wait_healthy() {
  local service="$1" cid status tries=0
  cid="$(docker compose ps -q "${service}" 2>/dev/null || true)"
  [ -n "${cid}" ] || return 1
  while [ "${tries}" -lt 90 ]; do
    status="$(docker inspect -f '{{.State.Health.Status}}' "${cid}" 2>/dev/null || echo starting)"
    [ "${status}" = "healthy" ] && return 0
    sleep 2; tries=$((tries + 1))
  done
  echo "WARN: ${service} did not report healthy within ~3 min" >&2
  return 1
}

echo "==> Waiting for db to be healthy..."
wait_healthy db

DB_CID="$(docker compose ps -q db)"
DB_USER="$(docker exec "${DB_CID}" printenv POSTGRES_USER)"
DB_NAME="$(docker exec "${DB_CID}" printenv POSTGRES_DB)"
if [ -z "${DB_USER}" ] || [ -z "${DB_NAME}" ]; then
  echo "ERROR: could not read the database name and user from the running container." >&2
  exit 1
fi

echo "==> Applying demo seed (demo/seed.sql)..."
docker exec -i "${DB_CID}" \
  psql -U "${DB_USER}" -d "${DB_NAME}" < demo/seed.sql >/dev/null
echo "    Seed applied (re-runnable — ON CONFLICT DO NOTHING)."

if [ "${APP_IMAGE_BUILT}" -eq 1 ]; then
  echo "==> Waiting for app to be healthy (Flyway + Spring Boot, up to ~3 min)..."
  wait_healthy app
fi

# --- 4. Print connection info -------------------------------------------------
cat <<EOF

==================================================================
 NexaChat demo is up.
==================================================================
 API base URL (phone clients):  http://${LAN_IP}:8080
 WebSocket endpoint:            ws://${LAN_IP}:8080/ws
 Health check:                  http://${LAN_IP}:8080/actuator/health

 Demo accounts (password for all: password123)
   Phone A:  +84900000001  (An)
   Phone B:  +84900000002  (Binh)
   Spare:    +84900000003  (Chi),  +84900000004  (Dung)

 Point the Android app at this machine:
   android/local.properties -> API_BASE_URL=http://${LAN_IP}:8080
   (or: ./gradlew -PapiBaseUrl=http://${LAN_IP}:8080 assembleDebug)
   emulator default http://10.0.2.2:8080 already works.
 Details + troubleshooting: docs/operations/operations.md
==================================================================
EOF
