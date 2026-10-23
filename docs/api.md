# Contrato de la API local

Base: `http://127.0.0.1:8080/api`. JSON en UTF-8. Montos en **céntimos enteros de PEN**. Fechas de registro en UTC ISO-8601; filtros diarios y métricas en `America/Lima`.

Toda mutación usa `Content-Type: application/json`, un objeto JSON y `X-Wallet-Request: 1`. La sesión viaja por cookie `wallet_session`. No habilitar esta API mock públicamente.

## Identidad y onboarding

| Método | Ruta | Cuerpo / comportamiento |
|---|---|---|
| GET | `/health` | Estado del proceso y modo sintético |
| POST | `/demo/login` | `{"userId":"demo-ana"}`; también `demo-luis`, `demo-merchant`, `demo-ops` o alias de un pagador que ya completó registro |
| POST | `/logout` | `{}`; revoca sesión actual |
| GET | `/me` | Cuenta, estado, saldo disponible, reservado y progreso de registro |
| POST | `/onboarding/start` | `{"campaign":"organico"}`; cuenta pendiente + sesión |
| POST | `/onboarding/step` | Paso `identity` con `name`, `alias`, `phone`, `consent:false`; luego paso `consent` con `consent:true` |
| POST | `/onboarding/complete` | `name`, `alias`, `phone`, `consent:true`, `kyc`: `APPROVED`, `PENDING` o `DECLINED` |
| GET | `/contacts` | Destinos activos no OPS, distintos de la propia cuenta |

Identidad: nombre ficticio de 3–80 caracteres; alias `[a-z][a-z0-9.]{2,29}`; celular ficticio `9[0-9]{8}`. Alias y celular son únicos. Una cuenta pendiente/rechazada no puede pagar.

## Checkout, pagos y QR

| Método | Ruta | Contrato |
|---|---|---|
| POST | `/checkout/start` | `{"attemptId":"<uuid>"}`; inicio analítico de revisión, deduplicado por pagador y clave |
| POST | `/payments` | Requiere `Idempotency-Key`; cuerpo de pago abajo |
| GET | `/payments` | Filtros opcionales `date=YYYY-MM-DD`, `reference=<texto>`, `channel=ALIAS\|QR`; últimos 200 coincidentes autorizados |
| GET | `/payments/{id}` | Resultado, guía de elegibilidad, ledger y casos; solo participantes u OPS |
| POST | `/qr` | Comercio activo; `{"amount":2500}`; devuelve id, comercio, monto, vencimiento e imagen PNG data URL |
| GET | `/qr/{id}` | QR no vencido y su comercio |
| POST | `/qr/decode` | `{"image":"data:image/png;base64,..."}`; valida PNG, decodifica y consulta QR local |

Ejemplo de pago:

```json
{
  "destination": "luis.demo",
  "amount": 1234,
  "channel": "ALIAS",
  "scenario": "APPROVED",
  "qrId": null
}
```

`scenario` es una **herramienta de fault injection de esta demo**, no un campo que un cliente financiero real debería poder controlar:

| Proveedor mock | Estado Wallet | Efecto |
|---|---|---|
| `APPROVED` | `SUCCEEDED` | Reserva y liquidación al destino |
| `DECLINED` | `FAILED` | Reserva devuelta al pagador |
| `PENDING` | `PENDING` | Reserva hasta callback humano simulado |
| `TIMEOUT` | `EXPIRED` | Mock confirma expiración; se devuelve la reserva |

En QR, `destination` y `amount` deben coincidir con `qrId`. En alias no se admite QR. Se rechazan el autopago, destinos no activos, montos fuera del límite y fondos insuficientes.

La respuesta incluye `id`, `payer_id`, `destination_id`, nombres, `amount`, `channel`, `scenario`, `status`, `reason`, fechas, `reversed`, `retry_allowed`, `dispute_allowed`, `investigation_allowed`. Un reintento devuelve la misma transacción con `duplicate:true`. No se exponen fingerprint ni clave en el DTO.

## Soporte y operaciones

