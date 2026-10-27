# Contrato API · Sección Clientes (v1.3.0)

Persistencia en PostgreSQL de lo que el frontend simulaba en `localStorage`: etiquetas, ficha del cliente,
invitaciones, leads y comunidad. Migración: `src/main/resources/db/migration/V2__clientes.sql`.
Swagger con el detalle de cada esquema: `http://localhost:8080/swagger-ui.html`.

Reglas comunes
- Todas las rutas requieren JWT salvo `/api/public/**`.
- TRAINER solo ve y modifica lo suyo (sus etiquetas, invitaciones, leads, grupos y clientes); ADMIN ve todo.
- Errores: `{ timestamp, status, error, message }` (400 validación, 403 sin permiso, 404 no existe, 409 conflicto).
- Fechas `yyyy-MM-dd`; instantes `yyyy-MM-ddTHH:mm:ss` (hora local del servidor).

## Etiquetas — `/api/tags` (TRAINER, ADMIN)
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/tags` | — | `TagDto[]` |
| POST | `/api/tags` | `TagRequest` | 201 `TagDto` · 409 si el nombre ya existe (sin distinguir mayúsculas) |
| PUT | `/api/tags/{id}` | `TagRequest` | `TagDto` |
| DELETE | `/api/tags/{id}` | — | 204 (se quita de clientes e invitaciones) |
| POST | `/api/tags/{id}/clients` | `{ clientIds: number[] }` | `TagDto` (asignación masiva) |
| DELETE | `/api/tags/{id}/clients/{clientId}` | — | `TagDto` |

`TagRequest { name (1-60), automations: TagAutomations }`
`TagDto { id, name, createdAt, automations, clientCount }`
`TagAutomations { chat, chatMessage, forms, formName, program, programName, programDate, nutrition, nutritionPlan, limitChat, chatLimit, service, services[] }`
Automatizaciones ejecutadas al crear el perfil del cliente con la etiqueta (alta manual o invitación aceptada):
`chat` envía `chatMessage` al cliente por el chat. El resto se guardan y se muestran; se ejecutarán cuando existan Programas, Formularios y planes de librería en el backend.

## Ficha del cliente — `/api/clients` (TRAINER, ADMIN)
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/clients/profiles` | — | `ClientProfileDto[]` (activos y archivados, sin los eliminados) |
| PUT | `/api/clients/{id}/profile` | `ClientProfileRequest` | `ClientProfileDto` |
| DELETE | `/api/clients/{id}` | — | 204 · solo si está archivado (409 si no); borrado lógico |
| POST | `/api/clients` | `CreateClientRequest` + `tagIds?` | 201 `UserDto` (crea la ficha y aplica automatizaciones) |
| PUT | `/api/clients/{id}` | `{ fullName, active }` | `UserDto` (archivar = `active:false`, sin acceso a la app) |

`ClientProfileDto { clientId, fullName, email, active, tagIds[], planName, planStatus, lastPayment, lastPaymentDate,
complianceWeek, complianceMonth, activatedAt, assignee, lastActivity, appAccess, heightCm, weightKg, gender, birthDate, services[] }`
Calculados en el servidor (no editables): `complianceWeek`/`complianceMonth` = % de rutinas asignadas completadas en los últimos 7/30 días;
`lastActivity` = última serie registrada o rutina completada; `lastPayment` = estado del último cobro (`DONE` pagado, `EXPIRED` vencido, `NOT_COMPLETED` pendiente).
`ClientProfileRequest { planName?, planStatus, assignee, appAccess, heightCm?, weightKg?, gender, birthDate?, services[], tagIds[] }`
Enumerados: `planStatus` ACTIVE | PENDING_START | SENT | NO_PLAN | ENDING_SOON | FINISHED | DISPUTE · `assignee` ME | OTHERS | NONE ·
`gender` NONE | MALE | FEMALE · `services` FITNESS | NUTRITION.

