import { Rol } from '../../../core/models/usuario.models';

export interface ProductoForm {
  nombre: string;
  descripcion: string;
  categoria: string;
  precioUnitario: number;
  unidadMedida: string;
  imagenUrl: string;
  stockInicial: number;
}

export interface UsuarioForm {
  nombre: string;
  email: string;
  password: string;
  telefono: string;
  rol: Rol;
  placaVehiculo: string;
}

export interface UsuarioEdicionForm {
  nombre: string;
  telefono: string;
}

export const USUARIO_VACIO = (): UsuarioForm => ({
  nombre: '',
  email: '',
  password: '',
  telefono: '',
  rol: 'CLIENTE',
  placaVehiculo: ''
});

export const PRODUCTO_VACIO = (): ProductoForm => ({
  nombre: '',
  descripcion: '',
  categoria: '',
  precioUnitario: 0,
  unidadMedida: '',
  imagenUrl: '',
  stockInicial: 0
});
