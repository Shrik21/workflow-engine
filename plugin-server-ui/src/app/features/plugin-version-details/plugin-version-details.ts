import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PluginApiService } from '../../core/plugin-api.service';
import { PluginVersionDetail } from '../../core/models/plugin.model';
import { AgoPipe, BytesPipe, PrettyJsonPipe } from '../../shared/format.pipes';
import { EmptyState } from '../../shared/empty-state';
import { PluginStatusBadge } from '../../shared/plugin-status-badge';

/**
 * One version of one plugin.
 *
 * <h2>The checksum is shown in full</h2>
 *
 * Everywhere else in this console a checksum is abbreviated, because nobody reads sixty-four hex characters in
 * a table. Here it is complete and selectable: this is the page somebody opens precisely to compare it against
 * what their build produced, and a truncated hash cannot be compared against anything.
 */
@Component({
  selector: 'ps-plugin-version-details',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, PluginStatusBadge, EmptyState, AgoPipe, BytesPipe, PrettyJsonPipe],
  template: `
    <div class="page">
      @if (record(); as detail) {
        <header class="page-header">
          <div class="page-header__text">
            <a class="back" [routerLink]="['/plugins', detail.pluginId]">← {{ detail.pluginId }}</a>
            <h1>{{ detail.name || detail.pluginId }} <span class="mono">{{ detail.version }}</span></h1>
            <div class="identity">
              <ps-plugin-status-badge [status]="detail.status" />
              @if (detail.signed) {
                <span class="tag" title="The archive carries a verified signature">signed</span>
              }
            </div>
            @if (detail.description) {
              <p>{{ detail.description }}</p>
            }
          </div>
        </header>

        @if (detail.status === 'REVOKED' && detail.revocationReason) {
          <div class="notice notice--error" role="alert">
            <strong>Revoked.</strong> {{ detail.revocationReason }}
          </div>
        }

        <div class="card card--pad">
          <dl class="facts">
            <div><dt>Plugin ID</dt><dd class="mono">{{ detail.pluginId }}</dd></div>
            <div><dt>Version</dt><dd class="mono">{{ detail.version }}</dd></div>
            <div><dt>Archive</dt><dd class="mono small">{{ detail.fileName || '—' }}</dd></div>
            <div><dt>Size</dt><dd>{{ detail.fileSize | bytes }}</dd></div>
            <div><dt>Java</dt><dd>{{ detail.javaVersion || '—' }}</dd></div>
            <div><dt>SDK</dt><dd>{{ detail.sdkVersion || '—' }}</dd></div>
            <div><dt>Engine range</dt><dd class="mono small">{{ detail.engineCompatibility || 'unconstrained' }}</dd></div>
            <div><dt>Main class</dt><dd class="mono small">{{ detail.mainClass || '—' }}</dd></div>
            <div><dt>Uploaded</dt><dd>{{ detail.uploadedAt | ago }}{{ detail.uploadedBy ? ' by ' + detail.uploadedBy : '' }}</dd></div>
            <div><dt>Published</dt><dd>{{ detail.publishedAt ? (detail.publishedAt | ago) : 'not published' }}</dd></div>
          </dl>

          <div class="checksum">
            <span class="fact__label">SHA-256</span>
            <code class="mono">{{ detail.checksum || 'not recorded' }}</code>
            <p class="small muted">
              An engine verifies the archive against this before anything loads it. Compare it against what
              your build produced to confirm the bytes here are the bytes you shipped.
            </p>
          </div>
        </div>

        @if (detail.nodes.length > 0) {
          <div class="card card--pad">
            <h2>Nodes</h2>
            <div class="nodes">
              @for (node of detail.nodes; track node.nodeType) {
                <article class="node">
                  <h3>{{ node.displayName || node.nodeType }}</h3>
                  <div class="mono small muted">{{ node.nodeType }}</div>
                  @if (node.category) {
                    <span class="tag">{{ node.category }}</span>
                  }
                  @if (node.description) {
                    <p class="small">{{ node.description }}</p>
                  }
                  @if (node.outputPorts.length > 0) {
                    <div class="small muted">Outputs: {{ node.outputPorts.join(', ') }}</div>
                  }
                  <details>
                    <summary class="small muted">Configuration schema</summary>
                    <pre class="code">{{ node.configurationSchema | prettyJson }}</pre>
                  </details>
                </article>
              }
            </div>
          </div>
        }

        @if (detail.dependencies.length > 0) {
          <div class="card card--pad">
            <h2>Declared dependencies</h2>
            <p class="small muted">
              What the archive says it bundles. Recorded for review; the registry does not resolve or fetch
              them, and each plugin runs in its own class loader, so two plugins may bundle different
              versions of the same library without conflict.
            </p>
            <table class="table">
              <thead>
                <tr>
                  <th scope="col">Group</th>
                  <th scope="col">Artifact</th>
                  <th scope="col">Version</th>
                  <th scope="col">Scope</th>
                </tr>
              </thead>
              <tbody>
                @for (dependency of detail.dependencies; track dependency.artifactId) {
                  <tr>
                    <td data-label="Group" class="mono small">{{ dependency.groupId }}</td>
                    <td data-label="Artifact" class="mono small">{{ dependency.artifactId }}</td>
                    <td data-label="Version" class="mono small">{{ dependency.version }}</td>
                    <td data-label="Scope" class="small">{{ dependency.scope || 'bundled' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      } @else if (!loading()) {
        <div class="card">
          <ps-empty-state
            heading="No such version"
            message="This registry holds no version with that number for this plugin."
          >
            <a class="btn" routerLink="/plugins">Back to plugins</a>
          </ps-empty-state>
        </div>
      }
    </div>
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
        gap: var(--space-2);
        align-items: center;
        margin: var(--space-2) 0;
      }

      .card {
        margin-bottom: var(--space-4);
      }

      .card--pad {
        padding: var(--space-4);
      }

      .facts {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
        gap: var(--space-3);
        margin: 0 0 var(--space-4);
      }

      .facts div {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }

      .facts dt,
      .fact__label {
        font-size: var(--text-xs);
        text-transform: uppercase;
        letter-spacing: 0.6px;
        color: var(--text-muted);
      }

      .facts dd {
        margin: 0;
      }

      .checksum {
        border-top: 1px solid var(--border);
        padding-top: var(--space-3);
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
      }

      .checksum code {
        word-break: break-all;
        background: var(--surface-sunken);
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        padding: var(--space-2);
        font-size: var(--text-sm);
      }

      .checksum p {
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
        max-height: 240px;
        overflow: auto;
      }
    `,
  ],
})
export class PluginVersionDetails {
  readonly pluginId = input.required<string>();
  readonly version = input.required<string>();

  private readonly api = inject(PluginApiService);

  protected readonly loading = signal(true);

  /** The loaded record. Named apart from the {@link version} route input, which is only its number. */
  protected readonly record = signal<PluginVersionDetail | null>(null);

  constructor() {
    effect(() => {
      const id = this.pluginId();
      const number = this.version();
      this.loading.set(true);
      this.api.getPluginVersion(id, number).subscribe({
        next: (detail) => {
          this.record.set(detail);
          this.loading.set(false);
        },
        error: () => {
          this.record.set(null);
          this.loading.set(false);
        },
      });
    });
  }

}
