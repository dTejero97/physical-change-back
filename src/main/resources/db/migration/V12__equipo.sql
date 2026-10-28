-- =====================================================================
-- Physical Change · V12 · Equipo: rol COLLABORATOR, permisos y clientes asignados (PostgreSQL 16) — T28, T142
-- Decisión: docs/adr/ADR-011-equipo-colaboradores.md · Contrato: docs/api-equipo.md
-- No editar una vez desplegado.
--
-- Reversión manual (antes, dar de baja o borrar los usuarios COLLABORATOR):
--   DROP TABLE collaborator_client, team_member;
--   ALTER TABLE user_token DROP CONSTRAINT user_token_purpose_check;
--   ALTER TABLE user_token ADD CONSTRAINT user_token_purpose_check CHECK (purpose IN ('RESET_PASSWORD', 'VERIFY_EMAIL'));
--   ALTER TABLE app_user DROP CONSTRAINT app_user_role_check;
--   ALTER TABLE app_user ADD CONSTRAINT app_user_role_check CHECK (role IN ('ADMIN', 'TRAINER', 'CLIENT'));
--   DELETE FROM flyway_schema_history WHERE version = '12';
-- =====================================================================

ALTER TABLE app_user DROP CONSTRAINT app_user_role_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_role_check CHECK (role IN ('ADMIN', 'TRAINER', 'CLIENT', 'COLLABORATOR'));

ALTER TABLE user_token DROP CONSTRAINT user_token_purpose_check;
ALTER TABLE user_token ADD CONSTRAINT user_token_purpose_check CHECK (purpose IN ('RESET_PASSWORD', 'VERIFY_EMAIL', 'TEAM_INVITE'));

-- Una fila por colaborador. owner_id: el profesional (ADMIN o TRAINER) de cuyo espacio forma parte.
CREATE TABLE team_member (
    user_id                 BIGINT    PRIMARY KEY REFERENCES app_user (id),
    owner_id                BIGINT    NOT NULL REFERENCES app_user (id),
    invited_at              TIMESTAMP NOT NULL,
    activated_at            TIMESTAMP,
    removed_at              TIMESTAMP,
    can_assign_routines     BOOLEAN   NOT NULL DEFAULT FALSE,
    can_manage_appointments BOOLEAN   NOT NULL DEFAULT FALSE,
    can_message_clients     BOOLEAN   NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_team_member_owner ON team_member (owner_id);

-- Clientes asignados a cada colaborador: solo ve y sigue a estos.
CREATE TABLE collaborator_client (
    collaborator_id BIGINT NOT NULL REFERENCES team_member (user_id) ON DELETE CASCADE,
    client_id       BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    PRIMARY KEY (collaborator_id, client_id)
);
CREATE INDEX ix_collaborator_client_client ON collaborator_client (client_id);
