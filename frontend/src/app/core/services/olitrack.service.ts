import {
  Injectable,
  computed,
  inject,
  signal
} from '@angular/core';

import {
  HttpClient,
  HttpErrorResponse,
  HttpParams
} from '@angular/common/http';

import { firstValueFrom } from 'rxjs';

import { AuthService } from './auth.service';
import type { ProductoRequest } from '../models/producto.models';
export type { ProductoRequest } from '../models/producto.models';

import {
  Usuario, Rol, RegistroRequest, Cliente, Repartidor, Producto, Inventario, Kardex, Pedido,
  MovimientoRequest,
  PedidoCrearRequest, EstadoPedido, MetodoPago, EstadoRepartidor, Incidencia, TipoIncidencia,
  EstadoIncidencia, Consulta, Notificacion, CartItem, ToastMessage, ClienteActualizarRequest,
  DireccionCrearRequest, DashboardResponse, Productividad, Zona, ZonaRequest, ConfiguracionEmpresa,
  AuditLog, Promocion, PromocionRequest, Faq, FaqRequest, AlertasResponse, ResetLinkResponse,
  UsuarioActualizarRequest
} from '../models/olitrack.models';

import { environment } from '../../../environments/environment';

@Injectable({
  providedIn: 'root'
})
export class OlitrackService {

  // ============================================================
  // DEPENDENCIAS
  // ============================================================

  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly base = environment.apiUrl;

  imagenProductoUrl(url?: string): string {
    if (!url) {
      return '';
    }

    if (url.startsWith('/api/') && /^https?:\/\//i.test(this.base)) {
      return new URL(url, this.base).toString();
    }

    return url;
  }

  // ============================================================
  // HELPERS HTTP
  // ============================================================

  private get<T>(
    path: string,
    params?: HttpParams
  ): Promise<T> {
    return firstValueFrom(
      this.http.get<T>(
        `${this.base}${path}`,
        { params }
      )
    );
  }

  private post<T>(
    path: string,
    body: unknown = {}
  ): Promise<T> {
    return firstValueFrom(
      this.http.post<T>(
        `${this.base}${path}`,
        body
      )
    );
  }

  private put<T>(
    path: string,
    body: unknown = {}
  ): Promise<T> {
    return firstValueFrom(
      this.http.put<T>(
        `${this.base}${path}`,
        body
      )
    );
  }

  private patch<T>(
    path: string,
    body: unknown = {}
  ): Promise<T> {
    return firstValueFrom(
      this.http.patch<T>(
        `${this.base}${path}`,
        body
      )
    );
  }

  private remove<T>(
    path: string
  ): Promise<T> {
    return firstValueFrom(
      this.http.delete<T>(
        `${this.base}${path}`
      )
    );
  }

  // ============================================================
  // MANEJO DE ERRORES
  // ============================================================

  /**
   * Extrae un mensaje legible de un error HTTP.
   */
  mensajeError(
    error: unknown,
    fallback = 'Ocurrió un error inesperado.'
  ): string {

    if (error instanceof HttpErrorResponse) {

      if (error.status === 0) {
        return 'No hay conexión con el servidor.';
      }

      const body = error.error;

      if (
        typeof body === 'string' &&
        body.trim()
      ) {
        return body;
      }

      return (
        body?.message ??
        body?.mensaje ??
        fallback
      );
    }

    if (error instanceof Error && error.message) {
      return error.message;
    }

    return fallback;
  }

  // ============================================================
  // ESTADO GLOBAL
  // ============================================================

  readonly companyConfig =
    signal<ConfiguracionEmpresa>({
      nombre: 'OLI FRESH ICE',
      ruc: '',
      direccionCentral: 'Ayacucho, Perú',
      telefono: '',
      correo: '',
      horarioApertura: '08:00',
      horarioCierre: '19:00',
      diasAtencion: 'Lunes a sábado',
      validarHorarioAtencion: true,
      latitudCentral: -13.1635,
      longitudCentral: -74.2240,
      metodosPagoHabilitados: [
        'EFECTIVO',
        'YAPE',
        'PLIN'
      ],
      tiempoEstimadoEntregaMinutos: 30,
      umbralRetrasoMinutos: 15,
      umbralSinAsignarMinutos: 10
    });

