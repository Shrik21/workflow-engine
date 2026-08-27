import {
  HttpClient,
  HttpEvent,
  HttpEventType,
  HttpHeaders,
  HttpParams,
  HttpRequest,
} from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, filter, map } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  Page,
  Plugin,
  PluginAuditEvent,
  PluginStatus,
  PluginUploadResult,
  PluginVersionDetail,
  PluginVersionSummary,
} from './models/plugin.model';

/** How an upload is getting on. */
export type UploadProgress =
  | { kind: 'PROGRESS'; percent: number; loadedBytes: number; totalBytes: number }
  | { kind: 'DONE'; result: PluginUploadResult };

/** Query for the plugin list. */
export interface PluginQuery {
  search?: string | null;
  page?: number;
  size?: number;
}

/**
 * The registry's HTTP API.
 *
 * <h2>Only what the registry actually exposes</h2>
 *
 * Every method here maps to an endpoint that exists. Where a convenient-sounding operation has no endpoint,
 * this service says so rather than inventing a path that would 404:
 *
 * - **There is no separate "upload a new version" endpoint.** A plugin's identity and version come from the
 *   manifest inside the archive, so `POST /api/plugins/upload` is how both a first upload and a new version
 *   arrive. The registry decides which it is. {@link uploadVersion} exists for readability at the call site and
 *   posts to the same place.
 * - **There is no validate endpoint.** The archive is inspected in the browser for the wizard's preview, and
 *   authoritatively by the registry when it arrives.
 * - **There is no usage endpoint.** Which workflows use a plugin is knowable only to a workflow engine; the
 *   registry stores plugins and has never heard of a workflow.
 * - **Audit lives at `/api/plugin-audit`**, filtered by plugin, not under the plugin resource.
 *
 * <h2>No caching</h2>
 *
 * Deliberate. Every screen here shows something an administrator is about to act on, and a stale "ACTIVE" next
 * to a version somebody revoked a minute ago is worse than a spinner. Callers refetch after every change.
 */
