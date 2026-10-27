# Contrato API · Physical AI (v1.5.0)

Asistentes de creación (workout, programa, plan diario, receta), instrucciones permanentes, créditos y panel de conversación.
Decisiones: `docs/adr/ADR-003-physical-ai.md` (raíz del proyecto). Pantallas: `docs/fase5-pantallas-v3.md` (sección I).
Migración: `src/main/resources/db/migration/V7__physical_ai.sql`. Swagger: grupo "Physical AI".

Reglas comunes
- JWT obligatorio. Solo TRAINER y ADMIN. Todo se refiere al profesional autenticado; nunca se ven créditos, instrucciones ni conversaciones de otro.
- Errores `{ timestamp, status, error, message }`:
  - 400 validación.
  - 402 **sin créditos** ("Has usado tus N créditos de este mes").
  - 409 asistente no disponible todavía (receta y plan diario hasta T27).
  - 422 la IA no devolvió un resultado válido tras un reintento (no se cobra).
  - 429 límite de mensajes de chat del día o demasiadas peticiones seguidas (cabecera `Retry-After`).
  - 503 Physical AI no configurado (`AI_PROVIDER=none`), presupuesto diario gratuito agotado o proveedor caído.
- Una generación tarda de 5 a 60 s. No hay streaming. Si el cliente HTTP corta la petición, no se cobra.
- **Se cobra solo si sale bien**: el crédito se descuenta en la misma transacción que guarda el borrador.
- Lo que escribe el profesional son **datos**, no instrucciones del sistema (ADR-003 §10 y riesgos).

**Implementación (T70):** `AiController`, `service/ai/AiService` (estado, créditos, instrucciones, workout y programa), `AiChatService` (conversaciones), `AiProvider` con `WorkersAiProvider` (`config/AiConfig`, `AI_PROVIDER=workers-ai`) y uno desactivado (`none`). Rutas de los asistentes: `POST /api/ai/generate/workout`, `/program`, `/daily-plan`, `/recipe`.
- Formato de Workers AI comprobado en la documentación de Cloudflare (2026-10-27): `POST /client/v4/accounts/{cuenta}/ai/run/{modelo}` con `messages`, `max_tokens` y `response_format { type: "json_schema", json_schema }`; respuesta en `result.response` (texto u objeto en JSON mode) y `result.usage { prompt_tokens, completion_tokens }`. **Sin probar contra la cuenta real** (falta el token): lo comprueba DevOps en T84.
- Workout: los elementos con un ejercicio fuera del catálogo o fuera de rango se descartan con aviso. Programa: un `WORKOUT` con un id que no es tuyo pasa a `NOTE` con su título (con aviso) y las semanas fuera de rango se descartan. "Personalizar para un cliente" envía solo sus servicios y etiquetas (el perfil no tiene objetivo, nivel ni días disponibles).
- Chat: se envían como mucho los 6 últimos mensajes, con los nombres de los clientes propios sustituidos por "Cliente N"; 429 lleva `Retry-After` con los segundos hasta medianoche. No se cobra el chat (crédito 0) pero cuenta en el presupuesto de neuronas.
- La receta sigue respondiendo 409 (T27): **pendiente decidir el contrato de imágenes** (`generateImages: false` aquí frente a la decisión del usuario del 2026-10-26; ver Dudas del 2026-10-27 en `tareas.md`).

## Estado y créditos
| Método | Ruta | Respuesta |
|---|---|---|
| GET | `/api/ai/status` | `AiStatusDto { enabled, provider, model, credits: CreditsDto, chatMessagesToday, chatDailyLimit, dailyBudgetExhausted, assistants: { WORKOUT, PROGRAM, DAILY_PLAN, RECIPE }: { available, reason? } }` |

`CreditsDto { used, total, resetsOn (primer día del mes siguiente) }` · Coste: WORKOUT 1, DAILY_PLAN 1, RECIPE 1, PROGRAM 2. El chat no gasta créditos (límite de 30 mensajes al día).

