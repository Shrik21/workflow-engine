import { HttpClient, HttpEvent, HttpRequest } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-base';
import { StorageConsistencyReport, WorkflowFile } from '../models/storage.models';

/**
 * Client for the files attached to a workflow version.
 *
 * Files are addressed by `workflowId`, `version` and `fileId` — never by a path. The server refuses to accept or
 * return one, so there is nothing here for a caller to assemble incorrectly.
 */
@Injectable({ providedIn: 'root' })
export class WorkflowFileApiService {
  private readonly base = inject(API_BASE_URL);

  private readonly http = inject(HttpClient);

  private path(workflowId: string, version: number): string {
    return `${this.base}/api/workflows/${encodeURIComponent(workflowId)}/versions/${version}/files`;
  }

  list(workflowId: string, version: number): Observable<WorkflowFile[]> {
    return this.http.get<WorkflowFile[]>(this.path(workflowId, version));
  }

  /**
   * Uploads with progress events.
   *
   * `reportProgress` on an explicit `HttpRequest` rather than a plain `post`, because a large upload with no
   * progress indication looks indistinguishable from a hung page — and this is the one screen where multi-hundred
   * megabyte files are expected.
   */
  upload(workflowId: string, version: number, file: File): Observable<HttpEvent<WorkflowFile>> {
    const body = new FormData();
    body.append('file', file, file.name);

    const request = new HttpRequest('POST', this.path(workflowId, version), body, {
      reportProgress: true,
    });
    return this.http.request<WorkflowFile>(request);
  }

  /**
   * Downloads a file as a blob.
   *
   * A blob rather than pointing the browser at the URL directly: the endpoint needs the bearer token that the
   * HTTP interceptor attaches, and a plain anchor navigation carries no such header.
   */
  download(workflowId: string, version: number, fileId: string): Observable<Blob> {
    return this.http.get(`${this.path(workflowId, version)}/${encodeURIComponent(fileId)}`, {
      responseType: 'blob',
    });
  }

  delete(workflowId: string, version: number, fileId: string): Observable<void> {
    return this.http.delete<void>(
      `${this.path(workflowId, version)}/${encodeURIComponent(fileId)}`,
    );
  }

  consistency(workflowId: string, version: number): Observable<StorageConsistencyReport> {
    return this.http.get<StorageConsistencyReport>(`${this.path(workflowId, version)}/consistency`);
  }
}