  readonly companyConfigDisplay =
    this.companyConfig.asReadonly();

  readonly users =
    signal<Usuario[]>([]);

  readonly productos =
    signal<Producto[]>([]);

  readonly inventarios =
    signal<Inventario[]>([]);

  readonly repartidores =
    signal<Repartidor[]>([]);

  readonly clientes =
    signal<Cliente[]>([]);

  readonly pedidos =
    signal<Pedido[]>([]);

  readonly alertas =
    signal<AlertasResponse | null>(null);

  readonly incidencias =
    signal<Incidencia[]>([]);

  readonly consultas =
    signal<Consulta[]>([]);

  readonly notificaciones =
    signal<Notificacion[]>([]);

  readonly zonas =
    signal<Zona[]>([]);

  readonly promociones =
    signal<Promocion[]>([]);

  readonly faqs =
    signal<Faq[]>([]);

  readonly auditoria =
    signal<AuditLog[]>([]);

  readonly dashboard =
    signal<DashboardResponse | null>(null);

  readonly productividad =
    signal<Productividad[]>([]);

  readonly clienteProfile =
    signal<Cliente | null>(null);

  readonly repartidorProfile =
    signal<Repartidor | null>(null);

  readonly currentUser =
    computed(
      () => this.authService.usuarioActualSignal()
    );

  readonly currentRole =
    computed<Rol | null>(
      () =>
        this.authService.usuarioActualSignal()?.rol ??
        null
    );

  // ============================================================
  // CARRITO
  // ============================================================

  readonly cart =
    signal<CartItem[]>([]);

  readonly cartSubtotal =
    computed(() =>
      this.cart().reduce(
        (sum, item) =>
          sum +
          item.producto.precioUnitario *
          item.cantidad,
        0
      )
    );

  readonly cartTotal =
    computed(() => this.cartSubtotal());

  readonly cartCount =
    computed(() =>
      this.cart().reduce(
        (sum, item) =>
          sum + item.cantidad,
        0
      )
    );

  addToCart(
    producto: Producto,
    cantidad = 1
  ): void {

    if (!Number.isInteger(cantidad) || cantidad <= 0) {
      return;
    }

    const existente = this.cart().find(
      item => item.producto.id === producto.id
    );
    const stockDisponible = producto.stockDisponible ?? 0;

    if ((existente?.cantidad ?? 0) + cantidad > stockDisponible) {
      this.triggerToast(
        'WARNING',
        'Stock insuficiente',
        stockDisponible > 0
          ? `Solo hay ${stockDisponible} unidad(es) disponibles de ${producto.nombre}.`
          : `${producto.nombre} no tiene stock disponible.`
      );
      return;
    }

    this.cart.update(prev => {

      if (existente) {

        return prev.map(item =>
          item.producto.id === producto.id
            ? {
                ...item,
                cantidad:
                  item.cantidad + cantidad
              }
            : item
        );
      }

      return [
        ...prev,
        {
          producto,
          cantidad
        }
      ];
    });

    this.triggerToast(
      'SUCCESS',
      'Producto agregado',
      `${cantidad}x ${producto.nombre}`
    );
  }

  updateCartQuantity(
    productoId: string,
    cantidad: number
  ): void {

    if (cantidad <= 0) {
      this.removeFromCart(productoId);
      return;
    }

    const item = this.cart().find(
      cartItem => cartItem.producto.id === productoId
    );
    const stockDisponible = item?.producto.stockDisponible ?? 0;
    if (cantidad > stockDisponible) {
      this.triggerToast(
        'WARNING',
        'Stock insuficiente',
        `Solo hay ${stockDisponible} unidad(es) disponibles.`
      );
      return;
    }

    this.cart.update(prev =>
      prev.map(item =>
        item.producto.id === productoId
          ? {
              ...item,
              cantidad
            }
          : item
      )
    );
  }

  removeFromCart(
    productoId: string
  ): void {

    this.cart.update(prev =>
      prev.filter(
        item =>
          item.producto.id !== productoId
      )
    );
  }

  clearCart(): void {
    this.cart.set([]);
  }

  // ============================================================
  // TOASTS
  // ============================================================

