import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AiCliApiService } from '../../core/api/ai-cli-api.service';
import { AuthStateService } from '../../core/auth/auth-state.service';
import { NotificationService } from '../../core/notification.service';
import { AgoPipe } from '../../shared/pipes/format.pipes';
import { Icon } from '../../shared/ui/icon';
import { ConfirmDialog, ConfirmRequest } from '../../shared/ui/confirm-dialog';
import {
  AiCliConfiguration,
  AiCliDetectionCandidate,
  AiCliFeatureStatus,
  AiCliOperatingSystem,
  AiCliTestResult,
  OS_LABELS,
  OS_PATH_EXAMPLES,
} from '../../core/models/ai-cli.models';

/**
 * Settings → AI Configuration → Claude CLI.
 *
 * <h2>The page is honest about the two gates</h2>
 *
 * Pointing the engine at an executable is a stronger thing than configuring an API endpoint, and the interface
 * says so rather than hiding it. When the host has the feature switched off, the form is shown read-only above
 * an explanation of which configuration property an operator must set — because the alternative, a working-
 * looking form whose every button fails, teaches nothing.
 *
 * <h2>Detect, then test</h2>
 *
 * Auto-detection offers what it found and never fills the field silently; the operator chooses. Test Connection
 * is the only thing that turns the status green, and editing the path clears a previous result — a green tick
 * for a path that was since changed is worse than no tick at all.
 */
