package com.example.backend.service;

import com.example.backend.config.FirebaseProperties;
import com.example.backend.mapper.EntityMapper;
import com.example.backend.repository.InventarioRepository;
import com.example.backend.repository.ProductoRepository;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductoImagenTest {

    @Mock
    ProductoRepository productoRepository;

    @Mock
    InventarioRepository inventarioRepository;

    @Mock
    AuditService auditService;

    @Mock
    EntityMapper mapper;

    @Mock
    Storage storage;

    @Mock
    FirebaseProperties firebaseProperties;

    ProductoService productoService;

    @BeforeEach
    void setUp() {
        when(firebaseProperties.getStorageBucket()).thenReturn("bucket");
        productoService = new ProductoService(
                productoRepository,
                inventarioRepository,
                auditService,
                mapper,
                storage,
                firebaseProperties);
    }

    @Test
    void rechazaImagenMayorQueCincoMegabytes() {
        byte[] imagen = new byte[5 * 1024 * 1024 + 1];

        StepVerifier.create(productoService.subirImagen(
                        "producto-1",
                        imagen,
                        "image/jpeg",
                        "producto.jpg"))
                .expectErrorMessage("La imagen debe tener contenido y no superar 5 MB")
                .verify();

        verifyNoInteractions(productoRepository, storage);
    }

    @Test
    void rechazaContenidoQueNoCoincideConElMimeDeclarado() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

        StepVerifier.create(productoService.subirImagen(
                        "producto-1",
                        jpeg,
                        "image/png",
                        "producto.png"))
                .expectErrorMessage("La imagen debe ser un JPG, PNG o WEBP válido")
                .verify();

        verifyNoInteractions(productoRepository, storage);
    }
}
