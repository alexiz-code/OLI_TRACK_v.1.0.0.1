
import {
  Component,
  ElementRef,
  OnDestroy,
  ViewChild,
  inject,
  signal
} from '@angular/core';

import {
  FormBuilder,
  ReactiveFormsModule,
  Validators
} from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';

import { Router, RouterLink } from '@angular/router';

import { AuthService } from '../../core/services/auth.service';
import { OlitrackService } from '../../core/services/olitrack.service';
import { Usuario } from '../../core/models/olitrack.models';

import type * as Leaflet from 'leaflet';

type ResultadoAutenticacionRegistro =
  | { exitoso: true }
  | { exitoso: false; error: unknown };

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

  @ViewChild('fotoNegocioInput')
  private fotoNegocioInput?: ElementRef<HTMLInputElement>;

  paso: 1 | 2 = 1;
  cargando = false;
  error = '';
  errorMapaRegistro = '';
  mensajeCarga = '';
  mostrarMapa = false;

  private leaflet?: typeof import('leaflet');
  private mapa?: Leaflet.Map;
  private marcador?: Leaflet.CircleMarker;
  private observadorTamanoMapa?: ResizeObserver;
  private generacionMapa = 0;
  private generacionGeolocalizacion = 0;
  private temporizadorMapa?: ReturnType<typeof setTimeout>;
  private usuarioRegistrado: Usuario | null = null;
  private preparacionAutenticacion: Promise<ResultadoAutenticacionRegistro> | null = null;
  readonly buscandoUbicacion = signal(false);
  readonly errorUbicacionActual = signal('');
  readonly fotoNegocio = signal<File | null>(null);
  readonly vistaPreviaFotoNegocio = signal<string | null>(null);
  readonly errorFotoNegocio = signal('');
  private urlVistaPreviaFotoNegocio: string | null = null;

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
    this.mensajeCarga = 'Creando tu cuenta. Puedes adelantar los datos del negocio...';
    this.error = '';

    const inicio = performance.now();
    const datos = this.formCuenta.getRawValue();
    this.paso = 2;

    try {
      const usuario = await this.authService.registrarCliente({
        nombre: datos.nombre.trim(),
        email: datos.email.trim(),
        password: datos.password,
        telefono: datos.telefono.trim() || undefined,
        rol: 'CLIENTE'
      });

      if (!usuario) {
        throw new Error(
          'No se recibió la confirmación de la creación de la cuenta.'
        );
      }

      this.usuarioRegistrado = usuario;
      this.preparacionAutenticacion = this.autenticarCuentaRegistrada(
        datos.email,
        datos.password
      );

      console.log(
        `[Registro] Cuenta creada en ${
          (performance.now() - inicio).toFixed(0)
        } ms`
      );

      this.error = '';

      console.log(
        `[Registro] Paso 2 activado en ${
          (performance.now() - inicio).toFixed(0)
        } ms`
      );
    } catch (error: unknown) {
      this.paso = 1;
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
    this.quitarFotoNegocio();
    this.error = '';
    this.paso = 1;
    this.usuarioRegistrado = null;
    this.preparacionAutenticacion = null;
  }

  private autenticarCuentaRegistrada(
    email: string,
    password: string
  ): Promise<ResultadoAutenticacionRegistro> {
    return this.authService.login(
      email.trim(),
      password
    ).then(
      () => ({ exitoso: true as const }),
      error => ({ exitoso: false as const, error })
    );
  }

  // PASO 2: GUARDAR EL PERFIL DEL NEGOCIO
  async completarPerfil(): Promise<void> {
    if (this.cargando) return;

    if (this.formNegocio.invalid) {
      this.formNegocio.markAllAsTouched();
      return;
    }

    this.cargando = true;
    this.error = '';

    try {
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

      this.mensajeCarga = 'Verificando tu cuenta...';
      const resultadoAutenticacion = await (
        this.preparacionAutenticacion ??
        this.autenticarCuentaRegistrada(
          this.formCuenta.controls.email.value,
          this.formCuenta.controls.password.value
        )
      );

      if (!resultadoAutenticacion.exitoso) {
        this.preparacionAutenticacion = null;
        throw resultadoAutenticacion.error;
      }

      // 2. Obtener el perfil correspondiente a la sesión actual.
      const usuario = this.authService.usuarioActual;

      if (!usuario || usuario.id !== this.usuarioRegistrado?.id) {
        throw new Error(
          'La cuenta autenticada no coincide con la cuenta registrada. Inicia sesión nuevamente.'
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

      const foto = this.fotoNegocio();
      const cliente = foto
        ? await this.olitrackService.crearClientePerfilConFoto(request, foto)
        : await this.olitrackService.crearClientePerfil(request);

      console.timeEnd('[Registro] Creación del perfil de cliente');

      if (!cliente?.id) {
        throw new Error(
          'El backend no devolvió un perfil de cliente válido.'
        );
      }

      // 4. Abrir el portal.
      this.mensajeCarga = 'Registro completo. Abriendo tu portal...';

      this.cerrarMapa();
      this.quitarFotoNegocio();

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
    this.errorMapaRegistro = '';

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

    const generacion = this.generacionMapa;

    try {
      const leafletModule = await import('leaflet');
      const leafletInterop = leafletModule as typeof import('leaflet') & {
        default?: typeof import('leaflet');
      };
      const L = leafletInterop.default ?? leafletModule;

      if (generacion !== this.generacionMapa || !this.mostrarMapa) return;
      if (typeof L.map !== 'function') {
        throw new Error('Leaflet no se inicializó correctamente.');
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

      if (typeof ResizeObserver !== 'undefined') {
        this.observadorTamanoMapa = new ResizeObserver(() => {
          this.mapa?.invalidateSize();
        });
        this.observadorTamanoMapa.observe(contenedor);
      }

      requestAnimationFrame(() => this.mapa?.invalidateSize());
      setTimeout(() => this.mapa?.invalidateSize(), 250);
    } catch (error) {
      this.mapa?.remove();
      this.mapa = undefined;
      this.marcador = undefined;
      this.leaflet = undefined;
      this.errorMapaRegistro =
        'No se pudo cargar el mapa. Revisa tu conexión e inténtalo de nuevo.';
      console.error('[Registro] Error inicializando el mapa:', error);
    }
  }

  usarUbicacionActual(): void {
    this.errorUbicacionActual.set('');

    if (typeof window !== 'undefined' && !window.isSecureContext) {
      this.errorUbicacionActual.set(
        'La ubicación actual requiere una conexión segura HTTPS.'
      );
      return;
    }

    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      this.errorUbicacionActual.set(
        'Este navegador no permite obtener la ubicación actual.'
      );
      return;
    }

    this.buscandoUbicacion.set(true);
    const generacion = ++this.generacionGeolocalizacion;

    try {
      navigator.geolocation.getCurrentPosition(
        position => {
          if (generacion !== this.generacionGeolocalizacion) return;

          this.buscandoUbicacion.set(false);
          const { latitude, longitude } = position.coords;
          this.seleccionarUbicacion(latitude, longitude);
          this.mapa?.setView([latitude, longitude], 17);
        },
        error => {
          if (generacion !== this.generacionGeolocalizacion) return;

          this.buscandoUbicacion.set(false);
          this.errorUbicacionActual.set(
            this.mensajeErrorGeolocalizacion(error)
          );
        },
        {
          enableHighAccuracy: true,
          timeout: 15_000,
          maximumAge: 60_000
        }
      );
    } catch (error) {
      if (generacion !== this.generacionGeolocalizacion) return;

      this.buscandoUbicacion.set(false);
      this.errorUbicacionActual.set(
        'No se pudo solicitar la ubicación actual.'
      );
      console.error('[Registro] Error solicitando ubicación:', error);
    }
  }

  private mensajeErrorGeolocalizacion(
    error: GeolocationPositionError
  ): string {
    switch (error.code) {
      case error.PERMISSION_DENIED:
        return 'Permite el acceso a la ubicación en tu navegador para continuar.';
      case error.POSITION_UNAVAILABLE:
        return 'No se pudo determinar tu ubicación. Revisa el GPS o la señal.';
      case error.TIMEOUT:
        return 'La búsqueda de ubicación tardó demasiado. Inténtalo otra vez.';
      default:
        return 'No se pudo obtener tu ubicación actual.';
    }
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

  seleccionarFotoNegocio(event: Event): void {
    const input = event.target as HTMLInputElement;
    const archivo = input.files?.[0];
    this.errorFotoNegocio.set('');

    if (!archivo) return;

    this.revocarVistaPreviaFotoNegocio();
    this.fotoNegocio.set(null);

    const tiposPermitidos = ['image/jpeg', 'image/png', 'image/webp'];
    if (!tiposPermitidos.includes(archivo.type)) {
      this.errorFotoNegocio.set('Selecciona una foto JPG, PNG o WEBP.');
      input.value = '';
      return;
    }

    if (archivo.size === 0 || archivo.size > 5 * 1024 * 1024) {
      this.errorFotoNegocio.set(
        'La foto debe tener contenido y no superar 5 MB.'
      );
      input.value = '';
      return;
    }

    this.fotoNegocio.set(archivo);
    this.urlVistaPreviaFotoNegocio = URL.createObjectURL(archivo);
    this.vistaPreviaFotoNegocio.set(this.urlVistaPreviaFotoNegocio);
  }

  quitarFotoNegocio(): void {
    this.revocarVistaPreviaFotoNegocio();
    this.fotoNegocio.set(null);
    this.vistaPreviaFotoNegocio.set(null);
    this.errorFotoNegocio.set('');
    if (this.fotoNegocioInput) {
      this.fotoNegocioInput.nativeElement.value = '';
    }
  }

  private revocarVistaPreviaFotoNegocio(): void {
    if (this.urlVistaPreviaFotoNegocio) {
      URL.revokeObjectURL(this.urlVistaPreviaFotoNegocio);
      this.urlVistaPreviaFotoNegocio = null;
    }
    this.vistaPreviaFotoNegocio.set(null);
  }

  cerrarMapa(): void {
    this.mostrarMapa = false;
    this.errorMapaRegistro = '';
    this.generacionMapa++;
    this.generacionGeolocalizacion++;
    this.buscandoUbicacion.set(false);
    this.errorUbicacionActual.set('');

    if (this.temporizadorMapa !== undefined) {
      clearTimeout(this.temporizadorMapa);
      this.temporizadorMapa = undefined;
    }

    this.observadorTamanoMapa?.disconnect();
    this.observadorTamanoMapa = undefined;
    this.mapa?.remove();
    this.mapa = undefined;
    this.marcador = undefined;
  }

  ngOnDestroy(): void {
    this.cerrarMapa();
    this.revocarVistaPreviaFotoNegocio();
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

    if (error instanceof HttpErrorResponse) {
      this.error = this.olitrackService.mensajeError(
        error,
        'No se pudo completar el registro.'
      );
      return;
    }

    this.error = error instanceof Error && error.message
      ? error.message
      : 'No se pudo completar el registro.';
  }
}
