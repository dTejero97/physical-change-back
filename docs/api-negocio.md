# Contrato API · Sección Negocio (v1.5.0)

Comprobantes (facturación manual), productos, planes de suscripción, cupones, bonos, panel de Datos de negocio, informes y pagos de los servicios de la plataforma.
Decisiones: `docs/adr/ADR-004-cobros-facturacion.md` (raíz del proyecto). Pantallas: `docs/fase5-pantallas-v3.md` (N1–N5).
Migración: `src/main/resources/db/migration/V6__negocio.sql`. Swagger: grupo "Negocio".

Reglas comunes
- JWT obligatorio. TRAINER y ADMIN gestionan lo suyo (`trainerId` = usuario autenticado; un profesional nunca ve lo de otro: 403/404). CLIENT solo `GET /api/payments` y `GET /api/me/bonuses`.
- **Solo ADMIN**: `/api/platform/**` y `/api/receipts/movements` (403 para TRAINER).
- Errores `{ timestamp, status, error, message }`: 400 validación, 403 sin permiso, 404 no existe, 409 conflicto (transición de estado no permitida, cupón agotado, producto con ventas).
- Importes `number` con 2 decimales, siempre en euros e **impuestos incluidos**. `taxRate` ∈ {21, 10, 4, 0} (solo informativo).
- Fechas `yyyy-MM-dd`; instantes `yyyy-MM-ddTHH:mm:ss`. Rangos `from`/`to` incluidos; como máximo 366 días (400 si no).
- Listados paginados: `?page=0&size=10` (`size` 1-100) → `Page<T> { content, totalElements, totalPages, number, size }`. Orden `?sort=campo,asc|desc`.
- `cover`: igual que en Agenda (`brand`, `gym`, `mat`, `weights`, `yoga`, `run` o `file:<id>`).
- Enumerados: `method` CASH | TRANSFER | BIZUM | PAYPAL | CARD | OTHER · `status` (comprobante) PENDING | PAID | VOID · `lineKind` PRODUCT | PLAN | FREE · `frequency` WEEKLY | MONTHLY | QUARTERLY | SEMIANNUAL | ANNUAL · `origin` MANUAL | SUBSCRIPTION | LEGACY.

## Comprobantes — `/api/receipts`
Antes de responder cualquier `GET` de comprobantes o KPIs se ejecuta la **puesta al día de suscripciones** del profesional (ADR-004 §4).

| Método | Ruta | Cuerpo / parámetros | Respuesta |
|---|---|---|---|
| GET | `/api/receipts` | `from`, `to`, `q` (cliente, producto o importe), `clientIds`, `lineKinds`, `statuses`, `methods`, `minAmount`, `maxAmount`, paginación, `sort` (`issueDate` por defecto desc, `total`, `clientName`, `status`) | `Page<ReceiptDto>` |
| GET | `/api/receipts/{id}` | — | `ReceiptDto` (lo usa el justificante imprimible) |
| POST | `/api/receipts/preview` | `ReceiptRequest` | `ReceiptDto` con `id = null` y totales calculados. **No guarda** |
| POST | `/api/receipts` | `ReceiptRequest` | 201 `ReceiptDto` |
| POST | `/api/receipts/{id}/pay` | `{ paidDate?, method? }` (por defecto hoy y el método del comprobante) | `ReceiptDto` · 409 si no está PENDING. Concede los bonos |
| POST | `/api/receipts/{id}/void` | `{ reason (1-200) }` | `ReceiptDto` · 409 si ya está VOID. Cancela los bonos no usados |
| GET | `/api/receipts/summary?from&to` | — | `ReceiptSummaryDto { collected, pending, overdue, countPaid, countPending }` (tarjetas Cobrado / Pendiente de cobrar) |
| GET | `/api/receipts/export?format=xlsx\|csv` + filtros de la lista | — | archivo (`Content-Disposition: attachment`) |
| GET | `/api/receipts/movements` **(ADMIN)** | `from`, `to`, `q`, `types` (INCOME, EXPENSE), `minAmount`, `maxAmount` (−5000…5000), paginación | `Page<MovementDto>` |

