import { NodeCatalogEntry } from '../../core/models/node.models';
import {
  carryOverConfiguration,
  defaultOperation,
  groupOperations,
  matchesOperation,
  operationsFor,
  toPaletteItems,
  filterPaletteItems,
  operationStatus,
} from './plugin-operations';

/** A catalogue entry with only the fields these functions read. */
function entry(overrides: Partial<NodeCatalogEntry> & { nodeType: string }): NodeCatalogEntry {
  return {
    displayName: overrides.nodeType,
    category: 'General',
    icon: 'plugin',
    description: '',
    source: 'PLUGIN',
    pluginId: 'p',
    pluginVersion: '1.0.0',
    configurationSchema: null,
    outputPorts: [],
    outputVariables: [],
    idempotent: false,
    supportsRetry: true,
    ...overrides,
  } as NodeCatalogEntry;
}

describe('toPaletteItems', () => {
  it('collapses a plugin to one row and keeps built-ins separate', () => {
    const items = toPaletteItems([
      entry({ nodeType: 'START', source: 'BUILT_IN', pluginId: null, category: 'Core' }),
      entry({ nodeType: 'K8S_LIST_PODS', pluginId: 'k8s', category: 'Kubernetes', idempotent: true }),
      entry({ nodeType: 'K8S_DELETE_POD', pluginId: 'k8s', category: 'Kubernetes' }),
      entry({ nodeType: 'GKE_LIST_CLUSTERS', pluginId: 'k8s', category: 'GCP Kubernetes' }),
    ]);

    // The whole point: 4 catalogue entries become 2 palette rows.
    expect(items.length).toBe(2);
    expect(items[0].label).toBe('START');
    expect(items[0].operations.length).toBe(1);

    const plugin = items[1];
    expect(plugin.operations.length).toBe(3);
    expect(plugin.meta).toBe('3 operations');
  });

  it('groups by pluginId, not category, so a plugin spanning categories stays one row', () => {
    // The GCP Kubernetes plugin labels cluster nodes and workload nodes differently. Grouping by category
    // would split it in two and misrepresent it as two integrations.
    const items = toPaletteItems([
      entry({ nodeType: 'A', pluginId: 'k8s', category: 'Kubernetes' }),
      entry({ nodeType: 'B', pluginId: 'k8s', category: 'GCP Kubernetes' }),
    ]);

    expect(items.length).toBe(1);
    expect(items[0].operations.length).toBe(2);
  });

  it('labels a row from the marketplace name when it is known', () => {
    const items = toPaletteItems(
      [entry({ nodeType: 'A', pluginId: 'orchpilot-excel-handler', category: 'Excel' })],
      new Map([['orchpilot-excel-handler', 'Excel Handler']]),
    );

    expect(items[0].label).toBe('Excel Handler');
  });

  it('falls back to the most common category rather than the plugin id', () => {
    // "orchpilot-gcp-kubernetes" is an identifier; "Kubernetes" is a name.
    const items = toPaletteItems([
      entry({ nodeType: 'A', pluginId: 'orchpilot-gcp-kubernetes', category: 'Kubernetes' }),
      entry({ nodeType: 'B', pluginId: 'orchpilot-gcp-kubernetes', category: 'Kubernetes' }),
      entry({ nodeType: 'C', pluginId: 'orchpilot-gcp-kubernetes', category: 'GCP Kubernetes' }),
    ]);

    expect(items[0].label).toBe('Kubernetes');
  });

  it('keeps a plugin entry that carries no pluginId as its own row rather than dropping it', () => {
    const items = toPaletteItems([entry({ nodeType: 'ORPHAN', pluginId: null })]);

    expect(items.length).toBe(1);
    expect(items[0].entry.nodeType).toBe('ORPHAN');
  });
});

describe('defaultOperation', () => {
  it('prefers an idempotent operation so a dropped node never lands on a delete', () => {
    const chosen = defaultOperation([
      entry({ nodeType: 'DELETE_CLUSTER' }),
      entry({ nodeType: 'LIST_CLUSTERS', idempotent: true }),
    ]);

    expect(chosen.nodeType).toBe('LIST_CLUSTERS');
  });

  it('falls back to the first when nothing is idempotent', () => {
    const chosen = defaultOperation([entry({ nodeType: 'SEND' }), entry({ nodeType: 'OTHER' })]);

    expect(chosen.nodeType).toBe('SEND');
  });
});

