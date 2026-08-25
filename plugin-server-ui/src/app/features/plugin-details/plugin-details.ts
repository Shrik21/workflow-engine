import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PluginApiService } from '../../core/plugin-api.service';
import { AuthService } from '../../core/auth/auth.service';
import { NotificationService } from '../../core/notification.service';
import {
  Plugin,
  PluginAuditEvent,
  PluginNode,
  PluginVersionDetail,
  PluginVersionSummary,
  displayNameOf,
} from '../../core/models/plugin.model';
import { AgoPipe, BytesPipe, PrettyJsonPipe } from '../../shared/format.pipes';
import { EmptyState } from '../../shared/empty-state';
import { Modal } from '../../shared/modal';
import { PluginStatusBadge } from '../../shared/plugin-status-badge';

type Tab = 'overview' | 'versions' | 'nodes' | 'compatibility' | 'usage' | 'audit';

/** A pending confirmation. Held as data so the dialog is declarative rather than imperative. */
interface Confirmation {
  title: string;
  body: string;
  consequence: string;
  confirmLabel: string;
  destructive: boolean;
  run: () => void;
}

/**
 * One plugin, in full.
 *
 * <h2>Tabs over one long page</h2>
 *
 * Six different questions get asked of a plugin — what is it, which versions exist, what does it contribute,
 * will it run here, who uses it, what happened to it — and they are asked at different times by different
 * people. A single scrolling page makes every one of them a hunt.
 *
 * <h2>The Usage tab tells the truth</h2>
 *
 * It has no data, and it explains why rather than showing an invented zero. The registry stores plugins; it
 * has never heard of a workflow. Which workflows use a plugin is knowable only to a workflow engine, and this
 * console speaks only to the registry. A tab that quietly displayed "0 workflows" would be worse than one that
 * says where the answer actually lives.
 */
