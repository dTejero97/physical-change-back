package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.payment.PaymentException;
import com.physicalchange.payment.PaymentProvider;
import com.physicalchange.payment.PaymentProvider.CheckoutRequest;
import com.physicalchange.payment.PaymentProvider.CheckoutSession;
import com.physicalchange.payment.PaymentProvider.Mode;
import com.physicalchange.repository.*;
import com.physicalchange.security.AccessService;
import com.physicalchange.security.AuthUser;
import com.physicalchange.security.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pagos online del cliente con Stripe Checkout (ADR-009, ADR-018, docs/api-stripe-profesional.md): pagar un comprobante
 * pendiente (pago único) y contratar un plan visible de su profesional (suscripción con renovación automática).
 * Cada cobro va a la cuenta Stripe del profesional, que debe estar activa (cobro directo). Al contratar un plan se suma la
 * comisión de la plataforma sobre el precio: el cliente paga precio + comisión y la plataforma la recibe vía application_fee.
 * El navegador solo envía ids: el importe sale siempre del comprobante o del plan guardados. Aquí nada se marca como pagado:
 * eso lo hace StripeWebhookService al recibir el evento firmado.
 */
@Service
public class OnlinePaymentService {
    /** Stripe exige que la sesión dure al menos 30 minutos; la dejamos abierta 1 hora. */
    static final Duration CHECKOUT_TTL = Duration.ofHours(1);
    /** Importe mínimo que acepta Stripe en euros. */
    static final BigDecimal MIN_AMOUNT = new BigDecimal("0.50");
    /** Sesiones de Checkout por cliente cada 10 minutos (T130: cada una crea objetos en Stripe y consume su cuota de API). */
    static final int CHECKOUTS_PER_WINDOW = 10;
    static final long CHECKOUT_WINDOW_MS = 10 * 60_000L;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final RateLimiter checkoutLimiter = new RateLimiter();

    private final PaymentProvider payments;
    private final PurchaseOrderRepository orders;
    private final ReceiptRepository receipts;
    private final ReceiptLineRepository lines;
    private final SubscriptionPlanRepository plans;
    private final ClientSubscriptionRepository subscriptions;
    private final UserRepository users;
    private final TrainerStripeCustomerRepository customers;
    private final TrainerStripeService connect;
    private final CatalogService catalog;
    private final AccessService access;
    private final Clock clock;
    private final StripeReconciler reconciler;
    private final String publicUrl;

