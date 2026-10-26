-- =====================================================================
-- Physical Change · V6 · Sección Negocio (PostgreSQL 16)
-- Comprobantes (facturación manual), productos, planes de suscripción, cupones, bonos,
-- suscripciones de clientes y pagos de los servicios de la plataforma.
-- Contrato: docs/api-negocio.md. Decisiones: docs/adr/ADR-004-cobros-facturacion.md (raíz del proyecto).
-- La tabla payment (V1) NO se toca: se copia a receipt (origin = 'LEGACY') y queda congelada como respaldo.
-- No editar una vez desplegado: cambios en V8__...
--
-- Reversión manual:
--   1) (Opcional) Devolver a payment los cobros creados después de V6 (una línea, sin descuentos):
--      INSERT INTO payment (trainer_id, client_id, amount, concept, due_date, paid_date, status)
--        SELECT r.trainer_id, r.client_id, r.total, LEFT(COALESCE(l.name, 'Comprobante PC-' || r.id), 160),
--               r.due_date, r.paid_date, r.status
--        FROM receipt r LEFT JOIN LATERAL (SELECT name FROM receipt_line WHERE receipt_id = r.id ORDER BY position LIMIT 1) l ON TRUE
--        WHERE r.origin <> 'LEGACY' AND r.status IN ('PENDING', 'PAID');
--      UPDATE payment p SET status = r.status, paid_date = r.paid_date
--        FROM receipt r WHERE r.legacy_payment_id = p.id AND r.status IN ('PENDING', 'PAID');
--   2) ALTER TABLE client_invitation DROP COLUMN plan_id;
--      DROP TABLE bonus_redemption, client_bonus, receipt_line, receipt, client_subscription,
--                 bonus_definition, product_auto_tag, plan_auto_tag, coupon, product, subscription_plan,
--                 platform_payment, platform_service;
--      DELETE FROM flyway_schema_history WHERE version = '6';
-- =====================================================================

-- ---------- CATÁLOGO ----------
CREATE TABLE product (
    id          BIGSERIAL PRIMARY KEY,
    trainer_id  BIGINT         NOT NULL REFERENCES app_user (id),
    name        VARCHAR(120)   NOT NULL,
    description VARCHAR(1000)  NOT NULL,
    price       NUMERIC(10, 2) NOT NULL CHECK (price > 0),
    tax_rate    SMALLINT       NOT NULL CHECK (tax_rate IN (0, 4, 10, 21)),
    visibility  VARCHAR(10)    NOT NULL DEFAULT 'HIDDEN' CHECK (visibility IN ('HIDDEN', 'ALL')),
    cover       VARCHAR(300)   NOT NULL,
    archived    BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP      NOT NULL,
    updated_at  TIMESTAMP      NOT NULL
);
CREATE INDEX ix_product_trainer ON product (trainer_id, archived);

CREATE TABLE subscription_plan (
    id          BIGSERIAL PRIMARY KEY,
    trainer_id  BIGINT         NOT NULL REFERENCES app_user (id),
    name        VARCHAR(120)   NOT NULL,
    description VARCHAR(1000)  NOT NULL,
    price       NUMERIC(10, 2) NOT NULL CHECK (price > 0),
    tax_rate    SMALLINT       NOT NULL CHECK (tax_rate IN (0, 4, 10, 21)),
    frequency   VARCHAR(12)    NOT NULL CHECK (frequency IN ('WEEKLY', 'MONTHLY', 'QUARTERLY', 'SEMIANNUAL', 'ANNUAL')),
    visibility  VARCHAR(10)    NOT NULL DEFAULT 'HIDDEN' CHECK (visibility IN ('HIDDEN', 'ALL')),
    cover       VARCHAR(300)   NOT NULL,
    archived    BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP      NOT NULL,
    updated_at  TIMESTAMP      NOT NULL
);
CREATE INDEX ix_plan_trainer ON subscription_plan (trainer_id, archived);

