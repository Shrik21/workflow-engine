import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { AiCliApiService } from '../../core/api/ai-cli-api.service';
import { AuthStateService } from '../../core/auth/auth-state.service';
import { NotificationService } from '../../core/notification.service';
import { ErrorAnalysis } from '../../core/models/ai-cli.models';

/**
 * A failed node's error, with an optional AI explanation beneath it.
 *
 * <h2>The error comes first, and stands alone</h2>
 *
 * The engine's own error code and message are rendered immediately and unconditionally. The AI section is
 * additive: it appears only when someone asks for it, and if the analysis fails, the original error is still
 * there. An interface that replaced the real error with an AI summary would be trading a fact for a paraphrase.
 *
 * <h2>Unverified claims are marked as unverified</h2>
 *
 * `verified` says whether the IAM claims were confirmed against the engine's own reference. When it is false,
 * the recommendation is shown with its warnings and without the confident styling — because presenting a
 * language model's guess about a permission as though the platform had checked it is precisely the failure the
 * server-side validation exists to prevent, and the UI is the last place that guarantee can be thrown away.
 *
 * <h2>Retry re-runs the node, and creates no second instance</h2>
 *
 * The Retry button emits an event; the host page performs the retry through the existing execution API, so the
 * original workflow instance resumes rather than a duplicate being started.
 */
