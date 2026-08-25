import { NodeCatalogEntry } from '../../core/models/node.models';

/**
 * Grouping a plugin's node types into one palette entry with an operation list.
 *
 * A plugin contributes one node type per operation — Kubernetes declares 45, Jira 39, Excel 27. That is the
 * right shape for the engine, which needs per-operation risk flags and per-operation AI tools, but it is the
 * wrong shape for a palette: 180 rows to scroll for twelve integrations.
 *
 * So the palette groups them. One row per plugin; the operation is chosen afterwards, in the property panel.
 * Nothing about the node types themselves changes — the workflow still stores the specific `nodeType`, the
 * engine still resolves it, and per-operation risk still works. This is presentation, and only presentation.
 */

/** A single row in the palette: either one built-in node, or a plugin with its operations. */
export interface PaletteItem {
  /** Stable identity for `track`. */
  key: string;
  /** What dragging this row creates. */
  entry: NodeCatalogEntry;
  /** Row label: the plugin's name, or the node's name for a built-in. */
  label: string;
  /** Secondary line. */
  meta: string;
  /** Every operation on offer. Length 1 for a built-in or a single-node plugin. */
  operations: NodeCatalogEntry[];
}

/**
 * Collapses a catalogue into palette rows.
 *
 * Built-ins stay one row each — `START` and `DECISION` are not operations of anything, and grouping them
 * would invent a relationship that does not exist. Plugin entries group by `pluginId`, which is the only
 * identifier guaranteed to be shared across a plugin's node types; grouping by `category` instead would split
 * the GCP Kubernetes plugin in two, because it labels its cluster nodes and its workload nodes differently.
 *
 * @param entries    the catalogue, in server order — which is the plugin's own declaration order
 * @param pluginNames plugin id to display name, from the marketplace; absent names fall back gracefully
 */
export function toPaletteItems(
  entries: readonly NodeCatalogEntry[],
  pluginNames?: ReadonlyMap<string, string | null>,
): PaletteItem[] {
  const items: PaletteItem[] = [];
  const byPlugin = new Map<string, NodeCatalogEntry[]>();

  for (const entry of entries) {
    // A plugin entry with no pluginId cannot be grouped; treat it as standalone rather than dropping it.
    if (entry.source !== 'PLUGIN' || !entry.pluginId) {
      items.push({
        key: entry.nodeType,
        entry,
        label: entry.displayName,
        meta: entry.nodeType,
        operations: [entry],
      });
      continue;
    }
    const list = byPlugin.get(entry.pluginId) ?? [];
    list.push(entry);
    byPlugin.set(entry.pluginId, list);
  }

  for (const [pluginId, operations] of byPlugin) {
    const representative = defaultOperation(operations);
    const label = pluginLabel(pluginId, operations, pluginNames);
    items.push({
      key: pluginId,
      entry: representative,
      label,
      meta:
        operations.length === 1
          ? `${pluginId} ${representative.pluginVersion ?? ''}`.trim()
          : `${operations.length} operations`,
      operations,
    });
  }
  return items;
}

/**
 * The operation a freshly dragged plugin node starts on.
 *
 * Prefers an idempotent one. The catalogue's first entry is the plugin's own first declaration, which is
 * usually a read — but not always, and dropping a node onto the canvas should never land on "Delete Cluster"
 * waiting for one careless click. `idempotent` is the closest thing the catalogue carries to "safe": every
 * read operation in these plugins declares it, and no destructive one does.
 */
export function defaultOperation(operations: readonly NodeCatalogEntry[]): NodeCatalogEntry {
  return operations.find((operation) => operation.idempotent) ?? operations[0];
}

