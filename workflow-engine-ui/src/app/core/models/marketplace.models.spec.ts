import {
  InstalledVersionView,
  PluginStatusView,
  PluginSyncStatus,
  describeStatus,
  hasUpdate,
  isInstallable,
  isInstalled,
} from './marketplace.models';

function view(overrides: Partial<PluginStatusView> = {}): PluginStatusView {
  return {
    pluginId: 'slack',
    name: 'Slack',
    description: 'Posts messages to Slack.',
    vendor: 'OrchPilot',
    serverVersion: '1.1.0',
    installedVersion: '1.0.0',
    status: 'UPDATE_AVAILABLE',
    compatible: true,
    incompatibility: [],
    installedVersions: [installed()],
    availableVersions: ['1.1.0', '1.0.0'],
    nodeTypes: ['SLACK_MESSAGE'],
    deprecatedInstalled: false,
    ...overrides,
  };
}

function installed(overrides: Partial<InstalledVersionView> = {}): InstalledVersionView {
  return {
    version: '1.0.0',
    state: 'ACTIVE',
    isDefault: true,
    installedAt: '2026-08-13T18:00:00Z',
    failure: null,
    ...overrides,
  };
}

const ALL_STATUSES: PluginSyncStatus[] = [
  'REVOKED',
  'INCOMPATIBLE',
  'UPDATE_AVAILABLE',
  'DEPRECATED',
  'INSTALLED',
  'NOT_INSTALLED',
  'UNKNOWN_TO_REGISTRY',
];

describe('marketplace status helpers', () => {
  it('treats only NOT_INSTALLED as installable', () => {
    // An incompatible plugin is offered by the registry and must not get an Install button, which is the
    // case this guards: the server refuses it, and a button that always 422s is worse than no button.
    expect(isInstallable('NOT_INSTALLED')).toBeTrue();
    expect(isInstallable('INCOMPATIBLE')).toBeFalse();
    expect(isInstallable('REVOKED')).toBeFalse();
    expect(isInstallable('INSTALLED')).toBeFalse();
  });

  it('treats only UPDATE_AVAILABLE as having an update', () => {
    expect(hasUpdate('UPDATE_AVAILABLE')).toBeTrue();
    expect(hasUpdate('DEPRECATED')).toBeFalse();
  });

  it('reads installed from the version rather than the status', () => {
    // A revoked plugin is still installed, and the screen has to keep offering to remove it.
    expect(isInstalled(view({ status: 'REVOKED' }))).toBeTrue();
    expect(isInstalled(view({ status: 'NOT_INSTALLED', installedVersion: null }))).toBeFalse();
  });

  it('explains every status it can be given', () => {
    for (const status of ALL_STATUSES) {
      const sentence = describeStatus(status);
      expect(sentence.length).toBeGreaterThan(0);
      expect(sentence.endsWith('.')).toBeTrue();
    }
  });

  it('describes a revoked plugin as something already running that may be harmful', () => {
    expect(describeStatus('REVOKED')).toContain('withdrawn');
  });
});
