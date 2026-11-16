package com.example.backend.controller;

import com.example.backend.dto.ClienteDTO;
import com.example.backend.exception.ApiException;
import com.example.backend.security.SecurityContextUtils;
import com.example.backend.service.ClienteService;
import jakarta.validation.Valid;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/clientes")
public class ClienteController {

    private static final int FOTO_MAX_BYTES = 5 * 1024 * 1024;

    private final ClienteService clienteService;

    public ClienteController(ClienteService clienteService) {
        this.clienteService = clienteService;
    }

    /** Completa el perfil de negocio justo después del registro (POST /api/auth/registro). */
    @PostMapping
    @PreAuthorize("hasRole('CLIENTE')")
    public Mono<ResponseEntity<ClienteDTO.Response>> crear(@Valid @RequestBody ClienteDTO.CrearRequest request) {
        return SecurityContextUtils.usuarioActual()
                .flatMap(actual -> clienteService.crear(actual.getUid(), request))
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('CLIENTE')")
    public Mono<ResponseEntity<ClienteDTO.Response>> crearConFotoNegocio(
            @Valid @RequestPart("datos") ClienteDTO.CrearRequest request,
            @RequestPart("foto") FilePart foto) {
        if (foto.headers().getContentLength() > FOTO_MAX_BYTES) {
            return Mono.error(new ApiException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "La foto no puede superar 5 MB"));
        }

        return DataBufferUtils.join(foto.content(), FOTO_MAX_BYTES + 1)
                .onErrorMap(
                        DataBufferLimitException.class,
                        error -> new ApiException(
                                HttpStatus.PAYLOAD_TOO_LARGE,
                                "La foto no puede superar 5 MB"))
                .map(buffer -> {
                    try {
                        if (buffer.readableByteCount() > FOTO_MAX_BYTES) {
                            throw new ApiException(
                                    HttpStatus.PAYLOAD_TOO_LARGE,
                                    "La foto no puede superar 5 MB");
                        }
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .flatMap(bytes -> SecurityContextUtils.usuarioActual()
                        .flatMap(actual -> clienteService.crearConFotoNegocio(
                                actual.getUid(),
                                request,
                                bytes,
                                foto.headers().getContentType() != null
                                        ? foto.headers().getContentType().toString()
                                        : "application/octet-stream"))
                        .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto)));
    }

    @GetMapping("/{id}/foto-negocio")
    @PreAuthorize("hasAnyRole('CLIENTE', 'ADMINISTRADOR', 'SUPERVISOR', 'REPARTIDOR')")
    public Mono<ResponseEntity<byte[]>> obtenerFotoNegocio(@PathVariable String id) {
        return SecurityContextUtils.usuarioActual()
                .flatMap(actual -> clienteService.obtenerFotoNegocio(
                        id, actual.getUid(), actual.getRol()))
                .map(foto -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(foto.contentType()))
                        .cacheControl(CacheControl.noStore())
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.inline()
                                        .filename("foto-negocio", StandardCharsets.UTF_8)
                                        .build()
                                        .toString())
                        .header("X-Content-Type-Options", "nosniff")
                        .body(foto.contenido()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR', 'REPARTIDOR')")
    public Flux<ClienteDTO.Response> listar() {
        return clienteService.listar();
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('CLIENTE')")
    public Mono<ClienteDTO.Response> obtenerPerfilPropio() {
        return SecurityContextUtils.usuarioActual()
                .flatMap(actual -> clienteService.obtenerPorUsuarioId(actual.getUid()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<ClienteDTO.Response> obtenerPorId(@PathVariable String id) {
        return clienteService.obtenerPorId(id);
    }

    @PutMapping("/me")
    @PreAuthorize("hasRole('CLIENTE')")
    public Mono<ClienteDTO.Response> actualizarPerfilPropio(@Valid @RequestBody ClienteDTO.ActualizarRequest request) {
        return SecurityContextUtils.usuarioActual()
                .flatMap(actual -> clienteService.obtenerPorUsuarioId(actual.getUid()))
                .flatMap(perfil -> clienteService.actualizar(perfil.getId(), request));
    }

    /** HU-057: el cliente agrega otra dirección de entrega. */
    @PostMapping("/me/direcciones")
    @PreAuthorize("hasRole('CLIENTE')")
    public Mono<ClienteDTO.Response> agregarDireccion(@Valid @RequestBody ClienteDTO.DireccionCrearRequest request) {
        return SecurityContextUtils.usuarioActual()
                .flatMap(actual -> clienteService.obtenerPorUsuarioId(actual.getUid()))
                .flatMap(cliente -> clienteService.agregarDireccion(cliente.getId(), request));
    }

    @DeleteMapping("/me/direcciones/{direccionId}")
    @PreAuthorize("hasRole('CLIENTE')")
    public Mono<ClienteDTO.Response> eliminarDireccion(@PathVariable String direccionId) {
        return SecurityContextUtils.usuarioActual()
                .flatMap(actual -> clienteService.obtenerPorUsuarioId(actual.getUid()))
                .flatMap(cliente -> clienteService.eliminarDireccion(cliente.getId(), direccionId));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Mono<ResponseEntity<Void>> desactivar(@PathVariable String id) {
        return clienteService.desactivar(id).thenReturn(ResponseEntity.noContent().build());
    }

    @org.springframework.web.bind.annotation.PatchMapping("/{id}/reactivar")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Mono<ResponseEntity<Void>> reactivar(@PathVariable String id) {
        return clienteService.reactivar(id).thenReturn(ResponseEntity.noContent().build());
    }
}
