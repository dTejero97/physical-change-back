package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.mapper.CalendarMapper;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.util.DateTimes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;

/** Calendario unificado: citas y rutinas asignadas en un rango de fechas. */
@Service
public class CalendarService {
    private final AppointmentRepository appointments;
    private final WorkoutAssignmentRepository assignments;
    private final WorkoutRepository workouts;
    private final UserRepository users;
    private final AccessService access;

    public CalendarService(AppointmentRepository a, WorkoutAssignmentRepository w, WorkoutRepository wo,
                           UserRepository u, AccessService ac) {
        appointments = a; assignments = w; workouts = wo; users = u; access = ac;
    }

    @Transactional(readOnly = true)
    public List<CalendarEventDto> events(LocalDate from, LocalDate to) {
        AuthUser me = access.current();
        Map<Long, String> names = new HashMap<>();
        List<CalendarEventDto> out = new ArrayList<>();

        if (me.role() == Role.CLIENT) {
            appointments.findByClientIdAndStartAtBetween(me.id(), DateTimes.startOfDay(from), DateTimes.endExclusive(to))
                    .forEach(a -> out.add(CalendarMapper.fromAppointment(a, nameOf(names, a.getClientId()))));
            for (WorkoutAssignment a : assignments.findByClientIdAndScheduledDateBetween(me.id(), from, to)) {
                Workout w = workouts.findById(a.getWorkoutId()).orElseThrow();
                out.add(CalendarMapper.fromAssignment(a, w, nameOf(names, a.getClientId())));
            }
        } else {
            appointments.findByTrainerIdAndStartAtBetween(me.id(), DateTimes.startOfDay(from), DateTimes.endExclusive(to))
                    .forEach(a -> out.add(CalendarMapper.fromAppointment(a, nameOf(names, a.getClientId()))));
            for (WorkoutAssignment a : assignments.findByTrainer(me.id())) {
                if (a.getScheduledDate().isBefore(from) || a.getScheduledDate().isAfter(to)) continue;
                Workout w = workouts.findById(a.getWorkoutId()).orElseThrow();
                out.add(CalendarMapper.fromAssignment(a, w, nameOf(names, a.getClientId())));
            }
        }
        out.sort(Comparator.comparing(CalendarEventDto::start));
        return out;
    }

    private String nameOf(Map<Long, String> cache, Long userId) {
        return cache.computeIfAbsent(userId, id -> users.findById(id).map(AppUser::getFullName).orElse("-"));
    }
}
