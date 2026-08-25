import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { NodeApiService } from '../../core/api/node-api.service';
import { PluginApiService } from '../../core/api/plugin-api.service';
import { PluginResponse, PluginVersionResponse } from '../../core/models/plugin.models';
import { NotificationService } from '../../core/notification.service';
import { AuthStateService } from '../../core/auth/auth-state.service';
import { AgoPipe, BytesPipe, PrettyJsonPipe, ShortIdPipe } from '../../shared/pipes/format.pipes';
import { EmptyState } from '../../shared/ui/empty-state';
import { StatusPill } from '../../shared/ui/status-pill';
import { ConfirmDialog, ConfirmRequest } from '../../shared/ui/confirm-dialog';
import { PluginUpload } from './plugin-upload';

/**
 * Plugin administration.
 *
 * Shows both the persisted status and whether the version is loaded in this instance right now, because
 * they can disagree and the disagreement is exactly what an operator needs to see: a version marked
 * ACTIVE that is not loaded has a `loadError`, and every workflow depending on it is failing.
 *
 * Granted permissions are displayed per version rather than tucked away. On a platform that loads
 * third-party code, "what is this allowed to reach" is a first-class property of an installation.
 */
import { Icon } from '../../shared/ui/icon';
@Component({
  selector: 'wf-plugin-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, StatusPill, ConfirmDialog, EmptyState, PluginUpload, AgoPipe, BytesPipe, ShortIdPipe, PrettyJsonPipe],
  template: `
    <div class="page">
      <div class="page-header">
        <div class="page-header__text">
          <h1>Plugins</h1>
          <p>
            Every node type beyond the four built-ins comes from a plugin JAR uploaded here. Uploading
            one installs executable code into the engine and makes its node types available without a
            restart.
          </p>
        </div>
        <div class="toolbar">
          <button class="btn btn--sm" type="button" [disabled]="loading()" (click)="load()">
            <wf-icon name="refresh" /><span>Refresh</span>
          </button>
          <button
            class="btn btn--primary"
            type="button"
            [disabled]="!session.has('PLUGIN_UPLOAD')"
            [title]="
              session.has('PLUGIN_UPLOAD')
                ? 'Install a plugin JAR'
                : 'Requires the PLUGIN_UPLOAD permission, which the ADMIN role grants'
            "
            (click)="uploading.set(true)"
          >
            Install plugin
          </button>
        </div>
      </div>

      @if (!session.has('PLUGIN_VIEW')) {
        <div class="notice notice--warning" style="margin-bottom: var(--space-4)">
          Managing plugins requires the ADMIN role: uploading a JAR runs code inside the engine's JVM.
          Ask an administrator if you need access.
        </div>
      }

      @if (plugins().length === 0 && !loading()) {
        <div class="card">
          <wf-empty-state
            heading="No plugins installed"
            message="The engine can still run workflows built from the four built-in node types. Install a plugin to add integrations such as email, HTTP or chat."
          >
            <button
              class="btn btn--primary"
              type="button"
              [disabled]="!session.has('PLUGIN_UPLOAD')"
              (click)="uploading.set(true)"
            >
              Install plugin
            </button>
          </wf-empty-state>
        </div>
      }

      <div class="stack">
        @for (plugin of plugins(); track plugin.id) {
          <div class="card">
            <div class="card__header">
              <h3>{{ plugin.name || plugin.id }}</h3>
              <span class="tag tag--mono">{{ plugin.id }}</span>
              <wf-status-pill [status]="plugin.status" />
              @if (plugin.pluginType) {
                <span class="tag">{{ plugin.pluginType }}</span>
              }
              <span class="spacer"></span>
              <span class="small muted">
                default
                <strong class="mono">{{ plugin.defaultVersion || 'none' }}</strong>
              </span>
              <button
                class="btn btn--danger btn--sm"
                type="button"
                [disabled]="!session.has('PLUGIN_UPLOAD')"
                (click)="removePlugin(plugin)"
              >
                Delete all
              </button>
            </div>

            @if (plugin.description) {
              <p class="description">{{ plugin.description }}</p>
            }

            <table class="table">
              <thead>
                <tr>
                  <th>Version</th>
                  <th>Status</th>
                  <th>Node types</th>
                  <th>Permissions</th>
                  <th>Calls</th>
                  <th>JAR</th>
                  <th class="cell-actions">Actions</th>
                </tr>
              </thead>
              <tbody>
                @for (version of plugin.versions; track version.version) {
                  <tr>
                    <td>
                      <strong class="mono">{{ version.version }}</strong>
                      @if (version.version === plugin.defaultVersion) {
                        <span class="tag" title="Serves nodes that do not pin a version">default</span>
                      }
                      <div class="small muted">
                        {{ version.uploadedAt | ago }}
                        @if (version.uploadedBy) {
                          by {{ version.uploadedBy }}
                        }
                      </div>
                    </td>
                    <td>
                      <wf-status-pill [status]="version.status" />
                      <div class="small" [class.muted]="version.loaded">
                        @if (version.loaded) {
                          loaded
                        } @else {
                          <span class="not-loaded">not loaded</span>
                        }
                      </div>
                      @if (version.loadError) {
                        <div class="small load-error" [title]="version.loadError">
                          {{ version.loadError }}
                        </div>
                      }
                    </td>
                    <td>
                      @if (version.nodeTypes.length === 0) {
                        <span class="faint small">none</span>
                      } @else {
                        @for (nodeType of version.nodeTypes; track nodeType) {
                          <span class="tag tag--mono">{{ nodeType }}</span>
                        }
                      }
                    </td>
                    <td class="small">
                      <div>
                        <span class="muted">hosts</span>
                        @if (version.allowedHosts.length === 0) {
                          <span class="denied">none</span>
                        } @else {
                          <span class="mono">{{ version.allowedHosts.join(', ') }}</span>
                        }
                      </div>
                      <div>
                        <span class="muted">secrets</span>
                        @if (version.secretScopes.length === 0) {
                          <span class="denied">none</span>
                        } @else {
                          <span class="mono">{{ version.secretScopes.join(', ') }}</span>
                        }
                      </div>
                      <div>
                        <span class="muted">events</span>
                        @if (version.eventsEnabled) {
                          <span>allowed</span>
                        } @else {
                          <span class="denied">denied</span>
                        }
                      </div>
                      @if (session.has('PLUGIN_UPLOAD')) {
                        <button
                          class="btn btn--quiet btn--sm"
                          type="button"
                          [disabled]="busy()"
                          title="Change what this version may reach"
                          (click)="editPermissions(plugin, version)"
                        >
                          Edit
                        </button>
                      }
                    </td>
                    <td class="small">
                      <div>{{ version.totalCalls }} total</div>
                      @if (version.failedCalls > 0) {
                        <div class="load-error">{{ version.failedCalls }} failed</div>
                      }
                      @if (version.activeCalls > 0) {
                        <div class="muted">{{ version.activeCalls }} in flight</div>
                      }
                    </td>
                    <td class="small muted">
                      <div>{{ version.jarSizeBytes | bytes }}</div>
                      <div class="mono" [title]="version.sha256 ?? ''">
                        {{ version.sha256 | shortId: 6 }}
                      </div>
                      @if (version.signed) {
                        <span class="tag">signed</span>
                      }
                    </td>
                    <td class="cell-actions">
                      @if (version.status === 'ACTIVE' && version.loaded) {
                        <button
                          class="btn btn--sm"
                          type="button"
                          [disabled]="busy() || !session.has('PLUGIN_ACTIVATE')"
                          title="Drain, unload and load again to pick up changed settings"
                          (click)="reload(plugin, version)"
                        >
                          Reload
                        </button>
                        <button
                          class="btn btn--danger btn--sm"
                          type="button"
                          [disabled]="busy() || !session.has('PLUGIN_ACTIVATE')"
                          title="Stop admitting work, drain, and remove the node types"
                          (click)="deactivate(plugin, version)"
                        >
                          Deactivate
                        </button>
                      } @else {
                        <button
                          class="btn btn--accent btn--sm"
                          type="button"
                          [disabled]="busy() || !session.has('PLUGIN_ACTIVATE')"
                          (click)="activate(plugin, version)"
                        >
                          Activate
                        </button>
                      }
                      @if (version.version !== plugin.defaultVersion && version.loaded) {
                        <button
                          class="btn btn--sm"
                          type="button"
                          [disabled]="busy() || !session.has('PLUGIN_ACTIVATE')"
                          title="Serve unpinned workflow nodes from this version"
                          (click)="makeDefault(plugin, version)"
                        >
                          Make default
                        </button>
                      }
                      <button
                        class="btn btn--sm"
                        type="button"
                        (click)="toggleHistory(plugin.id, version.version)"
                      >
                        History
                      </button>
                      <button
                        class="btn btn--danger btn--sm"
                        type="button"
                        [disabled]="busy() || !session.has('PLUGIN_ACTIVATE')"
                        (click)="removeVersion(plugin, version)"
                      >
                        Delete
                      </button>
                    </td>
                  </tr>

                  @if (editKey() === plugin.id + ':' + version.version) {
                    <tr>
                      <td colspan="7" class="editor">
                        <h4>Permissions for {{ plugin.id }} {{ version.version }}</h4>
                        <p class="small muted">
                          Granted here, never by the plugin. These constrain a plugin that goes through the
                          engine's HTTP client and secret provider — a plugin that opens its own socket, such
                          as MongoDB or SMTP, is not bound by the host list.
                        </p>

                        <div class="field">
                          <label class="field__label" [attr.for]="'hosts-' + version.version">
                            Allowed hosts
                          </label>
                          <input
                            [id]="'hosts-' + version.version"
                            type="text"
                            class="mono"
                            placeholder="api.sendgrid.com, *.example.com"
                            [value]="editHosts()"
                            (input)="editHosts.set($any($event.target).value)"
                          />
                          <p class="field__hint">
                            Comma-separated. A leading <span class="mono">*</span> is a wildcard. Empty denies
                            every outbound call through the engine's client.
                          </p>
                        </div>

                        <div class="field">
                          <label class="field__label" [attr.for]="'scopes-' + version.version">
                            Secret scopes
                          </label>
                          <input
                            [id]="'scopes-' + version.version"
                            type="text"
                            class="mono"
                            placeholder="sendgrid., smtp."
                            [value]="editScopes()"
                            (input)="editScopes.set($any($event.target).value)"
                          />
                          <p class="field__hint">
                            Comma-separated name prefixes. <span class="mono">sendgrid.</span> permits
                            <span class="mono">sendgrid.apiKey</span> and nothing else. Empty denies all secrets.
                          </p>
                        </div>

                        <label class="checkbox-row">
                          <input
                            type="checkbox"
                            [checked]="editEvents()"
                            (change)="editEvents.set($any($event.target).checked)"
                          />
                          <span class="small">May publish business events</span>
                        </label>

                        @if (version.loaded) {
                          <p class="small muted">
                            This version is loaded, so saving reloads it and the new hosts apply at once.
                            In-flight calls finish on the old permissions.
                          </p>
                        }

                        <div class="editor__actions">
                          <button
                            class="btn btn--accent btn--sm"
                            type="button"
                            [disabled]="busy()"
                            (click)="savePermissions(plugin, version)"
                          >
                            Save
                          </button>
                          <button
                            class="btn btn--sm"
                            type="button"
                            [disabled]="busy()"
                            (click)="editKey.set(null)"
                          >
                            Cancel
                          </button>
                        </div>
                      </td>
                    </tr>
                  }

                  @if (historyKey() === plugin.id + ':' + version.version) {
                    <tr>
                      <td colspan="7" class="history">
                        <h4>Recent invocations</h4>
                        @if (history().length === 0) {
                          <p class="small muted">
                            No recorded invocations. Every plugin call is recorded here with its
                            request and response, after secret redaction.
                          </p>
                        } @else {
                          <table class="table">
                            <thead>
                              <tr>
                                <th>When</th>
                                <th>Node</th>
                                <th>Status</th>
                                <th>Duration</th>
                                <th>Detail</th>
                              </tr>
                            </thead>
                            <tbody>
                              @for (record of history(); track record.id) {
                                <tr>
                                  <td class="small muted">{{ record.startTime | ago }}</td>
                                  <td class="mono small">{{ record.nodeId }}</td>
                                  <td><wf-status-pill [status]="record.status" /></td>
                                  <td class="small">{{ record.durationMillis }} ms</td>
                                  <td>
                                    @if (record.errorCode) {
                                      <div class="load-error small">
                                        {{ record.errorCode }}: {{ record.errorMessage }}
                                      </div>
                                    }
                                    <details>
                                      <summary class="small muted">request and response</summary>
                                      <pre class="code">{{ record.request | prettyJson }}</pre>
                                      <pre class="code">{{ record.response | prettyJson }}</pre>
                                    </details>
                                  </td>
                                </tr>
                              }
                            </tbody>
                          </table>
                        }
                      </td>
                    </tr>
                  }
                }
              </tbody>
            </table>
          </div>
        }
      </div>
    </div>

    @if (uploading()) {
      <wf-plugin-upload (installed)="onInstalled()" (cancelled)="uploading.set(false)" />
    }

    @if (pendingConfirm(); as c) {
      <wf-confirm-dialog
        [heading]="c.heading"
        [message]="c.message"
        [confirmLabel]="c.confirmLabel"
        [danger]="c.danger"
        (confirmed)="runConfirmed()"
        (cancelled)="pendingConfirm.set(null)"
      />
    }
  `,
  styles: [
    `
      .description {
        margin: 0;
        padding: 0 var(--space-4) var(--space-3);
        color: var(--text-muted);
        font-size: var(--text-base);
      }

      .not-loaded {
        color: var(--hl-orange-alt);
        font-weight: bold;
      }

      .load-error {
        color: var(--hl-error);
        max-width: 40ch;
        overflow: hidden;
        text-overflow: ellipsis;
      }

      .denied {
        color: var(--hl-grey-600);
        font-style: italic;
      }

      .history,
      .editor {
        background: var(--surface-sunken);
      }

      .history h4,
      .editor h4 {
        margin-bottom: var(--space-2);
      }

      .editor .field {
        max-width: 60ch;
        margin-bottom: var(--space-3);
      }

      .editor__actions {
        display: flex;
        gap: var(--space-2);
        margin-top: var(--space-3);
      }

      .code {
        font-family: var(--font-mono);
        font-size: var(--text-xs);
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        padding: var(--space-2);
        margin: var(--space-2) 0 0;
        max-height: 220px;
        overflow: auto;
      }

      td.small div {
        margin-bottom: 2px;
      }
    `,
  ],
})
export class PluginList {
  protected readonly session = inject(AuthStateService);