@Component({
  selector: 'wf-error-analysis-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="analysis">
      <div class="analysis__head">
        <span class="analysis__icon" aria-hidden="true">⚠</span>
        <span class="analysis__title">{{ heading() }}</span>
      </div>

      <dl class="analysis__facts">
        @if (operation()) {
          <dt>Operation</dt>
          <dd>{{ operation() }}</dd>
        }
        @if (resource()) {
          <dt>Resource</dt>
          <dd>{{ resource() }}</dd>
        }
        <dt>Error</dt>
        <dd><code>{{ errorCode() }}</code></dd>
      </dl>

      <!-- The engine's own message, always, regardless of what the AI does or does not say. -->
      <p class="analysis__message">{{ errorMessage() }}</p>

      @if (analysis(); as a) {
        @if (a.success) {
          <div class="analysis__ai" [class.analysis__ai--unverified]="!a.verified">
            <div class="analysis__ai-head">
              <span>AI analysis</span>
              @if (a.verified) {
                <span class="pill pill--ok" title="IAM claims confirmed against OrchPilot's own reference">
                  Verified
                </span>
              } @else {
                <span class="pill pill--warn" title="Not confirmed against OrchPilot's IAM reference">
                  Unverified
                </span>
              }
              @if (a.analysedBy) {
                <span class="analysis__by">via {{ a.analysedBy }}</span>
              }
            </div>

            @if (a.missingPermission) {
              <dl class="analysis__facts">
                <dt>Missing permission</dt>
                <dd><code>{{ a.missingPermission }}</code></dd>
                @if (a.recommendedRole) {
                  <dt>Recommended role</dt>
                  <dd><code>{{ a.recommendedRole }}</code></dd>
                }
                @if (a.resource) {
                  <dt>Grant at</dt>
                  <dd>{{ a.resource }}</dd>
                }
                @if (a.securityRisk) {
                  <dt>Security risk</dt>
                  <dd>{{ a.securityRisk }}</dd>
                }
              </dl>
            }

            @if (a.reason) {
              <p>{{ a.reason }}</p>
            }
            @if (a.recommendedAction) {
              <p><strong>Recommended resolution.</strong> {{ a.recommendedAction }}</p>
            }

            <!-- Warnings are never suppressed to make the answer look cleaner. -->
            @if (a.warnings.length) {
              <ul class="analysis__warnings">
                @for (warning of a.warnings; track warning) {
                  <li>{{ warning }}</li>
                }
              </ul>
            }

            <p class="analysis__note">
              This is a recommendation, not an action. OrchPilot does not change IAM permissions — apply the
              change in Google Cloud, then retry.
            </p>
          </div>
        } @else {
          <div class="analysis__ai analysis__ai--unverified">
            <div class="analysis__ai-head"><span>AI analysis unavailable</span></div>
            <p>{{ a.reason }}</p>
          </div>
        }
      }

      <div class="analysis__actions">
        @if (canAnalyse()) {
          <button class="btn btn--sm" type="button" [disabled]="analysing()" (click)="analyse()">
            {{ analysing() ? 'Analysing…' : analysis() ? 'Analyse again' : 'Analyse with Claude' }}
          </button>
        }
        @if (canRetry()) {
          <button class="btn btn--sm btn--primary" type="button" (click)="retry.emit()">Retry</button>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .analysis {
        border: 1px solid var(--border);
        border-left: 3px solid var(--warning, #d99a00);
        border-radius: var(--radius-md, 8px);
        padding: var(--space-4);
        background: var(--surface);
      }
      .analysis__head {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        margin-bottom: var(--space-3);
      }
      .analysis__title {
        font-weight: 600;
      }
      .analysis__facts {
        display: grid;
        grid-template-columns: max-content 1fr;
        gap: var(--space-1) var(--space-4);
        margin: 0 0 var(--space-3);
      }
      .analysis__facts dt {
        color: var(--text-muted);
        font-size: 0.875rem;
      }
      .analysis__facts dd {
        margin: 0;
        word-break: break-word;
      }
      .analysis__message {
        margin: 0 0 var(--space-3);
        white-space: pre-wrap;
      }
      .analysis__ai {
        border-top: 1px solid var(--border);
        padding-top: var(--space-3);
        margin-top: var(--space-3);
      }
      .analysis__ai--unverified {
        opacity: 0.92;
      }
      .analysis__ai-head {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        margin-bottom: var(--space-2);
        font-weight: 600;
      }
      .analysis__by {
        font-weight: 400;
        font-size: 0.8rem;
        color: var(--text-muted);
      }
      .analysis__warnings {
        margin: var(--space-2) 0;
        padding-left: var(--space-4);
        font-size: 0.875rem;
        color: var(--warning-text, #8a6100);
      }
      .analysis__note {
        margin: var(--space-3) 0 0;
        font-size: 0.8rem;
        color: var(--text-muted);
      }
      .analysis__actions {
        display: flex;
        gap: var(--space-2);
        margin-top: var(--space-4);
      }
    `,
  ],
})
export class ErrorAnalysisPanel {
  private readonly api = inject(AiCliApiService);

  private readonly notify = inject(NotificationService);

  private readonly session = inject(AuthStateService);

  readonly executionId = input.required<string>();

  readonly nodeId = input.required<string>();

  readonly errorCode = input<string>('');

  readonly errorMessage = input<string>('');

  readonly operation = input<string | null>(null);

  readonly resource = input<string | null>(null);

  readonly heading = input<string>('Node failed');

  /** Whether the host page can re-run the node; hides the button when it cannot. */
  readonly retryable = input<boolean>(false);

  /** Emitted when the user asks to retry. The host performs it, so no duplicate instance is created here. */
  readonly retry = output<void>();

  protected readonly analysis = signal<ErrorAnalysis | null>(null);

  protected readonly analysing = signal(false);

  protected canAnalyse(): boolean {
    return this.session.has('AI_ERROR_ANALYSIS');
  }

  protected canRetry(): boolean {
    // The engine's own view of retryability wins; the AI's opinion is advisory and does not enable the button.
    return this.retryable();
  }

  protected analyse(): void {
    this.analysing.set(true);
    this.api.analyseNode(this.executionId(), this.nodeId()).subscribe({
      next: (result) => {
        this.analysing.set(false);
        this.analysis.set(result);
        if (!result.success) {
          this.notify.warning('The AI analysis could not be produced.', result.reason ?? '');
        }
      },
      error: (error) => {
        this.analysing.set(false);
        const body = (error as { error?: { message?: string; detail?: string } })?.error;
        this.notify.error(
          'Analysis failed',
          body?.message ?? body?.detail ?? 'The AI CLI could not be reached.',
        );
      },
    });
  }
}
