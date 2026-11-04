-- =====================================================================
-- Physical Change · V26 · Registro de auditoría (PostgreSQL 16) — T232
-- Decisión: docs/adr/ADR-016-auditoria.md · Contrato: physical-change-back/docs/api-auditoria.md
-- Solo inserción desde la aplicación; la purga borra lo que tiene más de 12 meses (A-02).
-- actor_id y client_id sin clave foránea: el registro se conserva aunque se borre el usuario.
-- No editar una vez desplegado.
--
-- Reversión manual (se pierde el registro de auditoría):
--   DROP TABLE audit_event;
--   DELETE FROM flyway_schema_history WHERE version = '26';
-- =====================================================================

CREATE TABLE audit_event (
    id          BIGSERIAL    PRIMARY KEY,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor_id    BIGINT,                       -- null = el sistema (webhook, tareas programadas)
    actor_role  VARCHAR(20),
    action      VARCHAR(40)  NOT NULL,
    entity_type VARCHAR(40),
    entity_id   BIGINT,
    client_id   BIGINT,
    summary     VARCHAR(300),
    ip          VARCHAR(45),                  -- truncada: IPv4 /24, IPv6 /48
    request_id  VARCHAR(36)
);
CREATE INDEX ix_audit_time ON audit_event (occurred_at DESC);
CREATE INDEX ix_audit_actor ON audit_event (actor_id, occurred_at DESC);
CREATE INDEX ix_audit_client ON audit_event (client_id, occurred_at DESC);
CREATE INDEX ix_audit_action ON audit_event (action, occurred_at DESC);
