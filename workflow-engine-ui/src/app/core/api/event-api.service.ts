import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { API_BASE_URL } from './api-base';
import { Observable } from 'rxjs';

export interface EmitEventRequest {
  name: string;
  payload?: Record<string, unknown> | null;
  correlationId?: string | null;
}

export interface AcceptedEvent {
  name: string;
  correlationId: string | null;
  at: string;
}

/**
 * Client for `/api/events`.
 *
 * The engine answers 202 rather than listing the executions it started, because fan-out is
 * asynchronous and telling the emitter which workflows subscribed would couple it to whichever
 * workflows happen to exist today. The UI reflects that: it confirms acceptance and points the
 * operator at the execution list.
 */
@Injectable({ providedIn: 'root' })
export class EventApiService {
  /** Path segment. Prefixed with the configured backend base URL, which is empty for same-origin. */
  private static readonly PATH = '/api/events';

  private readonly base = inject(API_BASE_URL) + EventApiService.PATH;

  private readonly http = inject(HttpClient);

  emit(request: EmitEventRequest): Observable<AcceptedEvent> {
    return this.http.post<AcceptedEvent>(this.base, request);
  }
}
