-- =====================================================================
-- Physical Change · Esquema PostgreSQL · versión 1.0.0
-- Lo aplica Flyway automáticamente al arrancar el backend.
-- NO editar este fichero una vez desplegado: cualquier cambio va en V2__..., V3__...
-- Hibernate está en ddl-auto=validate: comprueba que las entidades coinciden con estas tablas.
-- =====================================================================

-- ---------- USUARIOS ----------
-- Roles: ADMIN (administrador), TRAINER (entrenador/nutricionista), CLIENT (cliente).
-- trainer_id: entrenador responsable del cliente (solo CLIENT).
-- active / nutrition_enabled: flags 0/1 (así lo espera la entidad AppUser).
CREATE TABLE app_user (
    id                 BIGSERIAL PRIMARY KEY,
    email              VARCHAR(160) NOT NULL UNIQUE,
    password_hash      VARCHAR(100) NOT NULL,
    full_name          VARCHAR(120) NOT NULL,
    role               VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN', 'TRAINER', 'CLIENT')),
    trainer_id         BIGINT REFERENCES app_user (id),
    active             INTEGER      NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    nutrition_enabled  INTEGER      NOT NULL DEFAULT 0 CHECK (nutrition_enabled IN (0, 1))
);
CREATE INDEX ix_app_user_trainer ON app_user (trainer_id);

-- ---------- EJERCICIOS (catálogo) ----------
-- owner_id NULL = ejercicio global (catálogo base). Si tiene dueño, solo lo borra él o un ADMIN.
CREATE TABLE exercise (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(120) NOT NULL,
    muscle_group VARCHAR(60),
    description  VARCHAR(2000),
    owner_id     BIGINT REFERENCES app_user (id)
);
CREATE INDEX ix_exercise_muscle ON exercise (muscle_group);

-- ---------- RUTINAS (entrenamientos) ----------
CREATE TABLE workout (
    id         BIGSERIAL PRIMARY KEY,
    trainer_id BIGINT       NOT NULL REFERENCES app_user (id),
    name       VARCHAR(120) NOT NULL,
    notes      VARCHAR(2000)
);
CREATE INDEX ix_workout_trainer ON workout (trainer_id);

-- Ejercicio dentro de una rutina con su configuración: series, repeticiones, peso y descanso.
CREATE TABLE workout_item (
    id           BIGSERIAL PRIMARY KEY,
    workout_id   BIGINT        NOT NULL REFERENCES workout (id) ON DELETE CASCADE,
    exercise_id  BIGINT        NOT NULL REFERENCES exercise (id),
    position     INTEGER       NOT NULL,
    sets         INTEGER       NOT NULL CHECK (sets BETWEEN 1 AND 20),
    reps         INTEGER       NOT NULL CHECK (reps BETWEEN 1 AND 200),
    weight_kg    NUMERIC(6, 2) NOT NULL DEFAULT 0 CHECK (weight_kg >= 0),
    rest_seconds INTEGER CHECK (rest_seconds IS NULL OR rest_seconds BETWEEN 0 AND 900),
    notes        VARCHAR(300)
);
CREATE INDEX ix_workout_item_workout ON workout_item (workout_id, position);

-- Rutina asignada a un cliente en una fecha. completed_date se rellena cuando el cliente la termina.
CREATE TABLE workout_assignment (
    id             BIGSERIAL PRIMARY KEY,
    workout_id     BIGINT NOT NULL REFERENCES workout (id),
    client_id      BIGINT NOT NULL REFERENCES app_user (id),
    scheduled_date DATE   NOT NULL,
    completed_date DATE
);
CREATE INDEX ix_assignment_client_date ON workout_assignment (client_id, scheduled_date);
CREATE INDEX ix_assignment_workout ON workout_assignment (workout_id);

-- Registro real de series: lo que el cliente levantó (peso x repeticiones).
-- assignment_id / workout_item_id opcionales: permiten comparar lo planificado con lo hecho.
CREATE TABLE exercise_log (
    id              BIGSERIAL PRIMARY KEY,
    client_id       BIGINT        NOT NULL REFERENCES app_user (id),
    assignment_id   BIGINT REFERENCES workout_assignment (id) ON DELETE SET NULL,
    workout_item_id BIGINT REFERENCES workout_item (id) ON DELETE SET NULL,
    exercise_id     BIGINT        NOT NULL REFERENCES exercise (id),
    log_date        DATE          NOT NULL,
    set_number      INTEGER       NOT NULL CHECK (set_number >= 1),
    reps            INTEGER       NOT NULL CHECK (reps BETWEEN 0 AND 500),
    weight_kg       NUMERIC(6, 2) NOT NULL CHECK (weight_kg >= 0)
);
CREATE INDEX ix_exercise_log_client_date ON exercise_log (client_id, log_date);
CREATE INDEX ix_exercise_log_assignment ON exercise_log (assignment_id);