  readonly toasts =
    signal<ToastMessage[]>([]);

  triggerToast(
    tipo: ToastMessage['tipo'],
    titulo: string,
    mensaje: string
  ): void {

    const nuevo: ToastMessage = {
      id:
        `TST-${Date.now()}-${Math.random()
          .toString(36)
          .slice(2, 7)}`,

      tipo,
      titulo,
      mensaje,

      tiempo:
        new Date().toLocaleTimeString(
          'es-PE',
          {
            hour: '2-digit',
            minute: '2-digit'
          }
        )
    };

    this.toasts.update(lista => [
      nuevo,
      ...lista.slice(0, 4)
    ]);

    setTimeout(
      () =>
        this.dismissToast(nuevo.id),

      tipo === 'ERROR'
        ? 8000
        : 5000
    );
  }

  dismissToast(
    id: string
  ): void {

    this.toasts.update(lista =>
      lista.filter(
        toast =>
          toast.id !== id
      )
    );
  }

  // ============================================================
  // CONFIGURACIÓN
  // ============================================================

  async reloadConfiguracion(): Promise<void> {

    const config = await this.get<ConfiguracionEmpresa>(
      '/configuracion'
    );

    this.companyConfig.set(this.normalizarMetodosPago(config));
  }

  async actualizarConfiguracion(
    datos: ConfiguracionEmpresa
  ): Promise<void> {

    const config = await this.put<ConfiguracionEmpresa>(
      '/configuracion',
      {
        ...datos,
        metodosPagoHabilitados: this.metodosPagoPermitidos(
          datos.metodosPagoHabilitados
        )
      }
    );

    this.companyConfig.set(this.normalizarMetodosPago(config));

    this.triggerToast(
      'SUCCESS',
      'Configuración actualizada',
      'Los cambios fueron guardados correctamente.'
    );
  }

  private normalizarMetodosPago(
    config: ConfiguracionEmpresa
  ): ConfiguracionEmpresa {
    return {
      ...config,
      metodosPagoHabilitados: this.metodosPagoPermitidos(
        config.metodosPagoHabilitados
      )
    };
  }

  private metodosPagoPermitidos(
    metodos: MetodoPago[] | null | undefined
  ): MetodoPago[] {
    return (metodos ?? []).filter(
      metodo =>
        metodo === 'EFECTIVO' ||
        metodo === 'YAPE' ||
        metodo === 'PLIN'
    );
  }

  // ============================================================
  // USUARIOS
  // ============================================================

  async reloadUsuarios(): Promise<void> {

    this.users.set(
      await this.get<Usuario[]>(
        '/usuarios'
      )
    );
  }

  async crearUsuario(
    request: RegistroRequest
  ): Promise<Usuario> {

    const usuario =
      await this.post<Usuario>(
        '/usuarios',
        request
      );

    await this.reloadUsuarios();

    this.triggerToast(
      'SUCCESS',
      'Usuario creado',
      `${usuario.nombre} fue registrado correctamente.`
    );

    return usuario;
  }

  async actualizarUsuario(
    usuarioId: string,
    request: UsuarioActualizarRequest
  ): Promise<Usuario> {
    const usuario = await this.put<Usuario>(
      `/usuarios/${usuarioId}`,
      request
    );

    await this.reloadUsuarios();

    this.triggerToast(
      'SUCCESS',
      'Usuario actualizado',
      `${usuario.nombre} fue actualizado correctamente.`
    );

    return usuario;
  }

  async desactivarUsuario(
    usuarioId: string
  ): Promise<void> {

    await this.remove(
      `/usuarios/${usuarioId}`
    );

    await this.reloadUsuarios();

    this.triggerToast(
      'INFO',
      'Usuario desactivado',
      'El usuario ya no podrá iniciar sesión.'
    );
  }

  async cambiarRolUsuario(
    usuarioId: string,
    rol: Rol
  ): Promise<void> {

    await this.patch<Usuario>(
      `/usuarios/${usuarioId}/rol`,
      { rol }
    );

    await this.reloadUsuarios();

    this.triggerToast(
      'SUCCESS',
      'Rol actualizado',
      `El usuario ahora tiene el rol ${rol}.`
    );
  }

