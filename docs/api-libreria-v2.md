# Contrato API · Librería V2 (v1.4.0)

Archivos, Programas, Documentos, Formularios, Plantillas y Logros.
Decisiones: `docs/adr/ADR-001-almacenamiento-archivos.md` (raíz del proyecto). Pantallas: `docs/fase4-pantallas-v2.md`.
Migración: `src/main/resources/db/migration/V4__libreria_v2.sql` (incluye el catálogo del sistema).

Reglas comunes
- JWT obligatorio y rol TRAINER o ADMIN (salvo `/api/public/files`). Cada profesional solo ve y modifica lo suyo; el contenido del sistema (plantillas, categorías y logros base) es de solo lectura.
- Errores `{ timestamp, status, error, message }` (400, 403, 404, 409, 413 archivo demasiado grande).
- `creatorName`: nombre del profesional, o "Physical Change" en el contenido del sistema.

## Archivos — `/api/files`
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| POST | `/api/files` | multipart: `file`, `purpose` = DOCUMENT \| IMAGE | 201 `FileDto` · 400 tipo no permitido · 409 cuota superada · 413 > 10 MB |
| DELETE | `/api/files/{id}` | — | 204 (solo el dueño) |
| GET | `/api/public/files/{id}?exp&sig` | — (sin JWT) | contenido; 403 firma no válida o caducada |

`FileDto { id, name, contentType, size, url }` · `url` = URL firmada válida 1 hora, **relativa a la base de la API** (`/public/files/{id}?exp&sig`; el frontend la antepone con `environment.apiUrl`). Igual en `DocumentDto.url`, `AchievementDto.badgeUrl` y `coverUrl` de la Agenda.
Tipos: DOCUMENT → PDF, PNG, JPEG, WEBP, TXT, CSV, DOCX, XLSX, PPTX. IMAGE → PNG, JPEG, WEBP (sin SVG). Se comprueba la firma del contenido.
Cuota: 50 MB por profesional.

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
`AchievementDto { id, categoryId, name, description, groupLabel, badge, badgeFileId, badgeUrl, tier, conditionType, exerciseId, exerciseName, weightKg, reps, days, targetKg, months, durationType, startDate, endDate, daysAfter, system, hidden, createdAt }`
`badge`: clave de insignia del sistema (p. ej. `workouts`, `cardio`, `sessions`, `nutrition`, `videos`, `streak`, `perfect`, `early`, `birthday`, `new-year`, `start`, `valentine`, `custom`); `tier`: BRONZE | SILVER | TEAL.
El desbloqueo automático por cliente es T58.
