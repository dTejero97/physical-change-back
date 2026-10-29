-- =====================================================================
-- Physical Change · V13 · Solicitudes de cambio, notificaciones, objetivos nutricionales y logros por cliente
-- (PostgreSQL 16) — Fase 7, T153. Decisión: docs/adr/ADR-012-solicitudes-notificaciones-logros.md
-- Contrato: physical-change-back/docs/api-solicitudes-logros.md
-- No editar una vez desplegado.
--
-- Reversión manual:
--   DELETE FROM achievement WHERE category_id IN (SELECT id FROM achievement_category WHERE system_key = 'PROGRESS');
--   DELETE FROM achievement_category WHERE system_key = 'PROGRESS';
--   DROP TABLE achievement_unlock, client_stats, change_request_event, change_request, notification;
--   ALTER TABLE achievement DROP COLUMN metric, DROP COLUMN threshold, DROP COLUMN points;
--   ALTER TABLE nutrition_plan DROP COLUMN target_protein_g, DROP COLUMN target_carbs_g, DROP COLUMN target_fat_g,
--                              DROP COLUMN version, DROP COLUMN updated_at;
--   ALTER TABLE workout DROP COLUMN session_copy_of;
--   ALTER TABLE app_user DROP COLUMN nutritionist_id, DROP COLUMN training_enabled;
--   DELETE FROM flyway_schema_history WHERE version = '13';
-- =====================================================================

-- ---------- Plan nutricional: objetivos de macros y versión vigente ----------
ALTER TABLE nutrition_plan ADD COLUMN target_protein_g NUMERIC(6, 1) CHECK (target_protein_g IS NULL OR target_protein_g BETWEEN 0 AND 1000);
ALTER TABLE nutrition_plan ADD COLUMN target_carbs_g   NUMERIC(6, 1) CHECK (target_carbs_g IS NULL OR target_carbs_g BETWEEN 0 AND 1500);
ALTER TABLE nutrition_plan ADD COLUMN target_fat_g     NUMERIC(6, 1) CHECK (target_fat_g IS NULL OR target_fat_g BETWEEN 0 AND 500);
-- Sube cada vez que el profesional cambia el plan; las solicitudes guardan la versión sobre la que se hicieron.
ALTER TABLE nutrition_plan ADD COLUMN version          INTEGER       NOT NULL DEFAULT 1;
ALTER TABLE nutrition_plan ADD COLUMN updated_at       TIMESTAMP;

-- ---------- Rutina de una sola sesión ----------
-- Un cambio aceptado vale solo para la sesión de la solicitud (dudas H-02): se aplica a una copia de la rutina
-- exclusiva de esa sesión. Guarda la rutina original; las copias no aparecen en la librería del profesional.
-- Sin clave ajena: si se borra la original, la copia sigue oculta.
ALTER TABLE workout ADD COLUMN session_copy_of BIGINT;

-- ---------- Responsables de entrenamiento y de nutrición (ADR-013) ----------
-- Profesional: ofrece entrenamiento (nuevo) y/o nutrición (nutrition_enabled, ya existía).
ALTER TABLE app_user ADD COLUMN training_enabled INTEGER NOT NULL DEFAULT 1;
-- Cliente: responsable de nutrición si no es su entrenador. NULL = el entrenador, si ofrece nutrición.
ALTER TABLE app_user ADD COLUMN nutritionist_id  BIGINT REFERENCES app_user (id) ON DELETE SET NULL;
CREATE INDEX ix_app_user_nutritionist ON app_user (nutritionist_id);

-- ---------- Notificaciones en la app ----------
CREATE TABLE notification (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    type       VARCHAR(30)  NOT NULL CHECK (type IN ('CHANGE_REQUEST_CREATED', 'CHANGE_REQUEST_ACCEPTED',
                                                     'CHANGE_REQUEST_REJECTED', 'PLAN_UPDATED', 'ACHIEVEMENT_UNLOCKED')),
    title      VARCHAR(120) NOT NULL,
    body       VARCHAR(500),
    ref_type   VARCHAR(30),
    ref_id     BIGINT,
    created_at TIMESTAMP    NOT NULL,
    read_at    TIMESTAMP
);
CREATE INDEX ix_notification_user ON notification (user_id, created_at DESC);

