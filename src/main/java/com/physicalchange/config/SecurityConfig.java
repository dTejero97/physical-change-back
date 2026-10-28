package com.physicalchange.config;

import com.physicalchange.security.JwtAuthFilter;
import com.physicalchange.security.RateLimitFilter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtAuthFilter jwt, RateLimitFilter rateLimit) throws Exception {
        http.csrf(c -> c.disable())
            .cors(c -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/auth/login", "/api/public/**", "/actuator/health", "/actuator/info",
                                 "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // Colaborador (ADR-011): solo esta lista blanca; los servicios filtran por clientes asignados y permisos.
                .requestMatchers(HttpMethod.GET, "/api/clients", "/api/clients/profiles", "/api/clients/{id}", "/api/clients/{id}/assignments")
                    .hasAnyRole("ADMIN", "TRAINER", "COLLABORATOR")
                .requestMatchers("/api/workouts/**").hasAnyRole("ADMIN", "TRAINER", "COLLABORATOR")
                .requestMatchers("/api/clients/**", "/api/reports/**", "/api/library/**", "/api/files/**").hasAnyRole("ADMIN", "TRAINER")
                .requestMatchers("/api/auth/**", "/api/team/me", "/api/exercises", "/api/exercises/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/exercise-logs/client/**", "/api/progress/client/**", "/api/progress/strength/**",
                                 "/api/assignments/*/compliance").authenticated()
                // Todo lo demás: cualquier usuario autenticado salvo el colaborador (denegado por defecto).
                .anyRequest().access((auth, ctx) -> {
                    Authentication who = auth.get();
                    boolean ok = who != null && who.isAuthenticated() && !(who instanceof AnonymousAuthenticationToken)
                            && who.getAuthorities().stream().noneMatch(g -> "ROLE_COLLABORATOR".equals(g.getAuthority()));
                    return new AuthorizationDecision(ok);
                }))
            .addFilterBefore(rateLimit, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(10); }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") List<String> origins) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(origins);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/**", c);
        return s;
    }
}
