# Contrato API · Librería V2 (v1.5.0)

Archivos, Programas, Documentos, Formularios, Plantillas y Logros.
Decisiones: `docs/adr/ADR-001-almacenamiento-archivos.md` y `docs/adr/ADR-005-cloudflare-r2.md` (raíz del proyecto). Pantallas: `docs/fase4-pantallas-v2.md`.
Migraciones: `src/main/resources/db/migration/V4__libreria_v2.sql` (incluye el catálogo del sistema) y `V5__almacenamiento_r2.sql` (almacenamiento, estado y `purpose` VIDEO).

Cambios v1.5.0: subida directa a Cloudflare R2 (`POST /api/files/uploads` y `POST /api/files/{id}/complete`), `purpose` VIDEO, descarga con redirección 302 para los archivos guardados en R2 y cuota configurable.

Reglas comunes
- JWT obligatorio y rol TRAINER o ADMIN (salvo `/api/public/files`). Cada profesional solo ve y modifica lo suyo; el contenido del sistema (plantillas, categorías y logros base) es de solo lectura.
- Errores `{ timestamp, status, error, message }` (400, 403, 404, 409, 413 archivo demasiado grande).
- `creatorName`: nombre del profesional, o "Physical Change" en el contenido del sistema.

## Archivos — `/api/files`
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| POST | `/api/files` | multipart: `file`, `purpose` = DOCUMENT \| IMAGE | 201 `FileDto` · 400 tipo no permitido, o `purpose` VIDEO (los vídeos van por subida directa) · 409 cuota superada · 413 > 10 MB |
| POST | `/api/files/uploads` | `UploadRequest` | 201 `UploadDto` · 400 `purpose` o tipo no permitido · 409 cuota superada, o almacenamiento distinto de R2 ("La subida directa necesita Cloudflare R2 (STORAGE_TYPE=r2)") · 413 > 500 MB (VIDEO) o > 10 MB (DOCUMENT, IMAGE) |
| POST | `/api/files/{id}/complete` | — | 200 `FileDto` (también si ya estaba completado) · 400 aún no subido (el registro se conserva) · 400 tamaño distinto del declarado o contenido que no corresponde a la extensión (se borran el objeto y el registro) · 403 no es el dueño · 404 |
| DELETE | `/api/files/{id}` | — | 204 (solo el dueño; también borra subidas pendientes) |
| GET | `/api/public/files/{id}?exp&sig` | — (sin JWT) | Archivo en PostgreSQL: 200 con el contenido. Archivo en R2: **302** a una URL GET prefirmada de R2 válida 1 hora (`Cache-Control: no-store`). 403 firma no válida o caducada · 404 si no existe o la subida está pendiente |

`FileDto { id, name, contentType, size, url }` · `url` = URL firmada válida 1 hora, **relativa a la base de la API** (`/public/files/{id}?exp&sig`; el frontend la antepone con `environment.apiUrl`). Igual en `DocumentDto.url`, `AchievementDto.badgeUrl` y `coverUrl` de la Agenda. La misma URL sirve para los archivos de PostgreSQL y de R2: el frontend no distingue dónde está el archivo.
Tipos: DOCUMENT → PDF, PNG, JPEG, WEBP, TXT, CSV, DOCX, XLSX, PPTX. IMAGE → PNG, JPEG, WEBP (sin SVG). VIDEO → MP4, MOV, WEBM (solo por subida directa). Se comprueba la firma del contenido (en la subida directa, los primeros 16 bytes al completar). El `Content-Type` lo decide el servidor por la extensión.
Cuota por profesional: `STORAGE_QUOTA_MB`. Si está vacía: 50 MB con `STORAGE_TYPE=database` y 2048 MB con `STORAGE_TYPE=r2`. Las subidas pendientes cuentan en la cuota.

### Subida directa a R2 (ADR-005)
`UploadRequest { name (1-200), purpose: DOCUMENT | IMAGE | VIDEO, sizeBytes (> 0) }`
`UploadDto { fileId, uploadUrl, method: "PUT", headers: { "Content-Type", "Content-Disposition" }, expiresAt }`

1. `POST /api/files/uploads` → el archivo queda `PENDING` y se devuelve una URL `PUT` prefirmada de R2 válida **15 minutos**.
2. El navegador hace `PUT uploadUrl` con el archivo como cuerpo y **exactamente** las cabeceras de `headers` (forman parte de la firma; si cambian, R2 responde 403). El bucket necesita CORS para el origen del frontend (`MANUAL-DESPLIEGUE.md`, Parte 8.4).
3. `POST /api/files/{fileId}/complete` → el backend comprueba en R2 el tamaño (HEAD) y la firma del contenido. Si todo cuadra, el archivo pasa a `READY` y se devuelve el `FileDto`.

Un archivo `PENDING` no se puede usar como documento, insignia ni portada (400 "El archivo todavía no se ha subido"). Las subidas pendientes de más de 24 horas se borran la próxima vez que ese profesional pide una subida directa.
`Content-Disposition`: `inline` para imágenes y vídeos, `attachment` para documentos (se fija al subir el objeto a R2).

