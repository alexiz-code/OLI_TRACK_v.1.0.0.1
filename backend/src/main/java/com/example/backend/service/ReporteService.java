package com.example.backend.service;

import com.example.backend.dto.ReporteDTO;
import com.example.backend.exception.OperacionInvalidaException;
import com.example.backend.model.DetallePedido;
import com.example.backend.model.EstadoPedido;
import com.example.backend.model.HistorialEstado;
import com.example.backend.model.Incidencia;
import com.example.backend.model.Pedido;
import com.example.backend.repository.DetallePedidoRepository;
import com.example.backend.repository.IncidenciaRepository;
import com.example.backend.repository.PedidoRepository;
import com.example.backend.repository.ProductoRepository;
import com.example.backend.repository.VentaLibreRepository;
import com.example.backend.model.VentaLibre;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Reportes e indicadores (HU-044 a HU-047). Trabaja sobre datos existentes de
 * Firestore, en memoria (adecuado para el volumen de un proyecto de tesis).
 * El umbral de "demora" sale de la configuración de la empresa.
 */
@Service
public class ReporteService {

    private static final TimeZone LIMA = TimeZone.getTimeZone("America/Lima");

    private final PedidoRepository pedidoRepository;
    private final DetallePedidoRepository detallePedidoRepository;
    private final ProductoRepository productoRepository;
    private final IncidenciaRepository incidenciaRepository;
    private final ConfiguracionService configuracionService;
    private final VentaLibreRepository ventaLibreRepository;

    public ReporteService(PedidoRepository pedidoRepository, DetallePedidoRepository detallePedidoRepository,
                           ProductoRepository productoRepository, IncidenciaRepository incidenciaRepository,
                           ConfiguracionService configuracionService, VentaLibreRepository ventaLibreRepository) {
        this.pedidoRepository = pedidoRepository;
        this.detallePedidoRepository = detallePedidoRepository;
        this.productoRepository = productoRepository;
        this.incidenciaRepository = incidenciaRepository;
        this.configuracionService = configuracionService;
        this.ventaLibreRepository = ventaLibreRepository;
    }

    /** Ingresos brutos por fecha de entrega/venta, separados por repartidor. */
    public Mono<List<ReporteDTO.IngresoDiarioRepartidorDTO>> ingresosDiariosPorRepartidor() {
        return Mono.zip(pedidoRepository.findAll().collectList(), ventaLibreRepository.findAll().collectList())
                .map(datos -> {
                    SimpleDateFormat formato = new SimpleDateFormat("yyyy-MM-dd");
                    formato.setTimeZone(LIMA);
                    Map<String, AcumuladoIngreso> acumulados = new TreeMap<>();
                    for (Pedido pedido : datos.getT1()) {
                        if (pedido.getEstado() != EstadoPedido.ENTREGADO || pedido.getEntregadoEn() == null
                                || pedido.getRepartidorId() == null || pedido.getRepartidorId().isBlank()) continue;
                        String fecha = formato.format(pedido.getEntregadoEn());
                        AcumuladoIngreso fila = acumulados.computeIfAbsent(fecha + "|" + pedido.getRepartidorId(),
                                clave -> new AcumuladoIngreso(fecha, pedido.getRepartidorId()));
                        fila.pedidosEntregados++;
                        if (pedido.getTotal() != null) fila.ingresosPedidos = fila.ingresosPedidos.add(pedido.getTotal());
                    }
                    for (VentaLibre venta : datos.getT2()) {
                        if (!"CONFIRMADA".equalsIgnoreCase(venta.getEstado()) || venta.getCreadoEn() == null
                                || venta.getRepartidorId() == null || venta.getRepartidorId().isBlank()) continue;
                        String fecha = formato.format(venta.getCreadoEn());
                        AcumuladoIngreso fila = acumulados.computeIfAbsent(fecha + "|" + venta.getRepartidorId(),
                                clave -> new AcumuladoIngreso(fecha, venta.getRepartidorId()));
                        fila.ventasLibres++;
                        if (venta.getTotal() != null) fila.ingresosVentasLibres = fila.ingresosVentasLibres.add(venta.getTotal());
                    }
                    return acumulados.values().stream().map(AcumuladoIngreso::toDto)
                            .sorted(Comparator.comparing(ReporteDTO.IngresoDiarioRepartidorDTO::getFecha).reversed()
                                    .thenComparing(ReporteDTO.IngresoDiarioRepartidorDTO::getRepartidorId))
                            .toList();
                });
    }

