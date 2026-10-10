package com.physicalchange.security;

import com.physicalchange.domain.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Control de propiedad de recursos: la regla real de "quién ve qué". */
@Service
public class AccessService {
    private final UserRepository users;
    public AccessService(UserRepository users) { this.users = users; }

    public AuthUser current() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof AuthUser u)) throw new ForbiddenException("No autenticado");
        return u;
    }

    /** ADMIN: siempre. CLIENT: solo él mismo. TRAINER: solo sus clientes. */
    public void assertCanAccessClient(Long clientId) {
        AuthUser me = current();
        if (me.role() == Role.ADMIN) return;
        if (me.role() == Role.CLIENT) {
            if (!me.id().equals(clientId)) throw new ForbiddenException("Recurso de otro cliente");
            return;
        }
        AppUser c = users.findById(clientId).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
        if (c.getRole() != Role.CLIENT || !me.id().equals(c.getTrainerId())) throw new ForbiddenException("No es tu cliente");
    }

    public void assertOwnerOrAdmin(Long ownerId) {
        AuthUser me = current();
        if (me.role() != Role.ADMIN && !me.id().equals(ownerId)) throw new ForbiddenException("No eres el propietario");
    }
}