-- ---------- SEGUIMIENTO CORPORAL ----------
CREATE TABLE progress_entry (
    id           BIGSERIAL PRIMARY KEY,
    client_id    BIGINT       NOT NULL REFERENCES app_user (id),
    entry_date   DATE         NOT NULL,
    weight_kg    NUMERIC(5, 2),
    body_fat_pct NUMERIC(4, 1),
    notes        VARCHAR(500)
);
CREATE INDEX ix_progress_client_date ON progress_entry (client_id, entry_date);

-- ---------- CITAS / CALENDARIO ----------
CREATE TABLE appointment (
    id         BIGSERIAL PRIMARY KEY,
    trainer_id BIGINT       NOT NULL REFERENCES app_user (id),
    client_id  BIGINT       NOT NULL REFERENCES app_user (id),
    start_at   TIMESTAMP    NOT NULL,
    end_at     TIMESTAMP    NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'BOOKED' CHECK (status IN ('BOOKED', 'CANCELLED')),
    title      VARCHAR(200),
    CHECK (end_at > start_at)
);
CREATE INDEX ix_appointment_trainer_start ON appointment (trainer_id, start_at);
CREATE INDEX ix_appointment_client_start ON appointment (client_id, start_at);

-- ---------- MENSAJES ----------
CREATE TABLE chat_message (
    id           BIGSERIAL PRIMARY KEY,
    sender_id    BIGINT        NOT NULL REFERENCES app_user (id),
    recipient_id BIGINT        NOT NULL REFERENCES app_user (id),
    body         VARCHAR(2000) NOT NULL,
    sent_at      TIMESTAMP     NOT NULL
);
CREATE INDEX ix_chat_pair ON chat_message (sender_id, recipient_id, sent_at);

-- ---------- NUTRICIÓN ----------
-- Alimentos con macros por 100 g.
CREATE TABLE food (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120)  NOT NULL,
    kcal_100g   NUMERIC(7, 2) NOT NULL CHECK (kcal_100g >= 0),
    protein_g   NUMERIC(6, 2),
    carbs_g     NUMERIC(6, 2),
    fat_g       NUMERIC(6, 2)
);
CREATE INDEX ix_food_name ON food (lower(name));

-- Plan nutricional de un cliente (objetivo de kcal diarias).
CREATE TABLE nutrition_plan (
    id         BIGSERIAL PRIMARY KEY,
    trainer_id BIGINT       NOT NULL REFERENCES app_user (id),
    client_id  BIGINT       NOT NULL REFERENCES app_user (id),
    name       VARCHAR(120) NOT NULL,
    daily_kcal INTEGER,
    notes      VARCHAR(2000)
);
CREATE INDEX ix_plan_client ON nutrition_plan (client_id);

-- Comidas del plan: "Desayuno", "Merienda", "Cena"... con alimento y gramos.
CREATE TABLE nutrition_meal (
    id        BIGSERIAL PRIMARY KEY,
    plan_id   BIGINT        NOT NULL REFERENCES nutrition_plan (id) ON DELETE CASCADE,
    meal_name VARCHAR(60)   NOT NULL,
    position  INTEGER       NOT NULL,
    food_id   BIGINT        NOT NULL REFERENCES food (id),
    grams     NUMERIC(7, 1) NOT NULL CHECK (grams > 0)
);
CREATE INDEX ix_meal_plan ON nutrition_meal (plan_id, meal_name, position);

-- ---------- PAGOS ----------
-- status: PENDING (pendiente) | PAID (pagado). Un pago vencido = PENDING con due_date anterior a hoy.
CREATE TABLE payment (
    id         BIGSERIAL PRIMARY KEY,
    trainer_id BIGINT        NOT NULL REFERENCES app_user (id),
    client_id  BIGINT        NOT NULL REFERENCES app_user (id),
    amount     NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
    concept    VARCHAR(160)  NOT NULL,
    due_date   DATE          NOT NULL,
    paid_date  DATE,
    status     VARCHAR(20)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PAID')),
    CHECK ((status = 'PAID') = (paid_date IS NOT NULL))
);
CREATE INDEX ix_payment_trainer_status ON payment (trainer_id, status);
CREATE INDEX ix_payment_client ON payment (client_id, due_date);
