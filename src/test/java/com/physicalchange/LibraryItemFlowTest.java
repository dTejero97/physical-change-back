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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Librería en el servidor: elementos JSON, imágenes enlazadas y preferencias (docs/api-libreria.md, T27). */
@SpringBootTest
@AutoConfigureMockMvc
class LibraryItemFlowTest {
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired StoredFileRepository storedFiles;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private String trainer, otherTrainer, client;

    @BeforeEach void data() {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        Long trainerId = save("t-" + sfx + "@test", Role.TRAINER, null).getId();
        Long otherId = save("ot-" + sfx + "@test", Role.TRAINER, null).getId();
        Long clientId = save("c-" + sfx + "@test", Role.CLIENT, trainerId).getId();
        trainer = token(trainerId, Role.TRAINER);
        otherTrainer = token(otherId, Role.TRAINER);
        client = token(clientId, Role.CLIENT);
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
        return read(mvc.perform(req).andExpect(status().is(status)).andReturn());
    }

    private long image(String auth) throws Exception {
        var req = multipart("/api/files").file(new MockMultipartFile("file", "foto.png", "image/png", PNG)).param("purpose", "IMAGE");
        req.header("Authorization", auth);
        return read(mvc.perform(req).andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private JsonNode read(MvcResult r) throws Exception {
        String text = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }

    @Test void recetaConFotosSeCreaEditaYBorraConSusArchivos() throws Exception {
        long f1 = image(trainer), f2 = image(trainer);
        JsonNode r = send("POST", "/api/library/items", trainer,
                "{\"kind\":\"RECIPE\",\"name\":\" Tortitas \",\"data\":{\"servings\":2,\"items\":[{\"name\":\"Avena\",\"qty\":80}],\"videoUrl\":\"https://youtu.be/xrm19Xx9fVQ\"},\"fileIds\":[" + f1 + "," + f2 + "]}", 201);
        long id = r.get("id").asLong();
        assertEquals("Tortitas", r.get("name").asText());
        assertEquals(2, r.get("data").get("servings").asInt());
        assertEquals(2, r.get("files").size());
        assertTrue(r.get("files").get(0).get("url").asText().startsWith("/public/files/" + f1 + "?exp="));

        JsonNode list = send("GET", "/api/library/items?kind=RECIPE", trainer, null, 200);
        assertEquals(1, list.size());
        assertEquals(0, send("GET", "/api/library/items?kind=VIDEO", trainer, null, 200).size());

        // Quitar una foto la borra del almacenamiento
        send("PUT", "/api/library/items/" + id, trainer, "{\"kind\":\"RECIPE\",\"name\":\"Tortitas\",\"data\":{\"servings\":4},\"fileIds\":[" + f2 + "]}", 200);
        assertFalse(storedFiles.existsById(f1));
        assertTrue(storedFiles.existsById(f2));

        // No se puede cambiar el tipo
        send("PUT", "/api/library/items/" + id, trainer, "{\"kind\":\"VIDEO\",\"name\":\"x\",\"data\":{}}", 400);

        send("DELETE", "/api/library/items/" + id, trainer, null, 204);
        assertFalse(storedFiles.existsById(f2));
        send("GET", "/api/library/items/" + id, trainer, null, 404);
    }

    @Test void validacionDelContenido() throws Exception {
        send("POST", "/api/library/items", trainer, "{\"kind\":\"VIDEO\",\"name\":\"x\",\"data\":[1,2]}", 400);
        send("POST", "/api/library/items", trainer, "{\"kind\":\"OTRO\",\"name\":\"x\",\"data\":{}}", 400);
        send("POST", "/api/library/items", trainer, "{\"kind\":\"CARDIO\",\"name\":\"x\",\"data\":{\"cover\":\"data:image/png;base64,AAAA\"}}", 400);
        String big = "a".repeat(210 * 1024);
        send("POST", "/api/library/items", trainer, "{\"kind\":\"FOOD\",\"name\":\"x\",\"data\":{\"t\":\"" + big + "\"}}", 400);
    }

    @Test void aislamientoEntreProfesionalesYClientes() throws Exception {
        long foto = image(trainer);
        long id = send("POST", "/api/library/items", trainer, "{\"kind\":\"CARDIO\",\"name\":\"Remo\",\"data\":{\"cover\":\"row\"}}", 201).get("id").asLong();
        send("GET", "/api/library/items/" + id, otherTrainer, null, 404);
        send("PUT", "/api/library/items/" + id, otherTrainer, "{\"kind\":\"CARDIO\",\"name\":\"x\",\"data\":{}}", 404);
        send("DELETE", "/api/library/items/" + id, otherTrainer, null, 404);
        assertEquals(0, send("GET", "/api/library/items?kind=CARDIO", otherTrainer, null, 200).size());
        // Un archivo ajeno no se puede enlazar
        send("POST", "/api/library/items", otherTrainer, "{\"kind\":\"RECIPE\",\"name\":\"x\",\"data\":{},\"fileIds\":[" + foto + "]}", 403);
        // El cliente no accede
        send("GET", "/api/library/items?kind=CARDIO", client, null, 403);
        send("GET", "/api/library/prefs/EXERCISE_META", client, null, 403);
    }

    @Test void unaImagenSoloPerteneceAUnElemento() throws Exception {
        long foto = image(trainer);
        send("POST", "/api/library/items", trainer, "{\"kind\":\"RECIPE\",\"name\":\"A\",\"data\":{},\"fileIds\":[" + foto + "]}", 201);
        send("POST", "/api/library/items", trainer, "{\"kind\":\"RECIPE\",\"name\":\"B\",\"data\":{},\"fileIds\":[" + foto + "]}", 409);
    }

    @Test void preferenciasPorClave() throws Exception {
        JsonNode empty = send("GET", "/api/library/prefs/EXERCISE_META", trainer, null, 200);
        assertTrue(empty.get("data").isObject() && empty.get("data").isEmpty());
        send("PUT", "/api/library/prefs/EXERCISE_META", trainer, "{\"data\":{\"12\":{\"favorite\":true}}}", 200);
        assertTrue(send("GET", "/api/library/prefs/EXERCISE_META", trainer, null, 200).get("data").get("12").get("favorite").asBoolean());
        assertTrue(send("GET", "/api/library/prefs/EXERCISE_META", otherTrainer, null, 200).get("data").isEmpty());
        send("PUT", "/api/library/prefs/OTRA", trainer, "{\"data\":{}}", 400);
        // Imagen citada como file:<id>: URL firmada solo si es propia
        long mine = image(trainer), other = image(otherTrainer);
        JsonNode p = send("PUT", "/api/library/prefs/EXERCISE_META", trainer,
                "{\"data\":{\"5\":{\"image\":\"file:" + mine + "\"},\"6\":{\"image\":\"file:" + other + "\"}}}", 200);
        assertEquals(1, p.get("files").size());
        assertEquals(mine, p.get("files").get(0).get("id").asLong());
        send("PUT", "/api/library/prefs/EXERCISE_META", trainer, "{\"data\":{\"1\":{\"image\":\"data:image/png;base64,AA\"}}}", 400);
    }
}
