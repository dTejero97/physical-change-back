-- =====================================================================
-- Physical Change · V7 · Physical AI (PostgreSQL 16)
-- Instrucciones permanentes, uso (créditos y neuronas) y conversaciones del panel lateral.
-- Contrato: docs/api-ia.md. Decisiones: docs/adr/ADR-003-physical-ai.md (raíz del proyecto).
-- RGPD: las conversaciones se borran a los 30 días (lo hace AiService al usar el chat y al arrancar).
-- No se guardan prompts en ai_usage: solo contadores.
-- No editar una vez desplegado: cambios en V8__...
--
-- Reversión manual (se pierden instrucciones, historial de uso y conversaciones):
--   DROP TABLE ai_message, ai_conversation, ai_usage, ai_instruction;
--   DELETE FROM flyway_schema_history WHERE version = '7';
-- =====================================================================

CREATE TABLE ai_instruction (
    trainer_id BIGINT        PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    text       VARCHAR(1000) NOT NULL,
    updated_at TIMESTAMP     NOT NULL
);

-- Una fila por llamada al proveedor que termina bien (las generaciones fallidas no se cobran).
-- Créditos del mes = SUM(credits) del mes en curso; presupuesto diario = SUM(neurons) del día (toda la plataforma).
CREATE TABLE ai_usage (
    id            BIGSERIAL PRIMARY KEY,
    trainer_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    kind          VARCHAR(12) NOT NULL CHECK (kind IN ('WORKOUT', 'PROGRAM', 'DAILY_PLAN', 'RECIPE', 'CHAT')),
    credits       SMALLINT    NOT NULL CHECK (credits BETWEEN 0 AND 2),
    input_tokens  INTEGER     NOT NULL CHECK (input_tokens >= 0),
    output_tokens INTEGER     NOT NULL CHECK (output_tokens >= 0),
    neurons       INTEGER     NOT NULL CHECK (neurons >= 0),
    model         VARCHAR(80) NOT NULL,
    ref_type      VARCHAR(16),
    ref_id        BIGINT,
    created_at    TIMESTAMP   NOT NULL,
    CHECK ((kind = 'CHAT') = (credits = 0))
);
CREATE INDEX ix_ai_usage_trainer ON ai_usage (trainer_id, created_at);
CREATE INDEX ix_ai_usage_day ON ai_usage (created_at);

CREATE TABLE ai_conversation (
    id         BIGSERIAL PRIMARY KEY,
    trainer_id BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    title      VARCHAR(120) NOT NULL,
    context    VARCHAR(60),
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL
);
CREATE INDEX ix_ai_conv_trainer ON ai_conversation (trainer_id, updated_at);

CREATE TABLE ai_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT        NOT NULL REFERENCES ai_conversation (id) ON DELETE CASCADE,
    role            VARCHAR(10)   NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    text            VARCHAR(8000) NOT NULL,
    mentions        VARCHAR(1000) NOT NULL DEFAULT '[]',   -- JSON [{clientId, name}]
    created_at      TIMESTAMP     NOT NULL
);
CREATE INDEX ix_ai_msg_conv ON ai_message (conversation_id, created_at);
