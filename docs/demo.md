# Recorrido de producto · seis minutos

Preparación: ejecutar el build y servidor descritos en [README](../README.md), abrir `http://127.0.0.1:8080` y tener a mano una carpeta donde descargar el QR. Todos los nombres y datos son ficticios.

## 0:00–0:40 · Problema y frontera

Explicar el abandono en checkout, onboarding lento y necesidad del comercio de saber si puede entregar. Mostrar el rótulo “Versión de prueba · Sin dinero real”. Dominio, ledger, estados e idempotencia son reales dentro de la aplicación; identidad, proveedores y fondos son simulados. El laboratorio está deshabilitado por defecto.

Las metas del trimestre no se presentan como resultados reales.

## 0:40–1:25 · W1: activar una wallet

1. Crear wallet de prueba.
2. Ingresar nombre, alias y celular inventados, por ejemplo un alias todavía no usado y nueve dígitos que comiencen con `9`.
3. Mostrar el consentimiento y el resumen de datos; finalizar sin seleccionar resultados de identidad.
4. Mostrar la wallet activa y los S/ 500 sintéticos.

Para volver a la cuenta, cerrar sesión e ingresar el alias en la pantalla de acceso. La revisión de cuentas pendientes pertenece a Operaciones, no al usuario que se registra.

## 1:25–2:15 · W2 y W4: pagar sin duplicar

1. Ingresar por alias `ana.demo` y enviar S/ 12.34 a `luis.demo`.
2. Mostrar nombre y monto antes de confirmar.
3. Mostrar resultado exitoso, referencia y acciones del comprobante, sin códigos ni ledger.
4. Cerrar comprobante y pulsar “Consultar mi pago”.
5. Mostrar la misma referencia y saldo sin un segundo cargo.
6. Pulsar “Volver a mi wallet” o “Hacer otro pago”; no hay cierre técnico de intentos para el usuario.

## 2:15–3:00 · W3: cobrar con QR

1. Cerrar sesión e ingresar como Café Miraflores.
2. Generar QR de S/ 8.90 y descargar el PNG.
3. Cerrar sesión e ingresar como pagador.
4. Cargar el archivo QR, verificar comercio/monto y confirmar.
5. Volver al comercio y mostrar el cobro exitoso. El historial permite filtrar fecha y referencia.

## 3:00–4:00 · W5: pendiente, ayuda y soporte

Esta sección y la siguiente son una evaluación técnica opcional, separada del recorrido del cliente. Reiniciar el servidor con `$env:WALLET_LAB_ENABLED = 'true'` para habilitarla; nunca habilitar el laboratorio en una exposición pública. El contrato está en [API](api.md). La suite Playwright crea estos estados sin exponer selectores al cliente.

1. Mediante la API de laboratorio, crear un pago de S/ 1 con `scenario: "PENDING"` y consultarlo desde Movimientos del pagador.
2. Mostrar reserva y advertencia de no repetir el pago.
3. Preguntar a Inti Guía por el resultado. Mostrar que solo explica.
4. Ingresar como Operaciones, abrir transacción y el laboratorio del proveedor.
5. Enviar callback `APPROVED`.
6. Volver al pagador/comercio: el estado cambia al consultar o dentro de 5 segundos.

Para demostrar devolución (disponible también sin laboratorio), en un pago exitoso abrir “Ayuda y revisión humana”, elegir “Solicitar devolución” y escribir un motivo. Operaciones registra una nota y aprueba. Se mantiene `SUCCEEDED` con marca de reverso y asientos compensatorios; el cliente ve “Dinero devuelto”.

## 4:00–5:00 · W6: detectar y corregir un descalce

1. Como Operaciones, abrir un pago y usar “Laboratorio del proveedor”.
2. Inyectar un fixture con estado o monto distinto, o proveedor ausente.
3. Ejecutar conciliación del día en Perú.
4. Mostrar referencia, tipo de diferencia y acción “Investigar”.
5. Restaurar fixture consistente y volver a conciliar: el descalce aparece resuelto.

No se modifica el ledger para esconder la diferencia.

## 5:00–6:00 · Evidencia y límites

- Mostrar Métricas MVP: tres meses, eventos reales de la demo y gasto por campaña; no se inventan MAU.
- Mostrar [evidencia](../evidence.md): 30 pruebas backend, 24 UI y reinicio persistente.
- Explicar por qué no hay LLM externo: asistente local verificable, sin herramientas financieras ni transmisión de datos.
- Declarar pendientes: identidad real, contratos de proveedores, antifraude, producción, revisión regulatoria y publicación en repositorio dedicado.
- Volver a iniciar sin `WALLET_LAB_ENABLED` para dejar la experiencia habitual sin laboratorio.

## Contingencia

Las pruebas UI no necesitan la base de la demo y arrancan su propio servidor. Si falla la presentación, ejecutar `npm test` desde `frontend` y abrir el reporte local con `npx playwright show-report`.

No utilizar datos reales, publicar la base H2 ni abrir el puerto en la red para resolver problemas de acceso.
