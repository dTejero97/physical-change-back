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
class SecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;

    static Long trainerId, clientId, otherClientId;

    private AppUser save(String email, Role role, Long trainer) {
        AppUser u = new AppUser();
        u.setEmail(email); u.setFullName(email); u.setRole(role); u.setTrainerId(trainer);
        u.setPasswordHash(enc.encode("Password1!"));
        return users.save(u);
    }

    @BeforeEach
    void data() {
        if (trainerId != null) return;
        trainerId = save("t@test", Role.TRAINER, null).getId();
        clientId = save("c1@test", Role.CLIENT, trainerId).getId();
        otherClientId = save("c2@test", Role.CLIENT, null).getId();
    }

    private String token(Long id, String email, Role r) { return "Bearer " + jwt.generate(new AuthUser(id, email, r)); }

    @Test void sinTokenEs401() throws Exception {
        mvc.perform(get("/api/exercises")).andExpect(status().isUnauthorized());
    }

    @Test void loginCorrecto() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"email\":\"c1@test\",\"password\":\"Password1!\"}")).andExpect(status().isOk());
    }

    @Test void loginIncorrectoEs401() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"email\":\"c1@test\",\"password\":\"mala\"}")).andExpect(status().isUnauthorized());
    }

    @Test void clienteNoAccedeAEndpointsDeEntrenador() throws Exception {
        mvc.perform(get("/api/clients").header("Authorization", token(clientId, "c1@test", Role.CLIENT)))
           .andExpect(status().isForbidden());
    }

    @Test void clienteNoVeProgresoDeOtroCliente() throws Exception {
        mvc.perform(get("/api/progress/client/" + otherClientId).header("Authorization", token(clientId, "c1@test", Role.CLIENT)))
           .andExpect(status().isForbidden());
    }

    @Test void clienteVeSuPropioProgreso() throws Exception {
        mvc.perform(get("/api/progress/client/" + clientId).header("Authorization", token(clientId, "c1@test", Role.CLIENT)))
           .andExpect(status().isOk());
    }

    @Test void entrenadorNoVeClienteAjeno() throws Exception {
        mvc.perform(get("/api/clients/" + otherClientId).header("Authorization", token(trainerId, "t@test", Role.TRAINER)))
           .andExpect(status().isForbidden());
    }

    @Test void entrenadorVeSuCliente() throws Exception {
        mvc.perform(get("/api/clients/" + clientId).header("Authorization", token(trainerId, "t@test", Role.TRAINER)))
           .andExpect(status().isOk());
    }
}
