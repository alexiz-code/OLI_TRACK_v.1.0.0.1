import {
  ChangeDetectionStrategy,
  Component,
  computed,
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
import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';

import type * as Leaflet from 'leaflet';
import { ComparacionRepartidoresResponse, RutaResponse } from '../../../core/models/rutas.models';
import { RutasService } from '../../../core/services/rutas.service';

import {
  Cliente,
  Pedido,
  Repartidor
} from '../../../core/models/olitrack.models';
import { CentroMapa } from './ayacucho-map.models';

const DEFAULT_CENTER: Leaflet.LatLngTuple = [-13.181655, -74.210656];

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

interface RutaPedidoEnSeguimiento {
  pedidoId: string;
  ruta: RutaResponse | null;
  origenSolicitado?: { latitud: number; longitud: number };
  destinoSolicitado?: { latitud: number; longitud: number };
  origenDeseado?: { latitud: number; longitud: number };
  destinoDeseado?: { latitud: number; longitud: number };
  solicitadoEn: number;
  enCurso: boolean;
  solicitudId: number;
  generacion: number;
  pendiente: boolean;
  error?: string;
}

@Component({
  selector: 'app-ayacucho-map',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DecimalPipe],
  templateUrl: './ayacucho-map.component.html'
})
export class AyacuchoMapComponent {

  private readonly destroyRef = inject(DestroyRef);
  private readonly rutasService = inject(RutasService);

  // ===========================================================
  // INPUTS / OUTPUTS
  // ===========================================================

  readonly repartidores = input<Repartidor[]>([]);

  readonly clientes = input<Cliente[]>([]);

  readonly pedidos = input<Pedido[]>([]);

  readonly companyConfig = input.required<CentroMapa>();

  readonly selectedClienteId = input<string>();

  readonly selectedRepartidorId = input<string>();

  readonly routingEnabled = input(false);

  readonly height = input('520px');

  readonly showClientes = input(true);

  readonly showRepartidores = input(true);

  readonly showAlmacen = input(true);

  readonly showLegend = input(true);

  readonly selectCliente = output<Cliente>();

  readonly selectRepartidor = output<Repartidor>();

  readonly rutaActual = signal<RutaResponse | null>(null);

  readonly comparacionRepartidores = signal<ComparacionRepartidoresResponse | null>(null);

  readonly cargandoRuta = signal(false);

  readonly errorRuta = signal<string | null>(null);

  readonly errorRutasActivas = signal<string | null>(null);

  readonly cargandoRutasActivas = signal(false);

  readonly clienteRutaId = signal<string | undefined>(undefined);

  readonly repartidorRutaId = signal<string | undefined>(undefined);

  readonly repartidoresConUbicacion = computed(() => this.repartidores()
    .filter(repartidor => repartidor.ubicacionActual &&
      this.isValidCoordinate(
        repartidor.ubicacionActual.latitud,
        repartidor.ubicacionActual.longitud
      )));

  // ===========================================================
  // ESTADO INTERNO
  // ===========================================================

  private readonly mapContainer =
    viewChild.required<ElementRef<HTMLDivElement>>('mapContainer');

  private leaflet?: typeof import('leaflet');

  private map?: Leaflet.Map;

  private markersLayer?: Leaflet.LayerGroup;

  private activeRoutesLayer?: Leaflet.LayerGroup;

  private selectedRouteLayer?: Leaflet.LayerGroup;

  private readonly rutasPedidos = new Map<string, RutaPedidoEnSeguimiento>();

  private readonly maxSolicitudesRutaActiva = 3;

  private solicitudesRutaActiva = 0;

  private routeQueueTimer?: ReturnType<typeof setTimeout>;

  private generacionSolicitudManual = 0;

  private generacionSolicitudComparacion = 0;

  private lastRoutedPosition?: { latitud: number; longitud: number };

  private lastRouteRequestAt = 0;

  private routeRepartidorId = signal<string | null>(null);

  private synchronizedClienteId?: string;

  private synchronizedRepartidorId?: string;

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

    effect(() => {
      const selectedId = this.selectedClienteId();
      if (selectedId !== this.synchronizedClienteId) {
        this.synchronizedClienteId = selectedId;
        this.clienteRutaId.set(selectedId);
      }
    });

