import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MarketplaceApiService } from '../../core/api/marketplace-api.service';
import { AuthStateService } from '../../core/auth/auth-state.service';
import {
  PluginStatusView,
  PluginSyncStatus,
  describeStatus,
} from '../../core/models/marketplace.models';
import { NotificationService } from '../../core/notification.service';
import { AgoPipe } from '../../shared/pipes/format.pipes';
import { EmptyState } from '../../shared/ui/empty-state';
import { LoadingSkeleton } from '../../shared/ui/loading-skeleton';
import { PageHeader } from '../../shared/ui/page-header';
import { StatusPill } from '../../shared/ui/status-pill';
import { InstallDialog, InstallIntent } from './install-dialog';

/** The filter chips across the top. */
type Filter = 'ALL' | 'AVAILABLE' | 'INSTALLED' | 'UPDATES' | 'ATTENTION';

/**
 * The plugin marketplace.
 *
 * <h2>One list, not two</h2>
 *
 * Everything either side knows about appears here: what the registry offers, what this engine has installed,
 * and the cases where those disagree. Splitting the screen into "available" and "installed" would hide the
 * only rows that need a decision — a version installed here that the registry has since revoked belongs in
 * both lists and would be easy to miss in either.
 *
 * <h2>The catalogue's age is part of the screen</h2>
 *
 * The engine serves this list from a cached catalogue so it keeps answering while the registry is down. That
 * makes "this plugin is not listed" ambiguous, so the header states when the catalogue was last confirmed and
 * says plainly when it is stale. Installed plugins keep executing regardless, and the header says that too
 * rather than presenting an unreachable registry as a broken system.
 */
