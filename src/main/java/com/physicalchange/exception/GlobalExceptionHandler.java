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
    @ExceptionHandler(Exception.class) ResponseEntity<ErrorResponse> other(Exception e) { return build(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno"); }
}
