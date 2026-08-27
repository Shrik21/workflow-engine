import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { API_BASE_URL } from './api-base';
import { Observable } from 'rxjs';
import { SecretRequest, SecretStatus, SecretSummary } from '../models/plugin.models';

/**
 * Client for `/api/secrets`.
 *
 * There is no read method for a secret value, because the engine exposes no such endpoint. Values go
 * in and only metadata comes out; the single path from a stored secret to a consumer is a plugin
 * calling the scoped provider, which the engine audits.
 */
@Injectable({ providedIn: 'root' })
export class SecretApiService {
  /** Path segment. Prefixed with the configured backend base URL, which is empty for same-origin. */
  private static readonly PATH = '/api/secrets';

  private readonly base = inject(API_BASE_URL) + SecretApiService.PATH;

  private readonly http = inject(HttpClient);

  list(): Observable<SecretSummary[]> {
    return this.http.get<SecretSummary[]>(this.base);
  }

  /** Reports whether a master key is configured. Writes are rejected when it is not. */
  status(): Observable<SecretStatus> {
    return this.http.get<SecretStatus>(`${this.base}/status`);
  }

  put(name: string, request: SecretRequest): Observable<void> {
    return this.http.put<void>(`${this.base}/${encodeURIComponent(name)}`, request);
  }

  delete(name: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${encodeURIComponent(name)}`);
  }
}