  async generarEnlaceReset(
    usuarioId: string
  ): Promise<string> {

    const respuesta =
      await this.post<ResetLinkResponse>(
        `/usuarios/${usuarioId}/restablecer-password`
      );

    return respuesta.link;
  }

  // ============================================================
  // PRODUCTOS
  // ============================================================

  async reloadProductos(
    admin = false
  ): Promise<void> {

    this.productos.set(
      await this.get<Producto[]>(
        admin
          ? '/productos/admin'
          : '/productos'
      )
    );
  }

  async crearProducto(
    request: ProductoRequest
  ): Promise<Producto> {

    const producto =
      await this.post<Producto>(
        '/productos',
        request
      );

    await this.reloadProductos(true);

    this.triggerToast(
      'SUCCESS',
      'Producto creado',
      `${producto.nombre} fue registrado correctamente.`
    );

    return producto;
  }

  async actualizarProducto(
    id: string,
    request: ProductoRequest
  ): Promise<Producto> {

    const producto =
      await this.put<Producto>(
        `/productos/${id}`,
        request
      );

    await this.reloadProductos(true);

    this.triggerToast(
      'SUCCESS',
      'Producto actualizado',
      `${producto.nombre} fue actualizado correctamente.`
    );

    return producto;
  }

  async subirImagenProducto(
    id: string,
    archivo: File
  ): Promise<Producto> {
    const formData = new FormData();
    formData.append('archivo', archivo);

    const producto = await this.post<Producto>(
      `/productos/${id}/imagen`,
      formData
    );

    await this.reloadProductos(true);
    this.triggerToast(
      'SUCCESS',
      'Imagen actualizada',
      `La imagen de ${producto.nombre} fue guardada correctamente.`
    );
    return producto;
  }

  async eliminarImagenProducto(id: string): Promise<Producto> {
    const producto = await this.remove<Producto>(
      `/productos/${id}/imagen`
    );

    await this.reloadProductos(true);
    this.triggerToast(
      'INFO',
      'Imagen eliminada',
      `Se quitó la imagen de ${producto.nombre}.`
    );
    return producto;
  }

  async desactivarProducto(
    id: string
  ): Promise<void> {

    await this.remove(
      `/productos/${id}`
    );

    await this.reloadProductos(true);

    this.triggerToast(
      'INFO',
      'Producto desactivado',
      'El producto dejó de estar disponible en el catálogo.'
    );
  }

  async reactivarProducto(
    id: string
  ): Promise<void> {

    await this.patch(
      `/productos/${id}/reactivar`
    );

    await this.reloadProductos(true);

    this.triggerToast(
      'SUCCESS',
      'Producto reactivado',
      'El producto vuelve a estar disponible en el catálogo.'
    );
  }

  // ============================================================
  // INVENTARIO
  // ============================================================

  async reloadInventarios(): Promise<void> {

    this.inventarios.set(
      await this.get<Inventario[]>(
        '/inventario'
      )
    );
  }

  async crearInventario(
    request: {
      productoId: string;
      stockActual: number;
      stockMinimo: number;
      stockMaximo: number;
    }
  ): Promise<Inventario> {
    const inventario = await this.post<Inventario>(
      '/inventario',
      request
    );
    this.inventarios.update(prev => [
      ...prev.filter(item => item.productoId !== inventario.productoId),
      inventario
    ]);
    return inventario;
  }

  async registrarMovimientoInventario(
    inventarioId: string,
    movimiento: MovimientoRequest
  ): Promise<void> {
    await this.patch(
      `/inventario/${inventarioId}/movimiento`,
      movimiento
    );
    await Promise.all([
      this.reloadInventarios(),
      this.reloadProductos(true)
    ]);
  }

  obtenerKardex(
    productoId: string
  ): Promise<Kardex[]> {

    return this.get<Kardex[]>(
      `/inventario/producto/${productoId}/kardex`
    );
  }

  // ============================================================
  // CLIENTES
  // ============================================================

