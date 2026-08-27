import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Page } from '../models/api.models';
import { Role, UserProfile } from '../auth/auth.models';
import { API_BASE_URL } from './api-base';

export interface CreateUserRequest {
  username: string;
  email: string;
  password: string;
  firstName?: string | null;
  lastName?: string | null;
  roles: Role[];
  enabled: boolean;
}

export interface UpdateUserRequest {
  email?: string | null;
  firstName?: string | null;
  lastName?: string | null;
}

/** One security audit record, as returned to administrators. */
export interface SecurityAuditRecord {
  id: string;
  event: string;
  userId: string | null;
  username: string | null;
  actorId: string | null;
  actorUsername: string | null;
  success: boolean;
  reason: string | null;
  ipAddress: string | null;
  path: string | null;
  at: string | null;
  details: Record<string, unknown>;
}

/** Client for `/api/admin/users`. ADMIN only; every call answers 403 otherwise. */
@Injectable({ providedIn: 'root' })
export class UserAdminApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL) + '/api/admin/users';

  list(options: {
    search?: string | null;
    role?: Role | null;
    page?: number;
    size?: number;
  }): Observable<Page<UserProfile>> {
    let params = new HttpParams()
      .set('page', String(options.page ?? 0))
      .set('size', String(options.size ?? 20));
    if (options.search?.trim()) {
      params = params.set('search', options.search.trim());
    }
    if (options.role) {
      params = params.set('role', options.role);
    }
    return this.http.get<Page<UserProfile>>(this.base, { params });
  }

  get(id: string): Observable<UserProfile> {
    return this.http.get<UserProfile>(`${this.base}/${encodeURIComponent(id)}`);
  }

  create(request: CreateUserRequest): Observable<UserProfile> {
    return this.http.post<UserProfile>(this.base, request);
  }

  update(id: string, request: UpdateUserRequest): Observable<UserProfile> {
    return this.http.put<UserProfile>(`${this.base}/${encodeURIComponent(id)}`, request);
  }

  /** Replaces the whole role set, so two concurrent edits cannot combine unexpectedly. */
  setRoles(id: string, roles: Role[]): Observable<UserProfile> {
    return this.http.put<UserProfile>(`${this.base}/${encodeURIComponent(id)}/roles`, { roles });
  }

  setStatus(id: string, enabled: boolean, reason?: string): Observable<UserProfile> {
    return this.http.put<UserProfile>(`${this.base}/${encodeURIComponent(id)}/status`, {
      enabled,
      reason: reason ?? null,
    });
  }

  lock(id: string): Observable<UserProfile> {
    return this.http.post<UserProfile>(`${this.base}/${encodeURIComponent(id)}/lock`, {});
  }

  unlock(id: string): Observable<UserProfile> {
    return this.http.post<UserProfile>(`${this.base}/${encodeURIComponent(id)}/unlock`, {});
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${encodeURIComponent(id)}`);
  }

  /** How many unrevoked refresh tokens the account holds, which is how many devices have access. */
  sessions(id: string): Observable<{ userId: string; liveSessions: number }> {
    return this.http.get<{ userId: string; liveSessions: number }>(
      `${this.base}/${encodeURIComponent(id)}/sessions`,
    );
  }

  audit(options: { userId?: string | null; event?: string | null; page?: number; size?: number } = {}) {
    let params = new HttpParams()
      .set('page', String(options.page ?? 0))
      .set('size', String(options.size ?? 50));
    if (options.userId) {
      params = params.set('userId', options.userId);
    }
    if (options.event) {
      params = params.set('event', options.event);
    }
    return this.http.get<Page<SecurityAuditRecord>>(`${this.base}/audit`, { params });
  }
}
