package com.example.backend.controller;

import com.example.backend.dto.ProductoDTO;
import com.example.backend.exception.ApiException;
import com.example.backend.service.ProductoService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Controlador de productos.
 *
 * GET es público para permitir que Angular muestre el catálogo.
 *
 * POST, PUT, DELETE y PATCH requieren ADMINISTRADOR.
 */
@RestController
@RequestMapping("/api/productos")
public class ProductoController {

    private static final int IMAGEN_MAX_BYTES = 5 * 1024 * 1024;

    private final ProductoService productoService;

    public ProductoController(ProductoService productoService) {
        this.productoService = productoService;
    }

    /**
     * Crear producto.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Mono<ResponseEntity<ProductoDTO.Response>> crear(
            @Valid @RequestBody ProductoDTO.CrearRequest request) {

        return productoService
                .crear(request)

                .map(
                        dto ->
                                ResponseEntity
                                        .status(HttpStatus.CREATED)
                                        .body(dto)
                );
    }

    /**
     * Listar productos activos.
     *
     * Endpoint público.
     */
    @GetMapping
    public Flux<ProductoDTO.Response> listar(
            @RequestParam(required = false)
            String categoria) {

        if (categoria != null && !categoria.isBlank()) {

            return productoService
                    .listarPorCategoria(categoria);
        }

        return productoService.listarActivos();
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Flux<ProductoDTO.Response> listarTodosAdmin() {
        return productoService.listar();
    }
    /**
     * Obtener producto por ID.
     */
    @GetMapping("/{id}")
    public Mono<ProductoDTO.Response> obtenerPorId(
            @PathVariable String id) {

        return productoService.obtenerPorId(id);
    }

    /**
     * Actualizar producto.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Mono<ProductoDTO.Response> actualizar(
            @PathVariable String id,
            @Valid @RequestBody ProductoDTO.ActualizarRequest request) {

        return productoService.actualizar(
                id,
                request
        );
    }

    @PostMapping(
            value = "/{id}/imagen",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Mono<ProductoDTO.Response> subirImagen(
            @PathVariable String id,
            @RequestPart("archivo") FilePart archivo) {
        if (archivo.headers().getContentLength() > IMAGEN_MAX_BYTES) {
            return Mono.error(new ApiException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "La imagen no puede superar 5 MB"));
        }

        return DataBufferUtils.join(archivo.content(), IMAGEN_MAX_BYTES + 1)
                .onErrorMap(
                        DataBufferLimitException.class,
                        error -> new ApiException(
                                HttpStatus.PAYLOAD_TOO_LARGE,
                                "La imagen no puede superar 5 MB"))
                .map(buffer -> {
                    try {
                        if (buffer.readableByteCount() > IMAGEN_MAX_BYTES) {
                            throw new ApiException(
                                    HttpStatus.PAYLOAD_TOO_LARGE,
                                    "La imagen no puede superar 5 MB");
                        }
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .flatMap(bytes -> productoService.subirImagen(
                        id,
                        bytes,
                        archivo.headers().getContentType() != null
                                ? archivo.headers().getContentType().toString()
                                : "application/octet-stream",
                        archivo.filename()));
    }

    @GetMapping("/{id}/imagen")
    public Mono<ResponseEntity<byte[]>> obtenerImagen(@PathVariable String id) {
        return productoService.obtenerImagen(id)
                .map(imagen -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(imagen.contentType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.inline()
                                        .filename("producto", StandardCharsets.UTF_8)
                                        .build()
                                        .toString())
                        .header("X-Content-Type-Options", "nosniff")
                        .body(imagen.contenido()));
    }

    @DeleteMapping("/{id}/imagen")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Mono<ProductoDTO.Response> eliminarImagen(@PathVariable String id) {
        return productoService.eliminarImagen(id);
    }

    /**
     * Desactivar producto.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Mono<ResponseEntity<Void>> desactivar(
            @PathVariable String id) {

        return productoService
                .desactivar(id)

                .thenReturn(
                        ResponseEntity.noContent().build()
                );
    }

    /**
     * Reactivar producto.
     */
    @PatchMapping("/{id}/reactivar")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Mono<ResponseEntity<Void>> reactivar(
            @PathVariable String id) {

        return productoService
                .reactivar(id)

                .thenReturn(
                        ResponseEntity.noContent().build()
                );
    }
}