package com.example.backend.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * Coordenadas GPS embebidas dentro de un documento (repartidor, cliente o pedido).
 * Usa java.util.Date (y no Instant) porque el SDK de Firestore no puede
 * deserializar java.time.Instant.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Ubicacion {

    private Double latitud;
    private Double longitud;
    private Date actualizadoEn;

    public Ubicacion(Double latitud, Double longitud) {
        this.latitud = latitud;
        this.longitud = longitud;
        this.actualizadoEn = new Date();
    }
}
