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
    $info.RedirectStandardOutput = $true
    $info.ArgumentList.Add("-Dquarkus.http.port=$Port")
    $info.ArgumentList.Add("-Dwallet.db.url=jdbc:h2:file:$database;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0")
    $info.ArgumentList.Add('-jar')
    $info.ArgumentList.Add($jar)
    $process = [Diagnostics.Process]::Start($info)
    $ready = $false
    try {
        for ($n = 0; $n -lt 40; $n++) {
            if ($process.HasExited) { throw 'El servidor de prueba termino antes de estar listo.' }
            $line = $process.StandardOutput.ReadLineAsync()
            if (-not $line.Wait(30000)) { throw 'El servidor no notifico su inicio a tiempo.' }
            $output = $line.GetAwaiter().GetResult()
            if ($null -eq $output) { throw 'El servidor cerro su salida antes de estar listo.' }
            if ($output -match 'Listening on:') {
                $health = Invoke-RestMethod "$base/health" -TimeoutSec 2
                if ($health.status -eq 'UP') { $ready = $true; return $process }
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
    $pinSuffix = (Get-Random -Minimum 10000000 -Maximum 99999999).ToString()
    $pinAlias = "persist$pinSuffix"
    $pinPhone = "9$pinSuffix"
    $startBody = @{ campaign = 'persistencia' } | ConvertTo-Json
    Invoke-RestMethod "$base/onboarding/start" -Method Post -Headers $headers -ContentType 'application/json' -Body $startBody -SessionVariable pinSession | Out-Null
    $identityBody = @{ step = 'identity'; name = 'Persona Persistente'; alias = $pinAlias; phone = $pinPhone; consent = $false } | ConvertTo-Json
    Invoke-RestMethod "$base/onboarding/step" -Method Post -Headers $headers -ContentType 'application/json' -Body $identityBody -WebSession $pinSession | Out-Null
    $consentBody = @{ step = 'consent'; consent = $true } | ConvertTo-Json
    Invoke-RestMethod "$base/onboarding/step" -Method Post -Headers $headers -ContentType 'application/json' -Body $consentBody -WebSession $pinSession | Out-Null
    $completeBody = @{ name = 'Persona Persistente'; alias = $pinAlias; phone = $pinPhone; consent = $true; pin = '123456' } | ConvertTo-Json
    Invoke-RestMethod "$base/onboarding/complete" -Method Post -Headers $headers -ContentType 'application/json' -Body $completeBody -WebSession $pinSession | Out-Null
    $headers['Idempotency-Key'] = [Guid]::NewGuid().ToString()
    $body = '{"destination":"luis.demo","amount":1234,"channel":"ALIAS"}'
    $first = Invoke-RestMethod "$base/payments" -Method Post -Headers $headers -ContentType 'application/json' -Body $body -WebSession $session
    $before = Invoke-RestMethod "$base/me" -WebSession $session
    Stop-Process -Id $server.Id
    $server.WaitForExit()
    $server = Start-TestServer
    $after = Invoke-RestMethod "$base/me" -WebSession $session
    $loginBody = @{ userId = $pinAlias; pin = '123456' } | ConvertTo-Json
    Invoke-RestMethod "$base/demo/login" -Method Post -Headers $headers -ContentType 'application/json' -Body $loginBody -SessionVariable restoredPinSession | Out-Null
    $restoredPinUser = Invoke-RestMethod "$base/me" -WebSession $restoredPinSession
    $duplicate = Invoke-RestMethod "$base/payments" -Method Post -Headers $headers -ContentType 'application/json' -Body $body -WebSession $session
    $final = Invoke-RestMethod "$base/me" -WebSession $session
    if ($first.id -ne $duplicate.id -or -not $duplicate.duplicate -or $before.balance -ne 48766 -or $after.balance -ne 48766 -or $final.balance -ne 48766) {
        throw 'La persistencia o idempotencia tras reinicio no cumple la invariante.'
    }
    if ($restoredPinUser.alias -ne $pinAlias -or $restoredPinUser.balance -ne 50000 -or $null -ne $restoredPinUser.pin_hash -or $null -ne $restoredPinUser.pin_salt) {
        throw 'El PIN no se autentica tras reinicio o la respuesta expone datos sensibles.'
    }
    Write-Host "PASS: reinicio conserva sesion, referencia $($first.id), ledger, saldo, idempotencia y PIN no expuesto."
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
