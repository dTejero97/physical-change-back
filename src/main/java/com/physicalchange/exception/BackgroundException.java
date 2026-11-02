package com.physicalchange.exception;

import org.springframework.http.HttpStatus;
import java.util.List;

/**
 * Error de los fondos de la página de carga (docs/api-fondos.md) con un código estable para que el front muestre el mensaje
 * adecuado. reasons: todos los motivos de rechazo de una subida (el primero es code). width/height/sizeBytes: si se conocen.
 */
public class BackgroundException extends RuntimeException {
    public record Reason(String code, String message) {}

    private final HttpStatus status;
    private final List<Reason> reasons;
    private final Integer width, height;
    private final Long sizeBytes;

    public BackgroundException(HttpStatus status, String code, String message) { this(status, List.of(new Reason(code, message)), null, null, null); }

    public BackgroundException(HttpStatus status, List<Reason> reasons, Integer width, Integer height, Long sizeBytes) {
        super(reasons.get(0).message());
        this.status = status; this.reasons = List.copyOf(reasons);
        this.width = width; this.height = height; this.sizeBytes = sizeBytes;
    }

    public HttpStatus status() { return status; }
    public String code() { return reasons.get(0).code(); }
    public List<Reason> reasons() { return reasons; }
    public Integer width() { return width; }
    public Integer height() { return height; }
    public Long sizeBytes() { return sizeBytes; }
}