@Injectable({ providedIn: 'root' })
export class PluginApiService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.registryBaseUrl}/api/plugins`;
  private readonly auditBase = `${environment.registryBaseUrl}/api/plugin-audit`;

  /**
   * Suppresses the global error toast for one request.
   *
   * Used where the caller renders the failure itself, in a place with room to explain it and a control to fix
   * it — the upload wizard, above all.
   */
  private static readonly QUIET = new HttpHeaders({ 'X-Suppress-Error-Toast': 'true' });

  /**
   * Every plugin the registry holds.
   *
   * @param query search term and paging; the search matches id, name and vendor server-side
   */
  getPlugins(query: PluginQuery = {}): Observable<Page<Plugin>> {
    let params = new HttpParams()
      .set('page', String(query.page ?? 0))
      .set('size', String(query.size ?? 50));
    if (query.search && query.search.trim().length > 0) {
      params = params.set('search', query.search.trim());
    }
    return this.http.get<Page<Plugin>>(this.base, { params });
  }

  getPlugin(pluginId: string): Observable<Plugin> {
    return this.http.get<Plugin>(`${this.base}/${encodeURIComponent(pluginId)}`);
  }

  getPluginVersions(pluginId: string): Observable<PluginVersionSummary[]> {
    return this.http.get<PluginVersionSummary[]>(
      `${this.base}/${encodeURIComponent(pluginId)}/versions`,
    );
  }

  getPluginVersion(pluginId: string, version: string): Observable<PluginVersionDetail> {
    return this.http.get<PluginVersionDetail>(
      `${this.base}/${encodeURIComponent(pluginId)}/versions/${encodeURIComponent(version)}`,
    );
  }

  /**
   * Uploads an archive, reporting progress.
   *
   * Progress matters here in a way it does not for most requests: a plugin that bundles its dependencies is
   * routinely tens of megabytes, and a silent minute looks like a hung page.
   *
   * @param file the archive
   * @returns progress events, ending in the registry's answer
   */
  uploadPlugin(file: File): Observable<UploadProgress> {
    const form = new FormData();
    form.append('file', file, file.name);

    const request = new HttpRequest<FormData>('POST', `${this.base}/upload`, form, {
      reportProgress: true,
      headers: PluginApiService.QUIET,
    });

    return this.http.request<PluginUploadResult>(request).pipe(
      // Sent and response-header events carry nothing a progress bar can use, and emitting them as 0% would
      // make the bar jump backwards between real measurements.
      filter(
        (event) =>
          event.type === HttpEventType.UploadProgress || event.type === HttpEventType.Response,
      ),
      map((event) => this.toProgress(event)),
    );
  }

  /**
   * Uploads a new version of a plugin that already exists.
   *
   * The same endpoint as {@link uploadPlugin}: the archive's manifest names the plugin and the version, so the
   * registry keys on `pluginId:version` and rejects a duplicate as a failed insert rather than a check
   * followed by a write. The parameter exists so a caller reads clearly and so the console can verify the
   * archive belongs to the plugin whose page it was uploaded from.
   *
   * @param pluginId the plugin the operator believes they are adding to
   * @param file the archive
   */
  uploadVersion(pluginId: string, file: File): Observable<UploadProgress> {
    return this.uploadPlugin(file);
  }

  /** Makes a DRAFT version visible to workflow services. */
  publishVersion(pluginId: string, version: string): Observable<PluginVersionDetail> {
    return this.versionAction(pluginId, version, 'publish');
  }

  /** Withdraws a version from the catalogue without deprecating it. */
  deactivateVersion(pluginId: string, version: string): Observable<PluginVersionDetail> {
    return this.versionAction(pluginId, version, 'deactivate');
  }

  /** Marks a version superseded. It still downloads, so pinned workflows keep running. */
  deprecateVersion(pluginId: string, version: string): Observable<PluginVersionDetail> {
    return this.versionAction(pluginId, version, 'deprecate');
  }

  /**
   * Withdraws a version as unsafe. Downloads are refused from this point on.
   *
   * @param reason recorded on the version and shown wherever it appears
   */
  revokeVersion(pluginId: string, version: string, reason: string): Observable<PluginVersionDetail> {
    return this.http.post<PluginVersionDetail>(
      `${this.base}/${encodeURIComponent(pluginId)}/versions/${encodeURIComponent(version)}/revoke`,
      { reason },
    );
  }

  activatePlugin(pluginId: string): Observable<Plugin> {
    return this.http.post<Plugin>(`${this.base}/${encodeURIComponent(pluginId)}/activate`, {});
  }

  deactivatePlugin(pluginId: string): Observable<Plugin> {
    return this.http.post<Plugin>(`${this.base}/${encodeURIComponent(pluginId)}/deactivate`, {});
  }

  /**
   * The registry's audit trail for one plugin.
   *
   * @param pluginId the plugin
   */
  getPluginAudit(pluginId: string): Observable<PluginAuditEvent[]> {
    const params = new HttpParams().set('pluginId', pluginId).set('size', '100');
    return this.http
      .get<Page<PluginAuditEvent> | PluginAuditEvent[]>(this.auditBase, { params })
      .pipe(map((response) => (Array.isArray(response) ? response : (response.content ?? []))));
  }

  private versionAction(
    pluginId: string,
    version: string,
    action: 'publish' | 'deactivate' | 'deprecate',
  ): Observable<PluginVersionDetail> {
    return this.http.post<PluginVersionDetail>(
      `${this.base}/${encodeURIComponent(pluginId)}/versions/${encodeURIComponent(version)}/${action}`,
      {},
    );
  }

  private toProgress(event: HttpEvent<PluginUploadResult>): UploadProgress {
    if (event.type === HttpEventType.UploadProgress) {
      const total = event.total ?? 0;
      return {
        kind: 'PROGRESS',
        // Without a total the percentage is unknowable; reporting 0 keeps the bar honest rather than
        // animating something invented.
        percent: total > 0 ? Math.round((event.loaded / total) * 100) : 0,
        loadedBytes: event.loaded,
        totalBytes: total,
      };
    }
    if (event.type === HttpEventType.Response && event.body) {
      return { kind: 'DONE', result: event.body };
    }
    return { kind: 'PROGRESS', percent: 0, loadedBytes: 0, totalBytes: 0 };
  }
}

/** @returns whether a status permits a version to be installed by a workflow service */
export function isInstallable(status: PluginStatus): boolean {
  return status === 'ACTIVE' || status === 'DEPRECATED';
}