-- ---------- Solicitudes de cambio del cliente (nunca modifican el plan por sí solas) ----------
CREATE TABLE change_request (
    id                 BIGSERIAL     PRIMARY KEY,
    kind               VARCHAR(10)   NOT NULL CHECK (kind IN ('TRAINING', 'NUTRITION')),
    action             VARCHAR(20)   NOT NULL CHECK (action IN ('ADD_EXERCISE', 'REPLACE_EXERCISE', 'MODIFY_EXERCISE',
                                                                'REMOVE_EXERCISE', 'CHANGE_TARGETS', 'REPLACE_FOOD')),
    status             VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    client_id          BIGINT        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    professional_id    BIGINT        NOT NULL REFERENCES app_user (id),
    -- Entrenamiento: asignación del cliente, rutina y ejercicio afectado (posición y ejercicio como instantánea).
    assignment_id      BIGINT        REFERENCES workout_assignment (id) ON DELETE SET NULL,
    workout_id         BIGINT        REFERENCES workout (id) ON DELETE SET NULL,
    workout_item_id    BIGINT,
    target_position    INTEGER,
    target_exercise_id BIGINT        REFERENCES exercise (id) ON DELETE SET NULL,
    exercise_id        BIGINT        REFERENCES exercise (id) ON DELETE SET NULL,
    sets               INTEGER       CHECK (sets IS NULL OR sets BETWEEN 1 AND 20),
    reps               INTEGER       CHECK (reps IS NULL OR reps BETWEEN 1 AND 200),
    weight_kg          NUMERIC(6, 2) CHECK (weight_kg IS NULL OR weight_kg BETWEEN 0 AND 1000),
    rest_seconds       INTEGER       CHECK (rest_seconds IS NULL OR rest_seconds BETWEEN 0 AND 900),
    -- Nutrición: plan y versión sobre la que se pidió, valores propuestos.
    plan_id            BIGINT        REFERENCES nutrition_plan (id) ON DELETE SET NULL,
    plan_version       INTEGER,
    meal_id            BIGINT,
    food_id            BIGINT        REFERENCES food (id) ON DELETE SET NULL,
    grams              NUMERIC(7, 1),
    kcal               INTEGER       CHECK (kcal IS NULL OR kcal BETWEEN 500 AND 10000),
    protein_g          NUMERIC(6, 1),
    carbs_g            NUMERIC(6, 1),
    fat_g              NUMERIC(6, 1),
    reason             VARCHAR(500),
    decision_note      VARCHAR(500),
    -- Clave del cambio mientras está pendiente (NULL al decidir): evita solicitudes duplicadas.
    pending_key        VARCHAR(80),
    created_at         TIMESTAMP     NOT NULL,
    decided_at         TIMESTAMP,
    decided_by         BIGINT        REFERENCES app_user (id),
    applied_workout_id BIGINT,
    copied_workout     BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_change_request_pending UNIQUE (client_id, pending_key)
);
CREATE INDEX ix_change_request_professional ON change_request (professional_id, status);
CREATE INDEX ix_change_request_client ON change_request (client_id, created_at DESC);

-- Historial de cada solicitud: CREATED, ACCEPTED, REJECTED y REASSIGNED (cambio de nutricionista, ADR-013).
CREATE TABLE change_request_event (
    id         BIGSERIAL   PRIMARY KEY,
    request_id  BIGINT      NOT NULL REFERENCES change_request (id) ON DELETE CASCADE,
    occurred_at TIMESTAMP   NOT NULL,
    actor_id   BIGINT      NOT NULL REFERENCES app_user (id),
    action     VARCHAR(10) NOT NULL CHECK (action IN ('CREATED', 'ACCEPTED', 'REJECTED', 'REASSIGNED')),
    note       VARCHAR(500)
);
CREATE INDEX ix_change_request_event_request ON change_request_event (request_id, occurred_at);

-- ---------- Logros por cliente ----------
-- Estadísticas recalculadas desde el histórico en cada evento (idempotentes). La fila también sirve
-- de cerrojo para serializar las evaluaciones del mismo cliente.
CREATE TABLE client_stats (
    client_id          BIGINT         PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    workouts_completed INTEGER        NOT NULL DEFAULT 0,
    active_days        INTEGER        NOT NULL DEFAULT 0,
    current_streak     INTEGER        NOT NULL DEFAULT 0,
    best_streak        INTEGER        NOT NULL DEFAULT 0,
    total_sets         INTEGER        NOT NULL DEFAULT 0,
    total_reps         BIGINT         NOT NULL DEFAULT 0,
    total_volume_kg    NUMERIC(14, 2) NOT NULL DEFAULT 0,
    last_activity      DATE,
    updated_at         TIMESTAMP      NOT NULL
);

-- Un desbloqueo por cliente y logro: nunca se repite ni se revierte.
CREATE TABLE achievement_unlock (
    id             BIGSERIAL PRIMARY KEY,
    client_id      BIGINT    NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    achievement_id BIGINT    NOT NULL REFERENCES achievement (id) ON DELETE CASCADE,
    unlocked_on    DATE      NOT NULL,
    points         INTEGER   NOT NULL DEFAULT 0,
    created_at     TIMESTAMP NOT NULL,
    CONSTRAINT uq_achievement_unlock UNIQUE (client_id, achievement_id)
);

