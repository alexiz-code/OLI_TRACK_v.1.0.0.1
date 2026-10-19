
import {
  Component,
  ElementRef,
  OnDestroy,
  ViewChild,
  inject
} from '@angular/core';

import {
  FormBuilder,
  ReactiveFormsModule,
  Validators
} from '@angular/forms';

import { Router, RouterLink } from '@angular/router';

import { AuthService } from '../../core/services/auth.service';
import { OlitrackService } from '../../core/services/olitrack.service';

import type * as Leaflet from 'leaflet';

@Component({
  selector: 'app-registro',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    RouterLink
  ],
  templateUrl: './registro.component.html',
  styleUrl: './registro.component.css'
})
export class RegistroComponent implements OnDestroy {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly olitrackService = inject(OlitrackService);
  private readonly router = inject(Router);

  @ViewChild('mapaRegistro')
  private mapaRegistro?: ElementRef<HTMLDivElement>;

  paso: 1 | 2 = 1;
  cargando = false;
  error = '';
  mensajeCarga = '';
  mostrarMapa = false;

  private leaflet?: typeof import('leaflet');
  private mapa?: Leaflet.Map;
  private marcador?: Leaflet.CircleMarker;
  private temporizadorMapa?: ReturnType<typeof setTimeout>;

  readonly formCuenta = this.fb.nonNullable.group({
    nombre: ['', Validators.required],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(6)]],
    telefono: ['']
  });

  readonly formNegocio = this.fb.nonNullable.group({
    nombreNegocio: ['', Validators.required],
    direccion: ['', Validators.required],
    referencia: [''],
    latitud: [
      -13.1635,
      [Validators.required, Validators.min(-90), Validators.max(90)]
    ],
    longitud: [
      -74.224,
      [Validators.required, Validators.min(-180), Validators.max(180)]
    ]
  });

  // PASO 1: CREAR CUENTA Y MOSTRAR EL PASO 2
  async registrarCuenta(): Promise<void> {
    if (this.cargando) return;

    if (this.formCuenta.invalid) {
      this.formCuenta.markAllAsTouched();
      return;
    }

    this.cargando = true;
    this.mensajeCarga = 'Creando tu cuenta...';
    this.error = '';

    const inicio = performance.now();

    try {
      const datos = this.formCuenta.getRawValue();

      const usuario = await this.authService.registrarCliente({
        nombre: datos.nombre.trim(),
        email: datos.email.trim(),
        password: datos.password,
        telefono: datos.telefono.trim(),
        rol: 'CLIENTE'
      });

      if (!usuario) {
        throw new Error(
          'No se recibió la confirmación de la creación de la cuenta.'
        );
      }

      console.log(
        `[Registro] Cuenta creada en ${
          (performance.now() - inicio).toFixed(0)
        } ms`
      );

      // No iniciar sesión ni crear el perfil del negocio aquí.
      // Mostrar el paso 2 tan pronto se confirme la cuenta.
      this.paso = 2;
      this.error = '';

      console.log(
        `[Registro] Paso 2 activado en ${
          (performance.now() - inicio).toFixed(0)
        } ms`
      );
    } catch (error: unknown) {
      console.error('[Registro] Error creando cuenta:', error);
      this.mostrarErrorRegistro(error);
    } finally {
      this.cargando = false;
      this.mensajeCarga = '';
    }
  }

  volverAlPasoCuenta(): void {
    if (this.cargando) return;

    this.cerrarMapa();
    this.error = '';
    this.paso = 1;
  }

  // PASO 2: INICIAR SESIÓN Y GUARDAR EL PERFIL DEL NEGOCIO
  async completarPerfil(): Promise<void> {
    if (this.cargando) return;

    if (this.formNegocio.invalid) {
      this.formNegocio.markAllAsTouched();
      return;
    }

    this.cargando = true;
    this.error = '';

    try {
      const cuenta = this.formCuenta.getRawValue();
      const negocio = this.formNegocio.getRawValue();

      // Validar coordenadas antes de enviarlas.
      const latitud = Number(negocio.latitud);
      const longitud = Number(negocio.longitud);

      if (
        !Number.isFinite(latitud) ||
        latitud < -90 ||
        latitud > 90 ||
        !Number.isFinite(longitud) ||
        longitud < -180 ||
        longitud > 180
      ) {
        throw new Error('Las coordenadas ingresadas no son válidas.');
      }

      // 1. Iniciar sesión con la cuenta recién creada.
      this.mensajeCarga = 'Iniciando sesión de forma segura...';

      console.time('[Registro] Inicio de sesión');

      await this.authService.login(
        cuenta.email.trim(),
        cuenta.password
      );

      console.timeEnd('[Registro] Inicio de sesión');

      // 2. Obtener el perfil correspondiente a la sesión actual.
      const usuario = this.authService.usuarioActual;

      if (!usuario) {
        throw new Error(
          'No se pudo cargar el usuario autenticado. Inicia sesión nuevamente.'
        );
      }

      if (!usuario.id) {
        throw new Error(
          'El usuario autenticado no tiene un identificador válido.'
        );
      }

      // Comprobar el rol antes de crear un perfil de cliente.
      if (usuario.rol !== 'CLIENTE') {
        throw new Error(
          'La cuenta autenticada no tiene el rol CLIENTE.'
        );
      }

      // 3. Guardar los datos del negocio.
      this.mensajeCarga = 'Guardando los datos de tu negocio...';

      const request = {
        usuarioId: usuario.id,
        nombreNegocio: negocio.nombreNegocio.trim(),
        direccion: negocio.direccion.trim(),
        referencia: negocio.referencia.trim() || undefined,
        ubicacion: {
          latitud,
          longitud
        }
      };

      console.time('[Registro] Creación del perfil de cliente');

      const cliente =
        await this.olitrackService.crearClientePerfil(request);

      console.timeEnd('[Registro] Creación del perfil de cliente');

      if (!cliente?.id) {
        throw new Error(
          'El backend no devolvió un perfil de cliente válido.'
        );
      }

      // 4. Abrir el portal.
      this.mensajeCarga = 'Registro completo. Abriendo tu portal...';

      this.cerrarMapa();

      await this.router.navigate(['/cliente']);
    } catch (error: unknown) {
      console.error('[Registro] Error completando el perfil:', error);
      this.mostrarErrorRegistro(error);
    } finally {
      this.cargando = false;
      this.mensajeCarga = '';
    }
  }

  // MAPA
  abrirMapa(): void {
    if (this.cargando || this.mostrarMapa) return;

    this.mostrarMapa = true;

    this.temporizadorMapa = setTimeout(() => {
      this.temporizadorMapa = undefined;
      void this.inicializarMapa();
    }, 0);
  }

  private async inicializarMapa(): Promise<void> {
    const contenedor = this.mapaRegistro?.nativeElement;

    if (!contenedor || !this.mostrarMapa) return;

    if (this.mapa) {
      this.mapa.invalidateSize();
      return;
    }

    const latitud = Number(this.formNegocio.controls.latitud.value);
    const longitud = Number(this.formNegocio.controls.longitud.value);

    const centroValido =
      Number.isFinite(latitud) &&
      Number.isFinite(longitud) &&
      latitud >= -90 &&
      latitud <= 90 &&
      longitud >= -180 &&
      longitud <= 180;

    const L = await import('leaflet');
    this.leaflet = L;

    const centroInicial: Leaflet.LatLngExpression = centroValido
      ? [latitud, longitud]
      : [-13.1635, -74.224];

    this.mapa = L.map(contenedor).setView(centroInicial, 16);

    L.tileLayer(
      'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
      {
        maxZoom: 19,
        attribution: '&copy; OpenStreetMap contributors'
      }
    ).addTo(this.mapa);

    this.marcador = L.circleMarker(centroInicial, {
      radius: 9,
      color: '#166534',
      weight: 3,
      fillColor: '#22c55e',
      fillOpacity: 0.9
    }).addTo(this.mapa);

    this.marcador.bindTooltip('Ubicación del negocio');

    this.mapa.on('click', (evento: Leaflet.LeafletMouseEvent) => {
      this.seleccionarUbicacion(
        evento.latlng.lat,
        evento.latlng.lng
      );
    });

    setTimeout(() => {
      this.mapa?.invalidateSize();
    }, 100);
  }

  private seleccionarUbicacion(
    latitud: number,
    longitud: number
  ): void {
    const lat = Number(latitud.toFixed(6));
    const lng = Number(longitud.toFixed(6));

    this.formNegocio.patchValue({
      latitud: lat,
      longitud: lng
    });

    this.marcador?.setLatLng([lat, lng]);

    console.log('[Registro] Ubicación seleccionada:', {
      latitud: lat,
      longitud: lng
    });
  }

  cerrarMapa(): void {
    this.mostrarMapa = false;

    if (this.temporizadorMapa !== undefined) {
      clearTimeout(this.temporizadorMapa);
      this.temporizadorMapa = undefined;
    }

    this.mapa?.remove();
    this.mapa = undefined;
    this.marcador = undefined;
  }

  ngOnDestroy(): void {
    this.cerrarMapa();
  }

  // MENSAJES DE ERROR
  private mostrarErrorRegistro(error: unknown): void {
    const code =
      typeof error === 'object' &&
      error !== null &&
      'code' in error
        ? String((error as { code?: unknown }).code ?? '')
        : '';

    switch (code) {
      case 'auth/email-already-in-use':
        this.error = 'El correo electrónico ya está registrado.';
        return;

      case 'auth/invalid-email':
        this.error = 'El correo electrónico no es válido.';
        return;

      case 'auth/weak-password':
        this.error = 'La contraseña debe tener al menos 6 caracteres.';
        return;

      case 'auth/invalid-credential':
      case 'auth/wrong-password':
      case 'auth/user-not-found':
        this.error = 'El correo o la contraseña no son válidos.';
        return;

      case 'auth/network-request-failed':
        this.error = 'No se pudo conectar con el servicio de autenticación.';
        return;
    }

    if (error instanceof Error) {
      this.error = error.message || 'No se pudo completar el registro.';
      return;
    }

    if (typeof error === 'object' && error !== null && 'error' in error) {
      const httpError = (error as {
        error?: { message?: string; mensaje?: string; error?: string } | string
      }).error;

      if (typeof httpError === 'string' && httpError.trim()) {
        this.error = httpError;
        return;
      }

      if (typeof httpError === 'object' && httpError !== null) {
        this.error =
          httpError.message ||
          httpError.mensaje ||
          httpError.error ||
          'No se pudo completar el registro.';
        return;
      }
    }

    this.error = 'No se pudo completar el registro.';
  }
}
