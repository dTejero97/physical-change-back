# API Stripe por profesional (T278) — contrato

Versión 0.1.0 · 2026-11-06 (borrador para implementar en T280) · Decisión: `../../docs/adr/ADR-018-stripe-por-profesional.md` · Amplía: `api-pagos-stripe.md` 0.4.0 (ADR-009) · Migración: **V29** (`V29__stripe_por_profesional.sql`, T279)

Alcance: cada profesional (TRAINER) vincula su **propia cuenta de Stripe** (Connect Standard) desde Negocio → Pagos online. Los pagos de sus clientes van a esa cuenta. Sin vincular, el profesional no cobra online. La plataforma no guarda claves de profesionales: solo el id de cuenta `acct_…` (que no se devuelve nunca en las respuestas).

## Configuración (nuevas)
| Variable | Valor |
|---|---|
| `STRIPE_CONNECT_CLIENT_ID` | `ca_…` de la plataforma (Connect). Obligatoria para vincular cuentas **Standard** (por defecto). Con Connect activado, las **Express** no la necesitan |
| `STRIPE_CONNECT_WEBHOOK_SECRET` | secreto `whsec_…` del endpoint de cuentas conectadas. Obligatoria con `stripe` |
| `PUBLIC_APP_URL` | base de la vuelta desde Stripe (ya existe) |

`STRIPE_SECRET_KEY` y `STRIPE_WEBHOOK_SECRET` no cambian (ADR-009).

## Estados de la cuenta del profesional
| `status` | Significado | ¿Puede cobrar online? |
|---|---|---|
| `NOT_LINKED` | Sin cuenta vinculada | No |
| `PENDING_DETAILS` | Vinculada, Stripe pide datos (`requirements_due` no vacío o `details_submitted = false`) | No |
| `ACTIVE` | `charges_enabled = true` | **Sí** |
| `RESTRICTED` | Vinculada pero Stripe la ha restringido (`charges_enabled = false` tras haber estado activa, o `requirements` vencidos) | No |

`payoutsEnabled` se muestra, pero solo `charges_enabled` decide si se puede cobrar.

## Profesional (rol TRAINER o ADMIN; otro rol → 403)
| Método | Ruta | Respuesta |
|---|---|---|
| GET | `/api/payments/stripe/account` | 200 `StripeAccountDto` (ver abajo). Sin cuenta → `status: NOT_LINKED` (200, no 404) |
| POST | `/api/payments/stripe/account/link` | Body opcional `{ "accountType": "STANDARD" \| "EXPRESS" }` (por defecto `STANDARD`). 201 `{ url }`: `STANDARD` → enlace de OAuth de Connect; `EXPRESS` → la plataforma crea la cuenta Express y devuelve su enlace de alta (Account Link). En ambos casos se guarda un `state` de un solo uso con caducidad de 10 min. Si ya hay una cuenta vinculada → 409 `STRIPE_ACCOUNT_ALREADY_LINKED` (desvincular antes). Si Connect no está configurado para Standard → 503 |
| POST | `/api/payments/stripe/account/refresh` | 200 `StripeAccountDto`. Vuelve a leer el estado en Stripe (para "Completar datos" y tras volver de Stripe). Límite: 10 por hora por profesional → 429 |
| DELETE | `/api/payments/stripe/account` | 204. Revoca la autorización en Stripe y borra el vínculo. Con suscripciones `ACTIVE` o `PAST_DUE` cobradas en su cuenta → **409** `STRIPE_HAS_ACTIVE_SUBSCRIPTIONS` (cancelar antes). Stripe no responde → 502 y no se desvincula aquí |
| GET | `/api/payments/stripe/status` | ya existe (ADR-009). Para TRAINER añade `account: { status, chargesEnabled }`; para ADMIN, igual que hoy. Nunca devuelve claves ni ids de cuenta |

