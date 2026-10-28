package com.physicalchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.domain.*;
import com.physicalchange.mail.MailMessage;
import com.physicalchange.mail.MailSender;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Correos (T137): invitación, recuperación de contraseña de un solo uso y sin revelar qué cuentas existen. */
@SpringBootTest
@AutoConfigureMockMvc
class MailFlowTest {
    static final List<MailMessage> SENT = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class CaptureMail {
        @Bean @Primary MailSender capture() { return SENT::add; }
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private AppUser trainer;
    private String trainerToken, sfx;

    @BeforeEach void data() {
        SENT.clear();
        sfx = UUID.randomUUID().toString().substring(0, 8);
        trainer = new AppUser(); trainer.setEmail("t-" + sfx + "@test"); trainer.setFullName("Lucía <b>Entrena</b>");
        trainer.setRole(Role.TRAINER); trainer.setPasswordHash(enc.encode("Password1!"));
        trainer = users.save(trainer);
        trainerToken = "Bearer " + jwt.generate(new AuthUser(trainer.getId(), trainer.getEmail(), Role.TRAINER));
    }

    private JsonNode send(String method, String url, String auth, String body, int status) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).contentType("application/json");
        if (auth != null) req.header("Authorization", auth);
        if (body != null) req.content(body);
        MvcResult r = mvc.perform(req).andExpect(status().is(status)).andReturn();
        String text = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }

    private static String token(MailMessage m, String path) {
        Matcher x = Pattern.compile(path + "([A-Za-z0-9_-]+)").matcher(m.text());
        assertTrue(x.find(), "el correo debe llevar el enlace " + path);
        return x.group(1);
    }

    @Test void invitarPorEmailEnviaElEnlaceYEscapaLosNombres() throws Exception {
        JsonNode inv = send("POST", "/api/invitations", trainerToken,
                "{\"email\":\"nueva-" + sfx + "@test\",\"name\":\"Ana\"}", 201);
        assertEquals(1, SENT.size());
        MailMessage m = SENT.get(0);
        assertEquals("nueva-" + sfx + "@test", m.to());
        assertEquals(inv.get("token").asText(), token(m, "http://localhost:4200/invitacion/"));
        assertFalse(m.html().contains("<b>Entrena</b>"), "el nombre del entrenador debe ir escapado");
        send("POST", "/api/invitations/" + inv.get("id").asLong() + "/resend", trainerToken, null, 200);
        assertEquals(2, SENT.size());
    }

    @Test void recuperarContrasenaDeUnSoloUsoYCierraSesiones() throws Exception {
        AppUser c = new AppUser(); c.setEmail("c-" + sfx + "@test"); c.setFullName("Cliente"); c.setRole(Role.CLIENT);
        c.setTrainerId(trainer.getId()); c.setPasswordHash(enc.encode("Password1!")); users.save(c);
        String old = "Bearer " + send("POST", "/api/auth/login", null, "{\"username\":\"" + c.getEmail() + "\",\"password\":\"Password1!\"}", 200).get("token").asText();

        send("POST", "/api/public/auth/forgot-password", null, "{\"email\":\"" + c.getEmail().toUpperCase() + "\"}", 204);
        assertEquals(1, SENT.size());
        String t = token(SENT.get(0), "http://localhost:4200/restablecer/");

        send("POST", "/api/public/auth/reset-password", null, "{\"token\":\"" + t + "\",\"newPassword\":\"Nueva-clave-1\"}", 204);
        send("GET", "/api/auth/me", old, null, 401);
        send("POST", "/api/auth/login", null, "{\"username\":\"" + c.getEmail() + "\",\"password\":\"Nueva-clave-1\"}", 200);
        // Un solo uso
        send("POST", "/api/public/auth/reset-password", null, "{\"token\":\"" + t + "\",\"newPassword\":\"Otra-clave-2\"}", 400);
    }

    @Test void unEnlaceNuevoAnulaElAnteriorYHayLimitePorHora() throws Exception {
        AppUser c = new AppUser(); c.setEmail("l-" + sfx + "@test"); c.setFullName("Cliente"); c.setRole(Role.CLIENT);
        c.setTrainerId(trainer.getId()); c.setPasswordHash(enc.encode("Password1!")); users.save(c);
        for (int i = 0; i < 4; i++) send("POST", "/api/public/auth/forgot-password", null, "{\"email\":\"" + c.getEmail() + "\"}", 204);
        assertEquals(3, SENT.size(), "máximo 3 correos por hora y email");
        String first = token(SENT.get(0), "/restablecer/");
        send("POST", "/api/public/auth/reset-password", null, "{\"token\":\"" + first + "\",\"newPassword\":\"Nueva-clave-1\"}", 400);
        send("POST", "/api/public/auth/reset-password", null, "{\"token\":\"" + token(SENT.get(2), "/restablecer/") + "\",\"newPassword\":\"Nueva-clave-1\"}", 204);
    }

    @Test void noRevelaSiElEmailExisteYValidaLaEntrada() throws Exception {
        send("POST", "/api/public/auth/forgot-password", null, "{\"email\":\"nadie-" + sfx + "@test\"}", 204);
        assertTrue(SENT.isEmpty());
        send("POST", "/api/public/auth/forgot-password", null, "{\"email\":\"no-es-un-email\"}", 400);
        send("POST", "/api/public/auth/reset-password", null, "{\"token\":\"corto\",\"newPassword\":\"Nueva-clave-1\"}", 400);
        send("POST", "/api/public/auth/reset-password", null, "{\"token\":\"" + "a".repeat(43) + "\",\"newPassword\":\"Nueva-clave-1\"}", 400);
    }
}
