import { HttpInterceptorFn } from '@angular/common/http';

import { inject } from '@angular/core';

import { from, switchMap } from 'rxjs';

import { AuthService } from '../services/auth.service';

import { environment } from '../../../environments/environment';

export const tokenInterceptor: HttpInterceptorFn = (req, next) => {

  // Solo interceptar peticiones dirigidas al backend de OLITRACK.
  if (!req.url.startsWith(environment.apiUrl)) {
    return next(req);
  }

  // Rutas públicas del backend.
  const rutasPublicas = [
    '/auth/registro',
    '/auth/login'
  ];

  const esRutaPublica = rutasPublicas.some(
    ruta => req.url.includes(ruta)
  );

  if (esRutaPublica) {
    return next(req);
  }

  const authService = inject(AuthService);

  return from(
    authService.obtenerIdToken()
  ).pipe(
    switchMap(token => {

      if (!token) {
        return next(req);
      }

      return next(
        req.clone({
          setHeaders: {
            Authorization: `Bearer ${token}`
          }
        })
      );
    })
  );
};