import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ExecutionApiService } from '../../core/api/execution-api.service';
import { WorkflowInstanceApiService } from '../../core/api/workflow-instance-api.service';
import { AuthStateService } from '../../core/auth/auth-state.service';
import {
  ExecutionLogResponse,
  ExecutionResponse,
  InstanceStatus,
  NodeHistoryView,
  isTerminal,
} from '../../core/models/execution.models';
import { NotificationService } from '../../core/notification.service';
import { AgoPipe, DurationPipe, PrettyJsonPipe } from '../../shared/pipes/format.pipes';
import { Modal } from '../../shared/ui/modal';
import { StatusPill } from '../../shared/ui/status-pill';
import { FormRunner } from './form-runner';

type Tab = 'Timeline' | 'Variables' | 'Result' | 'Logs';

/**
 * One execution, in detail.
 *
 * The timeline is the primary view because the question being asked is almost always "where did this
 * get to, and what happened there". Attempt numbers, selected branches, durations and per-node errors
 * are all on the timeline rather than hidden behind an expander, since each is a normal part of reading
 * a failed run.
 *
 * A waiting execution surfaces its form inline, so the operator who is already looking at the run can
 * answer it without going to the inbox.
 */
import { Icon } from '../../shared/ui/icon';
@Component({
  selector: 'wf-execution-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, 
    RouterLink,
    FormsModule,
    StatusPill,
    FormRunner,
    Modal,
    DurationPipe,
    AgoPipe,
    PrettyJsonPipe,
  ],
  template: `
    @if (execution(); as run) {
      <div class="page">
        <div class="page-header">
          <div class="page-header__text">
            <h1>Execution</h1>
            <p class="mono small">{{ run.executionId }}</p>
          </div>
          <div class="toolbar">
            @if (!terminal()) {
              @if (run.status === 'PAUSED') {
                @if (session.has('WORKFLOW_INSTANCE_RESUME')) {
                  <button class="btn btn--sm" type="button" [disabled]="busy()" (click)="resume()">
                    Resume
                  </button>
                }
              } @else if (session.has('WORKFLOW_INSTANCE_PAUSE')) {
                <button
                  class="btn btn--sm"
                  type="button"
                  [disabled]="busy()"
                  (click)="pendingPause.set(true)"
                >
                  Pause
                </button>
              }
              @if (session.has('WORKFLOW_INSTANCE_TERMINATE')) {
                <button
                  class="btn btn--danger btn--sm"
                  type="button"
                  [disabled]="busy()"
                  (click)="openTerminate()"
                >
                  Terminate
                </button>
              }
            }
            <button class="btn btn--sm" type="button" (click)="reload()"><wf-icon name="refresh" /><span>Refresh</span></button>
          </div>
        </div>

        <div class="summary card">
          <div class="summary__grid">
            <div>
              <span class="summary__label">Status</span>
              <wf-status-pill [status]="run.status" />
            </div>
            <div>
              <span class="summary__label">Workflow</span>
              <a [routerLink]="['/workflows', run.workflowId]">{{
                run.workflowName || run.workflowId
              }}</a>
              <span class="tag" style="margin-left: 6px">v{{ run.workflowVersion }}</span>
            </div>
            <div>
              <span class="summary__label">Started</span>
              <span [title]="run.startedAt ?? ''">{{ run.startedAt | ago }}</span>
            </div>
            <div>
              <span class="summary__label">Elapsed</span>
              <span>{{ elapsed() | duration }}</span>
            </div>
            <div>
              <span class="summary__label">Steps</span>
              <span>{{ run.stepCount }}</span>
            </div>
            <div>
              <span class="summary__label">Started by</span>
              <span>{{ run.mode }}</span>
            </div>
          </div>

          @if (run.error) {
            <div class="notice notice--error summary__error">
              <strong>{{ run.error.code }}</strong>
              <p>{{ run.error.message }}</p>
              @if (run.error.nodeId) {
                <p class="small muted">Failed at node <code>{{ run.error.nodeId }}</code></p>
              }
            </div>
          }

          @if (run.status === 'PAUSED') {
            <div class="notice notice--warn summary__error">
              <strong>Instance paused</strong>
              <p>
                Execution is paused and its active tasks are held. Assignees can save form drafts but cannot
                submit until it is resumed.
              </p>
              @if (instanceInfo()?.pauseReason) {
                <p class="small muted">Reason: {{ instanceInfo()?.pauseReason }}</p>
              }
            </div>
          }

          @if (run.status === 'TERMINATED') {
            <div class="notice notice--error summary__error">
              <strong>Instance terminated</strong>
              <p>This instance has been permanently terminated. It cannot be resumed or restarted.</p>
              @if (instanceInfo(); as info) {
                <p class="small muted">
                  @if (info.terminatedBy) {
                    Terminated by {{ info.terminatedBy }}
                  }
                  @if (info.terminatedAt) {
                    · {{ info.terminatedAt | ago }}
                  }
                  @if (info.terminationReason) {
                    · Reason: {{ info.terminationReason }}
                  }
                </p>
              }
            </div>
          }
        </div>

        @if (run.status === 'WAITING' && run.pendingSignal) {
          <div class="card" style="margin-top: var(--space-4)">
            <div class="card__header">
              <h3>Waiting for input</h3>
              <span class="spacer"></span>
              <span class="small muted"
                >parked at <code>{{ run.pendingSignal.nodeId }}</code></span
              >
            </div>
            <div class="card__body">
              <wf-form-runner
                [pending]="run.pendingSignal"
                [executionId]="run.executionId"
                (submitted)="onSubmitted()"
              />
            </div>
          </div>
        }

        <nav class="tabs" role="tablist">
          @for (tab of tabs; track tab) {
            <button
              class="tab"
              type="button"
              role="tab"
              [class.tab--active]="activeTab() === tab"
              [attr.aria-selected]="activeTab() === tab"
              (click)="selectTab(tab)"
            >
              {{ tab }}
              @if (tab === 'Timeline') {
                <span class="tab__count">{{ run.nodeHistory.length }}</span>
              }
              @if (tab === 'Logs' && logs().length > 0) {
                <span class="tab__count">{{ logs().length }}</span>
              }
            </button>
          }
        </nav>

        <div class="card tab-body">
          @switch (activeTab()) {
            @case ('Timeline') {
              @if (run.nodeHistory.length === 0 && !inFlight()) {
                <p class="pad small muted">No nodes have executed yet.</p>
              } @else {
                <ol class="timeline">
                  @for (record of run.nodeHistory; track $index) {
                    <li class="timeline__item" [class.timeline__item--current]="isCurrent(run, record)">
                      <span class="timeline__dot" [style.--dot]="dotColor(record.status)"></span>
                      <div class="timeline__content">
                        <div class="timeline__head">
                          <strong>{{ record.nodeName || record.nodeId }}</strong>
                          <span class="tag tag--mono">{{ record.nodeType }}</span>
                          <wf-status-pill [status]="record.status" />
                          @if (record.attempt > 1) {
                            <span class="tag" title="This node was retried"
                              >attempt {{ record.attempt }}</span
                            >
                          }
                          @if (record.selectedBranch) {
                            <span class="tag" title="Branch chosen by this node"
                              >to {{ record.selectedBranch }}</span
                            >
                          }
                          <span class="spacer"></span>
                          <span class="small muted">{{ record.durationMillis | duration }}</span>
                        </div>

                        @if (record.pluginId) {
                          <p class="small muted">
                            Executed by plugin <code>{{ record.pluginId }}</code>
                            @if (record.pluginVersion) {
                              <code>{{ record.pluginVersion }}</code>
                            }
                          </p>
                        }

                        @if (record.errorCode) {
                          <div class="notice notice--error timeline__error">
                            <strong>{{ record.errorCode }}</strong>
                            <p class="small">{{ record.errorMessage }}</p>
                          </div>
                        }

                        @if (hasKeys(record.outputs)) {
                          <details class="outputs">
                            <summary class="small">
                              Outputs ({{ keyCount(record.outputs) }})
                            </summary>
                            <pre class="code">{{ record.outputs | prettyJson }}</pre>
                          </details>
                        }
                      </div>
                    </li>
                  }

                  @if (inFlight(); as live) {
                    <li class="timeline__item timeline__item--live">
                      <span class="timeline__dot timeline__dot--live"></span>
                      <div class="timeline__content">
                        <div class="timeline__head">
                          <strong>{{ live.nodeId }}</strong>
                          @if (live.nodeType) {
                            <span class="tag tag--mono">{{ live.nodeType }}</span>
                          }
                          <span class="tag tag--live">Running</span>
                          <span class="spacer"></span>
                          <span class="small muted">{{ live.elapsedMillis | duration }}</span>
                        </div>
                        <p class="small muted">
                          Still executing. It joins the timeline above with its outputs once it finishes.
                        </p>
                      </div>
                    </li>
                  }
                </ol>
              }
            }

            @case ('Variables') {
              <div class="pad">
                <p class="small muted">
                  Every scope as persisted. Secret values a plugin read are removed before anything is
                  written, so they do not appear here.
                </p>
                <pre class="code">{{ run.variables | prettyJson }}</pre>
              </div>
            }

            @case ('Result') {
              <div class="pad">
                @if (hasKeys(run.output)) {
                  <pre class="code">{{ run.output | prettyJson }}</pre>
                } @else {
                  <p class="small muted">
                    No result yet. An end node assembles it when the workflow completes.
                  </p>
                }
              </div>
            }

            @case ('Logs') {
              @if (logs().length === 0) {
                <p class="pad small muted">No log entries.</p>
              } @else {
                <table class="table">
                  <thead>
                    <tr>
                      <th style="width: 60px">Seq</th>
                      <th style="width: 70px">Level</th>
                      <th style="width: 150px">Node</th>
                      <th>Message</th>
                      <th style="width: 90px">At</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (entry of logs(); track entry.sequence) {
                      <tr>
                        <td class="mono small">{{ entry.sequence }}</td>
                        <td>
                          <span class="level" [class]="'level--' + entry.level.toLowerCase()">{{
                            entry.level
                          }}</span>
                        </td>
                        <td class="mono small">{{ entry.nodeId || '' }}</td>
                        <td>
                          {{ entry.message }}
                          @if (hasKeys(entry.details)) {
                            <details>
                              <summary class="small muted">details</summary>
                              <pre class="code">{{ entry.details | prettyJson }}</pre>
                            </details>
                          }
                        </td>
                        <td class="small muted" [title]="entry.at ?? ''">{{ entry.at | ago }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              }
            }
          }
        </div>
      </div>
    } @else {
      <div class="page">
        <p class="muted">Loading execution…</p>
      </div>
    }

    @if (pendingPause()) {
      <wf-modal
        heading="Pause workflow instance?"
        width="480px"
        [dismissable]="!busy()"
        (closed)="pendingPause.set(false)"
      >
        <p>
          This will pause execution for this workflow instance and pause its active tasks. Assigned form users
          can save drafts but cannot submit forms until the instance is resumed.
        </p>
        <label class="field">
          <span class="small muted">Reason (optional)</span>
          <input type="text" [(ngModel)]="pauseReason" placeholder="e.g. waiting for customer approval" />
        </label>
        <div modalFooter style="display: flex; gap: var(--space-3); justify-content: flex-end">
          <button class="btn" type="button" [disabled]="busy()" (click)="pendingPause.set(false)">
            Cancel
          </button>
          <button class="btn btn--primary" type="button" [disabled]="busy()" (click)="pause()">
            {{ busy() ? 'Pausing…' : 'Pause' }}
          </button>
        </div>
      </wf-modal>
    }

    @if (pendingTerminate()) {
      <wf-modal
        heading="Terminate workflow instance?"
        width="480px"
        [dismissable]="!busy()"
        (closed)="pendingTerminate.set(false)"
      >
        <p><strong>This action is permanent.</strong></p>
        <p>
          All active tasks for this workflow instance will be terminated. The workflow instance cannot be
          resumed or restarted. Assigned form users will only be able to save their form as a draft and cannot
          submit it.
        </p>
        <label class="field">
          <span class="small muted">Reason</span>
          <input type="text" [(ngModel)]="terminateReason" placeholder="Why is this being terminated?" />
        </label>
        <div modalFooter style="display: flex; gap: var(--space-3); justify-content: flex-end">
          <button class="btn" type="button" [disabled]="busy()" (click)="pendingTerminate.set(false)">
            Cancel
          </button>
          <button class="btn btn--danger" type="button" [disabled]="busy()" (click)="terminate()">
            {{ busy() ? 'Terminating…' : 'Terminate' }}
          </button>
        </div>
      </wf-modal>
    }
  `,
  styles: [
    `
      .summary__grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
        gap: var(--space-4);
        padding: var(--space-4);
      }

      .summary__label {
        display: block;
        font-size: var(--text-xs);
        text-transform: uppercase;
        letter-spacing: 0.6px;
        color: var(--text-muted);
        margin-bottom: var(--space-1);
      }

      .summary__error {
        margin: 0 var(--space-4) var(--space-4);
      }

      .notice--warn {
        background: var(--warning-soft, #fdf3e2);
        color: var(--warning, #b26a00);
        border-radius: var(--radius-sm);
        padding: var(--space-3);
      }

      .field {
        display: flex;
        flex-direction: column;
        gap: var(--space-1);
        margin-top: var(--space-3);
      }

      .summary__error p {
        margin: var(--space-1) 0 0;
      }

      .tabs {
        display: flex;
        gap: 2px;
        margin: var(--space-5) 0 0;
        border-bottom: 1px solid var(--border);
      }

      .tab {
        border: none;
        background: transparent;
        padding: var(--space-2) var(--space-4);
        font-family: var(--font-body);
        font-size: var(--text-base);
        color: var(--text-muted);
        cursor: pointer;
        border-bottom: 2px solid transparent;
        display: flex;
        align-items: center;
        gap: var(--space-2);
      }

      .tab--active {
        color: var(--hl-blue);
        border-bottom-color: var(--hl-blue);
        font-weight: bold;
      }

      .tab__count {
        font-size: 10px;
        background: var(--hl-grey-200);
        color: var(--hl-grey-800);
        border-radius: 8px;
        padding: 0 5px;
      }

      .tab-body {
        border-top-left-radius: 0;
        border-top-right-radius: 0;
      }

      .pad {
        padding: var(--space-4);
      }

      .code {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        background: var(--hl-grey-100);
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        padding: var(--space-3);
        margin: var(--space-2) 0 0;
        max-height: 460px;
        overflow: auto;
      }

      .timeline {
        list-style: none;
        margin: 0;
        padding: var(--space-4);
      }

      .timeline__item {
        position: relative;
        padding: 0 0 var(--space-4) var(--space-5);
        border-left: 2px solid var(--border);
      }

      .timeline__item:last-child {
        border-left-color: transparent;
        padding-bottom: 0;
      }

      .timeline__item--current .timeline__content {
        background: #f2f8fd;
      }

      .timeline__dot {
        position: absolute;
        left: -7px;
        top: 4px;
        width: 12px;
        height: 12px;
        border-radius: 50%;
        background: var(--dot);
        border: 2px solid var(--surface);
      }

      .timeline__content {
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        padding: var(--space-3);
      }

      /* The step running right now: a dashed edge and a pulsing dot separate "working" from "finished". */
      .timeline__item--live .timeline__content {
        border-style: dashed;
        border-color: var(--hl-accent-blue, #1976d2);
      }

      .timeline__dot--live {
        background: var(--hl-accent-blue, #1976d2);
        animation: timeline-pulse 1.4s ease-in-out infinite;
      }

      .tag--live {
        background: var(--hl-accent-blue, #1976d2);
        color: #fff;
      }

      @keyframes timeline-pulse {
        0%,
        100% {
          box-shadow: 0 0 0 0 rgba(25, 118, 210, 0.55);
        }
        50% {
          box-shadow: 0 0 0 5px rgba(25, 118, 210, 0);
        }
      }

      @media (prefers-reduced-motion: reduce) {
        .timeline__dot--live {
          animation: none;
        }
      }

      .timeline__head {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        flex-wrap: wrap;
      }

      .timeline__error {
        margin-top: var(--space-2);
      }

      .timeline__error p {
        margin: var(--space-1) 0 0;
      }

      .outputs {
        margin-top: var(--space-2);
      }

      .level {
        font-size: 10px;
        font-weight: bold;
        letter-spacing: 0.4px;
      }

      .level--error {
        color: var(--hl-error);
      }
      .level--warn {
        color: var(--hl-orange-alt);
      }
      .level--info {
        color: var(--hl-accent-blue-alt);
      }
      .level--debug {
        color: var(--text-faint);
      }
    `,
  ],
})
export class ExecutionDetail {
  readonly executionId = input.required<string>();

