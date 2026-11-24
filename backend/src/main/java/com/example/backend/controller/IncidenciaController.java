package com.example.backend.controller;

import com.example.backend.dto.IncidenciaDTO;
import com.example.backend.security.SecurityContextUtils;
import com.example.backend.service.IncidenciaService;
import com.example.backend.service.RepartidorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/incidencias")
public class IncidenciaController {

    private final IncidenciaService incidenciaService;
    private final RepartidorService repartidorService;

    public IncidenciaController(IncidenciaService incidenciaService, RepartidorService repartidorService) {
        this.incidenciaService = incidenciaService;
        this.repartidorService = repartidorService;
    }

    /** Repartidor: registra sobre su propia entrega. Admin/Supervisor: sobre cualquier pedido (HU-043). */
    @PostMapping
    @PreAuthorize("hasAnyRole('REPARTIDOR', 'ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<ResponseEntity<IncidenciaDTO.Response>> registrar(@Valid @RequestBody IncidenciaDTO.CrearRequest request) {
        return SecurityContextUtils.usuarioActual().flatMap(actual -> {
            Mono<IncidenciaDTO.Response> resultado = "REPARTIDOR".equals(actual.getRol().name())
                    ? repartidorService.obtenerPorUsuarioId(actual.getUid())
                            .flatMap(repartidor -> incidenciaService.registrar(repartidor.getId(), request))
                    : incidenciaService.registrarComoSupervisor(request);
            return resultado.map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
        });
    }

    @PostMapping("/vehiculo")
    @PreAuthorize("hasRole('REPARTIDOR')")
    public Mono<ResponseEntity<IncidenciaDTO.Response>> reportarAveria(@Valid @RequestBody IncidenciaDTO.AveriaVehiculoRequest request) {
        return SecurityContextUtils.usuarioActual()
                .flatMap(actual -> repartidorService.obtenerPorUsuarioId(actual.getUid()))
                .flatMap(perfil -> incidenciaService.registrarAveriaVehiculo(perfil.getId(), request))
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Flux<IncidenciaDTO.Response> listar() {
        return incidenciaService.listar();
    }

    @GetMapping("/pedido/{pedidoId}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR', 'REPARTIDOR')")
    public Flux<IncidenciaDTO.Response> listarPorPedido(@PathVariable String pedidoId) {
        return SecurityContextUtils.usuarioActual()
                .flatMapMany(actual -> {
                    if (actual.getRol().name().equals("REPARTIDOR")) {
                        return repartidorService.obtenerPorUsuarioId(actual.getUid())
                                .flatMapMany(repartidor -> incidenciaService.listarPorPedido(
                                        pedidoId, repartidor.getId()));
                    }
                    return incidenciaService.listarPorPedido(pedidoId, null);
                });
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR', 'REPARTIDOR')")
    public Mono<IncidenciaDTO.Response> obtenerPorId(@PathVariable String id) {
        return SecurityContextUtils.usuarioActual()
                .flatMap(actual -> {
                    if (actual.getRol().name().equals("REPARTIDOR")) {
                        return repartidorService.obtenerPorUsuarioId(actual.getUid())
                                .flatMap(repartidor -> incidenciaService.obtenerPorId(id, repartidor.getId()));
                    }
                    return incidenciaService.obtenerPorId(id, null);
                });
    }

    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<IncidenciaDTO.Response> cambiarEstado(@PathVariable String id,
                                                        @Valid @RequestBody IncidenciaDTO.CambiarEstadoRequest request) {
        return incidenciaService.cambiarEstado(id, request.getEstado());
    }

    @PatchMapping("/{id}/mantenimiento")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<IncidenciaDTO.Response> gestionarMantenimiento(@PathVariable String id,
            @Valid @RequestBody IncidenciaDTO.GestionMantenimientoRequest request) {
        return SecurityContextUtils.usuarioActual()
                .flatMap(actual -> incidenciaService.gestionarMantenimiento(id, request, actual.getUid()));
    }
}
