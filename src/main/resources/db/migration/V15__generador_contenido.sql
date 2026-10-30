-- =====================================================================
-- Physical Change · V15 · Generador de contenido: cuentas sociales, OAuth, ideas, ganchos y copys,
-- colecciones, publicaciones por red, métricas y banco semilla (PostgreSQL 16) — Fase 8, T189.
-- Decisión: docs/adr/ADR-014-conectores-sociales.md (raíz del proyecto) · Contrato: docs/api-contenido.md §10
-- No editar una vez desplegado. Stripe (T127) pasa a V16.
--
-- Notas:
--   * TIMESTAMPTZ (nuevo en el esquema): instantes en UTC sin depender de la zona de la JVM (job de publicación).
--   * Los tokens y el code_verifier se guardan cifrados (AES-256-GCM, SOCIAL_TOKEN_KEY); nunca en claro.
--   * Las métricas nulas significan "sin datos" (nunca 0): cada fila exige al menos un valor.
--   * owner_id NULL en content_idea / content_snippet = banco del sistema (semilla al final).
--
-- Reversión manual (se pierden cuentas vinculadas, ideas, publicaciones y métricas):
--   DROP TABLE content_post_metric, content_post_target, content_post, content_collection_item, content_collection,
--              content_snippet, content_idea, social_oauth_state, social_account;
--   ALTER TABLE team_member DROP COLUMN can_use_content;
--   DELETE FROM notification WHERE type IN ('CONTENT_POST_DUE', 'CONTENT_POST_FAILED', 'SOCIAL_ACCOUNT_ATTENTION');
--   ALTER TABLE notification DROP CONSTRAINT notification_type_check;
--   ALTER TABLE notification ADD CONSTRAINT notification_type_check CHECK (type IN ('CHANGE_REQUEST_CREATED',
--       'CHANGE_REQUEST_ACCEPTED', 'CHANGE_REQUEST_REJECTED', 'PLAN_UPDATED', 'ACHIEVEMENT_UNLOCKED',
--       'ONBOARDING_COMPLETED', 'ONBOARDING_UPDATE_REQUESTED'));
--   DELETE FROM ai_usage WHERE kind IN ('CONTENT_IDEA', 'CONTENT_HOOK', 'CONTENT_COPY');
--   ALTER TABLE ai_usage DROP CONSTRAINT ai_usage_kind_check;
--   ALTER TABLE ai_usage ADD CONSTRAINT ai_usage_kind_check CHECK (kind IN ('WORKOUT', 'PROGRAM', 'DAILY_PLAN', 'RECIPE', 'CHAT'));
--   DELETE FROM flyway_schema_history WHERE version = '15';
-- =====================================================================

-- ---------- Cambios en tablas existentes ----------
-- Permiso del colaborador para el generador (T198, ADR-011): falso por defecto; solo lo cambia el profesional.
ALTER TABLE team_member ADD COLUMN can_use_content BOOLEAN NOT NULL DEFAULT FALSE;

-- Los CHECK de V13 (notification.type) y V7 (ai_usage.kind) se declararon en la columna:
-- PostgreSQL los llamó notification_type_check y ai_usage_kind_check.
ALTER TABLE notification DROP CONSTRAINT notification_type_check;
ALTER TABLE notification ADD CONSTRAINT notification_type_check CHECK (type IN ('CHANGE_REQUEST_CREATED',
    'CHANGE_REQUEST_ACCEPTED', 'CHANGE_REQUEST_REJECTED', 'PLAN_UPDATED', 'ACHIEVEMENT_UNLOCKED',
    'ONBOARDING_COMPLETED', 'ONBOARDING_UPDATE_REQUESTED',
    'CONTENT_POST_DUE', 'CONTENT_POST_FAILED', 'SOCIAL_ACCOUNT_ATTENTION'));

