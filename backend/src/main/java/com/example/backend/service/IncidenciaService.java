package com.example.backend.service;

import com.example.backend.dto.IncidenciaDTO;
import com.example.backend.exception.ApiException;
import com.example.backend.exception.OperacionInvalidaException;
import com.example.backend.exception.RecursoNoEncontradoException;
import com.example.backend.mapper.EntityMapper;
import com.example.backend.model.EstadoIncidencia;
import com.example.backend.model.Incidencia;
import com.example.backend.repository.IncidenciaRepository;
import com.example.backend.repository.PedidoRepository;
import com.example.backend.repository.RepartidorRepository;
import com.example.backend.repository.VehiculoRepository;
import com.example.backend.repository.UsuarioRepository;
import com.example.backend.service.NotificacionService;
import com.example.backend.model.Rol;
import com.example.backend.model.Usuario;
import com.example.backend.model.Vehiculo;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Date;

@Service
public class IncidenciaService {

    private final IncidenciaRepository incidenciaRepository;
    private final PedidoRepository pedidoRepository;
    private final EntityMapper mapper;
    private final RepartidorRepository repartidorRepository;
    private final VehiculoRepository vehiculoRepository;
    private final UsuarioRepository usuarioRepository;
    private final NotificacionService notificacionService;
    private final AuditService auditService;

    public IncidenciaService(IncidenciaRepository incidenciaRepository, PedidoRepository pedidoRepository, EntityMapper mapper,
                             RepartidorRepository repartidorRepository, VehiculoRepository vehiculoRepository,
                             UsuarioRepository usuarioRepository, NotificacionService notificacionService,
                             AuditService auditService) {
        this.incidenciaRepository = incidenciaRepository;
        this.pedidoRepository = pedidoRepository;
        this.mapper = mapper;
        this.repartidorRepository = repartidorRepository;
        this.vehiculoRepository = vehiculoRepository;
        this.usuarioRepository = usuarioRepository;
        this.notificacionService = notificacionService;
        this.auditService = auditService;
    }

    public Mono<IncidenciaDTO.Response> registrarAveriaVehiculo(String repartidorId, IncidenciaDTO.AveriaVehiculoRequest request) {
        return repartidorRepository.findById(repartidorId)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Repartidor", repartidorId)))
                .flatMap(repartidor -> {
                    if (repartidor.getVehiculoId() == null) return Mono.error(new OperacionInvalidaException("No tienes un vehículo asignado."));
                    return vehiculoRepository.findById(repartidor.getVehiculoId())
                            .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Vehículo", repartidor.getVehiculoId())))
                            .flatMap(vehiculo -> guardarAveria(repartidorId, vehiculo, request));
                });
    }

    private Mono<IncidenciaDTO.Response> guardarAveria(String repartidorId, Vehiculo vehiculo, IncidenciaDTO.AveriaVehiculoRequest request) {
        Incidencia incidencia = new Incidencia();
        incidencia.setRepartidorId(repartidorId); incidencia.setVehiculoId(vehiculo.getId());
        incidencia.setTipo(request.getTipo()); incidencia.setDescripcion(request.getDescripcion());
        incidencia.setGravedad(request.getGravedad()); incidencia.setPuedeContinuarTrabajando(request.getPuedeContinuarTrabajando());
        incidencia.setObservaciones(request.getObservaciones()); incidencia.setLatitud(request.getLatitud());
        incidencia.setLongitud(request.getLongitud()); incidencia.setEvidencias(request.getEvidencias() == null ? java.util.List.of() : request.getEvidencias());
        return incidenciaRepository.registrarAveriaVehiculo(incidencia, !Boolean.TRUE.equals(request.getPuedeContinuarTrabajando()))
                .flatMap(guardada -> {
            Mono<Void> notificar = usuarioRepository.findAll()
                    .filter(usuario -> usuario.getRol() == Rol.ADMINISTRADOR || usuario.getRol() == Rol.SUPERVISOR)
                    .flatMap(usuario -> notificacionService.crear(usuario.getId(), "Avería reportada · " + vehiculo.getPlaca(),
                            request.getTipo() + ": " + request.getDescripcion()), 4)
                    .then();
            return notificar.thenReturn(mapper.toDTO(guardada));
        });
    }

    /** Solo el repartidor asignado al pedido puede registrar una incidencia sobre él. */
    public Mono<IncidenciaDTO.Response> registrar(String repartidorId, IncidenciaDTO.CrearRequest request) {
        return pedidoRepository.findById(request.getPedidoId())
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Pedido", request.getPedidoId())))
                .flatMap(pedido -> {
                    if (!repartidorId.equals(pedido.getRepartidorId())) {
                        return Mono.error(new OperacionInvalidaException(
                                "Solo el repartidor asignado puede registrar una incidencia de este pedido"));
                    }
                    return incidenciaRepository.save(mapper.toModel(request, repartidorId));
                })
                .map(mapper::toDTO);
    }

    /** El supervisor puede registrar una incidencia sobre cualquier pedido (HU-043). */
    public Mono<IncidenciaDTO.Response> registrarComoSupervisor(IncidenciaDTO.CrearRequest request) {
        return pedidoRepository.findById(request.getPedidoId())
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Pedido", request.getPedidoId())))
                .flatMap(pedido -> incidenciaRepository.save(mapper.toModel(request, pedido.getRepartidorId())))
                .map(mapper::toDTO);
    }

