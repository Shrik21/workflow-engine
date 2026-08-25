import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { MarketplaceApiService } from '../../core/api/marketplace-api.service';
import { NodeApiService } from '../../core/api/node-api.service';
import { InstallationResult, PluginStatusView } from '../../core/models/marketplace.models';
import { NotificationService } from '../../core/notification.service';
import { Modal } from '../../shared/ui/modal';

/** What the dialog was opened to do. */
export type InstallIntent = 'INSTALL' | 'UPDATE' | 'UNINSTALL';

/**
 * Confirms, runs and reports one installation operation.
 *
 * <h2>Why the result gets a screen rather than a toast</h2>
 *
 * The engine returns two things after an install that a toast would throw away, and both change what the
 * operator has to do next:
 *
 * - **A plugin is granted nothing on install.** No allowed hosts, no secret scopes, whatever its manifest
 *   asked for. Without that stated plainly, the first thing the operator sees is a plugin that fails every
 *   call, which reads as a broken plugin rather than as a deliberate default.
 * - **An update may retain the old version.** When a published workflow pins it, or executions are still
 *   inside it, the old version stays loaded. That is a supported state, not a failure, and saying so is the
 *   difference between confidence and a support ticket.
 *
 * A refusal gets the same treatment. Uninstalling a version a published workflow uses answers 409 with the
 * workflows named, and those names are the entire value of the response.
 */
@Component({
  selector: 'wf-install-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Modal],
  template: `
    <wf-modal
      [heading]="heading()"
      [subheading]="subheading()"
      width="620px"
      [dismissable]="!busy()"
      (closed)="close()"
    >
      @if (result(); as outcome) {
        <div class="notice notice--success">{{ outcome.message }}</div>

        @if (outcome.nodeTypes.length > 0) {
          <p class="small">
            Node types now available:
            @for (nodeType of outcome.nodeTypes; track nodeType) {
              <span class="tag tag--mono">{{ nodeType }}</span>
            }
          </p>
        }

        @if (outcome.previousVersionRetained) {
          <div class="notice notice--warning">
            Version {{ outcome.previousVersion }} is still loaded because something still needs it. Both
            versions run side by side; the older one can be removed once nothing depends on it.
          </div>
        }

        @for (warning of outcome.warnings; track warning) {
          <p class="warning-line small">{{ warning }}</p>
        }
      } @else if (failure(); as problem) {
        <div class="notice notice--error">{{ problem }}</div>
        @if (blockedBy().length > 0) {
          <p class="small">Repoint or unpublish these first:</p>
          <ul class="small blocked">
            @for (workflow of blockedBy(); track workflow) {
              <li>{{ workflow }}</li>
            }
          </ul>
        }
      } @else {
        <p>{{ explanation() }}</p>

        @if (intent() !== 'UNINSTALL') {
          <div class="notice">
            The engine downloads the archive, verifies its SHA-256 against the checksum the registry
            published, and only then loads it. A mismatch installs nothing.
          </div>
          <p class="small muted">
            The plugin is installed with no allowed hosts and no secret scopes, whatever it asked for. Grant
            what it needs afterwards; until then it can make no outbound call and read no credential.
          </p>
        } @else {
          <div class="notice notice--warning">
            The version is unloaded and its archive removed. If a published workflow still uses it, the
            engine refuses and names the workflows rather than breaking them.
          </div>
        }

        @if (busy()) {
          <p class="small muted busy">
            {{ intent() === 'UNINSTALL' ? 'Draining and removing…' : 'Downloading and verifying…' }}
          </p>
        }
      }

      <div modalFooter>
        @if (result() || failure()) {
          <button class="btn btn--primary" type="button" (click)="close()">Done</button>
        } @else {
          <button class="btn" type="button" [disabled]="busy()" (click)="close()">Cancel</button>
          <button
            class="btn"
            [class.btn--danger]="intent() === 'UNINSTALL'"
            [class.btn--primary]="intent() !== 'UNINSTALL'"
            type="button"
            [disabled]="busy()"
            (click)="run()"
          >
            {{ confirmLabel() }}
          </button>
        }
      </div>
    </wf-modal>
  `,
  styles: [
    `
      .warning-line {
        color: var(--text-muted);
        margin: var(--space-2) 0 0;
      }

      .busy {
        margin-top: var(--space-3);
      }

      .blocked {
        margin: var(--space-2) 0 0 var(--space-4);
      }

      .tag {
        margin-right: 4px;
      }
    `,
  ],
})
export class InstallDialog {
  readonly plugin = input.required<PluginStatusView>();
  readonly intent = input.required<InstallIntent>();
  /** The version to act on. Absent on an install means the registry's latest release. */
  readonly version = input<string | null>(null);

