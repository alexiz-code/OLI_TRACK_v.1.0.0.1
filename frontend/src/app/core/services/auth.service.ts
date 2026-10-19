
import { Inject, Injectable, PLATFORM_ID } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { isPlatformBrowser } from '@angular/common';

import {
  initializeApp,
  getApp,
  getApps
} from 'firebase/app';

import {
  getAuth,
  initializeAuth,
  browserSessionPersistence,
  signInWithEmailAndPassword,
  signOut,
  onAuthStateChanged,
  User as FirebaseUser
} from 'firebase/auth';
import type { Auth } from 'firebase/auth';

import {
  BehaviorSubject,
  firstValueFrom,
  Observable,
  timeout
} from 'rxjs';

import { environment } from '../../../environments/environment';

import {
  Usuario,
  RegistroRequest
} from '../models/olitrack.models';

@Injectable({
  providedIn: 'root'
})
export class AuthService {

  // =========================================================
  // FIREBASE
  // Reutiliza la aplicación existente para evitar duplicados.
  // =========================================================

  private readonly app =
    getApps().length > 0
      ? getApp()
      : initializeApp(environment.firebase);

  private readonly auth: Auth;

  // =========================================================
  // ESTADO DEL USUARIO
  // =========================================================

  private readonly usuarioActualSubject =
    new BehaviorSubject<Usuario | null>(null);

  readonly usuarioActual$: Observable<Usuario | null> =
    this.usuarioActualSubject.asObservable();

  readonly usuarioActualSignal =
    toSignal(this.usuarioActual$, { initialValue: null });

  private readonly cargandoSesionSubject =
    new BehaviorSubject<boolean>(true);

  readonly cargandoSesion$: Observable<boolean> =
    this.cargandoSesionSubject.asObservable();

  // UID al que pertenece el perfil guardado en memoria.
  private uidPerfilCargado: string | null = null;

  // Evita solicitar varias veces el mismo perfil simultáneamente.
  private perfilEnCarga: {
    uid: string;
    promise: Promise<void>;
  } | null = null;

  // =========================================================
  // CONSTRUCTOR
  // =========================================================

  constructor(
    private readonly http: HttpClient,
    private readonly router: Router,
    @Inject(PLATFORM_ID) platformId: object
  ) {
    this.auth = isPlatformBrowser(platformId)
      ? initializeAuth(this.app, {
          persistence: browserSessionPersistence
        })
      : getAuth(this.app);

    onAuthStateChanged(this.auth, async (firebaseUser) => {

      // Firebase notificó que no hay sesión.
      // Comprobamos que no haya iniciado otra sesión entretanto.
      if (!firebaseUser) {
        if (!this.auth.currentUser) {
          this.limpiarUsuarioEnMemoria();
          this.cargandoSesionSubject.next(false);
        }

        return;
      }

      const uid = firebaseUser.uid;

      this.cargandoSesionSubject.next(true);

      try {
        await this.cargarPerfilPropio(uid);
      } catch (error) {
        console.error(
          '[AuthService] No se pudo restaurar el perfil:',
          error
        );

        // No borrar una sesión más reciente.
        if (this.auth.currentUser?.uid === uid) {
          this.limpiarUsuarioEnMemoria();
        }
      } finally {
        // Una respuesta antigua no debe modificar la carga
        // correspondiente a una sesión diferente.
        if (this.auth.currentUser?.uid === uid) {
          this.cargandoSesionSubject.next(false);
        }
      }
    });
  }

  // =========================================================
  // LOGIN
  // =========================================================

