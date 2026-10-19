export type TipoIncidencia =
  | 'RETRASO'
  | 'PRODUCTO_DANADO'
  | 'DIRECCION_INCORRECTA'
  | 'CLIENTE_AUSENTE'
  | 'MECANICA'
  | 'TRAFICO'
  | 'OPERATIVO'
  | 'OTRO';

export type EstadoIncidencia = 'ABIERTA' | 'EN_REVISION' | 'CERRADA';

export interface Incidencia {
  id: string;
  pedidoId: string;
  repartidorId: string;
  tipo: TipoIncidencia;
  descripcion: string;
  estado: EstadoIncidencia;
  creadoEn: string;
  cerradoEn?: string;
}

export interface IncidenciaCrearRequest {
  pedidoId: string;
  tipo: TipoIncidencia;
  descripcion: string;
}
