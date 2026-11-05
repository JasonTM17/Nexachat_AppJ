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
