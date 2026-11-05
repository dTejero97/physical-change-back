-- =====================================================================
-- Physical Change · V28 · Logros v2: puntos y emoji, "Próximamente" y catálogo completo (PostgreSQL 16) — T261
-- Contrato: physical-change-back/docs/api-logros-v2.md (T260) · Catálogo: docs/catalogo-logros-fase12.md (T259)
-- 1) Columnas nuevas: achievement.emoji, coming_soon y system_key (único); achievement_category.emoji; metric y
--    system_key más largos; puntos entre 0 y 1000.
-- 2) "Logros personalizados" pasan a visibles: lo que el profesional crea ahí ya llega a sus clientes.
-- 3) Catálogo del sistema: 20 secciones (F12_01…F12_20) y 142 logros. 9 son los de "Progreso" (V13), que se mueven
--    a su sección conservando id y desbloqueos; "Progreso" queda vacía y oculta. 76 son "Próximamente" (sin métrica).
-- 4) Orden: primero las 20 secciones nuevas; después Trofeos, Hitos y Cumplimiento (con emoji).
-- La parte de datos (secciones y logros) está generada desde el catálogo. No editar una vez desplegado.
--
-- Reversión manual (se pierden los desbloqueos de los logros nuevos; los 9 reutilizados vuelven a "Progreso"):
--   UPDATE achievement a SET category_id = p.id, system_key = NULL, emoji = NULL FROM achievement_category p
--     WHERE p.system_key = 'PROGRESS' AND a.system_key IN ('F12_01_08','F12_01_11','F12_01_12','F12_02_01','F12_02_02',
--                                                          'F12_05_01','F12_05_03','F12_06_03','F12_06_07');
--   -- y devolver sus valores de V13 (nombre, descripción, puntos, posición): ver V13__solicitudes_logros.sql
--   DELETE FROM achievement WHERE system_key LIKE 'F12_%';
--   DELETE FROM achievement_category WHERE system_key LIKE 'F12_%';
--   UPDATE achievement_category SET visibility = 'ALL', position = 4 WHERE system_key = 'PROGRESS';
--   UPDATE achievement_category SET position = 1 WHERE system_key = 'TROPHIES';
--   UPDATE achievement_category SET position = 2 WHERE system_key = 'MILESTONES';
--   UPDATE achievement_category SET position = 3 WHERE system_key = 'COMPLIANCE';
--   ALTER TABLE achievement DROP CONSTRAINT ck_achievement_points;
--   ALTER TABLE achievement DROP COLUMN emoji, DROP COLUMN coming_soon, DROP COLUMN system_key;
--   ALTER TABLE achievement_category DROP COLUMN emoji;
--   DELETE FROM flyway_schema_history WHERE version = '28';
--   ("Logros personalizados" se deja visible: volver a ocultarla escondería otra vez los logros de los profesionales.)
-- =====================================================================

-- ---- Esquema ----
ALTER TABLE achievement
    ADD COLUMN emoji       VARCHAR(16),
    ADD COLUMN coming_soon BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN system_key  VARCHAR(40),
    ADD CONSTRAINT ux_achievement_system_key UNIQUE (system_key),
    ADD CONSTRAINT ck_achievement_points CHECK (points BETWEEN 0 AND 1000);
ALTER TABLE achievement ALTER COLUMN metric TYPE VARCHAR(30);
ALTER TABLE achievement_category
    ADD COLUMN emoji VARCHAR(16);
ALTER TABLE achievement_category ALTER COLUMN system_key TYPE VARCHAR(40);

-- ---- Sincronización: la categoría personal ya no nace oculta (T260 §1) ----
UPDATE achievement_category SET visibility = 'ALL' WHERE trainer_id IS NOT NULL AND name = 'Logros personalizados' AND visibility = 'HIDDEN';

-- ---- Secciones existentes: emoji y orden detrás de las nuevas ----
UPDATE achievement_category SET emoji = '🏆', position = 21 WHERE system_key = 'TROPHIES';
UPDATE achievement_category SET emoji = '🎯', position = 22 WHERE system_key = 'MILESTONES';
UPDATE achievement_category SET emoji = '✅', position = 23 WHERE system_key = 'COMPLIANCE';

