$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location (Join-Path $root 'frontend')
try {
    if (-not (Test-Path 'node_modules')) {
        npm ci --no-fund --no-audit
        if ($LASTEXITCODE -ne 0) { throw 'No se pudieron restaurar las dependencias frontend.' }
    }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'La compilacion Angular fallo.' }
} finally { Pop-Location }
Push-Location $root
try {
    mvn -f backend\pom.xml -B clean package
    if ($LASTEXITCODE -ne 0) { throw 'La compilacion o las pruebas Quarkus fallaron.' }
    Write-Host 'MVP compilado y probado. Ejecuta .\scripts\start.ps1'
} finally { Pop-Location }
