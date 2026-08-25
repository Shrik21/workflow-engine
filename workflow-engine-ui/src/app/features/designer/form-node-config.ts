import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormApiService } from '../../core/api/form-api.service';
import { AvailableForm, FormVersion, formLabel } from '../../core/models/form.models';
import { WorkflowNode } from '../../core/models/workflow.models';
import { DynamicForm } from '../forms/dynamic-form';
import { ConfirmDialog, ConfirmRequest } from '../../shared/ui/confirm-dialog';

/**
 * The form node's Configuration tab: which form this human step shows.
 *
 * <p>Replaces a free-text box that asked the author to type a form id from memory. That was defensible when a
 * "form" was just a name a client submitted against; once forms became documents with published versions, a
 * hand-typed id was a guess that failed at run time rather than at design time.
 *
 * <h2>What it stores</h2>
 *
 * <p>`formId` and `formVersion`, never the name. The name belongs to the form definition and would be a second
 * copy going stale the moment somebody renamed it — the dropdown resolves it for display on every load.
 *
 * <h2>Every state the list can be in</h2>
 *
 * <p>Loading, empty, failed and loaded are all rendered, because each one means something different to the
 * author and a bare empty dropdown means all four at once. The interesting fifth case is a node referencing a
 * form that is not in the list: rather than clearing the value or showing a raw id, the form is fetched by id
 * so the author is told whether it was archived or has genuinely gone.
 */
@Component({
  selector: 'wf-form-node-config',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, DynamicForm, ConfirmDialog],
  template: `
    <div class="field">
      <label class="field__label" for="form-select">Form</label>

      @if (loading()) {
        <select id="form-select" disabled>
          <option>Loading forms…</option>
        </select>
      } @else if (loadError()) {
        <div class="notice notice--warning">
          <p>Unable to load forms.</p>
          <p class="small">{{ loadError() }}</p>
          <button class="btn btn--sm" type="button" (click)="load()">Retry</button>
        </div>
      } @else if (forms().length === 0) {
        <div class="notice">
          <p>No forms available.</p>
          <p class="small">
            A form node needs a published form. Create one and publish it, then choose it here.
          </p>
          <a class="btn btn--sm" routerLink="/forms/new">Create a form</a>
        </div>
      } @else {
        <!--
          Selection is expressed with [selected] on each option, not [value] on the select. A property binding
          on the select is applied before its options exist, so the browser has nothing to match and silently
          leaves the value empty — which reads as "no form chosen" on a node that has one.
        -->
        <select id="form-select" (change)="choose($any($event.target).value)">
          <option value="" [selected]="!node().formId">Select a form…</option>
          @for (form of forms(); track form.id) {
            <option [value]="form.id" [selected]="form.id === node().formId">
              {{ label(form) }}
            </option>
          }
          @if (missing()) {
            <!--
              The node's form is not selectable any more. Kept as an option so the select shows what the node
              actually references instead of falling back to "Select a form…", which invites somebody to
              overwrite a deliberate choice.
            -->
            <option [value]="node().formId" [selected]="true">{{ missingLabel() }}</option>
          }
        </select>
      }

      <p class="field__hint">
        Stored as an id, so renaming the form does not break this node.
      </p>

      @if (missing()) {
        <p class="field__error">
          @if (missingName()) {
            {{ missingName() }} is no longer offered for new nodes — it has been archived, or its published
            version was withdrawn. Existing executions keep working; choose another form to change this node.
          } @else {
            This node references a form that no longer exists
            (<span class="mono">{{ node().formId }}</span
            >). Executions reaching it will have nothing to fill in.
          }
        </p>
      }
    </div>

    <div class="field">
      <label class="field__label" for="assign-type">Assignment</label>
      <select id="assign-type" (change)="chooseAssignment($any($event.target).value)">
        <option value="INTERNAL_USER" [selected]="assignmentType() === 'INTERNAL_USER'">
          Internal user
        </option>
        <option value="GROUP" [selected]="assignmentType() === 'GROUP'">Group</option>
        <option value="EXTERNAL" [selected]="assignmentType() === 'EXTERNAL'">
          External user (public link)
        </option>
      </select>

      @if (assignmentType() === 'EXTERNAL') {
        <p class="field__hint">
          This task is completed by an external customer with no OrchPilot account. When the workflow runs and
          raises the task, open it in the <strong>Task inbox</strong> and use <em>Generate external link</em>
          to create a secure form link to send them.
        </p>
      } @else {
        <p class="field__hint">
          Set the assignee or candidate groups in the node’s Configuration below — keys
          <span class="mono">assignee</span> or <span class="mono">candidateGroups</span>.
        </p>
      }
    </div>

    @if (selected(); as form) {
      <div class="selected">
        <div class="selected__row">
          <span class="field__label">Version</span>
          <span class="tag tag--mono">v{{ node().formVersion ?? form.version }}</span>
          @if (isBehind()) {
            <button
              class="btn btn--quiet btn--sm"
              type="button"
              [title]="'Move this node to v' + form.version"
              (click)="pin(form.version)"
            >
              v{{ form.version }} is available
            </button>
          }
        </div>

        @if (form.description) {
          <p class="small muted">{{ form.description }}</p>
        }

        <div class="selected__actions">
          <a class="btn btn--sm" [routerLink]="['/forms', form.id]">Open form designer</a>
          <button class="btn btn--sm" type="button" (click)="togglePreview()">
            {{ previewing() ? 'Hide preview' : 'Preview form' }}
          </button>
        </div>

        @if (previewing()) {
          @if (previewError()) {
            <p class="field__error">{{ previewError() }}</p>
          } @else if (!preview()) {
            <p class="small muted">Loading the form…</p>
          } @else {
            <div class="preview">
              <p class="small muted">
                Version {{ preview()!.version }} as a person will see it. Nothing here is editable.
              </p>
              <wf-dynamic-form
                [form]="preview()!"
                [catalogue]="api.catalogue()"
                [readOnly]="true"
                [showActions]="false"
              />
            </div>
          }
        }
      </div>
    }

    @if (pendingConfirm(); as c) {
      <wf-confirm-dialog
        [heading]="c.heading"
        [message]="c.message"
        [confirmLabel]="c.confirmLabel"
        [danger]="c.danger"
        (confirmed)="runConfirmed()"
        (cancelled)="pendingConfirm.set(null)"
      />
    }
  `,
  styles: [
    `
      .selected {
        margin: var(--space-3) 0;
        padding: var(--space-3);
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        background: var(--surface-sunken);
      }

      .selected__row {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        margin-bottom: var(--space-2);
      }

      .selected__actions {
        display: flex;
        gap: var(--space-2);
        flex-wrap: wrap;
        margin-top: var(--space-3);
      }

      .preview {
        margin-top: var(--space-3);
        padding-top: var(--space-3);
        border-top: 1px solid var(--border);
      }

      .notice {
        padding: var(--space-3);
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        background: var(--surface-sunken);
      }

      .notice p {
        margin: 0 0 var(--space-2);
      }
    `,
  ],
})
export class FormNodeConfig {
  readonly node = input.required<WorkflowNode>();

