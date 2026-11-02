# API · Fondos de la página de carga

- **Versión del contrato:** 2.0.0, 2026-11-01 (T215, correcciones de la revisión de seguridad T211). Anterior: 1.0.0 (T208).
- **Cambios de la 2.0.0** (incompatibles para el front, ver "Indicaciones para el front"):
  - `PublicBackgroundDto.original` es `null` si el fondo tiene variantes (todos salvo los WebP); `PublicBackgroundDto.name` es genérico (`"Fondo personalizado"`) en los fondos subidos.
  - `GET /api/public/backgrounds/{id}/original` sin firma solo responde si el fondo no tiene variantes (WebP); en la galería la URL del `original` va firmada salvo en ese caso.
  - El alias `active/{name}` ya no cae al original salvo en WebP: si falta la variante, sirve la `w*` más ancha.
  - `Cache-Control` sin firma: 24 h solo para los iniciales; 300 s para los subidos.
  - El original se guarda **sin metadatos** (EXIF, XMP, IPTC, comentarios, textos PNG); JPEG progresivo ≤ 12 MP; JPEG aritmético, sin pérdida o jerárquico se rechaza; ≤ 64 barridos (`SOS`).
- **Diseño:** `docs/diseno-loading-page.md` (§5 página de carga, §6 galería, dudas L-01…L-16).
- **Migración:** `V18__fondos_pagina_carga.sql` (V15 contenido, V16 seguimiento y V17 Stripe están reservadas por otras ramas).
- **Decisión de arquitectura:** §9 de este documento (propuesta de ADR-015; ADR-001 y ADR-005 para el almacenamiento).
- Todas las URL de imagen que devuelve la API son **relativas a la base de la API** (`/api`): el front antepone su `apiUrl`.

## Reglas de negocio
- **Un único fondo activo para todo el sitio** (L-03). Cualquier ADMIN o TRAINER lo cambia; se registra quién y cuándo.
- **Fondos iniciales** (L-08): `fondo3` y `fondo4`; activo por defecto `fondo4`. Sus imágenes van empaquetadas en el backend (no en la base de datos) y **no se pueden borrar**.
- **Galería**: solo ADMIN y TRAINER (L-04). COLLABORATOR y CLIENT reciben 403.
- **Borrado** (L-05): no se borran ni el activo ni los iniciales; el TRAINER solo borra los que subió; el ADMIN, cualquiera.
- **Subida**: JPG, PNG o WebP; lado largo ≥ 1920 px y lado corto ≥ 1080 px (se acepta vertical, L-13); ≤ 10 MB (10 × 1024 × 1024 bytes); ≤ 40 megapíxeles y ≤ 16 384 px por lado (**JPEG progresivo: ≤ 12 megapíxeles**). Solo JPEG secuencial o progresivo con Huffman (`SOF0`, `SOF1`, `SOF2`) y ≤ 64 barridos. Recomendado 3840 × 2160.
- Los fondos subidos cuentan en la cuota de almacenamiento de quien los sube (ADR-001/ADR-005).

## Modelos

### `BackgroundImageDto` — una imagen servible (original o variante)
```json
{ "name": "w1920", "width": 1920, "height": 1168, "contentType": "image/jpeg", "url": "/public/backgrounds/2/w1920" }
```
- `name`: `original`, `w768`, `w1280`, `w1920`, `p1080` o `thumb`.
- `url`: pública (sin parámetros) si el fondo es el activo o uno inicial (en el `original`, solo si además no tiene variantes, es decir, WebP); **firmada** (`?exp=…&sig=…`, caduca en 1 hora) si es un fondo subido que no es el activo. No se debe guardar: se pide de nuevo la galería.

### Variantes (L-11: las genera el servidor al subir; los iniciales, al arrancar)
| `name` | Medida | Uso |
|---|---|---|
| `w768`, `w1280`, `w1920` | Ancho fijo, alto proporcional. **Solo si el original es al menos así de ancho** (una vertical de 1080 px de ancho solo tiene `w768`). | `srcset` horizontal |
| `p1080` | Recorte vertical 9:19,5 centrado en el punto focal, hasta 1080 × 2340 (menos si el original no da tanto alto) | `srcset` vertical (móvil) |
| `thumb` | Recorte 16:9 de 480 × 270 | Miniatura de la galería |

