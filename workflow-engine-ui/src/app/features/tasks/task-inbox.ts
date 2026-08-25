import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormApiService } from '../../core/api/form-api.service';
import { TaskApiService } from '../../core/api/task-api.service';
import { AuthStateService } from '../../core/auth/auth-state.service';
import {
  AssignableUser,
  TaskBucket,
  TaskCounts,
  TaskDetail,
  TaskStatus,
  TaskSummary,
  priorityLabel,
} from '../../core/models/task.models';
import { NotificationService } from '../../core/notification.service';
import { DynamicForm } from '../forms/dynamic-form';
import { AgoPipe, ShortIdPipe } from '../../shared/pipes/format.pipes';
import { EmptyState } from '../../shared/ui/empty-state';
import { StatusPill } from '../../shared/ui/status-pill';
import { ExternalLinkPanel } from './external-link-panel';

/** Which statuses a filter choice asks for. Empty means "whatever the server defaults to", the actionable ones. */
const STATUS_FILTERS: { key: string; label: string; statuses: TaskStatus[] }[] = [
  { key: 'open', label: 'To do', statuses: [] },
  { key: 'held', label: 'Paused', statuses: ['PAUSED'] },
  { key: 'done', label: 'Finished', statuses: ['COMPLETED', 'CANCELLED', 'EXPIRED', 'TERMINATED'] },
  {
    key: 'everything',
    label: 'Everything',
    statuses: ['OPEN', 'ASSIGNED', 'PAUSED', 'COMPLETED', 'CANCELLED', 'EXPIRED', 'TERMINATED'],
  },
];

/**
 * The task inbox: the work a workflow has raised for a person.
 *
 * <p>Replaces the older screen that listed every `WAITING` execution. That was an operator's view of the same
 * facts and could not express the two questions somebody with work to do actually asks — what is mine, and what
 * could I pick up — because an execution has no assignee. It also showed every parked run to anybody who could
 * read executions, which a form's contents are not.
 *
 * <p>The form is rendered by {@link DynamicForm}, the same component the designer previews with. That is the
 * reason Preview is worth anything: the author checks their work against what the user will actually see.
 */
