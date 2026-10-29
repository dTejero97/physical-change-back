package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.UserRepository;
import com.physicalchange.security.*;
import org.springframework.data.domain.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Comparator;
import java.util.List;

@Service
public class ClientService {
    private final UserRepository users; private final PasswordEncoder enc; private final AccessService access;
    private final ClientProfileService profiles; private final ChangeRequestService changeRequests;
    public ClientService(UserRepository u, PasswordEncoder e, AccessService a, ClientProfileService p, ChangeRequestService cr) {
        users = u; enc = e; access = a; profiles = p; changeRequests = cr;
    }

    /** Clientes del entrenador (todos si es ADMIN), sin los eliminados desde "Archivados". */
    @Transactional(readOnly = true)
    public Page<UserDto> list(Pageable p) {
        AuthUser me = access.current();
        if (me.role() == Role.COLLABORATOR) {
            // Solo sus clientes asignados y activos (ADR-011)
            List<AppUser> mine = users.findAllById(access.assignedClients()).stream()
                    .filter(u -> u.getRole() == Role.CLIENT && u.isActive()).sorted(Comparator.comparing(AppUser::getFullName)).toList();
            int from = (int) Math.min(p.getOffset(), mine.size()), to = Math.min(from + p.getPageSize(), mine.size());
            return new PageImpl<>(mine.subList(from, to), p, mine.size()).map(AuthService::toDto);
        }
        Page<AppUser> page = me.role() == Role.ADMIN ? users.findAllVisibleClients(p) : users.findVisibleClients(me.id(), p);
        return page.map(AuthService::toDto);
    }

    @Transactional(readOnly = true)
    public UserDto get(Long id) {
        access.assertCanAccessClient(id);
        return AuthService.toDto(users.findById(id).orElseThrow(() -> new NotFoundException("Cliente no encontrado")));
    }

    /** Edita nombre y activa/desactiva el acceso de un cliente propio (o cualquiera si es ADMIN). */
    @Transactional
    public UserDto update(Long id, UpdateClientRequest r) {
        access.assertCanAccessClient(id);
        AppUser c = users.findById(id).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
        if (c.getRole() != Role.CLIENT) throw new NotFoundException("Cliente no encontrado");
        c.setFullName(r.fullName());
        c.setActive(r.active());
        return AuthService.toDto(c);
    }

    /**
     * Responsable de nutrición del cliente (ADR-013 §2). Solo ADMIN: da acceso a la nutrición del cliente a otro
     * profesional. null = lo lleva su entrenador, si ofrece nutrición. Las solicitudes pendientes pasan al nuevo.
     */
    @Transactional
    public UserDto setNutritionist(Long id, NutritionistRequest r) {
        AuthUser me = access.current();
        if (me.role() != Role.ADMIN) throw new ForbiddenException("Solo el administrador asigna el responsable de nutrición");
        AppUser c = users.findById(id).filter(u -> u.getRole() == Role.CLIENT).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
        if (r.nutritionistId() != null) {
            users.findById(r.nutritionistId()).filter(p -> p.getRole() == Role.TRAINER && p.isActive() && p.isNutritionEnabled())
                    .orElseThrow(() -> new BadRequestException("El profesional elegido no ofrece nutrición"));
        }
        c.setNutritionistId(r.nutritionistId());
        changeRequests.reassignNutrition(c.getId(), access.nutritionResponsibleOf(c), me.id());
        return AuthService.toDto(c);
    }

    @Transactional
    public UserDto create(CreateClientRequest r) {
        if (users.findByEmailIgnoreCase(r.email()).isPresent()) throw new ConflictException("Email ya registrado");
        AuthUser me = access.current();
        AppUser u = new AppUser();
        u.setEmail(r.email().toLowerCase()); u.setFullName(r.fullName());
        u.setPasswordHash(enc.encode(r.password())); u.setRole(Role.CLIENT);
        u.setTrainerId(me.role() == Role.TRAINER ? me.id() : null);
        u = users.save(u);
        profiles.onClientCreated(u.getId(), u.getTrainerId(), r.tagIds(), null);
        return AuthService.toDto(u);
    }
}
