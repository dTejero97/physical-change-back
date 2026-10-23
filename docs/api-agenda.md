# Contrato API · Sección Agenda (v1.4.0)

Tipos de sesión, eventos (sesiones, reuniones, recordatorios y privados), reservas, KPIs, horarios de citas y actividad.
Decisiones de modelo: `docs/adr/ADR-002-agenda-eventos.md` (raíz del proyecto). Pantallas: `docs/fase4-pantallas-v2.md`.
Migración: `src/main/resources/db/migration/V3__agenda.sql`. Swagger: `/swagger-ui.html` (grupo "Agenda").

Reglas comunes
- JWT obligatorio. Gestión (crear, editar, borrar, KPIs, actividad): TRAINER y ADMIN; cada profesional solo ve lo suyo.
- Errores `{ timestamp, status, error, message }`: 400 validación, 403 sin permiso, 404 no existe, 409 conflicto.
- Fechas `yyyy-MM-dd`, horas `HH:mm`, instantes `yyyy-MM-ddTHH:mm:ss` (hora local del servidor).
- `cover`: clave de portada predefinida (`brand`, `gym`, `mat`, `weights`, `yoga`, `run`) o `file:<id>` (archivo propio subido con `/api/files`, ver api-libreria-v2.md). `coverUrl` es la URL firmada cuando es un archivo.
- Paleta de color de tipos de sesión (9): `#5B6CF0 #8B3FD1 #C2187A #F49AC8 #F07A12 #E9D85A #3FAE6A #1F6B4F #93ACD6`.

## Tipos de sesión — `/api/agenda/session-types`
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/agenda/session-types` | — | `SessionTypeDto[]` por nombre. Si el profesional no tiene ninguno se crean los 5 por defecto (Nutrición, Fisioterapia, CrossTraining, Yoga, Entrenamiento personal) |
| POST | `/api/agenda/session-types` | `SessionTypeRequest` | 201 `SessionTypeDto` · 409 nombre repetido |
| PUT | `/api/agenda/session-types/{id}` | `SessionTypeRequest` | `SessionTypeDto` |
| DELETE | `/api/agenda/session-types/{id}` | — | 204 · 409 si está en uso |
| POST | `/api/agenda/session-types/{id}/replace` | `{ targetId }` | `{ moved }`: eventos desde hoy y horarios pasan a `targetId` |

`SessionTypeRequest { name (1-60), color (paleta) }`
`SessionTypeDto { id, name, color, createdAt, usage }` · `usage` = eventos + horarios que lo usan (en uso si > 0).

## Eventos — `/api/agenda/events`
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/agenda/events?from&to` | filtros opcionales: `kinds`, `modes`, `sessionTypeIds`, `bookable`, `recurrent` (listas separadas por comas) | `EventDto[]` por inicio |
| GET | `/api/agenda/events/{id}` | — | `EventDetailDto` |
| POST | `/api/agenda/events` | `EventRequest` | 201 `EventDto[]` (una por ocurrencia) |
| PUT | `/api/agenda/events/{id}?scope=ONE\|FOLLOWING` | `EventRequest` (se ignora `recurrence`) | `EventDto[]` actualizados |
| DELETE | `/api/agenda/events/{id}?scope=ONE\|FOLLOWING` | — | 204 |
| GET | `/api/agenda/birthdays?from&to` | — | `BirthdayDto[] { clientId, name, date }` (clientes activos con `birthDate`) |

`EventRequest { kind, title (1-120), emoji? (≤16), description? (≤2000), mode?, location? (≤200), meetingUrl? (https, ≤500), showAs?, sessionTypeId?, cover? (≤300), date, startTime, endTime, recurrence?, capacity?, bookable, bookableTagIds[], clientIds[] }`
- `kind`: SESSION | MEETING | REMINDER | PRIVATE. `mode` (IN_PERSON | ONLINE) obligatorio en SESSION y MEETING. `sessionTypeId` y `cover` obligatorios en SESSION. `showAs` (BUSY | FREE) obligatorio en PRIVATE.
- `endTime` > `startTime`. `recurrence { weekdays: 1..7 (lunes=1), until }`: hasta 1 año y 200 ocurrencias.
- Solo SESSION: `capacity` (1-500, null = sin límite), `bookable` NO | YES | TAGS (`bookableTagIds` propios), `clientIds` (clientes propios reservados al crear; si superan las plazas, el resto queda en cola).

