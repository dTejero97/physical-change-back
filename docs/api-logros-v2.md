# API Logros v2: puntos, emoji, "Próximamente" y catálogo completo (T260) — contrato

Versión 1.0.0 · 2026-11-05

- **Fase 12** de `../../tareas.md`.
- **Catálogo:** `../../docs/catalogo-logros-fase12.md` (T259: 20 secciones y 142 logros).
- **Migración:** V28 (T261). **Implementación:** T262.
- **Amplía** `api-libreria-v2.md` (§ Logros) y `api-solicitudes-logros.md` (§ 4). Lo que no se menciona aquí sigue igual.

Objetivos (petición del usuario, 2026-11-05):
1. lo que el profesional crea o muestra en su librería de logros es exactamente lo que ve su cliente;
2. el profesional indica los **puntos** de cada logro, y profesional y cliente ven el mismo valor;
3. cada logro tiene **emoji**, o foto si se sube;
4. el catálogo completo de las capturas, con los logros que aún no se pueden medir marcados como **Próximamente**.

---

## 1. Sincronización profesional ↔ cliente

La regla de disponibilidad no cambia (`api-libreria-v2.md`). El cliente ve:
- los logros de su profesional (su entrenador o, si no tiene, el ADMIN);
- en categorías visibles para él: `ALL`, o `TAGS` con alguna de sus etiquetas. Las del sistema, según la preferencia del profesional;
- que no estén ocultos por el profesional.

Cambios en esta versión:

| Cambio | Antes | Ahora |
|---|---|---|
| Categoría por defecto "Logros personalizados" | Se creaba con `HIDDEN`: lo que el profesional creaba ahí nunca llegaba al cliente | Se crea con `ALL`. V28 pasa a `ALL` las que ya existen |
| `CategoryDto.clientVisible` (nuevo, booleano) | — | `true` si esa categoría llega a **todos** sus clientes (`ALL`, o sistema no oculta). La pantalla del profesional lo usa para avisar "Tus clientes no ven esta sección" |
| `AchievementDto.clientVisible` (nuevo) | — | `clientVisible` de su categoría y que el logro no esté oculto |
| Logros "Próximamente" | — | Se envían al cliente con `status = COMING_SOON` (§3). El profesional puede ocultarlos como cualquier otro |

No hay caché en ninguna capa: un logro o categoría nuevos, ocultos o mostrados se reflejan en la siguiente consulta del cliente (`GET /api/my/achievements`).

## 2. Profesional: puntos y emoji (`/api/library/achievements`)

### 2.1 `AchievementRequest` (logros propios), campos nuevos

| Campo | Tipo | Regla |
|---|---|---|
| `points` | entero | Obligatorio, de **1 a 1000**. Si un cliente antiguo de la API no lo envía, se usan 10 |
| `emoji` | texto o `null` | Opcional. Hasta **16 caracteres** (un emoji con modificadores o ZWJ ocupa varios). No puede contener letras ni cifras ASCII. `null` = usa el emoji de la categoría |

- La foto sigue llegando en `badgeFileId`. **Prioridad de lo que se ve:** foto > emoji del logro > emoji de la categoría > insignia por defecto.
- **Logros del sistema:** puntos, emoji y textos no se cambian (solo se ocultan con `PATCH …/{id}/hidden`, como hasta ahora). Ofrecer puntos propios por profesional queda en "Pendiente".

### 2.2 `CategoryRequest`, campo nuevo
`emoji`: texto o `null`, con las mismas reglas. En las categorías del sistema se ignora (solo cambia la visibilidad, como antes).

### 2.3 `AchievementDto` y `CategoryDto`, campos nuevos

`AchievementDto` añade:
- `points`: entero;
- `emoji`: texto o `null`;
- `comingSoon`: booleano;
- `clientVisible`: booleano;
- `metric` y `threshold`: ya existían en el contrato. Ahora también en los logros nuevos del sistema.

`CategoryDto` añade:
- `emoji`;
- `clientVisible`;
- `position`: orden de las secciones; el cliente las ve en el mismo orden.

### 2.4 Errores nuevos
- `points` fuera de 1–1000 → 400 "Los puntos deben estar entre 1 y 1000".
- `emoji` no válido → 400 "Emoji no válido".

## 3. Cliente (`GET /api/my/achievements` y `GET /api/clients/{id}/achievements`)

