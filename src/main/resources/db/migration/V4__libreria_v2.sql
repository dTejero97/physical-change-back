-- =====================================================================
-- Physical Change · V4 · Librería V2 (PostgreSQL 16)
-- Archivos, programas, documentos, formularios, plantillas y logros + catálogo del sistema.
-- Contrato: docs/api-libreria-v2.md. Archivos: docs/adr/ADR-001-almacenamiento-archivos.md (raíz).
-- Textos del catálogo redactados por el equipo (no copiados de Harbiz). No editar una vez desplegado.
--
-- Reversión manual (si hubiera que deshacer V4 antes de tener datos):
--   DROP TABLE achievement_hidden, achievement_category_pref, achievement, achievement_category_tag,
--              achievement_category, template_item, template_pack, library_form_question, library_form,
--              library_document_tag, library_document, library_program_item, library_program,
--              stored_file_data, stored_file;
--   DELETE FROM flyway_schema_history WHERE version = '4';
-- =====================================================================

-- ---------- ARCHIVOS ----------
CREATE TABLE stored_file (
    id           BIGSERIAL PRIMARY KEY,
    owner_id     BIGINT       NOT NULL REFERENCES app_user (id),
    name         VARCHAR(200) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL CHECK (size_bytes > 0),
    purpose      VARCHAR(10)  NOT NULL CHECK (purpose IN ('DOCUMENT', 'IMAGE')),
    created_at   TIMESTAMP    NOT NULL
);
CREATE INDEX ix_file_owner ON stored_file (owner_id);

-- Contenido aparte para que los listados no lo carguen.
CREATE TABLE stored_file_data (
    file_id BIGINT PRIMARY KEY REFERENCES stored_file (id) ON DELETE CASCADE,
    data    BYTEA  NOT NULL
);

-- ---------- PROGRAMAS ----------
CREATE TABLE library_program (
    id                BIGSERIAL PRIMARY KEY,
    trainer_id        BIGINT        NOT NULL REFERENCES app_user (id),
    name              VARCHAR(120)  NOT NULL,
    description       VARCHAR(2000),
    weeks             INTEGER       NOT NULL CHECK (weeks BETWEEN 1 AND 52),
    linked_program_id BIGINT        REFERENCES library_program (id) ON DELETE SET NULL,
    archive_client    BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP     NOT NULL,
    updated_at        TIMESTAMP     NOT NULL
);
CREATE INDEX ix_program_trainer ON library_program (trainer_id, updated_at);

-- ref_id sin clave foránea: CARDIO/VIDEO/NUTRITION_PLAN viven aún en el navegador (T27); title guarda el nombre.
CREATE TABLE library_program_item (
    id         BIGSERIAL PRIMARY KEY,
    program_id BIGINT       NOT NULL REFERENCES library_program (id) ON DELETE CASCADE,
    week       INTEGER      NOT NULL CHECK (week BETWEEN 1 AND 52),
    day        INTEGER      NOT NULL CHECK (day BETWEEN 1 AND 7),
    kind       VARCHAR(15)  NOT NULL CHECK (kind IN ('WORKOUT', 'CARDIO', 'VIDEO', 'NUTRITION_PLAN', 'NOTE')),
    ref_id     BIGINT,
    title      VARCHAR(160) NOT NULL,
    position   INTEGER      NOT NULL
);
CREATE INDEX ix_program_item_program ON library_program_item (program_id, week, day, position);

-- ---------- DOCUMENTOS ----------
CREATE TABLE library_document (
    id          BIGSERIAL PRIMARY KEY,
    trainer_id  BIGINT        NOT NULL REFERENCES app_user (id),
    name        VARCHAR(160)  NOT NULL,
    description VARCHAR(2000),
    visibility  VARCHAR(6)    NOT NULL DEFAULT 'HIDDEN' CHECK (visibility IN ('HIDDEN', 'ALL', 'TAGS')),
    file_id     BIGINT        NOT NULL UNIQUE REFERENCES stored_file (id),
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL
);
CREATE INDEX ix_document_trainer ON library_document (trainer_id);

CREATE TABLE library_document_tag (
    document_id BIGINT NOT NULL REFERENCES library_document (id) ON DELETE CASCADE,
    tag_id      BIGINT NOT NULL REFERENCES client_tag (id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, tag_id)
);

