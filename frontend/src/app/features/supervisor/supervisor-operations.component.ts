import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { OlitrackService } from '../../core/services/olitrack.service';
import { NavbarComponent } from '../../shared/components/navbar/navbar.component';
import { AyacuchoMapComponent } from '../../shared/components/map/ayacucho-map.component';
import { IncidenciasChartsComponent } from '../../shared/components/incidencias-charts.component';
import { PedidoEvidenciaComponent } from '../../shared/components/pedido-evidencia.component';
import { PedidoAsignacionModalComponent } from '../../shared/components/pedido-asignacion-modal.component';
import { PedidoRepartidorModalComponent } from '../../shared/components/pedido-repartidor-modal.component';
import { StockVehiculoModalComponent } from '../../shared/components/stock-vehiculo-modal.component';
import { AdminVehiclesComponent } from '../admin/admin-vehicles.component';

import {
  EstadoPedido,
  Pedido,
  Cliente
} from '../../core/models/olitrack.models';

type Vista =
  | 'BANDEJA'
  | 'ALERTAS'
  | 'MAPA'
  | 'FLOTA'
  | 'REPORTES';

@Component({
  selector: 'app-supervisor-operations',
  standalone: true,
  imports: [
    FormsModule,
    DatePipe,
    DecimalPipe,
    NavbarComponent,
    AyacuchoMapComponent,
    IncidenciasChartsComponent,
    PedidoEvidenciaComponent,
    PedidoAsignacionModalComponent,
    PedidoRepartidorModalComponent,
    StockVehiculoModalComponent,
    AdminVehiclesComponent
  ],
  templateUrl: './supervisor-operations.component.html'
})
export class SupervisorOperationsComponent implements OnInit {

  // ============================================================
  // SERVICIO
  // ============================================================

  readonly service = inject(OlitrackService);
  readonly ventasLibres = signal<any[]>([]);


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

  readonly selectedClienteId = signal<string | undefined>(undefined);
  readonly selectedRepartidorId = signal<string | undefined>(undefined);
  readonly vehiculoParaStock = signal<any | null>(null);
  readonly modoTransferencia = signal<'CARGA'|'DEVOLUCION'>('CARGA');
  readonly clienteParaAsignar = signal<Cliente | null>(null);
  readonly pedidoParaRepartidor = signal<Pedido | null>(null);

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
        this.service.reloadUsuarios(),
        this.service.reloadProductos(),
        this.service.reloadConfiguracion(),
        this.service.reloadPedidosAdmin()
      ]);

    } else if (v === 'FLOTA') {
      await Promise.all([this.service.reloadRepartidores(), this.service.reloadProductos(true), this.service.reloadPedidosAdmin()]);

    } else if (v === 'REPORTES') {

      await Promise.all([
        this.service.reloadDashboard(),
        this.service.reloadIngresosDiariosRepartidor(),
        this.service.reloadProductividad(),
        this.service.reloadIncidencias(),
        this.service.reloadRepartidores(),
        this.service.reloadProductos(),
        this.service.listarVentasLibres().then(ventas => this.ventasLibres.set(ventas))
      ]);

    }

  }

  productoDeVenta(productoId: string): string {
    return this.service.productos().find(producto => producto.id === productoId)?.nombre ?? productoId;
  }

  repartidorDeVenta(repartidorId: string): string {
    return this.service.repartidores().find(repartidor => repartidor.id === repartidorId)?.nombreUsuario ?? repartidorId;
  }

  seleccionarCliente(cliente: Cliente): void {
    this.selectedClienteId.set(cliente.id);
  }

  repartidorSeleccionado() { return this.service.repartidores().find(item => item.id === this.selectedRepartidorId()) ?? null; }

  async abrirTransferenciaStock(modo: 'CARGA'|'DEVOLUCION' = 'CARGA'): Promise<void> {
    const repartidor=this.service.repartidores().find(item=>item.id===this.selectedRepartidorId());
    if(!repartidor) return;
    try {
      const vehiculo=(await this.service.listarVehiculos()).find(item=>item.repartidorId===repartidor.id);
      if(!vehiculo?.placa) { this.service.triggerToast('WARNING','Vehículo no asignado','El repartidor no tiene un vehículo con placa asignado.'); return; }
      this.modoTransferencia.set(modo); this.vehiculoParaStock.set(vehiculo);
    } catch { this.service.triggerToast('ERROR','Error de inventario','No se pudo consultar el vehículo asignado.'); }
  }

  abrirAsignacionPedido(cliente: Cliente): void {
    if (cliente.activo) this.clienteParaAsignar.set(cliente);
  }

  usuarioDeCliente(cliente: Cliente): string {
    return this.service.users().find(usuario => usuario.id === cliente.usuarioId)?.nombre ?? '';
  }

  pedidoCreadoEnMapa(pedido: Pedido): void {
    this.pedidoParaRepartidor.set(pedido);
  }

  async asignacionConfirmada(): Promise<void> {
    this.pedidoParaRepartidor.set(null);
    await Promise.all([this.service.reloadPedidosAdmin(), this.service.reloadRepartidores()]);
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