  async reloadClientes(): Promise<void> {

    this.clientes.set(
      await this.get<Cliente[]>(
        '/clientes'
      )
    );
  }


// ============================================================
// CREAR PERFIL DE CLIENTE
// ============================================================

async crearClientePerfil(request: {
  usuarioId: string;
  nombreNegocio: string;
  direccion: string;
  referencia?: string;
  ubicacion: {
    latitud: number;
    longitud: number;
  };
}): Promise<Cliente> {

  console.log(
    '[OlitrackService] Creando perfil de cliente...',
    request
  );

  try {

    const cliente =
      await this.post<Cliente>(
        '/clientes',
        request
      );

    this.clienteProfile.set(
      cliente
    );

    console.log(
      '[OlitrackService] Perfil de cliente creado correctamente:',
      cliente
    );

    return cliente;

  } catch (error) {

    console.error(
      '[OlitrackService] Error creando perfil de cliente:',
      error
    );

    throw error;
  }
}



async reloadClienteProfile(): Promise<void> {

  try {

    console.log(
      '[OlitrackService] Cargando perfil de cliente...'
    );

    const perfil =
      await this.get<Cliente>(
        '/clientes/me'
      );

    this.clienteProfile.set(
      perfil
    );

    console.log(
      '[OlitrackService] Perfil de cliente cargado:',
      perfil
    );

  } catch (error) {

    console.error(
      '[OlitrackService] No se pudo cargar el perfil del cliente:',
      error
    );

    this.clienteProfile.set(
      null
    );

    throw error;
  }
}



  async updateClienteProfile(
    patch: ClienteActualizarRequest
  ): Promise<void> {

    this.clienteProfile.set(
      await this.put<Cliente>(
        '/clientes/me',
        patch
      )
    );

    this.triggerToast(
      'SUCCESS',
      'Datos actualizados',
      'Cambios guardados correctamente.'
    );
  }

  async agregarDireccion(
    request: DireccionCrearRequest
  ): Promise<void> {

    this.clienteProfile.set(
      await this.post<Cliente>(
        '/clientes/me/direcciones',
        request
      )
    );

    this.triggerToast(
      'SUCCESS',
      'Dirección agregada',
      request.alias
    );
  }

  async eliminarDireccion(
    direccionId: string
  ): Promise<void> {

    this.clienteProfile.set(
      await this.remove<Cliente>(
        `/clientes/me/direcciones/${direccionId}`
      )
    );
  }

  // ============================================================
  // REPARTIDORES
  // ============================================================

  async reloadRepartidores(): Promise<void> {

    this.repartidores.set(
      await this.get<Repartidor[]>(
        '/repartidores'
      )
    );
  }

  async reloadRepartidoresDisponibles(): Promise<void> {

    this.repartidores.set(
      await this.get<Repartidor[]>(
        '/repartidores/asignables'
      )
    );
  }

  async reloadRepartidorProfile(): Promise<void> {

    this.repartidorProfile.set(
      await this.get<Repartidor>(
        '/repartidores/me'
      )
    );
  }

  async iniciarSesionRepartidor(): Promise<void> {
    this.repartidorProfile.set(
      await this.post<Repartidor>(
        '/repartidores/me/iniciar-sesion',
        {}
      )
    );
  }

  async crearPerfilRepartidor(
    usuarioId: string,
    placaVehiculo: string
  ): Promise<Repartidor> {
    const repartidor = await this.post<Repartidor>(
      '/repartidores',
      { usuarioId, placaVehiculo }
    );
    await this.reloadRepartidores();
    return repartidor;
  }

  async updateDriverLocation(
    repartidorId: string,
    latitud: number,
    longitud: number
  ): Promise<void> {

    const actualizado =
      await this.put<Repartidor>(
        `/repartidores/${repartidorId}/ubicacion`,
        {
          latitud,
          longitud
        }
      );

    this.repartidores.update(lista =>
      lista.map(repartidor =>
        repartidor.id === repartidorId
          ? actualizado
          : repartidor
      )
    );

    if (
      this.repartidorProfile()?.id ===
      repartidorId
    ) {
      this.repartidorProfile.set(
        actualizado
      );
    }
  }

  async cambiarMiEstadoRepartidor(
    estado: EstadoRepartidor
  ): Promise<void> {

    this.repartidorProfile.set(
      await this.patch<Repartidor>(
        '/repartidores/me/estado',
        { estado }
      )
    );
  }

  // ============================================================
  // PEDIDOS
  // ============================================================

