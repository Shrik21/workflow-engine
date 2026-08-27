import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { PublicFormApiService } from '../../core/api/public-form-api.service';
import { NotificationService } from '../../core/notification.service';
import { RenderableForm } from '../../core/models/form.models';
import { ExternalFormState, PublicFormView } from '../../core/models/public-form.models';
import { DynamicForm } from '../forms/dynamic-form';

type Mode = 'loading' | 'form' | 'success' | 'unavailable';

/**
 * The public, account-free form an external customer fills in from a secure link.
 *
 * <h2>Nothing internal is shown</h2>
 *
 * This page renders only what the server returns for the token — a title and fields. No workflow name, node,
 * task id or internal navigation appears, because none is in the response. It
 * lives outside the authenticated shell, so it never redirects a signed-out customer to a login page.
 *
 * <h2>The server is the authority</h2>
 *
 * The Submit control follows {@code allowSubmit} from the server, but the server enforces the rule regardless:
 * a submit against a paused or terminated instance is refused with a 409 even from a tab left open, and this
 * page reacts by re-reading the state and showing the right message rather than pretending it succeeded.
 */
@Component({
  selector: 'wf-public-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DynamicForm],
  template: `
    <div class="public">
      <main class="public__main">
        @switch (mode()) {
          @case ('loading') {
            <div class="card"><p class="muted">Loading…</p></div>
          }

          @case ('form') {
            @if (view(); as v) {
              <div class="card">
                <h1>{{ v.formTitle || 'Form' }}</h1>
                @if (v.formDescription) {
                  <p class="muted">{{ v.formDescription }}</p>
                }

                @if (v.state === 'WORKFLOW_PAUSED') {
                  <div class="notice notice--warn">
                    This workflow is currently paused. You can save your progress, but you cannot submit the
                    form until the workflow is resumed.
                  </div>
                } @else if (v.state === 'WORKFLOW_TERMINATED') {
                  <div class="notice notice--error">
                    This workflow instance has been terminated. You can save your form as a draft, but it can no
                    longer be submitted.
                  </div>
                }

                <wf-dynamic-form
                  [form]="renderable()"
                  [initialData]="v.draftData"
                  [readOnly]="!v.allowSubmit && !v.allowDraft"
                  [allowSubmit]="v.allowSubmit"
                  [allowSave]="v.allowDraft"
                  [showActions]="v.allowSubmit || v.allowDraft"
                  [busy]="busy()"
                  (submitted)="submit($event)"
                  (saved)="saveDraft($event)"
                />
              </div>
            }
          }

          @case ('success') {
            <div class="card card--center">
              <div class="tick">✓</div>
              <h1>Thank you</h1>
              <p>Your information has been submitted successfully.</p>
              @if (reference()) {
                <p class="reference">
                  Reference number<br />
                  <strong>{{ reference() }}</strong>
                </p>
              }
              <p class="muted small">You may now close this window.</p>
            </div>
          }

          @case ('unavailable') {
            <div class="card card--center">
              <h1>{{ unavailableTitle() }}</h1>
              <p>{{ unavailableMessage() }}</p>
              <p class="muted small">
                Please contact the organization that provided this form link.
              </p>
            </div>
          }
        }
      </main>

      <footer class="public__footer">Powered by OrchPilot</footer>
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
        min-height: 100vh;
        background: var(--surface-sunken, #f4f6f9);
      }
      .public {
        display: flex;
        flex-direction: column;
        min-height: 100vh;
      }
      .public__main {
        flex: 1;
        display: flex;
        justify-content: center;
        align-items: flex-start;
        padding: var(--space-6, 24px) var(--space-4, 16px);
      }
      .card {
        width: 100%;
        max-width: 640px;
        background: var(--surface, #fff);
        border: 1px solid var(--border, #e2e6ea);
        border-radius: var(--radius-lg, 12px);
        box-shadow: var(--shadow-md, 0 2px 10px rgba(0, 0, 0, 0.05));
        padding: var(--space-6, 24px);
      }
      .card--center {
        text-align: center;
        max-width: 480px;
      }
      h1 {
        margin: 0 0 var(--space-2, 8px);
        font-size: 1.5rem;
      }
      .muted {
        color: var(--text-muted, #667);
      }
      .small {
        font-size: 0.85rem;
      }
      .notice {
        padding: var(--space-3, 12px);
        border-radius: var(--radius, 8px);
        margin: var(--space-3, 12px) 0;
        font-size: 0.9rem;
      }
      .notice--warn {
        background: var(--warning-soft, #fdf3e2);
        color: var(--warning, #b26a00);
      }
      .notice--error {
        background: var(--danger-soft, #fdeaea);
        color: var(--danger, #c62828);
      }
      .tick {
        width: 56px;
        height: 56px;
        margin: 0 auto var(--space-3, 12px);
        border-radius: 50%;
        background: var(--success-soft, #e6f4ea);
        color: var(--success, #1e7e34);
        font-size: 2rem;
        line-height: 56px;
      }
      .reference {
        margin: var(--space-4, 16px) 0;
        font-size: 1.1rem;
      }
      .public__footer {
        text-align: center;
        padding: var(--space-4, 16px);
        color: var(--text-muted, #889);
        font-size: 0.8rem;
      }
    `,
  ],
})
export class PublicForm {
  /** The secure token, bound from the route by withComponentInputBinding. */
  readonly token = input.required<string>();

