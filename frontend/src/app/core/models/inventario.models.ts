export type TipoMovimiento = 'ENTRADA' | 'SALIDA' | 'AJUSTE';

export interface Inventario {
  id: string;
  productoId: string;
  stockActual: number;
  stockMinimo: number;
  stockMaximo: number;
  actualizadoEn: string;
}

export interface MovimientoRequest {
  tipo: TipoMovimiento;
  cantidad: number;
  motivo?: string;
}

export interface Kardex {
  id: string;
  productoId: string;
  tipoMovimiento: TipoMovimiento;
  cantidad: number;
  stockAnterior: number;
  stockNuevo: number;
  motivo?: string;
  creadoEn: string;
}
