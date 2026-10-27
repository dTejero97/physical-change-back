package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.service.ReceiptService.ExportFile;
import com.physicalchange.util.DateTimes;
import com.physicalchange.util.Spreadsheet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Datos de negocio → Informes (docs/api-negocio.md, ADR-004 §7): billing, client-compliance, team-performance e incomplete-signups.
 * Fechas obligatorias (máx. 366 días). Sin emails ni teléfonos salvo en "Registros no completados", que los necesita para recontactar.
 */
@Service
public class BusinessReportService {
    private static final Set<String> OCCUPY = Set.of("BOOKED", "ATTENDED", "NO_SHOW");

    private final ReceiptRepository receipts;
    private final ReceiptService receiptService;
    private final BusinessService business;
    private final AgendaEventRepository events;
    private final AgendaBookingRepository bookings;
    private final BonusRedemptionRepository redemptions;
    private final WorkoutAssignmentRepository assignments;
    private final ClientInvitationRepository invitations;
    private final ClientLeadRepository leads;
    private final ClientTagRepository tags;
    private final UserRepository users;
    private final ClientDirectory directory;
    private final AccessService access;

    public BusinessReportService(ReceiptRepository r, ReceiptService rs, BusinessService b, AgendaEventRepository e, AgendaBookingRepository bk,
                                 BonusRedemptionRepository rd, WorkoutAssignmentRepository w, ClientInvitationRepository i, ClientLeadRepository l,
                                 ClientTagRepository t, UserRepository u, ClientDirectory d, AccessService a) {
        receipts = r; receiptService = rs; business = b; events = e; bookings = bk; redemptions = rd; assignments = w; invitations = i;
        leads = l; tags = t; users = u; directory = d; access = a;
    }

