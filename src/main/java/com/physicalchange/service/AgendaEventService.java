package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.util.DateTimes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Eventos de la agenda, reservas, KPIs y cumpleaños (docs/api-agenda.md, ADR-002).
 * Las series recurrentes son filas con el mismo seriesKey; editar/borrar admite scope ONE | FOLLOWING.
 */
@Service
public class AgendaEventService {
    /** Estados que ocupan plaza. */
    static final Set<String> OCCUPY = Set.of("BOOKED", "ATTENDED", "NO_SHOW");
    static final int MAX_OCCURRENCES = 200;

    private final AgendaEventRepository events;
    private final AgendaBookingRepository bookings;
    private final SessionTypeService types;
    private final AgendaActivityService activity;
    private final FileService files;
    private final TagRefs tags;
    private final UserRepository users;
    private final ClientProfileRepository profiles;
    private final ClientDirectory directory;
    private final AccessService access;
    private final BonusService bonusService;

    public AgendaEventService(AgendaEventRepository e, AgendaBookingRepository b, SessionTypeService t, AgendaActivityService a,
                              FileService f, TagRefs tr, UserRepository u, ClientProfileRepository p, ClientDirectory d, AccessService ac,
                              BonusService bs) {
        events = e; bookings = b; types = t; activity = a; files = f; tags = tr; users = u; profiles = p; directory = d; access = ac;
        bonusService = bs;
    }

    /** Filtros opcionales de la lista y de los KPIs (vacío o nulo = sin filtrar). */
    public record Filters(Set<String> kinds, Set<String> modes, Set<Long> sessionTypeIds, Set<String> bookable, Boolean recurrent) {
        public static Filters none() { return new Filters(Set.of(), Set.of(), Set.of(), Set.of(), null); }
        boolean test(AgendaEvent e) {
            return (kinds.isEmpty() || kinds.contains(e.getKind()))
                    && (modes.isEmpty() || modes.contains(e.getMode()))
                    && (sessionTypeIds.isEmpty() || sessionTypeIds.contains(e.getSessionTypeId()))
                    && (bookable.isEmpty() || bookable.contains(e.getBookable()))
                    && (recurrent == null || recurrent == (e.getSeriesKey() != null));
        }
    }

    // ---------- consulta ----------

    @Transactional(readOnly = true)
    public List<EventDto> list(LocalDate from, LocalDate to, Filters f) { return toDtos(range(from, to, f)); }

    @Transactional(readOnly = true)
    public EventDetailDto get(Long id) {
        AgendaEvent e = owned(id);
        Map<Long, String> names = new HashMap<>();
        List<BookingDto> list = bookings.findByEventIdOrderByCreatedAtAsc(id).stream().map(b -> toDto(b, names)).toList();
        return new EventDetailDto(toDtos(List.of(e)).get(0), list);
    }

    @Transactional(readOnly = true)
    public AgendaStatsDto stats(LocalDate from, LocalDate to, Filters f) {
        List<AgendaEvent> sessions = range(from, to, f).stream().filter(e -> "SESSION".equals(e.getKind())).toList();
        long inPerson = sessions.stream().filter(e -> "IN_PERSON".equals(e.getMode())).count();
        List<AgendaBooking> all = sessions.isEmpty() ? List.of() : bookings.findByEventIdIn(sessions.stream().map(AgendaEvent::getId).toList());
        Map<String, Long> byStatus = all.stream().collect(Collectors.groupingBy(AgendaBooking::getStatus, Collectors.counting()));
        long vouchers = bonusService.redeemedIn(all.stream().map(AgendaBooking::getId).toList());
        long attended = byStatus.getOrDefault("ATTENDED", 0L);
        long booked = byStatus.getOrDefault("BOOKED", 0L) + attended + byStatus.getOrDefault("NO_SHOW", 0L);
        int pct = booked == 0 ? 0 : (int) Math.round(attended * 100.0 / booked);
        return new AgendaStatsDto(sessions.size(), inPerson, sessions.size() - inPerson, booked,
                byStatus.getOrDefault("CANCELLED", 0L), byStatus.getOrDefault("WAITLIST", 0L), attended, pct, vouchers);
    }

