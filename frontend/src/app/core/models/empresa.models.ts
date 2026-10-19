import { MetodoPago } from './pedido.models';

export interface Zona {
  id: string;
  nombre: string;
  distrito: string;
  costoEnvio: number;
  tiempoEstimadoMinutos: number;
  activa: boolean;
  creadoEn: string;
}

export interface ZonaRequest {
  nombre: string;
  distrito: string;
  costoEnvio: number;
  tiempoEstimadoMinutos: number;
}

export interface ConfiguracionEmpresa {
  nombre: string;
  ruc?: string;
  direccionCentral?: string;
  telefono?: string;
  correo?: string;
  horarioApertura: string;
  horarioCierre: string;
  diasAtencion?: string;
  validarHorarioAtencion: boolean;
  latitudCentral?: number;
  longitudCentral?: number;
  metodosPagoHabilitados: MetodoPago[];
  tiempoEstimadoEntregaMinutos: number;
  umbralRetrasoMinutos: number;
  umbralSinAsignarMinutos: number;
}
