import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { API_BASE_URL } from './api-base';
import {
  CatalogHealth,
  InstallationResult,
  PluginInstallationRecord,
  PluginStatusView,
  RegistryInfo,
  SyncResult,
} from '../models/marketplace.models';

/**
 * Client and cache for the plugin marketplace.
 *
 * A small store rather than a bare client, for the same reason {@link NodeApiService} is one: the marketplace
 * screen, the plugin detail page, the designer palette and the workflow upgrade dialog all want the same
 * answer to "what is installed and what is available", and four independent fetches on every navigation would
 * be four chances for them to disagree.
 *
 * Nothing here re-derives status. The engine decides `UPDATE_AVAILABLE` against a version-precedence rule that
 * excludes pre-releases, and a second implementation in the browser would be a second rule.
 */
@Injectable({ providedIn: 'root' })
export class MarketplaceApiService {
  /** Path segment. Prefixed with the configured backend base URL, which is empty for same-origin. */
  private static readonly PATH = '/api/plugins';

  private readonly base = inject(API_BASE_URL) + MarketplaceApiService.PATH;

  private readonly http = inject(HttpClient);

  private readonly statusesState = signal<PluginStatusView[]>([]);
  private readonly healthState = signal<CatalogHealth | null>(null);
  private readonly loadingState = signal(false);
  private loaded = false;

  readonly statuses = this.statusesState.asReadonly();
  readonly health = this.healthState.asReadonly();
  readonly loading = this.loadingState.asReadonly();

  /** Plugins the registry offers that are not installed here. */
  readonly available = computed(() =>
    this.statusesState().filter((view) => view.status === 'NOT_INSTALLED'),
  );

  /** Plugins with a newer version waiting, which is what a badge on the navigation would count. */
  readonly updatable = computed(() =>
    this.statusesState().filter((view) => view.status === 'UPDATE_AVAILABLE'),
  );

  /** Anything installed here, whatever the registry thinks of it. */
  readonly installed = computed(() =>
    this.statusesState().filter((view) => view.installedVersion !== null),
  );

  /**
   * Indexed by plugin id, for the designer.
   *
   * The palette asks "is the plugin behind this node type deprecated" once per entry per render, and a linear
   * scan per lookup turns a fifty-node palette into a needless quadratic.
   */
  readonly byPluginId = computed(() => {
    const index = new Map<string, PluginStatusView>();
    for (const view of this.statusesState()) {
      index.set(view.pluginId, view);
    }
    return index;
  });

  /** Loads the marketplace once. Subsequent calls are no-ops until {@link invalidate}. */
  ensureLoaded(): void {
    if (this.loaded || this.loadingState()) {
      return;
    }
    this.refresh();
  }

  /**
   * Reloads the statuses and the catalogue's health.
   *
   * Both, always: a status list without the health beside it invites reading an absent plugin as proof it does
   * not exist, when the honest answer may be that the catalogue has not been reachable for an hour.
   */
  refresh(): void {
    this.loadingState.set(true);
    this.http.get<PluginStatusView[]>(`${this.base}/status`).subscribe({
      next: (statuses) => {
        this.statusesState.set(statuses ?? []);
        this.loaded = true;
        this.loadingState.set(false);
      },
      // The error interceptor has already reported it. Clearing the flag keeps the screen from being stuck
      // in a loading state after a failed refresh.
      error: () => this.loadingState.set(false),
    });
    this.http.get<CatalogHealth>(`${this.base}/catalog-health`).subscribe({
      next: (health) => this.healthState.set(health),
      error: () => this.healthState.set(null),
    });
  }

  /** Called after anything that could change what is installed. */
  invalidate(): void {
    this.loaded = false;
    this.refresh();
  }

  status(pluginId: string): Observable<PluginStatusView> {
    return this.http.get<PluginStatusView>(`${this.base}/status/${encodeURIComponent(pluginId)}`);
  }

  registry(): Observable<RegistryInfo> {
    return this.http.get<RegistryInfo>(`${this.base}/registry`);
  }

  catalogHealth(): Observable<CatalogHealth> {
    return this.http
      .get<CatalogHealth>(`${this.base}/catalog-health`)
      .pipe(tap((health) => this.healthState.set(health)));
  }

  /** Refreshes the engine's catalogue from the registry. Costs a 304 when nothing changed. */
  sync(): Observable<SyncResult> {
    return this.http.post<SyncResult>(`${this.base}/sync`, {});
  }

  /**
   * Installs the registry's latest release, or one specific version.
   *
   * No progress events: unlike an upload, the bytes move between the engine and the registry, so the browser
   * has nothing to report on. The call simply takes as long as the download and verification take.
   */
  install(pluginId: string, version?: string | null): Observable<InstallationResult> {
    const id = encodeURIComponent(pluginId);
    const url = version
      ? `${this.base}/${id}/versions/${encodeURIComponent(version)}/install`
      : `${this.base}/${id}/install`;
    return this.http.post<InstallationResult>(url, {});
  }

  update(pluginId: string): Observable<InstallationResult> {
    return this.http.post<InstallationResult>(
      `${this.base}/${encodeURIComponent(pluginId)}/update`,
      {},
    );
  }

  /** Refused with 409 when a published workflow still uses the version. */
  uninstall(pluginId: string, version: string): Observable<InstallationResult> {
    return this.http.delete<InstallationResult>(
      `${this.base}/${encodeURIComponent(pluginId)}/versions/${encodeURIComponent(version)}`,
    );
  }

  activateVersion(pluginId: string, version: string): Observable<InstallationResult> {
    return this.http.post<InstallationResult>(
      `${this.base}/${encodeURIComponent(pluginId)}/versions/${encodeURIComponent(version)}/activate`,
      {},
    );
  }

  deactivateVersion(pluginId: string, version: string): Observable<InstallationResult> {
    return this.http.post<InstallationResult>(
      `${this.base}/${encodeURIComponent(pluginId)}/versions/${encodeURIComponent(version)}/deactivate`,
      {},
    );
  }

  /** Every install, update, uninstall, activation and deactivation, including refusals and failures. */
  history(pluginId?: string | null): Observable<PluginInstallationRecord[]> {
    let params = new HttpParams();
    if (pluginId) {
      params = params.set('pluginId', pluginId);
    }
    return this.http.get<PluginInstallationRecord[]>(`${this.base}/installation-history`, {
      params,
    });
  }
}
