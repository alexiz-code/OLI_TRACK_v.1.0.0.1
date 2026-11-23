package com.example.backend.service;

import com.example.backend.dto.ReporteDTO;
import com.example.backend.model.ConfiguracionEmpresa;
import com.example.backend.model.EstadoPedido;
import com.example.backend.model.HistorialEstado;
import com.example.backend.model.Pedido;
import com.example.backend.model.VentaLibre;
import com.example.backend.repository.DetallePedidoRepository;
import com.example.backend.repository.IncidenciaRepository;
import com.example.backend.repository.PedidoRepository;
import com.example.backend.repository.ProductoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

class ReporteServiceTest {

    private PedidoRepository pedidoRepository;
    private IncidenciaRepository incidenciaRepository;
    private ConfiguracionService configuracionService;
    private ReporteService reporteService;
    private com.example.backend.repository.VentaLibreRepository ventaLibreRepository;

    @BeforeEach
    void setUp() {
        pedidoRepository = org.mockito.Mockito.mock(PedidoRepository.class);
        incidenciaRepository = org.mockito.Mockito.mock(IncidenciaRepository.class);
        configuracionService = org.mockito.Mockito.mock(ConfiguracionService.class);
        ventaLibreRepository = org.mockito.Mockito.mock(com.example.backend.repository.VentaLibreRepository.class);
        reporteService = new ReporteService(
                pedidoRepository,
                org.mockito.Mockito.mock(DetallePedidoRepository.class),
                org.mockito.Mockito.mock(ProductoRepository.class),
                incidenciaRepository,
                configuracionService,
                ventaLibreRepository
        );
    }

    @Test
    void agrupaIngresosBrutosDiariosPorRepartidorSinContarOtrosMovimientos() {
        Pedido entregado = pedido("pedido-1", "09:00:00Z", "10:22:00Z", "10:20:00Z", "10:00:00Z");
        entregado.setTotal(new BigDecimal("45.50"));
        VentaLibre venta = new VentaLibre();
        venta.setId("venta-1"); venta.setRepartidorId("r1"); venta.setEstado("CONFIRMADA");
        venta.setTotal(new BigDecimal("12.00")); venta.setCreadoEn(fecha("11:00:00Z"));
        when(pedidoRepository.findAll()).thenReturn(Flux.just(entregado));
        when(ventaLibreRepository.findAll()).thenReturn(Flux.just(venta));

        StepVerifier.create(reporteService.ingresosDiariosPorRepartidor())
                .assertNext(filas -> {
                    assertEquals(1, filas.size());
                    assertEquals("2026-10-19", filas.get(0).getFecha());
                    assertEquals("r1", filas.get(0).getRepartidorId());
                    assertEquals(1, filas.get(0).getPedidosEntregados());
                    assertEquals(new BigDecimal("45.50"), filas.get(0).getIngresosPedidos());
                    assertEquals(1, filas.get(0).getVentasLibres());
                    assertEquals(new BigDecimal("12.00"), filas.get(0).getIngresosVentasLibres());
                    assertEquals(new BigDecimal("57.50"), filas.get(0).getIngresosTotales());
                }).verifyComplete();
    }

    @Test
    void calculaTiempoPromedioDesdeSalidaHastaLlegadaYExcluyePedidosSinSalida() {
        List<Pedido> pedidos = List.of(
                pedido("p1", "09:00:00Z", "10:22:00Z", "10:20:00Z", "10:00:00Z"),
                pedido("p2", "10:50:00Z", "11:40:00Z", null, "11:00:00Z"),
                pedido("p3", "12:00:00Z", "12:30:00Z", null, null)
        );
        when(pedidoRepository.findAll()).thenReturn(Flux.fromIterable(pedidos));
        when(configuracionService.obtener()).thenReturn(Mono.just(configuracion(30)));

        StepVerifier.create(reporteService.generarDashboard())
                .assertNext(reporte -> {
                    assertEquals(3, reporte.getPedidosEntregados());
                    assertEquals(30.0, reporte.getTiempoPromedioMinutos());
                    assertEquals(2, reporte.getPedidosConDemora());
                })
                .verifyComplete();
    }

    @Test
    void productividadUsaElMismoTiempoDeRutaYExcluyePedidosSinSalida() {
        List<Pedido> pedidos = List.of(
                pedido("p1", "09:00:00Z", "10:22:00Z", "10:20:00Z", "10:00:00Z"),
                pedido("p2", "10:50:00Z", "11:40:00Z", null, "11:00:00Z"),
                pedido("p3", "12:00:00Z", "12:30:00Z", null, null)
        );
        when(pedidoRepository.findAll()).thenReturn(Flux.fromIterable(pedidos));
        when(incidenciaRepository.findAll()).thenReturn(Flux.empty());

        StepVerifier.create(reporteService.productividad())
                .assertNext(productividad -> {
                    ReporteDTO.ProductividadDTO repartidor = productividad.get(0);
                    assertEquals("r1", repartidor.getRepartidorId());
                    assertEquals(3, repartidor.getPedidosEntregados());
                    assertEquals(30.0, repartidor.getTiempoPromedioMinutos());
                })
                .verifyComplete();
    }

    private Pedido pedido(
            String id,
            String creadoEn,
            String entregadoEn,
            String llegadaEn,
            String salidaEn
    ) {
        Pedido pedido = new Pedido();
        pedido.setId(id);
        pedido.setRepartidorId("r1");
        pedido.setEstado(EstadoPedido.ENTREGADO);
        pedido.setCreadoEn(fecha(creadoEn));
        pedido.setEntregadoEn(fecha(entregadoEn));
        pedido.setLlegadaEn(llegadaEn == null ? null : fecha(llegadaEn));
        pedido.setHistorialEstados(salidaEn == null
                ? List.of()
                : List.of(new HistorialEstado(EstadoPedido.EN_CAMINO, fecha(salidaEn), "r1", null)));
        return pedido;
    }

    private ConfiguracionEmpresa configuracion(int umbral) {
        ConfiguracionEmpresa configuracion = new ConfiguracionEmpresa();
        configuracion.setUmbralRetrasoMinutos(umbral);
        return configuracion;
    }

    private Date fecha(String hora) {
        return Date.from(Instant.parse("2026-10-19T" + hora));
    }
}
