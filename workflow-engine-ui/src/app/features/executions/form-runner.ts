import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { ExecutionApiService } from '../../core/api/execution-api.service';
import { FormFieldDescriptor, PendingSignalView } from '../../core/models/execution.models';
import { KvEditor } from '../../shared/forms/kv-editor';
import { coerceScalar } from '../../shared/forms/schema-fields';

/**
 * Renders and submits the form a parked execution is waiting for.
 *
 * Two modes, decided by what the workflow author provided:
 *
 * - When the form node declares `fields` in its configuration, they are rendered as real controls with
 *   labels and types. This is what a designed human task should look like.
 * - When it does not, the operator gets a free-form key/value editor. That fallback matters: the engine
 *   accepts any submission shape, and refusing to render a form just because the author did not
 *   describe it would leave the execution permanently stuck.
 *
 * Prefilled values from the node's input mapping are used as initial values, so an approver sees the
 * amount they are approving rather than an empty form.
 */
@Component({
  selector: 'wf-form-runner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [KvEditor],
  template: `
    <div class="runner">
      @if (pending().reason) {
        <p class="small muted">{{ pending().reason }}</p>
      }

      @if (prefillEntries().length > 0) {
        <div class="prefill">
          <span class="field__label">Context</span>
          <dl>
            @for (entry of prefillEntries(); track entry.key) {
              <div>
                <dt>{{ entry.key }}</dt>
                <dd class="mono">{{ entry.display }}</dd>
              </div>
            }
          </dl>
        </div>
      }

      @if (descriptors().length > 0) {
        @for (field of descriptors(); track field.name) {
          <div class="field">
            <label class="field__label" [attr.for]="'ff-' + field.name">
              {{ field.label || field.name }}
              @if (field.required) {
                <span class="required" aria-hidden="true">*</span>
              }
            </label>
            @switch (field.type) {
              @case ('boolean') {
                <label class="checkbox-row">
                  <input
                    type="checkbox"
                    [id]="'ff-' + field.name"
                    [checked]="value()[field.name] === true"
                    (change)="set(field.name, $any($event.target).checked)"
                  />
                  <span class="small muted">Yes</span>
                </label>
              }
              @case ('number') {
                <input
                  type="number"
                  [id]="'ff-' + field.name"
                  [value]="asText(field.name)"
                  (input)="set(field.name, toNumber($any($event.target).value))"
                />
              }
              @case ('text') {
                <textarea
                  [id]="'ff-' + field.name"
                  rows="3"
                  [value]="asText(field.name)"
                  (input)="set(field.name, $any($event.target).value)"
                ></textarea>
              }
              @case ('select') {
                <select
                  [id]="'ff-' + field.name"
                  [value]="asText(field.name)"
                  (change)="set(field.name, $any($event.target).value)"
                >
                  <option value="">Choose…</option>
                  @for (option of field.options ?? []; track option) {
                    <option [value]="option">{{ option }}</option>
                  }
                </select>
              }
              @default {
                <input
                  type="text"
                  [id]="'ff-' + field.name"
                  [value]="asText(field.name)"
                  (input)="set(field.name, $any($event.target).value)"
                />
              }
            }
          </div>
        }
      } @else {
        <div class="field">
          <span class="field__label">Submission</span>
          <p class="field__hint">
            This form node declares no fields, so enter the values the workflow expects. They become
            the node's outputs.
          </p>
          <wf-kv-editor
            [value]="value()"
            keyLabel="field"
            emptyText="No values yet."
            (valueChange)="value.set($event)"
          />
        </div>
      }

      @if (missing().length > 0) {
        <p class="field__error">Required: {{ missing().join(', ') }}</p>
      }

      <div class="runner__actions">
        <button
          class="btn btn--accent"
          type="button"
          [disabled]="busy() || missing().length > 0"
          (click)="submit()"
        >
          Submit and continue
        </button>
        @if (pending().expiresAt) {
          <span class="small muted">Expires {{ pending().expiresAt }}</span>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .runner {
        display: block;
      }

      .prefill {
        margin-bottom: var(--space-4);
      }

      .prefill dl {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
        gap: var(--space-2);
        margin: var(--space-1) 0 0;
        padding: var(--space-3);
        background: var(--surface-sunken);
        border-radius: var(--radius-sm);
        border: 1px solid var(--border);
      }

      dt {
        font-size: var(--text-xs);
        text-transform: uppercase;
        letter-spacing: 0.5px;
        color: var(--text-muted);
      }

      dd {
        margin: 2px 0 0;
        font-size: var(--text-base);
        word-break: break-word;
      }

      .runner__actions {
        display: flex;
        align-items: center;
        gap: var(--space-3);
        margin-top: var(--space-3);
      }
    `,
  ],
})
export class FormRunner {
  /** Named `pending`, not `signal`, so it does not shadow Angular's `signal` function. */
  readonly pending = input.required<PendingSignalView>();
  readonly executionId = input.required<string>();

  readonly submitted = output<void>();

  private readonly api = inject(ExecutionApiService);

  protected readonly value = signal<Record<string, unknown>>({});
  protected readonly busy = signal(false);

  /** Field descriptors declared by the form node, when it declared any. */
  protected readonly descriptors = computed<FormFieldDescriptor[]>(() => {
    const raw = this.pending().payload?.['fields'];
    if (!Array.isArray(raw)) {
      return [];
    }
    return raw
      .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
      .map((item) => ({
        name: String(item['name'] ?? ''),
        type: item['type'] ? String(item['type']) : 'string',
        label: item['label'] ? String(item['label']) : undefined,
        required: item['required'] === true,
        options: Array.isArray(item['options']) ? item['options'].map(String) : undefined,
      }))
      .filter((field) => field.name.length > 0);
  });

  /** Values the input mapping resolved, shown as read-only context. */
  protected readonly prefillEntries = computed(() => {
    const prefill = this.pending().payload?.['prefill'];
    if (!prefill || typeof prefill !== 'object') {
      return [];
    }
    return Object.entries(prefill as Record<string, unknown>).map(([key, raw]) => ({
      key,
      display: typeof raw === 'object' ? JSON.stringify(raw) : String(raw),
    }));
  });

  protected readonly missing = computed(() => {
    const current = this.value();
    return this.descriptors()
      .filter((field) => field.required)
      .filter((field) => {
        const raw = current[field.name];
        return raw == null || (typeof raw === 'string' && raw.trim().length === 0);
      })
      .map((field) => field.label || field.name);
  });

  protected asText(name: string): string {
    const raw = this.value()[name];
    return raw == null ? '' : String(raw);
  }

  protected set(name: string, raw: unknown): void {
    this.value.update((current) => ({ ...current, [name]: raw }));
  }

  protected toNumber(text: string): unknown {
    return coerceScalar(text);
  }

  protected submit(): void {
    this.busy.set(true);
    this.api
      .submitForm(this.executionId(), {
        nodeId: this.pending().nodeId,
        formId: this.pending().formId,
        data: this.value(),
      })
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.value.set({});
          this.submitted.emit();
        },
        error: () => this.busy.set(false),
      });
  }
}
