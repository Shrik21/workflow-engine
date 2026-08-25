import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MarketplaceApiService } from '../../core/api/marketplace-api.service';
import { AuthStateService } from '../../core/auth/auth-state.service';
import {
  InstalledVersionView,
  PluginInstallationRecord,
  PluginStatusView,
  describeStatus,
} from '../../core/models/marketplace.models';
import { NotificationService } from '../../core/notification.service';
import { AgoPipe, ShortIdPipe } from '../../shared/pipes/format.pipes';
import { EmptyState } from '../../shared/ui/empty-state';
import { StatusPill } from '../../shared/ui/status-pill';
import { InstallDialog, InstallIntent } from './install-dialog';

/** One row of the version table, merging what the registry offers with what is installed. */
interface VersionRow {
  version: string;
  offered: boolean;
  installed: InstalledVersionView | null;
  isLatest: boolean;
}

/**
 * One plugin, in full.
 *
 * <h2>Both sides of every version</h2>
 *
 * The version table is the union of what the registry publishes and what this engine has, because the
 * interesting rows are the ones that appear on only one side: a version installed here that the registry has
 * dropped still runs and still needs managing, and a version the registry offers that is not installed is the
 * one somebody came here to install. A table of either side alone would hide half the decisions.
 *
 * <h2>History is part of the page, not an audit screen</h2>
 *
 * Installs, updates, removals, and the ones that failed or were refused, all in order. When a plugin is
 * misbehaving the first question is what changed and when, and answering it should not require opening
 * another screen and filtering a global log.
 */
