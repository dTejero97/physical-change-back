package com.physicalchange.security;

import com.physicalchange.domain.Role;

/** Principal autenticado extraído del JWT. */
public record AuthUser(Long id, String email, Role role) {}
