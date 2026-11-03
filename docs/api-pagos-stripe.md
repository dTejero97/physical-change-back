# API Pagos con Stripe (T126, T128) — contrato

Versión 0.2.0 · 2026-11-03 · Decisión: `../docs/adr/ADR-009-stripe.md` · Migración: **V20** (`V20__pagos_stripe.sql`)

Alcance de esta versión (decisión del usuario 2026-11-03): el **cliente con sesión** (1) paga con tarjeta sus comprobantes pendientes y (2) contrata un plan de su profesional con **renovación automática**. El catálogo público y el registro durante la contratación (`access_status`, `POST /api/public/checkout`) quedan para **T143**.

Cambios respecto a 0.1.0: no hay `stripe_price_id` (cada sesión lleva `price_data` con el importe del servidor); los cupones no se aplican en el pago online (pendiente); `GET /api/me/subscription` pasa a `GET /api/me/subscriptions` (un cliente puede tener varios planes); nuevos `POST /api/me/receipts/{id}/checkout` y `GET /api/me/plans`.

## Configuración
| Variable | Valor |
|---|---|
| `PAYMENTS_PROVIDER` | `none` (por defecto: todo responde **503** "Los pagos no están activados") o `stripe` |
| `STRIPE_SECRET_KEY` | secreto, `sk_test_…` en pruebas, `sk_live_…` en real. Con `stripe` es obligatoria (y no puede ser `pk_…`) |
| `STRIPE_WEBHOOK_SECRET` | secreto `whsec_…` del endpoint de webhook. Con `stripe` es obligatoria |
| `STRIPE_API_VERSION` | `Stripe-Version` de las llamadas, por defecto `2024-06-20`. No cambia el formato de los webhooks, que sigue la versión configurada en el endpoint de Stripe |
| `PUBLIC_APP_URL` | base de `success_url`, `cancel_url` y la vuelta del portal |

## Cliente (rol CLIENT; otro rol → 403)
| Método | Ruta | Respuesta |
|---|---|---|
| POST | `/api/me/receipts/{id}/checkout` | 201 `{ orderId, checkoutUrl }`. Comprobante propio y `PENDING`; ajeno o inexistente → 404; no pendiente o total < 0,50 € → 409 |
| GET | `/api/me/plans` | `ClientPlanDto[]`: `{ id, name, description, price, taxRate, frequency, coverUrl, bonuses[], subscribed }`. Solo planes `visibility = ALL`, no archivados, de su profesional (sin entrenador: de los ADMIN) |
| POST | `/api/me/plans/{id}/checkout` | 201 `{ orderId, checkoutUrl }`. Plan no visible, archivado o de otro profesional → 404; ya activo o con impago → 409 |
| GET | `/api/me/orders` | `OrderDto[]`: `{ id, kind: RECEIPT \| PLAN, concept, amount, status: PENDING \| PAID \| FAILED \| EXPIRED \| REFUNDED, receiptId, planId, createdAt, paidAt }` |
| GET | `/api/me/orders/{id}` | `OrderDto`; ajeno → 404. Para la vuelta de Checkout: **el estado real lo pone el webhook** |
| GET | `/api/me/subscriptions` | `MySubscriptionDto[]`: `{ id, planId, planName, frequency, price, status: ACTIVE \| PAST_DUE \| CANCELLED, startDate, nextPeriodStart, cancelAtPeriodEnd, online }` (`online` = cobrada por Stripe) |
| POST | `/api/me/billing-portal` | `{ url }` del Customer Portal de Stripe (tarjeta, facturas, cancelar). Sin ningún pago previo con tarjeta → 409 |

Reglas:
- El navegador solo envía ids. El importe sale del comprobante (`total`) o del plan (`price`) guardados.
- Una sola sesión abierta por comprobante (o por cliente y plan): al pedir otra, la anterior se caduca en Stripe y su pedido pasa a `EXPIRED`.
- La sesión dura 1 hora. `success_url` = `{PUBLIC_APP_URL}/payments?pago=ok&pedido={orderId}`, `cancel_url` = `…?pago=cancelado&pedido={orderId}`. Son solo informativas.
- El cliente de Stripe (`cus_…`) se crea en el primer pago y se guarda en `app_user.stripe_customer_id`.
- Frecuencia → `recurring`: WEEKLY = week×1, MONTHLY = month×1, QUARTERLY = month×3, SEMIANNUAL = month×6, ANNUAL = year×1.

