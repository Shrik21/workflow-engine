import { Signal, computed } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MarketplaceApiService } from '../../core/api/marketplace-api.service';
import { PluginStatusView } from '../../core/models/marketplace.models';
import { WorkflowNode } from '../../core/models/workflow.models';
import { PluginUpgradeDialog, UpgradeCandidate } from './plugin-upgrade-dialog';

/**
 * Which nodes the dialog offers to repoint.
 *
 * The rules pinned here are the ones that are wrong in opposite directions if they drift: offering to
 * "update" an unpinned node, which already follows the default and would be pinned by the change; and
 * offering a version this engine does not have, which produces a workflow that fails validation the moment
 * it is saved.
 */

function node(overrides: Partial<WorkflowNode> = {}): WorkflowNode {
  return {
    id: 'notify',
    type: 'SLACK_MESSAGE',
    name: 'Notify the channel',
    pluginId: 'slack',
    pluginVersion: '1.0.0',
    ...overrides,
  };
}

function view(overrides: Partial<PluginStatusView> = {}): PluginStatusView {
  return {
    pluginId: 'slack',
    name: 'Slack',
    description: null,
    vendor: null,
    serverVersion: '1.1.0',
    installedVersion: '1.1.0',
    status: 'INSTALLED',
    compatible: true,
    incompatibility: [],
    installedVersions: [
      { version: '1.1.0', state: 'ACTIVE', isDefault: true, installedAt: null, failure: null },
      { version: '1.0.0', state: 'ACTIVE', isDefault: false, installedAt: null, failure: null },
    ],
    availableVersions: ['1.1.0', '1.0.0'],
    nodeTypes: ['SLACK_MESSAGE'],
    deprecatedInstalled: false,
    ...overrides,
  };
}

/** The one member of the marketplace store this component reads. */
interface MarketplaceStub {
  byPluginId: Signal<Map<string, PluginStatusView>>;
}

/** Reaches the protected members under test without widening the component's own surface. */
interface Internals {
  candidates: () => UpgradeCandidate[];
  toggle: (nodeId: string, checked: boolean) => void;
  apply: () => void;
}

describe('PluginUpgradeDialog', () => {
  let fixture: ComponentFixture<PluginUpgradeDialog>;

  /**
   * Mounts the dialog over a fixed marketplace state.
   *
   * The store is replaced wholesale rather than stubbed through HTTP: what is being tested is which nodes
   * the component picks out of a known installation state, and routing that through request plumbing would
   * test the plumbing instead.
   */
  function mount(nodes: WorkflowNode[], statuses: PluginStatusView[]): Internals {
    const index = new Map(statuses.map((status) => [status.pluginId, status]));
    const stub: MarketplaceStub = { byPluginId: computed(() => index) };

    TestBed.configureTestingModule({
      imports: [PluginUpgradeDialog],
      providers: [{ provide: MarketplaceApiService, useValue: stub }],
    });

    fixture = TestBed.createComponent(PluginUpgradeDialog);
    fixture.componentRef.setInput('nodes', nodes);
    fixture.detectChanges();
    return fixture.componentInstance as unknown as Internals;
  }

  it('offers a node pinned below the newest installed version', () => {
    const dialog = mount([node()], [view()]);

    expect(dialog.candidates().length).toBe(1);
    expect(dialog.candidates()[0].from).toBe('1.0.0');
    expect(dialog.candidates()[0].to).toBe('1.1.0');
    expect(dialog.candidates()[0].pinnedStillInstalled).toBeTrue();
  });

  it('leaves an unpinned node alone, because it already follows the default', () => {
    const dialog = mount([node({ pluginVersion: null })], [view()]);

    expect(dialog.candidates()).toEqual([]);
  });

  it('leaves a node already pinned to the newest installed version alone', () => {
    const dialog = mount([node({ pluginVersion: '1.1.0' })], [view()]);

    expect(dialog.candidates()).toEqual([]);
  });

  it('does not offer a version the registry has but this engine has not installed', () => {
    // 2.0.0 is published and not installed. Repointing at it would produce a workflow that cannot run here.
    const dialog = mount(
      [node()],
      [
        view({
          serverVersion: '2.0.0',
          availableVersions: ['2.0.0', '1.0.0'],
          installedVersion: '1.0.0',
          status: 'UPDATE_AVAILABLE',
          installedVersions: [
            { version: '1.0.0', state: 'ACTIVE', isDefault: true, installedAt: null, failure: null },
          ],
        }),
      ],
    );

    expect(dialog.candidates()).toEqual([]);
  });

  it('flags a node whose pinned version is no longer installed', () => {
    const dialog = mount(
      [node()],
      [
        view({
          installedVersions: [
            { version: '1.1.0', state: 'ACTIVE', isDefault: true, installedAt: null, failure: null },
          ],
        }),
      ],
    );

    expect(dialog.candidates()[0].pinnedStillInstalled).toBeFalse();
  });

  it('ignores a plugin neither side knows about', () => {
    const dialog = mount([node({ pluginId: 'ghost' })], []);

    expect(dialog.candidates()).toEqual([]);
  });

  it('emits only the selected nodes', () => {
    const dialog = mount([node(), node({ id: 'second', name: 'Second' })], [view()]);

    const emitted: UpgradeCandidate[][] = [];
    fixture.componentInstance.applied.subscribe((value) => emitted.push(value));

    // Everything starts selected, which is the useful default for a bulk action; deselecting one proves the
    // emission follows the selection rather than the candidate list.
    dialog.toggle('second', false);
    dialog.apply();

    expect(emitted.length).toBe(1);
    expect(emitted[0].map((candidate) => candidate.nodeId)).toEqual(['notify']);
  });
});