## Instrucciones permanentes
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/ai/instructions` | — | `{ text, updatedAt }` (`text` vacío si no hay) |
| PUT | `/api/ai/instructions` | `{ text (0-1000) }` | `{ text, updatedAt }` |

Se añaden, como preferencias del profesional, a todos los asistentes y al chat.

## Asistentes — `POST /api/ai/generate/{assistant}` → 201 `AiDraftDto`
Crean un **borrador** en la librería del profesional con el nombre empezando por "(IA)" y sin asignarlo a nadie. El frontend abre el editor que indica `editPath`.

`AiDraftDto { assistant, refType (WORKOUT|PROGRAM|NUTRITION_PLAN|RECIPE), refId, name, editPath, creditsUsed, credits: CreditsDto, warnings[] }`
`warnings`: avisos no bloqueantes, p. ej. "Se han descartado 2 ejercicios que no están en tu librería".

### `workout`
`{ prompt (10-1000), ownExercisesOnly: boolean }`
- Catálogo enviado: ejercicios del sistema y propios (con `ownExercisesOnly`, solo los propios), como `id, nombre, grupo muscular`; como mucho 300, filtrados por relevancia con el texto.
- Esquema de salida: `{ name (≤120), notes (≤2000), items[1..30]: { exerciseId, sets 1-20, reps 1-200, weightKg 0-1000, restSeconds 0-900, notes ≤300 } }`. Los `exerciseId` que no estén en el catálogo enviado se descartan (con aviso); si no queda ninguno, 422.
- Se guarda como `WorkoutRequest` + `WorkoutItemRequest[]` (las mismas formas que `/api/workouts`). `editPath` = `/libreria/actividad-fisica/workouts?editar=<id>`.

### `program`
`{ weeks (1|2|4|6|8|12), include: { workouts, cardio, nutrition }, clientId?, prioritizeOwn: boolean, prompt (10-500), template? (WEIGHT_LOSS|HOME|HYBRID|SENIORS|VOLUME) }`
- `clientId` (propio): se envía **solo** objetivo, nivel, días disponibles y etiquetas de ese cliente, seudonimizado (ADR-003 §10).
- Catálogo: workouts propios (`id, nombre, nº de ejercicios`) si `include.workouts`. Con `prioritizeOwn`, se pide reutilizarlos antes que crear nuevos.
- Salida: `{ name, description, weeks: { week, days: { day 1-7, items: { kind (WORKOUT|NOTE), workoutId?, title ≤160 }[] }[] }[] }`. Cardio, vídeos y nutrición van como `NOTE` hasta que existan en el backend (ADR-003 §7). Coste 2 créditos.
- Se guarda como `ProgramRequest` (api-libreria-v2.md). `editPath` = `/libreria/programas/<id>`.

### `daily-plan` — 409 hasta T27
`{ kcal (1000-6000), proteinPct, carbsPct, fatPct (cada uno 5-80; suma 100), meals (3|4|5), optionsPerMeal (1-3), recipeSource (ALL|OWN|SYSTEM), allergens[] (GLUTEN, LACTOSE, EGG, FISH, SHELLFISH, NUTS, PEANUT, SOY, MILK, SESAME, MUSTARD, CELERY, SULPHITES, LUPIN, MOLLUSCS) }`
- La salida usa recetas y alimentos del catálogo (`id`) y el backend **recalcula** kcal y macros a partir de los alimentos (no se fía de las cifras del modelo). Se avisa si el total se desvía más del 10 % del objetivo.

### `recipe` — 409 hasta T27
`{ prompt (5-1000), preferences? (≤500), units (GRAMS|HOUSEHOLD), generateImages: false }` (`true` → 400 "La generación de imágenes no está disponible").
- Salida: `{ name, servings 1-12, minutes 1-600, category, instructions ≤4000, ingredients: { foodId?, text, grams }[], allergens[] }`. Los macros se recalculan con los `foodId` reconocidos; los ingredientes sin `foodId` quedan como texto para que el profesional los enlace.

## Conversaciones (panel lateral)
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/ai/suggestions?context` | — | `SuggestionDto[] { key, title, subtitle }`: CLIENT_REPORT, ACTIVITY_SUMMARY, INACTIVE_7_DAYS, WHAT_CAN_YOU_DO |
| GET | `/api/ai/conversations` | — | `ConversationDto[] { id, title, context, updatedAt }` (últimos 30 días, más recientes primero) |
| POST | `/api/ai/conversations` | `{ context? (≤60) }` | 201 `ConversationDto` |
| GET | `/api/ai/conversations/{id}` | — | `{ conversation: ConversationDto, messages: MessageDto[] }` |
| POST | `/api/ai/conversations/{id}/messages` | `{ text (1-2000), suggestion?, mentionClientIds[] (≤10, propios) }` | 201 `{ user: MessageDto, assistant: MessageDto }` |
| DELETE | `/api/ai/conversations/{id}` | — | 204 |

`MessageDto { id, role (USER|ASSISTANT), text, mentions: { clientId, name }[], createdAt }`
- `context`: pantalla desde la que se abre ("Panel principal", "Clientes"…), solo para el título y las sugerencias.
- **Datos que el backend añade, consultados por él** (el modelo no tiene herramientas, ADR-003 §9):
  - `INACTIVE_7_DAYS`: los clientes propios sin actividad en 7 días.
  - `ACTIVITY_SUMMARY`: sesiones del rango, adherencia media y pendientes.
  - `CLIENT_REPORT` o menciones `@`: cumplimiento semanal y mensual, última actividad, próximas sesiones y etiquetas del cliente mencionado.
  - Siempre seudonimizado: el backend sustituye "Cliente 1…" por los nombres reales en la respuesta.
- Nunca se envían emails, teléfonos, fecha de nacimiento ni datos de salud (peso, medidas, lesiones, notas).
- Las conversaciones y sus mensajes se borran a los 30 días.
- El texto de la respuesta es texto plano (el frontend lo pinta escapado; sin HTML).
