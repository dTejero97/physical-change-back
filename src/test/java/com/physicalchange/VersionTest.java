package com.physicalchange;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** La versión del servidor se consulta sin sesión y sale del pom (build-info). */
@SpringBootTest
@AutoConfigureMockMvc
class VersionTest {
    @Autowired MockMvc mvc;

    @Test void versionPublicaDelServidor() throws Exception {
        mvc.perform(get("/api/public/version"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.version").value(org.hamcrest.Matchers.matchesPattern("\\d+\\.\\d+\\.\\d+")));
    }
}
