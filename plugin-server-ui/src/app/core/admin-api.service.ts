import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

/** An account, as the registry describes one. There is no password field, by design. */
export interface RegistryUser {
  id: string;
  username: string;
  email: string;
  firstName: string | null;
  lastName: string | null;
  displayName: string;
  roles: string[];
  permissions: string[];
  enabled: boolean;
  accountLocked: boolean;
  serviceAccount: boolean;
  mustChangePassword: boolean;
  createdAt: string | null;
  updatedAt: string | null;
  lastLoginAt: string | null;
}

/** A role and what it grants. */
export interface RegistryRole {
  id: string;
  name: string;
  description: string | null;
  permissions: string[];
  systemRole: boolean;
  /** How many accounts hold it, so the blast radius of an edit is visible before making it. */
  userCount: number;
  createdAt: string | null;
  updatedAt: string | null;
}

/** One permission this registry implements, grouped for a role editor. */
export interface RegistryPermission {
  name: string;
  description: string;
  group: string;
  groupLabel: string;
}

/** One entry in the security trail. */
export interface SecurityAuditEntry {
  id: string;
  userId: string | null;
  username: string | null;
  action: string;
  resource: string | null;
  resourceId: string | null;
  timestamp: string | null;
  ipAddress: string | null;
  userAgent: string | null;
  success: boolean;
  details: Record<string, unknown>;
}

/** A Spring Data page. */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/** What is supplied to create an account. */
export interface NewUserRequest {
  username: string;
  email: string;
  firstName?: string;
  lastName?: string;
  password: string;
  roles: string[];
  mustChangePassword?: boolean;
}

/**
 * Accounts, roles, permissions and the security trail.
 *
 * <p>Separate from the plugin API service because these are a different job against a different set of
 * permissions: somebody administering accounts is rarely the same person publishing plugins, and the two
 * screens should not share a client that grew to serve both.
 */
@Injectable({ providedIn: 'root' })
export class AdminApiService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.registryBaseUrl;

  // ------------------------------------------------------------------- users

  listUsers(): Observable<RegistryUser[]> {
    return this.http.get<RegistryUser[]>(`${this.base}/api/users`);
  }

  getUser(id: string): Observable<RegistryUser> {
    return this.http.get<RegistryUser>(`${this.base}/api/users/${encodeURIComponent(id)}`);
  }

  createUser(request: NewUserRequest): Observable<RegistryUser> {
    return this.http.post<RegistryUser>(`${this.base}/api/users`, request);
  }

  updateUser(id: string, changes: Partial<NewUserRequest>): Observable<RegistryUser> {
    return this.http.put<RegistryUser>(`${this.base}/api/users/${encodeURIComponent(id)}`, changes);
  }

  /** Enabling and disabling are separate endpoints, so neither can be reached by a mistyped boolean. */
  setUserEnabled(id: string, enabled: boolean): Observable<RegistryUser> {
    const action = enabled ? 'enable' : 'disable';
    return this.http.post<RegistryUser>(
      `${this.base}/api/users/${encodeURIComponent(id)}/${action}`,
      {},
    );
  }

  resetPassword(id: string, newPassword: string): Observable<RegistryUser> {
    return this.http.post<RegistryUser>(
      `${this.base}/api/users/${encodeURIComponent(id)}/reset-password`,
      { newPassword },
    );
  }

  deleteUser(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/api/users/${encodeURIComponent(id)}`);
  }

  // ------------------------------------------------------------------- roles

  listRoles(): Observable<RegistryRole[]> {
    return this.http.get<RegistryRole[]>(`${this.base}/api/roles`);
  }

  createRole(name: string, description: string, permissions: string[]): Observable<RegistryRole> {
    return this.http.post<RegistryRole>(`${this.base}/api/roles`, { name, description, permissions });
  }

  updateRole(
    id: string,
    name: string,
    description: string,
    permissions: string[],
  ): Observable<RegistryRole> {
    return this.http.put<RegistryRole>(`${this.base}/api/roles/${encodeURIComponent(id)}`, {
      name,
      description,
      permissions,
    });
  }

  deleteRole(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/api/roles/${encodeURIComponent(id)}`);
  }

  /** Every permission the registry implements. A role editor may offer nothing outside this list. */
  listPermissions(): Observable<RegistryPermission[]> {
    return this.http.get<RegistryPermission[]>(`${this.base}/api/permissions`);
  }

  // ------------------------------------------------------------------- audit

  /**
   * The security trail.
   *
   * @param filters one of username, action or outcome; the registry applies them one at a time
   */
  listAudit(
    filters: { username?: string; action?: string; success?: boolean; page?: number } = {},
  ): Observable<Page<SecurityAuditEntry>> {
    let params = new HttpParams().set('page', String(filters.page ?? 0)).set('size', '50');
    if (filters.username) {
      params = params.set('username', filters.username);
    }
    if (filters.action) {
      params = params.set('action', filters.action);
    }
    if (filters.success !== undefined) {
      params = params.set('success', String(filters.success));
    }
    return this.http.get<Page<SecurityAuditEntry>>(`${this.base}/api/security/audit`, { params });
  }

  auditActions(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/api/security/audit/actions`);
  }

  // -------------------------------------------------------------- own account

  changePassword(currentPassword: string, newPassword: string): Observable<void> {
    return this.http.post<void>(`${this.base}/api/auth/change-password`, {
      currentPassword,
      newPassword,
    });
  }

  passwordPolicy(): Observable<{ rules: string[]; minLength: number; registrationEnabled: boolean }> {
    return this.http.get<{ rules: string[]; minLength: number; registrationEnabled: boolean }>(
      `${this.base}/api/auth/password-policy`,
    );
  }
}
