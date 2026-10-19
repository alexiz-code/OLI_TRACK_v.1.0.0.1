import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { OlitrackService } from '../../../core/services/olitrack.service';
import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'app-navbar',
  standalone: true,
  imports: [RouterLink],

  templateUrl: './navbar.component.html'
})
export class NavbarComponent {

  readonly service = inject(OlitrackService);

  private readonly authService = inject(AuthService);

  async cerrarSesion(): Promise<void> {
    await this.authService.logout();
  }
}
