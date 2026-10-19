
import { Routes } from '@angular/router';
import { authGuard, roleGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./public/home/home.component').then(
        (m) => m.HomeComponent
      )
  },
  {
    path: 'login',
    loadComponent: () =>
      import('./auth/login/login.component').then(
        (m) => m.LoginComponent
      )
  },
  {
    path: 'registro',
    loadComponent: () =>
      import('./auth/registro/registro.component').then(
        (m) => m.RegistroComponent
      )
  },
  {
    path: 'catalogo',
    loadComponent: () =>
      import('./public/catalogo/catalogo.component').then(
        (m) => m.CatalogoComponent
      )
  },
  {
    path: 'consulta',
    loadComponent: () =>
      import('./public/consulta/consulta.component').then(
        (m) => m.ConsultaComponent
      )
  },
  {
    path: 'admin',
    canActivate: [authGuard, roleGuard('ADMINISTRADOR')],
    loadComponent: () =>
      import('./features/admin/admin-dashboard.component').then(
        (m) => m.AdminDashboardComponent
      )
  },
  {
    path: 'cliente',
    canActivate: [authGuard, roleGuard('CLIENTE')],
    loadComponent: () =>
      import('./features/client/client-portal.component').then(
        (m) => m.ClientPortalComponent
      )
  },
  {
    path: 'supervisor',
    canActivate: [authGuard, roleGuard('SUPERVISOR', 'ADMINISTRADOR')],
    loadComponent: () =>
      import('./features/supervisor/supervisor-operations.component').then(
        (m) => m.SupervisorOperationsComponent
      )
  },
  {
    path: 'repartidor',
    canActivate: [authGuard, roleGuard('REPARTIDOR')],
    loadComponent: () =>
      import('./features/driver/driver-workspace.component').then(
        (m) => m.DriverWorkspaceComponent
      )
  },
  {
    path: '**',
    redirectTo: ''
  }
];