  private readonly api = inject(PublicFormApiService);
  private readonly notifications = inject(NotificationService);

  protected readonly mode = signal<Mode>('loading');
  protected readonly view = signal<PublicFormView | null>(null);
  protected readonly reference = signal<string | null>(null);
  protected readonly busy = signal(false);
  private readonly errorState = signal<ExternalFormState>('INVALID');

  protected readonly renderable = computed<RenderableForm>(() => {
    const v = this.view();
    return {
      name: 'public-form',
      title: v?.formTitle ?? null,
      fields: v?.fields ?? [],
      columns: 1,
      submitButtonText: 'Submit',
      saveButtonText: 'Save draft',
    };
  });

  constructor() {
    effect(() => {
      const token = this.token();
      if (token) {
        this.load(token);
      }
    });
  }

  private load(token: string): void {
    this.mode.set('loading');
    this.api.open(token).subscribe({
      next: (view) => this.applyView(view),
      error: (response) => this.applyError(response),
    });
  }

  private applyView(view: PublicFormView): void {
    this.view.set(view);
    if (view.state === 'OPEN' || view.state === 'WORKFLOW_PAUSED' || view.state === 'WORKFLOW_TERMINATED') {
      this.mode.set('form');
    } else {
      this.errorState.set(view.state);
      this.mode.set('unavailable');
    }
  }

  private applyError(response: { error?: { errorCode?: string } }): void {
    const code = response?.error?.errorCode as ExternalFormState | undefined;
    this.errorState.set(code ?? 'INVALID');
    this.mode.set('unavailable');
  }

  protected submit(data: Record<string, unknown>): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.api.submit(this.token(), data).subscribe({
      next: (result) => {
        this.busy.set(false);
        this.reference.set(result.referenceNumber);
        this.mode.set('success');
      },
      error: (response: { error?: { errorCode?: string; message?: string } }) => {
        this.busy.set(false);
        // A stale tab: the instance changed under the customer. Re-read the state and show the right screen.
        const code = response?.error?.errorCode as ExternalFormState | undefined;
        if (code && code !== 'INVALID') {
          this.load(this.token());
        } else {
          this.notifications.error('Could not submit', response?.error?.message ?? 'Please try again.');
        }
      },
    });
  }

  protected saveDraft(data: Record<string, unknown>): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.api.saveDraft(this.token(), data).subscribe({
      next: () => {
        this.busy.set(false);
        this.notifications.success('Draft saved', 'Your progress has been saved.');
      },
      error: (response) => {
        this.busy.set(false);
        this.load(this.token());
        void response;
      },
    });
  }

  protected unavailableTitle(): string {
    switch (this.errorState()) {
      case 'EXPIRED':
        return 'Link expired';
      case 'REVOKED':
        return 'Link revoked';
      case 'ALREADY_SUBMITTED':
        return 'Already submitted';
      case 'CANCELLED':
        return 'Form unavailable';
      default:
        return 'Form unavailable';
    }
  }

  protected unavailableMessage(): string {
    switch (this.errorState()) {
      case 'EXPIRED':
        return 'This form link has expired.';
      case 'REVOKED':
        return 'This form link has been revoked.';
      case 'ALREADY_SUBMITTED':
        return 'This form has already been submitted.';
      case 'CANCELLED':
        return 'This form is no longer available.';
      default:
        return 'This form link is not valid.';
    }
  }
}
