"""Revisión técnica final de la feature, sin sustituir la aprobación humana."""
from crewai import Agent, Task
from crew.llm_config import llm

KEY = 'chapter'
LABEL = 'Chapter'
SPEC_FILE = 'chapter_review.md'
EXTRACT_CODE = False

INSTRUCTIONS = """Evalúa la feature usando exclusivamente
los criterios y las evidencias incluidas en esta tarea y en el contexto aprobado de los
roles anteriores. No afirmes que inspeccionaste archivos, ejecutaste comandos o consultaste
registros que no estén presentes en ese contexto. Cita la evidencia con el nombre de archivo,
símbolo, fragmento o salida disponible; si no existe, indícalo como no disponible.

## Revisión
1. Enumera cada criterio de aceptación y relaciónalo con arquitectura/contratos, modelo y
    persistencia cuando aplique, implementación, pruebas y seguridad.
2. Clasifica cada criterio como SATISFECHO, PARCIAL, INCUMPLIDO o NO VERIFICABLE. Detecta
    incompatibilidades de interfaces, tipos, entradas/salidas, errores, responsabilidades,
    validación, transacciones, integridad y dependencias cuando la evidencia permita hacerlo.
3. Distingue defectos comprobados, riesgos potenciales y aspectos no verificados. Revisa
    autenticación, autorización, entradas, secretos e información sensible según el alcance.
4. No trates un plan de pruebas, código de prueba propuesto ni la frase "NO EJECUTADO" como
    resultado. Registra pruebas fallidas, pendientes, ausentes e insuficientes por separado.

## Hallazgos
Prioriza por riesgo. Para cada hallazgo incluye identificador único (CH-n), categoría,
severidad (crítica/alta/media/baja), criterio afectado, evidencia verificable, problema e
impacto, responsable (rol/equipo solo si consta; en otro caso "Por asignar"), cambio concreto,
método objetivo de verificación y estado respaldado por evidencia. No marques una corrección
como verificada sin una comprobación disponible.

## Veredicto
Emite exactamente una línea: `VEREDICTO: APROBADO` o `VEREDICTO: RECHAZADO`.
APROBADO solo si todos los criterios aplicables están satisfechos con evidencia suficiente,
las pruebas obligatorias tienen resultados reales satisfactorios, los controles de seguridad
requeridos están verificados y no quedan hallazgos bloqueantes. En cualquier otro caso,
incluidas pruebas pendientes, evidencia insuficiente, criterios no verificables o controles
de seguridad sin verificar, emite RECHAZADO. La ausencia de evidencia nunca significa éxito.
No añadas un veredicto intermedio ni contradigas la línea de veredicto en otra sección.

## Aprobaciones humanas
Tu veredicto es una evaluación técnica, nunca una aprobación humana. No crees, elimines,
modifiques ni atribuyas aprobaciones, responsables, fechas u observaciones. Conserva lo que
esté expresamente documentado; si el checkpoint o sus metadatos no están en el contexto,
declara esos datos no disponibles y cualquier aprobación requerida no verificable como
pendiente. Si una aprobación documentada contradice un hallazgo, conserva ambas evidencias y
explica la discrepancia. No propongas cambiar autorizaciones ni auditorías.

## Informe chapter_review.md
Devuelve el informe Markdown completo, con estas secciones:
1. Veredicto final.
2. Resumen ejecutivo.
3. Alcance y limitaciones.
4. Matriz de trazabilidad (criterio, componentes, evidencia, pruebas y estado).
5. Hallazgos priorizados con todos sus campos.
6. Pruebas fallidas, pendientes o faltantes.
7. Riesgos de seguridad y controles pendientes.
8. Correcciones priorizadas.
9. Estado de aprobaciones humanas y metadatos disponibles/no disponibles.
10. Justificación explícita del veredicto basada en evidencias.

Si faltan criterios, contexto de roles o evidencia, decláralo expresamente y rechaza la
aprobación. No inventes archivos, líneas, responsables, resultados, contratos ni estados."""
agent = Agent(role='Senior Chapter Reviewer',
                  goal="Contrastar cada criterio con evidencia verificable y emitir un veredicto técnico justificado.",
                  backstory="Eres un Revisor técnico especialista. Tu evaluación no sustituye las decisiones humanas ni modifica sus registros.", llm=llm,
              verbose=True, allow_delegation=False)


def build_task(feature: dict) -> Task:
    return Task(description=INSTRUCTIONS + "\nFeature: " + str(feature.get("title", ""))
                + "\n" + str(feature.get("description", ""))
                + "\n" + str(feature.get("acceptance_criteria", [])),
                expected_output="Informe Markdown completo para chapter_review.md, con matriz de trazabilidad, "
            "hallazgos priorizados, pruebas y seguridad, estado de aprobaciones humanas y "
            "exactamente una línea VEREDICTO: APROBADO o VEREDICTO: RECHAZADO.", agent=agent)

