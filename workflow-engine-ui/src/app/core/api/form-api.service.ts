import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { Page } from '../models/api.models';
import {
  AvailableForm,
  FieldTypeCatalogue,
  FormDefinition,
  FormStatus,
  FormSummary,
  FormVersion,
} from '../models/form.models';
import { ValidationResponse } from '../models/workflow.models';
import { API_BASE_URL } from './api-base';

/** Client for `/api/forms`, plus a cache of the server's field catalogue. */
@Injectable({ providedIn: 'root' })
export class FormApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL) + '/api/forms';

  private readonly catalogueState = signal<FieldTypeCatalogue>({});
  private catalogueLoaded = false;

  /** The field types the server supports, which drives the palette and the type check. */
  readonly catalogue = this.catalogueState.asReadonly();

  /** Loads the catalogue once. */
  ensureCatalogue(): void {
    if (this.catalogueLoaded) {
      return;
    }
    this.catalogueLoaded = true;
    this.http.get<FieldTypeCatalogue>(`${this.base}/field-types`).subscribe({
      next: (catalogue) => this.catalogueState.set(catalogue ?? {}),
      // Allow a retry on the next visit rather than leaving the palette permanently empty.
      error: () => (this.catalogueLoaded = false),
    });
  }

  list(options: {
    status?: FormStatus | null;
    name?: string | null;
    page?: number;
    size?: number;
  }): Observable<Page<FormSummary>> {
    let params = new HttpParams()
      .set('page', String(options.page ?? 0))
      .set('size', String(options.size ?? 20));
    if (options.status) {
      params = params.set('status', options.status);
    }
    if (options.name?.trim()) {
      params = params.set('name', options.name.trim());
    }
    return this.http.get<Page<FormSummary>>(this.base, { params });
  }

  /**
   * The forms a workflow node can be pointed at: published, not archived, ordered by name.
   *
   * <p>Not `list({ status: 'PUBLISHED' })`. Editing a published form returns its head to DRAFT while the
   * published snapshot stays in use, so that filter would drop a usable form the moment somebody opened it in
   * the designer. The server owns that rule; asking it here would mean two places to change.
   */
  available(): Observable<AvailableForm[]> {
    return this.http.get<AvailableForm[]>(`${this.base}/available`);
  }

  get(id: string): Observable<FormDefinition> {
    return this.http.get<FormDefinition>(`${this.base}/${encodeURIComponent(id)}`);
  }

  create(form: FormDefinition): Observable<FormDefinition> {
    return this.http.post<FormDefinition>(this.base, form);
  }

  /** Replacing a published draft returns it to DRAFT; published versions stay intact. */
  update(id: string, form: FormDefinition): Observable<FormDefinition> {
    return this.http.put<FormDefinition>(`${this.base}/${encodeURIComponent(id)}`, form);
  }

  validate(id: string): Observable<ValidationResponse> {
    return this.http.post<ValidationResponse>(`${this.base}/${encodeURIComponent(id)}/validate`, {});
  }

  /** Snapshots an immutable version. Fails with 422 and a detail list when the form is not publishable. */
  publish(id: string): Observable<FormVersion> {
    return this.http.post<FormVersion>(`${this.base}/${encodeURIComponent(id)}/publish`, {});
  }

  versions(id: string): Observable<FormVersion[]> {
    return this.http.get<FormVersion[]>(`${this.base}/${encodeURIComponent(id)}/versions`);
  }

  version(id: string, version: number): Observable<FormVersion> {
    return this.http.get<FormVersion>(
      `${this.base}/${encodeURIComponent(id)}/versions/${version}`,
    );
  }

  clone(id: string, name?: string): Observable<FormDefinition> {
    let params = new HttpParams();
    if (name?.trim()) {
      params = params.set('name', name.trim());
    }
    return this.http.post<FormDefinition>(
      `${this.base}/${encodeURIComponent(id)}/clone`,
      {},
      { params },
    );
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${encodeURIComponent(id)}`);
  }
}
