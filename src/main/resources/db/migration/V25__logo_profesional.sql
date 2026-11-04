-- =====================================================================
-- Physical Change · V25 · Logo del profesional (PostgreSQL 16) — T183
-- Contrato: physical-change-back/docs/api-logo.md. Si se borra el archivo, el usuario se queda sin logo.
-- No editar una vez desplegado.
--
-- Reversión manual:
--   ALTER TABLE app_user DROP COLUMN logo_file_id;
--   DELETE FROM flyway_schema_history WHERE version = '25';
-- =====================================================================

ALTER TABLE app_user ADD COLUMN logo_file_id BIGINT REFERENCES stored_file (id) ON DELETE SET NULL;
