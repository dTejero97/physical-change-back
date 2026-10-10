package com.physicalchange;

import com.physicalchange.domain.*;
import com.physicalchange.repository.UserRepository;
import com.physicalchange.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class NutritionSecurityTest {
    @Autowired MockMvc mvc; @Autowired UserRepository users; @Autowired PasswordEncoder enc; @Autowired JwtService jwt;
    static Long trainerId, clientId, plainTrainerId;

    private AppUser save(String email, Role role, Long trainer) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName(email); u.setRole(role); u.setTrainerId(trainer);
        u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }
    @BeforeEach void data() {
        if (trainerId != null) return;
        trainerId = save("nt@test", Role.TRAINER, null).getId();
        clientId = save("nc@test", Role.CLIENT, trainerId).getId();
        plainTrainerId = save("np@test", Role.TRAINER, null).getId();
    }
    private String tok(Long id, String e, Role r) { return "Bearer " + jwt.generate(new AuthUser(id, e, r)); }
    private static final String PLAN = "{\"clientId\":%d,\"name\":\"Plan\",\"dailyKcal\":2200}";

    @Test void sinServicioActivadoElEntrenadorNoCreaPlanes() throws Exception {
        mvc.perform(post("/api/nutrition/plans").header("Authorization", tok(plainTrainerId, "np@test", Role.TRAINER))
                .contentType("application/json").content(PLAN.formatted(clientId))).andExpect(status().isForbidden());
    }
    @Test void clienteNoPuedeActivarElServicio() throws Exception {
        mvc.perform(put("/api/nutrition/service").header("Authorization", tok(clientId, "nc@test", Role.CLIENT))
                .contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isForbidden());
    }
    @Test void clienteNoCreaPlanes() throws Exception {
        mvc.perform(post("/api/nutrition/plans").header("Authorization", tok(clientId, "nc@test", Role.CLIENT))
                .contentType("application/json").content(PLAN.formatted(clientId))).andExpect(status().isForbidden());
    }
    @Test void entrenadorActivaServicioYCreaPlan() throws Exception {
        String t = tok(trainerId, "nt@test", Role.TRAINER);
        mvc.perform(put("/api/nutrition/service").header("Authorization", t).contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isOk());
        mvc.perform(post("/api/nutrition/plans").header("Authorization", t).contentType("application/json").content(PLAN.formatted(clientId))).andExpect(status().isCreated());
    }
    @Test void clienteVeSusPlanesPeroNoLosDeOtro() throws Exception {
        String c = tok(clientId, "nc@test", Role.CLIENT);
        mvc.perform(get("/api/nutrition/plans/client/" + clientId).header("Authorization", c)).andExpect(status().isOk());
        mvc.perform(get("/api/nutrition/plans/client/" + (clientId + 999)).header("Authorization", c)).andExpect(status().isForbidden());
    }
}
