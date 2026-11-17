import { TestBed } from '@angular/core/testing';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { RutasService } from './rutas.service';

describe('RutasService', () => {
  let service: RutasService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(RutasService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('solicita al backend la ruta vigente de un pedido asignado', async () => {
    const response = {
      estado: 'OK',
      distanciaKm: 3.1,
      duracionMinutos: 9,
      geometria: [
        { latitud: -13.16, longitud: -74.22 },
        { latitud: -13.155, longitud: -74.215 },
        { latitud: -13.15, longitud: -74.21 }
      ],
      repartidorId: 'rep-1'
    };
    const result = service.calcularPedido('pedido/uno');

    const request = http.expectOne(
      req => req.url.endsWith('/rutas/pedidos/pedido%2Funo')
    );
    expect(request.request.method).toBe('GET');
    request.flush(response);

    await expect(result).resolves.toEqual(response);
  });

  it('solicita comparar repartidores por la ruta y el tiempo del viaje', async () => {
    const response = {
      estado: 'OK',
      repartidorRecomendadoId: 'rep-1',
      repartidores: [{
        repartidorId: 'rep-1',
        estado: 'OK',
        distanciaKm: 1.2,
        duracionMinutos: 4
      }]
    };
    const result = service.compararRepartidores({
      latitud: -13.16,
      longitud: -74.22
    });

    const request = http.expectOne(
      req => req.url.endsWith('/rutas/comparar-repartidores')
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      latitudDestino: -13.16,
      longitudDestino: -74.22
    });
    request.flush(response);

    await expect(result).resolves.toEqual(response);
  });
});
