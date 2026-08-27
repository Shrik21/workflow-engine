import { Routes } from '@angular/router';
import { authGuard, guestGuard, permissionGuard } from './core/auth/auth.guards';

/**
 * Routes.
 *
 * One public route, sign-in, and everything else behind {@link authGuard}. Every feature is lazily loaded:
 * the upload wizard carries the archive reader and is irrelevant to somebody who only came to look at a
 * version's checksum.
 *
 * Guards are a convenience, not a control. The registry authorises every request on its own, and a guard that
 * was somehow bypassed would show a page that immediately fills with 403s.
 */
export const routes: Routes = [
  {
    path: 'login',
    title: 'Sign in',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/login').then((m) => m.Login),
  },
  {
    path: 'plugins',
    title: 'Plugin Registry',
    canActivate: [authGuard, permissionGuard('PLUGIN_READ')],
    loadComponent: () => import('./features/plugin-list/plugin-list').then((m) => m.PluginList),
  },
  {
    // Declared before the :pluginId route, which would otherwise swallow the word "upload".
    path: 'plugins/upload',
    title: 'Upload a plugin',
    canActivate: [authGuard, permissionGuard('PLUGIN_UPLOAD')],
    loadComponent: () =>
      import('./features/plugin-upload/plugin-upload').then((m) => m.PluginUpload),
  },
  {
    path: 'plugins/:pluginId/upload-version',
    title: 'Upload a version',
    canActivate: [authGuard, permissionGuard('PLUGIN_UPLOAD')],
    loadComponent: () =>
      import('./features/plugin-upload/plugin-upload').then((m) => m.PluginUpload),
  },
  {
    path: 'plugins/:pluginId/versions/:version',
    title: 'Version',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/plugin-version-details/plugin-version-details').then(
        (m) => m.PluginVersionDetails,
      ),
  },
  {
    // Both the details page and its Versions tab, so /versions is a deep link into the same component
    // rather than a second page that would duplicate the header, statistics and actions.
    path: 'plugins/:pluginId/versions',
    title: 'Plugin versions',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/plugin-details/plugin-details').then((m) => m.PluginDetails),
    data: { tab: 'versions' },
  },
  {
    path: 'plugins/:pluginId',
    title: 'Plugin',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/plugin-details/plugin-details').then((m) => m.PluginDetails),
  },

  {
    path: 'users',
    title: 'Users',
    canActivate: [authGuard, permissionGuard('USER_READ')],
    loadComponent: () => import('./features/admin/user-admin').then((m) => m.UserAdmin),
  },
  {
    path: 'roles',
    title: 'Roles',
    canActivate: [authGuard, permissionGuard('ROLE_READ')],
    loadComponent: () => import('./features/admin/role-admin').then((m) => m.RoleAdmin),
  },
  {
    path: 'security/audit',
    title: 'Security audit',
    canActivate: [authGuard, permissionGuard('PLUGIN_AUDIT_READ')],
    loadComponent: () => import('./features/admin/security-audit').then((m) => m.SecurityAudit),
  },
  {
    // No permission guard: everybody may see their own account, and the forced password change lands here.
    path: 'profile',
    title: 'Your account',
    canActivate: [authGuard],
    loadComponent: () => import('./features/profile/profile').then((m) => m.Profile),
  },

  { path: '', pathMatch: 'full', redirectTo: 'plugins' },
  { path: '**', redirectTo: 'plugins' },
];