    public OnlinePaymentService(PaymentProvider pay, PurchaseOrderRepository o, ReceiptRepository r, ReceiptLineRepository l,
                                SubscriptionPlanRepository p, ClientSubscriptionRepository s, UserRepository u,
                                TrainerStripeCustomerRepository cu, TrainerStripeService ts, CatalogService c,
                                AccessService a, Clock clk, StripeReconciler rc, @Value("${app.public-url}") String publicUrl) {
        payments = pay; orders = o; receipts = r; lines = l; plans = p; subscriptions = s; users = u; customers = cu; connect = ts;
        catalog = c; access = a; clock = clk; reconciler = rc;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    public PaymentsStatusDto status() {
        return new PaymentsStatusDto(payments.enabled() ? "stripe" : "none", payments.mode(), payments.webhookConfigured());
    }

    /** Pagar con tarjeta un comprobante pendiente propio. Lo ajeno o inexistente responde 404. */
    @Transactional
    public CheckoutDto payReceipt(Long receiptId) {
        requireEnabled();
        AppUser me = lockedClient();
        Receipt x = receipts.findById(receiptId).filter(r -> r.getClientId().equals(me.getId()))
                .orElseThrow(() -> new NotFoundException("Comprobante no encontrado"));
        if (!"PENDING".equals(x.getStatus())) throw new ConflictException("Este comprobante ya no está pendiente");
        if (x.getTotal().compareTo(MIN_AMOUNT) < 0) throw new ConflictException("El importe es demasiado bajo para pagar con tarjeta");
        TrainerStripeAccount account = payable(x.getTrainerId());
        // Una sola sesión abierta por comprobante: así no se puede pagar dos veces desde dos pestañas.
        for (PurchaseOrder old : orders.findByReceiptIdAndStatus(x.getId(), "PENDING")) close(old);
        PurchaseOrder o = newOrder(me, x.getTrainerId(), "RECEIPT", x.getTotal());
        o.setReceiptId(x.getId());
        o.setStripeAccountId(account.getStripeAccountId());
        o = orders.save(o);
        String name = "PC-" + x.getId() + " · " + lines.findByReceiptIdOrderByPositionAsc(x.getId()).stream()
                .map(ReceiptLine::getName).collect(Collectors.joining(", "));
        return checkout(o, me, Mode.PAYMENT, truncate(name, 250), null, 0);
    }

    /**
     * Planes que el cliente puede contratar: visibles (ALL), no archivados y de su profesional. Cada plan dice si se puede
     * pagar ahora (payable), la comisión y el importe total que pagaría el cliente (precio + comisión).
     */
    @Transactional(readOnly = true)
    public List<ClientPlanDto> plans() {
        AppUser me = client();
        Set<Long> subscribed = subscriptions.findByClientIdOrderByCreatedAtDesc(me.getId()).stream()
                .filter(s -> !"CANCELLED".equals(s.getStatus())).map(ClientSubscription::getPlanId).collect(Collectors.toSet());
        List<SubscriptionPlan> list = owners(me).stream().flatMap(id -> plans.findByTrainerIdAndArchived(id, false).stream())
                .filter(p -> "ALL".equals(p.getVisibility()))
                .sorted(Comparator.comparing(SubscriptionPlan::getPrice).thenComparing(SubscriptionPlan::getId)).toList();
        Map<Long, Long> trainerOf = list.stream().collect(Collectors.toMap(SubscriptionPlan::getId, SubscriptionPlan::getTrainerId));
        return catalog.planDtos(list).stream().map(p -> {
            BigDecimal fee = fee(p.price(), p.platformFeePercent());
            boolean payable = connect.chargeable(trainerOf.get(p.id())).isPresent();
            return new ClientPlanDto(p.id(), p.name(), p.description(), p.price(), p.taxRate(), p.frequency(), p.coverUrl(), p.bonuses(),
                    subscribed.contains(p.id()), payable, p.platformFeePercent(), fee, p.price().add(fee));
        }).toList();
    }

    /** Contratar un plan con renovación automática. Plan no visible, archivado o de otro profesional → 404. */
    @Transactional
    public CheckoutDto subscribe(Long planId) {
        requireEnabled();
        AppUser me = lockedClient();
        SubscriptionPlan plan = plans.findById(planId)
                .filter(p -> !p.isArchived() && "ALL".equals(p.getVisibility()) && owners(me).contains(p.getTrainerId()))
                .orElseThrow(() -> new NotFoundException("Plan no encontrado"));
        if (subscriptions.existsByClientIdAndPlanIdAndStatusIn(me.getId(), plan.getId(), List.of("ACTIVE", "PAST_DUE")))
            throw new ConflictException("Ya tienes este plan activo");
        if (plan.getPrice().compareTo(MIN_AMOUNT) < 0) throw new ConflictException("El importe es demasiado bajo para pagar con tarjeta");
        TrainerStripeAccount account = payable(plan.getTrainerId());
        BigDecimal platformFee = fee(plan.getPrice(), plan.getPlatformFeePercent());
        BigDecimal total = plan.getPrice().add(platformFee);
        for (PurchaseOrder old : orders.findByClientIdAndPlanIdAndStatus(me.getId(), plan.getId(), "PENDING")) close(old);
        PurchaseOrder o = newOrder(me, plan.getTrainerId(), "PLAN", total);
        o.setPlanId(plan.getId());
        o.setStripeAccountId(account.getStripeAccountId());
        o.setPlatformFeeAmount(platformFee);
        o = orders.save(o);
        String[] interval = interval(plan.getFrequency());
        return checkout(o, me, Mode.SUBSCRIPTION, truncate(plan.getName(), 250), interval[0], Integer.parseInt(interval[1]));
    }

    @Transactional(readOnly = true)
    public List<OrderDto> myOrders() {
        AppUser me = client();
        List<PurchaseOrder> list = orders.findByClientIdOrderByCreatedAtDesc(me.getId());
        Map<Long, String> planNames = plans.findAllById(list.stream().map(PurchaseOrder::getPlanId).filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(SubscriptionPlan::getId, SubscriptionPlan::getName));
        LocalDateTime now = LocalDateTime.now(clock);
        return list.stream().map(o -> new OrderDto(o.getId(), o.getKind(),
                "PLAN".equals(o.getKind()) ? planNames.getOrDefault(o.getPlanId(), "Plan") : "Comprobante PC-" + o.getReceiptId(),
                o.getAmount(), shownStatus(o, now), o.getReceiptId(), o.getPlanId(), o.getCreatedAt(), o.getPaidAt())).toList();
    }

    /**
     * Antes de devolver un pedido propio PENDING, lo concilia con Stripe por si el webhook no ha llegado (T245).
     * Sin transacción: la llamada a Stripe no debe retener conexiones de la BD. Ajeno o inexistente: no hace nada
     * (el 404 lo da {@link #myOrder}). Un fallo de Stripe nunca llega al cliente.
     */
    public void reconcileMine(Long id) {
        AppUser me = client();
        orders.findById(id).filter(o -> o.getClientId().equals(me.getId())).ifPresent(reconciler::reconcileIfDue);
    }

    /** Un pedido propio (para la vuelta de Checkout: el estado lo pone el webhook o la conciliación). Ajeno → 404. */
    @Transactional(readOnly = true)
    public OrderDto myOrder(Long id) {
        return myOrders().stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow(() -> new NotFoundException("Pedido no encontrado"));
    }

    @Transactional(readOnly = true)
    public List<MySubscriptionDto> mySubscriptions() {
        AppUser me = client();
        List<ClientSubscription> list = subscriptions.findByClientIdOrderByCreatedAtDesc(me.getId());
        Map<Long, SubscriptionPlan> byId = plans.findAllById(list.stream().map(ClientSubscription::getPlanId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(SubscriptionPlan::getId, p -> p));
        return list.stream().map(s -> {
            SubscriptionPlan p = byId.get(s.getPlanId());
            return new MySubscriptionDto(s.getId(), s.getPlanId(), p == null ? "—" : p.getName(), p == null ? null : p.getFrequency(),
                    s.getPrice(), s.getStatus(), s.getStartDate(), s.getNextPeriodStart(), s.isCancelAtPeriodEnd(), s.getStripeSubscriptionId() != null);
        }).toList();
    }

    /**
     * Portal de cliente de Stripe (tarjeta, facturas, cancelar). Usa el cliente de Stripe que tiene en la cuenta de su
     * profesional; si solo pagó antes de ADR-018 (cuenta de la plataforma), usa ese. Sin ningún pago con tarjeta → 409.
     */
    @Transactional(readOnly = true)
    public PortalDto billingPortal() {
        requireEnabled();
        AppUser me = client();
        Optional<TrainerStripeCustomer> own = customers.findByClientId(me.getId()).stream().findFirst();
        if (own.isPresent())
            return new PortalDto(payments.createPortalSession(own.get().getStripeCustomerId(), publicUrl + "/payments", own.get().getStripeAccountId()));
        if (me.getStripeCustomerId() == null) throw new ConflictException("Todavía no has pagado con tarjeta");
        return new PortalDto(payments.createPortalSession(me.getStripeCustomerId(), publicUrl + "/payments", null));
    }

    /**
     * T130: si el profesional marca pagado o anula un comprobante con una sesión de Checkout abierta, se cierra en Stripe
     * para que el cliente no lo pague dos veces. También salta al cobrarlo por Stripe: cierra las demás sesiones abiertas.
     * Va en la transacción que cambia el comprobante.
     */
    @EventListener
    public void onReceiptPaid(ReceiptEvents.ReceiptPaidEvent ev) { closeOpenOrders(ev.receiptId()); }

    @EventListener
    public void onReceiptVoided(ReceiptEvents.ReceiptVoidedEvent ev) { closeOpenOrders(ev.receiptId()); }

    private void closeOpenOrders(Long receiptId) {
        for (PurchaseOrder o : orders.findByReceiptIdAndStatus(receiptId, "PENDING")) {
            if (payments.enabled()) close(o);
            else o.setStatus("EXPIRED");
        }
    }

    // ---------- internos ----------

    private void requireEnabled() { if (!payments.enabled()) throw PaymentException.disabled(); }

    /** Cuenta del profesional lista para cobrar; si no la tiene, el cliente no puede pagar (ADR-018, punto 4). */
    private TrainerStripeAccount payable(Long trainerId) {
        return connect.chargeable(trainerId).orElseThrow(() ->
                new ConflictException("Este profesional aún no acepta pagos online; paga por otro medio"));
    }

    private AppUser client() {
        AuthUser a = access.current();
        if (a.role() != Role.CLIENT) throw new ForbiddenException("Solo para clientes");
        return users.findById(a.id()).orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
    }

    /**
     * Cliente con su fila bloqueada hasta el final de la transacción (T130): dos peticiones de pago simultáneas del mismo
     * cliente se atienden de una en una, así la segunda ve y cierra la sesión de la primera y no se crean dos clientes de Stripe.
     */
    private AppUser lockedClient() {
        AuthUser a = access.current();
        if (a.role() != Role.CLIENT) throw new ForbiddenException("Solo para clientes");
        long wait = checkoutLimiter.hit("checkout:" + a.id(), CHECKOUTS_PER_WINDOW, CHECKOUT_WINDOW_MS);
        if (wait > 0) throw new TooManyRequestsException("Demasiados intentos de pago. Inténtalo dentro de unos minutos", wait);
        return users.lockById(a.id()).orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
    }

    /** Un pedido pendiente cuya sesión ya ha caducado se muestra caducado aunque el evento de Stripe no haya llegado. */
    private static String shownStatus(PurchaseOrder o, LocalDateTime now) {
        return "PENDING".equals(o.getStatus()) && o.getExpiresAt().isBefore(now) ? "EXPIRED" : o.getStatus();
    }

    /** Profesional del cliente; sin entrenador, los ADMIN (misma regla que ClientDirectory.canBookWith). */
    private Set<Long> owners(AppUser me) {
        if (me.getTrainerId() != null) return Set.of(me.getTrainerId());
        return users.findByRole(Role.ADMIN).stream().map(AppUser::getId).collect(Collectors.toSet());
    }

    private PurchaseOrder newOrder(AppUser me, Long trainerId, String kind, BigDecimal amount) {
        PurchaseOrder o = new PurchaseOrder();
        o.setClientId(me.getId());
        o.setTrainerId(trainerId);
        o.setKind(kind);
        o.setAmount(amount);
        o.setExpiresAt(LocalDateTime.now(clock).plus(CHECKOUT_TTL));
        return o;
    }

    /** Crea la sesión en la cuenta del profesional del pedido. El importe y la comisión ya están fijados en el pedido. */
    private CheckoutDto checkout(PurchaseOrder o, AppUser me, Mode mode, String name, String interval, int count) {
        String customer = customer(me, o.getTrainerId(), o.getStripeAccountId());
        CheckoutSession s = payments.createCheckout(new CheckoutRequest(mode, customer, o.getStripeAccountId(), o.getId(), name,
                cents(o.getAmount()), cents(o.getPlatformFeeAmount()), interval, count,
                publicUrl + "/payments?pago=ok&pedido=" + o.getId(), publicUrl + "/payments?pago=cancelado&pedido=" + o.getId(),
                clock.instant().plus(CHECKOUT_TTL), "checkout-order-" + o.getId()));
        o.setStripeSessionId(s.id());
        return new CheckoutDto(o.getId(), s.url());
    }

    /**
     * Cliente de Stripe del cliente en la cuenta de su profesional (uno por par, ADR-018). Se crea en el primer pago y se
     * reutiliza; si el profesional ha vinculado otra cuenta, se crea de nuevo en esa cuenta.
     */
    private String customer(AppUser me, Long trainerId, String accountId) {
        Optional<TrainerStripeCustomer> row = customers.findByClientIdAndTrainerId(me.getId(), trainerId);
        if (row.isPresent() && row.get().getStripeAccountId().equals(accountId)) return row.get().getStripeCustomerId();
        String id = payments.createCustomer(me.getId(), me.getEmail(), me.getFullName(), accountId);
        if (row.isPresent()) row.get().moveTo(accountId, id);
        else customers.save(new TrainerStripeCustomer(me.getId(), trainerId, accountId, id));
        return id;
    }

    /** Cierra en Stripe una sesión anterior todavía abierta (en la cuenta donde se creó) y la marca caducada. */
    private void close(PurchaseOrder old) {
        if (old.getStripeSessionId() != null) payments.expireCheckout(old.getStripeSessionId(), old.getStripeAccountId());
        old.setStatus("EXPIRED");
    }

    /** Comisión de la plataforma: precio × % del plan, con dos decimales (ADR-018, punto 3). */
    static BigDecimal fee(BigDecimal price, BigDecimal percent) {
        if (percent == null || percent.signum() == 0) return BigDecimal.ZERO.setScale(2);
        return price.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    static long cents(BigDecimal euros) { return euros.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact(); }

    /** Frecuencia del plan → recurring[interval] y recurring[interval_count] de Stripe. */
    static String[] interval(String frequency) {
        return switch (frequency) {
            case "WEEKLY" -> new String[] {"week", "1"};
            case "QUARTERLY" -> new String[] {"month", "3"};
            case "SEMIANNUAL" -> new String[] {"month", "6"};
            case "ANNUAL" -> new String[] {"year", "1"};
            default -> new String[] {"month", "1"};
        };
    }

    private static String truncate(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 1) + "…"; }
}
