import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { API_BASE_URL } from './api-base';
import { Observable } from 'rxjs';
import { Page } from '../models/api.models';
import {
  BulkControlResponse,
  ExportRequest,
  ImportResult,
  ImportValidationResult,
  PublishResponse,
  ScheduleConfig,
  ScheduleParseResult,
  SchedulePreview,
  ValidationResponse,
  WorkflowAuditEntry,
  WorkflowRequest,
  WorkflowResponse,
  WorkflowStatus,
} from '../models/workflow.models';
import { map } from 'rxjs/operators';
import { ExecuteWorkflowRequest, ExecutionResponse } from '../models/execution.models';

/** Client for `/api/workflows`. */
@Injectable({ providedIn: 'root' })
export class WorkflowApiService {
  /** Path segment. Prefixed with the configured backend base URL, which is empty for same-origin. */
  private static readonly PATH = '/api/workflows';

  private readonly base = inject(API_BASE_URL) + WorkflowApiService.PATH;

  private readonly http = inject(HttpClient);

  list(options: {
    status?: WorkflowStatus | null;
    name?: string | null;
    page?: number;
    size?: number;
  }): Observable<Page<WorkflowResponse>> {
    let params = new HttpParams()
      .set('page', String(options.page ?? 0))
      .set('size', String(options.size ?? 20));
    if (options.status) {
      params = params.set('status', options.status);
    }
    if (options.name?.trim()) {
      params = params.set('name', options.name.trim());
    }
    return this.http.get<Page<WorkflowResponse>>(this.base, { params });
  }

  get(id: string): Observable<WorkflowResponse> {
    return this.http.get<WorkflowResponse>(`${this.base}/${encodeURIComponent(id)}`);
  }

  create(request: WorkflowRequest): Observable<WorkflowResponse> {
    return this.http.post<WorkflowResponse>(this.base, request);
  }

  /** Replacing a published definition returns it to DRAFT; the published version stays executable. */
  update(id: string, request: WorkflowRequest): Observable<WorkflowResponse> {
    return this.http.put<WorkflowResponse>(
      `${this.base}/${encodeURIComponent(id)}`,
      request,
    );
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${encodeURIComponent(id)}`);
  }

  /** Validates and snapshots an immutable version. Fails with 422 and a detail list when invalid. */
  publish(id: string): Observable<PublishResponse> {
    return this.http.post<PublishResponse>(
      `${this.base}/${encodeURIComponent(id)}/publish`,
      {},
    );
  }

  validate(id: string): Observable<ValidationResponse> {
    return this.http.post<ValidationResponse>(
      `${this.base}/${encodeURIComponent(id)}/validate`,
      {},
    );
  }

  archive(id: string): Observable<WorkflowResponse> {
    return this.http.post<WorkflowResponse>(
      `${this.base}/${encodeURIComponent(id)}/archive`,
      {},
    );
  }

  /**
   * Starts the published version.
   *
   * With `async` the engine answers 202 and the caller polls; without it the response is the finished
   * execution. Both go through the same engine, so the only difference to the UI is whether it needs
   * to poll.
   */
  execute(
    id: string,
    request: ExecuteWorkflowRequest,
    options: { async?: boolean; version?: number | null } = {},
  ): Observable<ExecutionResponse> {
    let params = new HttpParams().set('async', String(options.async ?? false));
    if (options.version != null) {
      params = params.set('version', String(options.version));
    }
    return this.http.post<ExecutionResponse>(
      `${this.base}/${encodeURIComponent(id)}/execute`,
      request,
      { params },
    );
  }

  executions(id: string, page = 0, size = 20): Observable<Page<ExecutionResponse>> {
    const params = new HttpParams().set('page', String(page)).set('size', String(size));
    return this.http.get<Page<ExecutionResponse>>(
      `${this.base}/${encodeURIComponent(id)}/executions`,
      { params },
    );
  }

  /**
   * The workflow's change history: who created it and who has changed it since, newest first.
   *
   * The server orders it by time descending, so the caller does not sort; the top row is the most recent
   * change.
   */
  audit(id: string, page = 0, size = 50): Observable<Page<WorkflowAuditEntry>> {
    const params = new HttpParams().set('page', String(page)).set('size', String(size));
    return this.http.get<Page<WorkflowAuditEntry>>(
      `${this.base}/${encodeURIComponent(id)}/audit`,
      { params },
    );
  }

  /** Bulk control over every in-flight execution of a workflow. */
  bulkControl(id: string, action: 'pause' | 'resume' | 'cancel'): Observable<BulkControlResponse> {
    return this.http.post<BulkControlResponse>(
      `${this.base}/${encodeURIComponent(id)}/${action}`,
      {},
    );
  }

  // ---- Import / export ----

  /**
   * Exports a workflow to an encrypted `.orchpilot` file.
   *
   * The response is a binary blob; the filename the server suggests travels in `Content-Disposition`, so the
   * whole response is observed to read that header rather than guessing a name here.
   */
  export(id: string, request: ExportRequest): Observable<{ blob: Blob; fileName: string }> {
    return this.http
      .post(`${this.base}/${encodeURIComponent(id)}/export`, request, {
        observe: 'response',
        responseType: 'blob',
      })
      .pipe(
        map((response) => ({
          blob: response.body ?? new Blob(),
          fileName: this.fileNameFrom(response.headers.get('Content-Disposition'), id),
        })),
      );
  }

  /** Validates an uploaded `.orchpilot` file without importing it, returning a preview. */
  importValidate(file: File, password?: string | null): Observable<ImportValidationResult> {
    return this.http.post<ImportValidationResult>(
      `${this.base}/import/validate`,
      this.importForm(file, password),
    );
  }

  /** Imports an uploaded `.orchpilot` file, creating a new workflow in the caller's tenant. */
  import(file: File, password?: string | null): Observable<ImportResult> {
    return this.http.post<ImportResult>(`${this.base}/import`, this.importForm(file, password));
  }

  private importForm(file: File, password?: string | null): FormData {
    const form = new FormData();
    form.append('file', file);
    if (password) {
      form.append('password', password);
    }
    return form;
  }

  // ---- Friendly scheduler ----

  /** Previews a schedule: the generated cron, a plain-English description, and the next few run times. */
  schedulePreview(
    schedule: ScheduleConfig,
    timezone: string | null,
    count = 5,
  ): Observable<SchedulePreview> {
    return this.http.post<SchedulePreview>(`${this.base}/schedule/preview`, {
      schedule,
      timezone,
      count,
    });
  }

  /** Reconstructs a friendly configuration from an existing cron, for editing a legacy schedule. */
  scheduleParse(cron: string, timezone: string | null): Observable<ScheduleParseResult> {
    return this.http.post<ScheduleParseResult>(`${this.base}/schedule/parse`, { cron, timezone });
  }

  /** Parses the download filename from a Content-Disposition header, falling back to a sensible default. */
  private fileNameFrom(header: string | null, id: string): string {
    const match = header?.match(/filename="?([^"]+)"?/i);
    return match?.[1] ?? `${id}.orchpilot`;
  }
}
