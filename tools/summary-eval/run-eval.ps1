# Runs the server's offline summary gold-set evaluation test.
# Usage: pwsh -File tools/summary-eval/run-eval.ps1
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location (Join-Path $repoRoot 'server')
try {
    & mvn -Dtest=GoldSetEvaluationTest test
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}
