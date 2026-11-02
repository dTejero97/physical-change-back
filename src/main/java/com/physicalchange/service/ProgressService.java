package com.physicalchange.service;

import com.physicalchange.domain.ProgressEntry;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.ConflictException;
import com.physicalchange.repository.ProgressEntryRepository;
import com.physicalchange.security.AccessService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Peso y grasa corporal. Un único registro por cliente y día desde V19 (T201, D2): guardar un día ya registrado lo actualiza. */
@Service
public class ProgressService {
    private final ProgressEntryRepository repo; private final AccessService access; private final ApplicationEventPublisher publisher;
    private final Clock clock;
    public ProgressService(ProgressEntryRepository r, AccessService a, ApplicationEventPublisher p, Clock c) {
        repo = r; access = a; publisher = p; clock = c;
    }

    private static ProgressDto dto(ProgressEntry e) { return new ProgressDto(e.getId(), e.getClientId(), e.getEntryDate(), e.getWeightKg(), e.getBodyFatPct(), e.getNotes()); }

    @Transactional(readOnly = true)
    public List<ProgressDto> history(Long clientId) {
        access.assertCanAccessClient(clientId);
        return repo.findByClientIdOrderByEntryDateAsc(clientId).stream().map(ProgressService::dto).toList();
    }

    /** Inserta o actualiza el registro del día: los campos con valor sustituyen a los guardados; los null se conservan. */
    @Transactional
    public ProgressDto add(ProgressRequest r) {
        access.assertCanAccessClient(r.clientId());
        ProgressDto dto = dto(upsertDay(r.clientId(), r.entryDate(), r.weightKg(), r.bodyFatPct(), r.notes()));
        publisher.publishEvent(new ClientActivityEvent(r.clientId()));
        return dto;
    }

    /**
     * Peso del día con una nota (p. ej. el cuestionario inicial): actualiza el registro de hoy o lo crea. La nota solo se pone
     * si el registro no tenía ninguna. Así repetir el guardado no duplica registros. Sustituye a T196/B6 ("registro propio"):
     * desde V19 hay un solo peso por día (T201), el último guardado.
     */
    @Transactional
    public void recordTodayWeight(Long clientId, BigDecimal weightKg, String note) {
        access.assertCanAccessClient(clientId);
        LocalDate today = LocalDate.now(clock);
        ProgressEntry current = repo.findByClientIdAndEntryDate(clientId, today).orElse(null);
        boolean noNote = current == null || current.getNotes() == null || current.getNotes().isBlank();
        upsertDay(clientId, today, weightKg, null, noNote ? note : null);
    }

    /** Peso de los días [from, to] de un cliente. Sin control de acceso: lo hace quien llama. */
    @Transactional(readOnly = true)
    public List<ProgressEntry> between(Long clientId, LocalDate from, LocalDate to) {
        return repo.findByClientIdAndEntryDateBetween(clientId, from, to);
    }

    /** Registro del día de un cliente o null. Sin control de acceso: lo hace quien llama. */
    @Transactional(readOnly = true)
    public ProgressEntry ofDay(Long clientId, LocalDate day) { return repo.findByClientIdAndEntryDate(clientId, day).orElse(null); }

    /** Escribe el peso del día (diario del cliente) y avisa a los logros. Sin control de acceso: lo hace quien llama. */
    @Transactional
    public ProgressEntry recordWeight(Long clientId, LocalDate day, BigDecimal weightKg) {
        ProgressEntry e = upsertDay(clientId, day, weightKg, null, null);
        publisher.publishEvent(new ClientActivityEvent(clientId));
        return e;
    }

    private ProgressEntry upsertDay(Long clientId, LocalDate day, BigDecimal weightKg, BigDecimal bodyFatPct, String notes) {
        ProgressEntry e = repo.findByClientIdAndEntryDate(clientId, day).orElseGet(() -> {
            ProgressEntry n = new ProgressEntry(); n.setClientId(clientId); n.setEntryDate(day); return n;
        });
        if (weightKg != null) e.setWeightKg(weightKg);
        if (bodyFatPct != null) e.setBodyFatPct(bodyFatPct);
        if (notes != null) e.setNotes(notes);
        try {
            return repo.saveAndFlush(e);
        } catch (DataIntegrityViolationException ex) {   // otro guardado del mismo día a la vez (restricción única de V19)
            throw new ConflictException("El registro de ese día se está guardando a la vez; vuelve a intentarlo");
        }
    }
}