  async login(
    email: string,
    password: string
  ): Promise<void> {

    const correo = email.trim().toLowerCase();

    console.log('[AuthService] Iniciando sesión...');

    this.cargandoSesionSubject.next(true);

    try {
      const credential = await signInWithEmailAndPassword(
        this.auth,
        correo,
        password
      );

      const uid = credential.user.uid;

      console.log(
        '[AuthService] Firebase autenticó el UID:',
        uid
      );

      // No esperamos a que el observador lo haga por separado.
      // Si ya está cargando este mismo UID, reutilizamos esa petición.
      await this.cargarPerfilPropio(uid);

      if (this.auth.currentUser?.uid !== uid) {
        throw new Error(
          'La sesión cambió durante el inicio de sesión. Inténtalo nuevamente.'
        );
      }

      console.log('[AuthService] Perfil de OLITRACK listo.');

    } catch (error: any) {
      console.error(
        '[AuthService] Error de inicio de sesión:',
        error?.code ?? error?.message ?? error
      );

      throw error;
    } finally {
      this.cargandoSesionSubject.next(false);
    }
  }

  // =========================================================
  // REGISTRO DE CLIENTE
  // Solo crea la cuenta en el backend.
  // No inicia sesión automáticamente.
  // =========================================================

  async registrarCliente(
    request: RegistroRequest
  ): Promise<Usuario> {

    console.log('[AuthService] Registrando nuevo cliente...');

    const url = `${environment.apiUrl}/auth/registro`;

    try {
      const usuario = await firstValueFrom(
        this.http.post<Usuario>(url, request).pipe(
          timeout({ first: 30000 })
        )
      );

      console.log(
        '[AuthService] Cuenta registrada correctamente.'
      );

      return usuario;

    } catch (error: any) {
      console.error(
        '[AuthService] Error registrando cliente:',
        error?.status,
        error?.message
      );

      throw error;
    }
  }

  // =========================================================
  // CERRAR SESIÓN
  // =========================================================

  async logout(): Promise<void> {
    try {
      // Limpiar el usuario visible inmediatamente.
      this.limpiarUsuarioEnMemoria();

      await signOut(this.auth);

      await this.router.navigateByUrl('/');

    } catch (error) {
      console.error(
        '[AuthService] Error cerrando sesión:',
        error
      );

      throw error;
    } finally {
      this.cargandoSesionSubject.next(false);
    }
  }

  // =========================================================
  // LIMPIAR SESIÓN ANTES DEL REGISTRO, SI SE NECESITA
  // =========================================================

  async limpiarSesion(): Promise<void> {
    try {
      if (this.auth.currentUser) {
        await signOut(this.auth);
      }

      this.limpiarUsuarioEnMemoria();

      console.log('[AuthService] Estado local limpiado.');

    } catch (error) {
      this.limpiarUsuarioEnMemoria();

      console.error(
        '[AuthService] Error limpiando sesión:',
        error
      );

      throw error;
    }
  }

  // =========================================================
  // LIMPIAR USUARIO EN MEMORIA
  // =========================================================

  private limpiarUsuarioEnMemoria(): void {
    this.usuarioActualSubject.next(null);
    this.uidPerfilCargado = null;
  }

  // =========================================================
  // CARGAR PERFIL DEL USUARIO AUTENTICADO
  // =========================================================

  private cargarPerfilPropio(
    uidEsperado: string
  ): Promise<void> {

    const firebaseUser: FirebaseUser | null =
      this.auth.currentUser;

    // Nunca cargar un perfil si el UID ya cambió.
    if (
      !firebaseUser ||
      firebaseUser.uid !== uidEsperado
    ) {
      return Promise.reject(
        new Error(
          'El usuario autenticado cambió antes de cargar su perfil.'
        )
      );
    }

    if (
      this.uidPerfilCargado !== null &&
      this.uidPerfilCargado !== uidEsperado
    ) {
      this.limpiarUsuarioEnMemoria();
    }

    // El perfil de este UID ya está disponible.
    if (
      this.uidPerfilCargado === uidEsperado &&
      this.usuarioActualSubject.value !== null
    ) {
      return Promise.resolve();
    }

    // Ya hay una petición en curso para este mismo UID.
    if (this.perfilEnCarga?.uid === uidEsperado) {
      return this.perfilEnCarga.promise;
    }

    const peticion = this.solicitarPerfil(uidEsperado);

    this.perfilEnCarga = {
      uid: uidEsperado,
      promise: peticion
    };

    // Liberar únicamente la petición que acaba de terminar.
    void peticion.finally(() => {
      if (this.perfilEnCarga?.promise === peticion) {
        this.perfilEnCarga = null;
      }
    }).catch(() => {
      // La petición original gestiona el error.
    });

    return peticion;
  }

