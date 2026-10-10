-- Physical Change 1.0.1 - DATOS de prueba: nutrición y notificaciones.
INSERT INTO FOOD (NAME, KCAL_100G, PROTEIN_G, CARBS_G, FAT_G) VALUES ('Pechuga de pollo', 165, 31, 0, 3.6);
INSERT INTO FOOD (NAME, KCAL_100G, PROTEIN_G, CARBS_G, FAT_G) VALUES ('Arroz integral cocido', 123, 2.7, 25.6, 1);
INSERT INTO FOOD (NAME, KCAL_100G, PROTEIN_G, CARBS_G, FAT_G) VALUES ('Aguacate', 160, 2, 8.5, 14.7);
INSERT INTO NUTRITION_PLAN (TRAINER_ID, CLIENT_ID, NAME, DAILY_KCAL, NOTES)
  VALUES ((SELECT ID FROM APP_USER WHERE EMAIL='trainer@physicalchange.test'), (SELECT ID FROM APP_USER WHERE EMAIL='cliente@physicalchange.test'), 'Definición suave', 2300, 'Proteína alta, 5 comidas');
INSERT INTO NOTIFICATION (USER_ID, TITLE, BODY) VALUES ((SELECT ID FROM APP_USER WHERE EMAIL='cliente@physicalchange.test'), 'Nuevo plan nutricional', 'Tu entrenador te ha asignado un plan.');
COMMIT;