No existen `PUT` ni `DELETE`: un comprobante no se modifica (ADR-004 §1).

**Implementación (T67):** `ReceiptController` / `ReceiptService`. Un comprobante ajeno responde **404**. Sin `from`/`to`, el rango es el último año hasta hoy. `summary`: `collected` suma los PAID por `paidDate` del rango; `pending` y `overdue`, los PENDING por `issueDate`. Exportación: columnas Referencia, Fecha, Vencimiento, Cliente, Conceptos, Tipo, Método, Estado, Fecha de pago, Subtotal, Descuento y Total (`util/Spreadsheet`). Los cupones `AMOUNT` guardan `discountPct = null` y el importe en `discountAmount`; si no se envía `discountName`, se usa el código del cupón.
Bonos (T68): `BonusService` escucha `ReceiptEvents.ReceiptPaidEvent` / `ReceiptVoidedEvent`. La puesta al día de suscripciones se ejecuta antes de `GET /api/receipts`, `/summary` y `/export`, y al arrancar la aplicación.

**Implementación (T68):** `CatalogService` (productos y planes), `CouponService`, `SubscriptionService`, `BonusService`.
- Un producto o plan ajeno responde 404. `DELETE` de un plan también lo archiva si tiene suscripciones (aunque no tenga ventas).
- `sales` = unidades en comprobantes no anulados. Etiquetas automáticas: al pagar el comprobante (productos y planes) y al empezar una suscripción.
- Suscripción con cupón: el uso se gasta una vez al suscribir y el descuento se aplica a todos los periodos. El cupón no puede ser de `PRODUCTS`.
- Comprobante de periodo: emisión y vencimiento = inicio del periodo, método `OTHER`, una línea `PLAN` con el precio guardado en la suscripción.
- Canje de bono: al pasar una reserva a un estado que ocupa plaza (alta con clientes, reservar, cambio de estado o salida de la cola). Se devuelve al dejar de ocupar plaza o al borrar la sesión, solo si aún no ha empezado.

`ReceiptRequest { clientId, issueDate, dueDate?, notes? (≤500), method, status (PENDING|PAID), paidDate?, lines[1..20], couponId?, discountName? (≤60), discountPct? (0-100) }`
- `lines[]`: `{ kind, refId?, name (1-120), unitPrice (0.01-100000), quantity (1-999), taxRate }`. En PRODUCT/PLAN, `refId` es obligatorio y `name`, `unitPrice` y `taxRate` se toman del catálogo si vienen vacíos.
- `couponId` y `discountPct` son excluyentes. El cupón tiene que estar activo, sin caducar, con usos libres y aplicable a los tipos de línea (409 si no). Su uso se descuenta al guardar.
- `status = PAID` exige `paidDate` (por defecto `issueDate`) y concede los bonos al guardar.

`ReceiptDto { id, reference ("PC-<id>"), clientId, clientName, issueDate, dueDate, paidDate, notes, method, status, overdue, origin, subscriptionId, periodStart, periodEnd, lines: ReceiptLineDto[], subtotal, discountName, discountPct, discountAmount, total, voidReason, voidedAt, createdAt, trainerName }`
`ReceiptLineDto { kind, refId, name, unitPrice, quantity, taxRate, lineTotal }` · `overdue` = PENDING y `dueDate` < hoy.
`MovementDto { date, type (INCOME|EXPENSE), concept, amount (positivo en ingresos, negativo en gastos), description, sourceType (RECEIPT|PLATFORM_PAYMENT), sourceId }`.

## Compatibilidad — `/api/payments` (sin cambios para el cliente)
Mismo DTO `PaymentDto` y mismas rutas, ahora sobre `receipt` (ADR-004 §2): `GET` (CLIENT: los suyos; profesional: los suyos), `POST` crea un comprobante PENDING con una línea FREE (`concept`, `amount`) y `dueDate`, `POST /{id}/paid` = `pay`, `DELETE /{id}` = `void` del pendiente (409 si está pagado, como hasta ahora).

