-- =====================================================================
-- Physical Change · V18 · Fondos de la página de carga (PostgreSQL 16) — Fase 9.1, T208
-- Diseño: docs/diseno-loading-page.md · Contrato: physical-change-back/docs/api-fondos.md · Decisión: api-fondos.md §9 (propuesta de ADR-015)
-- (V15 contenido, V16 seguimiento y V17 Stripe están reservadas por otras ramas.)
-- No editar una vez desplegado.
--
-- Fondos iniciales: solo fondo3 y fondo4 (decisión del usuario, L-08); activo por defecto fondo4. Sus imágenes NO van en la
-- base de datos: están en el classpath del backend (src/main/resources/backgrounds/{clave}.jpg). Aquí solo sus metadatos.
-- Los subidos guardan el original y sus variantes en stored_file (purpose BACKGROUND), en la base de datos o en R2 (ADR-005).
--
-- Reversión manual (borra los fondos subidos y sus archivos):
--   DROP TABLE loading_background_setting;
--   DROP TABLE loading_background_variant;
--   CREATE TEMP TABLE bg_files AS SELECT id FROM stored_file WHERE purpose = 'BACKGROUND';
--   DROP TABLE loading_background;
--   DELETE FROM stored_file_data WHERE file_id IN (SELECT id FROM bg_files);
--   DELETE FROM stored_file WHERE id IN (SELECT id FROM bg_files);   -- en R2, borrar también files/{owner}/{id}
--   ALTER TABLE stored_file DROP CONSTRAINT stored_file_purpose_check;
--   ALTER TABLE stored_file ADD CONSTRAINT stored_file_purpose_check CHECK (purpose IN ('DOCUMENT', 'IMAGE', 'VIDEO'));
--   DELETE FROM flyway_schema_history WHERE version = '18';
-- =====================================================================

-- ---------- Archivos: nuevo propósito ----------
ALTER TABLE stored_file DROP CONSTRAINT stored_file_purpose_check;
ALTER TABLE stored_file ADD CONSTRAINT stored_file_purpose_check CHECK (purpose IN ('DOCUMENT', 'IMAGE', 'VIDEO', 'BACKGROUND'));

-- ---------- Galería ----------
-- builtin_key: fondo inicial (imagen en el classpath, no se borra). file_id: original subido. Exactamente uno de los dos.
CREATE TABLE loading_background (
    id              BIGSERIAL     PRIMARY KEY,
    builtin_key     VARCHAR(20)   UNIQUE,
    name            VARCHAR(200)  NOT NULL,
    content_type    VARCHAR(20)   NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    width           INTEGER       NOT NULL CHECK (width > 0),
    height          INTEGER       NOT NULL CHECK (height > 0),
    size_bytes      BIGINT        NOT NULL CHECK (size_bytes > 0),
    object_position VARCHAR(20)   NOT NULL DEFAULT '50% 50%',
    lqip            VARCHAR(4000),                       -- data URI de 32 px (null en WebP y en los iniciales: se calcula al arrancar)
    file_id         BIGINT        UNIQUE REFERENCES stored_file (id),
    uploaded_by     BIGINT        REFERENCES app_user (id) ON DELETE SET NULL,
    created_at      TIMESTAMP     NOT NULL,
    CONSTRAINT loading_background_source_check CHECK ((builtin_key IS NULL) <> (file_id IS NULL))
);
CREATE INDEX ix_background_uploaded_by ON loading_background (uploaded_by);

-- Variantes reducidas (JPEG) generadas por el servidor al subir (ADR-015). Las de los iniciales no se guardan (memoria).
CREATE TABLE loading_background_variant (
    id            BIGSERIAL    PRIMARY KEY,
    background_id BIGINT       NOT NULL REFERENCES loading_background (id) ON DELETE CASCADE,
    variant       VARCHAR(10)  NOT NULL CHECK (variant IN ('w768', 'w1280', 'w1920', 'p1080', 'thumb')),
    file_id       BIGINT       NOT NULL UNIQUE REFERENCES stored_file (id),
    width         INTEGER      NOT NULL CHECK (width > 0),
    height        INTEGER      NOT NULL CHECK (height > 0),
    CONSTRAINT uq_background_variant UNIQUE (background_id, variant)
);

-- ---------- Fondo activo (uno para todo el sitio, L-03) ----------
-- La FK impide borrar el fondo activo aunque falle la comprobación del servicio.
CREATE TABLE loading_background_setting (
    id                   SMALLINT   PRIMARY KEY CHECK (id = 1),
    active_background_id BIGINT     NOT NULL REFERENCES loading_background (id),
    changed_by           BIGINT     REFERENCES app_user (id) ON DELETE SET NULL,
    changed_at           TIMESTAMP
);

-- ---------- Siembra: fondos iniciales (medidas y peso de src/main/resources/backgrounds, comprobados en BackgroundImagesTest) ----------
INSERT INTO loading_background (builtin_key, name, content_type, width, height, size_bytes, object_position, created_at) VALUES
    ('fondo3', 'Fondo 3', 'image/jpeg', 3800, 2376, 1377054, '45% 55%', CURRENT_TIMESTAMP),
    ('fondo4', 'Fondo 4', 'image/jpeg', 5440, 3310,  298553, '50% 35%', CURRENT_TIMESTAMP);

INSERT INTO loading_background_setting (id, active_background_id)
    SELECT 1, id FROM loading_background WHERE builtin_key = 'fondo4';
