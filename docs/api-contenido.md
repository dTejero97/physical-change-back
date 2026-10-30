# API · Generador de contenido (Marketing)

Versión 1.0.0 · 2026-10-30 · Tarea T187 (implementa T190; permiso del colaborador T198) · Decisión: `docs/adr/ADR-014-conectores-sociales.md` (raíz del proyecto) · Especificación: `docs/fase8-generador-contenido.md` (raíz) · Migración: `V15` (T189, borrador en §10)

## 0. Reglas comunes
- Todas las rutas exigen JWT y cuelgan de `/api/content`. Swagger: grupo "Contenido".
- **Quién**: ADMIN y TRAINER, sobre **su propia marca** (el ADMIN no ve la de los TRAINER). COLLABORATOR solo con el permiso `contentGenerator` (T198) y en el espacio de su profesional (`AccessService.workspaceId()`), con las excepciones de §9. CLIENT: 403 en todo.
- Un id de otro espacio responde **404** (no 403), como en el resto de la API.
- Errores con `ErrorResponse { timestamp, status, error, message }`:
  - 400 validación o fecha/hora no válida;
  - 402 sin créditos de Physical AI;
  - 403 sin permiso (rol o permiso del colaborador);
  - 404 no existe o no es de tu espacio;
  - 409 conflicto de estado (red no configurada, transición no permitida, elemento del banco no editable);
  - 422 la IA no devolvió un resultado válido tras un reintento (no se cobra);
  - 429 demasiadas peticiones (`Retry-After`);
  - 503 Physical AI no configurado o sin presupuesto diario.
- **Paginación** (listados marcados como paginados): `?page=0&size=20&sort=campo,dir`, `size` ≤ 100. Respuesta `Page<T>` de Spring: `{ content[], totalElements, totalPages, number, size, first, last }`.
- **Fechas**: instantes en ISO-8601 con zona (`2026-11-03T11:00:00+01:00`). Fechas sin hora en `YYYY-MM-DD`. Zona de programación: `Europe/Madrid` (única admitida en v1).
- **Textos**: texto plano. El frontend los pinta escapados; los enlaces externos solo se aceptan con `https://`.
- **Sin cifras inventadas**: una métrica desconocida es `null` ("Sin datos"), nunca 0.

### Enumerados
| Enum | Valores |
|---|---|
| `SocialNetwork` | `INSTAGRAM`, `TIKTOK`, `YOUTUBE`, `LINKEDIN`, `X`, `FACEBOOK`, `THREADS`, `PINTEREST` |
| `AccountStatus` | `NOT_CONFIGURED`, `DISCONNECTED`, `CONNECTED`, `DISABLED`, `EXPIRED`, `ERROR` |
| `ContentFormat` | `REEL`, `CAROUSEL`, `SHORT`, `STORY`, `POST`, `LONG_VIDEO` |
| `ContentObjective` | `ATTRACT_CLIENTS`, `AUTHORITY`, `COMMUNITY`, `SALES` |
| `ContentOrigin` | `BANK` (banco del sistema), `AI`, `OWN` |
| `PostStatus` | `DRAFT`, `SCHEDULED`, `DUE`, `PUBLISHED`, `FAILED`, `CANCELLED` |
| `TargetStatus` | `PENDING`, `PUBLISHING`, `PUBLISHED`, `MANUAL_DUE`, `FAILED`, `CANCELLED` |
| `MetricSource` | `SYNC`, `MANUAL` (en agregados también `MIXED`) |

### `GET /api/content/catalog`
Etiquetas en español para la interfaz (sin datos del usuario):
```json
{ "networks": [{ "id": "INSTAGRAM", "label": "Instagram" }],
  "formats": [{ "id": "REEL", "label": "Reel" }],
  "objectives": [{ "id": "ATTRACT_CLIENTS", "label": "Captar clientes" }],
  "themes": ["Fuerza", "Nutrición", "Pérdida de grasa", "Movilidad"],
  "timeZones": ["Europe/Madrid"] }
```
`themes`: los temas del banco del sistema (sugerencias; el tema es texto libre ≤ 60).