    /** Cumpleaños de los clientes activos del profesional en el rango (29-feb cae el 28 en años no bisiestos). */
    @Transactional(readOnly = true)
    public List<BirthdayDto> birthdays(LocalDate from, LocalDate to) {
        checkRange(from, to);
        List<AppUser> clients = directory.clientsOf(access.current().id()).stream().filter(AppUser::isActive).toList();
        if (clients.isEmpty()) return List.of();
        Map<Long, String> names = clients.stream().collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
        List<BirthdayDto> out = new ArrayList<>();
        for (ClientProfile p : profiles.findByClientIdIn(names.keySet())) {
            if (p.getBirthDate() == null || p.getRemovedAt() != null) continue;
            MonthDay md = MonthDay.from(p.getBirthDate());
            for (int y = from.getYear(); y <= to.getYear(); y++) {
                LocalDate d = md.atYear(y);
                if (!d.isBefore(from) && !d.isAfter(to)) out.add(new BirthdayDto(p.getClientId(), names.get(p.getClientId()), d));
            }
        }
        out.sort(Comparator.comparing(BirthdayDto::date));
        return out;
    }

    /** Sesiones en las que el cliente tiene plaza (calendario del cliente, /api/calendar). */
    @Transactional(readOnly = true)
    public List<AgendaEvent> bookedByClient(Long clientId, LocalDate from, LocalDate to) {
        return events.findBookedByClient(clientId, List.of("BOOKED", "ATTENDED"), DateTimes.startOfDay(from), DateTimes.endExclusive(to));
    }

    // ---------- alta, edición y borrado ----------

    @Transactional
    public List<EventDto> create(EventRequest r) {
        Long me = access.current().id();
        checkTimes(r);
        List<LocalDate> dates = occurrences(r);
        String series = r.recurrence() != null ? UUID.randomUUID().toString() : null;
        List<Long> clientIds = "SESSION".equals(r.kind()) && r.clientIds() != null ? r.clientIds().stream().distinct().toList() : List.of();
        clientIds.forEach(access::assertCanAccessClient);

        List<AgendaEvent> created = new ArrayList<>();
        for (LocalDate d : dates) {
            AgendaEvent e = new AgendaEvent();
            e.setTrainerId(me);
            apply(e, r, me);
            e.setStartAt(d.atTime(r.startTime()));
            e.setEndAt(d.atTime(r.endTime()));
            e.setSeriesKey(series);
            e = events.save(e);
            for (Long c : clientIds) {
                AgendaBooking b = new AgendaBooking();
                b.setEventId(e.getId());
                b.setClientId(c);
                b.setStatus(hasSeat(e) ? "BOOKED" : "WAITLIST");
                bookings.saveAndFlush(b);
                if ("BOOKED".equals(b.getStatus())) bonusService.redeem(b, e);
            }
            created.add(e);
        }
        activity.log(created.get(0), "EVENT_CREATED");
        return toDtos(created);
    }

    /** FOLLOWING: esta ocurrencia y las siguientes de la serie (conservan su día; cambian datos y horas). */
    @Transactional
    public List<EventDto> update(Long id, String scope, EventRequest r) {
        AgendaEvent e = owned(id);
        checkTimes(r);
        List<AgendaEvent> targets = targets(e, scope);
        for (AgendaEvent t : targets) {
            LocalDate day = targets.size() == 1 ? r.date() : t.getStartAt().toLocalDate();
            apply(t, r, e.getTrainerId());
            t.setStartAt(day.atTime(r.startTime()));
            t.setEndAt(day.atTime(r.endTime()));
            if (!"SESSION".equals(t.getKind())) { releaseBonuses(t); bookings.deleteByEventId(t.getId()); }
        }
        activity.log(e, "EVENT_UPDATED");
        return toDtos(targets);
    }