@Component({
  selector: 'ps-plugin-details',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    PluginStatusBadge,
    EmptyState,
    Modal,
    AgoPipe,
    BytesPipe,
    PrettyJsonPipe,
  ],
  template: `
    <div class="page">
      @if (loading()) {
        <div class="card skeletons" aria-busy="true" aria-label="Loading plugin">
          <div class="skeleton skeleton--title"></div>
          <div class="skeleton"></div>
          <div class="skeleton"></div>
        </div>
      } @else if (plugin(); as detail) {
        <header class="page-header">
          <div class="page-header__text">
            <a class="back" routerLink="/plugins">← All plugins</a>
            <h1>{{ name(detail) }}</h1>
            <div class="identity">
              <span class="tag tag--mono">{{ detail.pluginId }}</span>
              <ps-plugin-status-badge [status]="detail.status" />
              @if (detail.pluginType) {
                <span class="tag">{{ detail.pluginType }}</span>
              }
              @if (detail.vendor) {
                <span class="small muted">by {{ detail.vendor }}</span>
              }
            </div>
            @if (detail.description) {
              <p>{{ detail.description }}</p>
            }
          </div>

          @if (auth.hasAnyPermission('PLUGIN_UPLOAD', 'PLUGIN_ACTIVATE', 'PLUGIN_DEACTIVATE')) {
            <div class="toolbar">
              <a class="btn btn--primary btn--sm" [routerLink]="['/plugins', detail.pluginId, 'upload-version']">
                Upload new version
              </a>
              @if (detail.status === 'ACTIVE') {
                <button class="btn btn--danger btn--sm" type="button" (click)="askDeactivate(detail)">
                  Deactivate
                </button>
              } @else {
                <button class="btn btn--accent btn--sm" type="button" (click)="activate(detail)">
                  Activate
                </button>
              }
            </div>
          }
        </header>

        <section class="tiles" aria-label="Plugin statistics">
          <div class="tile">
            <span class="tile__label">Versions</span>
            <strong class="tile__value">{{ versions().length || detail.versionCount }}</strong>
          </div>
          <div class="tile">
            <span class="tile__label">Latest version</span>
            <strong class="tile__value mono">{{ detail.latestVersion || '—' }}</strong>
          </div>
          <div class="tile">
            <span class="tile__label">Node types</span>
            <strong class="tile__value">{{ nodes().length }}</strong>
          </div>
          <div class="tile">
            <span class="tile__label">Last updated</span>
            <strong class="tile__value tile__value--small">{{ detail.updatedAt | ago }}</strong>
          </div>
        </section>

        <div class="tabs" role="tablist" aria-label="Plugin sections">
          @for (option of tabs; track option.key) {
            <button
              class="tab"
              type="button"
              role="tab"
              [class.tab--active]="tab() === option.key"
              [attr.aria-selected]="tab() === option.key"
              (click)="tab.set(option.key)"
            >
              {{ option.label }}
            </button>
          }
        </div>

        <div class="card card--pad" role="tabpanel">
          @switch (tab()) {
            @case ('overview') {
              <dl class="facts">
                <div><dt>Plugin ID</dt><dd class="mono">{{ detail.pluginId }}</dd></div>
                <div><dt>Vendor</dt><dd>{{ detail.vendor || '—' }}</dd></div>
                <div><dt>Type</dt><dd>{{ detail.pluginType || '—' }}</dd></div>
                <div><dt>Latest version</dt><dd class="mono">{{ detail.latestVersion || '—' }}</dd></div>
                <div><dt>Registered</dt><dd>{{ detail.createdAt | ago }}</dd></div>
                <div><dt>Updated</dt><dd>{{ detail.updatedAt | ago }}</dd></div>
              </dl>
              <p class="small muted">
                A plugin's status governs the whole plugin. Individual versions carry their own status, which
                is what a workflow engine reads when deciding whether a version may be installed.
              </p>
            }

            @case ('versions') {
              @if (versions().length === 0) {
                <ps-empty-state
                  heading="No versions"
                  message="Nothing has been uploaded under this plugin yet."
                />
              } @else {
                <table class="table">
                  <thead>
                    <tr>
                      <th scope="col">Version</th>
                      <th scope="col">Status</th>
                      <th scope="col">SDK</th>
                      <th scope="col">Size</th>
                      <th scope="col">Uploaded</th>
                      <th scope="col" class="cell-actions">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (version of versions(); track version.version) {
                      <tr>
                        <td data-label="Version">
                          <a
                            class="mono"
                            [routerLink]="['/plugins', detail.pluginId, 'versions', version.version]"
                          >
                            {{ version.version }}
                          </a>
                          @if (version.version === detail.latestVersion) {
                            <span class="tag">latest</span>
                          }
                        </td>
                        <td data-label="Status"><ps-plugin-status-badge [status]="version.status" /></td>
                        <td data-label="SDK" class="mono small">{{ version.sdkVersion || '—' }}</td>
                        <td data-label="Size" class="small muted">{{ version.fileSize | bytes }}</td>
                        <td data-label="Uploaded" class="small muted">
                          {{ version.uploadedAt | ago }}
                          @if (version.uploadedBy) {
                            <div class="faint">by {{ version.uploadedBy }}</div>
                          }
                        </td>
                        <td class="cell-actions">
                          <a
                            class="btn btn--sm"
                            [routerLink]="['/plugins', detail.pluginId, 'versions', version.version]"
                          >
                            View
                          </a>
                          @if (auth.hasAnyPermission('PLUGIN_ACTIVATE', 'PLUGIN_DEPRECATE')) {
                            @if (version.status === 'DRAFT') {
                              <button class="btn btn--accent btn--sm" type="button" (click)="publish(detail, version)">
                                Publish
                              </button>
                            }
                            @if (version.status === 'ACTIVE') {
                              <button class="btn btn--sm" type="button" (click)="askDeprecate(detail, version)">
                                Deprecate
                              </button>
                            }
                            @if (version.status === 'ACTIVE' || version.status === 'DEPRECATED') {
                              <button class="btn btn--danger btn--sm" type="button" (click)="askRevoke(detail, version)">
                                Revoke
                              </button>
                            }
                          }
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              }
            }

            @case ('nodes') {
              @if (nodes().length === 0) {
                <ps-empty-state
                  heading="No node types"
                  message="The latest version declares no nodes. That is valid for a utility or trigger plugin."
                />
              } @else {
                <div class="nodes">
                  @for (node of nodes(); track node.nodeType) {
                    <article class="node">
                      <h3>{{ node.displayName || node.nodeType }}</h3>
                      <div class="mono small muted">{{ node.nodeType }}</div>
                      @if (node.category) {
                        <span class="tag">{{ node.category }}</span>
                      }
                      @if (node.description) {
                        <p class="small">{{ node.description }}</p>
                      }
                      <details>
                        <summary class="small muted">Configuration schema</summary>
                        <pre class="code">{{ node.configurationSchema | prettyJson }}</pre>
                      </details>
                    </article>
                  }
                </div>
              }
            }

            @case ('compatibility') {
              @if (latest(); as version) {
                <ul class="checks">
                  <li>
                    <span class="checks__mark" aria-hidden="true">✓</span>
                    <span><strong>Java</strong> — declares {{ version.javaVersion || 'no requirement' }}</span>
                  </li>
                  <li>
                    <span class="checks__mark" aria-hidden="true">✓</span>
                    <span><strong>Workflow SDK</strong> — built against {{ version.sdkVersion || 'an undeclared version' }}</span>
                  </li>
                  <li>
                    <span class="checks__mark" aria-hidden="true">✓</span>
                    <span>
                      <strong>Engine range</strong> —
                      {{ version.engineCompatibility || 'unconstrained' }}
                    </span>
                  </li>
                </ul>
                <div class="notice">
                  These are the archive's own declarations, recorded at upload. Whether a
                  <em>particular</em> engine can run it is decided by that engine when it installs: it compares
                  these against its own SDK line, Java version and engine version, and refuses what it cannot
                  load. A registry serves many engines and cannot answer for any one of them.
                </div>
              } @else {
                <ps-empty-state heading="No published version" message="Upload and publish a version to see what it requires." />
              }
            }

            @case ('usage') {
              <ps-empty-state
                heading="Usage lives in the workflow engine"
                message="This registry stores and serves plugins; it has no knowledge of workflows. Which workflows use a plugin, and which versions they pin, is known only to an engine that has installed it — ask there."
              />
              <p class="small muted">
                An engine refuses to uninstall a version a published workflow still pins, and names those
                workflows when it does. That refusal is the authoritative answer to this question.
              </p>
            }

            @case ('audit') {
              @if (audit().length === 0) {
                <ps-empty-state heading="No audit entries" message="Nothing has been recorded against this plugin yet." />
              } @else {
                <table class="table">
                  <thead>
                    <tr>
                      <th scope="col">When</th>
                      <th scope="col">Action</th>
                      <th scope="col">Version</th>
                      <th scope="col">Actor</th>
                      <th scope="col">Outcome</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (entry of audit(); track entry.id) {
                      <tr>
                        <td data-label="When" class="small muted">{{ entry.at | ago }}</td>
                        <td data-label="Action"><strong>{{ entry.action }}</strong></td>
                        <td data-label="Version" class="mono small">{{ entry.version || '—' }}</td>
                        <td data-label="Actor" class="small">{{ entry.actor || '—' }}</td>
                        <td data-label="Outcome" class="small">{{ entry.outcome || '—' }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              }
            }
          }
        </div>
      } @else {
        <div class="card">
          <ps-empty-state
            heading="No such plugin"
            message="This registry holds no plugin with that id. It may have been deleted."
          >
            <a class="btn" routerLink="/plugins">Back to plugins</a>
          </ps-empty-state>
        </div>
      }
    </div>

    @if (confirmation(); as pending) {
      <ps-modal
        [heading]="pending.title"
        [subheading]="pending.body"
        [dismissable]="false"
        (closed)="confirmation.set(null)"
      >
        <p>{{ pending.consequence }}</p>
        <div modalFooter>
          <button class="btn" type="button" (click)="confirmation.set(null)">Cancel</button>
          <button
            class="btn"
            [class.btn--danger]="pending.destructive"
            [class.btn--primary]="!pending.destructive"
            type="button"
            (click)="pending.run()"
          >
            {{ pending.confirmLabel }}
          </button>
        </div>
      </ps-modal>
    }
  `,
  styles: [
    `
      .back {
        display: inline-block;
        margin-bottom: var(--space-2);
        color: var(--hl-accent-blue-alt);
        text-decoration: none;
        font-size: var(--text-sm);
      }

      .identity {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        margin: var(--space-2) 0;
        flex-wrap: wrap;
      }

      .tiles {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
        gap: var(--space-3);
        margin-bottom: var(--space-4);
      }

      .tile {
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: var(--radius);
        padding: var(--space-3) var(--space-4);
        display: flex;
        flex-direction: column;
        gap: 2px;
      }

      .tile__label {
        font-size: var(--text-xs);
        text-transform: uppercase;
        letter-spacing: 0.6px;
        color: var(--text-muted);
      }

      .tile__value {
        font-size: 24px;
        line-height: 1.15;
      }

      .tile__value--small {
        font-size: var(--text-base);
        font-weight: normal;
      }

      .tabs {
        display: flex;
        gap: 2px;
        border-bottom: 1px solid var(--border);
        margin-bottom: var(--space-4);
        overflow-x: auto;
      }

      .tab {
        border: none;
        background: none;
        padding: var(--space-2) var(--space-3);
        border-bottom: 2px solid transparent;
        color: var(--text-muted);
        cursor: pointer;
        white-space: nowrap;
        font-size: var(--text-base);
      }

      .tab:hover {
        color: var(--text);
      }

      .tab--active {
        color: var(--hl-accent-blue-alt);
        border-bottom-color: var(--hl-accent-blue-alt);
        font-weight: bold;
      }

      .card--pad {
        padding: var(--space-4);
      }

      .facts {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
        gap: var(--space-3);
        margin: 0 0 var(--space-3);
      }

      .facts div {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }

      .facts dt {
        font-size: var(--text-xs);
        text-transform: uppercase;
        letter-spacing: 0.6px;
        color: var(--text-muted);
      }

      .facts dd {
        margin: 0;
      }

      .nodes {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
        gap: var(--space-3);
      }

      .node {
        border: 1px solid var(--border);
        border-radius: var(--radius);
        padding: var(--space-3);
      }

      .node h3 {
        margin: 0;
      }

      .code {
        font-family: var(--font-mono);
        font-size: var(--text-xs);
        background: var(--surface-sunken);
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        padding: var(--space-2);
        max-height: 260px;
        overflow: auto;
      }

      .checks {
        list-style: none;
        padding: 0;
        margin: 0 0 var(--space-3);
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
      }

      .checks li {
        display: flex;
        align-items: baseline;
        gap: var(--space-2);
      }

      .checks__mark {
        color: var(--hl-green);
        font-weight: bold;
      }

      .skeletons {
        padding: var(--space-4);
        display: flex;
        flex-direction: column;
        gap: var(--space-3);
      }

      .skeleton {
        height: 38px;
        border-radius: var(--radius-sm);
        background: var(--hl-grey-100);
      }

      .skeleton--title {
        height: 54px;
        width: 40%;
      }

      @media (max-width: 860px) {
        .table thead {
          display: none;
        }

        .table,
        .table tbody,
        .table tr,
        .table td {
          display: block;
          width: 100%;
        }

        .table tr {
          border: 1px solid var(--border);
          border-radius: var(--radius);
          margin-bottom: var(--space-3);
          padding: var(--space-2);
        }

        .table td {
          display: flex;
          justify-content: space-between;
          gap: var(--space-3);
          border: none;
          padding: var(--space-2);
        }

        .table td::before {
          content: attr(data-label);
          font-size: var(--text-xs);
          text-transform: uppercase;
          color: var(--text-muted);
        }

        .table td.cell-actions {
          flex-wrap: wrap;
          justify-content: flex-start;
        }

        .table td.cell-actions::before {
          content: none;
        }
      }
    `,
  ],
})
export class PluginDetails {
  readonly pluginId = input.required<string>();

