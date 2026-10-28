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

/** Equipo y rol COLLABORATOR (ADR-011, docs/api-equipo.md): alta, clientes asignados, permisos, prohibidos y baja. */
@SpringBootTest
@AutoConfigureMockMvc
class TeamFlowTest {
    static final List<MailMessage> SENT = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class CaptureMail { @Bean @Primary MailSender capture() { return SENT::add; } }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private String sfx, owner, otherTrainer;
    private AppUser ownerUser, mine, notAssigned, otherClient;

    @BeforeEach void data() {
        SENT.clear();
        sfx = UUID.randomUUID().toString().substring(0, 8);
        ownerUser = save("o-" + sfx + "@test", Role.TRAINER, null);
        AppUser other = save("ot-" + sfx + "@test", Role.TRAINER, null);
        mine = save("c1-" + sfx + "@test", Role.CLIENT, ownerUser.getId());
        notAssigned = save("c2-" + sfx + "@test", Role.CLIENT, ownerUser.getId());
        otherClient = save("c3-" + sfx + "@test", Role.CLIENT, other.getId());
        owner = token(ownerUser);
        otherTrainer = token(other);
    }

    private AppUser save(String email, Role role, Long trainerOf) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName("U " + email); u.setRole(role); u.setTrainerId(trainerOf);
        u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }

    private String token(AppUser u) { return "Bearer " + jwt.generate(new AuthUser(u.getId(), u.getEmail(), u.getRole()), u.getTokenVersion()); }

    private JsonNode send(String method, String url, String auth, String body, int status) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).contentType("application/json");
        if (auth != null) req.header("Authorization", auth);
        if (body != null) req.content(body);
        MvcResult r = mvc.perform(req).andExpect(status().is(status)).andReturn();
        String text = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }

    /** Invita, acepta desde el enlace del correo y devuelve { id, token del colaborador }. */
    private Object[] collaborator() throws Exception {
        JsonNode m = send("POST", "/api/team/invitations", owner, "{\"email\":\"col-" + sfx + "@test\",\"fullName\":\"Colaboradora\"}", 201);
        assertEquals("INVITED", m.get("status").asText());
        Matcher x = Pattern.compile("/equipo/invitacion/([A-Za-z0-9_-]{43})").matcher(SENT.get(SENT.size() - 1).text());
        assertTrue(x.find());
        assertEquals("Colaboradora", send("GET", "/api/public/team-invitations/" + x.group(1), null, null, 200).get("fullName").asText());
        JsonNode s = send("POST", "/api/public/team-invitations/" + x.group(1) + "/accept", null, "{\"password\":\"Clave-colab-1\"}", 201);
        assertEquals("COLLABORATOR", s.get("user").get("role").asText());
        send("POST", "/api/public/team-invitations/" + x.group(1) + "/accept", null, "{\"password\":\"Clave-colab-1\"}", 404);
        return new Object[]{ m.get("id").asLong(), "Bearer " + s.get("token").asText() };
    }

    @Test void soloVeYSigueASusClientesAsignados() throws Exception {
        Object[] c = collaborator(); long id = (long) c[0]; String col = (String) c[1];
        assertEquals(0, send("GET", "/api/clients", col, null, 200).get("content").size());
        send("PUT", "/api/team/" + id + "/clients", owner, "{\"clientIds\":[" + mine.getId() + "]}", 200);
        JsonNode list = send("GET", "/api/clients", col, null, 200);
        assertEquals(1, list.get("content").size());
        assertEquals(mine.getId(), list.get("content").get(0).get("id").asLong());
        send("GET", "/api/clients/" + mine.getId(), col, null, 200);
        send("GET", "/api/progress/client/" + mine.getId(), col, null, 200);
        send("GET", "/api/clients/" + notAssigned.getId(), col, null, 403);
        send("GET", "/api/progress/client/" + notAssigned.getId(), col, null, 403);
        send("GET", "/api/exercise-logs/client/" + notAssigned.getId(), col, null, 403);
        assertEquals("U " + ownerUser.getEmail(), send("GET", "/api/team/me", col, null, 200).get("ownerName").asText());
    }

    @Test void loProhibidoDaForbidden() throws Exception {
        String col = (String) collaborator()[1];
        for (String url : List.of("/api/team", "/api/receipts", "/api/products", "/api/business/finance", "/api/library/items?kind=RECIPE",
                "/api/tags", "/api/invitations", "/api/ai/status", "/api/dashboard/trainer", "/api/calendar?from=2026-10-01&to=2026-10-31"))
            send("GET", url, col, null, 403);
        send("POST", "/api/clients", col, "{\"email\":\"x-" + sfx + "@test\",\"password\":\"Password1!\",\"fullName\":\"X\"}", 403);
        send("PUT", "/api/clients/" + mine.getId(), col, "{\"fullName\":\"X\",\"active\":false}", 403);
        send("DELETE", "/api/clients/" + mine.getId(), col, null, 403);
        send("POST", "/api/team/invitations", col, "{\"email\":\"y-" + sfx + "@test\",\"fullName\":\"Y\"}", 403);
    }

    @Test void rutinasEnElEspacioDelProfesionalYAsignarConPermiso() throws Exception {
        Object[] c = collaborator(); long id = (long) c[0]; String col = (String) c[1];
        send("PUT", "/api/team/" + id + "/clients", owner, "{\"clientIds\":[" + mine.getId() + "]}", 200);
        long w = send("POST", "/api/workouts", col, "{\"name\":\"Fuerza A\",\"notes\":null}", 201).get("id").asLong();
        assertTrue(send("GET", "/api/workouts", owner, null, 200).get("content").findValuesAsText("name").contains("Fuerza A"),
                "lo que crea el colaborador es del profesional");
        String assign = "{\"workoutId\":" + w + ",\"clientId\":" + mine.getId() + ",\"scheduledDate\":\"2026-11-02\"}";
        send("POST", "/api/workouts/assign", col, assign, 403);
        send("PUT", "/api/team/" + id + "/permissions", owner, "{\"assignRoutines\":true,\"manageAppointments\":false,\"messageClients\":false}", 200);
        send("POST", "/api/workouts/assign", col, assign, 201);
        send("POST", "/api/workouts/assign", col, "{\"workoutId\":" + w + ",\"clientId\":" + notAssigned.getId() + ",\"scheduledDate\":\"2026-11-02\"}", 403);
        send("DELETE", "/api/workouts/" + w, col, null, 403);
    }

    @Test void laBajaCierraSuSesionYOtroProfesionalNoVeElEquipo() throws Exception {
        Object[] c = collaborator(); long id = (long) c[0]; String col = (String) c[1];
        send("GET", "/api/team", otherTrainer, null, 200).forEach(m -> assertNotEquals(id, m.get("id").asLong()));
        send("PUT", "/api/team/" + id + "/permissions", otherTrainer, "{\"assignRoutines\":true,\"manageAppointments\":true,\"messageClients\":true}", 404);
        send("PUT", "/api/team/" + id + "/clients", owner, "{\"clientIds\":[" + otherClient.getId() + "]}", 403);
        send("DELETE", "/api/team/" + id, owner, null, 204);
        send("GET", "/api/auth/me", col, null, 401);
        assertEquals(0, send("GET", "/api/team", owner, null, 200).size());
    }

    @Test void invitacionNoValida() throws Exception {
        send("GET", "/api/public/team-invitations/" + "a".repeat(43), null, null, 404);
        send("GET", "/api/public/team-invitations/corto", null, null, 400);
        send("POST", "/api/team/invitations", owner, "{\"email\":\"" + mine.getEmail() + "\",\"fullName\":\"X\"}", 409);
    }
}
