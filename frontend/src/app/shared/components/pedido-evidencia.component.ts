import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  input,
  signal
} from '@angular/core';

import { OlitrackService } from '../../core/services/olitrack.service';

@Component({
  selector: 'app-pedido-evidencia',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (evidenciaUrl()) {
      <button
        type="button"
        (click)="abrir()"
        [disabled]="cargando()"
        class="px-3 py-1.5 rounded-lg bg-emerald-50 hover:bg-emerald-100 disabled:opacity-50 text-emerald-800 text-xs font-bold"
      >
        {{ cargando() ? 'Cargando...' : 'Ver comprobante' }}
      </button>
    }

    @if (error()) {
      <p role="alert" class="mt-2 text-xs text-red-700">
        {{ error() }}
      </p>
    }

    @if (url(); as objectUrl) {
      <div
        class="fixed inset-0 z-[100] bg-black/70 p-4 flex items-center justify-center"
        role="dialog"
        aria-modal="true"
        aria-label="Comprobante de pago"
        (click)="cerrar()"
      >
        <section
          class="relative bg-white rounded-2xl p-4 max-w-4xl max-h-[90vh] overflow-auto"
          (click)="$event.stopPropagation()"
        >
          <button
            type="button"
            class="float-right px-3 py-1 rounded-lg bg-slate-100 text-slate-800 font-bold"
            (click)="cerrar()"
          >
            Cerrar
          </button>

          <h2 class="font-bold text-slate-900 mb-4">
            Comprobante de pago
          </h2>

          @if (contentType().startsWith('image/')) {
            <img
              [src]="objectUrl"
              alt="Comprobante de pago"
              class="max-h-[70vh] max-w-full object-contain mx-auto"
            />
          } @else {
            <p class="text-sm text-slate-600 mb-3">
              El comprobante es un PDF.
            </p>
            <a
              [href]="objectUrl"
              target="_blank"
              rel="noopener noreferrer"
              class="text-blue-700 underline font-semibold"
            >
              Abrir PDF
            </a>
          }
        </section>
      </div>
    }
  `
})
export class PedidoEvidenciaComponent {
  readonly pedidoId = input.required<string>();
  readonly evidenciaUrl = input<string | undefined>();

  readonly cargando = signal(false);
  readonly error = signal('');
  readonly url = signal<string | null>(null);
  readonly contentType = signal('');

  private readonly service = inject(OlitrackService);
  private readonly destroyRef = inject(DestroyRef);

  constructor() {
    this.destroyRef.onDestroy(() => this.liberarUrl());
  }

  async abrir(): Promise<void> {
    if (!this.evidenciaUrl() || this.cargando()) {
      return;
    }

    this.cargando.set(true);
    this.error.set('');

    try {
      const archivo = await this.service.descargarEvidencia(this.pedidoId());
      this.liberarUrl();
      this.contentType.set(archivo.type);
      this.url.set(URL.createObjectURL(archivo));
    } catch (error: unknown) {
      console.error('No se pudo cargar el comprobante:', error);
      this.error.set(
        this.service.mensajeError(
          error,
          'No se pudo cargar el comprobante.'
        )
      );
    } finally {
      this.cargando.set(false);
    }
  }

  cerrar(): void {
    this.liberarUrl();
    this.error.set('');
  }

  private liberarUrl(): void {
    const urlActual = this.url();
    if (urlActual) {
      URL.revokeObjectURL(urlActual);
      this.url.set(null);
    }
  }
}
