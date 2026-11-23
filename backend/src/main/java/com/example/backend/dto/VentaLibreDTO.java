package com.example.backend.dto;

import com.example.backend.model.MetodoPago;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

public class VentaLibreDTO {
    private VentaLibreDTO() {
    }

    @Data
    public static class CrearRequest {
        @NotBlank(message = "El producto es obligatorio")
        private String productoId;
        @Positive(message = "La cantidad debe ser mayor que cero")
        private int cantidad;
        private String referenciaComprador;
        private MetodoPago metodoPago;
        @Valid
        private UbicacionDTO ubicacionVenta;
    }

    @Data
    public static class Response {
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
        private UbicacionDTO ubicacionVenta;
        private String estado;
        private Date creadoEn;
    }
}