import { Icon } from '../../shared/ui/icon';
@Component({
  selector: 'wf-task-inbox',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, 
    RouterLink,
    DynamicForm,
    StatusPill,
    EmptyState,
    AgoPipe,
    ShortIdPipe,
    ExternalLinkPanel,
  ],
  template: `
    <div class="page">
      <div class="page-header">
        <div class="page-header__text">
          <h1>Tasks</h1>
          <p>
            Work a workflow has raised for a person. A waiting task holds no thread, so it can stay open for as
            long as it needs to and survives a restart.
          </p>
        </div>
        <div class="toolbar">
          <button class="btn btn--sm" type="button" [disabled]="loading()" (click)="reload()">
            <wf-icon name="refresh" /><span>Refresh</span>
          </button>
        </div>
      </div>

      <div class="tabs">
        @for (tab of buckets(); track tab.key) {
          <button
            class="tab"
            type="button"
            [class.tab--active]="bucket() === tab.key"
            (click)="selectBucket(tab.key)"
          >
            {{ tab.label }}
            @if (tab.count !== undefined) {
              <span class="tab__count">{{ tab.count }}</span>
            }
          </button>
        }

        <span class="spacer"></span>

        @if (counts().overdue) {
          <span class="tag tag--warn" title="Assigned to you and past its due date">
            {{ counts().overdue }} overdue
          </span>
        }

        <div class="btn-group">
          @for (filter of statusFilters; track filter.key) {
            <button
              class="btn btn--sm"
              type="button"
              [class.btn--primary]="statusFilter() === filter.key"
              (click)="selectStatusFilter(filter.key)"
            >
              {{ filter.label }}
            </button>
          }
        </div>
      </div>

      @if (tasks().length === 0 && !loading()) {
        <div class="card">
          <wf-empty-state [heading]="emptyHeading()" [message]="emptyMessage()">
            <a class="btn" routerLink="/workflows">Go to workflows</a>
          </wf-empty-state>
        </div>
      } @else {
        <div class="inbox">
          <div class="card inbox__list">
            <div class="card__header">
              <h3>{{ activeBucketLabel() }}</h3>
              <span class="spacer"></span>
              <span class="tag">{{ tasks().length }}</span>
            </div>
            <ul class="tasks">
              @for (task of tasks(); track task.taskId) {
                <li>
                  <button
                    class="task"
                    type="button"
                    [class.task--active]="selectedId() === task.taskId"
                    [class.task--attention]="task.overdue"
                    (click)="select(task)"
                  >
                    <span class="task__row">
                      <span class="task__title">{{ task.taskName || task.nodeId }}</span>
                      @if (task.priority !== 'NORMAL') {
                        <span class="chip" [attr.data-priority]="task.priority">
                          {{ priorityLabelOf(task) }}
                        </span>
                      }
                    </span>
                    <span class="task__meta">
                      {{ task.workflowName || task.workflowId }}
                      <span class="faint">· {{ task.createdAt | ago }}</span>
                    </span>
                    <span class="task__row task__row--small">
                      <wf-status-pill [status]="task.status" />
                      @if (task.assigneeUsername) {
                        <span class="small muted">{{ task.assigneeUsername }}</span>
                      } @else {
                        <span class="small muted">unclaimed</span>
                      }
                      @if (task.overdue) {
                        <span class="tag tag--warn">overdue</span>
                      }
                      @if (task.hasDraft) {
                        <span class="tag" title="Partial input was saved">draft</span>
                      }
                    </span>
                  </button>
                </li>
              }
            </ul>
          </div>

          <div class="card inbox__detail">
            @if (detail(); as view) {
              <div class="card__header">
                <h3>{{ view.task.taskName || view.task.nodeId }}</h3>
                <wf-status-pill [status]="view.task.status" />
                @if (view.task.external) {
                  <span class="tag" title="Completed by an external customer via a secure link">External</span>
                }
                <span class="spacer"></span>
                <a class="btn btn--sm" [routerLink]="['/executions', view.task.executionId]">
                  Open execution
                </a>
              </div>

              <div class="card__body">
                <p class="small muted">
                  {{ view.task.workflowName || view.task.workflowId }} v{{ view.task.workflowVersion }} ·
                  node <span class="mono">{{ view.task.nodeId }}</span> ·
                  <span class="mono">{{ view.task.executionId | shortId }}</span>
                </p>

                @if (view.task.description) {
                  <p>{{ view.task.description }}</p>
                }

                <dl class="facts">
                  <div>
                    <dt>Holder</dt>
                    <dd>
                      {{ view.task.assigneeUsername || 'nobody yet' }}
                      @if (view.task.assignedToMe) {
                        <span class="tag">you</span>
                      }
                    </dd>
                  </div>
                  <div>
                    <dt>Priority</dt>
                    <dd>{{ priorityLabelOf(view.task) }}</dd>
                  </div>
                  @if (view.task.dueAt) {
                    <div>
                      <dt>Due</dt>
                      <dd [class.field__error]="view.task.overdue">{{ view.task.dueAt | ago }}</dd>
                    </div>
                  }
                  @if (view.task.expiresAt) {
                    <div>
                      <dt>Expires</dt>
                      <dd title="After this the task cannot be submitted and the run is cancelled">
                        {{ view.task.expiresAt | ago }}
                      </dd>
                    </div>
                  }
                  @if (view.task.completedBy) {
                    <div>
                      <dt>Submitted by</dt>
                      <dd>{{ view.task.completedBy }} · {{ view.task.completedAt | ago }}</dd>
                    </div>
                  }
                </dl>

                <div class="actions">
                  @if (view.capabilities.claim) {
                    <button class="btn btn--primary btn--sm" type="button" [disabled]="busy()"
                      (click)="claim(view)">
                      Claim
                    </button>
                  }
                  @if (view.capabilities.release) {
                    <button class="btn btn--sm" type="button" [disabled]="busy()" (click)="release(view)">
                      Release
                    </button>
                  }
                  @if (view.capabilities.reassign) {
                    <button class="btn btn--sm" type="button" [disabled]="busy()"
                      (click)="openReassign()">
                      Reassign
                    </button>
                  }
                  @if (view.capabilities.cancel) {
                    <button class="btn btn--danger btn--sm" type="button" [disabled]="busy()"
                      (click)="cancel(view)">
                      Withdraw
                    </button>
                  }
                </div>

                @if (reassigning()) {
                  <div class="reassign">
                    <label class="small muted" for="reassign-to">Hand this task to</label>
                    <div class="row">
                      <select id="reassign-to" [value]="reassignTo()"
                        (change)="reassignTo.set($any($event.target).value)">
                        <option value="">Choose a person…</option>
                        @for (person of assignable(); track person.userId) {
                          <option [value]="person.username">
                            {{ person.displayName }} ({{ person.username }})
                          </option>
                        }
                      </select>
                      <input
                        type="text"
                        placeholder="Why (optional)"
                        [value]="reassignComment()"
                        (input)="reassignComment.set($any($event.target).value)"
                      />
                      <button class="btn btn--primary btn--sm" type="button"
                        [disabled]="busy() || !reassignTo()" (click)="confirmReassign(view)">
                        Reassign
                      </button>
                      <button class="btn btn--quiet btn--sm" type="button" (click)="reassigning.set(false)">
                        Cancel
                      </button>
                    </div>
                  </div>
                }

                @if (problems().length > 0) {
                  <div class="designer__issues">
                    <strong class="small">This submission was refused</strong>
                    <ul>
                      @for (problem of problems(); track problem) {
                        <li>{{ problem }}</li>
                      }
                    </ul>
                  </div>
                }

                @if (view.task.external && state.has('EXTERNAL_FORM_CREATE_LINK')) {
                  @if (view.task.status !== 'COMPLETED') {
                    <p class="small muted">
                      Assigned to <strong>External Customer</strong> · waiting for external response.
                    </p>
                  }
                  <wf-external-link-panel [taskId]="view.task.taskId" />
                }

                @if (view.formIssue) {
                  <p class="small muted form-issue">{{ view.formIssue }}</p>
                }

                @if (view.task.status === 'PAUSED') {
                  <div class="notice notice--warn instance-notice">
                    This workflow instance is currently paused. You can save your progress, but you cannot
                    submit the form until the workflow instance is resumed.
                  </div>
                } @else if (view.task.status === 'TERMINATED') {
                  <div class="notice notice--error instance-notice">
                    This workflow instance has been permanently terminated. You can save your form as a draft,
                    but you cannot submit it.
                  </div>
                }

                @if (view.form) {
                  <wf-dynamic-form
                    [form]="view.form"
                    [initialData]="view.initialData"
                    [catalogue]="forms.catalogue()"
                    [readOnly]="!view.capabilities.complete && !view.capabilities.saveDraft"
                    [allowSubmit]="view.capabilities.complete"
                    [allowSave]="view.capabilities.saveDraft"
                    [showActions]="view.capabilities.complete || view.capabilities.saveDraft"
                    [busy]="busy()"
                    (submitted)="submit(view, $event)"
                    (saved)="saveDraft(view, $event)"
                  />
                } @else if (view.capabilities.complete) {
                  <button class="btn btn--primary" type="button" [disabled]="busy()"
                    (click)="submit(view, {})">
                    Complete this task
                  </button>
                }

                @if (view.task.status === 'COMPLETED' && submittedRows(view).length > 0) {
                  <div class="submitted">
                    <h4>What was submitted</h4>
                    <dl class="facts">
                      @for (row of submittedRows(view); track row.key) {
                        <div>
                          <dt>{{ row.label }}</dt>
                          <dd>{{ row.value }}</dd>
                        </div>
                      }
                    </dl>
                  </div>
                }

                @if (view.history.length > 0) {
                  <div class="history">
                    <h4>History</h4>
                    <ol>
                      @for (entry of view.history; track $index) {
                        <li>
                          <strong>{{ entry.action }}</strong>
                          <span class="small muted">
                            {{ entry.actor }} · {{ entry.at | ago }}
                          </span>
                          @if (entry.comment) {
                            <span class="small">— {{ entry.comment }}</span>
                          }
                        </li>
                      }
                    </ol>
                  </div>
                }
              </div>
            } @else {
              <div class="card__body">
                <p class="small muted">Select a task to work on it.</p>
              </div>
            }
          </div>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .tabs {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        margin-bottom: var(--space-3);
        flex-wrap: wrap;
      }

      .tab {
        display: inline-flex;
        align-items: center;
        gap: var(--space-1);
        border: none;
        background: transparent;
        border-bottom: 2px solid transparent;
        padding: var(--space-2) var(--space-1);
        font-family: var(--font-brand);
        font-size: var(--text-md);
        color: var(--text-muted);
        cursor: pointer;
      }

      .tab--active {
        color: var(--hl-blue);
        border-bottom-color: var(--hl-green);
        font-weight: 600;
      }

      .tab__count {
        background: var(--hl-grey-200);
        border-radius: 9px;
        padding: 0 6px;
        font-size: var(--text-xs);
      }

      .inbox {
        display: grid;
        grid-template-columns: 340px 1fr;
        gap: var(--space-4);
        align-items: start;
      }

      .tasks {
        list-style: none;
        margin: 0;
        padding: 0;
        max-height: 660px;
        overflow-y: auto;
      }

      .task {
        display: flex;
        flex-direction: column;
        gap: 3px;
        width: 100%;
        text-align: left;
        padding: var(--space-3);
        border: none;
        border-bottom: 1px solid var(--hl-grey-200);
        border-left: 3px solid transparent;
        background: transparent;
        cursor: pointer;
        font-family: var(--font-body);
      }

      .task:hover {
        background: var(--hl-grey-50);
      }

      .task--active {
        background: #f2f8fd;
        border-left-color: var(--hl-blue);
      }

      .task--attention {
        border-left-color: var(--hl-orange, #ee7836);
      }

      .task__row {
        display: flex;
        align-items: center;
        gap: var(--space-2);
      }

      .task__row--small {
        gap: var(--space-1);
        flex-wrap: wrap;
      }

      .task__title {
        flex: 1;
        font-size: var(--text-base);
        font-weight: bold;
        color: var(--hl-grey-900);
      }

      .task__meta {
        font-size: var(--text-sm);
        color: var(--text-muted);
      }

      .chip {
        font-size: 10px;
        font-weight: bold;
        border-radius: var(--radius-sm);
        padding: 1px 6px;
        background: var(--hl-grey-200);
        color: var(--hl-grey-800);
      }

      .chip[data-priority='URGENT'] {
        background: #fdecec;
        color: var(--hl-red-alt, #bf0a08);
      }

      .chip[data-priority='HIGH'] {
        background: #fdf1e7;
        color: var(--hl-orange-alt, #cf5511);
      }

      .facts {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
        gap: var(--space-2) var(--space-4);
        margin: var(--space-3) 0;
      }

      .facts dt {
        font-size: var(--text-xs);
        text-transform: uppercase;
        letter-spacing: 0.4px;
        color: var(--text-muted);
      }

      .facts dd {
        margin: 0;
        font-size: var(--text-base);
      }

      .actions {
        display: flex;
        gap: var(--space-2);
        flex-wrap: wrap;
        padding-bottom: var(--space-3);
        border-bottom: 1px solid var(--border);
        margin-bottom: var(--space-3);
      }

      .reassign {
        background: var(--hl-grey-50);
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        padding: var(--space-3);
        margin-bottom: var(--space-3);
      }

      .reassign .row {
        display: flex;
        gap: var(--space-2);
        flex-wrap: wrap;
        align-items: center;
      }

      .form-issue {
        background: var(--hl-grey-50);
        border-left: 3px solid var(--hl-grey-400);
        padding: var(--space-2) var(--space-3);
        margin-bottom: var(--space-3);
      }

      .instance-notice {
        padding: var(--space-3);
        border-radius: var(--radius-sm);
        margin-bottom: var(--space-3);
        font-size: var(--text-sm);
      }

      .instance-notice.notice--warn {
        background: var(--warning-soft, #fdf3e2);
        color: var(--warning, #b26a00);
      }

      .instance-notice.notice--error {
        background: var(--danger-soft, #fdeaea);
        color: var(--danger, #c62828);
      }

      .submitted,
      .history {
        margin-top: var(--space-4);
        padding-top: var(--space-3);
        border-top: 1px solid var(--border);
      }

      .history ol {
        margin: 0;
        padding-left: var(--space-4);
      }

      .history li {
        margin-bottom: var(--space-1);
      }

      @media (max-width: 1000px) {
        .inbox {
          grid-template-columns: 1fr;
        }
      }
    `,
  ],
})
export class TaskInbox {
  protected readonly forms = inject(FormApiService);

