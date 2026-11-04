# API Plan de nutrición del cliente: opciones, recetas y alimentos sin catálogo (T229) — contrato

Versión 1.0.0 · 2026-11-04 · Defecto del usuario (2026-11-03): al asignar un plan de *Librería → Nutrición → Planes nutricionales*, el cliente tiene que ver **toda** su información: macros objetivo y comidas, con **todas** sus opciones (decisión N-01). Migración **V23**.

## Qué cambia
Una línea de comida del plan del cliente (`nutrition_meal`) puede ser:
- **FOOD**: un alimento del catálogo (`foodId` + `grams`). Las macros se calculan con el catálogo, como hasta ahora.
- **CUSTOM**: una receta o un alimento que no está en el catálogo (p. ej. los de demostración). Se guarda **una copia** de su nombre, la cantidad en texto (`quantityLabel`, p. ej. "2 raciones" o "150 g") y sus macros ya calculadas. Si la receta de la Librería cambia después, el plan del cliente no cambia (igual que los comprobantes).

Cada línea pertenece a una **opción** de su comida (`optionNo`, 1–10; por defecto 1).

## `POST /api/nutrition/plans/{planId}/meals` (ADMIN o responsable de nutrición)
| Campo | FOOD | CUSTOM |
|---|---|---|
| `mealName` (≤ 60) | obligatorio | obligatorio |
| `optionNo` (1–10) | opcional, 1 | opcional, 1 |
| `foodId` | obligatorio | ausente |
| `grams` (1–5000) | obligatorio | opcional |
| `name` (≤ 160) | — | obligatorio |
| `quantityLabel` (≤ 40) | — | opcional |
| `kcal` (0–10000), `proteinG`, `carbsG`, `fatG` (0–1000) | — | obligatorios |

Errores: FOOD sin `grams` o CUSTOM sin nombre o sin alguna macro → 400. `foodId` inexistente → 404.

## `MealDto` (campos nuevos, aditivos)
`{ id, mealName, foodId, foodName, grams, kcal, proteinG, carbsG, fatG, optionNo, quantityLabel, custom }`
- `foodName` es el nombre del alimento o, en CUSTOM, el nombre guardado. `foodId` y `grams` son null en CUSTOM sin gramos.

## Totales del plan
`totals` suma **solo la opción 1** de cada comida: es lo que come el cliente en un día con las opciones por defecto (las demás son alternativas, sumarlas contaría la comida varias veces). Cada opción muestra su propio subtotal en la app.

## Macros objetivo
`POST /api/nutrition/plans` ya admitía `targetProteinG`, `targetCarbsG` y `targetFatG` (gramos). Al asignar desde la Librería, el frontend las calcula con las kcal y los porcentajes del plan: proteínas y carbohidratos = kcal × % / 4; grasas = kcal × % / 9.

## Solicitudes de cambio
"Cambiar un alimento" sobre una línea CUSTOM la convierte en FOOD (se borran la copia y la etiqueta).