## Invitaciones — `/api/invitations` (TRAINER, ADMIN)
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/invitations` | — | `InvitationDto[]` pendientes por email |
| POST | `/api/invitations` | `InvitationRequest` | 201 `InvitationDto` · 409 si el email ya es usuario o tiene invitación pendiente |
| POST | `/api/invitations/link` | `{ tagId?, planId? }` | 201 `InvitationDto` (enlace genérico reutilizable, caduca a los 14 días) |
| POST | `/api/invitations/{id}/resend` | — | `InvitationDto` (renueva fecha y caducidad) |
| DELETE | `/api/invitations/{id}` | — | 204 |

`InvitationRequest { email, name, tagId?, planName?, planId? }` · `planId`: plan de suscripción propio y no archivado (400 si no). Al aceptar la invitación se crea la suscripción desde ese día (docs/api-negocio.md).
`InvitationDto { id, email?, name?, tagId, planName, token, invitedAt, expiresAt, planId }` · enlace = `<origen del front>/invitacion/<token>`.
No hay servidor de correo: "enviar" registra la invitación y el profesional comparte el enlace.

## Público (sin JWT) — `/api/public/invitations`
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/public/invitations/{token}` | — | `PublicInvitationDto { trainerName, email?, name?, expired }` · 404 si no existe o ya se usó |
| POST | `/api/public/invitations/{token}/accept` | `AcceptInvitationRequest { fullName, email?, password (8-72) }` | 201 `LoginResponse` (cliente creado y con sesión) · 409 si el email ya existe · 410 si caducó |
El token es aleatorio (32 bytes, base64url). Las invitaciones por email son de un solo uso; los enlaces genéricos, reutilizables hasta caducar.

## Leads — `/api/leads` (TRAINER, ADMIN)
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/leads` | — | `LeadDto[]` |
| POST | `/api/leads` | `LeadRequest` | 201 `LeadDto` (alta manual) |
| DELETE | `/api/leads/{id}` | — | 204 |
| POST | `/api/leads/{id}/invite` | — | 201 `InvitationDto` (crea la invitación y elimina el lead; 400 si no tiene email) |

`LeadRequest { name, email?, phone?, message?, source }` · `source` WEB_FORM | REFERRAL | OTHER.
La captación pública desde el Sitio web llegará con la fase de Marketing.

## Comunidad — `/api/community`
| Método | Ruta | Quién | Cuerpo | Respuesta |
|---|---|---|---|---|
| GET | `/api/community/groups` | todos | — | `GroupDto[]` (profesional: los suyos; cliente: donde es miembro) |
| POST | `/api/community/groups` | TRAINER, ADMIN | `GroupRequest` | 201 `GroupDto` |
| PUT | `/api/community/groups/{id}` | dueño | `GroupRequest` | `GroupDto` |
| DELETE | `/api/community/groups/{id}` | dueño | — | 204 |
| GET | `/api/community/groups/{id}/posts` | dueño o miembro | — | `PostDto[]` (más recientes primero) |
| POST | `/api/community/groups/{id}/posts` | dueño; miembros si `whoCanPost=EVERYONE` | `PostRequest` | 201 `PostDto` |
| DELETE | `/api/community/posts/{id}` | autor o dueño del grupo | — | 204 |
| POST | `/api/community/posts/{id}/like` | dueño o miembro | — | `PostDto` (alterna me gusta) |

`GroupRequest { name (1-80), description?, emoji?, whoCanPost, shareResults, memberIds[] }` · `whoCanPost` TRAINER | TEAM | EVERYONE (TEAM equivale a TRAINER hasta que exista Equipo).
`GroupDto { id, name, description, emoji, whoCanPost, shareResults, createdAt, members: { id, name }[] }`
`PostRequest { text (1-2000), workoutId? }` · el workout debe ser del dueño del grupo.
`PostDto { id, groupId, authorId, authorName, mine, text, createdAt, workout?: { id, name, ranking: { name, result }[] }, likes, liked }`
Ranking del workout compartido: miembros que completaron una asignación de ese workout, por fecha de finalización (máx. 10).
