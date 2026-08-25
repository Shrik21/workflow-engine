import { NodeCatalogEntry } from '../../core/models/node.models';
import { WorkflowResponse } from '../../core/models/workflow.models';
import { DesignerStore } from './designer.store';

function entry(overrides: Partial<NodeCatalogEntry> = {}): NodeCatalogEntry {
  return {
    nodeType: 'SENDGRID_EMAIL',
    displayName: 'Send Email',
    category: 'Communication',
    icon: 'email',
    description: '',
    source: 'PLUGIN',
    pluginId: 'sendgrid',
    pluginVersion: '1.0.0',
    configurationSchema: {
      properties: { contentType: { type: 'string', default: 'text/plain' } },
      required: [],
    },
    outputPorts: [],
    outputVariables: [],
    idempotent: false,
    supportsRetry: true,
    ...overrides,
  };
}

const builtIn = (nodeType: string) =>
  entry({ nodeType, source: 'BUILT_IN', pluginId: null, pluginVersion: null, configurationSchema: {} });

describe('DesignerStore', () => {
  let store: DesignerStore;

  beforeEach(() => {
    store = new DesignerStore();
    store.startBlank();
  });

  describe('startBlank', () => {
    it('starts with a start and an end node so the canvas is never empty', () => {
      expect(store.nodes().map((node) => node.type)).toEqual(['START', 'END']);
      expect(store.isNew()).toBeTrue();
      expect(store.dirty()).toBeFalse();
    });
  });

  describe('addNode', () => {
    it('pins the loaded plugin version, so a later upload cannot change the node', () => {
      const added = store.addNode(entry(), { x: 100, y: 100 });

      expect(added.pluginId).toBe('sendgrid');
      expect(added.pluginVersion).toBe('1.0.0');
      expect(store.dirty()).toBeTrue();
    });

    it('does not record plugin details for a built-in type', () => {
      const added = store.addNode(builtIn('DECISION'), { x: 0, y: 0 });
      expect(added.pluginId).toBeUndefined();
    });

    it('applies schema defaults', () => {
      const added = store.addNode(entry(), { x: 0, y: 0 });
      expect(added.configuration).toEqual({ contentType: 'text/plain' });
    });

    it('derives a readable, unique id from the node type', () => {
      const first = store.addNode(entry(), { x: 0, y: 0 });
      const second = store.addNode(entry(), { x: 0, y: 0 });

      expect(first.id).toBe('sendgrid-email-1');
      expect(second.id).toBe('sendgrid-email-2');
    });

    it('gives a decision node an empty condition list, and a form node no form until one is chosen', () => {
      expect(store.addNode(builtIn('DECISION'), { x: 0, y: 0 }).conditions).toEqual([]);
      const form = store.addNode(builtIn('FORM'), { x: 0, y: 0 });

      // Not the node's own id, which is what it used to be. That made an unconfigured node look configured
      // and pass validation while resolving to no published form at run time.
      expect(form.formId).toBeUndefined();
      expect(form.waitForInput).toBeTrue();
    });

    it('selects the node it added, so the property panel opens on it', () => {
      const added = store.addNode(entry(), { x: 0, y: 0 });
      expect(store.selectedNodeId()).toBe(added.id);
    });
  });

  describe('connect', () => {
    it('adds an edge', () => {
      expect(store.connect('start-1', 'end-1', null)).toBeTrue();
      expect(store.connections().length).toBe(1);
    });

    it('refuses a self-loop', () => {
      expect(store.connect('start-1', 'start-1', null)).toBeFalse();
      expect(store.connections().length).toBe(0);
    });

    it('refuses an exact duplicate but allows a different branch', () => {
      expect(store.connect('start-1', 'end-1', 'yes')).toBeTrue();
      expect(store.connect('start-1', 'end-1', 'yes')).toBeFalse();
      expect(store.connect('start-1', 'end-1', 'no')).toBeTrue();
      expect(store.connections().length).toBe(2);
    });
  });

  describe('renameNode', () => {
    beforeEach(() => {
      store.connect('start-1', 'end-1', null);
    });

    it('rewires every connection that referenced the old id', () => {
      expect(store.renameNode('end-1', 'finish')).toBeTrue();

      expect(store.connections()[0].target).toBe('finish');
      expect(store.nodes().some((node) => node.id === 'finish')).toBeTrue();
    });

    it('rewires a compensation reference', () => {
      store.updateNode('start-1', { compensationNodeId: 'end-1' });
      store.renameNode('end-1', 'finish');

      expect(store.nodes().find((node) => node.id === 'start-1')!.compensationNodeId).toBe('finish');
    });

    it('refuses a duplicate or blank id', () => {
      expect(store.renameNode('end-1', 'start-1')).toBeFalse();
      expect(store.renameNode('end-1', '   ')).toBeFalse();
      expect(store.renameNode('end-1', 'end-1')).toBeFalse();
    });

    it('follows the selection to the new id', () => {
      store.selectNode('end-1');
      store.renameNode('end-1', 'finish');
      expect(store.selectedNodeId()).toBe('finish');
    });
  });

  describe('removeNode', () => {
    it('removes every edge attached to the node, leaving none dangling', () => {
      const middle = store.addNode(entry(), { x: 0, y: 0 });
      store.connect('start-1', middle.id, null);
      store.connect(middle.id, 'end-1', null);

      store.removeNode(middle.id);

      expect(store.connections().length).toBe(0);
      expect(store.selectedNodeId()).toBeNull();
    });
  });

  describe('duplicateNode', () => {
    it('produces an independent copy offset from the original', () => {
      const original = store.addNode(entry(), { x: 100, y: 100 });
      store.updateNode(original.id, { configuration: { subject: 'hello' } });

      store.duplicateNode(original.id);
      const copy = store.nodes().at(-1)!;

      expect(copy.id).not.toBe(original.id);
      expect(copy.configuration).toEqual({ subject: 'hello' });
      expect(copy.presentation?.x).toBe(140);

      // Mutating the copy must not affect the original.
      store.updateNode(copy.id, { configuration: { subject: 'changed' } });
      const refreshed = store.nodes().find((node) => node.id === original.id)!;
      expect(refreshed.configuration).toEqual({ subject: 'hello' });
    });
  });

  describe('localIssues', () => {
    it('is silent about a well-formed graph', () => {
      store.setName('Valid');
      store.connect('start-1', 'end-1', null);
      expect(store.localIssues()).toEqual([]);
    });

    it('reports a missing start node', () => {
      store.removeNode('start-1');
      expect(store.localIssues().some((issue) => issue.includes('Start node'))).toBeTrue();
    });

    it('reports two start nodes', () => {
      store.addNode(builtIn('START'), { x: 0, y: 0 });
      expect(store.localIssues().some((issue) => issue.includes('2 Start nodes'))).toBeTrue();
    });

    it('reports a missing end node', () => {
      store.removeNode('end-1');
      expect(store.localIssues().some((issue) => issue.includes('End node'))).toBeTrue();
    });

    it('reports a node with no incoming connection', () => {
      expect(store.localIssues().some((issue) => issue.includes('no incoming'))).toBeTrue();
    });

    it('does not report a compensation node as unreachable', () => {
      const compensation = store.addNode(entry(), { x: 0, y: 0 });
      store.connect('start-1', 'end-1', null);
      store.updateNode('start-1', { compensationNodeId: compensation.id });
      store.updateNode(compensation.id, {});
      store.connect(compensation.id, 'end-1', null);

      const issues = store.localIssues();
      expect(issues.some((issue) => issue.includes('no incoming'))).toBeFalse();
    });

    it('reports a decision with neither conditions nor a default branch', () => {
      const decision = store.addNode(builtIn('DECISION'), { x: 0, y: 0 });
      store.connect('start-1', decision.id, null);
      store.connect(decision.id, 'end-1', null);

      expect(store.localIssues().some((issue) => issue.includes('no conditions'))).toBeTrue();
    });

    it('reports a missing name', () => {
      store.setName('   ');
      expect(store.localIssues().some((issue) => issue.includes('name'))).toBeTrue();
    });
  });

  describe('import and export', () => {
    it('round-trips through JSON', () => {
      store.setName('Round trip');
      store.connect('start-1', 'end-1', null);

      const exported = store.exportJson();
      const fresh = new DesignerStore();
      expect(fresh.importJson(exported)).toBeNull();

      expect(fresh.name()).toBe('Round trip');
      expect(fresh.nodes().length).toBe(2);
      expect(fresh.connections().length).toBe(1);
    });

    it('accepts a full workflow response and ignores its engine-owned fields', () => {
      const response: Partial<WorkflowResponse> = {
        id: 'wf-1',
        name: 'From the API',
        status: 'PUBLISHED',
        publishedVersion: 7,
        nodes: [{ id: 'start-1', type: 'START' }],
        connections: [],
      };

      expect(store.importJson(JSON.stringify(response))).toBeNull();
      expect(store.name()).toBe('From the API');
      // Importing a definition does not make the draft a published workflow.
      expect(store.workflowId()).toBeNull();
      expect(store.status()).toBe('DRAFT');
    });

    it('reports a readable error rather than throwing', () => {
      expect(store.importJson('{ not json')).toContain('JSON');
      expect(store.importJson('{}')).toBe('The JSON has no "nodes" array.');
      expect(store.importJson('[]')).toBe('The JSON has no "nodes" array.');
    });

    it('lays out an imported graph that has no coordinates', () => {
      store.importJson(
        JSON.stringify({
          name: 'No coordinates',
          nodes: [
            { id: 'a', type: 'START' },
            { id: 'b', type: 'END' },
          ],
          connections: [{ source: 'a', target: 'b' }],
        }),
      );

      const [first, second] = store.nodes();
      expect(first.presentation?.x).toBeDefined();
      expect(second.presentation!.x!).toBeGreaterThan(first.presentation!.x!);
    });

    it('keeps hand-placed coordinates', () => {
      store.importJson(
        JSON.stringify({
          name: 'Placed',
          nodes: [
            { id: 'a', type: 'START', presentation: { x: 999, y: 111 } },
            { id: 'b', type: 'END', presentation: { x: 1200, y: 111 } },
          ],
          connections: [],
        }),
      );

      expect(store.nodes()[0].presentation!.x).toBe(999);
    });
  });

  describe('toRequest', () => {
    it('trims the name and omits an empty description', () => {
      store.setName('  Padded  ');
      expect(store.toRequest().name).toBe('Padded');
      expect(store.toRequest().description).toBeNull();
    });
  });

  describe('dirty tracking', () => {
    it('clears once the workflow is saved', () => {
      store.setName('Changed');
      expect(store.dirty()).toBeTrue();

      store.markSaved({ id: 'wf-1', status: 'DRAFT', publishedVersion: null } as WorkflowResponse);

      expect(store.dirty()).toBeFalse();
      expect(store.workflowId()).toBe('wf-1');
    });
  });
});
