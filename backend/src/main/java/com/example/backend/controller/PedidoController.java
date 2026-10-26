
package com.example.backend.controller;

import com.example.backend.dto.PedidoDTO;
import com.example.backend.exception.ApiException;
import com.example.backend.model.EstadoPedido;
import com.example.backend.security.SecurityContextUtils;
import com.example.backend.security.UsuarioAutenticado;
import com.example.backend.service.ClienteService;
import com.example.backend.service.PedidoService;
import com.example.backend.service.RepartidorService;

import jakarta.validation.Valid;

import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/pedidos")
public class PedidoController {

    private static final int EVIDENCIA_MAX_BYTES = 2 * 1024 * 1024;

    private final PedidoService pedidoService;
    private final ClienteService clienteService;
    private final RepartidorService repartidorService;

    public PedidoController(
            PedidoService pedidoService,
            ClienteService clienteService,
            RepartidorService repartidorService) {

        this.pedidoService = pedidoService;
        this.clienteService = clienteService;
        this.repartidorService = repartidorService;
    }

    // ============================================================
    // CREACIÓN Y CONSULTA DE PEDIDOS
    // ============================================================

    /**
     * Crea un pedido para el cliente autenticado.
     * El clienteId se obtiene del perfil vinculado al usuario autenticado.
     */
    @PostMapping
    @PreAuthorize("hasRole('CLIENTE')")
    public Mono<ResponseEntity<PedidoDTO.Response>> crear(
            @Valid @RequestBody PedidoDTO.CrearRequest request) {

        return SecurityContextUtils.usuarioActual()
                .flatMap(actual ->
                        clienteService.obtenerPorUsuarioId(actual.getUid()))
                .flatMap(cliente -> cliente.isActivo()
                        ? pedidoService.crear(cliente.getId(), request)
                        : Mono.error(new com.example.backend.exception.OperacionInvalidaException(
                                "El perfil del cliente está desactivado")))
                .map(dto ->
                        ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    /**
     * Lista los pedidos con filtros opcionales.
     * Disponible para administradores y supervisores.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Flux<PedidoDTO.Response> listar(
            @RequestParam(required = false) EstadoPedido estado,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate desde,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate hasta) {

        return pedidoService.listarFiltrado(estado, desde, hasta);
    }

    /**
     * HU-048 / HU-049: consulta de pedidos retrasados
     * y pedidos pendientes de asignación.
     */
    @GetMapping("/alertas")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<PedidoDTO.AlertasResponse> alertas() {
        return pedidoService.alertas();
    }

    /**
     * Lista los pedidos del cliente autenticado.
     */
    @GetMapping("/mis-pedidos")
    @PreAuthorize("hasRole('CLIENTE')")
    public Flux<PedidoDTO.Response> misPedidos() {

        return SecurityContextUtils.usuarioActual()
                .flatMap(actual ->
                        clienteService.obtenerPorUsuarioId(actual.getUid()))
                .flatMapMany(cliente ->
                        pedidoService.listarPorCliente(cliente.getId()));
    }

    /**
     * Lista las entregas del repartidor autenticado.
     */
    @GetMapping("/mis-entregas")
    @PreAuthorize("hasRole('REPARTIDOR')")
    public Flux<PedidoDTO.Response> misEntregas() {

        return SecurityContextUtils.usuarioActual()
                .flatMap(actual ->
                        repartidorService.obtenerPorUsuarioId(actual.getUid()))
                .flatMapMany(repartidor ->
                        pedidoService.listarPorRepartidor(repartidor.getId()));
    }

    /**
     * Obtiene un pedido aplicando las verificaciones de acceso del servicio.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR', 'CLIENTE', 'REPARTIDOR')")
    public Mono<PedidoDTO.Response> obtenerPorId(
            @PathVariable String id) {

        return SecurityContextUtils.usuarioActual()
                .flatMap(actual ->
                        pedidoService.obtenerPorId(
                                id,
                                actual.getUid(),
                                actual.getRol()));
    }

    // ============================================================
    // EDICIÓN Y CANCELACIÓN
    // ============================================================

    /**
     * Permite al cliente editar los datos autorizados de su pedido.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('CLIENTE')")
    public Mono<PedidoDTO.Response> editar(
            @PathVariable String id,
            @Valid @RequestBody PedidoDTO.EditarRequest request) {

        return SecurityContextUtils.usuarioActual()
                .flatMap(actual ->
                        pedidoService.editar(id, actual.getUid(), request));
    }

    /**
     * HU-074: el cliente cancela su propio pedido.
     */
    @PatchMapping("/{id}/cancelar")
    @PreAuthorize("hasRole('CLIENTE')")
    public Mono<PedidoDTO.Response> cancelar(
            @PathVariable String id) {

        return SecurityContextUtils.usuarioActual()
                .flatMap(actual ->
                        pedidoService.cancelarPorCliente(id, actual.getUid()));
    }

    // ============================================================
    // ASIGNACIÓN Y REASIGNACIÓN DE REPARTIDORES
    // ============================================================

    @PatchMapping("/{id}/repartidor")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<PedidoDTO.Response> asignarRepartidor(
            @PathVariable String id,
            @Valid @RequestBody PedidoDTO.AsignarRepartidorRequest request) {

        return pedidoService.asignarRepartidor(
                id,
                request.getRepartidorId());
    }

    @PatchMapping("/{id}/repartidor/auto")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<PedidoDTO.Response> asignarRepartidorAutomatico(
            @PathVariable String id) {

        return pedidoService.asignarRepartidorAutomatico(id);
    }

    /**
     * HU-033: reasignación de un repartidor.
     */
    @PatchMapping("/{id}/repartidor/reasignar")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<PedidoDTO.Response> reasignar(
            @PathVariable String id,
            @Valid @RequestBody PedidoDTO.ReasignarRequest request) {

        return pedidoService.reasignar(
                id,
                request.getRepartidorId(),
                request.getComentario());
    }

    // ============================================================
    // VALIDACIÓN, RECHAZO Y CAMBIO DE ESTADO
    // ============================================================

    /**
     * HU-029: confirma un pedido.
     */
    @PatchMapping("/{id}/validar")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<PedidoDTO.Response> validar(
            @PathVariable String id) {

        return pedidoService.validar(id);
    }

    /**
     * HU-030: rechaza un pedido.
     */
    @PatchMapping("/{id}/rechazar")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<PedidoDTO.Response> rechazar(
            @PathVariable String id,
            @Valid @RequestBody PedidoDTO.RechazarRequest request) {

        return pedidoService.rechazar(
                id,
                request.getObservaciones());
    }

    /**
     * Actualiza el estado respetando las reglas de autorización del servicio.
     */
    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR', 'REPARTIDOR')")
    public Mono<PedidoDTO.Response> cambiarEstado(
            @PathVariable String id,
            @Valid @RequestBody PedidoDTO.CambiarEstadoRequest request) {

        return SecurityContextUtils.usuarioActual()
                .flatMap(actual ->
                        pedidoService.cambiarEstadoComo(
                                id,
                                request.getEstado(),
                                request.getComentario(),
                                actual.getUid(),
                                actual.getRol()));
    }

    // ============================================================
    // ACCIONES DEL REPARTIDOR
    // ============================================================

    @PatchMapping("/{id}/aceptar")
    @PreAuthorize("hasRole('REPARTIDOR')")
    public Mono<PedidoDTO.Response> aceptarEntrega(
            @PathVariable String id) {

        return SecurityContextUtils.usuarioActual()
                .flatMap(actual ->
                        pedidoService.aceptarEntrega(
                                id,
                                actual.getUid()));
    }

    @PatchMapping("/{id}/llegada")
    @PreAuthorize("hasRole('REPARTIDOR')")
    public Mono<PedidoDTO.Response> registrarLlegada(
            @PathVariable String id) {

        return SecurityContextUtils.usuarioActual()
                .flatMap(actual ->
                        pedidoService.registrarLlegada(
                                id,
                                actual.getUid()));
    }

    @PatchMapping("/{id}/entrega-fallida")
    @PreAuthorize("hasRole('REPARTIDOR')")
    public Mono<PedidoDTO.Response> entregaFallida(
            @PathVariable String id,
            @Valid @RequestBody PedidoDTO.EntregaFallidaRequest request) {

        return SecurityContextUtils.usuarioActual()
                .flatMap((UsuarioAutenticado actual) ->
                        pedidoService.registrarEntregaFallida(
                                id,
                                actual.getUid(),
                                request));
    }

    // ============================================================
    // CALIFICACIÓN Y EVIDENCIA DE ENTREGA
    // ============================================================

    /**
     * Permite al cliente calificar un pedido entregado.
     */
    @PostMapping("/{id}/calificacion")
    @PreAuthorize("hasRole('CLIENTE')")
    public Mono<PedidoDTO.Response> calificar(
            @PathVariable String id,
            @Valid @RequestBody PedidoDTO.CalificarRequest request) {

        return SecurityContextUtils.usuarioActual()
                .flatMap(actual ->
                        pedidoService.calificar(
                                id,
                                actual.getUid(),
                                request));
    }

    /**
     * Sube el comprobante de pago asociado a un pedido.
     */
    @PostMapping(
            value = "/{id}/evidencia",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('CLIENTE')")
    public Mono<PedidoDTO.Response> subirEvidencia(
            @PathVariable String id,
            @RequestPart("archivo") FilePart archivo) {

        return SecurityContextUtils.usuarioActual()
                .flatMap(actual -> {
                    if (archivo.headers().getContentLength() > EVIDENCIA_MAX_BYTES) {
                        return Mono.error(new ApiException(
                                HttpStatus.PAYLOAD_TOO_LARGE,
                                "El comprobante no puede superar 2 MB"));
                    }

                    return DataBufferUtils.join(
                                    archivo.content(),
                                    EVIDENCIA_MAX_BYTES + 1)
                        .onErrorMap(
                                DataBufferLimitException.class,
                                error -> new ApiException(
                                        HttpStatus.PAYLOAD_TOO_LARGE,
                                        "El comprobante no puede superar 2 MB"))
                                .map(buffer -> {
                                    try {
                                        if (buffer.readableByteCount() > EVIDENCIA_MAX_BYTES) {
                                            throw new ApiException(
                                                    HttpStatus.PAYLOAD_TOO_LARGE,
                                                    "El comprobante no puede superar 2 MB");
                                        }
                                        byte[] bytes =
                                                new byte[buffer.readableByteCount()];

                                        buffer.read(bytes);
                                        return bytes;
                                    } finally {
                                        DataBufferUtils.release(buffer);
                                    }
                                })
                                .flatMap(bytes -> {
                                    String contentType =
                                            archivo.headers().getContentType() != null
                                                    ? archivo.headers()
                                                    .getContentType()
                                                    .toString()
                                                    : "application/octet-stream";

                                    return pedidoService.guardarEvidencia(
                                            id,
                                            actual.getUid(),
                                            bytes,
                                            contentType,
                                            archivo.filename());
                                });
                });
    }

    @GetMapping("/{id}/evidencia/archivo")
    @PreAuthorize("hasAnyRole('CLIENTE', 'ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<ResponseEntity<byte[]>> descargarEvidencia(
            @PathVariable String id) {

        return SecurityContextUtils.usuarioActual()
                .flatMap(actual ->
                        pedidoService.descargarEvidencia(
                                        id,
                                        actual.getUid(),
                                        actual.getRol())
                                .map(archivo -> ResponseEntity.ok()
                                        .contentType(MediaType.parseMediaType(
                                                archivo.contentType()))
                                        .cacheControl(CacheControl.noStore())
                                        .header(
                                                HttpHeaders.CONTENT_DISPOSITION,
                                                ContentDisposition.inline()
                                                        .filename(
                                                                archivo.nombreArchivo(),
                                                                StandardCharsets.UTF_8)
                                                        .build()
                                                        .toString())
                                        .header("X-Content-Type-Options", "nosniff")
                                        .body(archivo.contenido())));
    }
}
