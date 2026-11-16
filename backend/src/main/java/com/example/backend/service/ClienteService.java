package com.example.backend.service;

import com.example.backend.dto.ClienteDTO;
import com.example.backend.config.FirebaseProperties;
import com.example.backend.exception.ApiException;
import com.example.backend.exception.OperacionInvalidaException;
import com.example.backend.exception.RecursoNoEncontradoException;
import com.example.backend.mapper.EntityMapper;
import com.example.backend.model.Cliente;
import com.example.backend.model.EstadoPedido;
import com.example.backend.model.Pedido;
import com.example.backend.model.Repartidor;
import com.example.backend.model.Rol;
import com.example.backend.repository.ClienteRepository;
import com.example.backend.repository.PedidoRepository;
import com.example.backend.repository.RepartidorRepository;
import com.example.backend.repository.UsuarioRepository;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

/**
 * Perfil de negocio del cliente (dirección/ubicación del local incluidas).
 * Se crea después de que el usuario ya tiene su cuenta (Parte de Usuario/Auth).
 */
@Service
public class ClienteService {

    private static final Logger log = LoggerFactory.getLogger(ClienteService.class);
    private static final int FOTO_MAX_BYTES = 5 * 1024 * 1024;
    private static final List<String> TIPOS_FOTO_PERMITIDOS =
            List.of("image/jpeg", "image/png", "image/webp");

    private final ClienteRepository clienteRepository;
    private final UsuarioRepository usuarioRepository;
    private final PedidoRepository pedidoRepository;
    private final RepartidorRepository repartidorRepository;
    private final EntityMapper mapper;
    private final Storage storage;
    private final String storageBucketName;

    public ClienteService(ClienteRepository clienteRepository, UsuarioRepository usuarioRepository,
                          PedidoRepository pedidoRepository, RepartidorRepository repartidorRepository,
                          EntityMapper mapper,
                          Storage storage, FirebaseProperties firebaseProperties) {
        this.clienteRepository = clienteRepository;
        this.usuarioRepository = usuarioRepository;
        this.pedidoRepository = pedidoRepository;
        this.repartidorRepository = repartidorRepository;
        this.mapper = mapper;
        this.storage = storage;
        this.storageBucketName = firebaseProperties.getStorageBucket();
    }

    public Mono<ClienteDTO.Response> crear(String usuarioAutenticadoId, ClienteDTO.CrearRequest request) {
        if (!usuarioAutenticadoId.equals(request.getUsuarioId())) {
            return Mono.error(new ApiException(
                    HttpStatus.FORBIDDEN,
                    "El perfil solo se puede crear para el usuario autenticado"));
        }
        return usuarioRepository.findById(request.getUsuarioId())
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Usuario", request.getUsuarioId())))
                .flatMap(usuario -> {
                    if (usuario.getRol() != Rol.CLIENTE) {
                        return Mono.error(new OperacionInvalidaException(
                                "El usuario indicado no tiene rol CLIENTE"));
                    }
                    return clienteRepository.findByUsuarioId(usuario.getId());
                })
                .flatMap(existente -> Mono.<ClienteDTO.Response>error(
                        new OperacionInvalidaException("Este usuario ya tiene un perfil de cliente")))
                .switchIfEmpty(Mono.defer(() ->
                        clienteRepository.save(mapper.toModel(request)).map(mapper::toDTO)));
    }

