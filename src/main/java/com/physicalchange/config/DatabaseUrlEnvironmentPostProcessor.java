package com.physicalchange.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Render entrega la base de datos como postgres://usuario:clave@host:puerto/base.
 * JDBC necesita jdbc:postgresql://... y usuario/clave separados. Esta clase convierte
 * DATABASE_URL al formato JDBC cuando SPRING_DATASOURCE_URL no está definida.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        if (env.containsProperty("SPRING_DATASOURCE_URL")) return;
        String raw = env.getProperty("DATABASE_URL");
        // Render define RENDER=true. Sin DATABASE_URL en Render, el arranque caería en localhost: mejor avisar claro.
        if ((raw == null || raw.isBlank()) && env.containsProperty("RENDER")) {
            throw new IllegalStateException("Falta DATABASE_URL en Render: en el servicio physical-change-back → Environment, "
                    + "pega la URL de tu base PostgreSQL (postgresql://usuario:clave@host/base).");
        }
        if (raw == null || !(raw.startsWith("postgres://") || raw.startsWith("postgresql://"))) return;

        URI uri = URI.create(raw);
        Map<String, Object> props = new HashMap<>();
        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) jdbc.append(':').append(uri.getPort());
        jdbc.append(uri.getPath());
        if (uri.getQuery() != null) jdbc.append('?').append(uri.getQuery());
        props.put("spring.datasource.url", jdbc.toString());

        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int sep = userInfo.indexOf(':');
            String user = sep < 0 ? userInfo : userInfo.substring(0, sep);
            props.put("spring.datasource.username", decode(user));
            if (sep >= 0) props.put("spring.datasource.password", decode(userInfo.substring(sep + 1)));
        }
        env.getPropertySources().addFirst(new MapPropertySource("render-database-url", props));
    }

    private static String decode(String s) {
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