- Todas las variantes son **JPEG** (calidad 0,80; `thumb` 0,75). No hay AVIF/WebP ni 3840 (ver §9).
- Un **WebP subido no tiene variantes** (`variants: []`) ni `lqip`: el front usa `original` para todo.

### `BackgroundDto` — fondo de la galería
```json
{
  "id": 7, "name": "gimnasio.jpg", "builtin": false, "active": false,
  "contentType": "image/jpeg", "width": 3840, "height": 2160, "sizeBytes": 2483112,
  "portrait": false, "objectPosition": "50% 50%",
  "lqip": "data:image/jpeg;base64,/9j/4AAQ…",
  "original": { "name": "original", "width": 3840, "height": 2160, "contentType": "image/jpeg", "url": "/public/backgrounds/7/original?exp=1793548800&sig=…" },
  "variants": [ { "name": "w768", "width": 768, "height": 432, "contentType": "image/jpeg", "url": "…" } ],
  "uploadedBy": { "id": 3, "fullName": "Laura Pérez" },
  "uploadedAt": "2026-11-01T10:15:00",
  "canDelete": true
}
```
- `builtin`: fondo inicial. `uploadedBy` y `uploadedAt` son `null` en los iniciales.
- `portrait`: `true` si el alto es mayor que el ancho (aviso no bloqueante del diseño, L-13).
- `objectPosition`: punto focal para `object-position`. Los iniciales traen el del diseño (`fondo3` `45% 55%`, `fondo4` `50% 35%`); los subidos, `50% 50%`.
- `lqip`: data URI JPEG de 32 px de ancho (< 2 KB) o `null` (WebP).
- `canDelete`: lo calcula el servidor con L-05 (no inicial, no activo, y ADMIN o quien lo subió). El botón "Eliminar" lo usa; el servidor vuelve a comprobarlo.
- `width`/`height`: tal como se ve la imagen (con la orientación EXIF aplicada).

### `BackgroundGalleryDto`
```json
{
  "active": { "backgroundId": 2, "changedBy": { "id": 3, "fullName": "Laura Pérez" }, "changedAt": "2026-11-01T10:20:00" },
  "items": [ /* BackgroundDto */ ]
}
```
- `changedBy`/`changedAt` son `null` mientras nadie lo haya cambiado (valor inicial). `changedBy.fullName` es `null` si el usuario ya no existe.
- **Orden de `items`** (diseño §6.2): el activo primero, después los subidos (más recientes primero) y al final los iniciales.

### `PublicBackgroundDto` — fondo activo para la página pública (sin datos personales)
```json
{
  "id": 2, "name": "Fondo 4", "builtin": true, "contentType": "image/jpeg", "width": 5440, "height": 3310,
  "objectPosition": "50% 35%", "lqip": "data:image/jpeg;base64,…",
  "original": null,
  "variants": [ /* BackgroundImageDto, URL públicas */ ]
}
```
- `name`: el de los iniciales (`"Fondo 3"`, `"Fondo 4"`); en los subidos, siempre `"Fondo personalizado"` (no se publica el nombre del archivo, B-3 de T211).
- `original`: **`null` si hay variantes** (2.0.0). Solo un WebP subido (sin variantes) trae `original` con su URL pública. El original no se publica para no servir sin necesidad el archivo pesado (M-3 de T211).

### Errores de fondos: `BackgroundErrorResponse`
```json
{
  "timestamp": "2026-11-01T10:15:00Z", "status": 400, "error": "Bad Request",
  "message": "La imagen es demasiado pequeña: mide 1600 × 900 px. …",
  "code": "BACKGROUND_TOO_SMALL",
  "reasons": [ { "code": "BACKGROUND_TOO_SMALL", "message": "La imagen es demasiado pequeña: …" } ],
  "width": 1600, "height": 900, "sizeBytes": 412345
}
```
- Es el `ErrorResponse` habitual más `code` (el primer motivo), `reasons` (todos los motivos, para listarlos, diseño §6.4) y las medidas si se conocen (`null` si no).
- `message` de cada motivo está en español y listo para mostrarse.