  protected readonly auth = inject(AuthService);

  private readonly api = inject(PluginApiService);
  private readonly notifications = inject(NotificationService);

  protected readonly tabs: Array<{ key: Tab; label: string }> = [
    { key: 'overview', label: 'Overview' },
    { key: 'versions', label: 'Versions' },
    { key: 'nodes', label: 'Nodes' },
    { key: 'compatibility', label: 'Compatibility' },
    { key: 'usage', label: 'Usage' },
    { key: 'audit', label: 'Audit' },
  ];

  protected readonly plugin = signal<Plugin | null>(null);
  protected readonly versions = signal<PluginVersionSummary[]>([]);
  protected readonly latest = signal<PluginVersionDetail | null>(null);
  protected readonly audit = signal<PluginAuditEvent[]>([]);
  protected readonly loading = signal(true);
  protected readonly tab = signal<Tab>('overview');
  protected readonly confirmation = signal<Confirmation | null>(null);

  /** Node types come from the newest version fetched, which is what the plugin currently contributes. */
  protected readonly nodes = computed<PluginNode[]>(() => this.latest()?.nodes ?? []);

  constructor() {
    effect(() => {
      // Re-runs when the route parameter changes, so navigating between two plugins loads the second
      // rather than leaving the first on screen.
      const id = this.pluginId();
      this.load(id);
    });
  }

