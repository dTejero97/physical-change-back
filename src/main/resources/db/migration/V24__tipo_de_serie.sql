-- =====================================================================
-- Physical Change · V24 · Tipo de serie del registro (PostgreSQL 16) — T220
-- Diseño: docs/diseno-entreno-hevy.md §6. WARMUP (calentamiento) no cuenta en volumen, repeticiones ni récords;
-- FAILURE (al fallo) y DROP (descendente) cuentan como una serie normal. Las filas existentes quedan NORMAL.
-- No editar una vez desplegado.
--
-- Reversión manual (se pierde el tipo; todas pasan a contar como normales):
--   ALTER TABLE exercise_log DROP COLUMN set_type;
--   DELETE FROM flyway_schema_history WHERE version = '24';
-- =====================================================================

ALTER TABLE exercise_log ADD COLUMN set_type VARCHAR(8) NOT NULL DEFAULT 'NORMAL'
    CHECK (set_type IN ('WARMUP', 'NORMAL', 'FAILURE', 'DROP'));
