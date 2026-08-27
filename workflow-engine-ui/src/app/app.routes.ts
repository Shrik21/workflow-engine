import { Routes } from '@angular/router';
import { authGuard, guestGuard, permissionGuard, roleGuard } from './core/auth/auth.guards';

/**
 * Routes.
 *
 * Two public routes, sign-in and registration, and everything else behind {@link authGuard}. Routes are
 * guarded by **permission** rather than by role wherever possible, so a new role that happens to grant the
 * permission works without revisiting this file.
 *
 * Guards are a convenience, not a control: the server authorises every request independently. A guard that
 * was somehow bypassed would show a page that immediately fills with 403s.
 *
 * Every feature is lazily loaded, which matters most for the designer: the canvas and property panel are the
 * largest part of the bundle and are irrelevant to someone who only opened the task inbox.
 */
export const routes: Routes = [
  {
    path: 'login',
    title: 'Sign in',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/login').then((m) => m.Login),
  },
  {
    path: 'register',
    title: 'Create an account',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/register').then((m) => m.Register),
  },

  {
    // The public, account-free external form. No guard: an external customer is not signed in and must not be
    // redirected to a login page. Authorised entirely by the secure token in the path, server-side.
    path: 'public/form/:token',
    title: 'Form',
    loadComponent: () => import('./features/public/public-form').then((m) => m.PublicForm),
  },

  {
    path: '',
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'workflows' },
      {
        path: 'workflows',
        title: 'Workflows',
        canActivate: [permissionGuard('WORKFLOW_VIEW')],
        loadComponent: () => import('./features/workflows/workflow-list').then((m) => m.WorkflowList),
      },
      {
        path: 'workflows/:id',
        title: 'Workflow designer',
        canActivate: [permissionGuard('WORKFLOW_VIEW')],
        loadComponent: () =>
          import('./features/designer/workflow-designer').then((m) => m.WorkflowDesigner),
      },
      {
        path: 'executions',
        title: 'Executions',
        canActivate: [permissionGuard('EXECUTION_VIEW')],
        loadComponent: () =>
          import('./features/executions/execution-list').then((m) => m.ExecutionList),
      },
      {
        path: 'executions/:executionId',
        title: 'Execution',
        canActivate: [permissionGuard('EXECUTION_VIEW')],
        loadComponent: () =>
          import('./features/executions/execution-detail').then((m) => m.ExecutionDetail),
      },
      {
        path: 'tasks',
        title: 'Tasks',
        // TASK_VIEW is the gate on the feature. Which tasks this person actually sees is decided per task by
        // the server from their assignment and candidate groups, which no route guard could know.
        canActivate: [permissionGuard('TASK_VIEW', 'TASK_VIEW_ALL')],
        loadComponent: () => import('./features/tasks/task-inbox').then((m) => m.TaskInbox),
      },
      {
        // The old execution-oriented inbox. Kept as a redirect rather than deleted, because it is the URL
        // anybody who used this console before has bookmarked.
        path: 'inbox',
        redirectTo: 'tasks',
      },
      {
        path: 'forms',
        title: 'Forms',
        canActivate: [permissionGuard('WORKFLOW_VIEW')],
        loadComponent: () => import('./features/forms/form-list').then((m) => m.FormList),
      },
      {
        // 'new' is handled by the same component, which starts a blank draft rather than loading one.
        path: 'forms/:id',
        title: 'Form designer',
        canActivate: [permissionGuard('WORKFLOW_VIEW')],
        loadComponent: () => import('./features/forms/form-designer').then((m) => m.FormDesigner),
      },
      {
        path: 'nodes',
        title: 'Node types',
        loadComponent: () => import('./features/nodes/node-catalog').then((m) => m.NodeCatalog),
      },
      {
        // The marketplace is the front door: installing from the registry is now the ordinary way to get a
        // plugin, and uploading a JAR by hand is the exception.
        path: 'plugins',
        title: 'Plugins',
        canActivate: [permissionGuard('PLUGIN_VIEW')],
        loadComponent: () => import('./features/plugins/marketplace').then((m) => m.Marketplace),
      },
      {
        // Local administration of what is installed: activate, reload, default version, per-version delete
        // and invocation history. Declared before the :pluginId route, which would otherwise swallow it.
        path: 'plugins/installed',
        title: 'Installed plugins',
        canActivate: [permissionGuard('PLUGIN_VIEW')],
        loadComponent: () => import('./features/plugins/plugin-list').then((m) => m.PluginList),
      },
      {
        path: 'plugins/:pluginId',
        title: 'Plugin',
        canActivate: [permissionGuard('PLUGIN_VIEW')],
        loadComponent: () => import('./features/plugins/plugin-detail').then((m) => m.PluginDetail),
      },
      {
        path: 'secrets',
        title: 'Secrets',
        canActivate: [permissionGuard('SECRET_VIEW')],
        loadComponent: () => import('./features/secrets/secret-list').then((m) => m.SecretList),
      },
      {
        path: 'settings/ai-providers',
        title: 'AI Providers',
        canActivate: [permissionGuard('AI_PROVIDER_VIEW')],
        loadComponent: () =>
          import('./features/settings/ai-provider-list').then((m) => m.AiProviderList),
      },
      {
        path: 'settings/ai-usage',
        title: 'AI Usage',
        canActivate: [permissionGuard('AI_PROVIDER_VIEW')],
        loadComponent: () => import('./features/settings/ai-usage').then((m) => m.AiUsage),
      },
      {
        path: 'settings/ai',
        title: 'AI Configuration',
        canActivate: [permissionGuard('AI_CLI_VIEW')],
        loadComponent: () =>
          import('./features/settings/ai-configuration').then((m) => m.AiConfigurationPage),
      },
      {
        path: 'settings/ai/claude-cli',
        title: 'Claude CLI',
        canActivate: [permissionGuard('AI_CLI_VIEW')],
        loadComponent: () =>
          import('./features/settings/claude-cli-settings').then((m) => m.ClaudeCliSettingsPage),
      },
      {
        path: 'settings/storage',
        title: 'File Storage',
        canActivate: [permissionGuard('WORKFLOW_STORAGE_SETTINGS_VIEW')],
        loadComponent: () =>
          import('./features/settings/storage-settings').then((m) => m.StorageSettingsPage),
      },
      {
        path: 'events',
        title: 'Emit an event',
        canActivate: [permissionGuard('EVENT_EMIT')],
        loadComponent: () => import('./features/events/event-emitter').then((m) => m.EventEmitter),
      },
      {
        path: 'profile',
        title: 'Your account',
        loadComponent: () => import('./features/auth/profile').then((m) => m.Profile),
      },
      {
        path: 'admin/groups',
        title: 'Groups',
        // Group membership decides who can reach which workflow, so managing groups is ADMIN-only.
        canActivate: [roleGuard('ADMIN')],
        loadComponent: () => import('./features/admin/group-admin').then((m) => m.GroupAdmin),
      },
      {
        path: 'admin/users',
        title: 'Users',
        // Role as well as permission here: user administration is the one area where the coarse check is
        // genuinely what is meant, and it matches the server's rule for /api/admin/**.
        canActivate: [roleGuard('ADMIN'), permissionGuard('USER_VIEW')],
        loadComponent: () => import('./features/admin/user-admin').then((m) => m.UserAdmin),
      },
    ],
  },

  { path: '**', redirectTo: '' },
];
