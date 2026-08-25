import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-base';
import {
  AiCliConfiguration,
  AiCliConfigurationUpdate,
  AiCliDetection,
  AiCliFeatureStatus,
  AiCliTestResult,
  ErrorAnalysis,
  IamLookup,
} from '../models/ai-cli.models';

/**
 * Client for `/api/ai/cli` and `/api/ai/analysis`.
 *
 * Nothing here sends a command or an argument list. A caller supplies a name and a path; which arguments the
 * server runs are the server's, which is what keeps the browser off the command line entirely.
 */
@Injectable({ providedIn: 'root' })
export class AiCliApiService {
  private readonly base = inject(API_BASE_URL) + '/api/ai/cli';

  private readonly analysisBase = inject(API_BASE_URL) + '/api/ai/analysis';

  private readonly http = inject(HttpClient);

  /**
   * Whether the engine will run AI CLIs at all.
   *
   * Its own call so the page can say "an operator must enable this" rather than showing a form whose every
   * button fails.
   */
  status(): Observable<AiCliFeatureStatus> {
    return this.http.get<AiCliFeatureStatus>(`${this.base}/status`);
  }

  list(): Observable<AiCliConfiguration[]> {
    return this.http.get<AiCliConfiguration[]>(this.base);
  }

  get(id: string): Observable<AiCliConfiguration> {
    return this.http.get<AiCliConfiguration>(`${this.base}/${id}`);
  }

  create(request: AiCliConfigurationUpdate): Observable<AiCliConfiguration> {
    return this.http.post<AiCliConfiguration>(this.base, request);
  }

  update(id: string, request: AiCliConfigurationUpdate): Observable<AiCliConfiguration> {
    return this.http.put<AiCliConfiguration>(`${this.base}/${id}`, request);
  }

  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }

  /** Runs the tool's version command. Returns a failure result rather than erroring when it is absent. */
  test(id: string): Observable<AiCliTestResult> {
    return this.http.post<AiCliTestResult>(`${this.base}/${id}/test`, {});
  }

  version(id: string): Observable<{ version: string }> {
    return this.http.get<{ version: string }>(`${this.base}/${id}/version`);
  }

  /** Searches the engine host for an installed CLI. Executes nothing. */
  detect(type = 'CLAUDE_CLI'): Observable<AiCliDetection> {
    return this.http.get<AiCliDetection>(`${this.base}/detect`, { params: { type } });
  }

  /** Asks the configured AI to explain a failed node. Changes nothing. */
  analyseNode(
    executionId: string,
    nodeId: string,
    configurationId?: string,
  ): Observable<ErrorAnalysis> {
    // Built rather than passed as a possibly-empty literal: an empty object matches a different overload and
    // widens the return type to ArrayBuffer.
    let params = new HttpParams();
    if (configurationId) {
      params = params.set('configurationId', configurationId);
    }
    return this.http.post<ErrorAnalysis>(
      `${this.analysisBase}/executions/${executionId}/nodes/${nodeId}`,
      {},
      { params },
    );
  }

  /** Looks a permission up in the engine's own IAM reference, with no AI involved. */
  lookupPermission(permission: string): Observable<IamLookup> {
    return this.http.get<IamLookup>(
      `${this.analysisBase}/iam/permissions/${encodeURIComponent(permission)}`,
    );
  }
}
