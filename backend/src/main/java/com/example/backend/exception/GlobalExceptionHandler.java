package com.example.backend.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Punto único de manejo de errores de toda la API. Todo excepción termina
 * aquí y se traduce a un ErrorResponse consistente, con el HttpStatus correcto.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Excepciones propias de OLITRACK: cada una ya sabe su HttpStatus. */
    @ExceptionHandler(ApiException.class)
    public Mono<ResponseEntity<ErrorResponse>> manejarApiException(ApiException ex, ServerWebExchange exchange) {
        HttpStatus status = ex.getStatus();
        log.warn("ApiException [{}]: {}", status, ex.getMessage());
        return responder(status, ex.getMessage(), exchange, null);
    }

    /** Errores de @Valid sobre el body de la petición (DTOs con Jakarta Validation). */
    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ErrorResponse>> manejarValidacion(WebExchangeBindException ex, ServerWebExchange exchange) {
        List<ErrorResponse.CampoError> errores = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.CampoError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return responder(HttpStatus.BAD_REQUEST, "Uno o más campos no son válidos", exchange, errores);
    }

    /** JSON malformado, parámetros faltantes o con tipo incorrecto. */
    @ExceptionHandler(ServerWebInputException.class)
    public Mono<ResponseEntity<ErrorResponse>> manejarInputInvalido(ServerWebInputException ex, ServerWebExchange exchange) {
        return responder(HttpStatus.BAD_REQUEST, "La petición no tiene un formato válido", exchange, null);
    }

    /** Respaldo por si algo evita el ServerAccessDeniedHandler de seguridad. */
    @ExceptionHandler(AccessDeniedException.class)
    public Mono<ResponseEntity<ErrorResponse>> manejarAccesoDenegado(AccessDeniedException ex, ServerWebExchange exchange) {
        return responder(HttpStatus.FORBIDDEN, "No tiene permisos para realizar esta acción", exchange, null);
    }

    /** Conserva los estados 4xx/5xx declarados por los servicios reactivos. */
    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<ErrorResponse>> manejarEstadoHttp(ResponseStatusException ex, ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        String mensaje = ex.getReason();
        if (mensaje == null || mensaje.isBlank()) {
            mensaje = status == null ? "La operación no pudo completarse" : status.getReasonPhrase();
        }
        ErrorResponse body = new ErrorResponse(
                ex.getStatusCode().value(),
                status == null ? "Error" : status.getReasonPhrase(),
                mensaje,
                exchange.getRequest().getPath().value()
        );
        return Mono.just(ResponseEntity.status(ex.getStatusCode()).body(body));
    }

    /** Cualquier error no anticipado: nunca se expone el detalle interno al cliente. */
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ErrorResponse>> manejarGenerico(Exception ex, ServerWebExchange exchange) {
        log.error("Error no controlado en {}", exchange.getRequest().getPath(), ex);
        return responder(HttpStatus.INTERNAL_SERVER_ERROR, "Ocurrió un error inesperado", exchange, null);
    }

    private Mono<ResponseEntity<ErrorResponse>> responder(HttpStatus status, String mensaje,
                                                            ServerWebExchange exchange,
                                                            List<ErrorResponse.CampoError> errores) {
        ErrorResponse body = new ErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                mensaje,
                exchange.getRequest().getPath().value(),
                errores
        );
        return Mono.just(ResponseEntity.status(status).body(body));
    }
}
