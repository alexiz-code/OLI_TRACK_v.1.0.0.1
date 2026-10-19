export interface AuditLog {
  id: string;
  usuarioId: string;
  accion: string;
  entidad: string;
  entidadId: string;
  detalle: string;
  creadoEn: string;
}

export interface Promocion {
  id: string;
  titulo: string;
  descripcion?: string;
  vigenteDesde?: string;
  vigenteHasta?: string;
  activa: boolean;
  creadoEn: string;
}

export interface PromocionRequest {
  titulo: string;
  descripcion?: string;
  vigenteDesde?: string;
  vigenteHasta?: string;
}

export interface Faq {
  id: string;
  pregunta: string;
  respuesta: string;
  orden: number;
  activa: boolean;
}

export interface FaqRequest {
  pregunta: string;
  respuesta: string;
  orden: number;
}
