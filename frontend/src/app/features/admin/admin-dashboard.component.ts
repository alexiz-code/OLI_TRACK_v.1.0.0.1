import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  afterNextRender,
  computed,
  inject,
  signal
} from '@angular/core';

import {
  DatePipe,
  DecimalPipe
} from '@angular/common';

import {
  FormsModule
} from '@angular/forms';

import {  NavbarComponent} from '../../shared/components/navbar/navbar.component';
import {  AyacuchoMapComponent} from '../../shared/components/map/ayacucho-map.component';
import {  IncidenciasChartsComponent} from '../../shared/components/incidencias-charts.component';
import {  OlitrackService, ProductoRequest} from '../../core/services/olitrack.service';
import {  Cliente, MovimientoRequest, Producto, Repartidor, Rol, Usuario} from '../../core/models/olitrack.models';


type Tab =
  | 'MAPA_GPS'
  | 'PEDIDOS'
  | 'USUARIOS'
  | 'CATALOGO'
  | 'REPORTES';

interface ProductoForm {
  nombre: string;
  descripcion: string;
  categoria: string;
  precioUnitario: number;
  unidadMedida: string;
  imagenUrl: string;
  stockInicial: number;
}

interface UsuarioForm {
  nombre: string;
  email: string;
  password: string;
  telefono: string;
  rol: Rol;
  placaVehiculo: string;
}

interface UsuarioEdicionForm {
  nombre: string;
  telefono: string;
}

// ============================================================
// VALORES INICIALES
// ============================================================

const USUARIO_VACIO = (): UsuarioForm => ({
  nombre: '',
  email: '',
  password: '',
  telefono: '',
  rol: 'CLIENTE',
  placaVehiculo: ''
});

const PRODUCTO_VACIO = (): ProductoForm => ({
  nombre: '',
  descripcion: '',
  categoria: '',
  precioUnitario: 0,
  unidadMedida: '',
  imagenUrl: '',
  stockInicial: 0
});

// ============================================================
// CLASES VISUALES
// ============================================================

const ESTADO_CLASES: Record<string, string> = {
  PENDIENTE: 'bg-amber-100 text-amber-700',
  CONFIRMADO: 'bg-sky-100 text-sky-700',
  EN_PREPARACION: 'bg-orange-100 text-orange-700',
  EN_CAMINO: 'bg-blue-100 text-blue-700',
  ENTREGADO: 'bg-emerald-100 text-emerald-700',
  ENTREGA_FALLIDA: 'bg-red-100 text-red-700',
  CANCELADO: 'bg-red-100 text-red-700'
};

const TOAST_CLASES: Record<string, string> = {
  SUCCESS: 'border-l-emerald-500',
  INFO: 'border-l-blue-500',
  WARNING: 'border-l-amber-500',
  ERROR: 'border-l-red-500'
};

const CAMPO =
  'w-full px-3 py-2.5 rounded-xl border border-slate-300 bg-white focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none transition';

// ============================================================
// COMPONENTE
// ============================================================

@Component({
  selector: 'app-admin-dashboard',

  changeDetection: ChangeDetectionStrategy.OnPush,

  imports: [
    FormsModule,
    DecimalPipe,
    DatePipe,
    NavbarComponent,
    AyacuchoMapComponent,
    IncidenciasChartsComponent
  ],

  templateUrl: './admin-dashboard.component.html'
})
export class AdminDashboardComponent {

  // ============================================================
  // DEPENDENCIAS
  // ============================================================

  readonly service = inject(OlitrackService);

  private readonly destroyRef = inject(DestroyRef);

  // ============================================================
  // ESTILOS
  // ============================================================

  readonly campo = CAMPO;

  // ============================================================
  // TABS
  // ============================================================

  readonly tabs = [
    {
      id: 'MAPA_GPS' as Tab,
      label: 'Mapa GPS'
    },
    {
      id: 'PEDIDOS' as Tab,
      label: 'Pedidos'
    },
    {
      id: 'USUARIOS' as Tab,
      label: 'Usuarios'
    },
    {
      id: 'CATALOGO' as Tab,
      label: 'Catálogo'
    },
    {
      id: 'REPORTES' as Tab,
      label: 'Reportes'
    }
  ];

  readonly roles: {
    value: Rol;
    label: string;
  }[] = [
    {
      value: 'ADMINISTRADOR',
      label: 'Administrador'
    },
    {
      value: 'SUPERVISOR',
      label: 'Supervisor'
    },
    {
      value: 'CLIENTE',
      label: 'Cliente'
    },
    {
      value: 'REPARTIDOR',
      label: 'Repartidor'
    }
  ];

