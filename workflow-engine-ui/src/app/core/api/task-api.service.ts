import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Page } from '../models/api.models';
import {
  AssignableUser,
  TaskBucket,
  TaskCounts,
  TaskDetail,
  TaskStatus,
  TaskSummary,
} from '../models/task.models';
import { API_BASE_URL } from './api-base';

/**
 * Client for `/api/tasks`.
 *
 * Notice what none of these methods send: a user id. The task id identifies the work and the bearer token
 * identifies the person, and the server compares the two. There is no field here through which a client could
 * claim to be somebody else, which is deliberate rather than incidental — it is the reason `complete` takes only
 * form values.
 */
@Injectable({ providedIn: 'root' })
export class TaskApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL) + '/api/tasks';
  private readonly usersBase = inject(API_BASE_URL) + '/api/users';

  list(options: {
    bucket?: TaskBucket;
    workflowId?: string | null;
    status?: TaskStatus[] | null;
    page?: number;
    size?: number;
  }): Observable<Page<TaskSummary>> {
    let params = new HttpParams()
      .set('bucket', options.bucket ?? 'mine')
      .set('page', String(options.page ?? 0))
      .set('size', String(options.size ?? 25));
    if (options.workflowId?.trim()) {
      params = params.set('workflowId', options.workflowId.trim());
    }
    for (const status of options.status ?? []) {
      params = params.append('status', status);
    }
    return this.http.get<Page<TaskSummary>>(this.base, { params });
  }

  counts(): Observable<TaskCounts> {
    return this.http.get<TaskCounts>(`${this.base}/counts`);
  }

  /** 404 for a task that exists but is not yours; the server does not distinguish that from absent. */
  get(taskId: string): Observable<TaskDetail> {
    return this.http.get<TaskDetail>(`${this.base}/${encodeURIComponent(taskId)}`);
  }

  /** 409 when somebody else claimed it first, which is the outcome of two people opening the same inbox. */
  claim(taskId: string): Observable<TaskDetail> {
    return this.http.post<TaskDetail>(`${this.base}/${encodeURIComponent(taskId)}/claim`, {});
  }

  release(taskId: string): Observable<TaskDetail> {
    return this.http.post<TaskDetail>(`${this.base}/${encodeURIComponent(taskId)}/release`, {});
  }

  /** Not validated on the server, on purpose: a draft exists because the form is not finished. */
  saveDraft(taskId: string, formData: Record<string, unknown>): Observable<TaskDetail> {
    return this.http.post<TaskDetail>(`${this.base}/${encodeURIComponent(taskId)}/draft`, {
      formData,
    });
  }

  /** 422 with a details list naming every field that needs attention. */
  complete(taskId: string, formData: Record<string, unknown>): Observable<TaskDetail> {
    return this.http.post<TaskDetail>(`${this.base}/${encodeURIComponent(taskId)}/complete`, {
      formData,
    });
  }

  reassign(taskId: string, assignee: string, comment?: string): Observable<TaskDetail> {
    return this.http.post<TaskDetail>(`${this.base}/${encodeURIComponent(taskId)}/reassign`, {
      assignee,
      comment: comment ?? null,
    });
  }

  cancel(taskId: string, reason?: string): Observable<TaskDetail> {
    return this.http.post<TaskDetail>(`${this.base}/${encodeURIComponent(taskId)}/cancel`, {
      reason: reason ?? null,
    });
  }

  /** The assignee picker. Returns usernames and display names only. */
  assignableUsers(search?: string): Observable<AssignableUser[]> {
    let params = new HttpParams();
    if (search?.trim()) {
      params = params.set('search', search.trim());
    }
    return this.http.get<AssignableUser[]>(`${this.usersBase}/available`, { params });
  }
}
