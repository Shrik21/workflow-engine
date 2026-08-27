/**
 * Workflow definitions, as read from and written to the engine.
 *
 * Request and response shapes are separate types on purpose, mirroring the backend. `status`,
 * `version` and `publishedVersion` are engine-owned: they appear on the response and are absent
 * from the request, so the UI cannot publish a workflow by setting a field.
 */

export type WorkflowStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';

export type ErrorPolicy = 'FAIL_WORKFLOW' | 'SKIP' | 'CONTINUE' | 'COMPENSATE' | 'RETRY';

export type TriggerType = 'MANUAL' | 'API' | 'SCHEDULE' | 'EVENT';

/** Designer coordinates. Stored on the node but never interpreted by the engine. */
export interface Presentation {
  x?: number;
  y?: number;
  [key: string]: unknown;
}

export interface DecisionCondition {
  branch: string;
  expression: string;
  description?: string | null;
}

export interface RetryPolicy {
  enabled?: boolean | null;
  maxAttempts?: number | null;
  backoffMillis?: number | null;
  backoffMultiplier?: number | null;
  maxBackoffMillis?: number | null;
}

export interface WorkflowNode {
  id: string;
  type: string;
  name?: string | null;
  description?: string | null;
  pluginId?: string | null;
  pluginVersion?: string | null;
  configuration?: Record<string, unknown> | null;
  inputMapping?: Record<string, string> | null;
  outputMapping?: Record<string, string> | null;
  conditions?: DecisionCondition[] | null;
  defaultBranch?: string | null;
  formId?: string | null;
  /** Published form version this node pins. Null follows the newest published one. */
  formVersion?: number | null;
  waitForInput?: boolean | null;
  outputs?: string[] | null;
  retry?: RetryPolicy | null;
  errorPolicy?: ErrorPolicy | string | null;
  compensationNodeId?: string | null;
  timeoutMillis?: number | null;
  presentation?: Presentation | null;
}

export interface WorkflowConnection {
  id?: string | null;
  source: string;
  /** Branch name this edge serves. Absent means the default edge. */
  sourcePort?: string | null;
  target: string;
  label?: string | null;
  /** Optional guard expression evaluated before the edge is followed. */
  condition?: string | null;
}

/** The friendly scheduling frequency, mirroring the server's `ScheduleFrequency`. */
export type ScheduleFrequency =
  | 'EVERY_MINUTE'
  | 'EVERY_N_MINUTES'
  | 'HOURLY'
  | 'EVERY_N_HOURS'
  | 'DAILY'
  | 'WEEKLY'
  | 'SELECTED_DAYS'
  | 'MONTHLY'
  | 'SPECIFIC_DAY_OF_MONTH'
  | 'CUSTOM';

/** The user-facing schedule choices, stored alongside the generated cron. Mirrors the server's `ScheduleConfig`. */
export interface ScheduleConfig {
  frequency: ScheduleFrequency | null;
  time?: string | null;
  interval?: number | null;
  minute?: number | null;
  daysOfWeek?: string[];
  dayOfMonth?: number | null;
  lastDayOfMonth?: boolean;
  cron?: string | null;
}

/** Response from `POST /api/workflows/schedule/preview`. */
export interface SchedulePreview {
  cron: string;
  description: string;
  timezone: string | null;
  nextRuns: string[];
}

/** Response from `POST /api/workflows/schedule/parse`. */
export interface ScheduleParseResult {
  schedule: ScheduleConfig;
  description: string;
}

export interface WorkflowTrigger {
  id: string;
  type: TriggerType | string;
  enabled?: boolean | null;
  cron?: string | null;
  timezone?: string | null;
  eventName?: string | null;
  defaultInput?: Record<string, unknown> | null;
  /** The friendly schedule config, for SCHEDULE triggers created through the builder. */
  schedule?: ScheduleConfig | null;
}

