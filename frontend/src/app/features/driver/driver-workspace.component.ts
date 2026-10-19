import {
  Component,
  OnInit,
  computed,
  inject,
  signal
} from '@angular/core';

import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';

import { OlitrackService } from '../../core/services/olitrack.service';

import { NavbarComponent } from '../../shared/components/navbar/navbar.component';

import { AyacuchoMapComponent } from '../../shared/components/map/ayacucho-map.component';

import {
  Pedido,
  TipoIncidencia,
  EstadoRepartidor
} from '../../core/models/olitrack.models';

@Component({
  selector: 'app-driver-workspace',
  standalone: true,

  imports: [
    CommonModule,
    FormsModule,
    NavbarComponent,
    AyacuchoMapComponent
  ],

  templateUrl: './driver-workspace.component.html'
})
export class DriverWorkspaceComponent implements OnInit {

  readonly service =
    inject(OlitrackService);

  readonly tieneEntregasActivas = computed(() =>
    this.service.pedidos().some(pedido =>
      pedido.estado !== 'ENTREGADO' &&
      pedido.estado !== 'CANCELADO'
    )
  );

  readonly guardandoEstado = signal(false);


  readonly miRepartidorComoLista =
    computed(() => {

      const perfil =
        this.service.repartidorProfile();

      return perfil
        ? [perfil]
        : [];

    });


  readonly pedidoFallido =
    signal<Pedido | null>(null);

  readonly pedidoIncidencia =
    signal<Pedido | null>(null);

  readonly perfilRepartidorFaltante = signal(false);

  readonly tiposIncidencia: {
    value: TipoIncidencia;
    label: string;
  }[] = [
    { value: 'MECANICA', label: 'Falla mecánica' },
    { value: 'TRAFICO', label: 'Tráfico' },
    { value: 'OPERATIVO', label: 'Problema operativo' },
    { value: 'RETRASO', label: 'Retraso' },
    { value: 'PRODUCTO_DANADO', label: 'Producto dañado' },
    { value: 'DIRECCION_INCORRECTA', label: 'Dirección incorrecta' },
    { value: 'CLIENTE_AUSENTE', label: 'Cliente ausente' },
    { value: 'OTRO', label: 'Otro' }
  ];

  motivoFallido = '';
  tipoIncidencia: TipoIncidencia = 'MECANICA';
  descripcionIncidencia = '';


  async ngOnInit(): Promise<void> {

    await Promise.all([
      this.service.reloadClientes(),
      this.service.reloadConfiguracion()
    ]);

    try {
      await this.service.iniciarSesionRepartidor();
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 404) {
        this.perfilRepartidorFaltante.set(true);
        return;
      }
      console.error('Error al cargar el perfil del repartidor:', error);
      this.service.triggerToast(
        'ERROR',
        'No se pudo cargar el perfil',
        this.service.mensajeError(error, 'Intenta recargar la página.')
      );
      return;
    }

    try {
      await this.service.reloadMisEntregas();
    } catch (error) {
      console.error('Error al cargar las entregas:', error);
      this.service.triggerToast(
        'ERROR',
        'No se pudieron cargar las entregas',
        this.service.mensajeError(error, 'Intenta recargar la página.')
      );
    }

