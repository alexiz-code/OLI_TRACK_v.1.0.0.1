import { Ubicacion } from './ubicacion.models';

export interface DireccionGuardada {
  id: string;
  alias: string;
  direccion: string;
  referencia?: string;
  ubicacion: Ubicacion;
}

export interface Cliente {
  id: string;
  usuarioId: string;
  nombreNegocio: string;
  direccion: string;
  referencia?: string;
  ubicacion: Ubicacion;
  activo: boolean;
  creadoEn: string;
  direccionesAdicionales: DireccionGuardada[];
  ultimoPedidoEn?: string | null;
}

export interface ClienteCrearRequest {
  usuarioId: string;
  nombreNegocio: string;
  direccion: string;
  referencia?: string;
  ubicacion: Ubicacion;
}

export interface ClienteActualizarRequest {
  nombreNegocio: string;
  direccion: string;
  referencia?: string;
  ubicacion?: Ubicacion;
}

export interface DireccionCrearRequest {
  alias: string;
  direccion: string;
  referencia?: string;
  ubicacion: Ubicacion;
}
