import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { API_BASE_URL } from './api-base';
import { tap } from 'rxjs';
import { NodeCatalogEntry } from '../models/node.models';

/**
 * Client and cache for `GET /api/nodes`.
 *
 * This is the endpoint that decouples the front end from the plugin release cycle, so it gets a
 * small store rather than a bare client: the designer palette, the property panel and the node
 * browser all need the same catalogue, and each fetching it separately would triple the requests on
 * every navigation.
 *
 * The cache is explicitly invalidated after any plugin lifecycle change. A palette that still offers
 * a node type whose plugin was just deactivated is worse than a brief loading state, because the
 * workflow it produces would fail validation for reasons the operator cannot see.
 */
@Injectable({ providedIn: 'root' })
export class NodeApiService {
  /** Path segment. Prefixed with the configured backend base URL, which is empty for same-origin. */
  private static readonly PATH = '/api/nodes';

  private readonly base = inject(API_BASE_URL) + NodeApiService.PATH;

  private readonly http = inject(HttpClient);
  private readonly entriesState = signal<NodeCatalogEntry[]>([]);
  private readonly loadingState = signal(false);
  private loaded = false;

  /** Plugin id to a `data:` URL for the artwork that plugin shipped. Empty until the first load. */
  private readonly iconsState = signal<Record<string, string>>({});

  readonly entries = this.entriesState.asReadonly();
  readonly loading = this.loadingState.asReadonly();
  readonly icons = this.iconsState.asReadonly();

  /**
   * @param pluginId the plugin behind a node, or null for a built-in
   * @return its shipped icon as a `data:` URL, or null to fall back to the built-in glyph
   */
  iconFor(pluginId: string | null | undefined): string | null {
    return pluginId ? (this.iconsState()[pluginId] ?? null) : null;
  }

  /** Node types contributed by plugins, which is what an operator wants to see highlighted. */
  readonly pluginEntries = computed(() => this.entriesState().filter((e) => e.source === 'PLUGIN'));

  readonly builtInEntries = computed(() =>
    this.entriesState().filter((e) => e.source === 'BUILT_IN'),
  );

  /** Categories in catalogue order, for grouping the palette. */
  readonly categories = computed(() => {
    const seen: string[] = [];
    for (const entry of this.entriesState()) {
      if (!seen.includes(entry.category)) {
        seen.push(entry.category);
      }
    }
    return seen;
  });

  /** Loads the catalogue once. Subsequent calls are no-ops until {@link invalidate}. */
  ensureLoaded(): void {
    if (this.loaded || this.loadingState()) {
      return;
    }
    this.refresh();
  }

  refresh(): void {
    this.loadingState.set(true);
    this.http
      .get<NodeCatalogEntry[]>(this.base)
      .pipe(
        tap({
          next: (entries) => {
            this.entriesState.set(entries ?? []);
            this.loaded = true;
          },
        }),
      )
      .subscribe({
        next: () => this.loadingState.set(false),
        // The error interceptor has already told the operator. Clearing the flag here keeps the UI
        // from being stuck in a loading state after a failed refresh.
        error: () => this.loadingState.set(false),
      });
    this.refreshIcons();
  }

  /**
   * Loads the artwork plugins ship, keyed by plugin id.
   *
   * <p>Its own request rather than a field on each catalogue entry: an icon belongs to a plugin, and one
   * plugin contributes many node types — inlining it per entry would repeat the same kilobytes thirty-two
   * times for the GCP Network plugin alone.
   *
   * <p>Failure is silent on purpose. A missing icon costs nothing — every node falls back to its built-in
   * glyph — and a toast saying "could not load icons" over a designer that works perfectly well is noise.
   */
  private refreshIcons(): void {
    this.http.get<Record<string, string>>(`${this.base}/icons`).subscribe({
      next: (icons) => this.iconsState.set(icons ?? {}),
      error: () => this.iconsState.set({}),
    });
  }

  /** Called after a plugin is uploaded, activated, deactivated, reloaded or deleted. */
  invalidate(): void {
    this.loaded = false;
    this.refresh();
  }

  find(nodeType: string): NodeCatalogEntry | undefined {
    return this.entriesState().find((entry) => entry.nodeType === nodeType);
  }

  describe(nodeType: string) {
    return this.http.get<NodeCatalogEntry>(`${this.base}/${encodeURIComponent(nodeType)}`);
  }

  categoriesFromServer() {
    return this.http.get<string[]>(`${this.base}/categories`);
  }

  listByCategory(category: string) {
    const params = new HttpParams().set('category', category);
    return this.http.get<NodeCatalogEntry[]>(this.base, { params });
  }
}
