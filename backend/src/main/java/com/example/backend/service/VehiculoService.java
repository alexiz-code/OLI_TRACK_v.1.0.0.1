package com.example.backend.service;

import com.example.backend.dto.VehiculoDTO;
import com.example.backend.exception.RecursoNoEncontradoException;
import com.example.backend.model.InventarioVehiculo;
import com.example.backend.model.Rol;
import com.example.backend.model.Vehiculo;
import com.example.backend.model.HistorialVehiculo;
import com.example.backend.model.Repartidor;
import com.example.backend.repository.HistorialVehiculoRepository;
import com.example.backend.repository.InventarioVehiculoRepository;
import com.example.backend.repository.RepartidorRepository;
import com.example.backend.repository.VehiculoRepository;
import com.example.backend.repository.PedidoRepository;
import com.example.backend.model.EstadoPedido;
import com.example.backend.model.Pedido;
import com.example.backend.security.SecurityContextUtils;
import com.example.backend.security.UsuarioAutenticado;
import com.example.backend.config.FirebaseProperties;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Date;
import java.util.UUID;
import java.util.List;

@Service
public class VehiculoService {
    private final VehiculoRepository vehiculoRepository;
    private final InventarioVehiculoRepository inventarioRepository;
    private final RepartidorRepository repartidorRepository;
    private final HistorialVehiculoRepository historialRepository;
    private final PedidoRepository pedidoRepository;
    private final Storage storage;
    private final String storageBucketName;

    public VehiculoService(VehiculoRepository vehiculoRepository,
                           InventarioVehiculoRepository inventarioRepository,
                           RepartidorRepository repartidorRepository,
                           HistorialVehiculoRepository historialRepository,
                           PedidoRepository pedidoRepository,
                           Storage storage,
                           FirebaseProperties firebaseProperties) {
        this.vehiculoRepository = vehiculoRepository;
        this.inventarioRepository = inventarioRepository;
        this.repartidorRepository = repartidorRepository;
        this.historialRepository = historialRepository;
        this.pedidoRepository = pedidoRepository;
        this.storage = storage;
        this.storageBucketName = firebaseProperties.getStorageBucket();
    }

    public Mono<VehiculoDTO.Response> crear(VehiculoDTO.CrearRequest request) {
        Vehiculo vehiculo = new Vehiculo();
        vehiculo.setPlaca(request.getPlaca().trim().toUpperCase());
        vehiculo.setDescripcion(request.getDescripcion());
        completarCampos(vehiculo, request);
        vehiculo.setActivo(true);
        vehiculo.setActualizadoEn(new Date());
        return SecurityContextUtils.usuarioActual().flatMap(actual -> autorizado(actual)
                .then(vehiculoRepository.save(vehiculo))
                .flatMap(guardado -> registrarHistorial(guardado.getId(), "CREACION", "Vehículo registrado", actual.getUid())
                        .thenReturn(mapVehiculo(guardado))));
    }

    public Mono<VehiculoDTO.Response> actualizar(String id, VehiculoDTO.CrearRequest request) {
        return SecurityContextUtils.usuarioActual().flatMap(actual -> autorizado(actual)
                .then(vehiculoRepository.findById(id).switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Vehículo", id))))
                .flatMap(v -> {
                    v.setPlaca(request.getPlaca().trim().toUpperCase()); v.setDescripcion(request.getDescripcion());
                    completarCampos(v, request); v.setActualizadoEn(new Date());
                    return vehiculoRepository.save(v).flatMap(guardado -> registrarHistorial(id, "EDICION", "Datos del vehículo actualizados", actual.getUid()).thenReturn(mapVehiculo(guardado)));
                }));
    }

    public Mono<Void> desactivar(String id) {
        return SecurityContextUtils.usuarioActual().flatMap(actual -> autorizado(actual)
                .then(vehiculoRepository.findById(id).switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Vehículo", id))))
                .flatMap(v -> tienePedidosActivos(v.getRepartidorId()).flatMap(activos -> {
                    if (activos) return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "No se puede desactivar un vehículo con pedidos activos."));
                    v.setActivo(false); v.setEstadoOperativo("INACTIVO"); v.setActualizadoEn(new Date());
                    return vehiculoRepository.save(v).then(registrarHistorial(id, "DESACTIVACION", "Vehículo desactivado", actual.getUid()));
                })));
    }

