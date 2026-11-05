# API Entrenamiento automático al contratar (T248) — contrato

Versión 0.1.0 · 2026-11-05 · Fase 11 de `../../tareas.md` · Migración prevista: **V27** (T249) · Implementación: T250

Objetivo (decidido por el usuario el 2026-11-05): cuando un cliente empieza una suscripción, ya sea por pago con Stripe o por alta manual del profesional, recibe **automáticamente** el programa de la librería vinculado al plan. El profesional recibe un aviso en la campana y por email, y ve en su Inicio a los clientes que han pagado y no tienen entrenamiento.

Definición usada en todo el contrato: un cliente está **"pagado sin entrenamiento"** cuando tiene alguna suscripción `ACTIVE` o `PAST_DUE` y **ninguna asignación de workout con fecha de hoy en adelante**.

## 1. Programa vinculado a un plan de suscripción
Amplía `/api/subscription-plans` (`docs/api-negocio.md`):

| Campo | Dónde | Regla |
|---|---|---|
| `programId` (número o `null`) | `SubscriptionPlanRequest` | Opcional. Debe ser un programa **del mismo profesional que el plan** (`library_program.trainer_id = plan.trainer_id`). Si es ajeno o no existe → **404** "Programa no encontrado". No se valida en productos de pago único (allí no existe el campo) |
| `programId`, `programName` | `SubscriptionPlanDto` | `null` si no tiene. `programName` para mostrarlo en la tabla y el formulario |

- Si se **borra** el programa, los planes que lo usaban quedan con `programId = null` (FK `ON DELETE SET NULL`). Esto no afecta a las suscripciones ya empezadas.
- Cambiar el programa de un plan **solo afecta a las altas nuevas**. Las suscripciones en curso conservan lo que ya se les asignó.

## 2. Asignar un programa a un cliente (manual)
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| POST | `/api/clients/{clientId}/program-assignments` | `{ programId, startDate }` | 201 `ProgramAssignmentDto` |
| GET | `/api/clients/{clientId}/program-assignments` | — | `ProgramAssignmentDto[]`, la más reciente primero |

`ProgramAssignmentDto { id, programId, programName, clientId, startDate, endDate, workouts, skipped, origin: AUTO | MANUAL, subscriptionId, createdAt }`
- `workouts`: número de asignaciones de workout creadas.
- `skipped`: número de elementos del programa que no se asignan (ver reglas).
- `endDate`: `startDate + semanas·7 − 1`.

Permisos (los mismos que `POST /api/workouts/assign`): el profesional ofrece entrenamiento, el programa es suyo (o es ADMIN) y puede acceder al cliente (propio o responsable de entrenamiento, ADR-013). Un COLLABORATOR necesita el permiso "asignar rutinas". El cliente debe tener rol CLIENT.

Errores:

| Caso | Respuesta |
|---|---|
| Programa ajeno o inexistente; cliente ajeno o inexistente | 404 |
| `startDate` anterior a hoy | 400 |
| Programa sin ningún elemento WORKOUT | 409 "El programa no tiene workouts que asignar" |

Reglas de generación:
- Por cada elemento `WORKOUT` del programa se crea una `workout_assignment` en la fecha `startDate + (semana − 1)·7 + (día − 1)`. "Día 1…7" es relativo al inicio, no un día de la semana. Cada asignación queda ligada a su `program_assignment` (columna nueva `workout_assignment.program_assignment_id`).
- Los elementos `CARDIO`, `VIDEO`, `NUTRITION_PLAN` y `NOTE` **no se asignan en esta versión**: el cliente aún no tiene dónde verlos. Se cuentan en `skipped` y quedan en "Pendiente".
- Un elemento `WORKOUT` cuyo workout ya no existe se cuenta en `skipped`.
- El programa enlazado (`linkedProgramId`) **no se encadena** en esta versión. Al terminar el programa, el cliente vuelve a "pagados sin entrenamiento" con el motivo `PROGRAM_FINISHED`.
- Si el cliente ya tiene entrenamiento futuro, **no se borra nada**: el programa se añade. Pueden coincidir dos workouts el mismo día, y el profesional lo ve en el calendario del cliente.
- Auditoría: `ROUTINE_ASSIGNED`, con un resumen tipo "Programa X · N workouts desde AAAA-MM-DD".

## 3. Auto-asignación al empezar una suscripción
Se dispara en **las dos vías de alta**, que pasan por `SubscriptionService.start`: el pago con Stripe (webhook o conciliación, `docs/api-pagos-stripe.md`) y `POST /api/subscriptions`.