## Productos de pago único — `/api/products`
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/products?q&archived=false` + paginación, `sort` (name, price, createdAt) | — | `Page<ProductDto>` |
| GET | `/api/products/{id}` | — | `ProductDto` |
| POST | `/api/products` | `ProductRequest` | 201 `ProductDto` |
| PUT | `/api/products/{id}` | `ProductRequest` | `ProductDto` (los comprobantes ya emitidos no cambian) |
| DELETE | `/api/products/{id}` | — | 204 si no tiene ventas; si tiene, se **archiva** (200 `ProductDto` con `archived = true`) |

`ProductRequest { name (1-120), description (1-1000), price (0.01-100000), taxRate, visibility (HIDDEN; ALL → 409 "Disponible con la tienda de la app del cliente"), cover, bonuses: BonusDefinitionRequest[0..5], autoTagIds[] }`
`BonusDefinitionRequest { sessionTypeId (propio), sessions (1-100), validDays (1-730) }`
`ProductDto { id, name, description, price, taxRate, visibility, cover, coverUrl, bonuses: BonusDefinitionDto[], autoTagIds, automated (= autoTagIds no vacío), hasBonus, sales, archived, createdAt }`
`BonusDefinitionDto { id, sessionTypeId, sessionTypeName, color, sessions, validDays }`

## Planes de suscripción — `/api/subscription-plans`
Mismas rutas y reglas que productos. `SubscriptionPlanRequest` = `ProductRequest` + `frequency`. `SubscriptionPlanDto` = `ProductDto` + `frequency`, `activeSubscriptions`.

| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/subscriptions?clientId&status` | — | `SubscriptionDto[]` |
| POST | `/api/subscriptions` | `{ clientId, planId, startDate, couponId? }` | 201 `SubscriptionDto`. Emite ya el primer comprobante PENDING si `startDate` ≤ hoy · 409 si el cliente ya tiene ese plan activo |
| POST | `/api/subscriptions/{id}/cancel` | — | `SubscriptionDto` (no se emiten más periodos; los emitidos se mantienen) |

`SubscriptionDto { id, clientId, clientName, planId, planName, frequency, price, startDate, nextPeriodStart, status (ACTIVE|CANCELLED), cancelledAt }`
- Enlace de invitación con plan de pago: `POST /api/invitations` acepta `planId?` (ver api-clientes.md). Al aceptarse, se crea la suscripción con `startDate` = día del alta.

