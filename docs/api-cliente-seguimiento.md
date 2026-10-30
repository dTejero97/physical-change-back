# API · Seguimiento diario del cliente y sesiones reservables

- **Versión del contrato:** 1.0.0, 2026-10-30 (T201).
- **Especificación:** `docs/diseno-fase6.md` §3.4.1 (Diario), §3.5 (Calendario) y §3.8 (D1, D2, D3 y D17), en la raíz del proyecto.
- **Migración:** `V16__seguimiento_diario.sql` (V15 es la del generador de contenido; Stripe, T127, pasa a V17).
- **Swagger:** grupo "Seguimiento diario".

## Reglas comunes
- JWT obligatorio. Errores `{ timestamp, status, error, message }`: 400 validación, 403 sin permiso, 404 no existe, 409 conflicto.
- Fechas `yyyy-MM-dd`; meses `yyyy-MM`; instantes `yyyy-MM-ddTHH:mm:ss`.
- **Zona horaria (ADR-008):** "hoy" se calcula en `app.timezone` (`Europe/Madrid` por defecto), no en UTC ni en el reloj del
  navegador. Las fechas se guardan sin zona, en hora de España.
- `/api/me/**` es siempre el cliente del token (rol CLIENT): no admite `clientId` y nunca devuelve datos de otro cliente.
- El profesional **solo lee** el diario de su cliente (ver "Profesional"). Nadie más que el cliente lo escribe.

## Modelo: `DailyLogDto`
```json
{
  "date": "2026-10-30",
  "weightKg": 75.5,
  "sleepMinutes": 420,
  "mood": 4,
  "restDay": false,
  "logged": true,
  "updatedAt": "2026-10-30T08:12:00"
}
```
| Campo | Tipo | Notas |
|---|---|---|
| `date` | fecha | Día del registro |
| `weightKg` | número \| null | Peso del día, leído de `progress_entry` (el mismo dato que `/api/progress`) |
| `sleepMinutes` | entero \| null | 0–1440. La pantalla lo muestra como "7 h 00 min" |
| `mood` | entero \| null | Ánimo al levantarse: 1 Mal · 2 Regular · 3 Normal · 4 Bien · 5 Genial |
| `restDay` | booleano | Día de descanso marcado por el cliente; `false` si no hay registro |
| `logged` | booleano | `true` si el día tiene algún dato: peso, sueño, ánimo o descanso |
| `updatedAt` | instante \| null | Último guardado del diario (sueño, ánimo, descanso). `null` si solo hay peso o nada |

## D1 · Registro del día — CLIENT

### `GET /api/me/daily-log/{date}`
- 200 → `DailyLogDto`. Un día sin datos devuelve 200 con todo a `null`, `restDay: false` y `logged: false` (nunca 404).
- 400 si `date` no es una fecha válida.

### `PUT /api/me/daily-log/{date}` ("Guardar mi día")
Cuerpo `DailyLogRequest`:
```json
{ "weightKg": 75.5, "sleepMinutes": 420, "mood": 4, "restDay": false }
```
| Campo | Validación | Si llega `null` o no llega |
|---|---|---|
| `weightKg` | 20–500 | **No se toca** el peso del día (no se borra) |
| `sleepMinutes` | 0–1440 | Se vacía |
| `mood` | 1–5 | Se vacía |
| `restDay` | booleano | `false` |

- 200 → `DailyLogDto` del día, ya guardado.
- **Una fila por cliente y día** (`client_daily_log`, único por `(client_id, log_date)`): repetir el PUT actualiza, nunca duplica.
- Sueño, ánimo y descanso se **sustituyen** con lo enviado. Si los tres quedan vacíos (`null`, `null`, `false`), se borra la fila del diario.
- `weightKg` escribe el peso del día en `progress_entry` con la misma regla que `POST /api/progress` (inserta o actualiza el
  registro de ese día; la grasa y la nota del registro se conservan). Cuenta para los logros de peso (ADR-012).
- 400 si `date` es **posterior a hoy** (en Europe/Madrid), anterior a `2000-01-01`, o algún valor está fuera de rango.
- 409 si dos guardados del mismo día chocan a la vez (reintentar).

## D3 · Resumen mensual — CLIENT

### `GET /api/me/daily-log?month=2026-10`
```json
{
  "month": "2026-10",
  "today": "2026-10-30",
  "days": [
    { "date": "2026-10-01", "weightKg": null, "sleepMinutes": null, "mood": null, "restDay": false, "logged": false, "updatedAt": null },
    { "date": "2026-10-02", "weightKg": 75.9, "sleepMinutes": 450, "mood": 3, "restDay": false, "logged": true, "updatedAt": "2026-10-02T08:01:00" }
  ]
}
```
- `days` trae **todos los días del mes**, en orden (28–31 elementos), también los vacíos y los futuros, con el mismo `DailyLogDto`.
- `today` es la fecha de hoy en Europe/Madrid: el Frontend la usa para desactivar "›" y los días futuros, sin fiarse del reloj del dispositivo.
- 400 si falta `month` o no tiene el formato `yyyy-MM`.
- **Fuera de esta versión:** los puntos de check-in diario y semanal del calendario (D4, D5) no existen todavía; se añadirán como
  campos nuevos de `DailyLogDto` en una versión menor (compatible). Mientras, el calendario solo pinta "con diario" (`logged`) y
  "descanso" (`restDay`).

## Profesional (solo lectura) — ADMIN y TRAINER
| Método | Ruta | Respuesta |
|---|---|---|
| GET | `/api/clients/{clientId}/daily-log/{date}` | `DailyLogDto` |
| GET | `/api/clients/{clientId}/daily-log?month=yyyy-MM` | Resumen mensual, igual que el del cliente |

