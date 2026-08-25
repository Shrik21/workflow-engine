import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from './core/auth/auth.service';
import { ToastHost } from './shared/toast-host';

/**
 * The shell: a fixed sidebar, the routed page, and the toast host.
 *
 * Sign-in renders without the shell. A navigation frame around a page whose only purpose is to establish who
 * you are advertises destinations that would all bounce back to it.
 */
@Component({
  selector: 'ps-app',
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
              <strong>Plugin Registry</strong>
              <span class="brand__sub">Administration</span>
            </span>
          </div>

          <nav class="nav" aria-label="Sections">
            <a
              class="nav__link"
              routerLink="/plugins"
              routerLinkActive="nav__link--active"
              [routerLinkActiveOptions]="{ exact: true }"
            >
              All plugins
            </a>
            @if (auth.hasPermission('PLUGIN_UPLOAD')) {
              <a class="nav__link" routerLink="/plugins/upload" routerLinkActive="nav__link--active">
                Upload plugin
              </a>
            }
            @if (auth.hasPermission('USER_READ')) {
              <a class="nav__link" routerLink="/users" routerLinkActive="nav__link--active">Users</a>
            }
            @if (auth.hasPermission('ROLE_READ')) {
              <a class="nav__link" routerLink="/roles" routerLinkActive="nav__link--active">Roles</a>
            }
            @if (auth.hasPermission('PLUGIN_AUDIT_READ')) {
              <a class="nav__link" routerLink="/security/audit" routerLinkActive="nav__link--active">
                Security audit
              </a>
            }
          </nav>

          <div class="sidebar__footer">
            <div class="identity">
              <span class="identity__avatar" aria-hidden="true">{{ initials() }}</span>
              <a class="identity__text" routerLink="/profile">
                <strong>{{ auth.identity()?.displayName }}</strong>
                <span class="small muted">{{ roleLabel() }}</span>
              </a>
            </div>
            <button class="btn btn--quiet btn--sm" type="button" (click)="signOut()">Sign out</button>
          </div>
        </aside>

        <main class="content">
          <router-outlet />
        </main>
      </div>
    }

    <ps-toast-host />
  `,
  styles: [
    `
      :host {
        display: block;
        height: 100%;
      }

      .shell {
        display: grid;
        grid-template-columns: 232px 1fr;
        height: 100%;
        min-height: 100vh;
      }

      .sidebar {
        display: flex;
        flex-direction: column;
        background: var(--surface);
        border-right: 1px solid var(--border);
        padding: var(--space-4) var(--space-3);
      }

      .brand {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        padding: 0 var(--space-2) var(--space-4);
      }

      .brand__mark {
        display: grid;
        place-items: center;
        flex: none;
      }

      .brand__text {
        display: flex;
        flex-direction: column;
        line-height: 1.2;
      }

      .brand__sub {
        font-size: var(--text-xs);
        color: var(--text-muted);
      }

      .nav {
        display: flex;
        flex-direction: column;
        gap: 2px;
        flex: 1;
      }

      .nav__link {
        display: block;
        padding: var(--space-2) var(--space-3);
        border-radius: var(--radius-sm);
        color: var(--text);
        text-decoration: none;
        font-size: var(--text-base);
      }

      .nav__link:hover {
        background: var(--hl-grey-100);
      }

      .nav__link--active {
        background: color-mix(in srgb, var(--hl-accent-blue-alt) 12%, transparent);
        color: var(--hl-accent-blue-alt);
        font-weight: bold;
      }

      .sidebar__footer {
        border-top: 1px solid var(--border);
        padding-top: var(--space-3);
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
      }

      .identity {
        display: flex;
        align-items: center;
        gap: var(--space-2);
      }

      .identity__avatar {
        display: grid;
        place-items: center;
        width: 30px;
        height: 30px;
        border-radius: 50%;
        background: var(--hl-grey-200);
        font-size: var(--text-xs);
        font-weight: bold;
        flex: none;
      }

      .identity__text {
        display: flex;
        flex-direction: column;
        min-width: 0;
        line-height: 1.25;
      }

      .identity__text strong {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .content {
        overflow-y: auto;
        background: var(--surface-sunken);
      }

      /* Below a tablet the sidebar becomes a strip across the top: a 232px column on a phone leaves
         nothing for the table it exists to navigate to. */
      @media (max-width: 860px) {
        .shell {
          grid-template-columns: 1fr;
          grid-template-rows: auto 1fr;
        }

        .sidebar {
          border-right: none;
          border-bottom: 1px solid var(--border);
        }

        .nav {
          flex-direction: row;
          flex-wrap: wrap;
        }

        .sidebar__footer {
          flex-direction: row;
          align-items: center;
          justify-content: space-between;
        }
      }
    `,
  ],
})
export class App {
  protected readonly auth = inject(AuthService);

  private readonly router = inject(Router);
  private readonly currentUrl = signal(this.router.url);

  protected readonly chromeless = computed(
    () => this.currentUrl().startsWith('/login') || !this.auth.isAuthenticated(),
  );

  protected readonly roleLabel = computed(
    () => this.auth.identity()?.roles.join(', ') || 'no roles',
  );

  protected readonly initials = computed(() => {
    const name = this.auth.identity()?.displayName ?? '';
    const parts = name.split(/\s+/).filter((part) => part.length > 0);
    if (parts.length === 0) {
      return '?';
    }
    return parts.length === 1
      ? parts[0].slice(0, 2).toUpperCase()
      : (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  });

  constructor() {
    this.router.events
      .pipe(filter((event): event is NavigationEnd => event instanceof NavigationEnd))
      .subscribe((event) => this.currentUrl.set(event.urlAfterRedirects));
  }

  protected signOut(): void {
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}
