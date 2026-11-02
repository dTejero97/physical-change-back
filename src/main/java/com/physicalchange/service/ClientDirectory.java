package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.repository.UserRepository;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * Clientes de un profesional. Igual que en ClientProfileService: si el dueño es ADMIN, son todos los clientes
 * (los que crea el administrador quedan sin trainerId).
 */
@Component
public class ClientDirectory {
    private final UserRepository users;
    public ClientDirectory(UserRepository u) { users = u; }

    public List<AppUser> clientsOf(Long ownerId) {
        return isAdmin(ownerId) ? users.findByRole(Role.CLIENT) : users.findByRoleAndTrainerId(Role.CLIENT, ownerId);
    }

    public boolean isClientOf(AppUser client, Long ownerId) {
        return client.getRole() == Role.CLIENT && (ownerId.equals(client.getTrainerId()) || isAdmin(ownerId));
    }

    /**
     * Puede reservar las sesiones de ownerId (docs/api-cliente-seguimiento.md, D17): las de su entrenador; las de un ADMIN
     * solo si no tiene entrenador. isClientOf no sirve aquí: para el ADMIN, todos los clientes son suyos (QA T214).
     */
    public boolean canBookWith(AppUser client, Long ownerId) {
        if (client.getRole() != Role.CLIENT) return false;
        return client.getTrainerId() != null ? client.getTrainerId().equals(ownerId) : isAdmin(ownerId);
    }

    private boolean isAdmin(Long userId) {
        return users.findById(userId).map(u -> u.getRole() == Role.ADMIN).orElse(false);
    }
}
