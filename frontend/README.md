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

## Additional Resources

For more information on using the Angular CLI, including detailed command references, visit the [Angular CLI Overview and Command Reference](https://angular.dev/tools/cli) page.
