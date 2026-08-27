import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-base';
import { InstanceHistoryEntry, InstanceStatus } from '../models/execution.models';

/**
 * Client for `/api/workflow-instances` — the runtime lifecycle of a single instance.
 *
 * <p>Separate from {@link ExecutionApiService} because these operations mean something different: pause, resume
 * and terminate act on the instance and cascade to its tasks, and they are gated on their own permissions. The
 * execution endpoints remain for the lower-level controls; these are the instance-lifecycle ones the spec
 * defines.
 */
@Injectable({ providedIn: 'root' })
export class WorkflowInstanceApiService {
  private static readonly PATH = '/api/workflow-instances';

  private readonly base = inject(API_BASE_URL) + WorkflowInstanceApiService.PATH;
  private readonly http = inject(HttpClient);

  status(instanceId: string): Observable<InstanceStatus> {
    return this.http.get<InstanceStatus>(`${this.base}/${encodeURIComponent(instanceId)}/status`);
  }

  pause(instanceId: string, reason?: string | null): Observable<InstanceStatus> {
    return this.http.post<InstanceStatus>(
      `${this.base}/${encodeURIComponent(instanceId)}/pause`,
      { reason: reason ?? null },
    );
  }

  resume(instanceId: string): Observable<InstanceStatus> {
    return this.http.post<InstanceStatus>(
      `${this.base}/${encodeURIComponent(instanceId)}/resume`,
      {},
    );
  }

  terminate(instanceId: string, reason?: string | null): Observable<InstanceStatus> {
    return this.http.post<InstanceStatus>(
      `${this.base}/${encodeURIComponent(instanceId)}/terminate`,
      { reason: reason ?? null },
    );
  }

  history(instanceId: string, limit = 50): Observable<InstanceHistoryEntry[]> {
    const params = new HttpParams().set('limit', String(limit));
    return this.http.get<InstanceHistoryEntry[]>(
      `${this.base}/${encodeURIComponent(instanceId)}/history`,
      { params },
    );
  }
}