@Component({
  selector: 'wf-marketplace',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [StatusPill, EmptyState, LoadingSkeleton, PageHeader, InstallDialog, AgoPipe, RouterLink],
  template: `
    <div class="page">
      <wf-page-header
        title="Plugins"
        description="Node types beyond the four built-ins come from plugins. This engine reads a registry of them and installs on request, verifying each archive against its published checksum before anything loads it."
      >
        <a class="btn btn--sm" routerLink="/plugins/installed">Local administration</a>
        <button class="btn btn--sm" type="button" [disabled]="syncing()" (click)="sync()">
          {{ syncing() ? 'Syncing…' : 'Sync catalogue' }}
        </button>
      </wf-page-header>

      @if (health(); as state) {
        @if (!state.configured) {
          <div class="notice notice--warning banner">
            No plugin registry is configured, so nothing new can be discovered or installed. Plugins already
            installed here keep working. Set <span class="mono">plugin.server.base-url</span> to point at one.
          </div>
        } @else if (state.lastError) {
          <div class="notice notice--warning banner">
            The registry could not be reached
            @if (state.syncedAt) {
              — showing the catalogue as of {{ state.syncedAt | ago }}
            }
            . Installed plugins keep executing; installing a new one will fail until it is back.
            <div class="small muted">{{ state.lastError }}</div>
          </div>
        } @else if (state.stale) {
          <div class="notice notice--warning banner">
            The catalogue has not been confirmed current since {{ state.syncedAt | ago }}. It may be missing
            recent publications.
          </div>
        } @else {
          <p class="small muted banner">
            {{ state.plugins }} plugin{{ state.plugins === 1 ? '' : 's' }} in the catalogue, confirmed
            {{ state.syncedAt | ago }}.
          </p>
        }
      }

      <div class="filters">
        @for (option of filters; track option.key) {
          <button
            class="chip"
            type="button"
            [class.chip--active]="filter() === option.key"
            (click)="filter.set(option.key)"
          >
            {{ option.label }}
            <span class="chip__count">{{ countFor(option.key) }}</span>
          </button>
        }
        <span class="spacer"></span>
        <input
          type="search"
          class="search"
          placeholder="Search plugins"
          aria-label="Search plugins"
          [value]="query()"
          (input)="query.set($any($event.target).value)"
        />
      </div>

      @if (loading() && visible().length === 0) {
        <div class="card">
          <wf-loading-skeleton variant="page" label="Loading the plugin catalogue" />
        </div>
      } @else if (visible().length === 0) {
        <div class="card">
          <wf-empty-state heading="Nothing to show" [message]="emptyMessage()" />
        </div>
      }

      <div class="stack">
        @for (plugin of visible(); track plugin.pluginId) {
          <div class="card plugin">
            <div class="card__header">
              <a class="plugin__name" [routerLink]="['/plugins', plugin.pluginId]">
                {{ plugin.name || plugin.pluginId }}
              </a>
              <span class="tag tag--mono">{{ plugin.pluginId }}</span>
              <wf-status-pill [status]="plugin.status" [title]="describe(plugin.status)" />
              @if (plugin.vendor) {
                <span class="small muted">by {{ plugin.vendor }}</span>
              }
              <span class="spacer"></span>

              @if (plugin.status === 'NOT_INSTALLED') {
                <button
                  class="btn btn--primary btn--sm"
                  type="button"
                  [disabled]="!canInstall()"
                  [title]="installTitle()"
                  (click)="open(plugin, 'INSTALL')"
                >
                  Install
                </button>
              } @else if (plugin.status === 'UPDATE_AVAILABLE') {
                <button
                  class="btn btn--accent btn--sm"
                  type="button"
                  [disabled]="!canInstall()"
                  [title]="installTitle()"
                  (click)="open(plugin, 'UPDATE')"
                >
                  Update to {{ plugin.serverVersion }}
                </button>
              }
              <a class="btn btn--sm" [routerLink]="['/plugins', plugin.pluginId]">Details</a>
            </div>

            @if (plugin.description) {
              <p class="plugin__description">{{ plugin.description }}</p>
            }

            <div class="plugin__facts">
              <div class="fact">
                <span class="fact__label">Registry</span>
                <span class="mono">{{ plugin.serverVersion || 'not offered' }}</span>
              </div>
              <div class="fact">
                <span class="fact__label">Installed</span>
                <span class="mono">{{ plugin.installedVersion || 'none' }}</span>
              </div>
              <div class="fact fact--wide">
                <span class="fact__label">Node types</span>
                @if (plugin.nodeTypes.length === 0) {
                  <span class="faint small">none declared</span>
                } @else {
                  @for (nodeType of plugin.nodeTypes; track nodeType) {
                    <span class="tag tag--mono">{{ nodeType }}</span>
                  }
                }
              </div>
            </div>

            @if (!plugin.compatible && plugin.incompatibility.length > 0) {
              <div class="plugin__note notice notice--error">
                @for (reason of plugin.incompatibility; track reason) {
                  <div>{{ reason }}</div>
                }
              </div>
            } @else if (plugin.status === 'REVOKED') {
              <div class="plugin__note notice notice--error">
                {{ describe('REVOKED') }}
              </div>
            } @else if (plugin.deprecatedInstalled) {
              <div class="plugin__note notice notice--warning">
                A version installed here has been deprecated upstream. It still runs, and a newer release is
                expected to replace it.
              </div>
            }
          </div>
        }
      </div>
    </div>

    @if (dialog(); as request) {
      <wf-install-dialog
        [plugin]="request.plugin"
        [intent]="request.intent"
        (completed)="onCompleted()"
        (closed)="dialog.set(null)"
      />
    }
  `,
  styles: [
    `
      .banner {
        margin-bottom: var(--space-4);
      }

      .filters {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        margin-bottom: var(--space-4);
        flex-wrap: wrap;
      }

      .chip {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        padding: 4px 10px;
        border: 1px solid var(--border);
        border-radius: 999px;
        background: var(--surface);
        font-size: var(--text-sm);
        cursor: pointer;
      }

      .chip:hover {
        border-color: var(--border-strong);
      }

      .chip--active {
        border-color: var(--hl-accent-blue-alt);
        color: var(--hl-accent-blue-alt);
        font-weight: bold;
      }

      .chip__count {
        font-size: var(--text-xs);
        color: var(--text-muted);
      }

      .search {
        max-width: 260px;
      }

      .plugin__name {
        font-size: var(--text-lg);
        font-weight: bold;
        color: var(--text);
        text-decoration: none;
      }

      .plugin__name:hover {
        text-decoration: underline;
      }

      .plugin__description {
        margin: 0;
        padding: 0 var(--space-4) var(--space-3);
        color: var(--text-muted);
      }

      .plugin__facts {
        display: flex;
        flex-wrap: wrap;
        gap: var(--space-4);
        padding: 0 var(--space-4) var(--space-4);
      }

      .fact {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }

      .fact--wide {
        flex-direction: row;
        align-items: center;
        gap: var(--space-2);
        flex-wrap: wrap;
      }

      .fact__label {
        font-size: var(--text-xs);
        text-transform: uppercase;
        letter-spacing: 0.6px;
        color: var(--text-muted);
      }

      .plugin__note {
        margin: 0 var(--space-4) var(--space-4);
      }
    `,
  ],
})
export class Marketplace {
  protected readonly session = inject(AuthStateService);

