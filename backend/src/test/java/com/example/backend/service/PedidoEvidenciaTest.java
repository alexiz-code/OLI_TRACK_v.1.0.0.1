package com.example.backend.service;

import com.example.backend.config.FirebaseProperties;
import com.example.backend.exception.ApiException;
import com.example.backend.exception.OperacionInvalidaException;
import com.example.backend.model.Cliente;
import com.example.backend.model.Pedido;
import com.example.backend.model.Rol;
import com.example.backend.mapper.EntityMapper;
import com.example.backend.repository.ClienteRepository;
import com.example.backend.repository.DetallePedidoRepository;
import com.example.backend.repository.PedidoRepository;
import com.example.backend.repository.ProductoRepository;
import com.example.backend.repository.RepartidorRepository;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PedidoEvidenciaTest {

    @Mock
    PedidoRepository pedidoRepository;

    @Mock
    DetallePedidoRepository detallePedidoRepository;

    @Mock
    ProductoRepository productoRepository;

    @Mock
    RepartidorRepository repartidorRepository;

    @Mock
    RepartidorService repartidorService;

    @Mock
    ClienteRepository clienteRepository;

    @Mock
    InventarioService inventarioService;

    @Mock
    NotificacionService notificacionService;

    @Mock
    ConfiguracionService configuracionService;

    @Mock
    IncidenciaService incidenciaService;

    @Mock
    Storage storage;

    @Mock
    FirebaseProperties firebaseProperties;

    @Mock
    EntityMapper mapper;

    PedidoService pedidoService;

    @BeforeEach
    void setUp() {
        when(firebaseProperties.getStorageBucket()).thenReturn("bucket");
        pedidoService = new PedidoService(
                pedidoRepository,
                detallePedidoRepository,
                productoRepository,
                repartidorRepository,
                repartidorService,
                clienteRepository,
                inventarioService,
                notificacionService,
                configuracionService,
                incidenciaService,
                storage,
                firebaseProperties,
                mapper);
    }

    @Test
    void rechazaComprobanteQueSuperaDosMegabytes() {
        byte[] archivo = new byte[2 * 1024 * 1024 + 1];

        StepVerifier.create(pedidoService.guardarEvidencia(
                        "pedido-1",
                        "uid-cliente",
                        archivo,
                        "image/jpeg",
                        "pago.jpg"))
                .expectError(OperacionInvalidaException.class)
                .verify();

        verifyNoInteractions(pedidoRepository, storage);
    }

    @Test
    void rechazaTipoMimeQueNoCoincideConElContenido() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

        StepVerifier.create(pedidoService.guardarEvidencia(
                        "pedido-1",
                        "uid-cliente",
                        jpeg,
                        "image/png",
                        "pago.png"))
                .expectError(OperacionInvalidaException.class)
                .verify();

        verifyNoInteractions(pedidoRepository, storage);
    }

    @Test
    void clienteNoPuedeDescargarComprobanteDeOtroCliente() {
        Pedido pedido = new Pedido();
        pedido.setId("pedido-1");
        pedido.setClienteId("cliente-1");
        pedido.setEvidenciaStoragePath("evidencias/pedido-1/pago.jpg");

        Cliente cliente = new Cliente();
        cliente.setUsuarioId("uid-propietario");

        when(pedidoRepository.findById("pedido-1")).thenReturn(Mono.just(pedido));
        when(clienteRepository.findById("cliente-1")).thenReturn(Mono.just(cliente));

        StepVerifier.create(pedidoService.descargarEvidencia(
                        "pedido-1",
                        "uid-otro",
                        Rol.CLIENTE))
                .expectErrorSatisfies(error -> {
                    ApiException apiError = (ApiException) error;
                    assertEquals(HttpStatus.FORBIDDEN, apiError.getStatus());
                })
                .verify();

        verifyNoInteractions(storage);
    }

    @Test
    void supervisorPuedeSolicitarComprobanteProtegido() {
        Pedido pedido = new Pedido();
        pedido.setId("pedido-1");
        pedido.setEvidenciaStoragePath("evidencias/pedido-1/pago.jpg");

        when(pedidoRepository.findById("pedido-1")).thenReturn(Mono.just(pedido));
        when(storage.get(any(BlobId.class))).thenReturn(null);

        StepVerifier.create(pedidoService.descargarEvidencia(
                        "pedido-1",
                        "uid-supervisor",
                        Rol.SUPERVISOR))
                .expectError()
                .verify();

        verify(storage).get(any(BlobId.class));
    }
}
