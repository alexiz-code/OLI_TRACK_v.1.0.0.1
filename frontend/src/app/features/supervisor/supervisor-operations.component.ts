import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { OlitrackService } from '../../core/services/olitrack.service';
import { NavbarComponent } from '../../shared/components/navbar/navbar.component';
import { AyacuchoMapComponent } from '../../shared/components/map/ayacucho-map.component';
import { IncidenciasChartsComponent } from '../../shared/components/incidencias-charts.component';

import {
  EstadoPedido,
  Pedido
} from '../../core/models/olitrack.models';

type Vista =
  | 'BANDEJA'
  | 'ALERTAS'
  | 'MAPA'
  | 'REPORTES';

@Component({
  selector: 'app-supervisor-operations',
  standalone: true,
  imports: [
    FormsModule,
    DatePipe,
    NavbarComponent,
    AyacuchoMapComponent,
    IncidenciasChartsComponent
  ],
  templateUrl: './supervisor-operations.component.html'
})
export class SupervisorOperationsComponent implements OnInit {

  // ============================================================
  // SERVICIO
  // ============================================================

  readonly service = inject(OlitrackService);


  // ============================================================
  // VISTA
  // ============================================================

  readonly vista = signal<Vista>('BANDEJA');


  // ============================================================
  // ESTADOS DE PEDIDO
  // ============================================================

  readonly estadosPedido: EstadoPedido[] = [
    'PENDIENTE',
    'CONFIRMADO',
    'EN_PREPARACION',
    'EN_CAMINO',
    'ENTREGADO',
    'ENTREGA_FALLIDA',
    'CANCELADO'
  ];


  // ============================================================
  // FILTRO
  // ============================================================

  filtroEstado?: EstadoPedido;


  // ============================================================
  // RECHAZO
  // ============================================================

  readonly pedidoRechazo =
    signal<Pedido | null>(null);

  observacionesRechazo = '';


  // ============================================================
  // REASIGNACIÓN
  // ============================================================

  readonly pedidoReasignar =
    signal<Pedido | null>(null);

  readonly pedidoEnProceso = signal<string | null>(null);

  nuevoRepartidorId = '';


  // ============================================================
  // INICIO
  // ============================================================

  async ngOnInit(): Promise<void> {

    await this.irA('BANDEJA');

  }


  // ============================================================
  // CLASE DE NAVEGACIÓN
  // ============================================================

  clase(v: Vista): string {

    return this.vista() === v
      ? 'bg-blue-600 text-white font-bold'
      : 'bg-slate-100 text-slate-700';

  }


  // ============================================================
  // ALERTAS
  // ============================================================

  get pedidosRetrasados(): Pedido[] {

    return this.service.alertas()?.retrasados ?? [];

  }


  get pedidosSinAsignar(): Pedido[] {

    return this.service.alertas()?.sinAsignar ?? [];

  }


  totalAlertas(): number {

    return (
      this.pedidosRetrasados.length +
      this.pedidosSinAsignar.length
    );

  }


  // ============================================================
  // CAMBIAR DE VISTA
  // ============================================================

  async irA(v: Vista): Promise<void> {

    this.vista.set(v);


    if (v === 'BANDEJA') {

      await this.cargarBandeja();

    } else if (v === 'ALERTAS') {

      await this.service.reloadAlertas();

    } else if (v === 'MAPA') {

      await Promise.all([
        this.service.reloadRepartidores(),
        this.service.reloadClientes(),
        this.service.reloadConfiguracion(),
        this.service.reloadPedidosAdmin()
      ]);

    } else if (v === 'REPORTES') {

      await Promise.all([
        this.service.reloadDashboard(),
        this.service.reloadProductividad(),
        this.service.reloadIncidencias(),
        this.service.reloadRepartidores()
      ]);

    }

  }


  // ============================================================
  // CARGAR BANDEJA
  // ============================================================

  async cargarBandeja(): Promise<void> {

    await this.service.reloadPedidosAdmin(
      this.filtroEstado
    );

  }


  // ============================================================
  // VALIDAR PEDIDO
  // ============================================================