ALTER TABLE ai_usage DROP CONSTRAINT ai_usage_kind_check;
ALTER TABLE ai_usage ADD CONSTRAINT ai_usage_kind_check CHECK (kind IN ('WORKOUT', 'PROGRAM', 'DAILY_PLAN', 'RECIPE', 'CHAT',
    'CONTENT_IDEA', 'CONTENT_HOOK', 'CONTENT_COPY'));

-- ---------- Cuentas sociales vinculadas ----------
-- NOT_CONFIGURED y DISCONNECTED no se guardan: se calculan (sin claves en el servidor / sin fila).
-- Desconectar borra la fila con sus tokens y su usuario externo (minimización).
CREATE TABLE social_account (
    id                BIGSERIAL    PRIMARY KEY,
    owner_id          BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    network           VARCHAR(10)  NOT NULL CONSTRAINT ck_social_account_network
                          CHECK (network IN ('INSTAGRAM', 'TIKTOK', 'YOUTUBE', 'LINKEDIN', 'X', 'FACEBOOK', 'THREADS', 'PINTEREST')),
    status            VARCHAR(10)  NOT NULL CONSTRAINT ck_social_account_status
                          CHECK (status IN ('CONNECTED', 'DISABLED', 'EXPIRED', 'ERROR')),
    external_id       VARCHAR(100),
    external_username VARCHAR(120),
    access_token_enc  TEXT         NOT NULL,
    refresh_token_enc TEXT,
    token_expires_at  TIMESTAMPTZ,
    scopes            VARCHAR(500),
    connected_at      TIMESTAMPTZ  NOT NULL,
    connected_by      BIGINT       REFERENCES app_user (id) ON DELETE SET NULL,
    last_sync_at      TIMESTAMPTZ,
    sync_started_at   TIMESTAMPTZ,                 -- claim de la sincronización (UPDATE condicional)
    last_error        VARCHAR(500),                -- sin tokens ni datos sensibles
    error_count       SMALLINT     NOT NULL DEFAULT 0 CHECK (error_count >= 0),
    updated_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_social_account UNIQUE (owner_id, network)
);

-- state de OAuth: solo su SHA-256 (hex), un uso, 10 minutos, atado al usuario y a la red.
CREATE TABLE social_oauth_state (
    id                BIGSERIAL    PRIMARY KEY,
    state_hash        VARCHAR(64)  NOT NULL CONSTRAINT uq_social_oauth_state UNIQUE,
    user_id           BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    owner_id          BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    network           VARCHAR(10)  NOT NULL CONSTRAINT ck_social_oauth_state_network
                          CHECK (network IN ('INSTAGRAM', 'TIKTOK', 'YOUTUBE', 'LINKEDIN', 'X', 'FACEBOOK', 'THREADS', 'PINTEREST')),
    code_verifier_enc TEXT,                        -- PKCE; se borra al usarlo
    created_at        TIMESTAMPTZ  NOT NULL,
    expires_at        TIMESTAMPTZ  NOT NULL,
    used_at           TIMESTAMPTZ
);
CREATE INDEX ix_social_oauth_state_expires ON social_oauth_state (expires_at);

-- ---------- Ideas para vídeos ----------
CREATE TABLE content_idea (
    id         BIGSERIAL    PRIMARY KEY,
    owner_id   BIGINT       REFERENCES app_user (id) ON DELETE CASCADE,       -- NULL = banco del sistema
    title      VARCHAR(160) NOT NULL,
    hook       VARCHAR(300),
    script     TEXT         CONSTRAINT ck_content_idea_script CHECK (script IS NULL OR char_length(script) <= 4000),
    format     VARCHAR(12)  NOT NULL CONSTRAINT ck_content_idea_format
                   CHECK (format IN ('REEL', 'CAROUSEL', 'SHORT', 'STORY', 'POST', 'LONG_VIDEO')),
    objective  VARCHAR(16)  CONSTRAINT ck_content_idea_objective
                   CHECK (objective IS NULL OR objective IN ('ATTRACT_CLIENTS', 'AUTHORITY', 'COMMUNITY', 'SALES')),
    theme      VARCHAR(60),
    tags       VARCHAR(330),                                                  -- etiquetas separadas por comas (≤ 10)
    origin     VARCHAR(5)   NOT NULL CONSTRAINT ck_content_idea_origin CHECK (origin IN ('BANK', 'AI', 'OWN')),
    source_id  BIGINT       REFERENCES content_idea (id) ON DELETE SET NULL,  -- idea del banco copiada
    used       BOOLEAN      NOT NULL DEFAULT FALSE,
    favorite   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    -- Una fila del banco es siempre BANK y nunca una copia.
    CONSTRAINT ck_content_idea_bank CHECK (owner_id IS NOT NULL OR (origin = 'BANK' AND source_id IS NULL)),
    CONSTRAINT uq_content_idea_copy UNIQUE (owner_id, source_id)                -- una copia por idea del banco
);
CREATE INDEX ix_content_idea_owner ON content_idea (owner_id, updated_at DESC);

