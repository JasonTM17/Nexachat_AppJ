# Manual, opt-in provider check. Runs ONE call through the real adapter path
# (LlmClient + PromptBuilder + LlmOutputValidator) on the fixed synthetic
# Vietnamese conversation in LiveLlmSmoke. The gitignored .env is loaded into
# the process environment and never printed; output is limited to status,
# latency, token counts, the validation verdict and per-section item counts.

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$envPath = Join-Path $repoRoot '.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    throw 'configuration unavailable'
}

foreach ($line in Get-Content -LiteralPath $envPath) {
    if ($line -match '^\s*(NEXACHAT_SUMMARY_LLM_[A-Z_]+)\s*=\s*(.*)$') {
        $name = $Matches[1]
        $value = $Matches[2].Trim()
        if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[$value.Length - 1] -eq '"') -or
                ($value[0] -eq "'" -and $value[$value.Length - 1] -eq "'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        Set-Item -Path ("Env:" + $name) -Value $value
    }
}

Push-Location (Join-Path $repoRoot 'server')
try {
    mvn -q '-Dtest=LiveLlmSmoke' '-DRUN_LIVE_LLM=1' test
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
