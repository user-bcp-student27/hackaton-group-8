# Arquitectura y controles del MVP

## Componentes y fuentes de verdad

- [Historias y épica](../packages): alcance y criterios de aceptación.
- [Wallet](../backend/src/main/java/pe/wallet/Wallet.java): reglas, estados, contabilidad, callbacks, soporte, conciliación, métricas y guía.
- [Store](../backend/src/main/java/pe/wallet/Store.java): transacciones JDBC y datos sintéticos iniciales.
- [Esquema inicial](../backend/src/main/resources/schema.sql): claves, referencias, índices y restricciones de unicidad.
- [API HTTP](../backend/src/main/java/pe/wallet/WalletResource.java): contrato de transporte y QR PNG.
- [Guardas HTTP](../backend/src/main/java/pe/wallet/HttpGuards.java), [JSON estricto](../backend/src/main/java/pe/wallet/JsonConfig.java) y [errores](../backend/src/main/java/pe/wallet/Errors.java).
- [Angular](../frontend/src/app.ts): vistas por rol, formularios, recuperación de intentos y consulta periódica.

No se agregaron microservicios, colas ni orquestadores a un problema local que no los necesita. Las integraciones externas son simulaciones explícitas, no implementaciones incompletas que aparentan conectarse a bancos.

## Estados y contabilidad

```text
Wallet:
 PENDING --KYC aprobado--> ACTIVE
 PENDING --KYC rechazado-> REJECTED

Pago:
 PENDING --APPROVED--> SUCCEEDED
 PENDING --DECLINED--> FAILED
 PENDING --TIMEOUT confirmado mock--> EXPIRED

SUCCEEDED + solicitud REVERSAL + aprobación humana + saldo destino suficiente
 -> mismo estado SUCCEEDED, reversed=true y asiento compensatorio
```

El resultado terminal del pago no se sobrescribe. Un callback contradictorio recibe `409`; no se intenta “arreglar” el dinero cambiando el estado arbitrariamente.

Cada fase tiene dos asientos:

| Fase | Débito | Crédito |
|---|---|---|
| Activación sintética | Tesorería | Wallet nueva |
| Reserva | Pagador | Escrow |
| Éxito | Escrow | Destino |
| Rechazo / expiración mock | Escrow | Pagador |
| Reverso aprobado | Destino | Pagador |

El saldo disponible es la suma del ledger por cuenta. No hay una segunda columna de saldo que pueda divergir. Un pendiente reduce el disponible y expone su monto reservado por separado.

## Invariantes y evidencia observable

| Riesgo | Protección | Comprobación |
|---|---|---|
| Peticiones simultáneas duplican cargos | Transacción serializable, escritor único y `UNIQUE(payer_id, idem_key)` | 16 requests concurrentes → una referencia y un cargo |
| Dos pagos consumen el mismo saldo | Verificación de saldo y asientos dentro de la misma transacción | Cinco pagos concurrentes no permiten sobregiro |
| Se pierde la respuesta tras commit | Mismo payload y clave guardados antes de enviar; consulta idempotente | Prueba UI aborta la respuesta después del commit, recarga y recupera referencia |
| Callback duplicado vuelve a liquidar | PK de `event_id`, fingerprint y estados terminales inmutables | Mismo evento y distinto evento equivalente no agregan asientos |
| Reutilización accidental de una clave | Fingerprint de destino, céntimos, canal, escenario y QR | Payload diferente → `409` |
| Falla parcial local | Rollback de pago, ledger, resultado mock y eventos en una transacción | Sin saldo negativo ni asientos desbalanceados |
| Reverso no autorizado | Caso explícito, rol OPS, nota humana, pago elegible y saldo suficiente | UI y API comprueban aprobación; el asistente no puede ejecutar |
| Conciliación engañosa con suma global cero | Comparación exacta de cuentas, fases e importes esperados | Un crédito dirigido a otra cuenta sigue siendo detectado |
| Pending tratado como éxito | Reserva sin liquidar, estado visible y descalce `PENDING_REVIEW` | No se considera conciliado hasta un resultado definitivo |
| Métricas engañosas | Eventos persistidos por intento, cohortes y nulos cuando no hay denominador | Abandono y callback entre meses tienen pruebas específicas |
| Caída del proceso pierde estado | H2 en archivo, commit y `WRITE_DELAY=0` | Reinicio abrupto conserva sesión, referencia, saldo y deduplicación |