  private reemplazarPedido(
    actualizado: Pedido
  ): Pedido {

    this.pedidos.update(lista =>
      lista.map(pedido =>
        pedido.id === actualizado.id
          ? actualizado
          : pedido
      )
    );

    return actualizado;
  }

  private async patchPedido(
    pedidoId: string,
    sufijo: string,
    body: unknown = {}
  ): Promise<Pedido> {

    const actualizado =
      await this.patch<Pedido>(
        `/pedidos/${pedidoId}${sufijo}`,
        body
      );

    return this.reemplazarPedido(
      actualizado
    );
  }

  async reloadPedidosAdmin(
    estado?: EstadoPedido,
    desde?: string,
    hasta?: string
  ): Promise<void> {

    let params =
      new HttpParams();

    if (estado) {
      params =
        params.set(
          'estado',
          estado
        );
    }

    if (desde) {
      params =
        params.set(
          'desde',
          desde
        );
    }

    if (hasta) {
      params =
        params.set(
          'hasta',
          hasta
        );
    }

    this.pedidos.set(
      await this.get<Pedido[]>(
        '/pedidos',
        params
      )
    );
  }

  async reloadMisPedidos(): Promise<void> {

    this.pedidos.set(
      await this.get<Pedido[]>(
        '/pedidos/mis-pedidos'
      )
    );
  }

  async reloadMisEntregas(): Promise<void> {

    this.pedidos.set(
      await this.get<Pedido[]>(
        '/pedidos/mis-entregas'
      )
    );
  }

  async reloadAlertas(): Promise<void> {

    this.alertas.set(
      await this.get<AlertasResponse>(
        '/pedidos/alertas'
      )
    );
  }

  async createOrder(
    data: {
      direccionEntrega: string;
      ubicacionEntrega?: {
        latitud: number;
        longitud: number;
      };
      observaciones?: string;
      metodoPago: MetodoPago;
    }
  ): Promise<Pedido> {

    if (this.cart().length === 0) {
      throw new Error(
        'El carrito está vacío.'
      );
    }

    const productosSinStock = this.cart().filter(item => {
      const productoActual = this.productos().find(
        producto => producto.id === item.producto.id
      ) ?? item.producto;
      return item.cantidad > (productoActual.stockDisponible ?? 0);
    });
    if (productosSinStock.length > 0) {
      const detalle = productosSinStock
        .map(item => {
          const productoActual = this.productos().find(
            producto => producto.id === item.producto.id
          ) ?? item.producto;
          return `${productoActual.nombre}: disponible ${productoActual.stockDisponible ?? 0}, solicitado ${item.cantidad}`;
        })
        .join('; ');
      throw new Error(`No hay stock suficiente para completar el pedido. ${detalle}`);
    }

    const request: PedidoCrearRequest = {
      direccionEntrega:
        data.direccionEntrega,

      ubicacionEntrega:
        data.ubicacionEntrega,

      observaciones:
        data.observaciones,

      metodoPago:
        data.metodoPago,

      productos:
        this.cart().map(item => ({
          productoId:
            item.producto.id,

          cantidad:
            item.cantidad
        }))
    };

    const nuevoPedido =
      await this.post<Pedido>(
        '/pedidos',
        request
      );

    this.clearCart();

    this.pedidos.update(prev => [
      nuevoPedido,
      ...prev
    ]);

    this.triggerToast(
      'SUCCESS',
      '¡Pedido confirmado!',
      `Total: S/. ${nuevoPedido.total.toFixed(2)}`
    );

    if (!nuevoPedido.repartidorId) {
      this.triggerToast(
        'WARNING',
        'Pedido creado sin repartidor',
        request.ubicacionEntrega
          ? 'No hay un repartidor disponible con GPS. El pedido queda pendiente para asignarse al actualizar una ubicación o desde supervisión.'
          : 'El pedido no tiene coordenadas de entrega y no se pudo calcular la cercanía. Revisa la ubicación guardada del cliente o asígnalo desde supervisión.'
      );
    }

    return nuevoPedido;
  }

