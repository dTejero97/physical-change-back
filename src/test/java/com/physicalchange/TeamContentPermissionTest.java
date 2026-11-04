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
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Permiso del colaborador para el generador de contenido (T198, ADR-011, docs/api-equipo.md): solo lo cambia el profesional. */
@SpringBootTest
@AutoConfigureMockMvc
class TeamContentPermissionTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired TeamMemberRepository team;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private AppUser trainer, collab;

    @BeforeEach void data() {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        trainer = save("t-" + sfx + "@test", Role.TRAINER);
        collab = save("co-" + sfx + "@test", Role.COLLABORATOR);
        TeamMember m = new TeamMember(collab.getId(), trainer.getId());
        m.activate();
        team.save(m);
    }

    @Test void elProfesionalLoActivaYElColaboradorEntra() throws Exception {
        String t = token(trainer), c = token(collab);
        send("GET", "/api/content/ideas", c, null, 403);                    // sin permiso
        assertFalse(send("GET", "/api/team/me", c, null, 200).get("permissions").get("useContent").asBoolean());

        JsonNode on = send("PUT", "/api/team/" + collab.getId() + "/permissions", t,
                "{\"assignRoutines\":false,\"manageAppointments\":false,\"messageClients\":false,\"useContent\":true}", 200);
        assertTrue(on.get("permissions").get("useContent").asBoolean());
        assertTrue(send("GET", "/api/team/me", c, null, 200).get("permissions").get("useContent").asBoolean());
        send("GET", "/api/content/ideas", c, null, 200);
        send("POST", "/api/content/ai/ideas", c, "{\"count\":3}", 403);     // J-08: ni IA
        send("POST", "/api/content/accounts/INSTAGRAM/connect", c, null, 403);  // ni cuentas

        // Un cliente antiguo que no envía useContent no lo quita.
        send("PUT", "/api/team/" + collab.getId() + "/permissions", t, "{\"assignRoutines\":true,\"manageAppointments\":false,\"messageClients\":false}", 200);
        assertTrue(team.findById(collab.getId()).orElseThrow().isCanUseContent());

        // El colaborador no puede dárselo.
        send("PUT", "/api/team/" + collab.getId() + "/permissions", c,
                "{\"assignRoutines\":true,\"manageAppointments\":true,\"messageClients\":true,\"useContent\":true}", 403);

        send("PUT", "/api/team/" + collab.getId() + "/permissions", t,
                "{\"assignRoutines\":false,\"manageAppointments\":false,\"messageClients\":false,\"useContent\":false}", 200);
        send("GET", "/api/content/ideas", c, null, 403);
    }

    private AppUser save(String email, Role role) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName("U " + email); u.setRole(role);
        u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }

    private String token(AppUser u) { return "Bearer " + jwt.generate(new AuthUser(u.getId(), u.getEmail(), u.getRole()), u.getTokenVersion()); }

    private JsonNode send(String method, String url, String auth, String body, int status) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).contentType("application/json");
        if (auth != null) req.header("Authorization", auth);
        if (body != null) req.content(body);
        String text = mvc.perform(req).andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }
}
