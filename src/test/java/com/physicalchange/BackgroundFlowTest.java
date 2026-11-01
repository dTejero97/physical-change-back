package com.physicalchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.domain.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.service.BackgroundService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.physicalchange.BackgroundImagesTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Fondos de la página de carga (T208, docs/api-fondos.md): validación, permisos, endpoint público y borrado (L-05). */
@SpringBootTest
@AutoConfigureMockMvc
class BackgroundFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired LoadingBackgroundRepository backgrounds;
    @Autowired LoadingBackgroundSettingRepository settings;
    @Autowired StoredFileRepository storedFiles;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private String admin, trainer, otherTrainer, client, collaborator;
    private Long trainerId;
    private static byte[] validJpeg;

    @BeforeAll static void images() throws Exception { validJpeg = image(1920, 1080, "jpg"); }

    @BeforeEach void data() {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        admin = token(save("a-" + sfx + "@test", Role.ADMIN, null).getId(), Role.ADMIN);
        trainerId = save("t-" + sfx + "@test", Role.TRAINER, null).getId();
        trainer = token(trainerId, Role.TRAINER);
        otherTrainer = token(save("ot-" + sfx + "@test", Role.TRAINER, null).getId(), Role.TRAINER);
        client = token(save("c-" + sfx + "@test", Role.CLIENT, trainerId).getId(), Role.CLIENT);
        collaborator = token(save("co-" + sfx + "@test", Role.COLLABORATOR, null).getId(), Role.COLLABORATOR);
        // Cada test empieza con el fondo por defecto activo.
        LoadingBackgroundSetting s = settings.findById(LoadingBackgroundSetting.SINGLETON).orElseThrow();
        s.change(backgrounds.findByBuiltinKey("fondo4").orElseThrow().getId(), null, null);
        settings.save(s);
    }

    private AppUser save(String email, Role role, Long trainerOf) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName("U " + email); u.setRole(role); u.setTrainerId(trainerOf);
        u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }

    private String token(Long id, Role role) { return "Bearer " + jwt.generate(new AuthUser(id, "x", role)); }

    private JsonNode send(String method, String url, String auth, String body, int status) throws Exception {
        var req = request(HttpMethod.valueOf(method), url).contentType("application/json");
        if (auth != null) req.header("Authorization", auth);
        if (body != null) req.content(body);
        return read(mvc.perform(req).andExpect(status().is(status)).andReturn());
    }

    private JsonNode upload(String auth, String name, String contentType, byte[] bytes, int status) throws Exception {
        var req = multipart("/api/backgrounds").file(new MockMultipartFile("file", name, contentType, bytes));
        if (auth != null) req.header("Authorization", auth);
        return read(mvc.perform(req).andExpect(status().is(status)).andReturn());
    }

    private JsonNode read(MvcResult r) throws Exception {
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }

    private static List<String> codes(JsonNode err) {
        List<String> c = new ArrayList<>();
        err.get("reasons").forEach(r -> c.add(r.get("code").asText()));
        return c;
    }

    // ---------- Validación ----------

    @Test void subidaValidaGeneraVariantesYQuedaEnLaGaleria() throws Exception {
        JsonNode b = upload(trainer, "gimnasio.jpg", "image/jpeg", validJpeg, 201);
        assertEquals("gimnasio.jpg", b.get("name").asText());
        assertFalse(b.get("builtin").asBoolean());
        assertFalse(b.get("active").asBoolean());
        assertEquals(1920, b.get("width").asInt());
        assertEquals(1080, b.get("height").asInt());
        assertFalse(b.get("portrait").asBoolean());
        assertTrue(b.get("canDelete").asBoolean());
        assertEquals(trainerId, b.get("uploadedBy").get("id").asLong());
        assertTrue(b.get("lqip").asText().startsWith("data:image/jpeg;base64,"));
        Set<String> names = new HashSet<>();
        b.get("variants").forEach(v -> names.add(v.get("name").asText()));
        assertEquals(Set.of("w768", "w1280", "w1920", "p1080", "thumb"), names);
        // No activo: las URL van firmadas.
        assertTrue(b.get("original").get("url").asText().contains("sig="));

        JsonNode g = send("GET", "/api/backgrounds", trainer, null, 200);
        assertEquals(backgrounds.findByBuiltinKey("fondo4").orElseThrow().getId(), g.get("active").get("backgroundId").asLong());
        JsonNode first = g.get("items").get(0);
        assertTrue(first.get("active").asBoolean());           // el activo primero
        assertEquals(b.get("id").asLong(), g.get("items").get(1).get("id").asLong()); // después los subidos
        JsonNode last = g.get("items").get(g.get("items").size() - 1);
        assertTrue(last.get("builtin").asBoolean());           // al final los iniciales
        assertFalse(last.get("canDelete").asBoolean());
    }

    @Test void imagenVerticalSeAceptaConAviso() throws Exception {
        JsonNode b = upload(trainer, "vertical.png", "image/png", image(1080, 1920, "png"), 201);
        assertTrue(b.get("portrait").asBoolean());
    }

    @Test void rechazoPorFormato() throws Exception {
        JsonNode e = upload(trainer, "foto.gif", "image/gif", validJpeg, 400);
        assertEquals("BACKGROUND_FORMAT", e.get("code").asText());
        e = upload(trainer, "foto.jpg", "image/svg+xml", validJpeg, 400);
        assertEquals("BACKGROUND_FORMAT", e.get("code").asText());
    }

    @Test void rechazoPorFirmaFalsa() throws Exception {
        JsonNode e = upload(trainer, "foto.jpg", "image/jpeg", "%PDF-1.4 no es una imagen".getBytes(), 400);
        assertEquals("BACKGROUND_SIGNATURE", e.get("code").asText());
        // PNG real con extensión .jpg: el contenido no coincide con la extensión.
        e = upload(trainer, "foto.jpg", "image/jpeg", image(1920, 1080, "png"), 400);
        assertEquals("BACKGROUND_SIGNATURE", e.get("code").asText());
    }

    @Test void rechazoPorDimensiones() throws Exception {
        JsonNode e = upload(trainer, "pequena.jpg", "image/jpeg", image(1600, 900, "jpg"), 400);
        assertEquals("BACKGROUND_TOO_SMALL", e.get("code").asText());
        assertEquals(1600, e.get("width").asInt());
        assertEquals(900, e.get("height").asInt());
        // Lado corto por debajo de 1080 aunque el largo llegue.
        e = upload(trainer, "panoramica.jpg", "image/jpeg", image(2400, 1000, "jpg"), 400);
        assertEquals("BACKGROUND_TOO_SMALL", e.get("code").asText());
        // WebP: se mide por la cabecera.
        e = upload(trainer, "p.webp", "image/webp", webpVp8x(1600, 900), 400);
        assertEquals("BACKGROUND_TOO_SMALL", e.get("code").asText());
    }

    @Test void rechazoPorTamano() throws Exception {
        byte[] big = Arrays.copyOf(validJpeg, (int) BackgroundService.MAX_BYTES + 1);
        JsonNode e = upload(trainer, "enorme.jpg", "image/jpeg", big, 413);
        assertEquals("BACKGROUND_TOO_LARGE", e.get("code").asText());
        // Varios motivos: se listan todos (el de tamaño primero).
        e = upload(trainer, "enorme.bmp", "image/bmp", big, 413);
        assertEquals(List.of("BACKGROUND_TOO_LARGE", "BACKGROUND_FORMAT"), codes(e));
    }

    @Test void rechazoPorIlegible() throws Exception {
        JsonNode e = upload(trainer, "vacia.jpg", "image/jpeg", new byte[0], 400);
        assertEquals("BACKGROUND_UNREADABLE", e.get("code").asText());
        e = upload(trainer, "rota.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 1, 2, 3, 4}, 400);
        assertEquals("BACKGROUND_UNREADABLE", e.get("code").asText());
        e = upload(trainer, "cortada.jpg", "image/jpeg", Arrays.copyOf(validJpeg, validJpeg.length / 2), 400);
        assertEquals("BACKGROUND_UNREADABLE", e.get("code").asText());
        e = upload(trainer, "riff.webp", "image/webp", Arrays.copyOf(webpVp8x(3840, 2160), 40), 400);
        assertEquals("BACKGROUND_UNREADABLE", e.get("code").asText());
    }

    @Test void bombaDeDescompresionSeRechazaSinDecodificar() throws Exception {
        long before = storedFiles.count();
        JsonNode e = upload(trainer, "bomba.png", "image/png", pngHeaderOnly(50_000, 50_000), 400);
        assertEquals("BACKGROUND_TOO_MANY_PIXELS", e.get("code").asText());
        assertEquals(50_000, e.get("width").asInt());
        e = upload(trainer, "tira.png", "image/png", pngHeaderOnly(20_000, 1_080), 400); // 21,6 MP pero lado > 16384
        assertEquals("BACKGROUND_TOO_MANY_PIXELS", e.get("code").asText());
        assertEquals(before, storedFiles.count());
    }

    @Test void webpSeAceptaSinVariantes() throws Exception {
        JsonNode b = upload(admin, "fondo.webp", "image/webp", webpVp8x(3840, 2160), 201);
        assertEquals("image/webp", b.get("contentType").asText());
        assertEquals(0, b.get("variants").size());
        assertTrue(b.get("lqip").isNull());
    }

    // ---------- Permisos ----------

    @Test void permisosPorRol() throws Exception {
        Long fondo3 = backgrounds.findByBuiltinKey("fondo3").orElseThrow().getId();
        for (String who : new String[]{client, collaborator}) {
            send("GET", "/api/backgrounds", who, null, 403);
            upload(who, "x.jpg", "image/jpeg", validJpeg, 403);
            send("PUT", "/api/backgrounds/active", who, "{\"backgroundId\":" + fondo3 + "}", 403);
            send("DELETE", "/api/backgrounds/" + fondo3, who, null, 403);
        }
        send("GET", "/api/backgrounds", null, null, 401);
        upload(null, "x.jpg", "image/jpeg", validJpeg, 401);
        send("PUT", "/api/backgrounds/active", null, "{\"backgroundId\":" + fondo3 + "}", 401);
        send("GET", "/api/backgrounds", admin, null, 200);
        send("GET", "/api/backgrounds", trainer, null, 200);
    }

    // ---------- Público ----------

    @Test void fondoActivoPublicoSinLogin() throws Exception {
        MvcResult r = mvc.perform(get("/api/public/backgrounds/active")).andExpect(status().isOk()).andReturn();
        assertTrue(r.getResponse().getHeader("Cache-Control").contains("public"));
        JsonNode a = read(r);
        assertTrue(a.get("builtin").asBoolean());
        assertEquals("Fondo 4", a.get("name").asText());
        assertEquals("50% 35%", a.get("objectPosition").asText());
        assertNull(a.get("uploadedBy"));   // sin datos personales
        assertNull(a.get("changedBy"));
        assertTrue(a.get("lqip").asText().startsWith("data:image/jpeg;base64,"));
        String etag = r.getResponse().getHeader("ETag");
        mvc.perform(get("/api/public/backgrounds/active").header("If-None-Match", etag)).andExpect(status().isNotModified());

        // Imagen original y variante, sin login, con caché pública y cabeceras de seguridad.
        String url = "/api" + a.get("original").get("url").asText();
        MvcResult img = mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg")).andReturn();
        assertEquals(298553, img.getResponse().getContentAsByteArray().length);
        assertTrue(img.getResponse().getHeader("Cache-Control").startsWith("public"));
        assertEquals("nosniff", img.getResponse().getHeader("X-Content-Type-Options"));
        JsonNode w1920 = null;
        for (JsonNode v : a.get("variants")) if (v.get("name").asText().equals("w1920")) w1920 = v;
        assertNotNull(w1920);
        mvc.perform(get("/api" + w1920.get("url").asText())).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
        mvc.perform(get("/api/public/backgrounds/active/w1920")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=300"));
        mvc.perform(get("/api/public/backgrounds/active/nada")).andExpect(status().isNotFound());
    }

    @Test void fondoSubidoNoActivoSoloConUrlFirmada() throws Exception {
        JsonNode b = upload(trainer, "privado.jpg", "image/jpeg", validJpeg, 201);
        long id = b.get("id").asLong();
        mvc.perform(get("/api/public/backgrounds/" + id + "/original")).andExpect(status().isNotFound());
        mvc.perform(get("/api" + b.get("original").get("url").asText())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, max-age=3600"));
        String tampered = b.get("original").get("url").asText().replace("/original?", "/w1920?");
        mvc.perform(get("/api" + tampered)).andExpect(status().isForbidden());
        mvc.perform(get("/api/public/backgrounds/" + id + "/original?exp=1&sig=x")).andExpect(status().isForbidden());
    }

    @Test void cambiarFondoRegistraQuienYCuando() throws Exception {
        JsonNode b = upload(trainer, "nuevo.jpg", "image/jpeg", validJpeg, 201);
        long id = b.get("id").asLong();
        JsonNode g = send("PUT", "/api/backgrounds/active", otherTrainer, "{\"backgroundId\":" + id + "}", 200);
        assertEquals(id, g.get("active").get("backgroundId").asLong());
        assertNotNull(g.get("active").get("changedBy").get("fullName").asText());
        assertFalse(g.get("active").get("changedAt").isNull());
        assertTrue(g.get("items").get(0).get("active").asBoolean());
        assertEquals(id, g.get("items").get(0).get("id").asLong());

        JsonNode a = send("GET", "/api/public/backgrounds/active", null, null, 200);
        assertEquals(id, a.get("id").asLong());
        assertFalse(a.get("builtin").asBoolean());
        // Ahora es público sin firma.
        mvc.perform(get("/api/public/backgrounds/" + id + "/thumb")).andExpect(status().isOk());

        send("PUT", "/api/backgrounds/active", trainer, "{\"backgroundId\":999999}", 404);
        send("PUT", "/api/backgrounds/active", trainer, "{}", 400);
    }

    // ---------- Borrado (L-05) ----------

    @Test void noSeBorranNiElActivoNiLosIniciales() throws Exception {
        Long fondo3 = backgrounds.findByBuiltinKey("fondo3").orElseThrow().getId();
        Long fondo4 = backgrounds.findByBuiltinKey("fondo4").orElseThrow().getId();
        assertEquals("BACKGROUND_BUILTIN", send("DELETE", "/api/backgrounds/" + fondo3, admin, null, 409).get("code").asText());
        assertEquals("BACKGROUND_BUILTIN", send("DELETE", "/api/backgrounds/" + fondo4, admin, null, 409).get("code").asText());

        JsonNode b = upload(trainer, "activo.jpg", "image/jpeg", validJpeg, 201);
        long id = b.get("id").asLong();
        send("PUT", "/api/backgrounds/active", trainer, "{\"backgroundId\":" + id + "}", 200);
        assertEquals("BACKGROUND_ACTIVE", send("DELETE", "/api/backgrounds/" + id, trainer, null, 409).get("code").asText());
        assertEquals("BACKGROUND_ACTIVE", send("DELETE", "/api/backgrounds/" + id, admin, null, 409).get("code").asText());
        assertTrue(backgrounds.existsById(id));
    }

    @Test void trainerSoloBorraLosSuyosYAdminCualquiera() throws Exception {
        JsonNode b = upload(trainer, "mio.jpg", "image/jpeg", validJpeg, 201);
        long id = b.get("id").asLong();
        assertEquals("BACKGROUND_NOT_OWNER", send("DELETE", "/api/backgrounds/" + id, otherTrainer, null, 403).get("code").asText());
        // En la galería del otro profesional no se puede borrar.
        JsonNode g = send("GET", "/api/backgrounds", otherTrainer, null, 200);
        for (JsonNode it : g.get("items")) if (it.get("id").asLong() == id) assertFalse(it.get("canDelete").asBoolean());

        long files = storedFiles.count();
        send("DELETE", "/api/backgrounds/" + id, trainer, null, 204);
        assertFalse(backgrounds.existsById(id));
        assertEquals(files - 6, storedFiles.count()); // original + 5 variantes
        send("DELETE", "/api/backgrounds/" + id, trainer, null, 404);

        long other = upload(otherTrainer, "ajeno.jpg", "image/jpeg", validJpeg, 201).get("id").asLong();
        send("DELETE", "/api/backgrounds/" + other, admin, null, 204);
    }

    @Test void losArchivosDeFondosNoSeBorranPorApiFiles() throws Exception {
        JsonNode b = upload(trainer, "f.jpg", "image/jpeg", validJpeg, 201);
        Long fileId = backgrounds.findById(b.get("id").asLong()).orElseThrow().getFileId();
        send("DELETE", "/api/files/" + fileId, trainer, null, 400);
        assertTrue(storedFiles.existsById(fileId));
    }
}
