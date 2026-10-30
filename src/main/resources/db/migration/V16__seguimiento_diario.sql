-- =====================================================================
-- Physical Change · V16 · Seguimiento diario del cliente (diario y peso único por día)
-- (PostgreSQL 16) — Fase 6.6, T201. Especificación: docs/diseno-fase6.md §3.4.1 y §3.8 (D1, D2, D3)
-- Contrato: physical-change-back/docs/api-cliente-seguimiento.md
-- No editar una vez desplegado.
--
-- Reversión manual (restaura los registros de peso duplicados tal y como estaban antes de V16;
-- se pierden los cambios hechos después de V16 en los días que tenían duplicados):
--   ALTER TABLE progress_entry DROP CONSTRAINT uq_progress_entry_client_date;
--   CREATE INDEX ix_progress_client_date ON progress_entry (client_id, entry_date);
--   DELETE FROM progress_entry WHERE id IN (SELECT kept_id FROM progress_entry_dup_v16);
--   INSERT INTO progress_entry (id, client_id, entry_date, weight_kg, body_fat_pct, notes)
--       SELECT id, client_id, entry_date, weight_kg, body_fat_pct, notes FROM progress_entry_dup_v16;
--   DROP TABLE progress_entry_dup_v16;
--   DROP TABLE client_daily_log;
--   DELETE FROM flyway_schema_history WHERE version = '16';
-- =====================================================================

-- ---------- D2 · Peso único por día ----------
-- Copia de seguridad de TODAS las filas de los días duplicados (también la que se conserva, con sus valores originales).
-- Es temporal: se borrará en una migración posterior, cuando se hayan revisado los datos.
CREATE TABLE progress_entry_dup_v16 (
    id           BIGINT        PRIMARY KEY,
    client_id    BIGINT        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    entry_date   DATE          NOT NULL,
    weight_kg    NUMERIC(5, 2),
    body_fat_pct NUMERIC(4, 1),
    notes        VARCHAR(500),
    kept_id      BIGINT        NOT NULL,   -- fila de progress_entry que queda para ese día
    archived_at  TIMESTAMP     NOT NULL DEFAULT now()
);

INSERT INTO progress_entry_dup_v16 (id, client_id, entry_date, weight_kg, body_fat_pct, notes, kept_id)
SELECT p.id, p.client_id, p.entry_date, p.weight_kg, p.body_fat_pct, p.notes, g.kept_id
FROM progress_entry p
JOIN (SELECT client_id, entry_date, MAX(id) AS kept_id
      FROM progress_entry
      GROUP BY client_id, entry_date
      HAVING COUNT(*) > 1) g ON g.client_id = p.client_id AND g.entry_date = p.entry_date;

-- Se conserva la más reciente (id mayor); cada campo toma el valor más reciente no nulo de ese día.
UPDATE progress_entry k SET
    weight_kg    = (SELECT d.weight_kg FROM progress_entry_dup_v16 d
                    WHERE d.kept_id = k.id AND d.weight_kg IS NOT NULL ORDER BY d.id DESC LIMIT 1),
    body_fat_pct = (SELECT d.body_fat_pct FROM progress_entry_dup_v16 d
                    WHERE d.kept_id = k.id AND d.body_fat_pct IS NOT NULL ORDER BY d.id DESC LIMIT 1),
    notes        = (SELECT d.notes FROM progress_entry_dup_v16 d
                    WHERE d.kept_id = k.id AND d.notes IS NOT NULL AND d.notes <> '' ORDER BY d.id DESC LIMIT 1)
WHERE k.id IN (SELECT kept_id FROM progress_entry_dup_v16);

DELETE FROM progress_entry WHERE id IN (SELECT id FROM progress_entry_dup_v16 WHERE id <> kept_id);

-- La restricción única crea su propio índice (client_id, entry_date): el de V1 sobra.
DROP INDEX ix_progress_client_date;
ALTER TABLE progress_entry ADD CONSTRAINT uq_progress_entry_client_date UNIQUE (client_id, entry_date);

-- ---------- D1 · Diario del cliente: sueño, ánimo y día de descanso ----------
-- Una fila por cliente y día. El peso del día NO se guarda aquí: vive en progress_entry (D2).
-- La fila se borra si el cliente vacía los tres datos. log_date en hora de España (ADR-008).
CREATE TABLE client_daily_log (
    id             BIGSERIAL     PRIMARY KEY,
    client_id      BIGINT        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    log_date       DATE          NOT NULL,
    sleep_minutes  INTEGER       CHECK (sleep_minutes BETWEEN 0 AND 1440),
    mood           INTEGER       CHECK (mood BETWEEN 1 AND 5),
    rest_day       BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP     NOT NULL DEFAULT now(),
    -- también es el índice de las consultas por día y por mes (client_id, log_date BETWEEN ...)
    CONSTRAINT uq_client_daily_log_day UNIQUE (client_id, log_date)
);