    @Transactional
    public void delete(Long id, String scope) {
        AgendaEvent e = owned(id);
        activity.log(e, "EVENT_DELETED");
        for (AgendaEvent t : targets(e, scope)) {
            releaseBonuses(t);
            bookings.deleteByEventId(t.getId());
            events.delete(t);
        }
    }

    // ---------- reservas ----------

    @Transactional
    public BookingDto book(Long eventId, BookingRequest r) {
        AgendaEvent e = events.findById(eventId).orElseThrow(() -> new NotFoundException("Evento no encontrado"));
        if (!"SESSION".equals(e.getKind())) throw new BadRequestException("Solo se pueden reservar sesiones");
        AuthUser me = access.current();
        if (me.role() == Role.CLIENT) assertClientCanBook(me, e, r.clientId());
        else { access.assertOwnerOrAdmin(e.getTrainerId()); access.assertCanAccessClient(r.clientId()); }

        AgendaBooking b = bookings.findByEventIdAndClientId(eventId, r.clientId()).orElse(null);
        if (b != null && !"CANCELLED".equals(b.getStatus())) throw new ConflictException("El cliente ya tiene reserva en esta sesión");
        if (b == null) { b = new AgendaBooking(); b.setEventId(eventId); b.setClientId(r.clientId()); }
        b.setStatus(hasSeat(e) ? "BOOKED" : "WAITLIST");
        b = bookings.saveAndFlush(b);
        if ("BOOKED".equals(b.getStatus())) bonusService.redeem(b, e);
        activity.log(e, "BOOKED".equals(b.getStatus()) ? "BOOKED" : "WAITLISTED");
        return toDto(b, new HashMap<>());
    }

    /** El profesional cambia cualquier estado; el cliente solo cancela la suya. Al liberar plaza, entra el primero en cola. */
    @Transactional
    public BookingDto setStatus(Long bookingId, BookingStatusRequest r) {
        AgendaBooking b = bookings.findById(bookingId).orElseThrow(() -> new NotFoundException("Reserva no encontrada"));
        AgendaEvent e = events.findById(b.getEventId()).orElseThrow(() -> new NotFoundException("Evento no encontrado"));
        AuthUser me = access.current();
        if (me.role() == Role.CLIENT) {
            if (!me.id().equals(b.getClientId())) throw new ForbiddenException("Reserva de otro cliente");
            if (!"CANCELLED".equals(r.status())) throw new ForbiddenException("Solo puedes cancelar tu reserva");
        } else {
            access.assertOwnerOrAdmin(e.getTrainerId());
        }
        String old = b.getStatus();
        if (old.equals(r.status())) return toDto(b, new HashMap<>());
        if (OCCUPY.contains(r.status()) && !OCCUPY.contains(old) && !hasSeat(e)) throw new ConflictException("No quedan plazas en esta sesión");
        b.setStatus(r.status());
        bookings.saveAndFlush(b);
        if (OCCUPY.contains(r.status()) && !OCCUPY.contains(old)) bonusService.redeem(b, e);
        if (OCCUPY.contains(old) && !OCCUPY.contains(r.status())) { bonusService.release(b, e); promote(e); }
        activity.log(e, switch (r.status()) {
            case "CANCELLED" -> "BOOKING_CANCELLED";
            case "ATTENDED" -> "ATTENDED";
            case "NO_SHOW" -> "NO_SHOW";
            default -> "BOOKED";
        });
        return toDto(b, new HashMap<>());
    }

    // ---------- internos ----------

    private AgendaEvent owned(Long id) {
        AgendaEvent e = events.findById(id).orElseThrow(() -> new NotFoundException("Evento no encontrado"));
        access.assertOwnerOrAdmin(e.getTrainerId());
        return e;
    }

