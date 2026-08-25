import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { PluginApiService } from '../../core/plugin-api.service';
import { AuthService } from '../../core/auth/auth.service';
import {
  PLUGIN_STATUSES,
  PLUGIN_TYPES,
  Plugin,
  PluginStatus,
  displayNameOf,
  summarise,
} from '../../core/models/plugin.model';
import { AgoPipe } from '../../shared/format.pipes';
import { EmptyState } from '../../shared/empty-state';
import { PluginStatusBadge } from '../../shared/plugin-status-badge';

/**
 * Every plugin the registry holds.
 *
 * <h2>Where the filtering happens, and why it differs</h2>
 *
 * The search term goes to the registry, because it matches id, name and vendor across the whole collection and
 * the collection is paged: filtering a page in the browser would search whatever happened to be on screen.
 * Status and type are applied here, because the list endpoint takes no such parameter and a round trip to
 * narrow forty rows already in hand would be slower and no more correct.
 *
 * <h2>Debounced, not throttled</h2>
 *
 * A search fires once the operator stops typing. Throttling would send "s", "se", "sen" and "send" and race
 * four responses whose order is not guaranteed; `switchMap` also cancels a superseded request so a slow early
 * answer cannot overwrite a fast later one.
 */
@Component({
  selector: 'ps-plugin-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, PluginStatusBadge, EmptyState, AgoPipe],
  template: `
    <div class="page">
      <header class="page-header">
        <div class="page-header__text">
          <h1>Plugin Registry</h1>
          <p>
            Everything published to this registry. A workflow engine reads its catalogue from here and installs
            on request; nothing is executed by this console or by the registry itself.
          </p>
        </div>
        @if (auth.hasPermission('PLUGIN_UPLOAD')) {
          <div class="toolbar">
            <a class="btn btn--primary" routerLink="/plugins/upload">Upload plugin</a>
          </div>
        }
      </header>

      <section class="tiles" aria-label="Registry summary">
        <div class="tile">
          <span class="tile__label">Total plugins</span>
          <strong class="tile__value">{{ summary().totalPlugins }}</strong>
        </div>
        <div class="tile">
          <span class="tile__label">Active</span>
          <strong class="tile__value">{{ summary().active }}</strong>
        </div>
        <div class="tile">
          <span class="tile__label">Versions</span>
          <strong class="tile__value">{{ summary().versions }}</strong>
        </div>
        <div class="tile">
          <span class="tile__label">Deprecated</span>
          <strong class="tile__value tile__value--muted">{{ summary().deprecated }}</strong>
        </div>
      </section>

      <div class="controls">
        <input
          type="search"
          class="controls__search"
          placeholder="Search by name, id or vendor"
          aria-label="Search plugins"
          [value]="search()"
          (input)="onSearch($any($event.target).value)"
        />

        <label class="controls__filter">
          <span class="sr-only">Filter by status</span>
          <select [value]="status()" (change)="status.set($any($event.target).value)">
            <option value="ALL">All statuses</option>
            @for (option of statuses; track option) {
              <option [value]="option">{{ option }}</option>
            }
          </select>
        </label>

        <label class="controls__filter">
          <span class="sr-only">Filter by plugin type</span>
          <select [value]="type()" (change)="type.set($any($event.target).value)">
            <option value="ALL">All types</option>
            @for (option of types; track option) {
              <option [value]="option">{{ option }}</option>
            }
          </select>
        </label>

        <span class="spacer"></span>
        <span class="small muted" aria-live="polite">
          {{ visible().length }} of {{ plugins().length }} shown
        </span>
      </div>

      @if (loading()) {
        <div class="card skeletons" aria-busy="true" aria-label="Loading plugins">
          @for (row of [1, 2, 3, 4]; track row) {
            <div class="skeleton"></div>
          }
        </div>
      } @else if (plugins().length === 0) {
        <div class="card">
          <ps-empty-state
            heading="No plugins registered"
            message="Nothing has been published to this registry yet. Upload a plugin JAR to make it available to every workflow engine that reads this catalogue."
          >
            @if (auth.hasPermission('PLUGIN_UPLOAD')) {
              <a class="btn btn--primary" routerLink="/plugins/upload">Upload plugin</a>
            }
          </ps-empty-state>
        </div>
      } @else if (visible().length === 0) {
        <div class="card">
          <ps-empty-state
            heading="Nothing matches those filters"
            message="Try a different search term, or widen the status and type filters."
          >
            <button class="btn" type="button" (click)="clearFilters()">Clear filters</button>
          </ps-empty-state>
        </div>
      } @else {
        <div class="card table-card">
          <table class="table plugins">
            <thead>
              <tr>
                <th scope="col">Plugin</th>
                <th scope="col">Type</th>
                <th scope="col">Latest version</th>
                <th scope="col">Versions</th>
                <th scope="col">Status</th>
                <th scope="col">Updated</th>
                <th scope="col" class="cell-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              @for (plugin of visible(); track plugin.pluginId) {
                <tr>
                  <td data-label="Plugin">
                    <a class="plugin__name" [routerLink]="['/plugins', plugin.pluginId]">
                      {{ name(plugin) }}
                    </a>
                    <div class="mono small muted">{{ plugin.pluginId }}</div>
                    @if (plugin.vendor) {
                      <div class="small faint">{{ plugin.vendor }}</div>
                    }
                  </td>
                  <td data-label="Type">
                    @if (plugin.pluginType) {
                      <span class="tag">{{ plugin.pluginType }}</span>
                    } @else {
                      <span class="faint small">—</span>
                    }
                  </td>
                  <td data-label="Latest version" class="mono">
                    {{ plugin.latestVersion || '—' }}
                  </td>
                  <td data-label="Versions">{{ plugin.versionCount }}</td>
                  <td data-label="Status"><ps-plugin-status-badge [status]="plugin.status" /></td>
                  <td data-label="Updated" class="small muted">{{ plugin.updatedAt | ago }}</td>
                  <td class="cell-actions">
                    <a class="btn btn--sm" [routerLink]="['/plugins', plugin.pluginId]">View</a>
                    <a
                      class="btn btn--sm"
                      [routerLink]="['/plugins', plugin.pluginId, 'versions']"
                    >
                      Versions
                    </a>
                    @if (auth.hasPermission('PLUGIN_UPLOAD')) {
                      <a
                        class="btn btn--sm"
                        [routerLink]="['/plugins', plugin.pluginId, 'upload-version']"
                      >
                        Upload version
                      </a>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .tiles {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
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
        font-size: 26px;
        line-height: 1.1;
      }

      .tile__value--muted {
        color: var(--hl-orange-alt);
      }

      .controls {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        margin-bottom: var(--space-3);
        flex-wrap: wrap;
      }

      .controls__search {
        min-width: 240px;
        flex: 1 1 240px;
        max-width: 360px;
      }

      .plugin__name {
        font-weight: bold;
        color: var(--text);
        text-decoration: none;
      }

      .plugin__name:hover {
        text-decoration: underline;
      }

      .table-card {
        overflow-x: auto;
      }

      .skeletons {
        display: flex;
        flex-direction: column;
        gap: var(--space-3);
        padding: var(--space-4);
      }

      .skeleton {
        height: 42px;
        border-radius: var(--radius-sm);
        background: linear-gradient(
          90deg,
          var(--hl-grey-100) 25%,
          var(--hl-grey-200) 37%,
          var(--hl-grey-100) 63%
        );
        background-size: 400% 100%;
        animation: shimmer 1.3s ease-in-out infinite;
      }

      @keyframes shimmer {
        0% {
          background-position: 100% 50%;
        }
        100% {
          background-position: 0 50%;
        }
      }

      @media (prefers-reduced-motion: reduce) {
        .skeleton {
          animation: none;
        }
      }

      /* A seven-column table on a phone is unreadable, so each row becomes a card and every cell carries
         its own label. */
      @media (max-width: 860px) {
        .plugins thead {
          display: none;
        }

        .plugins,
        .plugins tbody,
        .plugins tr,
        .plugins td {
          display: block;
          width: 100%;
        }

        .plugins tr {
          border: 1px solid var(--border);
          border-radius: var(--radius);
          margin-bottom: var(--space-3);
          padding: var(--space-2);
        }

        .plugins td {
          display: flex;
          justify-content: space-between;
          gap: var(--space-3);
          border: none;
          padding: var(--space-2);
        }

        .plugins td::before {
          content: attr(data-label);
          font-size: var(--text-xs);
          text-transform: uppercase;
          letter-spacing: 0.5px;
          color: var(--text-muted);
        }

        .plugins td.cell-actions {
          justify-content: flex-start;
          flex-wrap: wrap;
        }

        .plugins td.cell-actions::before {
          content: none;
        }
      }
    `,
  ],
})
export class PluginList {
  protected readonly auth = inject(AuthService);