-- ---------- FORMULARIOS ----------
CREATE TABLE library_form (
    id          BIGSERIAL PRIMARY KEY,
    trainer_id  BIGINT        NOT NULL REFERENCES app_user (id),
    name        VARCHAR(160)  NOT NULL,
    description VARCHAR(2000),
    auto_send   BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL
);
CREATE INDEX ix_form_trainer ON library_form (trainer_id);

-- options: JSON ["opción 1", "opción 2"] (solo SINGLE y MULTIPLE).
CREATE TABLE library_form_question (
    id       BIGSERIAL PRIMARY KEY,
    form_id  BIGINT        NOT NULL REFERENCES library_form (id) ON DELETE CASCADE,
    position INTEGER       NOT NULL,
    kind     VARCHAR(12)   NOT NULL CHECK (kind IN ('SHORT_TEXT', 'LONG_TEXT', 'NUMBER', 'SINGLE', 'MULTIPLE', 'SCALE', 'DATE')),
    label    VARCHAR(300)  NOT NULL,
    required BOOLEAN       NOT NULL DEFAULT FALSE,
    options  VARCHAR(3000) NOT NULL DEFAULT '[]'
);
CREATE INDEX ix_question_form ON library_form_question (form_id, position);

-- ---------- PLANTILLAS (catálogo del sistema, solo lectura) ----------
CREATE TABLE template_pack (
    id          BIGSERIAL PRIMARY KEY,
    kind        VARCHAR(8)   NOT NULL CHECK (kind IN ('WORKOUT', 'PROGRAM')),
    name        VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL,
    cover       VARCHAR(20)  NOT NULL,
    position    INTEGER      NOT NULL
);

-- payload WORKOUT: {"exercises":[{"name","muscle","sets","reps","rest"}]}
-- payload PROGRAM: {"weeks":n,"workouts":[{"name","exercises":[...]}],"schedule":[{"week","day","workout":índice}]}
CREATE TABLE template_item (
    id          BIGSERIAL PRIMARY KEY,
    pack_id     BIGINT        NOT NULL REFERENCES template_pack (id) ON DELETE CASCADE,
    name        VARCHAR(160)  NOT NULL,
    description VARCHAR(1000) NOT NULL,
    detail      VARCHAR(40)   NOT NULL,
    payload     VARCHAR(8000) NOT NULL,
    position    INTEGER       NOT NULL
);
CREATE INDEX ix_template_item_pack ON template_item (pack_id, position);

-- ---------- LOGROS ----------
-- trainer_id NULL + system_key = categoría del sistema.
CREATE TABLE achievement_category (
    id          BIGSERIAL PRIMARY KEY,
    trainer_id  BIGINT       REFERENCES app_user (id),
    system_key  VARCHAR(20)  UNIQUE,
    name        VARCHAR(80)  NOT NULL,
    description VARCHAR(500),
    icon        VARCHAR(10)  NOT NULL DEFAULT 'custom' CHECK (icon IN ('custom', 'trophy', 'flag', 'check')),
    visibility  VARCHAR(6)   NOT NULL DEFAULT 'ALL' CHECK (visibility IN ('ALL', 'TAGS', 'HIDDEN')),
    position    INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL,
    CHECK ((trainer_id IS NULL) = (system_key IS NOT NULL))
);
CREATE INDEX ix_ach_category_trainer ON achievement_category (trainer_id);

CREATE TABLE achievement_category_tag (
    category_id BIGINT NOT NULL REFERENCES achievement_category (id) ON DELETE CASCADE,
    tag_id      BIGINT NOT NULL REFERENCES client_tag (id) ON DELETE CASCADE,
    PRIMARY KEY (category_id, tag_id)
);

-- Visibilidad elegida por cada profesional para las categorías del sistema (ALL u HIDDEN).
CREATE TABLE achievement_category_pref (
    trainer_id  BIGINT     NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    category_id BIGINT     NOT NULL REFERENCES achievement_category (id) ON DELETE CASCADE,
    visibility  VARCHAR(6) NOT NULL CHECK (visibility IN ('ALL', 'HIDDEN')),
    PRIMARY KEY (trainer_id, category_id)
);

