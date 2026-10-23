param([int]$Port = 8092)
$ErrorActionPreference = 'Stop'
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "El puerto $Port ya esta ocupado. Elige otro." }
$root = Split-Path $PSScriptRoot -Parent
$jar = Join-Path $root 'backend\target\quarkus-app\quarkus-run.jar'
if (-not (Test-Path $jar)) { throw 'Primero compila el MVP.' }
$directory = Join-Path $root ('backend\target\persistence-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $directory | Out-Null
$database = Join-Path $directory 'wallet'
$base = "http://127.0.0.1:$Port/api"
$server = $null
function Start-TestServer {
    $info = [Diagnostics.ProcessStartInfo]::new('java')
    $info.UseShellExecute = $false
    $info.ArgumentList.Add("-Dquarkus.http.port=$Port")
    $info.ArgumentList.Add("-Dwallet.db.url=jdbc:h2:file:$database;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0")
    $info.ArgumentList.Add('-jar')
    $info.ArgumentList.Add($jar)
    $process = [Diagnostics.Process]::Start($info)
    $ready = $false
    try {
        for ($n = 0; $n -lt 40; $n++) {
            if ($process.HasExited) { throw 'El servidor de prueba termino antes de estar listo.' }
            try {
                $health = Invoke-RestMethod "$base/health" -TimeoutSec 2
                if ($health.status -eq 'UP') { $ready = $true; return $process }
            } catch [System.Net.Http.HttpRequestException] {
                Start-Sleep -Milliseconds 250
            } catch [System.OperationCanceledException] {
                Start-Sleep -Milliseconds 250
            }
        }
        throw 'El servidor de prueba no respondio a tiempo.'
    } finally {
        if (-not $ready -and -not $process.HasExited) {
            Stop-Process -Id $process.Id
            $process.WaitForExit()
        }
    }
}
try {
    $server = Start-TestServer
    $headers = @{ 'X-Wallet-Request' = '1' }
    Invoke-RestMethod "$base/demo/login" -Method Post -Headers $headers -ContentType 'application/json' -Body '{"userId":"demo-ana"}' -SessionVariable session | Out-Null
    $headers['Idempotency-Key'] = [Guid]::NewGuid().ToString()
    $body = '{"destination":"luis.demo","amount":1234,"channel":"ALIAS","scenario":"APPROVED"}'
    $first = Invoke-RestMethod "$base/payments" -Method Post -Headers $headers -ContentType 'application/json' -Body $body -WebSession $session
    $before = Invoke-RestMethod "$base/me" -WebSession $session
    Stop-Process -Id $server.Id
    $server.WaitForExit()
    $server = Start-TestServer
    $after = Invoke-RestMethod "$base/me" -WebSession $session
    $duplicate = Invoke-RestMethod "$base/payments" -Method Post -Headers $headers -ContentType 'application/json' -Body $body -WebSession $session
    $final = Invoke-RestMethod "$base/me" -WebSession $session
    if ($first.id -ne $duplicate.id -or -not $duplicate.duplicate -or $before.balance -ne 48766 -or $after.balance -ne 48766 -or $final.balance -ne 48766) {
        throw 'La persistencia o idempotencia tras reinicio no cumple la invariante.'
    }
    Write-Host "PASS: reinicio conserva sesion, referencia $($first.id), ledger, saldo e idempotencia."
} finally {
    if ($null -ne $server -and -not $server.HasExited) {
        Stop-Process -Id $server.Id
        $server.WaitForExit()
    }
    foreach ($name in @('wallet.mv.db', 'wallet.trace.db', 'wallet.lock.db')) {
        $file = Join-Path $directory $name
        if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file }
    }
    Remove-Item -LiteralPath $directory
}