### 3.1 `ClientAchievementDto`
- **Añade:** `emoji` (resuelto: el del logro o, si no tiene, el de su categoría; nunca `null` si la categoría tiene emoji) y `categoryId`.
- **`badgeUrl`:** sigue indicando la foto. Si tiene valor, se muestra la foto.
- **`status`:** `UNLOCKED | LOCKED | NO_DATA | COMING_SOON`.
  - `COMING_SOON`: la app aún no registra los datos de ese logro. `progress`, `target`, `percent` y `unlockedOn` son `null`. **No se desbloquea nunca, no suma puntos y no aparece en `almost`.**
  - `NO_DATA` se mantiene para los logros medibles sin datos todavía (p. ej. cumpleaños sin fecha de nacimiento).

### 3.2 `ClientAchievementCategoryDto`
Añade:
- `emoji`;
- `unlockedCount`: conseguidos de la sección;
- `totalCount`: logros de la sección **sin contar `COMING_SOON`**;
- `comingSoonCount`.

Con esto se pintan los chips de sección "conseguidos / total".

### 3.3 `ClientAchievementsDto`
- `totalCount` ya no cuenta los `COMING_SOON`.
- Añade `comingSoonCount`.
- `points`, el nivel y la XP siguen siendo la suma de los puntos guardados en cada desbloqueo.

### 3.4 Puntos
- Al conseguir un logro se guardan **sus puntos de ese momento** (`achievement_unlock.points`, como hoy).
- Si el profesional cambia después los puntos de un logro propio:
  - los nuevos desbloqueos usan el valor nuevo;
  - los ya conseguidos conservan el suyo. El cliente no pierde ni gana puntos retroactivamente.
- En la tarjeta del cliente se muestran los `points` actuales del logro. En la lista de conseguidos, los del desbloqueo.

## 4. Métricas nuevas (logros del sistema)

Definiciones comunes:
- **Día con actividad:** día con alguna serie registrada (`exercise_log`, sin las de calentamiento), con algún workout completado o con asistencia a una sesión de agenda. Es la regla actual (ADR-007).
- **Sesión:** todas las series de un mismo día. La app no guarda la hora ni la duración del entreno.
- **Grupos musculares:** `exercise.muscle_group`, tomando el vacío como "sin grupo", que no cuenta.
  - **Grandes:** Pecho, Espalda, Hombros, Cuádriceps, Isquiotibiales, Glúteos, Piernas.
  - **Core:** Abdomen, Core.
  - "Cuerpo completo" cuenta como un grupo más.
- **Semana:** de lunes a domingo.
- **Volumen:** peso × repeticiones, sin calentamiento (como hoy).

| Métrica | Se consigue cuando… | Progreso que ve el cliente |
|---|---|---|
| `SESSION_MUSCLE_GROUPS` | Un día con ≥ umbral grupos musculares distintos | Máximo de grupos en un día |
| `SESSION_BIG_GROUPS` | Un día con ≥ umbral grupos **grandes** distintos | Máximo de grupos grandes en un día |
| `FULL_BODY_SESSION` | Un día con ≥ 4 grupos distintos **y** volumen ≥ umbral kg | Máximo volumen de un día con ≥ 4 grupos |
| `SESSION_CORE_EXERCISES` | Un día con ≥ umbral ejercicios **distintos** de core | Máximo de ejercicios de core en un día |
| `PROGRAMS_COMPLETED` | ≥ umbral asignaciones de programa (V27) con **todos** sus workouts completados | Programas completados |
| `WEEK_WORKOUTS` | Una semana con ≥ umbral workouts completados | Máximo semanal |
| `DOUBLE_SESSION` | ≥ umbral días con 2 o más workouts completados | Días con doble sesión |
| `WEEKDAY_STREAK_MON` | ≥ umbral lunes consecutivos con actividad | Mayor racha de lunes |
| `WEEKDAY_STREAK_FRI` | Ídem con viernes | Mayor racha de viernes |
| `COMEBACK` | Hay un día con actividad tras ≥ umbral días seguidos sin ella | — (sí/no: progreso nulo) |
| `CONSECUTIVE_WORKOUTS` | ≥ umbral workouts asignados completados seguidos, en orden de fecha programada. Un workout con fecha pasada sin completar rompe la racha; los futuros no cuentan | Mayor racha |
| `DROP_SETS` | ≥ umbral series de tipo `DROP` | Series descendentes |
| `WEIGHT_LOGGED` | ≥ umbral registros con peso (`progress_entry.weight_kg`) | Registros |
| `DIARY_STREAK` | ≥ umbral días seguidos con registro en el diario (`client_daily_log`) | Mayor racha |
| `DIARY_WEEKS` | ≥ umbral semanas seguidas con al menos un registro | Mayor racha de semanas |
| `SLEEP_NIGHT_MIN` | Un registro con `sleep_minutes` ≥ umbral | Máximo de minutos (se muestra en horas) |
| `SLEEP_STREAK_7H` | ≥ umbral días seguidos con `sleep_minutes` ≥ 420 | Mayor racha |
| `SHORT_SLEEP_LOGGED` | ≥ umbral registros con `sleep_minutes` < 300 | Registros |
| `MOOD_LOW_STREAK` | ≥ umbral días seguidos con `mood = 1` (Mal) | Mayor racha |
| `MOOD_COMEBACK` | Tras ≥ 3 días seguidos con `mood = 1`, ≥ umbral días seguidos con `mood ≥ 4` | — (sí/no) |
| `MESSAGES_SENT` | ≥ umbral mensajes del cliente a cualquier destinatario de su equipo (`chat_message.sender_id`) | Mensajes |
| `COMING_SOON` | Nunca (§3.1) | — |