### `StripeAccountDto`
```json
{
  "accountType": "STANDARD",
  "status": "ACTIVE",
  "chargesEnabled": true,
  "payoutsEnabled": true,
  "detailsSubmitted": true,
  "requirementsDue": [],
  "linkedAt": "2026-11-06T10:00:00"
}
```
- `accountType`: `STANDARD` (por defecto) o `EXPRESS`, elegido por el profesional al vincular. Con `NOT_LINKED` no hay tipo: se devuelve `null`.
- `requirementsDue`: lista de textos o códigos que Stripe pide completar (p. ej. `individual.verification.document`). Se muestran tal cual en la pantalla con traducción en el frontend.
- No incluye `stripe_account_id` ni comisiones: la comisión es de cada plan (ver "Comisión").

## Vuelta desde Stripe (público; el `state` es la autenticación)
| Método | Ruta | Respuesta |
|---|---|---|
| GET | `/api/public/stripe/connect/callback?code=…&state=…` | **302** a `{PUBLIC_APP_URL}/negocio/pagos-online?stripe=ok` o `?stripe=error&motivo=…`. Nunca devuelve JSON |

Reglas del callback:
- `state` inexistente, usado o caducado → `stripe=error&motivo=state`, sin llamar a Stripe.
- `code` ausente o rechazado por Stripe → `motivo=oauth`.
- Éxito: se guarda el `acct_…`, se lee la cuenta en Stripe y se rellena `status`. Un `state` solo sirve una vez (se marca como usado en la misma transacción).
- Si ese `acct_…` ya está vinculado a otro profesional → `motivo=cuenta_en_uso` y no se cambia nada.
- El `state` se liga al profesional que inició el enlace; si la sesión cambia, el callback no vincula.

## Cliente (rol CLIENT)
Las rutas de V20 no cambian. Cambian sus respuestas:

| Método | Ruta | Cambio |
|---|---|---|
| GET | `/api/me/plans` | Cada plan añade `payable: boolean` (`true` solo si el profesional está `ACTIVE`), `platformFeePercent`, `feeAmount` (precio × % , 2 decimales) y `totalPrice` (`price + feeAmount`, lo que paga el cliente). Sin `payable` → la app no muestra "Pagar" y muestra el aviso |
| POST | `/api/me/plans/{id}/checkout` | Si el profesional no está `ACTIVE` → **409** `PAYMENTS_NOT_AVAILABLE` (antes, con la cuenta de la plataforma, no había este caso) |
| POST | `/api/me/receipts/{id}/checkout` | Igual: 409 `PAYMENTS_NOT_AVAILABLE` si el profesional del comprobante no está `ACTIVE` |
| POST | `/api/me/billing-portal` | Usa la cuenta y el cliente de Stripe **del profesional de la suscripción** (`trainer_stripe_customer`). Si no hay pagos con tarjeta con ese profesional → 409 |

Reglas de cobro (todas nuevas o reforzadas):
- La sesión de Checkout se crea **con la cabecera `Stripe-Account` = cuenta del profesional dueño del plan o del comprobante** (`purchase_order.trainer_id`). Nunca la de otro profesional.
- Se guarda `stripe_account_id` en el pedido (`purchase_order`) y en la suscripción (`client_subscription`). NULL = legado de la plataforma.
- El cliente de Stripe se busca o crea por `(cliente, profesional)` en `trainer_stripe_customer`.
- **Contratar un plan:** el importe cobrado es `price + feeAmount` (`feeAmount = round(price × platform_fee_percent / 100, 2)`); la comisión se envía como `application_fee` (`feeAmount`) y el profesional recibe `price`. Con comisión 0 no se envía `application_fee`.
- **Pagar un comprobante:** importe = `total` del comprobante, **sin comisión** (supuesto de ADR-018).
- Al contratar, la comisión del plan se copia en `client_subscription.platform_fee_percent`; las renovaciones usan esa copia.
- Límite de sesiones por cliente (10 cada 10 min) se mantiene.

## Webhook de cuentas conectadas (público; la firma es la autenticación)
| Método | Ruta | Respuesta |
|---|---|---|
| POST | `/api/public/stripe/connect-webhook` | 200 al aplicar o si ya se aplicó; 400 si la firma falla (con `STRIPE_CONNECT_WEBHOOK_SECRET`); 413 si pasa de 1 MB; 500 si falla y Stripe debe reintentar |