import { Icon } from '../../shared/ui/icon';
@Component({
  selector: 'wf-plugin-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, StatusPill, EmptyState, InstallDialog, RouterLink, AgoPipe, ShortIdPipe],
  template: `
    <div class="page">
      @if (plugin(); as view) {
        <div class="page-header">
          <div class="page-header__text">
            <h1>{{ view.name || view.pluginId }}</h1>
            <p>
              {{ view.description || 'No description was published for this plugin.' }}
            </p>
            <div class="identity">
              <span class="tag tag--mono">{{ view.pluginId }}</span>
              <wf-status-pill [status]="view.status" [title]="describe(view)" />
              @if (view.vendor) {
                <span class="small muted">by {{ view.vendor }}</span>
              }
            </div>
          </div>
          <div class="toolbar">
            <a class="btn btn--sm" routerLink="/plugins">Back to plugins</a>
            @if (view.status === 'NOT_INSTALLED') {
              <button
                class="btn btn--primary btn--sm"
                type="button"
                [disabled]="!canInstall()"
                (click)="open(view, 'INSTALL')"
              >
                Install {{ view.serverVersion }}
              </button>
            } @else if (view.status === 'UPDATE_AVAILABLE') {
              <button
                class="btn btn--accent btn--sm"
                type="button"
                [disabled]="!canInstall()"
                (click)="open(view, 'UPDATE')"
              >
                Update to {{ view.serverVersion }}
              </button>
            }
          </div>
        </div>

        <p class="notice banner">{{ describe(view) }}</p>

        @if (!view.compatible && view.incompatibility.length > 0) {
          <div class="notice notice--error banner">
            @for (reason of view.incompatibility; track reason) {
              <div>{{ reason }}</div>
            }
          </div>
        }

        <div class="card">
          <div class="card__header"><h3>Versions</h3></div>
          <table class="table">
            <thead>
              <tr>
                <th>Version</th>
                <th>Registry</th>
                <th>On this engine</th>
                <th>Installed</th>
                <th class="cell-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              @for (row of versions(); track row.version) {
                <tr>
                  <td>
                    <strong class="mono">{{ row.version }}</strong>
                    @if (row.isLatest) {
                      <span class="tag" title="Newest release the registry offers">latest</span>
                    }
                    @if (row.installed?.isDefault) {
                      <span class="tag" title="Serves nodes that do not pin a version">default</span>
                    }
                  </td>
                  <td class="small">
                    @if (row.offered) {
                      <span class="muted">published</span>
                    } @else {
                      <span class="faint">not in the catalogue</span>
                    }
                  </td>
                  <td>
                    @if (row.installed) {
                      <wf-status-pill [status]="row.installed.state" />
                      @if (row.installed.failure) {
                        <div class="small failure" [title]="row.installed.failure">
                          {{ row.installed.failure }}
                        </div>
                      }
                    } @else {
                      <span class="faint small">not installed</span>
                    }
                  </td>
                  <td class="small muted">
                    @if (row.installed?.installedAt) {
                      {{ row.installed!.installedAt | ago }}
                    }
                  </td>
                  <td class="cell-actions">
                    @if (!row.installed) {
                      <button
                        class="btn btn--sm"
                        type="button"
                        [disabled]="!canInstall() || !row.offered || !view.compatible"
                        [title]="
                          row.offered
                            ? 'Install this version alongside any already present'
                            : 'The registry no longer publishes this version'
                        "
                        (click)="open(view, 'INSTALL', row.version)"
                      >
                        Install
                      </button>
                    } @else {
                      @if (row.installed.state === 'ACTIVE') {
                        <button
                          class="btn btn--sm"
                          type="button"
                          [disabled]="busy() || !session.has('PLUGIN_DEACTIVATE')"
                          title="Unload without removing. Refused while a published workflow depends on it."
                          (click)="deactivate(view, row.version)"
                        >
                          Deactivate
                        </button>
                      } @else {
                        <button
                          class="btn btn--accent btn--sm"
                          type="button"
                          [disabled]="busy() || !session.has('PLUGIN_ACTIVATE')"
                          (click)="activate(view, row.version)"
                        >
                          Activate
                        </button>
                      }
                      <button
                        class="btn btn--danger btn--sm"
                        type="button"
                        [disabled]="!session.has('PLUGIN_DELETE')"
                        (click)="open(view, 'UNINSTALL', row.version)"
                      >
                        Remove
                      </button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        @if (view.nodeTypes.length > 0) {
          <div class="card">
            <div class="card__header"><h3>Node types</h3></div>
            <div class="card__body node-types">
              @for (nodeType of view.nodeTypes; track nodeType) {
                <span class="tag tag--mono">{{ nodeType }}</span>
              }
              <p class="small muted">
                These appear in the designer palette once a version contributing them is active. A workflow
                node may name the type directly or pin this plugin and a version.
              </p>
            </div>
          </div>
        }

        <div class="card">
          <div class="card__header">
            <h3>Installation history</h3>
            <span class="spacer"></span>
            <button class="btn btn--sm" type="button" (click)="loadHistory()"><wf-icon name="refresh" /><span>Refresh</span></button>
          </div>
          @if (history().length === 0) {
            <div class="card__body">
              <p class="small muted">
                Nothing recorded yet. Every install, update, removal, activation and deactivation is kept
                here, including the ones that failed or were refused.
              </p>
            </div>
          } @else {
            <table class="table">
              <thead>
                <tr>
                  <th>When</th>
                  <th>Action</th>
                  <th>Version</th>
                  <th>Outcome</th>
                  <th>By</th>
                  <th>Detail</th>
                </tr>
              </thead>
              <tbody>
                @for (record of history(); track record.id) {
                  <tr>
                    <td class="small muted">{{ record.at | ago }}</td>
                    <td class="small"><strong>{{ record.action }}</strong></td>
                    <td class="small mono">
                      @if (record.fromVersion) {
                        {{ record.fromVersion }} →
                      }
                      {{ record.version }}
                    </td>
                    <td><wf-status-pill [status]="outcomeStatus(record)" /></td>
                    <td class="small muted">{{ record.actor }}</td>
                    <td class="small">
                      <div>{{ record.detail }}</div>
                      @if (record.checksum) {
                        <div class="mono faint" [title]="record.checksum">
                          sha256 {{ record.checksum | shortId: 8 }}
                          @if (record.durationMillis > 0) {
                            · {{ record.durationMillis }} ms
                          }
                        </div>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </div>
      } @else {
        <div class="card">
          <wf-empty-state
            [heading]="loading() ? 'Loading…' : 'No such plugin'"
            message="Neither the registry catalogue nor this engine knows a plugin with that id."
          >
            <a class="btn" routerLink="/plugins">Back to plugins</a>
          </wf-empty-state>
        </div>
      }
    </div>

    @if (dialog(); as request) {
      <wf-install-dialog
        [plugin]="request.plugin"
        [intent]="request.intent"
        [version]="request.version"
        (completed)="reload()"
        (closed)="dialog.set(null)"
      />
    }
  `,
  styles: [
    `
      .identity {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        margin-top: var(--space-2);
      }

      .banner {
        margin-bottom: var(--space-4);
      }

      .failure {
        color: var(--hl-error);
        max-width: 44ch;
        overflow: hidden;
        text-overflow: ellipsis;
      }

      .node-types {
        display: flex;
        flex-wrap: wrap;
        gap: var(--space-2);
        align-items: center;
      }

      .node-types p {
        flex-basis: 100%;
        margin: var(--space-2) 0 0;
      }

      .stack .card {
        margin-bottom: var(--space-4);
      }

      .card {
        margin-bottom: var(--space-4);
      }
    `,
  ],
})
export class PluginDetail {
  /** Bound from the route by `withComponentInputBinding()`. */
  readonly pluginId = input.required<string>();

