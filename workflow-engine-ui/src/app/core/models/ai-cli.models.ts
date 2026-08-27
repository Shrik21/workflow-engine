/**
 * Types for Settings → AI Configuration.
 *
 * These mirror the server's DTOs. Note what is absent: no credential of any kind, and no raw output from the
 * executable. The server never returns either — a CLI authenticates itself, and a third-party binary's stdout
 * is not something to render in a browser.
 */

export type AiCliOperatingSystem = 'WINDOWS' | 'UBUNTU' | 'LINUX';

export type AiCliStatus = 'NOT_CONFIGURED' | 'CONNECTED' | 'ERROR';

/** One AI CLI configuration. */
export interface AiCliConfiguration {
  id: string;
  name: string;
  type: string;
  operatingSystem: AiCliOperatingSystem;
  executablePath: string;
  enabled: boolean;
  defaultConfiguration: boolean;
  status: AiCliStatus;
  version: string | null;
  lastCheckedAt: string | null;
  /** Why the last check failed. Composed by the server, never the executable's own output. */
  lastError: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface AiCliConfigurationUpdate {
  name: string;
  type: string;
  operatingSystem: AiCliOperatingSystem;
  executablePath: string;
  enabled: boolean;
  defaultConfiguration: boolean;
  secretName?: string | null;
}

/** An installed CLI adapter. Types with no adapter are shown as unavailable rather than hidden. */
export interface AiCliProviderInfo {
  type: string;
  displayName: string;
  command: string;
}

/**
 * Whether the feature is usable on this engine at all.
 *
 * `enabled` is set in the engine's configuration file and cannot be changed from here — deliberately, since it
 * governs whether the server will run local binaries.
 */
export interface AiCliFeatureStatus {
  enabled: boolean;
  hostOperatingSystem: AiCliOperatingSystem;
  timeoutSeconds: number;
  directoriesRestricted: boolean;
  providers: AiCliProviderInfo[];
}

export interface AiCliTestResult {
  success: boolean;
  version: string | null;
  path: string;
  operatingSystem: AiCliOperatingSystem;
  errorCode: string | null;
  message: string;
  durationMillis: number;
}

export interface AiCliDetectionCandidate {
  path: string;
  /** How it was found — 'PATH' or 'common install location'. */
  source: string;
}

export interface AiCliDetection {
  hostOperatingSystem: AiCliOperatingSystem;
  command: string;
  candidates: AiCliDetectionCandidate[];
}

/**
 * An AI explanation of a failed node.
 *
 * `verified` is the field that matters: everything else came from a language model, and `verified` says whether
 * the IAM claims were confirmed against the engine's own reference. A view that renders the recommendation
 * without rendering this distinction presents a guess as a fact.
 */
export interface ErrorAnalysis {
  success: boolean;
  errorType: string | null;
  missingPermission: string | null;
  recommendedRole: string | null;
  resource: string | null;
  reason: string | null;
  securityRisk: 'LOW' | 'MEDIUM' | 'HIGH' | null;
  canRetry: boolean;
  recommendedAction: string | null;
  verified: boolean;
  warnings: string[];
  analysedBy: string | null;
}

/** The engine's own IAM reference for one permission, with no AI involved. */
export interface IamLookup {
  permission: string;
  wellFormed: boolean;
  known: boolean;
  leastPrivilegeRole: string | null;
  roles: string[];
}

export const OS_LABELS: Record<AiCliOperatingSystem, string> = {
  WINDOWS: 'Windows',
  UBUNTU: 'Ubuntu',
  LINUX: 'Linux',
};

/** Example paths shown as the field's placeholder, so the expected shape is obvious per OS. */
export const OS_PATH_EXAMPLES: Record<AiCliOperatingSystem, string> = {
  WINDOWS: 'C:\\Users\\you\\AppData\\Roaming\\npm\\claude.cmd',
  UBUNTU: '/usr/local/bin/claude',
  LINUX: '/usr/bin/claude',
};

/** CLI types the UI can offer. Only those with an installed adapter are selectable. */
export const AI_CLI_TYPE_LABELS: Record<string, string> = {
  CLAUDE_CLI: 'Claude CLI',
  OPENAI_CLI: 'OpenAI CLI',
  GEMINI_CLI: 'Gemini CLI',
  OLLAMA_CLI: 'Ollama CLI',
};
