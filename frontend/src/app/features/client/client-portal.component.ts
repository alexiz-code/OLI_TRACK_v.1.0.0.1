import {
  Component,
  OnInit,
  computed,
  inject,
  signal
} from '@angular/core';

import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { OlitrackService } from '../../core/services/olitrack.service';
import { NavbarComponent } from '../../shared/components/navbar/navbar.component';
import { AyacuchoMapComponent } from '../../shared/components/map/ayacucho-map.component';
import { PedidoEvidenciaComponent } from '../../shared/components/pedido-evidencia.component';
import { MetodoPago } from '../../core/models/olitrack.models';

@Component({
  selector: 'app-client-portal',
  standalone: true,
  imports: [
    FormsModule,
    DatePipe,
    NavbarComponent,
    AyacuchoMapComponent,
    PedidoEvidenciaComponent
  ],
  templateUrl: './client-portal.component.html',
  styleUrl: './client-portal.component.css'
})
export class ClientPortalComponent implements OnInit {
  readonly service = inject(OlitrackService);

  readonly activeTab = signal<'CATALOGO' | 'MIS_PEDIDOS'>('CATALOGO');
  readonly showCheckoutModal = signal(false);
  readonly showProfileModal = signal(false);
  readonly confirmando = signal(false);
  readonly perfilNoExiste = signal(false);
  readonly guardandoPerfil = signal(false);
  readonly obteniendoUbicacion = signal(false);
  readonly guardandoEdicion = signal(false);

  readonly metodos = computed<MetodoPago[]>(
    () => this.service.companyConfig().metodosPagoHabilitados ?? []
  );

  readonly selectedMetodo = signal<MetodoPago>('YAPE');

  errorPerfil = '';
  errorCheckout = '';
  errorComprobante = '';

  checkoutDireccion = '';
  checkoutObservaciones = '';

  profileNombreNegocio = '';
  profileDireccion = '';
  profileReferencia = '';

  latitudPerfil: number | null = null;
  longitudPerfil: number | null = null;

  async ngOnInit(): Promise<void> {
    // Cargar el catálogo.
    try {
      await this.service.reloadProductos();
    } catch (error: unknown) {
      console.error('Error al cargar productos:', error);
    }

    try {
      await this.service.reloadConfiguracion();
    } catch (error: unknown) {
      console.error('Error al cargar configuración:', error);
    }

    // Cargar el perfil una sola vez.
    await this.cargarPerfilCliente();

    const disponibles = this.metodos();
    if (disponibles.length && !disponibles.includes(this.selectedMetodo())) {
      this.selectedMetodo.set(disponibles[0]);
    }
  }

  private async cargarPerfilCliente(): Promise<void> {
    try {
      await this.service.reloadClienteProfile();

      const perfil = this.service.clienteProfile();

      if (perfil) {
        this.checkoutDireccion = perfil.direccion;
        this.profileNombreNegocio = perfil.nombreNegocio;
        this.profileDireccion = perfil.direccion;
        this.profileReferencia = perfil.referencia ?? '';

        this.latitudPerfil = perfil.ubicacion?.latitud ?? null;
        this.longitudPerfil = perfil.ubicacion?.longitud ?? null;
      }

      this.perfilNoExiste.set(false);
      this.errorPerfil = '';
    } catch (error: unknown) {
      console.error('No se pudo cargar el perfil:', error);

      if (error instanceof HttpErrorResponse && error.status === 404) {
        this.perfilNoExiste.set(true);
        this.errorPerfil = '';
      } else {
        this.perfilNoExiste.set(false);
        this.errorPerfil = this.service.mensajeError(
          error,
          'No se pudo cargar tu perfil. Intenta nuevamente.'
        );
      }
    }
  }

  async cambiarAMisPedidos(): Promise<void> {
    this.activeTab.set('MIS_PEDIDOS');

    try {
      await this.service.reloadMisPedidos();
    } catch (error: unknown) {
      console.error('Error al cargar pedidos:', error);
    }

    try {
      await this.service.reloadRepartidoresDisponibles();
    } catch (error: unknown) {
      console.error('Error al cargar repartidores:', error);
    }

    try {
      await this.service.reloadConfiguracion();
      const disponibles = this.metodos();
      if (disponibles.length && !disponibles.includes(this.selectedMetodo())) {
        this.selectedMetodo.set(disponibles[0]);
      }
    } catch (error: unknown) {
      console.error('Error al cargar configuración:', error);
    }
  }

