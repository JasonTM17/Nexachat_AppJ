# Runs the server's summary performance benchmark test.
# Usage: pwsh -File tools/summary-eval/run-benchmark.ps1
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location (Join-Path $repoRoot 'server')
try {
    & mvn -Dtest=SummaryPerformanceTest test
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}
