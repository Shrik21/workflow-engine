import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { EventApiService } from '../../core/api/event-api.service';
import { WorkflowApiService } from '../../core/api/workflow-api.service';
import { NotificationService } from '../../core/notification.service';
import { WorkflowResponse } from '../../core/models/workflow.models';
import { KvEditor } from '../../shared/forms/kv-editor';

/**
 * Emits a business event.
 *
 * The engine answers 202 without saying which workflows it started, because fan-out is asynchronous by
 * design. To make that useful rather than opaque, this screen lists the published workflows that
 * subscribe to each event name, so the operator can see in advance what emitting one will do.
 */
import { Icon } from '../../shared/ui/icon';
@Component({
  selector: 'wf-event-emitter',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, KvEditor],
  template: `
    <div class="page">
      <div class="page-header">
        <div class="page-header__text">
          <h1>Emit an event</h1>
          <p>
            An event starts every published workflow with a matching enabled trigger. The payload becomes
            each execution's input, readable as
            <code>&#36;{{ '{' }}input.name{{ '}' }}</code>.
          </p>
        </div>
      </div>

      <div class="grid-2">
        <div class="card">
          <div class="card__header"><h3>Event</h3></div>
          <div class="card__body">
            <div class="field">
              <label class="field__label" for="event-name">Name</label>
              <input
                id="event-name"
                type="text"
                class="mono"
                placeholder="ORDER_CREATED"
                [attr.list]="'known-events'"
                [value]="name()"
                (input)="name.set($any($event.target).value)"
              />
              @if (knownEvents().length > 0) {
                <datalist id="known-events">
                  @for (event of knownEvents(); track event) {
                    <option [value]="event"></option>
                  }
                </datalist>
              }
            </div>

            <div class="field">
              <span class="field__label">Payload</span>
              <wf-kv-editor
                [value]="payload()"
                keyLabel="field"
                emptyText="No payload."
                (valueChange)="payload.set($event)"
              />
            </div>

            <div class="field">
              <label class="field__label" for="correlation">Correlation id</label>
              <input
                id="correlation"
                type="text"
                placeholder="optional, carried into every started execution"
                [value]="correlationId()"
                (input)="correlationId.set($any($event.target).value)"
              />
            </div>

            @if (matching().length > 0) {
              <div class="notice">
                Emitting <code>{{ name() }}</code> will start
                {{ matching().length }} workflow(s):
                <strong>{{ matchingNames() }}</strong>
              </div>
            } @else if (name().trim()) {
              <div class="notice notice--warning">
                No published workflow subscribes to <code>{{ name().trim() }}</code>. The event will be
                accepted and start nothing.
              </div>
            }
          </div>
          <div class="card__footer">
            <button
              class="btn btn--accent"
              type="button"
              [disabled]="!name().trim() || busy()"
              (click)="emit()"
            >
              Emit event
            </button>
          </div>
        </div>

        <div class="card">
          <div class="card__header">
            <h3>Event subscriptions</h3>
            <span class="spacer"></span>
            <button class="btn btn--sm" type="button" (click)="loadWorkflows()"><wf-icon name="refresh" /><span>Refresh</span></button>
          </div>
          @if (subscriptions().length === 0) {
            <div class="card__body">
              <p class="small muted">
                No published workflow has an event trigger. Add one in the designer under "Variables and
                triggers".
              </p>
            </div>
          } @else {
            <table class="table">
              <thead>
                <tr>
                  <th>Event</th>
                  <th>Workflow</th>
                  <th>Trigger</th>
                </tr>
              </thead>
              <tbody>
                @for (subscription of subscriptions(); track subscription.key) {
                  <tr>
                    <td class="mono">{{ subscription.eventName }}</td>
                    <td>{{ subscription.workflowName }}</td>
                    <td class="mono small muted">{{ subscription.triggerId }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </div>
      </div>
    </div>
  `,
})
export class EventEmitter {
  private readonly api = inject(EventApiService);
  private readonly workflowApi = inject(WorkflowApiService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);

  protected readonly name = signal('');
  protected readonly payload = signal<Record<string, unknown>>({});
  protected readonly correlationId = signal('');
  protected readonly busy = signal(false);

  private readonly workflows = signal<WorkflowResponse[]>([]);

  /** Published workflows with an enabled event trigger, flattened to one row per subscription. */
  protected readonly subscriptions = computed(() => {
    const rows: Array<{ key: string; eventName: string; workflowName: string; triggerId: string }> = [];
    for (const workflow of this.workflows()) {
      for (const trigger of workflow.triggers ?? []) {
        if (trigger.type === 'EVENT' && trigger.enabled !== false && trigger.eventName) {
          rows.push({
            key: `${workflow.id}:${trigger.id}`,
            eventName: trigger.eventName,
            workflowName: workflow.name,
            triggerId: trigger.id,
          });
        }
      }
    }
    return rows.sort((left, right) => left.eventName.localeCompare(right.eventName));
  });

  protected readonly knownEvents = computed(() => [
    ...new Set(this.subscriptions().map((subscription) => subscription.eventName)),
  ]);

  protected readonly matching = computed(() => {
    const wanted = this.name().trim();
    return wanted
      ? this.subscriptions().filter((subscription) => subscription.eventName === wanted)
      : [];
  });

  protected readonly matchingNames = computed(() =>
    [...new Set(this.matching().map((subscription) => subscription.workflowName))].join(', '),
  );

  constructor() {
    this.loadWorkflows();
  }

  protected loadWorkflows(): void {
    this.workflowApi.list({ status: 'PUBLISHED', size: 200 }).subscribe({
      next: (page) => this.workflows.set(page.content),
    });
  }

  protected emit(): void {
    this.busy.set(true);
    this.api
      .emit({
        name: this.name().trim(),
        payload: this.payload(),
        correlationId: this.correlationId().trim() || null,
      })
      .subscribe({
        next: (accepted) => {
          this.busy.set(false);
          this.notifications.success(
            `Event "${accepted.name}" accepted`,
            'Executions start asynchronously. Check the execution list to follow them.',
          );
          this.router.navigate(['/executions']);
        },
        error: () => this.busy.set(false),
      });
  }
}