## Profesional
| Método | Ruta | Respuesta |
|---|---|---|
| GET | `/api/payments/stripe/status` (ADMIN, TRAINER, CLIENT: el cliente lo usa para mostrar "Pagar") | `{ provider: none \| stripe, mode: OFF \| TEST \| LIVE, webhookConfigured }`. Nunca devuelve claves |
| PUT | `/api/subscription-plans/{id}` | ya existente. **`visibility: ALL` ya se acepta en planes** (el cliente lo ve y lo contrata); en productos sigue dando 409 |
| POST | `/api/subscriptions/{id}/cancel` | ya existente. Si la suscripción es de Stripe, se cancela **primero en Stripe** (deja de cobrar al momento); si Stripe falla → 502 y no se cancela aquí |

Las suscripciones de Stripe **no generan comprobantes pendientes** en la puesta al día: cada periodo llega `PAID` (método `STRIPE`) por webhook.

## Webhook (público; la firma es la autenticación)
| Método | Ruta | Respuesta |
|---|---|---|
| POST | `/api/public/stripe/webhook` | 200 al aplicar el efecto (o si el evento ya se aplicó); 400 sin cuerpo si la firma falta, está caducada (> 5 min) o no coincide; 500 si falla algo y Stripe debe reintentar |

Firma: `Stripe-Signature: t=…,v1=…`; HMAC-SHA256 con el secreto sobre `t + "." + cuerpo en bruto`, comparación en tiempo constante, se acepta cualquier `v1` (rotación). Idempotencia por `event.id` en `stripe_event`, en la misma transacción que el efecto.

Eventos que hay que activar en el endpoint de Stripe:

| Evento | Efecto |
|---|---|
| `checkout.session.completed` (con `payment_status = paid`) y `checkout.session.async_payment_succeeded` | Comprueba que `amount_total` y `currency` coinciden con el pedido (si no → `FAILED`, nada se concede). RECEIPT: comprobante `PAID`, método `STRIPE` (bonos y etiquetas como al marcarlo pagado). PLAN: crea la suscripción ligada a Stripe y el comprobante `PAID` del primer periodo |
| `checkout.session.expired` / `checkout.session.async_payment_failed` | Pedido `PENDING` → `EXPIRED` / `FAILED` |
| `invoice.paid` | Renovación (se ignora `billing_reason = subscription_create`): comprobante `PAID` del periodo siguiente; `PAST_DUE` vuelve a `ACTIVE` |
| `invoice.payment_failed` | Suscripción `ACTIVE` → `PAST_DUE` |
| `customer.subscription.updated` | Sincroniza `cancelAtPeriodEnd` y el estado (`active`, `past_due`/`unpaid`, `canceled`) |
| `customer.subscription.deleted` | Suscripción → `CANCELLED` |
| `charge.refunded` (`refunded = true`) | Pedido de pago único → `REFUNDED` y su comprobante → `VOID` (cuenta como anulación). Parciales y renovaciones: a mano |

La suscripción de una factura se lee de `invoice.subscription` o, en las versiones de 2025, de `invoice.parent.subscription_details.subscription`.

Casos raros (se registran en el log con nivel WARN para revisarlos en Stripe):
- Comprobante pagado dos veces (dos pestañas o pagado a mano entretanto): los dos pedidos quedan `PAID`, el comprobante una vez; **hay que reembolsar el duplicado en Stripe**.
- Plan contratado dos veces o archivado durante el pago: se cancela la suscripción en Stripe; **hay que reembolsar el cobro**.

## Datos (V20)
`purchase_order` (cliente, profesional, `kind`, comprobante o plan, suscripción creada, importe ≥ 0,50, estado, `stripe_session_id` único, `stripe_payment_intent`, caducidad, pago) · `stripe_event` (`event_id` PK, tipo, recepción) · `app_user.stripe_customer_id` · `client_subscription.stripe_subscription_id` (único) y `cancel_at_period_end`, estado `PAST_DUE` · `receipt.method` admite `STRIPE` (solo lo pone el webhook; la API manual no lo acepta). Reversión en la cabecera de la migración.

## Pendiente
- T143: catálogo público y registro durante el pago (`access_status = PENDING_PAYMENT`).
- Cupones en el pago online.
- Reembolsos de renovaciones y reembolsos parciales: hoy se anulan a mano.
- Activar en Stripe el Customer Portal (Settings → Billing → Customer portal) antes de usar `/api/me/billing-portal` (T131/T138).
