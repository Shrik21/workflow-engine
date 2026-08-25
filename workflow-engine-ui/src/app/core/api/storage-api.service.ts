import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-base';
import {
  PathProbeResult,
  StorageHealth,
  StorageSettings,
  StorageSettingsUpdate,
  StorageTestRequest,
} from '../models/storage.models';

/**
 * Client for `/api/settings/storage`.
 *
 * Every endpoint here requires a storage-settings permission, which only an administrator holds. The console
 * still guards the route, but that is a courtesy to the user rather than a control: a guard that was bypassed
 * would produce a page of 403s, because the server authorises each call independently.
 */
@Injectable({ providedIn: 'root' })
export class StorageApiService {
  private static readonly PATH = '/api/settings/storage';

  private readonly base = inject(API_BASE_URL) + StorageApiService.PATH;

  private readonly http = inject(HttpClient);

  /** Re-probes the configured path server-side, so `status` reflects the location's condition now. */
  get(): Observable<StorageSettings> {
    return this.http.get<StorageSettings>(this.base);
  }

  save(request: StorageSettingsUpdate): Observable<StorageSettings> {
    return this.http.put<StorageSettings>(this.base, request);
  }

  /** Tests a path without saving it. The server writes and deletes a probe file. */
  test(request: StorageTestRequest): Observable<PathProbeResult> {
    return this.http.post<PathProbeResult>(`${this.base}/test`, request);
  }

  health(): Observable<StorageHealth> {
    return this.http.get<StorageHealth>(`${this.base}/health`);
  }

  /** Clears the configuration. Deletes no files — see the server's note on why. */
  reset(): Observable<void> {
    return this.http.delete<void>(this.base);
  }
}