CREATE TABLE achievement (
    id             BIGSERIAL PRIMARY KEY,
    category_id    BIGINT        NOT NULL REFERENCES achievement_category (id) ON DELETE CASCADE,
    trainer_id     BIGINT        REFERENCES app_user (id),
    name           VARCHAR(80)   NOT NULL,
    description    VARCHAR(500),
    group_label    VARCHAR(80),
    badge          VARCHAR(30)   NOT NULL DEFAULT 'custom',
    badge_file_id  BIGINT        REFERENCES stored_file (id) ON DELETE SET NULL,
    tier           VARCHAR(6)    NOT NULL DEFAULT 'TEAL' CHECK (tier IN ('BRONZE', 'SILVER', 'TEAL')),
    condition_type VARCHAR(16)   NOT NULL CHECK (condition_type IN
                       ('SYSTEM', 'WEIGHT_EXERCISE', 'TOTAL_REPS', 'STREAK', 'BODY_WEIGHT', 'TIME_WITH_YOU')),
    exercise_id    BIGINT        REFERENCES exercise (id) ON DELETE SET NULL,
    weight_kg      NUMERIC(6, 2),
    reps           INTEGER,
    days           INTEGER,
    target_kg      NUMERIC(5, 1),
    months         INTEGER,
    duration_type  VARCHAR(16)   NOT NULL DEFAULT 'UNLIMITED' CHECK (duration_type IN ('UNLIMITED', 'DATE_RANGE', 'AFTER_ASSIGNMENT')),
    start_date     DATE,
    end_date       DATE,
    days_after     INTEGER,
    position       INTEGER       NOT NULL DEFAULT 0,
    created_at     TIMESTAMP     NOT NULL
);
CREATE INDEX ix_achievement_category ON achievement (category_id, position);

-- Logros del sistema que un profesional ha ocultado.
CREATE TABLE achievement_hidden (
    trainer_id     BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    achievement_id BIGINT NOT NULL REFERENCES achievement (id) ON DELETE CASCADE,
    PRIMARY KEY (trainer_id, achievement_id)
);

-- =====================================================================
-- Catálogo del sistema
-- =====================================================================

INSERT INTO achievement_category (system_key, name, description, icon, visibility, position, created_at) VALUES
  ('TROPHIES', 'Trofeos', 'Fechas especiales para celebrar con tus clientes.', 'trophy', 'ALL', 1, CURRENT_TIMESTAMP),
  ('MILESTONES', 'Hitos', 'Actividades completadas acumuladas.', 'flag', 'ALL', 2, CURRENT_TIMESTAMP),
  ('COMPLIANCE', 'Cumplimiento', 'Constancia día a día.', 'check', 'ALL', 3, CURRENT_TIMESTAMP);

INSERT INTO achievement (category_id, name, description, group_label, badge, tier, condition_type, position, created_at)
SELECT c.id, v.name, v.description, NULL, v.badge, 'TEAL', 'SYSTEM', v.pos, CURRENT_TIMESTAMP
FROM achievement_category c,
     (VALUES ('Cumpleaños', 'Se consigue el día del cumpleaños del cliente.', 'birthday', 1),
             ('Año nuevo', 'Entrenar durante la primera semana del año.', 'new-year', 2),
             ('Empezamos', 'Completar la primera actividad asignada.', 'start', 3),
             ('San Valentín', 'Entrenar el 14 de febrero.', 'valentine', 4)) AS v(name, description, badge, pos)
WHERE c.system_key = 'TROPHIES';

-- Hitos: 5 familias × 6 niveles (5, 15, 30 bronce; 50, 100, 200 plata).
INSERT INTO achievement (category_id, name, description, group_label, badge, tier, condition_type, position, created_at)
SELECT c.id,
       n.n || ' ' || f.noun,
       'Completar ' || n.n || ' ' || f.noun || '.',
       f.label, f.badge,
       CASE WHEN n.n < 50 THEN 'BRONZE' ELSE 'SILVER' END,
       'SYSTEM', f.pos * 10 + n.ord, CURRENT_TIMESTAMP
FROM achievement_category c,
     (VALUES ('Workouts completados', 'workouts completados', 'workouts', 1),
             ('Cardios completados', 'cardios completados', 'cardio', 2),
             ('Sesiones completadas', 'sesiones', 'sessions', 3),
             ('Tareas de nutrición', 'tareas de nutrición', 'nutrition', 4),
             ('Vídeos completados', 'vídeos completados', 'videos', 5)) AS f(label, noun, badge, pos),
     (VALUES (5, 1), (15, 2), (30, 3), (50, 4), (100, 5), (200, 6)) AS n(n, ord)
WHERE c.system_key = 'MILESTONES';