| `code` | HTTP | Cuándo |
|---|---|---|
| `BACKGROUND_FORMAT` | 400 | Extensión distinta de `.jpg/.jpeg/.png/.webp` o `Content-Type` declarado que no es de imagen admitida |
| `BACKGROUND_TOO_LARGE` | 413 | Más de 10 MB. Si además falla el formato, `reasons` trae los dos (el de tamaño primero). Si el archivo supera el límite del servidor antes de llegar al servicio, solo trae este motivo y `sizeBytes` es `null` |
| `BACKGROUND_SIGNATURE` | 400 | El contenido no es JPEG/PNG/WebP o no coincide con la extensión (p. ej. un PNG llamado `.jpg`) |
| `BACKGROUND_UNREADABLE` | 400 | Archivo vacío, cabecera dañada o truncada, el decodificador no puede leerlo, JPEG aritmético/sin pérdida/jerárquico o con más de 64 barridos |
| `BACKGROUND_TOO_MANY_PIXELS` | 400 | Más de 40 MP o algún lado > 16 384 px (se rechaza leyendo solo la cabecera); JPEG progresivo de más de 12 MP |
| `BACKGROUND_TOO_SMALL` | 400 | Lado largo < 1920 o lado corto < 1080 |
| `BACKGROUND_QUOTA` | 409 | Se supera la cuota de almacenamiento del usuario o el tope global |
| `BACKGROUND_BUSY` | 503 | El servidor lleva 30 s procesando otra imagen: reintentar en unos segundos |
| `BACKGROUND_NOT_FOUND` | 404 | El fondo no existe (o no es público y la URL no va firmada) |
| `BACKGROUND_BUILTIN` | 409 | Borrar un fondo inicial |
| `BACKGROUND_ACTIVE` | 409 | Borrar el fondo activo |
| `BACKGROUND_NOT_OWNER` | 403 | Un TRAINER borra un fondo que no subió |

Los 401/403 por rol (sin token, COLLABORATOR, CLIENT) y los 400 de validación del cuerpo usan el `ErrorResponse` habitual, sin `code`.

## Endpoints de la galería (ADMIN y TRAINER)

### `GET /api/backgrounds`
- 200 → `BackgroundGalleryDto`.

### `POST /api/backgrounds` — subir un fondo
- `multipart/form-data` con la parte **`file`**. Una imagen por petición (L-06).
- Comprobaciones, en orden: formato declarado (extensión y `Content-Type`) y tamaño → firma del contenido → cabecera (dimensiones sin decodificar) → límite de píxeles → estructura completa y limpieza de metadatos (tipo de JPEG, 12 MP si es progresivo, barridos) → mínimo 1920 × 1080 → decodificación y variantes.
- No se guarda nada si algo falla.
- 201 → `BackgroundDto` (queda en la galería, **no activo**; el front lo deja seleccionado).
- Errores: tabla anterior. La generación de variantes puede tardar unos segundos (estado "Procesando…" del diseño).

### `PUT /api/backgrounds/active` — "Cambiar fondo"
```json
{ "backgroundId": 7 }
```
- 200 → `BackgroundGalleryDto` actualizado (con `active.changedBy` y `changedAt`).
- 400 si falta `backgroundId`; 404 `BACKGROUND_NOT_FOUND`.
- El cambio es inmediato para todo el mundo; la página pública lo verá en ≤ 60 s (caché, abajo).

### `DELETE /api/backgrounds/{id}`
- 204. Borra el fondo, su original y sus variantes (base de datos o R2).
- 404, 409 `BACKGROUND_BUILTIN`, 409 `BACKGROUND_ACTIVE`, 403 `BACKGROUND_NOT_OWNER`.
- Los archivos de fondos no se pueden borrar por `DELETE /api/files/{id}` (400).

## Endpoints públicos (sin login)

### `GET /api/public/backgrounds/active`
- 200 → `PublicBackgroundDto`. `Cache-Control: public, max-age=60` y `ETag: "active-{id}"`; con `If-None-Match` igual → 304.
- Si falla, la página usa el fondo de reserva empaquetado en el front (L-12).