## 1. Cuentas sociales

### `GET /api/content/accounts`
Siempre las 8 redes, en el orden del enum.
```
SocialAccountDto {
  network, label, status,
  configured,                       // el servidor tiene claves de la red
  statusReason,                     // texto para la interfaz, p. ej. "No disponible todavía: lo activa el administrador de la plataforma"
  capabilities: { publish, publishFormats[], metrics },   // en 8.1, todo false / []
  externalUsername, connectedAt, lastSyncAt, tokenExpiresAt,
  lastError                         // solo en ERROR/EXPIRED; sin tokens ni datos sensibles
}
```
Nunca devuelve tokens, claves ni scopes. En la Fase 8.1 las 8 redes salen `NOT_CONFIGURED`.

### `POST /api/content/accounts/{network}/connect` — ADMIN, TRAINER
- 200 `{ "authorizationUrl": "https://…", "expiresAt": "…" }` (el `state` caduca a los 10 min).
- 409 si la red está `NOT_CONFIGURED` o ya hay una cuenta vinculada (desconéctala antes).
- El frontend redirige el navegador a `authorizationUrl`. La red vuelve a `<PUBLIC_APP_URL>/marketing/contenido/cuentas/oauth?code&state` (o `?error&state`).

### `POST /api/content/oauth/callback` — ADMIN, TRAINER
```json
{ "network": "INSTAGRAM", "state": "…", "code": "…", "error": null }
```
- 200 `SocialAccountDto` con `status: CONNECTED`.
- 400 "La conexión ha caducado o no es válida; vuelve a intentarlo" si el `state` no existe, ya se usó, caducó, es de otro usuario u otra red. 400 "Conexión cancelada" si llega `error`. En ambos casos el `state` queda consumido.
- 502 "La red no ha aceptado la conexión" si falla el canje del `code` (detalle solo en el log, sin tokens).
- El frontend envía la petición nada más volver y limpia `code` y `state` de la URL.

### Gestión de una cuenta — ADMIN, TRAINER
| Método | Ruta | Respuesta |
|---|---|---|
| POST | `/api/content/accounts/{network}/disable` | `SocialAccountDto` (`DISABLED`). 409 si no está `CONNECTED`, `ERROR` ni `EXPIRED` |
| POST | `/api/content/accounts/{network}/enable` | `SocialAccountDto` (`CONNECTED`, o `EXPIRED` si el token ya no vale). 409 si no está `DISABLED` |
| POST | `/api/content/accounts/{network}/sync` | `SocialAccountDto` tras sincronizar métricas (≤ 30 s). 409 si no está `CONNECTED`/`ERROR` o el conector no da métricas |
| DELETE | `/api/content/accounts/{network}` | 204. Revoca en la red si se puede y borra la cuenta y sus tokens. Las publicaciones y métricas se conservan. 404 si no hay cuenta |

Las publicaciones `SCHEDULED` cuyas redes queden sin cuenta pasarán a publicación manual (`MANUAL_DUE` con aviso) al llegar su hora.

## 2. Ideas

`ContentIdeaDto { id, title, hook, script, format, objective, theme, tags[], origin, bank (true si es del sistema), sourceId, used, favorite, createdAt, updatedAt }`

