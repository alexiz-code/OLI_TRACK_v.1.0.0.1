package com.example.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CompararRepartidoresDTO(
        String estado,
        String repartidorRecomendadoId,
        List<ResultadoRepartidorDTO> repartidores
) {
    public record Solicitud(
            @NotNull
            @DecimalMin(value = "-90.0")
            @DecimalMax(value = "90.0")
            Double latitudDestino,
            @NotNull
            @DecimalMin(value = "-180.0")
            @DecimalMax(value = "180.0")
            Double longitudDestino
    ) {
    }

    public record ResultadoRepartidorDTO(
            String repartidorId,
            String estado,
            Double distanciaKm,
            Double duracionMinutos
    ) {
    }
}