-- ---------- Ganchos (HOOK) y copys (COPY) ----------
CREATE TABLE content_snippet (
    id         BIGSERIAL    PRIMARY KEY,
    owner_id   BIGINT       REFERENCES app_user (id) ON DELETE CASCADE,       -- NULL = banco del sistema
    kind       VARCHAR(4)   NOT NULL CONSTRAINT ck_content_snippet_kind CHECK (kind IN ('HOOK', 'COPY')),
    text       TEXT         NOT NULL CONSTRAINT ck_content_snippet_text CHECK (char_length(text) BETWEEN 1 AND 5000),
    network    VARCHAR(10)  CONSTRAINT ck_content_snippet_network
                   CHECK (network IS NULL OR network IN ('INSTAGRAM', 'TIKTOK', 'YOUTUBE', 'LINKEDIN', 'X', 'FACEBOOK', 'THREADS', 'PINTEREST')),
    cta        VARCHAR(300),
    hashtags   VARCHAR(3100),                                                 -- sin '#', separados por comas (≤ 30)
    theme      VARCHAR(60),
    objective  VARCHAR(16)  CONSTRAINT ck_content_snippet_objective
                   CHECK (objective IS NULL OR objective IN ('ATTRACT_CLIENTS', 'AUTHORITY', 'COMMUNITY', 'SALES')),
    tags       VARCHAR(330),
    origin     VARCHAR(5)   NOT NULL CONSTRAINT ck_content_snippet_origin CHECK (origin IN ('BANK', 'AI', 'OWN')),
    source_id  BIGINT       REFERENCES content_snippet (id) ON DELETE SET NULL,
    favorite   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    -- Los ganchos no llevan red, CTA ni hashtags.
    CONSTRAINT ck_content_snippet_hook CHECK (kind = 'COPY' OR (network IS NULL AND cta IS NULL AND hashtags IS NULL)),
    CONSTRAINT ck_content_snippet_hook_len CHECK (kind = 'COPY' OR char_length(text) <= 300),
    CONSTRAINT ck_content_snippet_bank CHECK (owner_id IS NOT NULL OR (origin = 'BANK' AND source_id IS NULL)),
    CONSTRAINT uq_content_snippet_copy UNIQUE (owner_id, source_id)
);
CREATE INDEX ix_content_snippet_owner ON content_snippet (owner_id, kind, updated_at DESC);

-- ---------- Bancos de contenido (colecciones propias) ----------
CREATE TABLE content_collection (
    id         BIGSERIAL   PRIMARY KEY,
    owner_id   BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name       VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_content_collection UNIQUE (owner_id, name)
);

-- item_id polimórfico (content_idea o content_snippet según item_type): sin clave ajena;
-- el servicio borra estas filas al borrar el elemento.
CREATE TABLE content_collection_item (
    collection_id BIGINT      NOT NULL REFERENCES content_collection (id) ON DELETE CASCADE,
    item_type     VARCHAR(5)  NOT NULL CONSTRAINT ck_content_collection_item_type CHECK (item_type IN ('IDEA', 'HOOK', 'COPY')),
    item_id       BIGINT      NOT NULL,
    added_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (collection_id, item_type, item_id)
);
CREATE INDEX ix_content_collection_item_item ON content_collection_item (item_type, item_id);

