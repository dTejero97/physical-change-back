package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.util.DateTimes;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Datos de negocio → Panel de control (docs/api-negocio.md, "Datos de negocio"). Cifras calculadas en el servidor;
 * lo que no se puede calcular sale con available = false y el frontend muestra "Sin datos" (nunca 0).
 * Finanzas: los comprobantes se reparten entre suscripción y producto por el peso de sus líneas (con el descuento prorrateado).
 */
@Service
public class BusinessService {
    static final int REGULAR_DAYS = 90, INACTIVE_DAYS = 7, LOW_COMPLIANCE = 50;
    private static final Set<String> OCCUPY = Set.of("BOOKED", "ATTENDED", "NO_SHOW");

    private final ReceiptRepository receipts;
    private final ReceiptLineRepository lines;
    private final ReceiptService receiptService;
    private final ClientSubscriptionRepository subscriptions;
    private final PlatformPaymentRepository platformPayments;
    private final ClientProfileRepository profiles;
    private final ClientLeadRepository leads;
    private final ClientInvitationRepository invitations;
    private final WorkoutAssignmentRepository assignments;
    private final ExerciseLogRepository logs;
    private final NutritionPlanRepository nutrition;
    private final AgendaEventRepository events;
    private final AgendaBookingRepository bookings;
    private final ClientDirectory directory;
    private final AccessService access;

    public BusinessService(ReceiptRepository r, ReceiptLineRepository l, ReceiptService rs, ClientSubscriptionRepository s,
                           PlatformPaymentRepository pp, ClientProfileRepository p, ClientLeadRepository le, ClientInvitationRepository i,
                           WorkoutAssignmentRepository w, ExerciseLogRepository lg, NutritionPlanRepository n, AgendaEventRepository e,
                           AgendaBookingRepository b, ClientDirectory d, AccessService a) {
        receipts = r; lines = l; receiptService = rs; subscriptions = s; platformPayments = pp; profiles = p; leads = le; invitations = i;
        assignments = w; logs = lg; nutrition = n; events = e; bookings = b; directory = d; access = a;
    }

