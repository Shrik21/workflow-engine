import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { MarketplaceApiService } from './core/api/marketplace-api.service';
import { NodeApiService } from './core/api/node-api.service';
import { TaskApiService } from './core/api/task-api.service';
import { AuthStateService } from './core/auth/auth-state.service';
import { AuthService } from './core/auth/auth.service';
import { Permission } from './core/auth/auth.models';
import { Icon } from './shared/ui/icon';
import { ToastHost } from './shared/ui/toast-host';

interface NavItem {
  path: string;
  label: string;
  exact: boolean;
  /** Hidden unless the user holds one of these. Empty means visible to anyone signed in. */
  permissions: Permission[];
  icon:
    | 'workflows'
    | 'executions'
    | 'tasks'
    | 'forms'
    | 'nodes'
    | 'plugins'
    | 'secrets'
    | 'settings'
    | 'events'
    | 'users'
    | 'groups';
  /** Visual group label in the sidebar. Presentation only — does not change guards. */
  group: 'Build' | 'Run' | 'Extend' | 'Settings' | 'Admin';
}

interface NavGroup {
  label: string;
  items: NavItem[];
}

/**
 * The application shell.
 *
 * Renders bare on the sign-in and registration pages: a navigation sidebar for an unauthenticated visitor
 * would be a list of places they cannot go.
 *
 * Navigation is filtered by permission, so a USER simply does not see Plugins, Secrets or Users. That is a
 * courtesy and not a control: the server refuses those endpoints regardless, and the guards refuse the
 * routes. Hiding them keeps the interface honest about what this person can actually do.
 */