    public Mono<ClienteDTO.Response> crearConFotoNegocio(
            String usuarioAutenticadoId,
            ClienteDTO.CrearRequest request,
            byte[] contenido,
            String contentType) {
        String tipoDetectado = detectarTipoImagen(contenido);
        if (contenido == null || contenido.length == 0 || contenido.length > FOTO_MAX_BYTES) {
            return Mono.error(new OperacionInvalidaException(
                    "La foto debe tener contenido y no superar 5 MB"));
        }
        if (tipoDetectado == null || !TIPOS_FOTO_PERMITIDOS.contains(contentType)
                || !tipoDetectado.equals(contentType)) {
            return Mono.error(new OperacionInvalidaException(
                    "La foto debe ser un archivo JPG, PNG o WEBP válido"));
        }

        return crear(usuarioAutenticadoId, request)
                .flatMap(cliente -> {
                    String ruta = "fotos-negocios/" + cliente.getId() + "/"
                            + UUID.randomUUID() + extensionImagen(tipoDetectado);
                    return guardarFotoNegocio(ruta, contenido, tipoDetectado)
                            .then(clienteRepository.findById(cliente.getId())
                                    .switchIfEmpty(Mono.error(
                                            RecursoNoEncontradoException.de("Cliente", cliente.getId())))
                                    .flatMap(perfil -> {
                                        perfil.setFotoNegocioStoragePath(ruta);
                                        return clienteRepository.save(perfil)
                                                .onErrorResume(error ->
                                                        eliminarFotoNegocio(ruta).then(Mono.error(error)));
                                    })
                                    .map(mapper::toDTO))
                            .onErrorResume(error -> clienteRepository.deleteById(cliente.getId())
                                    .then(Mono.error(error)));
                });
    }

    public Mono<ImagenNegocio> obtenerFotoNegocio(
            String clienteId,
            String usuarioAutenticadoId,
            Rol rol) {
        return clienteRepository.findById(clienteId)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Cliente", clienteId)))
                .flatMap(cliente -> {
                    return puedeVerFotoNegocio(cliente, usuarioAutenticadoId, rol)
                            .flatMap(puedeVer -> {
                                if (!puedeVer) {
                                    return Mono.error(new ApiException(
                                            HttpStatus.FORBIDDEN,
                                            "No tienes permiso para ver la foto de este negocio"));
                                }
                                if (!StringUtils.hasText(cliente.getFotoNegocioStoragePath())) {
                                    return Mono.error(
                                            RecursoNoEncontradoException.de("Foto del negocio", clienteId));
                                }
                                return Mono.fromCallable(() -> {
                                            Blob blob = storage.get(
                                                    BlobId.of(storageBucketName,
                                                            cliente.getFotoNegocioStoragePath()));
                                            if (blob == null) {
                                                throw RecursoNoEncontradoException.de(
                                                        "Foto del negocio", clienteId);
                                            }
                                            byte[] bytes = storage.readAllBytes(blob.getBlobId());
                                            String contentType = StringUtils.hasText(blob.getContentType())
                                                    ? blob.getContentType()
                                                    : detectarTipoImagen(bytes);
                                            if (!TIPOS_FOTO_PERMITIDOS.contains(contentType)) {
                                                throw new IllegalStateException(
                                                        "La foto guardada no tiene un tipo válido");
                                            }
                                            return new ImagenNegocio(bytes, contentType);
                                        })
                                        .subscribeOn(Schedulers.boundedElastic());
                            });
                });
    }

    private Mono<Boolean> puedeVerFotoNegocio(Cliente cliente, String usuarioId, Rol rol) {
        if (rol == Rol.ADMINISTRADOR || rol == Rol.SUPERVISOR) {
            return Mono.just(true);
        }
        if (rol == Rol.CLIENTE) {
            return Mono.just(usuarioId.equals(cliente.getUsuarioId()));
        }
        if (rol == Rol.REPARTIDOR) {
            return repartidorRepository.findByUsuarioId(usuarioId)
                    .filter(Repartidor::isActivo)
                    .flatMapMany(repartidor -> pedidoRepository.findByRepartidorId(repartidor.getId()))
                    .any(pedido -> cliente.getId().equals(pedido.getClienteId())
                            && pedido.getEstado() != EstadoPedido.CANCELADO);
        }
        return Mono.just(false);
    }

