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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Asistente de bienvenida del cliente (T179, docs/api-onboarding.md). */
@SpringBootTest
@AutoConfigureMockMvc
class OnboardingTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ClientProfileRepository profiles;
    @Autowired ProgressEntryRepository progress;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    String admin, trainer, nutritionist, otherTrainer, collaborator, client;
    Long trainerId, nutritionistId, clientId;

    @BeforeEach void data() {
        String k = UUID.randomUUID().toString().substring(0, 8);
        admin = token(save("ob-a-" + k + "@test", Role.ADMIN, null, false, true));
        AppUser t = save("ob-t-" + k + "@test", Role.TRAINER, null, true, true);       // entrena y lleva nutrición
        AppUser n = save("ob-n-" + k + "@test", Role.TRAINER, null, true, false);      // solo nutricionista
        AppUser o = save("ob-o-" + k + "@test", Role.TRAINER, null, true, true);       // otro entrenador
        AppUser col = save("ob-col-" + k + "@test", Role.COLLABORATOR, null, false, true);
        AppUser c = save("ob-c-" + k + "@test", Role.CLIENT, t.getId(), false, true);
        trainerId = t.getId(); nutritionistId = n.getId(); clientId = c.getId();
        trainer = token(t); nutritionist = token(n); otherTrainer = token(o); collaborator = token(col); client = token(c);
    }

    private AppUser save(String email, Role role, Long trainer, boolean nutrition, boolean training) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName(email); u.setRole(role); u.setTrainerId(trainer);
        u.setNutritionEnabled(nutrition); u.setTrainingEnabled(training); u.setPasswordHash(enc.encode("Password1!"));
        return users.save(u);
    }
    private String token(AppUser u) { return "Bearer " + jwt.generate(new AuthUser(u.getId(), u.getEmail(), u.getRole())); }

    private JsonNode send(String method, String url, String auth, String body, int expected) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).header("Authorization", auth);
        if (body != null) req.contentType("application/json").content(body);
        MvcResult r = mvc.perform(req).andExpect(status().is(expected)).andReturn();
        String s = r.getResponse().getContentAsString();
        return s.isBlank() ? null : json.readTree(s);
    }
    private JsonNode put(String body, int expected) throws Exception { return send("PUT", "/api/me/onboarding", client, body, expected); }
    private List<String> list(JsonNode n) { return json.convertValue(n, json.getTypeFactory().constructCollectionType(List.class, String.class)); }
    private long onboardingNotifications(String auth) throws Exception {
        long count = 0;
        for (JsonNode n : send("GET", "/api/notifications", auth, null, 200))
            if ("ONBOARDING_COMPLETED".equals(n.get("type").asText()) && n.get("refId").asLong() == clientId) count++;
        return count;
    }

    @Test void sinGuardarNadaTodoVacio() throws Exception {
        JsonNode o = send("GET", "/api/me/onboarding", client, null, 200);
        assertEquals(clientId, o.get("clientId").asLong());
        for (String f : List.of("birthDate", "weightKg", "heightCm", "gender", "experience", "techniqueLevel", "healthConsent",
                                "healthConsentAt", "medicalNotes", "completedAt", "updatedAt"))
            assertTrue(o.get(f).isNull(), f);
        assertEquals(0, o.get("goals").size());
        assertEquals(0, o.get("injuries").size());
        assertFalse(send("GET", "/api/auth/me", client, null, 200).get("onboardingCompleted").asBoolean());
    }

    @Test void guardadoParcialNoBorraLoAusente() throws Exception {
        put("{\"goals\":[\"FAT_LOSS\",\"HEALTH\"],\"experience\":\"BEGINNER\",\"techniqueLevel\":3}", 200);
        JsonNode o = put("{\"trainingStyle\":\"HYPERTROPHY\",\"mealsPerDay\":4,\"avoid\":[\"NOTHING\"]}", 200);
        assertEquals(List.of("FAT_LOSS", "HEALTH"), list(o.get("goals")));
        assertEquals("BEGINNER", o.get("experience").asText());
        assertEquals(3, o.get("techniqueLevel").asInt());
        assertEquals("HYPERTROPHY", o.get("trainingStyle").asText());
        assertEquals(List.of("NOTHING"), list(o.get("avoid")));
        assertNotNull(o.get("updatedAt").asText());
        // null vacía un campo y [] una lista; el resto sigue igual.
        o = put("{\"experience\":null,\"goals\":[]}", 200);
        assertTrue(o.get("experience").isNull());
        assertEquals(0, o.get("goals").size());
        assertEquals(4, o.get("mealsPerDay").asInt());
        // Recargar devuelve lo guardado.
        assertEquals("HYPERTROPHY", send("GET", "/api/me/onboarding", client, null, 200).get("trainingStyle").asText());
    }

    @Test void datosBasicosEnLaFichaYPesoDeHoyEnProgreso() throws Exception {
        LocalDate birth = LocalDate.now().minusYears(30);
        String basics = "{\"birthDate\":\"" + birth + "\",\"weightKg\":78.5,\"heightCm\":178,\"gender\":\"OTHER\"}";
        JsonNode o = put(basics, 200);
        assertEquals(birth.toString(), o.get("birthDate").asText());
        assertEquals("OTHER", o.get("gender").asText());
        ClientProfile p = profiles.findById(clientId).orElseThrow();
        assertEquals(0, new BigDecimal("78.5").compareTo(p.getWeightKg()));
        assertEquals(178, p.getHeightCm());
        assertEquals("OTHER", p.getGender());
        assertEquals(birth, p.getBirthDate());
        // Repetir el guardado actualiza el registro de hoy, no crea otro.
        put("{\"weightKg\":77.0}", 200);
        List<ProgressEntry> entries = progress.findByClientIdOrderByEntryDateAsc(clientId);
        assertEquals(1, entries.size());
        assertEquals(LocalDate.now(), entries.get(0).getEntryDate());
        assertEquals(0, new BigDecimal("77.0").compareTo(entries.get(0).getWeightKg()));
        assertEquals("Cuestionario inicial", entries.get(0).getNotes());
        // Sin peso en la petición no se toca el progreso; la altura sigue en la ficha.
        put("{\"gender\":null}", 200);
        assertEquals(1, progress.findByClientIdOrderByEntryDateAsc(clientId).size());
        assertEquals("NONE", profiles.findById(clientId).orElseThrow().getGender());
        assertEquals(178, profiles.findById(clientId).orElseThrow().getHeightCm());
    }

    @Test void saludSinConsentimientoSeIgnoraYAlRetirarloSeBorra() throws Exception {
        String health = "\"injuries\":[\"KNEE\"],\"injuriesOther\":\"tobillo\",\"medicalNotes\":\"Asma\","
                + "\"conditions\":[\"REFLUX\"],\"foodAllergy\":true,\"allergyDetails\":\"frutos secos\"";
        JsonNode o = put("{" + health + ",\"cooks\":\"MOSTLY\"}", 200);
        assertEquals(0, o.get("injuries").size());
        assertTrue(o.get("medicalNotes").isNull());
        assertTrue(o.get("foodAllergy").isNull());
        assertEquals("MOSTLY", o.get("cooks").asText());     // el resto del paso sí se guarda
        o = put("{\"healthConsent\":false," + health + "}", 200);
        assertEquals(0, o.get("conditions").size());
        assertTrue(o.get("healthConsentAt").isNull());

        o = put("{\"healthConsent\":true," + health + "}", 200);
        assertEquals(List.of("KNEE"), list(o.get("injuries")));
        assertEquals("Asma", o.get("medicalNotes").asText());
        assertTrue(o.get("foodAllergy").asBoolean());
        String consentAt = o.get("healthConsentAt").asText();
        assertFalse(o.get("healthConsentAt").isNull());
        // Volver a enviar true no cambia la fecha del consentimiento.
        assertEquals(consentAt, put("{\"healthConsent\":true}", 200).get("healthConsentAt").asText());

        o = put("{\"healthConsent\":false}", 200);
        assertFalse(o.get("healthConsent").asBoolean());
        assertEquals(0, o.get("injuries").size());
        assertTrue(o.get("allergyDetails").isNull());
        // Al volver a darlo, lo borrado no reaparece.
        o = put("{\"healthConsent\":true}", 200);
        assertTrue(o.get("medicalNotes").isNull());
        assertEquals(0, o.get("conditions").size());
        assertEquals("MOSTLY", o.get("cooks").asText());
    }

    @Test void validaciones() throws Exception {
        put("{\"experience\":\"EXPERT\"}", 400);                         // fuera de lista
        put("{\"gender\":\"NONE\"}", 400);
        put("{\"goals\":[\"FAT_LOSS\",\"FAT_LOSS\"]}", 400);             // duplicado
        put("{\"likedExercises\":[\"UNSURE\",\"MACHINES\"]}", 400);      // excluyentes combinados
        put("{\"avoid\":[\"NOTHING\",\"LEGS\"]}", 400);
        put("{\"healthConsent\":true,\"injuries\":[\"NONE\",\"KNEE\"]}", 400);
        put("{\"techniqueLevel\":6}", 400);                              // rangos
        put("{\"mealsPerDay\":0}", 400);
        put("{\"weightKg\":19}", 400);
        put("{\"heightCm\":261}", 400);
        put("{\"birthDate\":\"" + LocalDate.now().minusYears(13) + "\"}", 400);
        put("{\"birthDate\":\"" + LocalDate.now().minusYears(102) + "\"}", 400);
        put("{\"birthDate\":\"15/05/1990\"}", 400);
        put("{\"mustKeep\":\"" + "x".repeat(501) + "\"}", 400);          // longitud
        put("{\"goals\":\"FAT_LOSS\"}", 400);                            // tipo
        put("[]", 400);
        // Un 400 no deja nada a medias.
        put("{\"cardio\":\"MINIMAL\",\"heightCm\":20}", 400);
        assertTrue(send("GET", "/api/me/onboarding", client, null, 200).get("cardio").isNull());
        assertTrue(profiles.findById(clientId).map(p -> p.getHeightCm() == null).orElse(true));
    }

    @Test void completarEsIdempotenteYAvisaUnaVezACadaResponsable() throws Exception {
        // Su entrenador lleva también la nutrición: un solo aviso aunque sea responsable de ambas.
        JsonNode o = send("POST", "/api/me/onboarding/complete", client, null, 200);
        String completedAt = o.get("completedAt").asText();
        assertFalse(o.get("completedAt").isNull());
        assertEquals(completedAt, send("POST", "/api/me/onboarding/complete", client, null, 200).get("completedAt").asText());
        assertEquals(1, onboardingNotifications(trainer));
        assertTrue(send("GET", "/api/auth/me", client, null, 200).get("onboardingCompleted").asBoolean());
        assertTrue(send("GET", "/api/auth/me", trainer, null, 200).get("onboardingCompleted").isNull());
    }

    @Test void conNutricionistaAvisaAAmbos() throws Exception {
        AppUser c = users.findById(clientId).orElseThrow(); c.setNutritionistId(nutritionistId); users.save(c);
        send("POST", "/api/me/onboarding/complete", client, null, 200);
        send("POST", "/api/me/onboarding/complete", client, null, 200);
        assertEquals(1, onboardingNotifications(trainer));
        assertEquals(1, onboardingNotifications(nutritionist));
        assertEquals(0, onboardingNotifications(otherTrainer));
    }

    @Test void quienVeLasRespuestas() throws Exception {
        AppUser c = users.findById(clientId).orElseThrow(); c.setNutritionistId(nutritionistId); users.save(c);
        put("{\"healthConsent\":true,\"medicalNotes\":\"Asma\",\"dietStyle\":\"FLEXIBLE\"}", 200);
        String url = "/api/clients/" + clientId + "/onboarding";
        assertEquals("Asma", send("GET", url, trainer, null, 200).get("medicalNotes").asText());
        assertEquals("FLEXIBLE", send("GET", url, nutritionist, null, 200).get("dietStyle").asText());
        send("GET", url, admin, null, 200);
        send("GET", url, otherTrainer, null, 403);
        send("GET", url, collaborator, null, 403);
        send("GET", url, client, null, 403);
        send("PUT", url, trainer, "{}", 405);
    }

    @Test void soloElClienteUsaSuAsistente() throws Exception {
        send("GET", "/api/me/onboarding", trainer, null, 403);
        send("PUT", "/api/me/onboarding", admin, "{\"cardio\":\"MINIMAL\"}", 403);
        send("POST", "/api/me/onboarding/complete", trainer, null, 403);
        send("GET", "/api/me/onboarding", collaborator, null, 403);
    }
}