  readonly tab = signal<Tab>('MAPA_GPS');

  readonly cargando = signal(false);

  readonly guardando = signal(false);

  readonly usuariosPorCargo = computed(() =>
    this.roles.map(rol => ({
      ...rol,
      usuarios: this.service.users().filter(usuario => usuario.rol === rol.value)
    }))
  );

  // ============================================================
  // SELECCIÓN DEL MAPA
  // ============================================================

  readonly selectedClienteId =
    signal<string | undefined>(undefined);

  readonly selectedRepartidorId =
    signal<string | undefined>(undefined);

  readonly selectedCliente =
    computed(() =>
      this.service
        .clientes()
        .find(
          cliente =>
            cliente.id === this.selectedClienteId()
        ) ?? null
    );

  readonly selectedRepartidor =
    computed(() =>
      this.service
        .repartidores()
        .find(
          repartidor =>
            repartidor.id === this.selectedRepartidorId()
        ) ?? null
    );

  // ============================================================
  // MODAL USUARIO
  // ============================================================

  readonly mostrarModalUsuario =
    signal(false);

  readonly usuarioEditando =
    signal<Usuario | null>(null);

  usuarioEdicionForm: UsuarioEdicionForm = {
    nombre: '',
    telefono: ''
  };

  usuarioForm: UsuarioForm =
    USUARIO_VACIO();

  // ============================================================
  // MODAL PRODUCTO
  // ============================================================

  readonly mostrarModalProducto =
    signal(false);

  readonly productoEditando =
    signal<Producto | null>(null);

  productoForm: ProductoForm =
    PRODUCTO_VACIO();

  readonly productoParaMovimiento = signal<Producto | null>(null);
  readonly guardandoMovimiento = signal(false);
  movimientoForm: MovimientoRequest = {
    tipo: 'ENTRADA',
    cantidad: 1,
    motivo: ''
  };

  // ============================================================
  // COMPUTED
  // ============================================================

  readonly repartidoresEnRuta =
    computed(() =>
      this.service
        .repartidores()
        .filter(
          repartidor =>
            repartidor.estado === 'EN_RUTA'
        )
        .length
    );

  readonly pedidosPendientes =
    computed(() =>
      this.service
        .pedidos()
        .filter(
          pedido =>
            pedido.estado === 'PENDIENTE' ||
            pedido.estado === 'CONFIRMADO' ||
            pedido.estado === 'EN_PREPARACION'
        )
        .length
    );

  readonly pedidosEnCamino =
    computed(() =>
      this.service
        .pedidos()
        .filter(
          pedido =>
            pedido.estado === 'EN_CAMINO'
        )
        .length
    );

  readonly pedidosEntregados =
    computed(() =>
      this.service
        .pedidos()
        .filter(
          pedido =>
            pedido.estado === 'ENTREGADO'
        )
        .length
    );

  readonly productosActivos =
    computed(() =>
      this.service
        .productos()
        .filter(
          producto =>
            producto.activo
        )
        .length
    );

  readonly productosInactivos =
    computed(() =>
      this.service
        .productos()
        .filter(
          producto =>
            !producto.activo
        )
        .length
    );

  readonly stockTotal =
    computed(() =>
      this.service
        .productos()
        .reduce(
          (total, producto) =>
            total + this.stockActualProducto(producto),
          0
        )
    );

  // ============================================================
  // CONSTRUCTOR
  // ============================================================

  constructor() {

    /*
     * La carga inicial se ejecuta solamente en navegador.
     * Esto evita provocar solicitudes HTTP durante SSR.
     */
    afterNextRender(() => {

      void this.cargarDatos();

      const timer =
        setInterval(() => {

          if (
            this.tab() === 'MAPA_GPS' &&
            !document.hidden
          ) {

            this.service
              .reloadRepartidores()
              .catch(() => undefined);

          }

        }, 15000);

      this.destroyRef.onDestroy(
        () => clearInterval(timer)
      );

    });

  }

  // ============================================================
  // CARGA DE DATOS
  // ============================================================

