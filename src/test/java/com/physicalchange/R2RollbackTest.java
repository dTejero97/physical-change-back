package com.physicalchange;

import com.physicalchange.domain.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.service.FileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Vuelta a STORAGE_TYPE=database con R2 configurado (plan de reversión, ADR-005): lo nuevo va a la base y lo de R2 se sigue sirviendo. */
@SpringBootTest(properties = {
        "app.storage.type=database",
        "app.storage.r2.account-id=testaccount",
        "app.storage.r2.access-key-id=test-key",
        "app.storage.r2.secret-access-key=test-secret",
        "app.storage.r2.bucket=pc-test"})
@AutoConfigureMockMvc
class R2RollbackTest {
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired StoredFileRepository files;
    @Autowired FileService fileService;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @MockBean S3Client s3;

    @Test void lonuevoVaALaBaseYLoDeR2SigueAccesible() throws Exception {
        AppUser u = new AppUser(); u.setEmail("rb-" + UUID.randomUUID() + "@test"); u.setFullName("RB"); u.setRole(Role.TRAINER);
        u.setPasswordHash(enc.encode("Password1!")); u = users.save(u);
        String auth = "Bearer " + jwt.generate(new AuthUser(u.getId(), "x", Role.TRAINER));

        var req = multipart("/api/files").file(new MockMultipartFile("file", "logo.png", "image/png", PNG))
                .param("purpose", "IMAGE").header("Authorization", auth);
        mvc.perform(req).andExpect(status().isCreated());
        Long owner = u.getId();
        assertTrue(files.findAll().stream().filter(f -> f.getOwnerId().equals(owner)).allMatch(f -> "DB".equals(f.getStorage())));

        mvc.perform(post("/api/files/uploads").header("Authorization", auth).contentType("application/json")
                .content("{\"name\":\"a.mp4\",\"purpose\":\"VIDEO\",\"sizeBytes\":10}")).andExpect(status().isConflict());

        StoredFile old = new StoredFile();
        old.setOwnerId(u.getId()); old.setName("antiguo.mp4"); old.setContentType("video/mp4"); old.setSizeBytes(10L);
        old.setPurpose("VIDEO"); old.setStorage("R2");
        old = files.save(old);
        String location = mvc.perform(get("/api" + fileService.url(old.getId())))
                .andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        assertNotNull(location);
        assertTrue(location.startsWith("https://testaccount.r2.cloudflarestorage.com/pc-test/files/"), location);
    }
}
