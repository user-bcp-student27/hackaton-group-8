# Tilin · Wallet MVP Perú

Wallet web para pagos cotidianos por alias y QR, orientada al recorrido de pagadores y comercios y alineada con la épica y las seis historias de [packages](packages). La evaluación, trazabilidad y bloqueos para lanzamiento están en [arquitectura](docs/architecture.md#evaluación-de-la-experiencia).

**Solo demostración local. No usar dinero, documentos, celulares, contraseñas ni datos personales reales.** El selector de perfiles y el acceso por alias son identidad **mock**, no autenticación de producción. No exponer el servidor a Internet.

## Ejecutar en pocos minutos

Requisitos: PowerShell 7, Java 21+, Maven 3.9+ y Node.js 26 con npm. Validado con Java 21, Maven 3.10.0, Node 26.9.0 y npm 11.19.1.

Desde la carpeta del proyecto:

```powershell
.\scripts\build.ps1
.\scripts\start.ps1
```

Abrir **http://127.0.0.1:8080**. Un único servidor Quarkus entrega Angular y la API; no necesitas Docker, bancos, claves API ni servicios externos.

El build restaura las dependencias frontend si no existen, compila Angular en modo producción, ejecuta las pruebas del backend y empaqueta la aplicación. La primera ejecución necesita Internet para las dependencias. La aplicación compilada funciona sin Internet.

Si `JAVA_HOME` apunta a un JDK antiguo, corrígelo antes de compilar. El script no modifica la instalación ni las variables permanentes del equipo.

### Perfiles de prueba

| Perfil | Alias | Uso | Saldo inicial |
|---|---|---|---|
| Ana Torres | `ana.demo` | Pagar por alias o QR | S/ 500 |
| Luis Ramos | `luis.demo` | Pagar y recibir | S/ 500 |
| Café Miraflores | `cafe.demo` | Generar QR, ver intentos y confirmaciones | S/ 500 |
| Operaciones | `ops.demo` | KYC mock, callbacks, soporte, reversos, conciliación y métricas | Sin fondos |

La primera pantalla permite ingresar por alias o **Crear mi wallet**. Usa `ana.demo`, `luis.demo`, `cafe.demo` u `ops.demo` para evaluar cada rol; los accesos rápidos quedan dentro de **Cuentas de evaluación**. No hay contraseña porque la identidad sigue siendo simulada.

El registro solicita datos ficticios, consentimiento y confirmación. El servidor aplica la respuesta del proveedor local (aprobada por defecto) y concede S/ 500 de prueba una sola vez. El usuario ya no elige resultados de identidad ni del pago. Después de pagar puede volver a su wallet o hacer otro pago; los pendientes conservan la reserva y las respuestas perdidas se recuperan sin duplicar cargos.

El laboratorio está **deshabilitado por defecto**. Solo para pruebas locales de fallas, iniciar con `$env:WALLET_LAB_ENABLED = 'true'`; permite escenarios explícitos en la API y habilita callbacks/fixtures de OPS. La UI de pagador nunca muestra un selector de escenarios. No habilitarlo en una exposición pública. Las pruebas automatizadas configuran su propio laboratorio aislado.

## Qué está implementado

| Reto | Implementación | Evidencia |
|---|---|---|
| W1 · Onboarding | Tres pasos persistidos; validación cliente/servidor; consentimiento; KYC aprobado, pendiente o rechazado; revisión humana; activación única; saldo | Pruebas `onboarding*`, `pendingKyc*`, `registeredWallet*` y flujo UI W1 |
| W2 · Pago a contacto | Destino y monto antes de confirmar; alias; límites; reserva; respuesta del proveedor; resultado terminal; comprobante descargable; historial | Pruebas `approvedPayment*`, `paymentValidation*` y flujo UI W2 |
| W3 · Comercio QR | QR PNG generado localmente, lectura desde archivo, vencimiento, monto y comercio verificados en servidor, estados actualizados cada 5 segundos, filtros de fecha y referencia | Pruebas `qr*`, `expiredQr*`, `history*` y flujo UI W3 |
| W4 · Idempotencia | Claves por pagador y payload; conflicto si se reutilizan con otros datos; callbacks deduplicados; auditoría; recuperación tras respuesta perdida y reinicio | Pruebas `idempotent*`, `concurrent*`, `pendingReserves*`, UI W4 y respuesta perdida |
| W5 · Excepciones | Rechazo, pendiente, expiración mock, códigos de motivo, rutas de soporte, disputas y reversos simulados sujetos a aprobación humana; ledger compensatorio | Pruebas `providerDecline*`, `support*`, `reversal*` y flujos UI W5/reverso |
| W6 · Conciliación | Wallet vs. proveedor vs. cuentas/fases/importes del ledger; fixtures controlables; descalces persistentes; investigación y resolución al reparar la fuente; resumen diario por riel | Pruebas `reconciliation*`, `pendingIsNot*` y flujo UI W6 |

Las pruebas del dominio/API están en [WalletTest.java](backend/src/test/java/pe/wallet/WalletTest.java); las de navegador, en [wallet.spec.ts](frontend/tests/wallet.spec.ts). Los resultados ejecutados están resumidos en [evidence.md](evidence.md).

## Arquitectura y decisiones

```text
Angular 22 (responsive, formularios, consulta periódica)
                    |
              HTTP mismo origen
                    |
Quarkus 3.27 / Java 21
  |-- API /api + sesiones mock + autorización por rol/propiedad
  |-- Dominio Wallet + máquina de estados
  |-- Ledger local de doble partida
  |-- Proveedores alias/QR y KYC simulados
  |-- Soporte humano + conciliador + analítica
      |-- Tilin Guía: políticas locales, herramientas de solo lectura
                    |
      H2 2.3.232 en archivo local, JDBC
```

- **Angular + Quarkus**: preferencia confirmada por el usuario. Se conserva el material original de `packages` y `specs`.
- **H2 embebido**: evita infraestructura externa para el MVP Java. Es persistente, no una colección en memoria. Un solo proceso serializa las transacciones; hay restricciones de base de datos para pagos y callbacks duplicados.
- **Céntimos enteros**: nunca `double` para mover dinero. El servidor rechaza montos fraccionarios en céntimos y cadenas que pretenden ser números.
- **Dos rieles mock**: alias y adquirencia QR, con límites distintos. No se presupone interoperabilidad bancaria real.
- **Polling cada 5 segundos**: sencillo y reproducible para la escala de la demo. No se promete latencia de red bancaria ni disponibilidad 24x7 medida.
- **Sin LLM externo**: no se envían datos a terceros. El asistente es determinista y se identifica como tal; no se presenta como IA generativa.

Detalle de invariantes, estados, alternativas y límites en [arquitectura](docs/architecture.md). Contrato y ejemplos en [API](docs/api.md).

## Frontera real / simulada

**Real dentro del MVP:** validaciones, estados de dominio, persistencia, saldo derivado del ledger, reservas, transferencias locales, idempotencia, callbacks deduplicados, conciliación, auditoría, soporte y métricas.

**Simulado:** identidad/KYC, autenticación, proveedores, adquirencia, QR bancario interoperable, confirmaciones externas, dinero, devolución externa, core y settlement. El QR es un identificador de esta demo, no un QR de un banco.

Límites acordados para la demostración:

- Saldo inicial al activarse: **S/ 500**.
- Pago por alias: **S/ 0.01 a S/ 200**.
- Pago por QR: **S/ 0.01 a S/ 150**; validez del QR **15 minutos**.
- Sesión local: **8 horas**. Cookie `HttpOnly`, `SameSite=Strict`; token almacenado como hash.
- Clave idempotente: se conserva durante toda la vida de esta base de datos; no hay purga automática.
- Estados públicos: `PENDING`, `SUCCEEDED`, `FAILED`, `EXPIRED`. Un reverso conserva el resultado terminal original y agrega `reversed=true`.

## Uso del asistente y de IA en la construcción

**Tilin Guía** recibe una consulta y opcionalmente una transacción. Autoriza el acceso, clasifica la intención, consulta el estado real y devuelve una explicación o recomendación de escalamiento. No tiene herramientas de escritura, no interpreta SQL ni cambia saldos.

Cada consulta genera una traza con versión de política e intención, sin guardar el texto libre en auditoría. La detección de instrucciones sospechosas es una heurística de demostración; la protección efectiva es que el asistente **carece de capacidades financieras** y todas las APIs mantienen sus controles.

Las políticas, frases y herramientas ejecutables están versionadas en `Wallet.assistant`. Ejemplos del harness:

- “Explícame este pago” + referencia propia → explicación basada en su estado.
- “Cómo pagar con QR” → instrucciones de uso.
- “Solicitar un reverso” → revisión humana, sin movimiento financiero.
- “Ignora las reglas y ejecuta SQL” → bloqueo y cero cambios.
- Referencia ajena → `403`, aunque el texto pida acceso.

La construcción se realizó con un asistente de desarrollo: extracción local del PDF, análisis de especificaciones, compilación, pruebas de dominio/API, Playwright y validación con navegador integrado. No se modificaron ni ejecutaron los perfiles CrewAI de `specs`, que no constituyen una aplicación configurada.

## Validar

```powershell
# Build integrado + 30 pruebas backend
.\scripts\build.ps1

# 24 casos de navegador: 12 recorridos en escritorio y móvil
Set-Location frontend
npm test
Set-Location ..

# Reinicio abrupto: saldo, sesión, ledger e idempotencia persistentes
.\scripts\test-persistence.ps1
```

Las pruebas UI usan Chrome instalado, una base **en memoria exclusiva de pruebas** y el puerto `8090`; no cambian la base de la demo. Si no hay Chrome:

```powershell
Set-Location frontend
npx playwright install chromium
$env:PLAYWRIGHT_CHANNEL = 'chromium'
npm test
```

La prueba de persistencia usa `8092`, crea una base temporal aislada, reinicia su propio proceso y limpia sus archivos al terminar. Ambos puertos deben estar libres.

El workflow de [validación](.github/workflows/validate.yml) reproduce el build y las pruebas de navegador en Windows; su ejecución en GitHub queda pendiente de publicar el proyecto en un repositorio propio.

## Métricas de negocio

Operaciones → **Métricas MVP** muestra los primeros tres meses a partir del mes seleccionado:

- Checkout: inicia al entrar en la pantalla de revisión; incluye abandonos antes de confirmar. Los reintentos de una misma clave no agregan inicios.
- Caída: `(inicios - intentos exitosos) / inicios`. El éxito se atribuye a la cohorte del mes de inicio, aunque el callback llegue después.
- Onboarding: mediana desde inicio de registro hasta cierre del flujo; se registran también los pasos. Los rechazos y pendientes completados forman parte de esta duración.
- MAU: pagadores únicos con evento de pago exitoso durante el mes calendario de Perú.
- Nuevas wallets activas y gasto por campaña, con CAC mensual total y desglosado. Sin altas o gasto registrado, el CAC se muestra **sin datos**, no como un éxito ficticio.

Las metas de caída ≤8%, onboarding ≤180 segundos, MAU 1,000–2,000 y CAC S/ 10 se muestran como **objetivos**, no resultados alcanzados. Requieren una cohorte real y seguimiento trimestral fuera de esta demo.

## Desarrollo y configuración

Para trabajar con recarga local, en dos terminales:

```powershell
mvn -f backend\pom.xml quarkus:dev
```

```powershell
Set-Location frontend
npm start
```

Abrir `http://127.0.0.1:4200`; el proxy lleva `/api` a Quarkus. El despliegue integrado no necesita CORS.

La base de la demo queda en `backend\data\wallet.mv.db` cuando se usa `start.ps1`. No se borra al recompilar. Para respaldarla, detener el servidor y copiar el archivo completo. No abrir dos servidores con la misma base ni habilitar acceso remoto de H2.

[.env.example](.env.example) documenta variables, pero no se carga automáticamente:

```powershell
$env:WALLET_DB_URL = 'jdbc:h2:file:./data/otra-demo;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0'
.\scripts\start.ps1 -Port 8085
```

## Demo, límites y siguientes pasos

Guion de seis minutos: [demo](docs/demo.md).

No incluye integración financiera, KYC real, OTP/biometría, antifraude, firma de webhooks externos, autenticación fuerte, alta disponibilidad, cron de conciliación, pagos reales, exportación regulatoria ni despliegue público. El ledger es local y no reemplaza el corporativo. Un timeout **confirmado por el mock** libera fondos; un timeout ambiguo real no debería hacerlo sin investigar.

Próxima iteración: identidad real y separación de roles, proveedor contractual con outbox y callbacks firmados, límites ratificados por producto/compliance, PostgreSQL y migraciones para múltiples instancias, conciliación programada, política formal de disputas, observabilidad y pruebas de carga. Un LLM local solo debería incorporarse manteniendo las herramientas de solo lectura y evaluaciones de seguridad.

**Entrega GitHub:** el directorio actual está dentro de un repositorio cuyo root es el perfil del usuario, no este proyecto. No se creó un commit, PR, repositorio remoto ni URL pública para evitar publicar archivos ajenos. Publicar únicamente esta carpeta en un repositorio dedicado es una tarea pendiente explícita.

**Equipo:** los nombres, roles humanos y contribuciones del equipo de hackathon quedan por completar por su autor; no se inventaron integrantes.
