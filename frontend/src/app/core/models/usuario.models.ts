export type Rol = 'ADMINISTRADOR' | 'SUPERVISOR' | 'CLIENTE' | 'REPARTIDOR';

export interface Usuario {
  id: string;
  nombre: string;
  email: string;
  telefono?: string;
  rol: Rol;
  activo: boolean;
  creadoEn: string;
}

export interface RegistroRequest {
  nombre: string;
  email: string;
  password: string;
  telefono?: string;
  rol: Rol;
}

export interface UsuarioActualizarRequest {
  nombre: string;
  telefono?: string;
}

export interface ResetLinkResponse {
  link: string;
}