@Component({
  selector: 'wf-claude-cli-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, Icon, ConfirmDialog, AgoPipe],
  template: `
    <div class="page">
      <div class="page-header">
        <div class="page-header__text">
          <h1>Claude CLI</h1>
          <p>
            An AI command-line tool the engine may run to explain failures and assist with infrastructure
            operations. It is assistive only — it never replaces the workflow engine, the plugin engine, or any
            security control, and it cannot grant a permission.
          </p>
        </div>
        <div class="toolbar">
          <button class="btn btn--sm" type="button" (click)="load()">
            <wf-icon name="refresh" /><span>Refresh</span>
          </button>
          @if (canCreate() && feature()?.enabled) {
            <button class="btn btn--sm btn--primary" type="button" (click)="startCreate()">
              <wf-icon name="add" /><span>Add configuration</span>
            </button>
          }
        </div>
      </div>

      @if (!session.has('AI_CLI_VIEW')) {
        <div class="notice notice--warning">
          Viewing AI CLI configuration requires the <code>AI_CLI_VIEW</code> permission. Ask an administrator
          if you need access.
        </div>
      } @else {
        <!-- The host-level gate. Shown first, because nothing below it works while this is off. -->
        @if (feature(); as f) {
          @if (!f.enabled) {
            <div class="notice notice--warning" style="margin-bottom: var(--space-4)">
              <strong>AI CLI execution is disabled on this engine.</strong>
              <p style="margin: var(--space-2) 0 0">
                Running a local program is a stronger capability than calling an API, so it is switched off
                until an operator opts in. Set
                <code>workflow.engine.ai.cli.enabled: true</code> in the engine's configuration and restart.
                This cannot be changed from here, by design.
              </p>
            </div>
          } @else {
            <div class="notice" style="margin-bottom: var(--space-4)">
              Enabled on this engine. Host operating system:
              <strong>{{ osLabel(f.hostOperatingSystem) }}</strong>. Each invocation is stopped after
              {{ f.timeoutSeconds }}s.
              @if (f.directoriesRestricted) {
                Executables are restricted to an operator-configured set of directories.
              }
            </div>
          }
        }

        <!-- Containers catch people out often enough to warrant saying it up front. -->
        <div class="notice notice--info" style="margin-bottom: var(--space-4)">
          If OrchPilot runs inside Docker, the Claude CLI must be installed
          <strong>inside the OrchPilot runtime container</strong>. A path on the host machine is not reachable
          from within the container, and the engine will not try to reach one.
        </div>

        @if (loading()) {
          <div class="empty">Loading…</div>
        } @else if (!configurations().length && !editing()) {
          <div class="empty">
            <p>No AI CLI is configured.</p>
            @if (canCreate() && feature()?.enabled) {
              <button class="btn btn--primary" type="button" (click)="startCreate()">
                Add a configuration
              </button>
            }
          </div>
        }

        <!-- Existing configurations -->
        @for (configuration of configurations(); track configuration.id) {
          <div class="card" style="margin-bottom: var(--space-3)">
            <div class="card__body">
              <div class="status-row">
                <span class="status-row__label">{{ configuration.name }}</span>
                @switch (configuration.status) {
                  @case ('CONNECTED') {
                    <span class="pill pill--ok">Connected</span>
                  }
                  @case ('ERROR') {
                    <span class="pill pill--bad">Error</span>
                  }
                  @default {
                    <span class="pill pill--warn">Not configured</span>
                  }
                }
                @if (configuration.defaultConfiguration) {
                  <span class="pill">Default</span>
                }
                @if (!configuration.enabled) {
                  <span class="pill pill--warn">Disabled</span>
                }
              </div>

              <dl class="detail-grid">
                <dt>Operating system</dt>
                <dd>{{ osLabel(configuration.operatingSystem) }}</dd>
                <dt>Executable</dt>
                <dd><code>{{ configuration.executablePath }}</code></dd>
                @if (configuration.version) {
                  <dt>Version</dt>
                  <dd>{{ configuration.version }}</dd>
                }
                @if (configuration.lastCheckedAt) {
                  <dt>Last checked</dt>
                  <dd>{{ configuration.lastCheckedAt | ago }}</dd>
                }
                @if (configuration.lastError) {
                  <dt>Last error</dt>
                  <dd class="text-bad">{{ configuration.lastError }}</dd>
                }
              </dl>

              <div class="toolbar">
                @if (canExecute() && feature()?.enabled) {
                  <button
                    class="btn btn--sm"
                    type="button"
                    [disabled]="testing() === configuration.id"
                    (click)="test(configuration)"
                  >
                    <wf-icon name="run" />
                    <span>{{ testing() === configuration.id ? 'Testing…' : 'Test connection' }}</span>
                  </button>
                }
                @if (canUpdate()) {
                  <button class="btn btn--sm" type="button" (click)="startEdit(configuration)">
                    <span>Edit</span>
                  </button>
                }
                @if (canDelete()) {
                  <button class="btn btn--sm btn--danger" type="button" (click)="askDelete(configuration)">
                    <wf-icon name="delete" /><span>Delete</span>
                  </button>
                }
              </div>
            </div>
          </div>
        }

        <!-- The editor -->
        @if (editing()) {
          <div class="card">
            <div class="card__body">
              <h2>{{ editingId() ? 'Edit configuration' : 'New configuration' }}</h2>

              <label class="field">
                <span class="field__label">Configuration name</span>
                <input
                  class="input"
                  type="text"
                  [ngModel]="name()"
                  (ngModelChange)="name.set($event)"
                  placeholder="Claude CLI - Windows Development"
                />
                <span class="field__hint">
                  Names the host this points at, e.g. "Claude CLI - Ubuntu Server".
                </span>
              </label>

              <label class="field">
                <span class="field__label">Operating system</span>
                <select
                  class="input"
                  [ngModel]="operatingSystem()"
                  (ngModelChange)="changeOs($event)"
                >
                  <option value="WINDOWS">Windows</option>
                  <option value="UBUNTU">Ubuntu</option>
                  <option value="LINUX">Linux</option>
                </select>
                @if (feature(); as f) {
                  @if (f.hostOperatingSystem !== operatingSystem() && bothPosix(f) === false) {
                    <span class="field__hint text-warn">
                      This engine runs on {{ osLabel(f.hostOperatingSystem) }}. A configuration for
                      {{ osLabel(operatingSystem()) }} can be saved, but cannot be executed here.
                    </span>
                  }
                }
              </label>

              <label class="field">
                <span class="field__label">Claude CLI executable path</span>
                <input
                  class="input"
                  type="text"
                  [ngModel]="executablePath()"
                  (ngModelChange)="changePath($event)"
                  [placeholder]="pathExample()"
                />
                <span class="field__hint">
                  The full path to the program — no arguments, and no shell syntax. Example:
                  <code>{{ pathExample() }}</code>
                </span>
              </label>

              <div class="toolbar">
                @if (canCreate() && feature()?.enabled) {
                  <button
                    class="btn btn--sm"
                    type="button"
                    [disabled]="detecting()"
                    (click)="detect()"
                  >
                    <span>{{ detecting() ? 'Searching…' : 'Detect automatically' }}</span>
                  </button>
                }
              </div>

              <!-- Candidates are offered, never applied silently: which one is right is the operator's call. -->
              @if (candidates().length) {
                <div class="notice" style="margin-top: var(--space-3)">
                  <strong>Found on this host:</strong>
                  <ul style="margin: var(--space-2) 0 0; padding-left: var(--space-4)">
                    @for (candidate of candidates(); track candidate.path) {
                      <li style="margin-bottom: var(--space-2)">
                        <code>{{ candidate.path }}</code>
                        <span class="field__hint"> ({{ candidate.source }})</span>
                        <button
                          class="btn btn--sm"
                          type="button"
                          style="margin-left: var(--space-2)"
                          (click)="changePath(candidate.path)"
                        >
                          Use this
                        </button>
                      </li>
                    }
                  </ul>
                </div>
              } @else if (detected()) {
                <div class="notice notice--warning" style="margin-top: var(--space-3)">
                  No Claude CLI was found on this engine host. Install it, or enter the path manually if you
                  know where it is.
                </div>
              }

              <label class="field field--inline">
                <input
                  type="checkbox"
                  [ngModel]="enabled()"
                  (ngModelChange)="enabled.set($event)"
                />
                <span class="field__label">Enabled</span>
              </label>

              <label class="field field--inline">
                <input
                  type="checkbox"
                  [ngModel]="isDefault()"
                  (ngModelChange)="isDefault.set($event)"
                />
                <span class="field__label">Default configuration</span>
                <span class="field__hint">
                  Used when an operation does not name one. Making this the default clears the previous one.
                </span>
              </label>

              @if (testResult(); as result) {
                <div
                  class="notice"
                  [class.notice--warning]="!result.success"
                  style="margin-top: var(--space-3)"
                >
                  {{ result.message }}
                </div>
              }

              <div class="toolbar" style="margin-top: var(--space-4)">
                <button
                  class="btn btn--primary"
                  type="button"
                  [disabled]="!canSave() || saving()"
                  (click)="save()"
                >
                  {{ saving() ? 'Saving…' : 'Save' }}
                </button>
                <button class="btn" type="button" (click)="cancel()">Cancel</button>
              </div>
            </div>
          </div>
        }
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
      .detail-grid {
        display: grid;
        grid-template-columns: max-content 1fr;
        gap: var(--space-2) var(--space-4);
        margin: var(--space-3) 0;
      }
      .detail-grid dt {
        color: var(--text-muted);
        font-size: 0.875rem;
      }
      .detail-grid dd {
        margin: 0;
        word-break: break-all;
      }
      .field--inline {
        display: flex;
        align-items: center;
        gap: var(--space-2);
      }
      .text-bad {
        color: var(--danger, #b3261e);
      }
      .text-warn {
        color: var(--warning, #8a6100);
      }
    `,
  ],
})
export class ClaudeCliSettingsPage {
  private readonly api = inject(AiCliApiService);

