package com.example.backend.mapper;

import com.example.backend.dto.ClienteDTO;
import com.example.backend.dto.DetallePedidoDTO;
import com.example.backend.dto.IncidenciaDTO;
import com.example.backend.dto.InventarioDTO;
import com.example.backend.dto.PedidoDTO;
import com.example.backend.dto.ProductoDTO;
import com.example.backend.dto.RepartidorDTO;
import com.example.backend.dto.UbicacionDTO;
import com.example.backend.dto.UsuarioDTO;
import com.example.backend.model.Cliente;
import com.example.backend.model.DetallePedido;
import com.example.backend.model.Inventario;
import com.example.backend.model.Pedido;
import com.example.backend.model.Producto;
import com.example.backend.model.Repartidor;
import com.example.backend.model.Ubicacion;
import com.example.backend.model.Usuario;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Conversión manual entre entidades de Firestore (model) y los DTOs expuestos
 * por la API. Manual y explícito a propósito: evita "magia" de librerías de
 * mapping y deja clarísimo qué campo interno nunca se expone (por ejemplo,
 * nada relacionado a credenciales viaja fuera de Usuario).
 */
@Component
public class EntityMapper {

    // ---------- Ubicacion ----------

    public Ubicacion toModel(UbicacionDTO dto) {
        if (dto == null) {
            return null;
        }
        return new Ubicacion(dto.getLatitud(), dto.getLongitud(), new java.util.Date());
    }

    public UbicacionDTO toDTO(Ubicacion model) {
        if (model == null) {
            return null;
        }
        return new UbicacionDTO(model.getLatitud(), model.getLongitud());
    }

    // ---------- Usuario ----------

    public UsuarioDTO.Response toDTO(Usuario model) {
        if (model == null) {
            return null;
        }
        return new UsuarioDTO.Response(
                model.getId(),
                model.getNombre(),
                model.getEmail(),
                model.getTelefono(),
                model.getRol(),
                model.isActivo(),
                model.getCreadoEn()
        );
    }

    // ---------- Cliente ----------

    public Cliente toModel(ClienteDTO.CrearRequest dto) {
        Cliente cliente = new Cliente();
        cliente.setUsuarioId(dto.getUsuarioId());
        cliente.setNombreNegocio(dto.getNombreNegocio());
        cliente.setDireccion(dto.getDireccion());
        cliente.setReferencia(dto.getReferencia());
        cliente.setUbicacion(toModel(dto.getUbicacion()));
        cliente.setActivo(true);
        return cliente;
    }

    public ClienteDTO.Response toDTO(Cliente model) {
        if (model == null) {
            return null;
        }
        return new ClienteDTO.Response(
                model.getId(),
                model.getUsuarioId(),
                model.getNombreNegocio(),
                model.getDireccion(),
                model.getReferencia(),
                toDTO(model.getUbicacion()),
                model.isActivo(),
                model.getCreadoEn(),
                toDireccionesDTO(model.getDireccionesAdicionales()),
                null
        );
    }

    public List<ClienteDTO.DireccionDTO> toDireccionesDTO(List<com.example.backend.model.DireccionGuardada> lista) {
        if (lista == null) {
            return List.of();
        }
        return lista.stream()
                .map(d -> new ClienteDTO.DireccionDTO(d.getId(), d.getAlias(), d.getDireccion(), d.getReferencia(), toDTO(d.getUbicacion())))
                .toList();
    }

    public com.example.backend.model.DireccionGuardada toModel(ClienteDTO.DireccionCrearRequest dto) {
        return new com.example.backend.model.DireccionGuardada(
                UUID.randomUUID().toString(), dto.getAlias(), dto.getDireccion(), dto.getReferencia(), toModel(dto.getUbicacion()));
    }

    // ---------- Repartidor ----------

    public Repartidor toModel(RepartidorDTO.CrearRequest dto) {
        Repartidor repartidor = new Repartidor();
        repartidor.setUsuarioId(dto.getUsuarioId());
        repartidor.setPlacaVehiculo(dto.getPlacaVehiculo());
        repartidor.setTipoVehiculo("MOTOCICLETA");
        repartidor.setActivo(true);
        return repartidor;
    }

    public RepartidorDTO.Response toDTO(Repartidor model) {
        if (model == null) {
            return null;
        }
        return new RepartidorDTO.Response(
                model.getId(),
                model.getUsuarioId(),
                model.getPlacaVehiculo(),
                model.getTipoVehiculo() != null ? model.getTipoVehiculo() : "MOTOCICLETA",
                model.getEstado(),
                toDTO(model.getUbicacionActual()),
                model.isActivo(),
                model.getCreadoEn(),
                null,
                null
        );
    }

    // ---------- Producto ----------

    public Producto toModel(ProductoDTO.CrearRequest dto) {
        Producto producto = new Producto();
        producto.setNombre(dto.getNombre());
        producto.setDescripcion(dto.getDescripcion());
        producto.setCategoria(dto.getCategoria());
        producto.setPrecioUnitario(dto.getPrecioUnitario());
        producto.setUnidadMedida(dto.getUnidadMedida());
        producto.setImagenUrl(dto.getImagenUrl());
        producto.setActivo(true);
        return producto;
    }

    public ProductoDTO.Response toDTO(Producto model) {
        if (model == null) {
            return null;
        }
        return new ProductoDTO.Response(
                model.getId(),
                model.getNombre(),
                model.getDescripcion(),
                model.getCategoria(),
                model.getPrecioUnitario(),
                model.getUnidadMedida(),
                model.getImagenUrl(),
                model.isActivo(),
                model.getCreadoEn(),
                null
        );
    }

    // ---------- Inventario ----------