| Método | Ruta | Cuerpo / parámetros | Respuesta |
|---|---|---|---|
| GET | `/api/content/ideas` | `scope` (`MINE` por defecto · `BANK`), `format`, `objective`, `theme`, `tag`, `q` (≤ 100, título/gancho/guion), `favorite`, `used`; paginado, orden por defecto `updatedAt,desc` | `Page<ContentIdeaDto>` |
| GET | `/api/content/ideas/{id}` | — | `ContentIdeaDto` (propias o del banco) |
| POST | `/api/content/ideas` | `IdeaRequest` | 201 `ContentIdeaDto` (`origin: OWN`) |
| PUT | `/api/content/ideas/{id}` | `IdeaRequest` | `ContentIdeaDto`. 409 si es del banco |
| PATCH | `/api/content/ideas/{id}` | `{ favorite?, used? }` | `ContentIdeaDto`. 409 si es del banco |
| DELETE | `/api/content/ideas/{id}` | — | 204. 409 si es del banco. Las publicaciones que la usaban la pierden (`ideaId: null`) |
| POST | `/api/content/ideas/{id}/copy` | — | 201 `ContentIdeaDto`: copia propia de una idea del banco (`origin: BANK`, `sourceId`). Si ya la copiaste, 200 con la copia existente |
| POST | `/api/content/ideas/{id}/to-post` | `{ networks[]? }` | 201 `ContentPostDto` en `DRAFT` con título, gancho + guion como texto, formato e `ideaId`. Marca la idea como usada. Una del banco se copia antes |

`IdeaRequest { title (1-160), hook (≤ 300)?, script (≤ 4000)?, format, objective?, theme (≤ 60)?, tags[] (≤ 10, cada una ≤ 30)? }`

## 3. Ganchos y copys

Comparten forma (`ContentSnippetDto`); los ganchos no usan `network`, `cta` ni `hashtags`.

`ContentSnippetDto { id, kind (HOOK|COPY), text, network?, cta?, hashtags[], theme, objective, tags[], origin, bank, sourceId, favorite, createdAt, updatedAt, charLimit, overLimit }`
- `charLimit`: límite de caracteres de la red (solo copys con `network`). **`null` mientras no se verifique contra la documentación oficial (T194)**; `overLimit` es `null` en ese caso.

| Método | Ruta | Notas |
|---|---|---|
| GET | `/api/content/hooks` · `/api/content/copies` | `scope` (`MINE` · `BANK`), `theme`, `objective`, `tag`, `q`, `favorite`; copys también `network`. Paginado |
| GET | `/api/content/hooks/{id}` · `/api/content/copies/{id}` | |
| POST | `/api/content/hooks` · `/api/content/copies` | `SnippetRequest` → 201 (`origin: OWN`) |
| PUT | `…/{id}` | `SnippetRequest`. 409 si es del banco |
| PATCH | `…/{id}` | `{ favorite }`. 409 si es del banco |
| DELETE | `…/{id}` | 204. 409 si es del banco |
| POST | `…/{id}/copy` | Copia propia de un elemento del banco (idempotente, como en ideas) |

`SnippetRequest`:
- gancho: `{ text (1-300), theme?, objective?, tags[]? }`;
- copy: `{ text (1-5000), network?, cta (≤ 300)?, hashtags[] (≤ 30, cada uno 1-100 sin espacios; se guardan sin `#`)?, theme?, objective?, tags[]? }`.

"Copiar al portapapeles" es del frontend; no hay endpoint.

## 4. Bancos de contenido (colecciones)

`CollectionDto { id, name, itemCount, createdAt }` · `CollectionItemDto { type (IDEA|HOOK|COPY), id, title (o texto recortado a 120), addedAt }`

| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/content/collections` | — | `CollectionDto[]` |
| POST | `/api/content/collections` | `{ name (1-80) }` | 201 `CollectionDto`. 409 si ya tienes una con ese nombre |
| PUT | `/api/content/collections/{id}` | `{ name }` | `CollectionDto` |
| DELETE | `/api/content/collections/{id}` | — | 204 (no borra las ideas, ganchos ni copys) |
| GET | `/api/content/collections/{id}/items` | `type?`, `q?`; paginado | `Page<CollectionItemDto>` |
| PUT | `/api/content/collections/{id}/items/{type}/{itemId}` | — | 204 (idempotente). Solo elementos propios: los del banco se copian antes (409 si no) |
| DELETE | `/api/content/collections/{id}/items/{type}/{itemId}` | — | 204 |

La búsqueda por etiquetas está en los listados de ideas, ganchos y copys (`tag`, `q`).

## 5. Generar con Physical AI — ADMIN, TRAINER

Reutiliza `AiService` (ADR-003). **1 crédito por generación** (los créditos mensuales de Physical AI; sin contador aparte, J-03). Se cobra solo si se guarda el resultado. Lo generado se guarda con `origin: AI`. Para mostrar "X de Y" el frontend usa `GET /api/ai/status` (`credits`).

| Método | Ruta | Cuerpo |
|---|---|---|
| POST | `/api/content/ai/ideas` | `{ count (3-10, por defecto 5), format?, objective?, theme (≤ 60)?, networks[]?, prompt (≤ 500)? }` |
| POST | `/api/content/ai/hooks` | `{ count (3-10), theme?, objective?, prompt (≤ 500)? }` |
| POST | `/api/content/ai/copies` | `{ networks[] (1-8), ideaId?, topic (≤ 300)?, tone (CLOSE|PROFESSIONAL|ENERGETIC|EDUCATIONAL)?, cta?, includeHashtags (bool) }` — `ideaId` o `topic` obligatorio; un copy por red |

Respuesta 201:
```
ContentAiResultDto { kind (IDEAS|HOOKS|COPIES), ideas[]?: ContentIdeaDto, snippets[]?: ContentSnippetDto,
                     creditsUsed: 1, credits: CreditsDto, warnings[] }
```
- `warnings`: p. ej. "Se han descartado 2 ideas con un formato no válido" o "El límite de caracteres de X está sin verificar".
- Errores de ADR-003: 402 sin créditos, 422 salida no válida tras un reintento, 503 IA apagada o sin presupuesto, 429 demasiadas seguidas.
- Se envían al modelo solo los parámetros, el texto del profesional (como datos) y sus instrucciones permanentes. **Nunca datos de clientes.** La idea de `ideaId` debe ser propia o del banco (404 si no).
- `ai_usage.kind`: `CONTENT_IDEA`, `CONTENT_HOOK`, `CONTENT_COPY`.

## 6. Publicaciones

```
ContentPostDto {
  id, title, body, hashtags[], format,
  media: { fileId?, url?, name?, contentType? } | null,
  ideaId?, status (PostStatus),
  scheduledAt?,            // instante ISO con zona
  scheduledLocal?,         // "2026-11-03T11:00" en timeZone
  timeZone,                // "Europe/Madrid"
  targets: [ PostTargetDto ],
  createdBy: { id, fullName }, updatedBy: { id, fullName }, createdAt, updatedAt, version
}
PostTargetDto { network, status (TargetStatus), automatic (se publicará por API), accountStatus,
                externalUrl?, publishedAt?, error? }
