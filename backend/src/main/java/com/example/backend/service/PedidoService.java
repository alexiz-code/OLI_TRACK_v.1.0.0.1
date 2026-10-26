package com.example.backend.service;

import com.example.backend.dto.DetallePedidoDTO;
import com.example.backend.dto.IncidenciaDTO;
import com.example.backend.dto.PedidoDTO;
import com.example.backend.dto.UbicacionDTO;
import com.example.backend.exception.ApiException;
import com.example.backend.exception.OperacionInvalidaException;
import com.example.backend.exception.RecursoNoEncontradoException;
import com.example.backend.config.FirebaseProperties;
import com.example.backend.mapper.EntityMapper;
import com.example.backend.model.Calificacion;
import com.example.backend.model.ConfiguracionEmpresa;
import com.example.backend.model.DetallePedido;
import com.example.backend.model.EstadoPedido;
import com.example.backend.model.EstadoRepartidor;
import com.example.backend.model.HistorialEstado;
import com.example.backend.model.MetodoPago;
import com.example.backend.model.Pedido;
import com.example.backend.model.Producto;
import com.example.backend.model.Repartidor;
import com.example.backend.model.Rol;
import com.example.backend.model.TipoIncidencia;
import com.example.backend.model.Ubicacion;
import com.example.backend.repository.ClienteRepository;
import com.example.backend.repository.DetallePedidoRepository;
import com.example.backend.repository.PedidoRepository;
import com.example.backend.repository.ProductoRepository;
import com.example.backend.repository.RepartidorRepository;
import com.example.backend.security.SecurityContextUtils;
import com.example.backend.security.UsuarioAutenticado;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

@Service
public class PedidoService {
    private static final Logger log = LoggerFactory.getLogger(PedidoService.class);
    private static final ZoneId ZONA_LIMA = ZoneId.of("America/Lima");
    private static final int EVIDENCIA_MAX_BYTES = 2 * 1024 * 1024;
    private static final List<String> TIPOS_EVIDENCIA_PERMITIDOS =
            List.of("image/jpeg", "image/png", "image/webp", "application/pdf");

    private final PedidoRepository pedidoRepository;
    private final DetallePedidoRepository detallePedidoRepository;
    private final ProductoRepository productoRepository;
    private final RepartidorRepository repartidorRepository;
    private final RepartidorService repartidorService;
    private final ClienteRepository clienteRepository;
    private final InventarioService inventarioService;
    private final NotificacionService notificacionService;
    private final ConfiguracionService configuracionService;
    private final IncidenciaService incidenciaService;

    private final Storage storage;
    private final String storageBucketName;

    private final EntityMapper mapper;

    public PedidoService(PedidoRepository pedidoRepository,
                         DetallePedidoRepository detallePedidoRepository,
                         ProductoRepository productoRepository,
                         RepartidorRepository repartidorRepository,
                         RepartidorService repartidorService,
                         ClienteRepository clienteRepository,
                         InventarioService inventarioService,
                         NotificacionService notificacionService,
                         ConfiguracionService configuracionService,
                         IncidenciaService incidenciaService,
                         Storage storage,
                         FirebaseProperties firebaseProperties,
                         EntityMapper mapper) {
        this.pedidoRepository = pedidoRepository;
        this.detallePedidoRepository = detallePedidoRepository;
        this.productoRepository = productoRepository;
        this.repartidorRepository = repartidorRepository;
        this.repartidorService = repartidorService;
        this.clienteRepository = clienteRepository;
        this.inventarioService = inventarioService;
        this.notificacionService = notificacionService;
        this.configuracionService = configuracionService;
        this.incidenciaService = incidenciaService;
        this.storage = storage;
        this.storageBucketName = firebaseProperties.getStorageBucket();
        this.mapper = mapper;
    }

    // =====================================================================
    // Utilidades
    // =====================================================================

    private Mono<String> uidActual() {
        return SecurityContextUtils.usuarioActual()
                .map(UsuarioAutenticado::getUid)
                .defaultIfEmpty("sistema");
    }

    private void agregarHistorial(Pedido pedido, EstadoPedido estado, String uid, String comentario) {
        if (pedido.getHistorialEstados() == null) {
            pedido.setHistorialEstados(new ArrayList<>());
        }
        pedido.getHistorialEstados().add(new HistorialEstado(estado, new Date(), uid, comentario));
    }

    private String corto(String id) {
        return id != null && id.length() > 6
                ? id.substring(id.length() - 6)
                : String.valueOf(id);
    }

    private Mono<Pedido> buscar(String pedidoId) {
        return pedidoRepository.findById(pedidoId)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Pedido", pedidoId)));
    }

    private Mono<Repartidor> repartidorDe(String uid) {
        return repartidorRepository.findByUsuarioId(uid)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Perfil de repartidor para el usuario", uid)));
    }

