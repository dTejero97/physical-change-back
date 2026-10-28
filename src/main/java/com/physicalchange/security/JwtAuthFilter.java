package com.physicalchange.security;

import com.physicalchange.repository.UserRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

/**
 * Autentica con el JWT y, en cada petición, comprueba contra la base de datos que el usuario sigue activo,
 * con el mismo rol y la misma token_version (T141): desactivar, cambiar la contraseña o cerrar sesión
 * invalida los tokens emitidos antes, sin esperar a que caduquen.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final UserRepository users;
    public JwtAuthFilter(JwtService jwt, UserRepository users) { this.jwt = jwt; this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            try {
                JwtService.Parsed p = jwt.parseWithVersion(h.substring(7));
                AuthUser u = p.user();
                boolean valid = users.findById(u.id())
                        .filter(db -> db.isActive() && db.getRole() == u.role() && db.getTokenVersion() == p.tokenVersion())
                        .isPresent();
                if (valid) {
                    var auth = new UsernamePasswordAuthenticationToken(u, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + u.role().name())));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception ignored) { /* token inválido: se queda sin autenticar -> 401 */ }
        }
        chain.doFilter(req, res);
    }
}
