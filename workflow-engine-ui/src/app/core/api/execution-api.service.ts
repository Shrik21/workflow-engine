import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { API_BASE_URL } from './api-base';
import { Observable } from 'rxjs';
import { Page } from '../models/api.models';
import {
  ExecutionLogResponse,
  ExecutionResponse,
  ExecutionStatus,
  FormSubmissionRequest,
  PendingSignalView,
} from '../models/execution.models';

/** Client for `/api/executions`. */
@Injectable({ providedIn: 'root' })
export class ExecutionApiService {
  /** Path segment. Prefixed with the configured backend base URL, which is empty for same-origin. */
  private static readonly PATH = '/api/executions';

  private readonly base = inject(API_BASE_URL) + ExecutionApiService.PATH;

  private readonly http = inject(HttpClient);

  list(options: {
    workflowId?: string | null;
    status?: ExecutionStatus | null;
    page?: number;
    size?: number;
  }): Observable<Page<ExecutionResponse>> {
    let params = new HttpParams()
      .set('page', String(options.page ?? 0))
      .set('size', String(options.size ?? 20));
    if (options.workflowId) {
      params = params.set('workflowId', options.workflowId);
    }
    if (options.status) {
      params = params.set('status', options.status);
    }
    return this.http.get<Page<ExecutionResponse>>(this.base, { params });
  }

  get(executionId: string): Observable<ExecutionResponse> {
    return this.http.get<ExecutionResponse>(
      `${this.base}/${encodeURIComponent(executionId)}`,
    );
  }

  logs(executionId: string, limit = 200): Observable<ExecutionLogResponse[]> {
    const params = new HttpParams().set('limit', String(limit));
    return this.http.get<ExecutionLogResponse[]>(
      `${this.base}/${encodeURIComponent(executionId)}/logs`,
      { params },
    );
  }

  /** Returns 204 with no body when the execution is not waiting, so the result may be null. */
  pending(executionId: string): Observable<PendingSignalView | null> {
    return this.http.get<PendingSignalView | null>(
      `${this.base}/${encodeURIComponent(executionId)}/pending`,
    );
  }

  /** Satisfies the parked node and continues the execution. */
  submitForm(executionId: string, request: FormSubmissionRequest): Observable<ExecutionResponse> {
    return this.http.post<ExecutionResponse>(
      `${this.base}/${encodeURIComponent(executionId)}/form`,
      request,
    );
  }

  resume(executionId: string, async = true): Observable<ExecutionResponse> {
    const params = new HttpParams().set('async', String(async));
    return this.http.post<ExecutionResponse>(
      `${this.base}/${encodeURIComponent(executionId)}/resume`,
      {},
      { params },
    );
  }

  pause(executionId: string): Observable<ExecutionResponse> {
    return this.http.post<ExecutionResponse>(
      `${this.base}/${encodeURIComponent(executionId)}/pause`,
      {},
    );
  }

  cancel(executionId: string): Observable<ExecutionResponse> {
    return this.http.post<ExecutionResponse>(
      `${this.base}/${encodeURIComponent(executionId)}/cancel`,
      {},
    );
  }
}