    /** Rango por defecto: los últimos 30 días. Como máximo 366 días. */
    static LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate t = to != null ? to : LocalDate.now();
        return ReceiptService.range(from != null ? from : t.minusDays(29), t);
    }

    // ---------- Finanzas ----------

    @Transactional(readOnly = true)
    public FinanceDto finance(LocalDate from, LocalDate to) {
        AuthUser me = access.current();
        LocalDate[] r = range(from, to);
        List<Receipt> paid = receipts.findByTrainerIdAndPaidDateBetween(me.id(), r[0], r[1]).stream().filter(x -> "PAID".equals(x.getStatus())).toList();
        List<Receipt> voided = receipts.findByTrainerIdAndWasPaidTrueAndVoidedAtBetween(me.id(), DateTimes.startOfDay(r[0]), DateTimes.endExclusive(r[1]));
        List<Receipt> issued = receipts.findByTrainerIdAndIssueDateBetween(me.id(), r[0], r[1]);
        Map<Long, BigDecimal> planShare = planShares(concat(paid, voided));
        Map<Long, LocalDate> subStart = subscriptions.findAllById(concat(paid, voided).stream().map(Receipt::getSubscriptionId)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(ClientSubscription::getId, ClientSubscription::getStartDate));

        BigDecimal totalSold = sum(paid, Receipt::getTotal);
        BigDecimal subscriptionSales = paid.stream().map(x -> planShare.getOrDefault(x.getId(), BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal voidedAmount = sum(voided, Receipt::getTotal);

        Map<LocalDate, BigDecimal[]> subsByDay = new TreeMap<>();   // [nuevas, renovaciones, anulaciones]
        Map<LocalDate, BigDecimal> paidByDay = new TreeMap<>();
        for (LocalDate d = r[0]; !d.isAfter(r[1]); d = d.plusDays(1)) {
            subsByDay.put(d, new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
            paidByDay.put(d, BigDecimal.ZERO);
        }
        for (Receipt x : paid) {
            paidByDay.merge(x.getPaidDate(), x.getTotal(), BigDecimal::add);
            BigDecimal share = planShare.getOrDefault(x.getId(), BigDecimal.ZERO);
            if (share.signum() == 0) continue;
            boolean renewal = x.getSubscriptionId() != null && x.getPeriodStart() != null
                    && x.getPeriodStart().isAfter(subStart.getOrDefault(x.getSubscriptionId(), x.getPeriodStart()));
            BigDecimal[] v = subsByDay.get(x.getPaidDate());
            if (v != null) v[renewal ? 1 : 0] = v[renewal ? 1 : 0].add(share);
        }
        for (Receipt x : voided) {
            BigDecimal share = planShare.getOrDefault(x.getId(), BigDecimal.ZERO);
            BigDecimal[] v = subsByDay.get(x.getVoidedAt().toLocalDate());
            if (v != null && share.signum() > 0) v[2] = v[2].subtract(share);
        }
        BigDecimal pending = sum(issued.stream().filter(x -> "PENDING".equals(x.getStatus())).toList(), Receipt::getTotal);
        BigDecimal base = totalSold.add(pending);
        int paidPct = base.signum() == 0 ? 0 : totalSold.multiply(BigDecimal.valueOf(100)).divide(base, 0, RoundingMode.HALF_UP).intValue();

        BigDecimal platformCost = null, result = null;
        if (me.role() == Role.ADMIN) {
            platformCost = platformPayments.findByStatusAndPaymentDateBetween("PAID", r[0], r[1]).stream()
                    .map(PlatformPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            result = totalSold.subtract(platformCost);
        }
        return new FinanceDto(
                new FinanceKpisDto(totalSold, subscriptionSales, totalSold.subtract(subscriptionSales), sum(issued, Receipt::getTotal), voidedAmount),
                platformCost, result,
                new SubscriptionsChartDto(subscriptionSales, subsByDay.entrySet().stream()
                        .map(e -> new SubscriptionDayDto(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2])).toList()),
                new ReceiptsChartDto(totalSold, paidByDay.entrySet().stream().map(e -> new AmountDayDto(e.getKey(), e.getValue())).toList()),
                new ReceiptStatusDto(totalSold, paidPct, pending, base.signum() == 0 ? 0 : 100 - paidPct));
    }

    /** Comprobantes emitidos o cobrados ese día (modal al pulsar una barra). */
    @Transactional(readOnly = true)
    public List<ReceiptDto> financeDay(LocalDate date) {
        if (date == null) throw new BadRequestException("Indica el día");
        Long me = access.current().id();
        Map<Long, Receipt> byId = new LinkedHashMap<>();
        receipts.findByTrainerIdAndPaidDateBetween(me, date, date).forEach(x -> byId.put(x.getId(), x));
        receipts.findByTrainerIdAndIssueDateBetween(me, date, date).forEach(x -> byId.putIfAbsent(x.getId(), x));
        return receiptService.toDtos(new ArrayList<>(byId.values()));
    }

    @Transactional(readOnly = true)
    public Page<ItemIncomeDto> byItem(LocalDate from, LocalDate to, List<String> kinds, String q, Pageable p) {
        Set<String> k = kinds == null || kinds.isEmpty() ? Set.of("PRODUCT", "PLAN", "FREE") : new HashSet<>(kinds);
        if (k.contains("PRODUCT")) { k = new HashSet<>(k); k.add("FREE"); }   // los conceptos libres cuentan como productos
        Set<String> wanted = k;
        String n = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        List<ItemIncomeDto> all = sales(from, to).values().stream()
                .filter(i -> wanted.contains(i.kind) && (n.isEmpty() || i.name.toLowerCase(Locale.ROOT).contains(n)))
                .map(i -> new ItemIncomeDto(i.kind, i.refId, i.name, i.units, i.income)).collect(Collectors.toCollection(ArrayList::new));
        Sort.Order o = p.getSort().stream().findFirst().orElse(Sort.Order.desc("income"));
        Comparator<ItemIncomeDto> c = switch (o.getProperty()) {
            case "name" -> Comparator.comparing(i -> i.name().toLowerCase(Locale.ROOT));
            case "units" -> Comparator.comparingLong(ItemIncomeDto::units);
            default -> Comparator.comparing(ItemIncomeDto::income);
        };
        all.sort(o.isDescending() ? c.reversed() : c);
        int a = (int) Math.min(p.getOffset(), all.size());
        return new PageImpl<>(all.subList(a, Math.min(a + p.getPageSize(), all.size())), p, all.size());
    }

    /** Ventas de un plan, producto (refId) o concepto libre (name). */
    @Transactional(readOnly = true)
    public ItemSalesDto itemSales(LocalDate from, LocalDate to, String kind, Long refId, String name) {
        String key = itemKey(kind, refId, name);
        Item i = sales(from, to).get(key);
        if (i == null) return new ItemSalesDto(name != null ? name : "—", 0, BigDecimal.ZERO, List.of());
        List<ItemBuyerDto> buyers = i.buyers.stream().sorted(Comparator.comparing(ItemBuyerDto::date).reversed()).toList();
        return new ItemSalesDto(i.name, buyers.size(), i.income, buyers);
    }

    private static final class Item {
        final String kind; final Long refId; final String name; long units; BigDecimal income = BigDecimal.ZERO;
        final List<ItemBuyerDto> buyers = new ArrayList<>();
        Item(String kind, Long refId, String name) { this.kind = kind; this.refId = refId; this.name = name; }
    }

    private static String itemKey(String kind, Long refId, String name) {
        return "FREE".equals(kind) ? "FREE:" + (name == null ? "" : name.strip().toLowerCase(Locale.ROOT)) : kind + ":" + refId;
    }

    /** Líneas de los comprobantes cobrados en el rango, agrupadas por producto, plan o nombre (FREE). */
    private Map<String, Item> sales(LocalDate from, LocalDate to) {
        LocalDate[] r = range(from, to);
        List<Receipt> paid = receipts.findByTrainerIdAndPaidDateBetween(access.current().id(), r[0], r[1]).stream()
                .filter(x -> "PAID".equals(x.getStatus())).toList();
        Map<Long, Receipt> byId = paid.stream().collect(Collectors.toMap(Receipt::getId, x -> x));
        List<ReceiptDto> dtos = receiptService.toDtos(paid);
        Map<String, Item> out = new LinkedHashMap<>();
        for (ReceiptDto d : dtos) {
            Receipt x = byId.get(d.id());
            BigDecimal factor = x.getSubtotal().signum() == 0 ? BigDecimal.ONE
                    : x.getTotal().divide(x.getSubtotal(), 6, RoundingMode.HALF_UP);
            for (ReceiptLineDto l : d.lines()) {
                Item i = out.computeIfAbsent(itemKey(l.kind(), l.refId(), l.name()), k -> new Item(l.kind(), l.refId(), l.name()));
                BigDecimal amount = l.lineTotal().multiply(factor).setScale(2, RoundingMode.HALF_UP);
                i.units += l.quantity();
                i.income = i.income.add(amount);
                i.buyers.add(new ItemBuyerDto(d.clientId(), d.clientName(), amount, d.id(), d.paidDate()));
            }
        }
        return out;
    }

    /** Parte de cada comprobante que corresponde a líneas PLAN (con el descuento prorrateado). */
    private Map<Long, BigDecimal> planShares(List<Receipt> list) {
        Map<Long, Receipt> byId = list.stream().collect(Collectors.toMap(Receipt::getId, x -> x, (a, b) -> a));
        Map<Long, BigDecimal> plan = new HashMap<>();
        if (byId.isEmpty()) return plan;
        for (ReceiptLine l : lines.findByReceiptIdInOrderByReceiptIdAscPositionAsc(byId.keySet())) {
            if (!"PLAN".equals(l.getKind())) continue;
            plan.merge(l.getReceiptId(), l.getLineTotal(), BigDecimal::add);
        }
        Map<Long, BigDecimal> out = new HashMap<>();
        plan.forEach((id, gross) -> {
            Receipt x = byId.get(id);
            out.put(id, x.getSubtotal().signum() == 0 ? BigDecimal.ZERO
                    : gross.multiply(x.getTotal()).divide(x.getSubtotal(), 2, RoundingMode.HALF_UP));
        });
        return out;
    }

    // ---------- Clientes ----------

    private record ClientInfo(AppUser user, ClientProfile profile) {
        boolean removed() { return profile != null && profile.getRemovedAt() != null; }
        boolean active() { return user.isActive() && !removed(); }
        LocalDate since() { return profile != null ? profile.getActivatedAt() : null; }
    }

    private List<ClientInfo> clients() {
        List<AppUser> list = directory.clientsOf(access.current().id());
        Map<Long, ClientProfile> byId = profiles.findByClientIdIn(list.stream().map(AppUser::getId).toList()).stream()
                .collect(Collectors.toMap(ClientProfile::getClientId, p -> p));
        return list.stream().map(u -> new ClientInfo(u, byId.get(u.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public ClientsDto clientsData(LocalDate from, LocalDate to, String series) {
        LocalDate[] r = range(from, to);
        String s = series == null ? "TOTAL" : series;
        if (!Set.of("TOTAL", "NEW", "ARCHIVED").contains(s)) throw new BadRequestException("Serie no válida");
        LocalDate today = LocalDate.now();
        List<ClientInfo> all = clients();
        List<ClientInfo> active = all.stream().filter(ClientInfo::active).toList();
        long newClients = all.stream().filter(c -> !c.removed() && c.since() != null && !c.since().isBefore(r[0]) && !c.since().isAfter(r[1])).count();
        long regular = active.stream().filter(c -> c.since() != null && ChronoUnit.DAYS.between(c.since(), today) > REGULAR_DAYS).count();
        long tenure = Math.round(active.stream().filter(c -> c.since() != null).mapToLong(c -> ChronoUnit.DAYS.between(c.since(), today)).average().orElse(0));

        List<ValueDayDto> days = new ArrayList<>();
        boolean available = !"ARCHIVED".equals(s);
        if (available) {
            for (LocalDate d = r[0]; !d.isAfter(r[1]); d = d.plusDays(1)) {
                LocalDate day = d;
                long v = "NEW".equals(s)
                        ? all.stream().filter(c -> !c.removed() && day.equals(c.since())).count()
                        : active.stream().filter(c -> c.since() != null && !c.since().isAfter(day)).count();
                days.add(new ValueDayDto(d, v));
            }
        }
        return new ClientsDto(new ClientsKpisDto(active.size(), newClients, regular, tenure), new ClientsEvolutionDto(s, available, days), states(all));
    }

    @Transactional(readOnly = true)
    public ClientsDayDto clientsDay(LocalDate date, String series) {
        if (date == null) throw new BadRequestException("Indica el día");
        if ("ARCHIVED".equals(series)) throw new BadRequestException("La fecha de archivo no se guarda: no hay detalle por día");
        boolean onlyNew = "NEW".equals(series);
        List<ClientDayRowDto> rows = clients().stream()
                .filter(c -> c.since() != null && (onlyNew ? date.equals(c.since()) : c.active() && !c.since().isAfter(date)))
                .sorted(Comparator.comparing(c -> c.user().getFullName().toLowerCase(Locale.ROOT)))
                .map(c -> new ClientDayRowDto(c.user().getId(), c.user().getFullName(), date.equals(c.since()) ? "NEW" : "REGULAR")).toList();
        return new ClientsDayDto(date, rows.size(), rows);
    }

    /** Subestado → filas (leads e invitaciones no son clientes todavía: clientId null). */
    @Transactional(readOnly = true)
    public List<ClientStateRowDto> clientState(String state) {
        Long me = access.current().id();
        boolean admin = access.current().role() == Role.ADMIN;
        LocalDate today = LocalDate.now();
        switch (state) {
            case "leads" -> {
                return (admin ? leads.findAllByOrderByRegisteredAtDesc() : leads.findByTrainerIdOrderByRegisteredAtDesc(me)).stream()
                        .map(l -> new ClientStateRowDto(null, l.getName(), l.getEmail(), "Lead (" + l.getSource().toLowerCase(Locale.ROOT) + ") desde el "
                                + DateTimes.ddMMyyyy(l.getRegisteredAt().toLocalDate()))).toList();
            }
            case "notRegistered" -> {
                return pendingInvitations(me, admin).stream()
                        .map(i -> new ClientStateRowDto(null, i.getName(), i.getEmail(), "Invitación enviada el " + DateTimes.ddMMyyyy(i.getInvitedAt().toLocalDate())))
                        .toList();
            }
            default -> { }
        }
        Analysis a = analyse(clients());
        List<ClientInfo> list = switch (state) {
            case "notStarted" -> a.notStarted;
            case "withoutPlanning" -> a.withoutPlanning;
            case "withSubscription" -> a.withSubscription;
            case "withoutSubscription" -> a.withoutSubscription;
            case "lowCompliance" -> a.lowCompliance;
            case "inactive" -> a.inactive;
            case "archived" -> a.archived;
            default -> throw new NotFoundException("Estado no válido");
        };
        return list.stream().sorted(Comparator.comparing(c -> c.user().getFullName().toLowerCase(Locale.ROOT))).map(c -> {
            String detail = switch (state) {
                case "lowCompliance" -> "Cumplimiento mensual " + a.monthly.getOrDefault(c.user().getId(), 0) + " %";
                case "inactive" -> "Sin actividad desde hace " + ChronoUnit.DAYS.between(a.last.get(c.user().getId()), today) + " días";
                case "notStarted" -> "Aceptó la invitación y aún no tiene actividad";
                case "withoutPlanning" -> "Sin workout ni plan nutricional asignado";
                case "withSubscription" -> "Con un plan de suscripción activo";
                case "withoutSubscription" -> "Sin plan de suscripción";
                default -> "Archivado";
            };
            return new ClientStateRowDto(c.user().getId(), c.user().getFullName(), c.user().getEmail(), detail);
        }).toList();
    }

    private List<ClientInvitation> pendingInvitations(Long me, boolean admin) {
        LocalDateTime now = LocalDateTime.now();
        return (admin ? invitations.findByEmailIsNotNullAndAcceptedAtIsNullOrderByInvitedAtDesc()
                : invitations.findByTrainerIdAndEmailIsNotNullAndAcceptedAtIsNullOrderByInvitedAtDesc(me)).stream()
                .filter(i -> i.getExpiresAt().isAfter(now)).toList();
    }

    private static final class Analysis {
        final List<ClientInfo> notStarted = new ArrayList<>(), withoutPlanning = new ArrayList<>(), withSubscription = new ArrayList<>(),
                withoutSubscription = new ArrayList<>(), lowCompliance = new ArrayList<>(), inactive = new ArrayList<>(), archived = new ArrayList<>();
        final Map<Long, Integer> monthly = new HashMap<>();
        final Map<Long, LocalDate> last = new HashMap<>();
    }

    /** Estados de hoy (docs/fase5-pantallas-v3.md, supuesto 5: en riesgo = cumplimiento < 50 % en el mes o sin actividad en 7 días). */
    private Analysis analyse(List<ClientInfo> all) {
        Analysis a = new Analysis();
        LocalDate today = LocalDate.now();
        List<ClientInfo> active = all.stream().filter(ClientInfo::active).toList();
        List<Long> ids = active.stream().map(c -> c.user().getId()).toList();
        all.stream().filter(c -> !c.user().isActive() && !c.removed()).forEach(a.archived::add);
        if (ids.isEmpty()) return a;

        Map<Long, int[]> month = new HashMap<>();   // [hechas, total]
        Set<Long> withFutureWorkout = new HashSet<>();
        for (WorkoutAssignment w : assignments.findByClientIdInAndScheduledDateBetween(ids, today.minusDays(29), today.plusYears(1))) {
            if (!w.getScheduledDate().isAfter(today)) {
                int[] c = month.computeIfAbsent(w.getClientId(), k -> new int[2]);
                c[1]++; if (w.getCompletedDate() != null) c[0]++;
            }
            if (!w.getScheduledDate().isBefore(today)) withFutureWorkout.add(w.getClientId());
        }
        month.forEach((id, c) -> a.monthly.put(id, c[1] == 0 ? 0 : Math.round(c[0] * 100f / c[1])));
        for (Object[] row : logs.lastLogDates(ids)) a.last.merge((Long) row[0], (LocalDate) row[1], (x, y) -> x.isAfter(y) ? x : y);
        for (Object[] row : assignments.lastCompletedDates(ids)) a.last.merge((Long) row[0], (LocalDate) row[1], (x, y) -> x.isAfter(y) ? x : y);
        Set<Long> withNutrition = nutrition.findByClientIdIn(ids).stream().map(NutritionPlan::getClientId).collect(Collectors.toSet());
        Set<Long> subscribed = subscriptions.findByTrainerIdOrderByCreatedAtDesc(access.current().id()).stream()
                .filter(s -> "ACTIVE".equals(s.getStatus())).map(ClientSubscription::getClientId).collect(Collectors.toSet());

        for (ClientInfo c : active) {
            Long id = c.user().getId();
            LocalDate last = a.last.get(id);
            if (last == null) a.notStarted.add(c);
            if (!withFutureWorkout.contains(id) && !withNutrition.contains(id)) a.withoutPlanning.add(c);
            (subscribed.contains(id) ? a.withSubscription : a.withoutSubscription).add(c);
            Integer pct = a.monthly.get(id);
            if (pct != null && pct < LOW_COMPLIANCE) a.lowCompliance.add(c);
            if (last != null && last.isBefore(today.minusDays(INACTIVE_DAYS))) a.inactive.add(c);
        }
        return a;
    }

    private ClientStatesDto states(List<ClientInfo> all) {
        Long me = access.current().id();
        boolean admin = access.current().role() == Role.ADMIN;
        Analysis a = analyse(all);
        long leadCount = (admin ? leads.findAllByOrderByRegisteredAtDesc() : leads.findByTrainerIdOrderByRegisteredAtDesc(me)).size();
        return new ClientStatesDto(new ContactedDto(leadCount, pendingInvitations(me, admin).size()),
                new WaitingDto(a.notStarted.size(), a.withoutPlanning.size()),
                new ActiveClientsDto(a.withSubscription.size(), a.withoutSubscription.size()),
                new AtRiskDto(a.lowCompliance.size(), a.inactive.size()), new FinishedDto(a.archived.size()));
    }

    // ---------- Servicios ----------

    @Transactional(readOnly = true)
    public ServicesDto services(LocalDate from, LocalDate to) {
        LocalDate[] r = range(from, to);
        LocalDate today = LocalDate.now();
        LocalDate until = r[1].isAfter(today) ? today : r[1];
        List<AgendaEvent> sessions = events.findByTrainerIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(
                access.current().id(), DateTimes.startOfDay(r[0]), DateTimes.endExclusive(r[1])).stream()
                .filter(e -> "SESSION".equals(e.getKind())).toList();
        Map<Long, List<AgendaBooking>> byEvent = sessions.isEmpty() ? Map.of()
                : bookings.findByEventIdIn(sessions.stream().map(AgendaEvent::getId).toList()).stream().collect(Collectors.groupingBy(AgendaBooking::getEventId));

        long occupied = 0, attended = 0, pastOccupied = 0, seats = 0, seatsTaken = 0;
        for (AgendaEvent e : sessions) {
            List<AgendaBooking> bs = byEvent.getOrDefault(e.getId(), List.of());
            long occ = bs.stream().filter(b -> OCCUPY.contains(b.getStatus())).count();
            occupied += occ;
            if (!e.getStartAt().toLocalDate().isAfter(until)) {
                pastOccupied += occ;
                attended += bs.stream().filter(b -> "ATTENDED".equals(b.getStatus())).count();
            }
            if (e.getCapacity() != null) { seats += e.getCapacity(); seatsTaken += Math.min(occ, e.getCapacity()); }
        }
        Integer sessionPct = pastOccupied == 0 ? null : (int) Math.round(attended * 100.0 / pastOccupied);

        List<Long> clientIds = directory.clientsOf(access.current().id()).stream().map(AppUser::getId).toList();
        List<WorkoutAssignment> ws = clientIds.isEmpty() ? List.of() : assignments.findByClientIdInAndScheduledDateBetween(clientIds, r[0], r[1]);
        long wsPast = ws.stream().filter(w -> !w.getScheduledDate().isAfter(until)).count();
        long wsDone = ws.stream().filter(w -> !w.getScheduledDate().isAfter(until) && w.getCompletedDate() != null).count();
        Integer workoutPct = wsPast == 0 ? null : (int) Math.round(wsDone * 100.0 / wsPast);

        List<ComplianceByTypeDto> byType = List.of(
                new ComplianceByTypeDto("SESSIONS", sessionPct, sessionPct != null),
                new ComplianceByTypeDto("WORKOUTS", workoutPct, workoutPct != null),
                new ComplianceByTypeDto("CARDIO", null, false),
                new ComplianceByTypeDto("VIDEOS", null, false),
                new ComplianceByTypeDto("NUTRITION", null, false));
        List<Integer> avail = byType.stream().map(ComplianceByTypeDto::pct).filter(Objects::nonNull).toList();
        Integer avg = avail.isEmpty() ? null : (int) Math.round(avail.stream().mapToInt(Integer::intValue).average().orElse(0));
        Integer unbooked = seats == 0 ? null : (int) Math.round((seats - seatsTaken) * 100.0 / seats);
        return new ServicesDto(new ServicesKpisDto(occupied + ws.size(), avg, sessionPct, unbooked), byType, new RatingsDto(false, null, List.of()));
    }

    // ---------- utilidades ----------

    private static <T> List<T> concat(List<T> a, List<T> b) { List<T> out = new ArrayList<>(a); out.addAll(b); return out; }
    private static BigDecimal sum(List<Receipt> list, java.util.function.Function<Receipt, BigDecimal> f) {
        return list.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