1. Al crear la suscripción se publica `SubscriptionStartedEvent(subscriptionId)`. Lo atiende un oyente **después del commit** y en **su propia transacción**: si la asignación falla, el pago y la suscripción **no se deshacen**.
2. Si el plan tiene programa y la suscripción todavía no tiene una `program_assignment`, se asigna el programa con `origin = AUTO`, `created_by = null` y `subscription_id` relleno.
   - Fecha de inicio: **el día siguiente al alta**, o la fecha de inicio de la suscripción si es posterior.
   - Las mismas reglas que en el punto 2. No se comprueban permisos de usuario porque no hay sesión, pero **el programa debe ser del profesional del plan**. Si no lo es (por un cambio de datos posterior), no se asigna.
3. **Las renovaciones no asignan nada.** La idempotencia es por suscripción: índice único `program_assignment(subscription_id)` cuando no es nulo. Un evento repetido no duplica la asignación.
4. Avisos (punto 4), siempre: con programa asignado, sin programa vinculado o si la asignación falló.

## 4. Avisos al profesional
Destinatario: el **responsable de entrenamiento** del cliente (`AccessService.trainingResponsibleOf`). Si no tiene, el profesional dueño del plan. Sin duplicar si los dos son la misma persona.

| Caso | Notificación (`type = SUBSCRIPTION_STARTED`, `refType = CLIENT`, `refId = clientId`) |
|---|---|
| Programa asignado | **Título:** "Nuevo cliente: {cliente}". **Cuerpo:** "Ha contratado {plan}. Programa {programa} asignado desde el {fecha}." |
| Plan sin programa | **Título:** "Nuevo cliente: {cliente}". **Cuerpo:** "Ha contratado {plan}. Aún no tiene entrenamiento: asígnale un programa." |
| Falló la asignación | **Título:** "Nuevo cliente: {cliente}". **Cuerpo:** "Ha contratado {plan}. No se ha podido asignar el programa {programa}: asígnalo a mano." |

- **Email:** el mismo texto, con `MailService.notification` (asunto = título) y un enlace a la app `{PUBLIC_APP_URL}/clientes`. Se envía tras el commit, como el resto de correos. Con `MAIL_PROVIDER=log` solo se escribe en el log.
- **Sin datos de salud** en la notificación ni en el email: ni cuestionario, ni medidas, ni nutrición. Solo el nombre del cliente, el plan, el programa y la fecha.
- Las renovaciones no avisan.

## 5. Pagados sin entrenamiento (tarjeta del Inicio)
| Método | Ruta | Respuesta |
|---|---|---|
| GET | `/api/clients/paid-without-training` | `PaidWithoutTrainingDto[]` (máx. 50, el alta más antigua primero) |

`PaidWithoutTrainingDto { clientId, clientName, planName, subscriptionId, subscriptionStatus: ACTIVE | PAST_DUE, subscribedAt, lastTrainingDate, reason }`
- `lastTrainingDate`: la fecha de la última asignación pasada, o `null`.
- `reason`:
  - `NO_PROGRAM`: el plan no tiene programa y nunca se le asignó nada.
  - `PROGRAM_FINISHED`: tuvo asignaciones, pero todas son pasadas.
  - `ASSIGN_FAILED`: el plan tiene programa y la suscripción no tiene `program_assignment`.

Alcance: los clientes a los que el usuario puede acceder (ADMIN: todos; TRAINER: propios y de los que es responsable de entrenamiento; COLLABORATOR: según sus permisos de Equipo). Un CLIENT recibe 403. La lista desaparece sola cuando el cliente tiene alguna asignación de hoy en adelante.

## 6. Datos (V27, T249) — PostgreSQL 16
- `subscription_plan.program_id BIGINT NULL` con FK a `library_program(id)` `ON DELETE SET NULL`, e índice.
- `program_assignment`:
  - `id`, `client_id` (FK `app_user`), `program_id` (FK `library_program`, `ON DELETE SET NULL`), `program_name` (copia, por si se borra el programa), `start_date`, `end_date`.
  - `subscription_id` (FK `client_subscription`, NULL), con índice único parcial cuando no es nulo.
  - `origin` (`AUTO` | `MANUAL`), `created_by` (NULL en `AUTO`), `created_at`.
  - Índices por `(client_id, start_date)`.
- `workout_assignment.program_assignment_id BIGINT NULL`: FK a `program_assignment` `ON DELETE SET NULL`, con índice.
- La reversión manual va en la cabecera de la migración.

## Pendiente (fuera de esta versión)
- Asignar los elementos `CARDIO`, `VIDEO`, `NUTRITION_PLAN` y `NOTE`. Hace falta que el cliente tenga dónde verlos.
- Encadenar el programa enlazado al terminar (`linkedProgramId`).
- Deshacer una asignación de programa: borrar sus `workout_assignment` futuras no completadas.
- Asignación automática al contratar un **producto de pago único**.
