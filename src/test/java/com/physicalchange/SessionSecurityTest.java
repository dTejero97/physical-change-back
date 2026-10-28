package com.physicalchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.domain.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Revocación de sesiones, bloqueo por intentos fallidos y validación de parámetros (Nota de Seguridad T140, T141). */
@SpringBootTest
@AutoConfigureMockMvc
class SessionSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private String sfx;
    private AppUser trainer, client;

    @BeforeEach void data() {
        sfx = UUID.randomUUID().toString().substring(0, 8);
        trainer = save("t-" + sfx + "@test", Role.TRAINER, null);
        client = save("c-" + sfx + "@test", Role.CLIENT, trainer.getId());
    }

    private AppUser save(String email, Role role, Long trainerOf) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName("U " + email); u.setRole(role); u.setTrainerId(trainerOf);
        u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }

    private String login(String email, String password, int status) throws Exception {
        JsonNode r = send("POST", "/api/auth/login", null, "{\"username\":\"" + email + "\",\"password\":\"" + password + "\"}", status);
        return r != null && r.has("token") ? "Bearer " + r.get("token").asText() : null;
    }

    private JsonNode send(String method, String url, String auth, String body, int status) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).contentType("application/json");
        if (auth != null) req.header("Authorization", auth);
        if (body != null) req.content(body);
        MvcResult r = mvc.perform(req).andExpect(status().is(status)).andReturn();
        String text = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }

    @Test void archivarUnClienteCierraSuSesionAlMomento() throws Exception {
        String c = login(client.getEmail(), "Password1!", 200);
        String t = login(trainer.getEmail(), "Password1!", 200);
        send("GET", "/api/auth/me", c, null, 200);
        send("PUT", "/api/clients/" + client.getId(), t, "{\"fullName\":\"Archivado\",\"active\":false}", 200);
        send("GET", "/api/auth/me", c, null, 401);
        login(client.getEmail(), "Password1!", 401);
    }

    @Test void cambiarLaContrasenaCierraLasOtrasSesionesYDevuelveUnaNueva() throws Exception {
        String otroDispositivo = login(client.getEmail(), "Password1!", 200);
        String actual = login(client.getEmail(), "Password1!", 200);
        JsonNode r = send("PUT", "/api/auth/password", actual, "{\"currentPassword\":\"Password1!\",\"newPassword\":\"Nueva-clave-1\"}", 200);
        String nueva = "Bearer " + r.get("token").asText();
        send("GET", "/api/auth/me", otroDispositivo, null, 401);
        send("GET", "/api/auth/me", actual, null, 401);
        send("GET", "/api/auth/me", nueva, null, 200);
    }

    @Test void cerrarSesionInvalidaElTokenEnElServidor() throws Exception {
        String c = login(client.getEmail(), "Password1!", 200);
        send("POST", "/api/auth/logout", c, null, 204);
        send("GET", "/api/auth/me", c, null, 401);
        send("GET", "/api/auth/me", login(client.getEmail(), "Password1!", 200), null, 200);
    }

    @Test void unTokenConOtroRolDejaDeValer() throws Exception {
        String comoTrainer = "Bearer " + jwt.generate(new AuthUser(client.getId(), client.getEmail(), Role.TRAINER));
        send("GET", "/api/auth/me", comoTrainer, null, 401);
    }

    @Test void cincoFallosBloqueanLaCuentaAunqueLuegoAcierte() throws Exception {
        for (int i = 0; i < 5; i++) login(client.getEmail(), "mala-" + i, 401);
        var res = mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"" + client.getEmail() + "\",\"password\":\"Password1!\"}"))
                .andExpect(status().isTooManyRequests()).andReturn().getResponse();
        assertNotNull(res.getHeader("Retry-After"));
        // Otra cuenta no queda afectada
        login(trainer.getEmail(), "Password1!", 200);
    }

    @Test void unLoginCorrectoReiniciaLosFallos() throws Exception {
        for (int i = 0; i < 4; i++) login(client.getEmail(), "mala-" + i, 401);
        login(client.getEmail(), "Password1!", 200);
        for (int i = 0; i < 4; i++) login(client.getEmail(), "mala-" + i, 401);
        login(client.getEmail(), "Password1!", 200);
    }

    @Test void entradasDesproporcionadasSeRechazan() throws Exception {
        String t = login(trainer.getEmail(), "Password1!", 200);
        send("GET", "/api/exercises?q=" + "a".repeat(600), t, null, 400);
        send("POST", "/api/auth/login", null, "{\"username\":\"x@test\",\"password\":\"" + "a".repeat(80) + "\"}", 400);
    }
}
