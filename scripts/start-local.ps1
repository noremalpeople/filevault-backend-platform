[CmdletBinding()]
param([switch]$SkipInstall)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$frontend = Join-Path $root 'frontend'
Push-Location -LiteralPath $frontend
try {
    if (-not $SkipInstall) {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw "npm ci failed with $LASTEXITCODE." }
    }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw "Frontend build failed with $LASTEXITCODE." }
} finally {
    Pop-Location
}
$previous = $env:FILEVAULT_WEB_ROOT
try {
    $dist = (Resolve-Path -LiteralPath (Join-Path $frontend 'dist')).Path
    $env:FILEVAULT_WEB_ROOT = ([Uri]::new($dist + [IO.Path]::DirectorySeparatorChar)).AbsoluteUri
    & (Join-Path $PSScriptRoot 'start-backend.ps1')
} finally {
    $env:FILEVAULT_WEB_ROOT = $previous
}
