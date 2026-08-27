import { HttpClient, HttpEvent, HttpParams, HttpRequest } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { API_BASE_URL } from './api-base';
import { Observable } from 'rxjs';
import { Page } from '../models/api.models';
import {
  DeletePluginResponse,
  PluginExecutionResponse,
  PluginResponse,
  PluginUploadOptions,
  PluginVersionResponse,
} from '../models/plugin.models';

/**
 * Client for `/api/plugins`.
 *
 * Every call here needs a `PLUGIN_*` permission, which only the ADMIN and PLUGIN_ADMIN roles grant. The
 * auth interceptor attaches the bearer token; a caller without the permission receives 403.
 */
@Injectable({ providedIn: 'root' })
export class PluginApiService {
  /** Path segment. Prefixed with the configured backend base URL, which is empty for same-origin. */
  private static readonly PATH = '/api/plugins';

  private readonly base = inject(API_BASE_URL) + PluginApiService.PATH;

  private readonly http = inject(HttpClient);

  list(): Observable<PluginResponse[]> {
    return this.http.get<PluginResponse[]>(this.base);
  }

  get(pluginId: string): Observable<PluginResponse> {
    return this.http.get<PluginResponse>(`${this.base}/${encodeURIComponent(pluginId)}`);
  }

  versions(pluginId: string): Observable<PluginVersionResponse[]> {
    return this.http.get<PluginVersionResponse[]>(
      `${this.base}/${encodeURIComponent(pluginId)}/versions`,
    );
  }

  executions(
    pluginId: string,
    options: { version?: string | null; page?: number; size?: number } = {},
  ): Observable<Page<PluginExecutionResponse>> {
    let params = new HttpParams()
      .set('page', String(options.page ?? 0))
      .set('size', String(options.size ?? 20));
    if (options.version) {
      params = params.set('version', options.version);
    }
    return this.http.get<Page<PluginExecutionResponse>>(
      `${this.base}/${encodeURIComponent(pluginId)}/executions`,
      { params },
    );
  }

  /**
   * Uploads and installs a plugin JAR.
   *
   * Reports progress events, because a plugin that bundles its own dependencies is easily tens of
   * megabytes and a silent upload of that size feels broken.
   *
   * Form fields are used rather than a JSON metadata part: the engine accepts both, and multipart
   * with a typed JSON part is a well-known source of confusing 400s.
   */
  upload(options: PluginUploadOptions): Observable<HttpEvent<PluginVersionResponse>> {
    const form = new FormData();
    form.append('file', options.file, options.file.name);
    form.append('activate', String(options.activate));
    form.append('eventsEnabled', String(options.eventsEnabled));
    if (options.allowedHosts.length > 0) {
      form.append('allowedHosts', options.allowedHosts.join(','));
    }
    if (options.secretScopes.length > 0) {
      form.append('secretScopes', options.secretScopes.join(','));
    }
    appendIfPresent(form, 'pluginId', options.pluginId);
    appendIfPresent(form, 'version', options.version);
    appendIfPresent(form, 'mainClass', options.mainClass);
    appendIfPresent(form, 'description', options.description);
    appendIfPresent(form, 'expectedSha256', options.expectedSha256);

    const request = new HttpRequest<FormData>('POST', `${this.base}/upload`, form, {
      reportProgress: true,
    });
    return this.http.request<PluginVersionResponse>(request);
  }

  activate(pluginId: string, version: string): Observable<PluginVersionResponse> {
    return this.http.post<PluginVersionResponse>(
      `${this.base}/${encodeURIComponent(pluginId)}/activate`,
      {},
      { params: new HttpParams().set('version', version) },
    );
  }

  /** The kill switch: drains in-flight work, unloads, and removes the node types. */
  deactivate(pluginId: string, version: string): Observable<PluginVersionResponse> {
    return this.http.post<PluginVersionResponse>(
      `${this.base}/${encodeURIComponent(pluginId)}/deactivate`,
      {},
      { params: new HttpParams().set('version', version) },
    );
  }

  reload(pluginId: string, version: string): Observable<PluginVersionResponse> {
    return this.http.post<PluginVersionResponse>(
      `${this.base}/${encodeURIComponent(pluginId)}/reload`,
      {},
      { params: new HttpParams().set('version', version) },
    );
  }

  /**
   * Changes what an installed version may reach.
   *
   * The whole set is sent, not a delta — the editor shows the current lists and submits the edited ones, so
   * an empty `allowedHosts` means deny-all, exactly as it reads. The server reloads the version if it is
   * loaded, so the new allowlist applies without a separate reload.
   */
  updatePermissions(
    pluginId: string,
    version: string,
    permissions: { allowedHosts: string[]; secretScopes: string[]; eventsEnabled: boolean },
  ): Observable<PluginVersionResponse> {
    return this.http.put<PluginVersionResponse>(
      `${this.base}/${encodeURIComponent(pluginId)}/permissions`,
      permissions,
      { params: new HttpParams().set('version', version) },
    );
  }

  /** Chooses which version serves workflow nodes that do not pin one. */
  setDefaultVersion(pluginId: string, version: string): Observable<PluginResponse> {
    return this.http.post<PluginResponse>(
      `${this.base}/${encodeURIComponent(pluginId)}/default-version`,
      {},
      { params: new HttpParams().set('version', version) },
    );
  }

  /** Omitting the version removes the plugin entirely. */
  delete(pluginId: string, version?: string | null): Observable<DeletePluginResponse> {
    let params = new HttpParams();
    if (version) {
      params = params.set('version', version);
    }
    return this.http.delete<DeletePluginResponse>(
      `${this.base}/${encodeURIComponent(pluginId)}`,
      { params },
    );
  }
}

function appendIfPresent(form: FormData, key: string, value: string | undefined): void {
  if (value && value.trim().length > 0) {
    form.append(key, value.trim());
  }
}