  obtenerUbicacionPerfil(): void {
    if (
      typeof navigator === 'undefined' ||
      !navigator.geolocation
    ) {
      this.errorPerfil =
        'Tu navegador no permite obtener la ubicación automáticamente.';
      return;
    }

    this.obteniendoUbicacion.set(true);
    this.errorPerfil = '';

    navigator.geolocation.getCurrentPosition(
      (position: GeolocationPosition) => {
        this.latitudPerfil = Number(
          position.coords.latitude.toFixed(7)
        );

        this.longitudPerfil = Number(
          position.coords.longitude.toFixed(7)
        );

        this.errorPerfil = '';
        this.obteniendoUbicacion.set(false);
      },
      (error: GeolocationPositionError) => {
        console.error('Error de geolocalización:', error);

        switch (error.code) {
          case error.PERMISSION_DENIED:
            this.errorPerfil =
              'Debes permitir el acceso a la ubicación en el navegador.';
            break;

          case error.POSITION_UNAVAILABLE:
            this.errorPerfil =
              'No se pudo determinar tu ubicación. Ingresa las coordenadas manualmente.';
            break;

          case error.TIMEOUT:
            this.errorPerfil =
              'Se agotó el tiempo para obtener la ubicación. Inténtalo nuevamente.';
            break;

          default:
            this.errorPerfil =
              'Ocurrió un error al obtener la ubicación.';
        }

        this.obteniendoUbicacion.set(false);
      },
      {
        enableHighAccuracy: true,
        timeout: 15000,
        maximumAge: 0
      }
    );
  }

  async crearPerfilInicial(): Promise<void> {
    const usuario = this.service.currentUser();

    if (!usuario?.id) {
      this.errorPerfil =
        'No se pudo identificar al usuario. Vuelve a iniciar sesión.';
      return;
    }

    const nombreNegocio = this.profileNombreNegocio.trim();
    const direccion = this.profileDireccion.trim();
    const referencia = this.profileReferencia.trim();

    if (!nombreNegocio || !direccion) {
      this.errorPerfil =
        'El nombre del negocio y la dirección son obligatorios.';
      return;
    }

    const latitud = this.latitudPerfil;
    const longitud = this.longitudPerfil;

    if (
      latitud === null ||
      longitud === null ||
      !Number.isFinite(latitud) ||
      !Number.isFinite(longitud) ||
      latitud < -90 ||
      latitud > 90 ||
      longitud < -180 ||
      longitud > 180
    ) {
      this.errorPerfil =
        'Obtén la ubicación o ingresa una latitud y longitud válidas.';
      return;
    }

    if (this.guardandoPerfil()) {
      return;
    }

    this.guardandoPerfil.set(true);
    this.errorPerfil = '';

    try {
      await this.service.crearClientePerfil({
        usuarioId: usuario.id,
        nombreNegocio,
        direccion,
        ...(referencia ? { referencia } : {}),
        ubicacion: {
          latitud,
          longitud
        }
      });

      const perfil = this.service.clienteProfile();

      if (perfil) {
        this.checkoutDireccion = perfil.direccion;
        this.profileNombreNegocio = perfil.nombreNegocio;
        this.profileDireccion = perfil.direccion;
        this.profileReferencia = perfil.referencia ?? '';
        this.latitudPerfil = perfil.ubicacion?.latitud ?? null;
        this.longitudPerfil = perfil.ubicacion?.longitud ?? null;
      }

      this.perfilNoExiste.set(false);
      this.errorPerfil = '';

      this.service.triggerToast(
        'SUCCESS',
        'Perfil creado',
        'Tu perfil de cliente se registró correctamente.'
      );
    } catch (error: unknown) {
      console.error('Error al crear el perfil:', error);

      this.errorPerfil = this.service.mensajeError(
        error,
        'No se pudo crear el perfil de cliente.'
      );
    } finally {
      this.guardandoPerfil.set(false);
    }
  }

  openCheckout(): void {
    this.errorCheckout = '';

    const perfil = this.service.clienteProfile();

    if (!perfil) {
      this.errorCheckout =
        'Primero debes completar tu perfil de cliente.';
      return;
    }

    if (perfil.direccion && !this.checkoutDireccion.trim()) {
      this.checkoutDireccion = perfil.direccion;
    }

    if (this.metodos().length === 0) {
      this.errorCheckout =
        'No hay métodos de pago disponibles. Contacta con la empresa para completar el pedido.';
      this.showCheckoutModal.set(true);
      return;
    }

    this.showCheckoutModal.set(true);
  }

  async confirmOrder(): Promise<void> {
    const perfil = this.service.clienteProfile();

    if (!perfil) {
      this.errorCheckout = 'No se encontró el perfil del cliente.';
      return;
    }

    if (!this.checkoutDireccion.trim()) {
      this.errorCheckout = 'Ingresa una dirección de entrega.';
      return;
    }

    if (this.service.cart().length === 0) {
      this.errorCheckout = 'El carrito está vacío.';
      return;
    }

    if (!this.metodos().includes(this.selectedMetodo())) {
      this.errorCheckout =
        'Selecciona un método de pago disponible para continuar.';
      return;
    }

    if (this.confirmando()) {
      return;
    }

    this.confirmando.set(true);
    this.errorCheckout = '';

    try {
      await this.service.createOrder({
        direccionEntrega: this.checkoutDireccion.trim(),
        ubicacionEntrega: perfil.ubicacion,
        observaciones: this.checkoutObservaciones.trim() || undefined,
        metodoPago: this.selectedMetodo()
      });

      this.showCheckoutModal.set(false);
      this.checkoutObservaciones = '';

      await this.cambiarAMisPedidos();
    } catch (error: unknown) {
      console.error('Error al crear pedido:', error);

      this.errorCheckout = this.obtenerMensajeError(
        error,
        'No se pudo crear el pedido.'
      );
    } finally {
      this.confirmando.set(false);
    }
  }