  /** Emits the fields to write onto the node. The parent owns the store. */
  readonly nodeChange = output<Partial<WorkflowNode>>();

  protected readonly api = inject(FormApiService);

  protected readonly forms = signal<AvailableForm[]>([]);
  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);

  /** Resolved name of a referenced form that is not in the list, when it can still be read. */
  protected readonly missingName = signal<string | null>(null);

  protected readonly pendingConfirm = signal<ConfirmRequest | null>(null);
  protected readonly previewing = signal(false);
  protected readonly preview = signal<FormVersion | null>(null);
  protected readonly previewError = signal<string | null>(null);

  /** The selected form, when it is one the picker offers. */
  protected readonly selected = computed(() => {
    const id = this.node().formId;
    return id ? (this.forms().find((form) => form.id === id) ?? null) : null;
  });

  /** A form is referenced, the list has loaded, and it is not in it. */
  protected readonly missing = computed(() => {
    const id = this.node().formId;
    return !!id && !this.loading() && !this.loadError() && !this.selected();
  });

  protected readonly missingLabel = computed(() =>
    this.missingName() ? `${this.missingName()} (unavailable)` : 'Unknown form (unavailable)',
  );

  /** The node pins an older version than the one now published. */
  protected readonly isBehind = computed(() => {
    const pinned = this.node().formVersion;
    const latest = this.selected()?.version;
    return pinned != null && latest != null && pinned < latest;
  });

  constructor() {
    this.api.ensureCatalogue();
    this.load();

    effect(() => {
      // Re-resolve whenever the referenced form changes, including when the panel switches to another node.
      const id = this.node().formId;
      const unresolved = this.missing();
      this.preview.set(null);
      this.previewing.set(false);
      if (id && unresolved) {
        this.resolveMissing(id);
      } else {
        this.missingName.set(null);
      }
    });
  }

  /** The node's current assignment type, normalised to the three the selector offers. */
  protected readonly assignmentType = computed<'INTERNAL_USER' | 'GROUP' | 'EXTERNAL'>(() => {
    const raw = String(
      (this.node().configuration as Record<string, unknown> | undefined)?.['assignmentType'] ?? '',
    ).toUpperCase();
    if (raw === 'EXTERNAL' || raw === 'EXTERNAL_USER') {
      return 'EXTERNAL';
    }
    return raw === 'GROUP' ? 'GROUP' : 'INTERNAL_USER';
  });

  /** Writes the chosen assignment type onto the node's configuration, leaving other keys intact. */
  protected chooseAssignment(value: string): void {
    const config: Record<string, unknown> = {
      ...((this.node().configuration as Record<string, unknown> | undefined) ?? {}),
    };
    config['assignmentType'] = value === 'EXTERNAL' ? 'EXTERNAL' : value;
    this.nodeChange.emit({ configuration: config } as Partial<WorkflowNode>);
  }

  protected label(form: AvailableForm): string {
    return formLabel(form);
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.available().subscribe({
      next: (forms) => {
        this.loading.set(false);
        this.forms.set(forms ?? []);
      },
      error: (error: HttpErrorResponse) => {
        this.loading.set(false);
        // The console keeps the real error; the panel shows something a person can act on.
        console.error('Failed to load the available forms', error);
        this.loadError.set(this.describe(error));
      },
    });
  }

  /**
   * Applies a new selection.
   *
   * <p>Confirms first when the node carries mappings, because those are keyed by the previous form's field
   * names and will mostly be wrong against a different form. The mappings are not cleared either way: silently
   * discarding an author's work is worse than leaving something they can see and fix.
   */
  protected choose(formId: string): void {
    const current = this.node();
    if (formId === (current.formId ?? '')) {
      return;
    }

    if (formId && current.formId && this.hasMappings(current)) {
      this.pendingConfirm.set({
        heading: 'Change form?',
        message:
          'Changing the form may invalidate this node’s existing field mappings.\n\n' +
          'They are keyed by the current form’s field names and will not be cleared, so you can ' +
          'review them afterwards.',
        confirmLabel: 'Change form',
        danger: false,
        onConfirm: () => this.applyChoice(formId),
      });
      return;
    }

    this.applyChoice(formId);
  }

  private applyChoice(formId: string): void {
    if (!formId) {
      this.nodeChange.emit({ formId: null, formVersion: null });
      return;
    }
    const chosen = this.forms().find((form) => form.id === formId);
    // Pin the version that is published now, so a later publish cannot change what this node shows without
    // somebody deciding to move it.
    this.nodeChange.emit({ formId, formVersion: chosen?.version ?? null });
  }

  /** Runs the pending confirmed action, then clears the dialog. */
  protected runConfirmed(): void {
    const request = this.pendingConfirm();
    this.pendingConfirm.set(null);
    request?.onConfirm();
  }

  protected pin(version: number | null): void {
    this.nodeChange.emit({ formVersion: version });
  }

  protected togglePreview(): void {
    if (this.previewing()) {
      this.previewing.set(false);
      return;
    }
    this.previewing.set(true);
    if (this.preview()) {
      return;
    }
    const form = this.selected();
    const version = this.node().formVersion ?? form?.version;
    if (!form || version == null) {
      this.previewError.set('This form has no published version to preview.');
      return;
    }
    this.previewError.set(null);
    this.api.version(form.id, version).subscribe({
      next: (loaded) => this.preview.set(loaded),
      error: (error: HttpErrorResponse) => {
        console.error('Failed to load the form version for preview', error);
        this.previewError.set(this.describe(error));
      },
    });
  }

  /**
   * Names a referenced form the picker does not offer.
   *
   * <p>One extra request, only in the unusual case, and it buys the difference between "archived" and "gone",
   * which are different problems with different fixes.
   */
  private resolveMissing(formId: string): void {
    this.missingName.set(null);
    this.api.get(formId).subscribe({
      next: (form) => this.missingName.set(form.name),
      // 404 is the expected answer for a deleted form, so this is not logged as an error.
      error: () => this.missingName.set(null),
    });
  }

  private hasMappings(node: WorkflowNode): boolean {
    return (
      Object.keys(node.inputMapping ?? {}).length > 0 ||
      Object.keys(node.outputMapping ?? {}).length > 0
    );
  }

  private describe(error: HttpErrorResponse): string {
    if (error.status === 401) {
      return 'Your session has expired. Sign in again.';
    }
    if (error.status === 403) {
      return 'You do not have permission to list forms.';
    }
    if (error.status === 0) {
      return 'The engine could not be reached.';
    }
    return error.error?.message ?? error.message ?? 'Unexpected error';
  }
}
