import { DatePipe } from '@angular/common';
import { Component, computed, input, signal } from '@angular/core';
import {
  Incidencia,
  Repartidor,
  TipoIncidencia,
  Usuario
} from '../../core/models/olitrack.models';

interface IncidenciaSerie {
  tipo: TipoIncidencia;
  etiqueta: string;
  color: string;
  cantidad: number;
}

const TIPOS_INCIDENCIA: {
  tipo: TipoIncidencia;
  etiqueta: string;
  color: string;
}[] = [
  { tipo: 'RETRASO', etiqueta: 'Retraso', color: '#f97316' },
  { tipo: 'PRODUCTO_DANADO', etiqueta: 'Producto dañado', color: '#ef4444' },
  { tipo: 'DIRECCION_INCORRECTA', etiqueta: 'Dirección incorrecta', color: '#8b5cf6' },
  { tipo: 'CLIENTE_AUSENTE', etiqueta: 'Cliente ausente', color: '#0ea5e9' },
  { tipo: 'MECANICA', etiqueta: 'Falla mecánica', color: '#dc2626' },
  { tipo: 'TRAFICO', etiqueta: 'Tráfico', color: '#eab308' },
  { tipo: 'OPERATIVO', etiqueta: 'Problema operativo', color: '#14b8a6' },
  { tipo: 'OTRO', etiqueta: 'Otros', color: '#64748b' }
];

@Component({
  selector: 'app-incidencias-charts',
  standalone: true,
  template: `
    <section class="space-y-4">
      <article class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        <h2 class="font-bold text-slate-800">Incidencias generales</h2>
        <p class="mt-1 text-xs text-slate-500">
          Distribución de todas las incidencias registradas por tipo.
        </p>

        <div class="mt-5 flex flex-col items-center gap-5 sm:flex-row sm:items-center">
          <div
            class="h-40 w-40 shrink-0 rounded-full"
            [style.background]="gradiente(general())"
            role="img"
            [attr.aria-label]="'Gráfico circular de ' + total(general()) + ' incidencias generales'"
          ></div>

          <div class="grid w-full grid-cols-1 gap-2 sm:grid-cols-2">
            @for (serie of general(); track serie.tipo) {
              <div class="flex items-center justify-between gap-3 text-xs">
                <span class="flex min-w-0 items-center gap-2 text-slate-600">
                  <span class="h-2.5 w-2.5 shrink-0 rounded-full" [style.background]="serie.color"></span>
                  <span class="truncate">{{ serie.etiqueta }}</span>
                </span>
                <button
                  type="button"
                  (click)="verDetalle(null, serie.tipo)"
                  [attr.aria-label]="'Ver ' + serie.cantidad + ' incidencias de tipo ' + serie.etiqueta"
                  class="rounded-md px-2 py-1 font-bold text-blue-700 hover:bg-blue-50 disabled:text-slate-400"
                  [disabled]="serie.cantidad === 0"
                >
                  {{ serie.cantidad }} · Ver
                </button>
              </div>
            }
            <p class="text-xs text-slate-500 sm:col-span-2">
              Total: <strong class="text-slate-800">{{ total(general()) }}</strong>
            </p>
          </div>
        </div>
      </article>

      <div>
        <h2 class="mb-3 font-bold text-slate-800">Incidencias por repartidor</h2>
        @if (porRepartidor().length > 0) {
          <div class="grid grid-cols-1 gap-4 xl:grid-cols-2">
            @for (repartidor of porRepartidor(); track repartidor.id) {
              <article class="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm">
                <div class="flex items-center justify-between gap-3">
                  <h3 class="truncate text-sm font-bold text-slate-800">
                    {{ nombreRepartidor(repartidor.id) }}
                  </h3>
                  <span class="shrink-0 rounded-full bg-slate-100 px-2.5 py-1 text-xs font-bold text-slate-700">
                    {{ total(repartidor.series) }} incidencias
                  </span>
                </div>

                <div class="mt-4 flex items-center gap-4">
                  <div
                    class="h-24 w-24 shrink-0 rounded-full"
                    [style.background]="gradiente(repartidor.series)"
                    role="img"
                    [attr.aria-label]="'Gráfico circular de ' + total(repartidor.series) + ' incidencias para ' + nombreRepartidor(repartidor.id)"
                  ></div>
                  <div class="grid min-w-0 flex-1 grid-cols-1 gap-1.5 sm:grid-cols-2">
                    @for (serie of repartidor.series; track serie.tipo) {
                      <div class="flex items-center justify-between gap-2 text-[11px]">
                        <span class="flex min-w-0 items-center gap-1.5 text-slate-600">
                          <span class="h-2 w-2 shrink-0 rounded-full" [style.background]="serie.color"></span>
                          <span class="truncate">{{ serie.etiqueta }}</span>
                        </span>
                        <button
                          type="button"
                          (click)="verDetalle(repartidor.id, serie.tipo)"
                          [attr.aria-label]="'Ver ' + serie.cantidad + ' incidencias de tipo ' + serie.etiqueta + ' para ' + nombreRepartidor(repartidor.id)"
                          class="rounded-md px-2 py-1 font-bold text-blue-700 hover:bg-blue-50 disabled:text-slate-400"
                          [disabled]="serie.cantidad === 0"
                        >
                          {{ serie.cantidad }} · Ver
                        </button>
                      </div>
                    }
                  </div>
                </div>
              </article>
            }
          </div>
        } @else {
          <p class="rounded-xl border border-slate-200 bg-white p-4 text-sm text-slate-500">
            No hay repartidores registrados para mostrar.
          </p>
        }
      </div>

      @if (detalleSeleccionado(); as seleccion) {
        <article class="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
          <header class="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 p-4">
            <div>
              <h2 class="font-bold text-slate-800">
                Detalle de incidencias
                @if (seleccion.tipo) {
                  · {{ etiquetaTipo(seleccion.tipo) }}
                }
              </h2>
              <p class="mt-1 text-xs text-slate-500">
                {{ seleccion.repartidorId
                  ? nombreRepartidor(seleccion.repartidorId)
                  : 'Todos los repartidores' }}
                · {{ incidenciasFiltradas().length }} registro(s)
              </p>
            </div>
            <button
              type="button"
              (click)="detalleSeleccionado.set(null)"
              class="rounded-lg px-3 py-1.5 text-xs font-bold text-slate-600 hover:bg-slate-100"
            >
              Cerrar detalle
            </button>
          </header>

          <div class="overflow-x-auto">
            <table class="min-w-full text-left text-xs">
              <thead class="bg-slate-50 text-slate-500">
                <tr>
                  <th class="px-4 py-3">Fecha</th>
                  <th class="px-4 py-3">Repartidor</th>
                  <th class="px-4 py-3">Pedido</th>
                  <th class="px-4 py-3">Tipo</th>
                  <th class="px-4 py-3">Descripción</th>
                  <th class="px-4 py-3">Estado</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100">
                @for (incidencia of incidenciasFiltradas(); track incidencia.id) {
                  <tr class="align-top hover:bg-slate-50">
                    <td class="whitespace-nowrap px-4 py-3 text-slate-600">
                      {{ incidencia.creadoEn | date:'dd/MM/yyyy HH:mm' }}
                    </td>
                    <td class="px-4 py-3 font-semibold text-slate-700">
                      {{ nombreRepartidor(incidencia.repartidorId) }}
                    </td>
                    <td class="whitespace-nowrap px-4 py-3 font-mono text-slate-600">
                      #{{ incidencia.pedidoId.slice(-6) }}
                    </td>
                    <td class="whitespace-nowrap px-4 py-3 text-slate-700">
                      {{ etiquetaTipo(incidencia.tipo) }}
                    </td>
                    <td class="min-w-56 px-4 py-3 text-slate-600">
                      {{ incidencia.descripcion }}
                    </td>
                    <td class="whitespace-nowrap px-4 py-3 text-slate-600">
                      {{ incidencia.estado.replace('_', ' ') }}
                    </td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="6" class="px-4 py-8 text-center text-slate-500">
                      No hay incidencias para este filtro.
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </article>
      }
    </section>
  `,
  imports: [DatePipe]
})
export class IncidenciasChartsComponent {
  readonly incidencias = input<Incidencia[]>([]);
  readonly repartidores = input<Repartidor[]>([]);
  readonly usuarios = input<Usuario[]>([]);
  readonly detalleSeleccionado = signal<{
    repartidorId: string | null;
    tipo: TipoIncidencia | null;
  } | null>(null);