Las métricas existentes (`WORKOUTS`, `SESSION_VOLUME`, `TOTAL_VOLUME`, `TOTAL_REPS_ALL`, `WEEK_SESSIONS`…) no cambian.

**Fecha de desbloqueo:**
- es el **día en que se cumplió**, como hoy;
- en las de racha o semana, el día que se alcanzó el umbral;
- en `COMEBACK` y `MOOD_COMEBACK`, el día de la vuelta o el tercer día bueno.

**Duraciones:** las de los logros del sistema son siempre `UNLIMITED`.

> **Ajuste al catálogo (T259):** "Full body destroyer" usa `SESSION_BIG_GROUPS` (umbral 3), porque la captura dice "grupos musculares **grandes**". "Cuerpo completo" sigue con `SESSION_MUSCLE_GROUPS` (umbral 5).

## 5. Catálogo del sistema (V28)

- **Se crean** las 20 secciones del catálogo como categorías del sistema (`system_key` = `F12_<SECCIÓN>`), con `emoji`, `position` y visibilidad `ALL`.
  - Las de solo "Próximamente" también son `ALL`, pero el profesional puede ocultarlas.
  - **Orden:** primero las 20 secciones nuevas en el orden del catálogo; después "Trofeos", "Hitos" y "Cumplimiento".
- **Se crean** los logros del catálogo con `system = true`, `points`, `emoji`, `metric`, `threshold`, `coming_soon` y `position`.
- **Se reutilizan** los 9 logros de "Progreso" indicados en el catálogo: se mueven de categoría y se actualizan nombre, puntos y emoji. Se conservan su `id` y sus desbloqueos.
  - "Progreso" queda vacía y con `visibility = HIDDEN`.
- Las secciones existentes ("Trofeos", "Hitos", "Cumplimiento") reciben un emoji: 🏆 🎯 ✅.
- **Idempotente:** si se ejecuta sobre una base que ya tiene alguno (por `system_key` del logro, nuevo campo único), no lo duplica.

## 6. Datos (V28, T261) — PostgreSQL 16

| Cambio | Detalle |
|---|---|
| `achievement.emoji` | `VARCHAR(16)` NULL |
| `achievement.coming_soon` | `BOOLEAN NOT NULL DEFAULT false` |
| `achievement.system_key` | `VARCHAR(40)` NULL, único. Clave estable de los logros del sistema para no duplicarlos; `NULL` en los propios |
| `achievement.points` | Ya existe. Se añade `CHECK (points BETWEEN 0 AND 1000)` |
| `achievement_category.emoji` | `VARCHAR(16)` NULL |
| `achievement_category.system_key` | Se amplía a `VARCHAR(40)` |
| `metric` | Se amplía el `CHECK` de valores, si lo hay, con las métricas de §4 |

- **Datos:**
  - "Logros personalizados" → `ALL`;
  - catálogo y reutilización (§5);
  - "Progreso" → `HIDDEN`.
- **Reversión manual:**
  - borrar los logros con `system_key LIKE 'F12_%'`, salvo los 9 reutilizados, que vuelven a "Progreso" con sus valores anteriores (se documentan en la cabecera);
  - borrar las categorías `F12_%`;
  - quitar las columnas nuevas.

## Pendiente (fuera de esta versión)
- Que el profesional cambie puntos, textos o emoji de los logros del sistema solo para sus clientes.
- Medir los logros "Próximamente": pasos, hora y duración del entreno, notas, cardio por distancia, compartir, amigos, ranking, suplementos, comidas marcadas, listas de la compra y fotos de progreso. Cada uno necesita su propia función, y entonces pasa de `COMING_SOON` a una métrica real.
- Logros secretos: el usuario decidió no crearlos (2026-11-05).
