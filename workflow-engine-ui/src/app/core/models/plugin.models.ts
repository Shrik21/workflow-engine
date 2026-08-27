/**
 * Plugins, their versions and their invocation history.
 */

export type PluginStatus = 'INSTALLED' | 'ACTIVE' | 'INACTIVE' | 'FAILED' | 'DELETED';

/**
 * One installed plugin version.
 *
 * `status` is what the database records; `loaded` is whether this engine instance has it in a class
 * loader right now. They differ in exactly the cases an operator needs to know about: a version
 * marked ACTIVE that failed to load reports `status: ACTIVE, loaded: false` with `loadError` set.
 * The UI surfaces both rather than collapsing them into one badge.
 */
export interface PluginVersionResponse {
  pluginId: string;
  version: string;
  name: string | null;
  description: string | null;
  pluginType: string | null;
  status: PluginStatus;
  mainClass: string | null;
  apiVersion: number;
  jarFileName: string | null;
  jarSizeBytes: number;
  sha256: string | null;
  signed: boolean;
  nodeTypes: string[];
  allowedHosts: string[];
  secretScopes: string[];
  eventsEnabled: boolean;
  settings: Record<string, unknown>;
  dependencies: string[];
  loaded: boolean;
  activeCalls: number;
  totalCalls: number;
  failedCalls: number;
  uploadedAt: string | null;
  uploadedBy: string | null;
  lastLoadedAt: string | null;
  loadError: string | null;
}

export interface PluginResponse {
  id: string;
  name: string | null;
  description: string | null;
  pluginType: string | null;
  status: PluginStatus;
  latestVersion: string | null;
  /** Version that serves workflow nodes which do not pin one. */
  defaultVersion: string | null;
  versions: PluginVersionResponse[];
  createdAt: string | null;
  updatedAt: string | null;
}

/** One recorded plugin invocation. Payloads are the redacted copies the engine persisted. */
export interface PluginExecutionResponse {
  id: string;
  executionId: string;
  workflowId: string;
  nodeId: string;
  nodeType: string;
  pluginId: string;
  pluginVersion: string;
  status: string;
  attempt: number;
  startTime: string | null;
  endTime: string | null;
  durationMillis: number;
  request: Record<string, unknown>;
  response: Record<string, unknown>;
  errorCode: string | null;
  errorMessage: string | null;
}

/**
 * What the operator supplies alongside the JAR.
 *
 * Permissions are granted here rather than declared by the plugin, which is the whole point: a
 * plugin cannot widen its own reach. The upload form makes that explicit rather than hiding it
 * behind a default.
 */
export interface PluginUploadOptions {
  file: File;
  pluginId?: string;
  version?: string;
  mainClass?: string;
  description?: string;
  /** Hosts the plugin may call. Empty denies all outbound calls. */
  allowedHosts: string[];
  /** Secret name prefixes the plugin may read. Empty denies all secret access. */
  secretScopes: string[];
  expectedSha256?: string;
  activate: boolean;
  eventsEnabled: boolean;
}

export interface DeletePluginResponse {
  pluginId: string;
  version: string | null;
  deleted: number;
}

/** Secret metadata. There is no endpoint that returns a value, and no field here could hold one. */
export interface SecretSummary {
  name: string;
  description: string | null;
  allowedPlugins: string[];
  keyId: string | null;
  updatedAt: string | null;
  readCount: number;
}

export interface SecretRequest {
  value: string;
  description?: string | null;
  allowedPlugins?: string[] | null;
}

export interface SecretStatus {
  configured: boolean;
  count: number;
}
