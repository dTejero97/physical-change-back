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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Almacenamiento en Cloudflare R2 (ADR-005) con el cliente S3 simulado; las URLs prefirmadas se firman de verdad. */
@SpringBootTest(properties = {
        "app.storage.type=r2",
        "app.storage.r2.account-id=testaccount",
        "app.storage.r2.access-key-id=test-key",
        "app.storage.r2.secret-access-key=test-secret",
        "app.storage.r2.bucket=pc-test"})
@AutoConfigureMockMvc
class R2StorageFlowTest {
    static final byte[] MP4_HEAD = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0};
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired StoredFileRepository files;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @MockBean S3Client s3;

    private String trainer, otherTrainer;

    @BeforeEach void data() {
        reset(s3);
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        trainer = token(save("t-" + sfx + "@test").getId());
        otherTrainer = token(save("ot-" + sfx + "@test").getId());
    }

    private AppUser save(String email) {
        AppUser u = new AppUser(); u.setEmail(email); u.setFullName("U " + email); u.setRole(Role.TRAINER);
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

    private static String video(long size) { return "{\"name\":\"Sentadilla técnica.mp4\",\"purpose\":\"VIDEO\",\"sizeBytes\":" + size + "}"; }

    private void uploaded(long size, byte[] head) {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().contentLength(size).build());
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), head));
    }

    @Test void subidaDirectaDeVideoYDescargaRedirigida() throws Exception {
        JsonNode up = send("POST", "/api/files/uploads", trainer, video(120_000_000), 201);
        long id = up.get("fileId").asLong();
        String url = up.get("uploadUrl").asText();
        assertTrue(url.startsWith("https://testaccount.r2.cloudflarestorage.com/pc-test/uploads/"), url);
        String signed = url.substring(url.indexOf("X-Amz-SignedHeaders=")).split("&")[0];
        assertTrue(signed.contains("content-disposition") && signed.contains("content-length") && signed.contains("content-type"), signed);
        assertTrue(url.contains("X-Amz-Signature="), url);
        assertEquals("PUT", up.get("method").asText());
        assertEquals("video/mp4", up.get("headers").get("Content-Type").asText());
        assertTrue(up.get("headers").get("Content-Disposition").asText().startsWith("inline;"));
        assertEquals("PENDING", files.findById(id).orElseThrow().getStatus());

        // Aún no subido: no se puede usar ni descargar.
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        send("POST", "/api/files/" + id + "/complete", trainer, null, 400);

        reset(s3);
        uploaded(120_000_000, MP4_HEAD);
        send("POST", "/api/files/" + id + "/complete", otherTrainer, null, 403);
        JsonNode f = send("POST", "/api/files/" + id + "/complete", trainer, null, 200);
        assertEquals("video/mp4", f.get("contentType").asText());
        assertEquals("READY", files.findById(id).orElseThrow().getStatus());
        // Validado: se copia de uploads/ a files/ y se borra el temporal (la URL PUT ya no alcanza la copia buena).
        var copy = org.mockito.ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3).copyObject(copy.capture());
        assertEquals("uploads/", copy.getValue().sourceKey().substring(0, 8));
        assertEquals("files/", copy.getValue().destinationKey().substring(0, 6));
        verify(s3).deleteObject(any(DeleteObjectRequest.class));
        reset(s3);

        String location = mvc.perform(get("/api" + f.get("url").asText()))
                .andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        assertNotNull(location);
        assertTrue(location.startsWith("https://testaccount.r2.cloudflarestorage.com/pc-test/files/"), location);
        assertTrue(location.contains("X-Amz-Expires=3600"), location);

        send("DELETE", "/api/files/" + id, trainer, null, 204);
        verify(s3).deleteObject(any(DeleteObjectRequest.class));
        assertTrue(files.findById(id).isEmpty());
    }

    @Test void contenidoOTamanoFalsosSeBorran() throws Exception {
        long id = send("POST", "/api/files/uploads", trainer, video(1000), 201).get("fileId").asLong();
        uploaded(1000, "<html>no soy vídeo".getBytes());
        send("POST", "/api/files/" + id + "/complete", trainer, null, 400);
        assertTrue(files.findById(id).isEmpty());

        reset(s3);
        long id2 = send("POST", "/api/files/uploads", trainer, video(1000), 201).get("fileId").asLong();
        uploaded(999_999, MP4_HEAD);
        send("POST", "/api/files/" + id2 + "/complete", trainer, null, 400);
        assertTrue(files.findById(id2).isEmpty());
        verify(s3).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test void limitesYTipos() throws Exception {
        send("POST", "/api/files/uploads", trainer, video(600L * 1024 * 1024), 413);
        send("POST", "/api/files/uploads", trainer, "{\"name\":\"virus.exe\",\"purpose\":\"VIDEO\",\"sizeBytes\":10}", 400);
        send("POST", "/api/files/uploads", trainer, "{\"name\":\"logo.svg\",\"purpose\":\"IMAGE\",\"sizeBytes\":10}", 400);
        send("POST", "/api/files/uploads", trainer, "{\"name\":\"a.mp4\",\"purpose\":\"OTRO\",\"sizeBytes\":10}", 400);
        // Cuota de 2 GB con R2: cinco vídeos de 450 MB no caben.
        for (int i = 0; i < 4; i++) send("POST", "/api/files/uploads", trainer, video(450L * 1024 * 1024), 201);
        send("POST", "/api/files/uploads", trainer, video(450L * 1024 * 1024), 409);
    }

    @Test void subidaPequenaPorElBackendVaAR2() throws Exception {
        var req = multipart("/api/files").file(new MockMultipartFile("file", "logo.png", "image/png", PNG))
                .param("purpose", "IMAGE").header("Authorization", trainer);
        long id = read(mvc.perform(req).andExpect(status().isCreated()).andReturn()).get("id").asLong();
        verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        StoredFile f = files.findById(id).orElseThrow();
        assertEquals("R2", f.getStorage());
        assertEquals("READY", f.getStatus());
    }
}