  private readonly api = inject(PluginApiService);
  private readonly searches = new Subject<string>();

  protected readonly statuses = PLUGIN_STATUSES;
  protected readonly types = PLUGIN_TYPES;

  protected readonly plugins = signal<Plugin[]>([]);
  protected readonly loading = signal(true);
  protected readonly search = signal('');
  protected readonly status = signal<PluginStatus | 'ALL'>('ALL');
  protected readonly type = signal<string>('ALL');

  protected readonly summary = computed(() => summarise(this.plugins()));

  protected readonly visible = computed(() =>
    this.plugins()
      .filter((plugin) => this.status() === 'ALL' || plugin.status === this.status())
      .filter((plugin) => this.type() === 'ALL' || plugin.pluginType === this.type()),
  );

  constructor() {
    this.searches
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        // switchMap, so a slow answer to "sen" cannot land after the answer to "send" and overwrite it.
        switchMap((term) => {
          this.loading.set(true);
          return this.api.getPlugins({ search: term });
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (page) => {
          this.plugins.set(page.content ?? []);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });

    this.searches.next('');
  }

  protected name(plugin: Plugin): string {
    return displayNameOf(plugin);
  }

  protected onSearch(term: string): void {
    this.search.set(term);
    this.searches.next(term);
  }

  protected clearFilters(): void {
    this.status.set('ALL');
    this.type.set('ALL');
    this.search.set('');
    this.searches.next('');
  }
}
