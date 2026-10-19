import {
  Component,
  inject
} from '@angular/core';

import {
  CommonModule
} from '@angular/common';

import {
  FormBuilder,
  ReactiveFormsModule,
  Validators
} from '@angular/forms';

import {
  Router,
  RouterLink
} from '@angular/router';

import {
  AuthService
} from '../../core/services/auth.service';

import {
  Usuario
} from '../../core/models/olitrack.models';

@Component({
  selector: 'app-login',

  standalone: true,

  imports: [
    CommonModule,
    ReactiveFormsModule,
    RouterLink
  ],

  templateUrl: './login.component.html',

  styleUrl: './login.component.css'
})
export class LoginComponent {

  /* =====================================
     INYECCIÓN DE DEPENDENCIAS
     ===================================== */

  private readonly fb = inject(FormBuilder);

  private readonly authService =
    inject(AuthService);

  private readonly router =
    inject(Router);


  /* =====================================
     FORMULARIO
     ===================================== */

  readonly form = this.fb.nonNullable.group({

    email: [
      '',
      [
        Validators.required,
        Validators.email
      ]
    ],

    password: [
      '',
      [
        Validators.required
      ]
    ]

  });


  /* =====================================
     ESTADO
     ===================================== */

  cargando = false;

  error = '';


  /* =====================================
     INGRESAR
     ===================================== */

  async ingresar(): Promise<void> {

    /*
     * Verificar formulario.
     */

    if (this.form.invalid) {

      this.form.markAllAsTouched();

      return;
    }


    /*
     * Activar estado de carga.
     */

    this.cargando = true;

    this.error = '';


    /*
     * Obtener datos.
     */

    const {
      email,
      password
    } = this.form.getRawValue();


    try {

      /*
       * Iniciar sesión en Firebase.
       */

      await this.authService.login(
        email.trim(),
        password
      );


      /*
       * Firebase puede haber autenticado
       * al usuario antes de que
       * AuthService termine de cargar
       * /usuarios/me.
       *
       * Esperamos el perfil.
       */

      const usuario =
        await this.esperarUsuario();


      /*
       * Verificar perfil.
       */

      if (!usuario) {

        this.error =
          'No se pudo cargar el perfil del usuario.';

        return;
      }


      /*
       * Redireccionar según rol.
       */

      switch (usuario.rol) {

        case 'ADMINISTRADOR':

          await this.router.navigate([
            '/admin'
          ]);

          break;


        case 'SUPERVISOR':

          await this.router.navigate([
            '/supervisor'
          ]);

          break;


        case 'REPARTIDOR':

          await this.router.navigate([
            '/repartidor'
          ]);

          break;


        case 'CLIENTE':

          await this.router.navigate([
            '/cliente'
          ]);

          break;


        default:

          this.error =
            'El usuario no tiene un rol válido.';

          break;
      }


    } catch (error: unknown) {

      /*
       * Obtener código del error.
       */

      const code =
        this.obtenerCodigoError(error);


      /*
       * Mostrar mensaje amigable.
       */

      this.error =
        this.mapearError(code);


    } finally {

      this.cargando = false;

    }
  }


  /* =====================================
     ESPERAR USUARIO
     ===================================== */

  private async esperarUsuario():
    Promise<Usuario | null> {

    /*
     * Primero comprobar si ya existe
     * el usuario.
     */

    const usuarioActual =
      this.authService.usuarioActual;


    if (usuarioActual) {

      return usuarioActual;

    }


    /*
     * Esperar usuarioActual$.
     */

    return new Promise<Usuario | null>(
      (resolve) => {

        let resuelto = false;


        const subscription =
          this.authService.usuarioActual$
            .subscribe(usuario => {

              /*
               * El perfil ya fue cargado.
               */

              if (
                usuario &&
                !resuelto
              ) {

                resuelto = true;

                subscription.unsubscribe();

                resolve(usuario);

              }

            });


        /*
         * Tiempo máximo de espera:
         * 10 segundos.
         */

        setTimeout(() => {

          if (!resuelto) {

            resuelto = true;

            subscription.unsubscribe();

            resolve(
              this.authService.usuarioActual
            );

          }

        }, 10000);

      }
    );
  }


  /* =====================================
     OBTENER CÓDIGO DE ERROR
     ===================================== */

  private obtenerCodigoError(
    error: unknown
  ): string {

    if (
      typeof error === 'object' &&
      error !== null &&
      'code' in error
    ) {

      const errorConCodigo =
        error as {
          code?: unknown
        };


      return String(
        errorConCodigo.code ?? ''
      );
    }


    return '';
  }


  /* =====================================
     MAPEAR ERROR FIREBASE
     ===================================== */

  private mapearError(
    code: string
  ): string {

    switch (code) {

      case 'auth/invalid-credential':

      case 'auth/wrong-password':

      case 'auth/user-not-found':

        return (
          'Correo o contraseña incorrectos.'
        );


      case 'auth/invalid-email':

        return (
          'El correo electrónico no es válido.'
        );


      case 'auth/user-disabled':

        return (
          'Esta cuenta se encuentra deshabilitada.'
        );


      case 'auth/too-many-requests':

        return (
          'Demasiados intentos. ' +
          'Intenta nuevamente más tarde.'
        );


      case 'auth/network-request-failed':

        return (
          'No se pudo conectar con Firebase. ' +
          'Verifica tu conexión a Internet.'
        );


      default:

        return (
          'No se pudo iniciar sesión. ' +
          'Verifica tus datos.'
        );
    }
  }
}
