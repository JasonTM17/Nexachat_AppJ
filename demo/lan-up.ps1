# ============================================================================
# NexaChat LAN demo bootstrap — PowerShell FALLBACK for Windows hosts without
# Git Bash. Primary script: demo/lan-up.sh
#
# Usage:  powershell -ExecutionPolicy Bypass -File .\demo\lan-up.ps1
# ============================================================================
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$DbUser = 'nexachat'
$DbName = 'nexachat'

# --- 1. LAN IP ---------------------------------------------------------------
$Candidates = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
  Where-Object {
    $_.InterfaceAlias -notmatch 'Loopback|vEthernet|VMware|VirtualBox|WSL|Docker|Bluetooth|\*' -and
    $_.IPAddress -notmatch '^(127\.|169\.254\.)'
  }
$LanIp = ($Candidates | Where-Object { $_.IPAddress -like '192.168.*' } |
            Select-Object -First 1 -ExpandProperty IPAddress)
if (-not $LanIp) {
  $LanIp = ($Candidates | Where-Object {
              $_.IPAddress -match '^(10\.|172\.(1[6-9]|2[0-9]|3[01])\.)'
            } | Select-Object -First 1 -ExpandProperty IPAddress)
}
if (-not $LanIp) {
  $LanIp = ($Candidates | Select-Object -First 1 -ExpandProperty IPAddress)
}

if (-not $LanIp) {
  Write-Warning 'Could not detect LAN IP - using 127.0.0.1. Use adb reverse instead of LAN.'
  $LanIp = '127.0.0.1'
}
Write-Host "==> LAN IP: $LanIp"

# --- 2. Start services -------------------------------------------------------
$AppImageBuilt = $false
docker image inspect nexachat-app *> $null
if ($LASTEXITCODE -eq 0) { $AppImageBuilt = $true }

if ($AppImageBuilt) {
  Write-Host '==> Starting db + app (image nexachat-app found)'
  docker compose up -d db app
} else {
  Write-Host '==> Starting db only - app image not built yet.'
  Write-Host '    Build once with:  docker compose build app   (then re-run this script)'
  docker compose up -d db
}

# --- 3. Wait for health + seed ------------------------------------------------
function Wait-Healthy([string]$Service) {
  $cid = (docker compose ps -q $Service 2>$null)
  if (-not $cid) { return $false }
  for ($i = 0; $i -lt 90; $i++) {
    $status = (docker inspect -f '{{.State.Health.Status}}' $cid 2>$null)
    if ($status -eq 'healthy') { return $true }
    Start-Sleep -Seconds 2
  }
  Write-Warning "$Service did not report healthy within ~3 min"
  return $false
}

Write-Host '==> Waiting for db to be healthy...'
[void](Wait-Healthy 'db')

Write-Host '==> Applying demo seed (demo/seed.sql)...'
$dbCid = (docker compose ps -q db)
Get-Content demo/seed.sql -Raw | docker exec -i $dbCid psql -U $DbUser -d $DbName | Out-Null
Write-Host '    Seed applied (re-runnable - ON CONFLICT DO NOTHING).'

if ($AppImageBuilt) {
  Write-Host '==> Waiting for app to be healthy (Flyway + Spring Boot, up to ~3 min)...'
  [void](Wait-Healthy 'app')
}

# --- 4. Print connection info -------------------------------------------------
Write-Host @"

==================================================================
 NexaChat demo is up.
==================================================================
 API base URL (phone clients):  http://${LanIp}:8080
 WebSocket endpoint:            ws://${LanIp}:8080/ws
 Health check:                  http://${LanIp}:8080/actuator/health

 Demo accounts (password for all: password123)
   Phone A:  +84900000001  (An)
   Phone B:  +84900000002  (Binh)
   Spare:    +84900000003  (Chi),  +84900000004  (Dung)

 Point the Android app at this machine:
   android/.../core/common/AppConfig.kt -> API_BASE_URL
   emulator default http://10.0.2.2:8080 already works.
 Details + troubleshooting: docs/demo-runbook.md
==================================================================
"@
