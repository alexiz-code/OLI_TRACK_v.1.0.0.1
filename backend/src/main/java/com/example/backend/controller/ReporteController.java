package com.example.backend.controller;

import com.example.backend.dto.ReporteDTO;
import com.example.backend.service.ReporteService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/** Todo el módulo de reportes es de uso administrativo. */
@RestController
@RequestMapping("/api/reportes")
public class ReporteController {

    private final ReporteService reporteService;

    public ReporteController(ReporteService reporteService) {
        this.reporteService = reporteService;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<ReporteDTO.DashboardResponse> dashboard() {
        return reporteService.generarDashboard();
    }

    @GetMapping("/ventas-por-dia")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<List<ReporteDTO.VentaPorDiaDTO>> ventasPorDia() {
        return reporteService.ventasPorDia();
    }

    @GetMapping("/productos-mas-vendidos")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<List<ReporteDTO.ProductoMasVendidoDTO>> productosMasVendidos(
            @RequestParam(defaultValue = "10") int top) {
        return reporteService.productosMasVendidos(top);
    }

    /** HU-046: productividad por repartidor. */
    @GetMapping("/productividad")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<List<ReporteDTO.ProductividadDTO>> productividad() {
        return reporteService.productividad();
    }

    @GetMapping("/ingresos-diarios-repartidor")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<List<ReporteDTO.IngresoDiarioRepartidorDTO>> ingresosDiariosPorRepartidor() {
        return reporteService.ingresosDiariosPorRepartidor();
    }

    /** HU-044 / HU-047: exportar el listado de pedidos como CSV. */
    @GetMapping("/pedidos/exportar")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<org.springframework.http.ResponseEntity<String>> exportarPedidosCsv() {
        return reporteService.pedidosCsv().map(csv -> org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv"))
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"pedidos.csv\"")
                .body(csv));
    }

    /** Exporta el resumen por día como CSV (abre directo en Excel). */
    @GetMapping("/ventas-por-dia/exportar")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Mono<ResponseEntity<String>> exportarVentasCsv() {
        return reporteService.ventasPorDia().map(filas -> {
            StringBuilder csv = new StringBuilder("Fecha,Pedidos,Entregados,ConDemora,Ingresos\n");
            for (ReporteDTO.VentaPorDiaDTO fila : filas) {
                csv.append(fila.getFecha()).append(',')
                        .append(fila.getPedidos()).append(',')
                        .append(fila.getEntregados()).append(',')
                        .append(fila.getConDemora()).append(',')
                        .append(fila.getIngresos()).append('\n');
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"reporte-ventas.csv\"")
                    .body(csv.toString());
        });
    }
}