-- Etiquetas que se añaden al cliente al pagar el producto o empezar el plan (ADR-004: única automatización).
CREATE TABLE product_auto_tag (
    product_id BIGINT NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    tag_id     BIGINT NOT NULL REFERENCES client_tag (id) ON DELETE CASCADE,
    PRIMARY KEY (product_id, tag_id)
);
CREATE TABLE plan_auto_tag (
    plan_id BIGINT NOT NULL REFERENCES subscription_plan (id) ON DELETE CASCADE,
    tag_id  BIGINT NOT NULL REFERENCES client_tag (id) ON DELETE CASCADE,
    PRIMARY KEY (plan_id, tag_id)
);

-- Bonos incluidos (hasta 5 por producto o plan; el límite lo comprueba el servicio).
CREATE TABLE bonus_definition (
    id              BIGSERIAL PRIMARY KEY,
    product_id      BIGINT   REFERENCES product (id) ON DELETE CASCADE,
    plan_id         BIGINT   REFERENCES subscription_plan (id) ON DELETE CASCADE,
    session_type_id BIGINT   NOT NULL REFERENCES session_type (id),
    sessions        SMALLINT NOT NULL CHECK (sessions BETWEEN 1 AND 100),
    valid_days      SMALLINT NOT NULL CHECK (valid_days BETWEEN 1 AND 730),
    CHECK ((product_id IS NULL) <> (plan_id IS NULL))
);
CREATE INDEX ix_bonusdef_product ON bonus_definition (product_id);
CREATE INDEX ix_bonusdef_plan ON bonus_definition (plan_id);

CREATE TABLE coupon (
    id          BIGSERIAL PRIMARY KEY,
    trainer_id  BIGINT         NOT NULL REFERENCES app_user (id),
    code        VARCHAR(30)    NOT NULL CHECK (code ~ '^[A-Z0-9_-]{3,30}$'),
    description VARCHAR(300),
    type        VARCHAR(8)     NOT NULL CHECK (type IN ('PERCENT', 'AMOUNT')),
    discount_value NUMERIC(10, 2) NOT NULL CHECK (discount_value > 0 AND (type <> 'PERCENT' OR discount_value <= 100)),
    applies_to  VARCHAR(8)     NOT NULL CHECK (applies_to IN ('ALL', 'PRODUCTS', 'PLANS')),
    max_uses    INTEGER        CHECK (max_uses > 0),
    uses        INTEGER        NOT NULL DEFAULT 0 CHECK (uses >= 0 AND (max_uses IS NULL OR uses <= max_uses)),
    expires_at  DATE,
    active      BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP      NOT NULL,
    UNIQUE (trainer_id, code)
);

-- ---------- SUSCRIPCIONES ----------
CREATE TABLE client_subscription (
    id                BIGSERIAL PRIMARY KEY,
    trainer_id        BIGINT         NOT NULL REFERENCES app_user (id),
    client_id         BIGINT         NOT NULL REFERENCES app_user (id),
    plan_id           BIGINT         NOT NULL REFERENCES subscription_plan (id),
    price             NUMERIC(10, 2) NOT NULL CHECK (price > 0),
    coupon_id         BIGINT         REFERENCES coupon (id) ON DELETE SET NULL,
    start_date        DATE           NOT NULL,
    next_period_start DATE           NOT NULL,
    status            VARCHAR(10)    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCELLED')),
    created_at        TIMESTAMP      NOT NULL,
    cancelled_at      TIMESTAMP
);
CREATE INDEX ix_sub_trainer_due ON client_subscription (trainer_id, status, next_period_start);
-- Un cliente no puede tener el mismo plan activo dos veces.
CREATE UNIQUE INDEX ux_sub_active ON client_subscription (client_id, plan_id) WHERE status = 'ACTIVE';

ALTER TABLE client_invitation ADD COLUMN plan_id BIGINT REFERENCES subscription_plan (id) ON DELETE SET NULL;