  protected readonly tabs: Tab[] = ['Timeline', 'Variables', 'Result', 'Logs'];

  private readonly api = inject(ExecutionApiService);
  private readonly instanceApi = inject(WorkflowInstanceApiService);
  protected readonly session = inject(AuthStateService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);

  protected readonly execution = signal<ExecutionResponse | null>(null);
  protected readonly logs = signal<ExecutionLogResponse[]>([]);
  protected readonly activeTab = signal<Tab>('Timeline');

  /** Instance lifecycle detail (reason, terminatedBy…), fetched when the instance is paused or terminated. */
  protected readonly instanceInfo = signal<InstanceStatus | null>(null);
  protected readonly pendingPause = signal(false);
  protected readonly pendingTerminate = signal(false);
  protected readonly pauseReason = signal('');
  protected readonly terminateReason = signal('');
  protected readonly busy = signal(false);

  protected readonly terminal = computed(() => isTerminal(this.execution()?.status));

  /** Ticks once a second while a run is live, so elapsed times count up instead of jumping with each poll. */
  private readonly tick = signal(0);

  protected readonly elapsed = computed(() => {
    this.tick();
    const run = this.execution();
    if (!run?.startedAt) {
      return 0;
    }
    const start = Date.parse(run.startedAt);
    const end = run.completedAt ? Date.parse(run.completedAt) : Date.now();
    return Number.isNaN(start) || Number.isNaN(end) ? 0 : end - start;
  });