  async editarPedido(
    pedidoId: string,
    direccionEntrega: string,
    observaciones?: string
  ): Promise<void> {

    const actualizado =
      await this.put<Pedido>(
        `/pedidos/${pedidoId}`,
        {
          direccionEntrega,
          observaciones
        }
      );

    this.reemplazarPedido(
      actualizado
    );
  }

  async cancelarPedido(
    pedidoId: string
  ): Promise<void> {

    await this.patchPedido(
      pedidoId,
      '/cancelar'
    );

    this.triggerToast(
      'INFO',
      'Pedido cancelado',
      ''
    );
  }

  async subirEvidencia(
    pedidoId: string,
    archivo: File
  ): Promise<void> {

    const formData =
      new FormData();

    formData.append(
      'archivo',
      archivo
    );

    const actualizado =
      await this.post<Pedido>(
        `/pedidos/${pedidoId}/evidencia`,
        formData
      );

    this.reemplazarPedido(
      actualizado
    );
  }

  async descargarEvidencia(pedidoId: string): Promise<Blob> {
    return firstValueFrom(
      this.http.get(
        `${this.base}/pedidos/${pedidoId}/evidencia/archivo`,
        { responseType: 'blob' }
      )
    );
  }

  async calificarPedido(
    pedidoId: string,
    estrellas: number,
    comentario?: string
  ): Promise<void> {

    const actualizado =
      await this.post<Pedido>(
        `/pedidos/${pedidoId}/calificacion`,
        {
          estrellas,
          comentario
        }
      );

    this.reemplazarPedido(
      actualizado
    );

    this.triggerToast(
      'SUCCESS',
      '¡Gracias por tu calificación!',
      ''
    );
  }

  async changeOrderStatus(
    pedidoId: string,
    nuevoEstado: EstadoPedido,
    comentario?: string
  ): Promise<void> {

    await this.patchPedido(
      pedidoId,
      '/estado',
      {
        estado: nuevoEstado,
        comentario
      }
    );

    this.triggerToast(
      'INFO',
      'Estado de pedido',
      `Actualizado a ${nuevoEstado}`
    );
  }

  async validarPedido(
    pedidoId: string
  ): Promise<void> {

    await this.patchPedido(
      pedidoId,
      '/validar'
    );
  }

  async rechazarPedido(
    pedidoId: string,
    observaciones: string
  ): Promise<void> {

    await this.patchPedido(
      pedidoId,
      '/rechazar',
      {
        observaciones
      }
    );
  }

  async asignarRepartidor(
    pedidoId: string,
    repartidorId: string
  ): Promise<void> {

    await this.patchPedido(
      pedidoId,
      '/repartidor',
      {
        repartidorId
      }
    );
  }

  async asignarRepartidorAutomatico(
    pedidoId: string
  ): Promise<void> {

    await this.patchPedido(
      pedidoId,
      '/repartidor/auto'
    );

    this.triggerToast(
      'SUCCESS',
      'Repartidor asignado',
      'Se asignó automáticamente el repartidor disponible.'
    );
  }

  async reasignarRepartidor(
    pedidoId: string,
    repartidorId: string,
    comentario?: string
  ): Promise<void> {

    await this.patchPedido(
      pedidoId,
      '/repartidor/reasignar',
      {
        repartidorId,
        comentario
      }
    );
  }

  async aceptarEntrega(
    pedidoId: string
  ): Promise<void> {

    await this.patchPedido(
      pedidoId,
      '/aceptar'
    );
  }

  async registrarLlegada(
    pedidoId: string
  ): Promise<void> {

    await this.patchPedido(
      pedidoId,
      '/llegada'
    );
  }

  async registrarEntregaFallida(
    pedidoId: string,
    motivo: string,
    tipo?: TipoIncidencia
  ): Promise<void> {

    await this.patchPedido(
      pedidoId,
      '/entrega-fallida',
      {
        motivo,
        tipo
      }
    );
  }

  // ============================================================
  // INCIDENCIAS
  // ============================================================

  async reloadIncidencias(): Promise<void> {

    this.incidencias.set(
      await this.get<Incidencia[]>(
        '/incidencias'
      )
    );
  }

  async registrarIncidencia(
    pedidoId: string,
    tipo: TipoIncidencia,
    descripcion: string
  ): Promise<void> {

    await this.post(
      '/incidencias',
      {
        pedidoId,
        tipo,
        descripcion
      }
    );

    this.triggerToast(
      'SUCCESS',
      'Incidencia registrada',
      'El administrador la revisará pronto.'
    );
  }

