import { Producto } from './producto.models';

export interface CartItem {
  producto: Producto;
  cantidad: number;
}

export interface ToastMessage {
  id: string;
  tipo: 'INFO' | 'SUCCESS' | 'WARNING' | 'ERROR';
  titulo: string;
  mensaje: string;
  tiempo: string;
}

export const EMPRESA_CONFIG_DEFAULT = {
  nombre: 'OLI FRESH ICE',
  direccionCentral: 'Ayacucho, Peru',
  latitudCentral: -13.1635,
  longitudCentral: -74.2240
};
