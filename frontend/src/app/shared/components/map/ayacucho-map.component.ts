import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild
} from '@angular/core';

import type * as Leaflet from 'leaflet';

import {
  Cliente,
  Pedido,
  Repartidor
} from '../../../core/models/olitrack.models';
import { CentroMapa } from './ayacucho-map.models';

const DEFAULT_CENTER: Leaflet.LatLngTuple = [-13.1635, -74.224];

/**
 * Evita inyección de HTML en los popups de Leaflet.
 */
const esc = (value: unknown): string =>
  String(value ?? '').replace(
    /[&<>"']/g,
    ch =>
      ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#39;'
      })[ch]!
  );

@Component({
  selector: 'app-ayacucho-map',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,

  templateUrl: './ayacucho-map.component.html'
})
export class AyacuchoMapComponent {

  private readonly destroyRef = inject(DestroyRef);

  // ===========================================================
  // INPUTS / OUTPUTS
  // ===========================================================

  readonly repartidores = input<Repartidor[]>([]);

  readonly clientes = input<Cliente[]>([]);

  readonly pedidos = input<Pedido[]>([]);

  readonly companyConfig = input.required<CentroMapa>();

  readonly selectedClienteId = input<string>();

  readonly selectedRepartidorId = input<string>();

  readonly height = input('520px');

  readonly showClientes = input(true);

  readonly showRepartidores = input(true);

  readonly showAlmacen = input(true);

  readonly showLegend = input(true);

  readonly selectCliente = output<Cliente>();

  readonly selectRepartidor = output<Repartidor>();

  // ===========================================================
  // ESTADO INTERNO
  // ===========================================================

  private readonly mapContainer =
    viewChild.required<ElementRef<HTMLDivElement>>('mapContainer');

  private leaflet?: typeof import('leaflet');

  private map?: Leaflet.Map;

  private markersLayer?: Leaflet.LayerGroup;

  private readonly mapReady = signal(false);

  // ===========================================================
  // CONSTRUCTOR
  // ===========================================================

  constructor() {

    /*
     * Leaflet necesita acceder al DOM y a window.
     * Por eso el mapa se inicializa después de renderizar
     * el componente en el navegador.
     */
    afterNextRender(() => void this.initMap());

    /*
     * Cada vez que cambien los signals utilizados por
     * renderMarkers(), se vuelven a dibujar los marcadores.
     */
    effect(() => {

      if (this.mapReady()) {
        this.renderMarkers();
      }

    });

    // Al llegar la configuración remota, actualiza el centro sin hacerlo
    // en cada actualización de ubicación de clientes o repartidores.
    effect(() => {
      if (!this.mapReady()) return;
      const cfg = this.companyConfig();
      const lat = cfg?.latitudCentral;
      const lng = cfg?.longitudCentral;
      if (this.isValidCoordinate(lat, lng)) {
        this.map?.setView([lat!, lng!], this.map?.getZoom() ?? 14, {
          animate: false
        });
      }
    });

    /*
     * Limpieza al destruir el componente.
     */
    this.destroyRef.onDestroy(() => {

      this.map?.remove();

      this.map = undefined;

    });
  }

  // ===========================================================
  // INICIALIZACIÓN DEL MAPA
  // ===========================================================

  private async initMap(): Promise<void> {

    const L = await import('leaflet');
    this.leaflet = L;

    const cfg = this.companyConfig();

    const center: Leaflet.LatLngTuple = [
      cfg?.latitudCentral ?? DEFAULT_CENTER[0],
      cfg?.longitudCentral ?? DEFAULT_CENTER[1]
    ];

    const el = this.mapContainer().nativeElement;

    this.map = L.map(el, {
      center,
      zoom: 14,
      zoomControl: true
    });

    L.tileLayer(
      'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
      {
        attribution:
          '&copy; OpenStreetMap contributors • Ayacucho, Perú',
        maxZoom: 19
      }
    ).addTo(this.map);

    this.markersLayer = L.layerGroup().addTo(this.map);

    /*
     * Corrige el tamaño del mapa cuando el contenedor
     * cambia de tamaño.
     */
    const observer = new ResizeObserver(() => {
      this.map?.invalidateSize();
    });

    observer.observe(el);

    this.destroyRef.onDestroy(() => {
      observer.disconnect();
    });

    this.mapReady.set(true);
  }

  // ===========================================================
  // MARCADORES
  // ===========================================================

