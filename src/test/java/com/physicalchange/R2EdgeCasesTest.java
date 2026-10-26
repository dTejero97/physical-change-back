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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** QA (T91): casos límite de la subida directa a Cloudflare R2 (ADR-005), con el cliente S3 simulado. */
@SpringBootTest(properties = {
        "app.storage.type=r2",
        "app.storage.r2.account-id=testaccount",
        "app.storage.r2.access-key-id=test-key",
        "app.storage.r2.secret-access-key=test-secret",
        "app.storage.r2.bucket=pc-test"})
@AutoConfigureMockMvc
class R2EdgeCasesTest {
    static final byte[] MP4_HEAD = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0};
    static final byte[] MOV_HEAD = {0, 0, 0, 0x14, 'f', 't', 'y', 'p', 'q', 't', ' ', ' ', 0, 0, 0, 0};
    static final byte[] WEBM_HEAD = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    static final long MB = 1024L * 1024;

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired StoredFileRepository files;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.physicalchange.service.FileService fileService;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @MockBean S3Client s3;

    private Long trainerId;
    private String trainer, otherTrainer;

    @BeforeEach void data() {
        reset(s3);
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        trainerId = save("qa-t-" + sfx + "@test").getId();
        trainer = token(trainerId);
        otherTrainer = token(save("qa-ot-" + sfx + "@test").getId());
    }

    private AppUser save(String email) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName("[QA] " + email); u.setRole(Role.TRAINER);
        u.setPasswordHash(enc.encode("Password1!")); return users.save(u);
    }

    private String token(Long id) { return "Bearer " + jwt.generate(new AuthUser(id, "x", Role.TRAINER)); }

    private JsonNode send(String method, String url, String auth, String body, int status) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).contentType("application/json").header("Authorization", auth);
        if (body != null) req.content(body);
        return read(mvc.perform(req).andExpect(status().is(status)).andReturn());
    }

    private JsonNode read(MvcResult r) throws Exception {
        String text = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }

    private static String file(String name, long size) { return "{\"name\":\"" + name + "\",\"purpose\":\"VIDEO\",\"sizeBytes\":" + size + "}"; }

    private long pending(String auth, String name, long size) throws Exception {
        return send("POST", "/api/files/uploads", auth, file(name, size), 201).get("fileId").asLong();
    }

    private void uploaded(long size, byte[] head) {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().contentLength(size).build());
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), head));
    }

    @Test void completeRepetidoEsIdempotente() throws Exception {
        long id = pending(trainer, "clase.mp4", 2000);
        uploaded(2000, MP4_HEAD);
        JsonNode first = send("POST", "/api/files/" + id + "/complete", trainer, null, 200);
        JsonNode again = send("POST", "/api/files/" + id + "/complete", trainer, null, 200);
        assertEquals(first.get("id").asLong(), again.get("id").asLong());
        // La segunda llamada no vuelve a consultar R2.
        verify(s3, times(1)).headObject(any(HeadObjectRequest.class));
        assertEquals("READY", files.findById(id).orElseThrow().getStatus());
    }

    @Test void borrarPendienteYPermisos() throws Exception {
        long id = pending(trainer, "clase.mp4", 2000);
        send("DELETE", "/api/files/" + id, otherTrainer, null, 403);
        send("POST", "/api/files/" + id + "/complete", otherTrainer, null, 403);
        assertTrue(files.findById(id).isPresent());
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));

        send("DELETE", "/api/files/" + id, trainer, null, 204);
        verify(s3).deleteObject(any(DeleteObjectRequest.class));
        assertTrue(files.findById(id).isEmpty());
        send("POST", "/api/files/" + id + "/complete", trainer, null, 404);
        send("DELETE", "/api/files/999999999", trainer, null, 404);
    }

    @Test void pendienteNoSeDescarga() throws Exception {
        long id = pending(trainer, "clase.mp4", 2000);
        // URL firmada válida, pero el archivo aún no está subido: 404 y sin redirección a R2.
        mvc.perform(get("/api" + fileService.url(id))).andExpect(status().isNotFound());
    }

    @Test void cuotaCuentaPendientesYPurgaLosAbandonados() throws Exception {
        long[] ids = new long[4];
        for (int i = 0; i < 4; i++) ids[i] = pending(trainer, "v" + i + ".mp4", 450 * MB);
        send("POST", "/api/files/uploads", trainer, file("v5.mp4", 450 * MB), 409);

        // Dos abandonados hace más de 24 h: se purgan al pedir la siguiente subida y liberan cuota.
        jdbc.update("update stored_file set created_at = ? where id in (?, ?)", LocalDateTime.now().minusHours(25), ids[0], ids[1]);
        long fresh = pending(trainer, "v5.mp4", 450 * MB);
        assertTrue(files.findById(ids[0]).isEmpty());
        assertTrue(files.findById(ids[1]).isEmpty());
        assertTrue(files.findById(ids[2]).isPresent());
        assertTrue(files.findById(fresh).isPresent());
        verify(s3, times(2)).deleteObject(any(DeleteObjectRequest.class));
        assertEquals(3 * 450 * MB, files.usedBytes(trainerId));
    }

    /** Antes del arreglo de T92, 3 de cada 10 rondas dejaban pasar las dos subidas (2500 MB con cuota de 2048 MB). */
    @Test void cuotaConcurrenteSoloCabeUno() throws Exception {
        for (int round = 0; round < 10; round++) {
            // Base H2 compartida: sin limpiar, a la 3.ª ronda salta el tope global de R2 (9216 MB) y no la cuota.
            jdbc.update("delete from stored_file_data");
            jdbc.update("delete from stored_file");
            AppUser u = save("qa-race-" + round + "-" + UUID.randomUUID().toString().substring(0, 6) + "@test");
            String auth = token(u.getId());
            for (int i = 0; i < 3; i++) pending(auth, "base" + i + ".mp4", 500 * MB);
            java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(2);
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            java.util.List<java.util.concurrent.Future<Integer>> fs = new java.util.ArrayList<>();
            for (int t = 0; t < 2; t++) fs.add(ex.submit(() -> {
                go.await();
                return mvc.perform(post("/api/files/uploads").header("Authorization", auth).contentType("application/json")
                        .content(file("carrera.mp4", 500 * MB))).andReturn().getResponse().getStatus();
            }));
            go.countDown();
            int created = 0, conflict = 0;
            java.util.List<Integer> codes = new java.util.ArrayList<>();
            for (var f : fs) { int s = f.get(); codes.add(s); if (s == 201) created++; else if (s == 409) conflict++; }
            ex.shutdown();
            assertEquals(1, created, "ronda " + round + " códigos " + codes);
            assertEquals(1, conflict, "ronda " + round + " códigos " + codes);
            assertEquals(2000 * MB, files.usedBytes(u.getId()), "ronda " + round);
        }
    }

    @Test void webmYMovConFirmaCorrecta() throws Exception {
        long webm = pending(trainer, "clase.webm", 3000);
        uploaded(3000, WEBM_HEAD);
        assertEquals("video/webm", send("POST", "/api/files/" + webm + "/complete", trainer, null, 200).get("contentType").asText());

        reset(s3);
        long mov = pending(trainer, "clase.MOV", 3000);
        uploaded(3000, MOV_HEAD);
        assertEquals("video/quicktime", send("POST", "/api/files/" + mov + "/complete", trainer, null, 200).get("contentType").asText());

        reset(s3);
        // Un .webm con firma de mp4 se rechaza y se borra.
        long fake = pending(trainer, "falso.webm", 3000);
        uploaded(3000, MP4_HEAD);
        send("POST", "/api/files/" + fake + "/complete", trainer, null, 400);
        assertTrue(files.findById(fake).isEmpty());
    }

    @Test void nombreConRutaSeLimpia() throws Exception {
        long id = pending(trainer, "../../etc/passwd.mp4", 10);
        assertEquals("passwd.mp4", files.findById(id).orElseThrow().getName());
    }
}
