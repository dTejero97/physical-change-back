# API Librería (T27) — cardio, vídeos, recetas, planes de nutrición, agrupaciones, alimentos y preferencias

Versión 1.0.0 · 2026-10-28 · PostgreSQL 16 · Migración `V9__libreria_items.sql`

Persiste lo que hasta ahora el frontend guardaba en `localStorage` (`core/mock-store.ts`).
Todo es **propio del profesional** autenticado (`ADMIN` o `TRAINER`); lo ajeno responde **404**.
El contenido del sistema (cardio "Physical Change", recetas y alimentos de ejemplo) sigue en el frontend como semilla de solo lectura; aquí solo se guarda lo que crea o edita el profesional.

## Decisión de diseño

Las estructuras son anidadas (plan → comidas → opciones → líneas; receta → líneas y macros), las lee y escribe solo el frontend y no hay consultas por su contenido.
Por eso se guardan como **documento JSON por elemento** (`library_item.data`), con el mismo criterio que `template_item.payload` en V4, en vez de 10 tablas nuevas.
- Lo que sí se consulta o tiene integridad propia va en columnas: `kind`, `name`, dueño, fechas y **archivos** (`library_item_file`).
- Las imágenes **no** van dentro del JSON (nada de `data:` URL): se suben con `POST /api/files` (`purpose=IMAGE`) y se enlazan por `fileIds`. Así las URLs firmadas (1 h) se generan al leer y la cuota de archivos las cuenta.
- Si más adelante hace falta consultar dentro (p. ej. recetas para Physical AI), PostgreSQL puede leer el JSON; si se vuelve frecuente, se normaliza ese tipo concreto con su migración.

## Elementos — `/api/library/items`

| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/library/items?kind=RECIPE` | — | `LibraryItemDto[]` (más reciente primero) |
| GET | `/api/library/items/{id}` | — | `LibraryItemDto` |
| POST | `/api/library/items` | `LibraryItemRequest` | 201 `LibraryItemDto` |
| PUT | `/api/library/items/{id}` | `LibraryItemRequest` (el `kind` no cambia: si es distinto, 400) | `LibraryItemDto` |
| DELETE | `/api/library/items/{id}` | — | 204; borra también sus archivos |

`kind`: `CARDIO` · `VIDEO` · `RECIPE` · `NUTRITION_PLAN` · `PLAN_GROUP` · `FOOD`

`LibraryItemRequest { kind, name (1-160), data (objeto JSON), fileIds? (≤ 4, archivos IMAGE propios) }`
`LibraryItemDto { id, kind, name, data, files: [{ id, name, url }], creator, createdAt, updatedAt }`

Validación (400 con mensaje):
- `data` debe ser un objeto JSON de como máximo **200 KB** serializado y **sin** cadenas que empiecen por `data:` ("Las imágenes se suben como archivo").
- `fileIds`: cada uno debe ser un archivo propio (si no, 403/404) ya subido, con `purpose = IMAGE`; en `VIDEO` también se admite `purpose = VIDEO` (vídeo propio subido a R2 con `POST /api/files/uploads`, T93). Otro tipo → 400. Al actualizar, los archivos que dejan de estar enlazados se borran.
- Máximo **2.000 elementos por profesional y tipo** (409 "Has llegado al máximo de elementos de este tipo").

Forma de `data` por tipo (la valida el frontend; el servidor solo exige objeto, tamaño y sin `data:`). Son las interfaces de `physical-change-front` sin `id`, `creator` ni fechas:
- `CARDIO`: `{ cover }` (`Cardio` en `actividad-data.service.ts`; la portada subida pasa a `fileIds[0]`).
- `VIDEO`: `{ url (YouTube/Vimeo o "file:<id>" del vídeo propio), instructions, tags, visibility, clientTags, visible, thumb, emoji, duration }` (`VideoItem`).
- `RECIPE`: `{ emoji, thumb, tags, minutes, servings, category, instructions, items, ingredients, perServing, allergens, diet, mediaType, videoUrl, linkUrl, visible }` (`Recipe`; las fotos son `fileIds`).
- `NUTRITION_PLAN`: `{ description, type, emoji, meals, targets, days }` (`LibPlan`; `days` guarda ids de `library_item`).
- `PLAN_GROUP`: `{ description, planIds }` (`PlanGroup`).
- `FOOD`: `{ kind, kcal, protein, carbs, fat, fiber, sugars, satFat, salt, link, measures }` (`CatalogFood`).

## Preferencias — `/api/library/prefs/{key}`

Un documento JSON por profesional y clave, para metadatos que cuelgan de otros registros.

| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/library/prefs/{key}` | — | `{ key, data, updatedAt, files }`; `data = {}` si no existe |
| PUT | `/api/library/prefs/{key}` | `{ data }` | `{ key, data, updatedAt, files }` |

`key`: `EXERCISE_META` (favoritos, etiquetas y vídeo por id de ejercicio) · `EXERCISE_TAGS` (mis etiquetas) · `WORKOUT_META` · `FOOD_META` (metadatos de alimentos de `/api/nutrition/foods`).
Validación: objeto JSON de como máximo **512 KB**, sin `data:` URL. Otra clave → 400.
**Imágenes:** se suben con `POST /api/files` (IMAGE) y se citan dentro de `data` como texto `"file:<id>"`. La respuesta trae `files: [{ id, url }]` con la URL firmada de cada imagen citada que sea del profesional (las ajenas se ignoran). El frontend usa la misma convención dentro de `data` de los elementos, además de enviar sus `fileIds`. Una imagen sustituida en una preferencia no se borra sola: cuenta en la cuota hasta la limpieza periódica (Propuestas).

## Permisos y RGPD
- `@PreAuthorize("hasAnyRole('ADMIN','TRAINER')")`. Un cliente no accede a esta API (la app del cliente, Fase 6, tendrá sus propias rutas de lectura).
- No hay datos personales de clientes en estos documentos, salvo `clientTags` de los vídeos (nombres de etiquetas, ver la nota de Seguridad T83 sobre datos de salud en etiquetas).
- Reversión: ver la cabecera de `V9__libreria_items.sql`.
