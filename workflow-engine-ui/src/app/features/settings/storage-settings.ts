import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { StorageApiService } from '../../core/api/storage-api.service';
import { AuthStateService } from '../../core/auth/auth-state.service';
import { NotificationService } from '../../core/notification.service';
import { BytesPipe, AgoPipe } from '../../shared/pipes/format.pipes';
import { Icon } from '../../shared/ui/icon';
import { ConfirmDialog, ConfirmRequest } from '../../shared/ui/confirm-dialog';
import {
  PathProbeResult,
  RETENTION_LABELS,
  RetentionPolicy,
  STORAGE_TYPE_LABELS,
  StorageSettings,
  StorageType,
} from '../../core/models/storage.models';

/**
 * Settings → File Storage.
 *
 * <h2>Test before save, and the interface says so</h2>
 *
 * The save button stays disabled until the current path has been tested and passed. The server validates
 * independently and would refuse a bad path anyway, but making the order visible turns "your path is wrong" from
 * an error into a step — and it stops an administrator from saving a path they never checked and discovering it
 * hours later through somebody's failed upload.
 *
 * <p>Editing anything clears the previous test result, because a result for a path you have since changed is
 * worse than none: it would show a reassuring green tick for a path that was never probed.
 */
@Component({
  selector: 'wf-storage-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, Icon, ConfirmDialog, BytesPipe, AgoPipe],
  template: `
    <div class="page">
      <div class="page-header">
        <div class="page-header__text">
          <h1>File storage</h1>
          <p>
            Where OrchPilot writes files uploaded to a workflow. Files are stored under this root as
            <code>workflows/&#123;workflowId&#125;/v&#123;version&#125;/files</code>, and the database records
            only the path relative to it — so this location can be moved without rewriting a single workflow.
          </p>
        </div>
        <div class="toolbar">
          <button class="btn btn--sm" type="button" (click)="load()">
            <wf-icon name="refresh" /><span>Refresh</span>
          </button>
        </div>
      </div>

      @if (!session.has('WORKFLOW_STORAGE_SETTINGS_VIEW')) {
        <div class="notice notice--warning">
          Viewing the storage configuration requires the ADMIN role. Ask an administrator if you need access.
        </div>
      } @else {
        <!-- Current state, before any editing: what is live right now. -->
        <div class="card" style="margin-bottom: var(--space-4)">
          <div class="card__body">
            <div class="status-row">
              <span class="status-row__label">Status</span>
              @switch (settings()?.status) {
                @case ('CONNECTED') {
                  <span class="pill pill--ok">Connected</span>
                  <span class="status-row__detail">
                    Storage path is valid and writable.
                    @if (probeFreeSpace() >= 0) {
                      {{ probeFreeSpace() | bytes }} free.
                    }
                  </span>
                }
                @case ('INVALID') {
                  <span class="pill pill--bad">Invalid</span>
                  <span class="status-row__detail">
                    The configured path is not usable. Uploads will fail until it is fixed.
                  </span>
                }
                @default {
                  <span class="pill pill--warn">Not configured</span>
                  <span class="status-row__detail">
                    No storage location has been set. Uploads are refused with
                    <code>FILE_STORAGE_NOT_CONFIGURED</code>.
                  </span>
                }
              }
            </div>

            @if (settings(); as current) {
              @if (current.status === 'INVALID' && current.probe) {
                <ul class="problem-list">
                  @for (problem of current.probe.problems; track problem) {
                    <li>{{ problem }}</li>
                  }
                </ul>
              }
              @if (current.updatedAt) {
                <p class="muted">
                  Last changed {{ current.updatedAt | ago }}
                  @if (current.updatedBy) {
                    by {{ current.updatedBy }}
                  }
                </p>
              }
            }
          </div>
        </div>

        <div class="card">
          <div class="card__body">
            <div class="field">
              <label for="storageType">Storage type</label>
              <select
                id="storageType"
                [ngModel]="storageType()"
                (ngModelChange)="onStorageTypeChange($event)"
                [disabled]="!canEdit()"
              >
                @for (type of allTypes(); track type) {
                  <option [value]="type" [disabled]="!isAvailable(type)">
                    {{ label(type) }}{{ isAvailable(type) ? '' : ' — not available in this build' }}
                  </option>
                }
              </select>
              <p class="field__hint">
                Only the local file system ships today. The database records the type per file, so adding object
                storage later leaves existing files resolvable.
              </p>
            </div>

            <div class="field">
              <label for="basePath">Base storage path</label>
              <input
                id="basePath"
                type="text"
                [ngModel]="basePath()"
                (ngModelChange)="onBasePathChange($event)"
                [disabled]="!canEdit()"
                placeholder="D:\\OrchPilot\\data  or  /opt/orchpilot/data"
                spellcheck="false"
                autocomplete="off"
              />
              <p class="field__hint">
                Must be absolute. A relative path would depend on where the process was started from, so the same
                setting would mean different locations in development, as a service, and in a container.
              </p>
            </div>

            <div class="field field--inline">
              <label>
                <input
                  type="checkbox"
                  [ngModel]="createIfMissing()"
                  (ngModelChange)="onCreateIfMissingChange($event)"
                  [disabled]="!canEdit()"
                />
                Create the directory if it does not exist
              </label>
            </div>

            <div class="field field--inline">
              <label>
                <input
                  type="checkbox"
                  [ngModel]="enabled()"
                  (ngModelChange)="enabled.set($event)"
                  [disabled]="!canEdit()"
                />
                Accept uploads
              </label>
              <p class="field__hint">
                Turning this off refuses new uploads without discarding the configured path or deleting anything.
              </p>
            </div>

            <div class="field">
              <label for="retention">Retention after a version is archived</label>
              <select
                id="retention"
                [ngModel]="retentionPolicy()"
                (ngModelChange)="retentionPolicy.set($event)"
                [disabled]="!canEdit()"
              >
                @for (policy of retentionPolicies; track policy) {
                  <option [value]="policy">{{ retentionLabel(policy) }}</option>
                }
              </select>
              @if (retentionPolicy() === 'CUSTOM') {
                <input
                  type="number"
                  min="1"
                  [ngModel]="retentionDays()"
                  (ngModelChange)="retentionDays.set($event)"
                  [disabled]="!canEdit()"
                  placeholder="Days"
                  style="margin-top: var(--space-2); max-width: 12rem"
                />
              }
              <p class="field__hint">
                Stored but <strong>not yet enforced</strong>. Nothing deletes files on a schedule, deliberately —
                the policy is recorded now so that a future sweeper acts on choices you made rather than on a
                default nobody reviewed.
              </p>
            </div>

            <!-- The test result. Cleared whenever the path changes, so it can never describe a stale value. -->
            @if (probe(); as result) {
              <div
                class="notice"
                [class.notice--success]="result.valid"
                [class.notice--error]="!result.valid"
              >
                @if (result.valid) {
                  <strong>✓ Storage path is valid and writable.</strong>
                  <div class="probe-detail">
                    Resolved to <code>{{ result.canonicalPath }}</code>
                    @if (result.created) {
                      · directory created
                    }
                    @if (result.freeSpaceBytes >= 0) {
                      · {{ result.freeSpaceBytes | bytes }} free
                    }
                  </div>
                } @else {
                  <strong>This path cannot be used.</strong>
                  <ul class="problem-list">
                    @for (problem of result.problems; track problem) {
                      <li>{{ problem }}</li>
                    }
                  </ul>
                }
              </div>
            }

            <div class="toolbar toolbar--end">
              <button
                class="btn"
                type="button"
                [disabled]="!canEdit() || !basePath().trim() || testing()"
                (click)="test()"
              >
                {{ testing() ? 'Testing…' : 'Test path' }}
              </button>
              <button
                class="btn btn--primary"
                type="button"
                [disabled]="!canSave()"
                [title]="saveHint()"
                (click)="save()"
              >
                {{ saving() ? 'Saving…' : 'Save' }}
              </button>
              <button class="btn btn--danger" type="button" [disabled]="!canEdit()" (click)="confirmReset()">
                Reset
              </button>
            </div>

            @if (!canEdit()) {
              <p class="muted">
                Changing the storage location requires <code>WORKFLOW_STORAGE_SETTINGS_EDIT</code>, held by
                administrators. Attaching files to a workflow does not — that follows the workflow's own access.
              </p>
            }
          </div>
        </div>
      }
    </div>

    @if (confirmRequest(); as c) {
      <wf-confirm-dialog
        [heading]="c.heading"
        [message]="c.message"
        [confirmLabel]="c.confirmLabel"
        [danger]="c.danger"
        (confirmed)="runConfirmed()"
        (cancelled)="confirmRequest.set(null)"
      />
    }
  `,
  styles: [
    `
      .status-row {
        display: flex;
        align-items: center;
        gap: var(--space-3);
        flex-wrap: wrap;
      }

      .status-row__label {
        font-weight: 600;
        min-width: 5rem;
      }

      .status-row__detail {
        color: var(--text-muted);
      }

      .pill {
        border-radius: 999px;
        padding: 0.15rem 0.7rem;
        font-size: 0.8rem;
        font-weight: 600;
      }

      .pill--ok {
        background: var(--success-bg, #12341f);
        color: var(--success-fg, #6ee7a0);
      }

      .pill--warn {
        background: var(--warning-bg, #3a2d10);
        color: var(--warning-fg, #f5c451);
      }

      .pill--bad {
        background: var(--danger-bg, #3a1616);
        color: var(--danger-fg, #f58585);
      }

      .field {
        margin-bottom: var(--space-4);
      }

      .field--inline label {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        font-weight: 500;
      }

      .field label {
        display: block;
        font-weight: 600;
        margin-bottom: var(--space-2);
      }

      .field input[type='text'],
      .field input[type='number'],
      .field select {
        width: 100%;
        max-width: 40rem;
      }

      .field__hint {
        margin: var(--space-2) 0 0;
        color: var(--text-muted);
        font-size: 0.85rem;
      }

      .problem-list {
        margin: var(--space-2) 0 0;
        padding-left: 1.2rem;
      }

      .probe-detail {
        margin-top: var(--space-2);
        font-size: 0.85rem;
        /* An absolute path can be long; wrap rather than force the card to scroll sideways. */
        overflow-wrap: anywhere;
      }

      .toolbar--end {
        display: flex;
        gap: var(--space-2);
        margin-top: var(--space-4);
      }

      .muted {
        color: var(--text-muted);
        font-size: 0.85rem;
      }
    `,
  ],
})
export class StorageSettingsPage {
  protected readonly session = inject(AuthStateService);

