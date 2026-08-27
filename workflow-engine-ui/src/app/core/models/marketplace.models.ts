/**
 * The plugin registry, as this console sees it.
 *
 * These types mirror what the engine returns from `/api/plugins/status` and the installation endpoints, not
 * what the registry itself stores. The engine is the only service this application talks to, and it has
 * already done the comparison between "what the registry offers" and "what is installed here". Restating that
 * comparison in the browser would produce a second answer to "is there an update", which would disagree with
 * the first one the day somebody publishes a pre-release.
 */

/**
 * One plugin's standing, decided by the engine over the union of the catalogue and the local installation.
 *
 * The order these are decided in is deliberate and lives on the server: revocation outranks an update because
 * it is the only one saying something already running may be harmful, and incompatibility outranks an update
 * because offering an install that cannot load wastes the operator's time.
 */
export type PluginSyncStatus =
  | 'REVOKED'
  | 'INCOMPATIBLE'
  | 'UPDATE_AVAILABLE'
  | 'DEPRECATED'
  | 'INSTALLED'
  | 'NOT_INSTALLED'
  | 'UNKNOWN_TO_REGISTRY';

/** Where one installed version sits in its local lifecycle. */
export type InstallState =
  | 'DOWNLOADING'
  | 'VALIDATING'
  | 'INSTALLED'
  | 'ACTIVE'
  | 'DISABLED'
  | 'STOPPING'
  | 'INSTALL_FAILED';

/** One installed version, as the marketplace shows it. */
export interface InstalledVersionView {
  version: string;
  state: InstallState;
  /** Whether nodes that do not pin a version resolve to this one. */
  isDefault: boolean;
  installedAt: string | null;
  failure: string | null;
}

/** One row of the marketplace. */
export interface PluginStatusView {
  pluginId: string;
  name: string | null;
  description: string | null;
  vendor: string | null;
  /** Newest version the registry offers, or null when it offers none. */
  serverVersion: string | null;
  /** Newest usable version here, or null when nothing is installed. */
  installedVersion: string | null;
  status: PluginSyncStatus;
  compatible: boolean;
  /** Why this engine cannot run it. Empty when it can. */
  incompatibility: string[];
  installedVersions: InstalledVersionView[];
  availableVersions: string[];
  nodeTypes: string[];
  /** Whether a version installed here has been deprecated upstream. */
  deprecatedInstalled: boolean;
}

/**
 * How current the cached catalogue is.
 *
 * Shown rather than hidden because a stale catalogue is not an error: installed plugins keep executing while
 * the registry is unreachable. An operator looking at a plugin that is missing from the list needs to know
 * whether they are looking at the registry or at a snapshot of it from an hour ago.
 */
export interface CatalogHealth {
  syncedAt: string | null;
  stale: boolean;
  lastError: string | null;
  configured: boolean;
  plugins: number;
}

/** Which registry this engine points at. Never carries the client secret. */
export interface RegistryInfo {
  configured: boolean;
  description: string;
  syncIntervalSeconds: number;
}

export type SyncOutcome = 'UPDATED' | 'UNCHANGED' | 'FAILED' | 'NOT_CONFIGURED';

export interface SyncResult {
  outcome: SyncOutcome;
  plugins: number;
  syncedAt: string | null;
  error: string | null;
  success: boolean;
}

export type InstallationOutcome =
  | 'INSTALLED'
  | 'UPDATED'
  | 'ALREADY_INSTALLED'
  | 'ALREADY_CURRENT'
  | 'UNINSTALLED'
  | 'ACTIVATED'
  | 'DEACTIVATED';

/**
 * What an installation operation did.
 *
 * `warnings` and `previousVersionRetained` are the two fields worth rendering prominently. The first carries
 * the fact that a freshly installed plugin was granted nothing and cannot reach anything, which otherwise
 * looks like the plugin being broken. The second says the old version is still loaded because something still
 * needs it, which is a supported outcome and not a failed update.
 */
export interface InstallationResult {
  pluginId: string;
  version: string;
  outcome: InstallationOutcome;
  state: InstallState | null;
  nodeTypes: string[];
  previousVersion: string | null;
  previousVersionRetained: boolean;
  warnings: string[];
  message: string;
}

export type InstallationAction =
  | 'INSTALL'
  | 'UPDATE'
  | 'UNINSTALL'
  | 'ACTIVATE'
  | 'DEACTIVATE';

/** `REFUSED` is a deliberate no: a dependency check said stop and nothing was changed. */
export type InstallationHistoryOutcome = 'OK' | 'FAILED' | 'REFUSED';

/** One entry in this engine's installation history. */
export interface PluginInstallationRecord {
  id: string;
  pluginId: string;
  version: string;
  fromVersion: string | null;
  action: InstallationAction;
  outcome: InstallationHistoryOutcome;
  checksum: string | null;
  detail: string | null;
  actor: string | null;
  at: string | null;
  durationMillis: number;
}

/** @returns whether this status means the plugin can be installed right now */
export function isInstallable(status: PluginSyncStatus): boolean {
  return status === 'NOT_INSTALLED';
}

/** @returns whether this status means a newer version is waiting */
export function hasUpdate(status: PluginSyncStatus): boolean {
  return status === 'UPDATE_AVAILABLE';
}

/** @returns whether anything of this plugin is installed on this engine */
export function isInstalled(view: PluginStatusView): boolean {
  return view.installedVersion !== null;
}

/**
 * A short sentence explaining a status, for a tooltip or an empty state.
 *
 * Written here rather than in each template so the same status never gets two explanations.
 */
export function describeStatus(status: PluginSyncStatus): string {
  switch (status) {
    case 'REVOKED':
      return 'The registry has withdrawn this plugin. Installed versions still run, but they should be removed.';
    case 'INCOMPATIBLE':
      return 'This engine cannot run the version the registry offers.';
    case 'UPDATE_AVAILABLE':
      return 'A newer version is published. Updating installs it alongside and moves the default to it.';
    case 'DEPRECATED':
      return 'Still supported and still running, but the registry expects a newer version to replace it.';
    case 'INSTALLED':
      return 'Installed at the newest version the registry offers.';
    case 'NOT_INSTALLED':
      return 'Offered by the registry and not installed here.';
    case 'UNKNOWN_TO_REGISTRY':
      return 'Installed here but absent from the catalogue, either uploaded directly or dropped by the registry.';
  }
}
