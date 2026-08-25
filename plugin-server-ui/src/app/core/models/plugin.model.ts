/**
 * The registry's vocabulary, as this console reads it.
 *
 * <h2>Mirrored, not shared</h2>
 *
 * These types restate what the registry's DTOs emit rather than being generated from them. That looks like
 * duplication and is deliberate: this is the wire contract between two independently deployable things, and a
 * generated or shared type makes them one deployable in practice — the registry could not add a field without
 * rebuilding this console, and a console running an older release would fail on a payload it should ignore.
 *
 * The rule that follows is that unknown fields are ignored and absent ones are optional. Nothing here should be
 * made required unless the registry genuinely cannot omit it.
 */

/**
 * A version's place in its lifecycle.
 *
 * `DRAFT` is the state an upload lands in when the registry is configured to require publication, and no
 * workflow service can see it until it is published. The rest are the states of something already visible.
 */
export type PluginStatus = 'DRAFT' | 'ACTIVE' | 'INACTIVE' | 'DEPRECATED' | 'REVOKED';

/** Every status, in the order a filter should offer them. */
export const PLUGIN_STATUSES: readonly PluginStatus[] = [
  'DRAFT',
  'ACTIVE',
  'INACTIVE',
  'DEPRECATED',
  'REVOKED',
] as const;

/**
 * What kind of extension a plugin is.
 *
 * Typed as a union of the kinds the SDK defines plus `string`, because the registry stores whatever the
 * manifest declared. A plugin from a future SDK naming a kind this console has never heard of must still
 * appear in the list rather than vanishing from it.
 */
export type PluginType = 'NODE' | 'UTILITY' | 'INTEGRATION' | 'TRIGGER' | (string & {});

export const PLUGIN_TYPES: readonly string[] = ['NODE', 'UTILITY', 'INTEGRATION', 'TRIGGER'] as const;

/** One plugin, as the list shows it. `GET /api/plugins` returns a page of these. */
export interface Plugin {
  pluginId: string;
  name: string | null;
  description: string | null;
  vendor: string | null;
  pluginType: PluginType | null;
  /** Newest published release. Null while every version is a draft. */
  latestVersion: string | null;
  status: PluginStatus;
  versionCount: number;
  createdAt: string | null;
  updatedAt: string | null;
}

/** One node type a plugin contributes, with everything a designer would need to render it. */
export interface PluginNode {
  nodeType: string;
  displayName: string | null;
  description: string | null;
  category: string | null;
  icon: string | null;
  configurationSchema: Record<string, unknown>;
  inputPorts: string[];
  outputPorts: string[];
}

/** A third-party library the plugin declares. Recorded for review; the registry does not resolve it. */
export interface PluginDependency {
  groupId: string;
  artifactId: string;
  version: string;
  /** `bundled` when the archive ships it, which is the usual case. */
  scope: string | null;
}

/** One version, in full. `GET /api/plugins/{id}/versions/{version}`. */
export interface PluginVersionDetail {
  pluginId: string;
  version: string;
  status: PluginStatus;
  name: string | null;
  description: string | null;
  mainClass: string | null;
  sdkVersion: string | null;
  javaVersion: string | null;
  /** A range such as `>=1.0.0 <2.0.0`, or null when the plugin declares no constraint. */
  engineCompatibility: string | null;
  checksum: string | null;
  fileName: string | null;
  fileSize: number;
  signed: boolean;
  nodes: PluginNode[];
  dependencies: PluginDependency[];
  uploadedAt: string | null;
  uploadedBy: string | null;
  publishedAt: string | null;
  revocationReason: string | null;
}

/** One version, as a table row. `GET /api/plugins/{id}/versions`. */
export interface PluginVersionSummary {
  pluginId: string;
  version: string;
  status: PluginStatus;
  sdkVersion: string | null;
  checksum: string | null;
  fileSize: number;
  nodeTypes: string[];
  uploadedAt: string | null;
  uploadedBy: string | null;
}

/** What the registry answers with after an archive is stored. */
export interface PluginUploadResult {
  pluginId: string;
  version: string;
  status: PluginStatus;
  checksum: string;
  nodeTypes: string[];
  /** Present when the version landed in DRAFT: what to do next to make it installable. */
  nextStep: string | null;
}

export type AuditAction =
  | 'UPLOADED'
  | 'PUBLISHED'
  | 'DEACTIVATED'
  | 'DEPRECATED'
  | 'REVOKED'
  | 'DELETED'
  | 'DOWNLOADED'
  | (string & {});

/** One entry from the registry's audit trail. */
export interface PluginAuditEvent {
  id: string;
  pluginId: string;
  version: string | null;
  action: AuditAction;
  actor: string | null;
  outcome: string | null;
  at: string | null;
  details: Record<string, unknown>;
}

/** A Spring Data page, as the registry serialises it. */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}

/** The counts across the top of the list page. Derived here; the registry publishes no summary endpoint. */
export interface RegistrySummary {
  totalPlugins: number;
  active: number;
  versions: number;
  deprecated: number;
}

/**
 * @param plugins the plugins in hand
 * @returns the dashboard counts
 */
export function summarise(plugins: readonly Plugin[]): RegistrySummary {
  return {
    totalPlugins: plugins.length,
    active: plugins.filter((plugin) => plugin.status === 'ACTIVE').length,
    versions: plugins.reduce((total, plugin) => total + (plugin.versionCount ?? 0), 0),
    deprecated: plugins.filter((plugin) => plugin.status === 'DEPRECATED').length,
  };
}

/** @returns a plugin's display name, falling back to its id when the manifest named none */
export function displayNameOf(plugin: Pick<Plugin, 'pluginId' | 'name'>): string {
  return plugin.name && plugin.name.trim().length > 0 ? plugin.name : plugin.pluginId;
}