  async cargarDatos(): Promise<void> {

    this.cargando.set(true);

    try {

      const resultados =
        await Promise.allSettled([

          this.service.reloadUsuarios(),

          this.service.reloadProductos(true),

          this.service.reloadInventarios(),

          this.service.reloadClientes(),

          this.service.reloadRepartidores(),

          this.service.reloadPedidosAdmin(),

          this.service.reloadDashboard(),

          this.service.reloadIncidencias(),

          this.service.reloadConfiguracion()

        ]);

      const errores =
        resultados.filter(
          resultado =>
            resultado.status === 'rejected'
        );

      if (errores.length > 0) {

        console.error(
          '[AdminDashboard] Errores al cargar datos:',
          errores
        );

        this.service.triggerToast(
          'WARNING',
          'Carga parcial',
          `Se cargaron los datos disponibles, pero ${errores.length} solicitud(es) presentaron problemas.`
        );
      }

    } catch (error) {

      console.error(
        '[AdminDashboard] Error cargando datos:',
        error
      );

      this.service.triggerToast(
        'ERROR',
        'Error de carga',
        this.service.mensajeError(
          error,
          'No se pudieron cargar los datos del panel.'
        )
      );

    } finally {

      this.cargando.set(false);

    }
  }

  async recargarTodo(): Promise<void> {

    await this.cargarDatos();

  }

  // ============================================================
  // HELPERS
  // ============================================================

  estadoClase(
    estado: string
  ): string {

    return (
      ESTADO_CLASES[estado] ??
      'bg-slate-100 text-slate-700'
    );

  }

  toastClase(
    tipo: string
  ): string {

    return (
      TOAST_CLASES[tipo] ??
      'border-l-slate-500'
    );

  }

  stockClase(
    stock: number | undefined
  ): string {

    const valor =
      stock ?? 0;

    if (valor <= 0) {
      return 'text-red-600';
    }

    if (valor <= 10) {
      return 'text-amber-600';
    }

    return 'text-emerald-600';

  }

  nombreCliente(
    clienteId: string
  ): string {

    return (
      this.service
        .clientes()
        .find(
          cliente =>
            cliente.id === clienteId
        )
        ?.nombreNegocio ??
      clienteId
    );

  }

  nombreUsuarioRepartidor(
    repartidor: Repartidor
  ): string {

    return (
      this.service
        .users()
        .find(
          usuario =>
            usuario.id === repartidor.usuarioId
        )
        ?.nombre ??
      `Repartidor ${repartidor.id}`
    );

  }

  // ============================================================
  // MAPA
  // ============================================================

  /*
   * IMPORTANTE:
   *
   * AyacuchoMapComponent emite:
   *
   *   Cliente
   *   Repartidor
   *
   * y NO:
   *
   *   string
   *
   * Por eso recibimos el objeto completo y usamos .id.
   */

  seleccionarCliente(
    cliente: Cliente
  ): void {

    this.selectedClienteId.set(
      cliente.id
    );

    this.selectedRepartidorId.set(
      undefined
    );

  }

  seleccionarRepartidor(
    repartidor: Repartidor
  ): void {

    this.selectedRepartidorId.set(
      repartidor.id
    );

    this.selectedClienteId.set(
      undefined
    );

  }

  cambiarRolNuevoUsuario(rol: Rol): void {
    this.usuarioForm.rol = rol;
    if (rol !== 'REPARTIDOR') {
      this.usuarioForm.placaVehiculo = '';
    }
  }

  placaDeUsuario(usuarioId: string): string {
    return this.service.repartidores().find(
      repartidor => repartidor.usuarioId === usuarioId
    )?.placaVehiculo ?? '—';
  }

  tienePerfilRepartidor(usuarioId: string): boolean {
    return this.service.repartidores().some(
      repartidor => repartidor.usuarioId === usuarioId
    );
  }

  abrirEdicionUsuario(usuario: Usuario): void {
    this.usuarioEdicionForm = {
      nombre: usuario.nombre,
      telefono: usuario.telefono ?? ''
    };
    this.usuarioEditando.set(usuario);
  }

  cerrarEdicionUsuario(): void {
    if (this.guardando()) {
      return;
    }

    this.usuarioEditando.set(null);
    this.usuarioEdicionForm = { nombre: '', telefono: '' };
  }

