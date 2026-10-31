# Frontend

This project was generated using [Angular CLI](https://github.com/angular/angular-cli) version 21.2.24.

## Estructura de la aplicación

- `src/app/auth/`: inicio de sesión y registro.
- `src/app/core/auth/`: guardas e interceptores de autenticación.
- `src/app/core/models/`: contratos organizados por responsabilidad: `usuario.models.ts` (usuarios/roles), `ubicacion.models.ts` (coordenadas), `cliente.models.ts`, `repartidor.models.ts`, `producto.models.ts`, `inventario.models.ts`, `pedido.models.ts`, `incidencia.models.ts`, `comunicacion.models.ts` (consultas/notificaciones), `empresa.models.ts` (configuración/zonas), `administracion.models.ts` (auditoría/promociones/FAQ), `reporte.models.ts` y `interfaz.models.ts` (carrito/toasts del frontend). `olitrack.models.ts` reexporta los modelos para mantener imports compartidos.
- `src/app/core/services/`: acceso HTTP, autenticación y estado compartido.
- `src/app/features/`: áreas autenticadas (administración, cliente, repartidor y supervisión). Los modelos de formulario específicos se quedan dentro de su feature, por ejemplo `features/admin/models/admin.forms.ts`.
- `src/app/public/`: páginas públicas.
- `src/app/shared/components/`: componentes reutilizables; los tipos exclusivos de un componente van junto a este.

La autenticación de Firebase en el navegador usa persistencia por pestaña (`browserSessionPersistence`), de modo que se pueden probar cuentas/roles distintos en ventanas o pestañas independientes. La sesión se elimina al cerrar su pestaña.
- `src/app/app.component.ts`: componente raíz; `src/app/app.routes.ts` define las rutas.

Mantén los modelos de API/dominio en `core/models`, los formularios y el estado exclusivo dentro de su feature, y los tipos internos de componentes junto al componente. Por eso `ProductoForm`, `UsuarioForm` y `UsuarioEdicionForm` están en `features/admin/models/admin.forms.ts`, no en el modelo de dominio compartido. Evita añadir modelos de formulario de una pantalla al barrel global.

## Development server

To start a local development server, run:

```bash
ng serve
```

Once the server is running, open your browser and navigate to `http://localhost:4200/`. The application will automatically reload whenever you modify any of the source files.

## Code scaffolding

Angular CLI includes powerful code scaffolding tools. To generate a new component, run:

```bash
ng generate component component-name
```

For a complete list of available schematics (such as `components`, `directives`, or `pipes`), run:

```bash
ng generate --help
```

## Building

To build the project run:

```bash
ng build
```

This will compile your project and store the build artifacts in the `dist/` directory. By default, the production build optimizes your application for performance and speed.

## Running unit tests

To execute unit tests with the [Vitest](https://vitest.dev/) test runner, use the following command:

```bash
ng test
```

## Running end-to-end tests

For end-to-end (e2e) testing, run:

```bash
ng e2e
```

Angular CLI does not come with an end-to-end testing framework by default. You can choose one that suits your needs.

## Firebase Hosting, Cloud Run y comprobantes

La compilación de producción usa `/api` como base de la API. `firebase.json` reenvía `/api/**` al servicio Cloud Run `oli-track-v-1-0-0-1-git` en `us-central1`; conserva la regla SPA para el resto de rutas. Si cambia el nombre o región del servicio, actualiza esa regla antes de desplegar Hosting.

El backend recibe los comprobantes, valida el tamaño (máximo 2 MB) y el contenido del archivo, los guarda en Firebase Storage y registra solo la ruta y los metadatos en Firestore. La descarga pasa por una ruta autenticada: el cliente propietario, administradores y supervisores pueden revisarla. Subir un comprobante no confirma el pago ni cambia el estado del pedido.

Las imágenes de productos se cargan y reemplazan desde el formulario de administración (JPG, PNG o WEBP, máximo 5 MB), y también se pueden quitar al editar. El backend valida el contenido y guarda el archivo en `imagenes-productos/`; Firestore conserva la ruta de Storage y la URL de lectura de la API. El catálogo las consulta públicamente a través de `/api/productos/{id}/imagen`, mientras que la carga y eliminación requieren el rol `ADMINISTRADOR`. Las reglas de Storage niegan el acceso directo del navegador tanto a `evidencias/` como a `imagenes-productos/`. En desarrollo local, Angular convierte esa ruta relativa a la dirección del backend local; en producción usa el proxy de Firebase Hosting.

En Cloud Run, asigna al servicio una identidad de servicio y otórgale `roles/datastore.user` para las operaciones de Firestore y `roles/storage.objectAdmin` en el bucket `olitrack-f0fcc.firebasestorage.app`. Como el backend también crea, actualiza y elimina usuarios de Firebase Authentication, la identidad necesita permisos de administración de usuarios de Firebase Authentication (por ejemplo, `roles/firebaseauth.admin`, o un rol personalizado con solo los permisos requeridos). La verificación de ID tokens por sí sola no requiere permisos de administración de usuarios. El backend usa Application Default Credentials de la identidad de Cloud Run; no configures ni subas una clave JSON privada. En desarrollo local, define `GOOGLE_APPLICATION_CREDENTIALS` con la ruta a una credencial local que no se suba al repositorio; ADC la utilizará automáticamente. Confirma también que el bucket no tenga acceso público por IAM; las reglas de Firebase Storage no pueden revocar permisos públicos concedidos directamente por IAM.

El valor de `FIREBASE_STORAGE_BUCKET` es `olitrack-f0fcc.firebasestorage.app` sin el prefijo `gs://`. `CORS_ALLOWED_ORIGINS` puede configurarse con orígenes permitidos separados por comas. La integración de Hosting puede requerir que Cloud Run acepte invocaciones públicas; las rutas de la API siguen requiriendo y verificando el token de Firebase.

Primero despliega el backend real en Cloud Run desde PowerShell:

```powershell
cd ..\backend
gcloud run deploy oli-track-v-1-0-0-1-git --source . --project olitrack-f0fcc --region us-central1 --port 8080 --allow-unauthenticated --update-env-vars FIREBASE_STORAGE_BUCKET=olitrack-f0fcc.firebasestorage.app
```

Confirma en Cloud Run que está asignada la identidad de servicio con los permisos indicados arriba. En Firebase Console, elimina del servicio cualquier variable `FIREBASE_CREDENTIALS_PATH`; ADC no requiere esa variable ni una clave privada. El acceso público de invocación permite que Hosting llegue a la API; las rutas protegidas siguen validando el token de Firebase en la aplicación.

Luego recompila y despliega Hosting desde la carpeta `frontend`:

```powershell
cd ..\frontend
npm run build
firebase deploy --only hosting,storage
```

Después del despliegue, comprueba `https://oli-track-v-1-0-0-1-git-345576911273.us-central1.run.app/actuator/health` y confirma que responda JSON con estado `UP`. El rewrite `/api/**` de Hosting no funcionará correctamente mientras Cloud Run siga sirviendo la página placeholder en lugar del backend.

## Additional Resources

For more information on using the Angular CLI, including detailed command references, visit the [Angular CLI Overview and Command Reference](https://angular.dev/tools/cli) page.