    public Mono<VehiculoDTO.Response> cambiarEstado(String id, VehiculoDTO.EstadoRequest request) {
        String estado = request.getEstadoOperativo().trim().toUpperCase();
        if (!java.util.Set.of("DISPONIBLE", "EN_REPARTO", "PENDIENTE_REVISION", "MANTENIMIENTO", "INACTIVO").contains(estado))
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado operativo no válido."));
        return SecurityContextUtils.usuarioActual().flatMap(actual -> autorizado(actual)
                .then(vehiculoRepository.findById(id).switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Vehículo", id))))
                .flatMap(v -> tienePedidosActivos(v.getRepartidorId()).flatMap(activos -> {
                    if (activos && ("DISPONIBLE".equals(estado) || "INACTIVO".equals(estado)))
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Resuelve los pedidos activos antes de cambiar el vehículo a disponible o inactivo."));
                    v.setEstadoOperativo(estado); v.setActivo(!"INACTIVO".equals(estado)); v.setActualizadoEn(new Date());
                    String detalle = request.getMotivo() == null ? estado : estado + ": " + request.getMotivo();
                    return vehiculoRepository.save(v).flatMap(guardado -> registrarHistorial(id, "CAMBIO_ESTADO", detalle, actual.getUid()).thenReturn(mapVehiculo(guardado)));
                })));
    }

    public Mono<VehiculoDTO.Response> asignarRepartidor(String vehiculoId, VehiculoDTO.AsignacionRequest request) {
        return SecurityContextUtils.usuarioActual().flatMap(actual -> autorizado(actual)
                .then(vehiculoRepository.findById(vehiculoId).switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Vehículo", vehiculoId))))
                .flatMap(v -> repartidorRepository.findById(request.getRepartidorId()).switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Repartidor", request.getRepartidorId())))
                        .flatMap(r -> {
                            if (!r.isActivo()) return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "El repartidor está inactivo."));
                            return Mono.zip(tienePedidosActivos(r.getId()), vehiculoRepository.findByField("repartidorId", r.getId()).collectList())
                                    .flatMap(estado -> {
                                if (estado.getT1()) return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "No se puede cambiar el responsable mientras tenga pedidos activos."));
                                java.util.List<Vehiculo> vehiculosActuales = estado.getT2();
                                boolean asignadoAotro = vehiculosActuales.stream().anyMatch(actualVehiculo -> !vehiculoId.equals(actualVehiculo.getId()));
                                if (asignadoAotro) return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "El repartidor ya tiene otro vehículo asignado."));
                                return tienePedidosActivos(v.getRepartidorId()).flatMap(vehiculoConPedidos -> {
                                    if (vehiculoConPedidos) return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Resuelve los pedidos activos del vehículo antes de cambiar el conductor."));
                                    return repartidorRepository.asignarVehiculo(vehiculoId, r.getId(), v.getPlaca(), actual.getUid(), request.getMotivo())
                                            .then(vehiculoRepository.findById(vehiculoId)).map(this::mapVehiculo);
                                });
                            });
                        })));
    }

    private Mono<Boolean> tienePedidosActivos(String repartidorId) {
        if (repartidorId == null || repartidorId.isBlank()) return Mono.just(false);
        return pedidoRepository.findByRepartidorId(repartidorId)
                .filter(p -> p.getEstado() != EstadoPedido.ENTREGADO && p.getEstado() != EstadoPedido.CANCELADO)
                .hasElements();
    }

    public Mono<VehiculoDTO.Response> desasignarRepartidor(String vehiculoId, VehiculoDTO.MotivoRequest request) {
        return SecurityContextUtils.usuarioActual().flatMap(actual -> autorizado(actual)
                .then(vehiculoRepository.findById(vehiculoId).switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Vehículo", vehiculoId))))
                .flatMap(v -> tienePedidosActivos(v.getRepartidorId()).flatMap(activos -> {
                    if (activos) return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Resuelve los pedidos activos antes de retirar al conductor."));
                    return repartidorRepository.desasignarVehiculo(vehiculoId, actual.getUid(), request.getMotivo())
                            .then(vehiculoRepository.findById(vehiculoId)).map(this::mapVehiculo);
                })));
    }

    public Flux<HistorialVehiculo> historial(String id) {
        return vehiculoRepository.findById(id).switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Vehículo", id)))
                .flatMapMany(v -> historialRepository.findByField("vehiculoId", id));
    }

    public Mono<VehiculoDTO.Response> subirFoto(String id, byte[] bytes, String contentType) {
        String detectado = tipoImagen(bytes);
        if (bytes == null || bytes.length == 0 || bytes.length > 5 * 1024 * 1024 || detectado == null || !detectado.equals(contentType))
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "La foto debe ser JPG, PNG o WEBP y no superar 5 MB."));
        return SecurityContextUtils.usuarioActual().flatMap(actual -> autorizado(actual)
                .then(vehiculoRepository.findById(id).switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Vehículo", id))))
                .flatMap(v -> {
                    String previo = v.getFotoStoragePath(); String path = "imagenes-vehiculos/" + id + "/" + UUID.randomUUID();
                    return Mono.fromCallable(() -> storage.create(BlobInfo.newBuilder(storageBucketName, path).setContentType(detectado).build(), bytes))
                            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                            .flatMap(blob -> {
                                if (blob == null) return Mono.error(new IllegalStateException("No se guardó la fotografía."));
                                v.setFotoStoragePath(path); v.setFotoUrl("/api/vehiculos/" + id + "/foto?v=" + UUID.randomUUID());
                                v.setActualizadoEn(new Date());
                                return vehiculoRepository.save(v).flatMap(saved -> registrarHistorial(id, "FOTO", "Fotografía actualizada", actual.getUid())
                                        .then(eliminarFotoStorage(previo)).thenReturn(mapVehiculo(saved)));
                            });
                }));
    }

    public Mono<ImagenVehiculo> obtenerFoto(String id) {
        return vehiculoRepository.findById(id).switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Vehículo", id)))
                .flatMap(v -> {
                    if (v.getFotoStoragePath() == null) return Mono.error(RecursoNoEncontradoException.de("Foto del vehículo", id));
                    return Mono.fromCallable(() -> {
                        Blob blob = storage.get(BlobId.of(storageBucketName, v.getFotoStoragePath()));
                        if (blob == null) throw RecursoNoEncontradoException.de("Foto del vehículo", id);
                        return new ImagenVehiculo(storage.readAllBytes(blob.getBlobId()), blob.getContentType() == null ? "image/jpeg" : blob.getContentType());
                    }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
                });
    }

    public Mono<Void> eliminarFoto(String id) {
        return SecurityContextUtils.usuarioActual().flatMap(actual -> autorizado(actual)
                .then(vehiculoRepository.findById(id).switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Vehículo", id))))
                .flatMap(v -> { String path = v.getFotoStoragePath(); v.setFotoStoragePath(null); v.setFotoUrl(null); v.setActualizadoEn(new Date());
                    return vehiculoRepository.save(v).then(eliminarFotoStorage(path)).then(registrarHistorial(id, "FOTO", "Fotografía eliminada", actual.getUid())); }));
    }

    private Mono<Void> eliminarFotoStorage(String path) {
        if (path == null) return Mono.empty();
        return Mono.fromCallable(() -> storage.delete(BlobId.of(storageBucketName, path))).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).then();
    }
    private String tipoImagen(byte[] b) {
        if (b == null || b.length < 12) return null;
        if ((b[0] & 255) == 255 && (b[1] & 255) == 216 && (b[2] & 255) == 255) return "image/jpeg";
        if ((b[0] & 255) == 137 && b[1] == 80 && b[2] == 78 && b[3] == 71) return "image/png";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }
    public record ImagenVehiculo(byte[] contenido, String contentType) { }

    private Mono<Void> autorizado(UsuarioAutenticado actual) {
        return actual.getRol() == Rol.ADMINISTRADOR || actual.getRol() == Rol.SUPERVISOR ? Mono.empty()
                : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "No autorizado para administrar vehículos."));
    }
    private void completarCampos(Vehiculo v, VehiculoDTO.CrearRequest r) {
        v.setMarca(r.getMarca()); v.setModelo(r.getModelo()); v.setTipo(r.getTipo()); v.setColor(r.getColor());
        v.setAnio(r.getAnio()); v.setCapacidadMaximaKg(r.getCapacidadMaximaKg());
        if (r.getEstadoOperativo() != null) v.setEstadoOperativo(r.getEstadoOperativo().toUpperCase());
    }
    private Mono<Void> registrarHistorial(String id, String tipo, String detalle, String responsable) {
        HistorialVehiculo h = new HistorialVehiculo(); h.setVehiculoId(id); h.setTipo(tipo); h.setDetalle(detalle); h.setResponsableId(responsable);
        return historialRepository.save(h).then();
    }

    public Flux<VehiculoDTO.Response> listar() {
        return SecurityContextUtils.usuarioActual().flatMapMany(actual -> {
            if (actual.getRol() != Rol.REPARTIDOR) return vehiculoRepository.findAll().flatMap(this::mapVehiculoCompleto);
            return repartidorRepository.findByUsuarioId(actual.getUid())
                    .flatMapMany(repartidor -> vehiculoRepository.findByField("repartidorId", repartidor.getId()))
                    .flatMap(this::mapVehiculoCompleto);
        });
    }

    public Mono<VehiculoDTO.Response> obtener(String id) {
        return SecurityContextUtils.usuarioActual().flatMap(actual -> vehiculoRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Vehículo", id)))
                .flatMap(vehiculo -> autorizarVehiculo(actual, vehiculo).thenReturn(vehiculo))
                .flatMap(this::mapVehiculoCompleto));
    }

    public Flux<InventarioVehiculo> listarStock(String vehiculoId) {
        return SecurityContextUtils.usuarioActual().flatMapMany(actual -> vehiculoRepository.findById(vehiculoId)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Vehículo", vehiculoId)))
                .flatMapMany(vehiculo -> autorizarVehiculo(actual, vehiculo)
                        .thenMany(inventarioRepository.listarPorVehiculo(vehiculoId))));
    }

    private Mono<Void> autorizarVehiculo(UsuarioAutenticado actual, Vehiculo vehiculo) {
        if (actual.getRol() != Rol.REPARTIDOR) return Mono.empty();
        return repartidorRepository.findByUsuarioId(actual.getUid())
                .filter(repartidor -> repartidor.getId().equals(vehiculo.getRepartidorId()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Solo puedes consultar el inventario de tu vehículo asignado.")))
                .then();
    }

    public Mono<InventarioVehiculo> cargar(String vehiculoId, VehiculoDTO.CargaRequest request) {
        return vehiculoRepository.findById(vehiculoId)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Vehículo", vehiculoId)))
                .flatMap(vehiculo -> SecurityContextUtils.usuarioActual()
                        .flatMap(actual -> {
                            if (actual.getRol() != Rol.ADMINISTRADOR && actual.getRol() != Rol.SUPERVISOR)
                                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "No autorizado para transferir inventario."));
                            if (vehiculo.getRepartidorId() == null || vehiculo.getRepartidorId().isBlank())
                                return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "El vehículo no tiene repartidor asignado."));
                            java.util.Map<String, Integer> cantidades = new java.util.LinkedHashMap<>();
                            if (request.getProductos() != null) request.getProductos().forEach(item -> cantidades.put(item.getProductoId(), item.getCantidad()));
                            if (cantidades.isEmpty() && request.getProductoId() != null) cantidades.put(request.getProductoId(), request.getCantidad());
                            return inventarioRepository.cargarDesdePlanta(request.getOperacionId(), vehiculoId,
                                    vehiculo.getRepartidorId(), cantidades, request.getMotivo(), actual.getUid());
                        })
                        .onErrorMap(error -> error instanceof IllegalStateException
                                        || error instanceof IllegalArgumentException,
                                error -> new com.example.backend.exception.OperacionInvalidaException(
                                        error.getMessage()
                                )));
    }

    public Mono<InventarioVehiculo> devolver(String vehiculoId, VehiculoDTO.CargaRequest request) {
        return vehiculoRepository.findById(vehiculoId)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Vehículo", vehiculoId)))
                .flatMap(vehiculo -> SecurityContextUtils.usuarioActual().flatMap(actual -> {
                    if (actual.getRol() != Rol.ADMINISTRADOR && actual.getRol() != Rol.SUPERVISOR)
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "No autorizado para devolver inventario."));
                    java.util.Map<String, Integer> cantidades = new java.util.LinkedHashMap<>();
                    if (request.getProductos() != null) request.getProductos().forEach(item -> cantidades.put(item.getProductoId(), item.getCantidad()));
                    if (cantidades.isEmpty() && request.getProductoId() != null) cantidades.put(request.getProductoId(), request.getCantidad());
                    return inventarioRepository.devolverAPlanta(request.getOperacionId(), vehiculoId,
                            vehiculo.getRepartidorId(), cantidades, request.getMotivo(), actual.getUid())
                            .onErrorMap(error -> error instanceof IllegalStateException || error instanceof IllegalArgumentException,
                                    error -> new com.example.backend.exception.OperacionInvalidaException(error.getMessage()));
                }));
    }

    private Mono<VehiculoDTO.Response> mapVehiculoCompleto(Vehiculo vehiculo) {
        return inventarioRepository.listarPorVehiculo(vehiculo.getId())
                .map(this::mapStock)
                .collectList()
                .map(stock -> {
                    VehiculoDTO.Response response = mapVehiculo(vehiculo);
                    response.setStock(stock);
                    return response;
                });
    }

    private VehiculoDTO.Response mapVehiculo(Vehiculo vehiculo) {
        VehiculoDTO.Response response = new VehiculoDTO.Response();
        response.setId(vehiculo.getId());
        response.setPlaca(vehiculo.getPlaca());
        response.setDescripcion(vehiculo.getDescripcion());
        response.setMarca(vehiculo.getMarca()); response.setModelo(vehiculo.getModelo()); response.setTipo(vehiculo.getTipo());
        response.setColor(vehiculo.getColor()); response.setAnio(vehiculo.getAnio()); response.setCapacidadMaximaKg(vehiculo.getCapacidadMaximaKg());
        response.setEstadoOperativo(vehiculo.getEstadoOperativo()); response.setFotoUrl(vehiculo.getFotoUrl());
        response.setRepartidorId(vehiculo.getRepartidorId());
        response.setActivo(vehiculo.isActivo());
        response.setCreadoEn(vehiculo.getCreadoEn());
        response.setActualizadoEn(vehiculo.getActualizadoEn());
        return response;
    }

    private VehiculoDTO.StockResponse mapStock(InventarioVehiculo stock) {
        VehiculoDTO.StockResponse response = new VehiculoDTO.StockResponse();
        response.setId(stock.getId());
        response.setVehiculoId(stock.getVehiculoId());
        response.setRepartidorId(stock.getRepartidorId());
        response.setProductoId(stock.getProductoId());
        response.setCargado(stock.getCargado());
        response.setReservado(stock.getReservado());
        response.setVendido(stock.getVendido());
        response.setEntregado(stock.getEntregado());
        response.setDevuelto(stock.getDevuelto());
        response.setDisponible(stock.getDisponible());
        response.setUltimaCargaEn(stock.getUltimaCargaEn());
        return response;
    }
}
