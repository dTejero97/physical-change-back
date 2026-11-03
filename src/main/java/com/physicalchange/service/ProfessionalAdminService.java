package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.UserRepository;
import com.physicalchange.security.AccessService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Profesionales (rol TRAINER) que da de alta el ADMIN (T228, docs/api-profesionales.md): entrenador, nutricionista o ambos.
 * Es la única forma de crear un profesional: nadie puede registrarse como tal (decisión del usuario 2026-10-28).
 * El usuario de acceso va en el campo email, como "admin": un email o un nombre corto en minúsculas.
 */
@Service
public class ProfessionalAdminService {
    /** Nombres que no se pueden usar como usuario. */
    private static final List<String> RESERVED = List.of("admin", "administrador", "root", "soporte", "support");

    private final UserRepository users;
    private final PasswordEncoder enc;
    private final AccessService access;

    public ProfessionalAdminService(UserRepository u, PasswordEncoder e, AccessService a) { users = u; enc = e; access = a; }

    @Transactional(readOnly = true)
    public List<ProfessionalAdminDto> list() {
        requireAdmin();
        return users.findByRole(Role.TRAINER).stream().sorted(Comparator.comparing(AppUser::getFullName, String.CASE_INSENSITIVE_ORDER))
                .map(this::dto).toList();
    }

    @Transactional
    public ProfessionalAdminDto create(ProfessionalCreateRequest r) {
        requireAdmin();
        String username = r.username().trim().toLowerCase(Locale.ROOT);
        if (RESERVED.contains(username)) throw new BadRequestException("Ese nombre de usuario está reservado");
        if (!r.training() && !r.nutrition()) throw new BadRequestException("Elige al menos entrenamiento o nutrición");
        if (users.findByEmailIgnoreCase(username).isPresent()) throw new ConflictException("Ya existe un usuario con ese nombre o email");
        AppUser u = new AppUser();
        u.setEmail(username);
        u.setFullName(r.fullName().strip());
        u.setPasswordHash(enc.encode(r.password()));
        u.setRole(Role.TRAINER);
        u.setTrainingEnabled(r.training());
        u.setNutritionEnabled(r.nutrition());
        return dto(users.save(u));
    }

    /** Nombre, servicios y activo. Desactivar cierra sus sesiones; sus clientes y datos se conservan. */
    @Transactional
    public ProfessionalAdminDto update(Long id, ProfessionalUpdateRequest r) {
        requireAdmin();
        AppUser u = professional(id);
        if (!r.training() && !r.nutrition()) throw new BadRequestException("Elige al menos entrenamiento o nutrición");
        u.setFullName(r.fullName().strip());
        u.setTrainingEnabled(r.training());
        u.setNutritionEnabled(r.nutrition());
        u.setActive(r.active());
        return dto(u);
    }

    /** Contraseña nueva (p. ej. la ha olvidado): cierra todas sus sesiones. */
    @Transactional
    public void setPassword(Long id, ProfessionalPasswordRequest r) {
        requireAdmin();
        professional(id).setPasswordHash(enc.encode(r.password()));
    }

    private AppUser professional(Long id) {
        return users.findById(id).filter(u -> u.getRole() == Role.TRAINER).orElseThrow(() -> new NotFoundException("Profesional no encontrado"));
    }

    private void requireAdmin() {
        if (access.current().role() != Role.ADMIN) throw new ForbiddenException("Solo el administrador gestiona los profesionales");
    }

    private ProfessionalAdminDto dto(AppUser u) {
        return new ProfessionalAdminDto(u.getId(), u.getEmail(), u.getFullName(), u.isTrainingEnabled(), u.isNutritionEnabled(), u.isActive(),
                users.findByRoleAndTrainerId(Role.CLIENT, u.getId()).size());
    }
}