Las pruebas ejecutan además la invariante de suma global del ledger igual a cero y ausencia de saldo negativo fuera de tesorería.

## Idempotencia y recuperación

Una clave pertenece a un pagador; dos pagadores pueden usar el mismo texto sin compartir transacciones. Las claves se mantienen mientras exista la base. No hay expiración automática que permita reutilizarlas y cobrar de nuevo.

La UI genera una clave para la ventana de revisión, registra el inicio del checkout y persiste clave + payload en `sessionStorage` antes del POST financiero. Un error de red no descarta ese intento. Si el servidor rechaza explícitamente la solicitud con un error de validación o permiso, no hay movimiento y se permite corregir el formulario.

Un usuario no puede cerrar desde la UI un intento todavía pendiente. Cerrar un intento terminal consulta primero su resultado y elimina solo el estado del formulario, nunca el pago ni el ledger.

Se deduplican por separado los eventos de callback. La auditoría asigna identificador de solicitud y correlación con el pago, y el reporte de conciliación cuenta los reintentos duplicados sin sumarlos como operaciones financieras.

No se promete ejecución “exactamente una vez” entre sistemas externos: no hay proveedores reales en este MVP.

## Conciliación

El operador elige fecha calendario de Perú y riel. Se comparan:

1. Intención y resultado actual de Wallet.
2. Registro independiente del proveedor simulado.
3. Asientos esperados del ledger, incluidas reserva, liquidación y reverso.

Tipos: `MISSING_PROVIDER`, `AMOUNT_MISMATCH`, `STATUS_MISMATCH`, `LEDGER_MISMATCH`, `PENDING_REVIEW`.

Los descalces son persistentes y únicos por pago/tipo. Pueden pasar de `OPEN` a `INVESTIGATING`. Solo una nueva comparación que ya no observa el problema los marca `RESOLVED`; no hay un botón que esconda una diferencia sin reparar su causa. Si reaparece, se reabre.

El resumen separa operaciones conciliadas, no conciliadas y previamente discrepantes ahora resueltas. “Resueltas” es un subconjunto de conciliadas, no un tercer grupo financiero que deba sumarse al total.

Los fixtures solo cambian la fuente mock del proveedor, nunca maquillan saldos. La operación de restauración vuelve a alinear esa fuente para volver a conciliar. Un descalce real del ledger requiere investigación, no reparación automática.

## Seguridad proporcional a una demo local

- Bind predeterminado `127.0.0.1`; sin CORS habilitado.
- Sesiones opacas aleatorias de 256 bits; hash SHA-256 en almacenamiento; vencimiento y revocación.
- Cookie `HttpOnly`, `SameSite=Strict`; sin `Secure` porque la demo usa HTTP local. HTTPS y cookie `Secure` son obligatorios antes de cualquier exposición.
- Un encabezado no-simple `X-Wallet-Request: 1` es requerido para mutaciones; otro origen no puede enviarlo sin una autorización CORS que no está configurada.
- Validación estricta de JSON, longitudes, formatos, dinero, límites, roles y propiedad.
- SQL parametrizado; no se interpola contenido del usuario en consultas.
- Tamaño de cuerpo e imagen acotados; dimensiones máximas antes de decodificar PNG.
- Interpolación Angular, sin renderizar HTML libre.
- Respuestas API sin caché; errores internos registrados en el servidor, sin trazas SQL para el cliente.
- Asistente con autorización, trazas sin texto libre y cero herramientas financieras.

**Frontera importante:** cualquier persona con acceso local al selector puede entrar como OPS. Esto es identidad simulada exigida para la demostración, no un sistema bancario protegido. No se ha realizado una auditoría externa ni se afirma cumplimiento regulatorio.

## Persistencia, alcance y evolución

H2 mantiene los datos entre reinicios; el build y las pruebas no borran la base de la demo. El esquema inicial usa `CREATE TABLE IF NOT EXISTS`. No es un motor de migraciones productivo: cambios futuros de esquema deben introducir migraciones versionadas y respaldo previo, no depender de recreación de tablas.

