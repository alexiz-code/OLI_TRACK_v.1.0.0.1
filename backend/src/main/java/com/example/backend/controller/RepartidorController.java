package com.example.backend.controller;

import com.example.backend.dto.RepartidorDTO;
import com.example.backend.dto.UbicacionDTO;
import com.example.backend.security.SecurityContextUtils;
import com.example.backend.service.RepartidorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/repartidores")
public class RepartidorController {

    private final RepartidorService repartidorService;
    public RepartidorController(RepartidorService repartidorService) {
        this.repartidorService = repartidorService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Mono<ResponseEntity<RepartidorDTO.Response>> crear(@Valid @RequestBody RepartidorDTO.CrearRequest request) {
        return repartidorService.crear(request)
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Flux<RepartidorDTO.Response> listar() {
        return repartidorService.listar();
    }

    /** Ruta pública para roles autenticados: ver quién está libre para asignar un pedido. */
    @GetMapping("/disponibles")
    public Flux<RepartidorDTO.Response> listarDisponibles() {
        return repartidorService.listarDisponibles();
    }

    @GetMapping("/asignables")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Flux<RepartidorDTO.Response> listarAsignablesManualmente() {
        return repartidorService.listarAsignablesManualmente();
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('REPARTIDOR')")
    public Mono<RepartidorDTO.Response> obtenerPerfilPropio() {
        return SecurityContextUtils.usuarioActual()
                .flatMap(actual -> repartidorService.obtenerPorUsuarioId(actual.getUid()));
    }

    @PostMapping("/me/iniciar-sesion")
    @PreAuthorize("hasRole('REPARTIDOR')")
    public Mono<RepartidorDTO.Response> iniciarSesionPropia() {
        return SecurityContextUtils.usuarioActual()
                .flatMap(actual -> repartidorService.prepararInicioSesion(actual.getUid()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<RepartidorDTO.Response> obtenerPorId(@PathVariable String id) {
        return repartidorService.obtenerPorId(id);
    }

    @PutMapping("/me")
    @PreAuthorize("hasRole('REPARTIDOR')")
    public Mono<RepartidorDTO.Response> actualizarPerfilPropio(@Valid @RequestBody RepartidorDTO.ActualizarRequest request) {
        return SecurityContextUtils.usuarioActual()
                .flatMap(actual -> repartidorService.obtenerPorUsuarioId(actual.getUid()))
                .flatMap(perfil -> repartidorService.actualizar(perfil.getId(), request));
    }

    /** El propio repartidor marca si está disponible, en ruta o desconectado. */
    @PatchMapping("/me/estado")
    @PreAuthorize("hasRole('REPARTIDOR')")
    public Mono<RepartidorDTO.Response> cambiarEstadoPropio(@Valid @RequestBody RepartidorDTO.EstadoRequest request) {
        return SecurityContextUtils.usuarioActual()
                .flatMap(actual -> repartidorService.obtenerPorUsuarioId(actual.getUid()))
                .flatMap(perfil -> repartidorService.cambiarEstado(perfil.getId(), request.getEstado()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Mono<ResponseEntity<Void>> desactivar(@PathVariable String id) {
        return repartidorService.desactivar(id).thenReturn(ResponseEntity.noContent().build());
    }

    /** El repartidor reporta periódicamente su posición mientras hace una entrega. */
    @PutMapping("/{id}/ubicacion")
    @PreAuthorize("hasRole('REPARTIDOR')")
    public Mono<RepartidorDTO.Response> actualizarUbicacion(@PathVariable String id,
                                                              @Valid @RequestBody UbicacionDTO ubicacion) {
        return SecurityContextUtils.usuarioActual()
                .flatMap(actual -> repartidorService.actualizarUbicacion(id, actual.getUid(), ubicacion));
    }

    /** Consultado por Angular para pintar al repartidor en el mapa y calcular ETA. */
    @GetMapping("/{id}/ubicacion")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR', 'CLIENTE', 'REPARTIDOR')")
    public Mono<UbicacionDTO> obtenerUbicacion(@PathVariable String id) {
        return SecurityContextUtils.usuarioActual()
                .flatMap(actual -> repartidorService.obtenerUbicacion(id, actual.getUid(), actual.getRol()));
    }

    @org.springframework.web.bind.annotation.PatchMapping("/{id}/reactivar")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Mono<ResponseEntity<Void>> reactivar(@PathVariable String id) {
        return repartidorService.reactivar(id).thenReturn(ResponseEntity.noContent().build());
    }
}
