package com.example.backend.controller;

import com.example.backend.dto.CompararRepartidoresDTO;
import com.example.backend.dto.RutaDTO;
import com.example.backend.dto.UbicacionDTO;
import com.example.backend.exception.ApiException;
import com.example.backend.exception.RecursoNoEncontradoException;
import com.example.backend.model.EstadoPedido;
import com.example.backend.model.Pedido;
import com.example.backend.model.Repartidor;
import com.example.backend.model.Rol;
import com.example.backend.repository.ClienteRepository;
import com.example.backend.repository.PedidoRepository;
import com.example.backend.repository.RepartidorRepository;
import com.example.backend.security.SecurityContextUtils;
import com.example.backend.service.OsrmService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/rutas")
public class RutaController {

    private final OsrmService osrmService;
    private final PedidoRepository pedidoRepository;
    private final ClienteRepository clienteRepository;
    private final RepartidorRepository repartidorRepository;

    public RutaController(
            OsrmService osrmService,
            PedidoRepository pedidoRepository,
            ClienteRepository clienteRepository,
            RepartidorRepository repartidorRepository
    ) {
        this.osrmService = osrmService;
        this.pedidoRepository = pedidoRepository;
        this.clienteRepository = clienteRepository;
        this.repartidorRepository = repartidorRepository;
    }

    @GetMapping("/calcular")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<RutaDTO> calcular(
            @RequestParam("latitudOrigen") Double latitudOrigen,
            @RequestParam("longitudOrigen") Double longitudOrigen,
            @RequestParam("latitudDestino") Double latitudDestino,
            @RequestParam("longitudDestino") Double longitudDestino
    ) {
        return osrmService.calcularRuta(
                new UbicacionDTO(latitudOrigen, longitudOrigen),
                new UbicacionDTO(latitudDestino, longitudDestino)
        );
    }

    @GetMapping("/pedidos/{pedidoId}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR', 'CLIENTE', 'REPARTIDOR')")
    public Mono<RutaDTO> calcularRutaPedido(@PathVariable String pedidoId) {
        return SecurityContextUtils.usuarioActual()
                .flatMap(usuario -> pedidoRepository.findById(pedidoId)
                        .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Pedido", pedidoId)))
                        .flatMap(pedido -> puedeVerRuta(usuario.getUid(), usuario.getRol(), pedido)
                                .flatMap(puedeVer -> {
                                    if (!puedeVer) {
                                        return Mono.error(new ApiException(
                                                HttpStatus.FORBIDDEN,
                                                "No tienes permiso para consultar la ruta de este pedido."
                                        ));
                                    }
                                    if (pedido.getEstado() == EstadoPedido.ENTREGADO
                                            || pedido.getEstado() == EstadoPedido.CANCELADO) {
                                        return Mono.error(new ApiException(
                                                HttpStatus.BAD_REQUEST,
                                                "El pedido ya no tiene una ruta de entrega activa."
                                        ));
                                    }
                                    if (pedido.getRepartidorId() == null
                                            || pedido.getRepartidorId().isBlank()) {
                                        return Mono.error(new ApiException(
                                                HttpStatus.NOT_FOUND,
                                                "El pedido todavía no tiene un repartidor asignado."
                                        ));
                                    }
                                    return repartidorRepository.findById(pedido.getRepartidorId())
                                            .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de(
                                                    "Repartidor", pedido.getRepartidorId())))
                                            .flatMap(repartidor -> calcularRutaAsignada(pedido, repartidor));
                                })));
    }

    private Mono<Boolean> puedeVerRuta(String usuarioId, Rol rol, Pedido pedido) {
        if (rol == Rol.ADMINISTRADOR || rol == Rol.SUPERVISOR) {
            return Mono.just(true);
        }
        if (rol == Rol.CLIENTE) {
            return clienteRepository.findByUsuarioId(usuarioId)
                    .map(cliente -> cliente.getId().equals(pedido.getClienteId()))
                    .defaultIfEmpty(false);
        }
        if (rol == Rol.REPARTIDOR) {
            return repartidorRepository.findByUsuarioId(usuarioId)
                    .map(repartidor -> repartidor.getId().equals(pedido.getRepartidorId()))
                    .defaultIfEmpty(false);
        }
        return Mono.just(false);
    }

    private Mono<RutaDTO> calcularRutaAsignada(Pedido pedido, Repartidor repartidor) {
        if (!repartidor.isActivo()) {
            return Mono.error(new ApiException(
                    HttpStatus.NOT_FOUND,
                    "El repartidor asignado no está activo."
            ));
        }
        if (repartidor.getUbicacionActual() == null || pedido.getUbicacionEntrega() == null) {
            return Mono.error(new ApiException(
                    HttpStatus.NOT_FOUND,
                    "Falta la ubicación GPS del repartidor o la ubicación de entrega."
            ));
        }
        UbicacionDTO origen = new UbicacionDTO(
                repartidor.getUbicacionActual().getLatitud(),
                repartidor.getUbicacionActual().getLongitud()
        );
        UbicacionDTO destino = new UbicacionDTO(
                pedido.getUbicacionEntrega().getLatitud(),
                pedido.getUbicacionEntrega().getLongitud()
        );
        return osrmService.calcularRuta(origen, destino)
                .map(ruta -> new RutaDTO(
                        ruta.estado(),
                        ruta.distanciaKm(),
                        ruta.duracionMinutos(),
                        ruta.geometria(),
                        repartidor.getId()
                ));
    }

    @PostMapping("/comparar-repartidores")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public Mono<CompararRepartidoresDTO> compararRepartidores(
            @Valid @RequestBody CompararRepartidoresDTO.Solicitud request
    ) {
        return osrmService.compararRepartidores(
                new UbicacionDTO(request.latitudDestino(), request.longitudDestino())
        );
    }
}
