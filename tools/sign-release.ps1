# PowerShell entrypoint for the Bash release signer; Git Bash is required.
if ($args.Count -gt 0 -and $args[0] -in @('-h', '--help')) {
  Write-Output @'
Usage: tools/sign-release.ps1 [version-tag]

PowerShell entrypoint for sign-release.sh. Requires Git Bash, JDK 17+, the
Android SDK, and local release/keystore/signing.properties.
'@
  exit 0
}
if ($args.Count -gt 1) { throw 'Expected at most one version tag (use --help).' }

$VersionTag = if ($args.Count -eq 1) { [string]$args[0] } else { 'v1.0.0' }
if ($VersionTag -notmatch '^[A-Za-z0-9._-]+$') {
  throw 'Version tag may contain only letters, numbers, dot, underscore, and hyphen.'
}
$Bash = Get-Command bash.exe -ErrorAction SilentlyContinue
if (-not $Bash) { throw 'Git Bash bash.exe is required to run tools/sign-release.sh.' }

$Script = Join-Path $PSScriptRoot 'sign-release.sh'
& $Bash.Source $Script $VersionTag
exit $LASTEXITCODE