    public Mono<IncidenciaDTO.Response> obtenerPorId(String id, String repartidorId) {
        return incidenciaRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Incidencia", id)))
                .flatMap(incidencia -> {
                    if (repartidorId != null && !repartidorId.equals(incidencia.getRepartidorId())) {
                        return Mono.error(new ApiException(HttpStatus.FORBIDDEN,
                                "No tienes acceso a esta incidencia"));
                    }
                    return Mono.just(incidencia);
                })
                .map(mapper::toDTO);
    }

    public Flux<IncidenciaDTO.Response> listar() {
        return incidenciaRepository.findAll().map(mapper::toDTO);
    }

    public Flux<IncidenciaDTO.Response> listarPorPedido(String pedidoId, String repartidorId) {
        Mono<Void> acceso = repartidorId == null
                ? Mono.empty()
                : pedidoRepository.findById(pedidoId)
                        .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Pedido", pedidoId)))
                        .flatMap(pedido -> repartidorId.equals(pedido.getRepartidorId())
                                ? Mono.empty()
                                : Mono.error(new ApiException(HttpStatus.FORBIDDEN,
                                        "No tienes acceso a las incidencias de este pedido")))
                        .then();
        return acceso.thenMany(incidenciaRepository.findByPedidoId(pedidoId)
                .filter(incidencia -> repartidorId == null
                        || repartidorId.equals(incidencia.getRepartidorId()))
                .map(mapper::toDTO));
    }

    public Flux<IncidenciaDTO.Response> listarPorRepartidor(String repartidorId) {
        return incidenciaRepository.findByRepartidorId(repartidorId).map(mapper::toDTO);
    }

    public Mono<IncidenciaDTO.Response> cambiarEstado(String id, EstadoIncidencia estado) {
        return incidenciaRepository.findById(id)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Incidencia", id)))
                .flatMap(incidencia -> {
                    incidencia.setEstado(estado);
                    if (estado == EstadoIncidencia.CERRADA) {
                        incidencia.setCerradoEn(new Date());
                    }
                    return incidenciaRepository.save(incidencia);
                })
                .map(mapper::toDTO);
    }

    public Mono<IncidenciaDTO.Response> gestionarMantenimiento(String incidenciaId, IncidenciaDTO.GestionMantenimientoRequest request, String actorId) {
        return incidenciaRepository.findById(incidenciaId)
                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Incidencia", incidenciaId)))
                .flatMap(incidencia -> {
                    if (incidencia.getVehiculoId() == null) return Mono.error(new OperacionInvalidaException("La incidencia no corresponde a un vehículo."));
                    incidencia.setDiagnostico(request.getDiagnostico()); incidencia.setSolucion(request.getSolucion());
                    incidencia.setObservaciones(request.getObservaciones()); incidencia.setCostoReparacion(request.getCostoReparacion());
                    if (request.isFinalizar()) {
                        if (request.getSolucion() == null || request.getSolucion().isBlank()) return Mono.error(new OperacionInvalidaException("Indica la reparación antes de finalizar el mantenimiento."));
                        return vehiculoRepository.findById(incidencia.getVehiculoId())
                                .switchIfEmpty(Mono.error(RecursoNoEncontradoException.de("Vehículo", incidencia.getVehiculoId())))
                                .flatMap(vehiculo -> pedidoRepository.findByRepartidorId(incidencia.getRepartidorId())
                                        .filter(p -> p.getEstado() != com.example.backend.model.EstadoPedido.ENTREGADO && p.getEstado() != com.example.backend.model.EstadoPedido.CANCELADO)
                                        .hasElements().flatMap(pendientes -> {
                                            if (pendientes) return Mono.error(new OperacionInvalidaException("Resuelve los pedidos activos antes de autorizar el regreso."));
                                            vehiculo.setEstadoOperativo("DISPONIBLE"); vehiculo.setActualizadoEn(new Date());
                                            incidencia.setEstado(EstadoIncidencia.CERRADA); incidencia.setCerradoEn(new Date()); incidencia.setAutorizadoPor(actorId);
                                            return vehiculoRepository.save(vehiculo).then(incidenciaRepository.save(incidencia))
                                                    .flatMap(guardada -> auditService.registrar(actorId, "MANTENIMIENTO_FINALIZADO", "vehiculo", vehiculo.getId(),
                                                            "Incidencia " + incidenciaId + ", costo=" + request.getCostoReparacion())
                                                            .then(repartidorRepository.findById(incidencia.getRepartidorId())
                                                                    .flatMap(repartidor -> notificacionService.crear(repartidor.getUsuarioId(), "Vehículo autorizado", "El mantenimiento de " + vehiculo.getPlaca() + " fue finalizado.")).then())
                                                            .thenReturn(guardada));
                                        }));
                    }
                    return incidenciaRepository.save(incidencia).flatMap(guardada -> auditService.registrar(actorId,
                            "DIAGNOSTICO_MANTENIMIENTO", "vehiculo", incidencia.getVehiculoId(), "Incidencia " + incidenciaId)
                            .thenReturn(guardada));
                })
                .map(mapper::toDTO);
    }
}