  private readonly api = inject(StorageApiService);
  private readonly notify = inject(NotificationService);

  protected readonly retentionPolicies: RetentionPolicy[] = [
    'NEVER',
    'DAYS_30',
    'DAYS_90',
    'DAYS_180',
    'CUSTOM',
  ];

  protected readonly settings = signal<StorageSettings | null>(null);
  protected readonly probe = signal<PathProbeResult | null>(null);
  protected readonly testing = signal(false);
  protected readonly saving = signal(false);
  protected readonly confirmRequest = signal<ConfirmRequest | null>(null);

  protected readonly storageType = signal<StorageType>('LOCAL');
  protected readonly basePath = signal('');
  protected readonly createIfMissing = signal(true);
  protected readonly enabled = signal(true);
  protected readonly retentionPolicy = signal<RetentionPolicy>('NEVER');
  protected readonly retentionDays = signal<number | null>(null);

  protected readonly canEdit = computed(() => this.session.has('WORKFLOW_STORAGE_SETTINGS_EDIT'));

  /** Free space from the live probe the server ran while returning the settings. */
  protected readonly probeFreeSpace = computed(() => this.settings()?.probe?.freeSpaceBytes ?? -1);

  protected readonly allTypes = computed(() => this.settings()?.allTypes ?? (['LOCAL'] as StorageType[]));

