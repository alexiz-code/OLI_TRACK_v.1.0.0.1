package com.example.backend.service;

import com.example.backend.dto.VehiculoDTO;
import com.example.backend.exception.RecursoNoEncontradoException;
import com.example.backend.model.InventarioVehiculo;
import com.example.backend.model.Vehiculo;
import com.example.backend.repository.InventarioVehiculoRepository;
import com.example.backend.repository.VehiculoRepository;
import com.example.backend.security.SecurityContextUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Date;
import java.util.UUID;

@Service
public class VehiculoService {
    private final VehiculoRepository vehiculoRepository;
    private final InventarioVehiculoRepository inventarioRepository;

    public VehiculoService(VehiculoRepository vehiculoRepository,
                           InventarioVehiculoRepository inventarioRepository) {
        this.vehiculoRepository = vehiculoRepository;
        this.inventarioRepository = inventarioRepository;
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
        return vehiculoRepository.findAll().flatMap(this::mapVehiculoCompleto);
    }

    public Mono<VehiculoDTO.Response> obtener(String id) {
        return vehiculoRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Vehículo", id)))
                .flatMap(this::mapVehiculoCompleto);
    }

    public Flux<InventarioVehiculo> listarStock(String vehiculoId) {
        return inventarioRepository.listarPorVehiculo(vehiculoId);
    }

    public Mono<InventarioVehiculo> cargar(String vehiculoId, VehiculoDTO.CargaRequest request) {
        return vehiculoRepository.findById(vehiculoId)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Vehículo", vehiculoId)))
                .flatMap(vehiculo -> SecurityContextUtils.usuarioActual()
                        .flatMap(actual -> inventarioRepository.cargarDesdePlanta(
                                "carga-" + UUID.randomUUID(),
                                vehiculoId,
                                vehiculo.getRepartidorId(),
                                request.getProductoId(),
                                request.getCantidad(),
                                request.getMotivo(),
                                actual.getUid()
                        )
                        .onErrorMap(error -> error instanceof IllegalStateException
                                        || error instanceof IllegalArgumentException,
                                error -> new com.example.backend.exception.OperacionInvalidaException(
                                        error.getMessage()
                                ))));
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
        return response;
    }
}
