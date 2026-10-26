package com.example.backend.service;

import com.example.backend.config.FirebaseProperties;
import com.example.backend.dto.ProductoDTO;
import com.example.backend.exception.OperacionInvalidaException;
import com.example.backend.exception.RecursoNoEncontradoException;
import com.example.backend.mapper.EntityMapper;
import com.example.backend.model.Inventario;
import com.example.backend.model.Producto;
import com.example.backend.repository.InventarioRepository;
import com.example.backend.repository.ProductoRepository;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductoService {

    private static final Logger log = LoggerFactory.getLogger(ProductoService.class);
    private static final int IMAGEN_MAX_BYTES = 5 * 1024 * 1024;
    private static final List<String> TIPOS_IMAGEN_PERMITIDOS =
            List.of("image/jpeg", "image/png", "image/webp");

    private final ProductoRepository productoRepository;
    private final InventarioRepository inventarioRepository;
    private final AuditService auditService;
    private final EntityMapper mapper;
    private final Storage storage;
    private final String storageBucketName;

    public ProductoService(ProductoRepository productoRepository, InventarioRepository inventarioRepository,
                            AuditService auditService, EntityMapper mapper,
                            Storage storage, FirebaseProperties firebaseProperties) {
        this.productoRepository = productoRepository;
        this.inventarioRepository = inventarioRepository;
        this.auditService = auditService;
        this.mapper = mapper;
        this.storage = storage;
        this.storageBucketName = firebaseProperties.getStorageBucket();
    }

    public Mono<ProductoDTO.Response> crear(ProductoDTO.CrearRequest request) {
        return productoRepository.save(mapper.toModel(request))
                .flatMap(producto -> {
                    Inventario inventario = new Inventario();
                    inventario.setProductoId(producto.getId());
                    inventario.setStockActual(request.getStockInicial() == null ? 0 : request.getStockInicial());
                    inventario.setStockMinimo(0);
                    inventario.setStockMaximo(0);
                    return inventarioRepository.save(inventario)
                            .thenReturn(producto)
                            .onErrorResume(error -> productoRepository.deleteById(producto.getId())
                                    .onErrorResume(limpiezaError -> Mono.empty())
                                    .then(Mono.error(error)));
                })
                .flatMap(p -> auditService.registrarActual("PRODUCTO_CREADO", "producto", p.getId(), p.getNombre()).thenReturn(p))
                .flatMap(this::conStock);
    }

    public Mono<ProductoDTO.Response> obtenerPorId(String id) {
        return productoRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Producto", id)))
                .filter(Producto::isActivo)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Producto", id)))
                .flatMap(this::conStock);
    }

    /** Catálogo completo (uso administrativo). */
    public Flux<ProductoDTO.Response> listar() {
        return conStock(productoRepository.findAll());
    }

    /** Catálogo visible para clientes: solo productos activos, con su disponibilidad (HU-060). */
    public Flux<ProductoDTO.Response> listarActivos() {
        return conStock(productoRepository.findByActivoTrue());
    }

    public Flux<ProductoDTO.Response> listarPorCategoria(String categoria) {
        return conStock(productoRepository.findByActivoTrue()
                .filter(producto -> categoria.equals(producto.getCategoria())));
    }

    private Mono<ProductoDTO.Response> conStock(Producto producto) {
        return inventarioRepository.findByProductoId(producto.getId())
                .map(Inventario::getStockActual)
                .defaultIfEmpty(0)
                .map(stock -> {
                    ProductoDTO.Response dto = mapper.toDTO(producto);
                    dto.setStockDisponible(stock);
                    return dto;
                });
    }

    /** Une cada producto con su stock actual del inventario. */
    private Flux<ProductoDTO.Response> conStock(Flux<Producto> productos) {
        return inventarioRepository.findAll()
                .collectMap(Inventario::getProductoId, Inventario::getStockActual)
                .flatMapMany((Map<String, Integer> stock) -> productos.map(p -> {
                    ProductoDTO.Response dto = mapper.toDTO(p);
                    dto.setStockDisponible(stock.getOrDefault(p.getId(), 0));
                    return dto;
                }));
    }

    public Mono<ProductoDTO.Response> actualizar(String id, ProductoDTO.ActualizarRequest request) {
        return productoRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Producto", id)))
                .flatMap(producto -> {
                    String imagenStorageAnterior = producto.getImagenStoragePath();
                    producto.setNombre(request.getNombre());
                    producto.setDescripcion(request.getDescripcion());
                    producto.setCategoria(request.getCategoria());
                    producto.setPrecioUnitario(request.getPrecioUnitario());
                    producto.setUnidadMedida(request.getUnidadMedida());
                    if (request.getImagenUrl() != null
                            && !request.getImagenUrl().equals(producto.getImagenUrl())) {
                        producto.setImagenUrl(request.getImagenUrl());
                        producto.setImagenStoragePath(null);
                    }
                    return productoRepository.save(producto)
                            .flatMap(guardado -> eliminarImagenAnteriorSiCambio(
                                    imagenStorageAnterior,
                                    guardado.getImagenStoragePath())
                                    .thenReturn(guardado));
                })
                .flatMap(p -> auditService.registrarActual("PRODUCTO_ACTUALIZADO", "producto", p.getId(),
                        p.getNombre() + " · precio " + p.getPrecioUnitario()).thenReturn(p))
                .flatMap(this::conStock);
    }

    public Mono<ProductoDTO.Response> subirImagen(
            String id,
            byte[] contenido,
            String contentType,
            String nombreArchivo) {
        String tipoDetectado = detectarTipoImagen(contenido);
        if (contenido == null || contenido.length == 0
                || contenido.length > IMAGEN_MAX_BYTES) {
            return Mono.error(new OperacionInvalidaException(
                    "La imagen debe tener contenido y no superar 5 MB"));
        }
        if (tipoDetectado == null
                || !StringUtils.hasText(contentType)
                || !TIPOS_IMAGEN_PERMITIDOS.contains(contentType)
                || !tipoDetectado.equals(contentType)) {
            return Mono.error(new OperacionInvalidaException(
                    "La imagen debe ser un JPG, PNG o WEBP válido"));
        }

        return productoRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Producto", id)))
                .flatMap(producto -> {
                    String nombreSeguro = nombreSeguro(nombreArchivo, tipoDetectado);
                    String ruta = "imagenes-productos/" + id + "/"
                            + UUID.randomUUID() + "-" + nombreSeguro;
                    return guardarImagen(ruta, contenido, tipoDetectado)
                            .then(Mono.defer(() -> {
                                String rutaAnterior = producto.getImagenStoragePath();
                                producto.setImagenStoragePath(ruta);
                                producto.setImagenUrl(
                                        "/api/productos/" + id + "/imagen?v=" + UUID.randomUUID());
                                return productoRepository.save(producto)
                                        .onErrorResume(error ->
                                                eliminarImagenGuardada(ruta)
                                                        .then(Mono.error(error)))
                                        .flatMap(actualizado ->
                                                eliminarImagenAnteriorSiCambio(
                                                        rutaAnterior,
                                                        ruta)
                                                        .then(auditService.registrarActual(
                                                                "PRODUCTO_IMAGEN_ACTUALIZADA",
                                                                "producto",
                                                                id,
                                                                nombreSeguro))
                                                        .thenReturn(actualizado));
                            }));
                })
                .flatMap(this::conStock);
    }

    public Mono<ProductoDTO.Response> eliminarImagen(String id) {
        return productoRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Producto", id)))
                .flatMap(producto -> {
                    String rutaAnterior = producto.getImagenStoragePath();
                    producto.setImagenStoragePath(null);
                    producto.setImagenUrl(null);
                    return productoRepository.save(producto)
                            .flatMap(actualizado ->
                                    eliminarImagenAnteriorSiCambio(rutaAnterior, null)
                                            .then(auditService.registrarActual(
                                                    "PRODUCTO_IMAGEN_ELIMINADA",
                                                    "producto",
                                                    id,
                                                    producto.getNombre()))
                                            .thenReturn(actualizado));
                })
                .flatMap(this::conStock);
    }

    public Mono<ImagenProducto> obtenerImagen(String id) {
        return productoRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Imagen de producto", id)))
                .flatMap(producto -> {
                    if (!StringUtils.hasText(producto.getImagenStoragePath())) {
                        return Mono.error(RecursoNoEncontradoException.de("Imagen de producto", id));
                    }
                    return Mono.fromCallable(() -> {
                                Blob blob = storage.get(
                                        BlobId.of(storageBucketName, producto.getImagenStoragePath()));
                                if (blob == null) {
                                    throw RecursoNoEncontradoException.de("Imagen de producto", id);
                                }
                                byte[] bytes = storage.readAllBytes(blob.getBlobId());
                                String contentType = StringUtils.hasText(blob.getContentType())
                                        ? blob.getContentType()
                                        : detectarTipoImagen(bytes);
                                if (!StringUtils.hasText(contentType)) {
                                    throw new IllegalStateException(
                                            "La imagen guardada no tiene un tipo válido");
                                }
                                return new ImagenProducto(bytes, contentType);
                            })
                            .subscribeOn(Schedulers.boundedElastic());
                });
    }

    private Mono<Void> guardarImagen(String ruta, byte[] contenido, String contentType) {
        return Mono.fromCallable(() -> {
            BlobInfo info = BlobInfo.newBuilder(storageBucketName, ruta)
                    .setContentType(contentType)
                    .build();
            Blob blob = storage.create(info, contenido);
            if (blob == null) {
                throw new IllegalStateException("No se pudo guardar la imagen del producto");
            }
            return true;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Mono<Void> eliminarImagenAnteriorSiCambio(String rutaAnterior, String rutaActual) {
        if (!StringUtils.hasText(rutaAnterior) || rutaAnterior.equals(rutaActual)) {
            return Mono.empty();
        }
        return eliminarImagenGuardada(rutaAnterior);
    }

    private Mono<Void> eliminarImagenGuardada(String ruta) {
        return Mono.fromCallable(() -> storage.delete(storageBucketName, ruta))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> log.warn(
                        "No se pudo eliminar imagen de producto antigua en Storage: {}",
                        ruta,
                        error))
                .onErrorResume(error -> Mono.empty())
                .then();
    }

    private static String detectarTipoImagen(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47
                && bytes[4] == 0x0D
                && bytes[5] == 0x0A
                && bytes[6] == 0x1A
                && bytes[7] == 0x0A) {
            return "image/png";
        }
        if (bytes.length >= 12
                && bytes[0] == 0x52
                && bytes[1] == 0x49
                && bytes[2] == 0x46
                && bytes[3] == 0x46
                && bytes[8] == 0x57
                && bytes[9] == 0x45
                && bytes[10] == 0x42
                && bytes[11] == 0x50) {
            return "image/webp";
        }
        return null;
    }

    private static String nombreSeguro(String nombreArchivo, String contentType) {
        String nombre = StringUtils.hasText(nombreArchivo)
                ? nombreArchivo.replace('\\', '/').replaceAll("^.*/", "")
                        .replaceAll("[^A-Za-z0-9._-]", "_")
                : "producto";
        if (nombre.length() > 100) {
            nombre = nombre.substring(nombre.length() - 100);
        }
        if (!nombre.contains(".")) {
            nombre += switch (contentType) {
                case "image/jpeg" -> ".jpg";
                case "image/png" -> ".png";
                default -> ".webp";
            };
        }
        return nombre;
    }

    public record ImagenProducto(byte[] contenido, String contentType) {
    }

    /** Baja lógica: el producto deja de aparecer en el catálogo pero no se borra. */
    public Mono<Void> desactivar(String id) {
        return cambiarActivo(id, false, "PRODUCTO_DESACTIVADO");
    }

    public Mono<Void> reactivar(String id) {
        return cambiarActivo(id, true, "PRODUCTO_REACTIVADO");
    }

    private Mono<Void> cambiarActivo(String id, boolean activo, String accion) {
        return productoRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Producto", id)))
                .flatMap(producto -> {
                    producto.setActivo(activo);
                    return productoRepository.save(producto);
                })
                .flatMap(p -> auditService.registrarActual(accion, "producto", p.getId(), p.getNombre()))
                .then();
    }
}
