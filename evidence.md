# Evidencia ejecutada · Inti Wallet

Fecha: **23 de octubre de 2026**, entorno Windows local. Resultados de la versión final, no estimaciones ni pruebas pendientes presentadas como éxitos.

## Resultado

| Verificación | Resultado |
|---|---|
| Build Angular producción con plantillas estrictas | Correcto |
| Build Quarkus / Java 21, aplicación integrada | Correcto |
| Pruebas dominio/API | **28 ejecutadas; 0 fallas; 0 errores; 0 omitidas** |
| Pruebas Playwright | **20 aprobadas**: 10 recorridos × escritorio y móvil |
| Reinicio abrupto con H2 en archivo | **Aprobado**: sesión, referencia, saldo, ledger e idempotencia conservados |
| Servidor local | `/api/health` devuelve `UP` en modo `SYNTHETIC_DEMO`; `/` sirve Angular con HTTP 200 |
| Inspección visual | Aplicación abierta en navegador integrado; dashboard responsive, saldo y navegación renderizados |

## Comandos reproducibles

```powershell
.\scripts\build.ps1
Set-Location frontend
npm test
Set-Location ..
.\scripts\test-persistence.ps1
```

La última ejecución final del backend terminó a las **11:03** hora Perú:

```text
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

La última suite final de navegador terminó con:

```text
20 passed (45.7s)
```

La última prueba de reinicio terminó con:

```text
PASS: reinicio conserva sesion, referencia [...], ledger, saldo e idempotencia.
```

El test comprueba saldo de **48,766 céntimos** después de un pago de 1,234, antes del reinicio, después y después de reenviar la misma clave. No se acepta solo un `200` como sustituto de verificar dinero e identidad de la transacción.

## Cobertura del backend

[Suite ejecutable](backend/src/test/java/pe/wallet/WalletTest.java):

1. Registro por pasos con saldo inicial concedido una sola vez.
2. Identidad inválida, falta de consentimiento y pasos omitidos.
3. KYC pendiente, aprobación humana, rechazo y bloqueo de pagos.
4. Identidad duplicada.
5. Reingreso por alias mock y sesión expirada.
6. Pago aprobado, asientos y comprobante consultable.
7. Request idempotente, payload incompatible y auditoría de duplicados.
8. 16 requests simultáneos con la misma clave: un solo efecto.
9. Pagos simultáneos diferentes: imposible sobregirar.
10. Rechazo/expiración con reserva devuelta.
11. Pending con callback y duplicados del callback.
12. Pending terminado en rechazo o expiración sin doble devolución.
13. Límites, autopago, destino, rol y precisión de dinero.
14. PNG QR generado y leído, con destino/monto vinculados.
15. QR vencido e imagen inválida.
16. Roles, propiedad de recursos, cookie, guardas HTTP y logout.
17. Reverso explícito aprobado, compensación y prohibición de repetición.
18. Reverso rechazado por falta de saldo del destino.
19. Rutas de soporte para falla técnica vs. pago ejecutado.
20. Descalces de monto, estado y proveedor ausente; investigación y resolución.
21. Descalce de ledger aun cuando la suma global sigue siendo cero.
22. Pending que no puede clasificarse falsamente como conciliado.
23. Métricas de eventos, MAU, nuevas altas y gasto/CAC.
24. Revisiones abandonadas y deduplicación del inicio de checkout.
25. Callback entre meses: éxito en cohorte correcta, sin caída negativa.
26. Asistente read-only, contexto autorizado y bloqueo de instrucciones.
27. Cuerpos malformados y fechas inválidas.
28. Fechas de Perú, referencia, canal y aislamiento de historial.

Después de cada prueba se verifica suma global del ledger igual a cero y ausencia de saldos negativos fuera de tesorería.

## Cobertura UI

[Suite Playwright](frontend/tests/wallet.spec.ts), repetida en escritorio y Pixel 7 emulado:

- Registro, persistencia de paso al recargar, activación y reingreso por alias.
- Revisión de destino/monto, pago, comprobante, consulta idempotente y recuperación al recargar.
- Generación y lectura de QR PNG entre comercio y pagador.
- Pending que se actualiza tras callback de operaciones.
- Descalce, investigación y resolución de conciliación.
- Asistente y ausencia de desbordamiento horizontal.
- Validación de límite y resultado rechazado.
- Respuesta de pago abortada **después del commit**, recuperación con referencia original.
- Solicitud y aprobación humana de reverso desde la UI.
- Tres meses de métricas y atribución por campaña.

## Iteraciones relevantes

- Las primeras pruebas detectaron un nombre SQL reservado y una cantidad incorrecta de parámetros del INSERT; se corrigieron antes de dar por funcional la API.
- La primera suite UI detectó selectores ambiguos y sincronización insuficiente de una consulta filtrada. Se corrigieron las pruebas para verificar la fila esperada, no para ocultar un fallo de negocio.
- Se agregó reingreso por alias sintético para que las cuentas creadas no quedaran inaccesibles tras logout.
- La revisión de instrumentación detectó que medir únicamente pagos recibidos por la API no contaba abandono antes de confirmar. Se agregó inicio de revisión persistido y cálculo por cohorte.
- La prueba de persistencia detectó que su verificación de readiness debía manejar timeout de inicio y limpiar su proceso aun si el arranque fallaba. Se corrigió el harness y se repitió hasta aprobar.

Reportes regenerables, no versionados: `backend/target/surefire-reports` y `frontend/playwright-report`. Las pruebas usan datos sintéticos y bases aisladas.

## Lo que esta evidencia no demuestra

- No demuestra disponibilidad bancaria 24x7, rendimiento a escala, cumplimiento regulatorio ni integración real.
- No acredita haber alcanzado caída de 8%, onboarding de 3 minutos, MAU 1,000–2,000 ni CAC S/ 10 con clientes reales.
- No es una auditoría de seguridad independiente.
- No hay LLM ni evaluación de un modelo generativo; el asistente es una política local determinista.
- El workflow está creado, pero no se afirma haberlo ejecutado en GitHub.
- No se creó commit, PR ni publicación pública. El Git root de esta máquina es el perfil del usuario; publicar desde allí sin aislar el proyecto sería inapropiado.
