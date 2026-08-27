import { FormVersion } from './form.models';

/** Where a task is in its life. Mirrors the server's `TaskStatus`. */
export type TaskStatus =
  | 'OPEN'
  | 'ASSIGNED'
  | 'PAUSED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'EXPIRED'
  | 'TERMINATED';

export type TaskPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT';

/** Which set of tasks to list. */
export type TaskBucket = 'mine' | 'available' | 'all';

/**
 * One row in the inbox.
 *
 * Carries no form values, by design on the server: a list of twenty approvals would otherwise ship everything
 * twenty people typed to a screen that only shows their titles.
 */
export interface TaskSummary {
  taskId: string;
  executionId: string;
  workflowId: string;
  workflowName: string | null;
  workflowVersion: number;
  nodeId: string;
  taskName: string | null;
  description: string | null;
  status: TaskStatus;
  priority: TaskPriority;
  assigneeUsername: string | null;
  assignedToMe: boolean;
  claimable: boolean;
  candidateGroups: number;
  formDefinitionId: string | null;
  formVersion: number;
  createdAt: string | null;
  dueAt: string | null;
  expiresAt: string | null;
  overdue: boolean;
  hasDraft: boolean;
  completedAt: string | null;
  completedBy: string | null;
  /** True when this task is completed by an external customer through a secure form link. */
  external: boolean;
}

/**
 * What this user may do to a task.
 *
 * A courtesy for rendering, not a control: every one of these is checked again by the endpoint that performs
 * the action, so a client that ignored them would simply be refused.
 */
export interface TaskCapabilities {
  claim: boolean;
  release: boolean;
  complete: boolean;
  saveDraft: boolean;
  reassign: boolean;
  cancel: boolean;
}

export interface TaskHistoryEntry {
  action: string | null;
  actor: string | null;
  at: string | null;
  comment: string | null;
  details: Record<string, unknown>;
}

/** One task, with everything needed to render and submit it. */
export interface TaskDetail {
  task: TaskSummary;
  /** The pinned form version to render, or null when the node references no published form. */
  form: FormVersion | null;
  /** Why `form` is null, when it is. Written to be shown to the user. */
  formIssue: string | null;
  /** What to put in the controls: the workflow's prefill, overlaid with any saved draft. */
  initialData: Record<string, unknown>;
  /** Present only once the task is complete. */
  submittedData: Record<string, unknown>;
  capabilities: TaskCapabilities;
  history: TaskHistoryEntry[];
}

/** Counts for the bucket tabs. `all` is absent unless the user holds TASK_VIEW_ALL. */
export interface TaskCounts {
  mine?: number;
  available?: number;
  all?: number;
  overdue?: number;
}

/** One selectable assignee, from `/api/users/available`. */
export interface AssignableUser {
  userId: string;
  username: string;
  displayName: string;
}

/** Display label for a priority, so the template holds no lookup table. */
export function priorityLabel(priority: TaskPriority): string {
  switch (priority) {
    case 'LOW':
      return 'Low';
    case 'HIGH':
      return 'High';
    case 'URGENT':
      return 'Urgent';
    default:
      return 'Normal';
  }
}

/**
 * Whether a task should draw attention.
 *
 * Overdue is decided by the server, which knows the time the deadline was measured against. Recomputing it here
 * from a timestamp would disagree with the server whenever a clock is off, and the server's answer is the one
 * the reminder was sent about.
 */
export function needsAttention(task: TaskSummary): boolean {
  return task.overdue || task.priority === 'URGENT';
}
