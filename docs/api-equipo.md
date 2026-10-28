# API Equipo (T28, T142) — colaboradores, permisos y clientes asignados

Versión 1.0.0 · 2026-10-28 · Decisión: `../docs/adr/ADR-011-equipo-colaboradores.md` · Migración `V12__equipo.sql`

## Gestión del equipo (solo ADMIN o TRAINER, sobre su propio equipo)

| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/team` | — | `TeamMemberDto[]` (activos e invitados; los dados de baja no salen) |
| POST | `/api/team/invitations` | `{ email, fullName }` | 201 `TeamMemberDto` (envía el correo de invitación). 409 si el email ya tiene cuenta |
| POST | `/api/team/{id}/resend` | — | `TeamMemberDto` (nuevo enlace de 7 días; el anterior deja de valer). 409 si ya está activo |
| PUT | `/api/team/{id}/permissions` | `{ assignRoutines, manageAppointments, messageClients }` | `TeamMemberDto` |
| PUT | `/api/team/{id}/clients` | `{ clientIds: [] }` (≤ 500, clientes propios) | `TeamMemberDto`. 403 si algún cliente no es del profesional |
| DELETE | `/api/team/{id}` | — | 204. Baja: desactiva al colaborador (cierra sus sesiones) y quita sus asignaciones; no borra nada de lo que creó |

`TeamMemberDto { id, fullName, email, status: ACTIVE | INVITED, invitedAt, activatedAt, activeClients, clientIds, permissions: { assignRoutines, manageAppointments, messageClients } }`
Un miembro ajeno (de otro profesional) responde **404**.

## El colaborador

| Método | Ruta | Respuesta |
|---|---|---|
| GET | `/api/team/me` | `{ ownerName, permissions, clientIds }` (solo COLLABORATOR) para que la interfaz muestre lo que puede hacer |

Lo que puede usar (el resto de la API responde 403):
- Clientes asignados: `GET /api/clients` (solo los asignados), `GET /api/clients/{id}`, `GET /api/clients/profiles`, progreso (`/api/progress/**`), series (`/api/exercise-logs/client/{id}`), cumplimiento (`/api/assignments/{id}/compliance`).
- Rutinas y ejercicios del espacio del profesional: `/api/workouts/**` (crear, editar, ver), `GET/POST /api/exercises`. Asignar (`POST /api/workouts/assign`) solo con `assignRoutines` y a clientes asignados.
- Calendario: `GET /api/calendar`. Citas (`/api/appointments`) solo con `manageAppointments` y con clientes asignados.
- Mensajes (`/api/messages`) solo con `messageClients` y con clientes asignados.
- No puede: crear, archivar ni borrar clientes; equipo; Negocio y pagos; etiquetas, invitaciones, leads y comunidad; Librería V2 y `/api/library/**`; Physical AI; archivos.

## Alta del colaborador (pública, sin JWT)

| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/public/team-invitations/{token}` | — | `{ ownerName, email, fullName }`; 404 si no existe, ya se usó o caducó |
| POST | `/api/public/team-invitations/{token}/accept` | `{ password }` (8–72) | `LoginResponse` (abre sesión). 404 si no vale |

El token (256 bits, base64url de 43 caracteres) se guarda como SHA-256 en `user_token` con propósito `TEAM_INVITE` y caduca a los 7 días. El enlace del correo es `<PUBLIC_APP_URL>/equipo/invitacion/<token>`.
