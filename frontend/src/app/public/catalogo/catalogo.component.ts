import {
  Component,
  afterNextRender,
  inject
} from '@angular/core';

import { HttpClient } from '@angular/common/http';

import { Producto } from '../../core/models/olitrack.models';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-catalogo',
  standalone: true,

  imports: [],

  templateUrl: './catalogo.component.html',

  styleUrl: './catalogo.component.css'
})
export class CatalogoComponent {

  private readonly http = inject(HttpClient);

  productos: Producto[] = [];

  constructor() {
    afterNextRender(() => this.http
      .get<Producto[]>(
        `${environment.apiUrl}/productos`
      )
      .subscribe({

        next: productos => {
          this.productos = productos;
        },

        error: error => {
          console.error(
            'Error al cargar el catálogo:',
            error
          );
        }

      }));
  }
}