-- Cumplimiento: 3 familias × 4 niveles (5, 15, 30 bronce; 50 plata).
INSERT INTO achievement (category_id, name, description, group_label, badge, tier, condition_type, position, created_at)
SELECT c.id,
       n.n || ' ' || f.noun,
       f.text || ' ' || n.n || ' días.',
       f.label, f.badge,
       CASE WHEN n.n < 50 THEN 'BRONZE' ELSE 'SILVER' END,
       'SYSTEM', f.pos * 10 + n.ord, CURRENT_TIMESTAMP
FROM achievement_category c,
     (VALUES ('Días consecutivos', 'días seguidos', 'Registrar actividad', 'streak', 1),
             ('Días al 100 %', 'días al 100 %', 'Completar todo lo planificado durante', 'perfect', 2),
             ('Días madrugando', 'días madrugando', 'Entrenar antes de las 8:00 durante', 'early', 3)) AS f(label, noun, text, badge, pos),
     (VALUES (5, 1), (15, 2), (30, 3), (50, 4)) AS n(n, ord)
WHERE c.system_key = 'COMPLIANCE';

-- Packs de plantillas.
INSERT INTO template_pack (kind, name, description, cover, position) VALUES
  ('WORKOUT', 'Gimnasio', 'Rutinas con equipamiento completo para sacar el máximo partido a la sala.', 'gym', 1),
  ('WORKOUT', 'En casa con material', 'Mancuernas y bandas elásticas para entrenar completo sin salir de casa.', 'home', 2),
  ('WORKOUT', 'Sin material', 'Peso corporal y poco espacio: entrenamientos para cualquier lugar.', 'bodyweight', 3),
  ('WORKOUT', 'Movilidad', 'Sesiones para ganar rango de movimiento y prevenir molestias.', 'mobility', 4),
  ('PROGRAM', 'Programas completos', 'Planificaciones de varias semanas listas para asignar.', 'program', 5);

