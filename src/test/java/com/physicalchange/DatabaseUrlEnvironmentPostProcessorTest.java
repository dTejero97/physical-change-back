package com.physicalchange;

import com.physicalchange.config.DatabaseUrlEnvironmentPostProcessor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import java.util.Map;

/** Convierte la URL postgres:// que da Render al formato JDBC con usuario y clave separados. */
class DatabaseUrlEnvironmentPostProcessorTest {

    private StandardEnvironment env(Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        new DatabaseUrlEnvironmentPostProcessor().postProcessEnvironment(env, null);
        return env;
    }

    @Test void convierteUrlDeRender() {
        StandardEnvironment e = env(Map.of("DATABASE_URL", "postgresql://physical_change:s3cr%40to@dpg-abc.render.com/physical_change"));
        Assertions.assertEquals("jdbc:postgresql://dpg-abc.render.com/physical_change", e.getProperty("spring.datasource.url"));
        Assertions.assertEquals("physical_change", e.getProperty("spring.datasource.username"));
        Assertions.assertEquals("s3cr@to", e.getProperty("spring.datasource.password"));
    }

    @Test void conPuertoExplicito() {
        StandardEnvironment e = env(Map.of("DATABASE_URL", "postgres://u:p@localhost:5433/db"));
        Assertions.assertEquals("jdbc:postgresql://localhost:5433/db", e.getProperty("spring.datasource.url"));
    }

    @Test void respetaSpringDatasourceUrlSiExiste() {
        StandardEnvironment e = env(Map.of("DATABASE_URL", "postgresql://u:p@host/db",
                                           "SPRING_DATASOURCE_URL", "jdbc:postgresql://otro/db"));
        Assertions.assertNull(e.getProperty("spring.datasource.username"));
    }

    @Test void ignoraValoresQueNoSonPostgres() {
        StandardEnvironment e = env(Map.of("DATABASE_URL", "mysql://u:p@host/db"));
        Assertions.assertNull(e.getProperty("spring.datasource.url"));
    }
}
