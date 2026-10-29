package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.VersionDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.*;

/**
 * Versión del servidor, sin sesión (ruta permitida en SecurityConfig: /api/public/**). La muestra Configuración
 * para comprobar que el front y el back desplegados son la misma versión. Sale de build-info (pom.xml);
 * al arrancar sin empaquetar, "dev".
 */
@RestController @RequestMapping("/api/public/version") @Tag(name = "Público: versión")
public class PublicVersionController {
    private final VersionDto version;

    public PublicVersionController(ObjectProvider<BuildProperties> build) {
        BuildProperties b = build.getIfAvailable();
        version = b == null ? new VersionDto("dev", null) : new VersionDto(b.getVersion(), b.getTime());
    }

    @GetMapping public VersionDto get() { return version; }
}