@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ToastHost, Icon],
  template: `
    @if (chromeless()) {
      <router-outlet />
    } @else {
      <div class="shell" [class.shell--nav-open]="mobileNavOpen()">
        <a class="skip-link" href="#main-content">Skip to content</a>

        <button
          class="nav-toggle"
          type="button"
          [attr.aria-expanded]="mobileNavOpen()"
          aria-controls="app-sidebar"
          (click)="mobileNavOpen.set(!mobileNavOpen())"
        >
          <wf-icon [name]="mobileNavOpen() ? 'close' : 'menu'" [size]="18" />
          <span class="sr-only">{{ mobileNavOpen() ? 'Close navigation' : 'Open navigation' }}</span>
        </button>

        @if (mobileNavOpen()) {
          <button
            class="nav-backdrop"
            type="button"
            aria-label="Close navigation"
            (click)="mobileNavOpen.set(false)"
          ></button>
        }

        <aside class="sidebar" id="app-sidebar" aria-label="Primary">
          <div class="brand">
            <span class="brand__mark" aria-hidden="true">
              <svg width="30" height="30" viewBox="0 0 512 512">
                <circle cx="256" cy="256" r="248" fill="#080D17" />
                <circle cx="256" cy="256" r="148" fill="none" stroke="#3EC9D8" stroke-width="46" />
                <circle cx="256" cy="256" r="62" fill="#3EC9D8" />
                <path d="M 400 118 L 330 224 L 316 174 Z" fill="#F0A24B" />
              </svg>
            </span>
            <span class="brand__text">
              <strong>OrchPilot</strong>
              <span>Workflow Engine</span>
            </span>
          </div>

          <nav class="nav" aria-label="Application">
            @for (group of visibleGroups(); track group.label) {
              <div class="nav__group">
                <p class="nav__group-label">{{ group.label }}</p>
                @for (item of group.items; track item.path) {
                  <a
                    class="nav__link"
                    [routerLink]="item.path"
                    routerLinkActive="nav__link--active"
                    [routerLinkActiveOptions]="{ exact: item.exact }"
                    [attr.title]="item.label"
                    (click)="mobileNavOpen.set(false)"
                  >
                    <wf-icon class="nav__icon" [name]="item.icon" [size]="16" />
                    <span class="nav__label">{{ item.label }}</span>
                    @if (item.path === '/tasks' && waitingCount() > 0) {
                      <span class="nav__badge" [attr.aria-label]="waitingCount() + ' tasks waiting for you'">
                        {{ waitingCount() }}
                      </span>
                    }
                    @if (item.path === '/nodes' && pluginNodeCount() > 0) {
                      <span class="nav__badge nav__badge--quiet">{{ pluginNodeCount() }}</span>
                    }
                    @if (item.path === '/plugins' && updatableCount() > 0) {
                      <span
                        class="nav__badge nav__badge--quiet"
                        [attr.aria-label]="updatableCount() + ' plugins have a newer version'"
                        >{{ updatableCount() }}</span
                      >
                    }
                  </a>
                }
              </div>
            }
          </nav>

          <div class="sidebar__footer">
            <a class="identity" routerLink="/profile" (click)="mobileNavOpen.set(false)">
              <span class="identity__avatar" aria-hidden="true">{{ initials() }}</span>
              <span class="identity__text">
                <span class="identity__name">{{ state.displayName() }}</span>
                <span class="identity__role">{{ roleLabel() }}</span>
              </span>
            </a>
            <button class="signout" type="button" (click)="signOut()">Sign out</button>
          </div>
        </aside>

        <main class="content" id="main-content" tabindex="-1">
          <router-outlet />
        </main>
      </div>
    }

    <wf-toast-host />
  `,
  styles: [
    `
      .skip-link {
        position: absolute;
        left: var(--space-3);
        top: -40px;
        z-index: calc(var(--z-toast) + 1);
        background: var(--hl-white);
        color: var(--hl-blue);
        padding: var(--space-2) var(--space-3);
        border-radius: var(--radius-sm);
        font-weight: 600;
      }

      .skip-link:focus {
        top: var(--space-3);
      }

      .shell {
        display: grid;
        grid-template-columns: var(--sidebar-width) 1fr;
        height: 100vh;
      }

      .sidebar {
        display: flex;
        flex-direction: column;
        background: var(--hl-blue);
        color: var(--text-inverse);
        min-height: 0;
        z-index: var(--z-sidebar);
      }

      .brand {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        padding: var(--space-4) var(--space-4) var(--space-4);
      }

      .brand__mark {
        display: inline-flex;
      }

      .brand__text {
        display: flex;
        flex-direction: column;
        font-family: var(--font-brand);
        line-height: 1.2;
      }

      .brand__text strong {
        font-size: var(--text-lg);
        letter-spacing: 0.2px;
      }

      .brand__text span {
        font-size: var(--text-xs);
        color: rgba(255, 255, 255, 0.7);
      }

      .nav {
        display: flex;
        flex-direction: column;
        flex: 1;
        min-height: 0;
        overflow-y: auto;
        padding: 0 var(--space-2) var(--space-3);
        gap: var(--space-3);
      }

      .nav__group-label {
        margin: 0 0 var(--space-1);
        padding: 0 var(--space-3);
        font-size: 10px;
        font-weight: 700;
        letter-spacing: 0.08em;
        text-transform: uppercase;
        color: rgba(255, 255, 255, 0.45);
      }

      .nav__link {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        padding: 8px var(--space-3);
        border-radius: var(--radius-sm);
        color: rgba(255, 255, 255, 0.82);
        font-family: var(--font-brand);
        font-size: var(--text-md);
        text-decoration: none;
        margin-bottom: 2px;
        transition: background var(--motion-fast) var(--ease-standard);
      }

      .nav__link:hover {
        background: rgba(255, 255, 255, 0.09);
        color: var(--text-inverse);
        text-decoration: none;
      }

      .nav__link:focus-visible {
        outline: none;
        box-shadow: var(--focus-ring);
      }

      .nav__link--active {
        background: rgba(255, 255, 255, 0.14);
        color: var(--text-inverse);
        font-weight: 600;
        box-shadow: inset 3px 0 0 var(--hl-green);
      }

      .nav__icon {
        flex: none;
        opacity: 0.9;
      }

      .nav__label {
        flex: 1;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .nav__badge {
        background: var(--hl-green);
        color: var(--hl-blue);
        font-size: 10px;
        font-weight: bold;
        border-radius: 9px;
        padding: 1px 6px;
      }

      .nav__badge--quiet {
        background: rgba(255, 255, 255, 0.18);
        color: var(--text-inverse);
      }

      .sidebar__footer {
        padding: var(--space-3);
        border-top: 1px solid rgba(255, 255, 255, 0.12);
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
      }

      .identity {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        padding: var(--space-2);
        border-radius: var(--radius-sm);
        background: rgba(255, 255, 255, 0.07);
        color: var(--text-inverse);
        text-decoration: none;
      }

      .identity:hover {
        background: rgba(255, 255, 255, 0.13);
        text-decoration: none;
      }

      .identity:focus-visible {
        outline: none;
        box-shadow: var(--focus-ring);
      }

      .identity__avatar {
        flex: none;
        width: 28px;
        height: 28px;
        border-radius: 50%;
        background: var(--hl-green);
        color: var(--hl-blue);
        display: inline-flex;
        align-items: center;
        justify-content: center;
        font-size: var(--text-sm);
        font-weight: bold;
      }

      .identity__text {
        min-width: 0;
        display: flex;
        flex-direction: column;
      }

      .identity__name {
        font-size: var(--text-base);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .identity__role {
        font-size: 10px;
        color: rgba(255, 255, 255, 0.65);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .signout {
        border: 1px solid rgba(255, 255, 255, 0.2);
        background: transparent;
        color: rgba(255, 255, 255, 0.85);
        border-radius: var(--radius-sm);
        padding: 5px;
        cursor: pointer;
        font-family: var(--font-body);
        font-size: var(--text-sm);
      }

      .signout:hover {
        background: rgba(255, 255, 255, 0.12);
        color: var(--text-inverse);
      }

      .signout:focus-visible {
        outline: none;
        box-shadow: var(--focus-ring);
      }

      .content {
        min-width: 0;
        min-height: 0;
        overflow: auto;
        background: var(--surface-sunken);
      }

      .content:focus {
        outline: none;
      }

      .nav-toggle,
      .nav-backdrop {
        display: none;
      }

      @media (max-width: 900px) {
        .shell {
          grid-template-columns: 1fr;
        }

        .nav-toggle {
          display: inline-flex;
          align-items: center;
          justify-content: center;
          position: fixed;
          top: var(--space-3);
          left: var(--space-3);
          z-index: calc(var(--z-sidebar) + 2);
          width: 40px;
          height: 40px;
          border: 1px solid var(--border);
          border-radius: var(--radius);
          background: var(--surface);
          color: var(--hl-blue);
          box-shadow: var(--shadow-sm);
          cursor: pointer;
        }

        .nav-toggle:focus-visible {
          outline: none;
          box-shadow: var(--focus-ring);
        }

        .nav-backdrop {
          display: block;
          position: fixed;
          inset: 0;
          z-index: calc(var(--z-sidebar) - 1);
          border: 0;
          background: rgba(0, 45, 91, 0.4);
          cursor: pointer;
        }

        .sidebar {
          position: fixed;
          inset: 0 auto 0 0;
          width: min(280px, 86vw);
          transform: translateX(-105%);
          transition: transform var(--motion-base) var(--ease-standard);
          box-shadow: var(--shadow-lg);
        }

        .shell--nav-open .sidebar {
          transform: translateX(0);
        }

        .content {
          padding-top: 56px;
        }
      }
    `,
  ],
})
export class App {
  private static readonly NAVIGATION: NavItem[] = [
    { path: '/workflows', label: 'Workflows', exact: false, permissions: ['WORKFLOW_VIEW'], icon: 'workflows', group: 'Build' },
    { path: '/forms', label: 'Forms', exact: false, permissions: ['WORKFLOW_VIEW'], icon: 'forms', group: 'Build' },
    { path: '/nodes', label: 'Node types', exact: true, permissions: [], icon: 'nodes', group: 'Build' },
    { path: '/executions', label: 'Executions', exact: false, permissions: ['EXECUTION_VIEW'], icon: 'executions', group: 'Run' },
    { path: '/tasks', label: 'Tasks', exact: true, permissions: ['TASK_VIEW', 'TASK_VIEW_ALL'], icon: 'tasks', group: 'Run' },
    { path: '/events', label: 'Events', exact: true, permissions: ['EVENT_EMIT'], icon: 'events', group: 'Run' },
    { path: '/plugins', label: 'Plugins', exact: true, permissions: ['PLUGIN_VIEW'], icon: 'plugins', group: 'Extend' },
    { path: '/secrets', label: 'Secrets', exact: true, permissions: ['SECRET_VIEW'], icon: 'secrets', group: 'Extend' },
    {
      path: '/settings/ai-providers',
      label: 'AI Providers',
      exact: true,
      permissions: ['AI_PROVIDER_VIEW'],
      icon: 'settings',
      group: 'Settings',
    },
    {
      path: '/settings/ai-usage',
      label: 'AI Usage',
      exact: true,
      permissions: ['AI_PROVIDER_VIEW'],
      icon: 'settings',
      group: 'Settings',
    },
    // Not `exact`, so the entry stays highlighted on /settings/ai/claude-cli and its future siblings.
    {
      path: '/settings/ai',
      label: 'AI Configuration',
      exact: false,
      permissions: ['AI_CLI_VIEW'],
      icon: 'settings',
      group: 'Settings',
    },
    {
      path: '/settings/storage',
      label: 'File Storage',
      exact: true,
      permissions: ['WORKFLOW_STORAGE_SETTINGS_VIEW'],
      icon: 'settings',
      group: 'Settings',
    },
    { path: '/admin/users', label: 'Users', exact: true, permissions: ['USER_VIEW'], icon: 'users', group: 'Admin' },
    // Visibility unchanged: still USER_VIEW. Route still requires ADMIN — flagged for separate review.
    { path: '/admin/groups', label: 'Groups', exact: true, permissions: ['USER_VIEW'], icon: 'groups', group: 'Admin' },
  ];

