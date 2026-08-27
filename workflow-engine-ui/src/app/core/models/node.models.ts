/**
 * The node catalogue, and the configuration schema dialect the designer renders.
 *
 * This file is the contract that makes the front end independent of the plugin release cycle. The
 * engine returns built-in and plugin-contributed node types in one shape, each carrying its own
 * schema, so the designer can render a palette entry and a property panel for a node type that did
 * not exist when this application was built. Nothing here names a specific integration.
 */

export type NodeSource = 'BUILT_IN' | 'PLUGIN';

/** One entry from `GET /api/nodes`. */
export interface NodeCatalogEntry {
  nodeType: string;
  displayName: string;
  category: string;
  icon: string | null;
  description: string;
  source: NodeSource;
  pluginId: string | null;
  pluginVersion: string | null;
  configurationSchema: ConfigSchema;
  outputPorts: string[];
  outputVariables: string[];
  idempotent: boolean;
  supportsRetry: boolean;
}

/**
 * The JSON-schema subset produced by the SDK's `SchemaBuilder`.
 *
 * Deliberately typed as a subset rather than as full JSON Schema: the engine only ever emits these
 * constructs, and pretending to support the whole specification would mean a renderer that silently
 * mishandles the parts it does not implement.
 */
export interface ConfigSchema {
  type?: string;
  properties?: Record<string, ConfigProperty>;
  required?: string[];
}

export interface ConfigProperty {
  /** `string`, `integer`, `number`, `boolean` or `object`. */
  type?: string;
  /** Label shown in the property panel. */
  title?: string;
  /** Help text shown beneath the control. */
  description?: string;
  /** `textarea` for multi-line text, `secret-ref` for a secret picker. */
  format?: string;
  /** Present for single-choice fields, rendered as a dropdown. */
  enum?: string[];
  default?: unknown;
  /** Present on free-form maps: `{ type: 'string' }`. */
  additionalProperties?: { type?: string };
  /**
   * Shows this field only when another field holds one of the listed values.
   *
   * `{ operation: ['FIND_MANY'] }` reads as "only when operation is FIND_MANY". A plugin whose node has an
   * operation selector — MongoDB's read node offers five, each needing different fields — would otherwise
   * put every field for every operation on screen at once.
   *
   * Presentation only, and the schema form treats it as such: a hidden field keeps whatever value it had,
   * and the plugin still reads it. Nothing here is a permission check.
   */
  visibleWhen?: Record<string, string[]>;
}

/** The node types the engine implements itself. Used only to offer sensible designer defaults. */
export const BUILT_IN_NODE_TYPES = {
  start: 'START',
  form: 'FORM',
  decision: 'DECISION',
  end: 'END',
  plugin: 'PLUGIN',
} as const;

/**
 * Finds the catalogue entry that describes a workflow node.
 *
 * Node type alone is not enough. A workflow may name a plugin node in either of two ways, and both are
 * legal:
 *
 * - by its contributed node type, such as `SENDGRID_EMAIL`, which matches the catalogue directly;
 * - by the generic `PLUGIN` marker type plus a `pluginId`, which is the form the engine's own examples
 *   use and which matches no catalogue entry at all, because `PLUGIN` is a marker rather than a
 *   contributed type.
 *
 * Without the second case, a perfectly valid workflow renders every plugin node as unavailable and its
 * property panel shows no schema. Resolution therefore falls back to the plugin coordinate.
 *
 * @param node    the node to describe, as far as its type and plugin coordinate
 * @param entries the current catalogue
 */
export function resolveCatalogEntry(
  node: { type?: string | null; pluginId?: string | null; pluginVersion?: string | null },
  entries: readonly NodeCatalogEntry[],
): NodeCatalogEntry | undefined {
  const nodeType = node.type ?? '';
  const pluginId = node.pluginId ?? null;

  if (nodeType && nodeType !== BUILT_IN_NODE_TYPES.plugin) {
    // Prefer an entry that agrees with the node's plugin id, so two plugins contributing the same node
    // type resolve to the one the node actually pins.
    const exact = entries.filter((entry) => entry.nodeType === nodeType);
    if (exact.length > 0) {
      return (pluginId && exact.find((entry) => entry.pluginId === pluginId)) || exact[0];
    }
  }

  if (pluginId) {
    const fromPlugin = entries.filter((entry) => entry.pluginId === pluginId);
    if (fromPlugin.length === 1) {
      return fromPlugin[0];
    }
    // A multi-node plugin addressed through the marker type is ambiguous, so only an exact node type
    // match is accepted rather than guessing which of its types was meant.
    return fromPlugin.find((entry) => entry.nodeType === nodeType);
  }

  return undefined;
}

/**
 * Maps a palette category to a CSS custom property, so a node is tinted by what it does.
 *
 * Unknown categories fall back to a neutral tint rather than being dropped: a plugin is free to
 * invent a category, and the correct response is to show it in grey, not to hide the node.
 */
export function categoryColorVar(category: string | null | undefined): string {
  switch ((category ?? '').toLowerCase()) {
    case 'flow':
      return 'var(--category-flow)';
    case 'human':
      return 'var(--category-human)';
    case 'communication':
      return 'var(--category-communication)';
    case 'integration':
      return 'var(--category-integration)';
    case 'data':
    case 'database':
      return 'var(--category-data)';
    case 'ai':
    case 'llm':
      return 'var(--category-ai)';
    default:
      return 'var(--category-general)';
  }
}