INSERT INTO template_item (pack_id, name, description, detail, payload, position)
SELECT p.id, v.name, v.description, v.detail, v.payload, v.pos
FROM template_pack p,
     (VALUES
      ('Gimnasio', 'Full Body A (Intermedio)', 'Cuerpo completo con básicos multiarticulares. Calienta 10 minutos y deja 1-2 repeticiones en reserva en cada serie.', '6 ejercicios',
       '{"exercises":[{"name":"Sentadilla con barra","muscle":"Piernas","sets":4,"reps":8,"rest":150},{"name":"Press banca","muscle":"Pecho","sets":4,"reps":8,"rest":150},{"name":"Remo con barra","muscle":"Espalda","sets":4,"reps":10,"rest":120},{"name":"Press militar con mancuernas","muscle":"Hombros","sets":3,"reps":10,"rest":90},{"name":"Peso muerto rumano","muscle":"Piernas","sets":3,"reps":10,"rest":120},{"name":"Plancha frontal","muscle":"Core","sets":3,"reps":45,"rest":60}]}', 1),
      ('Gimnasio', 'Empuje (Intermedio)', 'Pecho, hombro y tríceps. Controla la bajada en los press y prioriza la técnica sobre la carga.', '6 ejercicios',
       '{"exercises":[{"name":"Press banca","muscle":"Pecho","sets":4,"reps":8,"rest":150},{"name":"Press inclinado con mancuernas","muscle":"Pecho","sets":3,"reps":10,"rest":120},{"name":"Press militar con barra","muscle":"Hombros","sets":4,"reps":8,"rest":120},{"name":"Elevaciones laterales","muscle":"Hombros","sets":3,"reps":15,"rest":60},{"name":"Fondos en paralelas","muscle":"Brazos","sets":3,"reps":10,"rest":90},{"name":"Extensión de tríceps en polea","muscle":"Brazos","sets":3,"reps":12,"rest":60}]}', 2),
      ('Gimnasio', 'Tracción (Intermedio)', 'Espalda y bíceps. Inicia cada tirón desde la escápula y mantén el torso estable.', '6 ejercicios',
       '{"exercises":[{"name":"Dominadas","muscle":"Espalda","sets":4,"reps":6,"rest":150},{"name":"Remo con barra","muscle":"Espalda","sets":4,"reps":10,"rest":120},{"name":"Jalón al pecho","muscle":"Espalda","sets":3,"reps":12,"rest":90},{"name":"Remo en polea baja","muscle":"Espalda","sets":3,"reps":12,"rest":90},{"name":"Face pull","muscle":"Hombros","sets":3,"reps":15,"rest":60},{"name":"Curl de bíceps con barra","muscle":"Brazos","sets":3,"reps":10,"rest":60}]}', 3),
      ('Gimnasio', 'Pierna y glúteo', 'Tren inferior con énfasis en glúteo. Busca rango completo y pausa de un segundo arriba en el hip thrust.', '6 ejercicios',
       '{"exercises":[{"name":"Hip thrust con barra","muscle":"Glúteos","sets":4,"reps":10,"rest":120},{"name":"Sentadilla búlgara","muscle":"Piernas","sets":3,"reps":10,"rest":90},{"name":"Peso muerto rumano","muscle":"Piernas","sets":4,"reps":8,"rest":120},{"name":"Prensa de piernas","muscle":"Piernas","sets":3,"reps":12,"rest":90},{"name":"Abducción en máquina","muscle":"Glúteos","sets":3,"reps":15,"rest":60},{"name":"Elevación de gemelos","muscle":"Piernas","sets":3,"reps":15,"rest":60}]}', 4),
      ('En casa con material', 'Full Body con mancuernas', 'Rutina completa con un par de mancuernas. Ajusta el peso para terminar cada serie con buena técnica.', '6 ejercicios',
       '{"exercises":[{"name":"Sentadilla goblet","muscle":"Piernas","sets":4,"reps":12,"rest":90},{"name":"Press de suelo con mancuernas","muscle":"Pecho","sets":3,"reps":12,"rest":90},{"name":"Remo a una mano con mancuerna","muscle":"Espalda","sets":3,"reps":12,"rest":60},{"name":"Zancadas alternas","muscle":"Piernas","sets":3,"reps":12,"rest":60},{"name":"Press de hombro con mancuernas","muscle":"Hombros","sets":3,"reps":12,"rest":60},{"name":"Dead bug","muscle":"Core","sets":3,"reps":10,"rest":45}]}', 1),
      ('En casa con material', 'Tren inferior en casa', 'Piernas y glúteo con mancuernas y banda. Mantén la rodilla alineada con el pie.', '5 ejercicios',
       '{"exercises":[{"name":"Sentadilla goblet","muscle":"Piernas","sets":4,"reps":15,"rest":60},{"name":"Peso muerto rumano con mancuernas","muscle":"Piernas","sets":4,"reps":12,"rest":60},{"name":"Puente de glúteo con banda","muscle":"Glúteos","sets":3,"reps":20,"rest":45},{"name":"Zancada inversa","muscle":"Piernas","sets":3,"reps":12,"rest":60},{"name":"Paso lateral con banda","muscle":"Glúteos","sets":3,"reps":20,"rest":45}]}', 2),
      ('En casa con material', 'Tren superior con bandas', 'Empuje y tracción con bandas elásticas. Controla la vuelta de la banda en cada repetición.', '5 ejercicios',
       '{"exercises":[{"name":"Remo con banda","muscle":"Espalda","sets":4,"reps":15,"rest":60},{"name":"Flexiones","muscle":"Pecho","sets":4,"reps":12,"rest":60},{"name":"Press de hombro con banda","muscle":"Hombros","sets":3,"reps":15,"rest":60},{"name":"Aperturas posteriores con banda","muscle":"Hombros","sets":3,"reps":15,"rest":45},{"name":"Curl de bíceps con banda","muscle":"Brazos","sets":3,"reps":15,"rest":45}]}', 3),
      ('Sin material', 'Full Body sin material', 'Circuito de peso corporal para cualquier lugar. Haz 3 vueltas descansando 1 minuto entre vueltas.', '6 ejercicios',
       '{"exercises":[{"name":"Sentadilla","muscle":"Piernas","sets":3,"reps":20,"rest":30},{"name":"Flexiones","muscle":"Pecho","sets":3,"reps":12,"rest":30},{"name":"Zancadas alternas","muscle":"Piernas","sets":3,"reps":16,"rest":30},{"name":"Remo invertido en mesa","muscle":"Espalda","sets":3,"reps":10,"rest":30},{"name":"Puente de glúteo","muscle":"Glúteos","sets":3,"reps":20,"rest":30},{"name":"Plancha frontal","muscle":"Core","sets":3,"reps":40,"rest":30}]}', 1),
      ('Sin material', 'HIIT 20 minutos', 'Intervalos de alta intensidad: 40 segundos de trabajo y 20 de descanso. Adapta el ritmo a tu nivel.', '5 ejercicios',
       '{"exercises":[{"name":"Burpees","muscle":"Cardio","sets":4,"reps":10,"rest":20},{"name":"Escaladores","muscle":"Core","sets":4,"reps":30,"rest":20},{"name":"Sentadilla con salto","muscle":"Piernas","sets":4,"reps":12,"rest":20},{"name":"Jumping jacks","muscle":"Cardio","sets":4,"reps":30,"rest":20},{"name":"Skipping","muscle":"Cardio","sets":4,"reps":30,"rest":20}]}', 2),
      ('Sin material', 'Core y abdomen', 'Estabilidad del tronco sin material. Respira de forma controlada y evita arquear la zona lumbar.', '5 ejercicios',
       '{"exercises":[{"name":"Plancha frontal","muscle":"Core","sets":3,"reps":45,"rest":30},{"name":"Plancha lateral","muscle":"Core","sets":3,"reps":30,"rest":30},{"name":"Dead bug","muscle":"Core","sets":3,"reps":12,"rest":30},{"name":"Bird dog","muscle":"Core","sets":3,"reps":12,"rest":30},{"name":"Hollow hold","muscle":"Core","sets":3,"reps":20,"rest":30}]}', 3),
      ('Movilidad', 'Movilidad de cadera y tobillo', 'Rutina breve para antes de entrenar pierna o para los días de descanso.', '5 ejercicios',
       '{"exercises":[{"name":"Rotaciones de cadera 90/90","muscle":"Movilidad","sets":2,"reps":10,"rest":20},{"name":"Estiramiento del flexor de cadera","muscle":"Movilidad","sets":2,"reps":30,"rest":20},{"name":"Movilidad de tobillo en pared","muscle":"Movilidad","sets":2,"reps":12,"rest":20},{"name":"Sentadilla profunda asistida","muscle":"Movilidad","sets":2,"reps":30,"rest":20},{"name":"Puente de glúteo","muscle":"Glúteos","sets":2,"reps":15,"rest":20}]}', 1),
      ('Movilidad', 'Movilidad de hombro y columna', 'Libera la parte alta de la espalda y los hombros. Ideal tras muchas horas sentado.', '5 ejercicios',
       '{"exercises":[{"name":"Gato-camello","muscle":"Movilidad","sets":2,"reps":10,"rest":20},{"name":"Rotación torácica en cuadrupedia","muscle":"Movilidad","sets":2,"reps":10,"rest":20},{"name":"Deslizamientos en pared","muscle":"Movilidad","sets":2,"reps":12,"rest":20},{"name":"Dislocaciones con pica","muscle":"Movilidad","sets":2,"reps":12,"rest":20},{"name":"Postura del niño","muscle":"Movilidad","sets":2,"reps":40,"rest":20}]}', 2),
      ('Programas completos', 'Iniciación a la fuerza · 3 semanas', 'Tres semanas con dos rutinas de cuerpo completo alternas (A y B), tres días por semana. Sube un poco la carga cada semana si la técnica es buena.', '3 semanas',
       '{"weeks":3,"workouts":[{"name":"Iniciación · Full Body A","exercises":[{"name":"Sentadilla goblet","muscle":"Piernas","sets":3,"reps":12,"rest":90},{"name":"Flexiones","muscle":"Pecho","sets":3,"reps":10,"rest":90},{"name":"Remo a una mano con mancuerna","muscle":"Espalda","sets":3,"reps":12,"rest":60},{"name":"Plancha frontal","muscle":"Core","sets":3,"reps":30,"rest":45}]},{"name":"Iniciación · Full Body B","exercises":[{"name":"Peso muerto rumano con mancuernas","muscle":"Piernas","sets":3,"reps":12,"rest":90},{"name":"Press de hombro con mancuernas","muscle":"Hombros","sets":3,"reps":10,"rest":60},{"name":"Zancada inversa","muscle":"Piernas","sets":3,"reps":10,"rest":60},{"name":"Dead bug","muscle":"Core","sets":3,"reps":10,"rest":45}]}],"schedule":[{"week":1,"day":1,"workout":0},{"week":1,"day":3,"workout":1},{"week":1,"day":5,"workout":0},{"week":2,"day":1,"workout":1},{"week":2,"day":3,"workout":0},{"week":2,"day":5,"workout":1},{"week":3,"day":1,"workout":0},{"week":3,"day":3,"workout":1},{"week":3,"day":5,"workout":0}]}', 1)
     ) AS v(pack, name, description, detail, payload, pos)
WHERE p.name = v.pack;