-- ---------- COMPROBANTES (inmutables: solo cambian status, paid_date, void_*) ----------
CREATE TABLE receipt (
    id                BIGSERIAL PRIMARY KEY,
    trainer_id        BIGINT         NOT NULL REFERENCES app_user (id),
    client_id         BIGINT         NOT NULL REFERENCES app_user (id),
    issue_date        DATE           NOT NULL,
    due_date          DATE           NOT NULL,
    notes             VARCHAR(500),
    method            VARCHAR(10)    NOT NULL CHECK (method IN ('CASH', 'TRANSFER', 'BIZUM', 'PAYPAL', 'CARD', 'OTHER')),
    status            VARCHAR(10)    NOT NULL CHECK (status IN ('PENDING', 'PAID', 'VOID')),
    paid_date         DATE,
    origin            VARCHAR(12)    NOT NULL CHECK (origin IN ('MANUAL', 'SUBSCRIPTION', 'LEGACY')),
    subscription_id   BIGINT         REFERENCES client_subscription (id),
    period_start      DATE,
    period_end        DATE,
    coupon_id         BIGINT         REFERENCES coupon (id) ON DELETE SET NULL,
    discount_name     VARCHAR(60),
    discount_pct      NUMERIC(5, 2)  CHECK (discount_pct BETWEEN 0 AND 100),
    subtotal          NUMERIC(12, 2) NOT NULL CHECK (subtotal >= 0),
    discount_amount   NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    total             NUMERIC(12, 2) NOT NULL CHECK (total >= 0),
    void_reason       VARCHAR(200),
    voided_at         TIMESTAMP,
    was_paid          BOOLEAN        NOT NULL DEFAULT FALSE,   -- anulado después de cobrado (KPI de anulaciones)
    legacy_payment_id BIGINT         UNIQUE REFERENCES payment (id),
    created_at        TIMESTAMP      NOT NULL,
    CHECK (due_date >= issue_date OR origin = 'LEGACY'),
    CHECK ((status = 'PAID') = (paid_date IS NOT NULL) OR (status = 'VOID' AND was_paid)),
    CHECK ((status = 'VOID') = (voided_at IS NOT NULL)),
    CHECK ((origin = 'SUBSCRIPTION') = (subscription_id IS NOT NULL AND period_start IS NOT NULL AND period_end IS NOT NULL))
);
CREATE INDEX ix_receipt_trainer_issue ON receipt (trainer_id, issue_date);
CREATE INDEX ix_receipt_trainer_paid ON receipt (trainer_id, paid_date) WHERE status = 'PAID';
CREATE INDEX ix_receipt_client ON receipt (client_id, issue_date);
-- Puesta al día idempotente: un comprobante por periodo de suscripción.
CREATE UNIQUE INDEX ux_receipt_period ON receipt (subscription_id, period_start) WHERE subscription_id IS NOT NULL;

CREATE TABLE receipt_line (
    id         BIGSERIAL PRIMARY KEY,
    receipt_id BIGINT         NOT NULL REFERENCES receipt (id) ON DELETE CASCADE,
    position   SMALLINT       NOT NULL CHECK (position BETWEEN 1 AND 20),
    kind       VARCHAR(8)     NOT NULL CHECK (kind IN ('PRODUCT', 'PLAN', 'FREE')),
    product_id BIGINT         REFERENCES product (id),
    plan_id    BIGINT         REFERENCES subscription_plan (id),
    name       VARCHAR(120)   NOT NULL,   -- copia: el comprobante no cambia si cambia el catálogo
    unit_price NUMERIC(10, 2) NOT NULL CHECK (unit_price > 0),
    quantity   SMALLINT       NOT NULL CHECK (quantity BETWEEN 1 AND 999),
    tax_rate   SMALLINT       NOT NULL CHECK (tax_rate IN (0, 4, 10, 21)),
    line_total NUMERIC(12, 2) NOT NULL CHECK (line_total > 0),
    UNIQUE (receipt_id, position),
    CHECK ((kind = 'PRODUCT') = (product_id IS NOT NULL)),
    CHECK ((kind = 'PLAN') = (plan_id IS NOT NULL))
);
CREATE INDEX ix_rline_product ON receipt_line (product_id) WHERE product_id IS NOT NULL;
CREATE INDEX ix_rline_plan ON receipt_line (plan_id) WHERE plan_id IS NOT NULL;

