[CmdletBinding()]
param([switch]$Reconcile)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env.local'
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw 'Create .env.local from .env.example first.'
}
$allowed = @('FILEVAULT_DB_PORT', 'FILEVAULT_DB_USER', 'FILEVAULT_DB_PASSWORD',
             'FILEVAULT_PORT', 'FILEVAULT_STORAGE_ROOT')
$config = @{}
foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
    if (-not $line.Trim() -or $line.TrimStart().StartsWith('#')) { continue }
    if ($line -notmatch '^([A-Z_][A-Z0-9_]*)=(.*)$') { throw 'Expected KEY=value in .env.local.' }
    $key = $Matches[1]
    if ($key -notin $allowed -or $config.ContainsKey($key)) { throw 'Unexpected or duplicate .env.local key.' }
    $config[$key] = $Matches[2]
}
if ([string]::IsNullOrWhiteSpace($config['FILEVAULT_DB_PASSWORD'])) {
    throw 'Set a generated FILEVAULT_DB_PASSWORD in .env.local.'
}
if (-not $config['FILEVAULT_STORAGE_ROOT']) {
    $config['FILEVAULT_STORAGE_ROOT'] = Join-Path $root '.local/storage'
}

$previous = @{}
Push-Location -LiteralPath (Join-Path $root 'backend')
try {
    foreach ($key in $allowed) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $config[$key], 'Process')
    }
    if ($env:FILEVAULT_JAVA_HOME) {
        $previous['JAVA_HOME'] = $env:JAVA_HOME
        $env:JAVA_HOME = $env:FILEVAULT_JAVA_HOME
    }
    $goals = @('spring-boot:run')
    if ($Reconcile) { $goals = @('-q', 'spring-boot:run', '-Dspring-boot.run.arguments=--reconcile-storage') }
    & .\mvnw.cmd @goals
    if ($LASTEXITCODE -ne 0) { throw "Maven exited with $LASTEXITCODE." }
} finally {
    foreach ($key in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process')
    }
    Pop-Location
}