  private readonly notify = inject(NotificationService);

  protected readonly session = inject(AuthStateService);

  protected readonly loading = signal(false);

  protected readonly saving = signal(false);

  protected readonly detecting = signal(false);

  protected readonly detected = signal(false);

  protected readonly testing = signal<string | null>(null);

  protected readonly feature = signal<AiCliFeatureStatus | null>(null);

  protected readonly configurations = signal<AiCliConfiguration[]>([]);

  protected readonly candidates = signal<AiCliDetectionCandidate[]>([]);

  protected readonly testResult = signal<AiCliTestResult | null>(null);

  protected readonly confirmRequest = signal<ConfirmRequest | null>(null);

  // ---- editor state
  protected readonly editing = signal(false);

  protected readonly editingId = signal<string | null>(null);

  protected readonly name = signal('');

  protected readonly operatingSystem = signal<AiCliOperatingSystem>('WINDOWS');

  protected readonly executablePath = signal('');

  protected readonly enabled = signal(true);

  protected readonly isDefault = signal(false);

  protected readonly canCreate = computed(() => this.session.has('AI_CLI_CREATE'));

  protected readonly canUpdate = computed(() => this.session.has('AI_CLI_UPDATE'));

  protected readonly canDelete = computed(() => this.session.has('AI_CLI_DELETE'));

  protected readonly canExecute = computed(() => this.session.has('AI_CLI_EXECUTE'));

  protected readonly pathExample = computed(() => OS_PATH_EXAMPLES[this.operatingSystem()]);

  protected readonly canSave = computed(
    () => this.name().trim().length > 0 && this.executablePath().trim().length > 0,
  );

  constructor() {
    this.load();
  }

  protected osLabel(os: AiCliOperatingSystem): string {
    return OS_LABELS[os] ?? os;
  }

  /** Windows and a POSIX target are incompatible; Ubuntu and Linux are not. */
  protected bothPosix(feature: AiCliFeatureStatus): boolean {
    const hostPosix = feature.hostOperatingSystem !== 'WINDOWS';
    const targetPosix = this.operatingSystem() !== 'WINDOWS';
    return hostPosix === targetPosix;
  }

  protected load(): void {
    if (!this.session.has('AI_CLI_VIEW')) {
      return;
    }
    this.loading.set(true);
    this.api.status().subscribe({
      next: (status) => {
        this.feature.set(status);
        // Default the editor to the host's own OS: it is right far more often than not.
        if (!this.editing()) {
          this.operatingSystem.set(status.hostOperatingSystem);
        }
      },
      error: () => this.feature.set(null),
    });
    this.api.list().subscribe({
      next: (list) => {
        this.configurations.set(list);
        this.loading.set(false);
      },
      error: (error) => {
        this.loading.set(false);
        this.notify.error(this.message(error, 'Could not load AI CLI configurations.'));
      },
    });
  }

