param([int]$Port = 8080, [switch]$ListenOnLan)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$jar = Join-Path $root 'backend\target\quarkus-app\quarkus-run.jar'
if (-not (Test-Path $jar)) { throw 'Primero ejecuta .\scripts\build.ps1' }
$bindAddress = if ($ListenOnLan) { '0.0.0.0' } else { '127.0.0.1' }
$phoneAddresses = @()
if ($ListenOnLan) {
    $phoneAddresses = @(Get-NetIPConfiguration |
        Where-Object { $_.IPv4DefaultGateway -and $_.IPv4Address } |
        ForEach-Object { $_.IPv4Address.IPAddress } |
        Where-Object { $_ -and $_ -notmatch '^(127\.|169\.254\.)' } |
        Select-Object -Unique)
    if (-not $phoneAddresses) { throw 'No se encontró una dirección IPv4 de red local.' }
}
Push-Location (Join-Path $root 'backend')
try {
    Write-Host "Tilin demo en este equipo: http://127.0.0.1:$Port"
    if ($ListenOnLan) {
        Write-Host 'Para probar desde un smartphone conectado a la misma red:'
        foreach ($address in $phoneAddresses) { Write-Host "  http://${address}:$Port" }
        Write-Host 'Permite el acceso en redes privadas si Windows Firewall lo solicita.'
    }
    Write-Host 'Presiona Ctrl+C para detener.'
    java "-Dquarkus.http.host=$bindAddress" "-Dquarkus.http.port=$Port" -jar $jar
    if ($LASTEXITCODE -ne 0) { throw 'El servidor no pudo iniciarse. Revisa el puerto y la base local.' }
} finally { Pop-Location }