  /**
   * The node executing right now, or null.
   *
   * <p>The timeline is built from history, which a node only joins once it returns — so a long step (an AI agent
   * working through a tool loop, a cloud operation being polled) would otherwise leave the page blank while the
   * run says RUNNING. The engine publishes the in-flight node before executing it; a node is still in flight
   * exactly when no history record shares its id and start time.
   */
  protected readonly inFlight = computed(() => {
    this.tick();
    const run = this.execution();
    if (!run || isTerminal(run.status) || run.status === 'WAITING') {
      return null;
    }
    const nodeId = run.currentNodeId;
    const startedAt = run.currentNodeStartedAt;
    if (!nodeId || !startedAt) {
      return null;
    }
    const recorded = run.nodeHistory.some(
      (record) => record.nodeId === nodeId && record.startedAt === startedAt,
    );
    if (recorded) {
      return null;
    }
    const start = Date.parse(startedAt);
    return {
      nodeId,
      nodeType: run.currentNodeType ?? '',
      elapsedMillis: Number.isNaN(start) ? 0 : Math.max(0, Date.now() - start),
    };
  });


  constructor() {
    effect(() => {
      const id = this.executionId();
      if (id) {
        this.reload();
      }
    });

    effect((onCleanup) => {
      // Polls only while the run can still change, and only every two seconds because an operator is
      // usually watching a specific run they just started.
      const run = this.execution();
      if (!run || isTerminal(run.status) || run.status === 'WAITING') {
        return;
      }
      const timer = setInterval(() => this.reload(), 2000);
      onCleanup(() => clearInterval(timer));
    });

    effect((onCleanup) => {
      // A separate, cheaper timer than the poll: it only advances the clock so a running step's elapsed time
      // counts up every second, without asking the server anything.
      const run = this.execution();
      if (!run || isTerminal(run.status) || run.status === 'WAITING') {
        return;
      }
      const timer = setInterval(() => this.tick.update((value) => value + 1), 1000);
      onCleanup(() => clearInterval(timer));
    });
  }

