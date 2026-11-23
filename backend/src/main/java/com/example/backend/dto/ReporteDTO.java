package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

public class ReporteDTO {

    private ReporteDTO() {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DashboardResponse {
        private long pedidosTotales;
        private long pedidosEntregados;
        private double tiempoPromedioMinutos;
        private long pedidosConDemora;
        private BigDecimal ingresosTotales;
        private List<VentaPorDiaDTO> resumenPorDia;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VentaPorDiaDTO {
        private String fecha;
        private long pedidos;
        private long entregados;
        private long conDemora;
        private BigDecimal ingresos;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductoMasVendidoDTO {
        private String productoId;
        private String nombre;
        private int cantidadVendida;
        private BigDecimal ingresoGenerado;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductividadDTO {
        private String repartidorId;
        private long pedidosAsignados;
        private long pedidosEntregados;
        private long entregasFallidas;
        private double tiempoPromedioMinutos;
        private long incidencias;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IngresoDiarioRepartidorDTO {
        private String fecha;
        private String repartidorId;
        private long pedidosEntregados;
        private BigDecimal ingresosPedidos;
        private long ventasLibres;
        private BigDecimal ingresosVentasLibres;
        private BigDecimal ingresosTotales;
    }
}
