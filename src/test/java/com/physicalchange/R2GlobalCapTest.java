package com.physicalchange;

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
import software.amazon.awssdk.services.s3.S3Client;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** QA (T91): tope global de R2 (STORAGE_GLOBAL_MAX_MB) para no salir del plan gratuito, sumando a todos los profesionales. */
@SpringBootTest(properties = {
        "app.storage.type=r2",
        "app.storage.global-max-mb=1",
        "app.storage.r2.account-id=testaccount",
        "app.storage.r2.access-key-id=test-key",
        "app.storage.r2.secret-access-key=test-secret",
        "app.storage.r2.bucket=pc-test"})
@AutoConfigureMockMvc
class R2GlobalCapTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder enc;
    @Autowired JwtService jwt;
    @MockBean S3Client s3;

    @BeforeEach void clean() {
        // La base H2 de los tests es compartida: el tope global cuenta todos los archivos de R2.
        jdbc.update("delete from stored_file_data");
        jdbc.update("delete from stored_file");
    }

    private String trainer() {
        AppUser u = new AppUser(); u.setEmail("qa-cap-" + UUID.randomUUID() + "@test"); u.setFullName("[QA] cap"); u.setRole(Role.TRAINER);
        u.setPasswordHash(enc.encode("Password1!"));
        return "Bearer " + jwt.generate(new AuthUser(users.save(u).getId(), "x", Role.TRAINER));
    }

    private void upload(String auth, long size, int status) throws Exception {
        mvc.perform(post("/api/files/uploads").header("Authorization", auth).contentType("application/json")
                .content("{\"name\":\"clase.mp4\",\"purpose\":\"VIDEO\",\"sizeBytes\":" + size + "}"))
                .andExpect(status().is(status));
    }

    @Test void elTopeGlobalSumaATodosLosProfesionales() throws Exception {
        String a = trainer(), b = trainer();
        upload(a, 600 * 1024, 201);
        // Cada uno está muy por debajo de su cuota (2 GB), pero juntos superarían 1 MB.
        upload(b, 600 * 1024, 409);
        upload(b, 400 * 1024, 201);
        upload(a, 100 * 1024, 409);
    }
}
