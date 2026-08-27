import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ExecutionApiService } from '../../core/api/execution-api.service';
import { WorkflowApiService } from '../../core/api/workflow-api.service';
import { Page, emptyPage } from '../../core/models/api.models';
import { ExecutionResponse, ExecutionStatus } from '../../core/models/execution.models';
import { WorkflowAuditEntry } from '../../core/models/workflow.models';
import { AgoPipe, ShortIdPipe } from '../../shared/pipes/format.pipes';
import { EmptyState } from '../../shared/ui/empty-state';
import { Icon } from '../../shared/ui/icon';
import { LoadingSkeleton } from '../../shared/ui/loading-skeleton';
import { PageHeader } from '../../shared/ui/page-header';
import { StatusPill } from '../../shared/ui/status-pill';

/**
 * The execution list.
 *
 * Auto-refreshes only while something is in flight. Polling a screen of finished executions burns
 * requests to redraw identical rows, and stopping when nothing is running also makes the refresh
 * indicator meaningful.
 */
@Component({
  selector: 'wf-execution-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, RouterLink, StatusPill, EmptyState, LoadingSkeleton, PageHeader, AgoPipe, ShortIdPipe],
  template: `
    <div class="page">
      <wf-page-header
        title="Executions"
        description="Every run, whatever started it. Synchronous, asynchronous, scheduled, event-driven and manual executions all go through the same engine, so they are all listed here."
      >
        @if (live()) {
          <span class="small muted">Refreshing every 4s while runs are in flight</span>
        }
        <button class="btn btn--sm" type="button" [disabled]="loading()" (click)="load()">
          <wf-icon name="refresh" /><span>Refresh</span>
        </button>
      </wf-page-header>

      @if (workflowId()) {
        <div class="card wf-history">
          <div class="card__header">
            <strong>Workflow history</strong>
            @if (workflowMeta(); as meta) {
              <span class="small muted">
                created by <strong>{{ meta.createdBy || 'unknown' }}</strong> {{ meta.createdAt | ago }} ·
                last updated by <strong>{{ meta.updatedBy || 'unknown' }}</strong> {{ meta.updatedAt | ago }}
              </span>
            }
            <span class="spacer"></span>
            <button class="btn btn--quiet btn--sm" type="button" (click)="showHistory.set(!showHistory())">
              {{ showHistory() ? 'Hide' : 'Show' }}
            </button>
          </div>

          @if (showHistory()) {
            @if (historyLoading()) {
              <wf-loading-skeleton variant="table" [rows]="[1, 2, 3]" label="Loading workflow history" />
            } @else if (historyDenied()) {
              <p class="pad small muted">You do not have permission to view this workflow's history.</p>
            } @else if (history().length === 0) {
              <p class="pad small muted">No change history is recorded for this workflow.</p>
            } @else {
              <table class="table">
                <thead>
                  <tr>
                    <th style="width: 120px">When</th>
                    <th style="width: 160px">User</th>
                    <th style="width: 160px">Action</th>
                    <th>Detail</th>
                  </tr>
                </thead>
                <tbody>
                  @for (entry of history(); track $index) {
                    <tr>
                      <td class="small muted" [title]="entry.at ?? ''">{{ entry.at | ago }}</td>
                      <td>{{ entry.actor || 'unknown' }}</td>
                      <td>
                        <span class="tag">{{ actionLabel(entry.action) }}</span>
                        @if (entry.outcome && entry.outcome !== 'OK') {
                          <span class="tag tag--danger">{{ entry.outcome }}</span>
                        }
                      </td>
                      <td class="small muted">{{ detailText(entry.details) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          }
        </div>
      }

      <div class="card">
        <div class="card__header list-toolbar">
          <div class="btn-group" role="group" aria-label="Filter by status">
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
          @if (workflowId()) {
            <span class="tag tag--mono">workflow {{ workflowId() }}</span>
            <a class="btn btn--quiet btn--sm" routerLink="/executions">Clear</a>
          }
          <div class="list-toolbar__meta">
            <span class="small muted">{{ page().totalElements }} total</span>
          </div>
        </div>

        @if (loading() && page().content.length === 0) {
          <wf-loading-skeleton variant="table" label="Loading executions" />
        } @else if (page().content.length === 0) {
          <wf-empty-state
            heading="No executions"
            message="Run a published workflow, or emit an event that one subscribes to."
          >
            <a class="btn" routerLink="/workflows">Go to workflows</a>
          </wf-empty-state>
        } @else {
          <div class="table-scroll">
          <table class="table table--clickable">
            <thead>
              <tr>
                <th>Execution</th>
                <th>Workflow</th>
                <th>Status</th>
                <th>Started by</th>
                <th>Node</th>
                <th>Steps</th>
                <th>Started</th>
                <th>Updated</th>
              </tr>
            </thead>
            <tbody>
              @for (execution of page().content; track execution.executionId) {
                <tr>
                  <td>
                    <a
                      class="mono"
                      [routerLink]="['/executions', execution.executionId]"
                      [title]="execution.executionId"
                      >{{ execution.executionId | shortId }}</a
                    >
                  </td>
                  <td>
                    <a [routerLink]="['/workflows', execution.workflowId]">
                      {{ execution.workflowName || execution.workflowId }}
                    </a>
                    <span class="tag" style="margin-left: 6px">v{{ execution.workflowVersion }}</span>
                  </td>
                  <td><wf-status-pill [status]="execution.status" /></td>
                  <td class="small muted">{{ execution.mode }}</td>
                  <td class="mono small">{{ execution.currentNodeId || '' }}</td>
                  <td>{{ execution.stepCount }}</td>
                  <td class="small muted" [title]="execution.startedAt ?? ''">
                    {{ execution.startedAt | ago }}
                  </td>
                  <td class="small muted" [title]="execution.updatedAt ?? ''">
                    {{ execution.updatedAt | ago }}
                  </td>
                </tr>
              }
            </tbody>
          </table>
          </div>

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
  `,
  styles: [
    `
      .wf-history {
        margin-bottom: var(--space-4);
      }

      .wf-history .card__header {
        display: flex;
        align-items: center;
        gap: var(--space-3);
      }
    `,
  ],
})
export class ExecutionList {
  /** Optional query parameter, bound by the router, for drilling in from a workflow. */
  readonly workflowId = input<string | undefined>(undefined);

