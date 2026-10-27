package com.physicalchange.exception;

import com.physicalchange.dto.Dtos.ErrorResponse;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private ResponseEntity<ErrorResponse> build(HttpStatus s, String m) {
        return ResponseEntity.status(s).body(new ErrorResponse(Instant.now(), s.value(), s.getReasonPhrase(), m));
    }
    @ExceptionHandler(NotFoundException.class) ResponseEntity<ErrorResponse> nf(NotFoundException e) { return build(HttpStatus.NOT_FOUND, e.getMessage()); }
    @ExceptionHandler({ForbiddenException.class, AccessDeniedException.class}) ResponseEntity<ErrorResponse> fb(Exception e) { return build(HttpStatus.FORBIDDEN, e.getMessage()); }
    @ExceptionHandler(ConflictException.class) ResponseEntity<ErrorResponse> cf(ConflictException e) { return build(HttpStatus.CONFLICT, e.getMessage()); }
    @ExceptionHandler(BadRequestException.class) ResponseEntity<ErrorResponse> br(BadRequestException e) { return build(HttpStatus.BAD_REQUEST, e.getMessage()); }
    @ExceptionHandler(GoneException.class) ResponseEntity<ErrorResponse> gn(GoneException e) { return build(HttpStatus.GONE, e.getMessage()); }
    @ExceptionHandler(AiException.class) ResponseEntity<ErrorResponse> ai(AiException e) {
        ResponseEntity<ErrorResponse> r = build(HttpStatus.valueOf(e.status()), e.getMessage());
        return e.retryAfterSeconds() == null ? r
                : ResponseEntity.status(r.getStatusCode()).header("Retry-After", String.valueOf(e.retryAfterSeconds())).body(r.getBody());
    }
    /** Validación de parámetros de ruta (p. ej. formato del token de invitación). */
    @ExceptionHandler({jakarta.validation.ConstraintViolationException.class,
                       org.springframework.web.method.annotation.HandlerMethodValidationException.class})
    ResponseEntity<ErrorResponse> param(Exception e) { return build(HttpStatus.BAD_REQUEST, "Parámetro no válido"); }
    @ExceptionHandler(BadCredentialsException.class) ResponseEntity<ErrorResponse> bc(BadCredentialsException e) { return build(HttpStatus.UNAUTHORIZED, "Credenciales inválidas"); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> val(MethodArgumentNotValidException e) {
        return build(HttpStatus.BAD_REQUEST, e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).collect(Collectors.joining("; ")));
    }
    /** Archivos de más de 10 MB (ADR-001): lo corta el servidor o el propio servicio. */
    @ExceptionHandler({PayloadTooLargeException.class, org.springframework.web.multipart.MaxUploadSizeExceededException.class})
    ResponseEntity<ErrorResponse> big(Exception e) { return build(HttpStatus.PAYLOAD_TOO_LARGE, "El archivo supera los 10 MB"); }
    /** Errores del cliente que antes acababan en 500 (propuesta de QA). */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> method(Exception e) { return build(HttpStatus.METHOD_NOT_ALLOWED, "Método no permitido"); }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> media(Exception e) { return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de contenido no soportado"); }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
                       org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
                       org.springframework.web.bind.MissingServletRequestParameterException.class,
                       org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<ErrorResponse> unreadable(Exception e) { return build(HttpStatus.BAD_REQUEST, "Petición no válida"); }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> noRoute(Exception e) { return build(HttpStatus.NOT_FOUND, "Recurso no encontrado"); }
    @ExceptionHandler(Exception.class) ResponseEntity<ErrorResponse> other(Exception e) { return build(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno"); }
}
