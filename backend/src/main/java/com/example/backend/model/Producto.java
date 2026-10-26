package com.example.backend.model;

import com.google.cloud.firestore.annotation.DocumentId;
import com.google.cloud.firestore.annotation.ServerTimestamp;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/**
 * Documento de la colección "productos".
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Producto implements EntidadFirestore {

    @DocumentId
    private String id;

    private String nombre;
    private String descripcion;
    private String categoria;
    private BigDecimal precioUnitario;
    private String unidadMedida;
    private String imagenUrl;
    /** Ruta privada del objeto en Firebase Storage; imagenUrl es la ruta pública de la API. */
    private String imagenStoragePath;
    private boolean activo = true;

    @ServerTimestamp
    private Date creadoEn;
}
