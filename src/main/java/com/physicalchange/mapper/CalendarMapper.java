package com.physicalchange.mapper;

import com.physicalchange.domain.AgendaEvent;
import com.physicalchange.domain.Appointment;
import com.physicalchange.domain.Workout;
import com.physicalchange.domain.WorkoutAssignment;
import com.physicalchange.dto.Dtos.CalendarEventDto;
import java.time.LocalDateTime;

public final class CalendarMapper {
    private CalendarMapper() {}

    public static CalendarEventDto fromAppointment(Appointment a, String clientName) {
        return new CalendarEventDto("APPOINTMENT", a.getId(), a.getTitle() == null ? "Cita" : a.getTitle(),
                a.getStartAt(), a.getEndAt(), a.getClientId(), clientName, a.getStatus());
    }

    /** Sesión de la agenda en la que el cliente tiene plaza. */
    public static CalendarEventDto fromEvent(AgendaEvent e, Long clientId, String clientName) {
        String title = e.getEmoji() == null ? e.getTitle() : e.getEmoji() + " " + e.getTitle();
        return new CalendarEventDto("EVENT", e.getId(), title, e.getStartAt(), e.getEndAt(), clientId, clientName, "BOOKED");
    }

    /** Una rutina asignada ocupa el día entero en el calendario. */
    public static CalendarEventDto fromAssignment(WorkoutAssignment a, Workout w, String clientName) {
        LocalDateTime start = a.getScheduledDate().atStartOfDay();
        String status = a.getCompletedDate() == null ? "PENDING" : "COMPLETED";
        return new CalendarEventDto("WORKOUT", a.getId(), w.getName(), start, start.plusDays(1).minusNanos(1),
                a.getClientId(), clientName, status);
    }
}
