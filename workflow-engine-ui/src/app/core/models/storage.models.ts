/**
 * Types for Settings → File Storage and for workflow file attachments.
 *
 * These mirror the server's DTOs. Note what is absent: nothing here carries a file's path. The server never
 * returns one, because a client needs a `fileId` and a download endpoint, not a location — and a path in the
 * browser would be both a leak of the deployment's layout and an invitation to construct one.
 */

export type StorageType = 'LOCAL' | 'S3' | 'AZURE_BLOB' | 'GCP_STORAGE' | 'NFS';

export type StorageStatus = 'NOT_CONFIGURED' | 'CONNECTED' | 'INVALID';

export type RetentionPolicy = 'NEVER' | 'DAYS_30' | 'DAYS_90' | 'DAYS_180' | 'CUSTOM';

/** The outcome of testing a candidate path. `problems` is empty exactly when `valid`. */
export interface PathProbeResult {
  valid: boolean;
  canonicalPath: string;
  readable: boolean;
  writable: boolean;
  created: boolean;
  freeSpaceBytes: number;
  problems: string[];
}

export interface StorageSettings {
  storageType: StorageType;
  basePath: string | null;
  enabled: boolean;
  status: StorageStatus;
  retentionPolicy: RetentionPolicy;
  retentionDays: number | null;
  /** Types with a working provider in this build; everything else is disabled in the dropdown. */
  availableTypes: StorageType[];
  /** Every type the platform knows about, so planned ones are visible as "not yet available". */
  allTypes: StorageType[];
  probe: PathProbeResult | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface StorageSettingsUpdate {
  storageType: StorageType;
  basePath: string;
  createIfMissing: boolean;
  enabled: boolean;
  retentionPolicy: RetentionPolicy;
  retentionDays: number | null;
}

export interface StorageTestRequest {
  storageType: StorageType;
  basePath: string;
  createIfMissing: boolean;
}

export interface StorageHealth {
  status: StorageStatus;
  pathConfigured: boolean;
  enabled: boolean;
  readable: boolean;
  writable: boolean;
  freeSpaceBytes: number;
  fileCount: number;
  problems: string[];
}

/** One file attached to a workflow version. */
export interface WorkflowFile {
  fileId: string;
  workflowId: string;
  workflowVersion: number;
  fileName: string;
  contentType: string;
  size: number;
  checksum: string;
  storageType: StorageType;
  uploadedBy: string;
  uploadedAt: string;
  /** False when the reference exists but its content is missing from storage. */
  downloadAvailable: boolean;
}

export interface StorageConsistencyReport {
  workflowId: string;
  checked: number;
  missingFromStorage: string[];
  orphanedInStorage: string[];
}

/** Human-readable labels for the storage types, used in the settings dropdown. */
export const STORAGE_TYPE_LABELS: Record<StorageType, string> = {
  LOCAL: 'Local file system',
  S3: 'Amazon S3',
  AZURE_BLOB: 'Azure Blob Storage',
  GCP_STORAGE: 'Google Cloud Storage',
  NFS: 'Network share (NFS)',
};

export const RETENTION_LABELS: Record<RetentionPolicy, string> = {
  NEVER: 'Keep forever',
  DAYS_30: '30 days',
  DAYS_90: '90 days',
  DAYS_180: '180 days',
  CUSTOM: 'Custom',
};
