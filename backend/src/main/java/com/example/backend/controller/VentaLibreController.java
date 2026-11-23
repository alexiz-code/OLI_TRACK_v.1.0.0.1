package com.example.backend.controller;

import com.example.backend.dto.VentaLibreDTO;
import com.example.backend.service.VentaLibreService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/ventas-libres")
public class VentaLibreController {
    private final VentaLibreService ventaLibreService;

    public VentaLibreController(VentaLibreService ventaLibreService) {
        this.ventaLibreService = ventaLibreService;
    }

    @PostMapping
    @PreAuthorize("hasRole('REPARTIDOR')")
    public Mono<ResponseEntity<VentaLibreDTO.Response>> crear(
            @Valid @RequestBody VentaLibreDTO.CrearRequest request) {
        return ventaLibreService.crear(request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @GetMapping("/mis-ventas")
    @PreAuthorize("hasRole('REPARTIDOR')")
    public Flux<VentaLibreDTO.Response> misVentas() {
        return ventaLibreService.misVentas();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Flux<VentaLibreDTO.Response> listar() {
        return ventaLibreService.listarTodas();
    }
}