Los listados entregan hasta 200 resultados; contactos, hasta 100. El filtrado diario y la analítica se hacen en memoria a partir del store local. No hay promesa de escalabilidad a miles de usuarios concurrentes ni medición de disponibilidad 24x7.

Se prefirió H2 a PostgreSQL para no requerir servicio adicional, un único escritor a coordinación distribuida, polling a WebSocket y políticas deterministas a inferencia no verificable. Antes de múltiples instancias: transacciones/locking verificados en PostgreSQL, migraciones, outbox, reintentos de red y pruebas de carga.

## Evaluación de la experiencia

Revisión del 23 de octubre de 2026 contra la épica y las seis historias de usuario:

| Prioridad | Hallazgo inicial | Adaptación y estado |
|---|---|---|
| Crítica para publicar | El acceso por perfil/alias no comprueba identidad; cualquiera con acceso local puede entrar como OPS | Sigue siendo un bloqueo de lanzamiento. El formulario se simplifica, pero no se presenta como autenticación segura. No publicar en Internet |
| Alta | El pagador elegía si el proveedor aprobaba su pago o su identidad | Cliente sin `scenario` ni `kyc`; el servidor resuelve el resultado local. Inyección explícita bloqueada por defecto con `LAB_DISABLED` |
| Alta | Iniciar otro pago requería comprender claves y cerrar un intento terminal | Comprobante con acciones de volver o pagar de nuevo. La limpieza del formulario consulta primero el original; un pendiente no se descarta |
| Media | Comprobantes y ayuda exponían asientos, códigos y vocabulario técnico | Motivos y solicitudes traducidos; ledger y trazas reservados visualmente a OPS; instrucciones distintas para pagador y comercio |
| Media | La entrada principal era una vitrina de perfiles y mensajes promocionales | Acceso por alias y creación de cuenta como acciones principales; perfiles agrupados como evaluación; registro con datos, consentimiento y resumen |
| Media | La vista del comercio se perdía al recargar y no admitía reingreso por alias | Selección de vista por rol al iniciar/restaurar sesión y alias habilitado para cada rol |

### Trazabilidad funcional

- W1: tres pasos persistidos, corrección de datos, consentimiento, resumen y activación instrumentada. Ya no se pide al usuario decidir su validación.
- W2: destinatario, importe, límite y saldo posterior antes de confirmar; resultado, referencia, comprobante y acciones posteriores claras.
- W3: cobro QR con importe y vencimiento visibles; pendiente no autoriza entrega; actualización asíncrona y filtros por fecha/referencia conservados.
- W4: clave interna conservada antes del envío, recuperación tras respuesta perdida y una sola afectación financiera. No se elimina para ocultar errores.
- W5: distinción entre pago no realizado, pendiente y ejecutado; solicitud de devolución sujeta a decisión humana y seguimiento del caso.
- W6: conciliación diaria, descalces e investigación siguen en el área OPS. Las herramientas de simulación requieren habilitación explícita del laboratorio.

### Frontera de lanzamiento

Esta adaptación es una experiencia de producto sobre integraciones locales, no una habilitación financiera. Antes de usuarios reales se requieren autenticación con prueba de identidad y acceso OPS separado; KYC y consentimiento revisados por cumplimiento; proveedor contractual con callbacks firmados y manejo de estados desconocidos; antifraude, límites ratificados, HTTPS, protección de datos y recuperación operativa. La lectura QR disponible sigue siendo desde PNG, sin cámara ni interoperabilidad bancaria.

Las metas de abandono, onboarding, MAU y CAC son objetivos no acreditados por pruebas funcionales. Requieren cohortes reales, volumen, analítica y seguimiento trimestral. No se inventan resultados de negocio.

### Compatibilidad local

No cambia el esquema ni se borran pagos o saldos. Los nuevos clientes omiten campos de simulación. Para recuperar un intento guardado por la versión anterior que incluya `scenario`, habilitar temporalmente el laboratorio local, consultar el mismo intento y completar su seguimiento antes de volver al modo cliente; no borrar el intento ni enviar otro pago para resolverlo. H2 usa su `DataSource` para funcionar también al reiniciar perfiles de Quarkus.
