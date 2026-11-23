package com.example.backend.service;

import com.example.backend.dto.VehiculoDTO;
import com.example.backend.exception.RecursoNoEncontradoException;
import com.example.backend.model.InventarioVehiculo;
import com.example.backend.model.Rol;
import com.example.backend.model.Vehiculo;
import com.example.backend.repository.InventarioVehiculoRepository;
import com.example.backend.repository.RepartidorRepository;
import com.example.backend.repository.VehiculoRepository;
import com.example.backend.security.SecurityContextUtils;
import com.example.backend.security.UsuarioAutenticado;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Date;
import java.util.UUID;

@Service
public class VehiculoService {
    private final VehiculoRepository vehiculoRepository;
    private final InventarioVehiculoRepository inventarioRepository;
    private final RepartidorRepository repartidorRepository;

    public VehiculoService(VehiculoRepository vehiculoRepository,
                           InventarioVehiculoRepository inventarioRepository,
                           RepartidorRepository repartidorRepository) {
        this.vehiculoRepository = vehiculoRepository;
        this.inventarioRepository = inventarioRepository;
        this.repartidorRepository = repartidorRepository;
    }

    public Mono<VehiculoDTO.Response> crear(VehiculoDTO.CrearRequest request) {
        Vehiculo vehiculo = new Vehiculo();
        vehiculo.setPlaca(request.getPlaca().trim().toUpperCase());
        vehiculo.setDescripcion(request.getDescripcion());
        vehiculo.setRepartidorId(request.getRepartidorId());
        vehiculo.setActivo(true);
        vehiculo.setActualizadoEn(new Date());
        return vehiculoRepository.save(vehiculo).map(this::mapVehiculo);
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
