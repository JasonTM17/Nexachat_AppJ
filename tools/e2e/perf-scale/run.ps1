<#
.SYNOPSIS
Runs the performance-scale SQL against a PostgreSQL container.
.DESCRIPTION
Streams this directory's benchmark.sql file to psql in the selected container.
.PARAMETER ContainerName
Name of the running NexaChat PostgreSQL container.
.EXAMPLE
pwsh -File tools/e2e/perf-scale/run.ps1 -ContainerName nexachat-db-1
#>
param(
    [string]$ContainerName = "nexachat-db-1"
)

$ErrorActionPreference = "Stop"
$sqlPath = Join-Path $PSScriptRoot "benchmark.sql"

if (-not (Test-Path -LiteralPath $sqlPath)) {
    throw "Benchmark SQL was not found."
}

Get-Content -Raw -LiteralPath $sqlPath |
    docker exec -i $ContainerName psql -X -v ON_ERROR_STOP=1 -U nexachat -d nexachat

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
