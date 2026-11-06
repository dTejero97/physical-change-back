-- =====================================================================
-- Physical Change · V29 · Cobros con Stripe por profesional (PostgreSQL 16) — T279
-- Decisión: docs/adr/ADR-018-stripe-por-profesional.md · Contrato: physical-change-back/docs/api-stripe-profesional.md v0.1.0
-- Alcance: cuenta Stripe Connect por profesional (Standard por defecto, Express si lo elige). Sin claves de profesionales
-- en la base de datos: solo el id de cuenta (acct_…). Comisión de la plataforma por plan, pagada por el cliente encima
-- del precio. Los pedidos y suscripciones anteriores (cuenta NULL, comisión 0) siguen en la cuenta de la plataforma.
-- No editar una vez desplegado.
--
-- Reversión manual (se pierden las cuentas vinculadas, los clientes de Stripe por profesional y las comisiones de
-- planes; antes de revertir, revisar en Stripe las suscripciones cobradas en cuentas de profesionales):
--   DELETE FROM stripe_oauth_state;
--   DROP INDEX ix_stripe_event_account;
--   ALTER TABLE stripe_event DROP COLUMN account_id;
--   ALTER TABLE purchase_order DROP COLUMN platform_fee_amount, DROP COLUMN stripe_account_id;
--   ALTER TABLE client_subscription DROP COLUMN platform_fee_percent, DROP COLUMN stripe_account_id;
--   ALTER TABLE subscription_plan DROP COLUMN platform_fee_percent;
--   DROP TABLE trainer_stripe_customer;
--   DROP TABLE stripe_oauth_state;
--   DROP TABLE trainer_stripe_account;
--   DELETE FROM flyway_schema_history WHERE version = '29';
-- =====================================================================

-- ---------- Cuenta de Stripe del profesional (Connect) ----------
-- account_type: STANDARD (por defecto, cuenta propia del profesional por OAuth) o EXPRESS (alta por enlace de Stripe).
-- requirements_due: requisitos pendientes que devuelve Stripe, como texto JSON (sin tipo JSON para mantener H2 en pruebas).
CREATE TABLE trainer_stripe_account (
    trainer_id              BIGINT         PRIMARY KEY REFERENCES app_user (id),
    stripe_account_id       VARCHAR(255)   NOT NULL UNIQUE,
    account_type            VARCHAR(10)    NOT NULL DEFAULT 'STANDARD' CHECK (account_type IN ('STANDARD', 'EXPRESS')),
    status                  VARCHAR(20)    NOT NULL CHECK (status IN ('PENDING_DETAILS', 'ACTIVE', 'RESTRICTED')),
    charges_enabled         BOOLEAN        NOT NULL DEFAULT FALSE,
    payouts_enabled         BOOLEAN        NOT NULL DEFAULT FALSE,
    details_submitted       BOOLEAN        NOT NULL DEFAULT FALSE,
    requirements_due        VARCHAR(2000)  NOT NULL DEFAULT '[]',
    linked_at               TIMESTAMP      NOT NULL,
    updated_at              TIMESTAMP      NOT NULL,
    unlinked_at             TIMESTAMP
);

-- ---------- Estado de OAuth / alta de un solo uso (vinculación) ----------
-- state: valor aleatorio (≥ 32 bytes en base64url). Caduca a los 10 min y se marca como usado al volver de Stripe.
CREATE TABLE stripe_oauth_state (
    state                 VARCHAR(64)   PRIMARY KEY,
    trainer_id            BIGINT        NOT NULL REFERENCES app_user (id),
    account_type          VARCHAR(10)   NOT NULL CHECK (account_type IN ('STANDARD', 'EXPRESS')),
    -- Solo Express: cuenta creada por la plataforma al empezar el alta (en Standard llega en el código de OAuth).
    stripe_account_id     VARCHAR(255),
    created_at            TIMESTAMP     NOT NULL,
    expires_at            TIMESTAMP     NOT NULL,
    used_at               TIMESTAMP
);
CREATE INDEX ix_stripe_oauth_state_trainer ON stripe_oauth_state (trainer_id);

-- ---------- Cliente de Stripe por par cliente–profesional ----------
-- Un cus_… pertenece a una sola cuenta: un cliente que paga a dos profesionales tiene dos clientes de Stripe.
CREATE TABLE trainer_stripe_customer (
    client_id             BIGINT        NOT NULL REFERENCES app_user (id),
    trainer_id            BIGINT        NOT NULL REFERENCES app_user (id),
    stripe_customer_id    VARCHAR(255)  NOT NULL UNIQUE,
    -- Cuenta del profesional en la que existe el cliente (cambia si el profesional vincula otra cuenta).
    stripe_account_id      VARCHAR(255)  NOT NULL,
    created_at            TIMESTAMP     NOT NULL,
    PRIMARY KEY (client_id, trainer_id)
);

-- ---------- Comisión de la plataforma por plan ----------
-- Porcentaje sobre el precio del plan; el cliente paga precio + comisión y la plataforma se queda la comisión.
-- 0 por defecto: los planes existentes no cambian de importe.
ALTER TABLE subscription_plan ADD COLUMN platform_fee_percent NUMERIC(5, 2) NOT NULL DEFAULT 0
    CHECK (platform_fee_percent >= 0 AND platform_fee_percent <= 100);

-- ---------- Cuenta de cobro y comisión en pedidos y suscripciones ----------
-- stripe_account_id NULL = cobrado en la cuenta de la plataforma (legado de V20). Con valor = cuenta del profesional.
-- La comisión se copia al contratar: cambiar el plan después no cambia lo ya contratado.
ALTER TABLE purchase_order ADD COLUMN stripe_account_id VARCHAR(255);
ALTER TABLE purchase_order ADD COLUMN platform_fee_amount NUMERIC(10, 2) NOT NULL DEFAULT 0 CHECK (platform_fee_amount >= 0);
ALTER TABLE client_subscription ADD COLUMN stripe_account_id VARCHAR(255);
ALTER TABLE client_subscription ADD COLUMN platform_fee_percent NUMERIC(5, 2) NOT NULL DEFAULT 0
    CHECK (platform_fee_percent >= 0 AND platform_fee_percent <= 100);

-- ---------- Eventos de cuentas conectadas ----------
-- account_id: cuenta de la que llega el evento (campo "account"). NULL = endpoint de la plataforma.
ALTER TABLE stripe_event ADD COLUMN account_id VARCHAR(255);
CREATE INDEX ix_stripe_event_account ON stripe_event (account_id);