## Programas — `/api/library/programs`
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/library/programs` | — | `ProgramDto[]` (actualizados primero) |
| GET | `/api/library/programs/{id}` | — | `ProgramDetailDto` |
| POST | `/api/library/programs` | `ProgramRequest` | 201 `ProgramDetailDto` |
| PUT | `/api/library/programs/{id}` | `ProgramRequest` | `ProgramDetailDto` (sustituye el contenido) |
| POST | `/api/library/programs/{id}/duplicate` | — | 201 `ProgramDetailDto` ("(copia)") |
| DELETE | `/api/library/programs/{id}` | — | 204 (los programas que lo enlazaban quedan sin enlace) |

`ProgramRequest { name (1-120), description? (≤2000), weeks (1-52), linkedProgramId?, archiveClient, items[] (≤500) }`
`ProgramItemRequest { week (1..weeks), day (1-7), kind, refId?, title (1-160) }` · `kind`: WORKOUT | CARDIO | VIDEO | NUTRITION_PLAN | NOTE.
WORKOUT exige `refId` de un workout propio. CARDIO/VIDEO/NUTRITION_PLAN guardan `refId` y `title` tal cual (sus librerías aún viven en el navegador, T27).
`linkedProgramId`: programa propio distinto de este (al terminar empieza el enlazado).
`ProgramDto { id, name, description, weeks, linkedProgramId, linkedProgramName, archiveClient, automated, itemCount, creatorName, createdAt, updatedAt }` · `automated` = enlazado o archivar cliente.
`ProgramDetailDto { program: ProgramDto, items: ProgramItemDto[] }` · `ProgramItemDto { id, week, day, kind, refId, title, position }`.

## Documentos — `/api/library/documents`
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/library/documents` | — | `DocumentDto[]` |
| POST | `/api/library/documents` | `DocumentRequest` | 201 `DocumentDto` |
| PUT | `/api/library/documents/{id}` | `DocumentRequest` | `DocumentDto` (si cambia `fileId`, el archivo anterior se borra) |
| DELETE | `/api/library/documents/{id}` | — | 204 (borra también el archivo) |

`DocumentRequest { name (1-160), description? (≤2000), visibility, tagIds[], fileId }` · `visibility`: HIDDEN | ALL | TAGS. `fileId`: archivo propio con `purpose` DOCUMENT no usado por otro documento.
`DocumentDto { id, name, description, visibility, tagIds, fileId, fileName, contentType, size, url, creatorName, createdAt }`.

## Formularios — `/api/library/forms`
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/library/forms` | — | `FormDto[]` |
| GET | `/api/library/forms/{id}` | — | `FormDetailDto` |
| POST | `/api/library/forms` | `FormRequest` | 201 `FormDetailDto` |
| PUT | `/api/library/forms/{id}` | `FormRequest` | `FormDetailDto` (sustituye las preguntas) |
| POST | `/api/library/forms/{id}/duplicate` | — | 201 `FormDetailDto` |
| DELETE | `/api/library/forms/{id}` | — | 204 |

`FormRequest { name (1-160), description? (≤2000), autoSend, questions[] (≤100) }`
`QuestionRequest { kind, label (1-300), required, options[] }` · `kind`: SHORT_TEXT | LONG_TEXT | NUMBER | SINGLE | MULTIPLE | SCALE | DATE. SINGLE y MULTIPLE exigen 2-20 opciones (1-120 caracteres).
`FormDto { id, name, description, autoSend, questionCount, creatorName, createdAt, updatedAt }` · `FormDetailDto { form, questions: QuestionDto[] }`.
Las respuestas de los clientes llegarán con la app del cliente (propuesta en el tablero).

## Plantillas — `/api/library/templates`
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/library/templates` | — | `PackDto[]` (catálogo del sistema) |
| GET | `/api/library/templates/{packId}` | — | `PackDto` |
| POST | `/api/library/templates/items/{itemId}/copy` | — | 201 `CopyResultDto { kind, id, name }` |

`PackDto { id, kind, name, description, cover, items: TemplateItemDto[] }` · `kind`: WORKOUT | PROGRAM.
`TemplateItemDto { id, name, description, detail }` (`detail`: "9 ejercicios", "3 semanas"...).
Copiar WORKOUT crea un workout propio con sus ejercicios (reutiliza ejercicios globales o propios con el mismo nombre; si no existen, los crea como propios). Copiar PROGRAM crea sus workouts y un programa con ellos.