-- ---------- Publicaciones ----------
CREATE TABLE content_post (
    id            BIGSERIAL    PRIMARY KEY,
    owner_id      BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_by    BIGINT       REFERENCES app_user (id) ON DELETE SET NULL,
    updated_by    BIGINT       REFERENCES app_user (id) ON DELETE SET NULL,
    title         VARCHAR(160) NOT NULL,
    body          TEXT         CONSTRAINT ck_content_post_body CHECK (body IS NULL OR char_length(body) <= 5000),
    hashtags      VARCHAR(3100),
    format        VARCHAR(12)  NOT NULL CONSTRAINT ck_content_post_format
                      CHECK (format IN ('REEL', 'CAROUSEL', 'SHORT', 'STORY', 'POST', 'LONG_VIDEO')),
    media_file_id BIGINT       REFERENCES stored_file (id) ON DELETE SET NULL,   -- borrar el archivo de la Librería no rompe la publicación
    media_url     VARCHAR(500),
    idea_id       BIGINT       REFERENCES content_idea (id) ON DELETE SET NULL,
    status        VARCHAR(10)  NOT NULL CONSTRAINT ck_content_post_status
                      CHECK (status IN ('DRAFT', 'SCHEDULED', 'DUE', 'PUBLISHED', 'FAILED', 'CANCELLED')),
    scheduled_at  TIMESTAMPTZ,
    time_zone     VARCHAR(40)  NOT NULL DEFAULT 'Europe/Madrid',
    due_at        TIMESTAMPTZ,                                                  -- cuándo lo tomó el job (claim)
    version       INTEGER      NOT NULL DEFAULT 0,                              -- bloqueo optimista
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_content_post_media CHECK (NOT (media_file_id IS NOT NULL AND media_url IS NOT NULL)),
    CONSTRAINT ck_content_post_media_url CHECK (media_url IS NULL OR media_url LIKE 'https://%'),
    CONSTRAINT ck_content_post_scheduled CHECK (status IN ('DRAFT', 'CANCELLED') OR scheduled_at IS NOT NULL)
);
CREATE INDEX ix_content_post_owner ON content_post (owner_id, scheduled_at);
CREATE INDEX ix_content_post_due ON content_post (status, scheduled_at);          -- job: SCHEDULED con scheduled_at <= now()
CREATE INDEX ix_content_post_idea ON content_post (idea_id);
CREATE INDEX ix_content_post_media ON content_post (media_file_id);

-- Una fila por red destino, con su propio estado.
CREATE TABLE content_post_target (
    id           BIGSERIAL    PRIMARY KEY,
    post_id      BIGINT       NOT NULL REFERENCES content_post (id) ON DELETE CASCADE,
    network      VARCHAR(10)  NOT NULL CONSTRAINT ck_content_post_target_network
                     CHECK (network IN ('INSTAGRAM', 'TIKTOK', 'YOUTUBE', 'LINKEDIN', 'X', 'FACEBOOK', 'THREADS', 'PINTEREST')),
    status       VARCHAR(10)  NOT NULL CONSTRAINT ck_content_post_target_status
                     CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'MANUAL_DUE', 'FAILED', 'CANCELLED')),
    claimed_at   TIMESTAMPTZ,
    attempts     SMALLINT     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    external_id  VARCHAR(200),
    external_url VARCHAR(500) CONSTRAINT ck_content_post_target_url CHECK (external_url IS NULL OR external_url LIKE 'https://%'),
    published_at TIMESTAMPTZ,
    error        VARCHAR(500),
    CONSTRAINT uq_content_post_target UNIQUE (post_id, network),
    CONSTRAINT ck_content_post_target_published CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);
CREATE INDEX ix_content_post_target_status ON content_post_target (status);