  private divIcon(
    etiqueta: string,
    size: number,
    classes: string
  ): Leaflet.DivIcon {

    const L = this.leaflet;
    if (!L) throw new Error('Leaflet aún no está inicializado.');

    return L.divIcon({
      className: 'custom-leaflet-icon',

      html: `
        <div
          class="flex items-center justify-center
                 w-full h-full
                 border-2 border-white
                 rounded-full text-white ${classes}"
        >
          <span class="text-xs font-black tracking-wide">${esc(etiqueta)}</span>
        </div>
      `,

      iconSize: [size, size],

      iconAnchor: [size / 2, size]
    });
  }

  // ===========================================================
  // MARCADORES
  // ===========================================================

  private renderMarkers(): void {

    const layer = this.markersLayer;
    const L = this.leaflet;

    if (!layer || !this.map || !L) {
      return;
    }

    layer.clearLayers();

    // =========================================================
    // PLANTA CENTRAL
    // =========================================================

    const cfg = this.companyConfig();
    this.renderRutas(layer, L);

    if (
      this.showAlmacen() &&
      this.isValidCoordinate(cfg?.latitudCentral, cfg?.longitudCentral)
    ) {

      L.marker(
        [
          cfg!.latitudCentral!,
          cfg!.longitudCentral!
        ],
        {
          icon: this.divIcon(
            'P',
            40,
            'bg-blue-600 shadow-xl'
          )
        }
      )
        .addTo(layer)
        .bindPopup(`
          <div class="p-1 text-xs">

            <p class="font-bold text-blue-700">
              ${esc(cfg.nombre)}
            </p>

            <p class="text-slate-600">
              ${esc(cfg.direccionCentral)}
            </p>

          </div>
        `);
    }

    // =========================================================
    // REPARTIDORES
    // =========================================================

    if (this.showRepartidores()) {

      const selectedId = this.selectedRepartidorId();

      for (const rep of this.repartidores()) {

        const pos = rep.ubicacionActual;

        if (!pos || !this.isValidCoordinate(pos.latitud, pos.longitud)) {
          continue;
        }

        const isSelected =
          rep.id === selectedId;

        const isEnRuta =
          rep.estado === 'EN_RUTA';

        const classes = [
          isEnRuta
            ? 'bg-amber-500'
            : 'bg-emerald-600',

          'shadow-lg',

          isSelected
            ? 'ring-4 ring-blue-500 scale-125'
            : ''
        ].join(' ');

        L.marker(
          [
            pos.latitud,
            pos.longitud
          ],
          {
            icon: this.divIcon(
              'R',
              36,
              classes
            )
          }
        )
          .addTo(layer)

          .bindPopup(`
            <div class="p-1.5 text-xs min-w-[160px]">

              <div
                class="flex items-center justify-between
                       border-b pb-1 mb-1"
              >

                <span class="font-bold">
                  Placa: ${esc(rep.placaVehiculo)}
                </span>

                <span
                  class="px-1.5 py-0.5
                         rounded text-[10px]
                         font-bold
                         ${
                           isEnRuta
                             ? 'bg-amber-100 text-amber-800'
                             : 'bg-emerald-100 text-emerald-800'
                         }"
                >
                  ${esc(rep.estado)}
                </span>

              </div>

            </div>
          `)

          .on(
            'click',
            () => this.selectRepartidor.emit(rep)
          );
      }
    }

    // =========================================================
    // CLIENTES
    // =========================================================

    if (this.showClientes()) {

      const selectedId =
        this.selectedClienteId();

      for (const cli of this.clientes()) {

        if (!cli.ubicacion || !this.isValidCoordinate(
          cli.ubicacion.latitud,
          cli.ubicacion.longitud
        )) {
          continue;
        }

        const isSelected =
          cli.id === selectedId;

        const recencia = this.recenciaPedido(cli.ultimoPedidoEn);
        const classes = [
          recencia.color,
          'shadow-md',

          isSelected
            ? 'ring-4 ring-blue-400 scale-125'
            : ''
        ].join(' ');

        L.marker(
          [
            cli.ubicacion.latitud,
            cli.ubicacion.longitud
          ],
          {
            icon: this.divIcon(
              'C',
              32,
              classes
            )
          }
        )
          .addTo(layer)

          .bindPopup(`
            <div class="p-1.5 text-xs min-w-[180px]">

              <span class="font-bold">
                ${esc(cli.nombreNegocio)}
              </span>

              <p class="text-slate-600 text-[11px] mt-0.5">
                ${esc(cli.direccion)}
              </p>

              <p class="text-[11px] font-semibold mt-1 ${recencia.textColor}">
                ${esc(recencia.label)}
              </p>

            </div>
          `)

          .on(
            'click',
            () => this.selectCliente.emit(cli)
          );
      }
    }
  }

