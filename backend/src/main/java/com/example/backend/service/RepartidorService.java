package com.example.backend.service;

import com.example.backend.dto.RepartidorDTO;
import com.example.backend.dto.UbicacionDTO;
import com.example.backend.exception.OperacionInvalidaException;
import com.example.backend.exception.RecursoNoEncontradoException;
import com.example.backend.mapper.EntityMapper;
import com.example.backend.model.EstadoPedido;
import com.example.backend.model.EstadoRepartidor;
import com.example.backend.model.Pedido;
import com.example.backend.model.Repartidor;
import com.example.backend.model.Rol;
import com.example.backend.repository.RepartidorRepository;
import com.example.backend.repository.ClienteRepository;
import com.example.backend.repository.PedidoRepository;
import com.example.backend.repository.UsuarioRepository;
import com.example.backend.repository.VehiculoRepository;
import org.springframework.http.HttpStatus;
import com.example.backend.exception.ApiException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

@Service
public class RepartidorService {

    private final RepartidorRepository repartidorRepository;
    private final UsuarioRepository usuarioRepository;
    private final ClienteRepository clienteRepository;
    private final PedidoRepository pedidoRepository;
    private final EntityMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired
    private VehiculoRepository vehiculoRepository;

    public RepartidorService(RepartidorRepository repartidorRepository, UsuarioRepository usuarioRepository,
                             ClienteRepository clienteRepository, PedidoRepository pedidoRepository,
                             EntityMapper mapper) {
        this.repartidorRepository = repartidorRepository;
        this.usuarioRepository = usuarioRepository;
        this.clienteRepository = clienteRepository;
        this.pedidoRepository = pedidoRepository;
        this.mapper = mapper;
    }

    public Mono<RepartidorDTO.Response> crear(RepartidorDTO.CrearRequest request) {
        return usuarioRepository.findById(request.getUsuarioId())
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Usuario", request.getUsuarioId())))
                .flatMap(usuario -> {
                    if (usuario.getRol() != Rol.REPARTIDOR) {
                        return Mono.error(new OperacionInvalidaException(
                                "El usuario indicado no tiene rol REPARTIDOR"));
                    }
                    if (!usuario.isActivo()) {
                        return Mono.error(new OperacionInvalidaException(
                                "No se puede crear una moto para un usuario desactivado"));
                    }
                    return repartidorRepository.findByUsuarioId(usuario.getId());
                })
                .flatMap(existente -> Mono.<RepartidorDTO.Response>error(
                        new OperacionInvalidaException("Este usuario ya tiene un perfil de repartidor")))
                .switchIfEmpty(Mono.defer(() ->
                        repartidorRepository.save(mapper.toModel(request)).map(mapper::toDTO)));
    }

    public Mono<RepartidorDTO.Response> obtenerPorId(String id) {
        return repartidorRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Repartidor", id)))
                .map(mapper::toDTO);
    }

    public Mono<RepartidorDTO.Response> obtenerPorUsuarioId(String usuarioId) {
        return repartidorRepository.findByUsuarioId(usuarioId)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Perfil de repartidor para el usuario", usuarioId)))
                .flatMap(this::sincronizarEstado)
                .map(mapper::toDTO);
    }

