# Base de datos Oracle

- `schema/<versión>/` y `data/<versión>/`: DDL y datos separados. **Cada versión es inmutable**: un cambio nuevo = carpeta de versión nueva.
- `install/install_X.sql`: instala la versión X completa desde cero.
- `upgrade/upgrade_A_to_B.sql`: actualiza de A a B.
- Ejecutar siempre desde la carpeta `database/` (rutas relativas `@schema/...`) con SQLcl o SQL*Plus.
- Los scripts no contienen contraseñas: los usuarios de prueba llevan `PENDING_PASSWORD` y el backend los hashea con BCrypt si defines `SEED_PASSWORD`.
- Hibernate está en `ddl-auto=validate`: nunca crea ni modifica tablas.