  protected reload(): void {
    this.api.get(this.executionId()).subscribe({
      next: (run) => {
        this.execution.set(run);
        if (this.activeTab() === 'Logs') {
          this.loadLogs();
        }
        // The reason and terminatedBy live on the instance record, not the execution response, so fetch them
        // only when there is something to show.
        if (run.status === 'PAUSED' || run.status === 'TERMINATED') {
          this.instanceApi.status(this.executionId()).subscribe({
            next: (info) => this.instanceInfo.set(info),
          });
        } else {
          this.instanceInfo.set(null);
        }
      },
      error: () => this.router.navigate(['/executions']),
    });
  }

  protected selectTab(tab: Tab): void {
    this.activeTab.set(tab);
    if (tab === 'Logs') {
      this.loadLogs();
    }
  }

  protected pause(): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.instanceApi.pause(this.executionId(), this.pauseReason() || null).subscribe({
      next: () => {
        this.busy.set(false);
        this.pendingPause.set(false);
        this.pauseReason.set('');
        this.notifications.success('Paused', 'The instance stops at its next node and its tasks are held.');
        this.reload();
      },
      error: () => this.busy.set(false),
    });
  }

  protected resume(): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.instanceApi.resume(this.executionId()).subscribe({
      next: () => {
        this.busy.set(false);
        this.notifications.success('Resumed', 'Held tasks are actionable again.');
        this.reload();
      },
      error: () => this.busy.set(false),
    });
  }

  protected openTerminate(): void {
    this.terminateReason.set('');
    this.pendingTerminate.set(true);
  }

  protected terminate(): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.instanceApi.terminate(this.executionId(), this.terminateReason() || null).subscribe({
      next: () => {
        this.busy.set(false);
        this.pendingTerminate.set(false);
        this.notifications.success('Terminated', 'The instance and its active tasks have been terminated.');
        this.reload();
      },
      error: () => this.busy.set(false),
    });
  }

  protected onSubmitted(): void {
    this.notifications.success('Submitted', 'The execution has been resumed.');
    this.reload();
  }

  protected isCurrent(run: ExecutionResponse, record: NodeHistoryView): boolean {
    return !isTerminal(run.status) && run.currentNodeId === record.nodeId;
  }

  protected dotColor(status: string): string {
    switch ((status ?? '').toUpperCase()) {
      case 'SUCCESS':
        return 'var(--status-completed)';
      case 'FAILED':
        return 'var(--status-failed)';
      case 'WAITING':
        return 'var(--status-waiting)';
      case 'SKIPPED':
        return 'var(--status-skipped)';
      default:
        return 'var(--status-pending)';
    }
  }

  protected hasKeys(value: Record<string, unknown> | null | undefined): boolean {
    return !!value && Object.keys(value).length > 0;
  }

  protected keyCount(value: Record<string, unknown> | null | undefined): number {
    return value ? Object.keys(value).length : 0;
  }

  private loadLogs(): void {
    this.api.logs(this.executionId(), 500).subscribe({
      next: (entries) => this.logs.set(entries),
    });
  }
}
