# API · Solicitudes de cambio, notificaciones, objetivos nutricionales y logros del cliente

Versión 1.0.0 · 2026-10-29 · Tareas T152–T157 · Decisión: `docs/adr/ADR-012-solicitudes-notificaciones-logros.md` (raíz del proyecto)

Todas las rutas exigen JWT. Errores con el formato habitual (`ErrorResponse`): 400 validación, 403 sin permiso, 404 no existe o no es tuyo, 409 conflicto de estado.

## 1. Solicitudes de cambio

### Tipos
| `kind` | `action` | Campos obligatorios |
|---|---|---|
| `TRAINING` | `ADD_EXERCISE` | `assignmentId`, `exerciseId`, `sets`, `reps`, `weightKg` |
| `TRAINING` | `REPLACE_EXERCISE` | `assignmentId`, `workoutItemId`, `exerciseId` |
| `TRAINING` | `MODIFY_EXERCISE` | `assignmentId`, `workoutItemId` y al menos uno de `sets`, `reps`, `weightKg`, `restSeconds` |
| `TRAINING` | `REMOVE_EXERCISE` | `assignmentId`, `workoutItemId` |
| `NUTRITION` | `CHANGE_TARGETS` | `planId` y al menos uno de `kcal`, `proteinG`, `carbsG`, `fatG` |
| `NUTRITION` | `REPLACE_FOOD` | `planId`, `mealId`, `foodId`, `grams` |

`reason` (opcional, máx. 500) en todos. Estados: `PENDING`, `ACCEPTED`, `REJECTED` (finales).

### `POST /api/change-requests/training` — solo CLIENT
```json
{ "action": "MODIFY_EXERCISE", "assignmentId": 12, "workoutItemId": 40, "exerciseId": null,
  "sets": 4, "reps": 8, "weightKg": 60, "restSeconds": null, "reason": "Me molesta el hombro con 10 reps" }
```
- 201 → `ChangeRequestDto`. **No modifica la rutina.** Notifica al profesional responsable.
- 403 si la asignación no es del cliente; 400 si el ejercicio no está en la rutina o el ejercicio propuesto no es visible para su entrenador; 409 si ya hay una solicitud igual pendiente o la asignación está completada.

### `POST /api/change-requests/nutrition` — solo CLIENT
```json
{ "action": "CHANGE_TARGETS", "planId": 3, "kcal": 2300, "proteinG": 150, "carbsG": null, "fatG": null, "reason": "Entreno 5 días" }
```
- 201 → `ChangeRequestDto`. **No modifica el plan.** Notifica al profesional del plan. 403 si el plan no es suyo; 409 si ya hay una igual pendiente o si el plan no es el vigente.

### `GET /api/change-requests/mine?status=` — solo CLIENT
Las solicitudes del cliente, de la más reciente a la más antigua.

### `GET /api/change-requests?status=` — TRAINER, ADMIN
Bandeja del profesional: las que le corresponden (ADMIN: todas). `status` opcional.

### `GET /api/clients/{clientId}/change-requests` — TRAINER (sus clientes), ADMIN
Para la ficha del cliente y la vista de cliente del admin.

### `POST /api/change-requests/{id}/accept` y `/reject` — profesional responsable o ADMIN
```json
{ "note": "OK, bajamos a 8 repeticiones" }
```
- `accept`: aplica el cambio en la misma transacción y devuelve el `ChangeRequestDto` con `status: ACCEPTED`, `appliedWorkoutId` y `copiedWorkout` (true si se creó una copia personal de la rutina compartida). Notifica al cliente.
- `reject`: no toca el plan. Notifica al cliente.
- 403 si quien decide no es el responsable ni ADMIN, o es el propio cliente; 409 si la solicitud ya no está pendiente, o si el ejercicio afectado ya no está en la rutina vigente (la solicitud sigue `PENDING`).

### `GET /api/change-requests/{id}/history`
Cliente dueño, profesional responsable o ADMIN. Lista de `{ at, actorId, actorName, action, note }`.

### `ChangeRequestDto`
```
id, kind, action, status, clientId, clientName, professionalId, professionalName,
assignmentId, workoutId, workoutName, workoutItemId, targetExerciseName,
exerciseId, exerciseName, sets, reps, weightKg, restSeconds,
planId, planName, planVersion, outdated, mealId, foodId, foodName, grams, kcal, proteinG, carbsG, fatG,
reason, decisionNote, createdAt, decidedAt, decidedById, appliedWorkoutId, copiedWorkout
```
`outdated`: en nutrición, la versión del plan cambió desde que se creó la solicitud.

## 2. Plan nutricional: objetivos y versión

`PlanDto` añade `targetProteinG`, `targetCarbsG`, `targetFatG`, `version`, `updatedAt` y `current` (true en el plan vigente: el más reciente del cliente).

### `PUT /api/nutrition/plans/{planId}` — TRAINER con nutrición activada (profesional del plan) o ADMIN
```json
{ "name": "Definición", "dailyKcal": 2100, "targetProteinG": 160, "targetCarbsG": 200, "targetFatG": 60, "notes": null }
```
Sube `version`. Notifica al cliente (`PLAN_UPDATED`). Un CLIENT recibe 403.

`POST /api/nutrition/plans` acepta los mismos campos de objetivos (opcionales).

## 3. Notificaciones

| Ruta | Descripción |
|---|---|
| `GET /api/notifications?unreadOnly=false` | Las 50 más recientes del usuario |
| `GET /api/notifications/unread-count` | `{ "count": 3 }` |
| `POST /api/notifications/{id}/read` | 204. 404 si no es suya |
| `POST /api/notifications/read-all` | 204 |

`NotificationDto`: `id, type, title, body, refType, refId, createdAt, readAt`.
Tipos: `CHANGE_REQUEST_CREATED`, `CHANGE_REQUEST_ACCEPTED`, `CHANGE_REQUEST_REJECTED`, `PLAN_UPDATED`, `ACHIEVEMENT_UNLOCKED`.

## 4. Logros del cliente

| Ruta | Quién |
|---|---|
| `GET /api/my/achievements` | CLIENT |
| `GET /api/clients/{clientId}/achievements` | TRAINER (sus clientes), ADMIN |

Ambas evalúan y guardan los desbloqueos nuevos antes de responder (ADR-007 + ADR-012).

```
ClientAchievementsDto {
  stats: { workoutsCompleted, activeDays, currentStreak, bestStreak, totalSets, totalReps, totalVolumeKg, lastActivity },
  points, level, levelName, xpInLevel, xpForNextLevel, unlockedCount, totalCount,
  almost: [ClientAchievementDto]   // hasta 3 bloqueados con más progreso
  categories: [{ id, name, icon, achievements: [ClientAchievementDto] }]
}
ClientAchievementDto { id, name, description, groupLabel, badge, badgeUrl, tier, points,
                       status: UNLOCKED | LOCKED | NO_DATA, unlockedOn, progress, target, percent }
```
`progress` y `target` son nulos cuando no se pueden medir. `NO_DATA`: no hay datos para comprobar la condición (cardio, nutrición, vídeos).
