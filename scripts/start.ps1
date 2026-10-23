param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$jar = Join-Path $root 'backend\target\quarkus-app\quarkus-run.jar'
if (-not (Test-Path $jar)) { throw 'Primero ejecuta .\scripts\build.ps1' }
Push-Location (Join-Path $root 'backend')
try {
    Write-Host "Inti Wallet demo: http://127.0.0.1:$Port (Ctrl+C para detener)"
    java "-Dquarkus.http.port=$Port" -jar $jar
    if ($LASTEXITCODE -ne 0) { throw 'El servidor no pudo iniciarse. Revisa el puerto y la base local.' }
} finally { Pop-Location }