## Logros — `/api/library/achievements`
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/library/achievements` | — | `CategoryDto[]` (la propia "Logros personalizados" se crea oculta la primera vez) |
| POST | `/api/library/achievements/categories` | `CategoryRequest` | 201 `CategoryDto` |
| PUT | `/api/library/achievements/categories/{id}` | `CategoryRequest` | `CategoryDto` (en las del sistema solo cambia la visibilidad, ALL u HIDDEN, para este profesional; TAGS → 400) |
| DELETE | `/api/library/achievements/categories/{id}` | — | 204 (solo propias; borra sus logros) |
| POST | `/api/library/achievements` | `AchievementRequest` | 201 `AchievementDto` |
| PUT | `/api/library/achievements/{id}` | `AchievementRequest` | `AchievementDto` (solo propios) |
| DELETE | `/api/library/achievements/{id}` | — | 204 (solo propios) |
| PATCH | `/api/library/achievements/{id}/hidden` | `{ hidden }` | `AchievementDto` (también los del sistema, solo para este profesional) |

`CategoryRequest { name (1-80), description? (≤500), visibility, tagIds[] }` · `visibility`: ALL | TAGS | HIDDEN.
`CategoryDto { id, name, description, icon, system, visibility, tagIds, createdAt, achievements: AchievementDto[] }` · `icon`: custom | trophy | flag | check.
`AchievementRequest { categoryId (propia), name (1-80), description? (≤500), badgeFileId?, conditionType, exerciseId?, weightKg?, reps?, days?, targetKg?, months?, durationType, startDate?, endDate?, daysAfter? }`
- `conditionType`: WEIGHT_EXERCISE (`exerciseId`, `weightKg` 1-1000) · TOTAL_REPS (`exerciseId`, `reps` 1-100000) · STREAK (`days` 2-365) · BODY_WEIGHT (`targetKg` 20-500) · TIME_WITH_YOU (`months` 1-120).
- `durationType`: UNLIMITED · DATE_RANGE (`startDate` ≤ `endDate`) · AFTER_ASSIGNMENT (`daysAfter` 1-365).
- `badgeFileId`: imagen propia (`purpose` IMAGE). Sin ella se usa la insignia por defecto.
`AchievementDto { id, categoryId, name, description, groupLabel, badge, badgeFileId, badgeUrl, tier, conditionType, exerciseId, exerciseName, weightKg, reps, days, targetKg, months, durationType, startDate, endDate, daysAfter, system, hidden, createdAt, metric, threshold }`
`badge`: clave de insignia del sistema (p. ej. `workouts`, `cardio`, `sessions`, `nutrition`, `videos`, `streak`, `perfect`, `early`, `birthday`, `new-year`, `start`, `valentine`, `custom`); `tier`: BRONZE | SILVER | TEAL.
`AchievementDto.metric` y `threshold` (desde V8): qué mide un logro del sistema y su umbral (ver abajo); `null` en los propios.

### Logros conseguidos por un cliente (T58, ADR-007 · desde v1.6.0)
Se calculan **al consultar** y se guardan (`V8__logros_desbloqueo.sql`, tabla `achievement_unlock`): la consulta es idempotente y la fecha de desbloqueo es el día en que se cumplió la condición, no el de la consulta. Un logro conseguido no se pierde.

| Método | Ruta | Rol | Respuesta |
|---|---|---|---|
| GET | `/api/clients/{clientId}/achievements` | TRAINER (solo sus clientes) o ADMIN | `ClientAchievementsDto` · 403 si no es su cliente · 404 si no existe o no es CLIENT |
| GET | `/api/my/achievements` | CLIENT (él mismo) | `ClientAchievementsDto` |

`ClientAchievementsDto { clientId, unlocked, total, achievements: ClientAchievementDto[] }` · `unlocked` = n.º con estado UNLOCKED; `total` = n.º de logros disponibles.
`ClientAchievementDto { id, categoryId, categoryName, name, description, groupLabel, badge, badgeUrl, tier, metric, status, unlockedOn, progress, target }`
- `status`: UNLOCKED (`unlockedOn` = fecha) · LOCKED (`progress` y `target` numéricos si se pueden medir) · NO_DATA (hoy no hay datos para calcularlo: cardio, nutrición y vídeos, cumpleaños sin fecha de nacimiento, tiempo contigo sin ficha, peso corporal sin registros).
- `metric`: BIRTHDAY · NEW_YEAR · FIRST_ACTIVITY · VALENTINE · WORKOUTS · CARDIO · SESSIONS · NUTRITION · VIDEOS · STREAK_DAYS · PERFECT_DAYS · EARLY_DAYS en los del sistema; en los propios, su `conditionType`.
- Solo se devuelven los logros **disponibles** para el cliente: los del profesional del cliente (si no tiene `trainerId`, los del ADMIN), con la categoría visible para él (ALL, o TAGS con alguna de sus etiquetas; las del sistema según la preferencia del profesional) y no ocultos por el profesional. Orden: categorías como en `GET /api/library/achievements` y, dentro, por posición.
- Duración: UNLIMITED (todo hasta hoy) · DATE_RANGE (solo los datos y desbloqueos entre `startDate` y `endDate`) · AFTER_ASSIGNMENT (desde que el logro está disponible para el cliente, el mayor entre la creación del logro y el alta del cliente, durante `daysAfter` días).
- Reglas de cálculo por métrica: tabla "Qué se calcula hoy" del ADR-007.
