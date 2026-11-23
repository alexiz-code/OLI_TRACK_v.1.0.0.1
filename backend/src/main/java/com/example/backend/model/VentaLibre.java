package com.example.backend.model;

import com.google.cloud.firestore.annotation.DocumentId;
import com.google.cloud.firestore.annotation.ServerTimestamp;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VentaLibre implements EntidadFirestore {
    @DocumentId
    private String id;
    private String operacionId;
    private String referenciaComprador;
    private String vehiculoId;
    private String repartidorId;
    private String productoId;
    private int cantidad;
    private BigDecimal precioUnitario;
    private BigDecimal total;
    private MetodoPago metodoPago;
    private Ubicacion ubicacionVenta;
    private String estado = "CONFIRMADA";
    private String usuarioResponsableId;
    @ServerTimestamp
    private Date creadoEn;
}
