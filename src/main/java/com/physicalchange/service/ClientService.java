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

@Service
public class ClientService {
    private final UserRepository users; private final PasswordEncoder enc; private final AccessService access;
    public ClientService(UserRepository u, PasswordEncoder e, AccessService a) { users = u; enc = e; access = a; }

    @Transactional(readOnly = true)
    public Page<UserDto> list(Pageable p) {
        AuthUser me = access.current();
        Page<AppUser> page = me.role() == Role.ADMIN ? users.findByRole(Role.CLIENT, p)
                : users.findByRoleAndTrainerId(Role.CLIENT, me.id(), p);
        return page.map(AuthService::toDto);
    }

    @Transactional(readOnly = true)
    public UserDto get(Long id) {
        access.assertCanAccessClient(id);
        return AuthService.toDto(users.findById(id).orElseThrow(() -> new NotFoundException("Cliente no encontrado")));
    }

    @Transactional
    public UserDto create(CreateClientRequest r) {
        if (users.findByEmailIgnoreCase(r.email()).isPresent()) throw new ConflictException("Email ya registrado");
        AuthUser me = access.current();
        AppUser u = new AppUser();
        u.setEmail(r.email().toLowerCase()); u.setFullName(r.fullName());
        u.setPasswordHash(enc.encode(r.password())); u.setRole(Role.CLIENT);
        u.setTrainerId(me.role() == Role.TRAINER ? me.id() : null);
        return AuthService.toDto(users.save(u));
    }
}