## Cupones — `/api/coupons`
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/coupons?q` + paginación, `sort` (code, uses, expiresAt, active) | — | `Page<CouponDto>` |
| POST | `/api/coupons` | `CouponRequest` | 201 · 409 código repetido |
| PUT | `/api/coupons/{id}` | `CouponRequest` | `CouponDto` (no se puede bajar `maxUses` por debajo de `uses`) |
| DELETE | `/api/coupons/{id}` | — | 204 si no se ha usado; si se ha usado, 409 (desactívalo) |

`CouponRequest { code (3-30, A-Z 0-9 - _ , se guarda en mayúsculas), description? (≤300), type (PERCENT|AMOUNT), value (PERCENT 1-100; AMOUNT 0.01-100000), appliesTo (ALL|PRODUCTS|PLANS), maxUses? (1-100000), expiresAt? (fecha), active }`
`CouponDto { id, code, description, type, value, appliesTo, maxUses, uses, expiresAt, active, status (ACTIVE|EXPIRED|EXHAUSTED|INACTIVE), createdAt }`

## Bonos
| Método | Ruta | Quién | Respuesta |
|---|---|---|---|
| GET | `/api/clients/{id}/bonuses` | dueño | `ClientBonusDto[]` |
| GET | `/api/me/bonuses` | CLIENT | `ClientBonusDto[]` vigentes |

`ClientBonusDto { id, sessionTypeId, sessionTypeName, color, total, used, remaining, expiresAt, status (ACTIVE|EXPIRED|USED|CANCELLED), receiptId, productName }`
- Canje automático al crear una reserva `BOOKED` en `/api/agenda/events/{id}/bookings` (ADR-004 §5). `BookingDto` añade `bonusId?`. Cancelar antes del inicio devuelve la sesión.
- `GET /api/agenda/stats` → `vouchersRedeemed` = canjes en el rango (deja de ser 0 fijo).

## Datos de negocio — `/api/business`
Parámetros comunes `from`, `to` (por defecto los últimos 30 días). Cada métrica lleva `available: boolean`; si es `false`, el frontend muestra **"Sin datos"** y nunca un 0.

| Método | Ruta | Respuesta |
|---|---|---|
| GET | `/api/business/finance` | `FinanceDto` |
| GET | `/api/business/finance/day?date` | `ReceiptDto[]` del día (modal al pulsar una barra) |
| GET | `/api/business/finance/by-item?kinds=PRODUCT,PLAN&q&page&size&sort` | `Page<ItemIncomeDto { kind, refId, name, units, income }>` (las líneas FREE se agrupan por nombre) |
| GET | `/api/business/finance/by-item/{kind}/{refId}` | `ItemSalesDto { name, sales, income, buyers: { clientId, clientName, amount, receiptId, date }[] }` |
| GET | `/api/business/clients` | `ClientsDto` |
| GET | `/api/business/clients/day?date&series=TOTAL\|NEW\|ARCHIVED` | `{ date, total, clients: { clientId, name, state (NEW|REGULAR|ARCHIVED|DELETED) }[] }` |
| GET | `/api/business/clients/states/{state}` | `{ clientId, name, email, detail }[]` para cada subestado |
| GET | `/api/business/services` | `ServicesDto` |

`FinanceDto`
- `kpis { totalSold, subscriptionSales, productSales, receiptsCreated, voidedAmount }` (se cuentan los comprobantes PAID por `paidDate`; `voidedAmount` = suma de los VOID que estaban pagados).
- Solo ADMIN: `platformCost` (pagos de servicios del rango) y `result` (`totalSold − platformCost`); `null` para TRAINER.
- `subscriptionsChart { total, days: { date, newSales, renewals, voided }[] }`: nueva = primer periodo de una suscripción; renovación = periodos siguientes; anulaciones en negativo.
- `receiptsChart { total, days: { date, amount }[] }` (todos los orígenes) · `receiptStatus { paidAmount, paidPct, pendingAmount, pendingPct }`.

`ClientsDto`
- `kpis { totalClients, newClients, regularClients, avgTenureDays }` (habitual = activo con más de 90 días; la permanencia media es de los clientes activos).
- `evolution { series, days: { date, value }[] }` con `series` = TOTAL (activos ese día), NEW o ARCHIVED.
- `states` (hoy): `contacted { leads, notRegistered }` · `waiting { notStarted (aceptó y no tiene actividad), withoutPlanning (sin workout ni plan nutricional asignado) }` · `active { withSubscription, withoutSubscription }` · `atRisk { lowCompliance (cumplimiento mensual < 50 %), inactive (sin actividad en 7 días) }` · `finished { archived }`.

`ServicesDto`
- `kpis { assignedActivities, avgCompliance, sessionAttendancePct, unbookedSeatsPct }`.
- `complianceByType: { type (SESSIONS|WORKOUTS|CARDIO|VIDEOS|NUTRITION), pct, available }[]`.
- `ratings { available: false, average: null, byType: [] }`.

**Disponibilidad hoy** (cálculo en el servidor; lo que no esté aquí sale con `available: true`):

| Métrica | Hoy | Motivo |
|---|---|---|
| Cumplimiento de SESSIONS | sí | `agenda_booking` (asistidas / reservadas) |
| Cumplimiento de WORKOUTS | sí | `workout_assignment` + `exercise_log` (mismo cálculo que la ficha del cliente) |
| Cumplimiento de CARDIO, VIDEOS, NUTRITION | **sin datos** | No hay registro de "completado" (llegará con la app del cliente y T27) |
| `avgCompliance` | parcial | Media solo de los tipos disponibles |
| Valoraciones (`ratings`) | **sin datos** | Las da el cliente desde su app |
| Rendimiento de mi equipo | solo el profesional | Equipo real en T28 |

**Implementación (T69):** `BusinessController`, `BusinessService`, `BusinessReportService`. Diferencias con lo de arriba que el frontend debe tener en cuenta (T76):
- `ServicesDto.kpis.avgCompliance`, `sessionAttendancePct` y `unbookedSeatsPct` son `null` cuando no hay datos (sin asistencias pasadas, sin asignaciones o sin sesiones con plazas), igual que `complianceByType[].pct`.
- `ClientsDto.evolution` lleva `available`; la serie `ARCHIVED` sale con `available: false` y `/clients/day?series=ARCHIVED` da 400, porque no se guarda la fecha de archivo.
- Concepto libre en el detalle por producto: `GET /api/business/finance/by-item/FREE/0?name=<nombre>`.
- `/finance/day` devuelve los comprobantes emitidos **o** cobrados ese día.
- `/clients/states/{state}`: `leads` y `notRegistered` devuelven `clientId: null` (aún no son clientes).
- Finanzas: un comprobante con líneas de plan y de producto se reparte entre "suscripciones" y "productos" por el peso de sus líneas, con el descuento prorrateado. "Nueva" = comprobante manual con plan o primer periodo de la suscripción; "Renovación" = periodos siguientes. Las anulaciones van al día en que se anularon.
- "Habitual" = activo con más de 90 días de alta; "En riesgo" = cumplimiento de workouts < 50 % en los últimos 30 días o última actividad (series o workouts completados) hace más de 7 días.

## Informes — `GET /api/business/reports/{report}?from&to&format=xlsx|csv`
`report`: `billing` (comprobantes con líneas) · `client-compliance` (por cliente: sesiones reservadas/asistidas, workouts asignados/hechos, bonos canjeados) · `team-performance` (por profesional: clientes activos, actividades asignadas, cumplimiento medio; hoy una fila) · `incomplete-signups` (invitaciones no aceptadas y leads: nombre, email, teléfono, origen, fecha, etiqueta).
- 400 "Las fechas son obligatorias" si falta `from` o `to`. Archivo `physical-change-<report>-<from>-<to>.<ext>`.
- CSV: UTF-8 con BOM, separador `;`, coma decimal. XLSX: una hoja. Toda celda de texto que empiece por `= + - @`, tabulador o retorno de carro lleva `'` delante (ADR-004 §7).

