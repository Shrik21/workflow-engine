import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { MarketplaceApiService } from '../../core/api/marketplace-api.service';
import { NodeCatalogEntry, categoryColorVar } from '../../core/models/node.models';
import { PluginIcon } from '../../shared/ui/plugin-icon';
import { PaletteItem, filterPaletteItems, toPaletteItems } from './plugin-operations';

/**
 * The node palette, built entirely from `GET /api/nodes`.
 *
 * Nothing here is hardcoded. The four built-in types and every plugin-contributed type arrive in the
 * same shape and are rendered the same way, which is what makes a plugin uploaded five minutes ago
 * usable without touching this application.
 *
 * Plugin entries are labelled with their plugin and version rather than being visually merged with the
 * built-ins. An author choosing a node should be able to see that it depends on something installed,
 * because that is what determines whether the workflow still publishes tomorrow.
 */
@Component({
  selector: 'wf-node-palette',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PluginIcon],
  template: `
    <div class="palette">
      <div class="palette__search">
        <input
          type="search"
          placeholder="Search node types"
          aria-label="Search node types"
          [value]="query()"
          (input)="query.set($any($event.target).value)"
        />
      </div>

      @if (loading()) {
        <p class="palette__status small muted">Loading node types…</p>
      } @else if (entries().length === 0) {
        <div class="palette__status">
          <p class="small muted">
            No node types were returned. Check that the engine is reachable.
          </p>
          <button class="btn btn--sm" type="button" (click)="refreshRequested.emit()">Retry</button>
        </div>
      } @else if (filtered().length === 0) {
        <p class="palette__status small muted">Nothing matches "{{ query() }}".</p>
      }

      @for (group of grouped(); track group.category) {
        <div class="group">
          <button
            class="group__title"
            type="button"
            [attr.aria-expanded]="!isCollapsed(group.category)"
            [title]="
              (isCollapsed(group.category) ? 'Show ' : 'Hide ') + group.category + ' nodes'
            "
            (click)="toggleCategory(group.category)"
          >
            <span class="group__chevron" [class.group__chevron--collapsed]="isCollapsed(group.category)"
              >▾</span
            >
            <span>{{ group.category }}</span>
            <span class="group__count">{{ group.items.length }}</span>
          </button>
          @if (!isCollapsed(group.category)) {
            <!--
              Tiles rather than rows, matching the canvas exactly: the thing you pick up looks like the thing
              you get. The meta line each row used to carry has no room here, so it moves to the tooltip —
              except the operation count, which stays visible as a corner badge because it is what tells an
              author this one tile is a whole integration rather than a single action.
            -->
            <div class="group__items">
              @for (item of group.items; track item.key) {
                <div
                  class="item"
                  draggable="true"
                  tabindex="0"
                  role="button"
                  [style.--item-color]="colorFor(item.entry)"
                  [attr.aria-label]="'Add ' + item.label + ' node'"
                  [title]="tooltipFor(item)"
                  (dragstart)="onDragStart($event, item.entry)"
                  (dblclick)="addRequested.emit(item.entry)"
                  (keydown.enter)="addRequested.emit(item.entry)"
                  (keydown.space)="addRequested.emit(item.entry)"
                >
                  <span class="item__tile">
                    <wf-plugin-icon class="item__icon" [pluginId]="item.entry.pluginId" [icon]="item.entry.icon" [size]="52" />
                    @if (item.operations.length > 1) {
                      <span class="item__ops" [title]="item.meta">{{ item.operations.length }}</span>
                    }
                    @if (noteFor(item.entry); as note) {
                      <span
                        class="item__flag"
                        [class]="'item__flag--' + note.kind"
                        [title]="note.title"
                        >{{ note.label }}</span
                      >
                    }
                  </span>
                  <span class="item__name">{{ item.label }}</span>
                </div>
              }
            </div>
          }
        </div>
      }

      <p class="palette__hint small faint">
        Drag onto the canvas, or double-click to drop one in the middle.
      </p>
    </div>
  `,
  styles: [
    `
      .palette {
        display: flex;
        flex-direction: column;
        height: 100%;
        overflow-y: auto;
        padding-bottom: var(--space-4);
      }

      .palette__search {
        position: sticky;
        top: 0;
        z-index: 1;
        padding: var(--space-3);
        background: var(--surface);
        border-bottom: 1px solid var(--border);
      }

      .palette__status {
        padding: var(--space-3);
        margin: 0;
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
        align-items: flex-start;
      }

      .group {
        padding: var(--space-3) var(--space-3) 0;
      }

      .group__title {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        width: 100%;
        padding: var(--space-1) 0;
        margin-bottom: var(--space-2);
        border: none;
        background: none;
        cursor: pointer;
        font-size: var(--text-xs);
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.7px;
        color: var(--text-muted);
      }

      .group__title:hover {
        color: var(--text);
      }

      .group__chevron {
        display: inline-block;
        transition: transform 0.12s ease;
        font-size: var(--text-sm);
      }

      .group__chevron--collapsed {
        transform: rotate(-90deg);
      }

      /* Pushes the count to the right end of the row. */
      .group__count {
        margin-left: auto;
        font-variant-numeric: tabular-nums;
        color: var(--text-muted);
        font-weight: 400;
      }

      /* auto-fill rather than a fixed count, so the palette reflows if the sidebar is ever resized. */
      .group__items {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(76px, 1fr));
        gap: var(--space-2);
        padding: var(--space-1) 0 var(--space-2);
      }

      .item {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 5px;
        padding: 2px;
        border-radius: var(--radius-sm);
        cursor: grab;
        user-select: none;
      }

      .item:focus-visible {
        outline: none;
        box-shadow: var(--focus-ring);
      }

      /* The square is the node as it will appear on the canvas, at a smaller size. */
      .item__tile {
        position: relative;
        display: flex;
        align-items: center;
        justify-content: center;
        width: 100%;
        aspect-ratio: 1;
        border: 1px solid var(--border);
        border-radius: 12px;
        background: var(--surface);
        transition:
          border-color 120ms ease,
          transform 120ms ease;
      }

      .item:hover .item__tile {
        border-color: var(--item-color);
        transform: translateY(-1px);
      }

      .item__icon {
        color: var(--item-color);
      }

      .item__name {
        width: 100%;
        font-size: 11px;
        line-height: 1.25;
        text-align: center;
        /* Two lines then ellipsis — "Create Firewall Rule" needs both and should not be cut to one. */
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
        line-clamp: 2;
        overflow: hidden;
        overflow-wrap: anywhere;
      }

      /* How many operations the integration offers, chosen in the property panel once dropped. */
      .item__ops {
        position: absolute;
        right: -4px;
        bottom: -4px;
        min-width: 16px;
        height: 16px;
        padding: 0 4px;
        border-radius: 999px;
        background: var(--hl-grey-200);
        border: 1px solid var(--surface);
        color: var(--text-muted);
        font-size: 9px;
        font-weight: 600;
        line-height: 14px;
        text-align: center;
      }

      /* A hint, not a barrier: every node here is loaded and usable, whatever the registry says of it. */
      .item__flag {
        position: absolute;
        top: -5px;
        left: -3px;
        padding: 0 5px;
        border-radius: 999px;
        font-size: 9px;
        text-transform: uppercase;
        letter-spacing: 0.4px;
        background: var(--surface);
      }

      .item__flag--update {
        color: var(--hl-orange-alt);
        background: color-mix(in srgb, var(--hl-orange-alt) 14%, transparent);
      }

      .item__flag--deprecated {
        color: var(--hl-orange-alt);
        background: color-mix(in srgb, var(--hl-orange-alt) 14%, transparent);
      }

      .item__flag--revoked {
        color: var(--hl-error);
        background: color-mix(in srgb, var(--hl-error) 14%, transparent);
      }

      .palette__hint {
        margin: var(--space-4) var(--space-3) 0;
      }
    `,
  ],
})
export class NodePalette {
  readonly entries = input.required<NodeCatalogEntry[]>();
  readonly loading = input<boolean>(false);

