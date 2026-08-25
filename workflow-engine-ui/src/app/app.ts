import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { MarketplaceApiService } from './core/api/marketplace-api.service';
import { NodeApiService } from './core/api/node-api.service';
import { TaskApiService } from './core/api/task-api.service';
import { AuthStateService } from './core/auth/auth-state.service';
import { AuthService } from './core/auth/auth.service';
import { Permission } from './core/auth/auth.models';
import { ToastHost } from './shared/ui/toast-host';

interface NavItem {
  path: string;
  label: string;
  exact: boolean;
  /** Hidden unless the user holds one of these. Empty means visible to anyone signed in. */
  permissions: Permission[];
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
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ToastHost],
  template: `
    @if (chromeless()) {
      <router-outlet />
    } @else {
      <div class="shell">
        <aside class="sidebar">
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

          <nav class="nav">
            @for (item of visibleNavigation(); track item.path) {
              <a
                class="nav__link"
                [routerLink]="item.path"
                routerLinkActive="nav__link--active"
                [routerLinkActiveOptions]="{ exact: item.exact }"
              >
                <span>{{ item.label }}</span>
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
          </nav>

          <div class="sidebar__footer">
            <a class="identity" routerLink="/profile">
              <span class="identity__avatar" aria-hidden="true">{{ initials() }}</span>
              <span class="identity__text">
                <span class="identity__name">{{ state.displayName() }}</span>
                <span class="identity__role">{{ roleLabel() }}</span>
              </span>
            </a>
            <button class="signout" type="button" (click)="signOut()">Sign out</button>
          </div>
        </aside>

        <main class="content">
          <router-outlet />
        </main>
      </div>
    }

    <wf-toast-host />
  `,
  styles: [
    `
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
      }

      .brand {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        padding: var(--space-4) var(--space-4) var(--space-5);
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
        padding: 0 var(--space-2);
      }

      .nav__link {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        padding: 9px var(--space-3);
        border-radius: var(--radius-sm);
        color: rgba(255, 255, 255, 0.82);
        font-family: var(--font-brand);
        font-size: var(--text-md);
        text-decoration: none;
        margin-bottom: 2px;
      }

      .nav__link:hover {
        background: rgba(255, 255, 255, 0.09);
        color: var(--text-inverse);
        text-decoration: none;
      }

      .nav__link--active {
        background: rgba(255, 255, 255, 0.14);
        color: var(--text-inverse);
        font-weight: 600;
        box-shadow: inset 3px 0 0 var(--hl-green);
      }

      .nav__link span:first-child {
        flex: 1;
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

      .content {
        min-width: 0;
        min-height: 0;
        overflow: auto;
        background: var(--surface-sunken);
      }

      @media (max-width: 900px) {
        .shell {
          grid-template-columns: 64px 1fr;
        }

        .brand__text,
        .identity__text {
          display: none;
        }

        .nav__link {
          justify-content: center;
          font-size: var(--text-xs);
        }
      }
    `,
  ],
})
export class App {
  private static readonly NAVIGATION: NavItem[] = [
    { path: '/workflows', label: 'Workflows', exact: false, permissions: ['WORKFLOW_VIEW'] },
    { path: '/executions', label: 'Executions', exact: false, permissions: ['EXECUTION_VIEW'] },
    { path: '/tasks', label: 'Tasks', exact: true, permissions: ['TASK_VIEW', 'TASK_VIEW_ALL'] },
    { path: '/forms', label: 'Forms', exact: false, permissions: ['WORKFLOW_VIEW'] },
    { path: '/nodes', label: 'Node types', exact: true, permissions: [] },
    { path: '/plugins', label: 'Plugins', exact: true, permissions: ['PLUGIN_VIEW'] },
    { path: '/secrets', label: 'Secrets', exact: true, permissions: ['SECRET_VIEW'] },
    { path: '/settings/ai-providers', label: 'AI Providers', exact: true, permissions: ['AI_PROVIDER_VIEW'] },
    { path: '/settings/ai-usage', label: 'AI Usage', exact: true, permissions: ['AI_PROVIDER_VIEW'] },
    // Not `exact`, so the entry stays highlighted on /settings/ai/claude-cli and its future siblings.
    {
      path: '/settings/ai',
      label: 'AI Configuration',
      exact: false,
      permissions: ['AI_CLI_VIEW'],
    },
    {
      path: '/settings/storage',
      label: 'File Storage',
      exact: true,
      permissions: ['WORKFLOW_STORAGE_SETTINGS_VIEW'],
    },
    { path: '/events', label: 'Events', exact: true, permissions: ['EVENT_EMIT'] },
    { path: '/admin/users', label: 'Users', exact: true, permissions: ['USER_VIEW'] },
    { path: '/admin/groups', label: 'Groups', exact: true, permissions: ['USER_VIEW'] },
  ];

  protected readonly state = inject(AuthStateService);

  private readonly auth = inject(AuthService);
  private readonly tasks = inject(TaskApiService);
  private readonly catalog = inject(NodeApiService);
  private readonly marketplace = inject(MarketplaceApiService);
  private readonly router = inject(Router);

  protected readonly waitingCount = signal(0);
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

  protected readonly visibleNavigation = computed(() =>
    App.NAVIGATION.filter(
      (item) => item.permissions.length === 0 || this.state.hasAny(...item.permissions),
    ),
  );

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
