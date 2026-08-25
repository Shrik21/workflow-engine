import { ChangeDetectionStrategy, Component, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { WorkflowApiService } from '../../core/api/workflow-api.service';
import { NotificationService } from '../../core/notification.service';
import { ImportResult, ImportValidationResult } from '../../core/models/workflow.models';
import { Modal } from '../../shared/ui/modal';

/**
 * The import wizard: upload an `.orchpilot` file, review what it will bring in, then import it.
 *
 * <h2>Validate before importing, always</h2>
 *
 * The file is untrusted. The first step never writes anything — it decrypts and checks the file server-side and
 * shows a preview: which plugins are missing or out of date, which credential references the operator must map
 * afterwards, which access groups the file mentions, and whether a workflow of the same origin already exists.
 * Only a second, deliberate click imports, and even then the server creates a brand-new workflow with new ids
 * in the operator's own tenant — an existing workflow is never overwritten, so there is no destructive path to
 * guard here beyond making the two steps distinct.
 */
@Component({
  selector: 'wf-workflow-import-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, Modal],
  template: `
    <wf-modal
      [heading]="step() === 'done' ? 'Import complete' : 'Import workflow'"
      [subheading]="subheading()"
      width="560px"
      [dismissable]="!busy()"
      (closed)="close()"
    >
      @switch (step()) {
        @case ('select') {
          <div class="stack">
            <label class="field">
              <span class="small muted">.orchpilot file</span>
              <input type="file" accept=".orchpilot" (change)="onFile($event)" />
            </label>
            <label class="field">
              <span class="small muted">Password (only for password-protected files)</span>
              <input type="password" autocomplete="off" [(ngModel)]="password" />
            </label>
            @if (error(); as message) {
              <p class="small" style="color: var(--danger)">{{ message }}</p>
            }
          </div>
        }

        @case ('preview') {
          @if (preview(); as p) {
            <div class="stack">
              <div class="summary">
                <div class="summary__name">{{ p.name }}</div>
                @if (p.description) {
                  <div class="small muted">{{ p.description }}</div>
                }
                <div class="small muted">
                  Exported by {{ p.exportedBy || 'unknown' }} · source version v{{ p.sourceVersion }}
                </div>
              </div>

              @if (p.conflict) {
                <div class="notice notice--warn">
                  A workflow from the same source already exists here. Importing creates a separate new
                  workflow — nothing is overwritten.
                </div>
              }

              <section>
                <h4 class="section-title">Plugins</h4>
                @if (p.plugins.length === 0) {
                  <p class="small muted">This workflow uses no plugins.</p>
                } @else {
                  <ul class="plain">
                    @for (plugin of p.plugins; track plugin.pluginId) {
                      <li class="row">
                        <span class="mono">{{ plugin.pluginId }}</span>
                        <span class="small muted">needs v{{ plugin.requiredVersion || 'any' }}</span>
                        @switch (plugin.compatibility) {
                          @case ('COMPATIBLE') {
                            <span class="badge badge--ok">installed v{{ plugin.installedVersion }}</span>
                          }
                          @case ('INCOMPATIBLE') {
                            <span class="badge badge--warn">
                              only v{{ plugin.installedVersion }} — update needed
                            </span>
                          }
                          @case ('MISSING') {
                            <span class="badge badge--bad">not installed</span>
                          }
                        }
                      </li>
                    }
                  </ul>
                  @if (p.missingPlugins.length > 0) {
                    <p class="small muted">
                      Install or update these before running the imported workflow. It imports either way.
                    </p>
                  }
                }
              </section>

              @if (p.credentialReferences.length > 0) {
                <section>
                  <h4 class="section-title">Credentials to map</h4>
                  <p class="small muted">
                    No secrets were exported. After importing, point these references at credentials in this
                    environment.
                  </p>
                  <ul class="plain">
                    @for (reference of p.credentialReferences; track reference.nodeId + reference.field) {
                      <li class="row">
                        <span class="mono">{{ reference.field }}</span>
                        <span class="small muted">on {{ reference.type }} → “{{ reference.name }}”</span>
                      </li>
                    }
                  </ul>
                </section>
              }

              @if (p.accessGroups.length > 0) {
                <section>
                  <h4 class="section-title">Access groups referenced</h4>
                  <p class="small muted">
                    Group grants are not applied automatically. Set permissions after importing.
                  </p>
                  <div class="tags">
                    @for (group of p.accessGroups; track group) {
                      <span class="tag">{{ group }}</span>
                    }
                  </div>
                </section>
              }
            </div>
          }
        }

        @case ('done') {
          @if (result(); as r) {
            <div class="stack">
              <p>
                Imported as <strong>{{ r.workflowName }}</strong>, a new draft workflow.
              </p>
              @if (r.missingPlugins.length > 0) {
                <div class="notice notice--warn">
                  Install or update these plugins before running it:
                  {{ r.missingPlugins.join(', ') }}.
                </div>
              }
              <p class="small muted">
                Review its nodes, map any credential references, then publish it when you are ready.
              </p>
            </div>
          }
        }
      }

      <div modalFooter style="display: flex; gap: var(--space-3); justify-content: flex-end">
        @switch (step()) {
          @case ('select') {
            <button class="btn" type="button" [disabled]="busy()" (click)="close()">Cancel</button>
            <button
              class="btn btn--primary"
              type="button"
              [disabled]="busy() || !file()"
              (click)="doValidate()"
            >
              {{ busy() ? 'Checking…' : 'Check file' }}
            </button>
          }
          @case ('preview') {
            <button class="btn" type="button" [disabled]="busy()" (click)="back()">Back</button>
            <button class="btn btn--primary" type="button" [disabled]="busy()" (click)="doImport()">
              {{ busy() ? 'Importing…' : 'Import as new workflow' }}
            </button>
          }
          @case ('done') {
            <button class="btn" type="button" (click)="close()">Close</button>
            <button class="btn btn--primary" type="button" (click)="openImported()">
              Open in designer
            </button>
          }
        }
      </div>
    </wf-modal>
  `,
  styles: [
    `
      .stack {
        display: flex;
        flex-direction: column;
        gap: var(--space-4);
      }
      .field {
        display: flex;
        flex-direction: column;
        gap: var(--space-1);
      }
      .summary__name {
        font-weight: 600;
        font-size: var(--text-lg);
      }
      .section-title {
        margin: 0 0 var(--space-2);
        font-size: var(--text-sm);
        text-transform: uppercase;
        letter-spacing: 0.04em;
        color: var(--text-muted);
      }
      .plain {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
      }
      .row {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        flex-wrap: wrap;
      }
      .mono {
        font-family: var(--font-mono, monospace);
        font-size: var(--text-sm);
      }
      .badge {
        font-size: var(--text-xs);
        padding: 2px 8px;
        border-radius: var(--radius-pill, 999px);
      }
      .badge--ok {
        background: var(--success-soft, #e6f4ea);
        color: var(--success, #1e7e34);
      }
      .badge--warn {
        background: var(--warning-soft, #fdf3e2);
        color: var(--warning, #b26a00);
      }
      .badge--bad {
        background: var(--danger-soft, #fdeaea);
        color: var(--danger, #c62828);
      }
      .notice {
        padding: var(--space-3);
        border-radius: var(--radius);
        font-size: var(--text-sm);
      }
      .notice--warn {
        background: var(--warning-soft, #fdf3e2);
        color: var(--warning, #b26a00);
      }
      .tags {
        display: flex;
        flex-wrap: wrap;
        gap: var(--space-2);
      }
    `,
  ],
})
export class WorkflowImportDialog {
  /** Emitted once with the new workflow id when an import succeeds, so the caller can refresh its list. */
  readonly imported = output<string>();
  readonly closed = output<void>();

  private readonly api = inject(WorkflowApiService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);

  protected readonly step = signal<'select' | 'preview' | 'done'>('select');
  protected readonly file = signal<File | null>(null);
  protected readonly password = signal('');
  protected readonly preview = signal<ImportValidationResult | null>(null);
  protected readonly result = signal<ImportResult | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected subheading(): string | null {
    switch (this.step()) {
      case 'select':
        return 'The file is checked before anything is created. Nothing is overwritten.';
      case 'preview':
        return 'Review what this file will bring in, then import it as a new workflow.';
      default:
        return null;
    }
  }

  protected onFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.file.set(input.files?.[0] ?? null);
    this.error.set(null);
  }

  protected doValidate(): void {
    const file = this.file();
    if (!file || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.importValidate(file, this.password() || null).subscribe({
      next: (preview) => {
        this.busy.set(false);
        if (!preview.valid) {
          this.error.set(preview.errors[0] ?? 'The file could not be read.');
          return;
        }
        this.preview.set(preview);
        this.step.set('preview');
      },
      error: (response) => {
        this.busy.set(false);
        this.error.set(response?.error?.message ?? 'The file could not be read.');
      },
    });
  }

  protected doImport(): void {
    const file = this.file();
    if (!file || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.api.import(file, this.password() || null).subscribe({
      next: (result) => {
        this.busy.set(false);
        this.result.set(result);
        this.step.set('done');
        this.notifications.success(`Imported "${result.workflowName}"`);
        this.imported.emit(result.workflowId);
      },
      error: () => this.busy.set(false),
    });
  }

  protected back(): void {
    if (this.busy()) {
      return;
    }
    this.step.set('select');
  }

  protected openImported(): void {
    const result = this.result();
    this.closed.emit();
    if (result) {
      this.router.navigate(['/workflows', result.workflowId]);
    }
  }

  protected close(): void {
    if (this.busy()) {
      return;
    }
    this.closed.emit();
  }
}