```
`automatic` se calcula al leer: red configurada, cuenta `CONNECTED` y conector que publica ese formato. En 8.1 siempre `false`.

| Método | Ruta | Cuerpo / parámetros | Respuesta |
|---|---|---|---|
| GET | `/api/content/posts` | `status`, `network`, `format`, `from`, `to` (fechas locales), `q`; paginado, orden `scheduledAt,asc` | `Page<ContentPostDto>` |
| GET | `/api/content/posts/{id}` | — | `ContentPostDto` |
| POST | `/api/content/posts` | `PostRequest` | 201 `ContentPostDto` en `DRAFT` |
| PUT | `/api/content/posts/{id}` | `PostRequest` + `version` | `ContentPostDto`. 409 si está `PUBLISHED` o `CANCELLED`, o si `version` no coincide (otro la editó) |
| DELETE | `/api/content/posts/{id}` | — | 204. Solo `DRAFT` o `CANCELLED` (409 si no) |
| POST | `/api/content/posts/{id}/schedule` | `ScheduleRequest` | `ContentPostDto` en `SCHEDULED` |
| PATCH | `/api/content/posts/{id}/schedule` | `ScheduleRequest` (mover en el calendario) | `ContentPostDto`. Solo `SCHEDULED`, `DRAFT` o `FAILED` |
| POST | `/api/content/posts/{id}/unschedule` | — | `ContentPostDto` en `DRAFT`. Solo desde `SCHEDULED` |
| POST | `/api/content/posts/{id}/cancel` | — | `ContentPostDto` en `CANCELLED` (los destinos no publicados pasan a `CANCELLED`). No desde `PUBLISHED` |
| POST | `/api/content/posts/{id}/restore` | — | `ContentPostDto` en `DRAFT`. Solo desde `CANCELLED` |
| POST | `/api/content/posts/{id}/targets/{network}/published` | `{ externalUrl (https, ≤ 500)?, publishedAt? (por defecto ahora) }` | `ContentPostDto`. Marca a mano esa red como publicada (desde `MANUAL_DUE` o `FAILED`; también `PENDING` de una publicación `DUE`). Recalcula el estado global |
| POST | `/api/content/posts/{id}/duplicate` | — | 201 copia en `DRAFT`, sin fecha ni enlaces |

`PostRequest { title (1-160), body (≤ 5000)?, hashtags[] (≤ 30)?, format, mediaFileId? | mediaUrl (https, ≤ 500)?, ideaId?, networks[] (0-8, sin repetir) }`
- `mediaFileId`: archivo propio de la Librería (`/api/files`); de otro → 404. No se aceptan los dos a la vez.
- Cambiar `networks` en `SCHEDULED` añade o quita destinos `PENDING`.

`ScheduleRequest { date: "2026-11-03", time: "11:00", timeZone: "Europe/Madrid", networks[]? }`
- Programar exige título, ≥ 1 red y una hora **al menos 1 minuto en el futuro** (400 si no).
- Hora que no existe por el cambio de hora (último domingo de marzo, 02:00–02:59) → 400; hora repetida (último domingo de octubre) → se toma la primera.
- Otra zona distinta de `Europe/Madrid` → 400 en v1.

### Qué pasa a la hora programada (ADR-014 §6)
- La publicación pasa a `DUE`. Las redes sin publicación automática pasan a `MANUAL_DUE` y el profesional recibe **una** notificación `CONTENT_POST_DUE` ("Toca publicar: «título» en Instagram y TikTok").
- Las redes automáticas (Fase 8.2) se publican: `PUBLISHED` (con `externalUrl`) o `FAILED` (con `error`).
- Estado global: todas publicadas → `PUBLISHED`; alguna `FAILED` y ninguna pendiente → `FAILED` (aviso `CONTENT_POST_FAILED`); si no, sigue `DUE`.
- No se publica dos veces ni se repite el aviso, aunque haya varias instancias o reintentos. Con el servidor dormido (Render gratuito), se procesa al despertar.
- Notificaciones (`GET /api/notifications`, ADR-012): tipos nuevos `CONTENT_POST_DUE`, `CONTENT_POST_FAILED` (`refType: CONTENT_POST`) y `SOCIAL_ACCOUNT_ATTENTION` (`refType: SOCIAL_ACCOUNT`, cuenta en `EXPIRED` o `ERROR`). Las recibe el profesional dueño del espacio.

## 7. Calendario

### `GET /api/content/calendar?view=WEEK|MONTH&date=2026-11-03&network=&format=`
- `WEEK`: de lunes a domingo de la semana de `date`. `MONTH`: semanas completas (lunes a domingo) que cubren el mes de `date`.
- Fechas y horas en `Europe/Madrid`. Incluye `SCHEDULED`, `DUE`, `PUBLISHED` y `FAILED` con fecha en el rango; `DRAFT` y `CANCELLED` no.
```
CalendarDto { view, from, to, timeZone,
  days: [ { date, posts: [ CalendarPostDto ] } ] }
