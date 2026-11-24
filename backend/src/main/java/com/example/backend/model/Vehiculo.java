package com.example.backend.model;

import com.google.cloud.firestore.annotation.DocumentId;
import com.google.cloud.firestore.annotation.ServerTimestamp;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Vehiculo implements EntidadFirestore {
    @DocumentId
    private String id;
    private String placa;
    private String descripcion;
    private String marca;
    private String modelo;
    private String tipo;
    private String color;
    private Integer anio;
    private Double capacidadMaximaKg;
    private String estadoOperativo = "DISPONIBLE";
    private String fotoStoragePath;
    private String fotoUrl;
    private String repartidorId;
    private boolean activo = true;
    @ServerTimestamp
    private Date creadoEn;
    private Date actualizadoEn;
}
