/**
 * Groups and workflow-level permissions.
 *
 * The permission list is fetched from `GET /api/groups/permissions` rather than hardcoded here. The server
 * owns the catalogue, so adding a permission to the backend enum makes it appear in the group editor with no
 * front-end change, and the two can never disagree about what exists.
 */

/** A workflow permission name. Kept as a string because the catalogue is server-driven. */
export type WorkflowPermission = string;

/** One permission as published by the server, with its display label. */
export interface PermissionOption {
  name: WorkflowPermission;
  label: string;
}

/** The catalogue, keyed by category: `Workflow`, `Execution`, `Version`. */
export type PermissionCatalogue = Record<string, PermissionOption[]>;

export interface Group {
  id: string;
  name: string;
  description: string | null;
  permissions: WorkflowPermission[];
  enabled: boolean;
  memberCount: number;
  createdBy: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

/** The minimal shape returned by the picker feed. Carries no membership information. */
export interface GroupSummary {
  id: string;
  name: string;
  description: string | null;
  enabled: boolean;
}

export interface GroupMember {
  userId: string;
  username: string;
  email: string;
  displayName: string;
  enabled: boolean;
  joinedAt: string | null;
  addedBy: string | null;
}

export interface CreateGroupRequest {
  name: string;
  description?: string | null;
  permissions: WorkflowPermission[];
  enabled: boolean;
}

export interface UpdateGroupRequest {
  name?: string | null;
  description?: string | null;
  enabled?: boolean | null;
}

/** The groups a workflow is shared with. */
export interface WorkflowAccess {
  workflowId: string;
  groups: GroupSummary[];
  /** Attached ids that no longer resolve, surfaced so a deleted group is a visible cause of lost access. */
  unresolvedGroupIds: string[];
  ownerId: string | null;
}

/**
 * What the current user may do to one workflow.
 *
 * For presentation only. The server re-checks every operation, so hiding a button is a courtesy rather than
 * a control, and a client that ignored this would still be refused.
 */
export interface MyWorkflowPermissions {
  workflowId: string;
  permissions: WorkflowPermission[];
  /** The groups that actually granted something, which answers "why do I have this". */
  groups: GroupSummary[];
  owner: boolean;
  admin: boolean;
}

/** An empty permission set, for use before the first load resolves. */
export const NO_PERMISSIONS: MyWorkflowPermissions = {
  workflowId: '',
  permissions: [],
  groups: [],
  owner: false,
  admin: false,
};