  readonly general = computed(() => this.resumir(this.incidencias()));

  readonly porRepartidor = computed(() => {
    const ids = new Set([
      ...this.repartidores().map(repartidor => repartidor.id),
      ...this.incidencias().map(incidencia => incidencia.repartidorId)
    ]);

    return [...ids].map(id => ({
      id,
      series: this.resumir(
        this.incidencias().filter(incidencia => incidencia.repartidorId === id)
      )
    }));
  });

  readonly incidenciasFiltradas = computed(() => {
    const filtro = this.detalleSeleccionado();
    if (!filtro) {
      return [];
    }

    return this.incidencias()
      .filter(incidencia =>
        !filtro.repartidorId ||
        incidencia.repartidorId === filtro.repartidorId
      )
      .filter(incidencia =>
        !filtro.tipo || incidencia.tipo === filtro.tipo
      )
      .sort((a, b) =>
        new Date(b.creadoEn).getTime() - new Date(a.creadoEn).getTime()
      );
  });

  verDetalle(
    repartidorId: string | null,
    tipo: TipoIncidencia
  ): void {
    this.detalleSeleccionado.set({ repartidorId, tipo });
  }

  etiquetaTipo(tipo: TipoIncidencia): string {
    return TIPOS_INCIDENCIA.find(item => item.tipo === tipo)?.etiqueta ?? tipo;
  }

  total(series: IncidenciaSerie[]): number {
    return series.reduce((suma, item) => suma + item.cantidad, 0);
  }

  gradiente(series: IncidenciaSerie[]): string {
    const total = this.total(series);
    if (total === 0) {
      return 'conic-gradient(#e2e8f0 0% 100%)';
    }

    let acumulado = 0;
    const partes = series
      .filter(item => item.cantidad > 0)
      .map(item => {
        const inicio = acumulado;
        acumulado += (item.cantidad / total) * 100;
        return `${item.color} ${inicio}% ${acumulado}%`;
      });

    return `conic-gradient(${partes.join(', ')})`;
  }

  nombreRepartidor(repartidorId: string): string {
    const repartidor = this.repartidores().find(item => item.id === repartidorId);
    const usuario = this.usuarios().find(item => item.id === repartidor?.usuarioId);
    return usuario?.nombre
      ?? repartidor?.placaVehiculo
      ?? `Repartidor #${repartidorId.slice(-6)}`;
  }

  private resumir(incidencias: Incidencia[]): IncidenciaSerie[] {
    return TIPOS_INCIDENCIA.map(tipo => ({
      ...tipo,
      cantidad: incidencias.filter(incidencia => incidencia.tipo === tipo.tipo).length
    }));
  }
}
