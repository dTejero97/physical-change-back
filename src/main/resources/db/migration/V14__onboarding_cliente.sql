-- =====================================================================
-- Physical Change · V14 · Asistente de bienvenida del cliente (onboarding)
-- (PostgreSQL 16) — Fase 6.5, T178. Especificación: docs/fase6-onboarding.md
-- Contrato: physical-change-back/docs/api-onboarding.md
-- No editar una vez desplegado.
--
-- Reversión manual (antes, pasar a 'NONE' las fichas con gender = 'OTHER'):
--   DROP TABLE client_onboarding;
--   UPDATE client_profile SET gender = 'NONE' WHERE gender = 'OTHER';
--   ALTER TABLE client_profile DROP CONSTRAINT client_profile_gender_check;
--   ALTER TABLE client_profile ADD CONSTRAINT client_profile_gender_check CHECK (gender IN ('NONE', 'MALE', 'FEMALE'));
--   DELETE FROM flyway_schema_history WHERE version = '14';
-- =====================================================================

-- ---------- Ficha del cliente: género "Otro" ----------
-- El CHECK de V2 se declaró en la columna; PostgreSQL lo llamó client_profile_gender_check.
ALTER TABLE client_profile DROP CONSTRAINT client_profile_gender_check;
ALTER TABLE client_profile ADD CONSTRAINT client_profile_gender_check CHECK (gender IN ('NONE', 'MALE', 'FEMALE', 'OTHER'));

-- ---------- Respuestas del asistente ----------
-- Una fila por cliente, creada en su primer guardado. Los datos básicos (nacimiento, peso, altura, género)
-- viven en client_profile. Las listas de varias opciones se guardan como códigos separados por comas;
-- el backend valida los valores, los duplicados y los excluyentes.
CREATE TABLE client_onboarding (
    client_id          BIGINT        PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    -- Paso 1
    goals              VARCHAR(60),
    -- Paso 2
    experience         VARCHAR(15)   CHECK (experience IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    commitment         VARCHAR(12)   CHECK (commitment IN ('MODERATE', 'CONSISTENT', 'ALL_IN')),
    technique_level    INTEGER       CHECK (technique_level BETWEEN 1 AND 5),
    -- Paso 3
    training_style     VARCHAR(20)   CHECK (training_style IN ('HYPERTROPHY', 'STRENGTH', 'FAT_LOSS', 'PERFORMANCE', 'GENERAL_HEALTH')),
    cardio             VARCHAR(10)   CHECK (cardio IN ('MINIMAL', 'MODERATE', 'AS_NEEDED')),
    diet_style         VARCHAR(10)   CHECK (diet_style IN ('FLEXIBLE', 'STRICT', 'CHAOTIC')),
    meals_per_day      INTEGER       CHECK (meals_per_day BETWEEN 1 AND 8),
    liked_exercises    VARCHAR(60),
    avoid_areas        VARCHAR(30),
    -- Paso 4 · datos de salud (RGPD art. 9): solo con consentimiento; retirarlo los borra.
    health_consent     BOOLEAN,
    health_consent_at  TIMESTAMP,
    injuries           VARCHAR(60),
    injuries_other     VARCHAR(200),
    medical_notes      VARCHAR(1000),
    health_conditions  VARCHAR(120),
    food_allergy       BOOLEAN,
    allergy_details    VARCHAR(300),
    -- Paso 4 · hábitos (no son datos de salud)
    special_diet       VARCHAR(12)   CHECK (special_diet IN ('NONE', 'VEGETARIAN', 'VEGAN')),
    must_keep          VARCHAR(500),
    disliked_foods     VARCHAR(500),
    cooks              VARCHAR(8)    CHECK (cooks IN ('MOSTLY', 'RARELY')),
    completed_at       TIMESTAMP,
    updated_at         TIMESTAMP     NOT NULL,
    -- Defensa en profundidad: sin consentimiento no puede quedar ningún dato de salud.
    CONSTRAINT ck_client_onboarding_health CHECK (health_consent IS TRUE OR (
        injuries IS NULL AND injuries_other IS NULL AND medical_notes IS NULL AND health_conditions IS NULL
        AND food_allergy IS NULL AND allergy_details IS NULL AND health_consent_at IS NULL))
);