  async subirComprobante(
    pedidoId: string,
    event: Event
  ): Promise<void> {
    const input = event.target as HTMLInputElement;
    const archivo = input.files?.[0];

    if (!archivo) {
      return;
    }

    const tiposPermitidos = [
      'image/jpeg',
      'image/png',
      'image/webp',
      'application/pdf'
    ];

    if (!tiposPermitidos.includes(archivo.type)) {
      this.errorComprobante = 'Selecciona un archivo JPG, PNG, WEBP o PDF.';
      input.value = '';
      return;
    }

    if (archivo.size === 0 || archivo.size > 2 * 1024 * 1024) {
      this.errorComprobante = 'El comprobante debe tener contenido y no superar 2 MB.';
      input.value = '';
      return;
    }

    this.errorComprobante = '';

    try {
      await this.service.subirEvidencia(pedidoId, archivo);
      await this.service.reloadMisPedidos();
    } catch (error: unknown) {
      console.error('Error al subir comprobante:', error);

      this.errorComprobante = this.obtenerMensajeError(
        error,
        'No se pudo subir el comprobante.'
      );
    } finally {
      input.value = '';
    }
  }

  async calificar(
    pedidoId: string,
    estrellas: number
  ): Promise<void> {
    try {
      await this.service.calificarPedido(pedidoId, estrellas);
      await this.service.reloadMisPedidos();
    } catch (error: unknown) {
      console.error('Error al calificar pedido:', error);
    }
  }

  async cancelar(pedidoId: string): Promise<void> {
    if (!confirm('¿Seguro que deseas cancelar este pedido?')) {
      return;
    }

    try {
      await this.service.cancelarPedido(pedidoId);
      await this.service.reloadMisPedidos();
    } catch (error: unknown) {
      console.error('Error al cancelar pedido:', error);
      this.service.triggerToast(
        'ERROR',
        'No se pudo cancelar el pedido',
        this.obtenerMensajeError(
          error,
          'Verifica que el pedido te pertenezca y siga en estado pendiente o confirmado.'
        )
      );
    }
  }

  async saveProfileChanges(): Promise<void> {
    if (
      !this.profileNombreNegocio.trim() ||
      !this.profileDireccion.trim()
    ) {
      this.service.triggerToast(
        'ERROR',
        'Datos incompletos',
        'El nombre del negocio y la dirección son obligatorios.'
      );
      return;
    }

    if (this.guardandoEdicion()) {
      return;
    }

    this.guardandoEdicion.set(true);

    try {
      await this.service.updateClienteProfile({
        nombreNegocio: this.profileNombreNegocio.trim(),
        direccion: this.profileDireccion.trim(),
        referencia: this.profileReferencia.trim() || undefined
      });

      await this.service.reloadClienteProfile();

      const perfil = this.service.clienteProfile();

      if (perfil) {
        this.checkoutDireccion = perfil.direccion;
        this.profileNombreNegocio = perfil.nombreNegocio;
        this.profileDireccion = perfil.direccion;
        this.profileReferencia = perfil.referencia ?? '';
      }

      this.showProfileModal.set(false);

      this.service.triggerToast(
        'SUCCESS',
        'Perfil actualizado',
        'Tus datos se guardaron correctamente.'
      );
    } catch (error: unknown) {
      console.error('Error al actualizar perfil:', error);

      this.service.triggerToast(
        'ERROR',
        'Error al actualizar',
        this.obtenerMensajeError(
          error,
          'No se pudieron guardar tus datos.'
        )
      );
    } finally {
      this.guardandoEdicion.set(false);
    }
  }

  private obtenerMensajeError(
    error: unknown,
    mensajePredeterminado: string
  ): string {
    if (error instanceof HttpErrorResponse) {
      const cuerpo = error.error;

      if (typeof cuerpo === 'string' && cuerpo.trim()) {
        return cuerpo;
      }

      if (cuerpo && typeof cuerpo === 'object') {
        const mensaje =
          cuerpo.mensaje ??
          cuerpo.message ??
          cuerpo.error;

        if (typeof mensaje === 'string' && mensaje.trim()) {
          return mensaje;
        }
      }

      return error.message || mensajePredeterminado;
    }

    if (error instanceof Error) {
      return error.message;
    }

    return mensajePredeterminado;
  }
}