    await this.actualizarMiPosicion(false);

  }


  puedeAceptar(p: Pedido): boolean {

    return (
      !p.entregaAceptada &&
      (
        p.estado === 'CONFIRMADO' ||
        p.estado === 'EN_PREPARACION'
      )
    );

  }


  async cambiarMiEstado(
    estado: EstadoRepartidor
  ): Promise<void> {

    this.guardandoEstado.set(true);

    try {
      await this.service.cambiarMiEstadoRepartidor(estado);
      await this.service.reloadRepartidorProfile();
    } catch (error) {
      console.error('Error al cambiar el estado del repartidor:', error);
      this.service.triggerToast(
        'ERROR',
        'No se pudo cambiar tu disponibilidad',
        this.service.mensajeError(
          error,
          'Verifica si tienes entregas activas e inténtalo de nuevo.'
        )
      );
      try {
        await this.service.reloadRepartidorProfile();
      } catch (reloadError) {
        console.error('No se pudo restaurar el estado del repartidor:', reloadError);
      }
    } finally {
      this.guardandoEstado.set(false);
    }

  }


  async aceptar(
    pedidoId: string
  ): Promise<void> {

    try {
      await Promise.all([
        this.service.reloadRepartidorProfile(),
        this.service.reloadMisEntregas()
      ]);

      const perfil = this.service.repartidorProfile();
      const pedido = this.service.pedidos().find(item => item.id === pedidoId);
      if (!perfil || !pedido || pedido.repartidorId !== perfil.id) {
        this.service.triggerToast(
          'WARNING',
          'Este pedido está asignado a otra cuenta',
          'Actualizamos tus entregas. Inicia sesión con la cuenta del repartidor que figura en la asignación.'
        );
        return;
      }

      await this.service.aceptarEntrega(pedidoId);
      await this.service.reloadMisEntregas();
    } catch (error) {
      console.error('No se pudo aceptar la entrega:', error);
      if (error instanceof HttpErrorResponse && error.status === 403) {
        try {
          await Promise.all([
            this.service.reloadRepartidorProfile(),
            this.service.reloadMisEntregas()
          ]);
        } catch (reloadError) {
          console.error('No se pudo actualizar la bandeja del repartidor:', reloadError);
        }
        this.service.triggerToast(
          'ERROR',
          'La asignación no coincide con tu cuenta',
          'Se actualizó tu bandeja. Verifica que hayas iniciado sesión con la cuenta del repartidor asignado al pedido.'
        );
        return;
      }

      this.service.triggerToast(
        'ERROR',
        'No se pudo aceptar la entrega',
        this.service.mensajeError(error, 'Intenta actualizar la bandeja y vuelve a intentarlo.')
      );
    }

  }


  async salirARuta(
    pedidoId: string
  ): Promise<void> {

    try {
      await this.service.changeOrderStatus(
        pedidoId,
        'EN_CAMINO'
      );

      await this.service.reloadMisEntregas();
    } catch (error) {
      console.error('Error al iniciar la ruta de entrega:', error);
      this.service.triggerToast(
        'ERROR',
        'No se pudo iniciar la entrega',
        this.service.mensajeError(
          error,
          'Verifica que hayas aceptado el pedido y vuelve a intentarlo.'
        )
      );
    }

  }


  async marcarLlegada(
    pedidoId: string
  ): Promise<void> {

    await this.service.registrarLlegada(
      pedidoId
    );

    await this.service.reloadMisEntregas();

  }


  async cambiarEstado(
    pedidoId: string,
    estado: 'ENTREGADO'
  ): Promise<void> {

    await this.service.changeOrderStatus(
      pedidoId,
      estado
    );

    await this.service.reloadMisEntregas();

  }


  abrirEntregaFallida(
    pedido: Pedido
  ): void {

    this.motivoFallido = '';

    this.pedidoFallido.set(
      pedido
    );

  }


  async confirmarEntregaFallida(): Promise<void> {

    const pedido =
      this.pedidoFallido();

    const motivo =
      this.motivoFallido.trim();

    if (
      !pedido ||
      !motivo
    ) {
      return;
    }

    await this.service.registrarEntregaFallida(
      pedido.id,
      motivo
    );

    this.pedidoFallido.set(null);

    this.motivoFallido = '';

    await this.service.reloadMisEntregas();

  }


  async actualizarMiPosicion(notificar = true): Promise<void> {

    const perfil = this.service.repartidorProfile();
    if (!perfil) {
      return;
    }

    if (!navigator.geolocation) {
      this.service.triggerToast(
        'WARNING',
        'GPS no disponible',
        'Este navegador no soporta geolocalización; no se podrá calcular el repartidor más cercano.'
      );
      return;
    }

    try {
      const posicion = await new Promise<GeolocationPosition>((resolve, reject) =>
        navigator.geolocation.getCurrentPosition(resolve, reject, {
          enableHighAccuracy: true,
          timeout: 15000,
          maximumAge: 0
        })
      );

      await this.service.updateDriverLocation(
        perfil.id,
        posicion.coords.latitude,
        posicion.coords.longitude
      );
      await this.service.reloadRepartidorProfile();
      await this.service.reloadMisEntregas();

      if (notificar) {
        this.service.triggerToast(
          'INFO',
          'GPS actualizado',
          'Tu ubicación quedó disponible para asignarte pedidos cercanos.'
        );
      }
    } catch (error) {
      console.error('No se pudo actualizar la ubicación GPS:', error);
      this.service.triggerToast(
        'WARNING',
        'Ubicación no disponible',
        'Permite el acceso al GPS del navegador para que el sistema pueda asignarte automáticamente el repartidor más cercano.'
      );
    }
  }


  abrirReporteIncidencia(pedidoId: string): void {
    const pedido = this.service.pedidos().find(item => item.id === pedidoId);
    if (!pedido) {
      this.service.triggerToast(
        'ERROR',
        'No se encontró el pedido',
        'Actualiza tus entregas e inténtalo nuevamente.'
      );
      return;
    }

    this.tipoIncidencia = 'MECANICA';
    this.descripcionIncidencia = '';
    this.pedidoIncidencia.set(pedido);
  }

  async confirmarReporteIncidencia(): Promise<void> {
    const pedido = this.pedidoIncidencia();
    const descripcion = this.descripcionIncidencia.trim();
    if (!pedido || !descripcion) {
      return;
    }

    try {
      await this.service.registrarIncidencia(
        pedido.id,
        this.tipoIncidencia,
        descripcion
      );
      this.pedidoIncidencia.set(null);
      this.descripcionIncidencia = '';
    } catch (error) {
      console.error('Error al reportar la incidencia:', error);
      this.service.triggerToast(
        'ERROR',
        'No se pudo reportar la incidencia',
        this.service.mensajeError(
          error,
          'Verifica que el pedido esté asignado a tu cuenta y vuelve a intentarlo.'
        )
      );
    }
  }

}
