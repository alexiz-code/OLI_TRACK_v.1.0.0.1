import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService } from '../../core/services/auth.service';
import { OlitrackService } from '../../core/services/olitrack.service';
import { RegistroComponent } from './registro.component';

describe('RegistroComponent geolocation', () => {
  beforeEach(async () => {
    Object.defineProperty(window, 'isSecureContext', {
      configurable: true,
      value: true
    });

    await TestBed.configureTestingModule({
      imports: [RegistroComponent],
      providers: [
        { provide: AuthService, useValue: {} },
        { provide: OlitrackService, useValue: {} },
        provideRouter([])
      ]
    }).compileComponents();
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('updates the business coordinates to the current location', () => {
    const position: GeolocationPosition = {
      coords: {
        latitude: -13.17,
        longitude: -74.23,
        accuracy: 5,
        altitude: null,
        altitudeAccuracy: null,
        heading: null,
        speed: null,
        toJSON: () => ({ latitude: -13.17, longitude: -74.23 })
      },
      timestamp: 0,
      toJSON: () => ({})
    };

    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: {
        getCurrentPosition: (success: PositionCallback) => success(position)
      }
    });

    const fixture = TestBed.createComponent(RegistroComponent);
    const component = fixture.componentInstance;

    component.usarUbicacionActual();

    expect(component.formNegocio.controls.latitud.value).toBe(-13.17);
    expect(component.formNegocio.controls.longitud.value).toBe(-74.23);
    expect(component.buscandoUbicacion()).toBe(false);
    expect(component.errorUbicacionActual()).toBe('');
  });

  it('shows a Spanish message when location permission is denied', () => {
    const permissionError = {
      code: 1,
      message: 'Permission denied',
      PERMISSION_DENIED: 1,
      POSITION_UNAVAILABLE: 2,
      TIMEOUT: 3
    } satisfies GeolocationPositionError;

    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: {
        getCurrentPosition: (
          _success: PositionCallback,
          error: PositionErrorCallback
        ) => error(permissionError)
      }
    });

    const fixture = TestBed.createComponent(RegistroComponent);
    const component = fixture.componentInstance;

    component.usarUbicacionActual();

    expect(component.buscandoUbicacion()).toBe(false);
    expect(component.errorUbicacionActual()).toContain(
      'Permite el acceso a la ubicación'
    );
  });

  it('rejects a business photo larger than 5 MB', () => {
    const fixture = TestBed.createComponent(RegistroComponent);
    const component = fixture.componentInstance;
    const file = new File(
      [new Uint8Array(5 * 1024 * 1024 + 1)],
      'negocio.jpg',
      { type: 'image/jpeg' }
    );
    const input = document.createElement('input');
    Object.defineProperty(input, 'files', { value: [file] });
    const event = new Event('change', { bubbles: true });
    Object.defineProperty(event, 'target', { value: input });

    component.seleccionarFotoNegocio(event);

    expect(component.fotoNegocio()).toBeNull();
    expect(component.errorFotoNegocio()).toContain('no superar 5 MB');
  });

  it('rejects unsupported image formats with a Spanish message', () => {
    const fixture = TestBed.createComponent(RegistroComponent);
    const component = fixture.componentInstance;
    component.fotoNegocio.set(
      new File(['anterior'], 'anterior.jpg', { type: 'image/jpeg' })
    );
    const file = new File(['image'], 'negocio.gif', { type: 'image/gif' });
    const input = document.createElement('input');
    Object.defineProperty(input, 'files', { value: [file] });
    const event = new Event('change', { bubbles: true });
    Object.defineProperty(event, 'target', { value: input });

    component.seleccionarFotoNegocio(event);

    expect(component.fotoNegocio()).toBeNull();
    expect(component.errorFotoNegocio()).toContain('JPG, PNG o WEBP');
  });
});