Eventos a activar en el endpoint de cuentas conectadas:

| Evento | Efecto |
|---|---|
| `account.updated` | Actualiza `status`, `charges_enabled`, `payouts_enabled`, `details_submitted`, `requirements_due` de la cuenta `account` |
| `account.application.deauthorized` | Borra el vínculo del profesional (`unlinked_at`). Las suscripciones activas no se cancelan en silencio: se marcan para revisión (`PAST_DUE`) y se avisa al profesional |
| `checkout.session.completed`, `invoice.paid`, `invoice.payment_failed`, `customer.subscription.deleted`, `charge.refunded` | Los mismos efectos que en ADR-009, pero buscando el pedido o la suscripción por `stripe_account_id = account` |

Reglas: firma y tolerancia de 5 min como en V20; idempotencia por `event.id` en `stripe_event` (columna `account_id` nueva); un evento de una cuenta no vinculada se ignora sin efecto; el `livemode` debe coincidir con la clave.

## Errores nuevos
| Código | HTTP | Cuándo |
|---|---|---|
| `PAYMENTS_NOT_AVAILABLE` | 409 | Profesional sin cuenta `ACTIVE` |
| `STRIPE_HAS_ACTIVE_SUBSCRIPTIONS` | 409 | Desvincular con suscripciones activas |
| `STRIPE_ACCOUNT_IN_USE` | 409 | `acct_…` ya vinculado a otro profesional (en el callback se responde con redirección, no con JSON) |
| `STRIPE_CONNECT_NOT_CONFIGURED` | 503 | Falta `STRIPE_CONNECT_CLIENT_ID` para una cuenta Standard |
| `STRIPE_ACCOUNT_ALREADY_LINKED` | 409 | Vincular con una cuenta ya vinculada (desvincular antes) |

Los errores existentes de V20 (503 con `PAYMENTS_PROVIDER=none`, 429, 502 si Stripe falla) se mantienen.

## Comisión (por plan)
- `subscription_plan.platform_fee_percent` (0–100, dos decimales), por defecto 0. Lo fija el profesional dueño del plan al crearlo o editarlo (`PUT /api/subscription-plans/{id}`, campo nuevo `platformFeePercent`; el ADMIN también puede).
- El cliente paga **precio + comisión**. Ejemplo: plan de 50 € con 21 % → cliente paga 60,50 €; profesional recibe 50 €; plataforma 10,50 €.
- La comisión no se aplica a comprobantes (supuesto de ADR-018) ni se muestra al profesional como ingreso propio: en su panel verá el precio del plan y el cobro neto que le llega de Stripe.
- Cambiar la comisión de un plan no afecta a las suscripciones ya contratadas (se usa la copia de `client_subscription`).
- Las comisiones de Stripe (procesamiento) las paga el profesional; no se incluyen en el cálculo.
- **Flujo del dinero:** todo el importe cobrado entra en la cuenta Stripe del profesional, incluidos los impuestos (el precio ya los incluye; no se suman). La comisión se envía como `application_fee` y Stripe la descuenta de esa cuenta.
- **Reembolsos (supuesto de ADR-018):** al devolver un pedido de plan se devuelve también la comisión (`refund_application_fee = true`).
- IVA sobre la comisión: pendiente de confirmar con el asesor (ADR-018).
- Dónde se configura: formulario de plan (`/negocio/productos/planes`), campo "Comisión de la plataforma (%)" junto a "Impuestos incluidos", con la previsión de importes.

## Auditoría (ADR-016)
Se registran: vincular (con tipo de cuenta), desvincular, cambio de estado de la cuenta (`account.updated` que cambia `status`) y cambio de comisión de un plan (valor anterior y nuevo). Nunca se registra el `acct_…` completo: solo los últimos 4 caracteres.

