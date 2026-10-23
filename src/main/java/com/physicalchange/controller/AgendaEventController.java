package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.AgendaEventService;
import com.physicalchange.service.AgendaEventService.Filters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

/** Eventos, reservas, KPIs y cumpleaños (docs/api-agenda.md). Las reservas también las usa el cliente. */
@RestController @RequestMapping("/api/agenda") @Tag(name = "Agenda")
public class AgendaEventController {
    private static final String TRAINER = "hasAnyRole('ADMIN','TRAINER')";
    private final AgendaEventService svc;
    public AgendaEventController(AgendaEventService s) { svc = s; }

    @GetMapping("/events") @PreAuthorize(TRAINER)
    public List<EventDto> list(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(required = false) Set<String> kinds, @RequestParam(required = false) Set<String> modes,
                               @RequestParam(required = false) Set<Long> sessionTypeIds, @RequestParam(required = false) Set<String> bookable,
                               @RequestParam(required = false) Boolean recurrent) {
        return svc.list(from, to, filters(kinds, modes, sessionTypeIds, bookable, recurrent));
    }

    @GetMapping("/stats") @PreAuthorize(TRAINER)
    public AgendaStatsDto stats(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(required = false) Set<String> kinds, @RequestParam(required = false) Set<String> modes,
                                @RequestParam(required = false) Set<Long> sessionTypeIds, @RequestParam(required = false) Set<String> bookable,
                                @RequestParam(required = false) Boolean recurrent) {
        return svc.stats(from, to, filters(kinds, modes, sessionTypeIds, bookable, recurrent));
    }

    @GetMapping("/birthdays") @PreAuthorize(TRAINER)
    public List<BirthdayDto> birthdays(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return svc.birthdays(from, to);
    }

    @GetMapping("/events/{id}") @PreAuthorize(TRAINER) public EventDetailDto get(@PathVariable Long id) { return svc.get(id); }

    @PostMapping("/events") @PreAuthorize(TRAINER) @ResponseStatus(HttpStatus.CREATED)
    public List<EventDto> create(@Valid @RequestBody EventRequest r) { return svc.create(r); }

    @PutMapping("/events/{id}") @PreAuthorize(TRAINER)
    public List<EventDto> update(@PathVariable Long id, @RequestParam(defaultValue = "ONE") String scope, @Valid @RequestBody EventRequest r) {
        return svc.update(id, scope, r);
    }

    @DeleteMapping("/events/{id}") @PreAuthorize(TRAINER) @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @RequestParam(defaultValue = "ONE") String scope) { svc.delete(id, scope); }

    /** Profesional (para cualquier cliente suyo) o el propio cliente si la sesión es reservable para él. */
    @PostMapping("/events/{id}/bookings") @ResponseStatus(HttpStatus.CREATED)
    public BookingDto book(@PathVariable Long id, @Valid @RequestBody BookingRequest r) { return svc.book(id, r); }

    @PatchMapping("/bookings/{id}")
    public BookingDto setStatus(@PathVariable Long id, @Valid @RequestBody BookingStatusRequest r) { return svc.setStatus(id, r); }

    private static Filters filters(Set<String> kinds, Set<String> modes, Set<Long> types, Set<String> bookable, Boolean recurrent) {
        return new Filters(orEmpty(kinds), orEmpty(modes), orEmpty(types), orEmpty(bookable), recurrent);
    }

    private static <T> Set<T> orEmpty(Set<T> s) { return s == null ? Set.of() : s; }
}
