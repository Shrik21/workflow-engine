import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-base';
import {
  ExternalLinkResponse,
  ExternalLinkSummary,
  GenerateLinkRequest,
} from '../models/public-form.models';

/**
 * Client for `/api/workflow-tasks/{taskId}/external-link` — the internal management of a task's external links.
 *
 * JWT-authenticated and permission-gated on the server. Generate and regenerate return the URL exactly once;
 * every other call returns only link status, never a token.
 */
@Injectable({ providedIn: 'root' })
export class ExternalLinkApiService {
  private readonly base = inject(API_BASE_URL) + '/api/workflow-tasks';
  private readonly http = inject(HttpClient);

  private path(taskId: string): string {
    return `${this.base}/${encodeURIComponent(taskId)}/external-link`;
  }

  generate(taskId: string, request: GenerateLinkRequest = {}): Observable<ExternalLinkResponse> {
    return this.http.post<ExternalLinkResponse>(this.path(taskId), request);
  }

  regenerate(taskId: string, request: GenerateLinkRequest = {}): Observable<ExternalLinkResponse> {
    return this.http.post<ExternalLinkResponse>(`${this.path(taskId)}/regenerate`, request);
  }

  revoke(taskId: string): Observable<ExternalLinkSummary[]> {
    return this.http.post<ExternalLinkSummary[]>(`${this.path(taskId)}/revoke`, {});
  }

  list(taskId: string): Observable<ExternalLinkSummary[]> {
    return this.http.get<ExternalLinkSummary[]>(this.path(taskId));
  }
}
