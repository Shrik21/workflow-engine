import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Page, emptyPage } from '../../core/models/api.models';
import { WorkflowResponse, WorkflowStatus } from '../../core/models/workflow.models';
import { WorkflowApiService } from '../../core/api/workflow-api.service';
import { AuthStateService } from '../../core/auth/auth-state.service';
import { NotificationService } from '../../core/notification.service';
import { AgoPipe } from '../../shared/pipes/format.pipes';
import { EmptyState } from '../../shared/ui/empty-state';
import { Icon } from '../../shared/ui/icon';
import { Modal } from '../../shared/ui/modal';
import { StatusPill } from '../../shared/ui/status-pill';
import { WorkflowExportDialog } from './workflow-export-dialog';
import { WorkflowImportDialog } from './workflow-import-dialog';

/**
 * The workflow inventory.
 *
 * Shows the published version alongside the status, because "PUBLISHED" alone does not tell an
 * operator whether the definition they are looking at is the one that runs. A workflow edited after
 * publishing is DRAFT but still executable at its previous version, and that distinction is the one
 * most likely to cause confusion.
 */
@Component({
  selector: 'wf-workflow-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    StatusPill,
    EmptyState,
    AgoPipe,
    Icon,
    Modal,
    WorkflowExportDialog,
    WorkflowImportDialog,
  ],
  template: `
    <div class="page">
      <div class="page-header">
        <div class="page-header__text">
          <h1>Workflows</h1>
          <p>
            A workflow is a graph of nodes. Publishing snapshots an immutable version that executions
            pin, so editing a workflow never changes a run already in flight.
          </p>
        </div>
        <div class="toolbar">
          @if (session.has('WORKFLOW_CREATE')) {
            <button class="btn" type="button" (click)="openImport()">
              <wf-icon name="import" />
              <span>Import</span>
            </button>
          }
          <a class="btn btn--primary" routerLink="/workflows/new">New workflow</a>
        </div>
      </div>

      <div class="card">
        <div class="card__header">
          <input
            type="search"
            style="max-width: 260px"
            placeholder="Search by name"
            aria-label="Search workflows by name"
            [value]="nameFilter()"
            (input)="onSearch($any($event.target).value)"
          />
          <div class="btn-group">
            @for (option of statusOptions; track option.value) {
              <button
                class="btn btn--sm"
                type="button"
                [class.btn--primary]="statusFilter() === option.value"
                (click)="setStatus(option.value)"
              >
                {{ option.label }}
              </button>
            }
          </div>
          <span class="spacer"></span>
          <span class="small muted">{{ page().totalElements }} total</span>
          <button class="btn btn--sm" type="button" [disabled]="loading()" (click)="load()">
            <wf-icon name="refresh" /><span>Refresh</span>
          </button>
        </div>

        @if (page().content.length === 0 && !loading()) {
          <wf-empty-state
            heading="No workflows here"
            message="Create one, or clear the filters if you expected to see something."
          >
            <a class="btn btn--primary" routerLink="/workflows/new">New workflow</a>
          </wf-empty-state>
        } @else {
          <table class="table table--clickable">
            <thead>
              <tr>
                <th>Name</th>
                <th>Status</th>
                <th>Live version</th>
                <th>Nodes</th>
                <th>Triggers</th>
                <th>Updated</th>
                <th class="cell-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              @for (workflow of page().content; track workflow.id) {
                <tr>
                  <td>
                    <a [routerLink]="['/workflows', workflow.id]">{{ workflow.name }}</a>
                    @if (workflow.description) {
                      <div class="small muted truncate" style="max-width: 46ch">
                        {{ workflow.description }}
                      </div>
                    }
                  </td>
                  <td><wf-status-pill [status]="workflow.status" /></td>
                  <td>
                    @if (workflow.publishedVersion !== null) {
                      <span class="tag">v{{ workflow.publishedVersion }}</span>
                    } @else {
                      <span class="faint small">never published</span>
                    }
                  </td>
                  <td>{{ workflow.nodes.length }}</td>
                  <td>
                    @if (workflow.triggers.length === 0) {
                      <span class="faint small">manual</span>
                    } @else {
                      @for (trigger of workflow.triggers; track trigger.id) {
                        <span class="tag" [title]="triggerTitle(trigger)">{{ trigger.type }}</span>
                      }
                    }
                  </td>
                  <td class="small muted" [title]="workflow.updatedAt ?? ''">
                    {{ workflow.updatedAt | ago }}
                  </td>
                  <td class="cell-actions">
                    @if (session.has('WORKFLOW_EXECUTE')) {
                      @if (workflow.publishedVersion !== null) {
                        <a
                          class="btn btn--accent btn--sm btn--icon"
                          [routerLink]="['/workflows', workflow.id]"
                          [queryParams]="{ openRun: 1 }"
                          title="Run this workflow"
                          [attr.aria-label]="'Run ' + workflow.name"
                        >
                          <wf-icon name="run" />
                        </a>
                      } @else {
                        <button
                          class="btn btn--sm btn--icon"
                          type="button"
                          disabled
                          title="Publish the workflow before it can be run"
                          aria-label="Run (publish first)"
                        >
                          <wf-icon name="run" />
                        </button>
                      }
                    }
                    <a
                      class="btn btn--sm btn--icon"
                      [routerLink]="['/workflows', workflow.id]"
                      title="Open in the designer"
                      [attr.aria-label]="'Open ' + workflow.name"
                    >
                      <wf-icon name="open" />
                    </a>
                    <a
                      class="btn btn--sm btn--icon"
                      [routerLink]="['/executions']"
                      [queryParams]="{ workflowId: workflow.id }"
                      title="View this workflow's runs"
                      [attr.aria-label]="'Runs of ' + workflow.name"
                    >
                      <wf-icon name="runs" />
                    </a>
                    <button
                      class="btn btn--sm btn--icon"
                      type="button"
                      (click)="openExport(workflow)"
                      title="Export this workflow to an encrypted file"
                      [attr.aria-label]="'Export ' + workflow.name"
                    >
                      <wf-icon name="export" />
                    </button>
                    <button
                      class="btn btn--danger btn--sm btn--icon"
                      type="button"
                      (click)="remove(workflow)"
                      title="Delete this workflow"
                      [attr.aria-label]="'Delete ' + workflow.name"
                    >
                      <wf-icon name="delete" />
                    </button>
                  </td>
                </tr>
              }
            </tbody>
          </table>

          @if (page().totalPages > 1) {
            <div class="card__footer">
              <button
                class="btn btn--sm"
                type="button"
                [disabled]="page().first"
                (click)="goTo(page().number - 1)"
              >
                Previous
              </button>
              <span class="small muted" style="align-self: center">
                Page {{ page().number + 1 }} of {{ page().totalPages }}
              </span>
              <button
                class="btn btn--sm"
                type="button"
                [disabled]="page().last"
                (click)="goTo(page().number + 1)"
              >
                Next
              </button>
            </div>
          }
        }
      </div>
    </div>

    @if (pendingDelete(); as target) {
      <wf-modal
        heading="Delete workflow?"
        [subheading]="'This cannot be undone.'"
        width="480px"
        [dismissable]="!deleting()"
        (closed)="cancelDelete()"
      >
        <p>
          Delete <strong>{{ target.name }}</strong>?
        </p>
        @if (target.publishedVersion !== null) {
          <p class="small muted">
            This also deletes version {{ target.publishedVersion }} and every other published version.
            Executions already running keep the version they pinned.
          </p>
        }
        <div modalFooter style="display: flex; gap: var(--space-3); justify-content: flex-end">
          <button class="btn" type="button" [disabled]="deleting()" (click)="cancelDelete()">Cancel</button>
          <button
            class="btn btn--danger"
            type="button"
            [disabled]="deleting()"
            (click)="confirmDelete()"
          >
            {{ deleting() ? 'Deleting…' : 'Delete' }}
          </button>
        </div>
      </wf-modal>
    }

    @if (exportTarget(); as target) {
      <wf-workflow-export-dialog
        [workflowId]="target.id"
        [name]="target.name"
        (closed)="closeExport()"
      />
    }

    @if (importOpen()) {
      <wf-workflow-import-dialog (imported)="onImported()" (closed)="closeImport()" />
    }
  `,
})
export class WorkflowList {
  protected readonly statusOptions: Array<{ label: string; value: WorkflowStatus | null }> = [
    { label: 'All', value: null },
    { label: 'Draft', value: 'DRAFT' },
    { label: 'Published', value: 'PUBLISHED' },
    { label: 'Archived', value: 'ARCHIVED' },
  ];