## Privacidad y seguridad
- Ninguna respuesta, log ni evento de analítica (PostHog) contiene `acct_…`, `cus_…` ni claves.
- El cliente nunca envía `trainerId` ni `stripeAccountId`: el servidor los deriva del plan o del comprobante.
- Un cliente no puede pagar a la cuenta de otro profesional (IDOR): el checkout busca el plan o el comprobante por id **y** `trainer_id` del cliente.

## Implementación (T280, rama de trabajo del backend)
Implementado y probado con la suite completa (`mvn test`: 278 pruebas, 0 fallos; 23 de ellas en `StripePaymentFlowTest`, 5 nuevas para ADR-018).

Decisiones de implementación:
- **Cuenta por cobro:** cada sesión, suscripción, cliente y portal se crea con la cabecera `Stripe-Account` de la cuenta del profesional. Los pedidos y suscripciones anteriores (`stripe_account_id` NULL) siguen en la cuenta de la plataforma y se gestionan con la clave sin cabecera.
- **Cliente de Stripe por profesional:** `trainer_stripe_customer` guarda también la cuenta. Si el profesional vincula otra, el cliente se crea de nuevo en esa cuenta. El portal usa el cliente del profesional y, si no existe, el de la plataforma (pagos anteriores).
- **Comisión:** `application_fee` (pago único) y `subscription_data[application_fee_percent]` (suscripciones, con el porcentaje efectivo redondeado a dos decimales; la diferencia máxima es de 0,01 € por periodo). La renovación se compara con precio + comisión, no con el precio solo.
- **Webhook de Connect:** `POST /api/public/stripe/connect-webhook` con `STRIPE_CONNECT_WEBHOOK_SECRET`. Atiende `account.updated` y `account.application.deauthorized`; los demás eventos se aplican igual que en la plataforma (los ids de sesión y suscripción son únicos).
- **Express:** al empezar el alta se crea la cuenta en Stripe y se guarda en `stripe_oauth_state`. Si el alta no se completa y el profesional vuelve a empezar, se crea otra cuenta: la anterior queda sin datos en Stripe y sin vincular aquí.
- **Límite de comprobaciones:** `POST …/account/refresh` admite 10 por hora y profesional (429 si se supera).

Pendiente antes de activar en producción (no implementado en T280):
- **Auditoría de la comisión:** el cambio de `platformFeePercent` de un plan no se registra todavía en el registro de auditoría.
- **Reembolsos:** la plataforma no crea reembolsos. Al devolver un pago de plan en el panel de Stripe hay que marcar «reembolsar también la comisión»; el webhook solo anula el pedido.
- **Verificación con Stripe:** nombres de OAuth (`connect.stripe.com/oauth/authorize|token|deauthorize`), `account_links`, `application_fee_percent` en suscripciones y eventos de cuenta: pendientes de comprobar contra la documentación vigente y con cuentas de prueba (T282).
- **Configuración de producción:** `STRIPE_CONNECT_CLIENT_ID`, `STRIPE_CONNECT_WEBHOOK_SECRET`, `PUBLIC_API_URL` y el `redirect_uri` de Connect (`{PUBLIC_API_URL}/api/public/stripe/connect/callback`) registrados en Stripe (T283).
- **Cambio de comportamiento:** en cuanto se despliegue, los clientes de un profesional sin cuenta vinculada dejan de poder pagar online. Hay que vincular antes las cuentas de los profesionales que cobran (incluido el ADMIN).

## Pendiente de verificar (al implementar T280)
- Nombre exacto del parámetro de OAuth de Connect Standard y de la URL de autorización.
- Creación de cuentas Express por API y Account Link de alta (parámetros y tipo de enlace).
- Forma exacta de la comisión en suscripciones de Stripe (línea extra de precio o `application_fee_percent`) y que `application_fee` en Checkout funciona con `payment` y con `subscription`.
- Campos y valores de `requirements` en `account.updated`.
- Evento y campos de `account.application.deauthorized`.
- Versión de la API de Stripe que se fija en `STRIPE_API_VERSION`.