  readonly addRequested = output<NodeCatalogEntry>();
  readonly refreshRequested = output<void>();

  protected readonly query = signal('');

  /** Read from the shared store rather than taken as an input, so the designer need not thread it through. */
  private readonly marketplace = inject(MarketplaceApiService);
  protected readonly pluginStatus = this.marketplace.byPluginId;

  /**
   * The palette's rows, before filtering.
   *
   * Grouping happens first so that filtering can match a plugin's *label* — "Excel Handler" appears in no
   * node type, category or description, and an operator who installed it will type exactly that.
   */
  private readonly allItems = computed(() => {
    const names = new Map<string, string | null>();
    for (const [pluginId, view] of this.pluginStatus()) {
      names.set(pluginId, view.name);
    }
    return toPaletteItems(this.entries(), names);
  });

  protected readonly filtered = computed(() => filterPaletteItems(this.allItems(), this.query()));

  /**
   * The rows, grouped under their category headings.
   *
   * A search narrows each surviving row to the operations that matched, so dragging it lands on what was
   * searched for rather than on the plugin's usual default. That is what keeps searching across 180
   * operations feeling like searching for a node.
   */
  protected readonly grouped = computed(() => {
    const groups = new Map<string, PaletteItem[]>();
    for (const item of this.filtered()) {
      // A plugin spanning categories is filed under its representative operation's category, so it appears
      // once rather than being split across two headings.
      const category = item.entry.category;
      const list = groups.get(category) ?? [];
      list.push(item);
      groups.set(category, list);
    }
    return [...groups.entries()].map(([category, grouped]) => ({ category, items: grouped }));
  });