  async guardarEdicionUsuario(): Promise<void> {
    const usuario = this.usuarioEditando();
    const nombre = this.usuarioEdicionForm.nombre.trim();
    const telefono = this.usuarioEdicionForm.telefono.trim();

    if (!usuario) {
      return;
    }

    if (!nombre) {
      this.service.triggerToast(
        'WARNING',
        'Nombre requerido',
        'Ingrese el nombre del usuario.'
      );
      return;
    }

    if (telefono && !/^\+?[0-9]{6,15}$/.test(telefono)) {
      this.service.triggerToast(
        'WARNING',
        'Teléfono inválido',
        'Use entre 6 y 15 dígitos; puede incluir + al inicio.'
      );
      return;
    }

    this.guardando.set(true);
    try {
      await this.service.actualizarUsuario(usuario.id, {
        nombre,
        telefono: telefono || undefined
      });
      this.usuarioEditando.set(null);
      this.usuarioEdicionForm = { nombre: '', telefono: '' };
    } catch (error) {
      console.error('[AdminDashboard] Error actualizando usuario:', error);
      this.service.triggerToast(
        'ERROR',
        'No se pudo actualizar el usuario',
        this.service.mensajeError(error, 'Verifique los datos e intente nuevamente.')
      );
    } finally {
      this.guardando.set(false);
    }
  }

  async crearPerfilRepartidor(usuario: Usuario): Promise<void> {
    const placa = prompt(
      `Ingresa la placa del vehículo de ${usuario.nombre}:`
    )?.trim();
    if (!placa) return;

    try {
      await this.service.crearPerfilRepartidor(usuario.id, placa);
      this.service.triggerToast(
        'SUCCESS',
        'Perfil de repartidor creado',
        `${usuario.nombre} ya puede acceder a sus entregas.`
      );
    } catch (error) {
      this.service.triggerToast(
        'ERROR',
        'No se pudo crear el perfil',
        this.service.mensajeError(error, 'Verifica la placa e intenta nuevamente.')
      );
    }
  }

  // ============================================================
  // USUARIOS
  // ============================================================

  abrirModalUsuario(): void {

    this.usuarioForm =
      USUARIO_VACIO();

    this.mostrarModalUsuario.set(
      true
    );

  }

  cerrarModalUsuario(): void {

    if (this.guardando()) {
      return;
    }

    this.mostrarModalUsuario.set(
      false
    );

    this.usuarioForm =
      USUARIO_VACIO();

  }

  async guardarUsuario(): Promise<void> {

    const f =
      this.usuarioForm;

    if (!f.nombre.trim()) {

      this.service.triggerToast(
        'WARNING',
        'Nombre requerido',
        'Ingrese el nombre del usuario.'
      );

      return;
    }

    if (!f.email.trim()) {

      this.service.triggerToast(
        'WARNING',
        'Correo requerido',
        'Ingrese el correo electrónico.'
      );

      return;
    }

    if (!f.password.trim()) {

      this.service.triggerToast(
        'WARNING',
        'Contraseña requerida',
        'Ingrese una contraseña.'
      );

      return;
    }

    if (f.password.length < 6) {

      this.service.triggerToast(
        'WARNING',
        'Contraseña inválida',
        'La contraseña debe tener al menos 6 caracteres.'
      );

      return;
    }

    if (!f.rol) {

      this.service.triggerToast(
        'WARNING',
        'Rol requerido',
        'Seleccione el rol del usuario.'
      );

      return;
    }

    const placaVehiculo = f.placaVehiculo.trim();
    if (f.rol === 'REPARTIDOR' && !placaVehiculo) {
      this.service.triggerToast(
        'WARNING',
        'Placa requerida',
        'Ingresa la placa del vehículo para crear el perfil del repartidor.'
      );
      return;
    }

    this.guardando.set(true);

    try {

      const usuario = await this.service.crearUsuario({

        nombre:
          f.nombre.trim(),

        email:
          f.email.trim(),

        password:
          f.password,

        telefono:
          f.telefono.trim() ||
          undefined,

        rol:
          f.rol

      });

      if (f.rol === 'REPARTIDOR') {
        try {
          await this.service.crearPerfilRepartidor(usuario.id, placaVehiculo);
        } catch (error) {
          this.service.triggerToast(
            'WARNING',
            'Usuario creado; falta su perfil',
            `La cuenta se creó, pero no se pudo crear el perfil de repartidor: ${this.service.mensajeError(error, 'verifica la placa')}. Usa "Crear perfil repartidor" en la fila del usuario para completarlo.`
          );
        }
      }

      this.mostrarModalUsuario.set(
        false
      );

      this.usuarioForm =
        USUARIO_VACIO();

    } catch (error) {

      console.error(
        '[AdminDashboard] Error creando usuario:',
        error
      );

      this.service.triggerToast(
        'ERROR',
        'No se pudo crear el usuario',
        this.service.mensajeError(
          error,
          'Verifique los datos e intente nuevamente.'
        )
      );

    } finally {

      this.guardando.set(false);

    }

  }