    private List<AgendaEvent> range(LocalDate from, LocalDate to, Filters f) {
        checkRange(from, to);
        return events.findByTrainerIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(
                access.current().id(), DateTimes.startOfDay(from), DateTimes.endExclusive(to)).stream().filter(f::test).toList();
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || from.plusDays(400).isBefore(to)) throw new BadRequestException("Rango de fechas no válido");
    }

    private static void checkTimes(EventRequest r) {
        if (!r.endTime().isAfter(r.startTime())) throw new BadRequestException("La hora de fin debe ser posterior a la de inicio");
    }

    private List<AgendaEvent> targets(AgendaEvent e, String scope) {
        if ("FOLLOWING".equals(scope) && e.getSeriesKey() != null) {
            return events.findBySeriesKeyAndStartAtGreaterThanEqualOrderByStartAtAsc(e.getSeriesKey(), e.getStartAt());
        }
        if (scope != null && !"ONE".equals(scope) && !"FOLLOWING".equals(scope)) throw new BadRequestException("scope debe ser ONE o FOLLOWING");
        return List.of(e);
    }

    /** Días de la serie: los días de la semana elegidos entre date y until (máx. 1 año y 200 ocurrencias). */
    private static List<LocalDate> occurrences(EventRequest r) {
        if (r.recurrence() == null) return List.of(r.date());
        if (!"SESSION".equals(r.kind()) && !"MEETING".equals(r.kind())) throw new BadRequestException("Solo las sesiones y reuniones pueden repetirse");
        LocalDate until = r.recurrence().until();
        if (until.isBefore(r.date()) || until.isAfter(r.date().plusYears(1))) throw new BadRequestException("La repetición debe terminar en el plazo de un año");
        Set<Integer> days = new HashSet<>(r.recurrence().weekdays());
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = r.date(); !d.isAfter(until); d = d.plusDays(1)) if (days.contains(d.getDayOfWeek().getValue())) out.add(d);
        if (out.isEmpty()) throw new BadRequestException("Ningún día de la repetición cae en el rango elegido");
        if (out.size() > MAX_OCCURRENCES) throw new BadRequestException("Una serie admite como máximo " + MAX_OCCURRENCES + " eventos");
        return out;
    }

    /** Copia los datos validados del formulario según el tipo de evento. */
    private void apply(AgendaEvent e, EventRequest r, Long trainerId) {
        String kind = r.kind();
        boolean session = "SESSION".equals(kind), withMode = session || "MEETING".equals(kind);
        if (withMode && r.mode() == null) throw new BadRequestException("Elige el modo (presencial u online)");
        if ("PRIVATE".equals(kind) && r.showAs() == null) throw new BadRequestException("Elige cómo se muestra a los demás");
        e.setKind(kind);
        e.setTitle(r.title().trim());
        e.setEmoji(blank(r.emoji()));
        e.setDescription(blank(r.description()));
        e.setMode(withMode ? r.mode() : null);
        e.setLocation(blank(r.location()));
        e.setMeetingUrl("ONLINE".equals(e.getMode()) ? blank(r.meetingUrl()) : null);
        e.setShowAs("PRIVATE".equals(kind) ? r.showAs() : null);
        e.getTagIds().clear();
        if (session) {
            if (r.sessionTypeId() == null) throw new BadRequestException("Elige el tipo de sesión");
            e.setSessionTypeId(types.ownedForRequest(r.sessionTypeId(), trainerId).getId());
            e.setCover(files.checkCover(r.cover(), true));
            e.setCapacity(r.capacity());
            e.setBookable(r.bookable());
            if ("TAGS".equals(r.bookable())) e.getTagIds().addAll(tags.own(r.bookableTagIds(), trainerId, true));
        } else {
            e.setSessionTypeId(null);
            e.setCover(null);
            e.setCapacity(null);
            e.setBookable("NO");
        }
    }

    private void assertClientCanBook(AuthUser me, AgendaEvent e, Long clientId) {
        if (!me.id().equals(clientId)) throw new ForbiddenException("Solo puedes reservar para ti");
        AppUser c = users.findById(me.id()).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
        if (!directory.isClientOf(c, e.getTrainerId())) throw new ForbiddenException("Sesión de otro profesional");
        if ("NO".equals(e.getBookable())) throw new ForbiddenException("Esta sesión no admite reservas");
        if ("TAGS".equals(e.getBookable())) {
            Set<Long> mine = profiles.findById(me.id()).map(ClientProfile::getTagIds).orElse(Set.of());
            if (Collections.disjoint(mine, e.getTagIds())) throw new ForbiddenException("Esta sesión no está disponible para ti");
        }
        if (e.getStartAt().isBefore(LocalDateTime.now())) throw new BadRequestException("La sesión ya ha empezado");
    }

    /** Al borrar una sesión futura (o dejar de ser sesión), sus reservas devuelven las sesiones de bono. */
    private void releaseBonuses(AgendaEvent e) {
        for (AgendaBooking b : bookings.findByEventIdIn(List.of(e.getId()))) bonusService.release(b, e);
    }

    private boolean hasSeat(AgendaEvent e) {
        return e.getCapacity() == null || bookings.countByEventIdAndStatusIn(e.getId(), OCCUPY) < e.getCapacity();
    }

    private void promote(AgendaEvent e) {
        if (!hasSeat(e)) return;
        bookings.findFirstByEventIdAndStatusOrderByCreatedAtAscIdAsc(e.getId(), "WAITLIST").ifPresent(w -> {
            w.setStatus("BOOKED");
            bookings.saveAndFlush(w);
            bonusService.redeem(w, e);
        });
    }

    private List<EventDto> toDtos(List<AgendaEvent> list) {
        if (list.isEmpty()) return List.of();
        Map<Long, SessionType> typeById = types.byIds(list.stream().map(AgendaEvent::getSessionTypeId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, long[]> counts = new HashMap<>();   // [ocupan plaza, en cola]
        for (AgendaBooking b : bookings.findByEventIdIn(list.stream().map(AgendaEvent::getId).toList())) {
            long[] c = counts.computeIfAbsent(b.getEventId(), k -> new long[2]);
            if (OCCUPY.contains(b.getStatus())) c[0]++;
            else if ("WAITLIST".equals(b.getStatus())) c[1]++;
        }
        Map<Long, String> names = new HashMap<>();
        return list.stream().map(e -> {
            SessionType t = e.getSessionTypeId() == null ? null : typeById.get(e.getSessionTypeId());
            long[] c = counts.getOrDefault(e.getId(), new long[2]);
            return new EventDto(e.getId(), e.getKind(), e.getTitle(), e.getEmoji(), e.getDescription(), e.getMode(), e.getLocation(),
                    e.getMeetingUrl(), e.getShowAs(), e.getSessionTypeId(), t == null ? null : t.getName(), t == null ? null : t.getColor(),
                    e.getCover(), files.coverUrl(e.getCover()), e.getStartAt(), e.getEndAt(), e.getSeriesKey() != null, e.getSeriesKey(),
                    e.getCapacity(), c[0], c[1], e.getBookable(), new ArrayList<>(e.getTagIds()), e.getTrainerId(), nameOf(names, e.getTrainerId()));
        }).toList();
    }

    private BookingDto toDto(AgendaBooking b, Map<Long, String> names) {
        return new BookingDto(b.getId(), b.getEventId(), b.getClientId(), nameOf(names, b.getClientId()), b.getStatus(), b.getCreatedAt());
    }

    private String nameOf(Map<Long, String> cache, Long userId) {
        return cache.computeIfAbsent(userId, id -> users.findById(id).map(AppUser::getFullName).orElse("-"));
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
