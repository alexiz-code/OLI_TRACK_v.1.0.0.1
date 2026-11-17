export interface RutaResponse {
  estado: string;
  distanciaKm: number;
  duracionMinutos: number;
  geometria: Array<{
    latitud: number;
    longitud: number;
  }>;
  repartidorId: string | null;
}

export interface ComparacionRepartidoresResponse {
  estado: string;
  repartidorRecomendadoId: string | null;
  repartidores: Array<{
    repartidorId: string;
    estado: string;
    distanciaKm: number | null;
    duracionMinutos: number | null;
  }>;
}
