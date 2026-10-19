import { Ubicacion } from './ubicacion.models';

export type EstadoRepartidor = 'DISPONIBLE' | 'EN_RUTA' | 'DESCONECTADO';

export interface Repartidor {
  id: string;
  usuarioId: string;
  placaVehiculo: string;
  estado: EstadoRepartidor;
  ubicacionActual?: Ubicacion;
  activo: boolean;
  creadoEn: string;
  nombreUsuario?: string;
  emailUsuario?: string;
}