-- ---------- BONOS ----------
CREATE TABLE client_bonus (
    id              BIGSERIAL PRIMARY KEY,
    trainer_id      BIGINT    NOT NULL REFERENCES app_user (id),
    client_id       BIGINT    NOT NULL REFERENCES app_user (id),
    session_type_id BIGINT    NOT NULL REFERENCES session_type (id),
    receipt_id      BIGINT    NOT NULL REFERENCES receipt (id),
    product_name    VARCHAR(120) NOT NULL,
    total           SMALLINT  NOT NULL CHECK (total > 0),
    used            SMALLINT  NOT NULL DEFAULT 0 CHECK (used >= 0 AND used <= total),
    expires_at      DATE      NOT NULL,
    cancelled_at    TIMESTAMP,
    created_at      TIMESTAMP NOT NULL
);
CREATE INDEX ix_bonus_client_type ON client_bonus (client_id, session_type_id, expires_at);

CREATE TABLE bonus_redemption (
    id          BIGSERIAL PRIMARY KEY,
    bonus_id    BIGINT    NOT NULL REFERENCES client_bonus (id),
    booking_id  BIGINT    NOT NULL UNIQUE REFERENCES agenda_booking (id) ON DELETE CASCADE,
    redeemed_at TIMESTAMP NOT NULL
);
CREATE INDEX ix_redemption_bonus ON bonus_redemption (bonus_id);
CREATE INDEX ix_redemption_at ON bonus_redemption (redeemed_at);

-- ---------- PLATAFORMA (solo ADMIN) ----------
CREATE TABLE platform_service (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(60)    NOT NULL UNIQUE,
    purpose       VARCHAR(200),
    plan          VARCHAR(60)    NOT NULL,
    monthly_cost  NUMERIC(10, 2) NOT NULL DEFAULT 0 CHECK (monthly_cost >= 0),
    renewal_date  DATE,
    dashboard_url VARCHAR(300)   CHECK (dashboard_url IS NULL OR dashboard_url LIKE 'https://%'),
    free_limits   VARCHAR(1000),
    active        BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP      NOT NULL
);

CREATE TABLE platform_payment (
    id         BIGSERIAL PRIMARY KEY,
    service_id BIGINT         NOT NULL REFERENCES platform_service (id),
    payment_date DATE         NOT NULL,
    concept    VARCHAR(120)   NOT NULL,
    amount     NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
    status     VARCHAR(8)     NOT NULL CHECK (status IN ('PAID', 'PENDING')),
    file_id    BIGINT         REFERENCES stored_file (id) ON DELETE SET NULL,
    notes      VARCHAR(500),
    created_at TIMESTAMP      NOT NULL
);
CREATE INDEX ix_platform_payment_date ON platform_payment (payment_date);

INSERT INTO platform_service (name, purpose, plan, monthly_cost, dashboard_url, free_limits, created_at) VALUES
    ('Render', 'Backend (API) y base de datos PostgreSQL', 'Gratuito', 0, 'https://dashboard.render.com',
     'El servicio se duerme sin tráfico; la base gratuita caduca a los 30 días (ver MANUAL-DESPLIEGUE.md, Parte 6).', NOW()),
    ('Vercel', 'Web (frontend Angular)', 'Gratuito', 0, 'https://vercel.com/dashboard',
     'Plan Hobby: uso personal y no comercial.', NOW()),
    ('Cloudflare', 'Archivos grandes (R2) y Physical AI (Workers AI)', 'Gratuito', 0, 'https://dash.cloudflare.com',
     'R2: 10 GB-mes, 1 M de operaciones de clase A y 10 M de clase B al mes, salida gratis. Workers AI: 10.000 neuronas al día.', NOW());

-- ---------- COPIA DE payment (V1) ----------
INSERT INTO receipt (trainer_id, client_id, issue_date, due_date, notes, method, status, paid_date, origin,
                     subtotal, discount_amount, total, legacy_payment_id, created_at)
SELECT p.trainer_id, p.client_id, p.due_date, p.due_date, NULL, 'OTHER', p.status, p.paid_date, 'LEGACY',
       p.amount, 0, p.amount, p.id, NOW()
FROM payment p;

INSERT INTO receipt_line (receipt_id, position, kind, name, unit_price, quantity, tax_rate, line_total)
SELECT r.id, 1, 'FREE', LEFT(p.concept, 120), p.amount, 1, 0, p.amount
FROM receipt r JOIN payment p ON p.id = r.legacy_payment_id;
