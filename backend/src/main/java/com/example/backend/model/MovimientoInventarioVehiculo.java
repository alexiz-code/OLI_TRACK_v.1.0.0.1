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
public class MovimientoInventarioVehiculo implements EntidadFirestore {
    @DocumentId
    private String id;
    private String operacionId;
    private String vehiculoId;
    private String vehiculoOrigenId;
    private String vehiculoDestinoId;
    private String repartidorId;
    private String productoId;
    private TipoMovimientoVehiculo tipoMovimiento;
    private int cantidad;
    private String motivo;
    private String usuarioResponsableId;
    @ServerTimestamp
    private Date creadoEn;
}