    public Mono<PedidoDTO.Response> crear(String clienteId, PedidoDTO.CrearRequest request) {
        return configuracionService.obtener()
                .flatMap(cfg -> validarPedidoPermitido(cfg, request)
                        .then(resolverUbicacionEntrega(clienteId, request.getUbicacionEntrega()))
                        .flatMap(ubicacionEntrega -> Flux.fromIterable(request.getProductos())
                                .flatMap(linea -> resolverLinea(linea)
                                        .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Producto", linea.getProductoId()))))
                                .collectList()
                                .flatMap(lineas -> construirYGuardar(
                                        clienteId, request, cfg, lineas, ubicacionEntrega.orElse(null))
                                        .flatMap(pedido -> !coordenadasValidas(
                                                mapper.toModel(pedido.getUbicacionEntrega()))
                                                ? Mono.just(pedido)
                                                : asignarRepartidorAutomatico(pedido.getId())
                                                        .onErrorResume(OperacionInvalidaException.class, error ->
                                                                error.getMessage() != null
                                                                        && error.getMessage().startsWith("No hay repartidores disponibles")
                                                                        ? Mono.just(pedido)
                                                                        : Mono.error(error))))));
    }

    private Mono<Optional<UbicacionDTO>> resolverUbicacionEntrega(
            String clienteId,
            UbicacionDTO ubicacionIndicada
    ) {
        if (coordenadasValidas(mapper.toModel(ubicacionIndicada))) {
            return Mono.just(Optional.of(ubicacionIndicada));
        }
        return clienteRepository.findById(clienteId)
                .flatMap(cliente -> Mono.justOrEmpty(cliente.getUbicacion()))
                .filter(this::coordenadasValidas)
                .map(ubicacion -> Optional.of(mapper.toDTO(ubicacion)))
                .defaultIfEmpty(Optional.empty());
    }

    private Mono<Void> validarPedidoPermitido(ConfiguracionEmpresa cfg, PedidoDTO.CrearRequest request) {
        List<MetodoPago> habilitados = cfg.getMetodosPagoHabilitados();
        if (habilitados != null && !habilitados.isEmpty() && !habilitados.contains(request.getMetodoPago())) {
            return Mono.error(new OperacionInvalidaException("El método de pago " + request.getMetodoPago() + " no está habilitado"));
        }
        if (cfg.isValidarHorarioAtencion() && !dentroDeHorario(cfg)) {
            return Mono.error(new OperacionInvalidaException(
                    "Fuera del horario de atención (" + cfg.getHorarioApertura() + " - " + cfg.getHorarioCierre() + ")"
            ));
        }
        return Mono.empty();
    }

    private boolean dentroDeHorario(ConfiguracionEmpresa cfg) {
        try {
            java.time.ZonedDateTime ahoraLocal = java.time.ZonedDateTime.now(ZONA_LIMA);
            if (!diaAtendido(cfg.getDiasAtencion(), ahoraLocal.getDayOfWeek())) {
                return false;
            }
            LocalTime ahora = ahoraLocal.toLocalTime();
            LocalTime apertura = LocalTime.parse(cfg.getHorarioApertura());
            LocalTime cierre = LocalTime.parse(cfg.getHorarioCierre());
            if (apertura.isBefore(cierre)) {
                return !ahora.isBefore(apertura) && !ahora.isAfter(cierre);
            }
            return !ahora.isBefore(apertura) || !ahora.isAfter(cierre);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean diaAtendido(String diasAtencion, DayOfWeek hoy) {
        if (!StringUtils.hasText(diasAtencion)) {
            return false;
        }
        String normalizado = Normalizer.normalize(diasAtencion, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        Pattern patron = Pattern.compile("lunes|martes|miercoles|jueves|viernes|sabado|domingo");
        Matcher matcher = patron.matcher(normalizado);
        List<DayOfWeek> dias = new ArrayList<>();
        while (matcher.find()) {
            DayOfWeek dia = switch (matcher.group()) {
                case "lunes" -> DayOfWeek.MONDAY;
                case "martes" -> DayOfWeek.TUESDAY;
                case "miercoles" -> DayOfWeek.WEDNESDAY;
                case "jueves" -> DayOfWeek.THURSDAY;
                case "viernes" -> DayOfWeek.FRIDAY;
                case "sabado" -> DayOfWeek.SATURDAY;
                default -> DayOfWeek.SUNDAY;
            };
            if (!dias.contains(dia)) {
                dias.add(dia);
            }
        }
        if (dias.isEmpty()) {
            return false;
        }
        if (normalizado.matches(".*\\b(a|hasta)\\b.*") && dias.size() == 2) {
            DayOfWeek actual = dias.get(0);
            for (int i = 0; i < 7; i++) {
                if (actual == hoy) {
                    return true;
                }
                if (actual == dias.get(1)) {
                    break;
                }
                actual = actual.plus(1);
            }
            return hoy == dias.get(1);
        }
        return dias.contains(hoy);
    }

    private Mono<PedidoDTO.Response> construirYGuardar(String clienteId,
                                                       PedidoDTO.CrearRequest request,
                                                       ConfiguracionEmpresa cfg,
                                                       List<DetallePedidoDTO> lineas,
                                                       UbicacionDTO ubicacionEntrega) {
        BigDecimal total = lineas.stream()
                .map(DetallePedidoDTO::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Pedido pedido = new Pedido();
        pedido.setClienteId(clienteId);
        pedido.setEstado(EstadoPedido.PENDIENTE);
        pedido.setTotal(total);
        pedido.setDireccionEntrega(request.getDireccionEntrega());
        pedido.setUbicacionEntrega(mapper.toModel(ubicacionEntrega));
        pedido.setObservaciones(request.getObservaciones());
        pedido.setMetodoPago(request.getMetodoPago());
        pedido.setCostoEnvio(BigDecimal.ZERO);
        pedido.setTiempoEstimadoMinutos(cfg.getTiempoEstimadoEntregaMinutos());

        // Se fija el ID antes de reservar para que la reserva y su compensación sean idempotentes.
        pedido.setId(UUID.randomUUID().toString());
        Map<String, Integer> cantidades = cantidadesPorProducto(lineas);
        return inventarioService.aplicarMovimientoPedido(
                        pedido.getId(), cantidades, true, "Reserva automática por pedido")
                .then(Mono.defer(() -> uidActual()
                        .flatMap(uid -> {
                            agregarHistorial(pedido, EstadoPedido.PENDIENTE, uid, "Pedido registrado");
                            return pedidoRepository.save(pedido);
                        })
                        .flatMap(guardado -> guardarDetalle(guardado, lineas)
                                .then(notificarCliente(
                                        guardado,
                                        "Pedido registrado",
                                        "Recibimos tu pedido #" + corto(guardado.getId()) + ". Te avisaremos cuando cambie de estado."
                                ))
                                .thenReturn(mapper.toDTO(guardado, lineas)))
                        .onErrorResume(error -> limpiarPedidoFallido(pedido.getId(), cantidades, error))));
    }

    private Mono<DetallePedidoDTO> resolverLinea(DetallePedidoDTO linea) {
        return productoRepository.findById(linea.getProductoId())
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Producto", linea.getProductoId())))
                .flatMap(producto -> {
                    if (!producto.isActivo()) {
                        return Mono.error(new OperacionInvalidaException(
                                "El producto " + producto.getNombre() + " no está disponible"));
                    }
                    if (linea.getCantidad() == null || linea.getCantidad() <= 0) {
                        return Mono.error(new OperacionInvalidaException(
                                "La cantidad debe ser mayor que cero para " + producto.getNombre()));
                    }
                    BigDecimal subtotal = producto.getPrecioUnitario()
                            .multiply(BigDecimal.valueOf(linea.getCantidad()));
                    return Mono.just(new DetallePedidoDTO(
                            null,
                            producto.getId(),
                            linea.getCantidad(),
                            producto.getPrecioUnitario(),
                            subtotal
                    ));
                });
    }

    private Mono<Void> guardarDetalle(Pedido pedido, List<DetallePedidoDTO> lineas) {
        return Flux.fromIterable(lineas)
                .concatMap(linea -> {
                    DetallePedido detalle = new DetallePedido();
                    detalle.setPedidoId(pedido.getId());
                    detalle.setProductoId(linea.getProductoId());
                    detalle.setCantidad(linea.getCantidad());
                    detalle.setPrecioUnitario(linea.getPrecioUnitario());
                    detalle.setSubtotal(linea.getSubtotal());
                    return detallePedidoRepository.save(detalle);
                })
                .then();
    }

    private Map<String, Integer> cantidadesPorProducto(List<DetallePedidoDTO> lineas) {
        Map<String, Integer> cantidades = new LinkedHashMap<>();
        for (DetallePedidoDTO linea : lineas) {
            cantidades.merge(linea.getProductoId(), linea.getCantidad(), Math::addExact);
        }
        return cantidades;
    }

    private Mono<PedidoDTO.Response> limpiarPedidoFallido(String pedidoId,
                                                          Map<String, Integer> cantidades,
                                                          Throwable error) {
        return detallePedidoRepository.deleteByPedidoId(pedidoId)
                .onErrorResume(limpiezaError -> Mono.empty())
                .then(pedidoRepository.deleteById(pedidoId).onErrorResume(limpiezaError -> Mono.empty()))
                .then(inventarioService.aplicarMovimientoPedido(
                        "compensacion-" + pedidoId,
                        cantidades,
                        false,
                        "Reposición por fallo al crear el pedido " + corto(pedidoId)))
                .then(Mono.error(error));
    }

    // =====================================================================
    // Lecturas
    // =====================================================================

    public Mono<PedidoDTO.Response> obtenerPorId(String id, String uid, Rol rol) {
        return buscar(id)
                .flatMap(p -> autorizarLectura(p, uid, rol).thenReturn(p))
                .flatMap(this::enriquecer);
    }

    private Mono<Void> autorizarLectura(Pedido pedido, String uid, Rol rol) {
        if (rol == Rol.CLIENTE) {
            return verificarPropietario(pedido, uid);
        }
        if (rol == Rol.REPARTIDOR) {
            return verificarRepartidorAsignado(pedido, uid);
        }
        return Mono.empty();
    }

    public Flux<PedidoDTO.Response> listarFiltrado(EstadoPedido estado, LocalDate desde, LocalDate hasta) {
        Instant inicio = desde != null ? desde.atStartOfDay(ZONA_LIMA).toInstant() : null;
        Instant fin = hasta != null ? hasta.plusDays(1).atStartOfDay(ZONA_LIMA).toInstant() : null;

        Flux<Pedido> base = estado != null
                ? pedidoRepository.findByEstado(estado)
                : pedidoRepository.findAll();

        return ordenados(base.filter(p -> {
            if (inicio == null && fin == null) {
                return true;
            }
            if (p.getCreadoEn() == null) {
                return false;
            }
            Instant t = p.getCreadoEn().toInstant();
            return (inicio == null || !t.isBefore(inicio))
                    && (fin == null || t.isBefore(fin));
        }).flatMap(this::enriquecer));
    }

    public Flux<PedidoDTO.Response> listarPorCliente(String clienteId) {
        return ordenados(pedidoRepository.findByClienteId(clienteId).flatMap(this::enriquecer));
    }

    public Flux<PedidoDTO.Response> listarPorRepartidor(String repartidorId) {
        return ordenados(pedidoRepository.findByRepartidorId(repartidorId).flatMap(this::enriquecer));
    }

    private Flux<PedidoDTO.Response> ordenados(Flux<PedidoDTO.Response> flux) {
        return flux.collectList()
                .flatMapMany(lista -> Flux.fromIterable(
                        lista.stream()
                                .sorted(Comparator.comparing(
                                        PedidoDTO.Response::getCreadoEn,
                                        Comparator.nullsLast(Comparator.reverseOrder())
                                ))
                                .toList()
                ));
    }

    private Mono<PedidoDTO.Response> enriquecer(Pedido pedido) {
        return detallePedidoRepository.findByPedidoId(pedido.getId())
                .map(mapper::toDTO)
                .collectList()
                .map(detalle -> mapper.toDTO(pedido, detalle));
    }

    public Mono<PedidoDTO.AlertasResponse> alertas() {
        return configuracionService.obtener()
                .flatMap(cfg -> pedidoRepository.findAll()
                        .collectList()
                        .flatMap(pedidos -> {
                            Instant ahora = Instant.now();
                            Instant limiteRetraso = ahora.minus(Duration.ofMinutes(cfg.getUmbralRetrasoMinutos()));
                            Instant limiteSinAsignar = ahora.minus(Duration.ofMinutes(cfg.getUmbralSinAsignarMinutos()));

                            List<Pedido> activos = pedidos.stream()
                                    .filter(p -> p.getEstado() != EstadoPedido.ENTREGADO
                                            && p.getEstado() != EstadoPedido.CANCELADO)
                                    .filter(p -> p.getCreadoEn() != null)
                                    .toList();

                            List<Pedido> retrasados = activos.stream()
                                    .filter(p -> p.getCreadoEn().toInstant().isBefore(limiteRetraso))
                                    .toList();

                            List<Pedido> sinAsignar = activos.stream()
                                    .filter(p -> !StringUtils.hasText(p.getRepartidorId()))
                                    .filter(p -> p.getCreadoEn().toInstant().isBefore(limiteSinAsignar))
                                    .toList();

                            return Mono.zip(
                                    enriquecerLista(retrasados),
                                    enriquecerLista(sinAsignar)
                            ).map(t -> new PedidoDTO.AlertasResponse(t.getT1(), t.getT2()));
                        }));
    }

    private Mono<List<PedidoDTO.Response>> enriquecerLista(List<Pedido> pedidos) {
        return Flux.fromIterable(pedidos)
                .concatMap(this::enriquecer)
                .collectList();
    }


    public Mono<PedidoDTO.Response> editar(String pedidoId, String uid, PedidoDTO.EditarRequest request) {
        return buscar(pedidoId)
                .flatMap(pedido -> verificarPropietario(pedido, uid)
                        .then(Mono.defer(() -> {
                            if (pedido.getEstado() != EstadoPedido.PENDIENTE) {
                                return Mono.error(new OperacionInvalidaException(
                                        "Solo se puede editar un pedido mientras está PENDIENTE"
                                ));
                            }

                            pedido.setDireccionEntrega(request.getDireccionEntrega());
                            if (request.getUbicacionEntrega() != null) {
                                pedido.setUbicacionEntrega(mapper.toModel(request.getUbicacionEntrega()));
                            }
                            pedido.setObservaciones(request.getObservaciones());
                            return pedidoRepository.save(pedido);
                        })))
                .flatMap(this::enriquecer);
    }

    public Mono<PedidoDTO.Response> cancelarPorCliente(String pedidoId, String uid) {
        return buscar(pedidoId)
                .flatMap(pedido -> verificarPropietario(pedido, uid)
                        .then(Mono.defer(() -> {
                            if (pedido.getEstado() != EstadoPedido.PENDIENTE
                                    && pedido.getEstado() != EstadoPedido.CONFIRMADO) {
                                return Mono.error(new OperacionInvalidaException(
                                        "Solo puedes cancelar pedidos PENDIENTE o CONFIRMADO"
                                ));
                            }
                            return cambiarEstado(pedidoId, EstadoPedido.CANCELADO, "Cancelado por el cliente");
                        })));
    }

    // =====================================================================
    // Asignación de repartidores
    // =====================================================================

    public Mono<PedidoDTO.Response> asignarRepartidor(String pedidoId, String repartidorId) {
        return asignarRepartidor(pedidoId, repartidorId, true);
    }

    private Mono<PedidoDTO.Response> asignarRepartidor(
            String pedidoId,
            String repartidorId,
            boolean asignacionManual
    ) {
        return uidActual()
                .flatMap(uid -> buscar(pedidoId)
                        .flatMap(pedido -> {
                            EstadoPedido e = pedido.getEstado();
                            if (e != EstadoPedido.PENDIENTE
                                    && e != EstadoPedido.CONFIRMADO
                                    && e != EstadoPedido.EN_PREPARACION) {
                                return Mono.error(new OperacionInvalidaException(
                                    "Solo se puede asignar repartidor a pedidos PENDIENTE, CONFIRMADO o EN_PREPARACION"
                                ));
                            }
                            if (repartidorId.equals(pedido.getRepartidorId())) {
                                return Mono.just(pedido);
                            }
                            return repartidorService.sincronizarEstados()
                                    .then(asignacionManual
                                            ? repartidorRepository.reclamarParaAsignacionManual(repartidorId)
                                            : repartidorRepository.reclamarDisponible(repartidorId))
                                    .onErrorMap(IllegalStateException.class,
                                            error -> new OperacionInvalidaException(error.getMessage()))
                                    .flatMap(repartidor -> {
                                        String anterior = pedido.getRepartidorId();
                                        pedido.setRepartidorId(repartidor.getId());
                                        pedido.setEntregaAceptada(false);

                                        if (pedido.getEstado() == EstadoPedido.PENDIENTE) {
                                            pedido.setEstado(EstadoPedido.CONFIRMADO);
                                            agregarHistorial(
                                                    pedido,
                                                    EstadoPedido.CONFIRMADO,
                                                    uid,
                                                    "Confirmado al asignar repartidor"
                                            );
                                        }

                                        agregarHistorial(
                                                pedido,
                                                pedido.getEstado(),
                                                uid,
                                                "Repartidor asignado: " + corto(repartidor.getId())
                                        );

                                        return pedidoRepository.save(pedido)
                                                .flatMap(guardado -> liberarRepartidorSiLibre(anterior)
                                                        .thenReturn(guardado))
                                                .onErrorResume(error -> liberarRepartidorSiLibre(repartidorId)
                                                        .then(Mono.error(error)));
                                    });
                        })
                        .flatMap(this::enriquecer));
    }

    public Mono<PedidoDTO.Response> asignarRepartidorAutomatico(String pedidoId) {
        return buscar(pedidoId)
                .flatMap(pedido -> {
                    if (StringUtils.hasText(pedido.getRepartidorId())) {
                        return Mono.error(new OperacionInvalidaException(
                                "El pedido ya tiene un repartidor asignado"));
                    }
                    if (pedido.getEstado() != EstadoPedido.PENDIENTE
                            && pedido.getEstado() != EstadoPedido.CONFIRMADO
                            && pedido.getEstado() != EstadoPedido.EN_PREPARACION) {
                        return Mono.error(new OperacionInvalidaException(
                                "El pedido ya no está disponible para asignación"));
                    }
                    Ubicacion destino = pedido.getUbicacionEntrega();
                    if (!coordenadasValidas(destino)) {
                        return Mono.error(new OperacionInvalidaException(
                                "El pedido no tiene coordenadas para calcular la cercanía"));
                    }
                    return repartidorService.sincronizarEstados()
                            .thenMany(repartidorRepository.findByEstado(EstadoRepartidor.DISPONIBLE))
                            .filter(Repartidor::isActivo)
                            .filter(repartidor -> coordenadasValidas(
                                    repartidor.getUbicacionActual()))
                            .collectList()
                            .flatMapMany(disponibles -> Flux.fromIterable(disponibles.stream()
                                    .sorted(Comparator.comparingDouble(repartidor ->
                                            distanciaMetros(destino, repartidor.getUbicacionActual())))
                                    .toList()))
                            .concatMap(repartidor -> asignarRepartidor(pedidoId, repartidor.getId(), false)
                                    .onErrorResume(OperacionInvalidaException.class, error ->
                                            error.getMessage() != null
                                                    && error.getMessage().contains("no está activo y disponible")
                                                    ? Mono.empty()
                                                    : Mono.error(error)))
                            .next()
                            .switchIfEmpty(Mono.error(new OperacionInvalidaException(
                                    "No hay repartidores disponibles con ubicación GPS")));
                });
    }

    /**
     * Reintenta asignar pedidos vigentes cuando un repartidor reporta GPS
     * o se pone disponible después de que se creó el pedido.
     */
    public Mono<Void> asignarPedidosSinRepartidor() {
        return pedidoRepository.findAll()
                .filter(pedido -> !StringUtils.hasText(pedido.getRepartidorId()))
                .filter(pedido -> pedido.getEstado() == EstadoPedido.PENDIENTE
                        || pedido.getEstado() == EstadoPedido.CONFIRMADO
                        || pedido.getEstado() == EstadoPedido.EN_PREPARACION)
                .filter(pedido -> coordenadasValidas(pedido.getUbicacionEntrega()))
                .concatMap(pedido -> asignarRepartidorAutomatico(pedido.getId())
                        .onErrorResume(OperacionInvalidaException.class, error ->
                                error.getMessage() != null
                                        && error.getMessage().startsWith("No hay repartidores disponibles")
                                        ? Mono.empty()
                                        : Mono.error(error)))
                .then();
    }

    private boolean coordenadasValidas(Ubicacion ubicacion) {
        return ubicacion != null
                && ubicacion.getLatitud() != null
                && ubicacion.getLongitud() != null
                && Double.isFinite(ubicacion.getLatitud())
                && Double.isFinite(ubicacion.getLongitud())
                && ubicacion.getLatitud() >= -90 && ubicacion.getLatitud() <= 90
                && ubicacion.getLongitud() >= -180 && ubicacion.getLongitud() <= 180;
    }

    private double distanciaMetros(Ubicacion origen, Ubicacion destino) {
        double latitud1 = Math.toRadians(origen.getLatitud());
        double latitud2 = Math.toRadians(destino.getLatitud());
        double deltaLatitud = latitud2 - latitud1;
        double deltaLongitud = Math.toRadians(destino.getLongitud() - origen.getLongitud());
        double a = Math.sin(deltaLatitud / 2) * Math.sin(deltaLatitud / 2)
                + Math.cos(latitud1) * Math.cos(latitud2)
                * Math.sin(deltaLongitud / 2) * Math.sin(deltaLongitud / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public Mono<PedidoDTO.Response> reasignar(String pedidoId, String nuevoRepartidorId, String comentario) {
        return uidActual()
                .flatMap(uid -> buscar(pedidoId)
                        .flatMap(pedido -> {
                            if (pedido.getEstado() == EstadoPedido.ENTREGADO
                                    || pedido.getEstado() == EstadoPedido.CANCELADO) {
                                return Mono.error(new OperacionInvalidaException(
                                        "No se puede reasignar un pedido finalizado"
                                ));
                            }

                            if (!StringUtils.hasText(pedido.getRepartidorId())) {
                                return Mono.error(new OperacionInvalidaException(
                                        "El pedido aún no tiene repartidor: use asignar"
                                ));
                            }

                            if (nuevoRepartidorId.equals(pedido.getRepartidorId())) {
                                return Mono.error(new OperacionInvalidaException(
                                        "El pedido ya está asignado a ese repartidor"
                                ));
                            }

                            String anterior = pedido.getRepartidorId();

                            return repartidorRepository.reclamarDisponible(nuevoRepartidorId)
                                    .onErrorMap(IllegalStateException.class,
                                            error -> new OperacionInvalidaException(error.getMessage()))
                                    .flatMap(nuevo -> {
                                        pedido.setRepartidorId(nuevo.getId());
                                        pedido.setEntregaAceptada(false);

                                        agregarHistorial(
                                                pedido,
                                                pedido.getEstado(),
                                                uid,
                                                "Reasignado de " + corto(anterior) + " a " + corto(nuevo.getId())
                                                        + (StringUtils.hasText(comentario) ? ": " + comentario : "")
                                        );

                                        return pedidoRepository.save(pedido)
                                                .flatMap(guardado -> liberarRepartidorSiLibre(anterior)
                                                        .thenReturn(guardado))
                                                .onErrorResume(error -> liberarRepartidorSiLibre(nuevo.getId())
                                                        .then(Mono.error(error)));
                                    });
                        })
                        .flatMap(this::enriquecer));
    }


    public Mono<PedidoDTO.Response> cambiarEstado(String pedidoId, EstadoPedido nuevoEstado) {
        return cambiarEstado(pedidoId, nuevoEstado, null);
    }

    public Mono<PedidoDTO.Response> cambiarEstado(String pedidoId, EstadoPedido nuevoEstado, String comentario) {
        return uidActual()
                .flatMap(uid -> buscar(pedidoId)
                        .flatMap(pedido -> validarTransicion(pedido, nuevoEstado))
                        .doOnNext(pedido -> agregarHistorial(pedido, nuevoEstado, uid, comentario))
                        .flatMap(pedido -> aplicarEfectosSecundarios(pedido, nuevoEstado))
                        .flatMap(pedido -> notificarCliente(
                                pedido,
                                "Actualización de tu pedido",
                                "Tu pedido #" + corto(pedido.getId()) + " ahora está " + nuevoEstado.name()
                        ).thenReturn(pedido))
                        .flatMap(this::enriquecer));
    }

    public Mono<PedidoDTO.Response> validar(String pedidoId) {
        return buscar(pedidoId)
                .flatMap(pedido -> {
                    if (pedido.getEstado() == EstadoPedido.CONFIRMADO) {
                        return StringUtils.hasText(pedido.getRepartidorId())
                                || !coordenadasValidas(pedido.getUbicacionEntrega())
                                ? enriquecer(pedido)
                                : asignarAutomaticamenteSiHayRepartidor(
                                        pedido.getId(),
                                        pedido.getRepartidorId(),
                                        pedido.getUbicacionEntrega(),
                                        null
                                );
                    }
                    if (pedido.getEstado() != EstadoPedido.PENDIENTE) {
                        return Mono.error(new OperacionInvalidaException(
                                "Solo se puede validar un pedido PENDIENTE; estado actual: " + pedido.getEstado()
                        ));
                    }
                    return cambiarEstado(
                                    pedidoId,
                                    EstadoPedido.CONFIRMADO,
                                    "Validado por supervisión"
                            )
                            .flatMap(confirmado -> asignarAutomaticamenteSiHayRepartidor(
                                    pedidoId,
                                    confirmado.getRepartidorId(),
                                    pedido.getUbicacionEntrega(),
                                    confirmado
                            ));
                });
    }

    private Mono<PedidoDTO.Response> asignarAutomaticamenteSiHayRepartidor(
            String pedidoId,
            String repartidorActual,
            Ubicacion ubicacionEntrega,
            PedidoDTO.Response pedidoActual
    ) {
        if (StringUtils.hasText(repartidorActual)
                || !coordenadasValidas(ubicacionEntrega)) {
            return Mono.just(pedidoActual);
        }
        return asignarRepartidorAutomatico(pedidoId)
                .onErrorResume(OperacionInvalidaException.class, error ->
                        error.getMessage() != null
                                && (error.getMessage().startsWith("No hay repartidores disponibles")
                                || error.getMessage().equals("El pedido ya tiene un repartidor asignado"))
                                ? buscar(pedidoId).flatMap(this::enriquecer)
                                : Mono.error(error));
    }

    public Mono<PedidoDTO.Response> cambiarEstadoComo(String pedidoId,
                                                      EstadoPedido nuevoEstado,
                                                      String comentario,
                                                      String uid,
                                                      Rol rol) {
        if (rol != Rol.REPARTIDOR) {
            return cambiarEstado(pedidoId, nuevoEstado, comentario);
        }

        if (nuevoEstado != EstadoPedido.EN_CAMINO && nuevoEstado != EstadoPedido.ENTREGADO) {
            return Mono.error(new ApiException(
                    HttpStatus.FORBIDDEN,
                    "El repartidor solo puede marcar EN_CAMINO o ENTREGADO"
            ));
        }

        return buscar(pedidoId)
                .flatMap(pedido -> verificarRepartidorAsignado(pedido, uid)
                        .then(Mono.defer(() -> {
                            if (nuevoEstado == EstadoPedido.EN_CAMINO && !pedido.isEntregaAceptada()) {
                                return Mono.error(new OperacionInvalidaException(
                                        "Primero debes aceptar la entrega"
                                ));
                            }
                            if (nuevoEstado == EstadoPedido.EN_CAMINO
                                    && pedido.getEstado() == EstadoPedido.CONFIRMADO) {
                                return cambiarEstado(
                                                pedidoId,
                                                EstadoPedido.EN_PREPARACION,
                                                "Pedido pasa a preparación antes de salir a ruta"
                                        )
                                        .then(cambiarEstado(pedidoId, nuevoEstado, comentario));
                            }
                            return cambiarEstado(pedidoId, nuevoEstado, comentario);
                        })));
    }

    public Mono<PedidoDTO.Response> rechazar(String pedidoId, String observaciones) {
        return buscar(pedidoId)
                .flatMap(pedido -> {
                    if (pedido.getEstado() == EstadoPedido.CANCELADO) {
                        return enriquecer(pedido);
                    }
                    if (pedido.getEstado() != EstadoPedido.PENDIENTE
                            && pedido.getEstado() != EstadoPedido.CONFIRMADO) {
                        return Mono.error(new OperacionInvalidaException(
                                "Solo se puede rechazar un pedido PENDIENTE o CONFIRMADO; estado actual: "
                                        + pedido.getEstado()
                        ));
                    }

                    return cambiarEstado(
                            pedidoId,
                            EstadoPedido.CANCELADO,
                            "Rechazado por supervisión: " + observaciones
                    );
                });
    }

    private Mono<Pedido> validarTransicion(Pedido pedido, EstadoPedido nuevoEstado) {
        EnumSet<EstadoPedido> permitidos = EnumSet.noneOf(EstadoPedido.class);
        permitidos.addAll(List.of(siguientesEstados(pedido.getEstado())));

        if (!permitidos.contains(nuevoEstado)) {
            return Mono.error(new OperacionInvalidaException(
                    "No se puede pasar de " + pedido.getEstado() + " a " + nuevoEstado
            ));
        }

        pedido.setEstado(nuevoEstado);

        if (nuevoEstado == EstadoPedido.ENTREGADO) {
            pedido.setEntregadoEn(new Date());
        }

        return Mono.just(pedido);
    }

    private EstadoPedido[] siguientesEstados(EstadoPedido actual) {
        return switch (actual) {
            case PENDIENTE -> new EstadoPedido[]{
                    EstadoPedido.CONFIRMADO,
                    EstadoPedido.CANCELADO
            };
            case CONFIRMADO -> new EstadoPedido[]{
                    EstadoPedido.EN_PREPARACION,
                    EstadoPedido.CANCELADO
            };
            case EN_PREPARACION -> new EstadoPedido[]{
                    EstadoPedido.EN_CAMINO,
                    EstadoPedido.CANCELADO
            };
            case EN_CAMINO -> new EstadoPedido[]{
                    EstadoPedido.ENTREGADO,
                    EstadoPedido.ENTREGA_FALLIDA
            };
            case ENTREGA_FALLIDA -> new EstadoPedido[]{
                    EstadoPedido.EN_CAMINO,
                    EstadoPedido.CANCELADO
            };
            case ENTREGADO, CANCELADO -> new EstadoPedido[]{};
        };
    }

    private Mono<Pedido> aplicarEfectosSecundarios(Pedido pedido, EstadoPedido nuevoEstado) {
        Mono<Void> movimientoStock = nuevoEstado == EstadoPedido.CANCELADO
                ? reponerStock(pedido)
                : Mono.empty();

        return movimientoStock.then(pedidoRepository.save(pedido))
                .flatMap(guardado -> {
                    Mono<Void> efectos = Mono.empty();

                    if (StringUtils.hasText(guardado.getRepartidorId())) {
                        EstadoRepartidor estadoRepartidor = switch (nuevoEstado) {
                            case EN_CAMINO -> EstadoRepartidor.EN_RUTA;
                            case ENTREGADO, CANCELADO -> EstadoRepartidor.DISPONIBLE;
                            default -> null;
                        };

                        if (estadoRepartidor != null) {
                            efectos = efectos.then(estadoRepartidor == EstadoRepartidor.DISPONIBLE
                                    ? liberarRepartidorSiLibre(guardado.getRepartidorId())
                                    : actualizarEstadoRepartidor(guardado.getRepartidorId(), estadoRepartidor).then());
                        }
                    }

                    return efectos.thenReturn(guardado);
        });
    }

    private Mono<Void> liberarRepartidorSiLibre(String repartidorId) {
        if (!StringUtils.hasText(repartidorId)) {
            return Mono.empty();
        }
        return pedidoRepository.findByRepartidorId(repartidorId)
                .filter(pedido -> pedido.getEstado() != EstadoPedido.ENTREGADO
                        && pedido.getEstado() != EstadoPedido.CANCELADO)
                .hasElements()
                .flatMap(tienePedidosActivos -> tienePedidosActivos
                        ? Mono.empty()
                        : actualizarEstadoRepartidor(repartidorId, EstadoRepartidor.DISPONIBLE).then());
    }

    private Mono<Void> reponerStock(Pedido pedido) {
        return detallePedidoRepository.findByPedidoId(pedido.getId())
                .collectList()
                .flatMap(detalles -> {
                    Map<String, Integer> cantidades = new LinkedHashMap<>();
                    for (DetallePedido detalle : detalles) {
                        cantidades.merge(detalle.getProductoId(), detalle.getCantidad(), Math::addExact);
                    }
                    return inventarioService.aplicarMovimientoPedido(
                            pedido.getId(),
                            cantidades,
                            false,
                            "Reposición por cancelación del pedido #" + corto(pedido.getId()));
                });
    }

    private Mono<Repartidor> actualizarEstadoRepartidor(String repartidorId, EstadoRepartidor estado) {
        return repartidorRepository.actualizarEstado(repartidorId, estado)
                .then(repartidorRepository.findById(repartidorId));
    }

    private Mono<Void> notificarCliente(Pedido pedido, String titulo, String mensaje) {
        if (!StringUtils.hasText(pedido.getClienteId())) {
            return Mono.empty();
        }

        return clienteRepository.findById(pedido.getClienteId())
                .flatMap(cliente -> notificacionService.crear(
                        cliente.getUsuarioId(),
                        titulo,
                        mensaje
                ))
                .onErrorResume(e -> Mono.empty())
                .then();
    }


    public Mono<PedidoDTO.Response> aceptarEntrega(String pedidoId, String uid) {
        return buscar(pedidoId)
                .flatMap(pedido -> verificarRepartidorAsignado(pedido, uid)
                        .then(Mono.defer(() -> {
                            if (pedido.getEstado() != EstadoPedido.CONFIRMADO
                                    && pedido.getEstado() != EstadoPedido.EN_PREPARACION) {
                                return Mono.error(new OperacionInvalidaException(
                                        "Solo puedes aceptar entregas CONFIRMADAS o EN_PREPARACION"
                                ));
                            }

                            pedido.setEntregaAceptada(true);
                            agregarHistorial(
                                    pedido,
                                    pedido.getEstado(),
                                    uid,
                                    "Entrega aceptada por el repartidor"
                            );

                            return pedidoRepository.save(pedido);
                        })))
                .flatMap(this::enriquecer);
    }

    public Mono<PedidoDTO.Response> registrarLlegada(String pedidoId, String uid) {
        return buscar(pedidoId)
                .flatMap(pedido -> verificarRepartidorAsignado(pedido, uid)
                        .then(Mono.defer(() -> {
                            if (pedido.getEstado() != EstadoPedido.EN_CAMINO) {
                                return Mono.error(new OperacionInvalidaException(
                                        "Solo puedes marcar llegada en un pedido EN_CAMINO"
                                ));
                            }

                            pedido.setLlegadaEn(new Date());
                            agregarHistorial(
                                    pedido,
                                    pedido.getEstado(),
                                    uid,
                                    "El repartidor llegó al destino"
                            );

                            return pedidoRepository.save(pedido)
                                    .flatMap(p -> notificarCliente(
                                            p,
                                            "Tu repartidor llegó",
                                            "Tu pedido #" + corto(p.getId()) + " está en la puerta."
                                    ).thenReturn(p));
                        })))
                .flatMap(this::enriquecer);
    }

    public Mono<PedidoDTO.Response> registrarEntregaFallida(String pedidoId,
                                                            String uid,
                                                            PedidoDTO.EntregaFallidaRequest request) {
        return repartidorDe(uid)
                .flatMap(repartidor -> buscar(pedidoId)
                        .flatMap(pedido -> {
                            if (!repartidor.getId().equals(pedido.getRepartidorId())) {
                                return Mono.error(new ApiException(
                                        HttpStatus.FORBIDDEN,
                                        "Este pedido no está asignado al repartidor autenticado"
                                ));
                            }

                            if (pedido.getEstado() != EstadoPedido.EN_CAMINO) {
                                return Mono.error(new OperacionInvalidaException(
                                        "Solo se puede registrar entrega fallida de un pedido EN_CAMINO"
                                ));
                            }

                            TipoIncidencia tipo = request.getTipo() != null
                                    ? request.getTipo()
                                    : TipoIncidencia.OTRO;

                            return cambiarEstado(
                                    pedidoId,
                                    EstadoPedido.ENTREGA_FALLIDA,
                                    request.getMotivo()
                            ).flatMap(respuesta -> incidenciaService.registrar(
                                    repartidor.getId(),
                                    new IncidenciaDTO.CrearRequest(
                                            pedidoId,
                                            tipo,
                                            "Entrega fallida: " + request.getMotivo()
                                    )
                            ).thenReturn(respuesta));
                        }));
    }

    public Mono<PedidoDTO.Response> calificar(String pedidoId,
                                              String uidSolicitante,
                                              PedidoDTO.CalificarRequest request) {
        return buscar(pedidoId)
                .flatMap(pedido -> verificarPropietario(pedido, uidSolicitante)
                        .then(Mono.defer(() -> {
                            if (pedido.getEstado() != EstadoPedido.ENTREGADO) {
                                return Mono.error(new OperacionInvalidaException(
                                        "Solo se puede calificar un pedido ya ENTREGADO"
                                ));
                            }

                            pedido.setCalificacion(new Calificacion(
                                    request.getEstrellas(),
                                    request.getComentario(),
                                    new Date()
                            ));

                            return pedidoRepository.save(pedido);
                        })))
                .flatMap(this::enriquecer);
    }

    public Mono<PedidoDTO.Response> guardarEvidencia(String pedidoId,
                                                     String uidSolicitante,
                                                     byte[] contenido,
                                                     String contentType,
                                                     String nombreArchivo) {
        if (contenido == null || contenido.length == 0 || contenido.length > EVIDENCIA_MAX_BYTES) {
            return Mono.error(new OperacionInvalidaException(
                    "El comprobante debe tener contenido y no superar 2 MB"));
        }
        String tipoDetectado = detectarTipoEvidencia(contenido);
        if (tipoDetectado == null
                || !TIPOS_EVIDENCIA_PERMITIDOS.contains(contentType)
                || !tipoDetectado.equals(contentType)) {
            return Mono.error(new OperacionInvalidaException(
                    "El comprobante debe ser un JPG, PNG, WEBP o PDF válido"));
        }
        return buscar(pedidoId)
                .flatMap(pedido -> verificarPropietario(pedido, uidSolicitante)
                        .then(Mono.defer(() -> {
                            if (pedido.getMetodoPago() == MetodoPago.EFECTIVO) {
                                return Mono.error(new OperacionInvalidaException(
                                        "El pago en efectivo no requiere comprobante"
                                ));
                            }

                            return subirArchivo(
                                    pedidoId,
                                    contenido,
                                    tipoDetectado,
                                    nombreArchivo
                            ).flatMap(ruta -> {
                                pedido.setEvidenciaStoragePath(ruta);
                                pedido.setEvidenciaUrl(
                                        "/api/pedidos/" + pedidoId + "/evidencia/archivo");
                                pedido.setEvidenciaContentType(tipoDetectado);
                                pedido.setEvidenciaNombreArchivo(
                                        nombreSeguro(nombreArchivo));
                                pedido.setEvidenciaTamanoBytes((long) contenido.length);
                                pedido.setEvidenciaSubidaEn(new Date());
                                return pedidoRepository.save(pedido).onErrorResume(error ->
                                        Mono.fromCallable(() -> storage.delete(storageBucketName, ruta))
                                                .subscribeOn(Schedulers.boundedElastic())
                                                .then(Mono.error(error)));
                            });
                        })))
                .flatMap(this::enriquecer);
    }

    public Mono<EvidenciaArchivo> descargarEvidencia(String pedidoId,
                                                     String uidSolicitante,
                                                     Rol rolSolicitante) {
        return buscar(pedidoId)
                .flatMap(pedido -> {
                    Mono<Void> autorizacion = rolSolicitante == Rol.CLIENTE
                            ? verificarPropietario(pedido, uidSolicitante)
                            : (rolSolicitante == Rol.ADMINISTRADOR || rolSolicitante == Rol.SUPERVISOR)
                                    ? Mono.empty()
                                    : Mono.error(new ApiException(
                                            HttpStatus.FORBIDDEN,
                                            "No tiene permiso para ver este comprobante"));

                    return autorizacion.then(Mono.defer(() -> {
                        String ruta = pedido.getEvidenciaStoragePath();
                        boolean esLegado = !StringUtils.hasText(ruta);
                        if (esLegado) {
                            ruta = extraerRutaStorageLegada(pedido.getEvidenciaUrl());
                        }
                        if (!StringUtils.hasText(ruta)) {
                            return Mono.error(new RecursoNoEncontradoException(
                                    "El pedido no tiene un comprobante disponible"));
                        }

                        String rutaFinal = ruta;
                        return Mono.fromCallable(() -> {
                            Blob blob = storage.get(BlobId.of(storageBucketName, rutaFinal));
                            if (blob == null) {
                                throw new RecursoNoEncontradoException(
                                        "No se encontró el comprobante en Firebase Storage");
                            }
                            byte[] bytes = storage.readAllBytes(blob.getBlobId());
                            String contentType = StringUtils.hasText(blob.getContentType())
                                    ? blob.getContentType()
                                    : detectarTipoEvidencia(bytes);
                            if (!StringUtils.hasText(contentType)) {
                                contentType = "application/octet-stream";
                            }
                            String nombreArchivo = StringUtils.hasText(
                                    pedido.getEvidenciaNombreArchivo())
                                    ? pedido.getEvidenciaNombreArchivo()
                                    : nombreSeguro(blob.getName());
                            return new EvidenciaLeida(
                                    new EvidenciaArchivo(
                                            bytes,
                                            contentType,
                                            nombreSeguro(nombreArchivo)),
                                    blob,
                                    rutaFinal,
                                    esLegado);
                        }).subscribeOn(Schedulers.boundedElastic())
                                .flatMap(evidencia -> {
                                    if (!evidencia.esLegado()) {
                                        return Mono.just(evidencia.archivo());
                                    }
                                    return Mono.fromCallable(() -> {
                                                revocarTokenDescarga(evidencia.blob());
                                                return true;
                                            })
                                            .subscribeOn(Schedulers.boundedElastic())
                                            .then(Mono.defer(() -> {
                                                pedido.setEvidenciaStoragePath(evidencia.ruta());
                                                pedido.setEvidenciaContentType(
                                                        evidencia.archivo().contentType());
                                                pedido.setEvidenciaNombreArchivo(
                                                        evidencia.archivo().nombreArchivo());
                                                pedido.setEvidenciaTamanoBytes(
                                                        (long) evidencia.archivo().contenido().length);
                                                if (pedido.getEvidenciaSubidaEn() == null) {
                                                    pedido.setEvidenciaSubidaEn(new Date());
                                                }
                                                pedido.setEvidenciaUrl(
                                                        "/api/pedidos/" + pedido.getId()
                                                                + "/evidencia/archivo");
                                                return pedidoRepository.save(pedido)
                                                        .thenReturn(evidencia.archivo());
                                            }));
                                });
                    }));
                });
    }

    private Mono<String> subirArchivo(String pedidoId,
                                      byte[] contenido,
                                      String contentType,
                                      String nombreArchivo) {
        String nombre = nombreSeguro(nombreArchivo);
        String ruta = "evidencias/" + pedidoId + "/" + UUID.randomUUID() + "-" + nombre;
        return Mono.fromCallable(() -> {
            BlobInfo info = BlobInfo.newBuilder(storageBucketName, ruta)
                    .setContentType(contentType)
                    .build();
            Blob blob = storage.create(info, contenido);
            if (blob == null) {
                throw new IllegalStateException("No se pudo guardar el comprobante");
            }
            return ruta;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static String detectarTipoEvidencia(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47
                && bytes[4] == 0x0D
                && bytes[5] == 0x0A
                && bytes[6] == 0x1A
                && bytes[7] == 0x0A) {
            return "image/png";
        }
        if (bytes.length >= 12
                && bytes[0] == 0x52
                && bytes[1] == 0x49
                && bytes[2] == 0x46
                && bytes[3] == 0x46
                && bytes[8] == 0x57
                && bytes[9] == 0x45
                && bytes[10] == 0x42
                && bytes[11] == 0x50) {
            return "image/webp";
        }
        if (bytes.length >= 5
                && bytes[0] == 0x25
                && bytes[1] == 0x50
                && bytes[2] == 0x44
                && bytes[3] == 0x46
                && bytes[4] == 0x2D) {
            return "application/pdf";
        }
        return null;
    }

    private static String nombreSeguro(String nombreArchivo) {
        String nombre = StringUtils.hasText(nombreArchivo)
                ? nombreArchivo.replace('\\', '/').replaceAll("^.*/", "")
                        .replaceAll("[^A-Za-z0-9._-]", "_")
                : "comprobante";
        return nombre.length() > 100 ? nombre.substring(nombre.length() - 100) : nombre;
    }

    private static String extraerRutaStorageLegada(String evidenciaUrl) {
        if (!StringUtils.hasText(evidenciaUrl)) {
            return null;
        }
        try {
            String rawPath = URI.create(evidenciaUrl).getRawPath();
            int objectStart = rawPath.indexOf("/o/");
            if (objectStart < 0) {
                return null;
            }
            return URLDecoder.decode(
                    rawPath.substring(objectStart + 3),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private void revocarTokenDescarga(Blob blob) {
        Map<String, String> metadata = new HashMap<>(
                blob.getMetadata() == null ? Map.of() : blob.getMetadata());
        metadata.remove("firebaseStorageDownloadTokens");
        storage.update(BlobInfo.newBuilder(blob.getBlobId())
                .setContentType(blob.getContentType())
                .setMetadata(metadata)
                .build());
    }

    public record EvidenciaArchivo(
            byte[] contenido,
            String contentType,
            String nombreArchivo) {
    }

    private record EvidenciaLeida(
            EvidenciaArchivo archivo,
            Blob blob,
            String ruta,
            boolean esLegado) {
    }

    private Mono<Void> verificarPropietario(Pedido pedido, String uidSolicitante) {
        return clienteRepository.findById(pedido.getClienteId())
                .switchIfEmpty(Mono.error(new OperacionInvalidaException(
                        "Cliente del pedido no encontrado"
                )))
                .flatMap(cliente -> {
                    if (!cliente.getUsuarioId().equals(uidSolicitante)) {
                        return Mono.error(new ApiException(
                                HttpStatus.FORBIDDEN,
                                "Este pedido no pertenece al usuario autenticado"
                        ));
                    }
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> verificarRepartidorAsignado(Pedido pedido, String uid) {
        return repartidorRepository.findByUsuarioId(uid)
                .filter(r -> r.getId().equals(pedido.getRepartidorId()))
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn(
                            "Acceso de repartidor rechazado: pedido={}, repartidorAsignado={}, usuarioAutenticado={}",
                            pedido.getId(),
                            pedido.getRepartidorId(),
                            uid
                    );
                    return Mono.empty();
                }))
                .switchIfEmpty(Mono.error(new ApiException(
                        HttpStatus.FORBIDDEN,
                        "Este pedido no está asignado al repartidor autenticado"
                )))
                .then();
    }
}
