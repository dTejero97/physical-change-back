-- =====================================================================
-- Physical Change · V9 · Librería en el servidor (PostgreSQL 16) — T27
-- Cardio, vídeos, recetas, planes de nutrición, agrupaciones y alimentos propios como documentos JSON,
-- y preferencias por profesional (metadatos de ejercicios, workouts y alimentos).
-- Contrato y decisión de diseño: docs/api-libreria.md. Usa la V9: logros pasa a V10 y email a V11 (Flyway no admite huecos que se rellenen después).
-- No editar una vez desplegado.
--
-- Reversión manual (los datos de estas tablas se pierden; exportarlos antes si hiciera falta):
--   DELETE FROM stored_file WHERE id IN (SELECT file_id FROM library_item_file);
--   DROP TABLE library_item_file, library_item, library_pref;
--   DELETE FROM flyway_schema_history WHERE version = '9';
-- =====================================================================

CREATE TABLE library_item (
    id         BIGSERIAL PRIMARY KEY,
    trainer_id BIGINT       NOT NULL REFERENCES app_user (id),
    kind       VARCHAR(15)  NOT NULL CHECK (kind IN ('CARDIO', 'VIDEO', 'RECIPE', 'NUTRITION_PLAN', 'PLAN_GROUP', 'FOOD')),
    name       VARCHAR(160) NOT NULL,
    data       TEXT         NOT NULL,   -- objeto JSON (≤ 200 KB, validado en el servicio)
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL
);
CREATE INDEX ix_library_item_owner ON library_item (trainer_id, kind, created_at);

-- Imágenes del elemento (fotos de receta, portada de cardio). Cada archivo pertenece a un solo elemento.
CREATE TABLE library_item_file (
    item_id  BIGINT  NOT NULL REFERENCES library_item (id) ON DELETE CASCADE,
    file_id  BIGINT  NOT NULL UNIQUE REFERENCES stored_file (id),
    position INTEGER NOT NULL,
    PRIMARY KEY (item_id, file_id)
);

CREATE TABLE library_pref (
    trainer_id BIGINT      NOT NULL REFERENCES app_user (id),
    pref_key   VARCHAR(20) NOT NULL CHECK (pref_key IN ('EXERCISE_META', 'EXERCISE_TAGS', 'WORKOUT_META', 'FOOD_META')),
    data       TEXT        NOT NULL,    -- objeto JSON (≤ 512 KB)
    updated_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (trainer_id, pref_key)
);
