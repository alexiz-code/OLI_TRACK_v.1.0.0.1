import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  ComparacionRepartidoresResponse,
  RutaResponse
} from '../models/rutas.models';
import { Ubicacion } from '../models/ubicacion.models';

@Injectable({
  providedIn: 'root'
})
export class RutasService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiUrl;

  calcular(origen: Ubicacion, destino: Ubicacion): Promise<RutaResponse> {
    const params = new HttpParams()
      .set('latitudOrigen', origen.latitud)
      .set('longitudOrigen', origen.longitud)
      .set('latitudDestino', destino.latitud)
      .set('longitudDestino', destino.longitud);

    return firstValueFrom(
      this.http.get<RutaResponse>(`${this.base}/rutas/calcular`, { params })
    );
  }

  calcularPedido(pedidoId: string): Promise<RutaResponse> {
    return firstValueFrom(
      this.http.get<RutaResponse>(
        `${this.base}/rutas/pedidos/${encodeURIComponent(pedidoId)}`
      )
    );
  }

  compararRepartidores(destino: Ubicacion): Promise<ComparacionRepartidoresResponse> {
    return firstValueFrom(
      this.http.post<ComparacionRepartidoresResponse>(
        `${this.base}/rutas/comparar-repartidores`,
        {
          latitudDestino: destino.latitud,
          longitudDestino: destino.longitud
        }
      )
    );
  }
}