  async validar(
    pedidoId: string
  ): Promise<void> {

    if (this.pedidoEnProceso() === pedidoId) {
      return;
    }

    this.pedidoEnProceso.set(pedidoId);
    try {
      await this.service.validarPedido(pedidoId);
      await this.cargarBandeja();
    } catch (error) {
      this.service.triggerToast(
        'ERROR',
        'No se pudo validar el pedido',
        this.service.mensajeError(
          error,
          'El pedido pudo cambiar de estado. Actualiza la bandeja e inténtalo de nuevo.'
        )
      );
      await this.cargarBandeja();
    } finally {
      this.pedidoEnProceso.set(null);
    }

  }


  // ============================================================
  // ABRIR RECHAZO
  // ============================================================

  abrirRechazo(
    p: Pedido
  ): void {

    this.observacionesRechazo = '';

    this.pedidoRechazo.set(p);

  }


  // ============================================================
  // CONFIRMAR RECHAZO
  // ============================================================

  async confirmarRechazo(): Promise<void> {

    const p = this.pedidoRechazo();

    if (
      !p ||
      !this.observacionesRechazo.trim() ||
      this.pedidoEnProceso() === p.id
    ) {
      return;
    }

    this.pedidoEnProceso.set(p.id);

    try {
      await this.service.rechazarPedido(
        p.id,
        this.observacionesRechazo.trim()
      );
      this.pedidoRechazo.set(null);
      this.observacionesRechazo = '';
      await this.cargarBandeja();
    } catch (error) {
      this.service.triggerToast(
        'ERROR',
        'No se pudo rechazar el pedido',
        this.service.mensajeError(
          error,
          'El pedido pudo cambiar de estado. Actualiza la bandeja e inténtalo de nuevo.'
        )
      );
      await this.cargarBandeja();
    } finally {
      this.pedidoEnProceso.set(null);
    }

  }


  // ============================================================
  // ASIGNACIÓN AUTOMÁTICA
  // ============================================================

  async asignarAutomatico(
    pedidoId: string
  ): Promise<void> {

    if (this.pedidoEnProceso() === pedidoId) {
      return;
    }
    this.pedidoEnProceso.set(pedidoId);

    try {
      await this.service.asignarRepartidorAutomatico(pedidoId);
      await this.cargarBandeja();
    } catch (error) {
      this.service.triggerToast(
        'WARNING',
        'No se pudo asignar el repartidor',
        this.service.mensajeError(
          error,
          'Verifica que haya un repartidor disponible con ubicación GPS.'
        )
      );
    } finally {
      this.pedidoEnProceso.set(null);
    }

  }


  // ============================================================
  // ABRIR REASIGNACIÓN
  // ============================================================

  async abrirReasignacion(
    p: Pedido
  ): Promise<void> {

    this.nuevoRepartidorId = '';

    try {
      await this.service.reloadRepartidoresDisponibles();
      this.pedidoReasignar.set(p);
    } catch (error) {
      this.service.triggerToast(
        'ERROR',
        'No se pudo cargar la lista de repartidores',
        this.service.mensajeError(
          error,
          'Inténtalo de nuevo en unos momentos.'
        )
      );
    }

  }

  async recargarRepartidoresDisponibles(): Promise<void> {
    try {
      await this.service.reloadRepartidoresDisponibles();
    } catch (error) {
      this.service.triggerToast(
        'ERROR',
        'No se pudo actualizar la lista',
        this.service.mensajeError(
          error,
          'Inténtalo de nuevo en unos momentos.'
        )
      );
    }
  }


  // ============================================================
  // CONFIRMAR REASIGNACIÓN
  // ============================================================

  async confirmarReasignacion(): Promise<void> {

    const p = this.pedidoReasignar();

    if (
      !p ||
      !this.nuevoRepartidorId ||
      this.pedidoEnProceso() === p.id
    ) {
      return;
    }

    this.pedidoEnProceso.set(p.id);

    try {
      if (p.repartidorId) {
        await this.service.reasignarRepartidor(
          p.id,
          this.nuevoRepartidorId
        );
      } else {
        await this.service.asignarRepartidor(
          p.id,
          this.nuevoRepartidorId
        );
      }

      this.pedidoReasignar.set(null);
      this.nuevoRepartidorId = '';
      await this.cargarBandeja();
    } catch (error) {
      this.service.triggerToast(
        'ERROR',
        p.repartidorId
          ? 'No se pudo reasignar el pedido'
          : 'No se pudo asignar el pedido',
        this.service.mensajeError(
          error,
          'Verifica que el repartidor siga disponible e inténtalo de nuevo.'
        )
      );
    } finally {
      this.pedidoEnProceso.set(null);
    }

  }

}
