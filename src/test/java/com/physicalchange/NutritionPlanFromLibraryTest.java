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

/**
 * T229 (docs/api-nutricion-plan-cliente.md): el plan asignado desde la Librería llega entero al cliente: macros objetivo,
 * alimentos del catálogo, recetas y alimentos sin catálogo (copia CUSTOM) y todas las opciones de cada comida.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NutritionPlanFromLibraryTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private Long clientId;
    private String pro, client;

    @BeforeEach void data() {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        AppUser t = save("n-" + sfx + "@test", Role.TRAINER, null);
        t.setNutritionEnabled(true); users.save(t);
        clientId = save("c-" + sfx + "@test", Role.CLIENT, t.getId()).getId();
        pro = token(t.getId(), Role.TRAINER);
        client = token(clientId, Role.CLIENT);
    }

    @Test void elClienteVeMacrosRecetasYTodasLasOpciones() throws Exception {
        long food = send("POST", "/api/nutrition/foods", pro,
                "{\"name\":\"Avena " + UUID.randomUUID().toString().substring(0, 4) + "\",\"kcal100g\":380,\"proteinG\":13,\"carbsG\":60,\"fatG\":7}", 201).get("id").asLong();
        long plan = send("POST", "/api/nutrition/plans", pro, "{\"clientId\":" + clientId + ",\"name\":\"Definición\",\"dailyKcal\":2000,"
                + "\"targetProteinG\":150,\"targetCarbsG\":200,\"targetFatG\":67}", 201).get("id").asLong();
        String meals = "/api/nutrition/plans/" + plan + "/meals";
        send("POST", meals, pro, "{\"mealName\":\"Desayuno\",\"foodId\":" + food + ",\"grams\":100}", 201);                           // opción 1, catálogo
        send("POST", meals, pro, "{\"mealName\":\"Desayuno\",\"optionNo\":2,\"name\":\"Tortitas de avena\",\"quantityLabel\":\"2 raciones\","
                + "\"kcal\":520.44,\"proteinG\":30,\"carbsG\":60,\"fatG\":15}", 201);                                                       // opción 2, receta
        send("POST", meals, pro, "{\"mealName\":\"Comida\",\"name\":\"Pechuga (demo)\",\"grams\":200,\"quantityLabel\":\"200 g\","
                + "\"kcal\":330,\"proteinG\":62,\"carbsG\":0,\"fatG\":7.2}", 201);                                                          // opción 1, sin catálogo

        JsonNode p = send("GET", "/api/nutrition/plans/client/" + clientId, client, null, 200).get(0);
        assertEquals(150, p.get("targetProteinG").asInt());
        assertEquals(200, p.get("targetCarbsG").asInt());
        assertEquals(67, p.get("targetFatG").asInt());
        assertEquals(3, p.get("meals").size());
        JsonNode recipe = null, demo = null;
        for (JsonNode m : p.get("meals")) {
            if ("Tortitas de avena".equals(m.get("foodName").asText())) recipe = m;
            if ("Pechuga (demo)".equals(m.get("foodName").asText())) demo = m;
        }
        assertNotNull(recipe); assertNotNull(demo);
        assertEquals(2, recipe.get("optionNo").asInt());
        assertTrue(recipe.get("custom").asBoolean());
        assertEquals("2 raciones", recipe.get("quantityLabel").asText());
        assertEquals(520.4, recipe.get("kcal").asDouble(), 0.001);
        assertTrue(recipe.get("foodId").isNull());
        // Totales del día: solo la opción 1 (avena 100 g = 380 kcal + pechuga 330 kcal); la receta de la opción 2 no suma.
        assertEquals(710, p.get("totals").get("kcal").asDouble(), 0.001);
        assertEquals(75, p.get("totals").get("proteinG").asDouble(), 0.001);
    }

    @Test void validaLasLineas() throws Exception {
        long plan = send("POST", "/api/nutrition/plans", pro, "{\"clientId\":" + clientId + ",\"name\":\"P\"}", 201).get("id").asLong();
        String meals = "/api/nutrition/plans/" + plan + "/meals";
        send("POST", meals, pro, "{\"mealName\":\"Cena\",\"name\":\"Sin macros\"}", 400);
        send("POST", meals, pro, "{\"mealName\":\"Cena\",\"kcal\":100,\"proteinG\":1,\"carbsG\":1,\"fatG\":1}", 400);
        send("POST", meals, pro, "{\"mealName\":\"Cena\",\"foodId\":999999,\"grams\":10}", 404);
        send("POST", meals, pro, "{\"mealName\":\"Cena\",\"foodId\":1}", 400);                                           // sin gramos
        send("POST", meals, pro, "{\"mealName\":\"Cena\",\"optionNo\":11,\"name\":\"X\",\"kcal\":1,\"proteinG\":1,\"carbsG\":1,\"fatG\":1}", 400);
        send("POST", meals, client, "{\"mealName\":\"Cena\",\"name\":\"X\",\"kcal\":1,\"proteinG\":1,\"carbsG\":1,\"fatG\":1}", 403);
    }

    private AppUser save(String email, Role role, Long trainerOf) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName("U " + email); u.setRole(role); u.setTrainerId(trainerOf);
        u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }

    private String token(Long id, Role role) { return "Bearer " + jwt.generate(new AuthUser(id, "x", role)); }

    private JsonNode send(String method, String url, String auth, String body, int status) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).contentType("application/json");
        if (auth != null) req.header("Authorization", auth);
        if (body != null) req.content(body);
        String text = mvc.perform(req).andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }
}
