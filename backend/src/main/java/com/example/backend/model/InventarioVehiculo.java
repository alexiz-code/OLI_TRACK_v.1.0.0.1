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
public class InventarioVehiculo implements EntidadFirestore {
    @DocumentId
    private String id;
    private String vehiculoId;
    private String repartidorId;
    private String productoId;
    private int cargado;
    private int reservado;
    private int vendido;
    private int entregado;
    private int devuelto;
    private Date ultimaCargaEn;
    @ServerTimestamp
    private Date actualizadoEn;

    public int getDisponible() {
        return cargado - reservado - vendido - entregado + devuelto;
    }
}