describe('operationsFor', () => {
  const catalogue = [
    entry({ nodeType: 'START', source: 'BUILT_IN', pluginId: null }),
    entry({ nodeType: 'JIRA_CREATE', pluginId: 'jira' }),
    entry({ nodeType: 'JIRA_DELETE', pluginId: 'jira' }),
    entry({ nodeType: 'EXCEL_READ', pluginId: 'excel' }),
  ];

  it('returns the siblings of a plugin node', () => {
    expect(operationsFor('JIRA_CREATE', catalogue).map((o) => o.nodeType)).toEqual([
      'JIRA_CREATE',
      'JIRA_DELETE',
    ]);
  });

  it('returns nothing for a built-in, which has no operations', () => {
    expect(operationsFor('START', catalogue)).toEqual([]);
  });

  it('returns nothing for an unresolved node type', () => {
    // A plugin that was uninstalled: the panel must not offer a dropdown it cannot populate.
    expect(operationsFor('GONE', catalogue)).toEqual([]);
  });
});

describe('operationsFor — plugin update removed the operation', () => {
  // The plugin is installed and healthy; a new version simply dropped JIRA_DELETE. Without the pluginId
  // fallback the dropdown vanishes and the author cannot repoint the node without editing raw JSON.
  const catalogue = [
    entry({ nodeType: 'JIRA_CREATE', pluginId: 'jira' }),
    entry({ nodeType: 'JIRA_COMMENT', pluginId: 'jira' }),
  ];

  it('still offers the plugin operations when the node type no longer exists', () => {
    const operations = operationsFor('JIRA_DELETE', catalogue, 'jira');

    expect(operations.map((o) => o.nodeType)).toEqual(['JIRA_CREATE', 'JIRA_COMMENT']);
  });

  it('offers nothing when the plugin itself is gone', () => {
    expect(operationsFor('JIRA_DELETE', catalogue, 'gone')).toEqual([]);
  });

  it('ignores the fallback when the node type does resolve', () => {
    // A resolving node type wins, so a stale pluginId on the node cannot pull in another plugin's list.
    const operations = operationsFor('JIRA_CREATE', catalogue, 'something-else');

    expect(operations.map((o) => o.nodeType)).toEqual(['JIRA_CREATE', 'JIRA_COMMENT']);
  });
});

describe('operationStatus', () => {
  const catalogue = [entry({ nodeType: 'JIRA_CREATE', pluginId: 'jira' })];

  it('is OK when the entry resolved', () => {
    expect(operationStatus({ type: 'JIRA_CREATE', pluginId: 'jira' }, catalogue, catalogue[0])).toBe(
      'OK',
    );
  });

  it('distinguishes a removed operation from a missing plugin', () => {
    // The distinction drives opposite advice: one the author fixes in seconds, the other needs an admin.
    expect(operationStatus({ type: 'JIRA_DELETE', pluginId: 'jira' }, catalogue, null)).toBe(
      'OPERATION_MISSING',
    );
    expect(operationStatus({ type: 'SLACK_POST', pluginId: 'slack' }, catalogue, null)).toBe(
      'PLUGIN_MISSING',
    );
  });

  it('treats a node with no pluginId as a missing plugin', () => {
    expect(operationStatus({ type: 'MYSTERY', pluginId: null }, catalogue, null)).toBe(
      'PLUGIN_MISSING',
    );
  });
});

