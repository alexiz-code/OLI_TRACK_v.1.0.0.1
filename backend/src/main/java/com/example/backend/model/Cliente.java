package com.example.backend.model;

import com.google.cloud.firestore.annotation.DocumentId;
import com.google.cloud.firestore.annotation.ServerTimestamp;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Documento de la colección "clientes".
 * Referencia a su Usuario mediante usuarioId.
 *
 * La direccion/ubicacion del local del cliente vive AQUI, como campos embebidos,
 * no como una coleccion aparte: cada cliente tiene un local principal.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Cliente implements EntidadFirestore {

    @DocumentId
    private String id;

    /** Referencia al documento en "usuarios". */
    private String usuarioId;

    private String nombreNegocio;
    private String direccion;
    private String referencia;
    private String fotoNegocioStoragePath;

    /** Ubicación del local del cliente (para calcular rutas y distancias en Angular). */
    private Ubicacion ubicacion;

    /** Otras direcciones de entrega guardadas por el cliente. */
    private List<DireccionGuardada> direccionesAdicionales = new ArrayList<>();

    private boolean activo = true;

    @ServerTimestamp
    private Date creadoEn;
}
