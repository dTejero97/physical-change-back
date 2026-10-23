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

    private boolean isAdmin(Long userId) {
        return users.findById(userId).map(u -> u.getRole() == Role.ADMIN).orElse(false);
    }
}
