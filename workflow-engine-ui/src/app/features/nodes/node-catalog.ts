import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NodeApiService } from '../../core/api/node-api.service';
import { NodeCatalogEntry } from '../../core/models/node.models';
import { PrettyJsonPipe } from '../../shared/pipes/format.pipes';
import { EmptyState } from '../../shared/ui/empty-state';
import { NodeGlyph } from '../../shared/ui/node-glyph';

/**
 * A browser over everything this engine can currently execute.
 *
 * Useful in its own right, and it is also the clearest demonstration of the platform's central claim:
 * the four built-in types and every plugin-contributed type arrive from one endpoint in one shape, so
 * this screen never changes when an integration is added.
 */
import { Icon } from '../../shared/ui/icon';
@Component({
  selector: 'wf-node-catalog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, RouterLink, NodeGlyph, EmptyState, PrettyJsonPipe],
  template: `
    <div class="page">
      <div class="page-header">
        <div class="page-header__text">
          <h1>Node types</h1>
          <p>
            What this engine can execute right now. Built-in types are always present; the rest come
            from loaded plugins and disappear from this list when their plugin is deactivated.
          </p>
        </div>
        <div class="toolbar">
          <span class="tag">{{ catalog.builtInEntries().length }} built-in</span>
          <span class="tag">{{ catalog.pluginEntries().length }} from plugins</span>
          <button class="btn btn--sm" type="button" (click)="catalog.refresh()"><wf-icon name="refresh" /><span>Refresh</span></button>
        </div>
      </div>

      <div class="card">
        <div class="card__header">
          <input
            type="search"
            style="max-width: 280px"
            placeholder="Search node types"
            aria-label="Search node types"
            [value]="query()"
            (input)="query.set($any($event.target).value)"
          />
          <div class="btn-group">
            <button
              class="btn btn--sm"
              type="button"
              [class.btn--primary]="sourceFilter() === null"
              (click)="sourceFilter.set(null)"
            >
              All
            </button>
            <button
              class="btn btn--sm"
              type="button"
              [class.btn--primary]="sourceFilter() === 'BUILT_IN'"
              (click)="sourceFilter.set('BUILT_IN')"
            >
              Built-in
            </button>
            <button
              class="btn btn--sm"
              type="button"
              [class.btn--primary]="sourceFilter() === 'PLUGIN'"
              (click)="sourceFilter.set('PLUGIN')"
            >
              Plugin
            </button>
          </div>
        </div>

        @if (filtered().length === 0) {
          <wf-empty-state
            heading="Nothing to show"
            message="Install a plugin to add node types, or clear the filter."
          >
            <a class="btn" routerLink="/plugins">Go to plugins</a>
          </wf-empty-state>
        } @else {
          <div class="nodes">
            @for (entry of filtered(); track entry.nodeType) {
              <article class="node-card">
                <header>
                  <wf-node-glyph [icon]="entry.icon" [size]="18" />
                  <div class="node-card__title">
                    <strong>{{ entry.displayName }}</strong>
                    <span class="mono small muted">{{ entry.nodeType }}</span>
                  </div>
                  <span class="tag">{{ entry.category }}</span>
                </header>

                <p class="node-card__description">{{ entry.description || 'No description.' }}</p>

                <dl class="node-card__facts">
                  <div>
                    <dt>Source</dt>
                    <dd>
                      @if (entry.source === 'PLUGIN') {
                        <span class="mono">{{ entry.pluginId }} {{ entry.pluginVersion }}</span>
                      } @else {
                        built into the engine
                      }
                    </dd>
                  </div>
                  <div>
                    <dt>Repeatable</dt>
                    <dd>
                      @if (entry.idempotent) {
                        yes, safe to retry
                      } @else {
                        no, the engine guards retries
                      }
                    </dd>
                  </div>
                  @if (entry.outputVariables.length > 0) {
                    <div>
                      <dt>Outputs</dt>
                      <dd>
                        @for (name of entry.outputVariables; track name) {
                          <span class="tag tag--mono">{{ name }}</span>
                        }
                      </dd>
                    </div>
                  }
                  @if (entry.outputPorts.length > 0) {
                    <div>
                      <dt>Branches</dt>
                      <dd>
                        @for (port of entry.outputPorts; track port) {
                          <span class="tag tag--mono">{{ port }}</span>
                        }
                      </dd>
                    </div>
                  }
                </dl>

                @if (requiredOf(entry).length > 0) {
                  <p class="small">
                    <span class="muted">Required configuration</span>
                    @for (name of requiredOf(entry); track name) {
                      <span class="tag tag--mono">{{ name }}</span>
                    }
                  </p>
                }

                <details>
                  <summary class="small muted">Configuration schema</summary>
                  <pre class="code">{{ entry.configurationSchema | prettyJson }}</pre>
                </details>
              </article>
            }
          </div>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .nodes {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
        gap: var(--space-4);
        padding: var(--space-4);
      }

      .node-card {
        border: 1px solid var(--border);
        border-radius: var(--radius);
        padding: var(--space-3);
        background: var(--surface);
      }

      .node-card header {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        margin-bottom: var(--space-2);
      }

      .node-card__title {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-direction: column;
      }

      .node-card__description {
        margin: 0 0 var(--space-3);
        color: var(--text-muted);
        font-size: var(--text-base);
      }

      .node-card__facts {
        margin: 0 0 var(--space-3);
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
      }

      .node-card__facts dt {
        font-size: var(--text-xs);
        text-transform: uppercase;
        letter-spacing: 0.5px;
        color: var(--text-muted);
      }

      .node-card__facts dd {
        margin: 2px 0 0;
        font-size: var(--text-base);
      }

      .code {
        font-family: var(--font-mono);
        font-size: var(--text-xs);
        background: var(--hl-grey-100);
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        padding: var(--space-2);
        margin: var(--space-2) 0 0;
        max-height: 260px;
        overflow: auto;
      }
    `,
  ],
})
export class NodeCatalog {
  protected readonly catalog = inject(NodeApiService);

  protected readonly query = signal('');
  protected readonly sourceFilter = signal<'BUILT_IN' | 'PLUGIN' | null>(null);

  protected readonly filtered = computed(() => {
    const term = this.query().trim().toLowerCase();
    const source = this.sourceFilter();
    return this.catalog.entries().filter((entry) => {
      if (source && entry.source !== source) {
        return false;
      }
      if (!term) {
        return true;
      }
      return [entry.displayName, entry.nodeType, entry.category, entry.description, entry.pluginId ?? '']
        .join(' ')
        .toLowerCase()
        .includes(term);
    });
  });

  constructor() {
    this.catalog.ensureLoaded();
  }

  protected requiredOf(entry: NodeCatalogEntry): string[] {
    const required = entry.configurationSchema?.required;
    return Array.isArray(required) ? required : [];
  }
}