    @Transactional(readOnly = true)
    public ExportFile report(String report, LocalDate from, LocalDate to, String format) {
        if (from == null || to == null) throw new BadRequestException("Las fechas son obligatorias");
        if (!"xlsx".equalsIgnoreCase(format) && !"csv".equalsIgnoreCase(format)) throw new BadRequestException("Formato no válido: xlsx o csv");
        LocalDate[] r = ReceiptService.range(from, to);
        List<List<Object>> rows = switch (report) {
            case "billing" -> billing(r[0], r[1]);
            case "client-compliance" -> compliance(r[0], r[1]);
            case "team-performance" -> team(r[0], r[1]);
            case "incomplete-signups" -> signups(r[0], r[1]);
            default -> throw new NotFoundException("Informe no encontrado");
        };
        boolean xlsx = "xlsx".equalsIgnoreCase(format);
        String name = "physical-change-" + report + "-" + r[0] + "-" + r[1] + (xlsx ? ".xlsx" : ".csv");
        return xlsx
                ? new ExportFile(Spreadsheet.xlsx(sheetName(report), rows), name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                : new ExportFile(Spreadsheet.csv(rows), name, "text/csv; charset=UTF-8");
    }

    private static String sheetName(String report) {
        return switch (report) {
            case "billing" -> "Facturación";
            case "client-compliance" -> "Cumplimiento";
            case "team-performance" -> "Equipo";
            default -> "Registros no completados";
        };
    }

    /** Una fila por línea de comprobante emitido en el rango. */
    private List<List<Object>> billing(LocalDate from, LocalDate to) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Referencia", "Fecha", "Cliente", "Concepto", "Tipo", "Cantidad", "Precio ud. (€)", "Impuesto (%)", "Importe línea (€)",
                "Descuento (€)", "Total comprobante (€)", "Método", "Estado", "Fecha de pago"));
        List<ReceiptDto> list = receiptService.toDtos(receipts.findByTrainerIdAndIssueDateBetween(access.current().id(), from, to));
        list.sort(Comparator.comparing(ReceiptDto::issueDate).thenComparing(ReceiptDto::id));
        for (ReceiptDto d : list) {
            for (ReceiptLineDto l : d.lines()) {
                rows.add(Arrays.asList(d.reference(), d.issueDate(), d.clientName(), l.name(), ReceiptService.kindLabel(l.kind()), l.quantity(),
                        l.unitPrice(), l.taxRate(), l.lineTotal(), d.discountAmount(), d.total(), ReceiptService.methodLabel(d.method()),
                        ReceiptService.statusLabel(d.status()), d.paidDate()));
            }
        }
        return rows;
    }

    /** Por cliente: sesiones reservadas y asistidas, workouts asignados y hechos, bonos canjeados en el rango. */
    private List<List<Object>> compliance(LocalDate from, LocalDate to) {
        Long me = access.current().id();
        List<AgendaEvent> sessions = events.findByTrainerIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(
                me, DateTimes.startOfDay(from), DateTimes.endExclusive(to)).stream().filter(e -> "SESSION".equals(e.getKind())).toList();
        List<AgendaBooking> bs = sessions.isEmpty() ? List.of() : bookings.findByEventIdIn(sessions.stream().map(AgendaEvent::getId).toList())
                .stream().filter(b -> OCCUPY.contains(b.getStatus())).toList();
        Map<Long, Long> bookingClient = bs.stream().collect(Collectors.toMap(AgendaBooking::getId, AgendaBooking::getClientId));
        Map<Long, Long> redeemed = bs.isEmpty() ? Map.of() : redemptions.findByBookingIdIn(bookingClient.keySet()).stream()
                .collect(Collectors.groupingBy(x -> bookingClient.get(x.getBookingId()), Collectors.counting()));

        List<AppUser> clients = directory.clientsOf(me).stream().sorted(Comparator.comparing(u -> u.getFullName().toLowerCase(Locale.ROOT))).toList();
        Map<Long, List<WorkoutAssignment>> ws = clients.isEmpty() ? Map.of()
                : assignments.findByClientIdInAndScheduledDateBetween(clients.stream().map(AppUser::getId).toList(), from, to).stream()
                .collect(Collectors.groupingBy(WorkoutAssignment::getClientId));

        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Cliente", "Sesiones reservadas", "Sesiones asistidas", "Workouts asignados", "Workouts hechos", "Bonos canjeados"));
        for (AppUser c : clients) {
            long booked = bs.stream().filter(b -> b.getClientId().equals(c.getId())).count();
            long attended = bs.stream().filter(b -> b.getClientId().equals(c.getId()) && "ATTENDED".equals(b.getStatus())).count();
            List<WorkoutAssignment> mine = ws.getOrDefault(c.getId(), List.of());
            rows.add(Arrays.asList(c.getFullName(), booked, attended, (long) mine.size(),
                    mine.stream().filter(w -> w.getCompletedDate() != null).count(), redeemed.getOrDefault(c.getId(), 0L)));
        }
        return rows;
    }

    /** Hoy solo el profesional (Equipo real en T28). */
    private List<List<Object>> team(LocalDate from, LocalDate to) {
        AuthUser me = access.current();
        ServicesDto s = business.services(from, to);
        ClientsDto c = business.clientsData(from, to, "TOTAL");
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Profesional", "Clientes activos", "Actividades asignadas", "Cumplimiento medio (%)"));
        rows.add(Arrays.asList(users.findById(me.id()).map(AppUser::getFullName).orElse("—"), c.kpis().totalClients(),
                s.kpis().assignedActivities(), s.kpis().avgCompliance()));
        return rows;
    }

    /** Invitaciones por email no aceptadas y leads del rango: para volver a contactar. */
    private List<List<Object>> signups(LocalDate from, LocalDate to) {
        Long me = access.current().id();
        boolean admin = access.current().role() == Role.ADMIN;
        Map<Long, String> tagNames = tags.findAll().stream().collect(Collectors.toMap(ClientTag::getId, ClientTag::getName));
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Nombre", "Email", "Teléfono", "Origen", "Fecha", "Etiqueta"));
        List<ClientInvitation> inv = admin ? invitations.findByEmailIsNotNullAndAcceptedAtIsNullOrderByInvitedAtDesc()
                : invitations.findByTrainerIdAndEmailIsNotNullAndAcceptedAtIsNullOrderByInvitedAtDesc(me);
        for (ClientInvitation i : inv) {
            LocalDate d = i.getInvitedAt().toLocalDate();
            if (d.isBefore(from) || d.isAfter(to)) continue;
            rows.add(Arrays.asList(i.getName(), i.getEmail(), null, "Invitación", d, i.getTagId() == null ? null : tagNames.get(i.getTagId())));
        }
        for (ClientLead l : admin ? leads.findAllByOrderByRegisteredAtDesc() : leads.findByTrainerIdOrderByRegisteredAtDesc(me)) {
            LocalDate d = l.getRegisteredAt().toLocalDate();
            if (d.isBefore(from) || d.isAfter(to)) continue;
            rows.add(Arrays.asList(l.getName(), l.getEmail(), l.getPhone(), "Lead (" + l.getSource().toLowerCase(Locale.ROOT) + ")", d, null));
        }
        return rows;
    }
}