  private readonly api = inject(MarketplaceApiService);
  private readonly notifications = inject(NotificationService);

  protected readonly filters: Array<{ key: Filter; label: string }> = [
    { key: 'ALL', label: 'All' },
    { key: 'AVAILABLE', label: 'Available' },
    { key: 'INSTALLED', label: 'Installed' },
    { key: 'UPDATES', label: 'Updates' },
    { key: 'ATTENTION', label: 'Needs attention' },
  ];

  protected readonly filter = signal<Filter>('ALL');
  protected readonly query = signal('');
  protected readonly syncing = signal(false);
  protected readonly dialog = signal<{ plugin: PluginStatusView; intent: InstallIntent } | null>(
    null,
  );

  protected readonly loading = this.api.loading;
  protected readonly health = this.api.health;

  protected readonly visible = computed(() => {
    const term = this.query().trim().toLowerCase();
    return this.api
      .statuses()
      .filter((plugin) => this.matchesFilter(plugin, this.filter()))
      .filter(
        (plugin) =>
          !term ||
          [plugin.pluginId, plugin.name ?? '', plugin.description ?? '', plugin.vendor ?? '']
            .concat(plugin.nodeTypes)
            .join(' ')
            .toLowerCase()
            .includes(term),
      );
  });

  protected readonly canInstall = computed(() => this.session.has('PLUGIN_UPLOAD'));

  constructor() {
    this.api.ensureLoaded();
  }

  protected describe(status: PluginSyncStatus): string {
    return describeStatus(status);
  }

  protected installTitle(): string {
    return this.canInstall()
      ? 'Download, verify and load this plugin'
      : 'Requires the PLUGIN_UPLOAD permission, which the ADMIN role grants';
  }

  protected countFor(filter: Filter): number {
    return this.api.statuses().filter((plugin) => this.matchesFilter(plugin, filter)).length;
  }

  protected emptyMessage(): string {
    if (this.loading()) {
      return 'Reading the catalogue this engine last synced from the registry.';
    }
    if (this.query()) {
      return `Nothing matches "${this.query()}".`;
    }
    switch (this.filter()) {
      case 'UPDATES':
        return 'Everything installed is at the newest version the registry offers.';
      case 'ATTENTION':
        return 'No plugin is revoked, incompatible or deprecated.';
      case 'INSTALLED':
        return 'No plugins are installed. The engine still runs workflows built from the built-in node types.';
      case 'AVAILABLE':
        return 'The registry offers nothing that is not already installed here.';
      default:
        return 'The catalogue is empty, and nothing is installed on this engine.';
    }
  }

  private matchesFilter(plugin: PluginStatusView, filter: Filter): boolean {
    switch (filter) {
      case 'ALL':
        return true;
      case 'AVAILABLE':
        return plugin.status === 'NOT_INSTALLED';
      case 'INSTALLED':
        return plugin.installedVersion !== null;
      case 'UPDATES':
        return plugin.status === 'UPDATE_AVAILABLE';
      case 'ATTENTION':
        // The three that ask somebody to decide something, rather than merely reporting a state.
        return (
          plugin.status === 'REVOKED' ||
          plugin.status === 'INCOMPATIBLE' ||
          plugin.status === 'DEPRECATED' ||
          plugin.deprecatedInstalled
        );
    }
  }

  protected open(plugin: PluginStatusView, intent: InstallIntent): void {
    this.dialog.set({ plugin, intent });
  }

  protected onCompleted(): void {
    // The dialog reports the outcome itself and has already invalidated the caches; this only has to make
    // sure the rows behind it agree once the operator closes it.
    this.api.invalidate();
  }

  protected sync(): void {
    this.syncing.set(true);
    this.api.sync().subscribe({
      next: (result) => {
        this.syncing.set(false);
        this.api.invalidate();
        switch (result.outcome) {
          case 'UPDATED':
            this.notifications.success(`Catalogue updated: ${result.plugins} plugin(s)`);
            break;
          case 'UNCHANGED':
            this.notifications.info('The catalogue was already current');
            break;
          case 'NOT_CONFIGURED':
            this.notifications.warning('No plugin registry is configured');
            break;
          case 'FAILED':
            this.notifications.error(
              'The registry could not be reached',
              result.error ?? 'The previous catalogue is still in use.',
            );
            break;
        }
      },
      error: () => this.syncing.set(false),
    });
  }
}