-- ---- Secciones del catálogo (20) ----
INSERT INTO achievement_category (system_key, name, description, icon, emoji, visibility, position, created_at) VALUES
  ('F12_01', 'Entrenos', NULL, 'flag', '🏋️', 'ALL', 1, CURRENT_TIMESTAMP),
  ('F12_02', 'Constancia semanal', NULL, 'flag', '📅', 'ALL', 2, CURRENT_TIMESTAMP),
  ('F12_03', 'Rachas', NULL, 'flag', '🔥', 'ALL', 3, CURRENT_TIMESTAMP),
  ('F12_04', 'Horarios', NULL, 'flag', '🌗', 'ALL', 4, CURRENT_TIMESTAMP),
  ('F12_05', 'Repeticiones', NULL, 'flag', '🔁', 'ALL', 5, CURRENT_TIMESTAMP),
  ('F12_06', 'Volumen histórico', NULL, 'flag', '🏗️', 'ALL', 6, CURRENT_TIMESTAMP),
  ('F12_07', 'Pasos', NULL, 'flag', '👣', 'ALL', 7, CURRENT_TIMESTAMP),
  ('F12_08', 'Seguimiento diario', NULL, 'flag', '📋', 'ALL', 8, CURRENT_TIMESTAMP),
  ('F12_09', 'Sueño', NULL, 'flag', '🌙', 'ALL', 9, CURRENT_TIMESTAMP),
  ('F12_10', 'Estado de ánimo', NULL, 'flag', '🙂', 'ALL', 10, CURRENT_TIMESTAMP),
  ('F12_11', 'Progreso corporal', NULL, 'flag', '⚖️', 'ALL', 11, CURRENT_TIMESTAMP),
  ('F12_12', 'Nutrición', NULL, 'flag', '🥗', 'ALL', 12, CURRENT_TIMESTAMP),
  ('F12_13', 'Suplementos', NULL, 'flag', '💊', 'ALL', 13, CURRENT_TIMESTAMP),
  ('F12_14', 'Tu entrenador', NULL, 'flag', '💬', 'ALL', 14, CURRENT_TIMESTAMP),
  ('F12_15', 'Notas de entreno', NULL, 'flag', '📝', 'ALL', 15, CURRENT_TIMESTAMP),
  ('F12_16', 'Técnicas avanzadas', NULL, 'flag', '🧰', 'ALL', 16, CURRENT_TIMESTAMP),
  ('F12_17', 'Cardio', NULL, 'flag', '🏃', 'ALL', 17, CURRENT_TIMESTAMP),
  ('F12_18', 'Compartir', NULL, 'flag', '📣', 'ALL', 18, CURRENT_TIMESTAMP),
  ('F12_19', 'Amigos', NULL, 'flag', '🤝', 'ALL', 19, CURRENT_TIMESTAMP),
  ('F12_20', 'Ranking', NULL, 'flag', '🏅', 'ALL', 20, CURRENT_TIMESTAMP)
ON CONFLICT (system_key) DO NOTHING;

-- ---- Logros reutilizados de "Progreso" (9): se mueven y se actualizan; conservan id y desbloqueos ----
UPDATE achievement a SET category_id = c.id, name = 'Primera tonelada', description = 'Has levantado 1.000 kg de volumen total en un solo entreno.', group_label = 'Entrenos', emoji = '🏋️', points = 2, tier = 'BRONZE', position = 8, system_key = 'F12_01_08'
  FROM achievement_category c, achievement_category p
  WHERE c.system_key = 'F12_01' AND p.system_key = 'PROGRESS' AND a.category_id = p.id AND a.name = 'Primera tonelada';
UPDATE achievement a SET category_id = c.id, name = 'Sesión seria', description = 'Primera sesión con ≥ 8.000 kg de volumen.', group_label = 'Entrenos', emoji = '🧱', points = 4, tier = 'BRONZE', position = 11, system_key = 'F12_01_11'
  FROM achievement_category c, achievement_category p
  WHERE c.system_key = 'F12_01' AND p.system_key = 'PROGRESS' AND a.category_id = p.id AND a.name = 'Sesión seria';
UPDATE achievement a SET category_id = c.id, name = 'Diez toneladas', description = 'Has levantado 10.000 kg de volumen total en un solo entreno.', group_label = 'Entrenos', emoji = '💣', points = 6, tier = 'BRONZE', position = 12, system_key = 'F12_01_12'
  FROM achievement_category c, achievement_category p
  WHERE c.system_key = 'F12_01' AND p.system_key = 'PROGRESS' AND a.category_id = p.id AND a.name = 'Diez toneladas';
UPDATE achievement a SET category_id = c.id, name = 'Semana básica completada', description = 'Haces 3 entrenos en una semana.', group_label = 'Constancia semanal', emoji = '✅', points = 20, tier = 'SILVER', position = 1, system_key = 'F12_02_01'
  FROM achievement_category c, achievement_category p
  WHERE c.system_key = 'F12_02' AND p.system_key = 'PROGRESS' AND a.category_id = p.id AND a.name = 'Semana básica';
UPDATE achievement a SET category_id = c.id, name = 'Semana blindada', description = 'Entrenas 4+ días en 7 días.', group_label = 'Constancia semanal', emoji = '🧱', points = 20, tier = 'SILVER', position = 2, system_key = 'F12_02_02'
  FROM achievement_category c, achievement_category p
  WHERE c.system_key = 'F12_02' AND p.system_key = 'PROGRESS' AND a.category_id = p.id AND a.name = 'Semana blindada';
UPDATE achievement a SET category_id = c.id, name = 'Canijo', description = 'Has conseguido tus primeras 1.000 repeticiones acumuladas.', group_label = 'Repeticiones', emoji = '👦', points = 10, tier = 'BRONZE', position = 1, system_key = 'F12_05_01'
  FROM achievement_category c, achievement_category p
  WHERE c.system_key = 'F12_05' AND p.system_key = 'PROGRESS' AND a.category_id = p.id AND a.name = '1.000 repeticiones';
UPDATE achievement a SET category_id = c.id, name = 'Máquina de reps', description = 'Has llegado a 10.000 repeticiones acumuladas.', group_label = 'Repeticiones', emoji = '⚙️', points = 150, tier = 'TEAL', position = 3, system_key = 'F12_05_03'
  FROM achievement_category c, achievement_category p
  WHERE c.system_key = 'F12_05' AND p.system_key = 'PROGRESS' AND a.category_id = p.id AND a.name = '10.000 repeticiones';
