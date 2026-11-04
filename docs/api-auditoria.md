# API Auditoría (T232) — contrato

Versión 1.0.0 · 2026-11-04 · Decisión: `../docs/adr/ADR-016-auditoria.md` · Migración **V26**

Registro de **quién hizo qué y cuándo**, de solo lectura y **solo para el ADMIN** (`/api/admin/**` en `SecurityConfig`, más `@PreAuthorize`). Se guarda **12 meses** y no se puede editar ni borrar desde la API.

## `GET /api/admin/audit`
Parámetros (todos opcionales): `from`, `to` (fechas `AAAA-MM-DD`, zona de la plataforma; rango máximo 366 días; por defecto los últimos 30), `actorId`, `clientId`, `action` (uno o varios códigos), `q` (busca en `summary`), `page`, `size` (máx. 200). Orden: más reciente primero.

Respuesta: página de `AuditEventDto`
`{ id, occurredAt, actorId, actorName, actorRole, action, actionLabel, entityType, entityId, clientId, clientName, summary, ip }`
- `actorId` null = el sistema (p. ej. el webhook de Stripe; `actorName` "Sistema") o un intento de inicio de sesión con un usuario que no existe (`actorName` "Desconocido").
- `ip` truncada (IPv4 /24, p. ej. `83.45.12.0`; IPv6 /48).
- `actorName` y `clientName` se resuelven al leer (si el usuario ya no existe: "Usuario eliminado").

## `GET /api/admin/audit/export?format=csv`
Mismos filtros. CSV UTF-8 con BOM y separador `;` (como los informes, ADR-004 §7), con protección contra inyección de fórmulas. Nombre: `physical-change-auditoria-<desde>-<hasta>.csv`. La exportación queda auditada (`EXPORT`).

## `GET /api/admin/audit/actions`
`[{ code, label, group }]` para el filtro de la pantalla. Grupos: Sesión, Personas, Dinero, Planes, Salud, Datos.

## Códigos
| Grupo | Código | Cuándo |
|---|---|---|
| Sesión | `LOGIN_OK`, `LOGIN_FAILED`, `LOGIN_LOCKED`, `LOGOUT`, `PASSWORD_CHANGED`, `PASSWORD_RESET` | Inicio de sesión correcto, fallido (con el usuario tecleado, nunca la contraseña), bloqueo por intentos, cierre, cambio y restablecimiento |
| Personas | `PROFESSIONAL_CREATED`, `PROFESSIONAL_UPDATED`, `PROFESSIONAL_PASSWORD_SET`, `CLIENT_CREATED`, `CLIENT_UPDATED`, `NUTRITIONIST_ASSIGNED`, `TEAM_INVITED`, `TEAM_PERMISSIONS_CHANGED`, `TEAM_REMOVED` | Altas, cambios de servicios o activo, responsables y equipo |
| Dinero | `RECEIPT_CREATED`, `RECEIPT_PAID`, `RECEIPT_VOIDED`, `STRIPE_PAYMENT_CONFIRMED`, `STRIPE_REFUND`, `SUBSCRIPTION_STARTED`, `SUBSCRIPTION_CANCELLED` | Comprobantes, pagos online y suscripciones |
| Planes | `NUTRITION_PLAN_ASSIGNED`, `NUTRITION_PLAN_CHANGED`, `ROUTINE_ASSIGNED`, `CHANGE_REQUEST_DECIDED` | Planes de nutrición, rutinas y solicitudes de cambio |
| Salud | `HEALTH_DATA_READ` | Alguien que **no es responsable** del cliente lee su cuestionario de salud (hoy, el ADMIN con clientes de otro profesional) |
| Datos | `EXPORT`, `SETTINGS_CHANGED` | Exportaciones; logo, servicios y fondos |

`summary` nunca contiene contraseñas, tokens, emails ni datos de salud (pesos, comidas, respuestas del cuestionario). Sí nombres de plan o producto e importes.