  async cambiarRol(
    usuario: Usuario,
    evento: Rol
  ): Promise<void> {

    const nuevoRol =
      evento;

    if (
      !nuevoRol ||
      nuevoRol === usuario.rol
    ) {

      return;

    }

    try {

      await this.service.cambiarRolUsuario(
        usuario.id,
        nuevoRol
      );

    } catch (error) {

      console.error(
        '[AdminDashboard] Error cambiando rol:',
        error
      );

      this.service.triggerToast(
        'ERROR',
        'No se pudo cambiar el rol',
        this.service.mensajeError(
          error,
          'No se pudo actualizar el rol del usuario.'
        )
      );

      await this.service.reloadUsuarios();

    }

  }

  async restablecerPassword(
    usuario: Usuario
  ): Promise<void> {

    try {

      const link =
        await this.service.generarEnlaceReset(
          usuario.id
        );

      if (navigator.clipboard) {

        await navigator.clipboard.writeText(
          link
        );

        this.service.triggerToast(
          'SUCCESS',
          'Enlace generado',
          'El enlace de restablecimiento fue copiado al portapapeles.'
        );

        return;

      }

      window.prompt(
        'Enlace de restablecimiento:',
        link
      );

    } catch (error) {

      console.error(
        '[AdminDashboard] Error generando enlace:',
        error
      );

      this.service.triggerToast(
        'ERROR',
        'No se pudo generar el enlace',
        this.service.mensajeError(
          error,
          'No se pudo generar el enlace de restablecimiento.'
        )
      );

    }

  }

  // ============================================================
  // PRODUCTOS
  // ============================================================

  stockActualProducto(producto: Producto | null): number {
    if (!producto) return 0;
    return this.service.inventarios().find(
      inventario => inventario.productoId === producto.id
    )?.stockActual ?? producto.stockDisponible ?? 0;
  }

  tieneInventario(producto: Producto | null): boolean {
    return !!producto && this.service.inventarios().some(
      inventario => inventario.productoId === producto.id
    );
  }

  abrirMovimientoInventario(producto: Producto): void {
    this.movimientoForm = { tipo: 'ENTRADA', cantidad: 1, motivo: '' };
    this.productoParaMovimiento.set(producto);
  }

  cerrarMovimientoInventario(): void {
    if (!this.guardandoMovimiento()) this.productoParaMovimiento.set(null);
  }

  async guardarMovimientoInventario(): Promise<void> {
    const producto = this.productoParaMovimiento();
    let inventario = this.service.inventarios().find(
      registro => registro.productoId === producto?.id
    );
    const cantidad = Number(this.movimientoForm.cantidad);
    if (!producto) return;
    if (!Number.isInteger(cantidad) || cantidad <= 0) {
      this.service.triggerToast('WARNING', 'Cantidad inválida', 'Ingrese un número entero mayor que cero.');
      return;
    }
    if (!inventario && this.movimientoForm.tipo === 'SALIDA') {
      this.service.triggerToast(
        'WARNING',
        'Inventario sin stock',
        'No se puede registrar una salida porque este producto todavía no tiene inventario.'
      );
      return;
    }

    this.guardandoMovimiento.set(true);
    try {
      if (!inventario) {
        inventario = await this.service.crearInventario({
          productoId: producto.id,
          stockActual: 0,
          stockMinimo: 0,
          stockMaximo: 0
        });
      }
      await this.service.registrarMovimientoInventario(inventario.id, {
        tipo: this.movimientoForm.tipo,
        cantidad,
        motivo: this.movimientoForm.motivo?.trim() || undefined
      });
      this.productoParaMovimiento.set(null);
      this.service.triggerToast('SUCCESS', 'Inventario actualizado', `Stock de ${producto.nombre} actualizado.`);
    } catch (error) {
      this.service.triggerToast(
        'ERROR',
        'No se pudo actualizar el inventario',
        this.service.mensajeError(error, 'Verifique el stock disponible e intente nuevamente.')
      );
    } finally {
      this.guardandoMovimiento.set(false);
    }
  }