UPDATE achievement a SET category_id = c.id, name = 'Cien coches metafóricos', description = 'Volumen acumulado equivalente a levantar 100 coches.', group_label = 'Volumen histórico', emoji = '🚗', points = 100, tier = 'SILVER', position = 3, system_key = 'F12_06_03'
  FROM achievement_category c, achievement_category p
  WHERE c.system_key = 'F12_06' AND p.system_key = 'PROGRESS' AND a.category_id = p.id AND a.name = 'Cien coches';
UPDATE achievement a SET category_id = c.id, name = 'Parking entero', description = 'Volumen equivalente a 1.000 coches.', group_label = 'Volumen histórico', emoji = '🅿️', points = 450, tier = 'TEAL', position = 7, system_key = 'F12_06_07'
  FROM achievement_category c, achievement_category p
  WHERE c.system_key = 'F12_06' AND p.system_key = 'PROGRESS' AND a.category_id = p.id AND a.name = 'Parking entero';

-- ---- Logros nuevos del catálogo (133) ----
INSERT INTO achievement (system_key, category_id, name, description, group_label, badge, tier, condition_type, metric, threshold, points, emoji, coming_soon, position, created_at)
SELECT v.skey, c.id, v.name, v.description, v.label, 'custom', v.tier, 'SYSTEM', v.metric, v.threshold, v.points, v.emoji, v.soon, v.pos, CURRENT_TIMESTAMP
FROM (VALUES
  ('F12_01_01', 'F12_01', 'Día 1 de guerra', 'Completas tu primer entreno registrado.', 'Entrenos', 'BRONZE', 'WORKOUTS', 1, 2, '🗡️', false, 1),
  ('F12_01_02', 'F12_01', 'Full body destroyer', 'Entreno con 3+ grupos musculares grandes.', 'Entrenos', 'BRONZE', 'SESSION_BIG_GROUPS', 3, 6, '💀', false, 2),
  ('F12_01_03', 'F12_01', 'Cuerpo completo', '5+ grupos musculares en una sola sesión.', 'Entrenos', 'BRONZE', 'SESSION_MUSCLE_GROUPS', 5, 6, '🫀', false, 3),
  ('F12_01_04', 'F12_01', 'Full Body real', 'Sesión con mínimo 4 grupos musculares distintos y volumen ≥ 5.000 kg.', 'Entrenos', 'BRONZE', 'FULL_BODY_SESSION', 5000, 6, '💥', false, 4),
  ('F12_01_05', 'F12_01', 'Core Killer', 'Varios ejercicios de core en una sesión (3+).', 'Entrenos', 'BRONZE', 'SESSION_CORE_EXERCISES', 3, 6, '🔥', false, 5),
  ('F12_01_06', 'F12_01', 'Rutina completada', 'Completas todos los días de un programa por primera vez.', 'Entrenos', 'SILVER', 'PROGRAMS_COMPLETED', 1, 20, '📋', false, 6),
  ('F12_01_07', 'F12_01', 'Disciplinado', 'Completas 5 ciclos de programa completos.', 'Entrenos', 'TEAL', 'PROGRAMS_COMPLETED', 5, 150, '🔄', false, 7),
  ('F12_01_09', 'F12_01', 'Calentando motores', 'Primera vez que haces ≥ 3.000 kg de volumen en una sesión.', 'Entrenos', 'BRONZE', 'SESSION_VOLUME', 3000, 4, '🔧', false, 9),
  ('F12_01_10', 'F12_01', 'Cinco toneladas', 'Has levantado 5.000 kg de volumen total en un solo entreno.', 'Entrenos', 'BRONZE', 'SESSION_VOLUME', 5000, 4, '🛠️', false, 10),
  ('F12_01_13', 'F12_01', 'Express', 'Completa un entreno en menos de 30 minutos.', 'Entrenos', 'BRONZE', NULL, NULL, 4, '⚡', true, 13),
  ('F12_01_14', 'F12_01', 'Corto pero criminal', 'Entreno < 25 min con alto volumen.', 'Entrenos', 'BRONZE', NULL, NULL, 6, '⏱️', true, 14),
  ('F12_01_15', 'F12_01', 'Maratón de hierro', 'Entreno de 90+ minutos.', 'Entrenos', 'BRONZE', NULL, NULL, 6, '🐢', true, 15),
  ('F12_01_16', 'F12_01', 'Larga pero controlada', 'Sesión de ≥ 70 minutos efectiva.', 'Entrenos', 'BRONZE', NULL, NULL, 4, '⏲️', true, 16),
  ('F12_02_03', 'F12_02', 'Semana estándar de atleta', 'Haces 4 entrenos en una semana.', 'Constancia semanal', 'SILVER', 'WEEK_WORKOUTS', 4, 20, '🏃', false, 3),
  ('F12_02_04', 'F12_02', 'Semana vicio controlado', 'Haces 5 entrenos en una semana.', 'Constancia semanal', 'SILVER', 'WEEK_WORKOUTS', 5, 30, '🔁', false, 4),
  ('F12_02_05', 'F12_02', 'Doble sesión', '2 entrenos en el mismo día.', 'Constancia semanal', 'BRONZE', 'DOUBLE_SESSION', 1, 10, '♻️', false, 5),
  ('F12_02_06', 'F12_02', 'Nunca saltas lunes', 'Entrenas 4 lunes seguidos.', 'Constancia semanal', 'SILVER', 'WEEKDAY_STREAK_MON', 4, 50, '💼', false, 6),
  ('F12_02_07', 'F12_02', 'Viernes de hierro', 'Entrenas 4 viernes seguidos.', 'Constancia semanal', 'SILVER', 'WEEKDAY_STREAK_FRI', 4, 50, '🍺', false, 7),
  ('F12_02_08', 'F12_02', 'Vuelves al ring', 'Vuelves a entrenar tras 14+ días sin entrenos.', 'Constancia semanal', 'BRONZE', 'COMEBACK', 14, 2, '🥊', false, 8),
  ('F12_03_01', 'F12_03', 'No es casualidad', 'Completas 3 sesiones de entreno consecutivas.', 'Rachas', 'BRONZE', 'CONSECUTIVE_WORKOUTS', 3, 10, '📅', false, 1),
  ('F12_03_02', 'F12_03', 'Una semana en racha', 'Completas 7 sesiones de entreno consecutivas.', 'Rachas', 'SILVER', 'CONSECUTIVE_WORKOUTS', 7, 20, '🔗', false, 2),
  ('F12_03_03', 'F12_03', 'Dos semanas imparable', 'Completas 14 sesiones de entreno consecutivas.', 'Rachas', 'SILVER', 'CONSECUTIVE_WORKOUTS', 14, 50, '⛓️', false, 3),
  ('F12_03_04', 'F12_03', 'Hábito forjado', '21 sesiones consecutivas. Ya es un hábito.', 'Rachas', 'SILVER', 'CONSECUTIVE_WORKOUTS', 21, 75, '🔨', false, 4),
  ('F12_03_05', 'F12_03', 'Mes de hierro', '30 sesiones consecutivas sin fallar.', 'Rachas', 'SILVER', 'CONSECUTIVE_WORKOUTS', 30, 75, '🏗️', false, 5),
  ('F12_03_06', 'F12_03', 'Máquina imparable', '60 sesiones consecutivas. No hay quien te pare.', 'Rachas', 'TEAL', 'CONSECUTIVE_WORKOUTS', 60, 200, '🚂', false, 6),
  ('F12_03_07', 'F12_03', 'Trimestre de acero', '90 sesiones consecutivas. Disciplina de élite.', 'Rachas', 'TEAL', 'CONSECUTIVE_WORKOUTS', 90, 200, '⚔️', false, 7),
  ('F12_03_08', 'F12_03', 'Medio año invicto', '180 sesiones consecutivas. Eres leyenda.', 'Rachas', 'TEAL', 'CONSECUTIVE_WORKOUTS', 180, 500, '👑', false, 8),
  ('F12_03_09', 'F12_03', 'Un año sin excusas', '365 sesiones consecutivas. Hazaña épica.', 'Rachas', 'TEAL', 'CONSECUTIVE_WORKOUTS', 365, 750, '🏆', false, 9),
  ('F12_04_01', 'F12_04', 'Media Luna', 'Entreno completado después de las 22:00.', 'Horarios', 'BRONZE', NULL, NULL, 2, '🌙', true, 1),
  ('F12_04_02', 'F12_04', 'Amanecer de hierro', 'Entreno completado antes de las 7:00.', 'Horarios', 'BRONZE', NULL, NULL, 4, '🌅', true, 2),
  ('F12_05_02', 'F12_05', 'Ya no tan canijo', 'Has llegado a 5.000 repeticiones acumuladas.', 'Repeticiones', 'SILVER', 'TOTAL_REPS_ALL', 5000, 50, '😏', false, 2),
  ('F12_05_04', 'F12_05', 'Leyenda de las reps', 'Has llegado a 50.000 repeticiones acumuladas.', 'Repeticiones', 'TEAL', 'TOTAL_REPS_ALL', 50000, 450, '🏛️', false, 4),
  ('F12_06_01', 'F12_06', 'Despegue', '50.000 kg acumulados (potencia de despegue).', 'Volumen histórico', 'SILVER', 'TOTAL_VOLUME', 50000, 100, '🚀', false, 1),
  ('F12_06_02', 'F12_06', 'Cien mil kilos', 'Volumen histórico ≥ 100.000 kg.', 'Volumen histórico', 'SILVER', 'TOTAL_VOLUME', 100000, 100, '📦', false, 2),
  ('F12_06_04', 'F12_06', 'Cuarto de millón', 'Volumen histórico ≥ 250.000 kg.', 'Volumen histórico', 'TEAL', 'TOTAL_VOLUME', 250000, 150, '⚙️', false, 4),
  ('F12_06_05', 'F12_06', 'Medio millón de hierro', 'Volumen histórico ≥ 500.000 kg.', 'Volumen histórico', 'TEAL', 'TOTAL_VOLUME', 500000, 200, '🔩', false, 5),
  ('F12_06_06', 'F12_06', 'Un millón de kilos', 'Volumen histórico ≥ 1.000.000 kg.', 'Volumen histórico', 'TEAL', 'TOTAL_VOLUME', 1000000, 450, '💎', false, 6),
  ('F12_06_08', 'F12_06', 'Dos millones de guerra', 'Volumen histórico ≥ 2.000.000 kg.', 'Volumen histórico', 'TEAL', 'TOTAL_VOLUME', 2000000, 450, '⚔️', false, 8),
  ('F12_06_09', 'F12_06', 'Tres millones de carga', 'Volumen histórico ≥ 3.000.000 kg.', 'Volumen histórico', 'TEAL', 'TOTAL_VOLUME', 3000000, 600, '🚚', false, 9),
  ('F12_06_10', 'F12_06', 'Cinco millones de locura', 'Volumen histórico ≥ 5.000.000 kg.', 'Volumen histórico', 'TEAL', 'TOTAL_VOLUME', 5000000, 600, '🤯', false, 10),
  ('F12_06_11', 'F12_06', 'Siete coma cinco industrial', 'Volumen histórico ≥ 7.500.000 kg.', 'Volumen histórico', 'TEAL', 'TOTAL_VOLUME', 7500000, 750, '🏭', false, 11),
  ('F12_06_12', 'F12_06', 'Diez millones de historia', 'Volumen histórico ≥ 10.000.000 kg.', 'Volumen histórico', 'TEAL', 'TOTAL_VOLUME', 10000000, 750, '🏰', false, 12),
  ('F12_07_01', 'F12_07', 'Primeros 10K', 'Has acumulado 10.000 pasos totales registrados.', 'Pasos', 'BRONZE', NULL, NULL, 5, '🐾', true, 1),
  ('F12_07_02', 'F12_07', 'Sin ascensor', 'Has acumulado 50.000 pasos.', 'Pasos', 'SILVER', NULL, NULL, 20, '🚶', true, 2),
  ('F12_07_03', 'F12_07', 'Centurión de pasos', 'Has acumulado 100.000 pasos.', 'Pasos', 'SILVER', NULL, NULL, 20, '🏙️', true, 3),
  ('F12_07_04', 'F12_07', 'Carretera secundaria', 'Has acumulado 250.000 pasos.', 'Pasos', 'SILVER', NULL, NULL, 50, '🛣️', true, 4),
  ('F12_07_05', 'F12_07', 'Medio millón de zancadas', 'Has acumulado 500.000 pasos.', 'Pasos', 'SILVER', NULL, NULL, 75, '🦿', true, 5),
  ('F12_07_06', 'F12_07', 'Millón de pasos', 'Has acumulado 1.000.000 pasos.', 'Pasos', 'TEAL', NULL, NULL, 150, '💠', true, 6),
  ('F12_07_07', 'F12_07', 'Día de 10K', 'Un día con ≥ 10.000 pasos.', 'Pasos', 'BRONZE', NULL, NULL, 10, '🎪', true, 7),
  ('F12_07_08', 'F12_07', 'Modo explorador', 'Un día con ≥ 15.000 pasos.', 'Pasos', 'SILVER', NULL, NULL, 15, '🧭', true, 8),
  ('F12_07_09', 'F12_07', 'Podómetro roto', 'Un día con ≥ 20.000 pasos.', 'Pasos', 'SILVER', NULL, NULL, 20, '📟', true, 9),
  ('F12_07_10', 'F12_07', 'Semana en movimiento', 'Una semana con ≥ 50.000 pasos totales.', 'Pasos', 'SILVER', NULL, NULL, 20, '🗓️', true, 10),
  ('F12_07_11', 'F12_07', 'Semana de 10K/día', 'Una semana con ≥ 70.000 pasos.', 'Pasos', 'SILVER', NULL, NULL, 30, '🔧', true, 11),
  ('F12_07_12', 'F12_07', 'Semana maratoniana', 'Una semana con ≥ 100.000 pasos.', 'Pasos', 'SILVER', NULL, NULL, 40, '🏃‍♂️', true, 12),
  ('F12_08_01', 'F12_08', 'Tres días honestos', 'Rellenas el seguimiento 3 días seguidos.', 'Seguimiento diario', 'BRONZE', 'DIARY_STREAK', 3, 10, '🧾', false, 1),
  ('F12_08_02', 'F12_08', 'Semana sin excusas', 'Rellenas el seguimiento 7 días seguidos.', 'Seguimiento diario', 'SILVER', 'DIARY_STREAK', 7, 20, '🗳️', false, 2),
  ('F12_08_03', 'F12_08', 'Quincena ninja', 'Rellenas el seguimiento 14 días seguidos.', 'Seguimiento diario', 'SILVER', 'DIARY_STREAK', 14, 30, '🥷', false, 3),
  ('F12_08_04', 'F12_08', 'Diario blindado', 'Rellenas el seguimiento 30 días seguidos.', 'Seguimiento diario', 'SILVER', 'DIARY_STREAK', 30, 75, '🔐', false, 4),
  ('F12_08_05', 'F12_08', 'Dos meses de verdad', 'Rellenas el seguimiento 60 días seguidos.', 'Seguimiento diario', 'TEAL', 'DIARY_STREAK', 60, 150, '📘', false, 5),
  ('F12_08_06', 'F12_08', 'Cien días consciente', 'Rellenas el seguimiento 100 días seguidos.', 'Seguimiento diario', 'TEAL', 'DIARY_STREAK', 100, 200, '💡', false, 6),
  ('F12_08_07', 'F12_08', 'Año despierto', 'Rellenas el seguimiento 365 días seguidos.', 'Seguimiento diario', 'TEAL', 'DIARY_STREAK', 365, 500, '👑', false, 7),
  ('F12_08_08', 'F12_08', '4 semanas presentes', 'Rellenas el seguimiento al menos 1 vez por semana durante 4 semanas seguidas.', 'Seguimiento diario', 'SILVER', 'DIARY_WEEKS', 4, 50, '📊', false, 8),
  ('F12_08_09', 'F12_08', 'Trimestre despierto', 'Mantienes esa constancia 12 semanas.', 'Seguimiento diario', 'SILVER', 'DIARY_WEEKS', 12, 100, '👁️', false, 9),
  ('F12_08_10', 'F12_08', 'Seguimiento anual', 'Rellenas el seguimiento semanalmente durante 1 año.', 'Seguimiento diario', 'TEAL', 'DIARY_WEEKS', 52, 400, '📚', false, 10),
  ('F12_09_01', 'F12_09', 'Noche de humano sano', 'Un día con ≥ 8 h de sueño.', 'Sueño', 'BRONZE', 'SLEEP_NIGHT_MIN', 480, 10, '🔋', false, 1),
  ('F12_09_02', 'F12_09', 'Reseteo brutal', 'Una noche con ≥ 10 h de sueño (modo recuperación total).', 'Sueño', 'BRONZE', 'SLEEP_NIGHT_MIN', 600, 5, '🔄', false, 2),
  ('F12_09_03', 'F12_09', 'Semana de sueño decente', '7 días seguidos con ≥ 7 h de sueño.', 'Sueño', 'SILVER', 'SLEEP_STREAK_7H', 7, 30, '😌', false, 3),
  ('F12_09_04', 'F12_09', 'Zombi pero responsable', 'Duermes < 5 h, pero aun así rellenas el seguimiento.', 'Sueño', 'BRONZE', 'SHORT_SLEEP_LOGGED', 1, 2, '🧟', false, 4),
  ('F12_10_01', 'F12_10', 'Mal pero firme', 'Marcas tu estado emocional como "Mal" 3 días seguidos y aun así rellenas el seguimiento.', 'Estado de ánimo', 'SILVER', 'MOOD_LOW_STREAK', 3, 30, '🪨', false, 1),
  ('F12_10_02', 'F12_10', 'Remontada emocional', 'Tras 3+ días seguidos marcando "Mal", encadenas 3 días seguidos marcando "Bien" o mejor.', 'Estado de ánimo', 'SILVER', 'MOOD_COMEBACK', 3, 30, '🔃', false, 2),
  ('F12_11_01', 'F12_11', 'Primer peso registrado', 'Registras tu peso por primera vez.', 'Progreso corporal', 'BRONZE', 'WEIGHT_LOGGED', 1, 2, '🏋️‍♀️', false, 1),
  ('F12_11_02', 'F12_11', 'Primera foto', 'Subes tu primera foto de transformación.', 'Progreso corporal', 'BRONZE', NULL, NULL, 2, '📸', true, 2),
  ('F12_11_03', 'F12_11', 'Álbum de progreso', 'Subes 5+ fotos de transformación.', 'Progreso corporal', 'SILVER', NULL, NULL, 50, '🖼️', true, 3),
  ('F12_11_04', 'F12_11', 'Comparte tu cambio', 'Compartes una foto de transformación por primera vez.', 'Progreso corporal', 'BRONZE', NULL, NULL, 4, '🌟', true, 4),
  ('F12_12_01', 'F12_12', 'Primera comida marcada', 'Marcas tu primera comida como completada.', 'Nutrición', 'BRONZE', NULL, NULL, 2, '☑️', true, 1),
  ('F12_12_02', 'F12_12', 'Día perfecto de dieta', 'Completas el 100 % de tus comidas planificadas en un día.', 'Nutrición', 'BRONZE', NULL, NULL, 10, '🎯', true, 2),
  ('F12_12_03', 'F12_12', '3 días comiendo bien', '3 días seguidos completando todas tus comidas.', 'Nutrición', 'BRONZE', NULL, NULL, 10, '🥗', true, 3),
  ('F12_12_04', 'F12_12', 'Semana de precisión', '7 días seguidos con todas las comidas completadas.', 'Nutrición', 'SILVER', NULL, NULL, 20, '🏆', true, 4),
  ('F12_12_05', 'F12_12', 'Mes nutrido', '30 días con ≥ 80 % de adherencia a las comidas.', 'Nutrición', 'SILVER', NULL, NULL, 75, '💎', true, 5),
  ('F12_12_06', 'F12_12', 'Smart Shopper', 'Completas tu primera lista de la compra.', 'Nutrición', 'BRONZE', NULL, NULL, 2, '🛒', true, 6),
  ('F12_12_07', 'F12_12', 'Compra eficiente', 'Completas 5 listas de la compra.', 'Nutrición', 'SILVER', NULL, NULL, 50, '🧾', true, 7),
  ('F12_13_01', 'F12_13', 'Primer suplemento', 'Añades tu primer suplemento al inventario.', 'Suplementos', 'BRONZE', NULL, NULL, 2, '💊', true, 1),
  ('F12_13_02', 'F12_13', 'Stack builder', 'Tienes 5+ suplementos en tu inventario.', 'Suplementos', 'BRONZE', NULL, NULL, 10, '🧪', true, 2),
  ('F12_13_03', 'F12_13', 'Reposición puntual', 'Haces restock de suplementos 3+ veces.', 'Suplementos', 'SILVER', NULL, NULL, 25, '📦', true, 3),
  ('F12_13_04', 'F12_13', 'Código del coach', 'Usas un código de descuento de tu entrenador en suplementos.', 'Suplementos', 'BRONZE', NULL, NULL, 2, '🏷️', true, 4),
  ('F12_13_05', 'F12_13', 'Cliente fiel', 'Usas 5+ veces códigos de tu entrenador en suplementos.', 'Suplementos', 'SILVER', NULL, NULL, 100, '🤝', true, 5),
  ('F12_14_01', 'F12_14', 'Primera consulta', 'Envías tu primer mensaje al entrenador.', 'Tu entrenador', 'BRONZE', 'MESSAGES_SENT', 1, 2, '💬', false, 1),
  ('F12_14_02', 'F12_14', 'Comunicación activa', 'Envías 10+ mensajes a tu entrenador.', 'Tu entrenador', 'SILVER', 'MESSAGES_SENT', 10, 25, '📨', false, 2),
  ('F12_14_03', 'F12_14', 'Alumno aplicado', 'Envías 50+ mensajes a tu entrenador.', 'Tu entrenador', 'SILVER', 'MESSAGES_SENT', 50, 100, '🎓', false, 3),
  ('F12_14_04', 'F12_14', 'Feedback completo', 'Usas 3+ categorías distintas de feedback (entreno, nutrición, etc.).', 'Tu entrenador', 'SILVER', NULL, NULL, 20, '📊', true, 4),
  ('F12_15_01', 'F12_15', 'Primera nota técnica', 'Añades tu primera nota a un ejercicio.', 'Notas de entreno', 'BRONZE', NULL, NULL, 2, '📝', true, 1),
  ('F12_15_02', 'F12_15', 'Diario del atleta', 'Escribes 10+ notas en tus entrenos.', 'Notas de entreno', 'SILVER', NULL, NULL, 50, '📓', true, 2),
  ('F12_15_03', 'F12_15', 'Cronista del hierro', 'Escribes 50+ notas. Tu diario de entreno es una obra de arte.', 'Notas de entreno', 'TEAL', NULL, NULL, 150, '📚', true, 3),
  ('F12_15_04', 'F12_15', 'Nota con foto', 'Adjuntas tu primera imagen a una nota de ejercicio.', 'Notas de entreno', 'BRONZE', NULL, NULL, 2, '🖼️', true, 4),
  ('F12_15_05', 'F12_15', 'Galería técnica', 'Adjuntas 10+ imágenes a tus notas.', 'Notas de entreno', 'SILVER', NULL, NULL, 50, '🎨', true, 5),
  ('F12_15_06', 'F12_15', 'Nota en vídeo', 'Adjuntas tu primer vídeo a una nota de ejercicio.', 'Notas de entreno', 'BRONZE', NULL, NULL, 4, '📽️', true, 6),
  ('F12_15_07', 'F12_15', 'Videoteca personal', 'Adjuntas 5+ vídeos a tus notas.', 'Notas de entreno', 'SILVER', NULL, NULL, 50, '📼', true, 7),
  ('F12_15_08', 'F12_15', 'Nota de voz', 'Grabas tu primera nota de audio en un ejercicio.', 'Notas de entreno', 'BRONZE', NULL, NULL, 2, '🎙️', true, 8),
  ('F12_16_01', 'F12_16', 'Primer descenso', 'Completas tu primera serie descendente.', 'Técnicas avanzadas', 'BRONZE', 'DROP_SETS', 1, 4, '📉', false, 1),
  ('F12_16_02', 'F12_16', 'Tu primera biserie', 'Completas un ejercicio con biserie por primera vez.', 'Técnicas avanzadas', 'BRONZE', NULL, NULL, 4, '🔗', true, 2),
  ('F12_16_03', 'F12_16', 'Maestro de biseries', 'Completas 10+ series con biserie.', 'Técnicas avanzadas', 'SILVER', NULL, NULL, 50, '⛓️', true, 3),
  ('F12_16_04', 'F12_16', 'Mio Reps activado', 'Completas tu primera serie con Mio Reps.', 'Técnicas avanzadas', 'BRONZE', NULL, NULL, 4, '🔄', true, 4),
  ('F12_16_05', 'F12_16', 'Parciales con control', 'Completas tu primera serie con repeticiones parciales.', 'Técnicas avanzadas', 'BRONZE', NULL, NULL, 4, '✂️', true, 5),
  ('F12_16_06', 'F12_16', 'Arsenal técnico', 'Usas 3+ técnicas avanzadas distintas (biserie, descendente, mio reps, parciales).', 'Técnicas avanzadas', 'SILVER', NULL, NULL, 30, '🧰', true, 6),
  ('F12_17_01', 'F12_17', 'Primer cardio', 'Completas tu primer bloque de resistencia.', 'Cardio', 'BRONZE', NULL, NULL, 2, '🏃', true, 1),
  ('F12_17_02', 'F12_17', 'Cardio habitual', 'Completas 10 sesiones con bloques de resistencia.', 'Cardio', 'SILVER', NULL, NULL, 50, '🫁', true, 2),
  ('F12_17_03', 'F12_17', 'Máquina aeróbica', 'Completas 30 sesiones con resistencia.', 'Cardio', 'TEAL', NULL, NULL, 150, '💨', true, 3),
  ('F12_17_04', 'F12_17', 'Primeros 10 km', 'Acumulas 10 km totales en resistencia.', 'Cardio', 'BRONZE', NULL, NULL, 10, '🛤️', true, 4),
  ('F12_17_05', 'F12_17', 'Medio centenar', 'Acumulas 50 km totales en resistencia.', 'Cardio', 'SILVER', NULL, NULL, 50, '🗺️', true, 5),
  ('F12_17_06', 'F12_17', 'Centurión cardio', 'Acumulas 100 km totales en resistencia.', 'Cardio', 'TEAL', NULL, NULL, 150, '🥇', true, 6),
  ('F12_17_07', 'F12_17', 'Medio maratón acumulado', 'Acumulas 500 km totales. Ya podrías ir a otra ciudad.', 'Cardio', 'TEAL', NULL, NULL, 400, '🚀', true, 7),
  ('F12_17_08', 'F12_17', 'Multideporte', 'Completas resistencia en 3+ disciplinas distintas (carrera, bici, natación…).', 'Cardio', 'SILVER', NULL, NULL, 100, '🏊', true, 8),
  ('F12_18_01', 'F12_18', 'Presume un poco', 'Comparte algo por primera vez (entreno, comida, logro…).', 'Compartir', 'BRONZE', NULL, NULL, 2, '📤', true, 1),
  ('F12_18_02', 'F12_18', 'Comparte tu entreno', 'Comparte un resumen de entreno por primera vez.', 'Compartir', 'BRONZE', NULL, NULL, 2, '💪', true, 2),
  ('F12_18_03', 'F12_18', 'Evangelizador del hierro', 'Compartes entreno 5 veces.', 'Compartir', 'SILVER', NULL, NULL, 50, '📣', true, 3),
  ('F12_18_04', 'F12_18', 'Coach de Instagram', 'Compartes 5 resúmenes de entreno.', 'Compartir', 'SILVER', NULL, NULL, 50, '📱', true, 4),
  ('F12_18_05', 'F12_18', 'Altavoz del hierro', 'Compartes 5 veces con el mundo.', 'Compartir', 'SILVER', NULL, NULL, 50, '🔊', true, 5),
  ('F12_18_06', 'F12_18', 'Influencer del gym', 'Compartes 10 veces. Ya eres marca personal.', 'Compartir', 'SILVER', NULL, NULL, 100, '🌟', true, 6),
  ('F12_18_07', 'F12_18', 'Embajador Physical Change', 'Compartes 25 veces. La gente ya te pregunta qué app usas.', 'Compartir', 'TEAL', NULL, NULL, 150, '🥇', true, 7),
  ('F12_18_08', 'F12_18', 'Foodie fitness', 'Comparte una comida o plato por primera vez.', 'Compartir', 'BRONZE', NULL, NULL, 2, '🍽️', true, 8),
  ('F12_18_09', 'F12_18', 'Chef influencer', 'Compartes 5 comidas. Tu dieta inspira a otros.', 'Compartir', 'SILVER', NULL, NULL, 50, '🧑‍🍳', true, 9),
  ('F12_18_10', 'F12_18', 'Logro compartido', 'Compartes un logro desbloqueado.', 'Compartir', 'BRONZE', NULL, NULL, 2, '🏆', true, 10),
  ('F12_19_01', 'F12_19', 'Recluta 1 discípulo', '1 amigo se registra con tu código.', 'Amigos', 'BRONZE', NULL, NULL, 5, '🧑‍🎓', true, 1),
  ('F12_19_02', 'F12_19', 'Trío de amigos', '3 amigos se registran con tu código.', 'Amigos', 'SILVER', NULL, NULL, 20, '👫', true, 2),
  ('F12_19_03', 'F12_19', 'Crea tu ejército', '5 amigos se registran con tu código.', 'Amigos', 'SILVER', NULL, NULL, 100, '🪖', true, 3),
  ('F12_19_04', 'F12_19', 'Reclutador de élite', '10 amigos se registran con tu código.', 'Amigos', 'TEAL', NULL, NULL, 150, '🎖️', true, 4),
  ('F12_19_05', 'F12_19', 'Líder de legión', '20 amigos se registran con tu código.', 'Amigos', 'TEAL', NULL, NULL, 450, '👑', true, 5),
  ('F12_20_01', 'F12_20', 'Entre los Mejores', 'Entra en el Top 10 de cualquier categoría del ranking.', 'Ranking', 'SILVER', NULL, NULL, 20, '🎖️', true, 1),
  ('F12_20_02', 'F12_20', 'Podio', 'Alcanza el Top 3 en cualquier categoría del ranking.', 'Ranking', 'SILVER', NULL, NULL, 30, '🥉', true, 2),
  ('F12_20_03', 'F12_20', 'Campeón', 'Consigue el #1 en cualquier categoría del ranking.', 'Ranking', 'SILVER', NULL, NULL, 40, '👑', true, 3),
  ('F12_20_04', 'F12_20', 'Tu Primer Título', 'Desbloquea tu primer título de ranking.', 'Ranking', 'SILVER', NULL, NULL, 20, '🏅', true, 4),
  ('F12_20_05', 'F12_20', 'Coleccionista de Títulos', 'Acumula 5 títulos de ranking desbloqueados.', 'Ranking', 'SILVER', NULL, NULL, 75, '🏰', true, 5),
  ('F12_20_06', 'F12_20', 'Multicampeón', 'Consigue el #1 en 3 categorías diferentes del ranking.', 'Ranking', 'TEAL', NULL, NULL, 125, '🔱', true, 6)
) AS v(skey, section, name, description, label, tier, metric, threshold, points, emoji, soon, pos)
JOIN achievement_category c ON c.system_key = v.section
ON CONFLICT (system_key) DO NOTHING;

-- ---- "Progreso" queda vacía (sus 9 logros se han movido) y oculta ----
UPDATE achievement_category SET visibility = 'HIDDEN', position = 24 WHERE system_key = 'PROGRESS';