    public InventarioDTO.Response toDTO(Inventario model) {
        if (model == null) {
            return null;
        }
        return new InventarioDTO.Response(
                model.getId(),
                model.getProductoId(),
                model.getStockActual(),
                model.getStockMinimo(),
                model.getStockMaximo(),
                model.getActualizadoEn()
        );
    }

    // ---------- DetallePedido ----------

    public DetallePedidoDTO toDTO(DetallePedido model) {
        if (model == null) {
            return null;
        }
        return new DetallePedidoDTO(
                model.getId(),
                model.getProductoId(),
                model.getCantidad(),
                model.getPrecioUnitario(),
                model.getSubtotal()
        );
    }

    // ---------- Pedido ----------

    public PedidoDTO.Response toDTO(Pedido model, List<DetallePedidoDTO> productos) {
        if (model == null) {
            return null;
        }
        return new PedidoDTO.Response(
                model.getId(),
                model.getClienteId(),
                model.getRepartidorId(),
                model.getEstado(),
                model.getTotal(),
                model.getDireccionEntrega(),
                toDTO(model.getUbicacionEntrega()),
                model.getObservaciones(),
                model.getMetodoPago(),
                model.getCostoEnvio(),
                model.getEvidenciaStoragePath() != null || model.getEvidenciaUrl() != null
                        ? "/api/pedidos/" + model.getId() + "/evidencia/archivo"
                        : null,
                model.getEvidenciaContentType(),
                model.getEvidenciaNombreArchivo(),
                model.getEvidenciaTamanoBytes(),
                model.getEvidenciaSubidaEn(),
                toDTO(model.getCalificacion()),
                productos,
                model.getCreadoEn(),
                model.getEntregadoEn(),
                toHistorialDTO(model.getHistorialEstados()),
                model.isEntregaAceptada(),
                model.getLlegadaEn(),
                model.getTiempoEstimadoMinutos(),
                tiempoAtencion(model)
        );
    }

    private List<PedidoDTO.HistorialEstadoDTO> toHistorialDTO(List<com.example.backend.model.HistorialEstado> lista) {
        if (lista == null) {
            return List.of();
        }
        return lista.stream()
                .map(h -> new PedidoDTO.HistorialEstadoDTO(h.getEstado(), h.getFecha(), h.getUsuarioId(), h.getComentario()))
                .toList();
    }

    private Long tiempoAtencion(Pedido pedido) {
        if (pedido.getCreadoEn() == null || pedido.getEntregadoEn() == null) {
            return null;
        }
        return Duration.between(pedido.getCreadoEn().toInstant(), pedido.getEntregadoEn().toInstant()).toMinutes();
    }

    public com.example.backend.dto.CalificacionDTO.Response toDTO(com.example.backend.model.Calificacion model) {
        if (model == null) {
            return null;
        }
        return new com.example.backend.dto.CalificacionDTO.Response(
                model.getEstrellas(), model.getComentario(), model.getFecha());
    }

    // ---------- Incidencia ----------

    public com.example.backend.model.Incidencia toModel(IncidenciaDTO.CrearRequest dto, String repartidorId) {
        com.example.backend.model.Incidencia incidencia = new com.example.backend.model.Incidencia();
        incidencia.setPedidoId(dto.getPedidoId());
        incidencia.setRepartidorId(repartidorId);
        incidencia.setTipo(dto.getTipo());
        incidencia.setDescripcion(dto.getDescripcion());
        incidencia.setEstado(com.example.backend.model.EstadoIncidencia.ABIERTA);
        return incidencia;
    }

    public IncidenciaDTO.Response toDTO(com.example.backend.model.Incidencia model) {
        if (model == null) {
            return null;
        }
        return new IncidenciaDTO.Response(
                model.getId(),
                model.getPedidoId(),
                model.getRepartidorId(),
                model.getTipo(),
                model.getDescripcion(),
                model.getEstado(),
                model.getCreadoEn(),
                model.getCerradoEn()
        );
    }

    // ---------- Kardex ----------

    public com.example.backend.dto.KardexDTO.Response toDTO(com.example.backend.model.Kardex model) {
        if (model == null) {
            return null;
        }
        return new com.example.backend.dto.KardexDTO.Response(
                model.getId(),
                model.getProductoId(),
                model.getTipoMovimiento(),
                model.getCantidad(),
                model.getStockAnterior(),
                model.getStockNuevo(),
                model.getMotivo(),
                model.getCreadoEn()
        );
    }

    // ---------- Consulta ----------

    public com.example.backend.model.Consulta toModel(com.example.backend.dto.ConsultaDTO.CrearRequest dto) {
        com.example.backend.model.Consulta consulta = new com.example.backend.model.Consulta();
        consulta.setNombre(dto.getNombre());
        consulta.setEmail(dto.getEmail());
        consulta.setAsunto(dto.getAsunto());
        consulta.setMensaje(dto.getMensaje());
        consulta.setEstado(com.example.backend.model.EstadoConsulta.NUEVA);
        return consulta;
    }

    public com.example.backend.dto.ConsultaDTO.Response toDTO(com.example.backend.model.Consulta model) {
        if (model == null) {
            return null;
        }
        return new com.example.backend.dto.ConsultaDTO.Response(
                model.getId(),
                model.getNombre(),
                model.getEmail(),
                model.getAsunto(),
                model.getMensaje(),
                model.getEstado(),
                model.getCreadoEn()
        );
    }

    // ---------- Notificacion ----------

    public com.example.backend.dto.NotificacionDTO.Response toDTO(com.example.backend.model.Notificacion model) {
        if (model == null) {
            return null;
        }
        return new com.example.backend.dto.NotificacionDTO.Response(
                model.getId(),
                model.getTitulo(),
                model.getMensaje(),
                model.isLeida(),
                model.getCreadoEn()
        );
    }
}
