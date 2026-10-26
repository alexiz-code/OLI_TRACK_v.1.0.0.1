package com.example.backend.dto;

import com.example.backend.model.EstadoPedido;
import com.example.backend.model.MetodoPago;
import com.example.backend.model.TipoIncidencia;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

public class PedidoDTO {

    private PedidoDTO() {
    }

    /** Cuerpo esperado al crear un pedido (lo envía el cliente desde Angular). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CrearRequest {

        @NotBlank(message = "La dirección de entrega es obligatoria")
        private String direccionEntrega;

        @Valid
        private UbicacionDTO ubicacionEntrega;

        private String observaciones;

        @NotNull(message = "El método de pago es obligatorio")
        private MetodoPago metodoPago;

        @NotEmpty(message = "El pedido debe tener al menos un producto")
        @Valid
        private List<DetallePedidoDTO> productos;
    }
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AsignarRepartidorRequest {

        @NotBlank(message = "El repartidorId es obligatorio")
        private String repartidorId;
    }

    /** Reasignación de un pedido que ya tenía repartidor (HU-033). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReasignarRequest {

        @NotBlank(message = "El repartidorId es obligatorio")
        private String repartidorId;

        private String comentario;
    }

    /** Rechazo de un pedido con observaciones (HU-030). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RechazarRequest {

        @NotBlank(message = "Las observaciones son obligatorias")
        private String observaciones;
    }

    /** Entrega fallida con su motivo (HU-098, HU-099). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntregaFallidaRequest {

        @NotBlank(message = "El motivo es obligatorio")
        private String motivo;

        /** Si no se indica, se registra como OTRO. */
        private TipoIncidencia tipo;
    }

    /** Edición permitida solo mientras el pedido sigue PENDIENTE (no cambia productos). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EditarRequest {

        @NotBlank(message = "La dirección de entrega es obligatoria")
        private String direccionEntrega;

        @Valid
        private UbicacionDTO ubicacionEntrega;

        private String observaciones;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CambiarEstadoRequest {

        @NotNull(message = "El estado es obligatorio")
        private EstadoPedido estado;

        private String comentario;
    }

    /** Cuerpo para que el cliente califique un pedido ya ENTREGADO. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CalificarRequest {

        @NotNull(message = "La cantidad de estrellas es obligatoria")
        @Min(value = 1, message = "Mínimo 1 estrella")
        @Max(value = 5, message = "Máximo 5 estrellas")
        private Integer estrellas;

        private String comentario;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistorialEstadoDTO {
        private EstadoPedido estado;
        private Date fecha;
        private String usuarioId;
        private String comentario;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private String id;
        private String clienteId;
        private String repartidorId;
        private EstadoPedido estado;
        private BigDecimal total;
        private String direccionEntrega;
        private UbicacionDTO ubicacionEntrega;
        private String observaciones;
        private MetodoPago metodoPago;
        private BigDecimal costoEnvio;
        private String evidenciaUrl;
        private String evidenciaContentType;
        private String evidenciaNombreArchivo;
        private Long evidenciaTamanoBytes;
        private Date evidenciaSubidaEn;
        private CalificacionDTO.Response calificacion;
        private List<DetallePedidoDTO> productos;
        private Date creadoEn;
        private Date entregadoEn;
        private List<HistorialEstadoDTO> historialEstados;
        private boolean entregaAceptada;
        private Date llegadaEn;
        private Integer tiempoEstimadoMinutos;
        /** Minutos desde la salida a ruta hasta la llegada al destino. */
        private Long tiempoAtencionMinutos;
    }

    /** Alertas para el supervisor (HU-048, HU-049). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlertasResponse {
        private List<Response> retrasados;
        private List<Response> sinAsignar;
    }
}
