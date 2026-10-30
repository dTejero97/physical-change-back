# API · Asistente de bienvenida del cliente (onboarding)

- **Versión del contrato:** 1.2.0, 2026-10-30: 1.0.0 en T177, más `request-update` en T184 (1.1.0); acceso solo de responsables vigentes, registro de progreso propio, `refId` y enfriamiento de `request-update` en T196 (1.2.0).
- **Especificación:** `docs/fase6-onboarding.md`.
- **Migración:** `V14__onboarding_cliente.sql`. La de Stripe (T127) pasa a ser V15.

## Modelo: `OnboardingDto`
```json
{
  "clientId": 12,
  "birthDate": "1990-05-15", "weightKg": 78.5, "heightCm": 178, "gender": "MALE",
  "goals": ["FAT_LOSS", "HEALTH"],
  "experience": "BEGINNER", "commitment": "CONSISTENT", "techniqueLevel": 3,
  "trainingStyle": "HYPERTROPHY", "cardio": "MODERATE",
  "dietStyle": "FLEXIBLE", "mealsPerDay": 4,
  "likedExercises": ["COMPOUND", "FREE_WEIGHTS"], "avoid": ["NOTHING"],
  "healthConsent": true, "healthConsentAt": "2026-10-29T20:31:00",
  "injuries": ["KNEE"], "injuriesOther": "tobillo derecho",
  "medicalNotes": "Asma con inhalador",
  "conditions": ["HIGH_CHOLESTEROL"],
  "foodAllergy": true, "allergyDetails": "frutos secos",
  "specialDiet": "NONE", "mustKeep": "café con leche", "dislikedFoods": "kiwi, brócoli", "cooks": "MOSTLY",
  "completedAt": null,
  "updatedAt": "2026-10-29T20:31:00"
}
```
- Todos los campos de respuesta pueden ser `null`, o lista vacía en los de varias opciones: el cliente puede saltar pasos.
- **Valores permitidos:**

  | Campo | Valores / rango |
  |---|---|
  | `gender` | `MALE`, `FEMALE`, `OTHER` |
  | `goals` (varios) | `FAT_LOSS`, `MUSCLE_GAIN`, `PERFORMANCE`, `HEALTH` |
  | `experience` | `BEGINNER`, `INTERMEDIATE`, `ADVANCED` |
  | `commitment` | `MODERATE`, `CONSISTENT`, `ALL_IN` |
  | `techniqueLevel` | 1–5 |
  | `trainingStyle` | `HYPERTROPHY`, `STRENGTH`, `FAT_LOSS`, `PERFORMANCE`, `GENERAL_HEALTH` |
  | `cardio` | `MINIMAL`, `MODERATE`, `AS_NEEDED` |
  | `dietStyle` | `FLEXIBLE`, `STRICT`, `CHAOTIC` |
  | `mealsPerDay` | 1–8 |
  | `likedExercises` (varios) | `COMPOUND`, `MACHINES`, `FREE_WEIGHTS`, `FUNCTIONAL`, `UNSURE` (excluyente) |
  | `avoid` (varios) | `CARDIO`, `LEGS`, `CORE`, `NOTHING` (excluyente) |
  | `injuries` (varios) | `KNEE`, `LOWER_BACK`, `SHOULDER`, `ELBOW_WRIST`, `NECK`, `HIP`, `NONE` (excluyente) |
  | `conditions` (varios) | `HYPOTHYROIDISM`, `PCOS_INSULIN`, `CELIAC`, `DIABETES`, `HIGH_CHOLESTEROL`, `HIGH_BLOOD_PRESSURE`, `REFLUX` |
  | `specialDiet` | `NONE`, `VEGETARIAN`, `VEGAN` |
  | `cooks` | `MOSTLY`, `RARELY` |
  | `birthDate` | Edad entre 14 y 100 años |
  | `weightKg` | 20–500 |
  | `heightCm` | 50–260 |
  | Textos (máximo de caracteres) | `injuriesOther` 200, `medicalNotes` 1000, `allergyDetails` 300, `mustKeep` 500, `dislikedFoods` 500 |

- Un valor fuera de la lista, un duplicado o un excluyente combinado con otros devuelve **400**.
- **Datos de salud:** `injuries`, `injuriesOther`, `medicalNotes`, `conditions`, `foodAllergy` y `allergyDetails`.
  - Si `healthConsent` es `false` o `null`, **no se guardan**: se ignoran los que lleguen y se devuelven `null` o vacíos.
  - Pasar `healthConsent` de `true` a `false` **borra** los guardados.
  - `healthConsentAt` lo pone el servidor.

