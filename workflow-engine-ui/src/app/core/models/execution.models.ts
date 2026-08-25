/**
 * Executions, their history, their pending signals and their logs.
 */

export type ExecutionStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'WAITING'
  | 'PAUSED'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'TERMINATED';

export type ExecutionMode = 'SYNCHRONOUS' | 'ASYNCHRONOUS' | 'SCHEDULED' | 'EVENT' | 'MANUAL';

/** Statuses after which nothing further happens. */
export const TERMINAL_STATUSES: readonly ExecutionStatus[] = [
  'COMPLETED',
  'FAILED',
  'CANCELLED',
  'TERMINATED',
];

export function isTerminal(status: ExecutionStatus | string | null | undefined): boolean {
  return TERMINAL_STATUSES.includes(status as ExecutionStatus);
}

/** The runtime state of one workflow instance, from `/api/workflow-instances/{id}`. */
export interface InstanceStatus {
  instanceId: string;
  workflowTemplateId: string;
  workflowVersion: number;
  status: ExecutionStatus;
  currentNodeId: string | null;
  statusBeforePause: ExecutionStatus | null;
  pauseReason: string | null;
  terminationReason: string | null;
  terminatedBy: string | null;
  terminatedAt: string | null;
  startedAt: string | null;
  completedAt: string | null;
  updatedAt: string | null;
}

/** One entry in an instance's lifecycle history. */
export interface InstanceHistoryEntry {
  at: string;
  actor: string;
  event: string;
  outcome: string;
  details: Record<string, unknown>;
}

/** One node attempt. */
export interface NodeHistoryView {
  nodeId: string;
  nodeType: string;
  nodeName: string | null;
  status: string;
  attempt: number;
  startedAt: string | null;
  completedAt: string | null;
  durationMillis: number;
  selectedBranch: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  pluginId: string | null;
  pluginVersion: string | null;
  outputs: Record<string, unknown>;
}

/**
 * What a WAITING execution is waiting for.
 *
 * `payload.prefill` carries the resolved input mapping, which is what lets the inbox render a
 * populated form without a second request. `payload.fields` carries optional field descriptors from
 * the node's configuration; when absent the inbox falls back to free-form key/value entry.
 */
export interface PendingSignalView {
  nodeId: string;
  type: string;
  formId: string | null;
  reason: string | null;
  requestedAt: string | null;
  expiresAt: string | null;
  payload: Record<string, unknown>;
}

/** A form field descriptor, when a form node declares one. */
export interface FormFieldDescriptor {
  name: string;
  type?: string;
  label?: string;
  required?: boolean;
  options?: string[];
}

export interface ExecutionErrorView {
  code: string;
  message: string;
  nodeId: string | null;
  at: string | null;
}

export interface ExecutionResponse {
  executionId: string;
  workflowId: string;
  workflowVersion: number;
  workflowName: string | null;
  status: ExecutionStatus;
  mode: ExecutionMode | string;
  currentNodeId: string | null;
  /** Type and start time of the node executing right now — set before it finishes, so long nodes are visible. */
  currentNodeType: string | null;
  currentNodeStartedAt: string | null;
  output: Record<string, unknown>;
  variables: Record<string, unknown>;
  nodeHistory: NodeHistoryView[];
  pendingSignal: PendingSignalView | null;
  error: ExecutionErrorView | null;
  stepCount: number;
  startedAt: string | null;
  completedAt: string | null;
  updatedAt: string | null;
  correlationId: string | null;
}

/** Payload for starting a workflow. */
export interface ExecuteWorkflowRequest {
  input?: Record<string, unknown> | null;
  /** Satisfies a form node reached immediately, so a fully specified run need not park. */
  formData?: Record<string, unknown> | null;
  correlationId?: string | null;
  /** Makes starting idempotent: a repeat returns the existing execution. */
  idempotencyKey?: string | null;
  mode?: string | null;
}

/** Payload that satisfies a parked form node and resumes the execution. */
export interface FormSubmissionRequest {
  nodeId?: string | null;
  formId?: string | null;
  data: Record<string, unknown>;
  async?: boolean | null;
}

export type LogLevel = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';

export interface ExecutionLogResponse {
  sequence: number;
  at: string | null;
  level: LogLevel | string;
  nodeId: string | null;
  nodeType: string | null;
  message: string;
  details: Record<string, unknown>;
}