    private static final class AcumuladoIngreso {
        private final String fecha;
        private final String repartidorId;
        private long pedidosEntregados;
        private BigDecimal ingresosPedidos = BigDecimal.ZERO;
        private long ventasLibres;
        private BigDecimal ingresosVentasLibres = BigDecimal.ZERO;
        private AcumuladoIngreso(String fecha, String repartidorId) { this.fecha = fecha; this.repartidorId = repartidorId; }
        private ReporteDTO.IngresoDiarioRepartidorDTO toDto() {
            return new ReporteDTO.IngresoDiarioRepartidorDTO(fecha, repartidorId, pedidosEntregados,
                    ingresosPedidos, ventasLibres, ingresosVentasLibres, ingresosPedidos.add(ingresosVentasLibres));
        }
    }

    private Mono<Long> umbralDemora() {
        return configuracionService.obtener().map(c -> c.getUmbralRetrasoMinutos().longValue());
    }

    public Mono<ReporteDTO.DashboardResponse> generarDashboard() {
        return Mono.zip(pedidoRepository.findAll().collectList(), umbralDemora()).map(t -> {
            List<Pedido> pedidos = t.getT1();
            long umbral = t.getT2();

            List<Pedido> entregados = pedidos.stream()
                    .filter(p -> p.getEstado() == EstadoPedido.ENTREGADO && p.getEntregadoEn() != null)
                    .toList();

            long conDemora = entregados.stream()
                    .filter(p -> minutosTotalesPedido(p).stream().anyMatch(minutos -> minutos > umbral))
                    .count();
            double tiempoPromedio = promedioRuta(entregados);
            BigDecimal ingresos = entregados.stream()
                    .map(Pedido::getTotal)
                    .filter(x -> x != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            return new ReporteDTO.DashboardResponse(
                    pedidos.size(), entregados.size(), tiempoPromedio, conDemora, ingresos, agruparPorDia(pedidos, umbral));
        });
    }

    public Mono<List<ReporteDTO.VentaPorDiaDTO>> ventasPorDia() {
        return Mono.zip(pedidoRepository.findAll().collectList(), umbralDemora())
                .map(t -> agruparPorDia(t.getT1(), t.getT2()));
    }

    private List<ReporteDTO.VentaPorDiaDTO> agruparPorDia(List<Pedido> pedidos, long umbral) {
        SimpleDateFormat formato = new SimpleDateFormat("yyyy-MM-dd");
        formato.setTimeZone(LIMA);
        Map<String, List<Pedido>> porDia = new TreeMap<>();
        for (Pedido pedido : pedidos) {
            if (pedido.getCreadoEn() == null) {
                continue;
            }
            porDia.computeIfAbsent(formato.format(pedido.getCreadoEn()), k -> new ArrayList<>()).add(pedido);
        }

        return porDia.entrySet().stream().map(entry -> {
            List<Pedido> delDia = entry.getValue();
            List<Pedido> entregadosDia = delDia.stream().filter(p -> p.getEstado() == EstadoPedido.ENTREGADO).toList();
            long conDemora = entregadosDia.stream()
                    .filter(p -> minutosTotalesPedido(p).stream().anyMatch(minutos -> minutos > umbral))
                    .count();
            BigDecimal ingresos = entregadosDia.stream()
                    .map(Pedido::getTotal)
                    .filter(x -> x != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new ReporteDTO.VentaPorDiaDTO(entry.getKey(), delDia.size(), entregadosDia.size(), conDemora, ingresos);
        }).toList();
    }

    private OptionalLong minutosTotalesPedido(Pedido pedido) {
        if (pedido.getCreadoEn() == null || pedido.getEntregadoEn() == null) {
            return OptionalLong.empty();
        }
        long minutos = Duration.between(
                pedido.getCreadoEn().toInstant(),
                pedido.getEntregadoEn().toInstant()).toMinutes();
        return minutos >= 0 ? OptionalLong.of(minutos) : OptionalLong.empty();
    }

    private double promedioRuta(List<Pedido> entregados) {
        return entregados.stream()
                .map(this::minutosDeRuta)
                .filter(OptionalLong::isPresent)
                .mapToLong(OptionalLong::getAsLong)
                .average()
                .orElse(0);
    }

    private OptionalLong minutosDeRuta(Pedido pedido) {
        Date destino = pedido.getLlegadaEn() != null
                ? pedido.getLlegadaEn()
                : pedido.getEntregadoEn();
        if (destino == null || pedido.getHistorialEstados() == null) {
            return OptionalLong.empty();
        }

        Date salida = pedido.getHistorialEstados().stream()
                .filter(historial -> historial.getEstado() == EstadoPedido.EN_CAMINO)
                .map(HistorialEstado::getFecha)
                .filter(fecha -> fecha != null && !fecha.after(destino))
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (salida == null) {
            return OptionalLong.empty();
        }

        long minutos = Duration.between(salida.toInstant(), destino.toInstant()).toMinutes();
        return minutos >= 0 ? OptionalLong.of(minutos) : OptionalLong.empty();
    }

    /** HU-046: productividad por repartidor. */
    public Mono<List<ReporteDTO.ProductividadDTO>> productividad() {
        return Mono.zip(pedidoRepository.findAll().collectList(), incidenciaRepository.findAll().collectList())
                .map(t -> {
                    Map<String, Long> incidenciasPorRepartidor = t.getT2().stream()
                            .filter(i -> i.getRepartidorId() != null)
                            .collect(Collectors.groupingBy(Incidencia::getRepartidorId, Collectors.counting()));

                    Map<String, List<Pedido>> porRepartidor = t.getT1().stream()
                            .filter(p -> p.getRepartidorId() != null && !p.getRepartidorId().isBlank())
                            .collect(Collectors.groupingBy(Pedido::getRepartidorId));

                    return porRepartidor.entrySet().stream().map(e -> {
                        List<Pedido> lista = e.getValue();
                        List<Pedido> entregados = lista.stream()
                                .filter(p -> p.getEstado() == EstadoPedido.ENTREGADO && p.getEntregadoEn() != null)
                                .toList();
                        long fallidas = lista.stream().filter(p -> p.getEstado() == EstadoPedido.ENTREGA_FALLIDA).count();
                        double promedio = promedioRuta(entregados);
                        return new ReporteDTO.ProductividadDTO(e.getKey(), lista.size(), entregados.size(), fallidas,
                                promedio, incidenciasPorRepartidor.getOrDefault(e.getKey(), 0L));
                    }).sorted(Comparator.comparingLong(ReporteDTO.ProductividadDTO::getPedidosEntregados).reversed()).toList();
                });
    }

    /** Top N productos más vendidos, por cantidad total en líneas de pedido. */
    public Mono<List<ReporteDTO.ProductoMasVendidoDTO>> productosMasVendidos(int top) {
        if (top < 1 || top > 100) {
            return Mono.error(new OperacionInvalidaException("El parámetro top debe estar entre 1 y 100"));
        }
        return pedidoRepository.findAll()
                .filter(pedido -> pedido.getEstado() == EstadoPedido.ENTREGADO)
                .map(Pedido::getId)
                .collect(Collectors.toSet())
                .flatMapMany(pedidosEntregados -> detallePedidoRepository.findAll()
                        .filter(detalle -> pedidosEntregados.contains(detalle.getPedidoId())))
                .collectList()
                .flatMap(detalles -> {
                    Map<String, List<DetallePedido>> porProducto = detalles.stream()
                            .collect(Collectors.groupingBy(DetallePedido::getProductoId));

                    return Flux.fromIterable(porProducto.entrySet())
                            .flatMap(entry -> productoRepository.findById(entry.getKey())
                                    .map(producto -> {
                                        int cantidad = entry.getValue().stream().mapToInt(DetallePedido::getCantidad).sum();
                                        BigDecimal ingreso = entry.getValue().stream()
                                                .map(DetallePedido::getSubtotal)
                                                .filter(s -> s != null)
                                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                                        return new ReporteDTO.ProductoMasVendidoDTO(
                                                producto.getId(), producto.getNombre(), cantidad, ingreso);
                                    }))
                            .collectList()
                            .map(lista -> lista.stream()
                                    .sorted(Comparator.comparingInt(ReporteDTO.ProductoMasVendidoDTO::getCantidadVendida).reversed())
                                    .limit(top)
                                    .toList());
                });
    }

    /** HU-044 / HU-047: listado de pedidos en CSV. */
    public Mono<String> pedidosCsv() {
        SimpleDateFormat formato = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        formato.setTimeZone(LIMA);
        return pedidoRepository.findAll().collectList().map(pedidos -> {
            StringBuilder csv = new StringBuilder("Id,Fecha,Estado,ClienteId,RepartidorId,MetodoPago,Total,TiempoRutaMin\n");
            pedidos.stream()
                    .sorted(Comparator.comparing(Pedido::getCreadoEn, Comparator.nullsLast(Comparator.reverseOrder())))
                    .forEach(p -> {
                        OptionalLong tiempoRuta = p.getEstado() == EstadoPedido.ENTREGADO
                                ? minutosDeRuta(p)
                                : OptionalLong.empty();
                        csv.append(p.getId()).append(',')
                            .append(p.getCreadoEn() != null ? formato.format(p.getCreadoEn()) : "").append(',')
                            .append(p.getEstado()).append(',')
                            .append(p.getClienteId() != null ? p.getClienteId() : "").append(',')
                            .append(p.getRepartidorId() != null ? p.getRepartidorId() : "").append(',')
                            .append(p.getMetodoPago() != null ? p.getMetodoPago() : "").append(',')
                            .append(p.getTotal() != null ? p.getTotal() : "").append(',')
                            .append(tiempoRuta.isPresent() ? tiempoRuta.getAsLong() : "")
                            .append('\n');
                    });
            return csv.toString();
        });
    }
}
