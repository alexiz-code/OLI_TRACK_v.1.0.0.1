import { Ubicacion } from './ubicacion.models';
import { TipoIncidencia } from './incidencia.models';

export type EstadoPedido =
  | 'PENDIENTE'
  | 'CONFIRMADO'
  | 'EN_PREPARACION'
  | 'EN_CAMINO'
  | 'ENTREGADO'
  | 'ENTREGA_FALLIDA'
  | 'CANCELADO';

export type MetodoPago = 'EFECTIVO' | 'YAPE' | 'PLIN';

export interface DetallePedidoLinea {
  productoId: string;
  cantidad: number;
  precioUnitario?: number;
  subtotal?: number;
}

export interface Calificacion {
  estrellas: number;
  comentario?: string;
  fecha: string;
}

export interface HistorialEstado {
  estado: EstadoPedido;
  fecha: string;
  usuarioId: string;
  comentario?: string;
}

export interface Pedido {
  id: string;
  clienteId: string;
  repartidorId?: string;
  estado: EstadoPedido;
  total: number;
  direccionEntrega: string;
  ubicacionEntrega?: Ubicacion;
  observaciones?: string;
  metodoPago: MetodoPago;
  costoEnvio: number;
  evidenciaUrl?: string;
  evidenciaContentType?: string;
  evidenciaNombreArchivo?: string;
  evidenciaTamanoBytes?: number;
  evidenciaSubidaEn?: string;
  calificacion?: Calificacion;
  productos: DetallePedidoLinea[];
  creadoEn: string;
  entregadoEn?: string;
  historialEstados: HistorialEstado[];
  entregaAceptada: boolean;
  llegadaEn?: string;
  tiempoEstimadoMinutos?: number;
  tiempoAtencionMinutos?: number;
}

export interface PedidoCrearRequest {
  direccionEntrega: string;
  ubicacionEntrega?: Ubicacion;
  observaciones?: string;
  metodoPago: MetodoPago;
  productos: { productoId: string; cantidad: number }[];
}

export interface CalificarRequest {
  estrellas: number;
  comentario?: string;
}

export interface EntregaFallidaRequest {
  motivo: string;
  tipo?: TipoIncidencia;
}

export interface RechazarRequest {
  observaciones: string;
}

export interface ReasignarRequest {
  repartidorId: string;
  comentario?: string;
}

export interface AlertasResponse {
  retrasados: Pedido[];
  sinAsignar: Pedido[];
}
