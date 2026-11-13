# PowerShell entrypoint for the Bash release signer; Git Bash is required.
if ($args.Count -gt 0 -and $args[0] -in @('-h', '--help')) {
  Write-Output @'
Usage: tools/release/sign-release.ps1 [version-tag] [-PapiBaseUrl=<url>] [--allow-http-smoke]

PowerShell entrypoint for sign-release.sh. Requires Git Bash, JDK 21+, the
Android SDK, and local release/keystore/signing.properties. HTTPS is required
for signing unless --allow-http-smoke is used for a local smoke build.
'@
  exit 0
}
$Bash = Get-Command bash.exe -ErrorAction SilentlyContinue
if (-not $Bash) { throw 'Git Bash bash.exe is required to run tools/release/sign-release.sh.' }

$Script = Join-Path $PSScriptRoot 'sign-release.sh'
& $Bash.Source $Script @args
exit $LASTEXITCODE
