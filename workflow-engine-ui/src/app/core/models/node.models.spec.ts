import { NodeCatalogEntry, categoryColorVar, resolveCatalogEntry } from './node.models';

function entry(overrides: Partial<NodeCatalogEntry>): NodeCatalogEntry {
  return {
    nodeType: 'SENDGRID_EMAIL',
    displayName: 'Send Email',
    category: 'Communication',
    icon: 'email',
    description: '',
    source: 'PLUGIN',
    pluginId: 'sendgrid',
    pluginVersion: '1.1.0',
    configurationSchema: {},
    outputPorts: [],
    outputVariables: [],
    idempotent: false,
    supportsRetry: true,
    ...overrides,
  };
}

const START = entry({
  nodeType: 'START',
  displayName: 'Start',
  category: 'Flow',
  icon: 'play',
  source: 'BUILT_IN',
  pluginId: null,
  pluginVersion: null,
});

describe('resolveCatalogEntry', () => {
  const catalog = [START, entry({}), entry({ nodeType: 'REST_API_CALL', pluginId: 'restapi' })];

  it('matches a built-in node by its type', () => {
    expect(resolveCatalogEntry({ type: 'START' }, catalog)).toBe(START);
  });

  it('matches a plugin node by its contributed type', () => {
    expect(resolveCatalogEntry({ type: 'SENDGRID_EMAIL' }, catalog)?.pluginId).toBe('sendgrid');
  });

  it('resolves a node that uses the generic PLUGIN marker type by its plugin id', () => {
    // This is the form the engine's own example workflows use, and it matches no catalogue entry by
    // type. Failing here renders every plugin node as unavailable with no schema.
    const resolved = resolveCatalogEntry({ type: 'PLUGIN', pluginId: 'sendgrid' }, catalog);
    expect(resolved?.nodeType).toBe('SENDGRID_EMAIL');
  });

  it('prefers the entry whose plugin id agrees with the node when a type is contributed twice', () => {
    const rival = entry({ pluginId: 'other-mailer', pluginVersion: '2.0.0' });
    const resolved = resolveCatalogEntry(
      { type: 'SENDGRID_EMAIL', pluginId: 'other-mailer' },
      [entry({}), rival],
    );
    expect(resolved).toBe(rival);
  });

  it('does not guess for a multi-node plugin addressed through the marker type', () => {
    const multi = [
      entry({ nodeType: 'ONE', pluginId: 'multi' }),
      entry({ nodeType: 'TWO', pluginId: 'multi' }),
    ];
    expect(resolveCatalogEntry({ type: 'PLUGIN', pluginId: 'multi' }, multi)).toBeUndefined();
    expect(resolveCatalogEntry({ type: 'TWO', pluginId: 'multi' }, multi)?.nodeType).toBe('TWO');
  });

  it('returns undefined when the plugin is not loaded', () => {
    expect(resolveCatalogEntry({ type: 'PLUGIN', pluginId: 'absent' }, catalog)).toBeUndefined();
    expect(resolveCatalogEntry({ type: 'NOT_A_TYPE' }, catalog)).toBeUndefined();
    expect(resolveCatalogEntry({}, catalog)).toBeUndefined();
  });
});

describe('categoryColorVar', () => {
  it('maps known categories, case-insensitively', () => {
    expect(categoryColorVar('Flow')).toBe('var(--category-flow)');
    expect(categoryColorVar('communication')).toBe('var(--category-communication)');
    expect(categoryColorVar('Integration')).toBe('var(--category-integration)');
  });

  it('falls back to neutral for a category a plugin invented', () => {
    expect(categoryColorVar('Robotics')).toBe('var(--category-general)');
    expect(categoryColorVar(null)).toBe('var(--category-general)');
  });
});