/**
 * The operations a node's plugin offers, for the property panel's dropdown.
 *
 * Falls back to the node's own `pluginId` when its node type does not resolve. That is the case a plugin
 * update creates: the plugin is installed and healthy, but the specific operation this node used has been
 * removed from the new version. Returning nothing there would take the dropdown away at exactly the moment
 * the author needs it — they can see the node is broken and have no way to repoint it without editing JSON.
 *
 * @param nodeType the node's current type
 * @param entries  the catalogue
 * @param pluginId the node's pinned plugin, used only when `nodeType` does not resolve
 * @returns the sibling operations, or an empty list for a built-in or a genuinely absent plugin
 */
export function operationsFor(
  nodeType: string | null | undefined,
  entries: readonly NodeCatalogEntry[],
  pluginId?: string | null,
): NodeCatalogEntry[] {
  const current = entries.find((entry) => entry.nodeType === nodeType);
  if (current?.source === 'PLUGIN' && current.pluginId) {
    return entries.filter(
      (entry) => entry.source === 'PLUGIN' && entry.pluginId === current.pluginId,
    );
  }
  if (!current && pluginId) {
    return entries.filter((entry) => entry.source === 'PLUGIN' && entry.pluginId === pluginId);
  }
  return [];
}

/** Why a node's operation cannot be resolved, which decides what the panel should say about it. */
export type OperationStatus =
  /** Resolved normally. */
  | 'OK'
  /** No node type and no sign of the plugin — it is not installed, or is disabled. */
  | 'PLUGIN_MISSING'
  /** The plugin is installed but no longer provides this operation. Recoverable: pick another. */
  | 'OPERATION_MISSING';

/**
 * Classifies an unresolved node.
 *
 * The distinction matters because the two cases need opposite advice. A missing plugin is an
 * administrator's problem and the author can do nothing in this panel. A missing *operation* means a plugin
 * update dropped it — the author can fix that themselves in seconds by choosing a different one, and telling
 * them "no loaded plugin provides this" would send them chasing an installation that is already fine.
 */
export function operationStatus(
  node: { type?: string | null; pluginId?: string | null },
  entries: readonly NodeCatalogEntry[],
  resolved: NodeCatalogEntry | null | undefined,
): OperationStatus {
  if (resolved) {
    return 'OK';
  }
  const pluginId = node.pluginId;
  if (!pluginId) {
    return 'PLUGIN_MISSING';
  }
  const installed = entries.some(
    (entry) => entry.source === 'PLUGIN' && entry.pluginId === pluginId,
  );
  return installed ? 'OPERATION_MISSING' : 'PLUGIN_MISSING';
}

/**
 * Filters palette rows.
 *
 * Runs on rows rather than on catalogue entries so that a plugin's *label* is searchable — an operator who
 * installed "Excel Handler" will type that, and it appears in no node type, category or description. When the
 * label matches, every operation stays on the row; when only some operations match, the row narrows to those,
 * so dragging it lands on something the operator was actually looking for.
 */
export function filterPaletteItems(items: readonly PaletteItem[], term: string): PaletteItem[] {
  const needle = term.trim().toLowerCase();
  if (!needle) {
    return [...items];
  }
  const matched: PaletteItem[] = [];
  for (const item of items) {
    // The plugin id counts as part of the row: someone who just uploaded "sendgrid" searches for that, and
    // it appears in no display name, category or description.
    const identity = `${item.label} ${item.entry.pluginId ?? ''}`.toLowerCase();
    if (identity.includes(needle)) {
      matched.push(item);
      continue;
    }
    const operations = item.operations.filter((operation) => matchesOperation(operation, needle));
    if (operations.length === 0) {
      continue;
    }
    matched.push({
      ...item,
      entry: defaultOperation(operations),
      operations,
      // Narrowed to one, the useful thing to show is which operation matched — "27 operations" would be a
      // straight lie, and repeating the plugin id says nothing the label has not.
      meta:
        operations.length === 1
          ? operations[0].displayName
          : `${operations.length} of ${item.operations.length} operations`,
    });
  }
  return matched;
}

