$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptDir "..\..")
Set-Location (Join-Path $RepoRoot "server")
mvn -Dtest=RagBenchmarkEvaluationTest test
