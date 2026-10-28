package com.physicalchange;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Límites por IP con valores bajos (T141). La IP es la última entrada de X-Forwarded-For. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.rate-limit.enabled=true", "app.rate-limit.login-per-ip-per-minute=3",
        "app.rate-limit.public-per-ip-per-minute=2", "app.rate-limit.api-per-ip-per-minute=1000"})
class RateLimitTest {
    @Autowired MockMvc mvc;

    private int login(String xff) throws Exception {
        return mvc.perform(post("/api/auth/login").header("X-Forwarded-For", xff).contentType("application/json")
                .content("{\"username\":\"nadie@test\",\"password\":\"x\"}")).andReturn().getResponse().getStatus();
    }

    @Test void loginLimitadoPorIpAunqueFalseeLaPrimeraEntrada() throws Exception {
        // El cliente cambia la primera IP en cada intento; la que añade el proxy (la última) es la misma.
        for (int i = 0; i < 3; i++) if (login("10.0.0." + i + ", 203.0.113.7") != 401) throw new AssertionError("debería ser 401");
        mvc.perform(post("/api/auth/login").header("X-Forwarded-For", "10.9.9.9, 203.0.113.7").contentType("application/json")
                .content("{\"username\":\"nadie@test\",\"password\":\"x\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        // Otra IP real sigue pudiendo intentarlo
        if (login("198.51.100.1") != 401) throw new AssertionError("otra IP debería poder intentarlo");
    }

    @Test void rutasPublicasLimitadasPorIp() throws Exception {
        String ip = "192.0.2.50";
        for (int i = 0; i < 2; i++) mvc.perform(get("/api/public/invitations/" + "a".repeat(43)).header("X-Forwarded-For", ip));
        mvc.perform(get("/api/public/invitations/" + "a".repeat(43)).header("X-Forwarded-For", ip)).andExpect(status().isTooManyRequests());
    }
}
