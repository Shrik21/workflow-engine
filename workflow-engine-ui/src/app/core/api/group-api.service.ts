import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import {
  CreateGroupRequest,
  Group,
  GroupMember,
  GroupSummary,
  MyWorkflowPermissions,
  PermissionCatalogue,
  UpdateGroupRequest,
  WorkflowAccess,
  WorkflowPermission,
} from '../models/group.models';
import { API_BASE_URL } from './api-base';

/**
 * Client for `/api/groups` and the per-workflow access endpoints.
 *
 * Managing groups requires ADMIN and answers 403 otherwise. Two endpoints are open to any authenticated
 * user, and deliberately so: the picker feed and the permission catalogue, because sharing a workflow you
 * own means choosing a group by name.
 */
@Injectable({ providedIn: 'root' })
export class GroupApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private readonly groupsUrl = `${this.base}/api/groups`;

  private readonly catalogueState = signal<PermissionCatalogue>({});

  /** The server's permission catalogue, so the editor never hardcodes the list. */
  readonly catalogue = this.catalogueState.asReadonly();

  list(search?: string | null): Observable<Group[]> {
    let params = new HttpParams();
    if (search?.trim()) {
      params = params.set('search', search.trim());
    }
    return this.http.get<Group[]>(this.groupsUrl, { params });
  }

  /** Enabled groups only, with no membership information. Usable by any authenticated caller. */
  available(): Observable<GroupSummary[]> {
    return this.http.get<GroupSummary[]>(`${this.groupsUrl}/available`);
  }

  loadCatalogue(): Observable<PermissionCatalogue> {
    return this.http
      .get<PermissionCatalogue>(`${this.groupsUrl}/permissions`)
      .pipe(tap((catalogue) => this.catalogueState.set(catalogue)));
  }

  get(groupId: string): Observable<Group> {
    return this.http.get<Group>(`${this.groupsUrl}/${encodeURIComponent(groupId)}`);
  }

  create(request: CreateGroupRequest): Observable<Group> {
    return this.http.post<Group>(this.groupsUrl, request);
  }

  update(groupId: string, request: UpdateGroupRequest): Observable<Group> {
    return this.http.put<Group>(`${this.groupsUrl}/${encodeURIComponent(groupId)}`, request);
  }

  /** Replaces the whole set, so the caller states the intended final state. */
  setPermissions(groupId: string, permissions: WorkflowPermission[]): Observable<Group> {
    return this.http.put<Group>(`${this.groupsUrl}/${encodeURIComponent(groupId)}/permissions`, {
      permissions,
    });
  }

  delete(groupId: string): Observable<void> {
    return this.http.delete<void>(`${this.groupsUrl}/${encodeURIComponent(groupId)}`);
  }

  members(groupId: string): Observable<GroupMember[]> {
    return this.http.get<GroupMember[]>(`${this.groupsUrl}/${encodeURIComponent(groupId)}/members`);
  }

  addMember(groupId: string, userId: string): Observable<void> {
    return this.http.post<void>(
      `${this.groupsUrl}/${encodeURIComponent(groupId)}/members/${encodeURIComponent(userId)}`,
      {},
    );
  }

  removeMember(groupId: string, userId: string): Observable<void> {
    return this.http.delete<void>(
      `${this.groupsUrl}/${encodeURIComponent(groupId)}/members/${encodeURIComponent(userId)}`,
    );
  }

  // -------------------------------------------------------- per-workflow access

  workflowAccess(workflowId: string): Observable<WorkflowAccess> {
    return this.http.get<WorkflowAccess>(
      `${this.base}/api/workflows/${encodeURIComponent(workflowId)}/access`,
    );
  }

  /** Requires ownership or ADMIN, deliberately not WORKFLOW_EDIT. */
  setWorkflowAccess(workflowId: string, groupIds: string[]): Observable<WorkflowAccess> {
    return this.http.put<WorkflowAccess>(
      `${this.base}/api/workflows/${encodeURIComponent(workflowId)}/access`,
      { groupIds },
    );
  }

  myPermissions(workflowId: string): Observable<MyWorkflowPermissions> {
    return this.http.get<MyWorkflowPermissions>(
      `${this.base}/api/workflows/${encodeURIComponent(workflowId)}/my-permissions`,
    );
  }
}
