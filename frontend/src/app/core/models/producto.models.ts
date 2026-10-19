export interface Producto {
  id: string;
  nombre: string;
  descripcion?: string;
  categoria: string;
  precioUnitario: number;
  unidadMedida: string;
  imagenUrl?: string;
  activo: boolean;
  creadoEn: string;
  stockDisponible?: number;
}

export interface ProductoRequest {
  nombre: string;
  descripcion?: string;
  categoria: string;
  precioUnitario: number;
  unidadMedida: string;
  imagenUrl?: string;
  /** Stock inicial opcional; el backend crea el inventario en cero si se omite. */
  stockInicial?: number;
}
