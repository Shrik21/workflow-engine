import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { By } from '@angular/platform-browser';
import { API_BASE_URL } from '../../core/api/api-base';
import { NodeApiService } from '../../core/api/node-api.service';
import { NodeCatalogEntry } from '../../core/models/node.models';
import { DesignerStore } from './designer.store';
import { NodeProperties } from './node-properties';

/**
 * The property panel's select elements, on a freshly rendered panel.
 *
 * <h2>The bug these pin</h2>
 *
 * `<select [value]="…">` sets the element's `value` property during the update pass. When the options come from
 * an `@for`, they do not exist yet at that moment — so the browser discards the assignment and the select falls
 * back to showing its first option. The node's stored type was always correct; only the control lied about it.
 *
 * <p>It survived review because it is invisible until you reopen a saved workflow: right after you pick an
 * operation the select holds the value you clicked, and it is only on the next fresh render that it resets.
 */

function entry(nodeType: string, displayName: string): NodeCatalogEntry {
  return {
    nodeType,
    displayName,
    description: displayName,
    category: 'GCP Network',
    icon: 'cloud',
    source: 'PLUGIN',
    pluginId: 'orchpilot-gcp-network',
    pluginVersion: '1.0.0',
    configurationSchema: { type: 'object', properties: {} },
    outputPorts: [],
    outputVariables: [],
    idempotent: true,
    supportsRetry: true,
  };
}

const CATALOG: NodeCatalogEntry[] = [
  entry('GCP_NET_CREATE_VPC', 'Create VPC'),
  entry('GCP_NET_GET_VPC', 'Get VPC'),
  entry('GCP_NET_LIST_VPCS', 'List VPCs'),
  entry('GCP_NET_CREATE_SUBNET', 'Create Subnet'),
  entry('GCP_NET_DELETE_VPC', 'Delete VPC'),
];

describe('NodeProperties selects', () => {
  let fixture: ComponentFixture<NodeProperties>;
  let store: DesignerStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [NodeProperties],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: API_BASE_URL, useValue: '' },
        // Provided by the designer shell in the app, not root-scoped — so the test supplies it.
        DesignerStore,
      ],
    });

    const catalog = TestBed.inject(NodeApiService);
    // The panel reads the catalogue as a signal; seed the backing state rather than round-tripping HTTP.
    (
      catalog as unknown as { entriesState: { set(value: NodeCatalogEntry[]): void } }
    ).entriesState.set(CATALOG);

    store = TestBed.inject(DesignerStore);
    fixture = TestBed.createComponent(NodeProperties);
  });

  /** The operation and compensation selects live on their own tabs, so a test has to open one. */
  function openTab(tab: 'Settings' | 'Configuration' | 'Mappings' | 'Reliability'): void {
    (fixture.componentInstance as unknown as { activeTab: { set(value: string): void } }).activeTab.set(
      tab,
    );
    fixture.detectChanges();
  }

  /** Loads a workflow the way reopening a saved one does, then selects the node. */
  function openSavedWorkflowWith(nodeType: string): void {
    store.load({
      id: 'wf-1',
      name: 'Provision VPC',
      nodes: [
        { id: 'start-1', type: 'START', name: 'Start' },
        {
          id: 'net-1',
          type: nodeType,
          name: 'Network step',
          pluginId: 'orchpilot-gcp-network',
          pluginVersion: '1.0.0',
          configuration: {},
        },
      ],
      connections: [],
    } as never);
    store.selectNode('net-1');
    fixture.detectChanges();
    openTab('Configuration');
  }

  function operationSelect(): HTMLSelectElement | null {
    const found = fixture.debugElement.query(By.css('select#operation'));
    return found ? (found.nativeElement as HTMLSelectElement) : null;
  }

  it('shows the saved operation, not the first one in the list', () => {
    // Deliberately not the first catalogue entry: the failure mode is "falls back to the first option", so a
    // node whose saved type IS the first would pass even while broken.
    openSavedWorkflowWith('GCP_NET_CREATE_SUBNET');

    const select = operationSelect();
    expect(select).withContext('the operation dropdown should be rendered').not.toBeNull();
    expect(select!.value).toBe('GCP_NET_CREATE_SUBNET');
  });

  it('marks the saved operation as the selected option', () => {
    openSavedWorkflowWith('GCP_NET_DELETE_VPC');

    const options = fixture.debugElement
      .queryAll(By.css('select#operation option'))
      .map((option) => option.nativeElement as HTMLOptionElement);

    const selected = options.filter((option) => option.selected);
    expect(selected.length).toBe(1);
    expect(selected[0].value).toBe('GCP_NET_DELETE_VPC');
  });

  it('keeps showing the right operation after the panel re-renders', () => {
    openSavedWorkflowWith('GCP_NET_LIST_VPCS');

    // A second pass with nothing changed must not disturb it — this is what happens continuously in the app.
    fixture.detectChanges();
    fixture.detectChanges();

    expect(operationSelect()!.value).toBe('GCP_NET_LIST_VPCS');
  });

  it('follows the node when the selection moves to another node', () => {
    openSavedWorkflowWith('GCP_NET_GET_VPC');
    expect(operationSelect()!.value).toBe('GCP_NET_GET_VPC');

    store.updateNode('net-1', { type: 'GCP_NET_CREATE_VPC' });
    fixture.detectChanges();

    expect(operationSelect()!.value).toBe('GCP_NET_CREATE_VPC');
  });

  it('shows the saved compensation node rather than "Not set"', () => {
    store.load({
      id: 'wf-1',
      name: 'Provision VPC',
      nodes: [
        { id: 'start-1', type: 'START', name: 'Start' },
        { id: 'rollback', type: 'GCP_NET_DELETE_VPC', name: 'Roll back' },
        {
          id: 'net-1',
          type: 'GCP_NET_CREATE_VPC',
          name: 'Create VPC',
          errorPolicy: 'COMPENSATE',
          compensationNodeId: 'rollback',
        },
      ],
      connections: [],
    } as never);
    store.selectNode('net-1');
    fixture.detectChanges();
    openTab('Reliability');

    const select = fixture.debugElement.query(By.css('select#compensation'));
    expect(select).withContext('the compensation dropdown should be rendered').not.toBeNull();
    // Same defect, same fix: its candidates also come from an `@for`.
    expect((select.nativeElement as HTMLSelectElement).value).toBe('rollback');
  });

  it('still shows the static error policy correctly', () => {
    store.load({
      id: 'wf-1',
      name: 'Provision VPC',
      nodes: [
        { id: 'start-1', type: 'START', name: 'Start' },
        { id: 'net-1', type: 'GCP_NET_CREATE_VPC', name: 'Create VPC', errorPolicy: 'SKIP' },
      ],
      connections: [],
    } as never);
    store.selectNode('net-1');
    fixture.detectChanges();
    openTab('Reliability');

    // This one's options are static, so it was never broken — asserted so a future refactor keeps it working.
    const select = fixture.debugElement.query(By.css('select#error-policy'));
    expect((select.nativeElement as HTMLSelectElement).value).toBe('SKIP');
  });
});
