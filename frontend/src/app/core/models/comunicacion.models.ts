export interface Consulta {
  id: string;
  nombre: string;
  email: string;
  asunto: string;
  mensaje: string;
  estado: 'NUEVA' | 'ATENDIDA';
  creadoEn: string;
}

export interface ConsultaCrearRequest {
  nombre: string;
  email: string;
  asunto: string;
  mensaje: string;
}

export interface Notificacion {
  id: string;
  titulo: string;
  mensaje: string;
  leida: boolean;
  creadoEn: string;
}