  protected readonly statusOptions: Array<{ label: string; value: ExecutionStatus | null }> = [
    { label: 'All', value: null },
    { label: 'Running', value: 'RUNNING' },
    { label: 'Waiting', value: 'WAITING' },
    { label: 'Paused', value: 'PAUSED' },
    { label: 'Completed', value: 'COMPLETED' },
    { label: 'Failed', value: 'FAILED' },
    { label: 'Terminated', value: 'TERMINATED' },
  ];

  private readonly api = inject(ExecutionApiService);
  private readonly workflowApi = inject(WorkflowApiService);

  protected readonly page = signal<Page<ExecutionResponse>>(emptyPage());
  protected readonly loading = signal(false);
  protected readonly statusFilter = signal<ExecutionStatus | null>(null);
  protected readonly live = signal(false);

  /**
   * The change history of the workflow these runs belong to, shown only when the list was reached filtered
   * by a workflow — the "who created / who updated" log, newest first.
   */
  protected readonly history = signal<WorkflowAuditEntry[]>([]);
  protected readonly workflowMeta = signal<{
    createdBy: string | null;
    updatedBy: string | null;
    createdAt: string | null;
    updatedAt: string | null;
  } | null>(null);
  protected readonly historyLoading = signal(false);
  protected readonly historyDenied = signal(false);

  /** Collapsed by default: the runs table is the reason to be here, and the history is one click away. */
  protected readonly showHistory = signal(false);

  private readonly pageIndex = signal(0);
  private timer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    effect(() => {
      this.workflowId();
      this.statusFilter();
      this.pageIndex();
      this.load();
    });

    // Loads the workflow's change history when arriving filtered by one, and clears it on leaving that filter.
    effect(() => {
      const id = this.workflowId();
      if (id) {
        this.loadHistory(id);
      } else {
        this.history.set([]);
        this.workflowMeta.set(null);
      }
    });

    effect((onCleanup) => {
      // Depends on `live`, so the interval is created and torn down as runs start and finish.
      const active = this.live();
      if (!active) {
        return;
      }
      this.timer = setInterval(() => this.load(), 4000);
      onCleanup(() => {
        if (this.timer) {
          clearInterval(this.timer);
          this.timer = null;
        }
      });
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.api
      .list({
        workflowId: this.workflowId() ?? null,
        status: this.statusFilter(),
        page: this.pageIndex(),
        size: 25,
      })
      .subscribe({
        next: (page) => {
          this.page.set(page);
          this.loading.set(false);
          this.live.set(
            page.content.some(
              (execution) => execution.status === 'RUNNING' || execution.status === 'PENDING',
            ),
          );
        },
        error: () => {
          this.loading.set(false);
          // Stop polling after a failure so an unreachable engine is not hammered every four seconds.
          this.live.set(false);
        },
      });
  }

  protected setStatus(status: ExecutionStatus | null): void {
    this.pageIndex.set(0);
    this.statusFilter.set(status);
  }

  protected goTo(index: number): void {
    this.pageIndex.set(Math.max(0, index));
  }

  /**
   * Loads the workflow's change history and created/updated summary.
   *
   * A viewer with access to a run does not necessarily have permission to view the workflow definition, so a
   * 403 is caught and shown as a plain "not available" rather than a scary error — the executions list itself
   * is unaffected.
   */
  private loadHistory(workflowId: string): void {
    this.historyLoading.set(true);
    this.historyDenied.set(false);

    this.workflowApi.get(workflowId).subscribe({
      next: (workflow) =>
        this.workflowMeta.set({
          createdBy: workflow.createdBy,
          updatedBy: workflow.updatedBy,
          createdAt: workflow.createdAt,
          updatedAt: workflow.updatedAt,
        }),
      error: () => this.workflowMeta.set(null),
    });

    this.workflowApi.audit(workflowId).subscribe({
      next: (page) => {
        this.history.set(page.content);
        this.historyLoading.set(false);
      },
      error: (err: { status?: number }) => {
        this.historyDenied.set(err?.status === 403);
        this.history.set([]);
        this.historyLoading.set(false);
      },
    });
  }

  /** Turns an audit action code into a short label: WORKFLOW_UPDATED -> "Updated". */
  protected actionLabel(action: string): string {
    const stripped = action.replace(/^WORKFLOW_/, '').toLowerCase().replace(/_/g, ' ');
    return stripped.charAt(0).toUpperCase() + stripped.slice(1);
  }

  /** A compact one-line rendering of an audit entry's details, or empty when there are none. */
  protected detailText(details: Record<string, unknown>): string {
    const entries = Object.entries(details ?? {});
    if (entries.length === 0) {
      return '';
    }
    return entries
      .map(([key, value]) => `${key}: ${typeof value === 'object' ? JSON.stringify(value) : value}`)
      .join(', ');
  }
}
