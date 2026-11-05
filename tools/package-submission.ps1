# PowerShell entrypoint for the Bash submission packager; Git Bash is required.
if ($args.Count -gt 0 -and $args[0] -in @('-h', '--help')) {
  Write-Output @'
Usage: tools/package-submission.ps1 [version-tag]

PowerShell entrypoint for package-submission.sh. Requires Git Bash and the
matching signed/unsigned APKs (the Bash script creates them when absent).
'@
  exit 0
}
if ($args.Count -gt 1) { throw 'Expected at most one version tag (use --help).' }

$VersionTag = if ($args.Count -eq 1) { [string]$args[0] } else { 'v1.0.0' }
if ($VersionTag -notmatch '^[A-Za-z0-9._-]+$') {
  throw 'Version tag may contain only letters, numbers, dot, underscore, and hyphen.'
}
$Bash = Get-Command bash.exe -ErrorAction SilentlyContinue
if (-not $Bash) { throw 'Git Bash bash.exe is required to run tools/package-submission.sh.' }

$Script = Join-Path $PSScriptRoot 'package-submission.sh'
& $Bash.Source $Script $VersionTag
exit $LASTEXITCODE