    effect(() => {
      const selectedId = this.selectedRepartidorId();
      if (selectedId !== this.synchronizedRepartidorId) {
        this.synchronizedRepartidorId = selectedId;
        this.repartidorRutaId.set(selectedId);
      }
    });

    effect(() => {
      const route = this.rutaActual();
      const driverId = this.routeRepartidorId();
      const drivers = this.repartidores();
      if (
        !route ||
        !driverId ||
        !this.routingEnabled() ||
        !this.mapReady() ||
        this.cargandoRuta()
      ) {
        return;
      }

      const position = drivers.find(item => item.id === driverId)?.ubicacionActual;
      const previous = this.lastRoutedPosition;
      if (!position || !previous || !this.isValidCoordinate(position.latitud, position.longitud)) {
        return;
      }

      if (
        this.distanceMeters(previous.latitud, previous.longitud, position.latitud, position.longitud) >= 50 &&
        Date.now() - this.lastRouteRequestAt >= 30_000
      ) {
        void this.calcularRuta();
      }
    });

    /*
     * Limpieza al destruir el componente.
     */
    this.destroyRef.onDestroy(() => {

      if (this.routeQueueTimer) clearTimeout(this.routeQueueTimer);
      this.generacionSolicitudManual++;
      for (const route of this.rutasPedidos.values()) {
        route.generacion++;
      }
      this.map?.remove();

      this.map = undefined;

    });
  }

  // ===========================================================
  // INICIALIZACIÓN DEL MAPA
  // ===========================================================


