-- =====================================================================
-- Physical Change · V20 · Cobros con Stripe (PostgreSQL 16) — Fase 6.3, T127
-- Decisión: docs/adr/ADR-009-stripe.md · Contrato: physical-change-back/docs/api-pagos-stripe.md v0.2.0
-- Alcance (decisión del usuario 2026-11-03): el cliente con sesión paga sus comprobantes pendientes y contrata planes
-- con renovación automática. El registro durante la contratación (app_user.access_status) queda para T143.
-- No editar una vez desplegado.
--
-- Reversión manual (se pierden los pedidos y eventos de Stripe; los comprobantes pagados con Stripe pasan a CARD):
--   UPDATE receipt SET method = 'CARD' WHERE method = 'STRIPE';
--   ALTER TABLE receipt DROP CONSTRAINT receipt_method_check;
--   ALTER TABLE receipt ADD CONSTRAINT receipt_method_check CHECK (method IN ('CASH', 'TRANSFER', 'BIZUM', 'PAYPAL', 'CARD', 'OTHER'));
--   DROP TABLE purchase_order;
--   DROP TABLE stripe_event;
--   UPDATE client_subscription SET status = 'ACTIVE' WHERE status = 'PAST_DUE';
--   DROP INDEX ux_sub_active;
--   CREATE UNIQUE INDEX ux_sub_active ON client_subscription (client_id, plan_id) WHERE status = 'ACTIVE';
--   ALTER TABLE client_subscription DROP CONSTRAINT client_subscription_status_check;
--   ALTER TABLE client_subscription ADD CONSTRAINT client_subscription_status_check CHECK (status IN ('ACTIVE', 'CANCELLED'));
--   ALTER TABLE client_subscription DROP COLUMN stripe_subscription_id, DROP COLUMN cancel_at_period_end;
--   ALTER TABLE app_user DROP COLUMN stripe_customer_id;
--   DELETE FROM flyway_schema_history WHERE version = '20';
-- =====================================================================

-- ---------- Cliente de Stripe (uno por usuario; se reutiliza en cada pago y en el portal) ----------
ALTER TABLE app_user ADD COLUMN stripe_customer_id VARCHAR(255) UNIQUE;

-- ---------- Comprobantes: método STRIPE (lo pone solo el webhook) ----------
ALTER TABLE receipt DROP CONSTRAINT receipt_method_check;
ALTER TABLE receipt ADD CONSTRAINT receipt_method_check
    CHECK (method IN ('CASH', 'TRANSFER', 'BIZUM', 'PAYPAL', 'CARD', 'OTHER', 'STRIPE'));

-- ---------- Suscripciones cobradas por Stripe ----------
-- Con stripe_subscription_id, la puesta al día NO emite comprobantes pendientes: cada periodo llega pagado por webhook.
ALTER TABLE client_subscription ADD COLUMN stripe_subscription_id VARCHAR(255) UNIQUE;
ALTER TABLE client_subscription ADD COLUMN cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE client_subscription DROP CONSTRAINT client_subscription_status_check;
ALTER TABLE client_subscription ADD CONSTRAINT client_subscription_status_check
    CHECK (status IN ('ACTIVE', 'PAST_DUE', 'CANCELLED'));
-- Un impago (PAST_DUE) sigue ocupando el plan: no se puede contratar otra vez hasta cancelarlo.
DROP INDEX ux_sub_active;
CREATE UNIQUE INDEX ux_sub_active ON client_subscription (client_id, plan_id) WHERE status IN ('ACTIVE', 'PAST_DUE');

-- ---------- Pedidos: un intento de pago en Stripe Checkout ----------
-- RECEIPT = pagar un comprobante pendiente (pago único); PLAN = contratar un plan (suscripción).
-- El importe lo fija el servidor al crear el pedido; el webhook solo lo confirma.
CREATE TABLE purchase_order (
    id                    BIGSERIAL PRIMARY KEY,
    client_id             BIGINT         NOT NULL REFERENCES app_user (id),
    trainer_id            BIGINT         NOT NULL REFERENCES app_user (id),
    kind                  VARCHAR(8)     NOT NULL CHECK (kind IN ('RECEIPT', 'PLAN')),
    receipt_id            BIGINT         REFERENCES receipt (id),
    plan_id               BIGINT         REFERENCES subscription_plan (id),
    subscription_id       BIGINT         REFERENCES client_subscription (id),
    amount                NUMERIC(10, 2) NOT NULL CHECK (amount >= 0.50),   -- mínimo de Stripe en euros
    status                VARCHAR(10)    NOT NULL CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'EXPIRED', 'REFUNDED')),
    stripe_session_id     VARCHAR(255)   UNIQUE,
    stripe_payment_intent VARCHAR(255),
    created_at            TIMESTAMP      NOT NULL,
    expires_at            TIMESTAMP      NOT NULL,
    paid_at               TIMESTAMP,
    CHECK ((kind = 'RECEIPT') = (receipt_id IS NOT NULL)),
    CHECK ((kind = 'PLAN') = (plan_id IS NOT NULL)),
    CHECK ((status = 'PAID' OR status = 'REFUNDED') = (paid_at IS NOT NULL))
);
CREATE INDEX ix_order_client ON purchase_order (client_id, created_at);
CREATE INDEX ix_order_receipt_open ON purchase_order (receipt_id) WHERE status = 'PENDING';
CREATE INDEX ix_order_plan_open ON purchase_order (client_id, plan_id) WHERE status = 'PENDING';
CREATE INDEX ix_order_payment_intent ON purchase_order (stripe_payment_intent);
-- Sin índice único de "un pago por comprobante": si se pagan dos sesiones a la vez, el segundo webhook no puede fallar en
-- bucle; el servicio deja los dos pedidos PAID, el comprobante una sola vez y avisa en el log para reembolsar el duplicado.

-- ---------- Eventos de webhook ya procesados (Stripe reintenta: idempotencia por id) ----------
CREATE TABLE stripe_event (
    event_id    VARCHAR(255) PRIMARY KEY,
    type        VARCHAR(80)  NOT NULL,
    received_at TIMESTAMP    NOT NULL
);
