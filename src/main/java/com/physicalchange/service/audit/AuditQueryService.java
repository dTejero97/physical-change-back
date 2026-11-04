package com.physicalchange.service.audit;

import com.physicalchange.domain.AppUser;
import com.physicalchange.domain.AuditEvent;
import com.physicalchange.domain.Role;
import com.physicalchange.exception.BadRequestException;
import com.physicalchange.exception.ForbiddenException;
import com.physicalchange.repository.AuditEventRepository;
import com.physicalchange.repository.UserRepository;
import com.physicalchange.security.AccessService;
import com.physicalchange.service.ReceiptService.ExportFile;
import com.physicalchange.util.Spreadsheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Consulta, exportación y purga del registro de auditoría (ADR-016, docs/api-auditoria.md). Solo ADMIN. */
@Service
public class AuditQueryService {
    private static final Logger log = LoggerFactory.getLogger(AuditQueryService.class);
    /** Retención decidida por el usuario (A-02). */
    static final Period RETENTION = Period.ofMonths(12);
    static final int MAX_RANGE_DAYS = 366, EXPORT_MAX = 20_000;

    public record AuditEventDto(Long id, OffsetDateTime occurredAt, Long actorId, String actorName, String actorRole, String action,
                                String actionLabel, String entityType, Long entityId, Long clientId, String clientName, String summary, String ip) {}
    public record AuditActionDto(String code, String label, String group) {}
    public record Filter(LocalDate from, LocalDate to, Long actorId, Long clientId, List<String> actions, String q) {}

    private final AuditEventRepository events;
    private final UserRepository users;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;
    private final AtomicReference<LocalDate> lastPurge = new AtomicReference<>();
    /** Purga en su propia transacción: se llama desde esta clase (sin proxy) y desde consultas de solo lectura. */
    private final TransactionTemplate purgeTx;

    public AuditQueryService(AuditEventRepository e, UserRepository u, AccessService a, AuditService au, Clock c, PlatformTransactionManager tm) {
        events = e; users = u; access = a; audit = au; clock = c;
        purgeTx = new TransactionTemplate(tm);
        purgeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<AuditActionDto> actions() {
        requireAdmin();
        return Arrays.stream(AuditAction.values()).map(x -> new AuditActionDto(x.name(), x.label(), x.group())).toList();
    }

    @Transactional(readOnly = true)
    public Page<AuditEventDto> search(Filter f, Pageable p) {
        requireAdmin();
        purgeIfDue();
        Page<AuditEvent> page = query(f, PageRequest.of(p.getPageNumber(), Math.min(200, Math.max(1, p.getPageSize()))));
        return new PageImpl<>(dtos(page.getContent()), page.getPageable(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ExportFile export(Filter f) {
        requireAdmin();
        LocalDate[] r = range(f.from(), f.to());
        List<AuditEventDto> all = dtos(query(f, PageRequest.of(0, EXPORT_MAX)).getContent());
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Fecha y hora", "Persona", "Rol", "Acción", "Cliente", "Resumen", "IP"));
        for (AuditEventDto d : all) {
            rows.add(Arrays.asList(d.occurredAt().format(fmt), d.actorName(), d.actorRole(), d.actionLabel(), d.clientName(), d.summary(), d.ip()));
        }
        audit.record(AuditAction.EXPORT, "AUDIT", null, null, "Registro de auditoría " + r[0] + " a " + r[1] + " (" + all.size() + " filas)");
        return new ExportFile(Spreadsheet.csv(rows), "physical-change-auditoria-" + r[0] + "-" + r[1] + ".csv", "text/csv; charset=UTF-8");
    }

    // ---------- Purga (12 meses): cada día, al arrancar y al consultar como mucho una vez al día (Render se duerme) ----------

    @Scheduled(cron = "0 30 3 * * *")
    public void scheduledPurge() { purge(); }

    @EventListener(ApplicationReadyEvent.class)
    public void purgeOnStart() { purge(); }

    private void purgeIfDue() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(lastPurge.get())) purge();
    }

    public void purge() {
        try {
            Instant before = ZonedDateTime.now(clock).minus(RETENTION).toInstant();
            Integer n = purgeTx.execute(s -> events.deleteOlderThan(before));
            lastPurge.set(LocalDate.now(clock));
            if (n != null && n > 0) log.info("Auditoría: {} eventos de más de 12 meses borrados", n);
        } catch (RuntimeException e) {
            log.warn("Auditoría: no se ha podido purgar ({})", e.getClass().getSimpleName());
        }
    }

    // ---------- internos ----------

    private Page<AuditEvent> query(Filter f, Pageable p) {
        LocalDate[] r = range(f.from(), f.to());
        ZoneId zone = clock.getZone();
        List<String> acts = f.actions() == null ? List.of() : f.actions().stream().filter(Objects::nonNull).map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
        for (String a : acts) {
            try { AuditAction.valueOf(a); } catch (IllegalArgumentException e) { throw new BadRequestException("Acción desconocida: " + a); }
        }
        String q = f.q() == null || f.q().isBlank() ? null : "%" + f.q().strip().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
        return events.search(r[0].atStartOfDay(zone).toInstant(), r[1].plusDays(1).atStartOfDay(zone).toInstant(), f.actorId(), f.clientId(),
                acts.isEmpty(), acts.isEmpty() ? List.of("-") : acts, q, p);
    }

    private LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate t = to != null ? to : LocalDate.now(clock), fr = from != null ? from : t.minusDays(29);
        if (fr.isAfter(t)) throw new BadRequestException("La fecha inicial es posterior a la final");
        if (fr.plusDays(MAX_RANGE_DAYS).isBefore(t)) throw new BadRequestException("El rango no puede pasar de 366 días");
        return new LocalDate[] { fr, t };
    }

    private List<AuditEventDto> dtos(List<AuditEvent> list) {
        Set<Long> ids = new HashSet<>();
        for (AuditEvent e : list) { if (e.getActorId() != null) ids.add(e.getActorId()); if (e.getClientId() != null) ids.add(e.getClientId()); }
        Map<Long, String> names = users.findAllById(ids).stream().collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
        ZoneId zone = clock.getZone();
        return list.stream().map(e -> new AuditEventDto(e.getId(), e.getOccurredAt().atZone(zone).toOffsetDateTime(), e.getActorId(),
                e.getActorId() == null ? (e.getAction().startsWith("LOGIN_") ? "Desconocido" : "Sistema") : names.getOrDefault(e.getActorId(), "Usuario eliminado"),
                e.getActorRole(), e.getAction(),
                label(e.getAction()), e.getEntityType(), e.getEntityId(), e.getClientId(),
                e.getClientId() == null ? null : names.getOrDefault(e.getClientId(), "Usuario eliminado"), e.getSummary(), e.getIp())).toList();
    }

    private static String label(String code) {
        try { return AuditAction.valueOf(code).label(); } catch (IllegalArgumentException e) { return code; }
    }

    private void requireAdmin() {
        if (access.current().role() != Role.ADMIN) throw new ForbiddenException("Solo el administrador consulta la auditoría");
    }
}
