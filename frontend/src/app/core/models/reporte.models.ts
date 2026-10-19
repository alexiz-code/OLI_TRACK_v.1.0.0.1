export interface DashboardResponse {
  pedidosTotales: number;
  pedidosEntregados: number;
  tiempoPromedioMinutos: number;
  pedidosConDemora: number;
  ingresosTotales: number;
  resumenPorDia: VentaPorDia[];
}

export interface VentaPorDia {
  fecha: string;
  pedidos: number;
  entregados: number;
  conDemora: number;
  ingresos: number;
}

export interface ProductoMasVendido {
  productoId: string;
  nombre: string;
  cantidadVendida: number;
  ingresoGenerado: number;
}

export interface Productividad {
  repartidorId: string;
  pedidosAsignados: number;
  pedidosEntregados: number;
  entregasFallidas: number;
  tiempoPromedioMinutos: number;
  incidencias: number;
}
