-- =====================================================================
-- Physical Change · V23 · Plan del cliente con opciones, recetas y alimentos sin catálogo (PostgreSQL 16) — T229
-- Contrato: physical-change-back/docs/api-nutricion-plan-cliente.md
-- Una línea es FOOD (food_id + grams, macros del catálogo) o CUSTOM (copia de nombre y macros: recetas y alimentos que no
-- están en el catálogo). option_no = número de opción de su comida (1 por defecto: las filas existentes quedan en la 1).
-- No editar una vez desplegado.
--
-- Reversión manual (se pierden las líneas CUSTOM y las opciones distintas de la 1):
--   DELETE FROM nutrition_meal WHERE food_id IS NULL OR grams IS NULL OR option_no <> 1;
--   ALTER TABLE nutrition_meal DROP CONSTRAINT ck_meal_food_or_custom;
--   ALTER TABLE nutrition_meal DROP COLUMN option_no, DROP COLUMN item_name, DROP COLUMN quantity_label,
--       DROP COLUMN kcal, DROP COLUMN protein_g, DROP COLUMN carbs_g, DROP COLUMN fat_g;
--   ALTER TABLE nutrition_meal ALTER COLUMN food_id SET NOT NULL, ALTER COLUMN grams SET NOT NULL;
--   DELETE FROM flyway_schema_history WHERE version = '23';
-- =====================================================================

ALTER TABLE nutrition_meal ADD COLUMN option_no SMALLINT NOT NULL DEFAULT 1 CHECK (option_no BETWEEN 1 AND 10);
ALTER TABLE nutrition_meal ADD COLUMN item_name VARCHAR(160);
ALTER TABLE nutrition_meal ADD COLUMN quantity_label VARCHAR(40);
ALTER TABLE nutrition_meal ADD COLUMN kcal NUMERIC(7, 1) CHECK (kcal BETWEEN 0 AND 10000);
ALTER TABLE nutrition_meal ADD COLUMN protein_g NUMERIC(6, 1) CHECK (protein_g BETWEEN 0 AND 1000);
ALTER TABLE nutrition_meal ADD COLUMN carbs_g NUMERIC(6, 1) CHECK (carbs_g BETWEEN 0 AND 1000);
ALTER TABLE nutrition_meal ADD COLUMN fat_g NUMERIC(6, 1) CHECK (fat_g BETWEEN 0 AND 1000);
ALTER TABLE nutrition_meal ALTER COLUMN food_id DROP NOT NULL;
ALTER TABLE nutrition_meal ALTER COLUMN grams DROP NOT NULL;
-- FOOD: alimento y gramos. CUSTOM: nombre y las cuatro macros copiadas.
ALTER TABLE nutrition_meal ADD CONSTRAINT ck_meal_food_or_custom CHECK (
    (food_id IS NOT NULL AND grams IS NOT NULL)
    OR (food_id IS NULL AND item_name IS NOT NULL AND kcal IS NOT NULL AND protein_g IS NOT NULL AND carbs_g IS NOT NULL AND fat_g IS NOT NULL));
