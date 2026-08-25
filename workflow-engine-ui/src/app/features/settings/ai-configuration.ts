import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AiCliApiService } from '../../core/api/ai-cli-api.service';
import { AuthStateService } from '../../core/auth/auth-state.service';
import { AiCliFeatureStatus } from '../../core/models/ai-cli.models';

/**
 * Settings → AI Configuration.
 *
 * <h2>Why the unavailable providers are listed rather than hidden</h2>
 *
 * Only Claude CLI has an implementation today. OpenAI, Gemini and Ollama appear anyway, marked as not yet
 * available — because an operator who cannot see them assumes the platform is Claude-only and plans around
 * that. Showing the shape of the extension point is the honest signal that adding one is a small change, and it
 * keeps this page from needing a redesign when the second provider lands.
 *
 * <p>The HTTP providers under AI Providers are a separate, already-working thing: those are AI *services*
 * reached over the network. This section is about AI *command-line tools* the engine host runs. Both are linked
 * from here so the distinction is visible in one place.
 */
@Component({
  selector: 'wf-ai-configuration',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  template: `
    <div class="page">
      <div class="page-header">
        <div class="page-header__text">
          <h1>AI configuration</h1>
          <p>
            How OrchPilot reaches AI models and tools. These are assistive throughout — they help the platform
            explain failures and suggest next actions, and never replace the workflow engine, the plugin
            engine, RBAC, or any cloud provider's own authorization.
          </p>
        </div>
      </div>

      <h2 class="section-heading">Command-line tools</h2>
      <p class="section-hint">
        AI CLIs installed on the engine host. Running a local program is a stronger capability than calling an
        API, so it is gated separately —
        @if (feature(); as f) {
          @if (f.enabled) {
            <span class="pill pill--ok">enabled on this engine</span>
          } @else {
            <span class="pill pill--warn">disabled on this engine</span>
          }
        }
      </p>

      <div class="provider-grid">
        <a class="provider-card" routerLink="/settings/ai/claude-cli">
          <div class="provider-card__head">            <span class="provider-card__name">Claude CLI</span>
            <span class="pill pill--ok">Available</span>
          </div>
          <p class="provider-card__body">
            Anthropic's Claude Code CLI. Used for error analysis, troubleshooting and infrastructure
            assistance.
          </p>
        </a>

        <!-- Listed, not hidden: see the class note on why. -->
        @for (planned of plannedProviders; track planned.name) {
          <div class="provider-card provider-card--disabled">
            <div class="provider-card__head">              <span class="provider-card__name">{{ planned.name }}</span>
              <span class="pill">Not yet available</span>
            </div>
            <p class="provider-card__body">{{ planned.description }}</p>
          </div>
        }
      </div>

      <h2 class="section-heading">Model providers</h2>
      <p class="section-hint">
        AI services reached over HTTP, used by the AI Agent node. Configured separately from the tools above.
      </p>

      <div class="provider-grid">
        @if (session.has('AI_PROVIDER_VIEW')) {
          <a class="provider-card" routerLink="/settings/ai-providers">
            <div class="provider-card__head">              <span class="provider-card__name">Providers &amp; connections</span>
            </div>
            <p class="provider-card__body">
              OpenAI, Anthropic, Gemini, Azure, Bedrock, Vertex, Ollama and OpenAI-compatible endpoints.
            </p>
          </a>
          <a class="provider-card" routerLink="/settings/ai-usage">
            <div class="provider-card__head">              <span class="provider-card__name">Usage</span>
            </div>
            <p class="provider-card__body">Token consumption and cost by workflow and provider.</p>
          </a>
        } @else {
          <p class="section-hint">
            Viewing model providers requires the <code>AI_PROVIDER_VIEW</code> permission.
          </p>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .section-heading {
        margin: var(--space-5) 0 var(--space-2);
        font-size: 1.05rem;
      }
      .section-hint {
        margin: 0 0 var(--space-3);
        color: var(--text-muted);
        font-size: 0.9rem;
      }
      .provider-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
        gap: var(--space-3);
      }
      .provider-card {
        display: block;
        padding: var(--space-4);
        border: 1px solid var(--border);
        border-radius: var(--radius-md, 8px);
        background: var(--surface);
        text-decoration: none;
        color: inherit;
      }
      a.provider-card:hover {
        border-color: var(--accent, #4a6cf7);
      }
      .provider-card--disabled {
        opacity: 0.6;
      }
      .provider-card__head {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        margin-bottom: var(--space-2);
      }
      .provider-card__name {
        font-weight: 600;
      }
      .provider-card__body {
        margin: 0;
        font-size: 0.875rem;
        color: var(--text-muted);
      }
    `,
  ],
})
export class AiConfigurationPage {
  private readonly api = inject(AiCliApiService);

  protected readonly session = inject(AuthStateService);

  protected readonly feature = signal<AiCliFeatureStatus | null>(null);

  /** CLIs the abstraction supports but that have no adapter yet. */
  protected readonly plannedProviders = [
    { name: 'OpenAI CLI', description: "OpenAI's command-line tool. No adapter installed." },
    { name: 'Gemini CLI', description: "Google's Gemini command-line tool. No adapter installed." },
    { name: 'Ollama CLI', description: 'Local models through Ollama. No adapter installed.' },
  ];

  constructor() {
    if (this.session.has('AI_CLI_VIEW')) {
      this.api.status().subscribe({
        next: (status) => this.feature.set(status),
        error: () => this.feature.set(null),
      });
    }
  }
}