CalendarPostDto { id, title, format, status, time ("11:00"), networks[], targets: [{ network, status }],
                  thumbnailUrl? }   // miniatura si el medio es una imagen o tiene portada; si no, null
```
- Mover en el calendario = `PATCH /api/content/posts/{id}/schedule`.
- Vista lista (móvil): `GET /api/content/posts?from&to`.

### `GET /api/content/home?date=2026-11-03`
Datos del Inicio en una sola llamada:
```
ContentHomeDto {
  week: CalendarDto (view WEEK),
  ideas: ContentIdeaDto[]   // hasta 5 propias no usadas, favoritas primero; si no hay, del banco
  hooks: ContentSnippetDto[] // hasta 5 ganchos propios favoritos; si no hay, del banco
  kpis: { scheduled, published, onTimeRate?, views?, viewsSource? }   // de esa semana
  accounts: [ { network, status } ]
}
```
No hay "ideas populares" calculadas con datos ajenos: el frontend las titula como ideas propias o del banco (nota para Diseño, T188).

## 8. Analíticas

Filtros comunes: `from`, `to` (fechas locales; por defecto los últimos 30 días; máximo 366 días), `network?`, `format?`. El periodo anterior es el de igual duración justo antes de `from`.

### `GET /api/content/analytics/summary`
```
AnalyticsSummaryDto {
  from, to, previousFrom, previousTo,
  kpis: {
    scheduled:      KpiDto,   // publicaciones con fecha en el periodo (datos propios: pueden ser 0)
    published:      KpiDto,
    onTimeRate:     KpiDto,   // % de destinos publicados ≤ 15 min tras su hora; null si no hay publicados
    views, reach, interactions: KpiDto,   // de las métricas; null = sin datos
    engagementRate: KpiDto,   // interacciones / visualizaciones, solo si views > 0
    followersGained: KpiDto   // solo con conector que lo dé; en 8.1 siempre null
  },
  viewsByDay:     [ { date, value?, source? } ],                 // por día de publicación
  postsByNetwork: [ { network, published, scheduled, failed } ],  // barras apiladas
  formats:        [ { format, count } ],                          // donut (publicadas)
  interactionsByType: { likes?, comments?, shares?, saves?, source? },
  heatmap: [ { weekday (1=lunes..7), hour (0-23), posts, avgEngagementRate? } ],   // hora de publicación en Europe/Madrid
  topPosts: [ { postId, title, network, format, publishedAt, views?, interactions?, engagementRate?, source } ]  // 10 mejores por visualizaciones; sin métricas no entran
}
KpiDto { value?, previous?, changePct?, source? (SYNC|MANUAL|MIXED) }
```
- `value: null` = "Sin datos". `changePct` es `null` si falta alguno de los dos valores o el anterior es 0.
- Para cada publicación y red se usa su **última medición** dentro del periodo (si el mismo día hay `SYNC` y `MANUAL`, gana `SYNC`). Interacciones = likes + comentarios + compartidos + guardados (los nulos no suman; si todos son nulos, `null`).

### Métricas de una publicación
`PostMetricDto { id, network, measuredOn, views?, reach?, likes?, comments?, shares?, saves?, source, createdBy?, createdAt }`

| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/content/posts/{id}/metrics` | — | `PostMetricDto[]` (más recientes primero) |
| PUT | `/api/content/posts/{id}/metrics/{network}/{measuredOn}` | `{ views?, reach?, likes?, comments?, shares?, saves? }` (enteros 0–10⁹; al menos uno) | `PostMetricDto` (`source: MANUAL`). Crea o sustituye la medición manual de ese día. 409 si esa red no está en `PUBLISHED` o la fecha es anterior a su publicación o futura |
| DELETE | `/api/content/posts/{id}/metrics/{network}/{measuredOn}` | — | 204. Solo la manual; las sincronizadas no se borran (409) |

Los valores son **totales acumulados** hasta ese día, como los muestra la red.

