
import {
  Component,
  DestroyRef,
  afterNextRender,
  inject,
  signal
} from '@angular/core';

import { CurrencyPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { AuthService } from '../../core/services/auth.service';
import { OlitrackService } from '../../core/services/olitrack.service';
import { Producto, Usuario } from '../../core/models/olitrack.models';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [
    CurrencyPipe,
    RouterLink
  ],
  templateUrl: './home.component.html'
})
export class HomeComponent {

  readonly authService = inject(AuthService);
  readonly service = inject(OlitrackService);

  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);

  readonly usuarioActual = signal<Usuario | null>(null);
  readonly cargandoSesion = signal(true);

  readonly productosDestacados = signal<Producto[]>([]);
  readonly cargandoProductos = signal(true);
  readonly errorProductos = signal('');

  constructor() {
    /*
     * Las suscripciones del estado de sesión se realizan
     * después del primer renderizado del componente.
     *
     * Esto evita que las emisiones iniciales de los
     * BehaviorSubject cambien las condiciones de la
     * plantilla durante la comprobación inicial.
     */
    afterNextRender(() => {

      this.cargarProductos();

      this.authService.cargandoSesion$
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe(cargando => {
          this.cargandoSesion.set(cargando);
        });

      this.authService.usuarioActual$
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe(usuario => {
          this.usuarioActual.set(usuario);
        });

    });
  }

  cargarProductos(): void {
    this.cargandoProductos.set(true);
    this.errorProductos.set('');

    this.http
      .get<Producto[]>(`${environment.apiUrl}/productos`)
      .pipe(
        catchError(error => {

          console.error(
            '[HomeComponent] Error cargando productos:',
            error
          );

          this.errorProductos.set(
            error?.status === 0
              ? 'No se pudo conectar con el servidor. Comprueba que el backend esté ejecutándose.'
              : 'No se pudieron cargar los productos. Inténtalo nuevamente.'
          );

          return of([] as Producto[]);
        }),

        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe(productos => {

        this.productosDestacados.set(
          productos.slice(0, 6)
        );

        this.cargandoProductos.set(false);
      });
  }

  rutaCuenta(rol: string | undefined): string {
    switch (rol) {
      case 'ADMINISTRADOR':
        return '/admin';

      case 'SUPERVISOR':
        return '/supervisor';

      case 'REPARTIDOR':
        return '/repartidor';

      case 'CLIENTE':
      default:
        return '/cliente';
    }
  }
}