### `GET /api/public/backgrounds/active/{name}` — alias estable del fondo activo
- `name`: `original`, `w768`, `w1280`, `w1920`, `p1080`, `thumb`. Otro nombre → 404.
- Devuelve la imagen del activo con `Cache-Control: public, max-age=300` y `ETag: "bg-{id}-{imagen servida}"`.
- Si el activo **no tiene variantes** (WebP), devuelve el original para cualquier `name`.
- Si tiene variantes y falta la pedida (p. ej. `w1920` de una vertical de 1080 px de ancho) o se pide `original`, devuelve la **`w*` más ancha** que exista (siempre hay al menos `w768`). Nunca devuelve el original de un JPEG o PNG.
- Pensado para `<link rel="preload" as="image" imagesrcset="…/active/w768 768w, …/active/w1280 1280w, …/active/w1920 1920w">` en `index.html` (diseño §5.2).

### `GET /api/public/backgrounds/{id}/{name}[?exp=&sig=]` — una imagen concreta
- Sin firma: solo el fondo activo y los iniciales, y de ellos solo las variantes (el `original` sin firma, solo si el fondo no tiene variantes: WebP); el resto → 404 (no se revela que existe).
- Con firma (las URL de la galería): cualquier fondo; firma no válida o caducada → 403.
- Variante inexistente → 404 (aquí no hay vuelta al original).
- Cabeceras: `Cache-Control: public, max-age=86400` (sin firma, fondos iniciales), `public, max-age=300` (sin firma, fondo subido activo: deja de ser público al cambiar el fondo) o `private, max-age=3600` (firmada); `ETag: "bg-{id}-{name}"` (304 con `If-None-Match`); `X-Content-Type-Options: nosniff`; `Content-Security-Policy: default-src 'none'; sandbox`; `Content-Disposition: inline`.
- Si el archivo está en R2 (ADR-005), responde **302** a una URL prefirmada de 1 hora (`Cache-Control` de 300 s en la redirección). `<img>` lo sigue sin hacer nada.

## Indicaciones para el front (T209)
- Página `/entrada`: `GET /api/public/backgrounds/active` → pinta `lqip` (si no es `null`), y monta el `<picture>` con:
  - vertical (`orientation: portrait`, `max-width: 1023px`): `p1080` si existe;
  - horizontal: `srcset` con las `w*` que vengan; `src` = `w1920` o, si no hay, la `w*` más ancha; **`original` solo si `variants` está vacío** (WebP); en los demás casos es `null` (2.0.0);
  - `object-position: objectPosition`.
- Si se reescriben las URL al alias `active/{name}`, no hay que hacerlo con `original` cuando es `null`; el alias ya resuelve las variantes que falten.
- Galería: miniatura = `thumb` o, si no hay, `original` (en la galería `original` nunca es `null`; su URL va firmada salvo en un WebP activo); aviso de vertical con `portrait`; "Eliminar" según `canDelete`; mensajes de rechazo con `reasons[].message`.
- Las URL firmadas caducan en 1 hora: al volver a la pantalla, recargar la galería.

## Seguridad
- Firma de contenido obligatoria y coherente con la extensión; `Content-Type` de la respuesta según el formato real.
- **Bombas de descompresión:** las dimensiones se leen de la cabecera (PNG `IHDR`, JPEG `SOF`, WebP `VP8 `/`VP8L`/`VP8X`) **sin decodificar los píxeles**; si superan 40 MP o 16 384 px por lado se rechaza antes de decodificar. Al decodificar, se comprueba que el decodificador ve las mismas dimensiones que la cabecera, se submuestrea a ≤ 3840 × 2160 (≈ 33 MB en memoria), cualquier aviso del decodificador se trata como imagen dañada, y solo se procesa **una imagen a la vez** en todo el servidor. ImageIO trabaja sin caché en disco.
- El WebP no se decodifica (Java no trae decodificador): solo se valida su cabecera y el tamaño del contenedor RIFF, y se sirve tal cual con `nosniff`.
- Las variantes se recodifican (no conservan EXIF ni metadatos).
- **El original se guarda sin metadatos** (T215, M-1 de T211), quitando segmentos y fragmentos sin recodificar los píxeles:
  - JPEG: se quitan `APP1`–`APP15` (EXIF, XMP, IPTC/Photoshop, MPF…), `COM`, los marcadores reservados `JPGn` y todo lo que va tras `EOI` (imágenes añadidas). Se conservan `APP2 ICC_PROFILE` (color), `APP14 Adobe` (transformación de color) y `APP0 JFIF` solo si no lleva miniatura. **Orientación:** si el EXIF traía una orientación distinta de 1, se escribe un `APP1` Exif mínimo con **solo** esa etiqueta, para que el original se siga viendo igual que las variantes.
  - PNG: se conservan los fragmentos críticos y `tRNS`, `cHRM`, `gAMA`, `iCCP`, `sBIT`, `sRGB`, `cICP`, `mDCV`, `cLLI`, `bKGD`, `pHYs`; se quitan `tEXt`, `zTXt`, `iTXt`, `eXIf`, `tIME`, cualquier otro auxiliar y lo que va tras `IEND`. Un PNG con orientación en `eXIf` se ve sin girar (como sus variantes, que ya la ignoraban).
  - WebP: se conservan `VP8X`, `ICCP`, `ANIM`, `ANMF`, `ALPH`, `VP8 `, `VP8L`; se quitan `EXIF`, `XMP ` y los desconocidos, se apagan los indicadores EXIF y XMP de `VP8X` y se recalcula el tamaño RIFF.
  - `sizeBytes` es el tamaño del original ya limpio (es lo que cuenta en la cuota).