  protected readonly session = inject(AuthStateService);

  private readonly api = inject(MarketplaceApiService);
  private readonly notifications = inject(NotificationService);

  protected readonly plugin = signal<PluginStatusView | null>(null);
  protected readonly history = signal<PluginInstallationRecord[]>([]);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly dialog = signal<{
    plugin: PluginStatusView;
    intent: InstallIntent;
    version: string | null;
  } | null>(null);

  protected readonly canInstall = computed(() => this.session.has('PLUGIN_UPLOAD'));

  /**
   * The version table: everything either side knows about, newest first.
   *
   * Ordering is by the registry's list where it has one, because that list is already in precedence order
   * and reproducing semantic-version comparison in the browser would be a second implementation of a rule
   * the SDK already owns. Versions only this engine has are appended, which is where a locally uploaded or
   * withdrawn version belongs: present, and visibly not part of the catalogue.
   */
  protected readonly versions = computed<VersionRow[]>(() => {
    const view = this.plugin();
    if (!view) {
      return [];
    }
    const installed = new Map(view.installedVersions.map((entry) => [entry.version, entry]));
    const rows: VersionRow[] = view.availableVersions.map((version) => ({
      version,
      offered: true,
      installed: installed.get(version) ?? null,
      isLatest: version === view.serverVersion,
    }));
    for (const entry of view.installedVersions) {
      if (!view.availableVersions.includes(entry.version)) {
        rows.push({ version: entry.version, offered: false, installed: entry, isLatest: false });
      }
    }
    return rows;
  });

  constructor() {
    effect(() => {
      // Re-runs whenever the route parameter changes, which is what makes navigating between two plugin
      // pages load the second one rather than leaving the first on screen.
      const id = this.pluginId();
      this.load(id);
    });
  }

  protected describe(view: PluginStatusView): string {
    return describeStatus(view.status);
  }

  /** Maps a history outcome onto the status vocabulary the pill already knows. */
  protected outcomeStatus(record: PluginInstallationRecord): string {
    switch (record.outcome) {
      case 'OK':
        return 'COMPLETED';
      case 'FAILED':
        return 'FAILED';
      case 'REFUSED':
        return 'CANCELLED';
    }
  }

  protected reload(): void {
    this.load(this.pluginId());
  }

  private load(pluginId: string): void {
    this.loading.set(true);
    this.api.status(pluginId).subscribe({
      next: (view) => {
        this.plugin.set(view);
        this.loading.set(false);
      },
      error: () => {
        this.plugin.set(null);
        this.loading.set(false);
      },
    });
    this.loadHistory();
  }

  protected loadHistory(): void {
    this.api.history(this.pluginId()).subscribe({
      next: (records) => this.history.set(records),
      error: () => this.history.set([]),
    });
  }

  protected open(plugin: PluginStatusView, intent: InstallIntent, version: string | null = null): void {
    this.dialog.set({ plugin, intent, version });
  }

  protected activate(view: PluginStatusView, version: string): void {
    this.busy.set(true);
    this.api.activateVersion(view.pluginId, version).subscribe({
      next: (result) => {
        this.busy.set(false);
        this.notifications.success(result.message);
        this.api.invalidate();
        this.reload();
      },
      error: () => this.busy.set(false),
    });
  }

  protected deactivate(view: PluginStatusView, version: string): void {
    this.busy.set(true);
    this.api.deactivateVersion(view.pluginId, version).subscribe({
      next: (result) => {
        this.busy.set(false);
        this.notifications.success(result.message);
        this.api.invalidate();
        this.reload();
      },
      // A 409 here means a published workflow depends on the version; the interceptor shows the engine's
      // sentence, which already names them.
      error: () => this.busy.set(false),
    });
  }
}
