package com.physicalchange.service;

import com.physicalchange.domain.ClientDailyLog;
import com.physicalchange.domain.ProgressEntry;
import com.physicalchange.domain.Role;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.BadRequestException;
import com.physicalchange.exception.ConflictException;
import com.physicalchange.exception.NotFoundException;
import com.physicalchange.repository.ClientDailyLogRepository;
import com.physicalchange.repository.UserRepository;
import com.physicalchange.security.AccessService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Diario del cliente (T201, docs/api-cliente-seguimiento.md): sueño, ánimo y descanso en client_daily_log; el peso del día
 * vive en progress_entry. "Hoy" en la zona de la plataforma (ADR-008). El cliente escribe lo suyo; el profesional solo lee.
 */
@Service
public class DailyLogService {
    static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);

    private final ClientDailyLogRepository logs;
    private final ProgressService progress;
    private final AccessService access;
    private final Clock clock;
    private final UserRepository users;

    public DailyLogService(ClientDailyLogRepository l, ProgressService p, AccessService a, Clock c, UserRepository u) {
        logs = l; progress = p; access = a; clock = c; users = u;
    }

    // ---------- cliente (siempre el del token) ----------

    @Transactional(readOnly = true)
    public DailyLogDto mine(LocalDate date) { return day(access.current().id(), date); }

    @Transactional(readOnly = true)
    public DailyLogMonthDto myMonth(String month) { return month(access.current().id(), month); }

    @Transactional
    public DailyLogDto save(LocalDate date, DailyLogRequest r) {
        Long clientId = access.current().id();
        LocalDate today = LocalDate.now(clock);
        if (date.isAfter(today)) throw new BadRequestException("No se puede registrar un día futuro");
        if (date.isBefore(MIN_DATE)) throw new BadRequestException("Fecha no válida");

        if (r.weightKg() != null) progress.recordWeight(clientId, date, r.weightKg());
        boolean rest = Boolean.TRUE.equals(r.restDay());
        ClientDailyLog log = logs.findByClientIdAndLogDate(clientId, date).orElse(null);
        if (r.sleepMinutes() == null && r.mood() == null && !rest) {
            if (log != null) logs.delete(log);   // sin datos no se guarda una fila vacía
        } else {
            LocalDateTime now = LocalDateTime.now(clock);
            if (log == null) { log = new ClientDailyLog(); log.setClientId(clientId); log.setLogDate(date); log.setCreatedAt(now); }
            log.setSleepMinutes(r.sleepMinutes()); log.setMood(r.mood()); log.setRestDay(rest); log.setUpdatedAt(now);
            try {
                logs.saveAndFlush(log);
            } catch (DataIntegrityViolationException ex) {   // otro guardado del mismo día a la vez
                throw new ConflictException("Tu día se está guardando a la vez; vuelve a intentarlo");
            }
        }
        return day(clientId, date);
    }

    // ---------- profesional (solo lectura, mismo permiso que el peso) ----------

    @Transactional(readOnly = true)
    public DailyLogDto forClient(Long clientId, LocalDate date) {
        assertProfessionalCanRead(clientId);
        return day(clientId, date);
    }

    @Transactional(readOnly = true)
    public DailyLogMonthDto monthForClient(Long clientId, String month) {
        assertProfessionalCanRead(clientId);
        return month(clientId, month);
    }

    /** Permiso del peso, más 404 si no es un cliente: assertCanAccessClient deja pasar al ADMIN sin buscarlo (QA T214). */
    private void assertProfessionalCanRead(Long clientId) {
        access.assertCanAccessClient(clientId);
        users.findById(clientId).filter(u -> u.getRole() == Role.CLIENT).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
    }

    // ---------- internos ----------

    private DailyLogDto day(Long clientId, LocalDate date) {
        ProgressEntry p = progress.ofDay(clientId, date);
        return dto(date, logs.findByClientIdAndLogDate(clientId, date).orElse(null), p == null ? null : p.getWeightKg());
    }

    private DailyLogMonthDto month(Long clientId, String month) {
        YearMonth ym = parseMonth(month);
        LocalDate first = ym.atDay(1), last = ym.atEndOfMonth();
        Map<LocalDate, ClientDailyLog> byDay = logs.findByClientIdAndLogDateBetween(clientId, first, last).stream()
                .collect(Collectors.toMap(ClientDailyLog::getLogDate, Function.identity()));
        Map<LocalDate, BigDecimal> weights = new HashMap<>();
        for (ProgressEntry p : progress.between(clientId, first, last)) if (p.getWeightKg() != null) weights.put(p.getEntryDate(), p.getWeightKg());
        List<DailyLogDto> days = new ArrayList<>(ym.lengthOfMonth());
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) days.add(dto(d, byDay.get(d), weights.get(d)));
        return new DailyLogMonthDto(ym.toString(), LocalDate.now(clock), days);
    }

    private static YearMonth parseMonth(String month) {
        try {
            YearMonth ym = YearMonth.parse(month == null ? "" : month.trim());
            if (ym.isBefore(YearMonth.from(MIN_DATE)) || ym.getYear() > 9999) throw new BadRequestException("Mes no válido");
            return ym;
        } catch (DateTimeParseException e) {
            throw new BadRequestException("El mes debe tener el formato yyyy-MM");
        }
    }

    private static DailyLogDto dto(LocalDate date, ClientDailyLog log, BigDecimal weightKg) {
        Integer sleep = log == null ? null : log.getSleepMinutes();
        Integer mood = log == null ? null : log.getMood();
        boolean rest = log != null && log.isRestDay();
        boolean logged = weightKg != null || sleep != null || mood != null || rest;
        return new DailyLogDto(date, weightKg, sleep, mood, rest, logged, log == null ? null : log.getUpdatedAt());
    }
}