  async cambiarEstadoIncidencia(
    id: string,
    estado: EstadoIncidencia
  ): Promise<void> {

    await this.patch(
      `/incidencias/${id}/estado`,
      {
        estado
      }
    );

    await this.reloadIncidencias();
  }

  // ============================================================
  // CONSULTAS
  // ============================================================

  async reloadConsultas(): Promise<void> {

    this.consultas.set(
      await this.get<Consulta[]>(
        '/consultas'
      )
    );
  }

  async marcarConsultaAtendida(
    id: string
  ): Promise<void> {

    await this.patch(
      `/consultas/${id}/atendida`
    );

    await this.reloadConsultas();
  }

  // ============================================================
  // NOTIFICACIONES
  // ============================================================

  async reloadMisNotificaciones(): Promise<void> {

    this.notificaciones.set(
      await this.get<Notificacion[]>(
        '/notificaciones/mias'
      )
    );
  }

  async marcarNotificacionLeida(
    id: string
  ): Promise<void> {

    await this.patch(
      `/notificaciones/${id}/leida`
    );

    this.notificaciones.update(
      lista =>
        lista.map(notificacion =>
          notificacion.id === id
            ? {
                ...notificacion,
                leida: true
              }
            : notificacion
        )
    );
  }

  // ============================================================
  // REPORTES
  // ============================================================

  async reloadDashboard(): Promise<void> {

    this.dashboard.set(
      await this.get<DashboardResponse>(
        '/reportes/dashboard'
      )
    );
  }

  async reloadProductividad(): Promise<void> {

    this.productividad.set(
      await this.get<Productividad[]>(
        '/reportes/productividad'
      )
    );
  }

  urlExportarPedidosCsv(): string {
    return `${this.base}/reportes/pedidos/exportar`;
  }

  urlExportarBackup(): string {
    return `${this.base}/backup/exportar`;
  }

  // ============================================================
  // ZONAS
  // ============================================================

  async reloadZonas(): Promise<void> {

    this.zonas.set(
      await this.get<Zona[]>(
        '/zonas'
      )
    );
  }

  async crearZona(
    request: ZonaRequest
  ): Promise<void> {

    await this.post(
      '/zonas',
      request
    );

    await this.reloadZonas();
  }

  async actualizarZona(
    id: string,
    request: ZonaRequest
  ): Promise<void> {

    await this.put(
      `/zonas/${id}`,
      request
    );

    await this.reloadZonas();
  }

  async cambiarActivaZona(
    id: string,
    activa: boolean
  ): Promise<void> {

    if (activa) {

      await this.patch(
        `/zonas/${id}/reactivar`
      );

    } else {

      await this.remove(
        `/zonas/${id}`
      );
    }

    await this.reloadZonas();
  }

  // ============================================================
  // PROMOCIONES
  // ============================================================

  async reloadPromociones(
    vigentesSolo = false
  ): Promise<void> {

    this.promociones.set(
      await this.get<Promocion[]>(
        vigentesSolo
          ? '/promociones'
          : '/promociones/todas'
      )
    );
  }

  async crearPromocion(
    request: PromocionRequest
  ): Promise<void> {

    await this.post(
      '/promociones',
      request
    );

    await this.reloadPromociones();
  }

  // ============================================================
  // FAQ
  // ============================================================

  async reloadFaqs(
    soloActivas = false
  ): Promise<void> {

    this.faqs.set(
      await this.get<Faq[]>(
        soloActivas
          ? '/faqs'
          : '/faqs/todas'
      )
    );
  }

  async crearFaq(
    request: FaqRequest
  ): Promise<void> {

    await this.post(
      '/faqs',
      request
    );

    await this.reloadFaqs();
  }


  async reloadAuditoria(
    entidad?: string
  ): Promise<void> {

    const params =
      entidad
        ? new HttpParams().set(
            'entidad',
            entidad
          )
        : undefined;

    this.auditoria.set(
      await this.get<AuditLog[]>(
        '/auditoria',
        params
      )
    );
  }
}