  protected startCreate(): void {
    this.editingId.set(null);
    this.name.set('');
    this.operatingSystem.set(this.feature()?.hostOperatingSystem ?? 'WINDOWS');
    this.executablePath.set('');
    this.enabled.set(true);
    this.isDefault.set(this.configurations().length === 0);
    this.resetTransient();
    this.editing.set(true);
  }

  protected startEdit(configuration: AiCliConfiguration): void {
    this.editingId.set(configuration.id);
    this.name.set(configuration.name);
    this.operatingSystem.set(configuration.operatingSystem);
    this.executablePath.set(configuration.executablePath);
    this.enabled.set(configuration.enabled);
    this.isDefault.set(configuration.defaultConfiguration);
    this.resetTransient();
    this.editing.set(true);
  }

  protected cancel(): void {
    this.editing.set(false);
    this.resetTransient();
  }

  protected changeOs(os: AiCliOperatingSystem): void {
    this.operatingSystem.set(os);
    // Candidates were found for the previous OS and a path for one OS is invalid for the other.
    this.resetTransient();
  }

  protected changePath(path: string): void {
    this.executablePath.set(path);
    // A test result describes the path it was run against; keeping it after an edit would be a false green.
    this.testResult.set(null);
  }

  protected detect(): void {
    this.detecting.set(true);
    this.api.detect().subscribe({
      next: (detection) => {
        this.detecting.set(false);
        this.detected.set(true);
        this.candidates.set(detection.candidates);
        if (!detection.candidates.length) {
          return;
        }
        // Offered, not applied: the first candidate is usually right, but "usually" is not good enough to
        // silently point the engine at a binary.
        this.notify.info(
          `Found ${detection.candidates.length} candidate${
            detection.candidates.length === 1 ? '' : 's'
          }. Choose the one to use.`,
        );
      },
      error: (error) => {
        this.detecting.set(false);
        this.notify.error(this.message(error, 'Detection failed.'));
      },
    });
  }

  protected test(configuration: AiCliConfiguration): void {
    this.testing.set(configuration.id);
    this.api.test(configuration.id).subscribe({
      next: (result) => {
        this.testing.set(null);
        this.testResult.set(result);
        if (result.success) {
          this.notify.success(result.message);
        } else {
          this.notify.error(result.message);
        }
        this.load();
      },
      error: (error) => {
        this.testing.set(null);
        this.notify.error(this.message(error, 'The connection test could not be run.'));
      },
    });
  }

  protected save(): void {
    if (!this.canSave()) {
      return;
    }
    this.saving.set(true);
    const request = {
      name: this.name().trim(),
      type: 'CLAUDE_CLI',
      operatingSystem: this.operatingSystem(),
      executablePath: this.executablePath().trim(),
      enabled: this.enabled(),
      defaultConfiguration: this.isDefault(),
    };
    const id = this.editingId();
    const call = id ? this.api.update(id, request) : this.api.create(request);

    call.subscribe({
      next: () => {
        this.saving.set(false);
        this.editing.set(false);
        this.resetTransient();
        this.notify.success('Configuration saved.');
        this.load();
      },
      error: (error) => {
        this.saving.set(false);
        // The server's message names the exact problem with the path, which is the useful part.
        this.notify.error(this.message(error, 'The configuration could not be saved.'));
      },
    });
  }

  protected askDelete(configuration: AiCliConfiguration): void {
    this.confirmRequest.set({
      heading: 'Delete this configuration?',
      message:
        `"${configuration.name}" will be removed from OrchPilot.\n\n` +
        'Nothing is uninstalled or deleted on the host — this clears the configuration only.',
      confirmLabel: 'Delete',
      danger: true,
      onConfirm: () => this.remove(configuration),
    });
  }

  /** Runs the pending action and clears the dialog, matching the pattern the other admin screens use. */
  protected runConfirmed(): void {
    const request = this.confirmRequest();
    this.confirmRequest.set(null);
    request?.onConfirm();
  }

  private remove(configuration: AiCliConfiguration): void {
    this.api.remove(configuration.id).subscribe({
      next: () => {
        this.notify.success('Configuration deleted.');
        this.load();
      },
      error: (error) => this.notify.error(this.message(error, 'The configuration could not be deleted.')),
    });
  }

  private resetTransient(): void {
    this.candidates.set([]);
    this.detected.set(false);
    this.testResult.set(null);
  }

  private message(error: unknown, fallback: string): string {
    const body = (error as { error?: { message?: string; detail?: string } })?.error;
    return body?.message ?? body?.detail ?? fallback;
  }
}