  /** Saving requires a path that has been tested and passed — see the class note. */
  protected readonly canSave = computed(
    () => this.canEdit() && !this.saving() && (this.probe()?.valid ?? false),
  );

  constructor() {
    this.load();
  }

  protected load(): void {
    if (!this.session.has('WORKFLOW_STORAGE_SETTINGS_VIEW')) {
      return;
    }
    this.api.get().subscribe({
      next: (settings) => {
        this.settings.set(settings);
        this.storageType.set(settings.storageType);
        this.basePath.set(settings.basePath ?? '');
        this.enabled.set(settings.enabled);
        this.retentionPolicy.set(settings.retentionPolicy);
        this.retentionDays.set(settings.retentionDays);
        // A saved path was validated when it was saved, so a currently-valid probe may seed the form and let
        // an unrelated change (retention, say) be saved without re-testing an unchanged path.
        this.probe.set(settings.probe?.valid ? settings.probe : null);
      },
      error: () => this.notify.error('Could not load the storage configuration'),
    });
  }

  protected isAvailable(type: StorageType): boolean {
    return (this.settings()?.availableTypes ?? ['LOCAL']).includes(type);
  }

  protected label(type: StorageType): string {
    return STORAGE_TYPE_LABELS[type];
  }

  protected retentionLabel(policy: RetentionPolicy): string {
    return RETENTION_LABELS[policy];
  }