## 9. Permisos

| Rutas | ADMIN / TRAINER | COLLABORATOR con `contentGenerator` | COLLABORATOR sin permiso · CLIENT |
|---|---|---|---|
| `GET /accounts`, `/catalog`, `/home`, `/calendar`, `/analytics/**` | Sí | Sí | 403 |
| Ideas, ganchos, copys, colecciones, publicaciones, métricas manuales | Sí | Sí (en el espacio del profesional; queda en `createdBy`/`updatedBy`) | 403 |
| `POST/DELETE /accounts/**`, `/oauth/callback` | Sí | **403** | 403 |
| `/ai/**` | Sí | **403** (ADR-011 §6: el colaborador no usa Physical AI) | 403 |

Permiso nuevo en Equipo (T198): `TeamMemberDto.permissions.contentGenerator` y `PUT /api/team/{id}/permissions` acepta `contentGenerator` (falso por defecto; solo lo cambia el profesional). `GET /api/team/me` lo devuelve en `permissions`.

## 10. Modelo de datos — borrador para la migración V15 (T189, Base de Datos)

Comprobado el 2026-10-30: la última migración es `V14__onboarding_cliente.sql` y **V15 está libre** (reservada en `tareas.md` para T189; Stripe, T127, pasa a V16). Motor: **PostgreSQL 16**; tests en H2 modo PostgreSQL. Base de Datos decide tipos y nombres finales y escribe la reversión en la cabecera.