  /** Emitted once the operation finished, so the opener can refresh. Not emitted on cancel. */
  readonly completed = output<InstallationResult>();
  readonly closed = output<void>();

  private readonly api = inject(MarketplaceApiService);
  private readonly catalog = inject(NodeApiService);
  private readonly notifications = inject(NotificationService);

  protected readonly busy = signal(false);
  protected readonly result = signal<InstallationResult | null>(null);
  protected readonly failure = signal<string | null>(null);
  protected readonly blockedBy = signal<string[]>([]);

  protected readonly heading = computed(() => {
    switch (this.intent()) {
      case 'INSTALL':
        return `Install ${this.plugin().name || this.plugin().pluginId}`;
      case 'UPDATE':
        return `Update ${this.plugin().name || this.plugin().pluginId}`;
      case 'UNINSTALL':
        return `Remove ${this.plugin().pluginId} ${this.version()}`;
    }
  });

  protected readonly subheading = computed(() =>
    this.result() ? null : 'Third-party code runs inside the engine once this completes.',
  );

  protected readonly confirmLabel = computed(() => {
    switch (this.intent()) {
      case 'INSTALL':
        return `Install ${this.targetVersion() ?? ''}`.trim();
      case 'UPDATE':
        return `Update to ${this.plugin().serverVersion}`;
      case 'UNINSTALL':
        return 'Remove';
    }
  });

  protected readonly explanation = computed(() => {
    const view = this.plugin();
    switch (this.intent()) {
      case 'INSTALL':
        return `Installs ${view.pluginId} ${this.targetVersion()} and loads it, making its node types available with no restart.`;
      case 'UPDATE':
        return (
          `Installs ${view.pluginId} ${view.serverVersion} alongside ${view.installedVersion}, moves the ` +
          `default to it, then drains and unloads the old version. Workflows pinned to ${view.installedVersion} keep running it.`
        );
      case 'UNINSTALL':
        return `Removes ${view.pluginId} ${this.version()} from this engine.`;
    }
  });

  private targetVersion(): string | null {
    return this.version() ?? this.plugin().serverVersion;
  }

  protected run(): void {
    this.busy.set(true);
    this.failure.set(null);
    this.blockedBy.set([]);

    this.call().subscribe({
      next: (outcome) => {
        this.busy.set(false);
        this.result.set(outcome);
        // Both caches are now stale: what is installed changed, and so did the node types on offer.
        this.api.invalidate();
        this.catalog.invalidate();
        this.completed.emit(outcome);
      },
      error: (error: HttpErrorResponse) => {
        this.busy.set(false);
        const message = error.error?.message ?? 'The operation could not be completed.';
        this.failure.set(message);
        this.blockedBy.set(extractWorkflows(message));
        // The interceptor's toast says something failed; this dialog says what and what to do about it.
        this.notifications.error(`${this.plugin().pluginId}: the operation was refused`);
      },
    });
  }

  private call(): Observable<InstallationResult> {
    const pluginId = this.plugin().pluginId;
    switch (this.intent()) {
      case 'INSTALL':
        return this.api.install(pluginId, this.version());
      case 'UPDATE':
        return this.api.update(pluginId);
      case 'UNINSTALL':
        return this.api.uninstall(pluginId, this.version()!);
    }
  }

  protected close(): void {
    if (!this.busy()) {
      this.closed.emit();
    }
  }
}

/**
 * Pulls the workflow names out of the engine's refusal message.
 *
 * The server writes one sentence naming them, which reads well in a log and badly in a dialog. Splitting it
 * into a list is presentation only: no decision is made from the parsed value, so a message the engine
 * rewrites later degrades to showing the sentence and nothing worse.
 */
function extractWorkflows(message: string): string[] {
  const marker = message.indexOf(': ');
  if (!message.startsWith('Published workflows still use') || marker < 0) {
    return [];
  }
  const tail = message.slice(marker + 2).replace(/\.\s*Repoint.*$/, '');
  return tail
    .split(/,\s*(?![^(]*\))/)
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0);
}
