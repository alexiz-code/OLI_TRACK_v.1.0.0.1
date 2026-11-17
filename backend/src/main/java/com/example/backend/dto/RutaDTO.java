package com.example.backend.dto;

import java.util.List;

public record RutaDTO(
        String estado,
        Double distanciaKm,
        Double duracionMinutos,
        List<CoordenadaDTO> geometria,
        String repartidorId
) {
    public record CoordenadaDTO(double latitud, double longitud) {
    }
}
