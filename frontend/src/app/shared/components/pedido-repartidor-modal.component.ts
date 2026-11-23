import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { Pedido, Repartidor } from '../../core/models/olitrack.models';
import { OlitrackService } from '../../core/services/olitrack.service';
import { RutasService } from '../../core/services/rutas.service';

@Component({
  selector: 'app-pedido-repartidor-modal',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="fixed inset-0 z-[1150] flex items-center justify-center bg-slate-900/60 p-3" role="dialog" aria-modal="true">
      <section class="max-h-[92vh] w-full max-w-2xl space-y-4 overflow-y-auto rounded-2xl bg-white p-5 shadow-2xl">
        <div class="flex items-start justify-between gap-3">
          <div><p class="text-xs font-bold uppercase tracking-wide text-blue-600">Pedido #{{ pedido().id.slice(-6) }}</p><h2 class="mt-1 text-lg font-black text-slate-800">Buscar repartidor disponible</h2><p class="mt-1 text-xs text-slate-500">El pedido permanece pendiente hasta que confirmes una asignación.</p></div>
          <button type="button" (click)="cerrar.emit()" class="rounded-lg px-3 py-2 text-sm font-bold text-slate-600">Cerrar</button>
        </div>
        @if (cargando()) { <p role="status" class="rounded-xl bg-blue-50 p-3 text-sm text-blue-700">Comprobando disponibilidad, stock y rutas por calles…</p> }
        @if (error()) { <p role="alert" class="rounded-xl bg-amber-50 p-3 text-sm text-amber-800">{{ error() }}</p> }
        <div class="space-y-2">
          @for (opcion of elegibles(); track opcion.repartidor.id) {
            <article class="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-slate-200 p-3">
              <div><p class="font-bold text-slate-800">{{ opcion.repartidor.nombreUsuario || opcion.repartidor.placaVehiculo }} · {{ opcion.repartidor.placaVehiculo }}</p><p class="text-xs text-slate-500">{{ opcion.minutos | number:'1.0-0' }} min · {{ opcion.km | number:'1.1-2' }} km · stock suficiente</p></div>
              <button type="button" (click)="confirmar(opcion.repartidor)" [disabled]="asignando()" class="rounded-xl bg-emerald-600 px-4 py-2 text-sm font-bold text-white disabled:opacity-50">{{ asignando() && seleccionado() === opcion.repartidor.id ? 'Asignando…' : 'Confirmar asignación' }}</button>
            </article>
          } @empty {
            @if (!cargando()) { <p class="rounded-xl border border-dashed border-slate-300 p-4 text-sm text-slate-600">No hay repartidores elegibles con disponibilidad, GPS, stock para todos los productos y ruta OSRM. El pedido sigue pendiente.</p> }
          }
        </div>
      </section>
    </div>
  `
})
export class PedidoRepartidorModalComponent implements OnInit {
  readonly pedido = input.required<Pedido>();
  readonly cerrar = output<void>();
  readonly asignado = output<void>();
  private readonly service = inject(OlitrackService);
  private readonly rutas = inject(RutasService);
  readonly cargando = signal(true);
  readonly asignando = signal(false);
  readonly seleccionado = signal('');
  readonly error = signal('');
  private readonly vehiculos = signal<any[]>([]);
  private readonly tiempos = signal<any[]>([]);

  readonly elegibles = computed(() => {
    const repartidores = this.service.repartidores();
    const vehiculos = this.vehiculos();
    return this.tiempos().flatMap(tiempo => {
      if (tiempo.duracionMinutos === null || tiempo.distanciaKm === null) return [];
      const repartidor = repartidores.find(item => item.id === tiempo.repartidorId);
      const vehiculo = vehiculos.find(item => item.repartidorId === tiempo.repartidorId);
      if (!repartidor || !vehiculo || !vehiculo.activo || repartidor.estado !== 'DISPONIBLE' || !repartidor.activo) return [];
      const suficiente = this.pedido().productos.every(linea =>
        Number(vehiculo.stock?.find((stock: any) => stock.productoId === linea.productoId)?.disponible ?? 0) >= linea.cantidad
      );
      return suficiente ? [{ repartidor, minutos: tiempo.duracionMinutos ?? 0, km: tiempo.distanciaKm ?? 0 }] : [];
    });
  });

  async ngOnInit(): Promise<void> {
    const destino = this.pedido().ubicacionEntrega;
    if (!destino || !Number.isFinite(destino.latitud) || !Number.isFinite(destino.longitud)) {
      this.error.set('El pedido no tiene coordenadas de entrega válidas para calcular rutas.');
      this.cargando.set(false);
      return;
    }
    try {
      const [, vehiculos, comparison] = await Promise.all([
        this.service.reloadRepartidoresDisponibles(),
        this.service.listarVehiculos(),
        this.rutas.compararRepartidores(destino)
      ]);
      this.vehiculos.set(vehiculos);
      this.tiempos.set(comparison.repartidores ?? []);
      if (!this.elegibles().length) this.error.set('No se encontraron repartidores con stock completo y una ruta OSRM válida.');
    } catch (error) {
      this.error.set(this.service.mensajeError(error, 'No se pudo consultar repartidores, stock o rutas.'));
    } finally {
      this.cargando.set(false);
    }
  }

  async confirmar(repartidor: Repartidor): Promise<void> {
    this.asignando.set(true);
    this.seleccionado.set(repartidor.id);
    this.error.set('');
    try {
      await this.service.asignarRepartidor(this.pedido().id, repartidor.id);
      this.service.triggerToast('SUCCESS', 'Repartidor asignado', 'Se confirmó la asignación y el backend reservó el stock del vehículo.');
      this.asignado.emit();
      this.cerrar.emit();
    } catch (error) {
      this.error.set(this.service.mensajeError(error, 'La asignación no se confirmó. El pedido conserva su estado pendiente.'));
    } finally {
      this.asignando.set(false);
      this.seleccionado.set('');
    }
  }
}
