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

DB_USER=nexachat
DB_NAME=nexachat

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
if docker image inspect nexachat-app >/dev/null 2>&1; then
  APP_IMAGE_BUILT=1
fi

if [ "${APP_IMAGE_BUILT}" -eq 1 ]; then
  echo "==> Starting db + app (image nexachat-app found)"
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

echo "==> Applying demo seed (demo/seed.sql)..."
docker exec -i "$(docker compose ps -q db)" \
  psql -U "${DB_USER}" -d "${DB_NAME}" < demo/seed.sql >/dev/null
echo "    Seed applied (re-runnable — ON CONFLICT DO NOTHING)."

if [ "${APP_IMAGE_BUILT}" -eq 1 ]; then
  echo "==> Waiting for app to be healthy (Flyway + Spring Boot, up to ~3 min)..."
  wait_healthy app || true
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
   android/.../core/common/AppConfig.kt -> API_BASE_URL
   emulator default http://10.0.2.2:8080 already works.
 Details + troubleshooting: docs/demo-runbook.md
==================================================================
EOF