```
social_account
  id BIGSERIAL PK · owner_id → app_user (espacio) · network VARCHAR(10) CHECK (8 redes)
  status VARCHAR(10) CHECK (CONNECTED, DISABLED, EXPIRED, ERROR)
  external_id VARCHAR(100) · external_username VARCHAR(120)
  access_token_enc TEXT NOT NULL · refresh_token_enc TEXT · token_expires_at TIMESTAMPTZ · scopes VARCHAR(500)
  connected_at TIMESTAMPTZ NOT NULL · connected_by → app_user
  last_sync_at, sync_started_at TIMESTAMPTZ · last_error VARCHAR(500) · error_count SMALLINT DEFAULT 0
  updated_at TIMESTAMPTZ NOT NULL
  UNIQUE (owner_id, network)

social_oauth_state
  id BIGSERIAL PK · state_hash CHAR(64) UNIQUE · user_id → app_user · owner_id → app_user · network VARCHAR(10)
  code_verifier_enc TEXT · created_at, expires_at TIMESTAMPTZ NOT NULL · used_at TIMESTAMPTZ

content_idea
  id BIGSERIAL PK · owner_id → app_user NULL (= banco del sistema)
  title VARCHAR(160) NOT NULL · hook VARCHAR(300) · script TEXT (≤ 4000)
  format VARCHAR(12) CHECK · objective VARCHAR(16) CHECK NULL · theme VARCHAR(60) · tags VARCHAR(330)
  origin VARCHAR(5) CHECK (BANK, AI, OWN) · source_id → content_idea NULL
  used, favorite BOOLEAN DEFAULT FALSE · created_at, updated_at
  índices (owner_id, updated_at DESC); UNIQUE (owner_id, source_id) para copias del banco

content_snippet                      -- ganchos y copys
  id BIGSERIAL PK · owner_id NULL = banco · kind VARCHAR(4) CHECK (HOOK, COPY)
  text TEXT NOT NULL (≤ 5000) · network VARCHAR(10) NULL · cta VARCHAR(300) · hashtags VARCHAR(3100)
  theme VARCHAR(60) · objective VARCHAR(16) · tags VARCHAR(330)
  origin VARCHAR(5) CHECK · source_id → content_snippet NULL · favorite BOOLEAN · created_at, updated_at
  índices (owner_id, kind, updated_at DESC); UNIQUE (owner_id, source_id)

content_collection
  id BIGSERIAL PK · owner_id NOT NULL · name VARCHAR(80) · created_at · UNIQUE (owner_id, name)
content_collection_item
  collection_id → content_collection ON DELETE CASCADE · item_type VARCHAR(5) CHECK (IDEA, HOOK, COPY) · item_id BIGINT · added_at
  PK (collection_id, item_type, item_id)   -- item_id polimórfico: el servicio borra las filas al borrar el elemento

content_post
  id BIGSERIAL PK · owner_id NOT NULL · created_by, updated_by → app_user
  title VARCHAR(160) NOT NULL · body TEXT (≤ 5000) · hashtags VARCHAR(3100) · format VARCHAR(12) CHECK
  media_file_id → stored_file NULL · media_url VARCHAR(500) · idea_id → content_idea ON DELETE SET NULL
  status VARCHAR(10) CHECK (DRAFT, SCHEDULED, DUE, PUBLISHED, FAILED, CANCELLED)
  scheduled_at TIMESTAMPTZ · time_zone VARCHAR(40) DEFAULT 'Europe/Madrid' · due_at TIMESTAMPTZ
  version INTEGER DEFAULT 0 (bloqueo optimista) · created_at, updated_at
  CHECK (NOT (media_file_id IS NOT NULL AND media_url IS NOT NULL))
  CHECK (status = 'DRAFT' OR status = 'CANCELLED' OR scheduled_at IS NOT NULL)
  índices (owner_id, scheduled_at); (status, scheduled_at) para el job

content_post_target
  id BIGSERIAL PK · post_id → content_post ON DELETE CASCADE · network VARCHAR(10) CHECK
  status VARCHAR(10) CHECK (PENDING, PUBLISHING, PUBLISHED, MANUAL_DUE, FAILED, CANCELLED)
  claimed_at TIMESTAMPTZ · attempts SMALLINT DEFAULT 0
  external_id VARCHAR(200) · external_url VARCHAR(500) · published_at TIMESTAMPTZ · error VARCHAR(500)
  UNIQUE (post_id, network) · índice (status)

content_post_metric
  id BIGSERIAL PK · post_id → content_post ON DELETE CASCADE · network VARCHAR(10) · measured_on DATE
  views, reach, likes, comments, shares, saves BIGINT NULL CHECK (>= 0)
  source VARCHAR(6) CHECK (SYNC, MANUAL) · created_by → app_user NULL · created_at
  UNIQUE (post_id, network, measured_on, source)
  CHECK (al menos una métrica no nula)

Cambios en tablas existentes
  team_member: ADD can_use_content BOOLEAN NOT NULL DEFAULT FALSE           -- T198
  notification.type CHECK: + CONTENT_POST_DUE, CONTENT_POST_FAILED, SOCIAL_ACCOUNT_ATTENTION
  ai_usage.kind CHECK: + CONTENT_IDEA, CONTENT_HOOK, CONTENT_COPY            -- caben en VARCHAR(12)

Semilla (banco del sistema, owner_id NULL, origin BANK)
  ideas, ganchos y copys fitness en español, sin marcas de terceros ni cifras de resultados inventadas
  (p. ej. "3 errores al empezar en el gimnasio", no "pierde 10 kg en 2 semanas").
```
Notas para Base de Datos:
- Los CHECK de `notification.type` y `ai_usage.kind` se declararon en la columna (nombres generados por PostgreSQL, p. ej. `ai_usage_kind_check`): confirmar el nombre real antes de `DROP CONSTRAINT`, como hizo V14.
- `TIMESTAMPTZ` es nuevo en el esquema (el resto usa `TIMESTAMP`): se propone para no depender de la zona de la JVM en el job. Validar el mapeo `Instant` de Hibernate en H2 y en PostgreSQL 16.
- Fuera de V15: la tabla de métricas de cuenta (seguidores) llega con el primer conector real (T194).

## Historial
- 1.0.0 (2026-10-30, T187): primera versión.