## `GET /api/me/onboarding` — CLIENT
- 200 → `OnboardingDto` del cliente del token.
- Si aún no ha guardado nada, todo va a `null`. Los datos básicos se leen de su ficha (`client_profile`).

## `PUT /api/me/onboarding` — CLIENT
- Cuerpo: `OnboardingRequest`, los mismos campos de respuesta que `OnboardingDto` **sin** `clientId`, `healthConsentAt`, `completedAt` ni `updatedAt`.
- **Guardado parcial por paso:** solo se cambian los campos **presentes en el JSON**; los que no se envían no se tocan.
  - Para vaciar un campo se envía `null`, o `[]` en las listas.
  - El servidor distingue "ausente" de `null` (lectura con `JsonNode` o `Optional` en el servicio).
- **Efectos:**
  - `birthDate`, `weightKg`, `heightCm` y `gender` se escriben en `client_profile`;
  - si llega `weightKg`, se crea o actualiza el registro de progreso del cliente para **hoy** con nota "Cuestionario inicial". Solo se actualiza el que ya tenga esa nota: los demás registros del día no se tocan (v1.2.0, T196);
  - todo en una transacción.
- Si el cuerpo trae `clientId`, se ignora: siempre se guarda el cuestionario de quien llama.
- Los guardados y el `complete` del mismo cliente se serializan: el primer PUT y el `complete` a la vez no fallan (T185).
- 200 → `OnboardingDto`. 400 por validación. 403 si no es CLIENT.

## `POST /api/me/onboarding/complete` — CLIENT
- Marca `completedAt` (solo la primera vez; repetirlo es idempotente) y avisa a sus responsables con una notificación de tipo `ONBOARDING_COMPLETED`: "{cliente} ha completado su cuestionario inicial".
- 200 → `OnboardingDto`.

## `GET /api/clients/{clientId}/onboarding` — TRAINER, ADMIN
- 200 → `OnboardingDto` (solo lectura).
- Acceso (`assertCanReadOnboarding`, v1.2.0, T196): el ADMIN, su responsable **vigente** de entrenamiento (su entrenador, si ofrece entrenamiento) o su responsable vigente de nutrición (ADR-013). El colaborador no.
- 403 a cualquier otro, también al `trainerId` que ya no le presta ningún servicio. 404 si el cliente no existe.

## `POST /api/clients/{clientId}/onboarding/request-update` — TRAINER, ADMIN (v1.1.0, T184)
El profesional pide al cliente que revise y ajuste su cuestionario (respuesta a I-08).
```json
{ "note": "¿Has cambiado de horario? Revisa tus días de entreno" }
```
- `note` es opcional, de 300 caracteres como máximo.
  - **No debe llevar datos de salud** (lesiones, enfermedades, medicación…): la nota queda en la notificación y no se borra al retirar el consentimiento (Seguridad T182/B1). El front lo avisa junto al campo.
- Mismo acceso que `GET /api/clients/{clientId}/onboarding`: el ADMIN o un responsable vigente del cliente.
- Crea una notificación al cliente de tipo `ONBOARDING_UPDATE_REQUESTED`:
  - título "Revisa tu cuestionario";
  - cuerpo "{profesional} te pide que revises tus datos. {nota}";
  - `refType` `ONBOARDING` y `refId` = id del profesional (o ADMIN) que lo pide (v1.2.0; antes era el id del cliente).
- **No cambia nada del cuestionario**: el cliente lo edita él mismo (`PUT /api/me/onboarding`).
- **Sin repetir, por profesional** (v1.2.0, T196/B5):
  - si el cliente tiene **sin leer** un aviso de este tipo **del mismo profesional**, 409 "Ya se lo has pedido; aún no lo ha visto". El aviso de otro profesional no bloquea;
  - si el mismo profesional se lo pidió hace menos de **60 minutos** (aunque ya esté leído), 429 con cabecera `Retry-After` (segundos que faltan);
  - dos peticiones a la vez del mismo profesional crean un solo aviso (T185).
- 204.

## Cambio en `GET /api/auth/me`
- `UserDto` añade `onboardingCompleted`:
  - en un CLIENT, `true` si tiene `completedAt`;
  - en el resto de roles, `null`.
- Es el dato que usa el front para redirigir a `/bienvenida`.
