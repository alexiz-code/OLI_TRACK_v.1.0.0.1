package com.example.backend.service;

import com.example.backend.config.FirebaseProperties;
import com.example.backend.dto.ClienteDTO;
import com.example.backend.mapper.EntityMapper;
import com.example.backend.model.Cliente;
import com.example.backend.model.EstadoPedido;
import com.example.backend.model.Pedido;
import com.example.backend.model.Repartidor;
import com.example.backend.model.Rol;
import com.example.backend.model.Usuario;
import com.example.backend.repository.ClienteRepository;
import com.example.backend.repository.PedidoRepository;
import com.example.backend.repository.RepartidorRepository;
import com.example.backend.repository.UsuarioRepository;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClienteFotoNegocioTest {

    @Mock
    ClienteRepository clienteRepository;

    @Mock
    UsuarioRepository usuarioRepository;

    @Mock
    PedidoRepository pedidoRepository;

    @Mock
    RepartidorRepository repartidorRepository;

    EntityMapper mapper;

    @Mock
    Storage storage;

    @Mock
    FirebaseProperties firebaseProperties;

    ClienteService clienteService;

    @BeforeEach
    void setUp() {
        when(firebaseProperties.getStorageBucket()).thenReturn("bucket");
        mapper = new EntityMapper();
        clienteService = new ClienteService(
                clienteRepository,
                usuarioRepository,
                pedidoRepository,
                repartidorRepository,
                mapper,
                storage,
                firebaseProperties);
    }

    @Test
    void rechazaFotoMayorQueCincoMegabytes() {
        byte[] foto = new byte[5 * 1024 * 1024 + 1];

        StepVerifier.create(clienteService.crearConFotoNegocio(
                        "usuario-1", null, foto, "image/jpeg"))
                .expectErrorMessage("La foto debe tener contenido y no superar 5 MB")
                .verify();

        verifyNoInteractions(clienteRepository, usuarioRepository, storage);
    }

    @Test
    void rechazaContenidoQueNoCoincideConElMimeDeclarado() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

        StepVerifier.create(clienteService.crearConFotoNegocio(
                        "usuario-1", null, jpeg, "image/png"))
                .expectErrorMessage("La foto debe ser un archivo JPG, PNG o WEBP válido")
                .verify();

        verifyNoInteractions(clienteRepository, usuarioRepository, storage);
    }

    @Test
    void guardaLaFotoEnStorageYAgregaLaRutaAlPerfil() {
        Usuario usuario = new Usuario();
        usuario.setId("usuario-1");
        usuario.setRol(Rol.CLIENTE);
        ClienteDTO.CrearRequest request = new ClienteDTO.CrearRequest(
                "usuario-1",
                "Negocio de prueba",
                "Dirección de prueba",
                null,
                null);
        Cliente perfil = mapper.toModel(request);
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

        when(usuarioRepository.findById("usuario-1")).thenReturn(reactor.core.publisher.Mono.just(usuario));
        when(clienteRepository.findByUsuarioId("usuario-1")).thenReturn(reactor.core.publisher.Mono.empty());
        when(clienteRepository.save(any(Cliente.class))).thenAnswer(invocacion -> {
            Cliente guardado = invocacion.getArgument(0);
            guardado.setId("cliente-1");
            return reactor.core.publisher.Mono.just(guardado);
        });
        when(clienteRepository.findById("cliente-1"))
                .thenAnswer(invocacion -> reactor.core.publisher.Mono.just(perfil));
        when(storage.create(any(), eq(jpeg))).thenReturn(mock(com.google.cloud.storage.Blob.class));

        StepVerifier.create(clienteService.crearConFotoNegocio(
                        "usuario-1", request, jpeg, "image/jpeg"))
                .expectNextMatches(response ->
                        "/api/clientes/cliente-1/foto-negocio".equals(response.getFotoNegocioUrl()))
                .verifyComplete();

        org.junit.jupiter.api.Assertions.assertTrue(
                perfil.getFotoNegocioStoragePath().startsWith("fotos-negocios/cliente-1/"));
        verify(storage).create(any(), eq(jpeg));
        verify(clienteRepository, times(2)).save(any(Cliente.class));
    }

    @Test
    void permiteAlRepartidorAsignadoVerLaFotoDelClienteDeSuPedido() {
        Cliente cliente = clienteConFoto();
        Repartidor repartidor = new Repartidor();
        repartidor.setId("repartidor-1");
        Pedido pedido = new Pedido();
        pedido.setClienteId("cliente-1");
        pedido.setEstado(EstadoPedido.CONFIRMADO);
        Blob blob = mock(Blob.class);
        BlobId blobId = BlobId.of("bucket", cliente.getFotoNegocioStoragePath());
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

        when(clienteRepository.findById("cliente-1")).thenReturn(reactor.core.publisher.Mono.just(cliente));
        when(repartidorRepository.findByUsuarioId("usuario-repartidor"))
                .thenReturn(reactor.core.publisher.Mono.just(repartidor));
        when(pedidoRepository.findByRepartidorId("repartidor-1"))
                .thenReturn(reactor.core.publisher.Flux.just(pedido));
        when(storage.get(blobId)).thenReturn(blob);
        when(blob.getBlobId()).thenReturn(blobId);
        when(blob.getContentType()).thenReturn("image/jpeg");
        when(storage.readAllBytes(blobId)).thenReturn(jpeg);

        StepVerifier.create(clienteService.obtenerFotoNegocio(
                        "cliente-1", "usuario-repartidor", Rol.REPARTIDOR))
                .assertNext(foto -> {
                    assertArrayEquals(jpeg, foto.contenido());
                    org.junit.jupiter.api.Assertions.assertEquals("image/jpeg", foto.contentType());
                })
                .verifyComplete();
    }

    @Test
    void niegaAlRepartidorLaFotoDeUnClienteSinPedidoAsignado() {
        Cliente cliente = clienteConFoto();
        Repartidor repartidor = new Repartidor();
        repartidor.setId("repartidor-1");
        Pedido pedido = new Pedido();
        pedido.setClienteId("otro-cliente");

        when(clienteRepository.findById("cliente-1")).thenReturn(reactor.core.publisher.Mono.just(cliente));
        when(repartidorRepository.findByUsuarioId("usuario-repartidor"))
                .thenReturn(reactor.core.publisher.Mono.just(repartidor));
        when(pedidoRepository.findByRepartidorId("repartidor-1"))
                .thenReturn(reactor.core.publisher.Flux.just(pedido));

        StepVerifier.create(clienteService.obtenerFotoNegocio(
                        "cliente-1", "usuario-repartidor", Rol.REPARTIDOR))
                .expectErrorMessage("No tienes permiso para ver la foto de este negocio")
                .verify();

        verifyNoInteractions(storage);
    }

    private Cliente clienteConFoto() {
        Cliente cliente = new Cliente();
        cliente.setId("cliente-1");
        cliente.setFotoNegocioStoragePath("fotos-negocios/cliente-1/foto.jpg");
        return cliente;
    }
}