describe('filterPaletteItems', () => {
  const items = toPaletteItems(
    [
      entry({ nodeType: 'START', source: 'BUILT_IN', pluginId: null, displayName: 'Start' }),
      entry({
        nodeType: 'K8S_DELETE_POD',
        pluginId: 'k8s',
        displayName: 'Delete Kubernetes Pod',
        category: 'Kubernetes',
      }),
      entry({
        nodeType: 'K8S_LIST_PODS',
        pluginId: 'k8s',
        displayName: 'List Kubernetes Pods',
        category: 'Kubernetes',
        idempotent: true,
      }),
      entry({
        nodeType: 'SENDGRID_EMAIL',
        pluginId: 'orchpilot-sendgrid',
        displayName: 'Send Email',
        category: 'Communication',
      }),
    ],
    new Map([['k8s', 'Kubernetes']]),
  );

  it('returns everything for an empty term', () => {
    expect(filterPaletteItems(items, '  ').length).toBe(items.length);
  });

  it('matches a plugin label and keeps all of its operations', () => {
    const [row] = filterPaletteItems(items, 'kubernetes');

    expect(row.label).toBe('Kubernetes');
    expect(row.operations.length).toBe(2);
  });

  it('matches the plugin id, which appears in no label or description', () => {
    // The regression this guards: an operator who just uploaded "sendgrid" searches for exactly that.
    const matched = filterPaletteItems(items, 'sendgrid');

    expect(matched.length).toBe(1);
    expect(matched[0].entry.nodeType).toBe('SENDGRID_EMAIL');
  });

  it('narrows a row to the matching operations and defaults to one of them', () => {
    const [row] = filterPaletteItems(items, 'delete');

    expect(row.operations.map((o) => o.nodeType)).toEqual(['K8S_DELETE_POD']);
    // Dragging the narrowed row must land on what was searched for, not on the plugin's usual default.
    expect(row.entry.nodeType).toBe('K8S_DELETE_POD');
  });

  it('names the single matching operation rather than claiming a count', () => {
    const [row] = filterPaletteItems(items, 'delete');

    expect(row.meta).toBe('Delete Kubernetes Pod');
  });

  it('reports how many of how many matched when several do', () => {
    const [row] = filterPaletteItems(items, 'pod');

    expect(row.meta).toBe('2 of 2 operations');
  });

  it('drops rows where nothing matches', () => {
    expect(filterPaletteItems(items, 'mongodb')).toEqual([]);
  });
});

describe('groupOperations', () => {
  it('groups by category in first-appearance order', () => {
    const groups = groupOperations([
      entry({ nodeType: 'A', category: 'Read' }),
      entry({ nodeType: 'B', category: 'Write' }),
      entry({ nodeType: 'C', category: 'Read' }),
    ]);

    expect(groups.map((g) => g.category)).toEqual(['Read', 'Write']);
    expect(groups[0].operations.length).toBe(2);
  });
});

describe('matchesOperation', () => {
  const operation = entry({
    nodeType: 'K8S_DELETE_POD',
    displayName: 'Delete Kubernetes Pod',
    category: 'Kubernetes',
    description: 'Removes a pod.',
  });

  it('matches on the display name, the node type and the description', () => {
    expect(matchesOperation(operation, 'delete')).toBe(true);
    expect(matchesOperation(operation, 'K8S_DELETE')).toBe(true);
    expect(matchesOperation(operation, 'removes a pod')).toBe(true);
  });

  it('is case-insensitive and matches everything on an empty term', () => {
    expect(matchesOperation(operation, 'DELETE')).toBe(true);
    expect(matchesOperation(operation, '   ')).toBe(true);
  });

  it('does not match an unrelated term', () => {
    expect(matchesOperation(operation, 'mongodb')).toBe(false);
  });
});

describe('carryOverConfiguration', () => {
  const schema = {
    properties: { credentialsSecret: {}, namespace: {}, podName: {} },
  };

  it('keeps the keys the new operation declares and reports the rest', () => {
    const result = carryOverConfiguration(
      { credentialsSecret: 'k8s.prod', namespace: 'web', confirmed: true, deleteAll: false },
      schema,
    );

    // The shared connection fields survive — those are what the author just filled in.
    expect(result.configuration).toEqual({ credentialsSecret: 'k8s.prod', namespace: 'web' });
    // The delete-only flags are dropped, and named so the author can see it happened.
    expect(result.dropped.sort()).toEqual(['confirmed', 'deleteAll']);
  });

  it('drops nothing when every key still applies', () => {
    const result = carryOverConfiguration({ namespace: 'web' }, schema);

    expect(result.dropped).toEqual([]);
    expect(result.configuration).toEqual({ namespace: 'web' });
  });

  it('keeps everything when the target schema is unknown', () => {
    // An unresolved plugin must not cost the author their configuration; those values are the only copy.
    const result = carryOverConfiguration({ anything: 1, atAll: 2 }, null);

    expect(result.configuration).toEqual({ anything: 1, atAll: 2 });
    expect(result.dropped).toEqual([]);
  });

  it('tolerates an empty configuration', () => {
    expect(carryOverConfiguration(null, schema).configuration).toEqual({});
    expect(carryOverConfiguration(undefined, schema).dropped).toEqual([]);
  });

  it('does not mutate the configuration it was given', () => {
    const original = { namespace: 'web', confirmed: true };
    carryOverConfiguration(original, schema);

    expect(original).toEqual({ namespace: 'web', confirmed: true });
  });
});
