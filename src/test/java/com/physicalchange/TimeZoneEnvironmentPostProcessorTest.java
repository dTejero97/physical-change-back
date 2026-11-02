package com.physicalchange;

import com.physicalchange.config.TimeZoneEnvironmentPostProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import java.util.Map;
import java.util.TimeZone;

/** T213: la JVM usa la zona de la plataforma (Render corre en UTC). */
class TimeZoneEnvironmentPostProcessorTest {
    private final TimeZone original = TimeZone.getDefault();

    @AfterEach void restore() { TimeZone.setDefault(original); }

    private void run(Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        new TimeZoneEnvironmentPostProcessor().postProcessEnvironment(env, null);
    }

    @Test void porDefectoEuropaMadrid() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        run(Map.of());
        Assertions.assertEquals("Europe/Madrid", TimeZone.getDefault().getID());
    }

    @Test void respetaAppTimezone() {
        run(Map.of("app.timezone", "Atlantic/Canary"));
        Assertions.assertEquals("Atlantic/Canary", TimeZone.getDefault().getID());
    }

    @Test void zonaNoValidaFalla() {
        Assertions.assertThrows(java.time.DateTimeException.class, () -> run(Map.of("app.timezone", "Marte/Olimpo")));
    }
}
