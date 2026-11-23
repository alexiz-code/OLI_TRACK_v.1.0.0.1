import { Component, inject, input, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Cliente, Pedido, Producto } from '../../core/models/olitrack.models';
import { OlitrackService } from '../../core/services/olitrack.service';

@Component({
  selector: 'app-pedido-asignacion-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="fixed inset-0 z-[1100] flex items-center justify-center bg-slate-900/60 p-3 sm:p-6" role="dialog" aria-modal="true">
      <div class="max-h-[92vh] w-full max-w-5xl overflow-hidden rounded-2xl bg-white shadow-2xl">
        <header class="flex items-start justify-between border-b border-slate-200 px-5 py-4">
          <div>
            <p class="text-xs font-bold uppercase tracking-wide text-blue-600">Nuevo pedido</p>
            <h2 class="mt-1 text-lg font-black text-slate-800">Nuevo pedido — {{ nombreCliente() || cliente().nombreNegocio }}</h2>
            <p class="text-xs text-slate-500">{{ cliente().nombreNegocio }}</p>
            <p class="mt-1 text-xs text-slate-500">{{ cliente().direccion }}</p>
          </div>
          <button type="button" (click)="cerrar.emit()" class="rounded-lg px-3 py-2 text-sm font-bold text-slate-600 hover:bg-slate-100">Cerrar</button>
        </header>

        <div class="grid max-h-[calc(92vh-72px)] grid-cols-1 overflow-y-auto lg:grid-cols-[1fr_320px]">
          <div class="p-4 sm:p-5">
            <label class="block text-xs font-semibold text-slate-700">
              Buscar productos
              <input type="search" [(ngModel)]="busqueda" name="busqueda" placeholder="Nombre o presentación..."
                class="mt-1 w-full rounded-xl border border-slate-300 px-3 py-2 text-sm" />
            </label>

            <div class="mt-4 grid gap-3 sm:grid-cols-2">
              @for (producto of productosFiltrados(); track producto.id) {
                <article class="flex gap-3 rounded-xl border border-slate-200 p-3">
                  @if (producto.imagenUrl) {
                    <img [src]="producto.imagenUrl" [alt]="producto.nombre" class="h-20 w-20 rounded-lg object-cover" />
                  } @else {
                    <div class="flex h-20 w-20 items-center justify-center rounded-lg bg-slate-100 text-xs text-slate-400">Sin foto</div>
                  }
                  <div class="min-w-0 flex-1">
                    <h3 class="truncate text-sm font-bold text-slate-800">{{ producto.nombre }}</h3>
                    <p class="text-xs text-slate-500">{{ producto.unidadMedida }}</p>
                    <p class="mt-1 text-sm font-black text-blue-700">S/. {{ producto.precioUnitario.toFixed(2) }}</p>
                    <p class="text-xs text-slate-500">Stock: {{ producto.stockDisponible ?? 0 }}</p>
                    <div class="mt-2 flex items-center gap-2">
                      <button type="button" (click)="cambiarCantidad(producto, -1)" class="h-8 w-8 rounded-lg border border-slate-300 font-bold">−</button>
                      <input type="number" min="0" [max]="producto.stockDisponible ?? 0" [ngModel]="cantidad(producto)"
                        (ngModelChange)="fijarCantidad(producto, $event)" name="cantidad-{{producto.id}}"
                        class="h-8 w-16 rounded-lg border border-slate-300 text-center text-sm" />
                      <button type="button" (click)="cambiarCantidad(producto, 1)" class="h-8 w-8 rounded-lg border border-slate-300 font-bold">+</button>
                    </div>
                  </div>
                </article>
              } @empty {
                <p class="col-span-full rounded-xl border border-dashed border-slate-300 p-6 text-center text-sm text-slate-500">
                  No hay productos activos disponibles.
                </p>
              }
            </div>
          </div>

          <aside class="border-t border-slate-200 bg-slate-50 p-4 lg:border-l lg:border-t-0">
            <h3 class="font-black text-slate-800">Resumen del pedido</h3>
            <div class="mt-3 space-y-2 text-xs text-slate-600">
              @for (linea of lineasSeleccionadas(); track linea.producto.id) {
                <div class="flex justify-between gap-2">
                  <span>{{ linea.producto.nombre }} × {{ linea.cantidad }}</span>
                  <strong>S/. {{ (linea.producto.precioUnitario * linea.cantidad).toFixed(2) }}</strong>
                </div>
              } @empty {
                <p>Selecciona productos del catálogo.</p>
              }
            </div>
            <p class="mt-4 border-t border-slate-200 pt-3 text-lg font-black text-slate-800">
              Total: S/. {{ total().toFixed(2) }}
            </p>

            <label class="mt-4 block text-xs font-semibold text-slate-700">
              Método de pago
              <select [(ngModel)]="metodoPago" name="metodoPago" class="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm">
                <option value="EFECTIVO">Efectivo</option>
                <option value="YAPE">Yape</option>
                <option value="PLIN">Plin</option>
                <option value="TRANSFERENCIA">Transferencia</option>
              </select>
            </label>
            <label class="mt-3 block text-xs font-semibold text-slate-700">
              Observaciones
              <textarea [(ngModel)]="observaciones" name="observaciones" rows="3" class="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"></textarea>
            </label>

            @if (error()) {
              <p class="mt-3 rounded-lg bg-red-50 px-3 py-2 text-xs font-semibold text-red-700">{{ error() }}</p>
            }

            <div class="mt-4 flex gap-2">
              <button type="button" (click)="cerrar.emit()" [disabled]="guardando()" class="flex-1 rounded-xl border border-slate-300 px-3 py-2.5 text-sm font-bold text-slate-700">Cancelar</button>
              <button type="button" (click)="confirmar()" [disabled]="guardando() || lineasSeleccionadas().length === 0"
                class="flex-1 rounded-xl bg-blue-600 px-3 py-2.5 text-sm font-bold text-white disabled:cursor-not-allowed disabled:opacity-50">
                {{ guardando() ? 'Guardando...' : 'Confirmar pedido' }}
              </button>
            </div>
          </aside>
        </div>
      </div>
    </div>
  `
})
export class PedidoAsignacionModalComponent {
  readonly cliente = input.required<Cliente>();
  readonly nombreCliente = input('');
  readonly cerrado = output<void>();
  readonly cerrar = this.cerrado;
  readonly confirmado = output<void>();
  readonly pedidoCreado = output<Pedido>();

  readonly service = inject(OlitrackService);
  readonly guardando = signal(false);
  readonly error = signal('');
  readonly cantidades = signal<Record<string, number>>({});

  busqueda = '';
  observaciones = '';
  metodoPago: 'EFECTIVO' | 'YAPE' | 'PLIN' | 'TRANSFERENCIA' = 'EFECTIVO';

  readonly productosFiltrados = () => {
    const term = this.normalizar(this.busqueda);
    return this.service.productos().filter(producto =>
      producto.activo &&
      (!term
        || this.normalizar(producto.nombre).includes(term)
        || this.normalizar(producto.unidadMedida).includes(term)
        || this.normalizar(producto.categoria).includes(term))
    );
  };

  readonly lineasSeleccionadas = signal<{ producto: Producto; cantidad: number }[]>([]);

  cantidad(producto: Producto): number {
    return this.cantidades()[producto.id] ?? 0;
  }

  fijarCantidad(producto: Producto, value: number): void {
    const cantidad = Math.max(0, Math.min(Number(value) || 0, producto.stockDisponible ?? 0));
    this.actualizarLinea(producto, cantidad);
  }

  cambiarCantidad(producto: Producto, delta: number): void {
    this.fijarCantidad(producto, this.cantidad(producto) + delta);
  }

  private actualizarLinea(producto: Producto, cantidad: number): void {
    this.cantidades.update(actual => ({ ...actual, [producto.id]: cantidad }));
    this.lineasSeleccionadas.update(() =>
      this.service.productos()
        .map(item => ({ producto: item, cantidad: this.cantidades()[item.id] ?? 0 }))
        .filter(linea => linea.cantidad > 0)
    );
  }

  readonly total = () => this.lineasSeleccionadas()
    .reduce((suma, linea) => suma + linea.producto.precioUnitario * linea.cantidad, 0);

  async confirmar(): Promise<void> {
    const lineas = this.lineasSeleccionadas();
    if (!lineas.length) return;
    this.guardando.set(true);
    this.error.set('');
    try {
      const pedido = await this.service.crearPedidoRapido(this.cliente().id, {
        direccionEntrega: this.cliente().direccion,
        ubicacionEntrega: this.cliente().ubicacion,
        observaciones: this.observaciones.trim() || undefined,
        metodoPago: this.metodoPago,
        productos: lineas.map(linea => ({
          productoId: linea.producto.id,
          cantidad: linea.cantidad
        }))
      });
      await Promise.all([
        this.service.reloadPedidosAdmin(),
        this.service.reloadClientes(),
        this.service.reloadProductos(true)
      ]);
      this.pedidoCreado.emit(pedido);
      this.confirmado.emit();
    } catch (error) {
      this.error.set(this.service.mensajeError(error, 'No se pudo crear el pedido.'));
    } finally {
      this.guardando.set(false);
    }
  }

  private normalizar(value: unknown): string {
    return String(value ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  }
}