-- Totales acumulados por publicación, red y día de medición; una fila por origen.
CREATE TABLE content_post_metric (
    id          BIGSERIAL   PRIMARY KEY,
    post_id     BIGINT      NOT NULL REFERENCES content_post (id) ON DELETE CASCADE,
    network     VARCHAR(10) NOT NULL CONSTRAINT ck_content_post_metric_network
                    CHECK (network IN ('INSTAGRAM', 'TIKTOK', 'YOUTUBE', 'LINKEDIN', 'X', 'FACEBOOK', 'THREADS', 'PINTEREST')),
    measured_on DATE        NOT NULL,
    views       BIGINT      CHECK (views IS NULL OR views >= 0),
    reach       BIGINT      CHECK (reach IS NULL OR reach >= 0),
    likes       BIGINT      CHECK (likes IS NULL OR likes >= 0),
    comments    BIGINT      CHECK (comments IS NULL OR comments >= 0),
    shares      BIGINT      CHECK (shares IS NULL OR shares >= 0),
    saves       BIGINT      CHECK (saves IS NULL OR saves >= 0),
    source      VARCHAR(6)  NOT NULL CONSTRAINT ck_content_post_metric_source CHECK (source IN ('SYNC', 'MANUAL')),
    created_by  BIGINT      REFERENCES app_user (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_content_post_metric UNIQUE (post_id, network, measured_on, source),
    CONSTRAINT ck_content_post_metric_any CHECK (views IS NOT NULL OR reach IS NOT NULL OR likes IS NOT NULL
        OR comments IS NOT NULL OR shares IS NOT NULL OR saves IS NOT NULL)
);

-- =====================================================================
-- Semilla: banco del sistema (owner_id NULL, origin BANK). Fitness, en español, sin marcas de terceros
-- ni cifras de resultados inventadas.
-- =====================================================================
INSERT INTO content_idea (owner_id, title, hook, script, format, objective, theme, tags, origin, created_at, updated_at) VALUES
(NULL, '3 errores al empezar en el gimnasio', '¿Acabas de apuntarte al gimnasio? Evita esto.',
 '1. Copiar la rutina de otra persona sin adaptarla. 2. Saltarte el calentamiento. 3. Querer subir peso cada día. Cierre: qué hacer en su lugar.',
 'REEL', 'ATTRACT_CLIENTS', 'Fuerza', 'principiantes,gimnasio', 'BANK', now(), now()),
(NULL, 'Cómo calentar antes de entrenar pierna', 'Si no calientas así, tus sentadillas lo notan.',
 'Movilidad de tobillo y cadera, activación de glúteo y series de aproximación con poco peso.',
 'REEL', 'AUTHORITY', 'Movilidad', 'calentamiento,pierna', 'BANK', now(), now()),
(NULL, 'Técnica de sentadilla en 4 puntos', 'Cuatro claves para una sentadilla más segura.',
 'Pies firmes, rodillas en la línea de los pies, espalda neutra y profundidad que puedas controlar.',
 'CAROUSEL', 'AUTHORITY', 'Fuerza', 'técnica,sentadilla', 'BANK', now(), now()),
(NULL, 'Mito o verdad: sudar más quema más grasa', '¿Sudar mucho significa que has quemado más grasa?',
 'Explicar que el sudor regula la temperatura y que lo importante es el conjunto: entrenamiento, alimentación y descanso.',
 'SHORT', 'AUTHORITY', 'Pérdida de grasa', 'mitos', 'BANK', now(), now()),
(NULL, 'Un día de comidas sencillo para quien entrena', 'Lo que como en un día normal de entrenamiento.',
 'Desayuno, comida, merienda y cena con proteína en cada comida, verdura y una fuente de hidratos según la actividad.',
 'REEL', 'COMMUNITY', 'Nutrición', 'nutrición,día de comidas', 'BANK', now(), now()),
(NULL, 'Rutina de 20 minutos en casa sin material', 'No tienes tiempo ni material: prueba esto.',
 'Circuito de sentadillas, flexiones adaptadas, zancadas, plancha y puente de glúteo. Indicar descansos y nivel.',
 'REEL', 'ATTRACT_CLIENTS', 'Entrenamiento en casa', 'casa,sin material', 'BANK', now(), now()),
(NULL, 'Qué es la sobrecarga progresiva', 'La clave para seguir progresando tiene nombre.',
 'Explicar cómo aumentar poco a poco repeticiones, series o peso, y por qué hay que registrarlo.',
 'CAROUSEL', 'AUTHORITY', 'Fuerza', 'progresión,principiantes', 'BANK', now(), now()),
(NULL, 'Pregunta a tu comunidad: ¿mañana o tarde?', '¿Tú entrenas por la mañana o por la tarde?',
 'Encuesta en stories con las dos opciones y una respuesta del profesional sobre cómo elegir según su horario.',
 'STORY', 'COMMUNITY', 'Hábitos', 'encuesta,hábitos', 'BANK', now(), now()),
(NULL, 'Así es una sesión conmigo', 'Te enseño cómo es una sesión de principio a fin.',
 'Recibimiento, revisión de objetivos, calentamiento, bloque principal, vuelta a la calma y registro del progreso.',
 'REEL', 'SALES', 'Entrenamiento personal', 'servicio,sesión', 'BANK', now(), now()),
(NULL, 'Preguntas frecuentes antes de contratar un entrenador', 'Lo que todo el mundo me pregunta antes de empezar.',
 'Cuántos días hacen falta, si sirve para principiantes, si incluye nutrición y cómo se hace el seguimiento.',
 'CAROUSEL', 'SALES', 'Entrenamiento personal', 'preguntas frecuentes,servicio', 'BANK', now(), now()),
(NULL, 'Estiramientos para después de estar sentado', 'Si pasas muchas horas sentado, guarda esto.',
 'Flexores de cadera, pectoral, espalda alta e isquiotibiales: 30 segundos por lado, sin dolor.',
 'SHORT', 'COMMUNITY', 'Movilidad', 'movilidad,oficina', 'BANK', now(), now()),
(NULL, 'Cómo leer una etiqueta nutricional', 'Tres cosas que mirar en cualquier etiqueta.',
 'Tamaño de la ración, lista de ingredientes en orden y proteína, azúcares y grasas por ración.',
 'CAROUSEL', 'AUTHORITY', 'Nutrición', 'nutrición,compra', 'BANK', now(), now()),
(NULL, 'La importancia de dormir bien para progresar', 'Puedes entrenar perfecto y no avanzar por esto.',
 'Relación entre descanso, recuperación y rendimiento; hábitos sencillos para dormir mejor.',
 'POST', 'AUTHORITY', 'Hábitos', 'descanso,recuperación', 'BANK', now(), now()),
(NULL, 'Historia de un cliente (con su permiso)', 'Así empezó y así entrena hoy.',
 'Contar el punto de partida, el plan y los hábitos que cambió, sin prometer resultados y con su consentimiento por escrito.',
 'REEL', 'SALES', 'Testimonios', 'testimonio,clientes', 'BANK', now(), now()),
(NULL, 'Peso muerto rumano paso a paso', 'El ejercicio que tus isquios necesitan, bien hecho.',
 'Posición inicial, bisagra de cadera, recorrido controlado y errores comunes.',
 'REEL', 'AUTHORITY', 'Fuerza', 'técnica,peso muerto', 'BANK', now(), now()),
(NULL, 'Qué llevar en la bolsa del gimnasio', 'Lo que nunca falta en mi bolsa.',
 'Agua, toalla, calzado adecuado, libreta o app para registrar y algo de comer si entrenas lejos de una comida.',
 'STORY', 'COMMUNITY', 'Hábitos', 'gimnasio,organización', 'BANK', now(), now()),
(NULL, 'Cardio o pesas: qué hacer primero', 'La pregunta que más me hacen en el gimnasio.',
 'Depende del objetivo de la sesión: explicar cuándo conviene cada orden.',
 'SHORT', 'ATTRACT_CLIENTS', 'Pérdida de grasa', 'cardio,fuerza', 'BANK', now(), now()),
(NULL, 'Mi método de trabajo explicado', 'Cómo trabajo con cada persona, sin letra pequeña.',
 'Valoración inicial, plan personalizado, revisiones periódicas y comunicación entre sesiones.',
 'LONG_VIDEO', 'SALES', 'Entrenamiento personal', 'método,servicio', 'BANK', now(), now()),
(NULL, 'Respuesta a una pregunta de la comunidad', 'Me habéis preguntado esto y os respondo.',
 'Elegir una pregunta real de los comentarios o mensajes y responderla en menos de un minuto.',
 'REEL', 'COMMUNITY', 'Comunidad', 'preguntas,comunidad', 'BANK', now(), now()),
(NULL, 'Cómo empezar a correr sin lesionarte', 'Si quieres empezar a correr, empieza así.',
 'Alternar caminar y correr, aumentar el tiempo poco a poco, calzado adecuado y fuerza dos días por semana.',
 'CAROUSEL', 'ATTRACT_CLIENTS', 'Cardio', 'correr,principiantes', 'BANK', now(), now());

INSERT INTO content_snippet (owner_id, kind, text, theme, objective, tags, origin, created_at, updated_at) VALUES
(NULL, 'HOOK', 'Deja de hacer esto si quieres progresar en el gimnasio.', 'Fuerza', 'ATTRACT_CLIENTS', 'errores', 'BANK', now(), now()),
(NULL, 'HOOK', 'Nadie te cuenta esto cuando empiezas a entrenar.', 'Fuerza', 'ATTRACT_CLIENTS', 'principiantes', 'BANK', now(), now()),
(NULL, 'HOOK', 'El error más común que veo en la sentadilla.', 'Fuerza', 'AUTHORITY', 'técnica', 'BANK', now(), now()),
(NULL, 'HOOK', '¿Entrenas y no ves cambios? Mira esto.', 'Fuerza', 'ATTRACT_CLIENTS', 'progreso', 'BANK', now(), now()),
(NULL, 'HOOK', 'Guarda este vídeo para tu próximo entrenamiento.', 'Entrenamiento en casa', 'COMMUNITY', 'guardar', 'BANK', now(), now()),
(NULL, 'HOOK', 'Esto es lo que como en un día de entrenamiento.', 'Nutrición', 'COMMUNITY', 'día de comidas', 'BANK', now(), now()),
(NULL, 'HOOK', 'Mito o verdad: ¿hay que comer cada tres horas?', 'Nutrición', 'AUTHORITY', 'mitos', 'BANK', now(), now()),
(NULL, 'HOOK', 'Si solo tienes 20 minutos, haz esto.', 'Entrenamiento en casa', 'ATTRACT_CLIENTS', 'rápido', 'BANK', now(), now()),
(NULL, 'HOOK', 'Tres señales de que necesitas descansar más.', 'Hábitos', 'AUTHORITY', 'descanso', 'BANK', now(), now()),
(NULL, 'HOOK', 'Lo que aprendí después de años entrenando a personas.', 'Entrenamiento personal', 'AUTHORITY', 'experiencia', 'BANK', now(), now()),
(NULL, 'HOOK', 'Prueba este calentamiento antes de tu próxima sesión.', 'Movilidad', 'COMMUNITY', 'calentamiento', 'BANK', now(), now()),
(NULL, 'HOOK', 'No necesitas más fuerza de voluntad, necesitas un plan.', 'Hábitos', 'SALES', 'motivación', 'BANK', now(), now()),
(NULL, 'HOOK', 'Esto es lo que pasa en una primera sesión conmigo.', 'Entrenamiento personal', 'SALES', 'servicio', 'BANK', now(), now()),
(NULL, 'HOOK', '¿Cardio antes o después de las pesas? Depende de esto.', 'Cardio', 'AUTHORITY', 'cardio', 'BANK', now(), now()),
(NULL, 'HOOK', 'Pasas el día sentado: este estiramiento es para ti.', 'Movilidad', 'COMMUNITY', 'oficina', 'BANK', now(), now()),
(NULL, 'HOOK', 'La técnica primero, el peso después.', 'Fuerza', 'AUTHORITY', 'técnica', 'BANK', now(), now()),
(NULL, 'HOOK', 'Cuéntame en comentarios: ¿cuál es tu ejercicio favorito?', 'Comunidad', 'COMMUNITY', 'preguntas', 'BANK', now(), now()),
(NULL, 'HOOK', 'Antes de comprar ese suplemento, escucha esto.', 'Nutrición', 'AUTHORITY', 'suplementos', 'BANK', now(), now());

INSERT INTO content_snippet (owner_id, kind, text, network, cta, hashtags, theme, objective, tags, origin, created_at, updated_at) VALUES
(NULL, 'COPY', 'Empezar a entrenar no tiene por qué ser complicado. Con un plan adaptado a ti, a tu tiempo y a tu nivel, cada sesión cuenta. ¿Te ayudo a dar el primer paso?',
 NULL, 'Escríbeme por mensaje privado y te cuento cómo trabajo.', 'entrenamientopersonal,fitness,principiantes', 'Entrenamiento personal', 'ATTRACT_CLIENTS', 'servicio', 'BANK', now(), now()),
(NULL, 'COPY', 'La técnica es lo primero. Antes de añadir peso, asegúrate de controlar el movimiento completo. Guarda este post para tu próximo entrenamiento.',
 NULL, 'Guárdalo y compártelo con quien entrena contigo.', 'técnica,fuerza,gimnasio', 'Fuerza', 'AUTHORITY', 'técnica', 'BANK', now(), now()),
(NULL, 'COPY', 'No hace falta una dieta perfecta, hace falta una que puedas mantener. Proteína en cada comida, verdura todos los días y agua a mano.',
 NULL, 'Cuéntame en comentarios qué te cuesta más.', 'nutrición,hábitos,saludable', 'Nutrición', 'COMMUNITY', 'hábitos', 'BANK', now(), now()),
(NULL, 'COPY', 'Hoy toca movilidad. Diez minutos al día pueden hacer que te muevas mejor en tus entrenamientos y en tu día a día.',
 NULL, 'Prueba la rutina y dime cómo te ha ido.', 'movilidad,estiramientos,bienestar', 'Movilidad', 'COMMUNITY', 'movilidad', 'BANK', now(), now()),
(NULL, 'COPY', 'Cada persona es distinta, y su entrenamiento también debería serlo. Trabajo con valoración inicial, plan personalizado y seguimiento continuo.',
 'LINKEDIN', 'Si quieres saber más, envíame un mensaje.', 'entrenamientopersonal,salud,bienestar', 'Entrenamiento personal', 'SALES', 'servicio', 'BANK', now(), now()),
(NULL, 'COPY', 'Sin material y en 20 minutos: sentadillas, flexiones adaptadas, zancadas, plancha y puente de glúteo. Repite el circuito tres veces.',
 NULL, 'Guárdalo para tu próximo entrenamiento en casa.', 'entrenamientoencasa,fitness,rutina', 'Entrenamiento en casa', 'ATTRACT_CLIENTS', 'casa', 'BANK', now(), now()),
(NULL, 'COPY', 'Descansar también es entrenar. Dormir bien y planificar los días de descanso te ayuda a recuperarte y a rendir mejor.',
 NULL, '¿Cuántas horas duermes? Te leo.', 'descanso,recuperación,hábitos', 'Hábitos', 'AUTHORITY', 'descanso', 'BANK', now(), now()),
(NULL, 'COPY', 'Pregunta de la semana: ¿qué te gustaría aprender sobre entrenamiento o nutrición? Elegiré algunas para los próximos vídeos.',
 NULL, 'Déjamela en comentarios.', 'comunidad,preguntas,fitness', 'Comunidad', 'COMMUNITY', 'preguntas', 'BANK', now(), now());