  /** Any change to what would be probed invalidates the previous result. */
  protected onBasePathChange(value: string): void {
    this.basePath.set(value);
    this.probe.set(null);
  }

  protected onStorageTypeChange(value: StorageType): void {
    this.storageType.set(value);
    this.probe.set(null);
  }

  protected onCreateIfMissingChange(value: boolean): void {
    this.createIfMissing.set(value);
    this.probe.set(null);
  }

  protected saveHint(): string {
    if (!this.canEdit()) {
      return 'Requires WORKFLOW_STORAGE_SETTINGS_EDIT';
    }
    return this.probe()?.valid ? 'Save this configuration' : 'Test the path first';
  }

  protected test(): void {
    this.testing.set(true);
    this.api
      .test({
        storageType: this.storageType(),
        basePath: this.basePath().trim(),
        createIfMissing: this.createIfMissing(),
      })
      .subscribe({
        next: (result) => {
          this.testing.set(false);
          this.probe.set(result);
          if (result.valid) {
            this.notify.success('Storage path is valid and writable');
          }
        },
        error: (error) => {
          this.testing.set(false);
          this.probe.set(null);
          this.notify.error('Could not test the path', error?.error?.message ?? '');
        },
      });
  }

  protected save(): void {
    this.saving.set(true);
    this.api
      .save({
        storageType: this.storageType(),
        basePath: this.basePath().trim(),
        createIfMissing: this.createIfMissing(),
        enabled: this.enabled(),
        retentionPolicy: this.retentionPolicy(),
        retentionDays: this.retentionPolicy() === 'CUSTOM' ? this.retentionDays() : null,
      })
      .subscribe({
        next: (settings) => {
          this.saving.set(false);
          this.settings.set(settings);
          this.basePath.set(settings.basePath ?? '');
          this.probe.set(settings.probe?.valid ? settings.probe : null);
          this.notify.success('Storage configuration saved');
        },
        error: (error) => {
          this.saving.set(false);
          this.notify.error('Could not save', error?.error?.message ?? '');
        },
      });
  }

  protected confirmReset(): void {
    this.confirmRequest.set({
      heading: 'Clear the storage configuration?',
      message:
        'Uploads will be refused until a location is configured again. No stored file is deleted — this ' +
        'clears the setting only.',
      confirmLabel: 'Clear configuration',
      danger: true,
      onConfirm: () => this.reset(),
    });
  }

  /** Runs the pending action and clears the dialog, matching the pattern the other admin screens use. */
  protected runConfirmed(): void {
    const request = this.confirmRequest();
    this.confirmRequest.set(null);
    request?.onConfirm();
  }

  private reset(): void {
    this.api.reset().subscribe({
      next: () => {
        this.notify.info('Storage configuration cleared', 'No files were deleted.');
        this.probe.set(null);
        this.load();
      },
      error: () => this.notify.error('Could not clear the configuration'),
    });
  }
}