  protected readonly session = inject(AuthStateService);
  private readonly api = inject(WorkflowApiService);
  private readonly notifications = inject(NotificationService);

  protected readonly page = signal<Page<WorkflowResponse>>(emptyPage());
  protected readonly loading = signal(false);
  protected readonly statusFilter = signal<WorkflowStatus | null>(null);
  protected readonly nameFilter = signal('');

  /** The workflow awaiting delete confirmation, and whether the delete is in flight. */
  protected readonly pendingDelete = signal<WorkflowResponse | null>(null);
  protected readonly deleting = signal(false);

  /** The workflow whose export dialog is open, and whether the import wizard is open. */
  protected readonly exportTarget = signal<WorkflowResponse | null>(null);
  protected readonly importOpen = signal(false);

  private readonly pageIndex = signal(0);
  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    // Re-reads whenever a filter or the page changes, so there is one load path rather than a call at
    // every interaction site.
    effect(() => {
      this.statusFilter();
      this.nameFilter();
      this.pageIndex();
      this.load();
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.api
      .list({
        status: this.statusFilter(),
        name: this.nameFilter(),
        page: this.pageIndex(),
        size: 20,
      })
      .subscribe({
        next: (page) => {
          this.page.set(page);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  /** Debounced so typing a name does not fire a request per keystroke. */
  protected onSearch(value: string): void {
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
    }
    this.searchTimer = setTimeout(() => {
      this.pageIndex.set(0);
      this.nameFilter.set(value);
    }, 250);
  }

  protected setStatus(status: WorkflowStatus | null): void {
    this.pageIndex.set(0);
    this.statusFilter.set(status);
  }

  protected goTo(index: number): void {
    this.pageIndex.set(Math.max(0, index));
  }

  /** Opens the confirmation modal rather than a native confirm(), which cannot be styled or themed. */
  protected remove(workflow: WorkflowResponse): void {
    this.pendingDelete.set(workflow);
  }

  protected cancelDelete(): void {
    if (this.deleting()) {
      return;
    }
    this.pendingDelete.set(null);
  }

  protected confirmDelete(): void {
    const target = this.pendingDelete();
    if (!target || this.deleting()) {
      return;
    }
    this.deleting.set(true);
    this.api.delete(target.id).subscribe({
      next: () => {
        this.deleting.set(false);
        this.pendingDelete.set(null);
        this.notifications.success(`Deleted "${target.name}"`);
        this.load();
      },
      error: () => this.deleting.set(false),
    });
  }

  protected openExport(workflow: WorkflowResponse): void {
    this.exportTarget.set(workflow);
  }

  protected closeExport(): void {
    this.exportTarget.set(null);
  }

  protected openImport(): void {
    this.importOpen.set(true);
  }

  protected closeImport(): void {
    this.importOpen.set(false);
  }

  /** After a successful import, refresh so the new draft appears in the list. */
  protected onImported(): void {
    this.load();
  }

  protected triggerTitle(trigger: { type: string; cron?: string | null; eventName?: string | null }): string {
    if (trigger.cron) {
      return `Schedule: ${trigger.cron}`;
    }
    if (trigger.eventName) {
      return `Event: ${trigger.eventName}`;
    }
    return trigger.type;
  }
}
