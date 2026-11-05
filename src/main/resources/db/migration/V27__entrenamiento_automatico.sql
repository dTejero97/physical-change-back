-- =====================================================================
-- Physical Change · V27 · Entrenamiento automático al contratar (PostgreSQL 16) — T249
-- Contrato: physical-change-back/docs/api-entrenamiento-automatico.md (T248)
-- 1) Un plan de suscripción puede llevar un programa de la librería, que se asigna solo al empezar la suscripción.
-- 2) program_assignment registra cada asignación de un programa a un cliente (automática o manual): trazabilidad
--    e idempotencia (una sola asignación automática por suscripción).
-- 3) Cada workout asignado recuerda de qué asignación de programa viene.
-- Borrar un programa no borra nada de lo asignado: las referencias quedan a NULL (program_name guarda el nombre).
-- No editar una vez desplegado.
--
-- Reversión manual (se pierde el registro de asignaciones de programas; los workouts asignados se conservan):
--   ALTER TABLE workout_assignment DROP COLUMN program_assignment_id;
--   DROP TABLE program_assignment;
--   ALTER TABLE subscription_plan DROP COLUMN program_id;
--   DELETE FROM flyway_schema_history WHERE version = '27';
-- =====================================================================

ALTER TABLE subscription_plan
    ADD COLUMN program_id BIGINT REFERENCES library_program (id) ON DELETE SET NULL;
CREATE INDEX ix_subscription_plan_program ON subscription_plan (program_id);

CREATE TABLE program_assignment (
    id              BIGSERIAL    PRIMARY KEY,
    client_id       BIGINT       NOT NULL REFERENCES app_user (id),
    program_id      BIGINT       REFERENCES library_program (id) ON DELETE SET NULL,
    program_name    VARCHAR(120) NOT NULL,          -- copia: se conserva si se borra el programa
    start_date      DATE         NOT NULL,
    end_date        DATE         NOT NULL,
    subscription_id BIGINT       REFERENCES client_subscription (id) ON DELETE SET NULL,
    origin          VARCHAR(10)  NOT NULL,
    created_by      BIGINT       REFERENCES app_user (id) ON DELETE SET NULL,   -- null = automática
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_program_assignment_origin CHECK (origin IN ('AUTO', 'MANUAL')),
    CONSTRAINT ck_program_assignment_dates CHECK (end_date >= start_date)
);
-- Una sola asignación por suscripción (las manuales sin suscripción no cuentan).
CREATE UNIQUE INDEX ux_program_assignment_subscription ON program_assignment (subscription_id) WHERE subscription_id IS NOT NULL;
CREATE INDEX ix_program_assignment_client ON program_assignment (client_id, start_date DESC);
CREATE INDEX ix_program_assignment_program ON program_assignment (program_id);

ALTER TABLE workout_assignment
    ADD COLUMN program_assignment_id BIGINT REFERENCES program_assignment (id) ON DELETE SET NULL;
CREATE INDEX ix_workout_assignment_program_assignment ON workout_assignment (program_assignment_id);
-- "Pagados sin entrenamiento" (¿alguna asignación de hoy en adelante?) usa el índice existente ix_assignment_client_date (V1).