private async initMap(): Promise<void> {
  try {
    const leafletModule = await import('leaflet');

    const L = (
      (leafletModule as any).default ?? leafletModule
    ) as typeof import('leaflet');

    if (typeof L.map !== 'function') {
      console.error(
        '[OLITRACK] Leaflet no se inicializó correctamente.',
        leafletModule
      );
      return;
    }

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
    this.activeRoutesLayer = L.layerGroup().addTo(this.map);
    this.selectedRouteLayer = L.layerGroup().addTo(this.map);

    const observer = new ResizeObserver(() => {
      this.map?.invalidateSize();
    });

    observer.observe(el);

    this.destroyRef.onDestroy(() => {
      observer.disconnect();
    });

    this.mapReady.set(true);

    // Fuerza una actualización del tamaño después de inicializar.
    requestAnimationFrame(() => {
      this.map?.invalidateSize();
    });

    console.info('[OLITRACK] Mapa Leaflet inicializado correctamente.');

  } catch (error) {
    console.error(
      '[OLITRACK] Error al inicializar el mapa:',
      error
    );
  }
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
            () => this.seleccionarRepartidor(rep)
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
            () => this.seleccionarCliente(cli)
          );
      }
    }

    this.renderRutas();
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

  private renderRutas(): void {
    const pedidosActivos = this.pedidos().filter(pedido =>
      !!pedido.repartidorId &&
      ['PENDIENTE', 'CONFIRMADO', 'EN_PREPARACION', 'EN_CAMINO'].includes(pedido.estado)
    );
    const idsActivos = new Set(pedidosActivos.map(pedido => pedido.id));

    for (const [pedidoId, state] of this.rutasPedidos) {
      if (!idsActivos.has(pedidoId)) {
        state.generacion++;
        this.rutasPedidos.delete(pedidoId);
      }
    }

    for (const pedido of pedidosActivos) {
      const repartidor = this.repartidores().find(item => item.id === pedido.repartidorId);
      const origen = repartidor?.ubicacionActual;
      const destino = pedido.ubicacionEntrega;
      let state = this.rutasPedidos.get(pedido.id);

      if (!state) {
        state = {
          pedidoId: pedido.id,
          ruta: null,
          solicitadoEn: 0,
          enCurso: false,
          solicitudId: 0,
          generacion: 0,
          pendiente: false
        };
        this.rutasPedidos.set(pedido.id, state);
      }

      if (
        !origen || !destino ||
        !this.isValidCoordinate(origen.latitud, origen.longitud) ||
        !this.isValidCoordinate(destino.latitud, destino.longitud)
      ) {
        state.generacion++;
        state.ruta = null;
        state.pendiente = false;
        state.error = 'Ruta vial no disponible: faltan coordenadas GPS válidas.';
        continue;
      }

      const currentOrigin = { latitud: origen.latitud, longitud: origen.longitud };
      const currentDestination = { latitud: destino.latitud, longitud: destino.longitud };
      const originChanged = !state.origenSolicitado ||
        this.distanceMeters(
          state.origenSolicitado.latitud,
          state.origenSolicitado.longitud,
          currentOrigin.latitud,
          currentOrigin.longitud
        ) >= 50;
      const destinationChanged = !state.destinoSolicitado ||
        this.distanceMeters(
          state.destinoSolicitado.latitud,
          state.destinoSolicitado.longitud,
          currentDestination.latitud,
          currentDestination.longitud
        ) >= 5;

      if (originChanged || destinationChanged) {
        state.origenDeseado = currentOrigin;
        state.destinoDeseado = currentDestination;
        state.pendiente = true;
        state.ruta = null;
        state.error = undefined;
        if (state.enCurso) state.generacion++;
      }
    }

    this.dibujarRutasActivas();
    this.procesarColaRutas();
  }

  private procesarColaRutas(): void {
    let siguienteDisponibleEn: number | null = null;
    for (const state of this.rutasPedidos.values()) {
      if (this.solicitudesRutaActiva >= this.maxSolicitudesRutaActiva) break;
      if (!state.pendiente || state.enCurso) continue;

      const disponibleEn = state.solicitadoEn
        ? state.solicitadoEn + 30_000
        : Date.now();
      if (disponibleEn > Date.now()) {
        siguienteDisponibleEn = siguienteDisponibleEn === null
          ? disponibleEn
          : Math.min(siguienteDisponibleEn, disponibleEn);
        continue;
      }

      state.pendiente = false;
      state.enCurso = true;
      state.solicitadoEn = Date.now();
      state.origenSolicitado = state.origenDeseado;
      state.destinoSolicitado = state.destinoDeseado;
      const solicitudId = ++state.generacion;
      state.solicitudId = solicitudId;
      this.solicitudesRutaActiva++;

      void this.rutasService.calcularPedido(state.pedidoId)
        .then(route => {
          if (
            state.generacion !== solicitudId ||
            this.rutasPedidos.get(state.pedidoId) !== state
          ) {
            return;
          }
          if (route.estado !== 'OK' || route.geometria.length < 2) {
            state.ruta = null;
            state.error = 'Ruta vial no disponible.';
            return;
          }
          state.ruta = route;
          state.error = undefined;
        })
        .catch(error => {
          if (
            state.generacion !== solicitudId ||
            this.rutasPedidos.get(state.pedidoId) !== state
          ) {
            return;
          }
          state.ruta = null;
          state.error = 'Ruta vial no disponible. Revisa la cobertura vial y la conexión.';
          console.warn('[OLITRACK] Falló el cálculo de una ruta OSRM.', {
            status: error instanceof HttpErrorResponse ? error.status : 0
          });
        })
        .finally(() => {
          this.solicitudesRutaActiva = Math.max(0, this.solicitudesRutaActiva - 1);
          if (state.solicitudId === solicitudId) state.enCurso = false;
          this.dibujarRutasActivas();
          this.procesarColaRutas();
        });
    }

    if (siguienteDisponibleEn !== null && !this.routeQueueTimer) {
      this.routeQueueTimer = setTimeout(() => {
        this.routeQueueTimer = undefined;
        this.procesarColaRutas();
      }, Math.max(0, siguienteDisponibleEn - Date.now()));
    }
  }

  private dibujarRutasActivas(): void {
    const layer = this.activeRoutesLayer;
    const L = this.leaflet;
    if (!layer || !L || !this.map) return;

    layer.clearLayers();
    let firstError: string | null = null;
    let loading = false;

    for (const state of this.rutasPedidos.values()) {
      loading ||= state.enCurso || state.pendiente;
      firstError ??= state.error ?? null;
      if (!state.ruta || state.ruta.geometria.length < 2) continue;

      const points: Leaflet.LatLngTuple[] = state.ruta.geometria.map(point => [
        point.latitud,
        point.longitud
      ]);
      L.polyline(points, {
        color: '#2563eb',
        weight: 4,
        opacity: 0.85
      })
        .addTo(layer)
        .bindPopup(
          `Pedido #${esc(state.pedidoId.slice(-6))} · ` +
          `${state.ruta.distanciaKm.toFixed(2)} km · ` +
          `${Math.round(state.ruta.duracionMinutos)} min`
        );
    }

    this.errorRutasActivas.set(firstError);
    this.cargandoRutasActivas.set(loading);
  }

  private distanceMeters(
    latitude1: number,
    longitude1: number,
    latitude2: number,
    longitude2: number
  ): number {
    const radians = (degrees: number) => degrees * Math.PI / 180;
    const deltaLatitude = radians(latitude2 - latitude1);
    const deltaLongitude = radians(longitude2 - longitude1);
    const a = Math.sin(deltaLatitude / 2) ** 2 +
      Math.cos(radians(latitude1)) * Math.cos(radians(latitude2)) *
      Math.sin(deltaLongitude / 2) ** 2;
    return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  refrescarRutasActivas(): void {
    for (const state of this.rutasPedidos.values()) {
      state.origenSolicitado = undefined;
      state.destinoSolicitado = undefined;
      state.ruta = null;
      state.error = undefined;
      state.pendiente = true;
      if (state.enCurso) state.generacion++;
    }
    this.dibujarRutasActivas();
    this.procesarColaRutas();
  }

  private drawSelectedRoute(route: RutaResponse | null): void {
    const layer = this.selectedRouteLayer;
    const L = this.leaflet;
    if (!layer || !L || !this.map) return;

    layer.clearLayers();
    if (!route || route.geometria.length < 2) return;

    const points: Leaflet.LatLngTuple[] = route.geometria.map(point => [
      point.latitud,
      point.longitud
    ]);
    const line = L.polyline(points, {
      color: '#7c3aed',
      weight: 6,
      opacity: 0.9
    }).addTo(layer);
    line.bindPopup(
      `Ruta por calles: ${route.distanciaKm.toFixed(2)} km · ` +
      `${Math.round(route.duracionMinutos)} min`
    );
    this.map.fitBounds(line.getBounds(), {
      padding: [24, 24],
      maxZoom: 16
    });
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

  private seleccionarCliente(cliente: Cliente): void {
    this.clienteRutaId.set(cliente.id);
    this.limpiarRuta();
    this.selectCliente.emit(cliente);
  }

  private seleccionarRepartidor(repartidor: Repartidor): void {
    this.repartidorRutaId.set(repartidor.id);
    this.limpiarRuta();
    this.selectRepartidor.emit(repartidor);
  }

  seleccionarClienteRuta(event: Event): void {
    const target = event.target;
    if (!(target instanceof HTMLSelectElement)) return;

    this.clienteRutaId.set(target.value);
    const cliente = this.clientes().find(item => item.id === target.value);
    if (cliente) this.selectCliente.emit(cliente);
    this.limpiarRuta();
  }

  seleccionarRepartidorRuta(event: Event): void {
    const target = event.target;
    if (!(target instanceof HTMLSelectElement)) return;

    this.repartidorRutaId.set(target.value);
    const repartidor = this.repartidores().find(item => item.id === target.value);
    if (repartidor) this.selectRepartidor.emit(repartidor);
    this.limpiarRuta();
  }

  clienteSeleccionadoParaRuta(): Cliente | undefined {
    const id = this.clienteRutaId() ?? this.selectedClienteId();
    return this.clientes().find(cliente => cliente.id === id);
  }

  repartidorSeleccionadoParaRuta(): Repartidor | undefined {
    const id = this.repartidorRutaId() ?? this.selectedRepartidorId();
    return this.repartidores().find(repartidor => repartidor.id === id);
  }

  async calcularRuta(): Promise<void> {
    if (this.cargandoRuta()) return;

    const cliente = this.clienteSeleccionadoParaRuta();
    if (!cliente?.ubicacion || !this.isValidCoordinate(
      cliente.ubicacion.latitud,
      cliente.ubicacion.longitud
    )) {
      this.errorRuta.set('Selecciona un cliente con coordenadas válidas.');
      return;
    }

    const repartidor = this.repartidorSeleccionadoParaRuta();
    const config = this.companyConfig();
    const origen = this.toUbicacion(
      repartidor?.ubicacionActual?.latitud ?? config.latitudCentral,
      repartidor?.ubicacionActual?.longitud ?? config.longitudCentral
    );
    if (!origen) {
      this.errorRuta.set('No hay coordenadas válidas para el origen seleccionado.');
      return;
    }

    const requestId = ++this.generacionSolicitudManual;
    const originRequested = origen;
    this.cargandoRuta.set(true);
    this.errorRuta.set(null);
    this.comparacionRepartidores.set(null);
    this.lastRouteRequestAt = Date.now();

    try {
      const route = await this.rutasService.calcular(originRequested, cliente.ubicacion);
      if (requestId !== this.generacionSolicitudManual) return;
      if (route.estado !== 'OK' || route.geometria.length < 2) {
        throw new Error('OSRM devolvió una ruta vacía.');
      }

      this.lastRoutedPosition = repartidor?.ubicacionActual
        ? { ...repartidor.ubicacionActual }
        : undefined;
      this.routeRepartidorId.set(repartidor?.id ?? null);
      this.rutaActual.set(route);
      this.drawSelectedRoute(route);
    } catch (error) {
      if (requestId === this.generacionSolicitudManual) {
        this.rutaActual.set(null);
        this.drawSelectedRoute(null);
        this.errorRuta.set(this.mensajeError(error));
        console.warn('[OLITRACK] Falló el cálculo de una ruta OSRM.', {
          status: error instanceof HttpErrorResponse ? error.status : 0
        });
      }
    } finally {
      if (requestId === this.generacionSolicitudManual) {
        this.cargandoRuta.set(false);
      }
    }
  }

  async buscarRepartidorCercano(): Promise<void> {
    if (this.cargandoRuta()) return;

    const cliente = this.clienteSeleccionadoParaRuta();
    if (!cliente?.ubicacion || !this.isValidCoordinate(
      cliente.ubicacion.latitud,
      cliente.ubicacion.longitud
    )) {
      this.errorRuta.set('Selecciona un cliente con coordenadas válidas.');
      return;
    }

    const requestId = ++this.generacionSolicitudComparacion;
    this.cargandoRuta.set(true);
    this.errorRuta.set(null);
    this.comparacionRepartidores.set(null);

    try {
      const result = await this.rutasService.compararRepartidores(cliente.ubicacion);
      if (requestId !== this.generacionSolicitudComparacion) return;
      this.comparacionRepartidores.set(result);
      if (!result.repartidorRecomendadoId) {
        this.errorRuta.set(
          result.estado === 'SIN_REPARTIDORES'
            ? 'No hay repartidores disponibles con ubicación GPS.'
            : 'No se encontró una ruta vial para los repartidores disponibles.'
        );
      }
    } catch (error) {
      if (requestId === this.generacionSolicitudComparacion) {
        this.errorRuta.set(this.mensajeError(error));
        console.warn('[OLITRACK] Falló la comparación de rutas OSRM.', {
          status: error instanceof HttpErrorResponse ? error.status : 0
        });
      }
    } finally {
      if (requestId === this.generacionSolicitudComparacion) {
        this.cargandoRuta.set(false);
      }
    }
  }

  nombreRepartidor(repartidorId: string): string {
    const repartidor = this.repartidores().find(item => item.id === repartidorId);
    return repartidor?.nombreUsuario || repartidor?.placaVehiculo || repartidorId;
  }

  private mensajeError(error: unknown): string {
    if (error instanceof HttpErrorResponse && [404, 502, 504].includes(error.status)) {
      return 'Ruta vial no disponible. El destino podría estar fuera de la cobertura vial.';
    }
    return 'No se pudo calcular la ruta por calles. Revisa la conexión y los permisos.';
  }

  private limpiarRuta(): void {
    this.generacionSolicitudManual++;
    this.generacionSolicitudComparacion++;
    this.cargandoRuta.set(false);
    this.rutaActual.set(null);
    this.comparacionRepartidores.set(null);
    this.errorRuta.set(null);
    this.routeRepartidorId.set(null);
    this.lastRoutedPosition = undefined;
    this.drawSelectedRoute(null);
  }

  private toUbicacion(
    latitud: number | null | undefined,
    longitud: number | null | undefined
  ): { latitud: number; longitud: number } | null {
    return this.isValidCoordinate(latitud, longitud)
      ? { latitud: latitud!, longitud: longitud! }
      : null;
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
