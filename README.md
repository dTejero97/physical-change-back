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
| Agenda | Tipos de sesión, eventos (sesión, reunión, recordatorio, privado; series recurrentes), reservas y cola, KPIs, cumpleaños, horarios de citas y actividad en `/api/agenda/…`. Contrato: [docs/api-agenda.md](docs/api-agenda.md) |
| Librería V2 | Programas, documentos, formularios, plantillas (copiar a mi librería) y logros en `/api/library/…`. Contrato: [docs/api-libreria-v2.md](docs/api-libreria-v2.md) |
| Archivos | `POST /api/files` (multipart, máx. 10 MB), subida directa a Cloudflare R2 `POST /api/files/uploads` + `POST /api/files/{id}/complete` (vídeos de hasta 500 MB), `DELETE /api/files/{id}`; descarga sin JWT con URL firmada `GET /api/public/files/{id}?exp&sig` (1 h; los archivos de R2 responden 302). Decisiones: `../docs/adr/ADR-001-almacenamiento-archivos.md` y `../docs/adr/ADR-005-cloudflare-r2.md` |

Migraciones de esta versión: `V3__agenda.sql`, `V4__libreria_v2.sql` (catálogo del sistema: 46 logros y 13 plantillas) y `V5__almacenamiento_r2.sql`. Cada una lleva su reversión manual en la cabecera.
Tests: `mvn test` (H2 en modo PostgreSQL). H2 no reproduce todas las diferencias con PostgreSQL: valida también arrancando contra la base local (`ddl-auto=validate`).

## Despliegue (Render)
`render.yaml` crea la base PostgreSQL y el servicio Docker. Variables a rellenar en el panel: `ADMIN_PASSWORD` y `CORS_ALLOWED_ORIGINS` (URL de Vercel). `JWT_SECRET` se genera solo.
`JWT_SECRET` también firma las URLs de descarga de archivos: si se rota, los enlaces emitidos dejan de valer (caducan en 1 h de todas formas). El disco de Render es efímero, así que los archivos nunca se guardan en el contenedor.

### Almacenamiento de archivos
| Variable | Valor | Para qué |
|---|---|---|
| `STORAGE_TYPE` | vacía o `database` (por defecto) \| `r2` | Dónde se guardan los archivos **nuevos**: PostgreSQL (ADR-001) o Cloudflare R2 (ADR-005) |
| `STORAGE_QUOTA_MB` | número, opcional | Cuota por profesional. Vacía = 50 con `database`, 2048 con `r2` |
| `R2_ACCOUNT_ID` | ID de cuenta de Cloudflare | Forma el endpoint `https://<id>.r2.cloudflarestorage.com` |
| `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` | token de R2 "Object Read & Write" (**secretos**) | Credenciales S3 |
| `R2_BUCKET` | p. ej. `physical-change-files` | Si tiene valor, se carga el cliente de R2 (aunque `STORAGE_TYPE=database`) para seguir sirviendo los archivos ya guardados allí |
| `R2_ENDPOINT` | opcional | Sustituye al endpoint por defecto (p. ej. buckets de la UE: `https://<id>.eu.r2.cloudflarestorage.com`) |

Si `R2_BUCKET` tiene valor y falta otra variable `R2_*`, el backend no arranca y dice cuál falta. Cada archivo recuerda dónde está (`stored_file.storage`), así que conviven los de PostgreSQL y los de R2. Alta en Cloudflare paso a paso: `../MANUAL-DESPLIEGUE.md`, Parte 8.