-- Métrica y umbral de los logros del sistema (ADR-007 §6) y puntos (ADR-012 §5).
ALTER TABLE achievement ADD COLUMN metric    VARCHAR(20);
ALTER TABLE achievement ADD COLUMN threshold NUMERIC(12, 2);
ALTER TABLE achievement ADD COLUMN points    INTEGER NOT NULL DEFAULT 10;

UPDATE achievement SET metric = CASE badge
        WHEN 'birthday'  THEN 'BIRTHDAY'
        WHEN 'new-year'  THEN 'NEW_YEAR'
        WHEN 'start'     THEN 'FIRST_ACTIVITY'
        WHEN 'valentine' THEN 'VALENTINE'
        WHEN 'workouts'  THEN 'WORKOUTS'
        WHEN 'cardio'    THEN 'CARDIO'
        WHEN 'sessions'  THEN 'SESSIONS'
        WHEN 'nutrition' THEN 'NUTRITION'
        WHEN 'videos'    THEN 'VIDEOS'
        WHEN 'streak'    THEN 'STREAK_DAYS'
        WHEN 'perfect'   THEN 'PERFECT_DAYS'
        WHEN 'early'     THEN 'EARLY_DAYS'
    END
WHERE trainer_id IS NULL;
-- Hitos y Cumplimiento: el umbral es el número con el que empieza el nombre ("15 workouts completados").
UPDATE achievement SET threshold = CAST(split_part(name, ' ', 1) AS NUMERIC)
WHERE trainer_id IS NULL AND badge IN ('workouts', 'cardio', 'sessions', 'nutrition', 'videos', 'streak', 'perfect', 'early');
UPDATE achievement SET points = CASE tier WHEN 'SILVER' THEN 25 WHEN 'BRONZE' THEN 10 ELSE 5 END WHERE trainer_id IS NULL;

-- Categoría del sistema "Progreso" (referencia: totalgains/logros.md §3.3–3.4 y §3.2).
INSERT INTO achievement_category (system_key, name, description, icon, visibility, position, created_at) VALUES
  ('PROGRESS', 'Progreso', 'Volumen levantado, repeticiones y semanas constantes.', 'flag', 'ALL', 4, CURRENT_TIMESTAMP);

INSERT INTO achievement (category_id, name, description, group_label, badge, tier, condition_type, metric, threshold, points, position, created_at)
SELECT c.id, v.name, v.description, v.label, v.badge, v.tier, 'SYSTEM', v.metric, v.threshold, v.points, v.pos, CURRENT_TIMESTAMP
FROM achievement_category c,
     (VALUES ('Primera tonelada', 'Levantar 1.000 kg de volumen en un solo día.', 'Volumen en un día', 'volume', 'BRONZE', 'SESSION_VOLUME', 1000, 10, 11),
             ('Sesión seria', 'Levantar 8.000 kg de volumen en un solo día.', 'Volumen en un día', 'volume', 'SILVER', 'SESSION_VOLUME', 8000, 25, 12),
             ('Diez toneladas', 'Levantar 10.000 kg de volumen en un solo día.', 'Volumen en un día', 'volume', 'SILVER', 'SESSION_VOLUME', 10000, 25, 13),
             ('Cien coches', 'Acumular 150.000 kg de volumen (unos 100 coches).', 'Volumen acumulado', 'volume', 'SILVER', 'TOTAL_VOLUME', 150000, 50, 21),
             ('Parking entero', 'Acumular 1.500.000 kg de volumen (unos 1.000 coches).', 'Volumen acumulado', 'volume', 'SILVER', 'TOTAL_VOLUME', 1500000, 100, 22),
             ('1.000 repeticiones', 'Acumular 1.000 repeticiones registradas.', 'Repeticiones', 'reps', 'BRONZE', 'TOTAL_REPS_ALL', 1000, 10, 31),
             ('10.000 repeticiones', 'Acumular 10.000 repeticiones registradas.', 'Repeticiones', 'reps', 'SILVER', 'TOTAL_REPS_ALL', 10000, 50, 32),
             ('Semana básica', 'Entrenar 3 días en una misma semana.', 'Semanas', 'week', 'BRONZE', 'WEEK_SESSIONS', 3, 10, 41),
             ('Semana blindada', 'Entrenar 4 o más días en una misma semana.', 'Semanas', 'week', 'BRONZE', 'WEEK_SESSIONS', 4, 15, 42))
     AS v(name, description, label, badge, tier, metric, threshold, points, pos)
WHERE c.system_key = 'PROGRESS';
