package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppointmentService {
    private final AppointmentRepository repo; private final UserRepository users; private final AccessService access;
    public AppointmentService(AppointmentRepository r, UserRepository u, AccessService a) { repo = r; users = u; access = a; }

    private static AppointmentDto dto(Appointment a) { return new AppointmentDto(a.getId(), a.getTrainerId(), a.getClientId(), a.getStartAt(), a.getEndAt(), a.getStatus(), a.getTitle()); }

    @Transactional(readOnly = true)
    public Page<AppointmentDto> mine(Pageable p) {
        AuthUser me = access.current();
        return (me.role() == Role.CLIENT ? repo.findByClientId(me.id(), p) : repo.findByTrainerId(me.id(), p)).map(AppointmentService::dto);
    }

    /** Reserva: el cliente reserva con su entrenador; el entrenador agenda con sus clientes. */
    @Transactional
    public AppointmentDto book(AppointmentRequest r) {
        AuthUser me = access.current();
        if (!r.endAt().isAfter(r.startAt())) throw new ConflictException("La hora de fin debe ser posterior al inicio");
        Long clientId = me.role() == Role.CLIENT ? me.id() : r.clientId();
        access.assertCanAccessClient(clientId);
        AppUser client = users.findById(clientId).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
        Long trainerId = me.role() == Role.TRAINER ? me.id() : client.getTrainerId();
        if (trainerId == null) throw new ConflictException("El cliente no tiene entrenador asignado");
        if (repo.countOverlaps(trainerId, r.startAt(), r.endAt()) > 0) throw new ConflictException("Horario no disponible");
        Appointment a = new Appointment();
        a.setTrainerId(trainerId); a.setClientId(clientId); a.setStartAt(r.startAt()); a.setEndAt(r.endAt()); a.setTitle(r.title());
        return dto(repo.save(a));
    }

    @Transactional
    public AppointmentDto cancel(Long id) {
        Appointment a = repo.findById(id).orElseThrow(() -> new NotFoundException("Cita no encontrada"));
        AuthUser me = access.current();
        if (me.role() != Role.ADMIN && !me.id().equals(a.getClientId()) && !me.id().equals(a.getTrainerId())) throw new ForbiddenException("Cita ajena");
        a.setStatus("CANCELLED");
        return dto(a);
    }
}
