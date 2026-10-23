-- =====================================================================
-- Physical Change · V3 · Sección Agenda (PostgreSQL 16)
-- Tipos de sesión, eventos con recurrencia materializada, reservas, horarios de citas y actividad.
-- Contrato: docs/api-agenda.md. Modelo: docs/adr/ADR-002-agenda-eventos.md (raíz del proyecto).
-- No editar una vez desplegado: cambios en V5__...
--
-- Reversión manual (si hubiera que deshacer V3 antes de tener datos):
--   DROP TABLE agenda_activity, appointment_schedule_tag, appointment_schedule,
--              agenda_booking, agenda_event_tag, agenda_event, session_type;
--   DELETE FROM flyway_schema_history WHERE version = '3';
-- =====================================================================

-- ---------- TIPOS DE SESIÓN ----------
CREATE TABLE session_type (
    id         BIGSERIAL PRIMARY KEY,
    trainer_id BIGINT      NOT NULL REFERENCES app_user (id),
    name       VARCHAR(60) NOT NULL,
    color      VARCHAR(7)  NOT NULL CHECK (color ~ '^#[0-9A-F]{6}$'),
    created_at TIMESTAMP   NOT NULL
);
CREATE UNIQUE INDEX ux_session_type_trainer_name ON session_type (trainer_id, lower(name));

-- ---------- EVENTOS ----------
-- Una fila por ocurrencia. series_key (UUID) une las ocurrencias de una serie recurrente.
-- cover: clave predefinida o 'file:<id>' (archivo propio, ver V4).
CREATE TABLE agenda_event (
    id              BIGSERIAL PRIMARY KEY,
    trainer_id      BIGINT        NOT NULL REFERENCES app_user (id),
    kind            VARCHAR(10)   NOT NULL CHECK (kind IN ('SESSION', 'MEETING', 'REMINDER', 'PRIVATE')),
    title           VARCHAR(120)  NOT NULL,
    emoji           VARCHAR(16),
    description     VARCHAR(2000),
    mode            VARCHAR(10)   CHECK (mode IN ('IN_PERSON', 'ONLINE')),
    location        VARCHAR(200),
    meeting_url     VARCHAR(500),
    show_as         VARCHAR(5)    CHECK (show_as IN ('BUSY', 'FREE')),
    session_type_id BIGINT        REFERENCES session_type (id),
    cover           VARCHAR(300),
    start_at        TIMESTAMP     NOT NULL,
    end_at          TIMESTAMP     NOT NULL,
    series_key      VARCHAR(36),
    capacity        INTEGER       CHECK (capacity IS NULL OR capacity BETWEEN 1 AND 500),
    bookable        VARCHAR(4)    NOT NULL DEFAULT 'NO' CHECK (bookable IN ('NO', 'YES', 'TAGS')),
    created_at      TIMESTAMP     NOT NULL,
    CHECK (end_at > start_at),
    CHECK (kind <> 'SESSION' OR session_type_id IS NOT NULL)
);
CREATE INDEX ix_event_trainer_start ON agenda_event (trainer_id, start_at);
CREATE INDEX ix_event_series ON agenda_event (series_key, start_at);
CREATE INDEX ix_event_session_type ON agenda_event (session_type_id);

-- Etiquetas de clientes que pueden reservar (bookable = 'TAGS').
CREATE TABLE agenda_event_tag (
    event_id BIGINT NOT NULL REFERENCES agenda_event (id) ON DELETE CASCADE,
    tag_id   BIGINT NOT NULL REFERENCES client_tag (id) ON DELETE CASCADE,
    PRIMARY KEY (event_id, tag_id)
);

-- ---------- RESERVAS ----------
CREATE TABLE agenda_booking (
    id         BIGSERIAL PRIMARY KEY,
    event_id   BIGINT      NOT NULL REFERENCES agenda_event (id) ON DELETE CASCADE,
    client_id  BIGINT      NOT NULL REFERENCES app_user (id),
    status     VARCHAR(10) NOT NULL CHECK (status IN ('BOOKED', 'WAITLIST', 'CANCELLED', 'ATTENDED', 'NO_SHOW')),
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP   NOT NULL,
    UNIQUE (event_id, client_id)
);
CREATE INDEX ix_booking_client ON agenda_booking (client_id);

-- ---------- HORARIOS DE CITAS ----------
-- slots: JSON [{ "weekday": 1..7, "from": "HH:mm", "to": "HH:mm" }].
CREATE TABLE appointment_schedule (
    id              BIGSERIAL PRIMARY KEY,
    trainer_id      BIGINT        NOT NULL REFERENCES app_user (id),
    title           VARCHAR(120)  NOT NULL,
    emoji           VARCHAR(16),
    mode            VARCHAR(10)   NOT NULL CHECK (mode IN ('IN_PERSON', 'ONLINE')),
    source          VARCHAR(5)    CHECK (source IN ('VIDEO', 'PHONE')),
    meeting_url     VARCHAR(500),
    location        VARCHAR(200),
    session_type_id BIGINT        NOT NULL REFERENCES session_type (id),
    description     VARCHAR(2000),
    cover           VARCHAR(300),
    start_date      DATE          NOT NULL,
    end_date        DATE          NOT NULL,
    duration_min    INTEGER       NOT NULL CHECK (duration_min BETWEEN 10 AND 240),
    slots           VARCHAR(4000) NOT NULL DEFAULT '[]',
    visibility      VARCHAR(4)    NOT NULL DEFAULT 'ALL' CHECK (visibility IN ('ALL', 'TAGS')),
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP     NOT NULL,
    CHECK (end_date >= start_date)
);
CREATE INDEX ix_schedule_trainer ON appointment_schedule (trainer_id, created_at);
CREATE INDEX ix_schedule_session_type ON appointment_schedule (session_type_id);

CREATE TABLE appointment_schedule_tag (
    schedule_id BIGINT NOT NULL REFERENCES appointment_schedule (id) ON DELETE CASCADE,
    tag_id      BIGINT NOT NULL REFERENCES client_tag (id) ON DELETE CASCADE,
    PRIMARY KEY (schedule_id, tag_id)
);

-- ---------- ACTIVIDAD ----------
-- Copia de texto del evento: sobrevive a su borrado (sin clave foránea a agenda_event).
CREATE TABLE agenda_activity (
    id          BIGSERIAL PRIMARY KEY,
    trainer_id  BIGINT       NOT NULL REFERENCES app_user (id),
    actor_id    BIGINT       NOT NULL REFERENCES app_user (id),
    actor_name  VARCHAR(120) NOT NULL,
    actor_kind  VARCHAR(10)  NOT NULL CHECK (actor_kind IN ('TRAINER', 'CLIENT', 'TEAM')),
    action      VARCHAR(20)  NOT NULL,
    event_title VARCHAR(120) NOT NULL,
    event_emoji VARCHAR(16),
    event_start TIMESTAMP    NOT NULL,
    recurrent   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL
);
CREATE INDEX ix_activity_trainer_at ON agenda_activity (trainer_id, created_at);