/** Groups operations for a grouped dropdown, preserving first-appearance order. */
export function groupOperations(
  operations: readonly NodeCatalogEntry[],
): { category: string; operations: NodeCatalogEntry[] }[] {
  const groups = new Map<string, NodeCatalogEntry[]>();
  for (const operation of operations) {
    const list = groups.get(operation.category) ?? [];
    list.push(operation);
    groups.set(operation.category, list);
  }
  return [...groups.entries()].map(([category, grouped]) => ({ category, operations: grouped }));
}

/** Case-insensitive match across the fields an operator would type. */
export function matchesOperation(operation: NodeCatalogEntry, term: string): boolean {
  const needle = term.trim().toLowerCase();
  if (!needle) {
    return true;
  }
  return [operation.displayName, operation.nodeType, operation.category, operation.description]
    .join(' ')
    .toLowerCase()
    .includes(needle);
}

/** What changing the operation did to the configuration, so the panel can say so. */
export interface ConfigurationCarryOver {
  configuration: Record<string, unknown>;
  /** Keys the previous operation used that the new one does not declare. */
  dropped: string[];
}

/**
 * Moves configuration across an operation change.
 *
 * Keeps the keys the incoming operation declares and discards the rest. The two alternatives are both worse:
 * keeping everything leaves `confirmed: true` from a delete lying inside a list operation, which pollutes the
 * stored workflow and reads as though it means something; dropping everything throws away the connection and
 * credential the author just filled in, which are exactly the fields every operation of a plugin shares.
 *
 * Dropped keys are returned rather than silently removed, because losing work invisibly is the thing an
 * author cannot recover from.
 */
export function carryOverConfiguration(
  configuration: Record<string, unknown> | null | undefined,
  // Deliberately `unknown`: the catalogue's schema type is a loose JSON-schema shape, and this only needs to
  // read `properties`. Narrowing happens in `declaredKeys`, which tolerates anything.
  targetSchema: unknown,
): ConfigurationCarryOver {
  const current = configuration ?? {};
  const declared = declaredKeys(targetSchema);

  // No usable schema means the plugin is unresolved. Keeping everything is right here: the values are the
  // author's only copy, and discarding them because a plugin is temporarily missing would be destructive.
  if (declared.size === 0) {
    return { configuration: { ...current }, dropped: [] };
  }

  const kept: Record<string, unknown> = {};
  const dropped: string[] = [];
  for (const [key, value] of Object.entries(current)) {
    if (declared.has(key)) {
      kept[key] = value;
    } else {
      dropped.push(key);
    }
  }
  return { configuration: kept, dropped };
}

function declaredKeys(schema: unknown): Set<string> {
  if (!schema || typeof schema !== 'object') {
    return new Set();
  }
  const properties = (schema as Record<string, unknown>)['properties'];
  if (!properties || typeof properties !== 'object') {
    return new Set();
  }
  return new Set(Object.keys(properties as Record<string, unknown>));
}

/**
 * A human label for a plugin row.
 *
 * The marketplace name is authoritative when the marketplace has loaded. Otherwise the most common node
 * category is a better guess than the plugin id: these plugins categorise their nodes as "Excel", "Jira",
 * "Kubernetes", which is exactly what the row should say, whereas "orchpilot-gcp-kubernetes" is an identifier
 * rather than a name.
 */
function pluginLabel(
  pluginId: string,
  operations: readonly NodeCatalogEntry[],
  pluginNames?: ReadonlyMap<string, string | null>,
): string {
  const registered = pluginNames?.get(pluginId);
  if (registered) {
    return registered;
  }
  const counts = new Map<string, number>();
  for (const operation of operations) {
    counts.set(operation.category, (counts.get(operation.category) ?? 0) + 1);
  }
  let best: string | null = null;
  let bestCount = 0;
  for (const [category, count] of counts) {
    if (count > bestCount) {
      best = category;
      bestCount = count;
    }
  }
  return best ?? pluginId;
}
