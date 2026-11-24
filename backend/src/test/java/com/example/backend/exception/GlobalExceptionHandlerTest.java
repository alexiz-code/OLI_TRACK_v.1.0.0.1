package com.example.backend.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    @Test
    void conservaConflictosDeclaradosPorServiciosEnLugarDeConvertirlosEn500() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.put("/api/vehiculos/v1/repartidor").build()
        );

        var response = handler.manejarEstadoHttp(
                new ResponseStatusException(HttpStatus.CONFLICT, "El repartidor ya tiene vehículo."),
                exchange
        ).block();

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(409, response.getBody().getStatus());
        assertEquals("El repartidor ya tiene vehículo.", response.getBody().getMensaje());
    }
}