  protected name(plugin: Plugin): string {
    return displayNameOf(plugin);
  }

  private load(pluginId: string): void {
    this.loading.set(true);
    this.api.getPlugin(pluginId).subscribe({
      next: (plugin) => {
        this.plugin.set(plugin);
        this.loading.set(false);
      },
      error: () => {
        this.plugin.set(null);
        this.loading.set(false);
      },
    });

    this.api.getPluginVersions(pluginId).subscribe({
      next: (versions) => {
        this.versions.set(versions ?? []);
        const newest = versions?.[0];
        if (newest) {
          this.api.getPluginVersion(pluginId, newest.version).subscribe({
            next: (detail) => this.latest.set(detail),
            error: () => this.latest.set(null),
          });
        } else {
          this.latest.set(null);
        }
      },
      error: () => this.versions.set([]),
    });

    this.api.getPluginAudit(pluginId).subscribe({
      next: (entries) => this.audit.set(entries ?? []),
      error: () => this.audit.set([]),
    });
  }

  // ------------------------------------------------------------------ actions

  protected activate(plugin: Plugin): void {
    this.api.activatePlugin(plugin.pluginId).subscribe({
      next: () => {
        this.notifications.success(`${displayNameOf(plugin)} activated`);
        this.load(plugin.pluginId);
      },
    });
  }

