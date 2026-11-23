package com.example.backend.controller;

import com.example.backend.dto.VehiculoDTO;
import com.example.backend.model.InventarioVehiculo;
import com.example.backend.service.VehiculoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/vehiculos")
public class VehiculoController {
    private final VehiculoService vehiculoService;

    public VehiculoController(VehiculoService vehiculoService) {
        this.vehiculoService = vehiculoService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<ResponseEntity<VehiculoDTO.Response>> crear(
            @Valid @RequestBody VehiculoDTO.CrearRequest request) {
        return vehiculoService.crear(request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR', 'REPARTIDOR')")
    public Flux<VehiculoDTO.Response> listar() {
        return vehiculoService.listar();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR', 'REPARTIDOR')")
    public Mono<VehiculoDTO.Response> obtener(@PathVariable String id) {
        return vehiculoService.obtener(id);
    }

    @GetMapping("/{id}/stock")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR', 'REPARTIDOR')")
    public Flux<InventarioVehiculo> stock(@PathVariable String id) {
        return vehiculoService.listarStock(id);
    }

    @PostMapping("/{id}/stock/carga")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<ResponseEntity<InventarioVehiculo>> cargar(
            @PathVariable String id,
            @Valid @RequestBody VehiculoDTO.CargaRequest request) {
        return vehiculoService.cargar(id, request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }
}