## Servicios de la plataforma (solo ADMIN) — `/api/platform`
| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/platform/services` | — | `PlatformServicesDto { services: PlatformServiceDto[], monthlyTotal, yearlyEstimate }` |
| POST | `/api/platform/services` | `PlatformServiceRequest` | 201 |
| PUT | `/api/platform/services/{id}` | `PlatformServiceRequest` | `PlatformServiceDto` |
| DELETE | `/api/platform/services/{id}` | — | 204 si no tiene pagos; si tiene, 409 (desactívalo con `active = false`) |
| GET | `/api/platform/payments?from&to&serviceId&status` + paginación | — | `Page<PlatformPaymentDto>` |
| POST | `/api/platform/payments` | `PlatformPaymentRequest` | 201 |
| PUT | `/api/platform/payments/{id}` | `PlatformPaymentRequest` | `PlatformPaymentDto` |
| DELETE | `/api/platform/payments/{id}` | — | 204 (borra también el justificante) |

`PlatformServiceRequest { name (1-60), purpose (≤200), plan (1-60), monthlyCost (0-100000), renewalDate?, dashboardUrl? (https://, ≤300), freeLimits? (≤1000), active }`
`PlatformServiceDto { id, name, purpose, plan, monthlyCost, renewalDate, dashboardUrl, freeLimits, active, paymentsCount, lastPaymentDate }` · Datos iniciales: Render, Vercel y Cloudflare, plan "Gratuito", 0 €.
`PlatformPaymentRequest { serviceId, date, concept (1-120), amount (0.01-100000), status (PAID|PENDING), fileId? (archivo DOCUMENT propio subido con `/api/files`), notes? (≤500) }`
`PlatformPaymentDto { id, serviceId, serviceName, date, concept, amount, status, fileId, fileName, fileUrl, notes, createdAt }`
