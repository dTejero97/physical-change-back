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
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Logo del profesional (T183, docs/api-logo.md): lo sube el profesional y lo ve su cliente. */
@SpringBootTest
@AutoConfigureMockMvc
class ProfessionalLogoTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    @Test void elClienteVeElLogoDeSuProfesional() throws Exception {
        AppUser t = save("t-" + UUID.randomUUID() + "@test", Role.TRAINER, null);
        AppUser other = save("o-" + UUID.randomUUID() + "@test", Role.TRAINER, null);
        AppUser c = save("c-" + UUID.randomUUID() + "@test", Role.CLIENT, t.getId());
        String pro = token(t), client = token(c);

        assertTrue(send("GET", "/api/auth/me", client, null, 200).get("logoUrl").isNull());
        long file = upload(pro, png(64, 64, false));
        long foreign = upload(token(other), png(32, 32, false));

        send("PUT", "/api/auth/me/logo", pro, "{\"fileId\":" + foreign + "}", 403);       // archivo de otro
        send("PUT", "/api/auth/me/logo", pro, "{\"fileId\":999999}", 404);                 // no existe
        JsonNode me = send("PUT", "/api/auth/me/logo", pro, "{\"fileId\":" + file + "}", 200);
        assertTrue(me.get("logoUrl").asText().contains("/public/files/" + file));
        assertTrue(send("GET", "/api/auth/me", client, null, 200).get("logoUrl").asText().contains("/public/files/" + file));

        send("PUT", "/api/auth/me/logo", client, "{\"fileId\":" + file + "}", 403);        // un cliente no tiene logo
        long big = upload(pro, png(1400, 1400, true));                                       // ruido: PNG de varios MB
        send("PUT", "/api/auth/me/logo", pro, "{\"fileId\":" + big + "}", 413);

        assertTrue(send("DELETE", "/api/auth/me/logo", pro, null, 200).get("logoUrl").isNull());
        assertTrue(send("GET", "/api/auth/me", client, null, 200).get("logoUrl").isNull());
    }

    private long upload(String auth, byte[] png) throws Exception {
        String text = mvc.perform(multipart("/api/files").file(new MockMultipartFile("file", "logo.png", "image/png", png))
                        .param("purpose", "IMAGE").header("Authorization", auth))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(text).get("id").asLong();
    }

    private static byte[] png(int w, int h, boolean noise) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(7);
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) img.setRGB(x, y, noise ? r.nextInt() : 0xFF6A2B);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
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
        String text = mvc.perform(req).andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }
}
