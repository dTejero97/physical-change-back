-- =====================================================================
-- Physical Change · V2 · Sección Clientes (PostgreSQL 14+)
-- Etiquetas, ficha del cliente, invitaciones, leads y comunidad.
-- Contrato: docs/api-clientes.md. No editar una vez desplegado: cambios en V3__...
--
-- Reversión manual (si hubiera que deshacer V2 antes de tener datos):
--   DROP TABLE community_like, community_post, community_member, community_group,
--              client_lead, client_invitation, client_tag_link, client_profile, client_tag;
--   DELETE FROM flyway_schema_history WHERE version = '2';
-- =====================================================================

-- ---------- ETIQUETAS ----------
-- automations: JSON con la configuración (ver TagAutomations en el contrato).
CREATE TABLE client_tag (
    id          BIGSERIAL PRIMARY KEY,
    trainer_id  BIGINT        NOT NULL REFERENCES app_user (id),
    name        VARCHAR(60)   NOT NULL,
    automations VARCHAR(4000) NOT NULL DEFAULT '{}',
    created_at  TIMESTAMP     NOT NULL
);
CREATE UNIQUE INDEX ux_client_tag_trainer_name ON client_tag (trainer_id, lower(name));

-- ---------- FICHA DEL CLIENTE ----------
-- Una fila por cliente. removed_at: borrado lógico desde "Archivados".
CREATE TABLE client_profile (
    client_id    BIGINT       PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    activated_at DATE         NOT NULL,
    plan_name    VARCHAR(120),
    plan_status  VARCHAR(20)  NOT NULL DEFAULT 'NO_PLAN'
        CHECK (plan_status IN ('ACTIVE', 'PENDING_START', 'SENT', 'NO_PLAN', 'ENDING_SOON', 'FINISHED', 'DISPUTE')),
    assignee     VARCHAR(10)  NOT NULL DEFAULT 'ME' CHECK (assignee IN ('ME', 'OTHERS', 'NONE')),
    app_access   BOOLEAN      NOT NULL DEFAULT TRUE,
    height_cm    INTEGER      CHECK (height_cm IS NULL OR height_cm BETWEEN 50 AND 260),
    weight_kg    NUMERIC(5, 1) CHECK (weight_kg IS NULL OR weight_kg BETWEEN 20 AND 500),
    gender       VARCHAR(10)  NOT NULL DEFAULT 'NONE' CHECK (gender IN ('NONE', 'MALE', 'FEMALE')),
    birth_date   DATE,
    services     VARCHAR(40)  NOT NULL DEFAULT 'FITNESS',
    removed_at   TIMESTAMP
);
-- Los clientes ya existentes reciben su ficha con la fecha de hoy como activación.
INSERT INTO client_profile (client_id, activated_at)
SELECT id, CURRENT_DATE FROM app_user WHERE role = 'CLIENT';

-- Etiquetas asignadas a cada cliente.
CREATE TABLE client_tag_link (
    client_id BIGINT NOT NULL REFERENCES client_profile (client_id) ON DELETE CASCADE,
    tag_id    BIGINT NOT NULL REFERENCES client_tag (id) ON DELETE CASCADE,
    PRIMARY KEY (client_id, tag_id)
);
CREATE INDEX ix_client_tag_link_tag ON client_tag_link (tag_id);

-- ---------- INVITACIONES ----------
-- email NULL = enlace genérico reutilizable. accepted_at: invitación por email ya usada.
CREATE TABLE client_invitation (
    id          BIGSERIAL PRIMARY KEY,
    trainer_id  BIGINT       NOT NULL REFERENCES app_user (id),
    email       VARCHAR(160),
    name        VARCHAR(120),
    tag_id      BIGINT       REFERENCES client_tag (id) ON DELETE SET NULL,
    plan_name   VARCHAR(120),
    token       VARCHAR(64)  NOT NULL UNIQUE,
    invited_at  TIMESTAMP    NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    accepted_at TIMESTAMP,
    client_id   BIGINT       REFERENCES app_user (id)
);
CREATE INDEX ix_invitation_trainer ON client_invitation (trainer_id, invited_at);

-- ---------- LEADS ----------
CREATE TABLE client_lead (
    id            BIGSERIAL PRIMARY KEY,
    trainer_id    BIGINT        NOT NULL REFERENCES app_user (id),
    name          VARCHAR(120)  NOT NULL,
    email         VARCHAR(160),
    phone         VARCHAR(40),
    message       VARCHAR(2000),
    source        VARCHAR(20)   NOT NULL CHECK (source IN ('WEB_FORM', 'REFERRAL', 'OTHER')),
    registered_at TIMESTAMP     NOT NULL
);
CREATE INDEX ix_lead_trainer ON client_lead (trainer_id, registered_at);

-- ---------- COMUNIDAD ----------
CREATE TABLE community_group (
    id            BIGSERIAL PRIMARY KEY,
    trainer_id    BIGINT       NOT NULL REFERENCES app_user (id),
    name          VARCHAR(80)  NOT NULL,
    description   VARCHAR(500),
    emoji         VARCHAR(16),
    who_can_post  VARCHAR(10)  NOT NULL DEFAULT 'EVERYONE' CHECK (who_can_post IN ('TRAINER', 'TEAM', 'EVERYONE')),
    share_results BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL
);
CREATE INDEX ix_group_trainer ON community_group (trainer_id);

CREATE TABLE community_member (
    group_id  BIGINT NOT NULL REFERENCES community_group (id) ON DELETE CASCADE,
    client_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, client_id)
);
CREATE INDEX ix_member_client ON community_member (client_id);

CREATE TABLE community_post (
    id         BIGSERIAL PRIMARY KEY,
    group_id   BIGINT        NOT NULL REFERENCES community_group (id) ON DELETE CASCADE,
    author_id  BIGINT        NOT NULL REFERENCES app_user (id),
    body       VARCHAR(2000) NOT NULL,
    workout_id BIGINT        REFERENCES workout (id) ON DELETE SET NULL,
    created_at TIMESTAMP     NOT NULL
);
CREATE INDEX ix_post_group ON community_post (group_id, created_at);

CREATE TABLE community_like (
    post_id BIGINT NOT NULL REFERENCES community_post (id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    PRIMARY KEY (post_id, user_id)
);