  private readonly api = inject(TaskApiService);
  private readonly notifications = inject(NotificationService);
  protected readonly state = inject(AuthStateService);

  protected readonly statusFilters = STATUS_FILTERS;

  protected readonly tasks = signal<TaskSummary[]>([]);
  protected readonly detail = signal<TaskDetail | null>(null);
  protected readonly counts = signal<TaskCounts>({});
  protected readonly bucket = signal<TaskBucket>('mine');
  protected readonly statusFilter = signal<string>('open');
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);

  /** Per-field messages from a refused submission, shown above the form. */
  protected readonly problems = signal<string[]>([]);

  protected readonly reassigning = signal(false);
  protected readonly reassignTo = signal('');
  protected readonly reassignComment = signal('');
  protected readonly assignable = signal<AssignableUser[]>([]);

  protected readonly selectedId = computed(() => this.detail()?.task.taskId ?? null);

  /** The visible tabs. "All" is hidden unless the user may actually read every task. */
  protected readonly buckets = computed(() => {
    const counts = this.counts();
    const tabs: { key: TaskBucket; label: string; count?: number }[] = [
      { key: 'mine', label: 'My tasks', count: counts.mine },
      { key: 'available', label: 'Available', count: counts.available },
    ];
    if (this.state.hasAny('TASK_VIEW_ALL', 'TASK_ADMIN')) {
      tabs.push({ key: 'all', label: 'All tasks', count: counts.all });
    }
    return tabs;
  });

  protected readonly activeBucketLabel = computed(
    () => this.buckets().find((tab) => tab.key === this.bucket())?.label ?? 'Tasks',
  );

  protected readonly emptyHeading = computed(() => {
    switch (this.bucket()) {
      case 'available':
        return 'Nothing to pick up';
      case 'all':
        return 'No tasks';
      default:
        return 'Nothing assigned to you';
    }
  });

  protected readonly emptyMessage = computed(() => {
    switch (this.bucket()) {
      case 'available':
        return 'Open tasks offered to a group you belong to appear here, ready to claim.';
      case 'all':
        return 'When a workflow reaches a form node, the task it raises appears here.';
      default:
        return 'Tasks assigned to you, or that you have claimed, appear here.';
    }
  });

  constructor() {
    // The renderer needs the field catalogue to know which types collect a value.
    this.forms.ensureCatalogue();
    this.reload();
  }

  protected priorityLabelOf(task: TaskSummary): string {
    return priorityLabel(task.priority);
  }

  protected selectBucket(bucket: TaskBucket): void {
    if (this.bucket() === bucket) {
      return;
    }
    this.bucket.set(bucket);
    this.detail.set(null);
    this.load();
  }

  protected selectStatusFilter(key: string): void {
    if (this.statusFilter() === key) {
      return;
    }
    this.statusFilter.set(key);
    this.detail.set(null);
    this.load();
  }

  protected reload(): void {
    this.load();
    this.api.counts().subscribe({ next: (counts) => this.counts.set(counts ?? {}) });
  }

  protected select(task: TaskSummary): void {
    this.problems.set([]);
    this.reassigning.set(false);
    // Always re-read: a row carries no form values, and the detail endpoint is the one that checks whether
    // this person may see them.
    this.api.get(task.taskId).subscribe({
      next: (detail) => this.detail.set(detail),
      error: (error: HttpErrorResponse) => this.report(error, 'That task could not be opened'),
    });
  }

  protected claim(view: TaskDetail): void {
    this.act(this.api.claim(view.task.taskId), 'Claimed', 'It is yours now.');
  }

  protected release(view: TaskDetail): void {
    this.act(
      this.api.release(view.task.taskId),
      'Released',
      'Anybody in its candidate groups can pick it up.',
    );
  }

  protected cancel(view: TaskDetail): void {
    const reason = window.prompt('Why is this task being withdrawn? The workflow will be cancelled.');
    if (reason === null) {
      return;
    }
    this.act(
      this.api.cancel(view.task.taskId, reason),
      'Withdrawn',
      'The execution has been cancelled.',
    );
  }

  protected openReassign(): void {
    this.reassigning.set(true);
    this.reassignTo.set('');
    this.reassignComment.set('');
    if (this.assignable().length === 0) {
      this.api.assignableUsers().subscribe({ next: (people) => this.assignable.set(people ?? []) });
    }
  }

  protected confirmReassign(view: TaskDetail): void {
    const to = this.reassignTo();
    if (!to) {
      return;
    }
    this.reassigning.set(false);
    this.act(
      this.api.reassign(view.task.taskId, to, this.reassignComment() || undefined),
      'Reassigned',
      `${to} now holds this task.`,
    );
  }

  protected saveDraft(view: TaskDetail, formData: Record<string, unknown>): void {
    this.busy.set(true);
    this.api.saveDraft(view.task.taskId, formData).subscribe({
      next: (detail) => {
        this.busy.set(false);
        this.detail.set(detail);
        this.notifications.success('Draft saved', 'Nothing has been sent to the workflow yet.');
        this.refreshRow(detail.task);
      },
      error: (error: HttpErrorResponse) => {
        this.busy.set(false);
        this.report(error, 'The draft could not be saved');
      },
    });
  }

  protected submit(view: TaskDetail, formData: Record<string, unknown>): void {
    this.busy.set(true);
    this.problems.set([]);
    this.api.complete(view.task.taskId, formData).subscribe({
      next: (detail) => {
        this.busy.set(false);
        this.detail.set(detail);
        this.notifications.success('Submitted', 'The workflow has been resumed.');
        this.reload();
      },
      error: (error: HttpErrorResponse) => {
        this.busy.set(false);
        // 422 carries a per-field list, which is more use above the form than in a toast that disappears.
        const details: string[] = error.error?.details ?? [];
        if (error.status === 422 && details.length > 0) {
          this.problems.set(details);
          return;
        }
        this.report(error, 'The task could not be submitted');
      },
    });
  }

  /** Rows of submitted values, labelled from the form so the reader sees "Salary", not "salary". */
  protected submittedRows(view: TaskDetail): { key: string; label: string; value: string }[] {
    const labels = new Map<string, string>();
    for (const field of view.form?.fields ?? []) {
      if (field.name) {
        labels.set(field.name, field.label || field.name);
      }
    }
    return Object.entries(view.submittedData).map(([key, value]) => ({
      key,
      label: labels.get(key) ?? key,
      value: this.asText(value),
    }));
  }

  private asText(value: unknown): string {
    if (value === null || value === undefined || value === '') {
      return '—';
    }
    if (Array.isArray(value)) {
      return value.length === 0 ? '—' : value.join(', ');
    }
    if (typeof value === 'boolean') {
      return value ? 'Yes' : 'No';
    }
    return String(value);
  }

  private act(
    request: ReturnType<TaskApiService['claim']>,
    title: string,
    message: string,
  ): void {
    this.busy.set(true);
    request.subscribe({
      next: (detail) => {
        this.busy.set(false);
        this.detail.set(detail);
        this.notifications.success(title, message);
        this.reload();
      },
      error: (error: HttpErrorResponse) => {
        this.busy.set(false);
        this.report(error, `${title} failed`);
        // Whatever went wrong, the list is now out of date: somebody else probably acted first.
        this.reload();
      },
    });
  }

  private load(): void {
    this.loading.set(true);
    const filter = STATUS_FILTERS.find((entry) => entry.key === this.statusFilter());
    this.api
      .list({ bucket: this.bucket(), status: filter?.statuses ?? [], size: 100 })
      .subscribe({
        next: (page) => {
          this.loading.set(false);
          this.tasks.set(page.content);
          // Opening the first row is a convenience for the common case of one waiting approval, and harmless
          // when the list is long because it is only ever the first.
          if (!this.detail() && page.content.length > 0) {
            this.select(page.content[0]);
          }
        },
        error: (error: HttpErrorResponse) => {
          this.loading.set(false);
          this.report(error, 'Tasks could not be listed');
        },
      });
  }

  private refreshRow(task: TaskSummary): void {
    this.tasks.update((current) =>
      current.map((item) => (item.taskId === task.taskId ? task : item)),
    );
  }

  private report(error: HttpErrorResponse, fallback: string): void {
    const message: string = error.error?.message ?? error.message ?? fallback;
    const details: string[] = error.error?.details ?? [];
    this.notifications.error(fallback, message, ...details);
  }
}