  private readonly api = inject(PluginApiService);
  private readonly catalog = inject(NodeApiService);
  private readonly notifications = inject(NotificationService);

  protected readonly plugins = signal<PluginResponse[]>([]);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly pendingConfirm = signal<ConfirmRequest | null>(null);
  protected readonly uploading = signal(false);
  protected readonly historyKey = signal<string | null>(null);
  protected readonly history = signal<
    Array<import('../../core/models/plugin.models').PluginExecutionResponse>
  >([]);

  /** The version whose permissions are being edited, as `pluginId:version`, and the draft fields. */
  protected readonly editKey = signal<string | null>(null);
  protected readonly editHosts = signal('');
  protected readonly editScopes = signal('');
  protected readonly editEvents = signal(true);

  protected readonly totalVersions = computed(() =>
    this.plugins().reduce((count, plugin) => count + plugin.versions.length, 0),
  );

  constructor() {
    if (this.session.has('PLUGIN_VIEW')) {
      this.load();
    }
  }

  protected load(): void {
    if (!this.session.has('PLUGIN_VIEW')) {
      return;
    }
    this.loading.set(true);
    this.api.list().subscribe({
      next: (plugins) => {
        this.plugins.set(plugins);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected onInstalled(): void {
    this.uploading.set(false);
    this.load();
    // The palette and the node browser are now stale: a new node type exists.
    this.catalog.invalidate();
  }

  protected activate(plugin: PluginResponse, version: PluginVersionResponse): void {
    this.run(this.api.activate(plugin.id, version.version), `Activated ${plugin.id} ${version.version}`);
  }

  protected deactivate(plugin: PluginResponse, version: PluginVersionResponse): void {
    this.pendingConfirm.set({
      heading: 'Deactivate plugin version?',
      message:
        `Deactivate ${plugin.id} ${version.version}?\n\n` +
        `In-flight invocations are drained, then the node types ${version.nodeTypes.join(', ') || '(none)'} ` +
        `stop being resolvable. Workflows using them will fail until it is activated again.`,
      confirmLabel: 'Deactivate',
      danger: true,
      onConfirm: () =>
        this.run(
          this.api.deactivate(plugin.id, version.version),
          `Deactivated ${plugin.id} ${version.version}`,
        ),
    });
  }

  protected reload(plugin: PluginResponse, version: PluginVersionResponse): void {
    this.run(this.api.reload(plugin.id, version.version), `Reloaded ${plugin.id} ${version.version}`);
  }

  /** Opens the inline editor, seeded with the version's current permissions. */
  protected editPermissions(plugin: PluginResponse, version: PluginVersionResponse): void {
    const key = `${plugin.id}:${version.version}`;
    if (this.editKey() === key) {
      this.editKey.set(null);
      return;
    }
    this.editHosts.set(version.allowedHosts.join(', '));
    this.editScopes.set(version.secretScopes.join(', '));
    this.editEvents.set(version.eventsEnabled);
    this.editKey.set(key);
  }

  protected savePermissions(plugin: PluginResponse, version: PluginVersionResponse): void {
    const permissions = {
      allowedHosts: this.splitList(this.editHosts()),
      secretScopes: this.splitList(this.editScopes()),
      eventsEnabled: this.editEvents(),
    };
    this.editKey.set(null);
    this.run(
      this.api.updatePermissions(plugin.id, version.version, permissions),
      version.loaded
        ? `Updated and reloaded ${plugin.id} ${version.version}`
        : `Updated ${plugin.id} ${version.version}`,
    );
  }

  /** Splits a comma or newline separated list, trimming and dropping blanks. */
  private splitList(text: string): string[] {
    return text
      .split(/[\n,]/)
      .map((entry) => entry.trim())
      .filter((entry) => entry.length > 0);
  }

  protected makeDefault(plugin: PluginResponse, version: PluginVersionResponse): void {
    this.run(
      this.api.setDefaultVersion(plugin.id, version.version),
      `${plugin.id} now defaults to ${version.version}`,
    );
  }

  protected removeVersion(plugin: PluginResponse, version: PluginVersionResponse): void {
    this.pendingConfirm.set({
      heading: 'Delete plugin version?',
      message:
        `Delete ${plugin.id} ${version.version}?\n\n` +
        'It is unloaded and its JAR is removed from GridFS. Workflows pinned to this version will ' +
        'fail validation until they are repointed.',
      confirmLabel: 'Delete',
      danger: true,
      onConfirm: () =>
        this.run(this.api.delete(plugin.id, version.version), `Deleted ${plugin.id} ${version.version}`),
    });
  }

  protected removePlugin(plugin: PluginResponse): void {
    this.pendingConfirm.set({
      heading: 'Delete plugin?',
      message: `Delete every version of ${plugin.id}? This cannot be undone.`,
      confirmLabel: 'Delete',
      danger: true,
      onConfirm: () => this.run(this.api.delete(plugin.id), `Deleted ${plugin.id}`),
    });
  }

  /** Runs the pending confirmed action, then clears the dialog. */
  protected runConfirmed(): void {
    const request = this.pendingConfirm();
    this.pendingConfirm.set(null);
    request?.onConfirm();
  }

  protected toggleHistory(pluginId: string, version: string): void {
    const key = `${pluginId}:${version}`;
    if (this.historyKey() === key) {
      this.historyKey.set(null);
      return;
    }
    this.historyKey.set(key);
    this.history.set([]);
    this.api.executions(pluginId, { version, size: 10 }).subscribe({
      next: (page) => this.history.set(page.content),
    });
  }

  /** Every lifecycle action refreshes both the plugin list and the node catalogue. */
  private run(call: import('rxjs').Observable<unknown>, message: string): void {
    this.busy.set(true);
    call.subscribe({
      next: () => {
        this.busy.set(false);
        this.notifications.success(message);
        this.load();
        this.catalog.invalidate();
      },
      error: () => this.busy.set(false),
    });
  }
}
