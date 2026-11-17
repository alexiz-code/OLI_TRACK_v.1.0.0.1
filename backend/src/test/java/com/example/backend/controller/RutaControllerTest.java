package com.example.backend.controller;

import com.example.backend.dto.RutaDTO;
import com.example.backend.exception.ApiException;
import com.example.backend.model.EstadoPedido;
import com.example.backend.model.Pedido;
import com.example.backend.model.Repartidor;
import com.example.backend.model.Rol;
import com.example.backend.model.Ubicacion;
import com.example.backend.repository.ClienteRepository;
import com.example.backend.repository.PedidoRepository;
import com.example.backend.repository.RepartidorRepository;
import com.example.backend.security.UsuarioAutenticado;
import com.example.backend.service.OsrmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RutaControllerTest {

    private final OsrmService osrmService = mock(OsrmService.class);
    private final PedidoRepository pedidoRepository = mock(PedidoRepository.class);
    private final ClienteRepository clienteRepository = mock(ClienteRepository.class);
    private final RepartidorRepository repartidorRepository = mock(RepartidorRepository.class);

    private RutaController controller;

    @BeforeEach
    void setUp() {
        controller = new RutaController(
                osrmService, pedidoRepository, clienteRepository, repartidorRepository
        );
    }

    @Test
    void permiteAlRepartidorConsultarLaRutaDeSuPedidoAsignado() {
        Pedido pedido = pedidoAsignado("repartidor-1");
        Repartidor repartidor = repartidor("repartidor-1", "usuario-repartidor");
        RutaDTO ruta = new RutaDTO(
                "OK",
                2.5,
                8.0,
                List.of(new RutaDTO.CoordenadaDTO(-13.16, -74.22),
                        new RutaDTO.CoordenadaDTO(-13.15, -74.21)),
                null
        );
        when(pedidoRepository.findById("pedido-1")).thenReturn(Mono.just(pedido));
        when(repartidorRepository.findByUsuarioId("usuario-repartidor"))
                .thenReturn(Mono.just(repartidor));
        when(repartidorRepository.findById("repartidor-1")).thenReturn(Mono.just(repartidor));
        when(osrmService.calcularRuta(any(), any())).thenReturn(Mono.just(ruta));

        StepVerifier.create(controller.calcularRutaPedido("pedido-1")
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                                authentication("usuario-repartidor", Rol.REPARTIDOR))))
                .assertNext(result -> {
                    assertEquals("OK", result.estado());
                    assertEquals("repartidor-1", result.repartidorId());
                })
                .verifyComplete();

        verify(osrmService).calcularRuta(
                new com.example.backend.dto.UbicacionDTO(-13.16, -74.22),
                new com.example.backend.dto.UbicacionDTO(-13.15, -74.21)
        );
    }

    @Test
    void deniegaAlRepartidorLaRutaDeUnPedidoDeOtraAsignacion() {
        when(pedidoRepository.findById("pedido-1"))
                .thenReturn(Mono.just(pedidoAsignado("repartidor-otro")));
        when(repartidorRepository.findByUsuarioId("usuario-repartidor"))
                .thenReturn(Mono.just(repartidor("repartidor-1", "usuario-repartidor")));

        StepVerifier.create(controller.calcularRutaPedido("pedido-1")
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                                authentication("usuario-repartidor", Rol.REPARTIDOR))))
                .expectErrorMatches(error -> error instanceof ApiException exception
                        && exception.getStatus() == HttpStatus.FORBIDDEN)
                .verify();

        verify(osrmService, never()).calcularRuta(any(), any());
    }

    @Test
    void permiteAlClienteConsultarSoloLaRutaDeSuPedido() {
        Pedido pedido = pedidoAsignado("repartidor-1");
        when(pedidoRepository.findById("pedido-1")).thenReturn(Mono.just(pedido));
        when(clienteRepository.findByUsuarioId("usuario-cliente"))
                .thenReturn(Mono.just(cliente("cliente-1", "usuario-cliente")));
        when(repartidorRepository.findById("repartidor-1"))
                .thenReturn(Mono.just(repartidor("repartidor-1", "usuario-repartidor")));
        when(osrmService.calcularRuta(any(), any())).thenReturn(Mono.just(
                new RutaDTO("OK", 2.0, 5.0, List.of(), "repartidor-1")
        ));

        StepVerifier.create(controller.calcularRutaPedido("pedido-1")
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                                authentication("usuario-cliente", Rol.CLIENTE))))
                .expectNextCount(1)
                .verifyComplete();
    }

    private static Pedido pedidoAsignado(String repartidorId) {
        Pedido pedido = new Pedido();
        pedido.setId("pedido-1");
        pedido.setClienteId("cliente-1");
        pedido.setRepartidorId(repartidorId);
        pedido.setEstado(EstadoPedido.EN_CAMINO);
        pedido.setUbicacionEntrega(new Ubicacion(-13.15, -74.21, null));
        return pedido;
    }

    private static Repartidor repartidor(String id, String usuarioId) {
        Repartidor repartidor = new Repartidor();
        repartidor.setId(id);
        repartidor.setUsuarioId(usuarioId);
        repartidor.setActivo(true);
        repartidor.setUbicacionActual(new Ubicacion(-13.16, -74.22, null));
        return repartidor;
    }

    private static com.example.backend.model.Cliente cliente(String id, String usuarioId) {
        com.example.backend.model.Cliente cliente = new com.example.backend.model.Cliente();
        cliente.setId(id);
        cliente.setUsuarioId(usuarioId);
        return cliente;
    }

    private static UsernamePasswordAuthenticationToken authentication(String uid, Rol rol) {
        return new UsernamePasswordAuthenticationToken(
                new UsuarioAutenticado(uid, uid + "@example.test", rol),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + rol.name()))
        );
    }
}
