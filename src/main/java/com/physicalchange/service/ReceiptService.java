package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.service.ReceiptEvents.*;
import com.physicalchange.util.Spreadsheet;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Comprobantes de pago (facturación manual, ADR-004 §1). Inmutables: solo se pagan o se anulan.
 * Cada profesional (TRAINER o ADMIN) ve y gestiona solo los suyos; lo ajeno responde 404.
 * Bonos y puesta al día de suscripciones: T68 (escucha ReceiptEvents y amplía list/summary).
 */
@Service
public class ReceiptService {
    private static final Set<Integer> TAX_RATES = Set.of(0, 4, 10, 21);
    private static final int MAX_RANGE_DAYS = 366;

    /** Filtros de GET /api/receipts y de la exportación. Listas vacías = sin filtro. */
    public record ReceiptFilter(LocalDate from, LocalDate to, String q, List<Long> clientIds, List<String> lineKinds,
                                List<String> statuses, List<String> methods, BigDecimal minAmount, BigDecimal maxAmount) {}
    public record ExportFile(byte[] content, String filename, String contentType) {}

    private final ReceiptRepository receipts;
    private final ReceiptLineRepository lines;
    private final ProductRepository products;
    private final SubscriptionPlanRepository plans;
    private final CouponRepository coupons;
    private final PlatformPaymentRepository platformPayments;
    private final PlatformServiceEntryRepository platformServices;
    private final UserRepository users;
    private final ClientDirectory directory;
    private final AccessService access;
    private final ApplicationEventPublisher events;

    public ReceiptService(ReceiptRepository r, ReceiptLineRepository l, ProductRepository p, SubscriptionPlanRepository sp,
                          CouponRepository c, PlatformPaymentRepository pp, PlatformServiceEntryRepository ps, UserRepository u,
                          ClientDirectory d, AccessService a, ApplicationEventPublisher e) {
        receipts = r; lines = l; products = p; plans = sp; coupons = c; platformPayments = pp; platformServices = ps; users = u;
        directory = d; access = a; events = e;
    }

    // ---------- Consultas ----------

    @Transactional(readOnly = true)
    public Page<ReceiptDto> list(ReceiptFilter f, Pageable p) {
        List<ReceiptDto> all = filtered(f);
        Comparator<ReceiptDto> order = comparator(p.getSort());
        all.sort(order);
        int from = (int) Math.min(p.getOffset(), all.size());
        int to = Math.min(from + p.getPageSize(), all.size());
        return new PageImpl<>(all.subList(from, to), p, all.size());
    }

    @Transactional(readOnly = true)
    public ReceiptDto get(Long id) { return toDtos(List.of(owned(id))).get(0); }

    @Transactional(readOnly = true)
    public ReceiptSummaryDto summary(LocalDate from, LocalDate to) {
        LocalDate[] r = range(from, to);
        Long me = access.current().id();
        LocalDate today = LocalDate.now();
        BigDecimal collected = BigDecimal.ZERO, pending = BigDecimal.ZERO, overdue = BigDecimal.ZERO;
        long countPaid = 0, countPending = 0;
        for (Receipt x : receipts.findByTrainerIdAndStatusAndPaidDateBetween(me, "PAID", r[0], r[1])) {
            collected = collected.add(x.getTotal()); countPaid++;
        }
        for (Receipt x : receipts.findByTrainerIdAndIssueDateBetween(me, r[0], r[1])) {
            if (!"PENDING".equals(x.getStatus())) continue;
            pending = pending.add(x.getTotal()); countPending++;
            if (x.getDueDate().isBefore(today)) overdue = overdue.add(x.getTotal());
        }
        return new ReceiptSummaryDto(collected, pending, overdue, countPaid, countPending);
    }