  /** Categories the operator has collapsed, remembered across reloads. */
  private readonly collapsedCategories = signal<Set<string>>(loadCollapsedCategories());

  /**
   * Whether a category is collapsed.
   *
   * <p>Always expanded while a search is active: a match hidden inside a collapsed group would look like the
   * search had missed it, so the collapse gives way to the more urgent job of showing what was found.
   */
  protected isCollapsed(category: string): boolean {
    if (this.query().trim().length > 0) {
      return false;
    }
    return this.collapsedCategories().has(category);
  }

  protected toggleCategory(category: string): void {
    const next = new Set(this.collapsedCategories());
    if (next.has(category)) {
      next.delete(category);
    } else {
      next.add(category);
    }
    this.collapsedCategories.set(next);
    storeCollapsedCategories(next);
  }

  protected colorFor(entry: NodeCatalogEntry): string {
    return categoryColorVar(entry.category);
  }

  /**
   * Everything a tile cannot show, gathered into its tooltip.
   *
   * A tile has room for an icon and a two-line name, so the description, the plugin coordinate and the
   * operation count move here rather than being dropped — this is the only place they remain reachable
   * before the node is on the canvas.
   */
  protected tooltipFor(item: PaletteItem): string {
    const lines = [item.label];
    const description = item.entry.description?.trim();
    if (description && description !== item.label) {
      lines.push(description);
    }
    if (item.operations.length > 1) {
      lines.push(item.meta);
    }
    if (item.entry.source === 'PLUGIN' && item.entry.pluginId) {
      lines.push(`${item.entry.pluginId} ${item.entry.pluginVersion ?? ''}`.trim());
    } else {
      lines.push(item.entry.nodeType);
    }
    return lines.join('\n');
  }

  /**
   * The registry's opinion of the plugin behind a palette entry, if it has one worth showing.
   *
   * Deliberately a hint rather than a filter. Every entry in this palette comes from a plugin that is loaded
   * and will execute, so hiding a deprecated one would remove a working node type on the strength of a label
   * somebody else applied upstream. The author is told and left to decide.
   */
  protected noteFor(
    entry: NodeCatalogEntry,
  ): { kind: 'update' | 'deprecated' | 'revoked'; label: string; title: string } | null {
    if (entry.source !== 'PLUGIN' || !entry.pluginId) {
      return null;
    }
    const view = this.pluginStatus().get(entry.pluginId);
    if (!view) {
      return null;
    }
    if (view.status === 'REVOKED') {
      return {
        kind: 'revoked',
        label: 'revoked',
        title: 'The registry has withdrawn this plugin. It still runs here, but avoid new uses of it.',
      };
    }
    if (view.status === 'DEPRECATED' || view.deprecatedInstalled) {
      return {
        kind: 'deprecated',
        label: 'deprecated',
        title: 'Still supported, but the registry expects a newer version to replace it.',
      };
    }
    if (view.status === 'UPDATE_AVAILABLE') {
      return {
        kind: 'update',
        label: 'update',
        title: `Version ${view.serverVersion} is published; this engine has ${view.installedVersion}.`,
      };
    }
    return null;
  }

  protected onDragStart(event: DragEvent, entry: NodeCatalogEntry): void {
    // The node type alone is enough: the canvas asks the catalogue for everything else, so the
    // payload cannot go stale between the drag starting and the drop landing.
    event.dataTransfer?.setData('text/plain', entry.nodeType);
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'copy';
    }
  }
}

const COLLAPSED_CATEGORIES_KEY = 'wf-palette-collapsed-categories';

/**
 * Reads the remembered set of collapsed categories, defaulting to none.
 *
 * Wrapped in a try because localStorage throws in a few environments (private-mode Safari, a sandboxed
 * frame); the palette works there, it just does not remember which groups were folded.
 */
function loadCollapsedCategories(): Set<string> {
  try {
    const raw = localStorage.getItem(COLLAPSED_CATEGORIES_KEY);
    if (!raw) {
      return new Set();
    }
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? new Set(parsed.map((value) => String(value))) : new Set();
  } catch {
    return new Set();
  }
}

function storeCollapsedCategories(categories: Set<string>): void {
  try {
    localStorage.setItem(COLLAPSED_CATEGORIES_KEY, JSON.stringify([...categories]));
  } catch {
    // Storage refused; the toggle still works for this session.
  }
}
