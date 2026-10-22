# physical-change-back

API REST de Physical Change: Java 21, Spring Boot 3.3, Spring Security (JWT + BCrypt), **PostgreSQL** y **Flyway**.
Arquitectura: Controller → Service → Repository → PostgreSQL, con DTOs (records), mappers (`mapper/`), utilidades (`util/`) y Swagger.

## Desarrollo local
1. Ten PostgreSQL 14+ en marcha y crea una base (`physicalchange`).
2. Define las variables de `.env.example` (`SPRING_DATASOURCE_URL`, `JWT_SECRET`, etc.). Nunca subas `.env`.
3. `mvn spring-boot:run` → http://localhost:8080/swagger-ui.html
4. Al arrancar, Flyway crea todas las tablas (`src/main/resources/db/migration/V1__init_schema.sql`) y se crea el usuario **admin** si no existe.
5. Tests: `mvn test` (H2 en modo PostgreSQL; no necesitas base real).

Contrato detallado de la sección Clientes: [`docs/api-clientes.md`](docs/api-clientes.md).

## Base de datos
- El esquema vive en `src/main/resources/db/migration/`. Cada versión es inmutable: un cambio nuevo = `V2__descripcion.sql`.
- Hibernate está en `ddl-auto=validate`: comprueba que las entidades coinciden con las tablas.
- En Render, `DATABASE_URL` (formato `postgres://`) se convierte automáticamente a JDBC (`DatabaseUrlEnvironmentPostProcessor`).

## Usuarios y seguridad
- Roles: `ADMIN`, `TRAINER` (entrenador/nutricionista), `CLIENT`.
- Login con email o con el usuario `admin`. Por defecto `ADMIN_USER=admin` y `ADMIN_PASSWORD=admin`: **cámbialo en Render** (la contraseña solo se aplica al crear el admin; después se cambia desde Configuración).
- `SecurityConfig` aplica reglas por ruta; `AccessService` controla la propiedad de cada recurso (un cliente solo ve lo suyo; un entrenador solo a sus clientes).

## Módulos
| Módulo | Endpoints principales |
|---|---|
| Auth y perfil | `POST /api/auth/login`, `GET/PUT /api/auth/me`, `PUT /api/auth/password` |
| Clientes | `GET/POST /api/clients`, `PUT /api/clients/{id}`, ficha: `GET /api/clients/profiles`, `PUT /api/clients/{id}/profile`, `DELETE /api/clients/{id}` (borrado lógico) |
| Etiquetas | `GET/POST /api/tags`, `PUT/DELETE /api/tags/{id}`, asignación en `/api/tags/{id}/clients` |
| Invitaciones | `GET/POST /api/invitations`, `POST /api/invitations/link`, `POST /api/invitations/{id}/resend`; públicas (sin JWT): `GET /api/public/invitations/{token}`, `POST .../accept` |
| Leads | `GET/POST /api/leads`, `DELETE /api/leads/{id}`, `POST /api/leads/{id}/invite` |
| Comunidad | `/api/community/groups` (CRUD), `/api/community/groups/{id}/posts`, `POST /api/community/posts/{id}/like` |
| Ejercicios | `GET/POST /api/exercises`, `DELETE /api/exercises/{id}` |
| Rutinas | `GET/POST /api/workouts`, `GET/PUT/DELETE /api/workouts/{id}`, ítems (series, reps, peso, descanso) en `/api/workouts/{id}/items` |
| Asignación | `POST /api/workouts/assign`, `GET /api/my/assignments`, `POST /api/assignments/{id}/complete`, `GET /api/assignments/{id}/compliance` |
| Registro de series | `POST /api/exercise-logs`, `GET /api/exercise-logs/client/{id}`, `DELETE /api/exercise-logs/{id}` |
| Progreso | `GET /api/progress/strength/{clientId}` (fuerza por ejercicio y carga por músculo), `GET/POST /api/progress` (peso y grasa) |
| Calendario y citas | `GET /api/calendar?from&to`, `GET/POST /api/appointments`, `DELETE /api/appointments/{id}` |
| Pagos | `GET/POST /api/payments`, `POST /api/payments/{id}/paid`, `DELETE /api/payments/{id}` |
| Nutrición | `PUT /api/nutrition/service`, alimentos, planes y comidas en `/api/nutrition/…` |
| Informes | `GET /api/reports/trainer` |
| Mensajes | `GET /api/messages/with/{userId}`, `POST /api/messages` |

## Despliegue (Render)
`render.yaml` crea la base PostgreSQL y el servicio Docker. Variables a rellenar en el panel: `ADMIN_PASSWORD` y `CORS_ALLOWED_ORIGINS` (URL de Vercel). `JWT_SECRET` se genera solo.
