# physical-change-back

API REST de Physical Change: Java 21, Spring Boot 3, Spring Security (JWT + BCrypt), Oracle.
Arquitectura: Controller → Service → Repository → Oracle. DTOs, validación, paginación, Swagger y Actuator.

## Desarrollo
1. Crea el esquema Oracle: `cd database && sql user/pass@db @install/install_1.2.0.sql`
2. Exporta las variables de `.env.example` (nunca subas `.env`). Define `SEED_PASSWORD` para activar los usuarios de prueba.
3. `mvn spring-boot:run` → http://localhost:8080/swagger-ui.html
4. Tests: `mvn test` (H2 en modo Oracle, sin necesidad de Oracle real).

## Seguridad
- JWT stateless; roles ADMIN / TRAINER / CLIENT.
- `SecurityConfig`: reglas por ruta + `@PreAuthorize`. `AccessService`: propiedad de recursos (un cliente solo ve lo suyo; un entrenador solo sus clientes).

## Producción (Render)
Despliegue Docker con `render.yaml`. Define `ORACLE_URL`, `ORACLE_USER`, `ORACLE_PASSWORD`, `JWT_SECRET`, `CORS_ALLOWED_ORIGINS` (URL de Vercel) en el panel de Render.

## Estado de módulos
Implementados en API: auth, clientes, ejercicios, entrenamientos y asignaciones, seguimiento, progreso, citas/reservas, mensajes, dashboards y **nutrición** (servicio opcional del entrenador: alimentos, planes, comidas y macros).
Esquema SQL ya creado (falta API): programas semanales, notificaciones, pagos, facturas, logros.
Pendientes: fotos, documentos/formularios, comunidad, suscripciones, estadísticas de negocio, automatizaciones, equipos/centros, IA.