/** Payload for `POST /api/workflows` and `PUT /api/workflows/{id}`. */
export interface WorkflowRequest {
  name: string;
  description?: string | null;
  nodes: WorkflowNode[];
  connections: WorkflowConnection[];
  variables?: Record<string, unknown> | null;
  triggers?: WorkflowTrigger[] | null;
  metadata?: Record<string, unknown> | null;
}

/** A workflow as returned by the engine. */
export interface WorkflowResponse {
  id: string;
  name: string;
  description: string | null;
  status: WorkflowStatus;
  version: number;
  publishedVersion: number | null;
  nodes: WorkflowNode[];
  connections: WorkflowConnection[];
  variables: Record<string, unknown>;
  triggers: WorkflowTrigger[];
  metadata: Record<string, unknown>;
  createdAt: string | null;
  updatedAt: string | null;
  publishedAt: string | null;
  createdBy: string | null;
  updatedBy: string | null;
}

/**
 * One entry in a workflow's change history.
 *
 * The server returns these newest first, each naming the user who made the change and when — the answer to
 * "who created this and who has touched it since".
 */
export interface WorkflowAuditEntry {
  at: string | null;
  actor: string | null;
  /** WORKFLOW_CREATED, WORKFLOW_UPDATED, WORKFLOW_PUBLISHED, WORKFLOW_ARCHIVED, WORKFLOW_DELETED. */
  action: string;
  outcome: string | null;
  details: Record<string, unknown>;
}

/** Result of validating without publishing. */
export interface ValidationResponse {
  valid: boolean;
  errors: string[];
  warnings: string[];
}

/** Result of publishing. */
export interface PublishResponse {
  workflowId: string;
  version: number;
  publishedAt: string | null;
  warnings: string[];
}

/** Result of a bulk pause, resume or cancel across a workflow's executions. */
export interface BulkControlResponse {
  workflowId: string;
  action: string;
  affected: string[];
  skipped: string[];
}

// ---- Import / export (the .orchpilot portability format) ----

/** What to include in an export, and how to protect it. */
export interface ExportRequest {
  includeForms: boolean;
  includeVariables: boolean;
  includePluginDependencies: boolean;
  includePermissions: boolean;
  /** `PLATFORM` encrypts under the engine master key; `PASSWORD` derives the key from a password. */
  encryptionMode: 'PLATFORM' | 'PASSWORD';
  /** Required only for `PASSWORD` mode. Sent over the request body, never stored. */
  password?: string | null;
}

/** One plugin dependency's status against what this environment has installed. */
export interface PluginDependencyStatus {
  pluginId: string;
  requiredVersion: string | null;
  installedVersion: string | null;
  compatibility: 'COMPATIBLE' | 'INCOMPATIBLE' | 'MISSING';
}

/** A reference to a credential the imported workflow needs — a name, never a secret value. */
export interface CredentialReferenceView {
  nodeId: string;
  field: string;
  type: string;
  name: string;
}

/** The preview returned by validating an .orchpilot file, before importing it. */
export interface ImportValidationResult {
  valid: boolean;
  name: string | null;
  description: string | null;
  sourceVersion: number;
  exportedBy: string | null;
  plugins: PluginDependencyStatus[];
  missingPlugins: string[];
  credentialReferences: CredentialReferenceView[];
  accessGroups: string[];
  /** True when a workflow with the file's source id already exists here — a conflict, never an overwrite. */
  conflict: boolean;
  warnings: string[];
  errors: string[];
}

/** The outcome of an import. */
export interface ImportResult {
  success: boolean;
  workflowId: string;
  workflowName: string;
  missingPlugins: string[];
  warnings: string[];
}

/** Strips the engine-owned fields, turning a loaded workflow back into an editable request. */
export function toWorkflowRequest(workflow: WorkflowResponse): WorkflowRequest {
  return {
    name: workflow.name,
    description: workflow.description,
    nodes: workflow.nodes,
    connections: workflow.connections,
    variables: workflow.variables,
    triggers: workflow.triggers,
    metadata: workflow.metadata,
  };
}
