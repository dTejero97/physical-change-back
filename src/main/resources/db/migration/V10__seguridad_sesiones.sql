-- =====================================================================
-- Physical Change · V10 · Revocación de sesiones (PostgreSQL 16) — T141
-- token_version viaja en el JWT (claim "tv") y se compara en cada petición.
-- Se incrementa al desactivar la cuenta, al cambiar la contraseña y al cerrar sesión:
-- los tokens emitidos antes dejan de valer al momento (Nota de Seguridad T140, Medio 1).
-- No editar una vez desplegado.
--
-- Reversión manual:
--   ALTER TABLE app_user DROP COLUMN token_version;
--   DELETE FROM flyway_schema_history WHERE version = '10';
-- =====================================================================

ALTER TABLE app_user ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
