import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-base';
import {
  AiAgentExecution,
  AiConnection,
  AiConnectionRequest,
  AiModel,
  AiProvider,
  AiTool,
  AiUsageSummary,
} from '../models/ai.models';

/** Client for `/api/ai` — providers, models and provider connections. */
@Injectable({ providedIn: 'root' })
export class AiApiService {
  private readonly base = inject(API_BASE_URL) + '/api/ai';
  private readonly http = inject(HttpClient);

  /** Installed providers and their capabilities. */
  providers(): Observable<AiProvider[]> {
    return this.http.get<AiProvider[]>(`${this.base}/providers`);
  }

  /** All configured connections (never their keys). */
  connections(): Observable<AiConnection[]> {
    return this.http.get<AiConnection[]>(`${this.base}/connections`);
  }

  /** Models a connection's provider offers, discovered live where possible. */
  models(connectionId: string): Observable<AiModel[]> {
    return this.http.get<AiModel[]>(`${this.base}/connections/${encodeURIComponent(connectionId)}/models`);
  }

  /** Plugin-backed tools an AI Agent can be configured with. */
  tools(): Observable<AiTool[]> {
    return this.http.get<AiTool[]>(`${this.base}/tools`);
  }

  /** Token-usage summary across all AI Agent runs. */
  usage(): Observable<AiUsageSummary> {
    return this.http.get<AiUsageSummary>(`${this.base}/usage`);
  }

  /** Recent AI Agent runs (metadata only — never prompts or responses). */
  executions(): Observable<AiAgentExecution[]> {
    return this.http.get<AiAgentExecution[]>(`${this.base}/executions`);
  }

  create(request: AiConnectionRequest): Observable<AiConnection> {
    return this.http.post<AiConnection>(`${this.base}/connections`, request);
  }

  update(id: string, request: AiConnectionRequest): Observable<AiConnection> {
    return this.http.put<AiConnection>(`${this.base}/connections/${encodeURIComponent(id)}`, request);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/connections/${encodeURIComponent(id)}`);
  }

  test(id: string): Observable<{ connected: boolean }> {
    return this.http.post<{ connected: boolean }>(
      `${this.base}/connections/${encodeURIComponent(id)}/test`,
      {},
    );
  }
}
