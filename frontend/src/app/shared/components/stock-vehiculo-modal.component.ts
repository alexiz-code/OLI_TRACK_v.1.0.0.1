import { ChangeDetectionStrategy, Component, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OlitrackService } from '../../core/services/olitrack.service';
import { Producto } from '../../core/models/producto.models';

@Component({
  selector: 'app-stock-vehiculo-modal', standalone: true, imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="fixed inset-0 z-[1000] flex items-center justify-center bg-slate-950/60 p-3" (click)="cerrar.emit()">
      <section class="max-h-[92vh] w-full max-w-5xl overflow-y-auto rounded-2xl bg-white p-5 shadow-2xl" (click)="$event.stopPropagation()">
        <header class="flex items-start justify-between gap-3"><div><p class="text-xs font-black uppercase tracking-widest text-blue-600">{{ modo() === 'CARGA' ? 'Carga desde inventario principal' : 'Devolución a planta' }}</p><h2 class="mt-1 text-xl font-black text-slate-900">{{ placa() }} · {{ nombreRepartidor() }}</h2></div><button type="button" class="rounded-lg px-3 py-1 text-slate-500 hover:bg-slate-100" (click)="cerrar.emit()">Cerrar</button></header>
        <p class="mt-2 text-sm text-slate-500">Selecciona productos y cantidades. Las cantidades reservadas para pedidos no se pueden devolver.</p>
        <div class="mt-4 space-y-2">
          @for (producto of service.productos(); track producto.id) {
            @let stockVehiculo = actualVehiculo(producto.id);
            @let disponible = modo() === 'CARGA' ? (producto.stockDisponible ?? 0) : stockVehiculo.disponible;
            <div class="grid grid-cols-[52px_1fr_auto] items-center gap-3 rounded-xl border p-3 sm:grid-cols-[64px_1fr_110px_130px]">
              @if (producto.imagenUrl) { <img class="h-12 w-12 rounded-lg object-cover" [src]="producto.imagenUrl" [alt]="producto.nombre" /> } @else { <div class="grid h-12 w-12 place-items-center rounded-lg bg-slate-100 text-xs text-slate-400">IMG</div> }
              <div><p class="font-bold text-slate-800">{{ producto.nombre }}</p><p class="text-xs text-slate-500">{{ producto.unidadMedida }} · Planta: {{ producto.stockDisponible ?? 0 }} · Vehículo: {{ stockVehiculo.disponible }}</p></div>
              <div class="hidden text-right text-xs text-slate-500 sm:block">{{ modo() === 'CARGA' ? 'Máximo planta' : 'Disponible físico' }}<br><b>{{ disponible }}</b></div>
              <div class="col-span-3 flex items-center justify-end gap-2 sm:col-span-1"><button type="button" class="h-9 w-9 rounded-lg border" (click)="ajustar(producto,-1)" [disabled]="cantidad(producto.id) <= 0">−</button><input class="w-16 rounded-lg border px-2 py-2 text-center" type="number" min="0" [max]="disponible" [ngModel]="cantidad(producto.id)" (ngModelChange)="poner(producto,$event)" /><button type="button" class="h-9 w-9 rounded-lg border" (click)="ajustar(producto,1)" [disabled]="cantidad(producto.id) >= disponible">+</button></div>
            </div>
          }
        </div>
        <div class="mt-5 rounded-xl bg-slate-50 p-4"><h3 class="font-black">TRANSFERENCIA DE STOCK</h3><p class="mt-1 text-sm">Origen: {{ modo() === 'CARGA' ? 'Inventario principal' : 'Vehículo ' + placa() }}.</p><p class="text-sm">Destino: {{ modo() === 'CARGA' ? 'Vehículo ' + placa() : 'Inventario principal' }}.</p><p class="text-sm">Repartidor responsable: {{ nombreRepartidor() }}.</p><ul class="mt-2 list-inside list-disc text-sm">@for (linea of seleccionados(); track linea.producto.id) {<li>{{ linea.producto.nombre }}: {{ linea.cantidad }} {{ linea.producto.unidadMedida }}</li>} @empty {<li class="list-none text-slate-400">Selecciona al menos un producto.</li>}</ul></div>
        @if (error()) {<p class="mt-3 rounded-lg bg-red-50 p-3 text-sm text-red-700">{{ error() }}</p>}
        <footer class="mt-4 flex justify-end gap-2"><button type="button" class="rounded-xl border px-4 py-2 font-bold" (click)="cerrar.emit()">Cancelar</button><button type="button" class="rounded-xl bg-blue-700 px-4 py-2 font-bold text-white disabled:opacity-50" (click)="confirmar()" [disabled]="guardando() || !seleccionados().length">{{ guardando() ? 'Procesando…' : modo() === 'CARGA' ? 'Confirmar carga' : 'Confirmar devolución' }}</button></footer>
      </section>
    </div>`
})
export class StockVehiculoModalComponent implements OnInit {
  readonly service = inject(OlitrackService);
  readonly vehiculo = input.required<any>(); readonly nombreRepartidor = input.required<string>();
  readonly modo = input<'CARGA'|'DEVOLUCION'>('CARGA'); readonly cerrar = output<void>(); readonly completado = output<void>();
  readonly stock = signal<any[]>([]); readonly cantidades = signal<Record<string,number>>({}); readonly guardando = signal(false); readonly error = signal('');
  readonly placa = () => this.vehiculo()?.placa ?? 'Sin placa';
  readonly seleccionados = () => this.service.productos().flatMap(producto => this.cantidad(producto.id)>0 ? [{producto,cantidad:this.cantidad(producto.id)}] : []);
  async ngOnInit(): Promise<void> { try { await Promise.all([this.service.reloadProductos(true), this.recargarStock()]); } catch { this.error.set('No se pudo cargar el catálogo o el stock del vehículo.'); } }
  private async recargarStock(): Promise<void> { this.stock.set(await this.service.stockVehiculo(this.vehiculo().id)); }
  actualVehiculo(id: string): any { return this.stock().find(item => item.productoId === id) ?? { disponible: 0 }; }
  cantidad(id: string): number { return this.cantidades()[id] ?? 0; }
  poner(producto: Producto, valor: number): void { const max = this.modo() === 'CARGA' ? producto.stockDisponible ?? 0 : this.actualVehiculo(producto.id).disponible; const n = Math.max(0, Math.min(max, Math.floor(Number(valor) || 0))); this.cantidades.update(v => ({...v,[producto.id]:n})); }
  ajustar(producto: Producto, delta: number): void { this.poner(producto,this.cantidad(producto.id)+delta); }
  async confirmar(): Promise<void> { if (this.guardando()) return; const lineas=this.seleccionados(); if (!lineas.length || lineas.some(l=>l.cantidad<=0)) return; this.guardando.set(true); this.error.set(''); try { const request={operacionId:crypto.randomUUID(),productos:lineas.map(l=>({productoId:l.producto.id,cantidad:l.cantidad})),motivo:this.modo()==='CARGA'?'Carga de planta a vehículo':'Devolución de vehículo a planta'}; if(this.modo()==='CARGA') await this.service.cargarStockVehiculo(this.vehiculo().id,request); else await this.service.devolverStockVehiculo(this.vehiculo().id,request); await Promise.all([this.service.reloadProductos(true),this.recargarStock()]); this.completado.emit(); this.cerrar.emit(); } catch(e:any) { this.error.set(e?.error?.message ?? e?.message ?? 'No se pudo registrar la transferencia.'); } finally { this.guardando.set(false); } }
}
