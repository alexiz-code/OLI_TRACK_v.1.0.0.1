package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.Date;
import java.util.List;

public class VehiculoDTO {
    private VehiculoDTO() {
    }

    @Data
    public static class CrearRequest {
        @NotBlank(message = "La placa es obligatoria")
        private String placa;
        private String descripcion;
        private String repartidorId;
        private String marca;
        private String modelo;
        private String tipo;
        private String color;
        private Integer anio;
        private Double capacidadMaximaKg;
        private String estadoOperativo;
    }

    @Data
    public static class CargaRequest {
        @NotBlank(message = "El identificador de operación es obligatorio")
        private String operacionId;
        private List<LineaStockRequest> productos;
        // Compatibilidad con clientes anteriores que cargaban un solo producto.
        private String productoId;
        private int cantidad;
        private String motivo;
    }

    @Data
    public static class LineaStockRequest {
        @NotBlank(message = "El producto es obligatorio") private String productoId;
        @Positive(message = "La cantidad debe ser mayor que cero") private int cantidad;
    }

    @Data
    public static class Response {
        private String id;
        private String placa;
        private String descripcion;
        private String repartidorId;
        private String marca;
        private String modelo;
        private String tipo;
        private String color;
        private Integer anio;
        private Double capacidadMaximaKg;
        private String estadoOperativo;
        private String fotoUrl;
        private boolean activo;
        private Date creadoEn;
        private Date actualizadoEn;
        private List<StockResponse> stock;
    }

    @Data
    public static class StockResponse {
        private String id;
        private String vehiculoId;
        private String repartidorId;
        private String productoId;
        private int cargado;
        private int reservado;
        private int vendido;
        private int entregado;
        private int devuelto;
        private int disponible;
        private Date ultimaCargaEn;
    }

    @Data
    public static class EstadoRequest { @NotBlank private String estadoOperativo; private String motivo; }
    @Data
    public static class AsignacionRequest { @NotBlank private String repartidorId; private String motivo; }
    @Data
    public static class MotivoRequest { private String motivo; }
}
