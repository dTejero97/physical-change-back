package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Suscripciones sin pasarela (ADR-004 §4): asignar un plan a un cliente y, en la puesta al día, un comprobante PENDING
 * por cada periodo vencido y no emitido (idempotente). El cupón, si lo hay, se gasta una vez al suscribir y se aplica a
 * todos los periodos. Cancelar no anula lo ya emitido.
 */
@Service
public class SubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    /** Tope de periodos emitidos de una vez por suscripción (un año de semanas y algo más). */
    private static final int MAX_PERIODS = 60;

    private final ClientSubscriptionRepository subscriptions;
    private final SubscriptionPlanRepository plans;
    private final CouponRepository coupons;
    private final ReceiptRepository receipts;
    private final ReceiptLineRepository lines;
    private final UserRepository users;
    private final ClientDirectory directory;
    private final ClientProfileService profiles;
    private final AccessService access;

    public SubscriptionService(ClientSubscriptionRepository s, SubscriptionPlanRepository p, CouponRepository c, ReceiptRepository r,
                               ReceiptLineRepository l, UserRepository u, ClientDirectory d, ClientProfileService pr, AccessService a) {
        subscriptions = s; plans = p; coupons = c; receipts = r; lines = l; users = u; directory = d; profiles = pr; access = a;
    }

    @Transactional(readOnly = true)
    public List<SubscriptionDto> list(Long clientId, String status) {
        Long me = access.current().id();
        List<ClientSubscription> list = subscriptions.findByTrainerIdOrderByCreatedAtDesc(me).stream()
                .filter(s -> clientId == null || s.getClientId().equals(clientId))
                .filter(s -> status == null || s.getStatus().equals(status)).toList();
        return toDtos(list);
    }

    @Transactional
    public SubscriptionDto create(SubscriptionRequest r) {
        AuthUser me = access.current();
        AppUser client = users.findById(r.clientId()).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
        if (!directory.isClientOf(client, me.id())) throw new ForbiddenException("No es tu cliente");
        SubscriptionPlan plan = plans.findById(r.planId()).filter(p -> p.getTrainerId().equals(me.id()))
                .orElseThrow(() -> new NotFoundException("Plan no encontrado"));
        Long couponId = null;
        if (r.couponId() != null) {
            Coupon c = coupons.findById(r.couponId()).filter(k -> k.getTrainerId().equals(me.id()))
                    .orElseThrow(() -> new NotFoundException("Cupón no encontrado"));
            if (!"ACTIVE".equals(CouponService.status(c)) || "PRODUCTS".equals(c.getAppliesTo()))
                throw new ConflictException("El cupón no se puede aplicar a este plan");
            if (coupons.consume(c.getId()) == 0) throw new ConflictException("El cupón está agotado");
            couponId = c.getId();
        }
        ClientSubscription s = start(me.id(), client.getId(), plan, r.startDate(), couponId);
        catchUp(s);
        return toDtos(List.of(s)).get(0);
    }

    @Transactional
    public SubscriptionDto cancel(Long id) {
        ClientSubscription s = subscriptions.findById(id).filter(x -> x.getTrainerId().equals(access.current().id()))
                .orElseThrow(() -> new NotFoundException("Suscripción no encontrada"));
        if (!"ACTIVE".equals(s.getStatus())) throw new ConflictException("La suscripción ya está cancelada");
        s.setStatus("CANCELLED");
        s.setCancelledAt(LocalDateTime.now());
        return toDtos(List.of(s)).get(0);
    }

    /** Alta desde un enlace de invitación con "Plan de pago" (sin sesión: el dueño es el de la invitación). */
    @Transactional
    public void startFromInvitation(Long trainerId, Long clientId, Long planId) {
        plans.findById(planId).filter(p -> p.getTrainerId().equals(trainerId) && !p.isArchived()).ifPresent(plan -> {
            ClientSubscription s = start(trainerId, clientId, plan, LocalDate.now(), null);
            catchUp(s);
        });
    }

    /** Puesta al día del profesional autenticado (antes de listar comprobantes o KPIs). */
    @Transactional
    public void catchUpMine() {
        for (ClientSubscription s : subscriptions.findByTrainerIdAndStatusAndNextPeriodStartLessThanEqual(access.current().id(), "ACTIVE", LocalDate.now())) {
            catchUp(s);
        }
    }

    /** Puesta al día de todos al arrancar la aplicación. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void catchUpAll() {
        int n = 0;
        for (ClientSubscription s : subscriptions.findAll()) {
            if ("ACTIVE".equals(s.getStatus()) && !s.getNextPeriodStart().isAfter(LocalDate.now())) n += catchUp(s);
        }
        if (n > 0) log.info("Suscripciones: {} comprobantes pendientes emitidos al arrancar", n);
    }

    // ---------- internos ----------

    private ClientSubscription start(Long trainerId, Long clientId, SubscriptionPlan plan, LocalDate startDate, Long couponId) {
        if (plan.isArchived()) throw new ConflictException("El plan está archivado");
        if (subscriptions.existsByClientIdAndPlanIdAndStatus(clientId, plan.getId(), "ACTIVE"))
            throw new ConflictException("El cliente ya tiene este plan activo");
        ClientSubscription s = new ClientSubscription();
        s.setTrainerId(trainerId);
        s.setClientId(clientId);
        s.setPlanId(plan.getId());
        s.setPrice(plan.getPrice());
        s.setCouponId(couponId);
        s.setStartDate(startDate);
        s.setNextPeriodStart(startDate);
        s = subscriptions.save(s);
        // Etiquetas automáticas del plan: al empezar (ADR-004 §3).
        if (!plan.getAutoTagIds().isEmpty()) profiles.ensure(clientId).getTagIds().addAll(plan.getAutoTagIds());
        return s;
    }

    /** Emite los periodos vencidos. Devuelve cuántos comprobantes ha creado. */
    private int catchUp(ClientSubscription s) {
        SubscriptionPlan plan = plans.findById(s.getPlanId()).orElse(null);
        if (plan == null) return 0;
        Coupon coupon = s.getCouponId() == null ? null : coupons.findById(s.getCouponId()).orElse(null);
        LocalDate today = LocalDate.now();
        int created = 0;
        while ("ACTIVE".equals(s.getStatus()) && !s.getNextPeriodStart().isAfter(today) && created < MAX_PERIODS) {
            LocalDate start = s.getNextPeriodStart(), next = advance(start, plan.getFrequency());
            if (!receipts.existsBySubscriptionIdAndPeriodStart(s.getId(), start)) {
                issue(s, plan, coupon, start, next.minusDays(1));
                created++;
            }
            s.setNextPeriodStart(next);
        }
        return created;
    }

    private void issue(ClientSubscription s, SubscriptionPlan plan, Coupon coupon, LocalDate start, LocalDate end) {
        BigDecimal price = s.getPrice();
        BigDecimal discount = BigDecimal.ZERO;
        Receipt x = new Receipt();
        if (coupon != null) {
            x.setCouponId(coupon.getId());
            x.setDiscountName(coupon.getCode());
            if ("PERCENT".equals(coupon.getType())) {
                x.setDiscountPct(coupon.getValue());
                discount = price.multiply(coupon.getValue()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            } else {
                discount = coupon.getValue().min(price);
            }
        }
        x.setTrainerId(s.getTrainerId());
        x.setClientId(s.getClientId());
        x.setIssueDate(start);
        x.setDueDate(start);
        x.setMethod("OTHER");
        x.setStatus("PENDING");
        x.setOrigin("SUBSCRIPTION");
        x.setSubscriptionId(s.getId());
        x.setPeriodStart(start);
        x.setPeriodEnd(end);
        x.setSubtotal(price);
        x.setDiscountAmount(discount);
        x.setTotal(price.subtract(discount).max(BigDecimal.ZERO));
        x = receipts.save(x);
        ReceiptLine l = new ReceiptLine();
        l.setReceiptId(x.getId());
        l.setPosition((short) 1);
        l.setKind("PLAN");
        l.setPlanId(plan.getId());
        l.setName(plan.getName());
        l.setUnitPrice(price);
        l.setQuantity((short) 1);
        l.setTaxRate(plan.getTaxRate());
        l.setLineTotal(price);
        lines.save(l);
    }

    static LocalDate advance(LocalDate d, String frequency) {
        return switch (frequency) {
            case "WEEKLY" -> d.plusWeeks(1);
            case "QUARTERLY" -> d.plusMonths(3);
            case "SEMIANNUAL" -> d.plusMonths(6);
            case "ANNUAL" -> d.plusYears(1);
            default -> d.plusMonths(1);
        };
    }

    private List<SubscriptionDto> toDtos(List<ClientSubscription> list) {
        Map<Long, SubscriptionPlan> planById = plans.findAllById(list.stream().map(ClientSubscription::getPlanId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(SubscriptionPlan::getId, p -> p));
        Map<Long, String> names = users.findAllById(list.stream().map(ClientSubscription::getClientId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
        return list.stream().map(s -> {
            SubscriptionPlan p = planById.get(s.getPlanId());
            return new SubscriptionDto(s.getId(), s.getClientId(), names.getOrDefault(s.getClientId(), "—"), s.getPlanId(),
                    p == null ? "—" : p.getName(), p == null ? null : p.getFrequency(), s.getPrice(), s.getStartDate(), s.getNextPeriodStart(),
                    s.getStatus(), s.getCancelledAt());
        }).toList();
    }
}