`EventDto { id, kind, title, emoji, description, mode, location, meetingUrl, showAs, sessionTypeId, sessionTypeName, color, cover, coverUrl, start, end, recurrent, seriesKey, capacity, booked, waitlist, bookable, bookableTagIds, organizerId, organizerName }`
`EventDetailDto { event: EventDto, bookings: BookingDto[] }`

## Reservas
| Método | Ruta | Quién | Cuerpo | Respuesta |
|---|---|---|---|---|
| POST | `/api/agenda/events/{id}/bookings` | dueño (cualquier cliente suyo) o CLIENT (él mismo, si la sesión es reservable para él) | `{ clientId }` | 201 `BookingDto` (`BOOKED`, o `WAITLIST` si no hay plazas) · 409 ya reservado |
| PATCH | `/api/agenda/bookings/{id}` | dueño; CLIENT solo `CANCELLED` de la suya | `{ status }` | `BookingDto`. Al cancelar, el primero en cola pasa a `BOOKED` |

`BookingDto { id, eventId, clientId, clientName, status, createdAt }` · `status`: BOOKED | WAITLIST | CANCELLED | ATTENDED | NO_SHOW.

## KPIs — `GET /api/agenda/stats?from&to` (mismos filtros que la lista de eventos)
`StatsDto { totalSessions, inPerson, online, booked, cancelled, waitlist, attended, attendancePct, vouchersRedeemed }`
Solo cuentan eventos SESSION. `booked` = BOOKED + ATTENDED + NO_SHOW; `attendancePct` = attended / booked (0 si no hay). `vouchersRedeemed` = 0 hasta que existan bonos (T10).

## Horarios de citas — `/api/agenda/schedules`
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/agenda/schedules` | — | `ScheduleDto[]` (más recientes primero) |
| GET | `/api/agenda/schedules/{id}` | — | `ScheduleDetailDto { schedule, availableFor: MemberDto[] }` |
| POST | `/api/agenda/schedules` | `ScheduleRequest` | 201 `ScheduleDto` |
| PUT | `/api/agenda/schedules/{id}` | `ScheduleRequest` | `ScheduleDto` |
| PATCH | `/api/agenda/schedules/{id}/active` | `{ active }` | `ScheduleDto` |
| DELETE | `/api/agenda/schedules/{id}` | — | 204 |

`ScheduleRequest { title (1-120), emoji?, mode, source?, meetingUrl?, location?, sessionTypeId, description?, cover, startDate, endDate, durationMin (10-240), slots[], visibility, tagIds[] }`
- `source` (VIDEO | PHONE) obligatorio si `mode = ONLINE`. `endDate` ≥ `startDate` y como mucho 1 año después.
- `slots`: 1-50 franjas `{ weekday 1..7, from HH:mm, to HH:mm }` con `to` > `from`.
- `visibility` ALL | TAGS (con `tagIds` propios).
`ScheduleDto { id, title, emoji, mode, source, meetingUrl, location, sessionTypeId, sessionTypeName, color, description, cover, coverUrl, startDate, endDate, durationMin, slots, visibility, tagIds, active, organizerId, organizerName, createdAt }`
`availableFor`: clientes activos del profesional (todos, o los que tienen alguna de las etiquetas).
Duplicar = el frontend abre el formulario relleno y hace POST.

## Actividad — `GET /api/agenda/activity?from&to&profile=ALL|CLIENTS|TEAM&actorIds=`
`ActivityDto[] { id, at, actorId, actorName, actorKind, action, eventTitle, eventEmoji, eventStart, recurrent }` (más recientes primero).
`actorKind`: TRAINER | CLIENT | TEAM (TEAM llegará con Equipo, T28). `action`: EVENT_CREATED | EVENT_UPDATED | EVENT_DELETED | BOOKED | WAITLISTED | BOOKING_CANCELLED | ATTENDED | NO_SHOW.

## Compatibilidad
- `/api/appointments` sin cambios. `/api/calendar` añade, solo para CLIENT, entradas `type: "EVENT"` (sesiones con reserva BOOKED/ATTENDED) con los mismos campos de siempre.