  private recenciaPedido(fecha?: string | null): {
    color: string;
    textColor: string;
    label: string;
  } {
    if (!fecha || !Number.isFinite(new Date(fecha).getTime())) {
      return {
        color: 'bg-slate-500',
        textColor: 'text-slate-600',
        label: 'Sin pedidos registrados'
      };
    }

    const dias = Math.max(
      0,
      Math.floor((Date.now() - new Date(fecha).getTime()) / 86_400_000)
    );
    if (dias <= 3) {
      return {
        color: 'bg-emerald-600',
        textColor: 'text-emerald-700',
        label: dias === 0 ? 'Pedido hoy' : `Último pedido hace ${dias} día(s)`
      };
    }
    if (dias <= 6) {
      return {
        color: 'bg-orange-500',
        textColor: 'text-orange-700',
        label: `Último pedido hace ${dias} días`
      };
    }
    return {
      color: 'bg-red-600',
      textColor: 'text-red-700',
      label: `Último pedido hace ${dias} días`
    };
  }

  tienePedidosAsignados(): boolean {
    return this.pedidos().some(pedido => !!pedido.repartidorId);
  }

  private renderRutas(
    layer: Leaflet.LayerGroup,
    leaflet: typeof import('leaflet')
  ): void {
    const repartidores = this.repartidores();
    const estadosActivos = new Set([
      'PENDIENTE',
      'CONFIRMADO',
      'EN_PREPARACION',
      'EN_CAMINO'
    ]);

    for (const pedido of this.pedidos()) {
      if (!pedido.repartidorId || !estadosActivos.has(pedido.estado)) continue;
      const repartidor = repartidores.find(
        item => item.id === pedido.repartidorId
      );
      const origen = repartidor?.ubicacionActual;
      const destino = pedido.ubicacionEntrega;
      if (
        !origen || !destino ||
        !this.isValidCoordinate(origen.latitud, origen.longitud) ||
        !this.isValidCoordinate(destino.latitud, destino.longitud)
      ) continue;

      const eta = Number.isFinite(pedido.tiempoEstimadoMinutos)
        ? `<br>Tiempo estimado referencial: ${Math.max(0, Math.round(pedido.tiempoEstimadoMinutos!))} min`
        : '';

      leaflet.polyline(
        [
          [origen.latitud, origen.longitud],
          [destino.latitud, destino.longitud]
        ],
        { color: '#2563eb', weight: 4, opacity: 0.75, dashArray: '8 8' }
      )
        .addTo(layer)
        .bindPopup(`Ruta aproximada del pedido #${esc(pedido.id.slice(-6))}${eta}`);
    }
  }

  private isValidCoordinate(
    latitud: number | null | undefined,
    longitud: number | null | undefined
  ): boolean {
    return Number.isFinite(latitud) &&
      Number.isFinite(longitud) &&
      latitud! >= -90 && latitud! <= 90 &&
      longitud! >= -180 && longitud! <= 180;
  }

  // ===========================================================
  // ACCIONES
  // ===========================================================

  recenterCentral(): void {

    const cfg = this.companyConfig();

    if (
      this.map &&
      this.isValidCoordinate(cfg?.latitudCentral, cfg?.longitudCentral)
    ) {

      this.map.setView(
        [
          cfg!.latitudCentral!,
          cfg!.longitudCentral!
        ],
        14
      );
    }
  }

  fitAllPoints(): void {

    if (!this.map) {
      return;
    }

    const L = this.leaflet;
    if (!L) return;

    const pts: Leaflet.LatLngTuple[] = [];

    const cfg = this.companyConfig();

    // Planta central
    if (
      this.showAlmacen() &&
      this.isValidCoordinate(cfg?.latitudCentral, cfg?.longitudCentral)
    ) {

      pts.push([
        cfg!.latitudCentral!,
        cfg!.longitudCentral!
      ]);
    }

    // Repartidores
    for (const r of this.repartidores()) {

      if (
        this.showRepartidores() &&
        r.ubicacionActual &&
        this.isValidCoordinate(r.ubicacionActual.latitud, r.ubicacionActual.longitud)
      ) {

        pts.push([
          r.ubicacionActual.latitud,
          r.ubicacionActual.longitud
        ]);

      }
    }

    // Clientes
    for (const c of this.clientes()) {

      if (
        this.showClientes() &&
        c.ubicacion &&
        this.isValidCoordinate(c.ubicacion.latitud, c.ubicacion.longitud)
      ) {

        pts.push([
          c.ubicacion.latitud,
          c.ubicacion.longitud
        ]);

      }
    }

    // Ajustar mapa
    if (pts.length > 0) {

      this.map.fitBounds(
        L.latLngBounds(pts),
        {
          padding: [50, 50]
        }
      );
    }
  }
}
