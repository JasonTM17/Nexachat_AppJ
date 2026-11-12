[CmdletBinding()]
param(
    [string]$TestSelection = 'SyncCursorRollbackIT'
)

$ErrorActionPreference = 'Stop'

$ServerRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RepoRoot = (Resolve-Path (Join-Path $ServerRoot '..')).Path
$V31SourceSha = '9216773920fc59684564170eebde13b33ca7fbc1'
$TempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$V31Worktree = Join-Path $TempRoot ("nexachat-v31-sync-drill-" + [guid]::NewGuid().ToString('N'))
$V31WorktreeFull = [IO.Path]::GetFullPath($V31Worktree)
if (-not $V31WorktreeFull.StartsWith($TempRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The temporary V31 worktree path is outside the system temp directory.'
}
if (Test-Path -LiteralPath $V31WorktreeFull) {
    throw 'The unique temporary V31 worktree path unexpectedly already exists.'
}

$EnvironmentNames = @(
    'IT_DB_URL', 'IT_DB_USER', 'IT_DB_PASSWORD',
    'FCM_ENABLED', 'FCM_PROJECT_ID', 'FCM_SERVICE_ACCOUNT_JSON',
    'V31_SERVER_JAR', 'V31_SOURCE_SHA'
)
$SavedEnvironment = @{}
foreach ($Name in $EnvironmentNames) {
    $SavedEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
}

$WorktreeAdded = $false
try {
    if ([string]::IsNullOrWhiteSpace($env:IT_DB_URL)) {
        $env:IT_DB_URL = 'jdbc:postgresql://localhost:5433/nexachat_grok_v2'
    }
    if ([string]::IsNullOrWhiteSpace($env:IT_DB_USER)) {
        $env:IT_DB_USER = 'nexachat'
    }
    if ([string]::IsNullOrWhiteSpace($env:IT_DB_PASSWORD)) {
        $env:IT_DB_PASSWORD = 'nexachat'
    }
    Remove-Item Env:FCM_ENABLED, Env:FCM_PROJECT_ID, Env:FCM_SERVICE_ACCOUNT_JSON -ErrorAction SilentlyContinue

    Write-Host 'Building the current V32 server artifact without running the full test suite.'
    & mvn -B -f (Join-Path $ServerRoot 'pom.xml') '-Dmaven.test.skip=true' package
    if ($LASTEXITCODE -ne 0) {
        throw "Current server package failed with exit code $LASTEXITCODE."
    }

    Write-Host "Creating the frozen V31 source worktree at $V31SourceSha."
    & git -C $RepoRoot worktree add --detach $V31WorktreeFull $V31SourceSha
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create the frozen V31 worktree (exit $LASTEXITCODE)."
    }
    $WorktreeAdded = $true
    $ResolvedV31Head = (& git -C $V31WorktreeFull rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $ResolvedV31Head -ne $V31SourceSha) {
        throw 'The frozen V31 worktree HEAD did not match the required source identity.'
    }

    Write-Host 'Building the frozen V31 server artifact without running its test suite.'
    & mvn -B -f (Join-Path $V31WorktreeFull 'server/pom.xml') '-Dmaven.test.skip=true' package
    if ($LASTEXITCODE -ne 0) {
        throw "Frozen V31 server package failed with exit code $LASTEXITCODE."
    }
    $V31Jar = Join-Path $V31WorktreeFull 'server/target/nexachat-server-0.0.1-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $V31Jar -PathType Leaf)) {
        throw 'The frozen V31 package did not produce the expected server jar.'
    }

    $env:V31_SERVER_JAR = (Resolve-Path -LiteralPath $V31Jar).Path
    $env:V31_SOURCE_SHA = $V31SourceSha
    Write-Host "Running focused sync integration selection ($TestSelection) on the configured local PostgreSQL server."
    & mvn -B -f (Join-Path $ServerRoot 'pom.xml') test-compile "-Dit.test=$TestSelection" failsafe:integration-test failsafe:verify
    if ($LASTEXITCODE -ne 0) {
        throw "SyncCursorRollbackIT failed with exit code $LASTEXITCODE."
    }
}
finally {
    foreach ($Name in $EnvironmentNames) {
        $Value = $SavedEnvironment[$Name]
        if ($null -eq $Value) {
            Remove-Item -LiteralPath "Env:$Name" -ErrorAction SilentlyContinue
        }
        else {
            Set-Item -LiteralPath "Env:$Name" -Value $Value
        }
    }

    if ($WorktreeAdded) {
        $ResolvedCleanupPath = [IO.Path]::GetFullPath($V31WorktreeFull)
        if (-not $ResolvedCleanupPath.StartsWith($TempRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing to remove a V31 worktree outside the system temp directory.'
        }
        if ($ResolvedCleanupPath -ne $V31WorktreeFull) {
            throw 'Refusing to remove a V31 worktree whose resolved path changed.'
        }
        & git -C $RepoRoot worktree remove --force $ResolvedCleanupPath
        if ($LASTEXITCODE -ne 0) {
            throw "Could not remove the temporary V31 worktree (exit $LASTEXITCODE)."
        }
    }
}