- **JPEG progresivos y exóticos** (M-2 de T211): se recorre toda la estructura antes de decodificar. `SOF2` (progresivo) de más de 12 MP → `BACKGROUND_TOO_MANY_PIXELS` (el decodificador de Java guarda los coeficientes a resolución completa aunque se submuestree). `SOF3`, `SOF5`–`SOF7`, `SOF9`–`SOF15` (sin pérdida, jerárquico, aritmético; ImageIO no los decodifica) → `BACKGROUND_UNREADABLE` con mensaje propio. Más de 64 barridos (`SOS`), varios `SOF`, falta de `EOI` o segmentos fuera de sitio → `BACKGROUND_UNREADABLE`.
- La URL firmada usa HMAC-SHA256 con una clave derivada de `app.jwt.secret`; incluye id, nombre de la imagen y caducidad.
- El endpoint público no devuelve datos personales (ni quién subió ni quién cambió el fondo).

## 9. Decisión de arquitectura (propuesta de ADR-015, L-11)
**Contexto:** la página pública necesita imágenes ligeras para móvil y escritorio, y los fondos se suben desde el navegador.

**Decisión:**
1. **Variantes en el servidor, al subir**, con Java ImageIO (sin dependencias nuevas ni procesos externos). Se guardan como archivos más (`stored_file`, `purpose = BACKGROUND`) en el almacenamiento activo (base de datos o R2, ADR-005) y se registran en `loading_background_variant`.
2. **Formato de las variantes: JPEG.** ImageIO no codifica WebP ni AVIF; añadir una librería nativa (libvips, libwebp) complica la imagen Docker de Render. Se descartan por ahora las variantes AVIF/WebP, la `3840` y la `p720` del diseño §5.2 y el LQIP en WebP (se entrega en JPEG, < 2 KB). Si se suben WebP, se sirven sin variantes.
3. **Iniciales en el classpath**, con los metadatos sembrados en V18 y las variantes generadas en memoria al arrancar (≈ 1 MB por fondo).
4. **Imágenes servidas por el backend** (caché HTTP pública para lo público) o por redirección a URL prefirmadas de R2.

**Alternativas descartadas:** generar las variantes en el navegador (L-11 lo descarta); servicio externo de imágenes (Cloudflare Images: coste y otra cuenta); librería nativa (imagen Docker y superficie de ataque mayores).

**Riesgos:**
- Memoria: una decodificación a la vez y submuestreo a 4K. Los JPEG **progresivos** se limitan a 12 MP (T215).
- ~~El original conserva EXIF (posible GPS).~~ Resuelto en 2.0.0 (T215): se limpian los metadatos al subir **y** el original de un JPEG/PNG no se publica sin firma. Los WebP siguen sin recodificarse (B-4 de T211).
- Sin AVIF/WebP, la `w1920` pesa más que el objetivo del diseño (≤ 250 KB en AVIF).
- Coste: cada subida guarda el original y hasta 5 JPEG (≈ +20 % del original).
