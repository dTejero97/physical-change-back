# API · Asistente de bienvenida del cliente (onboarding)

- **Versión del contrato:** 1.1.0, 2026-10-29: 1.0.0 en T177, más `request-update` en T184.
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
  - si llega `weightKg`, se crea o actualiza el registro de progreso del cliente para **hoy**, con nota "Cuestionario inicial";
  - todo en una transacción.
- 200 → `OnboardingDto`. 400 por validación. 403 si no es CLIENT.

## `POST /api/me/onboarding/complete` — CLIENT
- Marca `completedAt` (solo la primera vez; repetirlo es idempotente) y avisa a sus responsables con una notificación de tipo `ONBOARDING_COMPLETED`: "{cliente} ha completado su cuestionario inicial".
- 200 → `OnboardingDto`.

## `GET /api/clients/{clientId}/onboarding` — TRAINER, ADMIN
- 200 → `OnboardingDto` (solo lectura).
- Acceso: el ADMIN; su responsable de entrenamiento (`assertCanAccessClient`); o su responsable de nutrición (`assertCanReadNutrition`, ADR-013). El colaborador no.
- 403 a cualquier otro.

## `POST /api/clients/{clientId}/onboarding/request-update` — TRAINER, ADMIN (v1.1.0, T184)
El profesional pide al cliente que revise y ajuste su cuestionario (respuesta a I-08).
```json
{ "note": "Cuéntame si sigue la molestia de rodilla" }
```
- `note` es opcional, de 300 caracteres como máximo.
- Mismo acceso que `GET /api/clients/{clientId}/onboarding`: el ADMIN o un responsable del cliente.
- Crea una notificación al cliente de tipo `ONBOARDING_UPDATE_REQUESTED`:
  - título "Revisa tu cuestionario";
  - cuerpo "{profesional} te pide que revises tus datos. {nota}";
  - `refType` `ONBOARDING`.
- **No cambia nada del cuestionario**: el cliente lo edita él mismo (`PUT /api/me/onboarding`).
- **Sin repetir:** si el cliente tiene una notificación de este tipo **sin leer**, responde 409 "Ya se lo has pedido; aún no lo ha visto".
- 204.

## Cambio en `GET /api/auth/me`
- `UserDto` añade `onboardingCompleted`:
  - en un CLIENT, `true` si tiene `completedAt`;
  - en el resto de roles, `null`.
- Es el dato que usa el front para redirigir a `/bienvenida`.