    @Transactional(readOnly = true)
    public ExportFile export(ReceiptFilter f, String format) {
        if (!"xlsx".equalsIgnoreCase(format) && !"csv".equalsIgnoreCase(format)) throw new BadRequestException("Formato no válido: xlsx o csv");
        boolean xlsx = "xlsx".equalsIgnoreCase(format);
        List<ReceiptDto> all = filtered(f);
        all.sort(comparator(Sort.unsorted()));
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Referencia", "Fecha", "Vencimiento", "Cliente", "Conceptos", "Tipo", "Método", "Estado", "Fecha de pago",
                "Subtotal (€)", "Descuento (€)", "Total (€)"));
        for (ReceiptDto d : all) {
            rows.add(Arrays.asList(d.reference(), d.issueDate(), d.dueDate(), d.clientName(),
                    d.lines().stream().map(ReceiptLineDto::name).collect(Collectors.joining(", ")),
                    d.lines().stream().map(ReceiptLineDto::kind).distinct().map(ReceiptService::kindLabel).collect(Collectors.joining(", ")),
                    methodLabel(d.method()), statusLabel(d.status()), d.paidDate(), d.subtotal(), d.discountAmount(), d.total()));
        }
        LocalDate[] r = range(f.from(), f.to());
        String name = "physical-change-comprobantes-" + r[0] + "-" + r[1] + (xlsx ? ".xlsx" : ".csv");
        return xlsx
                ? new ExportFile(Spreadsheet.xlsx("Comprobantes", rows), name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                : new ExportFile(Spreadsheet.csv(rows), name, "text/csv; charset=UTF-8");
    }

    /** Movimientos (solo ADMIN): comprobantes cobrados (ingresos) y pagos de servicios pagados (gastos). */
    @Transactional(readOnly = true)
    public Page<MovementDto> movements(LocalDate from, LocalDate to, String q, List<String> types, BigDecimal min, BigDecimal max, Pageable p) {
        AuthUser me = access.current();
        if (me.role() != Role.ADMIN) throw new ForbiddenException("Solo el administrador ve los movimientos");
        LocalDate[] r = range(from, to);
        List<MovementDto> all = new ArrayList<>();
        Set<String> t = types == null ? Set.of() : new HashSet<>(types);
        if (t.isEmpty() || t.contains("INCOME")) {
            for (ReceiptDto d : toDtos(receipts.findByTrainerIdAndStatusAndPaidDateBetween(me.id(), "PAID", r[0], r[1]))) {
                all.add(new MovementDto(d.paidDate(), "INCOME", d.clientName(), d.total(),
                        d.reference() + " · " + d.lines().stream().map(ReceiptLineDto::name).collect(Collectors.joining(", ")), "RECEIPT", d.id()));
            }
        }
        if (t.isEmpty() || t.contains("EXPENSE")) {
            Map<Long, String> names = platformServices.findAll().stream()
                    .collect(Collectors.toMap(PlatformServiceEntry::getId, PlatformServiceEntry::getName));
            for (PlatformPayment x : platformPayments.findByStatusAndPaymentDateBetween("PAID", r[0], r[1])) {
                all.add(new MovementDto(x.getPaymentDate(), "EXPENSE", names.getOrDefault(x.getServiceId(), "—"), x.getAmount().negate(),
                        x.getConcept(), "PLATFORM_PAYMENT", x.getId()));
            }
        }
        String norm = norm(q);
        List<MovementDto> list = all.stream()
                .filter(m -> norm.isEmpty() || norm(m.concept()).contains(norm) || norm(m.description()).contains(norm)
                        || m.amount().abs().toPlainString().contains(norm.replace(',', '.')))
                .filter(m -> min == null || m.amount().compareTo(min) >= 0)
                .filter(m -> max == null || m.amount().compareTo(max) <= 0)
                .sorted(Comparator.comparing(MovementDto::date).reversed().thenComparing(MovementDto::sourceId, Comparator.reverseOrder()))
                .toList();
        int a = (int) Math.min(p.getOffset(), list.size()), b = Math.min(a + p.getPageSize(), list.size());
        return new PageImpl<>(list.subList(a, b), p, list.size());
    }

    // ---------- Alta y transiciones ----------

    @Transactional(readOnly = true)
    public ReceiptDto preview(ReceiptRequest r) {
        Draft d = draft(r);
        return toDto(d.receipt, d.lines, nameOf(d.receipt.getClientId()), nameOf(d.receipt.getTrainerId()), LocalDate.now());
    }

    @Transactional
    public ReceiptDto create(ReceiptRequest r) {
        Draft d = draft(r);
        if (d.receipt.getCouponId() != null && coupons.consume(d.receipt.getCouponId()) == 0) {
            throw new ConflictException("El cupón está agotado");
        }
        Receipt saved = receipts.save(d.receipt);
        short pos = 1;
        for (ReceiptLine l : d.lines) { l.setReceiptId(saved.getId()); l.setPosition(pos++); }
        lines.saveAll(d.lines);
        if ("PAID".equals(saved.getStatus())) events.publishEvent(new ReceiptPaidEvent(saved.getId()));
        return toDto(saved, d.lines, nameOf(saved.getClientId()), nameOf(saved.getTrainerId()), LocalDate.now());
    }

    @Transactional
    public ReceiptDto pay(Long id, PayReceiptRequest r) {
        Receipt x = owned(id);
        if (!"PENDING".equals(x.getStatus())) throw new ConflictException("Solo se puede marcar como pagado un comprobante pendiente");
        LocalDate paid = r != null && r.paidDate() != null ? r.paidDate() : LocalDate.now();
        if (paid.isAfter(LocalDate.now())) throw new BadRequestException("La fecha de pago no puede ser futura");
        if (paid.isBefore(x.getIssueDate())) throw new BadRequestException("La fecha de pago no puede ser anterior a la del comprobante");
        x.setStatus("PAID");
        x.setPaidDate(paid);
        if (r != null && r.method() != null) x.setMethod(r.method());
        events.publishEvent(new ReceiptPaidEvent(x.getId()));
        return toDtos(List.of(x)).get(0);
    }

    @Transactional
    public ReceiptDto voidReceipt(Long id, String reason) {
        Receipt x = owned(id);
        if ("VOID".equals(x.getStatus())) throw new ConflictException("El comprobante ya está anulado");
        boolean wasPaid = "PAID".equals(x.getStatus());
        x.setWasPaid(wasPaid);
        x.setStatus("VOID");
        x.setVoidReason(reason.strip());
        x.setVoidedAt(LocalDateTime.now());
        events.publishEvent(new ReceiptVoidedEvent(x.getId(), wasPaid));
        return toDtos(List.of(x)).get(0);
    }

    /** Comprobante propio o 404 (no se revela si existe uno ajeno). */
    Receipt owned(Long id) {
        Receipt x = receipts.findById(id).orElseThrow(() -> new NotFoundException("Comprobante no encontrado"));
        if (!x.getTrainerId().equals(access.current().id())) throw new NotFoundException("Comprobante no encontrado");
        return x;
    }

    // ---------- Cálculo ----------

    private record Draft(Receipt receipt, List<ReceiptLine> lines) {}

    private Draft draft(ReceiptRequest r) {
        AuthUser me = access.current();
        AppUser client = users.findById(r.clientId()).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
        if (!directory.isClientOf(client, me.id())) throw new ForbiddenException("No es tu cliente");
        if (r.couponId() != null && r.discountPct() != null) throw new BadRequestException("Usa un cupón o un descuento, no los dos");

        List<ReceiptLine> ls = new ArrayList<>();
        for (ReceiptLineRequest lr : r.lines()) ls.add(line(lr, me.id()));
        BigDecimal subtotal = ls.stream().map(ReceiptLine::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);

        Receipt x = new Receipt();
        x.setTrainerId(me.id());
        x.setClientId(client.getId());
        x.setIssueDate(r.issueDate());
        LocalDate due = r.dueDate() != null ? r.dueDate() : r.issueDate();
        if (due.isBefore(r.issueDate())) throw new BadRequestException("El vencimiento no puede ser anterior a la fecha del comprobante");
        x.setDueDate(due);
        x.setNotes(blankToNull(r.notes()));
        x.setMethod(r.method());
        x.setOrigin("MANUAL");
        x.setStatus(r.status());
        if ("PAID".equals(r.status())) {
            LocalDate paid = r.paidDate() != null ? r.paidDate() : r.issueDate();
            if (paid.isAfter(LocalDate.now())) throw new BadRequestException("La fecha de pago no puede ser futura");
            if (paid.isBefore(r.issueDate())) throw new BadRequestException("La fecha de pago no puede ser anterior a la del comprobante");
            x.setPaidDate(paid);
        }

        BigDecimal discount = BigDecimal.ZERO;
        if (r.couponId() != null) {
            Coupon c = coupons.findById(r.couponId()).filter(k -> k.getTrainerId().equals(me.id()))
                    .orElseThrow(() -> new NotFoundException("Cupón no encontrado"));
            checkCoupon(c, ls);
            x.setCouponId(c.getId());
            x.setDiscountName(r.discountName() != null && !r.discountName().isBlank() ? r.discountName().strip() : c.getCode());
            if ("PERCENT".equals(c.getType())) {
                x.setDiscountPct(c.getValue());
                discount = pct(subtotal, c.getValue());
            } else {
                discount = c.getValue().min(subtotal);
            }
        } else if (r.discountPct() != null && r.discountPct().signum() > 0) {
            x.setDiscountName(blankToNull(r.discountName()));
            x.setDiscountPct(r.discountPct());
            discount = pct(subtotal, r.discountPct());
        }
        x.setSubtotal(subtotal);
        x.setDiscountAmount(discount);
        x.setTotal(subtotal.subtract(discount).max(BigDecimal.ZERO));
        return new Draft(x, ls);
    }

    private ReceiptLine line(ReceiptLineRequest r, Long me) {
        ReceiptLine l = new ReceiptLine();
        l.setKind(r.kind());
        String name = r.name();
        BigDecimal price = r.unitPrice();
        Integer tax = r.taxRate();
        switch (r.kind()) {
            case "PRODUCT" -> {
                if (r.refId() == null) throw new BadRequestException("Elige el producto del catálogo");
                Product p = products.findById(r.refId()).filter(k -> k.getTrainerId().equals(me))
                        .orElseThrow(() -> new NotFoundException("Producto no encontrado"));
                if (p.isArchived()) throw new ConflictException("El producto «" + p.getName() + "» está archivado");
                l.setProductId(p.getId());
                if (isBlank(name)) name = p.getName();
                if (price == null) price = p.getPrice();
                if (tax == null) tax = p.getTaxRate().intValue();
            }
            case "PLAN" -> {
                if (r.refId() == null) throw new BadRequestException("Elige el plan del catálogo");
                SubscriptionPlan p = plans.findById(r.refId()).filter(k -> k.getTrainerId().equals(me))
                        .orElseThrow(() -> new NotFoundException("Plan no encontrado"));
                if (p.isArchived()) throw new ConflictException("El plan «" + p.getName() + "» está archivado");
                l.setPlanId(p.getId());
                if (isBlank(name)) name = p.getName();
                if (price == null) price = p.getPrice();
                if (tax == null) tax = p.getTaxRate().intValue();
            }
            default -> {
                if (r.refId() != null) throw new BadRequestException("Un concepto libre no lleva producto");
            }
        }
        if (isBlank(name)) throw new BadRequestException("Indica el nombre del concepto");
        if (price == null) throw new BadRequestException("Indica el precio de «" + name.strip() + "»");
        if (tax == null || !TAX_RATES.contains(tax)) throw new BadRequestException("Impuesto no válido (21, 10, 4 o 0 %)");
        l.setName(name.strip().length() > 120 ? name.strip().substring(0, 120) : name.strip());
        l.setUnitPrice(price.setScale(2, RoundingMode.HALF_UP));
        l.setQuantity(r.quantity().shortValue());
        l.setTaxRate(tax.shortValue());
        l.setLineTotal(l.getUnitPrice().multiply(BigDecimal.valueOf(r.quantity())).setScale(2, RoundingMode.HALF_UP));
        return l;
    }

    private void checkCoupon(Coupon c, List<ReceiptLine> ls) {
        if (!c.isActive()) throw new ConflictException("El cupón no está activo");
        if (c.getExpiresAt() != null && c.getExpiresAt().isBefore(LocalDate.now())) throw new ConflictException("El cupón ha caducado");
        if (c.getMaxUses() != null && c.getUses() >= c.getMaxUses()) throw new ConflictException("El cupón está agotado");
        boolean ok = switch (c.getAppliesTo()) {
            case "PRODUCTS" -> ls.stream().allMatch(l -> "PRODUCT".equals(l.getKind()));
            case "PLANS" -> ls.stream().allMatch(l -> "PLAN".equals(l.getKind()));
            default -> true;
        };
        if (!ok) throw new ConflictException("El cupón no se aplica a estos conceptos");
    }

    private static BigDecimal pct(BigDecimal amount, BigDecimal pct) {
        return amount.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    // ---------- Listado ----------

    private List<ReceiptDto> filtered(ReceiptFilter f) {
        LocalDate[] r = range(f.from(), f.to());
        Long me = access.current().id();
        String norm = norm(f.q());
        Set<Long> clientIds = f.clientIds() == null ? Set.of() : new HashSet<>(f.clientIds());
        Set<String> kinds = f.lineKinds() == null ? Set.of() : new HashSet<>(f.lineKinds());
        Set<String> statuses = f.statuses() == null ? Set.of() : new HashSet<>(f.statuses());
        Set<String> methods = f.methods() == null ? Set.of() : new HashSet<>(f.methods());
        List<Receipt> base = receipts.findByTrainerIdAndIssueDateBetween(me, r[0], r[1]).stream()
                .filter(x -> clientIds.isEmpty() || clientIds.contains(x.getClientId()))
                .filter(x -> statuses.isEmpty() || statuses.contains(x.getStatus()))
                .filter(x -> methods.isEmpty() || methods.contains(x.getMethod()))
                .filter(x -> f.minAmount() == null || x.getTotal().compareTo(f.minAmount()) >= 0)
                .filter(x -> f.maxAmount() == null || x.getTotal().compareTo(f.maxAmount()) <= 0)
                .toList();
        return toDtos(base).stream()
                .filter(d -> kinds.isEmpty() || d.lines().stream().anyMatch(l -> kinds.contains(l.kind())))
                .filter(d -> norm.isEmpty() || norm(d.clientName()).contains(norm) || d.lines().stream().anyMatch(l -> norm(l.name()).contains(norm))
                        || d.total().toPlainString().contains(norm.replace(',', '.')) || norm(d.reference()).contains(norm))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static Comparator<ReceiptDto> comparator(Sort sort) {
        Sort.Order o = sort.stream().findFirst().orElse(Sort.Order.desc("issueDate"));
        Comparator<ReceiptDto> c = switch (o.getProperty()) {
            case "total" -> Comparator.comparing(ReceiptDto::total);
            case "clientName" -> Comparator.comparing(d -> d.clientName().toLowerCase(Locale.ROOT));
            case "status" -> Comparator.comparing(ReceiptDto::status);
            default -> Comparator.comparing(ReceiptDto::issueDate);
        };
        if (o.isDescending()) c = c.reversed();
        return c.thenComparing(ReceiptDto::id, Comparator.reverseOrder());
    }

    /** Rango por defecto: el último año hasta hoy. Como máximo 366 días (contrato). */
    static LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate t = to != null ? to : LocalDate.now();
        LocalDate f = from != null ? from : t.minusDays(MAX_RANGE_DAYS - 1);
        if (f.isAfter(t)) throw new BadRequestException("\"Desde\" no puede ser posterior a \"Hasta\"");
        if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_RANGE_DAYS) throw new BadRequestException("El periodo máximo es de 366 días");
        return new LocalDate[] { f, t };
    }

    // ---------- Mapeo ----------

    List<ReceiptDto> toDtos(List<Receipt> list) {
        if (list.isEmpty()) return new ArrayList<>();
        Map<Long, List<ReceiptLine>> byReceipt = lines.findByReceiptIdInOrderByReceiptIdAscPositionAsc(
                list.stream().map(Receipt::getId).filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.groupingBy(ReceiptLine::getReceiptId));
        Set<Long> userIds = new HashSet<>();
        list.forEach(x -> { userIds.add(x.getClientId()); userIds.add(x.getTrainerId()); });
        Map<Long, String> names = users.findAllById(userIds).stream().collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
        LocalDate today = LocalDate.now();
        return list.stream().map(x -> toDto(x, byReceipt.getOrDefault(x.getId(), List.of()), names.getOrDefault(x.getClientId(), "—"),
                names.getOrDefault(x.getTrainerId(), "—"), today)).collect(Collectors.toCollection(ArrayList::new));
    }

    private static ReceiptDto toDto(Receipt x, List<ReceiptLine> ls, String clientName, String trainerName, LocalDate today) {
        boolean overdue = "PENDING".equals(x.getStatus()) && x.getDueDate().isBefore(today);
        List<ReceiptLineDto> lineDtos = ls.stream().map(l -> new ReceiptLineDto(l.getKind(),
                l.getProductId() != null ? l.getProductId() : l.getPlanId(), l.getName(), l.getUnitPrice(),
                l.getQuantity(), l.getTaxRate(), l.getLineTotal())).toList();
        return new ReceiptDto(x.getId(), x.getId() == null ? null : "PC-" + x.getId(), x.getClientId(), clientName, x.getIssueDate(),
                x.getDueDate(), x.getPaidDate(), x.getNotes(), x.getMethod(), x.getStatus(), overdue, x.getOrigin(), x.getSubscriptionId(),
                x.getPeriodStart(), x.getPeriodEnd(), lineDtos, x.getSubtotal(), x.getDiscountName(), x.getDiscountPct(),
                x.getDiscountAmount(), x.getTotal(), x.getVoidReason(), x.getVoidedAt(), x.getCreatedAt(), trainerName);
    }

    private String nameOf(Long userId) { return users.findById(userId).map(AppUser::getFullName).orElse("—"); }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String blankToNull(String s) { return isBlank(s) ? null : s.strip(); }
    private static String norm(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).strip();
    }
    static String kindLabel(String k) { return switch (k) { case "PRODUCT" -> "Pago único"; case "PLAN" -> "Suscripción"; default -> "Libre"; }; }
    static String statusLabel(String s) { return switch (s) { case "PAID" -> "Pagado"; case "VOID" -> "Anulado"; default -> "Pendiente"; }; }
    static String methodLabel(String m) {
        return switch (m) {
            case "CASH" -> "Efectivo"; case "TRANSFER" -> "Transferencia bancaria"; case "BIZUM" -> "Bizum";
            case "PAYPAL" -> "PayPal"; case "CARD" -> "Tarjeta"; default -> "Otro";
        };
    }

    /** Usado por la fachada de /api/payments: mismos comprobantes como función genérica. */
    <T> Page<T> page(Page<Receipt> p, BiFunction<Receipt, ReceiptDto, T> map) {
        List<ReceiptDto> dtos = toDtos(p.getContent());
        List<T> out = new ArrayList<>();
        for (int i = 0; i < dtos.size(); i++) out.add(map.apply(p.getContent().get(i), dtos.get(i)));
        return new PageImpl<>(out, p.getPageable(), p.getTotalElements());
    }
}
