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
import org.springframework.test.web.servlet.RequestBuilder;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Sección Clientes contra la base de datos: etiquetas, ficha, invitaciones, leads y comunidad (docs/api-clientes.md). */
@SpringBootTest
@AutoConfigureMockMvc
class ClientesFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired WorkoutRepository workouts;
    @Autowired WorkoutAssignmentRepository assignments;
    @Autowired ChatMessageRepository messages;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    /** Datos únicos por test: la base de datos de tests es compartida entre clases. */
    private String sfx;
    private Long trainerId, clientId, otherTrainerId, otherClientId;
    private String trainer, client, otherTrainer;

    @BeforeEach void data() {
        sfx = UUID.randomUUID().toString().substring(0, 8);
        trainerId = save("t-" + sfx + "@test", Role.TRAINER, null).getId();
        clientId = save("c-" + sfx + "@test", Role.CLIENT, trainerId).getId();
        otherTrainerId = save("ot-" + sfx + "@test", Role.TRAINER, null).getId();
        otherClientId = save("oc-" + sfx + "@test", Role.CLIENT, otherTrainerId).getId();
        trainer = token(trainerId, Role.TRAINER);
        client = token(clientId, Role.CLIENT);
        otherTrainer = token(otherTrainerId, Role.TRAINER);
    }

    private AppUser save(String email, Role role, Long trainerOf) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName("U " + email); u.setRole(role); u.setTrainerId(trainerOf);
        u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }

    private String token(Long id, Role role) { return "Bearer " + jwt.generate(new AuthUser(id, "x", role)); }

    private JsonNode call(RequestBuilder req, int status) throws Exception {
        MvcResult r = mvc.perform(req).andExpect(status().is(status)).andReturn();
        String body = r.getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    private JsonNode send(String method, String url, String auth, String body, int status) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).contentType("application/json");
        if (auth != null) req.header("Authorization", auth);
        if (body != null) req.content(body);
        return call(req, status);
    }

    private static final String AUTOMATIONS = "{\"chat\":true,\"chatMessage\":\"¡Bienvenido!\",\"forms\":false,\"formName\":\"\"," +
            "\"program\":false,\"programName\":\"\",\"programDate\":\"\",\"nutrition\":false,\"nutritionPlan\":\"\"," +
            "\"limitChat\":false,\"chatLimit\":-1,\"service\":true,\"services\":[\"FITNESS\",\"NUTRITION\"]}";

    private long createTag(String name) throws Exception {
        return send("POST", "/api/tags", trainer, "{\"name\":\"" + name + "\",\"automations\":" + AUTOMATIONS + "}", 201).get("id").asLong();
    }

    @Test void etiquetasYFicha() throws Exception {
        long tag = createTag("vip-" + sfx);
        send("POST", "/api/tags", trainer, "{\"name\":\"VIP-" + sfx + "\",\"automations\":" + AUTOMATIONS + "}", 409); // mismo nombre
        send("PUT", "/api/tags/" + tag, otherTrainer, "{\"name\":\"x\",\"automations\":" + AUTOMATIONS + "}", 403);     // no es suya

        // Asignación masiva: solo a clientes propios
        assertEquals(1, send("POST", "/api/tags/" + tag + "/clients", trainer, "{\"clientIds\":[" + clientId + "]}", 200).get("clientCount").asInt());
        send("POST", "/api/tags/" + tag + "/clients", trainer, "{\"clientIds\":[" + otherClientId + "]}", 403);

        // Ficha con métricas calculadas: 1 de 2 rutinas completadas esta semana = 50 %
        Workout w = new Workout(); w.setTrainerId(trainerId); w.setName("W " + sfx); w = workouts.save(w);
        assign(w.getId(), clientId, LocalDate.now(), LocalDate.now());
        assign(w.getId(), clientId, LocalDate.now().minusDays(1), null);
        JsonNode mine = profileOf(send("GET", "/api/clients/profiles", trainer, null, 200), clientId);
        assertEquals(50, mine.get("complianceWeek").asInt());
        assertEquals(LocalDate.now().toString(), mine.get("lastActivity").asText());
        assertEquals(tag, mine.get("tagIds").get(0).asLong());
        assertNull(profileOf(send("GET", "/api/clients/profiles", trainer, null, 200), otherClientId));

        // Edición de la ficha; una etiqueta ajena se rechaza
        String profile = "{\"planName\":\"Mensual\",\"planStatus\":\"ACTIVE\",\"assignee\":\"ME\",\"appAccess\":true,\"heightCm\":180," +
                "\"weightKg\":80.5,\"gender\":\"MALE\",\"birthDate\":\"1990-05-01\",\"services\":[\"FITNESS\"],\"tagIds\":[" + tag + "]}";
        JsonNode updated = send("PUT", "/api/clients/" + clientId + "/profile", trainer, profile, 200);
        assertEquals("ACTIVE", updated.get("planStatus").asText());
        assertEquals(80.5, updated.get("weightKg").asDouble());
        long foreignTag = send("POST", "/api/tags", otherTrainer, "{\"name\":\"f-" + sfx + "\",\"automations\":" + AUTOMATIONS + "}", 201).get("id").asLong();
        send("PUT", "/api/clients/" + clientId + "/profile", trainer, profile.replace("[" + tag + "]", "[" + foreignTag + "]"), 400);
        send("PUT", "/api/clients/" + otherClientId + "/profile", trainer, profile, 403);

        // Borrar la etiqueta la quita de los clientes
        send("DELETE", "/api/tags/" + tag, trainer, null, 204);
        assertEquals(0, profileOf(send("GET", "/api/clients/profiles", trainer, null, 200), clientId).get("tagIds").size());
    }

    @Test void altaManualConEtiquetaEjecutaAutomatizaciones() throws Exception {
        long tag = createTag("auto-" + sfx);
        JsonNode created = send("POST", "/api/clients", trainer,
                "{\"email\":\"n-" + sfx + "@test\",\"password\":\"Password1!\",\"fullName\":\"Nuevo\",\"tagIds\":[" + tag + "]}", 201);
        long id = created.get("id").asLong();
        JsonNode p = profileOf(send("GET", "/api/clients/profiles", trainer, null, 200), id);
        assertEquals(tag, p.get("tagIds").get(0).asLong());
        assertEquals(2, p.get("services").size());                       // automatización "tipo de servicio"
        assertTrue(messages.findAll().stream().anyMatch(m -> m.getRecipientId().equals(id) && m.getBody().equals("¡Bienvenido!")));
    }

    @Test void archivarYEliminar() throws Exception {
        send("DELETE", "/api/clients/" + clientId, trainer, null, 409);  // primero hay que archivar
        send("PUT", "/api/clients/" + clientId, trainer, "{\"fullName\":\"Archivado\",\"active\":false}", 200);
        assertFalse(profileOf(send("GET", "/api/clients/profiles", trainer, null, 200), clientId).get("active").asBoolean());
        send("DELETE", "/api/clients/" + clientId, otherTrainer, null, 403);
        send("DELETE", "/api/clients/" + clientId, trainer, null, 204);
        assertNull(profileOf(send("GET", "/api/clients/profiles", trainer, null, 200), clientId));
        JsonNode list = send("GET", "/api/clients?size=100", trainer, null, 200).get("content");
        list.forEach(u -> assertNotEquals(clientId.longValue(), u.get("id").asLong()));
    }

    @Test void invitacionPorEmailYEnlace() throws Exception {
        long tag = createTag("inv-" + sfx);
        String email = "inv-" + sfx + "@test";
        JsonNode inv = send("POST", "/api/invitations", trainer, "{\"email\":\"" + email + "\",\"name\":\"Invitada\",\"tagId\":" + tag + "}", 201);
        send("POST", "/api/invitations", trainer, "{\"email\":\"" + email + "\",\"name\":\"Otra vez\"}", 409);          // pendiente
        send("POST", "/api/invitations", trainer, "{\"email\":\"c-" + sfx + "@test\",\"name\":\"Ya cliente\"}", 409);      // ya es usuario
        send("POST", "/api/invitations", otherTrainer, "{\"email\":\"z-" + sfx + "@test\",\"name\":\"Z\",\"tagId\":" + tag + "}", 400); // etiqueta ajena
        assertEquals(1, send("GET", "/api/invitations", trainer, null, 200).size());
        assertEquals(0, send("GET", "/api/invitations", otherTrainer, null, 200).size());

        // Público: datos de la invitación y alta con sesión
        String token = inv.get("token").asText();
        JsonNode info = send("GET", "/api/public/invitations/" + token, null, null, 200);
        assertEquals(email, info.get("email").asText());
        assertFalse(info.get("expired").asBoolean());
        JsonNode login = send("POST", "/api/public/invitations/" + token + "/accept", null,
                "{\"fullName\":\"Invitada Real\",\"password\":\"Password1!\"}", 201);
        assertEquals("CLIENT", login.get("user").get("role").asText());
        assertEquals(trainerId, login.get("user").get("trainerId").asLong());
        long newId = login.get("user").get("id").asLong();
        assertEquals(tag, profileOf(send("GET", "/api/clients/profiles", trainer, null, 200), newId).get("tagIds").get(0).asLong());
        send("POST", "/api/public/invitations/" + token + "/accept", null, "{\"fullName\":\"X\",\"password\":\"Password1!\"}", 404); // un solo uso
        assertEquals(0, send("GET", "/api/invitations", trainer, null, 200).size());
        send("POST", "/api/auth/login", null, "{\"username\":\"" + email + "\",\"password\":\"Password1!\"}", 200);

        // Enlace genérico: reutilizable y exige email
        String link = send("POST", "/api/invitations/link", trainer, "{}", 201).get("token").asText();
        send("POST", "/api/public/invitations/" + link + "/accept", null, "{\"fullName\":\"Sin email\",\"password\":\"Password1!\"}", 400);
        send("POST", "/api/public/invitations/" + link + "/accept", null, "{\"fullName\":\"A\",\"email\":\"la-" + sfx + "@test\",\"password\":\"Password1!\"}", 201);
        send("POST", "/api/public/invitations/" + link + "/accept", null, "{\"fullName\":\"B\",\"email\":\"lb-" + sfx + "@test\",\"password\":\"Password1!\"}", 201);

        // Token con formato no válido o inexistente
        send("GET", "/api/public/invitations/no!valido", null, null, 400);
        send("GET", "/api/public/invitations/" + "a".repeat(43), null, null, 404);
        // Sin sesión no se puede listar ni crear
        send("GET", "/api/invitations", null, null, 401);
        send("GET", "/api/invitations", client, null, 403);
    }

    @Test void leads() throws Exception {
        long lead = send("POST", "/api/leads", trainer, "{\"name\":\"Lucía\",\"email\":\"lead-" + sfx + "@test\",\"phone\":\"600\",\"message\":\"Hola\",\"source\":\"REFERRAL\"}", 201).get("id").asLong();
        long noEmail = send("POST", "/api/leads", trainer, "{\"name\":\"Sin email\",\"source\":\"OTHER\"}", 201).get("id").asLong();
        send("POST", "/api/leads", trainer, "{\"name\":\"X\",\"source\":\"RADIO\"}", 400);
        send("POST", "/api/leads/" + lead + "/invite", otherTrainer, null, 403);
        JsonNode inv = send("POST", "/api/leads/" + lead + "/invite", trainer, null, 201);
        assertEquals("lead-" + sfx + "@test", inv.get("email").asText());
        send("POST", "/api/leads/" + noEmail + "/invite", trainer, null, 400);
        assertEquals(1, send("GET", "/api/leads", trainer, null, 200).size());
        send("DELETE", "/api/leads/" + noEmail, trainer, null, 204);
        assertEquals(0, send("GET", "/api/leads", trainer, null, 200).size());
    }

    @Test void comunidad() throws Exception {
        String group = "{\"name\":\"Full-body\",\"description\":\"Tarde\",\"emoji\":\"💪\",\"whoCanPost\":\"EVERYONE\",\"shareResults\":true,\"memberIds\":[" + clientId + "]}";
        send("POST", "/api/community/groups", trainer, group.replace(clientId.toString(), otherClientId.toString()), 403); // cliente ajeno
        long groupId = send("POST", "/api/community/groups", trainer, group, 201).get("id").asLong();
        send("POST", "/api/community/groups", client, group, 403);
        assertEquals(1, send("GET", "/api/community/groups", client, null, 200).size());          // el miembro lo ve
        assertEquals(0, send("GET", "/api/community/groups", otherTrainer, null, 200).size());
        send("GET", "/api/community/groups/" + groupId + "/posts", otherTrainer, null, 403);

        // Workout compartido con ranking de quien lo completó
        Workout w = new Workout(); w.setTrainerId(trainerId); w.setName("Reto " + sfx); w = workouts.save(w);
        assign(w.getId(), clientId, LocalDate.now(), LocalDate.now());
        JsonNode post = send("POST", "/api/community/groups/" + groupId + "/posts", trainer, "{\"text\":\"Reto semanal\",\"workoutId\":" + w.getId() + "}", 201);
        assertEquals(1, post.get("workout").get("ranking").size());
        send("POST", "/api/community/groups/" + groupId + "/posts", client, "{\"text\":\"Comparto\",\"workoutId\":" + w.getId() + "}", 403);

        // El cliente publica (EVERYONE), da "me gusta" y no puede borrar lo ajeno
        long clientPost = send("POST", "/api/community/groups/" + groupId + "/posts", client, "{\"text\":\"¡Hecho!\"}", 201).get("id").asLong();
        long trainerPost = post.get("id").asLong();
        JsonNode liked = send("POST", "/api/community/posts/" + trainerPost + "/like", client, null, 200);
        assertTrue(liked.get("liked").asBoolean());
        assertEquals(1, liked.get("likes").asInt());
        assertEquals(0, send("POST", "/api/community/posts/" + trainerPost + "/like", client, null, 200).get("likes").asInt());
        send("DELETE", "/api/community/posts/" + trainerPost, client, null, 403);
        send("DELETE", "/api/community/posts/" + clientPost, trainer, null, 204);                 // el dueño modera
        assertEquals(1, send("GET", "/api/community/groups/" + groupId + "/posts", client, null, 200).size());

        // Solo el entrenador publica: el cliente ya no puede
        send("PUT", "/api/community/groups/" + groupId, trainer, group.replace("EVERYONE", "TRAINER"), 200);
        send("POST", "/api/community/groups/" + groupId + "/posts", client, "{\"text\":\"Hola\"}", 403);
        send("DELETE", "/api/community/groups/" + groupId, trainer, null, 204);
        assertEquals(0, send("GET", "/api/community/groups", client, null, 200).size());
    }

    private void assign(Long workoutId, Long client, LocalDate scheduled, LocalDate completed) {
        WorkoutAssignment a = new WorkoutAssignment();
        a.setWorkoutId(workoutId); a.setClientId(client); a.setScheduledDate(scheduled); a.setCompletedDate(completed);
        assignments.save(a);
    }

    private static JsonNode profileOf(JsonNode list, Long clientId) {
        for (JsonNode p : list) if (p.get("clientId").asLong() == clientId) return p;
        return null;
    }
}