  private static readonly GROUP_ORDER: NavItem['group'][] = [
    'Build',
    'Run',
    'Extend',
    'Settings',
    'Admin',
  ];

  protected readonly state = inject(AuthStateService);

  private readonly auth = inject(AuthService);
  private readonly tasks = inject(TaskApiService);
  private readonly catalog = inject(NodeApiService);
  private readonly marketplace = inject(MarketplaceApiService);
  private readonly router = inject(Router);

  protected readonly waitingCount = signal(0);
  protected readonly mobileNavOpen = signal(false);
  private readonly currentUrl = signal(this.router.url);

  /** Sign-in, registration and the public form render without the shell. */
  protected readonly chromeless = computed(() => {
    const url = this.currentUrl();
    // The public form is chromeless even for a signed-in user testing a link: it must look the same to the
    // external customer, with no internal navigation.
    return (
      url.startsWith('/login') ||
      url.startsWith('/register') ||
      url.startsWith('/public/') ||
      !this.state.isAuthenticated()
    );
  });

  protected readonly visibleGroups = computed((): NavGroup[] => {
    const visible = App.NAVIGATION.filter(
      (item) => item.permissions.length === 0 || this.state.hasAny(...item.permissions),
    );
    return App.GROUP_ORDER.map((label) => ({
      label,
      items: visible.filter((item) => item.group === label),
    })).filter((group) => group.items.length > 0);
  });

