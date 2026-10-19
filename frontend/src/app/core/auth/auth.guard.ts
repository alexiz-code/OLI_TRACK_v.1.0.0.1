import { inject } from '@angular/core';
import {
  CanActivateFn,
  Router,
  UrlTree
} from '@angular/router';

import {
  filter,
  map,
  take
} from 'rxjs';

import { AuthService } from '../services/auth.service';

/**
 * Espera hasta que Firebase termine de resolver
 * el estado inicial de la sesión.
 */
function esperarSesionResuelta(
  authService: AuthService
) {
  return authService.cargandoSesion$.pipe(
    filter(cargando => !cargando),
    take(1)
  );
}


export const authGuard: CanActivateFn = () => {

  const authService = inject(AuthService);
  const router = inject(Router);

  return esperarSesionResuelta(authService).pipe(

    map(() => {

      if (authService.estaAutenticado()) {
        return true;
      }

      return router.createUrlTree(['/login']);
    })

  );
};


export function roleGuard(
  ...rolesPermitidos: string[]
): CanActivateFn {

  return () => {

    const authService = inject(AuthService);
    const router = inject(Router);

    return esperarSesionResuelta(authService).pipe(

      map(() => {

        // Si no está autenticado,
        // enviar al login.
        if (!authService.estaAutenticado()) {
          return router.createUrlTree(['/login']);
        }

        // Si tiene alguno de los roles permitidos,
        // puede ingresar.
        if (
          authService.tieneRol(...rolesPermitidos)
        ) {
          return true;
        }

        // Usuario autenticado pero sin permisos.
        return router.createUrlTree(['/']);
      })

    );
  };
}