    public Mono<RepartidorDTO.Response> prepararInicioSesion(String usuarioId) {
        return repartidorRepository.findByUsuarioId(usuarioId)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de(
                        "Perfil de repartidor para el usuario",
                        usuarioId
                )))
                .flatMap(repartidor -> pedidoRepository.findByRepartidorId(repartidor.getId())
                        .filter(pedido -> pedido.getEstado() != EstadoPedido.ENTREGADO
                                && pedido.getEstado() != EstadoPedido.CANCELADO)
                        .hasElements()
                        .flatMap(tienePedidoActivo -> {
                            EstadoRepartidor estadoInicial = tienePedidoActivo
                                    ? EstadoRepartidor.EN_RUTA
                                    : repartidor.isActivo()
                                            ? EstadoRepartidor.DISPONIBLE
                                            : EstadoRepartidor.DESCONECTADO;
                            if (repartidor.getEstado() == estadoInicial) {
                                return Mono.just(repartidor);
                            }
                            return repartidorRepository.actualizarEstado(
                                            repartidor.getId(),
                                            estadoInicial
                                    )
                                    .then(Mono.fromSupplier(() -> {
                                        repartidor.setEstado(estadoInicial);
                                        return repartidor;
                                    }));
                        }))
                .map(mapper::toDTO);
    }

    public Flux<RepartidorDTO.Response> listar() {
        return sincronizarEstados()
                .thenMany(repartidorRepository.findAll().flatMap(this::enriquecer));
    }

    private Mono<RepartidorDTO.Response> enriquecer(Repartidor repartidor) {
        Mono<RepartidorDTO.Response> conUsuario = usuarioRepository.findById(repartidor.getUsuarioId()).map(usuario -> {
            RepartidorDTO.Response response = mapper.toDTO(repartidor);
            response.setNombreUsuario(usuario.getNombre()); response.setEmailUsuario(usuario.getEmail());
            response.setTelefonoUsuario(usuario.getTelefono());
            return response;
        }).defaultIfEmpty(mapper.toDTO(repartidor));
        if (repartidor.getVehiculoId() == null || vehiculoRepository == null) return conUsuario;
        return conUsuario.flatMap(response -> vehiculoRepository.findById(repartidor.getVehiculoId()).map(vehiculo -> {
            response.setEstadoVehiculo(vehiculo.getEstadoOperativo()); response.setPlacaVehiculo(vehiculo.getPlaca()); return response;
        }).defaultIfEmpty(response));
    }

    /** Repartidores libres para que un administrador o cliente les asigne un pedido. */
    public Flux<RepartidorDTO.Response> listarDisponibles() {
        return sincronizarEstados()
                .thenMany(repartidorRepository.findByEstado(EstadoRepartidor.DISPONIBLE))
                .filter(Repartidor::isActivo)
                .filter(repartidor -> repartidor.getVehiculoId() != null || repartidor.getPlacaVehiculo() != null)
                .map(mapper::toDTO);
    }

    public Flux<RepartidorDTO.Response> listarAsignablesManualmente() {
        return sincronizarEstados()
                .thenMany(repartidorRepository.findAll())
                .filter(Repartidor::isActivo)
                .filter(repartidor -> repartidor.getEstado() == EstadoRepartidor.DISPONIBLE
                        || repartidor.getEstado() == EstadoRepartidor.DESCONECTADO)
                .flatMap(repartidor -> usuarioRepository.findById(repartidor.getUsuarioId())
                        .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de(
                                "Usuario del repartidor",
                                repartidor.getUsuarioId()
                        )))
                        .map(usuario -> {
                            RepartidorDTO.Response response = mapper.toDTO(repartidor);
                            response.setNombreUsuario(usuario.getNombre());
                            response.setEmailUsuario(usuario.getEmail());
                            return response;
                        }));
    }

    public Mono<Void> sincronizarEstados() {
        return Mono.zip(
                        repartidorRepository.findAll().collectList(),
                        pedidoRepository.findAll().collectList()
                )
                .flatMapMany(datos -> Flux.fromIterable(datos.getT1())
                        .concatMap(repartidor -> sincronizarEstado(repartidor, datos.getT2())))
                .then();
    }

    private Mono<Repartidor> sincronizarEstado(Repartidor repartidor) {
        return pedidoRepository.findByRepartidorId(repartidor.getId())
                .collectList()
                .flatMap(pedidos -> sincronizarEstado(repartidor, pedidos));
    }

    private Mono<Repartidor> sincronizarEstado(Repartidor repartidor, List<Pedido> pedidos) {
        boolean tienePedidoActivo = pedidos.stream()
                .anyMatch(pedido -> Objects.equals(pedido.getRepartidorId(), repartidor.getId())
                        && pedido.getEstado() != EstadoPedido.ENTREGADO
                        && pedido.getEstado() != EstadoPedido.CANCELADO);
        EstadoRepartidor estadoCorrecto = repartidor.getEstado();
        if (tienePedidoActivo) {
            estadoCorrecto = EstadoRepartidor.EN_RUTA;
        } else if (estadoCorrecto == EstadoRepartidor.EN_RUTA) {
            estadoCorrecto = repartidor.isActivo()
                    ? EstadoRepartidor.DISPONIBLE
                    : EstadoRepartidor.DESCONECTADO;
        }

        if (estadoCorrecto == repartidor.getEstado()) {
            return Mono.just(repartidor);
        }

        EstadoRepartidor nuevoEstado = estadoCorrecto;
        return repartidorRepository.actualizarEstado(repartidor.getId(), nuevoEstado)
                .then(Mono.fromSupplier(() -> {
                    repartidor.setEstado(nuevoEstado);
                    return repartidor;
                }));
    }

    public Mono<RepartidorDTO.Response> actualizar(String id, RepartidorDTO.ActualizarRequest request) {
        return repartidorRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Repartidor", id)))
                .flatMap(repartidor -> placaDisponible(request.getPlacaVehiculo(), id)
                        .flatMap(placa -> repartidorRepository.actualizarPlaca(id, placa)
                                .then(repartidorRepository.findById(id))))
                .map(mapper::toDTO);
    }

    private Mono<String> placaDisponible(String placa, String repartidorIdIgnorado) {
        String normalizada = placa.trim().toUpperCase(java.util.Locale.ROOT);
        String clave = normalizada.replaceAll("[^A-Z0-9]", "");
        return repartidorRepository.findAll()
                .filter(otro -> !otro.getId().equals(repartidorIdIgnorado))
                .filter(otro -> otro.getPlacaVehiculo() != null
                        && otro.getPlacaVehiculo().replaceAll("[^A-Za-z0-9]", "")
                                .equalsIgnoreCase(clave))
                .hasElements()
                .flatMap(duplicada -> duplicada
                        ? Mono.error(new OperacionInvalidaException("La placa ya está asignada a otro repartidor"))
                        : Mono.just(normalizada));
    }

    public Mono<RepartidorDTO.Response> cambiarEstado(String id, EstadoRepartidor estado) {
        return repartidorRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Repartidor", id)))
                .flatMap(repartidor -> {
                    if (!repartidor.isActivo()) {
                        return Mono.error(new OperacionInvalidaException("El perfil del repartidor está desactivado"));
                    }
                    if (estado == null) {
                        return Mono.error(new OperacionInvalidaException("El estado del repartidor es obligatorio"));
                    }
                    if (estado != EstadoRepartidor.DESCONECTADO && repartidor.getVehiculoId() == null && repartidor.getPlacaVehiculo() == null) {
                        return Mono.error(new OperacionInvalidaException("Necesitas un vehículo asignado antes de salir a repartir."));
                    }
                    return tienePedidosActivos(id).flatMap(tienePedidos -> {
                        if (tienePedidos && estado != EstadoRepartidor.EN_RUTA) {
                            return Mono.error(new OperacionInvalidaException(
                                    "No puedes cambiar tu disponibilidad mientras tengas pedidos activos"));
                        }
                        return repartidorRepository.actualizarEstado(id, estado)
                                .then(repartidorRepository.findById(id));
                    });
                })
                .map(mapper::toDTO);
    }

    private Mono<Boolean> tienePedidosActivos(String repartidorId) {
        return pedidoRepository.findByRepartidorId(repartidorId)
                .filter(pedido -> pedido.getEstado() != com.example.backend.model.EstadoPedido.ENTREGADO
                        && pedido.getEstado() != com.example.backend.model.EstadoPedido.CANCELADO)
                .hasElements();
    }

    /**
     * Actualiza la última ubicación GPS del repartidor. Solo el propio
     * repartidor (dueño del perfil) puede reportar su ubicación.
     */
    public Mono<RepartidorDTO.Response> actualizarUbicacion(String id, String uidSolicitante, UbicacionDTO ubicacion) {
        return repartidorRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Repartidor", id)))
                .flatMap(repartidor -> {
                    if (!repartidor.getUsuarioId().equals(uidSolicitante)) {
                        return Mono.error(new ApiException(HttpStatus.FORBIDDEN,
                                "Solo el propio repartidor puede reportar su ubicación"));
                    }
                    if (!repartidor.isActivo()) {
                        return Mono.error(new OperacionInvalidaException("El perfil del repartidor está desactivado"));
                    }
                    return repartidorRepository.actualizarUbicacion(id, mapper.toModel(ubicacion))
                            .then(repartidorRepository.findById(id));
                })
                .map(mapper::toDTO);
    }

    /** Última ubicación conocida del repartidor (para seguimiento desde Angular). */
    public Mono<UbicacionDTO> obtenerUbicacion(String id, String uid, Rol rol) {
        return repartidorRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Repartidor", id)))
                .flatMap(repartidor -> autorizarUbicacion(repartidor, uid, rol)
                        .then(Mono.fromCallable(() -> {
                    if (repartidor.getUbicacionActual() == null) {
                        throw RecursoNoEncontradoException.de("Ubicación del repartidor", id);
                    }
                    return mapper.toDTO(repartidor.getUbicacionActual());
                })));
    }

    private Mono<Void> autorizarUbicacion(com.example.backend.model.Repartidor repartidor,
                                          String uid, Rol rol) {
        if (rol == Rol.ADMINISTRADOR || rol == Rol.SUPERVISOR) {
            return Mono.empty();
        }
        if (rol == Rol.REPARTIDOR) {
            return repartidor.getUsuarioId().equals(uid)
                    ? Mono.empty()
                    : Mono.error(new ApiException(HttpStatus.FORBIDDEN,
                            "No puedes consultar la ubicación de otro repartidor"));
        }
        if (rol != Rol.CLIENTE) {
            return Mono.error(new ApiException(HttpStatus.FORBIDDEN, "No tienes acceso a esta ubicación"));
        }

        return clienteRepository.findByUsuarioId(uid)
                .switchIfEmpty(Mono.error(new ApiException(HttpStatus.FORBIDDEN,
                        "No tienes pedidos asociados a este repartidor")))
                .flatMap(cliente -> pedidoRepository.findByClienteId(cliente.getId())
                        .filter(pedido -> repartidor.getId().equals(pedido.getRepartidorId()))
                        .filter(pedido -> pedido.getEstado() == com.example.backend.model.EstadoPedido.EN_CAMINO
                                || pedido.getEstado() == com.example.backend.model.EstadoPedido.ENTREGA_FALLIDA)
                        .hasElements())
                .flatMap(tienePedido -> tienePedido
                        ? Mono.empty()
                        : Mono.error(new ApiException(HttpStatus.FORBIDDEN,
                                "No tienes una entrega activa con este repartidor")))
                .then();
    }

    public Mono<Void> desactivar(String id) {
        return repartidorRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Repartidor", id)))
                .flatMap(repartidor -> tienePedidosActivos(id).flatMap(tienePedidos -> {
                    if (tienePedidos) {
                        return Mono.error(new OperacionInvalidaException(
                                "No se puede desactivar un repartidor con pedidos activos"));
                    }
                    repartidor.setActivo(false);
                    repartidor.setEstado(EstadoRepartidor.DESCONECTADO);
                    return repartidorRepository.save(repartidor);
                }))
                .then();
    }

    public Mono<Void> reactivar(String id) {
        return repartidorRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Repartidor", id)))
                .flatMap(repartidor -> {
                    repartidor.setActivo(true);
                    return repartidorRepository.save(repartidor);
                })
                .then();
    }
}
