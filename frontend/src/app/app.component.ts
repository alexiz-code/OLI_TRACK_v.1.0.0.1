import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * Sin navbar global aqui a proposito: el Home (visitante) ya trae su propia
 * barra de marketing (logo + Catálogo/Contáctanos/Iniciar sesión). El navbar
 * de "usuario autenticado" (con nombre/rol/logout) se incluye dentro de cada
 * area protegida (admin, cliente, repartidor), no en toda la app.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet],
  templateUrl: './app.component.html'
})
export class AppComponent {
}