  protected askDeactivate(plugin: Plugin): void {
    this.confirmation.set({
      title: `Deactivate ${displayNameOf(plugin)}?`,
      body: 'The plugin stops being offered to workflow engines.',
      consequence:
        'Engines that have already installed a version keep running it: deactivating here withdraws it ' +
        'from the catalogue, it does not reach into an engine and stop anything.',
      confirmLabel: 'Deactivate',
      destructive: true,
      run: () => {
        this.confirmation.set(null);
        this.api.deactivatePlugin(plugin.pluginId).subscribe({
          next: () => {
            this.notifications.success(`${displayNameOf(plugin)} deactivated`);
            this.load(plugin.pluginId);
          },
        });
      },
    });
  }

  protected publish(plugin: Plugin, version: PluginVersionSummary): void {
    this.api.publishVersion(plugin.pluginId, version.version).subscribe({
      next: () => {
        this.notifications.success(`${plugin.pluginId} ${version.version} published`);
        this.load(plugin.pluginId);
      },
    });
  }

  protected askDeprecate(plugin: Plugin, version: PluginVersionSummary): void {
    this.confirmation.set({
      title: `Deprecate version ${version.version}?`,
      body: 'It should no longer be chosen for new work.',
      consequence:
        'A deprecated version still downloads, so workflows pinned to it keep running. This marks it as ' +
        'superseded rather than removing it.',
      confirmLabel: 'Deprecate',
      destructive: false,
      run: () => {
        this.confirmation.set(null);
        this.api.deprecateVersion(plugin.pluginId, version.version).subscribe({
          next: () => {
            this.notifications.success(`${plugin.pluginId} ${version.version} deprecated`);
            this.load(plugin.pluginId);
          },
        });
      },
    });
  }

  protected askRevoke(plugin: Plugin, version: PluginVersionSummary): void {
    this.confirmation.set({
      title: `Revoke version ${version.version}?`,
      body: 'This says the version is unsafe to use.',
      consequence:
        'Downloads are refused from this point on, so no engine can install it again. Engines that already ' +
        'have it will report it as revoked and should remove it. Use deprecation instead if the version is ' +
        'merely superseded.',
      confirmLabel: 'Revoke',
      destructive: true,
      run: () => {
        this.confirmation.set(null);
        this.api
          .revokeVersion(plugin.pluginId, version.version, 'Revoked from the registry console')
          .subscribe({
            next: () => {
              this.notifications.warning(`${plugin.pluginId} ${version.version} revoked`);
              this.load(plugin.pluginId);
            },
          });
      },
    });
  }
}