- Permiso: el de `AccessService.assertCanAccessClient` (como `/api/progress/client/{id}`): ADMIN siempre; TRAINER solo si es su
  entrenador (`trainerId`). 403 si no es su cliente; 404 si el cliente no existe.
- **Decisión T201:** el colaborador (ADR-011) no tiene acceso (`/api/clients/**` no está en su lista blanca) y el nutricionista que
  no es su entrenador tampoco, igual que hoy con el peso. Ampliarlo es decisión del Jefe y de Seguridad (sueño y ánimo son datos
  de bienestar, ver "Riesgos").
- No hay escritura del profesional: el diario lo rellena el cliente.

## D2 · Peso único por día — `POST /api/progress` (cambio compatible)
- Desde V16 hay **un único** registro de `progress_entry` por `(client_id, entry_date)`.
- `POST /api/progress` pasa a ser **"insertar o actualizar el día"**:
  - si no hay registro ese día, lo crea;
  - si ya lo hay, lo actualiza y devuelve **el mismo `id`**: cada campo que llega con valor (`weightKg`, `bodyFatPct`, `notes`)
    sustituye al guardado; los que llegan `null` se conservan.
- La respuesta sigue siendo **201** con `ProgressDto` (sin cambio para el Frontend actual).
- Permisos sin cambios (`assertCanAccessClient`: el cliente para sí mismo, su entrenador, ADMIN, colaborador asignado).
- 409 si dos guardados del mismo día chocan a la vez (reintentar).
- El peso del asistente de bienvenida (`PUT /api/me/onboarding`) usa la misma regla sobre el día de hoy.

### Migración de los duplicados existentes (V16)
- Por cada `(client_id, entry_date)` con varios registros se **conserva el más reciente** (el `id` mayor) y se le completa cada
  campo vacío (`weight_kg`, `body_fat_pct`, `notes`) con el valor del registro más reciente que lo tenga.
- **No se pierde nada:** todas las filas de esos días, también la conservada con sus valores originales, se copian antes en
  `progress_entry_dup_v16` (con `kept_id`). La reversión de la cabecera de V16 las restaura.
- `progress_entry_dup_v16` es temporal: se borrará en una migración posterior, cuando el profesional haya podido revisar los
  datos (decisión pendiente del Jefe; ver "Dudas").

## D17 · Sesiones reservables — CLIENT

### `GET /api/me/agenda/available?from=2026-11-01&to=2026-11-30`
`AvailableSessionDto[]`, ordenadas por inicio:
```json
{
  "id": 81, "title": "Yoga grupal", "emoji": "🧘", "description": null,
  "mode": "IN_PERSON", "location": "Sala 2", "meetingUrl": null,
  "sessionTypeName": "Yoga", "color": "#8B3FD1", "coverUrl": null, "cover": "yoga",
  "start": "2026-11-03T18:30:00", "end": "2026-11-03T20:00:00",
  "capacity": 10, "booked": 7, "seatsLeft": 3, "full": false,
  "myBookingId": null, "myBookingStatus": null,
  "organizerName": "Laura Pérez"
}
```
- Sesiones (`kind = SESSION`) de **su entrenador** (`trainerId` del cliente) que:
  - son reservables para él: `bookable = YES`, o `TAGS` y el cliente tiene alguna de las etiquetas;
  - **aún no han empezado** (`start` posterior a ahora en Europe/Madrid);
  - empiezan entre `from` y `to` (ambos incluidos).
- Cliente sin entrenador (`trainerId` vacío, los que crea el ADMIN): las sesiones de los ADMIN, que hacen de profesional
  (igual que `ClientDirectory` y la reserva).
- `capacity` `null` = sin límite (entonces `seatsLeft` también es `null` y `full` es `false`). `booked` cuenta las plazas
  ocupadas (BOOKED, ATTENDED, NO_SHOW). `full: true` → reservar deja al cliente **en cola** (`WAITLIST`).
- `myBookingId` / `myBookingStatus`: su reserva en esa sesión si está `BOOKED` o `WAITLIST`; `null` si no tiene o la canceló.
- `meetingUrl` solo se envía si el cliente tiene plaza (`BOOKED`); si no, `null`.
- No incluye la lista de clientes ni las etiquetas de la sesión.
- 400 si falta `from`/`to`, `to < from` o el rango supera 92 días.

### Reservar y cancelar (ya existían, **sin cambios**: `docs/api-agenda.md`)
- Reservar: `POST /api/agenda/events/{id}/bookings` con `{ "clientId": <su id> }` → 201 `BookingDto` (`BOOKED` o `WAITLIST`).
  403 si es para otro cliente, de otro profesional o no reservable para él; 409 si ya tiene reserva; 400 si ya empezó.
- Cancelar: `PATCH /api/agenda/bookings/{myBookingId}` con `{ "status": "CANCELLED" }` → 200 `BookingDto`. Al liberar plaza,
  entra el primero de la cola.
- La comprobación de "ya ha empezado" de la reserva también se calcula ahora en Europe/Madrid (antes, en la hora del servidor).

## Riesgos y límites conocidos
- **Datos de bienestar:** sueño y ánimo no son datos de salud del art. 9 en sentido estricto, pero se tratan con el mismo cuidado:
  solo el cliente, su entrenador y ADMIN. Pendiente de revisión de Seguridad.
- Zona única de plataforma (ADR-008): un cliente fuera de España ve "hoy" en hora de España.
- El resto del backend aún usa la hora del servidor en otros cálculos (`LocalDate.now()` sin zona); T201 solo lo corrige en lo suyo.
