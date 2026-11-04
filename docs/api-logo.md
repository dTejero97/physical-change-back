# API Logo del profesional (T183) — contrato

Versión 1.0.0 · 2026-11-04 · Especificación: `docs/fase6-onboarding.md` (tarjeta del profesional, I-07). Migración **V25**.

El profesional (TRAINER o ADMIN) sube su logo y sus clientes lo ven en la tarjeta del profesional del Inicio. Si no hay logo, se ven sus iniciales.

## Subir y quitar (ADMIN, TRAINER)
1. Subir la imagen con la API de archivos que ya existe: `POST /api/files?purpose=IMAGE` (multipart; JPG, PNG o WebP comprobados por contenido; máximo 10 MB; ADR-001/ADR-005).
2. `PUT /api/auth/me/logo` `{ fileId }` → `UserDto`. Mismas reglas que el resto de archivos (`FileService.owned`): inexistente → 404; de otro usuario → 403 (el ADMIN puede usar cualquiera); que no sea `IMAGE` o no esté subido del todo → 400. Además, **como mucho 2 MB** (si no → 413).
3. `DELETE /api/auth/me/logo` → `UserDto` (quita el logo; el archivo se queda en su Librería).

CLIENT o COLLABORATOR → 403.

## Leer
`UserDto.logoUrl` (en `GET /api/auth/me` y en la respuesta del login):
- **TRAINER / ADMIN:** la URL firmada de su propio logo, o null.
- **CLIENT:** la del logo de su profesional: su responsable de entrenamiento o, si solo tiene de nutrición, el de nutrición (ADR-013). null si no tiene profesional o no ha subido logo.
- Otros roles: null.

Es una URL firmada que caduca en 1 hora (`/api/public/files/{id}?exp=…&sig=…`); la app la renueva al refrescar el perfil (al abrirse y al volver a la pestaña, T225).
