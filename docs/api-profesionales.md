# API Profesionales (T228) — contrato

Versión 1.0.0 · 2026-11-03 · Petición del usuario: "solamente los admins crean usuarios para asignar entrenadores y nutricionistas".

Un **profesional** es un usuario con rol `TRAINER` que ofrece entrenamiento, nutrición o ambos (ADR-013). Es la única forma de crear uno: nadie puede registrarse como profesional. Todas las rutas son **solo ADMIN** (`/api/admin/**` en `SecurityConfig`, más `@PreAuthorize`); cualquier otro rol → 403.

## Usuario de acceso
Va en el campo `email` de `app_user` y es lo que se escribe en "Usuario o email" al iniciar sesión, igual que `admin`:
- un email (`ana@ejemplo.com`), o
- un nombre corto de 3 a 40 caracteres: letras minúsculas, números, `.`, `_` y `-` (`chani`).

Se guarda en minúsculas. Reservados: `admin`, `administrador`, `root`, `soporte`, `support`. Repetido (con cualquier rol) → 409.

## Rutas
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/admin/professionals` | — | `ProfessionalAdminDto[]` ordenados por nombre |
| POST | `/api/admin/professionals` | `{ username, fullName, password, training, nutrition }` | 201 `ProfessionalAdminDto` |
| PUT | `/api/admin/professionals/{id}` | `{ fullName, training, nutrition, active }` | 200 `ProfessionalAdminDto` |
| PUT | `/api/admin/professionals/{id}/password` | `{ password }` | 204 |

`ProfessionalAdminDto`: `{ id, username, fullName, trainingEnabled, nutritionEnabled, active, clients }` (`clients` = clientes que le tienen como entrenador). Nunca devuelve la contraseña ni su hash.

Reglas:
- Contraseña de 8 a 72 caracteres (mismo límite que el resto de la app). Se guarda con BCrypt.
- Al menos un servicio (`training` o `nutrition`) → si no, 400.
- `id` que no es un profesional (p. ej. un cliente o el ADMIN) → 404.
- Desactivar (`active: false`) o cambiar la contraseña **cierra todas sus sesiones**. Sus clientes y datos se conservan.
- Asignar clientes a un profesional sigue igual: invitaciones o alta desde su cuenta; el responsable de nutrición, en *Responsables* (`PUT /api/clients/{id}/nutritionist`).
