package com.example.backend.model;

import com.google.cloud.firestore.annotation.DocumentId;
import com.google.cloud.firestore.annotation.ServerTimestamp;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * Documento de la colección "repartidores".
 * Referencia a su Usuario mediante usuarioId.
 *
 * La ultima ubicacion GPS vive AQUI, como campo embebido (no como coleccion aparte),
 * y se actualiza mediante PUT /api/repartidores/{id}/ubicacion.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Repartidor implements EntidadFirestore {

    @DocumentId
    private String id;

    /** Referencia al documento en "usuarios". */
    private String usuarioId;

    private String placaVehiculo;
    private String vehiculoId;
    private String tipoVehiculo = "MOTOCICLETA";
    private EstadoRepartidor estado = EstadoRepartidor.DESCONECTADO;

    /** Última ubicación GPS reportada por el repartidor. */
    private Ubicacion ubicacionActual;

    private boolean activo = true;

    @ServerTimestamp
    private Date creadoEn;
}
