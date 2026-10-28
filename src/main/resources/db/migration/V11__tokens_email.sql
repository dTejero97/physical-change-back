-- =====================================================================
-- Physical Change · V11 · Tokens de un solo uso enviados por email (PostgreSQL 16) — T137
-- Recuperación de contraseña (RESET_PASSWORD) y verificación de cuenta (VERIFY_EMAIL, la usará el registro de T143).
-- Solo se guarda el SHA-256 del token: con la base de datos no se puede usar ningún enlace enviado.
-- No editar una vez desplegado.
--
-- Reversión manual:
--   DROP TABLE user_token;
--   DELETE FROM flyway_schema_history WHERE version = '11';
-- =====================================================================

CREATE TABLE user_token (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    purpose    VARCHAR(20) NOT NULL CHECK (purpose IN ('RESET_PASSWORD', 'VERIFY_EMAIL')),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP   NOT NULL,
    used_at    TIMESTAMP,
    created_at TIMESTAMP   NOT NULL
);
CREATE INDEX ix_user_token_user ON user_token (user_id, purpose);
