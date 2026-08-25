import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-base';
import { ExternalSubmitResult, PublicFormView } from '../models/public-form.models';

/**
 * Client for `/api/public/forms` — the customer-facing form API.
 *
 * No permission, no JWT: the secure token in the path is the only credential, and the server resolves
 * everything else from it. Used by the chromeless public form page, which an external customer reaches without
 * an OrchPilot account.
 */
@Injectable({ providedIn: 'root' })
export class PublicFormApiService {
  private static readonly PATH = '/api/public/forms';

  private readonly base = inject(API_BASE_URL) + PublicFormApiService.PATH;
  private readonly http = inject(HttpClient);

  open(token: string): Observable<PublicFormView> {
    return this.http.get<PublicFormView>(`${this.base}/${encodeURIComponent(token)}`);
  }

  saveDraft(token: string, data: Record<string, unknown>): Observable<{ saved: boolean }> {
    return this.http.post<{ saved: boolean }>(
      `${this.base}/${encodeURIComponent(token)}/draft`,
      { data },
    );
  }

  submit(token: string, data: Record<string, unknown>): Observable<ExternalSubmitResult> {
    return this.http.post<ExternalSubmitResult>(
      `${this.base}/${encodeURIComponent(token)}/submit`,
      { data },
    );
  }
}
