# ============================================================================
# NexaChat LAN demo bootstrap — PowerShell FALLBACK for Windows hosts without
# Git Bash. Primary script: demo/lan-up.sh
#
# Usage:  powershell -ExecutionPolicy Bypass -File .\demo\lan-up.ps1
# ============================================================================
if ($args.Count -gt 0) {
  if ($args.Count -eq 1 -and $args[0] -in @('-h', '--help')) {
    Write-Output @'
Usage: demo/lan-up.ps1

Start or reuse the NexaChat Compose demo, apply the idempotent demo seed, and
print the exact HTTP/WebSocket URLs to enter in a phone build.
The script refuses host ports 8080 or 5433 when a non-NexaChat listener owns
them. Bash twin: demo/lan-up.sh.
'@
    exit 0
  }
  Write-Error "Unexpected argument(s): $($args -join ' ') (use --help)."
  exit 2
}

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$DockerPortMappings = @(docker ps --filter label=com.docker.compose.project=nexachat --format '{{.Ports}}')
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect Docker containers before checking host ports.' }
foreach ($Port in @(8080, 5433)) {
  $Listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
  if ($Listeners.Count -gt 0) {
    $PublishedByNexaChat = $DockerPortMappings | Where-Object { $_ -match ":${Port}->" }
    if (-not $PublishedByNexaChat) {
      throw "TCP port $Port is held by a non-NexaChat listener; refusing to start."
    }
    Write-Host "==> Reusing NexaChat's existing listener on TCP $Port."
  }
}

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
docker image inspect nexachat-app:1.0.0 *> $null
if ($LASTEXITCODE -eq 0) { $AppImageBuilt = $true }

if ($AppImageBuilt) {
  Write-Host '==> Starting db + app (image nexachat-app:1.0.0 found)'
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
if (-not (Wait-Healthy 'db')) { throw 'The database did not become healthy; the demo seed was not applied.' }

$DbCid = (docker compose ps -q db | Select-Object -First 1)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($DbCid)) {
  throw 'Could not identify the healthy Compose database container.'
}
$DbUser = (docker exec $DbCid printenv POSTGRES_USER).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Could not read the database user from its container.' }
$DbName = (docker exec $DbCid printenv POSTGRES_DB).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Could not read the database name from its container.' }
if ([string]::IsNullOrWhiteSpace($DbUser) -or [string]::IsNullOrWhiteSpace($DbName)) {
  throw 'The running database container has no configured name or user.'
}

Write-Host '==> Applying demo seed (demo/seed.sql)...'
Get-Content demo/seed.sql -Raw | docker exec -i $DbCid psql -U $DbUser -d $DbName | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Applying demo/seed.sql failed.' }
Write-Host '    Seed applied (re-runnable - ON CONFLICT DO NOTHING).'

if ($AppImageBuilt) {
  Write-Host '==> Waiting for app to be healthy (Flyway + Spring Boot, up to ~3 min)...'
  if (-not (Wait-Healthy 'app')) { throw 'The app did not become healthy.' }
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
   android/local.properties -> API_BASE_URL=http://${LanIp}:8080
   (or: ./gradlew -PapiBaseUrl=http://${LanIp}:8080 assembleDebug)
   emulator default http://10.0.2.2:8080 already works.
 Details + troubleshooting: docs/demo-runbook.md
==================================================================
"@
