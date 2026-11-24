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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.bind.annotation.RequestPart;
import java.time.Duration;
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
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Mono<ResponseEntity<VehiculoDTO.Response>> crear(
            @Valid @RequestBody VehiculoDTO.CrearRequest request) {
        return vehiculoService.crear(request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Mono<VehiculoDTO.Response> actualizar(@PathVariable String id, @Valid @RequestBody VehiculoDTO.CrearRequest request) {
        return vehiculoService.actualizar(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Mono<Void> desactivar(@PathVariable String id) { return vehiculoService.desactivar(id); }

    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<VehiculoDTO.Response> cambiarEstado(@PathVariable String id, @Valid @RequestBody VehiculoDTO.EstadoRequest request) {
        return vehiculoService.cambiarEstado(id, request);
    }

    @PutMapping("/{id}/repartidor")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<VehiculoDTO.Response> asignar(@PathVariable String id, @Valid @RequestBody VehiculoDTO.AsignacionRequest request) {
        return vehiculoService.asignarRepartidor(id, request);
    }

    @PostMapping("/{id}/repartidor/desasignar")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<VehiculoDTO.Response> desasignar(@PathVariable String id, @RequestBody(required = false) VehiculoDTO.MotivoRequest request) {
        return vehiculoService.desasignarRepartidor(id, request == null ? new VehiculoDTO.MotivoRequest() : request);
    }

    @GetMapping("/{id}/historial")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Flux<com.example.backend.model.HistorialVehiculo> historial(@PathVariable String id) { return vehiculoService.historial(id); }

    @PostMapping(value = "/{id}/foto", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Mono<VehiculoDTO.Response> subirFoto(@PathVariable String id, @RequestPart("archivo") FilePart archivo) {
        if (archivo.headers().getContentLength() > 5 * 1024 * 1024) return Mono.error(new org.springframework.web.server.ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "La foto no puede superar 5 MB."));
        return DataBufferUtils.join(archivo.content(), 5 * 1024 * 1024 + 1)
                .onErrorMap(DataBufferLimitException.class, e -> new org.springframework.web.server.ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "La foto no puede superar 5 MB."))
                .map(buffer -> { try { byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); return bytes; } finally { DataBufferUtils.release(buffer); } })
                .flatMap(bytes -> vehiculoService.subirFoto(id, bytes, archivo.headers().getContentType() == null ? "" : archivo.headers().getContentType().toString()));
    }

    @GetMapping(value = "/{id}/foto", produces = {"image/jpeg", "image/png", "image/webp"})
    public Mono<ResponseEntity<byte[]>> foto(@PathVariable String id) {
        return vehiculoService.obtenerFoto(id).map(image -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType())).cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .header("X-Content-Type-Options", "nosniff").body(image.contenido()));
    }

    @DeleteMapping("/{id}/foto")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Mono<Void> eliminarFoto(@PathVariable String id) { return vehiculoService.eliminarFoto(id); }

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

    @PostMapping("/{id}/stock/devolucion")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<ResponseEntity<InventarioVehiculo>> devolver(
            @PathVariable String id,
            @Valid @RequestBody VehiculoDTO.CargaRequest request) {
        return vehiculoService.devolver(id, request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }
}