| Método | Ruta | Cuerpo / acceso |
|---|---|---|
| POST | `/payments/{id}/cases` | Participante u OPS; `{"category":"TECHNICAL\|DISPUTE\|REVERSAL","note":"motivo ficticio"}` |
| GET | `/ops/cases` | OPS; últimos 200 casos |
| POST | `/ops/cases/{id}` | OPS; `{"decision":"APPROVE_REVERSAL\|REJECT\|RESOLVE","note":"decisión humana"}` |
| GET | `/ops/onboarding` | OPS; cuentas cuyo KYC mock está pendiente |
| POST | `/ops/onboarding/{id}` | OPS; `{"decision":"APPROVED\|DECLINED"}` |
| POST | `/ops/payments/{id}/callback` | OPS; `{"eventId":"<uuid>","outcome":"APPROVED\|DECLINED\|PENDING\|TIMEOUT"}` |
| POST | `/ops/payments/{id}/fixture` | OPS; `{"outcome":"DECLINED","amount":1200,"missing":false}` o `{"missing":true}` |
| POST | `/ops/reconciliation` | OPS; `{"date":"2026-10-23","channel":"ALIAS"}`; canal `""` o ausente para ambos |
| POST | `/ops/mismatches/{id}/investigate` | OPS; `{}` |
| GET | `/ops/audit` | OPS; últimos 200 registros con solicitud y correlación |
| GET | `/ops/metrics?launchMonth=2026-10` | OPS; tres meses, objetivos y gasto atribuido |
| POST | `/ops/acquisition` | OPS; `{"month":"2026-10","campaign":"organico","cents":1000}`; reemplaza mes/campaña |
| POST | `/assistant` | Sesión; `{"message":"Explícame el pago","paymentId":"<id o null>"}` |

Un reverso solo se ejecuta en un caso `REVERSAL` abierto, sobre un pago exitoso no revertido, por OPS y con fondos suficientes en el destino. La decisión es una aprobación humana explícita, no una cancelación unilateral garantizada.

Conciliación devuelve `date`, `timezone`, `total`, `matched`, `unmatched`, `resolved` y `rows`. Cada fila incluye `payment`, `provider` o `null`, `matched`, `duplicate_requests` y `issues` con `id`, `kind`, `status` y fechas.

Métricas devuelve `months` con `month`, `checkout_started`, `checkout_succeeded`, `dropoff_percent`, `mau`, `new_active_users`, `spend_cents`, `cac_cents`, `onboarding_median_seconds` y `attribution` por campaña. Los campos sin denominador o gasto registrado usan `null`.

El asistente devuelve `message`, `intent`, `trace_id`, `mode:LOCAL_RULE_BASED`, `human_review_required` y nombres de herramientas permitidas. No ejecuta comandos ni movimientos.

## Errores

```json
{"code":"IDEMPOTENCY_CONFLICT","message":"La clave ya fue usada con otros datos. Consulta el intento original."}
```

- `400`: campos inválidos, monto, límite, saldo insuficiente, QR expirado, cuerpo no objeto JSON.
- `401`: sesión ausente, revocada o vencida.
- `403`: rol/propiedad, cuenta no activa o encabezado protector ausente.
- `404`: recurso o alias de prueba no encontrado.
- `409`: conflicto de idempotencia, evento contradictorio, identidad ya usada o caso duplicado.
- `413`: cuerpo demasiado grande.
- `500`: error inesperado registrado en servidor; mensaje genérico sin detalles internos.

No se usa el reintento con clave nueva para “resolver” un resultado desconocido. Consultar el intento original antes de iniciar otro.

## Ejemplo completo en PowerShell

```powershell
$base = 'http://127.0.0.1:8080/api'
$headers = @{ 'X-Wallet-Request' = '1' }
Invoke-RestMethod "$base/demo/login" -Method Post -Headers $headers `
  -ContentType 'application/json' -Body '{"userId":"demo-ana"}' `
  -SessionVariable walletSession

$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
$body = @{
  destination = 'luis.demo'
  amount = 1234
  channel = 'ALIAS'
  scenario = 'APPROVED'
  qrId = $null
} | ConvertTo-Json

$payment = Invoke-RestMethod "$base/payments" -Method Post -Headers $headers `
  -ContentType 'application/json' -Body $body -WebSession $walletSession

# Repetir sin duplicar movimiento
$same = Invoke-RestMethod "$base/payments" -Method Post -Headers $headers `
  -ContentType 'application/json' -Body $body -WebSession $walletSession
$payment.id -eq $same.id
```