  protected readonly pluginNodeCount = computed(() => this.catalog.pluginEntries().length);

  /** Plugins with a newer version published. Quiet, because an update is information rather than a task. */
  protected readonly updatableCount = computed(() => this.marketplace.updatable().length);

  protected readonly initials = computed(() => {
    const name = this.state.displayName();
    if (!name) {
      return '?';
    }
    const parts = name.split(/\s+/).filter((part) => part.length > 0);
    if (parts.length === 1) {
      return parts[0].slice(0, 2).toUpperCase();
    }
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  });

  protected readonly roleLabel = computed(() => this.state.roles().join(', ') || 'no roles');

  constructor() {
    this.router.events
      .pipe(filter((event): event is NavigationEnd => event instanceof NavigationEnd))
      .subscribe((event) => {
        this.currentUrl.set(event.urlAfterRedirects);
        this.mobileNavOpen.set(false);
        // Loaded lazily rather than at construction: an unauthenticated visitor on the sign-in page has no
        // token, and requesting the catalogue would produce a 401 before they have even signed in.
        if (this.state.isAuthenticated()) {
          this.catalog.ensureLoaded();
          // Gated on the permission the server enforces: without it every request here is a 403, and a
          // shell that 403s on every navigation is worse than a missing badge.
          if (this.state.has('PLUGIN_VIEW')) {
            this.marketplace.ensureLoaded();
          }
        }
      });

    this.refreshWaitingCount();
    // A slow poll: the badge needs to be roughly right, not instantly right.
    setInterval(() => this.refreshWaitingCount(), 30_000);
  }

  protected signOut(): void {
    this.auth.logout({ message: 'Signed out.' });
  }

  /**
   * The badge beside Tasks: how many are waiting for this person.
   *
   * Counted from the task API rather than from waiting executions. A waiting execution is not the same thing as
   * work for the signed-in user — it may be somebody else's approval, or one addressed to nobody — and a badge
   * that overstates what is yours is worse than no badge.
   */
  private refreshWaitingCount(): void {
    if (!this.state.isAuthenticated() || !this.state.hasAny('TASK_VIEW', 'TASK_VIEW_ALL')) {
      this.waitingCount.set(0);
      return;
    }
    this.tasks.counts().subscribe({
      next: (counts) => this.waitingCount.set(counts.mine ?? 0),
      // Silent: the shell must not raise a toast every 30 seconds when the engine is down.
      error: () => undefined,
    });
  }
}