  abrirNuevoProducto(): void {

    this.productoEditando.set(
      null
    );

    this.productoForm =
      PRODUCTO_VACIO();

    this.mostrarModalProducto.set(
      true
    );

  }

  editarProducto(
    producto: Producto
  ): void {

    this.productoEditando.set(
      producto
    );

    this.productoForm = {

      nombre:
        producto.nombre,

      descripcion:
        producto.descripcion ?? '',

      categoria:
        producto.categoria,

      precioUnitario:
        producto.precioUnitario,

      unidadMedida:
        producto.unidadMedida,

      imagenUrl:
        producto.imagenUrl ?? '',

      stockInicial: 0

    };

    this.mostrarModalProducto.set(
      true
    );

  }

  cerrarModalProducto(): void {

    if (this.guardando()) {
      return;
    }

    this.mostrarModalProducto.set(
      false
    );

    this.productoEditando.set(
      null
    );

    this.productoForm =
      PRODUCTO_VACIO();

  }

  async guardarProducto(): Promise<void> {

    const f =
      this.productoForm;

    if (!f.nombre.trim()) {

      this.service.triggerToast(
        'WARNING',
        'Nombre requerido',
        'Ingrese el nombre del producto.'
      );

      return;
    }

    if (!f.categoria.trim()) {

      this.service.triggerToast(
        'WARNING',
        'Categoría requerida',
        'Ingrese la categoría del producto.'
      );

      return;
    }

    if (!f.unidadMedida.trim()) {

      this.service.triggerToast(
        'WARNING',
        'Unidad requerida',
        'Ingrese la unidad de medida.'
      );

      return;
    }

    const precio =
      Number(
        f.precioUnitario
      );

    if (
      !Number.isFinite(precio) ||
      precio < 0
    ) {

      this.service.triggerToast(
        'WARNING',
        'Precio inválido',
        'Ingrese un precio válido.'
      );

      return;
    }

    const stockInicial = Number(f.stockInicial);
    if (
      !this.productoEditando() &&
      (!Number.isInteger(stockInicial) || stockInicial < 0)
    ) {
      this.service.triggerToast(
        'WARNING',
        'Stock inicial inválido',
        'Ingrese una cantidad entera igual o mayor que cero.'
      );
      return;
    }

    const request: ProductoRequest = {

      nombre:
        f.nombre.trim(),

      descripcion:
        f.descripcion.trim() ||
        undefined,

      categoria:
        f.categoria.trim(),

      precioUnitario:
        precio,

      unidadMedida:
        f.unidadMedida.trim(),

      imagenUrl:
        f.imagenUrl.trim() ||
        undefined,

      ...(!this.productoEditando() ? { stockInicial } : {})

    };

    this.guardando.set(true);

    try {

      const actual =
        this.productoEditando();

      if (actual) {

        await this.service.actualizarProducto(
          actual.id,
          request
        );

      } else {

        await this.service.crearProducto(
          request
        );

      }

      this.mostrarModalProducto.set(
        false
      );

      this.productoEditando.set(
        null
      );

      this.productoForm =
        PRODUCTO_VACIO();

    } catch (error) {

      console.error(
        '[AdminDashboard] Error guardando producto:',
        error
      );

      this.service.triggerToast(
        'ERROR',
        'No se pudo guardar el producto',
        this.service.mensajeError(
          error,
          'Verifique los datos e intente nuevamente.'
        )
      );

    } finally {

      this.guardando.set(false);

    }

  }

  async desactivarProducto(
    producto: Producto
  ): Promise<void> {

    const confirmado =
      window.confirm(
        `¿Deseas desactivar el producto "${producto.nombre}"?`
      );

    if (!confirmado) {
      return;
    }

    try {

      await this.service.desactivarProducto(
        producto.id
      );

    } catch (error) {

      console.error(
        '[AdminDashboard] Error desactivando producto:',
        error
      );

      this.service.triggerToast(
        'ERROR',
        'No se pudo desactivar',
        this.service.mensajeError(
          error,
          'No se pudo desactivar el producto.'
        )
      );

    }

  }

  async reactivarProducto(
    producto: Producto
  ): Promise<void> {

    try {

      await this.service.reactivarProducto(
        producto.id
      );

    } catch (error) {

      console.error(
        '[AdminDashboard] Error reactivando producto:',
        error
      );

      this.service.triggerToast(
        'ERROR',
        'No se pudo reactivar',
        this.service.mensajeError(
          error,
          'No se pudo reactivar el producto.'
        )
      );

    }

  }

}
