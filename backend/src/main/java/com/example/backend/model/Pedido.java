package com.example.backend.model;

import com.google.cloud.firestore.annotation.DocumentId;
import com.google.cloud.firestore.annotation.ServerTimestamp;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Documento de la colección "pedidos".
 * El detalle (productos y cantidades) vive en la colección aparte "detallePedidos",
 * relacionado mediante pedidoId.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Pedido implements EntidadFirestore {

    @DocumentId
    private String id;

    /** Referencia al documento en "clientes". */
    private String clienteId;

    /** Referencia al documento en "repartidores"; null hasta que se asigna uno. */
    private String repartidorId;

    private EstadoPedido estado = EstadoPedido.PENDIENTE;
    private BigDecimal total;
    private String direccionEntrega;
    private Ubicacion ubicacionEntrega;
    private String observaciones;

    /** Solo informativa: no cambia ningún flujo del backend. */
    private MetodoPago metodoPago;

    /** Fijo en 0.00 por ahora (no hay zonas de reparto con tarifa en el backend todavía). */
    private BigDecimal costoEnvio = BigDecimal.ZERO;

    /** Se llena solo si metodoPago != EFECTIVO (comprobante de Yape/Plin/Tarjeta). */
    private String evidenciaUrl;

    /** Ruta privada del objeto en Firebase Storage; no es una URL pública. */
    private String evidenciaStoragePath;

    private String evidenciaContentType;

    private String evidenciaNombreArchivo;

    private Long evidenciaTamanoBytes;

    private Date evidenciaSubidaEn;

    /** Se llena solo después de que el pedido llega a ENTREGADO. */
    private Calificacion calificacion;

    /** Trazabilidad de cada cambio de estado (quién, cuándo, comentario). */
    private List<HistorialEstado> historialEstados = new ArrayList<>();

    /** El repartidor asignado confirmó que realizará la entrega. */
    private boolean entregaAceptada;

    /** Momento en que el repartidor marcó "llegué al destino". */
    private Date llegadaEn;

    private Integer tiempoEstimadoMinutos;

    @ServerTimestamp
    private Date creadoEn;

    private Date entregadoEn;
}
