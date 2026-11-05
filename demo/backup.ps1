# NexaChat demo backup — PowerShell twin of backup.sh.
if ($args.Count -gt 0) {
  if ($args.Count -eq 1 -and $args[0] -in @('-h', '--help')) {
    Write-Output @'
Usage: demo/backup.ps1

Write a plain SQL snapshot of the running Compose database to
demo/backups/nexachat-<timestamp>.sql. Existing backups are never replaced.
Bash twin: demo/backup.sh.
'@
    exit 0
  }
  Write-Error "Unexpected argument(s): $($args -join ' ') (use --help)."
  exit 2
}

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$ContainerId = (docker compose ps -q db | Select-Object -First 1)
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the Compose database service.' }
if ([string]::IsNullOrWhiteSpace($ContainerId)) {
  throw 'The Compose database is not running. Start it with: docker compose up -d db'
}

$DbUser = (docker exec $ContainerId printenv POSTGRES_USER).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Could not read the database user from its container.' }
$DbName = (docker exec $ContainerId printenv POSTGRES_DB).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Could not read the database name from its container.' }
if ([string]::IsNullOrWhiteSpace($DbUser) -or [string]::IsNullOrWhiteSpace($DbName)) {
  throw 'The running database container has no configured name or user.'
}

$BackupDirectory = Join-Path (Get-Location) 'demo/backups'
[void][IO.Directory]::CreateDirectory($BackupDirectory)
$Timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$Suffix = 0
do {
  $Name = if ($Suffix -eq 0) { "nexachat-$Timestamp.sql" } else { "nexachat-$Timestamp-$Suffix.sql" }
  $OutputPath = Join-Path $BackupDirectory $Name
  $Suffix++
} while (Test-Path -LiteralPath $OutputPath)

$TemporaryPath = "$OutputPath.tmp"
$ErrorPath = "$OutputPath.stderr.tmp"
$Docker = (Get-Command docker -ErrorAction Stop).Source
try {
  $Process = Start-Process -FilePath $Docker -ArgumentList @(
    'exec', $ContainerId, 'pg_dump', '-U', $DbUser, '-d', $DbName, '--clean', '--if-exists'
  ) -NoNewWindow -Wait -PassThru -RedirectStandardOutput $TemporaryPath -RedirectStandardError $ErrorPath
  if ($Process.ExitCode -ne 0) {
    throw "pg_dump failed (exit $($Process.ExitCode)); no backup was kept."
  }
  Move-Item -LiteralPath $TemporaryPath -Destination $OutputPath
} finally {
  foreach ($Path in @($TemporaryPath, $ErrorPath)) {
    if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path }
  }
}

$Bytes = (Get-Item -LiteralPath $OutputPath).Length
Write-Output "Backup written: demo/backups/$Name ($Bytes bytes)"