  private async solicitarPerfil(
    uidEsperado: string
  ): Promise<void> {

    try {
      console.log('[AuthService] Obteniendo perfil propio...');

      const usuario = await firstValueFrom(
        this.http.get<Usuario>(
          `${environment.apiUrl}/usuarios/me`
        ).pipe(
          timeout({ first: 8000 })
        )
      );

      // Verificar otra vez después de recibir la respuesta.
      // La sesión pudo cambiar mientras se consultaba el backend.
      if (this.auth.currentUser?.uid !== uidEsperado) {
        throw new Error(
          'Se descartó una respuesta porque la sesión cambió.'
        );
      }

      if (!usuario) {
        throw new Error(
          'El backend no devolvió un perfil de usuario.'
        );
      }

      // Guardar perfil y UID como una sola operación lógica.
      this.usuarioActualSubject.next(usuario);
      this.uidPerfilCargado = uidEsperado;
      this.sincronizarRutaConRol(usuario);

      console.log(
        '[AuthService] Perfil de usuario cargado.',
        {
          uid: uidEsperado,
          rol: usuario.rol
        }
      );

    } catch (error) {
      // No borrar los datos de una sesión más reciente.
      if (this.auth.currentUser?.uid === uidEsperado) {
        this.limpiarUsuarioEnMemoria();
      }

      throw error;
    }
  }

  private sincronizarRutaConRol(usuario: Usuario): void {
    const rutaActual = this.router.url.split(/[?#]/, 1)[0];
    const rutasPorRol: Record<Usuario['rol'], string> = {
      ADMINISTRADOR: '/admin',
      SUPERVISOR: '/supervisor',
      REPARTIDOR: '/repartidor',
      CLIENTE: '/cliente'
    };

    const rutaProtegida = [
      '/admin',
      '/supervisor',
      '/repartidor',
      '/cliente'
    ].some(ruta => rutaActual === ruta || rutaActual.startsWith(`${ruta}/`));

    if (!rutaProtegida || rutaActual === rutasPorRol[usuario.rol]) {
      return;
    }

    void this.router.navigateByUrl(rutasPorRol[usuario.rol]).catch(error => {
      console.error(
        '[AuthService] No se pudo sincronizar la ruta con el rol:',
        error
      );
    });
  }

  // =========================================================
  // FIREBASE ID TOKEN
  // =========================================================

  async obtenerIdToken(): Promise<string | null> {
    const firebaseUser = this.auth.currentUser;

    if (!firebaseUser) {
      return null;
    }

    try {
      return await firebaseUser.getIdToken();

    } catch (error) {
      console.error(
        '[AuthService] Error obteniendo el token:',
        error
      );

      return null;
    }
  }

  // =========================================================
  // USUARIO ACTUAL
  // =========================================================

  get usuarioActual(): Usuario | null {
    if (
      this.uidPerfilCargado !== this.auth.currentUser?.uid
    ) {
      return null;
    }

    return this.usuarioActualSubject.value;
  }

  // =========================================================
  // ESTADO DE AUTENTICACIÓN
  // =========================================================

  estaAutenticado(): boolean {
    return (
      this.auth.currentUser !== null &&
      this.usuarioActualSubject.value !== null &&
      this.uidPerfilCargado === this.auth.currentUser.uid
    );
  }

  // =========================================================
  // COMPROBAR ROL
  // =========================================================

  tieneRol(...roles: string[]): boolean {
    const usuario = this.usuarioActual;

    if (!usuario || !this.estaAutenticado()) {
      return false;
    }

    return roles.includes(usuario.rol);
  }
}