    private Mono<Void> guardarFotoNegocio(String ruta, byte[] contenido, String contentType) {
        return Mono.fromCallable(() -> {
            BlobInfo info = BlobInfo.newBuilder(storageBucketName, ruta)
                    .setContentType(contentType)
                    .build();
            Blob blob = storage.create(info, contenido);
            if (blob == null) {
                throw new IllegalStateException("No se pudo guardar la foto del negocio");
            }
            return true;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Mono<Void> eliminarFotoNegocio(String ruta) {
        return Mono.fromCallable(() -> storage.delete(storageBucketName, ruta))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> log.error(
                        "No se pudo limpiar la foto del negocio en Storage: {}", ruta, error))
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

    private static String extensionImagen(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> ".webp";
        };
    }

    public record ImagenNegocio(byte[] contenido, String contentType) {
    }

    /** HU-057: el cliente guarda más de una dirección de entrega. */
    public Mono<ClienteDTO.Response> agregarDireccion(String clienteId, ClienteDTO.DireccionCrearRequest request) {
        return clienteRepository.findById(clienteId)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Cliente", clienteId)))
                .flatMap(cliente -> {
                    if (cliente.getDireccionesAdicionales() == null) {
                        cliente.setDireccionesAdicionales(new java.util.ArrayList<>());
                    }
                    cliente.getDireccionesAdicionales().add(mapper.toModel(request));
                    return clienteRepository.save(cliente);
                })
                .map(mapper::toDTO);
    }

    public Mono<ClienteDTO.Response> eliminarDireccion(String clienteId, String direccionId) {
        return clienteRepository.findById(clienteId)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Cliente", clienteId)))
                .flatMap(cliente -> {
                    if (cliente.getDireccionesAdicionales() != null) {
                        cliente.getDireccionesAdicionales().removeIf(d -> direccionId.equals(d.getId()));
                    }
                    return clienteRepository.save(cliente);
                })
                .map(mapper::toDTO);
    }

    public Mono<ClienteDTO.Response> obtenerPorId(String id) {
        return clienteRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Cliente", id)))
                .map(mapper::toDTO);
    }

    public Mono<ClienteDTO.Response> obtenerPorUsuarioId(String usuarioId) {
        return clienteRepository.findByUsuarioId(usuarioId)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Perfil de cliente para el usuario", usuarioId)))
                .map(mapper::toDTO);
    }

    public Flux<ClienteDTO.Response> listar() {
        return pedidoRepository.findAll()
                .filter(pedido -> pedido.getClienteId() != null && pedido.getCreadoEn() != null)
                .collect(java.util.stream.Collectors.toMap(
                        Pedido::getClienteId,
                        Pedido::getCreadoEn,
                        (anterior, reciente) -> anterior.after(reciente) ? anterior : reciente))
                .flatMapMany(ultimosPedidos -> clienteRepository.findAll().map(cliente -> {
                    ClienteDTO.Response response = mapper.toDTO(cliente);
                    response.setUltimoPedidoEn(ultimosPedidos.get(cliente.getId()));
                    return response;
                }));
    }

    public Mono<ClienteDTO.Response> actualizar(String id, ClienteDTO.ActualizarRequest request) {
        return clienteRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Cliente", id)))
                .flatMap(cliente -> {
                    cliente.setNombreNegocio(request.getNombreNegocio());
                    cliente.setDireccion(request.getDireccion());
                    if (StringUtils.hasText(request.getReferencia())) {
                        cliente.setReferencia(request.getReferencia());
                    }
                    if (request.getUbicacion() != null) {
                        cliente.setUbicacion(mapper.toModel(request.getUbicacion()));
                    }
                    return clienteRepository.save(cliente);
                })
                .map(mapper::toDTO);
    }

    public Mono<Void> desactivar(String id) {
        return clienteRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Cliente", id)))
                .flatMap(cliente -> {
                    cliente.setActivo(false);
                    return clienteRepository.save(cliente);
                })
                .then();
    }

    public Mono<Void> reactivar(String id) {
        return clienteRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Cliente", id)))
                .flatMap(cliente -> {
                    cliente.setActivo(true);
                    return clienteRepository.save(cliente);
                })
                .then();
    }
}
