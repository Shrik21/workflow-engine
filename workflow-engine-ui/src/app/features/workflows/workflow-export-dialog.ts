import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { WorkflowApiService } from '../../core/api/workflow-api.service';
import { NotificationService } from '../../core/notification.service';
import { ExportRequest } from '../../core/models/workflow.models';
import { Modal } from '../../shared/ui/modal';

/**
 * The export dialog: choose what to include and how to protect the file, then download an encrypted
 * `.orchpilot`.
 *
 * <h2>The one thing this dialog will not do</h2>
 *
 * There is no "include credentials" switch, by design and not by omission. A workflow definition never holds a
 * secret value — only a reference to one — so the export already carries none, and offering a control that
 * implied otherwise would be a trap. What the file does carry, so the person importing knows what to supply, is
 * the list of credential <em>references</em>; the import side shows them.
 */
@Component({
  selector: 'wf-workflow-export-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, Modal],
  template: `
    <wf-modal
      heading="Export workflow"
      [subheading]="'Downloads an encrypted .orchpilot file. Secrets are never exported.'"
      width="520px"
      [dismissable]="!busy()"
      (closed)="close()"
    >
      <div class="stack">
        <p class="small muted">
          Exporting <strong>{{ name() }}</strong> — its graph, and whichever of the following you include.
        </p>

        <fieldset class="option-group">
          <legend class="small muted">Include</legend>
          <label class="check"><input type="checkbox" [(ngModel)]="includeForms" /> Forms</label>
          <label class="check"><input type="checkbox" [(ngModel)]="includeVariables" /> Variables</label>
          <label class="check">
            <input type="checkbox" [(ngModel)]="includePluginDependencies" /> Plugin dependencies
          </label>
          <label class="check">
            <input type="checkbox" [(ngModel)]="includePermissions" /> Access groups
          </label>
        </fieldset>

        <fieldset class="option-group">
          <legend class="small muted">Protection</legend>
          <label class="check">
            <input type="radio" name="mode" value="PLATFORM" [(ngModel)]="mode" />
            Platform key — importable only into this environment
          </label>
          <label class="check">
            <input type="radio" name="mode" value="PASSWORD" [(ngModel)]="mode" />
            Password — importable anywhere with the password
          </label>
        </fieldset>

        @if (mode() === 'PASSWORD') {
          <div class="stack">
            <label class="field">
              <span class="small muted">Password</span>
              <input
                type="password"
                autocomplete="new-password"
                [(ngModel)]="password"
                placeholder="At least 8 characters"
              />
            </label>
            <label class="field">
              <span class="small muted">Confirm password</span>
              <input type="password" autocomplete="new-password" [(ngModel)]="confirm" />
            </label>
            @if (passwordProblem(); as problem) {
              <p class="small" style="color: var(--danger)">{{ problem }}</p>
            }
            <p class="small muted">
              The password is never stored. If it is lost, the file cannot be opened — there is no recovery.
            </p>
          </div>
        }
      </div>

      <div modalFooter style="display: flex; gap: var(--space-3); justify-content: flex-end">
        <button class="btn" type="button" [disabled]="busy()" (click)="close()">Cancel</button>
        <button
          class="btn btn--primary"
          type="button"
          [disabled]="busy() || !canExport()"
          (click)="doExport()"
        >
          {{ busy() ? 'Exporting…' : 'Export' }}
        </button>
      </div>
    </wf-modal>
  `,
  styles: [
    `
      .stack {
        display: flex;
        flex-direction: column;
        gap: var(--space-3);
      }
      .option-group {
        border: 1px solid var(--border);
        border-radius: var(--radius);
        padding: var(--space-3);
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
      }
      .check {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        cursor: pointer;
      }
      .field {
        display: flex;
        flex-direction: column;
        gap: var(--space-1);
      }
    `,
  ],
})
export class WorkflowExportDialog {
  readonly workflowId = input.required<string>();
  readonly name = input.required<string>();
  readonly closed = output<void>();

  private readonly api = inject(WorkflowApiService);
  private readonly notifications = inject(NotificationService);

  protected readonly includeForms = signal(true);
  protected readonly includeVariables = signal(true);
  protected readonly includePluginDependencies = signal(true);
  protected readonly includePermissions = signal(true);
  protected readonly mode = signal<'PLATFORM' | 'PASSWORD'>('PLATFORM');
  protected readonly password = signal('');
  protected readonly confirm = signal('');
  protected readonly busy = signal(false);

  /** The reason the password pair is not yet acceptable, or null when it is. */
  protected passwordProblem(): string | null {
    if (this.mode() !== 'PASSWORD') {
      return null;
    }
    if (this.password().length > 0 && this.password().length < 8) {
      return 'Use at least 8 characters.';
    }
    if (this.confirm().length > 0 && this.password() !== this.confirm()) {
      return 'The passwords do not match.';
    }
    return null;
  }

  protected canExport(): boolean {
    if (this.mode() !== 'PASSWORD') {
      return true;
    }
    return this.password().length >= 8 && this.password() === this.confirm();
  }

  protected doExport(): void {
    if (this.busy() || !this.canExport()) {
      return;
    }
    const request: ExportRequest = {
      includeForms: this.includeForms(),
      includeVariables: this.includeVariables(),
      includePluginDependencies: this.includePluginDependencies(),
      includePermissions: this.includePermissions(),
      encryptionMode: this.mode(),
      password: this.mode() === 'PASSWORD' ? this.password() : null,
    };
    this.busy.set(true);
    this.api.export(this.workflowId(), request).subscribe({
      next: (result) => {
        this.triggerDownload(result.blob, result.fileName);
        this.busy.set(false);
        this.notifications.success(`Exported "${this.name()}"`);
        this.close();
      },
      error: () => this.busy.set(false),
    });
  }

  /** Hands the browser a generated file to save. This is the app's own export, initiated by the operator. */
  private triggerDownload(blob: Blob, fileName: string): void {
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = fileName;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
  }

  protected close(): void {
    if (this.busy()) {
      return;
    }
    this.closed.emit();
  }
}
